package com.aibookkeeper.core.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    // Future migrations go here
    // val MIGRATION_1_2 = object : Migration(1, 2) {
    //     override fun migrate(db: SupportSQLiteDatabase) {
    //         db.execSQL("ALTER TABLE transactions ADD COLUMN newField TEXT DEFAULT NULL")
    //     }
    // }

    val ALL: Array<Migration> = arrayOf(
        // Add migrations in order
    )
}
