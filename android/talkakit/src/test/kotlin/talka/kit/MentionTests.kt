// Les mentions dans les groupes (« @Léa ») : comme `model::mentions` du
// navigateur, et la réception par le moteur ; la fiche d'une discussion
// (groupes en commun, photos, documents, liens) ; la présence. Les mêmes
// tests que l'iPhone (`MentionTests.swift`).

package talka.kit

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MentionTests {
    private val phones = Phones()

    @AfterTest
    fun cleanUp() = phones.close()

    /** Mentionné dans un groupe : « @ » jusqu'à la lecture ; on ne garde que les membres du groupe. */
    @Test
    fun testMentionMarksChatUntilRead() = phones.run {
        val e = phones.phone("Léa")
        val x = Identity.create()
        val stranger = Identity.create().code
        val grp = GroupInfo(id = "mention", name = "Les amis", members = listOf(x.code, e.me.code), admins = listOf(x.code), avatar = "", v = 1)
        var content = Content(text = "Salut @Léa", mentions = listOf(e.me.code, stranger))
        e.handle(x.code, Wire(Body.Msg("m1", nowMs(), "", content), grp = grp), nowMs(), live = true)
        assertEquals(true, e.chat("g:mention")?.mentioned)
        assertEquals(listOf(e.me.code), e.messages("g:mention").last().mentions)
        assertEquals("Léa", e.mentionLabel(e.me.code))
        e.markRead("g:mention")
        assertEquals(false, e.chat("g:mention")?.mentioned)

        // Sans nous mentionner : pas de « @ ».
        content = content.copy(mentions = listOf(x.code))
        e.handle(x.code, Wire(Body.Msg("m2", nowMs(), "", content), g = "mention"), nowMs(), live = true)
        assertEquals(1, e.chat("g:mention")?.unread)
        assertEquals(false, e.chat("g:mention")?.mentioned)
    }

    /**
     * Un message reçu : la discussion gardée dans la mémoire (une seule
     * écriture) porte les non-lus, la mention et le dernier message ; une
     * base rouverte les retrouve.
     */
    @Test
    fun testIncomingMessageIsKeptWithItsChat() = phones.run {
        val e = phones.phone("Léa")
        val store = e.store
        val x = Identity.create()
        val grp = GroupInfo(id = "recu", name = "Les amis", members = listOf(x.code, e.me.code), admins = listOf(x.code), avatar = "", v = 1)
        // Un premier groupe pour roder la JVM (le chargement des classes) : la
        // discussion créée ensuite l'est dans la milliseconde, comme sur
        // l'iPhone (son heure de création compte dans `lastAt`).
        e.handle(x.code, Wire(Body.Msg("r0", nowMs(), "", Content(text = "Rodage")), grp = grp.copy(id = "rodage")), nowMs(), live = true)
        val t = nowMs()
        val first = Content(text = "Salut @Léa", mentions = listOf(e.me.code))
        e.handle(x.code, Wire(Body.Msg("m1", t, "", first), grp = grp), t, live = true)
        e.handle(x.code, Wire(Body.Msg("m2", t + 1, "", Content(text = "Tu viens ?")), g = "recu"), t + 1, live = true)
        for (chat in listOf(store.read("chats", "g:recu", Chat), phones.reopen(store.dir).read("chats", "g:recu", Chat))) {
            assertEquals(2, chat?.unread)
            assertEquals(true, chat?.mentioned)
            assertEquals("m2", chat?.last?.id)
            assertEquals("Tu viens ?", chat?.last?.text)
            assertEquals(t + 1, chat?.lastAt)
        }
        assertEquals(e.chat("g:recu"), store.read("chats", "g:recu", Chat))
        // Un rejeu ne compte pas deux fois.
        e.handle(x.code, Wire(Body.Msg("m2", t + 1, "", Content(text = "Tu viens ?")), g = "recu"), t + 2, live = true)
        assertEquals(2, store.read("chats", "g:recu", Chat)?.unread)
        // Sous nos yeux (la discussion ouverte, Talka au premier plan) : lu.
        e.activeChat = "g:recu"
        e.foreground = true
        e.handle(x.code, Wire(Body.Msg("m3", t + 3, "", Content(text = "Alors ?")), g = "recu"), t + 3, live = true)
        val seen = store.read("chats", "g:recu", Chat)
        assertEquals(2, seen?.unread)
        assertEquals("m3", seen?.last?.id)
        assertEquals(true, e.store.message("g:recu", "m3")?.readByMe)
    }

    /**
     * « X vous a ajouté » reste avant le premier message du groupe, même
     * quand l'annonce a quitté l'expéditeur après qu'il a écrit ce message
     * (l'annonce est datée par le relais, le message par l'expéditeur).
     */
    @Test
    fun testAddedNoticeStaysFirst() = phones.run {
        val e = phones.phone("Léa")
        val x = Identity.create()
        val grp = GroupInfo(id = "ordre", name = "Les amis", members = listOf(x.code, e.me.code), admins = listOf(x.code), avatar = "", v = 1)
        val t = nowMs()
        e.handle(x.code, Wire(Body.Group("create", ""), grp = grp), t, live = true)
        e.handle(x.code, Wire(Body.Msg("m1", t - 800, "", Content(text = "Bienvenue")), g = "ordre"), t + 50, live = true)
        val list = e.messages("g:ordre")
        assertEquals(listOf("system", "text"), list.map { it.kind })
        assertTrue(list[1].at > list[0].at)
    }

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

class PresenceTests {
    private val phones = Phones()

    @AfterTest
    fun cleanUp() = phones.close()

    /** Hors ligne, plus personne n'est « en ligne » ; les « vu à » restent. */
    @Test
    fun testOfflineForgetsWhoIsOnline() = phones.run {
        val e = phones.phone("Léa")
        e.relay.onPresence?.invoke("AAAAAAAAAA", true, null)
        e.relay.onPresence?.invoke("BBBBBBBBBB", false, 1_000.0)
        assertEquals(true, e.presence["AAAAAAAAAA"]?.online)
        e.onNet(Relay.State.offline)
        assertNull(e.presence["AAAAAAAAAA"])
        assertEquals(1_000.0, e.presence["BBBBBBBBBB"]?.seen)
    }
}
