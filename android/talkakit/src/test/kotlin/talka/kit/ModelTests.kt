// Le modèle seul, comme le navigateur : le nom d'une pièce jointe
// enregistrée, les tailles, le stockage, les mentions, la fiche d'une
// discussion (photos, documents, liens). Les mêmes vérifications que
// l'iPhone (`SaveNameTests.swift`, et la partie sans moteur de
// `MentionTests.swift`).

package talka.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveNameTests {
    private fun message(kind: String, name: String = "", mime: String = "", data: String = "") =
        Message("c", "m", "x", 0.0, Content()).copy(kind = kind, name = name, mime = mime, data = data)

    @Test
    fun testSaveNameFollowsTheRealType() {
        assertEquals("devis.pdf", message("file", name = "devis.pdf").saveName)
        assertEquals("photo-talka.jpg", message("image", data = "data:image/jpeg;base64,AA").saveName)
        assertEquals("photo-talka.png", message("image", mime = "image/png").saveName)
        assertEquals("vocal-talka.m4a", message("voice", data = "data:audio/mp4;base64,AA").saveName)
        assertEquals("vocal-talka.webm", message("voice", mime = "audio/webm;codecs=opus").saveName)
        assertEquals("vocal-talka.webm", message("voice").saveName)
        assertEquals("fichier-talka", message("file").saveName)
    }

    /** Sous le nom d'un document : l'extension seulement s'il y en a une vraie. */
    @Test
    fun testFileDetailShowsARealExtensionOnly() {
        fun f(name: String) = Message("c", "m", "x", 0.0, Content()).copy(name = name)
        assertEquals("12 Ko · PDF", f("devis.pdf").fileDetail("12 Ko"))
        assertEquals("3 Mo · GZ", f("archive.tar.gz").fileDetail("3 Mo"))
        assertEquals("1 Ko", f("README").fileDetail("1 Ko"))
        assertEquals("1 Ko", f("").fileDetail("1 Ko"))
        assertEquals("1 Ko", f(".profile").fileDetail("1 Ko"))
        assertEquals("1 Ko", f("fin.").fileDetail("1 Ko"))
        assertEquals("1 Ko", f("v1.2 compte rendu final").fileDetail("1 Ko"))
        assertEquals("1 Ko", f("v1.2 fin").fileDetail("1 Ko"))
    }

    /** Une taille s'affiche comme dans le navigateur (par 1024, virgule). */
    @Test
    fun testSizesLikeTheBrowser() {
        assertEquals("0 o", formatBytes(0.0))
        assertEquals("25 o", formatBytes(25.0))
        assertEquals("1023 o", formatBytes(1023.0))
        assertEquals("1,0 Ko", formatBytes(1024.0))
        assertEquals("11,7 Ko", formatBytes(12_000.0))
        assertEquals("1,5 Mo", formatBytes(1_572_864.0))
        assertEquals("0 o", formatBytes(-3.0))
        // Une égalité exacte s'arrondit au pair, comme `printf` et le navigateur.
        assertEquals("1,2 Ko", formatBytes(1280.0))
    }

    /** « Gérer le stockage » compte comme le navigateur. */
    @Test
    fun testStorageCountsTheAttachments() {
        var n = 0
        fun m(kind: String, data: String, size: Double, deleted: Boolean = false) =
            Message("c", "m${n++}", "x", 0.0, Content()).copy(kind = kind, data = data, size = size, deleted = deleted)
        val st = Storage.of(
            listOf(
                m("image", "data:image/jpeg;base64,AAAAAAAA", 0.0),
                m("image", "data:image/jpeg;base64,AAAA", 0.0),
                m("file", "data:application/pdf;base64,AA==", 1000.0),
                m("voice", "data:audio/webm;base64,AAAA", 500.0),
                m("voice", "data:audio/webm;base64,AAAA", 500.0, deleted = true),
                m("text", "", 0.0),
            ),
        )
        assertEquals(Storage(bytes = 6.0 + 3 + 1000 + 500, photos = 2, files = 1, voices = 1), st)
        assertEquals("2 photos · 1 document · 1 vocal", st.detail)
        assertEquals("", Storage().detail)
    }

    /** Une durée absurde reçue d'un autre appareil ne fait pas planter. */
    @Test
    fun testDurationNeverCrashes() {
        assertEquals("1:05", formatDuration(65.0))
        assertEquals("0:00", formatDuration(0.4))
        assertEquals("0:00", formatDuration(-5.0))
        assertEquals("0:00", formatDuration(Double.NaN))
        assertEquals("0:00", formatDuration(Double.POSITIVE_INFINITY))
        assertEquals("5999:59", formatDuration(1e300))
    }
}

class MentionTests {
    @Test
    fun testQueryAndFound() {
        assertEquals("Lé", Mentions.query("Salut @Lé"))
        assertEquals("", Mentions.query("@"))
        assertNull(Mentions.query("écris à moi@exemple.fr"), "pas une adresse")
        assertNull(Mentions.query("Salut @Léa\nça va"))
        assertNull(Mentions.query("Salut"))
        val members = listOf("AAAAAAAAAA" to "Léa Martin", "BBBBBBBBBB" to "Bob", "CCCCCCCCCC" to "")
        assertEquals(listOf("AAAAAAAAAA", "BBBBBBBBBB"), Mentions.found("Merci @lea martin et @Bob ! @Bob", members))
        assertTrue(Mentions.found("Bonjour Bob", members).isEmpty())
        assertTrue(Mentions.found("@ tout le monde", members).isEmpty())
        // Un ancien client ignore le champ ; un message sans mention ne
        // l'écrit pas.
        val c = JSON.decode(Content, """{"kind":"text","text":"x"}""")!!
        assertTrue(c.mentions.isEmpty())
        assertFalse(JSON.encode(c).contains("mentions"))
    }

    @Test
    fun testGroupsInCommonAreSharedAndSorted() {
        fun g(id: String, name: String, members: List<String>, left: Boolean = false) =
            Chat(id).copy(group = true, name = name, members = members, left = left)
        val chats = listOf(
            g("g:1", "Voisins", listOf("MOI", "LEA")),
            g("g:2", "amis", listOf("MOI", "LEA", "BOB")),
            g("g:3", "Travail", listOf("MOI", "BOB")),
            g("g:4", "Ancien", listOf("MOI", "LEA"), left = true),
            g("g:5", "Sans moi", listOf("LEA", "BOB")),
            Chat("LEA"),
        )
        assertEquals(listOf("g:2", "g:1"), Chat.groupsInCommon(chats, "MOI", "LEA").map { it.id })
        assertTrue(Chat.groupsInCommon(chats, "MOI", "ZOE").isEmpty())
    }

    /**
     * La fiche montre les photos de la plus récente à la plus ancienne, sans
     * les supprimées ni une capsule encore scellée (comme le navigateur).
     */
    @Test
    fun testPhotosNewestFirstWithoutHiddenOnes() {
        fun m(id: String, kind: String, from: String, deleted: Boolean = false, openAt: Double? = null) =
            Message("LEA", id, from, 0.0, Content()).copy(kind = kind, data = "data:image/jpeg;base64,AA==", deleted = deleted, openAt = openAt)
        val msgs = listOf(
            m("1", "image", "LEA"),
            m("2", "text", "LEA"),
            m("3", "image", "LEA", deleted = true),
            m("4", "image", "LEA", openAt = 5_000.0),
            m("5", "image", "MOI", openAt = 5_000.0),
            m("6", "image", "MOI"),
        )
        assertEquals(listOf("6", "5", "1"), Message.photos(msgs, "MOI", 1_000.0).map { it.id })
        assertEquals(listOf("6", "5", "4", "1"), Message.photos(msgs, "MOI", 9_000.0).map { it.id })
    }

    /** Les documents de la fiche : les fichiers seulement, avec leur contenu. */
    @Test
    fun testDocumentsAreTheFilesOnly() {
        fun m(id: String, kind: String, data: String) = Message("LEA", id, "LEA", 0.0, Content()).copy(kind = kind, data = data)
        val msgs = listOf(
            m("1", "file", "data:application/pdf;base64,AA=="),
            m("2", "image", "data:image/jpeg;base64,AA=="),
            m("3", "file", ""),
            m("4", "voice", "data:audio/webm;base64,AA=="),
            m("5", "file", "data:text/plain;base64,AA=="),
        )
        assertEquals(listOf("5", "1"), Message.documents(msgs, "MOI", 0.0).map { it.id })
    }

    /** « Derniers messages » compte les messages des autres arrivés depuis. */
    @Test
    fun testNewMessagesBelowCountTheOthersOnly() {
        fun m(id: String, from: String) = Message("c", id, from, 0.0, Content())
        val msgs = listOf(m("1", "LEA"), m("2", "MOI"), m("3", "LEA"), m("4", "system"), m("5", "MOI"), m("6", "BOB"))
        assertEquals(2, Message.newBelow(msgs, "2", "MOI"))
        assertEquals(0, Message.newBelow(msgs, "6", "MOI"))
        assertEquals(0, Message.newBelow(msgs, "inconnu", "MOI"))
    }

    /**
     * Les liens de la fiche : la règle des bulles, chacun une fois, le plus
     * récent d'abord (comme le navigateur).
     */
    @Test
    fun testLinksLikeTheBubbles() {
        assertEquals(
            listOf("https://talka.example/a?b=1", "http://x.org/y"),
            Message.findLinks("Regarde https://talka.example/a?b=1, et (http://x.org/y)."),
        )
        assertEquals(listOf("https://a.fr"), Message.findLinks("«https://a.fr»"))
        assertTrue(Message.findLinks("pas un lien : xhttps://a.fr ni https://").isEmpty())
        // Le rejet rapide des textes sans « http » ne change rien à ce qui est trouvé.
        assertTrue(Message.findLinks("").isEmpty())
        assertTrue(Message.findLinks("Un message sans lien, htt p, h t t p, HTTP://MAJUSCULES.FR").isEmpty())
        assertFalse(Message.hasHTTP("htt hthtp ptth HTTP"))
        assertTrue(Message.hasHTTP("hhttp"))
        assertTrue(Message.hasHTTP("hthttp://"))
        assertEquals(listOf("http://b.fr"), Message.findLinks("hhttp://a.fr http://b.fr"))
        assertEquals(
            listOf("https://é.fr/ça?x=1", "http://c.fr/d"),
            Message.findLinks("hé ! https://é.fr/ça?x=1. Puis “http://c.fr/d” et <https://e.fr>"),
        )
        assertEquals(listOf("https://a.fr", "http://b.fr", "https://c.fr"), Message.findLinks("https://a.fr\nhttp://b.fr\thttps://c.fr"))
        assertEquals(listOf("https://b.fr"), Message.findLinks("🥦https://a.fr 🥦 https://b.fr"))
        fun m(id: String, from: String, text: String, deleted: Boolean = false, openAt: Double? = null) =
            Message("LEA", id, from, 0.0, Content()).copy(text = text, deleted = deleted, openAt = openAt)
        val msgs = listOf(
            m("1", "LEA", "https://a.fr"),
            m("2", "LEA", "https://b.fr https://a.fr"),
            m("3", "LEA", "https://supprime.fr", deleted = true),
            m("4", "LEA", "https://capsule.fr", openAt = 5_000.0),
            m("5", "system", "https://systeme.fr"),
        )
        val got = Message.links(msgs, "MOI", 1_000.0)
        assertEquals(listOf("https://b.fr", "https://a.fr"), got.map { it.url })
        assertEquals(listOf("2", "2"), got.map { it.message.id })
    }

    /** La recherche ignore accents et majuscules, et ne trahit pas une capsule. */
    @Test
    fun testSearchIgnoresAccentsAndSealedCapsules() {
        fun m(id: String, text: String, openAt: Double? = null) = Message("c", id, "LEA", 0.0, Content()).copy(text = text, openAt = openAt)
        val found = Message.matching(listOf(listOf(m("1", "Rendez-vous à la Crêperie"), m("2", "crêpe", openAt = 5_000.0), m("3", "rien"))), "CREPE", "MOI", 1_000.0)
        assertEquals(listOf("1"), found.found.map { it.id })
        assertEquals(5_000.0, found.nextOpen)
        assertTrue(Message.matching(listOf(listOf(m("1", "x"))), "", "MOI", 0.0).found.isEmpty())
        assertTrue(m("b", "Un brocoli !").summonsBrocoli("MOI", 0.0))
        assertFalse(m("b", "[[BROCOLIS:v1:AAAA]]").summonsBrocoli("MOI", 0.0), "un jeton scellé ne se trahit pas")
    }
}
