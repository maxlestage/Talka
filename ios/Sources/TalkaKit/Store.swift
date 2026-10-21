// La mémoire de Talka sur l'iPhone : une base SQLite dans le dossier de
// l'application, protégée par iOS (illisible tant que l'iPhone n'a pas été
// déverrouillé depuis son démarrage). Pas de base de données ailleurs.
//
// Une seule table, de la même forme que le modèle Memoire du navigateur
// (`client/src/rows.rs`) : chaque ligne range un objet (en JSON) avec son
// magasin et sa clé ; les messages ont en plus leur discussion et leur heure,
// pour les lire dans l'ordre par l'index. Une sauvegarde faite dans le
// navigateur s'importe sur l'iPhone, et inversement.
//
// Ce qui a été lu est gardé en mémoire (les messages, décodés, discussion par
// discussion) ; chaque écriture part aussitôt dans SQLite. Un lot
// (`transaction`) rend plusieurs écritures atomiques : un message reçu et
// l'état de session v3 qui l'a ouvert.
//
// Chiffrée au repos, en plus de la protection d'iOS : chaque JSON est gardé
// chiffré (AES-256-GCM, lié à son magasin et à sa clé) avec la clé de
// l'appareil, tenue dans le trousseau (`StoreVault`), jamais dans la base.
// Talka le déchiffre en mémoire pour l'afficher ; une copie du fichier
// (sauvegarde non chiffrée de l'iPhone, extraction du disque) n'en montre
// rien. Les lignes d'avant (du texte en clair) sont réécrites chiffrées une
// fois. La sauvegarde Talka (`dump`) garde son format.

import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
#if canImport(SQLite3)
import SQLite3
#else
import CSQLite
#endif

public final class Store: @unchecked Sendable {
    public static let stores = ["kv", "contacts", "chats", "messages", "outbox", "statuses", "calls"]

    let dir: URL
    let lock = NSLock()
    var db: OpaquePointer?
    /// magasin → clé → JSON (tout sauf les messages).
    var rows: [String: [String: String]] = [:]
    /// discussion → identifiant → message (chargé à la demande).
    var messages: [String: [String: Message]] = [:]
    /// La profondeur des lots ouverts (`transaction`).
    var depth = 0
    /// La clé de l'appareil (nil : rien n'est chiffré).
    let key: SymmetricKey?
    /// Des lignes chiffrées, mais pas de clé pour les lire : on n'écrit plus
    /// rien (surtout pas une nouvelle identité par-dessus l'ancienne).
    var readOnly = false

    /// `key` : la clé de l'appareil (`StoreVault.key()` dans l'application).
    public init(directory: URL, key: SymmetricKey? = nil) {
        dir = directory
        self.key = key
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        openDatabase()
        migrateJSONFiles()
        sealPlainRows()
        loadRows()
    }

    // ----- Le chiffrement au repos -----

    /// Le début d'une valeur chiffrée : `t1` puis nonce (12) ‖ chiffré ‖ tag.
    static let magic = Data("t1".utf8)

    func aad(_ store: String, _ key: String) -> Data { Data("talka-repos:\(store)/\(key)".utf8) }

    func seal(_ store: String, _ k: String, _ json: String) -> Data? {
        guard let key, let box = try? AES.GCM.seal(Data(json.utf8), using: key, authenticating: aad(store, k)),
              let combined = box.combined else { return nil }
        return Self.magic + combined
    }

    /// Le JSON d'une ligne lue : en clair (d'avant), ou déchiffré ; nil si
    /// illisible (abîmé, ou pas la clé).
    func value(_ stmt: OpaquePointer, _ index: Int32, store: String, key k: String) -> String? {
        guard sqlite3_column_type(stmt, index) == SQLITE_BLOB else { return column(stmt, index) }
        let n = Int(sqlite3_column_bytes(stmt, index))
        guard let key, let p = sqlite3_column_blob(stmt, index), n > Self.magic.count + 28 else { return nil }
        let data = Data(bytes: p, count: n)
        guard data.prefix(2) == Self.magic,
              let box = try? AES.GCM.SealedBox(combined: data.dropFirst(2)),
              let plain = try? AES.GCM.open(box, using: key, authenticating: aad(store, k))
        else { return nil }
        return String(data: plain, encoding: .utf8)
    }

    /// Les lignes en clair (d'avant le chiffrement), réécrites chiffrées ;
    /// sans clé, des lignes chiffrées rendent la base en lecture seule.
    func sealPlainRows() {
        guard key != nil else {
            var sealed = false
            run("SELECT 1 FROM rows WHERE typeof(json) = 'blob' LIMIT 1") { _ in sealed = true }
            readOnly = sealed
            return
        }
        var plain: [(String, String, String, Double, String)] = []
        run("SELECT store, key, chat, at, json FROM rows WHERE typeof(json) = 'text'") { stmt in
            plain.append((column(stmt, 0), column(stmt, 1), column(stmt, 2), sqlite3_column_double(stmt, 3), column(stmt, 4)))
        }
        guard !plain.isEmpty else { return }
        exec("BEGIN")
        for (store, k, chat, at, json) in plain { put(store, k, json, chat: chat, at: at) }
        guard exec("COMMIT") else { exec("ROLLBACK"); return }
        // Plus rien du texte d'avant, ni dans le journal ni dans les pages
        // libérées.
        exec("PRAGMA wal_checkpoint(TRUNCATE)")
        exec("VACUUM")
    }

    deinit {
        if let db { sqlite3_close(db) }
    }

    /// Le dossier par défaut : `Application Support/Talka`.
    public static func defaultDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("Talka", isDirectory: true)
    }

    var databaseURL: URL { dir.appendingPathComponent("talka.sqlite") }

    // ----- SQLite -----

    func openDatabase() {
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(databaseURL.path, &db, flags, nil) == SQLITE_OK else { return }
        exec("PRAGMA journal_mode = WAL")
        exec("PRAGMA synchronous = NORMAL")
        // Ce qui est effacé ou remplacé est écrasé sur le disque.
        exec("PRAGMA secure_delete = ON")
        exec("""
            CREATE TABLE IF NOT EXISTS rows (
                store TEXT NOT NULL,
                key   TEXT NOT NULL,
                chat  TEXT NOT NULL DEFAULT '',
                at    REAL NOT NULL DEFAULT 0,
                json  TEXT NOT NULL,
                PRIMARY KEY (store, key)
            )
            """)
        exec("CREATE INDEX IF NOT EXISTS rows_chat_at ON rows (store, chat, at)")
        #if os(iOS)
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: databaseURL.path + suffix)
        }
        #endif
    }

    @discardableResult
    func exec(_ sql: String) -> Bool {
        sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK
    }

    /// Une requête préparée, ses paramètres liés (`String`, `Double`).
    func run(_ sql: String, _ params: [Any] = [], each: ((OpaquePointer) -> Void)? = nil) {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { return }
        defer { sqlite3_finalize(stmt) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (i, p) in params.enumerated() {
            let index = Int32(i + 1)
            switch p {
            case let s as String: sqlite3_bind_text(stmt, index, s, -1, transient)
            case let d as Data:
                _ = d.withUnsafeBytes { sqlite3_bind_blob(stmt, index, $0.baseAddress, Int32(d.count), transient) }
            case let d as Double: sqlite3_bind_double(stmt, index, d)
            default: sqlite3_bind_null(stmt, index)
            }
        }
        while sqlite3_step(stmt) == SQLITE_ROW { each?(stmt) }
    }


    /// Écrit une ligne, son JSON chiffré (en clair seulement sans clé).
    func put(_ store: String, _ key: String, _ json: String, chat: String = "", at: Double = 0) {
        guard !readOnly else { return }
        let value: Any = seal(store, key, json) ?? json
        run("INSERT OR REPLACE INTO rows (store, key, chat, at, json) VALUES (?, ?, ?, ?, ?)", [store, key, chat, at, value])
    }

    func delete(_ store: String, _ key: String) {
        guard !readOnly else { return }
        run("DELETE FROM rows WHERE store = ? AND key = ?", [store, key])
    }

    /// Les petits magasins, tout de suite en mémoire.
    func loadRows() {
        lock.lock(); defer { lock.unlock() }
        run("SELECT store, key, json FROM rows WHERE store != 'messages'") { stmt in
            let (store, k) = (column(stmt, 0), column(stmt, 1))
            if let json = self.value(stmt, 2, store: store, key: k) { self.rows[store, default: [:]][k] = json }
        }
    }

    // ----- Les magasins simples -----

    public func read<T: Decodable>(_ store: String, _ key: String, as type: T.Type = T.self) -> T? {
        lock.lock(); defer { lock.unlock() }
        return rows[store]?[key].flatMap { JSON.decode(T.self, $0) }
    }

    public func write<T: Encodable>(_ store: String, _ key: String, _ value: T) {
        writeRaw(store, key, JSON.encode(value))
    }

    /// Le JSON gardé tel quel (l'état v3, qui a son propre format).
    public func readRaw(_ store: String, _ key: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return rows[store]?[key]
    }

    public func writeRaw(_ store: String, _ key: String, _ json: String) {
        lock.lock(); defer { lock.unlock() }
        rows[store, default: [:]][key] = json
        put(store, key, json)
    }

    /// Les écritures de `body` dans une seule transaction SQLite : toutes ou
    /// aucune, même si l'application s'arrête au milieu. Les lots s'emboîtent
    /// (seul le plus extérieur valide).
    public func transaction<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        if depth == 0 { exec("BEGIN IMMEDIATE") }
        depth += 1
        lock.unlock()
        defer {
            lock.lock()
            depth -= 1
            if depth == 0, !exec("COMMIT") { exec("ROLLBACK") }
            lock.unlock()
        }
        return try body()
    }

    public func remove(_ store: String, _ key: String) {
        lock.lock(); defer { lock.unlock() }
        rows[store]?[key] = nil
        delete(store, key)
    }

    public func all<T: Decodable>(_ store: String, as type: T.Type = T.self) -> [T] {
        lock.lock(); defer { lock.unlock() }
        return (rows[store] ?? [:]).values.compactMap { JSON.decode(T.self, $0) }
    }

    public func clear(_ store: String) {
        lock.lock(); defer { lock.unlock() }
        rows[store] = [:]
        run("DELETE FROM rows WHERE store = ?", [store])
    }

    // ----- Les messages -----

    func loadChat(_ chat: String) -> [String: Message] {
        if let m = messages[chat] { return m }
        var out: [String: Message] = [:]
        run("SELECT key, json FROM rows WHERE store = 'messages' AND chat = ?", [chat]) { stmt in
            if let json = self.value(stmt, 1, store: "messages", key: column(stmt, 0)),
               let m = JSON.decode(Message.self, json) { out[m.id] = m }
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
        lock.lock(); defer { lock.unlock() }
        _ = loadChat(m.chat)
        messages[m.chat]?[m.id] = m
        put("messages", m.key, JSON.encode(m), chat: m.chat, at: m.at)
    }

    public func removeMessage(_ chat: String, _ id: String) {
        lock.lock(); defer { lock.unlock() }
        _ = loadChat(chat)
        messages[chat]?[id] = nil
        delete("messages", "\(chat)|\(id)")
    }

    public func deleteMessages(of chat: String) {
        lock.lock(); defer { lock.unlock() }
        messages[chat] = [:]
        run("DELETE FROM rows WHERE store = 'messages' AND chat = ?", [chat])
    }

    /// Tous les messages de toutes les discussions (favoris, sauvegarde).
    public func allMessages() -> [Message] {
        lock.lock(); defer { lock.unlock() }
        var out: [Message] = []
        run("SELECT key, json FROM rows WHERE store = 'messages' ORDER BY chat, at") { stmt in
            if let json = self.value(stmt, 1, store: "messages", key: column(stmt, 0)),
               let m = JSON.decode(Message.self, json) { out.append(m) }
        }
        return out
    }

    /// Chaque écriture est déjà dans SQLite ; à l'arrière-plan, on reporte le
    /// journal dans la base.
    public func save() {
        lock.lock(); defer { lock.unlock() }
        exec("PRAGMA wal_checkpoint(PASSIVE)")
    }

    // ----- La sauvegarde : le même format que le navigateur -----

    /// `{ magasin: [[clé, json], …] }` (les messages : `[clé, json, discussion, heure]`).
    public func dump() -> [String: Any] {
        lock.lock(); defer { lock.unlock() }
        var out: [String: Any] = [:]
        for name in Self.stores { out[name] = [[Any]]() }
        run("SELECT store, key, json, chat, at FROM rows ORDER BY store, key") { stmt in
            let store = column(stmt, 0), k = column(stmt, 1)
            guard let json = self.value(stmt, 2, store: store, key: k) else { return }
            var row: [Any] = [k, json]
            if store == "messages" { row += [column(stmt, 3), sqlite3_column_double(stmt, 4)] }
            out[store] = ((out[store] as? [[Any]]) ?? []) + [row]
        }
        return out
    }

    public func restore(_ data: [String: Any]) {
        wipe()
        lock.lock(); defer { lock.unlock() }
        exec("BEGIN")
        for name in Self.stores {
            guard let list = data[name] as? [[Any]] else { continue }
            for row in list {
                guard row.count >= 2, let key = row[0] as? String, let json = row[1] as? String else { continue }
                if name == "messages" {
                    let chat = row.count > 2 ? row[2] as? String ?? "" : ""
                    let at = row.count > 3 ? (row[3] as? Double ?? (row[3] as? NSNumber)?.doubleValue ?? 0) : 0
                    put(name, key, json, chat: chat, at: at)
                } else {
                    rows[name, default: [:]][key] = json
                    put(name, key, json)
                }
            }
        }
        exec("COMMIT")
    }

    public func wipe() {
        lock.lock(); defer { lock.unlock() }
        rows = [:]
        messages = [:]
        exec("DELETE FROM rows")
        exec("VACUUM")
        // Tout est effacé : on peut écrire à nouveau (avec la clé, s'il y en a une).
        readOnly = false
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

    // ----- Les données d'avant SQLite -----

    /// Les fichiers JSON des premières versions (un par magasin, un par
    /// discussion) : repris une fois dans SQLite, puis effacés.
    func migrateJSONFiles() {
        let fm = FileManager.default
        let messagesDir = dir.appendingPathComponent("messages")
        let files = Self.stores.filter { $0 != "messages" }.map { dir.appendingPathComponent("\($0).json") }
        guard files.contains(where: { fm.fileExists(atPath: $0.path) }) || fm.fileExists(atPath: messagesDir.path) else { return }
        exec("BEGIN")
        for (name, url) in zip(Self.stores.filter { $0 != "messages" }, files) {
            guard let data = try? Data(contentsOf: url),
                  let r = try? JSONDecoder().decode([String: String].self, from: data) else { continue }
            for (key, json) in r { put(name, key, json) }
        }
        for url in (try? fm.contentsOfDirectory(at: messagesDir, includingPropertiesForKeys: nil)) ?? [] {
            guard let data = try? Data(contentsOf: url),
                  let r = try? JSONDecoder().decode([String: String].self, from: data) else { continue }
            for (_, json) in r {
                if let m = JSON.decode(Message.self, json) { put("messages", m.key, json, chat: m.chat, at: m.at) }
            }
        }
        guard exec("COMMIT") else { exec("ROLLBACK"); return }
        for url in files { try? fm.removeItem(at: url) }
        try? fm.removeItem(at: messagesDir)
    }
}

/// Le texte d'une colonne du résultat.
private func column(_ stmt: OpaquePointer, _ index: Int32) -> String {
    sqlite3_column_text(stmt, index).map { String(cString: $0) } ?? ""
}
