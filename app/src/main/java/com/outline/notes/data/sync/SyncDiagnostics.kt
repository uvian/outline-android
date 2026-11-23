package com.outline.notes.data.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * In-memory ring buffer storing recent sync and network diagnostics for troubleshooting.
 * Never logs raw API keys or note contents.
 */
object SyncDiagnostics {
    private const val MAX_ENTRIES = 250
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val entries = ArrayDeque<String>(MAX_ENTRIES)

    @Synchronized
    fun log(tag: String, message: String) {
        val ts = Instant.now().atZone(ZoneId.systemDefault()).format(formatter)
        if (entries.size >= MAX_ENTRIES) {
            entries.removeFirst()
        }
        entries.addLast("[$ts] [$tag] $message")
    }

    @Synchronized
    fun dumpText(): String {
        if (entries.isEmpty()) return "暂无同步诊断日志。"
        return entries.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
