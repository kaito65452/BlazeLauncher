# Stage L-B Completion Report: ZL2 Desktop-Style Input Bridge & Controls Overlay

**Branch:** `feat/stage-l`  
**Latest Green CI Commit:** `1b433140` (`test(stage-lb): verify Ctrl suppresses printable fallback and Shift resolves uppercase W`)  
**Pinned Submodule:** `engine/starling` @ `fa548eb7abe91c194d3780e4580b9746323cd606` (modified ONLY via [`engine/patches/0001`–`0009`](file:///data/data/com.termux/files/home/BlazeLauncher/engine/patches/))

---

## Summary of Implemented Changes (B1–B9)

### B1 — Input Path Audit & Bridge Design
- Documented in [`docs/ZL2_CONTROLS_BRIDGE.md`](file:///data/data/com.termux/files/home/BlazeLauncher/docs/ZL2_CONTROLS_BRIDGE.md):
  - Classified `horizon-client` / `horizon-client-input` into shared desktop core (`Game::handle_desktop_key_input` / `handle_window_event` / `mouse.on_move`) vs Android-only touch/HUD/IME modules.
  - Chose a single soft keyboard owner in desktop input mode: ZL2 `HidableInputLayout` + `LWJGLCharSender` (routing via `BlazeInputRouter.currentSink()` -> `RustInputBridge`), suppressing `native_ime::show` / `show_secret` / `capture_mouse` and `android_show_keyboard` while preserving SAF document picker (`DOCUMENT_PICKER_REQUEST_CODE = 4101`).

### B2 — Engine Input Injector & Desktop Input Mode (`0009-blaze-input-injector.patch`)
- Added zero-heap-allocation `BlazeInputEvent` fixed ring queue (`BLAZE_INPUT_QUEUE_CAP = 512`), `BlazeInputCounters`, coalesced `(dx, dy)` mouse deltas, and `BLAZE_DESKTOP_INPUT` toggle in `crates/horizon-client-render/src/android_runtime.rs`.
- Exported 10 JNI entry points on `BlazeNativeBridge` in `crates/horizon-client/src/android/jni_bridge.rs`:
  - `nativeInjectKey`, `nativeInjectMouseButton`, `nativeInjectCursorPos`, `nativeInjectCursorDelta`, `nativeInjectScroll`, `nativeInjectChar`, `nativeInjectAndroidBack`, `nativeResetInputState`, `nativeSetDesktopInputMode`, `nativeInputDiagnostics`.
- Added cursor-grab callback `notify_cursor_grab(bool)` in `crates/horizon-client/src/android/platform.rs` invoking `RustActivity.onCursorGrabChanged(Z)V`.
- Consumed queued events on every tick in `Game::idle` (`crates/horizon-client/src/game.rs`) feeding `Game::handle_desktop_key_input` (unified with physical `KeyboardInput`), `ModifiersChanged`, `CursorMoved`, `MouseInput`, `MouseWheel`, `Ime::Commit`, and camera mouse deltas.
- When `BLAZE_DESKTOP_INPUT` is active, dropped raw Android touch/key/mouse/IME events and skipped engine `draw_goy_hud`, `draw_pointer`, and `draw_hud_editor`, while preserving instant revert via Developer fallback toggle.

### B3–B6 — Runtime-Switched Sink, ZL2 Overlay Host & Fallback Toggle
- Created [`BlazeInputSink.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/input/BlazeInputSink.kt) (`BlazeInputSink`, `JavaCallbackInputSink`, `BlazeInputRouter`) so Java Edition remains on `CallbackBridge` untouched while Rust Edition selects `RustInputBridge` (never loading `libpojavexec.so`).
- Created [`GlfwKeycodeMapping.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/input/GlfwKeycodeMapping.kt) and [`RustInputBridge.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/input/RustInputBridge.kt) normalizing cursor coordinates, managing modifier state, scheduling delayed clicks, tracking counters, and driving `ZLBridgeStates.changeCursorMode`.
- Updated [`Events.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/control/event/Events.kt), [`LWJGLCharSender.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/input/LWJGLCharSender.kt), and [`EfficientAndroidLWJGLKeycode.java`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/input/EfficientAndroidLWJGLKeycode.java) to route through `BlazeInputRouter.currentSink()` (added additively to [`scripts/scope_guard.sh`](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/scope_guard.sh)).
- Created [`RustGameOverlay.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/input/RustGameOverlay.kt) hosting `ControlBoxLayout`, `SwitchableMouseLayout`, `MinecraftHotbar`, `GyroscopeReader`, `SimpleGamepadCapture`/`GamepadKeyListener`/`GamepadStickMovementListener`/`GamepadModePromptDialog`, `HidableInputLayout`, `DraggableGameBall`, `GameMenuSubscreen`, `SendKeycodeOperation`, `ReplacementControlOperation`, `ForceCloseOperation`, and `ControlEditor`.
- Updated [`RustLaunchDispatcher.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustLaunchDispatcher.kt) (`EXTRA_CONTROL_LAYOUT_PATH`) and [`RustActivity.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustActivity.kt) (`NativeActivityComposeHost`, `onCursorGrabChanged`, hardware/gamepad/mouse-back `dispatchKeyEvent`, focus/pause input reset, insets & gesture exclusion preserved).
- Added Developer Mode toggle `"Use engine touch controls (fallback)"` (`AllSettings.rustUseEngineTouchFallback`, default `false`) in [`AllSettings.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/setting/AllSettings.kt), [`DeveloperSection.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/developer/ui/DeveloperSection.kt), [`BlazeEngineSettings.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/BlazeEngineSettings.kt), [`values/strings.xml`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/res/values/strings.xml), and [`values-in/strings.xml`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/res/values-in/strings.xml).

### B7–B9 — Guards, Unit Tests, Copy Report & Morning Test Steps
- Added the 10 Stage L-B input JNI exports to `--check-embedded-so` in [`scripts/guard.sh`](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/guard.sh) and known-bad self-test `BAD_SO_MISSING_INPUT_JNI` in [`scripts/test_guards.sh`](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/test_guards.sh) (`PASS=74 FAIL=0`).
- Added [`StageLBInputBridgeTest.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/StageLBInputBridgeTest.kt) (mapping coverage, normalization/clamping, Ctrl/Shift printable fallback, grab state, router isolation, diagnostics section).
- Added cross-process `=== Input Bridge (Stage L-B) ===` section in [`RustDiagnosticsCollector.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustDiagnosticsCollector.kt).
- Added Indonesian Stage L-B verification steps in [`docs/MORNING_TEST_STEPS.md`](file:///data/data/com.termux/files/home/BlazeLauncher/docs/MORNING_TEST_STEPS.md).

---

## Touch Code Still Compiled In (To Delete After Device Verification)

Compiled-in Horizon touch controls remain gated behind `BLAZE_DESKTOP_INPUT == false` (controlled by Developer Mode `"Use engine touch controls (fallback)"`, default OFF) and should be physically deleted once ZL2 controls are verified on physical SM-N975F hardware:

- `crates/horizon-client-render/src/touch.rs` (`2,842` lines) and `crates/horizon-client-render/src/touch/{custom.rs (626 lines), editor.rs (988 lines), layout.rs (2,266 lines)}`
- `crates/horizon-client/src/touch_ui.rs` (`8,150` lines): `TouchUi` (`L22`), `draw_pointer` (`L367`), `draw_goy_hud` (`L1393`), `draw_hud_editor` (`L4237`), `touch_hud_editor` (`L6461`)
- `crates/horizon-client/src/game/android_input.rs` (`1,554` lines): `android_aux_mouse` (`L69`), `android_aux_touch` (`L657`), `android_show_keyboard` (`L1146`), `android_poll_keyboard` (`L1223`), `android_release_touch_keys` (`L1349`), `android_menu_touch` (`L1389`), `android_virtual_touch` (`L1473`), `android_virtual_mouse_clicks` (`L1507`)
- `crates/horizon-client/src/game.rs`: touch rendering & raw `WindowEvent::Touch` dispatch sites (`L15097`, `L15110–15111`, `L15147`, `L16422`, `L16468`, `L16599`, `L16639`, `L16834`, `L16838`)
- `crates/horizon-client/src/android/native_ime.rs` (`765` lines): `show` (`L673`), `show_secret` (`L676`), `hide` (`L691`), `capture_mouse` (`L715`)
- `crates/horizon-client/src/game/pad.rs` (`861` lines) and `crates/horizon-client/src/game/pad_menu.rs` (`1,538` lines) (fallback pad/touch menu integration)

---

## Native Library Size Delta (`arm64-v8a`, `libhorizon_android.so`)

- **After Stage L-A** (run `43825267269`, release stripped): `49,759,320` bytes
- **After Stage L-B** (run `43851022267`, release stripped): **`49,775,640` bytes** (`+16,320` bytes / `+0.03%` for ring buffer, coalescing, `desktop_key_input` unification, and 10 JNI symbols)

---

## CI Evidence

- **CI Workflow**: https://github.com/kaito65452/BlazeLauncher/actions/runs/43851022267 (`conclusion: success`)
  - `engine / engine`: `success` (`libhorizon_android.so` `49,775,640` bytes; Check 15: `23 KEEP markers present, 0 forbidden UI/network markers`; `horizon-client-render::android_runtime` `10 passed; 0 failed`; `horizon-prepare` suites `56+1+1+8+1 passed`)
  - `engine / blaze-prepare --release benchmark (aarch64)`: `success`
  - `ci`: `success` (`versionCode=27600542 > 27591674 > 27321741`, `android:debuggable=false`, R8 on, signing cert SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`)
  - **`signed-apks` Artifact ID**: `14220295607` (`385,722,957` bytes)
  - **`horizon-engine-arm64` Artifact ID**: `14218026294` (`26,911,492` bytes)
  - **`ci-host-tools` Artifact ID**: `14218520334` (`2,533,308` bytes)
- **Parallel Fast Checks**: https://github.com/kaito65452/BlazeLauncher/actions/runs/43851022387 (`conclusion: success` — `Guards & Self-Tests` + `JVM Unit Tests`)
- **Local Verification**: `check_no_zero_files.sh`, `scope_guard.sh`, `guard.sh`, `test_guards.sh` -> `PASS=74 FAIL=0` (exit 0).

---

## UNVERIFIED Items (Pending Physical Device Test)

- Physical execution on Samsung SM-N975F (Mali-G76) of Stage L-B APK (`signed-apks` ID `14220295607`, `versionCode=27600542`):
  - ZL2 `ControlBoxLayout` buttons, `DraggableGameBall`, `GameMenuSubscreen`, `ControlEditor`, and `ReplacementControlOperation` behavior inside live 26.3 session.
  - Camera rotation sensitivity & cursor grab synchronization (`onCursorGrabChanged`) when switching between world view (`grab=true`), Minecraft inventory/title screen (`grab=false`), and ZL2 hotbar taps.
  - `HidableInputLayout` soft IME text input, external keyboard/mouse, gamepad mapping/stick polling, and `"Use engine touch controls (fallback)"` emergency revert.
