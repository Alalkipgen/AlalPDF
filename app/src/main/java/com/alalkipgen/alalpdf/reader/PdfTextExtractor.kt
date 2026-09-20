package com.alalkipgen.alalpdf.reader
import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
data class PdfPageText(val page:Int,val text:String);data class PdfSearchResult(val page:Int,val excerpt:String)
object PdfTextExtractor{fun extract(c:Context,u:Uri,p:String?=null):List<PdfPageText>{PDFBoxResourceLoader.init(c);return c.contentResolver.openInputStream(u)!!.use{i->PDDocument.load(i,p.orEmpty()).use{d->(1..d.numberOfPages).map{n->val s=PDFTextStripper().apply{startPage=n;endPage=n;sortByPosition=true};PdfPageText(n-1,s.getText(d).trim())}}}};fun search(ps:List<PdfPageText>,q0:String):List<PdfSearchResult>{val q=q0.trim();if(q.isEmpty())return emptyList();return ps.mapNotNull{p->val i=p.text.indexOf(q,ignoreCase=true);if(i<0)null else PdfSearchResult(p.page,p.text.substring((i-40).coerceAtLeast(0),(i+q.length+60).coerceAtMost(p.text.length)).replace('\n',' '))}}}
