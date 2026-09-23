package com.alalkipgen.alalpdf.reader

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.alalkipgen.alalpdf.data.AlalPdfRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class ReadingProgressStore(
    context: Context,
    private val repository: AlalPdfRepository,
) {
    private val identityResolver = DocumentIdentityResolver(context)
    private val identities = ConcurrentHashMap<String, ResolvedDocumentIdentity>()
    private val checkpointTimes = ConcurrentHashMap<String, Long>()
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    suspend fun page(uri: Uri): Int {
        val identity = identityResolver.resolve(uri)
        identities[uri.toString()] = identity

        val journal = latestJournal(identity)
        val database = repository.readingProgress(identity.keys)
        val durable = listOfNotNull(
            journal,
            database?.let { ProgressRecord(it.pageIndex, it.updatedAt) },
        ).maxByOrNull(ProgressRecord::updatedAt)

        val legacyCheckpoint = preferences
            .getInt(legacyPageKey(uri), PAGE_NOT_SET)
            .takeIf { it != PAGE_NOT_SET }
        val page = (
            durable?.page
                ?: legacyCheckpoint
                ?: repository.page(uri)
            ).coerceAtLeast(0)

        // Complete migration immediately so future process starts no longer
        // depend on a provider-specific URI or an untimestamped preference.
        val migratedAt = durable?.updatedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
        writeJournal(identity, page, migratedAt, synchronous = false)
        checkpointTimes[uri.toString()] = migratedAt
        repository.saveReadingProgress(identity.keys, uri, page, migratedAt)
        return page
    }

    private fun latestJournal(identity: ResolvedDocumentIdentity): ProgressRecord? =
        identity.keys.mapNotNull { key ->
            val page = preferences.getInt(pageKey(key), PAGE_NOT_SET)
            if (page == PAGE_NOT_SET) {
                null
            } else {
                ProgressRecord(
                    page = page.coerceAtLeast(0),
                    updatedAt = preferences.getLong(updatedAtKey(key), 0L),
                )
            }
        }.maxByOrNull(ProgressRecord::updatedAt)

    private fun writeJournal(
        identity: ResolvedDocumentIdentity,
        page: Int,
        updatedAt: Long,
        synchronous: Boolean,
    ): Boolean {
        val editor = preferences.edit()
        identity.keys.forEach { key ->
            editor.putInt(pageKey(key), page.coerceAtLeast(0))
            editor.putLong(updatedAtKey(key), updatedAt)
        }
        return if (synchronous) editor.commit() else {
            editor.apply()
            true
        }
    }

    private fun identityFor(uri: Uri): ResolvedDocumentIdentity =
        identities[uri.toString()]
            ?: ResolvedDocumentIdentity(listOf(DocumentIdentityResolver.legacyUriKey(uri)))

    /**
     * Persists the current page synchronously so Android cannot lose it when
     * the task is swiped from Recents immediately after the reader stops.
     *
     * Reserved for ON_STOP and for leaving the reader. It must never run while
     * the user is scrolling: commit() does a blocking fsync, and calling it for
     * every page a fling passes froze the main thread for seconds at a time.
     */
    @SuppressLint("ApplySharedPref")
    fun checkpoint(uri: Uri, page: Int): Boolean {
        val uriString = uri.toString()
        val identity = identityFor(uri)
        val updatedAt = System.currentTimeMillis()
        checkpointTimes[uriString] = updatedAt
        return writeJournal(identity, page, updatedAt, synchronous = true)
    }

    /**
     * Non-blocking checkpoint used while reading.
     *
     * apply() keeps the value in memory immediately and lets Android flush it
     * on its own background thread, which is all the reader needs between
     * pages. The durable commit() still happens when the reader stops.
     */
    fun checkpointAsync(uri: Uri, page: Int) {
        val uriString = uri.toString()
        val identity = identityFor(uri)
        val updatedAt = System.currentTimeMillis()
        checkpointTimes[uriString] = updatedAt
        writeJournal(identity, page, updatedAt, synchronous = false)
    }

    suspend fun syncToDatabase(uri: Uri, page: Int) = withContext(NonCancellable) {
        val uriString = uri.toString()
        val identity = identities[uriString] ?: identityResolver.resolve(uri).also {
            identities[uriString] = it
        }
        val updatedAt = checkpointTimes[uriString] ?: System.currentTimeMillis()
        repository.saveReadingProgress(identity.keys, uri, page, updatedAt)
        // Keep the library's progress indicator compatible with existing rows.
        repository.savePage(uri, page.coerceAtLeast(0))
    }

    private data class ProgressRecord(val page: Int, val updatedAt: Long)

    private fun pageKey(documentKey: String): String = PAGE_KEY_PREFIX + documentKey
    private fun updatedAtKey(documentKey: String): String = UPDATED_AT_KEY_PREFIX + documentKey
    private fun legacyPageKey(uri: Uri): String = LEGACY_PAGE_KEY_PREFIX + uri.toString()

    private companion object {
        const val PREFERENCES_NAME = "reader_progress"
        const val PAGE_KEY_PREFIX = "v2-page:"
        const val UPDATED_AT_KEY_PREFIX = "v2-updated:"
        const val LEGACY_PAGE_KEY_PREFIX = "page:"
        const val PAGE_NOT_SET = -1
    }
}
