package com.aibookkeeper.core.common.permission

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [NotificationPermissionHelper].
 *
 * API-level-dependent behaviour (isPermissionGranted, needsRuntimePermission,
 * createNotificationSettingsIntent) relies on Build.VERSION.SDK_INT which is a
 * static final int — hard to mock reliably in pure JUnit without Robolectric.
 * Those branches are best covered by instrumentation / Robolectric tests.
 *
 * This file focuses on the SharedPreferences-backed logic which is fully testable
 * with MockK.
 */
class NotificationPermissionHelperTest {

    private val context: Context = mockk(relaxed = true)
    private val sharedPrefs: SharedPreferences = mockk(relaxed = true)
    private val editor: SharedPreferences.Editor = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        every {
            context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        } returns sharedPrefs
        every { sharedPrefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.apply() } just Runs
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ── permissionString ─────────────────────────────────────────────────

    @Test
    fun should_returnPostNotificationsConstant_when_permissionStringCalled() {
        val result = NotificationPermissionHelper.permissionString()
        assertEquals("android.permission.POST_NOTIFICATIONS", result)
    }

    // ── hasRequestedBefore / markPermissionRequested ──────────────────────

    @Nested
    inner class PermissionRequestTracking {

        @Test
        fun should_returnFalse_when_neverRequested() {
            every {
                sharedPrefs.getBoolean("permission_requested", false)
            } returns false

            assertFalse(NotificationPermissionHelper.hasRequestedBefore(context))
        }

        @Test
        fun should_returnTrue_when_previouslyRequested() {
            every {
                sharedPrefs.getBoolean("permission_requested", false)
            } returns true

            assertTrue(NotificationPermissionHelper.hasRequestedBefore(context))
        }

        @Test
        fun should_persistFlag_when_markPermissionRequestedCalled() {
            NotificationPermissionHelper.markPermissionRequested(context)

            verifyOrder {
                editor.putBoolean("permission_requested", true)
                editor.apply()
            }
        }

        @Test
        fun should_readFromCorrectPrefsFile_when_hasRequestedBeforeCalled() {
            every { sharedPrefs.getBoolean(any(), any()) } returns false

            NotificationPermissionHelper.hasRequestedBefore(context)

            verify {
                context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
            }
        }
    }

    // ── isOnboardingCompleted / markOnboardingCompleted ───────────────────

    @Nested
    inner class OnboardingState {

        @Test
        fun should_returnFalse_when_onboardingNotCompleted() {
            every {
                sharedPrefs.getBoolean("onboarding_completed", false)
            } returns false

            assertFalse(NotificationPermissionHelper.isOnboardingCompleted(context))
        }

        @Test
        fun should_returnTrue_when_onboardingCompleted() {
            every {
                sharedPrefs.getBoolean("onboarding_completed", false)
            } returns true

            assertTrue(NotificationPermissionHelper.isOnboardingCompleted(context))
        }

        @Test
        fun should_persistFlag_when_markOnboardingCompletedCalled() {
            NotificationPermissionHelper.markOnboardingCompleted(context)

            verifyOrder {
                editor.putBoolean("onboarding_completed", true)
                editor.apply()
            }
        }

        @Test
        fun should_useCorrectDefaultValue_when_onboardingNeverSet() {
            every {
                sharedPrefs.getBoolean("onboarding_completed", false)
            } returns false

            val result = NotificationPermissionHelper.isOnboardingCompleted(context)

            assertFalse(result)
            verify {
                sharedPrefs.getBoolean("onboarding_completed", false)
            }
        }
    }

    // ── isNotificationEnabled / setNotificationEnabled ───────────────────

    @Nested
    inner class NotificationPreference {

        @Test
        fun should_returnTrue_when_defaultPreference() {
            every {
                sharedPrefs.getBoolean("notification_enabled", true)
            } returns true

            assertTrue(NotificationPermissionHelper.isNotificationEnabled(context))
        }

        @Test
        fun should_returnFalse_when_userDisabledNotification() {
            every {
                sharedPrefs.getBoolean("notification_enabled", true)
            } returns false

            assertFalse(NotificationPermissionHelper.isNotificationEnabled(context))
        }

        @Test
        fun should_persistTrue_when_setNotificationEnabledTrue() {
            NotificationPermissionHelper.setNotificationEnabled(context, true)

            verifyOrder {
                editor.putBoolean("notification_enabled", true)
                editor.apply()
            }
        }

        @Test
        fun should_persistFalse_when_setNotificationEnabledFalse() {
            NotificationPermissionHelper.setNotificationEnabled(context, false)

            verifyOrder {
                editor.putBoolean("notification_enabled", false)
                editor.apply()
            }
        }

        @Test
        fun should_defaultToOptIn_when_preferenceNotSet() {
            // The default value parameter is `true` — feature is opt-out
            every {
                sharedPrefs.getBoolean("notification_enabled", true)
            } returns true

            assertTrue(NotificationPermissionHelper.isNotificationEnabled(context))
            verify {
                sharedPrefs.getBoolean("notification_enabled", true)
            }
        }
    }

    // ── SharedPreferences isolation ──────────────────────────────────────

    @Test
    fun should_useCorrectPrefsName_when_anyMethodCalled() {
        every { sharedPrefs.getBoolean(any(), any()) } returns false

        NotificationPermissionHelper.isOnboardingCompleted(context)
        NotificationPermissionHelper.hasRequestedBefore(context)
        NotificationPermissionHelper.isNotificationEnabled(context)

        verify(exactly = 3) {
            context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        }
    }

    @Test
    fun should_callApply_when_markOnboardingAndPermissionRequested() {
        NotificationPermissionHelper.markOnboardingCompleted(context)
        NotificationPermissionHelper.markPermissionRequested(context)
        NotificationPermissionHelper.setNotificationEnabled(context, true)

        verify(exactly = 3) { editor.apply() }
    }
}
