# Draft Upstream Issue for `starling-client/starling` (`android` branch)

> **Status**: DRAFT ONLY — **do not post** automatically. Review locally before deciding whether to open an issue on [`starling-client/starling`](https://github.com/starling-client/starling).
>
> **Note**: Any licensing or distribution remarks below are **technical screening, not legal advice**.

---

## Proposed Issue Title

`[Android / Downstream Integration] Clarifications on engine-data.zip lifecycle, runtime bootstrap, external auth handoff, and public redistribution boundaries`

## Proposed Issue Body

Hello Starling maintainers,

We are working on integrating the Starling engine (`starling-client/starling`, branch `android` pinned at `fa548eb7abe91c194d3780e4580b9746323cd606`) as an optional out-of-process (`android:process=":rust"`) `NativeActivity` backend inside an open-source (`GPL-3.0-or-later`) Android Minecraft: Java Edition launcher (**Blaze Launcher**).

In our downstream integration, our Java/Kotlin launcher already handles Microsoft / Xbox Live / XSTS / Minecraft authentication under our own launcher identity, never bundles Mojang proprietary textures/JARs/sounds/wallpapers/skins or embedded OFL font bytes inside `libhorizon_android.so`, and wants the embedded engine to act strictly as an isolated execution/render target using an in-memory session handoff.

We inspected `.github/workflows/android-dev.yml`, `tools/android_prepare.py`, `tools/android_build.py`, `crates/horizon-client/src/official_install.rs`, `crates/horizon-client/src/android_main.rs`, `crates/horizon-client/src/android/launcher.rs`, and `docs/microsoft-account.md`. Before we design our next integration phase, could you clarify the following four points?

### (a) How `engine-data.zip` / `official-assets` is built and expected in public APKs
In `.github/workflows/android-dev.yml` (`lines 105-115`), upstream runs:
1. `tools/android_prepare.py` (which downloads vanilla `26.3` client/server JARs and assets using `research-lock.json` and runs `horizon-prepare` on desktop Java 25 into `.local/horizon-client/26.3`), and
2. `tools/android_build.py --public` (which packages 28 allowlisted generated JSON/NBT files into `engine-data-generated.zip` and bundles it as `assets/engine-data.zip` inside the APK).

Is there an upstream-supported way to obtain or verify a distributable `engine-data.zip` (`official-assets`) for downstream CI without invoking full Mojang JAR downloads during APK compilation, or is every APK intended for public release expected to embed `engine-data-generated.zip` produced by `tools/android_prepare.py`?

### (b) Bootstrapping engine data at runtime without bundling `assets/engine-data.zip`
When an APK omits `assets/engine-data.zip`, `android_main.rs:222` finds no asset to unpack to `<filesDir>/engine-data.zip`, `horizon_client::official_install::ready(&self.home)` returns `false`, and tapping `"Download Minecraft 26.3"` / `"Retry installation"` (`launcher_pages.rs:1518-1527`) immediately returns an error at `launcher.rs:634-638`:

```rust
if !self.home.join("engine-data.zip").is_file() {
    return Err("This APK is missing its engine data. Install a complete build.".into());
}
```

Wait: `crates/horizon-client/src/official_install.rs` already implements verified runtime downloads from `piston-meta.mojang.com` / `resources.download.minecraft.net`, whereas the generated `.nbt`/`.json` lookup tables come from `horizon-prepare` (which requires a desktop JDK 25 + vanilla JARs). Is there a planned path for Android clients to download or generate the required engine tables at runtime (or accept user-supplied/PC-prepared archives into `<filesDir>`) so the base APK itself does not need to bundle `assets/engine-data.zip`?

### (c) `external-auth` / headless session handoff seam (instead of patching out OAuth)
Right now on `android`, Starling embeds a Microsoft client ID (`catalog.rs:22`), exposes account management / Microsoft / Ely.by / Wardrobe cloud sync flows (`android/login.rs`, `android/launcher.rs`, `android/launcher_pages.rs`, `android/wardrobe.rs`, `launcher/src/account*.rs`), and has no Cargo feature or compile-time flag to disable its own OAuth client while keeping `horizon_client::auth` active for in-memory session handoff from a host Android launcher.

In our downstream build we currently apply small build-time patches (`engine/patches/0001-runtime-fonts.patch`, `0002-blaze-native-bridge.patch`, `0003-phase1a-bridge-diagnostics.patch`, `0004-neutralize-engine-login.patch`) on top of upstream pinned commit `fa548eb7abe91c194d3780e4580b9746323cd606` (`android` branch) rather than forking `engine/starling` in-place:
- `0001-runtime-fonts.patch` switches OFL `.ttf` fonts from `include_bytes!` to runtime asset loading.
- `0002` + `0003` add `BlazeNativeBridge` (`dev.horizon.android.bridge.BlazeNativeBridge`) and in-memory `nativeSetSession`.
- `0004-neutralize-engine-login.patch` gates `launcher/src/account*.rs` endpoints behind `#[cfg(not(target_os = "android"))]` and disables in-engine login/refresh/account-switch/cloud-skin UI.

1. Is pinning `starling-client/starling` (`android` branch) at `fa548eb7ab` with build-time patches like this an acceptable downstream integration approach, or would upstream accept Cargo feature flags (`runtime-fonts`, `external-auth-only`, `blaze-bridge`) so downstream launchers do not have to carry patches against `login.rs` / `launcher_pages.rs` / `account.rs`?
2. Is it acceptable for a third-party launcher (Blaze Launcher) to execute `horizon-prepare` / engine-data preparation directly on the user's device against Mojang's official servers (using `research-lock.json` pins), and how would the Starling / Horizon / Horizon Android team like to be credited in-app and in documentation beyond MIT copyright notices?

### (d) `"Horizon Android is now Starling"` migration, stability & licensing boundaries *(technical screening, not legal advice)*
1. Upstream's `README.md` (`fa548eb7ab`) notes:
   > **Horizon Android is now Starling.** 0.7.8 is Horizon Android's last stable release. New work goes to the `starling` branch. This `android` branch takes the pull requests that are already open, then becomes a historical branch at the unified release.
   As `android` freezes into a historical branch and active development moves to `starling`, which branch, commit, or release tag should downstream integrators track next, and how stable are the `horizon-prepare` output schema (`SCHEMA = 7`, `OUTPUT_VERSION = 3`) and Android `NativeActivity` entrypoints expected to be across that merge?
2. From `docs/android-publication.md` and `docs/microsoft-account.md`, we understand downstream distributions must **never** reuse Starling's / another project's client ID (such as `00000000441cc96b`) and must pass our own Azure app registration and Mojang API review. When distributing an APK under **GPL-3.0-or-later** linking `starling` (`MIT`), we already avoid bundling OFL `.ttf` fonts via `include_bytes!` and omit Mojang wallpapers/skins. Regarding the 28 files inside `engine-data-generated.zip` (`tools/android_build.py:49-68`), could you clarify whether upstream treats those exported `.json`/`.nbt` tables as Starling-authored functional schemas or derived extracts from Mojang game JARs that downstream copyleft distributions should generate solely on the user's device?

Thank you for your time and for maintaining Starling!
