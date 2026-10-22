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
import BrocolisProto

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

    /// L'empreinte des clés d'un code (96 bits, base64url, 16 caractères),
    /// comme `crypto::fingerprint` du navigateur : dans les liens
    /// d'invitation, elle démasque d'autres clés donnant le même code.
    public static func fingerprint(sign: String, dh: String) -> String {
        let h = Data(SHA256.hash(data: Data("talka-empreinte:\(sign):\(dh)".utf8))).prefix(12)
        return h.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func validFingerprint(_ fp: String) -> Bool {
        fp.count == 16 && fp.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
    }

    /// L'empreinte d'un lien d'invitation (`/c/<empreinte>/<code>` ou
    /// `#k=<empreinte>&add=<code>`), s'il en a une.
    public static func fingerprint(in text: String) -> String? {
        let parts = text.split(whereSeparator: { "/#&?".contains($0) }).map(String.init)
        if let i = parts.firstIndex(of: "c"), i + 2 < parts.count, validFingerprint(parts[i + 1]) {
            return parts[i + 1]
        }
        return parts.first { $0.hasPrefix("k=") }.map { String($0.dropFirst(2)) }.flatMap { validFingerprint($0) ? $0 : nil }
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
        return jeton(nonce: b64(digest.prefix(16)))
    }

    /// Le nonce du jeton de remise donné à `contact`, à l'époque `epoch`
    /// (elle avance quand on le bloque : l'ancien jeton est révoqué), comme
    /// `Identity::jeton_nonce` du navigateur.
    public func jetonNonce(for contact: String, epoch: Int) -> String {
        guard let dh = unb64(dhPriv) else { return "" }
        let digest = Data(SHA256.hash(data: Data("talka-jeton-nonce:".utf8) + dh + Data(":\(contact):\(epoch)".utf8)))
        return b64(digest.prefix(16))
    }

    /// Le jeton de remise pour `contact` : un par contact, révocable.
    public func jeton(for contact: String, epoch: Int) -> String {
        jeton(nonce: jetonNonce(for: contact, epoch: epoch))
    }

    func jeton(nonce: String) -> String {
        guard !nonce.isEmpty else { return "" }
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

    /// Le numéro de sécurité d'une conversation, le même des deux côtés. Il
    /// porte sur les clés publiques complètes (code, `sign`, `dh` de chacun),
    /// pas seulement sur les codes (50 bits), comme le navigateur.
    public static func safetyNumber(_ a: (code: String, sign: String, dh: String),
                                    _ b: (code: String, sign: String, dh: String)) -> String {
        let (x, y) = a.code < b.code ? (a, b) : (b, a)
        let text = "talka-safety-v2:\(x.code):\(x.sign):\(x.dh)|\(y.code):\(y.sign):\(y.dh)"
        let h = Array(SHA256.hash(data: Data(text.utf8)))
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
        /// L'appareil de l'expéditeur (v4) ; absent d'un ancien client.
        var fd = ""
        var body: String
        /// Du remplissage (v6) : l'enveloppe a la taille d'un palier.
        var p = ""

        enum CodingKeys: String, CodingKey { case from, fd, body, p }

        init(from: String, fd: String, body: String) {
            self.from = from; self.fd = fd; self.body = body
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            from = try c.decode(String.self, forKey: .from)
            fd = c.v(.fd, "")
            body = try c.decode(String.self, forKey: .body)
        }

        func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(from, forKey: .from)
            if !fd.isEmpty { try c.encode(fd, forKey: .fd) }
            try c.encode(body, forKey: .body)
            if !p.isEmpty { try c.encode(p, forKey: .p) }
        }
    }

    /// Les paliers de taille des enveloppes scellées (v6, docs/SECURITE.md
    /// §7.15) : 1 Kio, 2, 4… 256 Kio, puis des multiples de 256 Kio, sans
    /// dépasser 5 Mio. Comme `sealed_bucket` du navigateur.
    public static func sealedBucket(_ n: Int) -> Int {
        let step = 256 * 1024, max = 5 * 1024 * 1024
        var b = 1024
        if n <= step {
            while b < n { b *= 2 }
        } else {
            b = (n + step - 1) / step * step
        }
        return b > max ? n : b
    }

    /// Le clair d'une enveloppe scellée, rempli jusqu'à son palier.
    static func sealedPlain(from: String, fd: String, body: String) -> Data? {
        var s = SealedSender(from: from, fd: fd, body: body)
        guard let base = try? JSONEncoder().encode(s) else { return nil }
        // `,"p":""` : 7 octets, puis autant de « 0 » qu'il faut (un au moins).
        let target = sealedBucket(base.count + 8)
        s.p = String(repeating: "0", count: target - base.count - 7)
        return try? JSONEncoder().encode(s)
    }

    private static func sealedKey(_ secret: SharedSecret, eph: Data, toDh: Data, to: String) -> SymmetricKey {
        secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: eph + toDh,
                                       sharedInfo: Data("talka-scelle-v1:\(to)".utf8), outputByteCount: 32)
    }

    // ----- L'aperçu des notifications -----------------------------------------
    //
    // Chiffré pour la clé de notification du destinataire (son navigateur la
    // garde, non exportable, dans son service worker) : le relais le
    // transmet sans le lire. Authentifié : la clé mêle un ECDH éphémère et
    // un ECDH avec notre clé d'identité ; le service worker n'affiche que
    // l'aperçu d'un contact. Comme `crypto::seal_apercu` du navigateur :
    // base64(0x02 ‖ code (10) ‖ éphémère (65) ‖ iv (12) ‖ chiffré ‖ tag).

    /// Une clé de notification valable : un point P-256 (65 octets, base64).
    public static func validNk(_ nk: String) -> Bool {
        guard let raw = unb64(nk), raw.count == 65 else { return false }
        return (try? P256.KeyAgreement.PublicKey(x963Representation: raw)) != nil
    }

    private static func apercuKey(_ e: SharedSecret, _ s: SharedSecret, eph: Data, nk: Data, from: String) -> SymmetricKey {
        let ikm = e.withUnsafeBytes { Data($0) } + s.withUnsafeBytes { Data($0) }
        return HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: eph + nk,
                                      info: Data("talka-apercu-v2:\(from)".utf8), outputByteCount: 32)
    }

    /// Chiffre un aperçu (du JSON) de `me` pour la clé `nk`.
    public static func sealApercu(_ me: Identity, nk: String, plain: String) -> String? {
        guard me.code.utf8.count == 10, let nkRaw = unb64(nk),
              let pub = try? P256.KeyAgreement.PublicKey(x963Representation: nkRaw),
              let mine = me.dhKey
        else { return nil }
        let eph = P256.KeyAgreement.PrivateKey()
        let ephRaw = eph.publicKey.x963Representation
        guard let e = try? eph.sharedSecretFromKeyAgreement(with: pub),
              let st = try? mine.sharedSecretFromKeyAgreement(with: pub),
              let box = try? AES.GCM.seal(Data(plain.utf8), using: apercuKey(e, st, eph: ephRaw, nk: nkRaw, from: me.code),
                                          nonce: AES.GCM.Nonce(), authenticating: Data("talka-apercu:\(me.code)".utf8)),
              let combined = box.combined
        else { return nil }
        return b64(Data([2]) + Data(me.code.utf8) + ephRaw + combined)
    }

    /// Ouvre un aperçu avec la clé privée de notification (32 octets) : ce
    /// que fait le service worker du navigateur ; ici pour les tests.
    /// `dhOf` : la clé `dh` d'un contact connu. (expéditeur, aperçu).
    public static func openApercu(nkPriv: Data, sealed: String, dhOf: (String) -> String?) -> (from: String, plain: String)? {
        guard let data = unb64(sealed), data.count >= 1 + 10 + 65 + 12 + 16, data.first == 2,
              let from = String(data: data.subdata(in: data.startIndex + 1 ..< data.startIndex + 11), encoding: .utf8),
              let dh = dhOf(from).flatMap(unb64),
              let fromKey = try? P256.KeyAgreement.PublicKey(x963Representation: dh),
              let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: nkPriv)
        else { return nil }
        let ephRaw = data.subdata(in: data.startIndex + 11 ..< data.startIndex + 76)
        guard let eph = try? P256.KeyAgreement.PublicKey(x963Representation: ephRaw),
              let e = try? key.sharedSecretFromKeyAgreement(with: eph),
              let st = try? key.sharedSecretFromKeyAgreement(with: fromKey),
              let box = try? AES.GCM.SealedBox(combined: data.subdata(in: data.startIndex + 76 ..< data.endIndex)),
              let plain = try? AES.GCM.open(box, using: apercuKey(e, st, eph: ephRaw, nk: key.publicKey.x963Representation, from: from),
                                            authenticating: Data("talka-apercu:\(from)".utf8))
        else { return nil }
        return String(data: plain, encoding: .utf8).map { (from, $0) }
    }

    /// Scelle `body` (une enveloppe ordinaire de `from` pour `to`) :
    /// base64(éphémère (65) ‖ iv (12) ‖ chiffré ‖ tag).
    public static func sealSender(from: String, to: String, toDh: String, body: String) -> String? {
        sealSender(from: from, fd: "", to: to, toDh: toDh, body: body)
    }

    /// Comme `sealSender`, en disant aussi de quel appareil (`fd`, v4) vient
    /// l'enveloppe.
    public static func sealSender(from: String, fd: String, to: String, toDh: String, body: String) -> String? {
        guard let toRaw = unb64(toDh),
              let pub = try? P256.KeyAgreement.PublicKey(x963Representation: toRaw)
        else { return nil }
        let eph = P256.KeyAgreement.PrivateKey()
        let ephRaw = eph.publicKey.x963Representation
        guard let secret = try? eph.sharedSecretFromKeyAgreement(with: pub),
              let plain = sealedPlain(from: from, fd: fd, body: body),
              let box = try? AES.GCM.seal(plain, using: sealedKey(secret, eph: ephRaw, toDh: toRaw, to: to),
                                          nonce: AES.GCM.Nonce(), authenticating: Data("talka-scelle:\(to)".utf8)),
              let combined = box.combined
        else { return nil }
        return b64(ephRaw + combined)
    }

    /// Ouvre une enveloppe scellée pour nous : (code de l'expéditeur,
    /// enveloppe ordinaire). L'expéditeur n'est prouvé qu'en ouvrant celle-ci.
    public static func openSealed(_ me: Identity, _ sealed: String) -> (from: String, body: String)? {
        openSealedFrom(me, sealed).map { ($0.from, $0.body) }
    }

    /// Ouvre une enveloppe scellée : (code de l'expéditeur, son appareil
    /// (vide pour un ancien client), enveloppe ordinaire).
    public static func openSealedFrom(_ me: Identity, _ sealed: String) -> (from: String, fd: String, body: String)? {
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
        return (s.from, TalkaV4.isDev(s.fd) ? s.fd : "", s.body)
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

    /// Argon2id, comme le navigateur (`crypto::argon2id`) : 19 Mio, 2 passes
    /// pour le code ; 46 Mio, 1 passe pour la sauvegarde.
    public static let argonM: UInt32 = 19_456, argonT: UInt32 = 2
    public static let backupM: UInt32 = 47_104, backupT: UInt32 = 1
    /// Les nouveaux codes : 6 chiffres au moins (4 ou 5 restent acceptés).
    public static let pinMin = 6, pinMax = 12

    public static func argon2id(_ secret: String, salt: Data, m: UInt32, t: UInt32) -> Data? {
        (try? Argon2.hash(variant: .id, password: Array(secret.utf8), salt: Array(salt),
                          memoryKiB: m, passes: t, lanes: 1, length: 32)).map { Data($0) }
    }

    /// La clé de l'appareil enveloppée par la clé tirée du code : nonce (12)
    /// ‖ AES-256-GCM, données associées « talka-cle » (comme
    /// `crypto::lock_device_key` du navigateur).
    public static func lockDeviceKey(_ userKey: Data, _ deviceKey: Data) -> Data? {
        guard userKey.count == 32, let nonce = try? AES.GCM.Nonce(data: randomBytes(12)),
              let box = try? AES.GCM.seal(deviceKey, using: SymmetricKey(data: userKey), nonce: nonce,
                                          authenticating: Data("talka-cle".utf8))
        else { return nil }
        return Data(nonce) + box.ciphertext + box.tag
    }

    /// La clé de l'appareil, si `userKey` est la bonne.
    public static func unlockDeviceKey(_ userKey: Data, _ locked: Data) -> Data? {
        guard userKey.count == 32, locked.count == 12 + 32 + 16 else { return nil }
        let bytes = [UInt8](locked)
        guard let nonce = try? AES.GCM.Nonce(data: Data(bytes[0..<12])),
              let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: Data(bytes[12..<44]), tag: Data(bytes[44...]))
        else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: userKey), authenticating: Data("talka-cle".utf8))
    }

    public static func hashPin(_ pin: String) -> PinHash {
        let salt = randomBytes(16)
        return PinHash(salt: b64(salt), hash: b64(argon2id(pin, salt: salt, m: argonM, t: argonT) ?? Data()),
                       v: 2, m: argonM, t: argonT)
    }

    /// Comparaison en temps constant.
    static func same(_ a: Data, _ b: Data) -> Bool {
        a.count == b.count && zip(a, b).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }

    public static func checkPin(_ pin: String, _ stored: PinHash) -> Bool {
        guard let salt = unb64(stored.salt), let hash = unb64(stored.hash) else { return false }
        switch stored.v {
        case 0, 1:
            return same(pbkdf2(pin, salt: salt, rounds: pinRounds), hash)
        case 2 where (8...(1 << 20)).contains(stored.m) && (1...10).contains(stored.t):
            return argon2id(pin, salt: salt, m: stored.m, t: stored.t).map { same($0, hash) } ?? false
        default:
            return false
        }
    }

    public static func encryptBackup(password: String, plain: String) -> Backup? {
        let salt = randomBytes(16)
        guard let raw = argon2id(password, salt: salt, m: backupM, t: backupT) else { return nil }
        let key = SymmetricKey(data: raw)
        let nonce = AES.GCM.Nonce()
        guard let box = try? AES.GCM.seal(Data(plain.utf8), using: key, nonce: nonce) else { return nil }
        return Backup(talka: 3, salt: b64(salt), iv: b64(Data(nonce)), data: b64(box.ciphertext + box.tag),
                      m: backupM, t: backupT)
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
        guard let salt = unb64(file.salt), let iv = unb64(file.iv),
              let data = unb64(file.data), data.count >= 16,
              let nonce = try? AES.GCM.Nonce(data: iv)
        else { throw BackupError.notBackup }
        let key: SymmetricKey
        switch file.talka {
        case 2:
            key = SymmetricKey(data: pbkdf2(password, salt: salt, rounds: backupRounds))
        case 3 where (8...(1 << 21)).contains(file.m) && (1...10).contains(file.t):
            guard let raw = argon2id(password, salt: salt, m: file.m, t: file.t) else { throw BackupError.notBackup }
            key = SymmetricKey(data: raw)
        default:
            throw BackupError.notBackup
        }
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
    /// 2 : Argon2id (`m`, `t`) ; 0 : PBKDF2 (les codes d'avant).
    public var v: UInt32 = 0
    public var m: UInt32 = 0
    public var t: UInt32 = 0

    public init(salt: String, hash: String, v: UInt32 = 0, m: UInt32 = 0, t: UInt32 = 0) {
        self.salt = salt; self.hash = hash; self.v = v; self.m = m; self.t = t
    }

    enum CodingKeys: String, CodingKey { case salt, hash, v, m, t }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        salt = try c.decode(String.self, forKey: .salt); hash = try c.decode(String.self, forKey: .hash)
        v = c.v(.v, 0); m = c.v(.m, 0); t = c.v(.t, 0)
    }

    /// Comme le navigateur : `v`, `m`, `t` seulement s'il y en a.
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(salt, forKey: .salt); try c.encode(hash, forKey: .hash)
        if v != 0 { try c.encode(v, forKey: .v) }
        if m != 0 { try c.encode(m, forKey: .m) }
        if t != 0 { try c.encode(t, forKey: .t) }
    }

    /// Un code d'avant (PBKDF2), à rehacher.
    public var isOld: Bool { v < 2 }
}

/// Une sauvegarde chiffrée : le même fichier que celle du navigateur.
public struct Backup: Codable, Equatable, Sendable {
    public var talka: Int
    public var salt: String
    public var iv: String
    public var data: String
    /// Argon2id (`talka` 3) : mémoire (Kio) et passes.
    public var m: UInt32 = 0
    public var t: UInt32 = 0

    public init(talka: Int, salt: String, iv: String, data: String, m: UInt32 = 0, t: UInt32 = 0) {
        self.talka = talka; self.salt = salt; self.iv = iv; self.data = data; self.m = m; self.t = t
    }

    enum CodingKeys: String, CodingKey { case talka, salt, iv, data, m, t }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        talka = try c.decode(Int.self, forKey: .talka); salt = try c.decode(String.self, forKey: .salt)
        iv = try c.decode(String.self, forKey: .iv); data = try c.decode(String.self, forKey: .data)
        m = c.v(.m, 0); t = c.v(.t, 0)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(talka, forKey: .talka); try c.encode(salt, forKey: .salt)
        try c.encode(iv, forKey: .iv); try c.encode(data, forKey: .data)
        if m != 0 { try c.encode(m, forKey: .m) }
        if t != 0 { try c.encode(t, forKey: .t) }
    }
}

// ----- Plusieurs appareils et boîtes tournantes (v4) -------------------------
//
// Comme `client/src/crypto.rs` (`docs/SECURITE.md` §7.11 à §7.13) : les
// identifiants d'appareil, les boîtes aux lettres tournantes (une adresse qui
// change chaque jour, que seuls l'expéditeur et le destinataire savent
// calculer) et la liaison d'un nouvel appareil par QR code.

public enum TalkaV4 {
    /// Le relais de Talka : un seul. Son adresse entre dans la dérivation des
    /// boîtes tournantes, qui ne valent que pour lui.
    public static let relay = TalkaServer.official

    /// Un identifiant d'appareil : 16 caractères hexadécimaux minuscules.
    public static func isDev(_ dev: String) -> Bool {
        dev.utf8.count == 16 && dev.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }

    /// Un nouvel identifiant d'appareil (8 octets aléatoires).
    public static func newDev() -> String { randomId(8) }

    /// Le jour d'un horodatage (ms Unix, UTC) : les boîtes tournantes
    /// changent chaque jour.
    public static func day(_ ms: Double) -> Int { Int(max(ms, 0) / 86_400_000) }

    /// L'adresse d'une session v3 : `<code>/<dev>` (v4), ou le code.
    public static func address(_ code: String, _ dev: String) -> String {
        dev.isEmpty ? code : "\(code)/\(dev)"
    }

    /// La clé des boîtes entre deux codes : ECDH de leurs clés d'identité,
    /// puis HKDF-SHA256 lié au relais (§7.13). Seuls les deux savent la
    /// calculer.
    public static func boitesKey(_ me: Identity, peerCode: String, peerDh: String) -> Data? {
        guard let raw = unb64(peerDh),
              let pub = try? P256.KeyAgreement.PublicKey(x963Representation: raw),
              let secret = try? me.dhKey?.sharedSecretFromKeyAgreement(with: pub)
        else { return nil }
        let (a, b) = me.code < peerCode ? (me.code, peerCode) : (peerCode, me.code)
        let key = secret.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: Data("talka-boites-v1:\(relay)".utf8),
            sharedInfo: Data("\(a):\(b)".utf8), outputByteCount: 32)
        return key.withUnsafeBytes { Data($0) }
    }

    /// La boîte de `day` où l'on écrit à l'appareil `dev` de `to` :
    /// base64url(HMAC-SHA256(clé, "talka-boite:<to>/<dev>:<jour>")[0..16]).
    /// Une boîte de couverture (v6, §7.15) : à nous seuls, indiscernable
    /// d'une boîte tournante. Comme `cover_box` du navigateur.
    public static func coverBox(secret: Data, day: Int, i: Int) -> String {
        let mac = HMAC<SHA256>.authenticationCode(for: Data("talka-couverture:\(day):\(i)".utf8), using: SymmetricKey(data: secret))
        return b64url(Data(mac).prefix(16))
    }

    public static func boite(key: Data, to: String, dev: String, day: Int) -> String {
        let mac = HMAC<SHA256>.authenticationCode(for: Data("talka-boite:\(to)/\(dev):\(day)".utf8), using: SymmetricKey(data: key))
        return b64url(Data(mac).prefix(16))
    }

    public static func b64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func unb64url(_ text: String) -> Data? {
        var s = String(text.reversed().drop { $0 == "=" }.reversed())
        guard !s.contains("+"), !s.contains("/"), s.count % 4 != 1 else { return nil }
        s = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        return Data(base64Encoded: s)
    }

    // ----- La liaison d'un appareil (§7.12) -----

    /// Le préfixe d'un lien de liaison (QR code).
    public static let linkPrefix = "talka-lien:"

    /// Ce que montre le nouvel appareil : son `dev`, l'identifiant de liaison
    /// et sa clé publique éphémère.
    public struct LinkOffer: Equatable, Sendable {
        public var dev: String
        public var lid: Data
        public var lk: Data

        /// `talka-lien:<base64url(0x01 ‖ dev ‖ lid ‖ lk)>`.
        public var text: String {
            let devBytes = stride(from: 0, to: dev.count, by: 2).compactMap { i -> UInt8? in
                let start = dev.index(dev.startIndex, offsetBy: i)
                return UInt8(dev[start..<dev.index(start, offsetBy: 2)], radix: 16)
            }
            return linkPrefix + b64url(Data([1]) + Data(devBytes) + lid + lk)
        }

        /// Lit un lien de liaison (collé, ou lu dans un QR code).
        public static func parse(_ text: String) -> LinkOffer? {
            let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard t.hasPrefix(linkPrefix), let data = unb64url(String(t.dropFirst(linkPrefix.count))) else { return nil }
            let bytes = [UInt8](data)
            guard bytes.count == 1 + 8 + 16 + 65, bytes[0] == 1,
                  (try? P256.KeyAgreement.PublicKey(x963Representation: Data(bytes[25...]))) != nil
            else { return nil }
            return LinkOffer(dev: bytes[1..<9].map { String(format: "%02x", $0) }.joined(),
                             lid: Data(bytes[9..<25]), lk: Data(bytes[25...]))
        }

        /// La boîte de liaison, où le nouvel appareil attend.
        public var boite: String {
            b64url(Data(SHA256.hash(data: Data("talka-lien:".utf8) + lid)).prefix(16))
        }
    }

    /// Le côté secret de la liaison, gardé par le nouvel appareil le temps
    /// qu'elle se fasse.
    public struct LinkSecret: @unchecked Sendable {
        public let offer: LinkOffer
        let privateKey: P256.KeyAgreement.PrivateKey

        public init() {
            let key = P256.KeyAgreement.PrivateKey()
            privateKey = key
            offer = LinkOffer(dev: newDev(), lid: randomBytes(16), lk: key.publicKey.x963Representation)
        }

        /// Une offre déjà montrée et sa clé privée (32 octets) : les tests.
        public init?(offer: LinkOffer, privateKey raw: Data) {
            guard let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: raw),
                  key.publicKey.x963Representation == offer.lk
            else { return nil }
            self.offer = offer
            privateKey = key
        }

        /// La clé de liaison, à partir de la clé éphémère de l'appareil du
        /// compte.
        public func key(ek: Data) -> Data? {
            guard let pub = try? P256.KeyAgreement.PublicKey(x963Representation: ek),
                  let shared = try? privateKey.sharedSecretFromKeyAgreement(with: pub)
            else { return nil }
            return linkKey(shared, lid: offer.lid, ek: ek, lk: offer.lk)
        }
    }

    static func linkKey(_ shared: SharedSecret, lid: Data, ek: Data, lk: Data) -> Data {
        shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: lid,
                                       sharedInfo: Data("talka-lien-v1:".utf8) + ek + lk, outputByteCount: 32)
            .withUnsafeBytes { Data($0) }
    }

    /// L'appareil du compte répond à une offre : (sa clé éphémère publique,
    /// la clé de liaison).
    public static func linkAnswer(_ offer: LinkOffer) -> (ek: Data, key: Data)? {
        guard let pub = try? P256.KeyAgreement.PublicKey(x963Representation: offer.lk) else { return nil }
        let eph = P256.KeyAgreement.PrivateKey()
        let ek = eph.publicKey.x963Representation
        guard let shared = try? eph.sharedSecretFromKeyAgreement(with: pub) else { return nil }
        return (ek, linkKey(shared, lid: offer.lid, ek: ek, lk: offer.lk))
    }

    /// Le code de vérification d'une liaison, le même sur les deux appareils.
    public static func linkVerification(ek: Data, lk: Data) -> String {
        let d = Array(SHA256.hash(data: Data("talka-lien-verif:".utf8) + ek + lk))
        let n = (UInt32(d[0]) << 24 | UInt32(d[1]) << 16 | UInt32(d[2]) << 8 | UInt32(d[3])) % 1_000_000
        return String(format: "%03d %03d", Int(n / 1000), Int(n % 1000))
    }

    /// La taille d'un morceau de liaison (en clair).
    public static let linkChunk = 2 * 1024 * 1024

    /// Chiffre ce que la liaison transporte, en morceaux : `{"e","i","n","c"}`
    /// (JSON), à déposer dans la boîte de liaison.
    public static func linkSeal(key: Data, lid: Data, ek: Data, plain: Data) -> [String]? {
        var chunks: [Data] = []
        var start = plain.startIndex
        repeat {
            let end = plain.index(start, offsetBy: linkChunk, limitedBy: plain.endIndex) ?? plain.endIndex
            chunks.append(plain.subdata(in: start..<end))
            start = end
        } while start < plain.endIndex
        let n = chunks.count
        let lid64 = b64url(lid)
        var out: [String] = []
        for (i, chunk) in chunks.enumerated() {
            guard let box = try? AES.GCM.seal(chunk, using: SymmetricKey(data: key), nonce: AES.GCM.Nonce(),
                                              authenticating: Data("talka-lien:\(lid64):\(i)/\(n)".utf8)),
                  let combined = box.combined,
                  let json = try? JSONSerialization.data(withJSONObject: ["e": b64(ek), "i": i, "n": n, "c": b64(combined)] as [String: Any],
                                                          options: [.sortedKeys]),
                  let text = String(data: json, encoding: .utf8)
            else { return nil }
            out.append(text)
        }
        return out
    }

    /// Ouvre un morceau de liaison : (indice, nombre, clair).
    public static func linkOpen(key: Data, lid: Data, chunk: [String: Any]) -> (i: Int, n: Int, plain: Data)? {
        guard let i = chunk["i"] as? Int, let n = chunk["n"] as? Int, n > 0, i >= 0, i < n, n <= 64,
              let bytes = (chunk["c"] as? String).flatMap(unb64), bytes.count >= 12 + 16,
              let box = try? AES.GCM.SealedBox(combined: bytes),
              let plain = try? AES.GCM.open(box, using: SymmetricKey(data: key),
                                            authenticating: Data("talka-lien:\(b64url(lid)):\(i)/\(n)".utf8))
        else { return nil }
        return (i, n, plain)
    }
}
