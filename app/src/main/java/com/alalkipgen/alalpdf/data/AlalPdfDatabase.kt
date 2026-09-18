package com.alalkipgen.alalpdf.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RecentDocumentEntity::class, BookmarkEntity::class], version = 1, exportSchema = true)
abstract class AlalPdfDatabase : RoomDatabase() {
    abstract fun dao(): AlalPdfDao

    companion object {
        fun create(context: Context): AlalPdfDatabase = Room.databaseBuilder(
            context, AlalPdfDatabase::class.java, "alal_pdf.db"
        ).addCallback(LegacyPreferenceMigration(context)).build()
    }
}