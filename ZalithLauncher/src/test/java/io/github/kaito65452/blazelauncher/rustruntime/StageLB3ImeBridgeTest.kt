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
import io.github.kaito65452.blazelauncher.rustruntime.input.RustImeTextProcessor
import io.github.kaito65452.blazelauncher.rustruntime.input.RustInputBridgeImpl
import io.github.kaito65452.blazelauncher.rustruntime.input.RustInputDiagnostics
import io.github.kaito65452.blazelauncher.rustruntime.input.RustNativeInputTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageLB3ImeBridgeTest {
    private sealed interface Event {
        data class Key(val keyCode: Int, val pressed: Boolean) : Event
        data class CharCp(val codepoint: Int) : Event
    }

    private class RecordingImeTransport : RustNativeInputTransport {
        val events = mutableListOf<Event>()
        override fun injectKey(keyCode: Int, unicodeChar: Int, mods: Int, pressed: Boolean) {
            events += Event.Key(keyCode, pressed)
        }
        override fun injectMouseButton(button: Int, mods: Int, pressed: Boolean) = Unit
        override fun injectCursorPos(normX: Float, normY: Float) = Unit
        override fun injectCursorDelta(dx: Float, dy: Float) = Unit
        override fun injectScroll(dx: Float, dy: Float) = Unit
        override fun injectChar(codepoint: Int) {
            events += Event.CharCp(codepoint)
        }
        override fun injectAndroidBack() = Unit
        override fun resetInputState() = Unit
        override fun setDesktopInputMode(enabled: Boolean) = Unit
    }

    @After
    fun tearDown() {
        RustInputDiagnostics.reset()
    }

    @Test
    fun composingText_halo_emitsMinimalCodepointsWithoutRedundantBackspaces() {
        val transport = RecordingImeTransport()
        val bridge = RustInputBridgeImpl(transport)
        val processor = RustImeTextProcessor()

        // Simulate IME composing: "" -> "h" -> "ha" -> "hal" -> "halo"
        val steps = listOf("" to "h", "h" to "ha", "ha" to "hal", "hal" to "halo")
        for ((before, after) in steps) {
            processor.onBeforeTextChanged(before, 0, before.length)
            processor.onTextChanged(after, 0, after.length, bridge)
        }

        assertEquals(
            listOf(
                Event.CharCp('h'.code),
                Event.CharCp('a'.code),
                Event.CharCp('l'.code),
                Event.CharCp('o'.code)
            ),
            transport.events
        )
        assertEquals(4L, bridge.charCount.get())
        assertEquals(0L, bridge.keyCount.get())
    }

    @Test
    fun autocorrectAndBackspace_emitExactBackspaceCountAndReplacement() {
        val transport = RecordingImeTransport()
        val bridge = RustInputBridgeImpl(transport)

        // Autocorrect "hlelo" -> "hello" (common prefix "h", 4 backspaces, then "ello")
        RustImeTextProcessor.applyDiff("hlelo", "hello", bridge)
        val bs = LwjglGlfwKeycode.GLFW_KEY_BACKSPACE.toInt()
        val expected = buildList {
            repeat(4) {
                add(Event.Key(bs, true))
                add(Event.Key(bs, false))
            }
            "ello".forEach { add(Event.CharCp(it.code)) }
        }
        assertEquals(expected, transport.events)

        // Single backspace on filler or last char
        transport.events.clear()
        RustImeTextProcessor.applyDiff("o", "", bridge)
        assertEquals(listOf(Event.Key(bs, true), Event.Key(bs, false)), transport.events)
    }

    @Test
    fun unicodeEmojiPasteAndNewline_preserveCodepointsAndSendEnter() {
        val transport = RecordingImeTransport()
        val bridge = RustInputBridgeImpl(transport)

        val text = "halo こんにちは 🌍\n"
        RustImeTextProcessor.applyDiff("", text, bridge)

        val enter = LwjglGlfwKeycode.GLFW_KEY_ENTER.toInt()
        val expectedCodepoints = listOf(
            'h'.code, 'a'.code, 'l'.code, 'o'.code, ' '.code,
            'こ'.code, 'ん'.code, 'に'.code, 'ち'.code, 'は'.code, ' '.code,
            0x1F30D // Earth globe emoji surrogate pair
        )
        val expected = expectedCodepoints.map { Event.CharCp(it) } +
            listOf(Event.Key(enter, true), Event.Key(enter, false))
        assertEquals(expected, transport.events)
        assertEquals(expectedCodepoints.size.toLong(), bridge.charCount.get())
    }

    @Test
    fun diagnosticsFormatIncludesImeSignalsAndShownCount() {
        RustInputDiagnostics.reset()
        RustInputDiagnostics.textInputActiveSignals.addAndGet(3)
        RustInputDiagnostics.imeShownCount.addAndGet(2)
        val formatted = RustInputDiagnostics.format(0L)
        assertTrue(formatted, formatted.contains("text_input_active_signals=3"))
        assertTrue(formatted, formatted.contains("ime_shown_count=2"))
    }
}
