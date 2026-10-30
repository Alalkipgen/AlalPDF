package com.alalkipgen.alalpdf.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.alalkipgen.alalpdf.reader.MyanmarFixtures.CHROME
import com.alalkipgen.alalpdf.reader.MyanmarFixtures.LIBREOFFICE
import com.alalkipgen.alalpdf.reader.MyanmarFixtures.VISUAL
import com.alalkipgen.alalpdf.reader.MyanmarFixtures.WORD_2001
import com.alalkipgen.alalpdf.reader.MyanmarFixtures.WORD_2107
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Burmese PDFs exported by Chrome and LibreOffice, and Word-style PDFs (see
 * the README next to them). PDFium returned 32 % and 4 % wrong characters
 * for the Chrome and LibreOffice pages, and PDFBox with the Word ToUnicode
 * about 18 %; the text must be exact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MyanmarFixtureTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PdfBoxDocument.initResources(context)
    }

    @Test
    fun chromeExportIsExact() = assertExact(CHROME, "မင်္ဂလာပါ", "ကျွန်တော်တို့")

    @Test
    fun libreOfficeExportIsExact() = assertExact(LIBREOFFICE, "မင်္ဂလာပါ", "ကျွန်တော်တို့")

    @Test
    fun wordExportWithBrokenToUnicodeIsExact() = assertExact(WORD_2001, "ကျွန်ုပ်တို့", "သင်္ချာ")

    @Test
    fun wordExportWithNewerNotoIsExact() = assertExact(WORD_2107, "ကျွန်ုပ်တို့", "သင်္ချာ")

    @Test
    fun visualOrderWithoutActualTextIsExact() = assertExact(VISUAL, "ကျွန်ုပ်တို့", "သင်္ချာ")

    @Test
    fun rotatedPagesKeepTheTextAndTurnTheBoxes() {
        val file = MyanmarFixtures.pdf(CHROME, temp.root)
        val upright = extract(file)
        for (rotation in listOf(90, 180, 270)) {
            val turned = PDDocument.load(file).use { document ->
                document.getPage(0).rotation = rotation
                PdfBoxTextSource.extract(document, 0)
            }
            assertEquals(upright.text, turned.text)
            assertEquals(upright.runs.size, turned.runs.size)
            upright.runs.zip(turned.runs).forEach { (run, rotated) ->
                val expected = turn(run, rotation)
                assertEquals(expected.left, rotated.left, EPSILON)
                assertEquals(expected.top, rotated.top, EPSILON)
                assertEquals(expected.right, rotated.right, EPSILON)
                assertEquals(expected.bottom, rotated.bottom, EPSILON)
            }
        }
    }

    @Test
    fun latinTextReadsTheSame() {
        val file = TestPdfs.write(temp.newFile("latin.pdf"), texts = listOf("Hello World, PDF Reader 123"))
        assertEquals("Hello World, PDF Reader 123", extract(file).text)
    }

    @Test
    fun slowOrCancelledPagesStop() {
        PDDocument.load(MyanmarFixtures.pdf(CHROME, temp.root)).use { document ->
            assertThrows(PageTextTimeoutException::class.java) {
                PdfBoxTextSource.extract(document, 0, timeoutMs = -1)
            }
            assertThrows(CancellationException::class.java) {
                PdfBoxTextSource.extract(document, 0, isActive = { false })
            }
        }
    }

    private fun assertExact(name: String, vararg words: String) {
        val layer = extract(MyanmarFixtures.pdf(name, temp.root))
        assertEquals(MyanmarFixtures.expectedLines(name), layer.text.lines())
        words.forEach { word -> assertEquals(word, 1, PdfTextSearch.matches(0, layer.text, word).size) }
        // Select all joins every run, so it copies exactly the page text.
        assertEquals(layer.text, layer.runs.joinToString("") { it.text }.trim())
        layer.runs.forEach { run ->
            assertTrue(run.left in 0f..1f && run.right in 0f..1f && run.top in 0f..1f && run.bottom in 0f..1f)
            assertTrue(run.left <= run.right && run.top <= run.bottom)
            if (run.text.isNotBlank()) assertTrue(run.bottom > run.top)
        }
    }

    private fun extract(file: File): AssembledPageText =
        PDDocument.load(file).use { document ->
            PdfBoxTextSource.extract(document, 0, MyanmarFixtures.glyphText(context))
        }

    /** The run box on the rendered page after a clockwise /Rotate. */
    private fun turn(run: PdfTextRun, rotation: Int): PdfTextRunBox = when (rotation) {
        90 -> PdfTextRunBox(1f - run.bottom, run.left, 1f - run.top, run.right)
        180 -> PdfTextRunBox(1f - run.right, 1f - run.bottom, 1f - run.left, 1f - run.top)
        270 -> PdfTextRunBox(run.top, 1f - run.right, run.bottom, 1f - run.left)
        else -> PdfTextRunBox(run.left, run.top, run.right, run.bottom)
    }

    private companion object {
        const val EPSILON = 1e-4f
    }
}
