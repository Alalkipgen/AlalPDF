package com.alalkipgen.alalpdf.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Google Drive style text selection over a rendered page.
 *
 * Three things were wrong with the first version and are fixed here:
 *
 * 1. **Granularity.** Selection was built from PDFBox word boxes, so a whole
 *    word was the smallest unit. The runs now come from PDFium with one box
 *    per character, so any range of characters can be selected.
 * 2. **Handles.** The blue circles were painted into a [Canvas] and had no
 *    touch target at all, so a selection could never be adjusted after the
 *    first long press. They are now real composables with a 48dp touch area
 *    that can be dragged independently, and the selection survives lifting
 *    the finger.
 * 3. **Burmese.** Dragging now snaps to grapheme cluster boundaries, so a
 *    Myanmar syllable is never cut in half.
 */
@Composable
fun SelectionLayer(
    runs: List<PdfTextRun>,
    modifier: Modifier = Modifier,
    onSearchSelection: (String) -> Unit = {},
    onEdgeDrag: (Float) -> Unit = {},
    onTap: (Offset) -> Unit = {},
) {
    if (runs.isEmpty()) return
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    val charMode = runs.first().character
    val joiner = if (charMode) "" else " "
    val pageText = remember(runs) { runs.joinToString(joiner) { it.text } }
    // Index of the first character of every run inside [pageText].
    val runStarts = remember(runs) {
        var cursor = 0
        IntArray(runs.size) { index ->
            val start = cursor
            cursor += runs[index].text.length + joiner.length
            start
        }
    }

    // Everything expensive is computed once per page. The first version did a
    // linear scan over every character and built a BreakIterator on every drag
    // event, which is what made dragging feel like it was stuttering.
    val lines = remember(runs) { buildLines(runs) }
    val clusterStarts = remember(runs, pageText) { clusterStarts(pageText, charMode) }

    var anchor by remember(runs) { mutableStateOf(-1) }
    var focus by remember(runs) { mutableStateOf(-1) }
    var dragging by remember(runs) { mutableStateOf(DragTarget.NONE) }
    val active = anchor >= 0 && focus >= 0
    val first = if (active) min(anchor, focus) else -1
    val last = if (active) max(anchor, focus) else -1

    BoxWithConstraints(modifier.fillMaxSize()) {
        val boxWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val boxHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val viewWidth = maxWidth
        val viewHeight = maxHeight

        fun indexAt(position: Offset): Int {
            val x = position.x / boxWidth
            val y = position.y / boxHeight
            if (lines.isEmpty()) return -1
            // Pick the closest line first, then the closest character inside
            // it. Both steps are binary searches, so dragging stays smooth on
            // pages with thousands of characters.
            var lineIndex = lines.binarySearch { line ->
                when {
                    y < line.top -> 1
                    y > line.bottom -> -1
                    else -> 0
                }
            }
            if (lineIndex < 0) {
                val insertion = -lineIndex - 1
                val before = (insertion - 1).coerceAtLeast(0)
                val after = insertion.coerceAtMost(lines.lastIndex)
                lineIndex = if (kotlin.math.abs(y - lines[before].bottom) <=
                    kotlin.math.abs(lines[after].top - y)
                ) {
                    before
                } else {
                    after
                }
            }
            val line = lines[lineIndex]
            var best = line.firstIndex
            var bestDistance = Float.MAX_VALUE
            for (index in line.firstIndex..line.lastIndex) {
                val run = runs[index]
                if (run.right <= run.left) continue
                val distance = when {
                    x < run.left -> run.left - x
                    x > run.right -> x - run.right
                    else -> 0f
                }
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                    if (distance == 0f) break
                }
            }
            return best
        }

        /** Keeps a Myanmar syllable or an emoji in one piece while dragging. */
        fun snapToCluster(index: Int, towardsEnd: Boolean): Int {
            if (!charMode || index < 0 || index >= runs.size || clusterStarts.isEmpty()) return index
            val offset = runStarts[index]
            var position = clusterStarts.binarySearch(offset)
            if (position < 0) position = (-position - 2).coerceAtLeast(0)
            val boundary = if (towardsEnd) {
                val next = position + 1
                if (next < clusterStarts.size) clusterStarts[next] - 1 else pageText.length - 1
            } else {
                clusterStarts[position]
            }
            val target = runIndexAt(runStarts, boundary)
            return if (target < 0) index else target
        }

        fun selectWordAt(index: Int) {
            if (index < 0) return
            if (!charMode) {
                anchor = index
                focus = index
                return
            }
            val iterator = BreakIterator.getWordInstance()
            iterator.setText(pageText)
            val offset = runStarts[index]
            val start = iterator.preceding(offset + 1).takeIf { it != BreakIterator.DONE } ?: offset
            val end = iterator.following(offset).takeIf { it != BreakIterator.DONE } ?: pageText.length
            anchor = runIndexAt(runStarts, start).coerceAtLeast(0)
            focus = runIndexAt(runStarts, (end - 1).coerceAtLeast(start)).coerceIn(anchor, runs.size - 1)
        }

        /** Scrolls the page when a handle is dragged past the top or bottom edge. */
        fun autoScroll(position: Offset) {
            val margin = boxHeight * EDGE_FRACTION
            when {
                position.y < margin -> onEdgeDrag(-(margin - position.y).coerceAtMost(margin))
                position.y > boxHeight - margin ->
                    onEdgeDrag((position.y - (boxHeight - margin)).coerceAtMost(margin))
            }
        }

        val selectionGestures = Modifier.pointerInput(runs, boxWidth, boxHeight) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val index = indexAt(position)
                    if (index >= 0) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectWordAt(index)
                        dragging = DragTarget.END
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    val index = indexAt(change.position)
                    if (index >= 0 && index != focus) focus = snapToCluster(index, true)
                    autoScroll(change.position)
                },
                onDragEnd = { dragging = DragTarget.NONE },
                onDragCancel = { dragging = DragTarget.NONE },
            )
        }
        val tapGestures = Modifier.pointerInput(active) {
            detectTapGestures(
                onTap = { position ->
                    if (active) {
                        // First tap only dismisses the selection.
                        anchor = -1
                        focus = -1
                    } else {
                        // Nothing is selected, so the tap belongs to the page:
                        // links and the top bar toggle need to see it.
                        onTap(Offset(position.x / boxWidth, position.y / boxHeight))
                    }
                },
            )
        }

        Canvas(Modifier.fillMaxSize().then(selectionGestures).then(tapGestures)) {
            if (!active) return@Canvas
            // Merge the character boxes of each line into one rectangle so the
            // highlight is continuous instead of a row of separate blocks.
            var lineStart = first
            while (lineStart <= last) {
                var lineEnd = lineStart
                while (lineEnd + 1 <= last && sameLine(runs[lineStart], runs[lineEnd + 1])) lineEnd++
                var left = Float.MAX_VALUE
                var right = Float.MIN_VALUE
                var top = Float.MAX_VALUE
                var bottom = Float.MIN_VALUE
                for (index in lineStart..lineEnd) {
                    val run = runs[index]
                    if (run.right <= run.left && run.bottom <= run.top) continue
                    left = min(left, run.left)
                    right = max(right, run.right)
                    top = min(top, run.top)
                    bottom = max(bottom, run.bottom)
                }
                if (right > left && bottom > top) {
                    drawRect(
                        color = HIGHLIGHT,
                        topLeft = Offset(left * size.width, top * size.height),
                        size = Size((right - left) * size.width, (bottom - top) * size.height),
                    )
                }
                lineStart = lineEnd + 1
            }
        }

        if (active) {
            val startRun = runs[first]
            val endRun = runs[last]
            val startX = viewWidth * startRun.left
            val startY = viewHeight * startRun.bottom
            val endX = viewWidth * endRun.right
            val endY = viewHeight * endRun.bottom

            SelectionHandle(
                x = startX,
                y = startY,
                leading = true,
                onDrag = { pagePosition ->
                    dragging = DragTarget.START
                    val index = indexAt(pagePosition)
                    if (index >= 0) anchor = snapToCluster(index, false).coerceAtMost(last)
                    autoScroll(pagePosition)
                },
                onDragEnd = { dragging = DragTarget.NONE },
            )
            SelectionHandle(
                x = endX,
                y = endY,
                leading = false,
                onDrag = { pagePosition ->
                    dragging = DragTarget.END
                    val index = indexAt(pagePosition)
                    if (index >= 0) focus = snapToCluster(index, true).coerceAtLeast(first)
                    autoScroll(pagePosition)
                },
                onDragEnd = { dragging = DragTarget.NONE },
            )

            if (dragging == DragTarget.NONE) {
                val selected = buildSelection(runs, first, last, joiner)
                val toolbarX = (startX - 24.dp).coerceIn(0.dp, (viewWidth - 220.dp).coerceAtLeast(0.dp))
                val toolbarY = (viewHeight * startRun.top - 52.dp).coerceAtLeast(0.dp)
                Surface(
                    Modifier.offset(x = toolbarX, y = toolbarY),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 6.dp,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = {
                            context.copyToClipboard(selected)
                            anchor = -1
                            focus = -1
                        }) { Text("Copy", color = MaterialTheme.colorScheme.inverseOnSurface) }
                        TextButton(onClick = {
                            anchor = 0
                            focus = runs.size - 1
                        }) { Text("Select all", color = MaterialTheme.colorScheme.inverseOnSurface) }
                        TextButton(onClick = {
                            onSearchSelection(selected)
                            anchor = -1
                            focus = -1
                        }) { Text("Search", color = MaterialTheme.colorScheme.inverseOnSurface) }
                        TextButton(onClick = {
                            context.shareText(selected)
                            anchor = -1
                            focus = -1
                        }) { Text("Share", color = MaterialTheme.colorScheme.inverseOnSurface) }
                    }
                }
            }
        }
    }
}

private enum class DragTarget { NONE, START, END }

/**
 * A draggable selection handle.
 *
 * The visible teardrop is small, but the composable itself is 48dp so it can
 * actually be grabbed with a finger, which is the whole reason adjusting a
 * selection used to be impossible.
 */
@Composable
private fun SelectionHandle(
    x: androidx.compose.ui.unit.Dp,
    y: androidx.compose.ui.unit.Dp,
    leading: Boolean,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val originX = (if (leading) x - HANDLE_TOUCH else x).coerceAtLeast(0.dp)
    val originY = (y - HANDLE_TOUCH / 4f).coerceAtLeast(0.dp)
    Box(
        Modifier
            .offset(x = originX, y = originY)
            .size(HANDLE_TOUCH)
            .pointerInput(leading) {
                // The finger grabs the handle somewhere, and the selection has
                // to follow the tip of the handle from there on, otherwise the
                // text jumps as soon as the drag starts.
                var pointer = Offset.Zero
                detectDragGestures(
                    onDragStart = { start ->
                        pointer = Offset(originX.toPx(), originY.toPx()) + start
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        pointer += amount
                        val tipX = if (leading) pointer.x + size.width / 2f else pointer.x - size.width / 2f
                        onDrag(Offset(tipX, pointer.y - size.height / 4f))
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 4f
            val center = if (leading) {
                Offset(size.width - radius, radius * 1.2f)
            } else {
                Offset(radius, radius * 1.2f)
            }
            drawCircle(HANDLE, radius, center)
        }
    }
}

/** A row of characters that share a baseline, kept sorted for binary search. */
private class TextLine(val top: Float, val bottom: Float, val firstIndex: Int, val lastIndex: Int)

private fun buildLines(runs: List<PdfTextRun>): List<TextLine> {
    val lines = ArrayList<TextLine>()
    var first = -1
    var top = 0f
    var bottom = 0f
    runs.forEachIndexed { index, run ->
        if (run.right <= run.left && run.bottom <= run.top) return@forEachIndexed
        if (first < 0) {
            first = index
            top = run.top
            bottom = run.bottom
            return@forEachIndexed
        }
        val center = (run.top + run.bottom) / 2f
        val lineCenter = (top + bottom) / 2f
        val tolerance = maxOf(bottom - top, run.bottom - run.top) * 0.6f
        if (abs(center - lineCenter) <= maxOf(tolerance, 0.004f)) {
            top = min(top, run.top)
            bottom = max(bottom, run.bottom)
        } else {
            lines.add(TextLine(top, bottom, first, index - 1))
            first = index
            top = run.top
            bottom = run.bottom
        }
    }
    if (first >= 0) lines.add(TextLine(top, bottom, first, runs.lastIndex))
    return lines.sortedBy { it.top }
}

/** Start offsets of every grapheme cluster in the page text. */
private fun clusterStarts(pageText: String, charMode: Boolean): IntArray {
    if (!charMode || pageText.isEmpty()) return IntArray(0)
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(pageText)
    val starts = ArrayList<Int>(pageText.length)
    var offset = iterator.first()
    while (offset != BreakIterator.DONE) {
        starts.add(offset)
        offset = iterator.next()
    }
    return starts.toIntArray()
}

/** Run that contains [offset] in the page text, using binary search. */
private fun runIndexAt(runStarts: IntArray, offset: Int): Int {
    if (runStarts.isEmpty()) return -1
    var low = 0
    var high = runStarts.size - 1
    var result = 0
    while (low <= high) {
        val middle = (low + high) / 2
        if (runStarts[middle] <= offset) {
            result = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return result
}

private fun sameLine(a: PdfTextRun, b: PdfTextRun): Boolean {
    val aCenter = (a.top + a.bottom) / 2f
    val bCenter = (b.top + b.bottom) / 2f
    val tolerance = max(a.bottom - a.top, b.bottom - b.top) * 0.6f
    return abs(aCenter - bCenter) <= max(tolerance, 0.004f)
}

private fun buildSelection(runs: List<PdfTextRun>, first: Int, last: Int, joiner: String): String =
    runs.subList(first, last + 1).joinToString(joiner) { it.text }.trim()

private val HIGHLIGHT = Color(0x553B82F6)
private val HANDLE = Color(0xFF3B82F6)
private val HANDLE_TOUCH = 48.dp
private const val EDGE_FRACTION = 0.12f

private fun Context.copyToClipboard(text: String) {
    if (text.isBlank()) return
    getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("PDF", text))
}

private fun Context.shareText(text: String) {
    if (text.isBlank()) return
    runCatching {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "Share text",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
