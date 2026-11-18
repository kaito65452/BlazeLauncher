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

import java.util.concurrent.atomic.AtomicLong

/** Why the keyboard was asked to show or hide. Only the first three may ever show it. */
enum class ImeSource(val label: String, val mayShow: Boolean) {
    ENGINE_SIGNAL("engine_signal", true),
    MANUAL_BUTTON("manual_button", true),
    CHAT_TAP("chat_tap", true),
    USER_DISMISS("user_dismiss", false),
    ACTIVITY_CREATE("activity_create", false),
    WINDOW_FOCUS("window_focus", false),
    OVERLAY_ATTACH("overlay_attach", false),
    RESUME("resume", false),
    OTHER("other", false)
}

/** Copy-report data for the IME path. Never holds typed text, only counts and lengths. */
object RustImeDiagnostics {
    private const val RING = 30

    val showRequests = AtomicLong(0)
    val hideRequests = AtomicLong(0)
    val ignoredShowRequests = AtomicLong(0)
    val createInputConnection = AtomicLong(0)
    val startInputSessions = AtomicLong(0)
    val commitTextCalls = AtomicLong(0)
    val commitTextChars = AtomicLong(0)
    val composingCalls = AtomicLong(0)
    val composingChars = AtomicLong(0)
    val finishComposingCalls = AtomicLong(0)
    val deleteSurroundingCalls = AtomicLong(0)
    val sendKeyEventCalls = AtomicLong(0)

    @Volatile var engineTextInputActive: Boolean = false
    @Volatile var imeVisibleObserved: Boolean = false
    @Volatile var targetViewClass: String = "none"
    @Volatile var viewHasFocus: Boolean = false
    @Volatile var windowHasFocus: Boolean = false
    @Volatile var windowFlags: Int = 0
    @Volatile var overlayWindowFlags: Int = 0
    @Volatile var immActive: Boolean = false
    @Volatile var immAcceptingText: Boolean = false
    @Volatile var editorInputType: Int = 0
    @Volatile var editorImeOptions: Int = 0

    private val ring = ArrayDeque<String>()

    @Synchronized
    fun record(entry: String, nowMs: Long = System.currentTimeMillis()) {
        ring.addLast("$nowMs:$entry")
        while (ring.size > RING) ring.removeFirst()
    }

    @Synchronized
    fun transitions(): List<String> = ring.toList()

    @Synchronized
    fun reset() {
        listOf(
            showRequests, hideRequests, ignoredShowRequests, createInputConnection,
            startInputSessions, commitTextCalls, commitTextChars, composingCalls, composingChars,
            finishComposingCalls, deleteSurroundingCalls, sendKeyEventCalls
        ).forEach { it.set(0) }
        engineTextInputActive = false
        imeVisibleObserved = false
        targetViewClass = "none"
        viewHasFocus = false
        windowHasFocus = false
        windowFlags = 0
        overlayWindowFlags = 0
        immActive = false
        immAcceptingText = false
        editorInputType = 0
        editorImeOptions = 0
        ring.clear()
    }

    fun format(): String =
        "ime_target_view=$targetViewClass view_has_focus=$viewHasFocus window_has_focus=$windowHasFocus " +
            "window_flags=0x${Integer.toHexString(windowFlags)} " +
            "overlay_window_flags=0x${Integer.toHexString(overlayWindowFlags)} " +
            "imm_is_active=$immActive imm_is_accepting_text=$immAcceptingText " +
            "on_create_input_connection=${createInputConnection.get()} " +
            "on_start_input=${startInputSessions.get()} " +
            "editor_input_type=0x${Integer.toHexString(editorInputType)} " +
            "editor_ime_options=0x${Integer.toHexString(editorImeOptions)} " +
            "ime_show_requests=${showRequests.get()} ime_hide_requests=${hideRequests.get()} " +
            "ime_show_ignored=${ignoredShowRequests.get()} " +
            "engine_text_input_active=$engineTextInputActive ime_visible_observed=$imeVisibleObserved " +
            "ic_commitText=${commitTextCalls.get()}(chars=${commitTextChars.get()}) " +
            "ic_setComposingText=${composingCalls.get()}(chars=${composingChars.get()}) " +
            "ic_finishComposing=${finishComposingCalls.get()} " +
            "ic_deleteSurrounding=${deleteSurroundingCalls.get()} ic_sendKeyEvent=${sendKeyEventCalls.get()} " +
            "ime_transitions=[${transitions().joinToString(",")}]"
}

/**
 * Single IME state machine for Rust Edition desktop mode.
 *
 * - engine false->true: show once; true->false: hide; repeated values are ignored.
 * - manual button: toggle. Chat tap: show when the engine wants text input.
 * - user dismissal (Gboard/Back) is respected: focus changes, resume, relayout and repeated engine
 *   `true` never re-show; only the next false->true, the manual button, or a chat tap do.
 * - Any other source is recorded and ignored.
 */
class RustImeController(
    private val host: Host,
    private val clock: () -> Long = System::currentTimeMillis
) {
    interface Host {
        fun showIme()
        fun hideIme()
    }

    var engineActive: Boolean = false
        private set

    /** True while the keyboard is (or should be) up. */
    var wanted: Boolean = false
        private set

    fun onEngineSignal(active: Boolean) {
        RustImeDiagnostics.record("engine_signal=$active", clock())
        if (active == engineActive) return
        engineActive = active
        RustImeDiagnostics.engineTextInputActive = active
        if (active) show(ImeSource.ENGINE_SIGNAL) else hide(ImeSource.ENGINE_SIGNAL)
    }

    fun onManualToggle() {
        if (wanted) hide(ImeSource.MANUAL_BUTTON) else show(ImeSource.MANUAL_BUTTON)
    }

    fun onChatTap() {
        if (engineActive && !wanted) show(ImeSource.CHAT_TAP)
    }

    /** Gboard hide button or Back closed the keyboard: respect it and release IME focus. */
    fun onUserDismissed() {
        if (!wanted) return
        wanted = false
        RustImeDiagnostics.hideRequests.incrementAndGet()
        RustImeDiagnostics.record("hide:${ImeSource.USER_DISMISS.label}", clock())
        host.hideIme()
    }

    /** Any non-sanctioned caller lands here; it never shows the keyboard. */
    fun onOtherRequest(source: ImeSource) {
        require(!source.mayShow)
        RustImeDiagnostics.ignoredShowRequests.incrementAndGet()
        RustImeDiagnostics.record("ignored_show_from=${source.label}", clock())
    }

    /** Insets truth source. Only a visible->hidden edge counts as a user dismissal. */
    fun onImeVisibility(visible: Boolean) {
        val before = RustImeDiagnostics.imeVisibleObserved
        if (before != visible) RustImeDiagnostics.record("ime_visible=$visible", clock())
        RustImeDiagnostics.imeVisibleObserved = visible
        if (before && !visible && wanted) onUserDismissed()
    }

    /** The text screen closed (engine says false) or the host closed it itself. */
    fun onTextCommitted() {
        if (!engineActive && wanted) hide(ImeSource.OTHER)
    }

    private fun show(source: ImeSource) {
        if (!source.mayShow) return onOtherRequest(source)
        wanted = true
        RustImeDiagnostics.showRequests.incrementAndGet()
        RustImeDiagnostics.record("show:${source.label}", clock())
        host.showIme()
    }

    private fun hide(source: ImeSource) {
        val was = wanted
        wanted = false
        RustImeDiagnostics.hideRequests.incrementAndGet()
        RustImeDiagnostics.record("hide:${source.label}", clock())
        if (was || RustImeDiagnostics.imeVisibleObserved) host.hideIme()
    }
}
