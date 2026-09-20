package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PdfReaderRepository(private val context: Context) {
    private val resolver = context.contentResolver

    /** Guards the platform renderer, which is not thread safe. */
    private val lock = Any()

    /**
     * Guards the PDFBox text index. This is deliberately a *separate* lock:
     * text extraction used to share the render lock, so reading text blocked
     * every page render and scrolling froze.
     */
    private val textLock = Any()

    private var currentUri: Uri? = null
    private var currentPassword: String? = null
    private var source: PdfRendererSource? = null
    private var unlockedTemp: File? = null

    private var textIndex: PdfTextIndex? = null
    private var textIndexUri: Uri? = null

    suspend fun pageCount(uri: Uri, password: String? = null): Int = withContext(Dispatchers.IO) {
        synchronized(lock) { open(uri, password).pageCount }
    }
    suspend fun render(uri: Uri, page: Int, width: Int, nightMode: Boolean = false): Bitmap = withContext(Dispatchers.IO) {
        synchronized(lock) { open(uri, currentPassword).renderPage(page, width, nightMode) }
    }
    suspend fun links(uri: Uri): Map<Int, List<PdfPageLink>> = withContext(Dispatchers.IO) {
        runCatching { PdfLinkExtractor.extract(resolver, uri) }.getOrDefault(emptyMap())
    }

    /**
     * Positioned text runs for one page. Only the platform renderer can supply
     * these, and only from API 35, so this returns an empty list elsewhere.
     */
    suspend fun pageTextRuns(uri: Uri, page: Int): List<PdfTextRun> = withContext(Dispatchers.IO) {
        runsOrEmpty(uri, page)
    }

    /**
     * Text for a single page. Prefers the platform renderer when it can supply
     * positioned runs, otherwise falls back to the lazy PDFBox index.
     */
    suspend fun pageText(uri: Uri, page: Int): String = withContext(Dispatchers.IO) {
        val runs = runsOrEmpty(uri, page)
        if (runs.isNotEmpty()) {
            MyanmarText.normalize(runs.joinToString("\n") { it.text }).trim()
        } else {
            synchronized(textLock) { textIndex(uri).pageText(page) }
        }
    }

    private fun runsOrEmpty(uri: Uri, page: Int): List<PdfTextRun> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching {
                synchronized(lock) { open(uri, currentPassword).textRuns(page) }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

    private fun textIndex(uri: Uri): PdfTextIndex {
        textIndex?.takeIf { textIndexUri == uri }?.let { return it }
        runCatching { textIndex?.close() }
        textIndex = null
        textIndexUri = null
        return PdfTextIndex.open(context, uri, currentPassword).also {
            textIndex = it
            textIndexUri = uri
        }
    }

    fun close() {
        synchronized(lock) { clear() }
        synchronized(textLock) {
            runCatching { textIndex?.close() }
            textIndex = null
            textIndexUri = null
        }
    }

    private fun clear() {
        runCatching { source?.close() }; source = null; currentUri = null; currentPassword = null
        unlockedTemp?.delete(); unlockedTemp = null
    }
    private fun open(uri: Uri, password: String?): PdfRendererSource {
        source?.takeIf { currentUri == uri && currentPassword == password }?.let { return it }
        clear()
        if (password == null) runCatching { PdfRendererSource.open(resolver.openFileDescriptor(uri, "r")!!) }.getOrNull()?.let {
            source = it; currentUri = uri; return it
        }
        PDFBoxResourceLoader.init(context)
        val temp = File(context.cacheDir, "unlock-${System.nanoTime()}.pdf")
        try {
            resolver.openInputStream(uri)!!.use { input -> PDDocument.load(input, password.orEmpty()).use { document ->
                document.isAllSecurityToBeRemoved = true; document.save(temp)
            } }
        } catch (error: InvalidPasswordException) { throw PdfPasswordRequiredException(error) }
        return PdfRendererSource.open(ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)).also {
            source = it; currentUri = uri; currentPassword = password; unlockedTemp = temp
        }
    }
}
