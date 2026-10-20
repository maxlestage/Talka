// Ce que montrent les widgets (iPhone : écran d'accueil et écran verrouillé ;
// montre : complications) : les messages non lus et les discussions récentes.
//
// L'application l'écrit, les widgets le lisent. Ils n'ont rien d'autre : ni
// clés, ni messages complets, ni accès à la base. Le partage passe par le
// trousseau (un groupe de trousseau commun à l'app et à ses widgets), lisible
// dès le premier déverrouillage de l'appareil.
//
// Compilé dans TalkaKit (testé avec le reste) et tel quel dans les widgets
// et l'app de la montre : il ne dépend que de WatchLink.swift.

import Foundation
#if canImport(Security)
import Security
#endif

public struct WidgetData: Codable, Equatable, Sendable {
    /// Les messages non lus (discussions en sourdine et archivées exclues).
    public var unread = 0
    /// Les discussions récentes, les non lues d'abord.
    public var chats: [WidgetChat] = []
    /// Un code de verrouillage protège Talka : ni noms ni aperçus, seulement
    /// le nombre de messages non lus.
    public var hidden = false
    public var at: Double = 0

    public init(unread: Int = 0, chats: [WidgetChat] = [], hidden: Bool = false, at: Double = 0) {
        self.unread = unread
        self.chats = chats
        self.hidden = hidden
        self.at = at
    }

    /// À partir du résumé envoyé à la montre.
    public init(_ s: WatchSnapshot, hidden: Bool, limit: Int = 4) {
        let visible = s.chats.filter { !$0.muted }
        unread = visible.reduce(0) { $0 + $1.unread }
        self.hidden = hidden
        at = s.chats.map(\.at).max() ?? 0
        guard !hidden else { return }
        // Les non lues d'abord, puis les plus récentes (les épinglées n'ont pas
        // de priorité ici : un widget montre ce qui vient d'arriver).
        chats = s.chats
            .sorted { a, b in
                let (ua, ub) = (!a.muted && a.unread > 0, !b.muted && b.unread > 0)
                if ua != ub { return ua }
                return a.at > b.at
            }
            .prefix(limit)
            .map { c in
                WidgetChat(id: c.id, title: c.title, initials: c.initials, color: c.color, group: c.group,
                           unread: c.muted ? 0 : c.unread, last: c.last, at: c.at)
            }
    }

    /// Le texte court des widgets et complications.
    public var summary: String {
        switch unread {
        case 0: "Aucun message non lu"
        case 1: "1 message non lu"
        default: "\(unread) messages non lus"
        }
    }

    /// L'adresse qu'ouvre un toucher : la discussion, ou la liste.
    public static func link(_ chat: String? = nil) -> URL {
        guard let chat, let id = chat.addingPercentEncoding(withAllowedCharacters: .alphanumerics) else {
            return URL(string: "talka://chats")!
        }
        return URL(string: "talka://chat/\(id)")!
    }

    /// La discussion d'une adresse `talka://chat/…` (nil : une autre adresse).
    public static func chat(in url: URL) -> String? {
        guard url.scheme == "talka", url.host == "chat" else { return nil }
        let id = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).removingPercentEncoding ?? ""
        return id.isEmpty ? nil : id
    }
}

public struct WidgetChat: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var title: String
    public var initials: String
    public var color: Int
    public var group: Bool
    public var unread: Int
    public var last: String
    public var at: Double

    public init(id: String, title: String, initials: String, color: Int, group: Bool, unread: Int, last: String, at: Double) {
        self.id = id
        self.title = title
        self.initials = initials
        self.color = color
        self.group = group
        self.unread = unread
        self.last = last
        self.at = at
    }
}

#if canImport(Security)
/// Le partage entre l'application et ses widgets : un élément du trousseau,
/// dans le groupe `TalkaKeychainGroup` (Info.plist), commun aux deux.
public enum WidgetShare {
    static let service = "talka.widgets"
    static let account = "data"

    static var group: String? {
        let g = Bundle.main.object(forInfoDictionaryKey: "TalkaKeychainGroup") as? String ?? ""
        return g.isEmpty || g.hasPrefix("$(") ? nil : g
    }

    static func query() -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let group { q[kSecAttrAccessGroup as String] = group }
        return q
    }

    /// Écrit ; `false` si rien n'a changé (inutile de recharger les widgets).
    @discardableResult
    public static func write(_ data: WidgetData) -> Bool {
        guard let bytes = WatchLink.encode(data) else { return false }
        if let old = readBytes(), old == bytes { return false }
        let attrs: [String: Any] = [
            kSecValueData as String: bytes,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        if SecItemUpdate(query() as CFDictionary, attrs as CFDictionary) == errSecItemNotFound {
            SecItemAdd(query().merging(attrs) { $1 } as CFDictionary, nil)
        }
        return true
    }

    public static func read() -> WidgetData? {
        WatchLink.decode(WidgetData.self, readBytes())
    }

    public static func clear() {
        SecItemDelete(query() as CFDictionary)
    }

    static func readBytes() -> Data? {
        var q = query()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }
}
#endif
