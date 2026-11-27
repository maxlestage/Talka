// Le protocole v3 (`V3Engine`), comme les tests du navigateur
// (`client/src/v3.rs`) et de l'iPhone (`V3Tests.swift`) : premier message,
// réponses, désordre, doublon, deux côtés qui commencent ensemble, vieux
// message rejoué sans réparation, session perdue qui guérit, gros contenu en
// ancien format ; et le cliquet post-quantique (v6). (Le moteur entier, ce
// qu'il garde et où, viendra avec `Engine`.)

package talka.kit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import talka.brocolis.Erreur
import talka.brocolis.Paquet
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class V3Tests {
    class Side(engine: V3Engine? = null, val id: Identity = Identity.create()) {
        var engine: V3Engine = engine ?: V3Engine(null, 1_000uL)
    }

    private fun bundleOf(s: Side) =
        assertNotNull(s.engine.published(s.id)).let { (bundle, sig) ->
            assertNotNull(V3.verifyBundle(s.id.code, s.id.sign, s.id.dh, bundle, sig))
        }

    private fun send(from: Side, to: Side, bundle: Boolean, plain: String): V3.Sealed =
        assertNotNull(from.engine.seal(from.id, to.id.code, to.id.dh, if (bundle) bundleOf(to) else null, plain))

    private fun recv(to: Side, from: Side, s: V3.Sealed, now: ULong = 2_000uL): V3.Opened =
        to.engine.open(to.id, from.id.code, from.id.dh, s.body, now)

    private fun inner(to: Side, from: Side, s: V3.Sealed): String =
        assertNotNull(TalkaCrypto.open(to.id, from.id.code, from.id.dh, s.body))

    private fun k(o: V3.Opened): Int? {
        val wire = (o as? V3.Opened.V3)?.wire ?: return null
        return (JSON.parse(wire) as? JsonObject)?.get("k").asInt()
    }

    private fun v3(wire: String): V3.Opened = V3.Opened.V3(wire)

    /**
     * Le cliquet post-quantique (v6, §7.17), comme le navigateur : l'annonce,
     * l'enveloppe, la rotation, la relecture et la clé perdue.
     */
    @Test
    fun testThePostQuantumRatchetStartsRotatesAndHeals() {
        assertTrue(Pq6.available, "ML-KEM : BouncyCastle")
        val a = Side()
        val b = Side()
        a.engine.startPq(null, 1_000uL)
        b.engine.startPq(null, 1_000uL)
        val m1 = send(a, b, true, "{\"k\":1}")
        assertTrue(V3.isToken(inner(b, a, m1)))
        assertEquals(1, k(recv(b, a, m1)))
        assertTrue(b.engine.pqWith(a.id.code))
        val m2 = send(b, a, false, "{\"k\":2}")
        assertTrue(Pq6.isWrapped(inner(a, b, m2)))
        assertEquals(2, k(recv(a, b, m2)))
        val m3 = send(a, b, false, "{\"k\":3}")
        assertTrue(Pq6.isWrapped(inner(b, a, m3)))
        assertTrue(inner(b, a, m3).charCount() < 3_000, "sans annonce")
        assertEquals(3, k(recv(b, a, m3)))
        // Une heure après, la clé de A tourne ; B l'apprend et s'en sert.
        val old = a.engine.pq?.current?.id
        a.engine.maintainPq(1_000uL + Pq6.rotateSecs)
        assertNotEquals(old, a.engine.pq?.current?.id)
        assertEquals(4, k(recv(b, a, send(a, b, false, "{\"k\":4}"))))
        assertEquals(5, k(recv(a, b, send(b, a, false, "{\"k\":5}"))))
        assertEquals(a.engine.pq?.current?.id, a.engine.peers[b.id.code]?.pq?.told)
        // Tout se garde et se relit.
        val dirty = a.engine.takeDirty()
        val keys = assertNotNull(dirty.pq)
        val peer = assertNotNull(dirty.peers.firstOrNull { it.code == b.id.code }?.json)
        val again = V3Engine(a.engine.device.toJson(), 1_000uL)
        again.startPq(keys, 1_000uL + Pq6.rotateSecs)
        again.load(b.id.code, peer)
        a.engine = again
        assertEquals(6, k(recv(a, b, send(b, a, false, "{\"k\":6}"))))
        // Ses clés perdues (une restauration) : à réparer, comme une session.
        a.engine.pq = Pq6.Keys()
        a.engine.maintainPq(10_000uL)
        assertEquals(V3.Opened.Failed(V3.Failure.V3(Erreur.CleInconnue)), recv(a, b, send(b, a, false, "{\"k\":7}")))
    }

    /** Une annonce qui n'est pas partie (l'ancien format, faute de session) part avec le message suivant. */
    @Test
    fun testAnAdvertThatDidNotLeaveIsSentAgain() {
        assertTrue(Pq6.available, "ML-KEM : BouncyCastle")
        val a = Side()
        val b = Side()
        a.engine.startPq(null, 1_000uL)
        b.engine.startPq(null, 1_000uL)
        assertFalse(V3.isToken(inner(b, a, send(a, b, false, "{\"k\":0}"))))
        assertEquals(1, k(recv(b, a, send(a, b, true, "{\"k\":1}"))))
        assertTrue(b.engine.pqWith(a.id.code))
    }

    /** Sans cliquet post-quantique d'un côté (une version d'avant), rien ne change. */
    @Test
    fun testWithoutThePostQuantumRatchetNothingChanges() {
        assertTrue(Pq6.available, "ML-KEM : BouncyCastle")
        val a = Side()
        val b = Side()
        a.engine.startPq(null, 1_000uL)
        assertEquals(1, k(recv(b, a, send(a, b, true, "{\"k\":1}"))))
        val m2 = send(b, a, false, "{\"k\":2}")
        assertTrue(V3.isToken(inner(a, b, m2)))
        assertEquals(2, k(recv(a, b, m2)))
        assertTrue(V3.isToken(inner(b, a, send(a, b, false, "{\"k\":3}"))))
        assertFalse(a.engine.pqWith(b.id.code))
    }

    @Test
    fun testBundleIsBoundToTheTalkaCode() {
        val a = Side()
        val b = Side()
        val (bundle, sig) = assertNotNull(a.engine.published(a.id))
        // Hybride ML-KEM-768 (BouncyCastle, partout sur Android).
        assertEquals(if (V3.postQuantum) Paquet.bundleLengthPq else Paquet.bundleLengthClassic, unb64(bundle)?.size)
        assertNotNull(V3.verifyBundle(a.id.code, a.id.sign, a.id.dh, bundle, sig))
        // Signé par un autre, ou présenté sous un autre code : refusé.
        val (_, other) = assertNotNull(b.engine.published(b.id))
        assertNull(V3.verifyBundle(a.id.code, a.id.sign, a.id.dh, bundle, other))
        assertNull(V3.verifyBundle(b.id.code, b.id.sign, b.id.dh, bundle, sig))
        assertNull(V3.verifyBundle(b.id.code, a.id.sign, a.id.dh, bundle, sig))
        // Sans le contexte : refusé.
        val raw = a.id.sign(assertNotNull(unb64(bundle)))
        assertNull(V3.verifyBundle(a.id.code, a.id.sign, a.id.dh, bundle, raw))
        // Un octet changé, même re-signé par la bonne identité : refusé (Ed25519).
        val bytes = assertNotNull(unb64(bundle))
        bytes[40] = (bytes[40].toInt() xor 1).toByte()
        assertNull(V3.verifyBundle(a.id.code, a.id.sign, a.id.dh, b64(bytes), sig))
        val resigned = a.id.sign(V3.bundleMessage(bytes))
        assertNull(V3.verifyBundle(a.id.code, a.id.sign, a.id.dh, b64(bytes), resigned))
        // Trop gros : refusé avant toute vérification.
        val big = ByteArray(V3.maxBundle + 1) { 7 }
        assertNull(V3.verifyBundle(a.id.code, a.id.sign, a.id.dh, b64(big), a.id.sign(V3.bundleMessage(big))))
    }

    @Test
    fun testFirstMessageRepliesDisorderAndReplay() {
        val a = Side()
        val b = Side()
        val m1 = send(a, b, true, """{"k":"un"}""")
        val m2 = send(a, b, false, """{"k":"deux"}""")
        assertTrue(m1.v3 && m2.v3)
        // Dans le désordre : le second premier message d'abord.
        assertEquals(v3("""{"k":"deux"}"""), recv(b, a, m2))
        assertEquals(v3("""{"k":"un"}"""), recv(b, a, m1))
        // Un doublon (le relais renvoie) : ignoré.
        assertEquals(V3.Opened.Replay, recv(b, a, m1))
        assertEquals(V3.Protection.Ratchet(V3.postQuantum), b.engine.sessionProtection(a.id.code))
        // La réponse, sans paquet : la session suffit.
        val r = send(b, a, false, """{"k":"réponse"}""")
        assertTrue(r.v3)
        assertEquals(v3("""{"k":"réponse"}"""), recv(a, b, r))
        val m3 = send(a, b, false, """{"k":"trois"}""")
        assertEquals(v3("""{"k":"trois"}"""), recv(b, a, m3))
        assertEquals(V3.Opened.Replay, recv(b, a, m3))
        // Pas pour lui : illisible.
        val c = Side()
        assertEquals(V3.Opened.Failed(V3.Failure.Envelope), recv(c, a, m3))
    }

    @Test
    fun testLegacyWithoutBundleAndForLargePayloads() {
        val a = Side()
        val b = Side()
        val old = send(a, b, false, """{"k":"ancien"}""")
        assertFalse(old.v3)
        // Exactement l'ancien format : `TalkaCrypto.open` le lit seul.
        assertEquals("""{"k":"ancien"}""", TalkaCrypto.open(b.id, a.id.code, a.id.dh, old.body))
        assertEquals(V3.Opened.Legacy("""{"k":"ancien"}"""), recv(b, a, old))
        // Une session existe, mais une photo de 300 Kio ne tient pas dans un
        // jeton v3 : elle part dans l'ancien format.
        send(a, b, true, "{}")
        assertTrue(a.engine.hasSession(b.id.code))
        val big = """{"data":"""" + "x".repeat(300 * 1024) + """"}"""
        val sealed = send(a, b, false, big)
        assertFalse(sealed.v3)
        assertEquals(V3.Opened.Legacy(big), recv(b, a, sealed))
    }

    @Test
    fun testBothSidesStartAtOnce() {
        val a = Side()
        val b = Side()
        val fromA = send(a, b, true, """{"k":"a"}""")
        val fromB = send(b, a, true, """{"k":"b"}""")
        assertEquals(v3("""{"k":"a"}"""), recv(b, a, fromA))
        assertEquals(v3("""{"k":"b"}"""), recv(a, b, fromB))
        for (i in 0 until 4) {
            val x = send(a, b, false, """{"a":$i}""")
            val y = send(b, a, false, """{"b":$i}""")
            assertEquals(v3("""{"b":$i}"""), recv(a, b, y))
            assertEquals(v3("""{"a":$i}"""), recv(b, a, x))
        }
        // Deux sessions au plus par contact, même si l'un recommence.
        assertEquals(2, a.engine.peers[b.id.code]?.sessions?.size)
        a.engine.forget(b.id.code)
        val again = send(a, b, true, """{"k":"encore"}""")
        assertEquals(v3("""{"k":"encore"}"""), recv(b, a, again))
        assertEquals(2, b.engine.peers[a.id.code]?.sessions?.size)
    }

    @Test
    fun testStatesSurviveAReloadAndALostSessionHeals() {
        val a = Side()
        val b = Side()
        val m = send(a, b, true, """{"k":1}""")
        if (recv(b, a, m) !is V3.Opened.V3) fail("illisible")
        // Ce qui a changé, et rien de plus (l'appareil a été créé, puis il a
        // retenu le premier message).
        val dirty = b.engine.takeDirty()
        val device = assertNotNull(dirty.device)
        assertEquals(1, dirty.peers.size)
        assertTrue(b.engine.takeDirty().peers.isEmpty())
        // Relu depuis ce qui a été gardé.
        val b2 = Side(V3Engine(device, 2_000uL), b.id)
        assertFalse(b2.engine.isLoaded(a.id.code))
        b2.engine.load(a.id.code, dirty.peers[0].json)
        assertNull(b2.engine.takeDirty().device)
        val r = send(b2, a, false, """{"k":2}""")
        assertTrue(r.v3)
        assertEquals(v3("""{"k":2}"""), recv(a, b2, r))
        // Le premier message rejoué après relecture : toujours ignoré.
        assertEquals(V3.Opened.Replay, recv(b2, a, m))

        // B perd ses sessions (données effacées) : le message suivant de A est
        // illisible — une session perdue, à réparer —, B en ouvre une neuve,
        // que A accepte.
        val b3 = Side(V3Engine(device, 3_000uL), b.id)
        val lost = send(a, b3, false, """{"k":3}""")
        val failed = recv(b3, a, lost) as? V3.Opened.Failed ?: fail("lisible ?")
        val e = (failed.failure as? V3.Failure.V3)?.error ?: fail("$failed")
        assertTrue(V3.needsRepair(e), "$e")
        b3.engine.forget(a.id.code)
        val heal = send(b3, a, true, """{"k":"profil"}""")
        assertEquals(v3("""{"k":"profil"}"""), recv(a, b3, heal))
        val after = send(a, b3, false, """{"k":4}""")
        assertEquals(v3("""{"k":4}"""), recv(b3, a, after))
    }

    @Test
    fun testAnOldReplayedMessageNeverResetsTheSession() {
        // Un message d'une chaîne plus vieille que celles que la session
        // retient (16 cliquets), rejoué par le relais : refusé, sans
        // réparation (sinon rejouer suffirait à faire tomber la session).
        val a = Side()
        val b = Side()
        val first = send(a, b, true, """{"k":"bonjour"}""")
        assertEquals(v3("""{"k":"bonjour"}"""), recv(b, a, first))
        val old = send(a, b, false, """{"k":"vieux"}""")
        assertEquals(v3("""{"k":"vieux"}"""), recv(b, a, old))
        for (i in 0 until 20) {
            val r = send(b, a, false, """{"r":$i}""")
            assertEquals(v3("""{"r":$i}"""), recv(a, b, r))
            val m = send(a, b, false, """{"m":$i}""")
            assertEquals(v3("""{"m":$i}"""), recv(b, a, m))
        }
        for (replayed in listOf(old, first)) {
            when (val o = recv(b, a, replayed)) {
                V3.Opened.Replay -> {}
                is V3.Opened.Failed -> {
                    val e = (o.failure as? V3.Failure.V3)?.error ?: fail("$o")
                    assertFalse(V3.needsRepair(e), "$e")
                }
                else -> fail("$o")
            }
        }
        // La session tient toujours.
        val after = send(a, b, false, """{"k":"après"}""")
        assertEquals(v3("""{"k":"après"}"""), recv(b, a, after))
    }

    /** Les mots du navigateur (`client/src/ui.rs`, fiche du contact). */
    @Test
    fun testProtectionLabelsAreTheBrowsers() {
        assertEquals("🔐 Session à double cliquet (post-quantique)", V3.Protection.Ratchet(true).label)
        assertEquals("🔐 Session à double cliquet (classique)", V3.Protection.Ratchet(false).label)
        assertEquals("Ancien chiffrement (le contact n'a pas encore la mise à jour)", V3.Protection.Legacy.label)
    }

    @Test
    fun testOnlyALostSessionIsRepaired() {
        assertTrue(V3.needsRepair(Erreur.SessionInconnue))
        assertTrue(V3.needsRepair(Erreur.CleInconnue))
        assertFalse(V3.needsRepair(Erreur.Dechiffrement))
        assertFalse(V3.needsRepair(Erreur.Rejoue))
        assertFalse(V3.needsRepair(Erreur.NouvelleSession))
    }

    @Test
    fun testRotationKeepsOldFirstMessagesReadable() {
        val a = Side()
        val b = Side()
        val oldBundle = bundleOf(b)
        assertFalse(b.engine.maintain(1_000uL))
        assertTrue(b.engine.maintain(1_000uL + Paquet.prekeyRotation))
        assertNotEquals(oldBundle.spkId, bundleOf(b).spkId)
        val m = assertNotNull(a.engine.seal(a.id, b.id.code, b.id.dh, oldBundle, "{}"))
        assertEquals(v3("{}"), recv(b, a, m))
        // Un mois plus tard, l'ancienne clé est effacée : un premier message
        // qui la vise est une session perdue (le contact en rouvrira une).
        b.engine.maintain(1_000uL + 2uL * Paquet.prekeyRotation)
        assertTrue(b.engine.maintain(1_000uL + 2uL * Paquet.prekeyRotation + Paquet.prekeyGrace + Paquet.prekeyRotation))
        val c = Side()
        val late = assertNotNull(c.engine.seal(c.id, b.id.code, b.id.dh, oldBundle, "{}"))
        assertEquals(
            V3.Opened.Failed(V3.Failure.V3(Erreur.CleInconnue)),
            recv(b, c, late, 1_000uL + 3uL * Paquet.prekeyRotation + Paquet.prekeyGrace),
        )
    }

    /** Les états écrits ici ont la forme de ceux du navigateur. */
    @Test
    fun testStoredPeerHasTheBrowserShape() {
        val a = Side()
        val b = Side()
        recv(b, a, send(a, b, true, "{}"))
        val json = assertNotNull(b.engine.takeDirty().peers.firstOrNull()?.json)
        val obj = assertNotNull(JSON.parse(json) as? JsonObject)
        assertEquals(16, obj["current"].asString()?.length)
        assertEquals(1, (obj["sessions"] as? JsonArray)?.size)
    }
}

/**
 * Les transactions de la mémoire s'imbriquent (`V3EngineStoreTests` de
 * l'iPhone : le seul de ses tests qui ne demande pas le moteur, ici sur une
 * mémoire seule).
 */
class V3StoreTests {
    private val stores = Stores()

    @AfterTest
    fun tearDown() = stores.closeAll()

    @Test
    fun testTransactionsNest() {
        val dir = stores.dir()
        val s = stores.open(dir)
        s.transaction {
            s.writeRaw("kv", "x", "1")
            s.transaction { s.writeRaw("kv", "y", "2") }
        }
        s.close()
        val again = stores.open(s.dir)
        assertEquals("1", again.readRaw("kv", "x"))
        assertEquals("2", again.readRaw("kv", "y"))
    }
}
