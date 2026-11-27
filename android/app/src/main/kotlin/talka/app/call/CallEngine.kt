// Les appels audio et vidéo, de pair à pair (WebRTC), à deux ou à plusieurs
// (huit au plus, chacun relié à chacun). Le même protocole que le navigateur
// (`client/src/calls.rs`) et l'iPhone (`CallEngine.swift`, porté ici), par les
// enveloppes chiffrées ; `Mesh` (TalkaKit) décide qui appelle qui :
//
// - l'appelant envoie `offer` (avec son SDP, et la liste des participants à
//   plusieurs), l'appelé répond `answer`, `reject` ou `busy` ;
// - à plusieurs, qui répond dit `join` aux autres, et deux personnes déjà
//   dans l'appel se relient par `link` ;
// - les candidats ICE passent par `ice` ;
// - `end` raccroche ; sans réponse en 45 secondes, l'invitation s'arrête.
//
// Le son et l'image vont directement d'un appareil à l'autre (DTLS-SRTP) :
// le serveur n'en voit rien.
//
// WebRTC est derrière `CallMedia` (le micro, la caméra, les liaisons) ;
// Android (le son, la sonnerie, les notifications, le service au premier
// plan) derrière `CallSystem`.

package talka.app.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import talka.app.AppModel
import talka.kit.Body
import talka.kit.CallEntry
import talka.kit.Engine
import talka.kit.JSONValue
import talka.kit.Mesh
import talka.kit.nowMs
import kotlin.math.roundToLong

class CallEngine(
    val engine: Engine,
    val model: AppModel,
    /** Qui nous a appelés, ou le premier appelé. */
    val first: String,
    /** Le groupe d'où l'appel est lancé, s'il y en a un. */
    val chat: String,
    var mesh: Mesh,
    val video: Boolean,
    val incoming: Boolean,
    val id: String,
) {
    enum class Phase { ringing, calling, connecting, live, ended }

    /** La liaison avec un participant ; ses candidats ICE arrivés avant sa description. */
    class Peer(val link: CallLink, val pending: MutableList<Candidate>)

    val at = nowMs()

    /** WebRTC : celui de l'application, ou un faux (les tests). */
    val media: CallMedia by lazy { model.env.callMedia?.invoke() ?: WebRtcMedia(model.env.context) }
    private val system = CallSystem(model.env.context)
    internal val peers = HashMap<String, Peer>()

    /** Des candidats arrivés avant la liaison (un appel qui sonne). */
    private val early = HashMap<String, MutableList<Candidate>>()

    /** Tous ceux qui ont été de l'appel (pour le journal). */
    private val ever = HashSet<String>()
    private var iceServers: List<IceServer>? = null
    private var timeout: Job? = null

    /** La notification d'appel sonne (Talka n'a pas à sonner lui-même). */
    private var alerted = false

    var phase by mutableStateOf(if (incoming) Phase.ringing else Phase.calling)
    var status by mutableStateOf(
        if (incoming) {
            if (mesh.isGroup) {
                if (video) "Appel vidéo de groupe entrant…" else "Appel de groupe entrant…"
            } else {
                if (video) "Appel vidéo entrant…" else "Appel audio entrant…"
            }
        } else {
            "Appel…"
        },
    )

    /** Le moment de la liaison (`SystemClock.elapsedRealtime`), pour la durée. */
    var connectedAt by mutableStateOf<Long?>(null)

    /** Notre image (une `VideoTrack` de WebRTC), à montrer en petit. */
    var localVideo by mutableStateOf<Any?>(null)

    /** L'image de chacun, à mesure qu'elle arrive. */
    val remoteVideos = mutableStateMapOf<String, Any>()

    /** Les autres participants (invités ou reliés), pour l'écran. */
    var participants by mutableStateOf(mesh.others)

    /** Ceux avec qui la liaison est faite. */
    var connected by mutableStateOf(emptySet<String>())
    var muted by mutableStateOf(false)
    var speaker by mutableStateOf(video)
    var frontCamera by mutableStateOf(true)

    /** Notre caméra est-elle allumée (`localVideo.isEnabled`) ? */
    var cameraOn by mutableStateOf(true)

    val camera: Boolean get() = localVideo != null
    val isGroup: Boolean get() = participants.size > 1

    /** Le titre de l'appel : l'autre, le groupe, ou les participants. */
    val title: String
        get() {
            if (participants.size <= 1) return engine.displayName(participants.firstOrNull() ?: first)
            engine.chat(chat)?.let { return engine.title(it) }
            return names(participants.map { engine.displayName(it) })
        }

    /** Qui en est, sous le nom du groupe (rien s'il redirait le titre). */
    val people: String
        get() {
            if (!isGroup) return ""
            val list = names(participants.map { engine.displayName(it) })
            return if (list == title) "" else list
        }

    // ----- Les signaux -----

    /**
     * Un signal à `peer`. Les invitations, liaisons et `join` d'un appel à
     * plusieurs portent la liste de ses participants ; un appel à deux part
     * exactement comme avant.
     */
    fun send(peer: String, op: String, sdp: String = "", cand: JSONValue? = null) {
        val rich = mesh.isGroup && op in listOf("offer", "link", "join")
        val body = Body.Call(
            op = op, callId = id, video = if (op == "offer" || op == "link") video else false, sdp = sdp, cand = cand,
            members = if (rich) mesh.members else emptyList(), chat = if (rich) chat else "",
        )
        val wire = model.env.callSignals
        if (wire != null) wire(peer, body) else engine.sendCallSignal(peer, body)
    }

    /** Un signal de cet appel, de l'un des participants. */
    suspend fun signal(from: String, body: Body, live: Boolean) {
        val b = body as? Body.Call ?: return
        if (phase == Phase.ended) return
        when (b.op) {
            "offer", "link" -> {
                if (!live) return
                val todo = mesh.onOffer(from, b.sdp, b.members, ring = b.op == "offer")
                run(todo, bye = "end", reason = "Appel terminé")
            }
            "join" -> {
                if (!live) return
                run(mesh.onJoin(from, b.members), bye = "end", reason = "Appel terminé")
            }
            "answer" -> {
                if (!mesh.onAnswer(from)) return
                val p = peers[from] ?: return
                if (connectedAt == null) {
                    status = "Connexion…"
                    phase = Phase.connecting
                }
                p.link.setRemote(offer = false, sdp = b.sdp)
                flush(p)
                refresh()
            }
            "ice" -> {
                val c = Candidate.of(b.cand) ?: return
                val p = peers[from]
                if (p != null) {
                    if (p.link.hasRemote) p.link.add(c) else p.pending.add(c)
                } else {
                    early.getOrPut(from) { ArrayList() }.add(c)
                }
            }
            "reject", "busy", "end" -> {
                val group = mesh.isGroup
                val missed = incoming && connectedAt == null
                val reason = when {
                    group -> "Appel terminé"
                    b.op == "reject" -> "Appel refusé"
                    b.op == "busy" -> "Occupé"
                    missed -> "Appel manqué"
                    else -> "Appel terminé"
                }
                val todo = mesh.onGone(from)
                val last = Mesh.Do.End in todo
                run(todo, bye = "end", reason = reason)
                if (group && !last) {
                    val name = engine.displayName(from)
                    model.show(
                        when (b.op) {
                            "end" -> "$name a quitté l'appel."
                            "busy" -> "$name est déjà en ligne."
                            else -> "$name ne vient pas."
                        },
                    )
                }
            }
            else -> {}
        }
    }

    private fun flush(p: Peer) {
        val list = p.pending.toList()
        p.pending.clear()
        for (c in list) p.link.add(c)
    }

    /**
     * Fait ce que `Mesh` a décidé. `bye` : ce que dit un au revoir (`end`,
     * ou `reject` pour un appel qui sonnait) ; `reason` : ce qu'affiche la fin.
     */
    suspend fun run(todo: List<Mesh.Do>, bye: String, reason: String) {
        for (d in todo) {
            when (d) {
                is Mesh.Do.Offer -> offer(d.peer, d.ring)
                is Mesh.Do.Answer -> answer(d.peer, d.sdp)
                is Mesh.Do.Join -> send(d.peer, "join")
                is Mesh.Do.Hangup -> {
                    send(d.peer, bye)
                    close(d.peer)
                }
                is Mesh.Do.Close -> close(d.peer)
                Mesh.Do.End -> end(reason)
            }
        }
        refresh()
    }

    /** Une liaison qui n'a pas pu se faire, ou qui a échoué. */
    private fun lost(peer: String, reason: String) {
        for (d in mesh.onGone(peer)) {
            when (d) {
                is Mesh.Do.Close -> close(d.peer)
                Mesh.Do.End -> end(reason)
                else -> {}
            }
        }
        refresh()
    }

    private fun refresh() {
        participants = mesh.others
        connected = connected.filter { peers[it] != null }.toSet()
    }

    // ----- Appeler, répondre, raccrocher -----

    suspend fun startOutgoing(todo: List<Mesh.Do>) {
        CallSystem.current = this
        // Personne n'a encore été appelé.
        if (!media()) return end("Micro ou caméra refusés.")
        system.startService(title, video)
        run(todo, bye = "end", reason = "Appel impossible")
        if (phase == Phase.ended) return
        playRing(outgoing = true)
        waitForAnswers()
    }

    /**
     * Sans réponse en 45 secondes, les invitations en attente s'arrêtent (et
     * l'appel avec elles, s'il n'y a personne d'autre).
     */
    private fun waitForAnswers() {
        timeout?.cancel()
        timeout = model.scope.launch {
            delay(answerMs)
            if (phase != Phase.ended) run(mesh.noAnswer(), bye = "end", reason = "Pas de réponse")
        }
    }

    /** Un appel entrant : sonner 45 secondes au plus. */
    fun ring() {
        CallSystem.current = this
        playRing(outgoing = false)
        timeout = model.scope.launch {
            delay(answerMs)
            if (phase == Phase.ringing) end("Appel manqué", missed = true)
        }
    }

    /**
     * Talka n'est pas à l'écran : la notification d'appel, en plein écran
     * même verrouillé. Elle sonne elle-même (son canal) : Talka cesse alors
     * de sonner.
     */
    fun alert(text: String) {
        if (phase != Phase.ringing) return
        if (system.incoming(engine.displayName(first), text, video)) {
            alerted = true
            system.stopRing()
        }
    }

    suspend fun accept() {
        if (phase != Phase.ringing) return
        timeout?.cancel()
        stopRing()
        // Nos autres appareils cessent de sonner (v4).
        engine.callTaken(id)
        phase = Phase.connecting
        status = "Connexion…"
        if (!media()) return run(mesh.hangup(), bye = "reject", reason = "Micro ou caméra refusés.")
        system.startService(title, video)
        run(mesh.accept(), bye = "end", reason = "Appel terminé")
    }

    fun hangup() {
        if (phase == Phase.ended) return
        val ringing = !mesh.joined
        // Refusé ici : nos autres appareils cessent de sonner (v4).
        if (ringing) engine.callTaken(id)
        val todo = mesh.hangup()
        model.scope.launch { run(todo, bye = if (ringing) "reject" else "end", reason = "Appel terminé") }
    }

    /** Faire sonner d'autres contacts pendant l'appel. */
    suspend fun add(codes: List<String>) {
        var any = false
        for (code in codes) {
            if (phase == Phase.ended) break
            val r = mesh.add(code)
            val todo = r.getOrNull()
            if (todo != null) {
                any = true
                run(todo, bye = "end", reason = "Appel terminé")
            } else if (r.exceptionOrNull() == Mesh.AddError.Full) {
                model.show("Un appel réunit huit personnes au plus.")
                return
            }
        }
        if (any) waitForAnswers()
    }

    /**
     * Pris ou refusé sur un autre de nos appareils (v4) : on cesse de
     * sonner, sans rien dire à l'appelant.
     */
    fun takenElsewhere() {
        end("Pris sur un autre appareil", elsewhere = true)
    }

    fun end(reason: String, missed: Boolean = false, elsewhere: Boolean = false) {
        if (phase == Phase.ended) return
        val start = connectedAt
        phase = Phase.ended
        status = reason
        timeout?.cancel()
        stopRing()
        if (start != null) system.endTone()
        localVideo = null
        remoteVideos.clear()
        for (p in peers.values) p.link.close()
        peers.clear()
        // Le micro et la caméra ne s'allument qu'avec la permission : sans eux, rien à éteindre.
        if (mediaStarted) media.stop()
        system.audioStop()
        system.cancelIncoming()
        system.stopService()
        if (CallSystem.current === this) CallSystem.current = null
        val dur = start?.let { ((SystemClock.elapsedRealtime() - it) / 1000.0).roundToLong().toDouble() } ?: 0.0
        val lostCall = !elsewhere && (missed || (incoming && dur == 0.0))
        val everyone = (ever + mesh.others + first).toSortedSet()
        engine.logCall(
            CallEntry(
                id = id, peer = first, video = video, incoming = incoming, at = at, dur = dur, missed = lostCall,
                members = if (everyone.size > 1) everyone.toList() else emptyList(), chat = chat,
            ),
        )
        model.scope.launch {
            delay(1400)
            if (model.call === this@CallEngine) model.call = null
        }
    }

    // ----- WebRTC -----

    private var mediaStarted = false

    /** La permission du système (micro, caméra), demandée par l'activité si elle manque. */
    private suspend fun permit(permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(model.env.context, permission) == PackageManager.PERMISSION_GRANTED) return true
        return model.askPermission?.invoke(permission) ?: false
    }

    /** Le micro (et la caméra pour un appel vidéo), avec la permission. */
    private suspend fun media(): Boolean {
        if (!permit(Manifest.permission.RECORD_AUDIO)) return false
        if (phase == Phase.ended) return false
        system.audioStart(speaker)
        mediaStarted = true
        if (!media.startAudio()) return false
        if (video) {
            if (!permit(Manifest.permission.CAMERA)) return false
            if (phase == Phase.ended) return false
            localVideo = media.startVideo(frontCamera)
        }
        return true
    }

    /** La liaison WebRTC avec `peer` (le même micro et la même caméra pour chacun). */
    private suspend fun createPeer(peer: String): Peer? {
        val servers = iceServers ?: media.iceServers(engine.relay.server).also { iceServers = it }
        if (phase == Phase.ended) return null
        var link: CallLink? = null
        // Les rappels d'une liaison remplacée ou fermée ne comptent plus.
        fun current() = link != null && peers[peer]?.link === link
        val events = object : LinkEvents {
            override fun onCandidate(c: Candidate) {
                if (current()) send(peer, "ice", cand = c.json())
            }

            override fun onRemoteVideo(track: Any) {
                if (current()) remoteVideos[peer] = track
            }

            override fun onState(state: LinkState) {
                if (current()) stateChanged(peer, state)
            }
        }
        val made = media.link(servers, events) ?: return null
        link = made
        peers[peer]?.link?.close()
        val p = Peer(made, early.remove(peer) ?: ArrayList())
        peers[peer] = p
        ever.add(peer)
        return p
    }

    private suspend fun offer(peer: String, ring: Boolean) {
        val p = createPeer(peer) ?: return lost(peer, "Appel impossible")
        val sdp = p.link.offer(video) ?: return lost(peer, "Appel impossible")
        if (peers[peer] !== p) return
        send(peer, if (ring) "offer" else "link", sdp = sdp)
    }

    private suspend fun answer(peer: String, sdp: String) {
        val p = createPeer(peer) ?: return lost(peer, "Appel impossible")
        p.link.setRemote(offer = true, sdp = sdp)
        flush(p)
        val answer = p.link.answer() ?: return lost(peer, "Appel impossible")
        if (peers[peer] !== p) return
        send(peer, "answer", sdp = answer)
    }

    private fun close(peer: String) {
        peers.remove(peer)?.link?.close()
        early.remove(peer)
        remoteVideos.remove(peer)
        connected = connected - peer
    }

    private fun stateChanged(peer: String, state: LinkState) {
        if (phase == Phase.ended || peers[peer] == null) return
        when (state) {
            LinkState.connected -> {
                connected = connected + peer
                if (connectedAt == null) {
                    connectedAt = SystemClock.elapsedRealtime()
                    stopRing()
                }
                phase = Phase.live
            }
            LinkState.failed -> lost(peer, "La connexion a échoué")
            LinkState.disconnected -> {
                connected = connected - peer
                if (!mesh.isGroup) status = "Reconnexion…"
            }
            else -> {}
        }
    }

    // ----- Micro, caméra, haut-parleur -----

    fun toggleMute() {
        muted = !muted
        media.setMuted(muted)
    }

    fun toggleSpeaker() {
        speaker = !speaker
        system.setSpeaker(speaker)
    }

    /** Couper ou rallumer la caméra (appel vidéo). */
    fun toggleCamera() {
        if (localVideo == null) return
        cameraOn = !cameraOn
        media.setCameraEnabled(cameraOn)
    }

    fun switchCamera() {
        frontCamera = !frontCamera
        media.switchCamera(frontCamera)
    }

    // ----- La sonnerie -----

    private fun playRing(outgoing: Boolean) {
        system.ring(outgoing)
    }

    private fun stopRing() {
        system.stopRing()
        if (alerted) {
            alerted = false
            system.cancelIncoming()
        }
    }

    /** Ce qui sonne : `true` l'appel sortant, `false` l'appel entrant, null rien (pour les tests). */
    val ringing: Boolean? get() = system.ringing

    companion object {
        /** Une invitation sonne 45 secondes au plus. */
        const val answerMs = 45_000L

        /** « Léa, Max et 3 autres ». */
        fun names(list: List<String>): String =
            when (list.size) {
                0 -> ""
                1 -> list[0]
                2, 3 -> list.dropLast(1).joinToString(", ") + " et " + list.last()
                else -> "${list[0]}, ${list[1]} et ${list.size - 2} autres"
            }
    }
}
