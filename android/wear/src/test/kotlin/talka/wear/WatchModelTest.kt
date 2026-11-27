// Le modèle de la montre (`WatchModel.swift`) : le résumé reçu et gardé, ce
// qui part vers le téléphone (tout de suite, ou en file), les vocaux, les
// complications. Sans Play Services : un faux téléphone prend la place de
// `WearDataLayer`.

package talka.wear

import android.content.Context
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import talka.kit.WatchAction
import talka.kit.WatchChat
import talka.kit.WatchLink
import talka.kit.WatchMessage
import talka.kit.WatchSnapshot
import talka.kit.WidgetShare
import talka.kit.WidgetStorage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Un faux téléphone : ce que la montre lui envoie, et par quel chemin. */
class FakePhone(var near: Boolean = true, var delivers: Boolean = true) : PhonePort {
    var events: PhonePort.Events? = null

    /** Les messages `/talka/action` (tout de suite). */
    val sent = ArrayList<ByteArray>()

    /** Les actions en file (`/talka/queue/…`). */
    val queued = ArrayList<ByteArray>()

    /** Les vocaux (`/talka/voice/…`). */
    val files = ArrayList<Triple<File, Map<String, Any>, () -> Unit>>()

    override fun activate(events: PhonePort.Events) {
        this.events = events
    }

    override val reachable: Boolean get() = near

    override fun sendMessage(data: ByteArray, done: (Boolean) -> Unit) {
        sent.add(data)
        done(delivers)
    }

    override fun transferUserInfo(data: ByteArray) {
        queued.add(data)
    }

    override fun transferFile(file: File, metadata: Map<String, Any>, done: () -> Unit) {
        files.add(Triple(file, metadata, done))
    }

    fun actions(list: List<ByteArray> = sent): List<WatchAction> = list.map { assertNotNull(WatchLink.decode(WatchAction, it)) }
}

class MemoryStorage : WidgetStorage {
    var bytes: ByteArray? = null
    var writes = 0

    override fun read(): ByteArray? = bytes

    override fun write(data: ByteArray): Boolean {
        writes += 1
        bytes = data.copyOf()
        return true
    }

    override fun clear() {
        bytes = null
    }
}

/** Un résumé comme celui du téléphone : Léa (deux non lus), et un groupe. */
fun sampleSnapshot(hidden: Boolean? = null) = WatchSnapshot(
    name = "Max",
    online = true,
    hidden = hidden,
    chats = listOf(
        WatchChat(
            id = "WGSEAJNSZJ", title = "Léa", initials = "L", color = 0, unread = 2, at = 2000.0, last = "On se retrouve à 19 h ?",
            messages = listOf(
                WatchMessage(id = "m1", text = "Salut", at = 1000.0),
                WatchMessage(id = "m2", text = "On se retrouve à 19 h ?", at = 2000.0),
            ),
        ),
        WatchChat(
            id = "g:famille", title = "Famille", initials = "F", color = 3, group = true, at = 500.0, last = "Papa : 📷 Photo",
            messages = listOf(WatchMessage(id = "m3", author = "Papa", text = "📷 Photo", at = 500.0)),
        ),
    ),
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchModelTest {
    private val phone = FakePhone()
    private val storage = MemoryStorage()
    private var reloads = 0
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("talka.montre.test", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        WidgetShare.storage = storage
        WidgetShare.clear()
    }

    @After
    fun tearDown() {
        WidgetShare.clear()
        WidgetShare.storage = null
    }

    private fun model() = WatchModel(phone, prefs, reloadComplications = { reloads += 1 }, now = { 9000.0 })

    @Test
    fun theSnapshotIsReceivedKeptAndShown() {
        val m = model()
        assertNull(m.snapshot)
        assertNotNull(phone.events, "la liaison est branchée")
        assertNull(WidgetShare.read(), "rien encore pour les complications")

        // La liaison est prête : le dernier résumé laissé par le téléphone, puis un résumé à jour demandé.
        phone.events!!.activated(WatchLink.encode(sampleSnapshot()), reachable = true)
        assertTrue(m.reachable)
        assertEquals("Léa", m.chat("WGSEAJNSZJ")?.title)
        assertEquals(listOf(WatchAction.Kind.refresh), phone.actions().map { it.kind })
        val w = assertNotNull(WidgetShare.read())
        assertEquals(2, w.unread)
        assertEquals("WGSEAJNSZJ", w.chats.first().id)
        assertEquals(1, reloads)

        // Le même résumé : les complications ne sont pas redemandées.
        m.receive(WatchLink.encode(sampleSnapshot()))
        assertEquals(1, reloads)
        // N'importe quoi : ignoré.
        m.receive("pas un résumé".toByteArray())
        assertEquals("Léa", m.chat("WGSEAJNSZJ")?.title)

        // Gardé : la montre relancée, loin du téléphone, l'affiche encore.
        val again = WatchModel(FakePhone(near = false), prefs)
        assertEquals(sampleSnapshot(), again.snapshot)
    }

    @Test
    fun aLockCodeHidesTheComplications() {
        val m = model()
        m.receive(WatchLink.encode(sampleSnapshot(hidden = true)))
        val w = assertNotNull(WidgetShare.read())
        assertTrue(w.hidden)
        assertEquals(2, w.unread)
        assertTrue(w.chats.isEmpty())
        assertFalse(String(assertNotNull(storage.bytes)).contains("19 h"))
    }

    @Test
    fun sendingShowsTheMessageAtOnce() {
        val m = model()
        m.receive(WatchLink.encode(sampleSnapshot()))
        m.send("WGSEAJNSZJ", "  J'arrive !  ")
        val c = assertNotNull(m.chat("WGSEAJNSZJ"))
        val mine = c.messages.last()
        assertTrue(mine.mine)
        assertEquals("J'arrive !", mine.text)
        assertEquals("pending", mine.tick)
        assertTrue(mine.id.startsWith("montre-"))
        assertEquals("J'arrive !", c.last)
        assertEquals(9000.0, c.at)
        val a = phone.actions().single()
        assertEquals(WatchAction(WatchAction.Kind.send, chat = "WGSEAJNSZJ", text = "J'arrive !"), a)
        assertTrue(phone.queued.isEmpty())

        // Vide : rien.
        m.send("WGSEAJNSZJ", "   ")
        assertEquals(1, phone.sent.size)
    }

    @Test
    fun farFromThePhoneActionsWaitInTheQueue() {
        val m = model()
        m.receive(WatchLink.encode(sampleSnapshot()))
        phone.near = false
        m.send("WGSEAJNSZJ", "Oui")
        m.react("WGSEAJNSZJ", "m2", "❤️")
        m.refresh()
        assertTrue(phone.sent.isEmpty())
        assertEquals(
            listOf(
                WatchAction(WatchAction.Kind.send, chat = "WGSEAJNSZJ", text = "Oui"),
                WatchAction(WatchAction.Kind.react, chat = "WGSEAJNSZJ", id = "m2", text = "❤️"),
            ),
            phone.actions(phone.queued),
            "dans l'ordre ; une demande de résumé ne se met pas en file",
        )

        // Joignable, mais le message ne part pas : en file aussi.
        phone.near = true
        phone.delivers = false
        m.send("WGSEAJNSZJ", "Non")
        m.refresh()
        assertEquals(2, phone.sent.size)
        assertEquals(3, phone.queued.size)
        assertEquals("Non", phone.actions(phone.queued).last().text)

        // Le téléphone revient : un résumé à jour est demandé.
        phone.delivers = true
        phone.events!!.reachabilityChanged(false)
        assertFalse(m.reachable)
        phone.events!!.reachabilityChanged(true)
        assertTrue(m.reachable)
        assertEquals(WatchAction.Kind.refresh, phone.actions().last().kind)
    }

    @Test
    fun markingAsRead() {
        val m = model()
        m.receive(WatchLink.encode(sampleSnapshot()))
        m.markRead("WGSEAJNSZJ")
        assertEquals(0, m.chat("WGSEAJNSZJ")?.unread)
        assertEquals(0, WidgetShare.read()?.unread, "les complications suivent tout de suite")
        assertEquals(WatchAction(WatchAction.Kind.read, chat = "WGSEAJNSZJ"), phone.actions().single())
        // Déjà lu : rien ne repart.
        m.markRead("WGSEAJNSZJ")
        m.markRead("g:famille")
        m.markRead("INCONNU")
        assertEquals(1, phone.sent.size)
    }

    @Test
    fun aVoiceMessage() {
        val m = model()
        m.receive(WatchLink.encode(sampleSnapshot()))
        val file = File.createTempFile("vocal", ".m4a").apply { writeBytes(ByteArray(100)) }
        m.sendVoice("g:famille", file, 7.4)
        val c = assertNotNull(m.chat("g:famille"))
        assertEquals("🎙️ Message vocal (0:07)", c.messages.last().text)
        assertEquals("pending", c.messages.last().tick)
        assertEquals("🎙️ Message vocal (0:07)", c.last)
        val (sentFile, metadata, done) = phone.files.single()
        assertEquals(file, sentFile)
        assertEquals(WatchLink.Voice("g:famille", 7.4), WatchLink.voice(metadata))
        assertTrue(file.exists())
        done()
        assertFalse(file.exists(), "parti : le fichier est effacé")
        assertTrue(phone.sent.isEmpty(), "un vocal passe en fichier, pas en message")
    }

    @Test
    fun voiceDurations() {
        assertEquals("0:00", voiceDuration(0.2))
        assertEquals("0:07", voiceDuration(7.4))
        assertEquals("0:08", voiceDuration(7.5))
        assertEquals("1:05", voiceDuration(65.0))
        assertEquals("2:00", voiceDuration(120.0))
        assertEquals("0:00", voiceDuration(-3.0))
    }
}
