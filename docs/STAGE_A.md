# Stage A Delivery Report (`docs/STAGE_A.md`)

> **Status**: COMPLETE (CI-Verified on commit `17d94b41`; physical SM-N975F checks marked `UNVERIFIED`)
>
> **Legal disclaimer**: All licensing, redistribution, and upstream-compliance notes below are **technical screening, not legal advice**.

---

## Summary Verdicts (`A1–A6`)

| Item | Requirement | Verdict | Evidence (`file:line` / Command Output) |
|---|---|---|---|
| **A1** | Neutralize engine Microsoft/Xbox/Minecraft/Ely.by OAuth login & refresh (`00000000441cc96b`, `00000000402b5328` unreachable; `setSession` memory-only) | **GO** (`UNVERIFIED` on phone) | `engine/patches/0004-neutralize-engine-login.patch`, `engine/patches/0003-phase1a-bridge-diagnostics.patch:141-188` (`nativeSetSession`), `ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustDiagnosticsCollector.kt:46-51`, `scripts/guard.sh` Check 10 & 10b binary `.so` scan |
| **A2** | Strict UUID/token redaction everywhere (UUID first 8 chars only, token length only; zero raw tokens or full 32-hex UUIDs in logs/reports) | **GO** (`UNVERIFIED` on phone) | `engine/patches/0003-phase1a-bridge-diagnostics.patch:181-187,268-274` (`uuid_prefix={}...`, `token_len={}`), `ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustDiagnosticsCollector.kt:43-62`, `scripts/guard.sh:552-554`, `ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/RustSessionHandoffTest.kt:134-204` |
| **A3** | Surface explicit `"engine login: disabled"` policy state in Developer `"Copy report"` | **GO** (`UNVERIFIED` on phone) | `ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustDiagnosticsCollector.kt:46-51,115-117`, `ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/RustSessionHandoffTest.kt:196-197` |
| **A4** | Explain `"This APK is missing its engine data. Install a complete build."` & surface `<filesDir>`/`engine-data.zip` check in `"Copy report"` | **GO** (`UNVERIFIED` on phone) | `ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustDiagnosticsCollector.kt:85-100,120-121`, `ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/RustSessionHandoffTest.kt:198-227` |
| **A5** | Complete `docs/UPSTREAM_QUESTION.md` covering `(a)–(d)` with DRAFT-ONLY banner (saved locally, **not posted**) | **GO** | `docs/UPSTREAM_QUESTION.md:1-58` (covers banner, `technical screening, not legal advice`, `(a)` `engine-data-generated.zip`, `(b)` runtime bootstrapping vs missing APK asset, `(c)` `external-auth`/Cargo features/attribution, `(d)` `"Horizon Android is now Starling"` migration & schema/derived-table status) |
| **A6** | Self-tested CI guards + release-signed non-debuggable APK (`arm64-v8a`, `debuggable=false`, cert `5aa533d1…`, `versionCode > nightly`) | **GO** | `scripts/test_guards.sh:470-511` (`PASS=35 FAIL=0`), CI Run `43658119350`, `Parallel Fast Checks` Run `43658119250` |

---

## Stage A Code Changes (Commit `17d94b41` on `feat/overnight-stages`)

1. **[`engine/patches/0003-phase1a-bridge-diagnostics.patch`](../engine/patches/0003-phase1a-bridge-diagnostics.patch)** (`lines 181-187`, `268-274`):
   - Changed `BlazeSessionHandoff` logs on `:rust` (`staged AuthSession` and `restored staged AuthSession`) so UUIDs emit only `uuid_prefix={}...` (first 8 hex characters via `&uuid_hex[..8]`) and tokens emit only `token_len={}` (never full 32-hex UUIDs or raw token bytes).
2. **[`ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustDiagnosticsCollector.kt`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustDiagnosticsCollector.kt)** (`lines 43-62`, `115-121`):
   - Added `DASHED_UUID_REGEX` and `HEX_32_UUID_REGEX` (strict word/hex boundaries so 40-hex SHA-1 hashes remain untouched) replacing any UUID with `<8-hex>...`.
   - Added `ENGINE_LOGIN_ENABLED = false`, `engineLoginStatusLine()`, and `=== Engine Login Policy ===` section in `collectRustDiagnosticsFromDirs`.
3. **[`ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/RustSessionHandoffTest.kt`](../ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/RustSessionHandoffTest.kt)** (`lines 134-227`):
   - Verified fake JWT & opaque access tokens and full dashed/undashed UUIDs are redacted from `"Copy report"`, while `12345678...`, `token_len=...`, 40-hex SHA-1 hashes, `"engine login: disabled"`, and `"Engine data: expected path=..."` are preserved.
4. **[`scripts/guard.sh`](../scripts/guard.sh)** (`lines 552-554`) & **[`scripts/test_guards.sh`](../scripts/test_guards.sh)**:
   - Hardened Check 10 to reject any `BlazeSessionHandoff` log formatting full `{:032x}` / `uuid={:032x}` instead of `uuid_prefix`, backed by a negative self-test (`PASS: Check 10 rejects full 32-hex UUID in BlazeSessionHandoff log`).
5. **[`docs/UPSTREAM_QUESTION.md`](UPSTREAM_QUESTION.md)** (`lines 1-58`):
   - Completed draft issue covering all four clarification items `(a)–(d)` with DRAFT-ONLY notice and `"technical screening, not legal advice"` disclaimer.

---

## Raw Verification Evidence

### CI Runs & Artifacts (Commit `17d94b41`)

- **`Parallel Fast Checks` Run**: [`https://github.com/kaito65452/BlazeLauncher/actions/runs/43658119250`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43658119250) (`status: completed`, `conclusion: success`)
  - `Guards & Self-Tests` (`132641136363`): `success`
  - `JVM Unit Tests` (`132641136139`): `success`
- **`CI` Run**: [`https://github.com/kaito65452/BlazeLauncher/actions/runs/43658119350`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43658119350) (`status: completed`, `conclusion: success`)
  - `engine / engine` (`132641136252`): `success`
    - Artifact: `horizon-engine-arm64` (ID `14139338339`, `26531754` bytes)
  - `ci` (`132647867635`): `success`
    - Artifact: `signed-apks` (ID `14140386606`, `376663715` bytes)

Raw log excerpts from `gh run view`:

```text
Guards & Self-Tests	Run Guards, Self-Tests, and Scope Guard	2026-11-15T00:27:12.1999043Z === Summary: PASS=35 FAIL=0 ===
Guards & Self-Tests	Run Guards, Self-Tests, and Scope Guard	2026-11-15T00:27:12.2090280Z ✓ All changed files match scope guard allowlist.
Guards & Self-Tests	Run Guards, Self-Tests, and Scope Guard	2026-11-15T00:27:12.2090280Z === Scope Guard Checks Passed ===

engine / engine	UNKNOWN STEP	2026-11-15T00:51:28.7704584Z libhorizon_android.so before (fast, unstripped): 81135424 bytes
engine / engine	UNKNOWN STEP	2026-11-15T00:51:28.7705553Z libhorizon_android.so after (release, lto=fat, panic=abort, strip=true): 52018200 bytes

ci	Run Tests and Assemble Release	2026-11-15T00:53:38.8251787Z Building with versionCode=27478418 versionName=2.6_hotfix-***.17d94b41
ci	Run Tests and Assemble Release	2026-11-15T01:02:05.6470099Z BUILD SUCCESSFUL in 8m 26s
ci	Verify No Debug APK Artifacts Built	2026-11-15T01:02:06.2873144Z ✓ Confirmed zero debug APKs exist under ZalithLauncher/build/outputs/apk/debug.
ci	Verify APK License Hygiene and Non-Debuggable Release	2026-11-15T01:03:10.0167452Z Scanning 156 shared libraries under lib/*/*.so...
ci	Verify APK License Hygiene and Non-Debuggable Release	2026-11-15T01:03:10.0169603Z ✓ Release APK manifest verified non-debuggable (android:debuggable=false).
ci	Verify APK License Hygiene and Non-Debuggable Release	2026-11-15T01:03:10.0170519Z ✓ APK license hygiene check passed (156 .so files scanned).
ci	Verify APK License Hygiene and Non-Debuggable Release	2026-11-15T01:03:12.3248169Z apkanalyzer manifest debuggable (ZalithLauncher/build/outputs/apk/release/BlazeLauncher-2.6_hotfix-***.17d94b41.apk): false
ci	Verify APK License Hygiene and Non-Debuggable Release	2026-11-15T01:03:12.3522383Z APK size report for ZalithLauncher/build/outputs/apk/release/BlazeLauncher-2.6_hotfix-***.17d94b41.apk: total=380253633 bytes (362.64 MiB)
ci	Verify APK License Hygiene and Non-Debuggable Release	2026-11-15T01:03:12.3523300Z   entry=lib/arm64-v8a/libhorizon_android.so uncompressed=52018192 bytes (49.61 MiB) compressed=25504813 bytes (24.32 MiB)
ci	Verify Signing Certificate SHA-256	2026-11-15T01:03:13.5775804Z V2 Signer: certificate SHA-256 digest: 5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc
ci	Verify Signing Certificate SHA-256	2026-11-15T01:03:13.5778615Z ✓ Signing certificate SHA-256 matched 5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc.
ci	Verify Monotonic versionCode Against Nightly Release	2026-11-15T01:03:22.4575242Z Nightly versionCode: 27321741
ci	Verify Monotonic versionCode Against Nightly Release	2026-11-15T01:03:22.4576086Z Comparing versionCode: Produced (27478418) vs Nightly (27321741)...
ci	Verify Monotonic versionCode Against Nightly Release	2026-11-15T01:03:22.4577201Z ✓ Verification passed: Produced versionCode (27478418) > Nightly versionCode (27321741).
```

---

## `UNVERIFIED` Items (Physical SM-N975F Only)

1. Runtime confirmation on Samsung Galaxy Note 10+ (`SM-N975F`, Android 12) that Developer `"Copy report"` displays `engine login: disabled` and redacts live `AuthSession` / `logcat` lines (showing 8-char UUID prefix and token length only).
2. Runtime confirmation on device that no OAuth/device-code/Ely.by flow can be triggered inside `:rust`.
