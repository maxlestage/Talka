// PBKDF2-HMAC-SHA256 (RFC 8018), un seul bloc de 32 octets : la clé
// protégée v1 (600 000 tours). Celui de BouncyCastle (la JCA ne prend
// qu'une phrase en `char[]`, pas des octets UTF-8).

package talka.brocolis

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter

internal object Pbkdf2 {
    /** PBKDF2-HMAC-SHA256(phrase, sel, tours), 32 octets. */
    fun sha256(password: ByteArray, salt: ByteArray, rounds: UInt): ByteArray {
        require(rounds >= 1u && rounds <= Int.MAX_VALUE.toUInt()) { "PBKDF2 : nombre de tours" }
        val g = PKCS5S2ParametersGenerator(SHA256Digest())
        g.init(password, salt, rounds.toInt())
        return (g.generateDerivedParameters(256) as KeyParameter).key
    }
}
