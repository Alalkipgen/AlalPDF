package com.alalkipgen.alalpdf.reader

/** Clickable web annotation bounds normalized to the rendered page. */
data class PdfPageLink(val left: Float, val top: Float, val right: Float, val bottom: Float, val url: String)
