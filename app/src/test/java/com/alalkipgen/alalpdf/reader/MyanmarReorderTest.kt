package com.alalkipgen.alalpdf.reader

import com.alalkipgen.alalpdf.reader.MyanmarReorder.Syllable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyanmarReorderTest {
    @Test
    fun allReorderVectorsPass() {
        // expected<TAB>glyph texts in visual order joined by "|" (HarfBuzz + Noto Sans Myanmar 2.001).
        val rows = resource("myanmar/reorder_vectors.tsv").lines()
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { row -> row.split('\t').let { (expected, glyphs) -> expected to glyphs.split('|') } }
        assertEquals(61, rows.size)
        val failures = rows.filter { (expected, glyphs) -> MyanmarReorder.reorder(glyphs) != expected }
            .map { (expected, glyphs) -> "$expected <- ${glyphs.joinToString("|")}: ${MyanmarReorder.reorder(glyphs)}" }
        assertEquals(emptyList<String>(), failures)
        // Visual order never looks like logical order.
        val logical = rows.filter { (_, glyphs) -> MyanmarReorder.isLogicalOrder(glyphs) }.map { it.first }
        assertEquals(emptyList<String>(), logical)
    }

    @Test
    fun logicalOrderHasAMarkRightAfterAPreBaseGlyph() {
        assertTrue(MyanmarReorder.isLogicalOrder(listOf("က", "ေ", "ာ")))
        assertTrue(MyanmarReorder.isLogicalOrder(listOf("က", "ြ", "", "ီ", "း")))
        assertTrue(MyanmarReorder.isLogicalOrder(listOf("A", "မ", "ြ", "ေ", "ာ", "က", "်")))
        // In visual order ေ and ြ come before their base; kinzi starts with a base.
        assertFalse(MyanmarReorder.isLogicalOrder(listOf("ေ", "က", "ာ")))
        assertFalse(MyanmarReorder.isLogicalOrder(listOf("ေ", "ြ", "", "က", "ာ")))
        assertFalse(MyanmarReorder.isLogicalOrder(listOf("ေ", "င်္")))
        assertFalse(MyanmarReorder.isLogicalOrder(listOf("ေ", "A", "ာ")))
    }

    @Test
    fun aSyllableCoversItsGlyphsInVisualOrder() {
        // ေ is drawn before its consonant, kinzi after it.
        assertEquals(
            listOf(Syllable("ကော", 0, 2), Syllable(" ", 3, 3), Syllable("သ", 4, 4), Syllable("င်္ချာ", 5, 8)),
            MyanmarReorder.syllables(listOf("ေ", "က", "ာ", " ", "သ", "ခ", "င်္", "ျ", "ာ")),
        )
    }

    @Test
    fun textThatIsNotMyanmarIsAUnitOfItsOwn() {
        assertEquals(
            listOf(Syllable("ကြ", 0, 1), Syllable("A", 2, 2), Syllable("ေ", 4, 4)),
            MyanmarReorder.syllables(listOf("ြ", "က", "A", "", "ေ")),
        )
    }

    @Test
    fun asatFollowsTheOpenTypeOrder() {
        // Asat stays before a medial drawn after it (ယောက်ျား), goes before ု (ကျွန်ုပ်) and after ့.
        assertEquals("က်ျ", MyanmarReorder.reorder(listOf("က", "်", "ျ")))
        assertEquals("န်ု", MyanmarReorder.reorder(listOf("န", "ု", "်")))
        assertEquals("င့်", MyanmarReorder.reorder(listOf("င", "်", "့")))
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)) { "missing $path" }.readText()
}
