package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.withContext

/**
 * Single owner of the native document session used by the reader.
 *
 * Every entry point runs on a bounded dispatcher from [PdfWorkDispatchers]
 * rather than on [kotlinx.coroutines.Dispatchers.IO]. The native engines are
 * serialized by locks in any case, and running them on the unbounded IO pool
 * meant that a fast fling blocked every IO thread on the same monitor and
 * starved the rest of the app.
 */
class PdfReaderRepository(private val context: Context) {
    private val resolver = context.contentResolver

    private val sessionLock = Any()
    private var session: PdfDocumentSession? = null

    private var thumbnails: ThumbnailCache? = null
    private var thumbnailsUri: Uri? = null
    private val metadataLock = Any()
    private var metadataUri: Uri? = null
    private var metadata = AlalPdfStoredContent()

    private val linkLock = Any()
    private var linkUri: Uri? = null
    private var linkIndex: Map<Int, List<PdfPageLink>>? = null

    suspend fun pageCount(uri: Uri, password: String? = null): Int =
        withContext(PdfWorkDispatchers.render) { openSession(uri, password).pageCount }

    suspend fun render(uri: Uri, page: Int, width: Int): Bitmap =
        withContext(PdfWorkDispatchers.render) { readerSession(uri).render(page, width) }

    suspend fun renderPreview(uri: Uri, page: Int, width: Int): Bitmap =
        withContext(PdfWorkDispatchers.render) {
            readerSession(uri).render(page, width, Bitmap.Config.RGB_565)
        }

    /** Cheap RGB_565 thumbnail, memoized in memory and on disk. */
    suspend fun thumbnail(uri: Uri, page: Int, width: Int = THUMBNAIL_WIDTH_PX): Bitmap =
        withContext(PdfWorkDispatchers.render) {
            val cache = thumbnailCache(uri)
            cache.get(page) ?: readerSession(uri)
                .render(page, width, Bitmap.Config.RGB_565)
                .also { cache.put(page, it) }
        }

    /**
     * Link boxes for one page.
     *
     * PdfiumAndroid 1.0.32 has a known getPageLinks rectangle-size bug, so
     * PDFBox stays the accurate source. It is now read once per document
     * instead of once per page: the old code ran a whole `PDDocument.load()`
     * for every page the user scrolled past, which is the single most
     * expensive thing that used to happen on the scroll path.
     */
    suspend fun pageLinks(uri: Uri, page: Int): List<PdfPageLink> =
        withContext(PdfWorkDispatchers.metadata) {
            val stored = storedContent(uri)
            stored.pageLinks[page]?.takeIf(List<PdfPageLink>::isNotEmpty)?.let {
                return@withContext it
            }
            documentLinks(uri)[page]?.takeIf(List<PdfPageLink>::isNotEmpty)?.let {
                return@withContext it
            }
            runCatching { readerSession(uri).pageLinks(page) }.getOrDefault(emptyList())
        }

    /**
     * Positioned character boxes for one page, used to draw and hit-test the
     * selection overlay. PDFium is the only source that gives a box per
     * character, which is what character level selection needs.
     */
    suspend fun pageTextRuns(uri: Uri, page: Int): List<PdfTextRun> =
        withContext(PdfWorkDispatchers.text) {
            val charRuns = runCatching { readerSession(uri).characterRuns(page) }
                .getOrDefault(emptyList())
            if (charRuns.isNotEmpty()) return@withContext charRuns
            runsOrEmpty(uri, page)
        }

    suspend fun pageText(uri: Uri, page: Int): String =
        withContext(PdfWorkDispatchers.text) {
            storedPageText(uri, page)?.takeIf { it.isNotBlank() }?.let { return@withContext it }
            val pdfiumText = runCatching { readerSession(uri).pageText(page) }.getOrDefault("")
            if (pdfiumText.isNotBlank()) return@withContext MyanmarText.normalize(pdfiumText).trim()
            val runs = runsOrEmpty(uri, page)
            if (runs.isNotEmpty()) {
                MyanmarText.normalize(runs.joinToString("\n") { it.text }).trim()
            } else {
                ""
            }
        }

    /** Plain text stored by Alal PDF itself, which is always exact. */
    private fun storedPageText(uri: Uri, page: Int): String? = storedContent(uri).pageTexts[page]

    private fun storedContent(uri: Uri): AlalPdfStoredContent = synchronized(metadataLock) {
        if (metadataUri != uri) {
            metadata = AlalPdfText.readContent(context, uri)
            metadataUri = uri
        }
        metadata
    }

    private fun documentLinks(uri: Uri): Map<Int, List<PdfPageLink>> {
        synchronized(linkLock) {
            if (linkUri == uri) return linkIndex.orEmpty()
        }
        val password = synchronized(sessionLock) {
            session?.takeIf { it.uri == uri }?.password
        }
        val extracted = runCatching { PdfLinkExtractor.extractDocument(resolver, uri, password) }
            .getOrDefault(emptyMap())
        synchronized(linkLock) {
            linkUri = uri
            linkIndex = extracted
        }
        return extracted
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
        synchronized(metadataLock) {
            metadata = AlalPdfStoredContent()
            metadataUri = null
        }
        synchronized(linkLock) {
            linkUri = null
            linkIndex = null
        }
        thumbnails?.clearMemory()
        thumbnails = null
        thumbnailsUri = null
    }

    fun trimMemory() {
        synchronized(sessionLock) { session?.trimTextEngine() }
        thumbnails?.clearMemory()
    }

    /**
     * Session for an already-open document.
     *
     * The password used to be read through a default argument
     * (`password = session?.password`), which evaluated outside [sessionLock]
     * and could observe a session that another thread was closing.
     */
    private fun readerSession(uri: Uri): PdfDocumentSession {
        synchronized(sessionLock) {
            session?.takeIf { it.uri == uri }?.let { return it }
            runCatching { session?.close() }
            session = null
            return PdfDocumentSession.open(context, uri, null).also { session = it }
        }
    }

    private fun openSession(uri: Uri, password: String?): PdfDocumentSession {
        synchronized(sessionLock) {
            session?.takeIf { it.uri == uri && it.password == password }?.let { return it }
            runCatching { session?.close() }
            session = null
            return PdfDocumentSession.open(context, uri, password).also { session = it }
        }
    }

    private companion object {
        const val THUMBNAIL_WIDTH_PX = 160
    }
}
