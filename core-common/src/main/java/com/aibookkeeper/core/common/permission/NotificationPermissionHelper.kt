package com.aibookkeeper.core.common.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Utility class for managing notification permission state and preferences.
 *
 * Handles:
 * - Runtime permission check (POST_NOTIFICATIONS on API 33+)
 * - Onboarding / first-launch tracking
 * - Persistent-notification user preference
 * - Deep-link to system notification settings
 */
object NotificationPermissionHelper {

    private const val PREFS_NAME = "notification_prefs"
    private const val KEY_PERMISSION_REQUESTED = "permission_requested"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"

    // ── Permission status ────────────────────────────────────────────────

    /**
     * Whether the POST_NOTIFICATIONS permission is granted.
     * Always returns `true` on API < 33 (no runtime permission required).
     */
    fun isPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Whether the device requires runtime notification permission (API 33+).
     */
    fun needsRuntimePermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * The permission string to request (only meaningful on API 33+).
     */
    fun permissionString(): String = Manifest.permission.POST_NOTIFICATIONS

    // ── Permission-request tracking ──────────────────────────────────────

    /** Whether the app has previously requested notification permission. */
    fun hasRequestedBefore(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PERMISSION_REQUESTED, false)
    }

    /** Record that the permission dialog has been shown. */
    fun markPermissionRequested(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_PERMISSION_REQUESTED, true)
            .apply()
    }

    // ── Onboarding state ─────────────────────────────────────────────────

    /** Whether the first-launch onboarding has been completed. */
    fun isOnboardingCompleted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    /** Mark onboarding as completed (won't show again). */
    fun markOnboardingCompleted(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, true)
            .apply()
    }

    // ── User preference for persistent notification ──────────────────────

    /**
     * Whether the user has enabled the persistent quick-bookkeeping notification.
     * Defaults to `true` — feature is opt-out.
     */
    fun isNotificationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_NOTIFICATION_ENABLED, true)
    }

    /** Save the user's preference for the persistent notification. */
    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_NOTIFICATION_ENABLED, enabled)
            .apply()
    }

    // ── System settings deep-link ────────────────────────────────────────

    /**
     * Create an intent that opens the app's notification settings in the system UI.
     * Useful when the user has permanently denied the permission.
     */
    fun createNotificationSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
