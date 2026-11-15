# Stage Z Report — Verification Gaps Closure (`docs/STAGE_Z.md`)

> **Branch**: `feat/overnight-stages` (code commit `80c3c8c5`, built on `19ef7524`; docs follow-up appended on same branch)
> **Constraint compliance**: `arm64-v8a` only; no guard weakening; no PR merges; no Mojang assets or Mojang-derived data in repo, APK, CI artifacts, or logs; tokens/UUIDs redacted (`token_len`, `uuid_prefix`); legal notes marked *technical screening, not legal advice*.
> **CI runs cited**:
> - `CI`: [`43683847378`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43683847378) (`engine` job `132720025674`, `bench-aarch64` job `132720025422`, `ci` APK job)
> - `Parallel Fast Checks`: [`43683847329`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43683847329) (`Guards & Self-Tests` `132720025152`, `JVM Unit Tests` `132720025365`)

---

## Z1. Proof of `jdk.compiler` in Bundled `Internal-25` JRE

**Result**: **VERIFIED PRESENT** in bundled `Internal-25` archives. No JRE rebuild or ECJ bundling is required. *(On-device `SM-N975F` `:jvm` `javac` execution remains `UNVERIFIED` until physical test.)*

### How ZL2 Ships & Filters `Internal-25`
- **Registry entry**: [`ZalithLauncher/src/main/java/com/movtery/zalithlauncher/components/jre/Jre.kt:27`](../ZalithLauncher/src/main/java/com/movtery/zalithlauncher/components/jre/Jre.kt#L27):
  ```kotlin
  JRE_25("Internal-25", "runtimes/jre-25", R.string.unpack_screen_jre25, 25)
  ```
- **Packaging filter**: [`ZalithLauncher/build.gradle.kts:52,186-195`](../ZalithLauncher/build.gradle.kts#L186-L195) reads `-Darch` (`projectArch`, default `"all"`), iterates `jreList = listOf("jre-8", "jre-17", "jre-21", "jre-25")`, and removes files under `assets/runtimes/$jreVersion` that do not match `version`, `*universal*`, or `bin-$projectArch.tar.xz` (retaining `bin-arm64.tar.xz` for `-Darch=arm64`).
- **Prebuilt binary archives**: Upstream ZL2 ships pre-built `.tar.xz` archives under `ZalithLauncher/src/main/assets/runtimes/jre-25/` (`bin-arm64.tar.xz` + `universal.tar.xz`); no in-repo `jlink` script exists (`git grep -nE "jre-25|jre_25|jlink|Internal-25|JRE_25"` found only `Jre.kt`, `build.gradle.kts`, `EngineDataPreparer.kt`, and `docs/`).

### Direct Archive Inspection (`bin-arm64.tar.xz` + `universal.tar.xz`)
```text
$ tar -xOf ZalithLauncher/src/main/assets/runtimes/jre-25/bin-arm64.tar.xz ./release
IMPLEMENTOR="N/A"
JAVA_RUNTIME_VERSION="25.0.5-internal-adhoc.runner.openjdk"
JAVA_VERSION="25.0.5"
JAVA_VERSION_DATE="2026-10-20"
LIBC="default"
MODULES="java.base java.compiler java.datatransfer java.xml java.prefs java.desktop java.instrument java.logging java.management java.security.sasl java.naming java.rmi java.management.rmi java.net.http java.scripting java.security.jgss java.transaction.xa java.sql java.sql.rowset java.xml.crypto java.se jdk.accessibility jdk.charsets jdk.internal.opt jdk.zipfs jdk.compiler jdk.crypto.cryptoki jdk.crypto.ec jdk.dynalink jdk.internal.ed jdk.editpad jdk.hotspot.agent jdk.httpserver jdk.incubator.vector jdk.internal.jvmstat jdk.internal.le jdk.internal.md jdk.internal.vm.ci jdk.jdwp.agent jdk.jfr jdk.jsobject jdk.localedata jdk.management jdk.management.agent jdk.management.jfr jdk.naming.dns jdk.naming.rmi jdk.net jdk.nio.mapmode jdk.sctp jdk.security.auth jdk.security.jgss jdk.unsupported jdk.xml.dom"
OS_ARCH="aarch64"
OS_NAME="Linux"
SOURCE=".:git:2a26511bdc07+"

$ tar -tvf ZalithLauncher/src/main/assets/runtimes/jre-25/bin-arm64.tar.xz ./bin/javac
-rwxr-xr-x runner/runner  7864 2026-08-08 13:02 ./bin/javac

$ tar -tvf ZalithLauncher/src/main/assets/runtimes/jre-25/universal.tar.xz | grep -E "jdk\.compiler|javac"
drwxr-xr-x runner/runner     0 2026-08-08 13:02 legal/jdk.compiler/
lrwxrwxrwx runner/runner     0 2026-08-08 13:02 legal/jdk.compiler/ASSEMBLY_EXCEPTION -> ../java.base/ASSEMBLY_EXCEPTION
lrwxrwxrwx runner/runner     0 2026-08-08 13:02 legal/jdk.compiler/LICENSE -> ../java.base/LICENSE
lrwxrwxrwx runner/runner     0 2026-08-08 13:02 legal/jdk.compiler/ADDITIONAL_LICENSE_INFO -> ../java.base/ADDITIONAL_LICENSE_INFO
```

---

## Z2. Output Parity (`blaze-prepare` vs Upstream `horizon-prepare` + `package_resources()`)

### Manifest Fix Applied During Stage Z
Comparing `blaze-prepare` against upstream `engine/starling/crates/horizon-prepare/src/lib.rs:47,56,799-814,1262-1285` and `check.rs:11,61-68` revealed that `blaze-prepare` previously placed `"output_version": 3` inside `horizon-resources.json` and omitted the per-group SHA-256 maps (`health_files_sha256`, `motion_files_sha256`, `render_files_sha256`).
- Fixed in [`blaze-prepare/src/resources.rs:918-965,984-995`](../blaze-prepare/src/resources.rs#L918-L965): moved `output_version` strictly to `horizon-prepare.json`, added `file_sha256_map(...)`, and emitted `health_files_sha256`, `motion_files_sha256`, and `render_files_sha256`.

### Every Field / Marker Read by Engine Readiness & Launcher Checks

| Reader (`file:line`) | File / Field Checked | How `blaze-prepare` Produces It (`file:line`) |
|---|---|---|
| `horizon_client::resource_install::ready` (`engine/starling/crates/horizon-client/src/resource_install.rs:47-52`) | `<home>/.local/horizon-client/26.3/horizon-resources.json` exists (`is_file()`) | `blaze-prepare/src/resources.rs:937-955` |
| `horizon_client::official_install::ready` (`engine/starling/crates/horizon-client/src/official_install.rs:39-46`) | Calls `resource_install::ready(home)`, plus `android-downloads-v2.json` (`is_file()`), plus `audio-complete.json` (`is_file()` when `cfg!(feature = "audio")`) | `blaze-prepare/src/resources.rs:952-964` (`{"schema":2}`, `{}`) |
| `horizon_prepare::check` (`engine/starling/crates/horizon-prepare/src/check.rs:35-68`) | `horizon-resources.json`: `"schema"` (`== 7`), `"minecraft"` (`== lock.version` `"26.3"`); `horizon-prepare.json`: `"output_version"` (`== 3`) | `blaze-prepare/src/resources.rs:937-959` (`HORIZON_SCHEMA = 7`, `HORIZON_OUTPUT_VERSION = 3`) |
| `horizon_prepare::missing_extras` (`engine/starling/crates/horizon-prepare/src/check.rs:79-147`) | `assets/<BUILT_IN_PACKS>`, `assets/minecraft/textures/gui/title/background/panorama_{0..5}.png` (equal square non-1x1 PNG IHDR) + `panorama_overlay.png`, `assets/minecraft/font/unifont.zip`, `assets/pack.mcmeta`, `assets/minecraft/sounds.json` | Phase 1/2 asset & resource staging (`blaze-prepare/src/resources.rs` & `sounds`/panorama/font packagers) |
| `horizon_prepare` incremental stamp / manifest schema (`engine/starling/crates/horizon-prepare/src/lib.rs:799-814,1262-1285`) | `client_sha256`, `server_sha256`, `health_files_sha256` (4 files), `motion_files_sha256` (3 files), `collision_sha256`, `render_files_sha256` (2 files), `model_states_sha256`, `data_version`, `facts_sha256`, `block_tags_sha256`, `resource_files` (integer) | `blaze-prepare/src/resources.rs:918-951` |
| `horizon_client::mod_content` (`engine/starling/crates/horizon-client/src/mod_content.rs:2289-2312`) | Reads full `horizon-resources.json` contents plus `JSON_TABLES`, `NBT_TABLES`, and `TAG_BUNDLES` file metadata for content fingerprinting | `blaze-prepare/src/resources.rs:897-955` |
| `EngineDataPreparer.isPreparedFallback` (`ZalithLauncher/.../EngineDataPreparer.kt:681-687`) | Checks `filesDir/engine-data.zip`, `horizon-resources.json`, `android-downloads-v2.json`, `audio-complete.json` | `blaze-prepare/src/resources.rs:952-975` (`package_resources_zip`) |

### CI-Only Python Oracle Verification (Synthetic Input)
Executed in `.github/workflows/rust-engine.yml:126-268` against outputs dumped by `blaze-prepare/tests/prepare_tests.rs:827-1022` (`synthetic_parity_export_for_ci_oracle`, using synthetic non-Mojang fixtures only — covering all 61 `CLASS_RULES`, `CropBlock`, `DoublePlantBlock`, all 9 `VEGETATION_RULES`, 4 tag-backed stem/fungus/roots rules, synthetic server bundler `versions.list` + `libraries.list`, `package_resources()`, and `engine-data.zip`).

- Note on JSON key order: `blaze-prepare` serializes via `serde_json` (`BTreeMap`, lexicographically sorted keys), whereas Starling's Python oracle preserves dict insertion order and upstream `tools/prepare_output.py:71` canonicalizes with `sort_keys=True`. The CI oracle checks semantic equality AND canonical (`sort_keys=True`) SHA-256 on `survival-rules.json`, plus byte-for-byte SHA-256 on every ZIP entry in `package_resources()` and `engine-data.zip`.

Raw log output from CI Run [`43683847378`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43683847378) (job `132720025674`):
```text
Running tests/prepare_tests.rs (blaze-prepare/target/debug/deps/prepare_tests-4d07217253978dbb)
test result: ok. 4 passed; 0 failed; 0 ignored; 0 measured; 1 filtered out; finished in 0.20s
ORACLE_MARKERS_OK schema=7 output_version=3
ORACLE_SURVIVAL_OK rules=76 sha256=0093bcac1434e0da19b3f789903173d36ba3be5ae3b36a6d05e816449c8045bd
ORACLE_BUNDLER_OK libs=1
ORACLE_ZIPS_OK package_resources_entries=87 engine_data_entries=33
```

---

## Z3. 4,000-File `--release` Benchmark (`x86_64` & `aarch64`) and Report Correction

Updated [`.github/workflows/rust-engine.yml`](../.github/workflows/rust-engine.yml) to execute `cargo test --release --locked --manifest-path blaze-prepare/Cargo.toml synthetic_4000_file_benchmark -- --nocapture` alongside the Python baseline on both `ubuntu-latest` (`x86_64`) and `ubuntu-24.04-arm` (`aarch64`), and updated [`docs/STAGE_B_REPORT.md`](STAGE_B_REPORT.md) to remove the unsupported `"optimal"` and `"~19% less RSS"` claims.

Raw CI benchmark outputs (`43683847378` & `43683847329`):
```text
# x86_64 (ubuntu-latest, 43683847378 job 132720025674 & 43683847329 job 132720025365)
BENCH_RUST_4000 build_ms=71 extract_ms=1516 verify_ms=52 zip_ms=113 peak_rss_kb=7512
BENCH_PYTHON_4000 build_ms=245 extract_ms=347 verify_ms=63 zip_ms=230 peak_rss_kb=19452
BENCH_KOTLIN_4000 build_ms=57 extract_ms=162 verify_ms=81 zip_ms=114 heap_kb=52122

# aarch64 (ubuntu-24.04-arm, 43683847378 job 132720025422)
BENCH_RUST_4000 build_ms=46 extract_ms=2482 verify_ms=32 zip_ms=72 peak_rss_kb=6488
BENCH_PYTHON_4000_AARCH64 build_ms=178 extract_ms=175 verify_ms=56 zip_ms=146 peak_rss_kb=17344
```

- **Where Rust `--release` is slower**: `extract_ms` (`1,516 ms` vs Python `347 ms` / Kotlin `162 ms` on `x86_64`, and `2,482 ms` vs Python `175 ms` on `aarch64`) — caused by `extract_client_jar_resources` ([`blaze-prepare/src/resources.rs:297-313`](../blaze-prepare/src/resources.rs#L297-L313)) calling `atomic_write_bytes` ([`blaze-prepare/src/files.rs:133-155`](../blaze-prepare/src/files.rs#L133-L155)), which performs `.partial` file creation, **`file.sync_all()` (`fsync`)**, and `fs::rename()` on every single extracted file.
- **Where Rust `--release` is faster**: `build_ms`, `verify_ms`, and `zip_ms` vs CPython on both `x86_64` and `aarch64`, with `VmHWM` (`7,512 KiB` `x86_64` / `6,488 KiB` `aarch64`). Physical `SM-N975F` `--release` timings remain `UNVERIFIED`.

---

## Z4. Direct Launch Verification & Implementation (`runtime=rust`)

### Finding Before Stage Z
1. Launching a `runtime=rust` version entered [`GameLaunchFlow.kt:235-236`](../ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/GameLaunchFlow.kt#L235-L236), calling `RustLaunchDispatcher.launchRustVersion(context, version, account)`, which started `RustActivity` (`:rust` process `NativeActivity`).
2. Inside the upstream engine, [`engine/starling/crates/horizon-client/src/android/launcher.rs:681-691`](../engine/starling/crates/horizon-client/src/android/launcher.rs#L681-L691) (`impl ApplicationHandler for Launcher { fn resumed(...) { ... self.open(events) ... } }`) unconditionally called `self.open(events)` ([`launcher.rs:320-377`](../engine/starling/crates/horizon-client/src/android/launcher.rs#L320-L377)), creating the `"Horizon Android"` engine menu window rather than starting Minecraft `26.3`.

### Implementation Delivered (Compiled & Unit-Tested; On-Device Execution `UNVERIFIED`)
- **Kotlin intent & pre-engine JNI handoff**:
  - [`RustLaunchDispatcher.kt:112-133`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustLaunchDispatcher.kt#L112-L133): `launchRustVersion` uses `buildLaunchIntent(context)` -> `populateLaunchIntent(...)` which attaches `RustActivity.EXTRA_DIRECT_LAUNCH = true`.
  - [`RustActivity.kt:60-76`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustActivity.kt#L60-L76): in `onCreate` **before `super.onCreate(savedInstanceState)`** (so native state is initialized before `android_main` starts), reads `EXTRA_DIRECT_LAUNCH`, consumes the staged session, and calls `BlazeNativeBridge.setDirectLaunch(directLaunchMode)` ([`BlazeNativeBridge.kt:239-243`](../ZalithLauncher/src/main/java/dev/horizon/android/bridge/BlazeNativeBridge.kt#L239-L243)). When `directLaunchMode == true`, the diagnostic spike overlay and synthetic touch probes are skipped; opening Developer -> `"Launch Rust Edition Spike"` omits the extra and retains the menu/overlay.
- **Native engine patch**: [`engine/patches/0006-direct-launch.patch`](../engine/patches/0006-direct-launch.patch) (`311` lines; submodule `engine/starling` remains clean at `fa548eb7abe91c194d3780e4580b9746323cd606`):
  - `crates/horizon-client-render/src/auth.rs`: adds `has_pending_android()`.
  - `crates/horizon-client/src/android_main.rs`: adds `Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSetDirectLaunch` and single-shot `take_direct_launch()`.
  - `crates/horizon-client/src/android/launcher.rs`: extracts `launch_prepared_game(&mut self, events)` and, in `Launcher::resumed` when `crate::take_direct_launch()` returns `true`, requires both `horizon_client::official_install::ready(&self.home)` and `horizon_client::auth::has_pending_android()`. On success, calls `self.launch_prepared_game(events)` directly and returns without creating `"Horizon Android"`; if unprepared or missing the verified Microsoft session, blocks direct launch, logs `"BlazeDirectLaunch: falling back to engine view: ..."`, and calls `self.fail(error)` (never launching offline/unauthenticated).
  - `crates/horizon-client/src/game.rs:21186`: assigns `options.player_name = Some(session.name.clone())` when restoring `PENDING_ANDROID`.
- **Unit test coverage**: `StageDIntegrationTest.rustLaunchDispatcher_populateLaunchIntentMarksDirectLaunchAndNewTaskFlag` ([`StageDIntegrationTest.kt:173-202`](../ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/StageDIntegrationTest.kt#L173-L202)) passed in `./gradlew testDebugUnitTest`. End-to-end direct launch behavior on `SM-N975F` remains `UNVERIFIED`.

---

## Z5. Manifest-Based `26.3` Duration, Download & Disk Estimates (`UNVERIFIED`)

Added to [`docs/MORNING_TEST_STEPS.md:42-60`](MORNING_TEST_STEPS.md#L42-L60), explicitly marked as manifest-derived estimates (`UNVERIFIED` on physical `SM-N975F`):
- **Clean vanilla download total** (from Mojang `26.3` manifest + `assetIndex id=34`): `5,266` files / **`708,382,898` B (`~675.6 MiB`)** (`client.jar` `41,483,720` B, `server.jar` `62,294,556` B, `114` libraries `116,648,633` B, `assetIndex` `597,235` B, `188` non-audio assets `110,694,508` B, `4,961` audio `.ogg` `376,664,246` B). With ZL2 `<gameHome>` SHA-1 reuse, incremental download drops to `~60–115 MiB` (`UNVERIFIED`).
- **Disk usage estimate**: `~1.4–2.0 GiB` temporary peak during preparation; `~0.9–1.3 GiB` after completion (`UNVERIFIED`).
- **Expected duration on `SM-N975F`**: `~2–8 min` (`UNVERIFIED`).

---

## Z7. [`docs/UPSTREAM_QUESTION.md`](UPSTREAM_QUESTION.md) Revisions

- Removed the stray `"Wait:"` prefix (`lines 1-31` clean).
- Condensed to four short `(a)–(d)` questions covering: **(a)** building/distributing `engine-data.zip` outside APK CI builds, **(b)** on-device runtime bootstrap vs `launcher.rs:634-638`, **(c)** upstream Cargo features (`runtime-fonts`, `external-auth-only`) instead of downstream source patches `0001..0006`, and **(d)** post-`android`-branch tracking + `SCHEMA = 7` / `OUTPUT_VERSION = 3` stability *(technical screening, not legal advice)*.
- Retained **DRAFT ONLY** warning so nothing is auto-posted.

---

## Z6. Complete `git diff origin/main...HEAD -- scripts/ .github/workflows/` & Hunk Audit

Summary from `git diff --stat origin/main...HEAD -- scripts/ .github/workflows/`:
`7 files changed, 1939 insertions(+), 87 deletions(-)`, spanning **15 hunks** across 7 files.

### Hunk-by-Hunk Classification

| # | File | Hunk Header | Effect on Checks (`Adds` / `Hardens` / `Weakens?`) |
|---|---|---|---|
| 1 | `.github/workflows/ci.yml` | `@@ -15,7 +15,11 @@ permissions:` | **Adds** `engine:` reusable workflow job (`rust-engine.yml`) and gates `ci:` on `needs: engine`. Weakens? **No**. |
| 2 | `.github/workflows/ci.yml` | `@@ -36,10 +40,30 @@ jobs:` | **Adds** `scripts/test_guards.sh` execution plus `horizon-engine-arm64` artifact download & non-empty `.so` size verification (`libhorizon_android.so`, `libblaze_prepare.so`). Weakens? **No**. |
| 3 | `.github/workflows/ci.yml` | `@@ -99,29 +123,93 @@ jobs:` | **Adds & hardens**: removes fallback `assembleDebug` in favor of `compileReleaseKotlin`, fails if any debug APK exists, switches `--check-apk` to `--check-release-apk` + `apkanalyzer manifest debuggable == false`, verifies Stage B assets (`26` parity `.java` + lock files), and enforces signing cert SHA-256 `5aa533d1...a2fc`. Weakens? **No**. |
| 4 | `.github/workflows/nightly.yml` | `@@ -83,11 +83,18 @@ jobs:` | **Hardens** `Verify Signing Certificate SHA-256` to fail unless `apksigner verify --print-certs` matches `5aa533d1...a2fc`. Weakens? **No**. |
| 5 | `.github/workflows/nightly.yml` | `@@ -99,6 +106,13 @@ jobs:` | **Adds** `apkanalyzer manifest debuggable == false` enforcement and `./scripts/guard.sh --check-release-apk` on nightly APKs. Weakens? **No**. |
| 6 | `.github/workflows/parallel-checks.yml` | `@@ -0,0 +1,65 @@` | **Adds** standalone fast workflow running `ENGINE_BUILD_ENABLED=1 ./scripts/guard.sh`, `./scripts/test_guards.sh`, `./scripts/scope_guard.sh`, and `./gradlew testDebugUnitTest` + `BENCH_KOTLIN_4000`. Weakens? **No**. |
| 7 | `.github/workflows/rust-engine.yml` | `@@ -10,69 +10,398 @@ permissions:` | **Adds & hardens**: pins `engine/starling` (`fa548eb7abe91c194d3780e4580b9746323cd606`), verifies & applies `engine/patches/*.patch`, runs `ENGINE_BUILD_ENABLED=1 ./scripts/guard.sh`, restricts ABI strictly to `arm64-v8a`, runs `blaze-prepare` tests + CI-only Python parity oracle + `--release` benchmarks (`bench-aarch64` has `continue-on-error: true` as optional telemetry), and uploads `.so` artifacts. Weakens? **No**. |
| 8 | `scripts/guard.sh` | `@@ -3,6 +3,12 @@ set -euo pipefail` | **Adds** `IS_ISOLATED_MODE` flag activated only by single-guard self-test env vars/flags (`GUARD_CHECK{7,9,10,11}_ONLY_DIR`, `--check-apk`, `--check-release-apk`) so `test_guards.sh` can isolate individual guards on synthetic fixtures without skipping them in normal runs (`IS_ISOLATED_MODE=0`). Weakens? **No**. |
| 9 | `scripts/guard.sh` | `@@ -16,7 +22,7 @@ echo "✓ No keystores or password files tracked in git."` | **Modifies pathspec**: extends Check 2 `git grep` secret exclusion from `':!scripts/guard.sh'` to `':!scripts/guard.sh' ':!scripts/test_guards.sh'` so guard self-test fixtures do not self-trigger. Note: excluding a second script file technically narrows Check 2 by one tracked test file (`scripts/test_guards.sh`), while keeping full secret scanning across all app/engine/doc files (`scripts/test_guards.sh` itself contains zero secrets and is audited under Hunk 15). |
| 10 | `scripts/guard.sh` | `@@ -54,6 +60,21 @@ if git grep -E -i 'github\.run_number|GITHUB_RUN_NUMBER' -- .github/workflows/;` | **Adds** Check 5b failing hard if `engine/starling/Cargo.toml` is missing/empty when `.gitmodules` or `ENGINE_BUILD_ENABLED=1` requires it. Weakens? **No**. |
| 11 | `scripts/guard.sh` | `@@ -64,43 +85,779 @@ if [ -n "$DISALLOWED_REPO_FILES" ]; then` | **Adds & hardens**: upgrades Check 7 (`include_bytes!`/`include_str!` AST/comment-aware font scanner), adds Check 10 (engine OAuth/client-ID/disk-session neutralization verified on temp patched worktree), adds Check 9 (Developer package safety banning offline/bypass/token identifiers), adds Check 11 (Stage B zero-Python / zero-Mojang-assets hygiene), and extends Check 8 APK scan (`--check-release-apk`, binary manifest `debuggable` parser, SFNT magic detection). Weakens? **No**. |
| 12 | `scripts/guard.sh` | `@@ -122,18 +879,66 @@ with zipfile.ZipFile(apk_path, "r") as zf:` | **Hardens** Check 8 APK native library check from `*/libhorizon_android.so` only to all `lib/*/*.so` files for ASCII + UTF-16BE OFL markers, SFNT tables, and 12 Starling OAuth/login markers (masking only the benign multiplayer `authserver.ely.by/session/join` string while blocking all login endpoints), plus enforces `android:debuggable=false` under `--check-release-apk`. Weakens? **No**. |
| 13 | `scripts/scope_guard.sh` | `@@ -33,15 +33,19 @@ for file in $CHANGED_FILES; do` | **Expands allowlist** beyond Phase 0 to admit `blaze-prepare/*`, `.gitmodules`, `AndroidManifest.xml`, `assets/fonts/*`, `assets/blaze-prepare/*`, and `BlazeNativeBridge.kt` for Stages A–D. Weakens security/license guards? **No** (expands allowed file paths only; does not remove earlier restrictions). |
| 14 | `scripts/scope_guard.sh` | `@@ -50,6 +54,12 @@ for file in $CHANGED_FILES; do` | **Expands allowlist** to admit Task Q Developer Mode (`AllSettings.kt`, `AboutInfoScreen.kt`, `LauncherSettingsScreen.kt`) and Stage D runtime/launch hook files (`VersionConfig.kt`, `Version.kt`, `GameDownloadInfo.kt`, `GameInstaller.kt`, `GameLaunchFlow.kt`, `GameLauncher.kt`, `DownloadGameWithAddonScreen.kt`, `VersionsManageElements.kt`, `LauncherElements.kt`). Weakens security/license guards? **No** (admits scoped hook points per `AGENTS.md` Rule 8). |
| 15 | `scripts/test_guards.sh` | `@@ -0,0 +1,541 @@` | **Adds** `39` automated self-tests (positive + fail-on-bad cases) proving Checks 7, 8, 5b, 8b, 9, 10, and 11 enforce their invariants. Weakens? **No**. |

### Verbatim Diff Output (`git diff origin/main...HEAD -- scripts/ .github/workflows/`)

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index b80bfa6d..f6074a00 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -15,7 +15,11 @@ permissions:
   contents: read
 
 jobs:
+  engine:
+    uses: ./.github/workflows/rust-engine.yml
+
   ci:
+    needs: engine
     runs-on: ubuntu-latest
 
     env:
@@ -36,10 +40,30 @@ jobs:
 
       - name: Run Guard Scripts
         run: |
-          chmod +x scripts/guard.sh scripts/scope_guard.sh
+          chmod +x scripts/guard.sh scripts/scope_guard.sh scripts/test_guards.sh
           ./scripts/guard.sh
+          ./scripts/test_guards.sh
           ./scripts/scope_guard.sh main
 
+      - name: Download Rust Engine .so (arm64-v8a)
+        uses: actions/download-artifact@v4
+        with:
+          name: horizon-engine-arm64
+          path: ZalithLauncher/src/main/jniLibs/arm64-v8a
+
+      - name: Verify Rust Engine .so Placement
+        run: |
+          for SO_PATH in \
+            "ZalithLauncher/src/main/jniLibs/arm64-v8a/libhorizon_android.so" \
+            "ZalithLauncher/src/main/jniLibs/arm64-v8a/libblaze_prepare.so"; do
+            if [ ! -s "$SO_PATH" ]; then
+              echo "ERROR: Missing $SO_PATH after artifact download!" >&2
+              exit 1
+            fi
+            ls -lh "$SO_PATH"
+            echo "$SO_PATH bytes: $(stat -c%s "$SO_PATH")"
+          done
+
       - name: Set up JDK 21
         uses: actions/setup-java@v5
         with:
@@ -99,29 +123,93 @@ jobs:
           if [ "${{ steps.oauth_check.outputs.has_client_id }}" = "true" ]; then
             ./gradlew testDebugUnitTest assembleRelease -PversionCodeOverride="$VERSION_CODE" -PversionNameOverride="$VERSION_NAME" --stacktrace
           else
-            ./gradlew testDebugUnitTest assembleDebug -PversionCodeOverride="$VERSION_CODE" -PversionNameOverride="$VERSION_NAME" --stacktrace
+            ./gradlew testDebugUnitTest compileReleaseKotlin -PversionCodeOverride="$VERSION_CODE" -PversionNameOverride="$VERSION_NAME" --stacktrace
           fi
 
-      - name: Verify APK License Hygiene
+      - name: Verify No Debug APK Artifacts Built
         run: |
-          APK_LIST=$(find ZalithLauncher/build/outputs/apk -name "*.apk" | sort)
+          DEBUG_APKS=$(find ZalithLauncher/build/outputs/apk/debug -name "*.apk" 2>/dev/null || true)
+          if [ -n "$DEBUG_APKS" ]; then
+            echo "ERROR: Debug APKs must not be built for distribution:" >&2
+            echo "$DEBUG_APKS" >&2
+            exit 1
+          fi
+          echo "✓ Confirmed zero debug APKs exist under ZalithLauncher/build/outputs/apk/debug."
+
+      - name: Verify APK License Hygiene and Non-Debuggable Release
+        if: steps.oauth_check.outputs.has_client_id == 'true'
+        run: |
+          APK_LIST=$(find ZalithLauncher/build/outputs/apk/release -name "*.apk" | sort)
           if [ -z "$APK_LIST" ]; then
-            echo "ERROR: No APK found under ZalithLauncher/build/outputs/apk!" >&2
+            echo "ERROR: No release APK found under ZalithLauncher/build/outputs/apk/release!" >&2
             exit 1
           fi
+          APKANALYZER=$(which apkanalyzer 2>/dev/null || find $ANDROID_HOME -name apkanalyzer 2>/dev/null | sort -V | tail -n 1)
           for apk in $APK_LIST; do
-            ./scripts/guard.sh --check-apk "$apk"
+            ./scripts/guard.sh --check-release-apk "$apk"
+            if [ -n "$APKANALYZER" ] && [ -x "$APKANALYZER" ]; then
+              DBG_VAL=$("$APKANALYZER" manifest debuggable "$apk")
+              echo "apkanalyzer manifest debuggable ($apk): $DBG_VAL"
+              if [ "$DBG_VAL" != "false" ]; then
+                echo "ERROR: Distributed APK $apk is debuggable ($DBG_VAL)!" >&2
+                exit 1
+              fi
+            fi
+            python3 - "$apk" <<'PY'
+          import os, sys, zipfile
+          apk = sys.argv[1]
+          apk_bytes = os.path.getsize(apk)
+          with zipfile.ZipFile(apk, "r") as zf:
+              names = set(zf.namelist())
+              print(f"APK size report for {apk}: total={apk_bytes} bytes ({apk_bytes / (1024*1024):.2f} MiB)")
+              for req_so in ("libhorizon_android.so", "libblaze_prepare.so"):
+                  matches = [i for i in zf.infolist() if i.filename.endswith(req_so)]
+                  if not matches:
+                      print(f"ERROR: {req_so} not packaged in APK!", file=sys.stderr)
+                      sys.exit(1)
+                  for m in matches:
+                      print(
+                          f"  entry={m.filename} uncompressed={m.file_size} bytes "
+                          f"({m.file_size / (1024*1024):.2f} MiB) compressed={m.compress_size} bytes "
+                          f"({m.compress_size / (1024*1024):.2f} MiB)"
+                      )
+              for req_asset in (
+                  "assets/research-lock.json",
+                  "assets/bedrock-controls.json",
+                  "assets/blaze-prepare/PrepareBatchShim.java",
+                  "assets/horizon-parity/dev/horizonlab/parity/PrepareWorker.java",
+                  "assets/horizon-parity/dev/horizonlab/parity/ParityData.java",
+                  "assets/horizon-parity/dev/horizonlab/parity/VanillaCommandParseOracle.java",
+                  "assets/horizon-parity/dev/horizonlab/parity/VanillaCreativeTabsExporter.java",
+                  "assets/horizon-parity/dev/horizonlab/parity/VanillaBlockStateBindingOracle.java",
+              ):
+                  if req_asset not in names:
+                      print(f"ERROR: Missing required Stage B asset {req_asset} in APK!", file=sys.stderr)
+                      sys.exit(1)
+              parity_java = [n for n in names if n.startswith("assets/horizon-parity/dev/horizonlab/parity/") and n.endswith(".java")]
+              print(f"  bundled parity java sources: {len(parity_java)}")
+              if len(parity_java) != 26:
+                  print(f"ERROR: Expected 26 MIT parity .java sources in APK, found {len(parity_java)}", file=sys.stderr)
+                  sys.exit(1)
+          PY
           done
 
       - name: Verify Signing Certificate SHA-256
         if: steps.oauth_check.outputs.has_client_id == 'true'
         run: |
+          EXPECTED_SHA256="5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc"
           APKSIGNER=$(find $ANDROID_HOME/build-tools -name apksigner | sort -V | tail -n 1)
           echo "Using apksigner: $APKSIGNER"
           for apk in $(find ZalithLauncher/build/outputs/apk/release -name "*.apk"); do
             echo "======================================================="
             echo "Signing certificate verification for: $apk"
-            "$APKSIGNER" verify --print-certs "$apk"
+            CERT_OUTPUT=$("$APKSIGNER" verify --print-certs "$apk")
+            echo "$CERT_OUTPUT"
+            if ! echo "$CERT_OUTPUT" | grep -qi "$EXPECTED_SHA256"; then
+              echo "ERROR: APK certificate SHA-256 does not match expected $EXPECTED_SHA256!" >&2
+              exit 1
+            fi
+            echo "✓ Signing certificate SHA-256 matched $EXPECTED_SHA256."
             echo "======================================================="
           done
 
diff --git a/.github/workflows/nightly.yml b/.github/workflows/nightly.yml
index 33732fae..a27187c8 100644
--- a/.github/workflows/nightly.yml
+++ b/.github/workflows/nightly.yml
@@ -83,11 +83,18 @@ jobs:
 
       - name: Verify Signing Certificate SHA-256
         run: |
+          EXPECTED_SHA256="5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc"
           APKSIGNER=$(find $ANDROID_HOME/build-tools -name apksigner | sort -V | tail -n 1)
           for apk in $(find ZalithLauncher/build/outputs/apk/release -name "*.apk"); do
             echo "======================================================="
             echo "Signing certificate verification for: $apk"
-            "$APKSIGNER" verify --print-certs "$apk"
+            CERT_OUTPUT=$("$APKSIGNER" verify --print-certs "$apk")
+            echo "$CERT_OUTPUT"
+            if ! echo "$CERT_OUTPUT" | grep -qi "$EXPECTED_SHA256"; then
+              echo "ERROR: Nightly APK certificate SHA-256 does not match expected $EXPECTED_SHA256!" >&2
+              exit 1
+            fi
+            echo "✓ Signing certificate SHA-256 matched $EXPECTED_SHA256."
             echo "======================================================="
           done
 
@@ -99,6 +106,13 @@ jobs:
             echo "Inspecting: $apk"
             echo "versionCode: $("$APKANALYZER" manifest version-code "$apk")"
             echo "versionName: $("$APKANALYZER" manifest version-name "$apk")"
+            DBG_VAL=$("$APKANALYZER" manifest debuggable "$apk")
+            echo "debuggable: $DBG_VAL"
+            if [ "$DBG_VAL" != "false" ]; then
+              echo "ERROR: Nightly APK $apk must not be debuggable ($DBG_VAL)!" >&2
+              exit 1
+            fi
+            ./scripts/guard.sh --check-release-apk "$apk"
             echo "======================================================="
           done
 
diff --git a/.github/workflows/parallel-checks.yml b/.github/workflows/parallel-checks.yml
new file mode 100644
index 00000000..0fc3adb0
--- /dev/null
+++ b/.github/workflows/parallel-checks.yml
@@ -0,0 +1,65 @@
+name: Parallel Fast Checks
+
+on:
+  push:
+    branches:
+      - 'feat/**'
+      - 'main'
+  pull_request:
+    branches:
+      - 'feat/**'
+      - 'main'
+  workflow_dispatch:
+
+permissions:
+  contents: read
+
+jobs:
+  guards-and-self-tests:
+    name: Guards & Self-Tests
+    runs-on: ubuntu-latest
+    steps:
+      - name: Checkout repository
+        uses: actions/checkout@v4
+        with:
+          submodules: recursive
+          fetch-depth: 0
+
+      - name: Run Guards, Self-Tests, and Scope Guard
+        run: |
+          chmod +x scripts/guard.sh scripts/scope_guard.sh scripts/test_guards.sh
+          ENGINE_BUILD_ENABLED=1 ./scripts/guard.sh
+          ./scripts/test_guards.sh
+          BASE_BRANCH="${{ github.base_ref || 'main' }}"
+          ./scripts/scope_guard.sh "$BASE_BRANCH"
+
+  jvm-unit-tests:
+    name: JVM Unit Tests
+    runs-on: ubuntu-latest
+    steps:
+      - name: Checkout repository
+        uses: actions/checkout@v4
+
+      - name: Set up JDK 21
+        uses: actions/setup-java@v5
+        with:
+          distribution: "temurin"
+          java-version: "21"
+
+      - name: Setup Gradle
+        uses: gradle/actions/setup-gradle@v4
+        with:
+          add-job-summary: "on-failure"
+
+      - name: Run Debug JVM Unit Tests
+        run: |
+          chmod +x gradlew
+          ./gradlew testDebugUnitTest --no-daemon --stacktrace
+          python3 - <<'PY'
+          import glob, re
+          for path in sorted(glob.glob("ZalithLauncher/build/test-results/**/*.xml", recursive=True)):
+              txt = open(path, encoding="utf-8", errors="replace").read()
+              for line in txt.splitlines():
+                  if "BENCH_KOTLIN_4000" in line:
+                      print(line.strip())
+          PY
diff --git a/.github/workflows/rust-engine.yml b/.github/workflows/rust-engine.yml
index 51540991..1258fc13 100644
--- a/.github/workflows/rust-engine.yml
+++ b/.github/workflows/rust-engine.yml
@@ -10,69 +10,398 @@ permissions:
 jobs:
   engine:
     runs-on: ubuntu-latest
+    env:
+      HORIZON_TELEMETRY_URL: off
+      HORIZON_ANDROID_PUBLIC: "1"
+      HORIZON_ANDROID_BUILD_COMMIT: fa548eb7abe9
+      HORIZON_ANDROID_BUILD_DIRTY: "false"
     steps:
       - name: Checkout
         uses: actions/checkout@v4
         with:
           submodules: recursive
 
-      - name: Check if engine directory exists
-        id: engine_check
+      - name: Verify Pinned Engine Submodule & Apply Patches
         run: |
-          if [ -d "engine" ]; then
-            echo "exists=true" >> $GITHUB_OUTPUT
-          else
-            echo "exists=false" >> $GITHUB_OUTPUT
-            echo "engine/ directory not present. Skipping build."
+          if [ ! -d "engine/starling" ] || [ ! -s "engine/starling/Cargo.toml" ]; then
+            echo "ERROR: engine/starling submodule is missing or empty!" >&2
+            exit 1
+          fi
+          ACTUAL_SHA=$(git -C engine/starling rev-parse HEAD)
+          EXPECTED_SHA="fa548eb7abe91c194d3780e4580b9746323cd606"
+          if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
+            echo "ERROR: engine/starling HEAD ($ACTUAL_SHA) != pinned ($EXPECTED_SHA)" >&2
+            exit 1
           fi
+          for p in engine/patches/*.patch; do
+            echo "Verifying clean apply of $p on $EXPECTED_SHA..."
+            git -C engine/starling apply --check "$PWD/$p"
+            git -C engine/starling apply "$PWD/$p"
+          done
+          echo "✓ Patches applied cleanly to $EXPECTED_SHA"
+
+      - name: Run Engine License Hygiene Guards
+        run: |
+          chmod +x scripts/guard.sh
+          ENGINE_BUILD_ENABLED=1 ./scripts/guard.sh
 
-      - name: Setup Rust Toolchain
-        if: steps.engine_check.outputs.exists == 'true'
-        uses: dtolnay/rust-toolchain@stable
+      - name: Setup Rust Toolchain (arm64-v8a only)
+        uses: dtolnay/rust-toolchain@1.95.0
         with:
-          targets: aarch64-linux-android,armv7-linux-androideabi,x86_64-linux-android,i686-linux-android
+          targets: aarch64-linux-android
+
+      - name: Ensure Starling Toolchain Target (arm64-v8a)
+        run: |
+          (
+            cd engine/starling
+            rustup show
+            rustup target add aarch64-linux-android
+          )
 
       - name: Cache Cargo & Target
-        if: steps.engine_check.outputs.exists == 'true'
         uses: actions/cache@v4
         with:
           path: |
             ~/.cargo/registry
             ~/.cargo/git
-            engine/target
-          key: ${{ runner.os }}-cargo-engine-${{ hashFiles('engine/**/Cargo.lock') }}
+            engine/starling/target
+            blaze-prepare/target
+          key: ${{ runner.os }}-cargo-starling-arm64-${{ hashFiles('engine/starling/Cargo.lock', 'engine/patches/*.patch', 'blaze-prepare/Cargo.lock') }}
           restore-keys: |
-            ${{ runner.os }}-cargo-engine-
+            ${{ runner.os }}-cargo-starling-arm64-
 
-      - name: Install cargo-ndk
-        if: steps.engine_check.outputs.exists == 'true'
-        run: cargo install cargo-ndk
+      - name: Setup cargo-ndk and Android NDK
+        run: |
+          if ! command -v cargo-ndk >/dev/null 2>&1; then
+            cargo install cargo-ndk --locked
+          fi
+          NDK_DIR=$(ls -d "${ANDROID_NDK_HOME:-}" "${ANDROID_SDK_ROOT:-$ANDROID_HOME}"/ndk/* 2>/dev/null | sort -V | tail -n 1 || true)
+          if [ -z "$NDK_DIR" ] || [ ! -d "$NDK_DIR" ]; then
+            yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" "ndk;27.2.12479018" >/dev/null
+            NDK_DIR="${ANDROID_HOME}/ndk/27.2.12479018"
+          fi
+          echo "Using ANDROID_NDK_HOME=$NDK_DIR"
+          echo "ANDROID_NDK_HOME=$NDK_DIR" >> "$GITHUB_ENV"
 
-      - name: Build Engine ABIs
-        if: steps.engine_check.outputs.exists == 'true'
+      - name: Build Engine (arm64-v8a) and Report Sizes
         run: |
-          cd engine
-          mkdir -p ../ZalithLauncher/src/main/jniLibs/arm64-v8a \
-                   ../ZalithLauncher/src/main/jniLibs/armeabi-v7a \
-                   ../ZalithLauncher/src/main/jniLibs/x86_64 \
-                   ../ZalithLauncher/src/main/jniLibs/x86
-
-          FAILED_TARGETS=""
-          TARGET_MAP="aarch64-linux-android:arm64-v8a armv7-linux-androideabi:armeabi-v7a x86_64-linux-android:x86_64 i686-linux-android:x86"
-
-          for pair in $TARGET_MAP; do
-            RUST_TARGET="${pair%%:*}"
-            ABI="${pair##*:}"
-            echo "=== Building $RUST_TARGET for ABI $ABI ==="
-            if cargo ndk -t "$ABI" build --release; then
-              echo "✓ Success: $RUST_TARGET"
-            else
-              echo "✗ FAILED: $RUST_TARGET"
-              FAILED_TARGETS="$FAILED_TARGETS $RUST_TARGET"
-            fi
-          done
+          echo "=== Building unoptimized/unstripped baseline (profile=fast) ==="
+          (
+            cd engine/starling
+            cargo ndk -t arm64-v8a --platform 28 build \
+              --profile fast \
+              --manifest-path Cargo.toml \
+              --package horizon-android
+          )
 
-          if [ -n "$FAILED_TARGETS" ]; then
-            echo "ERROR: The following native ABIs failed to build:$FAILED_TARGETS"
-            exit 1
-          fi
+          FAST_SO="engine/starling/target/aarch64-linux-android/fast/libhorizon_android.so"
+          BEFORE_BYTES=$(stat -c%s "$FAST_SO")
+
+          echo "=== Building optimized release (lto=fat, codegen-units=1, panic=abort, strip=true, opt-level=3) ==="
+          (
+            cd engine/starling
+            cargo ndk -t arm64-v8a --platform 28 build \
+              --release \
+              --manifest-path Cargo.toml \
+              --package horizon-android
+          )
+
+          REL_SO="engine/starling/target/aarch64-linux-android/release/libhorizon_android.so"
+          AFTER_BYTES=$(stat -c%s "$REL_SO")
+
+          echo "=== Native Library Size Report (arm64-v8a) ==="
+          ls -lh "$FAST_SO" "$REL_SO"
+          echo "libhorizon_android.so before (fast, unstripped): $BEFORE_BYTES bytes"
+          echo "libhorizon_android.so after (release, lto=fat, panic=abort, strip=true): $AFTER_BYTES bytes"
+
+          mkdir -p ZalithLauncher/src/main/jniLibs/arm64-v8a
+          cp "$REL_SO" ZalithLauncher/src/main/jniLibs/arm64-v8a/libhorizon_android.so
+
+          echo "=== Testing blaze-prepare (unit + synthetic oracle dump) ==="
+          ORACLE_DUMP=$(mktemp -d "${RUNNER_TEMP:-/tmp}/blaze-oracle-XXXXXX")
+          trap 'rm -rf "$ORACLE_DUMP"' EXIT
+          BLAZE_ORACLE_DUMP_DIR="$ORACLE_DUMP" \
+            cargo test --locked --manifest-path blaze-prepare/Cargo.toml -- --skip synthetic_4000_file_benchmark --nocapture
+
+          echo "=== Verifying blaze-prepare output parity with CI Python Oracle (synthetic input) ==="
+          python3 - "$ORACLE_DUMP" "$PWD/engine/starling" <<'PY'
+          import ast, hashlib, importlib.util, json, os, shutil, sys, tempfile, types, zipfile
+          from pathlib import Path
+
+          dump = Path(sys.argv[1])
+          starling = Path(sys.argv[2])
+          tools = starling / "tools"
+          sys.path.insert(0, str(tools))
+
+          import lab
+          import android_prepare
+          import android_build
+
+          def sha256_bytes(data: bytes) -> str:
+              return hashlib.sha256(data).hexdigest()
+
+          def sha256_file(path: Path) -> str:
+              return sha256_bytes(path.read_bytes())
+
+          # 1. Verify marker files and horizon-resources.json / horizon-prepare.json schema fields
+          res_dir = dump / "outputs" / "resources"
+          hr_raw = (res_dir / "horizon-resources.json").read_bytes()
+          hr = json.loads(hr_raw)
+          assert hr["schema"] == 7, f"Expected schema=7, got {hr.get('schema')}"
+          assert "output_version" not in hr, "output_version belongs only in horizon-prepare.json"
+          assert hr["minecraft"] == "26.3", hr.get("minecraft")
+          assert isinstance(hr["data_version"], int), hr.get("data_version")
+          for map_key, expected_names in [
+              ("health_files_sha256", ["damage-types.json", "damage-tags.json", "damage-catalog.json", "player-float-ids.json"]),
+              ("motion_files_sha256", ["movement-properties.nbt", "attributes.json", "landing-definitions.json"]),
+              ("render_files_sha256", ["render-definitions.json", "occlusion.nbt"]),
+          ]:
+              actual_map = hr.get(map_key)
+              assert isinstance(actual_map, dict) and set(actual_map.keys()) == set(expected_names), f"{map_key}: {actual_map}"
+              for fname, recorded_hash in actual_map.items():
+                  disk_hash = sha256_file(res_dir / fname)
+                  assert recorded_hash == disk_hash, f"{map_key}[{fname}] mismatch"
+          for key, fname in [
+              ("collision_sha256", "cached-collision.nbt"),
+              ("model_states_sha256", "model-states.json"),
+              ("facts_sha256", "facts.json"),
+              ("block_tags_sha256", "block-tags.json"),
+          ]:
+              assert hr[key] == sha256_file(res_dir / fname), f"{key} mismatch"
+          assert isinstance(hr.get("client_sha256"), str) and len(hr["client_sha256"]) == 64
+          assert isinstance(hr.get("server_sha256"), str) and len(hr["server_sha256"]) == 64
+          assert isinstance(hr.get("resource_files"), int) and hr["resource_files"] > 0
+
+          hp = json.loads((res_dir / "horizon-prepare.json").read_text())
+          assert hp == {"output_version": 3}, f"Unexpected horizon-prepare.json: {hp}"
+          ad = json.loads((res_dir / "android-downloads-v2.json").read_text())
+          assert ad == {"schema": 2}, f"Unexpected android-downloads-v2.json: {ad}"
+          ac = json.loads((res_dir / "audio-complete.json").read_text())
+          assert ac == {}, f"Unexpected audio-complete.json: {ac}"
+          print("ORACLE_MARKERS_OK schema=7 output_version=3")
+
+          # 2. Verify survival-rules.json byte/semantic parity against Starling bind_definitions()
+          surv_path = tools / "test_block_survival_parity.py"
+          tree = ast.parse(surv_path.read_text(encoding="utf-8"))
+          keep = []
+          for node in tree.body:
+              if isinstance(node, ast.Assign) and any(
+                  isinstance(t, ast.Name) and t.id in {"CLASS_RULES", "VEGETATION_RULES"}
+                  for t in node.targets
+              ):
+                  keep.append(node)
+              elif isinstance(node, ast.FunctionDef) and node.name == "bind_definitions":
+                  keep.append(node)
+          ns = {}
+          exec(compile(ast.Module(body=keep, type_ignores=[]), str(surv_path), "exec"), ns)
+          cls_in = json.loads((dump / "inputs" / "survival-classes.json").read_text())
+          plc_in = json.loads((dump / "inputs" / "survival-placement-classes.json").read_text())
+          sup_in = json.loads((dump / "inputs" / "survival-support-tags.json").read_text())
+          py_rules = ns["bind_definitions"](cls_in, plc_in, sup_in)
+          rs_rules = json.loads((res_dir / "worldgen/blocks/survival-rules.json").read_text())
+          assert py_rules == rs_rules, "survival-rules content diverged"
+          # tools/prepare_output.py canonicalizes JSON with sort_keys=True; serde_json uses BTreeMap sorted keys
+          py_rules_sha = sha256_bytes(json.dumps(py_rules, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode())
+          rs_rules_sha = sha256_file(res_dir / "worldgen/blocks/survival-rules.json")
+          assert py_rules_sha == rs_rules_sha, f"survival-rules sha256 mismatch: {py_rules_sha} vs {rs_rules_sha}"
+          print(f"ORACLE_SURVIVAL_OK rules={len(rs_rules)} sha256={rs_rules_sha}")
+
+          # 3. Verify server bundler extraction parity (versions.list & libraries.list + per-file sha256)
+          unpacked = dump / "outputs" / "unpacked-server"
+          with zipfile.ZipFile(dump / "inputs" / "server.jar") as sjar:
+              for line in sjar.read("META-INF/versions.list").decode().splitlines():
+                  expected_sha, _ver, inner = line.split("\t")
+                  disk = unpacked / "server-game.jar"
+                  assert disk.is_file(), f"Missing extracted server version jar: {inner}"
+                  assert sha256_file(disk) == expected_sha
+                  assert sha256_bytes(sjar.read(f"META-INF/versions/{inner}")) == expected_sha
+              lib_count = 0
+              for line in sjar.read("META-INF/libraries.list").decode().splitlines():
+                  expected_sha, _coord, inner = line.split("\t")
+                  disk = unpacked / "libraries" / inner
+                  assert disk.is_file(), f"Missing extracted server library: {inner}"
+                  assert sha256_file(disk) == expected_sha
+                  assert sha256_bytes(sjar.read(f"META-INF/libraries/{inner}")) == expected_sha
+                  lib_count += 1
+          print(f"ORACLE_BUNDLER_OK libs={lib_count}")
+
+          # 4. Verify package_resources() and engine-data.zip generated_engine_file() parity
+          with tempfile.TemporaryDirectory() as td_root:
+              fake_root = Path(td_root)
+              (fake_root / "research-lock.json").write_text((dump / "inputs" / "research-lock.json").read_text())
+              fake_res = fake_root / ".local" / "horizon-client" / "26.3"
+              shutil.copytree(res_dir, fake_res)
+              private_dir = fake_root / ".local" / "android"
+              old_ap_root, old_lab_root = android_prepare.ROOT, lab.ROOT
+              android_prepare.ROOT = fake_root
+              lab.ROOT = fake_root
+              try:
+                  android_prepare.package_resources("26.3", private_dir)
+              finally:
+                  android_prepare.ROOT = old_ap_root
+                  lab.ROOT = old_lab_root
+
+              upstream_zip = private_dir / "serve" / "26.3.zip"
+              rust_all_zip = dump / "outputs" / "packaged-all.zip"
+              with zipfile.ZipFile(upstream_zip) as uz, zipfile.ZipFile(rust_all_zip) as rz:
+                  u_entries = sorted(n for n in uz.namelist() if not n.endswith("/"))
+                  r_entries = sorted(n for n in rz.namelist() if not n.endswith("/"))
+                  assert u_entries == r_entries, f"Entry mismatch: {set(u_entries) ^ set(r_entries)}"
+                  for name in u_entries:
+                      assert sha256_bytes(uz.read(name)) == sha256_bytes(rz.read(name)), f"SHA mismatch on {name}"
+              with zipfile.ZipFile(dump / "outputs" / "packaged-with-bootstrap.zip") as bz:
+                  b_entries = sorted(n for n in bz.namelist() if not n.endswith("/"))
+                  assert set(b_entries) == set(u_entries) | {"bootstrap.json"}
+                  assert json.loads(bz.read("bootstrap.json"))["version"] == "26.3"
+
+              expected_gen = sorted(
+                  p.relative_to(res_dir).as_posix()
+                  for p in res_dir.rglob("*")
+                  if p.is_file() and android_build.generated_engine_file(p.relative_to(res_dir).as_posix())
+              )
+              with zipfile.ZipFile(dump / "outputs" / "engine-data.zip") as ez:
+                  e_entries = sorted(n for n in ez.namelist() if not n.endswith("/"))
+                  assert e_entries == expected_gen, f"engine-data.zip entries differ: {set(e_entries) ^ set(expected_gen)}"
+                  for name in e_entries:
+                      assert sha256_bytes(ez.read(name)) == sha256_file(res_dir / name), f"engine-data mismatch: {name}"
+          print(f"ORACLE_ZIPS_OK package_resources_entries={len(u_entries)} engine_data_entries={len(e_entries)}")
+          PY
+          rm -rf "$ORACLE_DUMP"
+          trap - EXIT
+
+          echo "=== Running 4,000-file Rust (--release) benchmark on x86_64 runner ==="
+          cargo test --release --locked --manifest-path blaze-prepare/Cargo.toml synthetic_4000_file_benchmark -- --nocapture
+
+          echo "=== Running 4,000-file Python baseline benchmark on runner ==="
+          python3 - <<'PY'
+          import json, os, resource, tempfile, time, zipfile, zlib
+          with tempfile.TemporaryDirectory() as td:
+              in_zip = os.path.join(td, "in.jar")
+              ext = os.path.join(td, "ext")
+              out_zip = os.path.join(td, "out.zip")
+              t0 = time.perf_counter()
+              crcs = {}
+              with zipfile.ZipFile(in_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as zf:
+                  for i in range(4000):
+                      rel = f"assets/minecraft/models/gen_{i}.json"
+                      data = f'{{"id":{i},"tag":"minecraft:stone_{i}","ok":true}}'.encode()
+                      crcs[rel] = zlib.crc32(data) & 0xFFFFFFFF
+                      zf.writestr(rel, data)
+              t1 = time.perf_counter()
+              with zipfile.ZipFile(in_zip, "r") as zf:
+                  zf.extractall(ext)
+              t2 = time.perf_counter()
+              count = 0
+              for r, _, files in os.walk(ext):
+                  for fn in files:
+                      p = os.path.join(r, fn)
+                      rel = os.path.relpath(p, ext).replace(os.sep, "/")
+                      with open(p, "rb") as fh:
+                          assert (zlib.crc32(fh.read()) & 0xFFFFFFFF) == crcs[rel]
+                      count += 1
+              assert count == 4000
+              t3 = time.perf_counter()
+              with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as zf:
+                  for r, _, files in os.walk(ext):
+                      for fn in files:
+                          p = os.path.join(r, fn)
+                          rel = os.path.relpath(p, ext).replace(os.sep, "/")
+                          zf.write(p, rel)
+              t4 = time.perf_counter()
+              rss_kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
+              print(
+                  f"BENCH_PYTHON_4000 build_ms={int((t1-t0)*1000)} extract_ms={int((t2-t1)*1000)} "
+                  f"verify_ms={int((t3-t2)*1000)} zip_ms={int((t4-t3)*1000)} peak_rss_kb={rss_kb}"
+              )
+          PY
+
+          echo "=== Building blaze-prepare (arm64-v8a fast vs release) ==="
+          (
+            cd blaze-prepare
+            cargo ndk -t arm64-v8a --platform 28 build --profile fast --locked --manifest-path Cargo.toml
+            cargo ndk -t arm64-v8a --platform 28 build --release --locked --manifest-path Cargo.toml
+          )
+          PREP_FAST_SO="blaze-prepare/target/aarch64-linux-android/fast/libblaze_prepare.so"
+          PREP_REL_SO="blaze-prepare/target/aarch64-linux-android/release/libblaze_prepare.so"
+          PREP_BEFORE_BYTES=$(stat -c%s "$PREP_FAST_SO")
+          PREP_AFTER_BYTES=$(stat -c%s "$PREP_REL_SO")
+          ls -lh "$PREP_FAST_SO" "$PREP_REL_SO"
+          echo "libblaze_prepare.so before (fast, unstripped): $PREP_BEFORE_BYTES bytes"
+          echo "libblaze_prepare.so after (release, lto=fat, panic=abort, strip=true): $PREP_AFTER_BYTES bytes"
+          cp "$PREP_REL_SO" ZalithLauncher/src/main/jniLibs/arm64-v8a/libblaze_prepare.so
+
+          {
+            echo "### Rust Engine Build (arm64-v8a)"
+            echo "- libhorizon_android.so fast/unstripped: \`$BEFORE_BYTES\` bytes"
+            echo "- libhorizon_android.so release/stripped: \`$AFTER_BYTES\` bytes"
+            echo "- libblaze_prepare.so fast/unstripped: \`$PREP_BEFORE_BYTES\` bytes"
+            echo "- libblaze_prepare.so release/stripped: \`$PREP_AFTER_BYTES\` bytes"
+          } >> "$GITHUB_STEP_SUMMARY"
+
+      - name: Upload Engine .so Artifact
+        uses: actions/upload-artifact@v4
+        with:
+          name: horizon-engine-arm64
+          path: |
+            ZalithLauncher/src/main/jniLibs/arm64-v8a/libhorizon_android.so
+            ZalithLauncher/src/main/jniLibs/arm64-v8a/libblaze_prepare.so
+          if-no-files-found: error
+
+  bench-aarch64:
+    name: blaze-prepare --release benchmark (aarch64)
+    runs-on: ubuntu-24.04-arm
+    continue-on-error: true
+    timeout-minutes: 10
+    steps:
+      - name: Checkout (no submodules needed)
+        uses: actions/checkout@v4
+        with:
+          submodules: false
+
+      - name: Setup Rust Toolchain (aarch64)
+        uses: dtolnay/rust-toolchain@1.95.0
+
+      - name: Run 4,000-file Rust (--release) and Python benchmarks (aarch64)
+        run: |
+          cargo test --release --locked --manifest-path blaze-prepare/Cargo.toml synthetic_4000_file_benchmark -- --nocapture
+          python3 - <<'PY'
+          import os, resource, tempfile, time, zipfile, zlib
+          with tempfile.TemporaryDirectory() as td:
+              in_zip = os.path.join(td, "in.jar")
+              ext = os.path.join(td, "ext")
+              out_zip = os.path.join(td, "out.zip")
+              t0 = time.perf_counter()
+              crcs = {}
+              with zipfile.ZipFile(in_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as zf:
+                  for i in range(4000):
+                      rel = f"assets/minecraft/models/gen_{i}.json"
+                      data = f'{{"id":{i},"tag":"minecraft:stone_{i}","ok":true}}'.encode()
+                      crcs[rel] = zlib.crc32(data) & 0xFFFFFFFF
+                      zf.writestr(rel, data)
+              t1 = time.perf_counter()
+              with zipfile.ZipFile(in_zip, "r") as zf:
+                  zf.extractall(ext)
+              t2 = time.perf_counter()
+              count = 0
+              for r, _, files in os.walk(ext):
+                  for fn in files:
+                      p = os.path.join(r, fn)
+                      rel = os.path.relpath(p, ext).replace(os.sep, "/")
+                      with open(p, "rb") as fh:
+                          assert (zlib.crc32(fh.read()) & 0xFFFFFFFF) == crcs[rel]
+                      count += 1
+              assert count == 4000
+              t3 = time.perf_counter()
+              with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as zf:
+                  for r, _, files in os.walk(ext):
+                      for fn in files:
+                          p = os.path.join(r, fn)
+                          rel = os.path.relpath(p, ext).replace(os.sep, "/")
+                          zf.write(p, rel)
+              t4 = time.perf_counter()
+              rss_kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
+              print(
+                  f"BENCH_PYTHON_4000_AARCH64 build_ms={int((t1-t0)*1000)} extract_ms={int((t2-t1)*1000)} "
+                  f"verify_ms={int((t3-t2)*1000)} zip_ms={int((t4-t3)*1000)} peak_rss_kb={rss_kb}"
+              )
+          PY
diff --git a/scripts/guard.sh b/scripts/guard.sh
index 02b4e352..5f6595cf 100755
--- a/scripts/guard.sh
+++ b/scripts/guard.sh
@@ -3,6 +3,12 @@ set -euo pipefail
 
 echo "=== Running Guard Checks ==="
 
+IS_ISOLATED_MODE=0
+if [ "${GUARD_CHECK7_ONLY_DIR:-}" != "" ] || [ "${GUARD_CHECK9_ONLY_DIR:-}" != "" ] || [ "${GUARD_CHECK10_ONLY_DIR:-}" != "" ] || [ "${GUARD_CHECK11_ONLY_DIR:-}" != "" ] || [ "${1:-}" = "--check-apk" ] || [ "${1:-}" = "--check-release-apk" ]; then
+    IS_ISOLATED_MODE=1
+fi
+
+if [ "$IS_ISOLATED_MODE" -eq 0 ]; then
 # 1. Keystore and password file leak check
 echo "Checking for committed keystores or passwords..."
 LEAKED_FILES=$(git ls-files | grep -E '\.(jks|keystore)$|passwords' || true)
@@ -16,7 +22,7 @@ echo "✓ No keystores or password files tracked in git."
 # 2. Hard-coded secrets pattern check
 echo "Checking for hard-coded secrets patterns..."
 SECRET_PATTERNS='BEGIN (RSA|OPENSSH|EC|DSA|PGP)? PRIVATE KEY|ghp_[A-Za-z0-9_]{36}|github_pat_[A-Za-z0-9_]{82}'
-if git grep -E -I -n "$SECRET_PATTERNS" -- ':!scripts/guard.sh'; then
+if git grep -E -I -n "$SECRET_PATTERNS" -- ':!scripts/guard.sh' ':!scripts/test_guards.sh'; then
     echo "ERROR: Hard-coded secrets pattern matched in tracked files!"
     exit 1
 fi
@@ -54,6 +60,21 @@ if git grep -E -i 'github\.run_number|GITHUB_RUN_NUMBER' -- .github/workflows/;
 fi
 echo "✓ Workflows do not reference run_number for versionCode."
 
+# 5b. Engine submodule presence check (must fail, not skip, when engine build enabled or .gitmodules declares it)
+ENGINE_REQUIRED="${ENGINE_BUILD_ENABLED:-0}"
+if [ -f ".gitmodules" ] && grep -q 'path = engine/starling' .gitmodules; then
+    ENGINE_REQUIRED=1
+fi
+if [ "$ENGINE_REQUIRED" = "1" ]; then
+    echo "Checking engine/starling submodule presence..."
+    if [ ! -d "engine/starling" ] || [ -z "$(ls -A engine/starling 2>/dev/null || true)" ] || [ ! -s "engine/starling/Cargo.toml" ]; then
+        echo "ERROR: Engine build is enabled / declared in .gitmodules, but engine/starling is missing or empty!"
+        echo "Hint: git submodule update --init --recursive"
+        exit 1
+    fi
+    echo "✓ engine/starling submodule is present and non-empty."
+fi
+
 # 6. License hygiene: forbid Mojang wallpapers/skins and unverified voicechat icons in repo (and submodule if present)
 echo "Checking for disallowed Mojang assets, wallpapers, skins, or voicechat icons..."
 DISALLOWED_PATTERN='(^|/)(wallpapers|wallpaper_templates)/|(^|/)packaging/android/assets/skin/|(^|/)voicechat-engine/|(^|/)engine/.*(steve|alex)\.png$|^ZalithLauncher/src/main/assets/(skin/|alex\.png$)|(^|/)ZalithLauncher/.*/logos/(neoforge|quilt)\.png$'
@@ -64,43 +85,779 @@ if [ -n "$DISALLOWED_REPO_FILES" ]; then
     exit 1
 fi
 echo "✓ No disallowed wallpapers, skins, or voicechat icon assets tracked in git."
+fi
 
-# 7. License hygiene: forbid include_bytes! font embedding under engine/
-if [ -d "engine" ]; then
-    echo "Checking engine/ for include_bytes! font embedding..."
-    FONT_EMBEDS=$(grep -rnE --exclude-dir=.git --exclude-dir=target 'include_bytes!\s*\([^)]*\.(ttf|otf)' engine/ || true)
-    if [ -n "$FONT_EMBEDS" ]; then
-        echo "ERROR: Found compile-time font embedding (include_bytes!) under engine/:"
-        echo "$FONT_EMBEDS"
-        exit 1
+# 7. License hygiene: forbid include_bytes!/include_str! font embedding under engine/ (single-line, multi-line, concat!/env!, or fonts/ paths)
+check_engine_font_embeds() {
+    local target_dir="$1"
+    python3 - "$target_dir" <<'PY'
+import os
+import re
+import sys
+
+root = sys.argv[1]
+if not os.path.isdir(root):
+    sys.exit(0)
+
+def strip_rust_comments(src: str) -> str:
+    out = []
+    i = 0
+    n = len(src)
+    in_str = False
+    block_depth = 0
+    while i < n:
+        ch = src[i]
+        nxt = src[i:i+2]
+        if block_depth > 0:
+            if nxt == "/*":
+                block_depth += 1
+                i += 2
+            elif nxt == "*/":
+                block_depth -= 1
+                i += 2
+                out.append(" ")
+            elif ch == "\n":
+                out.append("\n")
+                i += 1
+            else:
+                i += 1
+            continue
+        if in_str:
+            out.append(ch)
+            if ch == "\\" and i + 1 < n:
+                out.append(src[i + 1])
+                i += 2
+                continue
+            if ch == '"':
+                in_str = False
+            i += 1
+            continue
+        if nxt == "//":
+            while i < n and src[i] != "\n":
+                i += 1
+            continue
+        if nxt == "/*":
+            block_depth = 1
+            i += 2
+            continue
+        if ch == '"':
+            in_str = True
+        out.append(ch)
+        i += 1
+    return "".join(out)
+
+macro_re = re.compile(r"\binclude_(?:bytes|str)\s*!\s*\(")
+str_lit_re = re.compile(r'"((?:\\.|[^"\\])*)"')
+env_call_re = re.compile(r'\b(?:option_)?env\s*!\s*\(\s*"[^"]*"\s*\)')
+
+violations = []
+
+for dirpath, dirnames, filenames in os.walk(root):
+    dirnames[:] = [d for d in dirnames if d not in (".git", "target", ".local")]
+    for fname in sorted(filenames):
+        if not fname.endswith(".rs"):
+            continue
+        fpath = os.path.join(dirpath, fname)
+        try:
+            with open(fpath, "r", encoding="utf-8", errors="replace") as f:
+                raw = f.read()
+        except OSError:
+            continue
+        cleaned = strip_rust_comments(raw)
+        for m in macro_re.finditer(cleaned):
+            depth = 0
+            end = len(cleaned)
+            in_s = False
+            i = m.end() - 1
+            while i < len(cleaned):
+                c = cleaned[i]
+                if in_s:
+                    if c == "\\":
+                        i += 2
+                        continue
+                    if c == '"':
+                        in_s = False
+                else:
+                    if c == '"':
+                        in_s = True
+                    elif c == "(":
+                        depth += 1
+                    elif c == ")":
+                        depth -= 1
+                        if depth == 0:
+                            end = i + 1
+                            break
+                i += 1
+            span = cleaned[m.start():end]
+            # Replace env!("VAR") so var names aren't confused with path segments
+            span_no_env = env_call_re.sub('"<ENV>"', span)
+            parts = str_lit_re.findall(span_no_env)
+            resolved = "".join(parts)
+            line_no = cleaned[:m.start()].count("\n") + 1
+            if re.search(r"\.(ttf|otf)\b", resolved, re.IGNORECASE) or re.search(r"(^|[\\/])fonts([\\/]|$)", resolved, re.IGNORECASE):
+                compact = " ".join(span.split())
+                violations.append(f"{fpath}:{line_no}: resolved={resolved!r} macro={compact}")
+
+if violations:
+    print("ERROR: Found forbidden font/fonts/ compile-time include_bytes!/include_str!:", file=sys.stderr)
+    for v in violations:
+        print(f"  {v}", file=sys.stderr)
+    sys.exit(1)
+PY
+}
+
+if [ "${GUARD_CHECK7_ONLY_DIR:-}" != "" ]; then
+    echo "Checking custom directory for include_bytes!/include_str! font embedding: $GUARD_CHECK7_ONLY_DIR"
+    check_engine_font_embeds "$GUARD_CHECK7_ONLY_DIR"
+    echo "✓ Custom Check 7 directory passed."
+    exit 0
+fi
+
+# 10. Engine login neutralization guard (structurally parses Rust comments, raw strings, #[cfg(...)], concat!, and live call sites)
+check_engine_login_neutralized() {
+    local target_dir="$1"
+    python3 - "$target_dir" <<'PY'
+import os
+import re
+import sys
+
+root = sys.argv[1]
+if not os.path.isdir(root):
+    print(f"ERROR: Check 10 target directory missing: {root}", file=sys.stderr)
+    sys.exit(1)
+
+def skip_char_or_str(src: str, i: int):
+    """If src[i:] starts a Rust string/raw-string/byte-string/char literal, return (end_idx, kind)."""
+    n = len(src)
+    ch = src[i]
+    # Normal or byte string: "..." or b"..."
+    if ch == '"':
+        j = i + 1
+        while j < n:
+            c = src[j]
+            if c == '\\' and j + 1 < n:
+                j += 2
+                continue
+            if c == '"':
+                return j + 1, "str"
+            j += 1
+        return n, "str"
+    if ch == 'b' and i + 1 < n and src[i + 1] == '"':
+        end, _ = skip_char_or_str(src, i + 1)
+        return end, "str"
+    # Raw string: r#*"..."#* or br#*"..."#*
+    if ch == 'r' or (ch == 'b' and i + 1 < n and src[i + 1] == 'r'):
+        prev = src[i - 1] if i > 0 else ""
+        if not (prev.isalnum() or prev == '_'):
+            k = i + (2 if ch == 'b' else 1)
+            hashes = 0
+            while k < n and src[k] == '#':
+                hashes += 1
+                k += 1
+            if k < n and src[k] == '"':
+                j = k + 1
+                closing = '"' + ('#' * hashes)
+                pos = src.find(closing, j)
+                return (n if pos == -1 else pos + len(closing)), "raw_str"
+    # Char/byte literal: 'x', '\n', '\x7f', '\u{...}', b'x' (distinguish from 'lifetime)
+    if ch == "'" or (ch == 'b' and i + 1 < n and src[i + 1] == "'"):
+        q = i + (1 if ch == 'b' else 0)
+        if q + 2 < n and src[q + 1] == '\\':
+            j = q + 2
+            if j < n:
+                j += 1
+                while j < n and j - q <= 12 and src[j] not in ("\n", ";"):
+                    if src[j] == "'":
+                        return j + 1, "char"
+                    j += 1
+        elif q + 2 < n and src[q + 1] not in ("\n", "'", "\\") and src[q + 2] == "'":
+            return q + 3, "char"
+    return None
+
+def strip_rust_comments_preserving_strings(src: str) -> str:
+    out = []
+    i = 0
+    n = len(src)
+    block_depth = 0
+    while i < n:
+        ch = src[i]
+        nxt = src[i:i + 2]
+        if block_depth > 0:
+            if nxt == "/*":
+                block_depth += 1
+                out.append("  ")
+                i += 2
+            elif nxt == "*/":
+                block_depth -= 1
+                out.append("  ")
+                i += 2
+            elif ch == "\n":
+                out.append("\n")
+                i += 1
+            else:
+                out.append(" ")
+                i += 1
+            continue
+        lit = skip_char_or_str(src, i)
+        if lit is not None:
+            end, _ = lit
+            out.append(src[i:end])
+            i = end
+            continue
+        if nxt == "//":
+            while i < n and src[i] != "\n":
+                out.append(" ")
+                i += 1
+            continue
+        if nxt == "/*":
+            block_depth = 1
+            out.append("  ")
+            i += 2
+            continue
+        out.append(ch)
+        i += 1
+    return "".join(out)
+
+def find_matching_delim(src: str, open_idx: int, open_c: str, close_c: str) -> int:
+    depth = 0
+    i = open_idx
+    n = len(src)
+    while i < n:
+        lit = skip_char_or_str(src, i)
+        if lit is not None:
+            i = lit[0]
+            continue
+        c = src[i]
+        if c == open_c:
+            depth += 1
+        elif c == close_c:
+            depth -= 1
+            if depth == 0:
+                return i + 1
+        i += 1
+    return n
+
+DISABLED_CFG_RE = re.compile(
+    r'^\#\[\s*cfg\s*\(\s*(?:test|not\s*\(\s*target_os\s*=\s*"android"\s*\))\s*\)\s*\]$'
+)
+CFG_MOD_DECL_RE = re.compile(r'\bmod\s+([A-Za-z_][A-Za-z0-9_]*)\s*;')
+ATTR_PATH_RE = re.compile(r'\#\[\s*path\s*=\s*"([^"]+)"\s*\]')
+
+def prune_non_android_cfg_items(src: str, fpath: str):
+    """Blank out items guarded by #[cfg(test)] or #[cfg(not(target_os = "android"))] while tracking disabled external mods."""
+    disabled_mods = set()
+    chars = list(src)
+    i = 0
+    n = len(src)
+    while i < n:
+        lit = skip_char_or_str(src, i)
+        if lit is not None:
+            i = lit[0]
+            continue
+        if src[i:i + 2] == "#[":
+            attr_end = find_matching_delim(src, i, "[", "]")
+            attr_norm = " ".join(src[i:attr_end].split())
+            if DISABLED_CFG_RE.match(attr_norm):
+                j = attr_end
+                # Consume any stacked attributes (#[derive(...)], #[path = ...], #[allow(...)])
+                while j < n:
+                    while j < n and src[j].isspace():
+                        j += 1
+                    if src[j:j + 2] == "#[":
+                        j = find_matching_delim(src, j, "[", "]")
+                        continue
+                    break
+                # Advance to end of the annotated item (; at depth 0, or matching {...})
+                paren = 0
+                bracket = 0
+                item_end = n
+                while j < n:
+                    lit2 = skip_char_or_str(src, j)
+                    if lit2 is not None:
+                        j = lit2[0]
+                        continue
+                    c = src[j]
+                    if c == '(':
+                        paren += 1
+                    elif c == ')' and paren > 0:
+                        paren -= 1
+                    elif c == '[':
+                        bracket += 1
+                    elif c == ']' and bracket > 0:
+                        bracket -= 1
+                    elif paren == 0 and bracket == 0:
+                        if c == '{':
+                            j = find_matching_delim(src, j, "{", "}")
+                            k = j
+                            while k < n and src[k].isspace():
+                                k += 1
+                            if src[k:k + 4] == "else" and (k + 4 == n or not (src[k + 4].isalnum() or src[k + 4] == '_')):
+                                j = k + 4
+                                continue
+                            if k < n and src[k] == ';':
+                                j = k + 1
+                            item_end = j
+                            break
+                        if c == ';':
+                            item_end = j + 1
+                            break
+                    j += 1
+                removed = src[i:item_end]
+                if "mod " in removed and "{" not in removed:
+                    pm = ATTR_PATH_RE.search(removed)
+                    mm = CFG_MOD_DECL_RE.search(removed)
+                    base = os.path.dirname(fpath)
+                    if pm:
+                        disabled_mods.add(os.path.normpath(os.path.join(base, pm.group(1))))
+                    elif mm:
+                        mod_name = mm.group(1)
+                        stem = os.path.splitext(os.path.basename(fpath))[0]
+                        if stem in ("lib", "main", "mod"):
+                            disabled_mods.add(os.path.normpath(os.path.join(base, f"{mod_name}.rs")))
+                            disabled_mods.add(os.path.normpath(os.path.join(base, mod_name, "mod.rs")))
+                        else:
+                            disabled_mods.add(os.path.normpath(os.path.join(base, stem, f"{mod_name}.rs")))
+                for pos in range(i, item_end):
+                    if chars[pos] != "\n":
+                        chars[pos] = " "
+                i = item_end
+                continue
+        i += 1
+    return "".join(chars), disabled_mods
+
+CONCAT_RE = re.compile(r'\bconcat\s*!\s*\(')
+STR_TOKEN_RE = re.compile(r'"((?:\\.|[^"\\])*)"')
+
+def fold_concat_strings(src: str) -> list[str]:
+    folded = []
+    for m in CONCAT_RE.finditer(src):
+        end = find_matching_delim(src, m.end() - 1, "(", ")")
+        inner = src[m.end():end - 1]
+        parts = STR_TOKEN_RE.findall(inner)
+        if parts:
+            folded.append("".join(parts))
+    return folded
+
+def blank_string_contents(src: str) -> str:
+    out = []
+    i = 0
+    n = len(src)
+    while i < n:
+        lit = skip_char_or_str(src, i)
+        if lit is not None:
+            end, kind = lit
+            newlines = src[i:end].count("\n")
+            if kind in ("str", "raw_str"):
+                out.append('""' + ("\n" * newlines))
+            else:
+                out.append("' '" + ("\n" * newlines))
+            i = end
+            continue
+        out.append(src[i])
+        i += 1
+    return "".join(out)
+
+FORBIDDEN_CLIENT_IDS = ("00000000441cc96b", "00000000402b5328")
+FORBIDDEN_ENDPOINTS_RE = re.compile(
+    r"(login\.microsoftonline\.com"
+    r"|login\.live\.com"
+    r"|oauth20_(?:token|connect|remoteconnect)\.srf"
+    r"|user\.auth\.xboxlive\.com"
+    r"|xsts\.auth\.xboxlive\.com"
+    r"|/authentication/login_with_xbox"
+    r"|/entitlements/mcstore"
+    r"|authserver\.ely\.by(?!/session/join))",
+    re.IGNORECASE,
+)
+FORBIDDEN_CALLS_RE = re.compile(
+    r"\b(?:account\s*::\s*(?:device_code|poll|finish_sign_in|finish_sign_in_with_progress|refresh_ms|write_session_file|legacy_client|session\s*::\s*(?:selected_session|selected_session_with|refresh|refresh_with_progress|renew_for_game)|ely\s*::\s*(?:authenticate|refresh))"
+    r"|SignIn\s*::\s*start"
+    r"|Endpoints\s*::\s*live)\b"
+)
+
+DESKTOP_ONLY_REL = {
+    "launcher/src/backend.rs",
+    "launcher/src/main.rs",
+    "launcher/src/cli.rs",
+    "launcher/src/mcp.rs",
+    "launcher/src/owner_page.rs",
+    "launcher/src/switch_page.rs",
+}
+
+all_rs_files = []
+for dirpath, dirnames, filenames in os.walk(root):
+    dirnames[:] = sorted(d for d in dirnames if d not in (".git", "target", ".local", "patches"))
+    for fname in sorted(filenames):
+        if fname.endswith(".rs"):
+            all_rs_files.append(os.path.normpath(os.path.join(dirpath, fname)))
+
+# Two-pass scan so external #[cfg(test)] mod <name>; targets are collected before reporting.
+parsed = []
+disabled_external = set()
+for fpath in all_rs_files:
+    try:
+        with open(fpath, "r", encoding="utf-8", errors="replace") as f:
+            raw = f.read()
+    except OSError:
+        continue
+    no_comments = strip_rust_comments_preserving_strings(raw)
+    android_src, mods = prune_non_android_cfg_items(no_comments, fpath)
+    disabled_external.update(mods)
+    parsed.append((fpath, raw, no_comments, android_src))
+
+violations = []
+seen_rel = set()
+is_real_workspace = os.path.isfile(os.path.join(root, "Cargo.toml")) and os.path.isdir(os.path.join(root, "crates/horizon-client"))
+
+for fpath, raw, _no_comments, android_src in parsed:
+    if os.path.normpath(fpath) in disabled_external:
+        continue
+    rel = os.path.relpath(fpath, root).replace(os.sep, "/")
+    desktop_rel = rel[len("starling/"):] if (not is_real_workspace and rel.startswith("starling/")) else rel
+    if desktop_rel in DESKTOP_ONLY_REL or "/tests/" in f"/{rel}":
+        continue
+    seen_rel.add(rel)
+
+    folded = "\n".join(fold_concat_strings(android_src))
+    check_text = android_src + ("\n" + folded if folded else "")
+    code_only = blank_string_contents(android_src)
+
+    for cid in FORBIDDEN_CLIENT_IDS:
+        m = re.search(re.escape(cid), check_text)
+        if m:
+            line_no = check_text[:m.start()].count("\n") + 1
+            violations.append(f"{rel}:{line_no}: embedded Starling/Mojang client ID {cid!r} active in Android build")
+
+    for m in FORBIDDEN_ENDPOINTS_RE.finditer(check_text):
+        line_no = check_text[:m.start()].count("\n") + 1
+        violations.append(f"{rel}:{line_no}: active OAuth/Xbox/Ely login endpoint {m.group(0)!r} in Android build")
+
+    for m in FORBIDDEN_CALLS_RE.finditer(code_only):
+        line_no = code_only[:m.start()].count("\n") + 1
+        violations.append(f"{rel}:{line_no}: active engine login/refresh call {m.group(0)!r} in Android build")
+
+    if "/android/" in f"/{rel}" or rel.endswith(("android_main.rs", "blaze_diagnostics.rs", "jni_bridge.rs")):
+        m = re.search(r"\bAccounts\s*::\s*load\b", code_only)
+        if m:
+            line_no = code_only[:m.start()].count("\n") + 1
+            violations.append(f"{rel}:{line_no}: active Accounts::load call in Android engine module")
+
+    if rel.endswith("blaze_diagnostics.rs"):
+        for disk_pat in (r"\.save\s*\(", r"\bfs\s*::\s*write\b", r"File\s*::\s*(?:create|options)"):
+            m = re.search(disk_pat, code_only)
+            if m:
+                line_no = code_only[:m.start()].count("\n") + 1
+                violations.append(f"{rel}:{line_no}: setSession/diagnostics must not write session data to disk ({m.group(0)!r})")
+
+    for m in re.finditer(r"BlazeSessionHandoff[^;\n]*\{:032x\}|\buuid=\{:032x\}", check_text):
+        line_no = check_text[:m.start()].count("\n") + 1
+        violations.append(f"{rel}:{line_no}: BlazeSessionHandoff log must only log first 8 UUID chars (uuid_prefix), not full {{:032x}}")
+
+    if rel.endswith("android/catalog.rs"):
+        if not re.search(r'pub\s+const\s+MICROSOFT_CLIENT_ID\s*:\s*&str\s*=\s*""\s*;', android_src):
+            violations.append(f"{rel}: MICROSOFT_CLIENT_ID is not cleared to empty string")
+        m = re.search(r'"client_id"', android_src)
+        if m:
+            line_no = android_src[:m.start()].count("\n") + 1
+            violations.append(f"{rel}:{line_no}: editable client_id field must be removed")
+
+    if rel.endswith("android/platform.rs"):
+        m = re.search(r"pub\s+fn\s+open_login_url\b[^{]*\{(.*?)\n\}", android_src, re.DOTALL)
+        if not m or "startActivity" in m.group(1) or "Url::parse" in m.group(1):
+            violations.append(f"{rel}: open_login_url must immediately return Err without launching browser")
+
+    if rel.endswith("android/login.rs"):
+        if re.search(r"\buse\s+crate::account\b", code_only):
+            violations.append(f"{rel}: android/login.rs still imports crate::account")
+
+if is_real_workspace:
+    for required in (
+        "crates/horizon-client/src/android/catalog.rs",
+        "crates/horizon-client/src/android/login.rs",
+        "crates/horizon-client/src/android/launcher.rs",
+        "launcher/src/account.rs",
+    ):
+        if required not in seen_rel:
+            violations.append(f"{required}: expected engine file missing during Check 10 scan")
+
+if violations:
+    print("ERROR: Engine login neutralization guard failed:", file=sys.stderr)
+    for v in violations:
+        print(f"  {v}", file=sys.stderr)
+    sys.exit(1)
+PY
+}
+
+if [ "${GUARD_CHECK10_ONLY_DIR:-}" != "" ]; then
+    echo "Checking custom directory for engine login neutralization: $GUARD_CHECK10_ONLY_DIR"
+    check_engine_login_neutralized "$GUARD_CHECK10_ONLY_DIR"
+    echo "✓ Custom Check 10 directory passed."
+    exit 0
+fi
+
+if [ "$IS_ISOLATED_MODE" -eq 0 ] && [ -d "engine" ]; then
+    echo "Checking engine/ for include_bytes!/include_str! font embedding and login neutralization..."
+    TEMP_PATCHED_WORKTREE=""
+    cleanup_wt() {
+        if [ -n "$TEMP_PATCHED_WORKTREE" ] && [ -d "$TEMP_PATCHED_WORKTREE" ]; then
+            git -C engine/starling worktree remove --force "$TEMP_PATCHED_WORKTREE" >/dev/null 2>&1 || rm -rf "$TEMP_PATCHED_WORKTREE"
+        fi
+    }
+    trap cleanup_wt EXIT
+
+    run_engine_tree_checks() {
+        local starling_root="$1"
+        check_engine_font_embeds "$starling_root"
+        check_engine_login_neutralized "$starling_root"
+        for item in engine/*; do
+            if [ "$item" != "engine/starling" ] && [ -d "$item" ]; then
+                check_engine_font_embeds "$item"
+                check_engine_login_neutralized "$item"
+            fi
+        done
+    }
+
+    # Scan patched starling tree (clean submodule -> temp worktree; dirty/CI in-tree -> engine/starling) plus any sibling dirs
+    if [ -d "engine/starling/.git" ] || [ -f "engine/starling/.git" ]; then
+        if [ -z "$(git -C engine/starling status --porcelain)" ] && ls engine/patches/*.patch >/dev/null 2>&1; then
+            # Submodule working tree is clean upstream pin; verify patches apply cleanly and scan the patched view
+            TEMP_PATCHED_WORKTREE=$(mktemp -d)
+            git -C engine/starling worktree add --no-checkout --detach "$TEMP_PATCHED_WORKTREE" HEAD >/dev/null 2>&1
+            # Query tracked paths from main submodule index (--no-checkout worktree has an empty index)
+            {
+                git -C engine/starling ls-files 'Cargo.toml' '*.rs'
+                grep -h '^--- a/' engine/patches/*.patch | sed 's|^--- a/||'
+            } | sort -u | xargs -r git -C "$TEMP_PATCHED_WORKTREE" checkout HEAD --
+            for p in engine/patches/*.patch; do
+                p_abs="$(pwd)/$p"
+                git -C "$TEMP_PATCHED_WORKTREE" apply --check "$p_abs"
+                git -C "$TEMP_PATCHED_WORKTREE" apply "$p_abs"
+            done
+            run_engine_tree_checks "$TEMP_PATCHED_WORKTREE"
+        else
+            # Submodule already patched in-tree (CI build state) or no patches: scan engine/starling explicitly
+            run_engine_tree_checks "engine/starling"
+        fi
+    elif [ -d "engine/starling" ]; then
+        run_engine_tree_checks "engine/starling"
+    else
+        check_engine_font_embeds "engine"
+        check_engine_login_neutralized "engine"
     fi
-    echo "✓ No include_bytes! font embedding found under engine/."
+    echo "✓ No forbidden include_bytes!/include_str! font embedding found under engine/."
+    echo "✓ Engine login/OAuth paths & embedded client IDs verified neutralized."
 fi
 
-# 8. Optional APK inspection (--check-apk <apk_path>)
-if [ "${1:-}" = "--check-apk" ]; then
+# 9. Developer package safety guard (must never reference offline-account creation, ownership bypass, or token fields)
+check_developer_pkg_safety() {
+    local dev_dir="$1"
+    if [ ! -d "$dev_dir" ]; then
+        echo "ERROR: Developer package directory missing: $dev_dir" >&2
+        return 1
+    fi
+    local forbidden_re='(localLogin|AccountType\.LOCAL|OfflineYggdrasilServer|OfflinePlayer:|saveAccount|addOtherServer|circumventLimit|verifyGameOwnership|com\.movtery\.zalithlauncher\.game\.account|accessToken|refreshToken|access_token|refresh_token|id_token|microsoftRefreshToken|xboxToken|client_secret|MinecraftAccount|AccountsManager|offlineAccount|bypassOwnership|skipOwnership)'
+    local hits
+    hits=$(grep -rnE "$forbidden_re" "$dev_dir" || true)
+    if [ -n "$hits" ]; then
+        echo "ERROR: Forbidden account/ownership/token reference found in developer package ($dev_dir):" >&2
+        echo "$hits" >&2
+        return 1
+    fi
+    echo "✓ Developer package safety check passed ($dev_dir)."
+}
+
+if [ "${GUARD_CHECK9_ONLY_DIR:-}" != "" ]; then
+    echo "Checking custom directory for developer package safety: $GUARD_CHECK9_ONLY_DIR"
+    check_developer_pkg_safety "$GUARD_CHECK9_ONLY_DIR"
+    exit 0
+fi
+
+DEV_PKG_DIR="ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/developer"
+if [ "$IS_ISOLATED_MODE" -eq 0 ] && [ -d "$DEV_PKG_DIR" ]; then
+    echo "Checking developer package safety: $DEV_PKG_DIR..."
+    check_developer_pkg_safety "$DEV_PKG_DIR"
+fi
+
+# 7b. Check 11: Stage B pure-Rust preparer & bundled asset hygiene (zero Python, zero Mojang assets/derived tables)
+check_stage_b_hygiene() {
+    local target_root="${1:-.}"
+    python3 - "$target_root" <<'PY'
+import os
+import re
+import sys
+
+root = sys.argv[1]
+violations = []
+
+for sub in ("blaze-prepare", os.path.join("ZalithLauncher", "src", "main", "assets")):
+    scan_dir = os.path.join(root, sub)
+    if not os.path.isdir(scan_dir):
+        continue
+    for dirpath, dirnames, filenames in os.walk(scan_dir):
+        dirnames[:] = sorted(d for d in dirnames if d not in (".git", "target"))
+        for fname in sorted(filenames):
+            fpath = os.path.join(dirpath, fname)
+            rel = os.path.relpath(fpath, root).replace(os.sep, "/")
+            lower = rel.lower()
+            if lower.endswith((".py", ".pyc", ".nbt")):
+                violations.append(f"{rel}: forbidden Python or NBT file in Stage B tree")
+            if fname in ("engine-data.zip", "horizon-resources.json", "android-downloads-v2.json", "audio-complete.json"):
+                violations.append(f"{rel}: forbidden generated engine-data file committed in repo")
+            if sub == "blaze-prepare" and lower.endswith(".jar"):
+                violations.append(f"{rel}: forbidden JAR in blaze-prepare crate")
+            if "/horizon-parity/" in f"/{rel}" or "/blaze-prepare/" in f"/{rel}":
+                if sub.endswith("assets") and not lower.endswith(".java"):
+                    violations.append(f"{rel}: only .java sources may exist under assets/blaze-prepare or assets/horizon-parity")
+            if re.search(r"/assets/(?:minecraft|worldgen|\.local)(?:/|$)", f"/{lower}"):
+                violations.append(f"{rel}: forbidden Mojang assets/worldgen/.local directory under assets/")
+
+if violations:
+    print("ERROR: Stage B engine-data preparer hygiene guard failed:", file=sys.stderr)
+    for v in violations:
+        print(f"  {v}", file=sys.stderr)
+    sys.exit(1)
+PY
+}
+
+if [ "${GUARD_CHECK11_ONLY_DIR:-}" != "" ]; then
+    echo "Checking custom directory for Stage B preparer hygiene: $GUARD_CHECK11_ONLY_DIR"
+    check_stage_b_hygiene "$GUARD_CHECK11_ONLY_DIR"
+    echo "✓ Custom Check 11 directory passed."
+    exit 0
+fi
+
+if [ "$IS_ISOLATED_MODE" -eq 0 ]; then
+    echo "Checking Stage B preparer & bundled asset hygiene..."
+    check_stage_b_hygiene "."
+    echo "✓ Stage B preparer & bundled asset hygiene passed."
+fi
+
+# 8. Optional APK inspection (--check-apk <apk_path> | --check-release-apk <apk_path>)
+if [ "${1:-}" = "--check-apk" ] || [ "${1:-}" = "--check-release-apk" ]; then
+    CHECK_RELEASE_APK=0
+    if [ "${1:-}" = "--check-release-apk" ]; then
+        CHECK_RELEASE_APK=1
+    fi
     APK_PATH="${2:-}"
     if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
-        echo "ERROR: --check-apk requires a valid APK file path (got: '$APK_PATH')"
+        echo "ERROR: ${1:-} requires a valid APK file path (got: '$APK_PATH')"
         exit 1
     fi
-    echo "Checking APK license hygiene: $APK_PATH"
-    python3 - "$APK_PATH" <<'PY'
+    echo "Checking APK license hygiene: $APK_PATH (release_check=$CHECK_RELEASE_APK)"
+    python3 - "$APK_PATH" "$CHECK_RELEASE_APK" <<'PY'
 import re
+import struct
 import sys
 import zipfile
 
 apk_path = sys.argv[1]
+check_release = sys.argv[2] == "1"
 disallowed_re = re.compile(
-    r"(^|/)(wallpapers|wallpaper_templates)/|(^|/)assets/skin/|(^|/)assets/alex\.png$|voicechat",
+    r"(^|/)(wallpapers|wallpaper_templates)/|(^|/)assets/skin/|(^|/)assets/alex\.png$|voicechat"
+    r"|^assets/(?:minecraft|worldgen|\.local)/|^assets/(?:engine-data\.zip|horizon-resources\.json|android-downloads-v2\.json|audio-complete\.json)$"
+    r"|\.(?:py|pyc|nbt)$|^assets/(?:horizon-parity|blaze-prepare)/.*\.class$",
     re.IGNORECASE,
 )
-ofl_markers = [
-    b"SIL Open Font License",
-    b"The Geist Project Authors",
-    b"The Pixelify Sans Project Authors",
-    b"The Bricolage Grotesque Project Authors",
+marker_strings = [
+    "SIL Open Font License",
+    "The Geist Project Authors",
+    "The Pixelify Sans Project Authors",
+    "The Bricolage Grotesque Project Authors",
 ]
+ofl_markers = []
+for s in marker_strings:
+    ofl_markers.append(("ASCII", s, s.encode("ascii")))
+    ofl_markers.append(("UTF-16BE", s, s.encode("utf-16-be")))
+
+REQUIRED_SFNT_TABLES = {b"cmap", b"head", b"name"}
+SHAPE_SFNT_TABLES = {b"glyf", b"CFF ", b"CFF2"}
+
+def find_embedded_sfnt(data: bytes):
+    for magic in (b"\x00\x01\x00\x00", b"OTTO"):
+        start = 0
+        while True:
+            pos = data.find(magic, start)
+            if pos == -1:
+                break
+            start = pos + 1
+            if pos + 12 > len(data):
+                continue
+            num_tables = int.from_bytes(data[pos + 4 : pos + 6], "big")
+            if not (4 <= num_tables <= 64):
+                continue
+            dir_end = pos + 12 + 16 * num_tables
+            if dir_end > len(data):
+                continue
+            tags = {
+                data[pos + 12 + i * 16 : pos + 16 + i * 16]
+                for i in range(num_tables)
+            }
+            if REQUIRED_SFNT_TABLES.issubset(tags) and (tags & SHAPE_SFNT_TABLES):
+                found_names = sorted(t.decode("ascii", errors="replace") for t in (tags & (REQUIRED_SFNT_TABLES | SHAPE_SFNT_TABLES)))
+                return f"sfnt magic {magic!r} at offset {pos} with tables {found_names}"
+    return None
+
+def inspect_axml_debuggable(manifest_bytes: bytes):
+    if len(manifest_bytes) < 8:
+        raise ValueError("AndroidManifest.xml too short")
+    magic, hdr_size, _total_size = struct.unpack_from("<HHI", manifest_bytes, 0)
+    if magic != 0x0003:
+        raise ValueError(f"AndroidManifest.xml is not binary AXML (magic=0x{magic:04x})")
+    pos = hdr_size
+    strings = []
+    res_ids = []
+    seen_application = False
+    debuggable_attr = None
+    while pos + 8 <= len(manifest_bytes):
+        ctype, chdr, csize = struct.unpack_from("<HHI", manifest_bytes, pos)
+        if csize < 8 or pos + csize > len(manifest_bytes):
+            break
+        if ctype == 0x0001:  # RES_STRING_POOL_TYPE
+            scount, _stcount, flags, sstart, _ststart = struct.unpack_from("<IIIII", manifest_bytes, pos + 8)
+            utf8 = bool(flags & (1 << 8))
+            offsets = struct.unpack_from(f"<{scount}I", manifest_bytes, pos + 28)
+            base = pos + sstart
+            for off in offsets:
+                p = base + off
+                if utf8:
+                    if manifest_bytes[p] & 0x80:
+                        p += 2
+                    else:
+                        p += 1
+                    blen = manifest_bytes[p]
+                    if blen & 0x80:
+                        blen = ((blen & 0x7f) << 8) | manifest_bytes[p + 1]
+                        p += 2
+                    else:
+                        p += 1
+                    strings.append(manifest_bytes[p : p + blen].decode("utf-8", errors="replace"))
+                else:
+                    ulen = struct.unpack_from("<H", manifest_bytes, p)[0]
+                    p += 2
+                    if ulen & 0x8000:
+                        ulen = ((ulen & 0x7fff) << 16) | struct.unpack_from("<H", manifest_bytes, p)[0]
+                        p += 2
+                    strings.append(manifest_bytes[p : p + 2 * ulen].decode("utf-16-le", errors="replace"))
+        elif ctype == 0x0180:  # RES_XML_RESOURCE_MAP_TYPE
+            cnt = (csize - chdr) // 4
+            res_ids = list(struct.unpack_from(f"<{cnt}I", manifest_bytes, pos + chdr))
+        elif ctype == 0x0102:  # RES_XML_START_ELEMENT_TYPE
+            _line, _cmt, _ns, name_idx, attr_start, attr_size, attr_count = struct.unpack_from("<IIiiHHH", manifest_bytes, pos + 8)
+            tag_name = strings[name_idx] if 0 <= name_idx < len(strings) else ""
+            if tag_name == "application":
+                seen_application = True
+                attr_base = pos + chdr + attr_start
+                for i in range(attr_count):
+                    ap = attr_base + i * attr_size
+                    if ap + 20 > pos + csize:
+                        break
+                    _a_ns, a_name, _a_raw, _a_sz, _a_r0, a_type, a_data = struct.unpack_from("<iiiHBBI", manifest_bytes, ap)
+                    aname = strings[a_name] if 0 <= a_name < len(strings) else ""
+                    rid = res_ids[a_name] if 0 <= a_name < len(res_ids) else 0
+                    if aname == "debuggable" or rid == 0x0101000f:
+                        debuggable_attr = (a_type, a_data)
+        pos += csize
+    return seen_application, debuggable_attr
 
 with zipfile.ZipFile(apk_path, "r") as zf:
     names = zf.namelist()
@@ -122,18 +879,66 @@ with zipfile.ZipFile(apk_path, "r") as zf:
             print("ERROR: APK bundles assets/fonts/*.ttf|.otf without assets/fonts/OFL-*.txt!", file=sys.stderr)
             sys.exit(1)
 
-    for n in names:
-        if n.endswith("/libhorizon_android.so"):
-            data = zf.read(n)
-            for marker in ofl_markers:
-                if marker in data:
-                    print(
-                        f"ERROR: {n} contains embedded font marker {marker!r}!",
-                        file=sys.stderr,
-                    )
-                    sys.exit(1)
-
-print("✓ APK license hygiene check passed.")
+    so_entries = [n for n in names if re.match(r"^lib/[^/]+/.*\.so$", n)]
+    print(f"Scanning {len(so_entries)} shared libraries under lib/*/*.so...")
+    forbidden_so_markers = [
+        b"00000000441cc96b",
+        b"00000000402b5328",
+        b"login.microsoftonline.com",
+        b"login.live.com",
+        b"oauth20_token.srf",
+        b"oauth20_connect.srf",
+        b"oauth20_remoteconnect.srf",
+        b"user.auth.xboxlive.com",
+        b"xsts.auth.xboxlive.com",
+        b"/authentication/login_with_xbox",
+        b"/entitlements/mcstore",
+        b"authserver.ely.by",
+    ]
+    for n in so_entries:
+        data = zf.read(n)
+        for enc, text_marker, raw_marker in ofl_markers:
+            if raw_marker in data:
+                print(
+                    f"ERROR: {n} contains embedded {enc} font marker {text_marker!r}!",
+                    file=sys.stderr,
+                )
+                sys.exit(1)
+        sfnt_hit = find_embedded_sfnt(data)
+        if sfnt_hit:
+            print(
+                f"ERROR: {n} contains embedded TrueType/OpenType font data ({sfnt_hit})!",
+                file=sys.stderr,
+            )
+            sys.exit(1)
+        masked_so = data.replace(b"authserver.ely.by/session/join", b"")
+        for marker in forbidden_so_markers:
+            if marker in masked_so:
+                print(
+                    f"ERROR: {n} contains forbidden Starling OAuth/login marker {marker.decode('ascii')!r}!",
+                    file=sys.stderr,
+                )
+                sys.exit(1)
+
+    if check_release:
+        if "AndroidManifest.xml" not in names:
+            print("ERROR: Release APK check failed: missing AndroidManifest.xml!", file=sys.stderr)
+            sys.exit(1)
+        seen_app, dbg_attr = inspect_axml_debuggable(zf.read("AndroidManifest.xml"))
+        if not seen_app:
+            print("ERROR: Release APK check failed: AndroidManifest.xml missing <application> element!", file=sys.stderr)
+            sys.exit(1)
+        if dbg_attr is not None:
+            a_type, a_data = dbg_attr
+            if a_type != 0x12 or a_data != 0:
+                print(
+                    f"ERROR: APK has android:debuggable=true (or non-false attr type=0x{a_type:02x} data=0x{a_data:08x})!",
+                    file=sys.stderr,
+                )
+                sys.exit(1)
+        print("✓ Release APK manifest verified non-debuggable (android:debuggable=false).")
+
+print(f"✓ APK license hygiene check passed ({len(so_entries)} .so files scanned).")
 PY
 fi
 
diff --git a/scripts/scope_guard.sh b/scripts/scope_guard.sh
index 0e6926b3..b9c0c292 100755
--- a/scripts/scope_guard.sh
+++ b/scripts/scope_guard.sh
@@ -33,15 +33,19 @@ for file in $CHANGED_FILES; do
             ;;
         docs/*)
             ;;
-        engine/*)
+        engine/*|blaze-prepare/*|.gitmodules)
             ;;
         build.gradle.kts|settings.gradle.kts|gradle.properties|.gitignore)
             ;;
         ZalithLauncher/build.gradle.kts|ZalithLauncher/gradle.properties)
             ;;
+        ZalithLauncher/src/main/AndroidManifest.xml)
+            ;;
         ZalithLauncher/src/main/res/values*/strings.xml)
             ;;
-        ZalithLauncher/src/main/assets/THIRD_PARTY_NOTICES.md)
+        ZalithLauncher/src/main/assets/THIRD_PARTY_NOTICES.md|ZalithLauncher/src/main/assets/fonts/*|ZalithLauncher/src/main/assets/blaze-prepare/*)
+            ;;
+        ZalithLauncher/src/main/java/dev/horizon/android/bridge/BlazeNativeBridge.kt)
             ;;
         ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/*)
             ;;
@@ -50,6 +54,12 @@ for file in $CHANGED_FILES; do
         ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/account/microsoft/MicrosoftAuthenticator.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/account/AccountUtils.kt)
             # Microsoft login error logging and diagnostic enhancement
             ;;
+        ZalithLauncher/src/main/java/com/movtery/zalithlauncher/setting/AllSettings.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/screens/content/settings/AboutInfoScreen.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/screens/content/settings/LauncherSettingsScreen.kt)
+            # Developer Mode persistence, 5-tap version unlock, and diagnostics-only Settings section
+            ;;
+        ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/version/installed/VersionConfig.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/version/installed/Version.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/download/game/GameDownloadInfo.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/download/game/GameInstaller.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/GameLaunchFlow.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/GameLauncher.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/screens/content/download/game/DownloadGameWithAddonScreen.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/screens/content/elements/VersionsManageElements.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/screens/content/elements/LauncherElements.kt)
+            # Stage D runtime field, installer selector & Stage B task, Rust Edition launch dispatch, and version badge
+            ;;
         ZalithLauncher/zalith_launcher.jks|ZalithLauncher/zalith_launcher_debug.jks)
             # Removal of upstream keystores
             ;;
diff --git a/scripts/test_guards.sh b/scripts/test_guards.sh
new file mode 100755
index 00000000..dac63c4e
--- /dev/null
+++ b/scripts/test_guards.sh
@@ -0,0 +1,541 @@
+#!/usr/bin/env bash
+# Blaze Launcher — self-test suite for scripts/guard.sh
+# Copyright (C) 2026 Blaze Launcher contributors
+# SPDX-License-Identifier: GPL-3.0-or-later
+
+set -euo pipefail
+
+REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
+cd "$REPO_ROOT"
+
+TMP_ROOT="$(mktemp -d)"
+cleanup() {
+    rm -rf "$TMP_ROOT"
+}
+trap cleanup EXIT
+
+PASS_COUNT=0
+FAIL_COUNT=0
+
+expect_pass() {
+    local name="$1"
+    shift
+    echo "--- [TEST PASS] $name ---"
+    if "$@"; then
+        echo "PASS: $name"
+        PASS_COUNT=$((PASS_COUNT + 1))
+    else
+        echo "FAIL: $name (expected pass, exited non-zero)"
+        FAIL_COUNT=$((FAIL_COUNT + 1))
+    fi
+}
+
+expect_fail() {
+    local name="$1"
+    local expected_pattern="$2"
+    shift 2
+    echo "--- [TEST FAIL-ON-BAD] $name ---"
+    local out
+    set +e
+    out="$("$@" 2>&1)"
+    local rc=$?
+    set -e
+    echo "$out"
+    if [ "$rc" -eq 0 ]; then
+        echo "FAIL: $name (expected failure, got exit 0)"
+        FAIL_COUNT=$((FAIL_COUNT + 1))
+        return
+    fi
+    if ! grep -qE "$expected_pattern" <<<"$out"; then
+        echo "FAIL: $name (exited $rc, but output missed /$expected_pattern/)"
+        FAIL_COUNT=$((FAIL_COUNT + 1))
+        return
+    fi
+    echo "PASS: $name (caught with exit $rc)"
+    PASS_COUNT=$((PASS_COUNT + 1))
+}
+
+echo "=== 1. Check 7 Self-Tests (font include_bytes!/include_str!) ==="
+
+# Valid SVG concat! macro should NOT trigger false positive
+CASE_VALID="$TMP_ROOT/check7_valid"
+mkdir -p "$CASE_VALID/src"
+cat > "$CASE_VALID/src/lib.rs" <<'RS'
+const fn icon(name: &str) -> &'static [u8] { &[] }
+macro_rules! icon_svg {
+    ($name:literal) => {
+        include_bytes!(concat!("../icons/pixel/", $name, ".svg"))
+    };
+}
+RS
+expect_pass "Check 7 allows valid SVG concat! include_bytes!" \
+    env GUARD_CHECK7_ONLY_DIR="$CASE_VALID" bash scripts/guard.sh
+
+# Known-bad 1: single-line .ttf
+CASE_SINGLE="$TMP_ROOT/check7_single_ttf"
+mkdir -p "$CASE_SINGLE/src"
+cat > "$CASE_SINGLE/src/lib.rs" <<'RS'
+static BAD_FONT: &[u8] = include_bytes!("../assets/Geist-Regular.ttf");
+RS
+expect_fail "Check 7 rejects single-line .ttf include_bytes!" "resolved='.*Geist-Regular\.ttf'" \
+    env GUARD_CHECK7_ONLY_DIR="$CASE_SINGLE" bash scripts/guard.sh
+
+# Known-bad 2: single-line include_str! under fonts/
+CASE_FONTS_STR="$TMP_ROOT/check7_fonts_str"
+mkdir -p "$CASE_FONTS_STR/src"
+cat > "$CASE_FONTS_STR/src/lib.rs" <<'RS'
+static BAD_OFL: &str = include_str!("../fonts/OFL-Geist.txt");
+RS
+expect_fail "Check 7 rejects single-line fonts/ include_str!" "fonts/OFL-Geist\.txt" \
+    env GUARD_CHECK7_ONLY_DIR="$CASE_FONTS_STR" bash scripts/guard.sh
+
+# Known-bad 3: multi-line .otf include_bytes!
+CASE_MULTILINE="$TMP_ROOT/check7_multiline_otf"
+mkdir -p "$CASE_MULTILINE/src"
+cat > "$CASE_MULTILINE/src/lib.rs" <<'RS'
+static BAD_MULTI: &[u8] = include_bytes!(
+    "../assets/PixelifySans.otf"
+);
+RS
+expect_fail "Check 7 rejects multi-line .otf include_bytes!" "PixelifySans\.otf" \
+    env GUARD_CHECK7_ONLY_DIR="$CASE_MULTILINE" bash scripts/guard.sh
+
+# Known-bad 4: concat! + env! composing .ttf across pieces
+CASE_CONCAT="$TMP_ROOT/check7_concat_env"
+mkdir -p "$CASE_CONCAT/src"
+cat > "$CASE_CONCAT/src/lib.rs" <<'RS'
+static BAD_CONCAT: &[u8] = include_bytes!(concat!(
+    env!("CARGO_MANIFEST_DIR"),
+    "/ui_blobs/",
+    "PixelifySans",
+    ".t",
+    "tf"
+));
+RS
+expect_fail "Check 7 rejects concat!/env!-composed .ttf include_bytes!" "PixelifySans\.ttf" \
+    env GUARD_CHECK7_ONLY_DIR="$CASE_CONCAT" bash scripts/guard.sh
+
+# Known-bad 5: concat! composing a fonts/ directory path without .ttf extension
+CASE_CONCAT_DIR="$TMP_ROOT/check7_concat_fonts_dir"
+mkdir -p "$CASE_CONCAT_DIR/src"
+cat > "$CASE_CONCAT_DIR/src/lib.rs" <<'RS'
+static BAD_DIR: &[u8] = include_bytes!(concat!("../", "fonts", "/blob.bin"));
+RS
+expect_fail "Check 7 rejects concat!-composed fonts/ path" "\.\./fonts/blob\.bin" \
+    env GUARD_CHECK7_ONLY_DIR="$CASE_CONCAT_DIR" bash scripts/guard.sh
+
+echo ""
+echo "=== 2. Check 8 Self-Tests (--check-apk) ==="
+
+BASE_APK="$REPO_ROOT/signed-apks/BlazeLauncher-2.6_hotfix-blaze.be1defc0.apk"
+SYNTH_NEG_BASE="$TMP_ROOT/valid_neg_base.apk"
+python3 - "$SYNTH_NEG_BASE" <<'PY'
+import sys, zipfile
+with zipfile.ZipFile(sys.argv[1], "w") as z:
+    z.writestr("assets/THIRD_PARTY_NOTICES.md", b"# Notices\n")
+    z.writestr("assets/fonts/Geist-Regular.ttf", b"\x00\x01\x00\x00dummy")
+    z.writestr("assets/fonts/OFL-Geist.txt", b"SIL Open Font License")
+    z.writestr("lib/arm64-v8a/libclean.so", b"\x7fELFclean binary payload")
+PY
+
+if [ -f "$BASE_APK" ]; then
+    SYNTH_VALID_BASE="$TMP_ROOT/valid_with_notices.apk"
+    python3 - "$BASE_APK" "$SYNTH_VALID_BASE" <<'PY'
+import sys, zipfile
+src, dst = sys.argv[1], sys.argv[2]
+with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", compression=zipfile.ZIP_STORED) as zout:
+    for item in zin.infolist():
+        if item.filename.endswith(".so") or item.filename.startswith("assets/"):
+            zout.writestr(item.filename, zin.read(item.filename))
+    if "assets/THIRD_PARTY_NOTICES.md" not in zout.namelist():
+        zout.writestr("assets/THIRD_PARTY_NOTICES.md", b"# Notices\n")
+PY
+    expect_pass "Check 8 scans all existing upstream .so files cleanly when notices present" \
+        bash scripts/guard.sh --check-apk "$SYNTH_VALID_BASE"
+else
+    expect_pass "Check 8 passes on clean synthetic APK" \
+        bash scripts/guard.sh --check-apk "$SYNTH_NEG_BASE"
+fi
+
+# Helper to clone SYNTH_NEG_BASE and inject extra entries
+make_bad_apk() {
+    local out_apk="$1"
+    local entry_name="$2"
+    local mode="$3"
+    python3 - "$SYNTH_NEG_BASE" "$out_apk" "$entry_name" "$mode" <<'PY'
+import struct, sys, zipfile
+src, dst, entry_name, mode = sys.argv[1:5]
+if mode == "ascii":
+    payload = b"\x7fELF" + b"\x00" * 64 + b"Copyright (c) The Geist Project Authors" + b"\x00" * 32
+elif mode == "utf16be":
+    payload = b"\x7fELF" + b"\x00" * 64 + "SIL Open Font License".encode("utf-16-be") + b"\x00" * 32
+elif mode == "sfnt":
+    # Valid minimal SFNT TrueType header (\x00\x01\x00\x00 + 4 tables: cmap, glyf, head, name)
+    tags = [b"cmap", b"glyf", b"head", b"name"]
+    header = struct.pack(">IHHHH", 0x00010000, len(tags), 64, 2, 0)
+    records = b"".join(struct.pack(">4sIII", t, 0, 12 + 16 * len(tags) + i * 4, 4) for i, t in enumerate(tags))
+    payload = b"\x7fELF" + b"\x00" * 16 + header + records + b"\x00" * 32
+elif mode in ("missing_notices", "missing_ofl"):
+    payload = None
+else:
+    raise ValueError(mode)
+
+with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", compression=zipfile.ZIP_STORED) as zout:
+    for item in zin.infolist():
+        if mode == "missing_notices" and item.filename == "assets/THIRD_PARTY_NOTICES.md":
+            continue
+        if mode == "missing_ofl" and item.filename == "assets/fonts/OFL-Geist.txt":
+            continue
+        zout.writestr(item.filename, zin.read(item.filename))
+    if payload is not None:
+        zout.writestr(entry_name, payload)
+PY
+}
+
+BAD_ASCII_APK="$TMP_ROOT/bad_ascii.apk"
+make_bad_apk "$BAD_ASCII_APK" "lib/arm64-v8a/libhorizon_android.so" "ascii"
+expect_fail "Check 8 rejects ASCII OFL marker in lib/arm64-v8a/libhorizon_android.so" "ASCII font marker" \
+    bash scripts/guard.sh --check-apk "$BAD_ASCII_APK"
+
+BAD_UTF16_APK="$TMP_ROOT/bad_utf16.apk"
+make_bad_apk "$BAD_UTF16_APK" "lib/armeabi-v7a/libother.so" "utf16be"
+expect_fail "Check 8 rejects UTF-16BE OFL marker in any lib/*/*.so" "UTF-16BE font marker" \
+    bash scripts/guard.sh --check-apk "$BAD_UTF16_APK"
+
+BAD_SFNT_APK="$TMP_ROOT/bad_sfnt.apk"
+make_bad_apk "$BAD_SFNT_APK" "lib/arm64-v8a/libsfnt.so" "sfnt"
+expect_fail "Check 8 rejects embedded SFNT font data in any lib/*/*.so" "embedded TrueType/OpenType font data" \
+    bash scripts/guard.sh --check-apk "$BAD_SFNT_APK"
+
+BAD_NOTICES_APK="$TMP_ROOT/bad_notices.apk"
+make_bad_apk "$BAD_NOTICES_APK" "" "missing_notices"
+expect_fail "Check 8 rejects APK missing THIRD_PARTY_NOTICES.md" "missing assets/THIRD_PARTY_NOTICES.md" \
+    bash scripts/guard.sh --check-apk "$BAD_NOTICES_APK"
+
+BAD_OFL_APK="$TMP_ROOT/bad_ofl.apk"
+make_bad_apk "$BAD_OFL_APK" "" "missing_ofl"
+expect_fail "Check 8 rejects fonts/*.ttf without OFL-*.txt" "without assets/fonts/OFL-\*\.txt" \
+    bash scripts/guard.sh --check-apk "$BAD_OFL_APK"
+
+echo ""
+echo "=== 3. Check 5b Self-Test (missing/empty engine/starling must FAIL) ==="
+
+MOCK_REPO="$TMP_ROOT/mock_repo"
+mkdir -p "$MOCK_REPO/ZalithLauncher" "$MOCK_REPO/.github/workflows" "$MOCK_REPO/scripts" "$MOCK_REPO/engine/starling"
+cat > "$MOCK_REPO/LICENSE" <<'LIC'
+GNU GENERAL PUBLIC LICENSE
+Version 3, 29 June 2007
+LIC
+cat > "$MOCK_REPO/ZalithLauncher/build.gradle.kts" <<'KTS'
+android { defaultConfig { applicationId = "io.github.kaito65452.blazelauncher" } }
+KTS
+cat > "$MOCK_REPO/.gitmodules" <<'GM'
+[submodule "engine/starling"]
+	path = engine/starling
+	url = https://github.com/starling-client/starling.git
+GM
+cp scripts/guard.sh "$MOCK_REPO/scripts/guard.sh"
+(
+    cd "$MOCK_REPO"
+    git init -q
+    git config user.email "test@example.com"
+    git config user.name "Test"
+    git add LICENSE ZalithLauncher/build.gradle.kts .gitmodules scripts/guard.sh
+    git commit -q -m "mock"
+)
+expect_fail "Check 5b fails when .gitmodules declares engine/starling but dir is empty" "engine/starling is missing or empty" \
+    bash -c "cd '$MOCK_REPO' && bash scripts/guard.sh"
+
+expect_fail "Check 5b fails when ENGINE_BUILD_ENABLED=1 and engine/starling is empty" "engine/starling is missing or empty" \
+    bash -c "cd '$MOCK_REPO' && rm -f .gitmodules && ENGINE_BUILD_ENABLED=1 bash scripts/guard.sh"
+
+echo ""
+echo "=== 4. Check 8b Self-Test (--check-release-apk non-debuggable check) ==="
+
+make_manifest_apk() {
+    local out_apk="$1"
+    local mode="$2" # omitted | explicit_false | true
+    python3 - "$SYNTH_NEG_BASE" "$out_apk" "$mode" <<'PY'
+import struct, sys, zipfile
+
+src, dst, mode = sys.argv[1:4]
+
+def build_axml(dbg_mode: str) -> bytes:
+    strings = ["manifest", "application", "debuggable"]
+    # Encode UTF-8 string pool cleanly
+    pool_entries = bytearray()
+    offsets = []
+    for s in strings:
+        offsets.append(len(pool_entries))
+        b = s.encode("utf-8")
+        pool_entries.extend([len(s), len(b)])
+        pool_entries.extend(b)
+        pool_entries.append(0)
+    while len(pool_entries) % 4 != 0:
+        pool_entries.append(0)
+    str_hdr_size = 28
+    strings_start = str_hdr_size + 4 * len(strings)
+    sp_size = strings_start + len(pool_entries)
+    sp_chunk = (
+        struct.pack("<HHI", 0x0001, str_hdr_size, sp_size)
+        + struct.pack("<IIIII", len(strings), 0, 1 << 8, strings_start, 0)
+        + struct.pack(f"<{len(strings)}I", *offsets)
+        + bytes(pool_entries)
+    )
+    # RES_XML_RESOURCE_MAP_TYPE (assign 0x0101000f to index 2 = "debuggable")
+    res_ids = [0, 0, 0x0101000F]
+    rm_chunk = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(res_ids)) + struct.pack(f"<{len(res_ids)}I", *res_ids)
+
+    attrs = b""
+    attr_count = 0
+    if dbg_mode in ("explicit_false", "true"):
+        attr_count = 1
+        val = 0 if dbg_mode == "explicit_false" else 0xFFFFFFFF
+        attrs = struct.pack("<iiiHBBI", -1, 2, -1, 8, 0, 0x12, val)
+
+    start_app = (
+        struct.pack("<HHI", 0x0102, 16, 16 + 20 + len(attrs))
+        + struct.pack("<Ii", 1, -1)
+        + struct.pack("<iiHHHHHH", -1, 1, 20, 20, attr_count, 0, 0, 0)
+        + attrs
+    )
+    end_app = struct.pack("<HHIIiii", 0x0103, 16, 24, 1, -1, -1, 1)
+    body = sp_chunk + rm_chunk + start_app + end_app
+    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body
+
+manifest_bytes = build_axml(mode)
+with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", compression=zipfile.ZIP_STORED) as zout:
+    for item in zin.infolist():
+        if item.filename == "AndroidManifest.xml":
+            continue
+        zout.writestr(item.filename, zin.read(item.filename))
+    zout.writestr("AndroidManifest.xml", manifest_bytes)
+PY
+}
+
+REL_OMITTED_APK="$TMP_ROOT/rel_omitted.apk"
+make_manifest_apk "$REL_OMITTED_APK" "omitted"
+expect_pass "Check 8b passes release APK without android:debuggable attribute" \
+    bash scripts/guard.sh --check-release-apk "$REL_OMITTED_APK"
+
+REL_FALSE_APK="$TMP_ROOT/rel_false.apk"
+make_manifest_apk "$REL_FALSE_APK" "explicit_false"
+expect_pass "Check 8b passes release APK with android:debuggable=false" \
+    bash scripts/guard.sh --check-release-apk "$REL_FALSE_APK"
+
+REL_TRUE_APK="$TMP_ROOT/rel_true.apk"
+make_manifest_apk "$REL_TRUE_APK" "true"
+expect_fail "Check 8b rejects debuggable=true APK" "android:debuggable=true" \
+    bash scripts/guard.sh --check-release-apk "$REL_TRUE_APK"
+
+echo ""
+echo "=== 5. Check 9 Self-Test (Developer package safety guard) ==="
+
+expect_pass "Check 9 passes on real ZalithLauncher developer package" \
+    env GUARD_CHECK9_ONLY_DIR="ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/developer" bash scripts/guard.sh
+
+BAD_DEV_OFFLINE="$TMP_ROOT/dev_offline"
+mkdir -p "$BAD_DEV_OFFLINE"
+cat > "$BAD_DEV_OFFLINE/BadOffline.kt" <<'KT'
+package io.github.kaito65452.blazelauncher.developer
+fun unlockOffline() { localLogin("OfflinePlayer:test") }
+KT
+expect_fail "Check 9 rejects offline-account creation references in developer package" "Forbidden account/ownership/token reference" \
+    env GUARD_CHECK9_ONLY_DIR="$BAD_DEV_OFFLINE" bash scripts/guard.sh
+
+BAD_DEV_TOKEN="$TMP_ROOT/dev_token"
+mkdir -p "$BAD_DEV_TOKEN"
+cat > "$BAD_DEV_TOKEN/BadToken.kt" <<'KT'
+package io.github.kaito65452.blazelauncher.developer
+data class Leak(val accessToken: String, val refreshToken: String)
+KT
+expect_fail "Check 9 rejects token field references in developer package" "Forbidden account/ownership/token reference" \
+    env GUARD_CHECK9_ONLY_DIR="$BAD_DEV_TOKEN" bash scripts/guard.sh
+
+BAD_DEV_IMPORT="$TMP_ROOT/dev_import"
+mkdir -p "$BAD_DEV_IMPORT"
+cat > "$BAD_DEV_IMPORT/BadImport.kt" <<'KT'
+package io.github.kaito65452.blazelauncher.developer
+import com.movtery.zalithlauncher.game.account.AccountType
+KT
+expect_fail "Check 9 rejects account package import in developer package" "Forbidden account/ownership/token reference" \
+    env GUARD_CHECK9_ONLY_DIR="$BAD_DEV_IMPORT" bash scripts/guard.sh
+
+echo ""
+echo "=== 6. Check 10 Self-Tests (Engine login neutralization + .so binary markers) ==="
+
+# Positive: comments, #[cfg(test)], #[cfg(not(target_os = "android"))], and /session/join stay allowed
+CASE10_CLEAN="$TMP_ROOT/check10_clean"
+mkdir -p "$CASE10_CLEAN/src"
+cat > "$CASE10_CLEAN/src/lib.rs" <<'RS'
+// Previously used 00000000441cc96b and https://login.microsoftonline.com/consumers/oauth2/v2.0
+/* Nested /* block comment: 00000000402b5328 https://user.auth.xboxlive.com/user/authenticate */
+pub fn ely_server_join() -> &'static str {
+    "https://authserver.ely.by/session/join"
+}
+#[cfg(not(target_os = "android"))]
+pub fn desktop_only_auth() -> (&'static str, &'static str) {
+    ("https://login.microsoftonline.com/consumers/oauth2/v2.0", "https://xsts.auth.xboxlive.com/xsts/authorize")
+}
+#[cfg(test)]
+mod tests {
+    const TEST_ID: &str = "00000000441cc96b";
+}
+RS
+expect_pass "Check 10 allows comments, cfg(test), cfg(not(android)), and ely /session/join" \
+    env GUARD_CHECK10_ONLY_DIR="$CASE10_CLEAN" bash scripts/guard.sh
+
+# Negative 1: un-gated client ID 00000000441cc96b
+CASE10_CID1="$TMP_ROOT/check10_cid1"
+mkdir -p "$CASE10_CID1/src"
+cat > "$CASE10_CID1/src/lib.rs" <<'RS'
+pub const CLIENT_ID: &str = "00000000441cc96b";
+RS
+expect_fail "Check 10 rejects un-gated 00000000441cc96b" "embedded Starling/Mojang client ID '00000000441cc96b'" \
+    env GUARD_CHECK10_ONLY_DIR="$CASE10_CID1" bash scripts/guard.sh
+
+# Negative 2: obfuscated/split client ID via concat!
+CASE10_CONCAT="$TMP_ROOT/check10_concat"
+mkdir -p "$CASE10_CONCAT/src"
+cat > "$CASE10_CONCAT/src/lib.rs" <<'RS'
+pub const CLIENT_ID: &str = concat!("00000000", "402b5328");
+RS
+expect_fail "Check 10 rejects concat!-split 00000000402b5328" "embedded Starling/Mojang client ID '00000000402b5328'" \
+    env GUARD_CHECK10_ONLY_DIR="$CASE10_CONCAT" bash scripts/guard.sh
+
+# Negative 3: URL containing // inside string literal (defeats naive // comment stripping!)
+CASE10_URL="$TMP_ROOT/check10_url_slashes"
+mkdir -p "$CASE10_URL/src"
+cat > "$CASE10_URL/src/lib.rs" <<'RS'
+// Benign comment line before active URL:
+pub const MS_TOKEN_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
+RS
+expect_fail "Check 10 catches https://login.microsoftonline.com without naive // truncation" "active OAuth/Xbox/Ely login endpoint 'login\.microsoftonline\.com'" \
+    env GUARD_CHECK10_ONLY_DIR="$CASE10_URL" bash scripts/guard.sh
+
+# Negative 4: Android-targeted XSTS URL behind #[cfg(target_os = "android")]
+CASE10_ANDROID_CFG="$TMP_ROOT/check10_android_cfg"
+mkdir -p "$CASE10_ANDROID_CFG/src"
+cat > "$CASE10_ANDROID_CFG/src/lib.rs" <<'RS'
+#[cfg(target_os = "android")]
+pub fn xsts() -> &'static str { "https://xsts.auth.xboxlive.com/xsts/authorize" }
+RS
+expect_fail "Check 10 rejects Android-targeted XSTS endpoint" "active OAuth/Xbox/Ely login endpoint 'xsts\.auth\.xboxlive\.com'" \
+    env GUARD_CHECK10_ONLY_DIR="$CASE10_ANDROID_CFG" bash scripts/guard.sh
+
+# Negative 5: active login call inside Rust code (not just a string literal)
+CASE10_CALL="$TMP_ROOT/check10_call"
+mkdir -p "$CASE10_CALL/src"
+cat > "$CASE10_CALL/src/lib.rs" <<'RS'
+pub fn start_login() { let _ = account::device_code(&ep, "abc"); }
+RS
+expect_fail "Check 10 rejects active account::device_code call" "active engine login/refresh call" \
+    env GUARD_CHECK10_ONLY_DIR="$CASE10_CALL" bash scripts/guard.sh
+
+# Negative 6: setSession writing to disk in blaze_diagnostics.rs
+CASE10_DISK="$TMP_ROOT/check10_disk"
+mkdir -p "$CASE10_DISK/crates/horizon-client/src/android"
+cat > "$CASE10_DISK/crates/horizon-client/src/android/blaze_diagnostics.rs" <<'RS'
+pub fn set_session_bad(config: &Cfg, home: &Path) { let _ = config.save(home); }
+RS
+expect_fail "Check 10 rejects disk save in blaze_diagnostics.rs" "must not write session data to disk" \
+    env GUARD_CHECK10_ONLY_DIR="$CASE10_DISK" bash scripts/guard.sh
+
+# Negative 6b: full 32-hex UUID logged in BlazeSessionHandoff
+CASE10_FULL_UUID="$TMP_ROOT/check10_full_uuid"
+mkdir -p "$CASE10_FULL_UUID/crates/horizon-client/src/android"
+cat > "$CASE10_FULL_UUID/crates/horizon-client/src/android/blaze_diagnostics.rs" <<'RS'
+pub fn log_bad(uuid: u128, len: usize) { log::info!("BlazeSessionHandoff: staged (uuid={:032x}, token_len={})", uuid, len); }
+RS
+expect_fail "Check 10 rejects full 32-hex UUID in BlazeSessionHandoff log" "must only log first 8 UUID chars" \
+    env GUARD_CHECK10_ONLY_DIR="$CASE10_FULL_UUID" bash scripts/guard.sh
+
+# Desktop-only launcher/src/{backend,cli,main,mcp}.rs vs Android-shared launcher/src/account.rs (including starling/ prefix fallback)
+CASE10_DESKTOP_ONLY="$TMP_ROOT/check10_desktop_only"
+mkdir -p "$CASE10_DESKTOP_ONLY/launcher/src"
+cat > "$CASE10_DESKTOP_ONLY/launcher/src/backend.rs" <<'RS'
+pub fn desktop_download() { let _ = account::Endpoints::live(); }
+RS
+expect_pass "Check 10 allows desktop-only launcher/src/backend.rs Endpoints::live()" \
+    env GUARD_CHECK10_ONLY_DIR="$CASE10_DESKTOP_ONLY" bash scripts/guard.sh
+
+CASE10_STARLING_PREFIX="$TMP_ROOT/check10_starling_prefix"
+mkdir -p "$CASE10_STARLING_PREFIX/starling/launcher/src"
+cat > "$CASE10_STARLING_PREFIX/starling/launcher/src/backend.rs" <<'RS'
+pub fn desktop_download() { let _ = account::Endpoints::live(); }
+RS
+expect_pass "Check 10 allows starling/launcher/src/backend.rs when scanning parent dir" \
+    env GUARD_CHECK10_ONLY_DIR="$CASE10_STARLING_PREFIX" bash scripts/guard.sh
+
+CASE10_ACCOUNT_LIVE="$TMP_ROOT/check10_account_live"
+mkdir -p "$CASE10_ACCOUNT_LIVE/launcher/src"
+cat > "$CASE10_ACCOUNT_LIVE/launcher/src/account.rs" <<'RS'
+pub fn android_account_leak() { let _ = Endpoints::live(); }
+RS
+expect_fail "Check 10 rejects Endpoints::live() in Android-compiled launcher/src/account.rs" "active engine login/refresh call 'Endpoints::live'" \
+    env GUARD_CHECK10_ONLY_DIR="$CASE10_ACCOUNT_LIVE" bash scripts/guard.sh
+
+# Positive & Negative APK .so scans (Check 10b)
+make_so_marker_apk() {
+    local out_apk="$1"
+    local so_payload="$2"
+    python3 - "$SYNTH_NEG_BASE" "$out_apk" "$so_payload" <<'PY'
+import sys, zipfile
+src, dst, payload = sys.argv[1:4]
+with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", compression=zipfile.ZIP_STORED) as zout:
+    for item in zin.infolist():
+        zout.writestr(item.filename, zin.read(item.filename))
+    zout.writestr("lib/arm64-v8a/libhorizon_android.so", b"\x7fELF\x00" + payload.encode("ascii") + b"\x00")
+PY
+}
+
+SO_JOIN_OK="$TMP_ROOT/so_ely_join_only.apk"
+make_so_marker_apk "$SO_JOIN_OK" "https://authserver.ely.by/session/join"
+expect_pass "Check 10b allows https://authserver.ely.by/session/join in libhorizon_android.so" \
+    bash scripts/guard.sh --check-apk "$SO_JOIN_OK"
+
+SO_BAD_CID="$TMP_ROOT/so_bad_cid.apk"
+make_so_marker_apk "$SO_BAD_CID" "00000000441cc96b"
+expect_fail "Check 10b rejects 00000000441cc96b in libhorizon_android.so" "Starling OAuth/login marker '00000000441cc96b'" \
+    bash scripts/guard.sh --check-apk "$SO_BAD_CID"
+
+SO_BAD_URL="$TMP_ROOT/so_bad_url.apk"
+make_so_marker_apk "$SO_BAD_URL" "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
+expect_fail "Check 10b rejects login.microsoftonline.com in libhorizon_android.so" "Starling OAuth/login marker 'login\.microsoftonline\.com'" \
+    bash scripts/guard.sh --check-apk "$SO_BAD_URL"
+
+echo ""
+echo "=== 7. Check 11 Self-Tests (Stage B preparer & asset hygiene) ==="
+
+expect_pass "Check 11 passes on real repo blaze-prepare and ZalithLauncher assets" \
+    env GUARD_CHECK11_ONLY_DIR="." bash scripts/guard.sh
+
+BAD_PREPARE_PY="$TMP_ROOT/bad_prepare_py"
+mkdir -p "$BAD_PREPARE_PY/blaze-prepare/src"
+touch "$BAD_PREPARE_PY/blaze-prepare/src/helper.py"
+expect_fail "Check 11 rejects Python file inside blaze-prepare/" "forbidden Python or NBT file" \
+    env GUARD_CHECK11_ONLY_DIR="$BAD_PREPARE_PY" bash scripts/guard.sh
+
+BAD_PREPARE_NBT="$TMP_ROOT/bad_prepare_nbt"
+mkdir -p "$BAD_PREPARE_NBT/ZalithLauncher/src/main/assets/worldgen"
+touch "$BAD_PREPARE_NBT/ZalithLauncher/src/main/assets/worldgen/village.nbt"
+expect_fail "Check 11 rejects NBT/worldgen assets in ZalithLauncher assets/" "forbidden Python or NBT file|forbidden Mojang assets" \
+    env GUARD_CHECK11_ONLY_DIR="$BAD_PREPARE_NBT" bash scripts/guard.sh
+
+BAD_APK_PY="$TMP_ROOT/bad_stageb_py.apk"
+python3 - "$SYNTH_NEG_BASE" "$BAD_APK_PY" <<'PY'
+import sys, zipfile
+src, dst = sys.argv[1:3]
+with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", compression=zipfile.ZIP_STORED) as zout:
+    for item in zin.infolist():
+        zout.writestr(item.filename, zin.read(item.filename))
+    zout.writestr("assets/blaze-prepare/helper.py", b"print('bad')\n")
+PY
+expect_fail "Check 11 APK check rejects .py bundled into APK assets" "disallowed asset entries" \
+    bash scripts/guard.sh --check-apk "$BAD_APK_PY"
+
+echo ""
+echo "=== Summary: PASS=$PASS_COUNT FAIL=$FAIL_COUNT ==="
+if [ "$FAIL_COUNT" -ne 0 ]; then
+    exit 1
+fi

```

---

## Final Delivery & UNVERIFIED Items Summary

- **Code CI Run**: [`43683847378`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43683847378) (`success`, signed release APK `BlazeLauncher-2.6_hotfix-***.80c3c8c5.apk`, `versionCode=27498912 > 27321741` nightly, `android:debuggable=false`, cert SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`, artifact `signed-apks` ID `14150833342`).
- **Fast Checks Run**: [`43683847329`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43683847329) (`success`, `Guards & Self-Tests` + `JVM Unit Tests`).
- **UNVERIFIED (requires physical `SM-N975F` device)**:
  1. Live end-to-end `26.3` download, `Internal-25` `:jvm` `javac`/datagen execution, duration, disk peak, and Phase 2 preparation on `SM-N975F` (Z1/Z5).
  2. `blaze-prepare` `--release` 4,000-file benchmark timings on physical `SM-N975F` (Z3).
  3. Direct launch into Minecraft `26.3` (`EXTRA_DIRECT_LAUNCH = true`, skipping `Horizon Android` menu) and Developer spike fallback on `SM-N975F` (Z4).
  4. Physical verification of manifest-based `26.3` download/disk/time estimates in `docs/MORNING_TEST_STEPS.md` (Z5).
