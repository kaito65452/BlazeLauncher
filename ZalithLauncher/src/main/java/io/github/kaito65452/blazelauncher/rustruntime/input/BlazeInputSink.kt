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

import org.lwjgl.glfw.CallbackBridge

/**
 * Runtime-switched input sink shared by ZL2's control overlay, mouse layer,
 * hotbar, gamepad mapping, and soft keyboard bar.
 *
 * Java Edition uses [JavaCallbackInputSink] (unchanged [CallbackBridge] behavior);
 * Rust Edition uses [RustInputBridge] over `BlazeNativeBridge` JNI.
 */
interface BlazeInputSink {
    val sinkName: String
    val isGrabbing: Boolean
    fun getCurrentMods(): Int

    fun sendCursorPos(x: Float, y: Float)
    fun sendCursorDelta(dx: Float, dy: Float)
    fun sendKeycode(keycode: Int, keychar: Char, scancode: Int, modifiers: Int, isDown: Boolean)
    fun sendChar(keychar: Char, modifiers: Int)
    fun sendCodepoint(codepoint: Int) {
        if (codepoint in 0..0xFFFF) {
            sendChar(codepoint.toChar(), 0)
        }
    }
    fun sendKeyPress(keyCode: Int, scancode: Int, modifiers: Int, status: Boolean) {
        sendKeycode(keyCode, '\u0000', scancode, modifiers, status)
    }
    fun sendKeyPress(keyCode: Int, modifiers: Int, status: Boolean) {
        sendKeyPress(keyCode, 0, modifiers, status)
    }
    fun sendKeyPress(keyCode: Int) {
        val mods = getCurrentMods()
        sendKeyPress(keyCode, mods, true)
        sendKeyPress(keyCode, mods, false)
    }
    fun sendMouseButton(button: Int, status: Boolean) {
        sendMouseKeycode(button, getCurrentMods(), status)
    }
    fun sendMouseKeycode(button: Int, modifiers: Int, isDown: Boolean)
    fun putMouseEvent(button: Int, isDown: Boolean) {
        sendMouseKeycode(button, getCurrentMods(), isDown)
    }
    fun putMouseEvent(button: Int)
    fun putMouseEventWithCoords(button: Int, x: Float, y: Float) {
        sendCursorPos(x, y)
        putMouseEvent(button)
    }
    fun sendScroll(xoffset: Double, yoffset: Double)
    fun sendUpdateWindowSize(width: Int, height: Int)
    fun setModifiers(keyCode: Int, isDown: Boolean)
    fun syncModifiers(alt: Boolean, capslock: Boolean, ctrl: Boolean, numlock: Boolean, shift: Boolean)
    fun resetInputState()
}

object JavaCallbackInputSink : BlazeInputSink {
    override val sinkName: String = "java_callback_bridge"

    override val isGrabbing: Boolean
        get() = CallbackBridge.isGrabbing()

    override fun getCurrentMods(): Int = CallbackBridge.getCurrentMods()

    override fun sendCursorPos(x: Float, y: Float) = CallbackBridge.sendCursorPos(x, y)

    override fun sendCursorDelta(dx: Float, dy: Float) = CallbackBridge.sendCursorDelta(dx, dy)

    override fun sendKeycode(
        keycode: Int,
        keychar: Char,
        scancode: Int,
        modifiers: Int,
        isDown: Boolean
    ) = CallbackBridge.sendKeycode(keycode, keychar, scancode, modifiers, isDown)

    override fun sendChar(keychar: Char, modifiers: Int) = CallbackBridge.sendChar(keychar, modifiers)

    override fun sendMouseKeycode(button: Int, modifiers: Int, isDown: Boolean) =
        CallbackBridge.sendMouseKeycode(button, modifiers, isDown)

    override fun putMouseEvent(button: Int) = CallbackBridge.putMouseEvent(button)

    override fun sendScroll(xoffset: Double, yoffset: Double) = CallbackBridge.sendScroll(xoffset, yoffset)

    override fun sendUpdateWindowSize(width: Int, height: Int) =
        CallbackBridge.sendUpdateWindowSize(width, height)

    override fun setModifiers(keyCode: Int, isDown: Boolean) =
        CallbackBridge.setModifiers(keyCode, isDown)

    override fun syncModifiers(
        alt: Boolean,
        capslock: Boolean,
        ctrl: Boolean,
        numlock: Boolean,
        shift: Boolean
    ) {
        CallbackBridge.holdingAlt = alt
        CallbackBridge.holdingCapslock = capslock
        CallbackBridge.holdingCtrl = ctrl
        CallbackBridge.holdingNumlock = numlock
        CallbackBridge.holdingShift = shift
    }

    override fun resetInputState() = CallbackBridge.resetInputState()
}

object BlazeInputRouter {
    @Volatile
    private var activeSink: BlazeInputSink = JavaCallbackInputSink

    @JvmStatic
    fun currentSink(): BlazeInputSink = activeSink

    @JvmStatic
    fun selectJava() {
        activeSink = JavaCallbackInputSink
    }

    @JvmStatic
    fun selectRust(sink: BlazeInputSink = RustInputBridge) {
        activeSink = sink
    }

    @JvmStatic
    fun isRustActive(): Boolean = activeSink !== JavaCallbackInputSink
}
