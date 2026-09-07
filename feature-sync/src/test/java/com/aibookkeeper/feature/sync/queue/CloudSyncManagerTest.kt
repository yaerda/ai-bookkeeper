package com.aibookkeeper.feature.sync.queue

import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.feature.sync.auth.AccessToken
import com.aibookkeeper.feature.sync.auth.AuthenticationRequiredException
import com.aibookkeeper.feature.sync.auth.TokenProvider
import com.aibookkeeper.feature.sync.network.PullResponse
import com.aibookkeeper.feature.sync.network.PushResponse
import com.aibookkeeper.feature.sync.network.PushRequest
import com.aibookkeeper.feature.sync.network.SyncApi
import com.aibookkeeper.feature.sync.network.SyncTransactionDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.time.LocalDateTime
import java.util.UUID

class CloudSyncManagerTest {

    private val repository = mockk<TransactionRepository>()
    private val api = mockk<SyncApi>()
    private val tokenProvider = mockk<TokenProvider>()
    private val preferences = mockk<SyncPreferences>()
    private val categorySync = mockk<LedgerCategorySync>()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var manager: CloudSyncManager

    private val local = Transaction(
        id = 4,
        amount = 28.5,
        type = TransactionType.EXPENSE,
        categoryId = 2,
        categoryName = "餐饮",
        merchantName = "餐厅",
        date = LocalDateTime.of(2026, 8, 24, 9, 0),
        createdAt = LocalDateTime.of(2026, 8, 24, 9, 0),
        updatedAt = LocalDateTime.of(2026, 8, 24, 9, 1),
        source = TransactionSource.MANUAL,
        status = TransactionStatus.CONFIRMED,
        syncStatus = SyncStatus.PENDING_SYNC,
        syncId = "0ec11d58-589d-40c5-bc30-e4524b539a2c"
    )

    private val remote = SyncTransactionDto(
        syncId = local.syncId,
        serverVersion = 11,
        amount = local.amount,
        type = local.type.name,
        categoryId = local.categoryId,
        categoryName = local.categoryName,
        categoryIcon = null,
        categoryColor = null,
        merchantName = local.merchantName,
        note = null,
        originalInput = null,
        date = 1_724_468_400_000,
        createdAt = 1_724_468_400_000,
        updatedAt = 1_724_468_460_000,
        source = local.source.name,
        status = local.status.name,
        aiConfidence = null,
        deletedAt = null,
        recordedByUserId = "owner-user",
        recordedByDisplayName = "Cloud Owner",
        recordedByEmail = "owner@example.com"
    )

    @BeforeEach
    fun setUp() {
        manager = CloudSyncManager(
            repository,
            api,
            tokenProvider,
            preferences,
            json,
            categorySync
        )
        every { repository.observePendingSyncCount() } returns flowOf(0)
        coEvery { tokenProvider.invalidate() } returns Unit
        coEvery { categorySync.syncDefault(any()) } returns emptyList()
        every { preferences.isRecordedByMetadataRefreshComplete(any()) } returns true
        every { preferences.markRecordedByMetadataRefreshComplete(any()) } returns Unit
    }

    @Test
    fun `requires login before reading or uploading local data`() = runTest {
        coEvery { tokenProvider.acquireToken() } returns null

        val result = manager.syncNow()

        assertInstanceOf(AuthenticationRequiredException::class.java, result.exceptionOrNull())
        coVerify(exactly = 0) { repository.getPendingSync() }
        coVerify(exactly = 0) { categorySync.syncDefault(any()) }
        coVerify(exactly = 0) { api.push(any(), any()) }
    }

    @Test
    fun `propagates coroutine cancellation`() = runTest {
        coEvery { tokenProvider.acquireToken() } throws CancellationException()

        val failure = runCatching { manager.syncNow() }.exceptionOrNull()

        assertInstanceOf(CancellationException::class.java, failure)
    }

    @Test
    fun `blocks a different account before local data is read`() = runTest {
        coEvery { tokenProvider.acquireToken() } returns AccessToken("token", "account-b")
        every { preferences.bindAccount("account-b") } returns false

        val result = manager.syncNow()

        assertInstanceOf(AccountMismatchException::class.java, result.exceptionOrNull())
        coVerify(exactly = 0) { repository.getPendingSync() }
        coVerify(exactly = 0) { categorySync.syncDefault(any()) }
    }

    @Test
    fun `category synchronization failure stops before uploading or merging transactions`() = runTest {
        signedIn()
        coEvery { categorySync.syncDefault(any()) } throws RetryableSyncException("categories unavailable")

        val result = manager.syncNow()

        assertInstanceOf(RetryableSyncException::class.java, result.exceptionOrNull())
        coVerify(exactly = 0) { repository.getPendingSync() }
        coVerify(exactly = 0) { repository.mergeRemote(any()) }
        coVerify(exactly = 0) { api.push(any(), any()) }
        coVerify(exactly = 0) { api.pull(any(), any(), any()) }
    }

    @Test
    fun `one-time recorder refresh replays authoritative history without changing normal cursor`() = runTest {
        signedIn()
        every { preferences.isRecordedByMetadataRefreshComplete("account-a") } returns false
        coEvery { repository.needsRecordedByMetadataRefresh() } returns true
        coEvery { repository.refreshRecordedByMetadata(any()) } returnsMany listOf(true, false)
        coEvery { repository.getPendingSync() } returns emptyList()
        coEvery { api.pull(any(), 0, 500) } returns Response.success(
            PullResponse(listOf(remote), nextCursor = 11, hasMore = true)
        )
        coEvery { api.pull(any(), 11, 500) } returns Response.success(
            PullResponse(listOf(remote.copy(syncId = "other", recordedByUserId = null, recordedByDisplayName = null, recordedByEmail = null)), nextCursor = 17, hasMore = false)
        )
        every { preferences.cursor() } returns 7
        coEvery { api.pull(any(), 7, 200) } returns Response.success(
            PullResponse(emptyList(), nextCursor = 7, hasMore = false)
        )
        every { preferences.updateCursor(7) } returns Unit

        manager.syncNow().getOrThrow()

        coVerifyOrder {
            categorySync.syncDefault(any())
            repository.needsRecordedByMetadataRefresh()
            api.pull(any(), 0, 500)
            repository.refreshRecordedByMetadata(match { it.syncId == remote.syncId })
            api.pull(any(), 11, 500)
            repository.refreshRecordedByMetadata(match { it.syncId == "other" })
        }
        verify { preferences.markRecordedByMetadataRefreshComplete("account-a") }
        verify(exactly = 1) { preferences.updateCursor(7) }
    }

    @Test
    fun `completed recorder refresh is marked without replay when nothing is missing`() = runTest {
        signedIn()
        every { preferences.isRecordedByMetadataRefreshComplete("account-a") } returns false
        coEvery { repository.needsRecordedByMetadataRefresh() } returns false
        coEvery { repository.getPendingSync() } returns emptyList()
        every { preferences.cursor() } returns 3
        coEvery { api.pull(any(), 3, 200) } returns Response.success(
            PullResponse(emptyList(), nextCursor = 3, hasMore = false)
        )
        every { preferences.updateCursor(3) } returns Unit

        manager.syncNow().getOrThrow()

        verify { preferences.markRecordedByMetadataRefreshComplete("account-a") }
        coVerify(exactly = 0) { repository.refreshRecordedByMetadata(any()) }
        coVerify(exactly = 0) { api.pull(any(), 0, 500) }
    }

    @Test
    fun `refresh failure stays retryable and leaves completion flag unset`() = runTest {
        signedIn()
        every { preferences.isRecordedByMetadataRefreshComplete("account-a") } returns false
        coEvery { repository.needsRecordedByMetadataRefresh() } returns true
        coEvery { api.pull(any(), 0, 500) } returns Response.error(
            503,
            "{}".toResponseBody("application/json".toMediaType())
        )

        val failure = manager.syncNow().exceptionOrNull()

        assertInstanceOf(RetryableSyncException::class.java, failure)
        verify(exactly = 0) { preferences.markRecordedByMetadataRefreshComplete(any()) }
        coVerify(exactly = 0) { repository.getPendingSync() }
        verify(exactly = 0) { preferences.updateCursor(any()) }
    }

    @Test
    fun `uploads all pending data before pulling and acknowledges exact snapshot`() = runTest {
        signedIn()
        coEvery { repository.getPendingSync() } returns listOf(local)
        coEvery { api.push(any(), any()) } returns Response.success(
            PushResponse(accepted = listOf(remote), conflicts = emptyList())
        )
        coEvery {
            repository.acknowledgeSynced(
                local.syncId,
                local.updatedAt,
                0,
                11,
                "owner-user",
                "Cloud Owner",
                "owner@example.com"
            )
        } returns true
        every { preferences.cursor() } returns 0
        coEvery { api.pull(any(), 0, any()) } returns Response.success(
            PullResponse(listOf(remote), nextCursor = 11, hasMore = false)
        )
        coEvery { repository.mergeRemote(any()) } returns true
        every { preferences.updateCursor(11) } returns Unit

        val report = manager.syncNow().getOrThrow()

        assertEquals(1, report.uploaded)
        assertEquals(1, report.downloaded)
        assertEquals(0, report.conflicts)
        coVerifyOrder {
            categorySync.syncDefault(any())
            repository.getPendingSync()
            api.push(any(), any())
            repository.acknowledgeSynced(
                local.syncId,
                local.updatedAt,
                0,
                11,
                "owner-user",
                "Cloud Owner",
                "owner@example.com"
            )
            api.pull(any(), 0, any())
        }
    }

    @Test
    fun `keeps an accepted upload pending when its local row cannot be acknowledged`() = runTest {
        signedIn()
        coEvery { repository.getPendingSync() } returns listOf(local)
        coEvery { api.push(any(), any()) } returns Response.success(
            PushResponse(accepted = listOf(remote), conflicts = emptyList())
        )
        coEvery {
            repository.acknowledgeSynced(
                local.syncId,
                local.updatedAt,
                0,
                11,
                "owner-user",
                "Cloud Owner",
                "owner@example.com"
            )
        } returns false
        emptyPull()

        val report = manager.syncNow().getOrThrow()

        assertEquals(0, report.uploaded)
        assertEquals(1, report.conflicts)
        coVerify(exactly = 2) { api.push(any(), any()) }
    }

    @Test
    fun `default uploads use cloud category IDs without changing local Room IDs`() = runTest {
        signedIn()
        val request = slot<PushRequest>()
        val cloud = Category(901, "餐饮", "🍜", "#112233", TransactionType.EXPENSE)
        coEvery { categorySync.syncDefault(any()) } returns listOf(cloud)
        coEvery { repository.getPendingSync() } returns listOf(local)
        coEvery { api.push(any(), capture(request)) } returns Response.success(
            PushResponse(listOf(remote.copy(categoryId = cloud.id)), emptyList())
        )
        coEvery {
            repository.acknowledgeSynced(
                local.syncId,
                local.updatedAt,
                0,
                11,
                "owner-user",
                "Cloud Owner",
                "owner@example.com"
            )
        } returns true
        emptyPull()

        manager.syncNow().getOrThrow()

        assertEquals(901L, request.captured.transactions.single().categoryId)
        assertEquals("🍜", request.captured.transactions.single().categoryIcon)
        assertEquals("#112233", request.captured.transactions.single().categoryColor)
        assertEquals(2L, local.categoryId)
    }

    @Test
    fun `rebases and retries a server conflict without overwriting local data`() = runTest {
        signedIn()
        val rebased = local.copy(serverVersion = 11)
        coEvery { repository.getPendingSync() } returnsMany listOf(
            listOf(local),
            listOf(rebased)
        )
        val conflict = PushResponse(emptyList(), listOf(remote))
        val conflictResponse: () -> Response<PushResponse> = {
            Response.error<PushResponse>(
                409,
                json.encodeToString(conflict)
                    .toResponseBody("application/json".toMediaType())
            )
        }
        coEvery { api.push(any(), any()) } returnsMany listOf(
            conflictResponse(),
            Response.success(
                PushResponse(
                    accepted = listOf(remote.copy(serverVersion = 12)),
                    conflicts = emptyList()
                )
            )
        )
        coEvery { repository.rebasePendingSync(local.syncId, 0, 11) } returns true
        coEvery {
            repository.acknowledgeSynced(
                local.syncId,
                local.updatedAt,
                11,
                12,
                "owner-user",
                "Cloud Owner",
                "owner@example.com"
            )
        } returns true
        emptyPull()

        val report = manager.syncNow().getOrThrow()

        assertEquals(0, report.conflicts)
        assertEquals(1, report.uploaded)
        coVerify { repository.rebasePendingSync(local.syncId, 0, 11) }
        coVerify {
            repository.acknowledgeSynced(
                local.syncId,
                local.updatedAt,
                11,
                12,
                "owner-user",
                "Cloud Owner",
                "owner@example.com"
            )
        }
        coVerify(exactly = 0) { repository.mergeRemote(any()) }
    }

    @Test
    fun `empty pull advances nothing and never deletes local data`() = runTest {
        signedIn()
        coEvery { repository.getPendingSync() } returns emptyList()
        emptyPull(cursor = 7)

        val report = manager.syncNow().getOrThrow()

        assertEquals(0, report.downloaded)
        coVerify(exactly = 0) { repository.mergeRemote(any()) }
        verify { preferences.updateCursor(7) }
    }

    @Test
    fun `counts a pending local merge as a conflict and advances cursor`() = runTest {
        signedIn()
        coEvery { repository.getPendingSync() } returns emptyList()
        every { preferences.cursor() } returns 5
        coEvery { api.pull(any(), 5, any()) } returns Response.success(
            PullResponse(listOf(remote), nextCursor = 11, hasMore = false)
        )
        coEvery { repository.mergeRemote(any()) } returns false
        every { preferences.updateCursor(11) } returns Unit

        val report = manager.syncNow().getOrThrow()

        assertEquals(1, report.conflicts)
        assertEquals(0, report.downloaded)
    }

    @Test
    fun `pull merges recorded-by fields for local cache display`() = runTest {
        signedIn()
        coEvery { repository.getPendingSync() } returns emptyList()
        every { preferences.cursor() } returns 0
        coEvery { api.pull(any(), 0, any()) } returns Response.success(
            PullResponse(
                listOf(
                    remote.copy(
                        recordedByUserId = "editor-user",
                        recordedByDisplayName = null,
                        recordedByEmail = "editor@example.com"
                    )
                ),
                nextCursor = 11,
                hasMore = false
            )
        )
        val merged = slot<Transaction>()
        coEvery { repository.mergeRemote(capture(merged)) } returns true
        every { preferences.updateCursor(11) } returns Unit

        manager.syncNow().getOrThrow()

        assertEquals("editor-user", merged.captured.recordedByUserId)
        assertEquals("editor@example.com", merged.captured.recordedByEmail)
        assertNull(merged.captured.recordedByDisplayName)
    }

    @Test
    fun `uploads tombstones with their original transaction direction`() = runTest {
        signedIn()
        val deleted = local.copy(
            deletedAt = LocalDateTime.of(2026, 8, 24, 10, 0)
        )
        coEvery { repository.getPendingSync() } returns listOf(deleted)
        val request = slot<com.aibookkeeper.feature.sync.network.PushRequest>()
        val authorization = slot<String>()
        coEvery { api.push(capture(authorization), capture(request)) } returns Response.success(
            PushResponse(emptyList(), emptyList())
        )
        emptyPull()

        manager.syncNow().getOrThrow()

        val uploaded = request.captured.transactions.single()
        assertEquals("Bearer token", authorization.captured)
        assertEquals("EXPENSE", uploaded.type)
        assertEquals(28.5, uploaded.amount)
        assertTrue(uploaded.deletedAt != null)
    }

    @Test
    fun `pulls every page and persists each monotonic cursor`() = runTest {
        signedIn()
        coEvery { repository.getPendingSync() } returns emptyList()
        every { preferences.cursor() } returns 0
        coEvery { api.pull(any(), 0, any()) } returns Response.success(
            PullResponse(listOf(remote), nextCursor = 11, hasMore = true)
        )
        coEvery { api.pull(any(), 11, any()) } returns Response.success(
            PullResponse(listOf(remote.copy(serverVersion = 12)), 12, false)
        )
        coEvery { repository.mergeRemote(any()) } returns true
        every { preferences.updateCursor(any()) } returns Unit

        val report = manager.syncNow().getOrThrow()

        assertEquals(2, report.downloaded)
        verify { preferences.updateCursor(11) }
        verify { preferences.updateCursor(12) }
    }

    @Test
    fun `treats an expired API token as authentication required`() = runTest {
        signedIn()
        coEvery { repository.getPendingSync() } returns listOf(local)
        coEvery { api.push(any(), any()) } returns Response.error(
            401,
            "{}".toResponseBody("application/json".toMediaType())
        )

        val result = manager.syncNow()

        assertInstanceOf(AuthenticationRequiredException::class.java, result.exceptionOrNull())
        coVerify(exactly = 1) { tokenProvider.invalidate() }
        coVerify(exactly = 0) { api.pull(any(), any(), any()) }
    }

    @Test
    fun `splits first sync into backend-sized batches before pulling`() = runTest {
        signedIn()
        val pending = (1..201).map { index ->
            local.copy(
                id = index.toLong(),
                syncId = UUID.nameUUIDFromBytes("transaction-$index".toByteArray()).toString()
            )
        }
        coEvery { repository.getPendingSync() } returns pending
        val requests = mutableListOf<com.aibookkeeper.feature.sync.network.PushRequest>()
        coEvery { api.push(any(), capture(requests)) } answers {
            val request = secondArg<com.aibookkeeper.feature.sync.network.PushRequest>()
            Response.success(
                PushResponse(
                    accepted = request.transactions.mapIndexed { index, item ->
                        item.copy(serverVersion = (index + 1).toLong())
                    },
                    conflicts = emptyList()
                )
            )
        }
        coEvery {
            repository.acknowledgeSynced(any(), any(), any(), any(), any(), any(), any())
        } returns true
        emptyPull()

        val report = manager.syncNow().getOrThrow()

        assertEquals(listOf(200, 1), requests.map { it.transactions.size })
        assertEquals(201, report.uploaded)
        coVerify(exactly = 1) { api.pull(any(), any(), any()) }
    }

    @Test
    fun `isolates a permanently rejected record and uploads the rest`() = runTest {
        signedIn()
        val valid = local.copy(
            id = 5,
            syncId = UUID.nameUUIDFromBytes("valid".toByteArray()).toString()
        )
        coEvery { repository.getPendingSync() } returns listOf(local, valid)
        coEvery { api.push(any(), any()) } answers {
            val request = secondArg<com.aibookkeeper.feature.sync.network.PushRequest>()
            when {
                request.transactions.size > 1 ||
                    request.transactions.single().syncId == local.syncId -> Response.error(
                        400,
                        "{}".toResponseBody("application/json".toMediaType())
                    )
                else -> Response.success(
                    PushResponse(
                        accepted = listOf(
                            request.transactions.single().copy(
                                serverVersion = 9,
                                recordedByUserId = remote.recordedByUserId,
                                recordedByDisplayName = remote.recordedByDisplayName,
                                recordedByEmail = remote.recordedByEmail
                            )
                        ),
                        conflicts = emptyList()
                    )
                )
            }
        }
        coEvery {
            repository.acknowledgeSynced(
                valid.syncId,
                valid.updatedAt,
                0,
                9,
                "owner-user",
                "Cloud Owner",
                "owner@example.com"
            )
        } returns true
        emptyPull()

        val report = manager.syncNow().getOrThrow()

        assertEquals(1, report.uploaded)
        assertEquals(1, report.failed)
        assertEquals(setOf(local.syncId), report.failedSyncIds)
        coVerify(exactly = 3) { api.push(any(), any()) }
    }

    @Test
    fun `treats 429 and server errors as retryable`() = runTest {
        listOf(429, 500).forEach { status ->
            signedIn()
            coEvery { repository.getPendingSync() } returns listOf(local)
            coEvery { api.push(any(), any()) } returns Response.error(
                status,
                "{}".toResponseBody("application/json".toMediaType())
            )

            val failure = manager.syncNow().exceptionOrNull()

            assertInstanceOf(RetryableSyncException::class.java, failure)
        }
    }

    private fun signedIn() {
        coEvery { tokenProvider.acquireToken() } returns AccessToken("token", "account-a")
        every { preferences.bindAccount("account-a") } returns true
    }

    private fun emptyPull(cursor: Long = 0) {
        every { preferences.cursor() } returns cursor
        coEvery { api.pull(any(), cursor, any()) } returns Response.success(
            PullResponse(emptyList(), nextCursor = cursor, hasMore = false)
        )
        every { preferences.updateCursor(cursor) } returns Unit
    }
}
