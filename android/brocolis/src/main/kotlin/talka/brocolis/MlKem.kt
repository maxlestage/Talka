// ML-KEM-768 (FIPS 203) : jamais réécrit ici. Il vient de BouncyCastle, en
// Java pur : disponible partout (JVM, Android 8 et suivants), donc
// `MlKem.isAvailable` est toujours vrai.
//
// - le secret est la graine FIPS 203 `d ‖ z` (64 octets), comme la
//   référence ;
// - l'encapsulation prend son aléa `m` (32 octets) dans le hasard du
//   protocole, comme la référence (`encapsulate_deterministic`) : une
//   session ouverte avec `Deterministic` retrouve les chiffrés de Rust octet
//   pour octet.
//
// Le contrôle du module (FIPS 203 §7.2 : chaque coefficient de t̂ < q) est
// fait ici, en clair, pour qu'un paquet soit accepté ou refusé de la même
// façon partout.

package talka.brocolis

import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters

/**
 * Le résultat d'une encapsulation ML-KEM : le chiffré à envoyer et le secret
 * partagé. Sert aussi à rejouer les vecteurs (`Session.initiateWith`).
 */
data class KemEncapsulation(
    /** Le chiffré ML-KEM-768 (1 088 octets). */
    val ciphertext: ByteArray,
    /** Le secret partagé SS (32 octets). */
    val shared: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is KemEncapsulation && ciphertext.contentEquals(other.ciphertext) && shared.contentEquals(other.shared)

    override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + shared.contentHashCode()
}

/** ML-KEM-768, par BouncyCastle. */
object MlKem {
    /** La longueur d'une clé d'encapsulation. */
    const val publicKeyLength = 1_184

    /** La longueur d'un chiffré. */
    const val ciphertextLength = 1_088

    /** La longueur de la graine `d ‖ z`. */
    const val seedLength = 64

    private val params = MLKEMParameters.ml_kem_768

    /** Vrai si ML-KEM-768 est disponible sur cet appareil (toujours : BouncyCastle est embarqué). */
    val isAvailable: Boolean get() = true

    /**
     * Le contrôle du module de FIPS 203 (§7.2) : 1 184 octets, et chacun des
     * 768 coefficients de 12 bits de t̂ est < q = 3 329.
     */
    fun isValidPublicKey(ek: ByteArray): Boolean {
        if (ek.size != publicKeyLength) return false
        var i = 0
        while (i < 1_152) {
            val b0 = ek[i].toInt() and 0xFF
            val b1 = ek[i + 1].toInt() and 0xFF
            val b2 = ek[i + 2].toInt() and 0xFF
            val c0 = b0 or ((b1 and 0x0F) shl 8)
            val c1 = (b1 shr 4) or (b2 shl 4)
            if (c0 >= 3_329 || c1 >= 3_329) return false
            i += 3
        }
        return true
    }

    private fun privateKey(seed: ByteArray): MLKEMPrivateKeyParameters {
        if (seed.size != seedLength) throw Erreur.Format
        return try {
            MLKEMPrivateKeyParameters(params, seed.copyOf())
        } catch (e: RuntimeException) {
            throw Erreur.Format
        }
    }

    /** La clé d'encapsulation (1 184 octets) d'une graine `d ‖ z`. */
    fun publicKey(seed: ByteArray): ByteArray = privateKey(seed).publicKey

    /**
     * ML-KEM-768.Encaps : `(CT, SS)`, l'aléa `m` tiré du CSPRNG du système.
     * Refuse (`Erreur.CleInvalide`) une clé d'encapsulation invalide.
     */
    fun encapsulate(ek: ByteArray): KemEncapsulation {
        val m = Hasard.system().bytes(32)
        try {
            return encapsulate(ek, m)
        } finally {
            effacer(m)
        }
    }

    /**
     * ML-KEM-768.Encaps avec l'aléa `m` donné (32 octets : FIPS 203
     * `ML-KEM.Encaps_internal`, `encapsulate_deterministic` de la référence).
     * Refuse (`Erreur.CleInvalide`) une clé d'encapsulation invalide.
     */
    fun encapsulate(ek: ByteArray, m: ByteArray): KemEncapsulation {
        require(m.size == 32) { "ML-KEM : aléa de 32 octets" }
        if (!isValidPublicKey(ek)) throw Erreur.CleInvalide
        val r = try {
            MLKEMGenerator(null).internalGenerateEncapsulated(MLKEMPublicKeyParameters(params, ek.copyOf()), m.copyOf())
        } catch (e: RuntimeException) {
            throw Erreur.CleInvalide
        }
        return KemEncapsulation(r.encapsulation, r.secret)
    }

    /**
     * ML-KEM-768.Decaps (rejet implicite : un chiffré altéré donne un secret
     * faux, sans erreur, et le message est ensuite refusé). Un chiffré de
     * mauvaise longueur : `Erreur.Format`.
     */
    fun decapsulate(seed: ByteArray, ciphertext: ByteArray): ByteArray {
        if (seed.size != seedLength || ciphertext.size != ciphertextLength) throw Erreur.Format
        val key = privateKey(seed)
        return try {
            MLKEMExtractor(key).extractSecret(ciphertext.copyOf())
        } catch (e: RuntimeException) {
            throw Erreur.Format
        }
    }
}
