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
import io.github.kaito65452.blazelauncher.rustruntime.input.RustTouchRouting
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageLB2TouchRoutingTest {
    private class FakeTransport : RustNativeInputTransport {
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

    @Test
    fun desktopModeRoutesToOverlayAndNeverNative() {
        assertEquals(RustTouchRouting.Route.ZL2_OVERLAY, RustTouchRouting.route(true, true))
        assertEquals(RustTouchRouting.Route.ENGINE_NATIVE, RustTouchRouting.route(false, true))
        assertEquals(RustTouchRouting.Route.ENGINE_NATIVE, RustTouchRouting.route(true, false))
        assertTrue(RustTouchRouting.consumeAtActivity(true))
        assertTrue(!RustTouchRouting.consumeAtActivity(false))
    }

    @Test
    fun viewportDifferentFromSurfaceStillMapsToSameNormalizedPoint() {
        // Overlay area 1463x694 inside a 1520x720 window; offset (28,13) is the inset origin.
        val centerOfArea = RustTouchGeometry.normalize(
            x = 1463f / 2, y = 694f / 2, areaWidth = 1463, areaHeight = 694,
            offsetX = 28, offsetY = 13, windowWidth = 1520, windowHeight = 720
        )
        assertEquals((1463f / 2 + 28) / 1520f, centerOfArea.x, 1e-6f)
        assertEquals((694f / 2 + 13) / 720f, centerOfArea.y, 1e-6f)
        // Engine multiplies by its 1520x720 surface -> window pixel coordinates.
        assertEquals(1463f / 2 + 28, centerOfArea.x * 1520f, 1e-3f)
        assertEquals(694f / 2 + 13, centerOfArea.y * 720f, 1e-3f)
    }

    @Test
    fun withoutWindowGeometryFallsBackToViewportAndClamps() {
        val n = RustTouchGeometry.normalize(1463f, 694f, 1463, 694)
        assertEquals(1f, n.x, 1e-6f)
        assertEquals(1f, n.y, 1e-6f)
        val out = RustTouchGeometry.normalize(-50f, 9999f, 1463, 694)
        assertEquals(0f, out.x, 0f)
        assertEquals(1f, out.y, 0f)
        val nan = RustTouchGeometry.normalize(Float.NaN, Float.NaN, 100, 100)
        assertEquals(0.5f, nan.x, 0f)
    }

    @Test
    fun bridgeSendCursorPosUsesOverlayGeometryAndRecordsLastTouch() {
        RustInputDiagnostics.reset()
        val transport = FakeTransport()
        val bridge = RustInputBridgeImpl(transport)
        bridge.sendUpdateWindowSize(1463, 694)
        bridge.updateOverlayGeometry(28, 13, 1520, 720)
        bridge.sendCursorPos(100f, 200f)
        assertEquals((100f + 28) / 1520f, transport.positions.single().first, 1e-6f)
        assertEquals((200f + 13) / 720f, transport.positions.single().second, 1e-6f)
        assertEquals(100f, RustInputDiagnostics.lastTouchRawX, 0f)
        assertEquals(transport.positions.single().first, RustInputDiagnostics.lastMappedX, 0f)
        assertTrue(bridge.formatCountersSummary().contains("window=1520x720"))
    }

    @Test
    fun diagnosticsFormatContainsAllRequiredFields() {
        RustInputDiagnostics.reset()
        RustInputDiagnostics.touchSeenByOverlay.incrementAndGet()
        RustInputDiagnostics.overlayError = "boom"
        val line = RustInputDiagnostics.format(0L)
        listOf(
            "touch_seen_by_activity=", "touch_routed_to_zl2=", "touch_leaked_to_engine=0",
            "overlay_attached=", "overlay_visible=", "overlay_size=", "overlay_zorder_above_surface=",
            "overlay_composed_count=", "last_touch_xy_raw=", "last_touch_xy_mapped=", "overlay_error=boom"
        ).forEach { assertTrue("missing $it in $line", line.contains(it)) }
        RustInputDiagnostics.reset()
    }

    @Test
    fun parsesNativeRawTouchCounter() {
        val json = """{"desktop_input_enabled":true,"counters":{"raw_touch_ignored":125,"raw_touch_warnings":2}}"""
        assertEquals(125L, RustInputDiagnostics.parseNativeRawTouch(json))
        assertEquals(0L, RustInputDiagnostics.parseNativeRawTouch("unavailable"))
    }

    @Test
    fun touchDiagnosticsLandInInputBridgeFile() {
        val dir = Files.createTempDirectory("lb2").toFile()
        try {
            RustDiagnosticsCollector.recordInputBridgeStatsToDir(dir, "path=x", "{}", "touch_seen_by_activity=0")
            assertTrue(RustDiagnosticsCollector.readInputBridgeStats(dir).contains("touch_overlay: touch_seen_by_activity=0"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
