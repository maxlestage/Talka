// Les notifications des messages : comme sur l'iPhone, celles d'une
// discussion s'empilent (ici, dans sa notification), repartent de zéro une
// fois lue ou balayée, et portent le nombre de non-lus pour la pastille de
// l'icône.

package talka.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import talka.app.ui.Notifier
import talka.kit.Content
import talka.kit.Message
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotifierTest : AppTestBase() {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        install()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifier.prepare(app)
        Notifier.cancelAll()
    }

    @After
    fun tearDown() {
        Notifier.cancelAll()
        uninstall()
    }

    private fun of(thread: String): Notification? = manager.activeNotifications.firstOrNull { it.id == thread.hashCode() }?.notification

    private fun lines(n: Notification): List<String> =
        n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ?: emptyList()

    private fun text(n: Notification): String = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun theMessagesOfAChatPileUpInItsNotification() {
        Notifier.message("Max", "Coucou", "MAX", sound = true, unread = 1)
        val one = assertNotNull(of("MAX"))
        assertEquals("Coucou", text(one))
        assertEquals(emptyList(), lines(one))
        assertEquals(1, one.number)

        Notifier.message("Max", "Tu viens ce soir ?", "MAX", sound = true, unread = 2)
        Notifier.message("Zoé", "Salut", "ZOE", sound = false, unread = 1)
        val two = assertNotNull(of("MAX"))
        assertEquals("Tu viens ce soir ?", text(two))
        assertEquals(listOf("Coucou", "Tu viens ce soir ?"), lines(two))
        assertEquals(2, two.number)
        // Chaque discussion a la sienne.
        assertEquals("Salut", text(assertNotNull(of("ZOE"))))
        assertEquals(2, manager.activeNotifications.size)

        // Les plus récents seulement, les non-lus tous comptés.
        for (i in 3..9) Notifier.message("Max", "message $i", "MAX", sound = true, unread = i)
        val many = assertNotNull(of("MAX"))
        assertEquals((4..9).map { "message $it" }, lines(many))
        assertEquals(9, many.number)
    }

    @Test
    fun aLongMessageIsCutAt160CharactersNotInTheMiddleOfAnEmoji() {
        // `String(body.prefix(160))` : 160 caractères au sens de Swift (un emoji compte pour un).
        Notifier.message("Max", "a".repeat(159) + "😀😀", "MAX", sound = true, unread = 1)
        assertEquals("a".repeat(159) + "😀", text(assertNotNull(of("MAX"))))
        Notifier.message("Zoé", "😀".repeat(200), "ZOE", sound = true, unread = 1)
        assertEquals("😀".repeat(160), text(assertNotNull(of("ZOE"))))
    }

    @Test
    fun aReadOrDismissedNotificationStartsAgainFromNothing() {
        Notifier.message("Max", "Coucou", "MAX", sound = true, unread = 1)
        Notifier.message("Max", "Tu es là ?", "MAX", sound = true, unread = 2)
        // Lue (la discussion ouverte) : elle part.
        Notifier.cancel("MAX")
        assertNull(of("MAX"))
        Notifier.message("Max", "Bon…", "MAX", sound = true, unread = 1)
        assertEquals(emptyList(), lines(assertNotNull(of("MAX"))))

        // Balayée par l'utilisateur : ses anciens messages ne reviennent pas.
        Notifier.message("Max", "Allô ?", "MAX", sound = true, unread = 2)
        manager.cancel("MAX".hashCode())
        Notifier.message("Max", "Rappelle-moi", "MAX", sound = true, unread = 3)
        val again = assertNotNull(of("MAX"))
        assertEquals("Rappelle-moi", text(again))
        assertEquals(emptyList(), lines(again))
        assertEquals(3, again.number)

        // Tout marquer comme lu : plus rien.
        Notifier.cancelAll()
        assertEquals(0, manager.activeNotifications.size)
    }

    /**
     * Talka fermé, réveillé par la montre (aucune activité) : il est en
     * arrière-plan, comme sur l'iPhone. Un message qui arrive alors est
     * annoncé par une notification, pas par un bandeau que personne ne voit.
     */
    @Test
    fun wokenWithoutAnActivityTalkaIsInTheBackground() {
        val m = model(startsActive = false)
        m.createAccount("Léa", "")
        idle()
        val e = assertNotNull(m.engine)
        e.saveSettings { it.copy(notify = true) }
        val chat = e.ensureDirectChat("K7Q2MX9PRT")
        m.incoming(chat, Message(chat.id, "m1", "K7Q2MX9PRT", 1.0, Content(text = "Coucou")), false)
        assertEquals("Coucou", text(assertNotNull(of(chat.id), "pas de notification")))
        assertNull(m.toast)
        // L'activité arrive : au premier plan, un bandeau.
        m.scene(AppModel.Phase.active)
        m.incoming(chat, Message(chat.id, "m2", "K7Q2MX9PRT", 2.0, Content(text = "Tu es là ?")), false)
        assertEquals("K7Q2M-X9PRT : Tu es là ?", m.toast?.text)
    }

    /** En arrière-plan, le moteur annonce le message avec les non-lus de la discussion. */
    @Test
    fun aMessageInTheBackgroundIsAnnouncedWithTheUnreadCount() {
        val m = model()
        m.createAccount("Léa", "")
        idle()
        val e = assertNotNull(m.engine)
        val chat = e.ensureDirectChat("K7Q2MX9PRT")
        e.updateChat(chat.id) { it.copy(unread = 3) }
        m.scene(AppModel.Phase.inactive)
        m.scene(AppModel.Phase.background)
        // Les notifications sont à activer dans les réglages, comme sur l'iPhone.
        m.incoming(e.chat(chat.id)!!, Message(chat.id, "m0", "K7Q2MX9PRT", 0.5, Content(text = "Sans réglage")), false)
        assertNull(of(chat.id))
        e.saveSettings { it.copy(notify = true) }
        m.incoming(e.chat(chat.id)!!, Message(chat.id, "m1", "K7Q2MX9PRT", 1.0, Content(text = "Coucou")), false)
        val n = assertNotNull(of(chat.id))
        assertEquals("Coucou", text(n))
        assertEquals(3, n.number)
        // L'aperçu masqué (réglage) : rien du message.
        e.saveSettings { it.copy(notifyPreview = "none") }
        m.incoming(e.chat(chat.id)!!, Message(chat.id, "m2", "K7Q2MX9PRT", 2.0, Content(text = "Secret")), false)
        assertEquals("Nouveau message", text(assertNotNull(of(chat.id))))
    }
}
