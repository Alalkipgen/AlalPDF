package com.alalkipgen.alalpdf.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibraryUiState(
    val isLoading: Boolean = false,
    val documents: List<PdfDocument> = emptyList(),
    val errorMessage: String? = null,
)

class LibraryViewModel(private val repository: PdfLibraryRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    fun openDocument(uri: Uri) = load { listOf(repository.inspect(uri)) }
    fun openFolder(uri: Uri) = load { repository.listFolder(uri) }

    private fun load(block: suspend () -> List<PdfDocument>) {
        viewModelScope.launch {
            _uiState.value = LibraryUiState(isLoading = true)
            _uiState.value = runCatching { LibraryUiState(documents = block()) }
                .getOrElse { LibraryUiState(errorMessage = it.message ?: "Unable to read PDF files") }
        }
    }

    class Factory(private val repository: PdfLibraryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(repository) as T
    }
}