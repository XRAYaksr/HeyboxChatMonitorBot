package com.heychat.monitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** 设计稿只交付浅色模式；深浅色开关用于个人偏好，默认浅色。 */
enum class ThemeMode { Light, Dark, System }

@Composable
fun HeyChatTheme(
    themeMode: ThemeMode = ThemeMode.Light,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) HeyChatDarkColorScheme else HeyChatLightColorScheme,
        shapes = HeyChatShapes,
        content = content,
    )
}
