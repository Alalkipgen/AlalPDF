package com.alalkipgen.alalpdf.reader

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.alalkipgen.alalpdf.data.AlalPdfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ReadingProgressStore(
    context: Context,
    private val repository: AlalPdfRepository,
) {
    private val identityResolver = DocumentIdentityResolver(context)
    private val identities = ConcurrentHashMap<String, ResolvedDocumentIdentity>()
    private val checkpointTimes = ConcurrentHashMap<String, Long>()
    private val syncTokens = ConcurrentHashMap<String, Long>()
    private val syncSequence = AtomicLong(0)
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
        return if (synchronous) {
            editor.commit()
        } else {
            editor.apply()
            true
        }
    }

    private fun identityFor(uri: Uri): ResolvedDocumentIdentity =
        identities[uri.toString()]
            ?: ResolvedDocumentIdentity(listOf(DocumentIdentityResolver.legacyUriKey(uri)))

    /**
     * Records the current page.
     *
     * This is called from `onPageSelected`, which fires for every page the
     * viewport crosses. It used to call `SharedPreferences.commit()`, a
     * blocking fsync, so flinging through a long document performed one
     * synchronous disk write per page *on the main thread*. That is what made
     * the scroll seize up and then the watchdog kill the process.
     *
     * `apply()` publishes the value to the in-memory map immediately - so any
     * subsequent [page] read sees it - and Android's QueuedWork forces the
     * flush to complete at the next activity lifecycle transition, which is
     * exactly when durability actually matters.
     */
    fun checkpoint(uri: Uri, page: Int): Boolean {
        val uriString = uri.toString()
        val identity = identityFor(uri)
        val updatedAt = System.currentTimeMillis()
        checkpointTimes[uriString] = updatedAt
        return writeJournal(identity, page, updatedAt, synchronous = false)
    }

    /** Alias kept for call sites that want to state the intent explicitly. */
    fun checkpointAsync(uri: Uri, page: Int) {
        checkpoint(uri, page)
    }

    /**
     * Hard fsync variant. Only worth using off the scroll path, for example
     * before handing the document to another process.
     */
    @SuppressLint("ApplySharedPref")
    fun checkpointBlocking(uri: Uri, page: Int): Boolean {
        val uriString = uri.toString()
        val identity = identityFor(uri)
        val updatedAt = System.currentTimeMillis()
        checkpointTimes[uriString] = updatedAt
        return writeJournal(identity, page, updatedAt, synchronous = true)
    }

    /**
     * Mirrors the checkpoint into Room.
     *
     * Two changes matter here. The dispatcher is pinned instead of inherited:
     * callers use `rememberCoroutineScope()`, whose dispatcher is Main, so
     * every page change previously ran the Room writes on the UI thread.
     * And consecutive calls are debounced, because crossing thirty pages only
     * needs the last position to reach the database.
     */
    suspend fun syncToDatabase(uri: Uri, page: Int) =
        withContext(Dispatchers.IO + NonCancellable) {
            val uriString = uri.toString()
            val token = syncSequence.incrementAndGet()
            syncTokens[uriString] = token
            delay(SYNC_DEBOUNCE_MS)
            if (syncTokens[uriString] != token) return@withContext

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

        /** Long enough to absorb a fling, short enough to survive a back press. */
        const val SYNC_DEBOUNCE_MS = 350L
    }
}
