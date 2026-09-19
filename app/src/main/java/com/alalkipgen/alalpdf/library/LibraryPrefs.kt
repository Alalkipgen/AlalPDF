package com.alalkipgen.alalpdf.library

import android.content.Context

enum class LibrarySort { RECENT, NAME, DATE, SIZE }

/**
 * Lightweight local state that does not justify a Room migration:
 * favourites, hidden (removed) entries, cached page counts and sort order.
 */
class LibraryPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("alal_library", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()

    fun toggleFavorite(uri: String) {
        val current = favorites().toMutableSet()
        if (!current.add(uri)) current.remove(uri)
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
    }

    fun hidden(): Set<String> = prefs.getStringSet(KEY_HIDDEN, emptySet())?.toSet() ?: emptySet()

    fun hide(uris: Collection<String>) {
        val current = hidden().toMutableSet()
        current.addAll(uris)
        prefs.edit().putStringSet(KEY_HIDDEN, current).apply()
    }

    fun unhide(uri: String) {
        val current = hidden().toMutableSet()
        if (current.remove(uri)) prefs.edit().putStringSet(KEY_HIDDEN, current).apply()
    }

    /** A fresh scan should always show everything again, even after "Clear list". */
    fun clearHidden() {
        prefs.edit().putStringSet(KEY_HIDDEN, emptySet()).apply()
    }

    fun pageCount(uri: String): Int = prefs.getInt("pages:$uri", 0)

    fun setPageCount(uri: String, count: Int) {
        if (count > 0) prefs.edit().putInt("pages:$uri", count).apply()
    }

    var sort: LibrarySort
        get() = runCatching { LibrarySort.valueOf(prefs.getString(KEY_SORT, "") ?: "") }.getOrDefault(LibrarySort.RECENT)
        set(value) { prefs.edit().putString(KEY_SORT, value.name).apply() }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_HIDDEN = "hidden"
        const val KEY_SORT = "sort"
    }
}
