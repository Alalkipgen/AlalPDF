package com.alalkipgen.alalpdf.library

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

class RecentDocumentsStore(context: Context) {
    private val preferences = context.getSharedPreferences("recent_documents", Context.MODE_PRIVATE)

    fun add(document: PdfDocument) {
        val entries = recent().filterNot { it.uri == document.uri }.toMutableList()
        entries.add(0, document)
        preferences.edit().putString(KEY_ENTRIES, JSONArray(entries.take(MAX_ENTRIES).map { it.toJson() }).toString()).apply()
    }

    fun recent(): List<PdfDocument> = runCatching {
        val array = JSONArray(preferences.getString(KEY_ENTRIES, "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PdfDocument(Uri.parse(item.getString("uri")), item.getString("name"), item.getLong("size"), item.getLong("modified"))
        }
    }.getOrDefault(emptyList())

    private fun PdfDocument.toJson() = JSONObject().apply {
        put("uri", uri.toString()); put("name", name); put("size", sizeBytes); put("modified", lastModified)
    }

    private companion object { const val KEY_ENTRIES = "entries"; const val MAX_ENTRIES = 20 }
}