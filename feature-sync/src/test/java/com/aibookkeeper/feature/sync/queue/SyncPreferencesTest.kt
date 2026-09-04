package com.aibookkeeper.feature.sync.queue

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncPreferencesTest {

    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()

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
}
