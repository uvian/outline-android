package com.outline.notes.ui.components

import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object UiFormatters {
    private val dateFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val fullFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun relativeTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return ""
        val diffSec = (System.currentTimeMillis() - epochMillis) / 1000L
        return when {
            diffSec < 60 -> "刚刚"
            diffSec < 3600 -> "${diffSec / 60} 分钟前"
            diffSec < 86400 -> "${diffSec / 3600} 小时前"
            diffSec < 86400 * 7 -> "${diffSec / 86400} 天前"
            else -> Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .format(dateFormatter)
        }
    }

    fun formatFullTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return "从未同步"
        return Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(fullFormatter)
    }

    fun formatIsoTime(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return runCatching {
            val millis = Instant.parse(iso).toEpochMilli()
            relativeTime(millis)
        }.getOrDefault(iso.take(16))
    }

    fun stripMarkdownSnippet(markdown: String, maxLen: Int = 120): String {
        if (markdown.isBlank()) return "空白笔记"
        val cleaned = markdown
            .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "[图片]")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("```[\\s\\S]*?```"), "[代码块]")
            .replace(Regex("(?m)^#{1,6}\\s+"), "")
            .replace(Regex("(?m)^\\s*[-*+]\\s+\\[[ xX]\\]\\s*"), "☐ ")
            .replace(Regex("(?m)^\\s*[-*+>]\\s+"), "")
            .replace(Regex("[*_`~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen) + "…"
    }

    fun parseHexColor(hex: String?, fallback: Color = Color(0xFF0366D6)): Color {
        if (hex.isNullOrBlank()) return fallback
        return runCatching {
            val clean = hex.trim().removePrefix("#")
            val parsed = clean.toLong(16)
            when (clean.length) {
                6 -> Color((0xFF000000L or parsed).toInt())
                8 -> Color(parsed.toInt())
                else -> fallback
            }
        }.getOrDefault(fallback)
    }

    fun formatBytes(bytes: Long): String {
        val absB = abs(bytes)
        return when {
            absB < 1024 -> "$absB B"
            absB < 1024 * 1024 -> "%.1f KB".format(absB / 1024.0)
            else -> "%.1f MB".format(absB / (1024.0 * 1024.0))
        }
    }
}
