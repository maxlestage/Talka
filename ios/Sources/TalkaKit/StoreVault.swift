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

    /// La clé ne s'ouvre-t-elle qu'avec Face ID (ou le code de l'iPhone) ?
    public static var isProtected: Bool {
        UserDefaults.standard.bool(forKey: protectedFlag)
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

    static func read(_ account: String, context: LAContext?) -> (OSStatus, SymmetricKey?) {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        if let context { q[kSecUseAuthenticationContext as String] = context }
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        guard status == errSecSuccess, let data = out as? Data, data.count == 32 else { return (status, nil) }
        return (status, SymmetricKey(data: data))
    }

    static func add(_ account: String, _ key: SymmetricKey, protected: Bool) -> Bool {
        var attrs: [String: Any] = [kSecValueData as String: key.withUnsafeBytes { Data($0) }]
        if protected {
            guard let access = SecAccessControlCreateWithFlags(
                nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .userPresence, nil)
            else { return false }
            attrs[kSecAttrAccessControl as String] = access
        } else {
            // Lisible en arrière-plan (les messages arrivent), jamais ailleurs.
            attrs[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        }
        SecItemDelete(query(account) as CFDictionary)
        return SecItemAdd(query(account).merging(attrs) { $1 } as CFDictionary, nil) == errSecSuccess
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
