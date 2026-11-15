// Blaze Launcher — on-device 26.3 engine-data preparer
// Copyright (C) 2026 Blaze Launcher contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! `research-lock.json` and `bedrock-controls.json` verification and URL policy.

use crate::files::sha1_bytes;
use serde::Deserialize;
use serde_json::Value;
use std::fs;
use std::path::Path;

#[derive(Debug, Clone, Deserialize)]
pub struct LockFile {
    pub minecraft: MinecraftPin,
}

#[derive(Debug, Clone, Deserialize)]
pub struct MinecraftPin {
    pub version: String,
    pub manifest_sha1: String,
    #[serde(default)]
    pub accepted_manifest_sha1: Vec<String>,
    #[serde(default)]
    pub accepted_asset_index_sha1: Vec<String>,
    pub client_sha1: String,
    pub server_sha1: String,
    pub java_major: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct BedrockControlsManifest {
    pub revision: String,
    pub files: Vec<BedrockControlEntry>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct BedrockControlEntry {
    pub path: String,
    pub source: String,
    pub sha256: String,
    pub size: u64,
}

impl LockFile {
    pub fn read(path: &Path) -> Result<Self, String> {
        let raw = fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
        let parsed: Self = serde_json::from_str(&raw)
            .map_err(|e| format!("Invalid research-lock.json ({}): {e}", path.display()))?;
        parsed.validate()?;
        Ok(parsed)
    }

    pub fn validate(&self) -> Result<(), String> {
        let v = &self.minecraft.version;
        if v.is_empty()
            || v == "."
            || v == ".."
            || !v
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"_.-".contains(&b))
        {
            return Err("Unsafe Minecraft version in research-lock.json".to_string());
        }
        validate_sha1_hex(&self.minecraft.manifest_sha1, "manifest_sha1")?;
        validate_sha1_hex(&self.minecraft.client_sha1, "client_sha1")?;
        validate_sha1_hex(&self.minecraft.server_sha1, "server_sha1")?;
        for h in &self.minecraft.accepted_manifest_sha1 {
            validate_sha1_hex(h, "accepted_manifest_sha1")?;
        }
        for h in &self.minecraft.accepted_asset_index_sha1 {
            validate_sha1_hex(h, "accepted_asset_index_sha1")?;
        }
        Ok(())
    }

    pub fn manifest_accepted(&self, sha1: &str) -> bool {
        self.minecraft.manifest_sha1.eq_ignore_ascii_case(sha1)
            || self
                .minecraft
                .accepted_manifest_sha1
                .iter()
                .any(|h| h.eq_ignore_ascii_case(sha1))
    }

    pub fn asset_index_accepted(&self, sha1: &str) -> bool {
        self.minecraft
            .accepted_asset_index_sha1
            .iter()
            .any(|h| h.eq_ignore_ascii_case(sha1))
    }

    pub fn is_local_refreshed_manifest(&self, sha1: &str) -> bool {
        self.manifest_accepted(sha1)
            && LOCAL_REFRESHED_MANIFEST_SHA1
                .iter()
                .any(|h| h.eq_ignore_ascii_case(sha1))
    }

    pub fn is_local_refreshed_asset_index(&self, sha1: &str) -> bool {
        self.asset_index_accepted(sha1)
            && LOCAL_REFRESHED_ASSET_INDEX_SHA1
                .iter()
                .any(|h| h.eq_ignore_ascii_case(sha1))
    }

    pub fn accepted_manifest_list_display(&self) -> String {
        let mut all = Vec::with_capacity(1 + self.minecraft.accepted_manifest_sha1.len());
        all.push(self.minecraft.manifest_sha1.as_str());
        for h in &self.minecraft.accepted_manifest_sha1 {
            all.push(h.as_str());
        }
        all.join(", ")
    }

    pub fn accepted_asset_index_list_display(&self) -> String {
        if self.minecraft.accepted_asset_index_sha1.is_empty() {
            "<empty>".to_string()
        } else {
            self.minecraft.accepted_asset_index_sha1.join(", ")
        }
    }
}

/// Pins added by `engine/patches/0007-pin-refresh-26.3.patch` on top of upstream commit `fa548eb7ab`.
/// Only honored when also present in `research-lock.json`.
pub const LOCAL_REFRESHED_MANIFEST_SHA1: &[&str] = &["9f67e10ed013deeaad20ac3c800652765d493408"];
pub const LOCAL_REFRESHED_ASSET_INDEX_SHA1: &[&str] = &["e99f9973047785b5a9d52eaf72020b97545053c9"];

#[derive(Debug, Clone)]
pub struct ManifestValidationInfo {
    pub manifest_sha1: String,
    pub asset_index_sha1: String,
}

impl BedrockControlsManifest {
    pub fn read(path: &Path) -> Result<Self, String> {
        let raw = fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
        let parsed: Self = serde_json::from_str(&raw)
            .map_err(|e| format!("Invalid bedrock-controls.json ({}): {e}", path.display()))?;
        if parsed.revision.is_empty()
            || !parsed.revision.bytes().all(|b| b.is_ascii_hexdigit())
        {
            return Err("Unsafe revision in bedrock-controls.json".to_string());
        }
        Ok(parsed)
    }
}

pub fn validate_sha1_hex(s: &str, label: &str) -> Result<(), String> {
    if s.len() != 40 || !s.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(format!("Invalid SHA-1 digest for {label}: {s}"));
    }
    Ok(())
}

pub fn validate_sha256_hex(s: &str, label: &str) -> Result<(), String> {
    if s.len() != 64 || !s.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(format!("Invalid SHA-256 digest for {label}: {s}"));
    }
    Ok(())
}

/// Verifies URL host and scheme: production builds allow official Mojang CDN or `Mojang/bedrock-samples`
/// over HTTPS only; unit/synthetic tests may enable loopback HTTP.
pub fn validate_download_url(raw: &str, allow_loopback: bool) -> Result<(), String> {
    let parsed = url::Url::parse(raw).map_err(|e| format!("Malformed URL {raw}: {e}"))?;
    if !parsed.username().is_empty() || parsed.password().is_some() {
        return Err("Credentials in download URL are forbidden".to_string());
    }
    if allow_loopback
        && parsed.scheme() == "http"
        && matches!(parsed.host_str(), Some("127.0.0.1" | "localhost"))
    {
        return Ok(());
    }
    if parsed.scheme() != "https" || parsed.port().is_some_and(|p| p != 443) {
        return Err(format!("Download URL must use HTTPS on port 443: {raw}"));
    }
    let host = parsed.host_str().unwrap_or("");
    let allowed = matches!(
        host,
        "piston-meta.mojang.com"
            | "piston-data.mojang.com"
            | "resources.download.minecraft.net"
            | "launcher.mojang.com"
            | "launchermeta.mojang.com"
            | "raw.githubusercontent.com"
    );
    if !allowed {
        return Err(format!("Untrusted download host: {host}"));
    }
    Ok(())
}

/// Validates a Mojang version JSON against `research-lock.json` pins.
pub fn validate_version_manifest(
    bytes: &[u8],
    expected_sha1: Option<&str>,
    pin: &MinecraftPin,
    strict_manifest_pin: bool,
) -> Result<Value, String> {
    validate_version_manifest_with_source(
        bytes,
        expected_sha1,
        pin,
        strict_manifest_pin,
        "version manifest",
    )
    .map(|(val, _)| val)
}

/// Validates a Mojang version JSON with source labeling and enforces both manifest and asset-index pins
/// as well as strict client/server jar SHA-1 pins.
pub fn validate_version_manifest_with_source(
    bytes: &[u8],
    expected_sha1: Option<&str>,
    pin: &MinecraftPin,
    strict_manifest_pin: bool,
    source_label: &str,
) -> Result<(Value, ManifestValidationInfo), String> {
    let lock_wrapper = LockFile {
        minecraft: pin.clone(),
    };
    let actual_sha1 = sha1_bytes(bytes);
    if let Some(exp) = expected_sha1 {
        if !actual_sha1.eq_ignore_ascii_case(exp) {
            return Err(crate::files::redact_sensitive(&format!(
                "Version manifest SHA-1 mismatch while hashing {source_label}: computed sha1={actual_sha1}, expected={exp}. Next step: check network integrity or delete the corrupted cached file and retry preparation."
            )));
        }
    }
    if strict_manifest_pin && !lock_wrapper.manifest_accepted(&actual_sha1) {
        return Err(crate::files::redact_sensitive(&format!(
            "Version manifest sha1 ({actual_sha1}) from {source_label} is not accepted by research-lock.json (accepted: [{}]). Next step: Mojang may have republished {} or the file was modified; install/verify vanilla {} in Zalith Launcher or refresh accepted_manifest_sha1 after confirming client/server jar pins match.",
            lock_wrapper.accepted_manifest_list_display(),
            pin.version,
            pin.version,
        )));
    }
    let manifest: Value =
        serde_json::from_slice(bytes).map_err(|e| format!("Invalid version JSON ({source_label}): {e}"))?;
    if manifest.get("id").and_then(Value::as_str) != Some(pin.version.as_str()) {
        return Err(format!(
            "Version JSON id in {source_label} does not match pinned Minecraft version {}",
            pin.version
        ));
    }
    if manifest
        .get("javaVersion")
        .and_then(|j| j.get("majorVersion"))
        .and_then(Value::as_u64)
        != Some(u64::from(pin.java_major))
    {
        return Err(format!(
            "Version JSON javaVersion.majorVersion in {source_label} does not match pinned Java {}",
            pin.java_major
        ));
    }
    let downloads = manifest
        .get("downloads")
        .ok_or_else(|| format!("Version JSON ({source_label}) has no downloads section"))?;
    if downloads.get("client_mappings").is_some() || downloads.get("server_mappings").is_some() {
        return Err(format!(
            "Unobfuscated target expected ({source_label}); legacy obfuscated release rejected"
        ));
    }
    let client_sha = downloads
        .get("client")
        .and_then(|c| c.get("sha1"))
        .and_then(Value::as_str)
        .unwrap_or("");
    if !client_sha.eq_ignore_ascii_case(&pin.client_sha1) {
        return Err(crate::files::redact_sensitive(&format!(
            "Version JSON client_sha1 mismatch in {source_label}: got {client_sha}, expected pinned {}. Next step: do NOT override client_sha1; download official Minecraft {} from Mojang or restore an unmodified jar.",
            pin.client_sha1,
            pin.version
        )));
    }
    let server_sha = downloads
        .get("server")
        .and_then(|s| s.get("sha1"))
        .and_then(Value::as_str)
        .unwrap_or("");
    if !server_sha.eq_ignore_ascii_case(&pin.server_sha1) {
        return Err(crate::files::redact_sensitive(&format!(
            "Version JSON server_sha1 mismatch in {source_label}: got {server_sha}, expected pinned {}. Next step: do NOT override server_sha1; wait for verified upstream support if Mojang updated server artifacts.",
            pin.server_sha1
        )));
    }
    let asset_index_sha1 = manifest
        .get("assetIndex")
        .and_then(|a| a.get("sha1"))
        .and_then(Value::as_str)
        .ok_or_else(|| format!("Version JSON ({source_label}) missing assetIndex.sha1"))?;
    validate_sha1_hex(asset_index_sha1, "manifest assetIndex.sha1")?;
    if !lock_wrapper.asset_index_accepted(asset_index_sha1) {
        return Err(crate::files::redact_sensitive(&format!(
            "Asset index sha1 ({asset_index_sha1}) referenced by {source_label} is not accepted by research-lock.json (accepted: [{}]). Next step: Mojang republished the {} asset index; verify jars still match upstream pins and update accepted_asset_index_sha1 via an explicit lock refresh.",
            lock_wrapper.accepted_asset_index_list_display(),
            pin.version
        )));
    }
    Ok((
        manifest,
        ManifestValidationInfo {
            manifest_sha1: actual_sha1,
            asset_index_sha1: asset_index_sha1.to_string(),
        },
    ))
}
