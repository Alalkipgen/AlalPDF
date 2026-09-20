package com.alalkipgen.alalpdf.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Google Drive style text selection over a rendered page.
 *
 * The previous approach stacked transparent [Text] composables on top of the
 * page inside a `SelectionContainer`. That only produced a selection if the
 * platform renderer had supplied positioned text (API 35 only), the invisible
 * text never lined up with the glyphs underneath, and there was no way to
 * adjust a selection once made.
 *
 * Here the word boxes are used directly: long-press picks the word under the
 * finger, dragging extends the range, the selected words are highlighted where
 * they actually are, and an action bar offers copy / search / share.
 */
@Composable
fun SelectionLayer(
    runs: List<PdfTextRun>,
    modifier: Modifier = Modifier,
    onSearchSelection: (String) -> Unit = {},
) {
    if (runs.isEmpty()) return
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    var anchor by remember(runs) { mutableStateOf(-1) }
    var focus by remember(runs) { mutableStateOf(-1) }
    val active = anchor >= 0 && focus >= 0
    val first = if (active) min(anchor, focus) else -1
    val last = if (active) max(anchor, focus) else -1

    BoxWithConstraints(modifier.fillMaxSize()) {
        val boxWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val boxHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        fun indexAt(position: Offset): Int {
            val x = position.x / boxWidth
            val y = position.y / boxHeight
            runs.forEachIndexed { index, run ->
                if (x >= run.left && x <= run.right && y >= run.top && y <= run.bottom) return index
            }
            // Nothing directly under the finger: fall back to the closest word
            // so a long press between two lines still selects something.
            var best = -1
            var bestDistance = Float.MAX_VALUE
            runs.forEachIndexed { index, run ->
                val dx = abs((run.left + run.right) / 2f - x)
                val dy = abs((run.top + run.bottom) / 2f - y)
                val distance = dx + dy * 2f
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            return if (bestDistance < 0.12f) best else -1
        }

        val selectionGestures = Modifier.pointerInput(runs, boxWidth, boxHeight) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val index = indexAt(position)
                    if (index >= 0) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        anchor = index
                        focus = index
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    val index = indexAt(change.position)
                    if (index >= 0 && index != focus) focus = index
                },
            )
        }
        val dismissGestures = if (active) {
            Modifier.pointerInput(active) {
                detectTapGestures(onTap = { anchor = -1; focus = -1 })
            }
        } else {
            Modifier
        }

        Canvas(Modifier.fillMaxSize().then(selectionGestures).then(dismissGestures)) {
            if (!active) return@Canvas
            for (index in first..last) {
                val run = runs[index]
                drawRect(
                    color = HIGHLIGHT,
                    topLeft = Offset(run.left * size.width, run.top * size.height),
                    size = Size(
                        ((run.right - run.left) * size.width).coerceAtLeast(2f),
                        ((run.bottom - run.top) * size.height).coerceAtLeast(2f),
                    ),
                )
            }
            // Drag handles at both ends of the selection.
            val start = runs[first]
            val end = runs[last]
            drawCircle(HANDLE, HANDLE_RADIUS_PX, Offset(start.left * size.width, start.bottom * size.height))
            drawCircle(HANDLE, HANDLE_RADIUS_PX, Offset(end.right * size.width, end.bottom * size.height))
        }

        if (active) {
            val selected = runs.subList(first, last + 1).joinToString(" ") { it.text }.trim()
            val anchorRun = runs[first]
            val toolbarX = (maxWidth * anchorRun.left - 24.dp).coerceIn(0.dp, (maxWidth - 220.dp).coerceAtLeast(0.dp))
            val toolbarY = (maxHeight * anchorRun.top - 52.dp).coerceAtLeast(0.dp)
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

private val HIGHLIGHT = Color(0x553B82F6)
private val HANDLE = Color(0xFF3B82F6)
private const val HANDLE_RADIUS_PX = 12f

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
