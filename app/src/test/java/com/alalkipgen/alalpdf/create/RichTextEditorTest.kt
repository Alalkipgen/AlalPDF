package com.alalkipgen.alalpdf.create

import org.junit.Assert.assertEquals
import org.junit.Test

class RichTextEditorTest {
    @Test
    fun preservesHttpAndHttpsUrls() {
        assertEquals("https://example.com/page", normalizeHttpUrl(" https://example.com/page "))
        assertEquals("http://example.com", normalizeHttpUrl("http://example.com"))
    }

    @Test
    fun addsHttpsToBareDomains() {
        assertEquals("https://example.com", normalizeHttpUrl("example.com"))
    }

    @Test
    fun trimsParagraphBreaksFromPdfLinkAnnotations() {
        val text = "Open website \n\nNext page"
        assertEquals("Open website".length, trimLinkEnd(text, 0, "Open website \n".length))
    }

    @Test
    fun doesNotTrimCharactersInsideTheLink() {
        val text = "Open website"
        assertEquals(text.length, trimLinkEnd(text, 0, text.length))
    }
}
