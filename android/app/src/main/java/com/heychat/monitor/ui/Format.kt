package com.heychat.monitor.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
private val displayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/** 解析后端 "yyyy-MM-dd HH:mm:ss"，失败返回 null（不把坏数据伪装成 0）。 */
fun parseServerTime(raw: String?): Date? = raw?.let {
    try {
        fullFormat.parse(it)
    } catch (exc: Exception) {
        null
    }
}

fun formatClock(raw: String?): String = parseServerTime(raw)?.let { displayFormat.format(it) } ?: "-"

fun formatDate(raw: String?): String {
    val date = parseServerTime(raw) ?: return "-"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(date)
}

/** 秒数转可读时长，与后端 duration_text 的口径一致。 */
fun formatDuration(seconds: Long?): String {
    if (seconds == null || seconds < 0) return "-"
    val days = seconds / 86400
    val hours = seconds % 86400 / 3600
    val minutes = seconds % 3600 / 60
    val secs = seconds % 60
    return when {
        days > 0 -> "${days}天${hours}时${minutes}分"
        hours > 0 -> "${hours}时${minutes}分"
        minutes > 0 -> "${minutes}分${secs}秒"
        else -> "${secs}秒"
    }
}

fun formatUptime(seconds: Long): String {
    val days = seconds / 86400
    val hours = seconds % 86400 / 3600
    val minutes = seconds % 3600 / 60
    return "${days}天${hours}时${minutes}分"
}

fun relativeAgo(seconds: Long): String = when {
    seconds < 60 -> "${seconds}秒"
    seconds < 3600 -> "${seconds / 60}分钟"
    seconds < 86400 -> "${seconds / 3600}小时"
    else -> "${seconds / 86400}天"
}

fun todayDateString(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

private val datePattern = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val timePattern = Regex("""^([01]?\d|2[0-3]):[0-5]\d$""")

fun isPlainDate(value: String): Boolean = datePattern.matches(value.trim())

/** 与后端 PUSH_TIME_PATTERN 保持一致的 HH:MM 校验。 */
fun isPushTimeList(value: String): Boolean = value.split(",", "\n")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .all { timePattern.matches(it) }

fun splitList(value: String): List<String> =
    value.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }

fun joinList(values: List<String>): String = values.joinToString(", ")

/** 后端要求 "yyyy-MM-dd HH:mm[:ss]"，界面按分钟精度提交。 */
fun toServerTime(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    val parsed = try {
        displayFormat.parse(trimmed) ?: return null
    } catch (exc: Exception) {
        return null
    }
    return fullFormat.format(parsed)
}
