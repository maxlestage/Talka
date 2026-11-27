// AES-256-GCM gardé par fil (`AesGcm`) : le même chiffré qu'avant, lu sans
// copie à partir d'un décalage, et un chiffreur qui reste bon après un
// échec ou d'un fil à l'autre.

package talka.kit

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AesGcmTests {
    private val key = randomBytes(32)
    private val aad = "talka-repos:messages/k".toByteArray()

    @Test
    fun aSealedBoxOpensAndOnlyWithItsKeyAndLabel() {
        val plain = "Le code du portail est 4521".toByteArray()
        val box = AesGcm.seal(key, plain, aad)!!
        assertTrue(box.size == 12 + plain.size + 16)
        assertContentEquals(plain, AesGcm.open(key, box, aad))
        // Les mêmes parties qu'un nonce et un chiffré séparés.
        assertContentEquals(plain, AesGcm.openParts(key, box.copyOfRange(0, 12), box.copyOfRange(12, box.size), aad))
        assertContentEquals(box.copyOfRange(12, box.size), AesGcm.sealParts(key, box.copyOfRange(0, 12), plain, aad))
        assertNull(AesGcm.open(randomBytes(32), box, aad))
        assertNull(AesGcm.open(key, box, "talka-repos:messages/autre".toByteArray()))
        assertNull(AesGcm.open(key, box.copyOfRange(0, box.size - 1), aad))
        assertNull(AesGcm.open(ByteArray(31), box, aad))
        // Après un échec, le chiffreur du fil ouvre encore.
        assertContentEquals(plain, AesGcm.open(key, box, aad))
    }

    @Test
    fun aBoxIsReadInPlaceAfterItsPrefix() {
        val plain = ByteArray(500_000) { it.toByte() }
        val box = AesGcm.seal(key, plain, aad)!!
        val prefixed = "t1".toByteArray() + box
        assertContentEquals(plain, AesGcm.open(key, prefixed, aad, at = 2))
        assertNull(AesGcm.open(key, prefixed, aad, at = 1))
        assertNull(AesGcm.open(key, prefixed, aad, at = prefixed.size - 27))
        assertNull(AesGcm.open(key, prefixed, aad, at = -1))
        // Abîmé au milieu : refusé.
        prefixed[prefixed.size / 2] = (prefixed[prefixed.size / 2] + 1).toByte()
        assertNull(AesGcm.open(key, prefixed, aad, at = 2))
    }

    @Test
    fun manyThreadsSealAndOpenAtOnce() {
        val pool = Executors.newFixedThreadPool(8)
        val jobs = (0 until 2_000).map { i ->
            pool.submit<Boolean> {
                val k = if (i % 2 == 0) key else randomBytes(32)
                val plain = "message $i".toByteArray()
                val box = AesGcm.seal(k, plain, aad)!!
                AesGcm.open(k, box, aad)!!.contentEquals(plain) && AesGcm.open(randomBytes(32), box, aad) == null
            }
        }
        assertTrue(jobs.all { it.get() })
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
    }
}
