package com.aibookkeeper.core.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS raw_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceApp TEXT NOT NULL,
                    rawContent TEXT NOT NULL,
                    contentHash TEXT NOT NULL,
                    capturedAt INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    transactionId INTEGER,
                    extractionError TEXT,
                    retryCount INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_raw_events_sourceApp ON raw_events (sourceApp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_raw_events_status ON raw_events (status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_raw_events_capturedAt ON raw_events (capturedAt)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_raw_events_contentHash ON raw_events (contentHash)")
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2
    )
}
