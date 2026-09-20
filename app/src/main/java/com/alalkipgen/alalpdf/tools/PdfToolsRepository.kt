package com.alalkipgen.alalpdf.tools

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class PagePlan(val source: Int, val rotation: Int = 0)
data class EditPlan(
    val pages: List<PagePlan>, val selected: Int, val text: String, val image: Uri?, val cover: Boolean,
    val xFraction: Float = .08f, val yFraction: Float = .08f, val fontSize: Float = 14f,
    val imageWidthFraction: Float = .35f, val imageHeightFraction: Float = .25f,
)

class PdfToolsRepository(private val context: Context) {
    init { PDFBoxResourceLoader.init(context) }
    suspend fun count(uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)!!.use { PDDocument.load(it).use(PDDocument::getNumberOfPages) }
    }
    @SuppressLint("ResourceType")
    suspend fun save(input: Uri, output: Uri, plan: EditPlan) = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "edit-${System.nanoTime()}.pdf")
        context.contentResolver.openInputStream(input)!!.use { stream ->
            PDDocument.load(stream).use { source -> PDDocument().use { result ->
                plan.pages.forEach { item -> result.importPage(source.getPage(item.source)).rotation = item.rotation }
                if (result.numberOfPages > 0) {
                    val page = result.getPage(plan.selected.coerceIn(0, result.numberOfPages - 1)); val box = page.mediaBox
                    val x = (box.width * plan.xFraction).coerceIn(0f, box.width - 20f)
                    val y = (box.height * (1f - plan.yFraction)).coerceIn(20f, box.height)
                    PDPageContentStream(result, page, PDPageContentStream.AppendMode.APPEND, true, true).use { canvas ->
                        if (plan.cover) { canvas.setNonStrokingColor(255,255,255); canvas.addRect(x,(y-32f).coerceAtLeast(0f),(box.width-x-24f).coerceAtLeast(20f),48f); canvas.fill() }
                        if (plan.text.isNotBlank()) {
                            val font = context.resources.openRawResource(com.alalkipgen.alalpdf.R.font.pyidaungsu_regular).use { PDType0Font.load(result,it,true) }
                            canvas.setNonStrokingColor(0,0,0); canvas.beginText(); canvas.setFont(font,plan.fontSize); canvas.newLineAtOffset(x,y)
                            canvas.showText(plan.text.replace("\n"," ").take(500)); canvas.endText()
                        }
                        plan.image?.let { uri -> context.contentResolver.openInputStream(uri)?.use { imageStream ->
                            val image = JPEGFactory.createFromStream(result,imageStream)
                            canvas.drawImage(image,x,(y-box.height*plan.imageHeightFraction).coerceAtLeast(0f),box.width*plan.imageWidthFraction,box.height*plan.imageHeightFraction)
                        } }
                    }
                }
                result.save(temp)
            } }
        }
        check(temp.length() > 5L) { "Edited PDF is empty" }
        val descriptor = context.contentResolver.openFileDescriptor(output,"rwt") ?: error("Unable to save edited PDF")
        descriptor.use { pfd -> FileOutputStream(pfd.fileDescriptor).use { out -> temp.inputStream().use { it.copyTo(out) }; out.flush(); runCatching { out.fd.sync() } } }
        context.contentResolver.openInputStream(output)!!.use { PDDocument.load(it).use { check(it.numberOfPages > 0) } }
        temp.delete()
    }
}
