# Blaze Launcher Autonomous Overnight Report (`Stages A–D`)

> **Branch**: `feat/overnight-stages` (no PRs merged; `main` untouched)
>
> **Scope**: Autonomous Stages A–D implementing Starling/Horizon Rust Edition (`arm64-v8a` only) integration with on-device engine-data preparation, engine OAuth neutralization, first device-report fixes, and integrated version install/launch flow.
>
> **Legal disclaimer**: All third-party licensing, Microsoft ownership enforcement, and upstream redistribution remarks in this report are **technical screening, not legal advice**.

---

## 1. Stage Progression & CI Release Matrix

Every stage built strictly via GitHub Actions on `arm64-v8a` with `android:debuggable=false`, R8 enabled, V2 signer certificate SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`, zero debug APKs, and monotonically increasing `versionCode` above Nightly `27321741`:

| Stage | Code Commit | Parallel Fast Checks (`Guards` / `JVM Tests`) | Release `CI` Run | `horizon-engine-arm64` Artifact | `signed-apks` Artifact | `versionCode` (vs Nightly `27321741`) | APK Size / Key Change |
|---|---|---|---|---|---|---|---|
| **Stage A** (Redaction, login policy line, `UPSTREAM_QUESTION.md`) | `17d94b41` (+ `e497afc9` docs) | [`43658119250`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43658119250) (`success`, `PASS=35`) | [`43658119350`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43658119350) (`success`) | `14139338339` (`26,531,754` B) | `14140386606` (`376,663,715` B) | `27478418` (`>`) | `380,253,633` B (`156 .so`, `debuggable=false`) |
| **Stage B** (Pure-Rust `blaze-prepare/` + `:jvm` `Internal-25` + Check 11 + benchmarks) | `70326232` (+ `28f12e63` docs) | [`43664247483`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43664247483) (`success`, `PASS=39`) | [`43664247413`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43664247413) (`success`) | `14141344813` (`28,102,350` B) | `14142821675` (`378,275,842` B) | `27483401` (`>`) | `381,872,488` B (`157 .so`, `+1.44 MiB` compressed `libblaze_prepare.so`) |
| **Stage C** (Doc picker JNI `4101`, `adjustResize`, cutout/gesture insets, `GlobalRef` audit, `BlazePerf`) | `b395d322` (+ `206f7cbc` docs) | [`43668835071`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43668835071) (`success`, `PASS=39`) | [`43668835050`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43668835050) (`success`) | `14143798395` (`28,102,434` B) | `14144856033` (`378,278,446` B) | `27486478` (`>`) | `381,875,224` B (`157 .so`, `libhorizon_android.so` `52,019,160` B) |
| **Stage D** (`runtime=rust`, `26.3` selector & installer prep, `"Rust Edition"` badge, MS-only `:rust` dispatch) | `3c78d39a` | [`43673263356`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43673263356) (`success`, `PASS=39`) | [`43673263364`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43673263364) (`success`) | `14146545637` (`28,102,434` B) | `14146368186` (`378,283,243` B) | `27490042` (`>`) | `381,884,000` B (`157 .so`, `debuggable=false`) |

*(An initial Stage D push at `99245fe5` failed `Parallel Fast Checks` [`43672935132`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43672935132) due to `String` vs `AndroidStringText` and `private` visibility of `isPreparedFallback`; its superseded `CI` run `43672935061` was cancelled and `3c78d39a` passed cleanly.)*

---

## 2. Global Rule & Guard Verification

- **Architecture**: Strict `arm64-v8a` only (`libhorizon_android.so` `52,019,160` B release, `libblaze_prepare.so` `2,846,936` B release).
- **Hygiene & Asset Policy**: Zero Mojang JARs, assets, generated tables, or prebuilt `engine-data.zip` / `horizon-resources.json` checked into git or shipped in the APK (Check 11 in `scripts/guard.sh`, `157` `.so` files scanned cleanly in CI, only upstream lock SHA JSONs & 26 MIT `.java` parity sources packaged).
- **Engine Login Neutralization & Redaction**: Embedded Starling Microsoft client IDs (`00000000441cc96b`, `00000000402b5328`) and engine OAuth endpoints remain blocked by `0004-neutralize-engine-login.patch` and Check 10 / 10b binary scans; `"engine login: disabled"` is surfaced in `"Copy report"`; UUIDs truncate to 8 hex chars + `...` and tokens emit only `token_len=<N>`.
- **Microsoft Account Enforcement**: Both Developer spike (`RustSessionHandoff`) and normal Stage D `Play` launch (`RustLaunchDispatcher` + `LauncherElements.kt` + `GameLaunchFlow.kt`) refuse offline/local accounts and disallow offline skip when launching `GameRuntime.RUST`. Developer package guard Check 9 (`PASS=39 FAIL=0`) confirms zero offline-account/bypass/token leakage in `developer/`.
- **Reports per Stage**: [`docs/STAGE_A.md`](STAGE_A.md), [`docs/STAGE_B.md`](STAGE_B.md), [`docs/STAGE_B_REPORT.md`](STAGE_B_REPORT.md), [`docs/STAGE_C.md`](STAGE_C.md), [`docs/STAGE_D.md`](STAGE_D.md), [`docs/UPSTREAM_QUESTION.md`](UPSTREAM_QUESTION.md), plus [`docs/MORNING_TEST_STEPS.md`](MORNING_TEST_STEPS.md).

---

## 3. Repository File Inventory on `feat/overnight-stages` (vs `origin/main`)

The file lists below reflect `git diff --name-status origin/main...HEAD` verified at commit `3c78d39a`, plus the three Stage D / overnight completion documentation files (`docs/STAGE_D.md`, `docs/OVERNIGHT_REPORT.md`, `docs/MORNING_TEST_STEPS.md`):

### Files Created
- Build & Workflows: `.gitmodules`, `.github/workflows/parallel-checks.yml`, `scripts/test_guards.sh`
- Engine Submodule & Patches: `engine/starling` (pinned commit `fa548eb7abe91c194d3780e4580b9746323cd606`), `engine/patches/0001-runtime-fonts.patch`, `engine/patches/0002-blaze-native-bridge.patch`, `engine/patches/0003-phase1a-bridge-diagnostics.patch`, `engine/patches/0004-neutralize-engine-login.patch`, `engine/patches/0005-stage-c-diagnostics-telemetry.patch`
- Native Engine-Data Preparer (`blaze-prepare/`): `Cargo.toml`, `Cargo.lock`, `src/{lib,lock,download,files,survival,resources,bundler,jni_bridge}.rs`, `tests/prepare_tests.rs`
- Assets & Fonts: `ZalithLauncher/src/main/assets/blaze-prepare/PrepareBatchShim.java`, `ZalithLauncher/src/main/assets/fonts/{Geist-Medium.ttf,Geist-Regular.ttf,Geist-SemiBold.ttf,GeistMono-Regular.ttf,OFL-Geist.txt}`
- Blaze Java/Kotlin Modules: `ZalithLauncher/src/main/java/dev/horizon/android/bridge/BlazeNativeBridge.kt`, `io/github/kaito65452/blazelauncher/developer/{DeveloperModeManager.kt,ui/DeveloperSection.kt}`, `io/github/kaito65452/blazelauncher/rustruntime/{BlazePrepareNative.kt,DeviceCapabilityReport.kt,EngineDataPrepareTelemetry.kt,EngineDataPreparer.kt,RustActivity.kt,RustDiagnosticsCollector.kt,RustEditionCatalog.kt,RustLaunchDispatcher.kt,RustSessionHandoff.kt}`
- JVM Unit Tests: `ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/{developer/DeveloperModeTest.kt,rustruntime/DeviceCapabilityReportTest.kt,rustruntime/EngineDataPrepareTest.kt,rustruntime/RustSessionHandoffTest.kt,rustruntime/StageCDiagnosticsTest.kt,rustruntime/StageDIntegrationTest.kt}`
- Documentation: `docs/{PHASE0_SPIKE,PHASE1A,PHASE1A_REPORT,PHASE1A_Q2,PHASE1A_Q2_REPORT,STAGE_A,STAGE_B,STAGE_B_REPORT,STAGE_C,STAGE_D,UPSTREAM_QUESTION,OVERNIGHT_REPORT,MORNING_TEST_STEPS}.md`

### Files Modified
- Build, CI & Guards: `.gitignore`, `.github/workflows/{ci,nightly,rust-engine}.yml`, `ZalithLauncher/build.gradle.kts`, `scripts/{guard.sh,scope_guard.sh}`
- Manifest, Notices & Strings: `ZalithLauncher/src/main/AndroidManifest.xml`, `ZalithLauncher/src/main/assets/THIRD_PARTY_NOTICES.md`, `ZalithLauncher/src/main/res/{values,values-in}/strings.xml`
- Developer Settings & Spike UI: `AllSettings.kt`, `AboutInfoScreen.kt`, `LauncherSettingsScreen.kt`, `RustEditionScreen.kt`, `RustEditionTest.kt`, `MainScreen.kt` (net `-4` lines removing temporary rail tab)
- Stage D Minimal ZL2 Hooks: `VersionConfig.kt`, `Version.kt`, `GameDownloadInfo.kt`, `DownloadGameWithAddonScreen.kt`, `GameInstaller.kt`, `VersionsManageElements.kt`, `GameLaunchFlow.kt`, `GameLauncher.kt`, `LauncherElements.kt`

### Notable Files Untouched
- Upstream signing/keystore workflows, `OAUTH_CLIENT_ID` wiring, `versionCode` formula, upstream R/package `namespace` (`com.movtery.zalithlauncher`), untouched `engine/starling` submodule working tree (`fa548eb7...`), and unrelated ZL2 Java/mod/renderer code paths.

---

## 4. Consolidated `UNVERIFIED` Items (Physical Device `SM-N975F` Required)

Because this environment cannot run Android UI tests or Mali GPU / Samsung Exynos hardware on-device, all physical runtime behaviors remain **`UNVERIFIED`** until executed on Samsung Galaxy Note 10+ (`SM-N975F`, Android 12) following [`docs/MORNING_TEST_STEPS.md`](MORNING_TEST_STEPS.md):

1. **In-place APK update** over previous nightly build with preserved settings/versions/accounts (`versionCode=27490042` > `27321741`).
2. **Stage A/Q2 runtime checks**: `"engine login: disabled"` & live redacted `AuthSession` / `logcat` in `"Copy report"`, confirming `:rust` displays the launcher account name and exposes no working engine OAuth/login UI.
3. **Stage B live preparation**: real network Piston Meta + Mojang downloads -> ZL2 `<gameHome>` reuse -> `Internal-25` `:jvm` execution of vanilla datagen & 26 parity `.java` exporters -> `<filesDir>/engine-data/26.3/` readiness + cancellation cleanup + measured wall-clock time & peak `VmHWM`.
4. **Stage C device fixes**: SAF Document Picker (`requestCode=4101` -> `nativeDocumentResult`), soft keyboard `adjustResize` layout reflow, `SHORT_EDGES` cutout insets + `<= 200dp` edge gesture exclusion strips, zero long-session `GlobalRef` growth, and hardware `BlazePerf` (`pipelines_created_ms`, `surface_to_first_frame_ms`) in `"Copy report"`.
5. **Stage D user flow**: `Download -> Game -> 26.3 -> Runtime: Rust Edition -> Instal` (`26.3-Rust`), `"Rust Edition"` badge, `Luncurkan` booting `:rust` with verified Microsoft account, and rejection of offline/local accounts.
