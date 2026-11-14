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
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package io.github.kaito65452.blazelauncher.rustruntime

import android.app.NativeActivity
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.horizon.android.bridge.BlazeNativeBridge

/**
 * Phase 0/1a spike hosting the Starling/Horizon NativeActivity engine in `android:process=":rust"`,
 * with session handoff, synthetic touch probing, and a lightweight Kotlin overlay attached via
 * [addContentView].
 *
 * Plan A uses winit's `android-native-activity` backend inside `:rust`. Because winit permits only one
 * EventLoop per process, leaving this Activity explicitly terminates the `:rust` child process.
 */
class RustActivity : NativeActivity() {
    private var initialProbeDispatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val pid = Process.myPid()
        Log.i(TAG, "Starting RustActivity Phase 1a spike in pid=$pid")
        RustDiagnosticsCollector.recordRustEvent(this, "onCreate pid=$pid")
        super.onCreate(savedInstanceState)
        val sessionResult = RustSessionHandoff.consumePendingSessionInRustProcess(this)
        RustDiagnosticsCollector.recordRustEvent(this, "session_handoff: ${sessionResult.summary}")
        attachSpikeOverlay()
        triggerSyntheticTouchProbe(source = "onCreate_post")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !initialProbeDispatched) {
            initialProbeDispatched = true
            triggerSyntheticTouchProbe(source = "window_focus")
        }
    }

    private fun triggerSyntheticTouchProbe(source: String) {
        val x = 120f
        val y = 240f
        val bridgeStarted = runCatching { BlazeNativeBridge.injectSyntheticTouch(x, y, 0) }
            .getOrDefault(false)
        val bridgeEnded = runCatching { BlazeNativeBridge.injectSyntheticTouch(x, y, 2) }
            .getOrDefault(false)
        val posted = runCatching {
            window.decorView.post {
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(now, now + 10L, MotionEvent.ACTION_UP, x, y, 0)
                try {
                    window.decorView.dispatchTouchEvent(down)
                    window.decorView.dispatchTouchEvent(up)
                } finally {
                    down.recycle()
                    up.recycle()
                }
            }
        }.getOrDefault(false)
        val summary = "synthetic_touch($source): bridgeStart=$bridgeStarted bridgeEnd=$bridgeEnded decorPost=$posted"
        Log.i(TAG, summary)
        RustDiagnosticsCollector.recordRustEvent(this, summary)
    }

    private fun attachSpikeOverlay() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val container = FrameLayout(this)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(190, 20, 22, 30))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        val label = TextView(this).apply {
            text = "Starling Spike (:rust pid=${Process.myPid()})"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        bar.addView(label)

        val touchProbeButton = Button(this).apply {
            text = "Touch Probe"
            textSize = 11f
            setOnClickListener {
                triggerSyntheticTouchProbe(source = "overlay_button")
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(10)
        }
        bar.addView(touchProbeButton, btnParams)

        val exitButton = Button(this).apply {
            text = "Exit :rust"
            textSize = 11f
            setOnClickListener {
                exitRustProcess()
            }
        }
        bar.addView(exitButton, btnParams)

        val barParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(12)
            marginEnd = dp(12)
        }
        container.addView(bar, barParams)

        addContentView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        Log.i(TAG, "Attached Kotlin addContentView overlay on top of NativeActivity")
    }

    private fun exitRustProcess() {
        val pid = Process.myPid()
        Log.i(TAG, "Signaling engine exit and terminating :rust pid=$pid")
        RustDiagnosticsCollector.recordRustEvent(this, "exit_requested pid=$pid")
        runCatching { BlazeNativeBridge.signalEngineExit() }
        finishAndRemoveTask()
        Process.killProcess(pid)
    }

    override fun onDestroy() {
        val pid = Process.myPid()
        RustDiagnosticsCollector.recordRustEvent(this, "onDestroy finishing=$isFinishing pid=$pid")
        super.onDestroy()
        if (isFinishing) {
            Log.i(TAG, "RustActivity finishing; terminating :rust pid=$pid")
            Process.killProcess(pid)
        }
    }

    companion object {
        private const val TAG = "BlazeRustSpike"
    }
}
