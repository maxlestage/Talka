// Les groupes : Sender Keys (§4.5, §7.6, §7.7), le modèle de Signal —
// `brocolis_proto::groupe`.
//
// Chaque membre tire une **chaîne d'envoi** (clé de chaîne 32 octets + clé
// de signature Ed25519) et la distribue à chaque membre par sa session v3 à
// deux (jeton de type 0x03, `Session.encryptDistribution`). Ses messages de
// groupe (type 0x04) :
//
//     0x04 ‖ id groupe (16) ‖ id expéditeur (8) ‖ itération (u32 BE) ‖ chiffré ‖ signature (64)
//
// - la chaîne avance par `KDF_CK` ; la clé du message d'itération `i` est
//   `mk_i`, chiffrée comme au §4.3 (`ENCRYPT(mk)`, remplissage §3.3) ;
// - données associées AES-GCM : les 29 premiers octets ;
// - signature Ed25519 (stricte) de tout ce qui précède (type compris),
//   vérifiée **avant** toute autre chose ;
// - id expéditeur = `SHA-256(clé publique de signature)[0..8]`.
//
// Distribution (92 octets, puis remplissage §3.3) :
//
//     id groupe (16) ‖ id expéditeur (8) ‖ itération (u32 BE) ‖ clé de chaîne (32) ‖ clé publique Ed25519 (32)

package talka.brocolis

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import talka.brocolis.Json.bytes
import talka.brocolis.Json.number

object Groupe {
    /** La longueur d'une distribution (avant remplissage). */
    const val distributionLength = 92

    /** Un identifiant de groupe aléatoire (16 octets). */
    fun newGroupId(rng: CryptoRng): ByteArray = rng.bytes(16)

    /** L'identifiant d'expéditeur d'une chaîne : `SHA-256(clé publique)[0..8]`. */
    fun senderIdOf(signingPublic: ByteArray): ByteArray = Kdf.sha256(signingPublic).copyOfRange(0, 8)
}

/** Une chaîne de groupe telle qu'on la distribue. */
data class SenderKeyDistribution(
    /** L'identifiant du groupe (16 octets). */
    var groupId: ByteArray,
    /** L'identifiant de l'expéditeur (8 octets). */
    var senderId: ByteArray,
    /** L'itération de la clé de chaîne. */
    var iteration: UInt,
    /** La clé de chaîne à cette itération (secrète). */
    var chainKey: ByteArray,
    /** La clé publique Ed25519 de la chaîne. */
    var signingPublic: ByteArray,
) {
    /** Les 92 octets. */
    fun toBytes(): ByteArray = Octets.concat(groupId, senderId, Octets.u32be(iteration), chainKey, signingPublic)

    internal fun check() {
        if (groupId.size != 16 || senderId.size != 8 || chainKey.size != 32 || signingPublic.size != 32) {
            throw Erreur.Format
        }
        if (!Groupe.senderIdOf(signingPublic).contentEquals(senderId)) throw Erreur.Format
        if (!Kdf.ed25519IsValidPublic(signingPublic)) throw Erreur.CleInvalide
    }

    override fun equals(other: Any?): Boolean =
        other is SenderKeyDistribution && groupId.contentEquals(other.groupId) && senderId.contentEquals(other.senderId) &&
            iteration == other.iteration && chainKey.contentEquals(other.chainKey) &&
            signingPublic.contentEquals(other.signingPublic)

    override fun hashCode(): Int =
        listOf(groupId.contentHashCode(), senderId.contentHashCode(), iteration.hashCode(), chainKey.contentHashCode(),
            signingPublic.contentHashCode()).hashCode()

    companion object {
        /** Lit 92 octets ; vérifie l'identifiant d'expéditeur et la clé publique. */
        fun fromBytes(b: ByteArray): SenderKeyDistribution {
            if (b.size != Groupe.distributionLength) throw Erreur.Format
            val d = SenderKeyDistribution(
                b.copyOfRange(0, 16), b.copyOfRange(16, 24), Octets.readU32(b, 24), b.copyOfRange(28, 60),
                b.copyOfRange(60, 92),
            )
            d.check()
            return d
        }
    }
}

internal class SenderKeyStateData(
    var v: UByte,
    var groupId: ByteArray,
    var chainKey: ByteArray,
    var iteration: UInt,
    var signingSeed: ByteArray,
) {
    fun toJson(): JsonObject = buildJsonObject {
        number("v", v)
        bytes("group_id", groupId)
        bytes("chain_key", chainKey)
        number("iteration", iteration)
        bytes("signing_seed", signingSeed)
    }

    companion object {
        fun fromJson(o: JsonObject) = SenderKeyStateData(
            Json.u8(o, "v"), Json.bytes(o, "group_id", 16), Json.bytes(o, "chain_key", 32), Json.u32(o, "iteration"),
            Json.bytes(o, "signing_seed", 32),
        )
    }
}

/** Sa propre chaîne d'envoi dans un groupe. */
class SenderKeyState internal constructor(internal var data: SenderKeyStateData) {
    /** La clé publique Ed25519 de la chaîne. */
    val signingPublic: ByteArray get() = Kdf.ed25519Public(data.signingSeed)

    /** L'identifiant d'expéditeur. */
    val senderId: ByteArray get() = Groupe.senderIdOf(signingPublic)

    /** L'identifiant du groupe. */
    val groupId: ByteArray get() = data.groupId.copyOf()

    /** L'itération courante (celle du prochain message). */
    val iteration: UInt get() = data.iteration

    /** La distribution de la chaîne à son itération courante. */
    fun distribution(): SenderKeyDistribution {
        val pub = signingPublic
        return SenderKeyDistribution(data.groupId.copyOf(), Groupe.senderIdOf(pub), data.iteration, data.chainKey.copyOf(), pub)
    }

    /** Chiffre et signe un message de groupe (jeton décodé). */
    fun encryptToken(text: String): Token {
        val plain = Octets.bytes(text)
        try {
            if (plain.size > Session.maxPlaintext) throw Erreur.TropGrand
            if (data.iteration == UInt.MAX_VALUE) throw Erreur.Epuise
            val (next, mk) = Kdf.kdfCk(data.chainKey)
            val (key, nonce) = Kdf.messageKey(mk)
            effacer(mk)
            val msg = GroupMessage(data.groupId.copyOf(), senderId, data.iteration, ByteArray(0), ByteArray(64))
            val padded = Remplissage.pad(plain)
            try {
                msg.ciphertext = Kdf.aesGcmSeal(key, nonce, msg.prefix(), padded)
            } catch (e: Erreur) {
                effacer(next)
                throw e
            } finally {
                effacer(key)
                effacer(padded)
            }
            msg.signature = Kdf.ed25519Sign(data.signingSeed, msg.signedBytes())
            effacer(data.chainKey)
            data.chainKey = next
            data.iteration++
            return Token.Group(msg)
        } finally {
            effacer(plain)
        }
    }

    /** Chiffre et signe un message de groupe : `[[BROCOLIS:v3:…]]`. */
    fun encrypt(text: String): String = encryptToken(text).encode()

    /** L'état en JSON (même forme que `SenderKeyState::to_json`). */
    fun toJson(): String = data.toJson().toString()

    /** Efface les secrets de la chaîne (au mieux) ; elle ne sert plus ensuite. */
    fun wipe() {
        effacer(data.chainKey)
        effacer(data.signingSeed)
    }

    companion object {
        /** Une chaîne neuve. Ordre de tirage : clé de chaîne (32), graine Ed25519 (32). */
        fun generate(groupId: ByteArray, rng: CryptoRng): SenderKeyState {
            val chainKey = rng.bytes(32)
            val seed = rng.bytes(32)
            try {
                return generateWith(groupId, chainKey, seed)
            } finally {
                effacer(chainKey)
                effacer(seed)
            }
        }

        /** Une chaîne à partir des secrets donnés (les vecteurs). */
        fun generateWith(groupId: ByteArray, chainKey: ByteArray, signingSeed: ByteArray): SenderKeyState {
            if (groupId.size != 16 || chainKey.size != 32 || signingSeed.size != 32) throw Erreur.Format
            return SenderKeyState(SenderKeyStateData(1u, groupId.copyOf(), chainKey.copyOf(), 0u, signingSeed.copyOf()))
        }

        /** Relit un état JSON. */
        fun fromJson(text: String): SenderKeyState {
            val d = Json.lire { SenderKeyStateData.fromJson(Json.parse(text)) }
            if (d.v.toInt() != 1) throw Erreur.Etat
            return SenderKeyState(d)
        }
    }
}

internal class GroupSkippedKey(val iteration: UInt, val mk: ByteArray, val storedAt: ULong) {
    fun copy() = GroupSkippedKey(iteration, mk.copyOf(), storedAt)

    fun toJson(): JsonObject = buildJsonObject {
        number("iteration", iteration)
        bytes("mk", mk)
        number("stored_at", storedAt)
    }

    companion object {
        fun fromJson(o: JsonObject) = GroupSkippedKey(Json.u32(o, "iteration"), Json.bytes(o, "mk", 32), Json.u64(o, "stored_at"))
    }
}

internal class ReceivedSenderKeyData(
    var v: UByte,
    var groupId: ByteArray,
    var senderId: ByteArray,
    var chainKey: ByteArray,
    var iteration: UInt,
    var signingPublic: ByteArray,
    val skipped: MutableList<GroupSkippedKey>,
) {
    fun copy() = ReceivedSenderKeyData(
        v, groupId.copyOf(), senderId.copyOf(), chainKey.copyOf(), iteration, signingPublic.copyOf(),
        skipped.mapTo(mutableListOf()) { it.copy() },
    )

    fun wipe() {
        effacer(chainKey)
        skipped.forEach { effacer(it.mk) }
    }

    fun toJson(): JsonObject = buildJsonObject {
        number("v", v)
        bytes("group_id", groupId)
        bytes("sender_id", senderId)
        bytes("chain_key", chainKey)
        number("iteration", iteration)
        bytes("signing_public", signingPublic)
        put("skipped", JsonArray(skipped.map { it.toJson() }))
    }

    companion object {
        fun fromJson(o: JsonObject) = ReceivedSenderKeyData(
            Json.u8(o, "v"), Json.bytes(o, "group_id", 16), Json.bytes(o, "sender_id", 8), Json.bytes(o, "chain_key", 32),
            Json.u32(o, "iteration"), Json.bytes(o, "signing_public", 32),
            Json.array(Json.field(o, "skipped")).mapTo(mutableListOf()) { GroupSkippedKey.fromJson(Json.obj(it)) },
        )
    }
}

/** La chaîne d'un autre membre, reçue par une distribution. */
class ReceivedSenderKey internal constructor(internal var data: ReceivedSenderKeyData) {
    /** L'identifiant du groupe. */
    val groupId: ByteArray get() = data.groupId.copyOf()

    /** L'identifiant d'expéditeur. */
    val senderId: ByteArray get() = data.senderId.copyOf()

    /** Vrai si ce message de groupe est de cette chaîne. */
    fun matches(msg: GroupMessage): Boolean = msg.groupId.contentEquals(data.groupId) && msg.senderId.contentEquals(data.senderId)

    /** Le nombre de clés sautées gardées. */
    val skippedCount: Int get() = data.skipped.size

    /** Vérifie la signature puis déchiffre. Transactionnel ; un message déjà lu donne `Erreur.Rejoue`. */
    fun decrypt(msg: GroupMessage, now: ULong): String {
        if (!matches(msg)) throw Erreur.ExpediteurInconnu
        Kdf.ed25519Verify(data.signingPublic, msg.signedBytes(), msg.signature)
        val next = data.copy()
        try {
            val expired = next.skipped.filter { satAdd(it.storedAt, Protocole.maxSkipAge) <= now }
            expired.forEach { effacer(it.mk) }
            next.skipped.removeAll(expired)
            val mk: ByteArray
            if (msg.iteration < next.iteration) {
                val pos = next.skipped.indexOfFirst { it.iteration == msg.iteration }
                if (pos < 0) throw Erreur.Rejoue
                mk = next.skipped[pos].mk
                next.skipped.removeAt(pos)
            } else {
                if (msg.iteration.toULong() - next.iteration.toULong() > Protocole.maxSkip.toULong()) {
                    throw Erreur.TropDeClesSautees
                }
                var ck = next.chainKey
                while (next.iteration < msg.iteration) {
                    val (n, k) = Kdf.kdfCk(ck)
                    next.skipped.add(GroupSkippedKey(next.iteration, k, now))
                    effacer(ck)
                    ck = n
                    next.iteration++
                }
                val (n, k) = Kdf.kdfCk(ck)
                effacer(ck)
                next.chainKey = n
                if (next.iteration == UInt.MAX_VALUE) {
                    effacer(k)
                    throw Erreur.Epuise
                }
                next.iteration++
                val max = Protocole.maxSkip.toInt()
                if (next.skipped.size > max) {
                    val extra = next.skipped.subList(0, next.skipped.size - max)
                    extra.forEach { effacer(it.mk) }
                    extra.clear()
                }
                mk = k
            }
            val (key, nonce) = Kdf.messageKey(mk)
            effacer(mk)
            val text = try {
                val padded = Kdf.aesGcmOpen(key, nonce, msg.prefix(), msg.ciphertext)
                try {
                    Octets.utf8(Remplissage.unpad(padded)) ?: throw Erreur.Utf8
                } finally {
                    effacer(padded)
                }
            } finally {
                effacer(key)
            }
            val old = data
            data = next
            old.wipe()
            return text
        } catch (e: Erreur) {
            next.wipe()
            throw e
        }
    }

    /** L'état en JSON (même forme que `ReceivedSenderKey::to_json`). */
    fun toJson(): String = data.toJson().toString()

    /** Efface les secrets de la chaîne (au mieux) ; elle ne sert plus ensuite. */
    fun wipe() = data.wipe()

    companion object {
        /** La chaîne d'une distribution (vérifiée). */
        fun fromDistribution(d: SenderKeyDistribution): ReceivedSenderKey {
            d.check()
            return ReceivedSenderKey(
                ReceivedSenderKeyData(
                    1u, d.groupId.copyOf(), d.senderId.copyOf(), d.chainKey.copyOf(), d.iteration, d.signingPublic.copyOf(),
                    mutableListOf(),
                ),
            )
        }

        /** Relit un état JSON. */
        fun fromJson(text: String): ReceivedSenderKey {
            val d = Json.lire { ReceivedSenderKeyData.fromJson(Json.parse(text)) }
            if (d.v.toInt() != 1 || d.skipped.size > Protocole.maxSkip.toInt() ||
                !Groupe.senderIdOf(d.signingPublic).contentEquals(d.senderId)
            ) {
                throw Erreur.Etat
            }
            return ReceivedSenderKey(d)
        }
    }
}
