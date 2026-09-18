package com.alalkipgen.alalpdf.library

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Renders and caches the first page of a PDF so the library can show previews. */
object PdfThumbnails {
    private val cache = LruCache<String, Bitmap>(48)

    suspend fun load(resolver: ContentResolver, uri: Uri, width: Int): Bitmap? {
        val key = uri.toString()
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { render(resolver, uri, width) } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun render(resolver: ContentResolver, uri: Uri, width: Int): Bitmap? {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            descriptor = resolver.openFileDescriptor(uri, "r") ?: return null
            renderer = PdfRenderer(descriptor)
            if (renderer.pageCount == 0) return null
            val page = renderer.openPage(0)
            val height = (width.toFloat() / page.width * page.height).toInt().coerceIn(1, 4000)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap
        } catch (_: Exception) {
            null
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }
}

@Composable
fun PdfThumbnail(uri: Uri, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val pixelWidth = remember(width, density) { with(density) { width.roundToPx() }.coerceIn(48, 480) }
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri, pixelWidth) {
        bitmap = PdfThumbnails.load(context.contentResolver, uri, pixelWidth)
    }

    Surface(
        modifier = modifier.size(width, height).clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "PDF",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
