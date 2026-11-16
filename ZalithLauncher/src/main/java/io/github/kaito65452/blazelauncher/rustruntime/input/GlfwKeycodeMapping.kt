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

import com.movtery.inputmap.keycodes.LwjglGlfwKeycode

/**
 * Pure-Kotlin GLFW <-> winit `KeyCode` / `MouseButton` mapping helpers matching
 * `horizon-client-render/src/android_runtime.rs`.
 */
object GlfwKeycodeMapping {
    private val keycodeToWinitName: Map<Int, String> = mapOf(
        LwjglGlfwKeycode.GLFW_KEY_SPACE.toInt() to "Space",
        LwjglGlfwKeycode.GLFW_KEY_APOSTROPHE.toInt() to "Quote",
        LwjglGlfwKeycode.GLFW_KEY_COMMA.toInt() to "Comma",
        LwjglGlfwKeycode.GLFW_KEY_MINUS.toInt() to "Minus",
        LwjglGlfwKeycode.GLFW_KEY_PERIOD.toInt() to "Period",
        LwjglGlfwKeycode.GLFW_KEY_SLASH.toInt() to "Slash",
        LwjglGlfwKeycode.GLFW_KEY_0.toInt() to "Digit0",
        LwjglGlfwKeycode.GLFW_KEY_1.toInt() to "Digit1",
        LwjglGlfwKeycode.GLFW_KEY_2.toInt() to "Digit2",
        LwjglGlfwKeycode.GLFW_KEY_3.toInt() to "Digit3",
        LwjglGlfwKeycode.GLFW_KEY_4.toInt() to "Digit4",
        LwjglGlfwKeycode.GLFW_KEY_5.toInt() to "Digit5",
        LwjglGlfwKeycode.GLFW_KEY_6.toInt() to "Digit6",
        LwjglGlfwKeycode.GLFW_KEY_7.toInt() to "Digit7",
        LwjglGlfwKeycode.GLFW_KEY_8.toInt() to "Digit8",
        LwjglGlfwKeycode.GLFW_KEY_9.toInt() to "Digit9",
        LwjglGlfwKeycode.GLFW_KEY_SEMICOLON.toInt() to "Semicolon",
        LwjglGlfwKeycode.GLFW_KEY_EQUAL.toInt() to "Equal",
        LwjglGlfwKeycode.GLFW_KEY_A.toInt() to "KeyA",
        LwjglGlfwKeycode.GLFW_KEY_B.toInt() to "KeyB",
        LwjglGlfwKeycode.GLFW_KEY_C.toInt() to "KeyC",
        LwjglGlfwKeycode.GLFW_KEY_D.toInt() to "KeyD",
        LwjglGlfwKeycode.GLFW_KEY_E.toInt() to "KeyE",
        LwjglGlfwKeycode.GLFW_KEY_F.toInt() to "KeyF",
        LwjglGlfwKeycode.GLFW_KEY_G.toInt() to "KeyG",
        LwjglGlfwKeycode.GLFW_KEY_H.toInt() to "KeyH",
        LwjglGlfwKeycode.GLFW_KEY_I.toInt() to "KeyI",
        LwjglGlfwKeycode.GLFW_KEY_J.toInt() to "KeyJ",
        LwjglGlfwKeycode.GLFW_KEY_K.toInt() to "KeyK",
        LwjglGlfwKeycode.GLFW_KEY_L.toInt() to "KeyL",
        LwjglGlfwKeycode.GLFW_KEY_M.toInt() to "KeyM",
        LwjglGlfwKeycode.GLFW_KEY_N.toInt() to "KeyN",
        LwjglGlfwKeycode.GLFW_KEY_O.toInt() to "KeyO",
        LwjglGlfwKeycode.GLFW_KEY_P.toInt() to "KeyP",
        LwjglGlfwKeycode.GLFW_KEY_Q.toInt() to "KeyQ",
        LwjglGlfwKeycode.GLFW_KEY_R.toInt() to "KeyR",
        LwjglGlfwKeycode.GLFW_KEY_S.toInt() to "KeyS",
        LwjglGlfwKeycode.GLFW_KEY_T.toInt() to "KeyT",
        LwjglGlfwKeycode.GLFW_KEY_U.toInt() to "KeyU",
        LwjglGlfwKeycode.GLFW_KEY_V.toInt() to "KeyV",
        LwjglGlfwKeycode.GLFW_KEY_W.toInt() to "KeyW",
        LwjglGlfwKeycode.GLFW_KEY_X.toInt() to "KeyX",
        LwjglGlfwKeycode.GLFW_KEY_Y.toInt() to "KeyY",
        LwjglGlfwKeycode.GLFW_KEY_Z.toInt() to "KeyZ",
        LwjglGlfwKeycode.GLFW_KEY_LEFT_BRACKET.toInt() to "BracketLeft",
        LwjglGlfwKeycode.GLFW_KEY_BACKSLASH.toInt() to "Backslash",
        LwjglGlfwKeycode.GLFW_KEY_RIGHT_BRACKET.toInt() to "BracketRight",
        LwjglGlfwKeycode.GLFW_KEY_GRAVE_ACCENT.toInt() to "Backquote",
        LwjglGlfwKeycode.GLFW_KEY_ESCAPE.toInt() to "Escape",
        LwjglGlfwKeycode.GLFW_KEY_ENTER.toInt() to "Enter",
        LwjglGlfwKeycode.GLFW_KEY_TAB.toInt() to "Tab",
        LwjglGlfwKeycode.GLFW_KEY_BACKSPACE.toInt() to "Backspace",
        LwjglGlfwKeycode.GLFW_KEY_INSERT.toInt() to "Insert",
        LwjglGlfwKeycode.GLFW_KEY_DELETE.toInt() to "Delete",
        LwjglGlfwKeycode.GLFW_KEY_RIGHT.toInt() to "ArrowRight",
        LwjglGlfwKeycode.GLFW_KEY_LEFT.toInt() to "ArrowLeft",
        LwjglGlfwKeycode.GLFW_KEY_DOWN.toInt() to "ArrowDown",
        LwjglGlfwKeycode.GLFW_KEY_UP.toInt() to "ArrowUp",
        LwjglGlfwKeycode.GLFW_KEY_PAGE_UP.toInt() to "PageUp",
        LwjglGlfwKeycode.GLFW_KEY_PAGE_DOWN.toInt() to "PageDown",
        LwjglGlfwKeycode.GLFW_KEY_HOME.toInt() to "Home",
        LwjglGlfwKeycode.GLFW_KEY_END.toInt() to "End",
        LwjglGlfwKeycode.GLFW_KEY_CAPS_LOCK.toInt() to "CapsLock",
        LwjglGlfwKeycode.GLFW_KEY_SCROLL_LOCK.toInt() to "ScrollLock",
        LwjglGlfwKeycode.GLFW_KEY_NUM_LOCK.toInt() to "NumLock",
        LwjglGlfwKeycode.GLFW_KEY_PRINT_SCREEN.toInt() to "PrintScreen",
        LwjglGlfwKeycode.GLFW_KEY_PAUSE.toInt() to "Pause",
        LwjglGlfwKeycode.GLFW_KEY_F1.toInt() to "F1",
        LwjglGlfwKeycode.GLFW_KEY_F2.toInt() to "F2",
        LwjglGlfwKeycode.GLFW_KEY_F3.toInt() to "F3",
        LwjglGlfwKeycode.GLFW_KEY_F4.toInt() to "F4",
        LwjglGlfwKeycode.GLFW_KEY_F5.toInt() to "F5",
        LwjglGlfwKeycode.GLFW_KEY_F6.toInt() to "F6",
        LwjglGlfwKeycode.GLFW_KEY_F7.toInt() to "F7",
        LwjglGlfwKeycode.GLFW_KEY_F8.toInt() to "F8",
        LwjglGlfwKeycode.GLFW_KEY_F9.toInt() to "F9",
        LwjglGlfwKeycode.GLFW_KEY_F10.toInt() to "F10",
        LwjglGlfwKeycode.GLFW_KEY_F11.toInt() to "F11",
        LwjglGlfwKeycode.GLFW_KEY_F12.toInt() to "F12",
        LwjglGlfwKeycode.GLFW_KEY_F13.toInt() to "F13",
        LwjglGlfwKeycode.GLFW_KEY_F14.toInt() to "F14",
        LwjglGlfwKeycode.GLFW_KEY_F15.toInt() to "F15",
        LwjglGlfwKeycode.GLFW_KEY_F16.toInt() to "F16",
        LwjglGlfwKeycode.GLFW_KEY_F17.toInt() to "F17",
        LwjglGlfwKeycode.GLFW_KEY_F18.toInt() to "F18",
        LwjglGlfwKeycode.GLFW_KEY_F19.toInt() to "F19",
        LwjglGlfwKeycode.GLFW_KEY_F20.toInt() to "F20",
        LwjglGlfwKeycode.GLFW_KEY_F21.toInt() to "F21",
        LwjglGlfwKeycode.GLFW_KEY_F22.toInt() to "F22",
        LwjglGlfwKeycode.GLFW_KEY_F23.toInt() to "F23",
        LwjglGlfwKeycode.GLFW_KEY_F24.toInt() to "F24",
        LwjglGlfwKeycode.GLFW_KEY_F25.toInt() to "F25",
        LwjglGlfwKeycode.GLFW_KEY_KP_0.toInt() to "Numpad0",
        LwjglGlfwKeycode.GLFW_KEY_KP_1.toInt() to "Numpad1",
        LwjglGlfwKeycode.GLFW_KEY_KP_2.toInt() to "Numpad2",
        LwjglGlfwKeycode.GLFW_KEY_KP_3.toInt() to "Numpad3",
        LwjglGlfwKeycode.GLFW_KEY_KP_4.toInt() to "Numpad4",
        LwjglGlfwKeycode.GLFW_KEY_KP_5.toInt() to "Numpad5",
        LwjglGlfwKeycode.GLFW_KEY_KP_6.toInt() to "Numpad6",
        LwjglGlfwKeycode.GLFW_KEY_KP_7.toInt() to "Numpad7",
        LwjglGlfwKeycode.GLFW_KEY_KP_8.toInt() to "Numpad8",
        LwjglGlfwKeycode.GLFW_KEY_KP_9.toInt() to "Numpad9",
        LwjglGlfwKeycode.GLFW_KEY_KP_DECIMAL.toInt() to "NumpadDecimal",
        LwjglGlfwKeycode.GLFW_KEY_KP_DIVIDE.toInt() to "NumpadDivide",
        LwjglGlfwKeycode.GLFW_KEY_KP_MULTIPLY.toInt() to "NumpadMultiply",
        LwjglGlfwKeycode.GLFW_KEY_KP_SUBTRACT.toInt() to "NumpadSubtract",
        LwjglGlfwKeycode.GLFW_KEY_KP_ADD.toInt() to "NumpadAdd",
        LwjglGlfwKeycode.GLFW_KEY_KP_ENTER.toInt() to "NumpadEnter",
        LwjglGlfwKeycode.GLFW_KEY_KP_EQUAL.toInt() to "NumpadEqual",
        LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT.toInt() to "ShiftLeft",
        LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL.toInt() to "ControlLeft",
        LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT.toInt() to "AltLeft",
        LwjglGlfwKeycode.GLFW_KEY_LEFT_SUPER.toInt() to "SuperLeft",
        LwjglGlfwKeycode.GLFW_KEY_RIGHT_SHIFT.toInt() to "ShiftRight",
        LwjglGlfwKeycode.GLFW_KEY_RIGHT_CONTROL.toInt() to "ControlRight",
        LwjglGlfwKeycode.GLFW_KEY_RIGHT_ALT.toInt() to "AltRight",
        LwjglGlfwKeycode.GLFW_KEY_RIGHT_SUPER.toInt() to "SuperRight",
        LwjglGlfwKeycode.GLFW_KEY_MENU.toInt() to "ContextMenu"
    )

    private val mouseButtonToWinitName: Map<Int, String> = mapOf(
        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt() to "Left",
        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt() to "Right",
        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE.toInt() to "Middle",
        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_4.toInt() to "Back",
        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_5.toInt() to "Forward"
    )

    fun toWinitKeyName(glfwKey: Int): String? = keycodeToWinitName[glfwKey]

    fun toWinitMouseButtonName(glfwButton: Int): String? = mouseButtonToWinitName[glfwButton]

    fun allSupportedGlfwKeycodes(): Set<Int> = keycodeToWinitName.keys

    fun resolvePrintableFallbackChar(glfwKey: Int, modifiers: Int): Char {
        val shift = (modifiers and LwjglGlfwKeycode.GLFW_MOD_SHIFT) != 0
        val ctrl = (modifiers and LwjglGlfwKeycode.GLFW_MOD_CONTROL) != 0
        if (ctrl) return '\u0000'
        return when (glfwKey) {
            LwjglGlfwKeycode.GLFW_KEY_SPACE.toInt() -> ' '
            in LwjglGlfwKeycode.GLFW_KEY_A.toInt()..LwjglGlfwKeycode.GLFW_KEY_Z.toInt() -> {
                val base = ('a'.code + (glfwKey - LwjglGlfwKeycode.GLFW_KEY_A.toInt())).toChar()
                if (shift) base.uppercaseChar() else base
            }
            LwjglGlfwKeycode.GLFW_KEY_0.toInt() -> if (shift) ')' else '0'
            LwjglGlfwKeycode.GLFW_KEY_1.toInt() -> if (shift) '!' else '1'
            LwjglGlfwKeycode.GLFW_KEY_2.toInt() -> if (shift) '@' else '2'
            LwjglGlfwKeycode.GLFW_KEY_3.toInt() -> if (shift) '#' else '3'
            LwjglGlfwKeycode.GLFW_KEY_4.toInt() -> if (shift) '$' else '4'
            LwjglGlfwKeycode.GLFW_KEY_5.toInt() -> if (shift) '%' else '5'
            LwjglGlfwKeycode.GLFW_KEY_6.toInt() -> if (shift) '^' else '6'
            LwjglGlfwKeycode.GLFW_KEY_7.toInt() -> if (shift) '&' else '7'
            LwjglGlfwKeycode.GLFW_KEY_8.toInt() -> if (shift) '*' else '8'
            LwjglGlfwKeycode.GLFW_KEY_9.toInt() -> if (shift) '(' else '9'
            LwjglGlfwKeycode.GLFW_KEY_MINUS.toInt() -> if (shift) '_' else '-'
            LwjglGlfwKeycode.GLFW_KEY_EQUAL.toInt() -> if (shift) '+' else '='
            LwjglGlfwKeycode.GLFW_KEY_LEFT_BRACKET.toInt() -> if (shift) '{' else '['
            LwjglGlfwKeycode.GLFW_KEY_RIGHT_BRACKET.toInt() -> if (shift) '}' else ']'
            LwjglGlfwKeycode.GLFW_KEY_BACKSLASH.toInt() -> if (shift) '|' else '\\'
            LwjglGlfwKeycode.GLFW_KEY_SEMICOLON.toInt() -> if (shift) ':' else ';'
            LwjglGlfwKeycode.GLFW_KEY_APOSTROPHE.toInt() -> if (shift) '"' else '\''
            LwjglGlfwKeycode.GLFW_KEY_GRAVE_ACCENT.toInt() -> if (shift) '~' else '`'
            LwjglGlfwKeycode.GLFW_KEY_COMMA.toInt() -> if (shift) '<' else ','
            LwjglGlfwKeycode.GLFW_KEY_PERIOD.toInt() -> if (shift) '>' else '.'
            LwjglGlfwKeycode.GLFW_KEY_SLASH.toInt() -> if (shift) '?' else '/'
            in LwjglGlfwKeycode.GLFW_KEY_KP_0.toInt()..LwjglGlfwKeycode.GLFW_KEY_KP_9.toInt() ->
                ('0'.code + (glfwKey - LwjglGlfwKeycode.GLFW_KEY_KP_0.toInt())).toChar()
            LwjglGlfwKeycode.GLFW_KEY_KP_DECIMAL.toInt() -> '.'
            LwjglGlfwKeycode.GLFW_KEY_KP_DIVIDE.toInt() -> '/'
            LwjglGlfwKeycode.GLFW_KEY_KP_MULTIPLY.toInt() -> '*'
            LwjglGlfwKeycode.GLFW_KEY_KP_SUBTRACT.toInt() -> '-'
            LwjglGlfwKeycode.GLFW_KEY_KP_ADD.toInt() -> '+'
            LwjglGlfwKeycode.GLFW_KEY_KP_EQUAL.toInt() -> '='
            else -> '\u0000'
        }
    }
}
