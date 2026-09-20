package com.alalkipgen.alalpdf.tools

import android.content.Context
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.alalkipgen.alalpdf.R
import java.io.File

/**
 * Renders text into a one page PDF using Android's own text stack.
 *
 * PDFBox cannot shape complex scripts: it writes one glyph per code point in
 * logical order, so Burmese comes out as a string of broken, misordered
 * clusters (PDFBOX-4189 is still open). Android's text engine does full
 * HarfBuzz shaping, and `PdfDocument` writes the result as real vector text
 * with an embedded font subset.
 *
 * The page produced here is later stamped onto the real page as a form
 * XObject, which keeps the original layout, links and outline intact.
 */
object ShapedTextOverlay {

    /** [file] holds a single page of exactly [widthPoints] x [heightPoints]. */
    data class Overlay(val file: File, val widthPoints: Float, val heightPoints: Float)

    fun render(
        context: Context,
        text: String,
        widthPoints: Float,
        fontSizePoints: Float,
        bold: Boolean = false,
        color: Int = android.graphics.Color.BLACK,
    ): Overlay? {
        if (text.isBlank() || widthPoints <= 1f || fontSizePoints <= 0f) return null
        val typeface = runCatching {
            ResourcesCompat.getFont(
                context,
                if (bold) R.font.pyidaungsu_bold else R.font.pyidaungsu_regular,
            )
        }.getOrNull() ?: Typeface.DEFAULT

        // A PdfDocument page uses points, so one pixel here is one point.
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = fontSizePoints
            this.color = color
        }
        val width = widthPoints.toInt().coerceAtLeast(8)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(fontSizePoints * 0.35f, 1f)
            .setIncludePad(false)
            .build()
        val height = layout.height.coerceAtLeast(1)

        val document = PdfDocument()
        return runCatching {
            val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
            layout.draw(page.canvas)
            document.finishPage(page)
            val file = File.createTempFile("overlay", ".pdf", context.cacheDir)
            file.outputStream().use { document.writeTo(it) }
            Overlay(file, width.toFloat(), height.toFloat())
        }.getOrNull().also { document.close() }
    }
}
