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
import io.github.kaito65452.blazelauncher.rustruntime.input.ImeSource
import io.github.kaito65452.blazelauncher.rustruntime.input.RustImeController
import io.github.kaito65452.blazelauncher.rustruntime.input.RustImeDiagnostics
import io.github.kaito65452.blazelauncher.rustruntime.input.RustImeTextProcessor
import io.github.kaito65452.blazelauncher.rustruntime.input.RustInputBridgeImpl
import io.github.kaito65452.blazelauncher.rustruntime.input.RustNativeInputTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StageLB4ImeControllerTest {
    private sealed interface Event {
        data class Key(val keyCode: Int, val pressed: Boolean) : Event
        data class CharCp(val codepoint: Int) : Event
    }

    private class RecordingTransport : RustNativeInputTransport {
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

    private class ImmediateScheduler : RustImeController.Scheduler {
        override fun post(delayMs: Long, task: Runnable): RustImeController.Cancellable {
            if (delayMs == 0L) task.run()
            return object : RustImeController.Cancellable {
                override fun cancel() = Unit
            }
        }
    }

    private var shows = 0
    private var hides = 0
    private var autoShow = true
    private lateinit var c: RustImeController

    @Before
    fun setUp() {
        RustImeDiagnostics.reset()
        shows = 0
        hides = 0
        autoShow = true
        c = RustImeController(
            host = object : RustImeController.Host {
                override fun showIme() { shows++ }
                override fun hideIme() { hides++ }
            },
            scheduler = ImmediateScheduler(),
            autoShowProvider = { autoShow }
        )
    }

    @Test
    fun noInitialShowOnTitleOrLoading() {
        c.onOtherRequest(ImeSource.ACTIVITY_CREATE)
        c.onOtherRequest(ImeSource.OVERLAY_ATTACH)
        c.onOtherRequest(ImeSource.WINDOW_FOCUS)
        c.onOtherRequest(ImeSource.RESUME)
        c.onEngineSignal(false)
        assertEquals(0, shows)
        assertEquals(0, hides)
        assertFalse(c.wanted)
        assertEquals(4L, RustImeDiagnostics.ignoredShowRequests.get())
    }

    @Test
    fun engineTransitionsShowOnceAndHide() {
        c.onEngineSignal(true)
        c.onEngineSignal(true)
        assertEquals(1, shows)
        assertEquals(0, hides)
        c.onEngineSignal(false)
        assertEquals(1, hides)
        assertFalse(c.wanted)
    }

    @Test
    fun userDismissReleasesFocusAndIsRespectedUntilNextTransition() {
        c.onEngineSignal(true)
        c.onImeVisibility(true)
        c.onImeVisibility(false) // Gboard hide button
        assertFalse(c.wanted)
        assertEquals(1, hides)
        c.onOtherRequest(ImeSource.WINDOW_FOCUS)
        c.onOtherRequest(ImeSource.RESUME)
        c.onEngineSignal(true) // repeated true
        assertEquals(1, shows)
        c.onEngineSignal(false)
        c.onEngineSignal(true) // next false->true
        assertEquals(2, shows)
    }

    @Test
    fun initialInvisibleInsetsDoNotCountAsDismissal() {
        c.onEngineSignal(true)
        c.onImeVisibility(false) // before the keyboard ever appeared
        assertTrue(c.wanted)
        assertEquals(0, hides)
    }

    @Test
    fun manualButtonToggles() {
        c.onManualToggle()
        assertEquals(1, shows)
        c.onManualToggle()
        assertEquals(1, hides)
        assertFalse(c.wanted)
    }

    @Test
    fun chatTapRespectsManualFirstAndUserDismiss() {
        c.onChatTap()
        assertEquals(0, shows)
        c.onEngineSignal(true)
        c.onImeVisibility(true)
        c.onImeVisibility(false)
        assertEquals(1, hides)
        // L-B5 policy: chat taps do not auto-pop IME; explicit manual toggle does.
        c.onChatTap()
        assertEquals(1, shows)
        c.onManualToggle()
        assertEquals(2, shows)
    }

    @Test
    fun ringBufferKeepsLast30AndFormatsAllDiagnosticsWithoutLeakingText() {
        repeat(50) { c.onOtherRequest(ImeSource.RESUME) }
        assertEquals(30, RustImeDiagnostics.transitions().size)
        val formatted = RustImeDiagnostics.format()
        assertTrue(formatted, formatted.contains("ime_target_view="))
        assertTrue(formatted, formatted.contains("view_has_focus="))
        assertTrue(formatted, formatted.contains("window_has_focus="))
        assertTrue(formatted, formatted.contains("window_flags=0x"))
        assertTrue(formatted, formatted.contains("overlay_window_flags=0x"))
        assertTrue(formatted, formatted.contains("imm_is_active="))
        assertTrue(formatted, formatted.contains("imm_is_accepting_text="))
        assertTrue(formatted, formatted.contains("on_create_input_connection="))
        assertTrue(formatted, formatted.contains("on_start_input="))
        assertTrue(formatted, formatted.contains("editor_input_type=0x"))
        assertTrue(formatted, formatted.contains("editor_ime_options=0x"))
        assertTrue(formatted, formatted.contains("ime_show_ignored=50"))
        assertTrue(formatted, formatted.contains("ic_commitText="))
        assertTrue(formatted, formatted.contains("ic_setComposingText="))
        assertTrue(formatted, formatted.contains("ic_sendKeyEvent="))
    }

    @Test
    fun shadowComposingSwipeCommitBackspaceAndEmojiSurrogatePairs() {
        val transport = RecordingTransport()
        val sink = RustInputBridgeImpl(transport = transport)
        val processor = RustImeTextProcessor()

        // Composing progression: "hel" -> "hlelo" -> autocorrect commit "hello "
        assertTrue(processor.onSetComposingText("hel", sink))
        assertTrue(processor.onSetComposingText("hlelo", sink))
        assertTrue(processor.onCommitText("hello ", sink))
        processor.onFinishComposingText()

        // Swipe/paste with Unicode emoji surrogate pair + newline -> Enter
        val emoji = "\uD83D\uDD25" // U+1F525 FIRE
        assertTrue(processor.onCommitText("$emoji\n", sink))

        // Delete 2 codepoints
        assertTrue(processor.onDeleteSurroundingCodepoints(2, sink))

        val charCps = transport.events.filterIsInstance<Event.CharCp>().map { it.codepoint }
        val keyDowns = transport.events.filterIsInstance<Event.Key>().filter { it.pressed }.map { it.keyCode }

        assertTrue("Expected U+1F525 intact", charCps.contains(0x1F525))
        assertTrue(
            "Expected Enter key from newline",
            keyDowns.contains(LwjglGlfwKeycode.GLFW_KEY_ENTER.toInt())
        )
        // "hel" -> "hlelo" (2 bs) + "hlelo" -> "hello " (4 bs) + deleteSurrounding(2) = 8 backspaces
        val backspaceCount = keyDowns.count { it == LwjglGlfwKeycode.GLFW_KEY_BACKSPACE.toInt() }
        assertEquals(8, backspaceCount)
    }
}
