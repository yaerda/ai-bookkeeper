package com.aibookkeeper.feature.input.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.ProjectBinding
import com.aibookkeeper.core.data.model.ProjectDefaultsAvailability
import com.aibookkeeper.core.data.model.ProjectLedgerState
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.LOCAL_LEDGER_ID
import com.aibookkeeper.core.data.repository.LedgerContextState
import com.aibookkeeper.feature.input.detail.DetailUiState
import com.aibookkeeper.feature.input.detail.TransactionDetailScreen
import com.aibookkeeper.feature.input.detail.TransactionDetailViewModel
import com.aibookkeeper.feature.input.home.VoiceStatus
import com.aibookkeeper.feature.input.quick.QuickInputSheet
import com.aibookkeeper.feature.input.quick.QuickInputUiState
import com.aibookkeeper.feature.input.quick.QuickInputViewModel
import com.aibookkeeper.feature.input.text.TextInputScreen
import com.aibookkeeper.feature.input.text.TextInputUiState
import com.aibookkeeper.feature.input.text.TextInputViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectSelectionRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val noProjects = "\u4e0d\u5173\u8054\u9879\u76ee"
    private val firstProject = "11111111-1111-4111-8111-111111111111"
    private val nextProject = "22222222-2222-4222-8222-222222222222"
    private val historicalProject = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val category = Category(1, "Food", "ic_food", "#123456", TransactionType.EXPENSE)

    private fun binding(id: String, enabled: Boolean = true) = ProjectBinding(
        projectId = id, ledgerId = LOCAL_LEDGER_ID,
        name = if (enabled) "Current project" else "Historical",
        enabled = enabled, startDate = null, endDate = null,
        timeZone = "Asia/Shanghai", version = 1, active = enabled, canEdit = true
    )

    private fun projects(vararg items: ProjectBinding) = ProjectLedgerState(
        accountId = "synthetic-account", ledgerId = LOCAL_LEDGER_ID,
        role = "OWNER", projects = items.toList(), availability = ProjectDefaultsAvailability.LIVE
    )

    private fun launchText(
        state: TextInputUiState,
        projectState: MutableStateFlow<ProjectLedgerState>,
        initialCategoryId: Long? = null
    ): TextInputViewModel {
        val vm = mockk<TextInputViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(state)
        every { vm.categories } returns MutableStateFlow(listOf(category))
        every { vm.ledgerState } returns MutableStateFlow(LedgerContextState())
        every { vm.projectState } returns projectState
        every { vm.voiceStatus } returns MutableStateFlow(VoiceStatus.Idle)
        compose.setContent {
            MaterialTheme {
                TextInputScreen(
                    navController = rememberNavController(), viewModel = vm,
                    initialCategoryId = initialCategoryId
                )
            }
        }
        return vm
    }

    private fun preview() = TextInputUiState.Preview(
        amount = 10.0, category = "Food", note = "Synthetic", merchantName = null,
        date = "2026-09-01", confidence = 0.9f, originalInput = "Synthetic"
    )

    @Test
    fun previewOptOutSurvivesCachedToLiveDefaults() {
        val state = MutableStateFlow(projects(binding(firstProject)))
        val vm = launchText(preview(), state)
        compose.onNodeWithText(noProjects).performScrollTo().performClick()
        compose.runOnIdle { state.value = projects(binding(nextProject)) }
        compose.onNodeWithTag("preview-project-save").performScrollTo().performClick()
        compose.runOnIdle { verify(exactly = 1) { vm.confirmSave(emptyList()) } }
    }

    @Test
    fun previewHistoricalChoiceSurvivesDefaultRefresh() {
        val state = MutableStateFlow(projects(binding(firstProject), binding(historicalProject, false)))
        val vm = launchText(preview(), state)
        compose.onNodeWithText(noProjects).performScrollTo().performClick()
        compose.onNodeWithText("Historical", substring = true).performScrollTo().performClick()
        compose.runOnIdle { state.value = projects(binding(nextProject), binding(historicalProject, false)) }
        compose.onNodeWithTag("preview-project-save").performScrollTo().performClick()
        compose.runOnIdle { verify(exactly = 1) { vm.confirmSave(listOf(historicalProject)) } }
    }

    @Test
    fun untouchedPreviewKeepsAutomaticSaveTimeIntent() {
        val state = MutableStateFlow(projects(binding(firstProject)))
        val vm = launchText(preview(), state)
        compose.onNodeWithTag("preview-project-save").performScrollTo().performClick()
        compose.runOnIdle { verify(exactly = 1) { vm.confirmSave(null) } }
    }

    @Test
    fun manualOptOutSurvivesCachedToLiveDefaults() {
        val state = MutableStateFlow(projects(binding(firstProject)))
        val vm = launchText(TextInputUiState.Idle, state, initialCategoryId = 1)
        compose.onNodeWithTag("manual-project-amount").performTextInput("12.00")
        compose.onNodeWithText(noProjects).performScrollTo().performClick()
        compose.runOnIdle { state.value = projects(binding(nextProject)) }
        compose.onNodeWithTag("manual-project-save").performScrollTo().performClick()
        compose.runOnIdle {
            verify(exactly = 1) {
                vm.saveManual(12.0, 1L, "Food", null, TransactionType.EXPENSE, any(), emptyList())
            }
        }
    }

    @Test
    fun quickPreviewPreservesOptOutAcrossProjectRefresh() {
        val state = MutableStateFlow(projects(binding(firstProject)))
        val vm = mockk<QuickInputViewModel>(relaxed = true)
        every { vm.projectState } returns state
        every { vm.ledgerState } returns MutableStateFlow(LedgerContextState())
        every { vm.uiState } returns MutableStateFlow<QuickInputUiState>(
            QuickInputUiState.Preview(10.0, "Food", "Synthetic", "2026-09-01", 0.9f, "Synthetic")
        )
        compose.setContent {
            MaterialTheme { QuickInputSheet(vm, onDismiss = {}, onOpenFullEditor = {}) }
        }
        compose.onNodeWithText(noProjects).performScrollTo().performClick()
        compose.runOnIdle { state.value = projects(binding(nextProject)) }
        compose.onNodeWithText("\u786e\u8ba4\u4fdd\u5b58").performScrollTo().performClick()
        compose.runOnIdle { verify(exactly = 1) { vm.confirmSave(emptyList()) } }
    }

    @Test
    fun unrelatedDetailEditDoesNotRestoreTagsRemovedBySync() {
        val now = LocalDateTime.of(2026, 9, 1, 12, 0)
        val transaction = Transaction(
            id = 1, amount = 10.0, type = TransactionType.EXPENSE,
            categoryId = 1, categoryName = "Food", date = now, createdAt = now,
            updatedAt = now, source = TransactionSource.MANUAL,
            status = TransactionStatus.CONFIRMED, syncStatus = SyncStatus.SYNCED,
            projectIds = listOf(firstProject)
        )
        val state = MutableStateFlow<DetailUiState>(DetailUiState.Loaded(transaction))
        val vm = mockk<TransactionDetailViewModel>(relaxed = true)
        every { vm.uiState } returns state
        every { vm.projectState } returns MutableStateFlow(projects(binding(firstProject)))
        every { vm.ledgerState } returns MutableStateFlow(LedgerContextState())
        every { vm.categories } returns MutableStateFlow(listOf(category))
        every { vm.errorMessage } returns MutableStateFlow(null)
        compose.setContent {
            MaterialTheme { TransactionDetailScreen(rememberNavController(), viewModel = vm) }
        }
        compose.onNode(hasClickAction() and hasText("\u7f16\u8f91", substring = true))
            .performScrollTo().performClick()
        compose.runOnIdle { state.value = DetailUiState.Loaded(transaction.copy(projectIds = emptyList())) }
        compose.onNodeWithText("\u4fdd\u5b58").performScrollTo().performClick()
        compose.runOnIdle {
            verify(exactly = 1) { vm.updateTransaction(any(), any(), any(), any(), any(), null) }
        }
    }
}
