package com.aibookkeeper.feature.capture.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.aibookkeeper.core.data.repository.TransactionRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UndoTransactionBroadcastReceiverTest {

    private lateinit var receiver: UndoTransactionBroadcastReceiver
    private val transactionRepository: TransactionRepository = mockk()
    private val context: Context = mockk(relaxed = true)
    private val notificationManager: NotificationManager = mockk(relaxed = true)
    private val pendingResult: BroadcastReceiver.PendingResult = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        receiver = spyk(UndoTransactionBroadcastReceiver())
        receiver.transactionRepository = transactionRepository

        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        every { receiver.goAsync() } returns pendingResult

        mockkStatic(Toast::class)
        every { Toast.makeText(any(), any<String>(), any()) } returns mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Early-return scenarios ──

    @Test
    fun should_doNothing_when_actionDoesNotMatch() {
        val intent = mockk<Intent> {
            every { action } returns "com.other.action"
        }

        receiver.onReceive(context, intent)

        verify(exactly = 0) { context.getSystemService(any<String>()) }
        verify(exactly = 0) { receiver.goAsync() }
    }

    @Test
    fun should_doNothing_when_transactionIdIsMissing() {
        val intent = mockk<Intent> {
            every { action } returns NotificationConstants.ACTION_UNDO_TRANSACTION
            every { getLongExtra(NotificationConstants.EXTRA_TRANSACTION_ID, -1L) } returns -1L
        }

        receiver.onReceive(context, intent)

        verify(exactly = 0) { notificationManager.cancel(any()) }
        verify(exactly = 0) { receiver.goAsync() }
    }

    // ── Notification dismissal ──

    @Test
    fun should_cancelFeedbackNotification_when_validUndoBroadcast() {
        val intent = buildValidUndoIntent(42L)
        coEvery { transactionRepository.delete(42L) } returns Result.success(Unit)

        receiver.onReceive(context, intent)

        verify { notificationManager.cancel(NotificationConstants.NOTIFICATION_ID_FEEDBACK) }
    }

    // ── Async handling ──

    @Test
    fun should_callGoAsync_when_validUndoBroadcast() {
        val intent = buildValidUndoIntent(42L)
        coEvery { transactionRepository.delete(42L) } returns Result.success(Unit)

        receiver.onReceive(context, intent)

        verify { receiver.goAsync() }
    }

    // ── Repository interaction ──

    @Test
    fun should_deleteTransaction_when_validUndoBroadcast() {
        val intent = buildValidUndoIntent(99L)
        coEvery { transactionRepository.delete(99L) } returns Result.success(Unit)

        receiver.onReceive(context, intent)
        Thread.sleep(500)

        coVerify { transactionRepository.delete(99L) }
    }

    // ── PendingResult.finish() guarantee ──

    @Test
    fun should_finishPendingResult_when_deleteSucceeds() {
        val intent = buildValidUndoIntent(42L)
        coEvery { transactionRepository.delete(42L) } returns Result.success(Unit)

        receiver.onReceive(context, intent)
        Thread.sleep(500)

        verify { pendingResult.finish() }
    }

    @Test
    fun should_finishPendingResult_when_deleteFails() {
        val intent = buildValidUndoIntent(42L)
        coEvery { transactionRepository.delete(42L) } returns Result.failure(RuntimeException("DB error"))

        receiver.onReceive(context, intent)
        Thread.sleep(500)

        verify { pendingResult.finish() }
    }

    @Test
    fun should_finishPendingResult_when_unexpectedExceptionThrown() {
        val intent = buildValidUndoIntent(42L)
        coEvery { transactionRepository.delete(42L) } throws RuntimeException("Unexpected")

        receiver.onReceive(context, intent)
        Thread.sleep(500)

        verify { pendingResult.finish() }
    }

    // ── Toast feedback ──

    @Test
    fun should_showSuccessToast_when_deleteSucceeds() {
        val intent = buildValidUndoIntent(42L)
        coEvery { transactionRepository.delete(42L) } returns Result.success(Unit)

        receiver.onReceive(context, intent)
        Thread.sleep(500)

        verify { Toast.makeText(context, "已撤销记账", Toast.LENGTH_SHORT) }
    }

    @Test
    fun should_showFailureToast_when_deleteFails() {
        val intent = buildValidUndoIntent(42L)
        coEvery { transactionRepository.delete(42L) } returns Result.failure(RuntimeException("DB error"))

        receiver.onReceive(context, intent)
        Thread.sleep(500)

        verify { Toast.makeText(context, "撤销失败，请稍后重试", Toast.LENGTH_SHORT) }
    }

    // ── Helper ──

    private fun buildValidUndoIntent(transactionId: Long): Intent = mockk {
        every { action } returns NotificationConstants.ACTION_UNDO_TRANSACTION
        every { getLongExtra(NotificationConstants.EXTRA_TRANSACTION_ID, -1L) } returns transactionId
    }
}
