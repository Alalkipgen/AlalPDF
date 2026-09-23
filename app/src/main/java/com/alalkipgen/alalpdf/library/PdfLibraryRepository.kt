package com.alalkipgen.alalpdf.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

class PdfLibraryRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    suspend fun inspect(uri: Uri): PdfDocument = withContext(Dispatchers.IO) {
        queryDocument(uri) ?: error("Unable to read PDF metadata")
    }

    /** Recursively scans a user-approved Storage Access Framework folder. */
    suspend fun listFolder(treeUri: Uri): List<PdfDocument> = withContext(Dispatchers.IO) {
        val result = linkedMapOf<String, PdfDocument>()
        val pending = ArrayDeque<String>()
        pending.add(DocumentsContract.getTreeDocumentId(treeUri))

        while (pending.isNotEmpty()) {
            val parentId = pending.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex) ?: continue
                    val mime = cursor.getString(mimeIndex)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(id)
                    } else if (mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        result[uri.toString()] = PdfDocument(
                            uri = uri,
                            name = name,
                            sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L,
                            lastModified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else 0L,
                        )
                    }
                }
            }
        }
        result.values.sortedWith(compareByDescending<PdfDocument> { it.lastModified }.thenBy { it.name.lowercase() })
    }

    fun persistReadWritePermission(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) { persistReadPermission(uri)
        } catch (_: IllegalArgumentException) { persistReadPermission(uri) }
    }

    fun persistReadPermission(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // ACTION_VIEW and some providers grant access only for this activity session.
        } catch (_: IllegalArgumentException) {
            // The provider does not support persistable grants.
        }
    }

    fun canRead(uri: Uri): Boolean = try {
        resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    suspend fun canReadAsync(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        canRead(uri)
    }

    suspend fun partitionReadable(
        documents: List<PdfDocument>,
    ): Pair<List<PdfDocument>, List<PdfDocument>> = withContext(Dispatchers.IO) {
        documents.partition { canRead(it.uri) }
    }

    fun hasPersistedReadPermission(uri: Uri): Boolean {
        val target = uri.toString()
        return resolver.persistedUriPermissions.any {
            it.isReadPermission && (it.uri == uri || target.startsWith(it.uri.toString()))
        }
    }

    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        when {
            uri.scheme == "file" -> uri.path?.let { File(it).delete() } ?: false
            DocumentsContract.isDocumentUri(context, uri) ->
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
            else -> runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
        }
    }

    suspend fun rename(uri: Uri, newName: String): Uri? = withContext(Dispatchers.IO) {
        val safeName = if (newName.endsWith(".pdf", ignoreCase = true)) newName else "$newName.pdf"
        when {
            uri.scheme == "file" -> {
                val source = uri.path?.let { File(it) } ?: return@withContext null
                val target = File(source.parentFile, safeName)
                if (source.renameTo(target)) Uri.fromFile(target) else null
            }
            DocumentsContract.isDocumentUri(context, uri) ->
                runCatching { DocumentsContract.renameDocument(resolver, uri, safeName) }.getOrNull()
            else -> null
        }
    }

    private fun queryDocument(uri: Uri): PdfDocument? {
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) } ?: return null
            if (!file.exists()) return null
            return PdfDocument(uri, file.name, file.length(), file.lastModified())
        }
        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex < 0) return@use null
            val name = cursor.getString(nameIndex) ?: return@use null
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val mime = if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) cursor.getString(mimeIndex) else resolver.getType(uri)
            if (mime != "application/pdf" && !name.endsWith(".pdf", ignoreCase = true)) return@use null
            PdfDocument(
                uri = uri,
                name = name,
                sizeBytes = cursor.getLongOrNull(OpenableColumns.SIZE) ?: 0L,
                lastModified = cursor.getLongOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED) ?: 0L,
            )
        }
    }
}

private fun android.database.Cursor.getLongOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}
