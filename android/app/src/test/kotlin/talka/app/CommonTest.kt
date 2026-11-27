// Ce qui sert partout (`Common.swift` de l'iPhone), en valeurs : la couleur
// et l'initiale d'un avatar, la mise en forme du texte des messages (gras,
// italique, barré, code, liens, mentions), les messages tout en emoji, les
// dates, la réduction des photos. Les valeurs attendues sont celles que
// donne le Swift.

package talka.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import talka.app.ui.Avatars
import talka.app.ui.nextCharacter
import talka.app.ui.Dates
import talka.app.ui.Images
import talka.app.ui.dataOfURL
import talka.app.ui.dataURL
import talka.app.ui.firstChars
import talka.app.ui.onlyEmoji
import talka.app.ui.richText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommonTest {
    private val zone: TimeZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(zone)
    }

    // ----- Avatars -----

    @Test
    fun theColorOfACodeIsTheIPhoneOne() {
        // `id.unicodeScalars.reduce(UInt32(0)) { $0 &* 31 &+ $1.value } % 10`
        assertEquals(
            listOf(
                Color(0xFFE542A3), Color(0xFF1F7AEC), Color(0xFFD36B00), Color(0xFF02A698), Color(0xFF7F66FF),
                Color(0xFFC4532D), Color(0xFF008069), Color(0xFFA62C71), Color(0xFF5E47DE), Color(0xFFB7820B),
            ),
            Avatars.colors,
        )
        val expected = mapOf(
            "K7Q2MX9PRT" to 1, "WGSEAJNSZJ" to 6, "g:famille" to 5, "Léa" to 6, "" to 0,
            // Au-delà de 32 bits : le calcul reboucle, sans signe.
            "ZZZZZZZZZZZZ" to 2,
            // Par point de code (un emoji compte une fois), pas par unité UTF-16.
            "é😀" to 5,
        )
        for ((id, index) in expected) assertEquals(Avatars.colors[index], Avatars.color(id), id)
    }

    @Test
    fun theInitialOfAName() {
        assertEquals("L", Avatars.initial("Léa"))
        // Sans le « ~ » des noms choisis par le contact, ni les espaces.
        assertEquals("M", Avatars.initial("~max"))
        assertEquals("É", Avatars.initial(" ~ élodie "))
        assertEquals("", Avatars.initial(""))
        assertEquals("", Avatars.initial("~ "))
        // Un caractère au sens de Swift : l'emoji entier ; « ß » en majuscules.
        assertEquals("😀", Avatars.initial("😀 Léa"))
        assertEquals("SS", Avatars.initial("ßeta"))
    }

    // ----- Le texte des messages -----

    private fun styles(a: AnnotatedString) = a.spanStyles.map { Triple(a.text.substring(it.start, it.end), it.item, it.start) }

    private fun links(a: AnnotatedString) =
        a.getLinkAnnotations(0, a.length).map { a.text.substring(it.start, it.end) to (it.item as LinkAnnotation.Url).url }

    @Test
    fun boldItalicStrikeAndCode() {
        val a = richText("*gras* _penché_ ~barré~ `code` fin")
        assertEquals("gras penché barré code fin", a.text)
        val s = styles(a)
        assertEquals(listOf("gras", "penché", "barré", "code"), s.map { it.first })
        assertEquals(FontWeight.Bold, s[0].second.fontWeight)
        assertEquals(FontStyle.Italic, s[1].second.fontStyle)
        assertEquals(TextDecoration.LineThrough, s[2].second.textDecoration)
        assertEquals(FontFamily.Monospace, s[3].second.fontFamily)
    }

    @Test
    fun whatIsNotFormatting() {
        // Des espaces au bord, rien entre les deux, un retour à la ligne avant la fin, un signe seul.
        for (plain in listOf("a * b * c", "**", "__", "*a\nb*", "3 * 4", "fin*", "~ non ~", "` x`")) {
            val a = richText(plain)
            assertEquals(plain, a.text)
            assertTrue(a.spanStyles.isEmpty(), plain)
        }
        // Sans frontière de mot, comme le Swift : « 2*3*4 » met « 3 » en gras.
        assertEquals("234", richText("2*3*4").text)
        // La mise en forme ne s'emboîte pas : l'intérieur reste tel quel.
        val nested = richText("*a _b_ c*")
        assertEquals("a _b_ c", nested.text)
        assertEquals(1, nested.spanStyles.size)
        // Le premier signe fermant gagne.
        assertEquals("a b*", richText("*a* b*").text)
    }

    @Test
    fun linksAreFoundLikeOnTheIPhone() {
        val a = richText("Voir (https://talka.app/c/K7Q2MX9PRT). Ou «http://exemple.fr/a?b=1» !")
        assertEquals("Voir (https://talka.app/c/K7Q2MX9PRT). Ou «http://exemple.fr/a?b=1» !", a.text)
        assertEquals(
            listOf(
                "https://talka.app/c/K7Q2MX9PRT" to "https://talka.app/c/K7Q2MX9PRT",
                "http://exemple.fr/a?b=1" to "http://exemple.fr/a?b=1",
            ),
            links(a),
        )
        // Seulement en début de mot ; la ponctuation de fin n'en fait pas partie.
        assertTrue(links(richText("xhttps://talka.app")).isEmpty())
        assertEquals(listOf("https://talka.app/x_y_z"), links(richText("https://talka.app/x_y_z, non ?")).map { it.first })
        assertEquals(listOf("https://a.fr"), links(richText("“https://a.fr”")).map { it.second })
        // Un lien garde ses signes (pas d'italique dedans), et un texte en italique n'est pas un lien.
        assertTrue(richText("https://talka.app/x_y_z").spanStyles.isEmpty())
        val italic = richText("_https://talka.app_")
        assertEquals("https://talka.app", italic.text)
        assertTrue(links(italic).isEmpty())
        // Autre chose que http(s) : du texte.
        assertTrue(links(richText("ftp://a.fr talka://add/K7Q2MX9PRT")).isEmpty())
    }

    @Test
    fun mentionsAreColouredWhateverTheirCaseOrAccents() {
        val a = richText("Salut @léa, @LEA et @Max !", listOf("Léa"))
        assertEquals("Salut @léa, @LEA et @Max !", a.text)
        val s = styles(a)
        assertEquals(listOf("@léa", "@LEA"), s.map { it.first })
        assertTrue(s.all { it.second.color == TalkaColors.accent && it.second.fontWeight == FontWeight.Bold })
        // Sans mention, ou avec une mention vide : rien.
        assertTrue(richText("Salut @Léa", emptyList()).spanStyles.isEmpty())
        assertTrue(richText("Salut @Léa", listOf("")).spanStyles.isEmpty())
        // La mise en forme reste, la mention s'y ajoute.
        val both = richText("*gras* @Max", listOf("Max"))
        assertEquals("gras @Max", both.text)
        assertEquals(listOf("gras", "@Max"), styles(both).map { it.first })
    }

    @Test
    fun aMessageOfOneToThreeEmojisIsBig() {
        for (yes in listOf("😀", "😀😀😀", "  👍 ", "\t🎉", "❤️", "👍🏽", "🇫🇷", "👩‍👩‍👧")) {
            assertTrue(onlyEmoji(yes), yes)
        }
        // Quatre, du texte, un chiffre ou « © » (des emoji pour Unicode, en dessous de U+0100), rien.
        for (no in listOf("😀😀😀😀", "ok", "a😀", "1", "©", "", "   ", "😀\n", "😀 😀")) {
            assertFalse(onlyEmoji(no), no)
        }
    }

    @Test
    fun spacesAroundAnEmojiAreThoseOfSwift() {
        // `.whitespaces` : toutes les espaces d'Unicode (l'insécable aussi) et la tabulation.
        assertTrue(onlyEmoji(" 😀 "))
        assertTrue(onlyEmoji(" 👍 "))
    }

    @Test
    fun theFirstCharactersAreThoseOfSwift() {
        assertEquals("ab", "abc".firstChars(2))
        assertEquals("a😀", "a😀b".firstChars(2))
        assertEquals("👍🏽", "👍🏽!".firstChars(1))
        assertEquals("abc", "abc".firstChars(160))
    }

    /** Le même découpage partout, quel que soit le système (les anciens coupent un emoji teinté en deux). */
    @Test
    fun aCharacterIsAWholeGrapheme() {
        fun split(s: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < s.length) {
                val end = nextCharacter(s, i)
                out.add(s.substring(i, end))
                i = end
            }
            return out
        }
        assertEquals(listOf("a", "b", "c"), split("abc"))
        assertEquals(listOf("e\u0301", "t", "e\u0301"), split("e\u0301te\u0301"), "une lettre et son accent")
        assertEquals(listOf("👍🏽", "!"), split("👍🏽!"), "un emoji et sa teinte")
        assertEquals(listOf("❤️", "a"), split("❤️a"), "le sélecteur de variante")
        assertEquals(listOf("👩‍👩‍👧", "👨‍💻"), split("👩‍👩‍👧👨‍💻"), "des emojis reliés")
        assertEquals(listOf("🇫🇷", "🇩🇪", "🇯"), split("🇫🇷🇩🇪🇯"), "les drapeaux, deux par deux")
        assertEquals(listOf("🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"), split("🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"), "un drapeau à étiquettes")
        assertEquals(listOf("1️⃣", "2"), split("1️⃣2"), "une touche")
        assertEquals(listOf("\r\n", "a"), split("\r\na"))
        assertEquals(listOf("한", "\u1112\u1161\u11AB"), split("한\u1112\u1161\u11AB"), "le coréen lettre à lettre")
        assertEquals(listOf("😀", " ", "😀"), split("😀 😀"))
        // Un liant sans emoji derrière ne prend pas la lettre qui suit.
        assertEquals(listOf("a\u200D", "b"), split("a\u200Db"))
        assertEquals("", "".firstChars(3))
        assertEquals("M", Avatars.initial("~max"))
        assertEquals("👩‍👩‍👧", Avatars.initial("👩‍👩‍👧 famille"))
    }

    // ----- Les dates -----

    private fun fr(pattern: String, ms: Double) = SimpleDateFormat(pattern, Locale.FRANCE).format(Date(ms.toLong()))

    /** Midi, il y a `days` jours (dans `days` jours s'il est négatif). */
    private fun noon(days: Int): Double {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 12)
        c.set(java.util.Calendar.MINUTE, 5)
        c.add(java.util.Calendar.DAY_OF_YEAR, -days)
        return c.timeInMillis.toDouble()
    }

    @Test
    fun aFixedDayInFrench() {
        // Le 3 mars 2026 à 12 h 00 à Paris (un mardi).
        val at = 1772535600000.0
        assertEquals("12:00", Dates.clock(at))
        assertEquals("03/03/26", Dates.list(at))
        assertEquals("3 mars 2026", Dates.separator(at))
        assertEquals("vu(e) le 03/03/26", Dates.lastSeen(at))
        assertEquals("le 3 mars 2026", Dates.`when`(at))
        assertTrue(Dates.sameDay(at, at + 11 * 3_600_000))
        assertFalse(Dates.sameDay(at, at + 12 * 3_600_000))
    }

    @Test
    fun recentDaysAreNamed() {
        assertEquals("12:05", Dates.list(noon(0)))
        assertEquals("Aujourd'hui", Dates.separator(noon(0)))
        assertEquals("vu(e) aujourd'hui à 12:05", Dates.lastSeen(noon(0)))
        assertEquals("aujourd'hui", Dates.`when`(noon(0)))

        assertEquals("Hier", Dates.list(noon(1)))
        assertEquals("Hier", Dates.separator(noon(1)))
        assertEquals("vu(e) hier à 12:05", Dates.lastSeen(noon(1)))
        assertEquals("hier", Dates.`when`(noon(1)))
        assertEquals("demain", Dates.`when`(noon(-1)))

        // De deux à six jours : le jour, avec sa majuscule dans les listes.
        for (d in 2..6) {
            val day = fr("EEEE", noon(d))
            assertEquals(day.replaceFirstChar { it.uppercase() }, Dates.list(noon(d)))
            assertEquals(day.replaceFirstChar { it.uppercase() }, Dates.separator(noon(d)))
            assertEquals(day, Dates.`when`(noon(d)))
            assertEquals(fr("EEEE", noon(-d)), Dates.`when`(noon(-d)))
            assertEquals("vu(e) le ${fr("dd/MM/yy", noon(d))}", Dates.lastSeen(noon(d)))
        }
        assertTrue(Dates.list(noon(3)) in listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche"))

        // Au-delà : la date.
        assertEquals(fr("dd/MM/yy", noon(7)), Dates.list(noon(7)))
        assertEquals(fr("d MMMM yyyy", noon(7)), Dates.separator(noon(7)))
        assertEquals("le ${fr("d MMMM yyyy", noon(7))}", Dates.`when`(noon(7)))
        assertEquals("le ${fr("d MMMM yyyy", noon(-7))}", Dates.`when`(noon(-7)))
    }

    @Test
    fun aDayToComeInAListIsADateLikeOnTheIPhone() {
        // Une horloge en avance chez l'autre : le `default` du `switch` du Swift, la date courte.
        assertEquals(fr("dd/MM/yy", noon(-1)), Dates.list(noon(-1)))
        assertEquals(fr("dd/MM/yy", noon(-3)), Dates.list(noon(-3)))
        assertEquals(fr("d MMMM yyyy", noon(-1)), Dates.separator(noon(-1)))
        assertEquals("vu(e) le ${fr("dd/MM/yy", noon(-1))}", Dates.lastSeen(noon(-1)))
    }

    // ----- Les images -----

    private fun photo(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(20, 120, 200)) }

    private fun decoded(url: String): Bitmap {
        val data = assertNotNull(dataOfURL(url))
        return assertNotNull(BitmapFactory.decodeByteArray(data, 0, data.size))
    }

    @Test
    fun aPhotoIsReducedTo1600LikeOnTheIPhone() {
        // `min(1, 1600 / max(largeur, hauteur))`, les côtés arrondis ; JPEG.
        val (url, w, h) = assertNotNull(Images.compressed(photo(4000, 3000)))
        assertEquals(1600 to 1200, w to h)
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertEquals(1600 to 1200, decoded(url).let { it.width to it.height })
        // 1 600 / 3 001 × 2 000 = 1 066,3 → 1 066.
        assertEquals(1066 to 1600, assertNotNull(Images.compressed(photo(2000, 3001))).let { it.second to it.third })
        // Une petite photo n'est pas agrandie.
        assertEquals(640 to 480, assertNotNull(Images.compressed(photo(640, 480))).let { it.second to it.third })
        assertEquals(1600 to 1600, assertNotNull(Images.compressed(photo(1600, 1600))).let { it.second to it.third })
    }

    private fun jpeg(w: Int, h: Int): String {
        val out = java.io.ByteArrayOutputStream()
        photo(w, h).compress(Bitmap.CompressFormat.JPEG, 82, out)
        return out.toByteArray().dataURL("image/jpeg")
    }

    @Test
    fun aPhotoInABubbleIsAtMost1020PixelsLikeOnTheIPhone() {
        // `kCGImageSourceThumbnailMaxPixelSize: 1020` : la photo entière (1 600) n'est pas gardée pour la bulle.
        val full = jpeg(1600, 1200)
        assertEquals(1020 to 765, assertNotNull(Images.bubble(full)).let { it.width to it.height })
        assertEquals(765 to 1020, assertNotNull(Images.bubble(jpeg(1200, 1600))).let { it.width to it.height })
        // Une photo d'un autre appareil, plus grande encore.
        assertEquals(1020 to 765, assertNotNull(Images.bubble(jpeg(4000, 3000))).let { it.width to it.height })
        // Plus petite : telle quelle, jamais agrandie.
        assertEquals(800 to 600, assertNotNull(Images.bubble(jpeg(800, 600))).let { it.width to it.height })
        assertEquals(1020 to 1020, assertNotNull(Images.bubble(jpeg(1020, 1020))).let { it.width to it.height })
        // La photo qu'on ouvre reste entière.
        assertEquals(1600 to 1200, assertNotNull(Images.fromDataURL(full)).let { it.width to it.height })
        assertNull(Images.bubble(""))
        assertNull(Images.bubble("data:image/jpeg;base64,AAAA"))
    }

    @Test
    fun anAvatarIsASquareOf160() {
        val url = assertNotNull(Images.avatarDataURL(photo(900, 300)))
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertEquals(160 to 160, decoded(url).let { it.width to it.height })
    }

    @Test
    fun dataAddresses() {
        val bytes = byteArrayOf(0, 1, 2, -1, 127)
        val url = bytes.dataURL("application/octet-stream")
        assertEquals("data:application/octet-stream;base64,AAEC/38=", url)
        assertContentEquals(bytes, dataOfURL(url))
        assertNull(dataOfURL("https://talka.app/photo.jpg"))
        assertNull(dataOfURL("data:image/png;base64"))
        assertNull(Images.fromDataURL(""))
    }
}
