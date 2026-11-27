// L'application contre un vrai relais, local (`cargo build`, puis
// `PORT=3000 target/debug/talka`), comme les essais en direct de
// `talkakit` : deux téléphones (deux bases), qui s'ajoutent par leur code
// et s'écrivent. Sautés sans `TALKA_SERVER` (jamais un relais distant).

package talka.app

import android.os.Looper
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import talka.kit.Content
import talka.kit.Message
import talka.kit.OkHttpSockets
import talka.kit.Relay
import java.io.File
import java.time.Duration
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveTest : AppTestBase() {
    private val server = System.getenv("TALKA_SERVER") ?: ""
    private val other: File = Files.createTempDirectory("talka-app-2").toFile()

    @Before
    fun setUp() {
        assumeTrue("pas de relais local (TALKA_SERVER)", server.startsWith("http://127.0.0.1") || server.startsWith("http://localhost"))
        install()
    }

    @After
    fun tearDown() {
        uninstall()
        other.deleteRecursively()
    }

    private fun until(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!done()) {
            // Le temps du fil principal (celui de Robolectric) avance avec
            // le vrai : les accusés, groupés, partent après un court délai.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(10)
            check(System.currentTimeMillis() < end) { "toujours pas : $what" }
        }
    }

    @Test
    fun twoPhonesAddEachOtherAndTalk() {
        val lea = model(server = server, sockets = OkHttpSockets)
        lea.createAccount("Léa", "")
        val max = model(base = other, server = server, sockets = OkHttpSockets)
        max.createAccount("Max", "")
        val a = assertNotNull(lea.engine)
        val b = assertNotNull(max.engine)
        until("en ligne") { a.netState == Relay.State.online && b.netState == Relay.State.online }

        // Léa ajoute Max par son code : ses clés arrivent du relais.
        assertTrue(run(lea) { a.addContact(b.me.code, "Max") })
        lea.openChat(b.me.code)
        assertEquals(listOf(b.me.code), lea.path)
        val sent = assertNotNull(a.send(b.me.code, Content(text = "Bonjour Max")))

        // Max, ailleurs dans l'app : le message arrive, et le bandeau le dit.
        max.tab = AppModel.Tab.settings
        until("le message chez Max") { b.chat(a.me.code)?.last?.text == "Bonjour Max" }
        assertEquals(1, b.chat(a.me.code)?.unread)
        assertEquals(1, max.unreadTotal)
        val toast = assertNotNull(max.toast).text
        assertTrue(toast.endsWith(" : Bonjour Max"), toast)
        // Et chez Léa : ✓✓ distribué.
        until("distribué") { a.store.message(b.me.code, sent.id)?.tick == Message.Tick.delivered }

        // Max répond (il l'ajoute d'abord) ; Léa a la discussion à l'écran : pas de bandeau.
        assertTrue(run(max) { b.addContact(a.me.code, "Léa") })
        a.activeChat = b.me.code
        lea.toast = null
        b.send(a.me.code, Content(text = "Salut Léa"))
        until("la réponse chez Léa") { a.chat(b.me.code)?.last?.text == "Salut Léa" }
        assertEquals(null, lea.toast)
    }
}
