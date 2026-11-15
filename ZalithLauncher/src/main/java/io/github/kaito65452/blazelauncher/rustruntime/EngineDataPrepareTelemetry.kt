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
    @SerializedName("last_error") val lastError: String = "",
    @SerializedName("warnings") val warnings: List<String> = emptyList(),
    @SerializedName("updated_at_ms") val updatedAtMs: Long = 0L
)

internal fun EngineDataPrepareMetrics.safeWarnings(): List<String> {
    @Suppress("USELESS_ELVIS")
    return warnings ?: emptyList()
}

object EngineDataPrepareTelemetry {
    private const val METRICS_FILE = "blaze_prepare_metrics.json"
    private val gson = Gson()

    fun metricsFile(filesDir: File): File = File(filesDir, METRICS_FILE)

    fun save(filesDir: File, metrics: EngineDataPrepareMetrics) {
        val redacted = metrics.copy(
            lastError = RustDiagnosticsCollector.redactDiagnosticText(metrics.lastError),
            warnings = metrics.safeWarnings().map { RustDiagnosticsCollector.redactDiagnosticText(it) }
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

    fun formatForReport(filesDir: File): String {
        val metrics = load(filesDir) ?: return "Engine data preparation: no run recorded yet."
        return buildString {
            appendLine("status=${metrics.status}, version=${metrics.minecraftVersion.ifBlank { "unknown" }}, total_ms=${metrics.totalMs}, peak_rss_kb=${metrics.peakRssKb}")
            appendLine("files_reused=${metrics.filesReused}, files_downloaded=${metrics.filesDownloaded}, engine_data_entries=${metrics.engineDataEntries}")
            if (metrics.phaseTimingsMs.isNotEmpty()) {
                val breakdown = metrics.phaseTimingsMs.entries.joinToString(", ") { "${it.key}=${it.value}ms" }
                appendLine("phases: $breakdown")
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
