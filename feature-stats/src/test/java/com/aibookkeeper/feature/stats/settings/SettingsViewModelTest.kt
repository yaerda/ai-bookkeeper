package com.aibookkeeper.feature.stats.settings

import android.content.Context
import android.content.SharedPreferences
import com.aibookkeeper.core.common.permission.NotificationPermissionHelper
import com.aibookkeeper.core.data.security.SecureConfigStore
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SettingsViewModelTest {

    private val context: Context = mockk(relaxed = true)
    private val secureConfigStore: SecureConfigStore = mockk(relaxed = true)
    private val legacyPrefs: SharedPreferences = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkObject(NotificationPermissionHelper)
        every { context.getSharedPreferences("azure_openai", Context.MODE_PRIVATE) } returns legacyPrefs
        every { legacyPrefs.getString(any(), any()) } returns null
        every { secureConfigStore.getEndpoint() } returns ""
        every { secureConfigStore.getApiKey() } returns ""
        every { secureConfigStore.getDeployment() } returns ""
        every { secureConfigStore.getSpeechDeployment() } returns ""
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun createViewModel(
        permissionGranted: Boolean = false,
        notificationEnabled: Boolean = false
    ): SettingsViewModel {
        every { NotificationPermissionHelper.isPermissionGranted(context) } returns permissionGranted
        every { NotificationPermissionHelper.isNotificationEnabled(context) } returns notificationEnabled
        every { NotificationPermissionHelper.setNotificationEnabled(context, any()) } just Runs
        every { NotificationPermissionHelper.markPermissionRequested(context) } just Runs

        return SettingsViewModel(context, secureConfigStore)
    }

    // ── Initial state ────────────────────────────────────────────────────

    @Nested
    inner class InitialState {

        @Test
        fun should_readPermissionAndPreference_when_initialized() {
            val vm = createViewModel(
                permissionGranted = true,
                notificationEnabled = true
            )

            val state = vm.uiState.value
            assertTrue(state.isPermissionGranted)
            assertTrue(state.isNotificationEnabled)
        }

        @Test
        fun should_reflectDeniedPermission_when_initialized() {
            val vm = createViewModel(
                permissionGranted = false,
                notificationEnabled = false
            )

            val state = vm.uiState.value
            assertFalse(state.isPermissionGranted)
            assertFalse(state.isNotificationEnabled)
        }

        @Test
        fun should_reflectMixedState_when_permissionGrantedButDisabled() {
            val vm = createViewModel(
                permissionGranted = true,
                notificationEnabled = false
            )

            val state = vm.uiState.value
            assertTrue(state.isPermissionGranted)
            assertFalse(state.isNotificationEnabled)
        }
    }

    // ── refreshState ─────────────────────────────────────────────────────

    @Nested
    inner class RefreshState {

        @Test
        fun should_updateState_when_permissionChangedExternally() {
            val vm = createViewModel(
                permissionGranted = false,
                notificationEnabled = false
            )
            assertFalse(vm.uiState.value.isPermissionGranted)

            // Simulate user granting permission in system settings
            every { NotificationPermissionHelper.isPermissionGranted(context) } returns true
            every { NotificationPermissionHelper.isNotificationEnabled(context) } returns false

            vm.refreshState()

            assertTrue(vm.uiState.value.isPermissionGranted)
        }

        @Test
        fun should_updateBothFields_when_refreshStateCalled() {
            val vm = createViewModel(
                permissionGranted = false,
                notificationEnabled = false
            )

            every { NotificationPermissionHelper.isPermissionGranted(context) } returns true
            every { NotificationPermissionHelper.isNotificationEnabled(context) } returns true

            vm.refreshState()

            assertTrue(vm.uiState.value.isPermissionGranted)
            assertTrue(vm.uiState.value.isNotificationEnabled)
        }
    }

    // ── setNotificationEnabled ───────────────────────────────────────────

    @Nested
    inner class SetNotificationEnabled {

        @Test
        fun should_updateStateToEnabled_when_setTrue() {
            val vm = createViewModel(
                permissionGranted = true,
                notificationEnabled = false
            )

            vm.setNotificationEnabled(true)

            assertTrue(vm.uiState.value.isNotificationEnabled)
        }

        @Test
        fun should_updateStateToDisabled_when_setFalse() {
            val vm = createViewModel(
                permissionGranted = true,
                notificationEnabled = true
            )

            vm.setNotificationEnabled(false)

            assertFalse(vm.uiState.value.isNotificationEnabled)
        }

        @Test
        fun should_persistPreference_when_setNotificationEnabled() {
            val vm = createViewModel(
                permissionGranted = true,
                notificationEnabled = false
            )

            vm.setNotificationEnabled(true)

            verify { NotificationPermissionHelper.setNotificationEnabled(context, true) }
        }

        @Test
        fun should_persistDisabledPreference_when_setNotificationEnabledFalse() {
            val vm = createViewModel(
                permissionGranted = true,
                notificationEnabled = true
            )

            vm.setNotificationEnabled(false)

            verify { NotificationPermissionHelper.setNotificationEnabled(context, false) }
        }
    }

    // ── onPermissionResult ───────────────────────────────────────────────

    @Nested
    inner class OnPermissionResult {

        @Test
        fun should_markPermissionRequested_when_grantedTrue() {
            val vm = createViewModel(
                permissionGranted = false,
                notificationEnabled = false
            )

            vm.onPermissionResult(granted = true)

            verify { NotificationPermissionHelper.markPermissionRequested(context) }
        }

        @Test
        fun should_markPermissionRequested_when_grantedFalse() {
            val vm = createViewModel(
                permissionGranted = false,
                notificationEnabled = false
            )

            vm.onPermissionResult(granted = false)

            verify { NotificationPermissionHelper.markPermissionRequested(context) }
        }

        @Test
        fun should_updatePermissionGranted_when_grantedTrue() {
            val vm = createViewModel(
                permissionGranted = false,
                notificationEnabled = false
            )

            vm.onPermissionResult(granted = true)

            assertTrue(vm.uiState.value.isPermissionGranted)
        }

        @Test
        fun should_keepPermissionDenied_when_grantedFalse() {
            val vm = createViewModel(
                permissionGranted = false,
                notificationEnabled = false
            )

            vm.onPermissionResult(granted = false)

            assertFalse(vm.uiState.value.isPermissionGranted)
        }

        @Test
        fun should_enableNotification_when_permissionGranted() {
            val vm = createViewModel(
                permissionGranted = false,
                notificationEnabled = false
            )

            vm.onPermissionResult(granted = true)

            assertTrue(vm.uiState.value.isNotificationEnabled)
            verify { NotificationPermissionHelper.setNotificationEnabled(context, true) }
        }

        @Test
        fun should_notEnableNotification_when_permissionDenied() {
            val vm = createViewModel(
                permissionGranted = false,
                notificationEnabled = false
            )

            vm.onPermissionResult(granted = false)

            assertFalse(vm.uiState.value.isNotificationEnabled)
            verify(exactly = 0) {
                NotificationPermissionHelper.setNotificationEnabled(context, true)
            }
        }
    }

    // ── SettingsUiState defaults ──────────────────────────────────────────

    @Test
    fun should_defaultToAllFalse_when_settingsUiStateCreated() {
        val state = SettingsUiState()
        assertFalse(state.isPermissionGranted)
        assertFalse(state.isNotificationEnabled)
    }
}
