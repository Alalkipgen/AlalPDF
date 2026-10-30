package com.alalkipgen.alalpdf.reader

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import java.io.File

/** Small PDFs generated with PDFBox for reader tests. */
internal object TestPdfs {
    data class Link(val page: Int, val rect: PDRectangle, val url: String)

    fun write(
        target: File,
        pageCount: Int = 1,
        rotations: List<Int> = emptyList(),
        links: List<Link> = emptyList(),
        metadata: Map<String, String> = emptyMap(),
        userPassword: String? = null,
        /** Helvetica text drawn on the page with the same index. */
        texts: List<String> = emptyList(),
    ): File {
        PDDocument().use { document ->
            repeat(pageCount) { index ->
                val page = PDPage(PDRectangle(PAGE_WIDTH, PAGE_HEIGHT))
                page.rotation = rotations.getOrElse(index) { 0 }
                val annotations = links.filter { it.page == index }.map { link ->
                    PDAnnotationLink().apply {
                        rectangle = link.rect
                        action = PDActionURI().apply { uri = link.url }
                    }
                }
                if (annotations.isNotEmpty()) page.annotations = annotations
                document.addPage(page)
                texts.getOrNull(index)?.let { text ->
                    PDPageContentStream(document, page).use { content ->
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, 12f)
                        content.newLineAtOffset(10f, 50f)
                        content.showText(text)
                        content.endText()
                    }
                }
            }
            metadata.forEach { (key, value) -> document.documentInformation.setCustomMetadataValue(key, value) }
            if (userPassword != null) {
                document.protect(
                    StandardProtectionPolicy("owner-$userPassword", userPassword, AccessPermission())
                        .apply { encryptionKeyLength = 128 },
                )
            }
            document.save(target)
        }
        return target
    }

    const val PAGE_WIDTH = 200f
    const val PAGE_HEIGHT = 100f
}
