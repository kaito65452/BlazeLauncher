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

import com.movtery.inputmap.keycodes.LwjglGlfwKeycode
import com.movtery.zalithlauncher.bridge.CURSOR_DISABLED
import com.movtery.zalithlauncher.bridge.CURSOR_ENABLED
import com.movtery.zalithlauncher.bridge.ZLBridgeStates
import io.github.kaito65452.blazelauncher.rustruntime.input.BlazeInputRouter
import io.github.kaito65452.blazelauncher.rustruntime.input.GlfwKeycodeMapping
import io.github.kaito65452.blazelauncher.rustruntime.input.JavaCallbackInputSink
import io.github.kaito65452.blazelauncher.rustruntime.input.RustInputBridgeImpl
import io.github.kaito65452.blazelauncher.rustruntime.input.RustNativeInputTransport
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StageLBInputBridgeTest {
    private sealed interface RecordedCall {
        data class Key(val keyCode: Int, val unicodeChar: Int, val mods: Int, val pressed: Boolean) : RecordedCall
        data class MouseButton(val button: Int, val mods: Int, val pressed: Boolean) : RecordedCall
        data class CursorPos(val normX: Float, val normY: Float) : RecordedCall
        data class CursorDelta(val dx: Float, val dy: Float) : RecordedCall
        data class Scroll(val dx: Float, val dy: Float) : RecordedCall
        data class CharInput(val codepoint: Int) : RecordedCall
        data object AndroidBack : RecordedCall
        data object Reset : RecordedCall
        data class DesktopMode(val enabled: Boolean) : RecordedCall
    }

    private class RecordingTransport : RustNativeInputTransport {
        val calls = mutableListOf<RecordedCall>()

        override fun injectKey(keyCode: Int, unicodeChar: Int, mods: Int, pressed: Boolean) {
            calls += RecordedCall.Key(keyCode, unicodeChar, mods, pressed)
        }

        override fun injectMouseButton(button: Int, mods: Int, pressed: Boolean) {
            calls += RecordedCall.MouseButton(button, mods, pressed)
        }

        override fun injectCursorPos(normX: Float, normY: Float) {
            calls += RecordedCall.CursorPos(normX, normY)
        }

        override fun injectCursorDelta(dx: Float, dy: Float) {
            calls += RecordedCall.CursorDelta(dx, dy)
        }

        override fun injectScroll(dx: Float, dy: Float) {
            calls += RecordedCall.Scroll(dx, dy)
        }

        override fun injectChar(codepoint: Int) {
            calls += RecordedCall.CharInput(codepoint)
        }

        override fun injectAndroidBack() {
            calls += RecordedCall.AndroidBack
        }

        override fun resetInputState() {
            calls += RecordedCall.Reset
        }

        override fun setDesktopInputMode(enabled: Boolean) {
            calls += RecordedCall.DesktopMode(enabled)
        }
    }

    @After
    fun resetRouterAndCursorMode() {
        BlazeInputRouter.selectJava()
        ZLBridgeStates.changeCursorMode(CURSOR_ENABLED)
    }

    @Test
    fun glfwMapping_coversAllMinecraftControlKeysAndMouseButtons() {
        val essentialKeys = listOf(
            LwjglGlfwKeycode.GLFW_KEY_W,
            LwjglGlfwKeycode.GLFW_KEY_A,
            LwjglGlfwKeycode.GLFW_KEY_S,
            LwjglGlfwKeycode.GLFW_KEY_D,
            LwjglGlfwKeycode.GLFW_KEY_SPACE,
            LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT,
            LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL,
            LwjglGlfwKeycode.GLFW_KEY_E,
            LwjglGlfwKeycode.GLFW_KEY_Q,
            LwjglGlfwKeycode.GLFW_KEY_F,
            LwjglGlfwKeycode.GLFW_KEY_T,
            LwjglGlfwKeycode.GLFW_KEY_ESCAPE,
            LwjglGlfwKeycode.GLFW_KEY_ENTER,
            LwjglGlfwKeycode.GLFW_KEY_TAB,
            LwjglGlfwKeycode.GLFW_KEY_BACKSPACE,
            LwjglGlfwKeycode.GLFW_KEY_1,
            LwjglGlfwKeycode.GLFW_KEY_9,
            LwjglGlfwKeycode.GLFW_KEY_F1,
            LwjglGlfwKeycode.GLFW_KEY_F3,
            LwjglGlfwKeycode.GLFW_KEY_F5,
            LwjglGlfwKeycode.GLFW_KEY_F11
        )
        essentialKeys.forEach { key ->
            assertNotNull("Missing mapping for GLFW keycode $key", GlfwKeycodeMapping.toWinitKeyName(key.toInt()))
        }

        listOf(
            LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT,
            LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT,
            LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE,
            LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_4,
            LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_5
        ).forEach { button ->
            assertNotNull("Missing mapping for mouse button $button", GlfwKeycodeMapping.toWinitMouseButtonName(button.toInt()))
        }
        assertNull(GlfwKeycodeMapping.toWinitMouseButtonName(99))

        assertEquals('w', GlfwKeycodeMapping.resolvePrintableFallbackChar(LwjglGlfwKeycode.GLFW_KEY_W.toInt(), 0))
        assertEquals(
            'W',
            GlfwKeycodeMapping.resolvePrintableFallbackChar(
                LwjglGlfwKeycode.GLFW_KEY_W.toInt(),
                LwjglGlfwKeycode.GLFW_MOD_SHIFT
            )
        )
        assertEquals(
            '\u0000',
            GlfwKeycodeMapping.resolvePrintableFallbackChar(
                LwjglGlfwKeycode.GLFW_KEY_C.toInt(),
                LwjglGlfwKeycode.GLFW_MOD_CONTROL
            )
        )
    }

    @Test
    fun rustInputBridge_normalizesCursorModifiersScrollAndGrabState() {
        val transport = RecordingTransport()
        val delayedTasks = mutableListOf<Runnable>()
        val bridge = RustInputBridgeImpl(transport) { _, task -> delayedTasks += task }

        bridge.sendUpdateWindowSize(2000, 1000)
        bridge.sendCursorPos(1000f, 250f)
        bridge.sendCursorPos(-50f, 5000f)
        bridge.sendCursorPos(Float.NaN, 100f)

        assertEquals(RecordedCall.CursorPos(0.5f, 0.25f), transport.calls[0])
        assertEquals(RecordedCall.CursorPos(0f, 1f), transport.calls[1])
        assertEquals(RecordedCall.CursorPos(0.5f, 0.1f), transport.calls[2])

        bridge.sendCursorDelta(Float.NaN, 1f)
        bridge.sendCursorDelta(12.5f, -4.25f)
        assertEquals(RecordedCall.CursorDelta(12.5f, -4.25f), transport.calls[3])

        bridge.setModifiers(LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT.toInt(), true)
        bridge.setModifiers(LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL.toInt(), true)
        val expectedMods = LwjglGlfwKeycode.GLFW_MOD_SHIFT or LwjglGlfwKeycode.GLFW_MOD_CONTROL
        assertEquals(expectedMods, bridge.getCurrentMods())

        bridge.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_W.toInt())
        assertEquals(
            RecordedCall.Key(LwjglGlfwKeycode.GLFW_KEY_W.toInt(), '\u0000'.code, expectedMods, true),
            transport.calls[4]
        )
        assertEquals(
            RecordedCall.Key(LwjglGlfwKeycode.GLFW_KEY_W.toInt(), '\u0000'.code, expectedMods, false),
            transport.calls[5]
        )

        bridge.setModifiers(LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL.toInt(), false)
        val shiftOnly = LwjglGlfwKeycode.GLFW_MOD_SHIFT
        bridge.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_W.toInt())
        assertEquals(
            RecordedCall.Key(LwjglGlfwKeycode.GLFW_KEY_W.toInt(), 'W'.code, shiftOnly, true),
            transport.calls[6]
        )
        assertEquals(
            RecordedCall.Key(LwjglGlfwKeycode.GLFW_KEY_W.toInt(), '\u0000'.code, shiftOnly, false),
            transport.calls[7]
        )

        bridge.putMouseEvent(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt())
        assertEquals(1, delayedTasks.size)
        assertEquals(
            RecordedCall.MouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt(), shiftOnly, true),
            transport.calls[8]
        )
        delayedTasks.removeAt(0).run()
        assertEquals(
            RecordedCall.MouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt(), shiftOnly, false),
            transport.calls[9]
        )

        bridge.sendScroll(0.0, 2.0)
        assertEquals(RecordedCall.Scroll(0f, 2f), transport.calls[10])

        bridge.onCursorGrabChanged(true)
        assertTrue(bridge.isGrabbing)
        assertEquals(CURSOR_DISABLED, ZLBridgeStates.cursorMode.value)
        bridge.onCursorGrabChanged(false)
        assertFalse(bridge.isGrabbing)
        assertEquals(CURSOR_ENABLED, ZLBridgeStates.cursorMode.value)

        bridge.resetInputState()
        assertEquals(0, bridge.getCurrentMods())
        assertEquals(RecordedCall.Reset, transport.calls.last())

        val summary = bridge.formatCountersSummary()
        assertTrue(summary.contains("viewport=2000x1000"))
        assertTrue(summary.contains("keys=4"))
        assertTrue(summary.contains("mouseBtns=2"))
        assertTrue(summary.contains("pos=3"))
        assertTrue(summary.contains("delta=1"))
        assertTrue(summary.contains("scroll=1"))
    }

    @Test
    fun blazeInputRouter_defaultsToJavaAndRestoresCleanly() {
        assertSame(JavaCallbackInputSink, BlazeInputRouter.currentSink())
        assertFalse(BlazeInputRouter.isRustActive())

        val fakeBridge = RustInputBridgeImpl(RecordingTransport())
        BlazeInputRouter.selectRust(fakeBridge)
        assertSame(fakeBridge, BlazeInputRouter.currentSink())
        assertTrue(BlazeInputRouter.isRustActive())

        BlazeInputRouter.selectJava()
        assertSame(JavaCallbackInputSink, BlazeInputRouter.currentSink())
        assertFalse(BlazeInputRouter.isRustActive())
    }

    @Test
    fun settingsAndDiagnostics_includeFallbackToggleAndInputBridgeSection() {
        val tempDir: File = Files.createTempDirectory("stage_lb_test").toFile()
        try {
            val payload = BlazeEngineSettings.fromValues(
                renderScalePercent = 75,
                touchLookSensitivityPercent = 140,
                showControls = false,
                useEngineTouchFallback = true
            )
            assertTrue(BlazeEngineSettings.writeToDir(tempDir, payload))
            val parsed = BlazeEngineSettings.fromJsonOrNull(
                File(tempDir, BlazeEngineSettings.SETTINGS_FILE_NAME).readText()
            )
            assertNotNull(parsed)
            assertTrue(parsed!!.useEngineTouchFallback)
            assertFalse(parsed.showControls)

            val statusDir = File(tempDir, "status")
            RustDiagnosticsCollector.recordInputBridgeStatsToDir(
                statusDir = statusDir,
                bridgeSummary = "path=zl2_desktop_jni viewport=1920x1080 grab=true keys=5",
                nativeDiagnostics = "desktop_input=true queued=0 consumed=12"
            )
            val report = RustDiagnosticsCollector.collectRustDiagnosticsFromDirs(
                filesDir = tempDir,
                statusDir = statusDir,
                logcatProvider = { "" },
                useEngineTouchFallback = false
            )
            assertTrue(report.contains("=== Input Bridge (Stage L-B) ==="))
            assertTrue(report.contains("input_mode: zl2_desktop_jni"))
            assertTrue(report.contains("viewport=1920x1080"))
            assertTrue(report.contains("consumed=12"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
