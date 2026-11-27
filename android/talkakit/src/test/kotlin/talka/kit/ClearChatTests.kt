// Ce que `ios/Tests/TalkaKitTests/ClearChatTests.swift` vérifie de la seule
// mémoire. Ses tests passent tous par le moteur (`Engine`, pas encore porté) ;
// ici, la partie qui ne dépend que de `Store` : le ménage des vieux messages
// tel que le moteur le demande (`sweep` appelle `removeOld` avec la limite
// de `keepDays`). Le reste viendra avec le moteur.

package talka.kit

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClearChatTests {
    private val stores = Stores()

    @AfterTest
    fun cleanUp() = stores.closeAll()

    /**
     * Le ménage des vieux messages ne lit que leur âge : les trop vieux
     * partent, sauf les importants et une capsule pas encore ouverte depuis
     * assez longtemps ; la discussion n'est pas chargée, et une discussion
     * chargée est nettoyée de la même façon. (Sans le moteur : ni enterrés,
     * ni aperçu de la discussion.)
     */
    @Test
    fun testOldMessagesLeaveByTheirAgeAlone() {
        val dir = stores.dir()
        val store = stores.open(dir)
        val codes = listOf("K7Q2MX9PRT", "WGSEAJNSZJ")
        val now = nowMs()
        val keepDays = keepDaysDefault
        val limit = now - keepDays.toDouble() * 86_400_000
        store.transaction {
            for (code in codes) {
                for (i in 0 until 400) {
                    // Les 150 premiers sont trop vieux.
                    val at = (if (i < 150) limit - 3_600_000 else now) - (400 - i).toDouble() * 1000
                    var m = Message(code, "m$i", code, at, Content(text = "Message $i"))
                    if (i == 10 || i == 300) m = m.copy(starred = true)
                    if (i == 20) m = m.copy(starred = true, deleted = true)
                    if (i == 30) m = m.copy(openAt = now - 1_000)
                    if (i == 40) m = m.copy(openAt = limit - 10_000)
                    store.writeMessage(m)
                }
            }
        }
        val cold = stores.open(dir)
        cold.messagesOf(codes[1])
        // Ce que fait le ménage du moteur, discussion par discussion.
        fun sweep() {
            for (code in codes) cold.removeOld(code, limit, keepDays, now)
        }
        sweep()
        assertNull(cold.messages[codes[0]], "pas chargée pour son ménage")
        assertNotNull(cold.messages[codes[1]])
        for (code in codes) {
            val left = cold.datesOf(code).map { it.first }
            assertEquals(252, left.size, code)
            assertEquals(listOf("m10", "m30", "m150"), left.take(3), code)
            assertNull(cold.message(code, "m20"), "une étoile sur un message supprimé ne le garde pas")
            assertNull(cold.message(code, "m40"), "une capsule ouverte depuis trop longtemps part")
            assertEquals(2, cold.starredIds(code).size)
        }
        assertEquals(252, stores.open(dir).messagesOf(codes[0]).size)
        // Plus rien à faire l'heure suivante.
        val generation = cold.messagesGeneration
        sweep()
        assertEquals(generation, cold.messagesGeneration)
        // « Toujours garder » : rien ne part.
        assertTrue(cold.removeOld(codes[0], now + 1, keepDays = 0, now = now).isEmpty())
        assertEquals(250, cold.removeOld(codes[0], now + 1, keepDays = 1, now = now + 3 * 86_400_000).size)
        assertEquals(listOf("m10", "m300"), cold.datesOf(codes[0]).map { it.first })
    }
}
