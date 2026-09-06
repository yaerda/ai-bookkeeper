package com.aibookkeeper.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class BookkeeperThemeTest {
    @Test
    fun `brand colors follow the existing persimmon icon`() {
        assertEquals(Color(0xFFB65A2C), BookkeeperLightColors.primary)
        assertEquals(Color(0xFF52683E), BookkeeperLightColors.secondary)
        assertEquals(Color(0xFFF6F2E8), BookkeeperLightColors.background)
        assertEquals(Color(0xFFFFFDF7), BookkeeperLightColors.surface)
        assertEquals(Color(0xFF343B2F), BookkeeperLightColors.onSurface)
        assertEquals(Color.White, BookkeeperLightColors.onPrimary)
    }

    @Test
    fun `error and recording colors remain distinct from ordinary primary actions`() {
        assertEquals(Color(0xFFB3261E), BookkeeperLightColors.error)
        assertNotEquals(BookkeeperLightColors.primary, BookkeeperLightColors.error)
        assertNotEquals(BookkeeperLightColors.secondary, BookkeeperLightColors.error)
    }
}
