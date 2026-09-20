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
}
