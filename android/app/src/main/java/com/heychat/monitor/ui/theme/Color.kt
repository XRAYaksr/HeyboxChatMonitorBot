package com.heychat.monitor.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Blue 主题。设计稿只交付浅色模式，因此深色方案沿用同一套角色值的暗色推导，
 * 仅作为「跟随系统」时的兜底，不参与本次验收。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    inversePrimary = Color(0xFFA8C7FA),
    secondary = Color(0xFF5A5C7C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8EE),
    onTertiaryContainer = Color(0xFF2E1125),
    background = Color(0xFFFAF9FD),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFAF9FD),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE3E2E6),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEEEDF3),
    surfaceContainerHigh = Color(0xFFE9E8EF),
    surfaceContainerHighest = Color(0xFFE3E2E6),
    surfaceBright = Color(0xFFFBFAFF),
    surfaceDim = Color(0xFFDBDADF),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF2F0F4),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF0B57D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF043A73),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    inversePrimary = Color(0xFF0B57D0),
    secondary = Color(0xFFC0C5E4),
    onSecondary = Color(0xFF2A2D41),
    secondaryContainer = Color(0xFF414459),
    onSecondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFFF2B8CB),
    onTertiary = Color(0xFF4A2634),
    tertiaryContainer = Color(0xFF633C4A),
    onTertiaryContainer = Color(0xFFFFD8EE),
    background = Color(0xFF1B1B1F),
    onBackground = Color(0xFFE4E2E7),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE4E2E7),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF131316),
    surfaceContainerLow = Color(0xFF1F1F23),
    surfaceContainer = Color(0xFF242428),
    surfaceContainerHigh = Color(0xFF2E2F33),
    surfaceContainerHighest = Color(0xFF39393D),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFE4E2E7),
    inverseOnSurface = Color(0xFF303034),
)

val HeyChatLightColorScheme: ColorScheme = LightColors
val HeyChatDarkColorScheme: ColorScheme = DarkColors
