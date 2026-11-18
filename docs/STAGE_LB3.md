# Stage L-B3 — Cursor Alignment, Desktop IME/Chat, and World Render Fix

## Evidence (SM-N975F, L-B2)
- `viewport=1463x694`, `window=1520x720`, `overlay_size=1520x694`, `last_touch_xy_raw=1124.8,370.4` vs `last_touch_xy_mapped=0.5662,0.6632`.
- `keys=200, mouseBtns=72, cursor_pos=1462, mouse_delta=810`, but `chars=0` and `char_events=0`; overlay "IME" button did not work.
- World rendering behind Chat/Inventory/Creative/Options froze (including in multiplayer).
- `Render Startup & Exit Summary` showed `pipelines_created_ms=unavailable` and `backend=unknown`.

## Root Causes & Fixes

### Bug 1 — Cursor / touch offset
- **Root cause**:
  1. `RustActivity.kt` (`DesktopOverlayRoot.dispatchTouchEvent`, L489–490) overwrote `lastTouchRawX/Y` on every overlay `MotionEvent` (including button presses and camera drags), while `RustInputBridge.kt` (`sendCursorPos`, L177–180) only updated `lastMappedX/Y` on cursor events—so `last_touch_xy_raw` and `last_touch_xy_mapped` came from different events and coordinate spaces (`DesktopOverlayRoot` vs inset-padded `ComposeView`).
  2. `RustActivity.kt` (`addOverlayWindow`, L410–423) left `fitInsetsTypes` at default on `TYPE_APPLICATION_PANEL`, shrinking `DesktopOverlayRoot` to `1520x694` while `applySafeDisplayInsets` padded `ComposeView` to `1463x694` (cutout `57 px`), and `updateOverlayGeometry` (L443–457) normalized against `decor` (`1520x720`) instead of the engine's actual `native_content_rect` (`[0, 0, 1520, 694]`).
- **Fix**:
  - Engine patch [`0011-blaze-surface-geometry.patch`](../engine/patches/0011-blaze-surface-geometry.patch): tracks `[surf_x, surf_y, surf_w, surf_h, buf_w, buf_h]` from `native_content_rect` + `renderer.config` in `android_runtime.rs` (`BLAZE_SURFACE_RECT`) and exposes `BlazeNativeBridge.nativeGetSurfaceRect`.
  - Single mapping path in [`RustInputDiagnostics.kt`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/input/RustInputDiagnostics.kt) (`RustTouchGeometry.map`) and [`RustInputBridge.kt`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/input/RustInputBridge.kt) (`sendCursorPos`); `RustActivity.kt` sets `fitInsetsTypes = 0` on API 30+, measures `composeView` vs `decor + engineRect` on layout/grab changes, and removes `lastTouchRaw` writes from `DesktopOverlayRoot`.
  - Diagnostics: added `overlay_offset`, `surface_size`, `mapped_px`, `raw_vs_mapped_check`.
  - Tests: [`StageLB3CursorMappingTest.kt`](../ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/StageLB3CursorMappingTest.kt) and Rust `surface_rect_tracks_presentation_and_buffer_sizes`.

### Bug 2 — Desktop IME / keyboard & `text_input_active` signal
- **Root cause**:
  1. `RustActivity.kt` (`addOverlayWindow`, L414) attached `DesktopOverlayRoot` with `FLAG_NOT_FOCUSABLE`, so `HidableInputLayout` / `TouchCharInput` inside the panel window never had window focus or an `InputConnection` (`imm.showSoftInput` failed, `chars=0`, `char_events=0`).
  2. Engine `crates/horizon-client/src/android/native_ime.rs` (`initialize`, L96–151) still added its own `EditText` via `addContentView` and focused `native` on startup, and `show_mode`/`hide`/`capture_mouse` were not guarded inside `native_ime.rs`.
  3. Engine `crates/horizon-client/src/game/ime.rs` (`sync_ime`, L40–60) checked `crate::platform::active()` on Android instead of `self.text_field_focused()` and never signaled the launcher when a text field opened/closed.
- **Fix**:
  - Engine patch [`0012-blaze-ime-desktop-handoff.patch`](../engine/patches/0012-blaze-ime-desktop-handoff.patch): disables `native_ime` completely when `blaze_desktop_input_enabled()` is true (`EditText` not added, `show_mode`/`hide`/`text`/`capture_mouse` short-circuit), skips `android_poll_keyboard` legacy OSK polling in desktop mode, drives `sync_ime` from `self.text_field_focused()`, and calls `platform::notify_text_input_active(bool)` -> `RustActivity.onTextInputActiveChanged(Boolean)` (incrementing `text_input_active_signals`).
  - Launcher [`RustImeInputView.kt`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/input/RustImeInputView.kt) + `RustActivity.attachDesktopImeHost()`: hosts a `1x1`, `alpha=0f` `BlazeImeEditText` in `RustActivity`'s focused window (so `InputConnection` works without triggering `WindowEvent::Focused(false)` / singleplayer pause), while `DesktopOverlayRoot` stays `FLAG_NOT_FOCUSABLE`. `RustImeTextProcessor` performs longest-common-codepoint-prefix diffing for composing text, autocorrect, backspace, paste, Unicode/emoji (`sendCodepoint` -> `injectChar` -> `char_events`), and Enter.
  - Diagnostics: added `text_input_active_signals` and `ime_shown_count`.
  - Tests: [`StageLB3ImeBridgeTest.kt`](../ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/StageLB3ImeBridgeTest.kt) and Rust `text_input_active_signal_increments_counter`.

### Bug 3 — World freezes behind Chat / Inventory / Creative / Multiplayer UI
- **Root cause**: `engine/starling` (`crates/horizon-client/src/game.rs`, L14834–14853 at pin `fa548eb7`) computed `in_world_stationary_overlay` whenever `self.screen != Screen::Playing` (and `self.screen == Screen::Options` on L14850) and set `menu_blur_cache = true` / `skip_world_for_blur = true`, halting 3D world rendering behind Chat, Inventory, Creative, and multiplayer Options.
- **Fix**:
  - Engine patch [`0013-blaze-blur-cache-pause-only.patch`](../engine/patches/0013-blaze-blur-cache-pause-only.patch): removes `in_world_stationary_overlay` and `|| self.screen == Screen::Options`; gates blur caching solely via `should_cache_menu_blur(singleplayer_paused, shows_panorama, has_menu_blur)`. Adds `world_frames_while_ui_open` and `ui_overlay_cache_active` counters.
  - Engine patch [`0014-blaze-gui-item-cache.patch`](../engine/patches/0014-blaze-gui-item-cache.patch): ports only the `gui_item_cache` optimization (`Game.gui_item_cache` + `resolve_gui_item_cached` in `game/item_draw.rs`) from reference commit `e414bb382`.
  - Guard Check 17 in [`scripts/guard.sh`](../scripts/guard.sh) (+2 self-tests in [`scripts/test_guards.sh`](../scripts/test_guards.sh)): fails if `in_world_stationary_overlay` appears in patched `game.rs` or `should_cache_menu_blur` is absent.
  - Tests: [`StageLB3WorldRenderDiagnosticsTest.kt`](../ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/StageLB3WorldRenderDiagnosticsTest.kt) and Rust `blur_cache_disabled_for_inventory_creative_chat_and_multiplayer`.

### Render Startup & Exit Summary + Pin Refresh
- [`RustDiagnosticsCollector.kt`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustDiagnosticsCollector.kt): `readSameUidLogcat()` used `-t 250`, so long play sessions evicted startup `BlazePerf` lines. Added `readRenderPerfLogcat()` (`logcat -d -v threadtime -s Horizon:I` filtered for `BlazePerf`/`Horizon renderer:`), tested in `StageCDiagnosticsTest`.
- [`0007-pin-refresh-26.3.patch`](../engine/patches/0007-pin-refresh-26.3.patch): added Mojang's refreshed 26.3 `manifest_sha1` (`969f7ab0ce024e8dcf95372971bcf291b34ed9f7`) and `asset_index_sha1` (`4e950fe41d8ad13e66bde63f476afdbe0cb19f94`).

## Commits & CI Verification
- Branch: `feat/stage-l-b3`
- Commits:
  - Bug 1: `00851c49`
  - Bug 2: `df479919`
  - Bug 3: `5c4c2ba7`
  - Render summary: `689a50a5`
  - Pin refresh (`0007`): `d93af0a0`
- CI (`d93af0a0`):
  - `CI`: https://github.com/kaito65452/BlazeLauncher/actions/runs/44274084049 (`success`)
  - `Parallel Fast Checks`: https://github.com/kaito65452/BlazeLauncher/actions/runs/44274083804 (`success`)
  - `signed-apks` Artifact ID: `14417260358` (`385,734,228` bytes), `versionCode=27769346`
  - `horizon-engine-arm64` Artifact ID: `14415357191` (`26,911,478` bytes; Check 15: `23 KEEP markers present, 0 forbidden UI/network markers`)
  - `ci-host-tools` Artifact ID: `14414599692` (`2,533,308` bytes)
  - Rust `android_runtime` unit tests: `14 passed; 0 failed`

## UNVERIFIED (Device-only on SM-N975F)
- Physical alignment of cursor and taps on Singleplayer/hotbar/buttons (landscape + reverse landscape + cutout/nav bar).
- Auto-show/hide of Android IME when opening/closing Chat, Sign, Anvil, plus manual IME button, Unicode, composing, backspace, paste, and `char_events > 0`.
- Live world animation (waves, clouds, players) behind Chat, Inventory, Creative, and Options in both singleplayer and multiplayer.
- Non-empty `Render Startup & Exit Summary` and `world_frames_while_ui_open` / `ui_overlay_cache_active` in Copy report after a play session.
