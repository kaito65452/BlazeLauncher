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

### 2A. CI Runner Comparison (`ubuntu-latest`, `43664247483` & `43664247413`)

| Implementation | Source / Profile | `build_ms` | `extract_ms` | `verify_ms` | `zip_ms` | Total I/O Stages (`extract+verify+zip`) | Peak Memory |
|---|---|---|---|---|---|---|---|
| **Rust (`blaze-prepare`)** | `cargo test` (`opt-level=0`, pure-Rust `flate2` `rust_backend`, `BENCH_RUST_4000`) | `1074` | `1862` | `92` | `977` | `2931 ms` | **`15,544 KiB` (`VmHWM`)** |
| **Kotlin (JVM)** | OpenJDK 21 C `zlib` (`java.util.zip`, `BENCH_KOTLIN_4000`) | `77` | `113` | `68` | `81` | `262 ms` | `47,490 KiB` (`heap delta`; excludes full JVM RSS) |
| **Python 3** | CPython `zipfile` backed by C `zlib` (`BENCH_PYTHON_4000`) | `270` | `335` | `62` | `221` | `618 ms` | `19,236 KiB` (`ru_maxrss`) |

### 2B. Local Phone Termux Comparison (`aarch64` `SM-N975F` userspace)

| Implementation | Profile | `build_ms` | `extract_ms` | `verify_ms` | `zip_ms` | Peak Memory |
|---|---|---|---|---|---|---|
| **Rust (`blaze-prepare`)** | `cargo test --offline` (`opt-level=0` debug!) | `2428` | `4907` | `347` | `3349` | **`15,516 KiB` (`VmHWM`)** |
| **Python 3** | Termux CPython + C `zlib` | `967` | `1384` | `389` | `1384` | `21,272 KiB` (`ru_maxrss`) |

### 2C. Engineering Analysis of the Numbers

1. **Memory predictability**: Rust (`15.5 MiB` `VmHWM`) uses the smallest fixed OS process footprint of all three runtimes (`~19%` less RSS than bare CPython before third-party wheels, and avoids inflating Android's main `:app` ART heap where `512 MiB` heap pressure risks `OutOfMemoryError` or LMK kills alongside `:jvm`).
2. **Why `cargo test` (`opt-level=0`) timed higher on Deflate/Inflate than C `zlib`**:
   - `cargo test` compiles `blaze-prepare` and `flate2`'s pure-Rust `miniz_oxide` backend (`zip` default feature `deflate-flate2` without `--release`) with **`opt-level=0`** (unoptimized debug loop checks).
   - Both Python's `zipfile` and JVM's `java.util.zip` delegate Deflate/Inflate natively to pre-compiled `-O2`/`-O3` **C `zlib`**, plus `BENCH_KOTLIN_4000` measured `java.util.zip` on a desktop OpenJDK 21 runner without ART/Android Scoped Storage overhead.
   - Note that the APK ships **`libblaze_prepare.so` built with `--release` (`opt-level=3`, `lto=fat`, `codegen-units=1`)**, not debug profile; `--release` synthetic benchmark timings on `SM-N975F` were not captured in this offline run (`UNVERIFIED`).
3. **Why Rust remains optimal for Android Phase 1 & Phase 2**:
   - Deterministic `VmHWM` ceiling (~`15 MiB`), zero ART GC pauses or main-process OOM risk when copying/verifying thousands of Mojang asset hashes (`34.json`), atomic `.partial` cleanup on cancellation, and direct in-process JNI progress reporting without spawning Python or blowing APK size.
   - OpenJDK 25 runs **only** for what actually requires Java bytecode execution (vanilla `net.minecraft.data.Main --all` and Starling's 26 MIT parity `.java` exporters) inside ZL2's isolated `:jvm` process (`512–768 MiB` heap cap, `Jre.JRE_25` `"Internal-25"`).

---

## 3. Why Not Pure-Kotlin or Bundled Python?

| Option | Verdict in Blaze Launcher | Key Trade-off Observed |
|---|---|---|
| Bundled Python + stdlib + C extensions | **Rejected** | `+20–40 MiB` APK bloat, cross-compile maintenance across NDK API 28, complex cancellation/process cleanup |
| Pure-Kotlin downloader + ZIP/SHA pipeline | **Partial (control plane only)** | Fast C `zlib` on desktop JVM, but stresses launcher `:app` ART heap alongside Compose UI & Ktor, lacks clean native cancellation/partial rollback semantics vs Rust RAII |
| **Pure-Rust `libblaze_prepare.so` + `:jvm` datagen (`Stage B`)** | **Implemented** | **`+1.54 MiB` APK delta**, **`~15.5 MiB` `VmHWM`**, reuses ZL2 `<gameHome>` + `Internal-25` `:jvm`, passes Check 11 zero-Python / zero-Mojang hygiene guards |
