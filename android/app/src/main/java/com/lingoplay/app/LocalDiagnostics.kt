package com.lingoplay.app

import android.content.Context
import java.io.File
import java.time.Instant

object LocalDiagnostics {
    private const val ROOT = "lingoplay/diagnostics"
    private const val FILE_NAME = "events.log"
    private const val MAX_LINES = 120
    private const val MAX_BYTES = 96 * 1024L
    private val lock = Any()

    fun record(context: Context, event: String) {
        val safeEvent = event.lowercase().replace(Regex("[^a-z0-9_.-]"), "_").take(64)
        if (safeEvent.isBlank()) return
        synchronized(lock) {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            file.appendText("${Instant.now()} $safeEvent\n", Charsets.UTF_8)
            if (file.length() > MAX_BYTES) trim(file)
        }
    }

    fun recent(context: Context): List<String> = synchronized(lock) {
        val file = logFile(context)
        if (!file.isFile) return@synchronized emptyList()
        file.readLines(Charsets.UTF_8).takeLast(MAX_LINES)
    }

    fun clear(context: Context) = synchronized(lock) {
        logFile(context).delete()
    }

    private fun trim(file: File) {
        val lines = file.readLines(Charsets.UTF_8).takeLast(MAX_LINES)
        file.writeText(lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"), Charsets.UTF_8)
    }

    private fun logFile(context: Context): File = File(File(context.filesDir, ROOT), FILE_NAME)
}
