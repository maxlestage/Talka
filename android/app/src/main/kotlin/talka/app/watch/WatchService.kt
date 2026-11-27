// Ce que la montre envoie au téléphone, même quand Talka est fermé : Wear
// OS réveille l'application et passe par ce service (les méthodes
// `session(_:didReceiveMessage:…)`, `didReceive file`, `didReceiveUserInfo`
// du `WCSessionDelegate` de l'iPhone). Le travail lui-même est fait par
// `WatchBridge`, sur le fil principal (celui du moteur).

package talka.app.watch

import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import talka.app.TalkaApp
import talka.kit.WatchLink
import talka.link.WearProtocol
import java.util.concurrent.TimeUnit

class WatchService : WearableListenerService() {
    private val bridge: WatchBridge get() = (application as TalkaApp).model.watch

    /** Sur le fil principal, en l'attendant (ce service répond sur un fil à lui). */
    private fun <T> onMain(block: () -> T): T = runBlocking(Dispatchers.Main) { block() }

    /** Une action, la montre à l'écran : exécutée, et le résumé lui revient. */
    override fun onMessageReceived(event: MessageEvent) {
        if (WearProtocol.kind(event.path) != WearProtocol.Kind.action) return
        val data = event.data
        val node = event.sourceNodeId
        onMain { bridge.message(data, node) }
    }

    private class Voice(val uri: Uri, val metadata: Map<String, Any?>, val asset: Asset)

    override fun onDataChanged(events: DataEventBuffer) {
        val queued = ArrayList<Pair<String, ByteArray>>()
        val queuedUris = ArrayList<Uri>()
        val voices = ArrayList<Voice>()
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            val path = item.uri.path ?: continue
            when (WearProtocol.kind(path)) {
                WearProtocol.Kind.queued -> {
                    item.data?.let { queued.add(path to it) }
                    queuedUris.add(item.uri)
                }
                WearProtocol.Kind.voice -> {
                    val map = DataMapItem.fromDataItem(item).dataMap
                    val asset = map.getAsset(WearProtocol.audioKey)
                    if (asset != null) voices.add(Voice(item.uri, WearProtocol.metadata(map), asset)) else queuedUris.add(item.uri)
                }
                else -> {}
            }
        }
        if (queued.isEmpty() && voices.isEmpty() && queuedUris.isEmpty()) return
        val client = Wearable.getDataClient(this)
        // Le son des vocaux, lu ici (hors du fil principal) : 1 Mo au plus.
        val sounds = voices.map { v ->
            v to try {
                Tasks.await(client.getFdForAsset(v.asset), 30, TimeUnit.SECONDS).inputStream.use { input ->
                    readUpTo(input, WatchLink.maxVoiceBytes)
                }
            } catch (e: Exception) {
                null
            }
        }
        onMain {
            if (queued.isNotEmpty()) bridge.queued(queued)
            for ((v, audio) in sounds) if (audio != null) bridge.voice(v.metadata, audio)
        }
        // Traités : la montre n'a plus à les garder (`didFinish fileTransfer`).
        for (uri in queuedUris + voices.map { it.uri }) {
            try {
                Tasks.await(client.deleteDataItems(uri), 10, TimeUnit.SECONDS)
            } catch (e: Exception) {
            }
        }
    }
}

/** Tout ce que contient `input`, s'il ne dépasse pas `max` octets (rien sinon). */
internal fun readUpTo(input: java.io.InputStream, max: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > max) return null
    }
    return out.toByteArray()
}
