package com.aibookkeeper.core.data.model

private fun nonBlank(value: String?): String? =
    value?.trim()?.takeIf { it.isNotEmpty() }

fun transactionRecordedByLabel(
    displayName: String?,
    email: String?,
    userId: String?
): String? = nonBlank(displayName)
    ?: nonBlank(email)
    ?: nonBlank(userId)?.let { "成员信息未提供" }

val Transaction.recordedByLabel: String?
    get() = transactionRecordedByLabel(recordedByDisplayName, recordedByEmail, recordedByUserId)
