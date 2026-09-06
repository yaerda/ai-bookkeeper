package com.aibookkeeper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val BookkeeperLightColors = lightColorScheme(
    primary = Color(0xFFB65A2C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFAE5CB),
    onPrimaryContainer = Color(0xFF653514),
    inversePrimary = Color(0xFFF6B78F),
    secondary = Color(0xFF52683E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF0DC),
    onSecondaryContainer = Color(0xFF324522),
    tertiary = Color(0xFF785C30),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF1E2C5),
    onTertiaryContainer = Color(0xFF4C391B),
    background = Color(0xFFF6F2E8),
    onBackground = Color(0xFF343B2F),
    surface = Color(0xFFFFFDF7),
    onSurface = Color(0xFF343B2F),
    surfaceVariant = Color(0xFFEEE9DD),
    onSurfaceVariant = Color(0xFF666C5A),
    surfaceTint = Color(0xFFB65A2C),
    inverseSurface = Color(0xFF2F342C),
    inverseOnSurface = Color(0xFFF6F2E8),
    outline = Color(0xFF8C9079),
    outlineVariant = Color(0xFFDED8CA),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFBE8E3),
    onErrorContainer = Color(0xFF7D3022),
    surfaceBright = Color(0xFFFFFDF7),
    surfaceDim = Color(0xFFE4DECF),
    surfaceContainerLowest = Color(0xFFFFFDF7),
    surfaceContainerLow = Color(0xFFF7F2E8),
    surfaceContainer = Color(0xFFF1EBDD),
    surfaceContainerHigh = Color(0xFFEBE5D6),
    surfaceContainerHighest = Color(0xFFE5DFCF)
)

@Composable
fun BookkeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BookkeeperLightColors, content = content)
}
