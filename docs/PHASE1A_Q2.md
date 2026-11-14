# Phase 1a Q2 Technical Findings: Neutralizing Starling Login, Guard Hardening, Session Handoff Hygiene & `engine-data.zip` Diagnosis

> **Legal disclaimer**: All licensing, ownership, and redistribution remarks in this document are **technical screening, not legal advice**. Consult qualified counsel before public distribution.

---

## 1. Engine Login & OAuth Audit (`file:line` & Reachability)

Before Task Q2 neutralization, the upstream `engine/starling` submodule (`fa548eb7abe91c194d3780e4580b9746323cd606`) plus Blaze's initial JNI wrapper (`engine/patches/0002-blaze-native-bridge.patch`) exposed multiple paths capable of starting Microsoft / Xbox Live / XSTS / Minecraft / Ely.by authentication, token refresh, account switching, or offline-profile editing:

| # | Path & Mechanism | Upstream / Baseline `file:line` | User / Runtime Reachability |
|---|---|---|---|
| 1 | **Embedded Nintendo Switch MS Client ID & Config Fallback** | `engine/starling/crates/horizon-client/src/android/catalog.rs:22` (`MICROSOFT_CLIENT_ID = "00000000441cc96b"`), `:167-168` (`Config::load` sets `config.microsoft_client_id = crate::account::client_id().unwrap_or_else(|| MICROSOFT_CLIENT_ID.into())`), `:1951` (`Some("client_id")` mutable field) | **Startup & Settings/Login**: Automatically loaded into `Config::load` on startup for every login/refresh/device-code call and editable via text input. |
| 2 | **Legacy JNI Fallback Client ID & Login Functions** | `engine/patches/0002-blaze-native-bridge.patch` (pre-Q2): `nativeStartMicrosoftDeviceLogin` (fallback `"00000000402b5328"`), `nativePollMicrosoftDeviceLogin`, `nativeAddElyByAccount`, `nativeSelectAccount`, `nativeAddOfflineAccount`, `nativeDeleteAccount`, `nativeGetActiveAccessToken`, `nativeGetAccountsJson` | **JNI**: Exported on `dev.horizon.android.bridge.BlazeNativeBridge` (`BlazeNativeBridge.kt:107-115`). Even when unused by Kotlin, calling these JNI entry points could invoke `Endpoints::live()`, `account::device_code`, `account::poll`, `account::finish_sign_in`, or `account::ely::authenticate`. |
| 3 | **Startup Background Session Pre-Warm / Token Refresh** | `engine/starling/crates/horizon-client/src/android/launcher.rs:178-210` (`Launcher::new`) | **Automatic on startup**: If `Accounts::load().selected().is_some()`, spawned a background thread running `login::Login::session(&id)` -> Xbox/MS/MC token refresh + `skins::cloud::fetch` + `account::write_session_file`. |
| 4 | **Play Click Session Renewal Worker** | `engine/starling/crates/horizon-client/src/android/launcher.rs:466-500` & `:535-566` (`Launcher::action`) | **Menu ("Play")**: Triggered whenever `!self.session_ready && Accounts::load().selected().is_some()` on pressing Play / Retry; called `login::Login::session(&id)` and wrote a session file. |
| 5 | **Top-Right Account Pill & Settings "Account" Card** | `engine/starling/crates/horizon-client/src/android/launcher_pages.rs:1156-1188` (`Action::Account`), `:3804-3808` (`"Account"` / `"Microsoft and Xbox sign-in"` -> `Action::Account`) | **Main UI Menu & Settings**: Always visible in header pill and Settings overview; opened Starling's login/provider/account switcher modal (`launcher_pages.rs:6053-6071`). |
| 6 | **Provider Chooser, Saved Account Switcher & Offline UI** | `engine/starling/crates/horizon-client/src/android/login.rs:455-485` (`Microsoft`, `Ely.by`, `Offline`, `Saved accounts`), `launcher_pages.rs:6072-6127` (`Action::AddAccount`, `Action::SelectAccount`, `Action::SelectOffline`) | **Account Modal**: Lets user switch/add Microsoft, Ely.by, or Offline accounts and persists selection via `Accounts::load().save()`. |
| 7 | **Microsoft Browser OAuth & Device Code Flows** | `engine/starling/crates/horizon-client/src/android/login.rs:125-151,237-254,865-900`; `launcher_pages.rs:6150-6176` (`Action::DeviceSignIn`, `Action::SignIn`, `Action::LoginLink`, `Action::ReviewApp`, `Action::SignOut`); `android/platform.rs:537-605` (`open_login_url` Android Custom Tabs Intent) | **Microsoft Login Modal**: Opens `login.microsoftonline.com/consumers/oauth2/v2.0/authorize` or `microsoft.com/link?otc=...`, starts `SignIn::start_with_client_id`, polls `/consumers/oauth2/v2.0/token` or `login.live.com/oauth20_token.srf`. |
| 8 | **Ely.by Alternative Auth Modal** | `engine/starling/crates/horizon-client/src/android/login.rs:174-185,373-377,466`; `launcher_pages.rs:6128-6143` (`Action::ElySignIn`, `Action::SubmitEly`) | **Account Modal -> Ely.by**: Submits password/OTP to `https://authserver.ely.by` (`launcher/src/account/ely.rs:6`). |
| 9 | **Wardrobe Cloud Skin / Cape Sync** | `engine/starling/crates/horizon-client/src/android/launcher.rs:73,213-214`; `launcher_pages.rs:6178-6182,6206-6216`; `android/wardrobe.rs:143,315-385,1076,1189,1205` | **Startup, Skins Tab, and Wardrobe Buttons**: `wardrobe.open` / `WardrobeCloud` / `WardrobeUpload` / `WardrobeActivateCape` called `Login::session(&id)` -> `account::session::selected_session` (renewing MS/MC tokens) or `open_login_url("https://ely.by/skins")` (`wardrobe.rs:327`). |
| 10 | **Shared Desktop Account Endpoints Compiled into Android** | `crates/horizon-client/src/android_main.rs:16-17` (`#[path = "../../../launcher/src/account.rs"]`), `launcher/src/account.rs:79-83,189-209,259-269,465,529,554`, `account/session.rs:258-383`, `account/ely.rs:6-141` | **Library / Transitive Calls**: Exposes `login.microsoftonline.com`, `login.live.com/oauth20_*.srf`, `user.auth.xboxlive.com`, `xsts.auth.xboxlive.com`, `/launcher/login`, `/authentication/login_with_xbox`, `/entitlements/mcstore`, `/minecraft/profile`, `authserver.ely.by`, and `selected_session` to the Android build. |

---

## 2. Neutralization Architecture (`engine/patches/*.patch` Only, Submodule Untouched)

`engine/starling` remains completely unmodified at commit `fa548eb7abe91c194d3780e4580b9746323cd606`. All changes live in `engine/patches/*.patch` applied at build time:

- **`engine/patches/0002-blaze-native-bridge.patch`** (Keeps all **25** original `Java_dev_horizon_android_bridge_BlazeNativeBridge_*` JNI symbol names intact so `BlazeNativeBridge.kt` remains ABI-compatible):
  - Removes `use super::account::{...}`, `CURRENT_DEVICE_LOGIN`, `"00000000402b5328"`, `Endpoints::live()`, `device_code`, `poll`, `finish_sign_in`, `ely::authenticate`, `Accounts::load`, and `config.save`.
  - `nativeSignalEngineExit` calls `horizon_client::auth::clear_current()` and `horizon_client::auth::clear_pending_android()`.
  - `nativeGetAccountsJson` reads strictly from in-memory `horizon_client::auth::current()` (`accounts: []`, `offline: []`).
  - `nativeGetActiveAccessToken` returns `""`; `nativeSelectAccount`, `nativeAddOfflineAccount`, `nativeDeleteAccount` return `JNI_FALSE` (`0`); `nativeAddElyByAccount`, `nativeStartMicrosoftDeviceLogin`, `nativePollMicrosoftDeviceLogin` return JSON error `"disabled"`.
- **`engine/patches/0003-phase1a-bridge-diagnostics.patch`**:
  - Keeps `nativeSetSession` and `nativeInjectSyntheticTouch` (**27 total** exported `BlazeNativeBridge` JNI functions).
  - Adds `horizon_client::auth::clear_pending_android()` in `crates/horizon-client-render/src/auth.rs`.
  - Strips `Config::load` / `config.save(&home)` out of `blaze_diagnostics.rs` so `nativeSetSession` writes **nothing** to disk (`android-launcher.json` or any session file) and operates strictly in process memory (`horizon_client::auth`).
- **`engine/patches/0004-neutralize-engine-login.patch`**:
  - `crates/horizon-client/src/android/catalog.rs`: Sets `MICROSOFT_CLIENT_ID = ""`, clears `config.microsoft_client_id` on load, and removes the editable `"client_id"` field.
  - `crates/horizon-client/src/android/platform.rs`: Replaces `open_login_url` with an immediate `Err("In-engine login is disabled; sign in through Blaze Launcher.".into())` (zero `Intent` / Custom Tabs calls).
  - `crates/horizon-client/src/android/login.rs`: Replaces modal with a read-only `"Blaze Launcher Session"` status panel showing only the in-memory `horizon_client::auth::current()` session name and a `"Close"` button; `Login::session` always returns `Err(...)` (no refresh).
  - `crates/horizon-client/src/android/wardrobe.rs`: Keys offline/cloud appearance strictly from in-memory `auth::current()`, sets `provider = None`, stops `open`/`prepare_game` from loading `Accounts::load()` or spawning cloud OAuth sync, disables `"Refresh Cloud Profile"` and `"Upload Selected Skin..."`, and makes `cloud()` a zero-network disabled notice.
  - `crates/horizon-client/src/android/launcher.rs` & `launcher_pages.rs`: Removes startup `prewarm_session_worker`, Play-click `Login::session` workers, `Action::Account` chevron/click target on the top-right pill, and Settings `"Account"` card; hard-disables `Account` / `AddAccount` / `MicrosoftAccount` / `OfflineAccount` / `SavedAccounts` / `SelectAccount` / `SelectOffline` / `ElySignIn` / `SubmitEly` / `DeviceSignIn` / `SignIn` / `LoginLink` / `ReviewApp` / `SignOut` / `WardrobeCloud` / `WardrobeUpload` / `WardrobeActivateCape`; sets `LaunchRequest.session_file = None`; clears `auth::clear_current()` and `auth::clear_pending_android()` in `restore_launcher` and `exiting`.
  - `crates/horizon-client/src/android_main.rs`: Calls `auth::clear_current(); auth::clear_pending_android();` on process shutdown before `std::process::exit(0)`.
  - `launcher/src/account.rs`, `launcher/src/account/session.rs`, `launcher/src/account/ely.rs`: Gates `Endpoints`, `microsoft_endpoint`, `device_code`, `poll`, `wait_for_sign_in`, `refresh_ms`, `minecraft_login`, `minecraft_token`, `owns_game`, `profile`, `finish_sign_in*`, `Check`/`check*`, `fresh`, `launch*`, `write_session_file`, `SignInState`, `SignIn`, `session_for_game*`, `selected_session_with`, `refresh*`, `renew_for_game*`, and the entire `ely` auth implementation behind `#[cfg(not(target_os = "android"))]`; adds Android stubs `account::client_id() -> None` and `account::session::selected_session(...) -> Err(...)`. (Note: `crates/horizon-client-render/src/auth.rs:290` retains `"https://authserver.ely.by/session/join"` only for `session.ely == true`; `setSession` always sets `ely: false`, so that branch is unreachable.)

---

## 3. CI Guard Hardening (`scripts/guard.sh` Check 10 & 10b + `scripts/test_guards.sh`)

- **Check 10 (`check_engine_login_neutralized` in `scripts/guard.sh`)**:
  - Scans either the reconstructed `TEMP_PATCHED_WORKTREE` (when `engine/starling` is clean + `engine/patches/*.patch` applied) or direct `engine/` (during CI build state after `rust-engine.yml` applies patches).
  - Does **not** rely on naive comment stripping: implements a Rust lexer tracking normal/byte strings (`"..."`), raw strings (`r#*"..."#*`), char literals, nested `/* ... */` blocks, and `//` comments so strings like `"https://login.microsoftonline.com/..."` are never truncated at `//`.
  - Prunes balanced `#[cfg(test)]` and `#[cfg(not(target_os = "android"))]` items/external `mod tests;` targets (keeping `#[cfg(target_os = "android")]` and unconditional code intact) and folds `concat!("...", ...)` string expressions before checking.
  - Fails on: embedded IDs `00000000441cc96b` or `00000000402b5328`; OAuth/Xbox/XSTS/MC/Ely login endpoints (`login.microsoftonline.com`, `login.live.com`, `oauth20_*.srf`, `user.auth.xboxlive.com`, `xsts.auth.xboxlive.com`, `/authentication/login_with_xbox`, `/entitlements/mcstore`, `authserver.ely.by` other than `/session/join`); live login calls (`account::device_code`, `poll`, `finish_sign_in*`, `refresh_ms`, `selected_session*`, `ely::authenticate/refresh`, `SignIn::start`, `Endpoints::live`, `Accounts::load` in Android modules); disk persistence inside `blaze_diagnostics.rs`; or un-neutralized `catalog.rs` / `platform.rs` / `login.rs`.
- **Check 10b (`--check-apk` & `--check-release-apk` in `scripts/guard.sh`)**: Scans every `lib/*/*.so` binary in the APK for those client IDs and login/OAuth strings (masking only `authserver.ely.by/session/join`).
- **Self-tests (`scripts/test_guards.sh` Section 6)**: Tests positive (clean comments, `#[cfg(test)]`, `#[cfg(not(target_os = "android"))]`, `/session/join` both source and `.so`) and negative (`00000000441cc96b`, `concat!`-split `00000000402b5328`, `https://...` containing `//`, `#[cfg(target_os = "android")]` XSTS URL, live `account::device_code` call, `.save()` in `blaze_diagnostics.rs`, and `libhorizon_android.so` binary markers).

---

## 4. Account Handoff Hygiene

- **In-memory JNI session (`blaze_diagnostics.rs`)**: `nativeSetSession` validates inputs, stages `PENDING_ANDROID` + sets `CURRENT` in `horizon_client::auth` RAM only, and writes zero files to disk.
- **Read-once IPC cleanup (`RustSessionHandoff.kt` & `RustActivity.kt`)**: `:main` writes a `0600` temp file in `noBackupFilesDir`; `:rust` deletes both `rust_pending_session.json` and `.tmp` via `clearPendingSessionFiles(context)` immediately upon reading in `consumePendingSessionInRustProcess`, as well as on `exitRustProcess()` and `onDestroy()` (when `isFinishing`), alongside `BlazeNativeBridge.signalEngineExit()`.
- **JVM Unit Test (`RustSessionHandoffTest.kt`)**:
  - `copyReportOutputExcludesFakeAccessTokenAcrossHandoffAndEngineDiagnostics`: Passes fake JWT (`eyJ...`) and opaque (`M.C501_BLZ...`) tokens through `RustSessionHandoff.handoffSession` (success + throwing bridge exception), `RustDiagnosticsCollector.redactDiagnosticText`, simulated `rust_spike_last_status.txt`, `performance/diagnostic.log`, historical exits, and logcat into `RustDiagnosticsCollector.collectRustDiagnosticsFromDirs(...)` (the helper behind Developer `"Copy report"`), asserting neither fake token appears anywhere in the output.
  - `engineDataDiagnosisReportsExpectedPathAndFilePresence`: Verifies `"=== Engine Data Check ==="` one-line status output for missing and partially present files.

---

## 5. Missing `engine-data.zip` & `"Retry installation"` Diagnosis (`file:line`, No Fix)

- **Why the prompt & loop appear on the current APK**:
  1. **Startup unpack check (`engine/starling/crates/horizon-client/src/android_main.rs:222,249-262`)**: Opens bundled APK asset `c"engine-data.zip"` and copies it to `<filesDir>/engine-data.partial` -> `<filesDir>/engine-data.zip`. Because Blaze Launcher intentionally omits `engine-data.zip` from APK assets (blocked by `docs/LICENSE_PLAN.md`), `<filesDir>/engine-data.zip` never exists.
  2. **Readiness check (`android/launcher.rs:460`, `launcher_pages.rs:1320`, `official_install.rs:39-46`, `resource_install.rs:47-52`)**: `horizon_client::official_install::ready(&self.home)` returns `true` only when `<filesDir>/.local/horizon-client/26.3/horizon-resources.json`, `android-downloads-v2.json`, and (with `audio` feature) `audio-complete.json` all exist.
  3. **Button state & Play action error (`android/launcher_pages.rs:1518-1527,6199` -> `android/launcher.rs:634-638`)**: Initially (`self.error.is_none()`), the button displays `"Download Minecraft 26.3"` (`Action::Play`). Tapping it enters `Launcher::action`'s `else` branch at `launcher.rs:634-638`, which checks `if !self.home.join("engine-data.zip").is_file()` and returns `Err("This APK is missing its engine data. Install a complete build.")`. Setting `self.error` flips `launcher_pages.rs:1520-1521` to `"Retry installation"`, which still pushes `Action::Play`, repeating the same check in a loop while `engine-data.zip` is missing.
- **Developer `"Copy report"` diagnostic line (`RustDiagnosticsCollector.kt`)**: Appends:
  ```text
  === Engine Data Check ===
  Engine data: expected path=<filesDir>/engine-data.zip (and extracted markers under <filesDir>/.local/horizon-client/26.3), what was found=engine-data.zip=..., engine-data.partial=..., horizon-resources.json=..., android-downloads-v2.json=..., audio-complete.json=...
  ```

---

## 6. CI Verification & Physical Device Test Plan

- **CI Verification**: *(Pending push to `feat/phase1a-q2` — will be updated immediately after CI completes.)*
- **Physical Device Verification (`UNVERIFIED` until run on SM-N975F / Android 12)**:
  - **(a)** In Developer -> **Launch Rust Edition (Spike)** (`RustActivity`): confirm `:rust` launches, Starling UI shows handed-in Microsoft account name (`Blaze Launcher Session`), and no in-engine login / Add Account / Ely / Device-code button or chevron exists.
  - **(b)** Tap account pill or inspect Settings: verify no Starling login prompt opens (`Login::session` disabled, `open_login_url` never starts Custom Tabs) and expired/unready token does not trigger any Starling refresh request.
  - **(c)** Exit `:rust` (`Exit :rust` or Back) and inspect app-private storage / Developer `"Copy report"`: confirm `rust_pending_session.json` is absent, **no** Microsoft/MC token appears in `"Copy report"`, and `"=== Engine Data Check ==="` prints expected path + `what was found=...`.
  - **(d)** Install signed release APK over prior Q1 build in-place (`android:debuggable=false`, cert SHA-256 `5aa533d1…a2fc`, strictly higher epoch `versionCode`).
