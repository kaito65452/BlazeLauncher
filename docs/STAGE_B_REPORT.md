# Stage B Engineering Report: Pure-Rust On-Device Engine-Data Preparer (`docs/STAGE_B_REPORT.md`)

> **Scope**: Binary size, memory footprint, synthetic 4,000-file throughput benchmark (Rust vs Kotlin vs Python), and architectural trade-offs for `libblaze_prepare.so` (`arm64-v8a`). Device timings on `SM-N975F` remain `UNVERIFIED` until physical testing.

---

## 1. Binary & APK Size Impact (`arm64-v8a`)

Measured directly from CI Run [`43664247413`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43664247413) (`70326232`) compared against Stage A CI Run [`43658119350`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43658119350) (`17d94b41`):

| Artifact / Binary | Profile / Packaging | Size (bytes) | Size (MiB) |
|---|---|---|---|
| `libblaze_prepare.so` | `fast` (unstripped, `opt-level=0`) | `60,645,504` | `57.84 MiB` |
| `libblaze_prepare.so` | `release` (`opt-level=3`, `lto=fat`, `panic=abort`, `strip=true`) | `2,846,936` | `2.72 MiB` |
| `libblaze_prepare.so` inside release APK | `DEFLATED` compressed entry (`lib/arm64-v8a/`) | `1,511,576` | `1.44 MiB` |
| `libhorizon_android.so` | `fast` -> `release` (unchanged by `blaze-prepare`) | `81,135,272` -> `52,018,200` | `49.61 MiB` (`24.32 MiB` compressed) |
| Total Release APK (`BlazeLauncher-*.apk`) | Stage A (`43658119350`) | `380,253,633` | `362.64 MiB` |
| Total Release APK (`BlazeLauncher-*.apk`) | Stage B (`43664247413`) | `381,872,488` | `364.18 MiB` |
| **Net APK Delta (Stage B)** | `libblaze_prepare.so` + 26 MIT `.java` + lock assets + Kotlin/DEX | **`+1,618,855`** | **`+1.54 MiB`** |

**Conclusion**: Shipping pure Rust `libblaze_prepare.so` (`arm64-v8a` release LTO+stripped) adds only **`+1.44 MiB` compressed native code** (`+1.54 MiB` total APK delta) with **zero Python runtime**, **zero OpenSSL** (`ureq` + `rustls`), and **zero pre-generated game data**.

---

## 2. 4,000-File Synthetic Parity Benchmark (Rust vs Kotlin vs Python)

Each implementation executes the exact same 4-stage synthetic workload modeled after `26.3` client extraction & `engine-data.zip` bundling:
1. Build a compressed archive (`in.jar`) containing 4,000 JSON files with CRC-32 ground truth.
2. Extract all 4,000 entries to disk (`extract_ms`).
3. Re-read all 4,000 extracted files and verify CRC-32 (`verify_ms`).
4. Pack all 4,000 files into an output ZIP (`engine-data.zip`, `zip_ms`) and measure process memory.

### 2A. CI Runner `--release` Comparison (Run [`43683847378`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43683847378) & [`43683847329`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43683847329))

| Implementation | Runner / Profile | `build_ms` | `extract_ms` | `verify_ms` | `zip_ms` | Total I/O Stages (`extract+verify+zip`) | Memory Metric |
|---|---|---|---|---|---|---|---|
| **Rust (`blaze-prepare`)** | `ubuntu-latest` (`x86_64`), `cargo test --release` | `71` | `1516` | `52` | `113` | `1681 ms` | `7,512 KiB` (`VmHWM`) |
| **Python 3** | `ubuntu-latest` (`x86_64`), CPython `zipfile` + C `zlib` | `245` | `347` | `63` | `230` | `640 ms` | `19,452 KiB` (`ru_maxrss`) |
| **Kotlin (JVM)** | `ubuntu-latest` (`x86_64`), OpenJDK 21 `java.util.zip` | `57` | `162` | `81` | `114` | `357 ms` | `52,122 KiB` (`heap delta`; excludes full JVM RSS) |
| **Rust (`blaze-prepare`)** | `ubuntu-24.04-arm` (`aarch64`), `cargo test --release` | `46` | `2482` | `32` | `72` | `2586 ms` | `6,488 KiB` (`VmHWM`) |
| **Python 3** | `ubuntu-24.04-arm` (`aarch64`), CPython `zipfile` + C `zlib` | `178` | `175` | `56` | `146` | `377 ms` | `17,344 KiB` (`ru_maxrss`) |

### 2B. Earlier Debug (`opt-level=0`) Reference Runs (`43664247483` / `43664247413` & Termux `SM-N975F`)

Preserved for comparison against the earlier unoptimized debug runs (`opt-level=0` had slower Deflate/Inflate and higher peak RSS than `--release`; on-device `--release` timings on physical `SM-N975F` remain `UNVERIFIED`):

| Implementation | Environment / Profile | `build_ms` | `extract_ms` | `verify_ms` | `zip_ms` | Peak Memory |
|---|---|---|---|---|---|---|
| **Rust (`blaze-prepare`)** | `ubuntu-latest` (`x86_64`), `cargo test` (`opt-level=0`) | `1074` | `1862` | `92` | `977` | `15,544 KiB` (`VmHWM`) |
| **Rust (`blaze-prepare`)** | Local Termux `aarch64`, `cargo test --offline` (`opt-level=0`) | `2428` | `4907` | `347` | `3349` | `15,516 KiB` (`VmHWM`) |
| **Python 3** | Local Termux `aarch64`, CPython + C `zlib` | `967` | `1384` | `389` | `1384` | `21,272 KiB` (`ru_maxrss`) |

### 2C. Engineering Analysis of the Numbers

1. **Where Rust (`blaze-prepare --release`) is faster**:
   - Under `--release` (`opt-level=3`, `lto=fat`), Rust beats CPython `zipfile` on archive creation (`build_ms`: `71 ms` vs `245 ms` on `x86_64`, `46 ms` vs `178 ms` on `aarch64`), CRC-32 re-read verification (`verify_ms`: `52 ms` vs `63 ms` on `x86_64`, `32 ms` vs `56 ms` on `aarch64`), and output ZIP packing (`zip_ms`: `113 ms` vs `230 ms` on `x86_64`, `72 ms` vs `146 ms` on `aarch64`), matching Kotlin `java.util.zip` on `zip_ms` (`113 ms` vs `114 ms` on `x86_64`).
   - Process high-water memory (`VmHWM`) during `--release` execution was `7,512 KiB` (`x86_64`) and `6,488 KiB` (`aarch64`). Note that `VmHWM`, CPython `ru_maxrss` (`19,452 KiB` / `17,344 KiB`), and Kotlin `heap delta` (`52,122 KiB`, which excludes JVM metaspace and native buffers) measure different scopes and are reported as-measured rather than as a single percentage ratio.
2. **Where Rust (`blaze-prepare --release`) is slower**:
   - **Per-file disk extraction (`extract_ms`) is substantially slower in Rust**: `1,516 ms` vs Python `347 ms` (`~4.4x` slower) and Kotlin `162 ms` (`~9.4x` slower) on `x86_64`, and `2,482 ms` vs Python `175 ms` (`~14.2x` slower) on `aarch64`. Because `extract_ms` dominates `extract+verify+zip`, Rust's total I/O stage time (`1,681 ms` on `x86_64`, `2,586 ms` on `aarch64`) is slower than both Python (`640 ms` / `377 ms`) and Kotlin (`357 ms` on `x86_64`).
   - **Code-verified reason**: `extract_client_jar_resources` (`blaze-prepare/src/resources.rs:297-313`) writes each extracted file through `atomic_write_bytes` (`blaze-prepare/src/files.rs:133-155`), which creates a sibling `.partial` file, writes the entry, calls `file.sync_all()` (`fsync`) on every single file, and calls `fs::rename`. By contrast, the Python (`ZipFile.extractall`) and Kotlin (`java.util.zip`) benchmarks stream directly to target files without per-file `.partial` creation, `sync_all()`, and `rename`.
3. **Practical trade-offs for Android**:
   - Per-file atomic staging makes Phase 1/2 resilient to cancellation and mid-write kills, at a clear extraction-throughput cost (especially when `file.sync_all()` runs on every small JSON file rather than batching). If on-device `SM-N975F` testing shows `extract_client_jar_resources` is a major bottleneck, relaxing per-entry `sync_all()` (keeping `.partial` + `rename` or syncing only final stamp/zip outputs) is a concrete future optimization (`UNVERIFIED`).
   - Meanwhile, keeping HTTP downloads, extraction, and ZIP packaging in `libblaze_prepare.so` avoids bundling a Python runtime in the APK and runs heavy bytecode work (vanilla `net.minecraft.data.Main --all` and Starling's 26 MIT parity `.java` exporters) strictly in ZL2's isolated `:jvm` process (`Jre.JRE_25` `"Internal-25"`).

---

## 3. Why Not Pure-Kotlin or Bundled Python?

| Option | Verdict in Blaze Launcher | Key Trade-off Observed |
|---|---|---|
| Bundled Python + stdlib + C extensions | **Rejected** | `+20–40 MiB` APK bloat, cross-compile maintenance across NDK API 28, complex cancellation/process cleanup |
| Pure-Kotlin downloader + ZIP/SHA pipeline | **Partial (control plane only)** | Fast C `zlib` on desktop JVM, but stresses launcher `:app` ART heap alongside Compose UI & Ktor, lacks clean native cancellation/partial rollback semantics vs Rust RAII |
| **Pure-Rust `libblaze_prepare.so` + `:jvm` datagen (`Stage B`)** | **Implemented** | **`+1.54 MiB` APK delta**, **`6,488–7,512 KiB` `VmHWM` (`--release`)** (`~15.5 MiB` `opt-level=0`), faster archive build/verify/zip under `--release` (`build_ms`/`zip_ms` competitive with Kotlin) while `extract_ms` is slower due to per-file `.partial`+`sync_all()`+`rename`, reuses ZL2 `<gameHome>` + `Internal-25` `:jvm`, passes Check 11 hygiene guards |
