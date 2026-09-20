package com.alalkipgen.alalpdf.tools
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.*
import java.io.File
data class PagePlan(val source:Int,val rotation:Int=0);data class EditPlan(val pages:List<PagePlan>,val selected:Int,val text:String,val image:Uri?,val cover:Boolean)
class PdfToolsRepository(val c:Context){init{PDFBoxResourceLoader.init(c)};suspend fun count(u:Uri)=withContext(Dispatchers.IO){c.contentResolver.openInputStream(u)!!.use{PDDocument.load(it).use(PDDocument::getNumberOfPages)}};@SuppressLint("ResourceType") suspend fun save(i:Uri,o:Uri,p:EditPlan)=withContext(Dispatchers.IO){val f=File(c.cacheDir,"edit.pdf");c.contentResolver.openInputStream(i)!!.use{x->PDDocument.load(x).use{src->PDDocument().use{dst->p.pages.forEach{q->dst.importPage(src.getPage(q.source)).rotation=q.rotation};if(dst.numberOfPages>0){val page=dst.getPage(p.selected.coerceIn(0,dst.numberOfPages-1));PDPageContentStream(dst,page,PDPageContentStream.AppendMode.APPEND,true,true).use{cs->if(p.cover){cs.setNonStrokingColor(255,255,255);cs.addRect(36f,page.mediaBox.height-92f,page.mediaBox.width-72f,64f);cs.fill()};if(p.text.isNotBlank()){val font=c.resources.openRawResource(com.alalkipgen.alalpdf.R.font.pyidaungsu_regular).use{PDType0Font.load(dst,it,true)};cs.beginText();cs.setFont(font,14f);cs.newLineAtOffset(48f,page.mediaBox.height-64f);cs.showText(p.text.replace("\n"," ").take(500));cs.endText()};p.image?.let{v->c.contentResolver.openInputStream(v)?.use{z->val pic=JPEGFactory.createFromStream(dst,z);cs.drawImage(pic,36f,36f,180f,180f)}}}};dst.save(f)}}};c.contentResolver.openOutputStream(o,"w")!!.use{out->f.inputStream().use{it.copyTo(out)}};f.delete()}}
