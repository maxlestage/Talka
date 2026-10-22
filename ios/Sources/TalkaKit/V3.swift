// Le protocole v3 de Talka sur l'iPhone : des sessions à double cliquet (une
// clé neuve par message), établies en hybride X25519 + ML-KEM-768, avec
// BrocolisProto (le portage Swift, vérifié octet pour octet, de la crate Rust
// qu'utilise le navigateur). Exactement le même protocole que le navigateur
// (`client/src/v3.rs`) : un iPhone et un navigateur à jour discutent en
// sessions à double cliquet.
//
// - Un appareil v3 par compte (`Device` : IK X25519 + Ed25519, SPK, PQPK
//   ML-KEM-768), créé au premier démarrage, gardé dans le trousseau sur
//   iPhone (`V3Vault`), dans `kv/v3_device` ailleurs ; ses clés pré-signées
//   renouvelées chaque semaine (`V3Engine.maintain`).
// - Le paquet de clés est lié au code : ses octets sont signés par
//   l'identité ECDSA P-256 de Talka, après le contexte `talka-v3-paquet:`.
//   Un paquet n'est accepté que si cette signature vérifie avec la clé de
//   signature du contact, elle-même liée à son code.
// - Envoi : le `Wire` JSON est chiffré par la session du contact (un jeton
//   `[[BROCOLIS:v3:…]]`), puis ce jeton part dans l'enveloppe Talka
//   habituelle : le relais ne voit aucune différence. Sans paquet ni session
//   (un contact qui n'a pas la mise à jour), l'ancien format, exactement.
// - Réception : une enveloppe dont le contenu est un jeton v3 passe par la
//   session (ou `Device.accept` pour un premier message). Un rejeu est
//   ignoré sans bruit.
//
// Les sessions : AD = IK de l'initiateur ‖ IK du répondeur, donc deux
// identifiants de session au plus entre deux appareils (celle que j'ai
// ouverte, celle qu'il a ouverte). Les deux sont gardées : quand les deux
// côtés écrivent le premier message en même temps, chacun lit l'autre, et
// l'envoi suit la dernière session qui a servi.
//
// Avant iOS 26, CryptoKit n'a pas ML-KEM : l'appareil ne publie pas de clé
// ML-KEM, ses sessions sont classiques (X25519 seul), et la fiche du contact
// le dit. Passé à iOS 26, il en ajoute une (`maintain`).

import Foundation
import BrocolisProto

public enum V3 {
    /// Ce que signe l'identité Talka : ce contexte, puis les octets du paquet.
    public static let bundleContext = Data("talka-v3-paquet:".utf8)
    /// Un paquet de clés fait 166 octets (sans ML-KEM) ou 1 418 (avec) ; le
    /// relais en accepte jusqu'à 2 Kio.
    public static let maxBundle = 2048
    /// La clé de l'appareil v3 dans `kv` (hors iPhone, et dans la sauvegarde).
    public static let deviceKey = "v3_device"
    /// Le préfixe des sessions d'un contact dans `kv` (`v3:<code>`). Elles ne
    /// partent pas dans la sauvegarde : un état de cliquet restauré en retard
    /// réutiliserait des clés de message déjà servies.
    public static let sessionPrefix = "v3:"

    /// Cet appareil sait-il faire ML-KEM (iOS 26, ou les tests) ? Sinon, ses
    /// sessions sont classiques.
    public static var postQuantum: Bool { MlKem.isAvailable }

    /// Ce que signe l'identité Talka pour publier un paquet.
    public static func bundleMessage(_ bundle: Data) -> Data { bundleContext + bundle }

    /// Ce que signe l'identité Talka pour publier le paquet d'un appareil (v4,
    /// `docs/SECURITE.md` §7.11) : le paquet est lié au code **et** à
    /// l'appareil.
    public static func deviceBundleMessage(dev: String, _ bundle: Data) -> Data {
        Data("talka-v4-appareil:\(dev):".utf8) + bundle
    }

    /// Le paquet d'un appareil (v4), comme `verifyBundle`.
    public static func verifyDeviceBundle(code: String, sign: String, dh: String, dev: String,
                                          bundle: String, sig: String) -> KeyBundle? {
        guard TalkaV4.isDev(dev), TalkaCrypto.verifyKeys(code: code, sign: sign, dh: dh),
              bundle.utf8.count <= (maxBundle + 2) / 3 * 4,
              let bytes = unb64(bundle), bytes.count <= maxBundle,
              TalkaCrypto.verify(sign: sign, message: deviceBundleMessage(dev: dev, bytes), signature: sig)
        else { return nil }
        return try? KeyBundle.fromBytes(Array(bytes))
    }

    /// L'heure du protocole : des secondes Unix (Talka compte en millisecondes).
    public static func secs(_ ms: Double) -> UInt64 { UInt64(max(ms / 1000, 0)) }

    /// Un paquet publié sur le relais : accepté seulement si `sign` et `dh`
    /// sont bien les clés de `code`, et si `sig` (ECDSA P-256, `r ‖ s`) est
    /// leur signature du paquet. Le paquet lui-même est vérifié (ses
    /// signatures Ed25519, ses clés).
    public static func verifyBundle(code: String, sign: String, dh: String, bundle: String, sig: String) -> KeyBundle? {
        guard TalkaCrypto.verifyKeys(code: code, sign: sign, dh: dh),
              bundle.utf8.count <= (maxBundle + 2) / 3 * 4,
              let bytes = unb64(bundle), bytes.count <= maxBundle,
              TalkaCrypto.verify(sign: sign, message: bundleMessage(bytes), signature: sig)
        else { return nil }
        return try? KeyBundle.fromBytes(Array(bytes))
    }

    /// Vrai si ce texte (le contenu d'une enveloppe Talka) est un jeton v3.
    public static func isToken(_ text: String) -> Bool {
        text.hasPrefix(JetonV3.head) && text.hasSuffix("]]")
    }

    /// Faut-il réparer la session (l'oublier et renvoyer notre profil) ?
    /// Seulement quand elle est **perdue** : session inconnue, ou clé
    /// pré-signée déjà oubliée. Un déchiffrement raté sur une session connue
    /// est un rejeu (le relais peut renvoyer un vieux message, y compris
    /// d'une chaîne plus ancienne que celles que la session retient) ou une
    /// falsification : on l'ignore. Sinon, quiconque rejoue un vieux message
    /// forcerait une remise à zéro de la session.
    public static func needsRepair(_ e: Erreur) -> Bool {
        e == .sessionInconnue || e == .cleInconnue
    }

    /// Une enveloppe ouverte.
    public enum Opened: Equatable {
        /// L'ancien format : le `Wire` JSON.
        case legacy(String)
        /// Un `Wire` JSON sorti d'une session v3.
        case v3(String)
        /// Déjà lu (rejeu, doublon du relais) : à ignorer sans bruit.
        case replay
        /// Illisible : l'enveloppe n'est pas pour nous, ou la session est perdue.
        case failed(Failure)
    }

    /// Pourquoi une enveloppe n'a pas pu être ouverte.
    public enum Failure: Equatable {
        /// Le chiffrement de Talka (pas pour nous, ou abîmée).
        case envelope
        /// Le jeton v3 : session inconnue ou perdue, message altéré…
        case v3(Erreur)
    }

    /// Ce qui part : le corps de l'enveloppe, et s'il porte un jeton v3.
    public struct Sealed: Equatable {
        public var body: String
        public var v3: Bool
    }

    /// La protection d'une discussion avec un contact.
    public enum Protection: Equatable, Sendable {
        /// Une session à double cliquet ; `pq` : établie avec ML-KEM.
        case ratchet(pq: Bool)
        /// L'ancien chiffrement (le contact n'a pas la mise à jour).
        case legacy

        /// Ce que montre la fiche du contact (les mots du navigateur).
        public var label: String {
            switch self {
            case .ratchet(pq: true): "🔐 Session à double cliquet (post-quantique)"
            case .ratchet(pq: false): "🔐 Session à double cliquet (classique)"
            case .legacy: "Ancien chiffrement (le contact n'a pas encore la mise à jour)"
            }
        }
    }

    /// Ce qu'il faut garder après une opération.
    public struct Dirty {
        /// L'appareil, s'il a changé (son JSON).
        public var device: String?
        /// Les sessions des contacts qui ont changé (`kv/v3:<code>`) ; vide :
        /// plus de session.
        public var peers: [(code: String, json: String)] = []
    }
}

func hex(_ id: [UInt8]) -> String { id.map { String(format: "%02x", $0) }.joined() }

/// Les sessions avec un contact (deux au plus : voir plus haut).
final class V3Peer {
    var sessions: [Session] = []
    /// La session qui sert à l'envoi (la dernière qui a servi).
    var current: [UInt8]?

    /// Relit `{"current": "<hex>", "sessions": [<session>, …]}` : la forme du
    /// navigateur.
    static func fromJson(_ json: String) -> V3Peer {
        let peer = V3Peer()
        guard let obj = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] else { return peer }
        for value in obj["sessions"] as? [Any] ?? [] where peer.sessions.count < 2 {
            // (Sur iPhone, `data(withJSONObject:)` s'arrête net sur ce qui
            // n'est pas un objet JSON : vérifié d'abord.)
            guard JSONSerialization.isValidJSONObject(value),
                  let data = try? JSONSerialization.data(withJSONObject: value),
                  let text = String(data: data, encoding: .utf8),
                  let s = try? Session.fromJson(text)
            else { continue }
            peer.sessions.append(s)
        }
        let current = obj["current"] as? String ?? ""
        peer.current = peer.sessions.map(\.sessionId).first { hex($0) == current }
        return peer
    }

    func toJson() -> String {
        let list = sessions.map { $0.toJson() }.joined(separator: ",")
        return "{\"current\":\"\(current.map(hex) ?? "")\",\"sessions\":[\(list)]}"
    }

    func get(_ id: [UInt8]) -> Session? { sessions.first { $0.sessionId == id } }

    /// La session d'envoi : la courante, sinon la plus récente.
    func sending() -> Session? {
        guard let id = current ?? sessions.last?.sessionId else { return nil }
        return get(id)
    }

    /// Range une session (elle remplace celle de même identifiant) et en
    /// fait la session d'envoi.
    func put(_ s: Session) {
        let id = s.sessionId
        sessions.removeAll { $0.sessionId == id }
        sessions.append(s)
        if sessions.count > 2 { sessions.removeFirst() }
        current = id
    }
}

/// L'état v3 de l'appareil : son `Device` et ses sessions, contact par
/// contact. Pur : il ne garde rien lui-même ; ce qui a changé se lit avec
/// `takeDirty`, pour être écrit avec le message qui l'a changé.
public final class V3Engine {
    let device: Device
    var peers: [String: V3Peer] = [:]
    var loaded = Set<String>()
    var deviceDirty: Bool
    var dirty = Set<String>()

    /// L'appareil gardé, ou un neuf (avec ML-KEM si cet appareil le sait)
    /// s'il n'y en a pas encore (ou s'il est illisible). Un appareil neuf est
    /// à garder tout de suite.
    public init(deviceJson: String?, now: UInt64) {
        if let json = deviceJson, let d = try? Device.fromJson(json) {
            device = d
            deviceDirty = false
        } else {
            device = Device.generate(rng: Hasard.system(), now: now, pq: true)
            deviceDirty = true
        }
    }

    /// Les clés pré-signées : renouvelées chaque semaine, les anciennes
    /// effacées un mois après ; une clé ML-KEM ajoutée quand l'appareil
    /// vient de l'apprendre (passé à iOS 26). `true` : le paquet a changé (à
    /// republier).
    public func maintain(now: UInt64) -> Bool {
        if device.needsRotation(now: now) {
            device.rotate(rng: Hasard.system(), now: now)
            deviceDirty = true
            return true
        }
        if MlKem.isAvailable, !device.supportsPq, (try? device.enablePq(rng: Hasard.system(), now: now)) != nil {
            deviceDirty = true
            return true
        }
        return false
    }

    /// Le paquet à publier sur le relais, et sa signature par l'identité
    /// Talka : (paquet, signature), en base64.
    public func published(_ me: Identity) -> (bundle: String, sig: String)? {
        guard let bytes = try? device.bundle().toBytes() else { return nil }
        let data = Data(bytes)
        let sig = me.sign(V3.bundleMessage(data))
        return sig.isEmpty ? nil : (b64(data), sig)
    }

    /// Le paquet de cet appareil (v4), signé pour lui : (paquet, signature).
    public func publishedDevice(_ me: Identity, dev: String) -> (bundle: String, sig: String)? {
        guard let bytes = try? device.bundle().toBytes() else { return nil }
        let data = Data(bytes)
        let sig = me.sign(V3.deviceBundleMessage(dev: dev, data))
        return sig.isEmpty ? nil : (b64(data), sig)
    }

    /// Le paquet courant (les tests).
    public func bundle() -> KeyBundle? { try? device.bundle() }

    /// Les sessions d'un contact sont-elles déjà en mémoire ?
    public func isLoaded(_ code: String) -> Bool { loaded.contains(code) }

    /// Les sessions gardées d'un contact (`nil` : aucune). Sans effet si elles
    /// sont déjà en mémoire.
    public func load(_ code: String, json: String?) {
        guard loaded.insert(code).inserted, let json else { return }
        peers[code] = V3Peer.fromJson(json)
    }

    /// Ce qui a changé depuis le dernier appel.
    public func takeDirty() -> V3.Dirty {
        var out = V3.Dirty()
        if deviceDirty {
            out.device = device.toJson()
            deviceDirty = false
        }
        for code in dirty.sorted() {
            let peer = peers[code]
            out.peers.append((code, peer.map { $0.sessions.isEmpty ? "" : $0.toJson() } ?? ""))
        }
        dirty.removeAll()
        return out
    }

    /// La protection actuelle avec un contact : sa session d'envoi, sinon
    /// `nil` (pas encore de session).
    public func sessionProtection(_ code: String) -> V3.Protection? {
        guard let s = peers[code]?.sending() else { return nil }
        return .ratchet(pq: s.isPostQuantum)
    }

    /// Une session existe-t-elle avec ce contact ?
    public func hasSession(_ code: String) -> Bool {
        !(peers[code]?.sessions.isEmpty ?? true)
    }

    /// Oublie les sessions d'un contact : le prochain envoi en ouvre une
    /// neuve (après une session perdue de l'un ou l'autre côté).
    public func forget(_ code: String) {
        if peers.removeValue(forKey: code) != nil { dirty.insert(code) }
    }

    /// Chiffre `plain` (un `Wire` JSON) pour un contact : par sa session, ou
    /// une session neuve vers son paquet (`bundle`, vérifié) ; sinon, ou si le
    /// texte dépasse ce que porte un jeton v3 (200 Kio : photos, vocaux,
    /// fichiers), l'ancien format.
    public func seal(_ me: Identity, peerCode: String, peerDh: String, bundle: KeyBundle?, plain: String) -> V3.Sealed? {
        sealTo(me, addr: peerCode, peerCode: peerCode, peerDh: peerDh, bundle: bundle, plain: plain)
    }

    /// Comme `seal`, pour un appareil d'un compte (v4) : la session est celle
    /// de son adresse (`addr`, `<code>/<dev>`), l'enveloppe celle du code.
    public func sealTo(_ me: Identity, addr: String, peerCode: String, peerDh: String, bundle: KeyBundle?,
                       plain: String) -> V3.Sealed? {
        func legacy() -> V3.Sealed? {
            TalkaCrypto.seal(me, peerCode: peerCode, peerDh: peerDh, plain: plain).map { V3.Sealed(body: $0, v3: false) }
        }
        if plain.utf8.count > Session.maxPlaintext { return legacy() }
        guard let token = encrypt(addr, bundle: bundle, plain: plain) else { return legacy() }
        return TalkaCrypto.seal(me, peerCode: peerCode, peerDh: peerDh, plain: token).map { V3.Sealed(body: $0, v3: true) }
    }

    func encrypt(_ code: String, bundle: KeyBundle?, plain: String) -> String? {
        let peer = peers[code] ?? V3Peer()
        peers[code] = peer
        if let s = peer.sending() {
            if let token = try? s.encrypt(plain) {
                dirty.insert(code)
                return token
            }
            // Chaîne épuisée ou état abîmé : une session neuve, si l'on a le
            // paquet.
            peer.sessions.removeAll()
            peer.current = nil
            dirty.insert(code)
        }
        guard let bundle,
              let s = try? device.initiateBest(bundle: bundle, rng: Hasard.system()),
              let token = try? s.encrypt(plain)
        else { return nil }
        peer.put(s)
        dirty.insert(code)
        return token
    }

    /// Ouvre une enveloppe reçue de `peerCode` : l'ancien format, ou un jeton
    /// v3 (par sa session, ou un premier message).
    public func open(_ me: Identity, peerCode: String, peerDh: String, body: String, now: UInt64) -> V3.Opened {
        openFrom(me, addr: peerCode, peerCode: peerCode, peerDh: peerDh, body: body, now: now)
    }

    /// Comme `open`, d'un appareil d'un compte (v4) : la session est celle de
    /// son adresse (`addr`, `<code>/<dev>`).
    public func openFrom(_ me: Identity, addr: String, peerCode: String, peerDh: String, body: String,
                         now: UInt64) -> V3.Opened {
        guard let plain = TalkaCrypto.open(me, peerCode: peerCode, peerDh: peerDh, body: body) else {
            return .failed(.envelope)
        }
        guard V3.isToken(plain) else { return .legacy(plain) }
        do {
            switch try decrypt(addr, plain, now: now) {
            case .text(let wire): return .v3(wire)
            // Talka ne se sert pas (encore) des chaînes de groupe.
            case .distribution: return .failed(.v3(.format))
            }
        } catch Erreur.rejoue {
            return .replay
        } catch {
            return .failed(.v3(error as? Erreur ?? .etat))
        }
    }

    func decrypt(_ code: String, _ text: String, now: UInt64) throws -> BrocolisProto.Content {
        let token = try Token.decode(text)
        guard let id = token.sessionId else { throw Erreur.sessionInconnue }
        let rng = Hasard.system()
        // v4 : une session ouverte avant de connaître ses appareils est
        // rangée sous son code ; elle passe à l'adresse de l'appareil
        // (`<code>/<dev>`) dès qu'un message dit lequel c'est.
        if let slash = code.firstIndex(of: "/"), peers[code]?.get(id) == nil {
            let base = String(code[..<slash])
            if let old = peers[base], let s = old.get(id) {
                old.sessions.removeAll { $0.sessionId == id }
                if old.current == id { old.current = nil }
                dirty.insert(base)
                let moved = peers[code] ?? V3Peer()
                moved.put(s)
                peers[code] = moved
                dirty.insert(code)
            }
        }
        let peer = peers[code] ?? V3Peer()
        peers[code] = peer
        if let s = peer.get(id) {
            do {
                let content = try s.decrypt(token, now: now, rng: rng)
                peer.current = id
                dirty.insert(code)
                return content
            } catch Erreur.nouvelleSession {
                // Le correspondant a ouvert une session neuve (il avait perdu
                // la sienne) : elle remplace l'ancienne.
            }
        }
        guard case .initial(let first) = token else { throw Erreur.sessionInconnue }
        let (s, content) = try device.accept(first, now: now, rng: rng)
        peer.put(s)
        deviceDirty = true
        dirty.insert(code)
        return content
    }
}
