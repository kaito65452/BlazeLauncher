# Stage L-B4 (Revisi 2): IME Fixes, Diagnostics & Return to ZL2 Menu on Quit

Status: implemented on `feat/stage-l-b4`; verified in CI (on-device SM-N975F behavior UNVERIFIED until installed).

## Root Causes Found in Code

| Symptom | Root Cause Found in Code | Fix |
|---|---|---|
| (1)(3)(4) Gboard appears, keys produce `chars=0`, hide button dead, swipe dead | `RustActivity.addOverlayWindow()` created the full-screen `TYPE_APPLICATION_PANEL` overlay with `FLAG_NOT_FOCUSABLE` **without** `FLAG_ALT_FOCUSABLE_IM`. Per Android `WindowManager.LayoutParams`, a `FLAG_NOT_FOCUSABLE` window without `FLAG_ALT_FOCUSABLE_IM` is Z-ordered **above** the IME, so `DesktopOverlayRoot` swallowed every touch and swipe aimed at Gboard. Additionally `BlazeImeEditText` used `TYPE_TEXT_FLAG_NO_SUGGESTIONS \| TYPE_TEXT_VARIATION_FILTER` (disabling swipe) and did not clear focus from `NativeContentView` before `requestFocus()`. | Dynamically apply `FLAG_ALT_FOCUSABLE_IM` alongside `FLAG_NOT_FOCUSABLE` on the overlay sub-window while IME is active (`updateOverlayImeWindowFlags`), restoring normal flags on hide while keeping `touch_leaked_to_engine = 0`. Use `InputType.TYPE_CLASS_TEXT` + `IME_ACTION_SEND` on `BlazeImeEditText` in `RustActivity`'s main window, clear `NativeContentView` focus before `requestFocus()`, and handle `KEYCODE_ENTER`/`KEYCODE_DEL` inside `InputConnectionWrapper` without double-dispatching via `Activity.dispatchKeyEvent`. |
| (2) Keyboard pops up without user tapping a text field | Engine `Title::enter_singleplayer()` (`title.rs:719`) unconditionally initialized `self.search_focused = true` on entering Singleplayer (initial focused state before tapping the search box), `return_to_list` / Cancel did not reset `search_focused`, `Game::text_field_focused()` (`game/ime.rs:15`) used `dialog_has_text_field()` instead of `dialog_typing()`, and `platform::notify_text_input_active` did not deduplicate repeated signals (e.g. after `Game::resumed` reset `ime_allowed = false`). Also `RustActivity` lacked `stateHidden` in `AndroidManifest.xml`. | New engine patch `engine/patches/0015-blaze-ime-focus-state.patch` (search box starts unfocused in desktop input mode, dialogs require `dialog_typing()`, `BLAZE_TEXT_INPUT_ACTIVE` starts `false` and deduplicates transitions) + `adjustResize\|stateHidden` in `AndroidManifest.xml` + `RustImeController` single state machine. |
| (3) Keyboard cannot be closed / re-appears | Old `setDesktopTextInputMode(ENABLE)` re-invoked `enableIme()` on repeated calls; closing via Gboard did not release focus from `BlazeImeEditText`; overlay window covered Gboard's hide button. | `RustImeController` respects user dismissal (`onUserDismissed` triggered by `WindowInsetsCompat` visible→hidden edge or Back) by clearing IME focus and ignoring `window_focus`/`resume`/repeated `true` until the next `false->true` engine transition, manual IME button, or chat area tap. |
| (5) Quit from title screen exits to Android home and kills launcher | `GameLaunchFlow.kt` unconditionally called `exitActivity()` (finishing `MainActivity`) after starting `RustActivity`, and `RustActivity` (`:rust`) exited via `finish()` + `Process.killProcess()` without keeping or relaunching `MainActivity`. | Skip `exitActivity()` in `GameLaunchFlow.kt` for `GameRuntime.RUST` so `MainActivity` stays alive beneath `RustActivity`, AND add `returnToLauncherAndFinish()` in `RustActivity` (`Intent(this, MainActivity::class.java)` with `FLAG_ACTIVITY_CLEAR_TOP \| FLAG_ACTIVITY_SINGLE_TOP` before `finish()`) so quitting returns to the ZL2 menu even if Android LMK reclaimed `MainActivity` in background. |

## IME Show Callers -> Reason

| Caller | Allowed to Show IME? | Reason |
|---|---|---|
| `RustActivity.onTextInputActiveChanged(true)` (`false -> true` transition) | Yes (`engine_signal`) | Engine text field genuinely gained focus (`chat`, `sign`, `anvil`, `book`, search box, server address). |
| Manual IME overlay button / physical IME key (`RustActivity.toggleDesktopIme`) | Yes (`manual_button`) | Explicit user toggle. |
| Tap on chat area (`DesktopOverlayRoot.onBottomTap` -> `imeController.onChatTap`) | Yes (`chat_tap`, only when `engineActive && !wanted`) | Re-opens keyboard after user dismissed it while chat remains open. |
| `RustActivity.onWindowFocusChanged` | No (`window_focus`) | Logged in ring buffer as `ignored_show_from=window_focus`; never shows IME. |
| `RustActivity.onResume` | No (`resume`) | Logged in ring buffer as `ignored_show_from=resume`; never shows IME. |
| `RustActivity.onCreate` / `attachDesktopImeHost` / `addOverlayWindow` | No (`activity_create` / `overlay_attach`) | Never calls `showIme()`; manifest sets `windowSoftInputMode="adjustResize\|stateHidden"`. |
| Repeated `onTextInputActiveChanged(true)` | No | Ignored both in Rust (`update_text_input_active_state`) and Kotlin (`RustImeController`). |

## Diagnostics Added (Copy Report "Input Bridge" & Developer Overlay)

- Target & window state: `ime_target_view`, `view_has_focus`, `window_has_focus`, `window_flags` (hex), `overlay_window_flags` (hex), `imm_is_active`, `imm_is_accepting_text`.
- InputConnection & EditorInfo: `on_create_input_connection`, `on_start_input`, `editor_input_type` (hex), `editor_ime_options` (hex), `ic_commitText(chars=...)`, `ic_setComposingText(chars=...)`, `ic_finishComposing`, `ic_deleteSurrounding`, `ic_sendKeyEvent`.
- State & history: `ime_show_requests`, `ime_hide_requests`, `ime_show_ignored`, `engine_text_input_active`, `ime_visible_observed` (`WindowInsetsCompat`), `ime_transitions=[ts:event,...]` (last 30 transitions). Never logs text contents—only counts and lengths.

## Pin Refresh

- Updated `engine/patches/0007-pin-refresh-26.3.patch` with Mojang's republished 26.3 manifest SHA-1 `ead839cbccf0ddf013c3f935f2840080b5cb0d26` and asset index SHA-1 `f3df28b441565ca4e5deb9cda856d90df62e1433` (translations-only manifest republish; jars/libraries unchanged).
