// L'onglet Réglages : le profil, le code, la confidentialité, le verrou,
// l'apparence, les notifications, la sauvegarde.

package talka.app.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import talka.app.LocalAppModel
import talka.app.TalkaColors
import talka.kit.Engine
import talka.kit.Settings
import talka.kit.TalkaCode
import talka.kit.TalkaCrypto
import talka.kit.formatBytes

/** Les pages poussées depuis les réglages. */
sealed class SettingsPage {
    data object Blocked : SettingsPage()

    data object Brocolis : SettingsPage()

    data object Devices : SettingsPage()

    data object Storage : SettingsPage()

    /** La fiche d'une discussion (depuis « Gérer le stockage »). */
    data class Info(val id: String, val group: Boolean) : SettingsPage()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab() {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    val pages = remember { mutableStateListOf<SettingsPage>() }
    val back = { if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) }
    when (val page = pages.lastOrNull()) {
        SettingsPage.Blocked -> return BlockedView(onBack = back)
        SettingsPage.Brocolis -> return BrocolisKeysView(onBack = back)
        SettingsPage.Devices -> return LinkedDevicesView(onBack = back)
        SettingsPage.Storage -> return StorageView(onBack = back, open = { pages.add(it) })
        is SettingsPage.Info -> return if (page.group) GroupInfoView(page.id, onBack = back) else ContactInfoView(page.id, onBack = back)
        null -> {}
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Réglages") }) },
    ) { padding ->
        SettingsForm(e, Modifier.padding(padding), push = { pages.add(it) })
    }
}

/** Les réglages, section par section. */
@Composable
fun SettingsForm(e: Engine, modifier: Modifier = Modifier, push: (SettingsPage) -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCode by remember { mutableStateOf(false) }
    var settingPin by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var importData by remember { mutableStateOf<ByteArray?>(null) }
    var password by remember { mutableStateOf("") }
    var backupFile by remember { mutableStateOf<ByteArray?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmRemovePin by remember { mutableStateOf(false) }

    fun set(f: (Settings) -> Settings) = e.saveSettings(f)

    // « Enregistrer » : Téléchargements, Drive… (le sélecteur de fichiers d'Android).
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val data = backupFile
        backupFile = null
        if (uri == null || data == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(data) } != null
                } catch (x: Exception) {
                    false
                }
            }
            model.show(if (ok) "Sauvegarde enregistrée ✓" else "Sauvegarde non enregistrée : le fichier n'a pas pu être écrit.")
        }
    }
    // Tous les fichiers : « .talka » est inconnu du système. Le contenu est vérifié à la restauration.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importData = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (x: Exception) {
                    null
                }
            }
        }
    }

    LazyColumn(modifier.fillMaxSize()) {
        item { ProfileSection(e, onShowCode = { showCode = true }) }
        item {
            SectionHeader("Confidentialité")
            SwitchRow("Confirmations de lecture", e.settings.readReceipts) { v -> set { it.copy(readReceipts = v) } }
            SwitchRow("Masquer « en ligne »", e.settings.hideOnline) { v -> set { it.copy(hideOnline = v) } }
            SwitchRow("Pastille « en ligne »", e.settings.presenceDot) { v -> set { it.copy(presenceDot = v) } }
            NavRow("Contacts bloqués", "${e.contacts.values.count { it.blocked }}") { push(SettingsPage.Blocked) }
            SectionFooter("Sans confirmations de lecture, vous ne voyez pas non plus celles des autres. La pastille « en ligne » : un point vert sur l'avatar des contacts connectés, gris sinon.")
        }
        item {
            SectionHeader("Brocolis 🥦")
            NavRow("Clés Brocolis 🥦", "${e.brocolisKeys.size}") { push(SettingsPage.Brocolis) }
            SectionFooter("Une seconde couche de chiffrement, avec les clés de l'app Brocolis.")
        }
        item {
            SecuritySection(e, onSetPin = { settingPin = true }, onRemovePin = { confirmRemovePin = true })
        }
        item {
            SectionHeader("Appareils")
            NavRow("Appareils liés", "${e.devices.list.size}") { push(SettingsPage.Devices) }
            SectionFooter("Votre compte sur votre téléphone, votre tablette et votre ordinateur à la fois : vos messages arrivent partout, chiffrés pour chaque appareil.")
        }
        item {
            SectionHeader("Apparence")
            ChoiceRow("Thème", listOf("auto" to "Automatique", "light" to "Clair", "dark" to "Sombre"), e.settings.theme) { v -> set { it.copy(theme = v) } }
            WallpaperRows(e.settings.wallpaper, onChange = { v -> set { it.copy(wallpaper = v) } })
            StepperRow("Taille du texte : ${e.settings.fontSize}", e.settings.fontSize, 12..22) { v -> set { it.copy(fontSize = v) } }
        }
        item {
            SectionHeader("Discussions")
            ChoiceRow(
                "Conserver les messages",
                listOf(1 to "24 heures", 7 to "7 jours", 30 to "1 mois", 90 to "3 mois", 180 to "6 mois", 365 to "1 an", 0 to "Toujours"),
                e.settings.keepDays,
            ) { v -> set { it.copy(keepDays = v) } }
            SwitchRow("Entrée pour envoyer", e.settings.enterSends) { v -> set { it.copy(enterSends = v) } }
            SwitchRow("Garder les discussions archivées", e.settings.keepArchived) { v -> set { it.copy(keepArchived = v) } }
            SectionFooter("Plus vieux, ils s'effacent de cet appareil, sauf les messages importants ⭐. Entrée pour envoyer : avec un clavier, Maj + Entrée va à la ligne. Discussions archivées gardées : un nouveau message ne les fait pas ressortir.")
        }
        item {
            SectionHeader("Notifications")
            SwitchRow("Notifications", e.settings.notify) { on ->
                if (on) {
                    scope.launch {
                        val ok = model.askNotifications?.invoke() ?: Notifier.allowed()
                        e.saveSettings { it.copy(notify = ok) }
                        if (!ok) model.show("Autorisez les notifications de Talka dans les réglages de l'appareil.")
                    }
                } else {
                    e.saveSettings { it.copy(notify = false) }
                }
            }
            ChoiceRow(
                "Aperçu",
                listOf("all" to "Nom et message", "name" to "Nom seulement", "none" to "Rien (« Nouveau message »)"),
                e.settings.notifyPreview,
            ) { v -> set { it.copy(notifyPreview = v) } }
            SwitchRow("Sons", e.settings.sound) { v -> set { it.copy(sound = v) } }
            SectionFooter("Talka prévient quand l'application est ouverte ou en arrière-plan récent : aucun message ne passe par les serveurs de Google. L'aperçu choisit ce que montre l'écran verrouillé.")
        }
        item {
            SectionHeader("Sauvegarde")
            ActionRow("Exporter une sauvegarde", Icons.Filled.Upload) { exporting = true }
            ActionRow("Restaurer une sauvegarde", Icons.Filled.Download) {
                model.leavingOnPurpose()
                importer.launch(arrayOf("*/*"))
            }
            ListItem(
                headlineContent = { Text("Espace utilisé") },
                trailingContent = { Text(formatBytes(e.store.usage().toDouble()), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
            ActionRow("Gérer le stockage", Icons.Filled.Storage, chevron = true) { push(SettingsPage.Storage) }
            SectionFooter("La sauvegarde est chiffrée avec un mot de passe. Elle se restaure sur un téléphone Android, un iPhone, un iPad ou dans un navigateur.")
        }
        item {
            HorizontalDivider(Modifier.padding(top = 16.dp))
            ListItem(
                headlineContent = { Text("Tout effacer", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Filled.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { confirmWipe = true },
            )
            val version = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (x: Exception) {
                ""
            }
            SectionFooter("Talka $version · Code ouvert, chiffré de bout en bout.")
        }
    }

    if (showCode) MyCodeSheet(onDismiss = { showCode = false })
    if (settingPin) PinSetup(onDismiss = { settingPin = false })
    if (exporting) {
        PasswordDialog(
            title = "Mot de passe de la sauvegarde",
            message = "Il sera demandé pour restaurer. Sans lui, la sauvegarde est illisible.",
            password = password,
            onPassword = { password = it },
            placeholder = "Au moins 8 caractères",
            confirm = "Exporter",
            onConfirm = {
                val pw = password
                password = ""
                exporting = false
                if (pw.length < 8) {
                    model.show("Mot de passe trop court (8 caractères au moins).")
                } else {
                    scope.launch {
                        val data = withContext(Dispatchers.Default) { e.exportBackup(pw) }
                        if (data == null) {
                            model.show("La sauvegarde n'a pas pu être créée.")
                        } else {
                            backupFile = data
                            model.leavingOnPurpose()
                            saver.launch(model.backupFilename)
                        }
                    }
                }
            },
            onDismiss = {
                exporting = false
                password = ""
            },
        )
    }
    if (importData != null) {
        PasswordDialog(
            title = "Restaurer la sauvegarde ?",
            message = "Les données de cet appareil seront remplacées par celles de la sauvegarde. Cet appareil remplacera celui d'où elle vient : s'il sert encore, il sera retiré du compte.",
            password = password,
            onPassword = { password = it },
            placeholder = "Mot de passe",
            confirm = "Restaurer",
            destructive = true,
            onConfirm = {
                val data = importData ?: return@PasswordDialog
                val pw = password
                importData = null
                password = ""
                scope.launch {
                    try {
                        model.restore(data, pw)
                        model.show("Sauvegarde restaurée ✓")
                    } catch (x: Exception) {
                        model.show(x.message ?: "La sauvegarde n'a pas pu être restaurée.")
                    }
                }
            },
            onDismiss = {
                importData = null
                password = ""
            },
        )
    }
    if (confirmRemovePin) {
        ConfirmDialog(
            title = "Retirer le code ?",
            message = "Talka s'ouvrira sans code, et vos messages ne seront plus chiffrés par lui (ils restent chiffrés par l'appareil).",
            confirm = "Retirer le code",
            onConfirm = {
                confirmRemovePin = false
                scope.launch {
                    model.setPin(null)
                    model.show("Code retiré")
                }
            },
            onDismiss = { confirmRemovePin = false },
        )
    }
    if (confirmWipe) {
        ConfirmDialog(
            title = "Tout effacer ?",
            message = "Votre code sera perdu pour toujours, sauf si vous avez une sauvegarde.",
            confirm = "Effacer le code, les contacts et les messages",
            onConfirm = {
                confirmWipe = false
                scope.launch { model.leaveAndWipe() }
            },
            onDismiss = { confirmWipe = false },
        )
    }
}

/** Le profil : la photo, le nom, les infos, le code. */
@Composable
fun ProfileSection(e: Engine, onShowCode: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(e.profile.name) }
    var about by remember { mutableStateOf(e.profile.about) }
    val pick = rememberPhotoPicker { bitmap -> Images.avatarDataURL(bitmap)?.let { url -> e.saveProfile { it.copy(avatar = url) } } }
    val camera = rememberCameraPicker { bitmap -> Images.avatarDataURL(bitmap)?.let { url -> e.saveProfile { it.copy(avatar = url) } } }

    fun save() {
        val n = name.trimSpaces().firstChars(40)
        val a = about.trimSpaces().firstChars(140)
        if (n.isEmpty() || (n == e.profile.name && a == e.profile.about)) return
        e.saveProfile { it.copy(name = n, about = a) }
    }
    val saveNow by rememberUpdatedState(::save)
    DisposableEffect(Unit) { onDispose { saveNow() } }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clickable(onClickLabel = "Changer de photo") { pick() }) { EngineAvatar(e, e.me.code, 64.dp) }
            Column(Modifier.weight(1f)) {
                val colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                )
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Votre nom") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = about,
                    onValueChange = { about = it },
                    placeholder = { Text("Infos (ex. Disponible)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ActionRow(if (e.profile.avatar.isEmpty()) "Ajouter une photo de la galerie" else "Changer de photo (galerie)", Icons.Filled.PhotoLibrary) { pick() }
        if (cameraAvailable(context)) ActionRow("Prendre une photo", Icons.Filled.PhotoCamera) { camera() }
        if (e.profile.avatar.isNotEmpty()) {
            ListItem(
                headlineContent = { Text("Retirer la photo", color = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { e.saveProfile { it.copy(avatar = "") } },
            )
        }
        ListItem(
            headlineContent = { Text("Mon code") },
            supportingContent = {
                Text(
                    TalkaCode.pretty(e.me.code),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = TalkaColors.dark,
                )
            },
            trailingContent = { Icon(Icons.Filled.QrCode, null) },
            modifier = Modifier.clickable { onShowCode() },
        )
    }
}

/**
 * Le verrou de Talka, comme dans le navigateur : le code (qui chiffre
 * aussi les messages), la biométrie, le délai, l'effacement après 10 codes
 * faux ; et l'écran caché pendant un enregistrement.
 */
@Composable
fun SecuritySection(e: Engine, onSetPin: () -> Unit, onRemovePin: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val scope = rememberCoroutineScope()
    val bio = model.biometryName
    SectionHeader("Verrouillage et sécurité")
    if (model.hasPin) {
        SettingRow("Changer le code de verrouillage", "Talka demande votre code à l'ouverture", Icons.Filled.Lock) { onSetPin() }
        if (bio != null) {
            SwitchRow(bio, model.faceID, "Ouvre Talka sans taper le code", Icons.Filled.Fingerprint) { model.faceID = it }
        }
        ChoiceRow(
            "Verrouillage automatique",
            listOf(0 to "Dès que je quitte Talka", 60 to "Après 1 minute", 300 to "Après 5 minutes", 900 to "Après 15 minutes", 3600 to "Après 1 heure"),
            e.settings.lockAfter,
            Icons.Filled.Timer,
        ) { v -> e.saveSettings { it.copy(lockAfter = v) } }
        SwitchRow("Effacer après 10 codes faux", model.wipeAfterFails, "Au dixième code faux, Talka efface tout sur cet appareil", Icons.Filled.DeleteOutline) {
            model.wipeAfterFails = it
        }
        SettingRow("Retirer le code de verrouillage", null, Icons.Filled.LockOpen, tint = MaterialTheme.colorScheme.error) { onRemovePin() }
    } else {
        SettingRow("Code de verrouillage", "6 à 12 chiffres, qui chiffrent aussi vos messages sur cet appareil", Icons.Filled.Lock) { onSetPin() }
        // Le code de l'appareil seul ouvre une clé du Keystore à partir d'Android 11.
        val deviceCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && model.biometrics?.deviceSecure == true
        val name = bio?.lowercase() ?: if (deviceCode) "le code de l'appareil" else null
        if (name != null) {
            SwitchRow(
                "Protéger mes messages par $name",
                model.vaultProtected,
                "Ils ne s'ouvrent qu'avec votre visage, votre empreinte ou le code de l'appareil",
                Icons.Filled.Shield,
            ) { on -> scope.launch { model.protectVault(on) } }
        }
    }
    SwitchRow(
        "Masquer pendant un enregistrement",
        model.hideWhenCaptured,
        "Captures, enregistrement, recopie ou partage de l'écran : Talka apparaît noir",
        Icons.Filled.VisibilityOff,
    ) { model.hideWhenCaptured = it }
    SectionFooter(securityFooter(model.hasPin, model.codeEncrypts, bio))
}

fun securityFooter(hasPin: Boolean, codeEncrypts: Boolean, bio: String?): String {
    if (!hasPin) {
        return "Vos messages sont chiffrés sur cet appareil par une clé que seul Talka peut lire. Avec un code, ils ne s'ouvrent plus sans lui, même pour quelqu'un qui copierait les fichiers. Dans le sélecteur d'apps, Talka montre toujours un écran neutre."
    }
    if (!codeEncrypts) {
        return "Tapez votre code une fois au prochain déverrouillage : il chiffrera aussi vos messages sur cet appareil."
    }
    val face = bio?.let { " (ou ${it.lowercase()})" } ?: ""
    return "Votre code chiffre aussi vos messages sur cet appareil : sans lui$face, ils ne s'ouvrent pas, même si quelqu'un copie les fichiers ou a l'appareil déverrouillé. Dans le sélecteur d'apps, Talka montre toujours un écran neutre."
}

// ----- Les lignes de réglage -----

/** Une ligne de réglage : une icône, un titre et une précision, comme dans le navigateur. */
@Composable
fun SettingRow(title: String, detail: String?, icon: ImageVector?, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, color = tint) },
        supportingContent = detail?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, null, tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun ActionRow(title: String, icon: ImageVector?, chevron: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = icon?.let { { Icon(it, null) } },
        trailingContent = if (chevron) ({ Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }) else null,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun SwitchRow(title: String, checked: Boolean, detail: String? = null, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Switch) { onChange(!checked) },
    )
}

/** Une page de plus : le titre, sa valeur, la flèche. */
@Composable
fun NavRow(title: String, value: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Un choix parmi plusieurs (le `Picker` de l'iPhone) : la valeur, et un menu. */
@Composable
fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, value: T, icon: ImageVector? = null, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(title) },
            leadingContent = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
            trailingContent = { Text(options.firstOrNull { it.first == value }?.second ?: "", color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            for ((v, label) in options) {
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { RadioButton(selected = v == value, onClick = null) },
                    onClick = {
                        open = false
                        onChange(v)
                    },
                )
            }
        }
    }
}

/** Plus ou moins (le `Stepper` de l'iPhone). */
@Composable
fun StepperRow(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Row {
                IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first) { Icon(Icons.Filled.Remove, "Moins") }
                IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last) { Icon(Icons.Filled.Add, "Plus") }
            }
        },
    )
}

/** Une confirmation (le `confirmationDialog` de l'iPhone), son action en rouge. */
@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

// ----- Les pages -----

@Composable
fun BlockedView(onBack: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    val list = e.contacts.values.filter { it.blocked }.sortedBy { e.displayName(it.code) }
    Page("Contacts bloqués", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (list.isEmpty()) item { EmptyState("Aucun contact bloqué", modifier = Modifier.padding(top = 40.dp)) }
            items(list, key = { it.code }) { c ->
                ListItem(
                    leadingContent = { EngineAvatar(e, c.code, 36.dp) },
                    headlineContent = { Text(e.displayName(c.code)) },
                    trailingContent = { OutlinedButton(onClick = { e.setBlocked(c.code, false) }) { Text("Débloquer") } },
                )
            }
        }
    }
}

/** Choisir un code PIN (deux fois). */
@Composable
fun PinSetup(onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(Modifier.fillMaxWidth()) { TextButton(onClick = onDismiss) { Text("Annuler") } }
                Text(if (first == null) "Choisissez un code PIN" else "Confirmez le code PIN", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    problem ?: "6 à 12 chiffres, demandés à l'ouverture de Talka.",
                    color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PinDots(pin.length)
                PinPad(pin, onPin = { pin = it }, enabled = !saving, onDone = {
                    val f = first
                    if (f != null) {
                        if (f == pin) {
                            val chosen = pin
                            saving = true
                            scope.launch {
                                model.setPin(chosen)
                                model.show("Code PIN activé")
                                onDismiss()
                            }
                        } else {
                            problem = "Les codes ne correspondent pas."
                            first = null
                        }
                    } else if (pin.length < TalkaCrypto.pinMin) {
                        problem = "6 chiffres au moins."
                    } else if (pin.toSet().size == 1 || "01234567890123".contains(pin) || "98765432109876".contains(pin)) {
                        problem = "Trop facile à deviner : choisissez-en un autre."
                    } else {
                        first = pin
                        problem = null
                    }
                    pin = ""
                })
            }
        }
    }
}

/**
 * « Gérer le stockage », comme WhatsApp (et le navigateur) : les
 * discussions qui gardent le plus de photos, documents et vocaux sur cet
 * appareil ; toucher en ouvre la fiche.
 */
@Composable
fun StorageView(onBack: () -> Unit, open: (SettingsPage) -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    val rows = remember(model.revision) { e.storageByChat() }
    Page("Gérer le stockage", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (rows.isEmpty()) {
                item {
                    Text(
                        "Aucune photo, aucun document, aucun vocal pour l'instant.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(rows, key = { it.id }) { r ->
                    ListItem(
                        leadingContent = { EngineAvatar(e, r.chat.id, 40.dp) },
                        headlineContent = { Text(e.title(r.chat), maxLines = 1) },
                        supportingContent = { Text(r.storage.detail) },
                        trailingContent = { Text(formatBytes(r.storage.bytes)) },
                        modifier = Modifier.clickable { open(SettingsPage.Info(r.chat.id, r.chat.group)) },
                    )
                }
                item { SectionFooter("Photos, documents et vocaux : ${formatBytes(rows.sumOf { it.storage.bytes })} sur cet appareil.") }
            }
        }
    }
}
