# Bundled `assets/engine-data.zip` Documentation (Task K)

> Any licensing or distribution notes below are **technical screening, not legal advice**.

## Overview

When the GitHub Actions repository variable `BUNDLE_ENGINE_DATA` is set to `true`, the `ci` job (`scripts/ci_bundle_engine_data.py`, `.github/workflows/ci.yml:89-99`) transiently downloads the pinned Minecraft `26.3` inputs on the runner (`ALLOWED_DOWNLOAD_HOSTS` + SHA-1 pins), executes upstream `horizon-prepare` (from pinned submodule `engine/starling` @ `fa548eb7abe91c194d3780e4580b9746323cd606` + `engine/patches/*.patch` under JDK 25), packages the generated compatibility tables via `tools/android_build.py:engine_data(public=True)`, validates every entry against the narrow Check 11 allowlist, and bundles them as `assets/engine-data.zip` inside the `ci.yml` release APK (`retention-days: 3`).

`nightly.yml` and `release.yml` **never** bundle `assets/engine-data.zip` regardless of `BUNDLE_ENGINE_DATA` (enforced by Check 14 and `--check-release-upload`, Task K8).

On device (`Install.RustEngineData` and `Developer -> Prepare engine data`), when `assets/engine-data.zip` is present in the APK:
- On-device `:jvm` `javac` compilation, `datagen`, `server.jar`, and the 114 Java libraries are **skipped completely**.
- `libblaze_prepare.so` (`nativePrepareFromBundledZip`) reuses ZL2 `<gameHome>` (`client.jar`, `assetIndex`, indexed `assets/objects/*`), rebuilds client worldgen/tags/structures from `client.jar`, fetches/verifies `bedrock-controls`, extracts `assets/engine-data.zip`, and stamps/verifies `horizon-resources.json`, `horizon-prepare.json`, `android-downloads-v2.json`, and `audio-complete.json`.
- Downloaded runner inputs (`$RUNNER_TEMP/blaze-bundle-work`, `engine/starling/.local`, and transient `ZalithLauncher/src/main/assets/engine-data.zip`) are never committed to git, never cached, never uploaded as artifacts, and deleted unconditionally in `if: always()` (`.github/workflows/ci.yml`).

## How to Toggle

```bash
# Enable prebuilt assets/engine-data.zip in ci.yml APKs (faster install, no on-device javac)
gh variable set BUNDLE_ENGINE_DATA --body "true"

# Disable bundling (reverts ci.yml APK to zero Mojang-derived tables + slow/experimental on-device javac fallback)
gh variable set BUNDLE_ENGINE_DATA --body "false"
```

Note: `nightly.yml` and `release.yml` ignore `BUNDLE_ENGINE_DATA` completely and fail Check 14 if they ever reference it or receive a bundled APK.

## Latest Verified Bundle Inventory (Task K — CI Run `43749595583`, commit `56a21ae2`)

- **CI Run URL**: `https://github.com/kaito65452/BlazeLauncher/actions/runs/43749595583` (`Parallel Fast Checks`: `https://github.com/kaito65452/BlazeLauncher/actions/runs/43749595579`)
- **Artifact**: `signed-apks` (ID `14175899800`, `retention-days: 3`), APK `BlazeLauncher-2.6_hotfix-blaze.56a21ae2.apk`
- **`versionCode`**: `27543853` (`> 27535431` baseline at `4a8a6c5b` / run `43736688211`)
- **Signing Certificate SHA-256 (V2)**: `5aa533d1c85a38a2190f73761d81c033bf7065091660970653d6c63fbd81a2fc` (`android:debuggable=false`)
- **`assets/engine-data.zip` SHA-256**: `805659f75c0bb8c5e0a74b77be63924c549c18d166923262ec5b066d773e9521`
- **`assets/engine-data.zip` Compressed Size**: `9,121,611` bytes (~`8.70 MiB`) across `33` allowlisted entries (`tools/android_build.py:generated_engine_file`: 21 top-level files + 4 biome-parameter paths + 7 `worldgen/blocks/*` files + 1 `worldgen/block_flags.json`)
- **APK Artifact Size Delta (vs Baseline `4a8a6c5b`, run `43736688211`)**: `386,866,562` bytes vs `378,320,523` bytes (**`+8,546,039` bytes / `+8.15 MiB`**)
- **CI Timings**: `engine / engine` `3m37s` (cache hit on `horizon_android`, `blaze_prepare`, and `ci-host-tools`); `ci` `13m41s` vs `12m14s` baseline (upstream `horizon-prepare` took `32.2s`, 5,216 input downloads took `11.4s`, K2 `blaze-prepare preflight` took `65.1s`).

### Complete 33-Entry Manifest (`assets/engine-data.zip`)

| Entry | Uncompressed Bytes | K2 Oracle vs `blaze-prepare` |
| --- | ---: | --- |
| `attributes.json` | 131,846 | byte-identical |
| `biome-parameters/nether.json` | 1,350 | byte-identical |
| `biome-parameters/overworld.json` | 3,499,270 | byte-identical |
| `block-behaviours.json` | 1,982,156 | equal byte length (`1982156`), array order differs |
| `cached-collision.nbt` | 5,712,342 | byte-identical |
| `command-registries.json` | 1,611,810 | json-structurally-equal |
| `commands.json` | 583,592 | byte-identical |
| `creative-inventory.json` | 406,022 | equal byte length (`406022`), array order differs |
| `damage-catalog.json` | 8,703 | byte-identical |
| `entity-models.json` | 2,465,789 | byte-identical |
| `entity-types.json` | 107,173 | byte-identical |
| `facts.json` | 10,126,974 | byte-identical |
| `gameplay-facts.json` | 55,723,036 | byte-identical |
| `horizon-resources.json` | 5,528 | differs (`5528` desktop full health map vs `1573` compact `schema=7`) |
| `items.json` | 1,025,792 | json-structurally-equal |
| `landing-definitions.json` | 78,400 | byte-identical |
| `mob-data.json` | 1,037,426 | json-structurally-equal |
| `model-states.json` | 2,842,545 | byte-identical |
| `movement-properties.nbt` | 4,359,891 | byte-identical |
| `occlusion.nbt` | 6,180,754 | byte-identical |
| `player-float-ids.json` | 28 | byte-identical |
| `registries.json` | 536,272 | byte-identical |
| `render-definitions.json` | 343,371 | byte-identical |
| `worldgen/biome_parameters/nether.json` | 1,350 | byte-identical |
| `worldgen/biome_parameters/overworld.json` | 3,499,270 | byte-identical |
| `worldgen/block_flags.json` | 486,731 | byte-identical |
| `worldgen/blocks/block-properties.nbt` | 10,392,764 | byte-identical |
| `worldgen/blocks/cached-collision.nbt` | 5,712,342 | byte-identical |
| `worldgen/blocks/cached-support.nbt` | 5,713,394 | byte-identical |
| `worldgen/blocks/placement-definitions.json` | 296 | byte-identical |
| `worldgen/blocks/structure-blocks.json` | 1,399,618 | byte-identical |
| `worldgen/blocks/survival-extra-definitions.json` | 40,276 | byte-identical |
| `worldgen/blocks/survival-rules.json` | 51,574 | byte-identical |

**K2 Oracle Summary**: `total=33`, `byte_identical=27`, `json_structurally_equal=3`, `differing=3` (two same-length array ordering differences + expected `horizon-resources.json` manifest delta).

## Licensing & Risk Summary *(Technical Screening, Not Legal Advice)*

- Follows upstream `starling-client/starling` (`tools/android_build.py --public` / `.github/workflows/android-dev.yml`) public APK layout: generated `.json`/`.nbt` tables only, zero textures/sounds/classes/JARs.
- Open upstream boundary questions are tracked in [`docs/UPSTREAM_QUESTION.md`](UPSTREAM_QUESTION.md) items `(a)` and `(d)` and [`docs/LICENSE_PLAN.md`](LICENSE_PLAN.md) Section 6.
