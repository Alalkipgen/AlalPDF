package com.alalkipgen.alalpdf.reader

import android.graphics.Bitmap
import android.util.LruCache

class BitmapPageCache(maxMemoryBytes: Int) {
    private val cache = object : LruCache<Int, Bitmap>(maxMemoryBytes.coerceAtLeast(1)) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && oldValue !== newValue && !oldValue.isRecycled) oldValue.recycle()
        }
    }

    @Synchronized fun get(page: Int): Bitmap? = cache.get(page)
    @Synchronized fun put(page: Int, bitmap: Bitmap): Bitmap {
        cache.put(page, bitmap)?.let { previous -> if (previous !== bitmap && !previous.isRecycled) previous.recycle() }
        return bitmap
    }
    @Synchronized fun snapshot(): Map<Int, Bitmap> = cache.snapshot()
    @Synchronized fun clear() { cache.evictAll() }
}