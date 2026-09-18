package com.alalkipgen.alalpdf.data

import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray

class LegacyPreferenceMigration(private val context: Context) : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        val prefs = context.getSharedPreferences("room_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        val now = System.currentTimeMillis()
        val recent = context.getSharedPreferences("recent_documents", Context.MODE_PRIVATE)
        runCatching {
            val array = JSONArray(recent.getString("entries", "[]"))
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                db.execSQL("INSERT OR REPLACE INTO recent_documents(uri, displayName, sizeBytes, lastModified, lastOpenedAt, lastReadPage) VALUES(?, ?, ?, ?, ?, ?)", arrayOf(item.getString("uri"), item.getString("name"), item.optLong("size"), item.optLong("modified"), now - i, 0))
            }
        }
        val progress = context.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)
        progress.all.forEach { (uri, page) ->
            db.execSQL("UPDATE recent_documents SET lastReadPage = ? WHERE uri = ?", arrayOf((page as? Int) ?: 0, uri))
        }
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }

    private companion object { const val KEY_DONE = "legacy_preferences_migrated_v1" }
}