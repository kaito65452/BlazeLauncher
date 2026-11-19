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
import java.util.concurrent.atomic.AtomicLong

/** Why the keyboard was asked to show or hide. Only the first two may show it (ENGINE_SIGNAL only if auto-show ON). */
enum class ImeSource(val label: String, val mayShow: Boolean) {
    ENGINE_SIGNAL("engine_signal", true),
    MANUAL_BUTTON("manual_button", true),
    CHAT_TAP("chat_tap", false),
    USER_DISMISS("user_dismiss", false),
    RECONCILE("reconcile", false),
    ACTIVITY_CREATE("activity_create", false),
    WINDOW_FOCUS("window_focus", false),
    OVERLAY_ATTACH("overlay_attach", false),
    RESUME("resume", false),
    OTHER("other", false)
}

/** Copy-report data for the IME path. Never holds typed text, only counts, lengths, and sources. */
object RustImeDiagnostics {
    private const val RING = 30
    private const val SIGNALS_RING = 20

    val showRequests = AtomicLong(0)
    val hideRequests = AtomicLong(0)
    val ignoredShowRequests = AtomicLong(0)
    val pendingShowCancelledCount = AtomicLong(0)
    val reconcileAttempts = AtomicLong(0)
    val createInputConnection = AtomicLong(0)
    val startInputSessions = AtomicLong(0)
    val commitTextCalls = AtomicLong(0)
    val commitTextChars = AtomicLong(0)
    val composingCalls = AtomicLong(0)
    val composingChars = AtomicLong(0)
    val finishComposingCalls = AtomicLong(0)
    val deleteSurroundingCalls = AtomicLong(0)
    val sendKeyEventCalls = AtomicLong(0)
    val charsDroppedInactive = AtomicLong(0)
    val keysDroppedInactive = AtomicLong(0)

    @Volatile var desiredState: String = "hidden"
    @Volatile var engineTextInputActive: Boolean = false
    @Volatile var textInputSourceLast: String = "none"
    @Volatile var autoShowSetting: Boolean = false
    @Volatile var reconcileLastSource: String = "none"
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
    private val signalsRing = ArrayDeque<String>()
    private val restartReasons = linkedMapOf<String, Long>()
    private var lastSignalMs: Long = -1L

    @Synchronized
    fun record(entry: String, nowMs: Long = System.currentTimeMillis()) {
        ring.addLast("$nowMs:$entry")
        while (ring.size > RING) ring.removeFirst()
    }

    @Synchronized
    fun recordEngineSignal(active: Boolean, source: String, nowMs: Long = System.currentTimeMillis()) {
        val delta = if (lastSignalMs >= 0L) nowMs - lastSignalMs else 0L
        lastSignalMs = nowMs
        textInputSourceLast = source
        signalsRing.addLast("+${delta}ms:$active:$source")
        while (signalsRing.size > SIGNALS_RING) signalsRing.removeFirst()
    }

    @Synchronized
    fun recordInputRestartReason(reason: String) {
        restartReasons[reason] = (restartReasons[reason] ?: 0L) + 1L
    }

    @Synchronized
    fun transitions(): List<String> = ring.toList()

    @Synchronized
    fun signalHistory(): List<String> = signalsRing.toList()

    @Synchronized
    fun formatRestartReasons(): String =
        if (restartReasons.isEmpty()) "none"
        else restartReasons.entries.joinToString(",") { "${it.key}=${it.value}" }

    @Synchronized
    fun reset() {
        listOf(
            showRequests, hideRequests, ignoredShowRequests, pendingShowCancelledCount,
            reconcileAttempts, createInputConnection, startInputSessions, commitTextCalls,
            commitTextChars, composingCalls, composingChars, finishComposingCalls,
            deleteSurroundingCalls, sendKeyEventCalls, charsDroppedInactive, keysDroppedInactive
        ).forEach { it.set(0) }
        desiredState = "hidden"
        engineTextInputActive = false
        textInputSourceLast = "none"
        autoShowSetting = false
        reconcileLastSource = "none"
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
        lastSignalMs = -1L
        ring.clear()
        signalsRing.clear()
        restartReasons.clear()
    }

    fun format(): String =
        "ime_desired_state=$desiredState ime_observed_visible=$imeVisibleObserved " +
            "auto_show_setting=$autoShowSetting " +
            "reconcile_attempts=${reconcileAttempts.get()} reconcile_last_source=$reconcileLastSource " +
            "pending_show_cancelled_count=${pendingShowCancelledCount.get()} " +
            "input_restart_reasons=[${formatRestartReasons()}] " +
            "text_input_source_last=$textInputSourceLast " +
            "text_input_signals_last20=[${signalHistory().joinToString(",")}] " +
            "chars_dropped_inactive=${charsDroppedInactive.get()} " +
            "keys_dropped_inactive=${keysDroppedInactive.get()} " +
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
 * Serialized IME state machine with bounded desired-vs-observed reconciliation.
 *
 * Policy (Stage L-B5):
 * - Manual-first: opening a text screen (chat/sign/etc.) never auto-shows the keyboard unless
 *   [autoShowProvider] explicitly returns true (default OFF). Instead, the UI reads
 *   `engineActive && !wanted` to pulse the overlay IME button.
 * - Single serialized queue: every show/hide/reconcile operation is scheduled through one [Scheduler].
 *   Requesting HIDDEN immediately cancels any pending show runnable (`pendingShowCancelledCount++`).
 * - Bounded reconciliation: whenever `WindowInsetsCompat` observed visibility disagrees with
 *   [desiredState], or the keyboard stays visible while `!engineActive`, the controller retries
 *   show/hide up to [MAX_RECONCILE_ATTEMPTS] times (`RECONCILE_DELAY_MS` apart) so races like
 *   `show` followed 97 ms later by `hide` can never leave the keyboard stuck open.
 */
class RustImeController(
    private val host: Host,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scheduler: Scheduler = MainLooperScheduler(),
    private val autoShowProvider: () -> Boolean = { false }
) {
    enum class DesiredState(val label: String) {
        HIDDEN("hidden"),
        SHOWN("shown")
    }

    interface Host {
        fun showIme()
        fun hideIme()
        fun onOverlayImeStateChanged(keepBehindIme: Boolean) {}
    }

    interface Cancellable {
        fun cancel()
    }

    interface Scheduler {
        fun post(delayMs: Long, task: Runnable): Cancellable
    }

    class MainLooperScheduler : Scheduler {
        private val handler by lazy { Handler(Looper.getMainLooper()) }
        override fun post(delayMs: Long, task: Runnable): Cancellable {
            if (delayMs <= 0L) {
                handler.post(task)
            } else {
                handler.postDelayed(task, delayMs)
            }
            return object : Cancellable {
                override fun cancel() {
                    handler.removeCallbacks(task)
                }
            }
        }
    }

    var desiredState: DesiredState = DesiredState.HIDDEN
        private set

    var engineActive: Boolean = false
        private set

    var lastEngineSource: String = "none"
        private set

    /** True while desiredState is SHOWN. */
    val wanted: Boolean
        get() = desiredState == DesiredState.SHOWN

    private var userDismissedForCurrentFocus: Boolean = false
    private var reconcileRetriesUsed: Int = 0
    private var pendingShowHandle: Cancellable? = null
    private var pendingReconcileHandle: Cancellable? = null

    fun onEngineSignal(active: Boolean, source: String = if (active) "other" else "none") {
        val normalizedSource = if (active) source.ifBlank { "other" } else "none"
        val autoShow = autoShowProvider()
        RustImeDiagnostics.autoShowSetting = autoShow
        if (active == engineActive && normalizedSource == lastEngineSource) {
            return
        }
        val now = clock()
        RustImeDiagnostics.recordEngineSignal(active, normalizedSource, now)
        RustImeDiagnostics.record("engine_signal=$active($normalizedSource)", now)
        val wasActive = engineActive
        engineActive = active
        lastEngineSource = normalizedSource
        RustImeDiagnostics.engineTextInputActive = active
        if (active) {
            if (!wasActive) {
                userDismissedForCurrentFocus = false
            }
            if (autoShow && !userDismissedForCurrentFocus && desiredState != DesiredState.SHOWN) {
                transitionToShow(ImeSource.ENGINE_SIGNAL)
            } else {
                syncOverlayPolicy()
            }
        } else {
            userDismissedForCurrentFocus = false
            transitionToHide(ImeSource.ENGINE_SIGNAL)
        }
    }

    fun onManualToggle() {
        val autoShow = autoShowProvider()
        RustImeDiagnostics.autoShowSetting = autoShow
        if (desiredState == DesiredState.SHOWN || RustImeDiagnostics.imeVisibleObserved) {
            if (engineActive) {
                userDismissedForCurrentFocus = true
            }
            transitionToHide(ImeSource.MANUAL_BUTTON)
        } else {
            userDismissedForCurrentFocus = false
            transitionToShow(ImeSource.MANUAL_BUTTON)
        }
    }

    fun onChatTap() {
        val autoShow = autoShowProvider()
        RustImeDiagnostics.autoShowSetting = autoShow
        if (autoShow && engineActive && !userDismissedForCurrentFocus && desiredState != DesiredState.SHOWN) {
            transitionToShow(ImeSource.ENGINE_SIGNAL)
        } else {
            onOtherRequest(ImeSource.CHAT_TAP)
        }
    }

    /** Gboard hide button or Back closed the keyboard: respect it and release IME focus. */
    fun onUserDismissed() {
        if (desiredState == DesiredState.HIDDEN && !RustImeDiagnostics.imeVisibleObserved) return
        if (engineActive) {
            userDismissedForCurrentFocus = true
        }
        transitionToHide(ImeSource.USER_DISMISS)
    }

    /** Text arrived from IME while engineActive == false: force hide if keyboard is stuck open. */
    fun onTextWhileEngineInactive() {
        if (engineActive) return
        if (desiredState == DesiredState.SHOWN || RustImeDiagnostics.imeVisibleObserved) {
            transitionToHide(ImeSource.RECONCILE, reconcileReason = "reconcile_inactive_hide")
        }
    }

    /** Any non-sanctioned caller lands here; it never shows the keyboard. */
    fun onOtherRequest(source: ImeSource) {
        require(!source.mayShow)
        RustImeDiagnostics.ignoredShowRequests.incrementAndGet()
        RustImeDiagnostics.record("ignored_show_from=${source.label}", clock())
    }

    /** Insets truth source. Triggers bounded reconciliation whenever observed disagrees with desired. */
    fun onImeVisibility(visible: Boolean) {
        val before = RustImeDiagnostics.imeVisibleObserved
        if (before != visible) {
            RustImeDiagnostics.record("ime_visible=$visible", clock())
        }
        RustImeDiagnostics.imeVisibleObserved = visible

        if (before && !visible && desiredState == DesiredState.SHOWN) {
            // User dismissed the keyboard cleanly via Gboard's hide button.
            onUserDismissed()
            return
        }

        if (visible && !engineActive && desiredState == DesiredState.HIDDEN) {
            scheduleReconcile("reconcile_inactive_hide")
            syncOverlayPolicy()
            return
        }

        val matches = (desiredState == DesiredState.SHOWN) == visible
        if (matches) {
            clearPendingReconcile()
            reconcileRetriesUsed = 0
            syncOverlayPolicy()
        } else if (visible && desiredState == DesiredState.HIDDEN) {
            scheduleReconcile("reconcile_hide")
            syncOverlayPolicy()
        }
    }

    private fun transitionToShow(source: ImeSource) {
        if (!source.mayShow) {
            onOtherRequest(source)
            return
        }
        clearPendingReconcile()
        cancelPendingShow(countCancelled = false)
        reconcileRetriesUsed = 0
        desiredState = DesiredState.SHOWN
        RustImeDiagnostics.desiredState = desiredState.label
        syncOverlayPolicy()
        val now = clock()
        RustImeDiagnostics.showRequests.incrementAndGet()
        RustImeDiagnostics.record("show:${source.label}", now)

        var handle: Cancellable? = null
        val runnable = Runnable {
            if (pendingShowHandle === handle) {
                pendingShowHandle = null
            }
            if (desiredState != DesiredState.SHOWN) {
                return@Runnable
            }
            host.showIme()
            scheduleReconcile("reconcile_show", initialDelayMs = RECONCILE_INITIAL_SHOW_CHECK_MS)
        }
        handle = scheduler.post(0L, runnable)
        pendingShowHandle = handle
    }

    private fun transitionToHide(source: ImeSource, reconcileReason: String = "reconcile_hide") {
        cancelPendingShow(countCancelled = true)
        clearPendingReconcile()
        val wasDesiredShown = desiredState == DesiredState.SHOWN
        val wasVisible = RustImeDiagnostics.imeVisibleObserved
        desiredState = DesiredState.HIDDEN
        RustImeDiagnostics.desiredState = desiredState.label
        reconcileRetriesUsed = 0
        if (wasDesiredShown || wasVisible) {
            val now = clock()
            RustImeDiagnostics.hideRequests.incrementAndGet()
            RustImeDiagnostics.record("hide:${source.label}", now)
            host.hideIme()
            scheduleReconcile(reconcileReason, initialDelayMs = RECONCILE_DELAY_MS)
        }
        syncOverlayPolicy()
    }

    private fun cancelPendingShow(countCancelled: Boolean) {
        val handle = pendingShowHandle ?: return
        pendingShowHandle = null
        handle.cancel()
        if (countCancelled) {
            RustImeDiagnostics.pendingShowCancelledCount.incrementAndGet()
            RustImeDiagnostics.record("pending_show_cancelled", clock())
        }
    }

    private fun clearPendingReconcile() {
        pendingReconcileHandle?.cancel()
        pendingReconcileHandle = null
    }

    private fun scheduleReconcile(reason: String, initialDelayMs: Long = RECONCILE_DELAY_MS) {
        if (reconcileRetriesUsed >= MAX_RECONCILE_ATTEMPTS) return
        clearPendingReconcile()
        val task = object : Runnable {
            override fun run() {
                pendingReconcileHandle = null
                val observed = RustImeDiagnostics.imeVisibleObserved
                val wantShown = desiredState == DesiredState.SHOWN
                if (wantShown == observed) {
                    reconcileRetriesUsed = 0
                    syncOverlayPolicy()
                    return
                }
                if (reconcileRetriesUsed >= MAX_RECONCILE_ATTEMPTS) {
                    syncOverlayPolicy()
                    return
                }
                reconcileRetriesUsed++
                RustImeDiagnostics.reconcileAttempts.incrementAndGet()
                RustImeDiagnostics.reconcileLastSource = reason
                RustImeDiagnostics.record(
                    "reconcile($reason,#$reconcileRetriesUsed desired=${desiredState.label} observed=$observed)",
                    clock()
                )
                if (wantShown && !observed) {
                    host.showIme()
                } else if (!wantShown && observed) {
                    host.hideIme()
                }
                syncOverlayPolicy()
                if (reconcileRetriesUsed < MAX_RECONCILE_ATTEMPTS) {
                    pendingReconcileHandle = scheduler.post(RECONCILE_DELAY_MS, this)
                }
            }
        }
        pendingReconcileHandle = scheduler.post(initialDelayMs, task)
    }

    private fun syncOverlayPolicy() {
        host.onOverlayImeStateChanged(
            keepBehindIme = desiredState == DesiredState.SHOWN || RustImeDiagnostics.imeVisibleObserved
        )
    }

    companion object {
        const val MAX_RECONCILE_ATTEMPTS = 3
        const val RECONCILE_DELAY_MS = 140L
        const val RECONCILE_INITIAL_SHOW_CHECK_MS = 180L
    }
}
