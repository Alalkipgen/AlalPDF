package com.alalkipgen.alalpdf.reader

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

data class PdfReaderUiState(
    val isLoading: Boolean = true,
    val pageCount: Int = 0,
    val pages: Map<Int, Bitmap> = emptyMap(),
    val errorMessage: String? = null,
)

class PdfReaderViewModel(private val repository: PdfReaderRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(PdfReaderUiState())
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    @Volatile private var generation = 0
    private val renderJobs = ConcurrentHashMap<Int, Job>()
    private lateinit var cache: BitmapPageCache

    fun initialize(context: Context) {
        if (!::cache.isInitialized) {
            val memoryClass = (context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 128) * 1024 * 1024
            cache = BitmapPageCache(memoryClass / 8)
        }
    }

    fun load(uri: Uri, width: Int, initialPage: Int = 0, nightMode: Boolean = false) {
        loadJob?.cancel()
        renderJobs.values.forEach(Job::cancel)
        renderJobs.clear()
        if (::cache.isInitialized) cache.clear()
        val currentGeneration = ++generation
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = PdfReaderUiState()
            runCatching {
                val count = repository.pageCount(uri)
                ensureActive()
                if (currentGeneration != generation) return@runCatching
                _uiState.value = PdfReaderUiState(isLoading = false, pageCount = count, pages = cache.snapshot())
                if (count > 0) render(uri, initialPage.coerceIn(0, count - 1), width, currentGeneration, nightMode)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                _uiState.value = PdfReaderUiState(isLoading = false, errorMessage = it.message ?: "Unable to open PDF")
            }
        }
    }

    fun render(uri: Uri, pageIndex: Int, width: Int, nightMode: Boolean = false) =
        render(uri, pageIndex, width, generation, nightMode)

    private fun render(uri: Uri, pageIndex: Int, width: Int, expectedGeneration: Int, nightMode: Boolean = false) {
        if (pageIndex !in 0 until _uiState.value.pageCount) return
        if (cache.get(pageIndex) != null) {
            publishPages()
            return
        }
        if (renderJobs[pageIndex]?.isActive == true) return
        renderJobs[pageIndex] = viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.render(uri, pageIndex, width, nightMode) }.onSuccess { bitmap ->
                if (expectedGeneration != generation || !isActive) return@onSuccess
                cache.put(pageIndex, bitmap)
                publishPages()
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                if (expectedGeneration != generation) return@onFailure
                _uiState.update { state -> state.copy(isLoading = false, errorMessage = it.message ?: "Unable to render page") }
            }
            renderJobs.remove(pageIndex)
        }
    }

    private fun publishPages() {
        val snapshot = cache.snapshot()
        _uiState.update { state -> state.copy(isLoading = false, pages = snapshot) }
    }

    override fun onCleared() {
        loadJob?.cancel()
        renderJobs.values.forEach(Job::cancel)
        renderJobs.clear()
        if (::cache.isInitialized) cache.clear()
        repository.close()
        super.onCleared()
    }

    class Factory(private val repository: PdfReaderRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = PdfReaderViewModel(repository) as T
    }
}
