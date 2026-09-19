package com.alalkipgen.alalpdf.create

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.URLSpan
import androidx.core.text.HtmlCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

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
        val target = File(cacheDir, "alal-preview-${System.currentTimeMillis()}.pdf")
        val document = PdfDocument()
        val links: List<PdfLink>
        try { links = build(document, spec); FileOutputStream(target).use(document::writeTo) }
        finally { document.close() }
        if (links.isNotEmpty()) addAnnotations(target, links)
        target
    }
    suspend fun save(source: File, output: Uri) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(output, "w")?.use { out -> FileInputStream(source).use { it.copyTo(out) } }
            ?: error("Unable to create output file")
    }
    private fun plain(html: String) = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    private fun validate(s: PdfSpec) { when (s.mode) {
        CreatePdfMode.TEXT -> require(s.title.isNotBlank() || plain(s.bodyHtml).isNotBlank()) { "Write something first" }
        CreatePdfMode.IMAGES -> require(s.images.isNotEmpty()) { "Choose at least one image" }
        CreatePdfMode.IMAGE_TEXT -> require(s.title.isNotBlank() || plain(s.bodyHtml).isNotBlank() || s.images.isNotEmpty()) { "Write something or add an image" }
        CreatePdfMode.SCAN -> require(s.scans.isNotEmpty()) { "Capture a page first" }
    } }
    private fun build(doc: PdfDocument, s: PdfSpec): List<PdfLink> {
        val links = mutableListOf<PdfLink>(); var page = 1
        if (s.mode == CreatePdfMode.TEXT || s.mode == CreatePdfMode.IMAGE_TEXT) page = addText(doc, s.title, s.bodyHtml, page, links)
        if (s.mode == CreatePdfMode.IMAGES || s.mode == CreatePdfMode.IMAGE_TEXT) s.images.forEach { decode(it)?.let { b -> addImage(doc, b, page++); b.recycle() } }
        if (s.mode == CreatePdfMode.SCAN) s.scans.forEach { BitmapFactory.decodeFile(it.path)?.let { b -> addImage(doc, b, page++); b.recycle() } }
        return links
    }
    private fun addText(doc: PdfDocument, title: String, html: String, first: Int, links: MutableList<PdfLink>): Int {
        val styled = HtmlCompat.fromHtml(html.ifBlank { " " }, HtmlCompat.FROM_HTML_MODE_LEGACY)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 15f; typeface = Typeface.DEFAULT }
        val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, (pageWidth - margin * 2).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(3f, 1f).build()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 24f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        var line = 0; var number = first
        do {
            val hasTitle = number == first && title.isNotBlank(); val top = margin + if (hasTitle) 52f else 0f
            val start = line; val capacity = pageHeight - margin - top
            while (line < layout.lineCount && layout.getLineBottom(line) - layout.getLineTop(start) <= capacity) line++
            if (line == start && line < layout.lineCount) line++
            val pageNumber = number++; val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            page.canvas.drawColor(Color.WHITE); if (hasTitle) page.canvas.drawText(title.take(120), margin, margin + 24f, titlePaint)
            page.canvas.save(); page.canvas.clipRect(margin, top, pageWidth - margin, pageHeight - margin)
            page.canvas.translate(margin, top - layout.getLineTop(start)); layout.draw(page.canvas); page.canvas.restore()
            collectLinks(styled, layout, start, line, top, pageNumber - 1, links); doc.finishPage(page)
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
    private fun addAnnotations(file: File, links: List<PdfLink>) {
        PDFBoxResourceLoader.init(context); val temp = File(file.parentFile, file.nameWithoutExtension + "-links.pdf")
        PDDocument.load(file).use { doc ->
            links.forEach { item ->
                val link = PDAnnotationLink(); link.action = PDActionURI().apply { uri = item.url }
                link.rectangle = PDRectangle(item.left, pageHeight - item.bottom, item.right - item.left, item.bottom - item.top)
                doc.getPage(item.page).annotations.add(link)
            }; doc.save(temp)
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
}
