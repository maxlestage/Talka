// Là où l'application range ce que lisent ses widgets (sur la montre : ses
// complications) : un fichier privé de l'application, hors des sauvegardes.
// Sur l'iPhone, c'est un élément du trousseau lisible après le premier
// déverrouillage ; ici, le stockage chiffré par l'appareil, lisible lui
// aussi après le premier déverrouillage.

package talka.link

import android.content.Context
import talka.kit.WidgetStorage
import java.io.File
import java.io.IOException

class FileWidgetStorage(private val file: File) : WidgetStorage {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "talka-widgets.json"))

    @Synchronized
    override fun read(): ByteArray? =
        try {
            if (file.isFile) file.readBytes() else null
        } catch (e: IOException) {
            null
        }

    /** Écrit d'un coup (un fichier à côté, renommé) : un widget ne lit jamais une moitié. */
    @Synchronized
    override fun write(data: ByteArray): Boolean =
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(data)
            tmp.renameTo(file) || run {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: IOException) {
            false
        }

    @Synchronized
    override fun clear() {
        file.delete()
    }
}
