package com.alalkipgen.alalpdf.data

import android.net.Uri
import com.alalkipgen.alalpdf.library.PdfDocument
import kotlinx.coroutines.flow.Flow

class AlalPdfRepository(private val dao: AlalPdfDao) {
    fun recent(): Flow<List<RecentDocumentEntity>> = dao.recentDocuments()
    suspend fun remember(document: PdfDocument) {
        val uri = document.uri.toString()
        val openedAt = System.currentTimeMillis()
        val updated = dao.updateRecentMetadata(
            uri = uri,
            displayName = document.name,
            sizeBytes = document.sizeBytes,
            lastModified = document.lastModified,
            openedAt = openedAt,
        )
        if (updated == 0) {
            dao.upsertRecent(
                RecentDocumentEntity(
                    uri,
                    document.name,
                    document.sizeBytes,
                    document.lastModified,
                    openedAt,
                    0,
                ),
            )
        }
    }
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