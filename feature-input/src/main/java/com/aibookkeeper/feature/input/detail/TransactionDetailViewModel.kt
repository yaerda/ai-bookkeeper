package com.aibookkeeper.feature.input.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Loaded(val transaction: Transaction) : DetailUiState
    data object NotFound : DetailUiState
    data object Deleted : DetailUiState
}

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    private val transactionId: Long = savedStateHandle["transactionId"] ?: -1L

    val uiState: StateFlow<DetailUiState> = transactionRepository.observeById(transactionId)
        .map { tx ->
            if (tx != null) DetailUiState.Loaded(tx) else DetailUiState.NotFound
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState.Loading)

    fun deleteTransaction(onDeleted: () -> Unit) {
        viewModelScope.launch {
            transactionRepository.delete(transactionId)
            onDeleted()
        }
    }
}
