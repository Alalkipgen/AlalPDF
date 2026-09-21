package com.alalkipgen.alalpdf.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.Closeable

internal class PdfRendererSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : PdfRenderEngine {
    override val pageCount: Int get() = renderer.pageCount

    /**
     * Renders one page.
     *
     * [PdfRenderer] only accepts ARGB_8888 destinations, so [config] is applied
     * as a post-render copy. That is worth doing for thumbnails, where RGB_565
     * halves the memory held by the cache.
     *
     * Night mode is deliberately *not* handled here. It used to run a
     * getPixels/invert/setPixels pass over the whole bitmap on the render
     * thread, which is one of the slowest things you can do per page, and the
     * result was then discarded because the UI already applies an inverting
     * ColorFilter when drawing.
     */
    override fun renderPage(
        pageIndex: Int,
        width: Int,
        config: Bitmap.Config,
    ): Bitmap {
        require(pageIndex in 0 until renderer.pageCount)
        require(width > 0)
        renderer.openPage(pageIndex).use { page ->
            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            if (config == Bitmap.Config.ARGB_8888) return bitmap
            val converted = runCatching { bitmap.copy(config, false) }.getOrNull() ?: return bitmap
            bitmap.recycle()
            return converted
        }
    }

    /** Page aspect ratio (height / width) without rendering any pixels. */
    override fun pageAspectRatio(pageIndex: Int): Float {
        require(pageIndex in 0 until renderer.pageCount)
        renderer.openPage(pageIndex).use { page ->
            return page.height.toFloat() / page.width.coerceAtLeast(1)
        }
    }

    override fun textRuns(pageIndex: Int): List<PdfTextRun> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return emptyList()
        return textRunsApi35(pageIndex)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun textRunsApi35(pageIndex: Int): List<PdfTextRun> {
        require(pageIndex in 0 until renderer.pageCount)
        renderer.openPage(pageIndex).use { page ->
            val width = page.width.toFloat().coerceAtLeast(1f)
            val height = page.height.toFloat().coerceAtLeast(1f)
            return page.textContents.mapNotNull { content ->
                if (content.text.isBlank() || content.bounds.isEmpty()) return@mapNotNull null
                val bounds = android.graphics.RectF(content.bounds.first())
                content.bounds.drop(1).forEach { bounds.union(it) }
                PdfTextRun(pageIndex, content.text, bounds.left / width, bounds.top / height, bounds.right / width, bounds.bottom / height)
            }
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
