package com.alalkipgen.alalpdf.library

import android.net.Uri

data class PdfDocument(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val lastReadPage: Int = 0,
)
