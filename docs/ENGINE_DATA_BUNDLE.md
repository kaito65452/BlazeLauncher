# Bundled `assets/engine-data.zip` Documentation (Task K)

> Any licensing or distribution notes below are **technical screening, not legal advice**.

## Overview

When the GitHub Actions repository variable `BUNDLE_ENGINE_DATA` is set to `true`, the `ci` job (`scripts/ci_bundle_engine_data.py`) transiently downloads the pinned Minecraft `26.3` inputs on the runner, executes upstream `horizon-prepare` (from pinned submodule `engine/starling` @ `fa548eb7abe91c194d3780e4580b9746323cd606` + `engine/patches/*.patch` under JDK 25), packages the generated compatibility tables via `tools/android_build.py:engine_data(public=True)`, validates every entry against the narrow Check 11 allowlist, and bundles them as `assets/engine-data.zip` inside the release APK.

On device (`Install.RustEngineData` and `Developer -> Prepare engine data`), when `assets/engine-data.zip` is present in the APK:
- On-device `:jvm` `javac` compilation, `datagen`, `server.jar`, and the 114 Java libraries are **skipped completely**.
- `libblaze_prepare.so` (`nativePrepareFromBundledZip`) reuses ZL2 `<gameHome>` (`client.jar`, `assetIndex`, indexed `assets/objects/*`), rebuilds client worldgen/tags/structures from `client.jar`, fetches/verifies `bedrock-controls`, extracts `assets/engine-data.zip`, and stamps/verifies `horizon-resources.json`, `horizon-prepare.json`, `android-downloads-v2.json`, and `audio-complete.json`.
- Downloaded runner inputs (`$RUNNER_TEMP/blaze-bundle-work`, `engine/starling/.local`, and transient `ZalithLauncher/src/main/assets/engine-data.zip`) are never committed to git, never cached, never uploaded as artifacts, and deleted unconditionally in `if: always()`.

## How to Toggle

```bash
# Enable prebuilt assets/engine-data.zip in CI release APKs (faster install, no on-device javac)
gh variable set BUNDLE_ENGINE_DATA --body "true"

# Disable bundling (reverts APK to zero Mojang-derived tables + slow/experimental on-device javac fallback)
gh variable set BUNDLE_ENGINE_DATA --body "false"
```

## Latest Verified Bundle Inventory (Task K)

- **CI Run**: *pending first green Task K CI run (`feat/overnight-stages`)*
- **`assets/engine-data.zip` SHA-256**: *pending Run 1 output*
- **`assets/engine-data.zip` Size**: *pending Run 1 output*
- **APK Size Delta (vs Baseline `4a8a6c5b`, run `43736688211`)**: *pending Run 1 output*
- **Entries Inside `assets/engine-data.zip`**: *pending Run 1 output (will match upstream `tools/android_build.py:generated_engine_file` allowlist)*

## Licensing & Risk Summary *(Technical Screening, Not Legal Advice)*

- Follows upstream `starling-client/starling` (`tools/android_build.py --public` / `.github/workflows/android-dev.yml`) public APK layout: generated `.json`/`.nbt` tables only, zero textures/sounds/classes/JARs.
- Open upstream boundary questions are tracked in [`docs/UPSTREAM_QUESTION.md`](UPSTREAM_QUESTION.md) items `(a)` and `(d)` and [`docs/LICENSE_PLAN.md`](LICENSE_PLAN.md) Section 6.
