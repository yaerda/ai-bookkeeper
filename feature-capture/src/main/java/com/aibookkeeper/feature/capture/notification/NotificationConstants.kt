package com.aibookkeeper.feature.capture.notification

/**
 * Constants shared between PaymentNotificationService and QuickInputActivity.
 */
object NotificationConstants {
    const val CHANNEL_ID_QUICK_INPUT = "quick_input_channel"
    const val CHANNEL_NAME_QUICK_INPUT = "快捷记账"
    const val NOTIFICATION_ID_PERSISTENT = 1001
    const val NOTIFICATION_ID_FEEDBACK = 1002

    // Intent actions
    const val ACTION_QUICK_TEXT = "com.aibookkeeper.action.QUICK_TEXT"
    const val ACTION_QUICK_VOICE = "com.aibookkeeper.action.QUICK_VOICE"
    const val ACTION_QUICK_CAMERA = "com.aibookkeeper.action.QUICK_CAMERA"
    const val ACTION_QUICK_CATEGORY = "com.aibookkeeper.action.QUICK_CATEGORY"
    const val ACTION_UNDO_TRANSACTION = "com.aibookkeeper.action.UNDO_TRANSACTION"

    // Intent extras
    const val EXTRA_CATEGORY_NAME = "extra_category_name"
    const val EXTRA_CATEGORY_ICON = "extra_category_icon"
    const val EXTRA_TRANSACTION_ID = "extra_transaction_id"
    const val EXTRA_INPUT_MODE = "extra_input_mode"

    // Input modes
    const val MODE_TEXT = "text"
    const val MODE_VOICE = "voice"
    const val MODE_CAMERA = "camera"
    const val MODE_CATEGORY = "category"
}
