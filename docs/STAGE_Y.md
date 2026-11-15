# Stage Y Report — Manifest & Asset-Index Pin Drift (`docs/STAGE_Y.md`)

> **Branch**: `feat/overnight-stages`
> **Constraint compliance**: `arm64-v8a` release-signed only; never weaken guards; `engine/starling` submodule untouched in git (`fa548eb7abe91c194d3780e4580b9746323cd606`); no PR merges; no Mojang assets or Mojang-derived data in repo, APK, CI artifacts, or logs; tokens scrubbed and UUIDs truncated to first 8 chars; legal notes marked *technical screening, not legal advice*.

---

## Status Matrix (Y1–Y7)

| Item | Status | Summary |
|---|---|---|
| **Y1** Upstream lock check (`android` & `starling`) | **GO** (verified via `gh api`) | Neither `android` (`fc5a3c5e`) nor `starling` (`09fa16c1`) includes `9f67e10ed013deeaad20ac3c800652765d493408` or `e99f9973047785b5a9d52eaf72020b97545053c9`; submodule pin left at `fa548eb7ab`. |
| **Y2** Local patch `0007-pin-refresh-26.3.patch` & APK packaging | **PENDING CI RUN** | Patch adds `9f67e10e...` to `accepted_manifest_sha1` and `e99f9973...` to `accepted_asset_index_sha1`; `client_sha1`/`server_sha1` unchanged; `ci.yml`/`nightly.yml`/`release.yml` apply patch cleanly at build time. |
| **Y3** `blaze-prepare` pin rules + task menu warning + Copy report | **PENDING CI RUN** | Enforces manifest list + asset-index list + strict `client_sha1`/`server_sha1`; flags local-refresh pins as `"local pin refresh, not verified by upstream"` in UI and `=== Engine Data Check ===`. |
| **Y4** Guard Check 12 (`scripts/guard.sh` + `scripts/test_guards.sh`) | **PENDING GUARD RUN** | Rejects `0007` touching anything outside `accepted_manifest_sha1` / `accepted_asset_index_sha1` in `research-lock.json`; self-tested against `client_sha1`, unrelated-field, and wrong-file patches. |
| **Y5** Descriptive error messages & redaction | **PENDING CI RUN** | Reports file hashed (`reuse from <gameHome>` vs `work cache` vs `direct download`), computed hash, expected/accepted list, and plain-language next step; preserves 40-hex SHA-1 while scrubbing tokens & full UUIDs. |
| **Y6** Synthetic Rust & Kotlin verification tests | **PENDING CI RUN** | Tests `(i)` accepted drift + warning, `(ii)` unlisted manifest, `(iii)` unlisted asset index, `(iv)` client/server mismatch, `(v)` warning reaching Copy report builder. |
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

*(Populated after CI completes on `feat/overnight-stages`.)*
