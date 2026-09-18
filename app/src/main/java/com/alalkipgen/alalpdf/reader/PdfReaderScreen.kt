package com.alalkipgen.alalpdf.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
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
        LaunchedEffect(visiblePage) { onPageSelected(visiblePage) }
    }
}

@Composable private fun ZoomablePage(bitmap: android.graphics.Bitmap, pageIndex: Int) {
    var scale by remember(pageIndex) { mutableStateOf(1f) }
    var offsetX by remember(pageIndex) { mutableStateOf(0f) }
    var offsetY by remember(pageIndex) { mutableStateOf(0f) }
    Image(bitmap.asImageBitmap(), "PDF page ${pageIndex + 1}", Modifier.fillMaxWidth().padding(8.dp).clipToBounds().semantics { contentDescription = "PDF page ${pageIndex + 1}" }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY).pointerInput(pageIndex) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 4f); offsetX += pan.x; offsetY += pan.y } }.pointerInput(pageIndex) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2f; offsetX = 0f; offsetY = 0f }) })
}