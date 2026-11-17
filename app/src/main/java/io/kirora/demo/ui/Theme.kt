package io.kirora.demo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Indigo = Color(0xFF4F46E5)

private val Light = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    background = Color(0xFFF7F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEEEF5),
    onSurfaceVariant = Color(0xFF55576A),
    outlineVariant = Color(0xFFE2E3EC),
    tertiary = Color(0xFF059669)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CA3FF),
    onPrimary = Color(0xFF1B1A55),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    background = Color(0xFF0F1017),
    surface = Color(0xFF181A24),
    surfaceVariant = Color(0xFF22242F),
    onSurfaceVariant = Color(0xFFA6A8BC),
    outlineVariant = Color(0xFF2D3040),
    tertiary = Color(0xFF34D399)
)

@Composable
fun DemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp)
        ),
        content = content
    )
}
