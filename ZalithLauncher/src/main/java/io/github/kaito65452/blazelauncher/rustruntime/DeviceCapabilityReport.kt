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
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.util.Locale

data class CapabilitySnapshot(
    val deviceModel: String,
    val androidVersion: String,
    val apiLevel: Int,
    val supportedAbis: List<String>,
    val vulkanHardwareVersionRaw: Int?,
    val vulkanHardwareLevel: Int?,
    val glEsVersion: String?,
    val outputSampleRateHz: Int?,
    val outputFramesPerBuffer: Int?,
    val hasLowLatencyAudioFeature: Boolean,
    val hasProAudioFeature: Boolean,
    val aecAvailable: Boolean,
    val nsAvailable: Boolean,
    val agcAvailable: Boolean,
    val queriedEffectsCount: Int?,
    val queriedEffectsError: String?
)

object DeviceCapabilityReport {
    const val VULKAN_1_3_PACKED: Int = (1 shl 22) or (3 shl 12)

    fun formatVulkanVersion(rawVersion: Int): String {
        val major = rawVersion ushr 22
        val minor = (rawVersion ushr 12) and 0x3FF
        val patch = rawVersion and 0xFFF
        return "$major.$minor.$patch"
    }

    fun supportsVulkanApi(rawVersion: Int?, targetMajor: Int, targetMinor: Int): Boolean {
        if (rawVersion == null) return false
        val required = (targetMajor shl 22) or (targetMinor shl 12)
        return rawVersion >= required
    }

    fun estimateBufferLatencyMs(sampleRateHz: Int?, framesPerBuffer: Int?): Double? {
        if (sampleRateHz == null || framesPerBuffer == null) return null
        if (sampleRateHz <= 0 || framesPerBuffer <= 0) return null
        return framesPerBuffer.toDouble() * 1000.0 / sampleRateHz.toDouble()
    }

    fun collect(context: Context): CapabilitySnapshot {
        val pm = context.packageManager
        val features = runCatching { pm.systemAvailableFeatures }.getOrNull().orEmpty()

        val vulkanVersion = features
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            ?.version
        val vulkanLevel = features
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }
            ?.version

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val glEsVersion = runCatching {
            activityManager?.deviceConfigurationInfo?.glEsVersion
        }.getOrNull()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val sampleRate = runCatching {
            audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        }.getOrNull()
        val framesPerBuffer = runCatching {
            audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        }.getOrNull()

        val hasLowLatency = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        val hasProAudio = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)

        val aec = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
        val ns = runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)
        val agc = runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)

        var effectsCount: Int? = null
        var effectsErr: String? = null
        try {
            effectsCount = AudioEffect.queryEffects()?.size ?: 0
        } catch (t: Throwable) {
            effectsErr = "${t.javaClass.simpleName}: ${t.message}"
        }

        return CapabilitySnapshot(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            apiLevel = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            vulkanHardwareVersionRaw = vulkanVersion,
            vulkanHardwareLevel = vulkanLevel,
            glEsVersion = glEsVersion,
            outputSampleRateHz = sampleRate,
            outputFramesPerBuffer = framesPerBuffer,
            hasLowLatencyAudioFeature = hasLowLatency,
            hasProAudioFeature = hasProAudio,
            aecAvailable = aec,
            nsAvailable = ns,
            agcAvailable = agc,
            queriedEffectsCount = effectsCount,
            queriedEffectsError = effectsErr
        )
    }

    fun format(snapshot: CapabilitySnapshot): String {
        val vkVer = snapshot.vulkanHardwareVersionRaw?.let {
            "${formatVulkanVersion(it)} (raw=0x${it.toString(16)})"
        } ?: "Not advertised by PackageManager"
        val vk13 = if (snapshot.vulkanHardwareVersionRaw != null) {
            supportsVulkanApi(snapshot.vulkanHardwareVersionRaw, 1, 3).toString()
        } else {
            "false (feature missing)"
        }
        val latencyMs = estimateBufferLatencyMs(snapshot.outputSampleRateHz, snapshot.outputFramesPerBuffer)
        val latencyStr = if (latencyMs != null) {
            String.format(Locale.US, "%.2f ms/buffer", latencyMs)
        } else {
            "unknown"
        }
        val arm64Ready = snapshot.supportedAbis.contains("arm64-v8a")

        return buildString {
            appendLine("=== Blaze Launcher: Rust Engine Device Capability Report ===")
            appendLine("Device: ${snapshot.deviceModel}")
            appendLine("Android: ${snapshot.androidVersion} (API ${snapshot.apiLevel})")
            appendLine("Supported ABIs: ${snapshot.supportedAbis.joinToString(", ").ifEmpty { "none" }}")
            appendLine("arm64-v8a supported: $arm64Ready")
            appendLine()
            appendLine("[Graphics / Vulkan]")
            appendLine("PackageManager Vulkan version: $vkVer")
            appendLine("Vulkan 1.3+ advertised: $vk13")
            appendLine("PackageManager Vulkan level: ${snapshot.vulkanHardwareLevel ?: "not advertised"}")
            appendLine("OpenGL ES version: ${snapshot.glEsVersion ?: "unknown"}")
            appendLine("Starling actual requirement (verified statically in engine/starling):")
            appendLine("  - Not Vulkan 1.3 required.")
            appendLine("  - GraphicsApi::Auto tries [Backends::VULKAN, Backends::GL] (gfx.rs:1344),")
            appendLine("    caps ApplicationInfo api_version up to 1.3 (wgpu-hal instance.rs:593-625),")
            appendLine("    and gates 1.1/1.2/1.3 features conditionally on device_api_version (adapter.rs:381-420),")
            appendLine("    falling back to OpenGL ES 3.0 (AndroidManifest.xml:5-6).")
            appendLine()
            appendLine("[Audio Output & Voice Effects]")
            appendLine("Nominal output sample rate: ${snapshot.outputSampleRateHz?.let { "$it Hz" } ?: "unknown"}")
            appendLine("Nominal frames per buffer: ${snapshot.outputFramesPerBuffer ?: "unknown"}")
            appendLine("Estimated single-buffer latency: $latencyStr")
            appendLine("FEATURE_AUDIO_LOW_LATENCY: ${snapshot.hasLowLatencyAudioFeature}")
            appendLine("FEATURE_AUDIO_PRO: ${snapshot.hasProAudioFeature}")
            appendLine("Hardware AcousticEchoCanceler (AEC): ${snapshot.aecAvailable}")
            appendLine("Hardware NoiseSuppressor (NS): ${snapshot.nsAvailable}")
            appendLine("Hardware AutomaticGainControl (AGC): ${snapshot.agcAvailable}")
            if (snapshot.queriedEffectsCount != null) {
                appendLine("AudioEffect.queryEffects() count: ${snapshot.queriedEffectsCount}")
            } else if (snapshot.queriedEffectsError != null) {
                appendLine("AudioEffect.queryEffects() error: ${snapshot.queriedEffectsError}")
            }
        }
    }
}
