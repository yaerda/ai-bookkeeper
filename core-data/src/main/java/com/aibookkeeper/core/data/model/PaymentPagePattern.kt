package com.aibookkeeper.core.data.model

data class PaymentPagePattern(
    val id: Long = 0,
    val packageName: String,
    val appDisplayName: String,
    val keywords: List<String>,
    val description: String = "",
    val isEnabled: Boolean = true,
    val isSystem: Boolean = false
)
