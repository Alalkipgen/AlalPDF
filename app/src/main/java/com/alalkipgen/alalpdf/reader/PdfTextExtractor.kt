package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File

data class PdfPageText(val page: Int, val text: String)

/** One hit inside a page. [matchIndex] distinguishes repeats on the same page. */
data class PdfSearchResult(val page: Int, val excerpt: String, val matchIndex: Int = 0)

/**
 * Lazy, per-page text access for one document.
 *
 * The previous implementation opened the document, extracted **every** page,
 * and closed it again on every call. For a 109-page magazine that meant the
 * entire object model plus 109 stripper passes in one go, which reliably ran
 * out of memory on mid-range devices. Because the caller swallowed the
 * failure, the app simply reported "No selectable text" forever.
 *
 * Here the document is spooled to a cache file once and opened with
 * [MemoryUsageSetting.setupMixed] so PDFBox can use a scratch file instead of
 * the heap. Pages are extracted on demand and memoized.
 */
class PdfTextIndex private constructor(
    private val document: PDDocument,
    private val scratch: File?,
) : Closeable {

    val pageCount: Int get() = if (closed) 0 else document.numberOfPages

    private val cache = LruCache<Int, String>(CACHE_PAGES)
    private val runCache = LruCache<Int, List<PdfTextRun>>(CACHE_RUN_PAGES)

    @Volatile
    private var closed = false

    /** Extracted, normalized text for a single zero-based page. */
    fun pageText(page: Int): String {
        if (closed || page < 0 || page >= document.numberOfPages) return ""
        cache.get(page)?.let { return it }
        val stripper = PDFTextStripper().apply {
            startPage = page + 1
            endPage = page + 1
            sortByPosition = true
        }
        val raw = runCatching { stripper.getText(document) }.getOrDefault("")
        val text = MyanmarText.normalize(raw).trim()
        cache.put(page, text)
        return text
    }

    /**
     * Word boxes for a page, in reading order, with coordinates normalised to
     * 0..1 of the crop box.
     *
     * This is what makes real text selection possible below API 35: the
     * platform renderer only exposes positioned text from API 35, but PDFBox
     * reports a [TextPosition] per glyph on every version, so words can be
     * rebuilt and mapped onto the rendered bitmap.
     */
    fun pageRuns(page: Int): List<PdfTextRun> {
        if (closed || page < 0 || page >= document.numberOfPages) return emptyList()
        runCache.get(page)?.let { return it }

        val cropBox = document.getPage(page).cropBox
        val originX = cropBox.lowerLeftX
        val originY = cropBox.lowerLeftY
        val width = cropBox.width.takeIf { it > 0f } ?: 1f
        val height = cropBox.height.takeIf { it > 0f } ?: 1f

        val collected = ArrayList<PdfTextRun>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                var builder = StringBuilder()
                var left = Float.MAX_VALUE
                var top = Float.MAX_VALUE
                var right = -Float.MAX_VALUE
                var bottom = -Float.MAX_VALUE

                fun flush() {
                    val word = builder.toString()
                    if (word.isNotBlank() && right > left && bottom > top) {
                        collected.add(
                            PdfTextRun(
                                page = page,
                                text = MyanmarText.normalize(word),
                                left = ((left - originX) / width).coerceIn(0f, 1f),
                                top = ((top - originY) / height).coerceIn(0f, 1f),
                                right = ((right - originX) / width).coerceIn(0f, 1f),
                                bottom = ((bottom - originY) / height).coerceIn(0f, 1f),
                            )
                        )
                    }
                    builder = StringBuilder()
                    left = Float.MAX_VALUE
                    top = Float.MAX_VALUE
                    right = -Float.MAX_VALUE
                    bottom = -Float.MAX_VALUE
                }

                textPositions.forEach { position ->
                    val glyph = position.unicode
                    if (glyph.isNullOrEmpty() || glyph.isBlank()) {
                        flush()
                        return@forEach
                    }
                    builder.append(glyph)
                    val x = position.xDirAdj
                    val y = position.yDirAdj - position.heightDir
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x + position.widthDirAdj)
                    bottom = maxOf(bottom, position.yDirAdj)
                }
                flush()
            }
        }.apply {
            startPage = page + 1
            endPage = page + 1
            sortByPosition = true
        }

        runCatching { stripper.getText(document) }
        val runs: List<PdfTextRun> = collected
        runCache.put(page, runs)
        return runs
    }

    override fun close() {
        if (closed) return
        closed = true
        cache.evictAll()
        runCache.evictAll()
        runCatching { document.close() }
        runCatching { scratch?.delete() }
    }

    companion object {
        private const val CACHE_PAGES = 48
        private const val CACHE_RUN_PAGES = 12
        private const val MAX_MAIN_MEMORY_BYTES = 8L * 1024L * 1024L

        fun open(context: Context, uri: Uri, password: String? = null): PdfTextIndex {
            PDFBoxResourceLoader.init(context)
            val scratch = File(context.cacheDir, "textindex-" + System.nanoTime() + ".pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                scratch.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to open this PDF for text extraction")
            val document = try {
                PDDocument.load(
                    scratch,
                    password.orEmpty(),
                    MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES),
                )
            } catch (error: Throwable) {
                scratch.delete()
                throw error
            }
            return PdfTextIndex(document, scratch)
        }
    }
}

/**
 * Search helper.
 *
 * The old implementation called `indexOf` once per page, so a page containing
 * a word ten times reported a single hit, and Zawgyi documents matched nothing
 * at all because the query was Unicode.
 */
object PdfTextSearch {

    private const val MAX_MATCHES_PER_PAGE = 25
    private const val EXCERPT_BEFORE = 40
    private const val EXCERPT_AFTER = 60

    fun matches(page: Int, pageText: String, query: String): List<PdfSearchResult> {
        val needle = MyanmarText.searchKey(query.trim())
        if (needle.isEmpty()) return emptyList()
        val haystack = MyanmarText.searchKey(pageText)
        if (haystack.isEmpty()) return emptyList()

        val results = ArrayList<PdfSearchResult>()
        var cursor = haystack.indexOf(needle, 0, ignoreCase = true)
        while (cursor >= 0 && results.size < MAX_MATCHES_PER_PAGE) {
            val from = (cursor - EXCERPT_BEFORE).coerceAtLeast(0)
            val to = (cursor + needle.length + EXCERPT_AFTER).coerceAtMost(haystack.length)
            results.add(
                PdfSearchResult(
                    page = page,
                    excerpt = haystack.substring(from, to).replace('\n', ' ').trim(),
                    matchIndex = results.size,
                )
            )
            cursor = haystack.indexOf(needle, cursor + needle.length, ignoreCase = true)
        }
        return results
    }
}
