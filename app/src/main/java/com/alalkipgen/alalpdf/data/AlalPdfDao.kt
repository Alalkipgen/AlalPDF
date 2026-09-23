package com.alalkipgen.alalpdf.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlalPdfDao {
    @Query("SELECT * FROM recent_documents ORDER BY lastOpenedAt DESC LIMIT 20")
    fun recentDocuments(): Flow<List<RecentDocumentEntity>>

    @Query("SELECT lastReadPage FROM recent_documents WHERE uri = :uri LIMIT 1")
    suspend fun lastReadPage(uri: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecent(document: RecentDocumentEntity)

    @Query(
        """
        UPDATE recent_documents
        SET displayName = :displayName,
            sizeBytes = :sizeBytes,
            lastModified = :lastModified,
            lastOpenedAt = :openedAt
        WHERE uri = :uri
        """,
    )
    suspend fun updateRecentMetadata(
        uri: String,
        displayName: String,
        sizeBytes: Long,
        lastModified: Long,
        openedAt: Long,
    ): Int

    @Query("DELETE FROM recent_documents WHERE uri = :uri")
    suspend fun deleteRecent(uri: String)

    @Query("DELETE FROM recent_documents")
    suspend fun clearRecent()

    @Query("UPDATE recent_documents SET lastReadPage = :page, lastOpenedAt = :openedAt WHERE uri = :uri")
    suspend fun updateLastReadPage(uri: String, page: Int, openedAt: Long)

    @Query(
        """
        SELECT * FROM reading_progress
        WHERE documentKey IN (:documentKeys)
        ORDER BY updatedAt DESC
        LIMIT 1
        """,
    )
    suspend fun latestReadingProgress(documentKeys: List<String>): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadingProgress(progress: List<ReadingProgressEntity>)

    @Query("DELETE FROM reading_progress WHERE documentUri = :uri")
    suspend fun deleteReadingProgress(uri: String)

    @Query("SELECT * FROM bookmarks WHERE documentUri = :uri ORDER BY pageIndex ASC")
    fun bookmarks(uri: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE documentUri = :uri AND pageIndex = :page LIMIT 1")
    suspend fun bookmark(uri: String, page: Int): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBookmark(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE documentUri = :uri AND pageIndex = :page")
    suspend fun deleteBookmark(uri: String, page: Int)

    @Query("DELETE FROM bookmarks WHERE documentUri = :uri")
    suspend fun deleteBookmarks(uri: String)
}