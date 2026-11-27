// La discussion, dessinée pour de vrai par Compose (sous Robolectric),
// contre le faux relais local : l'ouvrir, envoyer, répondre, réagir,
// modifier, supprimer, les messages éphémères, les mentions, la recherche,
// les messages plus anciens à la demande ; et le format des vocaux, tel
// qu'il part sur le fil.

package talka.app

import android.Manifest
import android.media.MediaRecorder
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
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
import talka.app.ui.VoiceRecorder
import talka.app.ui.voiceBar
import talka.kit.Body
import talka.kit.Content
import talka.kit.Engine
import talka.kit.Identity
import talka.kit.TalkaCrypto
import talka.kit.Wire
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationTest : AppTestBase() {
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

    private fun waitFor(text: String, substring: Boolean = false) =
        waitUntil { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun gone(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()

    /** Léa, son contact Max (connu du faux relais), et leur discussion ouverte. */
    private fun withFriend(): Triple<AppModel, Engine, Identity> {
        val m = model()
        m.createAccount("Léa", "")
        val friend = Identity.create()
        relay.known[friend.code] = friend
        val e = assertNotNull(m.engine)
        run(m) { e.addContact(friend.code, "Max") }
        m.openChat(friend.code)
        return Triple(m, e, friend)
    }

    /** Le champ de saisie. */
    private fun field(): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and !hasContentDescription("Rechercher dans la discussion"))

    private fun type(text: String) {
        field().performTextInput(text)
        compose.waitForIdle()
    }

    private fun sendTyped(e: Engine, chat: String, text: String) {
        type(text)
        compose.onNodeWithContentDescription("Envoyer").performClick()
        waitUntil { e.messages(chat).lastOrNull()?.text == text }
    }

    /** Le menu d'un message : un toucher long sur sa bulle. */
    private fun menuOf(text: String) {
        compose.onAllNodesWithText(text)[0].performTouchInput { longClick() }
        compose.waitForIdle()
    }

    @Test
    fun openAndSendAMessage() {
        val (m, e, friend) = withFriend()
        show(m)
        // L'en-tête : son nom, les appels ; le rappel du chiffrement en haut du fil.
        compose.onNodeWithText("Max").assertIsDisplayed()
        compose.onNodeWithContentDescription("Appel audio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Appel vidéo").assertIsDisplayed()
        waitFor("Les messages sont chiffrés de bout en bout", substring = true)
        // Sans texte, le bouton est celui du vocal.
        compose.onNodeWithContentDescription("Message vocal").assertIsDisplayed()
        sendTyped(e, friend.code, "Bonjour Max")
        compose.onNodeWithText("Bonjour Max").assertIsDisplayed()
        waitFor("Aujourd'hui")
        // Le faux relais a accusé réception : ✓.
        waitUntil { e.messages(friend.code).last().sentTo.contains(friend.code) }
        assertEquals("", e.chat(friend.code)?.draft)
        // Le brouillon reste quand on quitte la discussion.
        type("À demain")
        compose.onNodeWithContentDescription("Retour").performClick()
        waitUntil { m.path.isEmpty() }
        assertEquals("À demain", e.chat(friend.code)?.draft)
        compose.onNodeWithText("Brouillon : ", substring = true).assertIsDisplayed()
    }

    @Test
    fun replyReactEditAndDelete() {
        val (m, e, friend) = withFriend()
        show(m)
        sendTyped(e, friend.code, "Bonjour")
        val first = e.messages(friend.code).last()

        // Répondre : le message cité au-dessus de la saisie, puis dans la bulle.
        menuOf("Bonjour")
        compose.onNodeWithText("Répondre").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Annuler").assertIsDisplayed()
        sendTyped(e, friend.code, "Tu vas bien ?")
        val reply = e.messages(friend.code).last()
        assertEquals(first.id, reply.reply?.id)
        assertEquals("Bonjour", reply.reply?.text)

        // Réagir 👍, depuis le menu.
        menuOf("Tu vas bien ?")
        compose.onNodeWithText("👍").performClick()
        waitUntil { e.store.message(friend.code, reply.id)?.reactions?.get(e.me.code) == "👍" }
        compose.onNodeWithContentDescription("Réactions : 👍").assertIsDisplayed()

        // Modifier : le texte revient dans la saisie, ✓ l'enregistre.
        menuOf("Tu vas bien ?")
        compose.onNodeWithText("Modifier").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Modifier le message").assertIsDisplayed()
        field().performTextClearance()
        type("Tu vas bien, Max ?")
        compose.onNodeWithContentDescription("Envoyer").performClick()
        waitUntil { e.store.message(friend.code, reply.id)?.text == "Tu vas bien, Max ?" }
        assertTrue(e.store.message(friend.code, reply.id)?.edited == true)
        waitFor("modifié")

        // Supprimer pour tout le monde.
        menuOf("Bonjour")
        compose.onNodeWithText("Supprimer").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Supprimer le message ?").assertIsDisplayed()
        compose.onNodeWithText("Supprimer pour tout le monde").performClick()
        waitUntil { e.store.message(friend.code, first.id)?.deleted == true }
        waitFor("Vous avez supprimé ce message")
        // Pour moi seulement : il disparaît.
        menuOf("Vous avez supprimé ce message")
        compose.onNodeWithText("Supprimer").performClick()
        compose.waitForIdle()
        assertTrue(gone("Supprimer pour tout le monde"), "déjà supprimé pour tous")
        compose.onNodeWithText("Supprimer pour moi").performClick()
        waitUntil { e.store.message(friend.code, first.id) == null }
    }

    @Test
    fun ephemeralMessages() {
        val (m, e, friend) = withFriend()
        show(m)
        compose.onNodeWithContentDescription("Plus d'options").performClick()
        compose.onNodeWithText("Messages éphémères").performClick()
        compose.onNodeWithText("24 heures").performClick()
        waitUntil { e.chat(friend.code)?.timer == 86_400L }
        waitFor("⏳ Vous avez activé les messages éphémères : 24 heures.")
        sendTyped(e, friend.code, "Ce message s'efface")
        val sent = e.messages(friend.code).last()
        val exp = assertNotNull(sent.exp)
        assertEquals(sent.at + 86_400_000, exp, 1.0)
        compose.onNodeWithContentDescription("Message éphémère").assertIsDisplayed()
    }

    @Test
    fun mentionSuggestionsInAGroup() {
        val (m, e, friend) = withFriend()
        val g = e.createGroup("Les amis", listOf(friend.code), "")
        m.openChat(g.id)
        show(m)
        compose.onNodeWithText("Les amis").assertIsDisplayed()
        compose.onNodeWithText("Vous, Max").assertIsDisplayed()
        type("Salut @Ma")
        compose.onNodeWithText("@Max").assertIsDisplayed()
        compose.onNodeWithText("@Max").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("Salut @Max ")).assertIsDisplayed()
        type("ça va ?")
        compose.onNodeWithContentDescription("Envoyer").performClick()
        waitUntil { e.messages(g.id).lastOrNull { it.kind == "text" }?.text == "Salut @Max ça va ?" }
        assertEquals(listOf(friend.code), e.messages(g.id).last().mentions)
    }

    @Test
    fun searchInTheChat() {
        val (m, e, friend) = withFriend()
        show(m)
        sendTyped(e, friend.code, "Bonjour")
        sendTyped(e, friend.code, "On se voit demain")
        compose.onNodeWithContentDescription("Plus d'options").performClick()
        compose.onNodeWithText("Rechercher").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Rechercher dans la discussion").performTextInput("demain")
        waitFor("1 message")
        compose.onNodeWithText("On se voit demain").assertIsDisplayed()
        assertTrue(gone("Bonjour"))
        compose.onNodeWithContentDescription("Rechercher dans la discussion").performTextClearance()
        compose.onNodeWithContentDescription("Rechercher dans la discussion").performTextInput("rien de tel")
        waitFor("Aucun résultat")
        compose.onNodeWithContentDescription("Fermer la recherche").performClick()
        waitFor("Bonjour")
    }

    @Test
    fun olderMessagesOnDemand() {
        val (m, e, friend) = withFriend()
        for (i in 1..310) e.send(friend.code, Content(text = "Message n° $i"))
        idle()
        assertTrue(e.recentMessages(friend.code, 300).more)
        show(m)
        // La discussion s'ouvre sur sa fin.
        waitFor("Message n° 310")
        assertTrue(gone("Message n° 1"))
        // En haut : « Messages plus anciens », pas le rappel du chiffrement.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        waitFor("Messages plus anciens")
        assertTrue(compose.onAllNodesWithText("Les messages sont chiffrés de bout en bout", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Messages plus anciens").performClick()
        compose.waitForIdle()
        // La lecture reste sur le message qui était en haut (le n° 11).
        waitFor("Message n° 11")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        waitFor("Les messages sont chiffrés de bout en bout", substring = true)
        waitFor("Message n° 1")
    }

    @Test
    fun theUnreadBannerAndMarkRead() {
        val (m, e, friend) = withFriend()
        for (i in 1..5) e.send(friend.code, Content(text = "Ligne $i"))
        e.updateChat(friend.code) { it.copy(unread = 2) }
        idle()
        show(m)
        waitFor("2 messages non lus")
        // Ouverte : lue.
        waitUntil { e.chat(friend.code)?.unread == 0 }
        compose.onNodeWithText("Ligne 5").assertIsDisplayed()
    }

    @Test
    fun theQuotedMessageIsReachedEvenWhenOlder() {
        val (m, e, friend) = withFriend()
        for (i in 1..310) e.send(friend.code, Content(text = "Message n° $i"))
        val oldest = e.messages(friend.code).first { it.text == "Message n° 1" }
        e.send(friend.code, Content(text = "Tu te souviens ?", reply = talka.kit.Reply(oldest.id, oldest.from, oldest.text)))
        idle()
        show(m)
        waitFor("Tu te souviens ?")
        // Seule la citation montre le n° 1 ; le toucher amène au message.
        assertEquals(1, compose.onAllNodesWithText("Message n° 1").fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Message n° 1")[0].performClick()
        // Le message cité, tout en haut de la discussion, est à l'écran (avec son voisin, le n° 2).
        waitFor("Message n° 2")
        compose.onAllNodesWithText("Message n° 1")[0].assertIsDisplayed()
        compose.onNodeWithText("Message n° 2").assertIsDisplayed()
    }

    @Test
    fun aPinnedMessageLeadsBackToIt() {
        val (m, e, friend) = withFriend()
        show(m)
        sendTyped(e, friend.code, "À épingler")
        menuOf("À épingler")
        compose.onNodeWithText("Épingler").performClick()
        waitUntil { e.chat(friend.code)?.pinMsg == e.messages(friend.code).last().id }
        compose.onNodeWithContentDescription("Désépingler").assertIsDisplayed()
        compose.onNodeWithContentDescription("Désépingler").performClick()
        waitUntil { e.chat(friend.code)?.pinMsg == "" }
        // L'étoile.
        menuOf("À épingler")
        compose.onNodeWithText("Important").performClick()
        waitUntil { e.messages(friend.code).last().isStarred }
        compose.onNodeWithContentDescription("Important").assertIsDisplayed()
    }

    @Test
    fun recordingStartsAndCancels() {
        val (m, _, _) = withFriend()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        show(m)
        compose.onNodeWithContentDescription("Message vocal").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Annuler l'enregistrement").assertIsDisplayed()
        compose.onNodeWithContentDescription("Envoyer le message vocal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Annuler l'enregistrement").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Message vocal").assertIsDisplayed()
    }

    /** Le micro enregistre comme l'iPhone : AAC dans du MP4, mono, 24 kHz, 32 kbit/s, 2 minutes au plus. */
    @Test
    fun voiceRecorderUsesTheIPhoneFormat() {
        val app = RuntimeEnvironment.getApplication()
        val r = MediaRecorder(app)
        VoiceRecorder.configure(r)
        val shadow = shadowOf(r)
        assertEquals(MediaRecorder.AudioSource.MIC, shadow.audioSource)
        assertEquals(MediaRecorder.OutputFormat.MPEG_4, shadow.outputFormat)
        assertEquals(MediaRecorder.AudioEncoder.AAC, shadow.audioEncoder)
        assertEquals(1, shadow.audioChannels)
        assertEquals(24_000, shadow.audioSamplingRate)
        assertEquals(32_000, shadow.audioEncodingBitRate)
        assertEquals(120_000, shadow.maxDuration)
        r.release()
        // Moins d'une demi-seconde : rien ne part.
        val rec = VoiceRecorder(app)
        assertTrue(rec.start())
        assertTrue(rec.recording)
        assertNull(rec.stop())
        assertFalse(rec.recording)
    }

    /** Ce qui part sur le fil pour un vocal : `voice`, `audio/mp4`, une adresse `data:audio/mp4`, la durée arrondie. */
    @Test
    fun aVoiceMessageGoesOutAsAacInMp4() {
        val (_, e, friend) = withFriend()
        val audio = ByteArray(2048) { (it % 251).toByte() }
        val content = VoiceRecorder.content(audio, 3.6)
        assertEquals("voice", content.kind)
        assertEquals("audio/mp4", content.mime)
        assertEquals(4.0, content.dur)
        assertEquals(2048.0, content.size)
        val sent = assertNotNull(e.send(friend.code, content))
        // Le faux relais reçoit l'enveloppe chiffrée pour Max : il l'ouvre.
        fun opened(): List<Body.Msg> =
            relay.received.toList()
                .filter { it["t"]?.jsonPrimitive?.contentOrNull == "send" && it["to"]?.jsonPrimitive?.contentOrNull == friend.code }
                .mapNotNull { o -> o["body"]?.jsonPrimitive?.contentOrNull?.let { TalkaCrypto.open(friend, e.me.code, e.me.dh, it) } }
                .mapNotNull { Wire.parse(it)?.body as? Body.Msg }
        waitUntil { opened().any { it.id == sent.id } }
        val m = opened().first { it.id == sent.id }
        assertEquals(sent.id, m.id)
        assertEquals("voice", m.content.kind)
        assertEquals("audio/mp4", m.content.mime)
        assertEquals(4.0, m.content.dur)
        assertTrue(m.content.data.startsWith("data:audio/mp4;base64,"))
        assertEquals(audio.toList(), android.util.Base64.decode(m.content.data.substringAfter(','), android.util.Base64.DEFAULT).toList())
    }

    /** La forme d'un vocal : le même calcul que l'iPhone (sur 32 bits non signés). */
    @Test
    fun voiceBarsMatchTheIPhone() {
        // `6 + (reduce(i) { $0 &* 31 &+ $1 } % 18)` sur les scalaires de « ab ».
        val expected = (0 until 3).map { i -> 6 + (((i.toLong() * 31 + 97) * 31 + 98) and 0xFFFFFFFFL).rem(18).toInt() }
        assertEquals(expected, (0 until 3).map { voiceBar("ab", it) })
        for (i in 0 until 24) assertTrue(voiceBar("un-identifiant-assez-long-🎙️", i) in 6..23)
    }
}
