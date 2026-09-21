package com.alalkipgen.alalpdf.common

import java.util.Base64

data class AlalStoredLink(
    val page: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val url: String,
)

/**
 * Compact, version-independent storage for links created by Alal PDF.
 * Coordinates are normalized to the top-left 0..1 page space.
 */
object AlalLinkMetadata {
    fun encode(link: AlalStoredLink): String {
        val raw = listOf(
            link.page,
            link.left,
            link.top,
            link.right,
            link.bottom,
            link.url,
        ).joinToString("\t")
        return Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    fun decode(value: String): AlalStoredLink? = runCatching {
        val raw = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        val fields = raw.split('\t', limit = 6)
        require(fields.size == 6)
        AlalStoredLink(
            page = fields[0].toInt(),
            left = fields[1].toFloat().coerceIn(0f, 1f),
            top = fields[2].toFloat().coerceIn(0f, 1f),
            right = fields[3].toFloat().coerceIn(0f, 1f),
            bottom = fields[4].toFloat().coerceIn(0f, 1f),
            url = fields[5],
        ).takeIf {
            it.page >= 0 &&
                it.left < it.right &&
                it.top < it.bottom &&
                (it.url.startsWith("https://", true) || it.url.startsWith("http://", true))
        }
    }.getOrNull()
}