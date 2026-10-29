package com.pdfreader.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B4965),
    secondary = Color(0xFF62929E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC6D6),
    secondary = Color(0xFF62929E),
)

@Composable
fun PdfReaderTheme(
    useDarkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (useDarkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
