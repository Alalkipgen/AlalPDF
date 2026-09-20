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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
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
    onPasswordSubmit: (String) -> Unit = {},
    onEditPdf: () -> Unit = {},
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceAtLeast(0))
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
    var searchOpen by remember{mutableStateOf(false)}
    var searchQuery by remember{mutableStateOf("")}
    var passwordText by remember{mutableStateOf("")}
    val searchResults=remember(state.pageTexts,searchQuery){PdfTextExtractor.search(state.pageTexts,searchQuery)}

    // The scrollbar and the page pill only appear while the document is moving
    // and fade away again about two seconds after scrolling stops.
    var chromeVisible by remember { mutableStateOf(false) }
    var thumbDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    var keepScreenOn by remember { mutableStateOf(true) }
    var lockRotation by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var thumbsOpen by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(false) }
    var autoSpeed by remember { mutableFloatStateOf(2.5f) }

    // Zoom is applied to the *content width* instead of a graphics layer.
    var scale by remember { mutableFloatStateOf(1f) }

    val visiblePage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
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
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { onPageSelected(it) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
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
                        val bitmap = state.pages[index]
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
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.pageCount, key = { index -> index }) { index ->
                            val bitmap = state.pages[index]
                            val pageRatio = 1f / (state.pageAspectRatios[index]
                                ?: state.defaultAspectRatio).coerceAtLeast(0.2f)
                            LaunchedEffect(index, bitmap == null) {
                                if (bitmap == null) {
                                    onRender(index)
                                    delay(2000)
                                    if (state.pages[index] == null) onRender(index)
                                }
                            }
                            Surface(
                                Modifier.fillMaxWidth(),
                                tonalElevation = 2.dp,
                                shadowElevation = 2.dp,
                            ) {
                                if (bitmap != null) {
                                    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(pageRatio)) {
                                        val renderedPageWidth = maxWidth
                                        val renderedPageHeight = maxHeight
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Page " + (index + 1),
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.FillBounds,
                                            colorFilter = nightColorFilter,
                                        )
                                        state.pageLinks[index].orEmpty().forEach { link ->
                                            Box(Modifier.offset(x = maxWidth * link.left, y = maxHeight * link.top)
                                                .width(maxWidth * (link.right - link.left))
                                                .height(maxHeight * (link.bottom - link.top))
                                                .clickable(role = Role.Button, onClick = { context.openWebLink(link.url) }))
                                        }
                                        val textRuns = state.textRuns.filter { it.page == index }
                                        if (textRuns.isNotEmpty()) SelectionContainer {
                                            Box(Modifier.fillMaxSize()) {
                                                textRuns.forEach { run ->
                                                    Text(
                                                        run.text,
                                                        color = Color.Transparent,
                                                        fontSize = 10.sp,
                                                        modifier = Modifier
                                                            .offset(renderedPageWidth * run.left, renderedPageHeight * run.top)
                                                            .width((renderedPageWidth * (run.right - run.left)).coerceAtLeast(24.dp))
                                                            .height((renderedPageHeight * (run.bottom - run.top)).coerceAtLeast(18.dp)),
                                                    )
                                                }
                                            }
                                        }
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
                val trackHeight = (viewportHeight - thumbHeight).coerceAtLeast(0.dp)
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
                        .offset(y = trackHeight * fraction),
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
            if(searchOpen){AlertDialog(onDismissRequest={searchOpen=false},title={Text("Search document")},text={Column{OutlinedTextField(searchQuery,{searchQuery=it},label={Text("Search")});Text("${searchResults.size} page(s)");LazyColumn(Modifier.height(280.dp)){items(searchResults.size){i->val r=searchResults[i];TextButton(onClick={requestedPage=r.page;searchOpen=false},modifier=Modifier.fillMaxWidth()){Column{Text("Page ${r.page+1}");Text(r.excerpt,maxLines=3,overflow=TextOverflow.Ellipsis)}}}}}},confirmButton={TextButton(onClick={searchOpen=false}){Text("Close")}})}
            if(textOpen){val t=state.pageTexts.firstOrNull{it.page==visiblePage}?.text.orEmpty();AlertDialog(onDismissRequest={textOpen=false},title={Text("Page text")},text={SelectionContainer{Text(t.ifBlank{"No selectable text"},Modifier.height(360.dp).verticalScroll(rememberScrollState()))}},confirmButton={TextButton(onClick={context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("PDF",t));textOpen=false}){Text("Copy all")}})}
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
