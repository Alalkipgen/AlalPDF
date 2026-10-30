package com.alalkipgen.alalpdf.reader

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals

/** The Burmese PDFs in `src/test/resources/myanmar` (see the README there). */
internal object MyanmarFixtures {
    const val CHROME = "chrome_noto"
    const val LIBREOFFICE = "libreoffice_noto"

    /** Word-style: broken ToUnicode, visual glyph order, no /ActualText; Noto Sans Myanmar 2.001. */
    const val WORD_2001 = "word_noto2001"

    /** The same with Noto Sans Myanmar 2.107. */
    const val WORD_2107 = "word_noto2107"

    /** Visual glyph order and no /ActualText, but a correct ToUnicode; Noto Sans Myanmar 2.001. */
    const val VISUAL = "visual_noto2001"

    private val SHA256 = mapOf(
        CHROME to "ee8d446b8937e519c7d1fbc9edce242ecaba3171484d4bb8ca641aaa8812de86",
        LIBREOFFICE to "8c837b9c40a24b1ca9ddb7a5776ead3edf8428d4a7feb733a084079a713ae37d",
        WORD_2001 to "1399ac3a9e43a6503c556044e2b400f9f2680e6c8438a7d67bcd8120a06d4cf0",
        WORD_2107 to "a3efb0dd52f0517537f9f1bdb1c713136b8679224137e3c5a1a46f454e535469",
        VISUAL to "60425aac2d478a32c352f7b1dc7f1b9e36e7e07aae25a3b9ff908feed8313fda",
    )

    /** Glyph text as the reader reads it, with the glyph map from the app's assets. */
    fun glyphText(context: Context): MyanmarGlyphText =
        MyanmarGlyphText(table = { MyanmarGlyphMap.bundled(context.assets) })

    /** Decodes a fixture into [directory], checking that it is byte for byte the PDF the producer wrote. */
    fun pdf(name: String, directory: File): File {
        val bytes = Base64.getMimeDecoder().decode(resource("$name.pdf.b64").readText())
        assertEquals(SHA256.getValue(name), sha256(bytes))
        return File(directory, "$name.pdf").apply { writeBytes(bytes) }
    }

    /** The text the PDF was made from, one trimmed line per paragraph. */
    fun expectedLines(name: String): List<String> =
        resource("expected/$name.txt").readText().lines().map(String::trim).filter(String::isNotEmpty)

    private fun resource(path: String): File {
        val url = requireNotNull(javaClass.classLoader?.getResource("myanmar/$path")) { "missing $path" }
        return File(url.toURI())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
