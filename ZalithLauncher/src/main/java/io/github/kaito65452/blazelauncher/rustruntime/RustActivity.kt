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

import android.app.Activity
import android.app.NativeActivity
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.horizon.android.bridge.BlazeNativeBridge
import kotlin.math.roundToInt

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
    private var directLaunchMode = false

    private external fun nativeDocumentResult(requestCode: Int, resultCode: Int, uriString: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        directLaunchMode = intent?.getBooleanExtra(EXTRA_DIRECT_LAUNCH, false) == true
        val pid = Process.myPid()
        Log.i(TAG, "Starting RustActivity pid=$pid directLaunch=$directLaunchMode")
        RustDiagnosticsCollector.recordRustEvent(
            this,
            "onCreate pid=$pid directLaunch=$directLaunchMode"
        )
        configureDisplayCutoutMode()
        // Consume session and set direct-launch mode BEFORE super.onCreate starts android_main.
        val sessionResult = RustSessionHandoff.consumePendingSessionInRustProcess(this)
        RustDiagnosticsCollector.recordRustEvent(this, "session_handoff: ${sessionResult.summary}")
        BlazeNativeBridge.setDirectLaunch(directLaunchMode)
        super.onCreate(savedInstanceState)
        if (!directLaunchMode) {
            attachSpikeOverlay()
            triggerSyntheticTouchProbe(source = "onCreate_post")
        }
    }

    private fun configureDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DOCUMENT_PICKER_REQUEST_CODE) return

        val sanitizedUri = sanitizeDocumentPickerUri(resultCode, data?.dataString)
        if (sanitizedUri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    Uri.parse(sanitizedUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val effectiveResult =
            if (resultCode == Activity.RESULT_OK && sanitizedUri == null) {
                Activity.RESULT_CANCELED
            } else {
                resultCode
            }
        val jniDelivered = try {
            nativeDocumentResult(requestCode, effectiveResult, sanitizedUri)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        val msg = "document_result req=$requestCode res=$effectiveResult uriPresent=${sanitizedUri != null} jniDelivered=$jniDelivered"
        Log.i(TAG, msg)
        RustDiagnosticsCollector.recordRustEvent(this, msg)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !initialProbeDispatched && !directLaunchMode) {
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
        applySafeDisplayInsets(container)
        applyGestureExclusionOnLayout(container, density)
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
        container.requestApplyInsets()
        Log.i(TAG, "Attached Kotlin addContentView overlay on top of NativeActivity")
    }

    @Suppress("DEPRECATION")
    private fun applySafeDisplayInsets(container: FrameLayout) {
        container.setOnApplyWindowInsetsListener { view, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                left = safe.left
                top = safe.top
                right = safe.right
                bottom = safe.bottom
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cutout = insets.displayCutout
                left = maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0)
                top = maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0)
                right = maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0)
                bottom = maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0)
            } else {
                left = insets.systemWindowInsetLeft
                top = insets.systemWindowInsetTop
                right = insets.systemWindowInsetRight
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(left, top, right, bottom)
            insets
        }
    }

    private fun applyGestureExclusionOnLayout(view: View, density: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        fun updateExclusion(target: View) {
            val strips = computeSideGestureExclusionStrips(target.width, target.height, density)
            if (strips.isNotEmpty()) {
                target.systemGestureExclusionRects = strips.map { Rect(it.left, it.top, it.right, it.bottom) }
            }
        }
        view.addOnLayoutChangeListener { target, _, _, _, _, _, _, _, _ ->
            updateExclusion(target)
        }
        view.post { updateExclusion(view) }
    }

    private fun exitRustProcess() {
        val pid = Process.myPid()
        Log.i(TAG, "Signaling engine exit and terminating :rust pid=$pid")
        RustDiagnosticsCollector.recordRustEvent(this, "exit_requested pid=$pid")
        runCatching { BlazeNativeBridge.signalEngineExit() }
        RustSessionHandoff.clearPendingSessionFiles(this)
        finishAndRemoveTask()
        Process.killProcess(pid)
    }

    override fun onDestroy() {
        val pid = Process.myPid()
        RustDiagnosticsCollector.recordRustEvent(this, "onDestroy finishing=$isFinishing pid=$pid")
        super.onDestroy()
        if (isFinishing) {
            Log.i(TAG, "RustActivity finishing; clearing session & terminating :rust pid=$pid")
            runCatching { BlazeNativeBridge.signalEngineExit() }
            RustSessionHandoff.clearPendingSessionFiles(this)
            Process.killProcess(pid)
        }
    }

    internal data class GestureStripBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val height: Int get() = bottom - top
    }

    companion object {
        private const val TAG = "BlazeRustSpike"
        internal const val EXTRA_DIRECT_LAUNCH = "io.github.kaito65452.blazelauncher.EXTRA_DIRECT_LAUNCH"
        internal const val DOCUMENT_PICKER_REQUEST_CODE = 4101
        internal const val MAX_DOCUMENT_URI_LENGTH = 8192
        internal const val MAX_SYSTEM_GESTURE_STRIP_DP = 200
        private const val EDGE_STRIP_WIDTH_DP = 24f

        internal fun sanitizeDocumentPickerUri(resultCode: Int, uriString: String?): String? {
            if (resultCode != Activity.RESULT_OK) return null
            val candidate = uriString?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (!candidate.startsWith("content://") || candidate.length > MAX_DOCUMENT_URI_LENGTH) {
                return null
            }
            return candidate
        }

        internal fun computeSideGestureExclusionStrips(
            widthPx: Int,
            heightPx: Int,
            density: Float
        ): List<GestureStripBounds> {
            if (widthPx <= 0 || heightPx <= 0 || !density.isFinite() || density <= 0f) {
                return emptyList()
            }
            val maxStripHeightPx = (MAX_SYSTEM_GESTURE_STRIP_DP * density).toInt().coerceAtLeast(1)
            val stripHeight = minOf(heightPx, maxStripHeightPx)
            val top = ((heightPx - stripHeight) / 2).coerceAtLeast(0)
            val bottom = top + stripHeight
            val edgeWidthPx = minOf(
                widthPx,
                maxOf(1, (EDGE_STRIP_WIDTH_DP * density).roundToInt()).coerceAtMost(widthPx / 4 + 1)
            )
            if (edgeWidthPx * 2 >= widthPx) {
                return listOf(GestureStripBounds(0, top, widthPx, bottom))
            }
            return listOf(
                GestureStripBounds(0, top, edgeWidthPx, bottom),
                GestureStripBounds(widthPx - edgeWidthPx, top, widthPx, bottom)
            )
        }
    }
}
