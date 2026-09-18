package com.alalkipgen.alalpdf.library

import android.net.Uri

data class PdfDocument(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val lastReadPage: Int = 0,
    val favorite: Boolean = false,
    val pageCount: Int = 0,
) {
    /** 0f..1f reading progress, or null when the total page count is unknown. */
    val progress: Float?
        get() = if (pageCount > 0) ((lastReadPage + 1).toFloat() / pageCount).coerceIn(0f, 1f) else null
}
