package com.aniruddha81.gaalifinderv2.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE audio_files ADD COLUMN isNew INTEGER NOT NULL DEFAULT 1")
    }
}