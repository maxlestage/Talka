// Deux téléphones (deux moteurs TalkaKit) sur un vrai serveur Talka, comme
// `LiveTests.swift` de l'iPhone :
//
//     TALKA_SERVER=http://localhost:3000 ./gradlew :talkakit:test --tests 'talka.kit.LiveTests'
//
// Sans `TALKA_SERVER`, ces tests sont sautés. `TALKA_RESTART_FILE` : un
// fichier que le test crée pour demander à son lanceur de redémarrer le
// serveur (voir ios/README.md) ; sans lui, les tests de redémarrage sont
// sautés.

package talka.kit

import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LiveTests {
    private val phones = Phones()
    private val started = ArrayList<Engine>()

    @AfterTest
    fun cleanUp() {
        phones.run { for (e in started) e.stop() }
        phones.close()
    }

    private fun server(): String {
        val s = System.getenv("TALKA_SERVER")
        assumeTrue(!s.isNullOrEmpty(), "TALKA_SERVER n'est pas défini")
        return s!!
    }

    private fun restartFlag(): String {
        val flag = System.getenv("TALKA_RESTART_FILE")
        assumeTrue(!flag.isNullOrEmpty(), "TALKA_RESTART_FILE n'est pas défini")
        return flag!!
    }

    /** Un moteur sur le vrai réseau (OkHttp), sur le fil des tests. */
    private fun live(store: Store, url: String): Engine = Engine(store, url, phones.dispatcher, OkHttpSockets).also { started.add(it) }

    private fun phone(name: String, url: String): Engine {
        val store = phones.store()
        Engine.createAccount(store, name, "")
        val e = live(store, url)
        e.start()
        return e
    }

    private suspend fun wait(what: String, timeout: Double = 10.0, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + (timeout * 1000).toLong()
        while (!ok()) {
            if (System.currentTimeMillis() > end) fail("trop long : $what")
            delay(50)
        }
    }

    @Test
    fun testTwoPhones() = phones.run {
        val url = server()
        val a = phone("Alice", url)
        val b = phone("Bob", url)
        wait("connexion") { a.netState == Relay.State.online && b.netState == Relay.State.online }

        // Alice ajoute Bob par son code et lui écrit.
        val reachable = a.addContact(b.me.code, "Bob")
        assertTrue(reachable)
        a.ensureDirectChat(b.me.code)
        val sent = assertNotNull(a.send(b.me.code, Content(text = "Bonjour depuis Android 🤖")))
        wait("réception") { b.messages(a.me.code).isNotEmpty() }
        val got = assertNotNull(b.messages(a.me.code).lastOrNull())
        assertEquals("Bonjour depuis Android 🤖", got.text)
        assertEquals("~Alice", b.displayName(a.me.code), "le profil a suivi")
        // Deux téléphones à jour : une session à double cliquet (v3) ; avec un
        // relais d'avant v3, l'ancien format (sans attendre le paquet).
        val protection = b.protection(a.me.code)
        val v3 = a.relay.relayV3 == true
        // (v6 : et le cliquet post-quantique, Alice ayant annoncé sa clé.)
        assertEquals(
            if (v3) (if (Pq6.available) V3.Protection.RatchetPq6 else V3.Protection.Ratchet(V3.postQuantum)) else V3.Protection.Legacy,
            protection,
        )
        // (Rangée sous son code, ou déjà passée à l'adresse de son appareil, v4.)
        val session = a.v3?.hasSession(b.me.code) == true || a.v3?.hasSession(TalkaV4.address(b.me.code, b.dev)) == true
        assertEquals(v3, session)

        // L'expéditeur scellé : chacun a reçu le jeton de l'autre ; ensuite,
        // tout part par la connexion anonyme (le relais ne sait plus qui écrit).
        if (a.relay.sealed) {
            wait("jetons") { b.contact(a.me.code)?.jeton?.isEmpty() == false && a.contact(b.me.code)?.jeton?.isEmpty() == false }
            val before = a.relay.sealedSent
            a.send(b.me.code, Content(text = "Scellé"))
            wait("scellé") { b.messages(a.me.code).any { it.text == "Scellé" } }
            assertTrue(a.relay.sealedSent > before)
            assertEquals(a.me.code, b.messages(a.me.code).lastOrNull { it.text == "Scellé" }?.from)
        }

        // ✓✓ puis ✓✓ bleus.
        wait("distribué") { a.store.message(b.me.code, sent.id)?.tick == Message.Tick.delivered }
        b.markRead(a.me.code)
        wait("lu") { a.store.message(b.me.code, sent.id)?.tick == Message.Tick.read }

        // Réaction, modification, suppression.
        b.react(a.me.code, got.id, "❤️")
        wait("réaction") { a.store.message(b.me.code, sent.id)?.reactions?.get(b.me.code) == "❤️" }
        a.edit(b.me.code, sent.id, "Bonjour, modifié")
        wait("modification") { b.store.message(a.me.code, sent.id)?.text == "Bonjour, modifié" }
        a.delete(b.me.code, sent.id, forEveryone = true)
        wait("suppression") { b.store.message(a.me.code, sent.id)?.deleted == true }

        // Un groupe.
        b.addContact(a.me.code, "Alice")
        val g = a.createGroup("Les amis", listOf(b.me.code), "")
        a.send(g.id, Content(text = "Dans le groupe"))
        wait("groupe") { b.messages(g.id).any { it.text == "Dans le groupe" } }
        assertEquals("Les amis", b.chat(g.id)?.name)

        // Un statut.
        a.postStatus("text", "Vacances 🌊", "#008069", "")
        wait("statut") { b.statuses().any { it.text == "Vacances 🌊" } }

        // Hors ligne : Bob part, Alice écrit, Bob revient.
        b.relay.stop()
        a.send(b.me.code, Content(text = "Tu étais parti"))
        delay(500)
        b.relay.start()
        wait("message en attente") { b.messages(a.me.code).any { it.text == "Tu étais parti" } }

        // La sauvegarde, et son import ailleurs.
        val file = assertNotNull(a.exportBackup("motdepasse"))
        val other = phones.store()
        Engine.importBackup(other, "motdepasse", file)
        assertEquals(a.me, other.read("kv", "identity", Identity))
        assertFalse(other.messagesOf(g.id).isEmpty())
    }

    /**
     * Retour au premier plan après une veille : les connexions sont
     * rouvertes tout de suite, et ce qui est arrivé entre-temps se lit.
     */
    @Test
    fun testReopenAfterSleep() = phones.run {
        val url = server()
        val a = phone("Alice", url)
        val b = phone("Bob", url)
        wait("connexion") { a.netState == Relay.State.online && b.netState == Relay.State.online }
        a.addContact(b.me.code, "Bob")
        a.ensureDirectChat(b.me.code)
        a.send(b.me.code, Content(text = "Avant la veille"))
        wait("réception") { b.messages(a.me.code).any { it.text == "Avant la veille" } }
        b.relay.reopen()
        a.send(b.me.code, Content(text = "Pendant le réveil"))
        wait("reconnexion") { b.netState == Relay.State.online }
        wait("réception après la réouverture", timeout = 20.0) { b.messages(a.me.code).any { it.text == "Pendant le réveil" } }
    }

    /**
     * Le relais redémarre pendant que Bob est absent : sa boîte aux lettres
     * est perdue, mais Alice renvoie ce qui n'a pas été confirmé.
     */
    @Test
    fun testResendAfterRelayRestart() = phones.run {
        val url = server()
        val flag = restartFlag()
        val a = phone("Alice", url)
        val b = phone("Bob", url)
        wait("connexion") { a.netState == Relay.State.online && b.netState == Relay.State.online }
        assertFalse(a.relay.boot.isEmpty())
        val before = a.relay.boot
        a.addContact(b.me.code, "Bob")
        a.ensureDirectChat(b.me.code)

        b.relay.stop()
        val sent = assertNotNull(a.send(b.me.code, Content(text = "Pendant ton absence")))
        wait("accepté par le relais") { a.store.message(b.me.code, sent.id)?.sentTo?.contains(b.me.code) == true }
        assertEquals(before, a.store.read("outbox", "${sent.id}:${b.me.code}", OutItem)?.boot)

        // Le relais redémarre : la boîte aux lettres de Bob est perdue.
        File(flag).createNewFile()
        wait("Alice reconnectée au nouveau relais", timeout = 40.0) { a.netState == Relay.State.online && a.relay.boot != before }

        b.relay.start()
        wait("message renvoyé puis reçu", timeout = 30.0) { b.messages(a.me.code).any { it.text == "Pendant ton absence" } }
        wait("accusé de réception", timeout = 15.0) { a.store.message(b.me.code, sent.id)?.deliveredTo?.contains(b.me.code) == true }
        assertNull(a.store.read("outbox", "${sent.id}:${b.me.code}", OutItem))
    }

    /** Une tablette liée à `a1` par le code de son QR code, et connectée. */
    private suspend fun linkTablet(a1: Engine, url: String): Pair<Engine, DeviceLink> {
        // Le nouvel appareil attend, le QR code à l'écran.
        val store = phones.store()
        val link = DeviceLink(store, url, phones.dispatcher, OkHttpSockets)
        var result: Result<String>? = null
        link.onDone = { result = it }
        link.start()
        wait("boîte de liaison écoutée") { link.socket.ready }
        val verif = a1.linkDevice(link.text)
        wait("liaison reçue", timeout = 20.0) { result != null }
        assertEquals(verif, result?.getOrNull(), "le même code de vérification des deux côtés")
        assertTrue(a1.devices.has(link.dev))
        val name = Engine.deviceName
        Engine.deviceName = "iPad"
        val a2 = live(store, url)
        Engine.deviceName = name
        a2.start()
        wait("la tablette connectée") { a2.netState == Relay.State.online }
        return a2 to link
    }

    /**
     * v4 : le relais redémarre pendant que la tablette d'Alice est absente ;
     * Bob ne renvoie qu'à elle (le téléphone avait accusé réception).
     */
    @Test
    fun testResendToTheMissingDeviceAfterRelayRestart() = phones.run {
        val url = server()
        val flag = restartFlag()
        val a1 = phone("Alice", url)
        val b = phone("Bob", url)
        wait("connexion") { a1.netState == Relay.State.online && b.netState == Relay.State.online }
        val (a2, _) = linkTablet(a1, url)
        a1.addContact(b.me.code, "Bob")
        b.addContact(a1.me.code, "Alice")
        wait("Bob connaît les deux appareils d'Alice") { b.contact(a1.me.code)?.devs?.size == 2 }
        wait("les boîtes de Bob suivies") { a2.boxMap.values.contains(b.me.code) }

        a2.relay.stop()
        b.ensureDirectChat(a1.me.code)
        val before = b.relay.boot
        val m = assertNotNull(b.send(a1.me.code, Content(text = "Pendant l'absence de la tablette")))
        val key = "${m.id}:${a1.me.code}"
        wait("reçu sur le téléphone") { a1.messages(b.me.code).any { it.id == m.id } }
        wait("accusé du téléphone seul", timeout = 15.0) { b.store.read("outbox", key, OutItem)?.got == listOf(a1.dev) }
        // L'accusé peut arriver pendant que la copie de la tablette part encore
        // (le délai au hasard des dépôts anonymes) : on attend que l'envoi
        // soit écrit (accepté par le relais : `boot`).
        wait("envoi écrit", timeout = 15.0) { b.store.read("outbox", key, OutItem)?.boot?.isNotEmpty() == true }
        assertEquals(listOf(a1.dev), b.store.read("outbox", key, OutItem)?.got)
        assertEquals(setOf(a1.dev, a2.dev), (b.store.read("outbox", key, OutItem)?.sent ?: emptyList()).toSet())

        // Le relais redémarre : la boîte de la tablette est perdue.
        val boxed = b.relay.boxSent
        File(flag).createNewFile()
        wait("Bob reconnecté au nouveau relais", timeout = 40.0) { b.netState == Relay.State.online && b.relay.boot != before }
        wait("Alice reconnectée", timeout = 40.0) { a1.netState == Relay.State.online }
        wait("renvoyé à la tablette seule", timeout = 20.0) { b.store.read("outbox", key, OutItem)?.boot == b.relay.boot }
        assertEquals(1, b.relay.boxSent - boxed, "une seule copie : le téléphone l'a déjà")
        a2.relay.start()
        wait("reçu sur la tablette", timeout = 30.0) { a2.messages(b.me.code).any { it.id == m.id } }
        wait("plus rien n'attend", timeout = 15.0) { b.store.read("outbox", key, OutItem) == null }
        assertEquals(1, a1.messages(b.me.code).count { it.id == m.id })
    }

    /**
     * v4 : deux appareils d'un même compte (Alice sur téléphone et tablette)
     * et un contact (Bob). La tablette est liée par le code du QR code ; les
     * deux reçoivent ce que Bob écrit (dans leurs boîtes tournantes) ; ce que
     * la tablette envoie apparaît sur le téléphone ; puis elle est retirée.
     */
    @Test
    fun testTwoDevicesOfOneAccount() = phones.run {
        val url = server()
        val a1 = phone("Alice", url)
        val b = phone("Bob", url)
        wait("connexion") { a1.netState == Relay.State.online && b.netState == Relay.State.online }

        val (a2, link) = linkTablet(a1, url)
        // Un code de liaison déjà servi, ou qui n'en est pas un : refusé.
        try {
            a1.linkDevice(link.text)
            fail("lié deux fois")
        } catch (e: LinkError) {
            assertEquals(LinkError.AlreadyLinked, e)
        }
        try {
            a1.linkDevice(b.me.code)
            fail("pas un code de liaison")
        } catch (e: LinkError) {
            assertEquals(LinkError.NotALink, e)
        }
        assertEquals(a1.me, a2.me)
        assertEquals(link.dev, a2.dev)
        assertNotEquals(a1.dev, a2.dev)
        assertEquals(listOf(a1.dev), a2.siblings)
        // La tablette prend son vrai nom, et le téléphone l'apprend.
        wait("le nom de la tablette") { a1.devices.list.firstOrNull { it.dev == a2.dev }?.name == "iPad" }

        // Alice ajoute Bob (sur le téléphone : la tablette l'apprend), Bob ajoute Alice.
        a1.addContact(b.me.code, "Bob")
        wait("contact synchronisé") { a2.contact(b.me.code)?.saved == true && a2.contact(b.me.code)?.name == "Bob" }
        b.addContact(a1.me.code, "Alice")
        wait("Bob connaît les deux appareils d'Alice") {
            (b.contact(a1.me.code)?.devs?.map { it.d } ?: emptyList()).toSet() == setOf(a1.dev, a2.dev) && b.contact(a1.me.code)?.bx == true
        }
        wait("les boîtes de Bob suivies") { a1.boxMap.values.contains(b.me.code) && a2.boxMap.values.contains(b.me.code) }

        // Bob écrit : les deux appareils d'Alice le reçoivent, par leurs
        // boîtes tournantes (le relais ne sait ni qui écrit, ni à qui).
        b.ensureDirectChat(a1.me.code)
        val boxed = b.relay.boxSent
        val hello = assertNotNull(b.send(a1.me.code, Content(text = "Salut Alice")))
        wait("reçu sur le téléphone") { a1.messages(b.me.code).any { it.id == hello.id } }
        wait("reçu sur la tablette") { a2.messages(b.me.code).any { it.id == hello.id } }
        assertTrue(b.relay.boxSent - boxed >= 2, "une copie par appareil, chacune dans sa boîte")
        assertEquals("Salut Alice", a2.messages(b.me.code).firstOrNull { it.id == hello.id }?.text)
        // Chacun accuse réception : le message n'attend plus personne.
        wait("accusés des deux appareils", timeout = 15.0) {
            b.store.read("outbox", "${hello.id}:${a1.me.code}", OutItem) == null &&
                b.store.message(a1.me.code, hello.id)?.tick == Message.Tick.delivered
        }

        // La tablette écrit : Bob le reçoit, le téléphone l'affiche comme envoyé.
        a2.ensureDirectChat(b.me.code)
        val reply = assertNotNull(a2.send(b.me.code, Content(text = "Envoyé de la tablette")))
        wait("reçu par Bob") { b.messages(a1.me.code).any { it.id == reply.id } }
        wait("synchronisé sur le téléphone") { a1.messages(b.me.code).any { it.id == reply.id } }
        val mirrored = assertNotNull(a1.messages(b.me.code).firstOrNull { it.id == reply.id })
        assertEquals(a1.me.code, mirrored.from)
        assertEquals("Envoyé de la tablette", mirrored.text)
        // Le téléphone a dit l'avoir (`sync`/`got`) : la tablette n'attend plus.
        wait("sync/got", timeout = 15.0) { a2.store.read("outbox", "${reply.id}:${a1.me.code}", OutItem) == null }

        // Lu sur la tablette : lu sur le téléphone.
        assertTrue((a1.chat(b.me.code)?.unread ?: 0) > 0)
        a2.markRead(b.me.code)
        wait("lu partout") { a1.chat(b.me.code)?.unread == 0 }
        // Épinglée sur le téléphone : épinglée sur la tablette.
        a1.updateChat(b.me.code) { it.copy(pinned = true) }
        wait("état de la discussion") { a2.chat(b.me.code)?.pinned == true }

        // Un appel pris sur le téléphone : la tablette cesse de sonner.
        var elsewhere: String? = null
        a2.onCallSignal = { from, body, _ ->
            if (from == a2.me.code && body is Body.Call && body.op == "elsewhere") elsewhere = body.callId
        }
        a1.callTaken("appel-1")
        wait("pris ailleurs") { elsewhere == "appel-1" }

        // Le téléphone retire la tablette : elle efface tout ; Bob ne lui écrit plus.
        var gone = false
        a2.onUnlinked = { gone = true }
        a1.unlinkDevice(a2.dev)
        wait("la tablette retirée", timeout = 15.0) { gone }
        assertEquals(listOf(a1.dev), a1.devices.list.map { it.dev })
        wait("Bob l'apprend") { b.contact(a1.me.code)?.devs?.map { it.d } == listOf(a1.dev) }
        val after = assertNotNull(b.send(a1.me.code, Content(text = "Encore là ?")))
        wait("toujours reçu sur le téléphone") { a1.messages(b.me.code).any { it.id == after.id } }
    }

    /**
     * v4 : une tablette retirée pendant qu'elle était hors ligne l'apprend à
     * son retour (le relais le lui dit avant de la refuser), et efface tout.
     */
    @Test
    fun testDeviceRemovedWhileOffline() = phones.run {
        val url = server()
        val a1 = phone("Alice", url)
        wait("connexion") { a1.netState == Relay.State.online }
        val (a2, _) = linkTablet(a1, url)

        a2.relay.stop()
        a1.unlinkDevice(a2.dev)
        assertEquals(listOf(a1.dev), a1.devices.list.map { it.dev })
        var gone = false
        a2.onUnlinked = { gone = true }
        a2.relay.start()
        wait("la tablette retirée l'apprend à son retour", timeout = 20.0) { gone }
    }

    /**
     * v4 : le relais ne garde rien sur disque ; après son redémarrage, le
     * téléphone lui redit la tablette retirée, qui l'apprend à son retour.
     */
    @Test
    fun testRemovalSurvivesARelayRestart() = phones.run {
        val url = server()
        val flag = restartFlag()
        val a1 = phone("Alice", url)
        wait("connexion") { a1.netState == Relay.State.online }
        val (a2, _) = linkTablet(a1, url)

        a2.relay.stop()
        a1.unlinkDevice(a2.dev)
        assertEquals(listOf(a2.dev), a1.devices.gone)
        val before = a1.relay.boot
        File(flag).createNewFile()
        wait("le téléphone reconnecté au nouveau relais", timeout = 40.0) { a1.netState == Relay.State.online && a1.relay.boot != before }
        delay(500)
        var gone = false
        a2.onUnlinked = { gone = true }
        a2.relay.start()
        wait("la tablette retirée l'apprend, même après le redémarrage", timeout = 20.0) { gone }
    }
}
