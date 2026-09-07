package com.aibookkeeper.feature.sync.queue

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncPreferencesTest {

    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()

    @Test
    fun `new accounts have no remembered ledger`() {
        every { context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString(any(), null) } returns null

        assertNull(SyncPreferences(context).selectedLedgerId("account-a"))
        verify(exactly = 0) { preferences.edit() }
    }

    @Test
    fun `last ledger persists per account without changing the local ledger binding`() {
        val values = mutableMapOf<String, String?>("bound_account_id" to "account-a")
        every { context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString(any(), any()) } answers {
            values[firstArg<String>()] ?: secondArg<String?>()
        }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            values[firstArg<String>()] = secondArg<String?>()
            editor
        }
        every { editor.apply() } returns Unit

        val initial = SyncPreferences(context)
        initial.updateSelectedLedgerId("account-a", "shared-a")
        initial.updateSelectedLedgerId("account-b", "shared-b")
        val restored = SyncPreferences(context)

        assertEquals("shared-a", restored.selectedLedgerId("account-a"))
        assertEquals("shared-b", restored.selectedLedgerId("account-b"))
        restored.updateSelectedLedgerId("account-a", "default")
        assertEquals("default", SyncPreferences(context).selectedLedgerId("account-a"))
        assertEquals("shared-b", restored.selectedLedgerId("account-b"))
        assertEquals("account-a", restored.boundAccountId.value)
        verify(exactly = 3) { editor.apply() }
        verify(exactly = 0) { editor.putString("bound_account_id", any()) }
    }

    @Test
    fun `first account permanently binds the local ledger`() {
        every { context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString("bound_account_id", null) } returns null
        every { preferences.edit() } returns editor
        every { editor.putString("bound_account_id", "account-a") } returns editor
        every { editor.apply() } returns Unit

        val result = SyncPreferences(context).bindAccount("account-a")

        assertTrue(result)
        verify { editor.putString("bound_account_id", "account-a") }
    }

    @Test
    fun `different account cannot claim an existing local ledger`() {
        every { context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString("bound_account_id", null) } returns "account-a"

        val result = SyncPreferences(context).bindAccount("account-b")

        assertFalse(result)
        verify(exactly = 0) { preferences.edit() }
    }

    @Test
    fun `recorded-by refresh completion persists per account`() {
        val strings = mutableMapOf<String, String?>("bound_account_id" to "account-a")
        val booleans = mutableMapOf<String, Boolean>()
        every { context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString(any(), any()) } answers {
            strings[firstArg<String>()] ?: secondArg<String?>()
        }
        every { preferences.getBoolean(any(), any()) } answers {
            booleans[firstArg<String>()] ?: secondArg<Boolean>()
        }
        every { preferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers {
            booleans[firstArg<String>()] = secondArg<Boolean>()
            editor
        }
        every { editor.apply() } returns Unit

        val initial = SyncPreferences(context)
        assertFalse(initial.isRecordedByMetadataRefreshComplete("account-a"))
        assertFalse(initial.isRecordedByMetadataRefreshComplete("account-b"))

        initial.markRecordedByMetadataRefreshComplete("account-a")
        val restored = SyncPreferences(context)

        assertTrue(restored.isRecordedByMetadataRefreshComplete("account-a"))
        assertFalse(restored.isRecordedByMetadataRefreshComplete("account-b"))
        verify { editor.putBoolean("recorded_by_refresh_complete:account-a", true) }
    }

    @Test
    fun `project metadata refresh and cache stay isolated per account and ledger`() {
        val strings = mutableMapOf<String, String?>("bound_account_id" to "account-a")
        val booleans = mutableMapOf<String, Boolean>()
        val longs = mutableMapOf<String, Long>()
        every { context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString(any(), any()) } answers {
            strings[firstArg<String>()] ?: secondArg<String?>()
        }
        every { preferences.getBoolean(any(), any()) } answers {
            booleans[firstArg<String>()] ?: secondArg<Boolean>()
        }
        every { preferences.contains(any()) } answers {
            val key = firstArg<String>()
            longs.containsKey(key)
        }
        every { preferences.getLong(any(), any()) } answers {
            longs[firstArg<String>()] ?: secondArg<Long>()
        }
        every { preferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers {
            booleans[firstArg<String>()] = secondArg<Boolean>()
            editor
        }
        every { editor.putString(any(), any()) } answers {
            strings[firstArg<String>()] = secondArg<String?>()
            editor
        }
        every { editor.putLong(any(), any()) } answers {
            longs[firstArg<String>()] = secondArg<Long>()
            editor
        }
        every { editor.apply() } returns Unit

        val prefs = SyncPreferences(context)
        prefs.markProjectMetadataRefreshComplete("account-a")
        prefs.updateProjectCache("account-a", "ledger-1", "{\"projects\":[]}", 123L)

        val restored = SyncPreferences(context)

        assertTrue(restored.isProjectMetadataRefreshComplete("account-a"))
        assertFalse(restored.isProjectMetadataRefreshComplete("account-b"))
        assertEquals("{\"projects\":[]}", restored.projectCacheJson("account-a", "ledger-1"))
        assertEquals(123L, restored.projectCacheRefreshedAt("account-a", "ledger-1"))
        assertNull(restored.projectCacheJson("account-a", "ledger-2"))
        verify { editor.putBoolean("project_metadata_refresh_complete:account-a", true) }
        verify { editor.putString("project_cache:account-a:ledger-1", "{\"projects\":[]}") }
    }
}
