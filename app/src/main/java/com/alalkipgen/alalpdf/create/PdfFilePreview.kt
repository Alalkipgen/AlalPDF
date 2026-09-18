package com.alalkipgen.alalpdf.create

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Renders a locally generated PDF so the user can check it before saving. */
@Composable
fun PdfFilePreview(file: File, modifier: Modifier = Modifier) {
    var pages by remember(file.path, file.lastModified()) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var loading by remember(file.path, file.lastModified()) { mutableStateOf(true) }

    LaunchedEffect(file.path, file.lastModified()) {
        loading = true
        pages = withContext(Dispatchers.IO) { renderPages(file, 900) }
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
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(pages.size) { index ->
                    Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp, shadowElevation = 2.dp) {
                        Image(
                            bitmap = pages[index].asImageBitmap(),
                            contentDescription = "Preview page ${index + 1}",
                            modifier = Modifier.fillMaxWidth(),
                        )
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
