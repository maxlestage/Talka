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

    /// Le jeton de remise de ce code (`nonce.signature`), comme
    /// `Identity::jeton` du navigateur : le nonce est tiré de la clé privée
    /// (toujours le même) ; la signature, elle, change à chaque calcul.
    public var jeton: String {
        guard let dh = unb64(dhPriv) else { return "" }
        let digest = Data(SHA256.hash(data: Data("talka-jeton-nonce:".utf8) + dh))
        let nonce = b64(digest.prefix(16))
        let sig = sign(TalkaCrypto.jetonMessage(code, nonce))
        return sig.isEmpty ? "" : "\(nonce).\(sig)"
    }

    /// Signe un message (ECDSA P-256, SHA-256) : `r ‖ s` en base64, comme
    /// `Identity::sign` du navigateur ; vide si la clé est illisible.
    public func sign(_ message: Data) -> String {
        guard let key = signKey, let sig = try? key.signature(for: message) else { return "" }
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
        var groups: [String] = []
        for i in 0..<12 {
            let high = Int(h[i * 2]) << 8
            let value: Int = (high | Int(h[i * 2 + 1])) % 100_000
            groups.append(String(format: "%05d", value))
        }
        return groups.joined(separator: " ")
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

    // ----- L'expéditeur scellé (v4) -----
    //
    // Comme `client/src/crypto.rs` : une seconde enveloppe autour de
    // l'enveloppe ordinaire (clé éphémère P-256, ECDH avec la clé de
    // chiffrement du destinataire, HKDF-SHA256, AES-256-GCM) ; elle porte le
    // code de l'expéditeur, que l'enveloppe intérieure prouve ensuite. Le
    // jeton de remise, signé par la clé du code, ouvre la connexion anonyme
    // du relais vers ce code.

    /// Ce que signe un code pour son jeton de remise.
    static func jetonMessage(_ code: String, _ nonce: String) -> Data {
        Data("talka-jeton:\(code):\(nonce)".utf8)
    }

    /// Le jeton est-il bien signé par la clé du code `to` ?
    public static func verifyJeton(to: String, sign: String, dh: String, jeton: String) -> Bool {
        let parts = jeton.split(separator: ".", maxSplits: 1).map(String.init)
        guard parts.count == 2, parts[0].count <= 64, verifyKeys(code: to, sign: sign, dh: dh) else { return false }
        return verify(sign: sign, message: jetonMessage(to, parts[0]), signature: parts[1])
    }

    private struct SealedSender: Codable {
        var from: String
        var body: String
    }

    private static func sealedKey(_ secret: SharedSecret, eph: Data, toDh: Data, to: String) -> SymmetricKey {
        secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: eph + toDh,
                                       sharedInfo: Data("talka-scelle-v1:\(to)".utf8), outputByteCount: 32)
    }

    // ----- L'aperçu des notifications -----------------------------------------
    //
    // Chiffré pour la clé de notification du destinataire (son navigateur la
    // garde, non exportable, dans son service worker) : le relais le
    // transmet sans le lire. Comme `crypto::seal_apercu` du navigateur.

    /// Une clé de notification valable : un point P-256 (65 octets, base64).
    public static func validNk(_ nk: String) -> Bool {
        guard let raw = unb64(nk), raw.count == 65 else { return false }
        return (try? P256.KeyAgreement.PublicKey(x963Representation: raw)) != nil
    }

    private static func apercuKey(_ secret: SharedSecret, eph: Data, nk: Data) -> SymmetricKey {
        secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: eph + nk,
                                       sharedInfo: Data("talka-apercu-v1".utf8), outputByteCount: 32)
    }

    /// Chiffre un aperçu (du JSON) pour la clé `nk` :
    /// base64(éphémère (65) ‖ iv (12) ‖ chiffré ‖ tag).
    public static func sealApercu(nk: String, plain: String) -> String? {
        guard let nkRaw = unb64(nk),
              let pub = try? P256.KeyAgreement.PublicKey(x963Representation: nkRaw)
        else { return nil }
        let eph = P256.KeyAgreement.PrivateKey()
        let ephRaw = eph.publicKey.x963Representation
        guard let secret = try? eph.sharedSecretFromKeyAgreement(with: pub),
              let box = try? AES.GCM.seal(Data(plain.utf8), using: apercuKey(secret, eph: ephRaw, nk: nkRaw),
                                          nonce: AES.GCM.Nonce(), authenticating: Data("talka-apercu".utf8)),
              let combined = box.combined
        else { return nil }
        return b64(ephRaw + combined)
    }

    /// Ouvre un aperçu avec la clé privée de notification (32 octets) : ce
    /// que fait le service worker du navigateur ; ici pour les tests.
    public static func openApercu(nkPriv: Data, sealed: String) -> String? {
        guard let data = unb64(sealed), data.count >= 65 + 12 + 16,
              let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: nkPriv),
              let eph = try? P256.KeyAgreement.PublicKey(x963Representation: data.prefix(65)),
              let secret = try? key.sharedSecretFromKeyAgreement(with: eph),
              let box = try? AES.GCM.SealedBox(combined: data.dropFirst(65)),
              let plain = try? AES.GCM.open(box, using: apercuKey(secret, eph: Data(data.prefix(65)),
                                                                  nk: key.publicKey.x963Representation),
                                            authenticating: Data("talka-apercu".utf8))
        else { return nil }
        return String(data: plain, encoding: .utf8)
    }

    /// Scelle `body` (une enveloppe ordinaire de `from` pour `to`) :
    /// base64(éphémère (65) ‖ iv (12) ‖ chiffré ‖ tag).
    public static func sealSender(from: String, to: String, toDh: String, body: String) -> String? {
        guard let toRaw = unb64(toDh),
              let pub = try? P256.KeyAgreement.PublicKey(x963Representation: toRaw)
        else { return nil }
        let eph = P256.KeyAgreement.PrivateKey()
        let ephRaw = eph.publicKey.x963Representation
        guard let secret = try? eph.sharedSecretFromKeyAgreement(with: pub),
              let plain = try? JSONEncoder().encode(SealedSender(from: from, body: body)),
              let box = try? AES.GCM.seal(plain, using: sealedKey(secret, eph: ephRaw, toDh: toRaw, to: to),
                                          nonce: AES.GCM.Nonce(), authenticating: Data("talka-scelle:\(to)".utf8)),
              let combined = box.combined
        else { return nil }
        return b64(ephRaw + combined)
    }

    /// Ouvre une enveloppe scellée pour nous : (code de l'expéditeur,
    /// enveloppe ordinaire). L'expéditeur n'est prouvé qu'en ouvrant celle-ci.
    public static func openSealed(_ me: Identity, _ sealed: String) -> (from: String, body: String)? {
        guard let data = unb64(sealed), data.count >= 65 + 12 + 16,
              let myDh = unb64(me.dh) else { return nil }
        let ephRaw = data.prefix(65)
        guard let eph = try? P256.KeyAgreement.PublicKey(x963Representation: ephRaw),
              let secret = try? me.dhKey?.sharedSecretFromKeyAgreement(with: eph),
              let box = try? AES.GCM.SealedBox(combined: data.dropFirst(65)),
              let plain = try? AES.GCM.open(box, using: sealedKey(secret, eph: Data(ephRaw), toDh: myDh, to: me.code),
                                            authenticating: Data("talka-scelle:\(me.code)".utf8)),
              let s = try? JSONDecoder().decode(SealedSender.self, from: plain),
              TalkaCode.normalize(s.from) == s.from
        else { return nil }
        return (s.from, s.body)
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
