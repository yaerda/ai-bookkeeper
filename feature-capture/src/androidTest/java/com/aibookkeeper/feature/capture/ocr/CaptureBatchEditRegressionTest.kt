package com.aibookkeeper.feature.capture.ocr

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aibookkeeper.core.data.ai.ExtractionCategoryProvider
import com.aibookkeeper.core.data.ai.ExtractionStrategyManager
import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.ProjectBinding
import com.aibookkeeper.core.data.model.ProjectDefaultsAvailability
import com.aibookkeeper.core.data.model.ProjectLedgerState
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.VisionExtractionResult
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.core.data.repository.ProjectRepository
import com.aibookkeeper.core.data.repository.ProjectWriteDestination
import com.aibookkeeper.feature.capture.test.R
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(AndroidJUnit4::class)
class CaptureBatchEditRegressionTest {
    @get:Rule val compose = createComposeRule()

    private val saved = CopyOnWriteArrayList<Transaction>()
    private val projectState = MutableStateFlow(ProjectLedgerState())
    private val text = "午餐 ¥26.00\n公交 ¥3.00\n工资 ¥50.00"
    private val items = listOf(
        item(26.0, "EXPENSE", "餐饮", "午餐", "2026-09-01"),
        item(3.0, "EXPENSE", "交通", "公交", "2026-09-02"),
        item(50.0, "INCOME", "工资", "工资", "2026-09-03")
    )

    @Before
    fun stubToasts() {
        // Compose's effect test dispatcher can resume on a thread without an Android Looper.
        mockkStatic(Toast::class)
        every { Toast.makeText(any<Context>(), any<CharSequence>(), any()) } returns mockk(relaxed = true)
    }

    @After
    fun restoreToasts() {
        unmockkStatic(Toast::class)
    }

    private fun launchRecognition() {
        val repository = object : TransactionRepository by mockk() {
            override suspend fun create(transaction: Transaction): Result<Long> {
                saved.add(transaction)
                return Result.success(saved.size.toLong())
            }
            override suspend fun createAllValidated(
                transactions: List<Transaction>,
                beforePersist: () -> Unit
            ): Result<List<Long>> {
                transactions.forEach { beforePersist() }
                beforePersist()
                val offset = saved.size
                saved.addAll(transactions)
                return Result.success(transactions.indices.map { offset + it + 1L })
            }
        }
        val categories = listOf(
            category(1, "餐饮", "EXPENSE"),
            category(2, "交通", "EXPENSE"),
            category(3, "工资", "INCOME"),
            category(4, "其他", "EXPENSE"),
            category(5, "其他", "INCOME")
        )
        val dao = mockk<CategoryDao>()
        coEvery { dao.findByNameAndType(any(), any()) } answers {
            categories.firstOrNull { it.name == firstArg<String>() && it.type == secondArg<String>() }
        }
        val summary = items.first().copy(amount = 79.0, date = "2026-09-06")
        val strategy = mockk<ExtractionStrategyManager>()
        every { strategy.isAiConfigured } returns true
        coEvery { strategy.extractFromImageDetailed(any(), any(), any()) } returns
            Result.success(VisionExtractionResult(text, summary, items))
        coEvery { strategy.extractFromOcr(any(), any()) } returns Result.success(summary)
        val categoryProvider = mockk<ExtractionCategoryProvider>()
        coEvery { categoryProvider.getCategoryNames(any()) } returns categories.map { it.name }
        val entryPoint = mockk<CaptureScreenEntryPoint>()
        every { entryPoint.notificationExtractionPipeline() } returns mockk(relaxed = true)
        every { entryPoint.transactionRepository() } returns repository
        every { entryPoint.extractionStrategyManager() } returns strategy
        every { entryPoint.extractionCategoryProvider() } returns categoryProvider
        every { entryPoint.categoryDao() } returns dao
        val projects = mockk<ProjectRepository>()
        val destination = ProjectWriteDestination("synthetic-account", "default", defaultRoom = true, contextVersion = 1)
        every { projects.defaultLedgerState } returns projectState
        every { projects.captureDestination(true) } returns destination
        every { projects.requireCurrentDestination(destination) } returns Unit
        every { entryPoint.projectRepository() } returns projects

        val context = InstrumentationRegistry.getInstrumentation().context
        val imageUri = "android.resource://${context.packageName}/${R.drawable.capture_fixture}"
        compose.setContent {
            MaterialTheme {
                CaptureScreen(
                    rememberNavController(), entryPoint = entryPoint, initialImageUri = imageUri
                )
            }
        }
        compose.onNodeWithText("🤖 AI识别").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("✨ 保存3笔")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("✨ 保存3笔").assertIsEnabled()
        coVerify(exactly = 1) { strategy.extractFromImageDetailed(any(), "image/jpeg", any()) }
    }

    private fun editText(value: String) {
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("capture-text-editor").performTextReplacement(value)
    }

    private fun saveAndAwaitCompletion(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("识别结果")).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun closeCommitsDeletionsAndEditsThroughReopenAndRepositorySave() {
        launchRecognition()
        compose.onNodeWithText("不关联项目").performScrollTo().performClick()
        compose.runOnIdle {
            projectState.value = ProjectLedgerState(
                accountId = "synthetic-account", ledgerId = "default", role = "OWNER", contextVersion = 1,
                availability = ProjectDefaultsAvailability.LIVE,
                projects = listOf(ProjectBinding(
                    "11111111-1111-4111-8111-111111111111", "default", "Loaded project",
                    true, null, null, "Asia/Shanghai", 1, true, true
                ))
            )
        }
        val edited = "午餐 ¥28.50\n本月奖金 ¥60.00"
        editText("午餐 ¥28.50\n工资 ¥55.00")
        compose.onNodeWithTag("capture-text-editor").performTextReplacement(edited)
        compose.onNodeWithContentDescription("关闭").performClick()

        compose.onNodeWithText("公交 ¥3.00").assertDoesNotExist()
        compose.onNodeWithText("午餐 ¥28.50").assertExists()
        compose.onNodeWithText("本月奖金 ¥60.00").assertExists()
        compose.onNodeWithText("餐饮").assertExists()
        compose.onNodeWithText("工资").assertExists()
        compose.onNodeWithText("9/1").assertExists()
        compose.onNodeWithText("9/3").assertExists()
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("capture-text-editor").assertTextEquals(edited)
        compose.onNodeWithText("完成").performClick()
        saveAndAwaitCompletion("✨ 保存2笔")

        assertEquals(listOf("午餐", "本月奖金"), saved.map { it.note })
        assertEquals(listOf(28.5, 60.0), saved.map { it.amount })
        assertEquals(listOf(1L, 3L), saved.map { it.categoryId })
        assertEquals(listOf("EXPENSE", "INCOME"), saved.map { it.type.name })
        assertTrue(saved.all { it.originalInput == edited })
        assertTrue(saved.all { it.date.toLocalDate().toString() == "2026-09-06" })
        assertTrue(saved.all { it.projectIds == emptyList<String>() })
        assertEquals(2, saved.size)
    }

    @Test
    fun doneCommitsFirstRowDeletionAndRetainedDescriptionEdit() {
        launchRecognition()
        editText("公交 ¥3.00\n本月工资 ¥50.00")
        compose.onNodeWithText("完成").performClick()
        saveAndAwaitCompletion("✨ 保存2笔")

        assertEquals(listOf("公交", "本月工资"), saved.map { it.note })
        assertEquals(listOf(3.0, 50.0), saved.map { it.amount })
        assertEquals(listOf(2L, 3L), saved.map { it.categoryId })
        assertEquals(listOf("EXPENSE", "INCOME"), saved.map { it.type.name })
        assertEquals(2, saved.size)
    }

    @Test
    fun systemBackCommitsDeletingAllAndNeitherSaveModeCanRestoreItems() {
        launchRecognition()
        editText("")
        repeat(2) {
            if (compose.onAllNodes(hasText("编辑识别文本"))
                    .fetchSemanticsNodes().isNotEmpty()) {
                // Back may first dismiss the IME; send it to the focused window, not Espresso's activity root.
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.waitForIdle()
            }
        }

        compose.onNodeWithText("编辑识别文本").assertDoesNotExist()
        compose.onNodeWithText("✨ AI记账").assertIsNotEnabled()
        compose.onNodeWithText("编辑").performClick()
        assertEquals(
            "",
            compose.onNodeWithTag("capture-text-editor").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text
        )
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("✨ AI记账").assertIsNotEnabled()
        compose.onNodeWithTag("capture-split-mode").performClick()
        compose.onNodeWithText("✨ AI记账").assertIsNotEnabled()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun explicitCancelOfImageReplacementKeepsTheCommittedEditedBatch() {
        launchRecognition()
        editText("工资 ¥50.00")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNode(hasText("🖼️ 相册") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("更换图片").assertDoesNotExist()
        saveAndAwaitCompletion("✨ AI记账")

        assertEquals(listOf("工资"), saved.map { it.note })
        assertEquals(listOf(3L), saved.map { it.categoryId })
        assertEquals(listOf("INCOME"), saved.map { it.type.name })
        assertEquals(1, saved.size)
    }

    @Test
    fun pendingInlineUpdateCannotResurrectRowsAfterTheEditorCloses() {
        launchRecognition()
        compose.onNodeWithTag("capture-split-mode").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("午餐 ¥28.50\n公交 ¥3.00\n工资 ¥50.00")
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("capture-text-editor").performTextReplacement("工资 ¥60.00")
        compose.onNodeWithContentDescription("关闭").performClick()
        SystemClock.sleep(1_200)
        compose.onNodeWithTag("capture-split-mode").performClick()
        saveAndAwaitCompletion("✨ AI记账")

        assertEquals(listOf("工资"), saved.map { it.note })
        assertEquals(listOf(60.0), saved.map { it.amount })
        assertEquals(listOf(3L), saved.map { it.categoryId })
        assertEquals(1, saved.size)
    }

    private fun item(amount: Double, type: String, category: String, note: String, date: String) =
        ExtractionResult(amount, type, category, "测试商户", date, note, 0.9f)

    private fun category(id: Long, name: String, type: String) =
        CategoryEntity(id = id, name = name, icon = "ic_other", color = "#607D8B", type = type)

    @Test
    fun narrowPickerKeepsEveryHistoricalProjectReachable() {
        val bindings = (1..12).map { index ->
            ProjectBinding(
                java.util.UUID.nameUUIDFromBytes("project-$index".toByteArray()).toString(),
                "default", "Historical project number $index", false, null, null, "Asia/Shanghai",
                1, false, true
            )
        }
        val selection = mutableStateOf<List<String>?>(emptyList())
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(240.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    CaptureProjectSelectionSection(
                        ProjectLedgerState(projects = bindings, availability = ProjectDefaultsAvailability.LIVE),
                        selection.value, { selection.value = it }
                    )
                }
            }
        }
        compose.onNodeWithText(bindings.last().name, substring = true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(bindings.last().projectId), selection.value) }
    }
}
