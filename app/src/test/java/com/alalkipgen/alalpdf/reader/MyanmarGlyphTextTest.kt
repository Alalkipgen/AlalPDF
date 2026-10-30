package com.alalkipgen.alalpdf.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.alalkipgen.alalpdf.reader.MyanmarFixtures.CHROME
import com.alalkipgen.alalpdf.reader.MyanmarFixtures.LIBREOFFICE
import com.alalkipgen.alalpdf.reader.MyanmarFixtures.VISUAL
import com.alalkipgen.alalpdf.reader.MyanmarFixtures.WORD_2001
import com.alalkipgen.alalpdf.reader.MyanmarFixtures.WORD_2107
import com.tom_roush.fontbox.ttf.CmapTable
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.text.PDFMarkedContentExtractor
import com.tom_roush.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which fonts count as broken and where their glyph text comes from, on the fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MyanmarGlyphTextTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PdfBoxDocument.initResources(context)
    }

    @Test
    fun wordFontsAreBrokenAndOthersAreNot() {
        for (name in listOf(WORD_2001, WORD_2107)) {
            open(name) { document ->
                val fonts = usedGlyphs(document).keys
                val type0 = fonts.filterIsInstance<PDType0Font>()
                assertEquals(name, 2, type0.size)
                val glyphs = MyanmarGlyphText()
                fonts.forEach { assertEquals("$name ${it.name}", it in type0, glyphs.isBroken(it)) }
            }
        }
        for (name in listOf(VISUAL, CHROME, LIBREOFFICE)) {
            open(name) { document ->
                val fonts = usedGlyphs(document).keys
                // LibreOffice embeds simple TrueType fonts, which are never checked.
                assertTrue(name, name == LIBREOFFICE || fonts.any { it is PDType0Font })
                val glyphs = MyanmarGlyphText()
                fonts.forEach { assertFalse("$name ${it.name}", glyphs.isBroken(it)) }
            }
        }
    }

    @Test
    fun zawgyiFontsByNameOrByCodePoint() {
        for (name in listOf(WORD_2001, WORD_2107, VISUAL, CHROME, LIBREOFFICE)) {
            open(name) { document ->
                val glyphs = MyanmarGlyphText()
                usedGlyphs(document).keys.forEach { assertFalse("$name ${it.name}", glyphs.isZawgyi(it)) }
            }
        }
        // Fixture fonts renamed, by their BaseFont or by their FontName.
        open(VISUAL) { document ->
            val fonts = usedGlyphs(document).keys.filterIsInstance<PDType0Font>()
            fonts.forEach { it.cosObject.setName(COSName.BASE_FONT, "ABCDEF+Zawgyi-One") }
            val glyphs = MyanmarGlyphText()
            fonts.forEach { assertTrue(glyphs.isZawgyi(it)) }
            // Their glyphs keep their order, as drawn: every ေ is right before its consonant or ြ.
            val text = PdfBoxTextSource.extract(document, 0, glyphs).text
            val expected = MyanmarFixtures.expectedLines(VISUAL).joinToString("\n")
            assertEquals(expected.count { it == '\u1031' }, text.count { it == '\u1031' })
            val misplaced = Regex("\u1031(?![\u1000-\u102A\u103C])").findAll(text).map { it.range.first }.toList()
            assertEquals(emptyList<Int>(), misplaced)
            assertEquals(1, glyphs.counts().zawgyiFonts)
        }
        open(VISUAL) { document ->
            val font = usedGlyphs(document).keys.first { it is PDType0Font }
            font.fontDescriptor.fontName = "Zawgyi One Bold"
            assertTrue(MyanmarGlyphText().isZawgyi(font))
        }
        // A glyph text with a Zawgyi code point.
        open(CHROME) { document ->
            val font = usedGlyphs(document).keys.first { it is PDType0Font }
            val glyphs = MyanmarGlyphText()
            assertFalse(glyphs.isZawgyi(font))
            assertEquals("\u1064", glyphs.text(glyphs.font(font), 1, "\u1064"))
            assertTrue(glyphs.isZawgyi(font))
        }
        // A ToUnicode value with a Zawgyi code point, before any glyph is read.
        open(CHROME) { document ->
            val font = usedGlyphs(document).keys.first { it is PDType0Font }
            val toUnicode = document.document.createCOSStream()
            toUnicode.createOutputStream().use { it.write(toUnicodeCMap("<0001> <1064>").toByteArray()) }
            font.cosObject.setItem(COSName.TO_UNICODE, toUnicode)
            val lines = ArrayList<String>()
            assertTrue(MyanmarGlyphText(log = { lines += it }).isZawgyi(font))
            assertEquals(listOf("${font.name}: Zawgyi (code point U+1064), kept as drawn"), lines)
        }
    }

    @Test
    fun outlineHashesMatchGhashPyAndTheGlyphMap() {
        val map = MyanmarGlyphMap.bundled(context.assets)
        assertEquals(4996, map.size)
        open(WORD_2001) { document ->
            val used = usedGlyphs(document)
            val regular = used.keys.filterIsInstance<PDType0Font>().single { it.name.endsWith("-Regular") }
            val ttf = (regular.descendantFont as PDCIDFontType2).trueTypeFont
            // From tools/myanmar-glyph-map/ghash.py on the font embedded in the fixture.
            val expected = mapOf(
                0xAE to ("840498c8157f2f6a" to "\u102B\u103A"),
                0xBD to ("be824d75b7f1c384" to "\u1004\u103A\u1039"),
                0xC6 to ("16c2a20ef7d3a3b5" to "\u103C"),
                0xD3 to ("662d7193faa08850" to "\u1039\u1000"),
                0x174 to ("c142032db5b5751e" to "\u1031"),
                // A composite glyph.
                0x18A to ("e9d2c484a2cdca8c" to "\u1030\u1037"),
            )
            for ((gid, value) in expected) {
                val hash = MyanmarGlyphHash.of(ttf.glyph.getGlyph(gid).description)
                assertEquals("glyph $gid", value.first, hash?.let(MyanmarGlyphHash::hex))
                assertEquals("glyph $gid", value.second, map[hash!!])
            }
            // Glyph 0xD5 (္ခ) is not drawn here, so the subset has no outline for it; in the full font ghash.py gives:
            assertEquals("\u1039\u1001", map[MyanmarGlyphHash.parse("50c6604230b34eac")!!])
            // Every glyph drawn with a broken font that the cmap does not name and that has an outline is in the map.
            var looked = 0
            for ((font, codes) in used) {
                if (font !is PDType0Font) continue
                val fontTtf = (font.descendantFont as PDCIDFontType2).trueTypeFont
                val cmap = fontTtf.cmap.getSubtable(CmapTable.PLATFORM_WINDOWS, CmapTable.ENCODING_WIN_UNICODE_BMP)
                for (code in codes) {
                    val gid = font.codeToGID(code)
                    if (cmap.getCharCodes(gid).orEmpty().any { it >= 0x20 }) continue
                    val hash = MyanmarGlyphHash.of(fontTtf.glyph.getGlyph(gid).description) ?: continue
                    assertNotNull("${font.name} glyph $gid", map[hash])
                    looked++
                }
            }
            assertTrue(looked >= expected.size - 1)
        }
    }

    @Test
    fun glyphTextComesFromTheCmapThenTheGlyphMap() {
        assertEquals(MyanmarGlyphText.Counts(2, 1155, 71, 3, 0), counts(WORD_2001, MyanmarFixtures.glyphText(context)))
        assertEquals(MyanmarGlyphText.Counts(2, 1155, 71, 11, 0), counts(WORD_2107, MyanmarFixtures.glyphText(context)))
        // Without the map, those glyphs keep their ToUnicode text.
        assertEquals(MyanmarGlyphText.Counts(2, 1155, 0, 3, 71), counts(WORD_2001, MyanmarGlyphText()))
        assertEquals(MyanmarGlyphText.Counts(0, 0, 0, 0, 0), counts(VISUAL, MyanmarFixtures.glyphText(context)))
    }

    @Test
    fun clearForgetsTheFonts() {
        val lines = ArrayList<String>()
        // Without a map every glyph that is not in the cmap is logged once, when it is first read.
        val glyphs = MyanmarGlyphText(log = { lines += it })
        fun resolved() = lines.count { "no text for glyph" in it }
        open(WORD_2001) { document ->
            val text = PdfBoxTextSource.extract(document, 0, glyphs).text
            val once = resolved()
            assertTrue(once > 0)
            PdfBoxTextSource.extract(document, 0, glyphs)
            assertEquals(once, resolved())
            glyphs.clear()
            assertEquals(text, PdfBoxTextSource.extract(document, 0, glyphs).text)
            assertEquals(2 * once, resolved())
        }
    }

    private fun counts(name: String, glyphs: MyanmarGlyphText): MyanmarGlyphText.Counts =
        open(name) { document ->
            PdfBoxTextSource.extract(document, 0, glyphs)
            glyphs.counts()
        }

    private fun toUnicodeCMap(bfchar: String): String =
        """
        /CIDInit /ProcSet findresource begin
        12 dict begin
        begincmap
        /CMapName /Test def
        /CMapType 2 def
        1 begincodespacerange
        <0000> <FFFF>
        endcodespacerange
        1 beginbfchar
        $bfchar
        endbfchar
        endcmap
        CMapName currentdict /CMap defineresource pop
        end
        end
        """.trimIndent()

    private fun <T> open(name: String, block: (PDDocument) -> T): T =
        PDDocument.load(MyanmarFixtures.pdf(name, temp.root)).use(block)

    /** The character codes drawn on the first page, by font. */
    private fun usedGlyphs(document: PDDocument): Map<PDFont, Set<Int>> {
        val used = LinkedHashMap<PDFont, MutableSet<Int>>()
        object : PDFMarkedContentExtractor() {
            override fun processTextPosition(text: TextPosition) {
                val font = text.font ?: return
                used.getOrPut(font) { LinkedHashSet() } += text.characterCodes?.toList().orEmpty()
            }
        }.processPage(document.getPage(0))
        return used
    }
}
