// L'état de l'application : le moteur (TalkaKit), le verrou, les
// notifications, les liens d'invitation, l'appel en cours.

import CryptoKit
import LocalAuthentication
import Observation
import SwiftUI
import TalkaKit
import UIKit
import UserNotifications

@MainActor
@Observable
final class AppModel {
    enum Tab: Hashable { case chats, status, calls, settings }

    struct Toast: Equatable {
        let id = UUID()
        let text: String
    }

    /// La base ; protégée par Face ID, elle ne s'ouvre qu'après
    /// `unlockVault` (jusque-là, une base en lecture seule, vide).
    var store: Store
    /// La base attend Face ID (ou le code de l'iPhone).
    var vaultLocked = false
    /// La base attend le code de verrouillage de Talka (qui chiffre sa clé).
    var codeLocked = false
    var engine: Engine?
    var tab: Tab = .chats
    /// Les discussions ouvertes (navigation de l'onglet Discussions).
    var path: [String] = []
    var toast: Toast?
    var locked = false
    var call: CallEngine?
    /// Un code reçu par un lien d'invitation, à ajouter.
    var invite: String?
    /// « Répondre en privé » : le message de groupe à citer dans la discussion
    /// à deux qui s'ouvre.
    var privateReply: Message?
    /// Un résultat de recherche : le message à montrer dans la discussion
    /// qui s'ouvre.
    var reveal: String?
    @ObservationIgnored var hiddenAt: Date?

    /// L'étape de l'écran de chargement (`nil` : Talka est prêt).
    var loading: String? = "Ouverture de Talka…"

    init() {
        if StoreVault.isProtected || StoreVault.isCodeLocked {
            store = Store(directory: Store.defaultDirectory(), key: nil)
            vaultLocked = StoreVault.isProtected
            codeLocked = StoreVault.isCodeLocked
            loading = nil
            WatchBridge.shared.start(self)
            return
        }
        store = Store(directory: Store.defaultDirectory(), key: StoreVault.key())
        locked = store.read("kv", "pin", as: PinHash.self) != nil
        forgetChosenServer()
        if Engine.hasAccount(store) { start() }
        WatchBridge.shared.start(self)
        Task { await finishLoading() }
    }

    /// L'écran de chargement : le temps de retrouver le relais (2,5 s au
    /// plus : hors ligne, on montre tout de suite les discussions gardées).
    func finishLoading() async {
        guard let e = engine else {
            withAnimation(.easeOut(duration: 0.3)) { loading = nil }
            return
        }
        loading = "Connexion à vos contacts…"
        let end = Date().addingTimeInterval(2.5)
        while e.netState != .online, Date() < end {
            try? await Task.sleep(for: .milliseconds(100))
        }
        withAnimation(.easeOut(duration: 0.3)) { loading = nil }
    }

    /// Une adresse de serveur choisie autrefois est oubliée : il n'y a plus
    /// qu'un relais.
    func forgetChosenServer() {
        if var s = store.read("kv", "settings", as: TalkaKit.Settings.self), !s.server.isEmpty {
            s.server = ""
            store.write("kv", "settings", s)
            store.save()
        }
    }

    // ----- Le serveur -----

    /// Le relais de Talka : celui que la CI intègre (`TalkaServer` dans
    /// Info.plist), sinon le relais officiel. Rien à choisir.
    static var builtInServer: String {
        let s = (Bundle.main.object(forInfoDictionaryKey: "TalkaServer") as? String ?? "").trimmingCharacters(in: .whitespaces)
        return URL(string: s)?.host != nil ? s : TalkaServer.official
    }

    var serverString: String { Self.builtInServer }

    // ----- Démarrage -----

    func start() {
        guard let url = URL(string: serverString), url.scheme != nil else { return }
        Engine.deviceName = Self.deviceName
        let e = Engine(store: store, server: url)
        e.onIncoming = { [weak self] chat, message, active in self?.incoming(chat, message, active) }
        e.onReaction = { [weak self] chat, from, emoji in
            guard let self, let e = self.engine else { return }
            if self.path.last != chat.id {
                self.show("\(e.displayName(from)) a réagi \(emoji) à votre message")
            }
        }
        e.onCallSignal = { [weak self] from, body, live in self?.callSignal(from, body, live) }
        e.onTooLarge = { [weak self] in self?.show("Message trop lourd : il n'a pas pu partir.") }
        // Retiré du compte par un autre appareil (v4) : tout est effacé.
        e.onUnlinked = { [weak self] in
            self?.wipe()
            self?.show("Cet appareil a été retiré de votre compte Talka.")
        }
        e.onUnprovenRelay = { [weak self] in
            self?.show("Le serveur ne prouve pas son identité habituelle : Talka ignore ses ordres d'effacement.")
        }
        e.onKeysMismatch = { [weak self] code in
            self?.show("Attention : les clés de \(TalkaCode.pretty(code)) ne correspondent pas à son lien d'invitation. Quelqu'un se fait peut-être passer pour lui.")
        }
        engine = e
        e.start()
        Sounds.prepare()
    }

    func createAccount(name: String, avatar: String) {
        Engine.createAccount(store, name: name, avatar: avatar)
        store.save()
        start()
    }

    func restore(_ data: Data, password: String) throws {
        engine?.stop()
        engine = nil
        try Engine.importBackup(store, password: password, file: data)
        locked = store.read("kv", "pin", as: PinHash.self) != nil
        start()
    }

    /// Ce que montre la liste des appareils du compte (v4).
    static var deviceName: String {
        if ProcessInfo.processInfo.isiOSAppOnMac { return "Mac" }
        return UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
    }

    // ----- Plusieurs appareils (v4) -----

    /// Ce nouvel appareil attend d'être lié : son QR code, et ce qui arrive.
    func makeDeviceLink() -> DeviceLink? {
        guard let url = URL(string: serverString), url.scheme != nil else { return nil }
        return DeviceLink(store: store, server: url)
    }

    /// Le compte reçu par la liaison est rangé : Talka démarre.
    func finishLinking() {
        guard engine == nil, Engine.hasAccount(store) else { return }
        locked = false
        start()
        Task { await finishLoading() }
    }

    /// « Tout effacer » : cet appareil quitte d'abord le compte (nos autres
    /// appareils et nos contacts ne lui écrivent plus), puis efface tout.
    func leaveAndWipe() async {
        await engine?.leaveAccount()
        wipe()
    }

    func wipe() {
        if let e = engine {
            e.wipeEverything()
        } else {
            // Verrouillé : la base n'est pas ouverte, on l'efface quand même.
            Engine.wipe(store)
        }
        engine = nil
        // Plus aucune copie de la clé : une nouvelle, pour une base vide.
        StoreVault.reset()
        store = Store(directory: Store.defaultDirectory(), key: StoreVault.key())
        path = []
        locked = false
        codeLocked = false
        vaultLocked = false
        for key in ["talka.faceid", "talka.essais", "talka.essais.jusqua"] {
            UserDefaults.standard.removeObject(forKey: key)
        }
        WatchBridge.shared.updateWidgets()
    }


    // ----- Premier plan, arrière-plan, verrou -----

    func scene(_ phase: ScenePhase) {
        switch phase {
        case .active:
            engine?.foreground = true
            // Après une veille, les connexions ont pu mourir sans le dire :
            // rouvertes (ce qui attend chez le relais arrive aussitôt).
            if let at = hiddenAt, Date().timeIntervalSince(at) >= 20 {
                engine?.relay.reopen()
            } else {
                engine?.relay.wake()
            }
            if let id = path.last { engine?.markRead(id) }
            if let at = hiddenAt, hasPin, Date().timeIntervalSince(at) >= Double(engine?.settings.lockAfter ?? 0) {
                locked = true
            }
            hiddenAt = nil
            UNUserNotificationCenter.current().setBadgeCount(unreadTotal) { _ in }
        case .background:
            engine?.foreground = false
            hiddenAt = Date()
            store.save()
            // Les widgets et la montre partent avec le dernier état.
            WatchBridge.shared.push()
        default:
            engine?.foreground = false
        }
    }

    /// Face ID (ou le code de l'iPhone) ouvre la clé de la base, puis Talka.
    func unlockVault() async {
        let ctx = LAContext()
        ctx.localizedFallbackTitle = "Code de l'appareil"
        guard (try? await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Ouvrir vos messages")) == true,
              let key = StoreVault.key(context: ctx)
        else { return }
        store = Store(directory: Store.defaultDirectory(), key: key)
        // On vient de montrer son visage : pas de code PIN en plus, si Face ID
        // le remplace déjà.
        locked = hasPin && !faceID
        vaultLocked = false
        forgetChosenServer()
        if Engine.hasAccount(store) { start() }
        loading = "Ouverture de Talka…"
        await finishLoading()
    }

    /// Protéger la base par Face ID (ou retirer cette protection).
    func protectVault(_ on: Bool) async {
        guard let key = store.key else { return }
        if !on {
            // Retirer la protection demande aussi le visage.
            let ctx = LAContext()
            guard (try? await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Retirer la protection")) == true
            else { return }
        }
        if StoreVault.setProtected(on, key: key) {
            show(on ? "Vos messages ne s'ouvrent qu'avec \(biometryName ?? "Face ID") 🔐" : "Protection retirée")
        } else {
            show("Le trousseau de l'appareil a refusé : réessayez.")
        }
    }

    var vaultProtected: Bool { StoreVault.isProtected }

    var hasPin: Bool { store.read("kv", "pin", as: PinHash.self) != nil }

    /// Le code chiffre-t-il la clé de la base ? (Un code d'avant cette
    /// version ne le fait qu'après avoir été tapé une fois.)
    var codeEncrypts: Bool { StoreVault.isCodeLocked }

    func setPin(_ pin: String?) {
        if let pin {
            store.write("kv", "pin", TalkaCrypto.hashPin(pin))
        } else {
            store.remove("kv", "pin")
            UserDefaults.standard.removeObject(forKey: "talka.faceid")
        }
        store.save()
        // La clé de la base suit le code : chiffrée par lui, ou plus.
        if let key = store.key {
            if StoreVault.setCode(pin, key: key) {
                if pin != nil, faceID { _ = StoreVault.setFaceUnlock(true, key: key) }
            } else if pin != nil {
                show("Le trousseau de l'appareil a refusé : vos messages restent protégés par l'appareil seul.")
            }
        }
    }

    func checkPin(_ pin: String) -> Bool {
        guard let stored = store.read("kv", "pin", as: PinHash.self) else { return true }
        let ok = TalkaCrypto.checkPin(pin, stored)
        // Un code d'avant (PBKDF2) : rehaché avec Argon2id.
        if ok, stored.isOld {
            store.write("kv", "pin", TalkaCrypto.hashPin(pin))
            store.save()
        }
        // Un code d'avant cette version : il chiffre désormais la clé.
        if ok, !StoreVault.isCodeLocked, let key = store.key, StoreVault.setCode(pin, key: key), faceID {
            _ = StoreVault.setFaceUnlock(true, key: key)
        }
        return ok
    }

    /// Talka est fermé et sa base chiffrée par le code : le bon code ouvre
    /// la clé (Argon2id, hors du fil principal), puis tout le reste.
    func unlock(code: String) async -> Bool {
        let key = await Task.detached(priority: .userInitiated) { StoreVault.key(code: code) }.value
        guard let key else { return false }
        open(with: key)
        return true
    }

    /// Talka est fermé : Face ID ouvre la copie de la clé liée au visage.
    func unlockColdWithFace() async {
        guard StoreVault.hasFaceUnlock, biometryName != nil else { return }
        let ctx = LAContext()
        ctx.localizedFallbackTitle = ""
        guard (try? await ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Ouvrir vos messages")) == true,
              let key = StoreVault.key(face: ctx)
        else { return }
        open(with: key)
    }

    func open(with key: SymmetricKey) {
        store = Store(directory: Store.defaultDirectory(), key: key)
        codeLocked = false
        locked = false
        forgetChosenServer()
        if Engine.hasAccount(store) { start() }
        loading = "Ouverture de Talka…"
        Task { await finishLoading() }
    }

    var faceID: Bool {
        get { UserDefaults.standard.bool(forKey: "talka.faceid") }
        set {
            UserDefaults.standard.set(newValue, forKey: "talka.faceid")
            // La copie de la clé derrière Face ID suit le réglage.
            if StoreVault.isCodeLocked, let key = store.key {
                _ = StoreVault.setFaceUnlock(newValue, key: key)
            }
        }
    }

    // ----- Les protections de l'écran -----

    /// Après 10 codes faux, tout est effacé.
    var wipeAfterFails = UserDefaults.standard.bool(forKey: "talka.effacer10") {
        didSet { UserDefaults.standard.set(wipeAfterFails, forKey: "talka.effacer10") }
    }

    /// Masquer Talka pendant une capture ou une recopie de l'écran (activé
    /// par défaut).
    var hideWhenCaptured = !UserDefaults.standard.bool(forKey: "talka.capture.visible") {
        didSet { UserDefaults.standard.set(!hideWhenCaptured, forKey: "talka.capture.visible") }
    }

    /// L'écran est-il enregistré, recopié (AirPlay) ou partagé en ce moment ?
    var screenCaptured = UIScreen.main.isCaptured

    /// Face ID ou Touch ID, si l'appareil en a.
    var biometryName: String? {
        let ctx = LAContext()
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else { return nil }
        switch ctx.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        case .opticID: return "Optic ID"
        default: return nil
        }
    }

    func unlockWithBiometry() async {
        guard faceID, biometryName != nil else { return }
        let ok = (try? await LAContext().evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Déverrouiller Talka")) ?? false
        if ok { locked = false }
    }

    // ----- Liens d'invitation -----

    /// `talka://add/K7Q2MX9PRT`, `https://…/c/K7Q2MX9PRT`, `…/app/#add=…`.
    func open(_ url: URL) {
        // Un widget : une discussion (talka://chat/…) ou la liste (talka://chats).
        if url.scheme == "talka", url.host == "chat" || url.host == "chats" {
            if let id = WidgetData.chat(in: url), engine?.chat(id) != nil {
                tab = .chats
                path = [id]
            } else {
                tab = .chats
                path = []
            }
            return
        }
        guard let code = TalkaCode.find(in: url.absoluteString) else { return }
        if let fp = TalkaCode.fingerprint(in: url.absoluteString) { engine?.expectFingerprint(code, fp) }
        if engine?.contact(code)?.saved == true {
            openChat(code)
        } else {
            invite = code
            tab = .chats
        }
    }

    func openChat(_ id: String) {
        engine?.ensureDirectChatIfNeeded(id)
        tab = .chats
        path = [id]
    }

    /// `/c/<empreinte>/<code>` : l'empreinte de nos clés, vérifiée par qui
    /// l'ouvre ; le code reste à la fin pour les versions qui ne la
    /// connaissent pas.
    func inviteLink(_ code: String) -> URL {
        let server = serverString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if let me = engine?.me, me.code == code {
            let fp = TalkaCode.fingerprint(sign: me.sign, dh: me.dh)
            if let url = URL(string: "\(server)/c/\(fp)/\(code)") { return url }
        }
        return URL(string: "\(server)/c/\(code)") ?? URL(string: "talka://add/\(code)")!
    }

    // ----- Notifications et sons -----

    func show(_ text: String) {
        withAnimation { toast = Toast(text: text) }
    }

    var unreadTotal: Int {
        engine?.chats.values.filter { !$0.muted && !$0.archived }.reduce(0) { $0 + $1.unread } ?? 0
    }

    var colorScheme: ColorScheme? {
        switch engine?.settings.theme {
        case "light": .light
        case "dark": .dark
        default: nil
        }
    }

    func incoming(_ chat: Chat, _ message: Message, _ active: Bool) {
        guard let e = engine else { return }
        if active {
            if e.settings.sound { Sounds.sent() }
            return
        }
        // En sourdine, seulement si l'on nous mentionne (« @Léa »).
        guard !chat.muted || message.mentions.contains(e.me.code) else { return }
        let who = e.displayName(message.from)
        let text = message.kind == "text" && !message.isCapsule ? Brocolis.masked(message.text) : Engine.preview(message)
        let body = chat.group ? "\(who) : \(text)" : text
        if UIApplication.shared.applicationState == .active {
            if e.settings.sound { Sounds.incoming() }
            if path.last != chat.id { show("\(e.title(chat)) : \(body)") }
        } else if e.settings.notify {
            // L'écran verrouillé ne montre que ce qu'on a choisi.
            let shown = e.settings.notification(title: e.title(chat), body: body)
            Notifier.message(title: shown.title, body: shown.body, thread: chat.id, sound: e.settings.sound)
        }
        UNUserNotificationCenter.current().setBadgeCount(unreadTotal) { _ in }
        WatchBridge.shared.push()
    }

    // ----- Appels -----

    func startCall(_ peer: String, video: Bool) async {
        guard call == nil else { return show("Un appel est déjà en cours.") }
        guard let e = engine, e.netState == .online else { return show("Pas de connexion au réseau.") }
        let c = CallEngine(engine: e, model: self, peer: peer, video: video, incoming: false, id: randomId(10))
        call = c
        await c.startOutgoing()
    }

    func callSignal(_ from: String, _ body: Body, _ live: Bool) {
        guard case let .call(op, callId, video, sdp, _) = body, let e = engine else { return }
        if op == "offer" {
            guard live else { return } // une vieille offre : l'appel est passé
            if call != nil || e.blocked(from) {
                e.sendCallSignal(from, .call(op: "busy", callId: callId, video: false, sdp: "", cand: nil))
                e.logCall(CallEntry(id: callId, peer: from, video: video, incoming: true, at: nowMs(), dur: 0, missed: true))
                return
            }
            let c = CallEngine(engine: e, model: self, peer: from, video: video, incoming: true, id: callId)
            c.remoteOffer = sdp
            call = c
            c.ring()
            if UIApplication.shared.applicationState != .active, e.settings.notify {
                Notifier.message(title: e.displayName(from), body: video ? "📹 Appel vidéo entrant" : "📞 Appel entrant", thread: "call", sound: true)
            }
            return
        }
        // Pris ou refusé sur un autre de nos appareils (v4) : on cesse de sonner.
        if op == "elsewhere" {
            if from == e.me.code, let c = call, c.id == callId, c.incoming, c.pc == nil { c.takenElsewhere() }
            return
        }
        guard let c = call, c.id == callId, c.peer == from else { return }
        c.signal(body)
    }

    // ----- Sauvegarde -----

    /// Le fichier de sauvegarde chiffrée, prêt à partager.
    /// « talka-sauvegarde-2026-10-21.talka »
    var backupFilename: String {
        "talka-sauvegarde-\(ISO8601DateFormatter().string(from: Date()).prefix(10)).talka"
    }
}

extension Engine {
    /// Ouvre la discussion d'un contact (sans en créer pour un groupe).
    func ensureDirectChatIfNeeded(_ id: String) {
        if chat(id) == nil, !isGroup(id) { ensureDirectChat(id) }
    }
}
