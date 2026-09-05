package com.aibookkeeper.feature.sync.ledger

import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.model.normalizeCategoryName
import com.aibookkeeper.feature.sync.network.CreateCategoryRequest
import com.aibookkeeper.feature.sync.network.LedgerCategoryDto

internal fun Category.toCreateRequest(): CreateCategoryRequest {
    val normalizedName = normalizeCategoryName(name)
    val normalizedIcon = icon.trim()
    require(normalizedName.length in 1..100) { "分类名称须为 1 至 100 个字符" }
    require(normalizedIcon.length in 1..64) { "分类图标须为 1 至 64 个字符" }
    require(color.matches(Regex("#[0-9a-fA-F]{6}"))) { "分类颜色格式无效" }
    require(sortOrder in 0..1_000_000) { "分类排序无效" }
    return CreateCategoryRequest(normalizedName, type.name, normalizedIcon, color, sortOrder)
}

internal fun LedgerCategoryDto.toCategory(): Category {
    require(id in 1..9_007_199_254_740_991L) { "服务器分类 ID 无效" }
    return Category(
        id = id,
        name = name,
        type = TransactionType.valueOf(type),
        icon = icon,
        color = color,
        sortOrder = sortOrder,
        isSystem = isSystem
    )
}

internal fun List<Category>.findCategory(name: String?, type: TransactionType): Category? {
    val normalizedName = name?.let(::normalizeCategoryName) ?: return null
    return firstOrNull {
        it.type == type && normalizeCategoryName(it.name).equals(normalizedName, ignoreCase = true)
    }
}

internal fun Transaction.withCatalog(categories: List<Category>): Transaction {
    val category = categories.findCategory(categoryName, type)
    return copy(
        categoryId = category?.id,
        categoryName = category?.name ?: categoryName,
        categoryIcon = category?.icon ?: categoryIcon,
        categoryColor = category?.color ?: categoryColor
    )
}
