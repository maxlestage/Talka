// La montre (Wear OS) et les widgets, côté téléphone : `WatchBridge.swift`
// de l'iPhone.
//
// Les widgets : le nombre de non-lus et les discussions récentes, partagés
// par `WidgetShare` (un fichier privé de l'application), mis à jour dès
// qu'ils changent pendant que Talka est ouvert.
//
// La montre : la couche de données de Wear OS (voir `WearProtocol`),
// derrière `WatchPort` ; `WearDataLayer` la branche, les tests la simulent.
//
// Le téléphone reste l'appareil Talka (les clés, le relais) : il envoie à la
// montre le résumé des discussions récentes dès qu'il change, et exécute ce
// que l'on fait sur la montre (répondre, réagir, marquer comme lu, envoyer
// un vocal). Voir talkakit/WatchLink.kt.

package talka.app.watch

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import talka.app.AppModel
import talka.kit.Engine
import talka.kit.WatchAction
import talka.kit.WatchLink
import talka.kit.WidgetData
import talka.kit.WidgetShare
import talka.kit.fittedWatchData
import talka.kit.perform
import talka.kit.sendWatchVoice
import talka.kit.watchSnapshot
import talka.link.WearProtocol

/**
 * La liaison avec la montre (la `WCSession` de l'iPhone) : la couche de
 * données de Wear OS (`WearDataLayer`), ou un faux dans les tests.
 */
interface WatchPort {
    /** Branche la liaison ; `changed` : la montre (appairée, application, portée) a changé, ou la liaison est prête. */
    fun activate(changed: () -> Unit)

    /** Une montre appairée a l'application Talka (`isPaired && isWatchAppInstalled`). */
    val watchReady: Boolean

    /** La montre répond en ce moment (`isReachable`). */
    val reachable: Boolean

    /** Le dernier résumé, que la montre retrouve même plus tard (`updateApplicationContext`). */
    fun updateContext(data: ByteArray)

    /** Le résumé tout de suite, aux montres joignables (`sendMessage`). */
    fun sendMessage(data: ByteArray)

    /** La réponse à une action : le résumé, à la montre qui l'a envoyée (le `replyHandler`). */
    fun reply(node: String, data: ByteArray)
}

class WatchBridge(
    private val model: AppModel,
    private val port: WatchPort?,
    /** Recharge les widgets de l'écran d'accueil (`WidgetCenter.reloadAllTimelines`). */
    private val reloadWidgets: () -> Unit,
) {
    private var lastSent: ByteArray? = null
    private var timer: Job? = null

    /** Ce qui est parti vers la montre en dernier (pour les tests). */
    val sent: ByteArray? get() = lastSent

    fun start() {
        if (timer != null) return
        port?.activate { push(force = true) }
        // Le résumé de la montre n'est recalculé que si elle a l'application ;
        // widgets et montre ne sont mis à jour que s'ils ont changé.
        timer = model.scope.launch {
            while (true) {
                delay(2000)
                push()
            }
        }
    }

    fun stop() {
        timer?.cancel()
        timer = null
    }

    val watchReady: Boolean get() = port?.watchReady ?: false

    /** Les widgets : écrits seulement s'ils ont changé. */
    fun updateWidgets() {
        val e = model.engine
        if (e == null) {
            if (WidgetShare.read() != null) {
                WidgetShare.clear()
                reloadWidgets()
            }
            return
        }
        val data = WidgetData.of(e.watchSnapshot(chats = 12, messages = 0), hidden = model.hasPin)
        if (WidgetShare.write(data)) reloadWidgets()
    }

    /** Tout est effacé : les widgets aussi. */
    fun clearWidgets() {
        WidgetShare.clear()
        reloadWidgets()
    }

    /**
     * Le résumé pour la montre, encodé, réduit s'il le faut pour passer par
     * la couche de données. Les mêmes octets tant qu'il n'a pas changé.
     */
    fun snapshotData(e: Engine): ByteArray? {
        val hidden = model.hasPin
        return e.fittedWatchData { it.copy(hidden = hidden) }
    }

    /** Le moteur, démarré s'il ne l'est pas encore (téléphone réveillé en arrière-plan par la montre). */
    private fun engine(): Engine? {
        if (model.engine == null && Engine.hasAccount(model.store)) model.start()
        return model.engine
    }

    fun push(force: Boolean = false) {
        updateWidgets()
        val port = port ?: return
        if (!port.watchReady) return
        val e = model.engine ?: return
        val data = snapshotData(e) ?: return
        if (!force && data.contentEquals(lastSent)) return
        lastSent = data
        // Le dernier résumé, que la montre retrouve même en se réveillant plus tard…
        port.updateContext(data)
        // … et tout de suite, si elle est à l'écran.
        if (port.reachable) port.sendMessage(data)
    }

    /** Une action venue de la montre ; renvoie le résumé à jour. */
    fun handle(data: ByteArray?): ByteArray? {
        val e = engine() ?: return null
        val action = WatchLink.decode(WatchAction, data)
        if (action != null) {
            e.perform(action)
            // Réveillé en arrière-plan par la montre : le message part tout de suite.
            if (action.kind == WatchAction.Kind.send) e.relay.wake()
        }
        return answer(e)
    }

    /** Le message `/talka/action` d'une montre : exécuté, puis le résumé lui revient. */
    fun message(data: ByteArray?, node: String) {
        val snapshot = handle(data) ?: return
        port?.reply(node, snapshot)
    }

    /** Les actions faites quand le téléphone n'était pas joignable, livrées dans l'ordre (`transferUserInfo`). */
    fun queued(items: List<Pair<String, ByteArray>>) {
        for ((_, data) in WearProtocol.inOrder(items) { it.first }) handle(data)
    }

    /** Un message vocal enregistré sur la montre. */
    fun voice(audio: ByteArray, chat: String, seconds: Double) {
        val e = engine() ?: return
        if (e.sendWatchVoice(chat, audio, seconds)) e.relay.wake()
        answer(e)
        val port = port ?: return
        val last = lastSent ?: return
        if (port.watchReady && port.reachable) port.sendMessage(last)
    }

    /** Un vocal venu de la montre, avec ses métadonnées (`WatchLink.voiceMetadata`). */
    fun voice(metadata: Map<String, Any?>, audio: ByteArray) {
        val v = WatchLink.voice(metadata) ?: return
        voice(audio, v.chat, v.seconds)
    }

    private fun answer(e: Engine): ByteArray? {
        updateWidgets()
        val snapshot = snapshotData(e)
        lastSent = snapshot
        val port = port
        if (snapshot != null && port != null && port.watchReady) port.updateContext(snapshot)
        return snapshot
    }
}
