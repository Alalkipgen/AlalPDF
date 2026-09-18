package com.alalkipgen.alalpdf.reader.bookmark

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alalkipgen.alalpdf.data.AlalPdfRepository
import com.alalkipgen.alalpdf.data.BookmarkEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BookmarksViewModel(
    private val repository: AlalPdfRepository,
    private val documentUri: Uri,
) : ViewModel() {
    val bookmarks: StateFlow<List<BookmarkEntity>> = repository.bookmarks(documentUri)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun add(page: Int) {
        viewModelScope.launch { repository.toggleBookmark(documentUri, page, "Page ${page + 1}") }
    }

    fun delete(bookmark: BookmarkEntity) {
        viewModelScope.launch { repository.deleteBookmark(documentUri, bookmark.pageIndex) }
    }

    class Factory(
        private val repository: AlalPdfRepository,
        private val documentUri: Uri,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BookmarksViewModel(repository, documentUri) as T
    }
}
