package com.alalkipgen.alalpdf.create

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders a locally generated PDF so the user can check it before saving.
 *
 * This mirrors the reader exactly: pages stream in one by one into a snapshot
 * state map (so only the finished page recomposes), placeholders use the real
 * page aspect ratio, and zoom grows the content width instead of a graphics
 * layer so panning and scrolling stay smooth.
 */
@Composable
fun PdfFilePreview(file: File, modifier: Modifier = Modifier) {
    val stamp = file.lastModified()
    val pages = remember(file.path, stamp) { mutableStateMapOf<Int, Bitmap>() }
    var pageCount by remember(file.path, stamp) { mutableIntStateOf(0) }
    var aspectRatio by remember(file.path, stamp) { mutableFloatStateOf(1.414f) }
    var failed by remember(file.path, stamp) { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()
    val horizontalScroll = rememberScrollState()

    LaunchedEffect(file.path, stamp) {
        failed = false
        withContext(Dispatchers.IO) {
            var descriptor: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(descriptor)
                val limit = minOf(renderer.pageCount, 40)
                pageCount = limit
                val width = 1400
                for (index in 0 until limit) {
                    val page = renderer.openPage(index)
                    val height = (width.toFloat() / page.width * page.height).toInt().coerceIn(1, 4000)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    if (index == 0) aspectRatio = height.toFloat() / width
                    pages[index] = bitmap
                }
            } catch (_: Exception) {
                failed = true
            } finally {
                runCatching { renderer?.close() }
                runCatching { descriptor?.close() }
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        when {
            failed && pages.isEmpty() -> Text(
                "Preview is not available for this file.",
                Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.error,
            )
            pageCount == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.size >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) {
                                        val previous = scale
                                        val next = (previous * zoom).coerceIn(1f, 6f)
                                        val ratio = next / previous
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        scale = next
                                        if (ratio != 1f && centroid != Offset.Unspecified) {
                                            horizontalScroll.dispatchRawDelta(
                                                (horizontalScroll.value + centroid.x) * (ratio - 1f)
                                            )
                                            listState.dispatchRawDelta(
                                                (listState.firstVisibleItemScrollOffset + centroid.y) * (ratio - 1f)
                                            )
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { tap ->
                            val previous = scale
                            val next = if (previous > 1f) 1f else 2.5f
                            val ratio = next / previous
                            scale = next
                            horizontalScroll.dispatchRawDelta((horizontalScroll.value + tap.x) * (ratio - 1f))
                            listState.dispatchRawDelta(
                                (listState.firstVisibleItemScrollOffset + tap.y) * (ratio - 1f)
                            )
                        })
                    }
            ) {
                val pageWidth = maxWidth * scale
                val pageRatio = 1f / aspectRatio.coerceAtLeast(0.2f)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxHeight()
                        .horizontalScroll(horizontalScroll)
                        .width(pageWidth),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(pageCount, key = { index -> index }) { index ->
                        val bitmap = pages[index]
                        Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp, shadowElevation = 2.dp) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Preview page " + (index + 1),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.FillWidth,
                                )
                            } else {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(pageRatio),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        (index + 1).toString(),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
