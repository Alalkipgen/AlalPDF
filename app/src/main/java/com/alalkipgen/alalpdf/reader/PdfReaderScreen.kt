package com.alalkipgen.alalpdf.reader

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

@Composable
fun PdfReaderScreen(uri: Uri, state: PdfReaderUiState, onRender: (Int) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        when {
            state.errorMessage != null -> Text(state.errorMessage, Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
            state.pageCount == 0 -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items((0 until state.pageCount).toList(), key = { it }) { pageIndex ->
                    state.pages[pageIndex]?.let { bitmap ->
                        ZoomablePage(bitmap, pageIndex)
                    } ?: run { LaunchedEffect(pageIndex) { onRender(pageIndex) }; CircularProgressIndicator(Modifier.padding(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ZoomablePage(bitmap: android.graphics.Bitmap, pageIndex: Int) {
    var scale by remember(pageIndex) { mutableStateOf(1f) }
    var offsetX by remember(pageIndex) { mutableStateOf(0f) }
    var offsetY by remember(pageIndex) { mutableStateOf(0f) }
    Image(
        bitmap.asImageBitmap(), "Page ${pageIndex + 1}",
        Modifier.fillMaxWidth().padding(8.dp).clipToBounds()
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
            .pointerInput(pageIndex) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }.pointerInput(pageIndex) {
                detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2f; offsetX = 0f; offsetY = 0f })
            }
    )
}