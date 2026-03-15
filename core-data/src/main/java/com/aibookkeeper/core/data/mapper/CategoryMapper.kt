package com.aibookkeeper.core.data.mapper

import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.TransactionType
import javax.inject.Inject

class CategoryMapper @Inject constructor() {

    fun toDomain(entity: CategoryEntity): Category = Category(
        id = entity.id,
        name = entity.name,
        icon = entity.icon,
        color = entity.color,
        type = TransactionType.valueOf(entity.type),
        parentId = entity.parentId,
        isSystem = entity.isSystem,
        sortOrder = entity.sortOrder
    )

    fun toEntity(domain: Category): CategoryEntity = CategoryEntity(
        id = domain.id,
        name = domain.name,
        icon = domain.icon,
        color = domain.color,
        type = domain.type.name,
        parentId = domain.parentId,
        isSystem = domain.isSystem,
        sortOrder = domain.sortOrder
    )
}
