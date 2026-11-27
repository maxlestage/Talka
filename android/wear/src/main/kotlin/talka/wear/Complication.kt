// « Non lus » sur le cadran : le nombre de messages non lus et le dernier
// arrivé (les complications de l'Apple Watch, `TalkaWidgets.swift`). Les
// données viennent de l'application de la montre (`WatchModel`), par
// `WidgetShare` ; elle redemande les complications quand elles changent.
//
// - texte court (le rond, le coin) : le nombre (« 99+ » au-delà) ;
// - texte long (le rectangle, la ligne) : « Talka », le nombre, puis la
//   discussion non lue la plus récente, ou l'état.

package talka.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import talka.kit.WidgetData
import talka.kit.WidgetShare
import talka.link.WidgetContent

/** Les textes d'une complication, calculés à part de son dessin. */
data class ComplicationTexts(
    /** Le rond (`accessoryCircular`) : le nombre. */
    val short: String,
    /** Sous le nombre (le coin, `accessoryCorner`, lit « 3 non lus ») ; « Talka » sans non-lus. */
    val shortTitle: String,
    /** Le rectangle : « Talka », et le nombre s'il y en a. */
    val longTitle: String,
    /** Puis « Léa : On se retrouve à 19 h ? », ou l'état. */
    val long: String,
    /** Ce que lit le lecteur d'écran. */
    val description: String,
    /** Il y a des non-lus (la bulle pleine, plutôt que vide). */
    val unread: Boolean,
) {
    companion object {
        fun of(data: WidgetData?): ComplicationTexts {
            val d = data ?: WidgetData()
            val latest = WidgetContent.latest(data)
            val badge = WidgetContent.rectangularBadge(data)
            return ComplicationTexts(
                short = WidgetContent.badge(d.unread),
                shortTitle = if (d.unread > 0) "non lus" else "Talka",
                longTitle = if (badge != null) "Talka · $badge" else "Talka",
                long = latest?.let { "${it.title} : ${it.last}" } ?: WidgetContent.status(data),
                description = WidgetContent.description(data),
                unread = d.unread > 0,
            )
        }
    }
}

class UnreadComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        data(request.complicationType, WidgetShare.read())

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, WidgetContent.sample())

    private fun text(s: String) = PlainComplicationText.Builder(s).build()

    private fun data(type: ComplicationType, data: WidgetData?): ComplicationData? {
        val t = ComplicationTexts.of(data)
        // Toucher : ouvre Talka sur la montre.
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = MonochromaticImage.Builder(
            Icon.createWithResource(this, if (t.unread) R.drawable.ic_chats else R.drawable.ic_chats_outline),
        ).build()
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text(t.short), text(t.description))
                    .setTitle(text(t.shortTitle))
                    .setMonochromaticImage(icon)
                    .setTapAction(open)
                    .build()
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(text(t.long), text(t.description))
                    .setTitle(text(t.longTitle))
                    .setMonochromaticImage(icon)
                    .setTapAction(open)
                    .build()
            else -> null
        }
    }
}
