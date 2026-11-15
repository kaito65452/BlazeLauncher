# Stage C Report: First Device-Report Fixes & Render Telemetry (`C1`–`C4`)

- **Branch**: `feat/overnight-stages`
- **Commit (Stage C code & tests)**: `b395d322`
- **Parallel Fast Checks CI Run**: [`43668835071`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43668835071) (`success` — `Guards & Self-Tests` 1m54s, `JVM Unit Tests` 6m49s)
- **Full Release CI Run**: [`43668835050`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43668835050) (`success` — `engine / engine` 25m52s [ID `132674131563`], `ci` 14m35s [ID `132680111165`])
- **Artifacts**:
  - `horizon-engine-arm64`: ID `14143798395` (`28,102,434` bytes)
  - `signed-apks`: ID `14144856033` (`378,278,446` bytes; release APK `versionCode=27486478 > 27321741`, `android:debuggable=false`, `157` `.so` files scanned cleanly, V2 signer SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`)

---

## C1. Document Picker JNI Callback & IME Resize (`adjustResize`)

1. **`RustActivity.nativeDocumentResult(int, int, String?)` & `onActivityResult`**:
   - Upstream Starling registers `"nativeDocumentResult"` with JNI signature `"(IILjava/lang/String;)V"` onto the active `Activity` class via `document_bridge::register` (`engine/starling/crates/horizon-client/src/android/document_bridge.rs:10-24`) and expects `requestCode == 4101` (`documents.rs:240-250`, dispatched by `native_ime.rs:381-386`).
   - Added `private external fun nativeDocumentResult(requestCode: Int, resultCode: Int, uriString: String?)` and `override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)` in `ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustActivity.kt`.
   - `RustActivity.sanitizeDocumentPickerUri(resultCode, data?.dataString)` enforces `Activity.RESULT_OK` (`-1`), `content://` scheme prefix, and length `<= 8192` (`documents.rs:274`). Best-effort `takePersistableUriPermission` runs inside `runCatching`, and `nativeDocumentResult` catches `UnsatisfiedLinkError` in case Android delivers a restored picker result before `document_bridge::register` runs. Redacted telemetry (`document_result req=4101 res=... uriPresent=... jniDelivered=...`) is recorded in `RustDiagnosticsCollector`.
2. **`android:windowSoftInputMode="adjustResize"`**:
   - Added `android:windowSoftInputMode="adjustResize"` to `io.github.kaito65452.blazelauncher.rustruntime.RustActivity` in `ZalithLauncher/src/main/AndroidManifest.xml:126-137`.

---

## C2. Display Cutout Safe Insets & API 29+ System Gesture Exclusion

- **`configureDisplayCutoutMode()` in `RustActivity.kt`**: On API 28+ (`Build.VERSION_CODES.P`), sets `window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`.
- **`applySafeDisplayInsets(container)` in `RustActivity.kt`**: Attaches an `OnApplyWindowInsetsListener` to the `addContentView` overlay `FrameLayout` using `WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()` on API 30+, `displayCutout` + `systemWindowInset*` on API 28–29, and `systemWindowInset*` on older APIs, returning `insets` unconsumed so `NativeActivity` surface handling is unaffected.
- **`applyGestureExclusionOnLayout(view, density)` & `computeSideGestureExclusionStrips` in `RustActivity.kt`**: On API 29+ (`Build.VERSION_CODES.Q`), registers left and right edge `Rect` strips (`24dp` width) clamped vertically to `MAX_SYSTEM_GESTURE_STRIP_DP = 200` (`<= 200dp` per Android framework gesture exclusion limit). Verified by JVM unit tests in `StageCDiagnosticsTest.kt`.

---

## C3. Audit of JNI `new_global_ref` Lifetimes in Starling & Blaze Patches

Inspected all `new_global_ref` sites in `engine/starling/crates/` and verified `engine/patches/0001`–`0005`:

| Component | Source Location | `new_global_ref` Object(s) | Ownership / Drop Behavior | Leak Risk |
|---|---|---|---|---|
| `native_ime.rs` | `engine/starling/crates/horizon-client/src/android/native_ime.rs:153-156, 203-205` | `edit`, `input`, `native`, `activity` (`GlobalRef` fields in `Ui`) | Stored once inside `static UI: OnceLock<Arc<Ui>>`; held for process lifetime by design, not allocated per key/edit. | None (singleton `OnceLock`) |
| `documents.rs` | `engine/starling/crates/horizon-client/src/android/documents.rs:266, 279-295` | `activity` (`GlobalRef`) | Moved into `"Content import"` worker thread closure; RAII-dropped (`JNIEnv::DeleteGlobalRef`) when worker thread completes. | None (RAII drop on thread exit) |
| `exit_reports.rs` | `engine/starling/crates/horizon-client/src/android/exit_reports.rs:188, 199-208` | `activity` (`GlobalRef`) | Moved into `"Android exit history"` worker thread closure; RAII-dropped on worker completion. | None (RAII drop on thread exit) |
| `gallery.rs` | `engine/starling/crates/horizon-client/src/android/gallery.rs:91-92` | `activity`, `resolver` (`GlobalRef` in `Request`) | Moved into gallery worker thread closure; RAII-dropped when `Request` drops. | None (RAII drop on thread exit) |
| `platform.rs` | `engine/starling/crates/horizon-client/src/android/platform.rs:47, 163, 194` | `VIBRATOR`, `PAD_VIBRATOR` | Thread-local / lazy cached haptic service handles, bounded count. | None (bounded static/TLS handles) |
| Blaze patches `0001`–`0005` | `engine/patches/0001`..`0005` | `0` occurrences (`git grep` exit `1`) | Blaze introduces zero additional `GlobalRef` allocations. | None |

Conclusion: No JNI `GlobalRef` leak exists in upstream Starling or Blaze patches; no upstream `engine/starling` C3 code patch was needed.

---

## C4. Render Startup Timing Telemetry & `:rust` Exit Reason Summary

1. **Rust instrumentation (`engine/patches/0005-stage-c-diagnostics-telemetry.patch`)**:
   - Instruments `engine/starling/crates/horizon-client-render/src/gfx.rs` with `SURFACE_START_AT` (`Mutex<Option<Instant>>`) and `pipeline_started = Instant::now()`.
   - Logs `BlazePerf pipelines_created_ms=<ms> initial=<bool> multi_draw=<on/off> occlusion_culling=<on/off> gpu_timer=<on/off>` after pipeline creation completes.
   - Logs `BlazePerf surface_to_first_frame_ms=<ms> backend=<Backend> adapter=<AdapterName>` upon presenting the first frame (`self.queue.present(surface_frame)`), routed through tag `"Horizon"` (`android_main.rs:164`) to both logcat and `files/performance/diagnostic.log`.
2. **Launcher telemetry summary (`RustDiagnosticsCollector.kt`)**:
   - Added `extractRenderStartupSummary(logcatText, diagText, historicalExitsText)` and `formatExitReasonLabel(reasonCode)` to `ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustDiagnosticsCollector.kt`.
   - `"Copy report"` now emits a `=== Render Startup & Exit Summary ===` section before raw tails, with redacted output verified against synthetic JWT tokens and full UUIDs in `StageCDiagnosticsTest.kt`.

---

## Verification & UNVERIFIED Items

- **Local checks (pre-commit)**: `bash scripts/scope_guard.sh main` (exit `0`), `bash scripts/test_guards.sh` (`PASS=39 FAIL=0`), `ENGINE_BUILD_ENABLED=1 bash scripts/guard.sh` (exit `0`).
- **CI checks**: `Parallel Fast Checks` (`43668835071`) green; `CI` (`43668835050`) green, `libhorizon_android.so` release `52,019,160` bytes, `libblaze_prepare.so` release `2,846,936` bytes.
- **`UNVERIFIED` (physical device `SM-N975F` required)**:
  - Interactive Android SAF document picker round-trip (`requestCode=4101`) through `RustActivity.onActivityResult` -> `nativeDocumentResult` on device.
  - On-screen soft keyboard `adjustResize` viewport reflow, punch-hole cutout padding (`SHORT_EDGES`), and edge back-gesture exclusion strips on `SM-N975F`.
  - Real hardware `BlazePerf pipelines_created_ms` / `surface_to_first_frame_ms` values in `"Copy report"` from Mali GPU rendering on `SM-N975F`.
