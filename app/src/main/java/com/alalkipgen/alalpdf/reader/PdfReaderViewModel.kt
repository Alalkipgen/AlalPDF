package com.alalkipgen.alalpdf.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import android.app.ActivityManager
import android.content.Context

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
    private var generation = 0
    private val renderJobs = mutableMapOf<Int, Job>()
    private lateinit var cache: BitmapPageCache

    fun initialize(context: Context) {
        if (!::cache.isInitialized) {
            val memoryClass = (context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 128) * 1024 * 1024
            cache = BitmapPageCache(memoryClass / 8)
        }
    }

    fun load(uri: Uri, width: Int, initialPage: Int = 0) {
        loadJob?.cancel()
        val currentGeneration = ++generation
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = PdfReaderUiState()
            runCatching {
                val count = repository.pageCount(uri)
                ensureActive()
                _uiState.value = PdfReaderUiState(isLoading = false, pageCount = count, pages = cache.snapshot())
                if (count > 0) render(uri, initialPage.coerceIn(0, count - 1), width, currentGeneration)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                _uiState.value = PdfReaderUiState(isLoading = false, errorMessage = it.message ?: "Unable to open PDF")
            }
        }
    }

    fun render(uri: Uri, pageIndex: Int, width: Int) = render(uri, pageIndex, width, generation)

    private fun render(uri: Uri, pageIndex: Int, width: Int, expectedGeneration: Int) {
        if (pageIndex !in 0 until _uiState.value.pageCount) return
        cache.get(pageIndex)?.let { _uiState.value = _uiState.value.copy(pages = _uiState.value.pages + (pageIndex to it)); return }
        renderJobs[pageIndex]?.cancel()
        renderJobs[pageIndex] = viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.render(uri, pageIndex, width) }.onSuccess { bitmap ->
                if (expectedGeneration != generation || !isActive) { bitmap.recycle(); return@onSuccess }
                cache.put(pageIndex, bitmap)
                _uiState.value = _uiState.value.copy(isLoading = false, pages = _uiState.value.pages + (pageIndex to bitmap))
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = it.message ?: "Unable to render page")
            }
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        renderJobs.values.forEach(Job::cancel)
        if (::cache.isInitialized) cache.clear()
        super.onCleared()
    }

    class Factory(private val repository: PdfReaderRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = PdfReaderViewModel(repository) as T
    }
}