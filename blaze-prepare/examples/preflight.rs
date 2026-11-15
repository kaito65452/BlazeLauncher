// Blaze Launcher — real-data preflight runner for CI (Stage 3 / Task P)
// Copyright (C) 2026 Blaze Launcher contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Executes the exact `blaze-prepare` Phase 1 -> JDK 25 exporters/datagen -> Phase 2 pipeline
//! on a desktop Linux host (`x86_64` or `aarch64`), logging per-phase timings, child JVM peak RSS
//! (`VmHWM`), and deterministic SHA-256 inventory metadata without embedding or leaking Mojang data.

use blaze_prepare::bundler::JavaPlan;
use blaze_prepare::files::{peak_rss_kb, redact_sensitive, sha256_file, CancelFlag};
use blaze_prepare::resources::{is_prepared, run_phase1, run_phase2_finalize, PrepareConfig};
use serde::Serialize;
use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

const SERVER_EXPORTER_FILES: &[&str] = &[
    "ParityData.java",
    "PrepareWorker.java",
    "PrepareBatchShim.java",
    "VanillaBlockBehaviourExporter.java",
    "VanillaBlockLandingOracle.java",
    "VanillaBlockPropertiesOracle.java",
    "VanillaBlockStateCodecOracle.java",
    "VanillaBlockSupportOracle.java",
    "VanillaBlockSurvivalOracle.java",
    "VanillaCollisionOracle.java",
    "VanillaCommandParseOracle.java",
    "VanillaCreativeTabsExporter.java",
    "VanillaDamageSourceOracle.java",
    "VanillaEntityAttributeOracle.java",
    "VanillaEntityTypesExporter.java",
    "VanillaFaceVisibilityOracle.java",
    "VanillaGameplayFactsExporter.java",
    "VanillaHeightmapOracle.java",
    "VanillaMovementRegistryOracle.java",
    "VanillaPlacementOracle.java",
    "VanillaPlayerHealthOracle.java",
    "VanillaSimpleBlockOracle.java",
    "VanillaStructureBlockExporter.java",
];

const CLIENT_EXPORTER_FILES: &[&str] = &[
    "PrepareWorker.java",
    "PrepareBatchShim.java",
    "VanillaBlockStateBindingOracle.java",
    "VanillaCuboidModelOracle.java",
    "VanillaEntityModelExporter.java",
    "VanillaMobDataExporter.java",
];

#[derive(Serialize)]
struct StepStat {
    name: String,
    elapsed_ms: u64,
    peak_rss_kb: u64,
}

#[derive(Serialize)]
struct FileDigestEntry {
    bytes: u64,
    sha256: String,
}

#[derive(Serialize)]
struct PreflightSummary {
    status: String,
    arch: String,
    total_ms: u64,
    rust_process_peak_rss_kb: u64,
    max_step_peak_rss_kb: u64,
    phase1_ms: u64,
    phase2_ms: u64,
    steps: Vec<StepStat>,
    work_dir: String,
    manifest_json: String,
    client_jar: String,
    server_jar: String,
    libraries_dir: String,
    assets_dir: String,
    target_resources_dir: String,
    engine_data_zip: String,
    engine_data_entries: u64,
    survival_rules_count: u64,
    items_count: u64,
    files_downloaded: u64,
    files_reused: u64,
    bytes_downloaded: u64,
    client_resource_files: u64,
    indexed_assets_copied: u64,
    worldgen_structures_extracted: u64,
    worldgen_maps_written: u64,
    is_prepared: bool,
    warnings: Vec<String>,
    inventory: BTreeMap<String, FileDigestEntry>,
}

struct CliArgs {
    files_dir: PathBuf,
    lock_path: PathBuf,
    controls_manifest: PathBuf,
    parity_sources: PathBuf,
    java_home: PathBuf,
    heap_mb: u32,
    summary_json: PathBuf,
}

fn parse_args() -> Result<CliArgs, String> {
    let mut files_dir = None;
    let mut lock_path = None;
    let mut controls_manifest = None;
    let mut parity_sources = None;
    let mut java_home = env::var_os("JAVA_HOME").map(PathBuf::from);
    let mut heap_mb: u32 = 2048;
    let mut summary_json = None;

    let mut it = env::args_os().skip(1);
    while let Some(arg) = it.next() {
        let key = arg.to_string_lossy();
        let next_val = |flag: &str, it: &mut std::iter::Skip<env::ArgsOs>| -> Result<String, String> {
            it.next()
                .map(|s| s.to_string_lossy().into_owned())
                .ok_or_else(|| format!("Missing value for {flag}"))
        };
        match key.as_ref() {
            "--files-dir" => files_dir = Some(PathBuf::from(next_val("--files-dir", &mut it)?)),
            "--lock-path" => lock_path = Some(PathBuf::from(next_val("--lock-path", &mut it)?)),
            "--controls-manifest" => {
                controls_manifest = Some(PathBuf::from(next_val("--controls-manifest", &mut it)?))
            }
            "--parity-sources" => {
                parity_sources = Some(PathBuf::from(next_val("--parity-sources", &mut it)?))
            }
            "--java-home" => java_home = Some(PathBuf::from(next_val("--java-home", &mut it)?)),
            "--heap-mb" => {
                heap_mb = next_val("--heap-mb", &mut it)?
                    .parse::<u32>()
                    .map_err(|e| format!("Invalid --heap-mb: {e}"))?;
            }
            "--summary-json" => {
                summary_json = Some(PathBuf::from(next_val("--summary-json", &mut it)?))
            }
            other => return Err(format!("Unknown argument: {other}")),
        }
    }

    Ok(CliArgs {
        files_dir: files_dir.ok_or("Missing --files-dir")?,
        lock_path: lock_path.ok_or("Missing --lock-path")?,
        controls_manifest: controls_manifest.ok_or("Missing --controls-manifest")?,
        parity_sources: parity_sources.ok_or("Missing --parity-sources")?,
        java_home: java_home.ok_or("Missing --java-home and JAVA_HOME")?,
        heap_mb,
        summary_json: summary_json.ok_or("Missing --summary-json")?,
    })
}

fn quote_argfile(val: &str) -> String {
    let escaped = val.replace('\\', "\\\\").replace('"', "\\\"");
    format!("\"{escaped}\"")
}

fn write_javac_argfile(
    dest: &Path,
    classpath: &str,
    source_root: &Path,
    output_dir: &str,
    parity_pkg_dir: &Path,
    files: &[&str],
) -> Result<(), String> {
    let mut lines = vec![
        "--release".to_string(),
        "25".to_string(),
        "-proc:none".to_string(),
        "-encoding".to_string(),
        "UTF-8".to_string(),
        "-cp".to_string(),
        quote_argfile(classpath),
        "-sourcepath".to_string(),
        quote_argfile(&source_root.to_string_lossy()),
        "-d".to_string(),
        quote_argfile(output_dir),
    ];
    for name in files {
        let p = parity_pkg_dir.join(name);
        if !p.is_file() {
            return Err(format!("Missing exporter source file: {name}"));
        }
        lines.push(quote_argfile(&p.to_string_lossy()));
    }
    fs::write(dest, format!("{}\n", lines.join("\n")))
        .map_err(|e| format!("Write {}: {e}", dest.display()))
}

fn sample_proc_peak_kb(pid: u32) -> u64 {
    let path = format!("/proc/{pid}/status");
    let Ok(text) = fs::read_to_string(path) else {
        return 0;
    };
    let mut best = 0u64;
    for line in text.lines() {
        if let Some(rest) = line
            .strip_prefix("VmHWM:")
            .or_else(|| line.strip_prefix("VmRSS:"))
        {
            let num: String = rest
                .trim()
                .chars()
                .take_while(|c| c.is_ascii_digit())
                .collect();
            if let Ok(kb) = num.parse::<u64>() {
                best = best.max(kb);
            }
        }
    }
    best
}

fn run_monitored_command(
    step_name: &str,
    exe: &Path,
    args: &[String],
    cwd: &Path,
) -> Result<StepStat, String> {
    let started = Instant::now();
    let mut cmd = Command::new(exe);
    cmd.args(args)
        .current_dir(cwd)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let mut child = cmd
        .spawn()
        .map_err(|e| format!("{step_name}: spawn {} failed: {e}", exe.display()))?;
    let pid = child.id();
    let mut peak_kb = sample_proc_peak_kb(pid);

    loop {
        peak_kb = peak_kb.max(sample_proc_peak_kb(pid));
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => thread::sleep(Duration::from_millis(15)),
            Err(e) => return Err(format!("{step_name}: wait failed: {e}")),
        }
    }

    let output = child
        .wait_with_output()
        .map_err(|e| format!("{step_name}: wait_with_output failed: {e}"))?;
    let elapsed_ms = started.elapsed().as_millis() as u64;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);
        let tail_err: Vec<&str> = stderr.lines().rev().take(25).collect::<Vec<_>>().into_iter().rev().collect();
        let tail_out: Vec<&str> = stdout.lines().rev().take(15).collect::<Vec<_>>().into_iter().rev().collect();
        return Err(redact_sensitive(&format!(
            "{step_name} failed (status={}, elapsed_ms={}, peak_rss_kb={}):\n--- stderr tail ---\n{}\n--- stdout tail ---\n{}",
            output.status,
            elapsed_ms,
            peak_kb,
            tail_err.join("\n"),
            tail_out.join("\n")
        )));
    }

    println!(
        "PREFLIGHT_STEP name={step_name} elapsed_ms={elapsed_ms} peak_rss_kb={peak_kb}"
    );
    Ok(StepStat {
        name: step_name.to_string(),
        elapsed_ms,
        peak_rss_kb: peak_kb,
    })
}

fn run_jvm_steps(
    plan: &JavaPlan,
    parity_sources: &Path,
    java_home: &Path,
    heap_mb: u32,
) -> Result<Vec<StepStat>, String> {
    let java_bin = java_home.join("bin/java");
    let javac_bin = java_home.join("bin/javac");
    if !java_bin.is_file() || !javac_bin.is_file() {
        return Err(format!(
            "JDK 25 binaries missing under java_home={}",
            java_home.display()
        ));
    }

    let work_dir = PathBuf::from(&plan.work_dir);
    let parity_pkg_dir = parity_sources.join("dev/horizonlab/parity");
    let server_argfile = work_dir.join("javac_server.args");
    let client_argfile = work_dir.join("javac_client.args");

    write_javac_argfile(
        &server_argfile,
        &plan.server_classpath,
        parity_sources,
        &plan.server_classes_dir,
        &parity_pkg_dir,
        SERVER_EXPORTER_FILES,
    )?;
    write_javac_argfile(
        &client_argfile,
        &plan.client_classpath,
        parity_sources,
        &plan.client_classes_dir,
        &parity_pkg_dir,
        CLIENT_EXPORTER_FILES,
    )?;

    let common_flags = vec![
        "-Djava.awt.headless=true".to_string(),
        "-Xms256M".to_string(),
        format!("-Xmx{heap_mb}M"),
        "-XX:TieredStopAtLevel=1".to_string(),
        "-XX:+UseSerialGC".to_string(),
    ];
    let server_batch_cp = format!("{}:{}", plan.server_classes_dir, plan.server_classpath);
    let client_batch_cp = format!("{}:{}", plan.client_classes_dir, plan.client_classpath);

    let mut stats = Vec::new();
    stats.push(run_monitored_command(
        "javac_server",
        &javac_bin,
        &[format!("@{}", server_argfile.display())],
        &work_dir,
    )?);
    stats.push(run_monitored_command(
        "javac_client",
        &javac_bin,
        &[format!("@{}", client_argfile.display())],
        &work_dir,
    )?);

    let mut datagen_args = common_flags.clone();
    datagen_args.extend([
        "-cp".to_string(),
        plan.server_classpath.clone(),
        "net.minecraft.data.Main".to_string(),
        "--reports".to_string(),
        "--output".to_string(),
        plan.datagen_out_dir.clone(),
    ]);
    stats.push(run_monitored_command(
        "datagen_reports",
        &java_bin,
        &datagen_args,
        &work_dir,
    )?);

    let mut srv_exp_args = common_flags.clone();
    srv_exp_args.extend([
        "-cp".to_string(),
        server_batch_cp.clone(),
        "dev.horizonlab.parity.PrepareBatchShim".to_string(),
        plan.server_tasks_jsonl.clone(),
    ]);
    stats.push(run_monitored_command(
        "server_exporters",
        &java_bin,
        &srv_exp_args,
        &work_dir,
    )?);

    let mut cmd_reg_args = common_flags.clone();
    cmd_reg_args.extend([
        "-cp".to_string(),
        server_batch_cp.clone(),
        "dev.horizonlab.parity.VanillaCommandParseOracle".to_string(),
        "registries".to_string(),
        plan.command_registries_dir.clone(),
    ]);
    stats.push(run_monitored_command(
        "command_registries",
        &java_bin,
        &cmd_reg_args,
        &work_dir,
    )?);

    let mut tabs_args = common_flags.clone();
    tabs_args.extend([
        "-cp".to_string(),
        server_batch_cp,
        "dev.horizonlab.parity.VanillaCreativeTabsExporter".to_string(),
        plan.creative_inventory_json.clone(),
    ]);
    stats.push(run_monitored_command(
        "creative_tabs",
        &java_bin,
        &tabs_args,
        &work_dir,
    )?);

    let mut cli_exp_args = common_flags;
    cli_exp_args.extend([
        "-cp".to_string(),
        client_batch_cp,
        "dev.horizonlab.parity.PrepareBatchShim".to_string(),
        plan.client_tasks_jsonl.clone(),
    ]);
    stats.push(run_monitored_command(
        "client_exporters",
        &java_bin,
        &cli_exp_args,
        &work_dir,
    )?);

    Ok(stats)
}

fn collect_inventory(root: &Path) -> Result<BTreeMap<String, FileDigestEntry>, String> {
    fn walk(
        base: &Path,
        cur: &Path,
        out: &mut BTreeMap<String, FileDigestEntry>,
    ) -> Result<(), String> {
        let mut entries: Vec<_> = fs::read_dir(cur)
            .map_err(|e| format!("{}: {e}", cur.display()))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| format!("{}: {e}", cur.display()))?;
        entries.sort_by_key(|e| e.file_name());
        for entry in entries {
            let p = entry.path();
            if p.is_dir() {
                walk(base, &p, out)?;
            } else if p.is_file() {
                let rel = p
                    .strip_prefix(base)
                    .map_err(|e| e.to_string())?
                    .to_string_lossy()
                    .replace('\\', "/");
                let bytes = entry.metadata().map_err(|e| e.to_string())?.len();
                let sha256 = sha256_file(&p)?;
                out.insert(rel, FileDigestEntry { bytes, sha256 });
            }
        }
        Ok(())
    }
    let mut out = BTreeMap::new();
    walk(root, root, &mut out)?;
    Ok(out)
}

fn run() -> Result<(), String> {
    let args = parse_args()?;
    fs::create_dir_all(&args.files_dir).map_err(|e| e.to_string())?;
    let total_started = Instant::now();
    let cancel = CancelFlag::new();

    let cfg = PrepareConfig {
        files_dir: args.files_dir.clone(),
        game_home: None,
        lock_path: args.lock_path.clone(),
        controls_manifest_path: args.controls_manifest.clone(),
        parity_sources_dir: Some(args.parity_sources.clone()),
        catalog_url: None,
        objects_base_url: None,
        controls_base_url: None,
        allow_loopback: false,
        download_workers: 6,
    };

    let last_phase = std::sync::Mutex::new(String::new());
    let phase1 = run_phase1(&cfg, &cancel, &|phase, done, total| {
        if let Ok(mut prev) = last_phase.lock() {
            if prev.as_str() != phase {
                println!("PREFLIGHT_PHASE1 stage={phase} progress={done}/{total}");
                *prev = phase.to_string();
            }
        }
    })?;
    println!(
        "PREFLIGHT_STEP name=rust_phase1 elapsed_ms={} peak_rss_kb={} downloaded={} reused={} bytes_downloaded={}",
        phase1.elapsed_ms,
        phase1.peak_rss_kb,
        phase1.download_stats.files_downloaded,
        phase1.download_stats.files_reused,
        phase1.download_stats.bytes_downloaded
    );

    let jvm_steps = run_jvm_steps(
        &phase1.java_plan,
        &args.parity_sources,
        &args.java_home,
        args.heap_mb,
    )?;

    let last_p2 = std::sync::Mutex::new(String::new());
    let phase2 = run_phase2_finalize(&cfg, &cancel, &|phase, done, total| {
        if let Ok(mut prev) = last_p2.lock() {
            if prev.as_str() != phase {
                println!("PREFLIGHT_PHASE2 stage={phase} progress={done}/{total}");
                *prev = phase.to_string();
            }
        }
    })?;
    println!(
        "PREFLIGHT_STEP name=rust_phase2 elapsed_ms={} peak_rss_kb={} zip_entries={} survival_rules={} items={}",
        phase2.elapsed_ms,
        phase2.peak_rss_kb,
        phase2.engine_data_entries,
        phase2.survival_rules_count,
        phase2.items_count
    );

    let mut steps = vec![StepStat {
        name: "rust_phase1".to_string(),
        elapsed_ms: phase1.elapsed_ms,
        peak_rss_kb: phase1.peak_rss_kb,
    }];
    steps.extend(jvm_steps);
    steps.push(StepStat {
        name: "rust_phase2".to_string(),
        elapsed_ms: phase2.elapsed_ms,
        peak_rss_kb: phase2.peak_rss_kb,
    });

    let prepared = is_prepared(&args.files_dir, "26.3");
    if !prepared {
        return Err("is_prepared(&files_dir, \"26.3\") returned false after Phase 2".to_string());
    }

    let target_dir = PathBuf::from(&phase2.target_resources_dir);
    let inventory = collect_inventory(&target_dir)?;
    let work_dir = PathBuf::from(&phase1.work_dir);
    let max_step_peak = steps.iter().map(|s| s.peak_rss_kb).max().unwrap_or(0);
    let rust_peak = peak_rss_kb();
    let total_ms = total_started.elapsed().as_millis() as u64;

    let summary = PreflightSummary {
        status: "ok".to_string(),
        arch: env::consts::ARCH.to_string(),
        total_ms,
        rust_process_peak_rss_kb: rust_peak,
        max_step_peak_rss_kb: max_step_peak.max(rust_peak),
        phase1_ms: phase1.elapsed_ms,
        phase2_ms: phase2.elapsed_ms,
        steps,
        work_dir: work_dir.to_string_lossy().into_owned(),
        manifest_json: work_dir.join("manifest.json").to_string_lossy().into_owned(),
        client_jar: phase1.java_plan.client_jar,
        server_jar: phase1.java_plan.server_jar,
        libraries_dir: work_dir.join("libraries/client").to_string_lossy().into_owned(),
        assets_dir: work_dir.join("assets").to_string_lossy().into_owned(),
        target_resources_dir: phase2.target_resources_dir,
        engine_data_zip: phase2.engine_data_zip,
        engine_data_entries: phase2.engine_data_entries,
        survival_rules_count: phase2.survival_rules_count,
        items_count: phase2.items_count,
        files_downloaded: phase1.download_stats.files_downloaded,
        files_reused: phase1.download_stats.files_reused,
        bytes_downloaded: phase1.download_stats.bytes_downloaded,
        client_resource_files: phase1.client_resource_files,
        indexed_assets_copied: phase1.indexed_assets_copied,
        worldgen_structures_extracted: phase1.worldgen_structures_extracted,
        worldgen_maps_written: phase1.worldgen_maps_written,
        is_prepared: prepared,
        warnings: phase1
            .warnings
            .into_iter()
            .map(|w| redact_sensitive(&w))
            .collect(),
        inventory,
    };

    if let Some(parent) = args.summary_json.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let json = serde_json::to_vec_pretty(&summary).map_err(|e| e.to_string())?;
    fs::write(&args.summary_json, json).map_err(|e| e.to_string())?;

    println!(
        "PREFLIGHT_COMPLETE total_ms={total_ms} rust_peak_rss_kb={rust_peak} max_step_peak_rss_kb={} output_files={}",
        summary.max_step_peak_rss_kb,
        summary.inventory.len()
    );
    Ok(())
}

fn main() {
    if let Err(err) = run() {
        eprintln!("PREFLIGHT_ERROR: {}", redact_sensitive(&err));
        std::process::exit(1);
    }
}
