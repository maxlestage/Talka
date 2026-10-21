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

    let identity: Identity
    var task: Socket?
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
    /// La connexion anonyme (expéditeur scellé), ouverte à la demande.
    var anon: Socket?
    var anonReady = false
    var anonWaiting: [CheckedContinuation<Bool, Never>] = []
    var anonPending: [String: CheckedContinuation<Ack, Never>] = [:]
    /// Les envois partis scellés (et acceptés), depuis le lancement.
    public private(set) var sealedSent = 0
    /// L'identifiant du démarrage du relais connecté (vide : relais ancien).
    public private(set) var boot = ""
    /// L'heure du relais moins la nôtre.
    var skew: Double = 0

    public init(identity: Identity, server: URL) {
        self.identity = identity
        self.server = server
    }

    /// L'heure du relais, en millisecondes (les horodatages des messages).
    public func now() -> Double { nowMs() + skew }

    var wsURL: URL {
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

    func connect() {
        guard !stopped, task == nil else { return }
        let t = openSocket(wsURL)
        task = t
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

    func transmit(_ t: Socket, _ text: String) {
        Task { @MainActor [weak self] in
            do { try await t.send(text) } catch { self?.closed(t) }
        }
    }

    func receive(_ text: String) {
        guard let msg = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else { return }
        switch msg["t"] as? String ?? "" {
        case "challenge":
            if let t = msg["time"] as? Double { skew = t - nowMs() }
            let nonce = msg["nonce"] as? String ?? ""
            let hello: [String: Any] = [
                "t": "hello", "sign": identity.sign, "dh": identity.dh,
                "sig": identity.signChallenge(nonce), "hidden": hidden,
            ]
            if let data = try? JSONSerialization.data(withJSONObject: hello),
               let s = String(data: data, encoding: .utf8), let task {
                transmit(task, s)
            }
        case "ready":
            retry = 0
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

    /// Dépose une enveloppe scellée pour `peer`, avec son jeton de remise.
    public func sendSealed(id: String, peer: Contact, body: String, live: Bool, wake: Bool,
                           apercu: String? = nil) async -> Ack {
        guard await anonConnected(), let anon else { return .failed("offline") }
        var msg: [String: Any] = ["t": "send", "id": id, "to": peer.code, "sign": peer.sign, "dh": peer.dh,
                                  "jeton": peer.jeton, "body": body, "live": live]
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
    public func send(id: String, to: String, body: String, live: Bool, wake: Bool = false,
                     apercu: String? = nil) async -> Ack {
        var msg: [String: Any] = ["t": "send", "id": id, "to": to, "body": body, "live": live]
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
    /// code. Le `ping` qui suit dit si le relais connaît v3.
    @discardableResult
    public func publishBundle(_ bundle: String, sig: String) -> Bool {
        guard raw(["t": "publish-bundle", "bundle": bundle, "sig": sig]), sendPing() else { return false }
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
}

/// Les avertissements du protocole v3 (journal de développement ; jamais de
/// secret).
func v3Log(_ text: String) {
    #if DEBUG
    print(text)
    #endif
}
