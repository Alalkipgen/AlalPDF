package com.alalkipgen.alalpdf.data

import android.net.Uri
import com.alalkipgen.alalpdf.library.PdfDocument
import kotlinx.coroutines.flow.Flow

class AlalPdfRepository(private val dao: AlalPdfDao) {
    fun recent(): Flow<List<RecentDocumentEntity>> = dao.recentDocuments()
    suspend fun remember(document: PdfDocument) = dao.upsertRecent(
        RecentDocumentEntity(document.uri.toString(), document.name, document.sizeBytes, document.lastModified, System.currentTimeMillis(), 0)
    )
    suspend fun savePage(uri: Uri, page: Int) = dao.updateLastReadPage(uri.toString(), page, System.currentTimeMillis())
    suspend fun page(uri: Uri): Int = dao.lastReadPage(uri.toString()) ?: 0
    suspend fun forget(uri: Uri) {
        dao.deleteRecent(uri.toString())
        dao.deleteBookmarks(uri.toString())
    }
    fun bookmarks(uri: Uri): Flow<List<BookmarkEntity>> = dao.bookmarks(uri.toString())
    suspend fun toggleBookmark(uri: Uri, page: Int, label: String? = null): Boolean {
        val existing = dao.bookmark(uri.toString(), page)
        return if (existing == null) {
            dao.addBookmark(BookmarkEntity(documentUri = uri.toString(), pageIndex = page, label = label, createdAt = System.currentTimeMillis()))
            true
        } else { dao.deleteBookmark(existing); false }
    }
    suspend fun deleteBookmark(uri: Uri, page: Int) = dao.deleteBookmark(uri.toString(), page)
}