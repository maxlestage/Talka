// La liaison avec le téléphone par la couche de données de Wear OS
// (Google Play Services) : ce que `WCSession` est à l'Apple Watch. Les
// chemins et les octets sont dans `WearProtocol` (partagé avec le téléphone).

package talka.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import talka.link.WearProtocol
import java.io.File
import java.io.IOException

class WearDataLayer(context: Context) : PhonePort {
    private val context = context.applicationContext
    private val data by lazy { Wearable.getDataClient(this.context) }
    private val messages by lazy { Wearable.getMessageClient(this.context) }
    private val capabilities by lazy { Wearable.getCapabilityClient(this.context) }

    /** Les téléphones (avec Talka) qui répondent maintenant. */
    @Volatile private var phones: Set<String> = emptySet()

    @Volatile private var activated = false
    private var listener: CapabilityClient.OnCapabilityChangedListener? = null

    override val reachable: Boolean get() = activated && phones.isNotEmpty()

    override fun activate(events: PhonePort.Events) {
        if (listener != null) return
        val l = CapabilityClient.OnCapabilityChangedListener { info ->
            phones = ids(info)
            events.reachabilityChanged(reachable)
        }
        listener = l
        try {
            capabilities.addListener(l, WearProtocol.phoneCapability)
            // Le dernier résumé laissé par le téléphone, puis qui répond.
            val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WearProtocol.snapshotPath).build()
            data.getDataItems(uri).addOnCompleteListener { task ->
                val context = if (task.isSuccessful) {
                    val buffer = task.result
                    try {
                        buffer.firstOrNull()?.data
                    } finally {
                        buffer.release()
                    }
                } else {
                    null
                }
                capabilities.getCapability(WearProtocol.phoneCapability, CapabilityClient.FILTER_REACHABLE)
                    .addOnCompleteListener { found ->
                        phones = if (found.isSuccessful) ids(found.result) else emptySet()
                        activated = true
                        events.activated(context, reachable)
                    }
            }
        } catch (e: RuntimeException) {
            // Pas de Play Services : pas de téléphone.
        }
    }

    private fun ids(info: CapabilityInfo?): Set<String> = info?.nodes?.map { it.id }?.toSet() ?: emptySet()

    override fun sendMessage(data: ByteArray, done: (Boolean) -> Unit) {
        val phone = phones.firstOrNull() ?: return done(false)
        try {
            messages.sendMessage(phone, WearProtocol.actionPath, data)
                .addOnSuccessListener { done(true) }
                .addOnFailureListener { done(false) }
        } catch (e: RuntimeException) {
            done(false)
        }
    }

    override fun transferUserInfo(data: ByteArray) {
        try {
            this.data.putDataItem(PutDataRequest.create(WearProtocol.queuePath()).setData(data).setUrgent())
        } catch (e: RuntimeException) {
        }
    }

    override fun transferFile(file: File, metadata: Map<String, Any>, done: () -> Unit) {
        try {
            val request = PutDataMapRequest.create(WearProtocol.voicePath())
            WearProtocol.putMetadata(request.dataMap, metadata)
            request.dataMap.putAsset(WearProtocol.audioKey, Asset.createFromBytes(file.readBytes()))
            // Une fois l'élément rangé par la couche de données, il partira
            // vers le téléphone de lui-même : le fichier n'a plus à être gardé.
            this.data.putDataItem(request.asPutDataRequest().setUrgent()).addOnCompleteListener { done() }
        } catch (e: IOException) {
            done()
        } catch (e: RuntimeException) {
            done()
        }
    }
}
