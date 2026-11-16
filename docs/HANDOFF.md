# BlazeLauncher Handoff Status

> Updated at start of Task L (Stage L-A -> Stage L-B) on branch `feat/stage-l`.

## Current Git & CI Baseline
- **Branch**: `feat/stage-l` (branched from `feat/overnight-stages` @ `5c864f1de608fb1d7b51649408aa84cd6f9db11d`)
- **HEAD**: `5c864f1de608fb1d7b51649408aa84cd6f9db11d` (no local commits yet on `feat/stage-l`)
- **Pinned Engine Submodule**: `engine/starling` @ `fa548eb7abe91c194d3780e4580b9746323cd606` (`v0.7.8-2-gfa548eb7ab`), modified ONLY via `engine/patches/0001`–`0007`
- **Latest Green CI Run**:
  - `CI`: https://github.com/kaito65452/BlazeLauncher/actions/runs/43753608691 (success)
  - `Parallel Fast Checks`: https://github.com/kaito65452/BlazeLauncher/actions/runs/43753608720 (success)
  - **`signed-apks` Artifact ID**: `14177729490` (`386,867,106` bytes)
  - **`horizon-engine-arm64` Artifact ID**: `14177196915` (`28,126,598` bytes; `libhorizon_android.so` release/stripped `52,021,912` bytes)
  - **`ci-host-tools` Artifact ID**: `14178005124` (`2,533,308` bytes)
  - **Produced `versionCode`**: `27546411` (`android:debuggable=false`, cert SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`)
- **Repository Variables (`gh variable list -R kaito65452/BlazeLauncher`)**:
  - `BUNDLE_ENGINE_DATA = true` (updated ~13h ago)

## What Works vs UNVERIFIED / BLOCKED
- **Verified in CI**:
  - Guards & self-tests (`scripts/guard.sh`, `scripts/test_guards.sh`, `scripts/scope_guard.sh`, `scripts/check_no_zero_files.sh`, `scripts/check_preflight_workflow.sh`).
  - `engine/starling` arm64-v8a release `.so` compilation with patches `0001`–`0007`.
  - `blaze-prepare` Rust unit/e2e tests (`MockHttp` bundled + full prepare) and `horizon-prepare` tests.
  - `K2_ORACLE` parity verification between upstream `horizon-prepare` and `blaze-prepare`.
  - Release-signed APK build (`android:debuggable=false`, R8 enabled, monotonic `versionCode > 27321741`).
- **Reported on Physical Device (`SM-N975F`, prior user testing before Stage L)**:
  - User reports the engine runs Minecraft 26.3 on the phone (Vulkan, Mali-G76) with the engine's own touch controls and Horizon overlay ("H" button, "...", Control Hub, "Horizon 26.3 (Rust)" watermark, touch HUD buttons at top centre), and falls back to the engine's "Horizon Android" launcher menu on Back/quit.
  - *Note*: Task K `BUNDLE_ENGINE_DATA=true` APK (`43753608691` / `14177729490`) has **not** yet been verified on device (`UNVERIFIED`).
- **UNVERIFIED (CI cannot execute)**:
  - Physical device execution of `BUNDLE_ENGINE_DATA=true` APK (`nativePrepareFromBundledZip`), Android `:jvm` `Internal-25` fallback path, on-device Vulkan/Mali rendering of latest Stage K build.
- **BLOCKED**:
  - `preflight-real.yml` `workflow_dispatch`: GitHub Actions requires workflow files to exist on the default branch (`main`) before `workflow_dispatch` is allowed (`HTTP 404` on `feat/*`); waiting for human PR merge to `main`.

## Exact Next Step
1. **Stage L-A (Step A0)**: Inspect `engine/starling` + `engine/patches/0001`–`0007` + `RustActivity.kt` / `BlazeNativeBridge.kt` to write `docs/ENGINE_EMBEDDING_API.md` (KEEP list, REMOVE/feature-gate list, minimal JNI surface, remaining Android UI/Activity dependencies).
2. **Stage L-A (Steps A1–A5)**: Implement no-fallback-menu exit/return, `blaze-embedded` Cargo feature stripping Horizon UI/non-gameplay network, launcher-owned settings JSON, CI synthetic tests & guards, Copy report additions, `docs/MORNING_TEST_STEPS.md`, `docs/STAGE_LA.md`, green CI + signed APK, update `docs/HANDOFF.md`.
3. **Stage L-B (Steps B1–B9)**: ZL2 desktop-style input bridge (`docs/ZL2_CONTROLS_BRIDGE.md`, Rust injector queue, Kotlin `RustInputBridge`, cursor grab callback, soft keyboard owner, overlay hosting, desktop input mode + fallback toggle, CI tests/guards, `docs/STAGE_LB.md`, `docs/MORNING_TEST_STEPS.md`, green CI + signed APK, update `docs/HANDOFF.md`).
