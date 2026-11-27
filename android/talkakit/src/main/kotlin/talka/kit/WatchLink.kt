// Ce que le téléphone et la montre s'échangent (sur l'iPhone :
// WatchConnectivity ; sur Android : la couche Wear OS que branchera
// l'application).
//
// La montre n'a ni clés ni relais : c'est le téléphone qui reste l'appareil
// Talka. Il envoie à la montre un résumé des discussions récentes
// (`WatchSnapshot`), la montre lui renvoie ce que l'on y fait
// (`WatchAction`) : répondre, réagir, marquer comme lu ; et ses messages
// vocaux, en fichier (avec `voiceMetadata`). Rien d'autre ne passe, et
// seulement entre les deux appareils appairés.
//
// Ce fichier ne dépend que du JSON de Talka : l'application de la montre
// pourra le reprendre tel quel.

package talka.kit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object WatchLink {
    /** Les clés des messages échangés avec la montre. */
    const val snapshotKey = "snapshot"
    const val actionKey = "action"

    /** Un message vocal enregistré sur la montre : les métadonnées du fichier. */
    const val voiceKind = "talka-vocal"

    /** Un vocal : 2 minutes et 1 Mo au plus (AAC mono 32 kbit/s, comme le téléphone). */
    const val maxVoiceSeconds: Double = 120.0
    const val maxVoiceBytes = 1_000_000

    /** Ce que le lien avec la montre accepte sans broncher pour un résumé. */
    const val maxSnapshotBytes = 60_000

    fun voiceMetadata(chat: String, seconds: Double): Map<String, Any> = mapOf("kind" to voiceKind, "chat" to chat, "dur" to seconds)

    /** Un vocal venu de la montre : sa discussion et sa durée. */
    data class Voice(val chat: String, val seconds: Double)

    /** (discussion, durée) d'un fichier venu de la montre, s'il est un vocal. */
    fun voice(metadata: Map<String, Any?>?): Voice? {
        val m = metadata ?: return null
        if (m["kind"] as? String != voiceKind) return null
        val chat = m["chat"] as? String ?: return null
        if (chat.isEmpty()) return null
        val dur = m["dur"] as? Double ?: return null
        if (dur <= 0) return null
        return Voice(chat, minOf(dur, maxVoiceSeconds))
    }

    fun encode(value: JsonEncodable): ByteArray = JSON.encode(value).toByteArray()

    fun <T> decode(type: JsonDecodable<T>, data: Any?): T? {
        val bytes = data as? ByteArray ?: return null
        return utf8(bytes)?.let { JSON.decode(type, it) }
    }
}

/** Les discussions récentes, telles que la montre les affiche. */
data class WatchSnapshot(
    /** Votre nom (votre profil). */
    val name: String = "",
    /** Le téléphone est-il relié au relais Talka ? */
    val online: Boolean = false,
    val chats: List<WatchChat> = emptyList(),
    /**
     * Talka est protégé par un code : les widgets de la montre n'affichent
     * que le nombre de non-lus. (Facultatif : absent des premières versions.)
     */
    val hidden: Boolean? = null,
) : JsonEncodable {
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("name", name)
            put("online", online)
            putList("chats", chats)
            hidden?.let { put("hidden", it) }
        }

    companion object : JsonDecodable<WatchSnapshot> {
        override fun fromJson(json: JsonElement): WatchSnapshot {
            val c = json.obj()
            if (c["chats"] != null && c["chats"] !is JsonArray) throw JSONError("chats")
            return WatchSnapshot(c.str("name"), c.bool("online"), c.list("chats", WatchChat), c.boolOrNull("hidden"))
        }
    }
}

data class WatchChat(
    val id: String = "",
    val title: String = "",
    /** Les initiales de l'avatar (pas de photo : la montre reste légère). */
    val initials: String = "",
    /** La couleur de l'avatar : la même que sur le téléphone et dans le navigateur. */
    val color: Int = 0,
    val group: Boolean = false,
    val unread: Int = 0,
    val muted: Boolean = false,
    val pinned: Boolean = false,
    /** Envoi impossible : contact bloqué, ou groupe quitté. */
    val readOnly: Boolean = false,
    val at: Double = 0.0,
    val last: String = "",
    /** Les derniers messages, du plus ancien au plus récent. */
    val messages: List<WatchMessage> = emptyList(),
) : JsonEncodable {
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("title", title)
            put("initials", initials)
            put("color", color)
            put("group", group)
            put("unread", unread)
            put("muted", muted)
            put("pinned", pinned)
            put("readOnly", readOnly)
            put("at", at)
            put("last", last)
            putList("messages", messages)
        }

    companion object : JsonDecodable<WatchChat> {
        override fun fromJson(json: JsonElement): WatchChat {
            val c = json.obj()
            return WatchChat(
                c.str("id"), c.str("title"), c.str("initials"), c.int("color"), c.bool("group"), c.int("unread"),
                c.bool("muted"), c.bool("pinned"), c.bool("readOnly"), c.double("at"), c.str("last"),
                c.list("messages", WatchMessage),
            )
        }
    }
}

data class WatchMessage(
    val id: String = "",
    /** Le nom de l'auteur, dans un groupe (vide sinon). */
    val author: String = "",
    val mine: Boolean = false,
    val system: Boolean = false,
    val text: String = "",
    val at: Double = 0.0,
    /** Pour vos messages : `pending`, `sent`, `delivered`, `read`, `failed`. */
    val tick: String = "",
    /** Les réactions, regroupées (« ❤️2 👍 »). */
    val reactions: String = "",
    /** Votre réaction à ce message (vide : aucune). */
    val myReaction: String = "",
) : JsonEncodable {
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("author", author)
            put("mine", mine)
            put("system", system)
            put("text", text)
            put("at", at)
            put("tick", tick)
            put("reactions", reactions)
            put("myReaction", myReaction)
        }

    companion object : JsonDecodable<WatchMessage> {
        override fun fromJson(json: JsonElement): WatchMessage {
            val c = json.obj()
            return WatchMessage(
                c.str("id"), c.str("author"), c.bool("mine"), c.bool("system"), c.str("text"), c.double("at"),
                c.str("tick"), c.str("reactions"), c.str("myReaction"),
            )
        }
    }
}

/** Ce que l'on fait sur la montre ; le téléphone l'exécute. */
data class WatchAction(
    val kind: Kind,
    val chat: String = "",
    val id: String = "",
    val text: String = "",
) : JsonEncodable {
    enum class Kind {
        /** Envoyer `text` dans `chat`. */
        send,

        /** Réagir (`text` : l'emoji ; le même une deuxième fois la retire). */
        react,

        /** Marquer `chat` comme lu. */
        read,

        /** Demander un résumé à jour. */
        refresh,
    }

    override fun toJson(): JsonObject =
        buildJsonObject {
            put("kind", kind.name)
            put("chat", chat)
            put("id", id)
            put("text", text)
        }

    companion object : JsonDecodable<WatchAction> {
        override fun fromJson(json: JsonElement): WatchAction {
            val c = json.obj()
            val kind = Kind.entries.firstOrNull { it.name == c.reqStr("kind") } ?: throw JSONError("kind")
            return WatchAction(kind, c.str("chat"), c.str("id"), c.str("text"))
        }
    }
}

/** Les réponses rapides proposées sur la montre. */
val watchQuickReplies = listOf("OK", "J'arrive !", "Merci !", "Je te rappelle", "Oui", "Non")
val watchReactions = listOf("❤️", "👍", "😂", "😮", "😢", "🙏")
