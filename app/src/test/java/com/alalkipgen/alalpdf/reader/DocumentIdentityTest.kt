package com.alalkipgen.alalpdf.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentIdentityTest {
    @Test
    fun `metadata aliases normalize case whitespace and timestamp precision`() {
        val first = DocumentIdentityResolver.metadataKey(
            name = "  My   File.PDF ",
            size = 42_000,
            modified = 1_800_000_000L,
        )
        val second = DocumentIdentityResolver.metadataKey(
            name = "my file.pdf",
            size = 42_000,
            modified = 1_800_000_000_900L,
        )
        assertEquals(first, second)
    }

    @Test
    fun `changed file size produces a different identity`() {
        val first = DocumentIdentityResolver.metadataKey("book.pdf", 10, 2_000)
        val second = DocumentIdentityResolver.metadataKey("book.pdf", 11, 2_000)
        assertNotEquals(first, second)
    }

    @Test
    fun `incomplete metadata does not create a collision prone key`() {
        assertNull(DocumentIdentityResolver.metadataKey("", 100, 1_000))
        assertNull(DocumentIdentityResolver.metadataKey("book.pdf", 0, 1_000))
    }
}