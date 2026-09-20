package com.alalkipgen.alalpdf.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CanonicalDocumentKeyTest {
    @Test fun aliasesShareIdentity() {
        assertEquals(canonicalDocumentKey("A.pdf", 42, 1_000), canonicalDocumentKey(" a.PDF ", 42, 1_000))
    }

    @Test fun nearbyProviderTimestampsShareIdentity() {
        assertEquals(canonicalDocumentKey("A.pdf", 42, 1_000), canonicalDocumentKey("A.pdf", 42, 1_999))
    }

    @Test fun differentFilesDiffer() {
        assertNotEquals(canonicalDocumentKey("A.pdf", 42, 1_000), canonicalDocumentKey("A.pdf", 43, 1_000))
    }
}
