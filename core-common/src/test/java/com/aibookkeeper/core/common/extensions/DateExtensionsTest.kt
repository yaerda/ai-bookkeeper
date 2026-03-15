package com.aibookkeeper.core.common.extensions

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class DateExtensionsTest {

    private val zone = ZoneId.systemDefault()

    // ── LocalDateTime.toEpochMillis / Long.toLocalDateTime ────────────────

    @Nested
    inner class EpochMillisConversion {

        @Test
        fun should_convertToMillis_when_validDateTime() {
            val dt = LocalDateTime.of(2026, 3, 15, 10, 30, 0)
            val millis = dt.toEpochMillis()

            assertTrue(millis > 0)
        }

        @Test
        fun should_roundTrip_when_convertingDateTimeToMillisAndBack() {
            val original = LocalDateTime.of(2026, 3, 15, 10, 30, 0)
            val roundTripped = original.toEpochMillis().toLocalDateTime()

            assertEquals(original.year, roundTripped.year)
            assertEquals(original.month, roundTripped.month)
            assertEquals(original.dayOfMonth, roundTripped.dayOfMonth)
            assertEquals(original.hour, roundTripped.hour)
            assertEquals(original.minute, roundTripped.minute)
            assertEquals(original.second, roundTripped.second)
        }

        @Test
        fun should_preserveStartOfDay_when_converting() {
            val startOfDay = LocalDateTime.of(2026, 1, 1, 0, 0, 0)
            val roundTripped = startOfDay.toEpochMillis().toLocalDateTime()

            assertEquals(0, roundTripped.hour)
            assertEquals(0, roundTripped.minute)
            assertEquals(0, roundTripped.second)
        }

        @Test
        fun should_preserveEndOfDay_when_converting() {
            val endOfDay = LocalDateTime.of(2026, 12, 31, 23, 59, 59)
            val roundTripped = endOfDay.toEpochMillis().toLocalDateTime()

            assertEquals(23, roundTripped.hour)
            assertEquals(59, roundTripped.minute)
            assertEquals(59, roundTripped.second)
        }

        @Test
        fun should_handleEpochZero_when_convertingToLocalDateTime() {
            val dt = 0L.toLocalDateTime()
            assertNotNull(dt)
        }
    }

    // ── YearMonth.startOfMonthMillis / endOfMonthMillis ──────────────────

    @Nested
    inner class YearMonthMillis {

        @Test
        fun should_returnStartOfDay_when_startOfMonthMillis() {
            val ym = YearMonth.of(2026, 3)
            val millis = ym.startOfMonthMillis()
            val dt = millis.toLocalDateTime()

            assertEquals(2026, dt.year)
            assertEquals(3, dt.monthValue)
            assertEquals(1, dt.dayOfMonth)
            assertEquals(0, dt.hour)
            assertEquals(0, dt.minute)
            assertEquals(0, dt.second)
        }

        @Test
        fun should_returnLastDayOfMonth_when_endOfMonthMillis() {
            val ym = YearMonth.of(2026, 3)
            val millis = ym.endOfMonthMillis()
            val dt = millis.toLocalDateTime()

            assertEquals(2026, dt.year)
            assertEquals(3, dt.monthValue)
            assertEquals(31, dt.dayOfMonth)
            assertEquals(23, dt.hour)
            assertEquals(59, dt.minute)
        }

        @Test
        fun should_handleFebruary_when_nonLeapYear() {
            val ym = YearMonth.of(2026, 2)
            val millis = ym.endOfMonthMillis()
            val dt = millis.toLocalDateTime()

            assertEquals(28, dt.dayOfMonth)
        }

        @Test
        fun should_handleFebruary_when_leapYear() {
            val ym = YearMonth.of(2028, 2)
            val millis = ym.endOfMonthMillis()
            val dt = millis.toLocalDateTime()

            assertEquals(29, dt.dayOfMonth)
        }

        @Test
        fun should_startBeforeEnd_when_sameMonth() {
            val ym = YearMonth.of(2026, 6)
            assertTrue(ym.startOfMonthMillis() < ym.endOfMonthMillis())
        }

        @Test
        fun should_handleJanuary_when_startOfYear() {
            val ym = YearMonth.of(2026, 1)
            val dt = ym.startOfMonthMillis().toLocalDateTime()

            assertEquals(1, dt.monthValue)
            assertEquals(1, dt.dayOfMonth)
        }

        @Test
        fun should_handleDecember_when_endOfYear() {
            val ym = YearMonth.of(2026, 12)
            val dt = ym.endOfMonthMillis().toLocalDateTime()

            assertEquals(12, dt.monthValue)
            assertEquals(31, dt.dayOfMonth)
        }
    }

    // ── toIsoString / toYearMonth ────────────────────────────────────────

    @Nested
    inner class IsoStringConversion {

        @Test
        fun should_returnIsoFormat_when_toIsoStringCalled() {
            val ym = YearMonth.of(2026, 3)
            assertEquals("2026-03", ym.toIsoString())
        }

        @Test
        fun should_padSingleDigitMonth_when_january() {
            val ym = YearMonth.of(2026, 1)
            assertEquals("2026-01", ym.toIsoString())
        }

        @Test
        fun should_parseIsoString_when_toYearMonthCalled() {
            val ym = "2026-03".toYearMonth()
            assertEquals(YearMonth.of(2026, 3), ym)
        }

        @Test
        fun should_roundTrip_when_isoStringConversion() {
            val original = YearMonth.of(2026, 11)
            val roundTripped = original.toIsoString().toYearMonth()
            assertEquals(original, roundTripped)
        }
    }
}
