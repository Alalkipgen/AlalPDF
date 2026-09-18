package com.alalkipgen.alalpdf.reader

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps a single [PdfRendererSource] open for the current document.
 *
 * Re-opening a large PDF for every page made a 27 MB / 109 page file appear to
 * hang: each placeholder queued another open of the whole file behind the same
 * lock. Holding one renderer makes page rendering cheap and serialised, which
 * is required anyway because PdfRenderer is not thread safe.
 */
class PdfReaderRepository(private val resolver: ContentResolver) {
    private val lock = Any()
    private var currentUri: Uri? = null
    private var source: PdfRendererSource? = null

    suspend fun pageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        synchronized(lock) { ensureOpen(uri).pageCount }
    }

    suspend fun render(uri: Uri, pageIndex: Int, width: Int, nightMode: Boolean = false): Bitmap =
        withContext(Dispatchers.IO) {
            synchronized(lock) { ensureOpen(uri).renderPage(pageIndex, width, nightMode) }
        }

    fun close() {
        synchronized(lock) {
            runCatching { source?.close() }
            source = null
            currentUri = null
        }
    }

    private fun ensureOpen(uri: Uri): PdfRendererSource {
        val existing = source
        if (existing != null && currentUri == uri) return existing
        runCatching { existing?.close() }
        source = null
        currentUri = null
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: error("Unable to open PDF: no readable file descriptor")
        val opened = runCatching { PdfRendererSource.open(descriptor) }
            .getOrElse { throw IllegalArgumentException("Unable to open PDF. The file may be corrupted or password protected.", it) }
        source = opened
        currentUri = uri
        return opened
    }
}
