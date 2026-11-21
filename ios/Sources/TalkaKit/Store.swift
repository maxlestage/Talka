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
    /// La fin de discussions pas chargées en entier : leurs derniers
    /// messages, dans l'ordre, lus par l'index puis tenus à jour à chaque
    /// écriture. Il en existe de plus anciens (une discussion qui tient en
    /// entier dans ce qu'on lui demande est chargée). C'est ce que montre une
    /// longue discussion à son ouverture, et ce que redemande le résumé de la
    /// montre toutes les deux secondes.
    var windows: [String: [Message]] = [:]
    /// Les discussions qui ont une fenêtre, de la moins récemment demandée à
    /// la plus récente : au-delà de `windowChats`, la plus ancienne est oubliée.
    var windowOrder: [String] = []
    static let windowChats = 32
    /// Combien des dernières lignes d'une discussion sa fenêtre couvre : le
    /// nombre de ses messages, sauf si des lignes y sont illisibles (elles
    /// comptent, sans y être). Sans cela, une fenêtre plus courte que ce
    /// qu'on lui demande serait relue dans SQLite à chaque fois.
    var windowCovers: [String: Int] = [:]

    /// Par discussion, la plus proche échéance parmi ses messages éphémères
    /// (l'infini : aucun n'en a), telle que le dernier ménage l'a trouvée ;
    /// absente tant que la discussion n'a pas été examinée. Chaque message
    /// écrit avec une échéance plus proche la rapproche ; un message retiré
    /// ou modifié peut la laisser trop proche, ce qui ne coûte qu'un examen
    /// de plus.
    var expNext: [String: Double] = [:]

    /// Par discussion, les identifiants de ses messages importants ; absents
    /// tant qu'elle n'a pas été examinée, puis tenus à jour à chaque message
    /// écrit ou retiré. Les écrans le demandent sans cesse (le compte des
    /// messages importants dans les infos, « Vider la discussion ? ») : pas
    /// de quoi charger toute la discussion.
    var starredIn: [String: Set<String>] = [:]

    /// Par discussion, ses pièces jointes (photos, documents, vocaux) et ce
    /// qu'elles pèsent ; absentes tant qu'elle n'a pas été examinée, puis
    /// tenues à jour à chaque message écrit ou retiré. « Gérer le stockage »
    /// les additionne, sans charger aucune discussion.
    var piecesIn: [String: [String: Storage.Piece]] = [:]
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
        dropWindow(chat)
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

    func decode<T: Decodable>(_ row: MessageRow, as type: T.Type) -> T? {
        let json = row.sealed.map { open($0, store: "messages", key: row.key) } ?? row.json
        return json.flatMap { JSON.decode(type, $0) }
    }

    /// Les messages d'une requête (`SELECT key, json …`), dans l'ordre de
    /// ses lignes. Elles sont lues par paquets ; chaque paquet est déchiffré
    /// et décodé sur plusieurs cœurs (le gros du temps d'ouverture d'une
    /// longue discussion).
    func readMessages(_ sql: String, _ params: [Any] = []) -> [Message] {
        var rows = 0
        return readMessages(sql, params, rows: &rows)
    }

    /// `rows` : le nombre de lignes lues, lisibles ou non.
    func readMessages(_ sql: String, _ params: [Any], rows: inout Int) -> [Message] {
        readRows(sql, params, rows: &rows, as: Message.self)
    }

    /// Les mêmes lignes décodées en autre chose qu'un message entier (ses
    /// seules échéances, par exemple), de la même façon.
    func readRows<T: Decodable>(_ sql: String, _ params: [Any], rows: inout Int, as type: T.Type) -> [T] {
        var out: [T] = []
        var batch: [MessageRow] = []
        var bytes = 0
        var read = 0
        defer { rows = read }
        run(sql, params) { stmt in
            read += 1
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
                out.append(contentsOf: self.decode(batch, as: type))
                batch.removeAll(keepingCapacity: true)
                bytes = 0
            }
        }
        out.append(contentsOf: decode(batch, as: type))
        return out
    }

    func decode(_ rows: [MessageRow]) -> [Message] {
        decode(rows, as: Message.self)
    }

    /// Les messages d'un paquet de lignes, dans le même ordre ; les lignes
    /// illisibles sont sautées. Un petit paquet est décodé sur place ; un
    /// gros est partagé entre les cœurs.
    func decode<T: Decodable>(_ rows: [MessageRow], as type: T.Type) -> [T] {
        // Assez de lignes, ou assez d'octets (quelques photos suffisent),
        // pour que le partage vaille son coût.
        let weight = rows.reduce(0) { $0 + ($1.sealed?.count ?? $1.json?.utf8.count ?? 0) }
        let workers = min(ProcessInfo.processInfo.activeProcessorCount, 8, rows.count, max(rows.count / 64, weight / 262_144))
        guard workers > 1 else { return rows.compactMap { decode($0, as: type) } }
        var parts = [[T]](repeating: [], count: workers)
        parts.withUnsafeMutableBufferPointer { buffer in
            let slots = buffer
            DispatchQueue.concurrentPerform(iterations: workers) { w in
                let range = (rows.count * w / workers)..<(rows.count * (w + 1) / workers)
                var found: [T] = []
                found.reserveCapacity(range.count)
                for i in range { if let m = self.decode(rows[i], as: type) { found.append(m) } }
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

    /// La fenêtre d'une discussion vient d'être demandée : elle passe en
    /// tête, et la moins récemment demandée est oubliée s'il y en a trop.
    func touchWindow(_ chat: String) {
        if windowOrder.last == chat { return }
        windowOrder.removeAll { $0 == chat }
        windowOrder.append(chat)
        while windowOrder.count > Self.windowChats {
            let old = windowOrder.removeFirst()
            windows[old] = nil
            windowCovers[old] = nil
        }
    }

    func dropWindow(_ chat: String) {
        windowCovers[chat] = nil
        if windows.removeValue(forKey: chat) != nil { windowOrder.removeAll { $0 == chat } }
    }

    /// La fin d'une discussion pas chargée : au moins ses `limit` derniers
    /// messages (davantage si la mémoire en tient déjà plus). Lus par
    /// l'index, une fois, puis tenus à jour. `nil` : il n'y en a pas de plus
    /// anciens, la discussion tient en entier et vient d'être chargée.
    func window(_ chat: String, _ limit: Int) -> [Message]? {
        if let kept = windows[chat], (windowCovers[chat] ?? kept.count) >= limit {
            touchWindow(chat)
            return kept
        }
        // Un de plus que demandé : pour savoir s'il en reste de plus anciens.
        var rows = 0
        let found = readMessages(
            "SELECT key, json FROM rows WHERE store = 'messages' AND chat = ? ORDER BY at DESC, key DESC LIMIT ?",
            [chat, limit + 1], rows: &rows)
        guard rows > limit else {
            var all: [String: Message] = [:]
            for m in found { all[m.id] = m }
            messages[chat] = all
            ordered[chat] = nil
            forgetSingles(of: chat)
            dropWindow(chat)
            singleReads[chat] = nil
            return nil
        }
        var list = Array(found.reversed())
        // La ligne de plus n'est pas gardée ; si des lignes sont illisibles,
        // tout ce qui a été lu reste, et la fenêtre couvre ces lignes-là.
        var covers = rows
        if list.count > limit {
            covers -= list.count - limit
            list.removeFirst(list.count - limit)
        }
        windows[chat] = list
        windowCovers[chat] = covers
        touchWindow(chat)
        return list
    }

    /// Tient à jour la fenêtre d'une discussion pas chargée : l'ancienne
    /// version du message (s'il y était) en sort, la nouvelle (`new`) y prend
    /// sa place si elle n'est pas plus ancienne que le début de la fenêtre.
    func slide(_ chat: String, id: String, new: Message?) {
        guard var list = windows.removeValue(forKey: chat) else { return }
        var covers = windowCovers[chat] ?? list.count
        if let i = list.lastIndex(where: { $0.id == id }) {
            list.remove(at: i)
            covers -= 1
        }
        if let new {
            // Vide, on ne sait plus où elle commence : oubliée.
            guard let first = list.first else {
                windowOrder.removeAll { $0 == chat }
                windowCovers[chat] = nil
                return
            }
            if (first.at, first.id) < (new.at, new.id) {
                list.insert(new, at: Self.rank(new.at, new.id, in: list))
                covers += 1
            }
        }
        windows[chat] = list
        windowCovers[chat] = covers
    }

    /// Les messages d'une discussion, du plus ancien au plus récent ; `limit`
    /// garde les derniers (sans charger la discussion si elle ne l'est pas).
    public func messagesOf(_ chat: String, limit: Int = .max) -> [Message] {
        lock.lock(); defer { lock.unlock() }
        if limit < Int.max - 1, messages[chat] == nil {
            guard limit > 0 else { return [] }
            if let end = window(chat, limit) { return end.count > limit ? Array(end.suffix(limit)) : end }
        }
        let sorted = inOrder(chat)
        return limit >= sorted.count ? sorted : Array(sorted.suffix(limit))
    }

    /// La fin d'une discussion, pour l'afficher : au moins ses `limit`
    /// derniers messages, du plus ancien au plus récent (davantage si la
    /// mémoire en tient déjà plus), et s'il en existe de plus anciens. Une
    /// longue discussion s'ouvre ainsi sans être lue en entier ; `limit`
    /// grandit quand on remonte.
    public func recentMessages(_ chat: String, limit: Int) -> (list: [Message], more: Bool) {
        lock.lock(); defer { lock.unlock() }
        guard limit > 0 else { return ([], false) }
        if limit < Int.max - 1, messages[chat] == nil, let end = window(chat, limit) { return (end, true) }
        let sorted = inOrder(chat)
        return sorted.count > limit ? (Array(sorted.suffix(limit)), true) : (sorted, false)
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
        // Dans la fin déjà en mémoire (le plus souvent : un message récent).
        if let m = windows[chat]?.last(where: { $0.id == id }) { return m }
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
            slide(m.chat, id: m.id, new: m)
        }
        if starredIn[m.chat] != nil {
            if m.isStarred { starredIn[m.chat]?.insert(m.id) } else { starredIn[m.chat]?.remove(m.id) }
        }
        if piecesIn[m.chat] != nil {
            piecesIn[m.chat]?[m.id] = Storage.Piece(kind: m.kind, deleted: m.deleted, size: m.size, data: m.data)
        }
        // Une échéance plus proche que celle qu'on connaissait.
        if let exp = m.exp, let next = expNext[m.chat], exp < next { expNext[m.chat] = exp }
        put("messages", m.key, JSON.encode(m), chat: m.chat, at: m.at)
    }

    public func removeMessage(_ chat: String, _ id: String) {
        lock.lock(); defer { lock.unlock() }
        drop(chat, id)
    }

    /// Le retrait d'un message, verrou déjà pris.
    func drop(_ chat: String, _ id: String) {
        generation += 1
        starredIn[chat]?.remove(id)
        piecesIn[chat]?[id] = nil
        if messages[chat] != nil {
            if let old = messages[chat]?.removeValue(forKey: id) ?? nil { reorder(chat, old: old, new: nil) }
        } else {
            singles["\(chat)|\(id)"] = nil
            slide(chat, id: id, new: nil)
        }
        delete("messages", "\(chat)|\(id)")
    }

    /// Retire d'une discussion ses messages éphémères arrivés à échéance
    /// avant `t`, et les renvoie (identifiant, date), du plus ancien au plus
    /// récent.
    ///
    /// Le ménage le demande chaque minute pour chaque discussion à messages
    /// éphémères : la plus proche échéance restante est retenue, et tant
    /// qu'elle n'est pas passée rien n'est relu. Quand il faut lire une
    /// discussion pas chargée, seules les échéances de ses messages sont
    /// décodées, et rien n'en est gardé en mémoire.
    public func removeExpired(_ chat: String, before t: Double) -> [(String, Double)] {
        lock.lock(); defer { lock.unlock() }
        if let next = expNext[chat], next >= t { return [] }
        var gone: [(String, Double)] = []
        var next = Double.infinity
        func see(_ id: String, _ at: Double, _ exp: Double?) {
            guard let exp else { return }
            if exp < t { gone.append((id, at)) } else { next = min(next, exp) }
        }
        if messages[chat] != nil {
            for m in inOrder(chat) { see(m.id, m.at, m.exp) }
        } else {
            var rows = 0
            let heads = readRows("SELECT key, json FROM rows WHERE store = 'messages' AND chat = ? ORDER BY at, key",
                                 [chat], rows: &rows, as: Message.Expiry.self)
            for h in heads { see(h.id, h.at, h.exp) }
        }
        for (id, _) in gone { drop(chat, id) }
        expNext[chat] = next
        return gone
    }

    /// Les identifiants des messages importants d'une discussion (ceux qui
    /// ne sont pas supprimés). D'une discussion pas chargée, seules ces
    /// marques sont décodées, une fois ; rien d'autre n'est gardé en mémoire.
    public func starredIds(_ chat: String) -> Set<String> {
        lock.lock(); defer { lock.unlock() }
        if let kept = starredIn[chat] { return kept }
        var ids = Set<String>()
        if messages[chat] != nil {
            for m in inOrder(chat) where m.isStarred { ids.insert(m.id) }
        } else {
            var rows = 0
            let marks = readRows("SELECT key, json FROM rows WHERE store = 'messages' AND chat = ?",
                                 [chat], rows: &rows, as: Message.Star.self)
            for mark in marks where mark.starred { ids.insert(mark.id) }
        }
        starredIn[chat] = ids
        return ids
    }

    /// Ce que pèsent les pièces jointes d'une discussion (voir `Storage.of`).
    /// D'une discussion pas chargée, seuls ces poids sont décodés, une fois ;
    /// ni ses messages ni ses photos ne restent en mémoire.
    public func storage(of chat: String) -> Storage {
        lock.lock(); defer { lock.unlock() }
        var st = Storage()
        if let kept = piecesIn[chat] {
            for piece in kept.values { st.add(piece) }
            return st
        }
        var pieces: [String: Storage.Piece] = [:]
        if messages[chat] != nil {
            for m in inOrder(chat) {
                pieces[m.id] = Storage.Piece(kind: m.kind, deleted: m.deleted, size: m.size, data: m.data)
            }
        } else {
            var rows = 0
            let weights = readRows("SELECT key, json FROM rows WHERE store = 'messages' AND chat = ?",
                                   [chat], rows: &rows, as: Message.Weight.self)
            for w in weights { pieces[w.id] = w.piece }
        }
        piecesIn[chat] = pieces
        for piece in pieces.values { st.add(piece) }
        return st
    }

    public func deleteMessages(of chat: String) {
        lock.lock(); defer { lock.unlock() }
        generation += 1
        expNext[chat] = nil
        starredIn[chat] = nil
        piecesIn[chat] = nil
        messages[chat] = [:]
        ordered[chat] = nil
        dropWindow(chat)
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
        // Des messages écrits sans passer par `writeMessage`.
        expNext = [:]
        starredIn = [:]
        piecesIn = [:]
    }

    public func wipe() {
        lock.lock(); defer { lock.unlock() }
        rows = [:]
        messages = [:]
        ordered = [:]
        singles = [:]
        windows = [:]
        windowOrder = []
        windowCovers = [:]
        expNext = [:]
        starredIn = [:]
        piecesIn = [:]
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
