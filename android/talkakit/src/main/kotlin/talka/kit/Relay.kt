// La connexion au relais : une WebSocket qui se présente (signature du
// défi), se reconnecte toute seule, et transporte les enveloppes chiffrées.
// Le même protocole que le navigateur (`client/src/net.rs`) et que l'iPhone
// (`Relay.swift`), avec la publication et la demande des paquets de clés v3
// (`publish-bundle`, `fetch-bundle`).
//
// Tout l'état du relais vit sur un seul fil (`dispatcher` : le fil principal
// dans l'application, comme `@MainActor` sur l'iPhone) : ses fonctions
// s'appellent depuis ce fil, et ses rappels (`onState`, `onEnvelope`…) y
// arrivent. Les fonctions `suspend` y passent d'elles-mêmes.

package talka.kit

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Un seul fil à la fois, par défaut (l'application donne le fil principal). */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun serialDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

/** Reprend une attente, si elle attend encore. */
internal fun <T> CancellableContinuation<T>.give(value: T) {
    if (isActive) resume(value)
}

/** Un objet JSON, ses clés dans l'ordre où elles sont mises. */
internal fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject {
    val out = LinkedHashMap<String, JsonElement>()
    for ((k, v) in pairs) out[k] = toJsonElement(v)
    return JsonObject(out)
}

internal fun toJsonElement(v: Any?): JsonElement =
    when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { toJsonElement(it) })
        else -> error("valeur JSON inattendue : $v")
    }

/** Ajoute (ou remplace) des clés à un objet JSON. */
internal fun JsonObject.with(vararg pairs: Pair<String, Any?>): JsonObject {
    val out = LinkedHashMap<String, JsonElement>(this)
    for ((k, v) in pairs) out[k] = toJsonElement(v)
    return JsonObject(out)
}

class Relay(
    val identity: Identity,
    /** Le serveur (`https://…`) : sa WebSocket est `wss://…/ws`. */
    var server: String,
    private val dispatcher: CoroutineDispatcher = serialDispatcher(),
    private val sockets: SocketOpener = OkHttpSockets,
) {
    enum class State { offline, connecting, online }

    /** La réponse du relais à un envoi. */
    sealed class Ack {
        data object Ok : Ack()

        data class Failed(val error: String) : Ack()
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    var state: State = State.offline
        private set(value) {
            val old = field
            field = value
            if (old != value) onState?.invoke(value)
        }

    var hidden: Boolean = false
        set(value) {
            val old = field
            field = value
            if (old != value) task?.close()
        }

    var onState: ((State) -> Unit)? = null
    var onEnvelope: ((JsonObject) -> Unit)? = null
    var onPresence: ((String, Boolean, Double?) -> Unit)? = null

    /** Une enveloppe arrive d'une boîte tournante (v4). */
    var onBoxEnvelope: ((JsonObject) -> Unit)?
        get() = boxes.onEnvelope
        set(value) {
            boxes.onEnvelope = value
        }

    /** Cet appareil a été retiré du compte (v4). */
    var onUnlinked: (() -> Unit)? = null

    /**
     * La clé du relais retenue (v6, §7.16) : vide tant qu'aucune n'est
     * prouvée. `onRelayKey` reçoit la première prouvée, à retenir.
     */
    var relayKey = ""
    var onRelayKey: ((String) -> Unit)? = null

    /**
     * Le relais ne prouve pas sa clé habituelle, ou un ordre d'effacement
     * n'est pas signé d'elle : il est ignoré (une fois par ouverture).
     */
    var onUnprovenRelay: (() -> Unit)? = null
    internal var relayWarned = false

    /** Le défi de la connexion en cours : le relais le signe (v6). */
    internal var nonce = ""

    /** Cet appareil (v4) : sa boîte chez le relais est `<code>/<dev>`. */
    var dev = ""

    /** La connexion anonyme qui lit nos boîtes tournantes (v4). */
    val boxes: BoxSocket = BoxSocket(Companion.wsURL(server), scope, sockets)

    internal var task: Socket? = null

    /** Ce qui attend de partir sur `task`, dans l'ordre. */
    private val outgoing = ArrayDeque<String>()
    private var sendingOn: Socket? = null
    private var retry = 0
    private var stopped = false
    private var reconnect: Job? = null
    private val pending = HashMap<String, CancellableContinuation<Ack>>()
    private val lookups = HashMap<String, MutableList<CancellableContinuation<JsonObject?>>>()
    private val bundles = HashMap<String, MutableList<CancellableContinuation<JsonObject?>>>()

    /** Le relais connaît-il les paquets v3 ? (rien : pas encore su.) */
    var relayV3: Boolean? = null
        private set
    private val watched = LinkedHashSet<String>()

    /**
     * Les `ping` envoyés et les `pong` reçus sur cette connexion, et le
     * numéro du `ping` qui suit la publication du paquet : si son `pong`
     * arrive avant `published`, le relais ne connaît pas v3.
     */
    private var pings = 0
    private var pongs = 0
    private var publishPing: Int? = null

    /** Le relais sait-il livrer en expéditeur scellé (`ready.sealed`) ? */
    var sealed = false
        private set

    /**
     * Le léger délai au hasard avant un dépôt anonyme (v6) ; coupé par
     * les tests qui comptent les millisecondes.
     */
    var jitter = true

    /** La connexion anonyme (expéditeur scellé), ouverte à la demande. */
    private var anon: Socket? = null
    private var anonReady = false
    private val anonWaiting = ArrayList<CancellableContinuation<Boolean>>()
    private val anonPending = HashMap<String, CancellableContinuation<Ack>>()

    /** Les envois partis scellés (et acceptés), depuis le lancement. */
    var sealedSent = 0
        private set

    /** Ceux d'entre eux déposés dans une boîte tournante (v4). */
    var boxSent = 0
        private set

    /** L'identifiant du démarrage du relais connecté (vide : relais ancien). */
    var boot = ""
        private set

    /** L'heure du relais moins la nôtre. */
    internal var skew = 0.0

    /** L'heure du relais, en millisecondes (les horodatages des messages). */
    fun now(): Double = nowMs() + skew

    internal val wsURL: String get() = wsURL(server)

    companion object {
        /** La WebSocket d'un serveur (`https://…` → `wss://…/ws`). */
        fun wsURL(server: String): String {
            val u = URI(server)
            val scheme = if (u.scheme == "http") "ws" else "wss"
            val raw = u.rawPath ?: ""
            val path = (if (raw.endsWith("/")) raw.dropLast(1) else raw) + "/ws"
            val query = u.rawQuery?.let { "?$it" } ?: ""
            val fragment = u.rawFragment?.let { "#$it" } ?: ""
            return "$scheme://${u.rawAuthority ?: ""}$path$query$fragment"
        }
    }

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        reconnect?.cancel()
        task?.close()
        task = null
        anon?.close()
        boxes.close()
        state = State.offline
    }

    /** Le réseau est revenu, ou l'application revient au premier plan. */
    fun wake() {
        if (state == State.offline && !stopped) {
            reconnect?.cancel()
            retry = 0
            connect()
        }
    }

    /**
     * Retour au premier plan après une veille : les connexions ont pu
     * mourir sans le dire (elles semblent ouvertes, plus rien n'arrive). On
     * les rouvre tout de suite : ce qui attend chez le relais arrive, et ce
     * qui attendait de partir repart (comme le navigateur).
     */
    fun reopen() {
        if (stopped) return
        task?.let { closed(it) }
        reconnect?.cancel()
        retry = 0
        anon?.let { anonClosed(it) }
        boxes.reopenNow()
        connect()
    }

    private fun connect() {
        if (stopped || task != null) return
        val t = sockets.open(wsURL)
        task = t
        outgoing.clear()
        state = State.connecting
        listen(t)
        ping(t)
    }

    private fun listen(t: Socket) {
        scope.launch {
            while (true) {
                val text = try {
                    t.receive()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    closed(t)
                    return@launch
                }
                if (task !== t) return@launch
                receive(text)
            }
        }
    }

    /** Un ping régulier : la connexion reste vivante (et se sait morte). */
    private fun ping(t: Socket) {
        scope.launch {
            while (task === t) {
                delay(25_000)
                if (task !== t) return@launch
                // Le relais répond `pong` ; une connexion morte échoue ici.
                sendPing()
            }
        }
    }

    private fun closed(t: Socket) {
        if (task !== t) return
        t.close()
        task = null
        state = State.offline
        for (c in pending.values) c.give(Ack.Failed("offline"))
        pending.clear()
        for (list in lookups.values) for (c in list) c.give(null)
        lookups.clear()
        for (list in bundles.values) for (c in list) c.give(null)
        bundles.clear()
        if (stopped) return
        val wait = min(30.0, 2.0.pow(retry)) * Random.nextDouble(0.7, 1.3)
        retry = min(retry + 1, 5)
        reconnect = scope.launch {
            delay((wait * 1000).toLong())
            connect()
        }
    }

    private fun raw(obj: JsonObject): Boolean {
        if (state != State.online) return false
        val t = task ?: return false
        transmit(t, JSON.write(obj))
        return true
    }

    /**
     * Les messages partent dans l'ordre où ils ont été écrits (une seule
     * file par connexion) : le `ping` qui suit une publication ne la double
     * jamais.
     */
    private fun transmit(t: Socket, text: String) {
        if (task !== t) return
        outgoing.addLast(text)
        if (sendingOn === t) return
        sendingOn = t
        scope.launch {
            while (task === t && outgoing.isNotEmpty()) {
                val next = outgoing.removeFirst()
                try {
                    t.send(next)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (sendingOn === t) sendingOn = null
                    closed(t)
                    return@launch
                }
            }
            if (sendingOn === t) sendingOn = null
        }
    }

    internal fun receive(text: String) {
        val msg = JSON.parse(text) as? JsonObject ?: return
        when (msg.str("t")) {
            "challenge" -> {
                msg.doubleOrNull("time")?.let { skew = it - nowMs() }
                val nonce = msg.str("nonce")
                this.nonce = nonce
                var hello = jsonOf(
                    "t" to "hello", "sign" to identity.sign, "dh" to identity.dh,
                    "sig" to identity.signChallenge(nonce), "hidden" to hidden,
                )
                if (dev.isNotEmpty()) hello = hello.with("dev" to dev)
                task?.let { transmit(it, JSON.write(hello)) }
            }
            "ready" -> {
                retry = 0
                checkRelayKey(msg.str("relais"), msg.str("relais_sig"))
                boot = msg.str("boot")
                sealed = msg.bool("sealed")
                relayV3 = null
                pings = 0
                pongs = 0
                publishPing = null
                state = State.online
                if (watched.isNotEmpty()) raw(jsonOf("t" to "watch", "codes" to watched.toList()))
            }
            "ack", "error" -> {
                val id = msg.str("id")
                pending.remove(id)?.give(if (msg.str("t") == "ack") Ack.Ok else Ack.Failed(msg.strOrNull("error") ?: "error"))
            }
            "msg" -> onEnvelope?.invoke(msg)
            "keys" -> {
                val code = msg.str("code")
                val found = if (msg.bool("unknown")) null else msg
                for (c in lookups.remove(code) ?: emptyList()) c.give(found)
            }
            "bundle" -> {
                val code = msg.str("code")
                for (c in bundles.remove(code) ?: emptyList()) c.give(msg)
            }
            // La réponse au `ping` qui suit la publication du paquet : si elle
            // arrive avant `published`, le relais ne connaît pas v3 (les réponses
            // arrivent dans l'ordre des demandes).
            "pong" -> {
                pongs += 1
                val p = publishPing
                if (relayV3 == null && p != null && pongs >= p) {
                    relayV3 = false
                    for (list in bundles.values) for (c in list) c.give(null)
                    bundles.clear()
                }
            }
            "published" -> {
                relayV3 = true
                if (msg.boolOrNull("ok") != true) {
                    v3Log("v3 : paquet de clés refusé par le relais (${msg.strOrNull("error") ?: "?"})")
                }
            }
            "presence" -> onPresence?.invoke(msg.str("code"), msg.bool("online"), msg.doubleOrNull("seen"))
            // Cet appareil a été retiré du compte (v4).
            "unlinked" -> if (relaySaysUnlinked(msg.str("sig"))) onUnlinked?.invoke()
            else -> {}
        }
    }

    // ----- L'expéditeur scellé -----
    //
    // Une seconde WebSocket, qui ne se présente pas (`{"t":"anon"}`) : le
    // relais ne sait pas qui l'a ouverte. Elle ne sert qu'à déposer des
    // enveloppes scellées, avec le jeton de remise du destinataire.

    private fun openAnon() {
        val t = sockets.open(wsURL)
        anon = t
        anonReady = false
        scope.launch {
            while (true) {
                val text = try {
                    t.receive()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    anonClosed(t)
                    return@launch
                }
                if (anon !== t) return@launch
                val msg = JSON.parse(text) as? JsonObject ?: continue
                when (msg.str("t")) {
                    "challenge" -> sendQuietly(t, """{"t":"anon"}""")
                    "ready" -> {
                        anonReady = true
                        anonDone(true)
                    }
                    "ack", "error" -> {
                        if (msg.str("t") == "ack") sealedSent += 1
                        anonPending.remove(msg.str("id"))
                            ?.give(if (msg.str("t") == "ack") Ack.Ok else Ack.Failed(msg.strOrNull("error") ?: "error"))
                    }
                    else -> {}
                }
            }
        }
        // Le relais ferme une connexion silencieuse : un ping régulier.
        scope.launch {
            while (anon === t) {
                delay(25_000)
                if (anon !== t) return@launch
                sendQuietly(t, """{"t":"ping"}""")
            }
        }
    }

    /** Un envoi dont l'échec ne dit rien (la lecture, elle, verra la connexion fermée). */
    private fun sendQuietly(t: Socket, text: String) {
        scope.launch {
            try {
                t.send(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    private fun anonClosed(t: Socket) {
        if (anon !== t) return
        t.close()
        anon = null
        anonReady = false
        anonDone(false)
        for (c in anonPending.values) c.give(Ack.Failed("offline"))
        anonPending.clear()
    }

    private fun anonDone(ok: Boolean) {
        val waiting = ArrayList(anonWaiting)
        anonWaiting.clear()
        for (c in waiting) c.give(ok)
    }

    /** La connexion anonyme, prête (ouverte au besoin, 10 secondes au plus). */
    private suspend fun anonConnected(): Boolean {
        if (anonReady && anon != null) return true
        if (anon == null) openAnon()
        val t = anon
        return suspendCancellableCoroutine { c ->
            anonWaiting.add(c)
            scope.launch {
                delay(10_000)
                if (anonReady || anon !== t) return@launch
                t?.let { anonClosed(it) }
            }
        }
    }

    /**
     * Dépose une enveloppe scellée pour `peer` (son appareil `dev` en v4),
     * avec son jeton de remise.
     */
    suspend fun sendSealed(
        id: String, peer: Contact, dev: String = "", body: String, live: Boolean, wake: Boolean,
        apercu: String? = null,
    ): Ack =
        withContext(dispatcher) {
            var msg = jsonOf(
                "t" to "send", "id" to id, "to" to peer.code, "sign" to peer.sign, "dh" to peer.dh,
                "jeton" to peer.jeton, "body" to body, "live" to live,
            )
            if (dev.isNotEmpty()) msg = msg.with("dev" to dev)
            anonSend(id, msg, wake, apercu)
        }

    /**
     * Dépose une enveloppe scellée dans une boîte tournante (v4) : ni code,
     * ni jeton. `ttl` : combien de temps le relais la garde (secondes).
     */
    suspend fun sendBoite(
        id: String, boite: String, body: String, live: Boolean, wake: Boolean,
        apercu: String? = null, ttl: Int? = null,
    ): Ack =
        withContext(dispatcher) {
            var msg = jsonOf("t" to "send", "id" to id, "boite" to boite, "body" to body, "live" to live)
            if (ttl != null) msg = msg.with("ttl" to ttl)
            val ack = anonSend(id, msg, wake, apercu)
            if (ack == Ack.Ok) boxSent += 1
            ack
        }

    private suspend fun anonSend(id: String, message: JsonObject, wake: Boolean, apercu: String?): Ack {
        // Un léger délai au hasard (v6, §7.15) : l'heure du dépôt ne colle
        // plus à la frappe. Pas pour ce qui est en direct, ni pour la liaison
        // d'un appareil (`ttl`).
        if (message.boolOrNull("live") != true && message["ttl"] == null && jitter) {
            delay(Random.nextLong(200, 2_001))
        }
        if (!anonConnected()) return Ack.Failed("offline")
        val a = anon ?: return Ack.Failed("offline")
        var msg = message
        if (wake) {
            msg = msg.with("wake" to true)
            if (apercu != null) msg = msg.with("apercu" to apercu)
        }
        val text = JSON.write(msg)
        return suspendCancellableCoroutine { c ->
            anonPending[id] = c
            scope.launch {
                try {
                    a.send(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    anonClosed(a)
                }
            }
            scope.launch {
                delay(30_000)
                anonPending.remove(id)?.give(Ack.Failed("timeout"))
            }
        }
    }

    /**
     * Envoie une enveloppe ; tenue quand le relais l'a reçue. `wake` : un vrai
     * message, qui réveille un destinataire hors ligne (notifications du
     * navigateur) ; pas un accusé de lecture, une réaction ou un profil.
     * `apercu` : ce que la notification affiche, chiffré pour le destinataire.
     */
    suspend fun send(
        id: String, to: String, dev: String = "", body: String, live: Boolean, wake: Boolean = false,
        apercu: String? = null,
    ): Ack =
        withContext(dispatcher) {
            var msg = jsonOf("t" to "send", "id" to id, "to" to to, "body" to body, "live" to live)
            if (dev.isNotEmpty()) msg = msg.with("dev" to dev)
            if (wake) {
                msg = msg.with("wake" to true)
                if (apercu != null) msg = msg.with("apercu" to apercu)
            }
            if (!raw(msg)) return@withContext Ack.Failed("offline")
            suspendCancellableCoroutine<Ack> { c ->
                pending[id] = c
                scope.launch {
                    delay(30_000)
                    pending.remove(id)?.give(Ack.Failed("timeout"))
                }
            }
        }

    fun got(numbers: List<Int>) {
        if (numbers.isNotEmpty()) raw(jsonOf("t" to "got", "n" to numbers))
    }

    /** Les clés publiques d'un code (rien si le relais ne le connaît pas, ou sans réseau). */
    suspend fun lookup(code: String): JsonObject? =
        withContext(dispatcher) {
            if (!raw(jsonOf("t" to "lookup", "code" to code))) return@withContext null
            suspendCancellableCoroutine<JsonObject?> { c ->
                lookups.getOrPut(code) { ArrayList() }.add(c)
                scope.launch {
                    delay(15_000)
                    val list = lookups[code]
                    if (list.isNullOrEmpty()) return@launch
                    list.removeAt(0).give(null)
                    if (list.isEmpty()) lookups.remove(code)
                }
            }
        }

    /**
     * Publie le paquet de clés v3 (base64) et sa signature par la clé du
     * code ; `dev` : celui de cet appareil (v4), sinon celui des anciens
     * clients. Le `ping` qui suit dit si le relais connaît v3.
     */
    fun publishBundle(bundle: String, sig: String, dev: String = ""): Boolean {
        var msg = jsonOf("t" to "publish-bundle", "bundle" to bundle, "sig" to sig)
        if (dev.isNotEmpty()) msg = msg.with("dev" to dev)
        if (!raw(msg) || !sendPing()) return false
        if (relayV3 == null) publishPing = pings
        return true
    }

    private fun sendPing(): Boolean {
        if (!raw(jsonOf("t" to "ping"))) return false
        pings += 1
        return true
    }

    /**
     * La réponse du relais à la demande du paquet de clés v3 d'un code
     * (`"none": true` s'il n'en a pas ; rien : pas de réponse, sans réseau
     * ou avec un relais d'avant v3). Le paquet est à vérifier :
     * `V3.verifyBundle`.
     */
    suspend fun fetchBundle(code: String): JsonObject? =
        withContext(dispatcher) {
            if (relayV3 == false || !raw(jsonOf("t" to "fetch-bundle", "code" to code))) return@withContext null
            suspendCancellableCoroutine<JsonObject?> { c ->
                bundles.getOrPut(code) { ArrayList() }.add(c)
                scope.launch {
                    delay(10_000)
                    val list = bundles[code]
                    if (list.isNullOrEmpty()) return@launch
                    list.removeAt(0).give(null)
                    if (list.isEmpty()) bundles.remove(code)
                }
            }
        }

    /**
     * Les nonces de nos jetons de remise révoqués (la liste remplace la
     * précédente) : le relais refuse ensuite les dépôts qui les présentent.
     */
    fun revoke(nonces: List<String>) {
        raw(jsonOf("t" to "revoke", "nonces" to nonces))
    }

    fun watch(codes: List<String>) {
        val fresh = codes.filter { watched.add(it) }
        if (fresh.isNotEmpty()) raw(jsonOf("t" to "watch", "codes" to fresh))
    }

    /** Retire un appareil de notre compte (v4). */
    fun unlink(dev: String): Boolean = raw(jsonOf("t" to "unlink", "dev" to dev))

    // ----- La clé du relais (v6, docs/SECURITE.md §7.16) -----

    /** La clé attendue du relais : celle des sources, ou celle retenue. */
    internal val pinnedRelayKey: String get() = RelayKey.official.ifEmpty { relayKey }

    /**
     * Le relais vient d'accepter la connexion : une clé prouvée est retenue
     * la première fois ; une autre ensuite, ou plus de preuve, et Talka le
     * dit et n'obéit plus à ses ordres d'effacement.
     */
    internal fun checkRelayKey(key: String, sig: String) {
        val proven = key.isNotEmpty() &&
            TalkaCrypto.verify(key, RelayKey.readyMessage(nonce, identity.code, dev), sig)
        val pinned = pinnedRelayKey
        if (proven && pinned.isEmpty()) {
            relayKey = key
            onRelayKey?.invoke(key)
        } else if (pinned.isNotEmpty() && !(proven && pinned == key)) {
            v3Log("v6 : le relais ne prouve pas sa clé habituelle")
            warnUnproven()
        }
    }

    /**
     * L'ordre « cet appareil est retiré » : à croire seulement s'il est
     * signé de la clé retenue (sans clé retenue, comme avant).
     */
    internal fun relaySaysUnlinked(sig: String): Boolean {
        val pinned = pinnedRelayKey
        if (pinned.isEmpty()) return true
        val ok = TalkaCrypto.verify(pinned, RelayKey.unlinkedMessage(identity.code, dev), sig)
        if (!ok) {
            v3Log("v6 : ordre d'effacement non signé par le relais, ignoré")
            warnUnproven()
        }
        return ok
    }

    private fun warnUnproven() {
        if (relayWarned) return
        relayWarned = true
        onUnprovenRelay?.invoke()
    }
}

// ----- Les boîtes tournantes (v4) ----------------------------------------------
//
// Une troisième WebSocket, anonyme elle aussi, qui ne fait que lire : elle
// s'abonne à nos boîtes (`{"t":"boites"}`) et reçoit ce qui y arrive. Rien ne
// la relie à notre code, sauf l'adresse IP (`docs/SECURITE.md` §7.13). Le
// nouvel appareil s'en sert aussi, avant d'avoir un compte, pour attendre la
// liaison.

class BoxSocket internal constructor(
    val url: String,
    private val scope: CoroutineScope,
    private val sockets: SocketOpener,
) {
    /** Une connexion à elle, sur son propre fil (`dispatcher`). */
    constructor(url: String, dispatcher: CoroutineDispatcher = serialDispatcher(), sockets: SocketOpener = OkHttpSockets) :
        this(url, CoroutineScope(SupervisorJob() + dispatcher), sockets)

    internal var socket: Socket? = null
    internal var ready = false
    private var list: List<String> = emptyList()
    private var reopen: Job? = null

    /** Ce qui arrive dans une boîte : `{"t":"msg","n","id","boite","body","ts","sealed"}`. */
    var onEnvelope: ((JsonObject) -> Unit)? = null

    /** Les boîtes suivies. */
    val boxes: List<String> get() = list

    /**
     * Les boîtes à lire (elles remplacent les précédentes) ; aucune : la
     * connexion se ferme.
     */
    fun listen(boxes: List<String>) {
        val changed = boxes != list
        list = boxes.toList()
        if (list.isEmpty()) {
            close()
            return
        }
        if (socket == null) {
            open()
        } else if (changed && ready) {
            subscribe()
        }
    }

    /** Rouvre la connexion tout de suite, s'il y a des boîtes à lire. */
    fun reopenNow() {
        close()
        if (list.isNotEmpty()) open()
    }

    /** Ferme la connexion (elle ne se rouvre pas d'elle-même). */
    fun close() {
        reopen?.cancel()
        reopen = null
        val t = socket
        socket = null
        ready = false
        t?.close()
    }

    /** Les messages rangés : le relais les oublie. */
    fun got(numbers: List<Int>) {
        val s = socket ?: return
        if (numbers.isEmpty() || !ready) return
        send(s, jsonOf("t" to "got", "n" to numbers))
    }

    private fun send(t: Socket, obj: JsonObject) {
        val text = JSON.write(obj)
        scope.launch {
            try {
                t.send(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    private fun subscribe() {
        val s = socket ?: return
        // Par paquets de 4 000 (le relais en prend 8 000 par demande).
        var start = 0
        while (start < list.size) {
            val part = list.subList(start, min(start + 4_000, list.size)).toList()
            send(s, jsonOf("t" to "boites", "list" to part))
            start += 4_000
        }
    }

    private fun open() {
        val t = sockets.open(url)
        socket = t
        ready = false
        scope.launch {
            while (true) {
                val text = try {
                    t.receive()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    closed(t)
                    return@launch
                }
                if (socket !== t) return@launch
                val msg = JSON.parse(text) as? JsonObject ?: continue
                when (msg.str("t")) {
                    "challenge" -> send(t, jsonOf("t" to "anon"))
                    "ready" -> {
                        ready = msg.bool("boites")
                        if (ready) subscribe()
                    }
                    "msg" -> onEnvelope?.invoke(msg)
                    else -> {}
                }
            }
        }
        // Le relais ferme une connexion silencieuse : un ping régulier.
        scope.launch {
            while (socket === t) {
                delay(25_000)
                if (socket !== t) return@launch
                send(t, jsonOf("t" to "ping"))
            }
        }
    }

    private fun closed(t: Socket) {
        if (socket !== t) return
        t.close()
        socket = null
        ready = false
        // Elle se rouvre tant qu'il y a des boîtes à lire.
        if (list.isEmpty()) return
        reopen?.cancel()
        reopen = scope.launch {
            delay((Random.nextDouble(2.0, 5.0) * 1000).toLong())
            if (socket != null || list.isEmpty()) return@launch
            open()
        }
    }
}

// ----- La clé du relais (v6, docs/SECURITE.md §7.16) -----

object RelayKey {
    /**
     * La clé du relais officiel, si elle est connue d'avance (sinon, la
     * première prouvée est retenue).
     */
    const val official = ""

    fun readyMessage(nonce: String, code: String, dev: String): ByteArray =
        "talka-relais-v6:pret:$nonce:$code/$dev".toByteArray()

    fun unlinkedMessage(code: String, dev: String): ByteArray = "talka-relais-v6:retire:$code/$dev".toByteArray()
}

/**
 * Le journal de développement du protocole (jamais de secret) : rien par
 * défaut, comme l'iPhone hors `DEBUG` ; l'application peut le brancher.
 */
object TalkaLog {
    @Volatile
    var sink: ((String) -> Unit)? = null
}

/** Les avertissements du protocole v3 (journal de développement ; jamais de secret). */
internal fun v3Log(text: String) {
    TalkaLog.sink?.invoke(text)
}
