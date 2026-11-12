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

echo "=== All Guard Checks Passed ==="
