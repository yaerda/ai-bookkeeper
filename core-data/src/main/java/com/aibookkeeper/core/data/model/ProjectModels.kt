package com.aibookkeeper.core.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ProjectDefaultsAvailability {
    LIVE,
    CACHED,
    UNAVAILABLE
}

data class ProjectBinding(
    val projectId: String,
    val ledgerId: String,
    val name: String,
    val enabled: Boolean,
    val startDate: String?,
    val endDate: String?,
    val timeZone: String,
    val version: Long,
    val active: Boolean,
    val canEdit: Boolean
)

fun ProjectBinding.isActiveAt(instant: Instant): Boolean {
    if (!enabled) return false
    val today = instant.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
    return (startDate == null || today >= LocalDate.parse(startDate)) &&
        (endDate == null || today <= LocalDate.parse(endDate))
}

data class ProjectLedgerState(
    val accountId: String? = null,
    val ledgerId: String? = null,
    val role: String? = null,
    val projects: List<ProjectBinding> = emptyList(),
    val availability: ProjectDefaultsAvailability = ProjectDefaultsAvailability.UNAVAILABLE,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val refreshedAtMillis: Long? = null,
    val contextVersion: Long = 0
) {
    val canEdit: Boolean
        get() = role == "OWNER" || role == "EDITOR"

    val defaultProjectIds: List<String>?
        get() = defaultProjectIdsAt(Instant.now())

    fun defaultProjectIdsAt(instant: Instant): List<String>? = when (availability) {
            ProjectDefaultsAvailability.LIVE,
            ProjectDefaultsAvailability.CACHED -> projects
                .filter { it.isActiveAt(instant) }
                .map { it.projectId }

            ProjectDefaultsAvailability.UNAVAILABLE -> null
        }
}

data class ProjectScope(
    val projectId: String,
    val name: String,
    val ledgers: List<ProjectBinding>
)

data class ProjectLedgerStats(
    val ledgerId: String,
    val ledgerName: String,
    val transactionCount: Int,
    val income: String,
    val expense: String,
    val balance: String
)

data class ProjectStats(
    val projectId: String,
    val name: String,
    val currency: String,
    val transactionCount: Int,
    val income: String,
    val expense: String,
    val balance: String,
    val ledgers: List<ProjectLedgerStats>
)

data class ProjectDraft(
    val name: String,
    val ledgerIds: List<String>? = null,
    val enabled: Boolean = true,
    val startDate: String? = null,
    val endDate: String? = null
)
