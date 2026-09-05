package com.aibookkeeper.core.data.ai

import com.aibookkeeper.core.data.model.normalizeCategoryName
import com.aibookkeeper.core.data.repository.CategoryRepository
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerSelectionChangedException
import javax.inject.Inject
import kotlinx.coroutines.flow.first

class ActiveExtractionCategoryProvider @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val ledgerContext: LedgerContext
) {
    suspend fun getCategoryNames(additionalNames: List<String>): List<String> {
        val state = ledgerContext.state.value
        check(state.selectedLedger.isLocal || (!state.isLoading && state.errorMessage == null)) {
            "账本分类正在加载，请稍后重试"
        }
        val names = categoryRepository.observeAllCategories().first()
            .map { normalizeCategoryName(it.name) }
            .filter(String::isNotBlank)
            .distinct()
        if (ledgerContext.state.value.selection != state.selection) {
            throw LedgerSelectionChangedException()
        }
        check(names.isNotEmpty()) { "账本分类尚未加载，请稍后重试" }
        val preferred = additionalNames.map(::normalizeCategoryName).filter { it in names }
        return (preferred + names).distinct()
    }
}
