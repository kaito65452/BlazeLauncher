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

import io.github.kaito65452.blazelauncher.rustruntime.input.BlazeInputRouter
import io.github.kaito65452.blazelauncher.rustruntime.input.ImeSource
import io.github.kaito65452.blazelauncher.rustruntime.input.InactiveDropCountingSink
import io.github.kaito65452.blazelauncher.rustruntime.input.RustImeController
import io.github.kaito65452.blazelauncher.rustruntime.input.RustImeDiagnostics
import io.github.kaito65452.blazelauncher.rustruntime.input.RustInputBridgeImpl
import io.github.kaito65452.blazelauncher.rustruntime.input.RustNativeInputTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StageLB5ImeControllerTest {
    private class FakeScheduler : RustImeController.Scheduler {
        var nowMs: Long = 1000L

        private data class Entry(val runAt: Long, val task: Runnable, var cancelled: Boolean = false)
        private val queue = mutableListOf<Entry>()

        override fun post(delayMs: Long, task: Runnable): RustImeController.Cancellable {
            val e = Entry(nowMs + delayMs.coerceAtLeast(0L), task)
            queue += e
            return object : RustImeController.Cancellable {
                override fun cancel() {
                    e.cancelled = true
                }
            }
        }

        fun runDue() {
            while (true) {
                val next = queue.filter { !it.cancelled && it.runAt <= nowMs }
                    .minByOrNull { it.runAt } ?: break
                queue.remove(next)
                next.task.run()
            }
        }

        fun advanceAndRun(deltaMs: Long) {
            nowMs += deltaMs
            runDue()
        }
    }

    private class RecordingTransport : RustNativeInputTransport {
        var keys = 0
        var chars = 0
        override fun injectKey(keyCode: Int, unicodeChar: Int, mods: Int, pressed: Boolean) { keys++ }
        override fun injectMouseButton(button: Int, mods: Int, pressed: Boolean) = Unit
        override fun injectCursorPos(normX: Float, normY: Float) = Unit
        override fun injectCursorDelta(dx: Float, dy: Float) = Unit
        override fun injectScroll(dx: Float, dy: Float) = Unit
        override fun injectChar(codepoint: Int) { chars++ }
        override fun injectAndroidBack() = Unit
        override fun resetInputState() = Unit
        override fun setDesktopInputMode(enabled: Boolean) = Unit
    }

    private lateinit var scheduler: FakeScheduler
    private lateinit var controller: RustImeController
    private var autoShow = false
    private var shows = 0
    private var hides = 0
    private var overlayKeepBehind = false

    @Before
    fun setUp() {
        RustImeDiagnostics.reset()
        scheduler = FakeScheduler()
        autoShow = false
        shows = 0
        hides = 0
        overlayKeepBehind = false
        controller = RustImeController(
            host = object : RustImeController.Host {
                override fun showIme() { shows++ }
                override fun hideIme() { hides++ }
                override fun onOverlayImeStateChanged(keepBehindIme: Boolean) {
                    overlayKeepBehind = keepBehindIme
                }
            },
            clock = { scheduler.nowMs },
            scheduler = scheduler,
            autoShowProvider = { autoShow }
        )
    }

    @Test
    fun autoShowOffDoesNotPopKeyboardOnEngineTrue() {
        autoShow = false
        controller.onEngineSignal(true, "chat")
        scheduler.runDue()
        assertTrue(controller.engineActive)
        assertEquals(RustImeController.DesiredState.HIDDEN, controller.desiredState)
        assertEquals(0, shows)
        assertFalse(RustImeDiagnostics.autoShowSetting)
        assertEquals("chat", RustImeDiagnostics.textInputSourceLast)

        // User presses manual IME button to show, then engine closes -> auto-hides.
        controller.onManualToggle()
        scheduler.runDue()
        assertEquals(RustImeController.DesiredState.SHOWN, controller.desiredState)
        assertEquals(1, shows)
        assertTrue(overlayKeepBehind)

        controller.onImeVisibility(true)
        controller.onEngineSignal(false, "none")
        scheduler.runDue()
        assertEquals(RustImeController.DesiredState.HIDDEN, controller.desiredState)
        assertEquals(1, hides)
    }

    @Test
    fun showThenHideIn97msCancelsPendingShowAndReconcilesStuckVisibleIme() {
        autoShow = true
        // t=1000: engine signals true (show queued, not executed yet)
        controller.onEngineSignal(true, "dialog")
        assertEquals(0, shows)

        // t=1097 (+97 ms): engine signals false before queued show runs -> pending show cancelled!
        scheduler.nowMs += 97L
        controller.onEngineSignal(false, "none")
        scheduler.runDue()

        assertEquals(0, shows)
        assertEquals(1L, RustImeDiagnostics.pendingShowCancelledCount.get())
        assertEquals(RustImeController.DesiredState.HIDDEN, controller.desiredState)

        // Simulate late WindowInsetsCompat reporting ime_visible=true anyway (L-B4 499287 bug!)
        scheduler.nowMs += 190L
        controller.onImeVisibility(true)
        assertTrue("Overlay must stay behind IME while still visible", overlayKeepBehind)

        // Bounded reconciliation retries up to MAX_RECONCILE_ATTEMPTS (3) hides, then stops.
        repeat(5) {
            scheduler.advanceAndRun(RustImeController.RECONCILE_DELAY_MS)
        }
        assertEquals(3, hides)
        assertEquals(3L, RustImeDiagnostics.reconcileAttempts.get())
        assertTrue(
            RustImeDiagnostics.reconcileLastSource,
            RustImeDiagnostics.reconcileLastSource.startsWith("reconcile")
        )

        // Once insets report ime_visible=false, overlay flags restore to normal.
        controller.onImeVisibility(false)
        assertFalse(overlayKeepBehind)
    }

    @Test
    fun userDismissIsRespectedUntilExplicitShow() {
        autoShow = true
        controller.onEngineSignal(true, "chat")
        scheduler.runDue()
        controller.onImeVisibility(true)
        assertEquals(1, shows)

        // Gboard hide button -> visible becomes false while wanted
        controller.onImeVisibility(false)
        assertEquals(RustImeController.DesiredState.HIDDEN, controller.desiredState)
        assertEquals(1, hides)

        // Focus/resume/chat-tap/repeated true MUST NOT re-show while auto-dismissed
        controller.onOtherRequest(ImeSource.WINDOW_FOCUS)
        controller.onOtherRequest(ImeSource.RESUME)
        controller.onChatTap()
        controller.onEngineSignal(true, "chat")
        scheduler.runDue()
        assertEquals(1, shows)

        // Manual IME button still works immediately
        controller.onManualToggle()
        scheduler.runDue()
        assertEquals(2, shows)
    }

    @Test
    fun textWhileEngineInactiveIsDroppedAndCountedAndReconciles() {
        val transport = RecordingTransport()
        val baseSink = RustInputBridgeImpl(transport = transport)
        val dropSink = InactiveDropCountingSink(
            delegate = baseSink,
            isEngineActive = { controller.engineActive },
            onInactiveDropped = { controller.onTextWhileEngineInactive() }
        )

        controller.onEngineSignal(false, "none")
        controller.onImeVisibility(true) // keyboard stuck visible while engine inactive
        dropSink.sendCodepoint('a'.code)
        dropSink.sendCodepoint('b'.code)
        dropSink.sendKeyPress(257) // Enter down + up
        scheduler.runDue()

        assertEquals(0, transport.chars)
        assertEquals(0, transport.keys)
        assertEquals(2L, RustImeDiagnostics.charsDroppedInactive.get())
        assertEquals(1L, RustImeDiagnostics.keysDroppedInactive.get())
        assertTrue(hides >= 1)
        val report = RustImeDiagnostics.format()
        assertTrue(report, report.contains("ime_desired_state=hidden"))
        assertTrue(report, report.contains("ime_observed_visible=true"))
        assertTrue(report, report.contains("chars_dropped_inactive=2"))
        assertTrue(report, report.contains("keys_dropped_inactive=1"))
        assertTrue(report, report.contains("input_restart_reasons=["))
        assertTrue(report, report.contains("text_input_signals_last20=["))
    }
}
