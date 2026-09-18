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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    state: PdfReaderUiState,
    initialPage: Int = 0,
    nightMode: Boolean = false,
    onBack: () -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    onShare: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onRender: (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceAtLeast(0))
    var jumpOpen by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    var requestedPage by remember { mutableStateOf<Int?>(null) }
    val visiblePage = listState.firstVisibleItemIndex
    LaunchedEffect(requestedPage) { requestedPage?.let { listState.animateScrollToItem(it); requestedPage = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Reading", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Page ${if (state.pageCount == 0) 0 else visiblePage + 1} of ${state.pageCount}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { jumpOpen = true }) { Text("Go") }
                    TextButton(onClick = { onNightModeChange(!nightMode) }) { Text(if (nightMode) "Day" else "Night") }
                    TextButton(onClick = onShare) { Text("Share") }
                },
            )
        },
    ) { contentPadding ->
        Box(Modifier.fillMaxSize().padding(contentPadding)) {
            when {
                state.errorMessage != null -> Text(
                    state.errorMessage,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                state.pageCount == 0 -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Opening document\u2026", style = MaterialTheme.typography.bodyMedium)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items((0 until state.pageCount).toList(), key = { it }) { pageIndex ->
                        val bitmap = state.pages[pageIndex]
                        if (bitmap != null) {
                            ZoomablePage(bitmap, pageIndex)
                        } else {
                            // A full-height placeholder keeps the lazy list from
                            // composing dozens of pages at once, which is what made
                            // large documents look stuck on an endless spinner.
                            LaunchedEffect(pageIndex) { onRender(pageIndex) }
                            Surface(
                                Modifier.fillMaxWidth().height(560.dp).padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Column(
                                    Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(12.dp))
                                    Text("Page ${pageIndex + 1}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            if (jumpOpen) {
                AlertDialog(
                    onDismissRequest = { jumpOpen = false },
                    title = { Text("Go to page") },
                    text = {
                        OutlinedTextField(
                            jumpText,
                            { jumpText = it.filter(Char::isDigit) },
                            label = { Text("Page number (1\u2013${state.pageCount})") },
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            jumpText.toIntOrNull()?.minus(1)?.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))?.let {
                                requestedPage = it
                                onPageSelected(it)
                            }
                            jumpOpen = false
                        }) { Text("Go") }
                    },
                    dismissButton = { TextButton(onClick = { jumpOpen = false }) { Text("Cancel") } },
                )
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { onPageSelected(it) }
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
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Image(
            bitmap.asImageBitmap(),
            "PDF page ${pageIndex + 1}",
            Modifier
                .fillMaxWidth()
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
}
