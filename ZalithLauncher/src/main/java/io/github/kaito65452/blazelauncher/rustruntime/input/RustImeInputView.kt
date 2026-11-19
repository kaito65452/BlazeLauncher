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

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.annotation.Keep
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import com.movtery.inputmap.keycodes.LwjglGlfwKeycode

/**
 * Pure-Kotlin IME text diff processor for Rust Edition.
 * Computes the minimal codepoint-level edit (backspaces + committed codepoints/newlines) between
 * composing or pasted snapshots so Gboard/Samsung composing, Unicode/emoji, backspace, Enter,
 * and clipboard paste all reach `BlazeInputSink.sendCodepoint` without splitting surrogate pairs.
 */
class RustImeTextProcessor {
    private var pendingBeforeSlice: String = ""
    private var composingShadow: String = ""

    fun onBeforeTextChanged(s: CharSequence?, start: Int, count: Int) {
        if (s == null || count <= 0 || start < 0 || start + count > s.length) {
            pendingBeforeSlice = ""
            return
        }
        pendingBeforeSlice = s.subSequence(start, start + count).toString()
    }

    fun onTextChanged(s: CharSequence?, start: Int, count: Int, sink: BlazeInputSink): Boolean {
        val afterSlice = if (s != null && count > 0 && start >= 0 && start + count <= s.length) {
            s.subSequence(start, start + count).toString()
        } else {
            ""
        }
        val beforeSlice = pendingBeforeSlice
        pendingBeforeSlice = ""
        return applyDiff(beforeSlice, afterSlice, sink)
    }

    /** Shadow-buffer composing step: sends minimal backspaces + new codepoints against prior composing. */
    fun onSetComposingText(text: CharSequence?, sink: BlazeInputSink): Boolean {
        val next = text?.toString().orEmpty()
        val prev = composingShadow
        composingShadow = next
        return applyDiff(prev, next, sink)
    }

    /** Commits text (replacing any active shadow composition) and clears composing state. */
    fun onCommitText(text: CharSequence?, sink: BlazeInputSink): Boolean {
        val committed = text?.toString().orEmpty()
        val prev = composingShadow
        composingShadow = ""
        return applyDiff(prev, committed, sink)
    }

    /** Finalizes current shadow composition without re-emitting already sent codepoints. */
    fun onFinishComposingText() {
        composingShadow = ""
    }

    /** Deletes codepoints before cursor (trimming shadow composition if active). */
    fun onDeleteSurroundingCodepoints(beforeCount: Int, sink: BlazeInputSink): Boolean {
        if (beforeCount <= 0) return false
        for (i in 0 until beforeCount) {
            sink.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_BACKSPACE.toInt())
        }
        if (composingShadow.isNotEmpty()) {
            val cps = toCodepoints(composingShadow)
            val keep = (cps.size - beforeCount).coerceAtLeast(0)
            composingShadow = if (keep == 0) {
                ""
            } else {
                buildString {
                    for (i in 0 until keep) appendCodePoint(cps[i])
                }
            }
        }
        return true
    }

    fun reset() {
        pendingBeforeSlice = ""
        composingShadow = ""
    }

    companion object {
        fun applyDiff(beforeSlice: String, afterSlice: String, sink: BlazeInputSink): Boolean {
            if (beforeSlice == afterSlice) return false
            val beforeCps = toCodepoints(beforeSlice)
            val afterCps = toCodepoints(afterSlice)
            var prefix = 0
            val minLen = minOf(beforeCps.size, afterCps.size)
            while (prefix < minLen && beforeCps[prefix] == afterCps[prefix]) {
                prefix++
            }
            val backspaces = beforeCps.size - prefix
            for (i in 0 until backspaces) {
                sink.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_BACKSPACE.toInt())
            }
            var emitted = backspaces > 0
            for (i in prefix until afterCps.size) {
                val cp = afterCps[i]
                if (cp == '\n'.code) {
                    sink.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ENTER.toInt())
                    emitted = true
                } else if (!Character.isISOControl(cp)) {
                    sink.sendCodepoint(cp)
                    emitted = true
                }
            }
            return emitted
        }

        private fun toCodepoints(text: String): IntArray {
            if (text.isEmpty()) return IntArray(0)
            val count = text.codePointCount(0, text.length)
            val out = IntArray(count)
            var offset = 0
            var idx = 0
            while (offset < text.length) {
                val cp = text.codePointAt(offset)
                out[idx++] = cp
                offset += Character.charCount(cp)
            }
            return out
        }
    }
}

/**
 * Wraps a [BlazeInputSink] and drops IME character/key deliveries whenever the engine does not
 * have an active text field, recording `chars_dropped_inactive` / `keys_dropped_inactive`.
 */
class InactiveDropCountingSink(
    private val delegate: BlazeInputSink,
    private val isEngineActive: () -> Boolean,
    private val onInactiveDropped: () -> Unit = {}
) : BlazeInputSink by delegate {
    override fun sendChar(keychar: Char, modifiers: Int) {
        if (!isEngineActive()) {
            RustImeDiagnostics.charsDroppedInactive.incrementAndGet()
            onInactiveDropped()
            return
        }
        delegate.sendChar(keychar, modifiers)
    }

    override fun sendCodepoint(codepoint: Int) {
        if (!isEngineActive()) {
            RustImeDiagnostics.charsDroppedInactive.incrementAndGet()
            onInactiveDropped()
            return
        }
        delegate.sendCodepoint(codepoint)
    }

    override fun sendKeycode(keycode: Int, keychar: Char, scancode: Int, modifiers: Int, isDown: Boolean) {
        if (!isEngineActive()) {
            if (isDown) {
                RustImeDiagnostics.keysDroppedInactive.incrementAndGet()
            }
            onInactiveDropped()
            return
        }
        delegate.sendKeycode(keycode, keychar, scancode, modifiers, isDown)
    }

    override fun sendKeyPress(keyCode: Int, scancode: Int, modifiers: Int, status: Boolean) {
        sendKeycode(keyCode, '\u0000', scancode, modifiers, status)
    }

    override fun sendKeyPress(keyCode: Int, modifiers: Int, status: Boolean) {
        sendKeycode(keyCode, '\u0000', 0, modifiers, status)
    }

    override fun sendKeyPress(keyCode: Int) {
        val mods = delegate.getCurrentMods()
        sendKeycode(keyCode, '\u0000', 0, mods, true)
        sendKeycode(keyCode, '\u0000', 0, mods, false)
    }
}

/**
 * Real, IME-focusable 1x1 EditText hosted in `RustActivity`'s main window (never the overlay
 * sub-window). Avoids calling `setText()` while focused so IMM does not restart InputConnection
 * mid-session (`on_create_input_connection` stays ~1 per show), tracks restart reasons, and drops
 * text arriving while `!isEngineActive()`.
 */
@Keep
class BlazeImeEditText(context: Context) : AppCompatEditText(context) {
    private val processor = RustImeTextProcessor()
    private var internalChange = false
    private var imeActive = false
    private var pendingRestartReason: String? = null

    var onEnterAction: (() -> Unit)? = null
    var onImeBackPressed: (() -> Unit)? = null
    var onImeShown: (() -> Unit)? = null
    var onInactiveTextInput: (() -> Unit)? = null
    var isEngineActive: () -> Boolean = { true }
    var hostWindow: android.view.Window? = null

    private fun imeSink(): BlazeInputSink = InactiveDropCountingSink(
        delegate = BlazeInputRouter.currentSink(),
        isEngineActive = isEngineActive,
        onInactiveDropped = { onInactiveTextInput?.invoke() }
    )

    init {
        alpha = 0f
        isSaveEnabled = false
        setBackgroundDrawable(null)
        setPadding(0, 0, 0, 0)
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_SEND
        isFocusable = false
        isFocusableInTouchMode = false

        setOnEditorActionListener { _, actionId, event ->
            val isAction = actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_UNSPECIFIED ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isAction) {
                sendEnter()
                true
            } else {
                false
            }
        }

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (internalChange) return
                processor.onBeforeTextChanged(s, start, count)
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (internalChange) return
                processor.onTextChanged(s, start, count, imeSink())
            }

            override fun afterTextChanged(s: Editable?) {
                if (internalChange || s == null) return
                if (hasComposing(s)) return
                // Mutate the Editable in place instead of setText() so IMM never calls restartInput().
                val newlineIdx = s.indexOf('\n')
                if (newlineIdx >= 0) {
                    internalChange = true
                    try {
                        s.delete(newlineIdx, newlineIdx + 1)
                    } finally {
                        internalChange = false
                    }
                }
                if (s.length <= 2) {
                    internalChange = true
                    try {
                        s.insert(0, TEXT_FILLER)
                    } finally {
                        internalChange = false
                    }
                }
            }
        })

        resetBufferIfUnfocused()
    }

    private fun hasComposing(s: Editable): Boolean =
        android.view.inputmethod.BaseInputConnection.getComposingSpanStart(s) >= 0

    private fun sendEnter() {
        imeSink().sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ENTER.toInt())
        if (!hasFocus()) {
            resetBufferIfUnfocused()
        }
        onEnterAction?.invoke()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        RustImeDiagnostics.createInputConnection.incrementAndGet()
        RustImeDiagnostics.startInputSessions.incrementAndGet()
        val reason = pendingRestartReason ?: "system_or_gboard"
        pendingRestartReason = null
        RustImeDiagnostics.recordInputRestartReason(reason)
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        RustImeDiagnostics.editorInputType = outAttrs.inputType
        RustImeDiagnostics.editorImeOptions = outAttrs.imeOptions
        RustImeDiagnostics.record("create_input_connection:$reason")
        refreshDiagnostics()
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                RustImeDiagnostics.commitTextCalls.incrementAndGet()
                RustImeDiagnostics.commitTextChars.addAndGet((text?.length ?: 0).toLong())
                return super.commitText(text, newCursorPosition)
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                RustImeDiagnostics.composingCalls.incrementAndGet()
                RustImeDiagnostics.composingChars.addAndGet((text?.length ?: 0).toLong())
                return super.setComposingText(text, newCursorPosition)
            }

            override fun finishComposingText(): Boolean {
                RustImeDiagnostics.finishComposingCalls.incrementAndGet()
                return super.finishComposingText()
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                RustImeDiagnostics.deleteSurroundingCalls.incrementAndGet()
                if (beforeLength > 0 && selectionStart <= 0) {
                    val sink = imeSink()
                    for (i in 0 until beforeLength) {
                        sink.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_BACKSPACE.toInt())
                    }
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                RustImeDiagnostics.sendKeyEventCalls.incrementAndGet()
                // Enter arrives either here or as an editor action, never both: handle it once.
                if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.action == KeyEvent.ACTION_DOWN) sendEnter()
                    return true
                }
                // Handle soft-keyboard Backspace here so it never double-dispatches via Activity.dispatchKeyEvent.
                if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val sel = selectionStart
                        val currentText = text
                        if (currentText != null && sel > 0) {
                            val cp = Character.codePointBefore(currentText, sel)
                            val charCount = Character.charCount(cp)
                            deleteSurroundingText(charCount, 0)
                        } else {
                            imeSink().sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_BACKSPACE.toInt())
                        }
                    }
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    /** Make this view the IME target and ask for the keyboard (called only by RustImeController). */
    internal fun enableIme(nativeView: View? = null) {
        val alreadyFocused = imeActive && hasFocus()
        imeActive = true
        if (!alreadyFocused) {
            resetBufferIfUnfocused()
            hostWindow?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            )
            nativeView?.clearFocus()
            visibility = View.VISIBLE
            isEnabled = true
            isFocusable = true
            isFocusableInTouchMode = true
            pendingRestartReason = "session_start"
            requestFocus()
        } else {
            pendingRestartReason = "reconcile_show"
        }

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        // Only restartInput when Android hasn't already bound an InputConnection for this view.
        if (!imm.isActive(this)) {
            if (pendingRestartReason == null) {
                pendingRestartReason = "imm_inactive"
            }
            imm.restartInput(this)
        } else if (!alreadyFocused) {
            pendingRestartReason = null
        }
        hostWindow?.let { WindowCompat.getInsetsController(it, this).show(WindowInsetsCompat.Type.ime()) }
        if (imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)) {
            onImeShown?.invoke()
        }
        refreshDiagnostics()
    }

    internal fun disableIme(restoreFocusView: View? = null) {
        imeActive = false
        pendingRestartReason = null
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        // Hide using the active IME target's window token BEFORE releasing focus from this view.
        val token = windowToken ?: hostWindow?.decorView?.windowToken
        if (token != null) {
            imm?.hideSoftInputFromWindow(token, 0)
        }
        hostWindow?.let {
            WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        clearFocus()
        isFocusable = false
        isFocusableInTouchMode = false
        resetBufferIfUnfocused()
        restoreFocusView?.requestFocus()
        refreshDiagnostics()
    }

    /** UI-thread snapshot for the Copy report. */
    fun refreshDiagnostics() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        RustImeDiagnostics.targetViewClass = javaClass.simpleName
        RustImeDiagnostics.viewHasFocus = hasFocus()
        RustImeDiagnostics.windowHasFocus = hasWindowFocus()
        RustImeDiagnostics.windowFlags = hostWindow?.attributes?.flags ?: 0
        RustImeDiagnostics.immActive = imm?.isActive(this) == true
        RustImeDiagnostics.immAcceptingText = imm?.isAcceptingText == true
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.action == KeyEvent.ACTION_UP &&
            (imeActive || RustImeDiagnostics.imeVisibleObserved)
        ) {
            onImeBackPressed?.invoke()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    private fun resetBufferIfUnfocused() {
        if (hasFocus()) return
        internalChange = true
        try {
            processor.reset()
            setText(TEXT_FILLER)
            setSelection(TEXT_FILLER.length)
        } finally {
            internalChange = false
        }
    }

    companion object {
        const val TEXT_FILLER = "                                                            "
    }
}
