package com.alalkipgen.alalpdf.create

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders a locally generated PDF so the user can check it before saving.
 * Pages fill the available width and zooming follows the finger centroid,
 * exactly like the reader.
 */
@Composable
fun PdfFilePreview(file: File, modifier: Modifier = Modifier) {
    val stamp = file.lastModified()
    var pages by remember(file.path, stamp) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var loading by remember(file.path, stamp) { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(file.path, stamp) {
        loading = true
        pages = withContext(Dispatchers.IO) { renderPages(file, 1400) }
        loading = false
    }

    Box(modifier.fillMaxSize()) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            pages.isEmpty() -> Text(
                "Preview is not available for this file.",
                Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.error,
            )
            else -> Box(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pan = event.calculatePan()
                                val limitX = { size.width * (scale - 1f) }
                                val limitY = { size.height * (scale - 1f) }
                                if (event.changes.size >= 2) {
                                    val previous = scale
                                    val next = (previous * event.calculateZoom()).coerceIn(1f, 5f)
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    if (centroid != Offset.Unspecified && next != previous) {
                                        val ratio = next / previous
                                        offsetX = centroid.x - (centroid.x - offsetX) * ratio
                                        offsetY = centroid.y - (centroid.y - offsetY) * ratio
                                    }
                                    scale = next
                                    if (scale > 1f) {
                                        offsetX = (offsetX + pan.x).coerceIn(-limitX(), 0f)
                                        offsetY = (offsetY + pan.y).coerceIn(-limitY(), 0f)
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (scale > 1f) {
                                    val nextX = (offsetX + pan.x).coerceIn(-limitX(), 0f)
                                    val nextY = (offsetY + pan.y).coerceIn(-limitY(), 0f)
                                    val moved = nextX != offsetX || nextY != offsetY
                                    offsetX = nextX
                                    offsetY = nextY
                                    // Only swallow the gesture while the zoomed page can
                                    // still move, so the list keeps scrolling at the edges.
                                    if (moved) event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { tap ->
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                                offsetX = (-tap.x * (scale - 1f)).coerceIn(-size.width * (scale - 1f), 0f)
                                offsetY = (-tap.y * (scale - 1f)).coerceIn(-size.height * (scale - 1f), 0f)
                            }
                        })
                    }
            ) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(pages.size) { index ->
                        Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp, shadowElevation = 2.dp) {
                            Image(
                                bitmap = pages[index].asImageBitmap(),
                                contentDescription = "Preview page " + (index + 1),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun renderPages(file: File, width: Int): List<Bitmap> {
    if (!file.exists()) return emptyList()
    var descriptor: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    return try {
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(descriptor)
        val limit = minOf(renderer.pageCount, 40)
        (0 until limit).map { index ->
            val page = renderer.openPage(index)
            val height = (width.toFloat() / page.width * page.height).toInt().coerceIn(1, 4000)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap
        }
    } catch (_: Exception) {
        emptyList()
    } finally {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
    }
}
