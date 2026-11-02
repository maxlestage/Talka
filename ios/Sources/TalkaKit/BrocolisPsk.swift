// Les messages « double cliquet, clé partagée » du clavier Brocolis 🥦 : des
// jetons v3 (`[[BROCOLIS:v3:…]]`) en mode PSK. Le premier message ouvre une
// session à partir d'une clé Brocolis partagée ; les suivants changent de clé
// à chaque fois (double cliquet). Talka les lit comme l'app Brocolis
// (`BrocolisV3.open`) : une session connue par son identifiant, sinon un
// premier message essayé avec chaque clé de la discussion.
//
// L'état (les sessions, la garde contre le rejeu des premiers messages) reste
// sur cet appareil : ni dans la sauvegarde, ni chez un appareil lié, comme les
// sessions v3 de Talka. Le même que le navigateur (`bro3::Psk`).

import BrocolisProto
import Foundation

public final class BrocolisPsk {
    /// La ligne de l'état dans la base (`kv`).
    public static let kv = "brocolis3"
    /// Au plus tant de sessions gardées (les plus anciennes partent).
    static let maxSessions = 64

    var sessions: [Session] = []
    var guardian = ReplayGuard()
    /// Les messages déjà reçus ont été rouverts une fois.
    var scanned = false
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
        guard case .initial(let first) = token, first.mode == .psk, !keys.isEmpty,
              let (session, content) = try? Session.acceptPsk(keys: keys, message: first, guard: &guardian, now: now, rng: rng)
        else { return nil }
        sessions.removeAll { $0.sessionId == session.sessionId }
        sessions.append(session)
        if sessions.count > Self.maxSessions { sessions.removeFirst() }
        dirty = true
        return Self.text(content)
    }

    /// Le clair d'un message ; une chaîne de groupe (type 0x03) ne s'affiche pas.
    static func text(_ content: BrocolisProto.Content) -> String {
        if case .text(let t) = content { return t }
        return ""
    }
}
