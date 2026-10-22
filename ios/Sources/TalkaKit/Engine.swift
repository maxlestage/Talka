// Le cœur de Talka sur l'iPhone : contacts, discussions, messages, groupes,
// statuts. La même logique que le navigateur (`client/src/core.rs`) : un
// iPhone et un navigateur discutent ensemble, dans les mêmes groupes.
//
// Un message part en autant d'enveloppes que de destinataires : chacune est
// chiffrée pour son destinataire au moment de partir. En attendant, elle est
// gardée dans `outbox` : un message écrit hors ligne part au retour du réseau.
//
// Avec un contact qui a publié son paquet de clés v3 (voir `V3.swift` et
// `EngineV3.swift`), l'enveloppe porte un jeton de sa session à double
// cliquet ; l'état de la session est écrit avant que rien ne parte, et avec
// le message qui l'a fait avancer à la réception.

import Foundation
import Observation
import BrocolisProto
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

public let dayMs: Double = 86_400_000
/// Une capsule temporelle s'ouvre au plus tard dans dix ans.
public let capsuleMaxMs: Double = 10 * 366 * dayMs

@MainActor
@Observable
public final class Engine {
    public private(set) var me: Identity
    public internal(set) var profile: Profile
    public private(set) var settings: Settings
    public private(set) var contacts: [String: Contact] = [:]
    public private(set) var chats: [String: Chat] = [:]
    /// Les clés Brocolis 🥦 de l'appareil.
    public private(set) var brocolisKeys: [BrocolisKey] = []
    /// code → (en ligne, vu pour la dernière fois).
    public private(set) var presence: [String: (online: Bool, seen: Double?)] = [:]
    /// discussion → codes en train d'écrire.
    public private(set) var typing: [String: Set<String>] = [:]
    public private(set) var netState: Relay.State = .offline
    /// Augmente à chaque changement des messages d'une discussion.
    public private(set) var revisions: [String: Int] = [:]
    public internal(set) var statusRevision = 0
    public private(set) var callRevision = 0
    /// La discussion à l'écran (ses messages arrivent lus).
    public var activeChat: String?
    /// L'application est-elle au premier plan ?
    public var foreground = true

    /// Un message arrive : (discussion, message, déjà à l'écran).
    @ObservationIgnored public var onIncoming: ((Chat, Message, Bool) -> Void)?
    /// Quelqu'un réagit à un de vos messages.
    @ObservationIgnored public var onReaction: ((Chat, String, String) -> Void)?
    /// Un signal d'appel (offre, réponse, candidat…).
    @ObservationIgnored public var onCallSignal: ((String, Body, Bool) -> Void)?
    /// Le relais a refusé un message trop lourd (il est marqué en échec).
    @ObservationIgnored public var onTooLarge: (() -> Void)?

    @ObservationIgnored public let store: Store
    @ObservationIgnored public let relay: Relay
    @ObservationIgnored var flushing = false
    @ObservationIgnored var flushAgain = false
    @ObservationIgnored var seq = 0
    @ObservationIgnored var typingSent: Double = 0
    @ObservationIgnored var typingTimers: [String: Task<Void, Never>] = [:]
    @ObservationIgnored var receipts: [String: Set<String>] = [:]
    @ObservationIgnored var receiptTask: Task<Void, Never>?
    @ObservationIgnored var sweeper: Task<Void, Never>?
    /// Le dernier effacement des messages trop vieux (0 : au prochain passage).
    @ObservationIgnored var lastPrune: Double = 0
    /// L'appareil v3 et les sessions (chargées contact par contact).
    @ObservationIgnored var v3: V3Engine?
    /// Les paquets v3 demandés au relais : (paquet vérifié ou aucun, heure).
    @ObservationIgnored var bundleCache: [String: (bundle: KeyBundle?, at: Double)] = [:]
    /// Les réparations de session v3 récentes (une toutes les cinq minutes
    /// par contact au plus).
    @ObservationIgnored var repairs: [String: Double] = [:]
    /// Cet appareil (v4) : son identifiant, tiré une fois, jamais sauvegardé
    /// ailleurs.
    public internal(set) var dev = ""
    /// Les appareils de notre compte (v4).
    public internal(set) var devices = Devices()
    /// Nos boîtes tournantes suivies (v4) : boîte → le contact qui y écrit ;
    /// et le jour pour lequel elles ont été calculées.
    @ObservationIgnored var boxMap: [String: String] = [:]
    @ObservationIgnored var boxDay = -1
    @ObservationIgnored var boxRefresh: Task<Void, Never>?
    /// Cet appareil a été retiré du compte (v4) : tout vient d'être effacé.
    @ObservationIgnored public var onUnlinked: (() -> Void)?
    /// Le nom de cet appareil dans la liste des appareils du compte (v4) :
    /// l'application le donne (« iPhone », « iPad »).
    public static var deviceName = "iPhone"

    // ----- Le compte -----

    /// Une identité est-elle déjà gardée ?
    public static func hasAccount(_ store: Store) -> Bool {
        store.read("kv", "identity", as: Identity.self)?.isValid ?? false
    }

    /// Crée l'identité et le profil.
    public static func createAccount(_ store: Store, name: String, avatar: String) {
        store.write("kv", "identity", Identity.create())
        store.write("kv", "profile", Profile(name: String(name.trimmingCharacters(in: .whitespaces).prefix(40)), about: "Disponible sur Talka", avatar: avatar, v: 1))
        store.save()
    }

    public init(store: Store, server: URL) {
        self.store = store
        let identity = store.read("kv", "identity", as: Identity.self) ?? Identity.create()
        me = identity
        profile = store.read("kv", "profile", as: Profile.self) ?? Profile()
        settings = store.read("kv", "settings", as: Settings.self) ?? Settings()
        brocolisKeys = Self.loadBrocolis(store)
        relay = Relay(identity: identity, server: server)
        for c in store.all("contacts", as: Contact.self) { contacts[c.code] = c }
        for c in store.all("chats", as: Chat.self) { chats[c.id] = c }
        relay.hidden = settings.hideOnline
        relay.onState = { [weak self] s in self?.onNet(s) }
        relay.onEnvelope = { [weak self] e in self?.onEnvelope(e) }
        relay.onPresence = { [weak self] code, online, seen in
            guard let self else { return }
            let was = self.presence[code]?.online == true
            self.presence[code] = (online, seen)
            // Il revient : il a republié son paquet v3 ; ce qui l'attendait
            // (un contact qui refuse l'ancien format) part maintenant.
            if online, !was, let cached = self.bundleCache[code], cached.bundle == nil {
                self.bundleCache[code] = nil
                Task { await self.flush() }
            }
        }
        relay.onBoxEnvelope = { [weak self] e in self?.onBoxEnvelope(e) }
        relay.onUnlinked = { [weak self] in self?.unlinked() }
        startV3()
        ensureDevice()
    }

    public func start() {
        relay.start()
        sweep()
        sweeper = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                self?.sweep()
                // Ce qui attend un paquet v3 (ou le réseau) est réessayé.
                await self?.flush()
            }
        }
    }

    public func stop() {
        sweeper?.cancel()
        relay.stop()
        store.save()
    }

    func onNet(_ s: Relay.State) {
        netState = s
        if s == .online {
            watchContacts()
            publishV3()
            unlinkGone()
            announceDevices()
            refreshBoxes()
            Task { await flush() }
            Task { await refreshMissingKeys() }
            if relay.sealed { shareJeton() }
        }
    }

    /// Notre profil pour `code`, avec son jeton de remise personnel : pour un
    /// contact, ou quelqu'un qui nous écrit (un membre d'un de nos groupes),
    /// tant qu'il n'est pas bloqué. Comme le navigateur.
    func profileBody(for code: String) -> Body {
        let c = contacts[code]
        let jeton = c.map { !$0.blocked } == true ? me.jeton(for: code, epoch: c?.jetonEpoch ?? 0) : ""
        return .profile(profile, jeton: jeton, nk: "", pj: true, devs: devices.announced, dv: devices.v, bx: true)
    }

    /// Notre jeton de remise, pour les contacts qui n'ont pas encore le leur
    /// (une fois) : ils pourront nous écrire sans que le relais sache qui
    /// écrit. Chacun a le sien ; l'ancien jeton commun est révoqué.
    func shareJeton() {
        for var c in contacts.values where c.saved && !c.blocked && !c.jetonPerso {
            c.jetonPerso = true
            c.jetonSent = true
            saveContact(c)
            enqueue(c.code, Wire(profileBody(for: c.code)))
        }
        if let nonce = me.jeton.split(separator: ".").first { revokeJeton(String(nonce)) }
        sendRevoked()
    }

    /// Les nonces de nos jetons révoqués (contacts bloqués, ancien jeton
    /// commun), redonnés au relais à chaque connexion. Les 1 000 derniers.
    func revokeJeton(_ nonce: String) {
        var list = store.read("kv", "jetons_revoques", as: [String].self) ?? []
        guard !list.contains(nonce) else { return }
        list.append(nonce)
        if list.count > 1_000 { list.removeFirst(list.count - 1_000) }
        store.write("kv", "jetons_revoques", list)
    }

    func sendRevoked() {
        let list = store.read("kv", "jetons_revoques", as: [String].self) ?? []
        if !list.isEmpty { relay.revoke(list) }
    }

    func watchContacts() {
        var codes = Set(contacts.keys)
        for c in chats.values {
            if c.group { codes.formUnion(c.members) } else { codes.insert(c.id) }
        }
        codes.remove(me.code)
        relay.watch(Array(codes))
    }

    func bump(_ chat: String) { revisions[chat, default: 0] += 1 }

    /// Les messages éphémères échus et les statuts de plus de 24 heures.
    /// Mettre en sourdine pour `seconds` secondes (`nil` : pour toujours), ou
    /// réactiver le son (`0`), comme dans le navigateur.
    public func mute(_ chat: String, seconds: Double?) {
        let on = seconds != 0
        updateChat(chat) { c in
            c.muted = on
            c.muteUntil = on ? seconds.map { nowMs() + $0 * 1000 } : nil
        }
    }

    public func sweep() {
        let t = nowMs()
        // Un nouveau jour : de nouvelles boîtes tournantes à lire (v4).
        if boxDay != TalkaV4.day(relay.now()) { refreshBoxes() }
        // Les sourdines pour un temps qui arrivent à leur terme.
        for chat in chats.values where chat.muted && (chat.muteUntil ?? .infinity) <= t {
            updateChat(chat.id) { $0.muted = false; $0.muteUntil = nil }
        }
        for chat in chats.values where chat.timer > 0 || chat.hasExp {
            var changed = false
            var gone: [(String, Double)] = []
            for m in store.messagesOf(chat.id) where (m.exp ?? .infinity) < t {
                store.removeMessage(chat.id, m.id)
                gone.append((m.id, m.at))
                changed = true
            }
            bury(chat.id, gone)
            if changed { refreshLast(chat.id); bump(chat.id) }
        }
        // Les messages trop vieux (3 mois par défaut), une fois par heure.
        if settings.keepDays > 0, t - lastPrune >= 3_600_000 {
            lastPrune = t
            for chat in chats.values {
                var changed = false
                var gone: [(String, Double)] = []
                for m in store.messagesOf(chat.id) where m.tooOld(keepDays: settings.keepDays, now: t) {
                    store.removeMessage(chat.id, m.id)
                    gone.append((m.id, m.at))
                    changed = true
                }
                bury(chat.id, gone)
                if changed { refreshLast(chat.id); bump(chat.id) }
            }
        }
        for s in store.all("statuses", as: StatusItem.self) where s.at + dayMs < t {
            store.remove("statuses", s.key)
        }
        statusRevision += 1
        // Les clés pré-signées v3 : renouvelées chaque semaine, puis republiées.
        if v3?.maintain(now: V3.secs(t)) == true {
            saveV3()
            publishV3()
        }
    }

    public func saveSettings(_ f: (inout Settings) -> Void) {
        let keep = settings.keepDays
        f(&settings)
        store.write("kv", "settings", settings)
        relay.hidden = settings.hideOnline
        if settings.keepDays != keep {
            lastPrune = 0
            sweep()
        }
    }

    public func saveProfile(_ f: (inout Profile) -> Void) {
        f(&profile)
        profile.v += 1
        store.write("kv", "profile", profile)
        sync("profile", d: jsonValue(profile))
        announceProfile()
    }

    /// Notre profil à tous nos contacts.
    func announceProfile() {
        for c in contacts.values where c.saved && !c.blocked {
            enqueue(c.code, Wire(profileBody(for: c.code)))
        }
        Task { await flush() }
    }

    // ----- Lecture -----

    public func contact(_ code: String) -> Contact? { contacts[code] }
    public func chat(_ id: String) -> Chat? { chats[id] }
    public func isGroup(_ id: String) -> Bool { id.hasPrefix("g:") }
    public func blocked(_ code: String) -> Bool { contacts[code]?.blocked ?? false }

    public func displayName(_ code: String) -> String {
        if code == me.code { return "Vous" }
        if let c = contacts[code] {
            if !c.name.isEmpty { return c.name }
            if !c.profileName.isEmpty { return "~\(c.profileName)" }
        }
        return TalkaCode.pretty(code)
    }

    public func avatarOf(_ code: String) -> String {
        code == me.code ? profile.avatar : contacts[code]?.avatar ?? ""
    }

    public func title(_ chat: Chat) -> String {
        chat.group ? (chat.name.isEmpty ? "Groupe" : chat.name) : displayName(chat.id)
    }

    public func recipients(_ chat: Chat) -> [String] {
        chat.group ? chat.members.filter { $0 != me.code } : [chat.id]
    }

    public func messages(_ chat: String, limit: Int = .max) -> [Message] {
        store.messagesOf(chat, limit: limit)
    }

    // ----- Contacts -----

    func saveContact(_ c: Contact) {
        let before = contacts[c.code]
        contacts[c.code] = c
        store.write("contacts", c.code, c)
        // Un correspondant dont on connaît les clés (ou qu'on débloque) : ses
        // boîtes tournantes sont à lire (v4).
        if !c.dh.isEmpty, before?.dh != c.dh || before?.blocked != c.blocked { scheduleBoxRefresh() }
    }

    /// Les clés d'un code, vérifiées : le code doit être leur empreinte.
    func keys(for code: String) async -> Contact? {
        guard let c = await fetchKeys(code) else { return nil }
        if contacts[code]?.sign.isEmpty ?? true { saveContact(c) }
        return c
    }

    /// Les clés d'un code (gardées, ou demandées au relais et vérifiées), sans
    /// rien enregistrer : un message scellé n'ajoute son expéditeur qu'une
    /// fois prouvé (l'enveloppe intérieure ouverte).
    func fetchKeys(_ code: String) async -> Contact? {
        if let c = contacts[code], !c.sign.isEmpty, !c.dh.isEmpty { return c }
        guard let found = await relay.lookup(code),
              let sign = found["sign"] as? String, let dh = found["dh"] as? String,
              TalkaCrypto.verifyKeys(code: code, sign: sign, dh: dh)
        else { return nil }
        // Relu après l'attente : un nom donné entre-temps n'est pas écrasé.
        var c = contacts[code] ?? Contact(code: code)
        // Le lien d'invitation donnait l'empreinte de ses clés : ce ne sont
        // pas elles. Quelqu'un d'autre se présente sous ce code.
        if !c.fp.isEmpty, TalkaCode.fingerprint(sign: sign, dh: dh) != c.fp {
            // Une fois par session (pas à chaque reconnexion).
            if mismatched.insert(code).inserted { onKeysMismatch?(code) }
            return nil
        }
        c.sign = sign
        c.dh = dh
        return c
    }

    /// Les clés trouvées pour un code ne sont pas celles de son lien.
    public var onKeysMismatch: ((String) -> Void)?
    /// Les empreintes des liens d'invitation ouverts, par code.
    var fingerprints: [String: String] = [:]
    /// Les codes dont les clés trouvées ne sont pas celles du lien.
    var mismatched: Set<String> = []

    /// Un lien d'invitation donne l'empreinte des clés de ce code : elle sera
    /// vérifiée quand il sera ajouté.
    public func expectFingerprint(_ code: String, _ fp: String) {
        if TalkaCode.validFingerprint(fp) { fingerprints[code] = fp }
    }

    func refreshMissingKeys() async {
        for c in contacts.values where c.sign.isEmpty {
            if await keys(for: c.code) != nil { await flush() }
        }
    }

    public enum AddError: Error, LocalizedError {
        case yourself, keysMismatch
        public var errorDescription: String? {
            switch self {
            case .yourself: "C'est votre propre code 🙂"
            case .keysMismatch: "Les clés de ce code ne correspondent pas au lien d'invitation : quelqu'un se fait peut-être passer pour lui."
            }
        }
    }

    /// Ajoute un contact par son code ; `true` s'il est joignable tout de suite.
    @discardableResult
    public func addContact(_ code: String, name: String) async throws -> Bool {
        guard code != me.code else { throw AddError.yourself }
        var c = contacts[code] ?? Contact(code: code)
        if let fp = fingerprints.removeValue(forKey: code) {
            if !c.sign.isEmpty, TalkaCode.fingerprint(sign: c.sign, dh: c.dh) != fp { throw AddError.keysMismatch }
            c.fp = fp
        }
        c.saved = true
        // Notre profil part avec son jeton personnel.
        c.jetonPerso = !c.blocked
        c.jetonSent = !c.blocked
        let n = String(name.trimmingCharacters(in: .whitespaces).prefix(40))
        if !n.isEmpty { c.name = n }
        saveContact(c)
        relay.watch([code])
        let reachable = await keys(for: code) != nil
        // Les clés trouvées ne sont pas celles du lien : on ne l'ajoute pas (et
        // on ne lui envoie rien).
        if !reachable, mismatched.contains(code) {
            updateContact(code) { $0.saved = false }
            throw AddError.keysMismatch
        }
        enqueue(code, Wire(profileBody(for: code)))
        Task { await flush() }
        if let c = contacts[code] { syncContact(c) }
        return reachable
    }

    public func updateContact(_ code: String, _ f: (inout Contact) -> Void) {
        guard var c = contacts[code] else { return }
        let before = c
        f(&c)
        saveContact(c)
        if before.name != c.name || before.saved != c.saved || before.blocked != c.blocked { syncContact(c) }
    }

    public func setBlocked(_ code: String, _ blocked: Bool) {
        var c = contacts[code] ?? Contact(code: code)
        // Bloqué : son jeton de remise est révoqué ; débloqué, il en recevra
        // un nouveau.
        if blocked, !c.blocked, c.jetonPerso {
            revokeJeton(me.jetonNonce(for: code, epoch: c.jetonEpoch))
            c.jetonEpoch += 1
            c.jetonPerso = false
            sendRevoked()
        }
        let unblocked = !blocked && c.blocked
        let changed = c.blocked != blocked
        c.blocked = blocked
        saveContact(c)
        if unblocked, relay.sealed { shareJeton() }
        if changed { syncContact(c) }
    }

    // ----- Discussions -----

    func saveChat(_ c: Chat) {
        chats[c.id] = c
        store.write("chats", c.id, c)
    }

    @discardableResult
    public func ensureDirectChat(_ code: String) -> Chat {
        if let c = chats[code] { return c }
        let c = Chat(id: code)
        saveChat(c)
        relay.watch([code])
        return c
    }

    public func updateChat(_ id: String, _ f: (inout Chat) -> Void) {
        guard var c = chats[id] else { return }
        let before = ChatState(c)
        f(&c)
        saveChat(c)
        // Archivée, épinglée, en sourdine… : pareil sur nos autres appareils.
        let after = ChatState(c)
        if before != after { sync("chat", c: id, d: jsonValue(after)) }
    }

    // ----- Brocolis 🥦 -----
    //
    // Les clés de l'app Brocolis : dans le trousseau de l'iPhone (voir
    // `BrocolisVault`), dans la base ailleurs. Une clé se partage par Talka
    // (bulle « clé partagée ») et se renouvelle, comme dans le navigateur.

    static let sharedKeyFallback = "🥦 Clé Brocolis partagée : mettez Talka à jour pour l'ajouter."

    static func loadBrocolis(_ store: Store) -> [BrocolisKey] {
        #if os(iOS)
        // Une sauvegarde restaurée apporte ses clés dans la base : elles
        // passent au trousseau.
        if let restored = store.read("kv", "brocolis", as: [BrocolisKey].self) {
            if BrocolisVault.write(restored) { store.remove("kv", "brocolis") }
            return restored
        }
        return BrocolisVault.read() ?? []
        #else
        return store.read("kv", "brocolis", as: [BrocolisKey].self) ?? []
        #endif
    }

    func saveBrocolis() {
        #if os(iOS)
        if BrocolisVault.write(brocolisKeys) {
            store.remove("kv", "brocolis")
            return
        }
        #endif
        store.write("kv", "brocolis", brocolisKeys)
    }

    /// Les clés qui chiffrent ce qui part dans `c` (celles qui existent encore,
    /// et pas celles qu'on a renouvelées).
    public func chatBrocolis(_ c: Chat) -> [BrocolisKey] {
        brocolisKeys.filter { !$0.retired && c.brocolis.contains($0.id) }
    }

    var allBrocolisKeys: [SymmetricKey] { brocolisKeys.compactMap { Brocolis.key(base64: $0.key) } }

    /// Les clés qui ouvrent les jetons reçus dans `chat` : celles liées à
    /// cette discussion, et celles liées à aucune. Pas la clé partagée avec
    /// quelqu'un d'autre : un jeton capturé ailleurs et renvoyé ici ne s'ouvre
    /// pas (ni ne se recopie en clair dans une réponse). Comme le navigateur.
    func brocolisKeys(for chat: String) -> [SymmetricKey] {
        let used = Set(chats.values.flatMap(\.brocolis))
        let here = Set(chats[chat]?.brocolis ?? [])
        return brocolisKeys.filter { here.contains($0.id) || !used.contains($0.id) }
            .compactMap { Brocolis.key(base64: $0.key) }
    }

    func sealText(_ c: Chat, _ text: String) -> String {
        Brocolis.seal(text, keys: chatBrocolis(c).compactMap { Brocolis.key(base64: $0.key) })
    }

    /// Scelle le texte et la citation de `content` ; `true` si le texte l'a été.
    /// Une clé partagée ne se scelle pas : elle voyage dans le chiffrement de Talka.
    func sealContent(_ c: Chat, _ content: inout Content) -> Bool {
        guard content.kind != "brokey" else { return false }
        let sealed = sealText(c, content.text)
        let bro = sealed != content.text
        content.text = sealed
        if var r = content.reply {
            r.text = sealText(c, r.text)
            content.reply = r
        }
        return bro
    }

    /// Ouvre les jetons d'un message et de sa citation ; `true` si l'un l'a été.
    @discardableResult
    func openMessage(_ m: inout Message) -> Bool {
        var changed = false
        let keys = brocolisKeys(for: m.chat)
        if Brocolis.hasToken(m.text) {
            let o = Brocolis.open(m.text, keys: keys)
            if o.opened > 0 { m.text = o.text; m.bro = true; changed = true }
        }
        if var r = m.reply, Brocolis.hasToken(r.text) {
            let o = Brocolis.open(r.text, keys: keys)
            if o.opened > 0 { r.text = o.text; m.reply = r; changed = true }
        }
        return changed
    }

    public enum BrocolisError: LocalizedError {
        case notAKey, forgotten, unknown
        public var errorDescription: String? {
            switch self {
            case .notAKey: "Ce n'est pas une clé Brocolis (32 octets en base64)."
            case .forgotten: "Cette clé a été oubliée."
            case .unknown: "Clé introuvable."
            }
        }
    }

    /// Ajoute une clé (collée seule, ou dans l'invitation de l'app Brocolis),
    /// puis ouvre les messages déjà reçus qu'elle déverrouille. Une clé déjà
    /// là est renvoyée telle quelle.
    @discardableResult
    public func addBrocolisKey(name: String, pasted: String) throws -> BrocolisKey {
        guard let key = Brocolis.findKey(in: pasted) else { throw BrocolisError.notAKey }
        if let existing = brocolisKeys.first(where: { $0.key == key }) { return existing }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let k = BrocolisKey(id: randomId(6), name: trimmed.isEmpty ? "Clé \(brocolisKeys.count + 1)" : String(trimmed.prefix(40)), key: key)
        brocolisKeys.append(k)
        saveBrocolis()
        reopenMessages()
        return k
    }

    public func renameBrocolisKey(_ id: String, name: String) {
        let trimmed = String(name.trimmingCharacters(in: .whitespaces).prefix(40))
        guard !trimmed.isEmpty, let i = brocolisKeys.firstIndex(where: { $0.id == id }) else { return }
        brocolisKeys[i].name = trimmed
        saveBrocolis()
    }

    /// Oublie une clé : les discussions qu'elle chiffrait n'en ont plus besoin.
    /// Les messages déjà ouverts le restent.
    public func removeBrocolisKey(_ id: String) {
        brocolisKeys.removeAll { $0.id == id }
        saveBrocolis()
        for c in chats.values where c.brocolis.contains(id) {
            updateChat(c.id) { $0.brocolis.removeAll { $0 == id } }
        }
    }

    func retire(_ id: String) {
        guard let i = brocolisKeys.firstIndex(where: { $0.id == id }), !brocolisKeys[i].retired else { return }
        brocolisKeys[i].retired = true
        brocolisKeys[i].name += " (ancienne)"
    }

    /// Renouvelle une clé : une neuve, du même nom, la remplace dans toutes ses
    /// discussions et y est envoyée par Talka ; l'ancienne garde l'historique
    /// lisible mais ne chiffre plus rien. Renvoie le nombre de discussions.
    @discardableResult
    public func renewBrocolisKey(_ id: String) throws -> Int {
        guard let old = brocolisKeys.first(where: { $0.id == id }) else { throw BrocolisError.unknown }
        let fresh = BrocolisKey(id: randomId(6), name: old.name, key: Brocolis.generateKey())
        retire(id)
        brocolisKeys.append(fresh)
        saveBrocolis()
        let using = chats.values.filter { $0.brocolis.contains(id) }
        for c in using {
            updateChat(c.id) { $0.brocolis = $0.brocolis.map { $0 == id ? fresh.id : $0 } }
            shareBrocolisKey(c.id, keyId: fresh.id, replaces: old)
        }
        return using.count
    }

    /// Envoie une clé dans une discussion, par le chiffrement de bout en bout
    /// de Talka : plus besoin de la copier-coller ailleurs.
    ///
    /// Sur le fil, `data` porte la clé et `code` l'empreinte de celle qu'elle
    /// remplace (un renouvellement). L'iPhone, lui, ne garde jamais la clé
    /// dans ses messages une fois qu'elle est dans ses clés : `data` est vidé
    /// et `code` désigne la clé (son `id`).
    @discardableResult
    public func shareBrocolisKey(_ chat: String, keyId: String, replaces: BrocolisKey? = nil) -> Message? {
        guard let k = brocolisKeys.first(where: { $0.id == keyId }) else { return nil }
        let content = Content(kind: "brokey", text: Self.sharedKeyFallback, data: k.key, name: k.name,
                              code: replaces.flatMap { Brocolis.fingerprint($0.key) } ?? "")
        guard var m = send(chat, content) else { return nil }
        forgetSharedKey(&m, k.id)
        return m
    }

    func forgetSharedKey(_ m: inout Message, _ keyId: String) {
        m.data = ""
        m.code = keyId
        store.writeMessage(m)
        bump(m.chat)
    }

    /// La clé d'une bulle « clé partagée », si elle est dans vos clés.
    public func sharedKey(of m: Message) -> BrocolisKey? {
        brocolisKeys.first { m.data.isEmpty ? $0.id == m.code : $0.key == m.data }
    }

    /// Ajoute une clé reçue dans une discussion et l'y active ; si elle en
    /// renouvelle une, l'ancienne n'y chiffre plus (elle garde l'historique).
    @discardableResult
    public func acceptBrocolisKey(_ m: Message) throws -> BrocolisKey {
        if m.data.isEmpty {
            guard let k = sharedKey(of: m) else { throw BrocolisError.forgotten }
            return k
        }
        let k = try addBrocolisKey(name: m.name, pasted: m.data)
        let old = m.code.isEmpty ? nil : brocolisKeys.first { $0.id != k.id && Brocolis.fingerprint($0.key) == m.code }
        if let old {
            retire(old.id)
            saveBrocolis()
        }
        if let c = chats[m.chat] {
            var ids = c.brocolis.filter { $0 != old?.id }
            if !ids.contains(k.id) { ids.append(k.id) }
            setChatBrocolis(m.chat, ids)
        }
        if var stored = store.message(m.chat, m.id) { forgetSharedKey(&stored, k.id) }
        return k
    }

    /// Les clés qui chiffrent ce qui part dans une discussion (vide : aucune).
    public func setChatBrocolis(_ chat: String, _ ids: [String]) {
        updateChat(chat) { $0.brocolis = ids }
        bump(chat)
    }

    /// Les messages gardés avec des jetons qu'une nouvelle clé peut ouvrir.
    func reopenMessages() {
        var touched = Set<String>()
        for var m in store.allMessages() where openMessage(&m) {
            store.writeMessage(m)
            touched.insert(m.chat)
        }
        for chat in touched {
            refreshLast(chat)
            bump(chat)
        }
    }

    // ----- Les pierres tombales -----
    //
    // Un message effacé ne revient pas si un relais rejouait sa vieille
    // enveloppe dans l'ancien format : son identifiant est gardé 31 jours
    // (le relais ne garde rien plus de 30 jours ; un message plus vieux dans
    // l'ancien format est refusé d'office). Comme le navigateur.

    public static let tombWindow: Double = 31 * dayMs

    struct Tomb: Codable { var id: String; var at: Double }

    func bury(_ chat: String, _ msgs: [(String, Double)]) {
        let t = nowMs()
        let key = "tombes:\(chat)"
        var list = store.read("kv", key, as: [Tomb].self) ?? []
        let before = list.count
        list.removeAll { t - $0.at >= Self.tombWindow }
        var changed = list.count != before
        for (id, at) in msgs where t - at < Self.tombWindow && !list.contains(where: { $0.id == id }) {
            list.append(Tomb(id: id, at: at))
            changed = true
        }
        if list.count > 20_000 { list.removeFirst(list.count - 20_000) }
        guard changed else { return }
        if list.isEmpty { store.remove("kv", key) } else { store.write("kv", key, list) }
    }

    func buried(_ chat: String, _ id: String) -> Bool {
        (store.read("kv", "tombes:\(chat)", as: [Tomb].self) ?? []).contains { $0.id == id }
    }

    func buryAll(_ chat: String) {
        let t = nowMs()
        bury(chat, store.messagesOf(chat).filter { t - $0.at < Self.tombWindow }.map { ($0.id, $0.at) })
    }

    /// Une offre d'appel jamais vue (les 200 dernières sont gardées) ?
    func firstOffer(_ callId: String) -> Bool {
        var seen = store.read("kv", "appels_vus", as: [String].self) ?? []
        guard !seen.contains(callId) else { return false }
        seen.append(callId)
        if seen.count > 200 { seen.removeFirst(seen.count - 200) }
        store.write("kv", "appels_vus", seen)
        return true
    }

    public func deleteChat(_ id: String) {
        dropChat(id)
        sync("chat", c: id, d: .object(["deleted": .bool(true)]))
    }

    func dropChat(_ id: String) {
        buryAll(id)
        store.deleteMessages(of: id)
        store.remove("chats", id)
        chats[id] = nil
        bump(id)
    }

    public func clearChat(_ id: String) {
        emptyChat(id)
        sync("chat", c: id, d: .object(["cleared": .bool(true)]))
    }

    func emptyChat(_ id: String) {
        buryAll(id)
        store.deleteMessages(of: id)
        if var c = chats[id] {
            c.last = nil; c.unread = 0; c.mentioned = false
            saveChat(c)
        }
        bump(id)
    }

    /// La recherche dans toutes les discussions, comme le navigateur : les
    /// messages qui contiennent `query` (accents et majuscules ignorés), les
    /// plus récents d'abord. Ni messages système ou supprimés, ni capsule
    /// scellée, ni discussion avec un contact bloqué.
    public func searchMessages(_ query: String, limit: Int = 40) -> [Message] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else { return [] }
        let now = nowMs()
        var found: [Message] = []
        for c in chats.values where c.group || !blocked(c.id) {
            for m in store.messagesOf(c.id)
            where m.kind != "system" && !m.deleted && !m.sealed(for: me.code, now: now)
                && "\(m.text) \(m.name)".range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil {
                found.append(m)
            }
        }
        return Array(found.sorted { $0.at > $1.at }.prefix(limit))
    }

    /// Ce qu'on lit d'un message sur une ligne (épingle, favoris, réponse) :
    /// son texte, sauf s'il est supprimé ou si c'est une capsule encore
    /// scellée pour soi ; sinon son aperçu (« ⏳ Capsule temporelle »…).
    public func readable(_ m: Message, now: Double = nowMs()) -> String {
        m.kind == "text" && !m.deleted && !m.sealed(for: me.code, now: now) ? Brocolis.masked(m.text) : Self.preview(m)
    }

    /// Une discussion en texte, comme le navigateur (« Exporter la
    /// discussion ») : (nom du fichier, texte). Ce qu'on lit dans la
    /// discussion, rien d'autre : une capsule scellée reste scellée.
    public func exportChat(_ chatId: String, now: Double = nowMs()) -> (filename: String, text: String)? {
        guard let c = chat(chatId) else { return nil }
        let t = title(c)
        var out = "Discussion Talka « \(t) », exportée le \(exportStamp(now)).\nChiffrée de bout en bout dans Talka ; ce fichier, lui, ne l'est pas.\n\n"
        for m in messages(chatId) where (m.exp ?? .infinity) > now {
            let author: String?
            if m.kind == "system" || m.from == "system" {
                author = nil
            } else if m.from == me.code {
                author = profile.name.isEmpty ? "Vous" : profile.name
            } else {
                author = displayName(m.from)
            }
            out += exportLine(exportStamp(m.at), author: author, text: readable(m, now: now)) + "\n"
        }
        return (exportFilename(t), out)
    }

    public static func preview(_ m: Message) -> String {
        if m.deleted { return "🚫 Message supprimé" }
        // Ni l'aperçu de la liste, ni la notification ne trahissent une capsule.
        if m.isCapsule { return "⏳ Capsule temporelle" }
        switch m.kind {
        case "image": return "📷 \(m.text.isEmpty ? "Photo" : Brocolis.masked(m.text))"
        case "voice": return "🎙️ Message vocal (\(formatDuration(m.dur)))"
        case "file": return "📄 \(m.name.isEmpty ? "Fichier" : m.name)"
        case "contact": return "👤 \(m.name.isEmpty ? "Contact" : m.name)"
        case "brokey": return "🥦 Clé Brocolis « \(m.name) »"
        default: return Brocolis.masked(m.text)
        }
    }

    func lastOf(_ m: Message) -> Last {
        Last(id: m.id, from: m.from, text: Self.preview(m), at: m.at, kind: m.kind)
    }

    func refreshLast(_ chat: String) {
        let last = store.messagesOf(chat, limit: 1).last
        updateChat(chat) { $0.last = last.map(lastOf) }
    }

    func storeMessage(_ m: Message, bump bumpChat: Bool = true) {
        store.writeMessage(m)
        if bumpChat, var c = chats[m.chat] {
            c.last = lastOf(m)
            c.lastAt = max(c.lastAt, m.at)
            if c.archived, m.from != me.code, !c.muted { c.archived = false }
            if m.exp != nil { c.hasExp = true }
            saveChat(c)
        }
        bump(m.chat)
    }

    // ----- Envoi -----

    /// Une enveloppe à envoyer à `to`, gardée jusqu'à ce que le relais confirme ;
    /// `live` : seulement si le destinataire est en ligne, sans garde.
    func enqueue(_ to: String, _ wire: Wire, chat: String = "", msgId: String = "", live: Bool = false) {
        seq += 1
        let item = OutItem(
            id: "\(msgId.isEmpty ? randomId(10) : msgId):\(to)", to: to, payload: wire.json(),
            chat: chat, msgId: msgId, seq: nowMs() * 1000 + Double(seq % 1000))
        if live {
            Task {
                var once = item
                _ = await sendNow(&once, live: true)
            }
            return
        }
        store.write("outbox", item.id, item)
        Task { await flush() }
    }

    /// Comme `enqueue`, pour un seul appareil du destinataire (v4) : les
    /// autres sont marqués comme déjà servis.
    func enqueueTo(_ to: String, dev d: String, _ wire: Wire) {
        let item = OutItem(id: "\(randomId(10)):\(to)", to: to, payload: wire.json(), chat: "", msgId: "",
                           seq: nowMs() * 1000, sent: targets(to).filter { $0 != d })
        store.write("outbox", item.id, item)
        Task { await flush() }
    }

    /// Envoie un élément de la boîte d'envoi : une copie par appareil du
    /// destinataire (v4), chacune chiffrée par sa propre session. Les
    /// appareils déjà servis (`item.sent`) ne la reçoivent pas deux fois.
    func sendNow(_ item: inout OutItem, live: Bool) async -> Relay.Ack {
        let mine = item.to == me.code
        let peer: Contact
        if mine {
            peer = selfContact
        } else {
            guard let found = await keys(for: item.to) else { return .failed("unknown") }
            peer = found
        }
        var failure: Relay.Ack?
        for d in targets(item.to) where !item.sent.contains(d) {
            let ack = await sendOne(item, peer: peer, dev: d, mine: mine, live: live)
            switch ack {
            // Le relais ne connaît plus cet appareil (retiré) : on n'insiste pas.
            case .ok, .failed("dev"):
                item.sent.append(d)
            default:
                if failure == nil { failure = ack }
            }
        }
        return failure ?? .ok
    }

    /// Une copie, pour un appareil (`d` vide : le code, avant la v4).
    func sendOne(_ item: OutItem, peer: Contact, dev d: String, mine: Bool, live: Bool) async -> Relay.Ack {
        let addr = TalkaV4.address(peer.code, d)
        loadV3(addr)
        // « écrit… » et les signaux d'appel ne sont remis qu'à un destinataire
        // en ligne : hors ligne, le relais les jette, et chacun laisserait chez
        // lui une clé sautée dans la session. Ils gardent alors l'ancien format.
        let ratchet = !live || mine || presence[peer.code]?.online == true
        // Sans session, le paquet de l'appareil en ouvre une (s'il en a publié un).
        var bundle: KeyBundle?
        if ratchet, let v3, !v3.hasSession(addr) { bundle = await bundleFor(peer, dev: d) }
        // Chiffré au moment de partir : un nouvel essai chiffre à nouveau (un
        // autre numéro de message) ; le destinataire range par identifiant.
        // Une pièce jointe trop grosse pour un jeton v3, vers un contact dont
        // la version le sait : chiffrée à part, sa clé dans le message v3.
        var payload = item.payload
        var blob: Data?
        if ratchet, peer.pj, payload.utf8.count > Session.maxPlaintext, let split = Attachment.split(payload) {
            payload = split.small
            blob = split.blob
        }
        let sealed = (ratchet ? v3?.sealTo(me, addr: addr, peerCode: peer.code, peerDh: peer.dh, bundle: bundle, plain: payload) : nil)
            ?? TalkaCrypto.seal(me, peerCode: peer.code, peerDh: peer.dh, plain: payload).map { V3.Sealed(body: $0, v3: false) }
        guard var sealed else { return .failed("crypto") }
        // Un contact qui refuse l'ancien format : sans session ni paquet, on
        // attend qu'il en publie un.
        if ratchet, peer.pj, !sealed.v3, payload.utf8.count <= Session.maxPlaintext { return .failed("v3") }
        if let blob { sealed.body += "|" + b64(blob) }
        // La session a avancé : rien ne part avant qu'elle soit écrite (une clé
        // de message ne doit jamais servir deux fois).
        if sealed.v3 { saveV3() }
        // Un identifiant par copie (le relais répond copie par copie).
        let id = d.isEmpty ? item.id : "\(item.id)#\(d)"
        // Nos propres appareils : le relais sait déjà qui nous sommes.
        if mine { return await relay.send(id: id, to: peer.code, dev: d, body: sealed.body, live: live) }
        let wake = !live && !item.msgId.isEmpty
        // Et, s'il a donné sa clé de notification (celle de cet appareil en
        // v4), avec un aperçu chiffré pour elle : le relais le transmet sans
        // pouvoir le lire.
        let nk = d.isEmpty ? peer.nk : (peer.devs.first { $0.d == d }?.nk ?? "")
        let apercu = wake && !nk.isEmpty
            ? JSON.decode(Wire.self, item.payload).flatMap { Self.apercu(me: me.code, name: profile.name, wire: $0) }
                .flatMap { TalkaCrypto.sealApercu(me, nk: nk, plain: $0) }
            : nil
        // Une boîte tournante (v4) : le relais ne sait ni qui écrit, ni à qui.
        if peer.bx, !d.isEmpty, relay.sealed {
            guard let key = TalkaV4.boitesKey(me, peerCode: peer.code, peerDh: peer.dh),
                  let outer = TalkaCrypto.sealSender(from: me.code, fd: dev, to: peer.code, toDh: peer.dh, body: sealed.body)
            else { return .failed("crypto") }
            let boite = TalkaV4.boite(key: key, to: peer.code, dev: d, day: TalkaV4.day(relay.now()))
            return await relay.sendBoite(id: id, boite: boite, body: outer, live: live, wake: wake, apercu: apercu)
        }
        // L'expéditeur scellé : le relais ne sait pas qui écrit (une connexion
        // anonyme, et le jeton de remise du destinataire).
        if !peer.jeton.isEmpty, relay.sealed {
            guard let outer = TalkaCrypto.sealSender(from: me.code, fd: dev, to: peer.code, toDh: peer.dh, body: sealed.body)
            else { return .failed("crypto") }
            let ack = await relay.sendSealed(id: id, peer: peer, dev: d, body: outer, live: live, wake: wake, apercu: apercu)
            // Son jeton ne vaut plus : on l'oublie, et cet envoi part comme
            // avant. Sinon pas de repli : l'envoi réessaiera, toujours scellé.
            guard ack == .failed("jeton") else { return ack }
            updateContact(peer.code) { $0.jeton = "" }
        }
        return await relay.send(id: id, to: item.to, dev: d, body: sealed.body, live: live, wake: wake, apercu: apercu)
    }

    /// L'aperçu d'une notification pour le destinataire, en JSON : qui écrit
    /// (`n`, notre nom choisi), le groupe (`g`), quoi (`t`, comme la liste
    /// des discussions) et la discussion (`c`, vue de chez lui). Seulement
    /// pour les messages écrits ; comme `Wire::apercu` du navigateur.
    public static func apercu(me: String, name: String, wire: Wire) -> String? {
        guard case let .msg(_, _, _, content) = wire.body else { return nil }
        let m = Message(chat: "", id: "", from: me, at: 0, content: content)
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        var obj: [String: String] = [
            "n": trimmed.isEmpty ? TalkaCode.pretty(me) : String(trimmed.prefix(40)),
            "t": String(preview(m).prefix(120)),
            "c": wire.groupId.map { "g:\($0)" } ?? me,
        ]
        if let g = wire.grp, !g.name.isEmpty { obj["g"] = String(g.name.prefix(40)) }
        guard let data = try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys]) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Envoie ce qui attend dans la boîte d'envoi.
    public func flush() async {
        guard relay.state == .online else { return }
        if flushing { flushAgain = true; return }
        flushing = true
        defer { flushing = false }
        repeat {
            flushAgain = false
            let items = store.all("outbox", as: OutItem.self).sorted { $0.seq < $1.seq }
            let boot = relay.boot
            for var item in items {
                guard relay.state == .online else { return }
                // Déjà accepté par le relais : on attend l'accusé de réception,
                // sauf si le relais a redémarré depuis (sa boîte aux lettres,
                // en mémoire, est perdue) : alors on renvoie.
                if !item.boot.isEmpty {
                    if !stillUnconfirmed(item) {
                        store.remove("outbox", item.id)
                        continue
                    }
                    if item.boot == boot || boot.isEmpty { continue }
                    // Le relais a redémarré : ce qui n'a pas été reçu repart
                    // (seulement vers les appareils qui ne l'ont pas).
                    let got = item.got
                    item.sent = item.sent.filter { got.contains($0) }
                }
                let before = item.sent.count
                let ack = await sendNow(&item, live: false)
                // Les accusés arrivés pendant l'envoi (v4 : appareil par appareil).
                for g in store.read("outbox", item.id, as: OutItem.self)?.got ?? [] where !item.got.contains(g) {
                    item.got.append(g)
                }
                // Les appareils déjà servis sont retenus, même si un autre attend.
                if ack != .ok, item.sent.count != before { store.write("outbox", item.id, item) }
                // Nos autres appareils ne comptent pas dans les coches.
                let toOthers = item.to != me.code
                switch ack {
                case .ok:
                    if !item.msgId.isEmpty, !boot.isEmpty {
                        item.boot = boot
                        if stillUnconfirmed(item) { store.write("outbox", item.id, item) } else { store.remove("outbox", item.id) }
                    } else {
                        store.remove("outbox", item.id)
                    }
                    if !item.msgId.isEmpty, toOthers { markSent(item.chat, item.msgId, item.to) }
                case .failed(let e) where e == "size" || e == "code":
                    store.remove("outbox", item.id)
                    if !item.msgId.isEmpty, toOthers {
                        markFailed(item.chat, item.msgId)
                        if e == "size" { onTooLarge?() }
                    }
                case .failed(let e) where e == "offline" || e == "timeout":
                    return
                case .failed:
                    // « unknown » : le destinataire n'a pas rouvert Talka depuis
                    // le redémarrage du relais ; l'enveloppe attend ses clés.
                    break
                }
            }
        } while flushAgain
    }

    /// Un message accepté par le relais attend-il encore l'accusé de réception
    /// de ce destinataire ? Au-delà de 30 jours (la durée des boîtes aux
    /// lettres), on n'insiste plus.
    func stillUnconfirmed(_ item: OutItem) -> Bool {
        guard let m = store.message(item.chat, item.msgId), !m.deleted, nowMs() - m.at < 30 * dayMs else { return false }
        // Pour nos autres appareils : chacun dit l'avoir reçu (`sync`/`got`).
        if item.to == me.code { return siblings.contains { !item.got.contains($0) } }
        // v4 : chaque appareil du destinataire doit l'avoir reçu.
        let devs = targets(item.to).filter { !$0.isEmpty }
        if devs.isEmpty { return !m.deliveredTo.contains(item.to) }
        return devs.contains { !item.got.contains($0) }
    }

    func markSent(_ chat: String, _ id: String, _ to: String) {
        guard var m = store.message(chat, id) else { return }
        if !m.sentTo.contains(to) { m.sentTo.append(to) }
        if m.tick != .pending, m.status == "pending" { m.status = "sent" }
        store.writeMessage(m)
        bump(chat)
    }

    func markFailed(_ chat: String, _ id: String) {
        guard var m = store.message(chat, id) else { return }
        m.status = "failed"
        store.writeMessage(m)
        bump(chat)
    }

    func groupInfo(_ c: Chat) -> GroupInfo {
        GroupInfo(id: String(c.id.dropFirst(2)), name: c.name, members: c.members, admins: c.admins, avatar: c.avatar, v: max(c.v, 1))
    }

    /// Ce qui accompagne chaque envoi : le groupe, la durée de vie des
    /// messages éphémères.
    func envelope(_ c: Chat, _ body: Body) -> Wire {
        Wire(body, grp: c.group ? groupInfo(c) : nil, ttl: c.timer > 0 ? c.timer : nil)
    }

    /// Envoie un message dans une discussion.
    @discardableResult
    public func send(_ chatId: String, _ content: Content) -> Message? {
        guard let c = chats[chatId] else { return nil }
        let id = randomId(12)
        let at = relay.now()
        let to = recipients(c).filter { !blocked($0) }
        var m = Message(chat: chatId, id: id, from: me.code, at: at, content: content)
        m.status = to.isEmpty ? "sent" : "pending"
        m.to = to
        if c.timer > 0 { m.exp = at + Double(c.timer) * 1000 }
        // Brocolis 🥦 : le texte part scellé ; l'appareil garde le clair.
        var sealed = content
        m.bro = sealContent(c, &sealed)
        // Un jeton collé (clavier Brocolis) s'affiche ouvert, s'il est pour vous.
        openMessage(&m)
        storeMessage(m)
        // Le nom choisi voyage avec le message : un membre de groupe qui ne
        // vous connaît pas voit « ~Nom » plutôt qu'un code.
        let wire = envelope(c, .msg(id: id, at: at, pn: profile.name, content: sealed))
        for code in to { enqueue(code, wire, chat: chatId, msgId: id) }
        // Nos autres appareils l'affichent aussi (v4) : gardé, comme pour un
        // destinataire, jusqu'à ce que chacun l'ait (et renvoyé si le relais
        // redémarre entre-temps).
        if !siblings.isEmpty {
            enqueue(me.code, Wire(.sync(op: "wire", c: chatId, w: wire)), chat: chatId, msgId: id)
        }
        if !(chats[chatId]?.draft.isEmpty ?? true) { updateChat(chatId) { $0.draft = "" } }
        return m
    }

    func broadcast(_ c: Chat, _ body: Body, live: Bool = false) {
        let wire = envelope(c, body)
        for code in recipients(c) where !blocked(code) { enqueue(code, wire, live: live) }
        // Ce qui change la discussion (réaction, modification, suppression,
        // minuterie) : refait sur nos autres appareils (v4).
        switch body {
        case .typing, .group: break
        default: if !live { sync("wire", c: c.id, w: wire) }
        }
    }

    public func react(_ chat: String, _ id: String, _ emoji: String) {
        guard let c = chats[chat], var m = store.message(chat, id) else { return }
        let e = m.reactions[me.code] == emoji ? "" : emoji
        if e.isEmpty { m.reactions[me.code] = nil } else { m.reactions[me.code] = e }
        store.writeMessage(m)
        bump(chat)
        broadcast(c, .react(id: id, emoji: e))
    }

    public func edit(_ chat: String, _ id: String, text: String) {
        guard let c = chats[chat], var m = store.message(chat, id), m.from == me.code else { return }
        m.text = text
        m.edited = true
        let sealed = sealText(c, text)
        m.bro = sealed != text
        store.writeMessage(m)
        refreshLast(chat)
        bump(chat)
        broadcast(c, .edit(id: id, text: sealed))
    }

    public func delete(_ chat: String, _ id: String, forEveryone: Bool) {
        guard let c = chats[chat], var m = store.message(chat, id) else { return }
        if forEveryone, m.from == me.code {
            m.deleted = true
            m.text = ""; m.data = ""; m.reactions = [:]
            store.writeMessage(m)
            broadcast(c, .delete(id: id))
        } else {
            store.removeMessage(chat, id)
            bury(chat, [(m.id, m.at)])
            sync("forget", c: chat, d: .object(["ids": .array([.string(m.id)])]))
        }
        refreshLast(chat)
        bump(chat)
    }

    public func toggleStar(_ chat: String, _ id: String) {
        guard var m = store.message(chat, id) else { return }
        m.starred.toggle()
        store.writeMessage(m)
        bump(chat)
        sync("star", c: chat, d: .object(["id": .string(id), "on": .bool(m.starred)]))
    }

    /// Épingle un message en haut de sa discussion ("" : retire l'épingle).
    /// Pour soi : pas envoyé aux correspondants, seulement à nos autres
    /// appareils (comme le navigateur).
    public func setPin(_ chat: String, _ id: String) {
        updateChat(chat) { $0.pinMsg = id }
    }

    /// Le message épinglé d'une discussion, s'il existe encore.
    public func pinnedMessage(_ chat: String) -> Message? {
        guard let id = chats[chat]?.pinMsg, !id.isEmpty, let m = store.message(chat, id), !m.deleted else { return nil }
        if let exp = m.exp, exp <= nowMs() { return nil }
        return m
    }

    public func starred() -> [Message] {
        store.allMessages().filter(\.starred).sorted { $0.at > $1.at }
    }

    /// Le libellé d'une mention (« @Léa ») : le nom que chacun s'est choisi,
    /// le même sur tous les appareils (à défaut, celui qu'on lui a donné).
    /// Comme `core::mention_label` du navigateur.
    public func mentionLabel(_ code: String) -> String {
        if code == me.code { return profile.name }
        guard let c = contacts[code] else { return TalkaCode.pretty(code) }
        if !c.profileName.isEmpty { return c.profileName }
        return c.name.isEmpty ? TalkaCode.pretty(code) : c.name
    }

    /// Les membres d'un groupe à proposer pour « @… », et ceux que `text`
    /// mentionne.
    public func mentionCandidates(_ chat: Chat) -> [(code: String, label: String)] {
        chat.members.filter { $0 != me.code }.map { ($0, mentionLabel($0)) }
    }

    public func forward(_ m: Message, to chats: [String]) {
        var content = m.content
        content.reply = nil
        content.fwd = true
        content.openAt = nil
        // Une mention ne suit pas un message transféré.
        content.mentions = []
        for id in chats { send(id, content) }
    }

    public func setTyping(_ chat: String, _ on: Bool) {
        guard let c = chats[chat], relay.state == .online else { return }
        let t = nowMs()
        if on, t - typingSent < 2500 { return }
        typingSent = on ? t : 0
        broadcast(c, .typing(on: on), live: true)
    }

    public func setTimer(_ chat: String, secs: Int) {
        updateChat(chat) { $0.timer = secs }
        systemMessage(chat, timerText(me.code, secs))
        if let c = chats[chat] { broadcast(c, .timer(secs: secs)) }
    }

    func timerText(_ code: String, _ secs: Int) -> String {
        let who = code == me.code ? "Vous avez" : "\(displayName(code)) a"
        if secs == 0 { return "⏳ \(who) désactivé les messages éphémères." }
        let label = secs >= 90 * 86400 ? "90 jours" : secs >= 7 * 86400 ? "7 jours" : "24 heures"
        return "⏳ \(who) activé les messages éphémères : \(label)."
    }

    func systemMessage(_ chat: String, _ text: String, at: Double = nowMs()) {
        var m = Message(chat: chat, id: randomId(12), from: "system", at: at, content: Content(kind: "system", text: text))
        m.kind = "system"
        storeMessage(m)
    }

    // ----- Groupes -----

    @discardableResult
    public func createGroup(name: String, members: [String], avatar: String) -> Chat {
        var c = Chat(id: "g:\(randomId(12))")
        c.group = true
        c.name = String(name.trimmingCharacters(in: .whitespaces).prefix(60))
        c.avatar = avatar
        c.members = [me.code] + members
        c.admins = [me.code]
        c.createdBy = me.code
        c.v = 1
        saveChat(c)
        systemMessage(c.id, "Vous avez créé le groupe « \(c.name) ».")
        broadcast(c, .group(op: "create", note: ""))
        syncGroup(c, note: "Vous avez créé le groupe « \(c.name) ».")
        return c
    }

    public func updateGroup(_ id: String, note: String, _ f: (inout Chat) -> Void) {
        guard let before = chats[id] else { return }
        var next = before
        f(&next)
        next.v = max(before.v, 1) + 1
        saveChat(next)
        if !note.isEmpty { systemMessage(id, note) }
        broadcast(next, .group(op: "update", note: note))
        syncGroup(next, note: note)
        // Les membres retirés l'apprennent aussi.
        for code in before.members where !next.members.contains(code) && code != me.code {
            enqueue(code, Wire(.group(op: "update", note: note), grp: groupInfo(next)))
        }
    }

    public func leaveGroup(_ id: String) {
        guard var c = chats[id] else { return }
        c.members.removeAll { $0 == me.code }
        c.admins.removeAll { $0 == me.code }
        if c.admins.isEmpty, let first = c.members.first { c.admins = [first] }
        c.v = max(c.v, 1) + 1
        c.left = true
        for code in c.members { enqueue(code, Wire(.group(op: "leave", note: ""), grp: groupInfo(c))) }
        saveChat(c)
        systemMessage(id, "Vous avez quitté le groupe.")
        syncGroup(c, note: "Vous avez quitté le groupe.")
    }

    // ----- Réception -----

    func queueReceipt(_ to: String, _ chat: String, _ ids: [String], _ st: String) {
        receipts["\(to)|\(st)|\(chat)", default: []].formUnion(ids)
        receiptTask?.cancel()
        receiptTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard let self, !Task.isCancelled else { return }
            let batch = self.receipts
            self.receipts = [:]
            for (key, ids) in batch {
                let parts = key.split(separator: "|", maxSplits: 2).map(String.init)
                guard parts.count == 3 else { continue }
                let chat = parts[2]
                let body = Body.receipt(st: parts[1], ids: Array(ids), dev: self.dev)
                self.enqueue(parts[0], Wire(body, g: self.isGroup(chat) ? String(chat.dropFirst(2)) : nil))
            }
        }
    }

    /// Marque une discussion comme lue (accusés de lecture bleus).
    /// Toutes les discussions non lues (archivées comprises) marquées comme
    /// lues, comme dans le navigateur. Renvoie leur nombre.
    @discardableResult
    public func markAllRead() -> Int {
        let ids = chats.values.filter { $0.unread > 0 || $0.markedUnread }.map(\.id)
        for id in ids { markRead(id) }
        return ids.count
    }

    public func markRead(_ chat: String) {
        guard let c = chats[chat] else { return }
        var bySender: [String: [String]] = [:]
        for var m in store.messagesOf(chat, limit: 300) where m.from != me.code && m.from != "system" && !m.readByMe {
            m.readByMe = true
            store.writeMessage(m)
            bySender[m.from, default: []].append(m.id)
        }
        let fresh = !bySender.isEmpty
        if settings.readReceipts {
            for (code, ids) in bySender { queueReceipt(code, chat, ids, "read") }
        }
        if c.unread > 0 || c.markedUnread || c.mentioned {
            updateChat(chat) { $0.unread = 0; $0.markedUnread = false; $0.mentioned = false }
        }
        // Lu ici : lu sur nos autres appareils (sans nouvel accusé).
        if fresh { sync("read", c: chat, d: .object(["at": .number(relay.now())])) }
    }

    /// Lu sur un autre de nos appareils : jusqu'à `at`, sans accusé.
    func readHere(_ chat: String, at: Double) {
        guard var c = chats[chat] else { return }
        for var m in store.messagesOf(chat, limit: 300)
        where m.from != me.code && m.from != "system" && !m.readByMe && m.at <= at + 60_000 {
            m.readByMe = true
            store.writeMessage(m)
        }
        if c.unread > 0 || c.mentioned {
            c.unread = 0
            c.mentioned = false
            saveChat(c)
        }
        bump(chat)
    }

    func onEnvelope(_ env: [String: Any]) {
        let n = env["n"] as? Int
        Task { @MainActor in
            await receive(env)
            if let n { relay.got([n]) }
        }
    }

    /// Une enveloppe arrive d'une boîte tournante (v4) : elle doit venir du
    /// contact à qui est cette boîte.
    func onBoxEnvelope(_ env: [String: Any]) {
        let n = env["n"] as? Int
        let boite = env["boite"] as? String ?? ""
        Task { @MainActor in
            if let contact = boxMap[boite] { await receive(env, boite: contact) }
            if let n { relay.boxes.got([n]) }
        }
    }

    /// `boite` : le contact dont c'est la boîte tournante (l'enveloppe doit
    /// venir de lui).
    func receive(_ env: [String: Any], boite: String? = nil) async {
        // Scellé : l'expéditeur est dans l'enveloppe, que nous seuls ouvrons ;
        // l'enveloppe intérieure le prouve ensuite (sa clé de paire).
        var from = env["from"] as? String ?? ""
        var fd = (env["fd"] as? String).flatMap { TalkaV4.isDev($0) ? $0 : nil } ?? ""
        var body = env["body"] as? String ?? ""
        if env["sealed"] as? Bool == true {
            guard let opened = TalkaCrypto.openSealedFrom(me, body) else { return }
            from = opened.from
            fd = opened.fd
            body = opened.body
        }
        guard !from.isEmpty, !blocked(from) else { return }
        if let boite, boite != from { return }
        // De nous-mêmes : seulement d'un autre de nos appareils.
        let mine = from == me.code
        if mine, fd.isEmpty || fd == dev || !devices.has(fd) { return }
        let peer: Contact
        if mine {
            peer = selfContact
        } else {
            guard let found = await fetchKeys(from) else { return }
            peer = found
        }
        // Une pièce jointe chiffrée à part : `<enveloppe>|<pièce>`.
        var blob: Data?
        if let bar = body.firstIndex(of: "|") {
            blob = unb64(String(body[body.index(after: bar)...]))
            body = String(body[..<bar])
        }
        let addr = TalkaV4.address(from, fd)
        loadV3(addr)
        // Une session ouverte avant de connaître ses appareils (à son code).
        loadV3(from)
        var repair = false
        // Ouvert en v3 : son appareil a publié son paquet.
        var openedV3 = false
        // Le message, et l'état de session qui l'a ouvert : une seule
        // transaction SQLite (le relais ne l'oublie qu'après, par `got`).
        let device: String? = store.transaction {
            let opened = v3?.openFrom(me, addr: addr, peerCode: peer.code, peerDh: peer.dh, body: body, now: V3.secs(nowMs()))
                ?? TalkaCrypto.open(me, peerCode: peer.code, peerDh: peer.dh, body: body).map { .legacy($0) }
                ?? .failed(.envelope)
            let device = saveV3Sessions()
            switch opened {
            case .legacy(let plain), .v3(let plain):
                let legacy: Bool
                if case .legacy = opened { legacy = true } else { legacy = false }
                openedV3 = !legacy
                // De nos appareils : seulement de la synchronisation, en v3.
                if mine {
                    if !legacy, let wire = Wire.parse(Attachment.join(plain, blob: blob)),
                       case let .sync(op, chat, w, d) = wire.body {
                        handleSync(fd, op, chat, w, d)
                    }
                    return device
                }
                // Prouvé : ses clés sont gardées (s'il est nouveau), et son
                // passage en v3.
                let known = contacts[from]
                if known == nil || known!.sign.isEmpty || (!legacy && !known!.v3) {
                    var c = known ?? peer
                    c.sign = peer.sign; c.dh = peer.dh
                    if !legacy { c.v3 = true }
                    saveContact(c)
                }
                let live = env["live"] as? Bool ?? false
                // La pièce jointe chiffrée à part (seulement en v3).
                let full = legacy ? plain : Attachment.join(plain, blob: blob)
                guard let wire = Wire.parse(full) else { break }
                // L'ancien format n'a pas de protection contre le rejeu : refusé
                // d'un contact dont la version chiffre tout en v3 ; d'un contact
                // passé en v3, il ne change plus d'état ; un message de plus de
                // 31 jours est refusé (c'est un rejeu). Hors signaux en direct.
                var refuse = false
                if legacy, !live {
                    if known?.pj == true { refuse = true }
                    else if known?.v3 == true, wire.body.changesState { refuse = true }
                    if case let .msg(_, at, _, _) = wire.body, nowMs() - at > Self.tombWindow { refuse = true }
                }
                if refuse {
                    v3Log("ancien format refusé de \(from)")
                } else {
                    handle(from, wire, ts: env["ts"] as? Double ?? nowMs(), live: live)
                }
            // Déjà lu (le relais l'a renvoyé), ou pas pour nous.
            case .replay, .failed(.envelope):
                break
            case .failed(.v3(let e)):
                v3Log("v3 : message de \(addr) illisible (\(e))")
                // Seulement une session perdue : un vieux message rejoué ne
                // la fait jamais tomber.
                repair = V3.needsRepair(e)
            }
            return device
        }
        // L'appareil (sa mémoire des premiers messages) après le message : un
        // premier message rangé sans elle se relirait comme un doublon.
        if let device { saveV3Device(device) }
        if repair { repairV3(from, dev: fd) }
        // Un appareil dont le relais n'avait pas de paquet (un appareil
        // qu'on vient de lier) : il en a un maintenant, et une session est
        // peut-être ouverte. Ce qui l'attendait part.
        if openedV3, let cached = bundleCache[addr], cached.bundle == nil {
            bundleCache[addr] = nil
            Task { await flush() }
        }
    }

    /// La discussion d'un groupe, créée ou mise à jour par `grp` ; `applied` :
    /// cette description a été appliquée (un groupe ne change que par ses
    /// admins, voir `Chat.accepts`).
    func groupChat(_ from: String, _ grp: GroupInfo, at: Double) -> (chat: Chat?, applied: Bool) {
        let id = "g:\(grp.id)"
        guard var c = chats[id] else {
            // Un nouveau groupe : créé par un de ses admins, qui nous y met.
            guard grp.members.contains(me.code), grp.admins.contains(from), grp.members.contains(from)
            else { return (nil, false) }
            var c = Chat(id: id)
            c.group = true
            c.name = grp.name; c.avatar = grp.avatar; c.members = grp.members; c.admins = grp.admins
            c.createdBy = from; c.v = grp.v
            saveChat(c)
            systemMessage(id, "\(displayName(from)) vous a ajouté au groupe « \(grp.name) ».", at: at)
            relay.watch(grp.members.filter { $0 != me.code })
            return (c, true)
        }
        guard c.accepts(from, grp) else { return (c, false) }
        let wasIn = c.members.contains(me.code)
        c.name = grp.name; c.avatar = grp.avatar; c.members = grp.members; c.admins = grp.admins; c.v = grp.v
        if grp.members.contains(me.code) { c.left = false } else if wasIn { c.left = true }
        saveChat(c)
        return (c, true)
    }

    func handle(_ from: String, _ wire: Wire, ts: Double, live: Bool) {
        switch wire.body {
        case let .profile(p, jeton, nk, pj, devs, dv, bx):
            var c = contacts[from] ?? Contact(code: from)
            // Sa version chiffre tout en v3 (une fois su, ça ne se perd pas).
            if pj, !c.pj {
                c.pj = true
                saveContact(c)
            }
            // Sa clé de notification (une clé P-256 publique, ou rien).
            if nk != c.nk, nk.isEmpty || TalkaCrypto.validNk(nk) {
                c.nk = nk
                saveContact(c)
            }
            // Son jeton de remise, s'il est bien signé par sa clé.
            if jeton != c.jeton, !jeton.isEmpty,
               TalkaCrypto.verifyJeton(to: from, sign: c.sign, dh: c.dh, jeton: jeton) {
                c.jeton = jeton
                saveContact(c)
            }
            // Ses appareils (v4) : la liste la plus récente, bien formée.
            if dv > c.dv, devs.count <= maxDevices,
               devs.allSatisfy({ TalkaV4.isDev($0.d) && ($0.nk.isEmpty || TalkaCrypto.validNk($0.nk)) }) {
                c.devs = devs
                c.dv = dv
                saveContact(c)
                // Un nouvel appareil : on vérifie son paquet au prochain envoi.
                bundleCache = bundleCache.filter { !$0.key.hasPrefix("\(from)/") }
                scheduleBoxRefresh()
            }
            // Il lit ses boîtes tournantes (v4).
            if bx != c.bx {
                c.bx = bx
                saveContact(c)
            }
            if p.v >= c.profileV {
                c.profileName = String(p.name.prefix(40))
                c.about = String(p.about.prefix(140))
                c.avatar = p.avatar.count <= 60_000 ? p.avatar : ""
                c.profileV = p.v
                saveContact(c)
            }
            return
        case .typing(let on):
            let chat = wire.groupId.map { "g:\($0)" } ?? from
            guard chats[chat] != nil else { return }
            let key = "\(chat)|\(from)"
            typingTimers[key]?.cancel()
            if on {
                typing[chat, default: []].insert(from)
                typingTimers[key] = Task { @MainActor [weak self] in
                    try? await Task.sleep(nanoseconds: 6_000_000_000)
                    guard !Task.isCancelled else { return }
                    self?.typing[chat]?.remove(from)
                }
            } else {
                typing[chat]?.remove(from)
            }
            return
        case let .call(op, callId, _, _, _):
            // Une offre d'appel déjà vue (un rejeu) ne fait pas sonner.
            if op == "offer", !firstOffer(callId) { return }
            onCallSignal?(from, wire.body, live)
            return
        case let .status(id, kind, text, bg, data, at):
            guard contacts[from]?.saved == true, ts - at <= dayMs else { return }
            let st = StatusItem(owner: from, id: id, kind: kind, text: String(text.prefix(700)), bg: bg, data: data, at: at)
            store.write("statuses", st.key, st)
            statusRevision += 1
            return
        case .statusSeen(let id):
            let key = "\(me.code)|\(id)"
            if var st = store.read("statuses", key, as: StatusItem.self) {
                st.viewers[from] = ts
                store.write("statuses", key, st)
                statusRevision += 1
            }
            return
        case .statusDelete(let id):
            store.remove("statuses", "\(from)|\(id)")
            statusRevision += 1
            return
        case .unknown:
            return
        default:
            break
        }

        // Le reste appartient à une discussion.
        var chat: Chat?
        var applied = false
        if let grp = wire.grp {
            // L'annonce « vous a ajouté » se range juste avant le premier message.
            var at = ts
            if case let .msg(_, mAt, _, _) = wire.body { at = min(mAt, ts) - 1 }
            (chat, applied) = groupChat(from, grp, at: at)
        } else if let g = wire.g {
            chat = chats["g:\(g)"]
        } else {
            // Un message de groupe hors d'un groupe : ignoré.
            if case .group = wire.body { return }
            chat = ensureDirectChat(from)
        }
        guard var c = chat else { return }
        // Dans un groupe, seuls ses membres parlent ; qui vient de le quitter
        // (sa description appliquée à l'instant) l'annonce encore.
        if c.group, !c.members.contains(from) {
            guard applied, case .group(let op, _) = wire.body, op == "leave" else { return }
        }

        switch wire.body {
        case let .msg(id, at, pn, content):
            // Déjà reçu, ou effacé ici depuis (un rejeu ne le fait pas revenir).
            guard store.message(c.id, id) == nil, !buried(c.id, id) else { return }
            let kinds = ["text", "image", "voice", "file", "contact", "brokey"]
            var safe = content
            // Une clé Brocolis partagée n'en est une que si elle est valide.
            if !kinds.contains(safe.kind) || (safe.kind == "brokey" && Brocolis.key(base64: safe.data) == nil) { safe.kind = "text" }
            safe.text = String(safe.text.prefix(65_536))
            // Une capsule s'ouvre au plus tard dans dix ans.
            let mAt = min(at, ts + 60_000)
            if let open = safe.openAt { safe.openAt = open > mAt ? min(open, mAt + capsuleMaxMs) : nil }
            // Les mentions : seulement des membres du groupe (50 au plus).
            safe.mentions = c.group ? Array(safe.mentions.filter { c.members.contains($0) }.prefix(50)) : []
            var m = Message(chat: c.id, id: id, from: from, at: mAt, content: safe)
            if let ttl = wire.ttl { m.exp = m.at + Double(ttl) * 1000 }
            // La citation : celle de notre copie du message cité, jamais ce que
            // l'expéditeur prétend (« Vous : je te dois 500 € »).
            if var r = m.reply {
                if let orig = store.message(c.id, r.id) {
                    r.from = orig.from
                    r.text = String(readable(orig).prefix(140))
                } else {
                    r.from = ""
                    r.text = String(r.text.prefix(140))
                }
                m.reply = r
            }
            openMessage(&m)
            if var sender = contacts[from], sender.profileName.isEmpty, !pn.isEmpty {
                sender.profileName = String(pn.prefix(40))
                saveContact(sender)
            }
            let active = activeChat == c.id && foreground
            if active {
                m.readByMe = true
            } else {
                c.unread += 1
                // Mentionné : « @ » dans la liste, jusqu'à la lecture.
                if m.mentions.contains(me.code) { c.mentioned = true }
                saveChat(c)
            }
            storeMessage(m)
            queueReceipt(from, c.id, [id], "delivered")
            if active, settings.readReceipts { queueReceipt(from, c.id, [id], "read") }
            // Lu ici, sous nos yeux : lu sur nos autres appareils aussi.
            if active { sync("read", c: c.id, d: .object(["at": .number(m.at)])) }
            // Votre profil, pour qui ne l'a pas encore.
            if var sender = contacts[from], !sender.blocked,
               sender.sentProfileV < profile.v || (!sender.jetonPerso && relay.sealed) {
                sender.sentProfileV = profile.v
                if relay.sealed { sender.jetonSent = true; sender.jetonPerso = true }
                saveContact(sender)
                enqueue(from, Wire(profileBody(for: from)))
            }
            typing[c.id]?.remove(from)
            onIncoming?(chats[c.id] ?? c, m, active)
        case let .receipt(st, ids, rdev):
            for id in ids {
                guard var m = store.message(c.id, id), m.from == me.code else { continue }
                if !m.deliveredTo.contains(from) { m.deliveredTo.append(from) }
                if st == "read", !m.readBy.contains(from) { m.readBy.append(from) }
                store.writeMessage(m)
                // Bien arrivé : plus besoin de le garder pour un renvoi (v4 :
                // quand chacun de ses appareils l'a).
                gotBy("\(id):\(from)", rdev)
            }
            bump(c.id)
        case let .react(id, emoji):
            guard var m = store.message(c.id, id) else { return }
            m.reactions[from] = emoji.isEmpty ? nil : String(emoji.prefix(8))
            store.writeMessage(m)
            bump(c.id)
            if m.from == me.code, !emoji.isEmpty { onReaction?(c, from, emoji) }
        case let .edit(id, text):
            guard var m = store.message(c.id, id), m.from == from, !m.deleted else { return }
            let opened = Brocolis.open(String(text.prefix(65_536)), keys: brocolisKeys(for: c.id))
            m.text = opened.text
            m.bro = opened.opened > 0
            m.edited = true
            store.writeMessage(m)
            refreshLast(c.id)
            bump(c.id)
        case let .delete(id):
            guard var m = store.message(c.id, id), m.from == from else { return }
            m.deleted = true
            m.text = ""; m.data = ""; m.reactions = [:]
            store.writeMessage(m)
            refreshLast(c.id)
            bump(c.id)
        case let .timer(secs):
            updateChat(c.id) { $0.timer = secs }
            systemMessage(c.id, timerText(from, secs), at: ts)
        case let .group(op, note):
            if op == "leave" {
                systemMessage(c.id, "\(displayName(from)) a quitté le groupe.", at: ts)
            } else if op != "create", applied, c.group, note.hasPrefix("Vous avez ") {
                // Seulement les annonces que Talka écrit, d'un admin dont la
                // mise à jour vient d'être appliquée : pas de faux message système.
                systemMessage(c.id, "\(displayName(from)) a " + note.dropFirst("Vous avez ".count).prefix(200), at: ts)
            }
        default:
            break
        }
    }

    // ----- Statuts -----

    public func postStatus(kind: String, text: String, bg: String, data: String) {
        let id = randomId(10)
        let at = relay.now()
        var st = StatusItem(owner: me.code, id: id, kind: kind, text: text, bg: bg, data: data, at: at)
        st.seen = true
        store.write("statuses", st.key, st)
        statusRevision += 1
        sync("status", d: jsonValue(st))
        for c in contacts.values where c.saved && !c.blocked {
            enqueue(c.code, Wire(.status(id: id, kind: kind, text: text, bg: bg, data: data, at: at)))
        }
    }

    public func deleteStatus(_ id: String) {
        store.remove("statuses", "\(me.code)|\(id)")
        statusRevision += 1
        sync("status", d: .object(["deleted": .string(id)]))
        for c in contacts.values where c.saved && !c.blocked { enqueue(c.code, Wire(.statusDelete(id: id))) }
    }

    public func statuses() -> [StatusItem] {
        let t = nowMs()
        return store.all("statuses", as: StatusItem.self).filter { $0.at + dayMs > t }.sorted { $0.at < $1.at }
    }

    public func markStatusSeen(_ st: StatusItem) {
        guard !st.seen, st.owner != me.code else { return }
        var s = st
        s.seen = true
        store.write("statuses", s.key, s)
        statusRevision += 1
        if settings.readReceipts { enqueue(st.owner, Wire(.statusSeen(id: st.id))) }
    }

    // ----- Appels -----

    public func sendCallSignal(_ code: String, _ body: Body) {
        enqueue(code, Wire(body), live: true)
    }

    /// Un appel entrant pris ou refusé ici : nos autres appareils cessent de
    /// sonner (v4).
    public func callTaken(_ id: String) {
        sync("call", d: .object(["id": .string(id)]))
    }

    public func logCall(_ entry: CallEntry) {
        store.write("calls", entry.id, entry)
        callRevision += 1
    }

    public func callLog() -> [CallEntry] {
        store.all("calls", as: CallEntry.self).sorted { $0.at > $1.at }
    }

    public func clearCalls() {
        store.clear("calls")
        callRevision += 1
    }

    // ----- Sauvegarde -----

    /// Le fichier de sauvegarde (JSON) : le même que celui du navigateur.
    public func exportBackup(password: String) -> Data? {
        var dump = store.dump()
        // Les clés Brocolis vivent dans le trousseau : la sauvegarde (chiffrée
        // par son mot de passe) les emporte, pour un autre appareil.
        if let json = try? JSONEncoder().encode(brocolisKeys), let text = String(data: json, encoding: .utf8) {
            let kv = (dump["kv"] as? [[Any]] ?? []).filter { ($0.first as? String) != "brocolis" && ($0.first as? String) != "brocolis_sealed" }
            dump["kv"] = kv + [["brocolis", text]]
        }
        // Les sessions v3 n'y vont pas : restaurées plus tard, en retard sur
        // celles du contact, elles reprendraient des clés de message déjà
        // servies. L'appareil v3 y va, comme l'identité (en clair sur iPhone :
        // il vit dans le trousseau) ; après une restauration, chaque contact
        // ouvre (ou reçoit) une session neuve.
        var kv = (dump["kv"] as? [[Any]] ?? []).filter {
            let key = $0.first as? String ?? ""
            return !key.hasPrefix(V3.sessionPrefix) && key != V3.deviceKey
        }
        if let device = try? loadV3Device() { kv.append([V3.deviceKey, device]) }
        dump["kv"] = kv
        let payload: [String: Any] = ["app": "talka", "at": nowMs(), "data": dump]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let plain = String(data: data, encoding: .utf8),
              let backup = TalkaCrypto.encryptBackup(password: password, plain: plain)
        else { return nil }
        return try? JSONEncoder().encode(backup)
    }

    /// Restaure une sauvegarde (du navigateur ou d'un iPhone) dans `store`.
    public static func importBackup(_ store: Store, password: String, file: Data) throws {
        guard let backup = try? JSONDecoder().decode(Backup.self, from: file) else { throw TalkaCrypto.BackupError.notBackup }
        let plain = try TalkaCrypto.decryptBackup(password: password, file: backup)
        guard let json = (try? JSONSerialization.jsonObject(with: Data(plain.utf8))) as? [String: Any],
              json["app"] as? String == "talka", let data = json["data"] as? [String: Any]
        else { throw TalkaCrypto.BackupError.notBackup }
        store.restore(data)
    }

    public func wipeEverything() {
        stop()
        Self.wipe(store)
    }

    /// Efface tout, même une base qui n'a pas pu s'ouvrir (Talka verrouillé,
    /// code oublié ou 10 codes faux) : messages, clés v3, clés Brocolis.
    public nonisolated static func wipe(_ store: Store) {
        store.wipe()
        #if os(iOS)
        BrocolisVault.clear()
        V3Vault.clear()
        #endif
    }
}

/// `2:05`.
/// Le vocal qui enchaîne quand `id` se termine (comme WhatsApp) : le message
/// juste après, s'il est lui aussi un vocal (supprimé ou expiré : non).
public func nextVoice(after id: String, in messages: [Message], now: Double = nowMs()) -> String? {
    guard let i = messages.firstIndex(where: { $0.id == id }), i + 1 < messages.count else { return nil }
    let next = messages[i + 1]
    guard next.kind == "voice", !next.deleted, next.exp.map({ $0 > now }) ?? true else { return nil }
    return next.id
}

/// « 21/10/2026 23:21 - Léa : Bonjour » (comme `talka_web::model::export_line`).
public func exportLine(_ stamp: String, author: String?, text: String) -> String {
    author.map { "\(stamp) - \($0) : \(text)" } ?? "\(stamp) - \(text)"
}

/// « Talka - Léa.txt », sans les caractères interdits dans un nom de fichier.
public func exportFilename(_ title: String) -> String {
    let clean = String(title.map { "/\\:*?\"<>|".contains($0) || $0.isNewline ? "_" : $0 })
    return "Talka - \(clean.trimmingCharacters(in: .whitespaces)).txt"
}

/// « 21/10/2026 23:21 », à l'heure de l'appareil.
public func exportStamp(_ ms: Double) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "fr_FR")
    f.dateFormat = "dd/MM/yyyy HH:mm"
    return f.string(from: Date(timeIntervalSince1970: ms / 1000))
}

public func formatDuration(_ s: Double) -> String {
    let t = max(0, Int(s.rounded()))
    return "\(t / 60):" + String(format: "%02d", t % 60)
}
