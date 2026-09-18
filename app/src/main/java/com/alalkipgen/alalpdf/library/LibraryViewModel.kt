package com.alalkipgen.alalpdf.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibraryUiState(
    val isLoading: Boolean = false,
    val documents: List<PdfDocument> = emptyList(),
    val errorMessage: String? = null,
)

class LibraryViewModel(
    private val repository: PdfLibraryRepository,
    private val recentStore: RecentDocumentsStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var recentDocuments: List<PdfDocument> = emptyList()
    private var scannedDocuments: List<PdfDocument> = emptyList()
    private var recentJob: Job? = null

    fun openDocument(uri: Uri) {
        viewModelScope.launch {
            runCatching { repository.inspect(uri) }
                .onSuccess { remember(it) }
                .onFailure { _uiState.value = _uiState.value.copy(errorMessage = it.message ?: "Unable to read PDF") }
        }
    }

    fun openFolder(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            runCatching { repository.listFolder(uri) }
                .onSuccess {
                    scannedDocuments = it
                    publish()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = it.message ?: "Unable to scan this folder",
                    )
                }
        }
    }

    fun loadRecent() {
        if (recentJob != null) return
        recentJob = viewModelScope.launch {
            recentStore.recent.collect {
                recentDocuments = it
                publish()
            }
        }
    }

    fun remember(document: PdfDocument) {
        viewModelScope.launch { recentStore.add(document) }
    }

    private fun publish() {
        val merged = (recentDocuments + scannedDocuments)
            .distinctBy { it.uri.toString() }
            .sortedWith(
                compareByDescending<PdfDocument> { it.lastReadPage > 0 }
                    .thenByDescending { it.lastModified }
                    .thenBy { it.name.lowercase() }
            )
        _uiState.value = LibraryUiState(documents = merged)
    }

    class Factory(
        private val repository: PdfLibraryRepository,
        private val recentStore: RecentDocumentsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(repository, recentStore) as T
    }
}
