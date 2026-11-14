# Blaze Launcher Phase 1a & Signed Release Integration Report (`docs/PHASE1A_REPORT.md`)

> Legal disclaimer: Any license/provenance assessments below are **technical screening, not legal advice**.

## Overview

Task Q delivers two stages on branch `feat/phase0-spike` ([PR #5](https://github.com/kaito65452/BlazeLauncher/pull/5)):

1. **Part 1 (Completed, Pushed, CI-Green)**: Transitioned all CI APK outputs to signed, R8-optimized, non-debuggable release APKs (`android:debuggable=false`, `isMinifyEnabled=true`, `isShrinkResources=true`), removed build-time `BuildConfig.DEBUG` / `SPIKE_ENABLED` spike gates, introduced persisted `DeveloperMode` (`default=false`) unlocked via 5 taps on the About version card with a Settings toggle, restricted the Developer menu strictly to diagnostics, and enforced static + binary release guards.
2. **Part 2 (Phase 1a Spike Diagnostics & Handoff)**: Diagnosed missing `engine-data.zip` behavior, inspected Starling OAuth/session architecture and hardcoded client IDs, implemented non-token-logging `BlazeNativeBridge.setSession` + cross-process handoff, traced direct-launch requirements for Phase 1b, wired synthetic touch arrival proofs on `:rust`, and added `BlazeWgpu` backend logging plus redacted `:rust` logcat/lifecycle capture inside Developer `"Copy report"`.

---

## Part 1 Results: Signed Release Builds + Developer Mode

### 1.1 Build Type Diagnosis (Task P vs Task Q Part 1)
- **Prior Task P APK (`BlazeLauncher-Debug-2.6_hotfix-blaze.7317ff67.apk`)**:
  - Produced by `./gradlew testDebugUnitTest assembleRelease assembleDebug` in pre-Part-1 `.github/workflows/ci.yml` (`debug-apks` artifact).
  - Even though `debug` build type in `ZalithLauncher/build.gradle.kts:111-117` shared `signingConfigs.getByName("release")`, it had `isMinifyEnabled = false` (R8 off) and `android:debuggable=true` — confirmed locally via `./scripts/guard.sh --check-release-apk debug-apks/BlazeLauncher-Debug-2.6_hotfix-blaze.7317ff67.apk` returning `ERROR: APK has android:debuggable=true (or non-false attr type=0x12 data=0xffffffff)!`.
- **Task Q Part 1 Release-Only Enforcement**:
  - `.github/workflows/ci.yml` now executes `./gradlew testDebugUnitTest assembleRelease` ONLY (`assembleDebug` and `debug-apks` removed), checks `apkanalyzer manifest debuggable ... == false`, runs `./scripts/guard.sh --check-release-apk`, and asserts V2/V3 cert SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc` plus strictly monotonic epoch `versionCode`.
  - `.github/workflows/nightly.yml` additively verifies `android:debuggable=false` (`--check-release-apk` + `apkanalyzer`) and signing certificate SHA-256 prior to publishing.

### 1.2–1.5 Runtime Developer Mode & Guards
- `AllSettings.kt:621`: `developerMode` (`defaultValue = false`), zero `BuildConfig.DEBUG` or `SPIKE_ENABLED` build-time gates remain.
- `AboutInfoScreen.kt`: 5-tap version entry unlock gesture showing `"N more taps to enable developer mode"` after tap 2 and `"Developer mode enabled"` at tap 5 (`DeveloperUnlockHost`).
- `LauncherSettingsScreen.kt` & `DeveloperSection.kt`: `"Developer"` section + `RustEditionScreen` diagnostics visible only when `developerMode` is enabled; includes Settings switch to turn Developer Mode off.
- `scripts/guard.sh` Check 9 (`check_developer_pkg_safety`) & `scripts/test_guards.sh` (21/21 cases pass): blocks any reference to offline-account creation, ownership bypass, or token fields inside `developer/`.
- **Part 1 Verified CI Evidence**:
  - **Run URL**: `https://github.com/kaito65452/BlazeLauncher/actions/runs/43576678893` (commit `ba37207bfacb1305ba6942fe79c929946bd9b910` / PR merge `1c5173d8`)
  - **Artifact**: `signed-apks` (Artifact ID `14107219575`, zip `376,773,746` bytes; APK `380,361,445` bytes / `362.74 MiB`, `lib/arm64-v8a/libhorizon_android.so` `52,226,128` bytes uncompressed)
  - **`apkanalyzer manifest debuggable`**: `false` (`✓ Release APK manifest verified non-debuggable (android:debuggable=false)`)
  - **`apksigner` SHA-256**: `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`
  - **Epoch `versionCode`**: `27421181` (verified strictly `> 27321741` on `nightly`)

---

## Part 2 Results: Phase 1a Engine & Integration Findings

Detailed verdicts, file:line citations, top 3 risks, and SM-N975F validation instructions are documented in [`docs/PHASE1A.md`](PHASE1A.md).

### Summary Table

| Item | Verdict | Summary of Code / Behavior |
|---|---|---|
| 2.1 `engine-data.zip` | **NO-GO / BLOCKED (APK bundling)** | `launcher.rs:634-638` requires `engine-data.zip`; upstream creates `engine-data-generated.zip` from Mojang `26.3` JARs via `tools/android_prepare.py` + `tools/android_build.py --public`. Do not bundle in GPL-3.0 APK without explicit license plan approval (*technical screening, not legal advice*). |
| 2.2 Session Handoff | **GO** (`UNVERIFIED` on device) | Audited `auth.rs:21-82,119-141` and hardcoded Starling IDs (`00000000441cc96b` at `catalog.rs:22`, `00000000402b5328` at `0002-blaze-native-bridge.patch:984`). Wired `BlazeNativeBridge.setSession` + `RustSessionHandoff` + `PENDING_ANDROID` in `auth.rs` / `game.rs:21181+`. Zero raw token logging. |
| 2.3 Direct Launch | **GO (for Phase 1b)** | Traced `LaunchRequest` (`game_launch.rs:5-63`) -> `android_game` -> `Options::read_arguments` (`game.rs:513-565`). Direct launch from ZL2's `Version`/`GameLauncher` hook needs an `android_main`/`Launcher` direct-launch flag plus legal `engine-data.zip` & prepared resources. |
| 2.4 Synthetic Touch Input | **GO** (`UNVERIFIED` on device) | Added `BlazeNativeBridge.injectSyntheticTouch` -> `android_runtime::note_input(InputMode::Touch)` + `WindowEvent::Touch` arrival logging at `launcher.rs:734` + `RustActivity` synthetic `MotionEvent` probe. |
| 2.5 `wgpu` & `:rust` Report | **GO** (`UNVERIFIED` on device) | Added `BlazeWgpu:` logging across `gfx.rs:1372-1670` and cross-process `:rust` lifecycle + `performance/diagnostic.log` + redacted same-UID `logcat` collection in `RustDiagnosticsCollector.kt` -> Developer `"Copy report"`. |

### CI Verification & APK Update Guidance
- **Part 1 CI Run**: `https://github.com/kaito65452/BlazeLauncher/actions/runs/43576678893` (artifact `signed-apks` ID `14107219575`)
- **Part 2 CI Run**: Executes automatically on PR #5 upon pushing the Part 2 commit — inspect the latest run URL on [PR #5 Checks](https://github.com/kaito65452/BlazeLauncher/pull/5/checks) and the final delivery response for raw `gh run view`, `apkanalyzer`, `apksigner`, `versionCode`, and `signed-apks` artifact ID.
- **In-Place Upgrade Compatibility**: Both Part 1 and Part 2 APKs build `assembleRelease` (`android:debuggable=false`), minify with R8, sign with cert SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`, and compute strictly increasing epoch `versionCode` overrides (`git rev-list` / epoch timestamp), allowing seamless update-in-place over the previous signed build (note: upgrading over an `assembleDebug` APK built cleanly only if both shared the release keystore and versionCode < new release versionCode; otherwise uninstall debug first once and stay on signed release builds).

---

## UNVERIFIED Items (Require Physical SM-N975F / Android 12 Hardware)

1. Runtime observation on SM-N975F of `BlazeWgpu:` backend (`Vulkan` vs `Gl`) and Mali GPU adapter name/driver strings (`gfx::open_device` invoked by `Launcher::open` before missing `engine-data.zip` exit).
2. Runtime confirmation on SM-N975F of `"BlazeInput: synthetic touch arrived via BlazeNativeBridge"` (and/or `"winit WindowEvent::Touch"`) in `:rust` `logcat` / `"Copy report"`.
3. Runtime confirmation on SM-N975F of `"BlazeSessionHandoff: staged AuthSession on :rust"` when an authenticated Microsoft account is active in ZL2 (plus verifying zero raw token strings in pasted report).
4. Physical device verification of 5-tap unlock toast sequence, toggle-off hiding Developer section, and `"Copy report"` clipboard + `ActivityManager.getHistoricalProcessExitReasons` (`:rust`) output on Android 12.
5. Exact byte size of `engine-data-generated.zip` from `tools/android_build.py --public` (not built in Blaze CI).
