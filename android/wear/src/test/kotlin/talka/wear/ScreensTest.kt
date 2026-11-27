// Les écrans de la montre (`Views.swift`), dessinés sans montre
// (Robolectric) : la liste, l'attente du téléphone, une discussion, répondre.

package talka.wear

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import talka.kit.WatchAction
import talka.kit.WatchLink
import talka.kit.WidgetShare
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val phone = FakePhone()
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("talka.montre.ecrans", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        WidgetShare.storage = MemoryStorage()
        WidgetShare.clear()
    }

    @After
    fun tearDown() {
        WidgetShare.clear()
        WidgetShare.storage = null
    }

    /** Fait défiler la discussion (la couronne, ou le doigt) jusqu'à ce texte. */
    private fun scrollUntil(text: String, towardsEnd: Boolean) {
        repeat(20) {
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            compose.onNodeWithTag("discussion").performTouchInput { if (towardsEnd) swipeUp() else swipeDown() }
            compose.waitForIdle()
        }
    }

    @Test
    fun waitingForThePhone() {
        val m = WatchModel(phone, prefs)
        compose.setContent { TalkaWatchApp(m) }
        compose.onNodeWithText("Ouvrez Talka sur votre téléphone").assertExists()
        compose.onNodeWithText("Actualiser").performClick()
        assertTrue(phone.actions().all { it.kind == WatchAction.Kind.refresh })
        assertTrue(phone.sent.size >= 2, "une demande à l'ouverture, une au toucher")
    }

    @Test
    fun noChatsYet() {
        val m = WatchModel(phone, prefs)
        m.receive(WatchLink.encode(sampleSnapshot().copy(chats = emptyList())))
        compose.setContent { TalkaWatchApp(m) }
        compose.onNodeWithText("Aucune discussion").assertExists()
        compose.onNodeWithText("Vos discussions récentes apparaîtront ici.").assertExists()
    }

    @Test
    fun theListThenAChatAndAQuickReply() {
        val m = WatchModel(phone, prefs)
        phone.near = false
        phone.events!!.activated(WatchLink.encode(sampleSnapshot()), reachable = false)
        compose.setContent { TalkaWatchApp(m) }
        compose.onNodeWithText("Léa").assertExists()
        compose.onNodeWithText("Famille").assertExists()
        compose.onNodeWithText("Papa : 📷 Photo").assertExists()
        compose.onNodeWithContentDescription("2 non lus").assertExists()
        compose.onNodeWithContentDescription("Téléphone hors de portée : les réponses partiront à son retour").assertExists()

        compose.onNodeWithText("Léa").performClick()
        compose.waitForIdle()
        // Ouverte sur la fin (le bas de la réponse) ; les messages sont au-dessus.
        compose.onNodeWithText("Non").assertExists()
        scrollUntil("Salut", towardsEnd = false)
        compose.onNodeWithText("Salut").assertExists()
        // Ouverte : lue (en file, le téléphone est loin).
        assertEquals(0, m.chat("WGSEAJNSZJ")?.unread)
        assertEquals(WatchAction(WatchAction.Kind.read, chat = "WGSEAJNSZJ"), phone.actions(phone.queued).last())

        scrollUntil("Je te rappelle", towardsEnd = true)
        compose.onNodeWithText("Je te rappelle").performClick()
        compose.waitForIdle()
        assertEquals(WatchAction(WatchAction.Kind.send, chat = "WGSEAJNSZJ", text = "Je te rappelle"), phone.actions(phone.queued).last())
        assertEquals("Je te rappelle", m.chat("WGSEAJNSZJ")?.messages?.last()?.text)
    }

    @Test
    fun aGroupLeftIsReadOnly() {
        val m = WatchModel(phone, prefs)
        val s = sampleSnapshot()
        m.receive(WatchLink.encode(s.copy(chats = s.chats.map { if (it.group) it.copy(readOnly = true) else it })))
        compose.setContent { ConversationScreen(m, "g:famille", record = {}, react = {}) }
        compose.onNodeWithText("Papa").assertExists()
        compose.onNodeWithText("Vous ne faites plus partie de ce groupe.").assertExists()
        compose.onNodeWithText("Vocal").assertDoesNotExist()
    }

    @Test
    fun reacting() {
        var picked = ""
        compose.setContent { ReactionPicker("❤️") { picked = it } }
        compose.onNodeWithText("Réagir").assertExists()
        compose.onNodeWithText("Touchez ❤️ pour la retirer").assertExists()
        compose.onNodeWithContentDescription("Retirer ❤️").performClick()
        assertEquals("❤️", picked)
    }
}
