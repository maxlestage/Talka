// Les écrans de la montre : la liste des discussions, une discussion,
// réagir. (`Views.swift` de l'Apple Watch.)

package talka.wear

import android.app.RemoteInput
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import kotlinx.coroutines.delay
import talka.kit.WatchChat
import talka.kit.WatchMessage
import talka.kit.watchQuickReplies
import talka.kit.watchReactions
import talka.link.WidgetContent
import java.util.Date

/** L'application : la liste, une discussion, l'enregistrement d'un vocal, réagir. */
@Composable
fun TalkaWatchApp(model: WatchModel) {
    MaterialTheme(colors = Colors(primary = WatchColors.talkaGreen, secondary = WatchColors.talkaGreen)) {
        val nav = rememberSwipeDismissableNavController()
        SwipeDismissableNavHost(navController = nav, startDestination = "chats") {
            composable("chats") {
                ChatsScreen(model) { id -> nav.navigate("chat/${Uri.encode(id)}") }
            }
            composable("chat/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                ConversationScreen(
                    model,
                    id,
                    record = { nav.navigate("record/${Uri.encode(id)}") },
                    react = { m -> nav.navigate("react/${Uri.encode(id)}/${Uri.encode(m.id)}") },
                )
            }
            composable("record/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                RecordScreen(model, id) { nav.popBackStack() }
            }
            composable("react/{chat}/{id}") { entry ->
                val chat = entry.arguments?.getString("chat") ?: return@composable
                val id = entry.arguments?.getString("id") ?: return@composable
                val current = model.chat(chat)?.messages?.firstOrNull { it.id == id }?.myReaction ?: ""
                ReactionPicker(current) { emoji ->
                    model.react(chat, id, emoji)
                    nav.popBackStack()
                }
            }
        }
    }
}

// ----- La liste -----

@Composable
fun ChatsScreen(model: WatchModel, open: (String) -> Unit) {
    val state = rememberScalingLazyListState()
    // Rien encore : on redemande au téléphone, régulièrement, tant que la
    // liste est vide et la montre à l'écran.
    LaunchedEffect(Unit) {
        model.refresh()
        while (model.snapshot?.chats.isNullOrEmpty()) {
            delay(4000)
            model.refresh()
        }
    }
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = state) }) {
        val s = model.snapshot
        ScalingLazyColumn(Modifier.fillMaxSize(), state = state) {
            item { Header(model) }
            if (s != null && s.chats.isNotEmpty()) {
                items(s.chats, key = { it.id }) { c -> ChatRow(c) { open(c.id) } }
            } else {
                item { EmptyState(model, waiting = s == null) }
            }
        }
    }
}

/** « Talka », et le téléphone hors de portée (les réponses partiront à son retour). */
@Composable
private fun Header(model: WatchModel) {
    ListHeader {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Talka", color = WatchColors.talkaGreen, fontWeight = FontWeight.Bold)
            if (model.snapshot != null && !model.reachable) {
                Icon(
                    painterResource(R.drawable.ic_phone_off),
                    "Téléphone hors de portée : les réponses partiront à son retour",
                    Modifier.size(16.dp),
                    tint = WatchColors.secondary,
                )
            }
        }
    }
}

@Composable
fun EmptyState(model: WatchModel, waiting: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            painterResource(if (waiting) R.drawable.ic_phone else R.drawable.ic_chats),
            null,
            Modifier.size(32.dp),
            tint = WatchColors.talkaGreen,
        )
        Text(
            if (waiting) "Ouvrez Talka sur votre téléphone" else "Aucune discussion",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center,
        )
        Text(
            if (waiting) "La montre affiche les discussions du téléphone et répond par lui." else "Vos discussions récentes apparaîtront ici.",
            style = MaterialTheme.typography.caption2,
            color = WatchColors.secondary,
            textAlign = TextAlign.Center,
        )
        Chip(
            onClick = { model.refresh() },
            label = { Text("Actualiser") },
            icon = { Icon(painterResource(R.drawable.ic_refresh), null) },
            colors = ChipDefaults.secondaryChipColors(),
        )
    }
}

/** L'avatar : la couleur de la discussion et ses initiales ; gris avec deux personnes pour un groupe. */
@Composable
fun AvatarView(chat: WatchChat, size: Dp = 32.dp) {
    val fill = if (chat.group) Color.Gray else Color(WidgetContent.avatar(chat.color))
    Box(Modifier.size(size).background(fill, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (chat.group) {
            Icon(painterResource(R.drawable.ic_group), null, Modifier.size(size * 0.5f), tint = Color.White)
        } else {
            Text(chat.initials, color = Color.White, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ChatRow(chat: WatchChat, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AvatarView(chat)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        chat.title,
                        style = MaterialTheme.typography.button,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (chat.pinned) Icon(painterResource(R.drawable.ic_pin), "Épinglée", Modifier.size(11.dp), tint = WatchColors.secondary)
                    if (chat.muted) Icon(painterResource(R.drawable.ic_muted), "En sourdine", Modifier.size(11.dp), tint = WatchColors.secondary)
                }
                Text(chat.last, style = MaterialTheme.typography.caption2, color = WatchColors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (chat.unread > 0) Badge(chat.unread)
        }
    }
}

@Composable
private fun Badge(unread: Int) {
    Box(
        Modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .background(WatchColors.talkaGreen, RoundedCornerShape(9.dp))
            .padding(horizontal = 5.dp)
            .clearAndSetSemantics { contentDescription = "$unread non lus" },
        contentAlignment = Alignment.Center,
    ) {
        Text("$unread", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ----- Une discussion -----

@Composable
fun ConversationScreen(model: WatchModel, chatId: String, record: () -> Unit, react: (WatchMessage) -> Unit) {
    val chat = model.chat(chatId)
    if (chat == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Discussion introuvable", color = WatchColors.secondary)
        }
        return
    }
    val state = rememberScalingLazyListState()
    // L'en-tête, les messages, puis la fin de la discussion (le bas de la réponse) :
    // c'est elle qu'on voit en ouvrant, et à chaque nouveau message.
    val last = chat.messages.lastOrNull()?.id
    val count = 1 + chat.messages.size + (if (chat.readOnly) 1 else composerItems)
    LaunchedEffect(last) {
        state.scrollToItem(count - 1)
        model.markRead(chatId)
    }
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = state) }) {
        ScalingLazyColumn(Modifier.fillMaxSize().testTag("discussion"), state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { ListHeader { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            items(chat.messages, key = { it.id }) { m ->
                val tappable = !m.system && !m.id.startsWith("montre-")
                Bubble(m, if (tappable) Modifier.clickable { react(m) } else Modifier)
            }
            if (chat.readOnly) {
                item {
                    Text(
                        if (chat.group) "Vous ne faites plus partie de ce groupe." else "Contact bloqué.",
                        style = MaterialTheme.typography.caption2,
                        color = WatchColors.secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else {
                composer(model, chatId, record)
            }
        }
    }
}

@Composable
fun Bubble(message: WatchMessage, modifier: Modifier = Modifier) {
    val m = message
    if (m.system) {
        Text(
            m.text,
            style = MaterialTheme.typography.caption3,
            color = WatchColors.secondary,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = if (m.mine) 14.dp else 0.dp, end = if (m.mine) 0.dp else 14.dp),
        horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (m.mine) WatchColors.bubbleOut else WatchColors.bubbleIn)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (m.author.isNotEmpty()) {
                Text(m.author, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WatchColors.talkaGreen)
            }
            Text(m.text, fontSize = 13.sp, color = Color.White)
            Row(
                Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(DateFormat.getTimeFormat(context).format(Date(m.at.toLong())), fontSize = 10.sp, color = WatchColors.secondary)
                if (m.mine) TickMark(m.tick)
            }
        }
        if (m.reactions.isNotEmpty()) {
            Text(
                m.reactions,
                fontSize = 11.sp,
                modifier = Modifier
                    .background(WatchColors.bubbleIn, RoundedCornerShape(50))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
                    .semantics { contentDescription = "Réactions : ${m.reactions}" },
            )
        }
    }
}

@Composable
fun TickMark(tick: String) {
    val (icon, tint, label) = when (tick) {
        "pending" -> Triple(R.drawable.ic_clock, WatchColors.secondary, "En attente")
        "failed" -> Triple(R.drawable.ic_failed, WatchColors.failed, "Non envoyé")
        "delivered" -> Triple(R.drawable.ic_delivered, WatchColors.secondary, "Distribué")
        "read" -> Triple(R.drawable.ic_read, WatchColors.tickBlue, "Lu")
        else -> Triple(R.drawable.ic_sent, WatchColors.secondary, "Envoyé")
    }
    Icon(painterResource(icon), label, Modifier.size(10.dp), tint = tint)
}

/** Les lignes de la réponse : Vocal, Écrire, les emoji, puis les réponses rapides. */
private val composerItems = 3 + watchQuickReplies.size

/** La clé du texte dicté ou tapé (`TextFieldLink`). */
private const val messageKey = "message"

/**
 * Répondre : un message vocal d'abord ; sinon dictée ou clavier, une
 * réponse rapide, ou un emoji.
 */
private fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.composer(
    model: WatchModel,
    chatId: String,
    record: () -> Unit,
) {
    item {
        Chip(
            onClick = record,
            label = { Text("Vocal") },
            icon = { Icon(painterResource(R.drawable.ic_mic), null) },
            colors = ChipDefaults.primaryChipColors(backgroundColor = WatchColors.talkaGreen),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Vocal : enregistre un message vocal" },
        )
    }
    item { WriteChip(model, chatId) }
    item {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (e in watchReactions) {
                Button(
                    onClick = { model.send(chatId, e) },
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(40.dp),
                ) { Text(e, fontSize = 18.sp) }
            }
        }
    }
    items(watchQuickReplies) { r ->
        Chip(
            onClick = { model.send(chatId, r) },
            label = { Text(r) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** « Écrire » : la dictée, le clavier ou l'écriture à la main de la montre. */
@Composable
private fun WriteChip(model: WatchModel, chatId: String) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(messageKey)?.toString()
        if (text != null) model.send(chatId, text)
    }
    Chip(
        onClick = {
            val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(messageKey).setLabel("Message").build()))
            launcher.launch(intent)
        },
        label = { Text("Écrire") },
        icon = { Icon(painterResource(R.drawable.ic_keyboard), null) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ----- Réagir -----

@Composable
fun ReactionPicker(current: String, pick: (String) -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = state) }) {
        ScalingLazyColumn(Modifier.fillMaxSize(), state = state, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ListHeader { Text("Réagir") } }
            for (row in watchReactions.chunked(3)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (e in row) {
                            Button(
                                onClick = { pick(e) },
                                colors = if (e == current) ButtonDefaults.primaryButtonColors() else ButtonDefaults.secondaryButtonColors(),
                                modifier = Modifier.size(ButtonDefaults.DefaultButtonSize).semantics {
                                    contentDescription = if (e == current) "Retirer $e" else e
                                },
                            ) { Text(e, fontSize = 22.sp) }
                        }
                    }
                }
            }
            if (current.isNotEmpty()) {
                item {
                    Text(
                        "Touchez $current pour la retirer",
                        style = MaterialTheme.typography.caption3,
                        color = WatchColors.secondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
