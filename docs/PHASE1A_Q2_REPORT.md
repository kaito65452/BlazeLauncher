# Phase 1a Q2 Delivery Report (`docs/PHASE1A_Q2_REPORT.md`)

> **Legal disclaimer**: All licensing and compliance observations below are **technical screening, not legal advice**.

---

## Summary Verdicts

| Workstream | Verdict | Key Outcome |
|---|---|---|
| **1. Engine Login & OAuth Audit** | **GO** | Audited all 10 embedded client-ID, MS/Xbox/XSTS/MC, Ely.by, startup pre-warm, Wardrobe cloud, and JNI login paths with exact `file:line` citations in [`docs/PHASE1A_Q2.md`](PHASE1A_Q2.md). |
| **2. Engine Login Neutralization** | **GO** (`UNVERIFIED` on device) | Patched exclusively under `engine/patches/` (`0002`, `0003`, `0004`); `engine/starling` submodule untouched at `fa548eb7abe91c194d3780e4580b9746323cd606`. Kept all 25 original + 2 Phase 1a `BlazeNativeBridge` JNI symbol names (27 total); removed `00000000441cc96b` & `00000000402b5328`, gated desktop OAuth modules with `#[cfg(not(target_os = "android"))]`, and disabled all engine login/refresh/account-switch/cloud-skin UI. |
| **3. CI Guard Hardening** | **GO** | Added `scripts/guard.sh` Check 10 (Rust lexer + `#[cfg]` pruning + `concat!` folding + call/disk-write checks) & Check 10b (`.so` binary string scan in `--check-apk`/`--check-release-apk`), plus positive/negative tests in `scripts/test_guards.sh`. |
| **4. Account Handoff Hygiene** | **GO** (`UNVERIFIED` on device) | `nativeSetSession` writes zero files to disk; `RustActivity` (`exitRustProcess` & `onDestroy`) and `RustSessionHandoff` wipe IPC staging files and call `signalEngineExit()` to clear RAM state. `RustSessionHandoffTest` verifies fake tokens are absent from `"Copy report"` output. |
| **5. Engine-Data Diagnosis** | **GO** (Diagnosis only, no fix) | Traced `"This APK is missing its engine data. Install a complete build."` + `"Retry installation"` loop (`android_main.rs:222,249-262`, `official_install.rs:39-46`, `resource_install.rs:47-52`, `launcher.rs:634-638`, `launcher_pages.rs:1518-1527`). Added one-line `Engine data: expected path=..., what was found=...` status into Developer `"Copy report"`. **NO-GO / BLOCKED** on APK bundling until Q3/upstream clarity. |
| **6. Upstream Question Draft** | **GO** (Saved locally, **not posted**) | Wrote [`docs/UPSTREAM_QUESTION.md`](UPSTREAM_QUESTION.md) covering `(a)`–`(d)`. |

---

## CI Verification

*(Pending push to `feat/phase1a-q2` — will be populated with CI run URL, `signed-apks` artifact name/ID, `apksigner` SHA-256 digest `5aa533d1…a2fc`, and epoch `versionCode` once GitHub Actions finishes.)*

---

## Physical Device Verification Checklist (`UNVERIFIED`)

On Samsung Galaxy Note 10+ (SM-N975F, Android 12) after installing `signed-apks` over the prior Q1 build:
1. **(a) No in-engine login controls**: Launch **Developer -> Launch Rust Edition (Spike)**; verify `:rust` displays handed-in account name only, with zero MS/Ely/Add Account/switcher/upload buttons or chevron.
2. **(b) Zero engine OAuth or refresh attempts**: Attempt taps on account status pill / Settings -> confirm no browser Custom Tab opens and no MS/XSTS/Ely requests occur.
3. **(c) Session wipe & redacted Copy report**: Exit `:rust`, tap **Copy report** in **Developer**, and verify `=== Engine Data Check ===` prints `<filesDir>/engine-data.zip` + found status and **zero** access/refresh tokens appear.
4. **(d) Missing engine-data prompt unchanged**: Confirm `"This APK is missing its engine data. Install a complete build."` / `"Retry installation"` behavior remains cleanly explained until Task Q3.
