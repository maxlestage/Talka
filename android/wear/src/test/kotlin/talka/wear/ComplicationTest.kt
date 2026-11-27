// Les complications du cadran : leurs textes (ceux des complications de
// l'Apple Watch), et les icônes de la montre et des widgets du téléphone
// (leurs tracés se lisent : un tracé faux ferait tomber l'écran qui le dessine).

package talka.wear

import androidx.core.graphics.PathParser
import org.junit.Test
import talka.kit.WidgetData
import talka.link.WidgetContent
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComplicationTest {
    @Test
    fun theTexts() {
        val s = ComplicationTexts.of(WidgetContent.sample())
        assertEquals("3", s.short)
        assertEquals("non lus", s.shortTitle)
        assertEquals("Talka · 3", s.longTitle)
        assertEquals("Léa : On se retrouve à 19 h ?", s.long)
        assertEquals("3 messages non lus", s.description)
        assertTrue(s.unread)

        val none = ComplicationTexts.of(null)
        assertEquals("0", none.short)
        assertEquals("Talka", none.shortTitle)
        assertEquals("Talka", none.longTitle)
        assertEquals("Vos messages s'afficheront ici", none.long)
        assertEquals("Aucun message non lu", none.description)
        assertFalse(none.unread)

        assertEquals("Aucun nouveau message", ComplicationTexts.of(WidgetData()).long)
        val hidden = ComplicationTexts.of(WidgetData(unread = 150, hidden = true))
        assertEquals("99+", hidden.short)
        assertEquals("Talka · 99+", hidden.longTitle)
        assertEquals("150 messages non lus", hidden.long, "protégé par un code : le nombre seulement")
    }

    @Test
    fun everyIconParses() {
        val root = listOf(File("."), File("wear")).first { File(it, "src/main/res/drawable").isDirectory }
        val icons = listOf("src/main/res/drawable", "../app/src/main/res/drawable")
            .flatMap { File(root, it).listFiles { f -> f.name.startsWith("ic_") && f.name.endsWith(".xml") }!!.toList() }
        assertTrue(icons.size >= 20)
        for (f in icons) {
            val paths = Regex("android:pathData=\"([^\"]+)\"").findAll(f.readText()).map { it.groupValues[1] }.toList()
            assertTrue(paths.isNotEmpty(), f.name)
            for (p in paths) assertTrue(PathParser.createNodesFromPathData(p).isNotEmpty(), f.name)
        }
    }
}
