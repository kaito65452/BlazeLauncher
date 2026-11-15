// Blaze Launcher — on-device 26.3 engine-data preparer
// Copyright (C) 2026 Blaze Launcher contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Pin-verified downloads via `ureq` (Rustls) with ZL2 `<gameHome>` reuse and atomic `.partial` writes.

use crate::files::{
    atomic_copy_file, atomic_write_bytes, partial_path, redact_sensitive, safe_relative, sha1_file,
    sha256_file, CancelFlag,
};
use crate::lock::{
    sanitize_url_for_error, validate_download_url_with_context, validate_sha1_hex,
    validate_sha256_hex, validate_version_manifest_with_source, BedrockControlsManifest, LockFile,
    ManifestValidationInfo, MinecraftPin,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::Duration;

pub const DEFAULT_CATALOG_URL: &str =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct DownloadStats {
    pub files_reused: u64,
    pub bytes_reused: u64,
    pub files_downloaded: u64,
    pub bytes_downloaded: u64,
}

#[derive(Clone)]
pub struct HttpFetcher {
    agent: ureq::Agent,
    allow_loopback: bool,
    cancel: CancelFlag,
}

const MAX_DOWNLOAD_REDIRECTS: usize = 5;

fn is_non_retryable_download_error(err: &str) -> bool {
    err.contains("Untrusted download host:")
        || err.contains("Download URL must use HTTPS on port 443")
        || err.contains("Credentials in download URL are forbidden")
        || err.contains("Malformed download URL")
        || err.contains("Too many redirects")
        || err.contains("Redirect ")
        || err.contains("Checksum mismatch")
        || err.contains("Size mismatch")
}

impl HttpFetcher {
    pub fn new(allow_loopback: bool, cancel: CancelFlag) -> Self {
        let agent = ureq::AgentBuilder::new()
            .redirects(0)
            .timeout_connect(Duration::from_secs(30))
            .timeout_read(Duration::from_secs(120))
            .build();
        Self {
            agent,
            allow_loopback,
            cancel,
        }
    }

    pub fn call_validated_with_redirects(
        &self,
        initial_url: &str,
        source_field: &str,
    ) -> Result<ureq::Response, String> {
        let mut current_url = initial_url.to_string();
        let mut current_field = source_field.to_string();
        validate_download_url_with_context(&current_url, &current_field, self.allow_loopback)?;

        for hop in 0..=MAX_DOWNLOAD_REDIRECTS {
            self.cancel.check()?;
            let safe_cur = sanitize_url_for_error(&current_url);
            let resp = match self.agent.get(&current_url).call() {
                Ok(r) => r,
                Err(ureq::Error::Status(code, _)) => {
                    return Err(redact_sensitive(&format!(
                        "GET {current_field} ({safe_cur}): HTTP {code}"
                    )));
                }
                Err(ureq::Error::Transport(t)) => {
                    return Err(redact_sensitive(&format!(
                        "GET {current_field} ({safe_cur}): {t}"
                    )));
                }
            };
            let status = resp.status();
            if (300..=399).contains(&status) {
                if !matches!(status, 301 | 302 | 303 | 307 | 308) {
                    return Err(redact_sensitive(&format!(
                        "Redirect HTTP {status} unsupported (from {current_field}: {safe_cur}). Next step: retry later or check whether a network proxy altered the Mojang CDN response."
                    )));
                }
                if hop == MAX_DOWNLOAD_REDIRECTS {
                    return Err(redact_sensitive(&format!(
                        "Too many redirects (>{MAX_DOWNLOAD_REDIRECTS}) from {source_field}: {}. Next step: retry on a clean network connection without redirect loops.",
                        sanitize_url_for_error(initial_url)
                    )));
                }
                let location = resp.header("location").ok_or_else(|| {
                    redact_sensitive(&format!(
                        "Redirect HTTP {status} missing Location header (from {current_field}: {safe_cur}). Next step: retry later or check for a broken CDN/proxy response."
                    ))
                })?;
                let base = url::Url::parse(&current_url).map_err(|e| {
                    redact_sensitive(&format!(
                        "Malformed download URL while resolving redirect from {current_field}: {e}"
                    ))
                })?;
                let next_url = base
                    .join(location)
                    .map_err(|e| {
                        redact_sensitive(&format!(
                            "Malformed download URL in redirect Location from {current_field}: {e}"
                        ))
                    })?
                    .to_string();
                let next_field = format!("{source_field} redirect hop {}", hop + 1);
                validate_download_url_with_context(&next_url, &next_field, self.allow_loopback)?;
                current_url = next_url;
                current_field = next_field;
                continue;
            }
            return Ok(resp);
        }
        unreachable!()
    }

    pub fn get_bytes(&self, url: &str, max_bytes: u64) -> Result<Vec<u8>, String> {
        self.get_bytes_with_context(url, max_bytes, "download")
    }

    pub fn get_bytes_with_context(
        &self,
        url: &str,
        max_bytes: u64,
        source_field: &str,
    ) -> Result<Vec<u8>, String> {
        validate_download_url_with_context(url, source_field, self.allow_loopback)?;
        self.cancel.check()?;
        let safe_url = sanitize_url_for_error(url);
        for attempt in 0..3 {
            let res = (|| -> Result<Vec<u8>, String> {
                self.cancel.check()?;
                let resp = self.call_validated_with_redirects(url, source_field)?;
                let mut reader = resp.into_reader().take(max_bytes + 1);
                let mut out = Vec::new();
                let mut buf = [0u8; 16 * 1024];
                loop {
                    self.cancel.check()?;
                    let n = reader
                        .read(&mut buf)
                        .map_err(|e| redact_sensitive(&format!("Read {source_field} ({safe_url}): {e}")))?;
                    if n == 0 {
                        break;
                    }
                    if out.len() as u64 + n as u64 > max_bytes {
                        return Err(redact_sensitive(&format!(
                            "Response from {source_field} ({safe_url}) exceeds {max_bytes} byte limit"
                        )));
                    }
                    out.extend_from_slice(&buf[..n]);
                }
                Ok(out)
            })();
            match res {
                Ok(bytes) => return Ok(bytes),
                Err(err) => {
                    if self.cancel.is_cancelled()
                        || is_non_retryable_download_error(&err)
                        || attempt == 2
                    {
                        return Err(err);
                    }
                    std::thread::sleep(Duration::from_millis(400 * (attempt + 1)));
                }
            }
        }
        unreachable!()
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum HashAlg {
    Sha1,
    Sha256,
}

#[derive(Clone)]
pub struct FileFetchSpec {
    pub url: String,
    pub source_field: String,
    pub expected_hash: String,
    pub alg: HashAlg,
    pub expected_size: Option<u64>,
    pub dest: PathBuf,
    pub reuse_candidates: Vec<PathBuf>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum FetchOutcome {
    AlreadyPresent(u64),
    ReusedFromStore(u64),
    Downloaded(u64),
}

fn file_matches(path: &Path, spec: &FileFetchSpec) -> Result<Option<u64>, String> {
    let Ok(meta) = fs::metadata(path) else {
        return Ok(None);
    };
    if !meta.is_file() {
        return Ok(None);
    }
    let len = meta.len();
    if let Some(exp_size) = spec.expected_size {
        if len != exp_size {
            return Ok(None);
        }
    }
    let actual = match spec.alg {
        HashAlg::Sha1 => sha1_file(path)?,
        HashAlg::Sha256 => sha256_file(path)?,
    };
    if actual.eq_ignore_ascii_case(&spec.expected_hash) {
        Ok(Some(len))
    } else {
        Ok(None)
    }
}

fn describe_reuse_mismatch(path: &Path, spec: &FileFetchSpec) -> Option<String> {
    let Ok(meta) = fs::metadata(path) else {
        return None;
    };
    if !meta.is_file() {
        return None;
    }
    let len = meta.len();
    if let Some(exp_size) = spec.expected_size {
        if len != exp_size {
            return Some(format!(
                "reuse from <gameHome> ({}): size={len}, expected={exp_size}",
                path.display()
            ));
        }
    }
    let actual = match spec.alg {
        HashAlg::Sha1 => sha1_file(path).ok()?,
        HashAlg::Sha256 => sha256_file(path).ok()?,
    };
    Some(format!(
        "reuse from <gameHome> ({}): computed={}, expected={}",
        path.display(),
        actual,
        spec.expected_hash
    ))
}

/// Ensures `spec.dest` exists with verified size/hash, preferring local reuse candidates before HTTP.
pub fn ensure_verified_file(
    fetcher: &HttpFetcher,
    spec: &FileFetchSpec,
) -> Result<FetchOutcome, String> {
    fetcher.cancel.check()?;
    match spec.alg {
        HashAlg::Sha1 => validate_sha1_hex(&spec.expected_hash, "download sha1")?,
        HashAlg::Sha256 => validate_sha256_hex(&spec.expected_hash, "download sha256")?,
    }
    if let Some(size) = file_matches(&spec.dest, spec)? {
        return Ok(FetchOutcome::AlreadyPresent(size));
    }
    let mut reuse_notes = Vec::new();
    for cand in &spec.reuse_candidates {
        if cand == &spec.dest {
            continue;
        }
        if let Some(size) = file_matches(cand, spec)? {
            atomic_copy_file(cand, &spec.dest, &fetcher.cancel)?;
            return Ok(FetchOutcome::ReusedFromStore(size));
        }
        if let Some(note) = describe_reuse_mismatch(cand, spec) {
            reuse_notes.push(note);
        }
    }

    validate_download_url_with_context(&spec.url, &spec.source_field, fetcher.allow_loopback)?;
    let safe_url = sanitize_url_for_error(&spec.url);
    let partial = partial_path(&spec.dest);
    if let Some(parent) = spec.dest.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    }

    for attempt in 0..3 {
        let dl_res = (|| -> Result<u64, String> {
            fetcher.cancel.check()?;
            let limit = spec.expected_size.unwrap_or(1024 * 1024 * 1024);
            let resp = fetcher.call_validated_with_redirects(&spec.url, &spec.source_field)?;
            let mut reader = resp.into_reader().take(limit + 1);
            let mut writer =
                fs::File::create(&partial).map_err(|e| format!("{}: {e}", partial.display()))?;
            let mut buf = vec![0u8; 64 * 1024];
            let mut written = 0u64;
            loop {
                fetcher.cancel.check()?;
                let n = reader
                    .read(&mut buf)
                    .map_err(|e| redact_sensitive(&format!("Read {} ({safe_url}): {e}", spec.source_field)))?;
                if n == 0 {
                    break;
                }
                written += n as u64;
                if written > limit {
                    return Err(redact_sensitive(&format!(
                        "Download exceeds expected size limit from {} ({safe_url})",
                        spec.source_field
                    )));
                }
                writer
                    .write_all(&buf[..n])
                    .map_err(|e| format!("{}: {e}", partial.display()))?;
            }
            writer
                .sync_all()
                .map_err(|e| format!("{}: {e}", partial.display()))?;
            drop(writer);
            let reuse_ctx = if reuse_notes.is_empty() {
                String::new()
            } else {
                format!("; {}", reuse_notes.join("; "))
            };
            if let Some(exp_size) = spec.expected_size {
                if written != exp_size {
                    return Err(redact_sensitive(&format!(
                        "Size mismatch for direct download from {} ({safe_url}) -> {}: expected {exp_size} bytes, got {written} bytes{reuse_ctx}. Next step: check your network connection or delete the partial download and retry.",
                        spec.source_field,
                        spec.dest.display()
                    )));
                }
            }
            let actual = match spec.alg {
                HashAlg::Sha1 => sha1_file(&partial)?,
                HashAlg::Sha256 => sha256_file(&partial)?,
            };
            if !actual.eq_ignore_ascii_case(&spec.expected_hash) {
                return Err(redact_sensitive(&format!(
                    "Checksum mismatch for direct download from {} ({safe_url}) -> {}: computed={actual}, expected={}{reuse_ctx}. Next step: delete corrupted cached files and retry on a stable connection.",
                    spec.source_field,
                    spec.dest.display(),
                    spec.expected_hash
                )));
            }
            fs::rename(&partial, &spec.dest)
                .map_err(|e| format!("{}: {e}", spec.dest.display()))?;
            Ok(written)
        })();

        match dl_res {
            Ok(bytes) => return Ok(FetchOutcome::Downloaded(bytes)),
            Err(err) => {
                let _ = fs::remove_file(&partial);
                // Do not retry checksum/size mismatches, URL/host/redirect policy errors, or user cancellations.
                if fetcher.cancel.is_cancelled()
                    || is_non_retryable_download_error(&err)
                    || attempt == 2
                {
                    return Err(err);
                }
                std::thread::sleep(Duration::from_millis(400 * (attempt + 1)));
            }
        }
    }
    unreachable!()
}

pub struct ResolvedManifest {
    pub manifest: Value,
    pub warnings: Vec<String>,
}

fn build_local_refresh_warnings(lock: &LockFile, info: &ManifestValidationInfo) -> Vec<String> {
    let mut parts = Vec::new();
    if lock.is_local_refreshed_manifest(&info.manifest_sha1) {
        parts.push(format!("manifest sha1={}", info.manifest_sha1));
    }
    if lock.is_local_refreshed_asset_index(&info.asset_index_sha1) {
        parts.push(format!("assetIndex sha1={}", info.asset_index_sha1));
    }
    if parts.is_empty() {
        Vec::new()
    } else {
        vec![redact_sensitive(&format!(
            "Manifest/asset-index pin drift on {} ({}; client_sha1 and server_sha1 match upstream pins): local pin refresh, not verified by upstream",
            lock.minecraft.version,
            parts.join(", ")
        ))]
    }
}

/// Resolves and pin-verifies `<version>.json`, reusing ZL2's version JSON if intact.
pub fn resolve_version_manifest(
    fetcher: &HttpFetcher,
    lock: &LockFile,
    work_dir: &Path,
    game_home: Option<&Path>,
    catalog_url: &str,
    stats: &mut DownloadStats,
) -> Result<ResolvedManifest, String> {
    let dest = work_dir.join("manifest.json");
    let mut candidates: Vec<(PathBuf, String)> = vec![(
        dest.clone(),
        format!("work cache ({})", dest.display()),
    )];
    if let Some(gh) = game_home {
        let gh_manifest = gh
            .join("versions")
            .join(&lock.minecraft.version)
            .join(format!("{}.json", lock.minecraft.version));
        let label = format!("reuse from <gameHome> ({})", gh_manifest.display());
        candidates.push((gh_manifest, label));
    }
    let mut local_rejections = Vec::new();
    for (cand, source_label) in &candidates {
        if let Ok(bytes) = fs::read(cand) {
            match validate_version_manifest_with_source(
                &bytes,
                None,
                &lock.minecraft,
                true,
                source_label,
            ) {
                Ok((val, info)) => {
                    if cand != &dest {
                        atomic_write_bytes(&dest, &bytes)?;
                    }
                    stats.files_reused += 1;
                    stats.bytes_reused += bytes.len() as u64;
                    let warnings = build_local_refresh_warnings(lock, &info);
                    return Ok(ResolvedManifest {
                        manifest: val,
                        warnings,
                    });
                }
                Err(err) => {
                    local_rejections.push(err);
                }
            }
        }
    }

    let catalog_bytes = fetcher
        .get_bytes_with_context(catalog_url, 32 * 1024 * 1024, "version_catalog")
        .map_err(|e| {
        if local_rejections.is_empty() {
            e
        } else {
            redact_sensitive(&format!(
                "{}; prior local checks failed: {}",
                e,
                local_rejections.join(" | ")
            ))
        }
    })?;
    let catalog: Value =
        serde_json::from_slice(&catalog_bytes).map_err(|e| format!("Invalid catalog JSON: {e}"))?;
    let entry = catalog
        .get("versions")
        .and_then(Value::as_array)
        .and_then(|arr| {
            arr.iter()
                .find(|v| v.get("id").and_then(Value::as_str) == Some(lock.minecraft.version.as_str()))
        })
        .ok_or_else(|| {
            format!(
                "Pinned version {} not found in catalog",
                lock.minecraft.version
            )
        })?;
    let meta_url = entry
        .get("url")
        .and_then(Value::as_str)
        .ok_or("Catalog entry missing url")?;
    let meta_sha1 = entry
        .get("sha1")
        .and_then(Value::as_str)
        .ok_or("Catalog entry missing sha1")?;
    if !lock.manifest_accepted(meta_sha1) {
        let local_ctx = if local_rejections.is_empty() {
            String::new()
        } else {
            format!(" Local check notes: [{}].", local_rejections.join(" | "))
        };
        let safe_meta_url = sanitize_url_for_error(meta_url);
        return Err(redact_sensitive(&format!(
            "Catalog version {} manifest sha1 ({meta_sha1}) from direct download catalog entry ({safe_meta_url}) is rejected by research-lock.json (accepted: [{}]).{local_ctx} Next step: Mojang may have republished {}; confirm client_sha1 and server_sha1 match upstream pins before adding an explicit lock refresh.",
            lock.minecraft.version,
            lock.accepted_manifest_list_display(),
            lock.minecraft.version
        )));
    }
    let manifest_field = format!("version_catalog.versions[{}].url", lock.minecraft.version);
    let manifest_bytes = fetcher.get_bytes_with_context(meta_url, 16 * 1024 * 1024, &manifest_field)?;
    let source_label = format!("direct download from {}", sanitize_url_for_error(meta_url));
    let (val, info) = validate_version_manifest_with_source(
        &manifest_bytes,
        Some(meta_sha1),
        &lock.minecraft,
        true,
        &source_label,
    )?;
    atomic_write_bytes(&dest, &manifest_bytes)?;
    stats.files_downloaded += 1;
    stats.bytes_downloaded += manifest_bytes.len() as u64;
    let warnings = build_local_refresh_warnings(lock, &info);
    Ok(ResolvedManifest {
        manifest: val,
        warnings,
    })
}

fn rule_system() -> &'static str {
    if cfg!(target_os = "macos") {
        "osx"
    } else if cfg!(windows) {
        "windows"
    } else {
        "linux"
    }
}

fn rule_architectures() -> Vec<&'static str> {
    match std::env::consts::ARCH {
        "x86_64" => vec!["x86_64", "amd64", "x64"],
        "aarch64" => vec!["arm64", "aarch64"],
        "x86" => vec!["i686"],
        other => vec![other],
    }
}

fn library_allowed(library: &Value) -> Result<bool, String> {
    let system = rule_system();
    let arches = rule_architectures();
    let rules = library
        .get("rules")
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or(&[]);
    let mut allowed = rules.is_empty();
    for rule in rules {
        let empty = Value::Object(serde_json::Map::new());
        let os = rule.get("os").unwrap_or(&empty);
        if os.get("name").and_then(Value::as_str).unwrap_or(system) != system {
            continue;
        }
        if let Some(arch) = os.get("arch").and_then(Value::as_str) {
            let re = regex::Regex::new(&format!("^(?:{arch})$")).map_err(|e| e.to_string())?;
            if !arches.iter().any(|a| re.is_match(a)) {
                continue;
            }
        }
        if let Some(features) = rule.get("features") {
            let nonempty = match features {
                Value::Object(o) => !o.is_empty(),
                Value::Array(a) => !a.is_empty(),
                Value::Null | Value::Bool(false) => false,
                Value::String(s) => !s.is_empty(),
                _ => true,
            };
            if nonempty {
                continue;
            }
        }
        allowed = rule.get("action").and_then(Value::as_str) == Some("allow");
    }
    Ok(allowed)
}

fn classifier_ends_with(classifier: &str, names: &[&str]) -> bool {
    names.iter().any(|name| {
        classifier == *name
            || classifier
                .strip_suffix(name)
                .is_some_and(|rest| rest.ends_with('-'))
    })
}

fn native_library_matches(library: &Value, names: &[&str]) -> bool {
    let arm = rule_architectures().contains(&"arm64");
    let name = library.get("name").and_then(Value::as_str).unwrap_or("");
    let classifier = name.split(':').nth(3).unwrap_or("");
    // Headless Java 25 exporters/datagen never load desktop LWJGL native binaries (.so/.dll/.dylib).
    if name.starts_with("org.lwjgl:") && classifier.starts_with("natives-") {
        return false;
    }
    if classifier_ends_with(classifier, &["arm64", "aarch64", "aarch_64"]) {
        return arm;
    }
    if classifier_ends_with(classifier, &["x86_64", "x64"]) {
        return !arm;
    }
    if arm && classifier.starts_with("natives-") {
        return !["arm64", "aarch64"]
            .iter()
            .any(|suffix| names.contains(&format!("{name}-{suffix}").as_str()));
    }
    true
}

fn runs_without_native_transport(name: &str) -> bool {
    let mut parts = name.split(':');
    parts.next() == Some("io.netty")
        && parts
            .next()
            .is_some_and(|a| a.starts_with("netty-transport-native-"))
        && parts.nth(1).is_some()
}

pub fn prism_library_candidate(libraries_root: &Path, name: &str) -> Option<PathBuf> {
    let parts: Vec<&str> = name.split(':').collect();
    if parts.len() == 4 {
        let artifact = format!("{}-{}", parts[1], parts[3]);
        let rel = format!(
            "{}/{artifact}/{}/{artifact}-{}.jar",
            parts[0].replace('.', "/"),
            parts[2],
            parts[2]
        );
        if let Ok(p) = safe_relative(&rel) {
            return Some(libraries_root.join(p));
        }
    }
    None
}

pub struct ClientArtifactsPlan {
    pub specs: Vec<FileFetchSpec>,
    pub optional_netty_dests: HashSet<PathBuf>,
    pub client_library_paths: Vec<PathBuf>,
    pub asset_index_id: String,
    pub asset_index_spec: FileFetchSpec,
    pub client_jar_path: PathBuf,
    pub server_jar_path: PathBuf,
}

pub fn build_artifact_plan(
    manifest: &Value,
    pin: &MinecraftPin,
    work_dir: &Path,
    game_home: Option<&Path>,
) -> Result<ClientArtifactsPlan, String> {
    let downloads = manifest
        .get("downloads")
        .ok_or("Version JSON missing downloads")?;
    let side_spec = |side: &str, expected_pin: &str, dest: PathBuf, gh_rel: Option<PathBuf>| -> Result<FileFetchSpec, String> {
        let obj = downloads
            .get(side)
            .ok_or_else(|| format!("Version JSON missing downloads.{side}"))?;
        let url = obj
            .get("url")
            .and_then(Value::as_str)
            .ok_or_else(|| format!("downloads.{side} missing url"))?
            .to_string();
        let sha1 = obj
            .get("sha1")
            .and_then(Value::as_str)
            .ok_or_else(|| format!("downloads.{side} missing sha1"))?
            .to_string();
        if !sha1.eq_ignore_ascii_case(expected_pin) {
            return Err(format!("downloads.{side}.sha1 mismatch against pin"));
        }
        let size = obj.get("size").and_then(Value::as_u64);
        let reuse = gh_rel.into_iter().collect();
        Ok(FileFetchSpec {
            url,
            source_field: format!("downloads.{side}.url"),
            expected_hash: sha1,
            alg: HashAlg::Sha1,
            expected_size: size,
            dest,
            reuse_candidates: reuse,
        })
    };

    let client_jar_path = work_dir.join("jars/client.jar");
    let server_jar_path = work_dir.join("jars/server.jar");
    let client_gh = game_home.map(|gh| {
        gh.join("versions")
            .join(&pin.version)
            .join(format!("{}.jar", pin.version))
    });
    let client_spec = side_spec("client", &pin.client_sha1, client_jar_path.clone(), client_gh)?;
    let server_spec = side_spec("server", &pin.server_sha1, server_jar_path.clone(), None)?;

    let asset_idx = manifest
        .get("assetIndex")
        .ok_or("Version JSON missing assetIndex")?;
    let asset_index_id = asset_idx
        .get("id")
        .and_then(Value::as_str)
        .ok_or("assetIndex missing id")?
        .to_string();
    let idx_rel = safe_relative(&asset_index_id)?;
    if idx_rel.components().count() != 1 {
        return Err(format!("Unsafe assetIndex id: {asset_index_id}"));
    }
    let idx_sha1 = asset_idx
        .get("sha1")
        .and_then(Value::as_str)
        .ok_or("assetIndex missing sha1")?
        .to_string();
    let lock_wrapper = LockFile {
        minecraft: pin.clone(),
    };
    if !lock_wrapper.asset_index_accepted(&idx_sha1) {
        return Err(redact_sensitive(&format!(
            "Asset index sha1 ({idx_sha1}) from version manifest is not accepted by research-lock.json (accepted: [{}]). Next step: refresh accepted_asset_index_sha1 only after verifying client_sha1 and server_sha1 match upstream pins.",
            lock_wrapper.accepted_asset_index_list_display()
        )));
    }
    let idx_url = asset_idx
        .get("url")
        .and_then(Value::as_str)
        .ok_or("assetIndex missing url")?
        .to_string();
    let idx_size = asset_idx.get("size").and_then(Value::as_u64);
    let idx_dest = work_dir.join("assets/indexes").join(format!("{asset_index_id}.json"));
    let mut idx_reuse = Vec::new();
    if let Some(gh) = game_home {
        idx_reuse.push(gh.join("assets/indexes").join(format!("{asset_index_id}.json")));
    }
    let asset_index_spec = FileFetchSpec {
        url: idx_url,
        source_field: "assetIndex.url".to_string(),
        expected_hash: idx_sha1,
        alg: HashAlg::Sha1,
        expected_size: idx_size,
        dest: idx_dest,
        reuse_candidates: idx_reuse,
    };

    let libraries = manifest
        .get("libraries")
        .and_then(Value::as_array)
        .ok_or("Version JSON has no libraries")?;
    let names: Vec<&str> = libraries
        .iter()
        .filter_map(|l| l.get("name").and_then(Value::as_str))
        .collect();

    let mut specs = vec![client_spec, server_spec];
    let mut client_library_paths = Vec::new();
    let mut optional_netty_dests = HashSet::new();

    for lib in libraries {
        let Some(artifact) = lib.get("downloads").and_then(|d| d.get("artifact")) else {
            continue;
        };
        if !library_allowed(lib)? || !native_library_matches(lib, &names) {
            continue;
        }
        let name = lib.get("name").and_then(Value::as_str).unwrap_or("");
        let path_str = artifact
            .get("path")
            .and_then(Value::as_str)
            .ok_or("Library artifact missing path")?;
        let url = artifact
            .get("url")
            .and_then(Value::as_str)
            .ok_or("Library artifact missing url")?
            .to_string();
        let sha1 = artifact
            .get("sha1")
            .and_then(Value::as_str)
            .ok_or("Library artifact missing sha1")?
            .to_string();
        let size = artifact.get("size").and_then(Value::as_u64);
        let rel = safe_relative(path_str)?;
        let dest = work_dir.join("libraries/client").join(&rel);
        let mut reuse = Vec::new();
        if let Some(gh) = game_home {
            let gh_libs = gh.join("libraries");
            reuse.push(gh_libs.join(&rel));
            if let Some(prism) = prism_library_candidate(&gh_libs, name) {
                reuse.push(prism);
            }
        }
        if runs_without_native_transport(name) {
            optional_netty_dests.insert(dest.clone());
        }
        client_library_paths.push(dest.clone());
        specs.push(FileFetchSpec {
            url,
            source_field: format!("libraries[{name}].downloads.artifact.url"),
            expected_hash: sha1,
            alg: HashAlg::Sha1,
            expected_size: size,
            dest,
            reuse_candidates: reuse,
        });
    }

    Ok(ClientArtifactsPlan {
        specs,
        optional_netty_dests,
        client_library_paths,
        asset_index_id,
        asset_index_spec,
        client_jar_path,
        server_jar_path,
    })
}

/// Downloads/reuses a batch of `FileFetchSpec` items in parallel on up to `workers` threads.
pub fn fetch_batch_parallel(
    fetcher: &HttpFetcher,
    specs: &[FileFetchSpec],
    workers: usize,
    optional_failures: &HashSet<PathBuf>,
    phase_label: &str,
    on_progress: &(dyn Fn(&str, u64, u64) + Sync),
) -> Result<DownloadStats, String> {
    let total_bytes: u64 = specs.iter().filter_map(|s| s.expected_size).sum();
    let total = if total_bytes > 0 {
        total_bytes
    } else {
        specs.len() as u64
    };
    on_progress(phase_label, 0, total);

    let next = AtomicUsize::new(0);
    let done_bytes = AtomicU64::new(0);
    let done_files = AtomicU64::new(0);
    let stats = Mutex::new(DownloadStats::default());
    let first_err: Mutex<Option<String>> = Mutex::new(None);
    let threads = workers.clamp(1, 8).min(specs.len().max(1));

    std::thread::scope(|scope| {
        for _ in 0..threads {
            scope.spawn(|| loop {
                if fetcher.cancel.is_cancelled() || first_err.lock().unwrap().is_some() {
                    break;
                }
                let idx = next.fetch_add(1, Ordering::Relaxed);
                let Some(spec) = specs.get(idx) else {
                    break;
                };
                match ensure_verified_file(fetcher, spec) {
                    Ok(outcome) => {
                        let mut s = stats.lock().unwrap();
                        let inc = match outcome {
                            FetchOutcome::AlreadyPresent(b) | FetchOutcome::ReusedFromStore(b) => {
                                s.files_reused += 1;
                                s.bytes_reused += b;
                                b
                            }
                            FetchOutcome::Downloaded(b) => {
                                s.files_downloaded += 1;
                                s.bytes_downloaded += b;
                                b
                            }
                        };
                        drop(s);
                        let f = done_files.fetch_add(1, Ordering::Relaxed) + 1;
                        let b = done_bytes.fetch_add(inc, Ordering::Relaxed) + inc;
                        on_progress(phase_label, if total_bytes > 0 { b } else { f }, total);
                    }
                    Err(err) => {
                        if optional_failures.contains(&spec.dest) && !fetcher.cancel.is_cancelled()
                        {
                            continue;
                        }
                        let mut slot = first_err.lock().unwrap();
                        if slot.is_none() {
                            *slot = Some(err);
                        }
                        break;
                    }
                }
            });
        }
    });

    if let Some(err) = first_err.into_inner().unwrap() {
        return Err(err);
    }
    fetcher.cancel.check()?;
    Ok(stats.into_inner().unwrap())
}

/// Parses asset-index JSON (`objects` map) and returns `(asset_specs, name_to_object_path)` mapping.
pub fn plan_asset_objects(
    asset_index_path: &Path,
    objects_base_url: &str,
    work_dir: &Path,
    game_home: Option<&Path>,
) -> Result<(Vec<FileFetchSpec>, HashMap<String, PathBuf>), String> {
    let bytes =
        fs::read(asset_index_path).map_err(|e| format!("{}: {e}", asset_index_path.display()))?;
    let val: Value =
        serde_json::from_slice(&bytes).map_err(|e| format!("Invalid asset index JSON: {e}"))?;
    let objects = val
        .get("objects")
        .and_then(Value::as_object)
        .ok_or("Asset index has no objects map")?;

    let mut specs = Vec::new();
    let mut seen_hashes = HashSet::new();
    let mut name_map = HashMap::with_capacity(objects.len());
    let base = objects_base_url.trim_end_matches('/');

    for (name, obj) in objects {
        safe_relative(name)?;
        let hash = obj
            .get("hash")
            .and_then(Value::as_str)
            .ok_or_else(|| format!("Asset {name} missing hash"))?;
        validate_sha1_hex(hash, name)?;
        let size = obj
            .get("size")
            .and_then(Value::as_u64)
            .ok_or_else(|| format!("Asset {name} missing size"))?;

        let prefix = &hash[..2];
        let dest = work_dir.join("assets/objects").join(prefix).join(hash);
        name_map.insert(name.clone(), dest.clone());

        if seen_hashes.insert(hash.to_string()) {
            let mut reuse = Vec::new();
            if let Some(gh) = game_home {
                reuse.push(gh.join("assets/objects").join(prefix).join(hash));
            }
            specs.push(FileFetchSpec {
                url: format!("{base}/{prefix}/{hash}"),
                source_field: format!("assetIndex.objects[{name}]"),
                expected_hash: hash.to_string(),
                alg: HashAlg::Sha1,
                expected_size: Some(size),
                dest,
                reuse_candidates: reuse,
            });
        }
    }

    Ok((specs, name_map))
}

/// Plans `bedrock-controls.json` downloads to `<work>/controls/<path>`.
pub fn plan_bedrock_controls(
    controls: &BedrockControlsManifest,
    controls_base_url: Option<&str>,
    work_dir: &Path,
) -> Result<Vec<(FileFetchSpec, PathBuf)>, String> {
    let mut out = Vec::with_capacity(controls.files.len());
    let base = controls_base_url.map(|s| s.trim_end_matches('/'));
    for entry in &controls.files {
        let rel = safe_relative(&entry.path)?;
        validate_sha256_hex(&entry.sha256, &entry.path)?;
        if entry.size > 4 * 1024 * 1024 {
            return Err(format!("Control texture too large: {}", entry.path));
        }
        let source = if entry.path == "LICENSE.md" {
            "LICENSE.md".to_string()
        } else {
            let _ = safe_relative(&entry.source)?;
            format!("resource_pack/textures/{}", entry.source)
        };
        let url = match base {
            Some(b) => format!("{b}/{}", source),
            None => format!(
                "https://raw.githubusercontent.com/Mojang/bedrock-samples/{}/{}",
                controls.revision, source
            ),
        };
        let cache_dest = work_dir.join("controls").join(&rel);
        out.push((
            FileFetchSpec {
                url,
                source_field: format!("bedrock_controls[{}]", rel.display()),
                expected_hash: entry.sha256.clone(),
                alg: HashAlg::Sha256,
                expected_size: Some(entry.size),
                dest: cache_dest,
                reuse_candidates: Vec::new(),
            },
            rel,
        ));
    }
    Ok(out)
}
