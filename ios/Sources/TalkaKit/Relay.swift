// La connexion au relais : une WebSocket qui se présente (signature du
// défi), se reconnecte toute seule, et transporte les enveloppes chiffrées.
// Le même protocole que le navigateur (`client/src/net.rs`), avec la
// publication et la demande des paquets de clés v3 (`publish-bundle`,
// `fetch-bundle`).

import Foundation

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

@MainActor
public final class Relay {
    public enum State: Sendable { case offline, connecting, online }

    /// La réponse du relais à un envoi.
    public enum Ack: Sendable, Equatable {
        case ok
        case failed(String)
    }

    public private(set) var state: State = .offline {
        didSet { if oldValue != state { onState?(state) } }
    }

    /// Le serveur (`https://…`) : sa WebSocket est `wss://…/ws`.
    public var server: URL
    public var hidden = false {
        didSet { if oldValue != hidden { task?.close() } }
    }

    public var onState: ((State) -> Void)?
    public var onEnvelope: (([String: Any]) -> Void)?
    public var onPresence: ((String, Bool, Double?) -> Void)?
    /// Une enveloppe arrive d'une boîte tournante (v4).
    public var onBoxEnvelope: (([String: Any]) -> Void)? {
        get { boxes.onEnvelope }
        set { boxes.onEnvelope = newValue }
    }
    /// Cet appareil a été retiré du compte (v4).
    public var onUnlinked: (() -> Void)?
    /// La clé du relais retenue (v6, §7.16) : vide tant qu'aucune n'est
    /// prouvée. `onRelayKey` reçoit la première prouvée, à retenir.
    public var relayKey = ""
    public var onRelayKey: ((String) -> Void)?
    /// Le relais ne prouve pas sa clé habituelle, ou un ordre d'effacement
    /// n'est pas signé d'elle : il est ignoré (une fois par ouverture).
    public var onUnprovenRelay: (() -> Void)?
    var relayWarned = false
    /// Le défi de la connexion en cours : le relais le signe (v6).
    var nonce = ""

    let identity: Identity
    /// Cet appareil (v4) : sa boîte chez le relais est `<code>/<dev>`.
    public var dev = ""
    /// La connexion anonyme qui lit nos boîtes tournantes (v4).
    public let boxes: BoxSocket
    var task: Socket?
    /// Ce qui attend de partir sur `task`, dans l'ordre.
    var outgoing: [String] = []
    var sendingOn: ObjectIdentifier?
    var retry = 0
    var stopped = false
    var reconnect: Task<Void, Never>?
    var pending: [String: CheckedContinuation<Ack, Never>] = [:]
    var lookups: [String: [CheckedContinuation<[String: Any]?, Never>]] = [:]
    var bundles: [String: [CheckedContinuation<[String: Any]?, Never>]] = [:]
    /// Le relais connaît-il les paquets v3 ? (`nil` : pas encore su.)
    public private(set) var relayV3: Bool?
    var watched = Set<String>()
    /// Les `ping` envoyés et les `pong` reçus sur cette connexion, et le
    /// numéro du `ping` qui suit la publication du paquet : si son `pong`
    /// arrive avant `published`, le relais ne connaît pas v3.
    var pings = 0
    var pongs = 0
    var publishPing: Int?
    /// Le relais sait-il livrer en expéditeur scellé (`ready.sealed`) ?
    public private(set) var sealed = false
    /// Le léger délai au hasard avant un dépôt anonyme (v6) ; coupé par
    /// les tests qui comptent les millisecondes.
    public var jitter = true
    /// La connexion anonyme (expéditeur scellé), ouverte à la demande.
    var anon: Socket?
    var anonReady = false
    var anonWaiting: [CheckedContinuation<Bool, Never>] = []
    var anonPending: [String: CheckedContinuation<Ack, Never>] = [:]
    /// Les envois partis scellés (et acceptés), depuis le lancement.
    public private(set) var sealedSent = 0
    /// Ceux d'entre eux déposés dans une boîte tournante (v4).
    public private(set) var boxSent = 0
    /// L'identifiant du démarrage du relais connecté (vide : relais ancien).
    public private(set) var boot = ""
    /// L'heure du relais moins la nôtre.
    var skew: Double = 0

    public init(identity: Identity, server: URL) {
        self.identity = identity
        self.server = server
        boxes = BoxSocket(url: Self.wsURL(server))
    }

    /// L'heure du relais, en millisecondes (les horodatages des messages).
    public func now() -> Double { nowMs() + skew }

    var wsURL: URL { Self.wsURL(server) }

    /// La WebSocket d'un serveur (`https://…` → `wss://…/ws`).
    public nonisolated static func wsURL(_ server: URL) -> URL {
        var c = URLComponents(url: server, resolvingAgainstBaseURL: false)!
        c.scheme = c.scheme == "http" ? "ws" : "wss"
        c.path = (c.path.hasSuffix("/") ? String(c.path.dropLast()) : c.path) + "/ws"
        return c.url!
    }

    public func start() {
        stopped = false
        connect()
    }

    public func stop() {
        stopped = true
        reconnect?.cancel()
        task?.close()
        task = nil
        anon?.close()
        boxes.close()
        state = .offline
    }

    /// Le réseau est revenu, ou l'application revient au premier plan.
    public func wake() {
        if state == .offline, !stopped {
            reconnect?.cancel()
            retry = 0
            connect()
        }
    }

    /// Retour au premier plan après une veille : les connexions ont pu
    /// mourir sans le dire (elles semblent ouvertes, plus rien n'arrive). On
    /// les rouvre tout de suite : ce qui attend chez le relais arrive, et ce
    /// qui attendait de partir repart (comme le navigateur).
    public func reopen() {
        guard !stopped else { return }
        if let t = task { closed(t) }
        reconnect?.cancel()
        retry = 0
        if let t = anon { anonClosed(t) }
        boxes.reopenNow()
        connect()
    }

    func connect() {
        guard !stopped, task == nil else { return }
        let t = openSocket(wsURL)
        task = t
        outgoing = []
        state = .connecting
        listen(t)
        ping(t)
    }

    func listen(_ t: Socket) {
        Task { @MainActor [weak self] in
            while true {
                let text: String
                do { text = try await t.receive() } catch {
                    self?.closed(t)
                    return
                }
                guard let self, self.task === t else { return }
                self.receive(text)
            }
        }
    }

    /// Un ping régulier : la connexion reste vivante (et se sait morte).
    func ping(_ t: Socket) {
        Task { @MainActor [weak self] in
            while let self, self.task === t {
                try? await Task.sleep(nanoseconds: 25_000_000_000)
                guard self.task === t else { return }
                // Le relais répond `pong` ; une connexion morte échoue ici.
                self.sendPing()
            }
        }
    }

    func closed(_ t: Socket) {
        guard task === t else { return }
        t.close()
        task = nil
        state = .offline
        for (_, c) in pending { c.resume(returning: .failed("offline")) }
        pending.removeAll()
        for (_, list) in lookups { for c in list { c.resume(returning: nil) } }
        lookups.removeAll()
        for (_, list) in bundles { for c in list { c.resume(returning: nil) } }
        bundles.removeAll()
        guard !stopped else { return }
        let delay = min(30.0, pow(2, Double(retry))) * Double.random(in: 0.7...1.3)
        retry = min(retry + 1, 5)
        reconnect = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.connect()
        }
    }

    @discardableResult
    func raw(_ obj: [String: Any]) -> Bool {
        guard state == .online, let task,
              let data = try? JSONSerialization.data(withJSONObject: obj),
              let text = String(data: data, encoding: .utf8)
        else { return false }
        transmit(task, text)
        return true
    }

    /// Les messages partent dans l'ordre où ils ont été écrits (une seule
    /// file par connexion) : le `ping` qui suit une publication ne la double
    /// jamais.
    func transmit(_ t: Socket, _ text: String) {
        guard task === t else { return }
        outgoing.append(text)
        let id = ObjectIdentifier(t)
        guard sendingOn != id else { return }
        sendingOn = id
        Task { @MainActor [weak self] in
            while let self, self.task === t, !self.outgoing.isEmpty {
                let next = self.outgoing.removeFirst()
                do { try await t.send(next) } catch {
                    if self.sendingOn == id { self.sendingOn = nil }
                    self.closed(t)
                    return
                }
            }
            if self?.sendingOn == id { self?.sendingOn = nil }
        }
    }

    func receive(_ text: String) {
        guard let msg = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else { return }
        switch msg["t"] as? String ?? "" {
        case "challenge":
            if let t = msg["time"] as? Double { skew = t - nowMs() }
            let nonce = msg["nonce"] as? String ?? ""
            self.nonce = nonce
            var hello: [String: Any] = [
                "t": "hello", "sign": identity.sign, "dh": identity.dh,
                "sig": identity.signChallenge(nonce), "hidden": hidden,
            ]
            if !dev.isEmpty { hello["dev"] = dev }
            if let data = try? JSONSerialization.data(withJSONObject: hello),
               let s = String(data: data, encoding: .utf8), let task {
                transmit(task, s)
            }
        case "ready":
            retry = 0
            checkRelayKey(msg["relais"] as? String ?? "", msg["relais_sig"] as? String ?? "")
            boot = msg["boot"] as? String ?? ""
            sealed = msg["sealed"] as? Bool ?? false
            relayV3 = nil
            pings = 0
            pongs = 0
            publishPing = nil
            state = .online
            if !watched.isEmpty { raw(["t": "watch", "codes": Array(watched)]) }
        case "ack", "error":
            let id = msg["id"] as? String ?? ""
            if let c = pending.removeValue(forKey: id) {
                c.resume(returning: msg["t"] as? String == "ack" ? .ok : .failed(msg["error"] as? String ?? "error"))
            }
        case "msg":
            onEnvelope?(msg)
        case "keys":
            let code = msg["code"] as? String ?? ""
            let found = (msg["unknown"] as? Bool ?? false) ? nil : msg
            for c in lookups.removeValue(forKey: code) ?? [] { c.resume(returning: found) }
        case "bundle":
            let code = msg["code"] as? String ?? ""
            for c in bundles.removeValue(forKey: code) ?? [] { c.resume(returning: msg) }
        // La réponse au `ping` qui suit la publication du paquet : si elle
        // arrive avant `published`, le relais ne connaît pas v3 (les réponses
        // arrivent dans l'ordre des demandes).
        case "pong":
            pongs += 1
            if relayV3 == nil, let p = publishPing, pongs >= p {
                relayV3 = false
                for (_, list) in bundles { for c in list { c.resume(returning: nil) } }
                bundles.removeAll()
            }
        case "published":
            relayV3 = true
            if msg["ok"] as? Bool != true {
                v3Log("v3 : paquet de clés refusé par le relais (\(msg["error"] as? String ?? "?"))")
            }
        case "presence":
            onPresence?(msg["code"] as? String ?? "", msg["online"] as? Bool ?? false, msg["seen"] as? Double)
        // Cet appareil a été retiré du compte (v4).
        case "unlinked":
            if relaySaysUnlinked(msg["sig"] as? String ?? "") { onUnlinked?() }
        default:
            break
        }
    }

    // ----- L'expéditeur scellé -----
    //
    // Une seconde WebSocket, qui ne se présente pas (`{"t":"anon"}`) : le
    // relais ne sait pas qui l'a ouverte. Elle ne sert qu'à déposer des
    // enveloppes scellées, avec le jeton de remise du destinataire.

    func openAnon() {
        let t = openSocket(wsURL)
        anon = t
        anonReady = false
        Task { @MainActor [weak self] in
            while true {
                let text: String
                do { text = try await t.receive() } catch {
                    self?.anonClosed(t)
                    return
                }
                guard let self, self.anon === t else { return }
                guard let msg = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else { continue }
                switch msg["t"] as? String ?? "" {
                case "challenge":
                    Task { try? await t.send(#"{"t":"anon"}"#) }
                case "ready":
                    self.anonReady = true
                    self.anonDone(true)
                case "ack", "error":
                    if msg["t"] as? String == "ack" { self.sealedSent += 1 }
                    if let c = self.anonPending.removeValue(forKey: msg["id"] as? String ?? "") {
                        c.resume(returning: msg["t"] as? String == "ack" ? .ok : .failed(msg["error"] as? String ?? "error"))
                    }
                default:
                    break
                }
            }
        }
        // Le relais ferme une connexion silencieuse : un ping régulier.
        Task { @MainActor [weak self] in
            while let self, self.anon === t {
                try? await Task.sleep(nanoseconds: 25_000_000_000)
                guard self.anon === t else { return }
                Task { try? await t.send(#"{"t":"ping"}"#) }
            }
        }
    }

    func anonClosed(_ t: Socket) {
        guard anon === t else { return }
        t.close()
        anon = nil
        anonReady = false
        anonDone(false)
        for (_, c) in anonPending { c.resume(returning: .failed("offline")) }
        anonPending.removeAll()
    }

    func anonDone(_ ok: Bool) {
        let waiting = anonWaiting
        anonWaiting.removeAll()
        for c in waiting { c.resume(returning: ok) }
    }

    /// La connexion anonyme, prête (ouverte au besoin, 10 secondes au plus).
    func anonConnected() async -> Bool {
        if anonReady, anon != nil { return true }
        if anon == nil { openAnon() }
        let t = anon
        return await withCheckedContinuation { c in
            anonWaiting.append(c)
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                guard let self, !self.anonReady, self.anon === t else { return }
                if let t { self.anonClosed(t) }
            }
        }
    }

    /// Dépose une enveloppe scellée pour `peer` (son appareil `dev` en v4),
    /// avec son jeton de remise.
    public func sendSealed(id: String, peer: Contact, dev: String = "", body: String, live: Bool, wake: Bool,
                           apercu: String? = nil) async -> Ack {
        var msg: [String: Any] = ["t": "send", "id": id, "to": peer.code, "sign": peer.sign, "dh": peer.dh,
                                  "jeton": peer.jeton, "body": body, "live": live]
        if !dev.isEmpty { msg["dev"] = dev }
        return await anonSend(id: id, msg, wake: wake, apercu: apercu)
    }

    /// Dépose une enveloppe scellée dans une boîte tournante (v4) : ni code,
    /// ni jeton. `ttl` : combien de temps le relais la garde (secondes).
    public func sendBoite(id: String, boite: String, body: String, live: Bool, wake: Bool,
                          apercu: String? = nil, ttl: Int? = nil) async -> Ack {
        var msg: [String: Any] = ["t": "send", "id": id, "boite": boite, "body": body, "live": live]
        if let ttl { msg["ttl"] = ttl }
        let ack = await anonSend(id: id, msg, wake: wake, apercu: apercu)
        if ack == .ok { boxSent += 1 }
        return ack
    }

    func anonSend(id: String, _ message: [String: Any], wake: Bool, apercu: String?) async -> Ack {
        // Un léger délai au hasard (v6, §7.15) : l'heure du dépôt ne colle
        // plus à la frappe. Pas pour ce qui est en direct, ni pour la liaison
        // d'un appareil (`ttl`).
        if message["live"] as? Bool != true, message["ttl"] == nil, jitter {
            try? await Task.sleep(nanoseconds: UInt64.random(in: 200_000_000...2_000_000_000))
        }
        guard await anonConnected(), let anon else { return .failed("offline") }
        var msg = message
        if wake {
            msg["wake"] = true
            if let apercu { msg["apercu"] = apercu }
        }
        guard let data = try? JSONSerialization.data(withJSONObject: msg),
              let text = String(data: data, encoding: .utf8) else { return .failed("crypto") }
        return await withCheckedContinuation { c in
            anonPending[id] = c
            Task { @MainActor [weak self] in
                do { try await anon.send(text) } catch { self?.anonClosed(anon) }
            }
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                self?.anonPending.removeValue(forKey: id)?.resume(returning: .failed("timeout"))
            }
        }
    }

    /// Envoie une enveloppe ; tenue quand le relais l'a reçue. `wake` : un vrai
    /// message, qui réveille un destinataire hors ligne (notifications du
    /// navigateur) ; pas un accusé de lecture, une réaction ou un profil.
    /// `apercu` : ce que la notification affiche, chiffré pour le destinataire.
    public func send(id: String, to: String, dev: String = "", body: String, live: Bool, wake: Bool = false,
                     apercu: String? = nil) async -> Ack {
        var msg: [String: Any] = ["t": "send", "id": id, "to": to, "body": body, "live": live]
        if !dev.isEmpty { msg["dev"] = dev }
        if wake {
            msg["wake"] = true
            if let apercu { msg["apercu"] = apercu }
        }
        guard raw(msg) else { return .failed("offline") }
        return await withCheckedContinuation { c in
            pending[id] = c
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                self?.pending.removeValue(forKey: id)?.resume(returning: .failed("timeout"))
            }
        }
    }

    public func got(_ numbers: [Int]) {
        if !numbers.isEmpty { raw(["t": "got", "n": numbers]) }
    }

    /// Les clés publiques d'un code (`nil` si le relais ne le connaît pas, ou
    /// sans réseau).
    public func lookup(_ code: String) async -> [String: Any]? {
        guard raw(["t": "lookup", "code": code]) else { return nil }
        return await withCheckedContinuation { c in
            lookups[code, default: []].append(c)
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                guard let self, var list = self.lookups[code], !list.isEmpty else { return }
                list.removeFirst().resume(returning: nil)
                self.lookups[code] = list.isEmpty ? nil : list
            }
        }
    }

    /// Publie le paquet de clés v3 (base64) et sa signature par la clé du
    /// code ; `dev` : celui de cet appareil (v4), sinon celui des anciens
    /// clients. Le `ping` qui suit dit si le relais connaît v3.
    @discardableResult
    public func publishBundle(_ bundle: String, sig: String, dev: String = "") -> Bool {
        var msg: [String: Any] = ["t": "publish-bundle", "bundle": bundle, "sig": sig]
        if !dev.isEmpty { msg["dev"] = dev }
        guard raw(msg), sendPing() else { return false }
        if relayV3 == nil { publishPing = pings }
        return true
    }

    @discardableResult
    func sendPing() -> Bool {
        guard raw(["t": "ping"]) else { return false }
        pings += 1
        return true
    }

    /// La réponse du relais à la demande du paquet de clés v3 d'un code
    /// (`"none": true` s'il n'en a pas ; `nil` : pas de réponse, sans réseau
    /// ou avec un relais d'avant v3). Le paquet est à vérifier :
    /// `V3.verifyBundle`.
    public func fetchBundle(_ code: String) async -> [String: Any]? {
        guard relayV3 != false, raw(["t": "fetch-bundle", "code": code]) else { return nil }
        return await withCheckedContinuation { c in
            bundles[code, default: []].append(c)
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                guard let self, var list = self.bundles[code], !list.isEmpty else { return }
                list.removeFirst().resume(returning: nil)
                self.bundles[code] = list.isEmpty ? nil : list
            }
        }
    }

    /// Les nonces de nos jetons de remise révoqués (la liste remplace la
    /// précédente) : le relais refuse ensuite les dépôts qui les présentent.
    public func revoke(_ nonces: [String]) {
        _ = raw(["t": "revoke", "nonces": nonces])
    }

    public func watch(_ codes: [String]) {
        let fresh = codes.filter { watched.insert($0).inserted }
        if !fresh.isEmpty { raw(["t": "watch", "codes": fresh]) }
    }

    /// Retire un appareil de notre compte (v4).
    @discardableResult
    public func unlink(_ dev: String) -> Bool {
        raw(["t": "unlink", "dev": dev])
    }
}

// ----- Les boîtes tournantes (v4) ----------------------------------------------
//
// Une troisième WebSocket, anonyme elle aussi, qui ne fait que lire : elle
// s'abonne à nos boîtes (`{"t":"boites"}`) et reçoit ce qui y arrive. Rien ne
// la relie à notre code, sauf l'adresse IP (`docs/SECURITE.md` §7.13). Le
// nouvel appareil s'en sert aussi, avant d'avoir un compte, pour attendre la
// liaison.

@MainActor
public final class BoxSocket {
    let url: URL
    var socket: Socket?
    var ready = false
    var list: [String] = []
    var reopen: Task<Void, Never>?
    /// Ce qui arrive dans une boîte : `{"t":"msg","n","id","boite","body","ts","sealed"}`.
    public var onEnvelope: (([String: Any]) -> Void)?

    public init(url: URL) {
        self.url = url
    }

    /// Les boîtes suivies.
    public var boxes: [String] { list }

    /// Les boîtes à lire (elles remplacent les précédentes) ; aucune : la
    /// connexion se ferme.
    public func listen(_ boxes: [String]) {
        let changed = boxes != list
        list = boxes
        if list.isEmpty {
            close()
            return
        }
        if socket == nil {
            open()
        } else if changed, ready {
            subscribe()
        }
    }

    /// Rouvre la connexion tout de suite, s'il y a des boîtes à lire.
    public func reopenNow() {
        close()
        if !list.isEmpty { open() }
    }

    /// Ferme la connexion (elle ne se rouvre pas d'elle-même).
    public func close() {
        reopen?.cancel()
        reopen = nil
        let t = socket
        socket = nil
        ready = false
        t?.close()
    }

    /// Les messages rangés : le relais les oublie.
    public func got(_ numbers: [Int]) {
        guard !numbers.isEmpty, let socket, ready else { return }
        send(socket, ["t": "got", "n": numbers])
    }

    func send(_ t: Socket, _ obj: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let text = String(data: data, encoding: .utf8) else { return }
        Task { try? await t.send(text) }
    }

    func subscribe() {
        guard let socket else { return }
        // Par paquets de 4 000 (le relais en prend 8 000 par demande).
        var start = 0
        while start < list.count {
            let part = Array(list[start..<min(start + 4_000, list.count)])
            send(socket, ["t": "boites", "list": part])
            start += 4_000
        }
    }

    func open() {
        let t = openSocket(url)
        socket = t
        ready = false
        Task { @MainActor [weak self] in
            while true {
                let text: String
                do { text = try await t.receive() } catch {
                    self?.closed(t)
                    return
                }
                guard let self, self.socket === t else { return }
                guard let msg = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else { continue }
                switch msg["t"] as? String ?? "" {
                case "challenge":
                    Task { try? await t.send(#"{"t":"anon"}"#) }
                case "ready":
                    self.ready = msg["boites"] as? Bool ?? false
                    if self.ready { self.subscribe() }
                case "msg":
                    self.onEnvelope?(msg)
                default:
                    break
                }
            }
        }
        // Le relais ferme une connexion silencieuse : un ping régulier.
        Task { @MainActor [weak self] in
            while let self, self.socket === t {
                try? await Task.sleep(nanoseconds: 25_000_000_000)
                guard self.socket === t else { return }
                Task { try? await t.send(#"{"t":"ping"}"#) }
            }
        }
    }

    func closed(_ t: Socket) {
        guard socket === t else { return }
        t.close()
        socket = nil
        ready = false
        // Elle se rouvre tant qu'il y a des boîtes à lire.
        guard !list.isEmpty else { return }
        reopen?.cancel()
        reopen = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Double.random(in: 2...5) * 1_000_000_000))
            guard !Task.isCancelled, let self, self.socket == nil, !self.list.isEmpty else { return }
            self.open()
        }
    }
}

/// Les avertissements du protocole v3 (journal de développement ; jamais de
/// secret).
// ----- La clé du relais (v6, docs/SECURITE.md §7.16) -----

public enum RelayKey {
    /// La clé du relais officiel, si elle est connue d'avance (sinon, la
    /// première prouvée est retenue).
    public static let official = ""

    public static func readyMessage(nonce: String, code: String, dev: String) -> Data {
        Data("talka-relais-v6:pret:\(nonce):\(code)/\(dev)".utf8)
    }

    public static func unlinkedMessage(code: String, dev: String) -> Data {
        Data("talka-relais-v6:retire:\(code)/\(dev)".utf8)
    }
}

extension Relay {
    /// La clé attendue du relais : celle des sources, ou celle retenue.
    var pinnedRelayKey: String { RelayKey.official.isEmpty ? relayKey : RelayKey.official }

    /// Le relais vient d'accepter la connexion : une clé prouvée est retenue
    /// la première fois ; une autre ensuite, ou plus de preuve, et Talka le
    /// dit et n'obéit plus à ses ordres d'effacement.
    func checkRelayKey(_ key: String, _ sig: String) {
        let proven = !key.isEmpty && TalkaCrypto.verify(
            sign: key, message: RelayKey.readyMessage(nonce: nonce, code: identity.code, dev: dev), signature: sig)
        let pinned = pinnedRelayKey
        if proven, pinned.isEmpty {
            relayKey = key
            onRelayKey?(key)
        } else if !pinned.isEmpty, !(proven && pinned == key) {
            v3Log("v6 : le relais ne prouve pas sa clé habituelle")
            warnUnproven()
        }
    }

    /// L'ordre « cet appareil est retiré » : à croire seulement s'il est
    /// signé de la clé retenue (sans clé retenue, comme avant).
    func relaySaysUnlinked(_ sig: String) -> Bool {
        let pinned = pinnedRelayKey
        guard !pinned.isEmpty else { return true }
        let ok = TalkaCrypto.verify(
            sign: pinned, message: RelayKey.unlinkedMessage(code: identity.code, dev: dev), signature: sig)
        if !ok {
            v3Log("v6 : ordre d'effacement non signé par le relais, ignoré")
            warnUnproven()
        }
        return ok
    }

    func warnUnproven() {
        guard !relayWarned else { return }
        relayWarned = true
        onUnprovenRelay?()
    }
}

func v3Log(_ text: String) {
    #if DEBUG
    print(text)
    #endif
}
