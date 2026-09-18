package com.alalkipgen.alalpdf.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    state: PdfReaderUiState,
    title: String,
    initialPage: Int = 0,
    nightMode: Boolean = false,
    pendingPage: Int? = null,
    onPendingPageConsumed: () -> Unit = {},
    onBack: () -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    onShare: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onAddBookmark: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onRender: (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceAtLeast(0))
    var jumpOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    var requestedPage by remember { mutableStateOf<Int?>(null) }
    var showPill by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(initialPage.toFloat()) }

    // One zoom/pan state for the whole document, so the scale no longer resets
    // every time a different page scrolls into view.
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    val visiblePage = listState.firstVisibleItemIndex
    val lastIndex = (state.pageCount - 1).coerceAtLeast(0)

    LaunchedEffect(requestedPage) {
        requestedPage?.let {
            listState.scrollToItem(it.coerceIn(0, lastIndex))
            requestedPage = null
        }
    }
    LaunchedEffect(pendingPage) {
        pendingPage?.let {
            listState.scrollToItem(it.coerceIn(0, lastIndex))
            onPendingPageConsumed()
        }
    }
    LaunchedEffect(visiblePage, dragging) {
        if (!dragging) sliderValue = visiblePage.toFloat()
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { onPageSelected(it) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                showPill = true
            } else {
                delay(1500)
                showPill = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.pageCount > 0) {
                            Text(
                                "Page ${visiblePage + 1} of ${state.pageCount}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onNightModeChange(!nightMode) },
                        colors = if (nightMode) {
                            IconButtonDefaults.filledTonalIconButtonColors()
                        } else {
                            IconButtonDefaults.iconButtonColors()
                        },
                    ) {
                        Icon(
                            if (nightMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Night mode",
                        )
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Go to page") },
                                onClick = { menuOpen = false; jumpText = ""; jumpOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Bookmarks") },
                                onClick = { menuOpen = false; onOpenBookmarks() },
                            )
                            DropdownMenuItem(
                                text = { Text("Add bookmark") },
                                onClick = { menuOpen = false; onAddBookmark() },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.pageCount > 1) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("1", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = sliderValue.coerceIn(0f, lastIndex.toFloat()),
                            onValueChange = { dragging = true; sliderValue = it },
                            onValueChangeFinished = {
                                dragging = false
                                requestedPage = sliderValue.roundToInt()
                            },
                            valueRange = 0f..lastIndex.toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text("${state.pageCount}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
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
                                    val maxX = size.width * (scale - 1f) / 2f
                                    if (event.changes.size >= 2) {
                                        scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                        offsetX = if (scale > 1f) {
                                            (offsetX + pan.x).coerceIn(-maxX, maxX)
                                        } else {
                                            0f
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else if (scale > 1f && abs(pan.x) > abs(pan.y)) {
                                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    scale = if (scale > 1f) 1f else 2.5f
                                    offsetX = 0f
                                }
                            )
                        }
                ) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                        }
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.pageCount) { index ->
                                val bitmap = state.pages[index]
                                LaunchedEffect(index, nightMode) { onRender(index) }
                                Surface(
                                    Modifier.fillMaxWidth(),
                                    tonalElevation = 2.dp,
                                    shadowElevation = 2.dp,
                                ) {
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Page ${index + 1}",
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        // Full-height placeholder keeps LazyColumn from
                                        // composing many pages at once on large PDFs.
                                        Box(
                                            Modifier.fillMaxWidth().height(560.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showPill && state.pageCount > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.inverseSurface,
                ) {
                    Text(
                        "${visiblePage + 1} / ${state.pageCount}",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (jumpOpen) {
                AlertDialog(
                    onDismissRequest = { jumpOpen = false },
                    title = { Text("Go to page") },
                    text = {
                        OutlinedTextField(
                            value = jumpText,
                            onValueChange = { input -> jumpText = input.filter(Char::isDigit) },
                            label = { Text("Page number (1 - ${state.pageCount})") },
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            jumpText.toIntOrNull()?.let { requestedPage = (it - 1).coerceIn(0, lastIndex) }
                            jumpOpen = false
                        }) { Text("Go") }
                    },
                    dismissButton = { TextButton(onClick = { jumpOpen = false }) { Text("Cancel") } },
                )
            }
        }
    }
}
