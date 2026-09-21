package com.alalkipgen.alalpdf.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionIndexTest {
    private val runs = listOf(
        PdfTextRun(0, "a", 0.10f, 0.10f, 0.20f, 0.20f, true),
        PdfTextRun(0, "b", 0.20f, 0.10f, 0.30f, 0.20f, true),
        PdfTextRun(0, "c", 0.30f, 0.10f, 0.40f, 0.20f, true),
        PdfTextRun(0, "d", 0.10f, 0.30f, 0.20f, 0.40f, true),
        PdfTextRun(0, "e", 0.20f, 0.30f, 0.30f, 0.40f, true),
    )

    @Test
    fun picksNearestCharacterInLine() {
        val index = SelectionIndex(runs)
        assertEquals(0, index.hit(0.11f, 0.15f))
        assertEquals(2, index.hit(0.38f, 0.15f))
        assertEquals(4, index.hit(0.27f, 0.35f))
    }

    @Test
    fun snapsVerticalGapsToNearestLine() {
        val index = SelectionIndex(runs)
        assertEquals(1, index.hit(0.25f, 0.22f))
        assertEquals(4, index.hit(0.25f, 0.28f))
    }

    @Test
    fun clampsOutsidePageToNearestCharacter() {
        val index = SelectionIndex(runs)
        assertEquals(0, index.hit(0f, 0f))
        assertEquals(4, index.hit(1f, 1f))
    }
}