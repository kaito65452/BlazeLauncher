# Stage L-B5: Manual-First IME Policy, Show/Hide Race Reconciliation & Signal Debounce

Status: implemented on `feat/stage-l-b5`; awaiting CI verification (SM-N975F on-device verification pending).

## Root Causes Diagnosed from L-B4 Copy Report (`donutsmp.net`, 2026-11-19 13:41)

1. **Show/hide race (`...499010 show` -> `...499107 engine_signal=false` -> `...499117 create_input_connection` -> `...499287 ime_visible=true`)**:
   - `BlazeImeEditText.enableIme()` (`RustImeInputView.kt:314-342` in L-B4) posted asynchronous `post`/`postDelayed(60ms)` show work outside `RustImeController`, while `disableIme()` (`RustImeInputView.kt:345-355`) called `clearFocus()` + `isFocusable=false` and `RustActivity.updateOverlayImeWindowFlags(false)` (`RustActivity.kt:516-535`) removed `FLAG_ALT_FOCUSABLE_IM` immediately while Gboard's show animation was still in flight.
   - When `WindowInsetsCompat` observed `ime_visible=true` 180 ms later (`...499287`), `RustImeController.onImeVisibility()` (`RustImeController.kt:166-171`) did nothing because `wanted` was already `false`. Meanwhile `overlay_window_flags` dropped `FLAG_ALT_FOCUSABLE_IM`, placing the `TYPE_APPLICATION_PANEL` overlay above Gboard and trapping taps (including Gboard's hide button).
2. **Excessive input restarts (`on_create_input_connection=27`, `on_start_input=27`)**:
   - `enableIme()` forced `imm.restartInput(this)` unconditionally, and `resetBuffer()` called `TextView.setText(TEXT_FILLER)` on `enableIme`, `sendEnter`, `afterTextChanged`, and `disableIme`, triggering Android IMM `restartInput` mid-session.
   - `BlazeImeEditText` lacked `@Keep`, causing R8 to report `ime_target_view=wh0`.
3. **Rapid `true -> false` engine signal flicker (97 ms) without source attribution**:
   - `Game::sync_ime()` (`crates/horizon-client/src/game/ime.rs`) called `notify_text_input_active` immediately on frame-level transitions (e.g., server dialog/chat/UI transition on `donutsmp.net`) with no debounce window and no source tag.

## Fixes Implemented

- **A. Serialized `RustImeController` + Bounded Reconciliation (`RustImeController.kt`)**:
  - Single `Scheduler` (`Handler(Looper.getMainLooper())`) serializes all show/hide/reconcile commands; transitioning to `HIDDEN` cancels any pending show runnable immediately (`pending_show_cancelled_count`).
  - Bounded desired-vs-observed reconciliation (`MAX_RECONCILE_ATTEMPTS = 3`, `RECONCILE_DELAY_MS = 140 ms`, recorded as `"reconcile"` in the 30-entry ring buffer) closes Gboard whenever `ime_visible_observed` diverges from `ime_desired_state` or `!engineActive`.
- **B. Manual-First Policy + Auto-Show Setting (Default OFF) + Pulsing IME Cue**:
  - Added `AllSettings.rustAutoShowIme` (default `false`) surfaced in Developer settings (`DeveloperSection.kt`).
  - When `auto_show_setting=false`, `engine_signal=true` never pops up the keyboard; instead `RustGameOverlay.ImePulseCueOverlay` draws a pulsing cyan highlight ring over visible `launcher.event.switch_ime` buttons (or a clickable pulsing `IME` pill when hidden).
  - Auto-hides on `engine_signal=false`, Back, or manual toggle; user dismissal via Gboard is respected.
- **C. Reduced Input Restarts & Overlay Flags (`RustImeInputView.kt`, `RustActivity.kt`)**:
  - Added `@Keep` on `BlazeImeEditText`, removed `setText()` calls while focused (in-place `Editable` updates), removed unconditional `imm.restartInput()`, and logged `input_restart_reasons`.
  - Overlay keeps `FLAG_ALT_FOCUSABLE_IM` only while `desired == SHOWN || imeVisibleObserved`, restoring normal overlay flags once insets confirm `imeVisibleObserved == false`.
  - `disableIme()` calls `hideSoftInputFromWindow` + `WindowInsetsControllerCompat.hide(ime())` using `this.windowToken ?: decorView.windowToken` **before** calling `clearFocus()`.
- **D. Engine Patch `engine/patches/0016-blaze-ime-l-b5.patch`**:
  - `BlazeTextInputDebouncer` (`150 ms` debounce/coalescing + exact-repeat suppression) absorbs 97 ms `false -> true -> false` and `true -> false -> true` flickers before emitting JNI callbacks.
  - Emits named signal source (`chat`, `dialog`, `sign`, `book`, `anvil`, `command_block`, `block_editor`, `world_options`, `search_box`, `server_address`, `title_field`, `none`) via `onTextInputActiveWithSource(ZLjava/lang/String;)V`.
- **E. Inactive Drop Counters & Guard Check 18**:
  - `InactiveDropCountingSink` + `RustActivity.dispatchKeyEvent` count `chars_dropped_inactive` / `keys_dropped_inactive` when `!engineActive` and trigger `onTextWhileEngineInactive()` reconcile-hide.
  - Guard Check 18 (`scripts/guard.sh`) rejects any IME show API or `enableIme()` outside `RustImeController.Host.showIme()`.
