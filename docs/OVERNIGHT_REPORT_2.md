# Overnight Consolidation Report 2 (`docs/OVERNIGHT_REPORT_2.md`) — Stages 0–3 (Tasks X, W, P)

> **Branch**: `feat/overnight-stages` (pinned engine submodule `engine/starling` at `fa548eb7abe91c194d3780e4580b9746323cd606`, modified only via `engine/patches/0001`–`0007`).  
> **Release Signing Policy**: Every CI APK built in Stages 1–3 is release-signed (`android:debuggable=false`, R8 enabled, certificate SHA-256 `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`, strictly monotonic `versionCode > 27516547`).  
> **Legal Note**: Any license/hygiene findings below reflect automated technical screening, not legal advice.

---

## 1. Consolidated Stage Verdict Matrix (`GO` / `NO-GO` / `BLOCKED` / `UNVERIFIED`)

| Stage / Item | Verdict | Evidence / Key Finding |
| :--- | :--- | :--- |
| **Stage 0.1** — Inspect WIP & restore NUL-corrupted `prepare_tests.rs` | **GO** | Audited partial edits in [download.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/download.rs) and [lock.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/lock.rs); kept valid redirect/host/native logic; restored and rebuilt [prepare_tests.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/tests/prepare_tests.rs). |
| **Stage 0.2** — Zero-file / NUL-byte guard (`check_no_zero_files.sh`) | **GO** | Added [check_no_zero_files.sh](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/check_no_zero_files.sh) checking `tr -d '\000' < file \| wc -c > 0` (+ exact text equality), wired into [guard.sh](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/guard.sh) & self-tested in [test_guards.sh](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/test_guards.sh) (`PASS=46 FAIL=0` in Stage 1, `PASS=51 FAIL=0` in Stage 3). |
| **Stage 0.3** — Zero local builds/heavy tasks on phone | **GO** | All Cargo/Gradle builds, JVM tests, Rust tests, and benchmarks executed strictly in GitHub Actions workflows. |
| **Task X1** — Allowlist `libraries.minecraft.net` + strict URL/redirect checks | **GO** | `ALLOWED_DOWNLOAD_HOSTS` ([lock.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/lock.rs#L166-L174)) includes `libraries.minecraft.net`; `validate_download_url_with_context` enforces HTTPS `:443`, exact lowercase match, no `@`/userinfo; `HttpFetcher::call_validated_with_redirects` ([download.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/download.rs#L71-L136)) uses `.redirects(0)` and re-validates every hop (max 5). Mandatory SHA-1 intact. |
| **Task X2** — Transient MC 26.3 Mojang host audit in CI | **GO** | Run [`43718712434`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43718712434) (`engine/engine` `132825771482`) & [`43729106715`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43729106715) verified `5,149` asset objects across 5 distinct hosts (`libraries.minecraft.net`, `piston-data.mojang.com`, `piston-meta.mojang.com`, `raw.githubusercontent.com`, `resources.download.minecraft.net`); `0` unexpected hosts; transient files never stored. |
| **Task X3** — Redacted actionable URL/host errors | **GO** | `sanitize_url_for_error` strips credentials, `?query`, `#fragment`; errors include offending host, source field, `allowed hosts: [...]`, and `Next step:` guidance. |
| **Task X4** — Synthetic download/redirect security Rust tests | **GO** | `task_x4_download_host_allowlist_and_redirect_security_synthetic` passes in CI (`6 passed; 0 failed`). |
| **Task X5** — Static preflight of post-download `Install.RustEngineData` | **GO** (fixes) / **UNVERIFIED** (device `:jvm`) | Fixed missing `getGameHome().mkdirs()` before `chdir()` and bumped >=8 GB device JVM heap to `1024` MB in [EngineDataPreparer.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPreparer.kt). 4 remaining runtime questions documented as `RISK 1`–`RISK 4` in [STAGE_X.md](file:///data/data/com.termux/files/home/BlazeLauncher/docs/STAGE_X.md#L79-L83). |
| **Task X6** — Phase failure + redacted error in Copy details/report | **GO** | Added `failedPhase` / `failed_phase` in [EngineDataPrepareTelemetry.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPrepareTelemetry.kt) and persistence in [EngineDataPreparer.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPreparer.kt); verified by `failedPhaseAndRedactedErrorReachBothDetailsAndCopyReport` in `JVM Unit Tests`. |
| **Task W1–W2** — Inventory & migrate all checks to CI | **GO** | Full mapping in [STAGE_W.md](file:///data/data/com.termux/files/home/BlazeLauncher/docs/STAGE_W.md). Added `bash -n scripts/*.sh` to `parallel-checks.yml`, `./scripts/test_guards.sh` to `nightly.yml`/`release.yml`, and `cargo test -p horizon-prepare` (`56+1+1 passed`) to `rust-engine.yml`. No path filters skip guards. |
| **Task W3–W4** — Update `AGENTS.md`, `GEMINI.md`, & docs | **GO** | Verbatim `Device Load Rules` placed at top of [AGENTS.md](file:///data/data/com.termux/files/home/BlazeLauncher/AGENTS.md) & [GEMINI.md](file:///data/data/com.termux/files/home/BlazeLauncher/GEMINI.md). Removed local build/test instructions from [STAGE_B.md](file:///data/data/com.termux/files/home/BlazeLauncher/docs/STAGE_B.md), [STAGE_C.md](file:///data/data/com.termux/files/home/BlazeLauncher/docs/STAGE_C.md), and [MS_LOGIN.md](file:///data/data/com.termux/files/home/BlazeLauncher/docs/MS_LOGIN.md). |
| **Task W5** — Strict `.so` cache + `0007` patch parity proof | **GO** | Strict `.so` cache in [rust-engine.yml](file:///data/data/com.termux/files/home/BlazeLauncher/.github/workflows/rust-engine.yml) cut `engine/engine` wall-clock from **`22m 32s`** (`43718712434`) to **`3m 17s`** (`43729106715`, **`85.4%` faster**). Patch `0007-pin-refresh-26.3.patch` verified applied before Rust & Gradle builds. |
| **Task W6** — CI-excluded tests & device-only gaps | **UNVERIFIED** | Excluded upstream unit tests (`file/**`, `VersionCompareTest*` in `build.gradle.kts:169-172`), Android `:jvm` `Internal-25` execution, and Mali GPU rendering remain `UNVERIFIED` in CI. |
| **Task P1 & P4** — `preflight-real.yml` & Check 13 hygiene guard | **GO** | [preflight-real.yml](file:///data/data/com.termux/files/home/BlazeLauncher/.github/workflows/preflight-real.yml) is `workflow_dispatch`-only, `contents: read`, zero secrets/cache, `always()` temp cleanup, log/summary-only upload. [check_preflight_workflow.sh](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/check_preflight_workflow.sh) + 5 self-tests verified (`PASS=51 FAIL=0`). |
| **Task P2** — `blaze-prepare` host preflight runner + JDK 25 | **GO** (build) / **BLOCKED** (live run) | [preflight.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/examples/preflight.rs) compiled in `rust-engine.yml` (`43729106715`); live MC 26.3 execution awaiting P6 unblock. |
| **Task P3, P5, P6** — Live `workflow_dispatch` real-data parity & memory telemetry | **BLOCKED** | `gh workflow run preflight-real.yml --ref feat/overnight-stages` returned `HTTP 404: workflow preflight-real.yml not found on the default branch`. GitHub Actions rejects `workflow_dispatch` for workflows absent from `main`, and merging/committing to `main` is forbidden here. |
| **On-Device Android Runtime** (`SM-N975F`, `:jvm` `Internal-25`, LMK RAM, UFS I/O) | **UNVERIFIED** | Not executable in GitHub Actions; requires installing the latest `signed-apks` build on physical device ([MORNING_TEST_STEPS.md](file:///data/data/com.termux/files/home/BlazeLauncher/docs/MORNING_TEST_STEPS.md)). |

---

## 2. Files Created, Modified, and Untouched (vs `37b45e50` Stage-0 Baseline)

### Files Created (`8`)
1. `.github/workflows/preflight-real.yml`
2. `blaze-prepare/examples/preflight.rs`
3. `docs/OVERNIGHT_REPORT_2.md` *(this report)*
4. `docs/STAGE_P.md`
5. `docs/STAGE_W.md`
6. `docs/STAGE_X.md`
7. `scripts/check_no_zero_files.sh`
8. `scripts/check_preflight_workflow.sh`

### Files Modified (`18`)
1. `.github/workflows/nightly.yml`
2. `.github/workflows/parallel-checks.yml`
3. `.github/workflows/release.yml`
4. `.github/workflows/rust-engine.yml`
5. `AGENTS.md`
6. `GEMINI.md`
7. `ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPrepareTelemetry.kt`
8. `ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPreparer.kt`
9. `ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPrepareTest.kt`
10. `blaze-prepare/src/download.rs`
11. `blaze-prepare/src/lock.rs`
12. `blaze-prepare/tests/prepare_tests.rs`
13. `docs/MORNING_TEST_STEPS.md`
14. `docs/MS_LOGIN.md`
15. `docs/STAGE_B.md`
16. `docs/STAGE_C.md`
17. `scripts/guard.sh`
18. `scripts/test_guards.sh`

### Key Areas Explicitly Untouched
- `engine/starling/` submodule (remains pinned at `fa548eb7abe91c194d3780e4580b9746323cd606`; zero dirty in-place edits).
- `engine/patches/0001`–`0007` (kept intact; `0007-pin-refresh-26.3.patch` unchanged from Task Y).
- Signing keystore config, `OAUTH_CLIENT_ID` wiring, `versionCode` formula, `.github/workflows/ci.yml`, `scripts/scope_guard.sh`.
- ZL2 upstream UI/account/rendering code outside the three approved hook points; `blaze-prepare/src/resources.rs` & bundled `.java` exporters.

---

## 3. CI Run Ledger per Stage

*(Prior baseline before Stage 0: commit `37b45e50`, CI run [`43707404107`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43707404107), `versionCode=27516547`.)*

| Stage | Commit | Workflow & Run URL | Artifact Name & ID | `versionCode` | Signing Cert SHA-256 & Flags |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Stage 1 (Task X)** | `21af392b` | `Parallel Fast Checks` [`43718712111`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43718712111) | — (`PASS=46 FAIL=0`, `JVM Unit Tests` pass) | — | — |
| **Stage 1 (Task X)** | `21af392b` | `CI` [`43718712434`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43718712434) | `horizon-engine-arm64` ID `14164242776` (`28116607` B)<br>`signed-apks` ID `14163849778` (`378303665` B) | `27524844` | `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`<br>`debuggable=false`, `engine=22m32s` |
| **Stage 2 (Task W)** | `b3ca46af` | `Parallel Fast Checks` [`43722497942`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43722497942) | — (`Guards` `1m40s` `PASS=46`, `JVM` `6m04s`) | — | — |
| **Stage 2 (Task W)** | `b3ca46af` | `CI` [`43722497912`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43722497912) | `horizon-engine-arm64` ID `14165444175` (`28116518` B)<br>`signed-apks` ID `14166921670` (`378303340` B) | `27528268` | `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`<br>`debuggable=false`, cold `.so` cache population `36m38s` |
| **Stage 3 (Task P + W5 hit)** | `0b6c5305` | `Parallel Fast Checks` [`43729106696`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43729106696) | — (`Guards` `1m56s`, `JVM` `6m43s`) | — | — |
| **Stage 3 (Task P + W5 hit)** | `0b6c5305` | `CI` [`43729106715`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43729106715) | `horizon-engine-arm64` ID `14167028992` (`28116518` B)<br>`signed-apks` ID `14167928768` (`378303365` B) | `27530606` | `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc`<br>`debuggable=false`, `PASS=51 FAIL=0`, `.so` **cache hit `3m17s`** |
| **Final Docs Push** | *(pending)* | *(Reported in final response after push)* | *(Reported in final response)* | *(> `27530606`)* | *(Reported in final response)* |

---

## 4. Hunk Audit: `git diff origin/main...HEAD -- scripts/ .github/workflows/`

Every hunk between `origin/main` and `HEAD` (11 files total) is classified strictly as **`adds`**, **`hardens`**, or **`weakens`**:

| File | Hunk Count (`-U0`) | Classification | Hunk-by-Hunk Summary |
| :--- | :--- | :--- | :--- |
| `.github/workflows/ci.yml` | 13 hunks | **adds / hardens** (`0 weakens`) | Hunk 1–2 (`+test_guards.sh`, `+0007` patch verify): **adds/hardens**. Hunk 3–13 (replaces `assembleDebug` with `compileReleaseKotlin` + `assembleRelease`, bans debug APKs, upgrades `--check-apk` -> `--check-apk-release`, pins `EXPECTED_SHA256` & `debuggable == false`): **hardens**. |
| `.github/workflows/nightly.yml` | 6 hunks | **adds / hardens** (`0 weakens`) | Adds `test_guards.sh` + `check_no_zero_files.sh`, applies `0007` patch, pins signing cert `EXPECTED_SHA256`, enforces `debuggable == false`. |
| `.github/workflows/release.yml` | 3 hunks | **adds / hardens** (`0 weakens`) | Adds `test_guards.sh` + `check_no_zero_files.sh`, enforces `0007` patch application parity. |
| `.github/workflows/parallel-checks.yml` | 1 hunk (new, 66 lines) | **adds** (`0 weakens`) | Adds fast parallel PR/push CI (`bash -n scripts/*.sh`, `test_guards.sh`, `guard.sh`, `scope_guard.sh`, `testDebugUnitTest`) with zero skip path-filters. |
| `.github/workflows/preflight-real.yml` | 1 hunk (new, 402 lines) | **adds** (`0 weakens`) | Adds manual `workflow_dispatch`-only MC 26.3 desktop preflight + parity diff against `horizon-prepare`, `contents: read`, zero secrets/cache, `always()` temp cleanup. |
| `.github/workflows/rust-engine.yml` | 17 hunks | **adds / hardens** (`0 weakens`) | Replaces 4-ABI placeholder (previous upstream job skipped when `engine/` was absent) with mandatory pinned `engine/starling` (`fa548eb7...`) arm64-v8a build (`exit 1` if missing), applies patches `0001`–`0007`, runs `horizon-prepare` + `blaze-prepare` unit tests, Python parity oracle, X2 transient Mojang host audit, aarch64 bench, and exact-key `.so` caching (`no restore-keys`). *Note*: dropping `armeabi-v7a`/`x86_64`/`i686` implements the repo's explicit `arm64-v8a only` policy, not a check relaxation. |
| `scripts/check_no_zero_files.sh` | 1 hunk (new, 100 lines) | **adds** (`0 weakens`) | New Check 12 integrity script rejecting zero-byte / NUL-corrupted changed files. |
| `scripts/check_preflight_workflow.sh` | 1 hunk (new, 120 lines) | **adds** (`0 weakens`) | New Check 13 guard enforcing `preflight-real.yml` trigger/permission/secret/cache/cleanup/artifact constraints. |
| `scripts/guard.sh` | 15 hunks | **adds / hardens** (`0 weakens`\* ) | Adds isolated test-mode switches for self-testing; enforces `0007`/submodule presence; adds AST-aware font embed scanner (Check 7), stricter `--check-apk-release` asset/`.class`/`.so` secret scans, Check 12 (`check_no_zero_files.sh`), and Check 13 (`check_preflight_workflow.sh`). \*Transparency note on Hunk 2 (`L25`): `git grep` secret scan excludes `:!scripts/test_guards.sh` solely so negative synthetic secret fixtures inside `test_guards.sh` do not self-flag; `guard.sh` Check 1 continues scanning all production/source/config paths untouched. |
| `scripts/scope_guard.sh` | 4 hunks | **adds** (`0 weakens`) | Expands allowlist strictly for approved architecture files (`blaze-prepare/*`, `.gitmodules`, `AndroidManifest.xml`, `assets/fonts/*`, `BlazeNativeBridge.kt`, `AllSettings.kt` + Developer Mode UI, `VersionConfig.kt`/`GameLauncher.kt` hooks). Zero checks removed. |
| `scripts/test_guards.sh` | 1 hunk (new, 647 lines) | **adds** (`0 weakens`) | New comprehensive positive/negative regression suite covering Checks 1–13 (`PASS=51 FAIL=0`). |

---

## 5. Top Risks & What Remains

1. **Task P6 Live Dispatch Block (`BLOCKED` until merge to `main`)**: `preflight-real.yml` cannot execute via `workflow_dispatch` on GitHub Actions until this branch merges into `main`. Once merged, trigger `gh workflow run preflight-real.yml --ref main` to collect real MC 26.3 timing, peak RSS (`VmHWM`), `horizon-prepare --check` pass/fail, and exact file/SHA-256 parity diff.
2. **Android `:jvm` `Internal-25` & Memory Pressure (`RISK 1` & `RISK 2`)**: On physical devices with 4–6 GB RAM (`-Xmx640M`/`768M`), `VanillaCreativeTabsExporter` and `net.minecraft.data.Main` could hit OOM or interact with `MioLibPatcher`/`-Dorg.lwjgl.*` args injected by [Launcher.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/Launcher.kt#L349-L368). Only device testing can verify.
3. **`net.minecraft.data.Main` Server Classpath (`RISK 3`)**: `EngineDataPreparer` unpacks `server.jar` and invokes `net.minecraft.data.Main` via `-cp <serverClasspath>` instead of upstream's `-DbundlerMainClass=net.minecraft.data.Main -jar server.jar`; unverified against live 26.3 until `preflight-real.yml` or device test runs.
4. **Upstream Readiness Parity (`RISK 4`)**: Confirming every entry checked by `horizon_prepare::check::check` (`horizon-resources.json` `schema=7`, `horizon-prepare.json` `output_version=3`, extras/sounds/panorama sizes) against real MC 26.3 data awaits `preflight-real.yml` dispatch on `main` or physical device verification.
5. **What Remains**:
   - Device verification of the latest green `signed-apks` APK from `feat/overnight-stages` following [MORNING_TEST_STEPS.md](file:///data/data/com.termux/files/home/BlazeLauncher/docs/MORNING_TEST_STEPS.md).
   - Human PR review & merge of `feat/overnight-stages` -> `main`, followed by dispatching `.github/workflows/preflight-real.yml` on `main`.
