package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.net.Uri
import com.alalkipgen.alalpdf.data.AlalPdfRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ReadingProgressStore(
    context: Context,
    private val repository: AlalPdfRepository,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    suspend fun page(uri: Uri): Int {
        val key = pageKey(uri)
        val checkpoint = preferences.getInt(key, PAGE_NOT_SET)
        if (checkpoint != PAGE_NOT_SET) return checkpoint.coerceAtLeast(0)

        return repository.page(uri).coerceAtLeast(0).also { page ->
            preferences.edit().putInt(key, page).apply()
        }
    }

    /**
     * Persists the current page synchronously so Android cannot lose it when
     * the task is swiped from Recents immediately after the reader stops.
     *
     * The value is tiny and this is called only after a page settles, not for
     * every scroll pixel.
     */
    fun checkpoint(uri: Uri, page: Int): Boolean =
        preferences.edit()
            .putInt(pageKey(uri), page.coerceAtLeast(0))
            .commit()

    suspend fun syncToDatabase(uri: Uri, page: Int) = withContext(NonCancellable) {
        repository.savePage(uri, page.coerceAtLeast(0))
    }

    private fun pageKey(uri: Uri): String = PAGE_KEY_PREFIX + uri.toString()

    private companion object {
        const val PREFERENCES_NAME = "reader_progress"
        const val PAGE_KEY_PREFIX = "page:"
        const val PAGE_NOT_SET = -1
    }
}