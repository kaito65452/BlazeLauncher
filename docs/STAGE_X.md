# Stage 0 & Stage 1 (Task X): Integrity Guard, Download Host Allowlist, Static Preflight, and Failure Reporting

## Stage 0 Summary — Integrity & Orientation

1. **WIP inspection**:
   - Inspected partial edits in [download.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/download.rs) and [lock.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/lock.rs) before editing. Kept clean allowlist, `.redirects(0)` hop-by-hop validation, `source_field` tracking, non-retryable rejection, and LWJGL `natives-` filtering.
   - Restored [prepare_tests.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/tests/prepare_tests.rs) from `HEAD` after prior NUL corruption on device and re-wrote comprehensive synthetic Task X tests (`task_x4_download_host_allowlist_and_redirect_security_synthetic`).
2. **Zero-file / NUL-corruption guard (`Stage 0.2`)**:
   - Added [check_no_zero_files.sh](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/check_no_zero_files.sh), checking `tr -d '\000' < file | wc -c` > 0 (and exact equality with byte size for all non-binary files).
   - Wired into [guard.sh](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/guard.sh) (full run + isolated `GUARD_CHECK_ZERO_ONLY_DIR` mode).
   - Added positive and known-bad NUL-filled / embedded-NUL negative self-tests in [test_guards.sh](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/test_guards.sh).

---

## Task X Details

### X1 — Host Allowlist & Strict Redirect Validation
- Allowlist location: `ALLOWED_DOWNLOAD_HOSTS` in [lock.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/lock.rs#L166-L174):
  - `piston-meta.mojang.com`
  - `piston-data.mojang.com`
  - `libraries.minecraft.net` (added)
  - `resources.download.minecraft.net`
  - `launcher.mojang.com`
  - `launchermeta.mojang.com`
  - `raw.githubusercontent.com`
- Enforcement rules in `validate_download_url_with_context` ([lock.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/lock.rs#L205-L246)):
  - HTTPS only on default/explicit port 443 (except synthetic loopback `http://127.0.0.1|localhost` when `allow_loopback=true`).
  - Exact lowercase hostname equality (`ALLOWED_DOWNLOAD_HOSTS.contains(&host)`), rejecting suffix, prefix, substring, trailing-dot, and subdomain lookalikes.
  - Rejects both non-empty `username`/`password` and raw `@` in authority (`https://@libraries.minecraft.net/...`).
  - Redirects disabled on the `ureq::Agent` (`.redirects(0)`) and manually followed up to `MAX_DOWNLOAD_REDIRECTS = 5` in `HttpFetcher::call_validated_with_redirects` ([download.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/download.rs#L71-L136)), re-running `validate_download_url_with_context` on every redirect hop with zero retries on security/hash errors.
  - Mandatory SHA-1 / SHA-256 verification on downloaded files (`ensure_verified_file` in [download.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/download.rs)).

### X2 — Transient Minecraft 26.3 Host Audit (CI)
- Added transient X2 audit step in [rust-engine.yml](file:///data/data/com.termux/files/home/BlazeLauncher/.github/workflows/rust-engine.yml) fetching catalog, 26.3 version JSON, and asset index into an auto-cleaned temporary directory, logging only distinct `(field, host)` pairs and asserting zero hosts outside `ALLOWED_DOWNLOAD_HOSTS`.
- Live CI results: *pending first CI push of this stage (will be filled from `gh run view --log`)*.

### X3 — Actionable Redacted Diagnostics
- URL/host/redirect errors in [lock.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/lock.rs) and [download.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/download.rs) report:
  1. Offending hostname (`host=...` / `Untrusted download host: ...`)
  2. Source field and URL sanitized via `sanitize_url_for_error` (stripping credentials, `?query` tokens, `#fragments`)
  3. Full `allowed hosts: [...]` list
  4. Plain-English `Next step:` guidance
  5. Direct catalog pin rejection in `resolve_version_manifest` also passes `meta_url` through `sanitize_url_for_error`.

### X4 — Synthetic Unit Tests
- Added `task_x4_download_host_allowlist_and_redirect_security_synthetic` in [prepare_tests.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/tests/prepare_tests.rs):
  - Every host in `ALLOWED_DOWNLOAD_HOSTS` passes HTTPS (`:443` implicit & explicit).
  - Lookalike/suffix/prefix/trailing-dot/subdomain hosts, `http://`, non-443 ports, `user:pass@`, empty-user `@`, and malformed URLs are rejected even with `allow_loopback=true`.
  - Loopback mock HTTP server verifies 302 redirects to untrusted host, `http://`, userinfo, and `>5` redirect loop fail immediately with zero retries and no query-token leak.
  - Mandatory SHA-1 mismatch rejection on valid loopback fetch.
- Executed via CI (`rust-engine.yml` -> `blaze-prepare-tests`); *UNVERIFIED until CI completes*.

### X5 — Static Preflight of Post-Download `Install.RustEngineData` Path
Traced end-to-end across [GameInstaller.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/download/game/GameInstaller.kt), [EngineDataPreparer.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPreparer.kt), [JvmLauncher.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/JvmLauncher.kt), [Launcher.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/Launcher.kt), and [resources.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/resources.rs):

**Verified OK / Fixed by us:**
1. **LWJGL native skip & arm64 selection** (`native_library_matches`, [download.rs](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/download.rs#L595-L615)): Skips `org.lwjgl:*:natives-*` (headless exporters/datagen never load LWJGL natives) and selects `arm64`/`aarch64` over `x86_64`.
2. **26 parity `.java` exporters & batch shim**: `EngineDataPreparer.unpackBundledAssets` unpacks all 26 MIT `.java` files + `PrepareBatchShim.java`; server/client partitions remain verified by unit test (`EngineDataPrepareTest`).
3. **`chdir()` game-home prerequisite**: `JvmLauncher.chdir()` ([JvmLauncher.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/JvmLauncher.kt#L85-L87)) enters `getGameHome()`. Added `mkdirs()` on `getGameHome()` before `runSequentialJavaExporters` in [EngineDataPreparer.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPreparer.kt) so first-run Developer Mode trigger does not fail `chdir`.
4. **JVM heap tier on >=8 GB devices**: Bumped `EngineDataPrepareSupport.recommendJvmHeapMb` for >=8 GB RAM devices to `1024` MB (later `-Xmx` in `jvmArgs` overrides `progressFinalUserArgs`'s earlier `-Xmx512M` in [Launcher.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/Launcher.kt#L183-L185)).

**Remaining RISKS (documented for Stage 3 real-data preflight & device testing):**
- **RISK 1 — `:jvm` Heap Headroom vs Desktop `1G`/`2G` (`VanillaCreativeTabsExporter` & `net.minecraft.data.Main`)**: Upstream [lib.rs](file:///data/data/com.termux/files/home/BlazeLauncher/engine/starling/crates/horizon-prepare/src/lib.rs#L1172) runs `VanillaCreativeTabsExporter` at `-Xmx2G` and other exporters at `-Xmx1G`. Devices with 4–6 GB total RAM receive `-Xmx640M` / `-Xmx768M` (`recommendJvmHeapMb`), which may still risk `OutOfMemoryError` if Android LMK pressure or 26.3 registry heap demands exceed 768 MB. Stage 3 (`preflight-real.yml`) will measure peak heap usage under JDK 25; device memory limits stay `UNVERIFIED`.
- **RISK 2 — `Launcher.progressFinalUserArgs` `-javaagent:MioLibPatcher` & `-Dorg.lwjgl.*` injection**: [Launcher.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/Launcher.kt#L349-L368) injects `-javaagent:MioLibPatcher` and LWJGL/Cacio properties onto all `JvmLauncher` subprocesses, including `javac` (`-m jdk.compiler/com.sun.tools.javac.Main`) and datagen. Only real device `:jvm` execution can confirm zero agent interference (CI cannot execute Android `:jvm` `Internal-25` JRE).
- **RISK 3 — `net.minecraft.data.Main` direct `serverClasspath` vs bundler `-jar server.jar`**: Upstream runs `java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports --output out` ([lib.rs](file:///data/data/com.termux/files/home/BlazeLauncher/engine/starling/crates/horizon-prepare/src/lib.rs#L1131-L1140)), while Blaze runs `-cp <serverClasspath> net.minecraft.data.Main --reports --output <datagenOutDir>` after `unpack_server_bundle`. Exact classpath completeness will be tested directly in Stage 3 (`preflight-real.yml`) against real MC 26.3 server jars.
- **RISK 4 — Readiness parity (`horizon_prepare::check`)**: Upstream `check::check` ([check.rs](file:///data/data/com.termux/files/home/BlazeLauncher/engine/starling/crates/horizon-prepare/src/check.rs#L35-L73)) validates `horizon-resources.json` (`schema=7`, `minecraft="26.3"`), `horizon-prepare.json` (`output_version=3`), and `missing_extras` (`resourcepacks`, `panorama_{0..5}.png` >1x1, `panorama_overlay.png`, `unifont.zip`, `assets/pack.mcmeta`, `assets/minecraft/sounds.json`). Real-data parity between `blaze-prepare` and `horizon-prepare` will be executed and verified in Stage 3 (`preflight-real.yml`).

### X6 — Phase Failure & Redacted Error Propagation ("Copy details" & "Copy report")
- Added `failedPhase` to `EngineDataPrepareException` and `failed_phase` to `EngineDataPrepareMetrics` ([EngineDataPrepareTelemetry.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPrepareTelemetry.kt)).
- Updated [EngineDataPreparer.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPreparer.kt) so failures in ALL phases (`load_native`, `unpack_assets`, `rust_phase1`, `ensure_jre25`, `jvm_exporters: <step>`, `rust_phase2`, unexpected throwables) call `saveFailureTelemetry(...)` and populate `_uiState.redactedError`, and `refreshPreparedState()` restores persisted failure details when returning to Developer Mode.
- Added `failedPhaseAndRedactedErrorReachBothDetailsAndCopyReport` in [EngineDataPrepareTest.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPrepareTest.kt).

---

## CI Verification (Pending / Updated After Push)
- Local fast checks before push: `scripts/check_no_zero_files.sh`, `scripts/scope_guard.sh`
- CI workflows triggered on push: `parallel-checks.yml`, `ci.yml`, `rust-engine.yml`
- CI run URL / APK / X2 host output: *to be recorded after CI completion*.
