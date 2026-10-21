// La clé de l'appareil, qui chiffre la base des messages au repos (`Store`) :
// 32 octets tirés une fois, gardés dans le trousseau d'iOS, jamais dans la
// base. `AfterFirstUnlock` : lisible en arrière-plan (les messages arrivent)
// et emportée par une sauvegarde chiffrée de l'iPhone avec la base, pour
// qu'un nouvel iPhone restauré relise ses messages ; un simple fichier copié
// n'a pas la clé. Dans le groupe de trousseau de l'application seule.
// Ailleurs (tests sous macOS ou Linux), pas de clé : la base reste comme
// avant.

import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
#if os(iOS)
import Security
#endif

public enum StoreVault {
    /// La clé de l'appareil ; nil hors iPhone, ou si le trousseau ne répond
    /// pas (alors surtout ne pas en tirer une autre : `Store` passe en
    /// lecture seule s'il trouve des lignes chiffrées).
    public static func key() -> SymmetricKey? {
        #if os(iOS)
        var q = query()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        if status == errSecSuccess, let data = out as? Data, data.count == 32 {
            return SymmetricKey(data: data)
        }
        guard status == errSecItemNotFound else { return nil }
        let key = SymmetricKey(size: .bits256)
        let data = key.withUnsafeBytes { Data($0) }
        let attrs: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        guard SecItemAdd(query().merging(attrs) { $1 } as CFDictionary, nil) == errSecSuccess else {
            // Un autre processus l'a écrite entre-temps : la sienne.
            var again = query()
            again[kSecReturnData as String] = true
            var found: CFTypeRef?
            guard SecItemCopyMatching(again as CFDictionary, &found) == errSecSuccess,
                  let d = found as? Data, d.count == 32 else { return nil }
            return SymmetricKey(data: d)
        }
        return key
        #else
        return nil
        #endif
    }

    #if os(iOS)
    static func query() -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "talka.repos",
            kSecAttrAccount as String: "cle",
        ]
        if let group = V3Vault.group { q[kSecAttrAccessGroup as String] = group }
        return q
    }
    #endif
}
