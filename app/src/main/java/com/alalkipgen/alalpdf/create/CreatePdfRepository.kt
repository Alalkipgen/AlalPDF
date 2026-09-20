package com.alalkipgen.alalpdf.create

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.URLSpan
import android.util.Base64
import androidx.core.text.HtmlCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

data class PdfDraft(val mode: CreatePdfMode,val title:String,val bodyHtml:String,val images:List<String> = emptyList())

data class PdfSpec(
    val mode: CreatePdfMode,
    val title: String = "",
    val bodyHtml: String = "",
    val images: List<Uri> = emptyList(),
    val scans: List<File> = emptyList(),
)
private data class PdfLink(val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float, val url: String)

class CreatePdfRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 48f

    suspend fun buildPreview(cacheDir: File, spec: PdfSpec): File = withContext(Dispatchers.IO) {
        validate(spec)
        cacheDir.listFiles { file -> file.name.startsWith("alal-preview-") && file.extension == "pdf" }
            ?.filter { System.currentTimeMillis() - it.lastModified() > 60_000L }
            ?.forEach(File::delete)
        val target = File(cacheDir, "alal-preview-${System.currentTimeMillis()}.pdf")
        val document = PdfDocument()
        val links: List<PdfLink>
        try { links = build(document, spec); FileOutputStream(target).use(document::writeTo) }
        finally { document.close() }
        finalizePdf(target, links, spec)
        validatePdf(target)
        target
    }
    suspend fun save(source: File, output: Uri) = withContext(Dispatchers.IO) {
        validatePdf(source)
        try {
            val descriptor = resolver.openFileDescriptor(output, "rwt")
                ?: resolver.openFileDescriptor(output, "w")
                ?: error("Unable to create output file")
            descriptor.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    FileInputStream(source).use { it.copyTo(out) }
                    out.flush()
                    runCatching { out.fd.sync() }
                }
            }
            resolver.openInputStream(output)?.use { input ->
                val header = ByteArray(5)
                check(input.read(header) == 5 && String(header, Charsets.US_ASCII) == "%PDF-") {
                    "The saved file is incomplete"
                }
            } ?: error("Unable to verify saved PDF")
            resolver.openInputStream(output)?.use { input ->
                PDDocument.load(input).use { check(it.numberOfPages > 0) }
            } ?: error("Unable to verify saved PDF")
            runCatching {
                resolver.takePersistableUriPermission(output, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } catch (error: Throwable) {
            runCatching { android.provider.DocumentsContract.deleteDocument(resolver, output) }
            runCatching { resolver.delete(output, null, null) }
            throw error
        }
    }
    private fun validatePdf(file: File) {
        check(file.isFile && file.length() > 5L) { "PDF creation produced an empty file" }
        FileInputStream(file).use { input ->
            val header = ByteArray(5)
            check(input.read(header) == 5 && String(header, Charsets.US_ASCII) == "%PDF-") { "Invalid PDF output" }
        }
        PDDocument.load(file).use { check(it.numberOfPages > 0) { "PDF has no pages" } }
    }
    private fun plain(html: String) = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    private fun validate(s: PdfSpec) { when (s.mode) {
        CreatePdfMode.TEXT -> require(s.title.isNotBlank() || plain(s.bodyHtml).isNotBlank()) { "Write something first" }
        CreatePdfMode.IMAGES -> require(s.images.isNotEmpty()) { "Choose at least one image" }
        CreatePdfMode.IMAGE_TEXT -> require(s.title.isNotBlank() || plain(s.bodyHtml).isNotBlank() || s.images.isNotEmpty()) { "Write something or add an image" }
        CreatePdfMode.SCAN -> require(s.scans.isNotEmpty()) { "Capture a page first" }
    } }
    /**
     * Plain text of every page we write, keyed by page index.
     *
     * Android shapes Myanmar correctly when it draws the page, but it can only
     * describe each shaped glyph with a single code point in the PDF's
     * ToUnicode table. Reading that back turns a Burmese syllable into loose,
     * reordered pieces. Keeping the real text next to the document is exact
     * and costs nothing, so the reader uses it instead of extraction.
     */
    private val pageTexts = mutableMapOf<Int, String>()

    private fun build(doc: PdfDocument, s: PdfSpec): List<PdfLink> {
        val links = mutableListOf<PdfLink>(); var page = 1
        pageTexts.clear()
        if (s.mode == CreatePdfMode.TEXT || s.mode == CreatePdfMode.IMAGE_TEXT) page = addText(doc, s.title, s.bodyHtml, page, links)
        if (s.mode == CreatePdfMode.IMAGES || s.mode == CreatePdfMode.IMAGE_TEXT) s.images.forEach { decode(it)?.let { b -> addImage(doc, b, page++); b.recycle() } }
        if (s.mode == CreatePdfMode.SCAN) s.scans.forEach { BitmapFactory.decodeFile(it.path)?.let { b -> addImage(doc, b, page++); b.recycle() } }
        return links
    }

    /**
     * Lays the title out over as many lines as it needs.
     *
     * A long title used to be drawn with a single `drawText(title.take(120))`
     * call, so it ran straight off the right edge of the page and lost
     * everything past 120 characters. Titles are now wrapped to the text
     * column and shrunk a step at a time when they grow past three lines, so
     * the whole title is always visible.
     */
    private fun titleLayout(title: String): StaticLayout {
        val available = (pageWidth - margin * 2).toInt()
        var layout: StaticLayout? = null
        for (size in TITLE_SIZES) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = size
                typeface = PyidaungsuFonts.bold(context)
            }
            layout = StaticLayout.Builder.obtain(title, 0, title.length, paint, available)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(2f, 1f)
                .build()
            if (layout.lineCount <= TITLE_MAX_LINES) break
        }
        return layout!!
    }

    private fun addText(doc: PdfDocument, title: String, html: String, first: Int, links: MutableList<PdfLink>): Int {
        val styled = PyidaungsuFonts.styled(context,HtmlCompat.fromHtml(html.ifBlank { " " },HtmlCompat.FROM_HTML_MODE_LEGACY))
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; linkColor = Color.rgb(0, 102, 204); textSize = 15f; typeface = PyidaungsuFonts.regular(context) }
        val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, (pageWidth - margin * 2).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(3f, 1f).build()
        val heading = if (title.isNotBlank()) titleLayout(title) else null
        // The body starts below the measured title block instead of a fixed 52f.
        val headingHeight = heading?.let { it.height + TITLE_GAP } ?: 0f
        var line = 0; var number = first
        do {
            val hasTitle = number == first && heading != null; val top = margin + if (hasTitle) headingHeight else 0f
            val start = line; val capacity = pageHeight - margin - top
            while (line < layout.lineCount && layout.getLineBottom(line) - layout.getLineTop(start) <= capacity) line++
            if (line == start && line < layout.lineCount) line++
            val pageNumber = number++; val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            page.canvas.drawColor(Color.WHITE)
            if (hasTitle && heading != null) {
                page.canvas.save(); page.canvas.translate(margin, margin); heading.draw(page.canvas); page.canvas.restore()
            }
            page.canvas.save(); page.canvas.clipRect(margin, top, pageWidth - margin, pageHeight - margin)
            page.canvas.translate(margin, top - layout.getLineTop(start)); layout.draw(page.canvas); page.canvas.restore()
            collectLinks(styled, layout, start, line, top, pageNumber - 1, links)
            val body = styled.subSequence(layout.getLineStart(start), layout.getLineEnd(line - 1)).toString()
            pageTexts[pageNumber - 1] = if (hasTitle) (title + "\n\n" + body).trim() else body.trim()
            doc.finishPage(page)
        } while (line < layout.lineCount)
        return number
    }
    private fun collectLinks(text: android.text.Spanned, layout: StaticLayout, firstLine: Int, endLine: Int, top: Float, page: Int, out: MutableList<PdfLink>) {
        text.getSpans(0, text.length, URLSpan::class.java).forEach { span ->
            val start = text.getSpanStart(span); val end = text.getSpanEnd(span)
            val from = maxOf(firstLine, layout.getLineForOffset(start)); val to = minOf(endLine - 1, layout.getLineForOffset((end - 1).coerceAtLeast(start)))
            if (from <= to) for (line in from..to) {
                val a = maxOf(start, layout.getLineStart(line)); val b = minOf(end, layout.getLineEnd(line))
                val x1 = margin + layout.getPrimaryHorizontal(a); val x2 = margin + layout.getPrimaryHorizontal(b)
                val y1 = top + layout.getLineTop(line) - layout.getLineTop(firstLine); val y2 = top + layout.getLineBottom(line) - layout.getLineTop(firstLine)
                out += PdfLink(page, minOf(x1, x2), y1, maxOf(x1, x2), y2, normalizeHttpUrl(span.url))
            }
        }
    }
    suspend fun readDraft(uri: Uri): PdfDraft? = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        resolver.openInputStream(uri)?.use { input -> PDDocument.load(input).use { doc ->
            val info=doc.documentInformation
            if(info.getCustomMetadataValue("AlalPDF-Version")==null) return@withContext null
            fun decode(k:String)=info.getCustomMetadataValue(k)?.let{String(Base64.decode(it,Base64.NO_WRAP),Charsets.UTF_8)}.orEmpty()
            PdfDraft(runCatching{CreatePdfMode.valueOf(info.getCustomMetadataValue("AlalPDF-Mode")?:"TEXT")}.getOrDefault(CreatePdfMode.TEXT),decode("AlalPDF-Title"),decode("AlalPDF-Body"),decode("AlalPDF-Images").lines().filter(String::isNotBlank))
        }}
    }
    private fun finalizePdf(file: File, links: List<PdfLink>, spec: PdfSpec) {
        PDFBoxResourceLoader.init(context); val temp = File(file.parentFile, file.nameWithoutExtension + "-links.pdf")
        PDDocument.load(file).use { doc ->
            links.forEach { item ->
                val link = PDAnnotationLink(); link.action = PDActionURI().apply { uri = item.url }
                link.rectangle = PDRectangle(item.left, pageHeight - item.bottom, item.right - item.left, item.bottom - item.top)
                doc.getPage(item.page).annotations.add(link)
            }
            fun encode(v:String)=Base64.encodeToString(v.toByteArray(Charsets.UTF_8),Base64.NO_WRAP)
            doc.documentInformation.apply { setCustomMetadataValue("AlalPDF-Version","1");setCustomMetadataValue("AlalPDF-Mode",spec.mode.name);setCustomMetadataValue("AlalPDF-Title",encode(spec.title));setCustomMetadataValue("AlalPDF-Body",encode(spec.bodyHtml));setCustomMetadataValue("AlalPDF-Images",encode(spec.images.joinToString("\n")))
                setCustomMetadataValue("AlalPDF-PageCount", pageTexts.size.toString())
                pageTexts.forEach { (index, text) -> setCustomMetadataValue("AlalPDF-Page-" + index, encode(text)) } }
            doc.save(temp)
        }
        check(file.delete() && temp.renameTo(file)) { "Unable to finalize PDF links" }
    }
    private fun addImage(doc: PdfDocument, bitmap: Bitmap, number: Int) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, number).create()); page.canvas.drawColor(Color.WHITE)
        val scale = minOf((pageWidth - margin * 2) / bitmap.width, (pageHeight - margin * 2) / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale; val l = (pageWidth - w) / 2; val t = (pageHeight - h) / 2
        page.canvas.drawBitmap(bitmap, null, RectF(l, t, l + w, t + h), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)); doc.finishPage(page)
    }
    private fun decode(uri: Uri) = resolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)

    private companion object {
        val TITLE_SIZES = floatArrayOf(24f, 20f, 18f)
        const val TITLE_MAX_LINES = 3
        const val TITLE_GAP = 16f
    }
}
