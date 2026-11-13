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
if git grep -E -I -n "$SECRET_PATTERNS" -- ':!scripts/guard.sh'; then
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

# 7. License hygiene: forbid include_bytes! font embedding under engine/
if [ -d "engine" ]; then
    echo "Checking engine/ for include_bytes! font embedding..."
    FONT_EMBEDS=$(grep -rnE --exclude-dir=.git --exclude-dir=target 'include_bytes!\s*\([^)]*\.(ttf|otf)' engine/ || true)
    if [ -n "$FONT_EMBEDS" ]; then
        echo "ERROR: Found compile-time font embedding (include_bytes!) under engine/:"
        echo "$FONT_EMBEDS"
        exit 1
    fi
    echo "✓ No include_bytes! font embedding found under engine/."
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
ofl_markers = [
    b"SIL Open Font License",
    b"The Geist Project Authors",
    b"The Pixelify Sans Project Authors",
    b"The Bricolage Grotesque Project Authors",
]

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

    for n in names:
        if n.endswith("/libhorizon_android.so"):
            data = zf.read(n)
            for marker in ofl_markers:
                if marker in data:
                    print(
                        f"ERROR: {n} contains embedded font marker {marker!r}!",
                        file=sys.stderr,
                    )
                    sys.exit(1)

print("✓ APK license hygiene check passed.")
PY
fi

echo "=== All Guard Checks Passed ==="
