// Blaze Launcher — on-device 26.3 engine-data preparer
// Copyright (C) 2026 Blaze Launcher contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Server bundler extraction (`META-INF/versions.list`, `META-INF/libraries.list`) and `:jvm` plan generation.

use crate::files::{
    atomic_write_bytes, safe_relative, sha256_bytes, sha256_file, CancelFlag,
};
use serde::{Deserialize, Serialize};
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use zip::ZipArchive;

pub struct BundledEntry {
    pub sha256: String,
    pub id: String,
    pub path: PathBuf,
    pub member_tail: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JavaPlan {
    pub minecraft_version: String,
    pub java_major: u32,
    pub server_jar: String,
    pub server_game_jar: String,
    pub client_jar: String,
    pub server_classpath: String,
    pub client_classpath: String,
    pub server_classes_dir: String,
    pub client_classes_dir: String,
    pub datagen_out_dir: String,
    pub server_tasks_jsonl: String,
    pub client_tasks_jsonl: String,
    pub command_registries_dir: String,
    pub creative_inventory_json: String,
    pub stage_dir: String,
    pub work_dir: String,
}

fn parse_list(archive: &mut ZipArchive<fs::File>, member: &str) -> Result<Vec<BundledEntry>, String> {
    let mut entry = archive
        .by_name(member)
        .map_err(|e| format!("Missing {member} in server.jar: {e}"))?;
    let mut text = String::new();
    entry
        .read_to_string(&mut text)
        .map_err(|e| format!("Failed reading {member}: {e}"))?;
    let mut out = Vec::new();
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let parts: Vec<&str> = trimmed.split('\t').collect();
        if parts.len() != 3 {
            return Err(format!("Malformed line in {member}: {trimmed}"));
        }
        let rel = safe_relative(parts[2])?;
        let posix = parts[2]
            .split('/')
            .filter(|p| !p.is_empty() && *p != ".")
            .collect::<Vec<_>>()
            .join("/");
        out.push(BundledEntry {
            sha256: parts[0].to_string(),
            id: parts[1].to_string(),
            path: rel,
            member_tail: posix,
        });
    }
    Ok(out)
}

fn extract_verified_member(
    archive: &mut ZipArchive<fs::File>,
    member_name: &str,
    dest: &Path,
    expected_sha256: &str,
    cancel: &CancelFlag,
) -> Result<(), String> {
    cancel.check()?;
    if dest.is_file() && sha256_file(dest)?.eq_ignore_ascii_case(expected_sha256) {
        return Ok(());
    }
    let bytes = {
        let mut file = archive
            .by_name(member_name)
            .map_err(|e| format!("Missing {member_name}: {e}"))?;
        let mut buf = Vec::with_capacity(file.size() as usize);
        file.read_to_end(&mut buf)
            .map_err(|e| format!("Read {member_name}: {e}"))?;
        buf
    };
    let actual = sha256_bytes(&bytes);
    if !actual.eq_ignore_ascii_case(expected_sha256) {
        return Err(format!(
            "Bundled server member {member_name} sha256 mismatch: expected {expected_sha256}, got {actual}"
        ));
    }
    atomic_write_bytes(dest, &bytes)
}

/// Unpacks `META-INF/versions.list` and `META-INF/libraries.list` from `server_jar`.
/// Returns `(server_game_jar, server_library_jars)`.
pub fn unpack_server_bundle(
    server_jar: &Path,
    work_dir: &Path,
    expected_version: &str,
    cancel: &CancelFlag,
) -> Result<(PathBuf, Vec<PathBuf>), String> {
    let file = fs::File::open(server_jar).map_err(|e| format!("{}: {e}", server_jar.display()))?;
    let mut archive = ZipArchive::new(file).map_err(|e| format!("{}: {e}", server_jar.display()))?;

    if archive.index_for_name("META-INF/versions.list").is_none() {
        return Ok((server_jar.to_path_buf(), Vec::new()));
    }
    let versions = parse_list(&mut archive, "META-INF/versions.list")?;
    if versions.len() != 1 || versions[0].id != expected_version {
        return Err(format!(
            "Expected single server bundle entry matching {expected_version}"
        ));
    }
    let game_jar = work_dir.join("jars/server-game.jar");
    extract_verified_member(
        &mut archive,
        &format!("META-INF/versions/{}", versions[0].member_tail),
        &game_jar,
        &versions[0].sha256,
        cancel,
    )?;

    let libs = parse_list(&mut archive, "META-INF/libraries.list")?;
    let mut lib_paths = Vec::with_capacity(libs.len());
    for lib in libs {
        let dest = work_dir.join("libraries/server").join(&lib.path);
        extract_verified_member(
            &mut archive,
            &format!("META-INF/libraries/{}", lib.member_tail),
            &dest,
            &lib.sha256,
            cancel,
        )?;
        lib_paths.push(dest);
    }
    Ok((game_jar, lib_paths))
}

fn join_classpath(primary: &Path, extras: &[PathBuf]) -> String {
    let mut items = Vec::with_capacity(1 + extras.len());
    items.push(primary.to_string_lossy().into_owned());
    for p in extras {
        if p.is_file() {
            items.push(p.to_string_lossy().into_owned());
        }
    }
    items.join(":")
}

fn jsonl_task(log: &Path, main: &str, args: &[String]) -> Result<String, String> {
    let val = serde_json::json!([log.to_string_lossy(), main, args]);
    serde_json::to_string(&val).map_err(|e| e.to_string())
}

/// Creates exporter work directories, `server_tasks.jsonl`, `client_tasks.jsonl`, and returns `JavaPlan`.
#[allow(clippy::too_many_arguments)]
pub fn write_java_plan(
    version: &str,
    java_major: u32,
    server_jar: &Path,
    server_game_jar: &Path,
    server_libs: &[PathBuf],
    client_jar: &Path,
    client_libs: &[PathBuf],
    work_dir: &Path,
    stage_dir: &Path,
) -> Result<JavaPlan, String> {
    let server_classes_dir = work_dir.join("classes/server");
    let client_classes_dir = work_dir.join("classes/client");
    let datagen_out_dir = work_dir.join("datagen/out");
    let motion_dir = work_dir.join("motion");
    let health_dir = work_dir.join("health");
    let collision_dir = work_dir.join("collision");
    let render_dir = work_dir.join("render");
    let block_behavior_dir = work_dir.join("block-behavior");
    let command_registries_dir = work_dir.join("command-registries");
    let logs_dir = work_dir.join("logs");

    for dir in [
        &server_classes_dir,
        &client_classes_dir,
        &datagen_out_dir,
        &motion_dir,
        &health_dir,
        &collision_dir,
        &render_dir,
        &block_behavior_dir,
        &command_registries_dir,
        &logs_dir,
        &stage_dir.join("worldgen/blocks"),
    ] {
        fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    }

    let facts_json = stage_dir.join("facts.json");
    let gameplay_json = stage_dir.join("gameplay-facts.json");
    let entity_types_json = stage_dir.join("entity-types.json");
    let block_behaviours_json = stage_dir.join("block-behaviours.json");
    let structure_blocks_json = stage_dir.join("worldgen/blocks/structure-blocks.json");
    let creative_inventory_json = stage_dir.join("creative-inventory.json");
    let model_states_json = stage_dir.join("model-states.json");
    let entity_models_json = stage_dir.join("entity-models.json");
    let mob_data_json = stage_dir.join("mob-data.json");

    let abs = |p: &Path| p.to_string_lossy().into_owned();
    let server_tasks = vec![
        jsonl_task(
            &logs_dir.join("heightmap.log"),
            "VanillaHeightmapOracle",
            &["export".into(), abs(&facts_json)],
        )?,
        jsonl_task(
            &logs_dir.join("landing.log"),
            "VanillaBlockLandingOracle",
            &["export".into(), abs(&motion_dir)],
        )?,
        jsonl_task(
            &logs_dir.join("attributes.log"),
            "VanillaEntityAttributeOracle",
            &["export".into(), abs(&motion_dir)],
        )?,
        jsonl_task(
            &logs_dir.join("health.log"),
            "VanillaPlayerHealthOracle",
            &["export".into(), abs(&health_dir)],
        )?,
        jsonl_task(
            &logs_dir.join("damage.log"),
            "VanillaDamageSourceOracle",
            &["export".into(), abs(&health_dir)],
        )?,
        jsonl_task(
            &logs_dir.join("collision.log"),
            "VanillaCollisionOracle",
            &["generate".into(), abs(&collision_dir)],
        )?,
        jsonl_task(
            &logs_dir.join("render.log"),
            "VanillaFaceVisibilityOracle",
            &["export".into(), abs(&render_dir)],
        )?,
        jsonl_task(
            &logs_dir.join("gameplay.log"),
            "VanillaGameplayFactsExporter",
            &[abs(&gameplay_json)],
        )?,
        jsonl_task(
            &logs_dir.join("entity_types.log"),
            "VanillaEntityTypesExporter",
            &[abs(&entity_types_json)],
        )?,
        jsonl_task(
            &logs_dir.join("block_behaviours.log"),
            "VanillaBlockBehaviourExporter",
            &[abs(&block_behaviours_json)],
        )?,
        jsonl_task(
            &logs_dir.join("simple_block.log"),
            "VanillaSimpleBlockOracle",
            &["export".into(), abs(&block_behavior_dir)],
        )?,
        jsonl_task(
            &logs_dir.join("structure_blocks.log"),
            "VanillaStructureBlockExporter",
            &[abs(&structure_blocks_json)],
        )?,
    ];
    let server_tasks_path = work_dir.join("server_tasks.jsonl");
    atomic_write_bytes(
        &server_tasks_path,
        format!("{}\n", server_tasks.join("\n")).as_bytes(),
    )?;

    let client_tasks = vec![
        jsonl_task(
            &logs_dir.join("model_states.log"),
            "VanillaBlockStateBindingOracle",
            &["export".into(), abs(&model_states_json)],
        )?,
        jsonl_task(
            &logs_dir.join("entity_models.log"),
            "VanillaEntityModelExporter",
            &[abs(&entity_models_json)],
        )?,
        jsonl_task(
            &logs_dir.join("mob_data.log"),
            "VanillaMobDataExporter",
            &[abs(&mob_data_json), abs(client_jar)],
        )?,
    ];
    let client_tasks_path = work_dir.join("client_tasks.jsonl");
    atomic_write_bytes(
        &client_tasks_path,
        format!("{}\n", client_tasks.join("\n")).as_bytes(),
    )?;

    let plan = JavaPlan {
        minecraft_version: version.to_string(),
        java_major,
        server_jar: abs(server_jar),
        server_game_jar: abs(server_game_jar),
        client_jar: abs(client_jar),
        server_classpath: join_classpath(server_game_jar, server_libs),
        client_classpath: join_classpath(client_jar, client_libs),
        server_classes_dir: abs(&server_classes_dir),
        client_classes_dir: abs(&client_classes_dir),
        datagen_out_dir: abs(&datagen_out_dir),
        server_tasks_jsonl: abs(&server_tasks_path),
        client_tasks_jsonl: abs(&client_tasks_path),
        command_registries_dir: abs(&command_registries_dir),
        creative_inventory_json: abs(&creative_inventory_json),
        stage_dir: abs(stage_dir),
        work_dir: abs(work_dir),
    };
    let plan_bytes = serde_json::to_vec_pretty(&plan).map_err(|e| e.to_string())?;
    atomic_write_bytes(&work_dir.join("java_plan.json"), &plan_bytes)?;
    Ok(plan)
}
