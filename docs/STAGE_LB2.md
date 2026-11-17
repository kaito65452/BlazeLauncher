# Stage L-B2 — ZL2 touch input / overlay fix (Rust Edition)

## Evidence (SM-N975F, versionCode 27600542, c0ca8382)
- `kotlin_bridge`: viewport=1463x694, pos=1, keys=0, mouseBtns=0 (Kotlin handlers never saw a touch).
- `native_queue`: raw_touch_ignored=125 (touches arrived at winit's native input queue and were dropped), drained_events=7.
- Overlay never visible; engine surface 1520x720 vs Kotlin viewport 1463x694.

## Root cause
`RustActivity` is a `NativeActivity`. `NativeActivity.onCreate` calls `Window.takeSurface()` and
`Window.takeInputQueue()`: the activity window's surface and input queue belong to native code.
Consequences for the old `attachDesktopInputOverlay` (`addContentView` into that window):
1. Views in that window are never drawn (surface owned by Vulkan) -> overlay invisible.
2. Input never enters the Java hierarchy (`dispatchTouchEvent` is bypassed) -> goes to winit `WindowEvent::Touch`
   -> patch 0009 drops it and counts `raw_touch_ignored`.
JNI and the engine queue were fine (the 1 cursor_pos was the initial viewport sync).
Broken hop: `RustActivity.attachDesktopInputOverlay` (addContentView) — not JNI, not the engine queue.

## Fix
- Overlay now lives in its own sub-window: `WindowManager.addView(root, TYPE_APPLICATION_PANEL, FLAG_NOT_FOCUSABLE)`
  with its own surface and input channel, above the engine surface. `DesktopOverlayRoot` is the single touch gate
  (counts, forwards into Compose, always consumes). `RustActivity.dispatchTouchEvent` forwards to it and consumes in
  desktop mode (never `super`). Built in a try/catch; failures land in `overlay_error`.
- Mapping: `RustTouchGeometry.normalize` uses overlay content offset inside the decor + decor size (== engine surface
  aspect), so 1463x694 inside 1520x720 maps correctly. Engine multiplies the normalized value by its surface size.
- Engine patch `0010-blaze-raw-touch-warn.patch`: `raw_touch_warnings` counter + rate-limited warning (1st, every 500th).
- Diagnostics: Copy report Input Bridge gets a `touch_overlay:` line (all requested fields). Developer toggle
  "Input debug overlay" draws the counters on screen. Fallback toggle unchanged (old engine touch path kept).
- Guard Check 16 (+3 self-tests): desktop overlay must not use `addContentView`, must be a panel window, engine
  patch must drop `WindowEvent::Touch`.

## UNVERIFIED (CI/device only)
- Everything: nothing compiled or run locally. Kotlin/Rust compile, unit tests, APK.
- On device: cursor drawn in menu, tap on Singleplayer, entering a world, ZL2 controls, IME/physical keys
  (panel is not focusable, keys still go to the native queue as before), cutout/inset alignment.
