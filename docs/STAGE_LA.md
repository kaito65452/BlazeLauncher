# Stage L-A Completion Report: Engine UI Strip & No Fallback Menu

**Branch:** `feat/stage-l`  
**Latest Green CI Commit:** `21e9e0bc` (`fix(stage-l-a): use Gson in BlazeEngineSettings and strip remaining embedded markers`)  
**Pinned Submodule:** `engine/starling` @ `fa548eb7abe91c194d3780e4580b9746323cd606` (modified ONLY via [`engine/patches/0008-blaze-embedded-ui-strip.patch`](file:///data/data/com.termux/files/home/BlazeLauncher/engine/patches/0008-blaze-embedded-ui-strip.patch))

---

## Summary of Implemented Changes (A0–A5)

### A0 — Keep/Remove Inventory & Embedding Surface
- Documented in [`docs/ENGINE_EMBEDDING_API.md`](file:///data/data/com.termux/files/home/BlazeLauncher/docs/ENGINE_EMBEDDING_API.md):
  1. **KEEP list (`file:line`)**: Vulkan/Mali-G76 surface & swapchain resilience, pipeline budget/disk cache & warm-up instrumentation (`BlazePerf`), audio & memory hooks, Android SurfaceView/SAF document bridge & cutout/insets integration, non-affiliation disclaimer (`"Not affiliated with Mojang or Microsoft"`), Minecraft gameplay/menus/server auth, `THIRD_PARTY_NOTICES.md`.
  2. **REMOVE / feature-gate list (`file:line`)**: Horizon Android launcher UI (`launcher.rs`, `launcher_pages.rs`), in-game `"H"` floating menu & `"..."` extra-keys/Control Hub (`touch_ui.rs`), HUD editor, watermark (`"Horizon 26.3 (Rust)"`), and non-gameplay endpoints (`telemetry`, `account`, `catalog`/Modrinth, `moved_extras` registry fetch, `heartbeat`, `updater`).
  3. **Minimal JNI surface**: `BlazeNativeBridge` session/runtime/diagnostics/document bridge + `nativeConfigureEmbedded` / `onEngineExit`.
- Added **Check 15** (`--check-embedded-so`) to [`scripts/guard.sh`](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/guard.sh) and self-tests (Section 12) to [`scripts/test_guards.sh`](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/test_guards.sh) verifying 13 mandatory KEEP markers and 0 forbidden Horizon UI/network markers in both the compiled `.so` and release APK.

### A1 — Clean Exit & No Fallback Launcher Menu
- Engine patch `0008` introduces `EmbeddedExitReason` (`None=0, UserQuit=1, TitleBack=2, NoPendingSession=3, MissingResources=4, FatalError=5, SignalOrClose=6`) and `EmbeddedLaunchDecision` in `horizon-client-render/src/android_runtime.rs`.
- Adds `crates/horizon-client/src/android/embedded_launcher.rs` (selected instead of `launcher::open` under `#[cfg(feature = "blaze-embedded")]`):
  - Back on title screen root, Pause -> Quit, fatal errors, missing resources, or missing/resumed pending session call `platform::notify_engine_exit(reason, message)` -> Java `RustActivity.onEngineExit(reasonCode, message)` -> clear transient session handoff files (`clear_session_files` / `clearSession`), record redacted exit reason, and call `finish()` so Blaze Launcher (`MainActivity`) returns to foreground and `:rust` exits cleanly without ever opening `"Horizon Android"`.
  - Android Back inside a live world preserves upstream `pad_back()` pause-menu behavior.
- Added `RustActivity.StartupDecision` (`decideStartup`) in [`RustActivity.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustActivity.kt) so resuming with `savedInstanceState != null` or launching without an accepted session immediately exits back to Blaze Launcher.

### A2 — `blaze-embedded` Cargo Feature & Launcher-Owned Settings
- Enabled `blaze-embedded` by default on `horizon-android` and propagated across `horizon-client`, `horizon-client-render`, and `horizon-telemetry`.
- Feature-gated out: Horizon launcher window/pages, Control Hub (`draw_floating_menu` / `floating_menu_touch` / `"..."` `Action::More` / HUD editor), `"Horizon 26.3 (Rust)"` watermark (`title.rs`), and non-gameplay network calls (`horizon-telemetry`, `moved_extras` remote index `horizon-rust.github.io/horizon-mods`, `catalog` Modrinth fetch, `heartbeat`, `no_resources`, `discord_presence`).
- Kept touch gameplay controls (`self.pad.touch` + `draw_goy_hud`) working for Stage L-A testing, `"Not affiliated with Mojang or Microsoft"`, server-join auth, and `THIRD_PARTY_NOTICES.md`.
- Added launcher-owned settings schema (`blaze_engine_settings.json`, schema v1: `render_scale_percent` 25..100, `touch_look_sensitivity` 0.25..3.0x, `show_controls`, `source`) in [`BlazeEngineSettings.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/BlazeEngineSettings.kt) + [`AllSettings.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/setting/AllSettings.kt) + [`DeveloperSection.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/developer/ui/DeveloperSection.kt) (`strings.xml` + `values-in/strings.xml`), applied at startup via [`RustLaunchDispatcher.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustLaunchDispatcher.kt) & engine `BlazeEngineSettings::load`.

### A3 & A4 — Synthetic Tests, Guards & Developer Copy Report
- **Rust tests**: `.github/workflows/rust-engine.yml` runs `cargo test --locked --manifest-path engine/starling/Cargo.toml -p horizon-client-render --features blaze-embedded --lib android_runtime -- --nocapture` (`7 passed; 0 failed`).
- **Kotlin JVM tests**: [`StageLAEmbeddedTest.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/StageLAEmbeddedTest.kt) verifies `RustActivity.decideStartup`, `BlazeEngineSettings` clamping + Gson JSON roundtrip, and `RustDiagnosticsCollector` Copy report policy + UUID prefix redaction.
- **Developer Copy Report** ([`RustDiagnosticsCollector.kt`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustDiagnosticsCollector.kt)): adds `=== Engine UI & Embedded Policy ===` with:
  - `engine ui: launcher window disabled | embedded feature on`
  - `last_exit_reason: <REASON_LABEL>(<code>) ...`
  - `settings_source: blaze_launcher (blaze_engine_settings.json: ...)`

### A5 — Morning Device Verification Checklist
- Updated [`docs/MORNING_TEST_STEPS.md`](file:///data/data/com.termux/files/home/BlazeLauncher/docs/MORNING_TEST_STEPS.md) in Indonesian covering Back from title, Quit from pause, Leave World, app switch/return, forced disconnect, fresh launch, and launcher-owned settings checks.

---

## Native Library Size Delta (`arm64-v8a`, `libhorizon_android.so`)

- **Before Stage L-A** (baseline run `43753608691`, release stripped): `52,021,912` bytes
- **After Stage L-A** (run `43825267269`):
  - Fast, unstripped: `77,334,728` bytes
  - **Release (`lto=fat`, `panic=abort`, `strip=true`): `49,759,320` bytes**
  - **Net size reduction:** **`-2,262,592` bytes (`-2.16 MiB` / `-4.35%`)**

---

## CI Evidence

- **CI Workflow**: https://github.com/kaito65452/BlazeLauncher/actions/runs/43825267269 (`conclusion: success`)
  - `engine / engine`: `success` (`libhorizon_android.so` `49,759,320` bytes; Check 15: `13 KEEP markers present, 0 forbidden UI/network markers`; `horizon-client-render::android_runtime` `7 passed; 0 failed`; `horizon-prepare` suite `56+1+1+8+1 passed`)
  - `engine / blaze-prepare --release benchmark (aarch64)`: `success`
  - `ci`: `success` (`versionCode=27591674`, `android:debuggable=false`, R8 enabled, signing cert SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`)
  - **`signed-apks` Artifact ID**: `14208549292` (`385,695,035` bytes)
  - **`horizon-engine-arm64` Artifact ID**: `14208635452` (`26,898,660` bytes)
  - **`ci-host-tools` Artifact ID**: `14208156517` (`2,533,308` bytes)
- **Parallel Fast Checks**: https://github.com/kaito65452/BlazeLauncher/actions/runs/43825267625 (`conclusion: success` — `Guards & Self-Tests` + `JVM Unit Tests`)
- **Local Verification**: `check_no_zero_files.sh`, `scope_guard.sh`, `guard.sh`, `test_guards.sh` -> `PASS=73 FAIL=0` (exit 0).

---

## UNVERIFIED Items (Pending Physical Device Test)

- Physical execution on Samsung SM-N975F (Mali-G76) of Stage L-A APK (`signed-apks` ID `14208549292`, `versionCode=27591674`):
  - Absence of `"H"` button, `"..."`, Control Hub, `"Horizon 26.3 (Rust)"` watermark, and `"Horizon Android"` menu during gameplay and exit paths.
  - Clean return to Blaze Launcher on title-screen Back, pause Quit, Leave World, app background/resume, and network disconnect.
  - Runtime effect of `render_scale_percent`, `touch_look_sensitivity`, and `show_controls` from `blaze_engine_settings.json`.
  - Playability with built-in touch controls in Stage L-A (before Stage L-B replaces them with ZL2 controls).
