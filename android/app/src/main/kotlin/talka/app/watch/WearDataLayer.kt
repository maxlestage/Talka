// La liaison avec la montre par la couche de données de Wear OS (Google
// Play Services) : ce que `WCSession` est à l'iPhone. Les messages
// eux-mêmes, et leur forme, sont dans `WearProtocol`.
//
// Sans Wear OS sur le téléphone (ni montre ni application compagnon), les
// appels échouent sans bruit : il n'y a simplement pas de montre.

package talka.app.watch

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import talka.kit.TalkaLog
import talka.link.WearProtocol

class WearDataLayer(context: Context) : WatchPort {
    private val context = context.applicationContext
    private val data by lazy { Wearable.getDataClient(this.context) }
    private val messages by lazy { Wearable.getMessageClient(this.context) }
    private val capabilities by lazy { Wearable.getCapabilityClient(this.context) }

    /** Les montres qui ont l'application, et celles qui répondent maintenant. */
    @Volatile private var installed: Set<String> = emptySet()

    @Volatile private var nearby: Set<String> = emptySet()
    private var listener: CapabilityClient.OnCapabilityChangedListener? = null

    override val watchReady: Boolean get() = installed.isNotEmpty()
    override val reachable: Boolean get() = nearby.isNotEmpty()

    override fun activate(changed: () -> Unit) {
        if (listener != null) return
        val l = CapabilityClient.OnCapabilityChangedListener { refresh(changed) }
        listener = l
        guarded("capacités") {
            capabilities.addListener(l, WearProtocol.watchCapability)
            refresh(changed)
        }
    }

    /** Quelles montres ont Talka, lesquelles sont là (`sessionWatchStateDidChange`, `sessionReachabilityDidChange`). */
    private fun refresh(changed: () -> Unit) {
        guarded("capacités") {
            capabilities.getCapability(WearProtocol.watchCapability, CapabilityClient.FILTER_ALL)
                .addOnSuccessListener { all ->
                    installed = ids(all)
                    capabilities.getCapability(WearProtocol.watchCapability, CapabilityClient.FILTER_REACHABLE)
                        .addOnSuccessListener { near ->
                            nearby = ids(near)
                            changed()
                        }
                        .addOnFailureListener { nearby = emptySet() }
                }
                .addOnFailureListener {
                    installed = emptySet()
                    nearby = emptySet()
                }
        }
    }

    private fun ids(info: CapabilityInfo?): Set<String> = info?.nodes?.map { it.id }?.toSet() ?: emptySet()

    override fun updateContext(data: ByteArray) {
        guarded("résumé") {
            val request = PutDataRequest.create(WearProtocol.snapshotPath).setData(data).setUrgent()
            this.data.putDataItem(request)
        }
    }

    override fun sendMessage(data: ByteArray) {
        for (node in nearby) reply(node, data)
    }

    override fun reply(node: String, data: ByteArray) {
        guarded("message") { messages.sendMessage(node, WearProtocol.snapshotPath, data) }
    }

    /** Efface un élément reçu de la montre (une action en file, un vocal), une fois traité. */
    fun delete(uri: Uri) {
        guarded("effacement") { data.deleteDataItems(uri) }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            // Pas de Play Services, ou pas de Wear OS : pas de montre.
            TalkaLog.sink?.invoke("montre : $what impossible (${e.javaClass.simpleName})")
        }
    }
}
