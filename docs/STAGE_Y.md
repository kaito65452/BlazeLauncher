# Stage Y Report — Manifest & Asset-Index Pin Drift (`docs/STAGE_Y.md`)

> **Branch**: `feat/overnight-stages`
> **Constraint compliance**: `arm64-v8a` release-signed only; never weaken guards; `engine/starling` submodule untouched in git (`fa548eb7abe91c194d3780e4580b9746323cd606`); no PR merges; no Mojang assets or Mojang-derived data in repo, APK, CI artifacts, or logs; tokens scrubbed and UUIDs truncated to first 8 chars; legal notes marked *technical screening, not legal advice*.

---

## Status Matrix (Y1–Y7)

| Item | Status | Summary |
|---|---|---|
| **Y1** Upstream lock check (`android` & `starling`) | **GO** (verified via `gh api`) | Neither `android` (`fc5a3c5e`) nor `starling` (`09fa16c1`) includes `9f67e10ed013deeaad20ac3c800652765d493408` or `e99f9973047785b5a9d52eaf72020b97545053c9`; submodule pin left at `fa548eb7ab`. |
| **Y2** Local patch `0007-pin-refresh-26.3.patch` & APK packaging | **GO** (verified in CI `43704222119`) | Patch adds `9f67e10e...` to `accepted_manifest_sha1` and `e99f9973...` to `accepted_asset_index_sha1`; `client_sha1`/`server_sha1` unchanged; `ci.yml`/`nightly.yml`/`release.yml` apply patch cleanly at build time and CI verified both hashes inside built APK `assets/research-lock.json`. |
| **Y3** `blaze-prepare` pin rules + task menu warning + Copy report | **GO** (verified in CI `43704222119` & `43704222099`) | Enforces manifest list + asset-index list + strict `client_sha1`/`server_sha1`; flags local-refresh pins as `"local pin refresh, not verified by upstream"` in UI and `=== Engine Data Check ===`. |
| **Y4** Guard Check 12 (`scripts/guard.sh` + `scripts/test_guards.sh`) | **GO** (verified in `43704222099`) | Rejects `0007` touching anything outside `accepted_manifest_sha1` / `accepted_asset_index_sha1` in `research-lock.json`; self-tested (`PASS=43 FAIL=0`) against `client_sha1`, unrelated-field, and wrong-file patches. |
| **Y5** Descriptive error messages & redaction | **GO** (verified in CI `43704222119`) | Reports file hashed (`reuse from <gameHome>` vs `work cache` vs `direct download`), computed hash, expected/accepted list, and plain-language next step; preserves 40-hex SHA-1 while scrubbing tokens & full UUIDs. |
| **Y6** Synthetic Rust & Kotlin verification tests | **GO** (verified in CI `43704222119` & `43704222099`) | Tests `(i)` accepted drift + warning, `(ii)` unlisted manifest, `(iii)` unlisted asset index, `(iv)` client/server mismatch, `(v)` warning reaching Copy report builder. |
| **Y7** Upstream follow-up in `docs/UPSTREAM_QUESTION.md` | **GO** | Appended question `(e)` on `research-lock.json` refresh workflow and `starling` branch coverage (`DRAFT ONLY`). |
| Device end-to-end 26.3 preparation (`SM-N975F`) | **UNVERIFIED** | Requires physical device run with newly signed CI APK. |

---

## Y1. Upstream Lock Investigation (`starling-client/starling`)

- **`android` branch HEAD**: `fa548eb7abe91c194d3780e4580b9746323cd606` (already matches `engine/starling` submodule pin).
  - Latest commit touching `research-lock.json` on `android`: `fc5a3c5eb15b3c373760ba007877e3393c566244` (`2026-11-03T16:20:45Z`, *"Accept republished 26.3 manifest"* — added `80608ff6753705b6a82e2cb86a5937d4d233abd4`, no new asset index).
  - Prior commit: `3c980626625e12bded2fa5d31d397d5b6dcbce5c` (`2026-10-28T16:51:58Z`, *"Prepare: accept Mojang's fifth 26.3 republish (5df1795e, index 917ff6d7)"*).
- **`starling` branch HEAD**: `09fa16c167c4b2f8eb23fddd0faa808d56d0a2f2` (`2026-11-13T16:54:32Z`, *"Building on GitHub takes as many mods as the player picked (#504)"*).
  - Latest commit touching `research-lock.json` on `starling`: `3c980626625e12bded2fa5d31d397d5b6dcbce5c` (`2026-10-28T16:51:58Z`); it does not include `80608ff6...`, `9f67e10ed013deeaad20ac3c800652765d493408`, or `e99f9973047785b5a9d52eaf72020b97545053c9`.
- **Engine code delta summary (`fa548eb7ab...09fa16c1`)**: `status=diverged`, `ahead_by=4472`, `behind_by=330`, `files_changed=300` (API cap), including gateway/GitHub mod builds (`#504`, `#368`), signed releases (`#434`), startup memory step logging (`#386`), Android BuildId/symbols (`#383`), PowerVR/GLES fixes (`#381`), and ported Horizon Android controls (`#350`).
- **Submodule state**: `engine/starling` remains pinned at `fa548eb7abe91c194d3780e4580b9746323cd606` and clean.

---

## Y2–Y7. Implementation Notes

- **Y2 (`engine/patches/0007-pin-refresh-26.3.patch`)**:
  - Appends `"9f67e10ed013deeaad20ac3c800652765d493408"` to `accepted_manifest_sha1` and `"e99f9973047785b5a9d52eaf72020b97545053c9"` to `accepted_asset_index_sha1` in `research-lock.json`.
  - `.github/workflows/rust-engine.yml` applies all `engine/patches/*.patch`; `.github/workflows/ci.yml`, `.github/workflows/nightly.yml`, and `.github/workflows/release.yml` apply `0007-pin-refresh-26.3.patch` after guards so Gradle packages the refreshed `assets/research-lock.json` into the APK while keeping the submodule clean in git. `ci.yml` explicitly verifies both refreshed hashes inside the built APK's `assets/research-lock.json`.
- **Y3 (`blaze-prepare` + UI + Copy report)**:
  - `blaze-prepare/src/lock.rs`: enforces both `manifest_accepted` and `asset_index_accepted` alongside strict `client_sha1` and `server_sha1` pins; tracks `LOCAL_REFRESHED_MANIFEST_SHA1` and `LOCAL_REFRESHED_ASSET_INDEX_SHA1` so pins satisfied via `0007` are flagged as `"local pin refresh, not verified by upstream"`.
  - `blaze-prepare/src/resources.rs`, `BlazePrepareNative.kt`, `EngineDataPreparer.kt`, `DeveloperSection.kt`, `EngineDataPrepareTelemetry.kt`, `RustDiagnosticsCollector.kt`: propagate `warnings` from `Phase1Output` into progress updates, `PrepareActionStrip` / `formatPrepareSummary` in the Developer prepare UI, persisted `blaze_prepare_metrics.json`, and `=== Engine Data Check ===` in Copy report.
- **Y4 (`scripts/guard.sh` Check 12 & `scripts/test_guards.sh` Section 8)**:
  - Rejects `0007-pin-refresh-26.3.patch` if it touches any file besides `research-lock.json`, alters non-accepted fields (`client_sha1`, `server_sha1`, `manifest_sha1`, `java_major`, `version`, notes), removes hashes, or adds unrelated JSON fields.
- **Y5 & Y6 (`blaze-prepare/src/files.rs`, `download.rs`, `tests/prepare_tests.rs`, `EngineDataPrepareTest.kt`)**:
  - Pin/checksum errors report file source (`reuse from <gameHome>` vs `work cache` vs `direct download`), computed hash, expected pin / accepted list, and next step in plain words; `redact_sensitive` scrubs tokens and truncates full UUIDs to first 8 chars while keeping 40-hex SHA-1 digests intact.
- **Y7 (`docs/UPSTREAM_QUESTION.md`)**:
  - Appended question `(e)` on upstream's process for refreshing `research-lock.json` on `26.3` republishes and whether `starling` will carry it (`DRAFT ONLY`).

---

## CI & Verification Evidence

Verified on commit `c4d6a7b5f6111c3485faf3967c648b6670c5a048` (`feat/overnight-stages`):

- **CI Run URL**: `https://github.com/kaito65452/BlazeLauncher/actions/runs/43704222119` (`success`)
- **Parallel Fast Checks URL**: `https://github.com/kaito65452/BlazeLauncher/actions/runs/43704222099` (`success`)
- **Artifacts (`43704222119`)**:
  - `signed-apks` — ID `14158956631` (`378295180` bytes)
  - `horizon-engine-arm64` — ID `14158213826` (`28109055` bytes)
- **Signing & Version Check (`ci` job `132787052656`)**:
```text
Building with versionCode=27514348 versionName=2.6_hotfix-***.c4d6a7b5
✓ Release APK manifest verified non-debuggable (android:debuggable=false).
apkanalyzer manifest debuggable (ZalithLauncher/build/outputs/apk/release/BlazeLauncher-2.6_hotfix-***.c4d6a7b5.apk): false
APK size report for ZalithLauncher/build/outputs/apk/release/BlazeLauncher-2.6_hotfix-***.c4d6a7b5.apk: total=381897008 bytes (364.21 MiB)
  verified bundled assets/research-lock.json includes 26.3 local pin refresh hashes
V2 Signer: certificate SHA-256 digest: 5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc
✓ Signing certificate SHA-256 matched 5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc.
```
- **Rust Tests & Python Oracle (`engine` job `132781729508`) & `aarch64` benchmark (`132781729265`)**:
```text
test pin_mismatch_and_cancellation_clean_partials ... ok
test task_y6_manifest_and_asset_index_drift_policy_synthetic ... ok
test zl2_game_home_reuse_avoids_redundant_downloads ... ok
test full_phase1_and_phase2_with_mock_http_and_engine_data_zip_parity ... ok
test synthetic_parity_export_for_ci_oracle ... ok
test result: ok. 5 passed; 0 failed; 0 ignored; 0 measured; 1 filtered out; finished in 0.11s
ORACLE_MARKERS_OK schema=7 output_version=3
ORACLE_SURVIVAL_OK rules=76 sha256=0093bcac1434e0da19b3f789903173d36ba3be5ae3b36a6d05e816449c8045bd
ORACLE_BUNDLER_OK libs=1
ORACLE_ZIPS_OK package_resources_entries=87 engine_data_entries=33
test synthetic_4000_file_benchmark ... ok
```
- **Guards & Self-Tests (`132781729412`) & JVM Unit Tests (`132781729210`)**:
```text
=== Summary: PASS=43 FAIL=0 ===
=== Scope Guard Checks Passed ===
./gradlew testDebugUnitTest --no-daemon --stacktrace -> BUILD SUCCESSFUL in 5m 8s
```
- **UNVERIFIED (Device-only)**: End-to-end 26.3 preparation on physical device (`SM-N975F`) using the new CI-signed APK (`versionCode=27514348 > 27502022`).
