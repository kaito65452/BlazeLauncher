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

package io.github.kaito65452.blazelauncher.rustruntime.input

import android.os.Handler
import android.os.Looper
import com.movtery.inputmap.keycodes.LwjglGlfwKeycode
import com.movtery.zalithlauncher.bridge.CURSOR_DISABLED
import com.movtery.zalithlauncher.bridge.CURSOR_ENABLED
import com.movtery.zalithlauncher.bridge.ZLBridgeStates
import dev.horizon.android.bridge.BlazeNativeBridge
import java.util.concurrent.atomic.AtomicLong

interface RustNativeInputTransport {
    fun injectKey(keyCode: Int, unicodeChar: Int, mods: Int, pressed: Boolean)
    fun injectMouseButton(button: Int, mods: Int, pressed: Boolean)
    fun injectCursorPos(normX: Float, normY: Float)
    fun injectCursorDelta(dx: Float, dy: Float)
    fun injectScroll(dx: Float, dy: Float)
    fun injectChar(codepoint: Int)
    fun injectAndroidBack()
    fun resetInputState()
    fun setDesktopInputMode(enabled: Boolean)
}

object BlazeNativeInputTransport : RustNativeInputTransport {
    override fun injectKey(keyCode: Int, unicodeChar: Int, mods: Int, pressed: Boolean) =
        BlazeNativeBridge.injectKey(keyCode, unicodeChar, mods, pressed)

    override fun injectMouseButton(button: Int, mods: Int, pressed: Boolean) =
        BlazeNativeBridge.injectMouseButton(button, mods, pressed)

    override fun injectCursorPos(normX: Float, normY: Float) =
        BlazeNativeBridge.injectCursorPos(normX, normY)

    override fun injectCursorDelta(dx: Float, dy: Float) =
        BlazeNativeBridge.injectCursorDelta(dx, dy)

    override fun injectScroll(dx: Float, dy: Float) =
        BlazeNativeBridge.injectScroll(dx, dy)

    override fun injectChar(codepoint: Int) =
        BlazeNativeBridge.injectChar(codepoint)

    override fun injectAndroidBack() =
        BlazeNativeBridge.injectAndroidBack()

    override fun resetInputState() =
        BlazeNativeBridge.resetInputState()

    override fun setDesktopInputMode(enabled: Boolean) =
        BlazeNativeBridge.setDesktopInputMode(enabled)
}

/**
 * [BlazeInputSink] implementation for the embedded Rust engine.
 * Never touches `org.lwjgl.glfw.CallbackBridge` so Pojav native libraries are never loaded in `:rust`.
 */
class RustInputBridgeImpl(
    private val transport: RustNativeInputTransport = BlazeNativeInputTransport,
    private val delayedPoster: ((Long, Runnable) -> Unit)? = null
) : BlazeInputSink {
    override val sinkName: String = "rust_jni_bridge"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var grabbingState: Boolean = false

    @Volatile
    var desktopInputEnabled: Boolean = true
        private set

    @Volatile
    var viewportWidth: Int = 1280
        private set

    @Volatile
    var viewportHeight: Int = 720
        private set

    @Volatile
    var holdingShift: Boolean = false
        private set

    @Volatile
    var holdingCtrl: Boolean = false
        private set

    @Volatile
    var holdingAlt: Boolean = false
        private set

    @Volatile
    var holdingCapslock: Boolean = false
        private set

    @Volatile
    var holdingNumlock: Boolean = false
        private set

    val keyCount = AtomicLong(0)
    val mouseButtonCount = AtomicLong(0)
    val cursorPosCount = AtomicLong(0)
    val cursorDeltaCount = AtomicLong(0)
    val scrollCount = AtomicLong(0)
    val charCount = AtomicLong(0)

    override val isGrabbing: Boolean
        get() = grabbingState

    fun configureDesktopInputMode(enabled: Boolean) {
        desktopInputEnabled = enabled
        transport.setDesktopInputMode(enabled)
    }

    fun onCursorGrabChanged(grabbing: Boolean) {
        grabbingState = grabbing
        ZLBridgeStates.changeCursorMode(if (grabbing) CURSOR_DISABLED else CURSOR_ENABLED)
    }

    fun sendAndroidBack() {
        transport.injectAndroidBack()
    }

    override fun getCurrentMods(): Int {
        var mods = 0
        if (holdingAlt) mods = mods or LwjglGlfwKeycode.GLFW_MOD_ALT
        if (holdingCapslock) mods = mods or LwjglGlfwKeycode.GLFW_MOD_CAPS_LOCK
        if (holdingCtrl) mods = mods or LwjglGlfwKeycode.GLFW_MOD_CONTROL
        if (holdingNumlock) mods = mods or LwjglGlfwKeycode.GLFW_MOD_NUM_LOCK
        if (holdingShift) mods = mods or LwjglGlfwKeycode.GLFW_MOD_SHIFT
        return mods
    }

    @Volatile
    private var geometrySnapshot: RustTouchGeometry.SurfaceGeometry =
        RustTouchGeometry.SurfaceGeometry()

    /** Single source of truth for overlay-to-surface coordinate geometry. */
    fun updateOverlayGeometry(
        offsetX: Int,
        offsetY: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
        overlayWidth: Int = viewportWidth,
        overlayHeight: Int = viewportHeight,
        bufferWidth: Int = surfaceWidth,
        bufferHeight: Int = surfaceHeight
    ) {
        val next = RustTouchGeometry.SurfaceGeometry(
            overlayOffsetX = offsetX,
            overlayOffsetY = offsetY,
            overlayWidth = overlayWidth.coerceAtLeast(1),
            overlayHeight = overlayHeight.coerceAtLeast(1),
            surfaceWidth = surfaceWidth.coerceAtLeast(0),
            surfaceHeight = surfaceHeight.coerceAtLeast(0),
            bufferWidth = bufferWidth.coerceAtLeast(0),
            bufferHeight = bufferHeight.coerceAtLeast(0)
        )
        geometrySnapshot = next
        RustInputDiagnostics.overlayOffsetX = next.overlayOffsetX
        RustInputDiagnostics.overlayOffsetY = next.overlayOffsetY
        RustInputDiagnostics.surfaceWidth = next.surfaceWidth
        RustInputDiagnostics.surfaceHeight = next.surfaceHeight
        RustInputDiagnostics.bufferWidth = next.bufferWidth
        RustInputDiagnostics.bufferHeight = next.bufferHeight
    }

    override fun sendCursorPos(x: Float, y: Float) {
        val current = geometrySnapshot
        val effective = if (current.overlayWidth == viewportWidth && current.overlayHeight == viewportHeight) {
            current
        } else {
            current.copy(
                overlayWidth = viewportWidth.coerceAtLeast(1),
                overlayHeight = viewportHeight.coerceAtLeast(1)
            )
        }
        val mapped = RustTouchGeometry.map(x, y, effective)
        RustInputDiagnostics.recordMappedCursor(x, y, mapped, effective)
        cursorPosCount.incrementAndGet()
        transport.injectCursorPos(mapped.normX, mapped.normY)
    }

    override fun sendCursorDelta(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        cursorDeltaCount.incrementAndGet()
        transport.injectCursorDelta(dx, dy)
    }

    override fun sendKeycode(
        keycode: Int,
        keychar: Char,
        scancode: Int,
        modifiers: Int,
        isDown: Boolean
    ) {
        if (keycode <= LwjglGlfwKeycode.GLFW_KEY_UNKNOWN.toInt() || keycode > LwjglGlfwKeycode.GLFW_KEY_LAST.toInt()) {
            return
        }
        val effectiveChar = if (isDown && keychar == '\u0000') {
            GlfwKeycodeMapping.resolvePrintableFallbackChar(keycode, modifiers)
        } else {
            keychar
        }
        keyCount.incrementAndGet()
        transport.injectKey(keycode, effectiveChar.code, modifiers, isDown)
    }

    override fun sendChar(keychar: Char, modifiers: Int) {
        if (Character.isISOControl(keychar)) return
        charCount.incrementAndGet()
        transport.injectChar(keychar.code)
    }

    override fun sendMouseKeycode(button: Int, modifiers: Int, isDown: Boolean) {
        if (GlfwKeycodeMapping.toWinitMouseButtonName(button) == null) return
        mouseButtonCount.incrementAndGet()
        transport.injectMouseButton(button, modifiers, isDown)
    }

    override fun putMouseEvent(button: Int) {
        putMouseEvent(button, true)
        val releaseTask = Runnable { putMouseEvent(button, false) }
        val poster = delayedPoster
        if (poster != null) {
            poster(33L, releaseTask)
        } else {
            mainHandler.postDelayed(releaseTask, 33L)
        }
    }

    override fun sendScroll(xoffset: Double, yoffset: Double) {
        if (!xoffset.isFinite() || !yoffset.isFinite()) return
        scrollCount.incrementAndGet()
        transport.injectScroll(xoffset.toFloat(), yoffset.toFloat())
    }

    override fun sendUpdateWindowSize(width: Int, height: Int) {
        if (width > 0) viewportWidth = width
        if (height > 0) viewportHeight = height
    }

    override fun setModifiers(keyCode: Int, isDown: Boolean) {
        when (keyCode) {
            LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT.toInt(),
            LwjglGlfwKeycode.GLFW_KEY_RIGHT_SHIFT.toInt() -> holdingShift = isDown

            LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL.toInt(),
            LwjglGlfwKeycode.GLFW_KEY_RIGHT_CONTROL.toInt() -> holdingCtrl = isDown

            LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT.toInt(),
            LwjglGlfwKeycode.GLFW_KEY_RIGHT_ALT.toInt() -> holdingAlt = isDown

            LwjglGlfwKeycode.GLFW_KEY_CAPS_LOCK.toInt() -> holdingCapslock = isDown
            LwjglGlfwKeycode.GLFW_KEY_NUM_LOCK.toInt() -> holdingNumlock = isDown
        }
    }

    override fun syncModifiers(
        alt: Boolean,
        capslock: Boolean,
        ctrl: Boolean,
        numlock: Boolean,
        shift: Boolean
    ) {
        holdingAlt = alt
        holdingCapslock = capslock
        holdingCtrl = ctrl
        holdingNumlock = numlock
        holdingShift = shift
    }

    override fun resetInputState() {
        holdingAlt = false
        holdingCapslock = false
        holdingCtrl = false
        holdingNumlock = false
        holdingShift = false
        transport.resetInputState()
    }

    fun formatCountersSummary(): String {
        val mode = if (desktopInputEnabled) "zl2_desktop_jni" else "engine_touch_fallback"
        val g = geometrySnapshot
        return "path=$mode viewport=${viewportWidth}x${viewportHeight} window=${g.surfaceWidth}x${g.surfaceHeight} " +
            "grab=$grabbingState " +
            "keys=${keyCount.get()} mouseBtns=${mouseButtonCount.get()} pos=${cursorPosCount.get()} " +
            "delta=${cursorDeltaCount.get()} scroll=${scrollCount.get()} chars=${charCount.get()}"
    }
}

val RustInputBridge: RustInputBridgeImpl = RustInputBridgeImpl()
