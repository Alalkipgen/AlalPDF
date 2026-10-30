package com.alalkipgen.alalpdf.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks a real Burmese article against its expected text, when a local copy
 * is present. The article is third-party content, so it is not in the
 * repository: put the PDF and `golden/pageN.txt` (one file per page) in
 * `local-fixtures/myanmar/` (gitignored) to run this test. Zawgyi PDFs, for
 * the same reason and because of their font, go in
 * `local-fixtures/myanmar/zawgyi/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MyanmarRealFixtureTest {
    @Test
    fun realArticleMatchesTheGoldenText() {
        val directory = listOf(File("local-fixtures/myanmar"), File("../local-fixtures/myanmar"))
            .firstOrNull { File(it, PDF).isFile }
        assumeTrue("local-fixtures/myanmar/$PDF is not present", directory != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        PdfBoxDocument.initResources(context)

        var errors = 0
        var length = 0
        val glyphs = MyanmarFixtures.glyphText(context)
        PDDocument.load(File(directory, PDF), MemoryUsageSetting.setupTempFileOnly()).use { document ->
            for (page in 0 until document.numberOfPages) {
                val started = System.nanoTime()
                val text = PdfBoxTextSource.extract(document, page, glyphs).text
                val millis = (System.nanoTime() - started) / 1_000_000
                val expected = collapse(File(directory, "golden/page${page + 1}.txt").readText())
                val actual = collapse(text)
                val distance = levenshtein(expected, actual)
                errors += distance
                length += expected.length
                val rate = distance.toDouble() / expected.length
                println(
                    String.format(
                        Locale.ROOT,
                        "page %d: CER %.3f %% (%d / %d), %d ms",
                        page + 1, rate * 100, distance, expected.length, millis,
                    ),
                )
                assertTrue("page ${page + 1} CER $rate", rate <= 0.01)
                assertEquals(occurrences(expected, "မြန်မာ"), occurrences(actual, "မြန်မာ"))
            }
        }
        val total = errors.toDouble() / length
        println(String.format(Locale.ROOT, "total: CER %.3f %% (%d / %d)", total * 100, errors, length))
        assertTrue("total CER $total", total <= 0.005)
    }

    /**
     * Each Zawgyi PDF has a font flagged Zawgyi, its text is the same
     * whichever page is read first, and it is the text as drawn, read with
     * every font flagged Zawgyi, but for text in a font that has no Zawgyi
     * code point (a bold title of a few glyphs) and is read as Unicode.
     */
    @Test
    fun zawgyiPdfsKeepTheirTextAsDrawn() {
        val pdfs = listOf(File("local-fixtures/myanmar/zawgyi"), File("../local-fixtures/myanmar/zawgyi"))
            .firstOrNull { it.isDirectory }
            ?.listFiles { file -> file.extension.equals("pdf", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        assumeTrue("no PDF in local-fixtures/myanmar/zawgyi", pdfs.isNotEmpty())
        val context = ApplicationProvider.getApplicationContext<Context>()
        PdfBoxDocument.initResources(context)

        var errors = 0
        var length = 0
        for (pdf in pdfs) {
            PDDocument.load(pdf, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                val pages = 0 until document.numberOfPages
                val glyphs = MyanmarFixtures.glyphText(context)
                val texts = pages.map { PdfBoxTextSource.extract(document, it, glyphs).text }
                val counts = glyphs.counts()
                println("${pdf.name}: ${counts.zawgyiFonts} Zawgyi font(s), ${counts.logicalRuns} logical-order run(s)")
                assertTrue("${pdf.name}: no Zawgyi font", counts.zawgyiFonts > 0)

                val lastPageFirst = MyanmarFixtures.glyphText(context)
                for (page in pages.reversed()) {
                    val text = PdfBoxTextSource.extract(document, page, lastPageFirst).text
                    assertEquals("${pdf.name} page ${page + 1}, read last page first", texts[page], text)
                }
                for (font in document.document.getObjectsByType(COSName.FONT)) {
                    (font.`object` as? COSDictionary)?.setName(COSName.BASE_FONT, "Zawgyi")
                }
                val allZawgyi = MyanmarFixtures.glyphText(context)
                for (page in pages) {
                    val drawn = collapse(PdfBoxTextSource.extract(document, page, allZawgyi).text)
                    val distance = levenshtein(drawn, collapse(texts[page]))
                    errors += distance
                    length += drawn.length
                    println(
                        String.format(
                            Locale.ROOT,
                            "%s page %d: %d / %d from the text as drawn",
                            pdf.name, page + 1, distance, drawn.length,
                        ),
                    )
                }
            }
        }
        assertTrue("$errors / $length from the text as drawn", errors <= length / 1000)
    }

    /** Whitespace runs count as one space, so line breaks do not count. */
    private fun collapse(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    private fun occurrences(text: String, needle: String): Int =
        text.windowed(needle.length).count { it == needle }

    private fun levenshtein(a: String, b: String): Int {
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        var previous = IntArray(y.size + 1) { it }
        var current = IntArray(y.size + 1)
        for (i in 1..x.size) {
            current[0] = i
            for (j in 1..y.size) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[y.size]
    }

    private companion object {
        const val PDF = "myanmar_refugees_india.pdf"
    }
}
