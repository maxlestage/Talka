// Ce que garde l'appareil et ce qui voyage, chiffré, entre appareils : le
// même JSON que le navigateur (`client/src/model.rs`) et l'iPhone
// (`Model.swift`). Un champ absent prend sa valeur par défaut, comme
// `#[serde(default)]` ; à l'écriture, les mêmes champs que l'iPhone sont omis.
//
// Les types sont des valeurs (comme les `struct` du Swift) : on les change
// avec `copy`.

package talka.kit

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.stream.IntStream

// ----- Petits outils de texte ----------------------------------------------------

/** Les `n` premiers caractères (points de code, comme les `char` du navigateur). */
internal fun String.prefixChars(n: Int): String {
    if (n <= 0) return ""
    var i = 0
    var count = 0
    while (i < length && count < n) {
        i += Character.charCount(codePointAt(i))
        count++
    }
    return substring(0, i)
}

internal fun String.charCount(): Int = codePointCount(0, length)

/** Un blanc, au sens d'Unicode (comme `Character.isWhitespace` du Swift). */
internal fun isWhite(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

// ----- Profil et réglages ------------------------------------------------------

data class Profile(
    val name: String = "",
    val about: String = "",
    val avatar: String = "",
    val v: Long = 0,
) : JsonEncodable {
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("name", name)
            put("about", about)
            put("avatar", avatar)
            put("v", v)
        }

    companion object : JsonDecodable<Profile> {
        override fun fromJson(json: JsonElement): Profile {
            val c = json.obj()
            return Profile(c.str("name"), c.str("about"), c.str("avatar"), c.long("v"))
        }
    }
}

/** Par défaut, les messages s'effacent au bout de 3 mois (comme le navigateur). */
const val keepDaysDefault = 90

/** Le titre et le texte d'une notification. */
data class NotificationText(val title: String, val body: String)

data class Settings(
    /** `auto`, `light` ou `dark`. */
    val theme: String = "auto",
    val readReceipts: Boolean = true,
    val hideOnline: Boolean = false,
    /** La pastille verte des contacts en ligne (grise hors ligne). */
    val presenceDot: Boolean = true,
    val notify: Boolean = false,
    val sound: Boolean = true,
    val enterSends: Boolean = true,
    val wallpaper: String = "doodle",
    val fontSize: Int = 15,
    /** Verrouillage automatique : secondes passées hors de Talka. */
    val lockAfter: Int = 0,
    /**
     * Autrefois, l'adresse d'un autre relais ; relue des anciens réglages,
     * jamais utilisée : il n'y a plus qu'un relais (`TalkaServer.official`).
     */
    val server: String = "",
    /**
     * Les messages plus vieux que ça (en jours) s'effacent de l'appareil,
     * sauf les messages importants (⭐). 0 : gardés pour toujours.
     */
    val keepDays: Int = keepDaysDefault,
    /**
     * Ce que montrent les notifications : `all` (le nom et le message),
     * `name` (le nom seulement), `none` (rien : « Nouveau message »).
     */
    val notifyPreview: String = "all",
    /**
     * Une discussion archivée le reste quand un message y arrive (comme
     * « Garder les discussions archivées » de WhatsApp).
     */
    val keepArchived: Boolean = false,
) : JsonEncodable {
    /**
     * Le titre et le texte d'une notification, selon `notifyPreview`
     * (comme `Settings::notification` du navigateur).
     */
    fun notification(title: String, body: String): NotificationText =
        when (notifyPreview) {
            "none" -> NotificationText("Talka", "Nouveau message")
            "name" -> NotificationText(title, "Nouveau message")
            else -> NotificationText(title, body)
        }

    override fun toJson(): JsonObject =
        buildJsonObject {
            put("theme", theme)
            put("read_receipts", readReceipts)
            put("hide_online", hideOnline)
            put("presence_dot", presenceDot)
            put("notify", notify)
            put("sound", sound)
            put("enter_sends", enterSends)
            put("wallpaper", wallpaper)
            put("font_size", fontSize)
            put("lock_after", lockAfter)
            put("server", server)
            put("keep_days", keepDays)
            put("notify_preview", notifyPreview)
            put("keep_archived", keepArchived)
        }

    companion object : JsonDecodable<Settings> {
        override fun fromJson(json: JsonElement): Settings {
            val c = json.obj()
            val d = Settings()
            return Settings(
                theme = c.str("theme", d.theme),
                readReceipts = c.bool("read_receipts", d.readReceipts),
                hideOnline = c.bool("hide_online", d.hideOnline),
                presenceDot = c.bool("presence_dot", d.presenceDot),
                notify = c.bool("notify", d.notify),
                sound = c.bool("sound", d.sound),
                enterSends = c.bool("enter_sends", d.enterSends),
                wallpaper = c.str("wallpaper", d.wallpaper),
                fontSize = c.int("font_size", d.fontSize),
                lockAfter = c.int("lock_after", d.lockAfter),
                server = c.str("server", d.server),
                keepDays = c.int("keep_days", d.keepDays),
                notifyPreview = c.str("notify_preview", d.notifyPreview),
                keepArchived = c.bool("keep_archived", d.keepArchived),
            )
        }
    }
}

// ----- Contacts ------------------------------------------------------------------

data class Contact(
    val code: String,
    /** Le nom que vous lui donnez. */
    val name: String = "",
    /** Le nom qu'il s'est choisi (affiché `~Nom`). */
    val profileName: String = "",
    val about: String = "",
    val avatar: String = "",
    val sign: String = "",
    val dh: String = "",
    val blocked: Boolean = false,
    /**
     * Ses actus en sourdine, sur cet appareil (comme WhatsApp) : rangées
     * sous « Masquées », sans compter comme nouvelles.
     */
    val statusMuted: Boolean = false,
    val saved: Boolean = false,
    val addedAt: Double = nowMs(),
    val profileV: Long = 0,
    val sentProfileV: Long = 0,
    /**
     * Son jeton de remise (dans son profil) : pour lui écrire en expéditeur
     * scellé, sans que le relais sache qui écrit. Vide : pas encore reçu.
     */
    val jeton: String = "",
    /** Lui a-t-on donné le nôtre ? */
    val jetonSent: Boolean = false,
    /** A-t-il reçu son jeton personnel (`Identity.jeton(contact, epoch)`) ? */
    val jetonPerso: Boolean = false,
    /** L'époque de son jeton personnel : elle avance quand on le bloque. */
    val jetonEpoch: Int = 0,
    /**
     * Sa version chiffre tout en v3, pièces jointes comprises, et n'envoie
     * plus l'ancien format (hors signaux en direct).
     */
    val pj: Boolean = false,
    /**
     * Sa clé de notification (navigateur) : l'aperçu de ses notifications
     * lui est chiffré pour elle. Vide : un simple réveil.
     */
    val nk: String = "",
    /**
     * L'empreinte de ses clés donnée par son lien d'invitation : les clés
     * trouvées pour son code doivent y correspondre.
     */
    val fp: String = "",
    /**
     * Il nous a déjà écrit en v3 : l'ancien format, que le relais pourrait
     * rejouer, ne change plus rien chez nous.
     */
    val v3: Boolean = false,
    /**
     * Ses appareils (v4), tels qu'il les annonce : on écrit à chacun. Vide :
     * un seul appareil, d'avant la v4.
     */
    val devs: List<DevInfo> = emptyList(),
    /** La version de cette liste (la plus grande l'emporte). */
    val dv: Long = 0,
    /** Il lit ses boîtes aux lettres tournantes (v4) : on lui écrit là. */
    val bx: Boolean = false,
) : JsonEncodable {
    val id: String get() = code

    /** Comme le navigateur : le jeton n'est écrit que s'il y en a un. */
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("code", code)
            put("name", name)
            put("profile_name", profileName)
            put("about", about)
            put("avatar", avatar)
            put("sign", sign)
            put("dh", dh)
            put("blocked", blocked)
            put("saved", saved)
            put("added_at", addedAt)
            put("profile_v", profileV)
            put("sent_profile_v", sentProfileV)
            if (jeton.isNotEmpty()) put("jeton", jeton)
            if (jetonSent) put("jeton_sent", true)
            if (jetonPerso) put("jeton_perso", true)
            if (jetonEpoch != 0) put("jeton_epoch", jetonEpoch)
            if (pj) put("pj", true)
            if (nk.isNotEmpty()) put("nk", nk)
            if (fp.isNotEmpty()) put("fp", fp)
            if (v3) put("v3", true)
            if (devs.isNotEmpty()) putList("devs", devs)
            if (dv != 0L) put("dv", dv)
            if (bx) put("bx", true)
            if (statusMuted) put("status_muted", true)
        }

    companion object : JsonDecodable<Contact> {
        override fun fromJson(json: JsonElement): Contact {
            val c = json.obj()
            return Contact(
                code = c.str("code"), name = c.str("name"), profileName = c.str("profile_name"),
                about = c.str("about"), avatar = c.str("avatar"), sign = c.str("sign"), dh = c.str("dh"),
                blocked = c.bool("blocked"), statusMuted = c.bool("status_muted"), saved = c.bool("saved"),
                addedAt = c.double("added_at"), profileV = c.long("profile_v"), sentProfileV = c.long("sent_profile_v"),
                jeton = c.str("jeton"), jetonSent = c.bool("jeton_sent"), jetonPerso = c.bool("jeton_perso"),
                jetonEpoch = c.int("jeton_epoch"), pj = c.bool("pj"), nk = c.str("nk"), fp = c.str("fp"),
                v3 = c.bool("v3"), devs = c.list("devs", DevInfo), dv = c.long("dv"), bx = c.bool("bx"),
            )
        }
    }
}

// ----- Plusieurs appareils (v4, `docs/SECURITE.md` §7.11) -----

/**
 * Un appareil d'un contact (v4) : son identifiant, et sa clé de
 * notification (l'aperçu de ses notifications lui est chiffré).
 */
data class DevInfo(val d: String, val nk: String = "") : JsonEncodable {
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("d", d)
            if (nk.isNotEmpty()) put("nk", nk)
        }

    companion object : JsonDecodable<DevInfo> {
        override fun fromJson(json: JsonElement): DevInfo {
            val c = json.obj()
            return DevInfo(c.str("d"), c.str("nk"))
        }
    }
}

/** Un appareil de notre compte (v4, `kv/appareils`). */
data class AccountDevice(
    val dev: String,
    /** « iPhone », « Chrome sur Mac »… */
    val name: String = "",
    /** Quand il a été lié. */
    val at: Double = 0.0,
    /** Sa clé de notification. */
    val nk: String = "",
) : JsonEncodable {
    val id: String get() = dev

    override fun toJson(): JsonObject =
        buildJsonObject {
            put("dev", dev)
            put("name", name)
            put("at", at)
            if (nk.isNotEmpty()) put("nk", nk)
        }

    companion object : JsonDecodable<AccountDevice> {
        override fun fromJson(json: JsonElement): AccountDevice {
            val c = json.obj()
            return AccountDevice(c.str("dev"), c.str("name"), c.double("at"), c.str("nk"))
        }
    }
}

/** Six appareils au plus par compte. */
const val maxDevices = 6

/** Les seize derniers appareils retirés sont retenus. */
const val maxGone = 16

/**
 * Les appareils de notre compte (v4) : six au plus ; `v` avance à chaque
 * changement, la plus grande l'emporte.
 */
data class Devices(
    val v: Long = 0,
    val list: List<AccountDevice> = emptyList(),
    /**
     * Les appareils retirés, les plus récents d'abord. Le relais ne garde
     * rien sur disque : chacun de nos appareils les lui redit à chaque
     * connexion, pour qu'un appareil retiré pendant qu'il était hors ligne
     * reste refusé (et efface tout à son retour), même après un redémarrage.
     */
    val gone: List<String> = emptyList(),
) : JsonEncodable {
    fun has(dev: String): Boolean = list.any { it.dev == dev }

    /**
     * Retire `dev` du compte : il quitte la liste et rejoint les appareils
     * retirés (la nouvelle liste).
     */
    fun remove(dev: String): Devices =
        Devices(
            v = v + 1,
            list = list.filter { it.dev != dev },
            gone = (listOf(dev) + gone.filter { it != dev }).take(maxGone),
        )

    /** Ce que voient les contacts (dans le profil). */
    val announced: List<DevInfo> get() = list.map { DevInfo(it.dev, it.nk) }

    /**
     * La liste reçue d'un autre de nos appareils : gardée si elle est plus
     * récente (et bien formée). Rien : on garde la nôtre.
     */
    fun merge(other: Devices): Devices? {
        if (other.v <= v || other.list.size > maxDevices || other.gone.size > maxGone ||
            !other.list.all { TalkaV4.isDev(it.dev) } || !other.gone.all(TalkaV4::isDev)
        ) {
            return null
        }
        return other
    }

    override fun toJson(): JsonObject =
        buildJsonObject {
            put("v", v)
            putList("list", list)
            if (gone.isNotEmpty()) put("gone", gone)
        }

    companion object : JsonDecodable<Devices> {
        override fun fromJson(json: JsonElement): Devices {
            val c = json.obj()
            return Devices(c.long("v"), c.list("list", AccountDevice), c.strings("gone"))
        }
    }
}

// ----- Discussions ---------------------------------------------------------------

data class Last(
    val id: String = "",
    val from: String = "",
    val text: String = "",
    val at: Double = 0.0,
    val kind: String = "",
) : JsonEncodable {
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("from", from)
            put("text", text)
            put("at", at)
            put("kind", kind)
        }

    companion object : JsonDecodable<Last> {
        override fun fromJson(json: JsonElement): Last {
            val c = json.obj()
            return Last(c.str("id"), c.str("from"), c.str("text"), c.double("at"), c.str("kind"))
        }
    }
}

data class Chat(
    /** Le code du contact, ou `g:<id>` pour un groupe. */
    val id: String,
    val group: Boolean = false,
    val name: String = "",
    val avatar: String = "",
    val members: List<String> = emptyList(),
    val admins: List<String> = emptyList(),
    /** La description d'un groupe (ce qu'en disent ses admins). */
    val desc: String = "",
    /** Dans ce groupe, seuls les admins envoient des messages. */
    val adminsOnly: Boolean = false,
    val createdBy: String = "",
    val createdAt: Double = nowMs(),
    val lastAt: Double = createdAt,
    val unread: Int = 0,
    val markedUnread: Boolean = false,
    /** On nous y a mentionné depuis la dernière lecture (« @ » dans la liste). */
    val mentioned: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    /** Messages éphémères : leur durée de vie en secondes (0 : désactivés). */
    val timer: Long = 0,
    val draft: String = "",
    val wallpaper: String = "",
    val left: Boolean = false,
    val v: Long = 0,
    val last: Last? = null,
    val hasExp: Boolean = false,
    /** Le message épinglé en haut de la discussion, pour soi (vide : aucun). */
    val pinMsg: String = "",
    /**
     * Les clés Brocolis 🥦 (leurs `id`) qui chiffrent ce qui part dans cette
     * discussion, en plus de Talka. Vide : désactivé.
     */
    val brocolis: List<String> = emptyList(),
    /**
     * Sourdine pour un temps : l'heure (ms) où elle se lève d'elle-même
     * (absente : pour toujours, tant que `muted`).
     */
    val muteUntil: Double? = null,
) : JsonEncodable {
    /**
     * Un message de `from` arrive : la discussion sort-elle des archives ?
     * Pas pour nos propres messages, ni en sourdine, ni si l'on garde les
     * discussions archivées. Comme `Chat::unarchives_on` du navigateur.
     */
    fun unarchivesOn(from: String, me: String, keepArchived: Boolean): Boolean =
        archived && from != me && !muted && !keepArchived

    /**
     * Un groupe ne change que par ses admins ; un membre peut seulement le
     * quitter (et, s'il était le dernier admin, le premier membre restant le
     * devient). Une version ne saute pas de plus de 1 000. Comme
     * `Chat::accepts` du navigateur.
     */
    fun accepts(from: String, grp: GroupInfo): Boolean {
        if (!group || grp.v <= v || grp.v - v > groupVersionStep || from !in members) return false
        if (from in admins) return true
        val rest = members.filter { it != from }
        var newAdmins = admins.filter { it != from }
        if (newAdmins.isEmpty() && rest.isNotEmpty()) newAdmins = listOf(rest.first())
        return grp.members == rest && grp.admins == newAdmins && grp.name == name && grp.avatar == avatar &&
            (grp.desc?.let { groupDesc(it) == desc } ?: true) &&
            (grp.adminsOnly?.let { it == adminsOnly } ?: true)
    }

    /**
     * `me` peut-il écrire ici ? Pas dans un groupe quitté, ni, quand seuls
     * les admins écrivent, sans en être un. Comme `Chat::can_write`.
     */
    fun canWrite(me: String): Boolean = !group || (!left && (!adminsOnly || me in admins))

    override fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("group", group)
            put("name", name)
            put("avatar", avatar)
            put("members", members)
            put("admins", admins)
            put("desc", desc)
            put("admins_only", adminsOnly)
            put("created_by", createdBy)
            put("created_at", createdAt)
            put("last_at", lastAt)
            put("unread", unread)
            put("marked_unread", markedUnread)
            put("mentioned", mentioned)
            put("pinned", pinned)
            put("archived", archived)
            put("muted", muted)
            muteUntil?.let { put("mute_until", it) }
            put("timer", timer)
            put("draft", draft)
            put("wallpaper", wallpaper)
            put("left", left)
            put("v", v)
            last?.let { put("last", it.toJson()) }
            put("has_exp", hasExp)
            put("pin_msg", pinMsg)
            put("brocolis", brocolis)
        }

    companion object : JsonDecodable<Chat> {
        const val groupVersionStep = 1_000

        /**
         * Les groupes que l'on partage avec `code` (fiche du contact, comme
         * WhatsApp) : ceux dont nous sommes membres tous les deux, par nom. Le
         * même que le navigateur (`model::groups_in_common`).
         */
        fun groupsInCommon(chats: List<Chat>, me: String, code: String): List<Chat> =
            chats.filter { it.group && !it.left && me in it.members && code in it.members }
                .sortedBy { it.name.lowercase() }

        override fun fromJson(json: JsonElement): Chat {
            val c = json.obj()
            return Chat(
                id = c.str("id"), group = c.bool("group"), name = c.str("name"), avatar = c.str("avatar"),
                members = c.strings("members"), admins = c.strings("admins"), desc = c.str("desc"),
                adminsOnly = c.bool("admins_only"), createdBy = c.str("created_by"),
                createdAt = c.double("created_at"), lastAt = c.double("last_at"), unread = c.int("unread"),
                markedUnread = c.bool("marked_unread"), mentioned = c.bool("mentioned"), pinned = c.bool("pinned"),
                archived = c.bool("archived"), muted = c.bool("muted"), timer = c.long("timer"),
                draft = c.str("draft"), wallpaper = c.str("wallpaper"), left = c.bool("left"), v = c.long("v"),
                last = c.of("last", Last), hasExp = c.bool("has_exp"), pinMsg = c.str("pin_msg"),
                brocolis = c.strings("brocolis"), muteUntil = c.doubleOrNull("mute_until"),
            )
        }
    }
}

/** Une clé de Brocolis 🥦, partagée avec ses proches dans l'app Brocolis. */
data class BrocolisKey(
    val id: String = "",
    val name: String = "",
    /** 32 octets en base64. */
    val key: String = "",
    /**
     * Remplacée par une clé neuve : elle ouvre encore les anciens messages,
     * mais ne chiffre plus rien.
     */
    val retired: Boolean = false,
) : JsonEncodable {
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("name", name)
            put("key", key)
            put("retired", retired)
        }

    companion object : JsonDecodable<BrocolisKey> {
        override fun fromJson(json: JsonElement): BrocolisKey {
            val c = json.obj()
            return BrocolisKey(c.str("id"), c.str("name"), c.str("key"), c.bool("retired"))
        }
    }
}

data class Reply(val id: String = "", val from: String = "", val text: String = "") : JsonEncodable {
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("from", from)
            put("text", text)
        }

    companion object : JsonDecodable<Reply> {
        override fun fromJson(json: JsonElement): Reply {
            val c = json.obj()
            return Reply(c.str("id"), c.str("from"), c.str("text"))
        }
    }
}

/** Le contenu d'un message : ce qui est commun à l'envoi et au stockage. */
data class Content(
    /** `text`, `image`, `voice`, `file`, `contact`. */
    val kind: String = "text",
    val text: String = "",
    /** Une photo, un vocal ou un fichier : une adresse `data:`. */
    val data: String = "",
    val name: String = "",
    val mime: String = "",
    val size: Double = 0.0,
    val dur: Double = 0.0,
    val w: Int = 0,
    val h: Int = 0,
    /** Une carte de contact : son code. */
    val code: String = "",
    val reply: Reply? = null,
    val fwd: Boolean = false,
    /**
     * Une capsule temporelle : à ouvrir à cette heure (ms). Absent sinon ;
     * les versions qui ne la connaissent pas affichent le message.
     */
    val openAt: Double? = null,
    /** Dans un groupe : les membres mentionnés (« @Léa »), par leur code. */
    val mentions: List<String> = emptyList(),
) : JsonEncodable {
    /**
     * Seulement ce qui n'est pas vide (le navigateur complète avec les
     * valeurs par défaut).
     */
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("kind", kind)
            if (text.isNotEmpty()) put("text", text)
            if (data.isNotEmpty()) put("data", data)
            if (name.isNotEmpty()) put("name", name)
            if (mime.isNotEmpty()) put("mime", mime)
            if (size != 0.0) put("size", size)
            if (dur != 0.0) put("dur", dur)
            if (w != 0) put("w", w)
            if (h != 0) put("h", h)
            if (code.isNotEmpty()) put("code", code)
            reply?.let { put("reply", it.toJson()) }
            if (fwd) put("fwd", true)
            openAt?.let { put("open_at", it) }
            if (mentions.isNotEmpty()) put("mentions", mentions)
        }

    companion object : JsonDecodable<Content> {
        override fun fromJson(json: JsonElement): Content {
            val c = json.obj()
            return Content(
                kind = c.str("kind", "text"), text = c.str("text"), data = c.str("data"), name = c.str("name"),
                mime = c.str("mime"), size = c.double("size"), dur = c.double("dur"), w = c.int("w"), h = c.int("h"),
                code = c.str("code"), reply = c.of("reply", Reply), fwd = c.bool("fwd"),
                openAt = c.doubleOrNull("open_at"), mentions = c.strings("mentions"),
            )
        }
    }
}

/** Un lien d'une discussion, avec le message qui le contient (fiche). */
data class ChatLink(val url: String, val message: Message) {
    val id: String get() = url
}

/**
 * Ce dont la recherche a besoin d'un message (voir `Message.matching`) :
 * le message entier, ou sa seule ligne de texte (`Message.Line`).
 */
interface SearchLine {
    val from: String
    val kind: String
    val deleted: Boolean
    val text: String
    val name: String
    val openAt: Double?
}

/** Ce que trouve la recherche, et l'heure où la prochaine capsule s'ouvrira. */
internal data class Matching<T>(val found: List<T>, val nextOpen: Double)

data class Message(
    val chat: String = "",
    val id: String = "",
    /** Le code de l'auteur, ou `system`. */
    override val from: String = "",
    val at: Double = 0.0,
    /** `text`, `image`, `voice`, `file`, `contact`, `system`. */
    override val kind: String = "text",
    override val text: String = "",
    val data: String = "",
    override val name: String = "",
    val mime: String = "",
    val size: Double = 0.0,
    val dur: Double = 0.0,
    val w: Int = 0,
    val h: Int = 0,
    val code: String = "",
    val reply: Reply? = null,
    val fwd: Boolean = false,
    /** `pending`, `sent`, `failed` (les accusés sont calculés). */
    val status: String = "",
    val to: List<String> = emptyList(),
    val sentTo: List<String> = emptyList(),
    val deliveredTo: List<String> = emptyList(),
    val readBy: List<String> = emptyList(),
    val reactions: Map<String, String> = emptyMap(),
    val edited: Boolean = false,
    override val deleted: Boolean = false,
    val starred: Boolean = false,
    val exp: Double? = null,
    val readByMe: Boolean = false,
    /** Une capsule temporelle : scellée jusqu'à cette heure (ms). */
    override val openAt: Double? = null,
    /** Arrivé ou parti chiffré par Brocolis 🥦 (et ouvert sur cet appareil). */
    val bro: Boolean = false,
    /** Les membres mentionnés (« @Léa »), par leur code. */
    val mentions: List<String> = emptyList(),
) : JsonEncodable, SearchLine {
    constructor(chat: String, id: String, from: String, at: Double, content: Content) : this(
        chat = chat, id = id, from = from, at = at,
        kind = content.kind, text = content.text, data = content.data, name = content.name, mime = content.mime,
        size = content.size, dur = content.dur, w = content.w, h = content.h, code = content.code,
        reply = content.reply, fwd = content.fwd, openAt = content.openAt, mentions = content.mentions,
    )

    /**
     * Important (⭐), et pas supprimé pour tous : un message supprimé n'a
     * plus rien à garder (comme WhatsApp et le navigateur).
     */
    val isStarred: Boolean get() = starred && !deleted

    /**
     * Trop vieux pour être gardé (`keepDays` jours, 0 : jamais) ? Les
     * messages importants (⭐) restent. Une capsule temporelle compte à
     * partir de son ouverture (sinon elle partirait avant de s'ouvrir).
     */
    fun tooOld(keepDays: Int, now: Double): Boolean = tooOld(at, openAt, isStarred, keepDays, now)

    /**
     * La ligne sous le nom d'un document : « 12 Ko · PDF », ou la taille
     * seule quand le nom n'a pas d'extension (« README », « .profile »).
     * `size` est déjà mise en forme. Le même que le navigateur
     * (`Message::file_detail`).
     */
    fun fileDetail(size: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return size
        val ext = name.substring(dot + 1)
        if (ext.isEmpty() || ext.length > 8 || !ext.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return size
        return "$size · ${ext.uppercase()}"
    }

    /**
     * Le nom d'une pièce jointe enregistrée (« Enregistrer ») : le sien, ou
     * `photo-talka` / `vocal-talka` / `fichier-talka` avec l'extension de son
     * vrai type. Le même que le navigateur (`Message::save_name`).
     */
    val saveName: String
        get() {
            if (name.isNotEmpty()) return name
            var type = mime
            if (type.isEmpty() && data.startsWith("data:")) {
                type = data.substring(5).takeWhile { it != ';' && it != ',' }
            }
            type = type.takeWhile { it != ';' }.trim { it == ' ' || it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }.lowercase()
            val base = when (kind) {
                "image" -> "photo-talka"
                "voice" -> "vocal-talka"
                else -> "fichier-talka"
            }
            val ext = when (type) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                "image/heic" -> "heic"
                "audio/webm" -> "webm"
                "audio/ogg" -> "ogg"
                "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "m4a"
                "audio/mpeg" -> "mp3"
                else -> when (kind) {
                    "image" -> "jpg"
                    "voice" -> "webm"
                    else -> return base
                }
            }
            return "$base.$ext"
        }

    /** Une capsule temporelle (scellée ou déjà ouverte). */
    val isCapsule: Boolean get() = (openAt ?: 0.0) > 0

    /**
     * Une capsule encore scellée pour son destinataire ; l'auteur, lui,
     * voit toujours ce qu'il a écrit.
     */
    fun sealed(me: String, now: Double = nowMs()): Boolean = (openAt ?: 0.0) > now && from != me

    /**
     * L'œuf de Pâques 🥦, comme dans le navigateur : un texte qui contient
     * 🥦 ou « brocoli » fait pleuvoir des brocolis. Une capsule scellée ne
     * se trahit pas.
     */
    fun summonsBrocoli(me: String, now: Double = nowMs()): Boolean {
        if (kind != "text" || deleted || sealed(me, now)) return false
        // Pas un jeton Brocolis resté scellé : il ne dit rien de ce qu'il cache.
        val text = Brocolis.masked(text).replace("🔒🥦", "")
        return text.contains("🥦") || Mentions.fold(text).contains("brocoli")
    }

    /** `chat|id` : la clé dans le stockage. */
    val key: String get() = "$chat|$id"

    val content: Content
        get() = Content(kind, text, data, name, mime, size, dur, w, h, code, reply, fwd, openAt, mentions)

    enum class Tick { pending, sent, delivered, read, failed }

    /** L'état d'un message envoyé. */
    val tick: Tick
        get() = when {
            status == "failed" -> Tick.failed
            to.isEmpty() -> Tick.sent
            to.all { it in readBy } -> Tick.read
            to.all { it in deliveredTo } -> Tick.delivered
            to.all { it in sentTo } -> Tick.sent
            else -> Tick.pending
        }

    override fun toJson(): JsonObject =
        buildJsonObject {
            put("chat", chat)
            put("id", id)
            put("from", from)
            put("at", at)
            put("kind", kind)
            put("text", text)
            put("data", data)
            put("name", name)
            put("mime", mime)
            put("size", size)
            put("dur", dur)
            put("w", w)
            put("h", h)
            put("code", code)
            reply?.let { put("reply", it.toJson()) }
            put("fwd", fwd)
            put("status", status)
            put("to", to)
            put("sent_to", sentTo)
            put("delivered_to", deliveredTo)
            put("read_by", readBy)
            putStringMap("reactions", reactions)
            put("edited", edited)
            put("deleted", deleted)
            put("starred", starred)
            exp?.let { put("exp", it) }
            put("read_by_me", readByMe)
            openAt?.let { put("open_at", it) }
            put("bro", bro)
            put("mentions", mentions)
        }

    /**
     * L'âge d'un message, lu sans le reste : le ménage des vieux messages
     * n'a besoin ni de leur texte ni de leurs photos pour les effacer.
     */
    internal data class Age(val id: String, val at: Double, val openAt: Double?, val starred: Boolean) {
        constructor(m: Message) : this(m.id, m.at, m.openAt, m.isStarred)

        fun tooOld(keepDays: Int, now: Double): Boolean = tooOld(at, openAt, starred, keepDays, now)

        companion object : JsonDecodable<Age> {
            override fun fromJson(json: JsonElement): Age {
                val c = json.obj()
                return Age(c.str("id"), c.double("at"), c.doubleOrNull("open_at"), c.bool("starred") && !c.bool("deleted"))
            }
        }
    }

    /**
     * L'échéance d'un message éphémère, lue sans le reste (son texte, sa
     * photo) : le ménage de chaque minute n'a besoin que d'elle.
     */
    internal data class Expiry(val id: String, val at: Double, val exp: Double?) {
        companion object : JsonDecodable<Expiry> {
            override fun fromJson(json: JsonElement): Expiry {
                val c = json.obj()
                return Expiry(c.str("id"), c.double("at"), c.doubleOrNull("exp"))
            }
        }
    }

    /**
     * Ce que la recherche lit d'un message : son texte et de quoi dire s'il
     * compte, sans le reste (ses photos surtout). La recherche dans toutes
     * les discussions garde ces lignes, pas les messages.
     */
    internal data class Line(
        val chat: String,
        val id: String,
        val at: Double,
        override val from: String,
        override val kind: String,
        override val deleted: Boolean,
        override val text: String,
        override val name: String,
        override val openAt: Double?,
    ) : SearchLine {
        constructor(m: Message) : this(m.chat, m.id, m.at, m.from, m.kind, m.deleted, m.text, m.name, m.openAt)

        companion object : JsonDecodable<Line> {
            override fun fromJson(json: JsonElement): Line {
                val c = json.obj()
                return Line(
                    c.str("chat"), c.str("id"), c.double("at"), c.str("from"), c.str("kind", "text"),
                    c.bool("deleted"), c.str("text"), c.str("name"), c.doubleOrNull("open_at"),
                )
            }
        }
    }

    /**
     * Ce que pèse la pièce jointe d'un message, lu sans le reste : « Gérer
     * le stockage » additionne toutes les discussions, et n'a besoin ni des
     * textes ni de garder les photos.
     */
    internal data class Weight(val id: String, val piece: Storage.Piece?) {
        companion object : JsonDecodable<Weight> {
            override fun fromJson(json: JsonElement): Weight {
                val c = json.obj()
                val kind = c.str("kind", "text")
                if (kind !in listOf("image", "file", "voice") || c.bool("deleted")) return Weight(c.str("id"), null)
                return Weight(c.str("id"), Storage.Piece.of(kind, false, c.double("size"), c.str("data")))
            }
        }
    }

    /**
     * La marque « important » d'un message (voir `isStarred`), lue sans le
     * reste : le compte des messages importants d'une discussion n'a pas
     * besoin de leur texte ni de leurs photos.
     */
    internal data class Star(val id: String, val starred: Boolean) {
        companion object : JsonDecodable<Star> {
            override fun fromJson(json: JsonElement): Star {
                val c = json.obj()
                return Star(c.str("id"), c.bool("starred") && !c.bool("deleted"))
            }
        }
    }

    companion object : JsonDecodable<Message> {
        internal fun tooOld(at: Double, openAt: Double?, starred: Boolean, keepDays: Int, now: Double): Boolean =
            keepDays > 0 && !starred && maxOf(at, openAt ?: 0.0) < now - keepDays.toDouble() * 86_400_000

        /**
         * Les photos d'une discussion, pour sa fiche (« Photos », comme
         * WhatsApp) : la plus récente d'abord, sans les supprimées ni une
         * capsule encore scellée pour nous. `messages` va du plus ancien au plus
         * récent. Le même que le navigateur (`model::photos`).
         */
        fun photos(messages: List<Message>, me: String, now: Double): List<Message> = attachments(messages, "image", me, now)

        /**
         * Les documents (fichiers) d'une discussion, pour sa fiche, avec la même
         * règle que les photos. Le même que le navigateur (`model::documents`).
         */
        fun documents(messages: List<Message>, me: String, now: Double): List<Message> = attachments(messages, "file", me, now)

        /** Le texte contient-il « http » (sans le découper en caractères) ? */
        internal fun hasHTTP(text: String): Boolean = text.contains("http")

        /**
         * Les liens d'un texte, avec la règle des liens cliquables des bulles
         * (`richText`) : `http://` ou `https://` en début de mot (ou après une
         * parenthèse, un guillemet), jusqu'à l'espace, sans la ponctuation
         * finale. Le même que le navigateur (`model::find_links`).
         */
        fun findLinks(text: String): List<String> {
            // Presque aucun message n'a de lien : sans « http », rien à chercher
            // (la fiche d'une discussion fouille ainsi 500 messages à chaque
            // affichage).
            if (!hasHTTP(text)) return emptyList()
            val chars = text.codePoints().toArray()
            val out = mutableListOf<String>()
            fun str(from: Int, to: Int) = String(chars, from, to - from)
            val opening = "([{«\"'“‘".codePoints().toArray().toSet()
            val closing = ".,;:!?)]}'\"»”’".codePoints().toArray().toSet()
            var i = 0
            while (i < chars.size) {
                // Un lien commence par un « h » : le reste n'est regardé que là.
                if (chars[i] != 'h'.code) {
                    i++
                    continue
                }
                val atStart = i == 0 || isWhite(chars[i - 1]) || chars[i - 1] in opening
                val rest = str(i, minOf(i + 8, chars.size))
                if (atStart && (rest.startsWith("https://") || rest.startsWith("http://"))) {
                    var j = i
                    while (j < chars.size && !isWhite(chars[j]) && chars[j] != '<'.code) j++
                    while (j > i && chars[j - 1] in closing) j--
                    val url = str(i, j)
                    if (url.toByteArray().size > "https://".length) out.add(url)
                    i = maxOf(j, i + 1)
                } else {
                    i++
                }
            }
            return out
        }

        /**
         * Combien de messages des autres sont arrivés après `seen` (le dernier
         * message affiché quand on a quitté le bas de la discussion) : le nombre
         * du bouton « Derniers messages », comme WhatsApp. Le même que le
         * navigateur (`model::new_below`).
         */
        fun newBelow(messages: List<Message>, seen: String, me: String): Int {
            val i = messages.indexOfFirst { it.id == seen }
            if (i < 0) return 0
            return messages.subList(i + 1, messages.size).count { it.from != me && it.from != "system" }
        }

        /**
         * Les liens d'une discussion, pour sa fiche (« Liens », comme
         * WhatsApp) : le plus récent d'abord, chacun une fois, avec son
         * message ; sans les messages supprimés ni une capsule encore scellée
         * pour nous. Le même que le navigateur (`model::links`).
         */
        fun links(messages: List<Message>, me: String, now: Double): List<ChatLink> {
            val seen = HashSet<String>()
            val out = mutableListOf<ChatLink>()
            for (m in messages.asReversed()) {
                if (m.from == "system" || m.deleted || m.sealed(me, now)) continue
                for (url in findLinks(m.text)) if (seen.add(url)) out.add(ChatLink(url, m))
            }
            return out
        }

        internal fun attachments(messages: List<Message>, kind: String, me: String, now: Double): List<Message> =
            messages.asReversed().filter { it.kind == kind && !it.deleted && it.data.isNotEmpty() && !it.sealed(me, now) }

        /**
         * La recherche dans des discussions entières : les messages dont le
         * texte ou le nom de pièce jointe contient `q` (accents et majuscules
         * ignorés), dans l'ordre des listes ; ni messages système ou supprimés,
         * ni capsule encore scellée pour `me`. `nextOpen` : l'heure où la
         * prochaine capsule scellée s'ouvrira (le résultat peut alors changer).
         * Les listes sont fouillées par tranches, sur plusieurs cœurs.
         */
        internal fun <T : SearchLine> matching(lists: List<List<T>>, q: String, me: String, now: Double): Matching<T> {
            val folded = Mentions.fold(q)
            fun scan(slice: List<T>): Matching<T> {
                val found = mutableListOf<T>()
                var next = Double.POSITIVE_INFINITY
                for (m in slice) {
                    if (m.kind == "system" || m.deleted) continue
                    if ((m.openAt ?: 0.0) > now && m.from != me) {
                        next = minOf(next, m.openAt ?: Double.POSITIVE_INFINITY)
                    } else if (folded.isNotEmpty() && Mentions.fold("${m.text} ${m.name}").contains(folded)) {
                        // Comme `range(of:)` : un texte vide ne se trouve nulle part.
                        found.add(m)
                    }
                }
                return Matching(found, next)
            }
            // Des tranches de 1 000 messages au plus, dans l'ordre.
            val slices = lists.flatMap { it.chunked(1000) }
            val parts = arrayOfNulls<Matching<T>>(slices.size)
            if (slices.size > 2 && Runtime.getRuntime().availableProcessors() > 1) {
                IntStream.range(0, slices.size).parallel().forEach { parts[it] = scan(slices[it]) }
            } else {
                slices.indices.forEach { parts[it] = scan(slices[it]) }
            }
            return Matching(
                parts.flatMap { it!!.found },
                parts.minOfOrNull { it!!.nextOpen } ?: Double.POSITIVE_INFINITY,
            )
        }

        override fun fromJson(json: JsonElement): Message {
            val c = json.obj()
            return Message(
                chat = c.str("chat"), id = c.str("id"), from = c.str("from"), at = c.double("at"),
                kind = c.str("kind", "text"), text = c.str("text"), data = c.str("data"), name = c.str("name"),
                mime = c.str("mime"), size = c.double("size"), dur = c.double("dur"), w = c.int("w"), h = c.int("h"),
                code = c.str("code"), reply = c.of("reply", Reply), fwd = c.bool("fwd"), status = c.str("status"),
                to = c.strings("to"), sentTo = c.strings("sent_to"), deliveredTo = c.strings("delivered_to"),
                readBy = c.strings("read_by"), reactions = c.stringMap("reactions"), edited = c.bool("edited"),
                deleted = c.bool("deleted"), starred = c.bool("starred"), exp = c.doubleOrNull("exp"),
                readByMe = c.bool("read_by_me"), openAt = c.doubleOrNull("open_at"), bro = c.bool("bro"),
                mentions = c.strings("mentions"),
            )
        }
    }
}

// ----- La boîte d'envoi, les actus, les appels -----------------------------------

/** Une enveloppe qui attend de partir. */
data class OutItem(
    val id: String = "",
    val to: String = "",
    /** Le `Wire`, en JSON : chiffré au moment de partir. */
    val payload: String = "",
    val chat: String = "",
    val msgId: String = "",
    val seq: Double = 0.0,
    /**
     * Le démarrage du relais qui a accepté ce message (`ready.boot`) : il est
     * gardé jusqu'à l'accusé de réception, et renvoyé si le relais a
     * redémarré entre-temps. Vide : pas encore parti.
     */
    val boot: String = "",
    /** v4 : les appareils du destinataire qui l'ont déjà (déposé pour eux). */
    val sent: List<String> = emptyList(),
    /** v4 : ceux qui l'ont reçu (accusé de réception de cet appareil). */
    val got: List<String> = emptyList(),
) : JsonEncodable {
    /**
     * Le JSON de l'élément, quand son contenu est déjà échappé
     * (`escapedPayload` : `JSON.encode(payload)`, guillemets compris). Un
     * envoi à tout un groupe écrit le même contenu pour chaque membre :
     * l'échapper une fois, pas cinquante (une photo pèse des centaines de
     * Ko). Le texte rendu se relit comme celui de `JSON.encode(this)`.
     */
    internal fun json(escapedPayload: String): String {
        val json = JSON.encode(copy(payload = marker))
        val hole = json.indexOf(escapedMarker)
        if (hole < 0) return JSON.encode(this)
        return StringBuilder(json.length + escapedPayload.length)
            .append(json, 0, hole)
            .append(escapedPayload)
            .append(json, hole + escapedMarker.length, json.length)
            .toString()
    }

    override fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("to", to)
            put("payload", payload)
            put("chat", chat)
            put("msg_id", msgId)
            put("seq", seq)
            put("boot", boot)
            if (sent.isNotEmpty()) put("sent", sent)
            if (got.isNotEmpty()) put("got", got)
        }

    companion object : JsonDecodable<OutItem> {
        /** Ce qui tient la place du contenu le temps d'encoder le reste. */
        internal const val marker = "\u0001contenu\u0001"
        internal val escapedMarker = JSON.encode(marker)

        /**
         * L'enveloppe sans son contenu (`payload` vide) : la boîte d'envoi
         * n'en a besoin qu'au moment de l'envoyer.
         */
        internal fun head(json: String): OutItem? = JSON.decode(OutItem, json)?.copy(payload = "")

        override fun fromJson(json: JsonElement): OutItem {
            val c = json.obj()
            return OutItem(
                id = c.str("id"), to = c.str("to"), payload = c.str("payload"), chat = c.str("chat"),
                msgId = c.str("msg_id"), seq = c.double("seq"), boot = c.str("boot"),
                sent = c.strings("sent"), got = c.strings("got"),
            )
        }
    }
}

data class StatusItem(
    val owner: String = "",
    val id: String = "",
    /** `text` ou `image`. */
    val kind: String = "text",
    val text: String = "",
    val bg: String = "",
    val data: String = "",
    val at: Double = 0.0,
    val seen: Boolean = false,
    val viewers: Map<String, Double> = emptyMap(),
) : JsonEncodable {
    val key: String get() = "$owner|$id"

    override fun toJson(): JsonObject =
        buildJsonObject {
            put("owner", owner)
            put("id", id)
            put("kind", kind)
            put("text", text)
            put("bg", bg)
            put("data", data)
            put("at", at)
            put("seen", seen)
            putDoubleMap("viewers", viewers)
        }

    /**
     * L'auteur, l'identifiant et la date d'une actu, lus sans le reste : le
     * ménage des actus de plus de 24 heures n'a besoin que de la date.
     */
    internal data class Head(val owner: String, val id: String, val at: Double) {
        val key: String get() = "$owner|$id"

        companion object : JsonDecodable<Head> {
            override fun fromJson(json: JsonElement): Head {
                val c = json.obj()
                return Head(c.str("owner"), c.str("id"), c.double("at"))
            }
        }
    }

    companion object : JsonDecodable<StatusItem> {
        override fun fromJson(json: JsonElement): StatusItem {
            val c = json.obj()
            return StatusItem(
                c.str("owner"), c.str("id"), c.str("kind", "text"), c.str("text"), c.str("bg"), c.str("data"),
                c.double("at"), c.bool("seen"), c.doubleMap("viewers"),
            )
        }
    }
}

data class CallEntry(
    val id: String = "",
    val peer: String = "",
    val video: Boolean = false,
    val incoming: Boolean = false,
    val at: Double = 0.0,
    val dur: Double = 0.0,
    val missed: Boolean = false,
    /** Un appel à plusieurs : les autres participants (`peer` compris). */
    val members: List<String> = emptyList(),
    /** Le groupe d'où il a été lancé, s'il y en a un. */
    val chat: String = "",
) : JsonEncodable {
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("peer", peer)
            put("video", video)
            put("incoming", incoming)
            put("at", at)
            put("dur", dur)
            put("missed", missed)
            put("members", members)
            put("chat", chat)
        }

    companion object : JsonDecodable<CallEntry> {
        override fun fromJson(json: JsonElement): CallEntry {
            val c = json.obj()
            return CallEntry(
                c.str("id"), c.str("peer"), c.bool("video"), c.bool("incoming"), c.double("at"), c.double("dur"),
                c.bool("missed"), c.strings("members"), c.str("chat"),
            )
        }
    }
}

/** Ce qui décrit un groupe, joint à chaque envoi dans ce groupe. */
data class GroupInfo(
    val id: String = "",
    val name: String = "",
    val members: List<String> = emptyList(),
    val admins: List<String> = emptyList(),
    val avatar: String = "",
    val v: Long = 0,
    /** Sa description ; absente (un client d'avant elle) : inchangée. */
    val desc: String? = null,
    /** Seuls les admins écrivent ; absent (un client d'avant) : inchangé. */
    val adminsOnly: Boolean? = null,
) : JsonEncodable {
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("name", name)
            put("members", members)
            put("admins", admins)
            put("avatar", avatar)
            put("v", v)
            desc?.let { put("desc", it) }
            adminsOnly?.let { put("admins_only", it) }
        }

    companion object : JsonDecodable<GroupInfo> {
        override fun fromJson(json: JsonElement): GroupInfo {
            val c = json.obj()
            return GroupInfo(
                c.str("id"), c.str("name"), c.strings("members"), c.strings("admins"), c.str("avatar"), c.long("v"),
                c.strOrNull("desc"), c.boolOrNull("admins_only"),
            )
        }
    }
}

// ----- Le stockage ---------------------------------------------------------------

/**
 * Une taille lisible : « 25 o », « 11,7 Ko », « 3,2 Mo » (par 1024, une
 * décimale à la française). Le même que le navigateur (`format_bytes`) :
 * un même fichier affiche la même taille des deux côtés.
 */
fun formatBytes(n: Double): String {
    val units = listOf("o", "Ko", "Mo", "Go")
    var x = if (n.isFinite()) maxOf(n, 0.0) else 0.0
    var i = 0
    while (x >= 1024 && i < units.size - 1) {
        x /= 1024
        i++
    }
    // L'arrondi de `printf` : la valeur exacte, au pair le plus proche.
    val s = BigDecimal(x).setScale(if (i == 0) 0 else 1, RoundingMode.HALF_EVEN).toPlainString()
    return "${s.replace(".", ",")} ${units[i]}"
}

/** Une discussion et ce que pèsent ses pièces jointes (« Gérer le stockage »). */
data class ChatStorage(val chat: Chat, val storage: Storage) {
    val id: String get() = chat.id
}

/**
 * Ce que pèsent les pièces jointes d'une discussion (« Gérer le
 * stockage », comme WhatsApp). Le même que le navigateur (`Storage`).
 */
data class Storage(
    val bytes: Double = 0.0,
    val photos: Int = 0,
    val files: Int = 0,
    val voices: Int = 0,
) {
    /** Une pièce jointe encore là : ce qu'elle est et ce qu'elle pèse. */
    internal data class Piece(val kind: Kind, val bytes: Double) {
        enum class Kind { photo, file, voice }

        companion object {
            /** Rien : pas une pièce jointe, ou supprimée, ou sans contenu. */
            fun of(kind: String, deleted: Boolean, size: Double, data: String): Piece? {
                if (deleted || data.isEmpty()) return null
                val k = when (kind) {
                    "image" -> Kind.photo
                    "file" -> Kind.file
                    "voice" -> Kind.voice
                    else -> return null
                }
                if (size > 0) return Piece(k, size)
                val comma = data.indexOf(',')
                val b64 = if (comma >= 0) data.substring(comma + 1) else data
                return Piece(k, (b64.toByteArray().size * 3 / 4).toDouble())
            }
        }
    }

    internal fun add(piece: Piece): Storage =
        when (piece.kind) {
            Piece.Kind.photo -> copy(photos = photos + 1, bytes = bytes + piece.bytes)
            Piece.Kind.file -> copy(files = files + 1, bytes = bytes + piece.bytes)
            Piece.Kind.voice -> copy(voices = voices + 1, bytes = bytes + piece.bytes)
        }

    /** « 12 photos · 2 documents · 1 vocal ». */
    val detail: String
        get() {
            fun part(n: Int, one: String, many: String): String? = if (n == 0) null else if (n == 1) "1 $one" else "$n $many"
            return listOfNotNull(part(photos, "photo", "photos"), part(files, "document", "documents"), part(voices, "vocal", "vocaux"))
                .joinToString(" · ")
        }

    companion object {
        /**
         * Les photos, documents et vocaux encore là (pas les supprimés) : leur
         * taille, ou à défaut celle de leur contenu base64 décodé.
         */
        fun of(messages: List<Message>): Storage {
            var st = Storage()
            for (m in messages) Piece.of(m.kind, m.deleted, m.size, m.data)?.let { st = st.add(it) }
            return st
        }
    }
}

/** Une description de groupe : 500 caractères au plus. */
const val maxGroupDesc = 500

/**
 * Un message : 20 000 caractères au plus, arrêtés à la saisie plutôt que
 * coupés en silence à l'envoi. Le même que le navigateur (`MAX_TEXT`, le
 * `maxlength` de son champ).
 */
const val maxMessageText = 20_000

/** La description d'un groupe telle qu'elle est gardée (comme le navigateur). */
fun groupDesc(d: String): String = d.trim { isWhite(it.code) }.prefixChars(maxGroupDesc)

// ----- Ce qui voyage ---------------------------------------------------------------

/** Ce qui voyage, chiffré, d'un appareil à l'autre (`k` en donne la sorte). */
sealed class Body {
    data class Msg(val id: String, val at: Double, val pn: String, val content: Content) : Body()

    /** `st` : `delivered` ou `read` ; `dev` : l'appareil qui a reçu (v4). */
    data class Receipt(val st: String, val ids: List<String>, val dev: String = "") : Body()

    data class React(val id: String, val emoji: String) : Body()

    data class Edit(val id: String, val text: String) : Body()

    data class Delete(val id: String) : Body()

    data class Typing(val on: Boolean) : Body()

    /**
     * `jeton` : le jeton de remise de l'expéditeur (expéditeur scellé).
     * `nk` : sa clé de notification (navigateur), pour l'aperçu chiffré.
     * `pj` : cette version chiffre tout en v3, pièces jointes comprises.
     * `devs`, `dv` : ses appareils et la version de cette liste (v4) ;
     * `bx` : il lit ses boîtes aux lettres tournantes (v4).
     */
    data class Profile(
        val profile: talka.kit.Profile,
        val jeton: String = "",
        val nk: String = "",
        val pj: Boolean = false,
        val devs: List<DevInfo> = emptyList(),
        val dv: Long = 0,
        val bx: Boolean = false,
    ) : Body()

    data class Group(val op: String, val note: String) : Body()

    data class Timer(val secs: Long) : Body()

    data class Status(val id: String, val kind: String, val text: String, val bg: String, val data: String, val at: Double) : Body()

    data class StatusSeen(val id: String) : Body()

    data class StatusDelete(val id: String) : Body()

    /**
     * Un signal d'appel. Un appel à plusieurs (`Mesh`) : `members`, tous
     * ceux de l'appel (l'expéditeur compris), et `chat`, le groupe d'où il
     * est lancé ; vides pour un appel à deux, comme avant.
     */
    data class Call(
        val op: String,
        val callId: String,
        val video: Boolean,
        val sdp: String,
        val cand: JSONValue?,
        val members: List<String> = emptyList(),
        val chat: String = "",
    ) : Body()

    /**
     * Entre les appareils d'un même compte (v4, `docs/SECURITE.md` §7.11) :
     * ce que l'un a fait, que les autres refont. Accepté seulement de nos
     * propres appareils. `c` : la discussion ; `w` : ce que l'appareil a
     * envoyé dans la discussion (`op` = `wire`) ; `d` : le reste.
     */
    data class Sync(val op: String, val c: String = "", val w: Wire? = null, val d: JSONValue = JSONValue.Null) : Body()

    /** Une sorte inconnue (une version plus récente) : ignorée. */
    data class Unknown(val k: String) : Body()

    /**
     * Ce qui change un état déjà là (et qu'un rejeu suffirait à défaire) :
     * refusé dans l'ancien format d'un contact passé en v3. Comme
     * `Body::changes_state` du navigateur.
     */
    val changesState: Boolean
        get() = this is Timer || this is Edit || this is Delete || this is Group || this is Call || this is StatusDelete
}

/** Une enveloppe : le contenu, et la discussion à laquelle il appartient. */
data class Wire(
    val body: Body,
    /** Le groupe (décrit en entier). */
    val grp: GroupInfo? = null,
    /** Le groupe (seulement son identifiant : accusés). */
    val g: String? = null,
    /** Messages éphémères : la durée de vie, en secondes. */
    val ttl: Long? = null,
) : JsonEncodable {
    /** L'identifiant du groupe, s'il y en a un. */
    val groupId: String? get() = grp?.id ?: g

    override fun toJson(): JsonObject =
        buildJsonObject {
            when (val b = body) {
                is Body.Msg -> {
                    put("k", "msg")
                    put("id", b.id)
                    put("at", b.at)
                    if (b.pn.isNotEmpty()) put("pn", b.pn)
                    putAll(b.content.toJson())
                }
                is Body.Receipt -> {
                    put("k", "receipt")
                    put("st", b.st)
                    put("ids", b.ids)
                    if (b.dev.isNotEmpty()) put("dev", b.dev)
                }
                is Body.React -> {
                    put("k", "react")
                    put("id", b.id)
                    put("emoji", b.emoji)
                }
                is Body.Edit -> {
                    put("k", "edit")
                    put("id", b.id)
                    put("text", b.text)
                }
                is Body.Delete -> {
                    put("k", "delete")
                    put("id", b.id)
                }
                is Body.Typing -> {
                    put("k", "typing")
                    put("on", b.on)
                }
                is Body.Profile -> {
                    put("k", "profile")
                    putAll(b.profile.toJson())
                    if (b.jeton.isNotEmpty()) put("jeton", b.jeton)
                    if (b.nk.isNotEmpty()) put("nk", b.nk)
                    if (b.pj) put("pj", true)
                    if (b.devs.isNotEmpty()) putList("devs", b.devs)
                    if (b.dv != 0L) put("dv", b.dv)
                    if (b.bx) put("bx", true)
                }
                is Body.Group -> {
                    put("k", "group")
                    put("op", b.op)
                    put("note", b.note)
                }
                is Body.Timer -> {
                    put("k", "timer")
                    put("secs", b.secs)
                }
                is Body.Status -> {
                    put("k", "status")
                    put("id", b.id)
                    put("type", b.kind)
                    put("text", b.text)
                    put("bg", b.bg)
                    put("data", b.data)
                    put("at", b.at)
                }
                is Body.StatusSeen -> {
                    put("k", "status-seen")
                    put("id", b.id)
                }
                is Body.StatusDelete -> {
                    put("k", "status-delete")
                    put("id", b.id)
                }
                is Body.Call -> {
                    put("k", "call")
                    put("op", b.op)
                    put("call_id", b.callId)
                    put("video", b.video)
                    put("sdp", b.sdp)
                    b.cand?.let { put("cand", it.toJson()) }
                    if (b.members.isNotEmpty()) put("members", b.members)
                    if (b.chat.isNotEmpty()) put("chat", b.chat)
                }
                is Body.Sync -> {
                    put("k", "sync")
                    put("op", b.op)
                    if (b.c.isNotEmpty()) put("c", b.c)
                    b.w?.let { put("w", it.toJson()) }
                    if (b.d != JSONValue.Null) put("d", b.d.toJson())
                }
                is Body.Unknown -> put("k", b.k)
            }
            grp?.let { put("grp", it.toJson()) }
            g?.let { put("g", it) }
            ttl?.let { put("ttl", it) }
        }

    fun json(): String = JSON.encode(this)

    companion object : JsonDecodable<Wire> {
        fun parse(text: String): Wire? = JSON.decode(Wire, text)

        override fun fromJson(json: JsonElement): Wire {
            val c = json.obj()
            fun s(k: String) = c.str(k)
            val body = when (val kind = s("k")) {
                "msg" -> Body.Msg(s("id"), c.double("at"), s("pn"), Content.fromJson(c))
                "receipt" -> Body.Receipt(s("st"), c.strings("ids"), s("dev"))
                "react" -> Body.React(s("id"), s("emoji"))
                "edit" -> Body.Edit(s("id"), s("text"))
                "delete" -> Body.Delete(s("id"))
                "typing" -> Body.Typing(c.bool("on"))
                "profile" -> Body.Profile(
                    Profile.fromJson(c), jeton = s("jeton"), nk = s("nk"), pj = c.bool("pj"),
                    devs = c.list("devs", DevInfo), dv = c.long("dv"), bx = c.bool("bx"),
                )
                "group" -> Body.Group(s("op"), s("note"))
                "timer" -> Body.Timer(c.long("secs"))
                "status" -> Body.Status(s("id"), s("type"), s("text"), s("bg"), s("data"), c.double("at"))
                "status-seen" -> Body.StatusSeen(s("id"))
                "status-delete" -> Body.StatusDelete(s("id"))
                "call" -> Body.Call(
                    s("op"), s("call_id"), c.bool("video"), s("sdp"), c.of("cand", JSONValue),
                    members = c.strings("members"), chat = s("chat"),
                )
                "sync" -> Body.Sync(s("op"), s("c"), c.of("w", Wire), c.of("d", JSONValue) ?: JSONValue.Null)
                else -> Body.Unknown(kind)
            }
            return Wire(body, c.of("grp", GroupInfo), c.strOrNull("g"), c.longOrNull("ttl"))
        }
    }
}

/** L'heure en millisecondes, comme `Date.now()`. */
fun nowMs(): Double = System.currentTimeMillis().toDouble()

/**
 * Le relais de Talka : un seul, le même pour le navigateur, l'iPhone,
 * l'iPad et Android. Il n'y a plus de serveur à choisir.
 */
object TalkaServer {
    const val official = "https://talka-maxlestage-e497dd868a8c.herokuapp.com"
}

// ----- Les pièces jointes en v3 ------------------------------------------------
//
// Comme `split_attachment` / `join_attachment` du navigateur : les données
// d'un message trop gros pour un jeton v3 sont chiffrées par une clé tirée
// pour lui seul (AES-256-GCM, AD `talka-pj:<id>`) ; le message v3 ne porte
// que la clé (`data` = `talka-pj:<clé>`) ; l'enveloppe devient
// `<enveloppe v3>|<pièce en base64>`.
object Attachment {
    const val prefix = "talka-pj:"

    /** Le message à mettre dans le jeton v3, et la pièce chiffrée. */
    class Split(val small: String, val blob: ByteArray)

    fun split(payload: String): Split? {
        val wire = JSON.decode(Wire, payload) ?: return null
        val body = wire.body as? Body.Msg ?: return null
        if (body.content.data.isEmpty()) return null
        val key = randomBytes(32)
        val combined = AesGcm.seal(key, body.content.data.toByteArray(), "talka-pj:${body.id}".toByteArray()) ?: return null
        val small = body.content.copy(data = prefix + b64(key))
        return Split(wire.copy(body = body.copy(content = small)).json(), combined)
    }

    /**
     * Remet la pièce dans le message ouvert ; absente, abîmée ou d'un autre
     * message : les données restent vides.
     */
    fun join(plain: String, blob: ByteArray?): String {
        val wire = JSON.decode(Wire, plain) ?: return plain
        val body = wire.body as? Body.Msg ?: return plain
        if (!body.content.data.startsWith(prefix)) return plain
        val key = unb64(body.content.data.substring(prefix.length))?.takeIf { it.size == 32 }
        val data = if (key != null && blob != null) {
            AesGcm.open(key, blob, "talka-pj:${body.id}".toByteArray())?.let(::utf8) ?: ""
        } else {
            ""
        }
        return wire.copy(body = body.copy(content = body.content.copy(data = data))).json()
    }
}

// ----- Les mentions (« @Léa ») -------------------------------------------------
//
// Comme `mention_query` / `mentions_in` du navigateur.
object Mentions {
    /** Sans majuscules ni accents, comme `fold` du navigateur. */
    fun fold(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        var i = 0
        while (i < d.length) {
            val cp = d.codePointAt(i)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return Normalizer.normalize(sb.toString().lowercase(), Normalizer.Form.NFC)
    }

    /**
     * Ce qu'on tape après « @ » (le texte jusqu'au curseur) : le « @ » doit
     * commencer un mot ; pas de retour à la ligne, 30 caractères au plus.
     */
    fun query(before: String): String? {
        val at = before.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && !isWhite(before.codePointBefore(at))) return null
        val q = before.substring(at + 1)
        return if (!q.contains('\n') && q.charCount() <= 30) q else null
    }

    /** Les membres mentionnés dans `text` : ceux dont « @libellé » y figure (code à libellé). */
    fun found(text: String, members: List<Pair<String, String>>): List<String> {
        val folded = fold(text)
        val out = mutableListOf<String>()
        for ((code, label) in members) {
            if (label.isNotEmpty() && folded.contains("@" + fold(label)) && code !in out) out.add(code)
        }
        return out
    }
}
