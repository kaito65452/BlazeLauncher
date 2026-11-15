// Blaze Launcher — on-device 26.3 engine-data preparer
// Copyright (C) 2026 Blaze Launcher contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Streaming hashing, safe path checks, atomic `.partial` staging, cancellation, and RSS stats.

use sha1::Sha1;
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

/// Cooperative cancellation flag shared across Phase 1 / Phase 2 worker threads.
#[derive(Clone, Default)]
pub struct CancelFlag(Arc<AtomicBool>);

impl CancelFlag {
    pub fn new() -> Self {
        Self(Arc::new(AtomicBool::new(false)))
    }

    pub fn cancel(&self) {
        self.0.store(true, Ordering::SeqCst);
    }

    pub fn reset(&self) {
        self.0.store(false, Ordering::SeqCst);
    }

    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::Relaxed)
    }

    pub fn check(&self) -> Result<(), String> {
        if self.is_cancelled() {
            Err("Preparation cancelled by user".to_string())
        } else {
            Ok(())
        }
    }
}

pub fn hex_lower(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for &b in bytes {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0f) as usize] as char);
    }
    out
}

pub fn sha1_bytes(data: &[u8]) -> String {
    hex_lower(&Sha1::digest(data))
}

pub fn sha256_bytes(data: &[u8]) -> String {
    hex_lower(&Sha256::digest(data))
}

pub fn sha1_file(path: &Path) -> Result<String, String> {
    hash_file::<Sha1>(path)
}

pub fn sha256_file(path: &Path) -> Result<String, String> {
    hash_file::<Sha256>(path)
}

fn hash_file<D: Digest>(path: &Path) -> Result<String, String> {
    let mut file = fs::File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let mut digest = D::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = file
            .read(&mut buf)
            .map_err(|e| format!("{}: {e}", path.display()))?;
        if n == 0 {
            return Ok(hex_lower(&digest.finalize()));
        }
        digest.update(&buf[..n]);
    }
}

pub fn crc32_file(path: &Path) -> Result<(u64, u32), String> {
    let mut file = fs::File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let size = file
        .metadata()
        .map_err(|e| format!("{}: {e}", path.display()))?
        .len();
    let mut hasher = crc32fast::Hasher::new();
    let mut buf = vec![0u8; 32 * 1024];
    loop {
        let n = file
            .read(&mut buf)
            .map_err(|e| format!("{}: {e}", path.display()))?;
        if n == 0 {
            return Ok((size, hasher.finalize()));
        }
        hasher.update(&buf[..n]);
    }
}

/// Validates that `raw` is a clean relative archive or asset path (no `\`, no `..`, no NUL).
pub fn safe_relative(raw: &str) -> Result<PathBuf, String> {
    if raw.is_empty() || raw.contains('\\') || raw.contains('\0') || raw.starts_with('/') {
        return Err(format!("Unsafe relative path: {raw:?}"));
    }
    let path = Path::new(raw);
    let mut out = PathBuf::new();
    for comp in path.components() {
        match comp {
            Component::Normal(part) => out.push(part),
            Component::CurDir => {}
            _ => return Err(format!("Unsafe path component in {raw:?}")),
        }
    }
    if out.as_os_str().is_empty() {
        return Err(format!("Empty relative path: {raw:?}"));
    }
    Ok(out)
}

/// Computes a sibling `.partial` path for atomic file writes.
pub fn partial_path(dest: &Path) -> PathBuf {
    let mut os = dest.as_os_str().to_os_string();
    os.push(".partial");
    PathBuf::from(os)
}

/// Atomically writes `data` to `dest` via a sibling `.partial` temporary file.
pub fn atomic_write_bytes(dest: &Path, data: &[u8]) -> Result<(), String> {
    let parent = dest.parent().ok_or("Missing destination parent directory")?;
    fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    let partial = partial_path(dest);
    let write_res = (|| -> Result<(), String> {
        let mut file = fs::File::create(&partial)
            .map_err(|e| format!("{}: {e}", partial.display()))?;
        file.write_all(data)
            .map_err(|e| format!("{}: {e}", partial.display()))?;
        file.sync_all()
            .map_err(|e| format!("{}: {e}", partial.display()))?;
        Ok(())
    })();
    if let Err(err) = write_res {
        let _ = fs::remove_file(&partial);
        return Err(err);
    }
    if let Err(err) = fs::rename(&partial, dest) {
        let _ = fs::remove_file(&partial);
        return Err(format!("{}: {err}", dest.display()));
    }
    Ok(())
}

/// Atomically copies `src` to `dest` via `.partial`, optionally checking `cancel`.
pub fn atomic_copy_file(src: &Path, dest: &Path, cancel: &CancelFlag) -> Result<u64, String> {
    cancel.check()?;
    let parent = dest.parent().ok_or("Missing destination parent")?;
    fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    let partial = partial_path(dest);
    let res = (|| -> Result<u64, String> {
        let mut reader = fs::File::open(src).map_err(|e| format!("{}: {e}", src.display()))?;
        let mut writer =
            fs::File::create(&partial).map_err(|e| format!("{}: {e}", partial.display()))?;
        let mut buf = vec![0u8; 64 * 1024];
        let mut copied = 0u64;
        loop {
            cancel.check()?;
            let n = reader
                .read(&mut buf)
                .map_err(|e| format!("{}: {e}", src.display()))?;
            if n == 0 {
                break;
            }
            writer
                .write_all(&buf[..n])
                .map_err(|e| format!("{}: {e}", partial.display()))?;
            copied += n as u64;
        }
        writer
            .flush()
            .map_err(|e| format!("{}: {e}", partial.display()))?;
        Ok(copied)
    })();
    match res {
        Ok(bytes) => {
            fs::rename(&partial, dest).map_err(|e| {
                let _ = fs::remove_file(&partial);
                format!("{}: {e}", dest.display())
            })?;
            Ok(bytes)
        }
        Err(err) => {
            let _ = fs::remove_file(&partial);
            Err(err)
        }
    }
}

/// Atomically replaces `destination` directory with `stage`, keeping rollback safety via `.previous`.
pub fn atomic_activate_dir(stage: &Path, destination: &Path) -> Result<(), String> {
    let parent = destination
        .parent()
        .ok_or("Missing destination parent directory")?;
    fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    let name = destination
        .file_name()
        .ok_or("Missing destination folder name")?
        .to_string_lossy();
    let backup = parent.join(format!("{name}.previous"));

    if backup.exists() && !destination.exists() {
        fs::rename(&backup, destination).map_err(|e| e.to_string())?;
    }
    if backup.exists() {
        fs::remove_dir_all(&backup).map_err(|e| e.to_string())?;
    }
    if destination.exists() {
        fs::rename(destination, &backup).map_err(|e| e.to_string())?;
    }
    if let Err(err) = fs::rename(stage, destination) {
        if backup.exists() && !destination.exists() {
            let _ = fs::rename(&backup, destination);
        }
        return Err(format!(
            "Could not activate prepared resources {}: {err}",
            destination.display()
        ));
    }
    if backup.exists() {
        let _ = fs::remove_dir_all(&backup);
    }
    Ok(())
}

/// Removes lingering `*.partial` files recursively under `dir`.
pub fn cleanup_partials(dir: &Path) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            cleanup_partials(&path);
        } else if path
            .file_name()
            .and_then(|s| s.to_str())
            .is_some_and(|n| n.ends_with(".partial"))
        {
            let _ = fs::remove_file(&path);
        }
    }
}

/// Reads high-water-mark RSS (`VmHWM`) in KiB from `/proc/self/status` on Linux/Android.
pub fn peak_rss_kb() -> u64 {
    let Ok(status) = fs::read_to_string("/proc/self/status") else {
        return 0;
    };
    for line in status.lines() {
        if let Some(rest) = line.strip_prefix("VmHWM:") {
            if let Some(num) = rest.split_whitespace().next() {
                if let Ok(kb) = num.parse::<u64>() {
                    return kb;
                }
            }
        }
    }
    0
}
