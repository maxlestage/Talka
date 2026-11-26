// L'établissement (§4.2, §7.3, §7.4) et le Double Ratchet (§4.3, §7.5) :
// une clé neuve par message, effacée aussitôt utilisée —
// `brocolis_proto::session`.
//
// L'initiateur A part de SK et de la SPK de B (sa clé de cliquet initiale) ;
// B part de SK et de sa SPK. Chaque déchiffrement est **transactionnel** :
// il travaille sur une copie de l'état, qui ne remplace l'état qu'en cas de
// succès (message altéré, rejeu, trop de sauts : la session est exactement
// celle d'avant).
//
// Pour recevoir un jeton v3 de type 0x01 à 0x03 :
//
// 1. décoder (`Token.decode`) et chercher la session par `Token.sessionId` ;
// 2. si elle existe : `Session.decrypt`. Une erreur `Erreur.NouvelleSession`
//    sur un 0x01 veut dire « nouvelle session » : passer à l'étape 3 ;
// 3. sinon, pour un 0x01 : `Device.accept` (mode X25519 ou hybride) ou
//    `Session.acceptPsk` (clé partagée).
//
// `Erreur.Rejoue` : message déjà lu, à ignorer sans bruit.

package talka.brocolis

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import talka.brocolis.Json.bytes
import talka.brocolis.Json.number
import talka.brocolis.Json.optionalBytes

/** Ce que porte un message déchiffré. */
sealed class Content {
    /** Un texte (types 0x01 et 0x02). */
    data class Text(val text: String) : Content()

    /** Une chaîne de groupe (type 0x03). */
    data class Distribution(val distribution: SenderKeyDistribution) : Content()
}

/** Ce que l'initiateur répète dans chaque message de type 0x01 tant qu'il n'a pas reçu de réponse. */
internal class Preamble(
    val identity: ByteArray,
    val ephemeral: ByteArray,
    val mode: Mode,
    val kemCiphertext: ByteArray?,
    val spkId: UInt,
    val pqId: UInt,
) {
    fun toJson(): JsonObject = buildJsonObject {
        bytes("identity", identity)
        bytes("ephemeral", ephemeral)
        put("mode", JsonPrimitive(mode.name))
        optionalBytes("kem_ciphertext", kemCiphertext)
        number("spk_id", spkId)
        number("pq_id", pqId)
    }

    companion object {
        fun fromJson(o: JsonObject): Preamble {
            // `#[serde(with = "serde_b64::opt", default)]` : absent = `None`.
            val kem = if (o.containsKey("kem_ciphertext")) Json.optionalBytes(o, "kem_ciphertext") else null
            return Preamble(
                Json.bytes(o, "identity", 32), Json.bytes(o, "ephemeral", 32), Mode.fromJson(Json.string(Json.field(o, "mode"))),
                kem, Json.u32(o, "spk_id"), Json.u32(o, "pq_id"),
            )
        }
    }
}

/** Une clé de message sautée (message pas encore arrivé). */
internal class SkippedKey(val dh: ByteArray, val n: UInt, val mk: ByteArray, val storedAt: ULong) {
    fun copy() = SkippedKey(dh, n, mk.copyOf(), storedAt)

    fun toJson(): JsonObject = buildJsonObject {
        bytes("dh", dh)
        number("n", n)
        bytes("mk", mk)
        number("stored_at", storedAt)
    }

    companion object {
        fun fromJson(o: JsonObject) =
            SkippedKey(Json.bytes(o, "dh", 32), Json.u32(o, "n"), Json.bytes(o, "mk", 32), Json.u64(o, "stored_at"))
    }
}

/** L'état d'une session, tel qu'il est sérialisé (les champs de serde, dans leur ordre). */
internal class SessionState(
    var v: UByte,
    var mode: Mode,
    var ad: ByteArray,
    var sessionId: ByteArray,
    var rk: ByteArray,
    var dhsSecret: ByteArray,
    var dhsPublic: ByteArray,
    var dhr: ByteArray?,
    var cks: ByteArray?,
    var ckr: ByteArray?,
    var ns: UInt,
    var nr: UInt,
    var pn: UInt,
    val skipped: MutableList<SkippedKey>,
    val previousDhr: MutableList<ByteArray>,
    var preamble: Preamble?,
    var initialEphemeral: ByteArray?,
    var peerIdentity: ByteArray?,
) {
    /** Un état neuf ; chaque tableau reçu est copié (l'appelant efface les siens). */
    constructor(
        mode: Mode, ad: ByteArray, rk: ByteArray, dhsSecret: ByteArray, dhr: ByteArray?, cks: ByteArray?,
        preamble: Preamble?, initialEphemeral: ByteArray?, peerIdentity: ByteArray?,
    ) : this(
        1u, mode, ad.copyOf(), Kdf.sessionId(ad), rk.copyOf(), dhsSecret.copyOf(), Kdf.x25519Public(dhsSecret),
        dhr?.copyOf(), cks?.copyOf(), null, 0u, 0u, 0u, mutableListOf(), mutableListOf(), preamble,
        initialEphemeral?.copyOf(), peerIdentity?.copyOf(),
    )

    /** Une copie indépendante (aucun tableau secret partagé) : la transaction d'un déchiffrement. */
    fun copy() = SessionState(
        v, mode, ad.copyOf(), sessionId.copyOf(), rk.copyOf(), dhsSecret.copyOf(), dhsPublic.copyOf(), dhr?.copyOf(),
        cks?.copyOf(), ckr?.copyOf(), ns, nr, pn, skipped.mapTo(mutableListOf()) { it.copy() },
        previousDhr.mapTo(mutableListOf()) { it.copyOf() }, preamble, initialEphemeral?.copyOf(), peerIdentity?.copyOf(),
    )

    fun toJson(): JsonObject = buildJsonObject {
        number("v", v)
        put("mode", JsonPrimitive(mode.name))
        bytes("ad", ad)
        bytes("session_id", sessionId)
        bytes("rk", rk)
        bytes("dhs_secret", dhsSecret)
        bytes("dhs_public", dhsPublic)
        optionalBytes("dhr", dhr)
        optionalBytes("cks", cks)
        optionalBytes("ckr", ckr)
        number("ns", ns)
        number("nr", nr)
        number("pn", pn)
        put("skipped", JsonArray(skipped.map { it.toJson() }))
        put("previous_dhr", JsonArray(previousDhr.map { JsonPrimitive(Base64.encode(it)) }))
        put("preamble", preamble?.toJson() ?: JsonNull)
        optionalBytes("initial_ephemeral", initialEphemeral)
        optionalBytes("peer_identity", peerIdentity)
    }

    fun wipe() {
        effacer(rk)
        effacer(dhsSecret)
        effacer(cks)
        effacer(ckr)
        skipped.forEach { effacer(it.mk) }
    }

    // MARK: Envoi

    /**
     * Les données associées : `AD ‖ en-tête`, et `‖ 0x03` pour une
     * distribution (§7.6 : le type est ainsi authentifié).
     */
    fun aad(header: Header, kind: UByte): ByteArray =
        if (kind == JetonV3.typeDistribution) {
            Octets.concat(ad, header.toBytes(), byteArrayOf(JetonV3.typeDistribution.toByte()))
        } else {
            Octets.concat(ad, header.toBytes())
        }

    fun seal(plain: ByteArray, kind: UByte): Token {
        if (plain.size > Session.maxPlaintext) throw Erreur.TropGrand
        val ck = cks ?: throw Erreur.Etat
        if (ns == UInt.MAX_VALUE) throw Erreur.Epuise
        val (next, mk) = Kdf.kdfCk(ck)
        val header = Header(dhsPublic.copyOf(), pn, ns)
        val (key, nonce) = Kdf.messageKey(mk)
        val padded = Remplissage.pad(plain)
        val ciphertext = try {
            Kdf.aesGcmSeal(key, nonce, aad(header, kind), padded)
        } catch (e: Erreur) {
            effacer(next)
            throw e
        } finally {
            effacer(mk)
            effacer(key)
            effacer(padded)
        }
        effacer(cks)
        cks = next
        ns++
        val envelope = Envelope(sessionId.copyOf(), header, ciphertext)
        if (kind == JetonV3.typeDistribution) return Token.Distribution(envelope)
        val p = preamble
        if (p != null) {
            return Token.Initial(
                Initial(p.identity.copyOf(), p.ephemeral.copyOf(), p.mode, p.kemCiphertext?.copyOf(), p.spkId, p.pqId, envelope),
            )
        }
        return Token.Message(envelope)
    }

    // MARK: Réception

    fun open(mk: ByteArray, header: Header, ct: ByteArray, kind: UByte): ByteArray {
        val (key, nonce) = Kdf.messageKey(mk)
        try {
            val padded = Kdf.aesGcmOpen(key, nonce, aad(header, kind), ct)
            try {
                return Remplissage.unpad(padded)
            } finally {
                effacer(padded)
            }
        } finally {
            effacer(key)
        }
    }

    fun ratchetDecrypt(header: Header, ct: ByteArray, kind: UByte, now: ULong, rng: CryptoRng): ByteArray {
        purge(now)
        if (header.dh.contentEquals(dhsPublic)) {
            // Un de nos messages, renvoyé.
            throw Erreur.Rejoue
        }
        val pos = skipped.indexOfFirst { it.dh.contentEquals(header.dh) && it.n == header.n }
        if (pos >= 0) {
            val plain = open(skipped[pos].mk, header, ct, kind)
            effacer(skipped[pos].mk)
            skipped.removeAt(pos)
            return plain
        }
        val current = dhr?.contentEquals(header.dh) == true
        if (current && header.n < nr) throw Erreur.Rejoue
        if (!current && previousDhr.any { it.contentEquals(header.dh) }) throw Erreur.Rejoue
        if (!current) {
            skip(header.pn, now)
            dhRatchet(header, rng)
        }
        skip(header.n, now)
        val ck = ckr ?: throw Erreur.Etat
        val (next, mk) = Kdf.kdfCk(ck)
        try {
            effacer(ck)
            ckr = next
            if (nr == UInt.MAX_VALUE) throw Erreur.Epuise
            nr++
            return open(mk, header, ct, kind)
        } finally {
            effacer(mk)
        }
    }

    fun skip(until: UInt, now: ULong) {
        if (nr.toULong() + Protocole.maxSkip.toULong() < until.toULong()) throw Erreur.TropDeClesSautees
        var ck = ckr ?: return
        val dh = dhr ?: return
        while (nr < until) {
            val (next, mk) = Kdf.kdfCk(ck)
            skipped.add(SkippedKey(dh.copyOf(), nr, mk, now))
            effacer(ck)
            ck = next
            nr++
        }
        ckr = ck
        val max = Protocole.maxSkip.toInt()
        if (skipped.size > max) {
            val extra = skipped.size - max
            for (i in 0 until extra) effacer(skipped[i].mk)
            skipped.subList(0, extra).clear()
        }
    }

    fun dhRatchet(header: Header, rng: CryptoRng) {
        pn = ns
        ns = 0u
        nr = 0u
        dhr?.let {
            previousDhr.add(it)
            if (previousDhr.size > Session.previousDhrCount) previousDhr.removeAt(0)
        }
        dhr = header.dh.copyOf()
        val dh = Kdf.x25519(dhsSecret, header.dh)
        val (rk1, ckr1) = Kdf.kdfRk(rk, dh)
        effacer(dh)
        effacer(rk)
        rk = rk1
        effacer(ckr)
        ckr = ckr1
        val secret = rng.bytes(32)
        effacer(dhsSecret)
        dhsSecret = secret
        dhsPublic = Kdf.x25519Public(secret)
        val dh2 = Kdf.x25519(dhsSecret, header.dh)
        val (rk2, cks2) = Kdf.kdfRk(rk, dh2)
        effacer(dh2)
        effacer(rk)
        rk = rk2
        effacer(cks)
        cks = cks2
    }

    /** Efface les clés sautées de plus de 30 jours. */
    fun purge(now: ULong) {
        val old = skipped.filter { satAdd(it.storedAt, Protocole.maxSkipAge) <= now }
        old.forEach { effacer(it.mk) }
        skipped.removeAll(old)
    }

    companion object {
        fun fromJson(o: JsonObject): SessionState {
            val preamble = o["preamble"]?.let { if (it is JsonNull) null else Preamble.fromJson(Json.obj(it)) }
            return SessionState(
                Json.u8(o, "v"),
                Mode.fromJson(Json.string(Json.field(o, "mode"))),
                Json.bytes(o, "ad"),
                Json.bytes(o, "session_id", 8),
                Json.bytes(o, "rk", 32),
                Json.bytes(o, "dhs_secret", 32),
                Json.bytes(o, "dhs_public", 32),
                Json.optionalBytes(o, "dhr", 32),
                Json.optionalBytes(o, "cks", 32),
                Json.optionalBytes(o, "ckr", 32),
                Json.u32(o, "ns"),
                Json.u32(o, "nr"),
                Json.u32(o, "pn"),
                Json.array(Json.field(o, "skipped")).mapTo(mutableListOf()) { SkippedKey.fromJson(Json.obj(it)) },
                Json.array(Json.field(o, "previous_dhr")).mapTo(mutableListOf()) { Json.bytes(it, 32) },
                preamble,
                Json.optionalBytes(o, "initial_ephemeral", 32),
                Json.optionalBytes(o, "peer_identity", 32),
            )
        }
    }
}

/**
 * Une session à cliquet entre deux appareils. Sérialisable (JSON, de la même
 * forme que la référence) ; à garder chiffrée par la clé matérielle (§3.1).
 */
class Session internal constructor(internal var state: SessionState) {
    /** Les clés de chaîne courantes (envoi, réception). */
    class ChainKeys(val sending: ByteArray?, val receiving: ByteArray?) {
        operator fun component1() = sending
        operator fun component2() = receiving
    }

    // MARK: Envoi

    /** Chiffre un texte : le jeton décodé (type 0x01 tant que l'initiateur n'a pas reçu de réponse, 0x02 ensuite). */
    fun encryptToken(text: String): Token {
        val plain = Octets.bytes(text)
        try {
            return state.seal(plain, JetonV3.typeMessage)
        } finally {
            effacer(plain)
        }
    }

    /** Chiffre un texte : `[[BROCOLIS:v3:…]]`. */
    fun encrypt(text: String): String = encryptToken(text).encode()

    /** Envoie une chaîne de groupe (type 0x03) par cette session. */
    fun encryptDistribution(d: SenderKeyDistribution): String {
        val bytes = d.toBytes()
        try {
            return state.seal(bytes, JetonV3.typeDistribution).encode()
        } finally {
            effacer(bytes)
        }
    }

    // MARK: Réception

    /**
     * Déchiffre un jeton décodé de cette session. Transactionnel : en cas
     * d'erreur, la session n'a pas changé. Peut tirer une nouvelle clé de
     * cliquet (32 octets) si le message fait tourner le cliquet.
     */
    fun decrypt(token: Token, now: ULong, rng: CryptoRng): Content {
        val env: Envelope
        val kind: UByte
        when (token) {
            is Token.Initial -> {
                val i = token.initial
                if (!i.envelope.sessionId.contentEquals(state.sessionId)) throw Erreur.SessionInconnue
                val ek = state.initialEphemeral
                if (ek != null && ek.contentEquals(i.ephemeral)) {
                    env = i.envelope
                    kind = JetonV3.typeMessage
                } else if (state.preamble?.ephemeral?.contentEquals(i.ephemeral) == true) {
                    // Notre propre premier message, renvoyé : ignoré.
                    throw Erreur.Rejoue
                } else {
                    throw Erreur.NouvelleSession
                }
            }
            is Token.Message -> {
                env = token.envelope
                kind = JetonV3.typeMessage
            }
            is Token.Distribution -> {
                env = token.envelope
                kind = JetonV3.typeDistribution
            }
            is Token.Group -> throw Erreur.SessionInconnue
        }
        if (!env.sessionId.contentEquals(state.sessionId)) throw Erreur.SessionInconnue
        if (env.header.dh.size != 32) throw Erreur.Format
        val next = state.copy()
        val content: Content
        try {
            val plain = next.ratchetDecrypt(env.header, env.ciphertext, kind, now, rng)
            try {
                content = if (kind == JetonV3.typeDistribution) {
                    Content.Distribution(SenderKeyDistribution.fromBytes(plain))
                } else {
                    Content.Text(Octets.utf8(plain) ?: throw Erreur.Utf8)
                }
            } finally {
                effacer(plain)
            }
        } catch (e: Erreur) {
            next.wipe()
            throw e
        }
        // Une réponse est arrivée : l'initiateur passe aux messages 0x02.
        next.preamble = null
        val old = state
        state = next
        old.wipe()
        return content
    }

    /** Le premier déchiffrement d'une session neuve : refusé, elle est effacée. */
    private fun decryptOrWipe(msg: Initial, now: ULong, rng: CryptoRng): Content = try {
        decrypt(Token.Initial(msg), now, rng)
    } catch (e: Erreur) {
        state.wipe()
        throw e
    }

    /** `decrypt` d'un jeton texte `[[BROCOLIS:v3:…]]`. */
    fun decryptText(token: String, now: ULong, rng: CryptoRng): Content = decrypt(Token.decode(token), now, rng)

    // MARK: État

    /** Efface les clés sautées de plus de 30 jours. */
    fun purge(now: ULong) = state.purge(now)

    /** L'identifiant de session (8 octets). */
    val sessionId: ByteArray get() = state.sessionId.copyOf()

    /** AD = IK_A ‖ IK_B (ou EK_A ‖ R en clé partagée). */
    val associatedData: ByteArray get() = state.ad.copyOf()

    /** Le mode d'établissement. */
    val mode: Mode get() = state.mode

    /**
     * Vrai si la session est post-quantique (hybride X25519 + ML-KEM).
     * Sinon, l'appli l'affiche (« session non post-quantique »).
     */
    val isPostQuantum: Boolean get() = state.mode == Mode.Hybrid

    /** Vrai si l'autre côté a répondu (l'initiateur envoie des messages 0x01 jusque-là). */
    val isConfirmed: Boolean get() = state.preamble == null

    /**
     * IK X25519 du correspondant (absent en clé partagée) : l'appli le
     * compare à celui du contact (alerte si une clé d'identité change).
     */
    val peerIdentity: ByteArray? get() = state.peerIdentity?.copyOf()

    /** Le nombre de clés sautées gardées. */
    val skippedCount: Int get() = state.skipped.size

    /** La clé publique de cliquet courante (pour les vecteurs). */
    val ratchetPublic: ByteArray get() = state.dhsPublic.copyOf()

    /** La clé racine courante (pour les vecteurs : un secret !). */
    val debugRootKey: ByteArray get() = state.rk.copyOf()

    /** Les clés de chaîne courantes (envoi, réception) — vecteurs seulement. */
    val debugChainKeys: ChainKeys get() = ChainKeys(state.cks?.copyOf(), state.ckr?.copyOf())

    /** L'état en JSON (même forme que `Session::to_json`). */
    fun toJson(): String = state.toJson().toString()

    /** Efface les secrets de la session (au mieux) ; elle ne sert plus ensuite. */
    fun wipe() = state.wipe()

    companion object {
        /**
         * Le plus long texte (en octets UTF-8) qu'un message v3 peut porter :
         * le jeton reste sous `JetonV3.maxTokenBytes`.
         */
        const val maxPlaintext = 200 * 1024

        /** Le nombre de clés de cliquet distantes passées dont on se souvient pour reconnaître un rejeu. */
        internal const val previousDhrCount = 16

        // MARK: Création

        internal fun newInitiator(
            mode: Mode, sk: ByteArray, ad: ByteArray, ratchetSecret: ByteArray, remoteRatchet: ByteArray,
            preamble: Preamble, peerIdentity: ByteArray?,
        ): Session {
            val dh = Kdf.x25519(ratchetSecret, remoteRatchet)
            try {
                val (rk, cks) = Kdf.kdfRk(sk, dh)
                try {
                    return Session(SessionState(mode, ad, rk, ratchetSecret, remoteRatchet, cks, preamble, null, peerIdentity))
                } finally {
                    effacer(rk)
                    effacer(cks)
                }
            } finally {
                effacer(dh)
            }
        }

        internal fun newResponder(
            mode: Mode, sk: ByteArray, ad: ByteArray, ratchetSecret: ByteArray, initialEphemeral: ByteArray,
            peerIdentity: ByteArray?,
        ): Session = Session(SessionState(mode, ad, sk, ratchetSecret, null, null, null, initialEphemeral, peerIdentity))

        /**
         * Ouvre une session vers l'appareil du paquet (§4.2), avec ML-KEM si
         * `pq` (le paquet doit alors en proposer : sinon `Erreur.PqIndisponible`).
         *
         * Ordre de tirage : secret EK (32), secret de cliquet (32), puis, avec
         * `pq`, le message ML-KEM `m` (32) : exactement la référence, chiffré
         * ML-KEM compris.
         */
        fun initiate(identity: IdentitySecret, bundle: KeyBundle, pq: Boolean, rng: CryptoRng): Session {
            bundle.verify()
            val ek = rng.bytes(32)
            val ratchet = rng.bytes(32)
            val m = if (pq) rng.bytes(32) else null
            try {
                return establish(identity, bundle, ek, ratchet) {
                    if (m == null) return@establish null
                    val p = bundle.pq ?: throw Erreur.PqIndisponible
                    MlKem.encapsulate(p.key, m)
                }
            } finally {
                effacer(ek)
                effacer(ratchet)
                effacer(m)
            }
        }

        /**
         * `initiate` en mode hybride si le paquet propose ML-KEM et que cet
         * appareil sait le faire, en X25519 seul sinon (« session non
         * post-quantique », §4.2).
         */
        fun initiateBest(identity: IdentitySecret, bundle: KeyBundle, rng: CryptoRng): Session =
            initiate(identity, bundle, bundle.pq != null && MlKem.isAvailable, rng)

        /**
         * `initiate` avec le hasard donné (les vecteurs). `kem` : le résultat
         * d'une encapsulation ML-KEM vers la PQPK du paquet (mode hybride), ou
         * `null` (X25519 seul). Le paquet doit avoir été vérifié
         * (`KeyBundle.fromBytes` le fait).
         */
        fun initiateWith(
            identity: IdentitySecret, bundle: KeyBundle, ekSecret: ByteArray, ratchetSecret: ByteArray,
            kem: KemEncapsulation?,
        ): Session = establish(identity, bundle, ekSecret, ratchetSecret) {
            if (kem == null) return@establish null
            if (bundle.pq == null) throw Erreur.PqIndisponible
            if (kem.ciphertext.size != JetonV3.kemCtLength || kem.shared.size != 32) throw Erreur.Format
            kem
        }

        private fun establish(
            identity: IdentitySecret, bundle: KeyBundle, ekSecret: ByteArray, ratchetSecret: ByteArray,
            kem: () -> KemEncapsulation?,
        ): Session {
            if (ekSecret.size != 32 || ratchetSecret.size != 32 || bundle.spk.size != 32 || bundle.identity.dh.size != 32) {
                throw Erreur.Format
            }
            val ikA = Kdf.x25519Public(identity.dh)
            val ikB = bundle.identity.dh.copyOf()
            val secrets = mutableListOf<ByteArray>()
            try {
                val dh1 = Kdf.x25519(identity.dh, bundle.spk).also { secrets.add(it) }
                val dh2 = Kdf.x25519(ekSecret, ikB).also { secrets.add(it) }
                val dh3 = Kdf.x25519(ekSecret, bundle.spk).also { secrets.add(it) }
                val r = kem()
                val mode = if (r != null) Mode.Hybrid else Mode.Classic
                val ss = r?.shared?.copyOf()?.also { secrets.add(it) }
                val pqId = if (r != null) bundle.pq?.id ?: 0u else 0u
                val sk = Kdf.skX3dh(dh1, dh2, dh3, ss).also { secrets.add(it) }
                val preamble = Preamble(ikA, Kdf.x25519Public(ekSecret), mode, r?.ciphertext?.copyOf(), bundle.spkId, pqId)
                return newInitiator(mode, sk, Octets.concat(ikA, ikB), ratchetSecret, bundle.spk, preamble, ikB)
            } finally {
                secrets.forEach { effacer(it) }
            }
        }

        /**
         * Ouvre une session à partir d'une clé Brocolis partagée (§4.2, §7.4,
         * sans appairage ; **deux personnes seulement**). Tire le secret EK
         * (32), qui sert aussi de première clé de cliquet.
         */
        fun initiatePsk(key: ByteArray, rng: CryptoRng): Session {
            val ek = rng.bytes(32)
            try {
                return initiatePskWith(key, ek)
            } finally {
                effacer(ek)
            }
        }

        /**
         * `initiatePsk` avec le hasard donné.
         *
         * SK = `HKDF(0³², clé, "Brocolis_v3_PSK_SHA-256")` ; la clé de cliquet
         * initiale du répondeur R est le secret X25519
         * `HKDF(0³², SK, "Brocolis_v3_PSK_Ratchet")` (connu des deux) ;
         * AD = EK_A ‖ R ; la première clé de cliquet de A est EK_A.
         */
        fun initiatePskWith(key: ByteArray, ekSecret: ByteArray): Session {
            if (key.size != 32 || ekSecret.size != 32) throw Erreur.Format
            val sk = Kdf.skPsk(key)
            val rSecret = Kdf.pskRatchetSecret(sk)
            try {
                val r = Kdf.x25519Public(rSecret)
                val ek = Kdf.x25519Public(ekSecret)
                val preamble = Preamble(ByteArray(32), ek, Mode.Psk, null, 0u, 0u)
                return newInitiator(Mode.Psk, sk, Octets.concat(ek, r), ekSecret, r, preamble, null)
            } finally {
                effacer(sk)
                effacer(rSecret)
            }
        }

        /**
         * Accepte le premier message d'une session à clé partagée : essaie
         * chaque clé (l'identifiant de session dit laquelle). `guard` retient
         * les clés éphémères déjà vues (un premier message rejoué est refusé :
         * `Erreur.Rejoue`) ; il n'est modifié qu'en cas de succès.
         */
        fun acceptPsk(
            keys: List<ByteArray>, message: Initial, guard: ReplayGuard, now: ULong, rng: CryptoRng,
        ): Pair<Session, Content> {
            val msg = message
            if (msg.mode != Mode.Psk || !msg.envelope.header.dh.contentEquals(msg.ephemeral)) throw Erreur.Format
            if (guard.seen(msg.ephemeral)) throw Erreur.Rejoue
            for (key in keys) {
                if (key.size != 32) continue
                val sk = Kdf.skPsk(key)
                val rSecret = Kdf.pskRatchetSecret(sk)
                try {
                    val ad = Octets.concat(msg.ephemeral, Kdf.x25519Public(rSecret))
                    if (Octets.ctEq(Kdf.sessionId(ad), msg.envelope.sessionId)) {
                        val s = newResponder(Mode.Psk, sk, ad, rSecret, msg.ephemeral, null)
                        val content = s.decryptOrWipe(msg, now, rng)
                        guard.insert(msg.ephemeral, now)
                        return s to content
                    }
                } finally {
                    effacer(sk)
                    effacer(rSecret)
                }
            }
            throw Erreur.SessionInconnue
        }

        /**
         * Accepte le premier message (type 0x01) d'une nouvelle session, en
         * mode X25519 ou hybride (`Device.accept`).
         */
        internal fun accept(device: Device, msg: Initial, now: ULong, rng: CryptoRng): Pair<Session, Content> {
            val state = device.state
            if (msg.mode == Mode.Psk) throw Erreur.Format
            if (state.replay.seen(msg.ephemeral)) throw Erreur.Rejoue
            val spk = device.spkSecret(msg.spkId)
            val secrets = mutableListOf(spk)
            try {
                val own = state.identity
                val dh1 = Kdf.x25519(spk, msg.identity).also { secrets.add(it) }
                val dh2 = Kdf.x25519(own.dh, msg.ephemeral).also { secrets.add(it) }
                val dh3 = Kdf.x25519(spk, msg.ephemeral).also { secrets.add(it) }
                val ct = msg.kemCiphertext
                val ss: ByteArray? = when {
                    msg.mode == Mode.Hybrid && ct != null -> {
                        val seed = device.pqSeed(msg.pqId).also { secrets.add(it) }
                        MlKem.decapsulate(seed, ct).also { secrets.add(it) }
                    }
                    msg.mode == Mode.Classic && ct == null -> null
                    else -> throw Erreur.Format
                }
                val sk = Kdf.skX3dh(dh1, dh2, dh3, ss).also { secrets.add(it) }
                val ad = Octets.concat(msg.identity, Kdf.x25519Public(own.dh))
                if (!Kdf.sessionId(ad).contentEquals(msg.envelope.sessionId)) throw Erreur.SessionInconnue
                val s = newResponder(msg.mode, sk, ad, spk, msg.ephemeral, msg.identity)
                val content = s.decryptOrWipe(msg, now, rng)
                state.replay.insert(msg.ephemeral, now)
                return s to content
            } finally {
                secrets.forEach { effacer(it) }
            }
        }

        /**
         * Relit un état JSON (celui de `toJson` ou de la référence) et vérifie
         * sa cohérence.
         */
        fun fromJson(text: String): Session {
            val s = Json.lire { SessionState.fromJson(Json.parse(text)) }
            val adOk = s.ad.size == 64 && Kdf.sessionId(s.ad).contentEquals(s.sessionId)
            if (s.v.toInt() != 1 || !adOk || s.skipped.size > Protocole.maxSkip.toInt() ||
                !Kdf.x25519Public(s.dhsSecret).contentEquals(s.dhsPublic)
            ) {
                throw Erreur.Etat
            }
            return Session(s)
        }
    }
}
