// Le lien entre le téléphone et la montre, sur la couche de données de
// Wear OS (Data Layer) : ce que WatchConnectivity est à l'iPhone. Ce
// fichier est compilé dans les deux applications (`app` et `wear`), pour
// qu'elles parlent exactement la même langue.
//
// Ce qui passe (les octets : ceux de `WatchLink.encode`, comme sur l'iPhone) :
//
// - le dernier résumé (`updateApplicationContext`) : l'élément de données
//   `/talka/snapshot`, que la montre retrouve même en se réveillant plus tard ;
// - le résumé tout de suite (`sendMessage`) : le message `/talka/snapshot` ;
// - une action de la montre (`sendMessage` avec réponse) : le message
//   `/talka/action` ; le téléphone répond par le message `/talka/snapshot`,
//   à la seule montre qui l'a envoyée ;
// - une action en file (`transferUserInfo`) : l'élément
//   `/talka/queue/<ordre>-<id>`, livré dès que les deux appareils se
//   retrouvent, exécuté dans l'ordre d'envoi puis effacé par le téléphone ;
// - un vocal (`transferFile`) : l'élément `/talka/voice/<id>`, le son en
//   `Asset` (clé `audio`) et ses métadonnées (`WatchLink.voiceMetadata`) à
//   côté, effacé une fois lu par le téléphone.
//
// Le téléphone et la montre se trouvent par leurs capacités
// (`res/values/wear.xml` de chacune) : `talka_phone`, `talka_watch`. Le
// même identifiant d'application et la même signature des deux côtés : la
// couche de données ne relie qu'elles.

package talka.link

import com.google.android.gms.wearable.DataMap
import talka.kit.WatchLink
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

object WearProtocol {
    const val snapshotPath = "/talka/" + WatchLink.snapshotKey
    const val actionPath = "/talka/" + WatchLink.actionKey
    const val queuePrefix = "/talka/queue/"
    const val voicePrefix = "/talka/voice/"

    /** La capacité du téléphone (Talka installé), que cherche la montre. */
    const val phoneCapability = "talka_phone"

    /** La capacité de la montre (l'application de la montre installée), que cherche le téléphone. */
    const val watchCapability = "talka_watch"

    /** La clé du son d'un vocal, dans son élément de données. */
    const val audioKey = "audio"

    enum class Kind { snapshot, action, queued, voice, other }

    fun kind(path: String): Kind =
        when {
            path == snapshotPath -> Kind.snapshot
            path == actionPath -> Kind.action
            path.startsWith(queuePrefix) && path.length > queuePrefix.length -> Kind.queued
            path.startsWith(voicePrefix) && path.length > voicePrefix.length -> Kind.voice
            else -> Kind.other
        }

    private val lastOrder = AtomicLong(0)

    /** Un ordre qui ne fait que croître (microsecondes, à peu près), même pour deux actions de la même milliseconde. */
    fun nextOrder(nowMs: Long = System.currentTimeMillis()): Long =
        lastOrder.updateAndGet { maxOf(it + 1, nowMs * 1000) }

    /** Le chemin d'une action en file : son ordre d'envoi s'y lit, et s'y trie. */
    fun queuePath(order: Long = nextOrder(), id: String = UUID.randomUUID().toString()): String =
        queuePrefix + "%019d".format(order) + "-" + id

    fun voicePath(id: String = UUID.randomUUID().toString()): String = voicePrefix + id

    /** Les actions en file, dans l'ordre où la montre les a faites. */
    fun <T> inOrder(items: List<T>, path: (T) -> String): List<T> = items.sortedBy(path)

    /** Les métadonnées d'un vocal (`WatchLink.voiceMetadata`) dans son élément de données. */
    fun putMetadata(map: DataMap, metadata: Map<String, Any>) {
        for ((k, v) in metadata) {
            when (v) {
                is String -> map.putString(k, v)
                is Double -> map.putDouble(k, v)
                is Int -> map.putInt(k, v)
                is Long -> map.putLong(k, v)
                is Boolean -> map.putBoolean(k, v)
            }
        }
    }

    /** Et à l'arrivée, pour `WatchLink.voice` (le son n'en fait pas partie). */
    fun metadata(map: DataMap): Map<String, Any?> =
        map.keySet().filter { it != audioKey }.associateWith { k ->
            @Suppress("DEPRECATION")
            map.get<Any?>(k)
        }
}
