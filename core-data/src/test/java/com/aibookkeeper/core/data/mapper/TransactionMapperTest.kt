package com.aibookkeeper.core.data.mapper

import com.aibookkeeper.core.data.local.entity.TransactionEntity
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TransactionMapperTest {

    private lateinit var mapper: TransactionMapper

    private val fixedDateTime = LocalDateTime.of(2026, 3, 15, 10, 30, 0)
    private val fixedEpochMillis = fixedDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @BeforeEach
    fun setUp() {
        mapper = TransactionMapper()
    }

    private fun createDomain(
        id: Long = 1,
        amount: Double = 35.5,
        type: TransactionType = TransactionType.EXPENSE,
        categoryId: Long? = 2,
        merchantName: String? = "星巴克",
        note: String? = "咖啡",
        originalInput: String? = "星巴克咖啡35.5元",
        source: TransactionSource = TransactionSource.TEXT_AI,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        syncStatus: SyncStatus = SyncStatus.LOCAL,
        aiConfidence: Float? = 0.95f
    ) = Transaction(
        id = id,
        amount = amount,
        type = type,
        categoryId = categoryId,
        merchantName = merchantName,
        note = note,
        originalInput = originalInput,
        date = fixedDateTime,
        createdAt = fixedDateTime,
        updatedAt = fixedDateTime,
        source = source,
        status = status,
        syncStatus = syncStatus,
        aiConfidence = aiConfidence
    )

    private fun createEntity(
        id: Long = 1,
        amount: Double = 35.5,
        type: String = "EXPENSE",
        categoryId: Long? = 2,
        merchantName: String? = "星巴克",
        note: String? = "咖啡",
        originalInput: String? = "星巴克咖啡35.5元",
        source: String = "TEXT_AI",
        status: String = "CONFIRMED",
        syncStatus: String = "LOCAL",
        aiConfidence: Float? = 0.95f
    ) = TransactionEntity(
        id = id,
        amount = amount,
        type = type,
        categoryId = categoryId,
        merchantName = merchantName,
        note = note,
        originalInput = originalInput,
        date = fixedEpochMillis,
        createdAt = fixedEpochMillis,
        updatedAt = fixedEpochMillis,
        source = source,
        status = status,
        syncStatus = syncStatus,
        aiConfidence = aiConfidence
    )

    // ── toDomain ─────────────────────────────────────────────────────────

    @Nested
    inner class ToDomain {

        @Test
        fun should_mapAllFields_when_entityHasAllValues() {
            val entity = createEntity()
            val domain = mapper.toDomain(entity)

            assertEquals(1L, domain.id)
            assertEquals(35.5, domain.amount)
            assertEquals(TransactionType.EXPENSE, domain.type)
            assertEquals(2L, domain.categoryId)
            assertEquals("星巴克", domain.merchantName)
            assertEquals("咖啡", domain.note)
            assertEquals("星巴克咖啡35.5元", domain.originalInput)
            assertEquals(TransactionSource.TEXT_AI, domain.source)
            assertEquals(TransactionStatus.CONFIRMED, domain.status)
            assertEquals(SyncStatus.LOCAL, domain.syncStatus)
            assertEquals(0.95f, domain.aiConfidence)
        }

        @Test
        fun should_mapNullableFieldsAsNull_when_entityHasNulls() {
            val entity = createEntity(
                categoryId = null,
                merchantName = null,
                note = null,
                originalInput = null,
                aiConfidence = null
            )
            val domain = mapper.toDomain(entity)

            assertNull(domain.categoryId)
            assertNull(domain.merchantName)
            assertNull(domain.note)
            assertNull(domain.originalInput)
            assertNull(domain.aiConfidence)
        }

        @Test
        fun should_mapIncomeType_when_entityTypeIsIncome() {
            val entity = createEntity(type = "INCOME")
            val domain = mapper.toDomain(entity)

            assertEquals(TransactionType.INCOME, domain.type)
        }

        @Test
        fun should_mapAllSources_when_differentSourceValues() {
            val sources = TransactionSource.entries
            for (source in sources) {
                val entity = createEntity(source = source.name)
                val domain = mapper.toDomain(entity)
                assertEquals(source, domain.source)
            }
        }

        @Test
        fun should_mapAllSyncStatuses_when_differentStatusValues() {
            val statuses = SyncStatus.entries
            for (status in statuses) {
                val entity = createEntity(syncStatus = status.name)
                val domain = mapper.toDomain(entity)
                assertEquals(status, domain.syncStatus)
            }
        }

        @Test
        fun should_mapPendingStatus_when_entityStatusIsPending() {
            val entity = createEntity(status = "PENDING")
            val domain = mapper.toDomain(entity)
            assertEquals(TransactionStatus.PENDING, domain.status)
        }

        @Test
        fun should_convertEpochMillisToLocalDateTime_when_mapping() {
            val entity = createEntity()
            val domain = mapper.toDomain(entity)

            assertEquals(fixedDateTime.year, domain.date.year)
            assertEquals(fixedDateTime.month, domain.date.month)
            assertEquals(fixedDateTime.dayOfMonth, domain.date.dayOfMonth)
            assertEquals(fixedDateTime.hour, domain.date.hour)
            assertEquals(fixedDateTime.minute, domain.date.minute)
        }
    }

    // ── toEntity ─────────────────────────────────────────────────────────

    @Nested
    inner class ToEntity {

        @Test
        fun should_mapAllFields_when_domainHasAllValues() {
            val domain = createDomain()
            val entity = mapper.toEntity(domain)

            assertEquals(1L, entity.id)
            assertEquals(35.5, entity.amount)
            assertEquals("EXPENSE", entity.type)
            assertEquals(2L, entity.categoryId)
            assertEquals("星巴克", entity.merchantName)
            assertEquals("咖啡", entity.note)
            assertEquals("星巴克咖啡35.5元", entity.originalInput)
            assertEquals("TEXT_AI", entity.source)
            assertEquals("CONFIRMED", entity.status)
            assertEquals("LOCAL", entity.syncStatus)
            assertEquals(0.95f, entity.aiConfidence)
        }

        @Test
        fun should_mapNullableFieldsAsNull_when_domainHasNulls() {
            val domain = createDomain(
                categoryId = null,
                merchantName = null,
                note = null,
                originalInput = null,
                aiConfidence = null
            )
            val entity = mapper.toEntity(domain)

            assertNull(entity.categoryId)
            assertNull(entity.merchantName)
            assertNull(entity.note)
            assertNull(entity.originalInput)
            assertNull(entity.aiConfidence)
        }

        @Test
        fun should_convertLocalDateTimeToEpochMillis_when_mapping() {
            val domain = createDomain()
            val entity = mapper.toEntity(domain)

            assertEquals(fixedEpochMillis, entity.date)
            assertEquals(fixedEpochMillis, entity.createdAt)
            assertEquals(fixedEpochMillis, entity.updatedAt)
        }

        @Test
        fun should_mapIncomeType_when_domainTypeIsIncome() {
            val domain = createDomain(type = TransactionType.INCOME)
            val entity = mapper.toEntity(domain)
            assertEquals("INCOME", entity.type)
        }

        @Test
        fun should_mapManualSource_when_domainSourceIsManual() {
            val domain = createDomain(source = TransactionSource.MANUAL)
            val entity = mapper.toEntity(domain)
            assertEquals("MANUAL", entity.source)
        }

        @Test
        fun should_mapAutoCapture_when_domainSourceIsAutoCapture() {
            val domain = createDomain(source = TransactionSource.AUTO_CAPTURE)
            val entity = mapper.toEntity(domain)
            assertEquals("AUTO_CAPTURE", entity.source)
        }
    }

    // ── Round-trip ────────────────────────────────────────────────────────

    @Nested
    inner class RoundTrip {

        @Test
        fun should_preserveAllValues_when_domainToEntityAndBack() {
            val original = createDomain()
            val roundTripped = mapper.toDomain(mapper.toEntity(original))

            assertEquals(original.id, roundTripped.id)
            assertEquals(original.amount, roundTripped.amount)
            assertEquals(original.type, roundTripped.type)
            assertEquals(original.categoryId, roundTripped.categoryId)
            assertEquals(original.merchantName, roundTripped.merchantName)
            assertEquals(original.note, roundTripped.note)
            assertEquals(original.originalInput, roundTripped.originalInput)
            assertEquals(original.source, roundTripped.source)
            assertEquals(original.status, roundTripped.status)
            assertEquals(original.syncStatus, roundTripped.syncStatus)
            assertEquals(original.aiConfidence, roundTripped.aiConfidence)
        }

        @Test
        fun should_preserveNulls_when_roundTripping() {
            val original = createDomain(
                categoryId = null,
                merchantName = null,
                note = null,
                originalInput = null,
                aiConfidence = null
            )
            val roundTripped = mapper.toDomain(mapper.toEntity(original))

            assertNull(roundTripped.categoryId)
            assertNull(roundTripped.merchantName)
            assertNull(roundTripped.note)
            assertNull(roundTripped.originalInput)
            assertNull(roundTripped.aiConfidence)
        }

        @Test
        fun should_preserveZeroId_when_roundTripping() {
            val original = createDomain(id = 0)
            val roundTripped = mapper.toDomain(mapper.toEntity(original))
            assertEquals(0L, roundTripped.id)
        }
    }
}
