package com.alalkipgen.alalpdf.reader

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * [pages] is a snapshot state map that is created once and never replaced.
 * Previously every finished page produced a brand new immutable map inside the
 * state flow, so all 100+ list items and the whole top bar recomposed for each
 * rendered page. That is what made long documents lag and flash.
 */
data class PdfReaderUiState(
    val isLoading: Boolean = true,
    val pageCount: Int = 0,
    val pages: SnapshotStateMap<Int, Bitmap> = mutableStateMapOf(),
    val aspectRatio: Float = 1.414f,
    val errorMessage: String? = null,
)

class PdfReaderViewModel(private val repository: PdfReaderRepository) : ViewModel() {
    private val pages = mutableStateMapOf<Int, Bitmap>()
    private val _uiState = MutableStateFlow(PdfReaderUiState(pages = pages))
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    @Volatile private var generation = 0
    private val renderJobs = ConcurrentHashMap<Int, Job>()
    private lateinit var cache: BitmapPageCache
    private var ratioKnown = false

    // Remembering what is already loaded lets a configuration change (rotation)
    // reuse every rendered page instead of clearing the cache and flashing a
    // blank document while everything re-renders.
    private var loadedUri: String? = null
    private var loadedWidth = 0
    private var loadedNightMode = false

    fun initialize(context: Context) {
        if (!::cache.isInitialized) {
            val memoryClass = (context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 128) * 1024 * 1024
            cache = BitmapPageCache(memoryClass / 4) { index -> pages.remove(index) }
        }
    }

    fun load(uri: Uri, width: Int, initialPage: Int = 0, nightMode: Boolean = false) {
        val key = uri.toString()
        if (key == loadedUri && nightMode == loadedNightMode && _uiState.value.pageCount > 0) {
            // Same document, same rendering mode: this is a rotation or a simple
            // recomposition. Keep the pages that are already on screen.
            loadedWidth = maxOf(loadedWidth, width)
            renderWindow(uri, initialPage, loadedWidth, nightMode)
            return
        }

        loadJob?.cancel()
        renderJobs.values.forEach(Job::cancel)
        renderJobs.clear()
        if (::cache.isInitialized) cache.clear()
        pages.clear()
        ratioKnown = false
        loadedUri = key
        loadedWidth = width
        loadedNightMode = nightMode
        val currentGeneration = ++generation
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = PdfReaderUiState(pages = pages)
            runCatching {
                val count = repository.pageCount(uri)
                ensureActive()
                if (currentGeneration != generation) return@runCatching
                _uiState.update { state -> state.copy(isLoading = false, pageCount = count, errorMessage = null) }
                if (count > 0) {
                    renderWindow(uri, initialPage.coerceIn(0, count - 1), width, currentGeneration, nightMode)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update { state ->
                    state.copy(isLoading = false, errorMessage = error.message ?: "Unable to open PDF")
                }
            }
        }
    }

    fun render(uri: Uri, pageIndex: Int, width: Int, nightMode: Boolean = false) =
        render(uri, pageIndex, maxOf(width, loadedWidth), generation, nightMode)

    /**
     * Renders the requested page plus its neighbours. Prefetching removes almost
     * every loading placeholder while scrolling continuously.
     */
    fun renderWindow(uri: Uri, pageIndex: Int, width: Int, nightMode: Boolean = false) =
        renderWindow(uri, pageIndex, maxOf(width, loadedWidth), generation, nightMode)

    private fun renderWindow(uri: Uri, pageIndex: Int, width: Int, expectedGeneration: Int, nightMode: Boolean) {
        render(uri, pageIndex, width, expectedGeneration, nightMode)
        for (offset in 1..3) {
            render(uri, pageIndex + offset, width, expectedGeneration, nightMode)
            render(uri, pageIndex - offset, width, expectedGeneration, nightMode)
        }
    }

    private fun render(uri: Uri, pageIndex: Int, width: Int, expectedGeneration: Int, nightMode: Boolean = false) {
        if (pageIndex !in 0 until _uiState.value.pageCount) return
        if (!::cache.isInitialized) return
        val cached = cache.get(pageIndex)
        if (cached != null) {
            if (pages[pageIndex] !== cached) pages[pageIndex] = cached
            return
        }
        // Only skip when a job for this page is genuinely still running.
        val existing = renderJobs[pageIndex]
        if (existing != null && existing.isActive) return
        val job = viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.render(uri, pageIndex, width, nightMode) }
                .onSuccess { bitmap ->
                    if (expectedGeneration != generation || !isActive) return@onSuccess
                    cache.put(pageIndex, bitmap)
                    pages[pageIndex] = bitmap
                    if (!ratioKnown && bitmap.width > 0) {
                        ratioKnown = true
                        val ratio = bitmap.height.toFloat() / bitmap.width
                        if (abs(ratio - _uiState.value.aspectRatio) > 0.01f) {
                            _uiState.update { state -> state.copy(aspectRatio = ratio) }
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    if (expectedGeneration != generation) return@onFailure
                    _uiState.update { state ->
                        state.copy(isLoading = false, errorMessage = error.message ?: "Unable to render page")
                    }
                }
        }
        renderJobs[pageIndex] = job
        job.invokeOnCompletion { renderJobs.remove(pageIndex, job) }
    }

    override fun onCleared() {
        loadJob?.cancel()
        renderJobs.values.forEach(Job::cancel)
        renderJobs.clear()
        pages.clear()
        if (::cache.isInitialized) cache.clear()
        repository.close()
        super.onCleared()
    }

    class Factory(private val repository: PdfReaderRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = PdfReaderViewModel(repository) as T
    }
}
