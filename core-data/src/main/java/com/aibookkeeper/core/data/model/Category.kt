package com.aibookkeeper.core.data.model

data class Category(
    val id: Long = 0,
    val name: String,
    val icon: String,
    val color: String,
    val type: TransactionType,
    val parentId: Long? = null,
    val isSystem: Boolean = true,
    val sortOrder: Int = 0,
    val children: List<Category> = emptyList()
)
