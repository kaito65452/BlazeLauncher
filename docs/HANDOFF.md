# BlazeLauncher Handoff Status

> Updated after **Stage L-B completion** (green CI + signed APK) on branch `feat/stage-l`. Both **Stage L-A** and **Stage L-B** are implemented and CI-green; **on-device verification on SM-N975F remains `UNVERIFIED`**.

## Current Git & CI Baseline
- **Branch**: `feat/stage-l` (branched from `feat/overnight-stages` @ `5c864f1de608fb1d7b51649408aa84cd6f9db11d`)
- **HEAD (Stage L-B code + tests)**: `1b433140` (followed by Stage L-B completion docs `docs/STAGE_LB.md` and this `HANDOFF.md` update)
- **Pinned Engine Submodule**: `engine/starling` @ `fa548eb7abe91c194d3780e4580b9746323cd606` (`v0.7.8-2-gfa548eb7ab`), modified ONLY via `engine/patches/0001`–`0009`
- **Latest Green CI Run (Stage L-B @ `1b433140`)**:
  - `CI`: https://github.com/kaito65452/BlazeLauncher/actions/runs/43851022267 (`success`)
  - `Parallel Fast Checks`: https://github.com/kaito65452/BlazeLauncher/actions/runs/43851022387 (`success`)
  - **`signed-apks` Artifact ID**: `14220295607` (`385,722,957` bytes)
  - **`horizon-engine-arm64` Artifact ID**: `14218026294` (`26,911,492` bytes; `libhorizon_android.so` release/stripped `49,775,640` bytes; Check 15: `23 KEEP markers present, 0 forbidden UI/network markers`)
  - **`ci-host-tools` Artifact ID**: `14218520334` (`2,533,308` bytes)
  - **Produced `versionCode`**: `27600542` (`> 27591674` Stage L-A and `> 27321741` Nightly; `android:debuggable=false`, R8 on, cert SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`)
- **Previous Stage L-A Baseline (@ `21e9e0bc`)**:
  - `CI`: https://github.com/kaito65452/BlazeLauncher/actions/runs/43825267269 (`success`), `signed-apks` ID `14208549292`, `versionCode=27591674`
- **Repository Variables (`gh variable list -R kaito65452/BlazeLauncher`)**:
  - `BUNDLE_ENGINE_DATA = true`

## What Works vs UNVERIFIED / BLOCKED
- **Verified in CI (Stage L-A + Stage L-B)**:
  - Guards & self-tests (`scripts/guard.sh` incl. Check 15 A0/A3/L-B `.so` symbol & stripped-UI checks, `scripts/test_guards.sh` `PASS=74 FAIL=0`, `scripts/scope_guard.sh`, `scripts/check_no_zero_files.sh`, `scripts/check_preflight_workflow.sh`).
  - `engine/starling` arm64-v8a release `.so` (`49,775,640` bytes) with patches `0001`–`0009` (`blaze-embedded` feature enabled): zero-alloc ring input injector (`BLAZE_INPUT_QUEUE_CAP = 512`), coalesced cursor deltas, `Game::handle_desktop_key_input` unification, cursor grab notification (`onCursorGrabChanged`), 10 JNI entry points on `BlazeNativeBridge`, Check 15 confirms 23 KEEP markers and 0 forbidden Horizon UI/network markers.
  - Rust unit tests (`horizon-client-render --features blaze-embedded --lib android_runtime`: `10 passed; 0 failed`, covering Stage L-A exit state machine + Stage L-B key/mouse/modifier mapping, delta coalescing & ring overflow; `horizon-prepare`: `56+1+1+8+1 passed`) and Kotlin JVM unit tests (`StageLAEmbeddedTest`, `StageLBInputBridgeTest`, full debug unit test suite).
  - Launcher-side ZL2 Compose controls overlay (`RustGameOverlay.kt`), runtime-switched `BlazeInputSink` / `BlazeInputRouter` (`JavaCallbackInputSink` untouched vs `RustInputBridge`), hardware/gamepad/IME routing on `RustActivity`, Developer fallback toggle `"Use engine touch controls (fallback)"` (default `false`), cross-process `=== Input Bridge (Stage L-B) ===` diagnostics in Copy report, and `docs/MORNING_TEST_STEPS.md` (Indonesian).
  - Signed release APK (`versionCode=27600542`, `android:debuggable=false`, R8 on, cert SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`).
- **UNVERIFIED (CI cannot execute on phone hardware)**:
  - Physical SM-N975F execution of Stage L-A + Stage L-B APK (`14220295607`, `versionCode=27600542`):
    - Stage L-A: clean return to Blaze Launcher without `"Horizon Android"` menu on title Back, pause Quit, Leave World, app switch/return, disconnect; stripped `"H"`/`"..."`/Control Hub/watermark; `blaze_engine_settings.json` settings application.
    - Stage L-B: ZL2 `ControlBoxLayout` buttons, `DraggableGameBall`, `GameMenuSubscreen`, `ControlEditor`, `ReplacementControlOperation`, `MinecraftHotbar`, camera touch-look drag vs inventory/menu hover cursor alignment (`onCursorGrabChanged`), `HidableInputLayout` chat/IME input, physical keyboard/mouse/gamepad routing, Developer fallback toggle revert, and Copy report `=== Input Bridge (Stage L-B) ===` counters.
  - `BUNDLE_ENGINE_DATA=true` end-to-end install/launch on device.
- **BLOCKED**:
  - `preflight-real.yml` `workflow_dispatch`: GitHub Actions requires workflow files on default branch (`main`) before `workflow_dispatch` is allowed (`HTTP 404` on `feat/*`).

## Exact Next Step
1. User runs physical verification on SM-N975F using [`docs/MORNING_TEST_STEPS.md`](file:///data/data/com.termux/files/home/BlazeLauncher/docs/MORNING_TEST_STEPS.md) with APK from `signed-apks` artifact `14220295607` (`versionCode=27600542`).
2. **Proposed Follow-Up Cleanup (After Physical Verification)**: Once ZL2 controls and desktop-style input are confirmed working cleanly on SM-N975F, physically remove / compile-out the legacy Horizon touch control code listed in [`docs/STAGE_LB.md`](file:///data/data/com.termux/files/home/BlazeLauncher/docs/STAGE_LB.md#L56-L67) (`crates/horizon-client-render/src/touch.rs` + `touch/*`, `crates/horizon-client/src/touch_ui.rs`, `crates/horizon-client/src/game/android_input.rs`, `crates/horizon-client/src/android/native_ime.rs` IME popups) and drop the temporary fallback toggle `"Use engine touch controls (fallback)"`.

## Stage L-B2 (branch feat/stage-l-b2)
Root cause: NativeActivity owns window surface + input queue, so the addContentView ZL2 overlay never drew or got touches.
Fix: overlay in a TYPE_APPLICATION_PANEL sub-window, geometry-aware coordinate mapping, engine patch 0010, guard Check 16,
new Input Bridge touch diagnostics + "Input debug overlay" toggle. See docs/STAGE_LB2.md. All device behavior UNVERIFIED.

## Stage L-B3 (branch feat/stage-l-b3 @ `d93af0a0`)
- **CI**: https://github.com/kaito65452/BlazeLauncher/actions/runs/44274084049 (`success`), `Parallel Fast Checks`: https://github.com/kaito65452/BlazeLauncher/actions/runs/44274083804 (`success`)
- **`signed-apks` Artifact ID**: `14417260358` (`385,734,228` bytes), **`versionCode`**: `27769346`
- **Fixes**:
  1. Bug 1 (`00851c49`, patch `0011`): single touch-to-surface mapping via engine `nativeGetSurfaceRect`, `fitInsetsTypes = 0` on panel window, paired `last_touch_xy_raw`/`last_touch_xy_mapped` + `overlay_offset`/`surface_size`/`mapped_px`/`raw_vs_mapped_check`.
  2. Bug 2 (`df479919`, patch `0012`): `native_ime` disabled in desktop mode, engine `sync_ime` emits `onTextInputActiveChanged(Boolean)` (`text_input_active_signals`), launcher hosts `BlazeImeEditText` in `RustActivity`'s focused window with codepoint diffing (`RustImeTextProcessor`), `ime_shown_count` + `char_events`.
  3. Bug 3 (`5c4c2ba7`, patches `0013` & `0014`, Guard Check 17): removed `in_world_stationary_overlay`; blur cache strictly gated by `should_cache_menu_blur(singleplayer_paused, shows_panorama, has_menu_blur)`; ported `gui_item_cache`; added `world_frames_while_ui_open` & `ui_overlay_cache_active`.
  4. Render Startup Summary (`689a50a5`) + 26.3 pin refresh in `0007` (`d93af0a0`).
- **Details**: [`docs/STAGE_LB3.md`](file:///data/data/com.termux/files/home/BlazeLauncher/docs/STAGE_LB3.md). On-device SM-N975F behavior remains `UNVERIFIED`.

## Stage L-B4 (branch feat/stage-l-b4)
- IME: overlay window gets `FLAG_ALT_FOCUSABLE_IM` (it was layered above Gboard and swallowed its touches), `RustImeController` state machine, `BlazeImeEditText` with swipe-friendly input type + counting `InputConnectionWrapper`, `stateHidden`, IME diagnostics in Copy report.
- Exit: `GameLaunchFlow` no longer finishes `MainActivity` for Rust runtime, so quitting returns to the ZL2 menu.
- Details and caller table: [`docs/STAGE_LB4.md`](file:///data/data/com.termux/files/home/BlazeLauncher/docs/STAGE_LB4.md). No engine patch. All device behavior UNVERIFIED.
