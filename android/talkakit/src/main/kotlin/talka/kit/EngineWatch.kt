// La montre, côté téléphone : le résumé envoyé à la montre et les actions
// qu'elle renvoie (voir WatchLink.kt). Comme `EngineWatch.swift` de
// l'iPhone ; la liaison avec Wear OS viendra avec l'application.

package talka.kit

/**
 * Les discussions récentes pour la montre : les épinglées d'abord, puis
 * les plus récentes, sans les archivées ; les derniers messages de chacune.
 */
fun Engine.watchSnapshot(chats: Int = 20, messages: Int = 15): WatchSnapshot {
    val list = _chats.values
        .filter { !it.archived && (it.last != null || it.group) }
        .sortedWith { a, b ->
            if (a.pinned != b.pinned) {
                if (a.pinned) -1 else 1
            } else {
                maxOf(b.lastAt, b.last?.at ?: 0.0).compareTo(maxOf(a.lastAt, a.last?.at ?: 0.0))
            }
        }
        .take(chats)
    val now = nowMs()
    return WatchSnapshot(
        name = profile.name,
        online = netState == Relay.State.online,
        chats = list.map { c ->
            val title = title(c)
            WatchChat(
                id = c.id,
                title = title,
                initials = watchInitials(title),
                color = watchColor(c.id),
                group = c.group,
                unread = c.unread,
                muted = c.muted,
                pinned = c.pinned,
                readOnly = if (c.group) (c.left || me.code !in c.members) else blocked(c.id),
                at = maxOf(c.lastAt, c.last?.at ?: 0.0),
                last = c.last?.let { l ->
                    if (c.group && l.from != me.code && l.from != "system") "${displayName(l.from)} : ${l.text}" else l.text
                } ?: "",
                messages = messages(c.id, messages)
                    .filter { m -> m.exp?.let { it > now } ?: true }
                    .map { watchMessage(it, c.group) },
            )
        },
    )
}

/**
 * Le résumé le plus complet qui tienne dans le lien avec la montre : moins
 * de messages par discussion, puis moins de discussions, si besoin.
 */
fun Engine.fittedWatchSnapshot(maxBytes: Int = WatchLink.maxSnapshotBytes, adjust: (WatchSnapshot) -> WatchSnapshot = { it }): WatchSnapshot =
    fittedWatch(maxBytes, adjust).first

/**
 * Le même, tel qu'il part vers la montre : il a déjà été encodé pour être
 * mesuré, il ne l'est pas une seconde fois (à chaque message reçu et
 * toutes les deux secondes tant que Talka est ouvert).
 */
fun Engine.fittedWatchData(maxBytes: Int = WatchLink.maxSnapshotBytes, adjust: (WatchSnapshot) -> WatchSnapshot = { it }): ByteArray? =
    fittedWatch(maxBytes, adjust).second

private fun Engine.fittedWatch(maxBytes: Int, adjust: (WatchSnapshot) -> WatchSnapshot): Pair<WatchSnapshot, ByteArray?> {
    val steps = listOf(20 to 15, 20 to 8, 15 to 4, 12 to 2, 12 to 0, 6 to 0)
    var s = WatchSnapshot()
    var data: ByteArray? = null
    var full: WatchSnapshot? = null
    for ((chats, messages) in steps) {
        s = adjust(watchSnapshot(chats, messages))
        if (full == null) {
            // Le résumé complet n'a pas changé : celui qu'on en avait tiré
            // non plus (les suivants n'en sont que des parties), et ses
            // octets sont rendus tels quels, sans réencoder.
            val kept = watchKept
            if (kept != null && kept.full == s && kept.maxBytes == maxBytes) return kept.snapshot to kept.data
            full = s
        }
        data = WatchLink.encode(s)
        if ((data?.size ?: Int.MAX_VALUE) <= maxBytes) break
    }
    if (full != null) watchKept = WatchKept(full, maxBytes, s, data)
    return s to data
}

internal fun Engine.watchMessage(m: Message, group: Boolean): WatchMessage {
    val mine = m.from == me.code
    val system = m.kind == "system" || m.from == "system"
    // Une capsule ouverte (ou écrite par soi) se lit en entier ; scellée,
    // elle ne montre que « ⏳ Capsule temporelle ».
    val text = if (m.kind == "text" && !m.deleted && !m.sealed(me.code)) m.text else Engine.preview(m)
    val counts = m.reactions.values.groupingBy { it }.eachCount().toList()
        .sortedWith { a, b -> if (a.second != b.second) b.second.compareTo(a.second) else compareCodePoints(a.first, b.first) }
    return WatchMessage(
        id = m.id,
        author = if (group && !mine && !system) displayName(m.from) else "",
        mine = mine,
        system = system,
        text = if (text.charCount() > 600) text.prefixChars(600) + "…" else text,
        at = m.at,
        tick = if (mine && !m.deleted) m.tick.name else "",
        reactions = counts.joinToString(" ") { if (it.second > 1) "${it.first}${it.second}" else it.first },
        myReaction = m.reactions[me.code] ?: "",
    )
}

/**
 * Exécute ce que l'on a fait sur la montre. `false` : impossible (la
 * discussion n'existe plus, ou on ne peut plus y écrire).
 */
fun Engine.perform(action: WatchAction): Boolean =
    when (action.kind) {
        WatchAction.Kind.refresh -> true
        WatchAction.Kind.read -> {
            if (_chats[action.chat] == null) {
                false
            } else {
                markRead(action.chat)
                true
            }
        }
        WatchAction.Kind.react -> {
            if (_chats[action.chat] == null || store.message(action.chat, action.id) == null) {
                false
            } else {
                react(action.chat, action.id, action.text)
                true
            }
        }
        WatchAction.Kind.send -> {
            val text = action.text.trimBlanks()
            if (text.isEmpty() || !watchCanWrite(action.chat)) false else send(action.chat, Content(text = text)) != null
        }
    }

/** Un message vocal enregistré sur la montre (AAC, `audio/mp4`). */
fun Engine.sendWatchVoice(chat: String, audio: ByteArray, seconds: Double): Boolean {
    if (!watchCanWrite(chat) || audio.isEmpty() || audio.size > WatchLink.maxVoiceBytes || seconds < 0.5) return false
    val content = Content(
        kind = "voice", data = "data:audio/mp4;base64,${b64(audio)}", mime = "audio/mp4", size = audio.size.toDouble(),
        dur = roundHalfAway(minOf(seconds, WatchLink.maxVoiceSeconds)),
    )
    return send(chat, content) != null
}

/** L'arrondi du Swift (`.rounded()`) : au plus proche, la moitié loin de zéro. */
internal fun roundHalfAway(x: Double): Double = if (x >= 0) Math.floor(x + 0.5) else Math.ceil(x - 0.5)

internal fun Engine.watchCanWrite(id: String): Boolean {
    val c = _chats[id] ?: return false
    return !(if (c.group) (c.left || me.code !in c.members) else blocked(c.id))
}

/** Les initiales d'un nom : « Léa Martin » → « LM », « ~Léa » → « L ». */
internal fun watchInitials(name: String): String {
    val out = StringBuilder()
    var words = 0
    var inWord = false
    var i = 0
    while (i < name.length && words < 2) {
        val cp = name.codePointAt(i)
        val letter = Character.isLetterOrDigit(cp)
        if (letter && !inWord) {
            out.append(String(Character.toChars(cp)).uppercase())
            words += 1
        }
        inWord = letter
        i += Character.charCount(cp)
    }
    return out.toString()
}

/** La couleur d'avatar d'un identifiant (8 couleurs, comme partout ailleurs). */
internal fun watchColor(id: String): Int {
    var h = 0u
    var i = 0
    while (i < id.length) {
        val cp = id.codePointAt(i)
        h = h * 31u + cp.toUInt()
        i += Character.charCount(cp)
    }
    return (h % 8u).toInt()
}
