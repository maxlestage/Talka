// Ce que `CallEngine` demande à WebRTC, derrière une petite interface : le
// micro, la caméra, et une liaison par participant (offre, réponse,
// candidats ICE). `WebRtcMedia` la fait avec `org.webrtc` ; les tests, avec
// une fausse liaison (WebRTC natif ne tourne pas sous Robolectric).
//
// Ce qui passe sur le fil est ici aussi : un candidat ICE en JSON (celui du
// navigateur et de l'iPhone), et les serveurs STUN/TURN du relais
// (`/api/ice`, comme `CallEngine.iceServers` de l'iPhone).

package talka.app.call

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import talka.kit.JSONValue
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/** Un serveur STUN ou TURN (`RTCIceServer`). */
data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null) {
    companion object {
        /** Sans réponse du relais : le STUN public de Google, comme le navigateur et l'iPhone. */
        val fallback = listOf(IceServer(listOf("stun:stun.l.google.com:19302")))

        /**
         * La réponse de `/api/ice` : `{"iceServers":[{"urls":…,"username","credential"}]}`
         * (`urls` : une adresse ou une liste). Vide ou illisible : `fallback`.
         */
        fun parse(text: String?): List<IceServer> {
            val servers = try {
                (Json.parseToJsonElement(text ?: return fallback) as? JsonObject)?.get("iceServers") as? JsonArray
            } catch (e: Exception) {
                null
            }
            if (servers.isNullOrEmpty()) return fallback
            return servers.mapNotNull { s ->
                val o = s as? JsonObject ?: return@mapNotNull null
                val urls = when (val u = o["urls"]) {
                    is JsonArray -> u.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    is JsonPrimitive -> listOfNotNull(u.takeIf { it.isString }?.content)
                    else -> emptyList()
                }
                if (urls.isEmpty()) return@mapNotNull null
                IceServer(
                    urls,
                    (o["username"] as? JsonPrimitive)?.contentOrNull,
                    (o["credential"] as? JsonPrimitive)?.contentOrNull,
                )
            }
        }

        /** Les serveurs STUN/TURN du serveur Talka (`<serveur>/api/ice`), hors du fil principal. */
        suspend fun fetch(server: String): List<IceServer> =
            withContext(Dispatchers.IO) {
                val text = try {
                    val c = URL(server.trimEnd('/') + "/api/ice").openConnection() as HttpURLConnection
                    c.connectTimeout = 5_000
                    c.readTimeout = 5_000
                    try {
                        if (c.responseCode == 200) c.inputStream.use { it.readBytes().decodeToString() } else null
                    } finally {
                        c.disconnect()
                    }
                } catch (e: Exception) {
                    null
                }
                parse(text)
            }
    }
}

/** Un candidat ICE (`RTCIceCandidate`). */
data class Candidate(val sdp: String, val sdpMid: String?, val sdpMLineIndex: Int) {
    /**
     * Le `cand` d'un signal `ice`, comme l'iPhone l'écrit et comme le
     * navigateur le lit : `{"candidate","sdpMid","sdpMLineIndex"}`.
     */
    fun json(): JSONValue =
        JSONValue.Object(
            mapOf(
                "candidate" to JSONValue.Str(sdp),
                "sdpMid" to JSONValue.Str(sdpMid ?: "0"),
                "sdpMLineIndex" to JSONValue.Number(sdpMLineIndex.toDouble()),
            ),
        )

    companion object {
        /** Le `cand` d'un signal `ice` (du navigateur, de l'iPhone ou d'Android). */
        fun of(cand: JSONValue?): Candidate? {
            val text = cand?.get("candidate")?.string ?: return null
            return Candidate(text, cand["sdpMid"]?.string, cand["sdpMLineIndex"]?.number?.toInt() ?: 0)
        }
    }
}

/** Où en est une liaison (`RTCPeerConnectionState`). */
enum class LinkState { new, connecting, connected, disconnected, failed, closed }

/** Ce qu'une liaison fait savoir, sur le fil principal. */
interface LinkEvents {
    /** Un candidat ICE à envoyer à l'autre. */
    fun onCandidate(c: Candidate)

    /** L'image de l'autre arrive (une `VideoTrack` de WebRTC). */
    fun onRemoteVideo(track: Any)

    fun onState(state: LinkState)
}

/** La liaison WebRTC avec un participant (`RTCPeerConnection`). */
interface CallLink {
    /** Crée l'offre et la pose comme description locale : son SDP (null : échec). */
    suspend fun offer(video: Boolean): String?

    /** Crée la réponse (à l'offre déjà posée) et la pose : son SDP (null : échec). */
    suspend fun answer(): String?

    /** Pose la description de l'autre : son offre, ou sa réponse. */
    suspend fun setRemote(offer: Boolean, sdp: String)

    /** La description de l'autre est-elle posée ? (Sinon, ses candidats attendent.) */
    val hasRemote: Boolean

    fun add(c: Candidate)

    fun close()
}

/** Le micro, la caméra et les liaisons d'un appel (WebRTC ; un faux dans les tests). */
interface CallMedia {
    /** Les serveurs STUN/TURN du relais (`/api/ice`). */
    suspend fun iceServers(server: String): List<IceServer> = IceServer.fetch(server)

    /** Le micro (sa permission est donnée). */
    fun startAudio(): Boolean

    /** La caméra (sa permission est donnée) : notre image, à montrer, ou null sans caméra. */
    fun startVideo(front: Boolean): Any?

    /** Une liaison, avec le même micro et la même caméra que les autres. */
    fun link(servers: List<IceServer>, events: LinkEvents): CallLink?

    fun setMuted(muted: Boolean)

    fun setCameraEnabled(on: Boolean)

    fun switchCamera(front: Boolean)

    /** L'appel est fini : le micro et la caméra s'arrêtent. */
    fun stop()
}

/** Un format de la caméra : sa taille, et ses images par seconde au plus. */
data class CaptureFormat(val width: Int, val height: Int, val maxFps: Int) {
    /** Les images par seconde demandées : 30 au plus (l'iPhone aussi), 30 si la caméra ne le dit pas. */
    val fps: Int get() = if (maxFps <= 0) 30 else minOf(30, maxFps)

    companion object {
        /** Sans liste de la caméra : ce que demande l'iPhone. */
        val preferred = CaptureFormat(1280, 720, 30)

        /**
         * Le format le plus proche de 1280 × 720 (la somme des écarts de
         * largeur et de hauteur), le premier en cas d'égalité : le choix de
         * `CallEngine.startCapture` sur l'iPhone.
         */
        fun closest(formats: List<CaptureFormat>): CaptureFormat? =
            formats.minByOrNull { abs(it.width - preferred.width) + abs(it.height - preferred.height) }
    }
}
