// La mémoire de Talka sur l'appareil : une base SQLite dans le dossier de
// l'application (donné par l'application, avec son SQLite : `SqlOpener`).
// Pas de base de données ailleurs.
//
// Une seule table, de la même forme que le modèle Memoire du navigateur
// (`client/src/rows.rs`) et que l'iPhone (`Store.swift`) : chaque ligne range
// un objet (en JSON) avec son magasin et sa clé ; les messages ont en plus
// leur discussion et leur heure, pour les lire dans l'ordre par l'index. Une
// sauvegarde faite dans le navigateur ou sur l'iPhone s'importe ici, et
// inversement.
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
// Chiffrée au repos : chaque JSON est gardé chiffré (AES-256-GCM, lié à son
// magasin et à sa clé) avec la clé de l'appareil, tenue par le système
// (`StoreVault`), jamais dans la base. Talka le déchiffre en mémoire pour
// l'afficher ; une copie du fichier n'en montre rien. Les lignes d'avant (du
// texte en clair) sont réécrites chiffrées une fois. La sauvegarde Talka
// (`dump`) garde son format.
//
// Les listes rendues ne changent plus ensuite : chaque écriture remplace la
// liste gardée par une nouvelle (comme les tableaux du Swift, copiés à
// l'écriture).

package talka.kit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import java.util.stream.IntStream
import kotlin.concurrent.withLock

/** La fin d'une discussion : ses derniers messages, et s'il en existe de plus anciens. */
data class RecentMessages(val list: List<Message>, val more: Boolean)

/**
 * `directory` : le dossier de Talka ; `sql` : le SQLite de l'appareil ;
 * `key` : la clé de l'appareil (`StoreVault.key()` dans l'application ;
 * rien : rien n'est chiffré).
 */
class Store(directory: File, sql: SqlOpener, val key: ByteArray? = null) : Closeable {
    val dir: File = directory

    private val lock = ReentrantLock()
    private var db: SqlDb? = null

    /** magasin → clé → JSON (tout sauf les messages). */
    internal val rows = HashMap<String, HashMap<String, String>>()

    /** discussion → identifiant → message (chargé à la demande). */
    internal val messages = HashMap<String, HashMap<String, Message>>()

    /**
     * Une discussion chargée → ses messages dans l'ordre (heure, identifiant) :
     * triés une fois, puis tenus à jour à chaque écriture.
     */
    internal val ordered = HashMap<String, List<Message>>()

    /**
     * Des messages lus ou écrits un par un (clé de ligne → message) dans des
     * discussions pas chargées : la coche de la liste, un accusé qui arrive.
     */
    internal var singles = HashMap<String, Message>()

    /** Voir `messagesGeneration`. */
    private var generation = 0

    /**
     * Les messages lus un par un dans SQLite, comptés par discussion pas
     * chargée. Passé `loadAfter`, la discussion sert visiblement beaucoup
     * (des dizaines de messages attendent un accusé, relus à chaque passage
     * de la boîte d'envoi) : elle est chargée, une fois, plutôt que relue
     * ligne à ligne sans fin.
     */
    internal val singleReads = HashMap<String, Int>()

    /** Les discussions chargées pour cette raison : `release` les laisse. */
    internal val busy = HashSet<String>()

    /**
     * La fin de discussions pas chargées en entier : leurs derniers
     * messages, dans l'ordre, lus par l'index puis tenus à jour à chaque
     * écriture. Il en existe de plus anciens (une discussion qui tient en
     * entier dans ce qu'on lui demande est chargée). C'est ce que montre une
     * longue discussion à son ouverture, et ce que redemande le résumé de la
     * montre toutes les deux secondes.
     */
    internal val windows = HashMap<String, List<Message>>()

    /**
     * Les discussions qui ont une fenêtre, de la moins récemment demandée à
     * la plus récente : au-delà de `windowChats`, la plus ancienne est oubliée.
     */
    internal val windowOrder = ArrayList<String>()

    /**
     * Combien des dernières lignes d'une discussion sa fenêtre couvre : le
     * nombre de ses messages, sauf si des lignes y sont illisibles (elles
     * comptent, sans y être). Sans cela, une fenêtre plus courte que ce
     * qu'on lui demande serait relue dans SQLite à chaque fois.
     */
    internal val windowCovers = HashMap<String, Int>()

    /**
     * Par discussion, la plus proche échéance parmi ses messages éphémères
     * (l'infini : aucun n'en a), telle que le dernier ménage l'a trouvée ;
     * absente tant que la discussion n'a pas été examinée. Chaque message
     * écrit avec une échéance plus proche la rapproche ; un message retiré
     * ou modifié peut la laisser trop proche, ce qui ne coûte qu'un examen
     * de plus.
     */
    internal val expNext = HashMap<String, Double>()

    /**
     * Par discussion, les identifiants de ses messages importants ; absents
     * tant qu'elle n'a pas été examinée, puis tenus à jour à chaque message
     * écrit ou retiré. Les écrans le demandent sans cesse (le compte des
     * messages importants dans les infos, « Vider la discussion ? ») : pas
     * de quoi charger toute la discussion.
     */
    internal val starredIn = HashMap<String, HashSet<String>>()

    /**
     * Par discussion, ses pièces jointes (photos, documents, vocaux) et ce
     * qu'elles pèsent ; absentes tant qu'elle n'a pas été examinée, puis
     * tenues à jour à chaque message écrit ou retiré. « Gérer le stockage »
     * les additionne, sans charger aucune discussion.
     */
    internal val piecesIn = HashMap<String, HashMap<String, Storage.Piece>>()

    /** Les lignes de texte d'une discussion, pour la recherche : une liste, et la place de chaque message dedans. */
    internal class Lines {
        val list = ArrayList<Message.Line>()
        val place = HashMap<String, Int>()

        fun put(line: Message.Line) {
            val i = place[line.id]
            if (i != null) {
                list[i] = line
            } else {
                place[line.id] = list.size
                list.add(line)
            }
        }

        fun remove(id: String) {
            val i = place.remove(id) ?: return
            val last = list.removeAt(list.size - 1)
            if (i < list.size) {
                list[i] = last
                place[last.id] = i
            }
        }
    }

    /**
     * Par discussion, le texte de ses messages (sans leurs photos) ; absent
     * tant que la recherche dans toutes les discussions ne l'a pas demandé,
     * puis tenu à jour à chaque message écrit ou retiré. Oublié à
     * l'arrière-plan (`forgetLines`).
     */
    internal val linesIn = HashMap<String, Lines>()

    /** La profondeur des lots ouverts (`transaction`). */
    private var depth = 0

    /**
     * Des lignes chiffrées, mais pas de clé pour les lire : on n'écrit plus
     * rien (surtout pas une nouvelle identité par-dessus l'ancienne).
     */
    internal var readOnly = false

    val databaseFile: File get() = File(dir, "talka.sqlite")

    init {
        dir.mkdirs()
        db = sql.open(databaseFile)
        openDatabase()
        migrateJSONFiles()
        sealPlainRows()
        loadRows()
    }

    companion object {
        val stores = listOf("kv", "contacts", "chats", "messages", "outbox", "statuses", "calls")

        internal const val singlesMax = 64
        internal const val loadAfter = 96
        internal const val windowChats = 32

        /** Le début d'une valeur chiffrée : `t1` puis nonce (12) ‖ chiffré ‖ étiquette. */
        internal val magic = "t1".toByteArray()

        /**
         * Un paquet : au plus tant de lignes, ou tant d'octets (des photos),
         * tenus en mémoire le temps de les décoder.
         */
        internal const val batchRows = 1024
        internal const val batchBytes = 8 shl 20

        /**
         * Une longue discussion ne reste chargée en entier que tant qu'elle
         * sert : au-delà de tant de messages, `release` n'en garde que la fin.
         */
        internal const val releaseOver = 600
        internal const val releaseKeep = 300

        /** Le nombre de cœurs à mettre sur un travail. */
        private val cores: Int get() = Runtime.getRuntime().availableProcessors()

        /**
         * Les objets d'une liste de JSON, dans le même ordre (les illisibles
         * sautés). Une liste lourde (au lancement : les contacts et les
         * discussions, avec leurs photos) est partagée entre les cœurs.
         */
        internal fun <T> decodeAll(jsons: List<String>, type: JsonDecodable<T>): List<T> {
            val weight = jsons.sumOf { utf8Length(it).toLong() }
            val workers = minOf(cores.toLong(), 8L, jsons.size.toLong(), weight / 262_144).toInt()
            if (workers <= 1) return jsons.mapNotNull { JSON.decode(type, it) }
            return inParts(jsons.size, workers) { i -> JSON.decode(type, jsons[i]) }
        }

        /**
         * `count` éléments partagés en `workers` tranches contiguës, chacune
         * sur un cœur ; les résultats dans l'ordre des éléments, les vides
         * sautés (`DispatchQueue.concurrentPerform` de l'iPhone).
         */
        internal fun <T> inParts(count: Int, workers: Int, one: (Int) -> T?): List<T> {
            val parts = arrayOfNulls<List<T>>(workers)
            IntStream.range(0, workers).parallel().forEach { w ->
                val from = (count.toLong() * w / workers).toInt()
                val to = (count.toLong() * (w + 1) / workers).toInt()
                val found = ArrayList<T>(to - from)
                for (i in from until to) one(i)?.let { found.add(it) }
                parts[w] = found
            }
            val out = ArrayList<T>(count)
            for (p in parts) out.addAll(p!!)
            return out
        }

        /** (heure, identifiant) avant (heure, identifiant) ? L'ordre des discussions. */
        internal fun before(at: Double, id: String, at2: Double, id2: String): Boolean =
            at < at2 || (at == at2 && compareCodePoints(id, id2) < 0)

        /** L'ordre des messages : par heure, puis par identifiant. */
        internal val order: Comparator<Message> = Comparator { a, b ->
            when {
                before(a.at, a.id, b.at, b.id) -> -1
                before(b.at, b.id, a.at, a.id) -> 1
                else -> 0
            }
        }

        /**
         * Le rang de (heure, identifiant) dans une liste triée : celui du
         * premier message qui n'est pas avant.
         */
        internal fun rank(at: Double, id: String, list: List<Message>): Int {
            var lo = 0
            var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (before(list[mid].at, list[mid].id, at, id)) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    /** Ferme la base (sur l'iPhone, quand la mémoire disparaît). */
    override fun close() {
        lock.withLock {
            db?.close()
            db = null
        }
    }

    // ----- Le chiffrement au repos -----

    internal fun aad(store: String, key: String): ByteArray = "talka-repos:$store/$key".toByteArray()

    internal fun seal(store: String, k: String, json: String): ByteArray? {
        val key = key ?: return null
        val box = AesGcm.seal(key, json.toByteArray(), aad(store, k)) ?: return null
        return magic + box
    }

    /** Le JSON d'une ligne lue : en clair (d'avant), ou déchiffré ; rien si illisible (abîmé, ou pas la clé). */
    private fun value(row: SqlRow, index: Int, store: String, key: String): String? {
        if (!row.isBlob(index)) return row.text(index)
        val data = row.blob(index)
        if (data.isEmpty()) return null
        return open(data, store, key)
    }

    /**
     * Le JSON d'une valeur chiffrée ; rien si illisible (abîmée, ou pas la
     * clé). Sans SQLite ni rien qui change : peut tourner sur plusieurs
     * cœurs à la fois.
     */
    internal fun open(data: ByteArray, store: String, key: String): String? {
        val k = this.key ?: return null
        if (data.size <= magic.size + 28 || data[0] != magic[0] || data[1] != magic[1]) return null
        val plain = AesGcm.open(k, data.copyOfRange(2, data.size), aad(store, key)) ?: return null
        return utf8(plain)
    }

    /**
     * Les lignes en clair (d'avant le chiffrement), réécrites chiffrées ;
     * sans clé, des lignes chiffrées rendent la base en lecture seule.
     */
    private fun sealPlainRows() {
        if (key == null) {
            var sealed = false
            run("SELECT 1 FROM rows WHERE typeof(json) = 'blob' LIMIT 1") { sealed = true }
            readOnly = sealed
            return
        }
        class Plain(val store: String, val key: String, val chat: String, val at: Double, val json: String)
        val plain = ArrayList<Plain>()
        run("SELECT store, key, chat, at, json FROM rows WHERE typeof(json) = 'text'") { r ->
            plain.add(Plain(r.text(0), r.text(1), r.text(2), r.double(3), r.text(4)))
        }
        if (plain.isEmpty()) return
        exec("BEGIN")
        for (p in plain) put(p.store, p.key, p.json, p.chat, p.at)
        if (!exec("COMMIT")) {
            exec("ROLLBACK")
            return
        }
        // Plus rien du texte d'avant, ni dans le journal ni dans les pages
        // libérées.
        exec("PRAGMA wal_checkpoint(TRUNCATE)")
        exec("VACUUM")
    }

    // ----- SQLite -----

    private fun openDatabase() {
        if (db == null) return
        exec("PRAGMA journal_mode = WAL")
        exec("PRAGMA synchronous = NORMAL")
        // Ce qui est effacé ou remplacé est écrasé sur le disque.
        exec("PRAGMA secure_delete = ON")
        exec(
            """
            CREATE TABLE IF NOT EXISTS rows (
                store TEXT NOT NULL,
                key   TEXT NOT NULL,
                chat  TEXT NOT NULL DEFAULT '',
                at    REAL NOT NULL DEFAULT 0,
                json  TEXT NOT NULL,
                PRIMARY KEY (store, key)
            )
            """.trimIndent(),
        )
        exec("CREATE INDEX IF NOT EXISTS rows_chat_at ON rows (store, chat, at)")
    }

    private fun exec(sql: String): Boolean = db?.exec(sql) ?: false

    /** Une requête préparée, ses paramètres liés (`String`, `ByteArray`, `Double`, `Int`). */
    internal fun run(sql: String, params: List<Any?> = emptyList(), each: (SqlRow) -> Unit = {}) {
        db?.query(sql, params, each)
    }

    /** Écrit une ligne, son JSON chiffré (en clair seulement sans clé). */
    private fun put(store: String, key: String, json: String, chat: String = "", at: Double = 0.0) {
        if (readOnly) return
        val value: Any = seal(store, key, json) ?: json
        run("INSERT OR REPLACE INTO rows (store, key, chat, at, json) VALUES (?, ?, ?, ?, ?)", listOf(store, key, chat, at, value))
    }

    private fun delete(store: String, key: String) {
        if (readOnly) return
        run("DELETE FROM rows WHERE store = ? AND key = ?", listOf(store, key))
    }

    /** Les petits magasins, tout de suite en mémoire. */
    private fun loadRows() {
        lock.withLock {
            run("SELECT store, key, json FROM rows WHERE store != 'messages'") { r ->
                val store = r.text(0)
                val k = r.text(1)
                value(r, 2, store, k)?.let { rows.getOrPut(store) { HashMap() }[k] = it }
            }
        }
    }

    // ----- Les magasins simples -----

    fun <T> read(store: String, key: String, type: JsonDecodable<T>): T? =
        lock.withLock { rows[store]?.get(key) }?.let { JSON.decode(type, it) }

    fun write(store: String, key: String, value: JsonEncodable) {
        writeRaw(store, key, JSON.encode(value))
    }

    /** Le JSON gardé tel quel (l'état v3, qui a son propre format). */
    fun readRaw(store: String, key: String): String? = lock.withLock { rows[store]?.get(key) }

    /** Toutes les lignes d'un magasin, telles quelles (clé → JSON). */
    fun rawRows(store: String): Map<String, String> = lock.withLock { HashMap(rows[store] ?: emptyMap()) }

    fun writeRaw(store: String, key: String, json: String) {
        lock.withLock {
            rows.getOrPut(store) { HashMap() }[key] = json
            put(store, key, json)
        }
    }

    /**
     * Les écritures de `body` dans une seule transaction SQLite : toutes ou
     * aucune, même si l'application s'arrête au milieu. Les lots s'emboîtent
     * (seul le plus extérieur valide).
     */
    fun <T> transaction(body: () -> T): T {
        lock.withLock {
            if (depth == 0) exec("BEGIN IMMEDIATE")
            depth += 1
        }
        try {
            return body()
        } finally {
            lock.withLock {
                depth -= 1
                if (depth == 0 && !exec("COMMIT")) exec("ROLLBACK")
            }
        }
    }

    fun remove(store: String, key: String) {
        lock.withLock {
            rows[store]?.remove(key)
            delete(store, key)
        }
    }

    fun <T> all(store: String, type: JsonDecodable<T>): List<T> {
        val jsons = lock.withLock { ArrayList((rows[store] ?: emptyMap<String, String>()).values) }
        return decodeAll(jsons, type)
    }

    fun clear(store: String) {
        lock.withLock {
            rows[store] = HashMap()
            run("DELETE FROM rows WHERE store = ?", listOf(store))
        }
    }

    // ----- Les messages -----

    /** Toute une discussion, lue une fois puis gardée. */
    private fun loadChat(chat: String): HashMap<String, Message> {
        messages[chat]?.let { return it }
        val out = HashMap<String, Message>()
        for (m in readMessages("SELECT key, json FROM rows WHERE store = 'messages' AND chat = ?", listOf(chat))) out[m.id] = m
        messages[chat] = out
        forgetSingles(chat)
        dropWindow(chat)
        singleReads.remove(chat)
        return out
    }

    /**
     * Une ligne de message lue, pas encore ouverte : sa clé, et son JSON
     * chiffré, ou en clair (une ligne d'avant le chiffrement).
     */
    internal class MessageRow(val key: String, val sealed: ByteArray? = null, val json: String? = null)

    private fun <T> decode(row: MessageRow, type: JsonDecodable<T>): T? {
        val json = if (row.sealed != null) open(row.sealed, "messages", row.key) else row.json
        return json?.let { JSON.decode(type, it) }
    }

    /**
     * Les messages d'une requête (`SELECT key, json …`), dans l'ordre de
     * ses lignes. Elles sont lues par paquets ; chaque paquet est déchiffré
     * et décodé sur plusieurs cœurs (le gros du temps d'ouverture d'une
     * longue discussion).
     */
    private fun readMessages(sql: String, params: List<Any?> = emptyList()): List<Message> = readRows(sql, params, Message).first

    /**
     * Les mêmes lignes décodées en autre chose qu'un message entier (ses
     * seules échéances, par exemple), de la même façon. Avec le nombre de
     * lignes lues, lisibles ou non.
     */
    private fun <T> readRows(sql: String, params: List<Any?>, type: JsonDecodable<T>): Pair<List<T>, Int> {
        val out = ArrayList<T>()
        var batch = ArrayList<MessageRow>()
        var bytes = 0
        var read = 0
        run(sql, params) { r ->
            read += 1
            val k = r.text(0)
            if (r.isBlob(1)) {
                val data = r.blob(1)
                if (data.isEmpty()) return@run
                batch.add(MessageRow(k, sealed = data))
                bytes += data.size
            } else {
                val json = r.text(1)
                bytes += utf8Length(json)
                batch.add(MessageRow(k, json = json))
            }
            if (batch.size >= batchRows || bytes >= batchBytes) {
                out.addAll(decode(batch, type))
                batch = ArrayList()
                bytes = 0
            }
        }
        out.addAll(decode(batch, type))
        return out to read
    }

    internal fun decode(rows: List<MessageRow>): List<Message> = decode(rows, Message)

    /**
     * Les messages d'un paquet de lignes, dans le même ordre ; les lignes
     * illisibles sont sautées. Un petit paquet est décodé sur place ; un
     * gros est partagé entre les cœurs.
     */
    private fun <T> decode(rows: List<MessageRow>, type: JsonDecodable<T>): List<T> {
        // Assez de lignes, ou assez d'octets (quelques photos suffisent),
        // pour que le partage vaille son coût.
        val weight = rows.sumOf { (it.sealed?.size ?: it.json?.let(::utf8Length) ?: 0).toLong() }
        val workers = minOf(cores.toLong(), 8L, rows.size.toLong(), maxOf(rows.size.toLong() / 64, weight / 262_144)).toInt()
        if (workers <= 1) return rows.mapNotNull { decode(it, type) }
        return inParts(rows.size, workers) { i -> decode(rows[i], type) }
    }

    /** Les messages d'une discussion (chargée au besoin), dans l'ordre. */
    private fun inOrder(chat: String): List<Message> {
        ordered[chat]?.let { return it }
        val sorted = loadChat(chat).values.sortedWith(order)
        ordered[chat] = sorted
        return sorted
    }

    /**
     * Tient à jour la liste triée d'une discussion chargée : `old` en sort,
     * `new` y prend sa place. Si `old` n'est pas où il devrait, la liste est
     * oubliée (retriée à la prochaine lecture).
     */
    private fun reorder(chat: String, old: Message?, new: Message?) {
        val kept = ordered.remove(chat) ?: return
        val list = ArrayList(kept)
        if (old != null) {
            val i = rank(old.at, old.id, list)
            if (i >= list.size || list[i].id != old.id) return
            if (new != null && new.at == old.at) {
                list[i] = new
                ordered[chat] = list
                return
            }
            list.removeAt(i)
        }
        if (new != null) list.add(rank(new.at, new.id, list), new)
        ordered[chat] = list
    }

    private fun remember(key: String, m: Message) {
        if (singles.size >= singlesMax && singles[key] == null) singles = HashMap()
        singles[key] = m
    }

    private fun forgetSingles(chat: String) {
        if (singles.isEmpty()) return
        val prefix = "$chat|"
        singles.keys.removeAll { it.startsWith(prefix) }
    }

    /**
     * La fenêtre d'une discussion vient d'être demandée : elle passe en
     * tête, et la moins récemment demandée est oubliée s'il y en a trop.
     */
    private fun touchWindow(chat: String) {
        if (windowOrder.lastOrNull() == chat) return
        windowOrder.removeAll { it == chat }
        windowOrder.add(chat)
        while (windowOrder.size > windowChats) {
            val old = windowOrder.removeAt(0)
            windows.remove(old)
            windowCovers.remove(old)
        }
    }

    private fun dropWindow(chat: String) {
        windowCovers.remove(chat)
        if (windows.remove(chat) != null) windowOrder.removeAll { it == chat }
    }

    /**
     * La fin d'une discussion pas chargée : au moins ses `limit` derniers
     * messages (davantage si la mémoire en tient déjà plus). Lus par
     * l'index, une fois, puis tenus à jour. Rien : il n'y en a pas de plus
     * anciens, la discussion tient en entier et vient d'être chargée.
     */
    private fun window(chat: String, limit: Int): List<Message>? {
        val kept = windows[chat]
        if (kept != null && (windowCovers[chat] ?: kept.size) >= limit) {
            touchWindow(chat)
            return kept
        }
        // Un de plus que demandé : pour savoir s'il en reste de plus anciens.
        val (found, rows) = readRows(
            "SELECT key, json FROM rows WHERE store = 'messages' AND chat = ? ORDER BY at DESC, key DESC LIMIT ?",
            listOf(chat, limit + 1), Message,
        )
        if (rows <= limit) {
            val all = HashMap<String, Message>()
            for (m in found) all[m.id] = m
            messages[chat] = all
            ordered.remove(chat)
            forgetSingles(chat)
            dropWindow(chat)
            singleReads.remove(chat)
            return null
        }
        var list: List<Message> = found.reversed()
        // La ligne de plus n'est pas gardée ; si des lignes sont illisibles,
        // tout ce qui a été lu reste, et la fenêtre couvre ces lignes-là.
        var covers = rows
        if (list.size > limit) {
            covers -= list.size - limit
            list = ArrayList(list.subList(list.size - limit, list.size))
        }
        windows[chat] = list
        windowCovers[chat] = covers
        touchWindow(chat)
        return list
    }

    /**
     * Tient à jour la fenêtre d'une discussion pas chargée : l'ancienne
     * version du message (s'il y était) en sort, la nouvelle (`new`) y prend
     * sa place si elle n'est pas plus ancienne que le début de la fenêtre.
     */
    private fun slide(chat: String, id: String, new: Message?) {
        val kept = windows.remove(chat) ?: return
        val list = ArrayList(kept)
        var covers = windowCovers[chat] ?: list.size
        val i = list.indexOfLast { it.id == id }
        if (i >= 0) {
            list.removeAt(i)
            covers -= 1
        }
        if (new != null) {
            // Vide, on ne sait plus où elle commence : oubliée.
            val first = list.firstOrNull()
            if (first == null) {
                windowOrder.removeAll { it == chat }
                windowCovers.remove(chat)
                return
            }
            if (before(first.at, first.id, new.at, new.id)) {
                list.add(rank(new.at, new.id, list), new)
                covers += 1
            }
        }
        windows[chat] = list
        windowCovers[chat] = covers
    }

    /**
     * Les messages d'une discussion, du plus ancien au plus récent ; `limit`
     * garde les derniers (sans charger la discussion si elle ne l'est pas).
     */
    fun messagesOf(chat: String, limit: Int = Int.MAX_VALUE): List<Message> =
        lock.withLock {
            if (limit < Int.MAX_VALUE - 1 && messages[chat] == null) {
                if (limit <= 0) return emptyList()
                window(chat, limit)?.let { end -> return if (end.size > limit) end.takeLast(limit) else end }
            }
            val sorted = inOrder(chat)
            if (limit >= sorted.size) sorted else sorted.takeLast(limit)
        }

    /**
     * La fin d'une discussion, pour l'afficher : au moins ses `limit`
     * derniers messages, du plus ancien au plus récent (davantage si la
     * mémoire en tient déjà plus), et s'il en existe de plus anciens. Une
     * longue discussion s'ouvre ainsi sans être lue en entier ; `limit`
     * grandit quand on remonte.
     */
    fun recentMessages(chat: String, limit: Int): RecentMessages =
        lock.withLock {
            if (limit <= 0) return RecentMessages(emptyList(), false)
            if (limit < Int.MAX_VALUE - 1 && messages[chat] == null) {
                window(chat, limit)?.let { return RecentMessages(it, true) }
            }
            val sorted = inOrder(chat)
            if (sorted.size > limit) RecentMessages(sorted.takeLast(limit), true) else RecentMessages(sorted, false)
        }

    /**
     * Les messages d'une discussion datés d'avant `at`, du plus ancien au
     * plus récent : par l'index si elle n'est pas chargée (le ménage des
     * vieux messages ne relit pas tout l'historique).
     */
    fun messagesBefore(chat: String, at: Double): List<Message> =
        lock.withLock {
            if (messages[chat] != null) {
                val sorted = inOrder(chat)
                return ArrayList(sorted.subList(0, rank(at, "", sorted)))
            }
            val out = ArrayList<Message>()
            run("SELECT key, json FROM rows WHERE store = 'messages' AND chat = ? AND at < ? ORDER BY at, key", listOf(chat, at)) { r ->
                value(r, 1, "messages", r.text(0))?.let { JSON.decode(Message, it) }?.let { out.add(it) }
            }
            out
        }

    /** Un message ; d'une discussion pas chargée, sa seule ligne est lue. */
    fun message(chat: String, id: String): Message? =
        lock.withLock {
            messages[chat]?.let { return it[id] }
            val key = "$chat|$id"
            singles[key]?.let { return it }
            // Dans la fin déjà en mémoire (le plus souvent : un message récent).
            windows[chat]?.lastOrNull { it.id == id }?.let { return it }
            var out: Message? = null
            run("SELECT json FROM rows WHERE store = 'messages' AND key = ?", listOf(key)) { r ->
                value(r, 0, "messages", key)?.let { out = JSON.decode(Message, it) }
            }
            val found = out ?: return null
            val reads = (singleReads[chat] ?: 0) + 1
            singleReads[chat] = reads
            if (reads > loadAfter) {
                // Chargée parce qu'elle sert beaucoup : pas rendue de sitôt.
                busy.add(chat)
                return loadChat(chat)[id]
            }
            remember(key, found)
            found
        }

    /**
     * Un message lu pour être montré une fois (un résultat de recherche).
     * Comme `message`, mais sa lecture ne compte pas parmi celles qui
     * finissent par charger toute la discussion et la garder (`loadAfter`,
     * `busy`), et il n'est pas gardé : quelques recherches de suite dans
     * une longue discussion ne la tiennent pas en mémoire.
     */
    fun passingMessage(chat: String, id: String): Message? =
        lock.withLock {
            messages[chat]?.let { return it[id] }
            val key = "$chat|$id"
            singles[key]?.let { return it }
            windows[chat]?.lastOrNull { it.id == id }?.let { return it }
            var out: Message? = null
            run("SELECT json FROM rows WHERE store = 'messages' AND key = ?", listOf(key)) { r ->
                value(r, 0, "messages", key)?.let { out = JSON.decode(Message, it) }
            }
            out
        }

    fun writeMessage(m: Message) {
        lock.withLock {
            generation += 1
            val loaded = messages[m.chat]
            if (loaded != null) {
                val old = loaded.put(m.id, m)
                reorder(m.chat, old, m)
            } else {
                remember(m.key, m)
                slide(m.chat, m.id, m)
            }
            starredIn[m.chat]?.let { if (m.isStarred) it.add(m.id) else it.remove(m.id) }
            linesIn[m.chat]?.put(Message.Line(m))
            piecesIn[m.chat]?.let { pieces ->
                val piece = Storage.Piece.of(m.kind, m.deleted, m.size, m.data)
                if (piece != null) pieces[m.id] = piece else pieces.remove(m.id)
            }
            // Une échéance plus proche que celle qu'on connaissait.
            val exp = m.exp
            val next = expNext[m.chat]
            if (exp != null && next != null && exp < next) expNext[m.chat] = exp
            put("messages", m.key, JSON.encode(m), m.chat, m.at)
        }
    }

    fun removeMessage(chat: String, id: String) {
        lock.withLock { drop(chat, id) }
    }

    /** Le retrait d'un message, verrou déjà pris. */
    private fun drop(chat: String, id: String) {
        generation += 1
        starredIn[chat]?.remove(id)
        piecesIn[chat]?.remove(id)
        linesIn[chat]?.remove(id)
        val loaded = messages[chat]
        if (loaded != null) {
            loaded.remove(id)?.let { reorder(chat, it, null) }
        } else {
            singles.remove("$chat|$id")
            slide(chat, id, null)
        }
        delete("messages", "$chat|$id")
    }

    /**
     * Retire d'une discussion ses messages éphémères arrivés à échéance
     * avant `t`, et les renvoie (identifiant, date), du plus ancien au plus
     * récent.
     *
     * Le ménage le demande chaque minute pour chaque discussion à messages
     * éphémères : la plus proche échéance restante est retenue, et tant
     * qu'elle n'est pas passée rien n'est relu. Quand il faut lire une
     * discussion pas chargée, seules les échéances de ses messages sont
     * décodées, et rien n'en est gardé en mémoire.
     */
    fun removeExpired(chat: String, before: Double): List<Pair<String, Double>> {
        val nothing = lock.withLock { expNext[chat]?.let { it >= before } ?: false }
        if (nothing) return emptyList()
        // Tout ce qui est échu part en une transaction : au retour d'une
        // absence il y en a des milliers, pas une transaction par message.
        return transaction { removeExpiredNow(chat, before) }
    }

    private fun removeExpiredNow(chat: String, t: Double): List<Pair<String, Double>> =
        lock.withLock {
            expNext[chat]?.let { if (it >= t) return emptyList() }
            val gone = ArrayList<Pair<String, Double>>()
            var next = Double.POSITIVE_INFINITY
            fun see(id: String, at: Double, exp: Double?) {
                if (exp == null) return
                if (exp < t) gone.add(id to at) else next = minOf(next, exp)
            }
            if (messages[chat] != null) {
                for (m in inOrder(chat)) see(m.id, m.at, m.exp)
            } else {
                val (heads, _) = readRows(
                    "SELECT key, json FROM rows WHERE store = 'messages' AND chat = ? ORDER BY at, key",
                    listOf(chat), Message.Expiry,
                )
                for (h in heads) see(h.id, h.at, h.exp)
            }
            for ((id, _) in gone) drop(chat, id)
            expNext[chat] = next
            gone
        }

    /**
     * Les identifiants des messages importants d'une discussion (ceux qui
     * ne sont pas supprimés). D'une discussion pas chargée, seules ces
     * marques sont décodées, une fois ; rien d'autre n'est gardé en mémoire.
     */
    fun starredIds(chat: String): Set<String> =
        lock.withLock {
            starredIn[chat]?.let { return HashSet(it) }
            val ids = HashSet<String>()
            if (messages[chat] != null) {
                for (m in inOrder(chat)) if (m.isStarred) ids.add(m.id)
            } else {
                val (marks, _) = readRows("SELECT key, json FROM rows WHERE store = 'messages' AND chat = ?", listOf(chat), Message.Star)
                for (mark in marks) if (mark.starred) ids.add(mark.id)
            }
            starredIn[chat] = ids
            HashSet(ids)
        }

    /**
     * Ce que pèsent les pièces jointes d'une discussion (voir `Storage.of`).
     * D'une discussion pas chargée, seuls ces poids sont décodés, une fois ;
     * ni ses messages ni ses photos ne restent en mémoire.
     */
    fun storage(of: String): Storage =
        lock.withLock {
            val chat = of
            var st = Storage()
            piecesIn[chat]?.let { kept ->
                for (piece in kept.values) st = st.add(piece)
                return st
            }
            val pieces = HashMap<String, Storage.Piece>()
            if (messages[chat] != null) {
                for (m in inOrder(chat)) {
                    val piece = Storage.Piece.of(m.kind, m.deleted, m.size, m.data)
                    if (piece != null) pieces[m.id] = piece else pieces.remove(m.id)
                }
            } else {
                val (weights, _) = readRows("SELECT key, json FROM rows WHERE store = 'messages' AND chat = ?", listOf(chat), Message.Weight)
                for (w in weights) {
                    val piece = w.piece
                    if (piece != null) pieces[w.id] = piece else pieces.remove(w.id)
                }
            }
            piecesIn[chat] = pieces
            for (piece in pieces.values) st = st.add(piece)
            st
        }

    /**
     * Le texte des messages d'une discussion, sans ordre (voir
     * `Message.Line`), pour la recherche dans toutes les discussions. D'une
     * discussion pas chargée, seuls ces textes sont décodés, une fois ; ni
     * ses messages ni ses photos ne restent en mémoire.
     */
    internal fun lines(of: String): List<Message.Line> =
        lock.withLock {
            val chat = of
            linesIn[chat]?.let { return ArrayList(it.list) }
            val lines = Lines()
            if (messages[chat] != null) {
                for (m in inOrder(chat)) lines.put(Message.Line(m))
            } else {
                val (read, _) = readRows("SELECT key, json FROM rows WHERE store = 'messages' AND chat = ?", listOf(chat), Message.Line)
                for (line in read) lines.put(line)
            }
            linesIn[chat] = lines
            ArrayList(lines.list)
        }

    /** Rend la mémoire de ces textes (l'application passe à l'arrière-plan). */
    fun forgetLines() {
        lock.withLock { linesIn.clear() }
    }

    /**
     * Efface d'une discussion ses messages trop vieux (datés d'avant
     * `before`, et que `keepDays` ne garde pas : voir `Message.tooOld`) et
     * les renvoie (identifiant, date), du plus ancien au plus récent. Seul
     * leur âge est décodé, par l'index, et tout part en une transaction.
     */
    fun removeOld(chat: String, before: Double, keepDays: Int, now: Double): List<Pair<String, Double>> =
        transaction {
            lock.withLock {
                val ages: List<Message.Age> =
                    if (messages[chat] != null) {
                        val sorted = inOrder(chat)
                        sorted.subList(0, rank(before, "", sorted)).map { Message.Age(it) }
                    } else {
                        readRows(
                            "SELECT key, json FROM rows WHERE store = 'messages' AND chat = ? AND at < ? ORDER BY at, key",
                            listOf(chat, before), Message.Age,
                        ).first
                    }
                val gone = ages.filter { it.tooOld(keepDays, now) }
                for (age in gone) drop(chat, age.id)
                gone.map { it.id to it.at }
            }
        }

    /**
     * L'identifiant et la date des messages d'une discussion datés d'après
     * `after` (tous, sans `after`), du plus ancien au plus récent : lus dans
     * l'index, sans ouvrir ni déchiffrer aucune ligne. Vider ou supprimer
     * une discussion n'a besoin que de cela pour noter ce qui part.
     */
    fun datesOf(chat: String, after: Double? = null): List<Pair<String, Double>> =
        lock.withLock {
            if (messages[chat] != null) {
                val sorted = inOrder(chat)
                return sorted.filter { after == null || it.at > after }.map { it.id to it.at }
            }
            val out = ArrayList<Pair<String, Double>>()
            val prefix = chat.toByteArray().size + 1
            val each: (SqlRow) -> Unit = { r ->
                val key = r.text(0).toByteArray()
                out.add((utf8(key.copyOfRange(minOf(prefix, key.size), key.size)) ?: "") to r.double(1))
            }
            if (after != null) {
                run("SELECT key, at FROM rows WHERE store = 'messages' AND chat = ? AND at > ? ORDER BY at, key", listOf(chat, after), each)
            } else {
                run("SELECT key, at FROM rows WHERE store = 'messages' AND chat = ? ORDER BY at, key", listOf(chat), each)
            }
            out
        }

    /**
     * Efface les messages d'une discussion sauf ceux de `keeping` (ses
     * messages importants), d'un coup. Elle n'est pas chargée pour autant :
     * ce qui reste se relira à la demande.
     */
    fun deleteMessages(of: String, keeping: Set<String>) {
        val chat = of
        if (keeping.isEmpty()) return deleteMessages(chat)
        val gone = if (keeping.size > 500) datesOf(chat).map { it.first }.filter { it !in keeping } else emptyList()
        transaction {
            lock.withLock {
                generation += 1
                messages.remove(chat)
                ordered.remove(chat)
                dropWindow(chat)
                singleReads.remove(chat)
                forgetSingles(chat)
                busy.remove(chat)
                expNext.remove(chat)
                starredIn.remove(chat)
                piecesIn.remove(chat)
                linesIn.remove(chat)
                if (keeping.size > 500) {
                    // Trop de messages gardés pour une seule requête : un par un.
                    for (id in gone) delete("messages", "$chat|$id")
                } else {
                    val keys = keeping.map { "$chat|$it" }
                    val marks = keys.joinToString(",") { "?" }
                    run("DELETE FROM rows WHERE store = 'messages' AND chat = ? AND key NOT IN ($marks)", listOf<Any?>(chat) + keys)
                }
            }
        }
    }

    /**
     * Rend la mémoire des longues discussions lues en entier (une
     * recherche, une remontée jusqu'au début, un export) : il n'en reste
     * que la fin, comme avant de les ouvrir. `except` : celle qui est à
     * l'écran. Rien n'est perdu, tout est dans SQLite ; les relire en entier
     * coûtera une lecture.
     */
    fun release(except: String?) {
        lock.withLock {
            for ((chat, all) in messages.entries.toList()) {
                if (chat == except || all.size <= releaseOver || chat in busy) continue
                val list = inOrder(chat)
                messages.remove(chat)
                ordered.remove(chat)
                singleReads.remove(chat)
                windows[chat] = list.takeLast(releaseKeep)
                windowCovers[chat] = releaseKeep
                touchWindow(chat)
            }
        }
    }

    fun deleteMessages(of: String) {
        val chat = of
        lock.withLock {
            generation += 1
            busy.remove(chat)
            expNext.remove(chat)
            starredIn.remove(chat)
            piecesIn.remove(chat)
            linesIn.remove(chat)
            messages[chat] = HashMap()
            ordered.remove(chat)
            dropWindow(chat)
            singleReads.remove(chat)
            forgetSingles(chat)
            run("DELETE FROM rows WHERE store = 'messages' AND chat = ?", listOf(chat))
        }
    }

    /**
     * Les discussions qui ont des messages (qu'elles soient connues ou
     * non), dans l'ordre de `allMessages` : lu dans l'index, sans ouvrir
     * aucune ligne.
     */
    fun chatsWithMessages(): List<String> =
        lock.withLock {
            val out = ArrayList<String>()
            run("SELECT DISTINCT chat FROM rows WHERE store = 'messages' ORDER BY chat") { r -> out.add(r.text(0)) }
            out
        }

    /**
     * Les messages d'une discussion, du plus ancien au plus récent, lus au
     * passage : elle n'est pas chargée et rien n'en est gardé. Pour ce qui
     * parcourt tout l'appareil, une discussion après l'autre, sans le tenir
     * en entier en mémoire.
     */
    fun passingMessages(of: String): List<Message> =
        lock.withLock {
            if (messages[of] != null) return inOrder(of)
            readMessages("SELECT key, json FROM rows WHERE store = 'messages' AND chat = ? ORDER BY at, key", listOf(of))
        }

    /** Tous les messages de toutes les discussions (favoris, sauvegarde). */
    fun allMessages(): List<Message> =
        lock.withLock { readMessages("SELECT key, json FROM rows WHERE store = 'messages' ORDER BY chat, at") }

    /**
     * Change à chaque écriture ou suppression de message (et quand tout est
     * effacé ou restauré) : ce qui a été calculé sur tous les messages (les
     * messages importants) reste valable tant qu'il n'a pas bougé.
     */
    val messagesGeneration: Int get() = lock.withLock { generation }

    /** Chaque écriture est déjà dans SQLite ; à l'arrière-plan, on reporte le journal dans la base. */
    fun save() {
        lock.withLock { exec("PRAGMA wal_checkpoint(PASSIVE)") }
    }

    // ----- La sauvegarde : le même format que le navigateur -----

    /** `{ magasin: [[clé, json], …] }` (les messages : `[clé, json, discussion, heure]`). */
    fun dump(messages: Boolean = true): JsonObject =
        lock.withLock {
            val out = LinkedHashMap<String, ArrayList<JsonArray>>()
            for (name in stores) out[name] = ArrayList()
            // Sans les messages : pour qui n'en veut qu'une partie (voir
            // `newestMessageRows`), sans les lire tous.
            val sql =
                if (messages) {
                    "SELECT store, key, json, chat, at FROM rows ORDER BY store, key"
                } else {
                    "SELECT store, key, json, chat, at FROM rows WHERE store != 'messages' ORDER BY store, key"
                }
            run(sql) { r ->
                val store = r.text(0)
                val k = r.text(1)
                val json = value(r, 2, store, k) ?: return@run
                val row = arrayListOf<kotlinx.serialization.json.JsonElement>(JsonPrimitive(k), JsonPrimitive(json))
                if (store == "messages") {
                    row.add(JsonPrimitive(r.text(3)))
                    row.add(JsonPrimitive(r.double(4)))
                }
                out.getOrPut(store) { ArrayList() }.add(JsonArray(row))
            }
            JsonObject(out.mapValues { JsonArray(it.value) })
        }

    /**
     * Les lignes de messages, de la plus récente à la plus ancienne, telles
     * que `dump` les rend (clé, JSON, discussion, heure), tant que `take`
     * répond `true`. Seules les lignes demandées sont lues et déchiffrées :
     * lier un appareil n'emporte que les plus récentes, pas toute la base
     * en mémoire.
     */
    fun newestMessageRows(take: (key: String, json: String, chat: String, at: Double) -> Boolean) {
        lock.withLock {
            // D'abord les clés et les heures (petites), triées ; puis une ligne à la fois.
            val order = ArrayList<Pair<String, Double>>()
            run("SELECT key, at FROM rows WHERE store = 'messages' ORDER BY at DESC") { r -> order.add(r.text(0) to r.double(1)) }
            for ((key, at) in order) {
                var row: Pair<String, String>? = null
                run("SELECT json, chat FROM rows WHERE store = 'messages' AND key = ?", listOf(key)) { r ->
                    value(r, 0, "messages", key)?.let { row = it to r.text(1) }
                }
                val found = row ?: continue
                if (!take(key, found.first, found.second, at)) return
            }
        }
    }

    fun restore(data: JsonObject) {
        wipe()
        lock.withLock {
            generation += 1
            exec("BEGIN")
            for (name in stores) {
                val list = data[name] as? JsonArray ?: continue
                // Tout ou rien, comme `as? [[Any]]` : une ligne qui n'est pas une liste, et le magasin est sauté.
                if (list.any { it !is JsonArray }) continue
                for (element in list) {
                    val row = element as JsonArray
                    if (row.size < 2) continue
                    val key = row[0].asString() ?: continue
                    val json = row[1].asString() ?: continue
                    if (name == "messages") {
                        val chat = if (row.size > 2) row[2].asString() ?: "" else ""
                        val at = if (row.size > 3) row[3].asDouble() ?: 0.0 else 0.0
                        put(name, key, json, chat, at)
                    } else {
                        rows.getOrPut(name) { HashMap() }[key] = json
                        put(name, key, json)
                    }
                }
            }
            exec("COMMIT")
            // Des messages écrits sans passer par `writeMessage`.
            expNext.clear()
            busy.clear()
            starredIn.clear()
            piecesIn.clear()
            linesIn.clear()
        }
    }

    fun wipe() {
        lock.withLock {
            rows.clear()
            messages.clear()
            ordered.clear()
            singles = HashMap()
            windows.clear()
            windowOrder.clear()
            windowCovers.clear()
            expNext.clear()
            busy.clear()
            starredIn.clear()
            piecesIn.clear()
            linesIn.clear()
            singleReads.clear()
            generation += 1
            exec("DELETE FROM rows")
            exec("VACUUM")
            // Tout est effacé : on peut écrire à nouveau (avec la clé, s'il y en a une).
            readOnly = false
        }
    }

    /** L'espace occupé, en octets. */
    fun usage(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ----- Les données d'avant SQLite -----

    /** Un fichier `{ clé: json }` ; rien s'il manque, ou si une valeur n'est pas un texte. */
    private fun readStringMap(file: File): Map<String, String>? {
        val text = try {
            file.readBytes().let(::utf8)
        } catch (e: java.io.IOException) {
            null
        } ?: return null
        val obj = JSON.parse(text) as? JsonObject ?: return null
        val out = LinkedHashMap<String, String>()
        for ((k, v) in obj) out[k] = v.asString() ?: return null
        return out
    }

    /**
     * Les fichiers JSON des premières versions (un par magasin, un par
     * discussion) : repris une fois dans SQLite, puis effacés.
     */
    private fun migrateJSONFiles() {
        val messagesDir = File(dir, "messages")
        val names = stores.filter { it != "messages" }
        val files = names.map { File(dir, "$it.json") }
        if (files.none { it.exists() } && !messagesDir.exists()) return
        exec("BEGIN")
        for ((name, file) in names.zip(files)) {
            val r = readStringMap(file) ?: continue
            for ((key, json) in r) put(name, key, json)
        }
        for (file in messagesDir.listFiles() ?: emptyArray()) {
            val r = readStringMap(file) ?: continue
            for ((_, json) in r) {
                JSON.decode(Message, json)?.let { put("messages", it.key, json, it.chat, it.at) }
            }
        }
        if (!exec("COMMIT")) {
            exec("ROLLBACK")
            return
        }
        for (file in files) file.delete()
        messagesDir.deleteRecursively()
    }
}

/** La longueur d'un texte en UTF-8, sans le convertir. */
internal fun utf8Length(s: String): Int {
    var n = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c.code < 0x80 -> n += 1
            c.code < 0x800 -> n += 2
            Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                n += 4
                i += 1
            }
            else -> n += 3
        }
        i += 1
    }
    return n
}

/**
 * L'ordre des textes par points de code (celui de SQLite, `ORDER BY key`, et
 * des chaînes du Swift), pas par unités UTF-16.
 */
internal fun compareCodePoints(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val x = a[i]
        val y = b[i]
        if (x != y) {
            // Les moitiés d'un point de code au-delà de U+FFFF (D800–DFFF)
            // passent après E000–FFFF.
            fun weight(c: Char): Int = if (c.code >= 0xD800) (if (c.code <= 0xDFFF) c.code + 0x2000 else c.code - 0x800) else c.code
            return weight(x) - weight(y)
        }
    }
    return a.length - b.length
}
