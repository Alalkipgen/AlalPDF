package com.alalkipgen.alalpdf.reader

import android.app.Activity
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    onPrint: () -> Unit = {},
    onOpenBookmarks: () -> Unit,
    onAddBookmark: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onRender: (Int) -> Unit,
    onPromotePage: (Int) -> Unit = {},
    onRequestPageText: (Int) -> Unit = {},
    onRequestPageLinks: (Int) -> Unit = {},
    onSearch: (String) -> Unit = {},
    onClearSearch: () -> Unit = {},
    onPasswordSubmit: (String) -> Unit = {},
    onEditPdf: () -> Unit = {},
) {
    // A cold reader starts with pageCount == 0. Initialising or scrolling the
    // list to the saved page at that point clamps it to page zero, which then
    // overwrites the durable checkpoint. Start neutral and apply the target
    // only after real list items exist.
    val listState = rememberLazyListState()
    var restoreTarget by remember { mutableIntStateOf(initialPage.coerceAtLeast(0)) }
    var pageSelectionEnabled by remember { mutableStateOf(false) }
    val horizontalScroll = rememberScrollState()
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }

    var jumpOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    var requestedPage by remember { mutableStateOf<Int?>(null) }
    var textOpen by remember{mutableStateOf(false)}
    var showAsUnicode by rememberSaveable { mutableStateOf(false) }
    var searchOpen by remember{mutableStateOf(false)}
    var searchQuery by remember{mutableStateOf("")}
    var passwordText by remember{mutableStateOf("")}

    // The scrollbar and the page pill only appear while the document is moving
    // and fade away again about two seconds after scrolling stops.
    var chromeVisible by remember { mutableStateOf(false) }
    var thumbDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    var keepScreenOn by remember { mutableStateOf(true) }
    var lockRotation by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var topBarHeight by remember { mutableStateOf(0.dp) }
    // The bar floats over the pages and only fades, so the reserved space must
    // stay constant: animating it made the whole document jump on every tap.
    val readerTopInset = topBarHeight
    var thumbsOpen by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(false) }
    var autoSpeed by remember { mutableFloatStateOf(2.5f) }

    // Zoom is applied to the *content width* instead of a graphics layer.
    var scale by remember { mutableFloatStateOf(1f) }

    val visiblePage by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            mostVisiblePage(
                layout.viewportStartOffset,
                layout.viewportEndOffset,
                layout.visibleItemsInfo.map {
                    VisiblePageBounds(it.index, it.offset, it.size)
                },
                listState.firstVisibleItemIndex,
            )
        }
    }
    val lastIndex = (state.pageCount - 1).coerceAtLeast(0)
    val nightColorFilter = remember(nightMode) {
        if (!nightMode) null else ColorFilter.colorMatrix(
            ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ))
        )
    }

    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(lockRotation, activity) {
        activity?.requestedOrientation = if (lockRotation) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // PDFium text geometry and link parsing wait until scrolling settles, so
    // they never compete with the visible-page preview.
    LaunchedEffect(listState, state.pageCount, pageSelectionEnabled) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val visible = layout.visibleItemsInfo.map { it.index }.distinct()
            Triple(listState.isScrollInProgress, visiblePage, visible)
        }.distinctUntilChanged().collectLatest { (scrolling, page, visiblePages) ->
            if (pageSelectionEnabled && !scrolling && state.pageCount > 0) {
                delay(SETTLED_EXTRACTION_DELAY_MS)
                onPromotePage(page)
                // Links must work on every page the user can tap, not only the
                // first list item (which may be a few remaining pixels).
                visiblePages.forEach { visible ->
                    onRequestPageText(visible)
                    onRequestPageLinks(visible)
                }
            }
        }
    }
    LaunchedEffect(textOpen, visiblePage) {
        if (textOpen) onRequestPageText(visiblePage)
    }

    LaunchedEffect(requestedPage) {
        requestedPage?.takeIf { state.pageCount > 0 }?.let {
            listState.scrollToItem(it.coerceIn(0, lastIndex))
            requestedPage = null
        }
    }
    LaunchedEffect(pendingPage) {
        pendingPage?.let {
            restoreTarget = it.coerceAtLeast(0)
            pageSelectionEnabled = false
        }
    }
    LaunchedEffect(state.pageCount, restoreTarget, pendingPage, pageSelectionEnabled) {
        if (pageSelectionEnabled) return@LaunchedEffect
        val target = ReaderRestorePolicy.targetPage(restoreTarget, state.pageCount)
            ?: return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.scrollToItem(target)
        // Enable persistence only after the restored item is the real visible
        // page. The first visible-page emission can no longer write page zero.
        pageSelectionEnabled = true
        if (pendingPage != null) onPendingPageConsumed()
    }
    LaunchedEffect(listState, pageSelectionEnabled, state.pageCount) {
        if (!ReaderRestorePolicy.canPersistSelection(state.pageCount, pageSelectionEnabled)) {
            return@LaunchedEffect
        }
        snapshotFlow { visiblePage }
            .distinctUntilChanged()
            .collect { onPageSelected(it) }
    }
    LaunchedEffect(listState) {
        // collectLatest is important here: when a new drag starts it cancels
        // the pending hide delay immediately. The old collect blocked inside
        // delay(2000), so quick follow-up scrolls were ignored and the thumb
        // sometimes appeared many pages late.
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                chromeVisible = true
            } else {
                delay(2000)
                chromeVisible = false
            }
        }
    }
    LaunchedEffect(thumbDragging) {
        if (thumbDragging) {
            chromeVisible = true
        } else {
            delay(2000)
            chromeVisible = false
        }
    }
    LaunchedEffect(autoScroll, autoSpeed) {
        while (autoScroll) {
            listState.scrollBy(autoSpeed)
            delay(16)
        }
    }

    if (thumbsOpen) {
        BackHandler { thumbsOpen = false }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { thumbsOpen = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                        }
                    },
                    title = { Text("Pages") },
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.pageCount) { index ->
                        // Thumbnails come from their own persistent cache. The
                        // live render map only ever holds the pages near the
                        // current one, which is why the grid used to be empty.
                        val bitmap = state.thumbnails[index] ?: state.pages[index]
                        LaunchedEffect(index, bitmap == null) {
                            if (bitmap == null) onRender(index)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        requestedPage = index
                                        thumbsOpen = false
                                    },
                                tonalElevation = 2.dp,
                            ) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Page " + (index + 1),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                        colorFilter = nightColorFilter,
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text((index + 1).toString(), style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                            Text((index + 1).toString(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {},
        bottomBar = {
            if (!fullScreen && autoScroll) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Speed", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = autoSpeed,
                            onValueChange = { autoSpeed = it },
                            valueRange = 0.5f..15f,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        TextButton(onClick = { autoScroll = false }) { Text("Stop") }
                    }
                }
            }
        },
    ) { _ ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportHeight = maxHeight
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
                            detectTapGestures(
                                // A single tap hides the top bar for a full screen
                                // read; the next tap brings it back.
                                onTap = { fullScreen = !fullScreen },
                                onDoubleTap = { tap ->
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val previous = scale
                                    val next = if (previous > 1f) 1f else 2.5f
                                    val ratio = next / previous
                                    scale = next
                                    horizontalScroll.dispatchRawDelta(
                                        (horizontalScroll.value + tap.x) * (ratio - 1f)
                                    )
                                    listState.dispatchRawDelta(
                                        (listState.firstVisibleItemScrollOffset + tap.y) * (ratio - 1f)
                                    )
                                },
                            )
                        }
                ) {
                    val pageWidth = maxWidth * scale
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .horizontalScroll(horizontalScroll)
                            .width(pageWidth),
                        // The top bar floats over the pages, so the list has to
                        // start below it or the first lines stay hidden.
                        contentPadding = PaddingValues(
                            top = readerTopInset + 8.dp,
                            bottom = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.pageCount, key = { index -> index }) { index ->
                            val bitmap = state.pages[index] ?: state.thumbnails[index]
                            val pageRatio = 1f / (state.pageAspectRatios[index]
                                ?: state.defaultAspectRatio).coerceAtLeast(0.2f)
                            // The render queue already re-prioritises work, so a
                            // single request per page is enough; the old
                            // delay(2000) retry only caused duplicate renders.
                            LaunchedEffect(index) { onRender(index) }
                            Surface(
                                Modifier.fillMaxWidth(),
                                tonalElevation = 2.dp,
                                shadowElevation = 2.dp,
                            ) {
                                if (bitmap != null) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(pageRatio)) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Page " + (index + 1),
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.FillBounds,
                                            colorFilter = nightColorFilter,
                                        )
                                        val pageLinks = state.pageLinks[index].orEmpty()
                                        // Real selection: long-press a word, drag
                                        // to extend, then copy / search / share.
                                        SelectionLayer(
                                            runs = state.textRuns[index].orEmpty(),
                                            modifier = Modifier.fillMaxSize(),
                                            onSearchSelection = { selected ->
                                                searchQuery = selected
                                                searchOpen = true
                                                onSearch(selected)
                                            },
                                            onTap = { point ->
                                                // The overlay covers the page,
                                                // so it forwards plain taps:
                                                // links first, then the bar.
                                                val link = pageLinks.firstOrNull {
                                                    point.x >= it.left && point.x <= it.right &&
                                                        point.y >= it.top && point.y <= it.bottom
                                                }
                                                if (link != null) {
                                                    context.openWebLink(link.url)
                                                } else {
                                                    fullScreen = !fullScreen
                                                }
                                            },
                                            onEdgeDrag = { delta ->
                                                // Dragging a handle past the edge
                                                // scrolls the page, like Drive.
                                                // Do not launch one coroutine per
                                                // pointer event; that queued work
                                                // made the handle lag behind.
                                                listState.dispatchRawDelta(delta * 0.35f)
                                            },
                                        )
                                    }
                                } else {
                                    // The placeholder uses the real page shape, so a
                                    // finished render never changes the item height
                                    // and the list never jumps or flashes.
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

            AnimatedVisibility(
                visible = !fullScreen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                    TopAppBar(
                        modifier = Modifier.onSizeChanged {
                            topBarHeight = with(density) { it.height.toDp() }
                        },
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
                                        "Page " + (visiblePage + 1) + " of " + state.pageCount,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = onEditPdf) { Icon(Icons.Default.Edit, "Edit PDF") }
                            IconButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onNightModeChange(!nightMode)
                                },
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
                                    DropdownMenuItem(text={Text("Search document")},onClick={menuOpen=false;searchOpen=true})
                                    DropdownMenuItem(text={Text("Select & copy page text")},onClick={menuOpen=false;textOpen=true})
                                    DropdownMenuItem(
                                        text = { Text("Go to page") },
                                        onClick = { menuOpen = false; jumpText = ""; jumpOpen = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Page thumbnails") },
                                        onClick = { menuOpen = false; thumbsOpen = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Bookmarks") },
                                        onClick = { menuOpen = false; onOpenBookmarks() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add bookmark") },
                                        onClick = {
                                            menuOpen = false
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onAddBookmark()
                                        },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(text={Text("Edit PDF")},onClick={menuOpen=false;onEditPdf()})
                                    DropdownMenuItem(
                                        text = { Text("Print") },
                                        onClick = { menuOpen = false; onPrint() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (autoScroll) "Stop auto-scroll" else "Auto-scroll") },
                                        onClick = { menuOpen = false; autoScroll = !autoScroll },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (scale > 1f) "Reset zoom" else "Zoom in") },
                                        onClick = {
                                            menuOpen = false
                                            scale = if (scale > 1f) 1f else 2f
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (fullScreen) "Exit full screen" else "Full screen") },
                                        onClick = { menuOpen = false; fullScreen = !fullScreen },
                                    )
                                    DropdownMenuItem(
                                        text = { Text((if (lockRotation) "\u2713 " else "") + "Lock rotation") },
                                        onClick = { menuOpen = false; lockRotation = !lockRotation },
                                    )
                                    DropdownMenuItem(
                                        text = { Text((if (keepScreenOn) "\u2713 " else "") + "Keep screen on") },
                                        onClick = { menuOpen = false; keepScreenOn = !keepScreenOn },
                                    )
                                }
                            }
                        },
                    )
            }

            if (fullScreen) {
                BackHandler { fullScreen = false }
            }
            if (scale > 1f) {
                BackHandler(enabled = !fullScreen) { scale = 1f }
            }

            if (state.pageCount > 1) {
                val thumbHeight = 56.dp
                val trackTop = if (fullScreen) 12.dp else readerTopInset + 12.dp
                val trackBottom = 12.dp
                val trackHeight =
                    (viewportHeight - trackTop - trackBottom - thumbHeight).coerceAtLeast(0.dp)
                val fraction = if (thumbDragging) {
                    dragFraction
                } else if (lastIndex == 0) {
                    0f
                } else {
                    (visiblePage.toFloat() / lastIndex).coerceIn(0f, 1f)
                }
                AnimatedVisibility(
                    visible = chromeVisible || thumbDragging,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = trackTop + trackHeight * fraction),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(width = 40.dp, height = thumbHeight)
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    val trackPx = with(density) { trackHeight.toPx() }.coerceAtLeast(1f)
                                    dragFraction = (dragFraction + delta / trackPx).coerceIn(0f, 1f)
                                    val target = (dragFraction * lastIndex).roundToInt().coerceIn(0, lastIndex)
                                    if (target != visiblePage) {
                                        scope.launch { listState.scrollToItem(target) }
                                    }
                                },
                                onDragStarted = {
                                    dragFraction = if (lastIndex == 0) 0f else visiblePage.toFloat() / lastIndex
                                    thumbDragging = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragStopped = {
                                    thumbDragging = false
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                            ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                (visiblePage + 1).toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = thumbDragging && state.pageCount > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.inverseSurface,
                ) {
                    Text(
                        (visiblePage + 1).toString() + " / " + state.pageCount,
                        Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            if(state.requiresPassword){AlertDialog(onDismissRequest=onBack,title={Text("Password protected PDF")},text={OutlinedTextField(passwordText,{passwordText=it},label={Text("Password")},singleLine=true)},confirmButton={TextButton(onClick={onPasswordSubmit(passwordText)},enabled=passwordText.isNotBlank()){Text("Open")}},dismissButton={TextButton(onClick=onBack){Text("Cancel")}})}

            if (searchOpen) {
                AlertDialog(
                    onDismissRequest = { searchOpen = false },
                    title = { Text("Search document") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                label = { Text("Search") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { onSearch(searchQuery) },
                                    enabled = searchQuery.isNotBlank(),
                                ) { Text("Search") }
                                TextButton(
                                    onClick = { searchQuery = ""; onClearSearch() },
                                    enabled = searchQuery.isNotBlank() || state.searchResults.isNotEmpty(),
                                ) { Text("Clear") }
                            }
                            if (state.searchRunning) {
                                Text(
                                    "Searching\u2026 " + (state.searchProgress * 100).roundToInt() + "%",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                LinearProgressIndicator(
                                    progress = { state.searchProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else if (state.searchQuery.isNotBlank()) {
                                Text(
                                    state.searchResults.size.toString() + " match(es)",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            if (state.textState == PdfTextLoadState.Failed) {
                                Text(
                                    state.textError ?: "Text extraction failed",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            LazyColumn(Modifier.height(280.dp)) {
                                items(state.searchResults.size) { i ->
                                    val result = state.searchResults[i]
                                    TextButton(
                                        onClick = { requestedPage = result.page; searchOpen = false },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                "Page " + (result.page + 1),
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                            Text(
                                                result.excerpt,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { searchOpen = false }) { Text("Close") } },
                )
            }

            if (textOpen) {
                val pageText = state.pageTexts[visiblePage].orEmpty()
                val hasText = pageText.isNotBlank()
                val message = when {
                    hasText -> pageText
                    state.textState == PdfTextLoadState.Failed ->
                        "Could not read this document's text layer.\n\n" +
                            (state.textError ?: "Unknown error")
                    state.pageTexts.containsKey(visiblePage) ->
                        "This page has no embedded text. It is most likely a scanned image, " +
                            "so there is nothing to select or copy."
                    else -> "Extracting text\u2026"
                }
                val zawgyi = hasText && MyanmarText.looksLikeZawgyi(pageText)
                val shown = if (zawgyi && showAsUnicode) MyanmarText.toUnicode(pageText) else message
                AlertDialog(
                    onDismissRequest = { textOpen = false },
                    title = { Text("Page " + (visiblePage + 1) + " text") },
                    text = {
                        Column {
                            if (zawgyi) {
                                TextButton(onClick = { showAsUnicode = !showAsUnicode }) {
                                    Text(
                                        if (showAsUnicode) {
                                            "Showing Unicode \u00b7 tap for original"
                                        } else {
                                            "This page is Zawgyi \u00b7 tap to show Unicode"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            SelectionContainer {
                                Text(
                                    shown,
                                    Modifier.height(340.dp).verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = hasText,
                            onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("PDF", shown))
                                textOpen = false
                            },
                        ) { Text("Copy all") }
                    },
                    dismissButton = { TextButton(onClick = { textOpen = false }) { Text("Close") } },
                )
            }

            if (jumpOpen) {
                AlertDialog(
                    onDismissRequest = { jumpOpen = false },
                    title = { Text("Go to page") },
                    text = {
                        OutlinedTextField(
                            value = jumpText,
                            onValueChange = { input -> jumpText = input.filter(Char::isDigit) },
                            label = { Text("Page number (1 - " + state.pageCount + ")") },
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

private const val SETTLED_EXTRACTION_DELAY_MS = 250L

private fun Context.openWebLink(url: String) {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
    if (uri.scheme != "http" && uri.scheme != "https") return
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
