// La mémoire de Talka sur l'iPhone : des fichiers JSON dans le dossier de
// l'application, protégés par iOS (illisibles tant que l'iPhone est
// verrouillé). Pas de base de données ailleurs.
//
// Les mêmes « magasins » que le navigateur (IndexedDB), avec les mêmes
// lignes `[clé, json]` : une sauvegarde faite dans le navigateur s'importe
// sur l'iPhone, et inversement.

import Foundation

public final class Store: @unchecked Sendable {
    public static let stores = ["kv", "contacts", "chats", "messages", "outbox", "statuses", "calls"]

    let dir: URL
    let lock = NSLock()
    /// magasin → clé → JSON (tout sauf les messages).
    var rows: [String: [String: String]] = [:]
    /// discussion → identifiant → message (chargé à la demande).
    var messages: [String: [String: Message]] = [:]
    var dirty = Set<String>()
    var saving = false

    public init(directory: URL) {
        dir = directory
        try? FileManager.default.createDirectory(at: dir.appendingPathComponent("messages"), withIntermediateDirectories: true)
        for name in Self.stores where name != "messages" {
            let url = file(name)
            if let data = try? Data(contentsOf: url),
               let r = try? JSONDecoder().decode([String: String].self, from: data) {
                rows[name] = r
            }
        }
    }

    /// Le dossier par défaut : `Application Support/Talka`.
    public static func defaultDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("Talka", isDirectory: true)
    }

    func file(_ store: String) -> URL { dir.appendingPathComponent("\(store).json") }

    func chatFile(_ chat: String) -> URL {
        let safe = chat.map { $0.isLetter || $0.isNumber ? $0 : "_" }
        return dir.appendingPathComponent("messages").appendingPathComponent("\(String(safe)).json")
    }

    // ----- Les magasins simples -----

    public func read<T: Decodable>(_ store: String, _ key: String, as type: T.Type = T.self) -> T? {
        lock.lock(); defer { lock.unlock() }
        return rows[store]?[key].flatMap { JSON.decode(T.self, $0) }
    }

    public func write<T: Encodable>(_ store: String, _ key: String, _ value: T) {
        let json = JSON.encode(value)
        lock.lock()
        rows[store, default: [:]][key] = json
        lock.unlock()
        markDirty(store)
    }

    public func remove(_ store: String, _ key: String) {
        lock.lock()
        rows[store]?[key] = nil
        lock.unlock()
        markDirty(store)
    }

    public func all<T: Decodable>(_ store: String, as type: T.Type = T.self) -> [T] {
        lock.lock(); defer { lock.unlock() }
        return (rows[store] ?? [:]).values.compactMap { JSON.decode(T.self, $0) }
    }

    public func clear(_ store: String) {
        lock.lock()
        rows[store] = [:]
        lock.unlock()
        markDirty(store)
    }

    // ----- Les messages, un fichier par discussion -----

    func loadChat(_ chat: String) -> [String: Message] {
        if let m = messages[chat] { return m }
        var out: [String: Message] = [:]
        if let data = try? Data(contentsOf: chatFile(chat)),
           let r = try? JSONDecoder().decode([String: String].self, from: data) {
            for (_, json) in r { if let m = JSON.decode(Message.self, json) { out[m.id] = m } }
        }
        messages[chat] = out
        return out
    }

    /// Les messages d'une discussion, du plus ancien au plus récent ; `limit`
    /// garde les derniers.
    public func messagesOf(_ chat: String, limit: Int = .max) -> [Message] {
        lock.lock(); defer { lock.unlock() }
        let sorted = loadChat(chat).values.sorted { ($0.at, $0.id) < ($1.at, $1.id) }
        return limit >= sorted.count ? sorted : Array(sorted.suffix(limit))
    }

    public func message(_ chat: String, _ id: String) -> Message? {
        lock.lock(); defer { lock.unlock() }
        return loadChat(chat)[id]
    }

    public func writeMessage(_ m: Message) {
        lock.lock()
        _ = loadChat(m.chat)
        messages[m.chat]?[m.id] = m
        lock.unlock()
        markDirty("messages:\(m.chat)")
    }

    public func removeMessage(_ chat: String, _ id: String) {
        lock.lock()
        _ = loadChat(chat)
        messages[chat]?[id] = nil
        lock.unlock()
        markDirty("messages:\(chat)")
    }

    public func deleteMessages(of chat: String) {
        lock.lock()
        messages[chat] = [:]
        lock.unlock()
        markDirty("messages:\(chat)")
    }

    /// Tous les messages de toutes les discussions (favoris, sauvegarde).
    public func allMessages() -> [Message] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.appendingPathComponent("messages").path)) ?? []
        lock.lock(); defer { lock.unlock() }
        var chats = Set(messages.keys)
        for chat in (rows["chats"] ?? [:]).keys { chats.insert(chat) }
        _ = names
        return chats.flatMap { loadChat($0).values }
    }

    // ----- L'écriture sur le disque -----

    func markDirty(_ what: String) {
        lock.lock()
        dirty.insert(what)
        let start = !saving
        saving = true
        lock.unlock()
        guard start else { return }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.25) { [weak self] in self?.save() }
    }

    /// Écrit ce qui a changé (appelé aussi quand l'application passe en
    /// arrière-plan).
    public func save() {
        lock.lock()
        let todo = dirty
        dirty.removeAll()
        saving = false
        var snapshot: [(URL, [String: String])] = []
        for what in todo {
            if what.hasPrefix("messages:") {
                let chat = String(what.dropFirst("messages:".count))
                let rows = (messages[chat] ?? [:]).mapValues { JSON.encode($0) }
                snapshot.append((chatFile(chat), Dictionary(uniqueKeysWithValues: rows.map { ("\(chat)|\($0.key)", $0.value) })))
            } else {
                snapshot.append((file(what), rows[what] ?? [:]))
            }
        }
        lock.unlock()
        for (url, rows) in snapshot {
            guard let data = try? JSONEncoder().encode(rows) else { continue }
            #if os(iOS)
            try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            #else
            try? data.write(to: url, options: .atomic)
            #endif
        }
    }

    // ----- La sauvegarde : le même format que le navigateur -----

    /// `{ magasin: [[clé, json], …] }` (les messages : `[clé, json, discussion, heure]`).
    public func dump() -> [String: Any] {
        var out: [String: Any] = [:]
        let all = allMessages()
        lock.lock(); defer { lock.unlock() }
        for name in Self.stores where name != "messages" {
            out[name] = (rows[name] ?? [:]).map { [$0.key, $0.value] }
        }
        out["messages"] = all.map { m -> [Any] in [m.key, JSON.encode(m), m.chat, m.at] }
        return out
    }

    public func restore(_ data: [String: Any]) {
        wipe()
        lock.lock()
        for name in Self.stores {
            guard let list = data[name] as? [[Any]] else { continue }
            for row in list {
                guard row.count >= 2, let key = row[0] as? String, let json = row[1] as? String else { continue }
                if name == "messages" {
                    if let m = JSON.decode(Message.self, json) {
                        if messages[m.chat] == nil { messages[m.chat] = [:] }
                        messages[m.chat]?[m.id] = m
                        dirty.insert("messages:\(m.chat)")
                    }
                } else {
                    rows[name, default: [:]][key] = json
                    dirty.insert(name)
                }
            }
        }
        lock.unlock()
        save()
    }

    public func wipe() {
        lock.lock()
        rows = [:]
        messages = [:]
        dirty.removeAll()
        lock.unlock()
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir.appendingPathComponent("messages"), withIntermediateDirectories: true)
    }

    /// L'espace occupé, en octets.
    public func usage() -> Int {
        guard let e = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var total = 0
        for case let url as URL in e {
            total += (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        }
        return total
    }
}
