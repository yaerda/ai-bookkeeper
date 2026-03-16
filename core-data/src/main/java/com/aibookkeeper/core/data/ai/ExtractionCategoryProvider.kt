package com.aibookkeeper.core.data.ai

import com.aibookkeeper.core.data.local.dao.CategoryDao
import javax.inject.Inject

class ExtractionCategoryProvider @Inject constructor(
    private val categoryDao: CategoryDao
) {

    suspend fun getCategoryNames(additionalNames: List<String>): List<String> {
        val normalizedAdditional = additionalNames
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()

        val storedNames = categoryDao.getAllOnce()
            .asSequence()
            .map { it.name.trim() }
            .filter(String::isNotBlank)
            .toList()

        return (normalizedAdditional + storedNames).distinct()
    }
}
