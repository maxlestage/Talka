// Les widgets de l'écran d'accueil : ce que le moteur leur écrit (par
// `WidgetShare`, comme le trousseau de l'iPhone), quand ils sont rechargés,
// ce qu'ils cachent quand Talka est protégé par un code, et ce qu'ils
// affichent (les textes de `TalkaWidgets.swift`), dessinés par Glance.

package talka.app.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Looper
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import talka.app.AppTestBase
import talka.kit.Content
import talka.kit.WidgetChat
import talka.kit.WidgetData
import talka.kit.WidgetShare
import talka.kit.WidgetStorage
import talka.link.FileWidgetStorage
import talka.link.WidgetContent
import java.io.File
import java.nio.file.Files
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Une garde en mémoire, qui compte ses écritures. */
class MemoryWidgetStorage : WidgetStorage {
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetsTest : AppTestBase() {
    private val storage = MemoryWidgetStorage()
    private var reloads = 0

    @Before
    fun setUp() {
        install()
        WidgetShare.storage = storage
        WidgetShare.clear()
    }

    @After
    fun tearDown() {
        uninstall()
        WidgetShare.clear()
        WidgetShare.storage = null
    }

    private fun widgetModel() = model(reloadWidgets = { reloads += 1 })

    @Test
    fun theEngineFeedsTheWidgets() {
        val m = widgetModel()
        assertNull(m.engine)
        m.watch.updateWidgets()
        assertNull(WidgetShare.read(), "pas de compte : rien pour les widgets")
        assertEquals(0, reloads)

        m.createAccount("Léa", "")
        idle()
        val e = assertNotNull(m.engine)
        val old = e.ensureDirectChat("K7Q2MX9PRT")
        e.send(old.id, Content(text = "Merci !"))
        val c = e.ensureDirectChat("WGSEAJNSZJ")
        e.send(c.id, Content(text = "On se retrouve à 19 h ?"))
        e.updateChat(c.id) { it.copy(unread = 2) }
        m.watch.updateWidgets()

        val d = assertNotNull(WidgetShare.read())
        assertEquals(2, d.unread)
        assertFalse(d.hidden)
        assertEquals(listOf("WGSEAJNSZJ", "K7Q2MX9PRT"), d.chats.map { it.id }, "les non lues d'abord")
        assertEquals("On se retrouve à 19 h ?", d.chats[0].last)
        assertEquals(1, reloads, "rechargés une fois")
        assertEquals("2 messages non lus", WidgetContent.description(d))
        assertEquals("WGSEA-JNSZJ : On se retrouve à 19 h ?", WidgetContent.smallLatest(d))
        assertEquals("talka://chat/WGSEAJNSZJ", WidgetContent.unreadLink(d))

        // Rien n'a changé : ni écriture ni rechargement.
        val writes = storage.writes
        m.watch.updateWidgets()
        assertEquals(writes, storage.writes)
        assertEquals(1, reloads)

        // Lu sur le téléphone : la minuterie (toutes les deux secondes) le reporte.
        e.markRead(c.id)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2100))
        assertEquals(0, WidgetShare.read()?.unread)
        assertEquals(2, reloads)
        assertEquals("Aucun nouveau message", WidgetContent.status(WidgetShare.read()))
    }

    @Test
    fun aLockCodeHidesNamesAndPreviews() {
        val m = widgetModel()
        m.createAccount("Léa", "")
        idle()
        val e = assertNotNull(m.engine)
        val c = e.ensureDirectChat("WGSEAJNSZJ")
        e.send(c.id, Content(text = "Secret"))
        e.updateChat(c.id) { it.copy(unread = 1) }
        run(m) { m.setPin("482916") }
        m.watch.updateWidgets()

        val d = assertNotNull(WidgetShare.read())
        assertTrue(d.hidden)
        assertEquals(1, d.unread, "le nombre reste")
        assertTrue(d.chats.isEmpty(), "ni noms ni aperçus")
        assertFalse(String(assertNotNull(storage.bytes)).contains("Secret"))
        assertNull(WidgetContent.latest(d))
        assertEquals("talka://chats", WidgetContent.unreadLink(d))
        assertEquals("1", WidgetContent.smallCount(d))
        assertEquals("message non lu", WidgetContent.smallLabel(d))
        assertEquals(WidgetContent.Recent.Hidden, WidgetContent.recent(d, large = false))
        assertEquals(
            "Talka est protégé par un code : les discussions restent dans l'application.",
            WidgetContent.recentMessage(WidgetContent.Recent.Hidden),
        )
    }

    @Test
    fun wipingEmptiesTheWidgets() {
        val m = widgetModel()
        m.createAccount("Léa", "")
        idle()
        m.watch.updateWidgets()
        assertNotNull(WidgetShare.read())
        val before = reloads
        m.wipe()
        assertNull(WidgetShare.read())
        assertTrue(reloads > before, "les widgets sont rechargés, vides")
        assertEquals(WidgetContent.Recent.Waiting, WidgetContent.recent(WidgetShare.read(), large = true))
    }

    @Test
    fun goingToTheBackgroundPushesTheLastState() {
        val m = widgetModel()
        m.createAccount("Léa", "")
        idle()
        val e = assertNotNull(m.engine)
        val c = e.ensureDirectChat("WGSEAJNSZJ")
        e.send(c.id, Content(text = "Bonjour"))
        e.updateChat(c.id) { it.copy(unread = 4) }
        m.scene(talka.app.AppModel.Phase.background)
        assertEquals(4, WidgetShare.read()?.unread)
    }

    @Test
    fun whatTheWidgetsShow() {
        val s = WidgetContent.sample(now = 1_700_000_000_000.0)
        assertEquals(3, s.unread)
        assertEquals("Léa", WidgetContent.latest(s)?.title)
        assertEquals("3", WidgetContent.smallCount(s))
        assertEquals("messages non lus", WidgetContent.smallLabel(s))
        assertEquals("Léa : On se retrouve à 19 h ?", WidgetContent.smallLatest(s))
        assertEquals("3", WidgetContent.rectangularBadge(s))
        assertEquals("3 messages non lus", WidgetContent.recentSummary(s))
        assertEquals("3 non lus", WidgetContent.inline(s))
        assertEquals("3 non lus", WidgetContent.corner(s))

        // Moyen : trois discussions ; grand : six au plus.
        val many = s.copy(chats = (1..8).map { WidgetChat("C$it", "Contact $it", "C", it, false, 0, "…", 0.0) })
        assertEquals(3, (WidgetContent.recent(many, large = false) as WidgetContent.Recent.Rows).chats.size)
        assertEquals(6, (WidgetContent.recent(many, large = true) as WidgetContent.Recent.Rows).chats.size)
        assertEquals(WidgetContent.Recent.Empty, WidgetContent.recent(WidgetData(), large = false))
        assertEquals("Aucune discussion pour l'instant.", WidgetContent.recentMessage(WidgetContent.Recent.Empty))

        // Rien d'écrit encore.
        assertEquals("0", WidgetContent.smallCount(null))
        assertEquals("Vos messages s'afficheront ici", WidgetContent.status(null))
        assertNull(WidgetContent.rectangularBadge(null))
        assertEquals("Talka", WidgetContent.inline(null))
        assertEquals("", WidgetContent.recentSummary(null))
        assertEquals("Vos discussions récentes s'afficheront ici.", WidgetContent.recentMessage(WidgetContent.Recent.Waiting))
        assertEquals("Talka : rien de nouveau", WidgetContent.inline(WidgetData()))
        assertEquals("1 non lu", WidgetContent.inline(WidgetData(unread = 1)))
        assertEquals("99+", WidgetContent.badge(120))
        assertEquals("120", WidgetContent.smallCount(WidgetData(unread = 120)), "le petit carré écrit le nombre entier")

        // Les adresses : la discussion (encodée), ou la liste.
        assertEquals("talka://chat/g%3Afamille", WidgetData.link("g:famille"))
        assertEquals("g:famille", WidgetData.chat(WidgetData.link("g:famille")))
        assertEquals("talka://chats", WidgetContent.recentLink)
        // Les couleurs des avatars, celles de l'iPhone, en boucle.
        assertEquals(0xFFE542A3, WidgetContent.avatar(0))
        assertEquals(0xFFE542A3, WidgetContent.avatar(8))
        assertEquals(0xFF02A698, WidgetContent.avatar(3))
    }

    @Test
    fun theFileStorageIsAtomicAndPrivate() {
        val dir: File = Files.createTempDirectory("widgets").toFile()
        val s = FileWidgetStorage(File(dir, "w/data.json"))
        assertNull(s.read())
        assertTrue(s.write("un".toByteArray()))
        assertTrue(s.write("deux".toByteArray()))
        assertContentEquals("deux".toByteArray(), s.read())
        assertFalse(File(dir, "w/data.json.tmp").exists())
        s.clear()
        assertNull(s.read())
        dir.deleteRecursively()
        // Celle de l'application : dans ses fichiers privés, hors sauvegarde.
        val app = RuntimeEnvironment.getApplication()
        assertTrue(app.noBackupFilesDir.exists() || app.noBackupFilesDir.mkdirs())
    }

    @Test
    fun theTwoWidgetsAreDeclared() {
        val app = RuntimeEnvironment.getApplication()
        val receivers = app.packageManager
            .queryBroadcastReceivers(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(app.packageName), 0)
            .map { it.activityInfo.name }
            .toSet()
        assertEquals(setOf(UnreadWidgetReceiver::class.java.name, RecentChatsWidgetReceiver::class.java.name), receivers)
    }
}

/** Les widgets dessinés (Glance), sans écran d'accueil : ce qu'on y lit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetViewsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val sample = WidgetContent.sample(now = 1_700_000_000_000.0)

    @Test
    fun theSmallSquare() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(UnreadWidget.SMALL)
        setContext(context)
        provideComposable { SmallView(sample) }
        onNode(hasText("Talka")).assertExists()
        onNode(hasText("3")).assertExists()
        onNode(hasText("messages non lus")).assertExists()
        onNode(hasText("Léa : On se retrouve à 19 h ?")).assertExists()
    }

    @Test
    fun theRectangle() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(UnreadWidget.RECTANGULAR)
        setContext(context)
        provideComposable { RectangularView(null) }
        onNode(hasText("Vos messages s'afficheront ici")).assertExists()
    }

    @Test
    fun theCircleOfTheIPhoneLockScreenInOneCell() = runGlanceAppWidgetUnitTest {
        // `accessoryCircular` : le nombre, « 99+ » au-delà ; le résumé pour le lecteur d'écran.
        setAppWidgetSize(UnreadWidget.CIRCULAR)
        setContext(context)
        provideComposable { CircularView(sample.copy(unread = 120)) }
        onNode(hasText("99+")).assertExists()
        onNode(hasContentDescriptionEqualTo("120 messages non lus")).assertExists()
        onNode(hasText("Léa")).assertDoesNotExist()
    }

    @Test
    fun theCircleBeforeTalkaWroteAnything() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(UnreadWidget.CIRCULAR)
        setContext(context)
        provideComposable { CircularView(null) }
        onNode(hasText("0")).assertExists()
    }

    @Test
    fun theLineOfTheIPhoneLockScreenInTwoCells() = runGlanceAppWidgetUnitTest {
        // `accessoryInline` : « 3 non lus », jamais de nom ni de message.
        setAppWidgetSize(UnreadWidget.INLINE)
        setContext(context)
        provideComposable { InlineView(sample) }
        onNode(hasText("3 non lus")).assertExists()
        onNode(hasText("Léa")).assertDoesNotExist()
    }

    @Test
    fun theLineWithNothingNewOrNothingYet() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(UnreadWidget.INLINE)
        setContext(context)
        provideComposable {
            androidx.glance.layout.Column {
                InlineView(WidgetData())
                InlineView(null)
                InlineView(WidgetData(unread = 1))
            }
        }
        onNode(hasTextEqualTo("Talka : rien de nouveau")).assertExists()
        onNode(hasTextEqualTo("Talka")).assertExists()
        onNode(hasTextEqualTo("1 non lu")).assertExists()
    }

    @Test
    fun theUnreadWidgetHasTheFourShapesOfTheIPhone() {
        val sizes = (UnreadWidget().sizeMode as androidx.glance.appwidget.SizeMode.Responsive).sizes
        assertEquals(setOf(UnreadWidget.CIRCULAR, UnreadWidget.INLINE, UnreadWidget.RECTANGULAR, UnreadWidget.SMALL), sizes)
        // Une case : le rond ; deux de large : la ligne ; plus large : le bandeau ; deux sur deux : le carré.
        fun fits(size: androidx.compose.ui.unit.DpSize, w: Int, h: Int) = size.width.value <= w && size.height.value <= h
        fun best(w: Int, h: Int) = sizes.filter { fits(it, w, h) }.maxByOrNull { it.width.value * it.height.value }
        assertEquals(UnreadWidget.CIRCULAR, best(60, 80))
        assertEquals(UnreadWidget.INLINE, best(140, 80))
        assertEquals(UnreadWidget.RECTANGULAR, best(220, 80))
        assertEquals(UnreadWidget.SMALL, best(140, 160))
        assertEquals(UnreadWidget.SMALL, best(300, 160))
    }

    @Test
    fun theRecentChats() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(RecentChatsWidget.MEDIUM)
        setContext(context)
        provideComposable { RecentView(sample, large = false) }
        onNode(hasText("3 messages non lus")).assertExists()
        onNode(hasText("Famille")).assertExists()
        onNode(hasText("Clément")).assertExists()
        onNode(hasText("2")).assertExists()
    }

    @Test
    fun theRecentChatsHiddenByACode() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(RecentChatsWidget.LARGE)
        setContext(context)
        provideComposable { RecentView(sample.copy(chats = emptyList(), hidden = true), large = true) }
        onNode(hasText("Talka est protégé par un code : les discussions restent dans l'application.")).assertExists()
        onNode(hasText("Léa")).assertDoesNotExist()
    }
}
