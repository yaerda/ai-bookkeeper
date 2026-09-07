package com.aibookkeeper.feature.sync.projects

import com.aibookkeeper.core.data.model.ProjectBinding
import com.aibookkeeper.core.data.model.ProjectScope
import com.aibookkeeper.core.data.model.ProjectStats
import com.aibookkeeper.core.data.model.ProjectLedgerStats
import com.aibookkeeper.feature.sync.network.ProjectBindingDto
import com.aibookkeeper.feature.sync.network.ProjectScopeResponse
import com.aibookkeeper.feature.sync.network.ProjectStatsResponse

internal fun ProjectBindingDto.toModel(): ProjectBinding = ProjectBinding(
    projectId = projectId,
    ledgerId = ledgerId,
    name = name,
    enabled = enabled,
    startDate = startDate,
    endDate = endDate,
    timeZone = timeZone,
    version = version,
    active = active,
    canEdit = canEdit
)

internal fun ProjectScopeResponse.toModel(): ProjectScope = ProjectScope(
    projectId = projectId,
    name = name,
    ledgers = ledgers.map(ProjectBindingDto::toModel)
)

internal fun ProjectStatsResponse.toModel(): ProjectStats = ProjectStats(
    projectId = projectId,
    name = name,
    currency = currency,
    transactionCount = transactionCount,
    income = income,
    expense = expense,
    balance = balance,
    ledgers = ledgers.map {
        ProjectLedgerStats(
            ledgerId = it.ledgerId,
            ledgerName = it.ledgerName,
            transactionCount = it.transactionCount,
            income = it.income,
            expense = it.expense,
            balance = it.balance
        )
    }
)
