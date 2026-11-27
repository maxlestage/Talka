// L'écran d'appel (plein écran) et l'onglet Appels (le journal) : le
// portage de `Calls.swift`. Et les choix de contacts : un (`ContactPicker`,
// pour une discussion, un appel, un transfert, un partage de contact), ou
// plusieurs (`MultiContactPicker`, pour un appel de groupe).

package talka.app.ui

import android.app.KeyguardManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import talka.app.LocalAppModel
import talka.app.TalkaColors
import talka.app.call.CallEngine
import talka.app.call.VideoView
import talka.app.launch
import talka.kit.CallEntry
import talka.kit.Engine
import talka.kit.TalkaCode
import talka.kit.formatDuration
import java.text.Collator
import java.util.Locale

// ----- L'écran d'appel -----

/** Le fond de l'écran d'appel (celui de l'iPhone). */
private val callBackground = Color(0xFF0B141A)
private val glow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f))

/** L'appel en cours, en plein écran (par-dessus tout le reste). */
@Composable
fun CallScreen(call: CallEngine) {
    val model = LocalAppModel.current
    val e = call.engine
    var adding by remember { mutableStateOf(false) }
    // L'écran reste allumé pendant l'appel (`isIdleTimerDisabled`).
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // Le retour d'Android ne cache pas l'appel (comme le plein écran de l'iPhone).
    BackHandler {}
    val live = call.phase == CallEngine.Phase.live
    Box(Modifier.fillMaxSize().background(callBackground).clickable(enabled = true, onClick = {})) {
        if (call.video && live && call.remoteVideos.isNotEmpty()) RemoteGrid(call)
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp).padding(bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!(call.video && live)) {
                Spacer(Modifier.height(40.dp))
                when {
                    !call.isGroup -> EngineAvatar(e, call.first, 120.dp)
                    call.chat.isNotEmpty() && e.chat(call.chat) != null -> EngineAvatar(e, call.chat, 120.dp)
                    else -> Avatar(call.id, "", group = true, size = 120.dp)
                }
            }
            Text(
                call.title,
                style = MaterialTheme.typography.headlineMedium.merge(glow),
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            if (call.people.isNotEmpty()) {
                Text(
                    call.people,
                    style = MaterialTheme.typography.bodyMedium.merge(glow),
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
            }
            val start = call.connectedAt
            Text(
                if (start != null && live) elapsed(start) else call.status,
                style = MaterialTheme.typography.titleMedium.merge(glow),
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.8f),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.Lock, null, Modifier.size(12.dp), tint = Color.White.copy(alpha = 0.6f))
                Text("Chiffré de bout en bout", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
            Spacer(Modifier.weight(1f))
            CallControls(call)
        }
        Row(Modifier.fillMaxWidth().safeDrawingPadding(), verticalAlignment = Alignment.Top) {
            // Faire sonner quelqu'un de plus (huit personnes au plus) ; pas
            // par-dessus l'écran verrouillé (les contacts ne s'y montrent pas).
            val context = LocalContext.current
            val lockedDevice = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            if (call.phase != CallEngine.Phase.ringing && call.phase != CallEngine.Phase.ended && call.mesh.joined && !lockedDevice) {
                IconButton(
                    onClick = { adding = true },
                    modifier = Modifier.padding(16.dp).size(48.dp).background(Color.White.copy(alpha = 0.2f), CircleShape),
                ) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = "Ajouter quelqu'un", tint = Color.White)
                }
            }
            Spacer(Modifier.weight(1f))
            val local = call.localVideo
            if (local != null) {
                VideoView(
                    local,
                    mirror = call.frontCamera,
                    overlay = true,
                    modifier = Modifier
                        .padding(16.dp)
                        .width(110.dp)
                        .height(160.dp)
                        .shadow(6.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
    if (adding) {
        MultiContactPicker(
            title = "Ajouter à l'appel",
            action = "Appeler",
            exclude = (call.participants + e.me.code).toSet(),
            onDismiss = { adding = false },
        ) { codes ->
            adding = false
            model.launch { call.add(codes) }
        }
    }
}

/** La durée de l'appel, à la seconde. */
@Composable
private fun elapsed(start: Long): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(start) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    return formatDuration((now - start) / 1000.0)
}

/** L'image de chacun : plein écran à deux, une grille à plusieurs. */
@Composable
private fun RemoteGrid(call: CallEngine) {
    val e = call.engine
    val peers = call.participants.filter { call.remoteVideos[it] != null }
    val columns = if (peers.size <= 1) 1 else if (peers.size <= 4) 2 else 3
    val rows = maxOf(1, (peers.size + columns - 1) / columns)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = (maxHeight - 2.dp * (rows - 1)) / rows
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (line in peers.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (p in line) {
                        val track = call.remoteVideos[p] ?: continue
                        Box(Modifier.weight(1f).height(height).clip(RoundedCornerShape(0.dp))) {
                            VideoView(track, mirror = false, modifier = Modifier.fillMaxSize())
                            if (call.isGroup) {
                                Text(
                                    e.displayName(p),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(8.dp)
                                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                    // Une ligne incomplète garde des cases de la même largeur.
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CallControls(call: CallEngine) {
    val model = LocalAppModel.current
    val translucent = Color.White.copy(alpha = 0.2f)
    when (call.phase) {
        CallEngine.Phase.ringing -> {
            Row(horizontalArrangement = Arrangement.spacedBy(70.dp)) {
                RoundButton(Icons.Filled.CallEnd, "Refuser", Color(0xFFFF3B30)) { call.hangup() }
                RoundButton(if (call.video) Icons.Filled.Videocam else Icons.Filled.Call, "Répondre", TalkaColors.accent) {
                    model.launch { call.accept() }
                }
            }
        }
        CallEngine.Phase.ended -> {}
        else -> {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RoundButton(
                    if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    if (call.muted) "Activer le micro" else "Couper le micro",
                    if (call.muted) Color.White else translucent,
                    dark = call.muted,
                ) { call.toggleMute() }
                if (call.video) {
                    RoundButton(Icons.Filled.Cameraswitch, "Changer de caméra", translucent) { call.switchCamera() }
                    RoundButton(
                        if (call.camera && !call.cameraOn) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                        "Caméra",
                        translucent,
                    ) { call.toggleCamera() }
                }
                RoundButton(
                    if (call.speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                    "Haut-parleur",
                    if (call.speaker) Color.White else translucent,
                    dark = call.speaker,
                ) { call.toggleSpeaker() }
                RoundButton(Icons.Filled.CallEnd, "Raccrocher", Color(0xFFFF3B30), modifier = Modifier.testTag("hangup")) {
                    call.hangup()
                }
            }
        }
    }
}

/** Un bouton rond de l'appel (62 points, comme l'iPhone) ; `dark` : l'icône en noir, sur un fond clair. */
@Composable
fun RoundButton(icon: ImageVector, label: String, color: Color, dark: Boolean = false, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(
        modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(28.dp), tint = if (dark) Color.Black else Color.White)
    }
}

// ----- L'onglet Appels -----

/** L'onglet Appels : le journal (rappeler), et un nouvel appel à un ou plusieurs contacts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsTab() {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    e.callRevision
    var picking by remember { mutableStateOf(false) }
    val log = e.callLog()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Appels") },
                navigationIcon = {
                    if (log.isNotEmpty()) TextButton(onClick = { e.clearCalls() }) { Text("Effacer") }
                },
                actions = {
                    IconButton(onClick = { picking = true }) { Icon(Icons.Filled.AddIcCall, contentDescription = "Nouvel appel") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (log.isEmpty()) {
                item {
                    EmptyState(
                        "Aucun appel",
                        "Les appels audio et vidéo sont chiffrés de bout en bout, de pair à pair.",
                        Icons.Filled.Call,
                    )
                }
            }
            items(log, key = { it.id }) { entry -> CallRow(e, entry) }
        }
    }
    if (picking) {
        // Un contact, ou plusieurs : un appel de groupe.
        MultiContactPicker(title = "Nouvel appel", action = "Appeler", exclude = emptySet(), onDismiss = { picking = false }) { codes ->
            picking = false
            model.launch {
                if (codes.size == 1) model.startCall(codes[0], video = false) else model.startGroupCall(codes, video = false, chat = "")
            }
        }
    }
}

@Composable
private fun CallRow(e: Engine, entry: CallEntry) {
    val model = LocalAppModel.current
    val red = Color(0xFFFF3B30)
    ListItem(
        leadingContent = { EngineAvatar(e, if (entry.members.size > 1 && e.chat(entry.chat) != null) entry.chat else entry.peer, 44.dp) },
        headlineContent = {
            Text(
                callTitle(e, entry),
                fontWeight = FontWeight.Medium,
                color = if (entry.missed) red else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    if (entry.incoming) Icons.AutoMirrored.Filled.CallReceived else Icons.AutoMirrored.Filled.CallMade,
                    null,
                    Modifier.size(14.dp),
                    tint = if (entry.missed) red else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    describeCall(entry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.missed) red else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Dates.list(entry.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { model.launch { model.callAgain(entry) } }) {
                    Icon(
                        if (entry.video) Icons.Filled.Videocam else Icons.Filled.Call,
                        contentDescription = "Rappeler",
                        tint = TalkaColors.green,
                    )
                }
            }
        },
    )
}

/** Le nom d'une ligne : l'autre, le groupe, ou les participants. */
fun callTitle(e: Engine, entry: CallEntry): String {
    if (entry.members.size <= 1) return e.displayName(entry.peer)
    e.chat(entry.chat)?.let { return e.title(it) }
    return CallEngine.names(entry.members.filter { it != e.me.code }.map { e.displayName(it) })
}

/** « Audio · 2:05 », « Vidéo de groupe · manqué », « Audio · sans réponse ». */
fun describeCall(e: CallEntry): String {
    val group = e.members.size > 1
    val kind = if (group) (if (e.video) "Vidéo de groupe" else "Groupe") else (if (e.video) "Vidéo" else "Audio")
    if (e.missed) return "$kind · manqué"
    if (e.dur > 0) return "$kind · ${formatDuration(e.dur)}"
    return "$kind · ${if (e.incoming) "entrant" else "sans réponse"}"
}

// ----- Choisir des contacts -----

/** Choisir un contact (une discussion, un appel, un transfert, un partage de contact). */
@Composable
fun ContactPicker(title: String, onDismiss: () -> Unit, header: @Composable () -> Unit = {}, pick: (String) -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    var query by remember { mutableStateOf("") }
    val collator = remember { Collator.getInstance(Locale.FRANCE).apply { strength = Collator.PRIMARY } }
    val list = e.contacts.values
        .filter { it.saved && !it.blocked }
        .filter { query.isEmpty() || e.displayName(it.code).contains(query, ignoreCase = true) || it.code.contains(query.uppercase()) }
        .sortedWith { a, b -> collator.compare(e.displayName(a.code), e.displayName(b.code)) }
    Sheet(title, onDismiss) { padding ->
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
            item { header() }
            item { SectionHeader("Contacts") }
            if (list.isEmpty()) {
                item { Text("Aucun contact", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
            items(list, key = { it.code }) { c ->
                ListItem(
                    leadingContent = { EngineAvatar(e, c.code, 40.dp) },
                    headlineContent = { Text(e.displayName(c.code)) },
                    supportingContent = { Text(TalkaCode.pretty(c.code), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.clickable { pick(c.code) },
                )
            }
        }
    }
}

/** Choisir un ou plusieurs contacts (un appel, un appel de groupe), dans l'ordre où on les coche. */
@Composable
fun MultiContactPicker(
    title: String,
    action: String,
    exclude: Set<String>,
    onDismiss: () -> Unit,
    pick: (List<String>) -> Unit,
) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>() }
    val collator = remember { Collator.getInstance(Locale.FRANCE).apply { strength = Collator.PRIMARY } }
    val list = e.contacts.values
        .filter { it.saved && !it.blocked && it.code !in exclude }
        .filter { query.isEmpty() || e.displayName(it.code).contains(query, ignoreCase = true) || it.code.contains(query.uppercase()) }
        .sortedWith { a, b -> collator.compare(e.displayName(a.code), e.displayName(b.code)) }
    Sheet(
        title,
        onDismiss,
        actions = { TextButton(onClick = { pick(chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text(action) } },
    ) { padding ->
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
            if (list.isEmpty()) {
                item { Text("Aucun contact", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
            items(list, key = { it.code }) { c ->
                val on = c.code in chosen
                ListItem(
                    leadingContent = { EngineAvatar(e, c.code, 40.dp) },
                    headlineContent = { Text(e.displayName(c.code)) },
                    supportingContent = { Text(TalkaCode.pretty(c.code), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = {
                        Icon(
                            if (on) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                            null,
                            tint = if (on) TalkaColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier
                        .semantics { selected = on }
                        .clickable { if (on) chosen.remove(c.code) else chosen.add(c.code) },
                )
            }
        }
    }
}
