package com.passkey.vault.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF2563EB)
private val Bg = Color(0xFFF4F4F5)
private val Surface = Color(0xFFFFFFFF)
private val TextPrimary = Color(0xFF18181B)
private val TextSecondary = Color(0xFF52525B)
private val Border = Color(0xFFE4E4E7)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Bg,
    surface = Surface,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    outline = Border,
)

@Composable
fun PassKeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}

object AppColors {
    val textMuted = Color(0xFFA1A1AA)
    val danger = Color(0xFFDC2626)
    val success = Color(0xFF16A34A)
    val warning = Color(0xFFCA8A04)
}
