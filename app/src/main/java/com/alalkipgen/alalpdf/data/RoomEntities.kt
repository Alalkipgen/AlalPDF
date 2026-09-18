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