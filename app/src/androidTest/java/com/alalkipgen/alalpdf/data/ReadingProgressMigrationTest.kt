package com.alalkipgen.alalpdf.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingProgressMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AlalPdfDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun v1ProgressSurvivesMigration() {
        val uri = "content://example.documents/document/book.pdf"
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO recent_documents
                    (uri, displayName, sizeBytes, lastModified, lastOpenedAt, lastReadPage)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(uri, "book.pdf", 1234L, 5678L, 9000L, 36),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            AlalPdfDatabase.MIGRATION_1_2,
        ).use { database ->
            database.query(
                """
                SELECT documentUri, pageIndex, updatedAt
                FROM reading_progress
                WHERE documentKey = ?
                """.trimIndent(),
                arrayOf("legacy-uri:$uri"),
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(uri, cursor.getString(0))
                assertEquals(36, cursor.getInt(1))
                assertEquals(9000L, cursor.getLong(2))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "reading-progress-migration-test"
    }
}