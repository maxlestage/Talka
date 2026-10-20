// Les clés Brocolis 🥦 sur l'iPhone : dans le trousseau d'iOS, pour cet
// appareil seulement (ni iCloud, ni sauvegarde de l'iPhone), chiffrées par
// le Secure Enclave et le code de l'iPhone — pas dans la base des messages.
// Ailleurs (tests sous macOS ou Linux), elles restent dans la base.

import Foundation
#if os(iOS)
import Security
#endif

public enum BrocolisVault {
    #if os(iOS)
    static let service = "talka.brocolis"
    static let account = "keys"

    static func query() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    /// Écrit ; `false` si le trousseau a refusé.
    @discardableResult
    public static func write(_ keys: [BrocolisKey]) -> Bool {
        guard let bytes = try? JSONEncoder().encode(keys) else { return false }
        let attrs: [String: Any] = [
            kSecValueData as String: bytes,
            // Lisibles en arrière-plan (les messages arrivent), jamais ailleurs.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(query() as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query().merging(attrs) { $1 } as CFDictionary, nil)
        }
        return status == errSecSuccess
    }

    public static func read() -> [BrocolisKey]? {
        var q = query()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode([BrocolisKey].self, from: data)
    }

    public static func clear() {
        SecItemDelete(query() as CFDictionary)
    }
    #endif
}
