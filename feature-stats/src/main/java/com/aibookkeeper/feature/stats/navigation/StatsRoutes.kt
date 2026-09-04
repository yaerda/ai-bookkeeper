package com.aibookkeeper.feature.stats.navigation

import java.time.YearMonth

object StatsRoutes {
    const val OVERVIEW = "stats"
    const val BUDGET = "stats/budget"
    const val TRENDS = "stats/trends"
    const val SETTINGS = "settings"
    const val LOCAL_SPEECH_DIAGNOSTIC = "settings/local_speech_diagnostic"
    const val PAYMENT_PATTERNS = "settings/payment_patterns"
    const val CHANGELOG = "settings/changelog"
    const val CATEGORY_DETAIL = "stats/category/{categoryId}/{yearMonth}"

    fun categoryDetail(categoryId: Long, yearMonth: YearMonth): String =
        "stats/category/$categoryId/$yearMonth"
}
