// Le cliquet post-quantique de Talka (v6, docs/SECURITE.md §7.17), comme
// `client/src/pq6.rs` : chaque appareil a une clé ML-KEM-768 qui tourne (une
// neuve dès que la courante a servi et a plus d'une heure, les anciennes
// gardées 30 jours), l'annonce dans ses messages v3 (le champ `pq6` du
// `Wire`), et chaque jeton v3 vers un appareil dont on connaît la clé est
// enveloppé par une encapsulation neuve :
// `[[TALKA:PQ6:<base64(1 ‖ id ‖ CT ‖ nonce ‖ AES-256-GCM(K, jeton))>]]`,
// `K = HKDF-SHA256(SS, info = "talka-pq6-cle:" ‖ id ‖ CT)`.
//
// Il faut ML-KEM (iOS 26) : avant, l'iPhone n'annonce pas de clé, et ses
// contacts n'enveloppent rien pour lui.

import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import BrocolisProto

public enum Pq6 {
    public static let head = "[[TALKA:PQ6:"
    /// Les clés de cet appareil dans `kv` : propres à l'appareil.
    public static let keysKey = "pq6"
    public static let rotateSecs: UInt64 = 60 * 60
    public static let keepSecs: UInt64 = 30 * 24 * 60 * 60
    public static let maxKeys = 200
    static let version: UInt8 = 1

    /// Cet appareil sait-il faire le cliquet post-quantique ?
    public static var available: Bool { MlKem.isAvailable }

    /// `SHA-256("talka-pq6-id:" ‖ ek)[0..8]`, en hexadécimal.
    public static func keyId(_ ek: [UInt8]) -> String {
        hex(Array(SHA256.hash(data: Data("talka-pq6-id:".utf8) + ek).prefix(8)))
    }

    public static func isWrapped(_ text: String) -> Bool {
        text.hasPrefix(head) && text.hasSuffix("]]")
    }

    static func aad(_ id: [UInt8]) -> Data { Data("talka-pq6:".utf8) + id }

    static func key(_ ss: [UInt8], _ id: [UInt8], _ ct: [UInt8]) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ss),
                               info: Data("talka-pq6-cle:".utf8) + id + ct, outputByteCount: 32)
    }

    /// Enveloppe `token` pour la clé `ek` (base64).
    public static func wrap(ek: String, token: String) -> String? {
        guard let ekBytes = unb64(ek).map(Array.init),
              let (ct, ss) = try? MlKem.encapsulate(ekBytes),
              let idBytes = unhex(keyId(ekBytes))
        else { return nil }
        let nonce = AES.GCM.Nonce()
        guard let box = try? AES.GCM.seal(Data(token.utf8), using: key(ss, idBytes, ct), nonce: nonce,
                                          authenticating: aad(idBytes))
        else { return nil }
        let bytes = Data([version]) + idBytes + ct + Data(nonce) + box.ciphertext + box.tag
        return head + b64(bytes) + "]]"
    }

    /// La clé annoncée par un appareil : `(id, ek, at)`, si elle est bien formée.
    public static func parseAdvert(_ v: Any?) -> (id: String, ek: String, at: UInt64)? {
        guard let o = v as? [String: Any], let id = o["id"] as? String, let ek = o["ek"] as? String,
              let bytes = unb64(ek), bytes.count == MlKem.publicKeyLength, keyId(Array(bytes)) == id
        else { return nil }
        return (id, ek, (o["at"] as? NSNumber)?.uint64Value ?? 0)
    }

    /// Ajoute l'annonce au `Wire` JSON `plain` (s'il en est un).
    static func withAdvert(_ plain: String, _ advert: [String: Any]) -> String? {
        guard var o = (try? JSONSerialization.jsonObject(with: Data(plain.utf8))) as? [String: Any] else { return nil }
        o["pq6"] = advert
        guard let data = try? JSONSerialization.data(withJSONObject: o, options: [.withoutEscapingSlashes]) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Une clé ML-KEM de cet appareil.
    public struct Key: Codable, Equatable, Sendable {
        public var id: String
        public var seed: String
        public var at: UInt64
        public var replaced: UInt64?
        public var used = false

        public init?(seed: [UInt8], at: UInt64) {
            guard let ek = try? MlKem.publicKey(seed: seed) else { return nil }
            id = Pq6.keyId(ek)
            self.seed = b64(Data(seed))
            self.at = at
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            seed = try c.decode(String.self, forKey: .seed)
            at = try c.decode(UInt64.self, forKey: .at)
            replaced = try c.decodeIfPresent(UInt64.self, forKey: .replaced)
            used = try c.decodeIfPresent(Bool.self, forKey: .used) ?? false
        }

        var seedBytes: [UInt8]? { unb64(seed).map(Array.init) }
    }

    /// Les clés de cet appareil (`kv/pq6`) : la dernière est la courante.
    public struct Keys: Codable, Equatable, Sendable {
        public var keys: [Key] = []

        public init(keys: [Key] = []) { self.keys = keys }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            keys = try c.decodeIfPresent([Key].self, forKey: .keys) ?? []
        }

        public var current: Key? { keys.last }

        /// Une clé neuve si aucune, ou si la courante a servi et a plus d'une
        /// heure ; les clés remplacées depuis plus de 30 jours sont effacées.
        /// `true` : quelque chose a changé.
        public mutating func maintain(now: UInt64, seed: () -> [UInt8] = { Array(randomBytes(64)) }) -> Bool {
            var changed = false
            let stale = current.map { $0.used && now >= $0.at + Pq6.rotateSecs } ?? true
            if stale, let fresh = Key(seed: seed(), at: now) {
                if !keys.isEmpty { keys[keys.count - 1].replaced = now }
                keys.append(fresh)
                changed = true
            }
            let before = keys.count
            keys.removeAll { k in k.replaced.map { now >= $0 + Pq6.keepSecs } ?? false }
            if keys.count > Pq6.maxKeys { keys.removeFirst(keys.count - Pq6.maxKeys) }
            return changed || keys.count != before
        }

        /// `{"id","ek","at"}` de la clé courante.
        public var advert: [String: Any]? {
            guard let k = current, let seed = k.seedBytes, let ek = try? MlKem.publicKey(seed: seed) else { return nil }
            return ["id": k.id, "ek": b64(Data(ek)), "at": k.at]
        }

        public enum OpenError: Error, Equatable {
            /// La clé est inconnue (effacée, ou d'avant une restauration).
            case unknownKey
            /// Le message est abîmé.
            case damaged
        }

        /// Ouvre un jeton enveloppé : (jeton, id de la clé, première fois
        /// qu'elle sert).
        public mutating func open(_ wrapped: String) -> Result<(token: String, id: String, first: Bool), OpenError> {
            guard isWrapped(wrapped),
                  let bytes = unb64(String(wrapped.dropFirst(head.count).dropLast(2))).map(Array.init),
                  bytes.count >= 1 + 8 + MlKem.ciphertextLength + 12 + 16, bytes[0] == version
            else { return .failure(.damaged) }
            let id = Array(bytes[1..<9])
            let ctEnd = 9 + MlKem.ciphertextLength
            let ct = Array(bytes[9..<ctEnd])
            let nonce = Array(bytes[ctEnd..<ctEnd + 12])
            let sealed = Array(bytes[(ctEnd + 12)...])
            let idHex = hex(id)
            guard let i = keys.firstIndex(where: { $0.id == idHex }), let seed = keys[i].seedBytes else {
                return .failure(.unknownKey)
            }
            guard let ss = try? MlKem.decapsulate(seed: seed, ciphertext: ct),
                  let n = try? AES.GCM.Nonce(data: nonce),
                  let box = try? AES.GCM.SealedBox(nonce: n, ciphertext: sealed.dropLast(16), tag: sealed.suffix(16)),
                  let plain = try? AES.GCM.open(box, using: key(ss, id, ct), authenticating: aad(id)),
                  let text = String(data: plain, encoding: .utf8)
            else { return .failure(.damaged) }
            let first = !keys[i].used
            keys[i].used = true
            return .success((text, idHex, first))
        }
    }

    static func unhex(_ s: String) -> [UInt8]? {
        let chars = Array(s.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var out: [UInt8] = []
        for i in stride(from: 0, to: chars.count, by: 2) {
            guard let b = UInt8(String(decoding: chars[i..<i + 2], as: UTF8.self), radix: 16) else { return nil }
            out.append(b)
        }
        return out
    }
}

/// Le cliquet post-quantique avec un appareil : sa clé annoncée, la dernière
/// des nôtres dont il s'est servi, la dernière que nous lui avons annoncée.
struct PqPeer: Codable, Equatable {
    var id = ""
    var ek = ""
    var at: UInt64 = 0
    var told = ""
    var sent = ""
    var sentAt: UInt64 = 0

    enum CodingKeys: String, CodingKey {
        case id, ek, at, told, sent
        case sentAt = "sent_at"
    }

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        ek = try c.decodeIfPresent(String.self, forKey: .ek) ?? ""
        at = try c.decodeIfPresent(UInt64.self, forKey: .at) ?? 0
        told = try c.decodeIfPresent(String.self, forKey: .told) ?? ""
        sent = try c.decodeIfPresent(String.self, forKey: .sent) ?? ""
        sentAt = try c.decodeIfPresent(UInt64.self, forKey: .sentAt) ?? 0
    }

    var isEmpty: Bool { self == PqPeer() }
}
