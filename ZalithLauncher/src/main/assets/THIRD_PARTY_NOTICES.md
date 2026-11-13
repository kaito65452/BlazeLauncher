# Third-Party Notices — Blaze Launcher APK

> **Notice**: This file satisfies attribution, copyright notice, and license text preservation requirements for third-party software, libraries, fonts, and assets distributed in or planned for the Blaze Launcher Android APK. Blaze Launcher itself is licensed under the GNU General Public License v3.0 or later (`GPL-3.0-or-later`).

---

## 1. Launcher Base & Android Modules (Blaze Launcher / Zalith Launcher 2)

- **Zalith Launcher 2 (upstream base)** — `GNU GPL v3.0 or later` (`GPL-3.0-or-later`), Copyright (C) 2025 MovTery and contributors (`ZalithLauncher/src/main/res/raw/fcl_license.txt`, `hmcl_license.txt`, `LICENSE`).
- **LWJGL (3.3.3 / 3.4.1 Android bindings)** — `BSD-3-Clause`, `ZalithLauncher/src/main/res/raw/lwjgl_license.txt`.
- **Terracotta (P2P multiplayer module)** — `GPL-3.0` (`Terracotta/src/main/res/raw/terracotta_license.txt`).
- **Mesa / GL4ES / NG-GL4ES / ANGLE** — `MIT` / `LGPL-2.1` / `BSD-3-Clause` (`mesa_license.txt`, `gl4es_license.txt`, `ng_gl4es_license.txt`, `angle_license.txt`).
- **SDL2 / SDL3** — `Zlib` (`sdl2_compat_license.txt`, `sdl3_license.txt`).
- **MMKV** — `BSD-3-Clause` (`mmkv_license.txt`).
- **ByteHook (`bhook`)** — `MIT` (`bhook_license.txt`).
- **OpenNBT / SkinView3D / Sora Editor / TM4E / XZ-Java** — `MIT` / `LGPL-2.1` / `EPL-2.0` / `0BSD` (`opennbt_license.txt`, `skinview3d_license.txt`, `sora_editor_license.txt`, `tm4e_license.txt`, `xz_java_license.txt`).
- **AndroidX, Kotlin stdlib, Ktor, OkHttp, Coil, Coroutines, Serialization** — `Apache-2.0` (`apache_license_2.txt`).

---

## 2. Starling / Horizon Rust Engine (Target Pin: `starling-client/starling` @ `fa548eb7abe91c194d3780e4580b9746323cd606`)

### 2.1 Core Engine & Workspace Crates
- **Starling / Horizon Android (`starling-client/starling`, branch `android`)** — **`MIT`**
  - Copyright (c) 2026 Ludovic Marie and the Horizon contributors
  - Covers workspace crates: `horizon-android`, `horizon-anticheat`, `horizon-assets`, `horizon-bench`, `horizon-bots`, `horizon-chat-filter`, `horizon-client`, `horizon-client-core`, `horizon-client-input`, `horizon-client-render`, `horizon-command`, `horizon-core`, `horizon-dualsense`, `horizon-friends`, `horizon-hdr`, `horizon-items`, `horizon-mod-sdk`, `horizon-mods`, `horizon-narrator`, `horizon-optifine`, `horizon-port-pack`, `horizon-protocol`, `horizon-relay`, `horizon-release-sign`, `horizon-server`, `horizon-server-files`, `horizon-shader-api`, `horizon-shaders`, `horizon-telemetry`, `horizon-theme` (code tokens & MIT icons), `horizon-threads`, `horizon-world`.
- **`BlazeNativeBridge` JNI Adapter (`dev.horizon.android.bridge.BlazeNativeBridge`)** — **`MIT`** / **`GPL-3.0-or-later`** (Blaze Launcher contributor JNI shim).

### 2.2 Vendored Crates (`third_party/` in Starling)
- **`third_party/wgpu-hal` (30.0.1 + patches)** — **`MIT OR Apache-2.0`**
  - Copyright (c) 2025 The gfx-rs developers (`third_party/wgpu-hal/LICENSE.MIT`, `LICENSE.APACHE`).
- **`third_party/naga` (30.0.1 + GLSL frontend patches)** — **`MIT OR Apache-2.0`**
  - Copyright (c) 2025 The gfx-rs developers (`third_party/naga/LICENSE.MIT`, `LICENSE.APACHE`).
- **`third_party/winit` (0.30.13 + Android mouse/modifier patches)** — **`Apache-2.0`**
  - `third_party/winit/LICENSE`.

### 2.3 Statically Bundled Native Modules & Shaders
- **`zoom`, `minimap`, `recipes` (from `horizon-rust/horizon-mods` @ `e3ee0a7c`)** — **`MIT`**
  - Copyright (c) 2026 Ludovic Marie and the Horizon contributors.
- **`smartf3` (from `d3grando/smartf3` @ `184b92ba`)** — **`MIT`**
  - Copyright (c) 2026 d3grando.
- **Frame Generation WGSL Shaders (`optical_flow.wgsl`, `interpolation.wgsl`, `optical_flow.rs`)** — **`MIT`**
  - Ported from AMD FidelityFX SDK 1.1.4; Copyright (C) 2024 Advanced Micro Devices, Inc. (AMD and FidelityFX are AMD's trademarks; not affiliated with AMD).

### 2.4 Icons & Fonts
- **Pixelarticons (`crates/horizon-theme/icons/pixel/*.svg`)** — **`MIT`**
  - Copyright (c) 2019 Gerrit Halfmann (`LICENSE-pixelarticons.txt`). `star.svg` is Horizon's own (`MIT`).
- **Font Software (`assets/fonts/` — loaded at runtime ONLY; NOT embedded in `.so`)** — **`SIL Open Font License 1.1` (`OFL-1.1`)**:
  - **Geist / Geist Mono** — Copyright 2024 The Geist Project Authors (`https://github.com/vercel/geist-font`). Ship with `OFL-Geist.txt` when bundled.
  - **Pixelify Sans** *(if packaged)* — Copyright 2021 The Pixelify Sans Project Authors (`https://github.com/eifetx/Pixelify-Sans`). Ship with `OFL-PixelifySans.txt` when bundled.
  - **Bricolage Grotesque** *(if packaged)* — Copyright 2022 The Bricolage Grotesque Project Authors (`https://github.com/ateliertriay/bricolage`); Cyrillic merged from Geist (`OFL-Geist.txt`). Ship with `OFL-BricolageGrotesque.txt` when bundled.
  - No Modified Versions of these fonts may use their Reserved Font Names without explicit written permission, nor be sold by themselves or relicensed under non-OFL terms.

### 2.5 Transitive Rust Dependencies (Target: `aarch64-linux-android` via `horizon-android`)
Summary by SPDX identifier from `cargo metadata --filter-platform aarch64-linux-android`:
- **`MIT`** (81 packages, including `bytes`, `glam` via `MIT OR Apache-2.0`, `mimalloc`, `ordered-float`, `tracing`, `twox-hash`, `zip`, etc.)
- **`MIT OR Apache-2.0` / `Apache-2.0 OR MIT` / `Apache-2.0/MIT` / `MIT/Apache-2.0`** (251 packages, including `wgpu`, `wgpu-core`, `wgpu-types`, `naga`, `android-activity`, `ash`, `ndk`, `jni`, `serde`, `serde_json`, `regex`, `ureq`, `url`, `image`, `flate2`, `chrono`, `uuid`, `rustls-pki-types`, `parking_lot`, `thiserror`, etc.)
- **`Apache-2.0 WITH LLVM-exception` (and `OR Apache-2.0 OR MIT`)** (45 packages: `wasmtime` 48.0.8, `cranelift-*`, `pulley-*`, `regalloc2`, `wit-*`, `wasmparser`, `wasm-encoder`, `rustix`, `linux-raw-sys`)
- **`Apache-2.0`** (10 packages: `ab_glyph`, `ab_glyph_rasterizer`, `codespan-reporting`, `cpal`, `flagset`, `hound`, `owned_ttf_parser`, `spirv`, `winit`, `zopfli`)
- **`Apache-2.0 AND ISC`** (`ring 0.17.14` — new files under ISC; BoringSSL-derived crypto files in compiled library under Apache-2.0 / ISC; Go/Chromium test scripts excluded from compiled binary)
- **`Apache-2.0 OR ISC OR MIT`** (`rustls 0.23.46`)
- **`Apache-2.0 OR GPL-2.0-only`** (`self_cell 1.3.0` — elected under `Apache-2.0` for GPL-3.0 compatibility)
- **`ISC`** (`audiopus`, `audiopus_sys`, `libloading`, `rustls-webpki`, `untrusted`)
- **`BSD-3-Clause` / `BSD-2-Clause` / dual BSD** (`curve25519-dalek`, `nnnoiseless`, `pp-rs`, `subtle`, `moxcms`, `pxfm`, `num_enum`, `zerocopy`, `encoding_rs`)
- **`MPL-2.0`** (6 packages: `symphonia`, `symphonia-codec-vorbis`, `symphonia-core`, `symphonia-format-ogg`, `symphonia-metadata`, `symphonia-utils-xiph` — combined under MPL-2.0 §3.3 larger-work terms with GPL-3.0-or-later)
- **`Unicode-3.0`** (18 `icu_*` / `litemap` / `tinystr` / `zerovec` packages; plus `unicode-ident`)
- **`CDLA-Permissive-2.0`** (`webpki-roots`)
- **`BSL-1.0`** (`xxhash-rust`), **`0BSD`** (`adler2`), **`Zlib`** (`foldhash`, `zlib-rs`, `bytemuck`), **`Unlicense OR MIT`** (`aho-corasick`, `memchr`, `walkdir`, etc.)

---

## 3. Explicitly Excluded Components (Not Shipped in Blaze Launcher APK)

- `crates/horizon-client/src/android/wallpapers/*.png` and `wallpaper_templates/*.zip` (Mojang Minecraft promotional drop wallpapers — no permissive license)
- `packaging/android/assets/skin/{alex,steve}.png` from Horizon fork (Mojang default skins — excluded; note Zalith Launcher 2 ships its own pre-existing `assets/steve.png`)
- `crates/voicechat-engine/` & `crates/voicechat-engine/data/icons/*/*.png` (excluded; unverified PNG provenance)
- `launcher/` desktop GPUI crate & loader icon `neoforge.png` (`CC-BY-4.0`) / `quilt.png` (`CC0-1.0`) (not compiled or packaged for Android)
