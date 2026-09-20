package com.alalkipgen.alalpdf.reader
import org.junit.*
class PdfTextExtractorTest{@Test fun search(){Assert.assertEquals(1,PdfTextExtractor.search(listOf(PdfPageText(0,"မြန်မာ Search")),"search").size)}}
