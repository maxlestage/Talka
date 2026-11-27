// « Vider la discussion » : tout, ou tout sauf les messages favoris (comme
// WhatsApp), et l'ordre partagé avec nos autres appareils. Les mêmes tests
// que l'iPhone (`ClearChatTests.swift`).

package talka.kit

import kotlinx.coroutines.delay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClearChatTests {
    private val phones = Phones()

    @AfterTest
    fun cleanUp() = phones.close()

    private fun phone() = phones.phone("Léa")

    private fun cold(e: Engine) = phones.engine(phones.reopen(e.store.dir))

    @Test
    fun testClearingKeepsStarredMessagesWhenAsked() = phones.run {
        val e = phone()
        val c = e.ensureDirectChat("K7Q2MX9PRT")
        val keep = assertNotNull(e.send(c.id, Content(text = "À garder")))
        e.send(c.id, Content(text = "À effacer"))
        assertFalse(e.hasStarred(c.id))
        e.toggleStar(c.id, keep.id)
        assertTrue(e.hasStarred(c.id))

        e.clearChat(c.id, keepStarred = true)
        assertEquals(listOf("À garder"), e.messages(c.id).map { it.text })
        assertEquals("À garder", e.chats[c.id]?.last?.text)

        e.clearChat(c.id)
        assertTrue(e.messages(c.id).isEmpty())
        assertNull(e.chats[c.id]?.last)
    }

    /** Un message supprimé pour tous n'est plus important (il n'a plus rien à garder) ; la liste d'une discussion ne montre que les siens. */
    @Test
    fun testDeletedMessagesLeaveTheStarredList() = phones.run {
        val e = phone()
        val c = e.ensureDirectChat("K7Q2MX9PRT")
        val other = e.ensureDirectChat("WGSEAJNSZJ")
        val a = assertNotNull(e.send(c.id, Content(text = "Un")))
        val b = assertNotNull(e.send(c.id, Content(text = "Deux")))
        val o = assertNotNull(e.send(other.id, Content(text = "Ailleurs")))
        for ((chat, id) in listOf(c.id to a.id, c.id to b.id, other.id to o.id)) e.toggleStar(chat, id)
        assertEquals(listOf("Deux", "Un"), e.starred(c.id).map { it.text })
        assertEquals(3, e.starred().size)

        e.delete(c.id, b.id, forEveryone = true)
        assertEquals(listOf("Un"), e.starred(c.id).map { it.text })
        assertEquals(2, e.starred().size)
        // Vider en gardant les importants : le message supprimé part aussi.
        e.clearChat(c.id, keepStarred = true)
        assertEquals(listOf(a.id), e.messages(c.id).map { it.id })
    }

    /** Les vieux messages s'effacent sauf les importants ; une étoile restée sur un message supprimé pour tous (une version d'avant) ne le garde pas. */
    @Test
    fun testOldDeletedMessagesGoEvenIfStarred() = phones.run {
        val now = 1_800_000_000_000.0
        var m = Message("c", "m", "WGSEAJNSZJ", now - 400 * 86_400_000.0, Content(text = "Vieux"))
        assertTrue(m.tooOld(keepDaysDefault, now))
        m = m.copy(starred = true)
        assertFalse(m.tooOld(keepDaysDefault, now))
        m = m.copy(deleted = true)
        assertTrue(m.tooOld(keepDaysDefault, now))
    }

    /** Chercher parmi les messages importants : le texte (accents et majuscules ignorés), ou le nom de la discussion. */
    @Test
    fun testSearchInStarredMessages() = phones.run {
        val e = phone()
        val c = e.ensureDirectChat("K7Q2MX9PRT")
        val a = assertNotNull(e.send(c.id, Content(text = "Rendez-vous à l'École")))
        val b = assertNotNull(e.send(c.id, Content(text = "Le code du portail")))
        for (m in listOf(a, b)) e.toggleStar(c.id, m.id)
        val all = e.starred()
        assertEquals(listOf(a.id), e.starred(all, "ecole").map { it.id })
        assertEquals(listOf(b.id), e.starred(all, "PORTAIL").map { it.id })
        assertEquals(2, e.starred(all, "  ").size)
        assertEquals(2, e.starred(all, "K7Q2M").size, "le nom de la discussion compte")
        assertTrue(e.starred(all, "piscine").isEmpty())
    }

    // ----- Les tombes (messages effacés qui ne doivent pas revenir) -----

    /** Les tombes d'avant (lues et redécodées à chaque fois), pour comparer. */
    private fun oldBury(list: MutableList<Engine.Tomb>, msgs: List<Pair<String, Double>>, now: Double) {
        list.removeAll { now - it.at >= Engine.tombWindow }
        for ((id, at) in msgs) {
            if (now - at < Engine.tombWindow && list.none { it.id == id }) list.add(Engine.Tomb(id, at))
        }
        if (list.size > 20_000) list.subList(0, list.size - 20_000).clear()
    }

    private fun stored(e: Engine, chat: String): List<String> = (e.store.read("kv", "tombes:$chat", Engine.TombList) ?: emptyList()).map { it.id }

    /** Un tirage pseudo-aléatoire, le même que celui de l'iPhone. */
    private class Seeded(var seed: ULong) {
        fun next(n: Int): Int {
            seed = seed * 6364136223846793005uL + 1442695040888963407uL
            return ((seed shr 33) % n.toULong()).toInt()
        }
    }

    /**
     * Gardées décodées en mémoire, les tombes restent exactement celles
     * qu'écrivait la version d'avant : ajouts, doublons, messages trop
     * vieux pour être enterrés, et ce que `buried` répond.
     */
    @Test
    fun testTombsStayTheSameAsBefore() = phones.run {
        val e = phone()
        val ref = ArrayList<Engine.Tomb>()
        val r = Seeded(7uL)
        assertFalse(e.buried("AMI", "m0"))
        e.bury("AMI", emptyList())
        assertNull(e.store.readRaw("kv", "tombes:AMI"))
        for (step in 0 until 200) {
            val now = nowMs()
            // De 0 à 3 messages, certains déjà enterrés, certains trop vieux.
            val msgs = (0 until r.next(4)).map {
                "m${r.next(60)}" to now - (if (r.next(5) == 0) Engine.tombWindow + 1000 else r.next(1000).toDouble())
            }
            e.bury("AMI", msgs)
            oldBury(ref, msgs, now)
            assertEquals(ref.map { it.id }, stored(e, "AMI"), "étape $step")
            val probe = "m${r.next(60)}"
            assertEquals(ref.any { it.id == probe }, e.buried("AMI", probe), "étape $step")
            assertFalse(e.buried("AUTRE", probe))
        }
        assertFalse(ref.isEmpty())
    }

    /**
     * Une tombe de plus de 31 jours s'en va au ménage, et la ligne gardée
     * fait foi : changée sans passer par `bury` (une restauration), elle
     * est relue.
     */
    @Test
    fun testOldTombsLeaveAndTheStoredRowWins() = phones.run {
        val e = phone()
        val now = nowMs()
        e.store.writeRaw(
            "kv", "tombes:AMI",
            Engine.Tomb.list(listOf(Engine.Tomb("vieux", now - Engine.tombWindow - 60_000), Engine.Tomb("récent", now - 60_000))),
        )
        assertTrue(e.buried("AMI", "vieux"))
        assertTrue(e.buried("AMI", "récent"))
        // Le ménage, sans rien à enterrer : la vieille part.
        e.bury("AMI", emptyList())
        assertEquals(listOf("récent"), stored(e, "AMI"))
        assertFalse(e.buried("AMI", "vieux"))
        // Rien à faire : la ligne n'est pas réécrite.
        val before = e.store.readRaw("kv", "tombes:AMI")
        e.bury("AMI", emptyList())
        e.bury("AMI", listOf("récent" to now - 60_000))
        assertEquals(before, e.store.readRaw("kv", "tombes:AMI"))
        // La ligne change sous nos pieds.
        e.store.writeRaw("kv", "tombes:AMI", Engine.Tomb.list(listOf(Engine.Tomb("autre", now))))
        assertTrue(e.buried("AMI", "autre"))
        assertFalse(e.buried("AMI", "récent"))
        e.bury("AMI", listOf("encore" to now))
        assertEquals(listOf("autre", "encore"), stored(e, "AMI"))
        // La dernière tombe partie, la ligne aussi.
        e.store.writeRaw("kv", "tombes:AMI", Engine.Tomb.list(listOf(Engine.Tomb("vieux", now - Engine.tombWindow - 60_000))))
        e.bury("AMI", emptyList())
        assertNull(e.store.readRaw("kv", "tombes:AMI"))
        assertFalse(e.buried("AMI", "vieux"))
        e.store.remove("kv", "tombes:AMI")
        assertFalse(e.buried("AMI", "autre"))
    }

    /**
     * Une tombe de plus s'écrit au bout de la ligne gardée, sans réencoder
     * les autres : la ligne reste celle qu'on aurait réécrite en entier
     * (identifiants, dates, ordre), doublons d'un même envoi compris, et
     * une ligne d'une autre forme est réécrite comme avant.
     */
    @Test
    fun testANewTombIsAppendedToTheStoredRow() = phones.run {
        val e = phone()
        val now = nowMs()
        var ref = ArrayList<Engine.Tomb>()
        fun check(label: String) {
            val row = e.store.read("kv", "tombes:AMI", Engine.TombList) ?: emptyList()
            assertEquals(ref.map { it.id }, row.map { it.id }, label)
            assertEquals(ref.map { it.at }, row.map { it.at }, label)
            for (tomb in ref) assertTrue(e.buried("AMI", tomb.id), label)
        }
        val steps: List<List<Pair<String, Double>>> = listOf(
            listOf("a" to now - 5000.5),
            listOf("b" to now - 4000, "b" to now - 3000, "a" to now, "c \"é\"\\" to now - 2000.25),
            listOf("trop vieux" to now - Engine.tombWindow - 1000),
            listOf("d" to now - 1),
            (0 until 300).map { "m$it" to now - it.toDouble() },
            listOf("m7" to now, "e" to now - 10),
        )
        for ((i, msgs) in steps.withIndex()) {
            val before = e.store.readRaw("kv", "tombes:AMI")
            e.bury("AMI", msgs)
            oldBury(ref, msgs, nowMs())
            check("étape $i")
            // Rien de neuf : la ligne n'est pas réécrite.
            if (i == 2) assertEquals(before, e.store.readRaw("kv", "tombes:AMI"))
        }
        assertEquals(305, ref.size)
        // La ligne fait foi : relue par un autre moteur, elle dit la même chose.
        val again = phones.engine(e.store)
        assertTrue(again.buried("AMI", "c \"é\"\\"))
        assertTrue(again.buried("AMI", "m299"))
        assertFalse(again.buried("AMI", "trop vieux"))
        // Une ligne écrite autrement (des blancs autour) : réécrite en entier.
        e.store.writeRaw("kv", "tombes:AMI", " [ {\"id\":\"x\",\"at\":${now - 100}} ] \n")
        ref = arrayListOf(Engine.Tomb("x", now - 100))
        e.bury("AMI", listOf("y" to now - 50))
        oldBury(ref, listOf("y" to now - 50), nowMs())
        check("ligne d'une autre forme")
        e.bury("AMI", listOf("z" to now - 25))
        oldBury(ref, listOf("z" to now - 25), nowMs())
        check("puis au bout")
        assertEquals(listOf("x", "y", "z"), ref.map { it.id })
    }

    /** Au plus 20 000 tombes par discussion : les plus anciennes sortent. */
    @Test
    fun testTombsAreCapped() = phones.run {
        val e = phone()
        val now = nowMs()
        e.bury("AMI", (0 until 20_010).map { "m$it" to now - 1000 })
        assertEquals(20_000, stored(e, "AMI").size)
        assertFalse(e.buried("AMI", "m9"))
        assertTrue(e.buried("AMI", "m10"))
        assertTrue(e.buried("AMI", "m20009"))
        // Une tombe sortie peut être enterrée de nouveau (comme avant).
        e.bury("AMI", listOf("m9" to now - 1000))
        assertTrue(e.buried("AMI", "m9"))
        assertFalse(e.buried("AMI", "m10"))
        assertEquals(20_000, stored(e, "AMI").size)
    }

    /**
     * La liste des messages importants est gardée tant qu'aucun message
     * ne change, et jamais périmée : étoile posée ou retirée, message
     * supprimé, discussion vidée, tout effacé — elle suit aussitôt.
     */
    @Test
    fun testStarredListIsKeptUntilAMessageChanges() = phones.run {
        val e = phone()
        val c = e.ensureDirectChat("K7Q2MX9PRT")
        val other = e.ensureDirectChat("WGSEAJNSZJ")
        val a = assertNotNull(e.send(c.id, Content(text = "Un")))
        val b = assertNotNull(e.send(other.id, Content(text = "Deux")))
        assertTrue(e.starred().isEmpty())
        // Relue sans rien changer : la même, sans rien relire.
        val before = e.store.messagesGeneration
        assertTrue(e.starred().isEmpty())
        e.messages(c.id)
        e.store.message(other.id, b.id)
        e.store.writeRaw("kv", "sans rapport", "1")
        assertEquals(before, e.store.messagesGeneration, "lire, ou écrire autre chose qu'un message, ne change rien")
        assertEquals(before, e.starredKept?.generation)

        e.toggleStar(c.id, a.id)
        assertTrue(e.store.messagesGeneration > before)
        assertEquals(listOf("Un"), e.starred().map { it.text })
        e.toggleStar(other.id, b.id)
        assertEquals(setOf("Un", "Deux"), e.starred().map { it.text }.toSet())
        e.toggleStar(c.id, a.id)
        assertEquals(listOf("Deux"), e.starred().map { it.text })
        e.delete(other.id, b.id, forEveryone = true)
        assertTrue(e.starred().isEmpty())

        e.toggleStar(c.id, a.id)
        assertEquals(1, e.starred().size)
        e.store.removeMessage(c.id, a.id)
        assertTrue(e.starred().isEmpty())
        val d = assertNotNull(e.send(c.id, Content(text = "Trois")))
        e.toggleStar(c.id, d.id)
        assertEquals(listOf("Trois"), e.starred().map { it.text })
        e.clearChat(c.id)
        assertTrue(e.starred().isEmpty())
        // Une restauration (une autre base, d'un coup) : relue aussi.
        val f = assertNotNull(e.send(c.id, Content(text = "Quatre")))
        e.toggleStar(c.id, f.id)
        val dump = e.store.dump()
        e.toggleStar(c.id, f.id)
        assertTrue(e.starred().isEmpty())
        e.store.restore(dump)
        assertEquals(listOf("Quatre"), e.starred().map { it.text })
        e.store.wipe()
        assertTrue(e.starred().isEmpty())
    }

    // ----- Les accusés de réception -----

    /**
     * L'accusé d'un second appareil du même contact, ou un accusé rejoué, ne
     * réécrit pas le message et ne redessine rien ; un accusé qui apporte
     * quelque chose (reçu, puis lu) reste écrit comme avant.
     */
    @Test
    fun testADuplicateReceiptRewritesNothing() = phones.run {
        val e = phone()
        val x = Identity.create()
        val c = e.ensureDirectChat(x.code)
        val a = assertNotNull(e.send(c.id, Content(text = "Un")))
        val b = assertNotNull(e.send(c.id, Content(text = "Deux")))
        val t = nowMs()
        fun receipt(st: String, ids: List<String>, dev: String) {
            e.handle(x.code, Wire(Body.Receipt(st, ids, dev)), t, live = true)
        }
        receipt("delivered", listOf(a.id, b.id), "dev1")
        assertEquals(listOf(x.code), e.store.message(c.id, a.id)?.deliveredTo)
        assertEquals(listOf(x.code), e.store.message(c.id, b.id)?.deliveredTo)
        assertEquals(emptyList(), e.store.message(c.id, a.id)?.readBy)

        // Le second appareil, puis un rejeu : rien ne bouge.
        val written = e.store.messagesGeneration
        val drawn = e.revisions[c.id]
        receipt("delivered", listOf(a.id, b.id), "dev2")
        receipt("delivered", listOf(a.id), "dev1")
        assertEquals(written, e.store.messagesGeneration, "aucun message réécrit")
        assertEquals(drawn, e.revisions[c.id], "rien à redessiner")
        assertEquals(listOf(x.code), e.store.message(c.id, a.id)?.deliveredTo)

        // Lu : écrit, redessiné ; relu par l'autre appareil : plus rien.
        receipt("read", listOf(a.id), "dev2")
        assertEquals(listOf(x.code), e.store.message(c.id, a.id)?.readBy)
        assertEquals(emptyList(), e.store.message(c.id, b.id)?.readBy)
        assertTrue(e.store.messagesGeneration > written)
        assertNotEquals(drawn, e.revisions[c.id])
        val after = e.store.messagesGeneration
        receipt("read", listOf(a.id), "dev1")
        assertEquals(after, e.store.messagesGeneration)
        // Lu sans avoir été « reçu » d'abord : les deux d'un coup.
        receipt("read", listOf(b.id), "dev1")
        assertEquals(listOf(x.code), e.store.message(c.id, b.id)?.deliveredTo)
        assertEquals(listOf(x.code), e.store.message(c.id, b.id)?.readBy)
        // Un accusé pour un message qui n'est pas de nous, ou inconnu : ignoré.
        e.handle(x.code, Wire(Body.Msg("r1", t, "", Content(text = "De lui"))), t, live = true)
        receipt("read", listOf("r1", "inconnu"), "dev1")
        assertEquals(emptyList(), e.store.message(c.id, "r1")?.readBy)
    }

    /**
     * Une enveloppe accusée par un appareil : gardée, avec tout son contenu,
     * tant qu'un autre l'attend ; partie quand plus rien ne l'attend ; et
     * laissée telle quelle par un accusé déjà connu.
     */
    @Test
    fun testAnAcknowledgedEnvelopeKeepsItsPayload() = phones.run {
        val e = phone()
        val x = Identity.create()
        val c = e.ensureDirectChat(x.code)
        val m = assertNotNull(e.send(c.id, Content(text = "Bonjour")))
        val key = "${m.id}:${x.code}"
        val before = assertNotNull(e.store.read("outbox", key, OutItem))
        assertFalse(before.payload.isEmpty())
        // Pas encore partie (hors ligne) : l'accusé d'un appareil est noté.
        e.gotBy(key, "dev1")
        var want = before.copy(got = listOf("dev1"))
        assertEquals(want, e.store.read("outbox", key, OutItem))
        // Le même accusé : la ligne ne bouge pas.
        val row = e.store.readRaw("outbox", key)
        e.gotBy(key, "dev1")
        assertEquals(row, e.store.readRaw("outbox", key))
        e.gotBy(key, "dev2")
        want = want.copy(got = listOf("dev1", "dev2"))
        assertEquals(want, e.store.read("outbox", key, OutItem))
        // Acceptée par le relais, et le message distribué : plus rien n'attend.
        val sent = want.copy(boot = "B00T")
        e.store.write("outbox", key, sent)
        e.gotBy(key, "dev3")
        assertEquals(listOf("dev1", "dev2", "dev3"), e.store.read("outbox", key, OutItem)?.got, "le message n'est pas encore distribué")
        e.handle(x.code, Wire(Body.Receipt("delivered", listOf(m.id), "dev1")), nowMs(), live = true)
        assertNull(e.store.readRaw("outbox", key))
        // Sans appareil, ou sans enveloppe : rien, ou la ligne retirée.
        e.store.write("outbox", key, sent)
        e.gotBy(key, "")
        assertNull(e.store.readRaw("outbox", key))
        e.gotBy("inconnue", "dev1")
        e.store.writeRaw("outbox", "abîmée", "pas du JSON")
        e.gotBy("abîmée", "dev1")
        assertNull(e.store.readRaw("outbox", "abîmée"))
    }

    // ----- La recherche dans toutes les discussions -----

    /** `range(of:options: [.caseInsensitive, .diacriticInsensitive])` : sans accents ni majuscules. */
    private fun contains(text: String, q: String): Boolean {
        val f = Mentions.fold(q)
        return f.isNotEmpty() && Mentions.fold(text).contains(f)
    }

    /**
     * Fouillées par tranches sur plusieurs cœurs, les discussions rendent
     * exactement ce que rendait la lecture message après message : les
     * mêmes messages, dans le même ordre, et l'heure de la prochaine capsule.
     */
    @Test
    fun testSearchFindsWhatAPlainScanFinds() = phones.run {
        val now = 1_800_000_000_000.0
        val r = Seeded(5uL)
        val texts = listOf("Un café ?", "CAFE ou thé", "on se voit demain", "Cafétéria fermée", "rien", "le cafe du coin", "École", "ecole")
        val lists = ArrayList<List<Message>>()
        for (c in 0 until 30) {
            val list = ArrayList<Message>()
            // Des discussions vides, courtes, et plus longues qu'une tranche.
            for (i in 0 until listOf(0, 3, 40, 2500)[r.next(4)]) {
                var m = Message("c$c", "m$i", if (r.next(3) == 0) "MOI" else "AMI", r.next(100_000).toDouble(), Content(text = texts[r.next(texts.size)]))
                when (r.next(12)) {
                    0 -> m = m.copy(deleted = true)
                    1 -> m = m.copy(kind = "system")
                    2 -> m = m.copy(openAt = now + (r.next(1000) + 1).toDouble())
                    3 -> m = m.copy(openAt = now - 5)
                    4 -> m = m.copy(kind = "file", text = "", name = "Café.pdf")
                    else -> {}
                }
                list.add(m)
            }
            lists.add(list)
        }
        for (q in listOf("café", "CAFE", "ecole", "thé", "introuvable", "f ?")) {
            val want = ArrayList<Message>()
            var open = Double.POSITIVE_INFINITY
            for (list in lists) {
                for (m in list) {
                    if (m.kind == "system" || m.deleted) continue
                    if (m.sealed("MOI", now)) {
                        open = minOf(open, m.openAt ?: Double.POSITIVE_INFINITY)
                        continue
                    }
                    if (contains("${m.text} ${m.name}", q)) want.add(m)
                }
            }
            val got = Message.matching(lists, q, "MOI", now)
            assertEquals(want, got.found, q)
            assertEquals(open, got.nextOpen, q)
        }
        assertTrue(Message.matching(emptyList<List<Message>>(), "café", "MOI", now).found.isEmpty())
        assertEquals(Double.POSITIVE_INFINITY, Message.matching(listOf(emptyList<Message>()), "café", "MOI", now).nextOpen)
    }

    /**
     * La même recherche n'est pas refaite deux fois de suite, mais elle
     * n'est jamais périmée : un message qui arrive ou part, un contact
     * bloqué, une capsule qui s'ouvre la font refaire.
     */
    @Test
    fun testSearchIsKeptButNeverStale() = phones.run {
        val e = phone()
        val x = Identity.create()
        val y = Identity.create()
        val c = e.ensureDirectChat(x.code)
        e.ensureDirectChat(y.code)
        val a = assertNotNull(e.send(c.id, Content(text = "Rendez-vous au café")))
        e.send(y.code, Content(text = "Un autre CAFE"))
        assertTrue(e.searchMessages("c").isEmpty(), "deux lettres au moins")
        assertEquals(setOf("Rendez-vous au café", "Un autre CAFE"), e.searchMessages("cafe").map { it.text }.toSet())
        val kept = e.searchKept?.at
        assertEquals(2, e.searchMessages(" cafe ").size)
        assertEquals(kept, e.searchKept?.at, "la même recherche, pas refaite")
        assertEquals(1, e.searchMessages("cafe", limit = 1).size)
        assertEquals(listOf(a.id), e.searchMessages("rendez").map { it.id })

        // Un message de plus, un de moins.
        val t = nowMs()
        e.handle(x.code, Wire(Body.Msg("r1", t, "", Content(text = "Quel café ?"))), t, live = true)
        assertEquals(3, e.searchMessages("cafe").size)
        e.delete(c.id, a.id, forEveryone = false)
        assertEquals(setOf("Quel café ?", "Un autre CAFE"), e.searchMessages("cafe").map { it.text }.toSet())
        // Un contact bloqué n'est plus fouillé ; débloqué, il l'est de nouveau.
        e.setBlocked(y.code, true)
        assertEquals(listOf("Quel café ?"), e.searchMessages("cafe").map { it.text })
        e.setBlocked(y.code, false)
        assertEquals(2, e.searchMessages("cafe").size)

        // Une capsule scellée ne se trahit pas ; ouverte, elle se trouve.
        val capsule = Content(text = "Le café de l'an prochain", openAt = t + 400)
        e.handle(x.code, Wire(Body.Msg("r2", t, "", capsule)), t, live = true)
        assertEquals(2, e.searchMessages("cafe").size)
        assertEquals(2, e.searchMessages("cafe").size)
        delay(600)
        assertEquals(3, e.searchMessages("cafe").size)
    }

    /**
     * La recherche dans une discussion (son écran) trouve ce que l'écran
     * trouvait en la lisant en entier, dans le même ordre ; un mot rare ne
     * la charge pas ; gardée d'un dessin à l'autre, elle n'est jamais
     * périmée (message reçu, supprimé, modifié, capsule qui s'ouvre).
     */
    @Test
    fun testSearchInAChatFindsWhatTheScreenFound() = phones.run {
        val e = phone()
        val c = e.ensureDirectChat("K7Q2MX9PRT")
        val other = e.ensureDirectChat("WGSEAJNSZJ")
        val now = nowMs()
        e.store.transaction {
            for (i in 0 until 700) {
                var content = Content(text = if (i % 100 == 42) "Un café numéro $i" else "Message $i")
                if (i == 300) content = Content(kind = "file", text = "", data = "data:application/pdf;base64,AAAA", name = "Carte du Café.pdf")
                var m = Message(c.id, "m$i", if (i % 2 == 0) c.id else e.me.code, now - (1000 - i).toDouble() * 1000, content)
                if (i == 142) m = m.copy(deleted = true)
                if (i == 242) m = m.copy(kind = "system")
                // Une capsule encore scellée (reçue), une autre écrite par soi.
                if (i == 342 || i == 343) m = m.copy(openAt = now + 3_600_000, text = "Le café de plus tard")
                if (i == 442) m = m.copy(exp = now + 600_000)
                e.store.writeMessage(m)
            }
            e.store.writeMessage(Message(other.id, "x", other.id, now, Content(text = "Un café ailleurs")))
        }

        /** Ce que faisait l'écran : toute la discussion, filtrée. */
        fun screen(engine: Engine, q: String): List<String> =
            engine.messages(c.id).filter { m ->
                m.kind != "system" && !m.deleted && !m.sealed(engine.me.code) && contains("${m.text} ${m.name}", q)
            }.map { it.id }
        val cold = cold(e)
        assertTrue(cold.searchChat(c.id, "  ").isEmpty())
        // Un mot rare : trouvé sans lire la discussion en entier.
        val found = cold.searchChat(c.id, " CAFE ")
        assertEquals(listOf("m42", "m300", "m343", "m442", "m542", "m642"), found.map { it.id })
        assertEquals("Un café numéro 42", found.first().text)
        assertEquals("Carte du Café.pdf", found[1].name)
        assertTrue(cold.store.messages.isEmpty())
        // La même recherche n'est pas refaite ; une autre, si.
        val kept = cold.chatSearchKept?.at
        assertEquals(6, cold.searchChat(c.id, "CAFE  ").size)
        assertEquals(kept, cold.chatSearchKept?.at)
        assertEquals(listOf("m642"), cold.searchChat(c.id, "numéro 64").map { it.id })
        assertEquals(listOf("x"), cold.searchChat(other.id, "cafe").map { it.id })
        assertTrue(cold.store.messages.isEmpty())
        // Un message reçu, un supprimé, un modifié : la recherche suit.
        cold.storeMessage(Message(c.id, "neuf", c.id, nowMs(), Content(text = "Encore un CAFÉ")))
        cold.delete(c.id, "m42", forEveryone = false)
        val edited = assertNotNull(cold.store.message(c.id, "m542")).copy(text = "Un thé")
        cold.store.writeMessage(edited)
        assertEquals(listOf("m300", "m343", "m442", "m642", "neuf"), cold.searchChat(c.id, "cafe").map { it.id })
        assertTrue(cold.store.messages.isEmpty())
        // Les mêmes que l'écran d'avant, pour des mots rares ou très courants
        // (une lettre, ou plus d'une centaine de messages : la discussion est lue).
        for (q in listOf("cafe", "Message 1", "9", "m", "é", "numero", "pdf", "zzz", "plus tard", "message 69", "E 6")) {
            assertEquals(screen(cold, q), cold.searchChat(c.id, q).map { it.id }, "« $q »")
        }
        assertTrue(cold.searchChat(c.id, "Message 1").size > Engine.searchSingles)
        assertEquals(emptyList(), cold.searchChat(c.id, "zzz"))
        // Une capsule scellée ne se trahit pas ; ouverte, elle se trouve.
        val t = nowMs()
        val capsule = Message(c.id, "capsule", c.id, t, Content(text = "Le café de tout à l'heure")).copy(openAt = t + 400)
        cold.storeMessage(capsule)
        assertEquals(emptyList(), cold.searchChat(c.id, "tout à l'heure"))
        assertEquals(emptyList(), cold.searchChat(c.id, "tout à l'heure"))
        delay(600)
        assertEquals(listOf("capsule"), cold.searchChat(c.id, "tout à l'heure").map { it.id })
        // Quitter la discussion rend ce que la recherche tenait.
        cold.activeChat = c.id
        assertEquals(0, cold.searchChat(c.id, "neuf").size)
        assertEquals(listOf("neuf"), cold.searchChat(c.id, "encore un").map { it.id })
        assertNotNull(cold.chatSearchKept)
        cold.activeChat = null
        assertNull(cold.chatSearchKept)
        assertEquals(listOf("neuf"), cold.searchChat(c.id, "encore un").map { it.id })
        cold.foreground = false
        assertNull(cold.chatSearchKept)
    }

    /**
     * Des recherches de suite dans une longue discussion (dans son écran
     * ou dans toutes les discussions) ne finissent pas par la charger en
     * entier ni par la garder en mémoire : lire un message trouvé ne compte
     * pas parmi les lectures qui font charger une discussion.
     */
    @Test
    fun testSearchingAgainAndAgainDoesNotLoadTheChat() = phones.run {
        val e = phone()
        val c = e.ensureDirectChat("K7Q2MX9PRT")
        val now = nowMs()
        val words = listOf("anniversaire", "vacances", "piscine", "dentiste", "facture", "concert")
        e.store.transaction {
            for (i in 0 until 900) {
                // Chaque mot dans trente messages, répartis dans toute la discussion.
                val text = "Message $i" + if (i % 30 < words.size) " ${words[i % 30]}" else ""
                e.store.writeMessage(Message(c.id, "m$i", c.id, now - (1000 - i).toDouble() * 1000, Content(text = text)))
            }
        }
        val cold = cold(e)
        for ((n, word) in words.withIndex()) {
            val here = cold.searchChat(c.id, word)
            assertEquals(30, here.size)
            assertEquals("Message $n $word", here.first().text)
            assertEquals("m${870 + n}", here.last().id)
            val everywhere = cold.searchMessages(word, limit = 40)
            assertEquals(30, everywhere.size)
            assertEquals("m${870 + n}", everywhere.first().id)
        }
        // 180 messages lus, plus qu'il n'en faut pour charger une discussion
        // quand ils sont demandés un par un.
        assertTrue(words.size * 30 > Store.loadAfter)
        assertTrue(cold.store.messages.isEmpty())
        assertTrue(cold.store.busy.isEmpty())
        assertTrue(cold.store.singles.isEmpty())
        // Demandés un par un pour autre chose, ils comptent toujours.
        for (i in 0..Store.loadAfter) cold.store.message(c.id, "m$i")
        assertEquals(900, cold.store.messages[c.id]?.size)
        assertEquals(setOf(c.id), cold.store.busy)
        assertEquals("Message 5 concert", cold.store.passingMessage(c.id, "m5")?.text)
        assertNull(cold.store.passingMessage(c.id, "absent"))
    }

    /**
     * Les messages importants d'une longue discussion pas chargée : leur
     * compte et leur liste (les plus récents d'abord) sans la lire en
     * entier, et « tout vider sauf eux » les garde.
     */
    @Test
    fun testStarredMessagesOfAChatThatIsNotLoaded() = phones.run {
        val e = phone()
        val c = e.ensureDirectChat("K7Q2MX9PRT")
        val now = nowMs()
        e.store.transaction {
            for (i in 0 until 400) {
                val m = Message(c.id, "m$i", c.id, now - (1000 - i).toDouble() * 1000, Content(text = "Message $i"))
                    .copy(starred = i == 30 || i == 200 || i == 399)
                e.store.writeMessage(m)
            }
        }
        val cold = cold(e)
        assertEquals(3, cold.starredCount(c.id))
        assertTrue(cold.hasStarred(c.id))
        assertEquals(listOf("Message 399", "Message 200", "Message 30"), cold.starred(c.id).map { it.text })
        assertNull(cold.store.messages[c.id])
        // La même liste qu'en lisant tout.
        assertEquals(cold.store.messagesOf(c.id).filter { it.isStarred }.sortedByDescending { it.at }, cold.starred(c.id))
        // Une étoile retirée, une posée.
        cold.toggleStar(c.id, "m200")
        cold.toggleStar(c.id, "m7")
        assertEquals(3, cold.starredCount(c.id))
        assertEquals(listOf("Message 399", "Message 30", "Message 7"), cold.starred(c.id).map { it.text })
        // Tout vider sauf eux.
        cold.clearChat(c.id, keepStarred = true)
        assertEquals(listOf("Message 7", "Message 30", "Message 399"), cold.messages(c.id).map { it.text })
        assertEquals(3, cold.starredCount(c.id))
        cold.clearChat(c.id)
        assertFalse(cold.hasStarred(c.id))
        assertEquals(0, cold.starredCount(c.id))
        // Aucune discussion de ce nom : rien.
        assertEquals(0, cold.starredCount("INCONNU"))
    }

    /** « Gérer le stockage » ne charge aucune discussion : les plus lourdes d'abord, celles sans pièce jointe écartées. */
    @Test
    fun testStorageByChatLoadsNoChat() = phones.run {
        val e = phone()
        val a = e.ensureDirectChat("K7Q2MX9PRT")
        val b = e.ensureDirectChat("WGSEAJNSZJ")
        val c = e.ensureDirectChat("AAAAAAAAAA")
        fun photo(chat: String, id: String, size: Double): Message =
            Message(chat, id, chat, nowMs(), Content(kind = "image", text = "", data = "data:image/jpeg;base64,/9j/4AAQ", size = size))
        e.store.transaction {
            for (i in 0 until 400) {
                for (chat in listOf(a.id, b.id, c.id)) {
                    e.store.writeMessage(Message(chat, "m$i", chat, i.toDouble(), Content(text = "Message $i")))
                }
            }
            e.store.writeMessage(photo(a.id, "p1", 1_000.0))
            e.store.writeMessage(photo(b.id, "p1", 5_000.0))
            e.store.writeMessage(photo(b.id, "p2", 2_000.0))
        }
        val cold = cold(e)
        val rows = cold.storageByChat()
        assertEquals(listOf(b.id, a.id), rows.map { it.chat.id })
        assertEquals(listOf(Storage(bytes = 7_000.0, photos = 2), Storage(bytes = 1_000.0, photos = 1)), rows.map { it.storage })
        assertTrue(cold.store.messages.isEmpty())
        // Une photo reçue, une supprimée : la liste suit, toujours sans rien charger.
        cold.storeMessage(photo(c.id, "p1", 9_000.0))
        cold.delete(b.id, "p1", forEveryone = false)
        assertEquals(listOf(c.id, b.id, a.id), cold.storageByChat().map { it.chat.id })
        assertEquals(listOf(9_000.0, 2_000.0, 1_000.0), cold.storageByChat().map { it.storage.bytes })
        assertTrue(cold.store.messages.isEmpty())
    }

    /**
     * Les messages importants de toutes les discussions : les mêmes qu'en
     * relisant tous les messages, sans charger aucune discussion, et la
     * liste suit ce qui arrive.
     */
    @Test
    fun testAllStarredMessagesWithoutReadingEverything() = phones.run {
        val e = phone()
        val a = e.ensureDirectChat("K7Q2MX9PRT")
        val b = e.ensureDirectChat("WGSEAJNSZJ")
        val now = nowMs()
        e.store.transaction {
            for (i in 0 until 400) {
                for (chat in listOf(a.id, b.id, "ORPHELINE")) {
                    var m = Message(
                        chat, "m$i", chat, now - (1000 - i).toDouble() * 1000 - (if (chat == a.id) 500 else 0),
                        Content(text = "Message $i de $chat"),
                    ).copy(starred = (chat == a.id && i == 5) || (chat == b.id && (i == 100 || i == 399)) || (chat == "ORPHELINE" && i == 50))
                    // Supprimé pour tous : son étoile ne compte plus.
                    if (chat == b.id && i == 200) m = m.copy(starred = true, deleted = true)
                    e.store.writeMessage(m)
                }
            }
        }
        val cold = cold(e)
        val all = cold.starred()
        assertEquals(listOf("${b.id}|m399", "${b.id}|m100", "ORPHELINE|m50", "${a.id}|m5"), all.map { it.key })
        assertTrue(cold.store.messages.isEmpty())
        assertEquals(cold.store.allMessages().filter { it.isStarred }.sortedBy { it.key }, all.sortedBy { it.key })
        assertEquals(setOf(a.id, b.id, "ORPHELINE"), cold.store.chatsWithMessages().toSet())
        // Une étoile posée, une retirée, un message important qui arrive.
        cold.toggleStar(a.id, "m7")
        cold.toggleStar(b.id, "m100")
        val fresh = Message(a.id, "neuf", a.id, nowMs(), Content(text = "Important")).copy(starred = true)
        cold.storeMessage(fresh)
        assertEquals(
            listOf("${a.id}|neuf", "${b.id}|m399", "ORPHELINE|m50", "${a.id}|m7", "${a.id}|m5"),
            cold.starred().map { it.key },
        )
        assertTrue(cold.store.messages.isEmpty())
        // Une discussion vidée n'en a plus.
        cold.clearChat(a.id)
        assertEquals(listOf("${b.id}|m399", "ORPHELINE|m50"), cold.starred().map { it.key })
    }

    /**
     * Ouvrir une autre discussion, ou passer à l'arrière-plan, rend la
     * mémoire d'une longue discussion lue en entier ; celle qui est à
     * l'écran reste.
     */
    @Test
    fun testLongChatsAreReleasedWhenAnotherOpens() = phones.run {
        val e = phone()
        val a = e.ensureDirectChat("K7Q2MX9PRT")
        val b = e.ensureDirectChat("WGSEAJNSZJ")
        e.store.transaction {
            for (i in 0 until 800) {
                for (chat in listOf(a.id, b.id)) {
                    e.store.writeMessage(Message(chat, "m$i", chat, i.toDouble(), Content(text = "Message $i")))
                }
            }
        }
        val cold = cold(e)
        cold.activeChat = a.id
        assertEquals(800, cold.messages(a.id).size)
        assertNotNull(cold.store.messages[a.id])
        // L'écran des infos la recouvre, on y revient : elle est toujours là.
        cold.activeChat = null
        cold.activeChat = a.id
        assertNotNull(cold.store.messages[a.id])
        // Une autre s'ouvre.
        cold.activeChat = null
        cold.activeChat = b.id
        assertNull(cold.store.messages[a.id])
        assertEquals(800, cold.messages(b.id).size)
        assertEquals("Message 799", cold.recentMessages(a.id, 300).list.last().text)
        assertEquals(800, cold.messages(a.id).size)
        // À l'arrière-plan : celle qui est à l'écran reste, l'autre part.
        cold.foreground = false
        assertNotNull(cold.store.messages[b.id])
        assertNull(cold.store.messages[a.id])
        cold.foreground = true
        assertEquals(800, cold.messages(a.id).size)
    }

    /**
     * La recherche dans toutes les discussions ne charge aucune discussion :
     * les mêmes résultats qu'en lisant tout, et elle suit ce qui arrive.
     */
    @Test
    fun testSearchingEveryChatLoadsNone() = phones.run {
        val e = phone()
        val a = e.ensureDirectChat("K7Q2MX9PRT")
        val b = e.ensureDirectChat("WGSEAJNSZJ")
        val now = nowMs()
        e.store.transaction {
            for (i in 0 until 700) {
                for (chat in listOf(a.id, b.id)) {
                    val text = if (i % 100 == 42) "Un café numéro $i chez $chat" else "Message $i"
                    e.store.writeMessage(Message(chat, "m$i", chat, now - (1000 - i).toDouble() * 1000 - (if (chat == a.id) 500 else 0), Content(text = text)))
                }
            }
        }
        val cold = cold(e)
        val found = cold.searchMessages("cafe", limit = 5)
        assertEquals(listOf("${b.id}|m642", "${a.id}|m642", "${b.id}|m542", "${a.id}|m542", "${b.id}|m442"), found.map { it.key })
        assertEquals("Un café numéro 642 chez ${b.id}", found.first().text)
        assertTrue(cold.store.messages.isEmpty())
        // Les mêmes qu'en fouillant les discussions lues en entier.
        val whole = Message.matching(listOf(a.id, b.id).map { cold.store.messagesOf(it) }, "cafe", cold.me.code, nowMs()).found
        assertEquals(whole.sortedBy { it.key }, cold.searchMessages("cafe", limit = 100).sortedBy { it.key })
        assertEquals(14, whole.size)
        // Une autre discussion s'ouvre (les longues rendent leur mémoire), un
        // message arrive, un autre est supprimé : la recherche suit, sans rien recharger.
        cold.activeChat = "AAAAAAAAAA"
        assertTrue(cold.store.messages.isEmpty())
        cold.storeMessage(Message(a.id, "neuf", a.id, nowMs(), Content(text = "Encore un CAFÉ")))
        cold.delete(b.id, "m642", forEveryone = false)
        assertEquals(listOf("${a.id}|neuf", "${a.id}|m642", "${b.id}|m542"), cold.searchMessages("cafe", limit = 3).map { it.key })
        assertTrue(cold.store.messages.isEmpty())
        // Un contact bloqué n'est plus fouillé.
        cold.setBlocked(a.id, true)
        assertEquals(listOf("${b.id}|m542", "${b.id}|m442"), cold.searchMessages("cafe", limit = 2).map { it.key })
        cold.setBlocked(a.id, false)
        // À l'arrière-plan les textes sont oubliés ; au retour la recherche les relit.
        cold.foreground = false
        assertTrue(cold.store.linesIn.isEmpty())
        cold.foreground = true
        assertEquals(listOf("${a.id}|neuf"), cold.searchMessages("encore un").map { it.key })
        assertTrue(cold.store.messages.isEmpty())
    }

    // ----- L'envoi à un groupe -----

    /**
     * Un envoi à tout un groupe n'encode et n'échappe son contenu qu'une
     * fois : la boîte d'envoi garde pourtant, pour chaque membre, exactement
     * la même ligne qu'en l'encodant un par un.
     */
    @Test
    fun testSendingToAGroupWritesTheSameOutboxRows() = phones.run {
        // Le JSON assemblé se relit comme celui de l'encodeur.
        for (payload in listOf(
            "", "simple", "des \"guillemets\" et des \\ barres / obliques\n\tet un saut",
            "accents éàü, emoji 🥦, \u0001contenu\u0001 (la marque elle-même)", "/9j/4AAQ+abc=".repeat(3_000),
        )) {
            val item = OutItem(id = "m1:AMI", to = "AMI", payload = payload, chat = "g:groupe", msgId = "m1", seq = 1_234_567.0, sent = listOf("a", "b"))
                .copy(boot = "démarrage", got = listOf("a"))
            val json = item.json(JSON.encode(payload))
            assertEquals(item, JSON.decode(OutItem, json))
            assertEquals(utf8Length(JSON.encode(item)), utf8Length(json))
        }

        val e = phone()
        val members = (0 until 12).map { "AMI%07d".format(it) }
        val group = e.createGroup("Les douze", members, "")
        for (key in e.store.rawRows("outbox").keys) e.store.remove("outbox", key)
        val photo = Content(kind = "image", text = "Une \"légende\" / avec des barres \\", data = "data:image/jpeg;base64," + "/9j/4AAQSkZJRg+abc=".repeat(2_000))
        val m = assertNotNull(e.send(group.id, photo))
        val rows = e.store.rawRows("outbox")
        assertEquals(members.map { "${m.id}:$it" }.toSet(), rows.keys)
        val payloads = HashSet<String>()
        for ((key, json) in rows) {
            val item = assertNotNull(JSON.decode(OutItem, json))
            assertEquals(key, item.id)
            assertEquals(group.id, item.chat)
            assertEquals(m.id, item.msgId)
            assertEquals(utf8Length(JSON.encode(item)), utf8Length(json), "la même ligne qu'encodée d'un bloc")
            assertEquals(key.split(":").last(), item.to)
            assertTrue(item.sent.isEmpty() && item.got.isEmpty() && item.boot.isEmpty())
            payloads.add(item.payload)
        }
        // Le même contenu pour tous, et c'est bien le message.
        assertEquals(1, payloads.size)
        val wire = assertNotNull(Wire.parse(payloads.first()))
        val body = wire.body as Body.Msg
        assertEquals(m.id, body.id)
        assertEquals(photo.data, body.content.data)
        assertEquals(photo.text, body.content.text)
        // À une seule personne : une ligne, comme avant.
        val one = e.ensureDirectChat("K7Q2MX9PRT")
        val direct = assertNotNull(e.send(one.id, Content(text = "Bonjour")))
        val row = assertNotNull(e.store.read("outbox", "${direct.id}:K7Q2MX9PRT", OutItem))
        assertEquals("K7Q2MX9PRT", row.to)
        assertEquals(direct.id, row.msgId)
    }

    /**
     * Une actu part à chaque contact enregistré (pas aux bloqués ni aux
     * inconnus), avec le même contenu pour tous ; une réaction dans un
     * groupe, à chaque membre.
     */
    @Test
    fun testAStatusAndAGroupReactionReachEveryone() = phones.run {
        val e = phone()
        for (i in 0 until 8) e.saveContact(Contact(code = "AMI%07d".format(i), saved = i != 6, blocked = i == 7))
        val photo = "data:image/jpeg;base64," + "/9j/4AAQ\"\\/+abc=".repeat(500)
        e.postStatus("image", "Une \"légende\"", "", photo)
        val st = assertNotNull(e.statuses().firstOrNull())
        var rows = e.store.all("outbox", OutItem)
        assertEquals((0 until 6).map { "AMI%07d".format(it) }.toSet(), rows.map { it.to }.toSet())
        assertEquals(1, rows.map { it.payload }.toSet().size)
        val status = assertNotNull(Wire.parse(rows[0].payload)).body as Body.Status
        assertEquals(listOf(st.id, "image", "Une \"légende\""), listOf(status.id, status.kind, status.text))
        assertEquals(photo, status.data)
        // La retirer prévient les mêmes.
        for (key in e.store.rawRows("outbox").keys) e.store.remove("outbox", key)
        e.deleteStatus(st.id)
        rows = e.store.all("outbox", OutItem)
        assertEquals(6, rows.size)
        assertEquals(1, rows.map { it.payload }.toSet().size)
        // Une réaction dans un groupe : une ligne par membre.
        val members = (0 until 5).map { "AMI%07d".format(it) }
        val group = e.createGroup("Cinq", members, "")
        val m = assertNotNull(e.send(group.id, Content(text = "Bonjour")))
        for (key in e.store.rawRows("outbox").keys) e.store.remove("outbox", key)
        e.react(group.id, m.id, "👍")
        rows = e.store.all("outbox", OutItem)
        assertEquals(members.toSet(), rows.map { it.to }.toSet())
        assertEquals(1, rows.map { it.payload }.toSet().size)
        assertEquals(5, rows.map { it.id }.toSet().size)
    }

    /**
     * Tout marquer comme lu : chaque message reçu l'est, sur le disque
     * aussi, un accusé de lecture part par discussion, et les compteurs
     * tombent à zéro.
     */
    @Test
    fun testMarkingEverythingReadIsKept() = phones.run {
        val e = phone()
        val codes = listOf("K7Q2MX9PRT", "WGSEAJNSZJ", "AAAAAAAAAA")
        e.store.transaction {
            for (code in codes) {
                val chat = e.ensureDirectChat(code)
                for (i in 0 until 120) {
                    val from = if (i % 10 == 0) e.me.code else code
                    e.store.writeMessage(Message(code, "m$i", from, i.toDouble(), Content(text = "Message $i")))
                }
                e.saveChat(chat.copy(unread = if (code == "AAAAAAAAAA") 0 else 108))
            }
        }
        for (key in e.store.rawRows("outbox").keys) e.store.remove("outbox", key)
        assertEquals(2, e.markAllRead())
        assertEquals(listOf(0, 0, 0), codes.map { e.chat(it)?.unread })
        // Relu depuis le disque : lus dans les deux discussions marquées, pas dans la troisième.
        val cold = phones.reopen(e.store.dir)
        for (code in codes.take(2)) {
            val received = cold.messagesOf(code).filter { it.from == code }
            assertEquals(108, received.size)
            assertTrue(received.all { it.readByMe })
            assertFalse(cold.messagesOf(code).filter { it.from == e.me.code }.any { it.readByMe })
        }
        assertFalse(cold.messagesOf("AAAAAAAAAA").any { it.readByMe })
        // Un accusé de lecture attend pour chaque discussion lue (ils partent groupés, peu après).
        if (e.settings.readReceipts) {
            assertEquals(codes.take(2).map { "$it|read|$it" }.toSet(), e.receipts.keys)
            assertEquals(listOf(108, 108), e.receipts.values.map { it.size })
        }
        // Rien de plus à faire la deuxième fois.
        assertEquals(0, e.markAllRead())
        // Une discussion ouverte très en retard.
        e.updateChat("AAAAAAAAAA") { it.copy(unread = 108) }
        e.markRead("AAAAAAAAAA")
        assertEquals(0, e.chat("AAAAAAAAAA")?.unread)
        assertTrue(phones.reopen(e.store.dir).messagesOf("AAAAAAAAAA").filter { it.from == "AAAAAAAAAA" }.all { it.readByMe })
    }

    /**
     * Vider ou supprimer une longue discussion pas chargée : les messages
     * récents sont enterrés (ils ne reviendront pas), les anciens non, les
     * importants restent si on le demande, et rien n'est chargé pour ça.
     */
    @Test
    fun testClearingALongChatWithoutLoadingIt() = phones.run {
        val e = phone()
        val codes = listOf("K7Q2MX9PRT", "WGSEAJNSZJ", "AAAAAAAAAA")
        val now = nowMs()
        e.store.transaction {
            for (code in codes) {
                var chat = e.ensureDirectChat(code)
                for (i in 0 until 700) {
                    // Les cent premiers ont plus de 31 jours.
                    val at = if (i < 100) now - Engine.tombWindow - (1000 - i).toDouble() * 1000 else now - (1000 - i).toDouble() * 1000
                    var m = Message(code, "m$i", code, at, Content(text = "Message $i")).copy(starred = i == 50 || i == 600)
                    if (i == 650) m = m.copy(starred = true, deleted = true)
                    e.store.writeMessage(m)
                    if (i == 699) chat = chat.copy(last = e.lastOf(m), lastAt = m.at)
                }
                e.saveChat(chat.copy(unread = 4))
            }
        }
        val cold = cold(e)
        // Tout vider.
        cold.clearChat(codes[0])
        assertTrue(cold.messages(codes[0]).isEmpty())
        assertTrue(cold.buried(codes[0], "m699"))
        assertTrue(cold.buried(codes[0], "m100"))
        assertFalse(cold.buried(codes[0], "m99"), "trop vieux pour revenir : pas enterré")
        assertNull(cold.chat(codes[0])?.last)
        assertEquals(0, cold.chat(codes[0])?.unread)
        // Vider sauf les importants (une étoile sur un message supprimé ne le garde pas).
        cold.clearChat(codes[1], keepStarred = true)
        assertNull(cold.store.messages[codes[1]], "pas chargée pour être vidée")
        assertEquals(listOf("m50", "m600"), cold.messages(codes[1]).map { it.id })
        assertFalse(cold.buried(codes[1], "m600"))
        assertTrue(cold.buried(codes[1], "m650"))
        assertTrue(cold.buried(codes[1], "m699"))
        assertFalse(cold.buried(codes[1], "m99"))
        assertEquals("Message 600", cold.chat(codes[1])?.last?.text)
        assertEquals(2, cold.starredCount(codes[1]))
        // Supprimer.
        cold.deleteChat(codes[2])
        assertNull(cold.chat(codes[2]))
        assertTrue(cold.buried(codes[2], "m300"))
        assertTrue(phones.reopen(e.store.dir).messagesOf(codes[2]).isEmpty())
        assertEquals(2, phones.reopen(e.store.dir).messagesOf(codes[1]).size)
    }

    /**
     * Un accusé de lecture pour des centaines de messages ne charge pas la
     * discussion : chacun est marqué, y compris un vieux message hors de la
     * fin lue d'un coup, et un accusé rejoué ne réécrit rien.
     */
    @Test
    fun testAReceiptForHundredsOfMessagesLoadsNothing() = phones.run {
        val e = phone()
        val x = "K7Q2MX9PRT"
        e.ensureDirectChat(x)
        val now = nowMs()
        e.store.transaction {
            for (i in 0 until 2_000) {
                var m = Message(x, "m$i", if (i % 2 == 0) e.me.code else x, now - (2_000 - i).toDouble() * 1000, Content(text = "Message $i"))
                if (i % 2 == 0) m = m.copy(to = listOf(x), status = "sent")
                e.store.writeMessage(m)
            }
        }
        val cold = cold(e)
        // Les 200 derniers messages envoyés, un très ancien, un reçu (pas à nous) et un inconnu.
        val ids = (0 until 200).map { "m${1_600 + it * 2}" } + listOf("m10", "m1999", "inconnu")
        cold.handle(x, Wire(Body.Receipt("read", ids, "")), nowMs(), live = true)
        assertNull(cold.store.messages[x], "pas chargée")
        for (id in listOf("m1600", "m1998", "m10")) {
            val m = assertNotNull(cold.store.message(x, id))
            assertEquals(listOf(x), m.readBy, id)
            assertEquals(listOf(x), m.deliveredTo, id)
            assertEquals(Message.Tick.read, m.tick, id)
        }
        assertEquals(emptyList(), cold.store.message(x, "m1999")?.readBy, "un message reçu n'a pas d'accusé")
        assertEquals(emptyList(), cold.store.message(x, "m1598")?.readBy, "pas dans l'accusé")
        // Gardé sur le disque.
        val disk = phones.reopen(e.store.dir)
        assertEquals(201, disk.messagesOf(x).count { it.readBy == listOf(x) })
        // Rejoué (un autre appareil du contact) : rien n'est réécrit.
        val generation = cold.store.messagesGeneration
        val revision = cold.revisions[x]
        cold.handle(x, Wire(Body.Receipt("read", ids, "")), nowMs(), live = true)
        assertEquals(generation, cold.store.messagesGeneration)
        assertEquals(revision, cold.revisions[x])
        // Un accusé de réception seul, pour un message : comme avant.
        cold.handle(x, Wire(Body.Receipt("delivered", listOf("m1598"), "")), nowMs(), live = true)
        assertEquals(listOf(x), cold.store.message(x, "m1598")?.deliveredTo)
        assertEquals(emptyList(), cold.store.message(x, "m1598")?.readBy)
        assertNull(cold.store.messages[x])
    }

    /**
     * Le ménage des vieux messages ne lit que leur âge : les trop vieux
     * partent, sauf les importants et une capsule pas encore ouverte depuis
     * assez longtemps ; la discussion n'est pas chargée, et une discussion
     * chargée est nettoyée de la même façon.
     */
    @Test
    fun testOldMessagesLeaveByTheirAgeAlone() = phones.run {
        val e = phone()
        val codes = listOf("K7Q2MX9PRT", "WGSEAJNSZJ")
        val now = nowMs()
        val limit = now - e.settings.keepDays.toDouble() * 86_400_000
        e.store.transaction {
            for (code in codes) {
                var chat = e.ensureDirectChat(code)
                for (i in 0 until 400) {
                    // Les 150 premiers sont trop vieux.
                    val at = (if (i < 150) limit - 3_600_000 else now) - (400 - i).toDouble() * 1000
                    var m = Message(code, "m$i", code, at, Content(text = "Message $i"))
                    if (i == 10 || i == 300) m = m.copy(starred = true)
                    if (i == 20) m = m.copy(starred = true, deleted = true)
                    if (i == 30) m = m.copy(openAt = now - 1_000)
                    if (i == 40) m = m.copy(openAt = limit - 10_000)
                    e.store.writeMessage(m)
                    if (i == 399) chat = chat.copy(last = e.lastOf(m), lastAt = m.at)
                }
                e.saveChat(chat)
            }
        }
        val cold = cold(e)
        cold.messages(codes[1])
        cold.sweep()
        assertNull(cold.store.messages[codes[0]], "pas chargée pour son ménage")
        assertNotNull(cold.store.messages[codes[1]])
        for (code in codes) {
            val left = cold.store.datesOf(code).map { it.first }
            assertEquals(252, left.size, code)
            assertEquals(listOf("m10", "m30", "m150"), left.take(3), code)
            assertNull(cold.store.message(code, "m20"), "une étoile sur un message supprimé ne le garde pas")
            assertNull(cold.store.message(code, "m40"), "une capsule ouverte depuis trop longtemps part")
            assertEquals(2, cold.starredCount(code))
            assertEquals("Message 399", cold.chat(code)?.last?.text)
            assertFalse(cold.buried(code, "m0"), "trop vieux pour revenir : pas enterré")
        }
        assertEquals(252, phones.reopen(e.store.dir).messagesOf(codes[0]).size)
        // Plus rien à faire l'heure suivante.
        val generation = cold.store.messagesGeneration
        cold.lastPrune = 0.0
        cold.sweep()
        assertEquals(generation, cold.store.messagesGeneration)
        // « Toujours garder » : rien ne part.
        assertTrue(cold.store.removeOld(codes[0], now + 1, keepDays = 0, now = now).isEmpty())
        assertEquals(250, cold.store.removeOld(codes[0], now + 1, keepDays = 1, now = now + 3 * 86_400_000).size)
        assertEquals(listOf("m10", "m300"), cold.store.datesOf(codes[0]).map { it.first })
    }

    /** Quitter un groupe prévient chaque membre restant, une fois, du même message. */
    @Test
    fun testLeavingAGroupTellsEveryMember() = phones.run {
        val e = phone()
        val members = (0 until 6).map { "AMI%07d".format(it) }
        val group = e.createGroup("Six", members, "")
        for (key in e.store.rawRows("outbox").keys) e.store.remove("outbox", key)
        e.leaveGroup(group.id)
        val rows = e.store.all("outbox", OutItem)
        assertEquals(members.toSet(), rows.map { it.to }.toSet())
        assertEquals(6, rows.size)
        assertEquals(1, rows.map { it.payload }.toSet().size)
        val wire = assertNotNull(Wire.parse(rows[0].payload))
        assertEquals("leave", (wire.body as Body.Group).op)
        assertEquals(true, e.chat(group.id)?.left)
        assertEquals(false, e.chat(group.id)?.members?.contains(e.me.code))
    }

    /** Un profil changé part à chaque contact enregistré (pas aux bloqués ni aux inconnus), chacun avec son propre jeton de remise. */
    @Test
    fun testAProfileChangeReachesEveryContactWithItsOwnToken() = phones.run {
        val e = phone()
        for (i in 0 until 8) e.saveContact(Contact(code = "AMI%07d".format(i), saved = i != 6, blocked = i == 7))
        for (key in e.store.rawRows("outbox").keys) e.store.remove("outbox", key)
        e.saveProfile { it.copy(name = "Léa D.") }
        val rows = e.store.all("outbox", OutItem)
        assertEquals((0 until 6).map { "AMI%07d".format(it) }.toSet(), rows.map { it.to }.toSet())
        assertEquals(6, rows.size)
        val tokens = HashSet<String>()
        for (row in rows) {
            val p = assertNotNull(Wire.parse(row.payload)).body as Body.Profile
            assertEquals("Léa D.", p.profile.name)
            // La signature du jeton change à chaque fois : on compare ce qu'elle signe.
            val nonce = p.jeton.split(".").firstOrNull() ?: ""
            assertEquals(e.me.jetonNonce(row.to, 0), nonce)
            assertFalse(nonce.isEmpty())
            tokens.add(nonce)
        }
        assertEquals(6, tokens.size, "un jeton par contact")
    }

    // ----- Le journal d'appels -----

    /** Le journal d'appels n'est pas redécodé à chaque affichage, et suit chaque ligne qui arrive, change ou part. */
    @Test
    fun testTheCallLogFollowsItsRows() = phones.run {
        val e = phone()
        fun call(id: String, at: Double, missed: Boolean = false) = CallEntry(id, "K7Q2MX9PRT", false, true, at, 12.0, missed)
        assertTrue(e.callLog().isEmpty())
        e.logCall(call("a", 10.0))
        e.logCall(call("b", 30.0))
        e.logCall(call("c", 20.0, missed = true))
        assertEquals(listOf("b", "c", "a"), e.callLog().map { it.id })
        assertEquals(e.store.all("calls", CallEntry).sortedByDescending { it.at }, e.callLog())
        assertEquals(setOf("a", "b", "c"), e.callsKept.keys)
        // Une ligne changée sans passer par le moteur, une retirée, une ajoutée.
        e.store.write("calls", "a", call("a", 40.0, missed = true))
        e.store.remove("calls", "c")
        e.store.write("calls", "d", call("d", 5.0))
        assertEquals(listOf("a", "b", "d"), e.callLog().map { it.id })
        assertEquals(true, e.callLog().first().missed)
        assertEquals(setOf("a", "b", "d"), e.callsKept.keys)
        e.clearCalls()
        assertTrue(e.callLog().isEmpty())
        assertTrue(e.callsKept.isEmpty())
        e.logCall(call("e", 1.0))
        assertEquals(listOf("e"), e.callLog().map { it.id })
    }

    // ----- Les messages éphémères -----

    /** Le ménage retire les messages éphémères échus sans charger la discussion : ils sont enterrés, et l'aperçu de la discussion suit. */
    @Test
    fun testExpiredMessagesLeaveAtTheSweep() = phones.run {
        val e = phone()
        val c = e.ensureDirectChat("K7Q2MX9PRT")
        val now = nowMs()
        e.store.transaction {
            for (i in 0 until 400) {
                e.store.writeMessage(Message(c.id, "m$i", c.id, now - (1000 - i).toDouble() * 1000, Content(text = "Message $i")))
            }
        }
        val gone = Message(c.id, "échu", c.id, now - 5_000, Content(text = "Éphémère échu")).copy(exp = now - 1_000)
        val soon = Message(c.id, "bientôt", c.id, now - 4_000, Content(text = "Éphémère")).copy(exp = now + 300)
        e.storeMessage(gone)
        e.storeMessage(soon)
        assertEquals(true, e.chat(c.id)?.hasExp)
        assertEquals("Éphémère", e.chat(c.id)?.last?.text)

        // Un lancement : rien en mémoire.
        val cold = cold(e)
        cold.sweep()
        assertNull(cold.store.messages[c.id])
        assertNull(cold.store.message(c.id, "échu"))
        assertNotNull(cold.store.message(c.id, "bientôt"))
        assertTrue(cold.buried(c.id, "échu"))
        assertFalse(cold.buried(c.id, "bientôt"))
        assertEquals("Éphémère", cold.chat(c.id)?.last?.text)
        // Le suivant échoit à son tour : l'aperçu revient au dernier message resté.
        delay(400)
        cold.sweep()
        assertNull(cold.store.message(c.id, "bientôt"))
        assertTrue(cold.buried(c.id, "bientôt"))
        assertEquals("Message 399", cold.chat(c.id)?.last?.text)
        assertNull(cold.store.messages[c.id])
        // Un message éphémère reçu ensuite part à son heure lui aussi.
        val late = Message(c.id, "tardif", c.id, nowMs(), Content(text = "Encore un")).copy(exp = nowMs() + 200)
        cold.storeMessage(late)
        cold.sweep()
        assertNotNull(cold.store.message(c.id, "tardif"))
        delay(300)
        cold.sweep()
        assertNull(cold.store.message(c.id, "tardif"))
        assertEquals(400, cold.messages(c.id).size)
    }

    // ----- Les actus -----

    /**
     * Le ménage lit la date des actus sans décoder leurs photos : celles de
     * plus de 24 heures partent, les autres restent, et la liste suit chaque
     * changement de ligne.
     */
    @Test
    fun testOldStatusesLeaveWithoutDecodingTheirPhotos() = phones.run {
        val e = phone()
        val now = nowMs()
        val photo = "data:image/jpeg;base64," + "/9j/4AAQ".repeat(20_000)
        fun status(id: String, hoursAgo: Double, kind: String = "image") =
            StatusItem("WGSEAJNSZJ", id, kind, "", "", if (kind == "image") photo else "", now - hoursAgo * 3_600_000)
        for (st in listOf(status("vieille", 25.0), status("hier", 23.0), status("texte", 30.0, "text"), status("neuve", 1.0))) {
            e.store.write("statuses", st.key, st)
        }
        // Les dates, sans le contenu.
        assertEquals(
            setOf("WGSEAJNSZJ|vieille", "WGSEAJNSZJ|hier", "WGSEAJNSZJ|texte", "WGSEAJNSZJ|neuve"),
            e.statusDates().map { it.key }.toSet(),
        )
        assertEquals(now - 23 * 3_600_000.0, e.statusDates().first { it.id == "hier" }.at)
        // Avant le ménage, la liste ne montre déjà que les moins de 24 heures.
        assertEquals(listOf("hier", "neuve"), e.statuses().map { it.id })
        e.sweep()
        assertEquals(setOf("WGSEAJNSZJ|hier", "WGSEAJNSZJ|neuve"), e.store.rawRows("statuses").keys)
        assertEquals(listOf("hier", "neuve"), e.statuses().map { it.id })
        assertEquals(photo, e.statuses().first().data)
        assertEquals(setOf("WGSEAJNSZJ|hier", "WGSEAJNSZJ|neuve"), e.statusHeads.keys)
        // Une ligne qui change (vue), une qui arrive, une qui part : suivies.
        e.markStatusSeen(e.statuses()[1])
        assertEquals(listOf(false, true), e.statuses().map { it.seen })
        val fresh = status("toute neuve", 0.0)
        e.store.write("statuses", fresh.key, fresh)
        e.store.remove("statuses", "WGSEAJNSZJ|hier")
        assertEquals(listOf("neuve", "toute neuve"), e.statuses().map { it.id })
        e.sweep()
        assertEquals(listOf("neuve", "toute neuve"), e.statuses().map { it.id })
        assertEquals(setOf("WGSEAJNSZJ|neuve", "WGSEAJNSZJ|toute neuve"), e.statusKept.keys)
        // La même liste que le décodage complet d'avant.
        val t = nowMs()
        assertEquals(e.store.all("statuses", StatusItem).filter { it.at + dayMs > t }.sortedBy { it.at }, e.statuses())
    }

    // ----- La boîte d'envoi -----

    /**
     * La boîte d'envoi lue sans son contenu : les mêmes enveloppes, dans le
     * même ordre, que la lecture complète ; elle suit chaque changement de
     * ligne, et le contenu reste dans la mémoire.
     */
    @Test
    fun testOutboxIsReadWithoutItsPayloads() = phones.run {
        val e = phone()
        fun full(): List<OutItem> = e.store.all("outbox", OutItem).sortedBy { it.seq }
        fun heads(): List<OutItem> = e.outbox().map { it.head }
        fun stripped(items: List<OutItem>): List<OutItem> = items.map { it.copy(payload = "") }
        assertTrue(e.outbox().isEmpty())

        val photo = "A".repeat(200_000)
        for ((i, to) in listOf("K7Q2MX9PRT", "WGSEAJNSZJ", "BGWQXQ2HU9").withIndex()) {
            val item = OutItem(
                id = "m$i:$to", to = to, payload = "{\"k\":\"msg\",\"data\":\"$photo\"}",
                chat = to, msgId = "m$i", seq = (10 - i).toDouble(),
            )
            e.store.write("outbox", item.id, item)
        }
        assertEquals(stripped(full()), heads())
        assertEquals(listOf("m2", "m1", "m0"), heads().map { it.msgId })
        // Chaque enveloppe vient avec sa ligne : le contenu s'y relit entier.
        for ((head, json) in e.outbox()) {
            assertTrue(head.payload.isEmpty())
            assertEquals(photo.length + 21, JSON.decode(OutItem, json)?.payload?.length)
            assertEquals(head.id, JSON.decode(OutItem, json)?.id)
        }
        // Une ligne qui change (un accusé, le relais qui l'accepte), une qui
        // part, une qui arrive : relues ; les autres non.
        val changed = full()[0].copy(boot = "B00T", got = listOf("dev1"))
        e.store.write("outbox", changed.id, changed)
        e.store.remove("outbox", "m0:K7Q2MX9PRT")
        e.store.write("outbox", "m9:K7Q2MX9PRT", OutItem(id = "m9:K7Q2MX9PRT", to = "K7Q2MX9PRT", payload = "{}", chat = "K7Q2MX9PRT", msgId = "m9", seq = 1.0))
        assertEquals(stripped(full()), heads())
        assertEquals(listOf("m9", "m2", "m1"), heads().map { it.msgId })
        assertEquals("B00T", heads()[1].boot)
        assertEquals(listOf("dev1"), heads()[1].got)
        assertEquals(full().map { it.id }.toSet(), e.outHeads.keys, "rien de gardé pour une enveloppe partie")
        // Le contenu n'a pas quitté la mémoire.
        assertEquals(photo.length + 21, e.store.read("outbox", changed.id, OutItem)?.payload?.length)
        e.store.clear("outbox")
        assertTrue(e.outbox().isEmpty())
        assertTrue(e.outHeads.isEmpty())
    }
}
