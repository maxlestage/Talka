// Le cycle de vie, comme sur l'iPhone (`TalkaApp.swift`, `AppModel.swift`,
// `Info.plist`) : ce que couvrent le verrou et l'écran de confidentialité,
// le sens de l'écran, le nom d'une sauvegarde.

package talka.app

import android.content.pm.ActivityInfo
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import talka.kit.Content
import talka.kit.Message
import java.time.Instant
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LifecycleTest : AppTestBase() {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = install()

    @After
    fun tearDown() = uninstall()

    private fun show(m: AppModel) {
        compose.setContent { TalkaTheme(m) { RootView() } }
        compose.waitForIdle()
    }

    /** Un changement d'état que Compose voit tout de suite, l'horloge du test arrêtée. */
    private fun change(block: () -> Unit) {
        block()
        Snapshot.sendApplyNotifications()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Un compte avec un code, et un message de Max qui arrive hors de sa discussion. */
    private fun withPin(): Pair<AppModel, () -> Unit> {
        val m = model()
        m.createAccount("Léa", "")
        idle()
        run(m) { m.setPin("482916") }
        val e = assertNotNull(m.engine)
        val chat = e.ensureDirectChat("K7Q2MX9PRT")
        val msg = Message(chat.id, "m1", "K7Q2MX9PRT", 1.0, Content(text = "Rendez-vous à 19 h"))
        return m to { m.incoming(chat, msg, false) }
    }

    @Test
    fun aToastStaysUnderTheLockLikeOnTheIPhone() {
        val (m, incoming) = withPin()
        // Talka quitté puis rouvert : verrouillé (le code est demandé tout de suite).
        m.scene(AppModel.Phase.background)
        m.scene(AppModel.Phase.active)
        assertTrue(m.locked)
        show(m)
        compose.onNodeWithText("Talka est verrouillé").assertIsDisplayed()

        // Un message arrive, Talka à l'écran mais verrouillé : son texte ne se lit pas par-dessus le verrou.
        incoming()
        assertEquals("K7Q2M-X9PRT : Rendez-vous à 19 h", m.toast?.text)
        compose.waitForIdle()
        assertTrue(!shown("K7Q2M-X9PRT : Rendez-vous à 19 h"), "le bandeau se lit par-dessus le verrou")

        // Déverrouillé dans les trois secondes : le bandeau est là.
        m.locked = false
        compose.waitForIdle()
        compose.onNodeWithText("K7Q2M-X9PRT : Rendez-vous à 19 h").assertIsDisplayed()
    }

    @Test
    fun aToastStaysUnderThePrivacyCover() {
        val m = model()
        m.createAccount("Léa", "")
        idle()
        show(m)
        m.show("Max a réagi 👍 à votre message")
        compose.waitForIdle()
        compose.onNodeWithText("Max a réagi 👍 à votre message").assertIsDisplayed()
        // Hors du premier plan (le sélecteur d'apps) : seulement le cadenas et « Talka ».
        m.scene(AppModel.Phase.inactive)
        compose.waitForIdle()
        assertTrue(!shown("Max a réagi 👍 à votre message"), "le bandeau se lit par-dessus l'écran de confidentialité")
        m.scene(AppModel.Phase.active)
        compose.waitForIdle()
        compose.onNodeWithText("Max a réagi 👍 à votre message").assertIsDisplayed()
    }

    @Test
    fun theLoadingScreenFadesAwayLikeOnTheIPhone() {
        val m = model()
        m.createAccount("Léa", "")
        idle()
        m.loading = "Ouverture de Talka…"
        show(m)
        val loading = "Chargement de Talka. Ouverture de Talka…"
        compose.onNodeWithContentDescription(loading).assertIsDisplayed()
        // `withAnimation(.easeOut(duration: 0.35)) { loading = nil }` : il s'efface, il ne disparaît pas d'un coup.
        compose.mainClock.autoAdvance = false
        change { m.loading = null }
        compose.mainClock.advanceTimeBy(120)
        compose.onNodeWithContentDescription(loading).assertExists()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription(loading).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Aucune discussion").assertIsDisplayed()
    }

    @Test
    fun theLockFadesAwayButComesAtOnce() {
        val (m, _) = withPin()
        show(m)
        compose.mainClock.autoAdvance = false
        // Il vient d'un coup : rien ne se lit à travers.
        change { m.locked = true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Talka est verrouillé").assertIsDisplayed()
        // Il s'efface (`.transition(.opacity)`, 0,2 s).
        change { m.locked = false }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Talka est verrouillé").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Talka est verrouillé").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun theBackupIsNamedLikeOnTheIPhone() {
        // `ISO8601DateFormatter().string(from: Date()).prefix(10)` : le jour en temps universel.
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        try {
            // Le 21 octobre 2026 à 23 h 30 UTC : déjà le 22 à Paris.
            assertEquals("talka-sauvegarde-2026-10-21.talka", AppModel.backupFilename(Instant.ofEpochMilli(1792625400000)))
            assertEquals("talka-sauvegarde-2026-03-03.talka", AppModel.backupFilename(Instant.ofEpochMilli(1772535600000)))
        } finally {
            TimeZone.setDefault(zone)
        }
        val m = model()
        assertTrue(Regex("""talka-sauvegarde-\d{4}-\d{2}-\d{2}\.talka""").matches(m.backupFilename))
    }

    // ----- Le sens de l'écran (`UISupportedInterfaceOrientations`) -----

    private fun orientation(): Int {
        val m = model()
        (RuntimeEnvironment.getApplication() as TalkaApp).preset = m
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val o = controller.get().requestedOrientation
        controller.pause().stop().destroy()
        return o
    }

    @Test
    @Config(qualifiers = "sw411dp-w411dp-h891dp-port-xxhdpi")
    fun aPhoneStaysUpright() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientation())
    }

    @Test
    @Config(qualifiers = "sw800dp-w1280dp-h800dp-land-xhdpi")
    fun aTabletTurnsFreely() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, orientation())
    }
}
