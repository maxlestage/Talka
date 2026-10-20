// L'appareil v3 sur l'iPhone (ses clés d'identité, pré-signées et ML-KEM) :
// dans le trousseau d'iOS, pour cet appareil seulement (ni iCloud, ni
// sauvegarde de l'iPhone), comme les clés Brocolis (`BrocolisVault`) — pas en
// clair dans la base des messages. Un élément par code Talka : un appareil ne
// sert jamais à deux identités (le relais pourrait les relier). Dans le
// groupe de trousseau de l'application seule, pas celui des widgets. Si le
// trousseau refuse, l'appareil reste dans la base (comme ailleurs). Ailleurs
// (tests sous macOS ou Linux), il reste dans la base (`kv/v3_device`).

import Foundation
#if os(iOS)
import Security
#endif

public enum V3Vault {
    #if os(iOS)
    static let service = "talka.v3"

    /// Le groupe de l'application seule (son identifiant, `TalkaPrivateKeychainGroup`
    /// dans Info.plist) : sans lui, l'élément irait dans le premier groupe des
    /// droits, celui que partagent les widgets.
    static var group: String? {
        let g = Bundle.main.object(forInfoDictionaryKey: "TalkaPrivateKeychainGroup") as? String ?? ""
        return g.isEmpty || g.hasPrefix("$(") ? nil : g
    }

    static func query(_ code: String) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: code,
        ]
        if let group { q[kSecAttrAccessGroup as String] = group }
        return q
    }

    /// Écrit l'appareil (son JSON) ; `false` si le trousseau a refusé.
    @discardableResult
    public static func write(_ json: String, code: String) -> Bool {
        let attrs: [String: Any] = [
            kSecValueData as String: Data(json.utf8),
            // Lisible en arrière-plan (les messages arrivent), jamais ailleurs.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(query(code) as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query(code).merging(attrs) { $1 } as CFDictionary, nil)
        }
        return status == errSecSuccess
    }

    public struct Unreadable: Error {
        public let status: OSStatus
    }

    /// L'appareil gardé ; `nil` s'il n'y en a pas. Une erreur si le trousseau
    /// ne répond pas (verrouillé, refus) : surtout ne pas en créer un autre.
    public static func read(code: String) throws -> String? {
        var q = query(code)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = out as? Data else { throw Unreadable(status: status) }
        return String(data: data, encoding: .utf8)
    }

    /// Efface les appareils de tous les codes.
    public static func clear() {
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
        if let group { q[kSecAttrAccessGroup as String] = group }
        SecItemDelete(q as CFDictionary)
    }
    #endif
}
