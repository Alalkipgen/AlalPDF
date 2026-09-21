package com.alalkipgen.alalpdf.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class VisiblePagePolicyTest {
    @Test
    fun choosesPageWithLargestVisibleArea_notFirstItem() {
        val selected = mostVisiblePage(
            viewportStart = 0,
            viewportEnd = 1_000,
            items = listOf(
                VisiblePageBounds(index = 17, offset = -850, size = 1_000),
                VisiblePageBounds(index = 18, offset = 160, size = 1_000),
            ),
            fallback = 17,
        )

        assertEquals(18, selected)
    }

    @Test
    fun usesFallbackWhenLayoutHasNoItems() {
        assertEquals(4, mostVisiblePage(0, 1_000, emptyList(), fallback = 4))
    }
}