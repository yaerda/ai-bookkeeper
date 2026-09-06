package com.aibookkeeper.update

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aibookkeeper.core.data.update.ApkDownloadEvent
import com.aibookkeeper.core.data.update.ApkDownloadProgress
import com.aibookkeeper.core.data.update.ApkDownloader
import com.aibookkeeper.core.data.update.UpdateChecker
import com.aibookkeeper.ui.theme.BookkeeperTheme
import java.nio.file.Files
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfficialUpdateDownloadTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun officialReleaseDownloadsWithVisibleProgressAndVerifiedDigest() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realUpdate") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = requireNotNull(UpdateChecker.checkForUpdate("0.0.0"))
        assertEquals("官方源", info.downloadSourceName)
        assertNotNull(info.sizeBytes)
        assertNotNull(info.sha256)
        val progress = mutableStateOf(ApkDownloadProgress(0, info.sizeBytes, info.downloadSourceName))
        compose.setContent { BookkeeperTheme { UpdateDownloadProgress(progress.value) } }
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "update-network-test-").toFile()
        var complete: ApkDownloadEvent.Complete? = null
        var sawPartialProgress = false
        try {
            ApkDownloader().download(info, directory).collect { event ->
                when (event) {
                    is ApkDownloadEvent.Progress -> {
                        compose.runOnUiThread { progress.value = event.value }
                        if (event.value.downloadedBytes > 0 &&
                            event.value.totalBytes?.let { event.value.downloadedBytes < it } == true
                        ) {
                            if (!sawPartialProgress) {
                                compose.onNodeWithTag("update-download-percent")
                                    .assertTextEquals("${event.value.percent}%")
                            }
                            sawPartialProgress = true
                        }
                    }
                    is ApkDownloadEvent.Complete -> {
                        complete = event
                        compose.runOnUiThread { progress.value = event.progress }
                    }
                }
            }
            val result = requireNotNull(complete)
            assertEquals("官方源", result.progress.sourceName)
            assertEquals(info.sizeBytes, result.file.length())
            assertEquals(100, result.progress.percent)
            assertTrue(sawPartialProgress)
            compose.onNodeWithTag("update-download-percent").assertTextEquals("100%")
        } finally {
            directory.listFiles().orEmpty().forEach { Files.deleteIfExists(it.toPath()) }
            Files.deleteIfExists(directory.toPath())
        }
    }
}
