package com.alalkipgen.alalpdf.reader
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.*
import java.io.File
class PdfReaderRepository(private val c:Context){private val r=c.contentResolver;private val l=Any();private var u:Uri?=null;private var pass:String?=null;private var s:PdfRendererSource?=null;private var tmp:File?=null;suspend fun pageCount(x:Uri,p:String?=null)=withContext(Dispatchers.IO){synchronized(l){open(x,p).pageCount}};suspend fun render(x:Uri,i:Int,w:Int,n:Boolean=false):Bitmap=withContext(Dispatchers.IO){synchronized(l){open(x,pass).renderPage(i,w,n)}};suspend fun links(x:Uri)=withContext(Dispatchers.IO){runCatching{PdfLinkExtractor.extract(r,x)}.getOrDefault(emptyMap())};suspend fun text(x:Uri)=withContext(Dispatchers.IO){PdfTextExtractor.extract(c,x,pass)};fun close()=synchronized(l){clear()};private fun clear(){runCatching{s?.close()};s=null;u=null;pass=null;tmp?.delete();tmp=null};private fun open(x:Uri,p:String?):PdfRendererSource{s?.takeIf{u==x&&pass==p}?.let{return it};clear();if(p==null){runCatching{PdfRendererSource.open(r.openFileDescriptor(x,"r")!!)}.getOrNull()?.let{s=it;u=x;return it}};PDFBoxResourceLoader.init(c);val f=File(c.cacheDir,"unlock-${System.nanoTime()}.pdf");try{r.openInputStream(x)!!.use{PDDocument.load(it,p.orEmpty()).use{d->d.isAllSecurityToBeRemoved=true;d.save(f)}}}catch(e:InvalidPasswordException){throw PdfPasswordRequiredException(e)};val z=PdfRendererSource.open(ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY));s=z;u=x;pass=p;tmp=f;return z}}
