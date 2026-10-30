package com.alalkipgen.alalpdf.reader

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals

/** The Burmese PDFs in `src/test/resources/myanmar` (see the README there). */
internal object MyanmarFixtures {
    const val CHROME = "chrome_noto"
    const val LIBREOFFICE = "libreoffice_noto"

    private val SHA256 = mapOf(
        CHROME to "ee8d446b8937e519c7d1fbc9edce242ecaba3171484d4bb8ca641aaa8812de86",
        LIBREOFFICE to "8c837b9c40a24b1ca9ddb7a5776ead3edf8428d4a7feb733a084079a713ae37d",
    )

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
