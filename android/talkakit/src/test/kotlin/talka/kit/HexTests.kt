// L'hexadécimal (`hex`, `randomId`) : deux chiffres minuscules par octet,
// les mêmes que `String.format("%02x")`.

package talka.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexTests {
    @Test
    fun everyByteGivesItsTwoDigits() {
        val all = ByteArray(256) { it.toByte() }
        assertEquals(all.joinToString("") { "%02x".format(it.toInt() and 0xff) }, hex(all))
        assertEquals("", hex(ByteArray(0)))
        assertEquals("00ff7f80", hex(byteArrayOf(0, -1, 127, -128)))
        repeat(100) {
            val b = randomBytes(32)
            assertEquals(b.joinToString("") { "%02x".format(it.toInt() and 0xff) }, hex(b))
        }
    }

    @Test
    fun randomIdsAreHexOfTheirLength() {
        val ids = (0 until 1_000).map { randomId(12) }
        assertTrue(ids.all { it.length == 24 && it.all { c -> c in "0123456789abcdef" } })
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(16, randomId(8).length)
    }
}
