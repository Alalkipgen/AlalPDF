package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import java.io.Closeable

/**
 * PDFium explicitly makes no thread-safety guarantee. This process-wide gate
 * covers every PdfiumTextSource, including short-lived tools and old reader
 * sessions that are finishing cancellation.
 */
internal object PdfiumGate {
    private val lock = Any()

    fun <T> call(block: () -> T): T = synchronized(lock, block)
}

/**
 * Character level text geometry, backed by PDFium.
 *
 * Selection used to be built from PDFBox word boxes, so the smallest thing a
 * user could grab was a whole word and the handles had nothing finer to snap
 * to. PDFium exposes a box per character (`FPDFText_GetCharBox`) on every
 * Android version, which is exactly what a Google Drive style selection needs:
 * character granularity, stable reading order, and boxes that line up with the
 * rendered bitmap.
 *
 * Boxes are returned in normalized page coordinates (0..1, origin top-left) so
 * the overlay does not care about the zoom level or the rendered size.
 */
internal class PdfiumTextSource private constructor(
    private val document: PdfDocument,
    private val descriptor: ParcelFileDescriptor,
) : PdfTextEngine {

    private val lock = Any()
    private val runCache = LruCache<Int, List<PdfTextRun>>(CACHE_PAGES)

    @Volatile
    private var closed = false

    override val pageCount: Int
        get() = PdfiumGate.call {
            if (closed) 0 else runCatching { document.getPageCount() }.getOrDefault(0)
        }

    /** One run per character, in reading order, with normalized coordinates. */
    override fun charRuns(page: Int): List<PdfTextRun> {
        if (closed || page < 0) return emptyList()
        runCache.get(page)?.let { return it }
        val runs = PdfiumGate.call {
            synchronized(lock) {
                if (closed) emptyList() else readCharRuns(page)
            }
        }
        runCache.put(page, runs)
        return runs
    }

    /** Page size in PDF points, or null when the page cannot be opened. */
    override fun pageSizePoints(page: Int): android.util.SizeF? = runCatching {
        PdfiumGate.call {
            synchronized(lock) {
                if (closed) return@call null
                document.openPage(page).use { pdfPage ->
                    android.util.SizeF(
                        pdfPage.getPageWidthPoint().toFloat(),
                        pdfPage.getPageHeightPoint().toFloat(),
                    )
                }
            }
        }
    }.getOrNull()

    /** Page text in the same order as [charRuns]. */
    override fun pageText(page: Int): String {
        val runs = charRuns(page)
        if (runs.isEmpty()) return ""
        return buildString { runs.forEach { append(it.text) } }
    }

    override fun pageLinks(page: Int): List<PdfPageLink> = runCatching {
        PdfiumGate.call {
            synchronized(lock) {
                if (closed) return@call emptyList()
                document.openPage(page).use { pdfPage ->
                    val width = pdfPage.getPageWidthPoint().toFloat().coerceAtLeast(1f)
                    val height = pdfPage.getPageHeightPoint().toFloat().coerceAtLeast(1f)
                    pdfPage.getPageLinks().mapNotNull { link: PdfDocument.Link ->
                        val url = link.uri?.trim()?.takeIf(::isSafeWebUrl)
                            ?: return@mapNotNull null
                        val box = link.bounds
                        val left = minOf(box.left, box.right) / width
                        val right = maxOf(box.left, box.right) / width
                        val top = 1f - maxOf(box.top, box.bottom) / height
                        val bottom = 1f - minOf(box.top, box.bottom) / height
                        rotateLinkBounds(
                            left.coerceIn(0f, 1f),
                            top.coerceIn(0f, 1f),
                            right.coerceIn(0f, 1f),
                            bottom.coerceIn(0f, 1f),
                            pdfPage.getPageRotation(),
                            url,
                        )
                    }.distinctBy { link ->
                        listOf(link.url, link.left, link.top, link.right, link.bottom)
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun readCharRuns(page: Int): List<PdfTextRun> = runCatching {
        document.openPage(page).use { pdfPage ->
            val width = pdfPage.getPageWidthPoint().toFloat()
            val height = pdfPage.getPageHeightPoint().toFloat()
            if (width <= 0f || height <= 0f) return emptyList()
            pdfPage.openTextPage().use { textPage ->
                val count = textPage.textPageCountChars()
                if (count <= 0) return emptyList()
                val text = textPage.textPageGetText(0, count).orEmpty()
                val runs = ArrayList<PdfTextRun>(count)
                for (i in 0 until minOf(count, text.length)) {
                    val symbol = text[i]
                    val box = runCatching { textPage.textPageGetCharBox(i) }.getOrNull()
                    if (box == null) {
                        // Line breaks and other zero-area characters still have
                        // to be part of the string so copied text keeps its
                        // shape; they just cannot be hit-tested.
                        runs.add(PdfTextRun(page, symbol.toString(), 0f, 0f, 0f, 0f, character = true))
                        continue
                    }
                    val left = minOf(box.left, box.right) / width
                    val right = maxOf(box.left, box.right) / width
                    // PDF coordinates grow upwards, the overlay grows downwards.
                    val top = 1f - maxOf(box.top, box.bottom) / height
                    val bottom = 1f - minOf(box.top, box.bottom) / height
                    runs.add(
                        PdfTextRun(
                            page = page,
                            text = symbol.toString(),
                            left = left.coerceIn(0f, 1f),
                            top = top.coerceIn(0f, 1f),
                            right = right.coerceIn(0f, 1f),
                            bottom = bottom.coerceIn(0f, 1f),
                            character = true,
                        ),
                    )
                }
                runs
            }
        }
    }.getOrDefault(emptyList())

    override fun close() {
        if (closed) return
        PdfiumGate.call {
            synchronized(lock) {
                if (closed) return@call
                closed = true
                runCatching { document.close() }
                runCatching { descriptor.close() }
            }
        }
    }

    private fun isSafeWebUrl(value: String): Boolean {
        val scheme = runCatching { Uri.parse(value).scheme?.lowercase() }.getOrNull()
        return scheme == "http" || scheme == "https"
    }

    private fun rotateLinkBounds(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotation: Int,
        url: String,
    ): PdfPageLink {
        val points = listOf(left to top, right to top, left to bottom, right to bottom)
            .map { (x, y) ->
                when (((rotation % 360) + 360) % 360) {
                    90 -> (1f - y) to x
                    180 -> (1f - x) to (1f - y)
                    270 -> y to (1f - x)
                    else -> x to y
                }
            }
        return PdfPageLink(
            points.minOf { it.first },
            points.minOf { it.second },
            points.maxOf { it.first },
            points.maxOf { it.second },
            url,
        )
    }

    companion object {
        private const val CACHE_PAGES = 8

        fun open(context: Context, uri: Uri, password: String?): PdfiumTextSource? = runCatching {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            open(context, descriptor, password) ?: run {
                descriptor.close()
                null
            }
        }.getOrNull()

        fun open(
            context: Context,
            descriptor: ParcelFileDescriptor,
            password: String?,
        ): PdfiumTextSource? = PdfiumGate.call {
            runCatching {
                val core = PdfiumCore(context)
                val document = if (password.isNullOrEmpty()) {
                    core.newDocument(descriptor)
                } else {
                    core.newDocument(descriptor, password)
                }
                PdfiumTextSource(document, descriptor)
            }.getOrElse {
                runCatching { descriptor.close() }
                null
            }
        }
    }
}
