package com.aibookkeeper.feature.input.components

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.aibookkeeper.feature.input.home.HomeScreen
import com.aibookkeeper.feature.input.home.HomeUiState
import com.aibookkeeper.feature.input.home.HomeViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class HomeVoiceRegressionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val microphone: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private class Engine : SpeechInputEngine {
        @Volatile var listener: SpeechInputEngine.Listener? = null
        @Volatile var stops = 0
        @Volatile var cancels = 0
        override fun start(listener: SpeechInputEngine.Listener) { this.listener = listener }
        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
        override fun close() {}
    }

    private val engines = CopyOnWriteArrayList<Engine>()
    private val factory: (Context) -> SpeechInputEngine = { Engine().also(engines::add) }

    private fun launchHome(engineFactory: (Context) -> SpeechInputEngine = factory): HomeViewModel {
        val viewModel = mockk<HomeViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(HomeUiState(isLoading = false))
        compose.setContent {
            CompositionLocalProvider(LocalSpeechInputEngineFactory provides engineFactory) {
                MaterialTheme { HomeScreen(rememberNavController(), viewModel = viewModel) }
            }
        }
        return viewModel
    }

    private fun holdHomeButton(): Engine {
        compose.onNodeWithContentDescription("AI 记账").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(800)
        compose.waitUntil(5_000) { engines.firstOrNull()?.listener != null }
        return engines.last()
    }

    @Test
    fun homeHoldDeliversRecognizedText() {
        val viewModel = launchHome()
        val engine = holdHomeButton()
        compose.runOnIdle { engine.listener!!.ready() }
        compose.mainClock.advanceTimeBy(3_000)
        compose.runOnIdle {
            assertEquals(0, engine.stops)
            engine.listener!!.partial("午饭")
        }
        compose.onNodeWithContentDescription("AI 记账").performTouchInput { up() }
        compose.waitUntil(5_000) { engine.stops == 1 }
        compose.runOnIdle {
            assertEquals(0, engine.cancels)
            engine.listener!!.result("午饭35元")
        }
        compose.onAllNodes(hasSetTextAction()).onFirst().assertTextContains("午饭35元")
        compose.onNodeWithTag("home-voice-submit").performTouchInput {
            down(center)
            up()
        }
        compose.runOnIdle { verify(exactly = 1) { viewModel.submitAiInput("午饭35元") } }
    }

    @Test
    fun releaseBeforeReadyStillCapturesAResult() {
        launchHome()
        val engine = holdHomeButton()
        compose.onNodeWithContentDescription("AI 记账").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(3_000)
        compose.runOnIdle {
            assertEquals(0, engine.stops)
            engine.listener!!.ready()
        }
        compose.waitUntil(5_000) { engine.stops == 1 }
        compose.runOnIdle { engine.listener!!.result("买菜20元") }
        compose.onAllNodes(hasSetTextAction()).onFirst().assertTextContains("买菜20元")
    }

    @Test
    fun failedRecognitionCanBeRetriedInsideTheSheet() {
        launchHome()
        val first = holdHomeButton()
        compose.runOnIdle { first.listener!!.error("识别失败，请重试") }
        compose.onNodeWithContentDescription("AI 记账").performTouchInput { up() }
        compose.onNodeWithTag("home-voice-submit").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(800)
        compose.waitUntil(5_000) { engines.size == 2 }
        val retry = engines.last()
        compose.runOnIdle { retry.listener!!.ready() }
        compose.onNodeWithTag("home-voice-submit").performTouchInput { up() }
        compose.waitUntil(5_000) { retry.stops == 1 }
        compose.runOnIdle { retry.listener!!.result("重试买菜20元") }
        compose.onAllNodes(hasSetTextAction()).onFirst().assertTextContains("重试买菜20元")
    }

    @Test
    fun cancelledShortPressDoesNotStartRecording() {
        launchHome()
        compose.onNodeWithContentDescription("AI 记账").performTouchInput {
            down(center)
            cancel()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertTrue(engines.isEmpty()) }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun nativeSpeechFixtureReachesTheInputField() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realSpeech") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "voice-regression.pcm")
        InstrumentationRegistry.getInstrumentation().context.assets.open("voice-regression.pcm").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        try {
            ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val events = CopyOnWriteArrayList<String>()
                launchHome { engineContext ->
                    val delegate = AndroidSpeechInputEngine(engineContext) { packageName ->
                        holdToTalkRecognitionIntent(packageName).apply {
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor)
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        }
                    }
                    object : SpeechInputEngine by delegate {
                        override fun start(listener: SpeechInputEngine.Listener) {
                            events += "start"
                            delegate.start(object : SpeechInputEngine.Listener {
                                override fun ready() { events += "ready"; listener.ready() }
                                override fun partial(text: String) { events += "partial:$text"; listener.partial(text) }
                                override fun ended() { events += "ended"; listener.ended() }
                                override fun result(text: String) { events += "result:$text"; listener.result(text) }
                                override fun error(message: String) { events += "error:$message"; listener.error(message) }
                            })
                        }
                        override fun stop() { events += "stop"; delegate.stop() }
                        override fun close() { events += "close"; delegate.close() }
                    }
                }
                compose.mainClock.autoAdvance = false
                compose.onNodeWithContentDescription("AI 记账").performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(800)
                repeat(100) {
                    Thread.sleep(100)
                    compose.mainClock.advanceTimeBy(100)
                }
                compose.onNodeWithContentDescription("AI 记账").performTouchInput { up() }
                var matched = false
                for (attempt in 0 until 300) {
                    Thread.sleep(100)
                    compose.mainClock.advanceTimeBy(100)
                    matched = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().any { node ->
                        val text = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
                        text.contains("午饭") && (text.contains("35") || text.contains("三十五"))
                    }
                    if (matched || events.any { it.startsWith("error:") } || "close" in events) break
                }
                assertTrue("Native recognition events: $events", matched)
            }
        } finally {
            check(fixture.delete() || !fixture.exists())
        }
    }

    @Test
    fun recognitionIntentDoesNotImposeTheRegressedShortSilenceTimeout() {
        val intent = holdToTalkRecognitionIntent("test.bookkeeper")
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertEquals("zh-CN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS))
        assertTrue(intent.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0) >= 15_000)
    }
}
