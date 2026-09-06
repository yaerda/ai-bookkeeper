package com.aibookkeeper.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookkeeperThemeRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun themeChangesColorsWithoutReplacingTypographyOrShapes() {
        val expectedTypography = Typography(bodyMedium = TextStyle(fontSize = 18.sp))
        val expectedShapes = Shapes(small = RoundedCornerShape(9.dp))
        var actualTypography: Typography? = null
        var actualShapes: Shapes? = null

        compose.setContent {
            MaterialTheme(typography = expectedTypography, shapes = expectedShapes) {
                BookkeeperTheme {
                    val typography = MaterialTheme.typography
                    val shapes = MaterialTheme.shapes
                    val colors = MaterialTheme.colorScheme
                    SideEffect {
                        actualTypography = typography
                        actualShapes = shapes
                        assertEquals(BookkeeperLightColors.primary, colors.primary)
                        assertEquals(BookkeeperLightColors.surface, colors.surface)
                        assertEquals(BookkeeperLightColors.error, colors.error)
                    }
                    Column {
                        Text("Brand theme", style = typography.bodyMedium)
                        Button(onClick = {}) { Text("Primary action") }
                    }
                }
            }
        }

        compose.runOnIdle {
            assertSame(expectedTypography, actualTypography)
            assertSame(expectedShapes, actualShapes)
        }
    }
}
