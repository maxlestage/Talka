// Le pont avec la montre, côté téléphone (`WatchBridge.swift`) : ce qui
// part vers la montre et ce qui en revient, tel que la couche de données de
// Wear OS le transporte (les chemins et les octets de `WearProtocol`), sans
// Play Services : une fausse liaison prend la place de `WearDataLayer`.

package talka.app.watch

import com.google.android.gms.wearable.DataMap
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import talka.app.AppTestBase
import talka.app.widgets.MemoryWidgetStorage
import talka.kit.Content
import talka.kit.WatchAction
import talka.kit.WatchLink
import talka.kit.WatchSnapshot
import talka.kit.WidgetShare
import talka.link.WearProtocol
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Une fausse montre : ce que le pont lui envoie, par quel chemin. */
class FakeWatch(var ready: Boolean = true, var near: Boolean = true) : WatchPort {
    var changed: (() -> Unit)? = null

    /** Le dernier résumé (`/talka/snapshot`, élément de données). */
    val contexts = ArrayList<ByteArray>()

    /** Les messages `/talka/snapshot` aux montres joignables. */
    val messages = ArrayList<ByteArray>()

    /** Les réponses (nœud, résumé). */
    val replies = ArrayList<Pair<String, ByteArray>>()

    override fun activate(changed: () -> Unit) {
        this.changed = changed
    }

    override val watchReady: Boolean get() = ready
    override val reachable: Boolean get() = near

    override fun updateContext(data: ByteArray) {
        contexts.add(data)
    }

    override fun sendMessage(data: ByteArray) {
        messages.add(data)
    }

    override fun reply(node: String, data: ByteArray) {
        replies.add(node to data)
    }

    fun lastContext(): WatchSnapshot = assertNotNull(WatchLink.decode(WatchSnapshot, contexts.lastOrNull()))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchBridgeTest : AppTestBase() {
    private val watch = FakeWatch()

    @Before
    fun setUp() {
        install()
        WidgetShare.storage = MemoryWidgetStorage()
        WidgetShare.clear()
    }

    @After
    fun tearDown() {
        uninstall()
        WidgetShare.clear()
        WidgetShare.storage = null
    }

    private fun account(): Pair<talka.app.AppModel, talka.kit.Engine> {
        val m = model(watch = watch)
        m.createAccount("Léa", "")
        idle()
        return m to assertNotNull(m.engine)
    }

    private fun action(kind: WatchAction.Kind, chat: String = "", id: String = "", text: String = "") =
        WatchLink.encode(WatchAction(kind, chat, id, text))

    @Test
    fun theProtocolPaths() {
        assertEquals("/talka/snapshot", WearProtocol.snapshotPath)
        assertEquals("/talka/action", WearProtocol.actionPath)
        assertEquals(WearProtocol.Kind.snapshot, WearProtocol.kind("/talka/snapshot"))
        assertEquals(WearProtocol.Kind.action, WearProtocol.kind("/talka/action"))
        assertEquals(WearProtocol.Kind.queued, WearProtocol.kind(WearProtocol.queuePath()))
        assertEquals(WearProtocol.Kind.voice, WearProtocol.kind(WearProtocol.voicePath()))
        assertEquals(WearProtocol.Kind.other, WearProtocol.kind("/talka/queue/"))
        assertEquals(WearProtocol.Kind.other, WearProtocol.kind("/autre"))
        // Deux actions de la même milliseconde gardent leur ordre.
        val a = WearProtocol.queuePath(WearProtocol.nextOrder(1000), "b")
        val b = WearProtocol.queuePath(WearProtocol.nextOrder(1000), "a")
        assertTrue(a < b)
        assertEquals(listOf(a, b), WearProtocol.inOrder(listOf(b, a)) { it })
    }

    @Test
    fun theSnapshotGoesToTheWatchWhenItChanges() {
        val (m, e) = account()
        assertNotNull(watch.changed, "la liaison est branchée au lancement")
        // La liaison est prête : le résumé part (élément de données, et message : la montre est là).
        watch.changed!!()
        assertEquals(1, watch.contexts.size)
        assertEquals(1, watch.messages.size)
        assertContentEquals(watch.contexts[0], watch.messages[0])
        assertEquals("Léa", watch.lastContext().name)
        assertEquals(false, watch.lastContext().hidden)

        // Rien de nouveau : rien ne repart.
        m.watch.push()
        assertEquals(1, watch.contexts.size)

        val c = e.ensureDirectChat("WGSEAJNSZJ")
        e.send(c.id, Content(text = "Bonjour"))
        m.watch.push()
        assertEquals(2, watch.contexts.size)
        assertEquals("Bonjour", watch.lastContext().chats.single().last)

        // Hors de portée : seulement le dernier résumé, que la montre lira plus tard.
        watch.near = false
        e.send(c.id, Content(text = "Tu es là ?"))
        m.watch.push()
        assertEquals(3, watch.contexts.size)
        assertEquals(2, watch.messages.size)

        // Pas de montre (ou pas l'application) : rien du tout, mais les widgets suivent.
        watch.ready = false
        e.send(c.id, Content(text = "Encore"))
        m.watch.push()
        assertEquals(3, watch.contexts.size)
        assertEquals("Encore", WidgetShare.read()?.chats?.first()?.last)
    }

    @Test
    fun aLockCodeHidesTheWatchComplications() {
        val (m, _) = account()
        run(m) { m.setPin("482916") }
        m.watch.push(force = true)
        assertEquals(true, watch.lastContext().hidden)
    }

    @Test
    fun anActionFromTheWatchIsDoneAndAnswered() {
        val (m, e) = account()
        val c = e.ensureDirectChat("WGSEAJNSZJ")
        m.watch.message(action(WatchAction.Kind.send, chat = c.id, text = "  J'arrive !  "), "montre-1")
        idle()
        assertEquals("J'arrive !", e.messages(c.id).last().text)
        val (node, data) = watch.replies.single()
        assertEquals("montre-1", node)
        val reply = assertNotNull(WatchLink.decode(WatchSnapshot, data))
        assertEquals("J'arrive !", reply.chats.single().messages.last().text)
        assertTrue(reply.chats.single().messages.last().mine)
        assertContentEquals(data, watch.contexts.last(), "le dernier résumé suit")
        assertContentEquals(data, m.watch.sent)

        // Une réaction, puis la même : retirée.
        val id = e.messages(c.id).last().id
        m.watch.message(action(WatchAction.Kind.react, chat = c.id, id = id, text = "👍"), "montre-1")
        assertEquals("👍", WatchLink.decode(WatchSnapshot, watch.replies.last().second)?.chats?.single()?.messages?.last()?.myReaction)
        m.watch.message(action(WatchAction.Kind.react, chat = c.id, id = id, text = "👍"), "montre-1")
        assertEquals("", WatchLink.decode(WatchSnapshot, watch.replies.last().second)?.chats?.single()?.messages?.last()?.myReaction)

        // Une demande de résumé ; et n'importe quoi : le résumé quand même.
        m.watch.message(action(WatchAction.Kind.refresh), "montre-2")
        assertEquals("montre-2", watch.replies.last().first)
        m.watch.message("pas une action".toByteArray(), "montre-2")
        assertNotNull(WatchLink.decode(WatchSnapshot, watch.replies.last().second))
    }

    @Test
    fun queuedActionsAreDoneInOrder() {
        val (m, e) = account()
        val c = e.ensureDirectChat("WGSEAJNSZJ")
        e.send(c.id, Content(text = "Bonjour"))
        e.updateChat(c.id) { it.copy(unread = 3) }
        m.watch.updateWidgets()
        assertEquals(3, WidgetShare.read()?.unread)

        val first = WearProtocol.queuePath()
        val second = WearProtocol.queuePath()
        val third = WearProtocol.queuePath()
        // Livrées dans le désordre : exécutées dans l'ordre où la montre les a faites.
        m.watch.queued(
            listOf(
                third to action(WatchAction.Kind.read, chat = c.id),
                second to action(WatchAction.Kind.send, chat = c.id, text = "deux"),
                first to action(WatchAction.Kind.send, chat = c.id, text = "un"),
            ),
        )
        assertEquals(listOf("Bonjour", "un", "deux"), e.messages(c.id).map { it.text })
        assertEquals(0, e.chat(c.id)?.unread)
        assertEquals(0, WidgetShare.read()?.unread, "les widgets suivent")
        assertTrue(watch.replies.isEmpty(), "pas de réponse : la montre n'attend rien")
    }

    @Test
    fun aVoiceMessageFromTheWatch() {
        val (m, e) = account()
        val c = e.ensureDirectChat("WGSEAJNSZJ")
        m.watch.push(force = true)
        val before = watch.messages.size

        // Les métadonnées, telles que l'élément de données les transporte.
        val map = DataMap()
        WearProtocol.putMetadata(map, WatchLink.voiceMetadata(c.id, 3.4))
        val metadata = WearProtocol.metadata(map)
        assertEquals(WatchLink.Voice(c.id, 3.4), WatchLink.voice(metadata))

        m.watch.voice(metadata, ByteArray(4000) { it.toByte() })
        val sent = e.messages(c.id).last()
        assertEquals("voice", sent.kind)
        assertEquals(3.0, sent.dur)
        assertEquals("audio/mp4", sent.mime)
        assertEquals(before + 1, watch.messages.size, "la montre, à l'écran, reçoit le résumé")
        assertTrue(WatchLink.decode(WatchSnapshot, watch.messages.last())!!.chats.single().last.isNotEmpty())

        // Trop court, ou pas un vocal : rien n'est envoyé.
        val count = e.messages(c.id).size
        m.watch.voice(metadata.plus("dur" to 0.2), ByteArray(10))
        m.watch.voice(mapOf("kind" to "autre", "chat" to c.id, "dur" to 2.0), ByteArray(10))
        assertEquals(count, e.messages(c.id).size)
    }

    @Test
    fun withoutAnAccountNothingIsAnswered() {
        val m = model(watch = watch)
        assertNull(m.engine)
        m.watch.message(action(WatchAction.Kind.refresh), "montre-1")
        assertTrue(watch.replies.isEmpty())
        m.watch.push(force = true)
        assertTrue(watch.contexts.isEmpty())
    }
}
