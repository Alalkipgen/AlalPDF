package com.alalkipgen.alalpdf.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfLibraryRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    suspend fun inspect(uri: Uri): PdfDocument = withContext(Dispatchers.IO) {
        queryDocument(uri) ?: error("Unable to read PDF metadata")
    }

    suspend fun listFolder(treeUri: Uri): List<PdfDocument> = withContext(Dispatchers.IO) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val result = mutableListOf<PdfDocument>()
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                queryDocument(childUri)?.takeIf { it.name.endsWith(".pdf", ignoreCase = true) }?.let(result::add)
            }
        }
        result.sortedBy { it.name.lowercase() }
    }

    fun persistReadPermission(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers grant temporary access only.
        }
    }

    fun hasPersistedReadPermission(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun queryDocument(uri: Uri): PdfDocument? {
        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) ?: return@use null
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
            if (mime != "application/pdf" && !name.endsWith(".pdf", ignoreCase = true)) return@use null
            PdfDocument(
                uri = uri,
                name = name,
                sizeBytes = cursor.getLongOrNull(OpenableColumns.SIZE) ?: 0L,
                lastModified = cursor.getLongOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED) ?: 0L
            )
        }
    }
}

private fun android.database.Cursor.getLongOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}