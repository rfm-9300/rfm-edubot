package com.rfm.edubot.mobile.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The colors of the `/app` skin, token for token with `html[data-layout="minimal"]` in
 * `admin/style.css`. The names are the CSS custom property names so the two can be diffed: `ink`
 * is `--ink`, `line` is `--line`, and so on.
 *
 * The app shipped dark-only while the web dashboard had both themes; [BotPalette.light] closes
 * that, and the values are copied rather than re-picked so the two surfaces stay the same product.
 */
@Immutable
data class BotPalette(
    val background: Color,
    val backgroundDeep: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val line: Color,
    val lineSoft: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val accent: Color,
    /** Readable on a tinted fill, where [accent] itself is not. */
    val accentDeep: Color,
    val accentSoft: Color,
    val accentInk: Color,
    val ok: Color,
    val okSoft: Color,
    val okInk: Color,
    val warn: Color,
    val warnSoft: Color,
    val warnInk: Color,
    val bad: Color,
    val badSoft: Color,
    val badInk: Color,
    val info: Color,
    val infoSoft: Color,
    val infoInk: Color,
    val dark: Boolean,
) {
    companion object {
        val light = BotPalette(
            background = Color(0xFFF7F8FA),
            backgroundDeep = Color(0xFFEEF0F3),
            surface = Color(0xFFFFFFFF),
            surfaceAlt = Color(0xFFF9FAFB),
            line = Color(0xFFE4E7EC),
            lineSoft = Color(0xFFEEF0F3),
            ink = Color(0xFF111318),
            inkSecondary = Color(0xFF344054),
            inkMuted = Color(0xFF667085),
            inkFaint = Color(0xFF98A2B3),
            accent = Color(0xFFF5D90A),
            accentDeep = Color(0xFF735F00),
            accentSoft = Color(0xFFFFF9D6),
            accentInk = Color(0xFF111318),
            ok = Color(0xFF12B76A),
            okSoft = Color(0x1F12B76A),
            okInk = Color(0xFF067647),
            warn = Color(0xFFF79009),
            warnSoft = Color(0x1FF79009),
            warnInk = Color(0xFFB54708),
            bad = Color(0xFFF04438),
            badSoft = Color(0x1AF04438),
            badInk = Color(0xFFB42318),
            info = Color(0xFF2E90FA),
            infoSoft = Color(0x1F2E90FA),
            infoInk = Color(0xFF175CD3),
            dark = false,
        )

        val dark = BotPalette(
            background = Color(0xFF0C0E12),
            backgroundDeep = Color(0xFF08090C),
            surface = Color(0xFF111318),
            surfaceAlt = Color(0xFF161A20),
            line = Color(0x14FFFFFF),
            lineSoft = Color(0x0DFFFFFF),
            ink = Color(0xFFF0F1F3),
            inkSecondary = Color(0xFFC1C5CD),
            inkMuted = Color(0xFF858B96),
            inkFaint = Color(0xFF5D636E),
            accent = Color(0xFFF5D90A),
            accentDeep = Color(0xFFE6CB00),
            accentSoft = Color(0x1FF5D90A),
            accentInk = Color(0xFF0B0D0F),
            ok = Color(0xFF32D583),
            okSoft = Color(0x1F32D583),
            okInk = Color(0xFF6CE9A6),
            warn = Color(0xFFFDB022),
            warnSoft = Color(0x1FFDB022),
            warnInk = Color(0xFFFEC84B),
            bad = Color(0xFFF97066),
            badSoft = Color(0x1FF97066),
            badInk = Color(0xFFFDA29B),
            info = Color(0xFF53B1FD),
            infoSoft = Color(0x1F53B1FD),
            infoInk = Color(0xFF84CAFF),
            dark = true,
        )
    }
}

/** The web minimal skin's `--r-*` radii. */
object BotRadius {
    val xs = 6.dp
    val sm = 8.dp
    val md = 10.dp
    val lg = 14.dp
    val pill = 999.dp
}

object BotSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
}

enum class ThemeChoice { System, Light, Dark }

private val LocalBotPalette = staticCompositionLocalOf { BotPalette.light }

/** The palette in force. Prefer this to `MaterialTheme.colorScheme` for anything token-named. */
val BotColors: BotPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalBotPalette.current

private val botTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 23.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.7.sp),
)

@Composable
fun BotTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) BotPalette.dark else BotPalette.light
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.accentInk,
            primaryContainer = palette.accentSoft,
            onPrimaryContainer = palette.accentDeep,
            secondary = palette.inkSecondary,
            onSecondary = palette.background,
            background = palette.background,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.inkSecondary,
            outline = palette.line,
            outlineVariant = palette.lineSoft,
            error = palette.bad,
            onError = palette.background,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.accentInk,
            primaryContainer = palette.accentSoft,
            onPrimaryContainer = palette.accentDeep,
            secondary = palette.inkSecondary,
            onSecondary = palette.surface,
            background = palette.background,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.inkSecondary,
            outline = palette.line,
            outlineVariant = palette.lineSoft,
            error = palette.bad,
            onError = palette.surface,
        )
    }
    CompositionLocalProvider(LocalBotPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = botTypography, content = content)
    }
}
