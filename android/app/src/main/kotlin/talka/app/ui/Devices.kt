// Plusieurs appareils par compte (v4, docs/SECURITE.md §7.11 et §7.12) :
// Réglages → Appareils liés (la liste, renommer cet appareil, en retirer un,
// en lier un nouveau par son QR code), et, sur le nouvel appareil, l'attente
// de la liaison (le QR code à scanner, puis le code de vérification).
// Le portage de `Devices.swift`, avec le QR code de l'iPhone (`QR` dans
// `Common.swift`, `QRScanner` dans `Chats.swift`) : zxing le dessine et le
// lit, CameraX filme. Rien de Google Play Services.

package talka.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.TabletMac
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import talka.app.LocalAppModel
import talka.app.launch
import talka.kit.AccountDevice
import talka.kit.DeviceLink
import talka.kit.TalkaV4
import talka.kit.linkDevice
import talka.kit.maxDevices
import talka.kit.renameDevice
import talka.kit.unlinkDevice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// ----- Réglages → Appareils liés -----

/** Les appareils liés au compte (Réglages → Appareils). */
@Composable
fun LinkedDevicesView(onBack: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<AccountDevice?>(null) }
    var linking by remember { mutableStateOf(false) }
    val list = e.devices.list
    Page("Appareils liés", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            // Ce que le moteur change (hors de l'état de Compose) : la liste suit `revision`.
            model.revision
            items(list, key = { it.dev }) { d ->
                DeviceRow(d, mine = d.dev == e.dev) {
                    if (d.dev == e.dev) {
                        newName = d.name
                        renaming = true
                    } else {
                        removing = d
                    }
                }
            }
            item {
                SectionFooter("Chaque appareil a ses propres clés : vos contacts chiffrent chaque message pour chacun d'eux. Un appareil retiré efface tout ce que Talka y garde.")
                SectionHeader("Lier un appareil")
                val full = list.size >= maxDevices
                ListItem(
                    headlineContent = {
                        Text("Scanner son QR code", color = if (full) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    },
                    leadingContent = { Icon(Icons.Filled.QrCodeScanner, null, tint = if (full) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable(enabled = !full) { linking = true },
                )
                SectionFooter("Pour en lier un : ouvrez Talka sur le nouvel appareil et choisissez « J'ai déjà Talka sur un autre appareil ». Un QR code s'affiche. Six appareils au plus : téléphone Android, iPhone, iPad ou navigateur.")
            }
        }
    }
    if (renaming) {
        TextFieldDialog(
            title = "Renommer cet appareil",
            value = newName,
            onValue = { newName = it },
            label = "Nom",
            confirm = "Enregistrer",
            onConfirm = {
                renaming = false
                e.renameDevice(e.dev, newName)
            },
            onDismiss = { renaming = false },
        )
    }
    removing?.let { d ->
        ConfirmDialog(
            title = "Retirer cet appareil ?",
            message = "Il effacera tout ce que Talka y garde, et ne recevra plus vos messages.",
            confirm = "Retirer « ${d.name} »",
            onConfirm = {
                removing = null
                model.launch { e.unlinkDevice(d.dev) }
            },
            onDismiss = { removing = null },
        )
    }
    if (linking) LinkDeviceSheet(onDismiss = { linking = false })
}

/** Une ligne de la liste : l'appareil, quand il a été lié, et son bouton. */
@Composable
fun DeviceRow(device: AccountDevice, mine: Boolean, action: () -> Unit) {
    val name = device.name.lowercase(Locale.ROOT)
    val icon = when {
        "ipad" in name || "tablet" in name -> Icons.Filled.TabletMac
        "iphone" in name -> Icons.Filled.PhoneIphone
        "mac" in name || "windows" in name || "linux" in name -> Icons.Filled.Laptop
        "android" in name || "pixel" in name || "samsung" in name || "galaxy" in name -> Icons.Filled.PhoneAndroid
        else -> Icons.Filled.Devices
    }
    ListItem(
        leadingContent = { Icon(icon, null, Modifier.width(32.dp), tint = talka.app.TalkaColors.green) },
        headlineContent = { Text(device.name.ifEmpty { "Appareil" }) },
        supportingContent = {
            Text(
                if (mine) "Cet appareil" else "Lié le ${Dates.medium(device.at)}",
                color = if (mine) talka.app.TalkaColors.dark else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            if (mine) {
                TextButton(onClick = action) { Text("Renommer") }
            } else {
                TextButton(onClick = action) { Text("Retirer", color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}

/** « 27 nov. 2026 » : la date abrégée de l'iPhone (`.abbreviated`). */
private fun Dates.medium(ms: Double): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.FRANCE)
        .format(Instant.ofEpochMilli(ms.toLong()).atZone(ZoneId.systemDefault()))

/** Le code de vérification d'une liaison, le même sur les deux appareils. */
@Composable
fun VerificationCode(code: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Code de vérification", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(code, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = talka.app.TalkaColors.dark)
    }
}

/**
 * Lier un nouvel appareil : scanner son QR code (ou coller son code de
 * liaison) ; notre compte lui part chiffré, pour lui seul.
 */
@Composable
fun LinkDeviceSheet(onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var verif by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun link(s: String) {
        val e = model.engine ?: return
        if (busy) return
        busy = true
        error = null
        model.launch {
            try {
                verif = e.linkDevice(s)
            } catch (x: kotlinx.coroutines.CancellationException) {
                throw x
            } catch (x: Exception) {
                error = x.message ?: "La liaison a échoué."
            }
            busy = false
        }
    }

    fun scanned(found: String) {
        val t = found.trim()
        if (!t.startsWith(TalkaV4.linkPrefix)) {
            error = "Ce QR code n'est pas un code de liaison Talka."
            return
        }
        text = t
        link(t)
    }

    Sheet(
        "Lier un appareil",
        onDismiss = { if (!busy) onDismiss() },
        actions = {
            when {
                verif != null -> TextButton(onClick = onDismiss) { Text("OK") }
                busy -> CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                else -> TextButton(onClick = { link(text) }, enabled = TalkaV4.LinkOffer.parse(text) != null) { Text("Lier") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            val v = verif
            if (v != null) {
                ListItem(
                    headlineContent = { Text("Compte envoyé au nouvel appareil", color = talka.app.TalkaColors.green) },
                    leadingContent = { Icon(Icons.Filled.Verified, null, tint = talka.app.TalkaColors.green) },
                )
                VerificationCode(v)
                SectionFooter("Le nouvel appareil doit afficher le même code de vérification. S'il est différent, retirez-le (Réglages → Appareils liés).")
            } else {
                SectionHeader("Le nouvel appareil")
                ListItem(
                    headlineContent = { Text("Scanner le QR code", color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(Icons.Filled.QrCodeScanner, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { scanning = true },
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("ou collez le code de liaison") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = "Code de liaison" },
                )
                ListItem(
                    headlineContent = { Text("Coller", color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(Icons.Filled.ContentPaste, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { clipboard.getText()?.text?.let { text = it } },
                )
                SectionFooter("Sur le nouvel appareil : « J'ai déjà Talka sur un autre appareil », puis visez son QR code (ou « Copier le code de liaison », et collez-le ici). Vos contacts, vos discussions et vos messages lui partent chiffrés, pour lui seul.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
    if (scanning) {
        QRScanner(
            onFound = { found ->
                scanning = false
                scanned(found)
            },
            onDismiss = { scanning = false },
        )
    }
}

// ----- L'accueil → « J'ai déjà Talka sur un autre appareil » -----

/**
 * « J'ai déjà Talka sur un autre appareil » : ce nouvel appareil montre son
 * QR code (`model.makeDeviceLink()`, et son code de liaison, à copier), et
 * attend que l'autre le lie ; puis le code de vérification, et, une fois
 * « Commencer » touché, `model.finishLinking()`.
 */
@Composable
fun LinkWaitView(onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    var link by remember { mutableStateOf<DeviceLink?>(null) }
    var verif by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start() {
        error = null
        link?.cancel()
        val l = model.makeDeviceLink()
        if (l == null) {
            error = "Pas de relais : réessayez."
            return
        }
        l.onDone = { result ->
            result.fold(onSuccess = { verif = it }, onFailure = { error = it.message ?: "La liaison a échoué." })
        }
        l.start()
        link = l
    }

    LaunchedEffect(Unit) { if (link == null) start() }
    val current by rememberUpdatedState(link)
    val done by rememberUpdatedState(verif)
    DisposableEffect(Unit) { onDispose { if (done == null) current?.cancel() } }

    Sheet(
        "Lier cet appareil",
        // Une fois lié, on ne revient plus en arrière : « Commencer ».
        onDismiss = {
            if (verif == null) {
                link?.cancel()
                onDismiss()
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val v = verif
                val l = link
                when {
                    v != null -> {
                        Icon(Icons.Filled.Verified, null, Modifier.padding(top = 20.dp).size(56.dp), tint = talka.app.TalkaColors.green)
                        Text("Appareil lié", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Vérifiez que l'autre appareil affiche le même code :",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        VerificationCode(v)
                        Button(
                            onClick = {
                                onDismiss()
                                model.finishLinking()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Commencer", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp)) }
                    }
                    l != null -> {
                        Text(
                            "Sur l'appareil où Talka est déjà ouvert (téléphone, iPhone, iPad, navigateur) : Réglages → Appareils liés → Scanner son QR code, puis visez celui-ci.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        QrImage(l.text, Modifier.widthIn(max = 260.dp).fillMaxWidth(), label = "QR code de liaison")
                        OutlinedButton(onClick = {
                            copyText(context, l.text)
                            model.show("Code de liaison copié")
                        }) {
                            Icon(Icons.Filled.ContentCopy, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Copier le code de liaison")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("En attente de l'autre appareil…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "Votre compte arrive chiffré pour cet appareil seul : le relais ne peut pas le lire.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    else -> CircularProgressIndicator(Modifier.padding(top = 40.dp))
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    TextButton(onClick = { start() }) { Text("Réessayer") }
                }
            }
        }
    }
}

// ----- Le QR code : le dessiner, le lire -----

/** Le QR code d'un texte, dessiné et lu par zxing (comme `QR` de l'iPhone : correction « M »). */
object QR {
    /** Les modules du QR code (une marge d'un module, le fond blanc fait le reste). */
    fun matrix(text: String): BitMatrix? =
        try {
            QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                    EncodeHintType.MARGIN to 1,
                ),
            )
        } catch (e: WriterException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    /** L'image du QR code, `scale` pixels par module (net, sans lissage). */
    fun bitmap(text: String, scale: Int = 10): Bitmap? {
        val m = matrix(text) ?: return null
        val w = m.width * scale
        val h = m.height * scale
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) pixels[row + x] = if (m[x / scale, y / scale]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Le texte d'un QR code dans une image (une capture, une photo). */
    fun decode(image: Bitmap): String? {
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h)
        image.getPixels(pixels, 0, w, 0, 0, w, h)
        return decode(RGBLuminanceSource(w, h, pixels))
    }

    /**
     * Le texte d'un QR code dans une image de la caméra : sa luminance (le
     * plan Y d'une image YUV), ligne par ligne (`rowStride` octets).
     */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? =
        decode(PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false))

    private val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")

    private fun decode(source: LuminanceSource): String? {
        val reader = QRCodeReader()
        for (s in listOf(source, source.invert())) {
            try {
                return reader.decode(BinaryBitmap(HybridBinarizer(s)), hints).text
            } catch (e: ReaderException) {
                reader.reset()
            }
        }
        return null
    }
}

/** Le QR code d'un texte (un lien d'invitation, un code de liaison), sur fond blanc. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier, label: String = "QR code de votre code Talka") {
    val bitmap = remember(text) { QR.bitmap(text)?.asImageBitmap() }
    Box(
        modifier
            .aspectRatio(1f)
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(12.dp)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
        } else {
            Text("QR code indisponible", color = Color.Gray)
        }
    }
}

/**
 * Le lecteur de QR code (la caméra, par CameraX ; chaque image lue par
 * zxing) : `onFound` reçoit le texte lu, une seule fois. La permission de
 * la caméra est demandée au premier usage.
 */
@Composable
fun QRScanner(onFound: (String) -> Unit, onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    var granted by remember { mutableStateOf(cameraGranted(context)) }
    var refused by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        refused = !ok
    }
    LaunchedEffect(Unit) {
        if (!granted && cameraAvailable(context)) {
            model.leavingOnPurpose()
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    Sheet("Scanner un QR code", onDismiss) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            when {
                !cameraAvailable(context) -> Text("Caméra indisponible", color = Color.White)
                granted -> {
                    CameraQrReader(onFound)
                    // Le cadre où viser.
                    Box(
                        Modifier
                            .fillMaxWidth(0.7f)
                            .aspectRatio(1f)
                            .clearAndSetSemantics { contentDescription = "Visez le QR code" },
                    ) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            drawRoundRect(
                                Color.White,
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()),
                            )
                        }
                    }
                }
                refused -> {
                    Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Autorisez l'appareil photo de Talka dans les réglages de l'appareil.",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = {
                            model.leavingOnPurpose()
                            openAppSettings(context)
                        }) { Text("Ouvrir les réglages") }
                    }
                }
                else -> CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

private fun cameraGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
    }
}

/** L'aperçu de la caméra arrière, et la lecture de ses images (la dernière seulement). */
@Composable
private fun CameraQrReader(onFound: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val found by rememberUpdatedState(onFound)
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycle) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val main = ContextCompat.getMainExecutor(context)
        future.addListener({
            val p = try {
                future.get()
            } catch (e: Exception) {
                return@addListener
            }
            provider = p
            val shown = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                val text = if (done.get()) null else readQr(image)
                image.close()
                if (text != null && done.compareAndSet(false, true)) {
                    main.execute {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        found(text)
                    }
                }
            }
            try {
                p.unbindAll()
                p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, shown, analysis)
            } catch (e: Exception) {
                // Pas de caméra arrière : celle de devant.
                try {
                    p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_FRONT_CAMERA, shown, analysis)
                } catch (e: Exception) {
                }
            }
        }, main)
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
}

/** Le texte d'un QR code dans une image de la caméra (son plan Y). */
private fun readQr(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return QR.decode(bytes, image.width, image.height, plane.rowStride)
}
