package com.aibookkeeper.core.common.extensions

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

fun LocalDateTime.toEpochMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    atZone(zone).toInstant().toEpochMilli()

fun Long.toLocalDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()

fun YearMonth.startOfMonthMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    atDay(1).atStartOfDay().toEpochMillis(zone)

fun YearMonth.endOfMonthMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    atEndOfMonth().atTime(23, 59, 59, 999_999_999).toEpochMillis(zone)

fun YearMonth.toIsoString(): String = toString() // "2026-03"

fun String.toYearMonth(): YearMonth = YearMonth.parse(this)
