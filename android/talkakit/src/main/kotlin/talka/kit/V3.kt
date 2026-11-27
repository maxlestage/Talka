// Le protocole v3 de Talka : des sessions à double cliquet (une clé neuve
// par message), établies en hybride X25519 + ML-KEM-768, avec BrocolisProto
// (le module `brocolis`, vérifié octet pour octet contre la crate Rust
// qu'utilise le navigateur). Exactement le même protocole que le navigateur
// (`client/src/v3.rs`) et l'iPhone (`V3.swift`).
//
// - Un appareil v3 par compte (`Device` : IK X25519 + Ed25519, SPK, PQPK
//   ML-KEM-768), créé au premier démarrage, gardé par le système
//   (`V3Vault`), sinon dans `kv/v3_device` ; ses clés pré-signées
//   renouvelées chaque semaine (`V3Engine.maintain`).
// - Le paquet de clés est lié au code : ses octets sont signés par
//   l'identité ECDSA P-256 de Talka, après le contexte `talka-v3-paquet:`.
//   Un paquet n'est accepté que si cette signature vérifie avec la clé de
//   signature du contact, elle-même liée à son code.
// - Envoi : le `Wire` JSON est chiffré par la session du contact (un jeton
//   `[[BROCOLIS:v3:…]]`), puis ce jeton part dans l'enveloppe Talka
//   habituelle : le relais ne voit aucune différence. Sans paquet ni session
//   (un contact qui n'a pas la mise à jour), l'ancien format, exactement.
// - Réception : une enveloppe dont le contenu est un jeton v3 passe par la
//   session (ou `Device.accept` pour un premier message). Un rejeu est
//   ignoré sans bruit.
//
// Les sessions : AD = IK de l'initiateur ‖ IK du répondeur, donc deux
// identifiants de session au plus entre deux appareils (celle que j'ai
// ouverte, celle qu'il a ouverte). Les deux sont gardées : quand les deux
// côtés écrivent le premier message en même temps, chacun lit l'autre, et
// l'envoi suit la dernière session qui a servi.
//
// ML-KEM vient de BouncyCastle : sur Android, les sessions sont toujours
// hybrides.

package talka.kit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import talka.brocolis.Device
import talka.brocolis.Erreur
import talka.brocolis.Hasard
import talka.brocolis.JetonV3
import talka.brocolis.KeyBundle
import talka.brocolis.MlKem
import talka.brocolis.Session
import talka.brocolis.Token
import talka.brocolis.Content as ProtoContent

object V3 {
    /** Ce que signe l'identité Talka : ce contexte, puis les octets du paquet. */
    val bundleContext: ByteArray get() = "talka-v3-paquet:".toByteArray()

    /**
     * Un paquet de clés fait 166 octets (sans ML-KEM) ou 1 418 (avec) ; le
     * relais en accepte jusqu'à 2 Kio.
     */
    const val maxBundle = 2048

    /** La clé de l'appareil v3 dans `kv` (sans garde du système, et dans la sauvegarde). */
    const val deviceKey = "v3_device"

    /**
     * Le préfixe des sessions d'un contact dans `kv` (`v3:<code>`). Elles ne
     * partent pas dans la sauvegarde : un état de cliquet restauré en retard
     * réutiliserait des clés de message déjà servies.
     */
    const val sessionPrefix = "v3:"

    /** Cet appareil sait-il faire ML-KEM ? (Toujours : BouncyCastle.) Sinon, ses sessions sont classiques. */
    val postQuantum: Boolean get() = MlKem.isAvailable

    /** Ce que signe l'identité Talka pour publier un paquet. */
    fun bundleMessage(bundle: ByteArray): ByteArray = bundleContext + bundle

    /**
     * Ce que signe l'identité Talka pour publier le paquet d'un appareil (v4,
     * `docs/SECURITE.md` §7.11) : le paquet est lié au code **et** à
     * l'appareil.
     */
    fun deviceBundleMessage(dev: String, bundle: ByteArray): ByteArray = "talka-v4-appareil:$dev:".toByteArray() + bundle

    /** Le paquet d'un appareil (v4), comme `verifyBundle`. */
    fun verifyDeviceBundle(code: String, sign: String, dh: String, dev: String, bundle: String, sig: String): KeyBundle? {
        if (!TalkaV4.isDev(dev) || !TalkaCrypto.verifyKeys(code, sign, dh)) return null
        val bytes = bundleBytes(bundle) ?: return null
        if (!TalkaCrypto.verify(sign, deviceBundleMessage(dev, bytes), sig)) return null
        return parse(bytes)
    }

    /** L'heure du protocole : des secondes Unix (Talka compte en millisecondes). */
    fun secs(ms: Double): ULong = maxOf(ms / 1000, 0.0).toULong()

    /**
     * Un paquet publié sur le relais : accepté seulement si `sign` et `dh`
     * sont bien les clés de `code`, et si `sig` (ECDSA P-256, `r ‖ s`) est
     * leur signature du paquet. Le paquet lui-même est vérifié (ses
     * signatures Ed25519, ses clés).
     */
    fun verifyBundle(code: String, sign: String, dh: String, bundle: String, sig: String): KeyBundle? {
        if (!TalkaCrypto.verifyKeys(code, sign, dh)) return null
        val bytes = bundleBytes(bundle) ?: return null
        if (!TalkaCrypto.verify(sign, bundleMessage(bytes), sig)) return null
        return parse(bytes)
    }

    /** Les octets d'un paquet en base64, refusé trop gros avant toute vérification. */
    private fun bundleBytes(bundle: String): ByteArray? {
        if (bundle.toByteArray().size > (maxBundle + 2) / 3 * 4) return null
        return unb64(bundle)?.takeIf { it.size <= maxBundle }
    }

    private fun parse(bytes: ByteArray): KeyBundle? =
        try {
            KeyBundle.fromBytes(bytes)
        } catch (e: Exception) {
            null
        }

    /** Vrai si ce texte (le contenu d'une enveloppe Talka) est un jeton v3. */
    fun isToken(text: String): Boolean = text.startsWith(JetonV3.head) && text.endsWith("]]")

    /**
     * Faut-il réparer la session (l'oublier et renvoyer notre profil) ?
     * Seulement quand elle est **perdue** : session inconnue, ou clé
     * pré-signée déjà oubliée. Un déchiffrement raté sur une session connue
     * est un rejeu (le relais peut renvoyer un vieux message, y compris
     * d'une chaîne plus ancienne que celles que la session retient) ou une
     * falsification : on l'ignore. Sinon, quiconque rejoue un vieux message
     * forcerait une remise à zéro de la session.
     */
    fun needsRepair(e: Erreur): Boolean = e == Erreur.SessionInconnue || e == Erreur.CleInconnue

    /** Une enveloppe ouverte. */
    sealed class Opened {
        /** L'ancien format : le `Wire` JSON. */
        data class Legacy(val wire: String) : Opened()

        /** Un `Wire` JSON sorti d'une session v3. */
        data class V3(val wire: String) : Opened()

        /** Déjà lu (rejeu, doublon du relais) : à ignorer sans bruit. */
        data object Replay : Opened()

        /** Illisible : l'enveloppe n'est pas pour nous, ou la session est perdue. */
        data class Failed(val failure: Failure) : Opened()
    }

    /** Pourquoi une enveloppe n'a pas pu être ouverte. */
    sealed class Failure {
        /** Le chiffrement de Talka (pas pour nous, ou abîmée). */
        data object Envelope : Failure()

        /** Le jeton v3 : session inconnue ou perdue, message altéré… */
        data class V3(val error: Erreur) : Failure()
    }

    /** Ce qui part : le corps de l'enveloppe, et s'il porte un jeton v3. */
    data class Sealed(val body: String, val v3: Boolean)

    /** Le paquet à publier et sa signature par l'identité Talka, en base64. */
    data class Published(val bundle: String, val sig: String)

    /** La protection d'une discussion avec un contact. */
    sealed class Protection {
        /** Une session à double cliquet ; `pq` : établie avec ML-KEM. */
        data class Ratchet(val pq: Boolean) : Protection()

        /** Et le cliquet post-quantique (v6) avec au moins un de ses appareils. */
        data object RatchetPq6 : Protection()

        /** L'ancien chiffrement (le contact n'a pas la mise à jour). */
        data object Legacy : Protection()

        /** Ce que montre la fiche du contact (les mots du navigateur). */
        val label: String
            get() =
                when (this) {
                    RatchetPq6 -> "🔐 Double cliquet, et cliquet post-quantique (v6)"
                    is Ratchet -> if (pq) "🔐 Session à double cliquet (post-quantique)" else "🔐 Session à double cliquet (classique)"
                    Legacy -> "Ancien chiffrement (le contact n'a pas encore la mise à jour)"
                }
    }

    /** Les sessions d'un contact à garder (`kv/v3:<code>`) ; `json` vide : plus de session. */
    data class PeerState(val code: String, val json: String)

    /** Ce qu'il faut garder après une opération. */
    data class Dirty(
        /** L'appareil, s'il a changé (son JSON). */
        val device: String? = null,
        /** Les sessions des contacts qui ont changé (`kv/v3:<code>`) ; vide : plus de session. */
        val peers: List<PeerState> = emptyList(),
        /** Les clés du cliquet post-quantique (`kv/pq6`), si elles ont changé. */
        val pq: String? = null,
    )
}

/** Les sessions avec un contact (deux au plus : voir plus haut). */
internal class V3Peer {
    val sessions = ArrayList<Session>()

    /** La session qui sert à l'envoi (la dernière qui a servi). */
    var current: ByteArray? = null

    /** Le cliquet post-quantique avec cet appareil (v6). */
    var pq = PqPeer()

    fun toJson(): String {
        val list = sessions.joinToString(",") { it.toJson() }
        val pqJson = if (pq.isEmpty) "" else ",\"pq\":${JSON.encode(pq)}"
        return "{\"current\":\"${current?.let(::hex) ?: ""}\",\"sessions\":[$list]$pqJson}"
    }

    fun get(id: ByteArray): Session? = sessions.firstOrNull { it.sessionId.contentEquals(id) }

    /** La session d'envoi : la courante, sinon la plus récente. */
    fun sending(): Session? {
        val id = current ?: sessions.lastOrNull()?.sessionId ?: return null
        return get(id)
    }

    /** Range une session (elle remplace celle de même identifiant) et en fait la session d'envoi. */
    fun put(s: Session) {
        val id = s.sessionId
        sessions.removeAll { it.sessionId.contentEquals(id) }
        sessions.add(s)
        if (sessions.size > 2) sessions.removeAt(0)
        current = id
    }

    companion object {
        /** Relit `{"current": "<hex>", "sessions": [<session>, …]}` : la forme du navigateur. */
        fun fromJson(json: String): V3Peer {
            val peer = V3Peer()
            val obj = JSON.parse(json) as? JsonObject ?: return peer
            for (value in obj["sessions"] as? JsonArray ?: JsonArray(emptyList())) {
                if (peer.sessions.size >= 2) break
                // Comme `JSONSerialization.data(withJSONObject:)` : un objet ou une liste seulement.
                if (value !is JsonObject && value !is JsonArray) continue
                val s =
                    try {
                        Session.fromJson(JSON.write(value))
                    } catch (e: Exception) {
                        continue
                    }
                peer.sessions.add(s)
            }
            val current = obj["current"].asString() ?: ""
            peer.current = peer.sessions.map { it.sessionId }.firstOrNull { hex(it) == current }
            (obj["pq"] as? JsonObject)?.let { o -> JSON.decode(PqPeer, o)?.let { peer.pq = it } }
            return peer
        }
    }
}

/**
 * L'état v3 de l'appareil : son `Device` et ses sessions, contact par
 * contact. Pur : il ne garde rien lui-même ; ce qui a changé se lit avec
 * `takeDirty`, pour être écrit avec le message qui l'a changé.
 *
 * L'appareil gardé, ou un neuf (avec ML-KEM) s'il n'y en a pas encore (ou
 * s'il est illisible). Un appareil neuf est à garder tout de suite.
 */
class V3Engine(deviceJson: String?, now: ULong) {
    internal val device: Device
    internal val peers = HashMap<String, V3Peer>()
    internal val loaded = HashSet<String>()
    internal var deviceDirty: Boolean
    internal val dirty = HashSet<String>()

    /**
     * Les clés ML-KEM du cliquet post-quantique (v6) ; rien : pas de
     * cliquet post-quantique (il s'active avec `startPq`).
     */
    internal var pq: Pq6.Keys? = null
    internal var pqDirty = false

    /** L'heure (secondes) de la dernière opération. */
    internal var clock: ULong = 0uL

    init {
        val kept =
            deviceJson?.let {
                try {
                    Device.fromJson(it)
                } catch (e: Exception) {
                    null
                }
            }
        if (kept != null) {
            device = kept
            deviceDirty = false
        } else {
            device = Device.generate(Hasard.system(), now, pq = true)
            deviceDirty = true
        }
    }

    /**
     * Les clés pré-signées : renouvelées chaque semaine, les anciennes
     * effacées un mois après ; une clé ML-KEM ajoutée si l'appareil n'en a
     * pas. `true` : le paquet a changé (à republier).
     */
    fun maintain(now: ULong): Boolean {
        if (device.needsRotation(now)) {
            device.rotate(Hasard.system(), now)
            deviceDirty = true
            return true
        }
        if (MlKem.isAvailable && !device.supportsPq) {
            val ok =
                try {
                    device.enablePq(Hasard.system(), now)
                    true
                } catch (e: Exception) {
                    false
                }
            if (ok) {
                deviceDirty = true
                return true
            }
        }
        return false
    }

    /** Active le cliquet post-quantique (v6, §7.17) avec les clés gardées (`kv/pq6`), ou de nouvelles. */
    fun startPq(json: String?, now: ULong) {
        pq = json?.let { JSON.decode(Pq6.Keys, it) } ?: Pq6.Keys()
        maintainPq(now)
    }

    /** Fait tourner la clé ML-KEM qui a servi (une fois par heure au plus), et efface celles qui ont fait leur temps. */
    fun maintainPq(now: ULong) {
        clock = maxOf(clock, now)
        if (pq?.maintain(now) == true) pqDirty = true
    }

    /**
     * L'annonce de notre clé, à joindre au message pour `addr` s'il ne s'en
     * est pas encore servi (au plus une fois par heure tant qu'il ne s'en
     * sert pas). Elle n'est comptée comme partie (`pqAdvertSent`) que si le
     * message la porte vraiment.
     */
    internal fun pqAdvert(addr: String): JsonObject? {
        val keys = pq ?: return null
        val current = keys.current?.id ?: return null
        val p = peers[addr]?.pq ?: PqPeer()
        val due = p.sent != current || clock >= satAdd(p.sentAt, Pq6.rotateSecs)
        if (p.told == current || !due) return null
        return keys.advert
    }

    internal fun pqAdvertSent(addr: String) {
        val current = pq?.current?.id ?: return
        val peer = peers.getOrPut(addr) { V3Peer() }
        peer.pq.sent = current
        peer.pq.sentAt = clock
        dirty.add(addr)
    }

    /** Retient la clé ML-KEM annoncée dans un `Wire` reçu de `addr`. */
    internal fun pqLearn(addr: String, wire: String) {
        if (pq == null || !wire.contains("\"pq6\"")) return
        val o = JSON.parse(wire) as? JsonObject ?: return
        val advert = Pq6.parseAdvert(o["pq6"]) ?: return
        val peer = peers.getOrPut(addr) { V3Peer() }
        if (peer.pq.id != advert.id && advert.at >= peer.pq.at) {
            peer.pq.id = advert.id
            peer.pq.ek = advert.ek
            peer.pq.at = advert.at
            dirty.add(addr)
        }
    }

    /** Le cliquet post-quantique est-il en place avec `addr` ? */
    fun pqWith(addr: String): Boolean = pq != null && !(peers[addr]?.pq?.ek?.isEmpty() ?: true)

    /** Avec un compte : avec l'un de ses appareils (en mémoire). */
    fun pqWithAny(code: String): Boolean = peers.keys.any { (it == code || it.startsWith("$code/")) && pqWith(it) }

    /** Le paquet à publier sur le relais, et sa signature par l'identité Talka, en base64. */
    fun published(me: Identity): V3.Published? {
        val bytes = bundleBytes() ?: return null
        val sig = me.sign(V3.bundleMessage(bytes))
        return if (sig.isEmpty()) null else V3.Published(b64(bytes), sig)
    }

    /** Le paquet de cet appareil (v4), signé pour lui. */
    fun publishedDevice(me: Identity, dev: String): V3.Published? {
        val bytes = bundleBytes() ?: return null
        val sig = me.sign(V3.deviceBundleMessage(dev, bytes))
        return if (sig.isEmpty()) null else V3.Published(b64(bytes), sig)
    }

    private fun bundleBytes(): ByteArray? =
        try {
            device.bundle().toBytes()
        } catch (e: Exception) {
            null
        }

    /** Le paquet courant (les tests). */
    fun bundle(): KeyBundle? =
        try {
            device.bundle()
        } catch (e: Exception) {
            null
        }

    /** L'appareil, en JSON (pour la sauvegarde et `V3Vault`). */
    fun deviceJson(): String = device.toJson()

    /** Les sessions d'un contact sont-elles déjà en mémoire ? */
    fun isLoaded(code: String): Boolean = loaded.contains(code)

    /** Les sessions gardées d'un contact (rien : aucune). Sans effet si elles sont déjà en mémoire. */
    fun load(code: String, json: String?) {
        if (!loaded.add(code) || json == null) return
        peers[code] = V3Peer.fromJson(json)
    }

    /** Ce qui a changé depuis le dernier appel. */
    fun takeDirty(): V3.Dirty {
        var deviceOut: String? = null
        if (deviceDirty) {
            deviceOut = device.toJson()
            deviceDirty = false
        }
        var pqOut: String? = null
        val keys = pq
        if (pqDirty && keys != null) {
            pqOut = JSON.encode(keys)
            pqDirty = false
        }
        val out = dirty.sorted().map { code ->
            val peer = peers[code]
            V3.PeerState(code, peer?.let { if (it.sessions.isEmpty() && it.pq.isEmpty) "" else it.toJson() } ?: "")
        }
        dirty.clear()
        return V3.Dirty(deviceOut, out, pqOut)
    }

    /** La protection actuelle avec un contact : sa session d'envoi, sinon rien (pas encore de session). */
    fun sessionProtection(code: String): V3.Protection? {
        val s = peers[code]?.sending() ?: return null
        return V3.Protection.Ratchet(s.isPostQuantum)
    }

    /** Une session existe-t-elle avec ce contact ? */
    fun hasSession(code: String): Boolean = !(peers[code]?.sessions?.isEmpty() ?: true)

    /**
     * Oublie les sessions d'un contact : le prochain envoi en ouvre une
     * neuve (après une session perdue de l'un ou l'autre côté).
     */
    fun forget(code: String) {
        if (peers.remove(code) != null) dirty.add(code)
    }

    /**
     * Chiffre `plain` (un `Wire` JSON) pour un contact : par sa session, ou
     * une session neuve vers son paquet (`bundle`, vérifié) ; sinon, ou si le
     * texte dépasse ce que porte un jeton v3 (200 Kio : photos, vocaux,
     * fichiers), l'ancien format.
     */
    fun seal(me: Identity, peerCode: String, peerDh: String, bundle: KeyBundle?, plain: String): V3.Sealed? =
        sealTo(me, peerCode, peerCode, peerDh, bundle, plain)

    /**
     * Comme `seal`, pour un appareil d'un compte (v4) : la session est celle
     * de son adresse (`addr`, `<code>/<dev>`), l'enveloppe celle du code.
     */
    fun sealTo(me: Identity, addr: String, peerCode: String, peerDh: String, bundle: KeyBundle?, plain: String): V3.Sealed? {
        fun legacy(): V3.Sealed? = TalkaCrypto.seal(me, peerCode, peerDh, plain)?.let { V3.Sealed(it, false) }
        if (plain.toByteArray().size > Session.maxPlaintext) return legacy()
        // v6 : notre clé ML-KEM, s'il ne la connaît pas encore.
        var inner = plain
        val advert = pqAdvert(addr)
        if (advert != null) {
            val with = Pq6.withAdvert(plain, advert)
            if (with != null && with.toByteArray().size <= Session.maxPlaintext) inner = with
        }
        var token = encrypt(addr, bundle, inner) ?: return legacy()
        if (inner != plain) pqAdvertSent(addr)
        // Enveloppé pour sa clé ML-KEM, si nous l'avons.
        val ek = peers[addr]?.pq?.ek
        if (pq != null && !ek.isNullOrEmpty()) Pq6.wrap(ek, token)?.let { token = it }
        return TalkaCrypto.seal(me, peerCode, peerDh, token)?.let { V3.Sealed(it, true) }
    }

    internal fun encrypt(code: String, bundle: KeyBundle?, plain: String): String? {
        val peer = peers.getOrPut(code) { V3Peer() }
        val current = peer.sending()
        if (current != null) {
            try {
                val token = current.encrypt(plain)
                dirty.add(code)
                return token
            } catch (e: Exception) {
                // Chaîne épuisée ou état abîmé : une session neuve, si l'on a le paquet.
                peer.sessions.clear()
                peer.current = null
                dirty.add(code)
            }
        }
        if (bundle == null) return null
        val s: Session
        val token: String
        try {
            s = device.initiateBest(bundle, Hasard.system())
            token = s.encrypt(plain)
        } catch (e: Exception) {
            return null
        }
        peer.put(s)
        dirty.add(code)
        return token
    }

    /** Ouvre une enveloppe reçue de `peerCode` : l'ancien format, ou un jeton v3 (par sa session, ou un premier message). */
    fun open(me: Identity, peerCode: String, peerDh: String, body: String, now: ULong): V3.Opened =
        openFrom(me, peerCode, peerCode, peerDh, body, now)

    /**
     * Comme `open`, d'un appareil d'un compte (v4) : la session est celle de
     * son adresse (`addr`, `<code>/<dev>`).
     */
    fun openFrom(me: Identity, addr: String, peerCode: String, peerDh: String, body: String, now: ULong): V3.Opened {
        var plain = TalkaCrypto.open(me, peerCode, peerDh, body) ?: return V3.Opened.Failed(V3.Failure.Envelope)
        maintainPq(now)
        // v6 : enveloppé par notre clé ML-KEM.
        var used: String? = null
        if (Pq6.isWrapped(plain)) {
            val keys = pq ?: return V3.Opened.Failed(V3.Failure.V3(Erreur.CleInconnue))
            when (val r = keys.open(plain)) {
                is Pq6.Keys.Opened.Success -> {
                    plain = r.token
                    used = r.id
                    if (r.first) pqDirty = true
                }
                is Pq6.Keys.Opened.Failure ->
                    return when (r.error) {
                        // Une clé effacée (ou d'avant une restauration) : comme une
                        // session perdue, il faut réparer (lui renvoyer notre clé).
                        Pq6.Keys.OpenError.unknownKey -> V3.Opened.Failed(V3.Failure.V3(Erreur.CleInconnue))
                        Pq6.Keys.OpenError.damaged -> V3.Opened.Failed(V3.Failure.V3(Erreur.Format))
                    }
            }
        }
        if (!V3.isToken(plain)) return V3.Opened.Legacy(plain)
        return try {
            when (val content = decrypt(addr, plain, now)) {
                is ProtoContent.Text -> {
                    val peer = peers[addr]
                    if (used != null && peer != null && peer.pq.told != used) {
                        peer.pq.told = used
                        dirty.add(addr)
                    }
                    pqLearn(addr, content.text)
                    V3.Opened.V3(content.text)
                }
                // Talka ne se sert pas (encore) des chaînes de groupe.
                is ProtoContent.Distribution -> V3.Opened.Failed(V3.Failure.V3(Erreur.Format))
            }
        } catch (e: Erreur.Rejoue) {
            V3.Opened.Replay
        } catch (e: Erreur) {
            V3.Opened.Failed(V3.Failure.V3(e))
        } catch (e: Exception) {
            V3.Opened.Failed(V3.Failure.V3(Erreur.Etat))
        }
    }

    internal fun decrypt(code: String, text: String, now: ULong): ProtoContent {
        val token = Token.decode(text)
        val id = token.sessionId ?: throw Erreur.SessionInconnue
        val rng = Hasard.system()
        // v4 : une session ouverte avant de connaître ses appareils est
        // rangée sous son code ; elle passe à l'adresse de l'appareil
        // (`<code>/<dev>`) dès qu'un message dit lequel c'est.
        val slash = code.indexOf('/')
        if (slash >= 0 && peers[code]?.get(id) == null) {
            val base = code.substring(0, slash)
            val old = peers[base]
            val s = old?.get(id)
            if (old != null && s != null) {
                old.sessions.removeAll { it.sessionId.contentEquals(id) }
                if (old.current?.contentEquals(id) == true) old.current = null
                dirty.add(base)
                val moved = peers[code] ?: V3Peer()
                moved.put(s)
                peers[code] = moved
                dirty.add(code)
            }
        }
        val peer = peers.getOrPut(code) { V3Peer() }
        val s = peer.get(id)
        if (s != null) {
            try {
                val content = s.decrypt(token, now, rng)
                peer.current = id
                dirty.add(code)
                return content
            } catch (e: Erreur.NouvelleSession) {
                // Le correspondant a ouvert une session neuve (il avait perdu
                // la sienne) : elle remplace l'ancienne.
            }
        }
        val first = (token as? Token.Initial)?.initial ?: throw Erreur.SessionInconnue
        val (fresh, content) = device.accept(first, now, rng)
        peer.put(fresh)
        deviceDirty = true
        dirty.add(code)
        return content
    }
}
