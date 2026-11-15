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

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class JvmWorkerHeartbeat(
    @SerializedName("elapsed_seconds") val elapsedSeconds: Long = 0L,
    @SerializedName("current_step") val currentStep: String = "",
    @SerializedName("free_heap_mb") val freeHeapMb: Long = -1L,
    @SerializedName("free_heap_source") val freeHeapSource: String = "unavailable",
    @SerializedName("javac_running") val javacRunning: Boolean = false,
    @SerializedName("attempt") val attempt: Int = 1,
    @SerializedName("recorded_at_ms") val recordedAtMs: Long = 0L
)

data class JvmStepAttemptRecord(
    @SerializedName("step_name") val stepName: String = "",
    @SerializedName("attempt") val attempt: Int = 1,
    @SerializedName("outcome") val outcome: String = "",
    @SerializedName("elapsed_seconds") val elapsedSeconds: Long = 0L,
    @SerializedName("heap_mb") val heapMb: Int = 0,
    @SerializedName("classpath_entries") val classpathEntries: Int = 0,
    @SerializedName("detail") val detail: String = ""
)

/**
 * Redacted timing and `VmHWM` peak-RSS summary persisted for `RustDiagnosticsCollector`.
 */
data class EngineDataPrepareMetrics(
    @SerializedName("status") val status: String = "not run",
    @SerializedName("minecraft_version") val minecraftVersion: String = "",
    @SerializedName("total_ms") val totalMs: Long = 0L,
    @SerializedName("phase_timings_ms") val phaseTimingsMs: Map<String, Long> = emptyMap(),
    @SerializedName("peak_rss_kb") val peakRssKb: Long = 0L,
    @SerializedName("files_reused") val filesReused: Long = 0L,
    @SerializedName("files_downloaded") val filesDownloaded: Long = 0L,
    @SerializedName("engine_data_entries") val engineDataEntries: Long = 0L,
    @SerializedName("failed_phase") val failedPhase: String = "",
    @SerializedName("last_error") val lastError: String = "",
    @SerializedName("warnings") val warnings: List<String> = emptyList(),
    @SerializedName("updated_at_ms") val updatedAtMs: Long = 0L,
    @SerializedName("current_phase") val currentPhase: String = "",
    @SerializedName("phase_start_ms") val phaseStartMs: Long = 0L,
    @SerializedName("last_heartbeat_at_ms") val lastHeartbeatAtMs: Long = 0L,
    @SerializedName("heartbeat") val heartbeat: JvmWorkerHeartbeat? = null,
    @SerializedName("step_attempts") val stepAttempts: List<JvmStepAttemptRecord> = emptyList(),
    @SerializedName("worker_log_tail") val workerLogTail: List<String> = emptyList()
)

internal fun EngineDataPrepareMetrics.safeWarnings(): List<String> {
    @Suppress("USELESS_ELVIS")
    return warnings ?: emptyList()
}

internal fun EngineDataPrepareMetrics.safeStepAttempts(): List<JvmStepAttemptRecord> {
    @Suppress("USELESS_ELVIS")
    return stepAttempts ?: emptyList()
}

internal fun EngineDataPrepareMetrics.safeWorkerLogTail(): List<String> {
    @Suppress("USELESS_ELVIS")
    return workerLogTail ?: emptyList()
}

object EngineDataPrepareTelemetry {
    private const val METRICS_FILE = "blaze_prepare_metrics.json"
    private val gson = Gson()

    fun metricsFile(filesDir: File): File = File(filesDir, METRICS_FILE)

    fun save(filesDir: File, metrics: EngineDataPrepareMetrics) {
        val redactedAttempts = metrics.safeStepAttempts().map { rec ->
            rec.copy(
                stepName = RustDiagnosticsCollector.redactDiagnosticText(rec.stepName),
                outcome = RustDiagnosticsCollector.redactDiagnosticText(rec.outcome),
                detail = RustDiagnosticsCollector.redactDiagnosticText(rec.detail)
            )
        }
        val redactedHeartbeat = metrics.heartbeat?.let { hb ->
            hb.copy(currentStep = RustDiagnosticsCollector.redactDiagnosticText(hb.currentStep))
        }
        val redactedTail = metrics.safeWorkerLogTail()
            .takeLast(EngineDataPrepareSupport.COPY_REPORT_LOG_TAIL_LINES)
            .map { RustDiagnosticsCollector.redactDiagnosticText(it) }
        @Suppress("USELESS_ELVIS")
        val safeCurrentPhase = RustDiagnosticsCollector.redactDiagnosticText(metrics.currentPhase ?: "")
        @Suppress("USELESS_ELVIS")
        val safeFailedPhase = RustDiagnosticsCollector.redactDiagnosticText(metrics.failedPhase ?: "")

        val redacted = metrics.copy(
            failedPhase = safeFailedPhase,
            currentPhase = safeCurrentPhase,
            lastError = RustDiagnosticsCollector.redactDiagnosticText(metrics.lastError),
            warnings = metrics.safeWarnings().map { RustDiagnosticsCollector.redactDiagnosticText(it) },
            heartbeat = redactedHeartbeat,
            stepAttempts = redactedAttempts,
            workerLogTail = redactedTail
        )
        runCatching {
            val file = metricsFile(filesDir)
            val tmp = File(filesDir, "$METRICS_FILE.partial")
            file.parentFile?.mkdirs()
            tmp.writeText(gson.toJson(redacted))
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(redacted))
                tmp.delete()
            }
        }
    }

    fun load(filesDir: File): EngineDataPrepareMetrics? {
        val file = metricsFile(filesDir)
        if (!file.isFile) return null
        return runCatching {
            gson.fromJson(file.readText(), EngineDataPrepareMetrics::class.java)
        }.getOrNull()
    }

    private fun formatEpochMs(ms: Long): String {
        if (ms <= 0L) return "unavailable"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
    }

    fun formatForReport(filesDir: File, nowMs: Long = System.currentTimeMillis()): String {
        val metrics = load(filesDir) ?: return "Engine data preparation: no run recorded yet."
        return buildString {
            appendLine("status=${metrics.status}, version=${metrics.minecraftVersion.ifBlank { "unknown" }}, total_ms=${metrics.totalMs}, peak_rss_kb=${metrics.peakRssKb}")
            @Suppress("USELESS_ELVIS")
            val activePhase = (metrics.currentPhase ?: "").trim()
                .ifBlank { (metrics.failedPhase ?: "").trim() }
                .ifBlank { metrics.status }
            appendLine("current_phase: ${RustDiagnosticsCollector.redactDiagnosticText(activePhase)}")
            appendLine("phase_start_time: ${formatEpochMs(metrics.phaseStartMs)}")
            val hbAt = metrics.lastHeartbeatAtMs
            val hbLine = if (hbAt > 0L) {
                val ageSec = maxOf(0L, (nowMs - hbAt) / 1000L)
                val hb = metrics.heartbeat
                if (hb != null) {
                    val heapText = if (hb.freeHeapMb >= 0L) "${hb.freeHeapMb}MB[${hb.freeHeapSource}]" else "unavailable"
                    "${ageSec}s ago (elapsed=${hb.elapsedSeconds}s, step=${RustDiagnosticsCollector.redactDiagnosticText(hb.currentStep)}, free_heap=$heapText, javac_running=${hb.javacRunning}, attempt=${hb.attempt})"
                } else {
                    "${ageSec}s ago"
                }
            } else {
                "unavailable"
            }
            appendLine("last_heartbeat_age: $hbLine")
            appendLine("files_reused=${metrics.filesReused}, files_downloaded=${metrics.filesDownloaded}, engine_data_entries=${metrics.engineDataEntries}")
            if (metrics.phaseTimingsMs.isNotEmpty()) {
                val breakdown = metrics.phaseTimingsMs.entries.joinToString(", ") { "${it.key}=${it.value}ms" }
                appendLine("phases: $breakdown")
            }
            val attempts = metrics.safeStepAttempts()
            if (attempts.isNotEmpty()) {
                attempts.forEach { rec ->
                    val detailSuffix = if (rec.detail.isNotBlank()) ", detail=${RustDiagnosticsCollector.redactDiagnosticText(rec.detail)}" else ""
                    appendLine(
                        "attempt #${rec.attempt} [${RustDiagnosticsCollector.redactDiagnosticText(rec.stepName)}]: " +
                            "outcome=${rec.outcome}, elapsed=${rec.elapsedSeconds}s, xmx=${rec.heapMb}M, cp_entries=${rec.classpathEntries}$detailSuffix"
                    )
                }
            }
            @Suppress("USELESS_ELVIS")
            val phase = (metrics.failedPhase ?: "").trim()
            if (phase.isNotBlank()) {
                appendLine("failed_phase: ${RustDiagnosticsCollector.redactDiagnosticText(phase)}")
            }
            if (metrics.lastError.isNotBlank()) {
                appendLine("last_error: ${RustDiagnosticsCollector.redactDiagnosticText(metrics.lastError)}")
            }
            val safeWarnings = metrics.safeWarnings().filter { it.isNotBlank() }
            if (safeWarnings.isNotEmpty()) {
                safeWarnings.forEach { warn ->
                    appendLine("warning: ${RustDiagnosticsCollector.redactDiagnosticText(warn)}")
                }
            }
        }.trimEnd()
    }
}
