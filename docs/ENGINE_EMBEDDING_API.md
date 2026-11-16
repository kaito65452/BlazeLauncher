# Engine Embedding API (`blaze-embedded`) — KEEP / REMOVE Inventory & JNI Contract

> **Scope**: Pinned `engine/starling` submodule @ `fa548eb7abe91c194d3780e4580b9746323cd606` + `engine/patches/0001`–`0007`.
> **Legal Note**: Licensing remarks in this document are **technical screening, not legal advice** (see [LICENSE_PLAN.md](LICENSE_PLAN.md)).

---

## 1. KEEP List (Mandatory Android GPU, Performance, Audio, Surface & Lifecycle Modules)

Later removals or feature-gating under `blaze-embedded` **must never** delete or disable any item below. An additive CI check verifies these symbols/modules remain compiled and present in the `blaze-embedded` build.

### 1.1 GPU, Rendering, Surface Lifecycle & Mali/Vulkan Patches
- **`crates/horizon-client-render/src/gfx.rs`**:
  - Android Vulkan/Mali & GLES renderer selection, `open_device_with` (`gfx.rs:1470-1499`), `open_backend` (`gfx.rs:1537-1675`), `memory_hints` (`gfx.rs:1628`), `recreate_surface` / `suspend_surface` (`gfx.rs:3989-4050`, `gfx.rs:8740-8776`), `multi_draw` / mobile pipeline flags (`gfx.rs:3597-3680`).
  - Blaze telemetry/perf hooks from `0003` & `0005`: `BlazeWgpu` logs (`gfx.rs:1380-1390`, `1483-1490`), `SURFACE_START_AT`, `BlazePerf pipelines_created_ms`, `BlazePerf surface_to_first_frame_ms`.
- **`crates/horizon-client-render/src/gfx/arena.rs:150-154`**: 64-bit/32-bit Android GPU arena growth limits.
- **`crates/horizon-client-render/src/gfx/cull.rs:191`**: Android occlusion culling gating.
- **`crates/horizon-client-render/src/gfx/flat.rs:122-146`**: Android `MIN_SECTIONS` and terrain batching policy.
- **`crates/horizon-client-render/src/gfx/hdr.rs:541-558`**, **`pack_post.rs:831,1877`**, **`pack_shadow.rs:86`**, **`world_post.rs:265`**, **`panorama.rs:25-35`**: Android shader/post/shadow/panorama sizing & compatibility.
- **`crates/horizon-client-render/src/graphics.rs:498-505,647-666,905`** & **`graphics_ui.rs:358`**: Android default culling/worker/graphics limits.
- **`crates/horizon-client/src/android/display.rs:1-222`**: `RenderScale`, `Aspect`, `ScreenPreferences`, `set_scale`, `prepare`, `apply` (viewport resolution scaling and surface resize reconciliation).

### 1.2 Audio, Performance, Threading & Device Runtime
- **`crates/horizon-client-render/src/audio.rs:150-175`**: Android audio output (`Audio::with_device`) and disposable sound cache budget (`cache_budget_overridden`).
- **`crates/horizon-client-render/src/android_runtime.rs:1-271`**: `configure_graphics`, `configure_gpu`, `gpu_batching`, `compatibility`, `graphics_startup` (`configure`/`recover`), `configure_worker_limit`, `limit_workers`, `configure_cache_budget`, `mesh_cache_budget`, `sound_cache_budget`, `configure_atlas_limit`, `atlas_limit`, `set_experimental_leaf_culling`, `configure_cache_dir`, `ClipboardHooks` (`install_clipboard`), `active_input` / `configure_input` / `note_input` / `gamepad_event`, `return_to_launcher` / `take_launcher_return`.
- **`crates/horizon-client/src/android/device_runtime.rs:1-217`** (+ **`device_gyro.rs`**, **`device_math.rs`**, **`device_preferences.rs`**): `ANativeWindow_setFrameRate`, Android Performance Hint Manager (`APerformanceHint_getManager`, `APerformanceHint_createSession`, `APerformanceHint_closeSession` ADPF), gyro sensor, `DevicePreferences`.
- **`crates/horizon-client/src/android/performance.rs`**: Local performance profiling session (`current-session.json` / local metrics only, zero network upload).
- **`crates/horizon-client/src/android/diagnostics.rs:1-532`** (+ **`native_tombstone.rs`**, **`exit_reports.rs`**): Local bounded `diagnostic.log` ring buffer, `current-session.json` breadcrumbs, `ApplicationExitInfo` / native tombstone reader.
- **`crates/horizon-threads/src/lib.rs`**: Worker & server thread priority helpers (`put_helpers_behind`, `favour_current`, `stop_requests`).

### 1.3 Lifecycle, Session, Resources, DNS, SAF & Fonts
- **`crates/horizon-client/src/android_main.rs:124-281`**: `android_on_create`, `android_main`, `APP`, `backend`, `init_logging`, `DIRECT_LAUNCH` / `nativeSetDirectLaunch` (`0006`), runtime unbundled font extraction (`0001`).
- **`crates/horizon-client/src/android/game_launch.rs:1-105`**: `LaunchRequest`, `start(request)`.
- **`crates/horizon-client/src/android/platform.rs:1-755`**: Android clipboard (`clipboard_get`/`clipboard_set`), haptics (`vibrate`/`cancel_vibration`), `display_density`, `display_rotation`, `capture_mouse`, `finish`, keyboard/gamepad polling hooks.
- **`crates/horizon-client/src/android/native_ime.rs:1-765`**: `initialize`, `configure_screen` (display cutout & immersive flags), `Capture`, `ImportDocument`, `viewport_bottom`, system IME bridge (until Stage L-B selects single IME owner).
- **`crates/horizon-client/src/android/document_bridge.rs:1-63`**, **`documents.rs`**, **`document_store.rs`**: Android SAF document picker (`requestCode = 4101`) & `nativeDocumentResult` JNI callback for world/pack imports.
- **`crates/horizon-client/src/android/dns.rs`**: `query_srv` (Android system DNS SRV lookup for multiplayer servers).
- **`crates/horizon-client-render/src/auth.rs:110-185`** (`0003`, `0006`): `AuthSession`, `stage_pending_android`, `take_pending_android`, `has_pending_android`, `clear_pending_android`, `token_len`, plus Mojang `sessionserver.mojang.com` server-join authentication using the handed-in session.
- **`crates/horizon-client/src/android/blaze_diagnostics.rs:1-94`** (`0003`): `nativeSetSession`, `nativeInjectSyntheticTouch`.
- **`crates/horizon-client/src/android/jni_bridge.rs`** (`0002`, minimal embedded subset): `nativeInit`, `nativeAreAssetsReady`, `nativeGetVersion`, `nativeGetDiagnosticsJson`, `nativeSignalEngineExit`, `nativeGetConfigJson`, `nativeSaveConfigJson`.
- **`crates/horizon-client/src/android_resources.rs:1-311`** & **`official_install.rs`**: `repair_structure_transforms`, `rebuild`, `official_install::ready`.
- **`crates/horizon-client/src/ui_theme.rs:152-220`** & **`crates/horizon-theme/src/lib.rs:23-39`** (`0001`): Runtime unbundled `Option<FontVec>` font loading (no `include_bytes!` OFL bytes).
- **`crates/horizon-client/src/title.rs:2338-2355`**: `"Not affiliated with Mojang or Microsoft"` disclaimer on Minecraft's TitleScreen, plus vanilla Minecraft screens (`Title`, `Select World`, `Multiplayer`, `Options`, `Credits`).
- **`engine/patches/0007-pin-refresh-26.3.patch`** (`research-lock.json` 26.3 manifest pins).
- **`ZalithLauncher/src/main/assets/THIRD_PARTY_NOTICES.md`** & **`assets/fonts/{Geist-*.ttf,OFL-Geist.txt}`**.

---

## 2. REMOVE / Feature-Gate List (`blaze-embedded` Cargo Feature)

The `blaze-embedded` Cargo feature (enabled on `horizon-android` for Blaze builds; default off on desktop/upstream) strips all Horizon launcher UI, overlay menus, watermark, and non-gameplay network code while keeping patches small and rebase-friendly.

### 2.1 Horizon Launcher Window & Pages
- **`crates/horizon-client/src/android/launcher.rs:32-1069`**:
  - `Launcher::open` (`320-377`, creates `"Horizon Android"` window), `Launcher::restore_launcher` (`256-300`), `Launcher::request_back` (`379-436`), `Launcher::action` installer/account UI (`438-678`), launcher page drawing/input (`681-1069`).
  - In `blaze-embedded`: `Launcher` becomes a minimal direct-launch lifecycle driver; missing session/resources or any exit/error returns cleanly to Blaze Launcher without ever opening `"Horizon Android"`.
- **`crates/horizon-client/src/android/launcher_pages.rs:1-7136`**: Entire native Horizon UI (Play/Mods/Shaders/Skins/Settings/Installed/Diagnostics modal; updater link `launcher_pages.rs:5952`).
- **`crates/horizon-client/src/android/launcher_gesture.rs:1-end`**: Launcher page scroll/drag gestures.
- **`crates/horizon-client/src/android/chooser.rs:1-end`**: Launcher modal chooser dialogs.
- **`crates/horizon-client/src/android/gallery.rs:1-end`**: Launcher world gallery/export UI.
- **`crates/horizon-client/src/android/login.rs:1-end`**: In-engine Microsoft/Ely.by OAuth login UI.
- **`crates/horizon-client/src/android/wardrobe.rs:1-1235`**: In-engine skin/cape wardrobe UI & Mojang skin template fetch (`wardrobe.rs:115`) / Ely.by skins link (`wardrobe.rs:327`).
- **`crates/horizon-client/src/android/shader_catalog.rs:1-end`** & **`search_queue.rs:1-end`**: Shader/mod catalog UI queues.
- **`crates/horizon-client/src/android/catalog.rs:1330-2214`**: Modrinth/Horizon mod browser & download flows (`catalog.rs:1334-1744, 2088-2165`). Retain only minimal `Config`/`RendererMode`/`Orientation`/`InputHost` types needed by runtime config.
- **`crates/horizon-client/src/android_main.rs:14-21,52-65`**: Desktop launcher module imports (`launcher/src/account.rs`, `launcher/src/browser.rs`, `launcher/src/proxy.rs`, `launcher/src/skins.rs`).

### 2.2 Engine Overlay ("H" Button, Control Hub UI, Watermark)
- **`crates/horizon-client/src/touch_ui.rs:1621-2650+` (plus Control Hub modal tabs/render)**: `floating_menu_touch` (`1621-2505`) and `draw_floating_menu` (`2510+`, renders the `"H"` / `"="` floating button and Horizon Control Hub modal).
- **`crates/horizon-client/src/game.rs:15165`**: `self.pad.touch_ui.draw_floating_menu(...)`.
- **`crates/horizon-client/src/game/android_input.rs:695`**: `self.pad.touch_ui.floating_menu_touch(...)`.
- **`crates/horizon-client/src/game/startup_menu.rs:1570, 2520`**: `floating_menu_touch` and `draw_floating_menu` on the loading screen.
- **`crates/horizon-client/src/title.rs:2331-2337`**: `"Horizon {} (Rust)"` watermark (`2331-2332`) and `nightly_label` (`2335-2337`). (**KEEP** `"Not affiliated with Mojang or Microsoft"` at `title.rs:2338-2355`).
- *Stage L-A note*: Touch controls themselves (`self.pad.touch`, `draw_goy_hud` at `touch_ui.rs:1393` / `game.rs:15147`) remain active during Stage L-A and are gated in Stage L-B (B6(d)) once the ZL2 input bridge replaces them.

### 2.3 Non-Gameplay Network Features
| Feature | Source (`file:line`) | Endpoint / Target | Action under `blaze-embedded` |
| :--- | :--- | :--- | :--- |
| **Telemetry / Heartbeat** | `crates/horizon-telemetry/src/lib.rs:42`, `live.rs:104`, `upload.rs:61`, `crates/horizon-client/src/heartbeat.rs:23-74`, `crates/horizon-client-render/src/telemetry.rs:298-311`, `game.rs:21281` | `https://telemetry.playhorizon.dev` | Compile-time no-op (`horizon_telemetry::endpoint()` -> `None` / skip `heartbeat::start()` & `outbox::push()`) |
| **Official Server Status / Auto-Prepend** | `crates/horizon-client/src/multiplayer.rs:308-356`, `game.rs:21282` | `horizon_telemetry::server_status::OFFICIAL_ADDRESS` | Do not auto-add Horizon Official server in `blaze-embedded` |
| **Mod / Shader Browser** | `launcher/src/browser.rs`, `browser/curseforge.rs:9`, `browser/download.rs:13-18`, `browser/horizon.rs:19-36`, `android/catalog.rs:1334-2165`, `android/shader_catalog.rs:200-226` | `api.modrinth.com`, `cdn.modrinth.com`, `api.curseforge.com/v1`, `edge.forgecdn.net`, `horizon-rust.github.io`, `playhorizon.dev/api/mods/*` | Feature-gate out (`not(feature = "blaze-embedded")`) |
| **Updater / Upstream Links** | `android/launcher_pages.rs:5952`, `android/platform.rs:296`, `no_resources.rs:8,16` | `github.com/JuanLuci/starling/releases/latest`, `github.com/Vasysik/horizon-android`, `playhorizon.dev` | Feature-gate out (`no_resources` replaced by clean exit to Blaze Launcher) |
| **Wardrobe / Cloud Skins** | `android/wardrobe.rs:115,327`, `launcher/src/skins.rs` | `assets.mojang.com/SkinTemplates/steve.png`, `ely.by/skins`, skin cloud API | Feature-gate out |
| **In-Engine OAuth / Ely.by** | `launcher/src/account.rs:79-84`, `launcher/src/account/ely.rs:6,136`, `android/login.rs:252` | `login.microsoftonline.com`, `user.auth.xboxlive.com`, `xsts.auth.xboxlive.com`, `api.minecraftservices.com`, `authserver.ely.by` | Feature-gate out (Blaze Launcher owns OAuth and passes `AuthSession` in-memory via `nativeSetSession`) |
| **Discord Rich Presence** | `crates/horizon-client/src/discord_presence.rs`, `game.rs:21299` | Local Discord IPC | Skip `discord_presence::start` in `blaze-embedded` |

**Retained Gameplay Networking**:
- `horizon-protocol` (Minecraft Java 26.3 TCP/UDP client protocol)
- `android/dns.rs` (Android SRV record lookup)
- `crates/horizon-client-render/src/auth.rs` (`sessionserver.mojang.com/session/minecraft/join` using handed-in `AuthSession`)
- `crates/horizon-client/src/server_packs/download.rs` (optional server-pushed resource pack download when joining a multiplayer server)

---

## 3. Minimal JNI Surface (`dev.horizon.android.bridge.BlazeNativeBridge` + `RustActivity`)

All communication between Blaze Launcher (Kotlin) and `libhorizon_android.so` crosses one narrow JNI boundary:

### 3.1 Session, Settings & Lifecycle (Stage L-A)
- `external fun nativeInit(dataDir: String)` -> `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeInit(JNIEnv, JClass, JString)`
- `external fun nativeSetSession(user: String, uuid: String, accessToken: String): Boolean` -> `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSetSession(JNIEnv, JClass, JString, JString, JString) -> jboolean`
- `external fun nativeSetDirectLaunch(enabled: Boolean)` -> `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSetDirectLaunch(JNIEnv, JClass, jboolean)`
- `external fun nativeConfigureEmbedded(settingsJson: String): Boolean` *(or read `<filesDir>/blaze_engine_settings.json` once on startup)* -> `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeConfigureEmbedded(JNIEnv, JClass, JString) -> jboolean`
- `external fun nativeSignalEngineExit(): Boolean` -> `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSignalEngineExit(JNIEnv, JClass) -> jboolean`
- `external fun nativeGetVersion(): String` -> `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeGetVersion(JNIEnv, JClass) -> jstring`
- `external fun nativeGetDiagnosticsJson(): String` -> `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeGetDiagnosticsJson(JNIEnv, JClass) -> jstring`
- `external fun nativeAreAssetsReady(): Boolean` -> `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeAreAssetsReady(JNIEnv, JClass) -> jboolean`

### 3.2 Surface Lifecycle (winit `android-native-activity` / `AndroidApp`)
- Driven directly by Android `NativeActivity` -> `android-activity` -> `winit`:
  - `onNativeWindowCreated` -> `ApplicationHandler::resumed` -> `Renderer::recreate_surface`
  - `onNativeWindowDestroyed` -> `ApplicationHandler::suspended` -> `Renderer::suspend_surface`
  - `onConfigurationChanged` / surface resize -> `WindowEvent::Resized` -> `display::apply`

### 3.3 Exit Callback (Engine -> Kotlin, Stage L-A)
- Engine resolves Activity method via `with_context` (or global Activity ref in `APP`) on any `exit_app`, title-screen Back, Quit, or fatal error:
  - Kotlin target on `RustActivity`: `fun onEngineExit(reasonCode: Int, message: String)`
  - Signature: `(ILjava/lang/String;)V`
  - Fallback if method absent: `platform::finish()` (`Activity.finish()`) + `events.exit()` -> `android_main` cleanly drops `Launcher` and exits `:rust`.

### 3.4 Input Injection & Cursor-Grab Callback (Stage L-B)
- `external fun nativeInjectKey(keyCode: Int, action: Int, modifiers: Int): Boolean` -> `(III)Z`
- `external fun nativeInjectMouseButton(button: Int, action: Int, modifiers: Int): Boolean` -> `(III)Z`
- `external fun nativeInjectCursorPos(x: Float, y: Float): Boolean` -> `(FF)Z`
- `external fun nativeInjectMouseDelta(dx: Float, dy: Float): Boolean` -> `(FF)Z`
- `external fun nativeInjectScroll(dx: Float, dy: Float): Boolean` -> `(FF)Z`
- `external fun nativeInjectChar(codePoint: Int): Boolean` -> `(I)Z`
- `external fun nativeSetInputMode(useEngineTouchFallback: Boolean): Boolean` -> `(Z)Z`
- Engine -> Kotlin callback on `RustActivity`:
  - `fun onCursorGrabChanged(grabbed: Boolean)` — signature `(Z)V`
- Retained SAF callback on `RustActivity` (`document_bridge.rs:16-23`):
  - `private external fun nativeDocumentResult(requestCode: Int, resultCode: Int, uriString: String?)` — signature `(IILjava/lang/String;)V`

---

## 4. Remaining Android UI / Activity Dependencies Outside `BlazeNativeBridge`

1. **`crates/horizon-client/src/android/native_ime.rs:66-160, 290-610`**:
   - Attaches a hidden `android.widget.EditText` via `Activity.addContentView(...)` and drives `InputMethodManager` for text input, queries `Window.getDecorView` insets/bounds, sets `WindowManager.LayoutParams.layoutInDisplayCutoutMode` / `WindowInsetsController` (`Command::Screen`), and calls `View.requestPointerCapture` / `releasePointerCapture` (`Command::Capture`).
   - Also emits `Command::ImportDocument` to open Android's SAF file picker (`requestCode = 4101`).
2. **`crates/horizon-client/src/android/platform.rs:17-260, 290-450`**:
   - Calls `Activity.getSystemService("vibrator" | "clipboard")`, `WindowManager.getDefaultDisplay().getRotation()`, `Activity.setRequestedOrientation(...)`, `Activity.finish()`, and `Intent.ACTION_VIEW` (only for vanilla MC links like credits/EULA if opened from MC options).
3. **`crates/horizon-client/src/android/documents.rs` & `document_bridge.rs:10-63`**:
   - Registers `nativeDocumentResult(IILjava/lang/String;)V` on the Activity class via JNI `RegisterNatives`, and opens granted `content://` FDs via `ContentResolver.openFileDescriptor`.
4. **`crates/horizon-client/src/android/exit_reports.rs:20-140`**:
   - Queries `ActivityManager.getHistoricalProcessExitReasons` (API 30+) for crash/LMK diagnostics.
