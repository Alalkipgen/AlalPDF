package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfReaderRepository(private val context: Context) {
    private val resolver = context.contentResolver

    private val sessionLock = Any()
    private var session: PdfDocumentSession? = null

    private var thumbnails: ThumbnailCache? = null
    private var thumbnailsUri: Uri? = null

    suspend fun pageCount(uri: Uri, password: String? = null): Int = withContext(Dispatchers.IO) {
        readerSession(uri, password).pageCount
    }

    suspend fun render(uri: Uri, page: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        readerSession(uri).render(page, width)
    }

    suspend fun renderPreview(uri: Uri, page: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        readerSession(uri).render(page, width, Bitmap.Config.RGB_565)
    }

    /** Cheap RGB_565 thumbnail, memoized in memory and on disk. */
    suspend fun thumbnail(uri: Uri, page: Int, width: Int = THUMBNAIL_WIDTH_PX): Bitmap =
        withContext(Dispatchers.IO) {
            val cache = thumbnailCache(uri)
            cache.get(page) ?: readerSession(uri)
                .render(page, width, Bitmap.Config.RGB_565)
                .also { cache.put(page, it) }
        }

    suspend fun pageLinks(uri: Uri, page: Int): List<PdfPageLink> = withContext(Dispatchers.IO) {
        runCatching {
            PdfLinkExtractor.extractPage(resolver, uri, page, readerSession(uri).password)
        }.getOrDefault(emptyList())
    }

    /**
     * Positioned word boxes for one page, used to draw and hit-test the
     * selection overlay.
     *
     * The platform renderer only exposes positioned text from API 35, which is
     * why selection never worked on normal devices. PDFBox reports a position
     * per glyph. PDFium is the normal reader text engine; PDFBox is not kept
     * open beside the renderer.
     */
    suspend fun pageTextRuns(uri: Uri, page: Int): List<PdfTextRun> = withContext(Dispatchers.IO) {
        // PDFium first: it is the only source that gives a box per character,
        // which is what character level selection needs.
        val charRuns = runCatching {
            readerSession(uri).characterRuns(page)
        }.getOrDefault(emptyList())
        if (charRuns.isNotEmpty()) return@withContext charRuns
        runsOrEmpty(uri, page)
    }

    /**
     * Text for a single page. Prefers the platform renderer when it can supply
     * positioned runs, otherwise falls back to the lazy PDFBox index.
     */
    /** Plain text stored by Alal PDF itself, which is always exact. */
    private var storedTextUri: Uri? = null
    private var storedText: Map<Int, String> = emptyMap()

    private fun storedPageText(uri: Uri, page: Int): String? {
        if (storedTextUri != uri) {
            storedText = runCatching { AlalPdfText.read(context, uri) }.getOrDefault(emptyMap())
            storedTextUri = uri
        }
        return storedText[page]
    }

    suspend fun pageText(uri: Uri, page: Int): String = withContext(Dispatchers.IO) {
        storedPageText(uri, page)?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val pdfiumText = runCatching {
            readerSession(uri).pageText(page)
        }.getOrDefault("")
        if (pdfiumText.isNotBlank()) return@withContext MyanmarText.normalize(pdfiumText).trim()
        val runs = runsOrEmpty(uri, page)
        if (runs.isNotEmpty()) {
            MyanmarText.normalize(runs.joinToString("\n") { it.text }).trim()
        } else {
            ""
        }
    }

    private fun runsOrEmpty(uri: Uri, page: Int): List<PdfTextRun> =
        runCatching { readerSession(uri).platformTextRuns(page) }.getOrDefault(emptyList())

    private fun thumbnailCache(uri: Uri): ThumbnailCache {
        thumbnails?.takeIf { thumbnailsUri == uri }?.let { return it }
        thumbnails?.clearMemory()
        return ThumbnailCache(context, uri.toString()).also {
            thumbnails = it
            thumbnailsUri = uri
        }
    }

    fun close() {
        synchronized(sessionLock) {
            runCatching { session?.close() }
            session = null
        }
        storedText = emptyMap()
        storedTextUri = null
        thumbnails?.clearMemory()
        thumbnails = null
        thumbnailsUri = null
    }

    fun trimMemory() {
        synchronized(sessionLock) { session?.trimTextEngine() }
        thumbnails?.clearMemory()
    }

    private fun readerSession(uri: Uri, password: String? = session?.password): PdfDocumentSession {
        synchronized(sessionLock) {
            session?.takeIf { it.uri == uri && it.password == password }?.let { return it }
            runCatching { session?.close() }
            return PdfDocumentSession.open(context, uri, password).also { session = it }
        }
    }

    private companion object {
        const val THUMBNAIL_WIDTH_PX = 160
    }
}
