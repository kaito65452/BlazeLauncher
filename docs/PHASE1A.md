# Phase 1a Readiness Assessment (`docs/PHASE1A.md`)

> Legal disclaimer: All licensing and provenance remarks in this document are **technical screening, not legal advice**.

## Summary Verdicts

| Workstream | Verdict | Key Finding & Status |
|---|---|---|
| **2.1 Engine data (`engine-data.zip`)** | **NO-GO / BLOCKED (APK bundling)** | Missing `engine-data.zip` causes `Launcher::open` (`launcher.rs:634-638`) to exit with `"This APK is missing its engine data. Install a complete build."`. Upstream generates `engine-data-generated.zip` via `tools/android_prepare.py` (downloads Mojang `26.3` JARs + runs Java 25 exporters) + `tools/android_build.py --public`. Bundling it into our GPL-3.0 APK is blocked pending explicit legal/license clearance in `docs/LICENSE_PLAN.md`. |
| **2.2 Session handoff (`setSession`)** | **GO** (`UNVERIFIED` on device) | Wired `BlazeNativeBridge.setSession(user, uuid, accessToken)` + `RustSessionHandoff.kt` + Rust `PENDING_ANDROID` (`auth.rs`, `game.rs`, `blaze_diagnostics.rs`) so ZL2's Microsoft session transfers into `:rust` memory without invoking Starling's OAuth client (`00000000441cc96b` / `00000000402b5328`). Tokens are never logged and diagnostics redact via `redactSensitiveData` (`MicrosoftAuthErrorHandler.kt:72`). |
| **2.3 Direct launch tracing** | **GO (with Phase 1b assets)** | `Launcher::open` (`launcher.rs:603-632`) builds `LaunchRequest` -> `game_launch::start` -> `game::android_game` (`game.rs:21173`) -> `Options::read_arguments` (`game.rs:513-565`). Direct launch from a ZL2 version entry (`runtime == STARLING`) is feasible without Starling's launcher UI once `.local/horizon-client/<version>/horizon-resources.json` and legal `engine-data.zip` prerequisites exist. |
| **2.4 Synthetic touch input** | **GO** (`UNVERIFIED` on device) | `BlazeNativeBridge.injectSyntheticTouch` hits `android_runtime::note_input(InputMode::Touch)` on `:rust` (`BlazeInput:` log), and `RustActivity` dispatches a `MotionEvent` (`ACTION_DOWN`/`ACTION_UP`) reaching `Launcher::window_event` (`launcher.rs:734`). TouchController integration remains deferred to Phase 1b+. |
| **2.5 Backend & `:rust` diagnostics** | **GO** (`UNVERIFIED` on device) | `gfx.rs` now logs `BlazeWgpu:` candidate order, backend probes, adapter metadata, and device creation (`describe_adapter`, `open_device_with`, `open_backend`). `RustDiagnosticsCollector` captures `:rust` lifecycle trace, API 30+ `getHistoricalProcessExitReasons`, `performance/diagnostic.log`, and redacted same-UID `logcat` into Developer `"Copy report"`. |

---

## Detailed File:Line Evidence

### 2.1 Engine Data (`engine-data.zip`)
- **Failure path on SM-N975F**:
  - `engine/starling/crates/horizon-client/src/android_main.rs:222,249-262`: opens packaged Android asset `engine-data.zip` via `app.asset_manager().open(c"engine-data.zip")` and extracts it to `home/engine-data.zip`.
  - `ZalithLauncher/src/main/java/dev/horizon/android/bridge/BlazeNativeBridge.kt:37-52`: `ensureEngineData(context)` also tries `context.assets.open("engine-data.zip")`, logging a notice when absent.
  - `engine/starling/crates/horizon-client/src/android/launcher.rs:350-377`: `Launcher::open` creates the winit window and opens `game::loading_renderer(..., GraphicsApi::Auto)` (`gfx::open_device`), builds `HudAtlas`, and then at `launcher.rs:634-638` checks: if `!official_install::ready(&self.home)` and `!self.home.join("engine-data.zip").is_file()`, it returns `Err("This APK is missing its engine data. Install a complete build.")`. `Launcher::resumed` (`launcher.rs:687-690`) calls `self.fail(error)` and `events.exit()`.
- **How `engine-data.zip` is created upstream**:
  - `engine/starling/tools/android_prepare.py:55-138`: downloads Mojang `26.3` client/server JARs + assets from `research-lock.json` and runs Java 25 exporters (`horizon-prepare`, `crates/horizon-prepare/src/lib.rs:1-6`, `exporters.rs:1-27`) into `.local/horizon-client/26.3`.
  - `engine/starling/tools/android_build.py:49-93,207`: packages 28 generated lookup/physics/worldgen JSON/NBT files into `engine-data-generated.zip` when invoked with `--public` (removing Mojang textures/sounds/structures per `engine/starling/docs/android-publication.md:27-33` and `engine/starling/tools/check_android_release.py:69-74,98`).
- **Size & license verdict (technical screening, not legal advice)**:
  - **Size**: Unbuilt/unknown in BlazeLauncher (`0 bytes` in repo/APK; exact `engine-data-generated.zip` compressed size is **UNVERIFIED** here because `tools/android_prepare.py` is not run in Blaze CI).
  - **Recommendation**: **NO-GO** for bundling `engine-data.zip` directly inside our GPL-3.0 APK. Exporting the 28 tables requires Mojang's proprietary `26.3` JARs + JDK 25 and embeds game data tables exported from Mojang binaries, which `docs/LICENSE_PLAN.md` has not approved for GPL-3.0 redistribution. Keep `engine-data.zip` excluded from the APK; pursue clean-room lookup generation or user-driven asset preparation on device in Phase 1b+.

### 2.2 Session Handoff & Starling OAuth Audit
- **Hardcoded Starling client IDs found**:
  - `engine/starling/crates/horizon-client/src/android/catalog.rs:22`: `pub const MICROSOFT_CLIENT_ID: &str = "00000000441cc96b";` (`catalog.rs:20` comment notes Nintendo Switch dev registration; fallback at `catalog.rs:168`).
  - `engine/patches/0002-blaze-native-bridge.patch:984`: fallback `"00000000402b5328"` inside `nativeStartMicrosoftDeviceLogin` (contradicts `engine/starling/docs/microsoft-account.md:41` warning against reusing another launcher's client ID). Note Blaze never calls `nativeStartMicrosoftDeviceLogin` in Phase 1a.
- **Session handoff implementation**:
  - `engine/patches/0003-phase1a-bridge-diagnostics.patch`:
    - `crates/horizon-client-render/src/auth.rs`: adds `stage_pending_android` / `take_pending_android` (safe Rust, `#![forbid(unsafe_code)]`, `#[cfg(target_os = "android")]`).
    - `crates/horizon-client/src/game.rs`: `android_game` (`21181+`) restores `take_pending_android()` if `horizon_client::auth::current().is_none()` after `clear_current()`.
    - `crates/horizon-client/src/android/blaze_diagnostics.rs`: exports `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSetSession` which validates `user` (1..=16 chars), `uuid` (`auth::parse_uuid`), non-empty `access_token`, stages both `PENDING_ANDROID` and `auth::set_current`, updates `catalog::Config.name`, and logs only `uuid` + `token_len` (zero raw secret characters).
  - `ZalithLauncher/src/main/java/dev/horizon/android/bridge/BlazeNativeBridge.kt`: adds `nativeSetSession` / `setSession` while leaving all 25 existing exports unchanged.
  - `ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustSessionHandoff.kt`: transfers only authenticated ZL2 Microsoft accounts (`isMicrosoftAccount()`, token non-blank and `!= "0"`) across `:main` -> `:rust` using a single-use `0600` file immediately deleted on read by `RustActivity.onCreate`, redacted with `redactSensitiveData` (`MicrosoftAuthErrorHandler.kt:72`).

### 2.3 Direct Launch Requirements
- **Entry flow**:
  - `engine/starling/crates/horizon-client/src/android/launcher.rs:603-632`: builds `crate::game_launch::LaunchRequest` (`resources`, `game_home`, `session_file`, `offline_name`, `server`, `control_socket`, `extra_arguments`, `open_gl`, `gpu_batching`), calls `crate::game_launch::start(request)`, releases the launcher surface/renderer, and calls `game.resumed(events)`.
  - `engine/starling/crates/horizon-client/src/android/game_launch.rs:5-63` -> `engine/starling/crates/horizon-client/src/game.rs:21173-21192` (`android_game`) -> `game.rs:513-565` (`Options::read_arguments`) -> `loading_app(...)`.
- **Smallest change for Phase 1b (no ZL2 UI overhaul)**:
  1. Keep ZL2's three hook points (`Version.kt` / `VersionConfig.kt` `runtime` field, `GameLauncher.kt` dispatch, input interface).
  2. When launching a `STARLING` version from `GameLauncher.kt`, pass the resolved version/game directory + resource root + launch args to `RustActivity` and add an `android_main` / `Launcher` direct-launch fast-path that builds `LaunchRequest` directly instead of rendering `Launcher::draw_pages()` (with auth already staged via `BlazeNativeBridge.setSession`).
  3. Requires legal `engine-data.zip` resolution (Section 2.1) and prepared `.local/horizon-client/<version>/horizon-resources.json` + vanilla assets matching Starling's expected version.

### 2.4 Input Proof & TouchController Roadmap
- **Phase 1a synthetic proof**:
  - `engine/patches/0003-phase1a-bridge-diagnostics.patch`:
    - `blaze_diagnostics.rs`: `nativeInjectSyntheticTouch(x, y, phase)` invokes `horizon_client::android_runtime::note_input(InputMode::Touch)` and logs `BlazeInput: synthetic touch arrived via BlazeNativeBridge seq=...`.
    - `launcher.rs`: `WindowEvent::Touch(touch)` logs `BlazeInput: winit WindowEvent::Touch arrived on :rust ...` before calling `self.touch_event(events, touch)`.
  - `RustActivity.kt`: invokes `BlazeNativeBridge.injectSyntheticTouch(120f, 240f, 0/2)` on startup, focus, and `"Touch Probe"` overlay button click, plus dispatches synthetic `MotionEvent` (`ACTION_DOWN`/`ACTION_UP`) to `window.decorView`.
- **Remaining work to connect ZL2 `TouchController`**:
  - Starling has its own native touch overlay (`ensure_factory_touch_layout`, `horizon-touch.json` in `game.rs:21175`), whereas ZL2's `TouchController.kt` drives Pojav/LWJGL Java view callbacks.
  - Phase 1b+ must either (a) bridge ZL2 overlay control events through new `BlazeNativeBridge` JNI input callbacks into `android_input` / `horizon-client-render::touch` while suppressing duplicate `MotionEvent` handling, or (b) use Starling's native touch controls in `:rust` with `TouchController` disabled for `STARLING` runtime sessions.

### 2.5 Rendering / Backend Diagnostics
- `engine/starling/crates/horizon-client-render/src/gfx.rs:1372-1379,1468-1484,1531-1670` now emits `BlazeWgpu:` `INFO`/`WARN` log lines (`TAG="Horizon"`) for `open_device_with` candidates, `open_backend` probes, `describe_adapter` (`backend`, `name`, `device_type`, `vendor`, `device`, `driver`, `driver_info`), and device open confirmation.
- `RustDiagnosticsCollector.kt` records `:rust` process lifecycle transitions to app-private `rust_spike_last_status.txt`, queries `ActivityManager.getHistoricalProcessExitReasons` (`:rust` on API 30+), reads `filesDir/performance/{current-session.json,diagnostic.log}`, and pulls same-UID `logcat` lines redacted via `redactSensitiveData` (`MicrosoftAuthErrorHandler.kt:72`) + JWT/XBL sanitizers into `"Copy report"`.

---

## Top 3 Risks

1. **Legal/Asset Block on `engine-data.zip` (High / Blocking for playable Phase 1b)**: Starling requires `engine-data.zip` + vanilla `26.3` resources, yet `tools/android_prepare.py` extracts tables from Mojang JARs. Until a clean-room generator or compliant user-side acquisition flow is implemented and documented in `docs/LICENSE_PLAN.md`, redistributing `engine-data.zip` inside the APK remains `NO-GO`.
2. **Early `:rust` Exit Before Sustained `winit` Loop (`engine-data.zip` Missing) (Medium)**: Because `Launcher::open` exits on line 634 when `engine-data.zip` is missing, `wgpu` backend selection (`line 350`) runs, but `winit` exits the loop before sustained user interaction. Testing full in-game `MotionEvent` routing requires provisioning test data locally or adding a stub diagnostic surface mode.
3. **Separate Process Boundary & `android:debuggable=false` Log Visibility (Low–Medium)**: `:rust` runs out-of-process (`android:process=":rust"`). While same-UID `logcat` + shared `filesDir` files normally bridge across processes on release builds, OEM SELinux/logd buffer limits can truncate `logcat`; persisting `rust_spike_last_status.txt` and Starling's `performance/diagnostic.log` tail guarantees `"Copy report"` retains diagnostic context even if `logcat` rotates.

---

## Physical Device Test Steps (SM-N975F / Android 12)

1. Download the signed release APK from CI artifact **`signed-apks`** (`BlazeLauncher-*.apk`). Verify in-place install compatibility (`android:debuggable=false`, cert SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`, strictly higher epoch `versionCode`). Install over the existing signed build (`adb install -r` or package installer).
2. Confirm Developer Mode is hidden by default: open **Settings** and verify no `"Developer"` section or Rust Spike entry appears.
3. Unlock Developer Mode: go to **Settings -> About**, tap the version entry 5 times (verify `"N more taps to enable developer mode"` after tap 2 and `"Developer mode enabled"` at tap 5).
4. Open **Settings -> Launcher -> Developer**, tap **Launch Rust Edition (Spike)**. Expect `:rust` to launch, run `BlazeWgpu` backend probing (`gfx::open_device` via `loading_renderer`), apply `BlazeNativeBridge.setSession` if a Microsoft account is active, inject synthetic touch via JNI + `decorView`, and exit due to missing `engine-data.zip` (or stay open if overlay catches before exit).
5. Return to **Settings -> Launcher -> Developer**, tap **Copy report**, paste into a note/message or inspect on device, and record:
   - `:rust Process Status Trace` (`onCreate`, `session_handoff`, `synthetic_touch`, `onDestroy`)
   - `:rust Historical Process Exits`
   - `BlazeWgpu:` / `Horizon renderer:` lines showing exact `wgpu` backend + Mali GPU adapter
   - `BlazeInput:` lines proving arrival on `:rust`
   - Absence of any raw Microsoft OAuth/MC token strings.
6. Confirm toggle-off: disable **Developer mode** switch in Settings and verify the Developer section disappears cleanly.
