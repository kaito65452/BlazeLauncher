#!/usr/bin/env bash
set -euo pipefail

echo "=== Running Scope Guard Checks ==="

BASE_BRANCH="${1:-main}"

# Determine modified files between current branch and base branch
if git rev-parse --verify "origin/$BASE_BRANCH" >/dev/null 2>&1; then
    BASE_REF="origin/$BASE_BRANCH"
elif git rev-parse --verify "$BASE_BRANCH" >/dev/null 2>&1; then
    BASE_REF="$BASE_BRANCH"
else
    echo "Warning: Could not resolve base branch '$BASE_BRANCH', checking against HEAD~1 if exists"
    BASE_REF="HEAD~1"
fi

echo "Comparing HEAD against $BASE_REF..."

CHANGED_FILES=$(git diff --name-only "$BASE_REF"...HEAD || git diff --name-only HEAD)

NAVIGATION_FILE="ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/screens/main/MainScreen.kt"
NAV_MODIFIED=0

for file in $CHANGED_FILES; do
    # Check against allowlist patterns:
    case "$file" in
        CLAUDE.md|GEMINI.md|AGENTS.md)
            ;;
        .github/*)
            ;;
        scripts/*)
            ;;
        docs/*)
            ;;
        engine/*|.gitmodules)
            ;;
        build.gradle.kts|settings.gradle.kts|gradle.properties|.gitignore)
            ;;
        ZalithLauncher/build.gradle.kts|ZalithLauncher/gradle.properties)
            ;;
        ZalithLauncher/src/main/AndroidManifest.xml)
            ;;
        ZalithLauncher/src/main/res/values*/strings.xml)
            ;;
        ZalithLauncher/src/main/assets/THIRD_PARTY_NOTICES.md|ZalithLauncher/src/main/assets/fonts/*)
            ;;
        ZalithLauncher/src/main/java/dev/horizon/android/bridge/BlazeNativeBridge.kt)
            ;;
        ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/*)
            ;;
        ZalithLauncher/src/test/java/io/github/kaito65452/blazelauncher/*)
            ;;
        ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/account/microsoft/MicrosoftAuthenticator.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/account/AccountUtils.kt)
            # Microsoft login error logging and diagnostic enhancement
            ;;
        ZalithLauncher/src/main/java/com/movtery/zalithlauncher/setting/AllSettings.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/screens/content/settings/AboutInfoScreen.kt|ZalithLauncher/src/main/java/com/movtery/zalithlauncher/ui/screens/content/settings/LauncherSettingsScreen.kt)
            # Developer Mode persistence, 5-tap version unlock, and diagnostics-only Settings section
            ;;
        ZalithLauncher/zalith_launcher.jks|ZalithLauncher/zalith_launcher_debug.jks)
            # Removal of upstream keystores
            ;;
        "$NAVIGATION_FILE")
            NAV_MODIFIED=1
            ;;
        *)
            echo "ERROR: File '$file' is not in the scope guard allowlist!"
            exit 1
            ;;
    esac
done

if [ "$NAV_MODIFIED" -eq 1 ]; then
    # Count changed lines in the navigation registration file
    DIFF_STAT=$(git diff -U0 "$BASE_REF"...HEAD -- "$NAVIGATION_FILE" || git diff -U0 HEAD -- "$NAVIGATION_FILE")
    ADDED_LINES=$(echo "$DIFF_STAT" | grep -c '^+[^+]' || true)
    REMOVED_LINES=$(echo "$DIFF_STAT" | grep -c '^-[^-]' || true)
    TOTAL_CHANGED=$((ADDED_LINES + REMOVED_LINES))
    echo "Navigation registration file changed lines: $TOTAL_CHANGED (added: $ADDED_LINES, removed: $REMOVED_LINES)"
    if [ "$TOTAL_CHANGED" -gt 10 ]; then
        echo "ERROR: Navigation registration file changed $TOTAL_CHANGED lines, exceeding maximum of 10!"
        exit 1
    fi
    echo "✓ Navigation file within line change limit ($TOTAL_CHANGED <= 10)."
fi

echo "✓ All changed files match scope guard allowlist."
echo "=== Scope Guard Checks Passed ==="
