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

    @Test
    fun captureBatchRollsBackEarlierRowsWhenTheDestinationChanges() = runBlocking {
        val dao = createDatabase().transactionDao()
        val first = entity(updatedAt = 100, serverVersion = 0)
        val second = first.copy(syncId = UUID.randomUUID().toString(), amount = 25.0)
        var validations = 0
        val failed = runCatching {
            dao.insertAllValidated(listOf(first, second)) {
                validations++
                check(validations < 2) { "Destination changed" }
            }
        }
        assertTrue(failed.isFailure)
        assertNull(dao.getBySyncId(first.syncId))
        assertNull(dao.getBySyncId(second.syncId))
        assertEquals(2, dao.insertAllValidated(listOf(first, second)) {}.size)
        assertEquals(2, dao.getPendingSyncTransactions().size)
    }

    @Test
    fun captureBatchAlsoRollsBackWhenItsFinalDestinationCheckFails() = runBlocking {
        val dao = createDatabase().transactionDao()
        val first = entity(updatedAt = 100, serverVersion = 0)
        val second = first.copy(syncId = UUID.randomUUID().toString())
        var validations = 0
        val failed = runCatching {
            dao.insertAllValidated(listOf(first, second)) {
                validations++
                check(validations < 3) { "Destination changed before commit" }
            }
        }
        assertTrue(failed.isFailure)
        assertTrue(dao.getPendingSyncTransactions().isEmpty())
    }

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
    fun migration5To6PreservesRowsAndDefaultsProjectSelectionToUnspecified() {
        migrationHelper.createDatabase(TEST_DATABASE, 5).apply {
            execSQL(
                """
                INSERT INTO transactions (
                    id, amount, type, categoryId, merchantName, note, originalInput,
                    date, createdAt, updatedAt, source, status, syncStatus,
                    aiConfidence, aiRawResponse, syncId, serverVersion, deletedAt,
                    recordedByUserId, recordedByDisplayName, recordedByEmail
                ) VALUES (
                    1, 12.5, 'EXPENSE', NULL, 'shop', 'note', NULL,
                    100, 100, 100, 'MANUAL', 'CONFIRMED', 'SYNCED', NULL, NULL,
                    '0ec11d58-589d-40c5-bc30-e4524b539a2c', 8, NULL,
                    'writer-1', 'Cloud Writer', 'writer@example.test'
                )
                """.trimIndent()
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            6,
            true,
            Migrations.MIGRATION_5_6
        )
        migrated.query(
            "SELECT projectIdsState, projectIdsBlob, recordedByUserId, projectIdsWriteState, projectIdsWriteBlob, projectIdsWriteUpdatedAt FROM transactions WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("UNSPECIFIED", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertEquals("writer-1", cursor.getString(2))
            assertEquals("UNSPECIFIED", cursor.getString(3))
            assertTrue(cursor.isNull(4))
            assertEquals(0, cursor.getLong(5))
        }
    }

    @Test
    fun destinationGuardRunsInsideRoomTransactionBeforeInsert() = runBlocking {
        val dao = createDatabase().transactionDao()
        val transaction = entity(updatedAt = 100, serverVersion = 0)
        val failure = runCatching {
            dao.insertValidated(transaction) { throw IllegalStateException("destination changed") }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertNull(dao.getBySyncId(transaction.syncId))
    }

    @Test
    fun untouchedLabelsRemainPreserveAcrossConflictRetryAndRemoteOptOut() = runBlocking {
        val dao = createDatabase().transactionDao()
        val original = entity(updatedAt = 100, serverVersion = 8).copy(
            syncStatus = "SYNCED", projectIdsState = "EXPLICIT", projectIdsBlob = "old"
        )
        val id = dao.insert(original)
        dao.updateMonotonic(original.copy(
            id = id, note = "only note", updatedAt = 200, syncStatus = "PENDING_SYNC",
            projectIdsState = "UNSPECIFIED", projectIdsBlob = null
        ))
        assertEquals("old", dao.getBySyncId(original.syncId)!!.projectIdsBlob)
        assertEquals(1, dao.rebasePendingSync(original.syncId, 8, 9))
        val pending = dao.getPendingSyncTransactions().single()
        assertEquals("UNSPECIFIED", pending.projectIdsWriteState)
        assertNull(pending.projectIdsWriteBlob)
        dao.acknowledgeSync(original.syncId, 200, 9, 10, "EXPLICIT", "", null, null, null)
        val stored = dao.getBySyncId(original.syncId)!!
        assertEquals("", stored.projectIdsBlob)
        assertEquals("UNSPECIFIED", stored.projectIdsWriteState)
        assertEquals("only note", stored.note)
    }

    @Test
    fun acknowledgedTagIntentClearsEvenWhenANewerNoteIsPending() = runBlocking {
        val dao = createDatabase().transactionDao()
        val original = entity(updatedAt = 100, serverVersion = 8).copy(
            projectIdsState = "EXPLICIT", projectIdsBlob = "picked",
            projectIdsWriteState = "EXPLICIT", projectIdsWriteBlob = "picked",
            projectIdsWriteUpdatedAt = 100
        )
        val id = dao.insert(original)
        dao.updateMonotonic(original.copy(
            id = id, note = "later note", updatedAt = 200,
            projectIdsState = "UNSPECIFIED", projectIdsBlob = null
        ))
        dao.acknowledgeSync(original.syncId, 100, 8, 9, "EXPLICIT", "picked", null, null, null)
        val stored = dao.getBySyncId(original.syncId)!!
        assertEquals("PENDING_SYNC", stored.syncStatus)
        assertEquals("picked", stored.projectIdsBlob)
        assertEquals("UNSPECIFIED", stored.projectIdsWriteState)
        assertNull(stored.projectIdsWriteBlob)
    }

    @Test
    fun newerExplicitOptOutAndListBothSurviveOlderAcknowledgement() = runBlocking {
        val dao = createDatabase().transactionDao()
        for (explicit in listOf("", "picked")) {
            val original = entity(updatedAt = 100, serverVersion = 8).copy(syncId = UUID.randomUUID().toString())
            val id = dao.insert(original)
            dao.updateMonotonic(original.copy(
                id = id, updatedAt = 100, projectIdsState = "EXPLICIT", projectIdsBlob = explicit
            ))
            dao.acknowledgeSync(original.syncId, 100, 8, 9, "EXPLICIT", "older", null, null, null)
            val stored = dao.getBySyncId(original.syncId)!!
            assertEquals("PENDING_SYNC", stored.syncStatus)
            assertEquals(explicit, stored.projectIdsBlob)
            assertEquals("EXPLICIT", stored.projectIdsWriteState)
            assertEquals(explicit, stored.projectIdsWriteBlob)
            assertEquals(101, stored.projectIdsWriteUpdatedAt)
            assertEquals(0, dao.refreshProjectMetadata(original.syncId, "EXPLICIT", "metadata"))
        }
    }

    @Test
    fun projectMetadataBackfillUpdatesDisplayWithoutCreatingWriteIntent() = runBlocking {
        val dao = createDatabase().transactionDao()
        val original = entity(updatedAt = 100, serverVersion = 8).copy(syncStatus = "SYNCED")
        val id = dao.insert(original)
        assertEquals(1, dao.refreshProjectMetadata(original.syncId, "EXPLICIT", "backfilled"))
        dao.updateMonotonic(original.copy(id = id, note = "later note", updatedAt = 200, syncStatus = "PENDING_SYNC"))
        val stored = dao.getBySyncId(original.syncId)!!
        assertEquals("backfilled", stored.projectIdsBlob)
        assertEquals("UNSPECIFIED", stored.projectIdsWriteState)
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
            serverVersion = 9,
            projectIdsState = "UNSPECIFIED",
            projectIdsBlob = null,
            recordedByUserId = "writer-1",
            recordedByDisplayName = "Cloud Writer",
            recordedByEmail = "writer@example.test"
        )

        assertEquals(1, changed)
        val stored = dao.getBySyncId(original.syncId)!!
        assertEquals(9, stored.serverVersion)
        assertEquals("PENDING_SYNC", stored.syncStatus)
        assertEquals("new edit", stored.note)
        assertEquals("writer-1", stored.recordedByUserId)
        assertEquals("Cloud Writer", stored.recordedByDisplayName)
        assertEquals("writer@example.test", stored.recordedByEmail)
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
            serverVersion = 9,
            projectIdsState = "UNSPECIFIED",
            projectIdsBlob = null,
            recordedByUserId = "writer-1",
            recordedByDisplayName = "Cloud Writer",
            recordedByEmail = "writer@example.test"
        )

        val stored = dao.getBySyncId(original.syncId)!!
        assertEquals(101, stored.updatedAt)
        assertEquals("PENDING_SYNC", stored.syncStatus)
        assertEquals("new edit", stored.note)
        assertEquals("writer-1", stored.recordedByUserId)
        assertEquals("Cloud Writer", stored.recordedByDisplayName)
        assertEquals("writer@example.test", stored.recordedByEmail)
    }

    @Test
    fun localEditPreservesRecorderMetadataAndServerVersionFilledAfterSnapshotWasRead() = runBlocking {
        val dao = createDatabase().transactionDao()
        val original = entity(updatedAt = 100, serverVersion = 0)
        val id = dao.insert(original)
        val staleEdit = original.copy(
            id = id,
            note = "edited after ack",
            updatedAt = 200,
            serverVersion = 0,
            recordedByUserId = null,
            recordedByDisplayName = null,
            recordedByEmail = null
        )

        dao.acknowledgeSync(
            syncId = original.syncId,
            expectedUpdatedAt = 100,
            expectedServerVersion = 0,
            serverVersion = 9,
            projectIdsState = "UNSPECIFIED",
            projectIdsBlob = null,
            recordedByUserId = "writer-2",
            recordedByDisplayName = "Cloud Editor",
            recordedByEmail = "editor@example.test"
        )
        dao.updateMonotonic(staleEdit)

        val stored = dao.getBySyncId(original.syncId)!!
        assertEquals(9, stored.serverVersion)
        assertEquals("edited after ack", stored.note)
        assertEquals("PENDING_SYNC", stored.syncStatus)
        assertEquals("writer-2", stored.recordedByUserId)
        assertEquals("Cloud Editor", stored.recordedByDisplayName)
        assertEquals("editor@example.test", stored.recordedByEmail)
    }

    @Test
    fun localEditKeepsExplicitProjectSelectionWhenSyncMetadataRefreshesLater() = runBlocking {
        val dao = createDatabase().transactionDao()
        val original = entity(updatedAt = 100, serverVersion = 0)
        val id = dao.insert(original.copy(projectIdsState = "EXPLICIT", projectIdsBlob = "project-a"))
        val staleEdit = original.copy(
            id = id,
            note = "edited",
            updatedAt = 200,
            projectIdsState = "UNSPECIFIED",
            projectIdsBlob = null
        )

        dao.updateMonotonic(staleEdit)

        val stored = dao.getBySyncId(original.syncId)!!
        assertEquals("EXPLICIT", stored.projectIdsState)
        assertEquals("project-a", stored.projectIdsBlob)
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
