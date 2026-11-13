# STEP A.1b: License Remediation Plan (Starling / Horizon Rust Engine)

> **Disclaimer**: All licensing assessments in this document are **technical screening, not legal advice**. Consult qualified legal counsel for binding license compliance decisions prior to public distribution.

---

## 0. Background & Scope Gate
In Step A.1, integrating the unmodified `starling-client` / `horizon-android` tree into Blaze Launcher (`GNU GPL v3.0 or later`) returned **NO** because:
1. `SIL Open Font License 1.1` (`OFL-1.1`) `.ttf` fonts were compiled directly into `libhorizon_android.so` via `include_bytes!`, creating a GPL-3.0 vs OFL-1.1 combination conflict without APK `OFL-*.txt` notices.
2. `kaito65452/horizon-android` branch `feat/blaze-ui` (`bd4328c4a8`) also tracks proprietary Mojang drop wallpapers, Mojang Steve/Alex skins, and `voicechat-engine/data/icons/*/*.png` without verified provenance.

This document specifies the minimal remediation plan before any build or Phase A.2+ spike work begins.

---

## 1. Upstream Engine Pin & Minimal `BlazeNativeBridge` File Set

- **Upstream Engine Repository**: `https://github.com/starling-client/starling.git`
- **Pinned Branch / Commit SHA**:
  - Branch: `android`
  - Commit SHA: **`fa548eb7abe91c194d3780e4580b9746323cd606`** (`Merge pull request #249 from starling-client/readme-starling-notice`, parent `d845ba0c15`).
  - Verified clean: `starling/android` @ `fa548eb7ab` contains **zero** `crates/voicechat-engine/`, **zero** `crates/horizon-client/src/android/wallpapers/`, and **zero** `packaging/android/assets/skin/`.

### Exact Files Permitted from `kaito65452/horizon-android` (`feat/blaze-ui` @ `bd4328c4a8`)
Do **not** merge or vendor `feat/blaze-ui` wholesale. Only extract the `BlazeNativeBridge` JNI source and its minimal Rust wiring:

1. `crates/horizon-client/src/android/jni_bridge.rs` — defines all 25 `Java_dev_horizon_android_bridge_BlazeNativeBridge_*` JNI exports (note: `nativeSetComplexVoiceChatConfig` writes only two booleans or becomes a no-op stub; it does **not** depend on `voicechat_engine`).
2. `crates/horizon-client/src/android_main.rs` — minimal 2-module export change only:
   - `pub mod jni_bridge;` (`#[path = "android/jni_bridge.rs"]`)
   - `pub(crate) mod account;` (visibility required by `jni_bridge.rs` `use super::account::...`)
3. `crates/horizon-client/src/android/catalog.rs` — only the 3 scalar `Config` fields (`svc_enabled`, `svc_rnnoise`, `keepalive_enabled`) if `nativeSetComplexVoiceChatConfig` persists them (no `voicechat-engine` crate dependency), or omit them and stub `nativeSetComplexVoiceChatConfig`.
4. `packaging/android/java/dev/horizon/android/bridge/BlazeNativeBridge.kt` — Kotlin bridge class declaration `dev.horizon.android.bridge.BlazeNativeBridge` (placed in Blaze Launcher's Android source when Phase A.2+ connects it, keeping the exact symbol names intact).

**Confirmation**: Inspected `git diff starling/android..bd4328c4a8 -- crates/horizon-client/src/android/ crates/horizon-client/src/android_main.rs packaging/android/java/dev/horizon/android/bridge/` and verified via `grep`: none of the 4 files above include, reference, or import `wallpapers/*.png`, `wallpaper_templates/`, `packaging/android/assets/skin/`, or `crates/voicechat-engine/data/icons/*/*.png`. (`jni_bridge.rs:704-806` only passes account metadata URLs/strings, not bundled PNG files.)

---

## 2. Fonts (`OFL-1.1`): Removing `include_bytes!` from `libhorizon_android.so` (Option 1)

### Why Remediation Is Required (Technical Screening, Not Legal Advice)
In `crates/horizon-theme/fonts/OFL-PixelifySans.txt` (identical terms in `OFL-Geist.txt`, `OFL-BricolageGrotesque.txt`):
> *"5) The Font Software, modified or unmodified, in part or in whole, must be distributed entirely under this license, and must not be distributed under any other license."*

Compiling `.ttf` bytes into `libhorizon_android.so` via `include_bytes!` combines `OFL-1.1` Font Software directly inside a compiled program library distributed in a `GPL-3.0-or-later` APK (`GPL-3.0 §5(c)` vs `OFL-1.1 §5`). Shipping fonts as **separate loose files** in `assets/fonts/` alongside `OFL-*.txt` places them in "mere aggregation" alongside the APK's code rather than inside the compiled ELF binary.

### Exact Code Paths to Change (Pinned Commit `fa548eb7ab`)

1. **`crates/horizon-theme/src/lib.rs:23-39` (`pub mod fonts`)**:
   - **Current**: Unconditionally compiles 12 `OFL-1.1` `.ttf` files (`GEIST_REGULAR`, `GEIST_MEDIUM`, `GEIST_SEMIBOLD`, `GEIST_MONO`, `PIXEL_*`, `BRICOLAGE_*`) via `include_bytes!("../fonts/...")`.
   - **Minimal Change**: Gate `pub mod fonts` behind an opt-in Cargo feature `bundled-fonts` (disabled by default, and guarded with `#[cfg(all(feature = "bundled-fonts", not(target_os = "android")))]` or `compile_error!` on Android) so zero OFL bytes are ever compiled into `libhorizon_android.so`. Export filename constants (`GEIST_FILES = ["Geist-Regular.ttf", ...]`) instead of static byte arrays.
2. **`crates/horizon-client/src/ui_theme.rs:152-175` (`Fonts` / `Fonts::load`), `ui_theme.rs:213-220` (`Cache`), `ui_theme.rs:582-615` (`Cache::glyph`), `ui_theme.rs:1408-1425` (`Ui::measure`), `ui_theme.rs:1428+` (`Ui::text`)**:
   - **Current**:
     - `ui_theme.rs:156`: `let f = |b: &'static [u8]| FontRef::try_from_slice(b).expect("bundled font");`
     - `ui_theme.rs:159-162`: reads `horizon_theme::fonts::{GEIST_REGULAR, GEIST_MEDIUM, GEIST_SEMIBOLD, GEIST_MONO}`.
     - `ui_theme.rs:214`: `Cache { fonts: Fonts::load(), ... }`.
   - **Minimal Change**:
     - Replace `[FontRef<'static>; 4]` with `[Option<ab_glyph::FontVec>; 4]` (owned heap font loaded from `$HORIZON_HOME/fonts/<file>.ttf` via `std::fs::read` after APK `assets/fonts/` extraction, returning `None` if the file is absent or fails to parse instead of calling `.expect("bundled font")`).
     - Update `Cache::glyph`, `Ui::measure`, and `Ui::text` to return empty `Cached { rect: None, left: 0.0, top: 0.0 }` / `0.0` advance when the `Option<FontVec>` is `None`.
3. **`crates/horizon-client/src/game.rs:20575-20587`**:
   - Already gated under `#[cfg(target_os = "windows")]` (calls `horizon_threads::boot_paint::paint` with `GEIST_SEMIBOLD` and `GEIST_MONO`); never compiled on Android. Optionally also gate with `feature = "bundled-fonts"`.
4. **`crates/horizon-client-render/src/font.rs:1274`**:
   - Inside unit test `#[test] fn true_type_glyphs_are_rasterized_at_the_provider_size()` only; never compiled into `libhorizon_android.so`. Gate test with `#[cfg(all(test, feature = "bundled-fonts"))]` or replace test input with an inline minimal synthetic/permissive TTF fixture.

### Does the Engine Still Render Without Them?
- **World / 3D Scene / Vanilla Minecraft UI**: **YES.** In-game Minecraft rendering in `crates/horizon-client-render/src/font.rs` loads Minecraft's own asset font sheets (`assets/minecraft/font/default.json` from downloaded game assets), completely independent of `horizon_theme::fonts`.
- **Native Overlay UI (`ui_theme::Ui`)**:
  - **Before the fix above**: **NO (crashes)** — `touch_ui.rs` (`touch_ui.rs:2517, 3993, 4074`), `game/no_resources.rs:54-60`, `game/pad_menu.rs:1496`, and `game.rs:15172-15182` invoke `ui_theme::Ui`, which triggers `Fonts::load()` (`ui_theme.rs:156`) and panics on `.expect("bundled font")` if font bytes are removed.
  - **After changing `Fonts` to runtime `Option<FontVec>`**: **YES** — if `assets/fonts/*.ttf` are absent at runtime, the engine still initializes wgpu, renders the 3D world, vanilla HUD, and geometric `ui_theme` boxes/icons without crashing (`ui_theme` text labels degrade to empty rather than panicking). When `assets/fonts/Geist-*.ttf` are packaged in APK `assets/fonts/` with `OFL-Geist.txt`, `ui_theme` renders text normally.

### APK Packaging & Reserved Font Name (RFN) Rules
When Option 1 fonts are packaged in `ZalithLauncher/src/main/assets/fonts/`:
- Copy unmodified `Geist-Regular.ttf`, `Geist-Medium.ttf`, `Geist-SemiBold.ttf`, `GeistMono-Regular.ttf` **plus** verbatim `OFL-Geist.txt` (`Copyright 2024 The Geist Project Authors`).
- Copy `OFL-PixelifySans.txt` / `OFL-BricolageGrotesque.txt` **only** if those faces are packaged (not used by `ui_theme`).
- Retain `ZalithLauncher/src/main/assets/THIRD_PARTY_NOTICES.md` inside the APK.
- Do not modify any OFL font while retaining a Reserved Font Name without written holder permission (`OFL-1.1 §3`).

---

## 3. Excluding Mojang Assets, Wallpapers, Skins, and Unverified Voicechat Icons

### Cargo & Build-Time Allowlist Controls
1. **Cargo**: `starling/android` (`fa548eb7ab`) omits `voicechat-engine`, `wallpapers/`, and `skin/`. Keeping `bundled-fonts` disabled in `horizon-android/Cargo.toml` prevents compile-time font embedding.
2. **Android Asset Packaging Allowlist**: Only explicitly audited assets under `ZalithLauncher/src/main/assets/` are packaged:
   - `assets/THIRD_PARTY_NOTICES.md`
   - `assets/fonts/{Geist-Regular.ttf,Geist-Medium.ttf,Geist-SemiBold.ttf,GeistMono-Regular.ttf,OFL-Geist.txt}` (when fonts are loaded at runtime in Phase A.2+)
   - Existing ZL2 assets (`app_runtime/`, `components/`, `default_layout.json`, `game/`, `mod_data.txt`, `modpack_data.txt`, `runtimes/`, `skinview/`, `textmate/`, and ZL2's pre-existing `assets/steve.png`).
3. **Repository & APK CI Guard (`scripts/guard.sh` + `.github/workflows/ci.yml`)**:
   - **Repo scan** (`scripts/guard.sh` on every push/PR): Fails if tracked files include:
     - `wallpapers/`, `wallpaper_templates/`, `packaging/android/assets/skin/`
     - `voicechat-engine/data/icons/` or `crates/voicechat-engine/`
     - `engine/**/{steve,alex}.png` or newly added `ZalithLauncher/src/main/assets/{skin/,alex.png}`
     - Desktop `launcher/assets/logos/` copied into Android assets or `engine/` `include_bytes!` targeting `.ttf`/`.otf`
   - **APK scan** (`scripts/guard.sh --check-apk <apk>` in `.github/workflows/ci.yml` after `assembleRelease`/`assembleDebug`):
     - Requires `assets/THIRD_PARTY_NOTICES.md` in the APK.
     - Fails if APK zip entries contain `wallpapers/`, `wallpaper_templates/`, `assets/skin/`, `assets/alex.png`, or `voicechat`.
     - If `assets/fonts/*.ttf` or `.otf` appear in the APK, requires matching `assets/fonts/OFL-*.txt` license files.
     - If `lib/*/libhorizon_android.so` exists in the APK, scans binary strings for embedded font license/copyright signatures (`SIL Open Font License`, `The Geist Project Authors`, `The Pixelify Sans Project Authors`, `The Bricolage Grotesque Project Authors`) and fails if found.

---

## 4. `ring` Crate Inspection (`ring 0.17.14`) — Technical Screening, Not Legal Advice

### Inspected License Files & Source Headers (`ring-0.17.14` in `Cargo.lock`)
- **`Cargo.toml`**: `license = "Apache-2.0 AND ISC"`
- **`LICENSE`** (verbatim):
  > `*ring* uses an "ISC" license, like BoringSSL used to use, for new code files. See LICENSE-other-bits for the text of that license.`
  >
  > `See LICENSE-BoringSSL for code that was sourced from BoringSSL under the Apache 2.0 license. Some code that was sourced from BoringSSL under the ISC license. In each case, the license info is at the top of the file.`
  >
  > `See src/polyfill/once_cell/LICENSE-APACHE and src/polyfill/once_cell/LICENSE-MIT for the license to code that was sourced from the once_cell project.`
- **`LICENSE-BoringSSL`** (272 lines total):
  - Lines 1–202: **Apache License, Version 2.0** (`http://www.apache.org/licenses/LICENSE-2.0`).
  - Lines 206–272 (`Licenses for support code`): Go test suite (`BSD-3-Clause`, `Copyright (c) 2009 The Go Authors`) and Chromium test scripts (`BSD-3-Clause`, `Copyright 2015 The Chromium Authors`), preceded by:
    > *"Parts of the TLS test suite are under the Go license. This code is not included in BoringSSL (i.e. libcrypto and libssl) when compiled, however, so distributing code linked against BoringSSL does not trigger this license"*
- **`crypto/` & `third_party/fiat/` source scan**:
  - `grep` across all files in `ring-0.17.14` returned **zero** matches for `"advertising"`, `"OpenSSL License"`, `"SSLeay"`, or `"Eric Young"`.
  - Files retaining upstream OpenSSL copyrights (`crypto/cpu_intel.c`, `crypto/chacha/asm/chacha-armv8.pl`, `crypto/constant_time_test.c`, `crypto/fipsmodule/bn/internal.h`) and `third_party/fiat/LICENSE` carry **Apache-2.0** headers (`https://www.apache.org/licenses/LICENSE-2.0`).

### Conclusion (Technical Screening, Not Legal Advice)
- **Acceptable in a `GPL-3.0-or-later` APK? YES (Technical Screening)** for **`ring 0.17.14`** specifically:
  - The legacy OpenSSL/SSLeay 4-clause advertising clause (which is GPL-incompatible) is **absent** from `ring 0.17.14`.
  - All compiled code paths in `ring 0.17.14` are licensed under **Apache-2.0**, **ISC**, or **MIT** (`once_cell` polyfill), each of which is compatible with `GPL-3.0-or-later` (note: Apache-2.0 is compatible with GPLv3, though incompatible with GPLv2-only; Blaze Launcher is `GPL-3.0-or-later`).
  - Apache-2.0 §4 attribution and ISC notice obligations must be fulfilled in the APK via `assets/THIRD_PARTY_NOTICES.md`.
  - Any future upgrade of `ring` must re-verify `Cargo.toml` / `LICENSE-BoringSSL` / `crypto/` headers to ensure no OpenSSL 4-clause terms are introduced.

---

## 5. Summary Verdict (Technical Screening, Not Legal Advice)
- **Raw `starling/android` (`fa548eb7ab`) + `feat/blaze-ui`**: **NO** (unremediated OFL font embedding + Mojang assets/unclear voicechat icons).
- **Remediated `starling/android` (`fa548eb7ab`) + `BlazeNativeBridge` JNI source only + Option 1 unbundled fonts + CI guard + `assets/THIRD_PARTY_NOTICES.md`**: **READY TO PROCEED TO IMPLEMENTATION ON USER GO-AHEAD** (once the remediation edits to `horizon-theme` / `ui_theme.rs` and the minimal `BlazeNativeBridge` import are applied under `engine/starling/` in Phase A.2+).
