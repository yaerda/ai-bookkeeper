package com.aibookkeeper.core.data.mapper

import com.aibookkeeper.core.data.local.entity.PaymentPagePatternEntity
import com.aibookkeeper.core.data.model.PaymentPagePattern

fun PaymentPagePatternEntity.toDomain(): PaymentPagePattern = PaymentPagePattern(
    id = id,
    packageName = packageName,
    appDisplayName = appDisplayName,
    keywords = keywords.toKeywordList(),
    description = description,
    isEnabled = isEnabled,
    isSystem = isSystem
)

fun PaymentPagePattern.toEntity(createdAt: Long = System.currentTimeMillis()): PaymentPagePatternEntity = PaymentPagePatternEntity(
    id = id,
    packageName = packageName,
    appDisplayName = appDisplayName,
    keywords = keywords.toKeywordString(),
    description = description,
    isEnabled = isEnabled,
    isSystem = isSystem,
    createdAt = createdAt
)

private fun String.toKeywordList(): List<String> = split(",")
    .map(String::trim)
    .filter(String::isNotEmpty)

private fun List<String>.toKeywordString(): String = map(String::trim)
    .filter(String::isNotEmpty)
    .joinToString(",")
