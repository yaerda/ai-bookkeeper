package com.aibookkeeper.feature.stats.navigation

import java.time.YearMonth
import java.net.URLEncoder

object StatsRoutes {
    const val OVERVIEW = "stats"
    const val BUDGET = "stats/budget"
    const val TRENDS = "stats/trends"
    const val SETTINGS = "settings"
    const val PROJECTS = "settings/projects"
    const val LOCAL_SPEECH_DIAGNOSTIC = "settings/local_speech_diagnostic"
    const val PAYMENT_PATTERNS = "settings/payment_patterns"
    const val CHANGELOG = "settings/changelog"
    const val CATEGORY_DETAIL = "stats/category/{categoryId}/{yearMonth}?projectId={projectId}"

    fun categoryDetail(categoryId: Long, yearMonth: YearMonth, projectId: String? = null): String =
        "stats/category/$categoryId/$yearMonth" +
            (projectId?.let { "?projectId=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
}
