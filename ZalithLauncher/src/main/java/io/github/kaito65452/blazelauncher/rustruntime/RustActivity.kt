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
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.horizon.android.bridge.BlazeNativeBridge

/**
 * Phase 0 throwaway spike hosting the Starling/Horizon NativeActivity engine in `android:process=":rust"`,
 * with a lightweight Kotlin overlay attached via [addContentView].
 *
 * Plan A uses winit's `android-native-activity` backend inside `:rust`. Because winit permits only one
 * EventLoop per process, leaving this Activity explicitly terminates the `:rust` child process.
 */
class RustActivity : NativeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "Starting RustActivity Phase 0 spike in pid=${Process.myPid()}")
        super.onCreate(savedInstanceState)
        attachSpikeOverlay()
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

        val exitButton = Button(this).apply {
            text = "Exit :rust"
            textSize = 11f
            setOnClickListener {
                exitRustProcess()
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(10)
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
        Log.i(TAG, "Signaling engine exit and terminating :rust pid=${Process.myPid()}")
        runCatching { BlazeNativeBridge.signalEngineExit() }
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            Log.i(TAG, "RustActivity finishing; terminating :rust pid=${Process.myPid()}")
            Process.killProcess(Process.myPid())
        }
    }

    companion object {
        private const val TAG = "BlazeRustSpike"
    }
}
