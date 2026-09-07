package com.aibookkeeper.feature.input.home

import android.app.Activity
import android.content.Intent
import com.aibookkeeper.core.common.extensions.resolveTransactionDate
import com.aibookkeeper.core.data.ai.AzureOpenAiPromptBuilder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.model.newestTransactionFirst
import com.aibookkeeper.core.data.repository.AiExtractionRepository
import com.aibookkeeper.core.data.repository.CategoryRepository
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerOption
import com.aibookkeeper.core.data.repository.requireEditable
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.core.data.repository.VoiceTranscriptionRepository
import com.aibookkeeper.core.data.security.SecureConfigStore
import com.aibookkeeper.core.data.speech.SystemSpeechRecognitionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject

private data class DataTuple(
    val monthExpense: Double,
    val monthIncome: Double,
    val transactions: List<Transaction>,
    val categories: List<Category>
)

data class HomeUiState(
    val todayExpense: Double = 0.0,
    val todayIncome: Double = 0.0,
    val monthExpense: Double = 0.0,
    val monthIncome: Double = 0.0,
    val recentTransactions: List<Transaction> = emptyList(),
    val expenseCategories: List<Category> = emptyList(),
    val currentMonth: YearMonth = YearMonth.now(),
    val isLoading: Boolean = true,
    val cloudSystemPrompt: String = "",
    val customCloudPrompt: String = "",
    val aiStatus: AiStatus = AiStatus.Idle,
    val voiceStatus: VoiceStatus = VoiceStatus.Idle,
    val isSignedIn: Boolean = false,
    val ledgers: List<LedgerOption> = emptyList(),
    val selectedLedgerId: String = "",
    val selectedLedgerName: String = "个人账本",
    val canEditSelectedLedger: Boolean = true,
    val ledgerErrorMessage: String? = null,
    val showFamilyTransactionAuthors: Boolean = false
)

sealed class AiStatus {
    data object Idle : AiStatus()
    data object Processing : AiStatus()
    data class Success(val message: String) : AiStatus()
    data class Error(val message: String) : AiStatus()
}

sealed class VoiceStatus {
    data object Idle : VoiceStatus()
    data object Processing : VoiceStatus()
    data class Success(val text: String) : VoiceStatus()
    data class Error(val message: String) : VoiceStatus()
}

enum class VoiceInputMode {
    SYSTEM,
    CLOUD,
    UNAVAILABLE
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val aiExtractionRepository: AiExtractionRepository,
    private val voiceTranscriptionRepository: VoiceTranscriptionRepository,
    private val secureConfigStore: SecureConfigStore,
    private val systemSpeechRecognitionManager: SystemSpeechRecognitionManager,
    private val ledgerContext: LedgerContext
) : ViewModel() {

    private val _aiStatus = MutableStateFlow<AiStatus>(AiStatus.Idle)
    private val _voiceStatus = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    private val _customCloudPrompt = MutableStateFlow(secureConfigStore.getTextPrompt())

    private val currentMonth = YearMonth.now()

    val uiState: StateFlow<HomeUiState> = combine(
        combine(
            transactionRepository.observeMonthlyExpense(currentMonth),
            transactionRepository.observeMonthlyIncome(currentMonth),
            transactionRepository.observeByMonth(currentMonth),
            categoryRepository.observeExpenseCategories()
        ) { monthExpense, monthIncome, transactions, categories ->
            DataTuple(monthExpense, monthIncome, transactions, categories)
        },
        _aiStatus,
        _voiceStatus,
        _customCloudPrompt,
        ledgerContext.state
    ) { data, aiStatus, voiceStatus, customCloudPrompt, ledgerState ->

        val today = LocalDate.now()
        val todayTransactions = data.transactions.filter { it.date.toLocalDate() == today }
        val todayExpense = todayTransactions
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf { it.amount }
        val todayIncome = todayTransactions
            .filter { it.type == TransactionType.INCOME }
            .sumOf { it.amount }

        HomeUiState(
            todayExpense = todayExpense,
            todayIncome = todayIncome,
            monthExpense = data.monthExpense,
            monthIncome = data.monthIncome,
            recentTransactions = data.transactions.sortedWith(newestTransactionFirst).take(20),
            expenseCategories = if (ledgerState.selectedLedger.isLocal || !ledgerState.isLoading) {
                data.categories
            } else {
                emptyList()
            },
            currentMonth = currentMonth,
            isLoading = ledgerState.isLoading,
            cloudSystemPrompt = AzureOpenAiPromptBuilder.buildBaseSystemPrompt(data.categories.map { it.name }),
            customCloudPrompt = customCloudPrompt,
            aiStatus = aiStatus,
            voiceStatus = voiceStatus,
            isSignedIn = ledgerState.isSignedIn,
            ledgers = ledgerState.ledgers,
            selectedLedgerId = ledgerState.selectedLedgerId,
            selectedLedgerName = ledgerState.selectedLedger.name,
            canEditSelectedLedger = ledgerState.canEdit,
            ledgerErrorMessage = ledgerState.errorMessage,
            showFamilyTransactionAuthors = ledgerState.isSignedIn &&
                ledgerState.selectedLedger.mode == "FAMILY"
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun selectLedger(ledgerId: String) {
        ledgerContext.selectLedger(ledgerId)
    }

    fun submitAiInput(text: String) {
        if (_aiStatus.value is AiStatus.Processing || _aiStatus.value is AiStatus.Success) return
        _aiStatus.value = AiStatus.Processing
        val selection = ledgerContext.state.value.selection

        viewModelScope.launch {
            try {
                ledgerContext.requireEditable(selection)
                val categories = categoryRepository.observeAllCategories().first()
                val categoryNames = categories.map { it.name }

                // Split multi-line input into separate entries
                val lines = text.split("\n", "，", "。", "；")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.length > 1 }

                val results = mutableListOf<String>()
                var lastError: Exception? = null
                for (line in lines) {
                    try {
                        val result = aiExtractionRepository.extract(line, categoryNames).getOrThrow()
                        ledgerContext.requireEditable(selection)
                        val amount = result.amount ?: continue
                        if (amount <= 0) continue
                        val type = if (result.type.equals("income", true)) TransactionType.INCOME else TransactionType.EXPENSE
                        val matchedCategory = categories.find { it.name == result.category && it.type == type }
                        val now = LocalDateTime.now()

                        val transaction = Transaction(
                            amount = amount,
                            type = type,
                            categoryId = matchedCategory?.id,
                            categoryName = matchedCategory?.name ?: result.category,
                            categoryIcon = matchedCategory?.icon,
                            categoryColor = matchedCategory?.color,
                            merchantName = result.merchantName,
                            note = result.note ?: line,
                            originalInput = line,
                            date = resolveTransactionDate(result.date, now),
                            createdAt = now,
                            updatedAt = now,
                            source = TransactionSource.TEXT_AI,
                            status = TransactionStatus.CONFIRMED,
                            syncStatus = SyncStatus.LOCAL,
                            aiConfidence = result.confidence
                        )
                        ledgerContext.requireEditable(selection)
                        transactionRepository.create(transaction).getOrThrow()
                        results.add("${result.category} ${amount}元")
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        ledgerContext.requireEditable(selection)
                        lastError = error
                    }
                }

                if (results.isNotEmpty()) {
                    _aiStatus.value = AiStatus.Success("已记${results.size}笔：${results.joinToString("、")}")
                } else {
                    _aiStatus.value = AiStatus.Error(lastError?.message ?: "未识别到有效账单")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _aiStatus.value = AiStatus.Error(e.message ?: "识别失败")
            }
        }
    }

    fun resetAiStatus() {
        _aiStatus.value = AiStatus.Idle
    }

    fun setCustomCloudPrompt(value: String) {
        secureConfigStore.setTextPrompt(value)
        _customCloudPrompt.value = value
    }

    fun isCloudVoiceConfigured(): Boolean = voiceTranscriptionRepository.isConfigured()

    fun currentVoiceInputMode(): VoiceInputMode {
        val systemSpeechAvailable = systemSpeechRecognitionManager.getAvailability().canUseSystemSpeech
        val cloudConfigured = voiceTranscriptionRepository.isConfigured()
        val preferLocalSpeech = secureConfigStore.isLocalSpeechPreferred()

        return when {
            preferLocalSpeech && systemSpeechAvailable -> VoiceInputMode.SYSTEM
            cloudConfigured -> VoiceInputMode.CLOUD
            systemSpeechAvailable -> VoiceInputMode.SYSTEM
            else -> VoiceInputMode.UNAVAILABLE
        }
    }

    fun buildSystemVoiceRecognitionIntent(): Intent {
        return systemSpeechRecognitionManager.buildRecognitionIntent()
    }

    fun handleSystemVoiceRecognitionResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            _voiceStatus.value = VoiceStatus.Idle
            return
        }

        val text = systemSpeechRecognitionManager.extractBestResult(data)
        _voiceStatus.value = if (text.isNullOrBlank()) {
            VoiceStatus.Error("未识别到有效语音内容")
        } else {
            VoiceStatus.Success(text)
        }
    }

    fun transcribeVoiceInput(audioFile: java.io.File) {
        viewModelScope.launch {
            _voiceStatus.value = VoiceStatus.Processing
            val result = voiceTranscriptionRepository.transcribe(audioFile)
            _voiceStatus.value = result.fold(
                onSuccess = { text ->
                    if (text.isBlank()) {
                        VoiceStatus.Error("未识别到有效语音内容")
                    } else {
                        VoiceStatus.Success(text.trim())
                    }
                },
                onFailure = { error ->
                    VoiceStatus.Error(error.message ?: "云端语音识别失败")
                }
            )
        }
    }

    fun resetVoiceStatus() {
        _voiceStatus.value = VoiceStatus.Idle
    }
}
