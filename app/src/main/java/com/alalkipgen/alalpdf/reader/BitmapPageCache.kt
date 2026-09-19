package com.alalkipgen.alalpdf.reader

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Byte-bounded cache for rendered PDF pages.
 *
 * Cache eviction only releases the cache reference. It must never remove the
 * bitmap currently displayed by Compose: in landscape a single ARGB page can
 * exceed the cache budget and coupling eviction to UI state causes a visible
 * render/blank/render loop.
 *
 * Bitmaps are not recycled here because Compose may still be drawing an
 * evicted bitmap. The garbage collector reclaims it after the UI releases it.
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

    @Synchronized fun clear() {
        cache.evictAll()
    }
}
