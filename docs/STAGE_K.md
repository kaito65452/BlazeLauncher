# Stage K Summary: Precompiled Engine Data Bundle & Release Isolation (K1–K8)

> Legal/licensing statements in this document are **technical screening, not legal advice**.

## Upstream Reference (Pinned Submodule `engine/starling` @ `fa548eb7abe91c194d3780e4580b9746323cd606`)
- `engine/starling/tools/android_prepare.py:125-160` — runs desktop `horizon-prepare --prepare` and calls `package_resources` / `bootstrap.json`.
- `engine/starling/tools/android_build.py:49-70` — defines `generated_engine_file(name)` allowlist (33 paths: 21 top-level `.json`/`.nbt`, 4 biome-parameter paths, 7 `worldgen/blocks/*`, 1 `worldgen/block_flags.json`).
- `engine/starling/.github/workflows/android-dev.yml` (`~105-115`) — CI build flow for upstream public APK bundling `engine-data.zip`.

## Implementation Summary by Task

### K1 — CI Bundling (`ci.yml` + `scripts/ci_bundle_engine_data.py`)
- Controlled by GitHub Actions repository variable `BUNDLE_ENGINE_DATA == 'true'` (`.github/workflows/ci.yml:21,76,83,90`).
- `scripts/ci_bundle_engine_data.py` transiently fetches the 5,216 pinned `26.3` Mojang inputs under `ALLOWED_DOWNLOAD_HOSTS` + SHA-1 pins (`.patch` `0007` applied first at `.github/workflows/ci.yml:50-54`), runs upstream prebuilt `horizon-prepare --prepare` on JDK 25 (`32.2s`), verifies `horizon-prepare --check` passes except optional sounds, runs `android_prepare.package_resources` + `android_build.engine_data(public=True)`, verifies every entry against `generated_engine_file`, and copies `ZalithLauncher/src/main/assets/engine-data.zip`.
- No Mojang files are committed or cached; `.gitignore` excludes `ZalithLauncher/src/main/assets/engine-data.zip` and `.local/ci-host-tools/`; `Clean Transient Runner Data (K1/K2)` deletes all runner scratch dirs under `if: always()`.

### K2 — Reconciliation Oracle (`K2_ORACLE`)
- On the same transient `mc_dir` (`--game-home`), `scripts/ci_bundle_engine_data.py` runs prebuilt `blaze-prepare --example preflight` (`65.1s`, peak Rust RSS `91,204 KiB`, max Java step RSS `548,000 KiB`) and compares every entry in upstream `engine-data.zip` against `blaze-prepare` output (`43749595583`):
  - `K2_ORACLE total=33 byte_identical=27 json_structurally_equal=3 differing=3`
  - `3` diffs are `block-behaviours.json` (equal `1,982,156` bytes, array ordering), `creative-inventory.json` (equal `406,022` bytes, array ordering), and `horizon-resources.json` (`5,528` desktop health map vs `1,573` compact `schema=7`).
- Source of truth for `assets/engine-data.zip` is upstream `horizon-prepare` via `android_build.engine_data(public=True)`.

### K3 — App Fast Path & Fallback (`blaze-prepare` + Kotlin UI)
- `blaze-prepare/src/resources.rs`: `PrepareConfig.bundled_engine_data_zip`, `validate_bundled_engine_data_zip`, `verify_readiness_markers`, `write_worldgen_bundles_from_client_only`, and `run_bundled_prepare` (skips `server.jar` and 114 Java libraries; reuses ZL2 `game_home`).
- `blaze-prepare/src/jni_bridge.rs`: shared `run_prepare_op` + `nativePrepareFromBundledZip`.
- Kotlin layer (`BlazePrepareNative.kt`, `EngineDataPreparer.kt`, `DeveloperSection.kt`, `values/strings.xml`, `values-in/strings.xml`): auto-selects `EngineDataInstallMode.BUNDLED_ASSET` when `assets/engine-data.zip` is present (no `javac`, no `datagen`), otherwise labels the legacy `:jvm` fallback clearly as slow/experimental in EN & ID strings.

### K4 — Narrow Check 11 Exception (`scripts/guard.sh` + `scripts/test_guards.sh`)
- `scripts/guard.sh` permits `assets/engine-data.zip` **only** when:
  1. Path is literally `assets/engine-data.zip` (any relocated `engine-data*.zip` fails).
  2. Every zip member satisfies `ALLOWED_GENERATED_ENGINE_FILES` (zero `.class`, `.jar`, `.png`, `.ogg`, `assets/minecraft/**`, etc.).
  3. `BUNDLE_ENGINE_DATA == 'true'` AND not `--check-release-upload`.
  4. Documented in `docs/ENGINE_DATA_BUNDLE.md`.
- Covered by 6 negative + 1 positive synthetic self-tests in `scripts/test_guards.sh` (Section 7b).

### K5 — Legal & Bundle Docs *(Technical Screening, Not Legal Advice)*
- Updated `docs/LICENSE_PLAN.md`, `ZalithLauncher/src/main/assets/THIRD_PARTY_NOTICES.md`, `docs/ENGINE_DATA_BUNDLE.md`, and `docs/MORNING_TEST_STEPS.md`.

### K6 — CI Host-Tool Caching (`rust-engine.yml` + `ci.yml`)
- `.github/workflows/rust-engine.yml`: builds & caches `horizon-prepare` + `blaze-prepare-preflight` under `.local/ci-host-tools/` using `actions/cache@v4` keyed by submodule SHA + patch hash + Cargo hash without rebuilding `horizon-android` when cached (`Cache hit` observed in run `43749595583`, job `132920364235`).

### K7 — Synthetic Tests (CI Verified)
- Rust unit & e2e tests in `blaze-prepare/tests/prepare_tests.rs`: `bundled_engine_data_validator_enforces_exact_allowlist` and `bundled_prepare_end_to_end_skips_server_and_stamps_all_markers` (MockHttp without `/server.jar` or `/client-lib.jar`).
- Kotlin JVM tests in `ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPrepareTest.kt`.

### K8 — Nightly / Release Bundle Isolation & Retention Policy
- `.github/workflows/nightly.yml:138` and `.github/workflows/release.yml:90`: invoke `./scripts/guard.sh --check-release-upload "$apk"` before uploading GitHub Release assets; neither file references `BUNDLE_ENGINE_DATA` or downloads Mojang assets.
- `.github/workflows/ci.yml:336`: `signed-apks` upload sets `retention-days: 3`.
- `scripts/guard.sh:959-1049,1052-1064,1313-1326` (Check 14 + `--check-release-upload`): rejects any reference to `BUNDLE_ENGINE_DATA` / Mojang download URLs in `nightly.yml` or `release.yml`, requires `--check-release-upload` in any workflow uploading GitHub Release assets, requires `retention-days: 3` on `ci.yml` `signed-apks`, and rejects any APK containing `assets/engine-data.zip` under `--check-release-upload` even when `BUNDLE_ENGINE_DATA=true`.
- Covered by 9 synthetic checks (8 fail/pass + real dir) in `scripts/test_guards.sh` (Section 11).

## Gate Status
- **CI Compilation, Rust Tests, Kotlin Unit Tests, Guards & K2 Oracle**: **PASS** (`CI` run `43749595583`, `Parallel Fast Checks` run `43749595579`).
- **On-Device Execution (APK install, `nativePrepareFromBundledZip` on Android `filesDir`, `official_install::ready`, in-game launch with `BUNDLE_ENGINE_DATA=true` APK)**: **UNVERIFIED** (requires physical Android device test using APK from `signed-apks` artifact `14175899800`).
