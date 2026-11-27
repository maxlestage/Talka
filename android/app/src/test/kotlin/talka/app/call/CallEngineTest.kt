// Les appels, de bout en bout entre plusieurs téléphones (des `AppModel`
// dans le même processus, sous Robolectric), sans WebRTC : une fausse
// liaison (`FakeLink`) et un faux réseau (`FakeNet`) où chaque signal passe
// en JSON. Ce qui est vérifié : `CallEngine` et `Mesh` ensemble (à deux, à
// trois, à quatre, quelqu'un ajouté en cours, quelqu'un qui part), le refus,
// l'absence de réponse, « occupé », pris ailleurs, le micro refusé ; le
// journal ; le JSON des signaux (les vecteurs du navigateur et de l'iPhone) ;
// et ce que fait Android (le son, le service, la notification plein écran).

package talka.app.call

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.media.AudioManager
import android.os.Looper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import talka.app.AppModel
import talka.app.AppTestBase
import talka.kit.Body
import talka.kit.Mesh
import talka.kit.Relay
import talka.kit.Wire
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.nio.file.Files
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallEngineTest : AppTestBase() {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val net = FakeNet()
    private val opened = ArrayList<AppModel>()
    private val dirs = ArrayList<File>()

    @Before
    fun setUp() {
        install()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    }

    @After
    fun tearDown() {
        for (m in opened) m.close()
        idle()
        dirs.forEach { it.deleteRecursively() }
        CallSystem.current = null
        uninstall()
    }

    /** Un téléphone de plus : son compte, en ligne sur le faux relais. */
    private fun phone(name: String): AppModel {
        val dir = Files.createTempDirectory("talka-appel").toFile()
        dirs.add(dir)
        val m = net.phone(testEnv(app, dir, relay) { guard.unlockCipher(it) }, name)
        opened.add(m)
        idle()
        m.createAccount(name, "")
        until("$name en ligne") { m.engine?.netState == Relay.State.online }
        net.register(m)
        return m
    }

    private fun code(m: AppModel) = m.engine!!.me.code

    private fun call(m: AppModel) = assertNotNull(m.call, "pas d'appel")

    /** Laisse passer `ms` du temps du fil principal (les délais de 45 s, de 1,4 s…). */
    private fun later(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun until(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!done()) {
            idle()
            Thread.sleep(2)
            check(System.currentTimeMillis() < end) { "toujours pas : $what" }
        }
    }

    private fun live(vararg phones: AppModel) =
        until("en ligne : ${phones.map { it.engine?.profile?.name }}") {
            phones.all { it.call?.phase == CallEngine.Phase.live }
        }

    /** Chacun est relié à chacun (et à personne d'autre). */
    private fun meshed(vararg phones: AppModel) {
        until("tous reliés") {
            phones.all { m ->
                val others = phones.filter { it !== m }.map(::code).toSet()
                m.call?.connected == others && m.call?.peers?.keys == others
            }
        }
    }

    @Test
    fun twoPeopleTalkThenHangUp() {
        val lea = phone("Léa")
        val max = phone("Max")
        val am = app.getSystemService(AudioManager::class.java)
        run(lea) { lea.startCall(code(max), video = false) }
        val out = call(lea)
        assertEquals(CallEngine.Phase.calling, out.phase)
        assertEquals("Appel…", out.status)
        // La tonalité d'appel, le mode communication, et le service au premier plan.
        assertEquals(true, out.ringing)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, am.mode)
        assertEquals(CallService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        // Un appel à deux : l'offre part exactement comme avant (ni `members`, ni `chat`).
        val offer = net.sent.single { it.body.op == "offer" }
        val json = Json.parseToJsonElement(offer.json).jsonObject
        assertEquals(setOf("k", "op", "call_id", "video", "sdp"), json.keys)
        assertEquals("call", (json["k"] as JsonPrimitive).content)
        assertEquals(out.id, offer.body.callId)

        idle()
        val inc = call(max)
        assertEquals(CallEngine.Phase.ringing, inc.phase)
        assertEquals("Appel audio entrant…", inc.status)
        assertEquals(false, inc.ringing)
        assertEquals(code(lea), inc.first)
        // Le candidat de Léa est arrivé avant que Max ne réponde : il attend.
        until("le candidat de Léa") { net.sent.any { it.from == code(lea) && it.body.op == "ice" } }

        run(max) { inc.accept() }
        live(lea, max)
        meshed(lea, max)
        assertNull(out.ringing)
        assertNull(inc.ringing)
        assertEquals(listOf("answer", "ice"), net.ops(max, lea).distinct())
        // Les candidats de chacun ont été posés chez l'autre (ceux arrivés tôt aussi).
        val maxLink = net.media(max).links.single()
        val leaLink = net.media(lea).links.single()
        assertTrue(maxLink.added.any { it.sdp.contains("10.0.0.1 ") }, "${maxLink.added}")
        assertEquals(1, leaLink.added.size)
        assertEquals(leaLink.local, maxLink.remote)
        assertEquals(maxLink.local, leaLink.remote)
        assertEquals(lea.engine!!.displayName(code(max)), out.title)
        assertFalse(out.isGroup)

        // Cinq secondes plus tard, Léa raccroche.
        later(5_000)
        out.hangup()
        until("fin chez Max") { inc.phase == CallEngine.Phase.ended }
        assertEquals(CallEngine.Phase.ended, out.phase)
        assertEquals("Appel terminé", out.status)
        assertEquals("Appel terminé", inc.status)
        assertTrue(leaLink.closed && maxLink.closed)
        assertTrue(net.media(lea).stopped && net.media(max).stopped)
        assertEquals(AudioManager.MODE_NORMAL, am.mode)
        // Le journal : 5 secondes, de part et d'autre ; personne n'a manqué l'appel.
        val leaLog = lea.engine!!.callLog().single()
        assertEquals(code(max), leaLog.peer)
        assertFalse(leaLog.incoming)
        assertEquals(5.0, leaLog.dur)
        assertFalse(leaLog.missed)
        assertEquals(emptyList(), leaLog.members)
        val maxLog = max.engine!!.callLog().single()
        assertTrue(maxLog.incoming)
        assertEquals(5.0, maxLog.dur)
        assertFalse(maxLog.missed)
        // L'écran d'appel reste un instant, puis s'en va.
        later(1_500)
        assertNull(lea.call)
        assertNull(max.call)
    }

    @Test
    fun aRefusedCall() {
        val lea = phone("Léa")
        val max = phone("Max")
        run(lea) { lea.startCall(code(max), video = false) }
        idle()
        call(max).hangup()
        until("refusé") { lea.call?.phase == CallEngine.Phase.ended }
        assertEquals("Appel refusé", call(lea).status)
        assertEquals(listOf("reject"), net.ops(max, lea).filter { it != "ice" })
        // Le refus, dans le journal de Max : un appel entrant sans conversation.
        assertTrue(max.engine!!.callLog().single().missed)
        val leaLog = lea.engine!!.callLog().single()
        assertFalse(leaLog.missed)
        assertEquals(0.0, leaLog.dur)
    }

    @Test
    fun noAnswerStopsAfter45Seconds() {
        val lea = phone("Léa")
        val max = phone("Max")
        run(lea) { lea.startCall(code(max), video = true) }
        idle()
        assertEquals("Appel vidéo entrant…", call(max).status)
        later(44_000)
        assertEquals(CallEngine.Phase.calling, call(lea).phase)
        assertEquals(CallEngine.Phase.ringing, call(max).phase)
        later(1_500)
        idle()
        assertEquals("Pas de réponse", call(lea).status)
        assertEquals("Appel manqué", call(max).status)
        assertTrue(net.ops(lea, max).contains("end"))
        val missed = max.engine!!.callLog().single()
        assertTrue(missed.missed && missed.video && missed.incoming)
    }

    @Test
    fun busyWhenAlreadyInACall() {
        val lea = phone("Léa")
        val max = phone("Max")
        val zoe = phone("Zoé")
        run(zoe) { zoe.startCall(code(max), video = false) }
        idle()
        run(max) { call(max).accept() }
        live(zoe, max)
        run(lea) { lea.startCall(code(max), video = false) }
        idle()
        // Max est déjà en ligne : son application répond `busy` (par le moteur,
        // hors de l'appel en cours), que Léa reçoit.
        val id = call(lea).id
        lea.callSignal(code(max), Body.Call("busy", id, false, "", null), true)
        until("occupé") { lea.call?.phase == CallEngine.Phase.ended }
        assertEquals("Occupé", call(lea).status)
        // Max reste avec Zoé ; l'appel de Léa est noté comme manqué.
        assertEquals(CallEngine.Phase.live, call(max).phase)
        assertEquals(code(zoe), call(max).first)
        val log = max.engine!!.callLog().single()
        assertEquals(code(lea), log.peer)
        assertTrue(log.missed)
    }

    @Test
    fun threePeopleThenOneLeaves() {
        val lea = phone("Léa")
        val max = phone("Max")
        val zoe = phone("Zoé")
        run(lea) { lea.startGroupCall(listOf(code(max), code(zoe)), video = false, chat = "") }
        idle()
        val members = listOf(code(lea), code(max), code(zoe)).sorted()
        // Les invitations disent qui en est.
        for (s in net.sent.filter { it.body.op == "offer" }) assertEquals(members, s.body.members)
        assertEquals("Appel de groupe entrant…", call(max).status)
        assertEquals("Appel de groupe entrant…", call(zoe).status)
        assertTrue(call(zoe).isGroup)

        run(max) { call(max).accept() }
        live(lea, max)
        // Zoé sonne encore : Max lui a dit `join`, sans liaison.
        assertTrue("join" in net.ops(max, zoe))
        assertEquals(CallEngine.Phase.ringing, call(zoe).phase)
        run(zoe) { call(zoe).accept() }
        live(lea, max, zoe)
        meshed(lea, max, zoe)
        // Entre Max et Zoé, une seule liaison (`link`), proposée par le plus petit code.
        val (small, big) = listOf(max, zoe).sortedBy(::code)
        assertEquals(listOf("link"), net.ops(small, big).filter { it == "link" || it == "offer" })
        assertTrue("answer" in net.ops(big, small))
        // Sans groupe : le titre, ce sont les autres (triés par code), et rien dessous.
        val others = listOf(max, zoe).sortedBy(::code).map { lea.engine!!.displayName(code(it)) }
        assertEquals("${others[0]} et ${others[1]}", call(lea).title)
        assertEquals("", call(lea).people)

        // Zoé part : les deux autres continuent, et le savent.
        call(zoe).hangup()
        until("Zoé partie") { call(lea).connected == setOf(code(max)) && call(max).connected == setOf(code(lea)) }
        assertEquals(CallEngine.Phase.live, call(lea).phase)
        assertEquals(CallEngine.Phase.live, call(max).phase)
        assertEquals("${lea.engine!!.displayName(code(zoe))} a quitté l'appel.", lea.toast?.text)
        assertEquals("${max.engine!!.displayName(code(zoe))} a quitté l'appel.", max.toast?.text)
        assertEquals(listOf(code(max)), call(lea).participants)
        assertFalse(call(lea).isGroup)

        // Puis Léa : Max reste seul, l'appel est fini.
        later(3_000)
        call(lea).hangup()
        until("fin") { max.call?.phase == CallEngine.Phase.ended }
        assertEquals("Appel terminé", call(max).status)
        // Le journal de Max : un appel de groupe, avec les deux autres.
        val log = max.engine!!.callLog().single()
        assertEquals(listOf(code(lea), code(zoe)).sorted(), log.members)
        assertEquals(code(lea), log.peer)
        assertEquals(3.0, log.dur)
    }

    @Test
    fun peopleAddedDuringTheCall() {
        val lea = phone("Léa")
        val max = phone("Max")
        val zoe = phone("Zoé")
        val sam = phone("Sam")
        run(lea) { lea.startCall(code(max), video = false) }
        idle()
        run(max) { call(max).accept() }
        live(lea, max)
        assertTrue(call(lea).mesh.joined)

        // Léa fait sonner Zoé et Sam : leurs invitations disent qui est déjà là.
        run(lea) { call(lea).add(listOf(code(zoe), code(sam))) }
        idle()
        val invite = net.sent.last { it.to == code(sam) && it.body.op == "offer" }.body
        assertEquals(listOf(code(lea), code(max), code(zoe), code(sam)).sorted(), invite.members)
        assertEquals(CallEngine.Phase.ringing, call(zoe).phase)
        assertEquals(CallEngine.Phase.ringing, call(sam).phase)
        assertEquals("Appel de groupe entrant…", call(sam).status)
        run(zoe) { call(zoe).accept() }
        run(sam) { call(sam).accept() }
        live(lea, max, zoe, sam)
        meshed(lea, max, zoe, sam)
        assertEquals(3, call(max).participants.size)

        // Huit personnes au plus : 4 de plus, puis plus de place.
        val strangers = (1..5).map { talka.kit.Identity.create().code }
        run(lea) { call(lea).add(strangers) }
        assertEquals("Un appel réunit huit personnes au plus.", lea.toast?.text)
        assertEquals(7, call(lea).participants.size)
        // Personne ne répond aux quatre : leurs invitations s'arrêtent, l'appel continue.
        later(46_000)
        idle()
        assertEquals(CallEngine.Phase.live, call(lea).phase)
        assertEquals(listOf(code(max), code(zoe), code(sam)).sorted(), call(lea).participants)
    }

    @Test
    fun takenOnAnotherDevice() {
        val lea = phone("Léa")
        val max = phone("Max")
        run(lea) { lea.startCall(code(max), video = false) }
        idle()
        val inc = call(max)
        // Un autre appareil de Max a répondu (v4) : celui-ci cesse de sonner, sans rien dire à Léa.
        max.callSignal(code(max), Body.Call("elsewhere", inc.id, false, "", null), true)
        assertEquals(CallEngine.Phase.ended, inc.phase)
        assertEquals("Pris sur un autre appareil", inc.status)
        assertNull(inc.ringing)
        assertTrue(net.ops(max, lea).isEmpty())
        assertFalse(max.engine!!.callLog().single().missed)
        assertEquals(CallEngine.Phase.calling, call(lea).phase)
    }

    @Test
    fun microphoneRefused() {
        val lea = phone("Léa")
        val max = phone("Max")
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        run(lea) { lea.startCall(code(max), video = false) }
        assertEquals("Micro ou caméra refusés.", call(lea).status)
        assertTrue(net.sent.isEmpty())

        // Chez l'appelé : refuser le micro refuse l'appel.
        later(1_500)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        run(lea) { lea.startCall(code(max), video = false) }
        idle()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        run(max) { call(max).accept() }
        assertEquals("Micro ou caméra refusés.", call(max).status)
        until("refusé chez Léa") { lea.call?.phase == CallEngine.Phase.ended }
        assertEquals("Appel refusé", call(lea).status)
        // Talka demande la permission par l'activité, quand elle en a une.
        var asked: String? = null
        max.askPermission = { p ->
            asked = p
            false
        }
        later(1_500)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        run(lea) { lea.startCall(code(max), video = false) }
        idle()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        run(max) { call(max).accept() }
        assertEquals(Manifest.permission.RECORD_AUDIO, asked)
    }

    @Test
    fun aVideoCallAndItsButtons() {
        val lea = phone("Léa")
        val max = phone("Max")
        run(lea) { lea.startCall(code(max), video = true) }
        val out = call(lea)
        assertEquals("caméra de Léa", out.localVideo)
        assertTrue(out.speaker)
        assertTrue(net.sent.single { it.body.op == "offer" }.body.video)
        idle()
        run(max) { call(max).accept() }
        live(lea, max)
        until("l'image de Max") { out.remoteVideos[code(max)] != null }
        assertTrue(call(max).remoteVideos.containsKey(code(lea)))
        // `answer` ne porte pas `video` (seuls `offer` et `link`).
        assertFalse(net.sent.single { it.body.op == "answer" }.body.video)

        val media = net.media(lea)
        out.toggleMute()
        assertTrue(out.muted && media.micOff)
        out.toggleCamera()
        assertFalse(out.cameraOn || media.cameraOn)
        out.switchCamera()
        assertFalse(out.frontCamera || media.front)
        out.toggleSpeaker()
        assertFalse(out.speaker)

        // La liaison tombe : l'appel à deux est fini.
        net.media(max).links.single().fail()
        idle()
        assertEquals("La connexion a échoué", call(max).status)
    }

    @Test
    fun aHeadsetTakesTheCallAndTheSpeakerButtonShowsIt() {
        val am = app.getSystemService(AudioManager::class.java)
        fun device(type: Int) = org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        val headset = device(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        shadowOf(am).setAvailableCommunicationDevices(listOf(device(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), headset))
        val lea = phone("Léa")
        val max = phone("Max")
        // Un appel vidéo : le haut-parleur par défaut, mais l'oreillette est là.
        run(lea) { lea.startCall(code(max), video = true) }
        val out = call(lea)
        assertFalse(out.speaker)
        assertEquals(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, am.communicationDevice?.type)
        // Le bouton force le haut-parleur, puis rend l'appel à l'oreillette.
        out.toggleSpeaker()
        assertTrue(out.speaker)
        assertEquals(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, am.communicationDevice?.type)
        out.toggleSpeaker()
        assertEquals(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, am.communicationDevice?.type)
        // L'oreillette s'éteint : le haut-parleur de l'appel vidéo, et le bouton le dit.
        shadowOf(am).removeAvailableCommunicationDevice(headset, true)
        idle()
        assertTrue(out.speaker)
        assertEquals(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, am.communicationDevice?.type)
        out.hangup()
        idle()
        assertNull(am.communicationDevice)
    }

    @Test
    fun anIncomingCallInTheBackgroundRingsInFullScreen() {
        val lea = phone("Léa")
        val max = phone("Max")
        max.engine!!.saveSettings { it.copy(notify = true) }
        max.scene(AppModel.Phase.background)
        run(lea) { lea.startCall(code(max), video = false) }
        idle()
        val inc = call(max)
        val manager = app.getSystemService(NotificationManager::class.java)
        val n = assertNotNull(shadowOf(manager).allNotifications.singleOrNull(), "pas de notification")
        assertEquals(CallSystem.incomingChannel, n.channelId)
        assertEquals(android.app.Notification.CATEGORY_CALL, n.category)
        assertNotNull(n.fullScreenIntent)
        assertTrue(n.flags and android.app.Notification.FLAG_INSISTENT != 0)
        // Le canal sonne (la sonnerie du téléphone) : Talka ne sonne pas en plus.
        val channel = manager.getNotificationChannel(CallSystem.incomingChannel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNotNull(channel.sound)
        assertNull(inc.ringing)
        // Répondre : la notification d'appel s'en va.
        max.scene(AppModel.Phase.active)
        run(max) { inc.accept() }
        live(lea, max)
        assertTrue(shadowOf(manager).allNotifications.none { it.channelId == CallSystem.incomingChannel })
        // Le bouton Raccrocher de la notification « Appel en cours ».
        CallActions().onReceive(app, android.content.Intent(CallSystem.ACTION_HANGUP))
        until("raccroché") { lea.call?.phase == CallEngine.Phase.ended }
        assertEquals(CallEngine.Phase.ended, inc.phase)
    }

    @Test
    fun theSignalsAreThoseOfTheBrowserAndTheIPhone() {
        val lea = phone("Léa")
        val wires = vectors()["wires"] as JsonArray
        val texts = wires.map { (it as JsonPrimitive).content }

        // `ice` : le candidat, tel que le navigateur et l'iPhone l'écrivent.
        val ice = texts.first { it.contains("\"op\":\"ice\"") }
        val theirs = Wire.parse(ice)!!.body as Body.Call
        val cand = assertNotNull(Candidate.of(theirs.cand))
        assertEquals(Candidate("candidate:1 1 udp 1 10.0.0.1 9 typ host", "0", 0), cand)
        assertEquals(
            Json.parseToJsonElement(ice).jsonObject["cand"].toString(),
            cand.json().toJson().toString(),
        )

        // `join` d'un appel de groupe : le même que celui des vecteurs.
        val join = texts.first { it.contains("\"op\":\"join\"") }
        val expected = Wire.parse(join)!!.body as Body.Call
        val (a, b, c) = expected.members
        val mesh = Mesh.incoming(a, b, "sdp", expected.members)
        val e = lea.engine!!
        val sent = ArrayList<Body>()
        val env = testEnv(app, dir, relay) { null }({ FakeMedia("x") }, { _, body -> sent.add(body) })
        val m = AppModel(env)
        opened.add(m)
        val engine = CallEngine(e, m, b, expected.chat, mesh, video = false, incoming = true, id = expected.callId)
        engine.send(c, "join")
        assertEquals(expected, sent.single())
        val ours = Json.parseToJsonElement(Wire(sent.single()).json()).jsonObject
        for ((key, value) in Json.parseToJsonElement(join).jsonObject) {
            if (value is JsonNull) continue
            assertEquals(value, ours[key], key)
        }
    }

    @Test
    fun theIceServersOfTheRelay() {
        // Sans réponse : le STUN de Google.
        assertEquals(listOf(IceServer(listOf("stun:stun.l.google.com:19302"))), IceServer.parse(null))
        assertEquals(IceServer.fallback, IceServer.parse("""{"iceServers":[]}"""))
        assertEquals(IceServer.fallback, IceServer.parse("pas du JSON"))
        // Le relais local : `/api/ice`, comme l'iPhone le lit (une adresse ou plusieurs).
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var path = ""
        val answer = thread {
            server.accept().use { s ->
                path = s.getInputStream().bufferedReader().readLine().orEmpty()
                val body = ("""{"iceServers":[{"urls":"stun:stun.example:3478"},""" +
                    """{"urls":["turn:turn.example:3478?transport=udp","turns:turn.example:5349"],"username":"u","credential":"p"},{"urls":[]}]}""")
                    .toByteArray()
                val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                s.getOutputStream().apply {
                    write(head.toByteArray())
                    write(body)
                    flush()
                }
            }
        }
        try {
            val list = runBlocking { IceServer.fetch("http://127.0.0.1:${server.localPort}/") }
            answer.join()
            assertEquals("GET /api/ice HTTP/1.1", path)
            assertEquals(
                listOf(
                    IceServer(listOf("stun:stun.example:3478")),
                    IceServer(listOf("turn:turn.example:3478?transport=udp", "turns:turn.example:5349"), "u", "p"),
                ),
                list,
            )
        } finally {
            server.close()
        }
        // Un relais qui ne répond pas.
        assertEquals(IceServer.fallback, runBlocking { IceServer.fetch("http://127.0.0.1:9") })
    }

    @Test
    fun namesOfTheParticipants() {
        assertEquals("", CallEngine.names(emptyList()))
        assertEquals("Léa", CallEngine.names(listOf("Léa")))
        assertEquals("Léa et Max", CallEngine.names(listOf("Léa", "Max")))
        assertEquals("Léa, Max et Zoé", CallEngine.names(listOf("Léa", "Max", "Zoé")))
        assertEquals("Léa, Max et 3 autres", CallEngine.names(listOf("Léa", "Max", "Zoé", "Sam", "Noé")))
    }

    /** Les vecteurs du navigateur, que l'iPhone et TalkaKit vérifient aussi. */
    private fun vectors(): JsonObject {
        val file = listOf("../../ios/Tests/TalkaKitTests/vectors.json", "../ios/Tests/TalkaKitTests/vectors.json")
            .map(::File).first { it.exists() }
        return Json.parseToJsonElement(file.readText()).jsonObject
    }
}
