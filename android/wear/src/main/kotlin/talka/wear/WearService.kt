// Ce que le téléphone envoie à la montre, même l'application fermée : Wear
// OS la réveille et passe par ce service (`didReceiveApplicationContext`,
// `didReceiveMessage` du `WCSessionDelegate` de l'Apple Watch). Le résumé
// est gardé, et les complications du cadran suivent.

package talka.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import talka.link.WearProtocol

class WearService : WearableListenerService() {
    private val model: WatchModel get() = (application as WearApp).model

    private fun onMain(block: () -> Unit) = runBlocking(Dispatchers.Main) { block() }

    /** Le résumé tout de suite (la montre à l'écran), ou la réponse à une action. */
    override fun onMessageReceived(event: MessageEvent) {
        if (WearProtocol.kind(event.path) != WearProtocol.Kind.snapshot) return
        val data = event.data
        onMain { model.receive(data) }
    }

    /** Le dernier résumé laissé par le téléphone. */
    override fun onDataChanged(events: DataEventBuffer) {
        var latest: ByteArray? = null
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (WearProtocol.kind(item.uri.path ?: "") == WearProtocol.Kind.snapshot) latest = item.data
        }
        val data = latest ?: return
        onMain { model.receive(data) }
    }
}
