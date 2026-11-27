// L'onglet Discussions : la liste, la recherche, et les feuilles pour
// ajouter un contact (code, lien d'invitation), créer un groupe, montrer
// son code, les messages importants.

package talka.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import talka.app.LocalAppModel
import talka.app.LocalWide
import talka.app.TalkaColors
import talka.kit.Chat
import talka.kit.Engine
import talka.kit.Message
import talka.kit.Relay
import talka.kit.TalkaCode
import java.text.Collator
import java.util.Locale

/** Les filtres de la liste. */
enum class ChatFilter(val label: String) { all("Toutes"), unread("Non lues"), groups("Groupes"), archived("Archivées") }

/** Les feuilles de l'onglet. */
sealed class ChatSheet {
    data object NewChat : ChatSheet()

    data class Add(val code: String) : ChatSheet()

    data object Group : ChatSheet()

    data object MyCode : ChatSheet()

    data object Starred : ChatSheet()
}

/** Les discussions affichées : filtrées, cherchées, épinglées d'abord, puis les plus récentes. */
fun visibleChats(e: Engine, filter: ChatFilter, query: String): List<Chat> {
    val q = query.trimSpaces()
    val code = q.uppercase().replace("-", "")
    return e.chats.values
        .filter { c ->
            when (filter) {
                ChatFilter.all -> !c.archived
                ChatFilter.unread -> !c.archived && (c.unread > 0 || c.markedUnread)
                ChatFilter.groups -> !c.archived && c.group
                ChatFilter.archived -> c.archived
            }
        }
        .filter { c ->
            q.isEmpty() || e.title(c).contains(q, ignoreCase = true) || c.id.contains(code) ||
                (c.last?.text?.contains(q, ignoreCase = true) ?: false)
        }
        .sortedWith { a, b ->
            if (a.pinned != b.pinned) {
                if (a.pinned) -1 else 1
            } else {
                maxOf(b.lastAt, b.last?.at ?: 0.0).compareTo(maxOf(a.lastAt, a.last?.at ?: 0.0))
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsTab() {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ChatFilter.all) }
    var sheet by remember { mutableStateOf<ChatSheet?>(null) }
    var more by remember { mutableStateOf(false) }
    val wide = LocalWide.current

    // Un lien d'invitation : la feuille « Ajouter un contact », son code rempli.
    LaunchedEffect(model.invite) {
        val code = model.invite ?: return@LaunchedEffect
        model.invite = null
        sheet = ChatSheet.Add(code)
    }

    val list: @Composable (Modifier) -> Unit = { modifier ->
        Scaffold(
            modifier = modifier,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text("Discussions") },
                    actions = {
                        Box {
                            IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Plus") }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(text = { Text("Mon code") }, leadingIcon = { Icon(Icons.Filled.QrCode, null) }, onClick = {
                                    more = false
                                    sheet = ChatSheet.MyCode
                                })
                                DropdownMenuItem(text = { Text("Nouveau groupe") }, leadingIcon = { Icon(Icons.Filled.GroupAdd, null) }, onClick = {
                                    more = false
                                    sheet = ChatSheet.Group
                                })
                                DropdownMenuItem(text = { Text("Messages importants") }, leadingIcon = { Icon(Icons.Filled.Star, null) }, onClick = {
                                    more = false
                                    sheet = ChatSheet.Starred
                                })
                                if (e.chats.values.any { it.unread > 0 || it.markedUnread }) {
                                    DropdownMenuItem(
                                        text = { Text("Tout marquer comme lu") },
                                        leadingIcon = { Icon(Icons.Filled.MarkChatRead, null) },
                                        onClick = {
                                            more = false
                                            val n = e.markAllRead()
                                            Notifier.cancelAll()
                                            model.show(if (n > 1) "$n discussions marquées comme lues" else "Discussion marquée comme lue")
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { sheet = ChatSheet.NewChat }, containerColor = TalkaColors.green, contentColor = Color.White) {
                    Icon(Icons.Filled.Edit, contentDescription = "Nouvelle discussion")
                }
            },
        ) { padding ->
            ChatList(e, query, { query = it }, filter, { filter = it }, Modifier.padding(padding), onNewChat = { sheet = ChatSheet.NewChat })
        }
    }

    if (wide) {
        // Une fenêtre large : la liste à gauche, la discussion à droite.
        Row(Modifier.fillMaxSize()) {
            list(Modifier.width(380.dp).fillMaxHeight())
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val open = model.path.lastOrNull()
                if (open != null) {
                    key(open) { ConversationView(open) }
                } else {
                    EmptyState(
                        "Talka",
                        "Choisissez une discussion. Vos messages sont chiffrés de bout en bout.",
                        Icons.Filled.Forum,
                        Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    } else {
        list(Modifier.fillMaxSize())
    }

    when (val s = sheet) {
        ChatSheet.NewChat -> NewChatSheet(onSheet = { sheet = it })
        is ChatSheet.Add -> AddContactSheet(s.code, onDismiss = { sheet = null })
        ChatSheet.Group -> NewGroupSheet(onDismiss = { sheet = null })
        ChatSheet.MyCode -> MyCodeSheet(onDismiss = { sheet = null })
        ChatSheet.Starred -> StarredSheet(onDismiss = { sheet = null })
        null -> {}
    }
}

/** Les lignes de la liste : l'état du réseau, la recherche, le filtre, les discussions, les messages trouvés. */
@Composable
fun ChatList(
    e: Engine,
    query: String,
    onQuery: (String) -> Unit,
    filter: ChatFilter,
    onFilter: (ChatFilter) -> Unit,
    modifier: Modifier = Modifier,
    onNewChat: () -> Unit,
) {
    val model = LocalAppModel.current
    model.revision
    val chats = visibleChats(e, filter, query)
    // La recherche trouve aussi les messages de toutes les discussions (comme le navigateur).
    val found = e.searchMessages(query)
    var muting by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val selected = if (LocalWide.current) model.path.lastOrNull() else null

    LazyColumn(modifier.fillMaxSize()) {
        if (e.netState != Relay.State.online) {
            item(key = "réseau") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.WifiOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (e.netState == Relay.State.connecting) "Connexion…" else "Hors ligne : les messages partiront au retour du réseau.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "recherche") {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Rechercher") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item(key = "filtre") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (f in ChatFilter.entries) {
                    FilterChip(selected = filter == f, onClick = { onFilter(f) }, label = { Text(f.label) })
                }
            }
        }
        items(chats, key = { "c:" + it.id }) { chat ->
            ChatRowWithActions(
                e,
                chat,
                selected = chat.id == selected,
                onOpen = { model.path = listOf(chat.id) },
                onMute = { muting = chat.id },
                onClear = { clearing = chat.id to e.hasStarred(chat.id) },
            )
        }
        if (found.isNotEmpty()) {
            item(key = "messages") { SectionHeader("Messages") }
            items(found, key = { "m:" + it.key }) { m ->
                FoundRow(
                    e,
                    m,
                    Modifier.clickable {
                        model.reveal = m.id
                        model.openChat(m.chat)
                    },
                )
            }
        }
        if (chats.isEmpty() && found.isEmpty()) {
            item(key = "vide") {
                val title = when {
                    query.isNotEmpty() -> "Aucun résultat"
                    filter == ChatFilter.all -> "Aucune discussion"
                    filter == ChatFilter.unread -> "Aucune discussion non lue"
                    filter == ChatFilter.groups -> "Aucun groupe"
                    else -> "Aucune discussion archivée"
                }
                val hint = when {
                    query.isNotEmpty() -> ""
                    filter == ChatFilter.all -> "Ajoutez un contact avec son code Talka pour commencer."
                    filter == ChatFilter.unread -> "Tout est lu."
                    filter == ChatFilter.groups -> "Créez-en un depuis « Nouvelle discussion »."
                    else -> "Touchez longuement une discussion pour l'archiver."
                }
                EmptyState(title, hint, Icons.Filled.Forum, Modifier.padding(top = 40.dp)) {
                    if (query.isEmpty() && filter == ChatFilter.all) {
                        Button(onClick = onNewChat) { Text("Nouvelle discussion") }
                    }
                }
            }
        }
    }
    muting?.let { id -> MuteChoicesDialog(e, id, muted = false, onDismiss = { muting = null }) }
    clearing?.let { (id, keepable) -> ClearChatDialog(e, id, keepable, onDismiss = { clearing = null }) }
}

/**
 * Une discussion de la liste et ses actions : un toucher l'ouvre, un
 * toucher long montre ce que l'iPhone met sous le balayage (lu / non lu,
 * épingler, archiver, sourdine, vider, supprimer).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRowWithActions(e: Engine, chat: Chat, selected: Boolean, onOpen: () -> Unit, onMute: () -> Unit, onClear: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val unread = chat.unread > 0 || chat.markedUnread
    Box {
        ChatRow(
            e,
            chat,
            Modifier
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = "Actions"),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (unread) "Lu" else "Non lu") },
                leadingIcon = { Icon(if (unread) Icons.Filled.MarkChatRead else Icons.Filled.MarkChatUnread, null) },
                onClick = {
                    menu = false
                    e.updateChat(chat.id) {
                        if (it.unread > 0 || it.markedUnread) it.copy(unread = 0, markedUnread = false, mentioned = false) else it.copy(markedUnread = true)
                    }
                    if (chat.unread > 0) e.markRead(chat.id)
                },
            )
            DropdownMenuItem(
                text = { Text(if (chat.pinned) "Désépingler" else "Épingler") },
                leadingIcon = { Icon(if (chat.pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin, null) },
                onClick = {
                    menu = false
                    e.updateChat(chat.id) { it.copy(pinned = !it.pinned) }
                },
            )
            DropdownMenuItem(
                text = { Text(if (chat.archived) "Désarchiver" else "Archiver") },
                leadingIcon = { Icon(if (chat.archived) Icons.Filled.Unarchive else Icons.Filled.Archive, null) },
                onClick = {
                    menu = false
                    e.updateChat(chat.id) { it.copy(archived = !it.archived) }
                },
            )
            DropdownMenuItem(
                text = { Text(if (chat.muted) "Son" else "Silence") },
                leadingIcon = { Icon(if (chat.muted) Icons.Filled.Notifications else Icons.Filled.NotificationsOff, null) },
                onClick = {
                    menu = false
                    if (chat.muted) e.mute(chat.id, 0.0) else onMute()
                },
            )
            DropdownMenuItem(
                text = { Text("Vider la discussion") },
                leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) },
                onClick = {
                    menu = false
                    onClear()
                },
            )
            DropdownMenuItem(
                text = { Text("Supprimer", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    menu = false
                    e.deleteChat(chat.id)
                },
            )
        }
    }
}

/** Un message trouvé par la recherche : sa discussion, sa date, le passage. */
@Composable
fun FoundRow(e: Engine, m: Message, modifier: Modifier = Modifier) {
    LocalAppModel.current.revision
    val who = when {
        m.from == e.me.code -> "Vous : "
        e.isGroup(m.chat) -> "${e.displayName(m.from)} : "
        else -> ""
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EngineAvatar(e, m.chat, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text(
                    e.chat(m.chat)?.let { e.title(it) } ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(Dates.list(m.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                who + e.readable(m),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ChatRow(e: Engine, chat: Chat, modifier: Modifier = Modifier) {
    LocalAppModel.current.revision
    val typing = e.typing[chat.id] ?: emptySet()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            PresenceAvatar(e, chat.id, 52.dp)
            if (!chat.group && e.presence[chat.id]?.online == true) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(14.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                        .padding(2.dp)
                        .background(TalkaColors.accent, CircleShape),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.title(chat),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                chat.last?.let { last ->
                    Text(
                        Dates.list(last.at),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chat.unread > 0) TalkaColors.accent else secondary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val style = MaterialTheme.typography.bodyMedium
                val last = chat.last
                Box(Modifier.weight(1f)) {
                    when {
                        typing.isNotEmpty() -> Text(
                            if (chat.group) "${typing.joinToString(", ") { e.displayName(it) }} écrit…" else "écrit…",
                            color = TalkaColors.green,
                            style = style,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        chat.draft.isNotEmpty() -> Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Color.Red)) { append("Brouillon : ") }
                                append(chat.draft)
                            },
                            color = secondary,
                            style = style,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        last != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (last.from == e.me.code && last.kind != "system") {
                                e.store.message(chat.id, last.id)?.let { TickView(it.tick) }
                            }
                            // Dans un groupe, qui l'a écrit (« Vous » compris), comme le navigateur.
                            val text = if (chat.group && last.from != "system" && last.from.isNotEmpty()) {
                                "${if (last.from == e.me.code) "Vous" else e.displayName(last.from)} : ${last.text}"
                            } else {
                                last.text
                            }
                            Text(text, color = secondary, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        else -> Text(if (chat.group) "Groupe créé" else "Nouvelle discussion", color = secondary, style = style, maxLines = 1)
                    }
                }
                if (chat.timer > 0) Text("⏳", style = MaterialTheme.typography.labelSmall, modifier = Modifier.semantics { contentDescription = "Messages éphémères" })
                if (chat.muted) Icon(Icons.Filled.NotificationsOff, "En sourdine", Modifier.size(14.dp), tint = secondary)
                if (chat.pinned) Icon(Icons.Filled.PushPin, "Épinglée", Modifier.size(14.dp), tint = secondary)
                if (chat.mentioned) {
                    // On nous a mentionné : « @ », même en sourdine.
                    Box(
                        Modifier.defaultMinSize(20.dp, 20.dp).background(TalkaColors.accent, CircleShape).semantics { contentDescription = "Vous êtes mentionné" },
                        contentAlignment = Alignment.Center,
                    ) { Text("@", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                }
                if (chat.unread > 0 || chat.markedUnread) {
                    Box(
                        Modifier
                            .defaultMinSize(20.dp, 20.dp)
                            .background(if (chat.muted) Color.Gray else TalkaColors.accent, CircleShape)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (chat.unread > 0) "${chat.unread}" else " ",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/** ✓, ✓✓, ✓✓ bleus, horloge, erreur. */
@Composable
fun TickView(tick: Message.Tick) {
    val m = Modifier.size(16.dp)
    when (tick) {
        Message.Tick.pending -> Icon(Icons.Outlined.Schedule, "En attente", m)
        Message.Tick.sent -> Icon(Icons.Filled.Done, "Envoyé", m)
        Message.Tick.delivered -> Icon(Icons.Filled.DoneAll, "Distribué", m)
        Message.Tick.read -> Icon(Icons.Filled.DoneAll, "Lu", m, tint = TalkaColors.tickBlue)
        Message.Tick.failed -> Icon(Icons.Filled.Error, "Échec", m, tint = Color.Red)
    }
}

// ----- Nouvelle discussion -----

@Composable
fun NewChatSheet(onSheet: (ChatSheet?) -> Unit) {
    val model = LocalAppModel.current
    ContactPicker(
        title = "Nouvelle discussion",
        onDismiss = { onSheet(null) },
        header = {
            Column {
                ListItem(
                    headlineContent = { Text("Ajouter un contact par son code") },
                    leadingContent = { Icon(Icons.Filled.PersonAdd, null) },
                    modifier = Modifier.clickable { onSheet(ChatSheet.Add("")) },
                )
                ListItem(
                    headlineContent = { Text("Nouveau groupe") },
                    leadingContent = { Icon(Icons.Filled.GroupAdd, null) },
                    modifier = Modifier.clickable { onSheet(ChatSheet.Group) },
                )
                ListItem(
                    headlineContent = { Text("Mon code et mon QR code") },
                    leadingContent = { Icon(Icons.Filled.QrCode, null) },
                    modifier = Modifier.clickable { onSheet(ChatSheet.MyCode) },
                )
            }
        },
        pick = { code ->
            onSheet(null)
            model.openChat(code)
        },
    )
}

@Composable
fun AddContactSheet(initial: String, onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(if (initial.isEmpty()) "" else TalkaCode.pretty(initial)) }
    var name by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun add() {
        val c = TalkaCode.normalize(code) ?: return
        val e = model.engine ?: return
        busy = true
        error = null
        scope.launch {
            try {
                val reachable = e.addContact(c, name)
                busy = false
                onDismiss()
                model.openChat(c)
                if (!reachable) model.show("Contact ajouté. Ses clés arriveront à sa prochaine connexion.")
            } catch (x: CancellationException) {
                throw x
            } catch (x: Exception) {
                busy = false
                error = x.message ?: "Le contact n'a pas pu être ajouté."
            }
        }
    }

    Sheet(
        "Ajouter un contact",
        onDismiss,
        actions = {
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { add() }, enabled = TalkaCode.normalize(code) != null) { Text("Ajouter") }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Code (ex. K7Q2M-X9PRT)") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Code" },
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom (facultatif)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { scanning = true }) {
                Icon(Icons.Filled.QrCodeScanner, null)
                Text("Scanner un QR code", Modifier.padding(start = 8.dp))
            }
            Text(
                "Demandez son code Talka à votre contact, ou scannez son QR code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (scanning) {
        QRScanner(
            onFound = { text ->
                scanning = false
                val c = TalkaCode.find(text)
                if (c != null) {
                    TalkaCode.fingerprint(text)?.let { model.engine?.expectFingerprint(c, it) }
                    code = TalkaCode.pretty(c)
                } else {
                    error = "Ce QR code n'est pas un code Talka."
                }
            },
            onDismiss = { scanning = false },
        )
    }
}

// ----- Groupe -----

@Composable
fun NewGroupSheet(onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    var name by remember { mutableStateOf("") }
    val members = remember { mutableStateListOf<String>() }
    var avatar by remember { mutableStateOf("") }
    val pick = rememberPhotoPicker { bitmap -> avatar = Images.avatarDataURL(bitmap) ?: "" }
    val collator = remember { Collator.getInstance(Locale.FRANCE).apply { strength = Collator.PRIMARY } }
    val contacts = e.contacts.values.filter { it.saved && !it.blocked }
        .sortedWith { a, b -> collator.compare(e.displayName(a.code), e.displayName(b.code)) }
    Sheet(
        "Nouveau groupe",
        onDismiss,
        actions = {
            TextButton(
                onClick = {
                    val g = e.createGroup(name.trimSpaces().firstChars(60), members.toList(), avatar)
                    onDismiss()
                    model.openChat(g.id)
                },
                enabled = name.trimSpaces().isNotEmpty() && members.isNotEmpty(),
            ) { Text("Créer") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar("group", name, avatar, group = true, size = 56.dp, modifier = Modifier.clickable { pick() })
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nom du groupe") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item { SectionHeader("Membres (${members.size})") }
            if (contacts.isEmpty()) {
                item { Text("Ajoutez d'abord des contacts.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
            items(contacts, key = { it.code }) { c ->
                val chosen = c.code in members
                ListItem(
                    leadingContent = { EngineAvatar(e, c.code, 36.dp) },
                    headlineContent = { Text(e.displayName(c.code)) },
                    trailingContent = {
                        Icon(
                            if (chosen) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                            if (chosen) "Choisi" else null,
                            tint = if (chosen) TalkaColors.green else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable { if (chosen) members.remove(c.code) else members.add(c.code) },
                )
            }
        }
    }
}

// ----- Mon code -----

@Composable
fun MyCodeSheet(onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    val context = LocalContext.current
    val link = model.inviteLink(e.me.code)
    Sheet("Mon code", onDismiss, actions = { TextButton(onClick = onDismiss) { Text("OK") } }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            EngineAvatar(e, e.me.code, 72.dp)
            Text(e.profile.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            QrImage(link, Modifier.widthIn(max = 240.dp).fillMaxWidth())
            CodeBadge(e.me.code)
            Text(
                "Vos contacts scannent ce QR code ou saisissent votre code pour vous écrire.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        copyText(context, TalkaCode.pretty(e.me.code))
                        model.show("Code copié")
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copier")
                }
                Button(
                    onClick = { shareText(context, "Écris-moi sur Talka ! Mon code : ${TalkaCode.pretty(e.me.code)}\n$link") },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Partager")
                }
            }
        }
    }
}

// ----- Messages importants -----

/** Les messages importants ⭐ : ceux d'une seule discussion (depuis ses infos), sinon tous. */
@Composable
fun StarredSheet(chatId: String? = null, onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    // Chercher parmi eux (accents et majuscules ignorés), comme le navigateur et WhatsApp.
    var query by remember { mutableStateOf("") }
    val all = if (chatId != null) e.starred(chatId) else e.starred()
    val list = e.starred(all, query)
    Sheet("Messages importants", onDismiss, actions = { TextButton(onClick = onDismiss) { Text("OK") } }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Rechercher") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (all.isEmpty()) {
                item { EmptyState("Aucun message important", icon = Icons.Filled.Star) }
            } else if (list.isEmpty()) {
                item { EmptyState("Aucun résultat pour « $query »", "Vérifiez l'orthographe ou lancez une nouvelle recherche.", Icons.Filled.Search) }
            }
            items(list, key = { it.key }) { m ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            // La discussion s'ouvre sur le message, comme WhatsApp.
                            model.reveal = m.id
                            model.openChat(m.chat)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(e.displayName(m.from), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        e.chat(m.chat)?.takeIf { it.group }?.let {
                            Text("· ${e.title(it)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(Dates.list(m.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(e.readable(m), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
