package com.aibookkeeper.core.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aibookkeeper.core.data.local.entity.TransactionEntity
import com.aibookkeeper.core.data.local.migration.Migrations
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransactionSyncIntegrationTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private var database: AppDatabase? = null

    @After
    fun closeDatabase() {
        database?.close()
    }

    @Test
    fun migration3To4PreservesRowsAndCreatesRfc4122Ids() {
        migrationHelper.createDatabase(TEST_DATABASE, 3).apply {
            execSQL(
                """
                INSERT INTO transactions (
                    id, amount, type, categoryId, merchantName, note, originalInput,
                    date, createdAt, updatedAt, source, status, syncStatus,
                    aiConfidence, aiRawResponse
                ) VALUES (
                    1, 12.5, 'EXPENSE', NULL, 'shop', NULL, NULL,
                    100, 100, 100, 'MANUAL', 'CONFIRMED', 'LOCAL', NULL, NULL
                )
                """.trimIndent()
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            4,
            true,
            Migrations.MIGRATION_3_4
        )
        migrated.query(
            "SELECT amount, syncId, serverVersion, deletedAt FROM transactions WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(12.5, cursor.getDouble(0), 0.0)
            val uuid = UUID.fromString(cursor.getString(1))
            assertEquals(4, uuid.version())
            assertEquals(2, uuid.variant())
            assertEquals(0, cursor.getLong(2))
            assertTrue(cursor.isNull(3))
        }
    }

    @Test
    fun acceptedOldSnapshotAdvancesBaselineWithoutClearingNewerEdit() = runBlocking {
        val dao = createDatabase().transactionDao()
        val original = entity(updatedAt = 100, serverVersion = 0)
        val id = dao.insert(original)
        dao.updateMonotonic(original.copy(id = id, note = "new edit", updatedAt = 200))

        val changed = dao.acknowledgeSync(
            syncId = original.syncId,
            expectedUpdatedAt = 100,
            expectedServerVersion = 0,
            serverVersion = 9
        )

        assertEquals(1, changed)
        val stored = dao.getBySyncId(original.syncId)!!
        assertEquals(9, stored.serverVersion)
        assertEquals("PENDING_SYNC", stored.syncStatus)
        assertEquals("new edit", stored.note)
    }

    @Test
    fun sameMillisecondEditCannotBeAcknowledgedAsUploadedSnapshot() = runBlocking {
        val dao = createDatabase().transactionDao()
        val original = entity(updatedAt = 100, serverVersion = 0)
        val id = dao.insert(original)
        dao.updateMonotonic(original.copy(id = id, note = "new edit", updatedAt = 100))

        dao.acknowledgeSync(
            syncId = original.syncId,
            expectedUpdatedAt = 100,
            expectedServerVersion = 0,
            serverVersion = 9
        )

        val stored = dao.getBySyncId(original.syncId)!!
        assertEquals(101, stored.updatedAt)
        assertEquals("PENDING_SYNC", stored.syncStatus)
        assertEquals("new edit", stored.note)
    }

    @Test
    fun pullNeverOverwritesPendingLocalRecord() = runBlocking {
        val dao = createDatabase().transactionDao()
        val pending = entity(updatedAt = 200, serverVersion = 4, note = "local")
        val id = dao.insert(pending)

        val merged = dao.mergeRemote(
            pending.copy(
                id = 0,
                note = "remote",
                serverVersion = 5,
                syncStatus = "SYNCED"
            )
        )

        assertFalse(merged)
        val stored = dao.getBySyncId(pending.syncId)!!
        assertEquals(id, stored.id)
        assertEquals("local", stored.note)
        assertEquals(4, stored.serverVersion)
    }

    @Test
    fun remoteTombstoneIsRetainedButHiddenFromUserQueries() = runBlocking {
        val dao = createDatabase().transactionDao()
        val tombstone = entity(
            updatedAt = 300,
            serverVersion = 6,
            deletedAt = 300,
            syncStatus = "SYNCED"
        )

        assertTrue(dao.mergeRemote(tombstone))

        val stored = dao.getBySyncId(tombstone.syncId)!!
        assertNull(dao.getById(stored.id))
        assertEquals(300L, stored.deletedAt)
    }

    @Test
    fun newEntriesAndTimestampTiesUseTheSameChronologyInEveryDetailQuery() = runBlocking {
        val dao = createDatabase().transactionDao()
        val original = entity(updatedAt = 9_999, serverVersion = 0, note = "chronology")
        val records = listOf(
            original.copy(syncId = "old", date = 10_000, createdAt = 100),
            original.copy(syncId = "a", date = 10_000, createdAt = 200, updatedAt = 200),
            original.copy(syncId = "b", date = 10_000, createdAt = 200, updatedAt = 200),
            original.copy(syncId = "backdated", date = 9_000, createdAt = 600),
            original.copy(syncId = "latest", date = 20_000, createdAt = 600)
        )
        records.forEach { dao.insert(it) }
        val expected = listOf("latest", "b", "a", "old", "backdated")
        assertEquals(expected, dao.observeByDateRange(0, 30_000).first().map { it.syncId })
        assertEquals(expected, dao.observeByDateRangeAndType(0, 30_000, "EXPENSE").first().map { it.syncId })
        assertEquals(expected, dao.observeByCategoryAndDateRange(null, 0, 30_000).first().map { it.syncId })
        assertEquals(expected, dao.search("chronology").map { it.syncId })
    }

    private fun createDatabase(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
    }

    private fun entity(
        updatedAt: Long,
        serverVersion: Long,
        note: String? = null,
        deletedAt: Long? = null,
        syncStatus: String = "PENDING_SYNC"
    ) = TransactionEntity(
        amount = 12.5,
        type = "EXPENSE",
        categoryId = null,
        merchantName = "shop",
        note = note,
        date = 100,
        createdAt = 100,
        updatedAt = updatedAt,
        source = "MANUAL",
        status = "CONFIRMED",
        syncStatus = syncStatus,
        syncId = "0ec11d58-589d-40c5-bc30-e4524b539a2c",
        serverVersion = serverVersion,
        deletedAt = deletedAt
    )

    private companion object {
        const val TEST_DATABASE = "sync-migration-test"
    }
}
