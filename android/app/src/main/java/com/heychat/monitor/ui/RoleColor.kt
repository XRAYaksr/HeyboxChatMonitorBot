package com.heychat.monitor.ui

import androidx.compose.ui.graphics.Color

/**
 * 平台身份组颜色为 #RRGGBB 或 #AARRGGBB。
 * 无法解析时返回 null，由调用方回落到主题角色色，避免把坏数据显示成黑色块。
 */
fun parseRoleColor(raw: String?): Color? {
    val hex = raw?.trim()?.removePrefix("#") ?: return null
    if (hex.length != 6 && hex.length != 8) return null
    val value = hex.toLongOrNull(16) ?: return null
    val argb = if (hex.length == 6) 0xFF000000L or value else value
    return Color(argb.toInt())
}
