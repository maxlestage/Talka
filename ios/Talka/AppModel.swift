// L'état de l'application : le moteur (TalkaKit), le verrou, les
// notifications, les liens d'invitation, l'appel en cours.

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
        if StoreVault.isProtected {
            store = Store(directory: Store.defaultDirectory(), key: nil)
            vaultLocked = true
            loading = nil
            WatchBridge.shared.start(self)
            return
        }
        store = Store(directory: Store.defaultDirectory(), key: StoreVault.key())
        locked = store.read("kv", "pin", as: PinHash.self) != nil
        adoptBuiltInServer()
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

    /// Une version qui apporte un (nouveau) serveur intégré le fait adopter :
    /// l'adresse saisie avant, faute de mieux, est oubliée. Une adresse choisie
    /// ensuite dans les réglages reste prioritaire.
    func adoptBuiltInServer() {
        let builtIn = Self.builtInServer
        let key = "talka.builtInServer"
        guard !builtIn.isEmpty, UserDefaults.standard.string(forKey: key) != builtIn else { return }
        UserDefaults.standard.set(builtIn, forKey: key)
        if var s = store.read("kv", "settings", as: TalkaKit.Settings.self), !s.server.isEmpty {
            s.server = ""
            store.write("kv", "settings", s)
            store.save()
        }
    }

    // ----- Le serveur -----

    /// Le serveur intégré à l'application (`TalkaServer` dans Info.plist).
    static var builtInServer: String {
        (Bundle.main.object(forInfoDictionaryKey: "TalkaServer") as? String ?? "").trimmingCharacters(in: .whitespaces)
    }

    var serverString: String {
        let s = engine?.settings.server ?? store.read("kv", "settings", as: TalkaKit.Settings.self)?.server ?? ""
        return s.isEmpty ? Self.builtInServer : s
    }

    // ----- Démarrage -----

    func start() {
        guard let url = URL(string: serverString), url.scheme != nil else { return }
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
        e.onKeysMismatch = { [weak self] code in
            self?.show("Attention : les clés de \(TalkaCode.pretty(code)) ne correspondent pas à son lien d'invitation. Quelqu'un se fait peut-être passer pour lui.")
        }
        engine = e
        e.start()
        Sounds.prepare()
    }

    func createAccount(name: String, avatar: String, server: String) {
        Engine.createAccount(store, name: name, avatar: avatar)
        if !server.isEmpty, server != Self.builtInServer {
            var s = store.read("kv", "settings", as: TalkaKit.Settings.self) ?? TalkaKit.Settings()
            s.server = server
            store.write("kv", "settings", s)
        }
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

    func wipe() {
        engine?.wipeEverything()
        engine = nil
        path = []
        locked = false
        UserDefaults.standard.removeObject(forKey: "talka.faceid")
        WatchBridge.shared.updateWidgets()
    }

    func changeServer(_ server: String) {
        engine?.saveSettings { $0.server = server }
        engine?.stop()
        engine = nil
        start()
    }

    // ----- Premier plan, arrière-plan, verrou -----

    func scene(_ phase: ScenePhase) {
        switch phase {
        case .active:
            engine?.foreground = true
            engine?.relay.wake()
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
        ctx.localizedFallbackTitle = "Code de l'iPhone"
        guard (try? await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Ouvrir vos messages")) == true,
              let key = StoreVault.key(context: ctx)
        else { return }
        store = Store(directory: Store.defaultDirectory(), key: key)
        // On vient de montrer son visage : pas de code PIN en plus, si Face ID
        // le remplace déjà.
        locked = hasPin && !faceID
        vaultLocked = false
        adoptBuiltInServer()
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
            show("Le trousseau de l'iPhone a refusé : réessayez.")
        }
    }

    var vaultProtected: Bool { StoreVault.isProtected }

    var hasPin: Bool { store.read("kv", "pin", as: PinHash.self) != nil }

    func setPin(_ pin: String?) {
        if let pin {
            store.write("kv", "pin", TalkaCrypto.hashPin(pin))
        } else {
            store.remove("kv", "pin")
            UserDefaults.standard.removeObject(forKey: "talka.faceid")
        }
        store.save()
    }

    func checkPin(_ pin: String) -> Bool {
        guard let stored = store.read("kv", "pin", as: PinHash.self) else { return true }
        let ok = TalkaCrypto.checkPin(pin, stored)
        // Un code d'avant (PBKDF2) : rehaché avec Argon2id.
        if ok, stored.isOld {
            store.write("kv", "pin", TalkaCrypto.hashPin(pin))
            store.save()
        }
        return ok
    }

    var faceID: Bool {
        get { UserDefaults.standard.bool(forKey: "talka.faceid") }
        set { UserDefaults.standard.set(newValue, forKey: "talka.faceid") }
    }

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
            Notifier.message(title: e.title(chat), body: body, thread: chat.id, sound: e.settings.sound)
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
