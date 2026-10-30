package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which pages of a session get the PDFBox text layer instead of PDFium's text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MyanmarTextLayerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PdfBoxDocument.initResources(context)
    }

    @Test
    fun myanmarPageIsReadOnceAndNotAfterClose() = runBlocking {
        val session = session(MyanmarFixtures.pdf(MyanmarFixtures.CHROME, temp.root))
        val layer = session.myanmarTextLayer(0, PDFIUM_MYANMAR)
        assertNotNull(layer)
        assertEquals(MyanmarFixtures.expectedLines(MyanmarFixtures.CHROME).joinToString("\n"), layer!!.text)
        assertSame(layer, session.myanmarTextLayer(0, PDFIUM_MYANMAR))
        session.close()
        assertNull(session.myanmarTextLayer(0, PDFIUM_MYANMAR))
    }

    @Test
    fun latinPageNeverTouchesPdfBox() = runBlocking {
        val copies = pdfBoxCopies()
        val session = session(TestPdfs.write(temp.newFile("latin.pdf"), texts = listOf("Hello World")))
        // PDFium's text and runs stay in use unchanged.
        assertNull(session.myanmarTextLayer(0, "Hello World"))
        assertEquals(copies, pdfBoxCopies())
        session.close()
    }

    @Test
    fun pageWithoutPdfBoxTextKeepsPdfium() = runBlocking {
        val session = session(TestPdfs.write(temp.newFile("blank.pdf")))
        assertNull(session.myanmarTextLayer(0, PDFIUM_MYANMAR))
        // Out of range: PDFBox fails, PDFium stays in use.
        assertNull(session.myanmarTextLayer(5, PDFIUM_MYANMAR))
        session.close()
    }

    private fun session(file: File) =
        PdfDocumentSession(context, Uri.fromFile(file), null, NoRenderEngine(), null)

    private fun pdfBoxCopies(): Int = File(context.cacheDir, "pdfbox").listFiles().orEmpty().size

    private class NoRenderEngine : PdfRenderEngine {
        override val pageCount: Int = 1
        override fun renderPage(pageIndex: Int, width: Int, config: Bitmap.Config): Bitmap = error("not used")
        override fun pageAspectRatio(pageIndex: Int): Float = 1f
        override fun textRuns(pageIndex: Int): List<PdfTextRun> = emptyList()
        override fun close() = Unit
    }

    private companion object {
        /** The start of PDFium's text for the Chrome fixture. */
        const val PDFIUM_MYANMAR = "မြ န်မာန် မာစာ"
    }
}
