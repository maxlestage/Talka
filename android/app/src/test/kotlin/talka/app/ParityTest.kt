// La parité avec l'iPhone : les écrans se redessinent quand le moteur
// change (sans qu'on les touche), les actions de balayage de la liste des
// discussions et des clés Brocolis, le menu ⋮ d'une discussion (et le
// choix de ses clés Brocolis), les sauvegardes et leur mot de passe.

package talka.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import talka.app.ui.BrocolisKeysView
import talka.app.ui.ChatsTab
import talka.app.ui.ContactInfoView
import talka.app.ui.GroupInfoView
import talka.app.ui.SettingsTab
import talka.app.ui.StatusTab
import talka.kit.Brocolis
import talka.kit.Content
import talka.kit.Identity
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParityTest : AppTestBase() {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() = install()

    @After
    fun tearDown() = uninstall()

    private fun waitUntil(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            idle()
            compose.waitForIdle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "toujours pas" }
            Thread.sleep(10)
        }
    }

    private fun waitFor(text: String) = waitUntil { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun scrollTo(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
        return compose.onNodeWithText(text, substring = true)
    }

    private fun account(name: String = "Léa"): AppModel = model().also { it.createAccount(name, "") }

    private fun friend(m: AppModel, name: String): Identity {
        val f = Identity.create()
        relay.known[f.code] = f
        run(m) { m.engine!!.addContact(f.code, name) }
        return f
    }

    // ----- Le redessin : le moteur change, l'écran suit -----

    @Test
    fun settingsFollowTheEngine() {
        val m = account()
        val e = m.engine!!
        compose.setContent { TalkaTheme(m) { SettingsTab() } }
        compose.waitForIdle()
        scrollTo("Taille du texte")
        // D'ailleurs (un autre appareil, la montre…) : la ligne suit.
        e.saveSettings { it.copy(fontSize = 19) }
        waitFor("Taille du texte : 19")
    }

    @Test
    fun contactInfoFollowsTheEngine() {
        val m = account()
        val e = m.engine!!
        val max = friend(m, "Max")
        e.ensureDirectChat(max.code)
        compose.setContent { TalkaTheme(m) { ContactInfoView(max.code, onBack = {}) } }
        compose.waitForIdle()
        scrollTo("Fond d'écran")
        e.updateChat(max.code) { it.copy(wallpaper = "mint") }
        waitUntil { compose.onAllNodes(hasContentDescription("Fond d'écran : Menthe")).fetchSemanticsNodes().isNotEmpty() }
        val msg = e.send(max.code, Content(text = "À garder"))!!
        e.toggleStar(max.code, msg.id)
        waitFor("Messages importants")
    }

    @Test
    fun groupInfoFollowsTheEngine() {
        val m = account()
        val e = m.engine!!
        val bob = friend(m, "Bob")
        val g = e.createGroup("Amis", listOf(bob.code), "")
        compose.setContent { TalkaTheme(m) { GroupInfoView(g.id, onBack = {}) } }
        compose.waitForIdle()
        scrollTo("Quitter le groupe")
        val msg = e.send(g.id, Content(text = "À garder"))!!
        e.toggleStar(g.id, msg.id)
        waitFor("Messages importants")
    }

    @Test
    fun statusAndKeysFollowTheEngine() {
        val m = account()
        val e = m.engine!!
        val tab = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent { TalkaTheme(m) { if (tab.intValue == 0) StatusTab() else BrocolisKeysView(onBack = {}) } }
        compose.waitForIdle()
        e.postStatus("text", "Bonjour", "#008069", "")
        waitFor("vue(s)")
        tab.intValue = 1
        compose.waitForIdle()
        e.addBrocolisKey("Maman", Brocolis.generateKey())
        waitFor("Maman")
    }

    // ----- Les balayages -----

    @Test
    fun aChatIsSwipedLikeOnTheIPhone() {
        val m = account()
        val e = m.engine!!
        val max = friend(m, "Max")
        e.ensureDirectChat(max.code)
        compose.setContent { TalkaTheme(m) { ChatsTab() } }
        compose.waitForIdle()
        // Rien du balayage tant qu'on ne balaie pas.
        assertTrue(compose.onAllNodesWithText("Archiver").fetchSemanticsNodes().isEmpty())

        // Vers la droite : lu / non lu, épingler.
        compose.onNodeWithText("Max").performTouchInput { swipeRight(startX = centerX, endX = centerX + 150.dp.toPx()) }
        compose.waitForIdle()
        compose.onNodeWithText("Non lu").assertIsDisplayed()
        compose.onNodeWithText("Épingler").performClick()
        waitUntil { e.chat(max.code)!!.pinned }
        compose.onNodeWithContentDescription("Épinglée", useUnmergedTree = true).assertExists()

        // Vers la gauche : supprimer, archiver, la sourdine.
        compose.onNodeWithText("Max").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 150.dp.toPx()) }
        compose.waitForIdle()
        compose.onNodeWithText("Supprimer").assertIsDisplayed()
        compose.onNodeWithText("Silence").performClick()
        compose.onNodeWithText("Plus de notification ni de son ; les messages arrivent quand même.").assertIsDisplayed()
        compose.onNodeWithText("8 heures").performClick()
        waitUntil { e.chat(max.code)!!.muted }
        compose.onNodeWithText("Max").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 150.dp.toPx()) }
        compose.waitForIdle()
        compose.onNodeWithText("Archiver").performClick()
        waitUntil { e.chat(max.code)!!.archived }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Max").fetchSemanticsNodes().isEmpty())

        // Les archivées : on désarchive en balayant ; vide, le texte de l'iPhone.
        compose.onNodeWithText("Archivées").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Max").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 150.dp.toPx()) }
        compose.waitForIdle()
        compose.onNodeWithText("Son").assertIsDisplayed()
        compose.onNodeWithText("Désarchiver").performClick()
        waitUntil { !e.chat(max.code)!!.archived }
        compose.waitForIdle()
        compose.onNodeWithText("Aucune discussion archivée").assertIsDisplayed()
        compose.onNodeWithText("Balayez une discussion vers la gauche pour l'archiver.").assertIsDisplayed()

        // Les mêmes actions pour TalkBack ; l'appui long garde son menu.
        compose.onNodeWithText("Toutes").performClick()
        compose.waitForIdle()
        val row = compose.onNodeWithText("Max").fetchSemanticsNode().config
        val actions = assertNotNull(row.getOrNull(SemanticsActions.CustomActions))
        assertEquals(listOf("Non lu", "Désépingler", "Supprimer", "Archiver", "Son"), actions.map { it.label })
        assertNotNull(row.getOrNull(SemanticsActions.OnLongClick))
        compose.runOnUiThread { actions.first { it.label == "Non lu" }.action() }
        waitUntil { e.chat(max.code)!!.markedUnread }

        // Supprimer, tout au bout à droite.
        compose.onNodeWithText("Max").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 150.dp.toPx()) }
        compose.waitForIdle()
        compose.onNodeWithText("Supprimer").performClick()
        waitUntil { e.chat(max.code) == null }
    }

    /**
     * Comme sur l'iPhone : un balayage long fait aussitôt l'action du bord ;
     * une seule ligne ouverte à la fois ; la toucher la referme sans
     * l'ouvrir.
     */
    @Test
    fun aLongSwipeDoesTheEdgeActionAndOneRowOpensAtATime() {
        val m = account()
        val e = m.engine!!
        val max = friend(m, "Max")
        val zoe = friend(m, "Zoé")
        e.ensureDirectChat(max.code)
        e.ensureDirectChat(zoe.code)
        compose.setContent { TalkaTheme(m) { ChatsTab() } }
        compose.waitForIdle()

        // Tout au bout vers la droite : « Non lu », sans toucher de tuile.
        compose.onNodeWithText("Max").performTouchInput { swipeRight(startX = centerX, endX = centerX + 400.dp.toPx()) }
        waitUntil { e.chat(max.code)!!.markedUnread }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Épingler").fetchSemanticsNodes().isEmpty(), "la ligne s'est refermée")

        // Une ligne ouverte, puis une autre : la première se referme.
        compose.onNodeWithText("Max").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 150.dp.toPx()) }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Archiver").fetchSemanticsNodes().size)
        compose.onNodeWithText("Zoé").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 150.dp.toPx()) }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Archiver").fetchSemanticsNodes().size, "une seule ligne ouverte")

        // La toucher la referme, sans ouvrir la discussion.
        compose.onNodeWithText("Zoé").performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Archiver").fetchSemanticsNodes().isEmpty())
        assertTrue(m.path.isEmpty(), "la discussion ne s'est pas ouverte")

        // Tout au bout vers la gauche : supprimer, aussitôt.
        compose.onNodeWithText("Zoé").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 400.dp.toPx()) }
        waitUntil { e.chat(zoe.code) == null }
        assertTrue(e.chat(max.code) != null)
    }

    /** Faire défiler la liste referme la ligne ouverte. */
    @Test
    fun scrollingClosesTheOpenRow() {
        val m = account()
        val e = m.engine!!
        val codes = (1..25).map { i -> friend(m, "Ami %02d".format(i)).code }
        for (c in codes) e.ensureDirectChat(c)
        compose.setContent { TalkaTheme(m) { ChatsTab() } }
        compose.waitForIdle()
        val first = compose.onAllNodesWithText("Ami", substring = true)[0]
        first.performTouchInput { swipeLeft(startX = centerX, endX = centerX - 150.dp.toPx()) }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Archiver").fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Ami", substring = true)[2].performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Archiver").fetchSemanticsNodes().isEmpty(), "le défilement l'a refermée")
    }

    @Test
    fun aBrocolisKeyIsSwipedLikeOnTheIPhone() {
        val m = account()
        val e = m.engine!!
        e.addBrocolisKey("Maman", Brocolis.generateKey())
        compose.setContent { TalkaTheme(m) { BrocolisKeysView(onBack = {}) } }
        compose.waitForIdle()
        scrollTo("Balayez une clé")
        compose.onNodeWithText("Maman").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 150.dp.toPx()) }
        compose.waitForIdle()
        compose.onNodeWithText("Oublier").assertIsDisplayed()
        compose.onNodeWithText("Renommer").assertIsDisplayed()
        compose.onNodeWithText("Renouveler").performClick()
        compose.onNodeWithText("Renouveler cette clé ?").assertIsDisplayed()
        compose.onNodeWithText("Renouveler").performClick()
        waitUntil { e.brocolisKeys.size == 2 }
        // L'ancienne ne se renouvelle plus : oublier et renommer seulement.
        compose.onNodeWithText("Maman (ancienne)").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 150.dp.toPx()) }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Renouveler").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Oublier").performClick()
        compose.onNodeWithText("Oublier cette clé ?").assertIsDisplayed()
        compose.onAllNodesWithText("Oublier")[0].performClick()
        waitUntil { e.brocolisKeys.size == 1 }
    }

    // ----- Le menu ⋮ d'une discussion (un seul, celui de l'iPhone) -----

    @Test
    fun theChatMenuChoosesBrocolisKeysAndManagesThem() {
        val m = account()
        val e = m.engine!!
        val max = friend(m, "Max")
        val k = e.addBrocolisKey("Maman", Brocolis.generateKey())
        m.openChat(max.code)
        compose.setContent { TalkaTheme(m) { RootView() } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Plus d'options").performClick()
        compose.onNodeWithText("Infos du contact").assertIsDisplayed()
        compose.onNodeWithText("Exporter la discussion").assertIsDisplayed()
        compose.onNodeWithText("Chiffrer avec Brocolis 🥦").performClick()
        compose.waitForIdle()
        // Un interrupteur par clé, comme le `Toggle` de l'iPhone.
        val key = hasText("🥦 Maman", substring = true) and isToggleable()
        compose.onNode(key).assertIsOff()
        compose.onNode(key).performClick()
        compose.onNode(key).assertIsOn()
        // « Gérer les clés » s'ouvre par-dessus ; le retour ramène à la feuille, la clé toujours cochée.
        compose.onNodeWithText("Gérer les clés").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Créer une clé").assertIsDisplayed()
        val backs = compose.onAllNodes(hasContentDescription("Retour"))
        backs[backs.fetchSemanticsNodes().size - 1].performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Créer une clé").fetchSemanticsNodes().isEmpty())
        compose.onNode(key).assertIsOn()
        compose.onNodeWithText("Enregistrer").performClick()
        waitUntil { e.chat(max.code)?.brocolis == listOf(k.id) }
        compose.onNodeWithContentDescription("Plus d'options").performClick()
        compose.onNodeWithText("Brocolis 🥦 : Maman").assertIsDisplayed()

        // La sourdine, puis le son.
        compose.onNodeWithText("Mettre en sourdine").performClick()
        compose.onNodeWithText("1 semaine").performClick()
        waitUntil { e.chat(max.code)!!.muted }
        compose.onNodeWithContentDescription("Plus d'options").performClick()
        compose.onNodeWithText("Réactiver les notifications").performClick()
        waitUntil { !e.chat(max.code)!!.muted }
    }

    // ----- Les sauvegardes et leur mot de passe -----

    /** Le sélecteur de fichiers d'Android rend `uri` à la dernière demande. */
    private fun pick(uri: Uri) {
        val activity = shadowOf(compose.activity)
        val asked = assertNotNull(activity.nextStartedActivityForResult, "pas de sélecteur de fichiers")
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, asked.intent.action)
        activity.receiveResult(asked.intent, Activity.RESULT_OK, Intent().setData(uri))
        idle()
    }

    @Test
    fun aBackupIsRestoredFromTheWelcomeWithItsPassword() {
        val m = account()
        val e = m.engine!!
        friend(m, "Max")
        val code = e.me.code
        val backup = assertNotNull(e.exportBackup("un bon mot de passe"))
        m.wipe()
        idle()
        assertNull(m.engine)
        val uri = Uri.parse("content://talka.test/talka-sauvegarde.talka")
        shadowOf(compose.activity.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(backup) }
        compose.setContent { TalkaTheme(m) { RootView() } }
        compose.waitForIdle()

        // Un mauvais mot de passe : l'erreur, et rien n'a changé.
        scrollTo("Restaurer une sauvegarde").performClick()
        pick(uri)
        waitFor("Mot de passe de la sauvegarde")
        compose.onNodeWithText("Cet appareil remplacera celui d'où vient la sauvegarde", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Mot de passe").performTextInput("pas le bon")
        compose.onNodeWithText("Restaurer").performClick()
        waitFor("Mot de passe incorrect.")
        assertNull(m.engine)
        assertTrue(compose.onAllNodesWithText("Mot de passe de la sauvegarde").fetchSemanticsNodes().isEmpty())

        // Le bon : le compte revient, avec ses contacts.
        scrollTo("Restaurer une sauvegarde").performClick()
        pick(uri)
        waitFor("Mot de passe de la sauvegarde")
        compose.onNodeWithText("Mot de passe").performTextInput("un bon mot de passe")
        compose.onNodeWithText("Restaurer").performClick()
        waitUntil { m.engine != null }
        waitFor("Discussions")
        assertEquals(code, m.engine?.me?.code)
        assertEquals("Max", m.engine?.contacts?.values?.single()?.name)
    }

    @Test
    fun backupsInTheSettingsAskForTheirPassword() {
        val m = account()
        val e = m.engine!!
        val code = e.me.code
        val backup = assertNotNull(e.exportBackup("un bon mot de passe"))
        val uri = Uri.parse("content://talka.test/talka-sauvegarde.talka")
        shadowOf(compose.activity.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(backup) }
        compose.setContent { TalkaTheme(m) { RootView() } }
        compose.waitForIdle()
        compose.onNodeWithText("Réglages").performClick()
        compose.waitForIdle()

        // Exporter : 8 caractères au moins.
        scrollTo("Exporter une sauvegarde").performClick()
        waitFor("Mot de passe de la sauvegarde")
        compose.onNodeWithText("Il sera demandé pour restaurer. Sans lui, la sauvegarde est illisible.").assertIsDisplayed()
        compose.onNodeWithText("Au moins 8 caractères").performTextInput("court")
        compose.onNodeWithText("Exporter").performClick()
        waitUntil { m.toast?.text == "Mot de passe trop court (8 caractères au moins)." }
        m.toast = null

        // Restaurer : le mauvais mot de passe le dit (et Talka reste ouvert), le bon restaure.
        scrollTo("Restaurer une sauvegarde").performClick()
        pick(uri)
        waitFor("Restaurer la sauvegarde ?")
        compose.onNodeWithText("Mot de passe").performTextInput("pas le bon")
        compose.onNodeWithText("Restaurer").performClick()
        waitUntil { m.toast?.text == "Mot de passe incorrect." && m.engine != null }
        m.toast = null
        assertEquals(code, m.engine?.me?.code)
        scrollTo("Restaurer une sauvegarde").performClick()
        pick(uri)
        waitFor("Restaurer la sauvegarde ?")
        compose.onNodeWithText("Mot de passe").performTextInput("un bon mot de passe")
        compose.onNodeWithText("Restaurer").performClick()
        waitUntil { m.toast != null && m.engine != null }
        assertEquals("Sauvegarde restaurée ✓", m.toast?.text)
        assertEquals(code, m.engine?.me?.code)
    }
}
