// Blaze Launcher — on-device 26.3 engine-data preparer
// Copyright (C) 2026 Blaze Launcher contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! JNI exports for `io.github.kaito65452.blazelauncher.rustruntime.BlazePrepareNative`.

use crate::files::{peak_rss_kb, CancelFlag};
use crate::resources::{is_prepared, run_phase1, run_phase2_finalize, PrepareConfig};
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::path::Path;
use std::sync::{Mutex, OnceLock};

static CANCEL_FLAG: OnceLock<CancelFlag> = OnceLock::new();
static PROGRESS_SLOT: OnceLock<Mutex<Option<(String, u64, u64)>>> = OnceLock::new();

fn cancel_flag() -> &'static CancelFlag {
    CANCEL_FLAG.get_or_init(CancelFlag::new)
}

fn progress_slot() -> &'static Mutex<Option<(String, u64, u64)>> {
    PROGRESS_SLOT.get_or_init(|| Mutex::new(None))
}

fn set_progress(phase: &str, done: u64, total: u64) {
    if let Ok(mut guard) = progress_slot().lock() {
        *guard = Some((phase.to_string(), done, total));
    }
}

fn json_ok(payload: serde_json::Value) -> String {
    serde_json::json!({
        "ok": true,
        "data": payload
    })
    .to_string()
}

fn json_err(err: &str) -> String {
    serde_json::json!({
        "ok": false,
        "error": err,
        "peak_rss_kb": peak_rss_kb(),
    })
    .to_string()
}

fn make_jstring(env: &mut JNIEnv, text: &str) -> jstring {
    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => JObject::null().into_raw(),
    }
}

/// Starts Phase 1 preparation from a JSON-encoded `PrepareConfig` and returns a JSON result envelope.
#[no_mangle]
pub extern "system" fn Java_io_github_kaito65452_blazelauncher_rustruntime_BlazePrepareNative_nativePreparePhase1(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jstring {
    let raw: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(e) => return make_jstring(&mut env, &json_err(&format!("Invalid config string: {e}"))),
    };
    let cfg: PrepareConfig = match serde_json::from_str(&raw) {
        Ok(c) => c,
        Err(e) => return make_jstring(&mut env, &json_err(&format!("Invalid PrepareConfig JSON: {e}"))),
    };

    let res = run_phase1(&cfg, cancel_flag(), &|phase, done, total| {
        set_progress(phase, done, total);
    });
    let out = match res {
        Ok(ok) => match serde_json::to_value(ok) {
            Ok(v) => json_ok(v),
            Err(e) => json_err(&e.to_string()),
        },
        Err(e) => json_err(&e),
    };
    make_jstring(&mut env, &out)
}

/// Runs Phase 2 post-Java assembly + `engine-data.zip` packaging + atomic activation.
#[no_mangle]
pub extern "system" fn Java_io_github_kaito65452_blazelauncher_rustruntime_BlazePrepareNative_nativePreparePhase2(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jstring {
    let raw: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(e) => return make_jstring(&mut env, &json_err(&format!("Invalid config string: {e}"))),
    };
    let cfg: PrepareConfig = match serde_json::from_str(&raw) {
        Ok(c) => c,
        Err(e) => return make_jstring(&mut env, &json_err(&format!("Invalid PrepareConfig JSON: {e}"))),
    };

    let res = run_phase2_finalize(&cfg, cancel_flag(), &|phase, done, total| {
        set_progress(phase, done, total);
    });
    let out = match res {
        Ok(ok) => match serde_json::to_value(ok) {
            Ok(v) => json_ok(v),
            Err(e) => json_err(&e.to_string()),
        },
        Err(e) => json_err(&e),
    };
    make_jstring(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_io_github_kaito65452_blazelauncher_rustruntime_BlazePrepareNative_nativeRequestCancel(
    _env: JNIEnv,
    _class: JClass,
) {
    cancel_flag().cancel();
}

#[no_mangle]
pub extern "system" fn Java_io_github_kaito65452_blazelauncher_rustruntime_BlazePrepareNative_nativeResetCancel(
    _env: JNIEnv,
    _class: JClass,
) {
    cancel_flag().reset();
    if let Ok(mut guard) = progress_slot().lock() {
        *guard = None;
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kaito65452_blazelauncher_rustruntime_BlazePrepareNative_nativeGetProgressJson(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let payload = if let Ok(guard) = progress_slot().lock() {
        if let Some((phase, done, total)) = guard.as_ref() {
            serde_json::json!({
                "phase": phase,
                "done": done,
                "total": total,
                "peak_rss_kb": peak_rss_kb(),
            })
            .to_string()
        } else {
            serde_json::json!({
                "phase": "",
                "done": 0,
                "total": 0,
                "peak_rss_kb": peak_rss_kb(),
            })
            .to_string()
        }
    } else {
        "{}".to_string()
    };
    make_jstring(&mut env, &payload)
}

#[no_mangle]
pub extern "system" fn Java_io_github_kaito65452_blazelauncher_rustruntime_BlazePrepareNative_nativeIsPrepared(
    mut env: JNIEnv,
    _class: JClass,
    files_dir: JString,
    version: JString,
) -> jboolean {
    let Ok(fd) = env.get_string(&files_dir) else {
        return JNI_FALSE;
    };
    let Ok(ver) = env.get_string(&version) else {
        return JNI_FALSE;
    };
    let fd_str: String = fd.into();
    let ver_str: String = ver.into();
    if is_prepared(Path::new(&fd_str), &ver_str) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kaito65452_blazelauncher_rustruntime_BlazePrepareNative_nativePeakRssKb(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let _unused: JValue = JValue::Long(0);
    peak_rss_kb() as jlong
}
