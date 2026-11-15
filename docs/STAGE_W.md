# Stage 2 Delivery Report (`docs/STAGE_W.md`) — Task W: All Tests & Checks Run in CI

> **Status**: Implementation Complete (`feat/overnight-stages`); cache-hit `after` wall-clock measured on subsequent non-engine commit push (Stage 3).

---

## W1 — Inventory of Tests, Benchmarks, Guards, and Build Checks

Every check documented in `docs/*.md`, `scripts/`, or agent rules is mapped below to its GitHub Actions workflow/job:

| # | Check / Test / Benchmark | Source / Reference | Workflow & Job | W1 Status -> W2 Action |
|---|---|---|---|---|
| 1 | `scripts/guard.sh` (static hygiene + check 1–13) | `scripts/guard.sh` | `parallel-checks.yml` (`guards-and-self-tests`), `ci.yml` (`ci`), `nightly.yml`, `release.yml` | Present (every push/PR) |
| 2 | `scripts/test_guards.sh` (guard self-tests) | `scripts/test_guards.sh` | `parallel-checks.yml` (`guards-and-self-tests`), `ci.yml` (`ci`), **`nightly.yml`, `release.yml`** | Present in PR/push; **MISSING in `nightly.yml`/`release.yml` -> ADDED** |
| 3 | `scripts/scope_guard.sh` (branch scope guard) | `scripts/scope_guard.sh` | `parallel-checks.yml`, `ci.yml`, `nightly.yml`, `release.yml` | Present |
| 4 | `scripts/check_no_zero_files.sh` (NUL/zero-file check) | `scripts/check_no_zero_files.sh` | Called by `guard.sh` across all 4 workflows | Present |
| 5 | `bash -n scripts/*.sh` (shell syntax validation) | Shell scripts in `scripts/` | **`parallel-checks.yml` (`guards-and-self-tests`)** | **MISSING -> ADDED** |
| 6 | `./gradlew testDebugUnitTest` (Kotlin unit tests incl. `BENCH_KOTLIN_4000`, `EngineDataPrepareTest`, `RustSessionHandoffTest`, `StageCDiagnosticsTest`, `StageDIntegrationTest`) | `ZalithLauncher/build.gradle.kts` | `parallel-checks.yml` (`jvm-unit-tests`), `ci.yml` (`ci`) | Present |
| 7 | `./gradlew assembleRelease` + `guard.sh --check-release-apk` + `apkanalyzer manifest debuggable` + `apksigner verify` + monotonic `versionCode` | `docs/PHASE1A_REPORT.md` | `ci.yml` (`ci`), `nightly.yml`, `release.yml` | Present |
| 8 | `blaze-prepare` Rust unit tests + Python oracle parity + `task_x4` | `blaze-prepare/tests/prepare_tests.rs` | `rust-engine.yml` (`engine`) | Present |
| 9 | `blaze-prepare` 4,000-file `--release` synthetic benchmark vs Python (`x86_64` & `aarch64`) | `docs/STAGE_B_REPORT.md` | `rust-engine.yml` (`engine` & `bench-aarch64`) | Present |
| 10 | Transient Minecraft `26.3` download host audit (Task X2) | `docs/STAGE_X.md` | `rust-engine.yml` (`engine`) | Present |
| 11 | `horizon-prepare` Rust unit tests (pinned + patched submodule) | `engine/starling/crates/horizon-prepare/` | **`rust-engine.yml` (`engine`)** | **MISSING -> ADDED** |
| 12 | `.so` caching keyed on submodule + patches + `Cargo.lock` | `W5` requirement | **`rust-engine.yml` (`engine`)** | **MISSING -> ADDED** |
| 13 | Real Mojang `26.3` preflight + upstream `horizon-prepare` parity | `docs/STAGE_P.md` | `preflight-real.yml` (`workflow_dispatch`, Stage 3) | Implemented in Stage 3 |

---

## W2 — Changes Made to Existing Workflows

- **[`.github/workflows/parallel-checks.yml`](file:///data/data/com.termux/files/home/BlazeLauncher/.github/workflows/parallel-checks.yml#L28-L35)**: Added `bash -n scripts/*.sh` and `chmod +x scripts/check_no_zero_files.sh` inside `guards-and-self-tests` (runs unconditionally on every push and PR; zero path filters, zero caches).
- **[`.github/workflows/nightly.yml`](file:///data/data/com.termux/files/home/BlazeLauncher/.github/workflows/nightly.yml#L32-L38)** & **[`.github/workflows/release.yml`](file:///data/data/com.termux/files/home/BlazeLauncher/.github/workflows/release.yml#L32-L38)**: Added `./scripts/test_guards.sh` (plus `chmod +x` for `test_guards.sh` and `check_no_zero_files.sh`).
- **[`.github/workflows/rust-engine.yml`](file:///data/data/com.termux/files/home/BlazeLauncher/.github/workflows/rust-engine.yml#L24-L165)**:
  - Exposed `steps.submodule.outputs.sha` (`fa548eb7abe91c194d3780e4580b9746323cd606`).
  - Added unconditional `cargo test --locked --manifest-path engine/starling/Cargo.toml -p horizon-prepare -- --nocapture` so patched upstream engine preparation tests run on every push even when `.so` caches hit.
  - Kept all existing guards, `blaze-prepare` unit/oracle tests, X2 transient Mojang host audit, and `--release` 4,000-file benchmarks running unconditionally.

---

## W3 & W4 — Agent Rules & Documentation Alignment

- **[`AGENTS.md`](file:///data/data/com.termux/files/home/BlazeLauncher/AGENTS.md)** & **[`GEMINI.md`](file:///data/data/com.termux/files/home/BlazeLauncher/GEMINI.md)**: Added the verbatim `## Device Load Rules (highest priority; the agent runs on my phone)` block above `## Core Rules`.
- **[`docs/STAGE_B.md`](file:///data/data/com.termux/files/home/BlazeLauncher/docs/STAGE_B.md)**, **[`docs/STAGE_C.md`](file:///data/data/com.termux/files/home/BlazeLauncher/docs/STAGE_C.md)**, **[`docs/MS_LOGIN.md`](file:///data/data/com.termux/files/home/BlazeLauncher/docs/MS_LOGIN.md)**: Replaced all instructions/references suggesting local `cargo test` or `./gradlew` execution with clear pointers to `.github/workflows/rust-engine.yml`, `parallel-checks.yml`, and `ci.yml`. Checked [`README.md`](file:///data/data/com.termux/files/home/BlazeLauncher/README.md) (contains no local build/test commands).

---

## W5 — Engine `.so` Caching & Patch-Parity Proof

### Cache Design (`.github/workflows/rust-engine.yml`)
1. `cache-horizon-so`: Caches `engine/starling/target/aarch64-linux-android/release/libhorizon_android.so` keyed strictly (no `restore-keys`) on:
   `${{ runner.os }}-horizon-so-arm64-${{ steps.submodule.outputs.sha }}-${{ hashFiles('engine/patches/*.patch', 'engine/starling/Cargo.lock') }}`
2. `cache-prepare-so`: Caches `blaze-prepare/target/aarch64-linux-android/release/libblaze_prepare.so` keyed strictly (no `restore-keys`) on:
   `${{ runner.os }}-prepare-so-arm64-${{ steps.submodule.outputs.sha }}-${{ hashFiles('engine/patches/*.patch', 'blaze-prepare/Cargo.toml', 'blaze-prepare/Cargo.lock', 'blaze-prepare/src/**/*.rs') }}`
3. When both `.so` caches hit, `Setup cargo-ndk and Android NDK` and the `cargo ndk` cross-compiles are skipped while all submodule/patch checks, license guards, Rust unit tests (`horizon-prepare` & `blaze-prepare`), Python oracle checks, benchmarks, and X2 Mojang host audits still execute.

### Proof That Both Engine `.so` and APK Use the Same Patched `research-lock.json` (`0007` Included)
1. **Engine `.so` embeds `research-lock.json` at compile time**:
   - [`engine/starling/crates/horizon-client/src/official_install.rs:20`](file:///data/data/com.termux/files/home/BlazeLauncher/engine/starling/crates/horizon-client/src/official_install.rs#L20): `const LOCK: &str = include_str!("../../../research-lock.json");`
   - [`engine/starling/crates/horizon-prepare/src/lib.rs`](file:///data/data/com.termux/files/home/BlazeLauncher/engine/starling/crates/horizon-prepare/src/lib.rs) and `blaze-prepare/build.rs` compile against the workspace root `engine/starling/research-lock.json`.
2. **Engine job applies all `engine/patches/*.patch` before building `.so` and keys cache on their hashes**:
   - [`.github/workflows/rust-engine.yml:37-40`](file:///data/data/com.termux/files/home/BlazeLauncher/.github/workflows/rust-engine.yml#L37-L40): iterates `for p in engine/patches/*.patch; do ... git -C engine/starling apply "$PWD/$p"; done` (which applies `0007-pin-refresh-26.3.patch` cleanly on pinned `fa548eb7abe91c194d3780e4580b9746323cd606`).
   - [`.github/workflows/rust-engine.yml:68,75`](file:///data/data/com.termux/files/home/BlazeLauncher/.github/workflows/rust-engine.yml#L68): cache keys include `steps.submodule.outputs.sha` and `hashFiles('engine/patches/*.patch', ...)`, guaranteeing `.so` invalidation whenever any patch changes.
3. **APK build job applies `0007` and packages `assets/research-lock.json`, then verifies SHA parity**:
   - [`.github/workflows/ci.yml:48-52`](file:///data/data/com.termux/files/home/BlazeLauncher/.github/workflows/ci.yml#L48-L52): applies `engine/patches/0007-pin-refresh-26.3.patch` before Gradle runs.
   - [`ZalithLauncher/build.gradle.kts:200-204`](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/build.gradle.kts#L200-L204): `merge*Assets.doLast` copies `engine/starling/research-lock.json` into `assets/research-lock.json`.
   - [`.github/workflows/ci.yml:201-211`](file:///data/data/com.termux/files/home/BlazeLauncher/.github/workflows/ci.yml#L201-L211): `Verify Release APK Contains Pinned Patched Horizon Assets` checks exact SHA-256 of packaged `assets/research-lock.json` against the patched `engine/starling/research-lock.json`.

### Wall-Clock Measurement (Before vs After Cache)
- **Before `.so` caching** (Stage 1 commit `21af392b`, CI run [`43718712434`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43718712434)):
  - `engine / engine` (`132825771482`): **`22m 32s`** (`13:23:02Z` -> `13:45:34Z`)
  - `ci` (`132831726156`): **`14m 38s`** (`13:45:37Z` -> `14:00:15Z`)
  - Total end-to-end `CI`: **`37m 13s`**
- **After `.so` caching (miss vs hit on Stage 2 / Stage 3)**:
  - Stage 2 push populates `horizon-so-arm64` & `prepare-so-arm64` caches; Stage 3 push (non-engine change) will hit both `.so` caches and record exact `after` wall-clock here.

---

## W6 — Workflow Summary & Tests Unable to Run in CI

### Workflows Changed
- `.github/workflows/parallel-checks.yml` (added `bash -n scripts/*.sh`; expected wall-clock: ~`1m` guards, ~`5m–7m` JVM unit tests).
- `.github/workflows/rust-engine.yml` (added `horizon-prepare` unit tests + strict `.so` caching; expected wall-clock: ~`22m` on `.so` cache miss, expected ~`3m–5m` on `.so` cache hit + `ci` APK assembly ~`14m`).
- `.github/workflows/nightly.yml` & `.github/workflows/release.yml` (added `./scripts/test_guards.sh` + `bash -n`-ready chmods; expected +`<5s`).

### Tests Excluded / Unable to Run in CI (with Reasons)
1. **`ZalithLauncher/build.gradle.kts:169-172` excluded upstream unit tests**: `com/movtery/zalithlauncher/utils/file/**` and `**/VersionCompareTest*` — pre-existing upstream ZL2 test exclusions requiring Android/filesystem fixtures not available on JVM runners.
2. **Android `:jvm` `Internal-25` runtime execution** (`EngineDataPreparer` on `SM-N975F`): standard `ubuntu-latest` / `ubuntu-24.04-arm` runners cannot execute Android Bionic binaries, Binder IPC, or Android `Internal-25` `:jvm` subprocesses (`UNVERIFIED` until device test; Stage 3 tests the same Rust + exporter pipeline on desktop JDK 25).
3. **Android Vulkan / Mali GPU rendering (`:rust` `RustActivity`)**: requires physical Android GPU & SurfaceFlinger (`UNVERIFIED` in CI).
