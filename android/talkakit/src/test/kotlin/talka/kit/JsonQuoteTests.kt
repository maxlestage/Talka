// Le texte d'une chaîne en JSON (`JSON.encode`) : recopié par blocs, il
// s'écrit comme avant, caractère pour caractère, et se relit tel quel.

package talka.kit

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonQuoteTests {
    /** L'écriture d'avant, caractère par caractère : la référence. */
    private fun reference(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    @Test
    fun everyStringIsWrittenAsBefore() {
        val cases = mutableListOf(
            "", "a", "\"", "\\", "\n", "\"\"", "\\\\", "a\"b", "\"a", "a\"", "é « ici » 😀", "ligne\r\nautre\tfin",
            "\u0000\u0001\u001f \u007f", "\b\u000C", "data:image/jpeg;base64," + "A/+9".repeat(50_000),
            "x".repeat(10_000) + "\"" + "y".repeat(10_000) + "\\",
        )
        // Tous les caractères d'un octet, seuls et entourés.
        for (c in 0..0xFF) {
            cases.add(c.toChar().toString())
            cases.add("a${c.toChar()}b")
        }
        val lcg = Lcg(7uL)
        repeat(500) {
            cases.add(buildString { repeat(lcg.next(40)) { append("ab\"\\\n\t\u0001é😀 "[lcg.next(11)]) } })
        }
        for (s in cases) {
            val json = JSON.encode(s)
            assertEquals(reference(s), json)
            assertEquals(s, JSON.parse(json)?.jsonPrimitive?.content, "se relit tel quel")
        }
    }

    @Test
    fun aMessageKeepsItsExactJson() {
        val m = Message("c", "m1", "WGSEAJNSZJ", 12.5, Content(text = "Il a dit « \"oui\" »\net c:\\temp"))
        val json = JSON.encode(m)
        assertEquals(true, json.contains("\"text\":\"Il a dit « \\\"oui\\\" »\\net c:\\\\temp\""))
        assertEquals(m, JSON.decode(Message, json))
    }
}
