# Phase 0 Spike Report: Starling Rust Engine Integration

> **Legal Screening Notice**: Any licensing/distribution remarks in this document are **technical screening, not legal advice**.

## 1. Engine Submodule & License Hygiene Summary
- **Submodule**: `engine/starling` -> `https://github.com/starling-client/starling.git`, branch `android`, pinned commit `fa548eb7abe91c194d3780e4580b9746323cd606`.
- **Patches applied at build time** (never modifying the submodule in place):
  - `engine/patches/0001-runtime-fonts.patch`: Removes all compile-time font `include_bytes!` macros from `crates/horizon-theme/src/lib.rs`, `crates/horizon-client/src/ui_theme.rs`, `crates/horizon-client-render/src/font.rs`, `crates/horizon-client/src/game.rs`, and `crates/horizon-server/src/discord.rs`; switches `ui_theme::Fonts` to runtime `Option<ab_glyph::FontVec>` loading with a fontless fallback (`0.55 * px` advance estimation); sets `[profile.release]` to `opt-level = 3`, `lto = "fat"`, `codegen-units = 1`, `panic = "abort"`, `strip = true` (and `[profile.fast]` `strip = "none"` for baseline size comparison).
  - `engine/patches/0002-blaze-native-bridge.patch`: Imports **only** the 3 Rust items listed in `docs/LICENSE_PLAN.md` (`crates/horizon-client/src/android/jni_bridge.rs`, `pub(crate) mod account;` + `pub mod jni_bridge;` + runtime Geist font unpack in `crates/horizon-client/src/android_main.rs`, and the 3 scalar booleans `svc_enabled`, `svc_rnnoise`, `keepalive_enabled` in `crates/horizon-client/src/android/catalog.rs`), plus Kotlin `ZalithLauncher/src/main/java/dev/horizon/android/bridge/BlazeNativeBridge.kt` (#4). No `voicechat-engine`, wallpapers, or Mojang skin assets are imported.
- **Packaged Fonts**: Geist (`Geist-Regular.ttf`, `Geist-Medium.ttf`, `Geist-SemiBold.ttf`, `GeistMono-Regular.ttf`) + `OFL-Geist.txt` under `ZalithLauncher/src/main/assets/fonts/`, alongside `ZalithLauncher/src/main/assets/THIRD_PARTY_NOTICES.md`.

---

## Step A.2: ZL2 Process and Launch Model (Read-Only Analysis)

1. **Process Spawning**:
   - `VMActivity` runs in dedicated child process `android:process=":game"` (`ZalithLauncher/src/main/AndroidManifest.xml:122`).
   - `GameLauncher` (`ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/GameLauncher.kt:77-82`) extends `Launcher` (`Launcher.kt:63-64`) and starts the JVM/game runtime via `launchGame(...)` (`GameLauncher.kt:138, 211`).
2. **Keep-Alive & Foreground Notification**:
   - `GameService` (`android:process=":game"`, `AndroidManifest.xml:187`, `GameService.kt:35`) calls `startForegroundNotification()` (`GameService.kt:40, 49, 61, 67`), invoked from `VMActivity.kt:926, 959, 968` (`startGameService(context)`).
3. **Quit / Exit Lifecycle**:
   - Native / JVM exits propagate to `ZLNativeInvoker.kt:131-133` (`staticLauncher?.onExit?.invoke(exitCode, isSignal)` and `killProgress()`), handled in `VMActivity.kt:204, 245` (`onExit = exitListener`).
4. **Crash / Abnormal Exit Reporting**:
   - Non-zero or signal exits invoke `showExitMessage(this@VMActivity, exitCode, isSignal, logPath)` (`VMActivity.kt:395`), launching `ErrorActivity` (`ErrorActivity.kt:60, 66, 83`, with `showLauncherCrash` at `ErrorActivity.kt:211-212`).
5. **Input Path**:
   - Game-mode handler `GameHandler` (`GameHandler.kt:67-73, 184, 225-226`) and control events (`Events.kt:35, 37`) forward pointer/key events to `CallbackBridge.sendMouseButton(...)` and `CallbackBridge.sendKeyPress(...)`. TouchController proxy integration connects via `TouchController.kt:504, 600` (`LauncherProxyClient.InputHandler`) and `ControllerProxy.kt:40, 55`.
6. **Minimal Hook Points (Proposed — Not Implemented in Phase 0)**:
   - **(i) Version Model (`VersionConfig.kt:37`, `Version.kt:64-68`)**: Add an optional `runtime: String` (default `"java"` vs `"rust"`) field to `VersionConfig` and read/pass it in `Version`.
   - **(ii) Launch Dispatcher (`GameLauncher.kt:77, 138` / `VMActivity.kt`)**: Branch on `version.runtime == "rust"` before spawning JVM; launch `RustActivity` in `android:process=":rust"` with a session handover file and foreground keep-alive service.
   - **(iii) Input Sink Interface (`TouchController.kt:504`, `Events.kt:35-37`, `AbstractHandler.kt:64`)**: Abstract `CallbackBridge` calls behind a small `InputSink` interface implemented separately for JVM/LWJGL (`CallbackBridge`) and Starling (`BlazeNativeBridge` JNI queue).

---

## Step A.3: Device Capability Report & Starling Vulkan Requirement

- **Debug UI**: `RustEditionScreen` (`BuildConfig.DEBUG` only) exposes **Debug: Capability Report** (`DeviceCapabilityReport.kt`) displaying copyable text with: Vulkan hardware version + Vulkan 1.3 flag (`PackageManager.FEATURE_VULKAN_HARDWARE_VERSION`), `Build.SUPPORTED_ABIS`, `AudioManager` sample rate + frames per buffer + estimated buffer latency, and `AcousticEchoCanceler` / `NoiseSuppressor` / `AutomaticGainControl` availability + `AudioEffect.queryEffects()`.
- **Engine Vulkan Requirement Verdict**: Starling does **not** require Vulkan 1.3.
  - `engine/starling/crates/horizon-client-render/src/gfx.rs:1344`: `GraphicsApi::Auto => vec![Backends::VULKAN, Backends::GL]` (automatic GLES fallback if Vulkan is unavailable).
  - `engine/starling/crates/horizon-client-render/src/gfx.rs:1595`: Adapter features are intersected (`adapter.features() & ...`) rather than unconditionally demanding 1.3 features.
  - `engine/starling/packaging/android/AndroidManifest.xml:5-6`: Declares OpenGL ES 3.0 required and Vulkan optional.
  - `engine/starling/third_party/wgpu-hal/src/vulkan/instance.rs:624` and `adapter.rs:284, 381-642, 898, 1005`: Caps `VkApplicationInfo.apiVersion` up to 1.3 (`vk::API_VERSION_1_3`) and gates 1.1/1.2/1.3 features conditionally on `device_api_version`.
- **Device execution output**: `UNVERIFIED` (CI runner has no physical Android GPU/audio hardware).

---

## Step A.4: CI Build (`arm64-v8a`) & Size Report

- Command in `.github/workflows/rust-engine.yml`:
  `cargo ndk -t arm64-v8a --platform 28 build --release --manifest-path engine/starling/Cargo.toml --package horizon-android`
- Profile (`Cargo.toml` patched by `0001-runtime-fonts.patch`): `opt-level = 3`, `lto = "fat"`, `codegen-units = 1`, `panic = "abort"`, `strip = true` (compared against `--profile fast` with `lto = false`, `codegen-units = 16`, `strip = "none"`).
- **CI Build Numbers** (Run [`43472629787`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43472629787) on commit `88ed54ce`):
  - `libhorizon_android.so` **before** (`--profile fast`, unstripped): `81,403,408 bytes` (`77.63 MiB`, `ls -lh`: `78M`)
  - `libhorizon_android.so` **after** (`--release`, `lto = "fat"`, `codegen-units = 1`, `panic = "abort"`, `strip = true`): `52,226,136 bytes` (`49.81 MiB`, `ls -lh`: `50M`; `-29,177,272 bytes`, `-35.84%`)
  - APK entry (`lib/arm64-v8a/libhorizon_android.so`): `52,226,128 bytes` (`49.81 MiB`) uncompressed / `25,615,472 bytes` (`24.43 MiB`) compressed inside `BlazeLauncher-2.6_hotfix-***.d6a1eb87.apk`
  - **Total APK size**: `380,353,781 bytes` (`362.73 MiB`), compared with local baseline APK `BlazeLauncher-2.6_hotfix-blaze.be1defc0.apk` (`354,454,385 bytes` / `338.03 MiB`): **`+25,899,396 bytes` (`+24.70 MiB`)** total APK growth (`24.43 MiB` compressed engine `.so` + `~0.27 MiB` Geist fonts/notices/classes).
  - **APK License & Signing Checks**: `guard.sh --check-apk` scanned all `156` `.so` files under `lib/*/*.so` (`0` font markers / `0` SFNT font headers found), `apksigner verify --print-certs` verified V2 SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`, and `versionCode` `27366515 > 27321741` (nightly) passed.

---

## Step A.5: NativeActivity Spike (`:rust` Process)

- **Plan A Implementation**: `RustActivity : NativeActivity` (`ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustActivity.kt`) declared in `ZalithLauncher/src/main/AndroidManifest.xml:127-137` with `android:process=":rust"`, `android:exported="false"`, `<meta-data android:name="android.app.lib_name" android:value="horizon_android" />`.
- **Kotlin Overlay**: Attached in `RustActivity.onCreate` via `addContentView(...)` displaying a compact top-right HUD pill (`Starling Spike (:rust pid=...)`) and `Exit :rust` button calling `BlazeNativeBridge.signalEngineExit()`, `finishAndRemoveTask()`, and `Process.killProcess(Process.myPid())` (required because upstream `winit` allows only one `EventLoop` per process: `engine/starling/crates/horizon-client/src/android_main.rs`).
- **Plan B Trigger Condition**: Not triggered yet; switch to Kotlin `SurfaceView` + `ANativeWindow_fromSurface` if physical-device testing hits `winit` `android-native-activity` surface or `addContentView` input conflicts.
- **First-frame rendering & logcat evidence**: `UNVERIFIED` (no physical Android device attached in CI).

---

## Step A.6: Input Injection & Minimal New JNI Surface

- Upstream Starling processes input in `crates/horizon-client/src/game.rs:16374-16909` via standard `winit::event::WindowEvent` (`Touch`, `KeyboardInput`, `MouseInput`, `CursorMoved`, `Occluded`).
- **Phase 0**: `BlazeNativeBridge` keeps its existing 25 exports untouched and unused in release paths.
- **Minimal New JNI Surface Needed for Phase 1** (design proposal only — not added to release):
  1. `nativeSetSession(name: String, uuid: String, accessToken: String, xuid: String?)`: Writes temporary memory/session handover for launcher-authenticated MSA accounts without re-running MSA login inside `:rust`.
  2. `nativeStartEngine(dataDir: String, gameDir: String, optionsJson: String)`: Configures `LaunchRequest` and starts headless/game mode directly once assets exist.
  3. `nativeInjectTouch(pointerId: Int, action: Int, x: Float, y: Float)` & `nativeInjectKey(keyCode: Int, pressed: Boolean, mods: Int)`: Enqueues thread-safe input events and wakes `winit` via `EventLoopProxy::send_event(())`.
  4. `nativeShutdown()` (or keep `nativeSignalEngineExit()`): Triggers graceful world save + `event_loop.exit()`.
  5. `nativeGetEngineStatusJson(): String`: Returns lightweight JSON (`state`, `fps`, `last_error`, `resources_ready`).
  6. *(Plan B only, if NativeActivity fails)* `nativeAttachSurface(surface: Surface?, width: Int, height: Int)` using `ANativeWindow_fromSurface`.

---

## Step A.7: Assets Preparation (`horizon-prepare` / `official_install`)

- **Pipeline**:
  - `horizon-prepare` (`engine/starling/crates/horizon-prepare/src/lib.rs`, `sounds.rs`) + runtime installer `official_install.rs:19, 351, 406` + `android_resources.rs:183` populate `.local/horizon-client/<VANILLA_VERSION>/` (`26.3`).
  - Uses Mojang official endpoints validated by `official_url()` (`official_install.rs:108, 147, 201`) plus `engine-data.zip` built by `tools/android_build.py` (generated collision/worldgen/biome tables `generated_engine_file`).
  - Note: `tools/android_build.py` and `crates/horizon-prepare/src/sounds.rs` require full vanilla `assets/minecraft/sounds` + JAR extraction + generated worldgen JSON/NBT tables (`engine-data.zip`). Exact on-device prepared byte footprint is `UNVERIFIED` until `horizon-prepare` / `official_install` runs on device or in a prepare harness.
- **Reuse with ZL2**: `official_install.rs:25` (`configure_download_cache_dir`) isolates downloaded Mojang artifacts in cache; ZL2 could pre-populate that directory via its existing `BatchDownloader` (reducing duplicate Mojang downloads), provided SHA-1 verification and `official_url()` rules match.

---

## Step A.8: Audio V0 (`cpal`)

- `engine/starling/crates/horizon-android/Cargo.toml:66-67` enables `audio = ["horizon-client/audio"]`, which enables `cpal = { version = "0.16", optional = true }` in `horizon-client-render/Cargo.toml:31, 64`.
- Per `engine/starling/Cargo.lock:676-700`, `cpal 0.16.0` on Android pulls `"ndk"` and `"ndk-context"` (Android NDK **AAudio** backend; no Oboe crate dependency).
- `engine/starling/crates/horizon-client-render/src/audio.rs:703` opens `device.default_output_config()?` and creates a `cpal::Stream` (`audio.rs:796-800`) without custom buffer-size or `AAudioPerformanceMode::LowLatency` overrides (latency is device/AudioFlinger default).
- Device output latency and AEC/NS/AGC behavior: `UNVERIFIED` on CI (inspectable on device via Step A.3 capability report).

---

## Step A.9: Auth Ownership & Outbound Network Audit

- **Auth Ownership**: Blaze Launcher (Kotlin, ZL2 auth flow) owns interactive Microsoft / Xbox Live / XSTS authentication and passes `(name, uuid, access_token)` into `:rust` (modeled on `AuthSession::parse` in `crates/horizon-client-render/src/auth.rs:64`).
- **Residual Mojang/External Hosts in Starling**:
  Even when ZL2 supplies `(name, uuid, access_token)` and skips `launcher/src/account.rs` (`login.microsoftonline.com`, `login.live.com`, `user.auth.xboxlive.com`, `xsts.auth.xboxlive.com`), the engine itself still performs outbound HTTPS requests:
  - **Auth / Session / Profile (FLAGGED)**:
    - `https://discovery.minecraftservices.com/minecraft/client` (`DEFAULT_DISCOVERY`, `crates/horizon-client-render/src/auth.rs:16`) for online-mode session `joinServer` discovery.
    - `https://api.minecraftservices.com/minecraft/profile` (`MINECRAFT_PROFILE`, `crates/horizon-client-render/src/auth.rs:498, 503-517` `own_textures()`), using the handed-over `Bearer <access_token>` even after launcher handover.
    - `https://authserver.ely.by/session/join` (`crates/horizon-client-render/src/auth.rs:290`).
  - **Skins & Capes**:
    - `https://api.mojang.com/users/profiles/minecraft/{name}` (`crates/horizon-client/src/skin_head.rs:90`).
    - `https://sessionserver.mojang.com/session/minecraft/profile/{id}` (`crates/horizon-client/src/skin_head.rs:102`).
    - `https://textures.minecraft.net/texture/...` (`crates/horizon-client-render/src/skins.rs:32-33`, `skin_head.rs:120-121`).
  - **Official Vanilla Assets / Preparation**:
    - `https://piston-meta.mojang.com`, `https://piston-data.mojang.com`, `https://resources.download.minecraft.net` (`crates/horizon-client/src/official_install.rs:19, 406, 760`).
    - `https://raw.githubusercontent.com/Mojang/bedrock-samples/...` (`crates/horizon-client/src/android_resources.rs:183`).
  - **Content / Mods / Shaders**:
    - `https://api.modrinth.com/v2` (`crates/horizon-port-pack/src/modrinth.rs:9`), `https://cdn.modrinth.com/`, `https://github.com/`, `https://raw.githubusercontent.com/`, `https://gitlab.com/` (`crates/horizon-port-pack/src/fetch.rs:26-29`), `https://horizon-rust.github.io/horizon-mods/index.json` (`crates/horizon-client/src/moved_extras.rs:16`).
  - **Telemetry**:
    - `https://telemetry.playhorizon.dev` (`crates/horizon-telemetry/src/lib.rs:42`), disabled at build time in `rust-engine.yml` via `HORIZON_TELEMETRY_URL=off`.
