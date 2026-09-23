package com.alalkipgen.alalpdf.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailPolicyTest {
    @Test
    fun cacheBudgetUsesHeapFractionWithSafeBounds() {
        assertEquals(4 * 1024 * 1024, ThumbnailPolicy.cacheSizeBytes(64L * 1024 * 1024))
        assertEquals(8 * 1024 * 1024, ThumbnailPolicy.cacheSizeBytes(256L * 1024 * 1024))
        assertEquals(12 * 1024 * 1024, ThumbnailPolicy.cacheSizeBytes(1024L * 1024 * 1024))
    }

    @Test
    fun thumbnailDimensionsStayNearTheirOnScreenSize() {
        assertEquals(ThumbnailRenderSize(64, 80), ThumbnailPolicy.renderSize(1, 1))
        assertEquals(ThumbnailRenderSize(144, 180), ThumbnailPolicy.renderSize(144, 180))
        assertEquals(ThumbnailRenderSize(192, 256), ThumbnailPolicy.renderSize(800, 4000))
    }
}