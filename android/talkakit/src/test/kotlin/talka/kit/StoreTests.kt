// La mémoire de l'appareil (SQLite) : lecture, écriture, ordre des messages,
// réouverture, sauvegarde au format du navigateur, reprise des fichiers JSON
// des premières versions. Les mêmes tests que l'iPhone
// (`ios/Tests/TalkaKitTests/StoreTests.swift`).

package talka.kit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Le générateur des tests tirés au hasard, le même que l'iPhone. */
internal class Lcg(var seed: ULong) {
    fun next(n: Int): Int {
        seed = seed * 6364136223846793005uL + 1442695040888963407uL
        return ((seed shr 33) % n.toULong()).toInt()
    }
}

/** Les octets de `needle` se trouvent-ils dans `haystack` ? */
internal fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return true
    }
    return false
}

internal fun JsonObject.rowsOf(store: String): Int = this[store]?.jsonArray?.size ?: -1

class StoreTests {
    private val stores = Stores()

    @AfterTest
    fun cleanUp() = stores.closeAll()

    fun tempDir(): File = stores.dir()

    fun store(dir: File, key: ByteArray? = null): Store = stores.open(dir, key)

    fun message(chat: String, id: String, at: Double, text: String): Message =
        Message(chat, id, "WGSEAJNSZJ", at, Content(text = text))

    fun ids(list: List<Message>): List<String> = list.map { it.id }

    @Test
    fun testReadWriteAndMessagesInOrder() {
        val s = store(tempDir())
        s.write("kv", "profile", Profile(name = "Léa"))
        assertEquals("Léa", s.read("kv", "profile", Profile)?.name)
        for ((id, at) in listOf("m1" to 30.0, "m2" to 10.0, "m3" to 20.0)) {
            s.writeMessage(message("g:x", id, at, id))
        }
        s.writeMessage(message("AUTRE", "m9", 5.0, "m9"))
        assertEquals(listOf("m2", "m3", "m1"), ids(s.messagesOf("g:x")))
        assertEquals(listOf("m3", "m1"), ids(s.messagesOf("g:x", limit = 2)))
        s.removeMessage("g:x", "m3")
        assertEquals(listOf("m2", "m1"), ids(s.messagesOf("g:x")))
        s.deleteMessages("g:x")
        assertTrue(s.messagesOf("g:x").isEmpty())
        assertEquals(listOf("m9"), ids(s.allMessages()))
        s.remove("kv", "profile")
        assertNull(s.read("kv", "profile", Profile))
    }

    /** Les octets de la base sur le disque (avec son journal). */
    fun disk(dir: File): ByteArray =
        listOf("", "-wal").fold(ByteArray(0)) { acc, suffix ->
            val f = File(dir, "talka.sqlite$suffix")
            acc + (if (f.exists()) f.readBytes() else ByteArray(0))
        }

    /**
     * Chiffrée au repos : rien de lisible sur le disque, tout en clair pour
     * Talka ; les lignes d'avant sont reprises ; sans la clé, rien ne se lit
     * et rien ne s'écrase.
     */
    @Test
    fun testEncryptedAtRest() {
        val dir = tempDir()
        run {
            // Une base d'avant, en clair.
            val old = store(dir)
            old.write("contacts", "WGSEAJNSZJ", Contact(code = "WGSEAJNSZJ"))
            old.writeMessage(message("WGSEAJNSZJ", "m1", 1.0, "Le code du portail est 4521"))
            old.close()
        }
        assertTrue(contains(disk(dir), "portail".toByteArray()))
        val key = newKey()
        run {
            val s = store(dir, key)
            assertEquals(listOf("Le code du portail est 4521"), s.messagesOf("WGSEAJNSZJ").map { it.text })
            s.writeMessage(message("WGSEAJNSZJ", "m2", 2.0, "Rendez-vous à la gare"))
            s.write("kv", "profile", Profile(name = "Léa Secrète"))
            s.save()
            s.close()
        }
        val bytes = disk(dir)
        for (word in listOf("portail", "gare", "Secrète", "WGSEAJNSZJ\"}")) {
            assertFalse(contains(bytes, word.toByteArray()), "« $word » en clair sur le disque")
        }
        val again = store(dir, key)
        assertEquals(listOf("Le code du portail est 4521", "Rendez-vous à la gare"), again.messagesOf("WGSEAJNSZJ").map { it.text })
        assertEquals("Léa Secrète", again.read("kv", "profile", Profile)?.name)
        assertEquals(2, again.allMessages().size)
        assertEquals(2, again.dump().rowsOf("messages"), "la sauvegarde garde son format")

        // Une autre clé : rien de lisible.
        assertTrue(store(dir, newKey()).messagesOf("WGSEAJNSZJ").isEmpty())
        // Pas de clé (trousseau muet) : lecture seule, rien n'est écrasé.
        val locked = store(dir)
        assertNull(locked.read("kv", "profile", Profile))
        locked.write("kv", "profile", Profile(name = "Autre"))
        assertEquals("Léa Secrète", store(dir, key).read("kv", "profile", Profile)?.name)
    }

    @Test
    fun testEverythingSurvivesAReopen() {
        val dir = tempDir()
        run {
            val s = store(dir)
            s.write("contacts", "WGSEAJNSZJ", Contact(code = "WGSEAJNSZJ"))
            s.writeMessage(message("WGSEAJNSZJ", "m1", 1.0, "Bonjour"))
            s.close()
        }
        assertTrue(File(dir, "talka.sqlite").exists())
        val again = store(dir)
        assertEquals(listOf("WGSEAJNSZJ"), again.all("contacts", Contact).map { it.code })
        assertEquals(listOf("Bonjour"), again.messagesOf("WGSEAJNSZJ").map { it.text })
    }

    @Test
    fun testBackupKeepsTheBrowserFormat() {
        val s = store(tempDir())
        s.write("calls", "old", CallEntry(id = "old", peer = "X", video = false, incoming = true, at = 1.0, dur = 0.0, missed = true))
        val backup = jsonOf(
            "kv" to listOf(listOf("identity", "{\"code\":\"X\"}")),
            "chats" to listOf(listOf("g:1", "{\"id\":\"g:1\"}")),
            "messages" to listOf(listOf("g:1|m1", JSON.encode(message("g:1", "m1", 12.5, "salut")), "g:1", 12.5)),
        )
        s.restore(backup)
        val dump = s.dump()
        assertEquals(0, dump.rowsOf("calls"))
        assertEquals("identity", dump["kv"]!!.jsonArray.first().jsonArray.first().jsonPrimitive.content)
        val m = dump["messages"]!!.jsonArray.first().jsonArray
        assertEquals(JsonPrimitive("g:1"), m[2])
        assertEquals(12.5, m[3].jsonPrimitive.double)
        assertEquals(listOf("salut"), s.messagesOf("g:1").map { it.text })
        s.wipe()
        assertTrue(s.allMessages().isEmpty())
        assertNull(s.read("kv", "identity", Identity))
    }

    /** Les fichiers JSON des premières versions sont repris, puis effacés. */
    @Test
    fun testMigratesTheOldJSONFiles() {
        val dir = tempDir()
        File(dir, "messages").mkdirs()
        fun write(file: File, map: Map<String, String>) =
            file.writeText(JSON.write(JsonObject(map.mapValues { JsonPrimitive(it.value) })))
        write(File(dir, "contacts.json"), mapOf("WGSEAJNSZJ" to JSON.encode(Contact(code = "WGSEAJNSZJ"))))
        val m = message("WGSEAJNSZJ", "m1", 3.0, "d'avant")
        write(File(File(dir, "messages"), "WGSEAJNSZJ.json"), mapOf("WGSEAJNSZJ|m1" to JSON.encode(m)))

        val s = store(dir)
        assertEquals(listOf("WGSEAJNSZJ"), s.all("contacts", Contact).map { it.code })
        assertEquals(listOf("d'avant"), s.messagesOf("WGSEAJNSZJ").map { it.text })
        assertFalse(File(dir, "contacts.json").exists())
        assertFalse(File(dir, "messages").exists())
        // Et une seule fois : rouvrir ne duplique rien.
        assertEquals(1, store(dir).allMessages().size)
    }

    // ----- Lire sans tout charger -----

    /**
     * Une discussion pas ouverte : un message seul, les derniers, ceux
     * d'avant une date se lisent par l'index, et une écriture ne charge
     * rien. Les mêmes réponses qu'une fois la discussion chargée.
     */
    @Test
    fun testReadsWithoutLoadingTheChat() {
        for (key in listOf(null, newKey())) {
            val dir = tempDir()
            val ats = listOf(50.0, 10.0, 30.0, 30.0, 20.0, 40.0, 30.0)
            run {
                val s = store(dir, key)
                for ((i, at) in ats.withIndex()) s.writeMessage(message("AMI", "m$i", at, "texte $i"))
                s.writeMessage(message("AUTRE", "m0", 25.0, "ailleurs"))
                // Écrire n'a rien chargé.
                assertNull(s.messages["AMI"])
                s.close()
            }
            val want = listOf("m1", "m4", "m2", "m3", "m6", "m5", "m0")
            val cold = store(dir, key)
            assertEquals("texte 3", cold.message("AMI", "m3")?.text)
            assertNull(cold.message("AMI", "absent"))
            assertNull(cold.message("AUTRE", "m3"))
            assertEquals(listOf("m0"), ids(cold.messagesOf("AMI", limit = 1)))
            assertEquals(want.takeLast(4), ids(cold.messagesOf("AMI", limit = 4)))
            assertEquals(want.takeLast(6), ids(cold.messagesOf("AMI", limit = 6)))
            assertTrue(cold.messagesOf("AMI", limit = 0).isEmpty())
            assertEquals(listOf("m1", "m4"), ids(cold.messagesBefore("AMI", 30.0)))
            assertEquals(listOf("m1", "m4", "m2", "m3", "m6"), ids(cold.messagesBefore("AMI", 30.5)))
            assertTrue(cold.messagesBefore("AMI", 10.0).isEmpty())
            assertNull(cold.messages["AMI"], "rien de tout cela ne charge la discussion")

            // Écrire et effacer sans charger : lu aussitôt, et gardé.
            val edited = message("AMI", "m3", 30.0, "corrigé").copy(edited = true)
            cold.writeMessage(edited)
            cold.writeMessage(message("AMI", "m7", 60.0, "nouveau"))
            cold.removeMessage("AMI", "m1")
            assertEquals("corrigé", cold.message("AMI", "m3")?.text)
            assertNull(cold.message("AMI", "m1"))
            assertEquals(listOf("m0", "m7"), ids(cold.messagesOf("AMI", limit = 2)))
            assertEquals(listOf("m4"), ids(cold.messagesBefore("AMI", 30.0)))
            assertNull(cold.messages["AMI"])

            // Chargée (tous ses messages demandés) : les mêmes réponses.
            val after = listOf("m4", "m2", "m3", "m6", "m5", "m0", "m7")
            assertEquals(after, ids(cold.messagesOf("AMI")))
            assertNotNull(cold.messages["AMI"])
            assertEquals("corrigé", cold.message("AMI", "m3")?.text)
            assertNull(cold.message("AMI", "m1"))
            assertEquals(listOf("m0", "m7"), ids(cold.messagesOf("AMI", limit = 2)))
            assertEquals(listOf("m4"), ids(cold.messagesBefore("AMI", 30.0)))
            assertEquals(listOf("m4", "m2", "m3", "m6"), ids(cold.messagesBefore("AMI", 30.5)))
            // Et après une réouverture.
            assertEquals(
                listOf("texte 4", "texte 2", "corrigé", "texte 6", "texte 5", "texte 0", "nouveau"),
                store(dir, key).messagesOf("AMI").map { it.text },
            )
        }
    }

    /**
     * La liste triée d'une discussion chargée suit chaque écriture (ajout,
     * modification, changement d'heure, suppression) : toujours celle qu'un
     * tri complet donnerait, et celle que relit une base rouverte.
     */
    @Test
    fun testOrderedListFollowsEveryWrite() {
        val dir = tempDir()
        val s = store(dir)
        val ref = HashMap<String, Message>()
        val rng = Lcg(42u)
        fun sorted(): List<Message> = ref.values.sortedWith(Store.order)
        assertTrue(s.messagesOf("g:x").isEmpty())
        for (step in 0 until 600) {
            val id = "m${rng.next(120)}"
            val r = rng.next(5)
            when {
                r == 0 && ref[id] != null -> {
                    s.removeMessage("g:x", id)
                    ref.remove(id)
                }
                r == 1 && ref[id] != null -> {
                    // Modifié, à la même heure.
                    val m = ref[id]!!.copy(text = "étape $step")
                    s.writeMessage(m)
                    ref[id] = m
                }
                else -> {
                    // Nouveau, ou la même identité à une autre heure (souvent la
                    // même que d'autres : l'identifiant départage).
                    val m = message("g:x", id, rng.next(25).toDouble(), "étape $step")
                    s.writeMessage(m)
                    ref[id] = m
                }
            }
            assertEquals(sorted(), s.messagesOf("g:x"), "étape $step")
            if (step % 50 == 0) {
                val want = sorted()
                assertEquals(want.takeLast(7), s.messagesOf("g:x", limit = 7))
                assertEquals(want.filter { it.at < 12 }, s.messagesBefore("g:x", 12.0))
                // Une base rouverte, sans la charger : les mêmes, par l'index.
                val cold = store(dir)
                assertEquals(want.takeLast(7), cold.messagesOf("g:x", limit = 7))
                assertEquals(want.filter { it.at < 12 }, cold.messagesBefore("g:x", 12.0))
                assertEquals(want, cold.messagesOf("g:x"))
                cold.close()
            }
        }
        s.deleteMessages("g:x")
        assertTrue(s.messagesOf("g:x").isEmpty())
        s.writeMessage(message("g:x", "seul", 3.0, "seul"))
        assertEquals(listOf("seul"), ids(s.messagesOf("g:x")))
    }

    /**
     * Les derniers messages d'une discussion pas chargée sont gardés d'une
     * lecture à l'autre (la montre les redemande sans cesse), mais jamais
     * périmés : après chaque écriture, ce sont ceux qu'un tri complet
     * donnerait, quelle que soit la limite demandée.
     */
    @Test
    fun testRecentMessagesAreKeptButNeverStale() {
        val s = store(tempDir(), newKey())
        val ref = HashMap<String, HashMap<String, Message>>()
        val rng = Lcg(99u)
        fun want(chat: String, limit: Int): List<Message> =
            (ref[chat] ?: emptyMap<String, Message>()).values.sortedWith(Store.order).takeLast(limit)
        // Plus de discussions que la mémoire n'en garde à la fois.
        val chats = (0 until 40).map { "AMI$it" }
        for (step in 0 until 1500) {
            val chat = chats[rng.next(chats.size)]
            val id = "m${rng.next(30)}"
            val r = rng.next(6)
            when {
                r == 0 && ref[chat]?.get(id) != null -> {
                    s.removeMessage(chat, id)
                    ref[chat]?.remove(id)
                }
                r == 1 -> {
                    // Seulement relire, deux fois de suite, avec des limites différentes.
                }
                else -> {
                    val m = message(chat, id, rng.next(40).toDouble(), "étape $step")
                    s.writeMessage(m)
                    ref.getOrPut(chat) { HashMap() }[id] = m
                }
            }
            for (limit in listOf(listOf(15, 8, 4, 2), listOf(1, 20), listOf(64, 3), listOf(70, 15))[rng.next(4)]) {
                assertEquals(want(chat, limit), s.messagesOf(chat, limit = limit), "étape $step, limite $limit")
            }
        }
        assertTrue(s.windows.size <= Store.windowChats)
        assertEquals(s.windows.keys, s.windowOrder.toSet())
        // Vidée, puis chargée : plus rien de gardé à part.
        s.deleteMessages("AMI0")
        assertTrue(s.messagesOf("AMI0", limit = 15).isEmpty())
        assertEquals(ref["AMI1"]?.size ?: 0, s.messagesOf("AMI1").size)
        assertNull(s.windows["AMI1"])
        assertEquals(want("AMI1", 5), s.messagesOf("AMI1", limit = 5))
    }

    /**
     * Lire quelques messages un par un ne charge pas la discussion, ni
     * chercher des messages absents (chaque message qui arrive demande s'il
     * est déjà là) ; en relire sans fin plus que la mémoire n'en garde la
     * charge une fois, avec les mêmes réponses.
     */
    @Test
    fun testAChatReadOverAndOverIsLoadedOnce() {
        val dir = tempDir()
        run {
            val s = store(dir)
            s.transaction {
                for (i in 0 until 300) s.writeMessage(message("AMI", "m$i", i.toDouble(), "texte $i"))
            }
            s.close()
        }
        // Quelques messages, relus cent fois : tenus en mémoire, rien n'est chargé.
        val few = store(dir)
        repeat(100) {
            for (i in 0 until Store.singlesMax) assertEquals("texte $i", few.message("AMI", "m$i")?.text)
        }
        assertNull(few.messages["AMI"])
        // Des messages absents, autant qu'on veut.
        for (i in 0 until 1000) assertNull(few.message("AMI", "absent$i"))
        assertNull(few.messages["AMI"])
        // Plus que la mémoire n'en garde, relus en boucle : chargée, une fois.
        val many = store(dir)
        for (pass in 0 until 3) {
            for (i in 0 until 200) assertEquals("texte $i", many.message("AMI", "m$i")?.text, "passage $pass")
        }
        assertEquals(300, many.messages["AMI"]?.size)
        assertNull(many.message("AMI", "absent"))
        assertTrue(many.singleReads.isEmpty())
        assertTrue(many.singles.isEmpty())
        // Vidée avant d'être chargée : le compte repart de zéro.
        val cleared = store(dir)
        for (i in 0 until 50) cleared.message("AMI", "m$i")
        cleared.deleteMessages("AMI")
        assertNull(cleared.message("AMI", "m0"))
        assertTrue(cleared.singleReads.isEmpty())
    }

    /**
     * Une longue discussion est décodée sur plusieurs cœurs : les mêmes
     * messages, tous, qu'une lecture ligne à ligne ; chiffrée ou d'avant le
     * chiffrement, avec des photos (des paquets coupés par le poids), et une
     * ligne abîmée simplement sautée.
     */
    @Test
    fun testALongChatIsDecodedInParallelExactly() {
        val photo = "data:image/jpeg;base64," + "/9j/4AAQ".repeat(40_000)
        for (key in listOf(null, newKey())) {
            val dir = tempDir()
            val want = ArrayList<Message>()
            run {
                val s = store(dir, key)
                s.transaction {
                    for (i in 0 until 3000) {
                        var content = Content(text = "Message $i — é, 🥦, \"guillemets\" et \\ barre")
                        if (i % 97 == 0) content = content.copy(kind = "image", data = photo)
                        // Des heures en désordre, et des heures égales.
                        val m = Message("AMI", "m$i", "WGSEAJNSZJ", ((i * 7919) % 500).toDouble(), content)
                            .copy(reactions = if (i % 5 == 0) mapOf("WGSEAJNSZJ" to "👍") else emptyMap())
                        s.writeMessage(m)
                        want.add(m)
                    }
                    s.writeMessage(message("AUTRE", "m0", 1.0, "ailleurs"))
                }
                s.close()
            }
            want.sortWith(Store.order)
            // À froid, ligne à ligne (une par une, sans charger) : la référence.
            val cold = store(dir, key)
            for (i in 0 until 3000 step 331) {
                assertEquals(want.first { it.id == "m$i" }, cold.message("AMI", "m$i"))
            }
            assertNull(cold.messages["AMI"])
            // Chargée d'un coup, en parallèle.
            val loaded = store(dir, key)
            assertEquals(want, loaded.messagesOf("AMI"))
            assertEquals(listOf("ailleurs"), loaded.messagesOf("AUTRE").map { it.text })
            // Une ligne abîmée au milieu : sautée, les autres restent.
            val broken = store(dir, key)
            broken.run("UPDATE rows SET json = ? WHERE store = 'messages' AND key = ?", listOf(ByteArray(64) { 7 }, "AMI|m1500"))
            assertEquals(want.filter { it.id != "m1500" }, broken.messagesOf("AMI"))
        }
        // Un paquet décodé garde l'ordre de ses lignes, quel que soit le partage.
        val s = store(tempDir(), newKey())
        val msgs = (0 until 1000).map { message("AMI", "m$it", it.toDouble(), "texte $it") }
        val rows = msgs.map { Store.MessageRow(it.key, sealed = s.seal("messages", it.key, JSON.encode(it))) }.toMutableList()
        rows[10] = Store.MessageRow("AMI|m10", json = JSON.encode(msgs[10]))
        rows[20] = Store.MessageRow("AMI|m20", sealed = ByteArray(80) { 1 })
        assertEquals(msgs.filter { it.id != "m20" }, s.decode(rows))
        assertEquals(msgs.take(5), s.decode(rows.take(5)))
        assertTrue(s.decode(emptyList()).isEmpty())
    }

    /**
     * Une liste lourde (les contacts et leurs photos, au lancement) est
     * décodée sur plusieurs cœurs : les mêmes objets, dans le même ordre,
     * les lignes illisibles sautées.
     */
    @Test
    fun testAHeavyListIsDecodedInParallelExactly() {
        val avatar = "data:image/jpeg;base64," + "/9j/4AAQ".repeat(5_000)
        val contacts = ArrayList<Contact>()
        for (i in 0 until 400) {
            var c = Contact(code = "CODE$i").copy(name = "Contact $i — é 🥦")
            if (i % 3 == 0) c = c.copy(avatar = avatar)
            contacts.add(c)
        }
        val jsons = contacts.map { JSON.encode(it) }.toMutableList()
        jsons[7] = "{abîmé"
        jsons[200] = ""
        val want = contacts.filterIndexed { i, _ -> i != 7 && i != 200 }
        assertEquals(want, Store.decodeAll(jsons, Contact))
        assertEquals(contacts.take(3), Store.decodeAll(jsons.take(3), Contact))
        assertTrue(Store.decodeAll(emptyList(), Contact).isEmpty())
        // Par la mémoire : tous, quels que soient leur nombre et leur poids.
        val s = store(tempDir(), newKey())
        s.transaction { for (c in contacts) s.write("contacts", c.code, c) }
        val all = s.all("contacts", Contact)
        assertEquals(400, all.size)
        assertEquals(contacts.associateBy { it.code }, all.associateBy { it.code })
        assertEquals(0, s.all("chats", Chat).size)
    }

    /**
     * Une longue discussion ne se lit pas en entier pour montrer sa fin :
     * la mémoire en garde les derniers messages (une fenêtre), tenue à jour
     * à chaque écriture. Après des centaines d'écritures tirées au hasard
     * (nouveaux messages, modifications, heures changées vers l'avant ou
     * l'arrière, suppressions), elle rend toujours ce qu'un tri complet
     * donnerait, dit juste s'il reste des messages plus anciens, et une base
     * rouverte est d'accord.
     */
    @Test
    fun testTheEndOfALongChatFollowsEveryWrite() {
        val dir = tempDir()
        val s = store(dir, newKey())
        val ref = HashMap<String, Message>()
        val rng = Lcg(2026u)
        fun sorted(): List<Message> = ref.values.sortedWith(Store.order)
        // 600 messages, puis la discussion vit.
        s.transaction {
            for (i in 0 until 600) {
                val m = message("LONGUE", "m$i", (i * 10).toDouble(), "texte $i")
                s.writeMessage(m)
                ref[m.id] = m
            }
        }
        assertNull(s.messages["LONGUE"])
        var fresh = 600
        for (step in 0 until 700) {
            when (rng.next(8)) {
                0, 1 -> {
                    // Un message arrive, à la fin.
                    val m = message("LONGUE", "n$fresh", (6000 + step * 3).toDouble(), "nouveau $fresh")
                    fresh += 1
                    s.writeMessage(m)
                    ref[m.id] = m
                }
                2 -> {
                    // Un message récent modifié (un accusé, une réaction), à la même heure.
                    val all = sorted()
                    val m = all[all.size - 1 - rng.next(minOf(40, all.size))].copy(text = "modifié à l'étape $step")
                    s.writeMessage(m)
                    ref[m.id] = m
                }
                3 -> {
                    // Un message n'importe où change d'heure, vers l'avant ou l'arrière.
                    val all = sorted()
                    val picked = all[rng.next(all.size)]
                    val m = picked.copy(at = rng.next(9000).toDouble())
                    s.writeMessage(m)
                    ref[m.id] = m
                }
                4 -> {
                    // Un message n'importe où est supprimé.
                    val all = sorted()
                    val m = all[rng.next(all.size)]
                    s.removeMessage("LONGUE", m.id)
                    ref.remove(m.id)
                }
                5 -> {
                    // Un vieux message, hors de toute fenêtre, arrive (une synchronisation).
                    val m = message("LONGUE", "v$step", rng.next(50).toDouble(), "ancien $step")
                    s.writeMessage(m)
                    ref[m.id] = m
                }
                else -> {}
            }
            val want = sorted()
            val limit = listOf(1, 15, 50, 120, 300)[rng.next(5)]
            assertEquals(want.takeLast(limit), s.messagesOf("LONGUE", limit = limit), "étape $step, limite $limit")
            val end = s.recentMessages("LONGUE", limit)
            assertTrue(end.list.size >= minOf(limit, want.size), "étape $step")
            assertEquals(want.takeLast(end.list.size), end.list, "étape $step, limite $limit")
            assertEquals(want.size > end.list.size, end.more, "étape $step")
            // Un message de la fin ; de temps en temps, un plus ancien que
            // la fenêtre (lus un par un sans fin, ils la feraient charger).
            val probe = if (step % 12 == 0) want[rng.next(want.size)] else want[want.size - 1 - rng.next(minOf(100, want.size))]
            assertEquals(probe, s.message("LONGUE", probe.id), "étape $step")
            assertNull(s.message("LONGUE", "absent$step"))
            if (step % 100 == 0) {
                val cold = store(dir, s.key)
                assertEquals(want.takeLast(120), cold.recentMessages("LONGUE", 120).list)
                assertEquals(want.takeLast(15), cold.messagesOf("LONGUE", limit = 15))
                cold.close()
            }
        }
        // Jamais chargée en entier : seule sa fin est en mémoire.
        assertNull(s.messages["LONGUE"])
        assertNotNull(s.windows["LONGUE"])
        assertTrue((s.windows["LONGUE"]?.size ?: Int.MAX_VALUE) < ref.size)
        // Demandée en entier (une recherche, un saut vers un vieux message) : chargée, la fenêtre s'efface.
        assertEquals(sorted(), s.recentMessages("LONGUE", Int.MAX_VALUE).list)
        assertFalse(s.recentMessages("LONGUE", Int.MAX_VALUE).more)
        assertNotNull(s.messages["LONGUE"])
        assertNull(s.windows["LONGUE"])
        assertEquals(sorted().takeLast(50), s.recentMessages("LONGUE", 50).list)
        assertTrue(s.recentMessages("LONGUE", 50).more)
    }

    /**
     * Une discussion qui tient en entier dans ce qu'on lui demande est
     * simplement chargée ; une discussion vide n'a rien de plus ancien ; et
     * la mémoire ne garde la fin que des discussions demandées récemment.
     */
    @Test
    fun testShortChatsAreLoadedAndOldWindowsForgotten() {
        val s = store(tempDir())
        for (i in 0 until 20) s.writeMessage(message("COURTE", "m$i", i.toDouble(), "texte $i"))
        val short = s.recentMessages("COURTE", 300)
        assertEquals((0 until 20).map { "m$it" }, ids(short.list))
        assertFalse(short.more)
        assertNotNull(s.messages["COURTE"])
        assertTrue(s.recentMessages("VIDE", 300).list.isEmpty())
        assertFalse(s.recentMessages("VIDE", 300).more)
        assertTrue(s.recentMessages("COURTE", 0).list.isEmpty())
        // Juste à la limite : 20 messages pour 20 demandés, rien de plus ancien.
        val exact = store(s.dir)
        assertFalse(exact.recentMessages("COURTE", 20).more)
        assertTrue(store(s.dir).recentMessages("COURTE", 19).more)
        // Plus de discussions que la mémoire ne garde de fenêtres.
        s.transaction {
            for (c in 0 until Store.windowChats + 8) {
                for (i in 0 until 30) s.writeMessage(message("C$c", "m$i", i.toDouble(), "texte $i"))
            }
        }
        for (c in 0 until Store.windowChats + 8) {
            assertEquals(listOf("m25", "m26", "m27", "m28", "m29"), ids(s.messagesOf("C$c", limit = 5)))
        }
        assertEquals(Store.windowChats, s.windows.size)
        assertNull(s.windows["C0"])
        assertNotNull(s.windows["C${Store.windowChats + 7}"])
        // Oubliée, elle se relit, juste.
        assertEquals(listOf("m27", "m28", "m29"), ids(s.messagesOf("C0", limit = 3)))
        assertEquals(s.windows.keys, s.windowOrder.toSet())
    }

    /**
     * Des lignes illisibles à la fin d'une discussion (une base abîmée) :
     * la fenêtre est plus courte que ce qu'on lui demande, mais elle n'est
     * pas relue dans SQLite à chaque fois pour autant, et elle suit toujours
     * les écritures.
     */
    @Test
    fun testAWindowWithUnreadableRowsIsNotReadAgain() {
        val dir = tempDir()
        run {
            val s = store(dir)
            s.transaction {
                for (i in 0 until 60) s.writeMessage(message("AMI", "m%02d".format(i), i.toDouble(), "texte $i"))
            }
            for (id in listOf("m52", "m57")) {
                s.run("UPDATE rows SET json = ? WHERE store = 'messages' AND key = ?", listOf("{abîmé", "AMI|$id"))
            }
            s.close()
        }
        val s = store(dir)
        // 21 lignes lues (une de plus que demandé), dont deux illisibles.
        val want = (39 until 60).filter { it != 52 && it != 57 }.map { "m%02d".format(it) }
        val first = s.recentMessages("AMI", 20)
        assertEquals(want, ids(first.list))
        assertTrue(first.more)
        assertEquals(21, s.windowCovers["AMI"])
        // Une ligne retirée dans le dos de la mémoire : si la fenêtre était
        // relue, elle le verrait. Elle ne l'est pas.
        s.run("DELETE FROM rows WHERE store = 'messages' AND key = ?", listOf("AMI|m45"))
        assertEquals(want, ids(s.recentMessages("AMI", 20).list))
        assertEquals(want, ids(s.messagesOf("AMI", limit = 20)))
        assertEquals(want.takeLast(5), ids(s.messagesOf("AMI", limit = 5)))
        // Elle suit toujours les écritures : un message arrive, un autre part.
        s.writeMessage(message("AMI", "m60", 60.0, "nouveau"))
        s.removeMessage("AMI", "m59")
        assertEquals(want.filter { it != "m59" } + "m60", ids(s.recentMessages("AMI", 20).list))
        assertEquals(21, s.windowCovers["AMI"])
        // Demander plus que ce qu'elle couvre : relue (et la ligne retirée se voit).
        val wider = ids(s.recentMessages("AMI", 30).list)
        assertFalse(wider.contains("m45"))
        assertEquals(29, wider.size)
        assertEquals(31, s.windowCovers["AMI"])
        s.deleteMessages("AMI")
        assertNull(s.windowCovers["AMI"])
    }

    /**
     * Les messages éphémères échus sont retirés sans charger la discussion,
     * et elle n'est relue que lorsqu'une échéance a pu passer.
     */
    @Test
    fun testExpiredMessagesLeaveWithoutLoadingTheChat() {
        fun ephemeral(id: String, at: Double, exp: Double?): Message = message("AMI", id, at, "texte $id").copy(exp = exp)
        for (key in listOf(null, newKey())) {
            val dir = tempDir()
            run {
                val s = store(dir, key)
                s.transaction {
                    for (i in 0 until 200) s.writeMessage(message("AMI", "m%03d".format(i), i.toDouble(), "texte $i"))
                    s.writeMessage(ephemeral("tard", 50.5, 5_000.0))
                    s.writeMessage(ephemeral("échu2", 120.5, 900.0))
                    s.writeMessage(ephemeral("échu1", 10.5, 999.0))
                    s.writeMessage(ephemeral("juste", 60.5, 1_000.0))
                    s.writeMessage(message("AUTRE", "x", 1.0, "ailleurs"))
                }
                s.close()
            }
            val s = store(dir, key)
            // Les échus, du plus ancien au plus récent ; une échéance qui
            // tombe pile à l'heure attend le passage suivant.
            assertEquals(listOf("échu1", "échu2"), s.removeExpired("AMI", 1_000.0).map { it.first })
            assertNull(s.messages["AMI"])
            assertEquals(1_000.0, s.expNext["AMI"])
            assertNull(s.message("AMI", "échu1"))
            assertNotNull(s.message("AMI", "juste"))
            // Rien n'a pu échoir : la discussion n'est pas relue (une ligne
            // changée dans le dos de la mémoire ne se voit pas).
            s.run("DELETE FROM rows WHERE store = 'messages' AND key = ?", listOf("AMI|juste"))
            assertTrue(s.removeExpired("AMI", 1_000.0).isEmpty())
            assertEquals(1_000.0, s.expNext["AMI"])
            // L'échéance passée : relue (« juste » n'y est plus).
            assertTrue(s.removeExpired("AMI", 1_001.0).isEmpty())
            assertEquals(5_000.0, s.expNext["AMI"])
            // Un message écrit avec une échéance plus proche la rapproche,
            // un autre plus lointaine non.
            s.writeMessage(ephemeral("loin", 300.0, 9_000.0))
            assertEquals(5_000.0, s.expNext["AMI"])
            s.writeMessage(ephemeral("proche", 301.0, 2_000.0))
            assertEquals(2_000.0, s.expNext["AMI"])
            assertTrue(s.removeExpired("AMI", 2_000.0).isEmpty())
            assertEquals(listOf(301.0), s.removeExpired("AMI", 2_001.0).map { it.second })
            assertEquals(5_000.0, s.expNext["AMI"])
            assertNull(s.messages["AMI"])
            // La fin en mémoire suit les retraits.
            assertEquals(listOf("m198", "m199", "loin"), ids(s.recentMessages("AMI", 3).list))
            assertEquals(listOf("tard", "loin"), s.removeExpired("AMI", 9_500.0).map { it.first })
            assertEquals(listOf("m197", "m198", "m199"), ids(s.recentMessages("AMI", 3).list))
            assertEquals(Double.POSITIVE_INFINITY, s.expNext["AMI"])
            // Une discussion chargée : la même chose, depuis la mémoire.
            assertEquals(200, s.messagesOf("AMI").size)
            s.writeMessage(ephemeral("chargé", 400.0, 20_000.0))
            s.writeMessage(ephemeral("chargé2", 401.0, 30_000.0))
            assertTrue(s.removeExpired("AMI", 20_000.0).isEmpty())
            assertEquals(listOf("chargé"), s.removeExpired("AMI", 20_001.0).map { it.first })
            assertEquals(30_000.0, s.expNext["AMI"])
            assertEquals(201, s.messagesOf("AMI").size)
            // Sans message éphémère : rien, et retenu.
            assertTrue(s.removeExpired("AUTRE", 1e15).isEmpty())
            assertEquals(Double.POSITIVE_INFINITY, s.expNext["AUTRE"])
            // Une discussion vidée ou une sauvegarde restaurée : tout est à revoir.
            s.deleteMessages("AMI")
            assertNull(s.expNext["AMI"])
            val dump = s.dump()
            s.restore(dump)
            assertTrue(s.expNext.isEmpty())
            // Ce qui reste sur le disque est bien ce qu'on croit.
            assertEquals(listOf("x"), ids(store(dir, key).messagesOf("AUTRE")))
        }
    }

    /**
     * Les messages importants d'une discussion se comptent sans la charger,
     * et le compte suit chaque message écrit ou retiré.
     */
    @Test
    fun testStarredIdsAreKeptWithoutLoadingTheChat() {
        fun starred(id: String, at: Double, deleted: Boolean = false): Message =
            message("AMI", id, at, "texte $id").copy(starred = true, deleted = deleted)
        for (key in listOf(null, newKey())) {
            val dir = tempDir()
            run {
                val s = store(dir, key)
                s.transaction {
                    for (i in 0 until 200) s.writeMessage(message("AMI", "m%03d".format(i), i.toDouble(), "texte $i"))
                    s.writeMessage(starred("étoile1", 10.5))
                    s.writeMessage(starred("étoile2", 150.5))
                    // Supprimé pour tous : son étoile ne compte plus.
                    s.writeMessage(starred("supprimé", 20.5, deleted = true))
                    s.writeMessage(message("AUTRE", "x", 1.0, "ailleurs"))
                }
                s.close()
            }
            val s = store(dir, key)
            assertNull(s.starredIn["AMI"])
            assertEquals(setOf("étoile1", "étoile2"), s.starredIds("AMI"))
            assertNull(s.messages["AMI"])
            assertTrue(s.starredIds("AUTRE").isEmpty())
            // Pas relue : une ligne changée dans le dos de la mémoire ne se voit pas.
            s.run("DELETE FROM rows WHERE store = 'messages' AND key = ?", listOf("AMI|étoile2"))
            assertEquals(setOf("étoile1", "étoile2"), s.starredIds("AMI"))
            // Une étoile posée, une retirée, un message supprimé pour tous, un autre retiré.
            var m = assertNotNull(s.message("AMI", "m050")).copy(starred = true)
            s.writeMessage(m)
            assertEquals(setOf("étoile1", "étoile2", "m050"), s.starredIds("AMI"))
            val first = assertNotNull(s.message("AMI", "étoile1")).copy(starred = false)
            s.writeMessage(first)
            assertEquals(setOf("étoile2", "m050"), s.starredIds("AMI"))
            m = m.copy(deleted = true)
            s.writeMessage(m)
            assertEquals(setOf("étoile2"), s.starredIds("AMI"))
            s.removeMessage("AMI", "étoile2")
            assertTrue(s.starredIds("AMI").isEmpty())
            s.writeMessage(starred("neuf", 500.0))
            assertEquals(setOf("neuf"), s.starredIds("AMI"))
            assertNull(s.messages["AMI"])
            // Un message éphémère important qui échoit sort du compte.
            val brief = starred("bref", 501.0).copy(exp = 1_000.0)
            s.writeMessage(brief)
            assertEquals(setOf("neuf", "bref"), s.starredIds("AMI"))
            assertEquals(listOf("bref"), s.removeExpired("AMI", 2_000.0).map { it.first })
            assertEquals(setOf("neuf"), s.starredIds("AMI"))
            // D'une discussion chargée : le même compte, depuis la mémoire.
            val other = store(dir, key)
            assertEquals(203, other.messagesOf("AMI").size)
            assertEquals(setOf("neuf"), other.starredIds("AMI"))
            other.removeMessage("AMI", "neuf")
            assertTrue(other.starredIds("AMI").isEmpty())
            // Vidée, ou restaurée d'une sauvegarde : tout est à revoir.
            s.deleteMessages("AMI")
            assertNull(s.starredIn["AMI"])
            assertTrue(s.starredIds("AMI").isEmpty())
            s.writeMessage(starred("après", 600.0))
            val dump = s.dump()
            s.writeMessage(starred("oublié", 601.0))
            s.restore(dump)
            assertTrue(s.starredIn.isEmpty())
            assertEquals(setOf("après"), s.starredIds("AMI"))
        }
    }

    /**
     * Ce que pèsent les pièces jointes d'une discussion se calcule sans la
     * charger, comme en la lisant en entier, et suit chaque message écrit
     * ou retiré.
     */
    @Test
    fun testStorageIsWeighedWithoutLoadingTheChat() {
        val photo = "data:image/jpeg;base64," + "/9j/4AAQ".repeat(500)
        fun piece(id: String, at: Double, kind: String, size: Double = 0.0, data: String? = null, deleted: Boolean = false): Message {
            val c = Content(text = "", kind = kind, data = data ?: photo, size = size)
            return Message("AMI", id, "WGSEAJNSZJ", at, c).copy(deleted = deleted)
        }
        for (key in listOf(null, newKey())) {
            val dir = tempDir()
            run {
                val s = store(dir, key)
                s.transaction {
                    for (i in 0 until 200) s.writeMessage(message("AMI", "m%03d".format(i), i.toDouble(), "texte $i"))
                    s.writeMessage(piece("photo1", 10.5, "image"))
                    s.writeMessage(piece("photo2", 11.5, "image", size = 123_456.0))
                    s.writeMessage(piece("doc", 12.5, "file", size = 2_000.0))
                    s.writeMessage(piece("vocal", 13.5, "voice", data = "data:audio/mp4;base64,AAAAAAAA"))
                    // Ne comptent pas : supprimé, sans contenu, ou pas une pièce jointe.
                    s.writeMessage(piece("supprimé", 14.5, "image", deleted = true))
                    s.writeMessage(piece("vide", 15.5, "image", data = ""))
                    s.writeMessage(piece("contact", 16.5, "contact", data = "{}"))
                    s.writeMessage(message("AUTRE", "x", 1.0, "ailleurs"))
                }
                s.close()
            }
            val whole = Storage.of(store(dir, key).messagesOf("AMI"))
            assertEquals(Storage(bytes = (3_000 + 123_456 + 2_000 + 6).toDouble(), photos = 2, files = 1, voices = 1), whole)
            val s = store(dir, key)
            assertEquals(whole, s.storage("AMI"))
            assertNull(s.messages["AMI"])
            assertEquals(Storage(), s.storage("AUTRE"))
            assertEquals(Storage(), s.storage("INCONNUE"))
            // Pas relue : une ligne changée dans le dos de la mémoire ne se voit pas.
            s.run("DELETE FROM rows WHERE store = 'messages' AND key = ?", listOf("AMI|doc"))
            assertEquals(whole, s.storage("AMI"))
            // Une photo arrive, une est supprimée pour tous, un document est retiré.
            s.writeMessage(piece("photo3", 300.0, "image", size = 500.0))
            val gone = assertNotNull(s.message("AMI", "photo2")).copy(deleted = true, data = "")
            s.writeMessage(gone)
            s.removeMessage("AMI", "doc")
            assertEquals(Storage(bytes = (3_000 + 500 + 6).toDouble(), photos = 2, files = 0, voices = 1), s.storage("AMI"))
            assertNull(s.messages["AMI"])
            // Un message de texte n'y change rien.
            s.writeMessage(message("AMI", "m500", 500.0, "du texte"))
            assertEquals(Storage(bytes = 3_506.0, photos = 2, files = 0, voices = 1), s.storage("AMI"))
            // D'une discussion chargée : le même poids, depuis la mémoire.
            val other = store(dir, key)
            assertEquals(208, other.messagesOf("AMI").size)
            assertEquals(Storage(bytes = 3_506.0, photos = 2, files = 0, voices = 1), other.storage("AMI"))
            assertEquals(Storage.of(other.messagesOf("AMI")), other.storage("AMI"))
            other.removeMessage("AMI", "vocal")
            assertEquals(Storage(bytes = 3_500.0, photos = 2, files = 0, voices = 0), other.storage("AMI"))
            // Vidée, ou restaurée d'une sauvegarde : tout est à revoir.
            s.deleteMessages("AMI")
            assertNull(s.piecesIn["AMI"])
            assertEquals(Storage(), s.storage("AMI"))
            s.writeMessage(piece("après", 600.0, "image", size = 42.0))
            val dump = s.dump()
            s.writeMessage(piece("oubliée", 601.0, "image", size = 1_000.0))
            s.restore(dump)
            assertTrue(s.piecesIn.isEmpty())
            assertEquals(Storage(bytes = 42.0, photos = 1, files = 0, voices = 0), s.storage("AMI"))
        }
    }

    /**
     * Une longue discussion lue en entier rend sa mémoire : il n'en reste
     * que la fin, tenue à jour, et tout se relit comme avant.
     */
    @Test
    fun testALongLoadedChatIsReleased() {
        val s = store(tempDir(), newKey())
        s.transaction {
            for (i in 0 until 1000) s.writeMessage(message("LONGUE", "m%04d".format(i), i.toDouble(), "texte $i"))
            for (i in 0 until 1000) s.writeMessage(message("ÉCRAN", "m%04d".format(i), i.toDouble(), "texte $i"))
            for (i in 0 until Store.releaseOver) s.writeMessage(message("COURTE", "m%04d".format(i), i.toDouble(), "texte $i"))
            for (i in 0 until 1000) s.writeMessage(message("OCCUPÉE", "m%04d".format(i), i.toDouble(), "texte $i"))
        }
        val cold = store(s.dir, s.key)
        val whole = cold.messagesOf("LONGUE")
        assertEquals(1000, whole.size)
        assertEquals(1000, cold.messagesOf("ÉCRAN").size)
        assertEquals(Store.releaseOver, cold.messagesOf("COURTE").size)
        // Chargée parce que ses messages sont relus un par un sans fin : laissée.
        for (i in 0..Store.loadAfter + 1) assertNotNull(cold.message("OCCUPÉE", "m%04d".format(i)))
        assertNotNull(cold.messages["OCCUPÉE"])

        cold.release("ÉCRAN")
        assertNull(cold.messages["LONGUE"])
        assertNull(cold.ordered["LONGUE"])
        assertNotNull(cold.messages["ÉCRAN"])
        assertNotNull(cold.messages["COURTE"])
        assertNotNull(cold.messages["OCCUPÉE"])
        // La fin est restée en mémoire : rien n'est relu pour l'afficher.
        assertEquals(Store.releaseKeep, cold.windows["LONGUE"]?.size)
        val end = cold.recentMessages("LONGUE", Store.releaseKeep)
        assertEquals(whole.takeLast(Store.releaseKeep), end.list)
        assertTrue(end.more)
        assertEquals(whole.takeLast(5), cold.messagesOf("LONGUE", limit = 5))
        assertNull(cold.messages["LONGUE"])
        // Elle suit toujours les écritures, et un vieux message se lit encore.
        cold.writeMessage(message("LONGUE", "neuf", 5000.0, "nouveau"))
        cold.removeMessage("LONGUE", "m0999")
        assertEquals(listOf("m0997", "m0998", "neuf"), ids(cold.recentMessages("LONGUE", 3).list.takeLast(3)))
        assertEquals("texte 3", cold.message("LONGUE", "m0003")?.text)
        // Demander plus que la fin : relu dans SQLite ; tout : rechargée.
        assertEquals(500, cold.recentMessages("LONGUE", 500).list.size)
        assertNull(cold.messages["LONGUE"])
        assertEquals(ids(whole.dropLast(1)) + "neuf", ids(cold.messagesOf("LONGUE")))
        assertNotNull(cold.messages["LONGUE"])
        // Sans discussion à l'écran, toutes les longues sont rendues ; deux fois ne change rien.
        cold.release(null)
        cold.release(null)
        assertNull(cold.messages["LONGUE"])
        assertNull(cold.messages["ÉCRAN"])
        assertNotNull(cold.messages["COURTE"])
        assertEquals(1000, cold.messagesOf("ÉCRAN").size)
        assertEquals(1000, store(s.dir, s.key).messagesOf("LONGUE").size)
    }

    /**
     * Le texte des messages d'une discussion, pour la recherche : lu sans la
     * charger, il suit chaque message écrit ou retiré.
     */
    @Test
    fun testSearchLinesFollowTheMessages() {
        fun texts(s: Store, chat: String): List<String> = s.lines(chat).sortedBy { it.at }.map { it.text }
        for (key in listOf(null, newKey())) {
            val dir = tempDir()
            run {
                val s = store(dir, key)
                s.transaction {
                    for (i in 0 until 100) s.writeMessage(message("AMI", "m%03d".format(i), i.toDouble(), "texte $i"))
                    val photo = message("AMI", "photo", 50.5, "une légende").copy(
                        kind = "image",
                        data = "data:image/jpeg;base64," + "/9j/4AAQ".repeat(5_000),
                        name = "plage.jpg",
                        openAt = 1_234.0,
                    )
                    s.writeMessage(photo)
                    s.writeMessage(message("AUTRE", "x", 1.0, "ailleurs"))
                }
                s.close()
            }
            val s = store(dir, key)
            assertEquals(101, s.lines("AMI").size)
            assertNull(s.messages["AMI"])
            assertNull(s.windows["AMI"])
            val line = assertNotNull(s.lines("AMI").firstOrNull { it.id == "photo" })
            assertEquals(Message.Line(assertNotNull(s.message("AMI", "photo"))), line)
            assertEquals(listOf("AMI", "image", "une légende", "plage.jpg", "WGSEAJNSZJ"), listOf(line.chat, line.kind, line.text, line.name, line.from))
            assertEquals(1_234.0, line.openAt)
            assertEquals(listOf("ailleurs"), texts(s, "AUTRE"))
            assertTrue(s.lines("INCONNUE").isEmpty())
            // Un message modifié, un nouveau, un retiré (au milieu, puis le dernier).
            val edited = assertNotNull(s.message("AMI", "m003")).copy(text = "corrigé")
            s.writeMessage(edited)
            s.writeMessage(message("AMI", "neuf", 500.0, "tout neuf"))
            s.removeMessage("AMI", "m000")
            s.removeMessage("AMI", "neuf")
            s.removeMessage("AMI", "pas là")
            val want = (1 until 100).map { "texte $it" }.toMutableList()
            want[2] = "corrigé"
            want.add(50, "une légende")
            assertEquals(want, texts(s, "AMI"))
            assertEquals(100, s.lines("AMI").map { it.id }.toSet().size)
            assertNull(s.messages["AMI"])
            // Les mêmes lignes qu'en lisant tout.
            val other = store(dir, key)
            assertEquals(s.lines("AMI").sortedBy { it.at }, other.messagesOf("AMI").map { Message.Line(it) })
            assertEquals(s.lines("AMI").sortedBy { it.at }, other.lines("AMI").sortedBy { it.at })
            // Oubliées (l'arrière-plan), vidée, restaurée : relues à la demande.
            s.forgetLines()
            assertTrue(s.linesIn.isEmpty())
            assertEquals(want, texts(s, "AMI"))
            s.deleteMessages("AMI")
            assertTrue(s.lines("AMI").isEmpty())
            s.writeMessage(message("AMI", "après", 600.0, "après"))
            val dump = s.dump()
            s.writeMessage(message("AMI", "oublié", 601.0, "oublié"))
            s.restore(dump)
            assertTrue(s.linesIn.isEmpty())
            assertEquals(listOf("après"), texts(s, "AMI"))
        }
    }

    /**
     * Les dates des messages se lisent dans l'index, sans ouvrir de ligne,
     * et une discussion se vide d'un coup en gardant certains messages.
     */
    @Test
    fun testDatesAndBulkDeleteWithoutLoading() {
        for (key in listOf(null, newKey())) {
            val dir = tempDir()
            run {
                val s = store(dir, key)
                s.transaction {
                    for (i in 0 until 900) s.writeMessage(message("AMI|x", "m%03d".format(i), i.toDouble(), "texte $i"))
                    s.writeMessage(message("AUTRE", "x", 1.0, "ailleurs"))
                }
                // Une ligne illisible a quand même sa date.
                s.run("UPDATE rows SET json = ? WHERE store = 'messages' AND key = ?", listOf("{abîmé", "AMI|x|m010"))
                s.close()
            }
            val s = store(dir, key)
            assertEquals(900, s.datesOf("AMI|x").size)
            assertEquals(listOf("m000", "m001"), s.datesOf("AMI|x").take(2).map { it.first })
            assertEquals(listOf("m897", "m898", "m899"), s.datesOf("AMI|x", after = 896.0).map { it.first })
            assertEquals(listOf(897.0, 898.0, 899.0), s.datesOf("AMI|x", after = 896.0).map { it.second })
            assertTrue(s.datesOf("AMI|x", after = 5_000.0).isEmpty())
            assertTrue(s.datesOf("INCONNUE").isEmpty())
            assertNull(s.messages["AMI|x"])
            // Chargée : les mêmes (sauf la ligne illisible, qui n'est pas en mémoire).
            val loaded = store(dir, key)
            assertEquals(899, loaded.messagesOf("AMI|x").size)
            assertEquals(listOf("m897", "m898", "m899"), loaded.datesOf("AMI|x", after = 896.0).map { it.first })
            assertEquals(899, loaded.datesOf("AMI|x").size)

            // Tout part sauf trois messages ; l'autre discussion n'est pas touchée.
            s.recentMessages("AMI|x", 50)
            s.starredIds("AMI|x")
            s.lines("AMI|x")
            s.deleteMessages("AMI|x", keeping = setOf("m005", "m500", "m899", "pas là"))
            assertEquals(listOf("m005", "m500", "m899"), ids(s.messagesOf("AMI|x")))
            assertEquals(3, s.lines("AMI|x").size)
            assertEquals(3, s.datesOf("AMI|x").size)
            assertNull(s.message("AMI|x", "m006"))
            assertEquals(1, s.messagesOf("AUTRE").size)
            assertEquals(listOf("m005", "m500", "m899"), ids(store(dir, key).messagesOf("AMI|x")))
            // Rien à garder : tout part.
            s.deleteMessages("AMI|x", keeping = emptySet())
            assertTrue(s.messagesOf("AMI|x").isEmpty())
            assertTrue(store(dir, key).messagesOf("AMI|x").isEmpty())
            // Beaucoup à garder (plus que n'en prend une requête).
            s.transaction {
                for (i in 0 until 700) s.writeMessage(message("GROS", "m%03d".format(i), i.toDouble(), "texte $i"))
            }
            val cold = store(dir, key)
            val keep = (0 until 600).map { "m%03d".format(it) }.toSet()
            cold.deleteMessages("GROS", keeping = keep)
            assertEquals(keep, ids(cold.messagesOf("GROS")).toSet())
            assertEquals(600, store(dir, key).messagesOf("GROS").size)
        }
    }

    /**
     * Les messages les plus récents d'abord, toutes discussions mêlées, lus
     * un à un tant qu'on en veut ; et la sauvegarde sans les messages.
     */
    @Test
    fun testNewestMessageRowsStopWhenAsked() {
        for (key in listOf(null, newKey())) {
            val s = store(tempDir(), key)
            s.transaction {
                for (i in 0 until 50) {
                    s.writeMessage(message("A", "a$i", (i * 2).toDouble(), "A $i"))
                    s.writeMessage(message("B", "b$i", (i * 2 + 1).toDouble(), "B $i"))
                }
            }
            s.write("kv", "profile", Profile(name = "Léa"))
            // Une ligne abîmée est rendue telle quelle, comme dans la sauvegarde.
            s.run("UPDATE rows SET json = ? WHERE store = 'messages' AND key = ?", listOf("{abîmé", "B|b48"))
            val seen = ArrayList<Triple<String, String, Double>>()
            s.newestMessageRows { k, json, chat, at ->
                assertEquals(k, JSON.decode(Message, json)?.key ?: k)
                if (k == "B|b48") assertEquals("{abîmé", json)
                seen.add(Triple(k, chat, at))
                seen.size < 5
            }
            assertEquals(listOf("B|b49", "A|a49", "B|b48", "A|a48", "B|b47"), seen.map { it.first })
            assertEquals("B", seen.first().second)
            assertEquals(99.0, seen.first().third)
            // Tout, si on ne s'arrête pas : les mêmes lignes que la sauvegarde.
            var all = 0
            s.newestMessageRows { _, _, _, _ ->
                all += 1
                true
            }
            assertEquals(s.dump().rowsOf("messages"), all)
            // Sans les messages : le reste est là.
            val light = s.dump(messages = false)
            assertEquals(0, light.rowsOf("messages"))
            assertEquals(1, light.rowsOf("kv"))
            assertEquals(1, s.dump().rowsOf("kv"))
        }
    }

    /** La sauvegarde garde chaque ligne, dans l'ordre, et se restaure. */
    @Test
    fun testDumpKeepsEveryRow() {
        val s = store(tempDir(), newKey())
        for (i in 0 until 300) s.writeMessage(message("AMI", "m$i", i.toDouble(), "texte $i"))
        s.write("kv", "profile", Profile(name = "Léa"))
        val dump = s.dump()
        assertEquals(300, dump.rowsOf("messages"))
        assertEquals(1, dump.rowsOf("kv"))
        assertEquals(0, dump.rowsOf("contacts"))
        val other = store(tempDir())
        other.writeMessage(message("AMI", "avant", 1.0, "effacé par la restauration"))
        other.messagesOf("AMI")
        other.restore(dump)
        assertEquals(300, other.messagesOf("AMI").size)
        assertNull(other.message("AMI", "avant"))
        assertEquals("Léa", other.read("kv", "profile", Profile)?.name)
    }
}

/**
 * Un membre (ou un ancien membre) ne prend pas un groupe en main ; comme
 * `only_admins_change_a_group` du navigateur. (Les tests de ce groupe qui
 * passent par le moteur, `testKeepArchived`, `testGroupDescription` et la
 * fin de `testOnlyAdminsWrite`, viendront avec lui.)
 */
class GroupRulesTests {
    fun group(members: List<String>, admins: List<String>): Chat =
        Chat(id = "g:g1", group = true, name = "Les amis", members = members, admins = admins, v = 5)

    fun info(c: Chat, v: Long): GroupInfo = GroupInfo(id = "g1", name = c.name, members = c.members, admins = c.admins, avatar = c.avatar, v = v)

    @Test
    fun testOnlyAdminsChangeAGroup() {
        val c = group(listOf("A", "B", "C"), listOf("A"))
        val byAdmin = info(c, 6).let { it.copy(members = it.members + "D") }
        assertTrue(c.accepts("A", byAdmin))
        val coup = info(c, 6).copy(admins = listOf("B"))
        assertFalse(c.accepts("B", coup))
        assertFalse(c.accepts("Z", byAdmin))
        assertFalse(c.accepts("A", info(c, Long.MAX_VALUE)))
        assertFalse(c.accepts("A", info(c, 5)))
        var leave = info(c, 6).copy(members = listOf("A", "C"))
        assertTrue(c.accepts("B", leave))
        leave = leave.copy(name = "À moi")
        assertFalse(c.accepts("B", leave))
        // … sans toucher à la description (absente : inchangée).
        leave = leave.copy(name = c.name, desc = "Mon groupe")
        assertFalse(c.accepts("B", leave))
        leave = leave.copy(desc = null)
        assertTrue(c.accepts("B", leave))
        // … ni à qui peut écrire.
        leave = leave.copy(adminsOnly = true)
        assertFalse(c.accepts("B", leave))
        leave = leave.copy(adminsOnly = null)
        assertTrue(c.accepts("B", leave))
        val last = info(c, 6).copy(members = listOf("B", "C"), admins = listOf("B"))
        assertTrue(c.accepts("A", last))
    }

    /**
     * « Seuls les admins peuvent écrire » : comme `only_admins_write_when_asked`
     * du navigateur (la partie sans le moteur).
     */
    @Test
    fun testOnlyAdminsWrite() {
        var g = group(listOf("A", "B"), listOf("A"))
        assertTrue(g.canWrite("B"))
        g = g.copy(adminsOnly = true)
        assertTrue(g.canWrite("A"))
        assertFalse(g.canWrite("B"))
        g = g.copy(left = true)
        assertFalse(g.canWrite("A"))
        assertTrue(Chat(id = "X").canWrite("B"))
    }

    /**
     * Les actus d'un contact en sourdine : le même champ que le navigateur
     * (`status_muted`), absent des anciens contacts.
     */
    @Test
    fun testMutedStatusesField() {
        assertEquals(false, JSON.decode(Contact, """{"code":"WGSEAJNSZJ"}""")?.statusMuted)
        var c = Contact(code = "WGSEAJNSZJ")
        assertFalse(JSON.encode(c).contains("status_muted"))
        c = c.copy(statusMuted = true)
        assertTrue(JSON.encode(c).contains("\"status_muted\":true"))
        assertEquals(true, JSON.decode(Contact, JSON.encode(c))?.statusMuted)
    }
}
