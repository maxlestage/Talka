// La v4 dans le moteur (`docs/SECURITE.md` §7.11 à §7.13), comme le
// navigateur (`client/src/core.rs`) et l'iPhone (`EngineV4.swift`) :
//
// - plusieurs appareils par compte : chacun a son identifiant (`kv/dev`),
//   son appareil v3 et ses sessions (`<code>/<dev>`) ; la liste des appareils
//   (`kv/appareils`) part aux contacts dans le profil ; un message part en
//   une copie par appareil du destinataire, et une copie `sync` vers nos
//   autres appareils, qui refont ce que celui-ci a fait ;
// - les boîtes aux lettres tournantes : vers un contact qui les lit,
//   l'enveloppe scellée part dans une boîte qui change chaque jour ; une
//   troisième connexion, anonyme, lit les nôtres ;
// - la liaison d'un nouvel appareil par QR code : le compte lui part chiffré
//   (ECDH + HKDF + AES-GCM), en morceaux, dans une boîte de liaison.

package talka.kit

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

/** Ce qui, d'une discussion, est le même sur tous nos appareils (v4). */
internal data class ChatState(
    val markedUnread: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    /** 0 : pour toujours (tant que `muted`). */
    val muteUntil: Double = 0.0,
    val wallpaper: String = "",
    val pinMsg: String = "",
) : JsonEncodable {
    constructor(c: Chat) : this(c.markedUnread, c.pinned, c.archived, c.muted, c.muteUntil ?: 0.0, c.wallpaper, c.pinMsg)

    fun apply(c: Chat): Chat =
        c.copy(
            markedUnread = markedUnread, pinned = pinned, archived = archived, muted = muted,
            muteUntil = if (muteUntil > 0) muteUntil else null, wallpaper = wallpaper.prefixChars(200), pinMsg = pinMsg,
        )

    /** L'ordre des champs du navigateur (`ChatState`). */
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("marked_unread", markedUnread)
            put("pinned", pinned)
            put("archived", archived)
            put("muted", muted)
            put("mute_until", muteUntil)
            put("wallpaper", wallpaper)
            put("pin_msg", pinMsg)
        }

    companion object : JsonDecodable<ChatState> {
        override fun fromJson(json: JsonElement): ChatState {
            val c = json.obj()
            return ChatState(
                c.bool("marked_unread"), c.bool("pinned"), c.bool("archived"), c.bool("muted"),
                c.double("mute_until"), c.str("wallpaper"), c.str("pin_msg"),
            )
        }
    }
}

/** Une valeur (ou un objet) en `JSONValue`, pour le champ `d` d'un `sync`. */
internal fun jsonValue(v: JsonEncodable): JSONValue = JSONValue.fromJson(v.toJson())

/** Un `JSONValue` relu comme une structure. */
internal fun <T> decodeJSON(v: JSONValue, type: JsonDecodable<T>): T? = JSON.decode(type, v.toJson())

/** La liaison d'un appareil n'a pas pu se faire. */
sealed class LinkError(message: String) : Exception(message) {
    data object NotALink : LinkError("Ce n'est pas un code de liaison Talka.")

    data object AlreadyLinked : LinkError("Cet appareil est déjà lié.")

    data object TooMany : LinkError("Six appareils au plus : retirez-en un d'abord.")

    data object Offline : LinkError("Pas de connexion au relais : réessayez.")

    data object Unreadable : LinkError("Code de liaison illisible.")

    data class Failed(val error: String) : LinkError("La liaison n'a pas pu partir ($error) : réessayez.")

    data object Bad : LinkError("La liaison n'a pas abouti : réessayez.")
}

/** Notre compte, vu comme un contact (pour écrire à nos autres appareils). */
internal val Engine.selfContact: Contact
    get() = Contact(code = me.code, sign = me.sign, dh = me.dh, pj = true, v3 = true)

/** Nos autres appareils. */
val Engine.siblings: List<String> get() = devices.list.map { it.dev }.filter { it != dev }

/**
 * Les appareils où écrire à `code` : chacun des siens (v4, sa dernière
 * liste annoncée), ou le code seul (vide, avant la v4). Pour nous-mêmes :
 * nos autres appareils.
 */
internal fun Engine.targets(code: String): List<String> {
    if (code == me.code) return siblings
    val c = _contacts[code]
    if (c != null && c.devs.isNotEmpty()) return c.devs.map { it.d }
    return listOf("")
}

// ----- Cet appareil et ceux du compte -----

/** Cet appareil : son identifiant (tiré une fois, jamais sauvegardé), et sa place dans la liste des appareils du compte. */
internal fun Engine.ensureDevice() {
    var d = store.read("kv", "dev", JsonText) ?: ""
    if (!TalkaV4.isDev(d)) {
        d = TalkaV4.newDev()
        store.writeRaw("kv", "dev", JSON.encode(d))
    }
    dev = d
    relay.dev = d
    var list = store.read("kv", "appareils", Devices) ?: Devices()
    // Lié depuis un autre appareil : il prend son vrai nom.
    val i = list.list.indexOfFirst { it.dev == d && it.name == Engine.newDeviceName }
    if (i >= 0) {
        list = list.copy(v = list.v + 1, list = list.list.mapIndexed { j, a -> if (j == i) a.copy(name = Engine.deviceName) else a })
        store.write("kv", "appareils", list)
    }
    if (!list.has(d)) {
        val all = ArrayList(list.list)
        all.add(AccountDevice(d, Engine.deviceName, nowMs()))
        // Au-delà de six, les plus anciens cèdent leur place.
        while (all.size > maxDevices) all.removeAt(0)
        list = list.copy(v = list.v + 1, list = all)
        store.write("kv", "appareils", list)
    }
    devices = list
}

/** La liste des appareils a changé : gardée, donnée à nos autres appareils et annoncée aux contacts (dans le profil). */
internal fun Engine.devicesChanged(list: Devices, tellSiblings: Boolean) {
    devices = list
    store.write("kv", "appareils", list)
    if (tellSiblings) sync("devices", d = jsonValue(list))
    announceProfile()
}

/** La liste des appareils a changé depuis la dernière annonce (un appareil ajouté au démarrage, une liaison faite hors ligne) : annoncée. */
internal fun Engine.announceDevices() {
    val told = store.read("kv", "appareils_annonces", JsonInt) ?: 0
    if (devices.v <= told) return
    sync("devices", d = jsonValue(devices))
    announceProfile()
    store.writeRaw("kv", "appareils_annonces", devices.v.toString())
}

/** Renomme cet appareil (ou un autre du compte). */
fun Engine.renameDevice(d: String, name: String) {
    val list = devices
    val i = list.list.indexOfFirst { it.dev == d }
    if (i < 0) return
    val trimmed = name.trimBlanks().prefixChars(40)
    if (trimmed.isEmpty() || trimmed == list.list[i].name) return
    devicesChanged(list.copy(v = list.v + 1, list = list.list.mapIndexed { j, a -> if (j == i) a.copy(name = trimmed) else a }), tellSiblings = true)
}

/** Retire un appareil du compte : il reçoit l'ordre de tout effacer, le relais l'oublie, et les contacts ne lui écrivent plus. */
suspend fun Engine.unlinkDevice(d: String) {
    withContext(dispatcher) {
        if (d == dev || !devices.has(d)) return@withContext
        // Lui d'abord (tant qu'il est encore dans la liste).
        enqueueTo(me.code, d, Wire(Body.Sync("unlink", d = JSONValue.Object(mapOf("dev" to JSONValue.Str(d))))))
        flush()
        relay.unlink(d)
        devicesChanged(devices.remove(d), tellSiblings = true)
    }
}

/**
 * Les appareils retirés, redits au relais à chaque connexion (il ne garde
 * rien sur disque) : un appareil retiré pendant qu'il était hors ligne
 * reste refusé, et efface tout à son retour.
 */
internal fun Engine.unlinkGone() {
    for (d in devices.gone) relay.unlink(d)
}

/**
 * Cet appareil quitte le compte (avant de tout effacer) : nos autres
 * appareils et les contacts ne lui écrivent plus. Au mieux : sans réseau,
 * il reste dans la liste jusqu'à ce qu'un autre appareil le retire.
 */
suspend fun Engine.leaveAccount() {
    withContext(dispatcher) {
        if (siblings.isEmpty() || !devices.has(dev) || relay.state != Relay.State.online) return@withContext
        val list = devices
        devicesChanged(list.copy(v = list.v + 1, list = list.list.filter { it.dev != dev }), tellSiblings = true)
        flush()
        // Un envoi déjà en cours finit le travail : on l'attend (5 s au plus).
        var waited = 0
        while (flushing && waited < 50) {
            delay(100)
            waited += 1
        }
    }
}

/** Le relais (ou un autre de nos appareils) dit que cet appareil a été retiré du compte : tout est effacé. */
internal fun Engine.unlinked() {
    val f = onUnlinked
    if (f != null) f() else wipeEverything()
}

// ----- La synchronisation entre nos appareils (§7.11) -----

/** Ce que cet appareil a fait, pour nos autres appareils. */
internal fun Engine.sync(op: String, c: String = "", w: Wire? = null, d: JSONValue = JSONValue.Null) {
    if (siblings.isEmpty()) return
    enqueue(me.code, Wire(Body.Sync(op, c, w, d)))
}

/** Un contact ajouté, renommé, bloqué : pareil sur nos autres appareils. */
internal fun Engine.syncContact(c: Contact) {
    sync(
        "contact",
        d = JSONValue.Object(
            linkedMapOf(
                "code" to JSONValue.Str(c.code), "name" to JSONValue.Str(c.name), "saved" to JSONValue.Bool(c.saved),
                "blocked" to JSONValue.Bool(c.blocked), "sign" to JSONValue.Str(c.sign), "dh" to JSONValue.Str(c.dh),
            ),
        ),
    )
}

/** Un groupe changé ici : nos autres appareils le changent aussi. */
internal fun Engine.syncGroup(c: Chat, note: String) {
    sync(
        "group", c = c.id,
        d = JSONValue.Object(linkedMapOf("grp" to jsonValue(groupInfo(c)), "left" to JSONValue.Bool(c.left), "note" to JSONValue.Str(note))),
    )
}

/** Un élément de la boîte d'envoi bien arrivé sur l'appareil `d` du destinataire : gardé tant qu'un autre l'attend. */
internal fun Engine.gotBy(key: String, d: String) {
    // L'enveloppe est d'abord lue sans son contenu (une photo) : le plus
    // souvent elle n'attendait que cet accusé et part ; elle n'est
    // décodée en entier que s'il faut la réécrire.
    val json = store.readRaw("outbox", key)
    var head = json?.let { OutItem.head(it) }
    if (d.isEmpty() || json == null || head == null) {
        store.remove("outbox", key)
        return
    }
    val known = d in head.got
    if (!known) head = head.copy(got = head.got + d)
    if (!(head.boot.isEmpty() || stillUnconfirmed(head))) {
        store.remove("outbox", key)
        return
    }
    // Rien de neuf : la ligne est déjà la bonne.
    if (known) return
    val item = JSON.decode(OutItem, json) ?: return
    store.write("outbox", key, item.copy(got = head.got))
}

/** Ce qu'un autre de nos appareils (`fd`) a fait : refait ici, sans le renvoyer à personne. */
internal fun Engine.handleSync(fd: String, op: String, chat: String, w: Wire?, d: JSONValue) {
    when (op) {
        "wire" -> {
            if (w == null) return
            applyOwnWire(chat, w)
            // Un message : l'appareil qui l'a envoyé sait que nous l'avons.
            val b = w.body
            if (b is Body.Msg) {
                enqueueTo(me.code, fd, Wire(Body.Sync("got", chat, d = JSONValue.Object(mapOf("ids" to JSONValue.Array(listOf(JSONValue.Str(b.id))))))))
            }
        }
        "got" -> for (id in d["ids"]?.array?.mapNotNull { it.string } ?: emptyList()) gotBy("$id:${me.code}", fd)
        "read" -> readHere(chat, d["at"]?.number ?: 0.0)
        "contact" -> syncedContact(d)
        "chat" -> {
            if (d["deleted"]?.bool == true) {
                dropChat(chat)
            } else if (d["cleared"]?.bool == true) {
                if (d["keep_starred"]?.bool == true) emptyChatButStarred(chat) else emptyChat(chat)
            } else {
                val state = decodeJSON(d, ChatState)
                val c = _chats[chat]
                if (state != null && c != null) saveChat(state.apply(c))
            }
        }
        "forget" -> {
            for (id in d["ids"]?.array?.mapNotNull { it.string } ?: emptyList()) {
                val m = store.message(chat, id) ?: continue
                store.removeMessage(chat, id)
                bury(chat, listOf(m.id to m.at))
            }
            refreshLast(chat)
            bump(chat)
        }
        "star" -> {
            store.message(chat, d["id"]?.string ?: "")?.let { m ->
                store.writeMessage(m.copy(starred = d["on"]?.bool ?: false))
                bump(chat)
            }
        }
        "group" -> {
            val grp = d["grp"]?.let { decodeJSON(it, GroupInfo) } ?: return
            applyOwnGroup(grp, d["left"]?.bool ?: false, d["note"]?.string ?: "")
        }
        "profile" -> {
            val p = decodeJSON(d, Profile) ?: return
            if (p.v <= profile.v) return
            profile = Profile(p.name.prefixChars(40), p.about.prefixChars(140), if (p.avatar.charCount() <= 60_000) p.avatar else "", p.v)
            store.write("kv", "profile", profile)
        }
        "devices" -> {
            val list = decodeJSON(d, Devices) ?: return
            // Retirés par un autre appareil, nous ne le sommes que par
            // `unlink` (une liste en retard ne nous efface pas).
            val merged = devices.merge(list)
            if (merged != null && merged.has(dev)) devicesChanged(merged, tellSiblings = false)
        }
        "status" -> {
            val deleted = d["deleted"]?.string
            if (deleted != null) {
                store.remove("statuses", "${me.code}|$deleted")
            } else {
                decodeJSON(d, StatusItem)?.let { st0 ->
                    val st = st0.copy(owner = me.code, seen = true)
                    store.write("statuses", st.key, st)
                }
            }
            statusRevision += 1
        }
        "call" -> onCallSignal?.invoke(me.code, Body.Call("elsewhere", d["id"]?.string ?: "", false, "", null), true)
        "unlink" -> {
            // Après la transaction en cours (la base est effacée).
            if (d["dev"]?.string == dev && devices.has(fd)) spawn { unlinked() }
        }
        else -> {}
    }
}

/** Un contact ajouté, renommé ou bloqué sur un autre de nos appareils. */
internal fun Engine.syncedContact(d: JSONValue) {
    val code = d["code"]?.string?.let { TalkaCode.normalize(it) } ?: return
    if (code == me.code) return
    var k = _contacts[code] ?: Contact(code = code)
    k = k.copy(
        name = (d["name"]?.string ?: "").prefixChars(40),
        saved = d["saved"]?.bool ?: k.saved,
        blocked = d["blocked"]?.bool ?: k.blocked,
    )
    // Ses clés, seulement si elles vont avec son code.
    val sign = d["sign"]?.string ?: ""
    val dh = d["dh"]?.string ?: ""
    if (k.sign.isEmpty() && TalkaCrypto.verifyKeys(code, sign, dh)) k = k.copy(sign = sign, dh = dh)
    saveContact(k)
    relay.watch(listOf(code))
}

/** Un groupe créé, changé ou quitté sur un autre de nos appareils. */
internal fun Engine.applyOwnGroup(grp: GroupInfo, left: Boolean, note: String) {
    val id = "g:${grp.id}"
    var c = _chats[id] ?: Chat(id = id)
    if (_chats[id] == null) {
        c = c.copy(group = true, createdBy = me.code, createdAt = nowMs(), lastAt = nowMs())
    }
    if (grp.v < c.v) return
    c = c.copy(name = grp.name.prefixChars(60), avatar = grp.avatar, members = grp.members, admins = grp.admins)
    grp.desc?.let { c = c.copy(desc = groupDesc(it)) }
    grp.adminsOnly?.let { c = c.copy(adminsOnly = it) }
    c = c.copy(v = grp.v, left = left)
    saveChat(c)
    if (note.isNotEmpty()) systemMessage(id, note)
    relay.watch(grp.members.filter { it != me.code })
}

/** Ce qu'un autre de nos appareils a envoyé dans la discussion `chatId`. */
internal fun Engine.applyOwnWire(chatId: String, wire: Wire) {
    // La discussion : un groupe dont nous sommes, ou un contact.
    val c: Chat =
        if (chatId.startsWith("g:")) {
            _chats[chatId] ?: return
        } else {
            val code = TalkaCode.normalize(chatId) ?: return
            if (code == me.code) return
            ensureDirectChat(code)
        }
    when (val b = wire.body) {
        is Body.Msg -> {
            if (store.message(c.id, b.id) != null || buried(c.id, b.id)) return
            var safe = b.content
            val kinds = listOf("text", "image", "voice", "file", "contact", "brokey")
            if (safe.kind !in kinds || (safe.kind == "brokey" && Brocolis.key(safe.data) == null)) safe = safe.copy(kind = "text")
            safe = safe.copy(text = safe.text.prefixChars(65_536))
            val to = recipients(c)
            var m = Message(c.id, b.id, me.code, b.at, safe).copy(status = "sent", to = to, sentTo = to, readByMe = true)
            wire.ttl?.let { m = m.copy(exp = b.at + it.toDouble() * 1000) }
            m = openMessage(m).second
            storeMessage(m)
        }
        is Body.React -> {
            val m = store.message(c.id, b.id) ?: return
            val reactions = LinkedHashMap(m.reactions)
            if (b.emoji.isEmpty()) reactions.remove(me.code) else reactions[me.code] = b.emoji.prefixChars(8)
            store.writeMessage(m.copy(reactions = reactions))
            bump(c.id)
        }
        is Body.Edit -> {
            val m = store.message(c.id, b.id) ?: return
            if (m.from != me.code || m.deleted) return
            val text = b.text.prefixChars(65_536)
            val opened = openBrocolis(text, c.id)
            if (opened.opened > 0) rememberOriginal(m, text)
            store.writeMessage(m.copy(text = opened.text, bro = opened.opened > 0, edited = true))
            refreshLast(c.id)
            bump(c.id)
        }
        is Body.Delete -> {
            val m = store.message(c.id, b.id) ?: return
            if (m.from != me.code) return
            store.writeMessage(m.copy(deleted = true, text = "", data = "", reactions = emptyMap(), starred = false))
            refreshLast(c.id)
            bump(c.id)
        }
        is Body.Timer -> {
            _chats[c.id]?.let { saveChat(it.copy(timer = b.secs)) }
            systemMessage(c.id, timerText(me.code, b.secs))
        }
        else -> {}
    }
}

// ----- Les boîtes tournantes (§7.13) -----

/**
 * Les boîtes où nos contacts nous écrivent (à cet appareil) : pour chacun,
 * chaque jour depuis la dernière écoute (un de marge, trente au plus)
 * jusqu'à demain. La connexion anonyme de lecture les suit.
 */
internal fun Engine.refreshBoxes() {
    boxRefresh?.cancel()
    boxRefresh = null
    val today = TalkaV4.day(relay.now())
    boxDay = today
    val last = store.read("kv", "boites_jour", JsonInt) ?: today
    val from = maxOf(maxOf(last - 1, 0), today - 30)
    val map = HashMap<String, String>()
    if (from <= today + 1) {
        for (c in _contacts.values) {
            if (c.blocked || c.dh.isEmpty() || c.code == me.code) continue
            val key = TalkaV4.boitesKey(me, c.code, c.dh) ?: continue
            for (day in from..(today + 1)) map[TalkaV4.boite(key, me.code, dev, day)] = c.code
        }
    }
    // Des boîtes de couverture (v6, §7.15) : à aujourd'hui et demain, et
    // assez pour que le nombre de boîtes suivies ne dise pas combien nous
    // avons de contacts (un multiple de 64).
    val secret = coverSecret()
    for (day in listOf(today, today + 1)) {
        for (i in 0 until Engine.coverMin) map[TalkaV4.coverBox(secret, day, i)] = ""
    }
    var i = Engine.coverMin
    while (map.size % Engine.coverRound != 0) {
        map[TalkaV4.coverBox(secret, today, i)] = ""
        i += 1
    }
    boxMap = map
    relay.boxes.listen(map.keys.sortedWith(::compareCodePoints))
    if (relay.state == Relay.State.online) store.writeRaw("kv", "boites_jour", today.toString())
}

/** Le secret des boîtes de couverture : tiré une fois, propre à l'appareil (la même clé que le navigateur, `kv/couverture`). */
internal fun Engine.coverSecret(): ByteArray {
    store.read("kv", "couverture", JsonText)?.let { saved -> unb64(saved)?.takeIf { it.size == 32 }?.let { return it } }
    val s = randomBytes(32)
    store.writeRaw("kv", "couverture", JSON.encode(b64(s)))
    return s
}

/**
 * Le trafic de couverture (v6, §7.15) : de temps en temps (une fois
 * toutes les trois minutes en moyenne, en ligne), une enveloppe scellée
 * vide, au même palier qu'un court message, part dans une de nos boîtes
 * de couverture ; elle nous revient, et on l'oublie.
 */
internal suspend fun Engine.sendCover() {
    if (relay.state != Relay.State.online || !relay.sealed || Random.nextDouble() >= 0.35) return
    val day = TalkaV4.day(relay.now())
    val boite = TalkaV4.coverBox(coverSecret(), day, Random.nextInt(Engine.coverMin))
    val sealed = TalkaCrypto.sealSender(me.code, dev, me.code, me.dh, "") ?: return
    // Pas `sendBoite` : la couverture ne compte pas parmi les envois.
    val id = randomId(9)
    relay.anonSend(id, jsonOf("t" to "send", "id" to id, "boite" to boite, "body" to sealed, "live" to false), wake = false, apercu = null)
}

/** Les boîtes à recalculer bientôt (une fois, après une rafale de changements). */
internal fun Engine.scheduleBoxRefresh() {
    if (boxRefresh != null) return
    boxRefresh = spawn {
        yield()
        boxRefresh = null
        refreshBoxes()
    }
}

// ----- La liaison d'un appareil (§7.12) -----

/** Ce qu'un nouvel appareil ne doit pas recevoir : ce qui est propre à cet appareil-ci. */
internal fun Engine.Companion.deviceOnly(key: String): Boolean =
    key in listOf("dev", "nk", "pin", "appareils", "boites_jour", "brocolis_sealed", "couverture", BrocolisPsk.kv) ||
        key == V3.deviceKey || key == Pq6.keysKey || key.startsWith(V3.sessionPrefix)

/**
 * Lie un nouvel appareil (son code de liaison, lu dans son QR code) : il
 * reçoit notre compte, chiffré pour lui seul. Renvoie le code de
 * vérification à comparer avec celui qu'il affiche.
 */
suspend fun Engine.linkDevice(text: String): String =
    withContext(dispatcher) {
        val offer = TalkaV4.LinkOffer.parse(text) ?: throw LinkError.NotALink
        val list = devices
        if (list.has(offer.dev)) throw LinkError.AlreadyLinked
        if (list.list.size >= maxDevices) throw LinkError.TooMany
        if (relay.state != Relay.State.online || !relay.sealed) throw LinkError.Offline
        val answer = TalkaV4.linkAnswer(offer) ?: throw LinkError.Unreadable
        val next = list.copy(v = list.v + 1, list = list.list + AccountDevice(offer.dev, Engine.newDeviceName, nowMs()))
        val payload = jsonOf("talka" to "lien", "v" to 1, "code" to me.code, "devices" to next.toJson(), "data" to linkDump())
        val chunks = TalkaV4.linkSeal(answer.key, offer.lid, answer.ek, JSON.write(payload).toByteArray())
            ?: throw LinkError.Unreadable
        val boite = offer.boite
        for ((i, chunk) in chunks.withIndex()) {
            val ack = relay.sendBoite("lien-${randomId(8)}-$i", boite, chunk, live = false, wake = false, ttl = 3600)
            if (ack is Relay.Ack.Failed) throw LinkError.Failed(ack.error)
        }
        devicesChanged(next, tellSiblings = true)
        TalkaV4.linkVerification(answer.ek, offer.lk)
    }

/**
 * Notre compte, pour un nouvel appareil : comme la sauvegarde, sans ce
 * qui est propre à cet appareil, ni les pièces jointes de plus de
 * 512 Kio, ni plus de 32 Mio de messages (les plus anciens restent).
 */
internal fun Engine.linkDump(): JsonObject {
    // Sans les messages : seuls les plus récents partent, lus plus bas.
    val dump = LinkedHashMap<String, JsonElement>(store.dump(messages = false))
    val kv = ArrayList(
        dumpRows(dump["kv"]).filter {
            val key = it.rowKey() ?: ""
            !Engine.deviceOnly(key) && key != "brocolis"
        },
    )
    // Les clés Brocolis (dans la garde de cet appareil) en clair : le lien
    // est chiffré pour le seul nouvel appareil.
    if (brocolisKeys.isNotEmpty()) kv.add(JsonArray(listOf(JsonPrimitive("brocolis"), JsonPrimitive(BrocolisKeyList.encode(brocolisKeys)))))
    dump["kv"] = JsonArray(kv)
    dump["outbox"] = JsonArray(emptyList())
    // Les plus récents d'abord, lus un à un jusqu'à 32 Mio : pas toute la
    // base en mémoire pour n'en envoyer qu'une partie.
    val kept = ArrayList<JsonArray>()
    var total = 0
    store.newestMessageRows { key, json0, chat, at ->
        var json = json0
        // Seule une ligne assez grosse peut porter une pièce jointe de plus de 512 Kio.
        if (utf8Length(json) > 512 * 1024) {
            val m = JSON.decode(Message, json)
            if (m != null && utf8Length(m.data) > 512 * 1024) json = JSON.encode(m.copy(data = ""))
        }
        total += utf8Length(json)
        if (total > 32 * 1024 * 1024) return@newestMessageRows false
        kept.add(JsonArray(listOf(JsonPrimitive(key), JsonPrimitive(json), JsonPrimitive(chat), JsonPrimitive(at))))
        true
    }
    dump["messages"] = JsonArray(kept)
    return JsonObject(dump)
}

/** Range le compte reçu par la liaison : tout, sauf ce qui est propre à l'autre appareil ; cet appareil garde son identifiant (`dev`). */
fun Engine.Companion.importLink(bytes: ByteArray, dev: String, into: Store): Boolean {
    val payload = utf8(bytes)?.let { JSON.parse(it) } as? JsonObject ?: return false
    if (payload["talka"].asString() != "lien" || payload["v"].asInt() != 1) return false
    val code = payload["code"].asString() ?: return false
    val listObject = payload["devices"] as? JsonObject ?: return false
    val devices = JSON.decode(Devices, listObject) ?: return false
    if (!devices.has(dev)) return false
    val data = (payload["data"] as? JsonObject)?.let { LinkedHashMap<String, JsonElement>(it) } ?: return false
    val kv = dumpRows(data["kv"])
    // L'identité doit y être, et valable.
    val row = kv.firstOrNull { it.rowKey() == "identity" } ?: return false
    if (row.size < 2) return false
    val identity = row[1].asString()?.let { JSON.decode(Identity, it) } ?: return false
    if (!identity.isValid || identity.code != code) return false
    data["kv"] = JsonArray(
        kv.filter { !deviceOnly(it.rowKey() ?: "") } +
            listOf(
                JsonArray(listOf(JsonPrimitive("dev"), JsonPrimitive(JSON.encode(dev)))),
                JsonArray(listOf(JsonPrimitive("appareils"), JsonPrimitive(JSON.encode(devices)))),
            ),
    )
    // Un appareil v3 laissé par une liaison d'avant (la garde survit à
    // l'application) : celui-ci en tire un neuf.
    V3Vault.clear()
    into.restore(JsonObject(data))
    return true
}

/**
 * Le nouvel appareil attend d'être lié (§7.12) : il montre `text` (QR
 * code), écoute sa boîte de liaison (une connexion anonyme), ouvre les
 * morceaux qu'y dépose l'appareil du compte, puis range le compte dans
 * `store`. `onDone` : le code de vérification à comparer, ou l'erreur
 * (`LinkError`). Sur un seul fil (`dispatcher`), comme le moteur.
 */
class DeviceLink(
    internal val store: Store,
    server: String,
    dispatcher: CoroutineDispatcher = serialDispatcher(),
    sockets: SocketOpener = OkHttpSockets,
) {
    val secret = TalkaV4.LinkSecret()
    internal val socket = BoxSocket(Relay.wsURL(server), dispatcher, sockets)
    internal var key: ByteArray? = null
    internal var ek = ByteArray(0)
    internal var chunks: Array<ByteArray?> = emptyArray()
    internal var finished = false
    var onDone: ((Result<String>) -> Unit)? = null

    init {
        socket.onEnvelope = { env -> receive(env) }
    }

    /** Ce que montre le QR code (et le bouton « Copier »). */
    val text: String get() = secret.offer.text

    /** L'identifiant de cet appareil. */
    val dev: String get() = secret.offer.dev

    fun start() {
        socket.listen(listOf(secret.offer.boite))
    }

    /** La liaison abandonnée. */
    fun cancel() {
        finished = true
        socket.listen(emptyList())
    }

    internal fun receive(env: JsonObject) {
        env["n"].asInt()?.let { socket.got(listOf(it)) }
        if (finished || env["boite"].asString() != secret.offer.boite) return
        val body = env["body"].asString() ?: return
        val chunk = JSON.parse(body) as? JsonObject ?: return
        val e = chunk["e"].asString()?.let { unb64(it) } ?: return
        if (key == null) {
            key = secret.key(e)
            ek = e
        }
        val k = key ?: return
        if (!ek.contentEquals(e)) return
        val opened = TalkaV4.linkOpen(k, secret.offer.lid, chunk) ?: return
        if (chunks.size != opened.n) chunks = arrayOfNulls(opened.n)
        chunks[opened.i] = opened.plain
        if (chunks.any { it == null }) return
        val bytes = chunks.fold(ByteArray(0)) { acc, part -> acc + (part ?: ByteArray(0)) }
        finished = true
        socket.listen(emptyList())
        if (Engine.importLink(bytes, dev, store)) {
            onDone?.invoke(Result.success(TalkaV4.linkVerification(ek, secret.offer.lk)))
        } else {
            onDone?.invoke(Result.failure(LinkError.Bad))
        }
    }
}
