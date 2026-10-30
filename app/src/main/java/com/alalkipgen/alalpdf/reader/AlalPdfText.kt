package com.alalkipgen.alalpdf.reader

import android.util.Base64
import com.alalkipgen.alalpdf.common.AlalLinkMetadata
import com.tom_roush.pdfbox.pdmodel.PDDocument

/**
 * Reads the plain page text that PDF Reader stores in its own documents.
 *
 * Text drawn by Android is shaped correctly but its ToUnicode table maps one
 * glyph to one code point, so extracting Burmese from it loses medials and
 * reorders syllables. When the document is ours, the original text is right
 * there in the metadata and is used instead of extraction.
 */
internal data class AlalPdfStoredContent(
    val isAlalPdf: Boolean = false,
    val pageTexts: Map<Int, String> = emptyMap(),
    val pageLinks: Map<Int, List<PdfPageLink>> = emptyMap(),
)

internal object AlalPdfText {

    fun read(pdf: PdfBoxDocument): Map<Int, String> = readContent(pdf).pageTexts

    /** Reads the metadata from the session's shared PDFBox document; never throws. */
    fun readContent(pdf: PdfBoxDocument): AlalPdfStoredContent =
        runCatching { pdf.read(::contentOf) }.getOrDefault(AlalPdfStoredContent())

    private fun contentOf(document: PDDocument): AlalPdfStoredContent {
        val info = document.documentInformation
        if (info.getCustomMetadataValue("AlalPDF-Version") == null) {
            return AlalPdfStoredContent()
        }
        val count = info.getCustomMetadataValue("AlalPDF-PageCount")?.toIntOrNull()
            ?: document.numberOfPages
        val texts = buildMap {
            for (page in 0 until count) {
                val raw = info.getCustomMetadataValue("AlalPDF-Page-$page") ?: continue
                val text = runCatching {
                    String(Base64.decode(raw, Base64.NO_WRAP), Charsets.UTF_8)
                }.getOrNull()
                if (!text.isNullOrBlank()) put(page, text)
            }
        }
        val linkCount = info.getCustomMetadataValue("AlalPDF-LinkCount")
            ?.toIntOrNull()
            ?.coerceIn(0, MAX_STORED_LINKS)
            ?: 0
        val links = buildMap<Int, MutableList<PdfPageLink>> {
            for (index in 0 until linkCount) {
                val raw = info.getCustomMetadataValue("AlalPDF-Link-$index") ?: continue
                val stored = AlalLinkMetadata.decode(raw) ?: continue
                getOrPut(stored.page) { mutableListOf() }.add(
                    PdfPageLink(
                        stored.left,
                        stored.top,
                        stored.right,
                        stored.bottom,
                        stored.url,
                    ),
                )
            }
        }
        return AlalPdfStoredContent(
            isAlalPdf = true,
            pageTexts = texts,
            pageLinks = links,
        )
    }

    private const val MAX_STORED_LINKS = 10_000
}
