// Les nombres s'écrivent comme serde_json (le navigateur) : une heure en
// millisecondes avec ses fractions (`nowMs()`, comme l'iPhone) reste sans
// exposant. Les textes attendus sont ceux de `serde_json::to_string`.

package talka.kit

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonNumberTests {
    @Test
    fun testNumbersAreWrittenLikeSerde() {
        val serde = listOf(
            1795740793297.5 to "1795740793297.5",
            1795740793297.123 to "1795740793297.123",
            0.00001 to "0.00001",
            0.000015 to "0.000015",
            1e15 to "1000000000000000.0",
            1.5e15 to "1500000000000000.0",
            9999999999999998.0 to "9999999999999998.0",
            12345.678 to "12345.678",
            0.1 to "0.1",
            1e7 to "10000000.0",
            1.25e7 to "12500000.0",
            3.0 to "3.0",
        )
        for ((v, text) in serde) assertEquals(text, JSON.write(JsonPrimitive(v)), "$v")
        // L'heure a ses fractions, comme sur l'iPhone, et se relit telle quelle.
        val t = nowMs()
        assertTrue(t > 1.7e12)
        assertEquals(t, JSON.parse(JSON.write(JsonPrimitive(t))).asDouble())
    }
}
