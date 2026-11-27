// Les messages « double cliquet, clé partagée » du clavier Brocolis 🥦 : des
// jetons v3 (`[[BROCOLIS:v3:…]]`) en mode PSK. Le premier message ouvre une
// session à partir d'une clé Brocolis partagée ; les suivants changent de clé
// à chaque fois (double cliquet). Talka les lit comme l'app Brocolis
// (`BrocolisV3.open`) : une session connue par son identifiant, sinon un
// premier message essayé avec chaque clé de la discussion.
//
// Talka écrit aussi dans ce format, pour que le clavier « déchiffre » ce qui
// part d'ici : une discussion à deux, avec une seule clé Brocolis, envoie par
// la session de cette clé (celle que l'autre a ouverte, sinon une neuve). Et
// il garde le jeton d'origine de chaque message ouvert ou scellé, pour
// « Copier chiffré 🥦 » (à coller dans le clavier).
//
// L'état (les sessions, la garde contre le rejeu des premiers messages, les
// jetons d'origine) reste sur cet appareil : ni dans la sauvegarde, ni chez
// un appareil lié, comme les sessions v3 de Talka. Le même que le navigateur
// (`bro3::Psk`) et l'iPhone (`BrocolisPsk.swift`).

package talka.kit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import talka.brocolis.Erreur
import talka.brocolis.Hasard
import talka.brocolis.JetonV3
import talka.brocolis.Mode
import talka.brocolis.ReplayGuard
import talka.brocolis.Session
import talka.brocolis.Token
import talka.brocolis.Content as ProtoContent

class BrocolisPsk() {
    internal val sessions = ArrayList<Session>()
    internal var guardian = ReplayGuard()

    /** Les messages déjà reçus ont été rouverts une fois. */
    internal var scanned = false

    /** La session de chaque clé : [identifiant de clé, session] (hex). */
    internal val byKey = ArrayList<List<String>>()

    /** Les jetons d'origine des messages ouverts ou scellés ici : [id, jeton]. */
    internal val originals = ArrayList<List<String>>()

    /** L'état a changé depuis la dernière écriture. */
    var dirty = false
        private set

    /** Relit l'état gardé ; vide s'il n'y en a pas (ou s'il est illisible). */
    constructor(json: String?) : this() {
        val obj = json?.let { JSON.parse(it) } as? JsonObject ?: return
        for (s in obj["sessions"] as? JsonArray ?: JsonArray(emptyList())) {
            if (s !is JsonObject && s !is JsonArray) continue
            try {
                sessions.add(Session.fromJson(JSON.write(s)))
            } catch (e: Exception) {
                continue
            }
        }
        val g = obj["guard"]
        if (g is JsonObject || g is JsonArray) {
            try {
                guardian = ReplayGuard.fromJson(JSON.write(g!!))
            } catch (e: Exception) {
            }
        }
        scanned = obj["scanned"].asBool() ?: false
        byKey.addAll(pairs(obj["by_key"]))
        originals.addAll(pairs(obj["originals"]))
    }

    /** La même forme que le navigateur : `{"sessions": [...], "guard": {...}}`. */
    fun toJson(): String {
        fun pairsJson(list: List<List<String>>): JsonArray = JsonArray(list.map { p -> JsonArray(p.map { JsonPrimitive(it) }) })
        val obj = buildJsonObject {
            put("sessions", JsonArray(sessions.mapNotNull { JSON.parse(it.toJson()) }))
            put("guard", JSON.parse(guardian.toJson()) ?: JsonObject(emptyMap()))
            put("scanned", scanned)
            put("by_key", pairsJson(byKey))
            put("originals", pairsJson(originals))
        }
        return JSON.write(obj)
    }

    /**
     * Les messages déjà reçus restent à rouvrir une fois ; `true` la première
     * fois seulement (l'état passe alors à « fait », à écrire).
     */
    fun firstScan(): Boolean {
        if (scanned) return false
        scanned = true
        dirty = true
        return true
    }

    /** Prend et remet à zéro le drapeau « à écrire ». */
    fun takeDirty(): Boolean {
        val was = dirty
        dirty = false
        return was
    }

    /** Un texte une fois ses jetons v3 ouverts, et combien l'ont été. */
    data class Opened(val text: String, val opened: Int)

    /**
     * Ouvre les jetons v3 de `text` avec les sessions connues, ou un premier
     * message avec l'une des `keys` (les clés Brocolis de la discussion).
     * Renvoie le texte (jetons ouverts remplacés par leur clair) et le nombre
     * de jetons ouverts. Un jeton qui ne s'ouvre pas reste tel quel.
     */
    fun open(text: String, keys: List<ByteArray>, now: ULong): Opened {
        var out = text
        var opened = 0
        for (found in JetonV3.findAll(text)) {
            val token = found.token.getOrNull() ?: continue
            val plain = openToken(token, keys, now) ?: continue
            val at = out.indexOf(found.text)
            if (at < 0) continue
            out = out.replaceRange(at, at + found.text.length, plain)
            opened++
        }
        return Opened(out, opened)
    }

    internal fun openToken(token: Token, keys: List<ByteArray>, now: ULong): String? {
        val id = token.sessionId ?: return null
        val rng = Hasard.system()
        val s = sessions.firstOrNull { it.sessionId.contentEquals(id) }
        if (s != null) {
            try {
                val content = s.decrypt(token, now, rng)
                dirty = true
                return text(content)
            } catch (e: Erreur.NouvelleSession) {
                // L'autre a ouvert une session neuve : un premier message
                // s'essaie comme tel, plus bas.
            } catch (e: Exception) {
                return null
            }
        }
        val first = (token as? Token.Initial)?.initial ?: return null
        if (first.mode != Mode.Psk) return null
        // Clé par clé, comme l'app Brocolis : on sait laquelle a ouvert.
        for (key in keys) {
            try {
                val (session, content) = Session.acceptPsk(listOf(key), first, guardian, now, rng)
                keep(session, key)
                return text(content)
            } catch (e: Erreur.SessionInconnue) {
                continue
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    /** Garde une session, et en fait celle de `key` (la plus récente sert). */
    internal fun keep(session: Session, key: ByteArray) {
        val id = session.sessionId
        val sid = hex(id)
        sessions.removeAll { it.sessionId.contentEquals(id) }
        sessions.add(session)
        if (sessions.size > maxSessions) sessions.removeAt(0)
        val kid = keyId(key)
        byKey.removeAll { it[0] == kid }
        byKey.add(listOf(kid, sid))
        dirty = true
    }

    /**
     * Chiffre `text` au format du clavier (« double cliquet, clé partagée »)
     * par la session de `key` : celle que l'autre a ouverte, ou une neuve.
     * Les passages `((…))` seuls s'il y en a (comme `Brocolis.seal`). Rien
     * si rien n'a pu être chiffré.
     */
    fun seal(text: String, key: ByteArray): String? {
        val kid = keyId(key)
        var session = byKey.firstOrNull { it[0] == kid }?.let { pair -> sessions.firstOrNull { hex(it.sessionId) == pair[1] } }
        if (session == null) {
            val fresh =
                try {
                    Session.initiatePsk(key, Hasard.system())
                } catch (e: Exception) {
                    return null
                }
            keep(fresh, key)
            session = fresh
        }
        val s: Session = session
        var failed = false
        val sealed = Brocolis.sealWith(text) { clear ->
            try {
                s.encrypt(clear)
            } catch (e: Exception) {
                failed = true
                null
            }
        }
        dirty = true
        return if (failed || sealed == text) null else sealed
    }

    /** Retient le jeton d'origine du message `id` (ouvert ou scellé ici). */
    fun remember(id: String, token: String) {
        originals.removeAll { it[0] == id }
        originals.add(listOf(id, token))
        if (originals.size > maxOriginals) originals.removeAt(0)
        dirty = true
    }

    /** Le jeton d'origine du message `id`, s'il est connu. */
    fun original(id: String): String? = originals.lastOrNull { it[0] == id }?.get(1)

    companion object {
        /** La ligne de l'état dans la base (`kv`). */
        const val kv = "brocolis3"

        /** Au plus tant de sessions gardées (les plus anciennes partent). */
        internal const val maxSessions = 64

        /** Au plus tant de jetons d'origine gardés (les plus anciens partent). */
        internal const val maxOriginals = 500

        /**
         * L'identifiant d'une clé (8 premiers octets de son SHA-256, en hex) :
         * la clé elle-même ne va pas dans cet état. Le même que le navigateur.
         */
        internal fun keyId(key: ByteArray): String = hex(sha256(key).copyOf(8))

        /** Le clair d'un message ; une chaîne de groupe (type 0x03) ne s'affiche pas. */
        internal fun text(content: ProtoContent): String = (content as? ProtoContent.Text)?.text ?: ""

        /** `[[String]]` : tout ou rien, puis les paires seulement. */
        private fun pairs(e: JsonElement?): List<List<String>> {
            val a = e as? JsonArray ?: return emptyList()
            val all = a.map { row -> (row as? JsonArray)?.map { it.asString() ?: return emptyList() } ?: return emptyList() }
            return all.filter { it.size == 2 }
        }
    }
}
