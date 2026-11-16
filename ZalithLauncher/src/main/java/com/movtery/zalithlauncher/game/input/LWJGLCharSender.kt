/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
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

package com.movtery.zalithlauncher.game.input

import android.view.KeyEvent
import android.view.MotionEvent
import com.movtery.inputmap.keycodes.LwjglGlfwKeycode
import io.github.kaito65452.blazelauncher.rustruntime.input.BlazeInputRouter

object LWJGLCharSender : CharacterSenderStrategy {
    override fun sendBackspace() {
        val sink = BlazeInputRouter.currentSink()
        sink.sendKeycode(LwjglGlfwKeycode.GLFW_KEY_BACKSPACE.toInt(), '\u0008', 0, 0, true)
        sink.sendKeycode(LwjglGlfwKeycode.GLFW_KEY_BACKSPACE.toInt(), '\u0008', 0, 0, false)
    }

    override fun sendEnter() {
        BlazeInputRouter.currentSink().sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ENTER.toInt())
    }

    override fun sendTab() {
        BlazeInputRouter.currentSink().sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_TAB.toInt())
    }

    override fun sendLeft() {
        BlazeInputRouter.currentSink().sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_LEFT.toInt())
    }

    override fun sendRight() {
        BlazeInputRouter.currentSink().sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_RIGHT.toInt())
    }

    override fun sendUp() {
        BlazeInputRouter.currentSink().sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_UP.toInt())
    }

    override fun sendDown() {
        BlazeInputRouter.currentSink().sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_DOWN.toInt())
    }

    override fun sendChar(character: Char) {
        // 按键与字符成对发送：lwjglx 系 LWJGL2 兼容层将字母等 keydown 暂存，
        // 待 charMods 事件合并后才投给游戏，只发字符无法驱动按键绑定
        val sink = BlazeInputRouter.currentSink()
        val index = EfficientAndroidLWJGLKeycode.getIndexByKey(
            EfficientAndroidLWJGLKeycode.getAndroidKeycode(character)
        )
        if (index < 0) {
            sink.sendChar(character, 0)
            return
        }
        val keycode = EfficientAndroidLWJGLKeycode.getValueByIndex(index).toInt()
        sink.sendKeycode(keycode, character, 0, sink.getCurrentMods(), true)
        sink.sendKeycode(keycode, character, 0, sink.getCurrentMods(), false)
    }

    override fun sendOther(key: KeyEvent) {
        EfficientAndroidLWJGLKeycode.getIndexByKey(key.keyCode)
            .takeIf { it >= 0 }
            ?.let { index ->
                EfficientAndroidLWJGLKeycode.execKey(key, index)
            }
    }

    override fun sendCopy() {
        // Ignore
    }

    override fun sendCut() {
        // Ignore
    }

    override fun sendPaste() {
        // Ignore
    }

    override fun sendSelectAll() {
        // Ignore
    }

    override fun sendModifierShift(press: Boolean) {
        val sink = BlazeInputRouter.currentSink()
        val keycode = LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT.toInt()
        sink.sendKeyPress(keycode, sink.getCurrentMods(), press)
        sink.setModifiers(keycode, press)
    }

    override fun sendModifierCtrl(press: Boolean) {
        val sink = BlazeInputRouter.currentSink()
        val keycode = LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL.toInt()
        sink.sendKeyPress(keycode, sink.getCurrentMods(), press)
        sink.setModifiers(keycode, press)
    }

    /**
     * 获取 LWJGL 鼠标点击事件
     */
    fun getMouseButton(button: Int): Short? {
        return when (button) {
            MotionEvent.BUTTON_PRIMARY -> LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT
            MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_STYLUS_SECONDARY -> LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT
            MotionEvent.BUTTON_TERTIARY -> LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE
            else -> null
        }
    }
}