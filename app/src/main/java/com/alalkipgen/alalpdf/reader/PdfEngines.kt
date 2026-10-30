package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SizeF
import androidx.annotation.VisibleForTesting
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.Closeable
import java.io.File

/**
 * Strict engine boundaries.
 *
 * Rendering, text geometry and mutation deliberately use different engines,
 * but feature code must never own those engines directly. A reader owns one
 * [PdfDocumentSession], which serializes each engine and closes every native
 * document/file descriptor together.
 */
internal interface PdfRenderEngine : Closeable {
    val pageCount: Int
    fun renderPage(
        pageIndex: Int,
        width: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap
    fun pageAspectRatio(pageIndex: Int): Float
    fun textRuns(pageIndex: Int): List<PdfTextRun>
}

internal interface PdfTextEngine : Closeable {
    val pageCount: Int
    fun charRuns(page: Int): List<PdfTextRun>
    fun pageText(page: Int): String
    fun pageSizePoints(page: Int): SizeF?
    fun pageLinks(page: Int): List<PdfPageLink>
}

/**
 * The only long-lived owner of read engines for one URI.
 *
 * PDFBox unlocks an encrypted document into a temporary file before either
 * read engine opens that file. Links, stored metadata and the text of
 * Myanmar pages are read through [pdfBox], the one PDFBox document of this
 * session, which closes with it.
 */
internal class PdfDocumentSession @VisibleForTesting internal constructor(
    private val context: Context,
    val uri: Uri,
    val password: String?,
    private val renderEngine: PdfRenderEngine,
    private val unlockedFile: File?,
) : Closeable {
    private val renderLock = Any()
    private val textLock = Any()
    private var textEngine: PdfTextEngine? = null
    @Volatile private var closed = false

    /** Shared PDFBox document for links, stored metadata and Myanmar text; loaded on first use. */
    val pdfBox: PdfBoxDocument = PdfBoxDocument.forSession(context, uri, password, unlockedFile)

    /**
     * Glyph text for the Myanmar text of [pdfBox]: fonts with a broken
     * ToUnicode (Word) are read by their glyphs. Debug builds log what it finds.
     */
    private val glyphText = MyanmarGlyphText(
        table = { MyanmarGlyphMap.bundled(context.assets) },
        log = if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            { message -> Log.d(LOG_TAG, message) }
        } else {
            null
        },
    )

    /** Recent Myanmar text layers by page, including pages that have none. Guarded by itself. */
    private val textLayers = object : LinkedHashMap<Int, TextLayer>(TEXT_LAYER_PAGES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, TextLayer>): Boolean =
            size > TEXT_LAYER_PAGES
    }

    val pageCount: Int
        get() = synchronized(renderLock) {
            check(!closed) { "PDF session is closed" }
            renderEngine.pageCount
        }

    fun render(page: Int, width: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap =
        synchronized(renderLock) {
            check(!closed) { "PDF session is closed" }
            renderEngine.renderPage(page, width, config)
        }

    fun aspectRatio(page: Int): Float = synchronized(renderLock) {
        check(!closed) { "PDF session is closed" }
        renderEngine.pageAspectRatio(page)
    }

    fun platformTextRuns(page: Int): List<PdfTextRun> = synchronized(renderLock) {
        if (closed) emptyList() else renderEngine.textRuns(page)
    }

    fun characterRuns(page: Int): List<PdfTextRun> = synchronized(textLock) {
        if (closed) emptyList() else openTextEngine()?.charRuns(page).orEmpty()
    }

    fun pageText(page: Int): String = synchronized(textLock) {
        if (closed) "" else openTextEngine()?.pageText(page).orEmpty()
    }

    fun pageSizePoints(page: Int): SizeF? = synchronized(textLock) {
        if (closed) null else openTextEngine()?.pageSizePoints(page)
    }

    fun pageLinks(page: Int): List<PdfPageLink> = synchronized(textLock) {
        if (closed) emptyList() else openTextEngine()?.pageLinks(page).orEmpty()
    }

    /** Release PDFium under memory pressure without disturbing the renderer. */
    fun trimTextEngine() = synchronized(textLock) {
        runCatching { textEngine?.close() }
        textEngine = null
    }

    /**
     * Text and selection runs from PDFBox for a page whose PDFium text has
     * Myanmar, or null when PDFium's text stays in use: no Myanmar on the
     * page, a PDFBox failure or timeout, blank PDFBox text, or a closed
     * session. See [PdfBoxTextSource] for why PDFium's Myanmar text is wrong.
     */
    suspend fun myanmarTextLayer(page: Int, pdfiumText: String): AssembledPageText? {
        if (closed || !MyanmarText.hasMyanmar(pdfiumText)) return null
        cachedTextLayer(page)?.let { return it.text }
        val coroutine = currentCoroutineContext()
        val layer = try {
            pdfBox.read { document ->
                // Another caller may have read this page while this one waited for the lock.
                cachedTextLayer(page) ?: TextLayer(
                    PdfBoxTextSource.extract(document, page, glyphText, isActive = { coroutine.isActive })
                        .takeIf { it.text.isNotBlank() },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            TextLayer(null)
        }
        // A page read while the session was closing is not cached.
        if (!closed) synchronized(textLayers) { textLayers[page] = layer }
        return layer.text
    }

    private fun cachedTextLayer(page: Int): TextLayer? = synchronized(textLayers) { textLayers[page] }

    /**
     * Release the parsed PDFBox document, its cached fonts and the cached
     * text layers under memory pressure; all are read again on next use.
     */
    fun trimPdfBox() {
        synchronized(textLayers) { textLayers.clear() }
        glyphText.clear()
        pdfBox.trim()
    }

    private fun openTextEngine(): PdfTextEngine? {
        textEngine?.let { return it }
        val opened = if (unlockedFile != null) {
            PdfiumTextSource.open(
                context,
                ParcelFileDescriptor.open(unlockedFile, ParcelFileDescriptor.MODE_READ_ONLY),
                null,
            )
        } else {
            PdfiumTextSource.open(context, uri, password)
        }
        textEngine = opened
        return opened
    }

    override fun close() {
        if (closed) return
        closed = true
        pdfBox.close()
        glyphText.clear()
        synchronized(textLayers) { textLayers.clear() }
        synchronized(textLock) {
            runCatching { textEngine?.close() }
            textEngine = null
        }
        synchronized(renderLock) { runCatching { renderEngine.close() } }
        runCatching { unlockedFile?.delete() }
    }

    /** A cached result for one page; [text] is null when the page keeps PDFium's text. */
    private class TextLayer(val text: AssembledPageText?)

    companion object {
        private const val TEXT_LAYER_PAGES = 16
        private const val LOG_TAG = "MyanmarGlyphText"

        fun open(context: Context, uri: Uri, password: String?): PdfDocumentSession {
            runCatching { PdfBoxDocument.deleteStaleFiles(context.cacheDir) }
            val resolver = context.contentResolver
            if (password.isNullOrEmpty()) {
                val direct = runCatching {
                    val descriptor = resolver.openFileDescriptor(uri, "r")
                        ?: error("Unable to open PDF")
                    PdfRendererSource.open(descriptor)
                }.getOrNull()
                if (direct != null) {
                    return PdfDocumentSession(context, uri, null, direct, null)
                }
            }

            val unlocked = File(context.cacheDir, "unlock-${System.nanoTime()}.pdf")
            try {
                PdfBoxDocument.writeUnlockedCopy(context, uri, password, unlocked)
                val descriptor = ParcelFileDescriptor.open(
                    unlocked,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                )
                val renderer = PdfRendererSource.open(descriptor)
                return PdfDocumentSession(context, uri, password, renderer, unlocked)
            } catch (error: InvalidPasswordException) {
                unlocked.delete()
                throw PdfPasswordRequiredException(error)
            } catch (error: Throwable) {
                unlocked.delete()
                throw error
            }
        }
    }
}