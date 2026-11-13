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
echo "=== Summary: PASS=$PASS_COUNT FAIL=$FAIL_COUNT ==="
if [ "$FAIL_COUNT" -ne 0 ]; then
    exit 1
fi
