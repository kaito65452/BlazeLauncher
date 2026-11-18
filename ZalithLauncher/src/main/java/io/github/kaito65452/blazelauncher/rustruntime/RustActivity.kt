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
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.annotation.Keep
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.movtery.inputmap.keycodes.LwjglGlfwKeycode
import com.movtery.zalithlauncher.game.input.EfficientAndroidLWJGLKeycode
import com.movtery.zalithlauncher.game.input.LWJGLCharSender
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.ui.control.gamepad.isGamepadKeyEvent
import com.movtery.zalithlauncher.ui.control.input.TextInputMode
import com.movtery.zalithlauncher.ui.theme.ZalithLauncherTheme
import com.movtery.zalithlauncher.utils.device.PhysicalMouseChecker
import com.movtery.zalithlauncher.viewmodel.GamepadViewModel
import dev.horizon.android.bridge.BlazeNativeBridge
import io.github.kaito65452.blazelauncher.rustruntime.input.BlazeImeEditText
import io.github.kaito65452.blazelauncher.rustruntime.input.BlazeInputRouter
import io.github.kaito65452.blazelauncher.rustruntime.input.RustGameOverlay
import io.github.kaito65452.blazelauncher.rustruntime.input.RustInputDiagnostics
import io.github.kaito65452.blazelauncher.rustruntime.input.RustTouchRouting
import io.github.kaito65452.blazelauncher.rustruntime.input.RustInputBridge
import java.io.File
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
    private var desktopOverlayAttached = false
    private var overlayRoot: DesktopOverlayRoot? = null
    private var overlayComposeView: ComposeView? = null
    private var overlayWindowAdded = false
    private var imeEditText: BlazeImeEditText? = null
    @Volatile
    private var engineTextInputActive = false
    private var lastImeVisible = false
    private var textInputMode by mutableStateOf(TextInputMode.DISABLE)
    private var activeLayoutFile by mutableStateOf<File?>(null)
    private val composeHost by lazy { NativeActivityComposeHost() }
    private val gamepadViewModel by lazy { GamepadViewModel() }

    private external fun nativeDocumentResult(requestCode: Int, resultCode: Int, uriString: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        directLaunchMode = intent?.getBooleanExtra(EXTRA_DIRECT_LAUNCH, false) == true
        val useEngineTouchFallback = runCatching { AllSettings.rustUseEngineTouchFallback.getValue() }
            .getOrDefault(false)
        val desktopInputActive = directLaunchMode && !useEngineTouchFallback
        activeLayoutFile = intent?.getStringExtra(EXTRA_CONTROL_LAYOUT_PATH)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile }

        val pid = Process.myPid()
        Log.i(
            TAG,
            "Starting RustActivity pid=$pid directLaunch=$directLaunchMode " +
                "desktopInput=$desktopInputActive fallback=$useEngineTouchFallback savedState=${savedInstanceState != null}"
        )
        RustDiagnosticsCollector.recordRustEvent(
            this,
            "onCreate pid=$pid directLaunch=$directLaunchMode desktopInput=$desktopInputActive savedState=${savedInstanceState != null}"
        )
        configureDisplayCutoutMode()
        // Consume session and set direct-launch mode BEFORE super.onCreate starts android_main.
        val sessionResult = RustSessionHandoff.consumePendingSessionInRustProcess(this)
        RustDiagnosticsCollector.recordRustEvent(this, "session_handoff: ${sessionResult.summary}")
        val startupDecision = decideStartup(
            hasSavedInstanceState = savedInstanceState != null,
            directLaunch = directLaunchMode,
            sessionAccepted = sessionResult.accepted,
            embeddedUiStripped = true
        )
        when (startupDecision) {
            is StartupDecision.Proceed -> {
                BlazeNativeBridge.setDirectLaunch(directLaunchMode)
                BlazeInputRouter.selectRust(RustInputBridge)
                RustInputBridge.configureDesktopInputMode(desktopInputActive)
                super.onCreate(savedInstanceState)
                if (desktopInputActive) {
                    attachDesktopImeHost()
                    attachDesktopInputOverlay(savedInstanceState)
                } else if (!directLaunchMode) {
                    attachSpikeOverlay()
                    triggerSyntheticTouchProbe(source = "onCreate_post")
                }
            }
            is StartupDecision.ExitToLauncher -> {
                BlazeNativeBridge.setDirectLaunch(false)
                RustInputBridge.configureDesktopInputMode(false)
                BlazeInputRouter.selectJava()
                RustSessionHandoff.clearPendingSessionFiles(this)
                RustDiagnosticsCollector.recordExitReason(
                    this,
                    startupDecision.reasonCode,
                    startupDecision.reasonMessage
                )
                RustDiagnosticsCollector.recordRustEvent(
                    this,
                    "startup_exit_to_launcher code=${startupDecision.reasonCode} msg=${startupDecision.reasonMessage}"
                )
                super.onCreate(savedInstanceState)
                finish()
            }
        }
    }

    @Suppress("unused")
    @Keep
    fun onCursorGrabChanged(grabbing: Boolean) {
        RustInputBridge.onCursorGrabChanged(grabbing)
        val root = overlayRoot ?: return
        val content = overlayComposeView ?: return
        root.post { updateOverlayGeometry(root, content) }
    }

    @Suppress("unused")
    @Keep
    fun onTextInputActiveChanged(active: Boolean) {
        RustInputDiagnostics.textInputActiveSignals.incrementAndGet()
        engineTextInputActive = active
        runOnUiThread {
            setDesktopTextInputMode(if (active) TextInputMode.ENABLE else TextInputMode.DISABLE)
        }
    }

    override fun onStart() {
        super.onStart()
        if (desktopOverlayAttached) {
            composeHost.onStart()
        }
    }

    override fun onResume() {
        super.onResume()
        if (desktopOverlayAttached) {
            composeHost.onResume()
        }
    }

    override fun onPause() {
        if (directLaunchMode) {
            BlazeInputRouter.currentSink().resetInputState()
            persistInputBridgeDiagnostics()
        }
        if (desktopOverlayAttached) {
            composeHost.onPause()
        }
        super.onPause()
    }

    override fun onStop() {
        if (desktopOverlayAttached) {
            composeHost.onStop()
        }
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (desktopOverlayAttached) {
            composeHost.onBackPressedDispatcher.onBackPressed()
            return
        }
        super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!desktopOverlayAttached || !RustInputBridge.desktopInputEnabled) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_UP && (event.flags and KeyEvent.FLAG_CANCELED) != 0) {
            return true
        }
        val isDown = event.action == KeyEvent.ACTION_DOWN

        if ((event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                BlazeInputRouter.currentSink().sendMouseButton(
                    LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt(),
                    isDown
                )
            }
            return true
        }

        if (event.isGamepadKeyEvent()) {
            if (AllSettings.gamepadControl.state && gamepadViewModel.checkModePrompt()) {
                return true
            }
            if (AllSettings.gamepadControl.state) {
                gamepadViewModel.sendKeyEvent(event)
                return true
            }
            return AllSettings.showMenuBall.state
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                composeHost.onBackPressedDispatcher.onBackPressed()
            }
            return true
        }

        if (isDown && event.keyCode == AllSettings.physicalKeyImeCode.state) {
            val next = if (textInputMode == TextInputMode.ENABLE) {
                TextInputMode.DISABLE
            } else {
                TextInputMode.ENABLE
            }
            setDesktopTextInputMode(next)
            return true
        }

        if (textInputMode == TextInputMode.ENABLE && handleComposeTextModeKey(event, isDown)) {
            return true
        }

        if ((event.flags and KeyEvent.FLAG_SOFT_KEYBOARD) == KeyEvent.FLAG_SOFT_KEYBOARD &&
            event.keyCode == KeyEvent.KEYCODE_ENTER && isDown
        ) {
            LWJGLCharSender.sendEnter()
            return true
        }

        val index = EfficientAndroidLWJGLKeycode.getIndexByKey(event.keyCode)
        if (index >= 0) {
            EfficientAndroidLWJGLKeycode.execKey(event, index)
            return true
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_UNKNOWN -> super.dispatchKeyEvent(event)
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun handleComposeTextModeKey(event: KeyEvent, isDown: Boolean): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                LWJGLCharSender.sendModifierShift(isDown)
                true
            }
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT -> {
                LWJGLCharSender.sendModifierCtrl(isDown)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isDown) LWJGLCharSender.sendUp()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isDown) LWJGLCharSender.sendDown()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isDown) LWJGLCharSender.sendLeft()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isDown) LWJGLCharSender.sendRight()
                true
            }
            KeyEvent.KEYCODE_TAB -> {
                if (isDown) LWJGLCharSender.sendTab()
                true
            }
            KeyEvent.KEYCODE_ENTER -> {
                if (isDown) LWJGLCharSender.sendEnter()
                true
            }
            KeyEvent.KEYCODE_DEL -> {
                if (isDown) LWJGLCharSender.sendBackspace()
                true
            }
            else -> false
        }
    }

    /**
     * NativeActivity hands the window surface AND the window input queue to native code
     * (`takeSurface`/`takeInputQueue`), so Views added to the activity window via addContentView are never
     * drawn and never receive touches. The ZL2 overlay therefore lives in its own sub-window
     * (TYPE_APPLICATION_PANEL) which has its own surface and input channel and sits above the engine surface.
     */
    private fun attachDesktopInputOverlay(savedInstanceState: Bundle?) {
        RustInputDiagnostics.reset()
        try {
            PhysicalMouseChecker.initChecker(this)
            composeHost.onCreate(savedInstanceState)
            composeHost.onBackPressedDispatcher.addCallback(
                composeHost,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        RustInputBridge.sendAndroidBack()
                    }
                }
            )

            val root = DesktopOverlayRoot(this)
            applySafeDisplayInsets(root)
            applyGestureExclusionOnLayout(root, resources.displayMetrics.density)
            composeHost.attachToDecorView(root)

            val composeView = ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    SideEffect { RustInputDiagnostics.overlayComposedCount.incrementAndGet() }
                    ZalithLauncherTheme {
                        RustGameOverlay(
                            initialLayoutFile = activeLayoutFile,
                            gamepadViewModel = gamepadViewModel,
                            textInputMode = textInputMode,
                            onTextInputModeChange = ::setDesktopTextInputMode,
                            onLayoutChanged = { newFile -> activeLayoutFile = newFile },
                            onForceClose = ::exitRustProcess
                        )
                    }
                }
            }
            root.addView(
                composeView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            val showDebug = runCatching { AllSettings.rustInputDebugOverlay.getValue() }.getOrDefault(false)
            if (showDebug) {
                attachInputDebugText(root)
            }
            root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayGeometry(root, composeView) }
            composeView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayGeometry(root, composeView) }
            window.decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayGeometry(root, composeView) }
            overlayRoot = root
            overlayComposeView = composeView
            desktopOverlayAttached = true

            val decor = window.decorView
            if (decor.windowToken != null) {
                decor.post { addOverlayWindow(root) }
            } else {
                decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.removeOnAttachStateChangeListener(this)
                        v.post { addOverlayWindow(root) }
                    }

                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
            }
        } catch (t: Throwable) {
            recordOverlayError("attach_failed ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private fun addOverlayWindow(root: DesktopOverlayRoot) {
        if (overlayWindowAdded || isFinishing || isDestroyed) return
        try {
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                }
            }
            windowManager.addView(root, lp)
            overlayWindowAdded = true
            RustInputDiagnostics.overlayAttached = true
            RustInputDiagnostics.overlayWindowType = lp.type
            RustInputDiagnostics.overlayZOrderAboveSurface = true
            RustDiagnosticsCollector.recordRustEvent(this, "overlay_window_added type=${lp.type}")
            root.requestApplyInsets()
        } catch (t: Throwable) {
            recordOverlayError("add_window_failed ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private fun recordOverlayError(message: String, t: Throwable) {
        val clean = RustDiagnosticsCollector.redactDiagnosticText(message).take(160)
        RustInputDiagnostics.overlayError = clean
        Log.e(TAG, "Desktop overlay failure: $clean", t)
        RustDiagnosticsCollector.recordRustEvent(this, "overlay_error $clean")
    }

    private fun updateOverlayGeometry(root: View, content: View) {
        val decor = window.decorView
        val contentLoc = IntArray(2)
        val decorLoc = IntArray(2)
        content.getLocationOnScreen(contentLoc)
        decor.getLocationOnScreen(decorLoc)
        val engineRect = BlazeNativeBridge.getSurfaceRect()
        val surfX = engineRect?.surfX ?: 0
        val surfY = engineRect?.surfY ?: 0
        val surfW = engineRect?.surfWidth ?: decor.width
        val surfH = engineRect?.surfHeight ?: decor.height
        val bufW = engineRect?.bufferWidth ?: surfW
        val bufH = engineRect?.bufferHeight ?: surfH
        val surfaceScreenX = decorLoc[0] + surfX
        val surfaceScreenY = decorLoc[1] + surfY
        RustInputBridge.updateOverlayGeometry(
            offsetX = contentLoc[0] - surfaceScreenX,
            offsetY = contentLoc[1] - surfaceScreenY,
            surfaceWidth = surfW,
            surfaceHeight = surfH,
            overlayWidth = content.width,
            overlayHeight = content.height,
            bufferWidth = bufW,
            bufferHeight = bufH
        )
        RustInputDiagnostics.overlayWidth = content.width
        RustInputDiagnostics.overlayHeight = content.height
        RustInputDiagnostics.overlayVisible =
            root.visibility == View.VISIBLE && content.width > 0 && content.height > 0
    }

    private fun attachInputDebugText(root: FrameLayout) {
        val text = TextView(this).apply {
            setTextColor(Color.GREEN)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            textSize = 10f
            isClickable = false
            isFocusable = false
        }
        root.addView(
            text,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        val tick = object : Runnable {
            override fun run() {
                text.text = RustInputDiagnostics.format(0L).replace(" ", "\n")
                text.postDelayed(this, 500L)
            }
        }
        text.post(tick)
    }

    /** Overlay window root: counts and routes every touch into the ZL2 Compose tree; never to the engine. */
    private class DesktopOverlayRoot(context: Activity) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            RustInputDiagnostics.touchSeenByOverlay.incrementAndGet()
            val handled = super.dispatchTouchEvent(ev)
            if (handled) RustInputDiagnostics.touchRoutedToZl2.incrementAndGet()
            return true
        }
    }

    /**
     * Single touch door in desktop mode. With NativeActivity the system normally bypasses this method
     * (input goes to the native queue), but if it is ever called the event is forwarded to the ZL2 overlay
     * and consumed; it is never passed to `super` (which would reach the native touch path).
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!RustTouchRouting.consumeAtActivity(directLaunchMode && RustInputBridge.desktopInputEnabled)) {
            return super.dispatchTouchEvent(ev)
        }
        RustInputDiagnostics.touchSeenByActivity.incrementAndGet()
        val route = RustTouchRouting.route(RustInputBridge.desktopInputEnabled, overlayRoot != null)
        if (route == RustTouchRouting.Route.ZL2_OVERLAY) {
            overlayRoot?.dispatchTouchEvent(ev)
        }
        return true
    }

    private fun persistInputBridgeDiagnostics() {
        val nativeDiag = runCatching { BlazeNativeBridge.inputDiagnostics() }
            .getOrElse { "unavailable (${it.javaClass.simpleName})" }
        RustDiagnosticsCollector.recordInputBridgeStats(
            context = this,
            bridgeSummary = RustInputBridge.formatCountersSummary(),
            nativeDiagnostics = nativeDiag,
            touchDiagnostics = RustInputDiagnostics.format(
                leakedToEngine = RustInputDiagnostics.parseNativeRawTouch(nativeDiag),
                worldFramesWhileUiOpen = RustInputDiagnostics.parseNativeWorldFramesWhileUiOpen(nativeDiag),
                uiOverlayCacheActive = RustInputDiagnostics.parseNativeUiOverlayCacheActive(nativeDiag)
            )
        )
    }

    @Suppress("unused")
    fun onEngineExit(reasonCode: Int, message: String?) {
        val cleanMsg = message.orEmpty().ifBlank {
            RustDiagnosticsCollector.formatEmbeddedExitReasonLabel(reasonCode)
        }
        Log.i(TAG, "Engine exit callback code=$reasonCode msg=$cleanMsg")
        RustSessionHandoff.clearPendingSessionFiles(this)
        RustDiagnosticsCollector.recordExitReason(this, reasonCode, cleanMsg)
        RustDiagnosticsCollector.recordRustEvent(this, "onEngineExit code=$reasonCode msg=$cleanMsg")
        runOnUiThread {
            if (!isFinishing) {
                finish()
            }
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

    /**
     * Hosts an invisible 1x1 EditText in the Activity's own focused window so Android grants a real
     * InputConnection and shows the soft keyboard without taking window focus from NativeActivity
     * (which would trigger WindowEvent::Focused(false) and pause singleplayer).
     */
    private fun attachDesktopImeHost() {
        if (imeEditText != null) return
        val edit = BlazeImeEditText(this).apply {
            onImeShown = { RustInputDiagnostics.imeShownCount.incrementAndGet() }
            onImeBackPressed = { setDesktopTextInputMode(TextInputMode.DISABLE) }
            onEnterAction = {
                if (!engineTextInputActive) {
                    setDesktopTextInputMode(TextInputMode.DISABLE)
                }
            }
        }
        addContentView(edit, FrameLayout.LayoutParams(1, 1))
        imeEditText = edit

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.setOnApplyWindowInsetsListener { v, insets ->
                val visible = insets.isVisible(WindowInsets.Type.ime())
                if (lastImeVisible && !visible && textInputMode == TextInputMode.ENABLE) {
                    setDesktopTextInputMode(TextInputMode.DISABLE)
                }
                lastImeVisible = visible
                v.onApplyWindowInsets(insets)
            }
        }
    }

    private fun setDesktopTextInputMode(mode: TextInputMode) {
        if (textInputMode == mode) {
            if (mode == TextInputMode.ENABLE) {
                imeEditText?.enableIme()
            }
            return
        }
        textInputMode = mode
        val edit = imeEditText ?: return
        if (mode == TextInputMode.ENABLE) {
            edit.enableIme()
        } else {
            val nativeView = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
            edit.disableIme(nativeView)
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
        if (!hasFocus && directLaunchMode) {
            BlazeInputRouter.currentSink().resetInputState()
        }
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
        RustDiagnosticsCollector.recordExitReason(this, REASON_USER_QUIT, "Exit button in overlay")
        RustDiagnosticsCollector.recordRustEvent(this, "exit_requested pid=$pid")
        if (directLaunchMode) {
            persistInputBridgeDiagnostics()
        }
        BlazeInputRouter.selectJava()
        runCatching { BlazeNativeBridge.signalEngineExit() }
        RustSessionHandoff.clearPendingSessionFiles(this)
        finish()
        Process.killProcess(pid)
    }

    override fun onDestroy() {
        val pid = Process.myPid()
        RustDiagnosticsCollector.recordRustEvent(this, "onDestroy finishing=$isFinishing pid=$pid")
        if (directLaunchMode) {
            BlazeInputRouter.currentSink().resetInputState()
            persistInputBridgeDiagnostics()
        }
        if (overlayWindowAdded) {
            runCatching { windowManager.removeViewImmediate(overlayRoot) }
            overlayWindowAdded = false
            RustInputDiagnostics.overlayAttached = false
        }
        if (desktopOverlayAttached) {
            composeHost.onDestroy()
        }
        imeEditText?.disableIme(null)
        imeEditText = null
        BlazeInputRouter.selectJava()
        super.onDestroy()
        if (isFinishing) {
            Log.i(TAG, "RustActivity finishing; clearing session & terminating :rust pid=$pid")
            runCatching { BlazeNativeBridge.signalEngineExit() }
            RustSessionHandoff.clearPendingSessionFiles(this)
            Process.killProcess(pid)
        }
    }

    private inner class NativeActivityComposeHost :
        LifecycleOwner,
        SavedStateRegistryOwner,
        ViewModelStoreOwner,
        OnBackPressedDispatcherOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private val ownerViewModelStore = ViewModelStore()
        private var created = false

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        override val viewModelStore: ViewModelStore
            get() = ownerViewModelStore

        override val onBackPressedDispatcher: OnBackPressedDispatcher =
            OnBackPressedDispatcher {
                finish()
            }

        fun attachToDecorView(decorView: View) {
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
            decorView.setViewTreeOnBackPressedDispatcherOwner(this)
        }

        fun onCreate(savedInstanceState: Bundle?) {
            if (created) return
            savedStateController.performAttach()
            savedStateController.performRestore(savedInstanceState)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            created = true
        }

        fun onStart() {
            if (created) lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        fun onResume() {
            if (created) lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onPause() {
            if (created) lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }

        fun onStop() {
            if (created) lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }

        fun onDestroy() {
            if (created) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                created = false
            }
            ownerViewModelStore.clear()
        }
    }

    internal sealed interface StartupDecision {
        data object Proceed : StartupDecision
        data class ExitToLauncher(val reasonCode: Int, val reasonMessage: String) : StartupDecision
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
        internal const val EXTRA_CONTROL_LAYOUT_PATH = "io.github.kaito65452.blazelauncher.EXTRA_CONTROL_LAYOUT_PATH"
        internal const val DOCUMENT_PICKER_REQUEST_CODE = 4101
        internal const val MAX_DOCUMENT_URI_LENGTH = 8192
        internal const val MAX_SYSTEM_GESTURE_STRIP_DP = 200
        private const val EDGE_STRIP_WIDTH_DP = 24f
        internal const val REASON_USER_QUIT = 1
        internal const val REASON_NO_PENDING_SESSION = 3

        internal fun decideStartup(
            hasSavedInstanceState: Boolean,
            directLaunch: Boolean,
            sessionAccepted: Boolean,
            embeddedUiStripped: Boolean = true
        ): StartupDecision {
            if (hasSavedInstanceState) {
                return StartupDecision.ExitToLauncher(
                    REASON_NO_PENDING_SESSION,
                    "Resumed without pending session"
                )
            }
            if ((directLaunch || embeddedUiStripped) && !sessionAccepted) {
                return StartupDecision.ExitToLauncher(
                    REASON_NO_PENDING_SESSION,
                    "No pending Microsoft session"
                )
            }
            return StartupDecision.Proceed
        }

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
