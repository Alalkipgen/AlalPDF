package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfBoxDocumentTest {
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
    fun sessionCloseLeavesNoFileInPdfboxCache() = runBlocking {
        val source = TestPdfs.write(
            temp.newFile("links.pdf"),
            pageCount = 2,
            links = listOf(TestPdfs.Link(1, PDRectangle(20f, 10f, 40f, 20f), "https://example.com/")),
        )
        val renderer = FakeRenderEngine()
        val session = PdfDocumentSession(context, Uri.fromFile(source), null, renderer, null)

        assertFalse(AlalPdfText.readContent(session.pdfBox).isAlalPdf)
        assertEquals(setOf(1), PdfLinkExtractor.extractDocument(session.pdfBox).keys)
        assertEquals(1, files(context.cacheDir, "pdfbox").size)

        session.close()

        assertTrue(renderer.closed)
        assertEquals(emptyList<File>(), files(context.cacheDir, "pdfbox"))
        assertEquals(emptyList<File>(), files(context.cacheDir, "pdfbox-tmp"))
        assertTrue(source.exists())
    }

    @Test
    fun copiesOnceAndKeepsTheCopyAcrossTrim() {
        val source = TestPdfs.write(temp.newFile("three.pdf"), pageCount = 3)
        var opened = 0
        val pdf = PdfBoxDocument(cacheDir, null, null, { opened++; source.inputStream() }, {})

        assertEquals(3, pdf.read { it.numberOfPages })
        pdf.trim()
        assertEquals(3, pdf.read { it.numberOfPages })

        assertEquals(1, opened)
        assertEquals(1, files(cacheDir, "pdfbox").size)
        pdf.close()
        assertEquals(emptyList<File>(), files(cacheDir, "pdfbox"))
        assertEquals(emptyList<File>(), files(cacheDir, "pdfbox-tmp"))
        assertThrows(IllegalStateException::class.java) { pdf.read { it.numberOfPages } }
    }

    @Test
    fun reusesTheSessionsUnlockedCopyWithoutDeletingIt() {
        val unlocked = TestPdfs.write(File(cacheDir, "unlock-1.pdf"), pageCount = 2)
        val pdf = PdfBoxDocument(cacheDir, "secret", unlocked, { error("must not copy") }, {})

        assertEquals(2, pdf.read { it.numberOfPages })
        pdf.close()

        assertTrue(unlocked.exists())
        assertEquals(emptyList<File>(), files(cacheDir, "pdfbox"))
    }

    @Test
    fun loadFailureIsKeptAndTheCopyIsDeletedOnClose() {
        val corrupt = temp.newFile("corrupt.pdf").apply { writeText("%PDF-1.4\nnot really a pdf") }
        var opened = 0
        val pdf = PdfBoxDocument(cacheDir, null, null, { opened++; corrupt.inputStream() }, {})

        assertThrows(IOException::class.java) { pdf.read { it.numberOfPages } }
        assertThrows(IOException::class.java) { pdf.read { it.numberOfPages } }
        assertEquals(1, opened)

        pdf.close()
        assertEquals(emptyList<File>(), files(cacheDir, "pdfbox"))
    }

    @Test
    fun encryptedFileNeedsItsPassword() {
        val encrypted = TestPdfs.write(temp.newFile("locked.pdf"), pageCount = 2, userPassword = "secret")
        val uri = Uri.fromFile(encrypted)

        val withoutPassword = PdfBoxDocument(cacheDir, null, null, { encrypted.inputStream() }, {})
        assertThrows(InvalidPasswordException::class.java) { withoutPassword.read { it.numberOfPages } }
        withoutPassword.close()

        val wrong = File(context.cacheDir, "unlock-wrong.pdf")
        assertThrows(InvalidPasswordException::class.java) {
            PdfBoxDocument.writeUnlockedCopy(context, uri, "nope", wrong)
        }
        val unlocked = File(context.cacheDir, "unlock-right.pdf")
        PdfBoxDocument.writeUnlockedCopy(context, uri, "secret", unlocked)
        val reopened = PdfBoxDocument(cacheDir, null, unlocked, { error("must not copy") }, {})
        assertEquals(2, reopened.read { it.numberOfPages })
        assertFalse(reopened.read { it.isEncrypted })
        reopened.close()

        assertEquals(emptyList<File>(), files(cacheDir, "pdfbox"))
        assertEquals(emptyList<File>(), files(context.cacheDir, "pdfbox"))
        assertEquals(emptyList<File>(), files(context.cacheDir, "pdfbox-tmp"))
    }

    @Test
    fun wrongPasswordStillAsksForThePassword() {
        val encrypted = TestPdfs.write(temp.newFile("locked.pdf"), userPassword = "secret")

        assertThrows(PdfPasswordRequiredException::class.java) {
            PdfDocumentSession.open(context, Uri.fromFile(encrypted), "nope")
        }
        assertEquals(emptyList<File>(), files(context.cacheDir, "pdfbox"))
        assertEquals(
            emptyList<String>(),
            context.cacheDir.listFiles().orEmpty().map(File::getName).filter { it.startsWith("unlock-") },
        )
    }

    @Test
    fun closeDuringAReadDoesNotWaitAndCleansUpAfterIt() {
        val source = TestPdfs.write(temp.newFile("busy.pdf"))
        val pdf = PdfBoxDocument(cacheDir, null, null, { source.inputStream() }, {})
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = thread {
            pdf.read {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                it.numberOfPages
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        pdf.close()
        assertEquals(1, files(cacheDir, "pdfbox").size)

        release.countDown()
        reader.join(5_000)
        assertEquals(emptyList<File>(), files(cacheDir, "pdfbox"))
        assertEquals(emptyList<File>(), files(cacheDir, "pdfbox-tmp"))
    }

    @Test
    fun trimFromInsideAReadWaitsUntilTheReadReturns() {
        val source = TestPdfs.write(temp.newFile("nested.pdf"), pageCount = 2)
        val pdf = PdfBoxDocument(cacheDir, null, null, { source.inputStream() }, {})

        val outer = pdf.read { document ->
            pdf.trim()
            assertEquals(2, pdf.read { it.numberOfPages })
            assertFalse(document.document.isClosed)
            document
        }

        assertTrue(outer.document.isClosed)
        assertEquals(2, pdf.read { it.numberOfPages })
        pdf.close()
    }

    @Test
    fun staleFilesAreDeletedButFreshAndOpenOnesKept() {
        val now = System.currentTimeMillis()
        val dayAndAnHour = TimeUnit.HOURS.toMillis(25)
        val copies = File(cacheDir, "pdfbox").apply { mkdirs() }
        val scratch = File(cacheDir, "pdfbox-tmp").apply { mkdirs() }
        val staleCopy = File(copies, "stale.pdf").apply { writeText("x"); setLastModified(now - dayAndAnHour) }
        val staleScratch = File(scratch, "PDFBox1.tmp").apply { writeText("x"); setLastModified(now - dayAndAnHour) }
        val freshCopy = File(copies, "fresh.pdf").apply { writeText("x") }
        val unrelated = File(cacheDir, "unlock-1.pdf").apply { writeText("x"); setLastModified(now - dayAndAnHour) }
        val source = TestPdfs.write(temp.newFile("open.pdf"))
        val open = PdfBoxDocument(cacheDir, null, null, { source.inputStream() }, {})
        open.read { it.numberOfPages }
        val openCopy = files(cacheDir, "pdfbox").single { it.name != "stale.pdf" && it.name != "fresh.pdf" }
        openCopy.setLastModified(now - dayAndAnHour)

        PdfBoxDocument.deleteStaleFiles(cacheDir, now)

        assertFalse(staleCopy.exists())
        assertFalse(staleScratch.exists())
        assertTrue(freshCopy.exists())
        assertTrue(openCopy.exists())
        assertTrue(unrelated.exists())
        open.trim()
        assertEquals(1, open.read { it.numberOfPages })
        open.close()
        assertFalse(openCopy.exists())
    }

    @Test
    fun storedTextIsReadThroughTheSharedDocument() {
        val encoded = Base64.encodeToString("မင်္ဂလာပါ".toByteArray(), Base64.NO_WRAP)
        val source = TestPdfs.write(
            temp.newFile("alal.pdf"),
            pageCount = 2,
            metadata = mapOf(
                "AlalPDF-Version" to "1",
                "AlalPDF-PageCount" to "2",
                "AlalPDF-Page-1" to encoded,
            ),
        )
        val pdf = PdfBoxDocument(cacheDir, null, null, { source.inputStream() }, {})

        val content = AlalPdfText.readContent(pdf)

        assertTrue(content.isAlalPdf)
        assertEquals(mapOf(1 to "မင်္ဂလာပါ"), content.pageTexts)
        pdf.close()
        assertEquals(AlalPdfStoredContent(), AlalPdfText.readContent(pdf))
    }

    private fun files(root: File, directory: String): List<File> =
        File(root, directory).listFiles().orEmpty().filter(File::isFile)

    private class FakeRenderEngine : PdfRenderEngine {
        var closed = false
        override val pageCount: Int = 1
        override fun renderPage(pageIndex: Int, width: Int, config: Bitmap.Config): Bitmap =
            error("not used")
        override fun pageAspectRatio(pageIndex: Int): Float = 1f
        override fun textRuns(pageIndex: Int): List<PdfTextRun> = emptyList()
        override fun close() {
            closed = true
        }
    }
}
