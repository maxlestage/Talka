// Ce que montrent les widgets (écran d'accueil, et sur la montre ses
// complications) : les messages non lus et les discussions récentes.
//
// L'application l'écrit, les widgets le lisent. Ils n'ont rien d'autre : ni
// clés, ni messages complets, ni accès à la base. Le partage passe par une
// garde que branche l'application (`WidgetShare.storage` : sur l'iPhone,
// le trousseau commun à l'app et à ses widgets).

package talka.kit

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException

data class WidgetData(
    /** Les messages non lus (discussions en sourdine et archivées exclues). */
    val unread: Int = 0,
    /** Les discussions récentes, les non lues d'abord. */
    val chats: List<WidgetChat> = emptyList(),
    /** Un code de verrouillage protège Talka : ni noms ni aperçus, seulement le nombre de messages non lus. */
    val hidden: Boolean = false,
    val at: Double = 0.0,
) : JsonEncodable {
    /** Le texte court des widgets et complications. */
    val summary: String
        get() =
            when (unread) {
                0 -> "Aucun message non lu"
                1 -> "1 message non lu"
                else -> "$unread messages non lus"
            }

    override fun toJson(): JsonObject =
        buildJsonObject {
            put("unread", unread)
            putList("chats", chats)
            put("hidden", hidden)
            put("at", at)
        }

    companion object : JsonDecodable<WidgetData> {
        /** À partir du résumé envoyé à la montre. */
        fun of(s: WatchSnapshot, hidden: Boolean, limit: Int = 4): WidgetData {
            val visible = s.chats.filter { !it.muted }
            val unread = visible.sumOf { it.unread }
            val at = s.chats.maxOfOrNull { it.at } ?: 0.0
            if (hidden) return WidgetData(unread, emptyList(), true, at)
            // Les non lues d'abord, puis les plus récentes (les épinglées n'ont pas
            // de priorité ici : un widget montre ce qui vient d'arriver).
            val chats = s.chats
                .sortedWith { a, b ->
                    val ua = !a.muted && a.unread > 0
                    val ub = !b.muted && b.unread > 0
                    if (ua != ub) (if (ua) -1 else 1) else b.at.compareTo(a.at)
                }
                .take(limit)
                .map { c -> WidgetChat(c.id, c.title, c.initials, c.color, c.group, if (c.muted) 0 else c.unread, c.last, c.at) }
            return WidgetData(unread, chats, false, at)
        }

        /** L'adresse qu'ouvre un toucher : la discussion, ou la liste. */
        fun link(chat: String? = null): String {
            if (chat == null) return "talka://chats"
            return "talka://chat/${percentEncode(chat)}"
        }

        /** La discussion d'une adresse `talka://chat/…` (rien : une autre adresse). */
        fun chat(url: String): String? {
            val u = try {
                URI(url)
            } catch (e: URISyntaxException) {
                return null
            }
            if (u.scheme != "talka" || u.host != "chat") return null
            val id = percentDecode((u.path ?: "").trim('/'))
            return id.ifEmpty { null }
        }

        /** Tout sauf les lettres et les chiffres, en `%XX` (comme `.alphanumerics` du Swift). */
        internal fun percentEncode(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val n = Character.charCount(cp)
                if (Character.isLetterOrDigit(cp)) {
                    out.appendCodePoint(cp)
                } else {
                    for (b in s.substring(i, i + n).toByteArray()) out.append("%%%02X".format(b.toInt() and 0xff))
                }
                i += n
            }
            return out.toString()
        }

        private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        internal fun percentDecode(s: String): String {
            if (!s.contains('%')) return s
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                    out.write(s.substring(i + 1, i + 3).toInt(16))
                    i += 3
                    continue
                }
                out.write(c.toString().toByteArray())
                i += 1
            }
            return utf8(out.toByteArray()) ?: s
        }

        override fun fromJson(json: JsonElement): WidgetData {
            val c = json.obj()
            return WidgetData(c.int("unread"), c.list("chats", WidgetChat), c.bool("hidden"), c.double("at"))
        }
    }
}

data class WidgetChat(
    val id: String,
    val title: String,
    val initials: String,
    val color: Int,
    val group: Boolean,
    val unread: Int,
    val last: String,
    val at: Double,
) : JsonEncodable {
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("title", title)
            put("initials", initials)
            put("color", color)
            put("group", group)
            put("unread", unread)
            put("last", last)
            put("at", at)
        }

    companion object : JsonDecodable<WidgetChat> {
        override fun fromJson(json: JsonElement): WidgetChat {
            val c = json.obj()
            return WidgetChat(
                c.str("id"), c.str("title"), c.str("initials"), c.int("color"), c.bool("group"), c.int("unread"),
                c.str("last"), c.double("at"),
            )
        }
    }
}

/** Là où l'application range ce que lisent ses widgets (sur l'iPhone : un élément du trousseau partagé). */
interface WidgetStorage {
    fun read(): ByteArray?

    /** Écrit ; `false` si refusé. */
    fun write(data: ByteArray): Boolean

    fun clear()
}

/** Le partage entre l'application et ses widgets. */
object WidgetShare {
    /** La garde, branchée par l'application (rien : pas de widgets). */
    @Volatile
    var storage: WidgetStorage? = null

    /**
     * Ce que la garde contient, tel que cette application l'y a lu ou écrit
     * en dernier : l'application demande à écrire toutes les deux secondes,
     * presque toujours la même chose, et la garde n'est alors plus
     * interrogée du tout.
     */
    private var known: ByteArray? = null

    /** Écrit ; `false` si rien n'a changé (inutile de recharger les widgets). */
    @Synchronized
    fun write(data: WidgetData): Boolean {
        val bytes = WatchLink.encode(data)
        if (known?.contentEquals(bytes) == true) return false
        val s = storage
        val old = s?.read()
        if (old != null && old.contentEquals(bytes)) {
            known = bytes
            return false
        }
        // Retenu seulement si la garde l'a pris : sinon on réessaiera.
        known = if (s?.write(bytes) == true) bytes else null
        return true
    }

    fun read(): WidgetData? = WatchLink.decode(WidgetData, storage?.read())

    @Synchronized
    fun clear() {
        known = null
        storage?.clear()
    }
}
