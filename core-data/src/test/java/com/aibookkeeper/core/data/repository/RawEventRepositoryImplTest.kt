package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.local.dao.RawEventDao
import com.aibookkeeper.core.data.local.entity.RawEventEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RawEventRepositoryImplTest {

    private lateinit var dao: RawEventDao
    private lateinit var repository: RawEventRepositoryImpl

    @BeforeEach
    fun setUp() {
        dao = mockk(relaxUnitFun = true)
        repository = RawEventRepositoryImpl(dao)
    }

    @Test
    fun should_insertEvent_when_notDuplicate() = runTest {
        coEvery { dao.existsByHash(any()) } returns false
        coEvery { dao.insert(any()) } returns 1L

        val result = repository.captureEvent("WECHAT_PAY", "微信支付35元")

        assertTrue(result.isSuccess)
        assertEquals(1L, result.getOrThrow())
        coVerify { dao.insert(any()) }
    }

    @Test
    fun should_returnMinusOne_when_isDuplicate() = runTest {
        coEvery { dao.existsByHash(any()) } returns true

        val result = repository.captureEvent("ALIPAY", "duplicate")

        assertTrue(result.isSuccess)
        assertEquals(-1L, result.getOrThrow())
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun should_computeConsistentHash_when_sameContent() = runTest {
        val entitySlot = slot<RawEventEntity>()
        coEvery { dao.existsByHash(any()) } returns false
        coEvery { dao.insert(capture(entitySlot)) } returns 1L

        repository.captureEvent("WECHAT_PAY", "test content")
        val hash1 = entitySlot.captured.contentHash

        coEvery { dao.insert(capture(entitySlot)) } returns 2L
        repository.captureEvent("WECHAT_PAY", "test content")
        val hash2 = entitySlot.captured.contentHash

        assertEquals(hash1, hash2)
    }

    @Test
    fun should_delegateMarkExtracted_to_dao() = runTest {
        repository.markExtracted(5L, 100L)
        coVerify { dao.markExtracted(5L, any(), 100L) }
    }

    @Test
    fun should_delegateMarkFailed_to_dao() = runTest {
        repository.markFailed(5L, "some error")
        coVerify { dao.markFailed(5L, "some error") }
    }

    @Test
    fun should_checkDuplicate_via_hash() = runTest {
        coEvery { dao.existsByHash(any()) } returns true
        assertTrue(repository.isDuplicate("test"))

        coEvery { dao.existsByHash(any()) } returns false
        assertFalse(repository.isDuplicate("other"))
    }
}
