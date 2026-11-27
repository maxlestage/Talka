// WebRTC pour de vrai (`org.webrtc`, de `io.getstream:stream-webrtc-android`) :
// le micro, la caméra (Camera2), une `PeerConnection` par participant, et
// l'image à l'écran (`SurfaceViewRenderer`). Le même réglage que l'iPhone :
// Unified Plan, la collecte continue des candidats, les pistes « talka-audio »
// et « talka-video » dans le flux « talka ».
//
// Les rappels de WebRTC arrivent sur son propre fil : ils reviennent au fil
// principal avant d'atteindre `CallEngine`.

package talka.app.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CompletableDeferred
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcMedia(context: Context) : CallMedia {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val factory: PeerConnectionFactory by lazy { factory(this.context) }
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    @Volatile private var capturing: CaptureFormat? = null
    private var helper: SurfaceTextureHelper? = null
    private val cameras: CameraEnumerator by lazy {
        if (Camera2Enumerator.isSupported(this.context)) Camera2Enumerator(this.context) else Camera1Enumerator(true)
    }

    override fun startAudio(): Boolean =
        try {
            val source = factory.createAudioSource(MediaConstraints())
            audioSource = source
            audioTrack = factory.createAudioTrack("talka-audio", source)
            true
        } catch (e: Throwable) {
            false
        }

    override fun startVideo(front: Boolean): Any? {
        try {
            val name = camera(front) ?: return null
            val cap = cameras.createCapturer(name, null) ?: return null
            val h = SurfaceTextureHelper.create("talka-camera", egl.eglBaseContext)
            val source = factory.createVideoSource(false)
            cap.initialize(h, context, source.capturerObserver)
            // Le format de la caméra le plus proche de 1280 × 720, 30 images
            // par seconde au plus (l'iPhone aussi).
            val format = captureFormat(name)
            cap.startCapture(format.width, format.height, format.fps)
            capturing = format
            capturer = cap
            helper = h
            videoSource = source
            return factory.createVideoTrack("talka-video", source).also { videoTrack = it }
        } catch (e: Throwable) {
            return null
        }
    }

    /** Le format choisi pour cette caméra (`CaptureFormat.closest`), `framerate` en millièmes. */
    private fun captureFormat(name: String): CaptureFormat {
        val formats = try {
            cameras.getSupportedFormats(name).orEmpty().map { CaptureFormat(it.width, it.height, it.framerate.max / 1000) }
        } catch (e: Throwable) {
            emptyList()
        }
        return CaptureFormat.closest(formats) ?: CaptureFormat.preferred
    }

    /** La caméra avant (ou arrière), sinon la première. */
    private fun camera(front: Boolean): String? {
        val names = cameras.deviceNames
        return names.firstOrNull { if (front) cameras.isFrontFacing(it) else cameras.isBackFacing(it) } ?: names.firstOrNull()
    }

    override fun link(servers: List<IceServer>, events: LinkEvents): CallLink? {
        val ice = servers.map { s ->
            PeerConnection.IceServer.builder(s.urls)
                .setUsername(s.username ?: "")
                .setPassword(s.credential ?: "")
                .createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val link = WebRtcLink(events, main)
        val pc = try {
            factory.createPeerConnection(config, link.observer)
        } catch (e: Throwable) {
            null
        } ?: return null
        link.pc = pc
        audioTrack?.let { pc.addTrack(it, listOf("talka")) }
        videoTrack?.let { pc.addTrack(it, listOf("talka")) }
        return link
    }

    override fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
    }

    override fun setCameraEnabled(on: Boolean) {
        videoTrack?.setEnabled(on)
    }

    override fun switchCamera(front: Boolean) {
        val cap = capturer ?: return
        val name = camera(front) ?: return
        val format = captureFormat(name)
        try {
            // L'autre caméra, puis son propre format (l'iPhone relance la
            // capture avec celui de la caméra choisie).
            cap.switchCamera(
                object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                        if (format == capturing) return
                        capturing = format
                        try {
                            cap.changeCaptureFormat(format.width, format.height, format.fps)
                        } catch (e: Throwable) {
                        }
                    }

                    override fun onCameraSwitchError(errorDescription: String?) {}
                },
                name,
            )
        } catch (e: Throwable) {
        }
    }

    override fun stop() {
        try {
            capturer?.stopCapture()
        } catch (e: Throwable) {
        }
        for (f in listOf<() -> Unit>(
            { capturer?.dispose() }, { helper?.dispose() }, { videoTrack?.dispose() }, { videoSource?.dispose() },
            { audioTrack?.dispose() }, { audioSource?.dispose() },
        )) {
            try {
                f()
            } catch (e: Throwable) {
            }
        }
        capturer = null
        capturing = null
        helper = null
        videoTrack = null
        videoSource = null
        audioTrack = null
        audioSource = null
    }

    companion object {
        /** Le contexte OpenGL partagé : l'encodeur, le décodeur et les images à l'écran. */
        val egl: EglBase by lazy { EglBase.create() }
        private var shared: PeerConnectionFactory? = null

        /** Une seule fabrique pour toute l'application (le `static let factory` de l'iPhone). */
        @Synchronized
        fun factory(context: Context): PeerConnectionFactory {
            shared?.let { return it }
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            val adm = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            val f = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            // La fabrique garde le module.
            adm.release()
            shared = f
            return f
        }
    }
}

/** Une `PeerConnection`, ses rappels ramenés au fil principal. */
private class WebRtcLink(val events: LinkEvents, val main: Handler) : CallLink {
    lateinit var pc: PeerConnection

    @Volatile private var closed = false

    /** Ce qui attend une réponse de WebRTC : rendu (vide) si la liaison se ferme avant. */
    private val waiting = HashSet<CompletableDeferred<SessionDescription?>>()

    private fun post(f: () -> Unit) {
        main.post { if (!closed) f() }
    }

    val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) = post { events.onCandidate(Candidate(c.sdp, c.sdpMid, c.sdpMLineIndex)) }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver?.track() as? VideoTrack ?: return
            post { events.onRemoteVideo(track) }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            val s = when (state) {
                PeerConnection.PeerConnectionState.NEW -> LinkState.new
                PeerConnection.PeerConnectionState.CONNECTING -> LinkState.connecting
                PeerConnection.PeerConnectionState.CONNECTED -> LinkState.connected
                PeerConnection.PeerConnectionState.DISCONNECTED -> LinkState.disconnected
                PeerConnection.PeerConnectionState.FAILED -> LinkState.failed
                PeerConnection.PeerConnectionState.CLOSED -> LinkState.closed
            }
            post { events.onState(s) }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

        override fun onAddStream(stream: MediaStream) {}

        override fun onRemoveStream(stream: MediaStream) {}

        override fun onDataChannel(channel: DataChannel) {}

        override fun onRenegotiationNeeded() {}
    }

    /** Une description de WebRTC (offre, réponse, ou rien pour une pose), en `suspend`. */
    private suspend fun ask(start: (SdpObserver) -> Unit): SessionDescription? {
        if (closed) return null
        val wait = CompletableDeferred<SessionDescription?>()
        waiting.add(wait)
        start(
            object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription) {
                    wait.complete(d)
                }

                override fun onSetSuccess() {
                    wait.complete(null)
                }

                override fun onCreateFailure(error: String?) {
                    wait.complete(null)
                }

                override fun onSetFailure(error: String?) {
                    wait.complete(null)
                }
            },
        )
        return try {
            wait.await()
        } finally {
            waiting.remove(wait)
        }
    }

    private suspend fun create(make: (SdpObserver) -> Unit): String? {
        val d = ask(make) ?: return null
        if (closed) return null
        ask { pc.setLocalDescription(it, d) }
        return d.description
    }

    override suspend fun offer(video: Boolean): String? {
        val c = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (video) "true" else "false"))
        }
        return create { pc.createOffer(it, c) }
    }

    override suspend fun answer(): String? = create { pc.createAnswer(it, MediaConstraints()) }

    override suspend fun setRemote(offer: Boolean, sdp: String) {
        val d = SessionDescription(if (offer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER, sdp)
        ask { pc.setRemoteDescription(it, d) }
    }

    override val hasRemote: Boolean
        get() = !closed && pc.remoteDescription != null

    override fun add(c: Candidate) {
        if (closed) return
        pc.addIceCandidate(IceCandidate(c.sdpMid ?: "0", c.sdpMLineIndex, c.sdp))
    }

    override fun close() {
        if (closed) return
        closed = true
        for (w in waiting.toList()) w.complete(null)
        try {
            pc.dispose()
        } catch (e: Throwable) {
        }
    }
}

/**
 * Une image de l'appel (la nôtre, ou celle d'un participant), remplissant
 * son cadre (`scaleAspectFill`) ; `mirror` : la caméra avant, comme un
 * miroir ; `overlay` : par-dessus une autre image (la nôtre, en petit).
 */
@Composable
fun VideoView(track: Any, mirror: Boolean, modifier: Modifier = Modifier, overlay: Boolean = false) {
    val t = track as? VideoTrack ?: return
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(WebRtcMedia.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (overlay) setZOrderMediaOverlay(true)
            }
        },
        update = { v ->
            v.setMirror(mirror)
            val shown = v.tag as? VideoTrack
            if (shown !== t) {
                shown?.let { detach(it, v) }
                try {
                    t.addSink(v)
                    v.tag = t
                } catch (e: IllegalStateException) {
                }
            }
        },
        onRelease = { v ->
            (v.tag as? VideoTrack)?.let { detach(it, v) }
            v.release()
        },
        modifier = modifier,
    )
}

/** Une piste déjà rendue (l'appel est fini) ne se détache plus : rien à faire. */
private fun detach(track: VideoTrack, view: SurfaceViewRenderer) {
    try {
        track.removeSink(view)
    } catch (e: IllegalStateException) {
    }
}
