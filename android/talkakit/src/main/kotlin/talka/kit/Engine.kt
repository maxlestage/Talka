// Le moteur (`Engine.swift`) viendra avec le relais et le stockage ; ses
// fonctions pures sont déjà là : l'aperçu d'un message, celui des
// notifications, la durée d'un vocal.

package talka.kit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class Engine private constructor() {
    companion object {
        /** Ce que montre la liste des discussions (et les notifications) d'un message. */
        fun preview(m: Message): String {
            if (m.deleted) return "🚫 Message supprimé"
            // Ni l'aperçu de la liste, ni la notification ne trahissent une capsule.
            if (m.isCapsule) return "⏳ Capsule temporelle"
            return when (m.kind) {
                "image" -> "📷 ${if (m.text.isEmpty()) "Photo" else Brocolis.masked(m.text)}"
                "voice" -> "🎙️ Message vocal (${formatDuration(m.dur)})"
                "file" -> "📄 ${m.name.ifEmpty { "Fichier" }}"
                "contact" -> "👤 ${m.name.ifEmpty { "Contact" }}"
                "brokey" -> "🥦 Clé Brocolis « ${m.name} »"
                else -> Brocolis.masked(m.text)
            }
        }

        /**
         * L'aperçu d'une notification pour le destinataire, en JSON : qui écrit
         * (`n`, notre nom choisi), le groupe (`g`), quoi (`t`, comme la liste
         * des discussions) et la discussion (`c`, vue de chez lui). Seulement
         * pour les messages écrits ; comme `Wire::apercu` du navigateur.
         */
        fun apercu(me: String, name: String, wire: Wire): String? {
            val body = wire.body as? Body.Msg ?: return null
            val m = Message("", "", me, 0.0, body.content)
            // `.whitespaces` : les espaces et les tabulations, pas les retours à la ligne.
            val trimmed = name.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
            val obj = sortedMapOf(
                "n" to if (trimmed.isEmpty()) TalkaCode.pretty(me) else trimmed.prefixChars(40),
                "t" to preview(m).prefixChars(120),
                "c" to (wire.groupId?.let { "g:$it" } ?: me),
            )
            wire.grp?.takeIf { it.name.isNotEmpty() }?.let { obj["g"] = it.name.prefixChars(40) }
            // Les clés dans l'ordre, comme `JSONSerialization` avec `.sortedKeys`.
            return JSON.write(JsonObject(obj.mapValues { JsonPrimitive(it.value) }))
        }
    }
}

/** Une durée en `m:ss` (un vocal, un appel). */
fun formatDuration(s: Double): String {
    val t = if (s.isFinite()) Math.round(minOf(maxOf(s, 0.0), 359_999.0)).toInt() else 0
    return "${t / 60}:" + "%02d".format(t % 60)
}
