// Blaze Launcher — on-device 26.3 engine-data preparer
// Copyright (C) 2026 Blaze Launcher contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Resource extraction, tag/worldgen bundling, datagen finalization, and engine-data ZIP packaging.

use crate::bundler::{unpack_server_bundle, write_java_plan, JavaPlan};
use crate::download::{
    build_artifact_plan, fetch_batch_parallel, plan_asset_objects, plan_bedrock_controls,
    resolve_version_manifest, DownloadStats, HttpFetcher, DEFAULT_CATALOG_URL,
};
use crate::files::{
    atomic_activate_dir, atomic_copy_file, atomic_write_bytes, cleanup_partials, crc32_file,
    peak_rss_kb, safe_relative, sha1_file, sha256_file, CancelFlag,
};
use crate::lock::{BedrockControlsManifest, LockFile};
use crate::survival;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::Instant;
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

pub const HORIZON_SCHEMA: u32 = 7;
pub const HORIZON_OUTPUT_VERSION: u32 = 3;

pub const WORLDGEN_BUNDLES: [(&str, &str); 19] = [
    ("noises", "worldgen/noise"),
    ("densities", "worldgen/density_function"),
    ("rules", "worldgen/material_rule"),
    ("conditions", "worldgen/material_condition"),
    ("settings", "worldgen/noise_settings"),
    ("carvers", "worldgen/carver"),
    ("biomes", "worldgen/biome"),
    ("world_presets", "worldgen/world_preset"),
    ("features", "worldgen/feature"),
    ("placed_features", "worldgen/placed_feature"),
    ("state_providers", "worldgen/block_state_provider"),
    ("biome_tags", "tags/worldgen/biome"),
    ("feature_tags", "tags/worldgen/feature"),
    ("block_tags", "tags/block"),
    ("fluid_tags", "tags/fluid"),
    ("processor_lists", "worldgen/processor_list"),
    ("template_pools", "worldgen/template_pool"),
    ("structures", "worldgen/structure"),
    ("structure_sets", "worldgen/structure_set"),
];

const BLOCK_BEHAVIOR_COPY_FILES: [&str; 5] = [
    "block-properties.nbt",
    "cached-collision.nbt",
    "cached-support.nbt",
    "survival-extra-definitions.json",
    "placement-definitions.json",
];

#[derive(Debug, Clone, Deserialize)]
pub struct PrepareConfig {
    pub files_dir: PathBuf,
    pub game_home: Option<PathBuf>,
    pub lock_path: PathBuf,
    pub controls_manifest_path: PathBuf,
    #[serde(default)]
    pub parity_sources_dir: Option<PathBuf>,
    #[serde(default)]
    pub catalog_url: Option<String>,
    #[serde(default)]
    pub objects_base_url: Option<String>,
    #[serde(default)]
    pub controls_base_url: Option<String>,
    #[serde(default)]
    pub allow_loopback: bool,
    #[serde(default = "default_workers")]
    pub download_workers: usize,
}

fn default_workers() -> usize {
    6
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Phase1Output {
    pub work_dir: String,
    pub stage_dir: String,
    pub target_resources_dir: String,
    pub java_plan: JavaPlan,
    pub download_stats: DownloadStats,
    pub client_resource_files: u64,
    pub indexed_assets_copied: u64,
    pub controls_ready: u64,
    pub worldgen_structures_extracted: u64,
    pub worldgen_maps_written: u64,
    pub elapsed_ms: u64,
    pub peak_rss_kb: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Phase2Output {
    pub target_resources_dir: String,
    pub engine_data_zip: String,
    pub engine_data_entries: u64,
    pub survival_rules_count: u64,
    pub items_count: u64,
    pub elapsed_ms: u64,
    pub peak_rss_kb: u64,
}

/// Exact 33-entry allowlist matching Starling `tools/android_build.py:generated_engine_file`.
pub fn is_generated_engine_file(rel_posix: &str) -> bool {
    let parts: Vec<&str> = rel_posix.split('/').collect();
    if parts.len() == 1 {
        return matches!(
            rel_posix,
            "attributes.json"
                | "block-behaviours.json"
                | "cached-collision.nbt"
                | "command-registries.json"
                | "commands.json"
                | "creative-inventory.json"
                | "damage-catalog.json"
                | "entity-models.json"
                | "entity-types.json"
                | "facts.json"
                | "gameplay-facts.json"
                | "horizon-resources.json"
                | "items.json"
                | "landing-definitions.json"
                | "mob-data.json"
                | "model-states.json"
                | "movement-properties.nbt"
                | "occlusion.nbt"
                | "player-float-ids.json"
                | "registries.json"
                | "render-definitions.json"
        );
    }
    if matches!(
        rel_posix,
        "biome-parameters/nether.json"
            | "biome-parameters/overworld.json"
            | "worldgen/biome_parameters/nether.json"
            | "worldgen/biome_parameters/overworld.json"
            | "worldgen/block_flags.json"
    ) {
        return true;
    }
    parts.len() == 3
        && parts[0] == "worldgen"
        && parts[1] == "blocks"
        && matches!(
            parts[2],
            "block-properties.nbt"
                | "cached-collision.nbt"
                | "cached-support.nbt"
                | "survival-rules.json"
                | "survival-extra-definitions.json"
                | "placement-definitions.json"
                | "structure-blocks.json"
        )
}

fn read_zip_text(archive: &mut ZipArchive<fs::File>, name: &str) -> Result<String, String> {
    let mut entry = archive
        .by_name(name)
        .map_err(|e| format!("Missing zip entry {name}: {e}"))?;
    let mut out = String::new();
    entry
        .read_to_string(&mut out)
        .map_err(|e| format!("Reading zip entry {name}: {e}"))?;
    Ok(out)
}

fn read_zip_bytes(archive: &mut ZipArchive<fs::File>, name: &str) -> Result<Vec<u8>, String> {
    let mut entry = archive
        .by_name(name)
        .map_err(|e| format!("Missing zip entry {name}: {e}"))?;
    let mut out = Vec::with_capacity(entry.size() as usize);
    entry
        .read_to_end(&mut out)
        .map_err(|e| format!("Reading zip entry {name}: {e}"))?;
    Ok(out)
}

/// Extracts `{"minecraft:<path>": raw_json_text}` under `prefix` sorted by member name.
pub fn json_resources_map(jar_path: &Path, prefix: &str) -> Result<BTreeMap<String, String>, String> {
    let file = fs::File::open(jar_path).map_err(|e| format!("{}: {e}", jar_path.display()))?;
    let mut archive = ZipArchive::new(file).map_err(|e| format!("{}: {e}", jar_path.display()))?;
    let mut names = Vec::new();
    for i in 0..archive.len() {
        let entry = archive.by_index(i).map_err(|e| e.to_string())?;
        let name = entry.name().map_err(|e| e.to_string())?;
        if name.starts_with(prefix) && name.ends_with(".json") && !entry.is_dir() {
            names.push(name.into_owned());
        }
    }
    names.sort();
    let mut map = BTreeMap::new();
    for name in names {
        let text = read_zip_text(&mut archive, &name)?;
        let stem = &name[prefix.len()..name.len() - 5];
        map.insert(format!("minecraft:{stem}"), text);
    }
    Ok(map)
}

fn json_resources_value(
    server_jar: &Path,
    client_jar: &Path,
    prefix: &str,
    label: &str,
) -> Result<Value, String> {
    let server = json_resources_map(server_jar, prefix)?;
    let client = json_resources_map(client_jar, prefix)?;
    if server.is_empty() || server != client {
        return Err(format!("Reference {label} resources missing or disagree between server and client jars"));
    }
    let obj: Map<String, Value> = server
        .into_iter()
        .map(|(k, v)| (k, Value::String(v)))
        .collect();
    Ok(Value::Object(obj))
}

fn is_indexed_override_candidate(asset_rel: &str) -> bool {
    asset_rel == "pack.mcmeta"
        || asset_rel.starts_with("minecraft/lang/")
        || asset_rel.starts_with("minecraft/font/")
        || matches!(
            asset_rel,
            "minecraft/textures/gui/title/background/panorama_0.png"
                | "minecraft/textures/gui/title/background/panorama_1.png"
                | "minecraft/textures/gui/title/background/panorama_2.png"
                | "minecraft/textures/gui/title/background/panorama_3.png"
                | "minecraft/textures/gui/title/background/panorama_4.png"
                | "minecraft/textures/gui/title/background/panorama_5.png"
                | "minecraft/textures/gui/title/background/panorama_overlay.png"
        )
}

/// Extracts `assets/`, `data/`, and `pack.png` from `client.jar`, preserving verified indexed assets.
pub fn extract_client_jar_resources(
    client_jar: &Path,
    stage_dir: &Path,
    indexed_assets: Option<&HashMap<String, PathBuf>>,
    cancel: &CancelFlag,
) -> Result<(Option<u64>, u64), String> {
    let file = fs::File::open(client_jar).map_err(|e| format!("{}: {e}", client_jar.display()))?;
    let mut archive = ZipArchive::new(file).map_err(|e| format!("{}: {e}", client_jar.display()))?;

    let world_version = if archive.index_for_name("version.json").is_some() {
        let text = read_zip_text(&mut archive, "version.json")?;
        let val: Value = serde_json::from_str(&text).map_err(|e| format!("version.json: {e}"))?;
        val.get("world_version").and_then(Value::as_u64)
    } else {
        None
    };

    let mut extracted_count = 0u64;
    let total_entries = archive.len();
    for i in 0..total_entries {
        if i % 128 == 0 {
            cancel.check()?;
        }
        let (name, is_dir, is_sym, size, crc) = {
            let entry = archive.by_index(i).map_err(|e| e.to_string())?;
            let name = entry.name().map_err(|e| e.to_string())?.into_owned();
            (
                name,
                entry.is_dir(),
                entry.is_symlink(),
                entry.size(),
                entry.crc32(),
            )
        };
        if is_dir || !(name.starts_with("assets/") || name.starts_with("data/") || name == "pack.png") {
            continue;
        }
        if is_sym {
            return Err(format!("Symlink in client jar resource: {name}"));
        }
        let rel = safe_relative(&name)?;
        extracted_count += 1;

        if let Some(asset_map) = indexed_assets {
            if let Some(asset_rel) = name.strip_prefix("assets/") {
                if is_indexed_override_candidate(asset_rel) && asset_map.contains_key(asset_rel) {
                    let target = stage_dir.join(&rel);
                    if target.is_file() {
                        continue;
                    }
                }
            }
        }

        let target = stage_dir.join(rel);
        if target.is_file() {
            if let Ok((existing_size, existing_crc)) = crc32_file(&target) {
                if existing_size == size && existing_crc == crc {
                    continue;
                }
            }
        }
        let bytes = read_zip_bytes(&mut archive, &name)?;
        atomic_write_bytes(&target, &bytes)?;
    }
    Ok((world_version, extracted_count))
}

/// Extracts `data/minecraft/structure/**/*.nbt` and builds the 19 `worldgen/*.json` bundles.
pub fn write_worldgen_bundles(
    server_jar: &Path,
    client_jar: &Path,
    stage_dir: &Path,
    cancel: &CancelFlag,
) -> Result<(u64, u64), String> {
    let out = stage_dir.join("worldgen");
    fs::create_dir_all(&out).map_err(|e| e.to_string())?;

    let structure_prefix = "data/minecraft/structure/";
    let file = fs::File::open(server_jar).map_err(|e| format!("{}: {e}", server_jar.display()))?;
    let mut server_arch = ZipArchive::new(file).map_err(|e| format!("{}: {e}", server_jar.display()))?;
    let mut nbt_names = Vec::new();
    for i in 0..server_arch.len() {
        let entry = server_arch.by_index(i).map_err(|e| e.to_string())?;
        let name = entry.name().map_err(|e| e.to_string())?;
        if name.starts_with(structure_prefix) && name.ends_with(".nbt") && !entry.is_dir() {
            nbt_names.push(name.into_owned());
        }
    }
    nbt_names.sort();

    let mut structures_count = 0u64;
    for name in &nbt_names {
        cancel.check()?;
        let rel = safe_relative(&name[structure_prefix.len()..])?;
        let dest = out.join("structure/minecraft").join(rel);
        let bytes = read_zip_bytes(&mut server_arch, name)?;
        if dest.is_file() && fs::read(&dest).ok().as_deref() == Some(bytes.as_slice()) {
            structures_count += 1;
            continue;
        }
        atomic_write_bytes(&dest, &bytes)?;
        structures_count += 1;
    }

    let mut maps_written = 0u64;
    for (bundle_name, rel_prefix) in WORLDGEN_BUNDLES {
        cancel.check()?;
        let prefix = format!("data/minecraft/{rel_prefix}/");
        let val = json_resources_value(server_jar, client_jar, &prefix, rel_prefix)?;
        let serialized = serde_json::to_vec(&val).map_err(|e| e.to_string())?;
        atomic_write_bytes(&out.join(format!("{bundle_name}.json")), &serialized)?;
        maps_written += 1;
    }

    Ok((structures_count, maps_written))
}

/// Phase 1 (Rust): Verify/download Mojang files, unpack bundler, extract assets/worldgen, build Java plan.
pub fn run_phase1(
    cfg: &PrepareConfig,
    cancel: &CancelFlag,
    on_progress: &(dyn Fn(&str, u64, u64) + Sync),
) -> Result<Phase1Output, String> {
    let started = Instant::now();
    cancel.reset();

    let lock = LockFile::read(&cfg.lock_path)?;
    let controls = BedrockControlsManifest::read(&cfg.controls_manifest_path)?;

    let horizon_local = cfg.files_dir.join(".local/horizon-client");
    let work_dir = cfg.files_dir.join(".local/prepare-work").join(&lock.minecraft.version);
    let stage_dir = horizon_local.join(format!("{}.partial", lock.minecraft.version));
    let target_resources_dir = horizon_local.join(&lock.minecraft.version);

    fs::create_dir_all(&work_dir).map_err(|e| format!("{}: {e}", work_dir.display()))?;
    fs::create_dir_all(&stage_dir).map_err(|e| format!("{}: {e}", stage_dir.display()))?;
    cleanup_partials(&work_dir);
    cleanup_partials(&stage_dir);

    let fetcher = HttpFetcher::new(cfg.allow_loopback, cancel.clone());
    let mut stats = DownloadStats::default();
    let catalog_url = cfg
        .catalog_url
        .as_deref()
        .unwrap_or(DEFAULT_CATALOG_URL);

    on_progress("Checking Mojang 26.3 manifest", 0, 1);
    let game_home = cfg.game_home.as_deref();
    let manifest = resolve_version_manifest(
        &fetcher,
        &lock,
        &work_dir,
        game_home,
        catalog_url,
        &mut stats,
    )?;
    on_progress("Checking Mojang 26.3 manifest", 1, 1);

    let artifact_plan = build_artifact_plan(&manifest, &lock.minecraft, &work_dir, game_home)?;
    let jar_stats = fetch_batch_parallel(
        &fetcher,
        &artifact_plan.specs,
        cfg.download_workers,
        &artifact_plan.optional_netty_dests,
        "Downloading/verifying jars and libraries",
        on_progress,
    )?;
    stats.files_reused += jar_stats.files_reused;
    stats.bytes_reused += jar_stats.bytes_reused;
    stats.files_downloaded += jar_stats.files_downloaded;
    stats.bytes_downloaded += jar_stats.bytes_downloaded;

    // Asset index + indexed objects
    on_progress("Downloading asset index", 0, 1);
    let idx_outcome = crate::download::ensure_verified_file(&fetcher, &artifact_plan.asset_index_spec)?;
    match idx_outcome {
        crate::download::FetchOutcome::AlreadyPresent(b)
        | crate::download::FetchOutcome::ReusedFromStore(b) => {
            stats.files_reused += 1;
            stats.bytes_reused += b;
        }
        crate::download::FetchOutcome::Downloaded(b) => {
            stats.files_downloaded += 1;
            stats.bytes_downloaded += b;
        }
    }
    let idx_bytes = fs::read(&artifact_plan.asset_index_spec.dest)
        .map_err(|e| format!("Read asset index: {e}"))?;
    atomic_write_bytes(&stage_dir.join("asset-index.json"), &idx_bytes)?;

    let objects_base = cfg
        .objects_base_url
        .as_deref()
        .unwrap_or("https://resources.download.minecraft.net");
    let (obj_specs, name_to_obj) = plan_asset_objects(
        &artifact_plan.asset_index_spec.dest,
        objects_base,
        &work_dir,
        game_home,
    )?;
    let empty_optional = HashSet::new();
    let asset_stats = fetch_batch_parallel(
        &fetcher,
        &obj_specs,
        cfg.download_workers,
        &empty_optional,
        "Downloading/verifying asset objects",
        on_progress,
    )?;
    stats.files_reused += asset_stats.files_reused;
    stats.bytes_reused += asset_stats.bytes_reused;
    stats.files_downloaded += asset_stats.files_downloaded;
    stats.bytes_downloaded += asset_stats.bytes_downloaded;

    // Bedrock touch controls
    let planned_controls =
        plan_bedrock_controls(&controls, cfg.controls_base_url.as_deref(), &work_dir)?;
    let control_specs: Vec<_> = planned_controls.iter().map(|(s, _)| s.clone()).collect();
    let ctrl_stats = fetch_batch_parallel(
        &fetcher,
        &control_specs,
        cfg.download_workers.min(4),
        &empty_optional,
        "Downloading touch controls",
        on_progress,
    )?;
    stats.files_reused += ctrl_stats.files_reused;
    stats.bytes_reused += ctrl_stats.bytes_reused;
    stats.files_downloaded += ctrl_stats.files_downloaded;
    stats.bytes_downloaded += ctrl_stats.bytes_downloaded;

    let controls_target = stage_dir.join("assets/minecraft/textures/gui/bedrock");
    let mut controls_ready = 0u64;
    for (spec, rel) in &planned_controls {
        cancel.check()?;
        let dst = controls_target.join(rel);
        atomic_copy_file(&spec.dest, &dst, cancel)?;
        controls_ready += 1;
    }

    // Unpack server bundler & write Java execution plan
    on_progress("Unpacking server bundler", 0, 1);
    let (server_game_jar, server_libs) = unpack_server_bundle(
        &artifact_plan.server_jar_path,
        &work_dir,
        &lock.minecraft.version,
        cancel,
    )?;
    let java_plan = write_java_plan(
        &lock.minecraft.version,
        lock.minecraft.java_major,
        &artifact_plan.server_jar_path,
        &server_game_jar,
        &server_libs,
        &artifact_plan.client_jar_path,
        &artifact_plan.client_library_paths,
        &work_dir,
        &stage_dir,
    )?;

    // Write pre-Java tag files required by exporters (verifying server == client)
    let block_tags_val = json_resources_value(
        &server_game_jar,
        &artifact_plan.client_jar_path,
        "data/minecraft/tags/block/",
        "block tags",
    )?;
    let block_tags_bytes = serde_json::to_vec_pretty(&block_tags_val).map_err(|e| e.to_string())?;
    atomic_write_bytes(&stage_dir.join("block-tags.json"), &block_tags_bytes)?;
    atomic_write_bytes(&work_dir.join("motion/tags.json"), &block_tags_bytes)?;
    atomic_write_bytes(&work_dir.join("render/tags.json"), &block_tags_bytes)?;

    let dmg_types = json_resources_value(
        &server_game_jar,
        &artifact_plan.client_jar_path,
        "data/minecraft/damage_type/",
        "damage_type",
    )?;
    let dmg_tags = json_resources_value(
        &server_game_jar,
        &artifact_plan.client_jar_path,
        "data/minecraft/tags/damage_type/",
        "tags/damage_type",
    )?;
    atomic_write_bytes(
        &work_dir.join("health/types.json"),
        &serde_json::to_vec_pretty(&dmg_types).map_err(|e| e.to_string())?,
    )?;
    atomic_write_bytes(
        &work_dir.join("health/tags.json"),
        &serde_json::to_vec_pretty(&dmg_tags).map_err(|e| e.to_string())?,
    )?;
    on_progress("Unpacking server bundler", 1, 1);

    // Copy indexed assets (sounds, panorama, unifont, packs, languages, etc.) before/over placeholders
    let total_indexed = name_to_obj.len() as u64;
    on_progress("Installing indexed Minecraft assets", 0, total_indexed);
    let mut indexed_copied = 0u64;
    for (name, src_obj) in &name_to_obj {
        cancel.check()?;
        let rel = safe_relative(name)?;
        let dst = stage_dir.join("assets").join(rel);
        let expected_hash = src_obj
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or_default();
        if !(dst.is_file() && sha1_file(&dst).is_ok_and(|h| h == expected_hash)) {
            atomic_copy_file(src_obj, &dst, cancel)?;
        }
        indexed_copied += 1;
        if indexed_copied % 256 == 0 || indexed_copied == total_indexed {
            on_progress(
                "Installing indexed Minecraft assets",
                indexed_copied,
                total_indexed,
            );
        }
    }

    // Extract client.jar resources (skipping already-installed indexed overrides!)
    on_progress("Extracting client.jar assets and data", 0, 1);
    let (data_version_opt, client_resource_files) = extract_client_jar_resources(
        &artifact_plan.client_jar_path,
        &stage_dir,
        Some(&name_to_obj),
        cancel,
    )?;
    let origin_meta = serde_json::json!({
        "minecraft": lock.minecraft.version,
        "client_sha256": sha256_file(&artifact_plan.client_jar_path)?,
        "server_sha256": sha256_file(&server_game_jar)?,
        "data_version": data_version_opt.unwrap_or(0),
        "resource_files": client_resource_files,
    });
    atomic_write_bytes(
        &work_dir.join("origin.json"),
        &serde_json::to_vec_pretty(&origin_meta).map_err(|e| e.to_string())?,
    )?;
    on_progress("Extracting client.jar assets and data", 1, 1);

    // Write worldgen JSON bundles + structure templates
    on_progress("Writing worldgen bundles", 0, 1);
    let (worldgen_structures_extracted, worldgen_maps_written) = write_worldgen_bundles(
        &server_game_jar,
        &artifact_plan.client_jar_path,
        &stage_dir,
        cancel,
    )?;
    on_progress("Writing worldgen bundles", 1, 1);

    Ok(Phase1Output {
        work_dir: work_dir.to_string_lossy().into_owned(),
        stage_dir: stage_dir.to_string_lossy().into_owned(),
        target_resources_dir: target_resources_dir.to_string_lossy().into_owned(),
        java_plan,
        download_stats: stats,
        client_resource_files,
        indexed_assets_copied: indexed_copied,
        controls_ready,
        worldgen_structures_extracted,
        worldgen_maps_written,
        elapsed_ms: started.elapsed().as_millis() as u64,
        peak_rss_kb: peak_rss_kb(),
    })
}

fn copy_checked(src: &Path, dst: &Path, cancel: &CancelFlag) -> Result<(), String> {
    if !src.is_file() {
        return Err(format!("Missing expected exporter output: {}", src.display()));
    }
    atomic_copy_file(src, dst, cancel).map(|_| ())
}

fn read_json_value(path: &Path) -> Result<Value, String> {
    let raw = fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
    serde_json::from_str(&raw).map_err(|e| format!("Invalid JSON in {}: {e}", path.display()))
}

fn install_datagen_reports(stage_dir: &Path, reports_dir: &Path, cancel: &CancelFlag) -> Result<u64, String> {
    for name in ["registries.json", "commands.json"] {
        copy_checked(&reports_dir.join(name), &stage_dir.join(name), cancel)?;
    }

    let items_dir = reports_dir.join("minecraft/components/item");
    let mut item_paths: Vec<PathBuf> = fs::read_dir(&items_dir)
        .map_err(|e| format!("{}: {e}", items_dir.display()))?
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| {
            p.file_name()
                .and_then(|n| n.to_str())
                .is_some_and(|n| n.ends_with(".json") && !n.starts_with('.'))
        })
        .collect();
    item_paths.sort();

    let mut components = Map::new();
    for path in item_paths {
        cancel.check()?;
        let stem = path
            .file_stem()
            .and_then(|s| s.to_str())
            .ok_or("Invalid item component report filename")?;
        let val = read_json_value(&path)?;
        let comp = val
            .get("components")
            .ok_or_else(|| format!("{} missing components field", path.display()))?;
        components.insert(format!("minecraft:{stem}"), comp.clone());
    }
    let items_count = components.len() as u64;
    let items_bytes = serde_json::to_vec_pretty(&Value::Object(components)).map_err(|e| e.to_string())?;
    atomic_write_bytes(&stage_dir.join("items.json"), &items_bytes)?;

    let biome_src = reports_dir.join("biome_parameters/minecraft");
    let biome_dirs = [
        stage_dir.join("biome-parameters"),
        stage_dir.join("worldgen/biome_parameters"),
    ];
    for dir in &biome_dirs {
        fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    }
    let mut biome_files: Vec<PathBuf> = fs::read_dir(&biome_src)
        .map_err(|e| format!("{}: {e}", biome_src.display()))?
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| {
            p.file_name()
                .and_then(|n| n.to_str())
                .is_some_and(|n| n.ends_with(".json") && !n.starts_with('.'))
        })
        .collect();
    biome_files.sort();
    for file in biome_files {
        let name = file.file_name().unwrap();
        for dir in &biome_dirs {
            copy_checked(&file, &dir.join(name), cancel)?;
        }
    }
    Ok(items_count)
}

fn write_worldgen_block_flags(stage_dir: &Path) -> Result<(), String> {
    let facts = read_json_value(&stage_dir.join("facts.json"))?;
    let states = facts
        .get("states")
        .and_then(Value::as_array)
        .ok_or("facts.json missing states array")?;
    let mut flags = Vec::with_capacity(states.len());
    for entry in states {
        let arr = entry.as_array().ok_or("facts.json state entry is not array")?;
        if arr.len() < 3 {
            return Err("facts.json state entry shorter than 3 items".to_string());
        }
        flags.push(Value::Array(vec![arr[1].clone(), arr[2].clone()]));
    }
    let out = stage_dir.join("worldgen/block_flags.json");
    let bytes = serde_json::to_vec(&Value::Array(flags)).map_err(|e| e.to_string())?;
    atomic_write_bytes(&out, &bytes)
}

/// Packages resources in `resources_dir` into `output_zip` atomically via `.partial`.
/// If `generated_only` is true, strictly filters by the 33-entry `is_generated_engine_file()` allowlist.
pub fn package_resources_zip(
    resources_dir: &Path,
    output_zip: &Path,
    generated_only: bool,
    bootstrap_json: Option<&Value>,
    cancel: &CancelFlag,
) -> Result<u64, String> {
    fn walk_files(root: &Path, dir: &Path, out: &mut Vec<PathBuf>) -> Result<(), String> {
        for entry in fs::read_dir(dir).map_err(|e| format!("{}: {e}", dir.display()))? {
            let entry = entry.map_err(|e| e.to_string())?;
            let path = entry.path();
            if path.is_dir() {
                walk_files(root, &path, out)?;
            } else if path.is_file() {
                out.push(path);
            }
        }
        Ok(())
    }

    let mut files = Vec::new();
    walk_files(resources_dir, resources_dir, &mut files)?;
    let mut rel_entries: Vec<(String, PathBuf)> = Vec::new();
    for path in files {
        let rel = path
            .strip_prefix(resources_dir)
            .map_err(|e| e.to_string())?;
        let posix = rel
            .components()
            .map(|c| c.as_os_str().to_string_lossy().into_owned())
            .collect::<Vec<_>>()
            .join("/");
        if posix.ends_with(".partial") || posix.ends_with(".lock") {
            continue;
        }
        if generated_only && !is_generated_engine_file(&posix) {
            continue;
        }
        rel_entries.push((posix, path));
    }
    rel_entries.sort_by(|a, b| a.0.cmp(&b.0));

    if let Some(parent) = output_zip.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    }
    let partial = crate::files::partial_path(output_zip);
    let write_res = (|| -> Result<u64, String> {
        let file = fs::File::create(&partial).map_err(|e| format!("{}: {e}", partial.display()))?;
        let mut zip = ZipWriter::new(file);
        let opts = SimpleFileOptions::default()
            .compression_method(CompressionMethod::Deflated)
            .compression_level(Some(1));
        let mut count = 0u64;
        for (posix, src_path) in &rel_entries {
            cancel.check()?;
            zip.start_file(posix, opts).map_err(|e| e.to_string())?;
            let mut reader =
                fs::File::open(src_path).map_err(|e| format!("{}: {e}", src_path.display()))?;
            let mut buf = [0u8; 32 * 1024];
            loop {
                let n = reader.read(&mut buf).map_err(|e| e.to_string())?;
                if n == 0 {
                    break;
                }
                zip.write_all(&buf[..n]).map_err(|e| e.to_string())?;
            }
            count += 1;
        }
        if let Some(bs) = bootstrap_json {
            let encoded = serde_json::to_vec_pretty(bs).map_err(|e| e.to_string())?;
            zip.start_file("bootstrap.json", opts)
                .map_err(|e| e.to_string())?;
            zip.write_all(&encoded).map_err(|e| e.to_string())?;
            count += 1;
        }
        let finished = zip.finish().map_err(|e| e.to_string())?;
        finished.sync_all().map_err(|e| e.to_string())?;
        Ok(count)
    })();

    match write_res {
        Ok(count) => {
            fs::rename(&partial, output_zip).map_err(|e| {
                let _ = fs::remove_file(&partial);
                format!("{}: {e}", output_zip.display())
            })?;
            Ok(count)
        }
        Err(err) => {
            let _ = fs::remove_file(&partial);
            Err(err)
        }
    }
}

/// Phase 2 (Rust): Assemble Java exporter + datagen outputs, build survival rules & manifests,
/// package `<filesDir>/engine-data.zip`, and atomically activate `.local/horizon-client/<version>`.
pub fn run_phase2_finalize(
    cfg: &PrepareConfig,
    cancel: &CancelFlag,
    on_progress: &(dyn Fn(&str, u64, u64) + Sync),
) -> Result<Phase2Output, String> {
    let started = Instant::now();
    cancel.check()?;
    let lock = LockFile::read(&cfg.lock_path)?;
    let horizon_local = cfg.files_dir.join(".local/horizon-client");
    let work_dir = cfg.files_dir.join(".local/prepare-work").join(&lock.minecraft.version);
    let stage_dir = horizon_local.join(format!("{}.partial", lock.minecraft.version));
    let target_resources_dir = horizon_local.join(&lock.minecraft.version);

    if !stage_dir.is_dir() {
        return Err(format!("Missing Phase 1 stage directory: {}", stage_dir.display()));
    }

    on_progress("Assembling Java exporter outputs", 0, 1);
    copy_checked(
        &work_dir.join("motion/movement-properties.nbt"),
        &stage_dir.join("movement-properties.nbt"),
        cancel,
    )?;
    copy_checked(
        &work_dir.join("motion/catalog.json"),
        &stage_dir.join("attributes.json"),
        cancel,
    )?;
    copy_checked(
        &work_dir.join("motion/landing-definitions.json"),
        &stage_dir.join("landing-definitions.json"),
        cancel,
    )?;

    for (src, dst) in [
        ("types.json", "damage-types.json"),
        ("tags.json", "damage-tags.json"),
        ("catalog.json", "damage-catalog.json"),
        ("ids.json", "player-float-ids.json"),
    ] {
        copy_checked(&work_dir.join("health").join(src), &stage_dir.join(dst), cancel)?;
    }

    copy_checked(
        &work_dir.join("collision/cached-collision.nbt"),
        &stage_dir.join("cached-collision.nbt"),
        cancel,
    )?;
    copy_checked(
        &work_dir.join("render/definitions.json"),
        &stage_dir.join("render-definitions.json"),
        cancel,
    )?;
    copy_checked(
        &work_dir.join("render/occlusion.nbt"),
        &stage_dir.join("occlusion.nbt"),
        cancel,
    )?;
    copy_checked(
        &work_dir.join("command-registries/registries.json"),
        &stage_dir.join("command-registries.json"),
        cancel,
    )?;

    let blocks_out = stage_dir.join("worldgen/blocks");
    fs::create_dir_all(&blocks_out).map_err(|e| e.to_string())?;
    let behavior_dir = work_dir.join("block-behavior");
    for name in BLOCK_BEHAVIOR_COPY_FILES {
        copy_checked(&behavior_dir.join(name), &blocks_out.join(name), cancel)?;
    }
    let classes_val = read_json_value(&behavior_dir.join("survival-classes.json"))?;
    let placement_val = read_json_value(&behavior_dir.join("survival-placement-classes.json"))?;
    let support_val = read_json_value(&behavior_dir.join("survival-support-tags.json"))?;
    let survival_rules = survival::bind_definitions(&classes_val, &placement_val, &support_val)?;
    let survival_rules_count = survival_rules.as_object().map_or(0, Map::len) as u64;
    atomic_write_bytes(
        &blocks_out.join("survival-rules.json"),
        &serde_json::to_vec(&survival_rules).map_err(|e| e.to_string())?,
    )?;

    if !blocks_out.join("structure-blocks.json").is_file() {
        return Err("Missing structure-blocks.json from VanillaStructureBlockExporter".to_string());
    }
    on_progress("Assembling Java exporter outputs", 1, 1);

    on_progress("Installing datagen reports and block flags", 0, 1);
    let items_count =
        install_datagen_reports(&stage_dir, &work_dir.join("datagen/out/reports"), cancel)?;
    write_worldgen_block_flags(&stage_dir)?;
    on_progress("Installing datagen reports and block flags", 1, 1);

    // Verify every required generated engine file exists before stamping manifests.
    for required in [
        "facts.json",
        "gameplay-facts.json",
        "block-behaviours.json",
        "creative-inventory.json",
        "model-states.json",
        "entity-models.json",
        "mob-data.json",
        "entity-types.json",
        "items.json",
        "registries.json",
        "commands.json",
        "worldgen/block_flags.json",
    ] {
        if !stage_dir.join(required).is_file() {
            return Err(format!("Missing required generated file: {required}"));
        }
    }

    on_progress("Writing manifests and engine-data.zip", 0, 1);
    let origin = read_json_value(&work_dir.join("origin.json"))?;
    let health_files_sha256 = file_sha256_map(
        &stage_dir,
        &[
            "damage-types.json",
            "damage-tags.json",
            "damage-catalog.json",
            "player-float-ids.json",
        ],
    )?;
    let motion_files_sha256 = file_sha256_map(
        &stage_dir,
        &[
            "movement-properties.nbt",
            "attributes.json",
            "landing-definitions.json",
        ],
    )?;
    let render_files_sha256 =
        file_sha256_map(&stage_dir, &["render-definitions.json", "occlusion.nbt"])?;
    let manifest = serde_json::json!({
        "schema": HORIZON_SCHEMA,
        "minecraft": lock.minecraft.version,
        "client_sha256": origin.get("client_sha256").cloned().unwrap_or(Value::Null),
        "server_sha256": origin.get("server_sha256").cloned().unwrap_or(Value::Null),
        "health_files_sha256": health_files_sha256,
        "motion_files_sha256": motion_files_sha256,
        "collision_sha256": sha256_file(&stage_dir.join("cached-collision.nbt"))?,
        "render_files_sha256": render_files_sha256,
        "model_states_sha256": sha256_file(&stage_dir.join("model-states.json"))?,
        "data_version": origin.get("data_version").cloned().unwrap_or(Value::Null),
        "facts_sha256": sha256_file(&stage_dir.join("facts.json"))?,
        "block_tags_sha256": sha256_file(&stage_dir.join("block-tags.json"))?,
        "resource_files": origin.get("resource_files").cloned().unwrap_or(Value::Null),
    });
    atomic_write_bytes(
        &stage_dir.join("horizon-resources.json"),
        &serde_json::to_vec_pretty(&manifest).map_err(|e| e.to_string())?,
    )?;
    atomic_write_bytes(
        &stage_dir.join("horizon-prepare.json"),
        format!("{{\"output_version\":{HORIZON_OUTPUT_VERSION}}}\n").as_bytes(),
    )?;
    atomic_write_bytes(
        &stage_dir.join("android-downloads-v2.json"),
        b"{\"schema\":2}\n",
    )?;
    atomic_write_bytes(&stage_dir.join("audio-complete.json"), b"{}\n")?;

    let engine_data_zip = cfg.files_dir.join("engine-data.zip");
    let engine_data_entries =
        package_resources_zip(&stage_dir, &engine_data_zip, true, None, cancel)?;

    atomic_activate_dir(&stage_dir, &target_resources_dir)?;
    on_progress("Writing manifests and engine-data.zip", 1, 1);

    Ok(Phase2Output {
        target_resources_dir: target_resources_dir.to_string_lossy().into_owned(),
        engine_data_zip: engine_data_zip.to_string_lossy().into_owned(),
        engine_data_entries,
        survival_rules_count,
        items_count,
        elapsed_ms: started.elapsed().as_millis() as u64,
        peak_rss_kb: peak_rss_kb(),
    })
}

/// Builds a filename -> lowercase hex SHA-256 JSON object matching horizon-prepare's file hash maps.
fn file_sha256_map(stage_dir: &Path, names: &[&str]) -> Result<Value, String> {
    let mut map = Map::new();
    for name in names {
        map.insert(
            (*name).to_string(),
            Value::String(sha256_file(&stage_dir.join(name))?),
        );
    }
    Ok(Value::Object(map))
}

/// Checks whether on-device `26.3` resources are complete and stamped.
pub fn is_prepared(files_dir: &Path, version: &str) -> bool {
    let root = files_dir.join(".local/horizon-client").join(version);
    root.join("horizon-resources.json").is_file()
        && root.join("android-downloads-v2.json").is_file()
        && root.join("audio-complete.json").is_file()
        && root.join("horizon-prepare.json").is_file()
}
