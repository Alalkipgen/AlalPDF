package com.alalkipgen.alalpdf.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FolderSort { NAME, DATE, SIZE }

data class FolderEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

data class FolderBrowserUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val breadcrumbs: List<String> = emptyList(),
    val entries: List<FolderEntry> = emptyList(),
    val pdfCount: Int = 0,
    val sort: FolderSort = FolderSort.NAME,
    val gridMode: Boolean = false,
    val errorMessage: String? = null,
)

class FolderBrowserViewModel(private val context: Context) : ViewModel() {
    private val _uiState = MutableStateFlow(FolderBrowserUiState())
    val uiState: StateFlow<FolderBrowserUiState> = _uiState.asStateFlow()
    private val stack = mutableListOf<DocumentFile>()
    private var sort = FolderSort.NAME
    private var gridMode = false

    fun openRoot(treeUri: Uri) {
        if (stack.isNotEmpty()) return
        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null) {
            _uiState.value = FolderBrowserUiState(isLoading = false, errorMessage = "Unable to open this folder.")
            return
        }
        stack.add(root)
        refresh()
    }

    fun enter(entry: FolderEntry) {
        if (!entry.isDirectory) return
        viewModelScope.launch {
            val current = stack.lastOrNull() ?: return@launch
            val child = withContext(Dispatchers.IO) { current.listFiles().firstOrNull { it.uri == entry.uri } }
            if (child != null) {
                stack.add(child)
                refresh()
            }
        }
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        refresh()
        return true
    }

    fun setSort(value: FolderSort) {
        sort = value
        refresh()
    }

    fun toggleGrid() {
        gridMode = !gridMode
        refresh()
    }

    private fun refresh() {
        val current = stack.lastOrNull() ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val entries = withContext(Dispatchers.IO) {
                current.listFiles().mapNotNull { file ->
                    val name = file.name ?: return@mapNotNull null
                    if (!file.isDirectory && !name.endsWith(".pdf", ignoreCase = true)) return@mapNotNull null
                    FolderEntry(file.uri, name, file.isDirectory, file.length(), file.lastModified())
                }
            }
            val sorted = when (sort) {
                FolderSort.NAME -> entries.sortedWith(
                    compareByDescending<FolderEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
                )
                FolderSort.DATE -> entries.sortedWith(
                    compareByDescending<FolderEntry> { it.isDirectory }.thenByDescending { it.lastModified }
                )
                FolderSort.SIZE -> entries.sortedWith(
                    compareByDescending<FolderEntry> { it.isDirectory }.thenByDescending { it.sizeBytes }
                )
            }
            _uiState.value = FolderBrowserUiState(
                isLoading = false,
                title = current.name ?: "Folder",
                breadcrumbs = stack.map { it.name ?: "Storage" },
                entries = sorted,
                pdfCount = sorted.count { !it.isDirectory },
                sort = sort,
                gridMode = gridMode,
            )
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FolderBrowserViewModel(context) as T
    }
}
