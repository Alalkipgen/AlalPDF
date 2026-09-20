package com.alalkipgen.alalpdf.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text search and Myanmar normalisation.
 *
 * These replace the old test for `PdfTextExtractor.search`, which searched a
 * list of already extracted pages. Text is now pulled one page at a time, so
 * matching is a pure function of a single page's text.
 */
class PdfTextExtractorTest {

    @Test
    fun findsCaseInsensitiveMatch() {
        val hits = PdfTextSearch.matches(0, "\u1019\u103C\u1014\u103A\u1019\u102C Search", "search")
        assertEquals(1, hits.size)
        assertEquals(0, hits.first().page)
    }

    @Test
    fun reportsEveryMatchOnThePage() {
        val hits = PdfTextSearch.matches(3, "alpha beta alpha gamma alpha", "alpha")
        assertEquals(3, hits.size)
        assertEquals(listOf(0, 1, 2), hits.map { it.matchIndex })
        assertTrue(hits.all { it.page == 3 })
    }

    @Test
    fun blankQueryFindsNothing() {
        assertTrue(PdfTextSearch.matches(0, "some text", "   ").isEmpty())
    }

    @Test
    fun normalizeRemovesInvisibleCharacters() {
        assertEquals("ab", MyanmarText.normalize("a\u200Bb\uFEFF"))
    }

    @Test
    fun plainUnicodeBurmeseIsNotTreatedAsZawgyi() {
        // မြန်မာစာ: an asat followed by a consonant is ordinary Unicode.
        assertFalse(MyanmarText.looksLikeZawgyi("\u1019\u103C\u1014\u103A\u1019\u102C\u1005\u102C"))
    }

    @Test
    fun leadingVowelIsTreatedAsZawgyi() {
        // A vowel sign written before its consonant only happens in Zawgyi.
        assertTrue(MyanmarText.looksLikeZawgyi("\u1031\u1000"))
    }

    @Test
    fun searchKeyLeavesUnicodeTextAlone() {
        val text = "\u1019\u103C\u1014\u103A\u1019\u102C\u1005\u102C"
        assertEquals(text, MyanmarText.searchKey(text))
    }
}
