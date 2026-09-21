package com.alalkipgen.alalpdf.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMemoryPolicyTest {
    @Test
    fun cacheBudget_isBoundedAndSmallerOnLowRamDevices() {
        val gib = 1024L * 1024L * 1024L
        val normal = ReaderMemoryPolicy.cacheBudgetBytes(gib, lowRamDevice = false)
        val lowRam = ReaderMemoryPolicy.cacheBudgetBytes(gib, lowRamDevice = true)

        assertEquals(24 * 1024 * 1024, normal)
        assertEquals(12 * 1024 * 1024, lowRam)
        assertTrue(lowRam < normal)
    }

    @Test
    fun renderOrder_prefetchesInScrollDirection() {
        assertEquals(
            listOf(5, 6, 4, 7, 3),
            ReaderMemoryPolicy.renderOrder(center = 5, pageCount = 10, distance = 2, direction = 1),
        )
        assertEquals(
            listOf(5, 4, 6, 3, 7),
            ReaderMemoryPolicy.renderOrder(center = 5, pageCount = 10, distance = 2, direction = -1),
        )
    }

    @Test
    fun renderOrder_neverRequestsOutsideDocument() {
        assertEquals(
            listOf(0, 1, 2),
            ReaderMemoryPolicy.renderOrder(center = 0, pageCount = 3, distance = 2, direction = -1),
        )
    }

    @Test
    fun renderOrder_keepsCurrentPageFirst() {
        val order = ReaderMemoryPolicy.renderOrder(
            center = 42,
            pageCount = 100,
            distance = 2,
            direction = 1,
        )
        assertEquals(42, order.first())
    }
}