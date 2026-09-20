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

    /** Guards the PDFium text source used for character level selection. */
    private val pdfiumLock = Any()
    private var pdfium: PdfiumTextSource? = null
    private var pdfiumUri: Uri? = null

    private var thumbnails: ThumbnailCache? = null
    private var thumbnailsUri: Uri? = null

    suspend fun pageCount(uri: Uri, password: String? = null): Int = withContext(Dispatchers.IO) {
        synchronized(lock) { open(uri, password).pageCount }
    }

    suspend fun render(uri: Uri, page: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        synchronized(lock) { open(uri, currentPassword).renderPage(page, width) }
    }

    /** Cheap RGB_565 thumbnail, memoized in memory and on disk. */
    suspend fun thumbnail(uri: Uri, page: Int, width: Int = THUMBNAIL_WIDTH_PX): Bitmap =
        withContext(Dispatchers.IO) {
            val cache = thumbnailCache(uri)
            cache.get(page) ?: synchronized(lock) {
                open(uri, currentPassword).renderPage(page, width, Bitmap.Config.RGB_565)
            }.also { cache.put(page, it) }
        }

    suspend fun links(uri: Uri): Map<Int, List<PdfPageLink>> = withContext(Dispatchers.IO) {
        runCatching { PdfLinkExtractor.extract(resolver, uri) }.getOrDefault(emptyMap())
    }

    /**
     * Positioned word boxes for one page, used to draw and hit-test the
     * selection overlay.
     *
     * The platform renderer only exposes positioned text from API 35, which is
     * why selection never worked on normal devices. PDFBox reports a position
     * per glyph on every version, so it is used whenever the platform cannot
     * answer.
     */
    suspend fun pageTextRuns(uri: Uri, page: Int): List<PdfTextRun> = withContext(Dispatchers.IO) {
        // PDFium first: it is the only source that gives a box per character,
        // which is what character level selection needs.
        val charRuns = runCatching {
            synchronized(pdfiumLock) { pdfiumSource(uri)?.charRuns(page).orEmpty() }
        }.getOrDefault(emptyList())
        if (charRuns.isNotEmpty()) return@withContext charRuns
        val platformRuns = runsOrEmpty(uri, page)
        if (platformRuns.isNotEmpty()) return@withContext platformRuns
        runCatching { synchronized(textLock) { textIndex(uri).pageRuns(page) } }
            .getOrDefault(emptyList())
    }

    /**
     * Text for a single page. Prefers the platform renderer when it can supply
     * positioned runs, otherwise falls back to the lazy PDFBox index.
     */
    /** Plain text stored by Alal PDF itself, which is always exact. */
    private val storedText = mutableMapOf<Uri, Map<Int, String>>()

    private fun storedPageText(uri: Uri, page: Int): String? {
        val cached = storedText[uri]
        if (cached != null) return cached[page]
        val loaded = runCatching { AlalPdfText.read(context, uri) }.getOrDefault(emptyMap())
        storedText[uri] = loaded
        return loaded[page]
    }

    suspend fun pageText(uri: Uri, page: Int): String = withContext(Dispatchers.IO) {
        storedPageText(uri, page)?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val pdfiumText = runCatching {
            synchronized(pdfiumLock) { pdfiumSource(uri)?.pageText(page).orEmpty() }
        }.getOrDefault("")
        if (pdfiumText.isNotBlank()) return@withContext MyanmarText.normalize(pdfiumText).trim()
        val runs = runsOrEmpty(uri, page)
        if (runs.isNotEmpty()) {
            MyanmarText.normalize(runs.joinToString("\n") { it.text }).trim()
        } else {
            synchronized(textLock) { textIndex(uri).pageText(page) }
        }
    }

    private fun pdfiumSource(uri: Uri): PdfiumTextSource? {
        pdfium?.takeIf { pdfiumUri == uri }?.let { return it }
        runCatching { pdfium?.close() }
        pdfium = null
        pdfiumUri = null
        return PdfiumTextSource.open(context, uri, currentPassword)?.also {
            pdfium = it
            pdfiumUri = uri
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

    private fun thumbnailCache(uri: Uri): ThumbnailCache {
        thumbnails?.takeIf { thumbnailsUri == uri }?.let { return it }
        thumbnails?.clearMemory()
        return ThumbnailCache(context, uri.toString()).also {
            thumbnails = it
            thumbnailsUri = uri
        }
    }

    fun close() {
        synchronized(lock) { clear() }
        synchronized(textLock) {
            runCatching { textIndex?.close() }
            textIndex = null
            textIndexUri = null
        }
        synchronized(pdfiumLock) {
            runCatching { pdfium?.close() }
            pdfium = null
            pdfiumUri = null
        }
        thumbnails?.clearMemory()
        thumbnails = null
        thumbnailsUri = null
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

    private companion object {
        const val THUMBNAIL_WIDTH_PX = 160
    }
}
