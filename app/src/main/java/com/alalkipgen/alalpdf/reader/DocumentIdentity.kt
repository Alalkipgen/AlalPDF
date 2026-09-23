package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal data class ResolvedDocumentIdentity(val keys: List<String>) {
    init {
        require(keys.isNotEmpty())
    }

    val primaryKey: String get() = keys.first()
}

/**
 * Resolves aliases for one physical document without reading its full content.
 *
 * Provider/document IDs survive process death and renames. A metadata key lets
 * MediaStore, SAF and file:// aliases meet when they describe the same file.
 * The legacy URI key keeps all pre-v2 progress readable during migration.
 */
internal class DocumentIdentityResolver(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun resolve(uri: Uri): ResolvedDocumentIdentity = withContext(Dispatchers.IO) {
        val keys = linkedSetOf<String>()

        if (DocumentsContract.isDocumentUri(appContext, uri)) {
            runCatching { DocumentsContract.getDocumentId(uri) }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { documentId ->
                    keys += digestKey("document", "${uri.authority.orEmpty()}|$documentId")
                }
        }

        when (uri.scheme?.lowercase()) {
            "file" -> {
                uri.path?.let(::File)?.let { file ->
                    runCatching { file.canonicalPath }.getOrNull()?.let { path ->
                        keys += digestKey("file", path)
                    }
                    metadataKey(
                        name = file.name,
                        size = runCatching { file.length() }.getOrDefault(0L),
                        modified = runCatching { file.lastModified() }.getOrDefault(0L),
                    )?.let(keys::add)
                }
            }

            "content" -> {
                queryMetadata(uri)?.let { metadata ->
                    metadataKey(metadata.name, metadata.size, metadata.modified)?.let(keys::add)
                }
                val mediaId = uri.lastPathSegment
                if (uri.authority?.contains("media", ignoreCase = true) == true &&
                    !mediaId.isNullOrBlank()
                ) {
                    keys += digestKey("media", "${uri.authority}|$mediaId")
                }
            }
        }

        // This exact value is also created by the Room v1 -> v2 migration.
        keys += legacyUriKey(uri)
        ResolvedDocumentIdentity(keys.toList())
    }

    private fun queryMetadata(uri: Uri): Metadata? = runCatching {
        resolver.query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val modifiedIndex =
                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            Metadata(
                name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    cursor.getString(nameIndex)
                } else {
                    uri.lastPathSegment.orEmpty()
                },
                size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    0L
                },
                modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                    normalizeModified(cursor.getLong(modifiedIndex))
                } else {
                    0L
                },
            )
        }
    }.getOrNull()

    private data class Metadata(
        val name: String,
        val size: Long,
        val modified: Long,
    )

    companion object {
        internal fun legacyUriKey(uri: Uri): String = "legacy-uri:$uri"

        internal fun metadataKey(name: String, size: Long, modified: Long): String? {
            val normalizedName = name.trim().lowercase().replace(Regex("\\s+"), " ")
            if (normalizedName.isBlank() || size <= 0L) return null
            val normalizedModified = normalizeModified(modified)
            val bucket = if (normalizedModified > 0L) normalizedModified / 2_000L else 0L
            return digestKey("metadata", "$normalizedName|$size|$bucket")
        }

        private fun normalizeModified(value: Long): Long =
            if (value in 1..9_999_999_999L) value * 1_000L else value.coerceAtLeast(0L)

        private fun digestKey(namespace: String, value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
            return "$namespace:$digest"
        }
    }
}