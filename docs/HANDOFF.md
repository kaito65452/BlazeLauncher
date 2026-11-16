# BlazeLauncher Handoff Status

> Updated after **Stage L-A completion** (green CI + signed APK) on branch `feat/stage-l`, before starting **Stage L-B**.

## Current Git & CI Baseline
- **Branch**: `feat/stage-l` (branched from `feat/overnight-stages` @ `5c864f1de608fb1d7b51649408aa84cd6f9db11d`)
- **HEAD**: `21e9e0bc` (before adding `docs/STAGE_LA.md` + this `HANDOFF.md` update)
- **Pinned Engine Submodule**: `engine/starling` @ `fa548eb7abe91c194d3780e4580b9746323cd606` (`v0.7.8-2-gfa548eb7ab`), modified ONLY via `engine/patches/0001`–`0008`
- **Latest Green CI Run (Stage L-A @ `21e9e0bc`)**:
  - `CI`: https://github.com/kaito65452/BlazeLauncher/actions/runs/43825267269 (`success`)
  - `Parallel Fast Checks`: https://github.com/kaito65452/BlazeLauncher/actions/runs/43825267625 (`success`)
  - **`signed-apks` Artifact ID**: `14208549292` (`385,695,035` bytes)
  - **`horizon-engine-arm64` Artifact ID**: `14208635452` (`26,898,660` bytes; `libhorizon_android.so` release/stripped `49,759,320` bytes vs `52,021,912` bytes baseline — `-2,262,592` bytes)
  - **`ci-host-tools` Artifact ID**: `14208156517` (`2,533,308` bytes)
  - **Produced `versionCode`**: `27591674` (`android:debuggable=false`, R8 on, cert SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`)
- **Repository Variables (`gh variable list -R kaito65452/BlazeLauncher`)**:
  - `BUNDLE_ENGINE_DATA = true`

## What Works vs UNVERIFIED / BLOCKED
- **Verified in CI (Stage L-A)**:
  - Guards & self-tests (`scripts/guard.sh` incl. Check 15 A0/A3 `.so` guard, `scripts/test_guards.sh` `PASS=73 FAIL=0`, `scripts/scope_guard.sh`, `scripts/check_no_zero_files.sh`, `scripts/check_preflight_workflow.sh`).
  - `engine/starling` arm64-v8a release `.so` (`49,759,320` bytes) with patches `0001`–`0008` (`blaze-embedded` feature enabled): Check 15 confirms 13 KEEP markers present and 0 forbidden Horizon UI/network markers (`Control Hub`, `Horizon Android`, `Horizon 26.3 (Rust)`, `horizon-rust.github.io/horizon-mods`, `telemetry.playhorizon.dev`, Modrinth/GitHub/OAuth).
  - Rust unit tests (`horizon-client-render --features blaze-embedded --lib android_runtime`: `7 passed`, `horizon-prepare`: `56+1+1+8+1 passed`) and Kotlin JVM unit tests (`StageLAEmbeddedTest` + full debug test suite).
  - Release-signed APK build (`versionCode=27591674`, `android:debuggable=false`, cert SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`).
- **UNVERIFIED (CI cannot execute on phone hardware)**:
  - Physical SM-N975F execution of Stage L-A APK (`14208549292`, `versionCode=27591674`): clean return to Blaze Launcher without `"Horizon Android"` menu on title-screen Back, pause Quit, Leave World, app switch/return, disconnect; stripped `"H"`/`"..."`/Control Hub/watermark; launcher-owned `blaze_engine_settings.json` values (`render_scale_percent`, `touch_look_sensitivity`, `show_controls`); `BUNDLE_ENGINE_DATA=true` asset flow; Android `:jvm` `Internal-25` fallback.
- **BLOCKED**:
  - `preflight-real.yml` `workflow_dispatch`: GitHub Actions requires workflow files on default branch (`main`) before `workflow_dispatch` is allowed (`HTTP 404` on `feat/*`).

## Exact Next Step
Execute **Stage L-B (ZL2 input bridge; desktop-style input)** on `feat/stage-l`:
1. **B1 (`docs/ZL2_CONTROLS_BRIDGE.md`)**: Write classification of `horizon-client` / `horizon-client-input` into shared desktop core (`Game::handle_window_event`, `Game::device_event`, `horizon-client-input`) vs Android-only layer (`game/android_input.rs`, `touch_ui.rs`, `horizon-client-render/src/touch.rs`, `android/native_ime.rs`), plus chosen bridge design, risks, unmappable items, and single soft keyboard owner decision (`B4`).
2. **B2 (`engine/patches/0009-blaze-input-injector.patch`)**: Zero-heap-alloc thread-safe ring queue in Rust consumed from `Game::idle` / main loop feeding `Game::handle_window_event` (`KeyboardInput`, `ModifiersChanged`, `CursorMoved`, `MouseInput`, `MouseWheel`, `Ime::Commit`) + `mouse.on_move` (`DeviceEvent::MouseMotion` path) with mouse-delta coalescing; JNI entry points on `BlazeNativeBridge`; cursor-grab callback (`onCursorGrabChanged(Z)V`); Desktop Input Mode dropping native pointer touch events, hiding virtual pointer rendering, disabling `draw_goy_hud` when active, and preserving fallback toggle to compiled-in touch controls.
3. **B3–B6 (Launcher side)**: `RustInputBridge`, runtime sink switching (`java` untouched vs `rust` -> `RustInputBridge`), GLFW->winit key mapping table + tests, hardware key/mouse/gamepad forwarding on `RustActivity`, ZL2 Compose controls overlay hosted in `RustActivity` preserving insets/cutout/gesture exclusion/SAF 4101/L-A clean exit, Developer toggle "Use engine touch controls (fallback)" (default OFF), additive `scope_guard.sh` allowlist entries.
4. **B7–B9 (Verification & Docs)**: Synthetic Rust/Kotlin tests, Copy report additions (`input path`, counters, `last exit reason`), Check 15 / guard checks + self-tests, Indonesian `docs/MORNING_TEST_STEPS.md`, `docs/STAGE_LB.md` (with compiled-in touch code inventory `file:line` for post-verification removal), green CI + signed APK, and final `docs/HANDOFF.md` update (including follow-up note to delete compiled-in touch controls once verified on device).
