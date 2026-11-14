# Phase 1a Q2 Delivery Report (`docs/PHASE1A_Q2_REPORT.md`)

> **Legal disclaimer**: All licensing and compliance observations below are **technical screening, not legal advice**.

---

## Summary Verdicts

| Workstream | Verdict | Key Outcome |
|---|---|---|
| **1. Engine Login & OAuth Audit** | **GO** | Audited all 10 embedded client-ID, MS/Xbox/XSTS/MC, Ely.by, startup pre-warm, Wardrobe cloud, and JNI login paths with exact `file:line` citations in [`docs/PHASE1A_Q2.md`](PHASE1A_Q2.md). |
| **2. Engine Login Neutralization** | **GO** (`UNVERIFIED` on device) | Patched exclusively under `engine/patches/` (`0002`, `0003`, `0004`); `engine/starling` submodule untouched at `fa548eb7abe91c194d3780e4580b9746323cd606`. Kept all 25 original + 2 Phase 1a `BlazeNativeBridge` JNI symbol names (27 total); removed `00000000441cc96b` & `00000000402b5328`, gated desktop OAuth modules with `#[cfg(not(target_os = "android"))]`, and disabled all engine login/refresh/account-switch/cloud-skin UI. |
| **3. CI Guard Hardening** | **GO** | Added `scripts/guard.sh` Check 10 (Rust lexer + `#[cfg]` pruning + `concat!` folding + call/disk-write checks + desktop-only `backend.rs` exclusion) & Check 10b (`.so` binary string scan in `--check-apk`/`--check-release-apk`), isolated-mode fast dispatch (`IS_ISOLATED_MODE`), 34 positive/negative tests in `scripts/test_guards.sh` (`PASS=34 FAIL=0`), plus `.github/workflows/parallel-checks.yml`. |
| **4. Account Handoff Hygiene** | **GO** (`UNVERIFIED` on device) | `nativeSetSession` writes zero files to disk; `RustActivity` (`exitRustProcess` & `onDestroy`) and `RustSessionHandoff` wipe IPC staging files and call `signalEngineExit()` to clear RAM state. `RustSessionHandoffTest` verifies fake tokens are absent from `"Copy report"` output. |
| **5. Engine-Data Diagnosis** | **GO** (Diagnosis only, no fix) | Traced `"This APK is missing its engine data. Install a complete build."` + `"Retry installation"` loop (`android_main.rs:222,249-262`, `official_install.rs:39-46`, `resource_install.rs:47-52`, `launcher.rs:634-638`, `launcher_pages.rs:1518-1527`). Added one-line `Engine data: expected path=..., what was found=...` status into Developer `"Copy report"`. **NO-GO / BLOCKED** on APK bundling until Q3/upstream clarity. |
| **6. Upstream Question Draft** | **GO** (Saved locally, **not posted**) | Wrote [`docs/UPSTREAM_QUESTION.md`](UPSTREAM_QUESTION.md) covering `(a)`–`(d)`. |

---

## CI Verification

- **Verified Code & Native Build Commit**: `21807ad597bcfa05efba22cfa0e46042d58e44f9` on branch `feat/phase1a-q2`
- **`CI` Workflow Run**: [`https://github.com/kaito65452/BlazeLauncher/actions/runs/43601187740`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43601187740) (`success`)
  - **`engine / engine` (`ID 132466076949`, `31m0s`)**: Cleanly applied patches `0001`–`0004` onto `fa548eb7abe91c194d3780e4580b9746323cd606`, passed `ENGINE_BUILD_ENABLED=1 ./scripts/guard.sh`, and built `arm64-v8a` `libhorizon_android.so` (`81123352` bytes fast/unstripped -> `52017880` bytes release fat-LTO stripped; uploaded as `horizon-engine-arm64` ID `14117895219`).
  - **`ci` (`ID 132474052720`, `8m52s`)**:
    - Guards & self-tests: `=== Summary: PASS=34 FAIL=0 ===`, `=== Scope Guard Checks Passed ===`
    - Gradle build & tests: `./gradlew testDebugUnitTest assembleRelease` (`BUILD SUCCESSFUL in 6m 58s`)
    - Release APK verification: `apkanalyzer manifest debuggable` = `false`; `--check-release-apk` scanned `156 .so` files (`✓ APK license hygiene check passed`); total APK `380253857` bytes (`362.64 MiB`) containing `lib/arm64-v8a/libhorizon_android.so` (`52017872` bytes uncompressed / `25504592` bytes compressed)
    - Signing certificate (`apksigner verify --print-certs`): `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`
    - Monotonic `versionCode`: `27440150` (`> Nightly versionCode: 27321741`)
    - **Signed APK Artifact**: `signed-apks` (ID `14117911499`, `376664393` bytes)
- **`Parallel Fast Checks` Workflow Run**: [`https://github.com/kaito65452/BlazeLauncher/actions/runs/43601187684`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43601187684) (`success`: `Guards & Self-Tests` `1m10s`, `JVM Unit Tests` `6m37s`)

---

## Physical Device Verification Checklist (`UNVERIFIED`)

On Samsung Galaxy Note 10+ (SM-N975F, Android 12) after installing `signed-apks` over the prior Q1 build:
1. **(a) No in-engine login controls**: Launch **Developer -> Launch Rust Edition (Spike)**; verify `:rust` displays handed-in account name only, with zero MS/Ely/Add Account/switcher/upload buttons or chevron.
2. **(b) Zero engine OAuth or refresh attempts**: Attempt taps on account status pill / Settings -> confirm no browser Custom Tab opens and no MS/XSTS/Ely requests occur.
3. **(c) Session wipe & redacted Copy report**: Exit `:rust`, tap **Copy report** in **Developer**, and verify `=== Engine Data Check ===` prints `<filesDir>/engine-data.zip` + found status and **zero** access/refresh tokens appear.
4. **(d) Missing engine-data prompt unchanged**: Confirm `"This APK is missing its engine data. Install a complete build."` / `"Retry installation"` behavior remains cleanly explained until Task Q3.
