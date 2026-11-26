// Le jeton v3 (§4.4) : `[[BROCOLIS:v3:<base64>]]`, encodage et décodage
// **stricts** (toute longueur, tout octet de type ou de mode inattendu, tout
// champ réservé non nul est refusé ; aucun arrêt) — `brocolis_proto::jeton_v3`.
//
//     octet 0 : type
//       0x01 premier message   : id session (8) ‖ IK_A (32) ‖ EK_A (32) ‖ mode (1)
//                                ‖ [CT ML-KEM (1 088) si mode = 0x01]
//                                ‖ id SPK (u32 BE) ‖ id PQPK (u32 BE)
//                                ‖ en-tête (40) ‖ chiffré
//       0x02 message           : id session (8) ‖ en-tête (40) ‖ chiffré
//       0x03 distribution      : id session (8) ‖ en-tête (40) ‖ chiffré
//       0x04 message de groupe : id groupe (16) ‖ id expéditeur (8)
//                                ‖ itération (u32 BE) ‖ chiffré ‖ signature (64)
//     en-tête = DH_pub (32) ‖ PN (u32 BE) ‖ N (u32 BE)
//     chiffré = AES-256-GCM(remplissage §3.3) ‖ tag : 160·k + 16 octets, k ≥ 1
//
// `mode` : `0x00` X25519 seul, `0x01` X25519 + ML-KEM-768, `0x02` clé
// partagée (alors IK_A, id SPK et id PQPK sont nuls). En mode `0x00`, id PQPK
// est nul.
//
// Les classes de données portent des `ByteArray` : `==` compare leur
// contenu, mais `copy()` ne copie pas les tableaux (les cloner avant de les
// modifier).

package talka.brocolis

/** Un jeton v3 trouvé dans un texte collé : son texte exact et son décodage (réussi ou non). */
data class JetonTrouve(
    /** Le jeton tel qu'il est dans le texte (`[[BROCOLIS:v3:…]]`). */
    val text: String,
    /** Le jeton décodé, ou l'`Erreur` du décodage. */
    val token: Result<Token>,
)

object JetonV3 {
    /** Le début d'un jeton v3. */
    const val head = "[[BROCOLIS:v3:"
    internal val headBytes = Octets.bytes(head)
    internal val end = Octets.bytes("]]")

    /** Type 0x01 : premier message, qui établit la session. */
    const val typeInitial: UByte = 0x01u

    /** Type 0x02 : message d'une session établie. */
    const val typeMessage: UByte = 0x02u

    /** Type 0x03 : distribution d'une chaîne de groupe. */
    const val typeDistribution: UByte = 0x03u

    /** Type 0x04 : message de groupe signé. */
    const val typeGroup: UByte = 0x04u

    /** La longueur d'un chiffré ML-KEM-768. */
    const val kemCtLength = 1_088

    /** La longueur d'un en-tête de ratchet. */
    const val headerLength = 40

    /** La taille maximale d'un jeton v3 décodé : 256 Kio. */
    const val maxTokenBytes = 256 * 1024

    /**
     * Les jetons v3 d'un texte collé, dans l'ordre (chaque
     * `[[BROCOLIS:v3:…]]` complet), décodés ou non.
     */
    fun findAll(text: String): List<JetonTrouve> {
        val bytes = Octets.bytes(text)
        val out = mutableListOf<JetonTrouve>()
        var rest = 0
        while (true) {
            val start = Octets.find(bytes, headBytes, rest) ?: break
            val e = Octets.find(bytes, end, start) ?: break
            val token = Octets.string(bytes.copyOfRange(start, e + end.size))
            val decoded = try {
                Result.success(Token.decode(token))
            } catch (x: Erreur) {
                Result.failure(x)
            }
            out.add(JetonTrouve(token, decoded))
            rest = e + end.size
        }
        return out
    }
}

/** Le mode d'établissement d'une session (`"Classic"`, `"Hybrid"`, `"Psk"` dans le JSON, comme serde). */
enum class Mode {
    /** X25519 seul (`pq = 0`) : « session non post-quantique ». */
    Classic,

    /** X25519 + ML-KEM-768 (`pq = 1`). */
    Hybrid,

    /** Clé Brocolis partagée (extension, octet `0x02`). */
    Psk;

    /** L'octet du mode. */
    val byte: UByte
        get() = when (this) {
            Classic -> 0x00u
            Hybrid -> 0x01u
            Psk -> 0x02u
        }

    companion object {
        internal fun from(b: UByte): Mode = when (b.toInt()) {
            0x00 -> Classic
            0x01 -> Hybrid
            0x02 -> Psk
            else -> throw Erreur.Format
        }

        /** Le mode d'un nom JSON (`"Hybrid"`…), ou `Erreur.Etat`. */
        internal fun fromJson(name: String): Mode = entries.firstOrNull { it.name == name } ?: throw Erreur.Etat
    }
}

/** L'en-tête d'un message du Double Ratchet. */
data class Header(
    /** La clé publique de cliquet de l'expéditeur (32 octets). */
    var dh: ByteArray,
    /** La longueur de sa chaîne d'envoi précédente. */
    var pn: UInt,
    /** Le numéro du message dans sa chaîne d'envoi. */
    var n: UInt,
) {
    /** Les 40 octets de l'en-tête. */
    fun toBytes(): ByteArray = Octets.concat(dh, Octets.u32be(pn), Octets.u32be(n))

    override fun equals(other: Any?): Boolean = other is Header && dh.contentEquals(other.dh) && pn == other.pn && n == other.n

    override fun hashCode(): Int = (dh.contentHashCode() * 31 + pn.hashCode()) * 31 + n.hashCode()

    internal companion object {
        fun parse(r: Reader): Header = Header(r.take(32), r.u32(), r.u32())
    }
}

/** Un message chiffré par le ratchet d'une session (types 0x02 et 0x03, et la fin d'un 0x01). */
data class Envelope(
    /** L'identifiant de session : `SHA-256(AD)[0..8]`. */
    var sessionId: ByteArray,
    /** L'en-tête du ratchet. */
    var header: Header,
    /** Le chiffré AES-256-GCM ‖ tag. */
    var ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is Envelope && sessionId.contentEquals(other.sessionId) && header == other.header &&
            ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = (sessionId.contentHashCode() * 31 + header.hashCode()) * 31 + ciphertext.contentHashCode()
}

/** Le premier message d'une session (type 0x01). */
data class Initial(
    /** La clé d'identité X25519 de l'initiateur (nulle en mode clé partagée). */
    var identity: ByteArray,
    /** La clé éphémère EK de l'initiateur. */
    var ephemeral: ByteArray,
    /** Le mode d'établissement. */
    var mode: Mode,
    /** Le chiffré ML-KEM-768 (mode hybride seulement). */
    var kemCiphertext: ByteArray?,
    /** L'identifiant de la clé pré-signée du répondeur utilisée. */
    var spkId: UInt,
    /** L'identifiant de sa clé ML-KEM utilisée (0 sans ML-KEM). */
    var pqId: UInt,
    /** Le message lui-même. */
    var envelope: Envelope,
) {
    override fun equals(other: Any?): Boolean =
        other is Initial && identity.contentEquals(other.identity) && ephemeral.contentEquals(other.ephemeral) &&
            mode == other.mode && kemCiphertext.contentEquals(other.kemCiphertext) && spkId == other.spkId &&
            pqId == other.pqId && envelope == other.envelope

    override fun hashCode(): Int =
        listOf(identity.contentHashCode(), ephemeral.contentHashCode(), mode.hashCode(), kemCiphertext.contentHashCode(),
            spkId.hashCode(), pqId.hashCode(), envelope.hashCode()).hashCode()
}

/** Un message de groupe (type 0x04). */
data class GroupMessage(
    /** L'identifiant du groupe (16 octets). */
    var groupId: ByteArray,
    /** L'identifiant de l'expéditeur, de sa chaîne (8 octets). */
    var senderId: ByteArray,
    /** L'itération de la chaîne. */
    var iteration: UInt,
    /** Le chiffré AES-256-GCM ‖ tag. */
    var ciphertext: ByteArray,
    /** La signature Ed25519 de tout ce qui précède (type compris). */
    var signature: ByteArray,
) {
    /**
     * Les 29 premiers octets : type ‖ id groupe ‖ id expéditeur ‖ itération
     * (les données associées du chiffrement).
     */
    fun prefix(): ByteArray = Octets.concat(byteArrayOf(JetonV3.typeGroup.toByte()), groupId, senderId, Octets.u32be(iteration))

    /** Ce que couvre la signature : préfixe ‖ chiffré. */
    fun signedBytes(): ByteArray = Octets.concat(prefix(), ciphertext)

    override fun equals(other: Any?): Boolean =
        other is GroupMessage && groupId.contentEquals(other.groupId) && senderId.contentEquals(other.senderId) &&
            iteration == other.iteration && ciphertext.contentEquals(other.ciphertext) &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int =
        listOf(groupId.contentHashCode(), senderId.contentHashCode(), iteration.hashCode(), ciphertext.contentHashCode(),
            signature.contentHashCode()).hashCode()
}

/** Un jeton v3 décodé. */
sealed class Token {
    /** Type 0x01. */
    data class Initial(val initial: talka.brocolis.Initial) : Token()

    /** Type 0x02. */
    data class Message(val envelope: Envelope) : Token()

    /** Type 0x03. */
    data class Distribution(val envelope: Envelope) : Token()

    /** Type 0x04. */
    data class Group(val message: GroupMessage) : Token()

    /** Les octets du jeton. */
    fun toBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        when (this) {
            is Initial -> {
                val i = initial
                out.write(JetonV3.typeInitial.toInt())
                out.write(i.envelope.sessionId)
                out.write(i.identity)
                out.write(i.ephemeral)
                out.write(i.mode.byte.toInt())
                i.kemCiphertext?.let { out.write(it) }
                out.write(Octets.u32be(i.spkId))
                out.write(Octets.u32be(i.pqId))
                out.write(i.envelope.header.toBytes())
                out.write(i.envelope.ciphertext)
            }
            is Message, is Distribution -> {
                val e = if (this is Message) envelope else (this as Distribution).envelope
                out.write(type.toInt())
                out.write(e.sessionId)
                out.write(e.header.toBytes())
                out.write(e.ciphertext)
            }
            is Group -> {
                out.write(message.signedBytes())
                out.write(message.signature)
            }
        }
        return out.toByteArray()
    }

    /** Le jeton `[[BROCOLIS:v3:<base64>]]`. */
    fun encode(): String = JetonV3.head + Base64.encode(toBytes()) + "]]"

    /** L'identifiant de session (types 0x01 à 0x03). */
    val sessionId: ByteArray?
        get() = when (this) {
            is Initial -> initial.envelope.sessionId
            is Message -> envelope.sessionId
            is Distribution -> envelope.sessionId
            is Group -> null
        }

    /** L'octet de type. */
    val type: UByte
        get() = when (this) {
            is Initial -> JetonV3.typeInitial
            is Message -> JetonV3.typeMessage
            is Distribution -> JetonV3.typeDistribution
            is Group -> JetonV3.typeGroup
        }

    companion object {
        /** Vérifie la longueur d'un chiffré : 160·k + 16 octets, k ≥ 1. */
        internal fun checkCiphertext(count: Int) {
            if (count < Remplissage.bloc + 16 || (count - 16) % Remplissage.bloc != 0) throw Erreur.Format
        }

        /** Décode les octets d'un jeton v3. */
        fun fromBytes(data: ByteArray): Token {
            if (data.size > JetonV3.maxTokenBytes) throw Erreur.TropGrand
            val r = Reader(data)
            val kind = r.u8()
            when (kind) {
                JetonV3.typeInitial -> {
                    val sessionId = r.take(8)
                    val identity = r.take(32)
                    val ephemeral = r.take(32)
                    val mode = Mode.from(r.u8())
                    val kem = if (mode == Mode.Hybrid) r.take(JetonV3.kemCtLength) else null
                    val spkId = r.u32()
                    val pqId = r.u32()
                    when (mode) {
                        Mode.Classic -> if (pqId != 0u) throw Erreur.Format
                        Mode.Psk -> if (!Octets.isZero(identity) || spkId != 0u || pqId != 0u) throw Erreur.Format
                        Mode.Hybrid -> {}
                    }
                    val header = Header.parse(r)
                    val ciphertext = r.rest()
                    checkCiphertext(ciphertext.size)
                    return Initial(
                        talka.brocolis.Initial(identity, ephemeral, mode, kem, spkId, pqId, Envelope(sessionId, header, ciphertext)),
                    )
                }
                JetonV3.typeMessage, JetonV3.typeDistribution -> {
                    val sessionId = r.take(8)
                    val header = Header.parse(r)
                    val ciphertext = r.rest()
                    checkCiphertext(ciphertext.size)
                    val env = Envelope(sessionId, header, ciphertext)
                    return if (kind == JetonV3.typeMessage) Message(env) else Distribution(env)
                }
                JetonV3.typeGroup -> {
                    val groupId = r.take(16)
                    val senderId = r.take(8)
                    val iteration = r.u32()
                    val rest = r.rest()
                    if (rest.size < 64) throw Erreur.Format
                    val ct = rest.copyOfRange(0, rest.size - 64)
                    checkCiphertext(ct.size)
                    return Group(GroupMessage(groupId, senderId, iteration, ct, rest.copyOfRange(rest.size - 64, rest.size)))
                }
                else -> throw Erreur.Format
            }
        }

        /**
         * Décode un jeton `[[BROCOLIS:v3:<base64>]]`, exactement (sans espace
         * autour ; voir `JetonV3.findAll` pour un texte collé).
         */
        fun decode(text: String): Token {
            val t = Octets.bytes(text)
            if (t.size < JetonV3.headBytes.size + JetonV3.end.size ||
                !Octets.hasPrefix(t, JetonV3.headBytes) || !Octets.hasSuffix(t, JetonV3.end)
            ) {
                throw Erreur.JetonAbsent
            }
            val bLength = t.size - JetonV3.headBytes.size - JetonV3.end.size
            if (bLength > 4 * ((JetonV3.maxTokenBytes + 2) / 3)) throw Erreur.TropGrand
            val data = Base64.decode(t.copyOfRange(JetonV3.headBytes.size, t.size - JetonV3.end.size))
                ?: throw Erreur.Base64
            return fromBytes(data)
        }
    }
}

/** Un lecteur d'octets borné : toute lecture au-delà de la fin est `Erreur.Format`. */
internal class Reader(private val data: ByteArray) {
    var position = 0
        private set

    fun take(n: Int): ByteArray {
        if (data.size - position < n) throw Erreur.Format
        val out = data.copyOfRange(position, position + n)
        position += n
        return out
    }

    fun u8(): UByte = take(1)[0].toUByte()

    fun u32(): UInt = Octets.readU32(take(4), 0)

    fun rest(): ByteArray {
        val out = data.copyOfRange(position, data.size)
        position = data.size
        return out
    }
}
