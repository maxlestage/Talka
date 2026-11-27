// Les écrans, dessinés pour de vrai par Compose (sous Robolectric) :
// l'accueil, la création du compte (contre un faux relais local), la liste
// des discussions, l'ajout d'un contact, les réglages, le verrou.

package talka.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasScrollAction
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import talka.kit.Identity
import talka.kit.TalkaCode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTest : AppTestBase() {
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

    /**
     * Attend que `done` soit vrai, en laissant tourner le fil principal (ce
     * que le moteur y lance) et Compose entre deux essais.
     */
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

    @Test
    fun welcomeCreatesTheAccountThenShowsTheChats() {
        val m = model()
        show(m)
        compose.onNodeWithText("Bienvenue sur Talka").assertIsDisplayed()
        compose.onNodeWithText("Créer mon code").assertIsNotEnabled()
        compose.onNodeWithText("Votre nom").performTextInput("Léa")
        compose.onNodeWithText("Créer mon code").performClick()
        waitFor("Discussions")
        val e = assertNotNull(m.engine)
        assertEquals("Léa", e.profile.name)
        // La liste vide, et les quatre onglets.
        compose.onNodeWithText("Aucune discussion").assertIsDisplayed()
        compose.onNodeWithText("Ajoutez un contact avec son code Talka pour commencer.").assertIsDisplayed()
        for (tab in listOf("Actus", "Appels", "Réglages")) compose.onNodeWithText(tab).assertIsDisplayed()
        // Le faux relais a accepté la présentation : pas de bandeau « Connexion… ».
        assertTrue(compose.onAllNodesWithText("Connexion…").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun addAContactByItsCodeAndSeeItInTheList() {
        val m = model()
        m.createAccount("Léa", "")
        val friend = Identity.create()
        relay.known[friend.code] = friend
        show(m)
        compose.onNodeWithContentDescription("Nouvelle discussion").performClick()
        compose.onNodeWithText("Ajouter un contact par son code").performClick()
        compose.onNode(hasContentDescription("Code")).performTextInput(TalkaCode.pretty(friend.code).lowercase())
        compose.onNodeWithText("Nom (facultatif)").performTextInput("Max")
        compose.onNodeWithText("Ajouter").performClick()
        // Ses clés arrivent du faux relais : la discussion s'ouvre.
        waitUntil { m.path == listOf(friend.code) }
        assertEquals(friend.sign, m.engine?.contact(friend.code)?.sign)
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Max").assertIsDisplayed()
        compose.onNodeWithText("Nouvelle discussion").assertIsDisplayed()
        // Le filtre « Non lues » : rien.
        compose.onNodeWithText("Non lues").performClick()
        compose.onNodeWithText("Aucune discussion non lue").assertIsDisplayed()
        // La recherche par nom.
        compose.onNodeWithText("Toutes").performClick()
        compose.onNodeWithText("Rechercher").performTextInput("Ma")
        compose.onNodeWithText("Max").assertIsDisplayed()
    }

    @Test
    fun aLinkOpensTheAddContactSheet() {
        val m = model()
        m.createAccount("Léa", "")
        show(m)
        m.open("https://relais.test/c/K7Q2MX9PRT")
        compose.waitForIdle()
        compose.onNodeWithText("Ajouter un contact").assertIsDisplayed()
        compose.onNodeWithText("K7Q2M-X9PRT").assertIsDisplayed()
    }

    @Test
    fun settingsShowTheProfileAndTheCode() {
        val m = model()
        m.createAccount("Léa", "")
        show(m)
        compose.onNodeWithText("Réglages").performClick()
        compose.waitForIdle()
        val code = TalkaCode.pretty(m.engine!!.me.code)
        compose.onNodeWithText(code).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Code de verrouillage"))
        compose.onNodeWithText("Code de verrouillage").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Tout effacer"))
        compose.onNodeWithText("Tout effacer").performClick()
        compose.onNodeWithText("Effacer le code, les contacts et les messages").performClick()
        waitFor("Bienvenue sur Talka")
    }

    @Test
    fun theLockScreenOpensWithTheCode() {
        val first = model()
        first.createAccount("Léa", "")
        run(first) { first.setPin("482916") }
        quit(first)
        val m = model()
        assertTrue(m.codeLocked)
        show(m)
        compose.onNodeWithText("Talka est verrouillé").assertIsDisplayed()
        for (d in "111111") compose.onNodeWithText("$d").performClick()
        compose.onNodeWithText("OK").performClick()
        waitFor("Code incorrect")
        for (d in "482916") compose.onNodeWithText("$d").performClick()
        compose.onNodeWithText("OK").performClick()
        waitFor("Discussions")
        assertEquals("Léa", m.engine?.profile?.name)
    }
}
