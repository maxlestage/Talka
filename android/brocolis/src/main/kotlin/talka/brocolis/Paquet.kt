// L'appareil, ses clés et son paquet de clés publiques (§4.1, §7.3) —
// `brocolis_proto::paquet`.
//
// Chaque appareil a une identité (IK : X25519 pour l'échange, Ed25519 pour
// signer), une clé pré-signée X25519 (SPK) et une clé d'encapsulation
// ML-KEM-768 signée (PQPK), toutes deux renouvelées chaque semaine (les
// anciennes gardées un mois pour les messages en retard).
//
// Le paquet (format binaire v1) :
//
//     octet 0          : 0x01 (version du paquet)
//     octets 1–32      : IK X25519
//     octets 33–64     : IK Ed25519 (clé de vérification)
//     octets 65–68     : id SPK (u32 BE)
//     octets 69–100    : SPK (X25519)
//     octets 101–164   : signature Ed25519 de "Brocolis_v3_SPK" ‖ IK X25519 ‖ id SPK ‖ SPK
//     octet 165        : 0x00 sans ML-KEM, 0x01 avec
//     si 0x01 :
//       octets 166–169   : id PQPK (u32 BE)
//       octets 170–1353  : PQPK (clé d'encapsulation ML-KEM-768, 1 184 octets)
//       octets 1354–1417 : signature Ed25519 de "Brocolis_v3_PQPK" ‖ IK X25519 ‖ id PQPK ‖ PQPK
//
// 166 octets sans ML-KEM, 1 418 avec.

package talka.brocolis

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import talka.brocolis.Json.bytes
import talka.brocolis.Json.number
import talka.brocolis.Json.optionalNumber

object Paquet {
    /** La version du format binaire du paquet. */
    const val bundleVersion: UByte = 0x01u

    /** La longueur d'une clé d'encapsulation ML-KEM-768. */
    const val pqpkLength = 1_184

    /** La longueur d'un paquet sans ML-KEM. */
    const val bundleLengthClassic = 166

    /** La longueur d'un paquet avec ML-KEM. */
    const val bundleLengthPq = 1_418

    /** Le contexte signé avec la SPK. */
    val sigContextSpk: ByteArray get() = Octets.bytes("Brocolis_v3_SPK")

    /** Le contexte signé avec la PQPK. */
    val sigContextPqpk: ByteArray get() = Octets.bytes("Brocolis_v3_PQPK")

    /** Le renouvellement des clés pré-signées : chaque semaine. */
    const val prekeyRotation: ULong = 604_800uL // 7 × Protocole.jour

    /** Une clé pré-signée remplacée est gardée un mois (30 jours). */
    const val prekeyGrace: ULong = 2_592_000uL // 30 × Protocole.jour

    internal fun spkSignedMessage(ik: ByteArray, id: UInt, spk: ByteArray): ByteArray =
        Octets.concat(sigContextSpk, ik, Octets.u32be(id), spk)

    internal fun pqpkSignedMessage(ik: ByteArray, id: UInt, pqpk: ByteArray): ByteArray =
        Octets.concat(sigContextPqpk, ik, Octets.u32be(id), pqpk)
}

/** Ce que l'appareil doit garder secret de son identité. */
class IdentitySecret(dh: ByteArray, sign: ByteArray) {
    /** Le secret X25519 (32 octets bruts). */
    var dh: ByteArray = dh.copyOf()
        internal set

    /** La graine Ed25519 (32 octets). */
    var sign: ByteArray = sign.copyOf()
        internal set

    init {
        // Une identité à partir de ses deux secrets de 32 octets.
        if (dh.size != 32 || sign.size != 32) throw Erreur.Format
    }

    /** L'identité publique. */
    fun publicKey(): IdentityPublic = IdentityPublic(Kdf.x25519Public(dh), Kdf.ed25519Public(sign))

    internal fun copy(): IdentitySecret = IdentitySecret(dh, sign)

    internal fun toJson(): JsonObject = buildJsonObject {
        bytes("dh", dh)
        bytes("sign", sign)
    }

    internal fun wipe() {
        effacer(dh)
        effacer(sign)
    }

    internal companion object {
        fun fromJson(o: JsonObject): IdentitySecret = IdentitySecret(Json.bytes(o, "dh", 32), Json.bytes(o, "sign", 32))
    }
}

/** L'identité publique d'un appareil : IK X25519 et IK Ed25519. */
data class IdentityPublic(
    /** IK X25519. */
    var dh: ByteArray,
    /** IK Ed25519 (clé de vérification). */
    var sign: ByteArray,
) {
    /**
     * L'empreinte de l'identité, à comparer de vive voix :
     * `SHA-256(IK X25519 ‖ IK Ed25519)[0..8]` en `XXXX-XXXX-XXXX-XXXX`.
     */
    fun fingerprint(): String = Kdf.fingerprint(Octets.concat(dh, sign))

    override fun equals(other: Any?): Boolean = other is IdentityPublic && dh.contentEquals(other.dh) && sign.contentEquals(other.sign)

    override fun hashCode(): Int = dh.contentHashCode() * 31 + sign.contentHashCode()
}

/** Une clé pré-signée X25519 (secrète). */
class SignedPreKey internal constructor(id: UInt, secret: ByteArray, createdAt: ULong, replacedAt: ULong?) {
    /** Son identifiant. */
    var id: UInt = id
        internal set

    /** Le secret X25519. */
    var secret: ByteArray = secret
        internal set

    /** Sa création (secondes Unix). */
    var createdAt: ULong = createdAt
        internal set

    /** Son remplacement (secondes Unix), si elle n'est plus la courante. */
    var replacedAt: ULong? = replacedAt
        internal set

    internal fun copy() = SignedPreKey(id, secret.copyOf(), createdAt, replacedAt)

    internal fun toJson(): JsonObject = buildJsonObject {
        number("id", id)
        bytes("secret", secret)
        number("created_at", createdAt)
        optionalNumber("replaced_at", replacedAt)
    }

    internal companion object {
        fun fromJson(o: JsonObject) = SignedPreKey(
            Json.u32(o, "id"), Json.bytes(o, "secret", 32), Json.u64(o, "created_at"), Json.optionalU64(o, "replaced_at"),
        )
    }
}

/** Une clé ML-KEM-768 (secrète : sa graine FIPS 203 `d ‖ z`, 64 octets). */
class PqPreKey internal constructor(id: UInt, seed: ByteArray, createdAt: ULong, replacedAt: ULong?) {
    /** Son identifiant. */
    var id: UInt = id
        internal set

    /** La graine `d ‖ z`. */
    var seed: ByteArray = seed
        internal set

    /** Sa création (secondes Unix). */
    var createdAt: ULong = createdAt
        internal set

    /** Son remplacement (secondes Unix), si elle n'est plus la courante. */
    var replacedAt: ULong? = replacedAt
        internal set

    internal fun copy() = PqPreKey(id, seed.copyOf(), createdAt, replacedAt)

    internal fun toJson(): JsonObject = buildJsonObject {
        number("id", id)
        bytes("seed", seed)
        number("created_at", createdAt)
        optionalNumber("replaced_at", replacedAt)
    }

    internal companion object {
        fun fromJson(o: JsonObject) = PqPreKey(
            Json.u32(o, "id"), Json.bytes(o, "seed", 64), Json.u64(o, "created_at"), Json.optionalU64(o, "replaced_at"),
        )
    }
}

/** La partie ML-KEM d'un paquet. */
data class PqBundle(
    /** L'identifiant de la PQPK. */
    var id: UInt,
    /** La clé d'encapsulation ML-KEM-768 (1 184 octets). */
    var key: ByteArray,
    /** Sa signature par IK Ed25519. */
    var signature: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PqBundle && id == other.id && key.contentEquals(other.key) && signature.contentEquals(other.signature)

    override fun hashCode(): Int = (id.hashCode() * 31 + key.contentHashCode()) * 31 + signature.contentHashCode()
}

/** Un paquet de clés publiques. `fromBytes` le vérifie entièrement. */
data class KeyBundle(
    /** L'identité. */
    var identity: IdentityPublic,
    /** L'identifiant de la SPK. */
    var spkId: UInt,
    /** La SPK. */
    var spk: ByteArray,
    /** Sa signature par IK Ed25519. */
    var spkSignature: ByteArray,
    /** La PQPK, si l'appareil sait faire ML-KEM. */
    var pq: PqBundle?,
) {
    /** Les octets du paquet (format v1). */
    fun toBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream(Paquet.bundleLengthPq)
        out.write(Paquet.bundleVersion.toInt())
        out.write(identity.dh)
        out.write(identity.sign)
        out.write(Octets.u32be(spkId))
        out.write(spk)
        out.write(spkSignature)
        val p = pq
        if (p != null) {
            out.write(0x01)
            out.write(Octets.u32be(p.id))
            out.write(p.key)
            out.write(p.signature)
        } else {
            out.write(0x00)
        }
        return out.toByteArray()
    }

    /** Vérifie les clés et les signatures du paquet. */
    fun verify() {
        if (identity.dh.size != 32 || identity.sign.size != 32 || spk.size != 32 || spkSignature.size != 64) {
            throw Erreur.Format
        }
        for (key in listOf(identity.dh, spk)) {
            if (!Kdf.x25519IsCanonical(key) || Octets.isZero(key)) throw Erreur.CleInvalide
        }
        Kdf.ed25519Verify(identity.sign, Paquet.spkSignedMessage(identity.dh, spkId, spk), spkSignature)
        val p = pq ?: return
        if (p.key.size != Paquet.pqpkLength || p.id == 0u) throw Erreur.Format
        Kdf.ed25519Verify(identity.sign, Paquet.pqpkSignedMessage(identity.dh, p.id, p.key), p.signature)
        // La clé doit être une clé ML-KEM valide (FIPS 203, contrôle du
        // module).
        if (!MlKem.isValidPublicKey(p.key)) throw Erreur.CleInvalide
    }

    /** Le paquet en base64 standard. */
    fun toBase64(): String = Base64.encode(toBytes())

    override fun equals(other: Any?): Boolean =
        other is KeyBundle && identity == other.identity && spkId == other.spkId && spk.contentEquals(other.spk) &&
            spkSignature.contentEquals(other.spkSignature) && pq == other.pq

    override fun hashCode(): Int =
        listOf(identity.hashCode(), spkId.hashCode(), spk.contentHashCode(), spkSignature.contentHashCode(), pq.hashCode()).hashCode()

    companion object {
        /**
         * Lit un paquet et **vérifie** tout : version, longueur exacte, clés
         * (Ed25519 non faible, X25519 canonique non nulle, ML-KEM bien formée)
         * et signatures (Ed25519 stricte).
         */
        fun fromBytes(data: ByteArray): KeyBundle {
            if (data.size != Paquet.bundleLengthClassic && data.size != Paquet.bundleLengthPq) throw Erreur.Format
            if (data[0] != Paquet.bundleVersion.toByte()) throw Erreur.Format
            val identity = IdentityPublic(data.copyOfRange(1, 33), data.copyOfRange(33, 65))
            val spkId = Octets.readU32(data, 65)
            val spk = data.copyOfRange(69, 101)
            val spkSignature = data.copyOfRange(101, 165)
            val flag = data[165].toInt()
            val pq = when {
                flag == 0x00 && data.size == Paquet.bundleLengthClassic -> null
                flag == 0x01 && data.size == Paquet.bundleLengthPq -> PqBundle(
                    Octets.readU32(data, 166), data.copyOfRange(170, 170 + Paquet.pqpkLength), data.copyOfRange(1354, 1418),
                )
                else -> throw Erreur.Format
            }
            val bundle = KeyBundle(identity, spkId, spk, spkSignature, pq)
            bundle.verify()
            return bundle
        }

        /** Lit (et vérifie) un paquet en base64 standard. */
        fun fromBase64(text: String): KeyBundle {
            if (Octets.bytes(text).size > 2 * Paquet.bundleLengthPq) throw Erreur.TropGrand
            val data = Base64.decode(Jetons.trim(text)) ?: throw Erreur.Base64
            return fromBytes(data)
        }
    }
}

/**
 * Les clés éphémères déjà vues dans un premier message (type 0x01) :
 * rejouer un premier message ne rouvre pas de session (§7.3). Bornée :
 * `ReplayGuard.max` entrées, `ReplayGuard.maxAge`.
 */
class ReplayGuard() {
    internal class SeenEphemeral(val ek: ByteArray, val at: ULong)

    internal val seenList = mutableListOf<SeenEphemeral>()

    /** Vrai si cette clé éphémère a déjà ouvert une session. */
    fun seen(ek: ByteArray): Boolean = seenList.any { it.ek.contentEquals(ek) }

    /** Retient une clé éphémère. */
    fun insert(ek: ByteArray, now: ULong) {
        purge(now)
        seenList.add(SeenEphemeral(ek.copyOf(), now))
        if (seenList.size > max) {
            seenList.subList(0, seenList.size - max).clear()
        }
    }

    /** Oublie les entrées trop vieilles. */
    fun purge(now: ULong) {
        seenList.removeAll { satAdd(it.at, maxAge) <= now }
    }

    /** Le nombre d'entrées. */
    val count: Int get() = seenList.size

    /** Vrai si aucune entrée. */
    val isEmpty: Boolean get() = seenList.isEmpty()

    /** Une copie indépendante. */
    fun copy(): ReplayGuard = ReplayGuard().also { g -> seenList.forEach { g.seenList.add(SeenEphemeral(it.ek.copyOf(), it.at)) } }

    internal fun toJsonObject(): JsonObject = buildJsonObject {
        put("seen", buildJsonArray {
            for (s in seenList) {
                add(buildJsonObject {
                    bytes("ek", s.ek)
                    number("at", s.at)
                })
            }
        })
    }

    /** L'état en JSON (`{"seen":[{"ek":…,"at":…}]}`, comme serde). */
    fun toJson(): String = toJsonObject().toString()

    companion object {
        /** Le nombre maximal de clés retenues (les plus anciennes partent). */
        const val max = 4_096

        /** L'âge maximal d'une entrée : la vie d'une clé pré-signée (7 + 30 jours). */
        const val maxAge: ULong = 3_196_800uL // Paquet.prekeyRotation + Paquet.prekeyGrace

        internal fun fromJsonObject(o: JsonObject): ReplayGuard {
            val g = ReplayGuard()
            for (e in Json.array(Json.field(o, "seen"))) {
                val s = Json.obj(e)
                g.seenList.add(SeenEphemeral(Json.bytes(s, "ek", 32), Json.u64(s, "at")))
            }
            return g
        }

        /** Relit un état JSON. */
        fun fromJson(text: String): ReplayGuard = Json.lire { fromJsonObject(Json.parse(text)) }
    }
}

/** L'état d'un appareil, tel qu'il est sérialisé (champs de serde). */
internal class DeviceState(
    var v: UByte,
    var identity: IdentitySecret,
    val signedPrekeys: MutableList<SignedPreKey>,
    val pqPrekeys: MutableList<PqPreKey>,
    var replay: ReplayGuard,
) {
    fun toJson(): JsonObject = buildJsonObject {
        number("v", v)
        put("identity", identity.toJson())
        put("signed_prekeys", JsonArray(signedPrekeys.map { it.toJson() }))
        put("pq_prekeys", JsonArray(pqPrekeys.map { it.toJson() }))
        put("replay", replay.toJsonObject())
    }

    fun wipe() {
        identity.wipe()
        signedPrekeys.forEach { effacer(it.secret) }
        pqPrekeys.forEach { effacer(it.seed) }
    }

    companion object {
        fun fromJson(o: JsonObject): DeviceState = DeviceState(
            Json.u8(o, "v"),
            IdentitySecret.fromJson(Json.obj(Json.field(o, "identity"))),
            Json.array(Json.field(o, "signed_prekeys")).map { SignedPreKey.fromJson(Json.obj(it)) }.toMutableList(),
            Json.array(Json.field(o, "pq_prekeys")).map { PqPreKey.fromJson(Json.obj(it)) }.toMutableList(),
            // `#[serde(default)]`.
            o["replay"]?.let { ReplayGuard.fromJsonObject(Json.obj(it)) } ?: ReplayGuard(),
        )
    }
}

/**
 * Un appareil : son identité, ses clés pré-signées (la courante et celles
 * gardées pour les retardataires) et la mémoire des premiers messages.
 * Sérialisable (JSON) ; à garder chiffré par la clé matérielle (§3.1).
 */
class Device internal constructor(internal var state: DeviceState) {
    /** La version du format de l'état. */
    val v: UByte get() = state.v

    internal fun addPrekeys(rng: CryptoRng, now: ULong, pq: Boolean) {
        val spkId = nextId(rng, state.signedPrekeys.lastOrNull()?.id)
        val secret = rng.bytes(32)
        for (k in state.signedPrekeys) if (k.replacedAt == null) k.replacedAt = now
        state.signedPrekeys.add(SignedPreKey(spkId, secret, now, null))
        if (pq) {
            val pqId = nextId(rng, state.pqPrekeys.lastOrNull()?.id)
            val seed = rng.bytes(64)
            for (k in state.pqPrekeys) if (k.replacedAt == null) k.replacedAt = now
            state.pqPrekeys.add(PqPreKey(pqId, seed, now, null))
        }
    }

    /**
     * Renouvelle la SPK (et la PQPK si l'appareil en a une) ; les anciennes
     * restent utilisables `Paquet.prekeyGrace`, puis `purge` les efface.
     *
     * Ordre de tirage : id SPK (4 octets, seulement la première fois),
     * secret SPK (32), id PQPK (4, la première fois), graine ML-KEM (64).
     */
    fun rotate(rng: CryptoRng, now: ULong) {
        addPrekeys(rng, now, state.pqPrekeys.isNotEmpty())
        purge(now)
    }

    /**
     * Ajoute ML-KEM à un appareil qui n'en avait pas, en renouvelant la SPK.
     * `Erreur.PqIndisponible` si ML-KEM manque ici.
     */
    fun enablePq(rng: CryptoRng, now: ULong) {
        if (!MlKem.isAvailable) throw Erreur.PqIndisponible
        addPrekeys(rng, now, true)
    }

    /** Vrai si la SPK courante a une semaine ou plus. */
    fun needsRotation(now: ULong): Boolean {
        val k = state.signedPrekeys.lastOrNull() ?: return true
        return satAdd(k.createdAt, Paquet.prekeyRotation) <= now
    }

    /**
     * Efface les clés remplacées depuis plus d'un mois et les vieilles
     * entrées de la mémoire des premiers messages.
     */
    fun purge(now: ULong) {
        fun keep(r: ULong?): Boolean = r == null || satAdd(r, Paquet.prekeyGrace) > now
        state.signedPrekeys.filter { !keep(it.replacedAt) }.forEach { effacer(it.secret) }
        state.pqPrekeys.filter { !keep(it.replacedAt) }.forEach { effacer(it.seed) }
        state.signedPrekeys.removeAll { !keep(it.replacedAt) }
        state.pqPrekeys.removeAll { !keep(it.replacedAt) }
        state.replay.purge(now)
    }

    /** L'identité publique. */
    fun identity(): IdentityPublic = state.identity.publicKey()

    /** L'identité secrète (pour l'établissement ; une copie). */
    val identitySecret: IdentitySecret get() = state.identity.copy()

    /** Vrai si l'appareil a une clé ML-KEM courante (et sait s'en servir ici). */
    val supportsPq: Boolean get() = MlKem.isAvailable && state.pqPrekeys.any { it.replacedAt == null }

    /** Le paquet de clés publiques courant, signé. */
    fun bundle(): KeyBundle {
        val ik = state.identity.publicKey()
        val spkKey = state.signedPrekeys.lastOrNull { it.replacedAt == null } ?: throw Erreur.Etat
        val spk = Kdf.x25519Public(spkKey.secret)
        val spkSignature = Kdf.ed25519Sign(state.identity.sign, Paquet.spkSignedMessage(ik.dh, spkKey.id, spk))
        var pq: PqBundle? = null
        val k = state.pqPrekeys.lastOrNull { it.replacedAt == null }
        if (MlKem.isAvailable && k != null) {
            val key = MlKem.publicKey(k.seed)
            val signature = Kdf.ed25519Sign(state.identity.sign, Paquet.pqpkSignedMessage(ik.dh, k.id, key))
            pq = PqBundle(k.id, key, signature)
        }
        return KeyBundle(ik, spkKey.id, spk, spkSignature, pq)
    }

    /** Le secret d'une SPK (une copie, à effacer après usage). */
    internal fun spkSecret(id: UInt): ByteArray =
        (state.signedPrekeys.firstOrNull { it.id == id } ?: throw Erreur.CleInconnue).secret.copyOf()

    /** La graine d'une PQPK (une copie, à effacer après usage). */
    internal fun pqSeed(id: UInt): ByteArray =
        (state.pqPrekeys.firstOrNull { it.id == id } ?: throw Erreur.CleInconnue).seed.copyOf()

    /** La mémoire des premiers messages (une copie). */
    val replayGuard: ReplayGuard get() = state.replay.copy()

    /** L'état en JSON (même forme que `Device::to_json`). */
    fun toJson(): String = state.toJson().toString()

    /** Efface les secrets de l'appareil (au mieux) ; il ne sert plus ensuite. */
    fun wipe() = state.wipe()

    // MARK: Sessions

    /** Ouvre une session vers un paquet (`Session.initiate`). */
    fun initiate(bundle: KeyBundle, pq: Boolean, rng: CryptoRng): Session =
        Session.initiate(state.identity, bundle, pq, rng)

    /** Ouvre une session vers un paquet, hybride si possible (`Session.initiateBest`). */
    fun initiateBest(bundle: KeyBundle, rng: CryptoRng): Session = Session.initiateBest(state.identity, bundle, rng)

    /**
     * Accepte le premier message (type 0x01) d'une nouvelle session, en mode
     * X25519 ou hybride : retrouve la SPK (et la PQPK) désignées, calcule SK,
     * déchiffre. Un premier message dont la clé éphémère a déjà servi est
     * refusé (`Erreur.Rejoue`).
     */
    fun accept(msg: Initial, now: ULong, rng: CryptoRng): Pair<Session, Content> = Session.accept(this, msg, now, rng)

    companion object {
        internal fun nextId(rng: CryptoRng, last: UInt?): UInt {
            if (last != null) return maxOf(last + 1u, 1u)
            return maxOf(rng.nextU32() shr 1, 1u)
        }

        /**
         * Un nouvel appareil : identité, SPK et, avec `pq` (et si ML-KEM est
         * disponible ici : `MlKem.isAvailable`), PQPK.
         *
         * Ordre de tirage (vecteurs) : secret X25519 d'IK (32), graine Ed25519
         * (32), puis comme `rotate`.
         */
        fun generate(rng: CryptoRng, now: ULong, pq: Boolean): Device {
            val dh = rng.bytes(32)
            val sign = rng.bytes(32)
            val identity = IdentitySecret(dh, sign)
            effacer(dh)
            effacer(sign)
            return fromIdentity(identity, rng, now, pq)
        }

        /** Un appareil à partir d'une identité existante (nouvelles SPK et PQPK). */
        fun fromIdentity(identity: IdentitySecret, rng: CryptoRng, now: ULong, pq: Boolean): Device {
            val d = Device(DeviceState(1u, identity.copy(), mutableListOf(), mutableListOf(), ReplayGuard()))
            d.addPrekeys(rng, now, pq && MlKem.isAvailable)
            return d
        }

        /**
         * Un appareil à partir de secrets donnés (vecteurs, import) : identité,
         * SPK (id, secret de 32 octets) et, optionnellement, PQPK (id, graine
         * `d ‖ z` de 64 octets).
         */
        fun fromSecrets(identity: IdentitySecret, spk: Pair<UInt, ByteArray>, pq: Pair<UInt, ByteArray>?, now: ULong): Device {
            if (spk.second.size != 32 || (pq != null && pq.second.size != 64)) throw Erreur.Format
            val spks = mutableListOf(SignedPreKey(spk.first, spk.second.copyOf(), now, null))
            val pqs = if (pq != null) mutableListOf(PqPreKey(pq.first, pq.second.copyOf(), now, null)) else mutableListOf()
            return Device(DeviceState(1u, identity.copy(), spks, pqs, ReplayGuard()))
        }

        /** Relit un état JSON (celui de `toJson` ou de la référence Rust). */
        fun fromJson(text: String): Device {
            val s = Json.lire { DeviceState.fromJson(Json.parse(text)) }
            if (s.v.toInt() != 1) throw Erreur.Etat
            return Device(s)
        }
    }
}
