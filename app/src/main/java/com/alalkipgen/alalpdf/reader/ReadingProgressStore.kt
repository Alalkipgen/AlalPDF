package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.net.Uri

class ReadingProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)
    fun page(uri: Uri): Int = preferences.getInt(uri.toString(), 0)
    fun save(uri: Uri, page: Int) { preferences.edit().putInt(uri.toString(), page).apply() }
}