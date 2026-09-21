package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.SizeF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
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
}

/**
 * The only long-lived owner of read engines for one URI.
 *
 * PDFBox is used here only to unlock an encrypted document into a temporary
 * file. It is closed before either read engine opens that file.
 */
internal class PdfDocumentSession private constructor(
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
        synchronized(textLock) {
            runCatching { textEngine?.close() }
            textEngine = null
        }
        synchronized(renderLock) { runCatching { renderEngine.close() } }
        runCatching { unlockedFile?.delete() }
    }

    companion object {
        fun open(context: Context, uri: Uri, password: String?): PdfDocumentSession {
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

            PDFBoxResourceLoader.init(context)
            val unlocked = File(context.cacheDir, "unlock-${System.nanoTime()}.pdf")
            try {
                resolver.openInputStream(uri)?.use { input ->
                    PDDocument.load(input, password.orEmpty()).use { document ->
                        document.isAllSecurityToBeRemoved = true
                        document.save(unlocked)
                    }
                } ?: error("Unable to read PDF")
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