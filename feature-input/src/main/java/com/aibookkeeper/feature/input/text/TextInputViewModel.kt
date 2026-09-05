package com.aibookkeeper.feature.input.text

import com.aibookkeeper.core.common.extensions.resolveTransactionDate

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.common.util.CategoryIconMapper
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.model.normalizeCategoryName
import com.aibookkeeper.core.data.repository.AiExtractionRepository
import com.aibookkeeper.core.data.repository.CategoryRepository
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerSelection
import com.aibookkeeper.core.data.repository.requireEditable
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.core.data.repository.VoiceTranscriptionRepository
import com.aibookkeeper.core.data.security.SecureConfigStore
import com.aibookkeeper.core.data.speech.SystemSpeechRecognitionManager
import com.aibookkeeper.feature.input.home.VoiceInputMode
import com.aibookkeeper.feature.input.home.VoiceStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

sealed interface TextInputUiState {
    data object Idle : TextInputUiState
    data object Extracting : TextInputUiState
    data class Preview(
        val amount: Double,
        val category: String,
        val note: String?,
        val merchantName: String?,
        val date: String,
        val confidence: Float,
        val originalInput: String
    ) : TextInputUiState
    data object Saving : TextInputUiState
    data class Success(
        val transactionId: Long,
        val amount: Double,
        val category: String
    ) : TextInputUiState
    data class Error(val message: String) : TextInputUiState
}

@HiltViewModel
class TextInputViewModel @Inject constructor(
    private val aiExtractionRepository: AiExtractionRepository,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val voiceTranscriptionRepository: VoiceTranscriptionRepository,
    private val secureConfigStore: SecureConfigStore,
    private val systemSpeechRecognitionManager: SystemSpeechRecognitionManager,
    private val ledgerContext: LedgerContext
) : ViewModel() {

    private val _uiState = MutableStateFlow<TextInputUiState>(TextInputUiState.Idle)
    private val _voiceStatus = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    val uiState: StateFlow<TextInputUiState> = _uiState.asStateFlow()
    val voiceStatus: StateFlow<VoiceStatus> = _voiceStatus.asStateFlow()
    val ledgerState = ledgerContext.state

    val categories = categoryRepository.observeAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var lastExtractionResult: ExtractionResult? = null
    private var extractionSelection: LedgerSelection? = null

    fun submitText(
        input: String,
        selection: LedgerSelection = ledgerState.value.selection
    ) {
        if (input.isBlank()) {
            _uiState.value = TextInputUiState.Error("请输入记账内容")
            return
        }
        viewModelScope.launch {
            _uiState.value = TextInputUiState.Extracting
            runCatching {
                ledgerContext.requireEditable(selection)
                val result = aiExtractionRepository.extract(input).getOrThrow()
                ledgerContext.requireEditable(selection)
                result
            }
                .onSuccess { result ->
                    lastExtractionResult = result
                    extractionSelection = selection
                    _uiState.value = TextInputUiState.Preview(
                        amount = result.amount ?: 0.0,
                        category = result.category,
                        note = result.note,
                        merchantName = result.merchantName,
                        date = result.date,
                        confidence = result.confidence,
                        originalInput = input
                    )
                }
                .onFailure { error ->
                    showError(error, "AI 提取失败，请重试")
                }
        }
    }

    fun confirmSave() {
        val preview = _uiState.value as? TextInputUiState.Preview ?: return
        val extraction = lastExtractionResult ?: return
        val selection = extractionSelection ?: return
        _uiState.value = TextInputUiState.Saving

        viewModelScope.launch {
            runCatching {
                ledgerContext.requireEditable(selection)
                val now = LocalDateTime.now()
                val txType = when (extraction.type.uppercase()) {
                    "INCOME" -> TransactionType.INCOME
                    else -> TransactionType.EXPENSE
                }
                val category = categoryRepository.findByNameAndType(extraction.category, txType)
                val txDate = resolveTransactionDate(extraction.date, now)
                val transaction = Transaction(
                    amount = preview.amount,
                    type = txType,
                    categoryId = category?.id,
                    categoryName = category?.name ?: extraction.category,
                    categoryIcon = category?.icon,
                    categoryColor = category?.color,
                    merchantName = extraction.merchantName,
                    note = extraction.note,
                    originalInput = preview.originalInput,
                    date = txDate,
                    createdAt = now,
                    updatedAt = now,
                    source = TransactionSource.TEXT_AI,
                    status = if (extraction.confidence >= 0.7f) {
                        TransactionStatus.CONFIRMED
                    } else {
                        TransactionStatus.PENDING
                    },
                    syncStatus = SyncStatus.LOCAL,
                    aiConfidence = extraction.confidence
                )
                ledgerContext.requireEditable(selection)
                transactionRepository.create(transaction).getOrThrow()
            }
                .onSuccess { id ->
                    _uiState.value = TextInputUiState.Success(
                        transactionId = id,
                        amount = preview.amount,
                        category = extraction.category
                    )
                }
                .onFailure { error ->
                    showError(error, "保存失败")
                }
        }
    }

    fun saveManual(
        amount: Double,
        categoryId: Long?,
        categoryName: String,
        note: String?,
        type: TransactionType,
        selection: LedgerSelection = ledgerState.value.selection
    ) {
        if (_uiState.value !is TextInputUiState.Idle) return
        _uiState.value = TextInputUiState.Saving

        viewModelScope.launch {
            runCatching {
                ledgerContext.requireEditable(selection)
                val category = if (categoryId == null) {
                    categoryRepository.findByNameAndType(categoryName, type)
                } else {
                    categoryRepository.getById(categoryId).also {
                        check(it != null && it.type == type &&
                            normalizeCategoryName(it.name) == normalizeCategoryName(categoryName)) {
                            "分类已变化，请重新选择"
                        }
                    }
                }
                val now = LocalDateTime.now()
                val transaction = Transaction(
                    amount = amount,
                    type = type,
                    categoryId = category?.id,
                    categoryName = category?.name ?: categoryName,
                    categoryIcon = category?.icon,
                    categoryColor = category?.color,
                    note = note,
                    date = now,
                    createdAt = now,
                    updatedAt = now,
                    source = TransactionSource.MANUAL,
                    status = TransactionStatus.CONFIRMED,
                    syncStatus = SyncStatus.LOCAL
                )
                ledgerContext.requireEditable(selection)
                transactionRepository.create(transaction).getOrThrow()
            }
                .onSuccess { id ->
                    _uiState.value = TextInputUiState.Success(
                        transactionId = id,
                        amount = amount,
                        category = categoryName
                    )
                }
                .onFailure { error ->
                    showError(error, "保存失败")
                }
        }
    }

    fun resetToIdle() {
        _uiState.value = TextInputUiState.Idle
        lastExtractionResult = null
        extractionSelection = null
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

    fun transcribeVoiceInput(audioFile: File) {
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

    fun addCategory(
        name: String,
        icon: String = CategoryIconMapper.DEFAULT_ICON_KEY,
        type: TransactionType = TransactionType.EXPENSE,
        selection: LedgerSelection = ledgerState.value.selection
    ) {
        viewModelScope.launch {
            runCatching {
                ledgerContext.requireEditable(selection)
                categoryRepository.create(
                    Category(
                        name = name.trim(),
                        icon = normalizeCategoryIcon(icon),
                        color = "#607D8B",
                        type = type,
                        isSystem = false
                    )
                ).getOrThrow()
            }.onFailure { showError(it, "新增分类失败") }
        }
    }

    fun updateCategory(
        category: Category,
        newName: String,
        newIcon: String,
        selection: LedgerSelection = ledgerState.value.selection
    ) {
        viewModelScope.launch {
            runCatching {
                ledgerContext.requireEditable(selection)
                check(ledgerContext.state.value.canUpdateCategories) {
                    "已接入云同步的分类暂不支持修改，可新增分类"
                }
                categoryRepository.update(
                    category.copy(
                        name = newName.trim(),
                        icon = normalizeCategoryIcon(newIcon)
                    )
                ).getOrThrow()
            }.onFailure { showError(it, "修改分类失败") }
        }
    }

    private fun showError(error: Throwable, fallback: String) {
        if (error is CancellationException) throw error
        _uiState.value = TextInputUiState.Error(error.message ?: fallback)
    }

    private fun normalizeCategoryIcon(icon: String): String =
        icon.trim().ifBlank { CategoryIconMapper.DEFAULT_ICON_KEY }
}
