// Les appels dans les tests : un faux WebRTC (une fausse liaison, qui « se
// connecte » dès que ses deux descriptions sont posées et donne un candidat
// ICE après la sienne), et un faux réseau entre plusieurs téléphones (des
// `AppModel` dans le même processus) : chaque signal y passe en JSON, celui
// des enveloppes (`Wire`), comme entre un Android, un iPhone et un navigateur.

package talka.app.call

import android.os.Handler
import android.os.Looper
import talka.app.AppEnv
import talka.app.AppModel
import talka.kit.Body
import talka.kit.SocketOpener
import talka.kit.Wire
import java.io.File

/** Une fausse liaison WebRTC. */
class FakeLink(val owner: FakeMedia, val events: LinkEvents) : CallLink {
    var local: String? = null
    var remote: String? = null
    val added = ArrayList<Candidate>()
    var closed = false
    private var connectedSent = false
    private val main = Handler(Looper.getMainLooper())

    private fun post(f: () -> Unit) {
        main.post { if (!closed) f() }
    }

    private fun afterLocal() {
        val n = ++owner.candidates
        // Comme WebRTC : un candidat après la description locale.
        post { events.onCandidate(Candidate("candidate:$n 1 udp 2122260223 10.0.0.$n 5000$n typ host", "0", 0)) }
        check()
    }

    private fun check() {
        if (local == null || remote == null || connectedSent || owner.neverConnect) return
        connectedSent = true
        post {
            events.onState(LinkState.connected)
            if (owner.videoOn) events.onRemoteVideo("image-de-${owner.links.indexOf(this)}")
        }
    }

    override suspend fun offer(video: Boolean): String = "v=0 offre ${owner.name} $video".also {
        local = it
        afterLocal()
    }

    override suspend fun answer(): String? {
        if (remote == null) return null
        return "v=0 réponse ${owner.name}".also {
            local = it
            afterLocal()
        }
    }

    override suspend fun setRemote(offer: Boolean, sdp: String) {
        remote = sdp
        check()
    }

    override val hasRemote: Boolean get() = remote != null

    override fun add(c: Candidate) {
        added.add(c)
    }

    override fun close() {
        closed = true
    }

    /** La liaison tombe (le réseau). */
    fun fail() {
        events.onState(LinkState.failed)
    }
}

/** Un faux WebRTC : ce que l'appel lui a demandé. */
class FakeMedia(val name: String) : CallMedia {
    val links = ArrayList<FakeLink>()
    var candidates = 0
    var audio = false
    var videoOn = false
    var micOff = false
    var cameraOn = true
    var front = true
    var stopped = false

    /** Les liaisons ne se connectent jamais (un réseau qui ne passe pas). */
    var neverConnect = false

    override suspend fun iceServers(server: String): List<IceServer> = IceServer.fallback

    override fun startAudio(): Boolean {
        audio = true
        return true
    }

    override fun startVideo(front: Boolean): Any {
        videoOn = true
        this.front = front
        return "caméra de $name"
    }

    override fun link(servers: List<IceServer>, events: LinkEvents): CallLink = FakeLink(this, events).also { links.add(it) }

    override fun setMuted(muted: Boolean) {
        micOff = muted
    }

    override fun setCameraEnabled(on: Boolean) {
        cameraOn = on
    }

    override fun switchCamera(front: Boolean) {
        this.front = front
    }

    override fun stop() {
        stopped = true
    }
}

/** Un signal passé sur le faux réseau : de qui, à qui, son JSON (celui de l'enveloppe). */
data class Sent(val from: String, val to: String, val json: String) {
    val body: Body.Call get() = Wire.parse(json)!!.body as Body.Call
}

/** Le faux réseau : chaque téléphone reçoit les signaux qui lui sont adressés (plus tard, comme par le relais). */
class FakeNet {
    val phones = LinkedHashMap<String, AppModel>()
    val medias = HashMap<AppModel, FakeMedia>()
    val sent = ArrayList<Sent>()
    private val main = Handler(Looper.getMainLooper())

    fun phone(env: (callMedia: () -> CallMedia, callSignals: (String, Body) -> Unit) -> AppEnv, name: String): AppModel {
        val media = FakeMedia(name)
        lateinit var m: AppModel
        m = AppModel(env({ media }, { to, body -> deliver(m, to, body) }))
        medias[m] = media
        return m
    }

    fun register(m: AppModel) {
        phones[m.engine!!.me.code] = m
    }

    fun media(m: AppModel): FakeMedia = medias.getValue(m)

    private fun deliver(from: AppModel, to: String, body: Body) {
        val code = from.engine!!.me.code
        val json = Wire(body).json()
        sent.add(Sent(code, to, json))
        val target = phones[to] ?: return
        val parsed = Wire.parse(json)!!.body
        main.post { target.callSignal(code, parsed, true) }
    }

    /** Les signaux `op` de `from` à `to`. */
    fun ops(from: AppModel, to: AppModel): List<String> =
        sent.filter { it.from == from.engine!!.me.code && it.to == to.engine!!.me.code }.map { it.body.op }
}

/** Ce qu'il faut à `FakeNet.phone` pour créer un `AppEnv` de test. */
fun testEnv(
    context: android.content.Context,
    dir: File,
    sockets: SocketOpener,
    unlockCipher: (String) -> Any?,
): (() -> CallMedia, (String, Body) -> Unit) -> AppEnv = { media, signals ->
    AppEnv(
        context,
        dir = dir,
        server = "http://relais.test",
        sockets = sockets,
        unlockCipher = unlockCipher,
        deviceName = "Pixel de test",
        splashMs = 0,
        netWaitMs = 0,
        callMedia = media,
        callSignals = signals,
        fullScreenCalls = { true },
    )
}
