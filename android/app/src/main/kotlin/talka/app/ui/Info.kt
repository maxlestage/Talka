// Les infos d'un contact (nom, code, numéro de sécurité, protection de la
// discussion — session à double cliquet ou ancien chiffrement —, blocage) et
// d'un groupe (membres, admins, photo, nom, description) : le portage
// d'`Info.swift`, avec ce qu'il porte aussi (la sourdine, les fonds d'écran,
// « Vider la discussion », les photos, documents et liens de la fiche, et le
// menu ⋮ d'une discussion).

package talka.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import talka.app.LocalAppModel
import talka.app.TalkaColors
import talka.app.launch
import talka.kit.Chat
import talka.kit.Engine
import talka.kit.Message
import talka.kit.TalkaCode
import talka.kit.TalkaCrypto
import talka.kit.V3
import talka.kit.formatBytes
import talka.kit.groupDesc
import talka.kit.protection
import kotlin.math.max

// ----- Contact -----

/** La fiche d'un contact. */
@Composable
fun ContactInfoView(code: String, onBack: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    val context = LocalContext.current
    var name by remember(code) { mutableStateOf(e.contact(code)?.name ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf<Boolean?>(null) }
    var showStarred by remember { mutableStateOf(false) }
    var safety by remember { mutableStateOf(false) }
    // La protection de la discussion : session à double cliquet (v3) ou
    // ancien chiffrement, comme dans le navigateur.
    var protection by remember(code) { mutableStateOf<V3.Protection?>(null) }
    LaunchedEffect(code) { protection = e.protection(code) }

    fun save() {
        val n = name.trimSpaces().firstChars(40)
        if (n != (e.contact(code)?.name ?: "")) e.updateContact(code) { it.copy(name = n) }
    }
    val saveNow by rememberUpdatedState(::save)
    DisposableEffect(code) { onDispose { saveNow() } }

    if (safety) return SafetyView(code, onBack = { safety = false })

    val c = e.contact(code)
    val chat = e.chat(code)
    val blocked = c?.blocked == true
    Page("Infos du contact", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            // Ce que le moteur change (hors de l'état de Compose) : la liste suit `revision`.
            model.revision
            item {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    EngineAvatar(e, code, 110.dp)
                    Text(e.displayName(code), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    SelectionContainer {
                        Text(TalkaCode.pretty(code), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    e.presence[code]?.let { p ->
                        Text(
                            if (p.online) "en ligne" else p.seen?.let { Dates.lastSeen(it) } ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InfoAction("Audio", Icons.Filled.Call, enabled = !blocked, Modifier.weight(1f)) {
                            model.launch { model.startCall(code, video = false) }
                        }
                        InfoAction("Vidéo", Icons.Filled.Videocam, enabled = !blocked, Modifier.weight(1f)) {
                            model.launch { model.startCall(code, video = true) }
                        }
                        InfoAction("Partager", Icons.Filled.Share, enabled = !blocked, Modifier.weight(1f)) {
                            shareText(context, model.inviteLink(code))
                        }
                    }
                }
            }
            val about = c?.about ?: ""
            if (about.isNotEmpty()) {
                item {
                    SectionHeader("Infos")
                    SelectionContainer { Text(about, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                }
            }
            item {
                SectionHeader("Nom")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom du contact") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                val profile = c?.profileName ?: ""
                if (profile.isNotEmpty()) {
                    ListItem(
                        headlineContent = { Text("Son nom de profil") },
                        trailingContent = { Text(profile, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                SectionFooter("Visible seulement par vous.")
            }
            if (chat != null) {
                item {
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    TimerRow(e, chat)
                    MuteMenu(e, chat)
                    WallpaperPicker(e, chat.id)
                    StarredRow(e, chat.id) { showStarred = true }
                }
                chatMediaSections(e, chat.id)
            }
            // Les groupes en commun, comme WhatsApp (et le navigateur).
            val common = e.groupsInCommon(code)
            if (common.isNotEmpty()) {
                item { SectionHeader("Groupes en commun (${common.size})") }
                items(common, key = { "g-${it.id}" }) { g ->
                    ListItem(
                        leadingContent = { EngineAvatar(e, g.id, 36.dp) },
                        headlineContent = { Text(e.title(g)) },
                        modifier = Modifier.clickable { model.openChat(g.id) },
                    )
                }
            }
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                ListItem(
                    headlineContent = { Text("Chiffrement") },
                    // Sans session encore, ou avec un contact qui n'a pas la mise à jour : `protection` le dit.
                    supportingContent = protection?.let { p -> { Text(p.label) } },
                    leadingContent = { Icon(Icons.Filled.Lock, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable { safety = true },
                )
                SectionFooter("Les messages et appels sont chiffrés de bout en bout. Touchez pour vérifier le numéro de sécurité.")
            }
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                val who = e.displayName(code)
                DangerRow(if (blocked) "Débloquer $who" else "Bloquer $who", red = !blocked) { e.setBlocked(code, !blocked) }
                if (chat != null) {
                    DangerRow("Vider la discussion") { clearing = e.hasStarred(code) }
                    DangerRow("Supprimer la discussion") { confirmDelete = true }
                }
                Spacer(Modifier.heightIn(min = 24.dp))
            }
        }
    }
    clearing?.let { keepable -> ClearChatDialog(e, code, keepable, onDismiss = { clearing = null }) }
    if (showStarred) StarredSheet(code, onDismiss = { showStarred = false })
    if (confirmDelete) {
        ConfirmDialog(
            title = "Supprimer la discussion ?",
            message = "Elle disparaîtra de cet appareil avec tous ses messages.",
            confirm = "Supprimer",
            onConfirm = {
                confirmDelete = false
                e.deleteChat(code)
                model.path = emptyList()
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Un des trois boutons sous l'avatar (Audio, Vidéo, Partager). */
@Composable
private fun InfoAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, modifier: Modifier, run: () -> Unit) {
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = run)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = TalkaColors.green)
        Text(label, style = MaterialTheme.typography.labelMedium, color = TalkaColors.green)
    }
}

/** Une action rouge en bas d'une fiche (bloquer, vider, supprimer, quitter). */
@Composable
private fun DangerRow(title: String, red: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, color = if (red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** « Jusqu'à 18:30 », « Jusqu'à demain 09:00 », « Toujours », « Non ». */
fun muteState(chat: Chat?): String {
    if (chat == null || !chat.muted) return "Non"
    val until = chat.muteUntil ?: return "Toujours"
    val day = Dates.`when`(until)
    return "Jusqu'à " + (if (day == "aujourd'hui") "" else "$day ") + Dates.clock(until)
}

/**
 * La sourdine, comme WhatsApp : 8 heures, une semaine ou toujours ; elle
 * se lève d'elle-même à l'échéance. Le même choix que dans le navigateur.
 */
@Composable
fun MuteMenu(e: Engine, chat: Chat) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text("Silence") },
            leadingContent = { Icon(Icons.Filled.NotificationsOff, null) },
            trailingContent = { Text(muteState(chat), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            MuteChoices(e, chat.id, chat.muted, onDone = { open = false })
        }
    }
}

/** Les choix de la sourdine, dans un menu. */
@Composable
fun MuteChoices(engine: Engine, chatId: String, muted: Boolean, onDone: () -> Unit) {
    if (muted) {
        DropdownMenuItem(
            text = { Text("Réactiver le son") },
            leadingIcon = { Icon(Icons.Filled.Notifications, null) },
            onClick = {
                onDone()
                engine.mute(chatId, 0.0)
            },
        )
    } else {
        for ((label, seconds) in muteChoices) {
            DropdownMenuItem(text = { Text(label) }, onClick = {
                onDone()
                engine.mute(chatId, seconds)
            })
        }
    }
}

private val muteChoices = listOf("8 heures" to 8.0 * 3600, "1 semaine" to 7.0 * 86400, "Toujours" to null)

/** Les choix de la sourdine : « Mettre en sourdine » (8 heures, 1 semaine, toujours), ou réactiver le son. */
@Composable
fun MuteChoicesDialog(engine: Engine, chatId: String, muted: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (muted) "Sourdine" else "Mettre en sourdine") },
        text = {
            Column {
                Text("Plus de notification ni de son ; les messages arrivent quand même.")
                if (muted) {
                    TextButton(onClick = {
                        engine.mute(chatId, 0.0)
                        onDismiss()
                    }) { Text("Réactiver le son") }
                } else {
                    for ((label, seconds) in muteChoices) {
                        TextButton(onClick = {
                            engine.mute(chatId, seconds)
                            onDismiss()
                        }) { Text(label) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** La durée de vie des messages éphémères : (secondes, nom). */
val timerOptions = listOf(0L to "Désactivés", 86400L to "24 heures", 7L * 86400 to "7 jours", 90L * 86400 to "90 jours")

/** « Messages éphémères » : le choix de leur durée (pas dans un groupe qu'on a quitté). */
@Composable
fun TimerRow(e: Engine, chat: Chat) {
    if (chat.left) {
        ListItem(
            headlineContent = { Text("Messages éphémères", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingContent = { Icon(Icons.Filled.Timer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingContent = { Text(timerOptions.firstOrNull { it.first == chat.timer }?.second ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    } else {
        ChoiceRow("Messages éphémères", timerOptions, chat.timer, icon = Icons.Filled.Timer) { e.setTimer(chat.id, it) }
    }
}

/** Le fond d'écran d'une discussion (« Par défaut » : celui des réglages). */
@Composable
fun WallpaperPicker(e: Engine, chatId: String) {
    WallpaperRows(e.chat(chatId)?.wallpaper ?: "", onChange = { v -> e.updateChat(chatId) { it.copy(wallpaper = v) } }, allowDefault = true)
}

/**
 * « Vider la discussion ? » : avec des messages importants, on peut les
 * garder (comme WhatsApp). `keepable` : la discussion a des messages
 * importants (`Engine.hasStarred`, demandé au toucher).
 */
@Composable
fun ClearChatDialog(engine: Engine, chatId: String, keepable: Boolean, onDismiss: () -> Unit) {
    val red = MaterialTheme.colorScheme.error
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vider la discussion ?") },
        text = {
            Column {
                Text("Les messages seront effacés de vos appareils.")
                if (keepable) {
                    TextButton(onClick = {
                        engine.clearChat(chatId, keepStarred = true)
                        onDismiss()
                    }) { Text("Vider, garder les importants", color = red) }
                    TextButton(onClick = {
                        engine.clearChat(chatId)
                        onDismiss()
                    }) { Text("Tout vider", color = red) }
                } else {
                    TextButton(onClick = {
                        engine.clearChat(chatId)
                        onDismiss()
                    }) { Text("Vider", color = red) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/**
 * Une question avec un champ de texte (renommer, nommer) : une carte au
 * milieu de l'écran, comme `AlertDialog`, dans une fenêtre plein écran qui
 * suit le clavier (`imePadding`).
 */
@Composable
fun TextFieldDialog(
    title: String,
    value: String,
    onValue: (String) -> Unit,
    label: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val outside = remember { MutableInteractionSource() }
        val inside = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = outside, indication = null, onClick = onDismiss)
                .safeDrawingPadding()
                .imePadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().clickable(interactionSource = inside, indication = null) {},
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    note?.let { Text(it, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValue,
                        label = { Text(label) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Annuler") }
                        TextButton(onClick = onConfirm) { Text(confirm) }
                    }
                }
            }
        }
    }
}

/** Les fonds d'écran de Talka : (valeur, nom), les mêmes que le navigateur. */
val wallpapers = listOf("doodle" to "Talka", "plain" to "Uni", "mint" to "Menthe", "sky" to "Ciel", "rose" to "Rose", "night" to "Nuit", "sand" to "Sable")

/**
 * Le choix d'un fond d'écran : un des fonds de Talka, ou une photo de la
 * galerie (réduite, gardée sur l'appareil en adresse `data:`, jamais
 * envoyée) — la même valeur que dans le navigateur.
 */
@Composable
fun WallpaperRows(value: String, onChange: (String) -> Unit, allowDefault: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val isPhoto = value.startsWith("data:")
    val current = when {
        isPhoto -> "Ma photo"
        value.isEmpty() -> if (allowDefault) "Par défaut" else "Talka"
        else -> wallpapers.firstOrNull { it.first == value }?.second ?: "Talka"
    }
    val pick = rememberPhotoPicker { bitmap: Bitmap -> Images.compressed(bitmap, 1280, 0.78f)?.let { onChange(it.first) } }
    Box {
        ListItem(
            headlineContent = { Text("Fond d'écran") },
            leadingContent = { Icon(Icons.Filled.Palette, null) },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isPhoto) {
                        Images.fromDataURL(value)?.let {
                            Image(it, null, Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                        }
                    }
                    Text(current, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            modifier = Modifier.clickable { open = true }.semantics { contentDescription = "Fond d'écran : $current" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val choices = (if (allowDefault) listOf("" to "Par défaut") else emptyList()) + wallpapers
            for ((v, label) in choices) {
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { RadioButton(selected = !isPhoto && v == value, onClick = null) },
                    onClick = {
                        open = false
                        onChange(v)
                    },
                )
            }
            if (isPhoto) {
                DropdownMenuItem(text = { Text("Ma photo") }, leadingIcon = { RadioButton(selected = true, onClick = null) }, onClick = { open = false })
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (isPhoto) "Changer de photo…" else "Une photo de la galerie…") },
                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) },
                onClick = {
                    open = false
                    pick()
                },
            )
        }
    }
}

/** Le numéro de sécurité : le même sur les deux appareils. */
@Composable
fun SafetyView(code: String, onBack: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    val context = LocalContext.current
    val number = safetyNumber(e, code)
    Page("Numéro de sécurité", onBack) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(Icons.Filled.Security, null, Modifier.size(56.dp), tint = TalkaColors.green)
            Text(
                "Comparez ce numéro avec celui affiché sur l'appareil de ${e.displayName(code)}. S'ils sont identiques, personne ne s'interpose entre vous.",
                textAlign = TextAlign.Center,
            )
            val groups = number.split(" ")
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                    .padding(16.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Numéro de sécurité : $number" },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (row in groups.chunked(4)) {
                    Row(Modifier.fillMaxWidth()) {
                        for (g in row) {
                            Text(g, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            QrImage("talka-safety:$number", Modifier.width(180.dp), label = "QR code du numéro de sécurité")
            TextButton(onClick = {
                copyText(context, number)
                model.show("Numéro copié")
            }) {
                Icon(Icons.Filled.ContentCopy, null)
                Spacer(Modifier.width(8.dp))
                Text("Copier")
            }
        }
    }
}

/** Le numéro de sécurité avec un contact : sur nos clés publiques et les siennes. */
fun safetyNumber(e: Engine, code: String): String {
    val peer = e.contact(code)
    return TalkaCrypto.safetyNumber(
        TalkaCrypto.Keys(e.me.code, e.me.sign, e.me.dh),
        TalkaCrypto.Keys(code, peer?.sign ?: "", peer?.dh ?: ""),
    )
}

// ----- Groupe -----

/** La fiche d'un groupe. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupInfoView(chatId: String, onBack: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    var name by remember(chatId) { mutableStateOf(e.chat(chatId)?.name ?: "") }
    var desc by remember(chatId) { mutableStateOf(e.chat(chatId)?.desc ?: "") }
    var adding by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf<Boolean?>(null) }
    var showStarred by remember { mutableStateOf(false) }
    var memberMenu by remember { mutableStateOf<String?>(null) }

    fun isAdmin(): Boolean {
        val c = e.chat(chatId) ?: return false
        return e.me.code in c.admins && !c.left
    }

    fun rename() {
        val n = name.trimSpaces().firstChars(60)
        if (n.isEmpty() || n == e.chat(chatId)?.name) return
        e.updateGroup(chatId, "Vous avez renommé le groupe en « $n ».") { it.copy(name = n) }
    }

    fun saveDesc() {
        val d = groupDesc(desc)
        val c = e.chat(chatId) ?: return
        if (d == c.desc) return
        val note = if (d.isEmpty()) "Vous avez supprimé la description du groupe." else "Vous avez changé la description du groupe."
        e.updateGroup(chatId, note) { it.copy(desc = d) }
    }
    val leaving by rememberUpdatedState {
        if (isAdmin()) {
            rename()
            saveDesc()
        }
    }
    DisposableEffect(chatId) { onDispose { leaving() } }
    val pick = rememberPhotoPicker { bitmap ->
        val url = Images.avatarDataURL(bitmap) ?: return@rememberPhotoPicker
        e.updateGroup(chatId, "Vous avez changé la photo du groupe.") { it.copy(avatar = url) }
    }

    val chat = e.chat(chatId) ?: return
    val admin = isAdmin()
    val me = e.me.code
    Page("Infos du groupe", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            // Ce que le moteur change (hors de l'état de Compose) : la liste suit `revision`.
            model.revision
            item {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (admin) {
                        Box(Modifier.clip(CircleShape).clickable { pick() }.semantics { contentDescription = "Changer la photo du groupe" }) {
                            EngineAvatar(e, chatId, 110.dp)
                        }
                    } else {
                        EngineAvatar(e, chatId, 110.dp)
                    }
                    Text(e.title(chat), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("Groupe · ${chat.members.size} membres", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (admin) {
                item {
                    ListItem(
                        headlineContent = { Text("Photo du groupe (galerie)", color = MaterialTheme.colorScheme.primary) },
                        leadingContent = { Icon(Icons.Filled.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { pick() },
                    )
                    SectionHeader("Nom du groupe")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nom du groupe") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { rename() }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
            // La description : les admins l'écrivent, tous les membres la lisent.
            if (admin) {
                item {
                    SectionHeader("Description")
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        placeholder = { Text("Ajouter une description") },
                        minLines = 1,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = "Description" },
                    )
                    TextButton(onClick = { saveDesc() }, enabled = groupDesc(desc) != chat.desc, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Enregistrer la description")
                    }
                    SectionFooter("Visible par tous les membres.")
                }
            } else if (chat.desc.isNotEmpty()) {
                item {
                    SectionHeader("Description")
                    SelectionContainer { Text(chat.desc, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                }
            }
            if (admin) {
                item {
                    SwitchRow("Seuls les admins peuvent écrire", chat.adminsOnly) { on ->
                        val note = if (on) "Vous avez réservé l'envoi de messages aux admins." else "Vous avez permis à tous les membres d'écrire."
                        e.updateGroup(chatId, note) { it.copy(adminsOnly = on) }
                    }
                    SectionFooter("Les autres membres lisent sans pouvoir répondre.")
                }
            }
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                TimerRow(e, chat)
                MuteMenu(e, chat)
                WallpaperPicker(e, chatId)
                StarredRow(e, chatId) { showStarred = true }
            }
            chatMediaSections(e, chatId)
            item { SectionHeader("${chat.members.size} membres") }
            if (admin) {
                item {
                    ListItem(
                        headlineContent = { Text("Ajouter des membres", color = MaterialTheme.colorScheme.primary) },
                        leadingContent = { Icon(Icons.Filled.PersonAdd, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { adding = true },
                    )
                }
            }
            val sorted = chat.members.sortedWith { a, b ->
                when {
                    a == b -> 0
                    a == me -> -1
                    b == me -> 1
                    else -> e.displayName(a).compareTo(e.displayName(b))
                }
            }
            items(sorted, key = { "m-$it" }) { code ->
                Box {
                    ListItem(
                        leadingContent = { EngineAvatar(e, code, 38.dp) },
                        headlineContent = { Text(if (code == me) "Vous" else e.displayName(code)) },
                        supportingContent = { Text(TalkaCode.pretty(code), fontFamily = FontFamily.Monospace) },
                        trailingContent = if (code in chat.admins) ({ AdminBadge() }) else null,
                        modifier = if (code == me) Modifier else Modifier.combinedClickable(onClick = { memberMenu = code }, onLongClick = { memberMenu = code }),
                    )
                    DropdownMenu(expanded = memberMenu == code, onDismissRequest = { memberMenu = null }, modifier = Modifier.align(Alignment.TopEnd)) {
                        MemberMenu(e, chat, code, admin, onDone = { memberMenu = null })
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                if (!chat.left) {
                    DangerRow("Quitter le groupe") { confirmLeave = true }
                } else {
                    DangerRow("Supprimer le groupe") {
                        e.deleteChat(chatId)
                        model.path = emptyList()
                        onBack()
                    }
                }
                DangerRow("Vider la discussion") { clearing = e.hasStarred(chatId) }
                Spacer(Modifier.heightIn(min = 24.dp))
            }
        }
    }
    clearing?.let { keepable -> ClearChatDialog(e, chatId, keepable, onDismiss = { clearing = null }) }
    if (showStarred) StarredSheet(chatId, onDismiss = { showStarred = false })
    if (adding) AddMembersSheet(chatId, onDismiss = { adding = false })
    if (confirmLeave) {
        ConfirmDialog(
            title = "Quitter « ${e.title(chat)} » ?",
            message = "Vous ne recevrez plus ses messages.",
            confirm = "Quitter le groupe",
            onConfirm = {
                confirmLeave = false
                e.leaveGroup(chatId)
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

/** La pastille « Admin » d'un membre. */
@Composable
private fun AdminBadge() {
    Text(
        "Admin",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = TalkaColors.green,
        modifier = Modifier.background(TalkaColors.green.copy(alpha = 0.12f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Ce qu'on peut faire d'un membre : lui écrire, l'ajouter aux contacts ; admin, le nommer admin ou le retirer. */
@Composable
private fun MemberMenu(e: Engine, chat: Chat, code: String, admin: Boolean, onDone: () -> Unit) {
    val model = LocalAppModel.current
    DropdownMenuItem(
        text = { Text("Écrire") },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Chat, null) },
        onClick = {
            onDone()
            model.openChat(code)
        },
    )
    if (e.contact(code)?.saved != true) {
        DropdownMenuItem(
            text = { Text("Ajouter aux contacts") },
            leadingIcon = { Icon(Icons.Filled.PersonAdd, null) },
            onClick = {
                onDone()
                model.launch { runCatching { e.addContact(code, "") } }
            },
        )
    }
    if (admin) {
        if (code !in chat.admins) {
            DropdownMenuItem(
                text = { Text("Nommer admin") },
                leadingIcon = { Icon(Icons.Filled.Star, null) },
                onClick = {
                    onDone()
                    e.updateGroup(chat.id, "Vous avez nommé ${e.displayName(code)} admin.") { it.copy(admins = it.admins + code) }
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Retirer du groupe", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Filled.PersonRemove, null, tint = MaterialTheme.colorScheme.error) },
            onClick = {
                onDone()
                e.updateGroup(chat.id, "Vous avez retiré ${e.displayName(code)}.") { c ->
                    c.copy(members = c.members.filter { it != code }, admins = c.admins.filter { it != code })
                }
            },
        )
    }
}

/** Ajouter des contacts au groupe. */
@Composable
fun AddMembersSheet(chatId: String, onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    val chat = e.chat(chatId) ?: return
    val picked = remember { mutableStateListOf<String>() }
    val list = e.contacts.values.filter { it.saved && !it.blocked && it.code !in chat.members }
        .sortedBy { e.displayName(it.code) }
    Sheet(
        "Ajouter des membres",
        onDismiss,
        actions = {
            TextButton(
                onClick = {
                    val codes = picked.toList()
                    val names = codes.joinToString(", ") { e.displayName(it) }
                    e.updateGroup(chatId, "Vous avez ajouté $names.") { it.copy(members = it.members + codes) }
                    onDismiss()
                },
                enabled = picked.isNotEmpty(),
            ) { Text("Ajouter") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            // Ce que le moteur change (hors de l'état de Compose) : la liste suit `revision`.
            model.revision
            if (list.isEmpty()) item { EmptyState("Tous vos contacts sont déjà dans le groupe", icon = Icons.Filled.PersonAdd) }
            items(list, key = { it.code }) { c ->
                val chosen = c.code in picked
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
                    modifier = Modifier.clickable { if (chosen) picked.remove(c.code) else picked.add(c.code) },
                )
            }
        }
    }
}

// ----- Ce que montre la fiche d'une discussion -----

/**
 * « Messages importants (n) » dans les infos d'un contact ou d'un groupe,
 * s'il y en a (comme WhatsApp et le navigateur).
 */
@Composable
fun StarredRow(e: Engine, chatId: String, onClick: () -> Unit) {
    val n = e.starredCount(chatId)
    if (n > 0) {
        ListItem(
            headlineContent = { Text("Messages importants") },
            leadingContent = { Icon(Icons.Filled.Star, null) },
            trailingContent = { Text("$n", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

/** Les photos, documents et liens de la discussion, comme WhatsApp (et le navigateur). */
fun LazyListScope.chatMediaSections(e: Engine, chatId: String) {
    item(key = "photos") { PhotosSection(e, chatId) }
    item(key = "documents") { DocumentsSection(e, chatId) }
    item(key = "liens") { LinksSection(e, chatId) }
}

/** Une vignette (au plus `side` pixels de côté), décodée à part : pas la photo entière. */
private fun thumbnail(url: String, side: Int = 240): ImageBitmap? {
    val data = dataOfURL(url) ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
    return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/**
 * Les photos de la discussion dans sa fiche : les 24 plus récentes en
 * grille, « Tout voir (N) » pour les autres ; toucher en ouvre une.
 */
@Composable
fun PhotosSection(e: Engine, chatId: String) {
    val model = LocalAppModel.current
    val photos = remember(model.revision, chatId) { e.photos(chatId) }
    // « Tout voir (N) » : toutes les photos, pas seulement les 24 dernières.
    var all by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Message?>(null) }
    var thumbs by remember { mutableStateOf<List<Pair<Message, ImageBitmap>>>(emptyList()) }
    // Décodées une fois (en vignettes), pas à chaque mise à jour de la fiche.
    val ids = photos.map { it.id }
    LaunchedEffect(ids, all) {
        val shown = photos.take(if (all) photos.size else 24)
        thumbs = withContext(Dispatchers.Default) { shown.mapNotNull { m -> thumbnail(m.data)?.let { m to it } } }
    }
    if (thumbs.isEmpty()) return
    Column {
        SectionHeader("Photos (${photos.size})")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in thumbs.chunked(4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((m, image) in row) {
                        Image(
                            image,
                            "Photo",
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).clickable { viewing = m },
                            contentScale = ContentScale.Crop,
                        )
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (!all && photos.size > 24) TextButton(onClick = { all = true }, Modifier.padding(horizontal = 8.dp)) { Text("Tout voir (${photos.size})") }
    }
    viewing?.let { m -> PhotoViewer(e, m, onDismiss = { viewing = null }) }
}

/** Enregistrer un fichier où l'on veut (le sélecteur de fichiers d'Android) : `save(nom, octets)`. */
@Composable
private fun rememberFileSaver(mime: String): (String, ByteArray) -> Unit {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        val data = pending
        pending = null
        if (uri == null || data == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(data) } != null
                } catch (x: Exception) {
                    false
                }
            }
            model.show(if (ok) "Enregistré ✓" else "Le fichier n'a pas pu être écrit.")
        }
    }
    return { name, data ->
        pending = data
        model.leavingOnPurpose()
        try {
            saver.launch(name)
        } catch (x: Exception) {
            pending = null
            model.show("Impossible d'enregistrer ici.")
        }
    }
}

/** Une photo en grand : pincer pour l'agrandir ; l'enregistrer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoViewer(e: Engine, message: Message, onDismiss: () -> Unit) {
    val save = rememberFileSaver("image/jpeg")
    var scale by remember { mutableFloatStateOf(1f) }
    val zoom = rememberTransformableState { change, _, _ -> scale = max(1f, scale * change) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(e.displayName(message.from), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { TextButton(onClick = onDismiss) { Text("Fermer", color = Color.White) } },
                    actions = {
                        IconButton(onClick = { dataOfURL(message.data)?.let { save(message.saveName, it) } }) {
                            Icon(Icons.Filled.Download, "Enregistrer la photo")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                )
                Box(Modifier.fillMaxSize().transformable(zoom), contentAlignment = Alignment.Center) {
                    Images.fromDataURL(message.data)?.let {
                        Image(it, "Photo", Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale), contentScale = ContentScale.Fit)
                    }
                }
            }
        }
    }
}

/**
 * Les documents de la discussion dans sa fiche : les 20 plus récents,
 * « Tout voir (N) » pour les autres ; toucher en enregistre un.
 */
@Composable
fun DocumentsSection(e: Engine, chatId: String) {
    val model = LocalAppModel.current
    val docs = remember(model.revision, chatId) { e.documents(chatId) }
    var all by remember { mutableStateOf(false) }
    val save = rememberFileSaver("application/octet-stream")
    if (docs.isEmpty()) return
    Column {
        SectionHeader("Documents (${docs.size})")
        for (m in docs.take(if (all) docs.size else 20)) {
            val label = m.name.ifEmpty { "Fichier" }
            ListItem(
                leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = TalkaColors.green) },
                headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${m.fileDetail(formatBytes(m.size))} · ${Dates.separator(m.at)}") },
                trailingContent = { Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier
                    .clickable { dataOfURL(m.data)?.let { save(m.saveName, it) } }
                    .semantics(mergeDescendants = true) { contentDescription = "Enregistrer ${if (m.name.isEmpty()) "le fichier" else m.name}" },
            )
        }
        if (!all && docs.size > 20) TextButton(onClick = { all = true }, Modifier.padding(horizontal = 8.dp)) { Text("Tout voir (${docs.size})") }
    }
}

/**
 * Les liens de la discussion dans sa fiche : les 20 plus récents, « Tout
 * voir (N) » pour les autres ; toucher en ouvre un.
 */
@Composable
fun LinksSection(e: Engine, chatId: String) {
    val model = LocalAppModel.current
    val uri = LocalUriHandler.current
    val links = remember(model.revision, chatId) { e.links(chatId) }
    var all by remember { mutableStateOf(false) }
    if (links.isEmpty()) return
    Column {
        SectionHeader("Liens (${links.size})")
        for (l in links.take(if (all) links.size else 20)) {
            ListItem(
                leadingContent = { Icon(Icons.Filled.Link, null, tint = TalkaColors.green) },
                headlineContent = { Text(l.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${e.displayName(l.message.from)} · ${Dates.separator(l.message.at)}") },
                modifier = Modifier.clickable {
                    try {
                        uri.openUri(l.url)
                    } catch (x: Exception) {
                        model.show("Ce lien ne s'ouvre pas.")
                    }
                },
            )
        }
        if (!all && links.size > 20) TextButton(onClick = { all = true }, Modifier.padding(horizontal = 8.dp)) { Text("Tout voir (${links.size})") }
    }
}

// ----- Le menu ⋮ d'une discussion -----

/**
 * Le menu ⋮ d'une discussion : les mêmes choix, dans le même ordre, que
 * celui du navigateur (`chat_menu`), comme WhatsApp. Pour la barre de la
 * discussion : `searching` dit si la recherche est ouverte, `onSearch`
 * l'ouvre ou la ferme, `onInfo` ouvre la fiche. Le choix des clés Brocolis,
 * le fond d'écran, l'export et les confirmations s'ouvrent d'ici.
 */
@Composable
fun ChatMoreMenu(chatId: String, searching: Boolean, onSearch: () -> Unit, onInfo: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    val chat = e.chat(chatId) ?: return
    var open by remember { mutableStateOf(false) }
    var choosingTimer by remember { mutableStateOf(false) }
    var muting by remember { mutableStateOf(false) }
    var choosingWallpaper by remember { mutableStateOf(false) }
    var choosingBrocolis by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf<Boolean?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var addingContact by remember { mutableStateOf(false) }
    var contactName by remember { mutableStateOf("") }
    val save = rememberFileSaver("text/plain")

    fun item(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, red: Boolean = false, run: () -> Unit): @Composable () -> Unit = {
        val tint = if (red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        DropdownMenuItem(
            text = { Text(text, color = tint) },
            leadingIcon = { Icon(icon, null, tint = tint) },
            onClick = {
                open = false
                run()
            },
        )
    }

    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Plus d'options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            item(if (chat.group) "Infos du groupe" else "Infos du contact", Icons.Filled.Info, run = onInfo)()
            item(if (searching) "Fermer la recherche" else "Rechercher", Icons.Filled.Search, run = onSearch)()
            item("Messages éphémères", Icons.Filled.Timer) { choosingTimer = true }()
            item("Exporter la discussion", Icons.Filled.Upload) {
                e.exportChat(chatId)?.let { save(it.filename, it.text.toByteArray()) }
            }()
            val bro = if (chat.brocolis.isEmpty()) "Chiffrer avec Brocolis 🥦" else "Brocolis 🥦 : ${e.chatBrocolis(chat).joinToString(", ") { it.name }}"
            item(bro, Icons.Filled.Shield) { choosingBrocolis = true }()
            if (chat.muted) {
                item("Réactiver les notifications", Icons.Filled.Notifications) { e.mute(chatId, 0.0) }()
            } else {
                item("Mettre en sourdine", Icons.Filled.NotificationsOff) { muting = true }()
            }
            item("Fond d'écran", Icons.Filled.Palette) { choosingWallpaper = true }()
            // Quelqu'un qui nous a écrit sans être dans nos contacts.
            if (!chat.group && e.contact(chatId)?.saved != true) {
                item("Ajouter aux contacts", Icons.Filled.PersonAdd) {
                    contactName = e.contact(chatId)?.profileName ?: ""
                    addingContact = true
                }()
            }
            if (!chat.group) {
                if (e.blocked(chatId)) {
                    item("Débloquer", Icons.Filled.Block) { e.setBlocked(chatId, false) }()
                } else {
                    item("Bloquer", Icons.Filled.Block, red = true) { e.setBlocked(chatId, true) }()
                }
            }
            item("Vider la discussion", Icons.Filled.DeleteSweep, red = true) { clearing = e.hasStarred(chatId) }()
            item(if (chat.group) "Quitter et supprimer" else "Supprimer la discussion", Icons.Filled.Delete, red = true) { confirmDelete = true }()
        }
    }
    if (choosingTimer) {
        AlertDialog(
            onDismissRequest = { choosingTimer = false },
            title = { Text("Messages éphémères") },
            text = {
                Column {
                    for ((secs, label) in timerOptions) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                choosingTimer = false
                                e.setTimer(chatId, secs)
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chat.timer == secs, onClick = null)
                            Text(label, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingTimer = false }) { Text("Annuler") } },
        )
    }
    if (muting) MuteChoicesDialog(e, chatId, muted = false, onDismiss = { muting = false })
    if (choosingWallpaper) {
        AlertDialog(
            onDismissRequest = { choosingWallpaper = false },
            title = { Text("Fond d'écran") },
            text = { WallpaperPicker(e, chatId) },
            confirmButton = { TextButton(onClick = { choosingWallpaper = false }) { Text("OK") } },
        )
    }
    if (choosingBrocolis) BrocolisChatSheet(chatId, onDismiss = { choosingBrocolis = false })
    clearing?.let { keepable -> ClearChatDialog(e, chatId, keepable, onDismiss = { clearing = null }) }
    if (addingContact) {
        TextFieldDialog(
            title = "Ajouter aux contacts",
            note = TalkaCode.pretty(chatId),
            value = contactName,
            onValue = { contactName = it },
            label = "Nom (facultatif)",
            confirm = "Ajouter",
            onConfirm = {
                addingContact = false
                val n = contactName
                model.launch { runCatching { e.addContact(chatId, n) } }
            },
            onDismiss = { addingContact = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Supprimer la discussion ?",
            message = "Elle disparaîtra de cet appareil avec tous ses messages.",
            confirm = "Supprimer",
            onConfirm = {
                confirmDelete = false
                if (chat.group && !chat.left) e.leaveGroup(chatId)
                e.deleteChat(chatId)
                model.path = emptyList()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
