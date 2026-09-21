package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.alalkipgen.alalpdf.common.AlalLinkMetadata
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
internal data class AlalPdfStoredContent(
    val isAlalPdf: Boolean = false,
    val pageTexts: Map<Int, String> = emptyMap(),
    val pageLinks: Map<Int, List<PdfPageLink>> = emptyMap(),
)

internal object AlalPdfText {

    fun read(context: Context, uri: Uri): Map<Int, String> = readContent(context, uri).pageTexts

    fun readContent(context: Context, uri: Uri): AlalPdfStoredContent {
        PDFBoxResourceLoader.init(context)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { document ->
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
                    AlalPdfStoredContent(
                        isAlalPdf = true,
                        pageTexts = texts,
                        pageLinks = links,
                    )
                }
            } ?: AlalPdfStoredContent()
        }.getOrDefault(AlalPdfStoredContent())
    }

    private const val MAX_STORED_LINKS = 10_000
}
