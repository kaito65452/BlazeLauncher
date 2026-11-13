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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityReportTest {
    @Test
    fun formatVulkanVersionDecodesPackedBits() {
        assertEquals("1.0.0", DeviceCapabilityReport.formatVulkanVersion((1 shl 22)))
        assertEquals("1.1.0", DeviceCapabilityReport.formatVulkanVersion((1 shl 22) or (1 shl 12)))
        assertEquals("1.3.0", DeviceCapabilityReport.formatVulkanVersion(DeviceCapabilityReport.VULKAN_1_3_PACKED))
        assertEquals("1.3.275", DeviceCapabilityReport.formatVulkanVersion((1 shl 22) or (3 shl 12) or 275))
    }

    @Test
    fun supportsVulkanApiChecksThreshold() {
        val vk11 = (1 shl 22) or (1 shl 12)
        val vk13 = DeviceCapabilityReport.VULKAN_1_3_PACKED
        assertFalse(DeviceCapabilityReport.supportsVulkanApi(null, 1, 3))
        assertFalse(DeviceCapabilityReport.supportsVulkanApi(vk11, 1, 3))
        assertTrue(DeviceCapabilityReport.supportsVulkanApi(vk11, 1, 1))
        assertTrue(DeviceCapabilityReport.supportsVulkanApi(vk13, 1, 3))
    }

    @Test
    fun estimateBufferLatencyMsHandlesValidAndInvalidInputs() {
        val latency = DeviceCapabilityReport.estimateBufferLatencyMs(48_000, 192)!!
        assertEquals(4.0, latency, 0.001)
        assertNull(DeviceCapabilityReport.estimateBufferLatencyMs(null, 192))
        assertNull(DeviceCapabilityReport.estimateBufferLatencyMs(0, 192))
        assertNull(DeviceCapabilityReport.estimateBufferLatencyMs(48_000, 0))
    }

    @Test
    fun formatReportIncludesEngineVulkanFindingAndFields() {
        val snapshot = CapabilitySnapshot(
            deviceModel = "TestVendor TestPhone (testdev)",
            androidVersion = "14",
            apiLevel = 34,
            supportedAbis = listOf("arm64-v8a"),
            vulkanHardwareVersionRaw = DeviceCapabilityReport.VULKAN_1_3_PACKED,
            vulkanHardwareLevel = 1,
            glEsVersion = "3.2",
            outputSampleRateHz = 48_000,
            outputFramesPerBuffer = 192,
            hasLowLatencyAudioFeature = true,
            hasProAudioFeature = false,
            aecAvailable = true,
            nsAvailable = true,
            agcAvailable = false,
            queriedEffectsCount = 3,
            queriedEffectsError = null
        )
        val report = DeviceCapabilityReport.format(snapshot)
        assertTrue(report.contains("PackageManager Vulkan version: 1.3.0"))
        assertTrue(report.contains("Vulkan 1.3+ advertised: true"))
        assertTrue(report.contains("arm64-v8a supported: true"))
        assertTrue(report.contains("4.00 ms/buffer"))
        assertTrue(report.contains("Hardware AcousticEchoCanceler (AEC): true"))
        assertTrue(report.contains("Hardware AutomaticGainControl (AGC): false"))
        assertTrue(report.contains("Not Vulkan 1.3 required."))
    }
}
