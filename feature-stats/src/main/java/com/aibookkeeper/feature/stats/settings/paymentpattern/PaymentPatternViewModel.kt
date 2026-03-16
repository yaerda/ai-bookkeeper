package com.aibookkeeper.feature.stats.settings.paymentpattern

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.PaymentPagePattern
import com.aibookkeeper.core.data.repository.PaymentPagePatternRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PaymentPatternViewModel @Inject constructor(
    private val repository: PaymentPagePatternRepository
) : ViewModel() {

    val patterns = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun togglePattern(pattern: PaymentPagePattern) {
        viewModelScope.launch {
            repository.update(pattern.copy(isEnabled = !pattern.isEnabled))
        }
    }

    fun deletePattern(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
        }
    }

    fun addPattern(
        packageName: String,
        appDisplayName: String,
        keywords: String,
        description: String
    ) {
        val normalizedKeywords = keywords.split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (packageName.isBlank() || appDisplayName.isBlank() || normalizedKeywords.isEmpty()) {
            return
        }

        viewModelScope.launch {
            repository.create(
                PaymentPagePattern(
                    packageName = packageName.trim(),
                    appDisplayName = appDisplayName.trim(),
                    keywords = normalizedKeywords,
                    description = description.trim(),
                    isEnabled = true,
                    isSystem = false
                )
            )
        }
    }
}
