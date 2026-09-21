package com.alalkipgen.alalpdf.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import com.alalkipgen.alalpdf.reader.PdfTextRun
import com.alalkipgen.alalpdf.reader.PdfiumTextSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class PagePlan(val source: Int, val rotation: Int = 0)

/**
 * A piece of text placed on a page.
 *
 * Positions are fractions of the page box measured from the top-left corner,
 * which is what the editor preview works in, so a note lands exactly where it
 * was dropped.
 */
data class TextNote(
    val page: Int,
    val text: String,
    val xFraction: Float = .08f,
    val yFraction: Float = .08f,
    val fontSize: Float = 14f,
    val widthFraction: Float = .84f,
    val whiteout: Boolean = false,
    val whiteoutHeightFraction: Float = .06f,
)

data class ImageNote(
    val page: Int,
    val uri: Uri,
    val xFraction: Float = .1f,
    val yFraction: Float = .1f,
    val widthFraction: Float = .35f,
    val heightFraction: Float = .25f,
)

/**
 * A block of text that already exists in the document, located with PDFium.
 * Coordinates are fractions of the page measured from the top-left corner.
 */
data class PdfTextBlock(
    val page: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val fontSizePoints: Float,
)

data class EditPlan(
    val pages: List<PagePlan>,
    val texts: List<TextNote> = emptyList(),
    val images: List<ImageNote> = emptyList(),
)

class PdfToolsRepository(private val context: Context) {
    init { PDFBoxResourceLoader.init(context) }

    suspend fun count(uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)!!.use { PDDocument.load(it).use(PDDocument::getNumberOfPages) }
    }

    /** Page bitmap used as the editor background, so editing is WYSIWYG. */
    suspend fun renderPage(uri: Uri, page: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (page !in 0 until renderer.pageCount) return@use null
                    renderer.openPage(page).use { rendered ->
                        val safeWidth = width.coerceIn(200, 1_200)
                        val height = (safeWidth.toFloat() * rendered.height / rendered.width).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(safeWidth, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        rendered.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }.getOrNull()
    }

    @SuppressLint("ResourceType")
    suspend fun save(input: Uri, output: Uri, plan: EditPlan) = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "edit-${System.nanoTime()}.pdf")
        try {
            // The input is completely closed before output is opened. This is
            // required for in-place Save; PDFBox warns that writing over a file
            // which it is still reading can corrupt the document.
            context.contentResolver.openInputStream(input)?.use { stream ->
                PDDocument.load(stream).use { document ->
                    reorder(document, plan.pages)
                    val overlays = ArrayList<PDDocument>()
                    val scratch = ArrayList<File>()
                    try {
                        if (document.numberOfPages > 0) {
                            plan.texts.filter { it.text.isNotBlank() }.forEach { note ->
                                val page = document.getPage(note.page.coerceIn(0, document.numberOfPages - 1))
                                drawText(document, page, note, overlays, scratch)
                            }
                            plan.images.forEach { note ->
                                val page = document.getPage(note.page.coerceIn(0, document.numberOfPages - 1))
                                drawImage(document, page, note)
                            }
                        }
                        document.save(temp)
                    } finally {
                        overlays.forEach { runCatching { it.close() } }
                        scratch.forEach { runCatching { it.delete() } }
                    }
                }
            } ?: error("Unable to read the source PDF")
            check(temp.length() > 5L) { "Edited PDF is empty" }
            val descriptor = context.contentResolver.openFileDescriptor(output, "rwt")
                ?: error("This PDF is read-only. Use Save As instead.")
            descriptor.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    temp.inputStream().use { it.copyTo(out) }
                    out.flush()
                    runCatching { out.fd.sync() }
                }
            }
            context.contentResolver.openInputStream(output)?.use {
                PDDocument.load(it).use { document -> check(document.numberOfPages > 0) }
            } ?: error("Unable to verify the saved PDF")
        } finally {
            temp.delete()
        }
    }

    /**
     * Applies the page order and rotations **inside** the original document.
     *
     * The old code copied pages into a brand new PDDocument with importPage(),
     * which silently threw away the outline, the links and any annotation that
     * lived on the document instead of the page.
     */
    private fun reorder(document: PDDocument, pages: List<PagePlan>) {
        if (pages.isEmpty()) return
        val original = (0 until document.numberOfPages).map { document.getPage(it) }
        val unchanged = pages.size == original.size &&
            pages.withIndex().all { (index, plan) -> plan.source == index }
        if (unchanged) {
            pages.forEach { plan -> original[plan.source].rotation = plan.rotation }
            return
        }
        original.forEach { page -> document.pages.remove(page) }
        pages.forEach { plan ->
            val page = original.getOrNull(plan.source) ?: return@forEach
            page.rotation = plan.rotation
            document.pages.add(page)
        }
    }

    /**
     * Stamps a note onto a page.
     *
     * The text is rendered by Android first and imported as a form XObject, so
     * Burmese (and every other complex script) is shaped correctly instead of
     * coming out as a row of broken clusters, and the page keeps its original
     * content untouched underneath.
     */
    private fun drawText(
        document: PDDocument,
        page: PDPage,
        note: TextNote,
        overlays: MutableList<PDDocument>,
        scratch: MutableList<File>,
    ) {
        val box = page.mediaBox
        val left = box.lowerLeftX + box.width * note.xFraction
        val top = box.upperRightY - box.height * note.yFraction
        val maxWidth = (box.width * note.widthFraction).coerceAtLeast(40f)

        val overlay = ShapedTextOverlay.render(
            context = context,
            text = note.text,
            widthPoints = maxWidth,
            fontSizePoints = note.fontSize,
        )

        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { canvas ->
            if (note.whiteout) {
                val height = (box.height * note.whiteoutHeightFraction)
                    .coerceAtLeast(overlay?.heightPoints ?: note.fontSize)
                canvas.setNonStrokingColor(255, 255, 255)
                canvas.addRect(left, (top - height).coerceAtLeast(0f), maxWidth, height)
                canvas.fill()
            }
        }
        if (overlay == null) return

        val overlayDocument = runCatching { PDDocument.load(overlay.file) }.getOrNull() ?: return
        overlays.add(overlayDocument)
        scratch.add(overlay.file)
        val form = runCatching { LayerUtility(document).importPageAsForm(overlayDocument, 0) }.getOrNull()
            ?: return
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { canvas ->
            canvas.saveGraphicsState()
            canvas.transform(
                Matrix.getTranslateInstance(left, (top - overlay.heightPoints).coerceAtLeast(0f)),
            )
            canvas.drawForm(form)
            canvas.restoreGraphicsState()
        }
    }

    /**
     * Text blocks that already exist on a page, so the editor can let the user
     * tap a paragraph and rewrite it in place instead of only stacking new
     * boxes on top of the page.
     */
    suspend fun textBlocks(uri: Uri, page: Int): List<PdfTextBlock> = withContext(Dispatchers.IO) {
        val source = PdfiumTextSource.open(context, uri, null) ?: return@withContext emptyList()
        source.use { pdfium ->
            val runs = pdfium.charRuns(page).filter { it.right > it.left && it.bottom > it.top }
            if (runs.isEmpty()) return@withContext emptyList()
            val height = pdfium.pageSizePoints(page)?.height ?: 842f
            groupBlocks(runs, page, height)
        }
    }

    private fun groupBlocks(runs: List<PdfTextRun>, page: Int, pageHeightPoints: Float): List<PdfTextBlock> {
        val lines = ArrayList<MutableList<PdfTextRun>>()
        runs.forEach { run ->
            val line = lines.lastOrNull()
            val reference = line?.lastOrNull()
            val sameLine = reference != null &&
                kotlin.math.abs(
                    (run.top + run.bottom) / 2f - (reference.top + reference.bottom) / 2f,
                ) <= (reference.bottom - reference.top) * 0.6f
            if (sameLine) line.add(run) else lines.add(mutableListOf(run))
        }

        val blocks = ArrayList<PdfTextBlock>()
        var current = ArrayList<MutableList<PdfTextRun>>()

        fun flush() {
            if (current.isEmpty()) return
            val all = current.flatten()
            val left = all.minOf { it.left }
            val right = all.maxOf { it.right }
            val top = all.minOf { it.top }
            val bottom = all.maxOf { it.bottom }
            val text = current.joinToString("\n") { line -> line.joinToString("") { it.text } }.trim()
            val lineHeight = current.maxOf { line -> line.maxOf { it.bottom } - line.minOf { it.top } }
            if (text.isNotBlank()) {
                blocks.add(
                    PdfTextBlock(
                        page = page,
                        text = text,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        // A glyph box is roughly the font size, minus the bits
                        // that hang below the baseline.
                        fontSizePoints = (lineHeight * pageHeightPoints * 0.85f).coerceIn(6f, 72f),
                    ),
                )
            }
            current = ArrayList()
        }

        lines.forEach { line ->
            val previous = current.lastOrNull()
            if (previous == null) {
                current.add(line)
                return@forEach
            }
            val previousBottom = previous.maxOf { it.bottom }
            val lineTop = line.minOf { it.top }
            val lineHeight = line.maxOf { it.bottom } - lineTop
            val gap = lineTop - previousBottom
            if (gap in -lineHeight..(lineHeight * 1.4f)) current.add(line) else { flush(); current.add(line) }
        }
        flush()
        return blocks
    }

    private fun drawImage(document: PDDocument, page: PDPage, note: ImageNote) {
        val bitmap = context.contentResolver.openInputStream(note.uri)?.use(BitmapFactory::decodeStream) ?: return
        val box = page.mediaBox
        val width = box.width * note.widthFraction
        val height = box.height * note.heightFraction
        val left = box.lowerLeftX + box.width * note.xFraction
        val bottom = (box.upperRightY - box.height * note.yFraction - height).coerceAtLeast(0f)
        // LosslessFactory accepts PNG and screenshots too; the old JPEGFactory
        // call failed on anything that was not already a JPEG.
        val image = LosslessFactory.createFromImage(document, bitmap)
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { canvas ->
            canvas.drawImage(image, left, bottom, width, height)
        }
        bitmap.recycle()
    }
}
