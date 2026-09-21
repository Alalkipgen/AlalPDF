package com.alalkipgen.alalpdf.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlalLinkMetadataTest {
    @Test
    fun roundTrip_preservesExactUrlAndBounds() {
        val expected = AlalStoredLink(
            page = 3,
            left = 0.12f,
            top = 0.34f,
            right = 0.56f,
            bottom = 0.78f,
            url = "https://example.com/source?q=မြန်မာ&chapter=2",
        )

        assertEquals(expected, AlalLinkMetadata.decode(AlalLinkMetadata.encode(expected)))
    }

    @Test
    fun decode_rejectsUnsafeSchemes() {
        val unsafe = AlalStoredLink(0, 0.1f, 0.1f, 0.2f, 0.2f, "javascript:alert(1)")
        assertNull(AlalLinkMetadata.decode(AlalLinkMetadata.encode(unsafe)))
    }
}