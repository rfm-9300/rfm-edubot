package com.rfm.edubot.mobile.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

object BotColor {
    val Background = Color(0xFF08090B)
    val Surface = Color(0xFF0F1216)
    val Panel = Color(0xFF14181D)
    val Text = Color(0xFFE8EAED)
    val Subtle = Color(0xFFB8BCC2)
    val Muted = Color(0xFF7A8089)
    val Border = Color(0xFF2A2F36)
    val Accent = Color(0xFFFFD60A)
    val Success = Color(0xFF54D68B)
    val Warning = Color(0xFFF2B94B)
    val Danger = Color(0xFFF06B70)
    val Info = Color(0xFF6EA8FF)
}

private val botScheme: ColorScheme = darkColorScheme(
    primary = BotColor.Accent,
    onPrimary = BotColor.Background,
    secondary = BotColor.Subtle,
    onSecondary = BotColor.Background,
    background = BotColor.Background,
    onBackground = BotColor.Text,
    surface = BotColor.Surface,
    onSurface = BotColor.Text,
    surfaceVariant = BotColor.Panel,
    onSurfaceVariant = BotColor.Subtle,
    outline = BotColor.Border,
    error = BotColor.Danger,
)

private val botTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 68.sp, lineHeight = 70.sp, letterSpacing = (-3.4).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.7.sp),
)

@Composable
fun BotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = botScheme, typography = botTypography, content = content)
}
