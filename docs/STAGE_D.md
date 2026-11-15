# Stage D Delivery Report (`docs/STAGE_D.md`)

> **Status**: COMPLETE (CI-Verified on commit `3c78d39a`; physical `SM-N975F` tests marked `UNVERIFIED`)
>
> **Legal disclaimer**: All account-ownership enforcement and binary/asset packaging notes below are **technical screening, not legal advice**.

---

## Summary Verdicts (`D1–D7`)

| Item | Requirement | Verdict | Evidence (`file:line` / CI Output) |
|---|---|---|---|
| **D1** | `VersionConfig` & `Version` `runtime` field (`java` default, `rust` for Rust Edition; backward-compatible GSON/Parcelable) | **PASS** | [`VersionConfig.kt:106-110,253,341-351`](../ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/version/installed/VersionConfig.kt), [`Version.kt:232`](../ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/version/installed/Version.kt), [`StageDIntegrationTest.kt:52-82,148-171`](../ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/StageDIntegrationTest.kt) |
| **D2** | Data-driven compatibility from `assets/research-lock.json` (`26.3`) | **PASS** | [`RustEditionCatalog.kt:29-59`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustEditionCatalog.kt), [`StageDIntegrationTest.kt:33-50`](../ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/StageDIntegrationTest.kt) |
| **D3** | Normal Game Download UI `Runtime` selector (`Java Edition` vs `Rust Edition (Experimental)` on `26.3`, Java mod loaders disabled/cleared, `-Rust` default version name) | **PASS** (`UNVERIFIED` UI on device) | [`GameDownloadInfo.kt:49`](../ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/download/game/GameDownloadInfo.kt), [`DownloadGameWithAddonScreen.kt:267-370,807`](../ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/screens/content/download/game/DownloadGameWithAddonScreen.kt), EN/ID `strings.xml` |
| **D4** | Normal Installer Stage B integration (`Install.RustEngineData` task invoking `EngineDataPreparer.prepareFromInstaller`, skipping Java mod loaders, persisting `runtime="rust"`) | **PASS** (`UNVERIFIED` on device) | [`GameInstaller.kt:393-405`](../ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/download/game/GameInstaller.kt), [`EngineDataPreparer.kt:228-335,681`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/EngineDataPreparer.kt) |
| **D5** | Installed versions `"Rust Edition"` pill badge | **PASS** (`UNVERIFIED` UI on device) | [`VersionsManageElements.kt:950`](../ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/screens/content/elements/VersionsManageElements.kt) |
| **D6** | Normal Play launch dispatch (`RustLaunchDispatcher`) requiring verified Microsoft account, blocking offline/non-MS/offline-skip/JVM `GameLauncher`, and launching `:rust` (`RustActivity`) | **PASS** (`UNVERIFIED` on device) | [`RustLaunchDispatcher.kt:38-119`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustLaunchDispatcher.kt), [`RustSessionHandoff.kt:104-145`](../ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustSessionHandoff.kt), [`GameLaunchFlow.kt:199-245`](../ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/GameLaunchFlow.kt), [`LauncherElements.kt:307-315,434-550`](../ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/screens/content/elements/LauncherElements.kt), [`GameLauncher.kt:102-104`](../ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/GameLauncher.kt), [`StageDIntegrationTest.kt:84-146`](../ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/rustruntime/StageDIntegrationTest.kt) |
| **D7** | Clean removal of temporary `RustEditionNavKey` rail tab from `MainScreen.kt` (`4` lines removed `<= 10`) & Scope Guard allowlist | **PASS** | [`MainScreen.kt`](../ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/screens/main/MainScreen.kt), [`scripts/scope_guard.sh:60-62`](../scripts/scope_guard.sh) |

---

## Stage D Implementation & Hook Points

1. **Hook 1 — Version Runtime Model (`VersionConfig.kt` & `Version.kt`)**:
   - Added `enum class GameRuntime(val wireName: String)` (`JAVA("java")`, `RUST("rust")`) with `GameRuntime.fromWire(String?)` normalizing `null`, blank, or unrecognized values back to `JAVA`.
   - Preserved full backward compatibility for existing `version.config` JSON files (`StageDIntegrationTest` verifies legacy JSON without `"runtime"`, explicit `null`, and `"rust"` serialized by ZL2 pretty GSON).
2. **Hook 2 — Installer & Version Catalog (`RustEditionCatalog.kt`, `DownloadGameWithAddonScreen.kt`, `GameInstaller.kt`)**:
   - `RustEditionCatalog.supportedMinecraftVersion(context)` parses `minecraft.version` from bundled `assets/research-lock.json` (generated from `engine/starling/research-lock.json` by Gradle; fallback `"26.3"`).
   - Selecting `26.3` in `Download -> Game` unlocks a `Runtime` card (`Java Edition` vs `Rust Edition (Experimental)`). Choosing `Rust Edition` automatically hides/clears Java mod loaders (`OptiFine`, `Forge`, `NeoForge`, `Fabric`, `Quilt`, `Cleanroom`) and pre-fills `<version>-Rust` (e.g. `26.3-Rust`).
   - `GameInstaller` downloads Mojang base files (reusing ZL2 `<gameHome>`) and executes task `Install.RustEngineData` (`EngineDataPreparer.prepareFromInstaller`), then writes `version.config` with `runtime = "rust"`.
3. **Hook 3 — Verified-Microsoft Launch Dispatch (`RustLaunchDispatcher.kt`, `GameLaunchFlow.kt`, `LauncherElements.kt`, `GameLauncher.kt`)**:
   - Refuses local/offline accounts in `LauncherElements.kt:307-315` (`R.string.rust_microsoft_account_required`).
   - Never sets `version.offlineAccountLogin` on `GameRuntime.RUST`, skips JVM renderer/plugin warning dialogs, and disables the `Skip` button (`allowSkip = false`) in `AccountRefreshFailedDialog` so unverified Microsoft launches cannot proceed offline.
   - `GameLaunchFlow` verifies Microsoft session freshness, prepares missing engine data on demand via `EngineDataPreparer.prepareFromInstaller`, and launches `RustActivity` (`:rust`) through `RustLaunchDispatcher.launchRustVersion` using `RustSessionHandoff.prepareAccountLaunch`.
   - `GameLauncher.launch()` carries an explicit `check(version.getRuntime() != GameRuntime.RUST)` invariant preventing accidental JVM startup.
   - Note: Developer Mode (`Pengembang`) spike buttons remain gated behind the runtime 5-tap unlock for isolated hardware diagnostics and troubleshooting.

---

## Raw Verification Evidence (`3c78d39a`)

Note on Stage D compile fix: initial commit `99245fe5` failed `Parallel Fast Checks` (`43672935132`) because `ErrorViewModel.ThrowableMessage` requires `AndroidStringText` and `EngineDataPreparer.isPreparedFallback` was `private`; obsolete `CI` (`43672935061`) was cancelled and commit `3c78d39a` fixed all 5 compile errors cleanly.

- **`Parallel Fast Checks` Run**: [`https://github.com/kaito65452/BlazeLauncher/actions/runs/43673263356`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43673263356) (`status: completed`, `conclusion: success`)
  - `Guards & Self-Tests` (`132687753959`): `success` in `1m36s`
  - `JVM Unit Tests` (`132687753742`): `success` in `6m44s`
- **`CI` Run**: [`https://github.com/kaito65452/BlazeLauncher/actions/runs/43673263364`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43673263364) (`status: completed`, `conclusion: success`)
  - `engine / engine` (`132687753735`): `success` in `27m0s` — Artifact `horizon-engine-arm64` (ID `14146545637`, `28,102,434` bytes)
  - `ci` (`132693712619`): `success` in `13m12s` — Artifact `signed-apks` (ID `14146368186`, `378,283,243` bytes)

```text
Guards & Self-Tests (43673263356):
=== Summary: PASS=39 FAIL=0 ===
=== Scope Guard Checks Passed ===

Engine Build (43673263364 / 132687753735):
libhorizon_android.so before (fast, unstripped): 81115248 bytes
libhorizon_android.so after (release, lto=fat, panic=abort, strip=true): 52019160 bytes
libblaze_prepare.so before (fast, unstripped): 60645504 bytes
libblaze_prepare.so after (release, lto=fat, panic=abort, strip=true): 2846936 bytes

CI Release Verification (43673263364 / 132693712619):
=== Summary: PASS=39 FAIL=0 ===
=== Scope Guard Checks Passed ===
Building with versionCode=27490042 versionName=2.6_hotfix-***.3c78d39a
BUILD SUCCESSFUL in 10m 20s
✓ Confirmed zero debug APKs exist under ZalithLauncher/build/outputs/apk/debug.
Scanning 157 shared libraries under lib/*/*.so...
✓ Release APK manifest verified non-debuggable (android:debuggable=false).
✓ APK license hygiene check passed (157 .so files scanned).
apkanalyzer manifest debuggable (ZalithLauncher/build/outputs/apk/release/BlazeLauncher-2.6_hotfix-***.3c78d39a.apk): false
APK size report for ZalithLauncher/build/outputs/apk/release/BlazeLauncher-2.6_hotfix-***.3c78d39a.apk: total=381884000 bytes (364.19 MiB)
  entry=lib/arm64-v8a/libhorizon_android.so uncompressed=52019152 bytes (49.61 MiB) compressed=25505557 bytes (24.32 MiB)
  entry=lib/arm64-v8a/lib***_prepare.so uncompressed=2846936 bytes (2.72 MiB) compressed=1511576 bytes (1.44 MiB)
V2 Signer: certificate SHA-256 digest: 5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc
✓ Signing certificate SHA-256 matched 5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc.
Nightly versionCode: 27321741
✓ Verification passed: Produced versionCode (27490042) > Nightly versionCode (27321741).
```

---

## `UNVERIFIED` Items (Physical `SM-N975F` Required)

1. End-to-end UI flow in Download Game -> `26.3` -> `Runtime: Rust Edition (Experimental)` -> `Instal` -> `26.3-Rust` badge appearance in Versions Manager.
2. Live Play button dispatch (`Luncurkan`) launching `:rust` (`RustActivity`) with a verified Microsoft account, and live UI block when switching to an offline/local account or dropping network before verification.
3. Interactive hardware rendering, `BlazePerf` timings, SAF document picker (`4101`), `adjustResize` IME, and cutout/gesture behavior inside `:rust` launched from a normal `26.3-Rust` version entry on `SM-N975F`.
