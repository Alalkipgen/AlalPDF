package com.alalkipgen.alalpdf.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun PdfReaderScreen(state: PdfReaderUiState, initialPage: Int = 0, nightMode: Boolean = false, onNightModeChange: (Boolean) -> Unit, onShare: () -> Unit, onPageSelected: (Int) -> Unit, onRender: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceAtLeast(0))
    var jumpOpen by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    var requestedPage by remember { mutableStateOf<Int?>(null) }
    val visiblePage = listState.firstVisibleItemIndex
    LaunchedEffect(requestedPage) { requestedPage?.let { listState.animateScrollToItem(it); requestedPage = null } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Page ${if (state.pageCount == 0) 0 else visiblePage + 1} / ${state.pageCount}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { jumpOpen = true }) { Text("Go to page") }
            TextButton(onClick = { onNightModeChange(!nightMode) }) { Text(if (nightMode) "Day" else "Night") }
            TextButton(onClick = onShare) { Text("Share") }
        }
        if (jumpOpen) AlertDialog(onDismissRequest = { jumpOpen = false }, title = { Text("Go to page") }, text = { OutlinedTextField(jumpText, { jumpText = it.filter(Char::isDigit) }, label = { Text("Page number") }) }, confirmButton = { TextButton(onClick = { jumpText.toIntOrNull()?.minus(1)?.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))?.let { requestedPage = it; onPageSelected(it) }; jumpOpen = false }) { Text("Go") } }, dismissButton = { TextButton(onClick = { jumpOpen = false }) { Text("Cancel") } })
        when {
            state.errorMessage != null -> Text(state.errorMessage, Modifier.align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.error)
            state.pageCount == 0 -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items((0 until state.pageCount).toList(), key = { it }) { pageIndex ->
                    val bitmap = state.pages[pageIndex]
                    if (bitmap != null) {
                        ZoomablePage(bitmap, pageIndex)
                    } else {
                        LaunchedEffect(pageIndex) { onRender(pageIndex) }
                        CircularProgressIndicator(Modifier.padding(24.dp))
                    }
                }
            }
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { onPageSelected(it) }
        }
    }
}

/**
 * Renders a single page.
 *
 * Pinch gestures are only consumed when two pointers are down, and drags are
 * only consumed once the page is zoomed in. Otherwise the gesture is left for
 * the enclosing list so vertical scrolling keeps working.
 */
@Composable private fun ZoomablePage(bitmap: android.graphics.Bitmap, pageIndex: Int) {
    var scale by remember(pageIndex) { mutableStateOf(1f) }
    var offsetX by remember(pageIndex) { mutableStateOf(0f) }
    var offsetY by remember(pageIndex) { mutableStateOf(0f) }
    Image(
        bitmap.asImageBitmap(),
        "PDF page ${pageIndex + 1}",
        Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clipToBounds()
            .semantics { contentDescription = "PDF page ${pageIndex + 1}" }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
            .pointerInput(pageIndex) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2f
                    offsetX = 0f
                    offsetY = 0f
                })
            }
            .pointerInput(pageIndex) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pinching = event.changes.size >= 2
                        if (pinching || scale > 1f) {
                            if (pinching) {
                                scale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                            }
                            if (scale > 1f) {
                                val pan = event.calculatePan()
                                val maxX = size.width * (scale - 1f) / 2f
                                val maxY = size.height * (scale - 1f) / 2f
                                offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    )
}