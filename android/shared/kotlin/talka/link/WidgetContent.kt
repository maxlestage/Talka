// Ce que montrent les widgets de Talka (téléphone) et ses complications
// (montre), calculé à part de leur dessin : les textes et les adresses de
// `TalkaWidgets.swift`, à l'identique. `null` : Talka n'a encore rien écrit
// (pas ouvert, ou pas de compte).

package talka.link

import talka.kit.WidgetChat
import talka.kit.WidgetData

object WidgetContent {
    /** La discussion à mettre en avant : la plus récente des non lues (rien si Talka est protégé par un code). */
    fun latest(data: WidgetData?): WidgetChat? {
        val d = data ?: return null
        if (d.hidden) return null
        return d.chats.firstOrNull { it.unread > 0 }
    }

    /** Ce qu'ouvre un toucher sur « Non lus » : la dernière discussion non lue, ou la liste. */
    fun unreadLink(data: WidgetData?): String = WidgetData.link(latest(data)?.id)

    /** Le nombre, en pastille (rond, rectangle) : « 99+ » au-delà. */
    fun badge(unread: Int): String = if (unread > 99) "99+" else "$unread"

    // ----- « Non lus », le petit carré -----

    fun smallCount(data: WidgetData?): String = "${data?.unread ?: 0}"

    fun smallLabel(data: WidgetData?): String = if ((data?.unread ?: 0) == 1) "message non lu" else "messages non lus"

    /** « Léa : On se retrouve à 19 h ? » */
    fun smallLatest(data: WidgetData?): String? = latest(data)?.let { "${it.title} : ${it.last}" }

    // ----- « Non lus », le rectangle -----

    /** Sous « Talka », quand aucune discussion non lue n'est à montrer. */
    fun status(data: WidgetData?): String {
        if (data == null) return "Vos messages s'afficheront ici"
        if (data.unread == 0) return "Aucun nouveau message"
        return data.summary
    }

    /** La pastille du rectangle (rien sans non-lus). */
    fun rectangularBadge(data: WidgetData?): String? = data?.unread?.takeIf { it > 0 }?.let { badge(it) }

    // ----- « Non lus », la ligne et le coin (montre) -----

    fun inline(data: WidgetData?): String {
        val d = data ?: return "Talka"
        return when (d.unread) {
            0 -> "Talka : rien de nouveau"
            1 -> "1 non lu"
            else -> "${d.unread} non lus"
        }
    }

    fun corner(data: WidgetData?): String {
        val unread = data?.unread ?: 0
        return if (unread > 0) "$unread non lus" else "Talka"
    }

    /** Ce que lit le lecteur d'écran. */
    fun description(data: WidgetData?): String = (data ?: WidgetData()).summary

    // ----- « Discussions » -----

    sealed class Recent {
        /** Talka n'a encore rien écrit. */
        data object Waiting : Recent()

        /** Talka est protégé par un code : les discussions restent dans l'application. */
        data object Hidden : Recent()

        data object Empty : Recent()

        data class Rows(val chats: List<WidgetChat>) : Recent()
    }

    /** Moyen : trois discussions ; grand : six. */
    fun recent(data: WidgetData?, large: Boolean): Recent {
        val d = data ?: return Recent.Waiting
        if (d.hidden) return Recent.Hidden
        val rows = d.chats.take(if (large) 6 else 3)
        return if (rows.isEmpty()) Recent.Empty else Recent.Rows(rows)
    }

    /** À droite de « Talka », en haut. */
    fun recentSummary(data: WidgetData?): String = data?.summary ?: ""

    fun recentMessage(state: Recent): String? =
        when (state) {
            Recent.Waiting -> "Vos discussions récentes s'afficheront ici."
            Recent.Hidden -> "Talka est protégé par un code : les discussions restent dans l'application."
            Recent.Empty -> "Aucune discussion pour l'instant."
            is Recent.Rows -> null
        }

    /** Le lien de tout le widget « Discussions » : la liste. */
    val recentLink: String get() = WidgetData.link()

    /** Pour la galerie des widgets (`WidgetData.sample`). */
    fun sample(now: Double = System.currentTimeMillis().toDouble()): WidgetData =
        WidgetData(
            unread = 3,
            chats = listOf(
                WidgetChat("WGSEAJNSZJ", "Léa", "L", 0, false, 2, "On se retrouve à 19 h ?", now - 120_000),
                WidgetChat("g:famille", "Famille", "F", 3, true, 1, "Papa : 📷 Photo", now - 900_000),
                WidgetChat("K7Q2MX9PRT", "Clément", "C", 5, false, 0, "Merci !", now - 3_600_000),
            ),
            at = now,
        )

    /** Les couleurs d'avatar des widgets (ARGB), celles de l'iPhone. */
    val avatars: List<Long> = listOf(
        0xFFE542A3, 0xFF1F7AEC, 0xFFD36B00, 0xFF02A698, 0xFF7F66FF, 0xFFC4532D, 0xFF008069, 0xFFA62C71,
    )

    fun avatar(color: Int): Long = avatars[Math.floorMod(color, avatars.size)]
}
