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
    private val lock = Any()
    private var currentUri: Uri? = null
    private var currentPassword: String? = null
    private var source: PdfRendererSource? = null
    private var unlockedTemp: File? = null

    suspend fun pageCount(uri: Uri, password: String? = null): Int = withContext(Dispatchers.IO) {
        synchronized(lock) { open(uri, password).pageCount }
    }
    suspend fun render(uri: Uri, page: Int, width: Int, nightMode: Boolean = false): Bitmap = withContext(Dispatchers.IO) {
        synchronized(lock) { open(uri, currentPassword).renderPage(page, width, nightMode) }
    }
    suspend fun links(uri: Uri): Map<Int, List<PdfPageLink>> = withContext(Dispatchers.IO) {
        runCatching { PdfLinkExtractor.extract(resolver, uri) }.getOrDefault(emptyMap())
    }
    suspend fun text(uri: Uri): Pair<List<PdfPageText>, List<PdfTextRun>> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val runs = synchronized(lock) {
                val renderer = open(uri, currentPassword)
                (0 until renderer.pageCount).flatMap(renderer::textRuns)
            }
            val pages = runs.groupBy(PdfTextRun::page).map { (page, items) ->
                PdfPageText(page, items.joinToString("\n", transform = PdfTextRun::text))
            }.sortedBy(PdfPageText::page)
            pages to runs
        } else PdfTextExtractor.extract(context, uri, currentPassword) to emptyList()
    }
    fun close() = synchronized(lock) { clear() }
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
