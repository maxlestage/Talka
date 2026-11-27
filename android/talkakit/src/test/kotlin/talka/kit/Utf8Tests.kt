// Le texte UTF-8 d'octets (`utf8`) : le raccourci de l'ASCII rend la même
// chaîne que le décodeur strict, et tout ce qui n'est pas de l'UTF-8 reste
// refusé.

package talka.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Utf8Tests {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun asciiAndAccentsReadTheSame() {
        assertEquals("", utf8(ByteArray(0)))
        val ascii = (0..127).map { it.toChar() }.joinToString("")
        assertEquals(ascii, utf8(ascii.toByteArray()))
        val photo = "{\"data\":\"data:image/jpeg;base64," + "A/+9".repeat(125_000) + "\"}"
        assertEquals(photo, utf8(photo.toByteArray()))
        for (text in listOf("Léa Secrète", "Rendez-vous à la gare 🚉", "日本語", "a\u0000b", "� est un vrai caractère")) {
            assertEquals(text, utf8(text.toByteArray()))
        }
    }

    @Test
    fun whatIsNotUtf8IsRefused() {
        assertNull(utf8(bytes(0x80)), "un octet de suite seul")
        assertNull(utf8(bytes(0x41, 0xC3)), "coupé au milieu d'un caractère")
        assertNull(utf8(bytes(0xC0, 0x80)), "forme trop longue")
        assertNull(utf8(bytes(0xED, 0xA0, 0x80)), "demi-paire UTF-16")
        assertNull(utf8(bytes(0xF4, 0x90, 0x80, 0x80)), "au-delà de U+10FFFF")
        assertNull(utf8(bytes(0xFF)))
        assertNull(utf8("tout en ASCII puis ".toByteArray() + bytes(0xC3, 0x28)))
    }
}
