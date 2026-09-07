package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.model.ProjectBinding
import com.aibookkeeper.core.data.model.ProjectDraft
import com.aibookkeeper.core.data.model.ProjectLedgerState
import com.aibookkeeper.core.data.model.ProjectScope
import com.aibookkeeper.core.data.model.ProjectStats
import kotlinx.coroutines.flow.StateFlow

interface ProjectRepository {
    val currentLedgerState: StateFlow<ProjectLedgerState>
    val defaultLedgerState: StateFlow<ProjectLedgerState>
        get() = currentLedgerState

    fun captureDestination(defaultRoom: Boolean = false): ProjectWriteDestination =
        ProjectWriteDestination(
            currentLedgerState.value.accountId,
            currentLedgerState.value.ledgerId,
            defaultRoom = defaultRoom
        )

    fun requireCurrentDestination(destination: ProjectWriteDestination) = Unit

    fun resolveProjectIds(
        destination: ProjectWriteDestination,
        explicitProjectIds: List<String>?
    ): List<String>? = explicitProjectIds ?: resolveProjectIdsForNewTransaction()

    suspend fun refreshCurrentLedger()

    fun resolveProjectIdsForNewTransaction(): List<String>?

    suspend fun createProject(draft: ProjectDraft): ProjectScope

    suspend fun loadProjectScope(projectId: String): ProjectScope

    suspend fun updateProjectBinding(
        projectId: String,
        ledgerId: String,
        version: Long,
        enabled: Boolean,
        startDate: String?,
        endDate: String?
    ): ProjectBinding

    suspend fun loadProjectStats(
        projectId: String,
        ledgerId: String? = null
    ): ProjectStats
}

data class ProjectWriteDestination(
    val accountId: String?,
    val ledgerId: String?,
    val defaultRoom: Boolean = false,
    val contextVersion: Long = 0,
    val selection: LedgerSelection? = null,
    val canWrite: Boolean = true
)
