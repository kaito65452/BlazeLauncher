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
    private val KV_TOKEN_REGEX = Regex("""(?i)\b(access_token|refresh_token|id_token|xsts_token|mc_token|xbox_token|token)=([^\s&;,)"']+)""")
    private val DASHED_UUID_REGEX = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val HEX_32_UUID_REGEX = Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{32}(?![0-9a-fA-F])""")

    const val ENGINE_LOGIN_ENABLED: Boolean = false

    fun engineLoginStatusLine(): String {
        val state = if (ENGINE_LOGIN_ENABLED) "enabled" else "disabled"
        return "engine login: $state (launcher-owned Microsoft OAuth + in-memory setSession handoff only)"
    }

    fun redactDiagnosticText(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var cleaned = redactSensitiveData(input)
        cleaned = JWT_REGEX.replace(cleaned, "[REDACTED_JWT]")
        cleaned = XBL_REGEX.replace(cleaned, "XBL3.0 x=[REDACTED]")
        cleaned = KV_TOKEN_REGEX.replace(cleaned) { m -> "${m.groupValues[1]}=[REDACTED]" }
        cleaned = DASHED_UUID_REGEX.replace(cleaned) { m -> "${m.value.take(8).lowercase()}..." }
        cleaned = HEX_32_UUID_REGEX.replace(cleaned) { m -> "${m.value.take(8).lowercase()}..." }
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
    ): String = collectRustDiagnosticsFromDirs(
        filesDir = context.filesDir,
        statusDir = statusDir(context),
        historicalExits = readHistoricalRustExits(context),
        logcatProvider = logcatProvider,
        jvmWorkerLogFile = runCatching {
            com.movtery.zalithlauncher.game.download.jvm_server.LATEST_PROCESS_LOG_FILE
        }.getOrNull(),
        nowMs = System.currentTimeMillis()
    )

    internal fun collectEngineDataDiagnosis(filesDir: File): String {
        val zipFile = File(filesDir, "engine-data.zip")
        val partialFile = File(filesDir, "engine-data.partial")
        val horizonVerDir = File(filesDir, ".local/horizon-client/26.3")
        val resJson = File(horizonVerDir, "horizon-resources.json")
        val dlJson = File(horizonVerDir, "android-downloads-v2.json")
        val audioJson = File(horizonVerDir, "audio-complete.json")
        val zipState = if (zipFile.isFile) "present (${zipFile.length()} bytes)" else "missing"
        val partialState = if (partialFile.isFile) "present (${partialFile.length()} bytes)" else "missing"
        val resState = if (resJson.isFile) "present" else "missing"
        val dlState = if (dlJson.isFile) "present" else "missing"
        val audioState = if (audioJson.isFile) "present" else "missing"
        return "Engine data: expected path=${zipFile.absolutePath} (and extracted markers under ${horizonVerDir.absolutePath}), " +
            "what was found=engine-data.zip=$zipState, engine-data.partial=$partialState, " +
            "horizon-resources.json=$resState, android-downloads-v2.json=$dlState, audio-complete.json=$audioState"
    }

    internal fun collectRustDiagnosticsFromDirs(
        filesDir: File,
        statusDir: File,
        historicalExits: String = "",
        logcatProvider: () -> String = { readSameUidLogcat() },
        jvmWorkerLogFile: File? = null,
        nowMs: Long = System.currentTimeMillis()
    ): String = buildString {
        appendLine("=== :rust Process Status Trace ===")
        val statusText = runCatching {
            val file = File(statusDir, STATUS_FILE)
            if (file.isFile) redactDiagnosticText(file.readText().trim()) else ""
        }.getOrDefault("")
        appendLine(statusText.ifBlank { "No :rust lifecycle trace recorded yet." })

        appendLine()
        appendLine("=== Engine Login Policy ===")
        appendLine(engineLoginStatusLine())

        appendLine()
        appendLine("=== Engine Data Check ===")
        appendLine(redactDiagnosticText(collectEngineDataDiagnosis(filesDir)))
        appendLine(redactDiagnosticText(EngineDataPrepareTelemetry.formatForReport(filesDir, nowMs = nowMs)))

        val liveWorkerTail = jvmWorkerLogFile
            ?.let { file ->
                EngineDataPrepareSupport.readTailLines(file, EngineDataPrepareSupport.COPY_REPORT_LOG_TAIL_LINES)
            }
            .orEmpty()
        val persistedWorkerTail = EngineDataPrepareTelemetry.load(filesDir)
            ?.safeWorkerLogTail()
            ?.takeLast(EngineDataPrepareSupport.COPY_REPORT_LOG_TAIL_LINES)
            .orEmpty()
        val workerTail = if (liveWorkerTail.isNotEmpty()) liveWorkerTail else persistedWorkerTail
        if (workerTail.isNotEmpty()) {
            appendLine()
            appendLine("=== :jvm Worker Log Tail (Last ${EngineDataPrepareSupport.COPY_REPORT_LOG_TAIL_LINES} Lines, Redacted) ===")
            workerTail.forEach { line ->
                appendLine(redactDiagnosticText(line))
            }
        }

        val engineDiag = readStarlingDiagnosticTail(filesDir)
        val logcatRaw = logcatProvider().trim()

        appendLine()
        appendLine("=== Render Startup & Exit Summary ===")
        appendLine(
            redactDiagnosticText(
                extractRenderStartupSummary(
                    logcatText = logcatRaw,
                    diagText = engineDiag,
                    historicalExitsText = historicalExits
                )
            )
        )

        if (historicalExits.isNotBlank()) {
            appendLine()
            appendLine("=== :rust Historical Process Exits ===")
            appendLine(redactDiagnosticText(historicalExits))
        }

        if (engineDiag.isNotBlank()) {
            appendLine()
            appendLine("=== Starling Diagnostic Log Tail (Redacted) ===")
            appendLine(redactDiagnosticText(engineDiag))
        }

        val logcatTail = redactDiagnosticText(logcatRaw)
        appendLine()
        appendLine("=== :rust Logcat Tail (Redacted) ===")
        appendLine(logcatTail.ifBlank { "(No matching same-UID Horizon/BlazeRustSpike logcat lines)" })
    }

    internal fun formatExitReasonLabel(reasonCode: Int): String = when (reasonCode) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        else -> "UNKNOWN"
    }

    internal fun extractRenderStartupSummary(
        logcatText: String,
        diagText: String,
        historicalExitsText: String = ""
    ): String {
        val combined = buildString {
            if (diagText.isNotBlank()) appendLine(diagText)
            if (logcatText.isNotBlank()) appendLine(logcatText)
        }
        val pipelineMatch = Regex("""pipelines_created_ms=(\d+)([^\r\n]*)""")
            .findAll(combined)
            .lastOrNull()
        val pipelineStr = if (pipelineMatch != null) {
            "pipelines_created_ms=${pipelineMatch.groupValues[1]}${pipelineMatch.groupValues[2].trimEnd()}"
        } else {
            "pipelines_created_ms=unavailable (no BlazePerf pipeline sample captured yet)"
        }

        val surfaceMatch = Regex("""surface_to_first_frame_ms=(\d+)(?:\s+backend=([^\s]+))?(?:\s+adapter=([^\r\n]+))?""")
            .findAll(combined)
            .lastOrNull()
        val firstFrameFallback = Regex("""Horizon:\s+first frame after\s+(\d+)\s+ms""")
            .findAll(combined)
            .lastOrNull()
        val rendererFallback = Regex("""Horizon renderer:\s+([^\r\n(]+)\(([^,\r\n]+),\s*([^)\r\n]+)\)""")
            .findAll(combined)
            .lastOrNull()

        val surfaceStr = if (surfaceMatch != null) {
            val ms = surfaceMatch.groupValues[1]
            val backend = surfaceMatch.groupValues[2].ifBlank {
                rendererFallback?.groupValues?.getOrNull(3)?.trim().orEmpty().ifBlank { "unknown" }
            }
            val adapter = surfaceMatch.groupValues[3].ifBlank {
                rendererFallback?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { "unknown" }
            }
            "surface_to_first_frame_ms=$ms backend=$backend adapter=$adapter"
        } else if (firstFrameFallback != null) {
            val backend = rendererFallback?.groupValues?.getOrNull(3)?.trim().orEmpty().ifBlank { "unknown" }
            val adapter = rendererFallback?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { "unknown" }
            "surface_to_first_frame_ms=unavailable (engine first_frame_after_ms=${firstFrameFallback.groupValues[1]}) backend=$backend adapter=$adapter"
        } else {
            "surface_to_first_frame_ms=unavailable backend=unknown adapter=unknown"
        }

        val exitFirstLine = historicalExitsText
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
        val exitStr = if (exitFirstLine != null) {
            val codeMatch = Regex("""reason=(\d+)""").find(exitFirstLine)
            val reasonLabel = codeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { code ->
                "${formatExitReasonLabel(code)}($code)"
            } ?: "unparsed"
            "last_rust_exit=$reasonLabel [$exitFirstLine]"
        } else {
            "last_rust_exit=none recorded"
        }

        return listOf(pipelineStr, surfaceStr, exitStr).joinToString("\n")
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

    private fun statusDir(context: Context): File =
        runCatching { context.noBackupFilesDir }.getOrNull() ?: context.filesDir

    private fun statusFile(context: Context): File =
        File(statusDir(context), STATUS_FILE)

    private fun formatTimestamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
}
