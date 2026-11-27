// Brocolis 🥦 dans l'app : les clés partagées avec l'app Brocolis, et les
// discussions qu'elles chiffrent, en plus de Talka. Le même réglage que le
// navigateur et l'iPhone : un téléphone et un ordinateur se lisent. Le
// portage de `BrocolisViews.swift`.

package talka.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import talka.app.LocalAppModel
import talka.app.TalkaColors
import talka.app.launch
import talka.kit.Brocolis
import talka.kit.BrocolisKey
import talka.kit.Engine
import talka.kit.Message

/**
 * Copie un secret : marqué sensible (Android ne le montre pas dans son
 * aperçu du presse-papiers), effacé au bout de deux minutes s'il y est
 * encore.
 */
fun copySecret(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("Talka", text)
    clip.description.extras = PersistableBundle().apply {
        if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) else putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    cm.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        try {
            val now = cm.primaryClip
            if (now != null && now.itemCount > 0 && now.getItemAt(0).text?.toString() == text) {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
        }
    }, 120_000)
}

const val brocolisAbout = "Brocolis chiffre vos messages une seconde fois, avec des clés que vous partagez avec vos proches dans l'app Brocolis 🥦. Talka les chiffre déjà de bout en bout ; seuls ceux qui ont la clé ouvrent cette seconde couche, ici ou dans l'app Brocolis (clavier, Partager)."
const val brocolisStrength = "Chaque clé : 256 bits tirés au hasard (AES-256-GCM) ; le base64 n'est que sa façon de s'écrire. Le point faible, c'est le partage : envoyez-la par Talka plutôt que par un canal en clair, et comparez son empreinte de vive voix. Sur Android, les clés sont gardées par le Keystore de l'appareil, pour cet appareil seulement."

/** L'empreinte d'une clé, à comparer de vive voix. */
@Composable
fun BrocolisFingerprint(key: String) {
    val fp = Brocolis.fingerprint(key) ?: ""
    Text(
        fp,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = "Empreinte $fp" },
    )
}

/** Réglages → Clés Brocolis 🥦 : ajouter, créer, exporter, renommer, renouveler, oublier. */
@Composable
fun BrocolisKeysView(onBack: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<BrocolisKey?>(null) }
    var newName by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<BrocolisKey?>(null) }
    var renewing by remember { mutableStateOf<BrocolisKey?>(null) }
    var exporting by remember { mutableStateOf<BrocolisKey?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }

    fun create() {
        val k = try {
            e.addBrocolisKey("", Brocolis.generateKey())
        } catch (x: Exception) {
            return
        }
        model.show("🥦 Clé créée : envoyez-la par Talka, ou exportez-la protégée")
        // Tout de suite son nom : pour qui est-elle ?
        newName = ""
        renaming = k
    }

    Page("Brocolis 🥦", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            // Ce que le moteur change (hors de l'état de Compose) : la liste suit `revision`.
            model.revision
            if (e.brocolisKeys.isEmpty()) {
                item { Text("Aucune clé pour l'instant.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
            items(e.brocolisKeys, key = { it.id }) { k ->
                ListItem(
                    leadingContent = { Text("🥦", fontSize = 24.sp) },
                    headlineContent = { Text(k.name) },
                    supportingContent = { BrocolisFingerprint(k.key) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { exporting = k }) { Icon(Icons.Filled.Share, "Exporter la clé") }
                            Box {
                                IconButton(onClick = { menu = k.id }) { Icon(Icons.Filled.MoreVert, "Plus d'options pour « ${k.name} »") }
                                DropdownMenu(expanded = menu == k.id, onDismissRequest = { menu = null }) {
                                    DropdownMenuItem(
                                        text = { Text("Renommer") },
                                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                        onClick = {
                                            menu = null
                                            newName = k.name
                                            renaming = k
                                        },
                                    )
                                    if (!k.retired) {
                                        DropdownMenuItem(
                                            text = { Text("Renouveler") },
                                            leadingIcon = { Icon(Icons.Filled.Autorenew, null) },
                                            onClick = {
                                                menu = null
                                                renewing = k
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Oublier", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            menu = null
                                            forgetting = k
                                        },
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.alpha(if (k.retired) 0.6f else 1f),
                )
            }
            item {
                SectionFooter(brocolisAbout)
                HorizontalDivider(Modifier.padding(top = 8.dp))
                ListItem(
                    headlineContent = { Text("Ajouter une clé", color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { adding = true },
                )
                ListItem(
                    headlineContent = { Text("Créer une clé", color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(Icons.Filled.Key, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { create() },
                )
                SectionFooter("Une clé créée ici est copiée : partagez-la par un canal sûr, ou envoyez-la par Talka depuis une discussion (⋮ → Chiffrer avec Brocolis). Touchez ⋮ à côté d'une clé pour la renommer, la renouveler ou l'oublier.\n\n$brocolisStrength")
            }
        }
    }
    if (adding) AddBrocolisKey(onDismiss = { adding = false })
    exporting?.let { k -> ExportBrocolisKey(k, onDismiss = { exporting = null }) }
    renaming?.let { k ->
        TextFieldDialog(
            title = "Renommer la clé",
            value = newName,
            onValue = { newName = it },
            label = "Nom",
            confirm = "Enregistrer",
            onConfirm = {
                renaming = null
                e.renameBrocolisKey(k.id, newName)
            },
            onDismiss = { renaming = null },
        )
    }
    forgetting?.let { k ->
        ConfirmDialog(
            title = "Oublier cette clé ?",
            message = "« ${k.name} » ne chiffrera plus rien. Les messages déjà ouverts le restent ; les nouveaux, à cette clé, resteront scellés.",
            confirm = "Oublier",
            onConfirm = {
                forgetting = null
                e.removeBrocolisKey(k.id)
            },
            onDismiss = { forgetting = null },
        )
    }
    renewing?.let { k ->
        AlertDialog(
            onDismissRequest = { renewing = null },
            title = { Text("Renouveler cette clé ?") },
            text = { Text("Une clé neuve remplace « ${k.name} » dans ses discussions et y est envoyée par Talka. L'ancienne garde l'historique lisible, mais ne chiffre plus rien.") },
            confirmButton = {
                TextButton(onClick = {
                    renewing = null
                    val n = try {
                        e.renewBrocolisKey(k.id)
                    } catch (x: Exception) {
                        0
                    }
                    model.show(if (n == 0) "🥦 Clé renouvelée : partagez la neuve avec vos proches" else "🥦 Clé renouvelée et envoyée dans $n discussion${if (n > 1) "s" else ""}")
                }) { Text("Renouveler") }
            },
            dismissButton = { TextButton(onClick = { renewing = null }) { Text("Annuler") } },
        )
    }
}

/** Ajouter une clé reçue : collée seule, ou toute l'invitation de l'app Brocolis. */
@Composable
fun AddBrocolisKey(onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    val clipboard = LocalClipboardManager.current
    var pasted by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val protectedKey = Brocolis.hasProtectedKey(pasted)

    fun add() {
        val e = model.engine ?: return
        model.launch {
            var key = pasted
            if (Brocolis.hasProtectedKey(pasted)) {
                opening = true
                val text = pasted
                val secret = phrase
                val opened = withContext(Dispatchers.Default) { Brocolis.unprotectKey(text, secret) }
                opening = false
                if (opened == null) {
                    error = "Phrase incorrecte, ou clé abîmée."
                    return@launch
                }
                key = opened
            }
            try {
                val k = e.addBrocolisKey(name, key)
                model.show("🥦 Clé « ${k.name} » ajoutée · empreinte ${Brocolis.fingerprint(k.key) ?: ""}")
                onDismiss()
            } catch (x: Exception) {
                error = x.message
            }
        }
    }

    Sheet(
        "Ajouter une clé",
        onDismiss,
        actions = { TextButton(onClick = { add() }, enabled = !opening && pasted.isNotBlank()) { Text("Ajouter") } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                label = { Text("La clé, ou toute l'invitation reçue") },
                minLines = 2,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            ListItem(
                headlineContent = { Text("Coller", color = MaterialTheme.colorScheme.primary) },
                leadingContent = { Icon(Icons.Filled.ContentPaste, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { clipboard.getText()?.text?.let { pasted = it } },
            )
            SectionFooter("Dans l'app Brocolis : copiez la clé (de préférence protégée par une phrase), ou partagez l'invitation reçue.")
            if (protectedKey) {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text("Phrase secrète") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SectionFooter("Cette clé est protégée : il faut la phrase qu'on vous a donnée à part.")
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom (par exemple : Maman, Travail)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        }
    }
}

/**
 * Sortir une clé de Talka. Recommandé : protégée par une phrase secrète (le
 * jeton copié ne dit rien de la clé sans elle). En clair : seulement après
 * l'empreinte, le visage ou le code de l'appareil.
 */
@Composable
fun ExportBrocolisKey(key: BrocolisKey, onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    var phrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    fun copyProtected() {
        working = true
        val k = key.key
        val p = phrase
        model.launch {
            val token = withContext(Dispatchers.Default) { Brocolis.protectKey(k, p) }
            working = false
            if (token == null) return@launch model.show("La protection a échoué.")
            copySecret(context, token)
            model.show("🔐 Clé protégée copiée : elle s'importe dans Talka ou l'app Brocolis avec la phrase")
            onDismiss()
        }
    }

    fun copyClear() {
        model.launch {
            val b = model.biometrics
            if (b != null && b.deviceSecure) {
                model.leavingOnPurpose()
                if (!b.authenticate("Copier la clé Brocolis en clair", null, deviceCredential = true)) return@launch
            }
            copySecret(context, key.key)
            model.show("Clé copiée en clair : ne l'envoyez que par un canal sûr")
            onDismiss()
        }
    }

    Sheet("Exporter la clé", onDismiss) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("🥦 ${key.name}") },
                trailingContent = { BrocolisFingerprint(key.key) },
            )
            SectionFooter("Le plus sûr : l'envoyer par Talka, depuis une discussion (⋮ → Chiffrer avec Brocolis). Pour l'app Brocolis ou un autre appareil : protégée par une phrase secrète, donnée à part, de vive voix.")
            SectionHeader("Protégée par une phrase (recommandé)")
            for ((label, value, set) in listOf(
                Triple("Phrase secrète (${Brocolis.phraseMin} caractères au moins)", phrase) { v: String -> phrase = v },
                Triple("Confirmez la phrase", again) { v: String -> again = v },
            )) {
                OutlinedTextField(
                    value = value,
                    onValueChange = set,
                    label = { Text(label) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val enough = phrase.codePointCount(0, phrase.length) >= Brocolis.phraseMin && phrase == again
            ListItem(
                headlineContent = {
                    Text(
                        if (working) "Protection…" else "Copier la clé protégée",
                        color = if (!working && enough) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingContent = { Icon(Icons.Filled.Lock, null, tint = if (!working && enough) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.clickable(enabled = !working && enough) { copyProtected() },
            )
            SectionHeader("En clair (déconseillé)")
            ListItem(
                headlineContent = { Text("Copier en clair", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Filled.ContentCopy, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { copyClear() },
            )
            SectionFooter("Qui la voit peut lire tous les messages qu'elle chiffre. Demande l'empreinte, le visage ou le code de l'appareil ; le presse-papiers la cache et s'efface au bout de deux minutes.")
        }
    }
}

/** Les clés qui chiffrent ce qui part dans une discussion (⋮ → Chiffrer avec Brocolis). */
@Composable
fun BrocolisChatSheet(chatId: String, onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    val selected = remember { mutableStateListOf<String>().apply { addAll(e.chat(chatId)?.brocolis ?: emptyList()) } }
    var managing by remember { mutableStateOf(false) }
    if (managing) {
        // « Gérer les clés », par-dessus la feuille.
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { managing = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize()) { BrocolisKeysView(onBack = { managing = false }) }
        }
    }

    /** Une clé neuve, au nom de la discussion : activée ici et envoyée par Talka. */
    fun createAndSend() {
        val title = e.chat(chatId)?.let { e.title(it) } ?: ""
        val k = try {
            e.addBrocolisKey(title, Brocolis.generateKey())
        } catch (x: Exception) {
            return
        }
        e.setChatBrocolis(chatId, (e.chat(chatId)?.brocolis ?: emptyList()) + k.id)
        e.shareBrocolisKey(chatId, k.id)
        model.show("🥦 Clé créée, envoyée par Talka et activée ici")
        onDismiss()
    }

    val keys = e.brocolisKeys.filter { !it.retired }
    Sheet(
        "Brocolis 🥦",
        onDismiss,
        actions = {
            TextButton(onClick = {
                val ids = e.brocolisKeys.map { it.id }.filter { it in selected }
                e.setChatBrocolis(chatId, ids)
                model.show(if (ids.isEmpty()) "Brocolis désactivé pour cette discussion" else "🥦 Brocolis activé pour cette discussion")
                onDismiss()
            }) { Text("Enregistrer") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (keys.isEmpty()) {
                Text("Ajoutez d'abord une clé, partagée avec vos correspondants.", Modifier.padding(16.dp))
                SectionFooter(brocolisAbout)
            } else {
                for (k in keys) {
                    val on = k.id in selected
                    ListItem(
                        headlineContent = { Text("🥦 ${k.name}") },
                        supportingContent = { BrocolisFingerprint(k.key) },
                        trailingContent = { Checkbox(checked = on, onCheckedChange = null) },
                        modifier = Modifier.clickable { if (on) selected.remove(k.id) else selected.add(k.id) },
                    )
                }
                SectionFooter("Ce qui part dans cette discussion sera chiffré avec les clés choisies, en plus de Talka. Écrivez ((comme ceci)) pour ne chiffrer qu'un passage. Plusieurs clés : un seul message, lisible par chacune.")
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            ListItem(
                headlineContent = { Text("Créer une clé et l'envoyer ici", color = MaterialTheme.colorScheme.primary) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Send, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { createAndSend() },
            )
            if (selected.isNotEmpty()) {
                ListItem(
                    headlineContent = { Text("Envoyer les clés cochées ici", color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(Icons.Filled.Key, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        for (id in keys.map { it.id }) if (id in selected) e.shareBrocolisKey(chatId, id)
                        model.show("🥦 Clé envoyée par Talka")
                        onDismiss()
                    },
                )
            }
            SectionFooter("Une clé envoyée par Talka voyage chiffrée de bout en bout ; vos correspondants l'ajoutent d'un geste. Comparez son empreinte de vive voix.")
            HorizontalDivider(Modifier.padding(top = 8.dp))
            ListItem(
                headlineContent = { Text("Gérer les clés") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                modifier = Modifier.clickable { managing = true },
            )
        }
    }
}

/**
 * La bulle d'une clé partagée par Talka : son nom, son empreinte, et
 * « Ajouter la clé » (qui l'active aussi dans la discussion).
 */
@Composable
fun BrocolisKeyCard(engine: Engine, message: Message) {
    val model = LocalAppModel.current
    model.revision
    val e = engine
    val m = message
    val have = e.sharedKey(m)
    Column(Modifier.widthIn(min = 220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🥦", fontSize = 24.sp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Clé Brocolis « ${m.name} »", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                BrocolisFingerprint(if (m.data.isEmpty()) have?.key ?: "" else m.data)
            }
        }
        val note = MaterialTheme.typography.labelSmall
        val grey = MaterialTheme.colorScheme.onSurfaceVariant
        when {
            m.from == e.me.code -> Text("Vous avez partagé cette clé. Comparez l'empreinte de vive voix.", style = note, color = grey)
            have != null -> Text("✓ Dans vos clés (« ${have.name} »)", style = note, color = grey)
            m.data.isEmpty() -> Text("Clé oubliée sur cet appareil.", style = note, color = grey)
            else -> Button(
                onClick = {
                    try {
                        val k = e.acceptBrocolisKey(m)
                        model.show("🥦 « ${k.name} » ajoutée et activée ici · empreinte ${Brocolis.fingerprint(k.key) ?: ""}")
                    } catch (x: Exception) {
                        model.show(x.message ?: "La clé n'a pas pu être ajoutée.")
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = TalkaColors.green),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Ajouter la clé") }
        }
    }
}
