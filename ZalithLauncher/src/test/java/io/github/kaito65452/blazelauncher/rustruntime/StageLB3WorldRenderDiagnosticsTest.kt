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

import io.github.kaito65452.blazelauncher.rustruntime.input.RustInputDiagnostics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageLB3WorldRenderDiagnosticsTest {
    @After
    fun tearDown() {
        RustInputDiagnostics.reset()
    }

    @Test
    fun parsesAndFormatsWorldFramesWhileUiOpenAndOverlayCacheFlag() {
        val nativeJson = """
            {
              "desktop_input_enabled": true,
              "counters": {
                "raw_touch_ignored": 0,
                "world_frames_while_ui_open": 240,
                "ui_overlay_cache_active": false
              }
            }
        """.trimIndent()
        val frames = RustInputDiagnostics.parseNativeWorldFramesWhileUiOpen(nativeJson)
        val cacheActive = RustInputDiagnostics.parseNativeUiOverlayCacheActive(nativeJson)
        assertEquals(240L, frames)
        assertFalse(cacheActive)

        val formatted = RustInputDiagnostics.format(
            leakedToEngine = 0L,
            worldFramesWhileUiOpen = frames,
            uiOverlayCacheActive = cacheActive
        )
        assertTrue(formatted, formatted.contains("world_frames_while_ui_open=240"))
        assertTrue(formatted, formatted.contains("ui_overlay_cache_active=false"))
    }
}
