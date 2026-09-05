package com.aibookkeeper.core.common.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class TransactionDateTest {
    private val recordedAt = LocalDateTime.of(2026, 9, 5, 17, 42, 35, 123_000_000)

    @Test
    fun `a new date-only entry retains recording time instead of sorting at midnight`() {
        val date = resolveTransactionDate("2026-09-05", recordedAt)
        assertEquals(recordedAt, date)
        assertTrue(date.isAfter(recordedAt.withHour(16)))
    }

    @Test
    fun `an explicitly backdated entry retains its chosen day and recording time`() {
        val date = resolveTransactionDate("2026-08-31", recordedAt)
        assertEquals(LocalDate.of(2026, 8, 31), date.toLocalDate())
        assertEquals(recordedAt.toLocalTime(), date.toLocalTime())
    }

    @Test
    fun `invalid AI dates use a complete recording timestamp`() {
        assertEquals(recordedAt, resolveTransactionDate("not-a-date", recordedAt))
        assertEquals(recordedAt, resolveTransactionDate("2026-02-30", recordedAt))
    }

    @Test
    fun `material date picker timestamps are UTC dates rather than local midnights`() {
        val date = LocalDate.of(2026, 9, 1)
        val millis = date.toDatePickerMillis()
        assertEquals(Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(), millis)
        assertEquals(date, millis.toDatePickerDate())
        assertEquals(0, Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).hour)
    }
}
