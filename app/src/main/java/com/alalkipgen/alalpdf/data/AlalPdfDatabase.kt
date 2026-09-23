package com.alalkipgen.alalpdf.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecentDocumentEntity::class,
        BookmarkEntity::class,
        ReadingProgressEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AlalPdfDatabase : RoomDatabase() {
    abstract fun dao(): AlalPdfDao

    companion object {
        fun create(context: Context): AlalPdfDatabase = Room.databaseBuilder(
            context, AlalPdfDatabase::class.java, "alal_pdf.db"
        )
            .addMigrations(MIGRATION_1_2)
            .addCallback(LegacyPreferenceMigration(context))
            .build()

        /**
         * Keep every v1 checkpoint. The first v2 read also resolves and writes
         * provider/document aliases, so this URI key is only the migration
         * bridge and is never the long-term identity.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reading_progress` (
                        `documentKey` TEXT NOT NULL,
                        `documentUri` TEXT NOT NULL,
                        `pageIndex` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`documentKey`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_reading_progress_documentUri`
                    ON `reading_progress` (`documentUri`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `reading_progress`
                        (`documentKey`, `documentUri`, `pageIndex`, `updatedAt`)
                    SELECT
                        'legacy-uri:' || `uri`,
                        `uri`,
                        `lastReadPage`,
                        `lastOpenedAt`
                    FROM `recent_documents`
                    """.trimIndent(),
                )
            }
        }
    }
}