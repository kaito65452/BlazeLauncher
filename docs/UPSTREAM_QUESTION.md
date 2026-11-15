# Draft Upstream Issue for `starling-client/starling` (`android` branch)

> **Status**: DRAFT ONLY — **do not post** automatically. Review locally before deciding whether to open an issue on [`starling-client/starling`](https://github.com/starling-client/starling).
>
> **Note**: Any licensing or distribution remarks below are **technical screening, not legal advice**.

---

## Proposed Issue Title

`[Android / Downstream Integration] Clarifications on engine-data.zip lifecycle, runtime bootstrap, external auth handoff, and public redistribution boundaries`

## Proposed Issue Body

Hello Starling maintainers,

We are integrating `starling-client/starling` (`android` branch, pinned at `fa548eb7abe91c194d3780e4580b9746323cd606`) as an optional out-of-process (`android:process=":rust"`) `NativeActivity` runtime in **Blaze Launcher** (`GPL-3.0-or-later`). Our Kotlin launcher performs Microsoft/Minecraft authentication under its own identity, avoids embedding OFL fonts or Mojang assets inside the APK, and passes sessions strictly in memory via JNI. Could you clarify four questions?

### (a) Building and distributing `engine-data.zip`
In `.github/workflows/android-dev.yml`, upstream generates `engine-data-generated.zip` via `tools/android_prepare.py` + `tools/android_build.py --public` and bundles it as `assets/engine-data.zip`. When downstream APKs omit `assets/engine-data.zip` (to avoid distributing Mojang-derived extracts in CI artifacts), is there an upstream-supported way to build or bootstrap those generated tables separately from APK compilation?

### (b) Runtime bootstrap on device
When `assets/engine-data.zip` is absent, tapping install/retry hits `launcher.rs:634-638` (*"This APK is missing its engine data. Install a complete build."*), even though `official_install.rs` already verifies and fetches vanilla assets from Mojang. Is there a planned path for Android clients to generate or import the `horizon-prepare` tables on-device without bundling `engine-data.zip` in the APK?

### (c) Cargo features for downstream auth/fonts instead of patches
Today we apply build-time patches (`engine/patches/0001..0006`) on top of `fa548eb7abe9` to load OFL `.ttf` fonts at runtime (`0001`), disable Starling's embedded OAuth/account switcher (`catalog.rs:22`, `android/login.rs`, `launcher/src/account*.rs`), and add our in-memory session/direct-launch JNI bridge. Would upstream consider Cargo feature flags (e.g., `runtime-fonts`, `external-auth-only`) so downstream launchers do not need to maintain source patches?

### (d) Branch stability and generated-table status *(technical screening, not legal advice)*
As the `android` branch freezes per `README.md` (*"Horizon Android is now Starling"*), which `starling` branch/tag should downstream integrators track next, how stable is the `horizon-prepare` layout (`SCHEMA = 7`, `OUTPUT_VERSION = 3`), and does upstream regard the 28 generated `.json`/`.nbt` files in `tools/android_build.py:49-68` as Starling-authored functional schemas or vanilla-derived extracts?

Thank you for maintaining Starling!
