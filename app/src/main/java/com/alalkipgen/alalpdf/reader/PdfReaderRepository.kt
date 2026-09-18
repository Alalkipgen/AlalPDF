package com.alalkipgen.alalpdf.reader

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PdfReaderRepository(private val resolver: ContentResolver) {
    private val rendererMutex = Mutex()
    suspend fun pageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        rendererMutex.withLock { open(uri).use { it.pageCount } }
    }

    suspend fun render(uri: Uri, pageIndex: Int, width: Int, nightMode: Boolean = false): Bitmap = withContext(Dispatchers.IO) {
        rendererMutex.withLock { open(uri).use { it.renderPage(pageIndex, width, nightMode) } }
    }

    private fun open(uri: Uri): PdfRendererSource {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: error("Unable to open PDF: no readable file descriptor")
        return runCatching { PdfRendererSource.open(descriptor) }
            .getOrElse { throw IllegalArgumentException("Unable to open PDF. The file may be corrupted or password protected.", it) }
    }
}