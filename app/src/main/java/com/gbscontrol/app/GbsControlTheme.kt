package com.gbscontrol.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF00677A),
    secondary = Color(0xFF49646B),
    tertiary = Color(0xFF5A5D8A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF51D7F5),
    secondary = Color(0xFFB1CBD3),
    tertiary = Color(0xFFC1C3F4),
)

@Composable
fun GbsControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
