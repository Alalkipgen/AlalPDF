package com.alalkipgen.alalpdf.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * `local-fixtures/myanmar/` (gitignored) to run this test.
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
        PDDocument.load(File(directory, PDF), MemoryUsageSetting.setupTempFileOnly()).use { document ->
            for (page in 0 until document.numberOfPages) {
                val started = System.nanoTime()
                val text = PdfBoxTextSource.extract(document, page).text
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
