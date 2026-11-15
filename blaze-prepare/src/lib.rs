// Blaze Launcher — on-device 26.3 engine-data preparer
// Copyright (C) 2026 Blaze Launcher contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Pure-Rust on-device preparer (`libblaze_prepare.so`) for Minecraft 26.3 engine-data on Android arm64-v8a.

pub mod bundler;
pub mod download;
pub mod files;
pub mod jni_bridge;
pub mod lock;
pub mod resources;
pub mod survival;
