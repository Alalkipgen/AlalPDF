package com.alalkipgen.alalpdf.reader

internal data class VisiblePageBounds(
    val index: Int,
    val offset: Int,
    val size: Int,
)

/**
 * Picks the page occupying the largest part of the viewport.
 *
 * firstVisibleItemIndex is not enough for tall PDF pages: the first item can
 * be a thin strip while the next page (and its hyperlink) fills the screen.
 */
internal fun mostVisiblePage(
    viewportStart: Int,
    viewportEnd: Int,
    items: List<VisiblePageBounds>,
    fallback: Int,
): Int = items.maxByOrNull { item ->
    val visibleStart = maxOf(viewportStart, item.offset)
    val visibleEnd = minOf(viewportEnd, item.offset + item.size)
    (visibleEnd - visibleStart).coerceAtLeast(0)
}?.index ?: fallback