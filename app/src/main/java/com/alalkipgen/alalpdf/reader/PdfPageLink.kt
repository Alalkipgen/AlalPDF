package com.alalkipgen.alalpdf.reader

import android.content.ContentResolver
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink

data class PdfPageLink(val left: Float, val top: Float, val right: Float, val bottom: Float, val url: String)

internal object PdfLinkExtractor {
    fun extract(resolver: ContentResolver, uri: Uri): Map<Int, List<PdfPageLink>> {
        val result = mutableMapOf<Int, MutableList<PdfPageLink>>()
        resolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { document ->
                document.pages.forEachIndexed { index, page ->
                    val box = page.cropBox ?: page.mediaBox
                    val width = box.width.coerceAtLeast(1f); val height = box.height.coerceAtLeast(1f)
                    page.annotations.filterIsInstance<PDAnnotationLink>().forEach { annotation ->
                        val target = (annotation.action as? PDActionURI)?.uri?.trim()?.takeIf(::isSafeWebUrl) ?: return@forEach
                        val rect = annotation.rectangle ?: return@forEach
                        val x1 = ((rect.lowerLeftX - box.lowerLeftX) / width).coerceIn(0f, 1f)
                        val x2 = ((rect.upperRightX - box.lowerLeftX) / width).coerceIn(0f, 1f)
                        val y1 = (1f - (rect.upperRightY - box.lowerLeftY) / height).coerceIn(0f, 1f)
                        val y2 = (1f - (rect.lowerLeftY - box.lowerLeftY) / height).coerceIn(0f, 1f)
                        val transformed = rotateRect(x1, y1, x2, y2, page.rotation)
                        if (transformed.right > transformed.left && transformed.bottom > transformed.top)
                            result.getOrPut(index) { mutableListOf() } += transformed.copy(url = target)
                    }
                }
            }
        } ?: return emptyMap()
        return result
    }

    private fun rotateRect(left: Float, top: Float, right: Float, bottom: Float, rotation: Int): PdfPageLink {
        val points = listOf(left to top, right to top, left to bottom, right to bottom).map { (x, y) ->
            when (((rotation % 360) + 360) % 360) {
                90 -> (1f - y) to x
                180 -> (1f - x) to (1f - y)
                270 -> y to (1f - x)
                else -> x to y
            }
        }
        return PdfPageLink(points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second }, "")
    }

    private fun isSafeWebUrl(value: String): Boolean {
        val scheme = runCatching { Uri.parse(value).scheme?.lowercase() }.getOrNull()
        return scheme == "http" || scheme == "https"
    }
}
