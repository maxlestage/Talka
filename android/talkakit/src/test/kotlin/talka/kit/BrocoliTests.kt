// L'œuf de Pâques 🥦 : les mêmes messages font pleuvoir des brocolis ici,
// sur l'iPhone et dans le navigateur (`BrocoliTests.swift`).

package talka.kit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrocoliTests {
    private val me = "WGSEAJNSZJ"

    private fun message(text: String, from: String = "K7Q2MX9PRT", openAt: Double? = null): Message =
        Message(from, "m1", from, 1.0, Content(text = text, openAt = openAt))

    @Test
    fun testTextAndEmojiSummonBrocolis() {
        assertTrue(message("Tu veux des brocolis ? 🥦").summonsBrocoli(me))
        assertTrue(message("🥦").summonsBrocoli(me))
        assertTrue(message("BROCOLI").summonsBrocoli(me))
        assertTrue(message("brócoli").summonsBrocoli(me))
        assertFalse(message("Des carottes 🥕").summonsBrocoli(me))
    }

    @Test
    fun testOnlyVisibleTextSummons() {
        val deleted = message("🥦").copy(deleted = true)
        assertFalse(deleted.summonsBrocoli(me))
        val file = message("").copy(kind = "file", name = "brocoli.pdf")
        assertFalse(file.summonsBrocoli(me))
    }

    @Test
    fun testSealedCapsuleKeepsItsSecret() {
        val later = nowMs() + 3_600_000
        assertFalse(message("🥦", openAt = later).summonsBrocoli(me))
        // L'auteur, lui, voit ce qu'il a écrit.
        assertTrue(message("🥦", from = me, openAt = later).summonsBrocoli(me))
    }
}
