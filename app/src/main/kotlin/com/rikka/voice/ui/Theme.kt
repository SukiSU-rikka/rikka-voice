package com.rikka.voice.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF7960AC), onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE4FB), onPrimaryContainer = Color(0xFF2E1F4E),
    secondary = Color(0xFFB49BE0), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3EEFB), onSecondaryContainer = Color(0xFF3A2D57),
    tertiary = Color(0xFFD98BAA), tertiaryContainer = Color(0xFFFBE7EF),
    background = Color(0xFFFBF9FE), onBackground = Color(0xFF221D2C),
    surface = Color.White, onSurface = Color(0xFF221D2C),
    surfaceVariant = Color(0xFFF3EEFB), onSurfaceVariant = Color(0xFF5E566C),
    outline = Color(0xFFD9D0E8), error = Color(0xFFC2415B),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFCDB8F5), onPrimary = Color(0xFF2E1F4E),
    primaryContainer = Color(0xFF4A3A70), onPrimaryContainer = Color(0xFFEDE4FB),
    secondary = Color(0xFFB49BE0), secondaryContainer = Color(0xFF2C2540),
    tertiary = Color(0xFFF0A9C4), tertiaryContainer = Color(0xFF4A2A38),
    background = Color(0xFF15121C), onBackground = Color(0xFFEAE4F3),
    surface = Color(0xFF1E1A27), onSurface = Color(0xFFEAE4F3),
    surfaceVariant = Color(0xFF2A2436), onSurfaceVariant = Color(0xFFC4BBD3),
    outline = Color(0xFF4A4258), error = Color(0xFFFF8FA3),
)

@Composable
fun RikkaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
