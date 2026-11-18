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
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package io.github.kaito65452.blazelauncher.rustruntime

import io.github.kaito65452.blazelauncher.rustruntime.input.RustInputBridgeImpl
import io.github.kaito65452.blazelauncher.rustruntime.input.RustInputDiagnostics
import io.github.kaito65452.blazelauncher.rustruntime.input.RustNativeInputTransport
import io.github.kaito65452.blazelauncher.rustruntime.input.RustTouchGeometry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageLB3CursorMappingTest {
    private class RecordingCursorTransport : RustNativeInputTransport {
        val positions = mutableListOf<Pair<Float, Float>>()
        override fun injectKey(keyCode: Int, unicodeChar: Int, mods: Int, pressed: Boolean) = Unit
        override fun injectMouseButton(button: Int, mods: Int, pressed: Boolean) = Unit
        override fun injectCursorPos(normX: Float, normY: Float) {
            positions += normX to normY
        }
        override fun injectCursorDelta(dx: Float, dy: Float) = Unit
        override fun injectScroll(dx: Float, dy: Float) = Unit
        override fun injectChar(codepoint: Int) = Unit
        override fun injectAndroidBack() = Unit
        override fun resetInputState() = Unit
        override fun setDesktopInputMode(enabled: Boolean) = Unit
    }

    @After
    fun tearDown() {
        RustInputDiagnostics.reset()
    }

    @Test
    fun reportSample_1124_8_by_370_4_onOverlay1520x694ToSurface1520x720() {
        val geometry = RustTouchGeometry.SurfaceGeometry(
            overlayOffsetX = 0,
            overlayOffsetY = 0,
            overlayWidth = 1520,
            overlayHeight = 694,
            surfaceWidth = 1520,
            surfaceHeight = 720,
            bufferWidth = 1520,
            bufferHeight = 720
        )
        val mapped = RustTouchGeometry.map(1124.8f, 370.4f, geometry)
        assertEquals(1124.8f / 1520f, mapped.normX, 1e-6f)
        assertEquals(370.4f / 720f, mapped.normY, 1e-6f)
        assertEquals(0.74f, mapped.normX, 1e-4f)
        assertEquals(0.5144f, mapped.normY, 1e-4f)
        assertEquals(1124.8f, mapped.enginePxX, 1e-2f)
        assertEquals(370.4f, mapped.enginePxY, 1e-2f)
    }

    @Test
    fun edgeCases_cornersCenterLeftCutoutAndReverseLandscape() {
        // Landscape with 57px left cutout and 26px bottom nav bar excluded by engine (surface 1520x694).
        val leftCutout = RustTouchGeometry.SurfaceGeometry(
            overlayOffsetX = 57,
            overlayOffsetY = 0,
            overlayWidth = 1463,
            overlayHeight = 694,
            surfaceWidth = 1520,
            surfaceHeight = 694,
            bufferWidth = 1520,
            bufferHeight = 694
        )
        val tl = RustTouchGeometry.map(0f, 0f, leftCutout)
        assertEquals(57f / 1520f, tl.normX, 1e-6f)
        assertEquals(0f, tl.normY, 0f)
        assertEquals(57f, tl.enginePxX, 1e-3f)

        val br = RustTouchGeometry.map(1463f, 694f, leftCutout)
        assertEquals(1f, br.normX, 1e-6f)
        assertEquals(1f, br.normY, 1e-6f)
        assertEquals(1520f, br.enginePxX, 1e-3f)
        assertEquals(694f, br.enginePxY, 1e-3f)

        val center = RustTouchGeometry.map(760f - 57f, 347f, leftCutout)
        assertEquals(0.5f, center.normX, 1e-6f)
        assertEquals(0.5f, center.normY, 1e-6f)
        assertEquals(760f, center.enginePxX, 1e-3f)
        assertEquals(347f, center.enginePxY, 1e-3f)

        // Reverse landscape: 57px cutout on right, overlay starts at (0,0) with width 1463.
        val reverseCutout = RustTouchGeometry.SurfaceGeometry(
            overlayOffsetX = 0,
            overlayOffsetY = 0,
            overlayWidth = 1463,
            overlayHeight = 694,
            surfaceWidth = 1520,
            surfaceHeight = 694,
            bufferWidth = 1520,
            bufferHeight = 694
        )
        val revTl = RustTouchGeometry.map(0f, 0f, reverseCutout)
        assertEquals(0f, revTl.normX, 0f)
        assertEquals(0f, revTl.normY, 0f)

        val revBr = RustTouchGeometry.map(1463f, 694f, reverseCutout)
        assertEquals(1463f / 1520f, revBr.normX, 1e-6f)
        assertEquals(1f, revBr.normY, 1e-6f)

        val revCenter = RustTouchGeometry.map(760f, 347f, reverseCutout)
        assertEquals(0.5f, revCenter.normX, 1e-6f)
        assertEquals(0.5f, revCenter.normY, 1e-6f)
    }

    @Test
    fun scaledRenderBuffer_mapsEnginePxToBufferDimensions() {
        val scaled = RustTouchGeometry.SurfaceGeometry(
            overlayOffsetX = 0,
            overlayOffsetY = 0,
            overlayWidth = 1463,
            overlayHeight = 694,
            surfaceWidth = 1463,
            surfaceHeight = 694,
            bufferWidth = 1097,
            bufferHeight = 521
        )
        val center = RustTouchGeometry.map(1463f / 2f, 694f / 2f, scaled)
        assertEquals(0.5f, center.normX, 1e-6f)
        assertEquals(0.5f, center.normY, 1e-6f)
        assertEquals(1097f / 2f, center.enginePxX, 1e-2f)
        assertEquals(521f / 2f, center.enginePxY, 1e-2f)
    }

    @Test
    fun bridgeDiagnosticsIncludeOffsetSurfaceSizeMappedPxAndRawCheck() {
        RustInputDiagnostics.reset()
        val transport = RecordingCursorTransport()
        val bridge = RustInputBridgeImpl(transport)
        bridge.sendUpdateWindowSize(1463, 694)
        bridge.updateOverlayGeometry(
            offsetX = 57,
            offsetY = 0,
            surfaceWidth = 1520,
            surfaceHeight = 694,
            overlayWidth = 1463,
            overlayHeight = 694,
            bufferWidth = 1520,
            bufferHeight = 694
        )
        bridge.sendCursorPos(703f, 347f)

        assertEquals(1, transport.positions.size)
        assertEquals(0.5f, transport.positions[0].first, 1e-6f)
        assertEquals(0.5f, transport.positions[0].second, 1e-6f)

        val line = RustInputDiagnostics.format(0L)
        assertTrue(line, line.contains("overlay_offset=57,0"))
        assertTrue(line, line.contains("surface_size=1520x694"))
        assertTrue(line, line.contains("mapped_px=760.0,347.0"))
        assertTrue(line, line.contains("raw_vs_mapped_check=ok("))
    }
}
