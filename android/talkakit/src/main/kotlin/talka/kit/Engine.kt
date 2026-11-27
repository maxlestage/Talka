// Le cœur de Talka sur Android : contacts, discussions, messages, groupes,
// statuts. La même logique que le navigateur (`client/src/core.rs`) et que
// l'iPhone (`Engine.swift`) : un téléphone Android, un iPhone et un
// navigateur discutent ensemble, dans les mêmes groupes.
//
// Un message part en autant d'enveloppes que de destinataires : chacune est
// chiffrée pour son destinataire au moment de partir. En attendant, elle est
// gardée dans `outbox` : un message écrit hors ligne part au retour du réseau.
//
// Avec un contact qui a publié son paquet de clés v3 (voir `V3.kt` et
// `EngineV3.kt`), l'enveloppe porte un jeton de sa session à double
// cliquet ; l'état de la session est écrit avant que rien ne parte, et avec
// le message qui l'a fait avancer à la réception.
//
// Tout le moteur vit sur un seul fil (`dispatcher` : le fil principal dans
// l'application, comme `@MainActor` sur l'iPhone) : ses fonctions
// s'appellent depuis ce fil, ses rappels (`onIncoming`, `onChange`…) y
// arrivent, et ce qu'il lance lui-même (envois, minuteries) y tourne. Le
// relais partage ce fil. Les fonctions `suspend` publiques y passent d'elles-
// mêmes.
//
// L'iPhone observe le moteur (`@Observable`) ; ici, `onChange` est appelé à
// chaque changement de ce que montrent les écrans (discussions, contacts,
// présence, réseau…) : l'application le branche sur son état Compose.

package talka.kit

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import talka.brocolis.KeyBundle
import talka.brocolis.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val dayMs: Double = 86_400_000.0

/** Une capsule temporelle s'ouvre au plus tard dans dix ans. */
const val capsuleMaxMs: Double = 10 * 366 * dayMs

/** En ligne, et vu pour la dernière fois. */
data class Presence(val online: Boolean, val seen: Double?)

/** Une discussion exportée : le nom du fichier, et son texte. */
data class ChatExport(val filename: String, val text: String)

/** Une enveloppe de la boîte d'envoi, sans son contenu, et sa ligne. */
data class OutRow(val head: OutItem, val json: String)

/** Un objet déjà décodé, et la ligne dont il vient. */
internal data class Kept<T>(val json: String, val value: T)

/** La dernière recherche dans toutes les discussions, et ce dont elle dépend. */
internal data class SearchKept(
    val query: String,
    val limit: Int,
    val generation: Int,
    val chats: List<String>,
    val at: Double,
    val until: Double,
    val list: List<Message>,
)

/** La dernière recherche dans une discussion. */
internal data class ChatSearchKept(
    val chat: String,
    val query: String,
    val generation: Int,
    val at: Double,
    val until: Double,
    val list: List<Message>,
)

/** Les messages importants de toutes les discussions, à cette génération. */
internal data class StarredKept(val generation: Int, val list: List<Message>)

/** Un paquet v3 demandé au relais : vérifié (ou aucun), et l'heure. */
internal data class CachedBundle(val bundle: KeyBundle?, val at: Double)

/** Le dernier résumé envoyé à la montre et ses octets (voir `fittedWatchData`). */
internal data class WatchKept(val full: WatchSnapshot, val maxBytes: Int, val snapshot: WatchSnapshot, val data: ByteArray?)

/** Une liste de textes en JSON (`[String]`), tout ou rien. */
internal object JsonStrings : JsonDecodable<List<String>> {
    override fun fromJson(json: JsonElement): List<String> {
        val a = json as? JsonArray ?: throw JSONError("une liste est attendue")
        return a.map { it.asString() ?: throw JSONError("un texte est attendu") }
    }

    fun encode(list: List<String>): String = JSON.write(JsonArray(list.map { JsonPrimitive(it) }))
}

/** Un texte en JSON (`"…"`). */
internal object JsonText : JsonDecodable<String> {
    override fun fromJson(json: JsonElement): String = json.asString() ?: throw JSONError("un texte est attendu")
}

/** Un entier en JSON. */
internal object JsonInt : JsonDecodable<Int> {
    override fun fromJson(json: JsonElement): Int = json.asInt() ?: throw JSONError("un entier est attendu")
}

/** Une liste de clés Brocolis, tout ou rien. */
internal object BrocolisKeyList : JsonDecodable<List<BrocolisKey>> {
    override fun fromJson(json: JsonElement): List<BrocolisKey> {
        val a = json as? JsonArray ?: throw JSONError("une liste est attendue")
        return a.map { BrocolisKey.fromJson(it) }
    }

    fun encode(list: List<BrocolisKey>): String = JSON.write(JsonArray(list.map { it.toJson() }))
}

/** Les lignes d'un magasin de la sauvegarde (`[[clé, json, …]]`) : tout ou rien, comme `as? [[Any]]`. */
internal fun dumpRows(e: JsonElement?): List<JsonArray> {
    val a = e as? JsonArray ?: return emptyList()
    if (a.any { it !is JsonArray }) return emptyList()
    return a.map { it as JsonArray }
}

/** La clé d'une ligne de la sauvegarde (son premier élément, s'il est un texte). */
internal fun JsonArray.rowKey(): String? = getOrNull(0).asString()

/** Les objets d'une liste JSON, tout ou rien, comme `as? [[String: Any]]`. */
internal fun objectList(e: JsonElement?): List<JsonObject> {
    val a = e as? JsonArray ?: return emptyList()
    if (a.any { it !is JsonObject }) return emptyList()
    return a.map { it as JsonObject }
}

/** Sans les espaces ni les tabulations autour (`.whitespaces` du Swift : pas les retours à la ligne). */
internal fun String.trimSpaces(): String = trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

/** Sans les blancs autour, retours à la ligne compris (`.whitespacesAndNewlines`). */
internal fun String.trimBlanks(): String = trim { isWhite(it.code) }

/** L'ordre (heure, discussion, identifiant), comme les tuples du Swift. */
internal fun compareAtKey(a: Double, ak: String, b: Double, bk: String): Int =
    if (a != b) a.compareTo(b) else compareCodePoints(ak, bk)

class Engine(
    val store: Store,
    server: String,
    /** Le fil du moteur (et du relais) : le fil principal dans l'application. */
    internal val dispatcher: CoroutineDispatcher = serialDispatcher(),
    sockets: SocketOpener = OkHttpSockets,
) {
    /** Ce que le moteur lance lui-même (envois, minuteries), sur son fil. */
    internal val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val me: Identity = store.read("kv", "identity", Identity) ?: Identity.create()

    var profile: Profile = store.read("kv", "profile", Profile) ?: Profile()
        internal set(value) {
            field = value
            changed()
        }

    var settings: Settings = store.read("kv", "settings", Settings) ?: Settings()
        private set(value) {
            field = value
            changed()
        }

    internal val _contacts = HashMap<String, Contact>()
    val contacts: Map<String, Contact> get() = _contacts

    internal val _chats = HashMap<String, Chat>()
    val chats: Map<String, Chat> get() = _chats

    /** Les clés Brocolis 🥦 de l'appareil. */
    var brocolisKeys: List<BrocolisKey> = loadBrocolis(store)
        private set(value) {
            field = value
            changed()
        }

    /** Les sessions « double cliquet, clé partagée » du clavier Brocolis. */
    internal val brocolisPsk = BrocolisPsk(store.readRaw("kv", BrocolisPsk.kv))

    /** code → (en ligne, vu pour la dernière fois). */
    internal val _presence = HashMap<String, Presence>()
    val presence: Map<String, Presence> get() = _presence

    /** discussion → codes en train d'écrire. */
    internal val _typing = HashMap<String, MutableSet<String>>()
    val typing: Map<String, Set<String>> get() = _typing

    var netState: Relay.State = Relay.State.offline
        private set(value) {
            field = value
            changed()
        }

    /** Augmente à chaque changement des messages d'une discussion. */
    internal val _revisions = HashMap<String, Int>()
    val revisions: Map<String, Int> get() = _revisions

    var statusRevision = 0
        internal set(value) {
            field = value
            changed()
        }

    var callRevision = 0
        private set(value) {
            field = value
            changed()
        }

    /**
     * La discussion à l'écran (ses messages arrivent lus). En ouvrir une
     * autre rend la mémoire des longues discussions lues en entier avant
     * elle (voir `Store.release`).
     */
    var activeChat: String? = null
        set(value) {
            val oldValue = field
            field = value
            if (value != null && value != oldValue) store.release(value)
            // La recherche gardée d'une discussion qu'on quitte tient ses
            // messages (photos comprises) : rendue aussi.
            if (value != oldValue) chatSearchKept = null
        }

    /**
     * L'application est-elle au premier plan ? À l'arrière-plan, la mémoire
     * des longues discussions lues en entier est rendue (sauf celle qui est
     * à l'écran), et celle des textes gardés pour la recherche.
     */
    var foreground = true
        set(value) {
            val oldValue = field
            field = value
            if (oldValue && !value) {
                store.release(activeChat)
                store.forgetLines()
                chatSearchKept = null
            }
        }

    /** Ce que montrent les écrans a changé (`@Observable` sur l'iPhone). */
    var onChange: (() -> Unit)? = null

    /** Un message arrive : (discussion, message, déjà à l'écran). */
    var onIncoming: ((Chat, Message, Boolean) -> Unit)? = null

    /** Quelqu'un réagit à un de vos messages. */
    var onReaction: ((Chat, String, String) -> Unit)? = null

    /** Un signal d'appel (offre, réponse, candidat…). */
    var onCallSignal: ((String, Body, Boolean) -> Unit)? = null

    /** Le relais a refusé un message trop lourd (il est marqué en échec). */
    var onTooLarge: (() -> Unit)? = null

    /** Les clés trouvées pour un code ne sont pas celles de son lien. */
    var onKeysMismatch: ((String) -> Unit)? = null

    /** Cet appareil a été retiré du compte (v4) : tout vient d'être effacé. */
    var onUnlinked: (() -> Unit)? = null

    /**
     * Le relais ne prouve pas son identité habituelle (v6, §7.16) : ses
     * ordres d'effacement sont ignorés.
     */
    var onUnprovenRelay: (() -> Unit)? = null

    val relay: Relay = Relay(me, server, dispatcher, sockets)

    internal var flushing = false
    internal var flushAgain = false

    /** La dernière recherche dans toutes les discussions : voir `searchMessages`. */
    internal var searchKept: SearchKept? = null

    /** La dernière recherche dans une discussion : voir `searchChat`. */
    internal var chatSearchKept: ChatSearchKept? = null

    /**
     * Les actus déjà décodées (clé → la ligne lue, l'actu), et leurs seules
     * dates : voir `statuses` et `statusDates`.
     */
    internal var statusKept = HashMap<String, Kept<StatusItem>>()
    internal var statusHeads = HashMap<String, Kept<StatusItem.Head>>()

    /** Les appels du journal déjà décodés, par clé, avec la ligne dont ils viennent. */
    internal var callsKept = HashMap<String, Kept<CallEntry>>()

    /** Les messages importants de toutes les discussions, tels que trouvés à cette génération : voir `starred()`. */
    internal var starredKept: StarredKept? = null

    /** Les enveloppes de la boîte d'envoi déjà décodées, sans leur contenu : voir `outbox`. */
    internal var outHeads = HashMap<String, Kept<OutItem>>()

    internal var seq = 0
    internal var typingSent = 0.0
    internal val typingTimers = HashMap<String, Job>()
    internal val receipts = LinkedHashMap<String, LinkedHashSet<String>>()
    internal var receiptTask: Job? = null
    internal var sweeper: Job? = null

    /** Le dernier effacement des messages trop vieux (0 : au prochain passage). */
    internal var lastPrune = 0.0

    /** L'appareil v3 et les sessions (chargées contact par contact). */
    internal var v3: V3Engine? = null

    /** Les paquets v3 demandés au relais : (paquet vérifié ou aucun, heure). */
    internal val bundleCache = HashMap<String, CachedBundle>()

    /** Les réparations de session v3 récentes (une toutes les cinq minutes par contact au plus). */
    internal val repairs = HashMap<String, Double>()

    /** Cet appareil (v4) : son identifiant, tiré une fois, jamais sauvegardé ailleurs. */
    var dev = ""
        internal set(value) {
            field = value
            changed()
        }

    /** Les appareils de notre compte (v4). */
    var devices = Devices()
        internal set(value) {
            field = value
            changed()
        }

    /** Nos boîtes tournantes suivies (v4) : boîte → le contact qui y écrit ; et le jour pour lequel elles ont été calculées. */
    internal var boxMap: Map<String, String> = emptyMap()
    internal var boxDay = -1
    internal var boxRefresh: Job? = null

    /** Les empreintes des liens d'invitation ouverts, par code. */
    internal val fingerprints = HashMap<String, String>()

    /** Les codes dont les clés trouvées ne sont pas celles du lien. */
    internal val mismatched = HashSet<String>()

    /**
     * Les tombes déjà décodées, par discussion. Chaque message qui arrive
     * demande s'il est enterré, et le ménage repasse chaque minute : la
     * liste (jusqu'à 20 000 tombes) n'est redécodée que si sa ligne a changé.
     */
    internal val tombCache = HashMap<String, Tombs>()

    /** Le dernier résumé envoyé à la montre et ses octets (voir `fittedWatchData`). */
    internal var watchKept: WatchKept? = null

    init {
        for (c in store.all("contacts", Contact)) _contacts[c.code] = c
        for (c in store.all("chats", Chat)) _chats[c.id] = c
        relay.hidden = settings.hideOnline
        relay.onState = { s -> onNet(s) }
        relay.onEnvelope = { e -> onEnvelope(e) }
        relay.onPresence = { code, online, seen ->
            val was = _presence[code]?.online == true
            _presence[code] = Presence(online, seen)
            changed()
            // Il revient : il a republié son paquet v3 ; ce qui l'attendait
            // (un contact qui refuse l'ancien format) part maintenant. À son
            // code comme à chacun de ses appareils (v4).
            if (online && !was && forgetMissingBundles(code)) {
                spawn { flush() }
            }
        }
        relay.onBoxEnvelope = { e -> onBoxEnvelope(e) }
        relay.onUnlinked = { unlinked() }
        relay.relayKey = store.read("kv", "relais_cle", JsonText) ?: ""
        relay.onRelayKey = { key -> store.writeRaw("kv", "relais_cle", JSON.encode(key)) }
        relay.onUnprovenRelay = { onUnprovenRelay?.invoke() }
        startV3()
        ensureDevice()
        // Une fois : les messages reçus avant que Talka sache lire le « double
        // cliquet » du clavier Brocolis s'ouvrent maintenant (dans l'ordre).
        if (brocolisPsk.firstScan()) {
            reopenMessages()
            store.writeRaw("kv", BrocolisPsk.kv, brocolisPsk.toJson())
        }
    }

    /** Lance `block` sur le fil du moteur (le `Task { }` de l'iPhone). */
    internal fun spawn(block: suspend () -> Unit): Job = scope.launch { block() }

    internal fun changed() {
        onChange?.invoke()
    }

    fun start() {
        relay.start()
        sweep()
        sweeper = scope.launch {
            while (isActive) {
                delay(60_000)
                sweep()
                // Ce qui attend un paquet v3 (ou le réseau) est réessayé.
                flush()
                sendCover()
            }
        }
    }

    fun stop() {
        sweeper?.cancel()
        relay.stop()
        store.save()
    }

    internal fun onNet(s: Relay.State) {
        netState = s
        // Hors ligne, on ne sait plus qui est « en ligne » : le relais le redira
        // à la reconnexion (`watch`). Les « vu à » restent (comme le navigateur).
        if (s != Relay.State.online) _presence.entries.removeAll { it.value.online }
        if (s == Relay.State.online) {
            watchContacts()
            publishV3()
            unlinkGone()
            announceDevices()
            refreshBoxes()
            spawn { flush() }
            spawn { refreshMissingKeys() }
            if (relay.sealed) shareJeton()
        }
    }

    /**
     * Notre profil pour `code`, avec son jeton de remise personnel : pour un
     * contact, ou quelqu'un qui nous écrit (un membre d'un de nos groupes),
     * tant qu'il n'est pas bloqué. Comme le navigateur.
     */
    internal fun profileBody(code: String): Body {
        val c = _contacts[code]
        val jeton = if (c != null && !c.blocked) me.jeton(code, c.jetonEpoch) else ""
        return Body.Profile(profile, jeton = jeton, nk = "", pj = true, devs = devices.announced, dv = devices.v, bx = true)
    }

    /**
     * Notre jeton de remise, pour les contacts qui n'ont pas encore le leur
     * (une fois) : ils pourront nous écrire sans que le relais sache qui
     * écrit. Chacun a le sien ; l'ancien jeton commun est révoqué.
     */
    internal fun shareJeton() {
        // Une seule transaction pour tous les contacts.
        store.transaction {
            for (c0 in _contacts.values.toList()) {
                if (!c0.saved || c0.blocked || c0.jetonPerso) continue
                val c = c0.copy(jetonPerso = true, jetonSent = true)
                saveContact(c)
                enqueue(c.code, Wire(profileBody(c.code)))
            }
        }
        me.jeton.split(".").firstOrNull { it.isNotEmpty() }?.let { revokeJeton(it) }
        sendRevoked()
    }

    /**
     * Les nonces de nos jetons révoqués (contacts bloqués, ancien jeton
     * commun), redonnés au relais à chaque connexion. Les 1 000 derniers.
     */
    internal fun revokeJeton(nonce: String) {
        val list = ArrayList(store.read("kv", "jetons_revoques", JsonStrings) ?: emptyList())
        if (nonce in list) return
        list.add(nonce)
        if (list.size > 1_000) list.subList(0, list.size - 1_000).clear()
        store.writeRaw("kv", "jetons_revoques", JsonStrings.encode(list))
    }

    internal fun sendRevoked() {
        val list = store.read("kv", "jetons_revoques", JsonStrings) ?: emptyList()
        if (list.isNotEmpty()) relay.revoke(list)
    }

    internal fun watchContacts() {
        val codes = LinkedHashSet(_contacts.keys)
        for (c in _chats.values) {
            if (c.group) codes.addAll(c.members) else codes.add(c.id)
        }
        codes.remove(me.code)
        relay.watch(codes.toList())
    }

    internal fun bump(chat: String) {
        _revisions[chat] = (_revisions[chat] ?: 0) + 1
        changed()
    }

    /**
     * Mettre en sourdine pour `seconds` secondes (`null` : pour toujours), ou
     * réactiver le son (`0`), comme dans le navigateur.
     */
    fun mute(chat: String, seconds: Double?) {
        val on = seconds != 0.0
        updateChat(chat) { c -> c.copy(muted = on, muteUntil = if (on) seconds?.let { nowMs() + it * 1000 } else null) }
    }

    /** Les messages éphémères échus et les statuts de plus de 24 heures. */
    fun sweep() {
        val t = nowMs()
        // Un nouveau jour : de nouvelles boîtes tournantes à lire (v4).
        if (boxDay != TalkaV4.day(relay.now())) refreshBoxes()
        // Les sourdines pour un temps qui arrivent à leur terme.
        for (chat in _chats.values.toList()) {
            if (chat.muted && (chat.muteUntil ?: Double.POSITIVE_INFINITY) <= t) {
                updateChat(chat.id) { it.copy(muted = false, muteUntil = null) }
            }
        }
        // Les messages éphémères arrivés à échéance : la discussion n'est
        // relue que si l'un d'eux a pu l'atteindre, et pas chargée pour ça.
        for (chat in _chats.values.toList()) {
            if (chat.timer <= 0 && !chat.hasExp) continue
            val gone = store.removeExpired(chat.id, t)
            bury(chat.id, gone)
            if (gone.isNotEmpty()) {
                refreshLast(chat.id)
                bump(chat.id)
            }
        }
        // Les messages trop vieux (3 mois par défaut), une fois par heure.
        if (settings.keepDays > 0 && t - lastPrune >= 3_600_000) {
            lastPrune = t
            // Seuls les messages d'avant la limite sont lus (par l'index) :
            // au lancement, pas tout l'historique de toutes les discussions.
            val limit = t - settings.keepDays.toDouble() * 86_400_000
            // Leur âge suffit pour les effacer : ni texte ni photo décodés,
            // et une transaction par discussion, pas une par message.
            for (chat in _chats.values.toList()) {
                val gone = store.removeOld(chat.id, limit, settings.keepDays, t)
                bury(chat.id, gone)
                if (gone.isNotEmpty()) {
                    refreshLast(chat.id)
                    bump(chat.id)
                }
            }
        }
        for (s in statusDates()) {
            if (s.at + dayMs >= t) continue
            store.remove("statuses", s.key)
            // Plus rien d'elle en mémoire non plus (sa photo).
            statusHeads.remove(s.key)
            statusKept.remove(s.key)
        }
        statusRevision += 1
        // Les clés pré-signées v3 : renouvelées chaque semaine, puis republiées.
        if (v3?.maintain(V3.secs(t)) == true) {
            saveV3()
            publishV3()
        }
    }

    fun saveSettings(f: (Settings) -> Settings) {
        val keep = settings.keepDays
        settings = f(settings)
        store.write("kv", "settings", settings)
        relay.hidden = settings.hideOnline
        if (settings.keepDays != keep) {
            lastPrune = 0.0
            sweep()
        }
    }

    fun saveProfile(f: (Profile) -> Profile) {
        val p = f(profile)
        profile = p.copy(v = p.v + 1)
        store.write("kv", "profile", profile)
        sync("profile", d = jsonValue(profile))
        announceProfile()
    }

    /** Notre profil à tous nos contacts. */
    internal fun announceProfile() {
        // Chacun reçoit son propre jeton avec le profil : un message par
        // contact, mais tous écrits dans une seule transaction.
        store.transaction {
            for (c in _contacts.values.toList()) {
                if (c.saved && !c.blocked) enqueue(c.code, Wire(profileBody(c.code)))
            }
        }
        spawn { flush() }
    }

    // ----- Lecture -----

    fun contact(code: String): Contact? = _contacts[code]

    fun chat(id: String): Chat? = _chats[id]

    fun isGroup(id: String): Boolean = id.startsWith("g:")

    fun blocked(code: String): Boolean = _contacts[code]?.blocked ?: false

    fun displayName(code: String): String {
        if (code == me.code) return "Vous"
        _contacts[code]?.let { c ->
            if (c.name.isNotEmpty()) return c.name
            if (c.profileName.isNotEmpty()) return "~${c.profileName}"
        }
        return TalkaCode.pretty(code)
    }

    fun avatarOf(code: String): String = if (code == me.code) profile.avatar else _contacts[code]?.avatar ?: ""

    /** Les groupes que l'on partage avec ce contact. */
    fun groupsInCommon(code: String): List<Chat> = Chat.groupsInCommon(_chats.values.toList(), me.code, code)

    fun title(chat: Chat): String = if (chat.group) chat.name.ifEmpty { "Groupe" } else displayName(chat.id)

    fun recipients(chat: Chat): List<String> = if (chat.group) chat.members.filter { it != me.code } else listOf(chat.id)

    fun messages(chat: String, limit: Int = Int.MAX_VALUE): List<Message> = store.messagesOf(chat, limit)

    /**
     * La fin d'une discussion, pour l'afficher : au moins ses `limit`
     * derniers messages, et s'il en existe de plus anciens (voir
     * `Store.recentMessages`). Une longue discussion s'ouvre sur ses
     * derniers messages ; les plus anciens viennent quand on remonte.
     */
    fun recentMessages(chat: String, limit: Int): RecentMessages = store.recentMessages(chat, limit)

    /** Les photos de la discussion, la plus récente d'abord (parmi ses 500 derniers messages, comme le navigateur). */
    fun photos(chat: String): List<Message> = Message.photos(messages(chat, 500), me.code, nowMs())

    /** Les documents de la discussion, le plus récent d'abord (mêmes 500 derniers messages). */
    fun documents(chat: String): List<Message> = Message.documents(messages(chat, 500), me.code, nowMs())

    /** « Gérer le stockage » : les discussions qui gardent des pièces jointes, de la plus lourde à la plus légère. */
    fun storageByChat(): List<ChatStorage> =
        _chats.values
            .map { ChatStorage(it, store.storage(it.id)) }
            .filter { it.storage.bytes > 0 }
            .sortedByDescending { it.storage.bytes }

    /** Les liens de la discussion, le plus récent d'abord (mêmes 500 derniers messages). */
    fun links(chat: String): List<ChatLink> = Message.links(messages(chat, 500), me.code, nowMs())

    // ----- Contacts -----

    internal fun saveContact(c: Contact) {
        val before = _contacts[c.code]
        _contacts[c.code] = c
        store.write("contacts", c.code, c)
        changed()
        // Un correspondant dont on connaît les clés (ou qu'on débloque) : ses
        // boîtes tournantes sont à lire (v4).
        if (c.dh.isNotEmpty() && (before?.dh != c.dh || before?.blocked != c.blocked)) scheduleBoxRefresh()
    }

    /** Les clés d'un code, vérifiées : le code doit être leur empreinte. */
    internal suspend fun keys(code: String): Contact? {
        val c = fetchKeys(code) ?: return null
        if (_contacts[code]?.sign?.isEmpty() ?: true) saveContact(c)
        return c
    }

    /**
     * Les clés d'un code (gardées, ou demandées au relais et vérifiées), sans
     * rien enregistrer : un message scellé n'ajoute son expéditeur qu'une
     * fois prouvé (l'enveloppe intérieure ouverte).
     */
    internal suspend fun fetchKeys(code: String): Contact? {
        _contacts[code]?.let { if (it.sign.isNotEmpty() && it.dh.isNotEmpty()) return it }
        val found = relay.lookup(code) ?: return null
        val sign = found.strOrNull("sign") ?: return null
        val dh = found.strOrNull("dh") ?: return null
        if (!TalkaCrypto.verifyKeys(code, sign, dh)) return null
        // Relu après l'attente : un nom donné entre-temps n'est pas écrasé.
        val c = _contacts[code] ?: Contact(code = code)
        // Le lien d'invitation donnait l'empreinte de ses clés : ce ne sont
        // pas elles. Quelqu'un d'autre se présente sous ce code.
        if (c.fp.isNotEmpty() && TalkaCode.fingerprint(sign, dh) != c.fp) {
            // Une fois par session (pas à chaque reconnexion).
            if (mismatched.add(code)) onKeysMismatch?.invoke(code)
            return null
        }
        return c.copy(sign = sign, dh = dh)
    }

    /** Un lien d'invitation donne l'empreinte des clés de ce code : elle sera vérifiée quand il sera ajouté. */
    fun expectFingerprint(code: String, fp: String) {
        if (TalkaCode.validFingerprint(fp)) fingerprints[code] = fp
    }

    internal suspend fun refreshMissingKeys() {
        for (c in _contacts.values.toList()) {
            if (c.sign.isNotEmpty()) continue
            if (keys(c.code) != null) flush()
        }
    }

    sealed class AddError(message: String) : Exception(message) {
        class Yourself : AddError("C'est votre propre code 🙂")

        class KeysMismatch :
            AddError("Les clés de ce code ne correspondent pas au lien d'invitation : quelqu'un se fait peut-être passer pour lui.")
    }

    /** Ajoute un contact par son code ; `true` s'il est joignable tout de suite. */
    suspend fun addContact(code: String, name: String): Boolean =
        withContext(dispatcher) {
            if (code == me.code) throw AddError.Yourself()
            var c = _contacts[code] ?: Contact(code = code)
            fingerprints.remove(code)?.let { fp ->
                if (c.sign.isNotEmpty() && TalkaCode.fingerprint(c.sign, c.dh) != fp) throw AddError.KeysMismatch()
                c = c.copy(fp = fp)
            }
            // Notre profil part avec son jeton personnel.
            c = c.copy(saved = true, jetonPerso = !c.blocked, jetonSent = !c.blocked)
            val n = name.trimSpaces().prefixChars(40)
            if (n.isNotEmpty()) c = c.copy(name = n)
            saveContact(c)
            relay.watch(listOf(code))
            val reachable = keys(code) != null
            // Les clés trouvées ne sont pas celles du lien : on ne l'ajoute pas (et
            // on ne lui envoie rien).
            if (!reachable && code in mismatched) {
                updateContact(code) { it.copy(saved = false) }
                throw AddError.KeysMismatch()
            }
            enqueue(code, Wire(profileBody(code)))
            spawn { flush() }
            _contacts[code]?.let { syncContact(it) }
            reachable
        }

    fun updateContact(code: String, f: (Contact) -> Contact) {
        val before = _contacts[code] ?: return
        val c = f(before)
        saveContact(c)
        if (before.name != c.name || before.saved != c.saved || before.blocked != c.blocked) syncContact(c)
    }

    fun setBlocked(code: String, blocked: Boolean) {
        var c = _contacts[code] ?: Contact(code = code)
        // Bloqué : son jeton de remise est révoqué ; débloqué, il en recevra
        // un nouveau.
        if (blocked && !c.blocked && c.jetonPerso) {
            revokeJeton(me.jetonNonce(code, c.jetonEpoch))
            c = c.copy(jetonEpoch = c.jetonEpoch + 1, jetonPerso = false)
            sendRevoked()
        }
        val unblocked = !blocked && c.blocked
        val changedState = c.blocked != blocked
        c = c.copy(blocked = blocked)
        saveContact(c)
        if (unblocked && relay.sealed) shareJeton()
        if (changedState) syncContact(c)
    }

    // ----- Discussions -----

    internal fun saveChat(c: Chat) {
        _chats[c.id] = c
        store.write("chats", c.id, c)
        changed()
    }

    fun ensureDirectChat(code: String): Chat {
        _chats[code]?.let { return it }
        val c = Chat(id = code)
        saveChat(c)
        relay.watch(listOf(code))
        return c
    }

    fun updateChat(id: String, f: (Chat) -> Chat) {
        val before = _chats[id] ?: return
        val c = f(before)
        saveChat(c)
        // Archivée, épinglée, en sourdine… : pareil sur nos autres appareils.
        val was = ChatState(before)
        val after = ChatState(c)
        if (was != after) sync("chat", c = id, d = jsonValue(after))
    }

    // ----- Brocolis 🥦 -----
    //
    // Les clés de l'app Brocolis : gardées par le système (voir
    // `BrocolisVault`) quand l'application l'a branché, dans la base sinon.
    // Une clé se partage par Talka (bulle « clé partagée ») et se renouvelle,
    // comme dans le navigateur.

    internal fun saveBrocolis() {
        if (BrocolisVault.isAvailable && BrocolisVault.write(brocolisKeys)) {
            store.remove("kv", "brocolis")
            return
        }
        store.writeRaw("kv", "brocolis", BrocolisKeyList.encode(brocolisKeys))
    }

    /** Les clés qui chiffrent ce qui part dans `c` (celles qui existent encore, et pas celles qu'on a renouvelées). */
    fun chatBrocolis(c: Chat): List<BrocolisKey> = brocolisKeys.filter { !it.retired && it.id in c.brocolis }

    internal val allBrocolisKeys: List<ByteArray> get() = brocolisKeys.mapNotNull { Brocolis.key(it.key) }

    /**
     * Les clés qui ouvrent les jetons reçus dans `chat` : celles liées à
     * cette discussion, et celles liées à aucune. Pas la clé partagée avec
     * quelqu'un d'autre : un jeton capturé ailleurs et renvoyé ici ne s'ouvre
     * pas (ni ne se recopie en clair dans une réponse). Comme le navigateur.
     */
    internal fun brocolisKeys(chat: String): List<ByteArray> {
        val used = _chats.values.flatMap { it.brocolis }.toSet()
        val here = (_chats[chat]?.brocolis ?: emptyList()).toSet()
        return brocolisKeys.filter { it.id in here || it.id !in used }.mapNotNull { Brocolis.key(it.key) }
    }

    /**
     * Ouvre aussi les jetons « double cliquet, clé partagée » (v3) du
     * clavier Brocolis, avec les clés de la discussion, et garde l'état.
     */
    internal fun openBrocolis(text: String, chat: String): Brocolis.Opened {
        val keys = brocolisKeys(chat)
        var o = Brocolis.open(text, keys)
        if (Brocolis.hasRatchetToken(o.text)) {
            val r = brocolisPsk.open(o.text, keys, V3.secs(nowMs()))
            if (r.opened > 0) {
                o = o.copy(text = r.text, opened = o.opened + r.opened, locked = maxOf(0, o.locked - r.opened))
            }
            saveBrocolisPsk()
        }
        return o
    }

    internal fun sealText(c: Chat, text: String): String {
        val keys = chatBrocolis(c).mapNotNull { Brocolis.key(it.key) }
        // À deux, avec une seule clé : le format du clavier Brocolis (« double
        // cliquet, clé partagée »), que l'autre peut « déchiffrer » au clavier.
        if (!c.group && keys.size == 1) {
            val sealed = brocolisPsk.seal(text, keys[0])
            if (sealed != null) {
                saveBrocolisPsk()
                return sealed
            }
        }
        return Brocolis.seal(text, keys)
    }

    /** Écrit l'état du « double cliquet » du clavier Brocolis s'il a changé. */
    internal fun saveBrocolisPsk() {
        if (brocolisPsk.takeDirty()) store.writeRaw("kv", BrocolisPsk.kv, brocolisPsk.toJson())
    }

    /** Le jeton d'origine d'un message Brocolis (ouvert ou scellé ici), pour « Copier chiffré 🥦 » : à coller dans le clavier Brocolis. */
    fun brocolisOriginal(m: Message): String? = brocolisPsk.original(m.chat + "/" + m.id)

    internal fun rememberOriginal(m: Message, token: String) {
        if (!Brocolis.hasToken(token)) return
        brocolisPsk.remember(m.chat + "/" + m.id, token)
        saveBrocolisPsk()
    }

    /**
     * Scelle le texte et la citation de `content` ; `first` : le texte l'a
     * été. Une clé partagée ne se scelle pas : elle voyage dans le
     * chiffrement de Talka.
     */
    internal fun sealContent(c: Chat, content: Content): Pair<Boolean, Content> {
        if (content.kind == "brokey") return false to content
        val sealed = sealText(c, content.text)
        val bro = sealed != content.text
        var out = content.copy(text = sealed)
        content.reply?.let { r -> out = out.copy(reply = r.copy(text = sealText(c, r.text))) }
        return bro to out
    }

    /**
     * « Déchiffrer 🥦 » sur un message resté chiffré : toutes vos clés
     * Brocolis s'y essaient (c'est vous qui le demandez, pour ce message-là),
     * pas seulement celles de la discussion. `true` s'il s'est ouvert. Le même
     * que le navigateur (`decrypt_now`).
     */
    fun decryptNow(chat: String, id: String): Boolean {
        val m = store.message(chat, id) ?: return false
        if (!Brocolis.hasToken(m.text)) return false
        val keys = allBrocolisKeys
        var o = Brocolis.open(m.text, keys)
        if (Brocolis.hasRatchetToken(o.text)) {
            val r = brocolisPsk.open(o.text, keys, V3.secs(nowMs()))
            o = o.copy(text = r.text, opened = o.opened + r.opened)
        }
        saveBrocolisPsk()
        if (o.opened <= 0) return false
        rememberOriginal(m, m.text)
        store.writeMessage(m.copy(text = o.text, bro = true))
        refreshLast(chat)
        bump(chat)
        return true
    }

    /** Ouvre les jetons d'un message et de sa citation ; `first` : l'un l'a été. */
    internal fun openMessage(m0: Message): Pair<Boolean, Message> {
        var m = m0
        var opened = false
        if (Brocolis.hasToken(m.text)) {
            val o = openBrocolis(m.text, m.chat)
            if (o.opened > 0) {
                rememberOriginal(m, m.text)
                m = m.copy(text = o.text, bro = true)
                opened = true
            }
        }
        val r = m.reply
        if (r != null && Brocolis.hasToken(r.text)) {
            val o = openBrocolis(r.text, m.chat)
            if (o.opened > 0) {
                m = m.copy(reply = r.copy(text = o.text))
                opened = true
            }
        }
        return opened to m
    }

    sealed class BrocolisError(message: String) : Exception(message) {
        class NotAKey : BrocolisError("Ce n'est pas une clé Brocolis (32 octets en base64).")

        class Forgotten : BrocolisError("Cette clé a été oubliée.")

        class Unknown : BrocolisError("Clé introuvable.")
    }

    /**
     * Ajoute une clé (collée seule, ou dans l'invitation de l'app Brocolis),
     * puis ouvre les messages déjà reçus qu'elle déverrouille. Une clé déjà
     * là est renvoyée telle quelle.
     */
    fun addBrocolisKey(name: String, pasted: String): BrocolisKey {
        val key = Brocolis.findKey(pasted) ?: throw BrocolisError.NotAKey()
        brocolisKeys.firstOrNull { it.key == key }?.let { return it }
        val trimmed = name.trimSpaces()
        val k = BrocolisKey(randomId(6), if (trimmed.isEmpty()) "Clé ${brocolisKeys.size + 1}" else trimmed.prefixChars(40), key)
        brocolisKeys = brocolisKeys + k
        saveBrocolis()
        reopenMessages()
        return k
    }

    fun renameBrocolisKey(id: String, name: String) {
        val trimmed = name.trimSpaces().prefixChars(40)
        if (trimmed.isEmpty() || brocolisKeys.none { it.id == id }) return
        brocolisKeys = brocolisKeys.map { if (it.id == id) it.copy(name = trimmed) else it }
        saveBrocolis()
    }

    /** Oublie une clé : les discussions qu'elle chiffrait n'en ont plus besoin. Les messages déjà ouverts le restent. */
    fun removeBrocolisKey(id: String) {
        brocolisKeys = brocolisKeys.filter { it.id != id }
        saveBrocolis()
        for (c in _chats.values.toList()) {
            if (id in c.brocolis) updateChat(c.id) { ch -> ch.copy(brocolis = ch.brocolis.filter { it != id }) }
        }
    }

    internal fun retire(id: String) {
        val i = brocolisKeys.indexOfFirst { it.id == id }
        if (i < 0 || brocolisKeys[i].retired) return
        brocolisKeys = brocolisKeys.mapIndexed { j, k -> if (j == i) k.copy(retired = true, name = k.name + " (ancienne)") else k }
    }

    /**
     * Renouvelle une clé : une neuve, du même nom, la remplace dans toutes ses
     * discussions et y est envoyée par Talka ; l'ancienne garde l'historique
     * lisible mais ne chiffre plus rien. Renvoie le nombre de discussions.
     */
    fun renewBrocolisKey(id: String): Int {
        val old = brocolisKeys.firstOrNull { it.id == id } ?: throw BrocolisError.Unknown()
        val fresh = BrocolisKey(randomId(6), old.name, Brocolis.generateKey())
        retire(id)
        brocolisKeys = brocolisKeys + fresh
        saveBrocolis()
        val using = _chats.values.filter { id in it.brocolis }
        for (c in using) {
            updateChat(c.id) { ch -> ch.copy(brocolis = ch.brocolis.map { if (it == id) fresh.id else it }) }
            shareBrocolisKey(c.id, fresh.id, replaces = old)
        }
        return using.size
    }

    /**
     * Envoie une clé dans une discussion, par le chiffrement de bout en bout
     * de Talka : plus besoin de la copier-coller ailleurs.
     *
     * Sur le fil, `data` porte la clé et `code` l'empreinte de celle qu'elle
     * remplace (un renouvellement). L'appareil, lui, ne garde jamais la clé
     * dans ses messages une fois qu'elle est dans ses clés : `data` est vidé
     * et `code` désigne la clé (son `id`).
     */
    fun shareBrocolisKey(chat: String, keyId: String, replaces: BrocolisKey? = null): Message? {
        val k = brocolisKeys.firstOrNull { it.id == keyId } ?: return null
        val content = Content(
            kind = "brokey", text = sharedKeyFallback, data = k.key, name = k.name,
            code = replaces?.let { Brocolis.fingerprint(it.key) } ?: "",
        )
        val m = send(chat, content) ?: return null
        return forgetSharedKey(m, k.id)
    }

    internal fun forgetSharedKey(m: Message, keyId: String): Message {
        val out = m.copy(data = "", code = keyId)
        store.writeMessage(out)
        bump(out.chat)
        return out
    }

    /** La clé d'une bulle « clé partagée », si elle est dans vos clés. */
    fun sharedKey(m: Message): BrocolisKey? = brocolisKeys.firstOrNull { if (m.data.isEmpty()) it.id == m.code else it.key == m.data }

    /**
     * Ajoute une clé reçue dans une discussion et l'y active ; si elle en
     * renouvelle une, l'ancienne n'y chiffre plus (elle garde l'historique).
     */
    fun acceptBrocolisKey(m: Message): BrocolisKey {
        if (m.data.isEmpty()) return sharedKey(m) ?: throw BrocolisError.Forgotten()
        val k = addBrocolisKey(m.name, m.data)
        val old = if (m.code.isEmpty()) null else brocolisKeys.firstOrNull { it.id != k.id && Brocolis.fingerprint(it.key) == m.code }
        if (old != null) {
            retire(old.id)
            saveBrocolis()
        }
        _chats[m.chat]?.let { c ->
            val ids = ArrayList(c.brocolis.filter { it != old?.id })
            if (k.id !in ids) ids.add(k.id)
            setChatBrocolis(m.chat, ids)
        }
        store.message(m.chat, m.id)?.let { forgetSharedKey(it, k.id) }
        return k
    }

    /** Les clés qui chiffrent ce qui part dans une discussion (vide : aucune). */
    fun setChatBrocolis(chat: String, ids: List<String>) {
        updateChat(chat) { it.copy(brocolis = ids) }
        bump(chat)
    }

    /** Les messages gardés avec des jetons qu'une nouvelle clé peut ouvrir. */
    internal fun reopenMessages() {
        val touched = LinkedHashSet<String>()
        // Une discussion après l'autre, dans le même ordre qu'en les lisant
        // toutes d'un coup (les jetons s'ouvrent dans l'ordre), mais sans
        // tenir tous les messages de l'appareil en mémoire.
        for (chat in store.chatsWithMessages()) {
            for (m in store.passingMessages(chat)) {
                val (opened, out) = openMessage(m)
                if (!opened) continue
                store.writeMessage(out)
                touched.add(out.chat)
            }
        }
        for (chat in touched) {
            refreshLast(chat)
            bump(chat)
        }
    }

    // ----- Les pierres tombales -----
    //
    // Un message effacé ne revient pas si un relais rejouait sa vieille
    // enveloppe dans l'ancien format : son identifiant est gardé 31 jours
    // (le relais ne garde rien plus de 30 jours ; un message plus vieux dans
    // l'ancien format est refusé d'office). Comme le navigateur.

    internal data class Tomb(val id: String, val at: Double) : JsonEncodable {
        override fun toJson(): JsonObject =
            buildJsonObject {
                put("id", id)
                put("at", at)
            }

        companion object : JsonDecodable<Tomb> {
            override fun fromJson(json: JsonElement): Tomb {
                val c = json.obj()
                return Tomb(c.reqStr("id"), c.reqDouble("at"))
            }

            fun list(tombs: List<Tomb>): String = JSON.write(JsonArray(tombs.map { it.toJson() }))
        }
    }

    /** Une liste de tombes, tout ou rien. */
    internal object TombList : JsonDecodable<List<Tomb>> {
        override fun fromJson(json: JsonElement): List<Tomb> {
            val a = json as? JsonArray ?: throw JSONError("une liste est attendue")
            return a.map { Tomb.fromJson(it) }
        }
    }

    /**
     * Les tombes d'une discussion, décodées : la ligne lue (pour savoir si
     * elle a changé), la liste, ses identifiants, la plus ancienne heure.
     */
    internal class Tombs(var json: String, val list: ArrayList<Tomb>, val ids: HashSet<String>, var oldest: Double)

    internal fun tombs(chat: String): Tombs? {
        val json = store.readRaw("kv", "tombes:$chat")
        if (json == null) {
            tombCache.remove(chat)
            return null
        }
        tombCache[chat]?.let { if (it.json == json) return it }
        val list = ArrayList(JSON.decode(TombList, json) ?: emptyList())
        val fresh = Tombs(json, list, list.mapTo(HashSet()) { it.id }, list.minOfOrNull { it.at } ?: Double.POSITIVE_INFINITY)
        tombCache[chat] = fresh
        return fresh
    }

    internal fun bury(chat: String, msgs: List<Pair<String, Double>>) {
        val t = nowMs()
        val key = "tombes:$chat"
        val kept = tombs(chat)
        // Rien à enterrer, et aucune tombe assez vieille pour partir.
        if (msgs.isEmpty() && t - (kept?.oldest ?: Double.POSITIVE_INFINITY) < tombWindow) return
        // Le cas de tous les jours : aucune tombe assez vieille pour partir,
        // et de la place. Les nouvelles s'écrivent au bout de la ligne, sans
        // réencoder les milliers d'autres.
        if (kept != null && kept.list.isNotEmpty() && t - kept.oldest < tombWindow && kept.json.endsWith("]")) {
            val fresh = ArrayList<Tomb>()
            for ((id, at) in msgs) {
                if (t - at < tombWindow && id !in kept.ids && fresh.none { it.id == id }) fresh.add(Tomb(id, at))
            }
            if (kept.list.size + fresh.size <= 20_000) {
                if (fresh.isEmpty()) return
                // Seule référence à la liste et aux identifiants : ils
                // grandissent sur place, sans copie.
                tombCache.remove(chat)
                val grown = kept
                grown.json = grown.json.dropLast(1) + "," + Tomb.list(fresh).substring(1)
                for (tomb in fresh) {
                    grown.ids.add(tomb.id)
                    grown.oldest = minOf(grown.oldest, tomb.at)
                }
                grown.list.addAll(fresh)
                store.writeRaw("kv", key, grown.json)
                tombCache[chat] = grown
                return
            }
        }
        val list = ArrayList(kept?.list ?: emptyList())
        var ids = HashSet(kept?.ids ?: emptySet())
        val before = list.size
        list.removeAll { t - it.at >= tombWindow }
        var changedRow = list.size != before
        // Des tombes sont parties : leurs identifiants aussi.
        if (changedRow) ids = list.mapTo(HashSet()) { it.id }
        for ((id, at) in msgs) {
            if (t - at < tombWindow && ids.add(id)) {
                list.add(Tomb(id, at))
                changedRow = true
            }
        }
        val over = list.size - 20_000
        if (over > 0) list.subList(0, over).clear()
        if (!changedRow) return
        if (list.isEmpty()) {
            store.remove("kv", key)
            tombCache.remove(chat)
        } else {
            val json = Tomb.list(list)
            store.writeRaw("kv", key, json)
            if (over > 0) ids = list.mapTo(HashSet()) { it.id }
            tombCache[chat] = Tombs(json, list, ids, list.minOfOrNull { it.at } ?: Double.POSITIVE_INFINITY)
        }
    }

    internal fun buried(chat: String, id: String): Boolean = tombs(chat)?.ids?.contains(id) ?: false

    internal fun buryAll(chat: String) {
        val t = nowMs()
        // Leurs identifiants et leurs dates suffisent : la discussion n'est
        // pas chargée (ni même déchiffrée) pour être vidée ou supprimée.
        bury(chat, store.datesOf(chat, t - tombWindow))
    }

    /** Une offre d'appel jamais vue (les 200 dernières sont gardées) ? */
    internal fun firstOffer(callId: String): Boolean {
        val seen = ArrayList(store.read("kv", "appels_vus", JsonStrings) ?: emptyList())
        if (callId in seen) return false
        seen.add(callId)
        if (seen.size > 200) seen.subList(0, seen.size - 200).clear()
        store.writeRaw("kv", "appels_vus", JsonStrings.encode(seen))
        return true
    }

    fun deleteChat(id: String) {
        dropChat(id)
        sync("chat", c = id, d = JSONValue.Object(mapOf("deleted" to JSONValue.Bool(true))))
    }

    internal fun dropChat(id: String) {
        buryAll(id)
        store.deleteMessages(id)
        store.remove("chats", id)
        _chats.remove(id)
        bump(id)
    }

    /** Vide une discussion ; `keepStarred` : sauf les messages favoris (comme WhatsApp). Nos autres appareils font de même. */
    fun clearChat(id: String, keepStarred: Boolean = false) {
        if (keepStarred) emptyChatButStarred(id) else emptyChat(id)
        sync("chat", c = id, d = JSONValue.Object(linkedMapOf("cleared" to JSONValue.Bool(true), "keep_starred" to JSONValue.Bool(keepStarred))))
    }

    /** La discussion a-t-elle des messages favoris ? */
    fun hasStarred(id: String): Boolean = store.starredIds(id).isNotEmpty()

    /** Combien de messages importants dans la discussion (ses infos l'affichent), sans la charger. */
    fun starredCount(chat: String): Int = store.starredIds(chat).size

    /** Efface tout sauf les messages favoris. */
    internal fun emptyChatButStarred(id: String) {
        val t = nowMs()
        // Les messages importants restent ; tout le reste part d'un coup,
        // sans charger la discussion ni l'effacer message par message.
        val keep = store.starredIds(id)
        val gone = store.datesOf(id, t - tombWindow).filter { it.first !in keep }
        store.deleteMessages(id, keep)
        bury(id, gone)
        refreshLast(id)
        _chats[id]?.let { saveChat(it.copy(unread = 0, mentioned = false)) }
        bump(id)
    }

    internal fun emptyChat(id: String) {
        buryAll(id)
        store.deleteMessages(id)
        _chats[id]?.let { saveChat(it.copy(last = null, unread = 0, mentioned = false)) }
        bump(id)
    }

    /**
     * La recherche dans toutes les discussions, comme le navigateur : les
     * messages qui contiennent `query` (accents et majuscules ignorés), les
     * plus récents d'abord. Ni messages système ou supprimés, ni capsule
     * scellée, ni discussion avec un contact bloqué.
     */
    fun searchMessages(query: String, limit: Int = 40): List<Message> {
        val q = query.trimSpaces()
        if (q.charCount() < 2) return emptyList()
        val now = nowMs()
        // Les discussions fouillées, dans l'ordre où elles le sont.
        val ids = _chats.values.filter { it.group || !blocked(it.id) }.map { it.id }
        val generation = store.messagesGeneration
        // La liste des discussions la demande deux fois par frappe : la même
        // recherche n'est pas refaite tant que rien de ce dont elle dépend
        // n'a changé (les messages, les discussions fouillées, une capsule
        // qui s'ouvre).
        searchKept?.let { k ->
            if (k.query == q && k.limit == limit && k.generation == generation && k.chats == ids && now >= k.at && now < k.until) return k.list
        }
        // Dans le texte des messages, gardé à part : pas en chargeant toutes
        // les discussions en entier, photos comprises. Seuls les messages
        // trouvés sont lus, sans que cela finisse par charger leur discussion.
        val found = Message.matching(ids.map { store.lines(it) }, q, me.code, now)
        val list = found.found
            .sortedWith { a, b ->
                // Les plus récents d'abord : (heure, discussion, identifiant) décroissants.
                val c = compareAtKey(b.at, b.chat, a.at, a.chat)
                if (c != 0) c else compareCodePoints(b.id, a.id)
            }
            .take(limit)
            .mapNotNull { store.passingMessage(it.chat, it.id) }
        searchKept = SearchKept(q, limit, generation, ids, now, found.nextOpen, list)
        return list
    }

    /**
     * La recherche dans une discussion (son écran) : ses messages qui
     * contiennent `query` (accents et majuscules ignorés), dans l'ordre de
     * la discussion. Ni messages système ou supprimés, ni capsule scellée.
     * Une longue discussion n'est pas chargée en entier pour être fouillée :
     * son texte, gardé à part, suffit, et seuls les messages trouvés sont
     * lus (au-delà d'une centaine, la discussion l'est d'un coup). L'écran
     * la redemande à chaque dessin : la même recherche n'est pas refaite
     * tant qu'aucun message n'a changé et qu'aucune capsule ne s'est ouverte.
     */
    fun searchChat(chatId: String, query: String): List<Message> {
        val q = query.trimSpaces()
        if (q.isEmpty()) return emptyList()
        val now = nowMs()
        val generation = store.messagesGeneration
        chatSearchKept?.let { k ->
            if (k.chat == chatId && k.query == q && k.generation == generation && now >= k.at && now < k.until) return k.list
        }
        val list: List<Message>
        val nextOpen: Double
        if (q.charCount() == 1) {
            // Une seule lettre trouve presque tout : la discussion est lue
            // en entier de toute façon, autant la fouiller directement.
            val found = Message.matching(listOf(messages(chatId)), q, me.code, now)
            list = found.found
            nextOpen = found.nextOpen
        } else {
            val found = Message.matching(listOf(store.lines(chatId)), q, me.code, now)
            nextOpen = found.nextOpen
            if (found.found.size > searchSingles) {
                val ids = found.found.mapTo(HashSet()) { it.id }
                list = messages(chatId).filter { it.id in ids }
            } else {
                list = found.found
                    .sortedWith { a, b -> compareAtKey(a.at, a.id, b.at, b.id) }
                    .mapNotNull { store.passingMessage(chatId, it.id) }
            }
        }
        chatSearchKept = ChatSearchKept(chatId, q, generation, now, nextOpen, list)
        return list
    }

    /**
     * Ce qu'on lit d'un message sur une ligne (épingle, favoris, réponse) :
     * son texte, sauf s'il est supprimé ou si c'est une capsule encore
     * scellée pour soi ; sinon son aperçu (« ⏳ Capsule temporelle »…).
     */
    fun readable(m: Message, now: Double = nowMs()): String =
        if (m.kind == "text" && !m.deleted && !m.sealed(me.code, now)) Brocolis.masked(m.text) else preview(m)

    /**
     * Une discussion en texte, comme le navigateur (« Exporter la
     * discussion ») : (nom du fichier, texte). Ce qu'on lit dans la
     * discussion, rien d'autre : une capsule scellée reste scellée.
     */
    fun exportChat(chatId: String, now: Double = nowMs()): ChatExport? {
        val c = chat(chatId) ?: return null
        val t = title(c)
        val out = StringBuilder("Discussion Talka « $t », exportée le ${exportStamp(now)}.\nChiffrée de bout en bout dans Talka ; ce fichier, lui, ne l'est pas.\n\n")
        for (m in messages(chatId)) {
            if ((m.exp ?: Double.POSITIVE_INFINITY) <= now) continue
            val author: String? =
                when {
                    m.kind == "system" || m.from == "system" -> null
                    m.from == me.code -> profile.name.ifEmpty { "Vous" }
                    else -> displayName(m.from)
                }
            out.append(exportLine(exportStamp(m.at), author, readable(m, now))).append("\n")
        }
        return ChatExport(exportFilename(t), out.toString())
    }

    internal fun lastOf(m: Message): Last = Last(m.id, m.from, preview(m), m.at, m.kind)

    internal fun refreshLast(chat: String) {
        val last = store.messagesOf(chat, 1).lastOrNull()
        updateChat(chat) { it.copy(last = last?.let(::lastOf)) }
    }

    internal fun storeMessage(m: Message, bumpChat: Boolean = true) {
        store.writeMessage(m)
        if (bumpChat) {
            _chats[m.chat]?.let { c0 ->
                var c = c0.copy(last = lastOf(m), lastAt = maxOf(c0.lastAt, m.at))
                if (c.unarchivesOn(m.from, me.code, settings.keepArchived)) c = c.copy(archived = false)
                if (m.exp != null) c = c.copy(hasExp = true)
                saveChat(c)
            }
        }
        bump(m.chat)
    }

    // ----- Envoi -----

    /**
     * Une enveloppe à envoyer à `to`, gardée jusqu'à ce que le relais confirme ;
     * `live` : seulement si le destinataire est en ligne, sans garde.
     */
    internal fun enqueue(to: String, wire: Wire, chat: String = "", msgId: String = "", live: Boolean = false) {
        enqueue(to, payload = wire.json(), chat = chat, msgId = msgId, live = live)
    }

    /**
     * `payload` : le message déjà mis en JSON. Un envoi à tout un groupe ne
     * le refait pas pour chaque membre (une photo pèse des centaines de Ko),
     * et ne l'échappe qu'une fois pour la boîte d'envoi (`escaped`, voir
     * `OutItem.json(escapedPayload)`).
     */
    internal fun enqueue(
        to: String, payload: String, escaped: String? = null, chat: String = "", msgId: String = "", live: Boolean = false,
    ) {
        seq += 1
        val item = OutItem(
            id = "${msgId.ifEmpty { randomId(10) }}:$to", to = to, payload = payload,
            chat = chat, msgId = msgId, seq = nowMs() * 1000 + (seq % 1000).toDouble(),
        )
        if (live) {
            spawn { sendNow(item, live = true) }
            return
        }
        if (escaped != null) {
            store.writeRaw("outbox", item.id, item.json(escaped))
        } else {
            store.write("outbox", item.id, item)
        }
        spawn { flush() }
    }

    /**
     * Le même message pour plusieurs destinataires (un groupe, une actu
     * pour tous ses contacts) : encodé et échappé une fois, et toute sa
     * boîte d'envoi écrite en une transaction.
     */
    internal fun enqueueAll(codes: List<String>, wire: Wire, chat: String = "", msgId: String = "") {
        if (codes.isEmpty()) return
        val payload = wire.json()
        val escaped = if (codes.size > 1) JSON.encode(payload) else null
        store.transaction {
            for (code in codes) enqueue(code, payload = payload, escaped = escaped, chat = chat, msgId = msgId)
        }
    }

    /** Comme `enqueue`, pour un seul appareil du destinataire (v4) : les autres sont marqués comme déjà servis. */
    internal fun enqueueTo(to: String, d: String, wire: Wire) {
        val item = OutItem(
            id = "${randomId(10)}:$to", to = to, payload = wire.json(), chat = "", msgId = "",
            seq = nowMs() * 1000, sent = targets(to).filter { it != d },
        )
        store.write("outbox", item.id, item)
        spawn { flush() }
    }

    /**
     * Envoie un élément de la boîte d'envoi : une copie par appareil du
     * destinataire (v4), chacune chiffrée par sa propre session. Les
     * appareils déjà servis (`item.sent`) ne la reçoivent pas deux fois.
     * Rend la réponse, et l'élément (ses appareils servis).
     */
    internal suspend fun sendNow(item0: OutItem, live: Boolean): Pair<Relay.Ack, OutItem> {
        var item = item0
        val mine = item.to == me.code
        val peer: Contact =
            if (mine) {
                selfContact
            } else {
                keys(item.to) ?: return Relay.Ack.Failed("unknown") to item
            }
        var failure: Relay.Ack? = null
        for (d in targets(item.to)) {
            if (d in item.sent) continue
            val ack = sendOne(item, peer, d, mine, live)
            // Le relais ne connaît plus cet appareil (retiré) : on n'insiste pas.
            if (ack == Relay.Ack.Ok || ack == Relay.Ack.Failed("dev")) {
                item = item.copy(sent = item.sent + d)
            } else if (failure == null) {
                failure = ack
            }
        }
        return (failure ?: Relay.Ack.Ok) to item
    }

    /** Une copie, pour un appareil (`d` vide : le code, avant la v4). */
    internal suspend fun sendOne(item: OutItem, peer: Contact, d: String, mine: Boolean, live: Boolean): Relay.Ack {
        val addr = TalkaV4.address(peer.code, d)
        loadV3(addr)
        // « écrit… » et les signaux d'appel ne sont remis qu'à un destinataire
        // en ligne : hors ligne, le relais les jette, et chacun laisserait chez
        // lui une clé sautée dans la session. Ils gardent alors l'ancien format.
        val ratchet = !live || mine || _presence[peer.code]?.online == true
        // Sans session, le paquet de l'appareil en ouvre une (s'il en a publié un).
        var bundle: KeyBundle? = null
        val engine = v3
        if (ratchet && engine != null && !engine.hasSession(addr)) bundle = bundleFor(peer, d)
        // Chiffré au moment de partir : un nouvel essai chiffre à nouveau (un
        // autre numéro de message) ; le destinataire range par identifiant.
        // Une pièce jointe trop grosse pour un jeton v3, vers un contact dont
        // la version le sait : chiffrée à part, sa clé dans le message v3.
        var payload = item.payload
        var blob: ByteArray? = null
        if (ratchet && peer.pj && utf8Length(payload) > Session.maxPlaintext) {
            Attachment.split(payload)?.let {
                payload = it.small
                blob = it.blob
            }
        }
        if (ratchet) v3?.maintainPq(V3.secs(nowMs()))
        var sealed = (if (ratchet) v3?.sealTo(me, addr, peer.code, peer.dh, bundle, payload) else null)
            ?: TalkaCrypto.seal(me, peer.code, peer.dh, payload)?.let { V3.Sealed(it, false) }
            ?: return Relay.Ack.Failed("crypto")
        // Un contact qui refuse l'ancien format : sans session ni paquet, on
        // attend qu'il en publie un.
        if (ratchet && peer.pj && !sealed.v3 && utf8Length(payload) <= Session.maxPlaintext) return Relay.Ack.Failed("v3")
        blob?.let { sealed = sealed.copy(body = sealed.body + "|" + b64(it)) }
        // La session a avancé : rien ne part avant qu'elle soit écrite (une clé
        // de message ne doit jamais servir deux fois).
        if (sealed.v3) saveV3()
        // Un identifiant par copie (le relais répond copie par copie).
        val id = if (d.isEmpty()) item.id else "${item.id}#$d"
        // Nos propres appareils : le relais sait déjà qui nous sommes.
        if (mine) return relay.send(id, peer.code, d, sealed.body, live)
        val wake = !live && item.msgId.isNotEmpty()
        // Et, s'il a donné sa clé de notification (celle de cet appareil en
        // v4), avec un aperçu chiffré pour elle : le relais le transmet sans
        // pouvoir le lire.
        val nk = if (d.isEmpty()) peer.nk else (peer.devs.firstOrNull { it.d == d }?.nk ?: "")
        val notice =
            if (wake && nk.isNotEmpty()) {
                Wire.parse(item.payload)?.let { Companion.apercu(me.code, profile.name, it) }?.let { TalkaCrypto.sealApercu(me, nk, it) }
            } else {
                null
            }
        // Une boîte tournante (v4) : le relais ne sait ni qui écrit, ni à qui.
        if (peer.bx && d.isNotEmpty() && relay.sealed) {
            val key = TalkaV4.boitesKey(me, peer.code, peer.dh) ?: return Relay.Ack.Failed("crypto")
            val outer = TalkaCrypto.sealSender(me.code, dev, peer.code, peer.dh, sealed.body) ?: return Relay.Ack.Failed("crypto")
            val boite = TalkaV4.boite(key, peer.code, d, TalkaV4.day(relay.now()))
            return relay.sendBoite(id, boite, outer, live, wake, notice)
        }
        // L'expéditeur scellé : le relais ne sait pas qui écrit (une connexion
        // anonyme, et le jeton de remise du destinataire).
        if (peer.jeton.isNotEmpty() && relay.sealed) {
            val outer = TalkaCrypto.sealSender(me.code, dev, peer.code, peer.dh, sealed.body) ?: return Relay.Ack.Failed("crypto")
            val ack = relay.sendSealed(id, peer, d, outer, live, wake, notice)
            // Son jeton ne vaut plus : on l'oublie, et cet envoi part comme
            // avant. Sinon pas de repli : l'envoi réessaiera, toujours scellé.
            if (ack != Relay.Ack.Failed("jeton")) return ack
            updateContact(peer.code) { it.copy(jeton = "") }
        }
        return relay.send(id, item.to, d, sealed.body, live, wake, notice)
    }

    /**
     * La boîte d'envoi dans l'ordre : chaque enveloppe sans son contenu
     * (`payload` vide), et sa ligne. Une enveloppe n'est redécodée que si sa
     * ligne a changé : celles qui attendent un accusé (une photo pour un
     * contact hors ligne, pendant des jours) ne sont plus relues en entier à
     * chaque passage, c'est-à-dire à chaque message ou accusé qui part.
     */
    internal fun outbox(): List<OutRow> {
        val rows = store.rawRows("outbox")
        val kept = HashMap<String, Kept<OutItem>>(rows.size)
        for ((key, json) in rows) {
            val known = outHeads[key]
            if (known != null && known.json == json) {
                kept[key] = known
            } else {
                OutItem.head(json)?.let { kept[key] = Kept(json, it) }
            }
        }
        outHeads = kept
        return kept.values.map { OutRow(it.value, it.json) }.sortedBy { it.head.seq }
    }

    /** Envoie ce qui attend dans la boîte d'envoi. */
    suspend fun flush() {
        withContext(dispatcher) { flushNow() }
    }

    private suspend fun flushNow() {
        if (relay.state != Relay.State.online) return
        if (flushing) {
            flushAgain = true
            return
        }
        flushing = true
        try {
            do {
                flushAgain = false
                val items = outbox()
                val boot = relay.boot
                for ((head, json) in items) {
                    if (relay.state != Relay.State.online) return
                    var item = head
                    // Déjà accepté par le relais : on attend l'accusé de réception,
                    // sauf si le relais a redémarré depuis (sa boîte aux lettres,
                    // en mémoire, est perdue) : alors on renvoie.
                    if (item.boot.isNotEmpty()) {
                        if (!stillUnconfirmed(item)) {
                            store.remove("outbox", item.id)
                            continue
                        }
                        if (item.boot == boot || boot.isEmpty()) continue
                        // Le relais a redémarré : ce qui n'a pas été reçu repart
                        // (seulement vers les appareils qui ne l'ont pas).
                        val got = item.got
                        item = item.copy(sent = item.sent.filter { it in got })
                    }
                    // Elle part : son contenu, lu maintenant seulement.
                    val full = JSON.decode(OutItem, json) ?: continue
                    item = item.copy(payload = full.payload)
                    val before = item.sent.size
                    val (ack, sentItem) = sendNow(item, live = false)
                    item = sentItem
                    // Les accusés arrivés pendant l'envoi (v4 : appareil par appareil).
                    val got = ArrayList(item.got)
                    for (g in store.read("outbox", item.id, OutItem)?.got ?: emptyList()) if (g !in got) got.add(g)
                    item = item.copy(got = got)
                    // Les appareils déjà servis sont retenus, même si un autre attend.
                    if (ack != Relay.Ack.Ok && item.sent.size != before) store.write("outbox", item.id, item)
                    // Nos autres appareils ne comptent pas dans les coches.
                    val toOthers = item.to != me.code
                    val error = (ack as? Relay.Ack.Failed)?.error
                    when {
                        ack == Relay.Ack.Ok -> {
                            if (item.msgId.isNotEmpty() && boot.isNotEmpty()) {
                                item = item.copy(boot = boot)
                                if (stillUnconfirmed(item)) store.write("outbox", item.id, item) else store.remove("outbox", item.id)
                            } else {
                                store.remove("outbox", item.id)
                            }
                            if (item.msgId.isNotEmpty() && toOthers) markSent(item.chat, item.msgId, item.to)
                        }
                        error == "size" || error == "code" -> {
                            store.remove("outbox", item.id)
                            if (item.msgId.isNotEmpty() && toOthers) {
                                markFailed(item.chat, item.msgId)
                                if (error == "size") onTooLarge?.invoke()
                            }
                        }
                        error == "offline" || error == "timeout" -> return
                        // « unknown » : le destinataire n'a pas rouvert Talka depuis
                        // le redémarrage du relais ; l'enveloppe attend ses clés.
                        else -> {}
                    }
                }
            } while (flushAgain)
        } finally {
            flushing = false
        }
    }

    /**
     * Un message accepté par le relais attend-il encore l'accusé de réception
     * de ce destinataire ? Au-delà de 30 jours (la durée des boîtes aux
     * lettres), on n'insiste plus.
     */
    internal fun stillUnconfirmed(item: OutItem): Boolean {
        val m = store.message(item.chat, item.msgId) ?: return false
        if (m.deleted || nowMs() - m.at >= 30 * dayMs) return false
        // Pour nos autres appareils : chacun dit l'avoir reçu (`sync`/`got`).
        if (item.to == me.code) return siblings.any { it !in item.got }
        // v4 : chaque appareil du destinataire doit l'avoir reçu.
        val devs = targets(item.to).filter { it.isNotEmpty() }
        if (devs.isEmpty()) return item.to !in m.deliveredTo
        return devs.any { it !in item.got }
    }

    internal fun markSent(chat: String, id: String, to: String) {
        var m = store.message(chat, id) ?: return
        if (to !in m.sentTo) m = m.copy(sentTo = m.sentTo + to)
        if (m.tick != Message.Tick.pending && m.status == "pending") m = m.copy(status = "sent")
        store.writeMessage(m)
        bump(chat)
    }

    internal fun markFailed(chat: String, id: String) {
        val m = store.message(chat, id) ?: return
        store.writeMessage(m.copy(status = "failed"))
        bump(chat)
    }

    internal fun groupInfo(c: Chat): GroupInfo =
        GroupInfo(
            id = c.id.substring(2), name = c.name, members = c.members, admins = c.admins, avatar = c.avatar,
            v = maxOf(c.v, 1), desc = c.desc, adminsOnly = c.adminsOnly,
        )

    /** Ce qui accompagne chaque envoi : le groupe, la durée de vie des messages éphémères. */
    internal fun envelope(c: Chat, body: Body): Wire = Wire(body, grp = if (c.group) groupInfo(c) else null, ttl = if (c.timer > 0) c.timer else null)

    /** Envoie un message dans une discussion. */
    fun send(chatId: String, content: Content): Message? {
        val c = _chats[chatId] ?: return null
        val id = randomId(12)
        val at = relay.now()
        val to = recipients(c).filter { !blocked(it) }
        var m = Message(chatId, id, me.code, at, content).copy(status = if (to.isEmpty()) "sent" else "pending", to = to)
        if (c.timer > 0) m = m.copy(exp = at + c.timer.toDouble() * 1000)
        // Brocolis 🥦 : le texte part scellé ; l'appareil garde le clair.
        val (bro, sealed) = sealContent(c, content)
        m = m.copy(bro = bro)
        if (m.bro) rememberOriginal(m, sealed.text)
        // Un jeton collé (clavier Brocolis) s'affiche ouvert, s'il est pour vous.
        m = openMessage(m).second
        storeMessage(m)
        // Le nom choisi voyage avec le message : un membre de groupe qui ne
        // vous connaît pas voit « ~Nom » plutôt qu'un code.
        val wire = envelope(c, Body.Msg(id, at, profile.name, sealed))
        enqueueAll(to, wire, chat = chatId, msgId = id)
        // Nos autres appareils l'affichent aussi (v4) : gardé, comme pour un
        // destinataire, jusqu'à ce que chacun l'ait (et renvoyé si le relais
        // redémarre entre-temps).
        if (siblings.isNotEmpty()) {
            enqueue(me.code, Wire(Body.Sync("wire", chatId, wire)), chat = chatId, msgId = id)
        }
        if (!(_chats[chatId]?.draft?.isEmpty() ?: true)) updateChat(chatId) { it.copy(draft = "") }
        return m
    }

    internal fun broadcast(c: Chat, body: Body, live: Boolean = false) {
        val wire = envelope(c, body)
        val to = recipients(c).filter { !blocked(it) }
        if (live) {
            val payload = wire.json()
            for (code in to) enqueue(code, payload = payload, live = true)
        } else {
            enqueueAll(to, wire)
        }
        // Ce qui change la discussion (réaction, modification, suppression,
        // minuterie) : refait sur nos autres appareils (v4).
        if (body !is Body.Typing && body !is Body.Group && !live) sync("wire", c = c.id, w = wire)
    }

    fun react(chat: String, id: String, emoji: String) {
        val c = _chats[chat] ?: return
        val m = store.message(chat, id) ?: return
        val e = if (m.reactions[me.code] == emoji) "" else emoji
        val reactions = LinkedHashMap(m.reactions)
        if (e.isEmpty()) reactions.remove(me.code) else reactions[me.code] = e
        store.writeMessage(m.copy(reactions = reactions))
        bump(chat)
        broadcast(c, Body.React(id, e))
    }

    fun edit(chat: String, id: String, text: String) {
        val c = _chats[chat] ?: return
        var m = store.message(chat, id) ?: return
        if (m.from != me.code) return
        val sealed = sealText(c, text)
        m = m.copy(text = text, edited = true, bro = sealed != text)
        rememberOriginal(m, sealed)
        store.writeMessage(m)
        refreshLast(chat)
        bump(chat)
        broadcast(c, Body.Edit(id, sealed))
    }

    fun delete(chat: String, id: String, forEveryone: Boolean) {
        val c = _chats[chat] ?: return
        val m = store.message(chat, id) ?: return
        if (forEveryone && m.from == me.code) {
            store.writeMessage(m.copy(deleted = true, text = "", data = "", reactions = emptyMap(), starred = false))
            broadcast(c, Body.Delete(id))
        } else {
            store.removeMessage(chat, id)
            bury(chat, listOf(m.id to m.at))
            sync("forget", c = chat, d = JSONValue.Object(mapOf("ids" to JSONValue.Array(listOf(JSONValue.Str(m.id))))))
        }
        refreshLast(chat)
        bump(chat)
    }

    fun toggleStar(chat: String, id: String) {
        val m0 = store.message(chat, id) ?: return
        val m = m0.copy(starred = !m0.starred)
        store.writeMessage(m)
        bump(chat)
        sync("star", c = chat, d = JSONValue.Object(linkedMapOf("id" to JSONValue.Str(id), "on" to JSONValue.Bool(m.starred))))
    }

    /**
     * Épingle un message en haut de sa discussion ("" : retire l'épingle).
     * Pour soi : pas envoyé aux correspondants, seulement à nos autres
     * appareils (comme le navigateur).
     */
    fun setPin(chat: String, id: String) {
        updateChat(chat) { it.copy(pinMsg = id) }
    }

    /** Le message épinglé d'une discussion, s'il existe encore. */
    fun pinnedMessage(chat: String): Message? {
        val id = _chats[chat]?.pinMsg ?: return null
        if (id.isEmpty()) return null
        val m = store.message(chat, id) ?: return null
        if (m.deleted) return null
        m.exp?.let { if (it <= nowMs()) return null }
        return m
    }

    /** Les messages importants d'une discussion, les plus récents d'abord. */
    fun starred(chat: String): List<Message> =
        // Eux seuls sont lus : pas toute la discussion pour quelques étoiles.
        store.starredIds(chat)
            .mapNotNull { store.message(chat, it) }
            .filter { it.isStarred }
            .sortedWith { a, b -> compareAtKey(b.at, b.id, a.at, a.id) }

    /**
     * Ceux qui parlent de `query` (le texte, qui l'a écrit, la discussion ;
     * accents et majuscules ignorés), comme la recherche du navigateur.
     */
    fun starred(list: List<Message>, matching: String): List<Message> {
        val q = Mentions.fold(matching.trimSpaces())
        if (q.isEmpty()) return list
        return list.filter { m ->
            val title = _chats[m.chat]?.let { title(it) } ?: ""
            Mentions.fold("${readable(m)} ${displayName(m.from)} $title").contains(q)
        }
    }

    /**
     * Tous les messages importants, les plus récents d'abord. La liste est
     * gardée tant qu'aucun message n'a été écrit ni effacé (leur écran la
     * redemande à chaque frappe dans sa recherche) ; elle est refaite
     * discussion par discussion, de leurs seuls messages importants (voir
     * `Store.starredIds`), et non en relisant tous les messages de
     * l'appareil à chaque message qui arrive.
     */
    fun starred(): List<Message> {
        val generation = store.messagesGeneration
        starredKept?.let { if (it.generation == generation) return it.list }
        val list = store.chatsWithMessages().flatMap { starred(it) }.sortedWith { a, b -> compareAtKey(b.at, b.key, a.at, a.key) }
        starredKept = StarredKept(generation, list)
        return list
    }

    /**
     * Le libellé d'une mention (« @Léa ») : le nom que chacun s'est choisi,
     * le même sur tous les appareils (à défaut, celui qu'on lui a donné).
     * Comme `core::mention_label` du navigateur.
     */
    fun mentionLabel(code: String): String {
        if (code == me.code) return profile.name
        val c = _contacts[code] ?: return TalkaCode.pretty(code)
        if (c.profileName.isNotEmpty()) return c.profileName
        return c.name.ifEmpty { TalkaCode.pretty(code) }
    }

    /** Les membres d'un groupe à proposer pour « @… » : (code, libellé). */
    fun mentionCandidates(chat: Chat): List<Pair<String, String>> = chat.members.filter { it != me.code }.map { it to mentionLabel(it) }

    fun forward(m: Message, chats: List<String>) {
        // Une mention ne suit pas un message transféré.
        val content = m.content.copy(reply = null, fwd = true, openAt = null, mentions = emptyList())
        for (id in chats) send(id, content)
    }

    fun setTyping(chat: String, on: Boolean) {
        val c = _chats[chat] ?: return
        if (relay.state != Relay.State.online) return
        val t = nowMs()
        if (on && t - typingSent < 2500) return
        typingSent = if (on) t else 0.0
        broadcast(c, Body.Typing(on), live = true)
    }

    fun setTimer(chat: String, secs: Long) {
        updateChat(chat) { it.copy(timer = secs) }
        systemMessage(chat, timerText(me.code, secs))
        _chats[chat]?.let { broadcast(it, Body.Timer(secs)) }
    }

    internal fun timerText(code: String, secs: Long): String {
        val who = if (code == me.code) "Vous avez" else "${displayName(code)} a"
        if (secs == 0L) return "⏳ $who désactivé les messages éphémères."
        val label = if (secs >= 90L * 86400) "90 jours" else if (secs >= 7L * 86400) "7 jours" else "24 heures"
        return "⏳ $who activé les messages éphémères : $label."
    }

    internal fun systemMessage(chat: String, text: String, at: Double = nowMs()) {
        val m = Message(chat, randomId(12), "system", at, Content(kind = "system", text = text)).copy(kind = "system")
        storeMessage(m)
    }

    // ----- Groupes -----

    fun createGroup(name: String, members: List<String>, avatar: String): Chat {
        val c = Chat(
            id = "g:${randomId(12)}", group = true, name = name.trimSpaces().prefixChars(60), avatar = avatar,
            members = listOf(me.code) + members, admins = listOf(me.code), createdBy = me.code, v = 1,
        )
        saveChat(c)
        systemMessage(c.id, "Vous avez créé le groupe « ${c.name} ».")
        broadcast(c, Body.Group("create", ""))
        syncGroup(c, "Vous avez créé le groupe « ${c.name} ».")
        return c
    }

    fun updateGroup(id: String, note: String, f: (Chat) -> Chat) {
        val before = _chats[id] ?: return
        val next = f(before).let { it.copy(v = maxOf(before.v, 1) + 1) }
        saveChat(next)
        if (note.isNotEmpty()) systemMessage(id, note)
        broadcast(next, Body.Group("update", note))
        syncGroup(next, note)
        // Les membres retirés l'apprennent aussi.
        for (code in before.members) {
            if (code !in next.members && code != me.code) enqueue(code, Wire(Body.Group("update", note), grp = groupInfo(next)))
        }
    }

    fun leaveGroup(id: String) {
        var c = _chats[id] ?: return
        val members = c.members.filter { it != me.code }
        var admins = c.admins.filter { it != me.code }
        if (admins.isEmpty() && members.isNotEmpty()) admins = listOf(members.first())
        c = c.copy(members = members, admins = admins, v = maxOf(c.v, 1) + 1, left = true)
        enqueueAll(c.members, Wire(Body.Group("leave", ""), grp = groupInfo(c)))
        saveChat(c)
        systemMessage(id, "Vous avez quitté le groupe.")
        syncGroup(c, "Vous avez quitté le groupe.")
    }

    // ----- Réception -----

    internal fun queueReceipt(to: String, chat: String, ids: List<String>, st: String) {
        receipts.getOrPut("$to|$st|$chat") { LinkedHashSet() }.addAll(ids)
        receiptTask?.cancel()
        receiptTask = scope.launch {
            delay(400)
            val batch = LinkedHashMap(receipts)
            receipts.clear()
            for ((key, set) in batch) {
                val parts = key.split("|", limit = 3)
                if (parts.size != 3) continue
                val c = parts[2]
                val body = Body.Receipt(parts[1], set.toList(), dev)
                enqueue(parts[0], Wire(body, g = if (isGroup(c)) c.substring(2) else null))
            }
        }
    }

    /**
     * Toutes les discussions non lues (archivées comprises) marquées comme
     * lues, comme dans le navigateur. Renvoie leur nombre.
     */
    fun markAllRead(): Int {
        val ids = _chats.values.filter { it.unread > 0 || it.markedUnread }.map { it.id }
        // Une seule transaction pour toutes les discussions.
        store.transaction {
            for (id in ids) markRead(id)
        }
        return ids.size
    }

    /** Marque une discussion comme lue (accusés de lecture bleus). */
    fun markRead(chat: String) {
        val c = _chats[chat] ?: return
        val bySender = LinkedHashMap<String, ArrayList<String>>()
        // Des centaines de messages non lus s'écrivent en une transaction,
        // pas une par message (ouvrir une discussion très en retard, ou
        // tout marquer comme lu, bloquait l'écran).
        store.transaction {
            for (m in store.messagesOf(chat, 300)) {
                if (m.from == me.code || m.from == "system" || m.readByMe) continue
                store.writeMessage(m.copy(readByMe = true))
                bySender.getOrPut(m.from) { ArrayList() }.add(m.id)
            }
        }
        val fresh = bySender.isNotEmpty()
        if (settings.readReceipts) {
            for ((code, ids) in bySender) queueReceipt(code, chat, ids, "read")
        }
        if (c.unread > 0 || c.markedUnread || c.mentioned) {
            updateChat(chat) { it.copy(unread = 0, markedUnread = false, mentioned = false) }
        }
        // Lu ici : lu sur nos autres appareils (sans nouvel accusé).
        if (fresh) sync("read", c = chat, d = JSONValue.Object(mapOf("at" to JSONValue.Number(relay.now()))))
    }

    /** Lu sur un autre de nos appareils : jusqu'à `at`, sans accusé. */
    internal fun readHere(chat: String, at: Double) {
        val c = _chats[chat] ?: return
        store.transaction {
            for (m in store.messagesOf(chat, 300)) {
                if (m.from == me.code || m.from == "system" || m.readByMe || m.at > at + 60_000) continue
                store.writeMessage(m.copy(readByMe = true))
            }
        }
        if (c.unread > 0 || c.mentioned) saveChat(c.copy(unread = 0, mentioned = false))
        bump(chat)
    }

    internal fun onEnvelope(env: JsonObject) {
        val n = env["n"].asInt()
        spawn {
            receive(env)
            if (n != null) relay.got(listOf(n))
        }
    }

    /** Une enveloppe arrive d'une boîte tournante (v4) : elle doit venir du contact à qui est cette boîte. */
    internal fun onBoxEnvelope(env: JsonObject) {
        val n = env["n"].asInt()
        val boite = env["boite"].asString() ?: ""
        spawn {
            // Une boîte de couverture : rien à lire, seulement à oublier.
            val contact = boxMap[boite]
            if (contact != null && contact.isNotEmpty()) receive(env, contact)
            if (n != null) relay.boxes.got(listOf(n))
        }
    }

    /** `boite` : le contact dont c'est la boîte tournante (l'enveloppe doit venir de lui). */
    internal suspend fun receive(env: JsonObject, boite: String? = null) {
        // Scellé : l'expéditeur est dans l'enveloppe, que nous seuls ouvrons ;
        // l'enveloppe intérieure le prouve ensuite (sa clé de paire).
        var from = env["from"].asString() ?: ""
        var fd = env["fd"].asString()?.takeIf { TalkaV4.isDev(it) } ?: ""
        var body = env["body"].asString() ?: ""
        if (env["sealed"].asBool() == true) {
            val opened = TalkaCrypto.openSealedFrom(me, body) ?: return
            from = opened.from
            fd = opened.fd
            body = opened.body
        }
        if (from.isEmpty() || blocked(from)) return
        if (boite != null && boite != from) return
        // De nous-mêmes : seulement d'un autre de nos appareils.
        val mine = from == me.code
        if (mine && (fd.isEmpty() || fd == dev || !devices.has(fd))) return
        val peer: Contact = if (mine) selfContact else (fetchKeys(from) ?: return)
        // Une pièce jointe chiffrée à part : `<enveloppe>|<pièce>`.
        var blob: ByteArray? = null
        val bar = body.indexOf('|')
        if (bar >= 0) {
            blob = unb64(body.substring(bar + 1))
            body = body.substring(0, bar)
        }
        val addr = TalkaV4.address(from, fd)
        loadV3(addr)
        // Une session ouverte avant de connaître ses appareils (à son code).
        loadV3(from)
        var repair = false
        // Ouvert en v3 : son appareil a publié son paquet.
        var openedV3 = false
        // Le message, et l'état de session qui l'a ouvert : une seule
        // transaction SQLite (le relais ne l'oublie qu'après, par `got`).
        val device: String? = store.transaction tx@{
            val opened = v3?.openFrom(me, addr, peer.code, peer.dh, body, V3.secs(nowMs()))
                ?: TalkaCrypto.open(me, peer.code, peer.dh, body)?.let { V3.Opened.Legacy(it) }
                ?: V3.Opened.Failed(V3.Failure.Envelope)
            val device = saveV3Sessions()
            when (opened) {
                is V3.Opened.Legacy, is V3.Opened.V3 -> {
                    val legacy = opened is V3.Opened.Legacy
                    val plain = if (opened is V3.Opened.Legacy) opened.wire else (opened as V3.Opened.V3).wire
                    openedV3 = !legacy
                    // De nos appareils : seulement de la synchronisation, en v3.
                    if (mine) {
                        if (!legacy) {
                            val s = Wire.parse(Attachment.join(plain, blob))?.body as? Body.Sync
                            if (s != null) handleSync(fd, s.op, s.c, s.w, s.d)
                        }
                        return@tx device
                    }
                    // Prouvé : ses clés sont gardées (s'il est nouveau), et son
                    // passage en v3.
                    val known = _contacts[from]
                    if (known == null || known.sign.isEmpty() || (!legacy && !known.v3)) {
                        var c = (known ?: peer).copy(sign = peer.sign, dh = peer.dh)
                        if (!legacy) c = c.copy(v3 = true)
                        saveContact(c)
                    }
                    val live = env["live"].asBool() ?: false
                    // La pièce jointe chiffrée à part (seulement en v3).
                    val full = if (legacy) plain else Attachment.join(plain, blob)
                    val wire = Wire.parse(full) ?: return@tx device
                    // L'ancien format n'a pas de protection contre le rejeu : refusé
                    // d'un contact dont la version chiffre tout en v3 ; d'un contact
                    // passé en v3, il ne change plus d'état ; un message de plus de
                    // 31 jours est refusé (c'est un rejeu). Hors signaux en direct.
                    var refuse = false
                    if (legacy && !live) {
                        if (known?.pj == true) {
                            refuse = true
                        } else if (known?.v3 == true && wire.body.changesState) {
                            refuse = true
                        }
                        val b = wire.body
                        if (b is Body.Msg && nowMs() - b.at > tombWindow) refuse = true
                    }
                    if (refuse) {
                        v3Log("ancien format refusé de $from")
                    } else {
                        handle(from, wire, env["ts"].asDouble() ?: nowMs(), live)
                    }
                }
                // Déjà lu (le relais l'a renvoyé), ou pas pour nous.
                is V3.Opened.Replay -> {}
                is V3.Opened.Failed ->
                    when (val f = opened.failure) {
                        is V3.Failure.Envelope -> {}
                        is V3.Failure.V3 -> {
                            v3Log("v3 : message de $addr illisible (${f.error})")
                            // Seulement une session perdue : un vieux message rejoué ne
                            // la fait jamais tomber.
                            repair = V3.needsRepair(f.error)
                        }
                    }
            }
            device
        }
        // L'appareil (sa mémoire des premiers messages) après le message : un
        // premier message rangé sans elle se relirait comme un doublon.
        if (device != null) saveV3Device(device)
        if (repair) repairV3(from, fd)
        // Un appareil dont le relais n'avait pas de paquet (un appareil
        // qu'on vient de lier) : il en a un maintenant, et une session est
        // peut-être ouverte. Ce qui l'attendait part.
        val cached = bundleCache[addr]
        if (openedV3 && cached != null && cached.bundle == null) {
            bundleCache.remove(addr)
            spawn { flush() }
        }
    }

    /**
     * La discussion d'un groupe, créée ou mise à jour par `grp` ; `second` :
     * cette description a été appliquée (un groupe ne change que par ses
     * admins, voir `Chat.accepts`).
     */
    internal fun groupChat(from: String, grp: GroupInfo, at: Double): Pair<Chat?, Boolean> {
        val id = "g:${grp.id}"
        val existing = _chats[id]
        if (existing == null) {
            // Un nouveau groupe : créé par un de ses admins, qui nous y met.
            if (me.code !in grp.members || from !in grp.admins || from !in grp.members) return null to false
            // Rejoint à l'heure de l'annonce : nos premiers messages viennent après.
            val c = Chat(
                id = id, group = true, name = grp.name, avatar = grp.avatar, members = grp.members, admins = grp.admins,
                desc = groupDesc(grp.desc ?: ""), adminsOnly = grp.adminsOnly ?: false, createdBy = from, v = grp.v,
            ).copy(createdAt = at)
            saveChat(c)
            systemMessage(id, "${displayName(from)} vous a ajouté au groupe « ${grp.name} ».", at)
            relay.watch(grp.members.filter { it != me.code })
            return c to true
        }
        if (!existing.accepts(from, grp)) return existing to false
        val wasIn = me.code in existing.members
        var c = existing.copy(name = grp.name, avatar = grp.avatar, members = grp.members, admins = grp.admins, v = grp.v)
        grp.desc?.let { c = c.copy(desc = groupDesc(it)) }
        grp.adminsOnly?.let { c = c.copy(adminsOnly = it) }
        if (me.code in grp.members) c = c.copy(left = false) else if (wasIn) c = c.copy(left = true)
        saveChat(c)
        return c to true
    }

    internal fun handle(from: String, wire: Wire, ts: Double, live: Boolean) {
        when (val b = wire.body) {
            is Body.Profile -> {
                var c = _contacts[from] ?: Contact(code = from)
                // Sa version chiffre tout en v3 (une fois su, ça ne se perd pas).
                if (b.pj && !c.pj) {
                    c = c.copy(pj = true)
                    saveContact(c)
                }
                // Sa clé de notification (une clé P-256 publique, ou rien).
                if (b.nk != c.nk && (b.nk.isEmpty() || TalkaCrypto.validNk(b.nk))) {
                    c = c.copy(nk = b.nk)
                    saveContact(c)
                }
                // Son jeton de remise, s'il est bien signé par sa clé.
                if (b.jeton != c.jeton && b.jeton.isNotEmpty() && TalkaCrypto.verifyJeton(from, c.sign, c.dh, b.jeton)) {
                    c = c.copy(jeton = b.jeton)
                    saveContact(c)
                }
                // Ses appareils (v4) : la liste la plus récente, bien formée.
                if (b.dv > c.dv && b.devs.size <= maxDevices &&
                    b.devs.all { TalkaV4.isDev(it.d) && (it.nk.isEmpty() || TalkaCrypto.validNk(it.nk)) }
                ) {
                    c = c.copy(devs = b.devs, dv = b.dv)
                    saveContact(c)
                    // Un nouvel appareil : on vérifie son paquet au prochain envoi.
                    bundleCache.keys.removeAll { it.startsWith("$from/") }
                    scheduleBoxRefresh()
                }
                // Il lit ses boîtes tournantes (v4).
                if (b.bx != c.bx) {
                    c = c.copy(bx = b.bx)
                    saveContact(c)
                }
                if (b.profile.v >= c.profileV) {
                    val p = b.profile
                    c = c.copy(
                        profileName = p.name.prefixChars(40), about = p.about.prefixChars(140),
                        avatar = if (p.avatar.charCount() <= 60_000) p.avatar else "", profileV = p.v,
                    )
                    saveContact(c)
                }
                return
            }
            is Body.Typing -> {
                val chat = wire.groupId?.let { "g:$it" } ?: from
                if (_chats[chat] == null) return
                val key = "$chat|$from"
                typingTimers.remove(key)?.cancel()
                if (b.on) {
                    _typing.getOrPut(chat) { LinkedHashSet() }.add(from)
                    changed()
                    typingTimers[key] = scope.launch {
                        delay(6_000)
                        _typing[chat]?.remove(from)
                        changed()
                    }
                } else {
                    _typing[chat]?.remove(from)
                    changed()
                }
                return
            }
            is Body.Call -> {
                // Une offre d'appel déjà vue (un rejeu) ne fait pas sonner. À
                // plusieurs, chacun invite avec le même identifiant d'appel :
                // une offre par appel et par expéditeur.
                if (b.op == "offer" && !firstOffer("${b.callId}:$from")) return
                onCallSignal?.invoke(from, b, live)
                return
            }
            is Body.Status -> {
                if (_contacts[from]?.saved != true || ts - b.at > dayMs) return
                val st = StatusItem(from, b.id, b.kind, b.text.prefixChars(700), b.bg, b.data, b.at)
                store.write("statuses", st.key, st)
                statusRevision += 1
                return
            }
            is Body.StatusSeen -> {
                val key = "${me.code}|${b.id}"
                store.read("statuses", key, StatusItem)?.let { st ->
                    store.write("statuses", key, st.copy(viewers = st.viewers + (from to ts)))
                    statusRevision += 1
                }
                return
            }
            is Body.StatusDelete -> {
                store.remove("statuses", "$from|${b.id}")
                statusRevision += 1
                return
            }
            is Body.Unknown -> return
            else -> {}
        }

        // Le reste appartient à une discussion.
        var chat: Chat?
        var applied = false
        val grp = wire.grp
        val g = wire.g
        if (grp != null) {
            // L'annonce « vous a ajouté » se range juste avant le premier message.
            var at = ts
            val b = wire.body
            if (b is Body.Msg) at = minOf(b.at, ts) - 1
            val r = groupChat(from, grp, at)
            chat = r.first
            applied = r.second
        } else if (g != null) {
            chat = _chats["g:$g"]
        } else {
            // Un message de groupe hors d'un groupe : ignoré.
            if (wire.body is Body.Group) return
            chat = ensureDirectChat(from)
        }
        var c = chat ?: return
        // Dans un groupe, seuls ses membres parlent ; qui vient de le quitter
        // (sa description appliquée à l'instant) l'annonce encore.
        if (c.group && from !in c.members) {
            val b = wire.body
            if (!applied || b !is Body.Group || b.op != "leave") return
        }

        when (val b = wire.body) {
            is Body.Msg -> {
                val id = b.id
                // Déjà reçu, ou effacé ici depuis (un rejeu ne le fait pas revenir).
                if (store.message(c.id, id) != null || buried(c.id, id)) return
                val kinds = listOf("text", "image", "voice", "file", "contact", "brokey")
                var safe = b.content
                // Une clé Brocolis partagée n'en est une que si elle est valide.
                if (safe.kind !in kinds || (safe.kind == "brokey" && Brocolis.key(safe.data) == null)) safe = safe.copy(kind = "text")
                safe = safe.copy(text = safe.text.prefixChars(65_536))
                // Une capsule s'ouvre au plus tard dans dix ans.
                var mAt = minOf(b.at, ts + 60_000)
                // Dans un groupe où l'on nous a ajoutés, rien ne précède l'annonce
                // « X vous a ajouté » (datée par le relais, le message par son
                // auteur : une annonce partie après le message passait derrière).
                // Quelques minutes au plus : un historique plus ancien garde son heure.
                if (c.group && c.createdBy != me.code && mAt <= c.createdAt && c.createdAt - mAt < 300_000) mAt = c.createdAt + 1
                safe.openAt?.let { open -> safe = safe.copy(openAt = if (open > mAt) minOf(open, mAt + capsuleMaxMs) else null) }
                // Les mentions : seulement des membres du groupe (50 au plus).
                safe = safe.copy(mentions = if (c.group) safe.mentions.filter { it in c.members }.take(50) else emptyList())
                var m = Message(c.id, id, from, mAt, safe)
                wire.ttl?.let { ttl -> m = m.copy(exp = m.at + ttl.toDouble() * 1000) }
                // La citation : celle de notre copie du message cité, jamais ce que
                // l'expéditeur prétend (« Vous : je te dois 500 € »).
                m.reply?.let { r ->
                    val orig = store.message(c.id, r.id)
                    m = m.copy(
                        reply = if (orig != null) {
                            r.copy(from = orig.from, text = readable(orig).prefixChars(140))
                        } else {
                            r.copy(from = "", text = r.text.prefixChars(140))
                        },
                    )
                }
                m = openMessage(m).second
                _contacts[from]?.let { sender ->
                    if (sender.profileName.isEmpty() && b.pn.isNotEmpty()) saveContact(sender.copy(profileName = b.pn.prefixChars(40)))
                }
                val active = activeChat == c.id && foreground
                if (active) {
                    m = m.copy(readByMe = true)
                } else {
                    c = c.copy(unread = c.unread + 1)
                    // Mentionné : « @ » dans la liste, jusqu'à la lecture.
                    if (me.code in m.mentions) c = c.copy(mentioned = true)
                    // En mémoire seulement : `storeMessage`, juste après, écrit la
                    // discussion (avec son dernier message). Une écriture au lieu
                    // de deux, et sa ligne porte la photo du groupe.
                    _chats[c.id] = c
                }
                storeMessage(m)
                queueReceipt(from, c.id, listOf(id), "delivered")
                if (active && settings.readReceipts) queueReceipt(from, c.id, listOf(id), "read")
                // Lu ici, sous nos yeux : lu sur nos autres appareils aussi.
                if (active) sync("read", c = c.id, d = JSONValue.Object(mapOf("at" to JSONValue.Number(m.at))))
                // Votre profil, pour qui ne l'a pas encore.
                val sender = _contacts[from]
                if (sender != null && !sender.blocked && (sender.sentProfileV < profile.v || (!sender.jetonPerso && relay.sealed))) {
                    var s = sender.copy(sentProfileV = profile.v)
                    if (relay.sealed) s = s.copy(jetonSent = true, jetonPerso = true)
                    saveContact(s)
                    enqueue(from, Wire(profileBody(from)))
                }
                _typing[c.id]?.remove(from)
                onIncoming?.invoke(_chats[c.id] ?: c, m, active)
            }
            is Body.Receipt -> {
                var changedAny = false
                // Un accusé pour beaucoup de messages (quelqu'un rouvre Talka après
                // une absence) porte sur les derniers : la fin de la discussion est
                // lue d'un coup, plutôt que chaque message un par un (ce qui
                // finissait par charger toute la discussion), et tout est écrit en
                // une transaction.
                val near = HashMap<String, Message>()
                if (b.ids.size > 8) {
                    for (m in store.messagesOf(c.id, minOf(maxOf(b.ids.size * 2, 64), 600))) near[m.id] = m
                }
                store.transaction {
                    for (id in b.ids) {
                        var m = near[id] ?: store.message(c.id, id) ?: continue
                        if (m.from != me.code) continue
                        val before = m.deliveredTo.size to m.readBy.size
                        if (from !in m.deliveredTo) m = m.copy(deliveredTo = m.deliveredTo + from)
                        if (b.st == "read" && from !in m.readBy) m = m.copy(readBy = m.readBy + from)
                        // L'accusé d'un second appareil du même contact (ou un accusé
                        // rejoué) ne change rien au message : il n'est pas réécrit
                        // (photo comprise), ni l'écran redessiné.
                        if ((m.deliveredTo.size to m.readBy.size) != before) {
                            store.writeMessage(m)
                            if (near[id] != null) near[id] = m
                            changedAny = true
                        }
                        // Bien arrivé : plus besoin de le garder pour un renvoi (v4 :
                        // quand chacun de ses appareils l'a).
                        gotBy("$id:$from", b.dev)
                    }
                }
                if (changedAny) bump(c.id)
            }
            is Body.React -> {
                val m = store.message(c.id, b.id) ?: return
                val reactions = LinkedHashMap(m.reactions)
                if (b.emoji.isEmpty()) reactions.remove(from) else reactions[from] = b.emoji.prefixChars(8)
                store.writeMessage(m.copy(reactions = reactions))
                bump(c.id)
                if (m.from == me.code && b.emoji.isNotEmpty()) onReaction?.invoke(c, from, b.emoji)
            }
            is Body.Edit -> {
                val m = store.message(c.id, b.id) ?: return
                if (m.from != from || m.deleted) return
                val text = b.text.prefixChars(65_536)
                val opened = openBrocolis(text, c.id)
                if (opened.opened > 0) rememberOriginal(m, text)
                store.writeMessage(m.copy(text = opened.text, bro = opened.opened > 0, edited = true))
                refreshLast(c.id)
                bump(c.id)
            }
            is Body.Delete -> {
                val m = store.message(c.id, b.id) ?: return
                if (m.from != from) return
                store.writeMessage(m.copy(deleted = true, text = "", data = "", reactions = emptyMap(), starred = false))
                refreshLast(c.id)
                bump(c.id)
            }
            is Body.Timer -> {
                updateChat(c.id) { it.copy(timer = b.secs) }
                systemMessage(c.id, timerText(from, b.secs), ts)
            }
            is Body.Group -> {
                if (b.op == "leave") {
                    systemMessage(c.id, "${displayName(from)} a quitté le groupe.", ts)
                } else if (b.op != "create" && applied && c.group && b.note.startsWith("Vous avez ")) {
                    // Seulement les annonces que Talka écrit, d'un admin dont la
                    // mise à jour vient d'être appliquée : pas de faux message système.
                    systemMessage(c.id, "${displayName(from)} a " + b.note.substring("Vous avez ".length).prefixChars(200), ts)
                }
            }
            else -> {}
        }
    }

    // ----- Statuts -----

    fun postStatus(kind: String, text: String, bg: String, data: String) {
        val id = randomId(10)
        val at = relay.now()
        val st = StatusItem(me.code, id, kind, text, bg, data, at, seen = true)
        store.write("statuses", st.key, st)
        statusRevision += 1
        sync("status", d = jsonValue(st))
        enqueueAll(
            _contacts.values.filter { it.saved && !it.blocked }.map { it.code },
            Wire(Body.Status(id, kind, text, bg, data, at)),
        )
    }

    fun deleteStatus(id: String) {
        store.remove("statuses", "${me.code}|$id")
        statusRevision += 1
        sync("status", d = JSONValue.Object(mapOf("deleted" to JSONValue.Str(id))))
        enqueueAll(_contacts.values.filter { it.saved && !it.blocked }.map { it.code }, Wire(Body.StatusDelete(id)))
    }

    /**
     * Les actus de moins de 24 heures, de la plus ancienne à la plus
     * récente. Une actu (souvent une photo) n'est redécodée que si sa ligne
     * a changé : leur écran les redemande à chaque mise à jour.
     */
    fun statuses(): List<StatusItem> {
        val t = nowMs()
        val kept = HashMap<String, Kept<StatusItem>>()
        val all = ArrayList<StatusItem>()
        for ((key, json) in store.rawRows("statuses")) {
            val known = statusKept[key]
            if (known != null && known.json == json) {
                kept[key] = known
                all.add(known.value)
            } else {
                JSON.decode(StatusItem, json)?.let {
                    kept[key] = Kept(json, it)
                    all.add(it)
                }
            }
        }
        statusKept = kept
        return all.filter { it.at + dayMs > t }.sortedBy { it.at }
    }

    /**
     * L'auteur, l'identifiant et la date de chaque actu gardée, lus sans
     * décoder les photos, et une seule fois tant que la ligne ne change pas :
     * le ménage repasse chaque minute.
     */
    internal fun statusDates(): List<StatusItem.Head> {
        val kept = HashMap<String, Kept<StatusItem.Head>>()
        for ((key, json) in store.rawRows("statuses")) {
            val known = statusHeads[key]
            if (known != null && known.json == json) {
                kept[key] = known
            } else {
                JSON.decode(StatusItem.Head, json)?.let { kept[key] = Kept(json, it) }
            }
        }
        statusHeads = kept
        return kept.values.map { it.value }
    }

    fun markStatusSeen(st: StatusItem) {
        if (st.seen || st.owner == me.code) return
        val s = st.copy(seen = true)
        store.write("statuses", s.key, s)
        statusRevision += 1
        if (settings.readReceipts) enqueue(st.owner, Wire(Body.StatusSeen(st.id)))
    }

    // ----- Appels -----

    fun sendCallSignal(code: String, body: Body) {
        enqueue(code, Wire(body), live = true)
    }

    /** Un appel entrant pris ou refusé ici : nos autres appareils cessent de sonner (v4). */
    fun callTaken(id: String) {
        sync("call", d = JSONValue.Object(mapOf("id" to JSONValue.Str(id))))
    }

    fun logCall(entry: CallEntry) {
        store.write("calls", entry.id, entry)
        callRevision += 1
    }

    /**
     * Le journal d'appels, le plus récent d'abord. L'onglet le redemande à
     * chaque affichage : un appel déjà décodé n'est pas redécodé tant que
     * sa ligne n'a pas changé (comme les actus).
     */
    fun callLog(): List<CallEntry> {
        val kept = HashMap<String, Kept<CallEntry>>()
        val out = ArrayList<CallEntry>()
        for ((key, json) in store.rawRows("calls")) {
            val known = callsKept[key]
            if (known != null && known.json == json) {
                kept[key] = known
                out.add(known.value)
            } else {
                JSON.decode(CallEntry, json)?.let {
                    kept[key] = Kept(json, it)
                    out.add(it)
                }
            }
        }
        callsKept = kept
        return out.sortedByDescending { it.at }
    }

    fun clearCalls() {
        store.clear("calls")
        callRevision += 1
    }

    // ----- Sauvegarde -----

    /** Le fichier de sauvegarde (JSON) : le même que celui du navigateur. */
    fun exportBackup(password: String): ByteArray? {
        val dump = LinkedHashMap<String, JsonElement>(store.dump())
        // Les clés Brocolis vivent dans la garde du système : la sauvegarde
        // (chiffrée par son mot de passe) les emporte, pour un autre appareil.
        val text = BrocolisKeyList.encode(brocolisKeys)
        val withKeys = dumpRows(dump["kv"]).filter { it.rowKey() != "brocolis" && it.rowKey() != "brocolis_sealed" } +
            listOf(JsonArray(listOf(JsonPrimitive("brocolis"), JsonPrimitive(text))))
        // Les sessions v3 n'y vont pas : restaurées plus tard, en retard sur
        // celles du contact, elles reprendraient des clés de message déjà
        // servies. L'appareil v3 y va, comme l'identité (en clair : il vit
        // dans la garde du système) ; après une restauration, chaque contact
        // ouvre (ou reçoit) une session neuve.
        val kv = ArrayList(
            withKeys.filter {
                val key = it.rowKey() ?: ""
                !key.startsWith(V3.sessionPrefix) && key != V3.deviceKey && key != Pq6.keysKey && key != BrocolisPsk.kv
            },
        )
        val device = try {
            loadV3Device()
        } catch (e: Exception) {
            null
        }
        if (device != null) kv.add(JsonArray(listOf(JsonPrimitive(V3.deviceKey), JsonPrimitive(device))))
        dump["kv"] = JsonArray(kv)
        val payload = jsonOf("app" to "talka", "at" to nowMs(), "data" to JsonObject(dump))
        val backup = TalkaCrypto.encryptBackup(password, JSON.write(payload)) ?: return null
        return JSON.encode(backup).toByteArray()
    }

    fun wipeEverything() {
        stop()
        wipe(store)
    }

    companion object {
        /** Une clé partagée, pour une version qui ne sait pas l'ajouter. */
        internal const val sharedKeyFallback = "🥦 Clé Brocolis partagée : mettez Talka à jour pour l'ajouter."

        const val tombWindow: Double = 31 * dayMs

        /** Jusqu'à combien de messages trouvés sont lus un par un. */
        internal const val searchSingles = 80

        /** Le nom d'un appareil que l'on vient de lier, jusqu'à ce qu'il donne le sien. */
        const val newDeviceName = "Nouvel appareil"

        /**
         * Le nom de cet appareil dans la liste des appareils du compte (v4) :
         * l'application le donne (« Pixel 9 », « Galaxy Tab »).
         */
        @Volatile
        var deviceName = "Android"

        /** Boîtes de couverture par jour, au moins ; le nombre de boîtes suivies est arrondi au multiple de `coverRound` supérieur. */
        internal const val coverMin = 4
        internal const val coverRound = 64

        /** Une identité est-elle déjà gardée ? */
        fun hasAccount(store: Store): Boolean = store.read("kv", "identity", Identity)?.isValid ?: false

        /** Crée l'identité et le profil. */
        fun createAccount(store: Store, name: String, avatar: String) {
            store.write("kv", "identity", Identity.create())
            store.write("kv", "profile", Profile(name.trimSpaces().prefixChars(40), "Disponible sur Talka", avatar, 1))
            store.save()
        }

        internal fun loadBrocolis(store: Store): List<BrocolisKey> {
            if (BrocolisVault.isAvailable) {
                // Une sauvegarde restaurée apporte ses clés dans la base : elles
                // passent à la garde du système.
                store.read("kv", "brocolis", BrocolisKeyList)?.let { restored ->
                    if (BrocolisVault.write(restored)) store.remove("kv", "brocolis")
                    return restored
                }
                return BrocolisVault.read() ?: emptyList()
            }
            return store.read("kv", "brocolis", BrocolisKeyList) ?: emptyList()
        }

        /** Ce que montre la liste des discussions (et les notifications) d'un message. */
        fun preview(m: Message): String {
            if (m.deleted) return "🚫 Message supprimé"
            // Ni l'aperçu de la liste, ni la notification ne trahissent une capsule.
            if (m.isCapsule) return "⏳ Capsule temporelle"
            return when (m.kind) {
                "image" -> "📷 ${if (m.text.isEmpty()) "Photo" else Brocolis.masked(m.text)}"
                "voice" -> "🎙️ Message vocal (${formatDuration(m.dur)})"
                "file" -> "📄 ${m.name.ifEmpty { "Fichier" }}"
                "contact" -> "👤 ${m.name.ifEmpty { "Contact" }}"
                "brokey" -> "🥦 Clé Brocolis « ${m.name} »"
                else -> Brocolis.masked(m.text)
            }
        }

        /**
         * L'aperçu d'une notification pour le destinataire, en JSON : qui écrit
         * (`n`, notre nom choisi), le groupe (`g`), quoi (`t`, comme la liste
         * des discussions) et la discussion (`c`, vue de chez lui). Seulement
         * pour les messages écrits ; comme `Wire::apercu` du navigateur.
         */
        fun apercu(me: String, name: String, wire: Wire): String? {
            val body = wire.body as? Body.Msg ?: return null
            val m = Message("", "", me, 0.0, body.content)
            // `.whitespaces` : les espaces et les tabulations, pas les retours à la ligne.
            val trimmed = name.trimSpaces()
            val obj = sortedMapOf(
                "n" to if (trimmed.isEmpty()) TalkaCode.pretty(me) else trimmed.prefixChars(40),
                "t" to preview(m).prefixChars(120),
                "c" to (wire.groupId?.let { "g:$it" } ?: me),
            )
            wire.grp?.takeIf { it.name.isNotEmpty() }?.let { obj["g"] = it.name.prefixChars(40) }
            // Les clés dans l'ordre, comme `JSONSerialization` avec `.sortedKeys`.
            return JSON.write(JsonObject(obj.mapValues { JsonPrimitive(it.value) }))
        }

        /** Restaure une sauvegarde (du navigateur, d'un iPhone ou d'Android) dans `store`. */
        fun importBackup(store: Store, password: String, file: ByteArray) {
            val backup = utf8(file)?.let { JSON.decode(Backup, it) } ?: throw TalkaCrypto.BackupError.NotBackup()
            val plain = TalkaCrypto.decryptBackup(password, backup)
            val json = JSON.parse(plain) as? JsonObject
            val data = json?.get("data") as? JsonObject
            if (json == null || json["app"].asString() != "talka" || data == null) throw TalkaCrypto.BackupError.NotBackup()
            store.restore(data)
            replaceBackedUpDevice(store)
        }

        /**
         * Une sauvegarde restaurée remplace l'appareil d'où elle vient : cet
         * appareil prend un identifiant neuf (deux appareils ne partagent jamais
         * le leur), et l'ancien rejoint les appareils retirés ; s'il sert encore,
         * il efface tout à sa prochaine connexion. Comme le navigateur.
         */
        internal fun replaceBackedUpDevice(store: Store) {
            val old = store.read("kv", "dev", JsonText) ?: ""
            store.writeRaw("kv", "dev", JSON.encode(TalkaV4.newDev()))
            if (TalkaV4.isDev(old)) {
                val list = store.read("kv", "appareils", Devices)
                if (list != null && list.has(old)) store.write("kv", "appareils", list.remove(old))
            }
        }

        /**
         * Efface tout, même une base qui n'a pas pu s'ouvrir (Talka verrouillé,
         * code oublié ou 10 codes faux) : messages, clés v3, clés Brocolis.
         */
        fun wipe(store: Store) {
            store.wipe()
            BrocolisVault.clear()
            V3Vault.clear()
        }
    }
}

/**
 * Le vocal qui enchaîne quand `id` se termine (comme WhatsApp) : le message
 * juste après, s'il est lui aussi un vocal (supprimé ou expiré : non).
 */
fun nextVoice(after: String, messages: List<Message>, now: Double = nowMs()): String? {
    val i = messages.indexOfFirst { it.id == after }
    if (i < 0 || i + 1 >= messages.size) return null
    val next = messages[i + 1]
    if (next.kind != "voice" || next.deleted || !(next.exp?.let { it > now } ?: true)) return null
    return next.id
}

/** « 21/10/2026 23:21 - Léa : Bonjour » (comme `talka_web::model::export_line`). */
fun exportLine(stamp: String, author: String?, text: String): String = author?.let { "$stamp - $it : $text" } ?: "$stamp - $text"

/** « Talka - Léa.txt », sans les caractères interdits dans un nom de fichier. */
fun exportFilename(title: String): String {
    val clean = StringBuilder()
    var i = 0
    while (i < title.length) {
        val cp = title.codePointAt(i)
        val n = Character.charCount(cp)
        // Un retour à la ligne (`\r\n` compte pour un, comme un `Character` du Swift).
        val newline = cp == 0x0A || cp == 0x0B || cp == 0x0C || cp == 0x0D || cp == 0x85 || cp == 0x2028 || cp == 0x2029
        if (cp == 0x0D && i + 1 < title.length && title[i + 1] == '\n') i += 1
        if (newline || (cp < 0x80 && "/\\:*?\"<>|".indexOf(cp.toChar()) >= 0)) clean.append('_') else clean.appendCodePoint(cp)
        i += n
    }
    return "Talka - ${clean.toString().trimSpaces()}.txt"
}

/** « 21/10/2026 23:21 », à l'heure de l'appareil. */
fun exportStamp(ms: Double): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(ms.toLong()))

/**
 * « 1:05 ». Une durée reçue d'un autre appareil peut être n'importe quoi :
 * bornée (0 à 99 h) avant de devenir un entier, pour ne jamais planter. Le
 * même que le navigateur (`fmt_dur`).
 */
fun formatDuration(s: Double): String {
    val t = if (s.isFinite()) Math.round(minOf(maxOf(s, 0.0), 359_999.0)).toInt() else 0
    return "${t / 60}:" + "%02d".format(t % 60)
}
