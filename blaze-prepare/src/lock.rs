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
    let actual_sha1 = sha1_bytes(bytes);
    if let Some(exp) = expected_sha1 {
        if !actual_sha1.eq_ignore_ascii_case(exp) {
            return Err(format!(
                "Version manifest SHA-1 mismatch: expected {exp}, got {actual_sha1}"
            ));
        }
    }
    let lock_wrapper = LockFile {
        minecraft: pin.clone(),
    };
    if strict_manifest_pin && !lock_wrapper.manifest_accepted(&actual_sha1) {
        return Err(format!(
            "Version manifest SHA-1 {actual_sha1} is not accepted by research-lock.json"
        ));
    }
    let manifest: Value =
        serde_json::from_slice(bytes).map_err(|e| format!("Invalid version JSON: {e}"))?;
    if manifest.get("id").and_then(Value::as_str) != Some(pin.version.as_str()) {
        return Err("Version JSON id does not match pinned Minecraft version".to_string());
    }
    if manifest
        .get("javaVersion")
        .and_then(|j| j.get("majorVersion"))
        .and_then(Value::as_u64)
        != Some(u64::from(pin.java_major))
    {
        return Err("Version JSON javaVersion.majorVersion does not match pin".to_string());
    }
    let downloads = manifest
        .get("downloads")
        .ok_or("Version JSON has no downloads section")?;
    if downloads.get("client_mappings").is_some() || downloads.get("server_mappings").is_some() {
        return Err("Unobfuscated target expected; legacy obfuscated release rejected".to_string());
    }
    let client_sha = downloads
        .get("client")
        .and_then(|c| c.get("sha1"))
        .and_then(Value::as_str)
        .unwrap_or("");
    if !client_sha.eq_ignore_ascii_case(&pin.client_sha1) {
        return Err(format!(
            "Version JSON client_sha1 ({client_sha}) != pinned ({})",
            pin.client_sha1
        ));
    }
    let server_sha = downloads
        .get("server")
        .and_then(|s| s.get("sha1"))
        .and_then(Value::as_str)
        .unwrap_or("");
    if !server_sha.eq_ignore_ascii_case(&pin.server_sha1) {
        return Err(format!(
            "Version JSON server_sha1 ({server_sha}) != pinned ({})",
            pin.server_sha1
        ));
    }
    Ok(manifest)
}
