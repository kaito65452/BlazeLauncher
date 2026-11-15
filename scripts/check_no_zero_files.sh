#!/usr/bin/env bash
# Blaze Launcher — Zero-byte & NUL-corruption integrity guard.
# Copyright (C) 2026 Blaze Launcher contributors (GPL-3.0-or-later)
set -euo pipefail

BASE_REF="${CHECK_ZERO_BASE_REF:-origin/feat/overnight-stages}"

is_known_binary() {
    case "$1" in
        *.png|*.webp|*.jpg|*.jpeg|*.gif|*.ico|*.aar|*.jar|*.zip|*.tar|*.tar.xz|*.xz|*.gz|*.so|*.ttf|*.otf|*.bin|*.ogg|*.wav)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

is_exempt_empty() {
    case "$1" in
        InputMap/consumer-rules.keep|*/consumer-rules.keep)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

check_file() {
    local f="$1"
    [ -f "$f" ] || return 0
    if is_exempt_empty "$f"; then
        return 0
    fi
    local total_bytes non_nul_bytes
    total_bytes=$(wc -c < "$f" | tr -d '[:space:]')
    non_nul_bytes=$(tr -d '\000' < "$f" | wc -c | tr -d '[:space:]')

    if [ "$total_bytes" -eq 0 ] || [ "$non_nul_bytes" -eq 0 ]; then
        echo "ERROR: Zero-filled or empty file detected: $f (total=${total_bytes}B, non_nul=${non_nul_bytes}B)" >&2
        return 1
    fi

    if ! is_known_binary "$f" && [ "$non_nul_bytes" -ne "$total_bytes" ]; then
        echo "ERROR: NUL bytes detected in text file: $f (total=${total_bytes}B, non_nul=${non_nul_bytes}B)" >&2
        return 1
    fi
    return 0
}

collect_targets() {
    if [ "$#" -gt 0 ]; then
        for arg in "$@"; do
            if [ -d "$arg" ]; then
                find "$arg" -type f -not -path '*/.git/*' -not -path '*/target/*'
            elif [ -e "$arg" ]; then
                printf '%s\n' "$arg"
            fi
        done | sort -u
        return 0
    fi

    {
        # Working tree / staged modifications against HEAD
        git diff --name-only --diff-filter=ACMRTUXB HEAD 2>/dev/null || true
        git diff --cached --name-only --diff-filter=ACMRTUXB 2>/dev/null || true
        # Untracked source/doc/script/workflow files (skip generated build/apk outputs)
        git ls-files --others --exclude-standard -- \
            .github scripts docs engine/patches blaze-prepare \
            ZalithLauncher/src/main/java/io/github/kaito65452 2>/dev/null || true
        # Tracked changes compared against origin/feat/overnight-stages when present
        if git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
            git diff --name-only --diff-filter=ACMRTUXB "$BASE_REF"...HEAD 2>/dev/null || true
            git diff --name-only --diff-filter=ACMRTUXB "$BASE_REF" 2>/dev/null || true
        fi
        # Also sweep core text directories maintained by overnight stages
        git ls-files -- \
            .github scripts docs engine/patches blaze-prepare AGENTS.md GEMINI.md \
            ZalithLauncher/src/main/java/io/github/kaito65452 \
            ZalithLauncher/src/test/java/io/github/kaito65452 2>/dev/null || true
    } | awk 'NF && !seen[$0]++'
}

FAILED=0
CHECKED=0
while IFS= read -r target; do
    [ -n "$target" ] || continue
    if ! check_file "$target"; then
        FAILED=1
    fi
    CHECKED=$((CHECKED + 1))
done < <(collect_targets "$@")

if [ "$FAILED" -ne 0 ]; then
    echo "ERROR: File zero/NUL integrity check failed." >&2
    exit 1
fi

echo "✓ Zero/NUL integrity check passed ($CHECKED file(s) checked)."
