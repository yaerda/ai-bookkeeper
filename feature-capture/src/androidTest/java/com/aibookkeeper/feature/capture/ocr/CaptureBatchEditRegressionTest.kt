package com.aibookkeeper.feature.capture.ocr

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.compose.rememberNavController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aibookkeeper.core.data.ai.ExtractionCategoryProvider
import com.aibookkeeper.core.data.ai.ExtractionStrategyManager
import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.VisionExtractionResult
import com.aibookkeeper.core.data.repository.TransactionRepository
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

@RunWith(AndroidJUnit4::class)
class CaptureBatchEditRegressionTest {
    @get:Rule val compose = createComposeRule()

    private val saved = CopyOnWriteArrayList<Transaction>()
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
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("识别结果")).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun closeCommitsDeletionsAndEditsThroughReopenAndRepositorySave() {
        launchRecognition()
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
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()

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
        compose.onNode(isToggleable()).performClick()
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
        compose.onNode(isToggleable()).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("午餐 ¥28.50\n公交 ¥3.00\n工资 ¥50.00")
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("capture-text-editor").performTextReplacement("工资 ¥60.00")
        compose.onNodeWithContentDescription("关闭").performClick()
        SystemClock.sleep(1_200)
        compose.onNode(isToggleable()).performClick()
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
}
