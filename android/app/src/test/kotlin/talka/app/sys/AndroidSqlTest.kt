// La mémoire de Talka (`Store`, de TalkaKit) sur SQLite d'Android
// (`AndroidSql`), avec Robolectric : les mêmes résultats que sur la JVM
// (`talkakit`, `sqlite-jdbc`) et que sur l'iPhone.

package talka.app.sys

import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import talka.kit.Contact
import talka.kit.Content
import talka.kit.Message
import talka.kit.Profile
import talka.kit.Store
import talka.kit.randomBytes
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSqlTest {
    private val opened = ArrayList<Store>()
    private val dir: File = Files.createTempDirectory("talka").toFile()

    @After
    fun cleanUp() {
        for (s in opened) s.close()
        dir.deleteRecursively()
    }

    private fun store(key: ByteArray? = null): Store = Store(dir, AndroidSql, key).also { opened.add(it) }

    private fun message(chat: String, id: String, at: Double, text: String) =
        Message(chat, id, "WGSEAJNSZJ", at, Content(text = text))

    private fun disk(): ByteArray =
        listOf("", "-wal").fold(ByteArray(0)) { acc, suffix ->
            val f = File(dir, "talka.sqlite$suffix")
            acc + (if (f.exists()) f.readBytes() else ByteArray(0))
        }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    @Test
    fun readWriteAndMessagesInOrder() {
        val s = store()
        s.write("kv", "profile", Profile(name = "Léa"))
        assertEquals("Léa", s.read("kv", "profile", Profile)?.name)
        for ((id, at) in listOf("m1" to 30.0, "m2" to 10.0, "m3" to 20.0)) s.writeMessage(message("g:x", id, at, id))
        s.writeMessage(message("AUTRE", "m9", 5.0, "m9"))
        assertEquals(listOf("m2", "m3", "m1"), s.messagesOf("g:x").map { it.id })
        assertEquals(listOf("m3", "m1"), s.messagesOf("g:x", limit = 2).map { it.id })
        s.removeMessage("g:x", "m3")
        assertEquals(listOf("m2", "m1"), s.messagesOf("g:x").map { it.id })
        s.deleteMessages("g:x")
        assertTrue(s.messagesOf("g:x").isEmpty())
        assertEquals(listOf("m9"), s.allMessages().map { it.id })
        s.remove("kv", "profile")
        assertNull(s.read("kv", "profile", Profile))
    }

    /** Chiffrée au repos, reprise des lignes en clair, rien ne se lit sans la clé. */
    @Test
    fun encryptedAtRest() {
        store().apply {
            write("contacts", "WGSEAJNSZJ", Contact(code = "WGSEAJNSZJ"))
            writeMessage(message("WGSEAJNSZJ", "m1", 1.0, "Le code du portail est 4521"))
            close()
        }
        assertTrue(contains(disk(), "portail".toByteArray()))
        val key = randomBytes(32)
        store(key).apply {
            assertEquals(listOf("Le code du portail est 4521"), messagesOf("WGSEAJNSZJ").map { it.text })
            writeMessage(message("WGSEAJNSZJ", "m2", 2.0, "Rendez-vous à la gare"))
            write("kv", "profile", Profile(name = "Léa Secrète"))
            save()
            close()
        }
        for (word in listOf("portail", "gare", "Secrète")) {
            assertFalse(contains(disk(), word.toByteArray()), "« $word » en clair sur le disque")
        }
        val again = store(key)
        assertEquals(listOf("Le code du portail est 4521", "Rendez-vous à la gare"), again.messagesOf("WGSEAJNSZJ").map { it.text })
        assertEquals("Léa Secrète", again.read("kv", "profile", Profile)?.name)
        assertEquals(2, again.dump().let { it["messages"].toString().split("|m").size - 1 })
        // Pas de clé : lecture seule, rien n'est écrasé.
        val locked = store()
        assertNull(locked.read("kv", "profile", Profile))
        locked.write("kv", "profile", Profile(name = "Autre"))
        assertEquals("Léa Secrète", store(key).read("kv", "profile", Profile)?.name)
    }

    /** Les lots s'emboîtent et valident ensemble ; tout survit à la réouverture. */
    @Test
    fun transactionsAndReopen() {
        val key = randomBytes(32)
        val s = store(key)
        s.transaction {
            s.writeMessage(message("g:y", "a", 1.0, "un"))
            s.transaction { s.writeMessage(message("g:y", "b", 2.0, "deux")) }
            s.write("contacts", "WGSEAJNSZJ", Contact(code = "WGSEAJNSZJ"))
        }
        s.close()
        val again = store(key)
        assertEquals(listOf("un", "deux"), again.messagesOf("g:y").map { it.text })
        assertEquals(listOf("WGSEAJNSZJ"), again.all("contacts", Contact).map { it.code })
    }
}
