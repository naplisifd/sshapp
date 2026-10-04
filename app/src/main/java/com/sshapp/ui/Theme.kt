package com.sshapp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF7EE787),
    onPrimary = Color(0xFF0B2911),
    primaryContainer = Color(0xFF1F3D27),
    onPrimaryContainer = Color(0xFFB8F5C0),
    secondary = Color(0xFF79C0FF),
    secondaryContainer = Color(0xFF1C3150),
    onSecondaryContainer = Color(0xFFCFE6FF),
    tertiary = Color(0xFFD2A8FF),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceContainer = Color(0xFF181D23),
    surfaceContainerHigh = Color(0xFF1F252C),
    surfaceContainerHighest = Color(0xFF262D35),
    surfaceContainerLow = Color(0xFF14191E),
    error = Color(0xFFFF7B72),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1A7F37),
    primaryContainer = Color(0xFFCDF2D4),
    onPrimaryContainer = Color(0xFF0B2911),
    secondary = Color(0xFF0969DA),
    secondaryContainer = Color(0xFFD8E9FF),
    tertiary = Color(0xFF8250DF),
    error = Color(0xFFCF222E),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
