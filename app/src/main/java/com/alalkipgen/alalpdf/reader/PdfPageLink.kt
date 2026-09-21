package com.alalkipgen.alalpdf.reader

import android.content.ContentResolver
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import kotlin.math.roundToInt

/** Clickable web annotation bounds normalized to the rendered page. */
data class PdfPageLink(val left: Float, val top: Float, val right: Float, val bottom: Float, val url: String)

/**
 * Compatibility path for external PDFs and Alal PDFs made before link metadata
 * was introduced. The PDFBox document exists only for this call.
 */
internal object PdfLinkExtractor {
    fun extractPage(
        resolver: ContentResolver,
        uri: Uri,
        pageIndex: Int,
        password: String? = null,
    ): List<PdfPageLink> {
        if (pageIndex < 0) return emptyList()
        val result = mutableListOf<PdfPageLink>()
        resolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input, password.orEmpty()).use { document ->
                if (pageIndex >= document.numberOfPages) return@use
                val page = document.getPage(pageIndex)
                val box = page.cropBox ?: page.mediaBox
                val width = box.width.coerceAtLeast(1f)
                val height = box.height.coerceAtLeast(1f)
                page.annotations.filterIsInstance<PDAnnotationLink>().forEach { annotation ->
                    val target = (annotation.action as? PDActionURI)?.uri
                        ?.trim()
                        ?.takeIf(::isSafeWebUrl)
                        ?: return@forEach
                    val rect = annotation.rectangle ?: return@forEach
                    val x1 = ((rect.lowerLeftX - box.lowerLeftX) / width).coerceIn(0f, 1f)
                    val x2 = ((rect.upperRightX - box.lowerLeftX) / width).coerceIn(0f, 1f)
                    val y1 = (1f - (rect.upperRightY - box.lowerLeftY) / height).coerceIn(0f, 1f)
                    val y2 = (1f - (rect.lowerLeftY - box.lowerLeftY) / height).coerceIn(0f, 1f)
                    val transformed = rotateRect(x1, y1, x2, y2, page.rotation, target)
                    if (transformed.right > transformed.left && transformed.bottom > transformed.top) {
                        result += transformed
                    }
                }
            }
        }
        return result.distinctBy { link ->
            listOf(
                link.url,
                (link.left * 10_000).roundToInt(),
                (link.top * 10_000).roundToInt(),
                (link.right * 10_000).roundToInt(),
                (link.bottom * 10_000).roundToInt(),
            )
        }
    }

    private fun rotateRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotation: Int,
        url: String,
    ): PdfPageLink {
        val points = listOf(left to top, right to top, left to bottom, right to bottom)
            .map { (x, y) ->
                when (((rotation % 360) + 360) % 360) {
                    90 -> (1f - y) to x
                    180 -> (1f - x) to (1f - y)
                    270 -> y to (1f - x)
                    else -> x to y
                }
            }
        return PdfPageLink(
            points.minOf { it.first },
            points.minOf { it.second },
            points.maxOf { it.first },
            points.maxOf { it.second },
            url,
        )
    }

    private fun isSafeWebUrl(value: String): Boolean {
        val scheme = runCatching { Uri.parse(value).scheme?.lowercase() }.getOrNull()
        return scheme == "http" || scheme == "https"
    }
}
