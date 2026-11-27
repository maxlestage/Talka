// La fiche d'un contact et d'un groupe, les appareils liés (le QR code
// dessiné puis relu, l'appairage de deux appareils), les clés Brocolis et
// les actus, dessinés pour de vrai par Compose (sous Robolectric), contre
// un faux relais local.

package talka.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import talka.app.ui.BrocolisKeysView
import talka.app.ui.ContactInfoView
import talka.app.ui.GroupInfoView
import talka.app.ui.LinkWaitView
import talka.app.ui.LinkedDevicesView
import talka.app.ui.QR
import talka.app.ui.QrImage
import talka.app.ui.StatusTab
import talka.app.ui.safetyNumber
import talka.kit.Brocolis
import talka.kit.Identity
import talka.kit.Socket
import talka.kit.SocketError
import talka.kit.SocketOpener
import talka.kit.StatusItem
import talka.kit.TalkaCode
import talka.kit.TalkaCrypto
import talka.kit.TalkaV4
import talka.kit.linkDevice
import talka.kit.nowMs
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json as KJson

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest : AppTestBase() {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = install()

    @After
    fun tearDown() = uninstall()

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

    private fun none(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()

    /** Fait défiler la liste jusqu'au texte, puis le rend. */
    private fun scrollTo(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

    /** Le temps arrêté (`autoAdvance` faux) : quelques images, et le fil principal. */
    private fun tick() {
        repeat(6) {
            compose.mainClock.advanceTimeByFrame()
            idle()
        }
    }

    /**
     * La fenêtre du dessus (l'écran, ou la feuille ouverte par-dessus),
     * dessinée en logiciel : `captureToImage` attend sous Robolectric une
     * image qui ne vient pas.
     */
    private fun topWindow(): Bitmap {
        compose.waitForIdle()
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val field = global.getDeclaredField("mViews").apply { isAccessible = true }
        val view = (field.get(instance) as List<*>).filterIsInstance<View>().last { it.isAttachedToWindow && it.width > 0 }
        val out = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            view.draw(this)
        }
        return out
    }

    private fun account(name: String = "Léa"): AppModel = model().also { it.createAccount(name, "") }

    /** Un contact enregistré, dont le faux relais connaît les clés. */
    private fun friend(m: AppModel, name: String): Identity {
        val f = Identity.create()
        relay.known[f.code] = f
        run(m) { m.engine!!.addContact(f.code, name) }
        return f
    }

    // ----- La fiche d'un contact -----

    @Test
    fun contactInfoShowsTheSafetyNumberBlocksAndMutes() {
        val m = account()
        val e = m.engine!!
        val max = friend(m, "Max")
        e.ensureDirectChat(max.code)
        compose.setContent { TalkaTheme(m) { ContactInfoView(max.code, onBack = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Infos du contact").assertIsDisplayed()
        compose.onNodeWithText(TalkaCode.pretty(max.code)).assertIsDisplayed()
        compose.onNodeWithText("Audio").assertIsDisplayed()

        // Le numéro de sécurité : le même que celui que calcule l'autre côté.
        val seenByMax = TalkaCrypto.safetyNumber(
            TalkaCrypto.Keys(max.code, max.sign, max.dh),
            TalkaCrypto.Keys(e.me.code, e.me.sign, e.me.dh),
        )
        assertEquals(seenByMax, safetyNumber(e, max.code))
        assertEquals(12, seenByMax.split(" ").size)
        scrollTo("Chiffrement").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Numéro de sécurité").assertIsDisplayed()
        compose.onNodeWithContentDescription("Numéro de sécurité : $seenByMax").assertIsDisplayed()
        compose.onNodeWithText(seenByMax.split(" ").first()).assertIsDisplayed()
        // Son QR code porte le numéro.
        val qr = topWindow()
        assertEquals("talka-safety:$seenByMax", QR.decode(qr))
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.waitForIdle()

        // La sourdine : 8 heures, puis le son réactivé.
        scrollTo("Silence").performClick()
        compose.onNodeWithText("8 heures").performClick()
        compose.waitForIdle()
        val chat = e.chat(max.code)!!
        assertTrue(chat.muted)
        val until = assertNotNull(chat.muteUntil)
        assertTrue(kotlin.math.abs(until - (nowMs() + 8 * 3600_000.0)) < 60_000)
        compose.onAllNodesWithText("Jusqu'à", substring = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        scrollTo("Silence").performClick()
        compose.onNodeWithText("Réactiver le son").performClick()
        compose.waitForIdle()
        assertFalse(e.chat(max.code)!!.muted)

        // Le blocage, puis le déblocage.
        scrollTo("Bloquer Max").performClick()
        compose.waitForIdle()
        assertTrue(e.blocked(max.code))
        scrollTo("Débloquer Max").assertIsDisplayed()
        compose.onNodeWithText("Débloquer Max").performClick()
        compose.waitForIdle()
        assertFalse(e.blocked(max.code))

        // Le nom donné au contact, gardé quand la fiche se ferme.
        scrollTo("Nom du contact").assert(hasText("Max"))
    }

    @Test
    fun contactNameIsSavedWhenTheInfoCloses() {
        val m = account()
        val e = m.engine!!
        val max = friend(m, "Max")
        val shown = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { TalkaTheme(m) { if (shown.value) ContactInfoView(max.code, onBack = { shown.value = false }) } }
        compose.waitForIdle()
        compose.onNodeWithText("Nom du contact").performTextReplacement("Maxime")
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.waitForIdle()
        assertEquals("Maxime", e.contact(max.code)?.name)
    }

    // ----- La fiche d'un groupe -----

    @Test
    fun groupInfoListsMembersAndManagesAdmins() {
        val m = account()
        val e = m.engine!!
        val bob = friend(m, "Bob")
        val zoe = friend(m, "Zoé")
        val g = e.createGroup("Amis", listOf(bob.code, zoe.code), "")
        compose.setContent { TalkaTheme(m) { GroupInfoView(g.id, onBack = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Infos du groupe").assertIsDisplayed()
        compose.onNodeWithText("Groupe · 3 membres").assertIsDisplayed()
        scrollTo("3 membres")
        scrollTo("Vous").assertIsDisplayed()
        scrollTo("Bob").assertIsDisplayed()
        scrollTo("Zoé").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("Admin").fetchSemanticsNodes().size)

        // « Seuls les admins peuvent écrire ».
        scrollTo("Seuls les admins peuvent écrire").performClick()
        compose.waitForIdle()
        assertTrue(e.chat(g.id)!!.adminsOnly)

        // La description.
        scrollTo("Enregistrer la description").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Description").performTextInput("Les amis du jeudi")
        compose.onNodeWithText("Enregistrer la description").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals("Les amis du jeudi", e.chat(g.id)!!.desc)

        // Bob nommé admin.
        scrollTo("Bob").performClick()
        compose.onNodeWithText("Nommer admin").performClick()
        compose.waitForIdle()
        assertTrue(bob.code in e.chat(g.id)!!.admins)
        scrollTo("Zoé")
        assertEquals(2, compose.onAllNodesWithText("Admin").fetchSemanticsNodes().size)

        // Zoé retirée du groupe.
        compose.onNodeWithText("Zoé").performClick()
        compose.onNodeWithText("Retirer du groupe").performClick()
        compose.waitForIdle()
        val after = e.chat(g.id)!!
        assertEquals(listOf(e.me.code, bob.code), after.members)
        scrollTo("Groupe · 2 membres").assertIsDisplayed()

        // Quitter le groupe.
        scrollTo("Quitter le groupe").performClick()
        compose.onAllNodesWithText("Quitter le groupe").fetchSemanticsNodes().let { assertEquals(2, it.size) }
        compose.onAllNodesWithText("Quitter le groupe")[1].performClick()
        compose.waitForIdle()
        assertTrue(e.chat(g.id)!!.left)
        scrollTo("Supprimer le groupe").assertIsDisplayed()
    }

    @Test
    fun aMemberSeesTheGroupWithoutTheAdminRows() {
        val m = account()
        val e = m.engine!!
        val bob = friend(m, "Bob")
        val g = e.createGroup("Amis", listOf(bob.code), "")
        // Bob seul admin : nous ne sommes plus qu'un membre.
        e.updateGroup(g.id, "") { it.copy(admins = listOf(bob.code), desc = "Le groupe de Bob") }
        compose.setContent { TalkaTheme(m) { GroupInfoView(g.id, onBack = {}) } }
        compose.waitForIdle()
        assertTrue(none("Ajouter des membres"))
        assertTrue(none("Seuls les admins peuvent écrire"))
        compose.onNodeWithText("Le groupe de Bob").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithContentDescription("Changer la photo du groupe").fetchSemanticsNodes().isEmpty())
    }

    // ----- Les appareils et le QR code -----

    @Test
    fun aQrCodeDrawnOnScreenIsReadBack() {
        val text = "talka-lien:" + "AbC-_09".repeat(12)
        compose.setContent { QrImage(text, Modifier.size(300.dp), label = "QR à relire") }
        compose.waitForIdle()
        val shot = topWindow()
        assertEquals(text, QR.decode(shot))
        // Et par le chemin de la caméra : la luminance seule (le plan Y), avec une marge de ligne.
        val b = assertNotNull(QR.bitmap("https://relais.test/c/K7Q2MX9PRT", scale = 6))
        val stride = b.width + 16
        val y = ByteArray(stride * b.height)
        for (row in 0 until b.height) for (col in 0 until b.width) {
            y[row * stride + col] = if (b.getPixel(col, row) == android.graphics.Color.BLACK) 0 else 255.toByte()
        }
        assertEquals("https://relais.test/c/K7Q2MX9PRT", QR.decode(y, b.width, b.height, stride))
        assertNull(QR.decode(ByteArray(100 * 100) { 127 }, 100, 100))
    }

    @Test
    fun linkedDevicesListThisDeviceAndRenameIt() {
        val m = account()
        val e = m.engine!!
        compose.setContent { TalkaTheme(m) { LinkedDevicesView(onBack = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Appareils liés").assertIsDisplayed()
        compose.onNodeWithText("Pixel de test").assertIsDisplayed()
        compose.onNodeWithText("Cet appareil").assertIsDisplayed()
        compose.onNodeWithText("Renommer").performClick()
        compose.onNodeWithText("Nom").performTextReplacement("Mon Pixel")
        compose.onNodeWithText("Enregistrer").performClick()
        compose.waitForIdle()
        assertEquals("Mon Pixel", e.devices.list.first { it.dev == e.dev }.name)
        // Lier un appareil : « Lier » attend un vrai code de liaison.
        compose.onNodeWithText("Scanner son QR code").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Lier").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Code de liaison").performTextInput("pas un code")
        compose.onNodeWithText("Lier").assertIsNotEnabled()
        val offer = TalkaV4.LinkSecret().offer.text
        compose.onNodeWithContentDescription("Code de liaison").performTextReplacement(offer)
        compose.onNodeWithText("Lier").assertIsEnabled()
    }

    /**
     * Deux appareils : le nouveau montre son QR code (`LinkWaitView`), qu'on
     * relit sur l'écran ; l'appareil du compte le lie (par le faux relais des
     * boîtes) ; les deux affichent le même code de vérification, et le
     * nouveau démarre sur le même compte.
     */
    @Test
    fun twoDevicesPairThroughTheQrCode() {
        val boxes = BoxRelay()
        val first = model(sockets = boxes)
        first.createAccount("Léa", "")
        val e = assertNotNull(first.engine)
        val other = Files.createTempDirectory("talka-autre").toFile()
        val fresh = model(base = other, sockets = boxes)
        assertNull(fresh.engine)
        var closed = false
        compose.setContent { TalkaTheme(fresh) { if (!closed) LinkWaitView(onDismiss = { closed = true }) } }
        waitUntil { compose.onAllNodesWithContentDescription("QR code de liaison").fetchSemanticsNodes().isNotEmpty() && boxes.listening() }
        compose.onNodeWithText("En attente de l'autre appareil…").assertIsDisplayed()
        val shot = topWindow()
        val text = assertNotNull(QR.decode(shot))
        assertTrue(text.startsWith(TalkaV4.linkPrefix))

        waitUntil { e.netState == talka.kit.Relay.State.online && e.relay.sealed }
        val code = run(first) { e.linkDevice(text) }
        assertEquals(2, e.devices.list.size)
        waitFor("Appareil lié")
        compose.onNodeWithText(code).assertIsDisplayed()
        compose.onNodeWithText("Commencer").performClick()
        waitUntil { fresh.engine != null }
        assertTrue(closed)
        assertEquals(e.me.code, fresh.engine!!.me.code)
        assertEquals("Léa", fresh.engine!!.profile.name)
        other.deleteRecursively()
    }

    // ----- Brocolis -----

    @Test
    fun brocolisKeysAreCreatedRenamedRenewedAndForgotten() {
        val m = account()
        val e = m.engine!!
        compose.setContent { TalkaTheme(m) { BrocolisKeysView(onBack = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Aucune clé pour l'instant.").assertIsDisplayed()

        // Créer : son nom tout de suite.
        compose.onNodeWithText("Créer une clé").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Renommer la clé").assertIsDisplayed()
        compose.onNodeWithText("Nom").performTextInput("Maman")
        compose.onNodeWithText("Enregistrer").performClick()
        compose.waitForIdle()
        val k = e.brocolisKeys.single()
        assertEquals("Maman", k.name)
        compose.onNodeWithText("Maman").assertIsDisplayed()
        val fp = assertNotNull(Brocolis.fingerprint(k.key))
        compose.onNodeWithContentDescription("Empreinte $fp").assertIsDisplayed()

        // Ajouter une clé reçue.
        val received = Brocolis.generateKey()
        compose.onNodeWithText("Ajouter une clé").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("La clé, ou toute l'invitation reçue").performTextInput(received)
        compose.onNodeWithText("Nom (par exemple : Maman, Travail)").performTextInput("Travail")
        compose.onNodeWithText("Ajouter").performClick()
        waitUntil { e.brocolisKeys.size == 2 }
        assertEquals(received, e.brocolisKeys.first { it.name == "Travail" }.key)

        // Renouveler : une neuve, l'ancienne gardée pour l'historique.
        compose.onNodeWithContentDescription("Plus d'options pour « Maman »").performClick()
        compose.onNodeWithText("Renouveler").performClick()
        compose.onNodeWithText("Renouveler cette clé ?").assertIsDisplayed()
        compose.onNodeWithText("Renouveler").performClick()
        compose.waitForIdle()
        assertTrue(e.brocolisKeys.first { it.id == k.id }.retired)
        assertEquals(3, e.brocolisKeys.size)
        compose.onNodeWithText("Maman (ancienne)").assertIsDisplayed()

        // Oublier.
        compose.onNodeWithContentDescription("Plus d'options pour « Travail »").performClick()
        compose.onNodeWithText("Oublier").performClick()
        compose.onNodeWithText("Oublier cette clé ?").assertIsDisplayed()
        compose.onAllNodesWithText("Oublier")[0].performClick()
        compose.waitForIdle()
        assertTrue(e.brocolisKeys.none { it.name == "Travail" })
    }

    @Test
    fun aProtectedBrocolisKeyNeedsItsPhrase() {
        val m = account()
        val e = m.engine!!
        val key = Brocolis.generateKey()
        val token = assertNotNull(Brocolis.protectKey(key, "une phrase sûre"))
        compose.setContent { TalkaTheme(m) { BrocolisKeysView(onBack = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Ajouter une clé").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("La clé, ou toute l'invitation reçue").performTextInput(token)
        compose.onNodeWithText("Phrase secrète").performTextInput("mauvaise phrase")
        compose.onNodeWithText("Ajouter").performClick()
        waitFor("Phrase incorrecte, ou clé abîmée.")
        compose.onNode(hasText("Phrase secrète")).performTextReplacement("une phrase sûre")
        compose.onNodeWithText("Ajouter").performClick()
        waitUntil { e.brocolisKeys.size == 1 }
        assertEquals(key, e.brocolisKeys.single().key)
    }

    // ----- Les actus -----

    @Test
    fun statusIsPostedSeenAndDeleted() {
        val m = account()
        val e = m.engine!!
        compose.setContent { TalkaTheme(m) { StatusTab() } }
        compose.waitForIdle()
        compose.onNodeWithText("Touchez pour publier").assertIsDisplayed()
        compose.onNodeWithText("Les actus de vos contacts apparaîtront ici. Elles disparaissent au bout de 24 heures.").assertIsDisplayed()

        // Publier un texte.
        compose.onNodeWithText("Mon actu").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Écrivez une actu").performTextInput("Bonjour à tous")
        compose.onNodeWithContentDescription("Changer la couleur").performClick()
        compose.onNodeWithContentDescription("Publier").performClick()
        compose.waitForIdle()
        val mine = e.statuses().single()
        assertEquals("Bonjour à tous", mine.text)
        assertEquals("text", mine.kind)
        assertEquals("#5e47de", mine.bg)
        waitFor("0 vue(s)", substring = true)

        // La voir, puis la supprimer (le temps arrêté : elle ne file pas).
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Mon actu").performClick()
        tick()
        compose.onNodeWithText("Bonjour à tous").assertIsDisplayed()
        compose.onNodeWithContentDescription("Vue par 0").assertIsDisplayed()
        compose.onNodeWithContentDescription("Supprimer").performClick()
        compose.mainClock.advanceTimeBy(200)
        idle()
        assertTrue(e.statuses().isEmpty())
        compose.mainClock.autoAdvance = true
        waitFor("Touchez pour publier")
    }

    @Test
    fun statusesOfContactsAreSeenAndExpireAfter24Hours() {
        val m = account()
        val e = m.engine!!
        val max = friend(m, "Max")
        val zoe = friend(m, "Zoé")
        val now = nowMs()
        e.store.write("statuses", "${max.code}|a", StatusItem(max.code, "a", "text", "Coucou", "#1f7aec", "", now - 3600_000.0))
        // Plus de 24 heures : partie.
        e.store.write("statuses", "${zoe.code}|b", StatusItem(zoe.code, "b", "text", "Trop vieux", "", "", now - 25 * 3600_000.0))
        // La nôtre, vue par Max.
        e.store.write("statuses", "${e.me.code}|c", StatusItem(e.me.code, "c", "text", "Moi", "", "", now - 60_000.0, seen = true, viewers = mapOf(max.code to now)))
        assertEquals(listOf("a", "c"), e.statuses().map { it.id }.sorted())

        compose.setContent { TalkaTheme(m) { StatusTab() } }
        compose.waitForIdle()
        compose.onNodeWithText("Récentes").assertIsDisplayed()
        compose.onNodeWithText("Max").assertIsDisplayed()
        assertTrue(none("Zoé"))
        compose.onAllNodesWithText("1 vue(s)", substring = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }

        // L'actu de Max vue : elle passe dans « Vues ».
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Max").performClick()
        tick()
        compose.onNodeWithText("Coucou").assertIsDisplayed()
        assertTrue(e.statuses().first { it.id == "a" }.seen)
        // Cinq secondes plus tard, elle se ferme d'elle-même.
        compose.mainClock.advanceTimeBy(6_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        waitFor("Vues")
        assertTrue(none("Coucou"))
        assertTrue(none("Récentes"))

        // Masquer ses actus (appui long) : sous « Masquées ».
        compose.onNodeWithText("Max").performTouchInputLongClick()
        compose.onNodeWithText("Masquer ses actus").performClick()
        compose.waitForIdle()
        assertTrue(e.contact(max.code)!!.statusMuted)
        waitFor("Masquées")
    }

    private fun SemanticsNodeInteraction.performTouchInputLongClick() =
        performTouchInput { longClick() }

    private fun waitFor(text: String, substring: Boolean) =
        waitUntil { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
}

/**
 * Un faux relais qui porte aussi les boîtes (v4) : les connexions
 * anonymes, les boîtes suivies (`boites`), et ce qu'on y dépose (`send`
 * avec `boite`), remis à qui les suit — de quoi lier deux appareils.
 */
class BoxRelay : SocketOpener {
    private val conns = CopyOnWriteArrayList<Conn>()
    private val waiting = CopyOnWriteArrayList<JsonObject>()
    private var n = 0

    /** Quelqu'un suit-il une boîte ? */
    fun listening(): Boolean = conns.any { it.boxes.isNotEmpty() }

    override fun open(url: String): Socket = Conn().also { conns.add(it) }

    private inner class Conn : Socket {
        private val inbox = Channel<String>(Channel.UNLIMITED)

        @Volatile var boxes: List<String> = emptyList()

        init {
            reply(buildJsonObject { put("t", "challenge"); put("nonce", "nonce-de-test") })
        }

        fun reply(o: JsonObject) {
            inbox.trySend(o.toString())
        }

        override suspend fun send(text: String) {
            val msg = KJson.parseToJsonElement(text) as? JsonObject ?: return
            when (msg["t"]?.jsonPrimitive?.contentOrNull) {
                "hello" -> reply(buildJsonObject { put("t", "ready"); put("sealed", true) })
                "anon" -> reply(buildJsonObject { put("t", "ready"); put("boites", true) })
                "ping" -> reply(buildJsonObject { put("t", "pong") })
                "boites" -> {
                    boxes = msg["list"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    for (w in waiting.toList()) if (deliver(w)) waiting.remove(w)
                }
                "lookup" -> reply(buildJsonObject { put("t", "keys"); put("code", msg["code"] ?: JsonPrimitive("")); put("unknown", true) })
                "publish-bundle" -> reply(buildJsonObject { put("t", "published"); put("ok", true) })
                "fetch-bundle" -> reply(buildJsonObject { put("t", "bundle"); put("code", msg["code"] ?: JsonPrimitive("")) })
                "send" -> {
                    msg["id"]?.let { id -> reply(buildJsonObject { put("t", "ack"); put("id", id) }) }
                    val boite = msg["boite"]?.jsonPrimitive?.contentOrNull ?: return
                    val env = buildJsonObject {
                        put("t", "msg")
                        put("n", synchronized(this@BoxRelay) { ++n })
                        put("id", msg["id"] ?: JsonPrimitive(""))
                        put("boite", boite)
                        put("body", msg["body"] ?: JsonPrimitive(""))
                        put("ts", nowMs())
                        put("sealed", true)
                    }
                    if (!deliver(env)) waiting.add(env)
                }
                else -> {}
            }
        }

        override suspend fun receive(): String =
            try {
                inbox.receive()
            } catch (e: Exception) {
                throw SocketError("closed", e)
            }

        override fun close() {
            boxes = emptyList()
            conns.remove(this)
            inbox.close()
        }
    }

    private fun deliver(env: JsonObject): Boolean {
        val boite = env["boite"]?.jsonPrimitive?.contentOrNull
        val to = conns.filter { boite in it.boxes }
        to.forEach { it.reply(env) }
        return to.isNotEmpty()
    }
}
