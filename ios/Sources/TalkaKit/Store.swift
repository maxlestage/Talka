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
// Une discussion n'est chargée en entier (déchiffrée, décodée) que lorsqu'on
// demande tous ses messages : l'ouvrir, y chercher. Un message seul, les
// derniers, ceux d'avant une date se lisent par l'index, et une écriture ne
// charge rien : le lancement, la liste des discussions et un message qui
// arrive ne relisent pas tout l'historique. Une discussion chargée garde
// aussi sa liste triée, tenue à jour à chaque écriture.
//
// Chiffrée au repos, en plus de la protection d'iOS : chaque JSON est gardé
// chiffré (AES-256-GCM, lié à son magasin et à sa clé) avec la clé de
// l'appareil, tenue dans le trousseau (`StoreVault`, pour cet iPhone
// seulement, au besoin derrière Face ID), jamais dans la base ; la base est
// exclue des sauvegardes de l'iPhone.
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
    /// Une discussion chargée → ses messages dans l'ordre (heure, identifiant) :
    /// triés une fois, puis tenus à jour à chaque écriture.
    var ordered: [String: [Message]] = [:]
    /// Des messages lus ou écrits un par un (clé de ligne → message) dans des
    /// discussions pas chargées : la coche de la liste, un accusé qui arrive.
    var singles: [String: Message] = [:]
    static let singlesMax = 64
    /// Voir `messagesGeneration`.
    var generation = 0
    /// Les messages lus un par un dans SQLite, comptés par discussion pas
    /// chargée. Passé `loadAfter`, la discussion sert visiblement beaucoup
    /// (des dizaines de messages attendent un accusé, relus à chaque passage
    /// de la boîte d'envoi) : elle est chargée, une fois, plutôt que relue
    /// ligne à ligne sans fin.
    var singleReads: [String: Int] = [:]
    static let loadAfter = 96
    /// Les derniers messages de discussions pas chargées, déjà lus par
    /// l'index : la limite demandée, ce qu'elle a rendu, et s'il n'y en a pas
    /// d'autres. Le résumé de la montre les redemande toutes les deux
    /// secondes : tant que rien n'y est écrit, ils ne sont pas relus.
    var tails: [String: (limit: Int, complete: Bool, list: [Message])] = [:]
    /// Au-delà (marquer 300 messages comme lus), une lecture n'est pas gardée.
    static let tailMax = 64
    static let tailChats = 32
    /// La profondeur des lots ouverts (`transaction`).
    var depth = 0
    /// La clé de l'appareil (nil : rien n'est chiffré).
    public let key: SymmetricKey?
    /// Des lignes chiffrées, mais pas de clé pour les lire : on n'écrit plus
    /// rien (surtout pas une nouvelle identité par-dessus l'ancienne).
    var readOnly = false

    /// `key` : la clé de l'appareil (`StoreVault.key()` dans l'application).
    public init(directory: URL, key: SymmetricKey? = nil) {
        dir = directory
        self.key = key
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        #if os(iOS)
        // Ni iCloud ni la sauvegarde de l'iPhone : la sauvegarde Talka
        // (chiffrée par votre mot de passe) est faite pour ça.
        var url = dir
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        #endif
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
        guard let p = sqlite3_column_blob(stmt, index) else { return nil }
        return open(Data(bytes: p, count: n), store: store, key: k)
    }

    /// Le JSON d'une valeur chiffrée ; nil si illisible (abîmée, ou pas la
    /// clé). Sans SQLite ni rien qui change : peut tourner sur plusieurs
    /// cœurs à la fois.
    func open(_ data: Data, store: String, key k: String) -> String? {
        guard let key, data.count > Self.magic.count + 28, data.prefix(2) == Self.magic,
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

    /// Une requête préparée, ses paramètres liés (`String`, `Data`, `Double`, `Int`).
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
            case let n as Int: sqlite3_bind_int64(stmt, index, Int64(n))
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

    /// Toutes les lignes d'un magasin, telles quelles (clé → JSON).
    public func rawRows(_ store: String) -> [String: String] {
        lock.lock(); defer { lock.unlock() }
        return rows[store] ?? [:]
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
        lock.lock()
        let jsons = Array((rows[store] ?? [:]).values)
        lock.unlock()
        return Self.decodeAll(jsons)
    }

    /// Les objets d'une liste de JSON, dans le même ordre (les illisibles
    /// sautés). Une liste lourde (au lancement : les contacts et les
    /// discussions, avec leurs photos) est partagée entre les cœurs.
    static func decodeAll<T: Decodable>(_ jsons: [String]) -> [T] {
        let weight = jsons.reduce(0) { $0 + $1.utf8.count }
        let workers = min(ProcessInfo.processInfo.activeProcessorCount, 8, jsons.count, weight / 262_144)
        guard workers > 1 else { return jsons.compactMap { JSON.decode(T.self, $0) } }
        var parts = [[T]](repeating: [], count: workers)
        parts.withUnsafeMutableBufferPointer { buffer in
            let slots = buffer
            DispatchQueue.concurrentPerform(iterations: workers) { w in
                let range = (jsons.count * w / workers)..<(jsons.count * (w + 1) / workers)
                var found: [T] = []
                found.reserveCapacity(range.count)
                for i in range { if let v = JSON.decode(T.self, jsons[i]) { found.append(v) } }
                slots[w] = found
            }
        }
        return parts.flatMap { $0 }
    }

    public func clear(_ store: String) {
        lock.lock(); defer { lock.unlock() }
        rows[store] = [:]
        run("DELETE FROM rows WHERE store = ?", [store])
    }

    // ----- Les messages -----

    /// Toute une discussion, lue une fois puis gardée.
    func loadChat(_ chat: String) -> [String: Message] {
        if let m = messages[chat] { return m }
        var out: [String: Message] = [:]
        for m in readMessages("SELECT key, json FROM rows WHERE store = 'messages' AND chat = ?", [chat]) { out[m.id] = m }
        messages[chat] = out
        forgetSingles(of: chat)
        tails[chat] = nil
        singleReads[chat] = nil
        return out
    }

    /// Une ligne de message lue, pas encore ouverte : sa clé, et son JSON
    /// chiffré, ou en clair (une ligne d'avant le chiffrement).
    struct MessageRow {
        var key: String
        var sealed: Data?
        var json: String?
    }

    /// Un paquet : au plus tant de lignes, ou tant d'octets (des photos),
    /// tenus en mémoire le temps de les décoder.
    static let batchRows = 1024
    static let batchBytes = 8 << 20

    func decode(_ row: MessageRow) -> Message? {
        let json = row.sealed.map { open($0, store: "messages", key: row.key) } ?? row.json
        return json.flatMap { JSON.decode(Message.self, $0) }
    }

    /// Les messages d'une requête (`SELECT key, json …`), dans l'ordre de
    /// ses lignes. Elles sont lues par paquets ; chaque paquet est déchiffré
    /// et décodé sur plusieurs cœurs (le gros du temps d'ouverture d'une
    /// longue discussion).
    func readMessages(_ sql: String, _ params: [Any] = []) -> [Message] {
        var out: [Message] = []
        var batch: [MessageRow] = []
        var bytes = 0
        run(sql, params) { stmt in
            let k = column(stmt, 0)
            if sqlite3_column_type(stmt, 1) == SQLITE_BLOB {
                let n = Int(sqlite3_column_bytes(stmt, 1))
                guard let p = sqlite3_column_blob(stmt, 1) else { return }
                batch.append(MessageRow(key: k, sealed: Data(bytes: p, count: n)))
                bytes += n
            } else {
                let json = column(stmt, 1)
                bytes += json.utf8.count
                batch.append(MessageRow(key: k, json: json))
            }
            if batch.count >= Self.batchRows || bytes >= Self.batchBytes {
                out.append(contentsOf: self.decode(batch))
                batch.removeAll(keepingCapacity: true)
                bytes = 0
            }
        }
        out.append(contentsOf: decode(batch))
        return out
    }

    /// Les messages d'un paquet de lignes, dans le même ordre ; les lignes
    /// illisibles sont sautées. Un petit paquet est décodé sur place ; un
    /// gros est partagé entre les cœurs.
    func decode(_ rows: [MessageRow]) -> [Message] {
        // Assez de lignes, ou assez d'octets (quelques photos suffisent),
        // pour que le partage vaille son coût.
        let weight = rows.reduce(0) { $0 + ($1.sealed?.count ?? $1.json?.utf8.count ?? 0) }
        let workers = min(ProcessInfo.processInfo.activeProcessorCount, 8, rows.count, max(rows.count / 64, weight / 262_144))
        guard workers > 1 else { return rows.compactMap { decode($0) } }
        var parts = [[Message]](repeating: [], count: workers)
        parts.withUnsafeMutableBufferPointer { buffer in
            let slots = buffer
            DispatchQueue.concurrentPerform(iterations: workers) { w in
                let range = (rows.count * w / workers)..<(rows.count * (w + 1) / workers)
                var found: [Message] = []
                found.reserveCapacity(range.count)
                for i in range { if let m = self.decode(rows[i]) { found.append(m) } }
                slots[w] = found
            }
        }
        return parts.flatMap { $0 }
    }

    /// Les messages d'une discussion (chargée au besoin), dans l'ordre.
    func inOrder(_ chat: String) -> [Message] {
        if let kept = ordered[chat] { return kept }
        let sorted = loadChat(chat).values.sorted { ($0.at, $0.id) < ($1.at, $1.id) }
        ordered[chat] = sorted
        return sorted
    }

    /// Le rang de (heure, identifiant) dans une liste triée : celui du
    /// premier message qui n'est pas avant.
    static func rank(_ at: Double, _ id: String, in list: [Message]) -> Int {
        var lo = 0, hi = list.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if (list[mid].at, list[mid].id) < (at, id) { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }

    /// Tient à jour la liste triée d'une discussion chargée : `old` en sort,
    /// `new` y prend sa place. Si `old` n'est pas où il devrait, la liste est
    /// oubliée (retriée à la prochaine lecture).
    func reorder(_ chat: String, old: Message?, new: Message?) {
        guard var list = ordered.removeValue(forKey: chat) else { return }
        if let old {
            let i = Self.rank(old.at, old.id, in: list)
            guard i < list.count, list[i].id == old.id else { return }
            if let new, new.at == old.at {
                list[i] = new
                ordered[chat] = list
                return
            }
            list.remove(at: i)
        }
        if let new { list.insert(new, at: Self.rank(new.at, new.id, in: list)) }
        ordered[chat] = list
    }

    func remember(_ key: String, _ m: Message) {
        if singles.count >= Self.singlesMax, singles[key] == nil { singles.removeAll(keepingCapacity: true) }
        singles[key] = m
    }

    func forgetSingles(of chat: String) {
        guard !singles.isEmpty else { return }
        let prefix = chat + "|"
        singles = singles.filter { !$0.key.hasPrefix(prefix) }
    }

    /// Les `limit` derniers messages d'une discussion pas chargée, par
    /// l'index ; gardés (`tails`) jusqu'à la prochaine écriture dans la
    /// discussion.
    func tail(_ chat: String, _ limit: Int) -> [Message] {
        guard limit > 0 else { return [] }
        if let kept = tails[chat], kept.complete || kept.limit >= limit {
            return limit >= kept.list.count ? kept.list : Array(kept.list.suffix(limit))
        }
        var out: [Message] = []
        var read = 0
        run("SELECT key, json FROM rows WHERE store = 'messages' AND chat = ? ORDER BY at DESC, key DESC LIMIT ?", [chat, limit]) { stmt in
            read += 1
            if let json = self.value(stmt, 1, store: "messages", key: column(stmt, 0)),
               let m = JSON.decode(Message.self, json) { out.append(m) }
        }
        let list = Array(out.reversed())
        if limit <= Self.tailMax {
            if tails.count >= Self.tailChats, tails[chat] == nil { tails.removeAll(keepingCapacity: true) }
            tails[chat] = (limit, read < limit, list)
        }
        return list
    }

    /// Les messages d'une discussion, du plus ancien au plus récent ; `limit`
    /// garde les derniers (sans charger la discussion si elle ne l'est pas).
    public func messagesOf(_ chat: String, limit: Int = .max) -> [Message] {
        lock.lock(); defer { lock.unlock() }
        if limit != .max, messages[chat] == nil { return tail(chat, limit) }
        let sorted = inOrder(chat)
        return limit >= sorted.count ? sorted : Array(sorted.suffix(limit))
    }

    /// Les messages d'une discussion datés d'avant `at`, du plus ancien au
    /// plus récent : par l'index si elle n'est pas chargée (le ménage des
    /// vieux messages ne relit pas tout l'historique).
    public func messagesBefore(_ chat: String, _ at: Double) -> [Message] {
        lock.lock(); defer { lock.unlock() }
        if messages[chat] != nil {
            let sorted = inOrder(chat)
            return Array(sorted.prefix(Self.rank(at, "", in: sorted)))
        }
        var out: [Message] = []
        run("SELECT key, json FROM rows WHERE store = 'messages' AND chat = ? AND at < ? ORDER BY at, key", [chat, at]) { stmt in
            if let json = self.value(stmt, 1, store: "messages", key: column(stmt, 0)),
               let m = JSON.decode(Message.self, json) { out.append(m) }
        }
        return out
    }

    /// Un message ; d'une discussion pas chargée, sa seule ligne est lue.
    public func message(_ chat: String, _ id: String) -> Message? {
        lock.lock(); defer { lock.unlock() }
        if let loaded = messages[chat] { return loaded[id] }
        let key = "\(chat)|\(id)"
        if let m = singles[key] { return m }
        var out: Message?
        run("SELECT json FROM rows WHERE store = 'messages' AND key = ?", [key]) { stmt in
            if let json = self.value(stmt, 0, store: "messages", key: key) { out = JSON.decode(Message.self, json) }
        }
        guard let out else { return nil }
        singleReads[chat, default: 0] += 1
        if singleReads[chat, default: 0] > Self.loadAfter { return loadChat(chat)[id] }
        remember(key, out)
        return out
    }

    public func writeMessage(_ m: Message) {
        lock.lock(); defer { lock.unlock() }
        generation += 1
        if messages[m.chat] != nil {
            let old = messages[m.chat]?.updateValue(m, forKey: m.id) ?? nil
            reorder(m.chat, old: old, new: m)
        } else {
            remember(m.key, m)
            tails[m.chat] = nil
        }
        put("messages", m.key, JSON.encode(m), chat: m.chat, at: m.at)
    }

    public func removeMessage(_ chat: String, _ id: String) {
        lock.lock(); defer { lock.unlock() }
        generation += 1
        if messages[chat] != nil {
            if let old = messages[chat]?.removeValue(forKey: id) ?? nil { reorder(chat, old: old, new: nil) }
        } else {
            singles["\(chat)|\(id)"] = nil
            tails[chat] = nil
        }
        delete("messages", "\(chat)|\(id)")
    }

    public func deleteMessages(of chat: String) {
        lock.lock(); defer { lock.unlock() }
        generation += 1
        messages[chat] = [:]
        ordered[chat] = nil
        tails[chat] = nil
        singleReads[chat] = nil
        forgetSingles(of: chat)
        run("DELETE FROM rows WHERE store = 'messages' AND chat = ?", [chat])
    }

    /// Tous les messages de toutes les discussions (favoris, sauvegarde).
    public func allMessages() -> [Message] {
        lock.lock(); defer { lock.unlock() }
        return readMessages("SELECT key, json FROM rows WHERE store = 'messages' ORDER BY chat, at")
    }

    /// Change à chaque écriture ou suppression de message (et quand tout est
    /// effacé ou restauré) : ce qui a été calculé sur tous les messages (les
    /// messages importants) reste valable tant qu'il n'a pas bougé.
    public var messagesGeneration: Int {
        lock.lock(); defer { lock.unlock() }
        return generation
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
        var out: [String: [[Any]]] = [:]
        for name in Self.stores { out[name] = [] }
        run("SELECT store, key, json, chat, at FROM rows ORDER BY store, key") { stmt in
            let store = column(stmt, 0), k = column(stmt, 1)
            guard let json = self.value(stmt, 2, store: store, key: k) else { return }
            var row: [Any] = [k, json]
            if store == "messages" { row += [column(stmt, 3), sqlite3_column_double(stmt, 4)] }
            out[store, default: []].append(row)
        }
        return out
    }

    public func restore(_ data: [String: Any]) {
        wipe()
        lock.lock(); defer { lock.unlock() }
        generation += 1
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
        ordered = [:]
        singles = [:]
        tails = [:]
        singleReads = [:]
        generation += 1
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
