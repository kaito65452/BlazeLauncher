# Stage B Delivery Report (`docs/STAGE_B.md`)

> **Status**: COMPLETE (CI-Verified on commit `70326232`; physical SM-N975F checks marked `UNVERIFIED`)
>
> **Architecture**: Pure-Rust `blaze-prepare` native library (`libblaze_prepare.so`, `arm64-v8a`, **zero Python**) + sequential OpenJDK 25 `:jvm` (`Internal-25`) execution for vanilla data generation & MIT-licensed `dev.horizonlab.parity` Java exporters + Kotlin orchestrator (`EngineDataPreparer.kt`).
>
> **Technical screening, not legal advice**: Zero Mojang assets, JARs, generated JSON tables, NBT files, or compiled `.class`/`.jar` files are checked into git or shipped in the APK. Only upstream PIN hashes (`research-lock.json`, `bedrock-controls.json`) and Starling's 26 MIT `.java` parity exporter source files (plus GPL-3.0 `PrepareBatchShim.java` and updated `THIRD_PARTY_NOTICES.md`) are packaged as assets. All Mojang artifacts are downloaded directly on the user's device during preparation.

---

## Summary Verdicts

| Deliverable | Status | Evidence (`file:line` / CI Output) |
|---|---|---|
| Pure-Rust `blaze-prepare` crate + `libblaze_prepare.so` (`arm64-v8a`) | **PASS** | `blaze-prepare/Cargo.toml:1-40`, `blaze-prepare/src/{lib,lock,download,files,survival,resources,bundler,jni_bridge}.rs`; CI `libblaze_prepare.so` release=`2,846,936` bytes (`+1.44 MiB` compressed in APK) |
| Zero Python & zero Mojang assets in repo/APK | **PASS** | `scripts/guard.sh:682-736` (`check_stage_b_hygiene`), `scripts/test_guards.sh` Check 11 (`PASS=39 FAIL=0`); CI `Verify APK License Hygiene` (`157 .so` files scanned) |
| `research-lock.json` & `bedrock-controls.json` verification + ZL2 `<gameHome>` reuse | **PASS** | `blaze-prepare/src/lock.rs:1-219`, `blaze-prepare/src/download.rs:1-773`; `zl2_game_home_reuse_avoids_redundant_downloads` (`4 passed; 0 failed`) |
| Sequential OpenJDK 25 `:jvm` (`Internal-25`) batch exporters | **PASS** (`UNVERIFIED` on device) | `ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPreparer.kt:516-636`, `PrepareBatchShim.java:1-40`, `buildCommonJvmFlags` (`-Xms256M -Xmx512..768M -Djava.awt.headless=true`) |
| Developer UI `"Prepare engine data (spike)"` + redacted `"Copy details"` + `"Copy report"` telemetry | **PASS** (`UNVERIFIED` on device) | `DeveloperSection.kt:158-270,338-360`, `EngineDataPrepareTelemetry.kt:1-85`, `RustDiagnosticsCollector.kt`, `values/` & `values-in/` `strings.xml` |
| Synthetic 4,000-file Rust vs Kotlin vs Python comparison benchmark | **PASS** | `docs/STAGE_B_REPORT.md` + CI raw `BENCH_RUST_4000`, `BENCH_KOTLIN_4000`, `BENCH_PYTHON_4000` logs |
| Signed non-debuggable release APK | **PASS** | `CI` `43664247413` (`versionCode=27483401`, `debuggable: false`, cert `5aa533d1…a2fc`, artifact `signed-apks` ID `14142821675`) |

---

## Architecture & Component Breakdown

### 1. Pure-Rust Native Preparer (`blaze-prepare/` -> `libblaze_prepare.so`)

- **[`blaze-prepare/src/lock.rs`](../blaze-prepare/src/lock.rs)** (`219` lines): Parses `research-lock.json` (`minecraft.version = "26.3"`, primary + accepted `manifest_sha1` & `asset_index_sha1`, `client_sha1 = e877b6a07acd633fb3bb475002175cec036e7b87`, `server_sha1 = 33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`) and computes combined SHA-256 prepare stamp over bundled `PrepareBatchShim.java`, parity `.java` files, and `research-lock.json`.
- **[`blaze-prepare/src/download.rs`](../blaze-prepare/src/download.rs)** (`773` lines):
  - Enforces HTTPS/SHA-1 pins against `research-lock.json` on Piston Meta & Mojang CDN downloads (`rustls` TLS stack via `ureq`, zero OpenSSL dependency).
  - Reuses existing ZL2 `<gameHome>/versions/26.3/`, `<gameHome>/libraries/`, and `<gameHome>/assets/` when sizes/hashes match; skips redundant network downloads.
  - Writes downloads through `.partial` temp files, polls cooperative `CANCEL_FLAG`, and cleans partial files on cancellation/failure.
  - Computes server classpath (skipping LWJGL native artifacts) and full client classpath for `:jvm` datagen/exporter steps.
- **[`blaze-prepare/src/files.rs`](../blaze-prepare/src/files.rs)** (`272` lines): Atomic `.partial` -> `rename` commits, recursive file listing, symlink-safe atomic staging, and `/proc/self/status` `VmHWM` peak RSS reader.
- **[`blaze-prepare/src/survival.rs`](../blaze-prepare/src/survival.rs)** (`147` lines): Normalizes/emits `survival-data.json` from datagen outputs without Python.
- **[`blaze-prepare/src/resources.rs`](../blaze-prepare/src/resources.rs)** (`970` lines): Extracts `client.jar` assets, verifies SHA-1 of asset objects, builds `horizon-resources.json`, `android-downloads-v2.json`, and `audio-complete.json` purely in Rust.
- **[`blaze-prepare/src/bundler.rs`](../blaze-prepare/src/bundler.rs)** (`330` lines): Packs `engine-data.zip` + extracted resources into `<filesDir>/engine-data/26.3/`, tracks per-phase durations and `/proc/self/status` `VmHWM`, and writes `.prepared` / `.prepared.sha256` markers atomically after verification.
- **[`blaze-prepare/src/jni_bridge.rs`](../blaze-prepare/src/jni_bridge.rs)** (`191` lines): Exposes 7 JNI entry points consumed by [`BlazePrepareNative.kt`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/BlazePrepareNative.kt#L160-L166):
  - `nativePreparePhase1`
  - `nativePreparePhase2`
  - `nativeRequestCancel`
  - `nativeResetCancel`
  - `nativeGetProgressJson`
  - `nativeIsPrepared`
  - `nativePeakRssKb`

### 2. Gradle Asset Packaging & Sequential `:jvm` Orchestrator

- **[`ZalithLauncher/build.gradle.kts`](../ZalithLauncher/build.gradle.kts)**: Copies `research-lock.json`, `bedrock-controls.json`, and all 26 MIT `.java` files under `engine/starling/mods/horizon/src/test/java/dev/horizonlab/parity/` into APK `assets/` at build time without tracking any generated data in git.
- **[`ZalithLauncher/src/main/assets/blaze-prepare/PrepareBatchShim.java`](../ZalithLauncher/src/main/assets/blaze-prepare/PrepareBatchShim.java)** (`40` lines): Small GPL-3.0 shim allowing `:jvm` (`GameJVMRunner.startJvmServiceAndWaitExit` with `Jre.JRE_25` `"Internal-25"`) to read newline-separated exporter invocations from stdin (`PrepareWorker`) rather than exceeding Android Binder / exec argument limits.
- **[`ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPreparer.kt`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPreparer.kt)** (`644` lines):
  - `runPreparation` orchestrates Phase 1 (Rust download & classpath planning) -> `ensureInternalJre25` -> `runSequentialJavaExporters` (vanilla `net.minecraft.data.Main --all` server datagen -> javac compile server exporters vs `serverClasspath` + 4 client exporters vs full `clientClasspath` -> sequential exporter execution inside `:jvm` with `512–768 MiB` heap ceiling and `-Djava.awt.headless=true`) -> Phase 2 (Rust resource bundling & atomic commit).
  - Supports cooperative cancellation (`cancel(context)` calls `nativeRequestCancel()` + `GameJVMRunner.stopAllNonMainProcesses`), elapsed-time/phase/RSS telemetry saving (`EngineDataPrepareTelemetry.kt`), and redacted failure dialog (`buildRedactedErrorDetails`).

### 3. Developer UI & Diagnostics Integration

- **[`DeveloperSection.kt`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/developer/ui/DeveloperSection.kt)** (`lines 158, 212, 338`): Adds `"Prepare engine data (spike)"` button with live phase progress strip, cancel control, and redacted `"Copy details"` failure dialog (`Developer` package remains clean per Check 9 guard).
- **[`RustDiagnosticsCollector.kt`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustDiagnosticsCollector.kt)**: Appends `EngineDataPrepareTelemetry.formatForReport(filesDir)` under `=== Engine Data Check ===` inside Developer `"Copy report"`.
- **[`scripts/guard.sh`](../scripts/guard.sh)** & **[`scripts/test_guards.sh`](../scripts/test_guards.sh)**: Added Check 11 (`check_stage_b_hygiene`) + APK asset checks (rejecting `.py`, `.pyc`, `.nbt`, prebuilt `engine-data.zip` / `horizon-resources.json`, JARs in `blaze-prepare/`, and `assets/minecraft/` or `worldgen/` trees) backed by 4 new self-tests (`PASS=39 FAIL=0`). Fixed `IS_ISOLATED_MODE` to include `GUARD_CHECK11_ONLY_DIR`.

---

## Raw Verification Evidence

### CI Runs & Artifacts (Commit `70326232`)

- **`Parallel Fast Checks` Run**: [`https://github.com/kaito65452/BlazeLauncher/actions/runs/43664247483`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43664247483) (`status: completed`, `conclusion: success`)
  - `Guards & Self-Tests` (`132660002525`): `success` in `1m19s`
  - `JVM Unit Tests` (`132660002268`): `success` in `6m48s`
- **`CI` Run**: [`https://github.com/kaito65452/BlazeLauncher/actions/runs/43664247413`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43664247413) (`status: completed`, `conclusion: success`)
  - `engine / engine` (`132660002062`): `success` in `33m9s`
    - Artifact: `horizon-engine-arm64` (ID `14141344813`, `28102350` bytes)
  - `ci` (`132667947678`): `success` in `14m42s`
    - Artifact: `signed-apks` (ID `14142821675`, `378275842` bytes)

```text
Guards & Self-Tests:
=== Summary: PASS=39 FAIL=0 ===
=== Scope Guard Checks Passed ===

JVM Unit Tests (43664247483):
BENCH_KOTLIN_4000 build_ms=77 extract_ms=113 verify_ms=68 zip_ms=81 heap_kb=47490

CI Engine Build (43664247413 / 132660002062):
libhorizon_android.so before (fast, unstripped): 81135272 bytes
libhorizon_android.so after (release, lto=fat, panic=abort, strip=true): 52018200 bytes
test result: ok. 4 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 4.29s
BENCH_RUST_4000 build_ms=1074 extract_ms=1862 verify_ms=92 zip_ms=977 peak_rss_kb=15544
BENCH_PYTHON_4000 build_ms=270 extract_ms=335 verify_ms=62 zip_ms=221 peak_rss_kb=19236
libblaze_prepare.so before (fast, unstripped): 60645504 bytes
libblaze_prepare.so after (release, lto=fat, panic=abort, strip=true): 2846936 bytes

CI APK Verification (43664247413 / 132667947678):
Building with versionCode=27483401 versionName=2.6_hotfix-***.70326232
BUILD SUCCESSFUL in 11m 16s
✓ Confirmed zero debug APKs exist under ZalithLauncher/build/outputs/apk/debug.
Scanning 157 shared libraries under lib/*/*.so...
✓ Release APK manifest verified non-debuggable (android:debuggable=false).
✓ APK license hygiene check passed (157 .so files scanned).
apkanalyzer manifest debuggable (ZalithLauncher/build/outputs/apk/release/BlazeLauncher-2.6_hotfix-***.70326232.apk): false
APK size report for ZalithLauncher/build/outputs/apk/release/BlazeLauncher-2.6_hotfix-***.70326232.apk: total=381872488 bytes (364.18 MiB)
  entry=lib/arm64-v8a/libhorizon_android.so uncompressed=52018192 bytes (49.61 MiB) compressed=25504760 bytes (24.32 MiB)
  entry=lib/arm64-v8a/lib***_prepare.so uncompressed=2846936 bytes (2.72 MiB) compressed=1511576 bytes (1.44 MiB)
  bundled parity java sources: 26
V2 Signer: certificate SHA-256 digest: 5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc
Nightly versionCode: 27321741
✓ Verification passed: Produced versionCode (27483401) > Nightly versionCode (27321741).
```

Local offline Termux verification (`cargo test --offline --locked --manifest-path blaze-prepare/Cargo.toml`):
- `4 passed; 0 failed` (`pin_mismatch_and_cancellation_clean_partials`, `zl2_game_home_reuse_avoids_redundant_downloads`, `full_phase1_and_phase2_with_mock_http_and_engine_data_zip_parity`, `synthetic_4000_file_benchmark`), 0 compiler warnings.
- Local `aarch64` debug benchmark: `BENCH_RUST_4000 build_ms=2428 extract_ms=4907 verify_ms=347 zip_ms=3349 peak_rss_kb=15516` vs local Termux Python: `BENCH_PYTHON_4000 build_ms=967 extract_ms=1384 verify_ms=389 zip_ms=1384 peak_rss_kb=21272`.

---

## `UNVERIFIED` Items (Physical SM-N975F Only)

1. End-to-end live download & preparation of Minecraft `26.3` engine-data on `SM-N975F` (Android 12) via Developer -> `"Prepare engine data (spike)"` (Piston Meta fetch, ZL2 `<gameHome>` reuse, `Internal-25` `:jvm` `javac` compilation + execution of vanilla datagen & 26 Starling MIT parity exporters, Phase 2 bundling into `<filesDir>/engine-data/26.3/`).
2. Live `:rust` (`RustActivity`) boot from prepared on-device `<filesDir>/engine-data` without `"This APK is missing its engine data. Install a complete build."`.
3. Device-measured wall-clock seconds, cancel-cleanup verification (zero lingering `.partial` files / `:jvm` child processes), and `:jvm`/`libblaze_prepare.so` `VmHWM` on `SM-N975F`.
4. Optimized `--release` 4,000-file synthetic timing on ARM64 hardware (measured benchmarks ran on CI `ubuntu-latest` x86_64 and local debug Cargo test).
