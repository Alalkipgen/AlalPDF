package com.alalkipgen.alalpdf.reader

import kotlin.math.abs

/**
 * Small, testable policy object for the reader's memory and prefetch choices.
 *
 * The values deliberately leave most of the heap to Compose, the Android
 * renderer and PDFium. A PDF page is transient UI, not an image-gallery cache.
 */
internal object ReaderMemoryPolicy {
    private const val MIB = 1024L * 1024L

    fun cacheBudgetBytes(maxHeapBytes: Long, lowRamDevice: Boolean): Int {
        val upperBound = if (lowRamDevice) 12L * MIB else 24L * MIB
        val lowerBound = if (lowRamDevice) 4L * MIB else 8L * MIB
        return (maxHeapBytes / 8L)
            .coerceIn(lowerBound, upperBound)
            .toInt()
    }

    fun maxRenderWidth(lowRamDevice: Boolean): Int = if (lowRamDevice) 900 else 1_200

    /**
     * Current page first, then the side the user is moving toward.
     */
    fun renderOrder(center: Int, pageCount: Int, distance: Int, direction: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        val result = ArrayList<Int>(distance * 2 + 1)
        result += center.coerceIn(0, pageCount - 1)
        val firstSign = if (direction < 0) -1 else 1
        for (offset in 1..distance) {
            val first = center + firstSign * offset
            val second = center - firstSign * offset
            if (first in 0 until pageCount) result += first
            if (second in 0 until pageCount) result += second
        }
        return result.distinct()
    }

    fun pagesToKeep(center: Int, pageCount: Int, distance: Int): Set<Int> =
        (0 until pageCount).filterTo(mutableSetOf()) { abs(it - center) <= distance }
}