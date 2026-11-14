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
echo "=== Summary: PASS=$PASS_COUNT FAIL=$FAIL_COUNT ==="
if [ "$FAIL_COUNT" -ne 0 ]; then
    exit 1
fi
