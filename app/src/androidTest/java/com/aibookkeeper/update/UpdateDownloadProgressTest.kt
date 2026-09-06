package com.aibookkeeper.update

import android.text.format.Formatter
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aibookkeeper.core.data.update.ApkDownloadPhase
import com.aibookkeeper.core.data.update.ApkDownloadProgress
import com.aibookkeeper.ui.theme.BookkeeperTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDownloadProgressTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun knownSizeShowsPercentageDownloadedBytesAndTotal() {
        val state = mutableStateOf(ApkDownloadProgress(21_000_000, 50_000_000, "官方源"))
        compose.setContent { BookkeeperTheme { UpdateDownloadProgress(state.value) } }

        compose.onNodeWithTag("update-download-progress").assertIsDisplayed()
        compose.onNodeWithTag("update-download-percent").assertTextEquals("42%")
        compose.onNodeWithTag("update-download-size").assertTextEquals(
            "${Formatter.formatShortFileSize(context, 21_000_000)} / ${Formatter.formatShortFileSize(context, 50_000_000)}"
        )
        compose.runOnIdle {
            state.value = ApkDownloadProgress(50_000_000, 50_000_000, "官方源", ApkDownloadPhase.COMPLETE)
        }
        compose.onNodeWithTag("update-download-percent").assertTextEquals("100%")
        compose.onNodeWithText("下载完成").assertIsDisplayed()
    }

    @Test
    fun unknownSizeShowsReceivedBytesAndIndeterminateProgress() {
        compose.setContent {
            BookkeeperTheme { UpdateDownloadProgress(ApkDownloadProgress(5_000_000, null, "GitHub")) }
        }

        compose.onNodeWithTag("update-download-progress").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
        )
        compose.onNodeWithTag("update-download-size")
            .assertTextEquals("已下载 ${Formatter.formatShortFileSize(context, 5_000_000)}（总大小未知）")
    }

    @Test
    fun fallbackAndVerificationAreVisibleInsteadOfAnUnexplainedStall() {
        val state = mutableStateOf(ApkDownloadProgress(
            0, 50_000_000, "GitHub", ApkDownloadPhase.CONNECTING, "官方源下载失败，已切换备用源"
        ))
        compose.setContent { BookkeeperTheme { UpdateDownloadProgress(state.value) } }

        compose.onNodeWithText("正在连接GitHub…").assertIsDisplayed()
        compose.onNodeWithText("官方源下载失败，已切换备用源").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(downloadedBytes = 50_000_000, phase = ApkDownloadPhase.VERIFYING)
        }
        compose.onNodeWithText("正在校验更新包…").assertIsDisplayed()
        compose.onNodeWithTag("update-download-percent").assertTextEquals("100%")
    }
}
