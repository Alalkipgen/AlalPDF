package com.alalkipgen.alalpdf.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins link behavior on the shared PDFBox document: same rects, filters and dedupe as before. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfLinkExtractorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PdfBoxDocument.initResources(context)
        cacheDir = temp.newFolder("cache")
    }

    @Test
    fun linksAreNormalizedForEveryRotation() = runBlocking {
        // 20..60 x 10..30 on a 200 x 100 page: x 0.1..0.3, y 0.7..0.9 before rotation.
        val rect = PDRectangle(20f, 10f, 40f, 20f)
        val pdf = document(
            pageCount = 4,
            rotations = listOf(0, 90, 180, 270),
            links = (0 until 4).map { TestPdfs.Link(it, rect, "https://example.com/$it") },
        )

        val links = PdfLinkExtractor.extractDocument(pdf)

        assertLink(links.getValue(0).single(), 0.1f, 0.7f, 0.3f, 0.9f, "https://example.com/0")
        assertLink(links.getValue(1).single(), 0.1f, 0.1f, 0.3f, 0.3f, "https://example.com/1")
        assertLink(links.getValue(2).single(), 0.7f, 0.1f, 0.9f, 0.3f, "https://example.com/2")
        assertLink(links.getValue(3).single(), 0.7f, 0.7f, 0.9f, 0.9f, "https://example.com/3")
        assertEquals(links.getValue(2), PdfLinkExtractor.extractPage(pdf, 2))
        pdf.close()
    }

    @Test
    fun unsafeDuplicateAndEmptyLinksAreDropped() = runBlocking {
        val rect = PDRectangle(20f, 10f, 40f, 20f)
        val pdf = document(
            pageCount = 2,
            links = listOf(
                TestPdfs.Link(0, rect, " https://example.com/ok "),
                TestPdfs.Link(0, rect, "https://example.com/ok"),
                TestPdfs.Link(0, rect, "javascript:alert(1)"),
                TestPdfs.Link(0, rect, "file:///sdcard/secret.pdf"),
                TestPdfs.Link(0, PDRectangle(20f, 10f, 0f, 20f), "https://example.com/flat"),
                TestPdfs.Link(1, rect, "mailto:someone@example.com"),
            ),
        )

        val links = PdfLinkExtractor.extractDocument(pdf)

        assertEquals(setOf(0), links.keys)
        assertLink(links.getValue(0).single(), 0.1f, 0.7f, 0.3f, 0.9f, "https://example.com/ok")
        assertEquals(emptyList<PdfPageLink>(), PdfLinkExtractor.extractPage(pdf, 1))
        assertEquals(emptyList<PdfPageLink>(), PdfLinkExtractor.extractPage(pdf, 7))
        pdf.close()
    }

    @Test
    fun cancellationStopsThePassBetweenPages() {
        val source = TestPdfs.write(temp.newFile(), pageCount = 3)
        val job = Job()
        // Cancelled while the document loads, i.e. after the pass started and before page 0.
        val pdf = PdfBoxDocument(cacheDir, null, null, { source.inputStream() }, { job.cancel() })

        assertThrows(CancellationException::class.java) {
            runBlocking { withContext(job) { PdfLinkExtractor.extractDocument(pdf) } }
        }
        pdf.close()
    }

    private fun document(
        pageCount: Int,
        rotations: List<Int> = emptyList(),
        links: List<TestPdfs.Link> = emptyList(),
    ): PdfBoxDocument {
        val source = TestPdfs.write(temp.newFile(), pageCount, rotations, links)
        return PdfBoxDocument(cacheDir, null, null, { source.inputStream() }, {})
    }

    private fun assertLink(
        link: PdfPageLink,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        url: String,
    ) {
        assertEquals(url, link.url)
        assertEquals(left, link.left, EPSILON)
        assertEquals(top, link.top, EPSILON)
        assertEquals(right, link.right, EPSILON)
        assertEquals(bottom, link.bottom, EPSILON)
    }

    private companion object {
        const val EPSILON = 1e-5f
    }
}
