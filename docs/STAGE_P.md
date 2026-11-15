# Stage 3 Delivery Report (`docs/STAGE_P.md`) — Task P: Real-Data Preflight in CI

> **Status**: Workflow & Preflight Runner Committed; Real-Data Dispatch Execution Pending Push CI & `workflow_dispatch`

---

## P1 & P4 — Workflow Constraints & Hygiene Guard

- **[`.github/workflows/preflight-real.yml`](file:///data/data/com.termux/files/home/BlazeLauncher/.github/workflows/preflight-real.yml)**:
  - Triggers ONLY on `workflow_dispatch` (optional `heap_mb` input defaulting to `2048`). Never triggers on `push`, `pull_request`, or `schedule`.
  - Minimal permissions (`contents: read` only); zero `secrets.*` references; zero `actions/cache` usage.
  - Runs on `ubuntu-latest` (`x86_64`, required) and `ubuntu-24.04-arm` (`aarch64`, `continue-on-error: true`).
  - Transient `$RUNNER_TEMP/preflight-work-*` and `engine/starling/.local` directories are removed in an `if: always()` cleanup step.
  - Uploads ONLY redacted `*.log` and `*-summary-*.md` reports (`if-no-files-found: warn`), asserting zero `.local`, `engine-data`, `.jar`, `.json`, `.nbt`, `.ogg`, `.png`, or `libraries/` files exist in the report directory.
- **[`scripts/check_preflight_workflow.sh`](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/check_preflight_workflow.sh)**, **[`scripts/guard.sh`](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/guard.sh#L931-L936)**, **[`scripts/test_guards.sh`](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/test_guards.sh#L607-L645)**:
  - Added automated CI guard verifying `preflight-real.yml` trigger isolation, minimal permissions, zero secrets, zero `actions/cache`, `if: always()` cleanup, and strict artifact allowlist, backed by positive and known-bad (`push`, `pull_request`, `engine-data.zip` upload, `actions/cache`) self-tests.

---

## P2 — Host `blaze-prepare` Preflight Pipeline

- **[`blaze-prepare/examples/preflight.rs`](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/examples/preflight.rs)**:
  - Links directly against `blaze_prepare` (`run_phase1`, `run_phase2_finalize`, `is_prepared`, `PrepareConfig`, `peak_rss_kb`, `sha256_file`, `redact_sensitive`).
  - Downloads Minecraft `26.3` transiently into `$RUNNER_TEMP` with identical pin verification (`0007-pin-refresh-26.3.patch` applied to `engine/starling/research-lock.json`), `ALLOWED_DOWNLOAD_HOSTS` allowlist + hop-by-hop redirect validation, and mandatory SHA-1 checks.
  - Invokes desktop OpenJDK 25 `javac` + `net.minecraft.data.Main --reports` + `PrepareBatchShim` (`server_tasks.jsonl` & `client_tasks.jsonl`) + `VanillaCommandParseOracle` + `VanillaCreativeTabsExporter`, matching [EngineDataPreparer.kt](file:///data/data/com.termux/files/home/BlazeLauncher/ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPreparer.kt).
  - Measures per-phase wall-clock (`ms`) and peak RSS (`VmHWM` in KiB) for both the Rust process and every child `javac`/`java` subprocess.

---

## P3, P5 & P6 — Execution Results, Upstream Parity Diffs & `UNVERIFIED` Scope

*To be populated immediately after pushing Stage 3 and running `gh workflow run preflight-real.yml --ref feat/overnight-stages`.*

### Always `UNVERIFIED` on CI Runners (Android Physical Device Required)
- Android `:jvm` (`Internal-25` bundled JRE + `libawt`/`libawt_headless` / `MioLibPatcher` agent interactions) execution.
- Physical device (`SM-N975F`) RAM/LMK headroom at `-Xmx640M`/`-Xmx768M`/`-Xmx1024M` vs desktop CI headroom.
- Physical device UFS I/O throughput and cancel-cleanup timing on Android storage.
