package com.simpleefb.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark theme: easier on night vision and readable in a cockpit. */
private val EfbColors = darkColorScheme(
    primary = Color(0xFF7CB8FF),
    onPrimary = Color(0xFF002F5E),
    secondary = Color(0xFF8BD18F),
    tertiary = Color(0xFFFFC86B),
    background = Color(0xFF101418),
    surface = Color(0xFF161B21),
    surfaceVariant = Color(0xFF242B33),
    error = Color(0xFFFF8A80),
)

object EfbPalette {
    val good = Color(0xFF66D17A)
    val caution = Color(0xFFFFC14D)
    val warning = Color(0xFFFF6B6B)
    val overlay = Color(0xD9101418)
}

@Composable
fun EfbTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EfbColors, content = content)
}
