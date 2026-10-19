// Les codes, les clés et le chiffrement de Talka — exactement comme le
// navigateur (`client/src/crypto.rs`), octet pour octet :
//
// - l'identité : une clé ECDSA P-256 (signer le défi du relais) et une clé
//   ECDH P-256 (chiffrer), tirées sur l'appareil ;
// - le code : 50 bits de SHA-256(signature ‖ chiffrement) en 10 caractères ;
// - une conversation à deux : ECDH → HKDF-SHA256 → AES-256-GCM, avec les deux
//   codes en données associées ;
// - le code de verrouillage et la sauvegarde : PBKDF2-SHA256.

import Foundation

#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

#if canImport(CommonCrypto)
import CommonCrypto
#endif

/// Les codes Talka : ce qui remplace le numéro de téléphone.
public enum TalkaCode {
    /// Les 32 caractères d'un code : ni `0`/`O`, ni `1`/`I`.
    public static let alphabet = Array("23456789ABCDEFGHJKLMNPQRSTUVWXYZ")

    /// Le code de deux clés publiques brutes (65 octets chacune).
    public static func of(sign: Data, dh: Data) -> String {
        let digest = Array(SHA256.hash(data: sign + dh))
        var out = ""
        for i in 0..<10 {
            var v = 0
            for b in 0..<5 {
                let bit = i * 5 + b
                v = (v << 1) | Int((digest[bit / 8] >> (7 - UInt8(bit % 8))) & 1)
            }
            out.append(alphabet[v])
        }
        return out
    }

    /// `K7Q2MX9PRT` → `K7Q2M-X9PRT`.
    public static func pretty(_ code: String) -> String {
        guard code.count == 10 else { return code }
        return "\(code.prefix(5))-\(code.suffix(5))"
    }

    /// Ce que l'on tape (minuscules, tirets, espaces) → un code, ou rien.
    public static func normalize(_ text: String) -> String? {
        let code = text.uppercased().filter { $0 != "-" && $0 != " " }
        guard code.count == 10, code.allSatisfy({ alphabet.contains($0) }) else { return nil }
        return code
    }

    /// Un code trouvé dans un lien d'invitation (`…/c/K7Q2MX9PRT`,
    /// `…/app/#add=K7Q2MX9PRT`) ou tapé tel quel.
    public static func find(in text: String) -> String? {
        if let code = normalize(text) { return code }
        let tail = text.split(whereSeparator: { $0 == "/" || $0 == "=" || $0 == "#" }).last.map(String.init) ?? ""
        return normalize(tail)
    }
}

public func b64(_ data: Data) -> String { data.base64EncodedString() }
public func unb64(_ text: String) -> Data? { Data(base64Encoded: text) }

public func randomBytes(_ n: Int) -> Data {
    var g = SystemRandomNumberGenerator()
    return Data((0..<n).map { _ in UInt8.random(in: 0...255, using: &g) })
}

/// Un identifiant aléatoire, en hexadécimal.
public func randomId(_ bytes: Int = 12) -> String {
    randomBytes(bytes).map { String(format: "%02x", $0) }.joined()
}

/// L'identité de l'appareil, telle qu'elle est gardée (et sauvegardée) :
/// le même JSON que le navigateur.
public struct Identity: Codable, Equatable, Sendable {
    public var code: String
    /// Les clés publiques brutes (65 octets), en base64.
    public var sign: String
    public var dh: String
    /// Les clés privées (32 octets), en base64.
    public var signPriv: String
    public var dhPriv: String
    public var createdAt: Double

    enum CodingKeys: String, CodingKey {
        case code, sign, dh
        case signPriv = "sign_priv"
        case dhPriv = "dh_priv"
        case createdAt = "created_at"
    }

    public static func create(now: Double = Date().timeIntervalSince1970 * 1000) -> Identity {
        let sign = P256.Signing.PrivateKey()
        let dh = P256.KeyAgreement.PrivateKey()
        let signRaw = sign.publicKey.x963Representation
        let dhRaw = dh.publicKey.x963Representation
        return Identity(
            code: TalkaCode.of(sign: signRaw, dh: dhRaw),
            sign: b64(signRaw), dh: b64(dhRaw),
            signPriv: b64(sign.rawRepresentation), dhPriv: b64(dh.rawRepresentation),
            createdAt: now)
    }

    var signKey: P256.Signing.PrivateKey? {
        unb64(signPriv).flatMap { try? P256.Signing.PrivateKey(rawRepresentation: $0) }
    }

    var dhKey: P256.KeyAgreement.PrivateKey? {
        unb64(dhPriv).flatMap { try? P256.KeyAgreement.PrivateKey(rawRepresentation: $0) }
    }

    /// Les clés de cette identité vont-elles bien ensemble ?
    public var isValid: Bool {
        guard let s = signKey, let d = dhKey else { return false }
        let sr = s.publicKey.x963Representation, dr = d.publicKey.x963Representation
        return b64(sr) == sign && b64(dr) == dh && TalkaCode.of(sign: sr, dh: dr) == code
    }

    /// La signature du défi du relais : `talka-auth:<nonce>`, `r ‖ s` en base64.
    public func signChallenge(_ nonce: String) -> String {
        guard let key = signKey,
              let sig = try? key.signature(for: Data("talka-auth:\(nonce)".utf8))
        else { return "" }
        return b64(sig.rawRepresentation)
    }
}

public enum TalkaCrypto {
    /// Vérifie une signature `r ‖ s` (comme le relais).
    public static func verify(sign: String, message: Data, signature: String) -> Bool {
        guard let k = unb64(sign), let s = unb64(signature),
              let key = try? P256.Signing.PublicKey(x963Representation: k),
              let sig = try? P256.Signing.ECDSASignature(rawRepresentation: s)
        else { return false }
        return key.isValidSignature(sig, for: message)
    }

    /// Les clés publiques d'un contact correspondent-elles à son code ?
    public static func verifyKeys(code: String, sign: String, dh: String) -> Bool {
        guard let s = unb64(sign), let d = unb64(dh), s.count == 65, d.count == 65,
              (try? P256.KeyAgreement.PublicKey(x963Representation: d)) != nil
        else { return false }
        return TalkaCode.of(sign: s, dh: d) == code
    }

    /// Le numéro de sécurité d'une conversation, le même des deux côtés.
    public static func safetyNumber(_ a: String, _ b: String) -> String {
        let (x, y) = a < b ? (a, b) : (b, a)
        let h = Array(SHA256.hash(data: Data("talka-safety:\(x):\(y)".utf8)))
        return (0..<12).map { i in
            String(format: "%05d", ((Int(h[i * 2]) << 8) | Int(h[i * 2 + 1])) % 100_000)
        }.joined(separator: " ")
    }

    nonisolated(unsafe) private static var pairKeys: [String: SymmetricKey] = [:]
    private static let lock = NSLock()

    /// La clé d'une conversation à deux : ECDH, puis HKDF-SHA256.
    static func pairKey(_ me: Identity, peerCode: String, peerDh: String) -> SymmetricKey? {
        let cacheKey = "\(me.code):\(peerCode):\(peerDh)"
        lock.lock()
        defer { lock.unlock() }
        if let k = pairKeys[cacheKey] { return k }
        guard let raw = unb64(peerDh),
              let pub = try? P256.KeyAgreement.PublicKey(x963Representation: raw),
              let secret = try? me.dhKey?.sharedSecretFromKeyAgreement(with: pub)
        else { return nil }
        let (a, b) = me.code < peerCode ? (me.code, peerCode) : (peerCode, me.code)
        let key = secret.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: Data("talka:\(a):\(b)".utf8),
            sharedInfo: Data("talka-message-v1".utf8), outputByteCount: 32)
        pairKeys[cacheKey] = key
        return key
    }

    /// Chiffre un texte (du JSON) pour un contact : base64(iv ‖ chiffré ‖ tag).
    public static func seal(_ me: Identity, peerCode: String, peerDh: String, plain: String) -> String? {
        guard let key = pairKey(me, peerCode: peerCode, peerDh: peerDh),
              let box = try? AES.GCM.seal(
                Data(plain.utf8), using: key, nonce: AES.GCM.Nonce(),
                authenticating: Data("\(me.code)>\(peerCode)".utf8)),
              let combined = box.combined
        else { return nil }
        return b64(combined)
    }

    /// Déchiffre ce qu'un contact a chiffré pour nous.
    public static func open(_ me: Identity, peerCode: String, peerDh: String, body: String) -> String? {
        guard let key = pairKey(me, peerCode: peerCode, peerDh: peerDh),
              let data = unb64(body), data.count >= 28,
              let box = try? AES.GCM.SealedBox(combined: data),
              let plain = try? AES.GCM.open(box, using: key, authenticating: Data("\(peerCode)>\(me.code)".utf8))
        else { return nil }
        return String(data: plain, encoding: .utf8)
    }

    // ----- Le code de verrouillage et la sauvegarde -----

    public static let pinRounds = 120_000
    public static let backupRounds = 200_000

    /// PBKDF2-HMAC-SHA256, 32 octets.
    public static func pbkdf2(_ secret: String, salt: Data, rounds: Int) -> Data {
        #if canImport(CommonCrypto)
        var out = [UInt8](repeating: 0, count: 32)
        let pw = Array(secret.utf8)
        _ = salt.withUnsafeBytes { s in
            pw.withUnsafeBufferPointer { p in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2), p.baseAddress.map { UnsafeRawPointer($0).assumingMemoryBound(to: Int8.self) }, pw.count,
                    s.bindMemory(to: UInt8.self).baseAddress, salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), UInt32(rounds), &out, out.count)
            }
        }
        return Data(out)
        #else
        // Un seul bloc de 32 octets : T1 = U1 ⊕ … ⊕ Uc.
        let key = SymmetricKey(data: Data(secret.utf8))
        var u = Data(HMAC<SHA256>.authenticationCode(for: salt + Data([0, 0, 0, 1]), using: key))
        var t = Array(u)
        for _ in 1..<rounds {
            u = Data(HMAC<SHA256>.authenticationCode(for: u, using: key))
            for (i, b) in u.enumerated() { t[i] ^= b }
        }
        return Data(t)
        #endif
    }

    public static func hashPin(_ pin: String) -> PinHash {
        let salt = randomBytes(16)
        return PinHash(salt: b64(salt), hash: b64(pbkdf2(pin, salt: salt, rounds: pinRounds)))
    }

    public static func checkPin(_ pin: String, _ stored: PinHash) -> Bool {
        guard let salt = unb64(stored.salt) else { return false }
        return b64(pbkdf2(pin, salt: salt, rounds: pinRounds)) == stored.hash
    }

    public static func encryptBackup(password: String, plain: String) -> Backup? {
        let salt = randomBytes(16)
        let key = SymmetricKey(data: pbkdf2(password, salt: salt, rounds: backupRounds))
        let nonce = AES.GCM.Nonce()
        guard let box = try? AES.GCM.seal(Data(plain.utf8), using: key, nonce: nonce) else { return nil }
        return Backup(talka: 2, salt: b64(salt), iv: b64(Data(nonce)), data: b64(box.ciphertext + box.tag))
    }

    public enum BackupError: Error, LocalizedError {
        case notBackup, wrongPassword
        public var errorDescription: String? {
            switch self {
            case .notBackup: "Ce fichier n'est pas une sauvegarde Talka."
            case .wrongPassword: "Mot de passe incorrect."
            }
        }
    }

    public static func decryptBackup(password: String, file: Backup) throws -> String {
        guard file.talka == 2, let salt = unb64(file.salt), let iv = unb64(file.iv),
              let data = unb64(file.data), data.count >= 16,
              let nonce = try? AES.GCM.Nonce(data: iv)
        else { throw BackupError.notBackup }
        let key = SymmetricKey(data: pbkdf2(password, salt: salt, rounds: backupRounds))
        guard let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: data.dropLast(16), tag: data.suffix(16)),
              let plain = try? AES.GCM.open(box, using: key)
        else { throw BackupError.wrongPassword }
        guard let text = String(data: plain, encoding: .utf8) else { throw BackupError.notBackup }
        return text
    }
}

/// Le code de verrouillage, haché : jamais gardé en clair.
public struct PinHash: Codable, Equatable, Sendable {
    public var salt: String
    public var hash: String
}

/// Une sauvegarde chiffrée : le même fichier que celle du navigateur.
public struct Backup: Codable, Equatable, Sendable {
    public var talka: Int
    public var salt: String
    public var iv: String
    public var data: String
}
