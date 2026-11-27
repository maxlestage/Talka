// La montre : le résumé que le téléphone lui envoie, et ce qu'elle renvoie ;
// les widgets ; et ce que l'iPhone vérifie avec eux (`WatchTests.swift`) :
// capsules, sourdine, recherche, vocaux enchaînés, expéditeur scellé,
// conservation, export. Sans réseau : le moteur n'est pas démarré, les
// messages attendent dans la boîte d'envoi.

package talka.kit

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTests {
    private val phones = Phones()

    @AfterTest
    fun cleanUp() = phones.close()

    private fun phone() = phones.phone("Léa")

    @Test
    fun testSnapshotOrderMessagesAndRoundTrip() = phones.run {
        val e = phone()
        val old = e.ensureDirectChat("WGSEAJNSZJ")
        e.send(old.id, Content(text = "Bonjour"))
        val recent = e.ensureDirectChat("K7Q2MX9PRT")
        val m = assertNotNull(e.send(recent.id, Content(text = "Salut")))
        e.react(recent.id, m.id, "❤️")
        e.ensureDirectChat("AAAAAAAAAA") // jamais écrit : absent

        val s = e.watchSnapshot()
        assertEquals("Léa", s.name)
        assertEquals(listOf("K7Q2MX9PRT", "WGSEAJNSZJ"), s.chats.map { it.id })
        val first = s.chats[1]
        // Un contact sans nom : son code, comme sur l'iPhone.
        assertEquals("WGSEA-JNSZJ", first.title)
        assertEquals("WJ", first.initials)
        assertEquals("Bonjour", first.last)
        val mine = assertNotNull(s.chats[0].messages.lastOrNull())
        assertTrue(mine.mine)
        assertEquals("Salut", mine.text)
        assertEquals("pending", mine.tick)
        assertEquals("❤️", mine.reactions)
        assertEquals("❤️", mine.myReaction)

        // Ce qui traverse le lien avec la montre revient à l'identique.
        val data = WatchLink.encode(s)
        assertEquals(s, WatchLink.decode(WatchSnapshot, data))
        assertNull(WatchLink.decode(WatchSnapshot, "pas des données"))
    }

    @Test
    fun testActionsFromTheWatch() = phones.run {
        val e = phone()
        val c = e.ensureDirectChat("WGSEAJNSZJ")
        assertTrue(e.perform(WatchAction(WatchAction.Kind.send, chat = c.id, text = "  J'arrive !  ")))
        val sent = assertNotNull(e.messages(c.id).lastOrNull())
        assertEquals("J'arrive !", sent.text)
        assertFalse(e.perform(WatchAction(WatchAction.Kind.send, chat = c.id, text = "   ")))
        assertFalse(e.perform(WatchAction(WatchAction.Kind.send, chat = "INCONNU", text = "x")))

        assertTrue(e.perform(WatchAction(WatchAction.Kind.react, chat = c.id, id = sent.id, text = "👍")))
        assertEquals("👍", e.messages(c.id).last().reactions[e.me.code])
        assertTrue(e.perform(WatchAction(WatchAction.Kind.react, chat = c.id, id = sent.id, text = "👍")))
        assertNull(e.messages(c.id).last().reactions[e.me.code])

        e.updateChat(c.id) { it.copy(unread = 3) }
        assertTrue(e.perform(WatchAction(WatchAction.Kind.read, chat = c.id)))
        assertEquals(0, e.chat(c.id)?.unread)

        e.setBlocked(c.id, true)
        assertTrue(e.watchSnapshot().chats[0].readOnly)
        assertFalse(e.perform(WatchAction(WatchAction.Kind.send, chat = c.id, text = "bloqué")))

        // Une capsule : lisible par son auteur sur la montre.
        val capsule = assertNotNull(e.send(c.id, Content(text = "Plus tard", openAt = nowMs() + 3_600_000)))
        assertEquals("Plus tard", e.watchMessage(capsule, group = false).text)
        var theirs = capsule.copy(from = "WGSEAJNSZJ")
        assertEquals("⏳ Capsule temporelle", e.watchMessage(theirs, group = false).text)
        theirs = theirs.copy(openAt = nowMs() - 1)
        assertEquals("Plus tard", e.watchMessage(theirs, group = false).text)

        e.setBlocked(c.id, false)
        // Un vocal enregistré sur la montre part comme celui du téléphone.
        val meta = WatchLink.voiceMetadata(c.id, 7.4)
        val voice = assertNotNull(WatchLink.voice(meta))
        assertTrue(e.sendWatchVoice(voice.chat, byteArrayOf(1, 2, 3), voice.seconds))
        val v = assertNotNull(e.messages(c.id).lastOrNull())
        assertEquals("voice", v.kind)
        assertEquals("audio/mp4", v.mime)
        assertEquals(7.0, v.dur)
        assertEquals("data:audio/mp4;base64,AQID", v.data)
        assertEquals("🎙️ Message vocal (0:07)", e.watchMessage(v, group = false).text)
        assertFalse(e.sendWatchVoice(c.id, ByteArray(0), 3.0))
        assertFalse(e.sendWatchVoice(c.id, ByteArray(WatchLink.maxVoiceBytes + 1), 3.0))
        assertFalse(e.sendWatchVoice("INCONNU", byteArrayOf(1), 3.0))
        assertNull(WatchLink.voice(mapOf("kind" to "autre", "chat" to c.id, "dur" to 3.0)))
        assertNull(WatchLink.voice(null))
        assertEquals(120.0, WatchLink.voice(WatchLink.voiceMetadata(c.id, 999.0))?.seconds)

        val data = WatchLink.encode(WatchAction(WatchAction.Kind.refresh))
        assertEquals(WatchAction.Kind.refresh, WatchLink.decode(WatchAction, data)?.kind)
    }

    @Test
    fun testSnapshotAlwaysFitsWatchConnectivity() = phones.run {
        val e = phone()
        for (i in 0 until 20) {
            val c = e.ensureDirectChat("K7Q2MX%04d".format(i))
            repeat(15) { e.send(c.id, Content(text = "Bonjour ".repeat(75))) }
        }
        assertTrue(WatchLink.encode(e.watchSnapshot()).size > WatchLink.maxSnapshotBytes)
        val s = e.fittedWatchSnapshot { it.copy(hidden = true) }
        assertTrue(WatchLink.encode(s).size <= WatchLink.maxSnapshotBytes)
        assertEquals(true, s.hidden)
        assertFalse(s.chats.isEmpty())
        assertFalse(s.chats[0].messages.isEmpty())
    }

    /**
     * Le résumé part vers la montre tel qu'il a été mesuré, et ses octets
     * sont gardés tant qu'il ne change pas : un message, un non-lu, le
     * code de verrouillage ou la limite de taille le refont.
     */
    @Test
    fun testWatchDataIsKeptUntilTheSnapshotChanges() = phones.run {
        val e = phone()
        for (i in 0 until 20) {
            val c = e.ensureDirectChat("K7Q2MX%04d".format(i))
            for (n in 0 until 15) e.send(c.id, Content(text = "Message $n " + "Bonjour ".repeat(70)))
        }
        fun decoded(data: ByteArray?): WatchSnapshot? = WatchLink.decode(WatchSnapshot, data)
        // Trop gros en entier : réduit, et ce sont bien les octets du résumé réduit.
        val first = assertNotNull(e.fittedWatchData())
        assertTrue(first.size <= WatchLink.maxSnapshotBytes)
        assertEquals(e.fittedWatchSnapshot(), decoded(first))
        assertTrue((decoded(first)?.chats?.get(0)?.messages?.size ?: 99) < 15)
        // Rien n'a changé : les mêmes octets, à l'octet près.
        assertTrue(first.contentEquals(e.fittedWatchData()))
        assertTrue(first.contentEquals(e.fittedWatchData()))
        // Le verrouillage (réglé par le téléphone à chaque envoi) fait un autre résumé…
        val hidden = assertNotNull(e.fittedWatchData { it.copy(hidden = true) })
        assertEquals(true, decoded(hidden)?.hidden)
        assertEquals(e.fittedWatchSnapshot { it.copy(hidden = true) }, decoded(hidden))
        // … et sans lui, on retrouve le premier.
        assertEquals(decoded(first), decoded(e.fittedWatchData()))
        // Un message de plus : le résumé suit aussitôt.
        val c = e.ensureDirectChat("K7Q2MX0003")
        e.send(c.id, Content(text = "Du nouveau"))
        val next = assertNotNull(decoded(e.fittedWatchData()))
        assertEquals(e.fittedWatchSnapshot(), next)
        assertEquals(c.id, next.chats.first().id)
        assertEquals("Du nouveau", next.chats.first().last)
        assertNotEquals(decoded(first), next)
        // Un non-lu : pareil.
        val chat = assertNotNull(e.chats[c.id])
        e.saveChat(chat.copy(unread = 3))
        assertEquals(3, decoded(e.fittedWatchData())?.chats?.first()?.unread)
        // Une autre limite de taille n'est pas servie par les octets gardés.
        val small = assertNotNull(e.fittedWatchData(4_000))
        assertTrue(small.size < (e.fittedWatchData()?.size ?: 0))
        assertEquals(e.fittedWatchSnapshot(4_000), decoded(small))
        // Tout tient d'emblée : le résumé complet, gardé lui aussi.
        val light = phone()
        val d = light.ensureDirectChat("K7Q2MX9PRT")
        light.send(d.id, Content(text = "Salut"))
        val whole = assertNotNull(light.fittedWatchData())
        assertEquals(light.watchSnapshot(), decoded(whole))
        assertTrue(whole.contentEquals(light.fittedWatchData()))
    }

    @Test
    fun testMarkAllRead() = phones.run {
        val e = phone()
        for ((code, n) in listOf("WGSEAJNSZJ" to 2, "K7Q2MX9PRT" to 0, "AAAAAAAAAA" to 5)) {
            e.ensureDirectChat(code)
            e.updateChat(code) { it.copy(unread = n) }
        }
        e.updateChat("K7Q2MX9PRT") { it.copy(markedUnread = true, archived = true) }
        assertEquals(3, e.markAllRead())
        assertTrue(e.chats.values.all { it.unread == 0 && !it.markedUnread })
        assertEquals(0, e.markAllRead())
    }

    /** Épingle, favoris, réponse : jamais le texte d'une capsule scellée, mais celui d'une capsule ouverte, ou écrite par soi. */
    @Test
    fun testReadableHidesOnlySealedCapsules() = phones.run {
        val e = phone()
        val c = e.ensureDirectChat("WGSEAJNSZJ")
        val mine = assertNotNull(e.send(c.id, Content(text = "Plus tard", openAt = nowMs() + 3_600_000)))
        assertEquals("Plus tard", e.readable(mine))
        var theirs = mine.copy(from = "WGSEAJNSZJ")
        assertEquals("⏳ Capsule temporelle", e.readable(theirs))
        assertEquals("Plus tard", e.readable(theirs, now = mine.openAt!! + 1))
        theirs = theirs.copy(deleted = true)
        assertEquals("🚫 Message supprimé", e.readable(theirs, now = mine.openAt!! + 1))
        val photo = Message(c.id, "p", "WGSEAJNSZJ", 1.0, Content(kind = "image", text = "Vue"))
        assertEquals("📷 Vue", e.readable(photo))
    }

    @Test
    fun testInitialsAndColors() {
        assertEquals("LM", watchInitials("Léa Martin"))
        assertEquals("L", watchInitials("~Léa"))
        assertEquals("WE", watchInitials("WGS-EAJ-NSZJ"))
        assertEquals("", watchInitials(""))
        assertTrue(watchColor("WGSEAJNSZJ") in 0 until 8)
        assertEquals(watchColor("WGSEAJNSZJ"), watchColor("WGSEAJNSZJ"))
    }
}

/** Les widgets : ce qu'ils montrent, et l'adresse qu'ouvre un toucher. */
class WidgetDataTests {
    private fun chat(id: String, unread: Int, at: Double, muted: Boolean = false) =
        WatchChat(id = id, title = id, unread = unread, at = at, muted = muted, last = "dernier de $id")

    @Test
    fun testUnreadFirstThenRecentAndMutedIgnored() {
        val s = WatchSnapshot(
            chats = listOf(
                chat("A", 0, 50.0), chat("B", 2, 10.0),
                chat("C", 4, 5.0, muted = true), chat("D", 1, 20.0), chat("E", 0, 40.0),
            ),
        )
        val w = WidgetData.of(s, hidden = false, limit = 3)
        assertEquals(3, w.unread)
        assertEquals(listOf("D", "B", "A"), w.chats.map { it.id })
        assertEquals("3 messages non lus", w.summary)
        assertEquals(50.0, w.at)
        assertEquals("1 message non lu", WidgetData(unread = 1).summary)
        assertEquals("Aucun message non lu", WidgetData().summary)
    }

    @Test
    fun testHiddenKeepsOnlyTheCount() {
        val w = WidgetData.of(WatchSnapshot(chats = listOf(chat("A", 2, 1.0))), hidden = true)
        assertEquals(2, w.unread)
        assertTrue(w.chats.isEmpty())
        assertTrue(w.hidden)
    }

    @Test
    fun testLinksRoundTrip() {
        for (id in listOf("WGSEAJNSZJ", "g:ab12cd")) assertEquals(id, WidgetData.chat(WidgetData.link(id)))
        assertEquals("talka://chats", WidgetData.link())
        assertNull(WidgetData.chat(WidgetData.link()))
        assertNull(WidgetData.chat("talka://add/WGSEAJNSZJ"))
    }

    @Test
    fun testOlderSnapshotsWithoutHiddenStillDecode() {
        val old = """{"name":"Léa","online":true,"chats":[]}""".toByteArray()
        val s = assertNotNull(WatchLink.decode(WatchSnapshot, old))
        assertNull(s.hidden)
    }
}

/** La capsule temporelle : scellée pour le destinataire, jamais pour l'auteur ; le champ voyage comme le navigateur l'écrit (`open_at`). */
class CapsuleTests {
    @Test
    fun testSealedOnlyForTheRecipientUntilItOpens() {
        val wire = """{"kind":"text","text":"Joyeux anniversaire","open_at":5000}"""
        val content = assertNotNull(JSON.decode(Content, wire))
        assertEquals(5000.0, content.openAt)
        val m = Message("A", "1", "LEA", 1000.0, content)
        assertTrue(m.isCapsule)
        assertTrue(m.sealed("CLEMENT", 4999.0))
        assertFalse(m.sealed("CLEMENT", 5000.0))
        assertFalse(m.sealed("LEA", 2000.0))
        assertEquals("⏳ Capsule temporelle", Engine.preview(m))
        // Stocké et relu à l'identique.
        assertEquals(5000.0, JSON.decode(Message, JSON.encode(m))?.openAt)
    }

    @Test
    fun testOrdinaryMessagesDoNotCarryTheField() {
        val plain = Content(text = "Salut")
        assertFalse(JSON.encode(plain).contains("open_at"))
        val m = Message("A", "1", "LEA", 1.0, plain)
        assertFalse(m.isCapsule)
        assertFalse(JSON.encode(m).contains("open_at"))
    }
}

/** La sourdine pour un temps : levée d'elle-même à l'échéance, et un champ facultatif (`mute_until`) comme dans le navigateur. */
class MuteTests {
    private val phones = Phones()

    @AfterTest
    fun cleanUp() = phones.close()

    @Test
    fun testTimedMuteLiftsByItselfAndForeverStays() = phones.run {
        val e = phones.phone("Léa")
        e.ensureDirectChat("WGSEAJNSZJ")
        e.ensureDirectChat("K7Q2MX9PRT")
        e.mute("WGSEAJNSZJ", 8 * 3600.0)
        e.mute("K7Q2MX9PRT", null)
        assertEquals(true, e.chat("WGSEAJNSZJ")?.muted)
        assertNotNull(e.chat("WGSEAJNSZJ")?.muteUntil)
        assertNull(e.chat("K7Q2MX9PRT")?.muteUntil)
        // L'échéance passée : la sourdine se lève, « toujours » reste.
        e.updateChat("WGSEAJNSZJ") { it.copy(muteUntil = nowMs() - 1) }
        e.sweep()
        assertEquals(false, e.chat("WGSEAJNSZJ")?.muted)
        assertNull(e.chat("WGSEAJNSZJ")?.muteUntil)
        assertEquals(true, e.chat("K7Q2MX9PRT")?.muted)
        e.mute("K7Q2MX9PRT", 0.0)
        assertEquals(false, e.chat("K7Q2MX9PRT")?.muted)
        // Le format : absent sans sourdine pour un temps, relu tel quel sinon.
        assertFalse(JSON.encode(assertNotNull(e.chat("K7Q2MX9PRT"))).contains("mute_until"))
        val old = assertNotNull(JSON.decode(Chat, """{"id":"A","muted":true}"""))
        assertTrue(old.muted)
        assertNull(old.muteUntil)
        val timed = assertNotNull(JSON.decode(Chat, """{"id":"A","muted":true,"mute_until":1800000000000}"""))
        assertEquals(1_800_000_000_000.0, timed.muteUntil)
    }
}

/** La recherche dans toutes les discussions, comme le navigateur. */
class SearchTests {
    private val phones = Phones()

    @AfterTest
    fun cleanUp() = phones.close()

    @Test
    fun testSearchesAllChatsIgnoringAccentsAndHidingSealed() = phones.run {
        val e = phones.phone("Alice")
        val bob = e.ensureDirectChat("WGSEAJNSZJ")
        val chloe = e.ensureDirectChat("K7Q2MX9PRT")
        e.send(bob.id, Content(text = "On se voit samedi au marché ?"))
        e.send(chloe.id, Content(text = "Le cinéma de Samédi est complet"))
        e.send(chloe.id, Content(text = "Rien à voir"))
        val sealed = assertNotNull(e.send(chloe.id, Content(text = "Surprise samedi", openAt = nowMs() + 3_600_000))).copy(from = "K7Q2MX9PRT")
        e.store.writeMessage(sealed)
        val found = e.searchMessages("samedi")
        assertEquals(setOf("On se voit samedi au marché ?", "Le cinéma de Samédi est complet"), found.map { it.text }.toSet())
        assertTrue(e.searchMessages("s").isEmpty())
        e.setBlocked("WGSEAJNSZJ", true)
        assertEquals(listOf("Le cinéma de Samédi est complet"), e.searchMessages("samedi").map { it.text })
    }
}

class VoiceChainTests {
    private fun message(id: String, kind: String, deleted: Boolean = false, exp: Double? = null) =
        Message("c", id, "WGSEAJNSZJ", 0.0, Content(kind = kind)).copy(deleted = deleted, exp = exp)

    @Test
    fun testOnlyTheVoiceRightAfterChains() {
        val list = listOf(
            message("a", "voice"), message("b", "voice"), message("c", "text"), message("d", "voice"),
            message("e", "voice", deleted = true), message("f", "voice", exp = 10.0), message("g", "voice"),
        )
        assertEquals("b", nextVoice("a", list, 100.0))
        assertNull(nextVoice("b", list, 100.0))
        assertNull(nextVoice("d", list, 100.0))
        assertNull(nextVoice("e", list, 100.0))
        assertEquals("f", nextVoice("e", list, 5.0))
        assertNull(nextVoice("g", list, 100.0))
        assertNull(nextVoice("inconnu", list, 100.0))
    }
}

/**
 * L'expéditeur scellé, dans le moteur : une enveloppe scellée arrive sans
 * expéditeur, et en ressort attribuée ; le jeton d'un profil est gardé s'il
 * est bien signé.
 */
class SealedSenderTests {
    private val phones = Phones()

    @AfterTest
    fun cleanUp() = phones.close()

    /** Notre jeton personnel va à un contact comme à un membre de groupe qui nous écrit (pas dans nos contacts) ; jamais à qui est bloqué. */
    @Test
    fun testJetonGoesToCorrespondentsButNotBlocked() = phones.run {
        val e = phones.phone("Léa")
        val member = Identity.create()
        e.saveContact(Contact(code = member.code))
        val p = e.profileBody(member.code) as Body.Profile
        assertTrue(p.pj)
        assertEquals(e.me.jetonNonce(member.code, 0), p.jeton.split(".").firstOrNull())
        e.setBlocked(member.code, true)
        assertEquals("", (e.profileBody(member.code) as Body.Profile).jeton)
        assertEquals("", (e.profileBody(Identity.create().code) as Body.Profile).jeton, "quelqu'un qu'on ne connaît pas du tout")
    }

    @Test
    fun testSealedEnvelopeAndJeton() = phones.run {
        val e = phones.phone("Léa")
        val x = Identity.create()
        e.saveContact(Contact(code = x.code, sign = x.sign, dh = x.dh, saved = true))

        // Son profil, avec un jeton bien signé : gardé. (La signature change
        // à chaque calcul : on garde celui-ci.)
        val jeton = x.jeton
        val profile = Wire(Body.Profile(Profile(name = "Xavier", v = 1), jeton = jeton)).json()
        assertTrue(profile.contains("\"jeton\""))
        fun seal(plain: String): String =
            TalkaCrypto.sealSender(x.code, e.me.code, e.me.dh, TalkaCrypto.seal(x, e.me.code, e.me.dh, plain)!!)!!
        e.receive(envelope("sealed" to true, "body" to seal(profile), "ts" to nowMs()))
        assertEquals(jeton, e.contact(x.code)?.jeton)
        assertEquals("Xavier", e.contact(x.code)?.profileName)
        // Le jeton d'un autre : refusé.
        val other = Identity.create()
        val bad = Wire(Body.Profile(Profile(name = "Xavier", v = 2), jeton = other.jeton)).json()
        e.receive(envelope("sealed" to true, "body" to seal(bad), "ts" to nowMs()))
        assertEquals(jeton, e.contact(x.code)?.jeton)

        // Un message scellé : attribué à son auteur.
        val msg = Wire(Body.Msg("s1", nowMs(), "Xavier", Content(text = "En secret"))).json()
        e.receive(envelope("sealed" to true, "body" to seal(msg), "ts" to nowMs()))
        assertEquals("En secret", e.messages(x.code).last().text)
        assertEquals(x.code, e.messages(x.code).last().from)

        // Quelqu'un qui se fait passer pour lui : rien.
        val forged = TalkaCrypto.sealSender(
            x.code, e.me.code, e.me.dh,
            TalkaCrypto.seal(other, e.me.code, e.me.dh, Wire(Body.Msg("s2", nowMs(), "", Content(text = "Faux"))).json())!!,
        )!!
        e.receive(envelope("sealed" to true, "body" to forged, "ts" to nowMs()))
        assertNull(e.messages(x.code).firstOrNull { it.id == "s2" })

        // Le contact garde son jeton dans le stockage ; l'ancien format se relit.
        val stored = JSON.encode(assertNotNull(e.contact(x.code)))
        assertTrue(stored.contains("\"jeton\""))
        val old = assertNotNull(JSON.decode(Contact, """{"code":"WGSEAJNSZJ"}"""))
        assertEquals("", old.jeton)
        assertFalse(JSON.encode(old).contains("jeton"))
    }
}

/** Les messages de plus de 3 mois s'effacent (sauf les importants). */
class KeepDaysTests {
    private val phones = Phones()

    @AfterTest
    fun cleanUp() = phones.close()

    @Test
    fun testOldMessagesGoExceptStarred() = phones.run {
        val e = phones.phone("Léa")
        assertEquals(90, e.settings.keepDays)
        val c = e.ensureDirectChat("WGSEAJNSZJ")
        val now = nowMs()
        val day = 86_400_000.0
        fun put(id: String, daysAgo: Double, starred: Boolean = false) {
            e.store.writeMessage(Message(c.id, id, e.me.code, now - daysAgo * day, Content(text = id)).copy(starred = starred))
        }
        put("vieux", 120.0)
        put("vieux-important", 120.0, starred = true)
        put("récent", 10.0)
        // Une capsule écrite il y a 4 mois, qui s'ouvre dans un mois : gardée.
        val capsule = Message(c.id, "capsule", e.me.code, now - 120 * day, Content(text = "plus tard")).copy(openAt = now + 30 * day)
        e.store.writeMessage(capsule)
        e.sweep()
        assertEquals(setOf("vieux-important", "récent", "capsule"), e.messages(c.id).map { it.id }.toSet())
        e.store.removeMessage(c.id, "capsule")

        // « Toujours » : plus rien ne part.
        put("très-vieux", 1000.0)
        e.saveSettings { it.copy(keepDays = 0) }
        assertTrue(e.messages(c.id).any { it.id == "très-vieux" })
        // 1 mois : tout ce qui dépasse s'efface aussitôt.
        e.saveSettings { it.copy(keepDays = 30) }
        assertEquals(setOf("vieux-important", "récent"), e.messages(c.id).map { it.id }.toSet())

        // Un réglage d'avant cette version : 3 mois.
        val old = assertNotNull(JSON.decode(Settings, """{"theme":"dark"}"""))
        assertEquals(90, old.keepDays)
    }
}

/** « Exporter la discussion » : le même texte que le navigateur. */
class ExportChatTests {
    private val phones = Phones()

    @AfterTest
    fun cleanUp() = phones.close()

    @Test
    fun testExportedText() = phones.run {
        val e = phones.phone("Alice")
        val c = e.ensureDirectChat("WGSEAJNSZJ")
        e.send(c.id, Content(text = "Bonjour\nsur deux lignes"))
        e.send(c.id, Content(text = "Plus tard", openAt = nowMs() + 3_600_000))
        var theirs = Message(c.id, "t1", "WGSEAJNSZJ", nowMs(), Content(kind = "voice", dur = 7.0))
        theirs = theirs.copy(at = theirs.at + 1)
        e.store.writeMessage(theirs)
        val (name, text) = assertNotNull(e.exportChat(c.id))
        assertEquals("Talka - WGSEA-JNSZJ.txt", name)
        assertTrue(text.startsWith("Discussion Talka « WGSEA-JNSZJ », exportée le "))
        assertTrue(text.contains(" - Alice : Bonjour\nsur deux lignes\n"))
        assertTrue(text.contains(" - Alice : Plus tard\n"), "sa propre capsule se lit")
        assertTrue(text.contains(" - WGSEA-JNSZJ : 🎙️ Message vocal (0:07)\n"))
        assertEquals("21/10/2026 23:21 - Léa : Bonjour", exportLine("21/10/2026 23:21", "Léa", "Bonjour"))
        assertEquals("21/10/2026 23:22 - Léa a rejoint le groupe.", exportLine("21/10/2026 23:22", null, "Léa a rejoint le groupe."))
        assertEquals("Talka - Les amis _ 2026 _ été_.txt", exportFilename("Les amis / 2026 : été?"))
        assertEquals(16, exportStamp(0.0).length)
    }
}
