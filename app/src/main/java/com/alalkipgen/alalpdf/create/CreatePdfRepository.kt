package com.alalkipgen.alalpdf.create

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.SpannedString
import android.text.style.URLSpan
import android.util.Base64
import androidx.core.text.HtmlCompat
import com.alalkipgen.alalpdf.common.AlalLinkMetadata
import com.alalkipgen.alalpdf.common.AlalStoredLink
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
    suspend fun save(
        source: File,
        output: Uri,
        deleteNewDocumentOnFailure: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        validatePdf(source)
        try {
            var lastFailure: Throwable? = null
            var written = false
            for (mode in OUTPUT_MODES) {
                val attempt = runCatching {
                    val outputStream = resolver.openOutputStream(output, mode)
                        ?: error("Document provider returned no output stream")
                    val copied = outputStream.use { out ->
                        FileInputStream(source).use { input -> input.copyTo(out) }
                            .also { out.flush() }
                    }
                    check(copied == source.length()) { "The saved file is incomplete" }
                }
                if (attempt.isSuccess) {
                    written = true
                    break
                }
                lastFailure = attempt.exceptionOrNull()
            }
            if (!written) throw lastFailure ?: error("Unable to create output file")
            resolver.openInputStream(output)?.use { input ->
                val header = ByteArray(5)
                check(input.read(header) == 5 && String(header, Charsets.US_ASCII) == "%PDF-") {
                    "The saved file is incomplete"
                }
            } ?: error("Unable to verify saved PDF")
            // The cache source was already fully parsed and verified. Avoid
            // loading a second PDFBox document immediately after the picker:
            // that native-memory spike caused process death and left 0 B SAF
            // placeholders on some devices.
            runCatching {
                resolver.takePersistableUriPermission(output, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } catch (error: Throwable) {
            // ACTION_CREATE_DOCUMENT creates the destination before this method
            // runs. Remove that placeholder on Save As failure, but never
            // delete an existing document after an in-place Save failure.
            if (deleteNewDocumentOnFailure) {
                runCatching { android.provider.DocumentsContract.deleteDocument(resolver, output) }
                runCatching { resolver.delete(output, null, null) }
            }
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
            // Build a page-local layout from an exclusive character slice.
            // Drawing the complete document layout once per page and relying
            // on a floating-point clip boundary could paint the bottom line on
            // both pages. A character can now belong to exactly one page.
            val pageStart = layout.getLineStart(start)
            val pageEnd = layout.getLineEnd(line - 1)
            val pageStyled = SpannedString(styled.subSequence(pageStart, pageEnd))
            val pageLayout = StaticLayout.Builder.obtain(
                pageStyled,
                0,
                pageStyled.length,
                paint,
                (pageWidth - margin * 2).toInt(),
            ).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(3f, 1f)
                .build()
            val pageNumber = number++; val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            page.canvas.drawColor(Color.WHITE)
            if (hasTitle && heading != null) {
                page.canvas.save(); page.canvas.translate(margin, margin); heading.draw(page.canvas); page.canvas.restore()
            }
            page.canvas.save(); page.canvas.clipRect(margin, top, pageWidth - margin, pageHeight - margin)
            page.canvas.translate(margin, top); pageLayout.draw(page.canvas); page.canvas.restore()
            collectLinks(pageStyled, pageLayout, 0, pageLayout.lineCount, top, pageNumber - 1, links)
            val body = pageStyled.toString()
            pageTexts[pageNumber - 1] = if (hasTitle) (title + "\n\n" + body).trim() else body.trim()
            doc.finishPage(page)
        } while (line < layout.lineCount)
        return number
    }
    private fun collectLinks(text: android.text.Spanned, layout: StaticLayout, firstLine: Int, endLine: Int, top: Float, page: Int, out: MutableList<PdfLink>) {
        if (firstLine >= endLine) return
        val pageStart = layout.getLineStart(firstLine)
        val pageEnd = layout.getLineEnd(endLine - 1)
        text.getSpans(0, text.length, URLSpan::class.java).forEach { span ->
            val start = text.getSpanStart(span).coerceAtLeast(0)
            // HTML frequently leaves the paragraph newline inside URLSpan.
            // If that newline is the first character of the next PDF page it
            // used to create a second, phantom annotation on that page.
            val end = trimLinkEnd(text, start, text.getSpanEnd(span))
            val visibleStart = maxOf(start, pageStart)
            val visibleEnd = minOf(end, pageEnd)
            if (visibleStart >= visibleEnd) return@forEach
            val from = maxOf(firstLine, layout.getLineForOffset(visibleStart))
            val to = minOf(endLine - 1, layout.getLineForOffset(visibleEnd - 1))
            if (from <= to) for (line in from..to) {
                var a = maxOf(visibleStart, layout.getLineStart(line))
                var b = minOf(visibleEnd, layout.getLineEnd(line))
                while (a < b && text[a].isWhitespace()) a++
                while (b > a && text[b - 1].isWhitespace()) b--
                if (a >= b) continue
                val x1 = margin + layout.getPrimaryHorizontal(a); val x2 = margin + layout.getPrimaryHorizontal(b)
                val y1 = top + layout.getLineTop(line) - layout.getLineTop(firstLine); val y2 = top + layout.getLineBottom(line) - layout.getLineTop(firstLine)
                val item = PdfLink(page, minOf(x1, x2), y1, maxOf(x1, x2), y2, normalizeHttpUrl(span.url))
                if (item.right > item.left && item.bottom > item.top && item !in out) out += item
            }
        }
    }
    suspend fun readDraft(uri: Uri): PdfDraft? = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val loaded = resolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                val info = doc.documentInformation
                if (info.getCustomMetadataValue("AlalPDF-Version") == null) {
                    return@withContext null
                }
                fun decode(key: String) = info.getCustomMetadataValue(key)?.let {
                    String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8)
                }.orEmpty()
                DraftReadResult(
                    mode = runCatching {
                        CreatePdfMode.valueOf(
                            info.getCustomMetadataValue("AlalPDF-Mode") ?: "TEXT",
                        )
                    }.getOrDefault(CreatePdfMode.TEXT),
                    title = decode("AlalPDF-Title"),
                    body = decode("AlalPDF-Body"),
                    originalImages = decode("AlalPDF-Images").lines().filter(String::isNotBlank),
                    textPageCount = info.getCustomMetadataValue("AlalPDF-PageCount")
                        ?.toIntOrNull()
                        ?.coerceIn(0, doc.numberOfPages)
                        ?: 0,
                )
            }
        } ?: return@withContext null

        val editableImages = when (loaded.mode) {
            CreatePdfMode.TEXT -> emptyList()
            CreatePdfMode.IMAGE_TEXT ->
                renderDraftPages(uri, loaded.textPageCount).ifEmpty { loaded.originalImages }
            CreatePdfMode.IMAGES,
            CreatePdfMode.SCAN,
            -> renderDraftPages(uri, 0).ifEmpty { loaded.originalImages }
        }
        PdfDraft(loaded.mode, loaded.title, loaded.body, editableImages)
    }

    /**
     * Original image URIs may have expired and scans are intentionally removed
     * after saving. Reconstruct only the image/scan pages as local edit assets,
     * so every Alal creation mode can reopen in its original Create UI.
     */
    private fun renderDraftPages(uri: Uri, firstPage: Int): List<String> {
        val root = File(context.cacheDir, "edit-page-assets").apply { mkdirs() }
        root.listFiles()
            ?.filter { System.currentTimeMillis() - it.lastModified() > EDIT_ASSET_MAX_AGE_MS }
            ?.forEach(File::deleteRecursively)
        val outputDir = File(root, "draft-${System.nanoTime()}").apply { mkdirs() }
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: return emptyList()
        val renderer = runCatching { PdfRenderer(descriptor) }
            .getOrElse {
                descriptor.close()
                outputDir.deleteRecursively()
                return emptyList()
            }
        return try {
            buildList {
                for (index in firstPage.coerceAtLeast(0) until renderer.pageCount) {
                    renderer.openPage(index).use { page ->
                        val width = EDIT_PAGE_WIDTH_PX
                        val height = (width.toFloat() * page.height / page.width)
                            .toInt()
                            .coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            val file = File(outputDir, "page-${index + 1}.jpg")
                            FileOutputStream(file).use { stream ->
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream))
                            }
                            add(Uri.fromFile(file).toString())
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            outputDir.deleteRecursively()
            emptyList()
        } finally {
            renderer.close()
            runCatching { descriptor.close() }
        }
    }
    private fun finalizePdf(file: File, links: List<PdfLink>, spec: PdfSpec) {
        PDFBoxResourceLoader.init(context); val temp = File(file.parentFile, file.nameWithoutExtension + "-links.pdf")
        PDDocument.load(file).use { doc ->
            links.distinct().forEach { item ->
                val targetPage = doc.getPage(item.page)
                val link = PDAnnotationLink(); link.action = PDActionURI().apply { uri = item.url }
                link.rectangle = PDRectangle(item.left, pageHeight - item.bottom, item.right - item.left, item.bottom - item.top)
                link.page = targetPage
                targetPage.annotations.add(link)
            }
            fun encode(v:String)=Base64.encodeToString(v.toByteArray(Charsets.UTF_8),Base64.NO_WRAP)
            doc.documentInformation.apply { setCustomMetadataValue("AlalPDF-Version","1");setCustomMetadataValue("AlalPDF-Mode",spec.mode.name);setCustomMetadataValue("AlalPDF-Title",encode(spec.title));setCustomMetadataValue("AlalPDF-Body",encode(spec.bodyHtml));setCustomMetadataValue("AlalPDF-Images",encode(spec.images.joinToString("\n")))
                setCustomMetadataValue("AlalPDF-PageCount", pageTexts.size.toString())
                pageTexts.forEach { (index, text) -> setCustomMetadataValue("AlalPDF-Page-" + index, encode(text)) }
                setCustomMetadataValue("AlalPDF-LinkCount", links.size.toString())
                links.forEachIndexed { index, item ->
                    setCustomMetadataValue(
                        "AlalPDF-Link-$index",
                        AlalLinkMetadata.encode(
                            AlalStoredLink(
                                page = item.page,
                                left = item.left / pageWidth,
                                top = item.top / pageHeight,
                                right = item.right / pageWidth,
                                bottom = item.bottom / pageHeight,
                                url = item.url,
                            ),
                        ),
                    )
                }
            }
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
        val OUTPUT_MODES = arrayOf("rwt", "wt", "w")
        const val EDIT_PAGE_WIDTH_PX = 1_200
        const val EDIT_ASSET_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
        val TITLE_SIZES = floatArrayOf(24f, 20f, 18f)
        const val TITLE_MAX_LINES = 3
        const val TITLE_GAP = 16f
    }

    private data class DraftReadResult(
        val mode: CreatePdfMode,
        val title: String,
        val body: String,
        val originalImages: List<String>,
        val textPageCount: Int,
    )
}

internal fun trimLinkEnd(text: CharSequence, start: Int, rawEnd: Int): Int {
    var end = rawEnd.coerceIn(start, text.length)
    while (end > start && text[end - 1].isWhitespace()) end--
    return end
}
