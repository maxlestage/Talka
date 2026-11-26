// Argon2 (RFC 9106, version 0x13) et BLAKE2b (RFC 7693), par BouncyCastle.
// Vérifiés par les vecteurs de RFC 7693 (« abc »), de RFC 9106 §5 (Argon2d,
// Argon2i, Argon2id, avec secret et données associées), par ceux de
// proto/vectors.json (19 Mio et 64 Mio) et par la crate `argon2` (plusieurs
// voies, sortie longue : Fixtures/reference-rust.json).
//
// Les bornes sont contrôlées ici, avant BouncyCastle : il arrondit en
// silence une mémoire trop petite, la référence la refuse.

package talka.brocolis

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** BLAKE2b sans clé (RFC 7693), sortie de 1 à 64 octets. */
internal object Blake2b {
    /** BLAKE2b(données) en `outLength` octets (1 à 64). */
    fun hash(data: ByteArray, outLength: Int): ByteArray {
        require(outLength in 1..64)
        val d = Blake2bDigest(outLength * 8)
        d.update(data, 0, data.size)
        val out = ByteArray(outLength)
        d.doFinal(out, 0)
        return out
    }
}

/** Argon2 (RFC 9106), version 0x13. */
object Argon2 {
    /** La variante. */
    enum class Variant(val rawValue: UInt) {
        /** Argon2d (adressage dépendant des données). */
        D(0u),

        /** Argon2i (adressage indépendant des données). */
        I(1u),

        /** Argon2id (le protocole : i pour la première moitié de la première passe, d ensuite). */
        ID(2u),
    }

    /**
     * Argon2(mot de passe, sel, m Kio, t passes, p voies, longueur), avec un
     * secret et des données associées optionnels (le protocole n'en a pas ;
     * RFC 9106 §5 si). Paramètres : p ≥ 1, m ≥ 8p, t ≥ 1, longueur ≥ 4
     * (sinon `Erreur.Parametres`).
     */
    fun hash(
        variant: Variant,
        password: ByteArray,
        salt: ByteArray,
        secret: ByteArray = ByteArray(0),
        associatedData: ByteArray = ByteArray(0),
        memoryKiB: UInt,
        passes: UInt,
        lanes: UInt,
        length: Int,
    ): ByteArray {
        // Au-delà de 2³¹ − 1 (Kio, passes), BouncyCastle ne compte plus : de
        // toute façon hors de portée d'un appareil.
        val ok = lanes >= 1u && lanes <= 0xFF_FFFFu && passes >= 1u && memoryKiB >= 8u * lanes &&
            length >= 4 && memoryKiB <= Int.MAX_VALUE.toUInt() && passes <= Int.MAX_VALUE.toUInt()
        if (!ok) throw Erreur.Parametres
        val type = when (variant) {
            Variant.D -> Argon2Parameters.ARGON2_d
            Variant.I -> Argon2Parameters.ARGON2_i
            Variant.ID -> Argon2Parameters.ARGON2_id
        }
        val builder = Argon2Parameters.Builder(type)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKiB.toInt())
            .withIterations(passes.toInt())
            .withParallelism(lanes.toInt())
            .withSalt(salt)
        if (secret.isNotEmpty()) builder.withSecret(secret)
        if (associatedData.isNotEmpty()) builder.withAdditional(associatedData)
        val g = Argon2BytesGenerator()
        g.init(builder.build())
        val out = ByteArray(length)
        g.generateBytes(password, out)
        return out
    }
}
