// Les widgets de Talka sur l'écran d'accueil (Jetpack Glance), ceux de
// l'iPhone (`TalkaWidgets.swift`) :
//
// - « Non lus » : le nombre de messages non lus et le dernier arrivé. Petit
//   carré ; étiré en bandeau bas, le rectangle de l'écran verrouillé de
//   l'iPhone (le logo, le nombre, le dernier message) ; réduit à deux cases,
//   sa ligne (« 3 non lus ») ; à une seule, son rond (l'icône et le nombre).
// - « Discussions » : les discussions récentes, chacune ouvre la sienne
//   d'un toucher (moyen : trois ; grand : six).
//
// Les données viennent de l'application (`WatchBridge.updateWidgets`), par
// `WidgetShare` ; elle recharge les widgets quand elles changent. Si Talka
// est protégé par un code, les widgets ne montrent que le nombre de non-lus.
// Les textes sont calculés par `WidgetContent` (partagé avec la montre).

package talka.app.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import talka.app.MainActivity
import talka.app.R
import talka.kit.WidgetChat
import talka.kit.WidgetData
import talka.kit.WidgetShare
import talka.link.WidgetContent
import java.util.Date

/** Ce que les widgets affichent, et leur rechargement (`WidgetCenter`). */
object TalkaWidgets {
    private val current = MutableStateFlow<WidgetData?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Les données des widgets, telles que l'application les a écrites en dernier. */
    val data: StateFlow<WidgetData?> get() = current

    /** Relit `WidgetShare` (les widgets affichés se redessinent). */
    fun refresh() {
        current.value = WidgetShare.read()
    }

    /** Les données ont changé : tous les widgets de Talka sont redessinés. */
    fun reload(context: Context) {
        refresh()
        val app = context.applicationContext
        scope.launch {
            try {
                UnreadWidget().updateAll(app)
                RecentChatsWidget().updateAll(app)
            } catch (e: Exception) {
                // Pas d'écran d'accueil (ou pas encore de widget) : rien à faire.
            }
        }
    }
}

/** Les couleurs des widgets, celles de l'iPhone. */
internal object WidgetColors {
    val talka = Color(0xFF00A884)
    val white = Color.White
    val gray = Color(0xFF8E8E93)
    val primary = ColorProvider(day = Color(0xFF000000), night = Color(0xFFFFFFFF))
    val secondary = ColorProvider(day = Color(0xFF6C6C70), night = Color(0xFFAEAEB2))
}

/** Toucher : ouvre Talka à cette adresse (`talka://chat/…`, `talka://chats`). */
internal fun openLink(context: Context, link: String): Action =
    actionStartActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )

private fun color(c: Color) = ColorProvider(c)

// ----- « Non lus » -----

class UnreadWidget : GlanceAppWidget() {
    companion object {
        /** Le petit carré (`systemSmall`). */
        val SMALL = DpSize(110.dp, 110.dp)

        /** Le bandeau, bas et large (le rectangle de l'écran verrouillé de l'iPhone, `accessoryRectangular`). */
        val RECTANGULAR = DpSize(180.dp, 48.dp)

        /** La ligne (`accessoryInline`) : deux cases de large, une de haut. */
        val INLINE = DpSize(110.dp, 48.dp)

        /** Le rond (`accessoryCircular`) : une seule case. */
        val CIRCULAR = DpSize(48.dp, 48.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(CIRCULAR, INLINE, RECTANGULAR, SMALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        TalkaWidgets.refresh()
        provideContent {
            val data by TalkaWidgets.data.collectAsState()
            when (LocalSize.current) {
                CIRCULAR -> CircularView(data)
                INLINE -> InlineView(data)
                RECTANGULAR -> RectangularView(data)
                else -> SmallView(data)
            }
        }
    }
}

class UnreadWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UnreadWidget()
}

/** Le carré : sur le vert de Talka, le nombre de non-lus et le dernier message. */
@Composable
internal fun SmallView(data: WidgetData?) {
    val context = LocalContext.current
    val white = color(WidgetColors.white)
    val latest = WidgetContent.smallLatest(data)
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_small_background))
            .padding(14.dp)
            .clickable(openLink(context, WidgetContent.unreadLink(data)))
            .semantics { contentDescription = WidgetContent.description(data) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_widget_bubble), null, GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(white))
            Spacer(GlanceModifier.width(5.dp))
            Text("Talka", style = TextStyle(color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(
            WidgetContent.smallCount(data),
            style = TextStyle(color = white, fontSize = 40.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Text(WidgetContent.smallLabel(data), style = TextStyle(color = color(Color.White.copy(alpha = 0.85f)), fontSize = 12.sp))
        if (latest != null) {
            Spacer(GlanceModifier.height(4.dp))
            Text(latest, style = TextStyle(color = color(Color.White.copy(alpha = 0.9f)), fontSize = 11.sp), maxLines = 2)
        }
    }
}

/** Le rond : sur le vert de Talka, la bulle (pleine s'il y a des non-lus) et leur nombre (« 99+ » au-delà). */
@Composable
internal fun CircularView(data: WidgetData?) {
    val context = LocalContext.current
    val white = color(WidgetColors.white)
    val unread = data?.unread ?: 0
    Box(
        GlanceModifier
            .fillMaxSize()
            .clickable(openLink(context, WidgetContent.unreadLink(data)))
            .semantics { contentDescription = WidgetContent.description(data) },
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(R.drawable.widget_circle), null, GlanceModifier.size(48.dp), colorFilter = ColorFilter.tint(color(WidgetColors.talka)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                ImageProvider(if (unread > 0) R.drawable.ic_widget_bubble else R.drawable.ic_widget_bubble_outline),
                null,
                GlanceModifier.size(13.dp),
                colorFilter = ColorFilter.tint(white),
            )
            Text(WidgetContent.badge(unread), style = TextStyle(color = white, fontSize = 17.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }
}

/** La ligne : la bulle, puis « 3 non lus », « Talka : rien de nouveau », ou « Talka » tant que rien n'est écrit. */
@Composable
internal fun InlineView(data: WidgetData?) {
    val context = LocalContext.current
    val white = color(WidgetColors.white)
    Row(
        GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_rectangular_background))
            .padding(horizontal = 12.dp)
            .clickable(openLink(context, WidgetContent.unreadLink(data))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(R.drawable.ic_widget_bubble), null, GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(white))
        Spacer(GlanceModifier.width(6.dp))
        Text(WidgetContent.inline(data), style = TextStyle(color = white, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/** Le rectangle : le logo, « Talka » et le nombre de non-lus, puis le dernier message arrivé. */
@Composable
internal fun RectangularView(data: WidgetData?) {
    val context = LocalContext.current
    val white = color(WidgetColors.white)
    val latest = WidgetContent.latest(data)
    val badge = WidgetContent.rectangularBadge(data)
    Row(
        GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_rectangular_background))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(openLink(context, WidgetContent.unreadLink(data))),
        verticalAlignment = Alignment.Top,
    ) {
        Box(GlanceModifier.size(24.dp), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.widget_circle), null, GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(color(WidgetColors.talka)))
            Image(ImageProvider(R.drawable.ic_widget_bubble), null, GlanceModifier.size(12.dp), colorFilter = ColorFilter.tint(white))
        }
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Talka", style = TextStyle(color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.defaultWeight())
                if (badge != null) {
                    Text(badge, style = TextStyle(color = color(WidgetColors.talka), fontSize = 16.sp, fontWeight = FontWeight.Bold))
                }
            }
            if (latest != null) {
                Text(latest.title, style = TextStyle(color = white, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Text(latest.last, style = TextStyle(color = color(Color.White.copy(alpha = 0.7f)), fontSize = 12.sp), maxLines = 1)
            } else {
                Text(WidgetContent.status(data), style = TextStyle(color = color(Color.White.copy(alpha = 0.7f)), fontSize = 12.sp), maxLines = 2)
            }
        }
    }
}

// ----- « Discussions » -----

class RecentChatsWidget : GlanceAppWidget() {
    companion object {
        /** Moyen (`systemMedium`) : trois discussions. */
        val MEDIUM = DpSize(250.dp, 110.dp)

        /** Grand (`systemLarge`) : six. */
        val LARGE = DpSize(250.dp, 260.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        TalkaWidgets.refresh()
        provideContent {
            val data by TalkaWidgets.data.collectAsState()
            RecentView(data, large = LocalSize.current == LARGE)
        }
    }
}

class RecentChatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentChatsWidget()
}

@Composable
internal fun RecentView(data: WidgetData?, large: Boolean) {
    val context = LocalContext.current
    val state = WidgetContent.recent(data, large)
    val green = color(WidgetColors.talka)
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_surface))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clickable(openLink(context, WidgetContent.recentLink)),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_widget_bubble), null, GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(green))
            Spacer(GlanceModifier.width(5.dp))
            Text("Talka", style = TextStyle(color = green, fontSize = 16.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.defaultWeight())
            Text(WidgetContent.recentSummary(data), style = TextStyle(color = WidgetColors.secondary, fontSize = 12.sp), maxLines = 1)
        }
        Spacer(GlanceModifier.height(8.dp))
        when (state) {
            is WidgetContent.Recent.Rows -> {
                for (c in state.chats) {
                    ChatLine(c)
                    Spacer(GlanceModifier.height(8.dp))
                }
                Spacer(GlanceModifier.defaultWeight())
            }
            else -> {
                Spacer(GlanceModifier.defaultWeight())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state == WidgetContent.Recent.Hidden) {
                        Image(ImageProvider(R.drawable.ic_widget_lock), null, GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(WidgetColors.secondary))
                        Spacer(GlanceModifier.width(6.dp))
                    }
                    Text(WidgetContent.recentMessage(state) ?: "", style = TextStyle(color = WidgetColors.secondary, fontSize = 14.sp))
                }
                Spacer(GlanceModifier.defaultWeight())
            }
        }
    }
}

/** Une discussion : l'avatar, le nom, le dernier message ; l'heure et les non-lus à droite. */
@Composable
internal fun ChatLine(chat: WidgetChat) {
    val context = LocalContext.current
    val white = color(WidgetColors.white)
    val green = color(WidgetColors.talka)
    Row(
        GlanceModifier.fillMaxWidth().clickable(openLink(context, WidgetData.link(chat.id))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.size(34.dp), contentAlignment = Alignment.Center) {
            val fill = if (chat.group) WidgetColors.gray else Color(WidgetContent.avatar(chat.color))
            Image(ImageProvider(R.drawable.widget_circle), null, GlanceModifier.size(34.dp), colorFilter = ColorFilter.tint(color(fill)))
            if (chat.group) {
                Image(ImageProvider(R.drawable.ic_widget_group), null, GlanceModifier.size(15.dp), colorFilter = ColorFilter.tint(white))
            } else {
                Text(chat.initials, style = TextStyle(color = white, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            }
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(
                chat.title,
                style = TextStyle(color = WidgetColors.primary, fontSize = 15.sp, fontWeight = if (chat.unread > 0) FontWeight.Bold else FontWeight.Medium),
                maxLines = 1,
            )
            Text(chat.last, style = TextStyle(color = WidgetColors.secondary, fontSize = 12.sp), maxLines = 1)
        }
        Spacer(GlanceModifier.width(4.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                time(context, chat.at),
                style = TextStyle(color = if (chat.unread > 0) green else WidgetColors.secondary, fontSize = small),
            )
            if (chat.unread > 0) {
                Spacer(GlanceModifier.height(3.dp))
                Box(
                    GlanceModifier.height(18.dp).background(ImageProvider(R.drawable.widget_badge)).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${chat.unread}", style = TextStyle(color = white, fontSize = small, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

private val small: TextUnit = 11.sp

/** L'heure d'un message (`style: .time`), au format de l'appareil. */
internal fun time(context: Context, at: Double): String = DateFormat.getTimeFormat(context).format(Date(at.toLong()))
