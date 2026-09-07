package com.aibookkeeper.core.data.local.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrationsTest {

    @Test
    fun `migration 3 to 4 preserves local transactions`() {
        val statements = mutableListOf<String>()
        val database = mockk<SupportSQLiteDatabase>()
        every { database.execSQL(capture(statements)) } just runs

        Migrations.MIGRATION_3_4.migrate(database)

        val sql = statements.joinToString("\n").uppercase()
        assertFalse(sql.contains("DROP TABLE"))
        assertFalse(Regex("\\bDELETE\\b").containsMatchIn(sql))
        assertTrue(sql.contains("ALTER TABLE TRANSACTIONS ADD COLUMN SYNCID"))
        assertTrue(sql.contains("UPDATE TRANSACTIONS"))
        assertTrue(sql.contains("CREATE UNIQUE INDEX"))
        assertTrue(sql.contains("'4' || SUBSTR(HEX(RANDOMBLOB(2)), 2, 3)"))
        assertTrue(sql.contains("SUBSTR('89AB', 1 + ABS(RANDOM() % 4), 1)"))
    }

    @Test
    fun `migration 4 to 5 adds additive recorded-by columns only`() {
        val statements = mutableListOf<String>()
        val database = mockk<SupportSQLiteDatabase>()
        every { database.execSQL(capture(statements)) } just runs

        Migrations.MIGRATION_4_5.migrate(database)

        val sql = statements.joinToString("\n").uppercase()
        assertFalse(sql.contains("DROP TABLE"))
        assertFalse(Regex("\\bDELETE\\b").containsMatchIn(sql))
        assertTrue(sql.contains("ALTER TABLE TRANSACTIONS ADD COLUMN RECORDEDBYUSERID"))
        assertTrue(sql.contains("ALTER TABLE TRANSACTIONS ADD COLUMN RECORDEDBYDISPLAYNAME"))
        assertTrue(sql.contains("ALTER TABLE TRANSACTIONS ADD COLUMN RECORDEDBYEMAIL"))
    }

    @Test
    fun `migration 5 to 6 adds additive project columns only`() {
        val statements = mutableListOf<String>()
        val database = mockk<SupportSQLiteDatabase>()
        every { database.execSQL(capture(statements)) } just runs

        Migrations.MIGRATION_5_6.migrate(database)

        val sql = statements.joinToString("\n").uppercase()
        assertFalse(sql.contains("DROP TABLE"))
        assertFalse(Regex("\\bDELETE\\b").containsMatchIn(sql))
        assertTrue(sql.contains("ALTER TABLE TRANSACTIONS ADD COLUMN PROJECTIDSSTATE"))
        assertTrue(sql.contains("ALTER TABLE TRANSACTIONS ADD COLUMN PROJECTIDSBLOB"))
        assertTrue(sql.contains("ALTER TABLE TRANSACTIONS ADD COLUMN PROJECTIDSWRITESTATE"))
        assertTrue(sql.contains("ALTER TABLE TRANSACTIONS ADD COLUMN PROJECTIDSWRITEBLOB"))
        assertTrue(sql.contains("ALTER TABLE TRANSACTIONS ADD COLUMN PROJECTIDSWRITEUPDATEDAT"))
    }
}
