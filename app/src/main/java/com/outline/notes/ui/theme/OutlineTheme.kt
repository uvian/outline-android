package com.outline.notes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.outline.notes.data.prefs.ThemeMode
import com.outline.notes.data.prefs.UserSettings

object OutlineColors {
    val AccentLight = Color(0xFF0366D6)
    val AccentDark = Color(0xFF4E94FF)
    val SuccessLight = Color(0xFF1A7F37)
    val SuccessDark = Color(0xFF3FB950)
    val WarningLight = Color(0xFFB35900)
    val WarningDark = Color(0xFFD29922)
    val DangerLight = Color(0xFFCF222E)
    val DangerDark = Color(0xFFF85149)

    val PresetAccents = listOf(
        "#0366D6" to "官方蓝",
        "#9E5CF7" to "优雅紫",
        "#10B981" to "翡翠绿",
        "#ED2651" to "珊瑚红",
        "#F05A28" to "活力橙",
        "#D97706" to "琥珀金",
        "#0EA5E9" to "天空青",
        "#475569" to "石墨灰",
    )

    fun parseAccent(hex: String?, fallback: Color = AccentLight): Color {
        if (hex.isNullOrBlank()) return fallback
        return runCatching {
            val clean = hex.trim().removePrefix("#")
            if (clean.length != 6) return fallback
            Color((0xFF000000L or clean.toLong(16)).toInt())
        }.getOrDefault(fallback)
    }
}

private fun buildLightColorScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = if (accent.luminance() > 0.55f) Color(0xFF111319) else Color.White,
    primaryContainer = lerp(Color.White, accent, 0.13f),
    onPrimaryContainer = lerp(Color(0xFF111319), accent, 0.65f),
    secondary = Color(0xFF5E6673),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEF2F6),
    onSecondaryContainer = Color(0xFF1E242E),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111319),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111319),
    surfaceVariant = Color(0xFFF6F8FA),
    onSurfaceVariant = Color(0xFF5E6673),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF3F5F8),
    surfaceContainerHigh = Color(0xFFECEFF3),
    outline = Color(0xFFD5DBE3),
    outlineVariant = Color(0xFFE8ECF1),
    error = OutlineColors.DangerLight,
    onError = Color.White,
)

private fun buildDarkColorScheme(accent: Color): androidx.compose.material3.ColorScheme {
    // Gently brighten very dark accents on dark backgrounds so buttons/links stay readable
    val darkPrimary = if (accent.luminance() < 0.22f) lerp(accent, Color.White, 0.30f) else accent
    return darkColorScheme(
        primary = darkPrimary,
        onPrimary = if (darkPrimary.luminance() > 0.55f) Color(0xFF111319) else Color.White,
        primaryContainer = lerp(Color(0xFF111319), darkPrimary, 0.24f),
        onPrimaryContainer = lerp(Color.White, darkPrimary, 0.25f),
        secondary = Color(0xFF9BA3B0),
        onSecondary = Color(0xFF111319),
        secondaryContainer = Color(0xFF212631),
        onSecondaryContainer = Color(0xFFD8DEE9),
        background = Color(0xFF111319),
        onBackground = Color(0xFFE6EDF3),
        surface = Color(0xFF111319),
        onSurface = Color(0xFFE6EDF3),
        surfaceVariant = Color(0xFF181B22),
        onSurfaceVariant = Color(0xFF9BA3B0),
        surfaceContainerLowest = Color(0xFF0D0F14),
        surfaceContainerLow = Color(0xFF151820),
        surfaceContainer = Color(0xFF1B1F29),
        surfaceContainerHigh = Color(0xFF222734),
        outline = Color(0xFF343B48),
        outlineVariant = Color(0xFF262B36),
        error = OutlineColors.DangerDark,
        onError = Color(0xFF2B0709),
    )
}

private val OutlineTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 21.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun OutlineTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentHex: String = UserSettings.DEFAULT_ACCENT_HEX,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val accent = OutlineColors.parseAccent(accentHex, OutlineColors.AccentLight)

    MaterialTheme(
        colorScheme = if (isDark) buildDarkColorScheme(accent) else buildLightColorScheme(accent),
        typography = OutlineTypography,
        content = content,
    )
}
