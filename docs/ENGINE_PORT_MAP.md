# Horizon Engine Port Map & Architectural Blueprint

This document details the architectural mapping and integration strategy for porting the Starling / Horizon Rust Minecraft engine into Blaze Launcher (based on Zalith Launcher 2).

---

## 1. Engine Repositories & Workspace Layout

### 1.1 Repository Locations
- **Upstream Engine Repository**: `starling-client/starling` (Minecraft: Java Edition 26.3 rewritten in pure Rust using wgpu/Vulkan).
- **Android Fork**: `kaito65452/horizon-android` (verified via `gh api repos/kaito65452/horizon-android --jq .parent.full_name` returning `starling-client/starling`).
- **Local Development Clone**: `/data/data/com.termux/files/home/horizon-android`.

### 1.2 Cargo Workspace Members Categorization
Inspecting `~/horizon-android/Cargo.toml` identifies the following crates across 4 architectural categories:

| Category | Crate Name | Description / Responsibilities |
|---|---|---|
| **(a) ENGINE** | `crates/horizon-client` | Game loop, world ticking, remote/integrated server simulation, client state |
| | `crates/horizon-client-core` | Low-level primitives: NBT, lang strings, snbt, scoreboard, registries |
| | `crates/horizon-client-render` | Vulkan/wgpu graphics, chunk meshing, lighting, HUD, camera, fog, sky |
| | `crates/horizon-client-input` | Keybindings, mouse handling, game controller mapping, input hosts |
| | `crates/horizon-world` | World generation, block physics/behaviors, redstone, entity ticks |
| | `crates/horizon-core` | Math (`jmath`), HotSpot-exact Java `Random`, noise, density functions |
| | `crates/horizon-protocol` | Minecraft network protocol 777 codecs, packets, encryption |
| | `crates/horizon-items` | Blocks, items, enchantments, components, item data |
| | `crates/horizon-command` | Brigadier command tree parser and argument execution |
| | `crates/horizon-shaders` | WGSL shader pipeline and clean-room shader format |
| | `crates/horizon-shader-api` | Shader pack format specifications (.hshader) |
| | `crates/horizon-optifine` | OptiFine format compatibility layer |
| | `crates/voicechat-engine` | Native 3D proximity UDP voice chat with RNNoise & AGC (ported by Asano Saryu) |
| | `crates/horizon-dualsense` | PS5 DualSense haptics, triggers, and adaptive lights |
| | `crates/horizon-threads` | Real-time thread priority scheduling |
| | `crates/horizon-hdr` | HDR display colorspace management |
| **(b) ASSETS DOWNLOADER** | `crates/horizon-assets` | Resource decoding, item/block model baking, atlas generation |
| | `crates/horizon-prepare` | Java exporter tool for asset preparation |
| **(c) LAUNCHER UI (EXCLUDE)**| `launcher/` | Desktop GPUI launcher (excluded: not compatible with Android) |
| | `crates/horizon-telemetry-server`| Server-side metrics ingest server (excluded) |
| | `crates/horizon-mod-registry` | Remote mod registry backend (excluded) |
| | `xtask` | Desktop task automations (excluded) |
| **(d) ANDROID / JNI GLUE** | `crates/horizon-android` | Android `cdylib` crate targeting `android_main.rs` |
| | `crates/horizon-client/src/android/` | JNI bridge (`jni_bridge.rs`), documents (`documents.rs`, `document_provider.rs`) |

---

## 2. Submodule Strategy & Thin Cargo Workspace

### 2.1 Git Submodule Inclusion
To include the engine without polluting the Android app repository with upstream commit churn:
```bash
git submodule add https://github.com/kaito65452/horizon-android.git engine/starling
```
The submodule is pinned to a specific stable commit.

### 2.2 Thin Workspace in `engine/`
A root `engine/Cargo.toml` workspace will build only the Android cdylib target:
```toml
[workspace]
members = [
    "starling/crates/horizon-android"
]
resolver = "2"
```
The CI workflow builds only the required target:
```bash
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 -o ../ZalithLauncher/src/main/jniLibs build --release -p horizon-android
```
Features enabled on `horizon-android`:
- `audio`: Enables `cpal` with OpenSL ES / AAudio on Android.
- `mods`: Enables native `.hmod` sandboxed mod loading.

---

## 3. JNI Export Analysis & Surface Integration

### 3.1 Existing JNI Function Exports
The existing JNI bridge in `crates/horizon-client/src/android/jni_bridge.rs` exports the following C symbols:

1. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeInit`
2. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeGetConfigJson`
3. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSaveConfigJson`
4. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeAreAssetsReady`
5. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeLaunchGame`
6. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeStartMultiThreadDownload`
7. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeGetDownloadProgressJson`
8. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSetKeepAliveEnabled`
9. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSetComplexVoiceChatConfig`
10. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeFetchCatalogMods`
11. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeGetControlLayoutJson`
12. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSaveControlLayoutJson`
13. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeExportWorlds`
14. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeGetDiagnosticsJson`
15. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeGetVersion`
16. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeResetSettings`
17. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSignalEngineExit`
18. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeGetAccountsJson`
19. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeGetActiveAccessToken`
20. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSelectAccount`
21. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeAddOfflineAccount`
22. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeAddElyByAccount`
23. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeStartMicrosoftDeviceLogin`
24. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativePollMicrosoftDeviceLogin`
25. `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeDeleteAccount`

### 3.2 Target Kotlin Class & JNI Symbol Renames
Target Kotlin class in Blaze Launcher:
- **Package**: `io.github.kaito65452.blazelauncher.rustruntime`
- **Class**: `HorizonBridge`

Exact renamed symbols:
`Java_io_github_kaito65452_blazelauncher_rustruntime_HorizonBridge_<functionName>`
For example:
- `Java_io_github_kaito65452_blazelauncher_rustruntime_HorizonBridge_nativeInit`
- `Java_io_github_kaito65452_blazelauncher_rustruntime_HorizonBridge_nativeLaunchGame`
- `Java_io_github_kaito65452_blazelauncher_rustruntime_HorizonBridge_nativeAreAssetsReady`

### 3.3 Window & Surface Integration
- **Current Windowing**: `horizon-android` currently expects `android.app.NativeActivity` via `winit`'s `android-native-activity` backend, where the native OS calls `ANativeActivity_onCreate` and provides `ANativeWindow` lifecycle events via callbacks.
- **Surface Adaptation for Blaze Launcher**:
  To render inside ZL2's activity lifecycle (such as `VMActivity` or a dedicated `SurfaceView`), the engine must support accepting an explicit Android `Surface` from Kotlin:
  1. Kotlin passes `surface: Surface` to JNI:
     ```kotlin
     external fun nativeSetSurface(surface: Surface, width: Int, height: Int)
     ```
  2. In Rust, acquire `ANativeWindow`:
     ```rust
     let window = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface.as_raw());
     ```
  3. Create a `raw_window_handle::AndroidNdkWindowHandle` pointing to `window` and bind it into `wgpu::Instance::create_surface`.

---

## 4. Assets Downloader Comparison & Recommendation

### 4.1 Comparison Matrix

| Feature | Horizon Downloader (`horizon-assets` / JNI) | ZL2 Downloader (`ZalithLauncher/game/download`) |
|---|---|---|
| **Primary Target** | Vanilla 26.3 assets, `engine-data.zip`, baked models | Mojang version manifest, client jars, Forge/Fabric libraries |
| **Concurrency** | Multi-threaded async worker pool via `ureq`/threads | Multi-process/coroutine batch downloader with thread pooling |
| **Checksums** | SHA-1 / SHA-256 validation per file | SHA-1 hash checks matching Mojang version manifests |
| **Mirroring** | BMCLAPI, Mojang Official | Official Mojang, BMCLAPI, MCBBS mirrors configurable |
| **Resume Support** | Range request HTTP resumption | Chunked resume with `.part` state tracking |

### 4.2 Recommendation & Engine Post-Processing
- **Recommendation**: Use ZL2's mature Android download pipeline (`game/download`) for initial version manifest querying and official asset downloads, since it already features robust mirror failover, background KeepAliveService management, and notification progress reporting.
- **Engine Post-Processing Required**:
  1. Extraction of `engine-data.zip` into `HORIZON_HOME/assets/`.
  2. Extraction of font textures and default shaders.
  3. Model geometry pre-baking via `horizon-assets`.

---

## 5. Zalith Launcher 2 Core Hook Points

The integration points within ZL2 are restricted to:

1. **(i) Version Model**:
   - Files: `ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/version/installed/Version.kt` and `VersionConfig.kt`.
   - Hook: Add `runtime: GameRuntime` (`JAVA` vs `RUST_HORIZON`).
2. **(ii) Game Launch Dispatcher**:
   - Files: `ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/GameLauncher.kt` and `Launcher.kt`.
   - Hook: When `runtime == RUST_HORIZON`, dispatch to `HorizonBridge.nativeLaunchGame` instead of starting the Java JVM process (`JvmLauncher`).
3. **(iii) Control Layout & Input Sink Interface**:
   - Files: `ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/support/touch_controller/TouchController.kt`, `ui/control/event/Events.kt`.
   - Hook: Introduce an `InputSink` interface that decouples LWJGL's `CallbackBridge` from the touch layout buttons, routing key, mouse, and touch coordinates directly to `HorizonBridge` when running the Rust engine.
4. **(iv) Account Object**:
   - Files: `ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/account/Account.kt` and `AccountsManager.kt`.
   - Hook: Pass username, UUID, and accessToken to `HorizonBridge.nativeSelectAccount` / launch args.
5. **(v) Navigation**:
   - File: `ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/screens/main/MainScreen.kt`.
   - Hook: TopBar menu item and `entry<RustEditionNavKey>` in `NavigationUI`.
6. **(vi) Native Libraries & ABIs**:
   - Supported ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` located in `ZalithLauncher/src/main/jniLibs/<abi>/`.

---

## 6. Risks & Unverified Items

1. **Vulkan Device Support [UNVERIFIED]**: The Horizon engine requires Vulkan 1.3+ driver support. On older Mali GPUs or Android 8-9 devices, Vulkan support may be incomplete or lack required extensions (`VK_KHR_synchronization2`).
2. **Surface Lifecycle Synchronization [UNVERIFIED]**: Suspending and resuming the Android activity while `wgpu` holds an active swapchain surface can cause driver panics if surface loss events are not handled gracefully.
3. **Audio Latency [UNVERIFIED]**: `cpal` OpenSL ES fallback vs AAudio on various Android SoC vendors may exhibit different buffer size behaviors.
4. **Multi-Architecture Native Build Memory [UNVERIFIED]**: Building 4 ABI targets (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) in GitHub Actions runners requires monitoring runner disk space and memory limits.
