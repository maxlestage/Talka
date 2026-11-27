// Les appels audio et vidéo, de pair à pair (WebRTC), à deux ou à plusieurs
// (huit au plus) : le portage de `CallEngine.swift`, À FAIRE.
//
// Pour l'instant, ce qu'en attend `AppModel` (les mêmes noms que l'iPhone),
// sans WebRTC : un appel sortant s'arrête aussitôt, un appel entrant sonne
// à l'écran (`CallScreen`) et se refuse.

package talka.app.call

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import talka.app.AppModel
import talka.kit.Body
import talka.kit.Engine
import talka.kit.Mesh
import talka.kit.nowMs

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

    val at = nowMs()
    var phase by mutableStateOf(if (incoming) Phase.ringing else Phase.calling)
    var status by mutableStateOf("")

    /** Appeler : les offres de `todo`. */
    suspend fun startOutgoing(todo: List<Mesh.Do>) {
        model.show("Les appels arrivent bientôt sur Android.")
        end("")
    }

    /** Un signal de l'appel (réponse, candidat, fin…). */
    suspend fun signal(from: String, body: Body, live: Boolean) {
        val b = body as? Body.Call ?: return
        if (b.op == "end" || b.op == "reject" || b.op == "busy") end("")
    }

    /** Un appel entrant sonne. */
    fun ring() {
        status = if (video) "Appel vidéo entrant" else "Appel entrant"
    }

    /** Décrocher. */
    suspend fun accept() {
        hangup()
    }

    /** Raccrocher (ou refuser). */
    fun hangup() {
        if (incoming && phase == Phase.ringing) {
            engine.sendCallSignal(first, Body.Call(op = "reject", callId = id, video = video, sdp = "", cand = null))
        }
        end("")
    }

    /** Pris ou refusé sur un autre de nos appareils. */
    fun takenElsewhere() {
        end("", elsewhere = true)
    }

    fun end(reason: String, missed: Boolean = false, elsewhere: Boolean = false) {
        if (phase == Phase.ended) return
        phase = Phase.ended
        if (model.call === this) model.call = null
    }
}
