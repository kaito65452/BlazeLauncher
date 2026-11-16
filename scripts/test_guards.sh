#!/usr/bin/env bash
# Blaze Launcher — self-test suite for scripts/guard.sh
# Copyright (C) 2026 Blaze Launcher contributors
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TMP_ROOT="$(mktemp -d)"
cleanup() {
    rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

PASS_COUNT=0
FAIL_COUNT=0

expect_pass() {
    local name="$1"
    shift
    echo "--- [TEST PASS] $name ---"
    if "$@"; then
        echo "PASS: $name"
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        echo "FAIL: $name (expected pass, exited non-zero)"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
}

expect_fail() {
    local name="$1"
    local expected_pattern="$2"
    shift 2
    echo "--- [TEST FAIL-ON-BAD] $name ---"
    local out
    set +e
    out="$("$@" 2>&1)"
    local rc=$?
    set -e
    echo "$out"
    if [ "$rc" -eq 0 ]; then
        echo "FAIL: $name (expected failure, got exit 0)"
        FAIL_COUNT=$((FAIL_COUNT + 1))
        return
    fi
    if ! grep -qE "$expected_pattern" <<<"$out"; then
        echo "FAIL: $name (exited $rc, but output missed /$expected_pattern/)"
        FAIL_COUNT=$((FAIL_COUNT + 1))
        return
    fi
    echo "PASS: $name (caught with exit $rc)"
    PASS_COUNT=$((PASS_COUNT + 1))
}

echo "=== 1. Check 7 Self-Tests (font include_bytes!/include_str!) ==="

# Valid SVG concat! macro should NOT trigger false positive
CASE_VALID="$TMP_ROOT/check7_valid"
mkdir -p "$CASE_VALID/src"
cat > "$CASE_VALID/src/lib.rs" <<'RS'
const fn icon(name: &str) -> &'static [u8] { &[] }
macro_rules! icon_svg {
    ($name:literal) => {
        include_bytes!(concat!("../icons/pixel/", $name, ".svg"))
    };
}
RS
expect_pass "Check 7 allows valid SVG concat! include_bytes!" \
    env GUARD_CHECK7_ONLY_DIR="$CASE_VALID" bash scripts/guard.sh

# Known-bad 1: single-line .ttf
CASE_SINGLE="$TMP_ROOT/check7_single_ttf"
mkdir -p "$CASE_SINGLE/src"
cat > "$CASE_SINGLE/src/lib.rs" <<'RS'
static BAD_FONT: &[u8] = include_bytes!("../assets/Geist-Regular.ttf");
RS
expect_fail "Check 7 rejects single-line .ttf include_bytes!" "resolved='.*Geist-Regular\.ttf'" \
    env GUARD_CHECK7_ONLY_DIR="$CASE_SINGLE" bash scripts/guard.sh

# Known-bad 2: single-line include_str! under fonts/
CASE_FONTS_STR="$TMP_ROOT/check7_fonts_str"
mkdir -p "$CASE_FONTS_STR/src"
cat > "$CASE_FONTS_STR/src/lib.rs" <<'RS'
static BAD_OFL: &str = include_str!("../fonts/OFL-Geist.txt");
RS
expect_fail "Check 7 rejects single-line fonts/ include_str!" "fonts/OFL-Geist\.txt" \
    env GUARD_CHECK7_ONLY_DIR="$CASE_FONTS_STR" bash scripts/guard.sh

# Known-bad 3: multi-line .otf include_bytes!
CASE_MULTILINE="$TMP_ROOT/check7_multiline_otf"
mkdir -p "$CASE_MULTILINE/src"
cat > "$CASE_MULTILINE/src/lib.rs" <<'RS'
static BAD_MULTI: &[u8] = include_bytes!(
    "../assets/PixelifySans.otf"
);
RS
expect_fail "Check 7 rejects multi-line .otf include_bytes!" "PixelifySans\.otf" \
    env GUARD_CHECK7_ONLY_DIR="$CASE_MULTILINE" bash scripts/guard.sh

# Known-bad 4: concat! + env! composing .ttf across pieces
CASE_CONCAT="$TMP_ROOT/check7_concat_env"
mkdir -p "$CASE_CONCAT/src"
cat > "$CASE_CONCAT/src/lib.rs" <<'RS'
static BAD_CONCAT: &[u8] = include_bytes!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/ui_blobs/",
    "PixelifySans",
    ".t",
    "tf"
));
RS
expect_fail "Check 7 rejects concat!/env!-composed .ttf include_bytes!" "PixelifySans\.ttf" \
    env GUARD_CHECK7_ONLY_DIR="$CASE_CONCAT" bash scripts/guard.sh

# Known-bad 5: concat! composing a fonts/ directory path without .ttf extension
CASE_CONCAT_DIR="$TMP_ROOT/check7_concat_fonts_dir"
mkdir -p "$CASE_CONCAT_DIR/src"
cat > "$CASE_CONCAT_DIR/src/lib.rs" <<'RS'
static BAD_DIR: &[u8] = include_bytes!(concat!("../", "fonts", "/blob.bin"));
RS
expect_fail "Check 7 rejects concat!-composed fonts/ path" "\.\./fonts/blob\.bin" \
    env GUARD_CHECK7_ONLY_DIR="$CASE_CONCAT_DIR" bash scripts/guard.sh

echo ""
echo "=== 2. Check 8 Self-Tests (--check-apk) ==="

BASE_APK="$REPO_ROOT/signed-apks/BlazeLauncher-2.6_hotfix-blaze.be1defc0.apk"
SYNTH_NEG_BASE="$TMP_ROOT/valid_neg_base.apk"
python3 - "$SYNTH_NEG_BASE" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("assets/THIRD_PARTY_NOTICES.md", b"# Notices\n")
    z.writestr("assets/fonts/Geist-Regular.ttf", b"\x00\x01\x00\x00dummy")
    z.writestr("assets/fonts/OFL-Geist.txt", b"SIL Open Font License")
    z.writestr("lib/arm64-v8a/libclean.so", b"\x7fELFclean binary payload")
PY

if [ -f "$BASE_APK" ]; then
    SYNTH_VALID_BASE="$TMP_ROOT/valid_with_notices.apk"
    python3 - "$BASE_APK" "$SYNTH_VALID_BASE" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", compression=zipfile.ZIP_STORED) as zout:
    for item in zin.infolist():
        if item.filename.endswith(".so") or item.filename.startswith("assets/"):
            zout.writestr(item.filename, zin.read(item.filename))
    if "assets/THIRD_PARTY_NOTICES.md" not in zout.namelist():
        zout.writestr("assets/THIRD_PARTY_NOTICES.md", b"# Notices\n")
PY
    expect_pass "Check 8 scans all existing upstream .so files cleanly when notices present" \
        bash scripts/guard.sh --check-apk "$SYNTH_VALID_BASE"
else
    expect_pass "Check 8 passes on clean synthetic APK" \
        bash scripts/guard.sh --check-apk "$SYNTH_NEG_BASE"
fi

# Helper to clone SYNTH_NEG_BASE and inject extra entries
make_bad_apk() {
    local out_apk="$1"
    local entry_name="$2"
    local mode="$3"
    python3 - "$SYNTH_NEG_BASE" "$out_apk" "$entry_name" "$mode" <<'PY'
import struct, sys, zipfile
src, dst, entry_name, mode = sys.argv[1:5]
if mode == "ascii":
    payload = b"\x7fELF" + b"\x00" * 64 + b"Copyright (c) The Geist Project Authors" + b"\x00" * 32
elif mode == "utf16be":
    payload = b"\x7fELF" + b"\x00" * 64 + "SIL Open Font License".encode("utf-16-be") + b"\x00" * 32
elif mode == "sfnt":
    # Valid minimal SFNT TrueType header (\x00\x01\x00\x00 + 4 tables: cmap, glyf, head, name)
    tags = [b"cmap", b"glyf", b"head", b"name"]
    header = struct.pack(">IHHHH", 0x00010000, len(tags), 64, 2, 0)
    records = b"".join(struct.pack(">4sIII", t, 0, 12 + 16 * len(tags) + i * 4, 4) for i, t in enumerate(tags))
    payload = b"\x7fELF" + b"\x00" * 16 + header + records + b"\x00" * 32
elif mode in ("missing_notices", "missing_ofl"):
    payload = None
else:
    raise ValueError(mode)

with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", compression=zipfile.ZIP_STORED) as zout:
    for item in zin.infolist():
        if mode == "missing_notices" and item.filename == "assets/THIRD_PARTY_NOTICES.md":
            continue
        if mode == "missing_ofl" and item.filename == "assets/fonts/OFL-Geist.txt":
            continue
        zout.writestr(item.filename, zin.read(item.filename))
    if payload is not None:
        zout.writestr(entry_name, payload)
PY
}

BAD_ASCII_APK="$TMP_ROOT/bad_ascii.apk"
make_bad_apk "$BAD_ASCII_APK" "lib/arm64-v8a/libhorizon_android.so" "ascii"
expect_fail "Check 8 rejects ASCII OFL marker in lib/arm64-v8a/libhorizon_android.so" "ASCII font marker" \
    bash scripts/guard.sh --check-apk "$BAD_ASCII_APK"

BAD_UTF16_APK="$TMP_ROOT/bad_utf16.apk"
make_bad_apk "$BAD_UTF16_APK" "lib/armeabi-v7a/libother.so" "utf16be"
expect_fail "Check 8 rejects UTF-16BE OFL marker in any lib/*/*.so" "UTF-16BE font marker" \
    bash scripts/guard.sh --check-apk "$BAD_UTF16_APK"

BAD_SFNT_APK="$TMP_ROOT/bad_sfnt.apk"
make_bad_apk "$BAD_SFNT_APK" "lib/arm64-v8a/libsfnt.so" "sfnt"
expect_fail "Check 8 rejects embedded SFNT font data in any lib/*/*.so" "embedded TrueType/OpenType font data" \
    bash scripts/guard.sh --check-apk "$BAD_SFNT_APK"

BAD_NOTICES_APK="$TMP_ROOT/bad_notices.apk"
make_bad_apk "$BAD_NOTICES_APK" "" "missing_notices"
expect_fail "Check 8 rejects APK missing THIRD_PARTY_NOTICES.md" "missing assets/THIRD_PARTY_NOTICES.md" \
    bash scripts/guard.sh --check-apk "$BAD_NOTICES_APK"

BAD_OFL_APK="$TMP_ROOT/bad_ofl.apk"
make_bad_apk "$BAD_OFL_APK" "" "missing_ofl"
expect_fail "Check 8 rejects fonts/*.ttf without OFL-*.txt" "without assets/fonts/OFL-\*\.txt" \
    bash scripts/guard.sh --check-apk "$BAD_OFL_APK"

echo ""
echo "=== 3. Check 5b Self-Test (missing/empty engine/starling must FAIL) ==="

MOCK_REPO="$TMP_ROOT/mock_repo"
mkdir -p "$MOCK_REPO/ZalithLauncher" "$MOCK_REPO/.github/workflows" "$MOCK_REPO/scripts" "$MOCK_REPO/engine/starling"
cat > "$MOCK_REPO/LICENSE" <<'LIC'
GNU GENERAL PUBLIC LICENSE
Version 3, 29 June 2007
LIC
cat > "$MOCK_REPO/ZalithLauncher/build.gradle.kts" <<'KTS'
android { defaultConfig { applicationId = "io.github.kaito65452.blazelauncher" } }
KTS
cat > "$MOCK_REPO/.gitmodules" <<'GM'
[submodule "engine/starling"]
	path = engine/starling
	url = https://github.com/starling-client/starling.git
GM
cp scripts/guard.sh "$MOCK_REPO/scripts/guard.sh"
(
    cd "$MOCK_REPO"
    git init -q
    git config user.email "test@example.com"
    git config user.name "Test"
    git add LICENSE ZalithLauncher/build.gradle.kts .gitmodules scripts/guard.sh
    git commit -q -m "mock"
)
expect_fail "Check 5b fails when .gitmodules declares engine/starling but dir is empty" "engine/starling is missing or empty" \
    bash -c "cd '$MOCK_REPO' && bash scripts/guard.sh"

expect_fail "Check 5b fails when ENGINE_BUILD_ENABLED=1 and engine/starling is empty" "engine/starling is missing or empty" \
    bash -c "cd '$MOCK_REPO' && rm -f .gitmodules && ENGINE_BUILD_ENABLED=1 bash scripts/guard.sh"

echo ""
echo "=== 4. Check 8b Self-Test (--check-release-apk non-debuggable check) ==="

make_manifest_apk() {
    local out_apk="$1"
    local mode="$2" # omitted | explicit_false | true
    python3 - "$SYNTH_NEG_BASE" "$out_apk" "$mode" <<'PY'
import struct, sys, zipfile

src, dst, mode = sys.argv[1:4]

def build_axml(dbg_mode: str) -> bytes:
    strings = ["manifest", "application", "debuggable"]
    # Encode UTF-8 string pool cleanly
    pool_entries = bytearray()
    offsets = []
    for s in strings:
        offsets.append(len(pool_entries))
        b = s.encode("utf-8")
        pool_entries.extend([len(s), len(b)])
        pool_entries.extend(b)
        pool_entries.append(0)
    while len(pool_entries) % 4 != 0:
        pool_entries.append(0)
    str_hdr_size = 28
    strings_start = str_hdr_size + 4 * len(strings)
    sp_size = strings_start + len(pool_entries)
    sp_chunk = (
        struct.pack("<HHI", 0x0001, str_hdr_size, sp_size)
        + struct.pack("<IIIII", len(strings), 0, 1 << 8, strings_start, 0)
        + struct.pack(f"<{len(strings)}I", *offsets)
        + bytes(pool_entries)
    )
    # RES_XML_RESOURCE_MAP_TYPE (assign 0x0101000f to index 2 = "debuggable")
    res_ids = [0, 0, 0x0101000F]
    rm_chunk = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(res_ids)) + struct.pack(f"<{len(res_ids)}I", *res_ids)

    attrs = b""
    attr_count = 0
    if dbg_mode in ("explicit_false", "true"):
        attr_count = 1
        val = 0 if dbg_mode == "explicit_false" else 0xFFFFFFFF
        attrs = struct.pack("<iiiHBBI", -1, 2, -1, 8, 0, 0x12, val)

    start_app = (
        struct.pack("<HHI", 0x0102, 16, 16 + 20 + len(attrs))
        + struct.pack("<Ii", 1, -1)
        + struct.pack("<iiHHHHHH", -1, 1, 20, 20, attr_count, 0, 0, 0)
        + attrs
    )
    end_app = struct.pack("<HHIIiii", 0x0103, 16, 24, 1, -1, -1, 1)
    body = sp_chunk + rm_chunk + start_app + end_app
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body

manifest_bytes = build_axml(mode)
with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", compression=zipfile.ZIP_STORED) as zout:
    for item in zin.infolist():
        if item.filename == "AndroidManifest.xml":
            continue
        zout.writestr(item.filename, zin.read(item.filename))
    zout.writestr("AndroidManifest.xml", manifest_bytes)
PY
}

REL_OMITTED_APK="$TMP_ROOT/rel_omitted.apk"
make_manifest_apk "$REL_OMITTED_APK" "omitted"
expect_pass "Check 8b passes release APK without android:debuggable attribute" \
    bash scripts/guard.sh --check-release-apk "$REL_OMITTED_APK"

REL_FALSE_APK="$TMP_ROOT/rel_false.apk"
make_manifest_apk "$REL_FALSE_APK" "explicit_false"
expect_pass "Check 8b passes release APK with android:debuggable=false" \
    bash scripts/guard.sh --check-release-apk "$REL_FALSE_APK"

REL_TRUE_APK="$TMP_ROOT/rel_true.apk"
make_manifest_apk "$REL_TRUE_APK" "true"
expect_fail "Check 8b rejects debuggable=true APK" "android:debuggable=true" \
    bash scripts/guard.sh --check-release-apk "$REL_TRUE_APK"

echo ""
echo "=== 5. Check 9 Self-Test (Developer package safety guard) ==="

expect_pass "Check 9 passes on real ZalithLauncher developer package" \
    env GUARD_CHECK9_ONLY_DIR="ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/developer" bash scripts/guard.sh

BAD_DEV_OFFLINE="$TMP_ROOT/dev_offline"
mkdir -p "$BAD_DEV_OFFLINE"
cat > "$BAD_DEV_OFFLINE/BadOffline.kt" <<'KT'
package io.github.kaito65452.blazelauncher.developer
fun unlockOffline() { localLogin("OfflinePlayer:test") }
KT
expect_fail "Check 9 rejects offline-account creation references in developer package" "Forbidden account/ownership/token reference" \
    env GUARD_CHECK9_ONLY_DIR="$BAD_DEV_OFFLINE" bash scripts/guard.sh

BAD_DEV_TOKEN="$TMP_ROOT/dev_token"
mkdir -p "$BAD_DEV_TOKEN"
cat > "$BAD_DEV_TOKEN/BadToken.kt" <<'KT'
package io.github.kaito65452.blazelauncher.developer
data class Leak(val accessToken: String, val refreshToken: String)
KT
expect_fail "Check 9 rejects token field references in developer package" "Forbidden account/ownership/token reference" \
    env GUARD_CHECK9_ONLY_DIR="$BAD_DEV_TOKEN" bash scripts/guard.sh

BAD_DEV_IMPORT="$TMP_ROOT/dev_import"
mkdir -p "$BAD_DEV_IMPORT"
cat > "$BAD_DEV_IMPORT/BadImport.kt" <<'KT'
package io.github.kaito65452.blazelauncher.developer
import com.movtery.zalithlauncher.game.account.AccountType
KT
expect_fail "Check 9 rejects account package import in developer package" "Forbidden account/ownership/token reference" \
    env GUARD_CHECK9_ONLY_DIR="$BAD_DEV_IMPORT" bash scripts/guard.sh

echo ""
echo "=== 6. Check 10 Self-Tests (Engine login neutralization + .so binary markers) ==="

# Positive: comments, #[cfg(test)], #[cfg(not(target_os = "android"))], and /session/join stay allowed
CASE10_CLEAN="$TMP_ROOT/check10_clean"
mkdir -p "$CASE10_CLEAN/src"
cat > "$CASE10_CLEAN/src/lib.rs" <<'RS'
// Previously used 00000000441cc96b and https://login.microsoftonline.com/consumers/oauth2/v2.0
/* Nested /* block comment: 00000000402b5328 https://user.auth.xboxlive.com/user/authenticate */
pub fn ely_server_join() -> &'static str {
    "https://authserver.ely.by/session/join"
}
#[cfg(not(target_os = "android"))]
pub fn desktop_only_auth() -> (&'static str, &'static str) {
    ("https://login.microsoftonline.com/consumers/oauth2/v2.0", "https://xsts.auth.xboxlive.com/xsts/authorize")
}
#[cfg(test)]
mod tests {
    const TEST_ID: &str = "00000000441cc96b";
}
RS
expect_pass "Check 10 allows comments, cfg(test), cfg(not(android)), and ely /session/join" \
    env GUARD_CHECK10_ONLY_DIR="$CASE10_CLEAN" bash scripts/guard.sh

# Negative 1: un-gated client ID 00000000441cc96b
CASE10_CID1="$TMP_ROOT/check10_cid1"
mkdir -p "$CASE10_CID1/src"
cat > "$CASE10_CID1/src/lib.rs" <<'RS'
pub const CLIENT_ID: &str = "00000000441cc96b";
RS
expect_fail "Check 10 rejects un-gated 00000000441cc96b" "embedded Starling/Mojang client ID '00000000441cc96b'" \
    env GUARD_CHECK10_ONLY_DIR="$CASE10_CID1" bash scripts/guard.sh

# Negative 2: obfuscated/split client ID via concat!
CASE10_CONCAT="$TMP_ROOT/check10_concat"
mkdir -p "$CASE10_CONCAT/src"
cat > "$CASE10_CONCAT/src/lib.rs" <<'RS'
pub const CLIENT_ID: &str = concat!("00000000", "402b5328");
RS
expect_fail "Check 10 rejects concat!-split 00000000402b5328" "embedded Starling/Mojang client ID '00000000402b5328'" \
    env GUARD_CHECK10_ONLY_DIR="$CASE10_CONCAT" bash scripts/guard.sh

# Negative 3: URL containing // inside string literal (defeats naive // comment stripping!)
CASE10_URL="$TMP_ROOT/check10_url_slashes"
mkdir -p "$CASE10_URL/src"
cat > "$CASE10_URL/src/lib.rs" <<'RS'
// Benign comment line before active URL:
pub const MS_TOKEN_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
RS
expect_fail "Check 10 catches https://login.microsoftonline.com without naive // truncation" "active OAuth/Xbox/Ely login endpoint 'login\.microsoftonline\.com'" \
    env GUARD_CHECK10_ONLY_DIR="$CASE10_URL" bash scripts/guard.sh

# Negative 4: Android-targeted XSTS URL behind #[cfg(target_os = "android")]
CASE10_ANDROID_CFG="$TMP_ROOT/check10_android_cfg"
mkdir -p "$CASE10_ANDROID_CFG/src"
cat > "$CASE10_ANDROID_CFG/src/lib.rs" <<'RS'
#[cfg(target_os = "android")]
pub fn xsts() -> &'static str { "https://xsts.auth.xboxlive.com/xsts/authorize" }
RS
expect_fail "Check 10 rejects Android-targeted XSTS endpoint" "active OAuth/Xbox/Ely login endpoint 'xsts\.auth\.xboxlive\.com'" \
    env GUARD_CHECK10_ONLY_DIR="$CASE10_ANDROID_CFG" bash scripts/guard.sh

# Negative 5: active login call inside Rust code (not just a string literal)
CASE10_CALL="$TMP_ROOT/check10_call"
mkdir -p "$CASE10_CALL/src"
cat > "$CASE10_CALL/src/lib.rs" <<'RS'
pub fn start_login() { let _ = account::device_code(&ep, "abc"); }
RS
expect_fail "Check 10 rejects active account::device_code call" "active engine login/refresh call" \
    env GUARD_CHECK10_ONLY_DIR="$CASE10_CALL" bash scripts/guard.sh

# Negative 6: setSession writing to disk in blaze_diagnostics.rs
CASE10_DISK="$TMP_ROOT/check10_disk"
mkdir -p "$CASE10_DISK/crates/horizon-client/src/android"
cat > "$CASE10_DISK/crates/horizon-client/src/android/blaze_diagnostics.rs" <<'RS'
pub fn set_session_bad(config: &Cfg, home: &Path) { let _ = config.save(home); }
RS
expect_fail "Check 10 rejects disk save in blaze_diagnostics.rs" "must not write session data to disk" \
    env GUARD_CHECK10_ONLY_DIR="$CASE10_DISK" bash scripts/guard.sh

# Negative 6b: full 32-hex UUID logged in BlazeSessionHandoff
CASE10_FULL_UUID="$TMP_ROOT/check10_full_uuid"
mkdir -p "$CASE10_FULL_UUID/crates/horizon-client/src/android"
cat > "$CASE10_FULL_UUID/crates/horizon-client/src/android/blaze_diagnostics.rs" <<'RS'
pub fn log_bad(uuid: u128, len: usize) { log::info!("BlazeSessionHandoff: staged (uuid={:032x}, token_len={})", uuid, len); }
RS
expect_fail "Check 10 rejects full 32-hex UUID in BlazeSessionHandoff log" "must only log first 8 UUID chars" \
    env GUARD_CHECK10_ONLY_DIR="$CASE10_FULL_UUID" bash scripts/guard.sh

# Desktop-only launcher/src/{backend,cli,main,mcp}.rs vs Android-shared launcher/src/account.rs (including starling/ prefix fallback)
CASE10_DESKTOP_ONLY="$TMP_ROOT/check10_desktop_only"
mkdir -p "$CASE10_DESKTOP_ONLY/launcher/src"
cat > "$CASE10_DESKTOP_ONLY/launcher/src/backend.rs" <<'RS'
pub fn desktop_download() { let _ = account::Endpoints::live(); }
RS
expect_pass "Check 10 allows desktop-only launcher/src/backend.rs Endpoints::live()" \
    env GUARD_CHECK10_ONLY_DIR="$CASE10_DESKTOP_ONLY" bash scripts/guard.sh

CASE10_STARLING_PREFIX="$TMP_ROOT/check10_starling_prefix"
mkdir -p "$CASE10_STARLING_PREFIX/starling/launcher/src"
cat > "$CASE10_STARLING_PREFIX/starling/launcher/src/backend.rs" <<'RS'
pub fn desktop_download() { let _ = account::Endpoints::live(); }
RS
expect_pass "Check 10 allows starling/launcher/src/backend.rs when scanning parent dir" \
    env GUARD_CHECK10_ONLY_DIR="$CASE10_STARLING_PREFIX" bash scripts/guard.sh

CASE10_ACCOUNT_LIVE="$TMP_ROOT/check10_account_live"
mkdir -p "$CASE10_ACCOUNT_LIVE/launcher/src"
cat > "$CASE10_ACCOUNT_LIVE/launcher/src/account.rs" <<'RS'
pub fn android_account_leak() { let _ = Endpoints::live(); }
RS
expect_fail "Check 10 rejects Endpoints::live() in Android-compiled launcher/src/account.rs" "active engine login/refresh call 'Endpoints::live'" \
    env GUARD_CHECK10_ONLY_DIR="$CASE10_ACCOUNT_LIVE" bash scripts/guard.sh

# Positive & Negative APK .so scans (Check 10b)
make_so_marker_apk() {
    local out_apk="$1"
    local so_payload="$2"
    python3 - "$SYNTH_NEG_BASE" "$out_apk" "$so_payload" <<'PY'
import sys, zipfile
src, dst, payload = sys.argv[1:4]
with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", compression=zipfile.ZIP_STORED) as zout:
    for item in zin.infolist():
        zout.writestr(item.filename, zin.read(item.filename))
    zout.writestr("lib/arm64-v8a/libhorizon_android.so", b"\x7fELF\x00" + payload.encode("ascii") + b"\x00")
PY
}

SO_JOIN_OK="$TMP_ROOT/so_ely_join_only.apk"
make_so_marker_apk "$SO_JOIN_OK" "https://authserver.ely.by/session/join"
expect_pass "Check 10b allows https://authserver.ely.by/session/join in libhorizon_android.so" \
    bash scripts/guard.sh --check-apk "$SO_JOIN_OK"

SO_BAD_CID="$TMP_ROOT/so_bad_cid.apk"
make_so_marker_apk "$SO_BAD_CID" "00000000441cc96b"
expect_fail "Check 10b rejects 00000000441cc96b in libhorizon_android.so" "Starling OAuth/login marker '00000000441cc96b'" \
    bash scripts/guard.sh --check-apk "$SO_BAD_CID"

SO_BAD_URL="$TMP_ROOT/so_bad_url.apk"
make_so_marker_apk "$SO_BAD_URL" "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
expect_fail "Check 10b rejects login.microsoftonline.com in libhorizon_android.so" "Starling OAuth/login marker 'login\.microsoftonline\.com'" \
    bash scripts/guard.sh --check-apk "$SO_BAD_URL"

echo ""
echo "=== 7. Check 11 Self-Tests (Stage B preparer & asset hygiene) ==="

expect_pass "Check 11 passes on real repo blaze-prepare and ZalithLauncher assets" \
    env GUARD_CHECK11_ONLY_DIR="." bash scripts/guard.sh

BAD_PREPARE_PY="$TMP_ROOT/bad_prepare_py"
mkdir -p "$BAD_PREPARE_PY/blaze-prepare/src"
touch "$BAD_PREPARE_PY/blaze-prepare/src/helper.py"
expect_fail "Check 11 rejects Python file inside blaze-prepare/" "forbidden Python or NBT file" \
    env GUARD_CHECK11_ONLY_DIR="$BAD_PREPARE_PY" bash scripts/guard.sh

BAD_PREPARE_NBT="$TMP_ROOT/bad_prepare_nbt"
mkdir -p "$BAD_PREPARE_NBT/ZalithLauncher/src/main/assets/worldgen"
touch "$BAD_PREPARE_NBT/ZalithLauncher/src/main/assets/worldgen/village.nbt"
expect_fail "Check 11 rejects NBT/worldgen assets in ZalithLauncher assets/" "forbidden Python or NBT file|forbidden Mojang assets" \
    env GUARD_CHECK11_ONLY_DIR="$BAD_PREPARE_NBT" bash scripts/guard.sh

BAD_APK_PY="$TMP_ROOT/bad_stageb_py.apk"
python3 - "$SYNTH_NEG_BASE" "$BAD_APK_PY" <<'PY'
import sys, zipfile
src, dst = sys.argv[1:3]
with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", compression=zipfile.ZIP_STORED) as zout:
    for item in zin.infolist():
        zout.writestr(item.filename, zin.read(item.filename))
    zout.writestr("assets/blaze-prepare/helper.py", b"print('bad')\n")
PY
expect_fail "Check 11 APK check rejects .py bundled into APK assets" "disallowed asset entries" \
    bash scripts/guard.sh --check-apk "$BAD_APK_PY"

echo ""
echo "=== 7b. K4 Bundled engine-data.zip Check 11 Self-Tests ==="

make_k4_apk() {
    local out_apk="$1"
    local asset_path="$2"
    local extra_entry="${3:-}"
    python3 - "$SYNTH_NEG_BASE" "$out_apk" "$asset_path" "$extra_entry" <<'PY'
import io, sys, zipfile
src, dst, asset_path, extra_entry = sys.argv[1:5]
inner = io.BytesIO()
with zipfile.ZipFile(inner, "w", compression=zipfile.ZIP_DEFLATED) as nz:
    nz.writestr("horizon-resources.json", b'{"schema":7,"minecraft":"26.3"}')
    nz.writestr("facts.json", b"{}")
    nz.writestr("items.json", b"{}")
    nz.writestr("registries.json", b"{}")
    nz.writestr("commands.json", b"{}")
    nz.writestr("model-states.json", b"{}")
    nz.writestr("cached-collision.nbt", b"nbt")
    nz.writestr("worldgen/block_flags.json", b"[]")
    if extra_entry:
        nz.writestr(extra_entry, b"bad")
with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", compression=zipfile.ZIP_STORED) as zout:
    for item in zin.infolist():
        zout.writestr(item.filename, zin.read(item.filename))
    zout.writestr(asset_path, inner.getvalue())
PY
}

K4_BUNDLE_DOC="$TMP_ROOT/ENGINE_DATA_BUNDLE.md"
printf '# test placeholder doc\n' > "$K4_BUNDLE_DOC"

K4_OK_APK="$TMP_ROOT/k4_ok.apk"
make_k4_apk "$K4_OK_APK" "assets/engine-data.zip" ""
expect_pass "K4 Check 11 accepts valid allowlisted assets/engine-data.zip when BUNDLE_ENGINE_DATA=true" \
    env BUNDLE_ENGINE_DATA=true GUARD_ENGINE_DATA_DOC_PATH="$K4_BUNDLE_DOC" bash scripts/guard.sh --check-apk "$K4_OK_APK"

# K4 negative 1: extra non-allowlisted file inside engine-data.zip
K4_BAD_EXTRA="$TMP_ROOT/k4_bad_extra.apk"
make_k4_apk "$K4_BAD_EXTRA" "assets/engine-data.zip" "extra-unknown-table.json"
expect_fail "K4 Check 11 (1) rejects extra file inside assets/engine-data.zip" "Disallowed entry inside assets/engine-data.zip: extra-unknown-table.json" \
    env BUNDLE_ENGINE_DATA=true GUARD_ENGINE_DATA_DOC_PATH="$K4_BUNDLE_DOC" bash scripts/guard.sh --check-apk "$K4_BAD_EXTRA"

# K4 negative 2: .jar inside engine-data.zip
K4_BAD_JAR="$TMP_ROOT/k4_bad_jar.apk"
make_k4_apk "$K4_BAD_JAR" "assets/engine-data.zip" "client.jar"
expect_fail "K4 Check 11 (2) rejects .jar inside assets/engine-data.zip" "Disallowed entry inside assets/engine-data.zip: client.jar" \
    env BUNDLE_ENGINE_DATA=true GUARD_ENGINE_DATA_DOC_PATH="$K4_BUNDLE_DOC" bash scripts/guard.sh --check-apk "$K4_BAD_JAR"

# K4 negative 3: texture/sound (.png / .ogg) inside engine-data.zip
K4_BAD_PNG="$TMP_ROOT/k4_bad_png.apk"
make_k4_apk "$K4_BAD_PNG" "assets/engine-data.zip" "assets/minecraft/textures/block/stone.png"
expect_fail "K4 Check 11 (3) rejects texture/sound inside assets/engine-data.zip" "Disallowed entry inside assets/engine-data.zip" \
    env BUNDLE_ENGINE_DATA=true GUARD_ENGINE_DATA_DOC_PATH="$K4_BUNDLE_DOC" bash scripts/guard.sh --check-apk "$K4_BAD_PNG"

# K4 negative 4: renamed or relocated asset tree
K4_BAD_RENAMED="$TMP_ROOT/k4_bad_renamed.apk"
make_k4_apk "$K4_BAD_RENAMED" "assets/other/engine-data.zip" ""
expect_fail "K4 Check 11 (4) rejects relocated assets/other/engine-data.zip" "disallowed asset entries" \
    env BUNDLE_ENGINE_DATA=true GUARD_ENGINE_DATA_DOC_PATH="$K4_BUNDLE_DOC" bash scripts/guard.sh --check-apk "$K4_BAD_RENAMED"

# K4 negative 5: BUNDLE_ENGINE_DATA off with assets/engine-data.zip present
expect_fail "K4 Check 11 (5) rejects assets/engine-data.zip when BUNDLE_ENGINE_DATA != true" "BUNDLE_ENGINE_DATA != 'true'" \
    env BUNDLE_ENGINE_DATA=false GUARD_ENGINE_DATA_DOC_PATH="$K4_BUNDLE_DOC" bash scripts/guard.sh --check-apk "$K4_OK_APK"

# K4 negative 6: git-tracked engine-data.zip committed in repo
K4_BAD_GIT="$TMP_ROOT/k4_bad_git_repo"
mkdir -p "$K4_BAD_GIT/subdir"
git -C "$K4_BAD_GIT" init -q
touch "$K4_BAD_GIT/subdir/engine-data.zip"
git -C "$K4_BAD_GIT" add subdir/engine-data.zip
expect_fail "K4 Check 11 (6) rejects git-tracked engine-data.zip" "forbidden git-tracked engine-data zip" \
    env GUARD_CHECK11_ONLY_DIR="$K4_BAD_GIT" bash scripts/guard.sh

echo ""
echo "=== 8. Check 12 Self-Tests (0007 local pin refresh patch scope guard) ==="

if [ -f "engine/patches/0007-pin-refresh-26.3.patch" ]; then
    expect_pass "Check 12 accepts real engine/patches/0007-pin-refresh-26.3.patch" \
        env GUARD_CHECK12_ONLY_PATCH="engine/patches/0007-pin-refresh-26.3.patch" bash scripts/guard.sh
fi

BAD_PATCH_CLIENT="$TMP_ROOT/bad_0007_client_sha1.patch"
cat > "$BAD_PATCH_CLIENT" <<'PATCH'
diff --git a/research-lock.json b/research-lock.json
--- a/research-lock.json
+++ b/research-lock.json
@@ -17,7 +17,7 @@
     ],
     "accepted_asset_index_note": "The asset indexes (id 34) of those republished version JSONs: a launcher keeps one 34.json, the last it downloaded, whichever version JSON it reads.",
-    "client_sha1": "e877b6a07acd633fb3bb475002175cec036e7b87",
+    "client_sha1": "0000000000000000000000000000000000000000",
     "server_sha1": "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c",
PATCH
expect_fail "Check 12 rejects patch modifying client_sha1" "forbidden|non-hash|strict field" \
    env GUARD_CHECK12_ONLY_PATCH="$BAD_PATCH_CLIENT" bash scripts/guard.sh

BAD_PATCH_FIELD="$TMP_ROOT/bad_0007_unrelated_field.patch"
cat > "$BAD_PATCH_FIELD" <<'PATCH'
diff --git a/research-lock.json b/research-lock.json
--- a/research-lock.json
+++ b/research-lock.json
@@ -19,5 +19,6 @@
     "accepted_asset_index_note": "The asset indexes (id 34) of those republished version JSONs: a launcher keeps one 34.json, the last it downloaded, whichever version JSON it reads.",
     "client_sha1": "e877b6a07acd633fb3bb475002175cec036e7b87",
     "server_sha1": "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c",
+    "unrelated_field": "bypass",
     "java_major": 25
PATCH
expect_fail "Check 12 rejects patch adding an unrelated field" "unrelated field|non-hash|added/removed fields" \
    env GUARD_CHECK12_ONLY_PATCH="$BAD_PATCH_FIELD" bash scripts/guard.sh

BAD_PATCH_OTHER_FILE="$TMP_ROOT/bad_0007_other_file.patch"
cat > "$BAD_PATCH_OTHER_FILE" <<'PATCH'
diff --git a/crates/horizon-client/src/catalog.rs b/crates/horizon-client/src/catalog.rs
--- a/crates/horizon-client/src/catalog.rs
+++ b/crates/horizon-client/src/catalog.rs
@@ -1,1 +1,2 @@
+// "9f67e10ed013deeaad20ac3c800652765d493408"
PATCH
expect_fail "Check 12 rejects patch touching files other than research-lock.json" "forbidden paths outside research-lock.json" \
    env GUARD_CHECK12_ONLY_PATCH="$BAD_PATCH_OTHER_FILE" bash scripts/guard.sh

echo ""
echo "=== 9. Zero/NUL Integrity Self-Tests (check_no_zero_files.sh) ==="

GOOD_ZERO_DIR="$TMP_ROOT/good_zero_dir"
mkdir -p "$GOOD_ZERO_DIR"
printf 'ok\n' > "$GOOD_ZERO_DIR/valid.rs"
expect_pass "Zero/NUL check accepts valid non-empty UTF-8 file" \
    env GUARD_CHECK_ZERO_ONLY_DIR="$GOOD_ZERO_DIR" bash scripts/guard.sh

BAD_NUL_DIR="$TMP_ROOT/bad_nul_dir"
mkdir -p "$BAD_NUL_DIR"
dd if=/dev/zero bs=128 count=1 2>/dev/null > "$BAD_NUL_DIR/prepare_tests.rs"
expect_fail "Zero/NUL check rejects NUL-filled text file" "Zero-filled or empty file detected" \
    env GUARD_CHECK_ZERO_ONLY_DIR="$BAD_NUL_DIR" bash scripts/guard.sh

BAD_MIXED_NUL_DIR="$TMP_ROOT/bad_mixed_nul_dir"
mkdir -p "$BAD_MIXED_NUL_DIR"
printf 'abc\0def\n' > "$BAD_MIXED_NUL_DIR/mixed.kt"
expect_fail "Zero/NUL check rejects text file containing embedded NUL bytes" "NUL bytes detected in text file" \
    env GUARD_CHECK_ZERO_ONLY_DIR="$BAD_MIXED_NUL_DIR" bash scripts/guard.sh

echo ""
echo "=== 10. Real-Data Preflight Workflow Hygiene Self-Tests (P4 / check_preflight_workflow.sh) ==="

expect_pass "Preflight hygiene check accepts repo .github/workflows/preflight-real.yml" \
    bash scripts/check_preflight_workflow.sh .github/workflows/preflight-real.yml

BAD_PREFLIGHT_PUSH="$TMP_ROOT/preflight-push.yml"
sed 's/workflow_dispatch:/push:\n  workflow_dispatch:/' .github/workflows/preflight-real.yml > "$BAD_PREFLIGHT_PUSH"
expect_fail "Preflight hygiene check rejects push trigger" "Forbidden trigger 'push'" \
    bash scripts/check_preflight_workflow.sh "$BAD_PREFLIGHT_PUSH"

BAD_PREFLIGHT_PR="$TMP_ROOT/preflight-pr.yml"
sed 's/workflow_dispatch:/pull_request:\n  workflow_dispatch:/' .github/workflows/preflight-real.yml > "$BAD_PREFLIGHT_PR"
expect_fail "Preflight hygiene check rejects pull_request trigger" "Forbidden trigger 'pull_request'" \
    bash scripts/check_preflight_workflow.sh "$BAD_PREFLIGHT_PR"

BAD_PREFLIGHT_UPLOAD="$TMP_ROOT/preflight-upload.yml"
python3 - .github/workflows/preflight-real.yml "$BAD_PREFLIGHT_UPLOAD" <<'PY'
import sys
s = open(sys.argv[1]).read()
s = s.replace("preflight-${{ matrix.arch }}.log", "engine-data.zip")
open(sys.argv[2], "w").write(s)
PY
expect_fail "Preflight hygiene check rejects uploading engine-data.zip" "Forbidden Mojang/engine artifact path" \
    bash scripts/check_preflight_workflow.sh "$BAD_PREFLIGHT_UPLOAD"

BAD_PREFLIGHT_CACHE="$TMP_ROOT/preflight-cache.yml"
python3 - .github/workflows/preflight-real.yml "$BAD_PREFLIGHT_CACHE" <<'PY'
import sys
s = open(sys.argv[1]).read()
s = s.replace("actions/setup-java@v4", "actions/cache@v4")
open(sys.argv[2], "w").write(s)
PY
expect_fail "Preflight hygiene check rejects actions/cache in preflight-real.yml" "Forbidden 'actions/cache'" \
    bash scripts/check_preflight_workflow.sh "$BAD_PREFLIGHT_CACHE"

echo ""
echo "=== 11. K8 Nightly/Release/CI Bundle Isolation Self-Tests ==="

expect_pass "Check 14 accepts repo .github/workflows" \
    env GUARD_CHECK14_ONLY_DIR=".github/workflows" bash scripts/guard.sh

K8_WF_NIGHTLY_VAR="$TMP_ROOT/k8_wf_nightly_var"
cp -a ".github/workflows" "$K8_WF_NIGHTLY_VAR"
printf '\n# %s\n' "BUNDLE_ENGINE_DATA" >> "$K8_WF_NIGHTLY_VAR/nightly.yml"
expect_fail "Check 14 rejects nightly.yml referencing BUNDLE_ENGINE_DATA" "nightly/release workflow must never reference" \
    env GUARD_CHECK14_ONLY_DIR="$K8_WF_NIGHTLY_VAR" bash scripts/guard.sh

K8_WF_RELEASE_VAR="$TMP_ROOT/k8_wf_release_var"
cp -a ".github/workflows" "$K8_WF_RELEASE_VAR"
printf '\n      env:\n        FLAG: ${{ vars.BUNDLE_ENGINE_DATA }}\n' >> "$K8_WF_RELEASE_VAR/release.yml"
expect_fail "Check 14 rejects release.yml referencing BUNDLE_ENGINE_DATA" "nightly/release workflow must never reference" \
    env GUARD_CHECK14_ONLY_DIR="$K8_WF_RELEASE_VAR" bash scripts/guard.sh

K8_WF_NIGHTLY_MOJANG="$TMP_ROOT/k8_wf_nightly_mojang"
cp -a ".github/workflows" "$K8_WF_NIGHTLY_MOJANG"
printf '\n# curl https://piston-meta.mojang.com/mc/game/version_manifest_v2.json\n' >> "$K8_WF_NIGHTLY_MOJANG/nightly.yml"
expect_fail "Check 14 rejects nightly.yml referencing Mojang download host" "nightly/release workflow must never reference" \
    env GUARD_CHECK14_ONLY_DIR="$K8_WF_NIGHTLY_MOJANG" bash scripts/guard.sh

K8_WF_RELEASE_MOJANG="$TMP_ROOT/k8_wf_release_mojang"
cp -a ".github/workflows" "$K8_WF_RELEASE_MOJANG"
printf '\n# https://resources.download.minecraft.net/00/0000\n' >> "$K8_WF_RELEASE_MOJANG/release.yml"
expect_fail "Check 14 rejects release.yml referencing Mojang resources host" "nightly/release workflow must never reference" \
    env GUARD_CHECK14_ONLY_DIR="$K8_WF_RELEASE_MOJANG" bash scripts/guard.sh

K8_WF_NO_UPLOAD_GUARD="$TMP_ROOT/k8_wf_no_upload_guard"
cp -a ".github/workflows" "$K8_WF_NO_UPLOAD_GUARD"
python3 - "$K8_WF_NO_UPLOAD_GUARD/release.yml" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read().replace("--check-release-upload", "--check-release-apk")
open(p, "w").write(s)
PY
expect_fail "Check 14 rejects release upload workflow missing --check-release-upload" "must run scripts/guard.sh --check-release-upload" \
    env GUARD_CHECK14_ONLY_DIR="$K8_WF_NO_UPLOAD_GUARD" bash scripts/guard.sh

K8_WF_NO_RETENTION="$TMP_ROOT/k8_wf_no_retention"
cp -a ".github/workflows" "$K8_WF_NO_RETENTION"
python3 - "$K8_WF_NO_RETENTION/ci.yml" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read().replace("retention-days: 3", "retention-days: 30")
open(p, "w").write(s)
PY
expect_fail "Check 14 rejects ci.yml signed-apks without retention-days: 3" "must specify retention-days: 3" \
    env GUARD_CHECK14_ONLY_DIR="$K8_WF_NO_RETENTION" bash scripts/guard.sh

expect_fail "Check 14 --check-release-upload rejects APK built with assets/engine-data.zip even if BUNDLE_ENGINE_DATA=true" "GitHub Release upload forbids APK built with assets/engine-data.zip" \
    env BUNDLE_ENGINE_DATA=true bash scripts/guard.sh --check-release-upload "$K4_OK_APK"

expect_pass "Check 14 --check-release-upload accepts clean release APK without bundle even if BUNDLE_ENGINE_DATA=true" \
    env BUNDLE_ENGINE_DATA=true bash scripts/guard.sh --check-release-upload "$REL_FALSE_APK"

echo ""
echo "=== 12. Check 15 Embedded Engine .so Self-Tests (A0 KEEP-list + A3 stripped UI) ==="

make_embedded_so() {
    local out="$1"
    local extra="$2"
    local omit="${3:-}"
    python3 - "$out" "$extra" "$omit" <<'PY'
import sys
out, extra, omit = sys.argv[1], sys.argv[2], sys.argv[3]
keep = [
    "Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeInit",
    "Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSetSession",
    "Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSetDirectLaunch",
    "Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSignalEngineExit",
    "Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeConfigureEmbedded",
    "BlazeWgpu:",
    "BlazePerf pipelines_created_ms=",
    "BlazePerf surface_to_first_frame_ms=",
    "Not affiliated with Mojang or Microsoft",
    "APerformanceHint_createSession",
    "ANativeWindow_setFrameRate",
    "android_main",
    "BlazeDirectLaunch: entered 26.3 game directly (blaze-embedded)",
]
lines = [k for k in keep if k != omit]
if extra:
    lines.append(extra)
open(out, "wb").write(b"\x7fELF" + ("\x00".join(lines)).encode("ascii") + b"\x00")
PY
}

GOOD_EMBEDDED_SO="$TMP_ROOT/good_embedded.so"
make_embedded_so "$GOOD_EMBEDDED_SO" ""
expect_pass "Check 15 accepts clean embedded .so with all KEEP markers" \
    bash scripts/guard.sh --check-embedded-so "$GOOD_EMBEDDED_SO"

BAD_SO_HUB="$TMP_ROOT/bad_hub.so"
make_embedded_so "$BAD_SO_HUB" "Horizon Control Hub"
expect_fail "Check 15 rejects Control Hub string in embedded .so" "forbidden Horizon UI/network marker" \
    bash scripts/guard.sh --check-embedded-so "$BAD_SO_HUB"

BAD_SO_LAUNCHER="$TMP_ROOT/bad_launcher.so"
make_embedded_so "$BAD_SO_LAUNCHER" "Horizon Android"
expect_fail "Check 15 rejects Horizon Android launcher title in embedded .so" "forbidden Horizon UI/network marker" \
    bash scripts/guard.sh --check-embedded-so "$BAD_SO_LAUNCHER"

BAD_SO_TELEMETRY="$TMP_ROOT/bad_telemetry.so"
make_embedded_so "$BAD_SO_TELEMETRY" "https://telemetry.playhorizon.dev"
expect_fail "Check 15 rejects telemetry endpoint in embedded .so" "forbidden Horizon UI/network marker" \
    bash scripts/guard.sh --check-embedded-so "$BAD_SO_TELEMETRY"

BAD_SO_MISSING_KEEP="$TMP_ROOT/bad_missing_keep.so"
make_embedded_so "$BAD_SO_MISSING_KEEP" "" "BlazePerf pipelines_created_ms="
expect_fail "Check 15 rejects embedded .so missing KEEP-list perf marker" "missing mandatory A0 KEEP-list" \
    bash scripts/guard.sh --check-embedded-so "$BAD_SO_MISSING_KEEP"

BAD_SO_MISSING_NOTICE="$TMP_ROOT/bad_missing_notice.so"
make_embedded_so "$BAD_SO_MISSING_NOTICE" "" "Not affiliated with Mojang or Microsoft"
expect_fail "Check 15 rejects embedded .so missing Mojang non-affiliation disclaimer" "missing mandatory A0 KEEP-list" \
    bash scripts/guard.sh --check-embedded-so "$BAD_SO_MISSING_NOTICE"

echo ""
echo "=== Summary: PASS=$PASS_COUNT FAIL=$FAIL_COUNT ==="
if [ "$FAIL_COUNT" -ne 0 ]; then
    exit 1
fi
