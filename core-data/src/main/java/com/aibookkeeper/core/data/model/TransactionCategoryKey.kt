package com.aibookkeeper.core.data.model

fun Transaction.categoryKey(): Long =
    categoryId
        ?: categoryName?.hashCode()?.toLong()?.let { if (it > 0) -it else it }
        ?: 0L
