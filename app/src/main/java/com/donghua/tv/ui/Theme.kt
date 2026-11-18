package com.donghua.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Orange = Color(0xFFF47521)
val Bg = Color(0xFF0B0F14)
val NavBg = Color(0xFF0E131A)
val SurfaceCard = Color(0xFF151B22)
val SurfaceHigh = Color(0xFF1D252E)
val TextPrimary = Color(0xFFF2F5F8)
val TextMuted = Color(0xFF9AA7B4)

private val DarkColors = darkColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    primaryContainer = Orange,
    onPrimaryContainer = Color.White,
    secondary = SurfaceHigh,
    onSecondary = TextPrimary,
    background = Bg,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextMuted,
    outline = TextMuted,
)

@Composable
fun DonghuaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
