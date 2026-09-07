package com.aibookkeeper.feature.stats.navigation

import java.time.YearMonth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatsRoutesTest {
    @Test
    fun `category route preserves optional project filter and fallback category keys`() {
        val month = YearMonth.of(2026, 9)
        assertEquals("stats/category/1/2026-09", StatsRoutes.categoryDetail(1, month))
        assertEquals("stats/category/-42/2026-09?projectId=p1", StatsRoutes.categoryDetail(-42, month, "p1"))
        assertEquals("stats/category/1/2026-09?projectId=a%2Fb", StatsRoutes.categoryDetail(1, month, "a/b"))
    }
}
