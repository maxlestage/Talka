// L'onglet Appels et l'écran d'appel, dessinés pour de vrai par Compose (sous
// Robolectric) : le journal, rappeler, effacer, un nouvel appel à plusieurs
// contacts, un appel qui arrive (refuser, répondre). WebRTC est faux
// (`FakeMedia`), et les signaux sont gardés au lieu de partir.

package talka.app

import android.Manifest
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import talka.app.call.CallEngine
import talka.app.call.FakeMedia
import talka.app.call.testEnv
import talka.kit.Body
import talka.kit.CallEntry
import talka.kit.Identity
import talka.kit.Relay
import talka.kit.nowMs
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CallsUiTest : AppTestBase() {
    @get:Rule
    val compose = createComposeRule()

    private val sent = ArrayList<Pair<String, Body.Call>>()
    private var m: AppModel? = null
    private val max = Identity.create()
    private val zoe = Identity.create()

    @Before
    fun setUp() {
        install()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    }

    @After
    fun tearDown() {
        m?.close()
        uninstall()
    }

    /** Léa, en ligne, avec Max et Zoé dans ses contacts, sur l'onglet Appels. */
    private fun lea(): AppModel {
        val env = testEnv(RuntimeEnvironment.getApplication(), dir, relay) { guard.unlockCipher(it) }(
            { FakeMedia("Léa") },
            { to, body -> sent.add(to to body as Body.Call) },
        )
        val model = AppModel(env)
        m = model
        idle()
        model.createAccount("Léa", "")
        waitUntil { model.engine?.netState == Relay.State.online }
        val e = model.engine!!
        relay.known[max.code] = max
        relay.known[zoe.code] = zoe
        assertTrue(run(model) { e.addContact(max.code, "Max") })
        assertTrue(run(model) { e.addContact(zoe.code, "Zoé") })
        model.tab = AppModel.Tab.calls
        compose.setContent { TalkaTheme(model) { RootView() } }
        compose.waitForIdle()
        return model
    }

    private fun waitUntil(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (true) {
            idle()
            compose.waitForIdle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "toujours pas" }
            Thread.sleep(10)
        }
    }

    private fun waitFor(text: String) = waitUntil { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun later(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    @Test
    fun theLogCallsBackAndClears() {
        val m = lea()
        val e = m.engine!!
        compose.onNodeWithText("Aucun appel").assertIsDisplayed()
        val hour = 3_600_000.0
        e.logCall(CallEntry(id = "c1", peer = max.code, video = false, incoming = true, at = nowMs() - 3 * hour, missed = true))
        e.logCall(CallEntry(id = "c2", peer = zoe.code, video = true, incoming = false, at = nowMs() - 2 * hour, dur = 125.0))
        e.logCall(
            CallEntry(
                id = "c3", peer = max.code, video = false, incoming = true, at = nowMs() - hour, dur = 42.0,
                members = listOf(max.code, zoe.code),
            ),
        )
        compose.waitForIdle()
        // Le plus récent d'abord : le groupe (les deux autres), Zoé en vidéo, Max manqué.
        compose.onNodeWithText("Max et Zoé").assertIsDisplayed()
        compose.onNodeWithText("Groupe · 0:42").assertIsDisplayed()
        compose.onNodeWithText("Vidéo · 2:05").assertIsDisplayed()
        compose.onNodeWithText("Audio · manqué").assertIsDisplayed()
        val back = compose.onAllNodesWithContentDescription("Rappeler")
        assertEquals(3, back.fetchSemanticsNodes().size)

        // Rappeler Max (la dernière ligne) : l'écran d'appel, et l'invitation part.
        back[2].performClick()
        waitUntil { m.call != null && sent.isNotEmpty() }
        val call = assertNotNull(m.call)
        assertEquals(max.code, call.first)
        assertFalse(call.video)
        // L'invitation, puis notre candidat ICE.
        assertEquals(listOf(max.code to "offer", max.code to "ice"), sent.map { it.first to it.second.op }.distinct())
        compose.onNodeWithText("Appel…").assertIsDisplayed()
        compose.onNodeWithText("Chiffré de bout en bout").assertIsDisplayed()
        compose.onNodeWithContentDescription("Couper le micro").performClick()
        assertTrue(call.muted)
        compose.onNodeWithContentDescription("Activer le micro").assertIsDisplayed()
        // Raccrocher avant la réponse : « sans réponse » dans le journal.
        compose.onNodeWithTag("hangup").performClick()
        waitUntil { call.phase == CallEngine.Phase.ended }
        assertEquals("end", sent.last().second.op)
        later(1_500)
        assertNull(m.call)
        compose.onNodeWithText("Audio · sans réponse").assertIsDisplayed()

        // Effacer le journal.
        compose.onNodeWithText("Effacer").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Aucun appel").assertIsDisplayed()
        assertTrue(e.callLog().isEmpty())
    }

    @Test
    fun aNewCallToTwoContacts() {
        val m = lea()
        compose.onNodeWithContentDescription("Nouvel appel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Appeler").assertIsNotEnabled()
        compose.onNodeWithText("Zoé").performClick()
        compose.onNodeWithText("Max").performClick()
        compose.onNodeWithText("Appeler").assertIsEnabled().performClick()
        waitUntil { m.call != null && sent.size >= 2 }
        val call = assertNotNull(m.call)
        // Dans l'ordre où on les a cochés : Zoé, la première appelée.
        assertEquals(zoe.code, call.first)
        assertTrue(call.isGroup)
        val members = listOf(m.engine!!.me.code, max.code, zoe.code).sorted()
        assertEquals(setOf(max.code, zoe.code), sent.filter { it.second.op == "offer" }.map { it.first }.toSet())
        assertTrue(sent.filter { it.second.op == "offer" }.all { it.second.members == members })
        // Ni groupe ni discussion : le titre, ce sont les deux autres.
        assertTrue(call.title == "Max et Zoé" || call.title == "Zoé et Max", call.title)
        compose.onNodeWithText(call.title).assertIsDisplayed()
    }

    @Test
    fun anIncomingCallIsDeclinedOrAnswered() {
        val m = lea()
        m.callSignal(max.code, Body.Call("offer", "appel-1", video = true, sdp = "v=0 offre", cand = null), true)
        compose.waitForIdle()
        compose.onNodeWithText("Max").assertIsDisplayed()
        compose.onNodeWithText("Appel vidéo entrant…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refuser").performClick()
        waitUntil { m.call?.phase == CallEngine.Phase.ended }
        assertEquals(listOf(max.code to "reject"), sent.map { it.first to it.second.op }.distinct())
        compose.onNodeWithText("Appel terminé").assertIsDisplayed()
        later(1_500)
        assertNull(m.call)

        // Un autre appel : Répondre. La fausse liaison se connecte, l'appel est en cours.
        sent.clear()
        m.callSignal(max.code, Body.Call("offer", "appel-2", video = false, sdp = "v=0 offre", cand = null), true)
        compose.waitForIdle()
        compose.onNodeWithText("Appel audio entrant…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Répondre").performClick()
        waitUntil { m.call?.phase == CallEngine.Phase.live }
        assertEquals("answer", sent.first().second.op)
        compose.onNodeWithText("0:00").assertIsDisplayed()
        compose.onNodeWithContentDescription("Haut-parleur").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ajouter quelqu'un").assertIsDisplayed()
        compose.onNodeWithTag("hangup").performClick()
        waitUntil { m.call?.phase == CallEngine.Phase.ended }
        assertEquals("end", sent.last().second.op)
    }
}
