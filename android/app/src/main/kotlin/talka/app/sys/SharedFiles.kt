// Les fichiers que Talka donne à une autre application, par le FileProvider
// d'AndroidX (déclaré dans le manifeste, `res/xml/fichiers.xml`) : une photo,
// un document ou une discussion exportée à partager (la feuille de partage,
// `UIActivityViewController` / `ShareLink` de l'iPhone), et la photo que
// l'appareil photo écrit en pleine taille.
//
// Tout reste dans le cache privé de l'application (`cache/partage`,
// `cache/appareil`), et seulement le temps qu'il faut : la photo de l'appareil
// est effacée dès qu'elle est lue ; un fichier partagé, au partage suivant ou
// au lancement suivant passé une heure (l'application qui le reçoit le lit
// parfois bien après avoir été choisie : un courriel, un envoi vers un drive).

package talka.app.sys

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

object SharedFiles {
    /** Les fichiers à partager (un dossier par partage, pour garder le nom du fichier). */
    const val shareDir = "partage"

    /** Les photos de l'appareil photo, le temps de les lire. */
    const val cameraDir = "appareil"

    /** Ce qu'un fichier partagé reste lisible par l'application qui l'a reçu. */
    const val keepMs = 60 * 60_000L

    /** L'autorité du FileProvider, celle du manifeste (`${applicationId}.fichiers`). */
    fun authority(context: Context): String = "${context.packageName}.fichiers"

    fun dir(context: Context, name: String): File = File(context.cacheDir, name)

    /**
     * Un nom de fichier sûr : celui d'une pièce jointe vient de l'autre
     * appareil, il ne doit pas sortir de son dossier (`../`, `/`).
     */
    fun safeName(name: String): String {
        val clean = name.replace('/', '_').replace('\\', '_').replace("\u0000", "").trim()
        return if (clean.isEmpty() || clean == "." || clean == "..") "fichier" else clean.take(120)
    }

    /** Écrit `data` sous le nom `name`, dans un dossier à lui (et efface les vieux partages). */
    fun write(context: Context, data: ByteArray, name: String): File {
        sweep(context)
        val dir = File(dir(context, shareDir), UUID.randomUUID().toString()).apply { mkdirs() }
        return File(dir, safeName(name)).apply { writeBytes(data) }
    }

    /** Le fichier vide où l'appareil photo écrira sa photo. */
    fun newCameraFile(context: Context): File {
        val dir = dir(context, cameraDir).apply { mkdirs() }
        return File(dir, "photo-${UUID.randomUUID()}.jpg").apply { createNewFile() }
    }

    /** L'adresse `content://` d'un de ces fichiers, pour une autre application. */
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /**
     * La feuille de partage d'Android pour un fichier : `ACTION_SEND` (son
     * type, son adresse), et la lecture permise à l'application choisie
     * (et à l'aperçu de la feuille, par le `ClipData`).
     */
    fun shareIntent(uri: Uri, mime: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime.ifEmpty { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Efface les partages et les photos de plus d'une heure (au lancement, à chaque partage). */
    fun sweep(context: Context, now: Long = System.currentTimeMillis()) {
        for (name in listOf(shareDir, cameraDir)) {
            dir(context, name).listFiles()?.forEach { f ->
                if (now - f.lastModified() > keepMs) f.deleteRecursively()
            }
        }
    }
}
