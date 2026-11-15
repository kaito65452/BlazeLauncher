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
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * Configuration JSON passed across JNI into `libblaze_prepare.so`.
 */
data class PrepareConfigJson(
    @SerializedName("files_dir") val filesDir: String,
    @SerializedName("game_home") val gameHome: String? = null,
    @SerializedName("lock_path") val lockPath: String,
    @SerializedName("controls_manifest_path") val controlsManifestPath: String,
    @SerializedName("parity_sources_dir") val paritySourcesDir: String? = null,
    @SerializedName("catalog_url") val catalogUrl: String? = null,
    @SerializedName("objects_base_url") val objectsBaseUrl: String? = null,
    @SerializedName("controls_base_url") val controlsBaseUrl: String? = null,
    @SerializedName("allow_loopback") val allowLoopback: Boolean = false,
    @SerializedName("download_workers") val downloadWorkers: Int = 6
)

data class DownloadStatsJson(
    @SerializedName("files_reused") val filesReused: Long = 0,
    @SerializedName("bytes_reused") val bytesReused: Long = 0,
    @SerializedName("files_downloaded") val filesDownloaded: Long = 0,
    @SerializedName("bytes_downloaded") val bytesDownloaded: Long = 0
)

data class JavaPlanJson(
    @SerializedName("minecraft_version") val minecraftVersion: String = "",
    @SerializedName("java_major") val javaMajor: Int = 25,
    @SerializedName("server_jar") val serverJar: String = "",
    @SerializedName("server_game_jar") val serverGameJar: String = "",
    @SerializedName("client_jar") val clientJar: String = "",
    @SerializedName("server_classpath") val serverClasspath: String = "",
    @SerializedName("client_classpath") val clientClasspath: String = "",
    @SerializedName("server_classes_dir") val serverClassesDir: String = "",
    @SerializedName("client_classes_dir") val clientClassesDir: String = "",
    @SerializedName("datagen_out_dir") val datagenOutDir: String = "",
    @SerializedName("server_tasks_jsonl") val serverTasksJsonl: String = "",
    @SerializedName("client_tasks_jsonl") val clientTasksJsonl: String = "",
    @SerializedName("command_registries_dir") val commandRegistriesDir: String = "",
    @SerializedName("creative_inventory_json") val creativeInventoryJson: String = "",
    @SerializedName("stage_dir") val stageDir: String = "",
    @SerializedName("work_dir") val workDir: String = ""
)

data class Phase1OutputJson(
    @SerializedName("work_dir") val workDir: String = "",
    @SerializedName("stage_dir") val stageDir: String = "",
    @SerializedName("target_resources_dir") val targetResourcesDir: String = "",
    @SerializedName("java_plan") val javaPlan: JavaPlanJson = JavaPlanJson(),
    @SerializedName("download_stats") val downloadStats: DownloadStatsJson = DownloadStatsJson(),
    @SerializedName("client_resource_files") val clientResourceFiles: Long = 0,
    @SerializedName("indexed_assets_copied") val indexedAssetsCopied: Long = 0,
    @SerializedName("controls_ready") val controlsReady: Long = 0,
    @SerializedName("worldgen_structures_extracted") val worldgenStructuresExtracted: Long = 0,
    @SerializedName("worldgen_maps_written") val worldgenMapsWritten: Long = 0,
    @SerializedName("elapsed_ms") val elapsedMs: Long = 0,
    @SerializedName("peak_rss_kb") val peakRssKb: Long = 0
)

data class Phase2OutputJson(
    @SerializedName("target_resources_dir") val targetResourcesDir: String = "",
    @SerializedName("engine_data_zip") val engineDataZip: String = "",
    @SerializedName("engine_data_entries") val engineDataEntries: Long = 0,
    @SerializedName("survival_rules_count") val survivalRulesCount: Long = 0,
    @SerializedName("items_count") val itemsCount: Long = 0,
    @SerializedName("elapsed_ms") val elapsedMs: Long = 0,
    @SerializedName("peak_rss_kb") val peakRssKb: Long = 0
)

data class PrepareProgressSnapshot(
    @SerializedName("phase") val phase: String = "",
    @SerializedName("done") val done: Long = 0,
    @SerializedName("total") val total: Long = 0,
    @SerializedName("peak_rss_kb") val peakRssKb: Long = 0
)

sealed interface PrepareEnvelope<out T> {
    data class Success<T>(val data: T) : PrepareEnvelope<T>
    data class Failure(val error: String, val peakRssKb: Long) : PrepareEnvelope<Nothing>
}

/**
 * JNI wrapper around `libblaze_prepare.so` (pure-Rust on-device 26.3 engine-data preparer).
 * Library loading is explicit rather than automatic during static initialization so JVM unit
 * tests can exercise JSON encoding/decoding without loading native `.so` binaries.
 */
object BlazePrepareNative {
    private val gson = Gson()

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var loadError: String? = null

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            return try {
                System.loadLibrary("blaze_prepare")
                loaded = true
                loadError = null
                true
            } catch (t: UnsatisfiedLinkError) {
                loadError = t.message ?: "UnsatisfiedLinkError loading blaze_prepare"
                false
            }
        }
    }

    fun lastLoadError(): String? = loadError

    fun encodeConfig(config: PrepareConfigJson): String = gson.toJson(config)

    fun parseProgress(rawJson: String): PrepareProgressSnapshot =
        runCatching { gson.fromJson(rawJson, PrepareProgressSnapshot::class.java) }
            .getOrNull() ?: PrepareProgressSnapshot()

    fun <T> parseEnvelope(rawJson: String, payloadClass: Class<T>): PrepareEnvelope<T> {
        return try {
            val root = JsonParser.parseString(rawJson).asJsonObject
            val ok = root.get("ok")?.asBoolean == true
            if (ok) {
                val payload = gson.fromJson(root.get("data"), payloadClass)
                    ?: return PrepareEnvelope.Failure("Missing data payload in native response", 0L)
                PrepareEnvelope.Success(payload)
            } else {
                val err = root.get("error")?.asString ?: "Unknown native prepare error"
                val rss = root.get("peak_rss_kb")?.asLong ?: 0L
                PrepareEnvelope.Failure(err, rss)
            }
        } catch (t: Throwable) {
            PrepareEnvelope.Failure("Malformed native response JSON: ${t.message}", 0L)
        }
    }

    @JvmStatic external fun nativePreparePhase1(configJson: String): String
    @JvmStatic external fun nativePreparePhase2(configJson: String): String
    @JvmStatic external fun nativeRequestCancel()
    @JvmStatic external fun nativeResetCancel()
    @JvmStatic external fun nativeGetProgressJson(): String
    @JvmStatic external fun nativeIsPrepared(filesDir: String, version: String): Boolean
    @JvmStatic external fun nativePeakRssKb(): Long
}
