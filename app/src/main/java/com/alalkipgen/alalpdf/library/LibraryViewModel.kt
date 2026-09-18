package com.alalkipgen.alalpdf.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

data class LibraryUiState(
    val isLoading: Boolean = false,
    val documents: List<PdfDocument> = emptyList(),
    val errorMessage: String? = null,
)

class LibraryViewModel(private val repository: PdfLibraryRepository, private val recentStore: RecentDocumentsStore) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    fun openDocument(uri: Uri) = load { listOf(repository.inspect(uri)) }
    fun openFolder(uri: Uri) = load { repository.listFolder(uri) }
    fun loadRecent() { viewModelScope.launch { recentStore.recent.collect { _uiState.value = LibraryUiState(documents = it) } } }
    fun remember(document: PdfDocument) { viewModelScope.launch { recentStore.add(document) } }

    private fun load(block: suspend () -> List<PdfDocument>) {
        viewModelScope.launch {
            _uiState.value = LibraryUiState(isLoading = true)
            _uiState.value = runCatching { LibraryUiState(documents = block()) }
                .getOrElse { LibraryUiState(errorMessage = it.message ?: "Unable to read PDF files") }
        }
    }

    class Factory(private val repository: PdfLibraryRepository, private val recentStore: RecentDocumentsStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(repository, recentStore) as T
    }
}