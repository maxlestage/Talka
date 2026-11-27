// Ce qui sert partout : avatars, images en adresses `data:`, texte des
// messages, dates, sons, notifications, photos (galerie et appareil).
// Le QR code est dans `Devices.kt`.

package talka.app.ui

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.media.AudioManager
import android.media.ExifInterface
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import talka.app.LocalAppModel
import talka.app.MainActivity
import talka.app.TalkaColors
import talka.app.sys.SharedFiles
import talka.kit.Brocolis
import talka.kit.Engine
import talka.kit.TalkaCode
import talka.kit.WidgetData
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.BreakIterator
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ----- Avatars -----

object Avatars {
    /**
     * Les dix couleurs du navigateur (`COLORS` dans `client/src/ui.rs`), dans
     * le même ordre : un contact a la même couleur partout.
     */
    val colors = listOf(
        Color(0xFFE542A3), Color(0xFF1F7AEC), Color(0xFFD36B00), Color(0xFF02A698), Color(0xFF7F66FF),
        Color(0xFFC4532D), Color(0xFF008069), Color(0xFFA62C71), Color(0xFF5E47DE), Color(0xFFB7820B),
    )

    /** La couleur d'un code : le même calcul que l'iPhone (sur 32 bits non signés). */
    fun color(id: String): Color {
        var h = 0
        var i = 0
        while (i < id.length) {
            val cp = id.codePointAt(i)
            h = h * 31 + cp
            i += Character.charCount(cp)
        }
        return colors[(h.toUInt() % colors.size.toUInt()).toInt()]
    }

    /** L'initiale d'un nom (sans le « ~ » des noms choisis par le contact). */
    fun initial(name: String): String {
        val t = name.trim('~', ' ')
        if (t.isEmpty()) return ""
        val it = BreakIterator.getCharacterInstance(Locale.FRANCE)
        it.setText(t)
        val end = it.next().takeIf { it != BreakIterator.DONE } ?: t.length
        return t.substring(0, end).uppercase(Locale.FRANCE)
    }
}

/** Une photo, sinon l'initiale sur une couleur tirée du code. */
@Composable
fun Avatar(id: String, name: String, image: String = "", group: Boolean = false, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val bitmap = remember(image) { Images.fromDataURL(image) }
    Box(
        modifier.size(size).clip(CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(bitmap, null, Modifier.size(size), contentScale = ContentScale.Crop)
            group -> {
                Box(Modifier.size(size).background(Color(0xFFC7C7CC)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Groups, null, Modifier.size(size * 0.5f), tint = Color.White)
                }
            }
            else -> {
                Box(Modifier.size(size).background(Avatars.color(id)), contentAlignment = Alignment.Center) {
                    Text(
                        Avatars.initial(name),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (size.value * 0.42f).sp,
                    )
                }
            }
        }
    }
}

/** L'avatar d'un code ou d'une discussion. */
@Composable
fun EngineAvatar(e: Engine, id: String, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    // Ce qu'il montre vient du moteur (hors de l'état de Compose) : il suit `revision`.
    LocalAppModel.current.revision
    val c = e.chat(id)
    if (c != null && c.group) {
        Avatar(id, c.name, c.avatar, group = true, size = size, modifier = modifier)
    } else {
        val name = if (id == e.me.code) e.profile.name else e.displayName(id)
        Avatar(id, name, e.avatarOf(id), size = size, modifier = modifier)
    }
}

/**
 * L'avatar avec la pastille « en ligne » : verte si le contact est
 * connecté, grise sinon. Ni pour un groupe, ni pour soi, ni pour un
 * contact bloqué, ni si elle est désactivée dans les réglages.
 */
@Composable
fun PresenceAvatar(e: Engine, id: String, size: Dp = 48.dp) {
    LocalAppModel.current.revision
    val shown = e.settings.presenceDot && id != e.me.code && !e.isGroup(id) && !e.blocked(id)
    val online = e.presence[id]?.online ?: false
    Box {
        EngineAvatar(e, id, size)
        if (shown) {
            PresenceDot(online, max(10f, size.value * 0.27f).dp, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
fun PresenceDot(online: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val green = TalkaColors.accent
    Box(
        modifier
            .size(size)
            .then(if (online) Modifier.shadow(3.dp, CircleShape, ambientColor = green, spotColor = green) else Modifier)
            .background(if (online) green else Color(0xFFC7C7CC), CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
            .semantics { contentDescription = if (online) "En ligne" else "Hors ligne" },
    )
}

// ----- Images et fichiers en adresses `data:` -----

/** `data:<mime>;base64,…`. */
fun ByteArray.dataURL(mime: String): String = "data:$mime;base64,${Base64.encodeToString(this, Base64.NO_WRAP)}"

/** Le contenu d'une adresse `data:…;base64,…`. */
fun dataOfURL(url: String): ByteArray? {
    if (!url.startsWith("data:")) return null
    val comma = url.indexOf(',')
    if (comma < 0) return null
    return try {
        Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        null
    }
}

object Images {
    /**
     * Les images déjà décodées, par leur adresse `data:`. Un avatar ou une
     * photo se redessine à chaque mise à jour de l'écran : sans cela, le
     * base64 et le JPEG seraient relus à chaque fois. Bornée par le poids
     * des pixels.
     */
    private val decoded = object : LruCache<String, ImageBitmap>(64 shl 20) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private val bubbles = object : LruCache<String, ImageBitmap>(48 shl 20) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun fromDataURL(url: String): ImageBitmap? {
        if (url.isEmpty()) return null
        decoded.get(url)?.let { return it }
        val data = dataOfURL(url) ?: return null
        val image = BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() ?: return null
        decoded.put(url, image)
        return image
    }

    /**
     * Une photo pour une bulle de discussion : au plus 1 020 pixels de côté,
     * au lieu des 1 600 de la photo entière. La photo entière reste celle
     * qu'on ouvre.
     */
    fun bubble(url: String): ImageBitmap? {
        if (url.isEmpty()) return null
        bubbles.get(url)?.let { return it }
        val data = dataOfURL(url) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1020) sample *= 2
        val image = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.asImageBitmap() ?: return fromDataURL(url)
        bubbles.put(url, image)
        return image
    }

    /** Réduite (comme dans le navigateur) et sans métadonnées : (JPEG, largeur, hauteur). */
    fun compressed(image: Bitmap, max: Int = 1600, quality: Float = 0.82f): Triple<String, Int, Int>? {
        val scale = min(1f, max.toFloat() / max(image.width, image.height))
        val w = (image.width * scale).roundToInt().coerceAtLeast(1)
        val h = (image.height * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(image, null, Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        val jpeg = ByteArrayOutputStream()
        if (!out.compress(Bitmap.CompressFormat.JPEG, (quality * 100).roundToInt(), jpeg)) return null
        return Triple(jpeg.toByteArray().dataURL("image/jpeg"), w, h)
    }

    /** Une petite photo carrée (profil, groupe). */
    fun avatarDataURL(image: Bitmap, side: Int = 160): String? {
        val s = min(image.width, image.height)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val src = Rect((image.width - s) / 2, (image.height - s) / 2, (image.width + s) / 2, (image.height + s) / 2)
        Canvas(out).drawBitmap(image, src, Rect(0, 0, side, side), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        val jpeg = ByteArrayOutputStream()
        if (!out.compress(Bitmap.CompressFormat.JPEG, 80, jpeg)) return null
        return jpeg.toByteArray().dataURL("image/jpeg")
    }

    /** Une image choisie (galerie, fichiers), redressée selon son orientation. */
    fun load(context: Context, uri: Uri): Bitmap? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            null
        }

    /**
     * La photo que l'appareil photo vient d'écrire (`ok` : prise, pas
     * annulée), puis le fichier effacé. Décodée à `max` pixels de côté au
     * moins (pas les 12 mégapixels entiers en mémoire), redressée selon son
     * EXIF (l'`imageOrientation` qu'applique l'iPhone).
     */
    fun takenPhoto(file: File, ok: Boolean, max: Int = 1600): Bitmap? =
        try {
            if (ok) decodeFile(file, max) else null
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            file.delete()
        }

    private fun decodeFile(file: File, max: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val image = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = try {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> m.apply { setRotate(90f) }.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> m.apply { setRotate(-90f) }.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return image
        }
        return Bitmap.createBitmap(image, 0, 0, image.width, image.height, m, true)
    }
}

/**
 * La feuille de partage d'Android pour un fichier (`UIActivityViewController`
 * de l'iPhone) : `share(octets, nom, mime)`. Le fichier passe par le
 * FileProvider (`SharedFiles`), lisible par la seule application choisie.
 */
@Composable
fun rememberFileSharer(): (ByteArray, String, String) -> Unit {
    val model = LocalAppModel.current
    val context = LocalContext.current
    return remember(model, context) {
        { data, name, mime ->
            try {
                val file = SharedFiles.write(context, data, name)
                val intent = SharedFiles.shareIntent(SharedFiles.uri(context, file), mime)
                // Depuis l'activité (une photo en grand est dans sa propre fenêtre).
                val activity = context.findActivity()
                if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                model.leavingOnPurpose()
                (activity ?: context).startActivity(intent)
            } catch (x: Exception) {
                model.show("Ce fichier n'a pas pu être partagé.")
            }
        }
    }
}

/** L'activité derrière un contexte (celui d'une fenêtre, d'un thème). */
tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

// ----- Photos : la galerie et l'appareil -----

/** Choisir une photo de la galerie ; `onPicked` la reçoit décodée. */
@Composable
fun rememberPhotoPicker(onPicked: (Bitmap) -> Unit): () -> Unit {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { Images.load(context, uri) }
                if (bitmap != null) onPicked(bitmap)
            }
        }
    }
    return {
        model.leavingOnPurpose()
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/** L'appareil a-t-il une caméra ? */
fun cameraAvailable(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * Prendre une photo avec l'appareil, en pleine taille (le `UIImagePickerController`
 * de l'iPhone) : l'appareil photo l'écrit dans un fichier du FileProvider
 * (`SharedFiles`), lu puis effacé aussitôt. `onPhoto` la reçoit redressée,
 * réduite à 1 600 pixels de côté au plus (assez pour `Images.compressed`).
 */
@Composable
fun rememberCameraPicker(onPhoto: (Bitmap) -> Unit): () -> Unit {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Le fichier de la photo : gardé si Android recrée l'écran pendant que l'appareil photo est ouvert.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pending?.let { File(it) } ?: return@rememberLauncherForActivityResult
        pending = null
        scope.launch {
            val photo = withContext(Dispatchers.IO) { Images.takenPhoto(file, ok) }
            if (photo != null) onPhoto(photo)
        }
    }
    fun open() {
        val file = try {
            SharedFiles.newCameraFile(context)
        } catch (e: Exception) {
            null
        }
        if (file == null) {
            model.show("Appareil photo indisponible")
            return
        }
        pending = file.path
        model.leavingOnPurpose()
        try {
            camera.launch(SharedFiles.uri(context, file))
        } catch (e: Exception) {
            pending = null
            file.delete()
            model.show("Appareil photo indisponible")
        }
    }
    // Si l'application déclare la caméra (les appels), Android exige la permission.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) open() else model.show("Autorisez l'appareil photo de Talka dans les réglages de l'appareil.")
    }
    return {
        if (declares(context, Manifest.permission.CAMERA) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            model.leavingOnPurpose()
            permission.launch(Manifest.permission.CAMERA)
        } else {
            open()
        }
    }
}

private fun declares(context: Context, permission: String): Boolean =
    try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(permission) == true
    } catch (e: Exception) {
        false
    }

// ----- Le texte des messages -----

/**
 * Les textes déjà mis en forme (par leur texte et leurs mentions). Chaque
 * bulle à l'écran redemande le sien à chaque mise à jour de la
 * discussion : il n'est construit qu'une fois.
 */
private val richTextKept = LruCache<String, AnnotatedString>(600)

/** Le texte d'un message, avec les « @Léa » qu'il mentionne en couleur. */
fun richText(text: String, mentions: List<String>): AnnotatedString {
    val key = if (mentions.isEmpty()) text else "${mentions.size}\u0000$text\u0000" + mentions.joinToString("\u0000")
    richTextKept.get(key)?.let { return it }
    val out = richTextWithMentions(text, mentions)
    richTextKept.put(key, out)
    return out
}

/** Une lettre sans accent ni majuscule (« É » → « e »), pour comparer comme `.diacriticInsensitive`. */
private fun fold(c: Char): Char = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar()

private fun richTextWithMentions(text: String, mentions: List<String>): AnnotatedString {
    val base = richText(text)
    val plain = base.text
    val found = ArrayList<IntRange>()
    for (label in mentions) {
        if (label.isEmpty()) continue
        val needle = "@$label"
        var from = 0
        while (from + needle.length <= plain.length) {
            var i = from
            var hit = -1
            while (i + needle.length <= plain.length) {
                if (needle.indices.all { fold(plain[i + it]) == fold(needle[it]) }) {
                    hit = i
                    break
                }
                i++
            }
            if (hit < 0) break
            found.add(hit until hit + needle.length)
            from = hit + needle.length
        }
    }
    if (found.isEmpty()) return base
    return AnnotatedString.Builder(base).apply {
        for (r in found) addStyle(SpanStyle(color = TalkaColors.accent, fontWeight = FontWeight.Bold), r.first, r.last + 1)
    }.toAnnotatedString()
}

/** Liens cliquables, *gras*, _italique_, ~barré~, `code` : comme le navigateur. */
fun richText(source: String): AnnotatedString {
    // Un jeton Brocolis qu'aucune clé n'ouvre : « 🔒🥦 », pas un pavé de base64.
    val text = Brocolis.masked(source)
    val chars = text
    val plain = StringBuilder()
    return buildAnnotatedString {
        fun flush() {
            if (plain.isNotEmpty()) {
                append(plain.toString())
                plain.clear()
            }
        }
        var i = 0
        while (i < chars.length) {
            // Un lien commence après une espace, ou juste après une parenthèse ou
            // un guillemet ouvrants : « (https://…) », « «https://…» ».
            val atStart = i == 0 || chars[i - 1].isWhitespace() || "([{«\"'“‘".contains(chars[i - 1])
            if (atStart && (chars.startsWith("https://", i) || chars.startsWith("http://", i))) {
                var j = i
                while (j < chars.length && !chars[j].isWhitespace()) j++
                while (j > i && ".,;:!?)]}'\"»”’".contains(chars[j - 1])) j--
                val link = chars.substring(i, j)
                flush()
                withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) {
                    append(link)
                }
                i = j
                continue
            }
            val c = chars[i]
            if ("*_~`".contains(c) && i + 1 < chars.length) {
                var end = i + 1
                while (end < chars.length && chars[end] != c && chars[end] != '\n') end++
                val len = end - i - 1
                if (end < chars.length && chars[end] == c && len > 0) {
                    val inner = chars.substring(i + 1, end)
                    if (!inner.startsWith(" ") && !inner.endsWith(" ")) {
                        flush()
                        val style = when (c) {
                            '*' -> SpanStyle(fontWeight = FontWeight.Bold)
                            '_' -> SpanStyle(fontStyle = FontStyle.Italic)
                            '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                            else -> SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x14808080))
                        }
                        withStyle(style) { append(inner) }
                        i = end + 1
                        continue
                    }
                }
            }
            plain.append(c)
            i++
        }
        flush()
    }
}

/** Un message fait seulement de 1 à 3 emojis s'affiche en grand. */
fun onlyEmoji(text: String): Boolean {
    val t = text.trim(' ', '\t')
    if (t.isEmpty()) return false
    val it = BreakIterator.getCharacterInstance()
    it.setText(t)
    val clusters = ArrayList<String>()
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        clusters.add(t.substring(start, end))
        if (clusters.size > 3) return false
        start = end
        end = it.next()
    }
    return clusters.all { cluster -> cluster.codePoints().anyMatch { isEmojiPresentation(it) || (isEmoji(it) && it > 0xFF) } }
}

private fun isEmojiPresentation(cp: Int): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_PRESENTATION) else cp >= 0x1F000

private fun isEmoji(cp: Int): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        UCharacter.hasBinaryProperty(cp, UProperty.EMOJI)
    } else {
        cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2190..0x21FF || cp in 0x2B00..0x2BFF
    }

// ----- Les textes -----

/** Les `n` premiers caractères, au sens de Swift (les graphèmes : un emoji compte pour un). */
fun String.firstChars(n: Int): String {
    val breaks = BreakIterator.getCharacterInstance()
    breaks.setText(this)
    var end = 0
    repeat(n) {
        val next = breaks.next()
        if (next == BreakIterator.DONE) return this
        end = next
    }
    return substring(0, end)
}

/** Sans espaces ni tabulations autour (`.whitespaces` du Swift : pas les retours à la ligne). */
fun String.trimSpaces(): String = trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

// ----- Les dates -----

object Dates {
    private val fr = Locale.FRANCE
    private val time = DateTimeFormatter.ofPattern("HH:mm", fr)
    private val day = DateTimeFormatter.ofPattern("EEEE", fr)
    private val short = DateTimeFormatter.ofPattern("dd/MM/yy", fr)
    private val long = DateTimeFormatter.ofPattern("d MMMM yyyy", fr)

    private fun date(ms: Double) = Instant.ofEpochMilli(ms.toLong()).atZone(ZoneId.systemDefault())

    fun daysAgo(ms: Double): Int = ChronoUnit.DAYS.between(date(ms).toLocalDate(), LocalDate.now()).toInt()

    private fun capitalized(s: String) = s.replaceFirstChar { it.titlecase(fr) }

    /** `14:05`. */
    fun clock(ms: Double): String = time.format(date(ms))

    /** L'heure d'un élément de liste : `14:05`, « Hier », « Lundi », `03/03/26`. */
    fun list(ms: Double): String =
        when (val d = daysAgo(ms)) {
            0 -> clock(ms)
            1 -> "Hier"
            in 2..6 -> capitalized(day.format(date(ms)))
            else -> if (d < 0) clock(ms) else short.format(date(ms))
        }

    /** « Aujourd'hui », « Hier », « Lundi », « 3 mars 2026 ». */
    fun separator(ms: Double): String =
        when (daysAgo(ms)) {
            0 -> "Aujourd'hui"
            1 -> "Hier"
            in 2..6 -> capitalized(day.format(date(ms)))
            else -> long.format(date(ms))
        }

    fun lastSeen(ms: Double): String =
        when (daysAgo(ms)) {
            0 -> "vu(e) aujourd'hui à ${clock(ms)}"
            1 -> "vu(e) hier à ${clock(ms)}"
            else -> "vu(e) le ${short.format(date(ms))}"
        }

    /** Un jour, passé ou à venir : « aujourd'hui », « demain », « hier », « lundi », « le 3 mars 2026 ». */
    fun `when`(ms: Double): String =
        when (daysAgo(ms)) {
            0 -> "aujourd'hui"
            1 -> "hier"
            -1 -> "demain"
            in -6..6 -> day.format(date(ms))
            else -> "le ${long.format(date(ms))}"
        }

    fun sameDay(a: Double, b: Double): Boolean = date(a).toLocalDate() == date(b).toLocalDate()
}

// ----- Sons et notifications -----

object Sounds {
    private var context: Context? = null
    private var tones: ToneGenerator? = null

    fun prepare(context: Context) {
        this.context = context.applicationContext
    }

    /** Un message arrive. */
    fun incoming() {
        val c = context ?: return
        try {
            RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        } catch (e: Exception) {
        }
    }

    /** Un message arrive dans la discussion ouverte (un petit son discret). */
    fun sent() {
        try {
            val t = tones ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 40).also { tones = it }
            t.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (e: RuntimeException) {
        }
    }
}

/**
 * Les notifications locales : quand Talka est en arrière-plan, l'appareil,
 * qui a déchiffré le message, se prévient lui-même. Rien ne passe par
 * Google ni par le serveur.
 */
object Notifier {
    private var context: Context? = null
    private const val withSound = "messages"
    private const val silent = "messages-silencieux"

    fun prepare(context: Context) {
        val c = context.applicationContext
        this.context = c
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = c.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(withSound, "Messages", NotificationManager.IMPORTANCE_HIGH))
            manager.createNotificationChannel(
                NotificationChannel(silent, "Messages sans son", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) },
            )
        }
    }

    /** Les notifications sont-elles permises ? */
    fun allowed(): Boolean = context?.let { NotificationManagerCompat.from(it).areNotificationsEnabled() } ?: false

    @SuppressLint("MissingPermission")
    fun message(title: String, body: String, thread: String, sound: Boolean) {
        val c = context ?: return
        if (!allowed()) return
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(if (thread == "call") "talka://chats" else WidgetData.link(thread)), c, MainActivity::class.java)
        val pending = PendingIntent.getActivity(c, thread.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, if (sound) withSound else silent)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body.take(160))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(160)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(thread)
            .setColor(0xFF00A884.toInt())
            .build()
        try {
            NotificationManagerCompat.from(c).notify(thread.hashCode(), n)
        } catch (e: SecurityException) {
        }
    }

    /** La discussion est lue : sa notification part. */
    fun cancel(thread: String) {
        context?.let { NotificationManagerCompat.from(it).cancel(thread.hashCode()) }
    }

    fun cancelAll() {
        context?.let { NotificationManagerCompat.from(it).cancelAll() }
    }
}

// ----- Petits éléments -----

/** Le code Talka en gros, à copier. */
@Composable
fun CodeBadge(code: String, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Text(
            TalkaCode.pretty(code),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
            color = TalkaColors.dark,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .background(TalkaColors.green.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Une carte (fond groupé). */
@Composable
fun Modifier.card(): Modifier =
    this.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(16.dp)

// ----- Les gabarits d'écran -----

/**
 * Une page poussée (le `NavigationLink` de l'iPhone) : une barre avec son
 * titre et la flèche de retour (le retour d'Android aussi).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = actions,
            )
        },
        content = content,
    )
}

/**
 * Une feuille (le `.sheet` de l'iPhone) : sur Android, une fenêtre plein
 * écran avec une croix pour fermer, son titre et son action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Annuler") }
                        },
                        actions = actions,
                    )
                },
                content = content,
            )
        }
    }
}

/** Un écran qui n'est pas encore porté de l'iPhone. */
@Composable
fun ComingSoon(title: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text("Bientôt sur Android.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** La liste est vide (le `ContentUnavailableView` de l'iPhone). */
@Composable
fun EmptyState(title: String, hint: String = "", icon: ImageVector? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (hint.isNotEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

/** Le titre d'une section (le `Section("…")` d'un `Form`). */
@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

/** L'explication sous une section (le `footer:`). */
@Composable
fun SectionFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** Copier dans le presse-papiers. */
fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Talka", text))
}

/** Partager un texte (la feuille de partage d'Android). */
fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
