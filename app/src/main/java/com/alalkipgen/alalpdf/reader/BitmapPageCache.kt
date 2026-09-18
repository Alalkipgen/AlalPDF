package com.alalkipgen.alalpdf.reader

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Caches rendered PDF pages.
 *
 * Bitmaps are never recycled here. Evicted entries may still be referenced by
 * the UI while a recomposition is in flight, and recycling them caused
 * "Canvas: trying to use a recycled bitmap" crashes while scrolling. Dropping
 * the reference is enough; the garbage collector reclaims the memory.
 */
class BitmapPageCache(maxMemoryBytes: Int) {
    private val cache = object : LruCache<Int, Bitmap>(maxMemoryBytes.coerceAtLeast(1)) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    @Synchronized fun get(page: Int): Bitmap? = cache.get(page)

    @Synchronized fun put(page: Int, bitmap: Bitmap): Bitmap {
        cache.put(page, bitmap)
        return bitmap
    }

    @Synchronized fun snapshot(): Map<Int, Bitmap> = cache.snapshot()

    @Synchronized fun clear() {
        cache.evictAll()
    }
}