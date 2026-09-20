package com.alalkipgen.alalpdf.reader

/** Text extracted by the platform renderer with normalized page coordinates. */
data class PdfTextRun(
    val page: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
