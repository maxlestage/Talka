// Les messages « double cliquet, clé partagée » du clavier Brocolis 🥦 : des
// jetons v3 (`[[BROCOLIS:v3:…]]`) en mode PSK. Le premier message ouvre une
// session à partir d'une clé Brocolis partagée ; les suivants changent de clé
// à chaque fois (double cliquet). Talka les lit comme l'app Brocolis
// (`BrocolisV3.open`) : une session connue par son identifiant, sinon un
// premier message essayé avec chaque clé de la discussion.
//
// Talka écrit aussi dans ce format, pour que le clavier « déchiffre » ce qui
// part d'ici : une discussion à deux, avec une seule clé Brocolis, envoie par
// la session de cette clé (celle que l'autre a ouverte, sinon une neuve). Et
// il garde le jeton d'origine de chaque message ouvert ou scellé, pour
// « Copier chiffré 🥦 » (à coller dans le clavier).
//
// L'état (les sessions, la garde contre le rejeu des premiers messages, les
// jetons d'origine) reste sur cet appareil : ni dans la sauvegarde, ni chez un appareil lié, comme les
// sessions v3 de Talka. Le même que le navigateur (`bro3::Psk`).

import BrocolisProto
import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

public final class BrocolisPsk {
    /// La ligne de l'état dans la base (`kv`).
    public static let kv = "brocolis3"
    /// Au plus tant de sessions gardées (les plus anciennes partent).
    static let maxSessions = 64
    /// Au plus tant de jetons d'origine gardés (les plus anciens partent).
    static let maxOriginals = 500

    var sessions: [Session] = []
    var guardian = ReplayGuard()
    /// Les messages déjà reçus ont été rouverts une fois.
    var scanned = false
    /// La session de chaque clé : [identifiant de clé, session] (hex).
    var byKey: [[String]] = []
    /// Les jetons d'origine des messages ouverts ou scellés ici : [id, jeton].
    var originals: [[String]] = []
    /// L'état a changé depuis la dernière écriture.
    public private(set) var dirty = false

    public init() {}

    /// Relit l'état gardé ; vide s'il n'y en a pas (ou s'il est illisible).
    public init(json: String?) {
        guard let json, let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return }
        for s in obj["sessions"] as? [Any] ?? [] {
            if let raw = try? JSONSerialization.data(withJSONObject: s),
               let text = String(data: raw, encoding: .utf8),
               let session = try? Session.fromJson(text) {
                sessions.append(session)
            }
        }
        if let g = obj["guard"], let raw = try? JSONSerialization.data(withJSONObject: g),
           let text = String(data: raw, encoding: .utf8), let guardian = try? ReplayGuard.fromJson(text) {
            self.guardian = guardian
        }
        scanned = obj["scanned"] as? Bool ?? false
        byKey = (obj["by_key"] as? [[String]] ?? []).filter { $0.count == 2 }
        originals = (obj["originals"] as? [[String]] ?? []).filter { $0.count == 2 }
    }

    /// La même forme que le navigateur : `{"sessions": [...], "guard": {...}}`.
    public func toJson() -> String {
        func object(_ text: String) -> Any? {
            text.data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) }
        }
        let obj: [String: Any] = [
            "sessions": sessions.compactMap { object($0.toJson()) },
            "guard": object(guardian.toJson()) ?? [:],
            "scanned": scanned,
            "by_key": byKey,
            "originals": originals,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: obj) else { return "{}" }
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    /// Les messages déjà reçus restent à rouvrir une fois ; `true` la première
    /// fois seulement (l'état passe alors à « fait », à écrire).
    public func firstScan() -> Bool {
        if scanned { return false }
        scanned = true
        dirty = true
        return true
    }

    /// Prend et remet à zéro le drapeau « à écrire ».
    public func takeDirty() -> Bool {
        defer { dirty = false }
        return dirty
    }

    /// Ouvre les jetons v3 de `text` avec les sessions connues, ou un premier
    /// message avec l'une des `keys` (les clés Brocolis de la discussion).
    /// Renvoie le texte (jetons ouverts remplacés par leur clair) et le nombre
    /// de jetons ouverts. Un jeton qui ne s'ouvre pas reste tel quel.
    public func open(_ text: String, keys: [[UInt8]], now: UInt64) -> (text: String, opened: Int) {
        var out = text
        var opened = 0
        for found in JetonV3.findAll(text) {
            guard case .success(let token) = found.token, let plain = openToken(token, keys: keys, now: now),
                  let range = out.range(of: found.text)
            else { continue }
            out.replaceSubrange(range, with: plain)
            opened += 1
        }
        return (out, opened)
    }

    func openToken(_ token: Token, keys: [[UInt8]], now: UInt64) -> String? {
        guard let id = token.sessionId else { return nil }
        let rng = Hasard.system()
        if let s = sessions.first(where: { $0.sessionId == id }) {
            do {
                let content = try s.decrypt(token, now: now, rng: rng)
                dirty = true
                return Self.text(content)
            } catch Erreur.nouvelleSession {
                // L'autre a ouvert une session neuve : un premier message
                // s'essaie comme tel, plus bas.
            } catch {
                return nil
            }
        }
        guard case .initial(let first) = token, first.mode == .psk else { return nil }
        // Clé par clé, comme l'app Brocolis : on sait laquelle a ouvert.
        for key in keys {
            do {
                let (session, content) = try Session.acceptPsk(keys: [key], message: first, guard: &guardian, now: now, rng: rng)
                keep(session, key: key)
                return Self.text(content)
            } catch Erreur.sessionInconnue {
                continue
            } catch {
                return nil
            }
        }
        return nil
    }

    /// Garde une session, et en fait celle de `key` (la plus récente sert).
    func keep(_ session: Session, key: [UInt8]) {
        let sid = Self.hex(session.sessionId)
        sessions.removeAll { $0.sessionId == session.sessionId }
        sessions.append(session)
        if sessions.count > Self.maxSessions { sessions.removeFirst() }
        let kid = Self.keyId(key)
        byKey.removeAll { $0[0] == kid }
        byKey.append([kid, sid])
        dirty = true
    }

    /// Chiffre `text` au format du clavier (« double cliquet, clé partagée »)
    /// par la session de `key` : celle que l'autre a ouverte, ou une neuve.
    /// Les passages `((…))` seuls s'il y en a (comme `Brocolis.seal`). `nil`
    /// si rien n'a pu être chiffré.
    public func seal(_ text: String, key: [UInt8]) -> String? {
        let kid = Self.keyId(key)
        var session = byKey.first { $0[0] == kid }.flatMap { pair in sessions.first { Self.hex($0.sessionId) == pair[1] } }
        if session == nil {
            guard let fresh = try? Session.initiatePsk(key: key, rng: Hasard.system()) else { return nil }
            keep(fresh, key: key)
            session = fresh
        }
        guard let session else { return nil }
        var failed = false
        let sealed = Brocolis.sealWith(text) { clear in
            let t = try? session.encrypt(clear)
            if t == nil { failed = true }
            return t
        }
        dirty = true
        return failed || sealed == text ? nil : sealed
    }

    /// Retient le jeton d'origine du message `id` (ouvert ou scellé ici).
    public func remember(_ id: String, token: String) {
        originals.removeAll { $0[0] == id }
        originals.append([id, token])
        if originals.count > Self.maxOriginals { originals.removeFirst() }
        dirty = true
    }

    /// Le jeton d'origine du message `id`, s'il est connu.
    public func original(_ id: String) -> String? {
        originals.last { $0[0] == id }?[1]
    }

    /// L'identifiant d'une clé (8 premiers octets de son SHA-256, en hex) :
    /// la clé elle-même ne va pas dans cet état. Le même que le navigateur.
    static func keyId(_ key: [UInt8]) -> String {
        hex(Array(SHA256.hash(data: Data(key)).prefix(8)))
    }

    static func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    /// Le clair d'un message ; une chaîne de groupe (type 0x03) ne s'affiche pas.
    static func text(_ content: BrocolisProto.Content) -> String {
        if case .text(let t) = content { return t }
        return ""
    }
}
