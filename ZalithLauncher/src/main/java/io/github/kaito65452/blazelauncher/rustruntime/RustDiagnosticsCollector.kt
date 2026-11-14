/*
 * Blaze Launcher (based on Zalith Launcher 2)
 * Copyright (C) 2026 Blaze Launcher contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package io.github.kaito65452.blazelauncher.rustruntime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.github.kaito65452.blazelauncher.msauth.redactSensitiveData
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists `:rust` lifecycle events across the `:rust` / main process boundary and collects
 * redacted `:rust` diagnostic tails for the Developer "Copy report" dialog.
 */
object RustDiagnosticsCollector {
    private const val STATUS_FILE = "rust_spike_last_status.txt"
    private const val MAX_STATUS_LINES = 24
    private const val MAX_LOGCAT_LINES = 80
    private const val MAX_FILE_LOG_LINES = 40

    private val JWT_REGEX = Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}(?:\.[A-Za-z0-9_-]*)?""")
    private val XBL_REGEX = Regex("""(?i)XBL3\.0\s+x=[^\s;"]+""")

    fun redactDiagnosticText(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var cleaned = redactSensitiveData(input)
        cleaned = JWT_REGEX.replace(cleaned, "[REDACTED_JWT]")
        cleaned = XBL_REGEX.replace(cleaned, "XBL3.0 x=[REDACTED]")
        return cleaned
    }

    fun recordRustEvent(context: Context, event: String) {
        val line = "[${formatTimestamp(System.currentTimeMillis())}] ${redactDiagnosticText(event)}"
        val file = statusFile(context)
        runCatching {
            val existing = if (file.isFile) file.readLines() else emptyList()
            val updated = (existing + line).takeLast(MAX_STATUS_LINES)
            file.parentFile?.mkdirs()
            file.writeText(updated.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    fun collectRustProcessDiagnostics(
        context: Context,
        logcatProvider: () -> String = { readSameUidLogcat() }
    ): String = buildString {
        appendLine("=== :rust Process Status Trace ===")
        val statusText = runCatching {
            val file = statusFile(context)
            if (file.isFile) redactDiagnosticText(file.readText().trim()) else ""
        }.getOrDefault("")
        appendLine(statusText.ifBlank { "No :rust lifecycle trace recorded yet." })

        val exitInfo = readHistoricalRustExits(context)
        if (exitInfo.isNotBlank()) {
            appendLine()
            appendLine("=== :rust Historical Process Exits ===")
            appendLine(redactDiagnosticText(exitInfo))
        }

        val engineDiag = readStarlingDiagnosticTail(context.filesDir)
        if (engineDiag.isNotBlank()) {
            appendLine()
            appendLine("=== Starling Diagnostic Log Tail (Redacted) ===")
            appendLine(redactDiagnosticText(engineDiag))
        }

        val logcatTail = redactDiagnosticText(logcatProvider().trim())
        appendLine()
        appendLine("=== :rust Logcat Tail (Redacted) ===")
        appendLine(logcatTail.ifBlank { "(No matching same-UID Horizon/BlazeRustSpike logcat lines)" })
    }

    private fun readHistoricalRustExits(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching ""
            am.getHistoricalProcessExitReasons(context.packageName, 0, 12)
                .filter { it.processName.endsWith(":rust") }
                .take(5)
                .joinToString("\n") { info ->
                    "${formatTimestamp(info.timestamp)} pid=${info.pid} reason=${info.reason} status=${info.status} desc=${info.description.orEmpty()}"
                }
        }.getOrDefault("")
    }

    internal fun readStarlingDiagnosticTail(filesDir: File): String {
        val perfDir = File(filesDir, "performance")
        val parts = mutableListOf<String>()
        val sessionFile = File(perfDir, "current-session.json")
        if (sessionFile.isFile) {
            runCatching {
                parts += "current-session.json: ${sessionFile.readText().trim().take(1200)}"
            }
        }
        val diagLog = File(perfDir, "diagnostic.log")
        if (diagLog.isFile) {
            runCatching {
                val tail = diagLog.readLines().takeLast(MAX_FILE_LOG_LINES)
                if (tail.isNotEmpty()) {
                    parts += tail.joinToString("\n")
                }
            }
        }
        return parts.joinToString("\n")
    }

    private fun readSameUidLogcat(): String = runCatching {
        val process = ProcessBuilder(
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "-t",
            "250",
            "-s",
            "Horizon:V",
            "BlazeRustSpike:V",
            "BlazeNativeBridge:V",
            "wgpu:V",
            "wgpu_hal:V"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { reader ->
            reader.lineSequence().toList()
        }
        process.waitFor()
        output.takeLast(MAX_LOGCAT_LINES).joinToString("\n")
    }.getOrDefault("")

    private fun statusFile(context: Context): File {
        val dir = runCatching { context.noBackupFilesDir }.getOrNull() ?: context.filesDir
        return File(dir, STATUS_FILE)
    }

    private fun formatTimestamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
}
