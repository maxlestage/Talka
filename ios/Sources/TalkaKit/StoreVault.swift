// La clé de l'appareil, qui chiffre la base des messages au repos (`Store`) :
// 32 octets tirés une fois, gardés dans le trousseau d'iOS, jamais dans la
// base, pour cet iPhone seulement (`ThisDeviceOnly` : ni iCloud, ni
// sauvegarde ; la base, elle, est exclue des sauvegardes). Dans le groupe de
// trousseau de l'application seule.
//
// Protégée par Face ID (au choix) : la clé est alors gardée avec un contrôle
// d'accès (`userPresence` : Face ID, Touch ID ou le code de l'iPhone),
// vérifié par la Secure Enclave. Sans le visage ou le code de l'iPhone, la
// base ne s'ouvre pas, même pour Talka.
//
// Chiffrée par le code de verrouillage de Talka (dès qu'il y en a un, comme
// dans le navigateur) : la clé n'est plus gardée qu'enveloppée par
// Argon2id(code) (`cle-code`, même format que `crypto::lock_device_key`).
// Quelqu'un qui a l'iPhone déverrouillé, ou une copie de son trousseau, ne
// lit rien sans le code. Face ID peut l'ouvrir aussi, par une seconde copie
// liée aux visages enregistrés (`biometryCurrentSet` : ni le code de
// l'iPhone, ni un visage ajouté plus tard).
//
// Ailleurs (tests sous macOS ou Linux), pas de clé : la base reste comme
// avant.

import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
#if os(iOS)
import LocalAuthentication
import Security
#endif

public enum StoreVault {
    static let protectedFlag = "talka.repos.protege"
    static let codeFlag = "talka.repos.code"
    static let faceFlag = "talka.repos.visage"

    /// La clé ne s'ouvre-t-elle qu'avec Face ID (ou le code de l'iPhone) ?
    public static var isProtected: Bool {
        UserDefaults.standard.bool(forKey: protectedFlag)
    }

    /// La clé est-elle chiffrée par le code de verrouillage de Talka ?
    public static var isCodeLocked: Bool {
        UserDefaults.standard.bool(forKey: codeFlag)
    }

    /// Face ID ouvre-t-il aussi la clé chiffrée par le code ?
    public static var hasFaceUnlock: Bool {
        isCodeLocked && UserDefaults.standard.bool(forKey: faceFlag)
    }

    /// La clé enveloppée par le code : sel et réglages d'Argon2id, puis
    /// nonce (12) ‖ AES-256-GCM(clé, données associées « talka-cle »).
    public struct CodeLock: Codable, Equatable, Sendable {
        public var salt: String
        public var m: UInt32
        public var t: UInt32
        public var data: String
    }

    /// Enveloppe `key` par `code` (Argon2id, comme le code du navigateur).
    public static func wrap(_ key: SymmetricKey, code: String) -> CodeLock? {
        let salt = randomBytes(16)
        guard let user = TalkaCrypto.argon2id(code, salt: salt, m: TalkaCrypto.argonM, t: TalkaCrypto.argonT),
              let data = TalkaCrypto.lockDeviceKey(user, key.withUnsafeBytes { Data($0) })
        else { return nil }
        return CodeLock(salt: b64(salt), m: TalkaCrypto.argonM, t: TalkaCrypto.argonT, data: b64(data))
    }

    /// La clé, si `code` est le bon (sinon nil : le chiffrement l'a refusé).
    public static func unwrap(_ lock: CodeLock, code: String) -> SymmetricKey? {
        guard (8...(1 << 20)).contains(lock.m), (1...10).contains(lock.t),
              let salt = unb64(lock.salt), let data = unb64(lock.data),
              let user = TalkaCrypto.argon2id(code, salt: salt, m: lock.m, t: lock.t),
              let raw = TalkaCrypto.unlockDeviceKey(user, data)
        else { return nil }
        return SymmetricKey(data: raw)
    }

    #if os(iOS)
    static func query(_ account: String) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "talka.repos",
            kSecAttrAccount as String: account,
        ]
        if let group = V3Vault.group { q[kSecAttrAccessGroup as String] = group }
        return q
    }

    static func readData(_ account: String, context: LAContext?) -> (OSStatus, Data?) {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        if let context { q[kSecUseAuthenticationContext as String] = context }
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        return (status, status == errSecSuccess ? out as? Data : nil)
    }

    static func read(_ account: String, context: LAContext?) -> (OSStatus, SymmetricKey?) {
        let (status, data) = readData(account, context: context)
        guard let data, data.count == 32 else { return (status, nil) }
        return (status, SymmetricKey(data: data))
    }

    /// Qui peut lire une copie : Talka seule (`plain`), avec Face ID ou le
    /// code de l'iPhone (`presence`), avec un visage enregistré seulement
    /// (`biometry`).
    enum Access { case plain, presence, biometry }

    static func add(_ account: String, data: Data, access: Access) -> Bool {
        var attrs: [String: Any] = [kSecValueData as String: data]
        switch access {
        case .plain:
            // Lisible en arrière-plan (les messages arrivent), jamais ailleurs.
            attrs[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        case .presence, .biometry:
            guard let control = SecAccessControlCreateWithFlags(
                nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
                access == .presence ? .userPresence : .biometryCurrentSet, nil)
            else { return false }
            attrs[kSecAttrAccessControl as String] = control
        }
        SecItemDelete(query(account) as CFDictionary)
        return SecItemAdd(query(account).merging(attrs) { $1 } as CFDictionary, nil) == errSecSuccess
    }

    static func add(_ account: String, _ key: SymmetricKey, protected: Bool) -> Bool {
        add(account, data: key.withUnsafeBytes { Data($0) }, access: protected ? .presence : .plain)
    }

    static func delete(_ account: String) {
        SecItemDelete(query(account) as CFDictionary)
    }
    #endif

    /// La clé de l'appareil ; nil hors iPhone, ou si le trousseau ne répond
    /// pas (alors surtout ne pas en tirer une autre : `Store` passe en
    /// lecture seule s'il trouve des lignes chiffrées). Protégée par Face ID,
    /// il faut `context`, déjà authentifié.
    public static func key(context: AnyObject? = nil) -> SymmetricKey? {
        #if os(iOS)
        if isProtected {
            return read("cle-protegee", context: context as? LAContext).1
        }
        // Chiffrée par le code : il faut `key(code:)` (ou `key(face:)`).
        if isCodeLocked { return nil }
        let (status, key) = read("cle", context: nil)
        if let key { return key }
        guard status == errSecItemNotFound else { return nil }
        let fresh = SymmetricKey(size: .bits256)
        guard add("cle", fresh, protected: false) else {
            // Un autre processus l'a écrite entre-temps : la sienne.
            return read("cle", context: nil).1
        }
        return fresh
        #else
        return nil
        #endif
    }

    /// La clé chiffrée par le code, si `code` est le bon. Lent (Argon2id) :
    /// hors du fil principal.
    public static func key(code: String) -> SymmetricKey? {
        #if os(iOS)
        guard isCodeLocked, let data = readData("cle-code", context: nil).1,
              let lock = try? JSONDecoder().decode(CodeLock.self, from: data)
        else { return nil }
        return unwrap(lock, code: code)
        #else
        return nil
        #endif
    }

    /// La clé par Face ID seul (`context` déjà authentifié par un visage).
    public static func key(face context: AnyObject) -> SymmetricKey? {
        #if os(iOS)
        guard hasFaceUnlock else { return nil }
        return read("cle-visage", context: context as? LAContext).1
        #else
        return nil
        #endif
    }

    /// Chiffre la clé par `code` (et oublie toute copie en clair ou derrière
    /// le code de l'iPhone) ; `nil` : la clé redevient lisible par Talka
    /// seule. La nouvelle copie est écrite avant que les anciennes partent.
    public static func setCode(_ code: String?, key: SymmetricKey) -> Bool {
        #if os(iOS)
        if let code {
            guard let lock = wrap(key, code: code), let data = try? JSONEncoder().encode(lock),
                  add("cle-code", data: data, access: .plain)
            else { return false }
            UserDefaults.standard.set(true, forKey: codeFlag)
            UserDefaults.standard.set(false, forKey: protectedFlag)
            delete("cle")
            delete("cle-protegee")
            return true
        }
        guard add("cle", key, protected: false) else { return false }
        UserDefaults.standard.set(false, forKey: codeFlag)
        UserDefaults.standard.set(false, forKey: faceFlag)
        delete("cle-code")
        delete("cle-visage")
        return true
        #else
        return false
        #endif
    }

    /// Face ID ouvre aussi la clé chiffrée par le code (ou plus).
    public static func setFaceUnlock(_ on: Bool, key: SymmetricKey) -> Bool {
        #if os(iOS)
        guard isCodeLocked else { return false }
        if on {
            guard add("cle-visage", data: key.withUnsafeBytes { Data($0) }, access: .biometry) else { return false }
        } else {
            delete("cle-visage")
        }
        UserDefaults.standard.set(on, forKey: faceFlag)
        return true
        #else
        return false
        #endif
    }

    /// Tout est effacé : plus aucune copie de la clé (une nouvelle sera
    /// tirée), plus aucune protection.
    public static func reset() {
        #if os(iOS)
        for account in ["cle", "cle-protegee", "cle-code", "cle-visage"] { delete(account) }
        #endif
        for flag in [protectedFlag, codeFlag, faceFlag] { UserDefaults.standard.removeObject(forKey: flag) }
    }

    /// Protège la clé par Face ID, ou retire cette protection. La nouvelle
    /// copie est écrite avant que l'ancienne soit effacée : jamais de clé
    /// perdue en route. `false` si le trousseau a refusé.
    public static func setProtected(_ on: Bool, key: SymmetricKey) -> Bool {
        #if os(iOS)
        guard on != isProtected else { return true }
        let (new, old) = on ? ("cle-protegee", "cle") : ("cle", "cle-protegee")
        guard add(new, key, protected: on) else { return false }
        UserDefaults.standard.set(on, forKey: protectedFlag)
        SecItemDelete(query(old) as CFDictionary)
        return true
        #else
        return false
        #endif
    }
}
