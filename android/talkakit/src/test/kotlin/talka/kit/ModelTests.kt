// Le modèle seul, comme le navigateur : le nom d'une pièce jointe
// enregistrée, les tailles, le stockage. Les mêmes vérifications que
// l'iPhone (`SaveNameTests.swift`). Les mentions et la fiche d'une
// discussion : `MentionTests.kt`.

package talka.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveNameTests {
    private fun message(kind: String, name: String = "", mime: String = "", data: String = "") =
        Message("c", "m", "x", 0.0, Content()).copy(kind = kind, name = name, mime = mime, data = data)

    @Test
    fun testSaveNameFollowsTheRealType() {
        assertEquals("devis.pdf", message("file", name = "devis.pdf").saveName)
        assertEquals("photo-talka.jpg", message("image", data = "data:image/jpeg;base64,AA").saveName)
        assertEquals("photo-talka.png", message("image", mime = "image/png").saveName)
        assertEquals("vocal-talka.m4a", message("voice", data = "data:audio/mp4;base64,AA").saveName)
        assertEquals("vocal-talka.webm", message("voice", mime = "audio/webm;codecs=opus").saveName)
        assertEquals("vocal-talka.webm", message("voice").saveName)
        assertEquals("fichier-talka", message("file").saveName)
    }

    /** Sous le nom d'un document : l'extension seulement s'il y en a une vraie. */
    @Test
    fun testFileDetailShowsARealExtensionOnly() {
        fun f(name: String) = Message("c", "m", "x", 0.0, Content()).copy(name = name)
        assertEquals("12 Ko · PDF", f("devis.pdf").fileDetail("12 Ko"))
        assertEquals("3 Mo · GZ", f("archive.tar.gz").fileDetail("3 Mo"))
        assertEquals("1 Ko", f("README").fileDetail("1 Ko"))
        assertEquals("1 Ko", f("").fileDetail("1 Ko"))
        assertEquals("1 Ko", f(".profile").fileDetail("1 Ko"))
        assertEquals("1 Ko", f("fin.").fileDetail("1 Ko"))
        assertEquals("1 Ko", f("v1.2 compte rendu final").fileDetail("1 Ko"))
        assertEquals("1 Ko", f("v1.2 fin").fileDetail("1 Ko"))
    }

    /** Une taille s'affiche comme dans le navigateur (par 1024, virgule). */
    @Test
    fun testSizesLikeTheBrowser() {
        assertEquals("0 o", formatBytes(0.0))
        assertEquals("25 o", formatBytes(25.0))
        assertEquals("1023 o", formatBytes(1023.0))
        assertEquals("1,0 Ko", formatBytes(1024.0))
        assertEquals("11,7 Ko", formatBytes(12_000.0))
        assertEquals("1,5 Mo", formatBytes(1_572_864.0))
        assertEquals("0 o", formatBytes(-3.0))
        // Une égalité exacte s'arrondit au pair, comme `printf` et le navigateur.
        assertEquals("1,2 Ko", formatBytes(1280.0))
    }

    /** « Gérer le stockage » compte comme le navigateur. */
    @Test
    fun testStorageCountsTheAttachments() {
        var n = 0
        fun m(kind: String, data: String, size: Double, deleted: Boolean = false) =
            Message("c", "m${n++}", "x", 0.0, Content()).copy(kind = kind, data = data, size = size, deleted = deleted)
        val st = Storage.of(
            listOf(
                m("image", "data:image/jpeg;base64,AAAAAAAA", 0.0),
                m("image", "data:image/jpeg;base64,AAAA", 0.0),
                m("file", "data:application/pdf;base64,AA==", 1000.0),
                m("voice", "data:audio/webm;base64,AAAA", 500.0),
                m("voice", "data:audio/webm;base64,AAAA", 500.0, deleted = true),
                m("text", "", 0.0),
            ),
        )
        assertEquals(Storage(bytes = 6.0 + 3 + 1000 + 500, photos = 2, files = 1, voices = 1), st)
        assertEquals("2 photos · 1 document · 1 vocal", st.detail)
        assertEquals("", Storage().detail)
    }

    /** Une durée absurde reçue d'un autre appareil ne fait pas planter. */
    @Test
    fun testDurationNeverCrashes() {
        assertEquals("1:05", formatDuration(65.0))
        assertEquals("0:00", formatDuration(0.4))
        assertEquals("0:00", formatDuration(-5.0))
        assertEquals("0:00", formatDuration(Double.NaN))
        assertEquals("0:00", formatDuration(Double.POSITIVE_INFINITY))
        assertEquals("5999:59", formatDuration(1e300))
    }
}
