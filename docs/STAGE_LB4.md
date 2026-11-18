# Stage L-B4: IME + exit returns to ZL2

Status: implemented on `feat/stage-l-b4`; NOT compiled or run on device (CI only).

## Root causes (from code reading, not yet confirmed on device)

| Symptom | Cause found in code |
|---|---|
| (1)(3)(4) Gboard shows, keys dead, cannot close | `addOverlayWindow` used `FLAG_NOT_FOCUSABLE` without `FLAG_ALT_FOCUSABLE_IM`. Android Z-orders such a window above the IME, and `DesktopOverlayRoot` consumes every touch, so the keyboard is covered. Also the old `BlazeImeEditText` used `NO_SUGGESTIONS|FILTER` (kills swipe). |
| (3) re-appears | Old `setDesktopTextInputMode(ENABLE)` re-ran `enableIme()` on every repeat; the old insets listener forced `DISABLE` and was replaced with a non-compat listener. |
| (2) shows on title | NOT proven. Engine side: `Game::sync_ime` signals only on `text_field_focused()` change, initial `ime_allowed=false`. Kotlin side: no remaining show caller except the table below. If it still happens, `ime_transitions=[...]` in the Copy report shows the exact `engine_signal=true` timestamp. No engine patch was written without evidence. |
| (5) quit lands on Android home | `GameLaunchFlow.kt` called `exitActivity()` (finishing `MainActivity`) after launching `RustActivity`; `:rust` exits with `finish()` + `killProcess` and never relaunches the launcher (Java Edition relies on `ProcessPhoenix.triggerRebirth`). Fix: skip `exitActivity()` for Rust runtime so `MainActivity` stays beneath `RustActivity` (same task, `FLAG_ACTIVITY_NEW_TASK`, same affinity). |

## IME show callers -> reason

| Caller | Allowed | Reason |
|---|---|---|
| `onTextInputActiveChanged(true)` false->true | yes | engine text field focused |
| manual IME button / physical IME key (`toggleDesktopIme`) | yes | user toggle |
| tap in bottom 20% of overlay while engine wants text (`onBottomTap`) | yes | chat area tap |
| `onWindowFocusChanged`, `onResume` | no | recorded as `ignored_show_from=...` |
| activity create / overlay attach | no | no caller exists; `ImeSource` kept for the report |
| `windowSoftInputMode` | `adjustResize|stateHidden` | system must not auto-show |

## Design

- `RustImeController`: single state machine (engine false->true show once, true->false hide, user dismissal respected, visibility truth from `WindowInsetsCompat`, only a visible->hidden edge counts as dismissal).
- `BlazeImeEditText`: focusable 1x1 in the main window, `TYPE_CLASS_TEXT`, `IME_ACTION_SEND`, `InputConnectionWrapper` counts calls (never text), Enter handled once (editor action or key event).
- Overlay window gets `FLAG_ALT_FOCUSABLE_IM`; touch routing code is unchanged.
- Show: `WindowInsetsControllerCompat.show(ime())` + `SHOW_IMPLICIT`; hide: `hideSoftInputFromWindow` + `hide(ime())`. No `SHOW_FORCED`/`toggleSoftInput`.

## Diagnostics (Copy report, Input Bridge)

`ime_target_view view_has_focus window_has_focus window_flags imm_is_active imm_is_accepting_text on_create_input_connection editor_input_type editor_ime_options ime_show_requests ime_hide_requests ime_show_ignored engine_text_input_active ime_visible_observed ic_commitText ic_setComposingText ic_finishComposing ic_deleteSurrounding ic_sendKeyEvent ime_transitions=[ts:event,...30]`.
`onStartInput/onStartInputView` are framework-side and not observable from the app; `on_create_input_connection` is the proxy.

## Not done / unverified

- Everything on device and in CI. Whether the overlay flag is the sole cause of dead keys.
- Source tagging for `window_focus`/`resume` records ignored requests only (no such callers exist to remove).
- `engine/starling` untouched (pin `fa548eb7`).
