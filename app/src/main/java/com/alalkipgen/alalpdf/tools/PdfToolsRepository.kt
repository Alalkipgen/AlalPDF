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
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
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
        context.contentResolver.openInputStream(input)!!.use { stream ->
            PDDocument.load(stream).use { document ->
                reorder(document, plan.pages)
                if (document.numberOfPages > 0) {
                    val font = if (plan.texts.any { it.text.isNotBlank() }) {
                        context.resources.openRawResource(com.alalkipgen.alalpdf.R.font.pyidaungsu_regular)
                            .use { PDType0Font.load(document, it, true) }
                    } else {
                        null
                    }
                    plan.texts.filter { it.text.isNotBlank() }.forEach { note ->
                        val page = document.getPage(note.page.coerceIn(0, document.numberOfPages - 1))
                        drawText(document, page, note, font!!)
                    }
                    plan.images.forEach { note ->
                        val page = document.getPage(note.page.coerceIn(0, document.numberOfPages - 1))
                        drawImage(document, page, note)
                    }
                }
                document.save(temp)
            }
        }
        check(temp.length() > 5L) { "Edited PDF is empty" }
        val descriptor = context.contentResolver.openFileDescriptor(output, "rwt") ?: error("Unable to save edited PDF")
        descriptor.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { out ->
                temp.inputStream().use { it.copyTo(out) }
                out.flush()
                runCatching { out.fd.sync() }
            }
        }
        context.contentResolver.openInputStream(output)!!.use { PDDocument.load(it).use { check(it.numberOfPages > 0) } }
        temp.delete()
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

    private fun drawText(document: PDDocument, page: PDPage, note: TextNote, font: PDType0Font) {
        val box = page.mediaBox
        val left = box.lowerLeftX + box.width * note.xFraction
        val top = box.upperRightY - box.height * note.yFraction
        val maxWidth = (box.width * note.widthFraction).coerceAtLeast(40f)
        val lines = wrap(note.text, font, note.fontSize, maxWidth)
        val leading = note.fontSize * 1.35f

        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { canvas ->
            if (note.whiteout) {
                // A real rectangle the user sized, not a fixed 48pt bar.
                val height = (box.height * note.whiteoutHeightFraction).coerceAtLeast(leading * lines.size)
                canvas.setNonStrokingColor(255, 255, 255)
                canvas.addRect(left, (top - height).coerceAtLeast(0f), maxWidth, height)
                canvas.fill()
            }
            if (lines.isEmpty()) return@use
            canvas.setNonStrokingColor(0, 0, 0)
            canvas.beginText()
            canvas.setFont(font, note.fontSize)
            canvas.setLeading(leading.toDouble())
            canvas.newLineAtOffset(left, top - note.fontSize)
            lines.forEachIndexed { index, line ->
                if (index > 0) canvas.newLine()
                canvas.showText(line)
            }
            canvas.endText()
        }
    }

    /** Breaks text on its own newlines first, then on measured font width. */
    private fun wrap(text: String, font: PDType0Font, fontSize: Float, maxWidth: Float): List<String> {
        fun width(value: String): Float =
            runCatching { font.getStringWidth(value) / 1000f * fontSize }.getOrDefault(Float.MAX_VALUE)

        val output = ArrayList<String>()
        text.split("\n").forEach { paragraph ->
            if (paragraph.isBlank()) { output.add(""); return@forEach }
            var line = StringBuilder()
            paragraph.split(" ").filter { it.isNotEmpty() }.forEach { word ->
                val candidate = if (line.isEmpty()) word else line.toString() + " " + word
                if (width(candidate) <= maxWidth || line.isEmpty()) {
                    line = StringBuilder(candidate)
                } else {
                    output.add(line.toString())
                    line = StringBuilder(word)
                }
                // A single word longer than the column still has to break.
                while (width(line.toString()) > maxWidth && line.length > 1) {
                    var cut = line.length - 1
                    while (cut > 1 && width(line.substring(0, cut)) > maxWidth) cut--
                    output.add(line.substring(0, cut))
                    line = StringBuilder(line.substring(cut))
                }
            }
            if (line.isNotEmpty()) output.add(line.toString())
        }
        return output
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
