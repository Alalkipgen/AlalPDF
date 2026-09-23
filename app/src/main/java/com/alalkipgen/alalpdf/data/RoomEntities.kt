package com.alalkipgen.alalpdf.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "recent_documents")
data class RecentDocumentEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val lastOpenedAt: Long,
    val lastReadPage: Int,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["documentUri", "pageIndex"], unique = true)]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentUri: String,
    val pageIndex: Int,
    val label: String?,
    val createdAt: Long,
)

/**
 * Durable reader position, independent from the Recent documents list.
 *
 * A document may be opened through more than one Android URI (SAF, MediaStore
 * or file://), so the progress store writes the same checkpoint under the
 * stable keys resolved for that document.
 */
@Entity(
    tableName = "reading_progress",
    indices = [Index(value = ["documentUri"])],
)
data class ReadingProgressEntity(
    @PrimaryKey val documentKey: String,
    val documentUri: String,
    val pageIndex: Int,
    val updatedAt: Long,
)