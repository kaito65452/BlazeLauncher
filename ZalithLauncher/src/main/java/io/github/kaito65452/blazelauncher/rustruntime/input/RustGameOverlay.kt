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

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movtery.inputmap.keycodes.LwjglGlfwKeycode
import com.movtery.inputmap.keycodes.OPEN_CHAT
import com.movtery.inputmap.keycodes.OPEN_CHAT_VALUE
import com.movtery.layer_controller.ControlBoxLayout
import com.movtery.layer_controller.data.HideLayerWhen
import com.movtery.layer_controller.event.ClickEvent
import com.movtery.layer_controller.event.EventHandler
import com.movtery.layer_controller.layout.EmptyControlLayout
import com.movtery.layer_controller.layout.loadLayoutFromFile
import com.movtery.layer_controller.observable.ObservableControlLayout
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.bridge.CURSOR_DISABLED
import com.movtery.zalithlauncher.bridge.ZLBridgeStates
import com.movtery.zalithlauncher.game.control.ControlManager
import com.movtery.zalithlauncher.game.input.LWJGLCharSender
import com.movtery.zalithlauncher.game.keycodes.mapToKeycode
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.enums.GamepadInputMode
import com.movtery.zalithlauncher.setting.enums.isLauncherInDarkTheme
import com.movtery.zalithlauncher.setting.enums.toAction
import com.movtery.zalithlauncher.ui.components.MenuState
import com.movtery.zalithlauncher.ui.components.rememberBoxSize
import com.movtery.zalithlauncher.ui.control.MinecraftHotbar
import com.movtery.zalithlauncher.ui.control.event.launcherEvent
import com.movtery.zalithlauncher.ui.control.event.lwjglEvent
import com.movtery.zalithlauncher.ui.control.gamepad.GamepadKeyListener
import com.movtery.zalithlauncher.ui.control.gamepad.GamepadOnActionListener
import com.movtery.zalithlauncher.ui.control.gamepad.GamepadStickMovementListener
import com.movtery.zalithlauncher.ui.control.gamepad.SimpleGamepadCapture
import com.movtery.zalithlauncher.ui.control.gyroscope.GyroscopeReader
import com.movtery.zalithlauncher.ui.control.gyroscope.isGyroscopeAvailable
import com.movtery.zalithlauncher.ui.control.hotbarPercentage
import com.movtery.zalithlauncher.ui.control.input.TextInputMode
import com.movtery.zalithlauncher.ui.control.mouse.SwitchableMouseLayout
import com.movtery.zalithlauncher.ui.screens.game.elements.DraggableGameBall
import com.movtery.zalithlauncher.ui.screens.game.elements.ForceCloseOperation
import com.movtery.zalithlauncher.ui.screens.game.elements.GameMenuSubscreen
import com.movtery.zalithlauncher.ui.screens.game.elements.GamepadModePromptDialog
import com.movtery.zalithlauncher.ui.screens.game.elements.ReplacementControlOperation
import com.movtery.zalithlauncher.ui.screens.game.elements.ReplacementControlState
import com.movtery.zalithlauncher.ui.screens.game.elements.SendKeycodeOperation
import com.movtery.zalithlauncher.ui.screens.game.elements.SendKeycodeState
import com.movtery.zalithlauncher.ui.screens.main.control_editor.ControlEditor
import com.movtery.zalithlauncher.ui.toAndroidString
import com.movtery.zalithlauncher.utils.device.PhysicalMouseChecker
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.viewmodel.EditorViewModel
import com.movtery.zalithlauncher.viewmodel.GamepadViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "RustGameOverlay"

private class RustMouseScrollController(
    private val scope: CoroutineScope,
    private val sinkProvider: () -> BlazeInputSink
) {
    private var scrollUpJob: Job? = null
    private var scrollDownJob: Job? = null

    fun triggerSingleUp() {
        sinkProvider().sendScroll(0.0, 1.0)
    }

    fun triggerSingleDown() {
        sinkProvider().sendScroll(0.0, -1.0)
    }

    fun startLongUp() {
        scrollUpJob?.cancel()
        scrollUpJob = scope.launch {
            while (true) {
                ensureActive()
                sinkProvider().sendScroll(0.0, 1.0)
                delay(50.milliseconds)
            }
        }
    }

    fun cancelLongUp() {
        scrollUpJob?.cancel()
        scrollUpJob = null
    }

    fun startLongDown() {
        scrollDownJob?.cancel()
        scrollDownJob = scope.launch {
            while (true) {
                ensureActive()
                sinkProvider().sendScroll(0.0, -1.0)
                delay(50.milliseconds)
            }
        }
    }

    fun cancelLongDown() {
        scrollDownJob?.cancel()
        scrollDownJob = null
    }

    fun cancelAll() {
        cancelLongUp()
        cancelLongDown()
    }
}

private class RustChatTextSender(
    private val scope: CoroutineScope,
    private val sinkProvider: () -> BlazeInputSink
) {
    private val mutex = Mutex()

    fun sendText(text: String) {
        if (text.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val sink = sinkProvider()
                val chatKey = mapToKeycode(OPEN_CHAT, OPEN_CHAT_VALUE) ?: LwjglGlfwKeycode.GLFW_KEY_T.toInt()
                val mods = sink.getCurrentMods()
                sink.sendKeyPress(chatKey, mods, true)
                sink.sendKeyPress(chatKey, mods, false)
                delay(150.milliseconds)
                text.forEach { ch ->
                    LWJGLCharSender.sendChar(ch)
                }
                delay(50.milliseconds)
                LWJGLCharSender.sendEnter()
            }
        }
    }
}

@Composable
fun RustGameOverlay(
    initialLayoutFile: File?,
    gamepadViewModel: GamepadViewModel,
    textInputMode: TextInputMode,
    onTextInputModeChange: (TextInputMode) -> Unit,
    onLayoutChanged: (File) -> Unit,
    onForceClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val cursorMode by ZLBridgeStates.cursorMode.collectAsStateWithLifecycle()
    val isGrabbing = cursorMode == CURSOR_DISABLED

    val occupiedPointers = remember { mutableStateListOf<PointerId>() }
    val moveOnlyPointers = remember { mutableStateListOf<PointerId>() }

    var currentLayoutFile by remember(initialLayoutFile) {
        mutableStateOf(initialLayoutFile ?: ControlManager.selectedLayout.value?.file)
    }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var observableLayout by remember { mutableStateOf<ObservableControlLayout?>(null) }

    var gameMenuState by remember { mutableStateOf(MenuState.NONE) }
    var controlMenuTabIndex by remember { mutableIntStateOf(0) }
    var sendKeycodeState by remember { mutableStateOf<SendKeycodeState>(SendKeycodeState.None) }
    var replacementControlState by remember { mutableStateOf<ReplacementControlState>(ReplacementControlState.None) }
    var forceCloseState by remember { mutableStateOf<ForceCloseOperation>(ForceCloseOperation.None) }
    var editorTargetFile by remember { mutableStateOf<File?>(null) }
    var editorViewModel by remember { mutableStateOf<EditorViewModel?>(null) }

    var isTouchOnly by remember { mutableStateOf(!PhysicalMouseChecker.physicalMouseConnected) }

    val scrollController = remember(scope) {
        RustMouseScrollController(scope) { BlazeInputRouter.currentSink() }
    }
    val chatSender = remember(scope) {
        RustChatTextSender(scope) { BlazeInputRouter.currentSink() }
    }

    DisposableEffect(scrollController) {
        onDispose { scrollController.cancelAll() }
    }

    LaunchedEffect(Unit) {
        ControlManager.checkDefaultAndRefresh(context)
        if (currentLayoutFile == null) {
            currentLayoutFile = ControlManager.selectedLayout.value?.file
        }
    }

    LaunchedEffect(currentLayoutFile, reloadNonce) {
        val loaded = withContext(Dispatchers.IO) {
            val file = currentLayoutFile
            if (file != null && file.isFile) {
                runCatching { loadLayoutFromFile(file) }
                    .onFailure { Logger.warning(TAG, "Failed to load control layout ${file.absolutePath}", it) }
                    .getOrElse { EmptyControlLayout }
            } else {
                EmptyControlLayout
            }
        }
        observableLayout = ObservableControlLayout(loaded)
    }

    // Always drive GamepadInputMode.Mapped in Rust Edition unless user explicitly wired custom SDL handling.
    LaunchedEffect(gamepadViewModel) {
        while (true) {
            val level = gamepadViewModel.checkGamepadActive()
            if (gamepadViewModel.gamepadEngaged) {
                gamepadViewModel.pollJoystick(level.delayMs.toDouble())
                delay(level.delayMs)
            } else {
                delay(250L)
            }
        }
    }

    fun toggleMenu() {
        gameMenuState = gameMenuState.next()
        BlazeInputRouter.currentSink().resetInputState()
    }

    fun toggleInputMethod() {
        val nextMode = if (textInputMode == TextInputMode.ENABLE) TextInputMode.DISABLE else TextInputMode.ENABLE
        onTextInputModeChange(nextMode)
    }

    fun dispatchClickEvent(event: ClickEvent, pressed: Boolean) {
        when (event.type) {
            ClickEvent.Type.Key -> lwjglEvent(eventKey = event.key, isMouse = false, isPressed = pressed)
            ClickEvent.Type.LauncherEvent -> {
                launcherEvent(
                    eventKey = event.key,
                    isPressed = pressed,
                    onSwitchIME = ::toggleInputMethod,
                    onSwitchMenu = ::toggleMenu,
                    onSingleScrollUp = scrollController::triggerSingleUp,
                    onSingleScrollDown = scrollController::triggerSingleDown,
                    onLongScrollUp = scrollController::startLongUp,
                    onLongScrollUpCancel = scrollController::cancelLongUp,
                    onLongScrollDown = scrollController::startLongDown,
                    onLongScrollDownCancel = scrollController::cancelLongDown
                )
            }
            ClickEvent.Type.SendText -> {
                if (pressed) chatSender.sendText(event.key)
            }
            else -> Unit
        }
    }

    val eventHandler = remember {
        EventHandler { event, pressed ->
            dispatchClickEvent(event, pressed)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenSize = rememberBoxSize()

        LaunchedEffect(screenSize) {
            if (screenSize.width > 0 && screenSize.height > 0) {
                BlazeInputRouter.currentSink().sendUpdateWindowSize(screenSize.width, screenSize.height)
            }
        }

        val editFile = editorTargetFile
        val activeEditorVm = editorViewModel
        if (editFile != null && activeEditorVm != null) {
            ControlEditor(
                viewModel = activeEditorVm,
                targetFile = editFile,
                exit = {
                    editorTargetFile = null
                    editorViewModel = null
                    reloadNonce++
                },
                menuExit = {
                    editorTargetFile = null
                    editorViewModel = null
                }
            )
            return@BoxWithConstraints
        }

        val showControls = AllSettings.rustShowControls.state
        val hideLayerWhen = when {
            gamepadViewModel.gamepadEngaged -> HideLayerWhen.WhenGamepad
            !isTouchOnly -> HideLayerWhen.WhenMouse
            else -> HideLayerWhen.None
        }

        val mouseAndHudLayer: @Composable () -> Unit = {
            SwitchableMouseLayout(
                modifier = Modifier.fillMaxSize(),
                screenSize = screenSize,
                cursorMode = cursorMode,
                gamepadViewModel = gamepadViewModel,
                onTouch = { isTouchOnly = true },
                onMouse = { isTouchOnly = false },
                onTap = { position ->
                    val sink = BlazeInputRouter.currentSink()
                    sink.sendCursorPos(position.x, position.y)
                    sink.putMouseEvent(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt())
                },
                onCapturedTap = {
                    BlazeInputRouter.currentSink().putMouseEvent(AllSettings.gestureTapMouseAction.state.toAction())
                },
                onLongPress = {
                    BlazeInputRouter.currentSink().sendMouseButton(
                        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt(),
                        true
                    )
                },
                onLongPressEnd = {
                    BlazeInputRouter.currentSink().sendMouseButton(
                        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt(),
                        false
                    )
                },
                onCapturedLongPress = {
                    BlazeInputRouter.currentSink().sendMouseButton(
                        AllSettings.gestureLongPressMouseAction.state.toAction(),
                        true
                    )
                },
                onCapturedLongPressEnd = {
                    BlazeInputRouter.currentSink().sendMouseButton(
                        AllSettings.gestureLongPressMouseAction.state.toAction(),
                        false
                    )
                },
                onPointerMove = { position ->
                    BlazeInputRouter.currentSink().sendCursorPos(position.x, position.y)
                },
                onCapturedMove = { delta ->
                    val scale = AllSettings.rustTouchLookSensitivityPercent.state.coerceIn(25, 300) / 100f
                    BlazeInputRouter.currentSink().sendCursorDelta(delta.x * scale, delta.y * scale)
                },
                onMouseScroll = { scroll ->
                    BlazeInputRouter.currentSink().sendScroll(scroll.x.toDouble(), scroll.y.toDouble())
                },
                onMouseButton = { button, pressed ->
                    BlazeInputRouter.currentSink().sendMouseButton(button, pressed)
                },
                isMoveOnlyPointer = { pointerId -> moveOnlyPointers.contains(pointerId) },
                onOccupiedPointer = { pointerId ->
                    if (!occupiedPointers.contains(pointerId)) occupiedPointers.add(pointerId)
                },
                onReleasePointer = { pointerId ->
                    occupiedPointers.remove(pointerId)
                    moveOnlyPointers.remove(pointerId)
                },
                enableScrollGesture = AllSettings.gestureControl.state,
                onScrollGesture = { delta ->
                    BlazeInputRouter.currentSink().sendScroll(delta.x.toDouble(), delta.y.toDouble())
                }
            )

            MinecraftHotbar(
                screenSize = screenSize,
                rule = AllSettings.hotbarRule.state,
                widthPercentage = AllSettings.hotbarWidth.state.hotbarPercentage(),
                heightPercentage = AllSettings.hotbarHeight.state.hotbarPercentage(),
                sendKeycode = { keycode ->
                    BlazeInputRouter.currentSink().sendKeyPress(keycode)
                },
                isGrabbing = isGrabbing,
                displayOffset = IntOffset.Zero,
                onOccupiedPointer = { pointerId ->
                    if (!occupiedPointers.contains(pointerId)) occupiedPointers.add(pointerId)
                },
                onReleasePointer = { pointerId ->
                    occupiedPointers.remove(pointerId)
                }
            )
        }

        if (showControls) {
            ControlBoxLayout(
                modifier = Modifier.fillMaxSize(),
                observedLayout = observableLayout,
                eventHandler = eventHandler,
                isCursorGrabbing = isGrabbing,
                checkOccupiedPointers = { pointerId -> occupiedPointers.contains(pointerId) },
                opacity = (AllSettings.controlsOpacity.state.toFloat() / 100f).coerceIn(0f, 1f),
                markPointerAsMoveOnly = { pointerId ->
                    if (!moveOnlyPointers.contains(pointerId)) moveOnlyPointers.add(pointerId)
                },
                onOccupiedPointer = { pointerId ->
                    if (!occupiedPointers.contains(pointerId)) occupiedPointers.add(pointerId)
                },
                onReleasePointer = { pointerId ->
                    occupiedPointers.remove(pointerId)
                    moveOnlyPointers.remove(pointerId)
                },
                hideLayerWhen = hideLayerWhen,
                isDark = isLauncherInDarkTheme(),
                content = { mouseAndHudLayer() }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                mouseAndHudLayer()
            }
        }

        if (AllSettings.gyroscopeControl.state && isGyroscopeAvailable(context)) {
            GyroscopeReader(
                xEvent = { dx ->
                    if (isGrabbing) {
                        val scale = AllSettings.rustTouchLookSensitivityPercent.state.coerceIn(25, 300) / 100f
                        val invert = if (AllSettings.gyroscopeInvertX.state) -1f else 1f
                        BlazeInputRouter.currentSink().sendCursorDelta(dx * invert * scale, 0f)
                    }
                },
                yEvent = { dy ->
                    if (isGrabbing) {
                        val scale = AllSettings.rustTouchLookSensitivityPercent.state.coerceIn(25, 300) / 100f
                        val invert = if (AllSettings.gyroscopeInvertY.state) -1f else 1f
                        BlazeInputRouter.currentSink().sendCursorDelta(0f, dy * invert * scale)
                    }
                },
                sampleRate = AllSettings.gyroscopeSampleRate.state,
                smoothing = AllSettings.gyroscopeSmoothing.state,
                smoothingWindow = AllSettings.gyroscopeSmoothingWindow.state,
                sensitivity = AllSettings.gyroscopeSensitivity.state.toFloat() / 100f
            )
        }

        if (AllSettings.gamepadControl.state) {
            SimpleGamepadCapture(gamepadViewModel = gamepadViewModel)

            GamepadOnActionListener(
                gamepadViewModel = gamepadViewModel,
                onAction = { isTouchOnly = false }
            )

            GamepadKeyListener(
                gamepadViewModel = gamepadViewModel,
                isGrabbing = isGrabbing,
                onKeyEvent = { targets, pressed ->
                    targets.forEach { target -> dispatchClickEvent(target, pressed) }
                }
            )

            GamepadStickMovementListener(
                gamepadViewModel = gamepadViewModel,
                isGrabbing = isGrabbing,
                onKeyEvent = { event, pressed ->
                    dispatchClickEvent(event, pressed)
                }
            )

            GamepadModePromptDialog(
                visible = gamepadViewModel.modePromptVisible,
                onConfirm = { mode ->
                    if (mode == GamepadInputMode.SdlDirect) {
                        AllSettings.gamepadInputMode.save(GamepadInputMode.Mapped)
                        gamepadViewModel.confirmModePrompt(GamepadInputMode.Mapped)
                    } else {
                        gamepadViewModel.confirmModePrompt(mode)
                    }
                }
            )
        }

        if (AllSettings.showMenuBall.state) {
            DraggableGameBall(
                position = AllSettings.menuBallPos.state,
                onPositionChanged = { newPos -> AllSettings.menuBallPos.updateState(newPos) },
                onSavePos = { AllSettings.menuBallPos.save(AllSettings.menuBallPos.state) },
                gameFps = null,
                showMemory = AllSettings.showMemory.state,
                opened = gameMenuState == MenuState.SHOW,
                alpha = (AllSettings.menuBallOpacity.state.toFloat() / 100f).coerceIn(0.2f, 1f),
                onClick = ::toggleMenu
            )
        }

        GameMenuSubscreen(
            state = gameMenuState,
            controlMenuTabIndex = controlMenuTabIndex,
            onControlMenuTabChange = { controlMenuTabIndex = it },
            gamepadViewModel = gamepadViewModel,
            closeScreen = { gameMenuState = MenuState.HIDE },
            onForceClose = { forceCloseState = ForceCloseOperation.Show },
            onSwitchLog = {
                Toast.makeText(
                    context,
                    R.string.blaze_rust_log_not_available,
                    Toast.LENGTH_SHORT
                ).show()
            },
            enableTerracotta = false,
            onOpenTerracottaMenu = {},
            onRefreshWindowSize = {
                if (screenSize.width > 0 && screenSize.height > 0) {
                    BlazeInputRouter.currentSink().sendUpdateWindowSize(screenSize.width, screenSize.height)
                }
            },
            onInputMethod = {
                gameMenuState = MenuState.HIDE
                toggleInputMethod()
            },
            onSendKeycode = {
                gameMenuState = MenuState.HIDE
                sendKeycodeState = SendKeycodeOperationStateShow
            },
            onReplacementControl = {
                gameMenuState = MenuState.HIDE
                replacementControlState = ReplacementControlState.Show
            },
            onEditLayout = {
                gameMenuState = MenuState.HIDE
                val file = currentLayoutFile ?: ControlManager.selectedLayout.value?.file
                if (file != null && file.isFile) {
                    val layout = observableLayout?.let { runCatching { loadLayoutFromFile(file) }.getOrNull() }
                        ?: EmptyControlLayout
                    editorViewModel = EditorViewModel().apply { initLayout(layout) }
                    editorTargetFile = file
                } else {
                    Toast.makeText(
                        context,
                        R.string.blaze_rust_no_control_layout_to_edit,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onShowToast = { text, duration ->
                Toast.makeText(context, text.toAndroidString(context), duration).show()
            }
        )

        SendKeycodeOperation(
            operation = sendKeycodeState,
            onChange = { sendKeycodeState = it },
            lifecycleScope = scope
        )

        ReplacementControlOperation(
            operation = replacementControlState,
            onChange = { replacementControlState = it },
            currentLayout = currentLayoutFile,
            replacementControl = { file ->
                currentLayoutFile = file
                onLayoutChanged(file)
                ControlManager.selectedLayout.value?.let {
                    // Persist selection if user picked a managed layout
                }
            }
        )

        ForceCloseOperation(
            operation = forceCloseState,
            onChange = { forceCloseState = it },
            onForceClose = onForceClose,
            text = stringResource(R.string.game_menu_option_force_close_text)
        )
    }
}

private val SendKeycodeOperationStateShow = SendKeycodeState.ShowDialog
