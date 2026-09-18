package com.alalkipgen.alalpdf.create

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CreatePdfRepository(private val resolver: ContentResolver) {
    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 48f

    suspend fun writeText(output: Uri, title: String, body: String) = withContext(Dispatchers.IO) {
        writeDocument(output) { document -> addTextPages(document, title, body, 1) }
    }

    suspend fun writeImages(output: Uri, images: List<Uri>) = withContext(Dispatchers.IO) {
        require(images.isNotEmpty()) { "Choose at least one image" }
        writeDocument(output) { document ->
            images.forEachIndexed { index, uri ->
                decode(uri)?.let { bitmap ->
                    addImagePage(document, bitmap, index + 1)
                    bitmap.recycle()
                }
            }
        }
    }

    suspend fun writeImageAndText(output: Uri, title: String, body: String, images: List<Uri>) =
        withContext(Dispatchers.IO) {
            require(images.isNotEmpty()) { "Choose at least one image" }
            writeDocument(output) { document ->
                var page = addTextPages(document, title, body, 1)
                images.forEach { uri ->
                    decode(uri)?.let { bitmap ->
                        addImagePage(document, bitmap, page++)
                        bitmap.recycle()
                    }
                }
            }
        }

    suspend fun writeScan(output: Uri, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        writeDocument(output) { document -> addImagePage(document, bitmap, 1) }
    }

    private fun writeDocument(output: Uri, build: (PdfDocument) -> Unit) {
        val document = PdfDocument()
        try {
            build(document)
            resolver.openOutputStream(output, "w")?.use(document::writeTo)
                ?: error("Unable to create output file")
        } finally {
            document.close()
        }
    }

    /** Returns the next page number. */
    private fun addTextPages(document: PdfDocument, title: String, body: String, firstPage: Int): Int {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val lines = wrap(body.ifBlank { " " }, bodyPaint, pageWidth - margin * 2)
        val linesPerPage = ((pageHeight - margin * 2 - 52f) / 23f).toInt().coerceAtLeast(1)
        val chunks = lines.chunked(linesPerPage).ifEmpty { listOf(listOf("")) }
        var pageNumber = firstPage
        chunks.forEachIndexed { chunkIndex, chunk ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber++).create())
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            var y = margin
            if (chunkIndex == 0 && title.isNotBlank()) {
                canvas.drawText(title.take(80), margin, y + 24f, titlePaint)
                y += 52f
            }
            chunk.forEach { line ->
                canvas.drawText(line, margin, y + 16f, bodyPaint)
                y += 23f
            }
            document.finishPage(page)
        }
        return pageNumber
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val output = mutableListOf<String>()
        text.replace("\r", "").split("\n").forEach { paragraph ->
            if (paragraph.isBlank()) {
                output += ""
            } else {
                var remaining = paragraph
                while (remaining.isNotEmpty()) {
                    var count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                    if (count < remaining.length) {
                        val breakAt = remaining.lastIndexOf(' ', count - 1).takeIf { it > 0 } ?: count
                        count = breakAt
                    }
                    output += remaining.take(count).trimEnd()
                    remaining = remaining.drop(count).trimStart()
                }
            }
        }
        return output
    }

    private fun addImagePage(document: PdfDocument, bitmap: Bitmap, pageNumber: Int) {
        val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        page.canvas.drawColor(Color.WHITE)
        drawBitmapFit(page.canvas, bitmap)
        document.finishPage(page)
    }

    private fun drawBitmapFit(canvas: Canvas, bitmap: Bitmap) {
        val availableWidth = pageWidth - margin * 2
        val availableHeight = pageHeight - margin * 2
        val scale = minOf(availableWidth / bitmap.width, availableHeight / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (pageWidth - width) / 2f
        val top = (pageHeight - height) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun decode(uri: Uri): Bitmap? = resolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
}
