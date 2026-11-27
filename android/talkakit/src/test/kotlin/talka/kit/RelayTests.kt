// Le relais : la clé du relais authentifié (v6) sans réseau, comme l'iPhone
// (`ios/Tests/TalkaKitTests/V6RelayKeyTests.swift`) ; puis le protocole
// contre un faux relais local (OkHttp MockWebServer) : défi signé,
// présentation, accusés, clés, paquets v3, expéditeur scellé, boîtes
// tournantes et reconnexion.

package talka.kit

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class V6RelayKeyTests {
    val server = P256.newPrivate()
    val serverKey: String get() = b64(P256.publicKey(server)!!)

    fun sig(key: ByteArray, message: ByteArray): String = b64(P256.sign(key, message)!!)

    fun json(vararg pairs: Pair<String, Any?>): String = JSON.write(jsonOf(*pairs))

    fun relay(): Pair<Relay, Identity> {
        val me = Identity.create()
        val relay = Relay(me, "https://talka.example")
        relay.dev = "00000000000000a1"
        relay.receive(json("t" to "challenge", "nonce" to "n1", "time" to 1.0))
        return relay to me
    }

    @Test
    fun testTheFirstProvenKeyIsPinnedAndSignedRemovalsObeyed() {
        val (relay, me) = relay()
        var pinned: String? = null
        var unlinked = 0
        var warned = 0
        relay.onRelayKey = { pinned = it }
        relay.onUnlinked = { unlinked += 1 }
        relay.onUnprovenRelay = { warned += 1 }
        val ready = RelayKey.readyMessage("n1", me.code, "00000000000000a1")
        relay.receive(json("t" to "ready", "relais" to serverKey, "relais_sig" to sig(server, ready)))
        assertEquals(serverKey, pinned)
        assertEquals(serverKey, relay.relayKey)
        // Non signé, ou signé d'une autre clé : ignoré, et dit une fois.
        relay.receive(json("t" to "unlinked"))
        val removal = RelayKey.unlinkedMessage(me.code, "00000000000000a1")
        relay.receive(json("t" to "unlinked", "sig" to sig(P256.newPrivate(), removal)))
        assertEquals(0, unlinked)
        assertEquals(1, warned)
        // Signé de la clé retenue : obéi.
        relay.receive(json("t" to "unlinked", "sig" to sig(server, removal)))
        assertEquals(1, unlinked)
    }

    @Test
    fun testAnotherKeyIsReportedAndNotPinned() {
        val (relay, me) = relay()
        relay.relayKey = serverKey
        var pinned: String? = null
        var warned = 0
        relay.onRelayKey = { pinned = it }
        relay.onUnprovenRelay = { warned += 1 }
        val other = P256.newPrivate()
        val ready = RelayKey.readyMessage("n1", me.code, "00000000000000a1")
        relay.receive(json("t" to "ready", "relais" to b64(P256.publicKey(other)!!), "relais_sig" to sig(other, ready)))
        assertNull(pinned)
        assertEquals(serverKey, relay.relayKey)
        assertEquals(1, warned)
        // Plus de preuve du tout : pareil (sans redire).
        relay.receive(json("t" to "ready"))
        assertEquals(1, warned)
    }

    @Test
    fun testWithoutAKeyNothingChanges() {
        val (relay, _) = relay()
        var unlinked = 0
        var warned = 0
        relay.onUnlinked = { unlinked += 1 }
        relay.onUnprovenRelay = { warned += 1 }
        relay.receive(json("t" to "ready"))
        relay.receive(json("t" to "unlinked"))
        assertEquals(1, unlinked)
        assertEquals(0, warned)
        assertEquals("", relay.relayKey)
    }
}

/** Un faux relais : il présente un défi, vérifie la signature, et répond comme le vrai. */
class FakeRelay(private val v3: Boolean) {
    val server = MockWebServer()

    /** Ce que le relais a reçu, dans l'ordre (toutes connexions). */
    val received = CopyOnWriteArrayList<JsonObject>()
    val connections = AtomicInteger()
    val sockets = CopyOnWriteArrayList<WebSocket>()

    /** Les présentations dont la signature du défi est juste. */
    val verified = AtomicInteger()

    /** Les envois qui restent sans réponse (pour voir ce qui se passe à la coupure). */
    @Volatile
    var answerSends = true

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(Connection("nonce-${connections.incrementAndGet()}"))
        }
        server.start()
    }

    val url: String get() = server.url("/").toString()

    private inner class Connection(val nonce: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets.add(webSocket)
            webSocket.send(JSON.write(jsonOf("t" to "challenge", "nonce" to nonce, "time" to nowMs() + 5_000)))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSON.parse(text) as JsonObject
            received.add(msg)
            fun reply(vararg pairs: Pair<String, Any?>) {
                webSocket.send(JSON.write(jsonOf(*pairs)))
            }
            when (msg.str("t")) {
                "hello" -> {
                    if (TalkaCrypto.verify(msg.str("sign"), "talka-auth:$nonce".toByteArray(), msg.str("sig"))) {
                        verified.incrementAndGet()
                        reply("t" to "ready", "boot" to "B007", "sealed" to true)
                    } else {
                        webSocket.close(4001, "signature")
                    }
                }
                "anon" -> reply("t" to "ready", "boites" to true)
                "ping" -> reply("t" to "pong")
                "send" -> if (answerSends) reply("t" to "ack", "id" to msg.str("id"))
                "lookup" ->
                    if (msg.str("code") == "INCONNU00") {
                        reply("t" to "keys", "code" to msg.str("code"), "unknown" to true)
                    } else {
                        reply("t" to "keys", "code" to msg.str("code"), "sign" to "S", "dh" to "D")
                    }
                "publish-bundle" -> if (v3) reply("t" to "published", "ok" to true)
                "fetch-bundle" -> if (v3) reply("t" to "bundle", "code" to msg.str("code"), "none" to true)
                "boites" -> reply("t" to "msg", "n" to 7, "id" to "e1", "boite" to msg["list"]!!.jsonArray.first().jsonPrimitive.content, "body" to "B")
                else -> {}
            }
        }
    }

    fun of(t: String): List<JsonObject> = received.filter { it.str("t") == t }

    fun shutdown() {
        for (s in sockets) s.close(1001, null)
        server.shutdown()
    }
}

class RelayTests {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val fakes = ArrayList<FakeRelay>()
    private val relays = ArrayList<Relay>()

    @AfterTest
    fun cleanUp() {
        runBlocking(dispatcher) { for (r in relays) r.stop() }
        for (f in fakes) f.shutdown()
        executor.shutdownNow()
    }

    fun fake(v3: Boolean = true): FakeRelay = FakeRelay(v3).also { fakes.add(it) }

    fun relay(f: FakeRelay, me: Identity = Identity.create()): Relay =
        Relay(me, f.url, dispatcher).also { relays.add(it) }

    private suspend fun until(what: String, timeoutMs: Long = 10_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            if (System.currentTimeMillis() > end) fail("trop long : $what")
            delay(20)
        }
    }

    @Test
    fun testTheWebSocketOfAServer() {
        assertEquals("wss://talka.example/ws", Relay.wsURL("https://talka.example"))
        assertEquals("wss://talka.example/ws", Relay.wsURL("https://talka.example/"))
        assertEquals("ws://127.0.0.1:3000/ws", Relay.wsURL("http://127.0.0.1:3000"))
        assertEquals("wss://exemple.fr/talka/ws", Relay.wsURL("https://exemple.fr/talka/"))
    }

    /** Le défi est signé, la présentation a les champs du navigateur, et le relais répond. */
    @Test
    fun testHelloAckLookupAndWatch() =
        runBlocking(dispatcher) {
            val f = fake()
            val r = relay(f)
            val states = ArrayList<Relay.State>()
            r.onState = { states.add(it) }
            r.watch(listOf("AAAAAAAAAA"))
            r.start()
            until("connexion") { r.state == Relay.State.online }
            assertEquals(listOf(Relay.State.connecting, Relay.State.online), states)
            assertEquals(1, f.verified.get())
            val hello = f.of("hello").single()
            assertEquals(setOf("t", "sign", "dh", "sig", "hidden"), hello.keys)
            assertEquals(r.identity.sign, hello.str("sign"))
            assertEquals("B007", r.boot)
            assertTrue(r.sealed)
            // L'heure du relais (5 secondes d'avance).
            assertTrue(r.now() - nowMs() in 4_000.0..6_000.0)
            // Les codes suivis avant la connexion partent avec elle ; un nouveau seulement ensuite.
            until("suivi") { f.of("watch").isNotEmpty() }
            r.watch(listOf("AAAAAAAAAA", "BBBBBBBBBB", "BBBBBBBBBB"))
            until("suivi") { f.of("watch").size == 2 }
            assertEquals(listOf(listOf("AAAAAAAAAA"), listOf("BBBBBBBBBB")), f.of("watch").map { w -> w["codes"]!!.jsonArray.map { it.jsonPrimitive.content } })

            assertEquals(Relay.Ack.Ok, r.send("m1", "WGSEAJNSZJ", body = "corps", live = false, wake = true, apercu = "A"))
            val sent = f.of("send").single()
            assertEquals(
                jsonOf("t" to "send", "id" to "m1", "to" to "WGSEAJNSZJ", "body" to "corps", "live" to false, "wake" to true, "apercu" to "A"),
                sent,
            )
            assertNull(r.lookup("INCONNU00"))
            assertEquals("S", r.lookup("WGSEAJNSZJ")?.str("sign"))
            r.got(listOf(3, 4))
            r.got(emptyList())
            until("reçus") { f.of("got").isNotEmpty() }
            assertEquals(listOf(3, 4), f.of("got").single()["n"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
            assertTrue(r.unlink("00000000000000b2"))
            until("retrait") { f.of("unlink").isNotEmpty() }
            assertEquals("00000000000000b2", f.of("unlink").single().str("dev"))
        }

    /** Un relais d'avant v3 ne répond pas à la publication : le `pong` qui suit le dit. */
    @Test
    fun testARelayWithoutV3IsDetectedByThePong() =
        runBlocking(dispatcher) {
            val f = fake(v3 = false)
            val r = relay(f)
            r.start()
            until("connexion") { r.state == Relay.State.online }
            assertNull(r.relayV3)
            assertTrue(r.publishBundle("UEFRVUVU", "SIG", dev = "00000000000000a1"))
            until("pong") { r.relayV3 != null }
            assertEquals(false, r.relayV3)
            val published = f.of("publish-bundle").single()
            assertEquals(jsonOf("t" to "publish-bundle", "bundle" to "UEFRVUVU", "sig" to "SIG", "dev" to "00000000000000a1"), published)
            // Plus la peine de demander un paquet.
            assertNull(r.fetchBundle("WGSEAJNSZJ"))
            assertTrue(f.of("fetch-bundle").isEmpty())
        }

    @Test
    fun testARelayWithV3PublishesAndAnswersBundles() =
        runBlocking(dispatcher) {
            val f = fake(v3 = true)
            val r = relay(f)
            r.start()
            until("connexion") { r.state == Relay.State.online }
            assertTrue(r.publishBundle("UEFRVUVU", "SIG"))
            until("publié") { r.relayV3 != null }
            assertEquals(true, r.relayV3)
            val bundle = assertNotNull(r.fetchBundle("WGSEAJNSZJ"))
            assertEquals(true, bundle.bool("none"))
            assertEquals("WGSEAJNSZJ", bundle.str("code"))
        }

    /** L'expéditeur scellé : une seconde connexion, anonyme, qui ne se présente pas. */
    @Test
    fun testSealedSendGoesThroughTheAnonymousConnection() =
        runBlocking(dispatcher) {
            val f = fake()
            val r = relay(f)
            r.jitter = false
            r.start()
            until("connexion") { r.state == Relay.State.online }
            val peer = Contact(code = "WGSEAJNSZJ", sign = "SG", dh = "DH", jeton = "J.T")
            assertEquals(Relay.Ack.Ok, r.sendSealed("s1", peer, dev = "00000000000000c3", body = "B", live = true, wake = false))
            assertEquals(1, r.sealedSent)
            assertEquals(1, f.of("anon").size)
            assertEquals(1, f.of("hello").size, "la connexion anonyme ne se présente pas")
            assertEquals(
                jsonOf(
                    "t" to "send", "id" to "s1", "to" to "WGSEAJNSZJ", "sign" to "SG", "dh" to "DH", "jeton" to "J.T",
                    "body" to "B", "live" to true, "dev" to "00000000000000c3",
                ),
                f.of("send").single(),
            )
            assertEquals(Relay.Ack.Ok, r.sendBoite("s2", "BOITE", body = "B", live = false, wake = true, apercu = "A", ttl = 60))
            assertEquals(1, r.boxSent)
            assertEquals(
                jsonOf("t" to "send", "id" to "s2", "boite" to "BOITE", "body" to "B", "live" to false, "ttl" to 60, "wake" to true, "apercu" to "A"),
                f.of("send").last(),
            )
        }

    /** Les boîtes tournantes : une connexion anonyme qui s'y abonne et reçoit ce qui y arrive. */
    @Test
    fun testRotatingBoxesAreListenedTo() =
        runBlocking(dispatcher) {
            val f = fake()
            val r = relay(f)
            val got = ArrayList<JsonObject>()
            r.onBoxEnvelope = { got.add(it) }
            r.boxes.listen(listOf("B1", "B2"))
            until("boîte") { got.isNotEmpty() }
            assertEquals(listOf("B1", "B2"), f.of("boites").single()["list"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("B1", got.single().str("boite"))
            assertEquals(7, got.single().int("n"))
            r.boxes.got(listOf(7))
            until("rangé") { f.of("got").isNotEmpty() }
            r.boxes.listen(emptyList())
            assertNull(r.boxes.socket)
        }

    /** La connexion coupée : ce qui attendait échoue (`offline`), et le relais se reconnecte seul. */
    @Test
    fun testReconnectsAfterTheRelayCloses() =
        runBlocking(dispatcher) {
            val f = fake()
            val r = relay(f)
            r.start()
            until("connexion") { r.state == Relay.State.online }
            f.answerSends = false
            val waiting = async { r.send("m1", "WGSEAJNSZJ", body = "B", live = false) }
            until("envoi") { f.of("send").isNotEmpty() }
            f.sockets.first().close(1001, "redémarrage")
            assertEquals(Relay.Ack.Failed("offline"), waiting.await())
            assertEquals(Relay.State.offline, r.state)
            assertEquals(Relay.Ack.Failed("offline"), r.send("m2", "WGSEAJNSZJ", body = "B", live = false))
            until("reconnexion") { r.state == Relay.State.online }
            assertEquals(2, f.verified.get())
            f.answerSends = true
            assertEquals(Relay.Ack.Ok, r.send("m3", "WGSEAJNSZJ", body = "B", live = false))
            // Arrêté : il ne revient plus.
            r.stop()
            assertEquals(Relay.State.offline, r.state)
            delay(1_500)
            assertEquals(Relay.State.offline, r.state)
            assertFalse(f.connections.get() > 2)
        }
}
