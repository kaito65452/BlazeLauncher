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

## P3, P5 & P6 — Execution Results, Upstream Parity Diffs & `UNVERIFIED` / `BLOCKED` Scope

- **Preflight runner compilation**: `blaze-prepare/examples/preflight.rs` is compiled on every push in `.github/workflows/rust-engine.yml` (`cargo test --locked --manifest-path blaze-prepare/Cargo.toml ...` builds all examples).
- **Workflow hygiene self-tests**: [`scripts/test_guards.sh`](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/test_guards.sh) (`PASS=51 FAIL=0`) verified `.github/workflows/preflight-real.yml` passes [`scripts/check_preflight_workflow.sh`](file:///data/data/com.termux/files/home/BlazeLauncher/scripts/check_preflight_workflow.sh) while rejecting `push`, `pull_request`, `actions/cache`, and uploading `engine-data.zip`.
- **P6 Live Workflow Dispatch — `BLOCKED` (GitHub Platform Constraint)**:
  - Command attempted: `gh workflow run preflight-real.yml --ref feat/overnight-stages`
  - Raw failure output:
    ```text
    HTTP 404: workflow preflight-real.yml not found on the default branch (https://api.github.com/repos/kaito65452/BlazeLauncher/actions/workflows/preflight-real.yml)
    ```
  - **Root cause**: GitHub Actions only allows dispatching `workflow_dispatch`-only workflow files that are already committed on the repository's default branch (`main`). Because project rules strictly forbid committing directly to `main`, merging PRs, or relaxing `preflight-real.yml` to trigger on `push`/`pull_request`, live execution (`P3`, `P5`, `P6`) is **BLOCKED** until `feat/overnight-stages` is merged into `main` (at which point `gh workflow run preflight-real.yml --ref main` will execute cleanly without weakening any guard).
- **Expected / Static-Verified P3 Differences (pending first `workflow_dispatch` on `main`)**:
  - `only_blaze`: `android-downloads-v2.json`, `audio-complete.json` (Android launcher completion markers written by [`blaze-prepare/src/resources.rs:969-973`](file:///data/data/com.termux/files/home/BlazeLauncher/blaze-prepare/src/resources.rs#L969-L973)).
  - `horizon-resources.json` (`schema = 7`, `minecraft = "26.3"`) & `horizon-prepare.json` (`output_version = 3`): parity verified against Python oracle and upstream constants, while live 26.3 SHA-256 diff & `horizon-prepare --check` exit status remain `BLOCKED`/`UNVERIFIED` until `workflow_dispatch` runs from `main`.

### Always `UNVERIFIED` on CI Runners (Android Physical Device Required)
- Android `:jvm` (`Internal-25` bundled JRE + `libawt`/`libawt_headless` / `MioLibPatcher` agent interactions) execution.
- Physical device (`SM-N975F`) RAM/LMK headroom at `-Xmx640M`/`-Xmx768M`/`-Xmx1024M` vs desktop CI headroom.
- Physical device UFS I/O throughput and cancel-cleanup timing on Android storage.
