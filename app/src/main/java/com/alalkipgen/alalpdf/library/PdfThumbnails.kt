package com.alalkipgen.alalpdf.library

import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class ThumbnailRenderSize(val width: Int, val height: Int)

/** Pure policy functions kept separate so memory limits can be unit-tested. */
internal object ThumbnailPolicy {
    private const val MIB = 1024 * 1024
    private const val MIN_CACHE_BYTES = 4 * MIB
    private const val MAX_CACHE_BYTES = 12 * MIB

    fun cacheSizeBytes(maxHeapBytes: Long): Int =
        (maxHeapBytes / 32L)
            .coerceIn(MIN_CACHE_BYTES.toLong(), MAX_CACHE_BYTES.toLong())
            .toInt()

    fun renderSize(width: Int, height: Int): ThumbnailRenderSize =
        ThumbnailRenderSize(
            width = width.coerceIn(64, 192),
            height = height.coerceIn(80, 256),
        )
}

private data class ThumbnailKey(
    val uri: String,
    val width: Int,
    val height: Int,
)

/**
 * Renders the first page of a PDF for the library.
 *
 * The old cache counted entries rather than bytes, so 48 tall ARGB bitmaps
 * could occupy hundreds of megabytes. Fast flings also opened one PdfRenderer
 * per visible/prefetched row with no concurrency limit. This cache is bounded
 * by bytes, renders only at the on-screen thumbnail size and permits at most
 * two native renders at a time.
 */
object PdfThumbnails {
    private val maxCacheBytes = ThumbnailPolicy.cacheSizeBytes(Runtime.getRuntime().maxMemory())
    private val renderGate = Semaphore(permits = 2)
    private val cache = object : LruCache<ThumbnailKey, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: ThumbnailKey, value: Bitmap): Int =
            value.allocationByteCount.coerceAtLeast(1)
    }

    @Volatile
    private var initialized = false

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun onLowMemory() {
            cache.evictAll()
        }

        override fun onTrimMemory(level: Int) {
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> cache.evictAll()
                level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                    cache.trimToSize(maxCacheBytes / 4)
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                    cache.trimToSize(maxCacheBytes / 2)
            }
        }
    }

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            context.applicationContext.registerComponentCallbacks(memoryCallbacks)
            initialized = true
        }
    }

    fun peek(uri: Uri, width: Int, height: Int): Bitmap? {
        val size = ThumbnailPolicy.renderSize(width, height)
        return cache.get(ThumbnailKey(uri.toString(), size.width, size.height))
    }

    suspend fun load(
        resolver: ContentResolver,
        uri: Uri,
        width: Int,
        height: Int,
    ): Bitmap? {
        val size = ThumbnailPolicy.renderSize(width, height)
        val key = ThumbnailKey(uri.toString(), size.width, size.height)
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            renderGate.withPermit {
                // A request for the same thumbnail may have completed while
                // this coroutine was waiting for one of the two render slots.
                cache.get(key)?.let { return@withPermit it }
                val bitmap = try {
                    render(resolver, uri, size)
                } catch (_: OutOfMemoryError) {
                    // Recover instead of terminating the process. The next
                    // idle request can retry after Android has reclaimed RAM.
                    cache.evictAll()
                    null
                } catch (_: Exception) {
                    null
                }
                bitmap?.also { cache.put(key, it) }
            }
        }
    }

    private fun render(
        resolver: ContentResolver,
        uri: Uri,
        size: ThumbnailRenderSize,
    ): Bitmap? {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: return null
        return descriptor.use {
            PdfRenderer(it).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    if (page.width <= 0 || page.height <= 0) return null
                    val bitmap = Bitmap.createBitmap(
                        size.width,
                        size.height,
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(Color.WHITE)

                    val scale = minOf(
                        size.width.toFloat() / page.width.toFloat(),
                        size.height.toFloat() / page.height.toFloat(),
                    )
                    val left = (size.width - page.width * scale) / 2f
                    val top = (size.height - page.height * scale) / 2f
                    val matrix = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(left, top)
                    }
                    page.render(
                        bitmap,
                        null,
                        matrix,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                    )

                    // RGB_565 halves the retained thumbnail memory. Rendering
                    // still uses ARGB_8888 because PdfRenderer requires it.
                    val compact = bitmap.copy(Bitmap.Config.RGB_565, false)
                    if (compact != null) {
                        bitmap.recycle()
                        compact
                    } else {
                        bitmap
                    }
                }
            }
        }
    }
}

@Composable
fun PdfThumbnail(
    uri: Uri,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    loadEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val pixelWidth = remember(width, density) {
        with(density) { width.roundToPx() }
    }
    val pixelHeight = remember(height, density) {
        with(density) { height.roundToPx() }
    }
    var bitmap by remember(uri, pixelWidth, pixelHeight) {
        mutableStateOf(PdfThumbnails.peek(uri, pixelWidth, pixelHeight))
    }

    LaunchedEffect(uri, pixelWidth, pixelHeight, loadEnabled) {
        if (loadEnabled && bitmap == null) {
            bitmap = PdfThumbnails.load(
                context.contentResolver,
                uri,
                pixelWidth,
                pixelHeight,
            )
        }
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
                contentScale = ContentScale.FillBounds,
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