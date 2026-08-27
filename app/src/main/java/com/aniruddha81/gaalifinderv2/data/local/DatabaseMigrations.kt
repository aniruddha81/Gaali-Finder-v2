package com.aniruddha81.gaalifinderv2.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 -> v2: added the "new clip" badge flag. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE audio_files ADD COLUMN isNew INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * v2 -> v3: added duration/size/added-at, plus lookup indices.
 *
 * Existing rows get 0 for the new columns; `backfillMissingMetadata()` fills them in later by
 * probing the files, so the migration itself stays fast and cannot fail on a missing file.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE audio_files ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE audio_files ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE audio_files ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audio_files_fileName ON audio_files (fileName)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audio_files_source ON audio_files (source)")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
