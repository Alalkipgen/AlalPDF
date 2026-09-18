package com.alalkipgen.alalpdf.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable

class PdfRendererSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {
    val pageCount: Int get() = renderer.pageCount

    fun renderPage(pageIndex: Int, width: Int): Bitmap {
        require(pageIndex in 0 until renderer.pageCount)
        require(width > 0)
        renderer.openPage(pageIndex).use { page ->
            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    override fun close() {
        renderer.close()
        descriptor.close()
    }

    companion object {
        fun open(descriptor: ParcelFileDescriptor): PdfRendererSource =
            runCatching { PdfRendererSource(descriptor, PdfRenderer(descriptor)) }
                .getOrElse { descriptor.close(); throw it }
    }
}