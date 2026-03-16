package com.aibookkeeper.feature.capture.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.aibookkeeper.core.data.repository.TransactionRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the "撤销" (Undo) action from the success-feedback notification.
 * Deletes the transaction identified by [NotificationConstants.EXTRA_TRANSACTION_ID]
 * and dismisses the feedback notification.
 */
class UndoTransactionBroadcastReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UndoReceiverEntryPoint {
        fun transactionRepository(): TransactionRepository
    }

    companion object {
        private const val TAG = "UndoTxReceiver"
    }

    // Visible for testing — in production, resolved via EntryPointAccessors
    internal var transactionRepository: TransactionRepository? = null

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationConstants.ACTION_UNDO_TRANSACTION) return

        val transactionId = intent.getLongExtra(NotificationConstants.EXTRA_TRANSACTION_ID, -1L)
        if (transactionId == -1L) {
            Log.w(TAG, "Received undo broadcast without valid transaction ID")
            return
        }

        // Resolve repository via Hilt EntryPoint if not injected (testing)
        val repo = transactionRepository ?: EntryPointAccessors.fromApplication(
            context.applicationContext, UndoReceiverEntryPoint::class.java
        ).transactionRepository()
        transactionRepository = repo

        // Dismiss the feedback notification immediately
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NotificationConstants.NOTIFICATION_ID_FEEDBACK)

        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                repo.delete(transactionId)
                    .onSuccess {
                        Log.i(TAG, "Transaction $transactionId undone successfully")
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, "已撤销记账", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .onFailure { e ->
                        Log.e(TAG, "Failed to undo transaction $transactionId", e)
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, "撤销失败，请稍后重试", Toast.LENGTH_SHORT).show()
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error undoing transaction $transactionId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
