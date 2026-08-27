package com.aniruddha81.gaalifinderv2.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the migrations against the one failure mode that actually matters here: a user's
 * imported clips being lost or orphaned by a schema change.
 *
 * Requires a connected device or emulator — run with `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AudioDatabaseMigrationTest {

    private companion object {
        const val TEST_DB = "migration-test"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AudioDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate2To3_keepsExistingRowsAndDefaultsNewColumns() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO audio_files (id, fileName, path, source, isNew)
                VALUES (1, 'hello.mp3', '/data/hello.mp3', 'local', 1)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        db.query("SELECT id, fileName, durationMs, sizeBytes, addedAt FROM audio_files").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("hello.mp3", c.getString(1))
            // The migration must not attempt to probe files; new columns start at zero and are
            // backfilled later by the repository.
            assertEquals(0L, c.getLong(2))
            assertEquals(0L, c.getLong(3))
            assertEquals(0L, c.getLong(4))
            assertEquals(1, c.count)
        }
    }

    @Test
    fun migrate1To3_runsTheWholeChainWithoutLosingRows() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO audio_files (id, fileName, path, source)
                VALUES (1, 'old.mp3', '/data/old.mp3', 'local')
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *ALL_MIGRATIONS)

        db.query("SELECT fileName, isNew FROM audio_files").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("old.mp3", c.getString(0))
            // v1 rows predate the badge, so they default to "new".
            assertEquals(1, c.getInt(1))
        }
    }
}
