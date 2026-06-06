package com.n0va.detection.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * MIRO 主题色系
 */
data class ThemeColors(
    val background: Color,
    val navBar: Color,
    val cardBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDim: Color,
    val accent: Color,
    val headerBg: Color,
)

object MiroTheme {
    val Dark = ThemeColors(
        background = Color(0xFF1E1E1E),
        navBar = Color(0xFF2E2E2E),
        cardBg = Color(0xFF2E2E2E),
        textPrimary = Color(0xFFE0E0E0),
        textSecondary = Color(0xFF999999),
        textDim = Color(0xFF666666),
        accent = Color(0xFF07C160),
        headerBg = Color(0xFF252525),
    )

    val Light = ThemeColors(
        background = Color(0xFFF5F5F5),
        navBar = Color(0xFFF0F0F0),
        cardBg = Color(0xFFFFFFFF),
        textPrimary = Color(0xFF191919),
        textSecondary = Color(0xFF666666),
        textDim = Color(0xFF999999),
        accent = Color(0xFF07C160),
        headerBg = Color(0xFFE8E8E8),
    )
}

val LocalTheme = compositionLocalOf { MiroTheme.Dark }
