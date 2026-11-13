#!/usr/bin/env bash
set -euo pipefail

echo "=== Running Guard Checks ==="

# 1. Keystore and password file leak check
echo "Checking for committed keystores or passwords..."
LEAKED_FILES=$(git ls-files | grep -E '\.(jks|keystore)$|passwords' || true)
if [ -n "$LEAKED_FILES" ]; then
    echo "ERROR: Found tracked keystore or password files in repository:"
    echo "$LEAKED_FILES"
    exit 1
fi
echo "✓ No keystores or password files tracked in git."

# 2. Hard-coded secrets pattern check
echo "Checking for hard-coded secrets patterns..."
SECRET_PATTERNS='BEGIN (RSA|OPENSSH|EC|DSA|PGP)? PRIVATE KEY|ghp_[A-Za-z0-9_]{36}|github_pat_[A-Za-z0-9_]{82}'
if git grep -E -I -n "$SECRET_PATTERNS" -- ':!scripts/guard.sh' ':!scripts/test_guards.sh'; then
    echo "ERROR: Hard-coded secrets pattern matched in tracked files!"
    exit 1
fi
echo "✓ No hard-coded secrets patterns found."

# 3. LICENSE-present check
echo "Checking LICENSE presence..."
if [ ! -f "LICENSE" ]; then
    echo "ERROR: LICENSE file is missing!"
    exit 1
fi
if ! grep -q "GNU GENERAL PUBLIC LICENSE" LICENSE || ! grep -q "Version 3" LICENSE; then
    echo "ERROR: LICENSE is not GPL-3.0!"
    exit 1
fi
echo "✓ GPL-3.0 LICENSE is present."

# 4. applicationId check: must not be upstream
echo "Checking applicationId..."
if git grep -n 'applicationId = "com.movtery.zalithlauncher"' ZalithLauncher/build.gradle.kts; then
    echo "ERROR: applicationId is still set to upstream com.movtery.zalithlauncher!"
    exit 1
fi
if ! git grep -q 'applicationId = "io.github.kaito65452.blazelauncher"' ZalithLauncher/build.gradle.kts; then
    echo "ERROR: applicationId is not io.github.kaito65452.blazelauncher!"
    exit 1
fi
echo "✓ applicationId is correctly set to io.github.kaito65452.blazelauncher."

# 5. Workflow versionCode run_number check
echo "Checking workflows for run_number in versionCode..."
if git grep -E -i 'github\.run_number|GITHUB_RUN_NUMBER' -- .github/workflows/; then
    echo "ERROR: Workflow passes run_number as versionCode or references GITHUB_RUN_NUMBER!"
    exit 1
fi
echo "✓ Workflows do not reference run_number for versionCode."

# 5b. Engine submodule presence check (must fail, not skip, when engine build enabled or .gitmodules declares it)
ENGINE_REQUIRED="${ENGINE_BUILD_ENABLED:-0}"
if [ -f ".gitmodules" ] && grep -q 'path = engine/starling' .gitmodules; then
    ENGINE_REQUIRED=1
fi
if [ "$ENGINE_REQUIRED" = "1" ]; then
    echo "Checking engine/starling submodule presence..."
    if [ ! -d "engine/starling" ] || [ -z "$(ls -A engine/starling 2>/dev/null || true)" ] || [ ! -s "engine/starling/Cargo.toml" ]; then
        echo "ERROR: Engine build is enabled / declared in .gitmodules, but engine/starling is missing or empty!"
        echo "Hint: git submodule update --init --recursive"
        exit 1
    fi
    echo "✓ engine/starling submodule is present and non-empty."
fi

# 6. License hygiene: forbid Mojang wallpapers/skins and unverified voicechat icons in repo (and submodule if present)
echo "Checking for disallowed Mojang assets, wallpapers, skins, or voicechat icons..."
DISALLOWED_PATTERN='(^|/)(wallpapers|wallpaper_templates)/|(^|/)packaging/android/assets/skin/|(^|/)voicechat-engine/|(^|/)engine/.*(steve|alex)\.png$|^ZalithLauncher/src/main/assets/(skin/|alex\.png$)|(^|/)ZalithLauncher/.*/logos/(neoforge|quilt)\.png$'
DISALLOWED_REPO_FILES=$( { git ls-files; if [ -d "engine" ]; then find engine -type f -not -path '*/.git/*' -not -path '*/target/*'; fi; } | grep -iE "$DISALLOWED_PATTERN" || true )
if [ -n "$DISALLOWED_REPO_FILES" ]; then
    echo "ERROR: Found disallowed asset/crate paths in repository:"
    echo "$DISALLOWED_REPO_FILES"
    exit 1
fi
echo "✓ No disallowed wallpapers, skins, or voicechat icon assets tracked in git."

# 7. License hygiene: forbid include_bytes!/include_str! font embedding under engine/ (single-line, multi-line, concat!/env!, or fonts/ paths)
check_engine_font_embeds() {
    local target_dir="$1"
    python3 - "$target_dir" <<'PY'
import os
import re
import sys

root = sys.argv[1]
if not os.path.isdir(root):
    sys.exit(0)

def strip_rust_comments(src: str) -> str:
    out = []
    i = 0
    n = len(src)
    in_str = False
    block_depth = 0
    while i < n:
        ch = src[i]
        nxt = src[i:i+2]
        if block_depth > 0:
            if nxt == "/*":
                block_depth += 1
                i += 2
            elif nxt == "*/":
                block_depth -= 1
                i += 2
                out.append(" ")
            elif ch == "\n":
                out.append("\n")
                i += 1
            else:
                i += 1
            continue
        if in_str:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(src[i + 1])
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if nxt == "//":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if nxt == "/*":
            block_depth = 1
            i += 2
            continue
        if ch == '"':
            in_str = True
        out.append(ch)
        i += 1
    return "".join(out)

macro_re = re.compile(r"\binclude_(?:bytes|str)\s*!\s*\(")
str_lit_re = re.compile(r'"((?:\\.|[^"\\])*)"')
env_call_re = re.compile(r'\b(?:option_)?env\s*!\s*\(\s*"[^"]*"\s*\)')

violations = []

for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in (".git", "target", ".local")]
    for fname in sorted(filenames):
        if not fname.endswith(".rs"):
            continue
        fpath = os.path.join(dirpath, fname)
        try:
            with open(fpath, "r", encoding="utf-8", errors="replace") as f:
                raw = f.read()
        except OSError:
            continue
        cleaned = strip_rust_comments(raw)
        for m in macro_re.finditer(cleaned):
            depth = 0
            end = len(cleaned)
            in_s = False
            i = m.end() - 1
            while i < len(cleaned):
                c = cleaned[i]
                if in_s:
                    if c == "\\":
                        i += 2
                        continue
                    if c == '"':
                        in_s = False
                else:
                    if c == '"':
                        in_s = True
                    elif c == "(":
                        depth += 1
                    elif c == ")":
                        depth -= 1
                        if depth == 0:
                            end = i + 1
                            break
                i += 1
            span = cleaned[m.start():end]
            # Replace env!("VAR") so var names aren't confused with path segments
            span_no_env = env_call_re.sub('"<ENV>"', span)
            parts = str_lit_re.findall(span_no_env)
            resolved = "".join(parts)
            line_no = cleaned[:m.start()].count("\n") + 1
            if re.search(r"\.(ttf|otf)\b", resolved, re.IGNORECASE) or re.search(r"(^|[\\/])fonts([\\/]|$)", resolved, re.IGNORECASE):
                compact = " ".join(span.split())
                violations.append(f"{fpath}:{line_no}: resolved={resolved!r} macro={compact}")

if violations:
    print("ERROR: Found forbidden font/fonts/ compile-time include_bytes!/include_str!:", file=sys.stderr)
    for v in violations:
        print(f"  {v}", file=sys.stderr)
    sys.exit(1)
PY
}

if [ "${GUARD_CHECK7_ONLY_DIR:-}" != "" ]; then
    echo "Checking custom directory for include_bytes!/include_str! font embedding: $GUARD_CHECK7_ONLY_DIR"
    check_engine_font_embeds "$GUARD_CHECK7_ONLY_DIR"
    echo "✓ Custom Check 7 directory passed."
    exit 0
fi

if [ -d "engine" ]; then
    echo "Checking engine/ for include_bytes!/include_str! font embedding..."
    TEMP_PATCHED_WORKTREE=""
    cleanup_wt() {
        if [ -n "$TEMP_PATCHED_WORKTREE" ] && [ -d "$TEMP_PATCHED_WORKTREE" ]; then
            git -C engine/starling worktree remove --force "$TEMP_PATCHED_WORKTREE" >/dev/null 2>&1 || rm -rf "$TEMP_PATCHED_WORKTREE"
        fi
    }
    trap cleanup_wt EXIT

    # First check any non-submodule Rust files directly under engine/ (excluding engine/starling if clean+patched below)
    if [ -d "engine/starling/.git" ] || [ -f "engine/starling/.git" ]; then
        if [ -z "$(git -C engine/starling status --porcelain)" ] && ls engine/patches/*.patch >/dev/null 2>&1; then
            # Submodule working tree is clean upstream pin; verify patches apply cleanly and scan the patched view
            TEMP_PATCHED_WORKTREE=$(mktemp -d)
            git -C engine/starling worktree add --no-checkout --detach "$TEMP_PATCHED_WORKTREE" HEAD >/dev/null 2>&1
            # Check out only Rust sources and patch-target files to avoid copying thousands of unrelated assets
            {
                git -C "$TEMP_PATCHED_WORKTREE" ls-files '*.rs'
                grep -h '^--- a/' engine/patches/*.patch | sed 's|^--- a/||'
            } | sort -u | xargs -r git -C "$TEMP_PATCHED_WORKTREE" checkout HEAD --
            for p in engine/patches/*.patch; do
                p_abs="$(pwd)/$p"
                git -C "$TEMP_PATCHED_WORKTREE" apply --check "$p_abs"
                git -C "$TEMP_PATCHED_WORKTREE" apply "$p_abs"
            done
            check_engine_font_embeds "$TEMP_PATCHED_WORKTREE"
            # Also check any non-starling Rust files under engine/
            for item in engine/*; do
                if [ "$item" != "engine/starling" ] && [ -d "$item" ]; then
                    check_engine_font_embeds "$item"
                fi
            done
        else
            # Submodule already patched in-tree (CI build state) or no patches: scan engine/ directly
            check_engine_font_embeds "engine"
        fi
    else
        check_engine_font_embeds "engine"
    fi
    echo "✓ No forbidden include_bytes!/include_str! font embedding found under engine/."
fi

# 8. Optional APK inspection (--check-apk <apk_path>)
if [ "${1:-}" = "--check-apk" ]; then
    APK_PATH="${2:-}"
    if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
        echo "ERROR: --check-apk requires a valid APK file path (got: '$APK_PATH')"
        exit 1
    fi
    echo "Checking APK license hygiene: $APK_PATH"
    python3 - "$APK_PATH" <<'PY'
import re
import sys
import zipfile

apk_path = sys.argv[1]
disallowed_re = re.compile(
    r"(^|/)(wallpapers|wallpaper_templates)/|(^|/)assets/skin/|(^|/)assets/alex\.png$|voicechat",
    re.IGNORECASE,
)
marker_strings = [
    "SIL Open Font License",
    "The Geist Project Authors",
    "The Pixelify Sans Project Authors",
    "The Bricolage Grotesque Project Authors",
]
ofl_markers = []
for s in marker_strings:
    ofl_markers.append(("ASCII", s, s.encode("ascii")))
    ofl_markers.append(("UTF-16BE", s, s.encode("utf-16-be")))

REQUIRED_SFNT_TABLES = {b"cmap", b"head", b"name"}
SHAPE_SFNT_TABLES = {b"glyf", b"CFF ", b"CFF2"}

def find_embedded_sfnt(data: bytes):
    for magic in (b"\x00\x01\x00\x00", b"OTTO"):
        start = 0
        while True:
            pos = data.find(magic, start)
            if pos == -1:
                break
            start = pos + 1
            if pos + 12 > len(data):
                continue
            num_tables = int.from_bytes(data[pos + 4 : pos + 6], "big")
            if not (4 <= num_tables <= 64):
                continue
            dir_end = pos + 12 + 16 * num_tables
            if dir_end > len(data):
                continue
            tags = {
                data[pos + 12 + i * 16 : pos + 16 + i * 16]
                for i in range(num_tables)
            }
            if REQUIRED_SFNT_TABLES.issubset(tags) and (tags & SHAPE_SFNT_TABLES):
                found_names = sorted(t.decode("ascii", errors="replace") for t in (tags & (REQUIRED_SFNT_TABLES | SHAPE_SFNT_TABLES)))
                return f"sfnt magic {magic!r} at offset {pos} with tables {found_names}"
    return None

with zipfile.ZipFile(apk_path, "r") as zf:
    names = zf.namelist()
    if "assets/THIRD_PARTY_NOTICES.md" not in names:
        print("ERROR: APK is missing assets/THIRD_PARTY_NOTICES.md!", file=sys.stderr)
        sys.exit(1)

    bad = [n for n in names if disallowed_re.search(n)]
    if bad:
        print("ERROR: APK contains disallowed asset entries:", file=sys.stderr)
        for item in bad:
            print(f"  - {item}", file=sys.stderr)
        sys.exit(1)

    font_files = [n for n in names if re.match(r"^assets/fonts/.*\.(ttf|otf)$", n, re.IGNORECASE)]
    if font_files:
        ofl_notices = [n for n in names if re.match(r"^assets/fonts/OFL-.*\.txt$", n)]
        if not ofl_notices:
            print("ERROR: APK bundles assets/fonts/*.ttf|.otf without assets/fonts/OFL-*.txt!", file=sys.stderr)
            sys.exit(1)

    so_entries = [n for n in names if re.match(r"^lib/[^/]+/.*\.so$", n)]
    print(f"Scanning {len(so_entries)} shared libraries under lib/*/*.so...")
    for n in so_entries:
        data = zf.read(n)
        for enc, text_marker, raw_marker in ofl_markers:
            if raw_marker in data:
                print(
                    f"ERROR: {n} contains embedded {enc} font marker {text_marker!r}!",
                    file=sys.stderr,
                )
                sys.exit(1)
        sfnt_hit = find_embedded_sfnt(data)
        if sfnt_hit:
            print(
                f"ERROR: {n} contains embedded TrueType/OpenType font data ({sfnt_hit})!",
                file=sys.stderr,
            )
            sys.exit(1)

print(f"✓ APK license hygiene check passed ({len(so_entries)} .so files scanned).")
PY
fi

echo "=== All Guard Checks Passed ==="
