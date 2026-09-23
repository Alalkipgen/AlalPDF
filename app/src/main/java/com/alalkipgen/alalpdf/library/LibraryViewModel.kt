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
    val sort: LibrarySort = LibrarySort.RECENT,
    val statusMessage: String? = null,
)

class LibraryViewModel(
    private val repository: PdfLibraryRepository,
    private val recentStore: RecentDocumentsStore,
    private val prefs: LibraryPrefs,
    private val deviceScan: DeviceScanRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState(sort = prefs.sort))
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var recentDocuments: List<PdfDocument> = emptyList()
    private var scannedDocuments: List<PdfDocument> = emptyList()
    private var deviceDocuments: List<PdfDocument> = emptyList()
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
                    publish(status = "Found ${it.size} PDF file(s) in this folder")
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = it.message ?: "Unable to scan this folder",
                    )
                }
        }
    }

    /** Scans the whole device for PDFs. */
    fun scanDevice() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            runCatching { deviceScan.scan() }
                .onSuccess {
                    deviceDocuments = it
                    publish(status = "Found ${it.size} PDF file(s) on this device")
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = it.message ?: "Unable to scan this device",
                    )
                }
        }
    }

    fun loadRecent() {
        if (recentJob != null) return
        recentJob = viewModelScope.launch {
            recentStore.recent.collect { documents ->
                val (readable, stale) = repository.partitionReadable(documents)
                stale.forEach { recentStore.remove(it.uri) }
                recentDocuments = readable
                publish()
            }
        }
    }

    fun remember(document: PdfDocument) {
        prefs.unhide(document.uri.toString())
        viewModelScope.launch { recentStore.add(document) }
    }

    fun setSort(sort: LibrarySort) {
        prefs.sort = sort
        publish()
    }

    fun toggleFavorite(document: PdfDocument) {
        prefs.toggleFavorite(document.uri.toString())
        publish()
    }

    fun removeFromList(document: PdfDocument) {
        prefs.hide(listOf(document.uri.toString()))
        publish(status = "Removed from list")
    }

    fun clearList() {
        prefs.hide(_uiState.value.documents.map { it.uri.toString() })
        scannedDocuments = emptyList()
        deviceDocuments = emptyList()
        publish(status = "List cleared")
    }

    fun delete(document: PdfDocument) {
        viewModelScope.launch {
            val deleted = runCatching { repository.delete(document.uri) }.getOrDefault(false)
            val stillReadable = if (deleted) false else repository.canReadAsync(document.uri)
            if (deleted || !stillReadable) {
                val aliases = (recentDocuments + scannedDocuments + deviceDocuments)
                    .filter { it.canonicalKey == document.canonicalKey }
                scannedDocuments = scannedDocuments.filterNot { it.canonicalKey == document.canonicalKey }
                deviceDocuments = deviceDocuments.filterNot { it.canonicalKey == document.canonicalKey }
                aliases.forEach {
                    recentStore.remove(it.uri)
                    prefs.removeMetadata(it.uri.toString())
                }
                publish(status = if (deleted) "Deleted ${document.name}" else "Removed missing file")
            } else {
                publish(status = "This file cannot be deleted from Alal PDF")
            }
        }
    }

    fun rename(document: PdfDocument, newName: String) {
        viewModelScope.launch {
            val renamed = runCatching { repository.rename(document.uri, newName) }.getOrNull()
            if (renamed == null) {
                publish(status = "This file cannot be renamed")
            } else {
                val updated = runCatching { repository.inspect(renamed) }.getOrNull()
                if (updated != null) {
                    prefs.hide(listOf(document.uri.toString()))
                    scannedDocuments = scannedDocuments.filterNot { it.uri == document.uri } + updated
                    deviceDocuments = deviceDocuments.filterNot { it.uri == document.uri }
                    recentStore.add(updated)
                }
                publish(status = "Renamed")
            }
        }
    }

    fun dismissStatus() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    private fun publish(status: String? = null) {
        val favorites = prefs.favorites()
        val hidden = prefs.hidden()
        val sort = prefs.sort
        val merged = (recentDocuments + scannedDocuments + deviceDocuments)
            .groupBy(PdfDocument::canonicalKey)
            .values.mapNotNull { aliases ->
                val visible = aliases.filterNot { hidden.contains(it.uri.toString()) }
                if (visible.isEmpty()) return@mapNotNull null
                val preferred = visible.maxWithOrNull(
                    compareBy<PdfDocument> { if (it.uri.scheme == "content") 2 else 1 }
                        .thenBy { it.lastModified }
                ) ?: return@mapNotNull null
                preferred.copy(
                    lastReadPage = visible.maxOfOrNull(PdfDocument::lastReadPage) ?: 0,
                    favorite = visible.any { favorites.contains(it.uri.toString()) },
                    pageCount = visible.maxOfOrNull { prefs.pageCount(it.uri.toString()) } ?: 0,
                )
            }
        val sorted = when (sort) {
            LibrarySort.NAME -> merged.sortedBy { it.name.lowercase() }
            LibrarySort.DATE -> merged.sortedByDescending { it.lastModified }
            LibrarySort.SIZE -> merged.sortedByDescending { it.sizeBytes }
            LibrarySort.RECENT -> merged.sortedWith(
                compareByDescending<PdfDocument> { it.lastReadPage > 0 }
                    .thenByDescending { it.lastModified }
                    .thenBy { it.name.lowercase() }
            )
        }
        _uiState.value = LibraryUiState(documents = sorted, sort = sort, statusMessage = status)
    }

    class Factory(
        private val repository: PdfLibraryRepository,
        private val recentStore: RecentDocumentsStore,
        private val prefs: LibraryPrefs,
        private val deviceScan: DeviceScanRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(repository, recentStore, prefs, deviceScan) as T
    }
}
