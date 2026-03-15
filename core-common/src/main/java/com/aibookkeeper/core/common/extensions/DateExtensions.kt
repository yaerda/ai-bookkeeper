package com.aibookkeeper.core.common.extensions

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

private val WEEKDAY_NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun LocalDate.weekdayName(): String = WEEKDAY_NAMES[dayOfWeek.value - 1]

/**
 * 友好的中文日期: `3月14日 周六`
 */
fun LocalDate.toFriendlyDateString(): String =
    "${monthValue}月${dayOfMonth}日 ${weekdayName()}"

/**
 * 友好的中文日期时间: `3月14日 周六 14:30`
 */
fun LocalDateTime.toFriendlyDateTimeString(): String =
    "${toLocalDate().toFriendlyDateString()} ${"%02d:%02d".format(hour, minute)}"

/**
 * 完整的中文日期时间（含年份和秒）: `2026年3月14日 14:30:00`
 */
fun LocalDateTime.toFriendlyFullDateTimeString(): String =
    "${year}年${monthValue}月${dayOfMonth}日 ${"%02d:%02d:%02d".format(hour, minute, second)}"

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
