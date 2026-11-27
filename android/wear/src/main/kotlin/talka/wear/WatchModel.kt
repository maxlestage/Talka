// L'état de Talka sur la montre : le dernier résumé reçu du téléphone
// (gardé pour l'afficher même loin de lui), et l'envoi des actions.
// `WatchModel.swift` de l'Apple Watch ; la liaison (`WCSession`) est ici
// `PhonePort` : la couche de données de Wear OS (`WearDataLayer`), ou un
// faux dans les tests.

package talka.wear

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import talka.kit.WatchAction
import talka.kit.WatchChat
import talka.kit.WatchLink
import talka.kit.WatchMessage
import talka.kit.WatchSnapshot
import talka.kit.WidgetData
import talka.kit.WidgetShare
import java.io.File
import java.util.Base64
import java.util.UUID

/** La liaison avec le téléphone (la `WCSession` de la montre). */
interface PhonePort {
    interface Events {
        /** La liaison est prête : le dernier résumé laissé par le téléphone (`receivedApplicationContext`), et s'il répond. */
        fun activated(context: ByteArray?, reachable: Boolean)

        /** Le téléphone répond, ou plus (`sessionReachabilityDidChange`). */
        fun reachabilityChanged(reachable: Boolean)
    }

    fun activate(events: Events)

    /** La liaison est prête et le téléphone répond (`activationState == .activated && isReachable`). */
    val reachable: Boolean

    /** Tout de suite (`sendMessage`) ; `done(false)` : pas parti. La réponse (le résumé) arrive à part. */
    fun sendMessage(data: ByteArray, done: (Boolean) -> Unit)

    /** En file : livré au téléphone dès que possible, dans l'ordre (`transferUserInfo`). */
    fun transferUserInfo(data: ByteArray)

    /** Un fichier, avec ses métadonnées (`transferFile`) ; `done` : il n'a plus à être gardé. */
    fun transferFile(file: File, metadata: Map<String, Any>, done: () -> Unit)
}

class WatchModel(
    private val port: PhonePort,
    private val prefs: SharedPreferences,
    /** Recharge les complications du cadran (`WidgetCenter.reloadAllTimelines`). */
    private val reloadComplications: () -> Unit = {},
    private val now: () -> Double = { System.currentTimeMillis().toDouble() },
) : PhonePort.Events {
    var snapshot by mutableStateOf<WatchSnapshot?>(null)
        private set

    /** Le téléphone répond-il en ce moment ? */
    var reachable by mutableStateOf(false)
        private set

    init {
        snapshot = WatchLink.decode(WatchSnapshot, saved())
        // Les complications partent du dernier résumé gardé, même s'il n'a
        // pas changé depuis (une mise à jour de l'app, par exemple).
        updateWidgets()
        port.activate(this)
    }

    private fun saved(): ByteArray? =
        try {
            prefs.getString(savedKey, null)?.let { Base64.getDecoder().decode(it) }
        } catch (e: IllegalArgumentException) {
            null
        }

    fun chat(id: String): WatchChat? = snapshot?.chats?.firstOrNull { it.id == id }

    fun receive(data: ByteArray?) {
        val bytes = data ?: return
        val s = WatchLink.decode(WatchSnapshot, bytes) ?: return
        if (s != snapshot) {
            snapshot = s
            prefs.edit().putString(savedKey, Base64.getEncoder().encodeToString(bytes)).apply()
        }
        // Toujours : les complications n'avaient peut-être encore rien.
        updateWidgets()
    }

    /** Les complications : rechargées seulement si elles ont changé. */
    private fun updateWidgets() {
        val s = snapshot ?: return
        if (WidgetShare.write(WidgetData.of(s, hidden = s.hidden ?: false))) reloadComplications()
    }

    // ----- Les actions -----

    fun send(chat: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        // Affiché tout de suite, avec l'horloge : le téléphone confirme ensuite.
        appendMine(chat, t)
        perform(WatchAction(WatchAction.Kind.send, chat = chat, text = t))
    }

    /**
     * Un vocal : affiché tout de suite, puis confié à la couche de données,
     * qui le livre au téléphone dès que possible (même plus tard, loin de lui).
     */
    fun sendVoice(chat: String, file: File, seconds: Double) {
        appendMine(chat, "🎙️ Message vocal (${voiceDuration(seconds)})")
        port.transferFile(file, WatchLink.voiceMetadata(chat, seconds)) { file.delete() }
    }

    private fun appendMine(chat: String, text: String) {
        update(chat) { c ->
            val m = WatchMessage(id = "montre-${UUID.randomUUID()}", mine = true, text = text, at = now(), tick = "pending")
            c.copy(messages = c.messages + m, last = text, at = m.at)
        }
    }

    fun react(chat: String, id: String, emoji: String) {
        perform(WatchAction(WatchAction.Kind.react, chat = chat, id = id, text = emoji))
    }

    fun markRead(chat: String) {
        val c = chat(chat) ?: return
        if (c.unread <= 0) return
        update(chat) { it.copy(unread = 0) }
        updateWidgets()
        perform(WatchAction(WatchAction.Kind.read, chat = chat))
    }

    fun refresh() {
        perform(WatchAction(WatchAction.Kind.refresh))
    }

    private fun update(chat: String, f: (WatchChat) -> WatchChat) {
        val s = snapshot ?: return
        val i = s.chats.indexOfFirst { it.id == chat }
        if (i < 0) return
        snapshot = s.copy(chats = s.chats.toMutableList().also { it[i] = f(it[i]) })
    }

    /**
     * Tout de suite si le téléphone répond ; sinon, mis en file : livré dès
     * qu'il est de nouveau là, dans l'ordre. (Une demande de résumé ne se
     * met pas en file.)
     */
    private fun perform(action: WatchAction) {
        val data = WatchLink.encode(action)
        val queue = action.kind != WatchAction.Kind.refresh
        if (port.reachable) {
            port.sendMessage(data) { ok -> if (!ok && queue) port.transferUserInfo(data) }
        } else if (queue) {
            port.transferUserInfo(data)
        }
    }

    // ----- La liaison -----

    override fun activated(context: ByteArray?, reachable: Boolean) {
        this.reachable = reachable
        receive(context)
        refresh()
    }

    override fun reachabilityChanged(reachable: Boolean) {
        this.reachable = reachable
        if (reachable) refresh()
    }

    companion object {
        const val savedKey = "talka.watch.snapshot"
    }
}
