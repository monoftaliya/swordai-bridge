package com.swordai.bridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Палитра SwordAI: глубокий чёрный и графит, электрический синий и фиолетовый. */
object Sword {
    val Background = Color(0xFF07080C)
    val Graphite = Color(0xFF0E1018)
    val GraphiteHigh = Color(0xFF151826)
    val Line = Color(0x2E8C96FF)
    val Blue = Color(0xFF3D7BFF)
    val Violet = Color(0xFF8A5CFF)
    val Cyan = Color(0xFF22D3EE)
    val Success = Color(0xFF2FD39A)
    val Warning = Color(0xFFFFB547)
    val Danger = Color(0xFFFF4D6A)
    val Text = Color(0xFFE8EBFF)
    val Muted = Color(0xFF8A90B2)
    val Faint = Color(0xFF5B6185)
}

private val colors = darkColorScheme(
    primary = Sword.Blue,
    onPrimary = Color.White,
    secondary = Sword.Violet,
    onSecondary = Color.White,
    tertiary = Sword.Cyan,
    background = Sword.Background,
    onBackground = Sword.Text,
    surface = Sword.Graphite,
    onSurface = Sword.Text,
    surfaceVariant = Sword.GraphiteHigh,
    onSurfaceVariant = Sword.Muted,
    outline = Sword.Line,
    error = Sword.Danger,
    onError = Color.White,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp, color = Sword.Text),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Sword.Text),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Sword.Text),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, color = Sword.Text),
    bodyLarge = TextStyle(fontSize = 16.sp, color = Sword.Text),
    bodyMedium = TextStyle(fontSize = 14.sp, color = Sword.Text),
    bodySmall = TextStyle(fontSize = 12.sp, color = Sword.Muted),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

@Composable
fun SwordTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
