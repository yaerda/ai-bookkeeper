package com.aibookkeeper.feature.input.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechInputSessionTest {
    private class Engine : SpeechInputEngine {
        lateinit var listener: SpeechInputEngine.Listener
        var stops = 0
        var cancels = 0
        var closes = 0
        override fun start(listener: SpeechInputEngine.Listener) { this.listener = listener }
        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
        override fun close() { closes++ }
    }

    @Test
    fun `release before microphone readiness never stops an unready recognizer`() = runTest {
        val engine = Engine()
        val texts = mutableListOf<String>()
        val session = SpeechInputSession(this, { engine }, texts::add, {})
        session.start()
        session.release()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(0, engine.stops)
        assertEquals(SpeechPhase.STARTING, session.state.value.phase)
        engine.listener.ready()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, engine.stops)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, engine.stops)
        assertTrue(session.state.value.isProcessing)
        engine.listener.result(" 午饭35元 ")
        assertEquals(listOf("午饭35元"), texts)
        assertEquals(SpeechPhase.IDLE, session.state.value.phase)
    }

    @Test
    fun `a pause while still holding does not end capture or discard recognized text`() = runTest {
        val engine = Engine()
        val texts = mutableListOf<String>()
        var finished = 0
        val session = SpeechInputSession(this, { engine }, texts::add, { finished++ })
        session.start()
        engine.listener.ready()
        advanceTimeBy(5_000)
        engine.listener.partial("买菜")
        assertEquals(0, engine.stops)
        assertEquals("买菜", session.state.value.partialText)
        session.release()
        advanceTimeBy(1_000)
        runCurrent()
        engine.listener.result("买菜20元")
        assertEquals(listOf("买菜20元"), texts)
        assertEquals(1, finished)
        assertEquals(1, engine.closes)
    }

    @Test
    fun `end of audio remains busy until the final transcript arrives`() = runTest {
        val engines = mutableListOf<Engine>()
        val texts = mutableListOf<String>()
        val session = SpeechInputSession(this, { Engine().also(engines::add) }, texts::add, {})
        session.start()
        val first = engines.single()
        first.listener.ready()
        first.listener.ended()
        session.start()
        assertEquals(1, engines.size)
        assertTrue(session.state.value.isProcessing)
        first.listener.result("公交2元")
        assertEquals(listOf("公交2元"), texts)
        session.start()
        assertEquals(2, engines.size)
        session.cancel()
    }

    @Test
    fun `holding again during release grace resumes the same recording`() = runTest {
        val engine = Engine()
        val session = SpeechInputSession(this, { engine }, {}, {})
        session.start()
        engine.listener.ready()
        session.release()
        advanceTimeBy(500)
        session.start()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(0, engine.stops)
        session.release()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, engine.stops)
        engine.listener.result("再次按住")
    }

    @Test
    fun `cancellation clears timers and late callbacks cannot affect a retry`() = runTest {
        val engines = mutableListOf<Engine>()
        val texts = mutableListOf<String>()
        val session = SpeechInputSession(this, { Engine().also(engines::add) }, texts::add, {})
        session.start()
        val first = engines.single()
        first.listener.ready()
        session.release()
        session.cancel()
        session.start()
        val retry = engines.last()
        retry.listener.ready()
        advanceTimeBy(2_000)
        runCurrent()
        first.listener.result("过期结果")
        first.listener.error("过期错误")
        assertTrue(texts.isEmpty())
        assertEquals(0, first.stops)
        assertEquals(0, retry.stops)
        assertEquals(1, first.cancels)
        assertEquals(1, first.closes)
        retry.listener.result("重试成功")
        assertEquals(listOf("重试成功"), texts)
    }

    @Test
    fun `failure and empty results allow immediate retry without submitting partial text`() = runTest {
        val engine = Engine()
        val texts = mutableListOf<String>()
        val session = SpeechInputSession(this, { engine }, texts::add, {})
        session.start()
        engine.listener.partial("不完整金额")
        engine.listener.error("识别失败")
        assertEquals("识别失败", session.state.value.error)
        assertTrue(texts.isEmpty())
        session.start()
        assertEquals(null, session.state.value.error)
        engine.listener.result(" ")
        assertFalse(session.state.value.isRecording)
        assertTrue(session.state.value.error!!.contains("未识别到"))
        session.start()
        engine.listener.result("新结果")
        assertEquals(listOf("新结果"), texts)
    }

    @Test
    fun `empty final callback preserves real recognized words with an explicit review warning`() = runTest {
        val engine = Engine()
        val texts = mutableListOf<String>()
        val session = SpeechInputSession(this, { engine }, texts::add, {})
        session.start()
        engine.listener.ready()
        engine.listener.partial("回归测试午饭花了35元")
        engine.listener.partial("")
        engine.listener.result("")
        assertEquals(listOf("回归测试午饭花了35元"), texts)
        assertTrue(session.state.value.error!!.contains("请确认"))
        assertEquals(SpeechPhase.IDLE, session.state.value.phase)
        engine.listener.result("")
        assertEquals(1, texts.size)
    }

    @Test
    fun `readiness and result deadlines release resources and reset busy state`() = runTest {
        val engine = Engine()
        val session = SpeechInputSession(this, { engine }, {}, {})
        session.start()
        advanceTimeBy(SpeechInputSession.READY_TIMEOUT_MILLIS)
        runCurrent()
        assertEquals(SpeechPhase.IDLE, session.state.value.phase)
        assertTrue(session.state.value.error!!.contains("未能就绪"))
        session.start()
        engine.listener.ready()
        engine.listener.ended()
        advanceTimeBy(SpeechInputSession.RESULT_TIMEOUT_MILLIS)
        runCurrent()
        assertEquals(SpeechPhase.IDLE, session.state.value.phase)
        assertTrue(session.state.value.error!!.contains("超时"))
        assertEquals(2, engine.closes)
    }
}
