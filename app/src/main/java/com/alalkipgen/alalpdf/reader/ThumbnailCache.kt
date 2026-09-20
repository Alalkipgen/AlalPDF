package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * Small, persistent page thumbnails.
 *
 * The page grid used to read the live render map, which the reader trims to
 * the pages around the current one. That is why only two or three thumbnails
 * ever appeared. Thumbnails need their own storage with a different lifetime,
 * so they are kept here: RGB_565 in memory and WebP on disk, which means the
 * grid is instant the second time a document is opened.
 */
class ThumbnailCache(context: Context, documentKey: String) {

    private val memory = object : LruCache<Int, Bitmap>(MEMORY_BUDGET_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    private val directory = File(File(context.cacheDir, "thumbs"), documentKey.stableName())

    fun get(page: Int): Bitmap? {
        memory.get(page)?.let { return it }
        val file = fileFor(page)
        if (!file.exists()) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
            ?: return null
        memory.put(page, decoded)
        return decoded
    }

    fun put(page: Int, bitmap: Bitmap) {
        memory.put(page, bitmap)
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            fileFor(page).outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, QUALITY, output)
            }
        }
    }

    fun clearMemory() {
        memory.evictAll()
    }

    private fun fileFor(page: Int) = File(directory, page.toString() + ".webp")

    private companion object {
        const val MEMORY_BUDGET_BYTES = 6 * 1024 * 1024
        const val QUALITY = 70

        fun String.stableName(): String {
            var hash = 0L
            for (character in this) hash = hash * 31 + character.code
            return java.lang.Long.toHexString(hash)
        }
    }
}
