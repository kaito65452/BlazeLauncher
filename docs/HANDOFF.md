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
