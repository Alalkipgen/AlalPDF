package com.alalkipgen.alalpdf.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class MyanmarTextAssemblerTest {
    private val frame = PageFrame(WIDTH, HEIGHT, 0)

    @Test
    fun markDrawnRightOfTheNextConsonantStaysWithItsBase() {
        // ွ is drawn under လ, but its origin is right of the next consonant.
        val text = assemble(
            glyph("လ", 0f, 6f),
            glyph("ွ", 9f, 0f),
            glyph("တ", 6f, 6f),
            glyph("်", 12f, 0f),
        )
        assertEquals("လွတ်", text)
    }

    @Test
    fun runsDrawnOutOfOrderAreReadLeftToRight() {
        // LibreOffice can draw a later run first and emits no space glyphs.
        val text = assemble(*word("World", 40f), *word("Hello", 10f))
        assertEquals("Hello World", text)
    }

    @Test
    fun missingSpacesAreAddedOnlyForRealGaps() {
        val text = assemble(
            glyph("H", 0f, 5f),
            // Kerning-sized gaps, up to the 0.22 em seen after ါ် in Google Docs exports.
            glyph("i", 5.5f, 2.5f),
            *word("there", 11f),
            glyph(",", 38.2f, 2f),
        )
        assertEquals("Hi there,", text)
    }

    @Test
    fun glyphsWithoutTextAreSkipped() {
        // An unmapped glyph code comes out as a control character; an empty /ActualText has no text.
        val text = assemble(
            glyph("က", 0f, 6f),
            glyph("\u0002", 6f, 3f),
            glyph("", 9f, 3f, span = 4),
            glyph("ခ", 6f, 6f),
        )
        assertEquals("ကခ", text)
    }

    @Test
    fun noSpaceIsAddedBeforeAMark() {
        // The dot below and asat sit well right of the base glyph.
        val text = assemble(
            glyph("ည", 0f, 6f),
            glyph("့", 9f, 0f),
            glyph("်", 10f, 0f),
        )
        assertEquals("ည့်", text)
    }

    @Test
    fun markAfterASpaceIsKept() {
        // A mark with no base before it stays where it was drawn.
        val text = assemble(
            glyph("မ", 0f, 6f),
            glyph(" ", 6f, 3f),
            glyph("ိ", 9f, 3f),
            glyph("က", 12f, 6f),
        )
        assertEquals("မ ိက", text)
    }

    @Test
    fun glyphsInVisualOrderAreReadInLogicalOrder() {
        // Without /ActualText, as Word writes it: ေ is drawn before its consonant, kinzi after it.
        val layer = MyanmarTextAssembler.assemble(
            0,
            listOf(
                glyph("ေ", 0f, 4f),
                glyph("က", 4f, 6f),
                glyph("ာ", 10f, 4f),
                glyph(" ", 14f, 3f),
                glyph("သ", 17f, 6f),
                glyph("ခ", 23f, 6f),
                glyph("င်္", 24f, 0f),
                glyph("ျ", 29f, 2f),
                glyph("ာ", 31f, 4f),
            ),
            frame,
            MyanmarText::normalize,
        )
        assertEquals("ကော သင်္ချာ", layer.text)
        assertEquals(listOf("ကော", " ", "သ", "င်္ချာ"), layer.runs.map { it.text })
        // A syllable selects as one run over all of its glyphs.
        val syllable = layer.runs.first()
        assertEquals(0f, syllable.left, EPSILON)
        assertEquals(14f / WIDTH, syllable.right, EPSILON)
    }

    @Test
    fun actualTextSpansAreNotPartOfASyllable() {
        // ActualText is logical already and stays as it is: the ေ before it is not moved into it.
        val text = assemble(
            glyph("ေ", 0f, 4f),
            glyph("ကျ", 4f, 6f, span = 1),
            glyph("ကျ", 6f, 3f, span = 1),
            glyph(" ", 13f, 3f),
            glyph("ေ", 16f, 4f),
            glyph("ခ", 20f, 6f),
        )
        assertEquals("ေကျ ခေ", text)
    }

    @Test
    fun actualTextIsEmittedOnceForTheWholeSpan() {
        // PDFium returned "လုံး" and then "ုံ" again for this span.
        val layer = MyanmarTextAssembler.assemble(
            0,
            listOf(
                glyph("လုံး", 0f, 6f, span = 1),
                glyph("လုံး", 4f, 0f, span = 1),
                glyph("လုံး", 6f, 3f, span = 1),
                glyph(" ", 9f, 3f),
                glyph("ပါ", 12f, 6f, span = 2),
                glyph("ပါ", 18f, 4f, span = 2),
            ),
            frame,
        )
        assertEquals("လုံး ပါ", layer.text)
        assertEquals(listOf("လုံး", " ", "ပါ"), layer.runs.map { it.text })
        val span = layer.runs.first()
        assertEquals(0f, span.left, EPSILON)
        assertEquals(9f / WIDTH, span.right, EPSILON)
    }

    @Test
    fun fakeBoldCopiesAreDropped() {
        val text = assemble(*word("Bold", 0f), *word("Bold", 0.3f), glyph(" ", 20.3f, 3f), *word("all", 24f))
        assertEquals("Bold all", text)
    }

    @Test
    fun linesSplitByBaselineNotByGlyphBoxes() {
        val text = assemble(
            glyph("က", 0f, 6f, baseline = 100f),
            glyph("ို", 2f, 0f, baseline = 100f),
            // Baseline jitter well under half the font size stays on the line.
            glyph("ခ", 6f, 6f, baseline = 101.5f),
            // Tall glyphs overlap the previous line, but the baseline moved by a line.
            glyph("ဂ", 0f, 6f, baseline = 112f, top = 95f),
            glyph("ဃ", 6f, 6f, baseline = 112f, top = 95f),
        )
        assertEquals("ကိုခ\nဂဃ", text)
    }

    @Test
    fun runsSpellTheTextWithZeroWidthLineBreaks() {
        val layer = MyanmarTextAssembler.assemble(
            3,
            listOf(*word("One two", 0f), *word("three", 0f, baseline = 120f)),
            frame,
        )
        assertEquals("One two\nthree", layer.text)
        assertEquals(layer.text, layer.runs.joinToString("") { it.text })
        assertEquals(2, layer.lineCount)
        val lineBreak = layer.runs.single { it.text == "\n" }
        assertEquals(lineBreak.left, lineBreak.right, 0f)
        assertEquals(35f / WIDTH, lineBreak.left, EPSILON)
        layer.runs.forEach { run ->
            assertEquals(3, run.page)
            assertEquals(true, run.character)
        }
    }

    @Test
    fun boxesFollowThePageRotation() {
        // A box near the top-left of an unrotated 200 x 100 page.
        val box = PageFrame(200f, 100f, 0).normalize(0, 20f, 10f, 60f, 30f)
        assertBox(PdfTextRunBox(0.1f, 0.1f, 0.3f, 0.3f), box)
        // /Rotate turns the rendered page clockwise.
        assertBox(PdfTextRunBox(0.7f, 0.1f, 0.9f, 0.3f), PageFrame(200f, 100f, 90).normalize(0, 20f, 10f, 60f, 30f))
        assertBox(PdfTextRunBox(0.7f, 0.7f, 0.9f, 0.9f), PageFrame(200f, 100f, 180).normalize(0, 20f, 10f, 60f, 30f))
        assertBox(PdfTextRunBox(0.1f, 0.7f, 0.3f, 0.9f), PageFrame(200f, 100f, 270).normalize(0, 20f, 10f, 60f, 30f))
        // Text running up the unrotated page: its frame is the page turned by 90 degrees,
        // 100 wide and 200 tall. Frame x runs up from the bottom edge, frame y from the left edge.
        assertBox(PdfTextRunBox(0.1f, 0.4f, 0.3f, 0.8f), PageFrame(200f, 100f, 0).normalize(90, 20f, 20f, 60f, 60f))
        // On a page with /Rotate 90 the same text reads left to right.
        assertBox(PdfTextRunBox(0.2f, 0.1f, 0.6f, 0.3f), PageFrame(200f, 100f, 90).normalize(90, 20f, 20f, 60f, 60f))
    }

    private fun assemble(vararg items: GlyphItem): String =
        MyanmarTextAssembler.assemble(0, items.toList(), frame, MyanmarText::normalize).text

    private fun word(text: String, left: Float, baseline: Float = 100f): Array<GlyphItem> =
        text.mapIndexed { index, char -> glyph(char.toString(), left + index * 5f, 5f, baseline) }.toTypedArray()

    private fun glyph(
        text: String,
        left: Float,
        width: Float,
        baseline: Float = 100f,
        span: Int? = null,
        top: Float = baseline - 7f,
    ) = GlyphItem(text, left, left + width, top, baseline, baseline, FONT_SIZE, span)

    private fun assertBox(expected: PdfTextRunBox, actual: PdfTextRunBox) {
        assertEquals(expected.left, actual.left, EPSILON)
        assertEquals(expected.top, actual.top, EPSILON)
        assertEquals(expected.right, actual.right, EPSILON)
        assertEquals(expected.bottom, actual.bottom, EPSILON)
    }

    private companion object {
        const val WIDTH = 200f
        const val HEIGHT = 300f
        const val FONT_SIZE = 10f
        const val EPSILON = 1e-5f
    }
}
