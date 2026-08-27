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
 * Guards the migrations against the failure mode that actually matters: an upgrade that leaves
 * the database in a shape the app cannot open.
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
            // The migration must not attempt to probe files; new columns start at zero.
            assertEquals(0L, c.getLong(2))
            assertEquals(0L, c.getLong(3))
            assertEquals(0L, c.getLong(4))
            assertEquals(1, c.count)
        }
    }

    /**
     * v4 moved the app from a local library to a mirror of the shared Appwrite catalogue.
     *
     * The old rows are deliberately dropped: a locally-imported clip has no `audio_metadata`
     * document, no `fileId` and no uploader, so there is nothing to carry it across to. What
     * this test pins down is that the migration leaves a *valid, empty* v4 table which the
     * first sync can then fill — not that the old data survives.
     */
    @Test
    fun migrate3To4_replacesTheLocalLibraryWithAnEmptyCatalogueMirror() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO audio_files (id, fileName, path, source, isNew, durationMs, sizeBytes, addedAt)
                VALUES (1, 'hello.mp3', '/data/hello.mp3', 'local', 1, 1000, 2048, 99)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        db.query("SELECT count(*) FROM audio_clips").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        // The new table must genuinely accept the shape the entity writes, or the first sync
        // after upgrade would crash instead of repopulating.
        db.execSQL(
            """
            INSERT INTO audio_clips
                (documentId, fileId, fileName, uploaderId, uploaderName, isNew,
                 durationMs, sizeBytes, createdAt, likeCount, dislikeCount, myReaction, cachedPath)
            VALUES ('doc1', 'file1', 'oi.mp3', 'u1', 'Ada', 1, 0, 1024, 5, 2, 1, 'like', NULL)
            """.trimIndent()
        )

        db.query("SELECT documentId, uploaderName, likeCount, myReaction FROM audio_clips").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("doc1", c.getString(0))
            assertEquals("Ada", c.getString(1))
            assertEquals(2, c.getInt(2))
            assertEquals("like", c.getString(3))
        }
    }

    @Test
    fun migrate1To4_runsTheWholeChainAndEndsOnAValidSchema() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO audio_files (id, fileName, path, source)
                VALUES (1, 'old.mp3', '/data/old.mp3', 'local')
                """.trimIndent()
            )
        }

        // Validation is the real assertion here: it fails if the chain leaves the database in
        // any shape other than exactly what the v4 entity declares.
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *ALL_MIGRATIONS)

        db.query("SELECT count(*) FROM audio_clips").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }
}
