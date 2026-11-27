// L'onglet Actus : des textes sur fond de couleur ou des photos, visibles
// 24 heures par vos contacts. Le portage de `Status.swift`.

package talka.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import talka.app.LocalAppModel
import talka.app.TalkaColors
import talka.kit.Engine
import talka.kit.StatusItem

/** Les fonds des actus en texte : les mêmes que le navigateur et l'iPhone. */
val statusColors = listOf("#008069", "#5e47de", "#c4532d", "#1f7aec", "#a62c71", "#b7820b", "#243b4a")

/** `#rrggbb` (le vert de Talka si illisible). */
fun hexColor(hex: String): Color {
    val v = hex.trim('#').toLongOrNull(16) ?: 0x008069L
    return Color(0xFF000000 or (v and 0xFFFFFF))
}

/** Une actu qui dure 24 heures, comme dans le navigateur. */
private const val statusChars = 700

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatusTab() {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    var writing by remember { mutableStateOf(false) }
    var photoData by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<List<StatusItem>?>(null) }
    val pick = rememberPhotoPicker { bitmap -> photoData = Images.compressed(bitmap, 1280, 0.8f)?.first }

    val all = remember(e.statusRevision, model.revision) { e.statuses() }
    val me = e.me.code
    val mine = all.filter { it.owner == me }
    val others = all.filter { it.owner != me && e.contact(it.owner)?.blocked != true }
        .groupBy { it.owner }
        .toList()
        .sortedByDescending { (_, items) -> items.lastOrNull()?.at ?: 0.0 }
    // Les actus en sourdine : à part, en bas, jamais « Récentes ».
    val hidden = others.filter { e.contact(it.first)?.statusMuted == true }
    val shown = others.filter { e.contact(it.first)?.statusMuted != true }
    val unseen = shown.filter { (_, items) -> items.any { !it.seen } }
    val seen = shown.filter { (_, items) -> items.none { !it.seen } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Actus") },
                actions = {
                    IconButton(onClick = pick) { Icon(Icons.Filled.PhotoCamera, "Publier une photo") }
                    IconButton(onClick = { writing = true }) { Icon(Icons.Filled.Edit, "Publier un texte") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            // Ce que le moteur change (hors de l'état de Compose) : la liste suit `revision`.
            model.revision
            item {
                ListItem(
                    leadingContent = {
                        Box {
                            StatusRing(mine.size, 0) { EngineAvatar(e, me, 50.dp) }
                            if (mine.isEmpty()) {
                                Icon(
                                    Icons.Filled.AddCircle,
                                    null,
                                    Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.background, CircleShape),
                                    tint = TalkaColors.green,
                                )
                            }
                        }
                    },
                    headlineContent = { Text("Mon actu", fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(mine.lastOrNull()?.let { "${Dates.list(it.at)} · ${viewers(mine)} vue(s)" } ?: "Touchez pour publier")
                    },
                    modifier = Modifier.clickable { if (mine.isEmpty()) writing = true else viewing = mine },
                )
            }
            for ((title, list) in listOf("Récentes" to unseen, "Vues" to seen, "Masquées" to hidden)) {
                if (list.isEmpty()) continue
                item(key = "titre-$title") { SectionHeader(title) }
                items(list, key = { "$title-${it.first}" }) { (owner, items) -> StatusRow(e, owner, items) { viewing = items } }
            }
            if (others.isEmpty()) {
                item {
                    Text(
                        "Les actus de vos contacts apparaîtront ici. Elles disparaissent au bout de 24 heures.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
    if (writing) TextStatusComposer(onDismiss = { writing = false })
    photoData?.let { d -> PhotoStatusComposer(d, onDismiss = { photoData = null }) }
    viewing?.let { items -> StatusViewer(items, onDismiss = { viewing = null }) }
}

/** Combien de personnes ont vu ces actus. */
private fun viewers(items: List<StatusItem>): Int = items.flatMap { it.viewers.keys }.toSet().size

/** Les actus d'un contact dans la liste ; un appui long les masque ou les affiche. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusRow(e: Engine, owner: String, items: List<StatusItem>, open: () -> Unit) {
    val muted = e.contact(owner)?.statusMuted == true
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            leadingContent = { StatusRing(items.size, if (muted) 0 else items.count { !it.seen }) { EngineAvatar(e, owner, 50.dp) } },
            headlineContent = { Text(e.displayName(owner), fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(Dates.list(items.lastOrNull()?.at ?: 0.0)) },
            modifier = Modifier.alpha(if (muted) 0.6f else 1f).combinedClickable(onLongClick = { menu = true }, onClick = open),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (muted) "Afficher ses actus" else "Masquer ses actus") },
                leadingIcon = { Icon(if (muted) Icons.Filled.Notifications else Icons.Filled.NotificationsOff, null) },
                onClick = {
                    menu = false
                    e.updateContact(owner) { it.copy(statusMuted = !it.statusMuted) }
                },
            )
        }
    }
}

/** L'anneau autour de l'avatar : vert pour les actus non vues. */
@Composable
fun StatusRing(count: Int, unseen: Int, content: @Composable () -> Unit) {
    val ring = if (unseen > 0) TalkaColors.accent else Color(0xFFC7C7CC)
    Box(
        Modifier
            .then(if (count > 0) Modifier.border(2.5.dp, ring, CircleShape) else Modifier)
            .padding(3.dp),
    ) { content() }
}

/** Un plein écran (les actus se montrent et s'écrivent bord à bord). */
@Composable
private fun FullScreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        content()
    }
}

/** Écrire une actu : du texte, sur l'une des couleurs. */
@Composable
fun TextStatusComposer(onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    var text by remember { mutableStateOf("") }
    var color by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    FullScreen(onDismiss) {
        Surface(Modifier.fillMaxSize(), color = hexColor(statusColors[color]), contentColor = Color.White) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                Box(Modifier.fillMaxSize().padding(30.dp), contentAlignment = Alignment.Center) {
                    val style = TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = style,
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Écrivez une actu" },
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.Center) {
                                if (text.isEmpty()) Text("Écrivez une actu", style = style.copy(color = Color.White.copy(alpha = 0.6f)))
                                inner()
                            }
                        },
                    )
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Fermer") }
                    IconButton(onClick = { color = (color + 1) % statusColors.size }) { Icon(Icons.Filled.Palette, "Changer la couleur") }
                }
                IconButton(
                    onClick = {
                        val t = text.trim()
                        if (t.isEmpty()) return@IconButton
                        model.engine?.postStatus("text", t.firstChars(statusChars), statusColors[color], "")
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp).background(TalkaColors.accent, CircleShape),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Publier", tint = Color.White) }
            }
        }
    }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (x: IllegalStateException) {
        }
    }
}

/** Publier une photo, avec sa légende. */
@Composable
fun PhotoStatusComposer(data: String, onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    var text by remember { mutableStateOf("") }
    Sheet(
        "Nouvelle actu",
        onDismiss,
        actions = {
            TextButton(onClick = {
                model.engine?.postStatus("image", text.firstChars(statusChars), "", data)
                onDismiss()
            }) { Text("Publier") }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Images.fromDataURL(data)?.let {
                Image(it, "Photo", Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.FillWidth)
            }
            OutlinedTextField(text, { text = it }, placeholder = { Text("Légende") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Les actus d'une personne, l'une après l'autre (5 secondes chacune). */
@Composable
fun StatusViewer(items: List<StatusItem>, onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    val e = model.engine ?: return onDismiss()
    var index by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var showViewers by remember { mutableStateOf(false) }

    fun go(step: Int) {
        val next = index + step
        if (next < 0) {
            progress = 0f
            return
        }
        if (next >= items.size) return onDismiss()
        index = next
    }

    if (index >= items.size) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val st = items[index]
    LaunchedEffect(index) {
        e.markStatusSeen(st)
        progress = 0f
        while (progress < 1f) {
            delay(50)
            if (!paused && !showViewers) progress += 0.01f
        }
        go(1)
    }
    FullScreen(onDismiss) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (st.kind == "text") hexColor(st.bg.ifEmpty { statusColors[0] }) else Color.Black),
        ) {
            if (st.kind == "image") {
                Images.fromDataURL(st.data)?.let { Image(it, "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
            if (st.kind == "text") {
                Text(
                    st.text,
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(30.dp),
                )
            } else if (st.text.isNotEmpty()) {
                Text(
                    st.text,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(16.dp),
                )
            }
            // Toucher à gauche : la précédente ; à droite : la suivante ; garder le doigt : pause.
            Row(Modifier.fillMaxSize()) {
                for (step in listOf(-1, 1)) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight().pointerInput(step) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val start = down.uptimeMillis
                                paused = true
                                val up = waitForUpOrCancellation()
                                paused = false
                                if (up != null && up.uptimeMillis - start < 200) go(step)
                            }
                        },
                    )
                }
            }
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items.indices.forEach { i ->
                        val part = if (i < index) 1f else if (i == index) progress.coerceIn(0f, 1f) else 0f
                        Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f))) {
                            Box(Modifier.fillMaxWidth(part).height(3.dp).background(Color.White))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EngineAvatar(e, st.owner, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(if (st.owner == e.me.code) "Mon actu" else e.displayName(st.owner), color = Color.White, fontWeight = FontWeight.Bold)
                        Text(Dates.list(st.at), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                    if (st.owner == e.me.code) {
                        IconButton(onClick = {
                            e.deleteStatus(st.id)
                            if (items.size == 1) onDismiss() else go(1)
                        }) { Icon(Icons.Filled.Delete, "Supprimer", tint = Color.White) }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Fermer", tint = Color.White) }
                }
                Spacer(Modifier.weight(1f))
                if (st.owner == e.me.code) {
                    TextButton(
                        onClick = { showViewers = true },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape).semantics { contentDescription = "Vue par ${st.viewers.size}" },
                    ) {
                        Icon(Icons.Filled.Visibility, null, tint = Color.White)
                        Text(" ${st.viewers.size}", color = Color.White)
                    }
                }
            }
        }
    }
    if (showViewers) {
        Sheet("Vue par", onDismiss = { showViewers = false }) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                if (st.viewers.isEmpty()) item { EmptyState("Pas encore de vues", icon = Icons.Filled.VisibilityOff) }
                items(st.viewers.entries.sortedByDescending { it.value }, key = { it.key }) { (code, at) ->
                    ListItem(
                        leadingContent = { EngineAvatar(e, code, 36.dp) },
                        headlineContent = { Text(e.displayName(code)) },
                        trailingContent = { Text(Dates.list(at), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}
