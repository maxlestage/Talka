// L'accueil (créer son code, ou restaurer une sauvegarde) et le verrou (PIN,
// empreinte ou visage).

package talka.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import talka.app.LocalAppModel
import talka.app.Logo
import talka.app.TalkaColors
import talka.kit.StoreVault
import talka.kit.TalkaCrypto
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

@Composable
fun WelcomeView() {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf("") }
    var backup by remember { mutableStateOf<ByteArray?>(null) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var linking by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    val pickPhoto = rememberPhotoPicker { bitmap -> avatar = Images.avatarDataURL(bitmap) ?: "" }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                backup = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
    }

    fun create() {
        val n = name.trimSpaces()
        if (n.isEmpty()) return
        model.createAccount(n.firstChars(40), avatar)
    }

    fun restore() {
        val data = backup ?: return
        backup = null
        val pw = password
        password = ""
        restoring = true
        scope.launch {
            try {
                model.restore(data, pw)
                model.show("Sauvegarde restaurée ✓")
            } catch (e: Exception) {
                error = e.message ?: "La sauvegarde n'a pas pu être restaurée."
            }
            restoring = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Logo(88.dp, Modifier.padding(top = 40.dp))
        Column(Modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bienvenue sur Talka", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                buildAnnotatedString {
                    append("Pas de numéro de téléphone : vous recevez un code, comme ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("K7Q2M-X9PRT") }
                    append(". Donnez-le à vos proches pour discuter, chiffré de bout en bout.")
                },
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .size(110.dp)
                .clickable(onClickLabel = "Choisir une photo") { pickPhoto() }
                .semantics { contentDescription = "Choisir une photo" },
        ) {
            if (avatar.isEmpty()) {
                Box(Modifier.size(110.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PhotoCamera, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Avatar("", name, avatar, size = 110.dp)
            }
            Icon(
                Icons.Filled.AddCircle,
                null,
                Modifier.align(Alignment.BottomEnd).size(32.dp).background(MaterialTheme.colorScheme.background, CircleShape),
                tint = TalkaColors.green,
            )
        }
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Votre nom") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { create() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Visible par vos contacts. Vous pourrez le changer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = { create() },
            enabled = name.trimSpaces().isNotEmpty() && !restoring,
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
        ) {
            Text("Créer mon code", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp))
        }
        TextButton(onClick = { linking = true }) { Text("J'ai déjà Talka sur un autre appareil") }
        TextButton(onClick = {
            model.leavingOnPurpose()
            importer.launch(arrayOf("*/*"))
        }) { Text("Restaurer une sauvegarde") }
        Row(Modifier.widthIn(max = 520.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Lock, null, Modifier.size(14.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Tout reste sur cet appareil. Le serveur ne fait que passer des messages chiffrés.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (linking) LinkWaitView(onDismiss = { linking = false })
    if (backup != null) {
        PasswordDialog(
            title = "Mot de passe de la sauvegarde",
            message = "Cet appareil remplacera celui d'où vient la sauvegarde : s'il sert encore, il sera retiré du compte. Pour ajouter un appareil, liez-le plutôt par son QR code.",
            password = password,
            onPassword = { password = it },
            placeholder = "Mot de passe",
            confirm = "Restaurer",
            onConfirm = { restore() },
            onDismiss = {
                backup = null
                password = ""
            },
        )
    }
}

/** Une alerte qui demande un mot de passe (sauvegarde). */
@Composable
fun PasswordDialog(
    title: String,
    message: String,
    password: String,
    onPassword: (String) -> Unit,
    placeholder: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                OutlinedTextField(
                    value = password,
                    onValueChange = onPassword,
                    label = { Text(placeholder) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

// ----- Le verrou -----

/**
 * La base protégée par la biométrie : rien ne s'ouvre sans l'empreinte, le
 * visage (ou le code de l'appareil).
 */
@Composable
fun VaultLockView() {
    val model = LocalAppModel.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Logo(70.dp)
        Text("Talka est verrouillé", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Vos messages sont chiffrés sur cet appareil et ne s'ouvrent qu'avec ${model.biometryName?.let { "votre ${it.lowercase()}" } ?: "votre empreinte, votre visage"} ou le code de l'appareil.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { scope.launch { model.unlockVault() } }, modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth()) {
            Icon(Icons.Filled.Fingerprint, null)
            Text("Déverrouiller", Modifier.padding(start = 8.dp))
        }
    }
    LaunchedEffect(Unit) { model.unlockVault() }
}

/**
 * Le verrou : le code de Talka (ou la biométrie). Talka fermé, sa base
 * chiffrée par le code : le code ouvre d'abord la clé de la base (`codeLocked`).
 */
@Composable
fun LockView() {
    val model = LocalAppModel.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf(false) }
    // Les essais manqués survivent à un redémarrage de l'app.
    var fails by remember { mutableIntStateOf(model.lockFails) }
    var until by remember { mutableLongStateOf(model.lockUntil) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val shake = remember { Animatable(0f) }

    // La base n'est pas encore ouverte : le code ouvre sa clé.
    val cold = model.codeLocked
    // Un code d'avant cette version : tapé une fois, il chiffrera la base
    // (la biométrie attend jusque-là).
    val mustType = !cold && !model.codeEncrypts
    val faceAvailable = if (cold) StoreVault.hasFaceUnlock else (model.faceID && !mustType)

    suspend fun biometry() {
        if (cold) model.unlockColdWithFace() else if (!mustType) model.unlockWithBiometry()
    }

    fun check() {
        if (System.currentTimeMillis() < until || checking) return
        val entered = pin
        pin = ""
        checking = true
        scope.launch {
            val ok = if (cold) model.unlock(entered) else model.checkPin(entered)
            checking = false
            if (ok) {
                model.locked = false
                fails = 0
            } else {
                fails += 1
                // Choisi dans les réglages : au dixième code faux, tout part.
                if (model.wipeAfterFails && fails >= 10) {
                    model.wipe()
                    model.show("10 codes faux : Talka a tout effacé sur cet appareil.")
                    return@launch
                }
                if (fails >= 5) until = System.currentTimeMillis() + (min(300.0, 15 * 2.0.pow(fails - 5)) * 1000).toLong()
                wrong = true
                shake.snapTo(0f)
                shake.animateTo(1f, tween(400))
            }
            model.lockFails = fails
            model.lockUntil = if (fails == 0) 0 else until
        }
    }

    LaunchedEffect(Unit) { biometry() }
    // Le retour d'Android ne passe pas sous le verrou : Talka passe à l'arrière-plan.
    val activity = LocalContext.current as? android.app.Activity
    BackHandler { activity?.moveTaskToBack(true) }
    LaunchedEffect(until) {
        while (System.currentTimeMillis() < until) {
            now = System.currentTimeMillis()
            delay(250)
        }
        now = System.currentTimeMillis()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Logo(70.dp, Modifier.padding(top = 30.dp))
            Text("Talka est verrouillé", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            PinDots(pin.length, Modifier.offset(x = (10 * sin(shake.value * Math.PI * 6)).dp))
            val wait = ceil((until - now) / 1000.0).toInt()
            val text = when {
                wait > 0 -> "Trop d'essais : réessayez dans $wait s"
                checking -> "Vérification…"
                wrong -> "Code incorrect"
                mustType -> "Entrez votre code une fois : il chiffrera désormais vos messages."
                else -> "Entrez votre code"
            }
            Text(
                text,
                textAlign = TextAlign.Center,
                color = if (wrong || wait > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PinPad(pin, onPin = {
                pin = it
                wrong = false
            }, onDone = { check() }, enabled = !checking)
            val bio = if (faceAvailable) model.biometryName else null
            if (bio != null) {
                TextButton(onClick = { scope.launch { biometry() } }) {
                    Icon(Icons.Filled.Fingerprint, null)
                    Text(bio, Modifier.padding(start = 8.dp))
                }
            }
            TextButton(onClick = { forgot = true }) {
                Text("Code oublié ?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (forgot) {
        AlertDialog(
            onDismissRequest = { forgot = false },
            title = { Text("Code oublié") },
            text = { Text("Sans lui, vos messages ne s'ouvrent plus sur cet appareil. Vous pouvez tout effacer, puis restaurer une sauvegarde.") },
            confirmButton = {
                TextButton(onClick = {
                    forgot = false
                    model.wipe()
                }) { Text("Tout effacer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { forgot = false }) { Text("Annuler") } },
        )
    }
}

@Composable
fun PinDots(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { contentDescription = "$count chiffres" },
        horizontalArrangement = Arrangement.spacedBy(if (count > 8) 9.dp else 14.dp),
    ) {
        repeat(maxOf(4, count)) { i ->
            Box(
                Modifier.size(13.dp).background(
                    if (i < count) TalkaColors.green else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape,
                ),
            )
        }
    }
}

/** Un clavier numérique : 4 à 12 chiffres, puis OK (6 au moins pour un nouveau code). */
@Composable
fun PinPad(pin: String, onPin: (String) -> Unit, onDone: () -> Unit, enabled: Boolean = true) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("del", "0", "ok"))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                for (key in row) {
                    when (key) {
                        "del" -> Box(
                            Modifier
                                .size(72.dp)
                                .clickable(enabled = enabled, onClickLabel = "Effacer") { if (pin.isNotEmpty()) onPin(pin.dropLast(1)) }
                                .semantics { contentDescription = "Effacer" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.AutoMirrored.Filled.Backspace, null) }
                        "ok" -> Box(
                            Modifier.size(72.dp).clickable(enabled = enabled && pin.length >= 4) { onDone() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "OK",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (pin.length >= 4) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                        }
                        else -> Box(
                            Modifier
                                .size(72.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .clickable(enabled = enabled) { if (pin.length < TalkaCrypto.pinMax) onPin(pin + key) },
                            contentAlignment = Alignment.Center,
                        ) { Text(key, fontSize = 28.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }
    }
}
