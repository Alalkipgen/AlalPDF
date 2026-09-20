package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument

/**
 * Reads the plain page text that Alal PDF stores in its own documents.
 *
 * Text drawn by Android is shaped correctly but its ToUnicode table maps one
 * glyph to one code point, so extracting Burmese from it loses medials and
 * reorders syllables. When the document is ours, the original text is right
 * there in the metadata and is used instead of extraction.
 */
internal object AlalPdfText {

    fun read(context: Context, uri: Uri): Map<Int, String> {
        PDFBoxResourceLoader.init(context)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { document ->
                    val info = document.documentInformation
                    if (info.getCustomMetadataValue("AlalPDF-Version") == null) return emptyMap()
                    val count = info.getCustomMetadataValue("AlalPDF-PageCount")?.toIntOrNull()
                        ?: document.numberOfPages
                    buildMap {
                        for (page in 0 until count) {
                            val raw = info.getCustomMetadataValue("AlalPDF-Page-$page") ?: continue
                            val text = runCatching {
                                String(Base64.decode(raw, Base64.NO_WRAP), Charsets.UTF_8)
                            }.getOrNull()
                            if (!text.isNullOrBlank()) put(page, text)
                        }
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyMap())
    }
}
