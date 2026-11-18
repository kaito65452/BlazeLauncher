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

    fun reset() {
        pendingBeforeSlice = ""
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
 * Real, IME-focusable 1x1 EditText hosted in `RustActivity`'s main window (never the overlay
 * sub-window). Same proven approach as ZL2's TouchCharInput (filler text + TextWatcher diff), but
 * with a swipe-friendly input type (no NO_SUGGESTIONS / FILTER) and an InputConnection wrapper
 * that counts what the keyboard delivers (counts and lengths only, never the text).
 */
class BlazeImeEditText(context: Context) : AppCompatEditText(context) {
    private val processor = RustImeTextProcessor()
    private var internalChange = false
    private var imeActive = false

    var onEnterAction: (() -> Unit)? = null
    var onImeBackPressed: (() -> Unit)? = null
    var onImeShown: (() -> Unit)? = null
    var hostWindow: android.view.Window? = null

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
                processor.onTextChanged(s, start, count, BlazeInputRouter.currentSink())
            }

            override fun afterTextChanged(s: Editable?) {
                if (internalChange) return
                // Keep the buffer topped up so backspace always has something to delete,
                // but never while the keyboard is still composing a word.
                if (s != null && !hasComposing(s) &&
                    (s.length < TEXT_FILLER.length / 2 || s.toString().indexOf('\n') >= 0)
                ) {
                    resetBuffer()
                }
            }
        })

        resetBuffer()
    }

    private fun hasComposing(s: Editable): Boolean =
        android.view.inputmethod.BaseInputConnection.getComposingSpanStart(s) >= 0

    private fun sendEnter() {
        BlazeInputRouter.currentSink().sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ENTER.toInt())
        resetBuffer()
        onEnterAction?.invoke()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        RustImeDiagnostics.createInputConnection.incrementAndGet()
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        RustImeDiagnostics.editorInputType = outAttrs.inputType
        RustImeDiagnostics.editorImeOptions = outAttrs.imeOptions
        RustImeDiagnostics.record("create_input_connection")
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
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                RustImeDiagnostics.sendKeyEventCalls.incrementAndGet()
                // Enter arrives either here or as an editor action, never both: handle it once.
                if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.action == KeyEvent.ACTION_DOWN) sendEnter()
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    /** Make this view the IME target and ask for the keyboard (called only by RustImeController). */
    fun enableIme() {
        imeActive = true
        resetBuffer()
        isEnabled = true
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        post {
            if (!imeActive) return@post
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return@post
            imm.restartInput(this)
            hostWindow?.let { WindowCompat.getInsetsController(it, this).show(WindowInsetsCompat.Type.ime()) }
            if (imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)) {
                onImeShown?.invoke()
            }
            refreshDiagnostics()
        }
    }

    fun disableIme(restoreFocusView: View? = null) {
        imeActive = false
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        hostWindow?.let { WindowCompat.getInsetsController(it, this).hide(WindowInsetsCompat.Type.ime()) }
        clearFocus()
        isFocusable = false
        isFocusableInTouchMode = false
        resetBuffer()
        restoreFocusView?.requestFocus()
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
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.action == KeyEvent.ACTION_UP && imeActive) {
            onImeBackPressed?.invoke()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    fun resetBuffer() {
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
        const val TEXT_FILLER = "                              "
    }
}
