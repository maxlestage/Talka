// Ce que Talka donne aux autres applications par son FileProvider
// (`SharedFiles`) : la feuille de partage (l'intent, l'adresse, le type, la
// lecture permise), la photo de l'appareil photo en pleine taille (le
// contrat, puis la réduction de l'iPhone), et le ménage du cache. Et le
// guide des appels en plein écran d'Android 14.

package talka.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import talka.app.sys.SharedFiles
import talka.app.ui.FileBubble
import talka.app.ui.FullScreenCalls
import talka.app.ui.FullScreenCallsGuide
import talka.app.ui.ImageViewer
import talka.app.ui.Images
import talka.app.ui.dataURL
import talka.kit.Message
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharingTest : AppTestBase() {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        install()
        // Le FileProvider garde ses dossiers d'un test à l'autre (une table
        // statique) ; Robolectric change de dossier à chaque test.
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() }
    }

    @After
    fun tearDown() {
        SharedFiles.dir(context, SharedFiles.shareDir).deleteRecursively()
        SharedFiles.dir(context, SharedFiles.cameraDir).deleteRecursively()
        uninstall()
    }

    /** L'intent `ACTION_SEND` dans la feuille de partage (`ACTION_CHOOSER`). */
    private fun sent(chooser: Intent): Intent {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(send)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        return send
    }

    @Suppress("DEPRECATION")
    private fun stream(send: Intent): Uri = assertNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM))

    private fun read(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    @Test
    fun aSharedFileGoesThroughTheFileProvider() {
        val data = "Bonjour".toByteArray()
        val file = SharedFiles.write(context, data, "compte-rendu.pdf")
        val uri = SharedFiles.uri(context, file)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fichiers", uri.authority)
        assertTrue(uri.path!!.startsWith("/partage/"), uri.toString())
        assertTrue(uri.path!!.endsWith("/compte-rendu.pdf"), uri.toString())

        val send = sent(SharedFiles.shareIntent(uri, "application/pdf"))
        assertEquals("application/pdf", send.type)
        assertEquals(uri, stream(send))
        // L'aperçu de la feuille (et la permission qui le suit) : le `ClipData`.
        val clip: ClipData = assertNotNull(send.clipData)
        assertEquals(uri, clip.getItemAt(0).uri)
        // Ce que lit l'application choisie : le fichier, par le FileProvider.
        assertContentEquals(data, read(uri))
        // Sans type : des octets.
        assertEquals("application/octet-stream", sent(SharedFiles.shareIntent(uri, "")).type)
    }

    @Test
    fun aNameFromTheOtherDeviceStaysInItsFolder() {
        val root = SharedFiles.dir(context, SharedFiles.shareDir).canonicalFile
        for (name in listOf("../../shared_prefs/talka.reglages.xml", "/etc/passwd", "..", "", "a\\..\\b")) {
            val file = SharedFiles.write(context, byteArrayOf(1), name)
            assertEquals(root, file.canonicalFile.parentFile!!.parentFile, name)
        }
        assertEquals("fichier", SharedFiles.safeName(".."))
        assertEquals("fichier", SharedFiles.safeName("  "))
        assertEquals(".._.._x", SharedFiles.safeName("../../x"))
    }

    @Test
    fun oldSharesAreSweptAway() {
        val old = SharedFiles.write(context, byteArrayOf(1), "vieux.txt")
        old.parentFile!!.setLastModified(System.currentTimeMillis() - SharedFiles.keepMs - 60_000)
        val photo = SharedFiles.newCameraFile(context)
        photo.setLastModified(System.currentTimeMillis() - SharedFiles.keepMs - 60_000)
        // Un nouveau partage efface ceux de plus d'une heure.
        val fresh = SharedFiles.write(context, byteArrayOf(2), "neuf.txt")
        assertFalse(old.exists())
        assertFalse(photo.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun theCameraWritesAFullSizePhotoIntoTheFileProvider() {
        val file = SharedFiles.newCameraFile(context)
        assertTrue(file.exists())
        assertEquals(SharedFiles.dir(context, SharedFiles.cameraDir).canonicalFile, file.canonicalFile.parentFile)
        val uri = SharedFiles.uri(context, file)
        assertEquals("${context.packageName}.fichiers", uri.authority)
        // Le contrat de `rememberCameraPicker` : l'appareil photo, et où écrire.
        val intent = ActivityResultContracts.TakePicture().createIntent(context, uri)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
        @Suppress("DEPRECATION")
        assertEquals(uri, intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT))
        // L'appareil photo écrit par l'adresse : la photo arrive dans le fichier.
        context.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(9, 8, 7)) }
        assertContentEquals(byteArrayOf(9, 8, 7), file.readBytes())
    }

    /** Une photo de 4 000 × 3 000, tenue en portrait (EXIF : tournée de 90°). */
    private fun cameraPhoto(): File {
        val file = SharedFiles.newCameraFile(context)
        val image = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        Canvas(image).drawColor(Color.rgb(30, 160, 90))
        file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        return file
    }

    @Test
    fun aPhotoTakenIsStraightenedReducedLikeTheIPhoneAndErased() {
        val file = cameraPhoto()
        val photo = assertNotNull(Images.takenPhoto(file, ok = true))
        // Redressée (portrait), décodée à 1 600 pixels de côté au moins, pas plus du double.
        assertTrue(photo.height > photo.width, "${photo.width} × ${photo.height}")
        assertEquals(2000, photo.height)
        assertEquals(1500, photo.width)
        assertFalse(file.exists())
        // La réduction de l'iPhone (`compressed()` : 1 600 pixels, JPEG à 0,82).
        val (url, w, h) = assertNotNull(Images.compressed(photo))
        assertEquals(1200, w)
        assertEquals(1600, h)
        assertTrue(url.startsWith("data:image/jpeg;base64,"))

        // Annulée : rien, et le fichier vide effacé quand même.
        val cancelled = SharedFiles.newCameraFile(context)
        assertNull(Images.takenPhoto(cancelled, ok = false))
        assertFalse(cancelled.exists())
    }

    @Test
    fun touchingADocumentOpensTheShareSheet() {
        val m = model()
        val data = "%PDF-1.4 bonjour".toByteArray()
        val doc = Message(chat = "c", id = "d1", from = "x", kind = "file", data = data.dataURL("application/pdf"), name = "devis.pdf", mime = "application/pdf", size = data.size.toDouble())
        compose.setContent { TalkaTheme(m) { FileBubble(doc) } }
        compose.onNodeWithText("devis.pdf").performClick()
        val send = sent(assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity))
        assertEquals("application/pdf", send.type)
        val uri = stream(send)
        assertEquals("${context.packageName}.fichiers", uri.authority)
        assertTrue(uri.path!!.endsWith("/devis.pdf"))
        assertContentEquals(data, read(uri))
    }

    @Test
    fun aPhotoOpenedLargeIsSharedLikeTheIPhone() {
        val m = model()
        val image = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val (url, w, h) = assertNotNull(Images.compressed(image))
        m.createAccount("Léa", "")
        val end = System.currentTimeMillis() + 30_000
        while (m.engine == null) {
            idle()
            check(System.currentTimeMillis() < end) { "pas de compte" }
            Thread.sleep(10)
        }
        val photo = Message(chat = "c", id = "p1", from = "x", kind = "image", data = url, mime = "image/jpeg", w = w, h = h)
        compose.setContent { TalkaTheme(m) { ImageViewer(m.engine!!, photo, onDismiss = {}) } }
        compose.onNodeWithContentDescription("Partager").performClick()
        val send = sent(assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity))
        assertEquals("image/jpeg", send.type)
        assertTrue(read(stream(send)).isNotEmpty())
    }

    @Test
    fun theFullScreenGuideNeedsAndroid14ARefusalAndShowsOnce() {
        val u = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        assertTrue(FullScreenCalls.needsGuide(u, allowed = false, shown = false))
        assertTrue(FullScreenCalls.needsGuide(u + 1, allowed = false, shown = false))
        assertFalse(FullScreenCalls.needsGuide(u, allowed = true, shown = false))
        assertFalse(FullScreenCalls.needsGuide(u, allowed = false, shown = true))
        assertFalse(FullScreenCalls.needsGuide(u - 1, allowed = false, shown = false))
        val settings = FullScreenCalls.settings(context)
        assertEquals("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", settings.action)
        assertEquals(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, settings.action)
        assertEquals("package:${context.packageName}", settings.dataString)
    }

    @Test
    fun theFullScreenGuideLeadsToTheSettingOnce() {
        val m = model(fullScreenCalls = { false })
        var round by mutableIntStateOf(0)
        compose.setContent { TalkaTheme(m) { key(round) { FullScreenCallsGuide() } } }
        compose.onNodeWithText("Les appels en plein écran").assertExists()
        compose.onNodeWithText("Ouvrir les réglages").performClick()
        val opened = assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertEquals(FullScreenCalls.settingsAction, opened.action)
        assertEquals("package:${context.packageName}", opened.dataString)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Les appels en plein écran").fetchSemanticsNodes().isEmpty())
        // Une fois : plus jamais, même si le plein écran reste refusé.
        assertTrue(m.env.prefs.getBoolean(FullScreenCalls.shownKey, false))
        round++
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Les appels en plein écran").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun noGuideWhenFullScreenIsAllowed() {
        val m = model(fullScreenCalls = { true })
        compose.setContent { TalkaTheme(m) { FullScreenCallsGuide() } }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Les appels en plein écran").fetchSemanticsNodes().isEmpty())
        assertFalse(m.env.prefs.getBoolean(FullScreenCalls.shownKey, false))
    }
}
