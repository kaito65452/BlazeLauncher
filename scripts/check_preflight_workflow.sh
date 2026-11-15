#!/usr/bin/env bash
# Blaze Launcher (based on Zalith Launcher 2)
# Copyright (C) 2026 Blaze Launcher contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Guard Check 13 (Stage 3 / P4): verify .github/workflows/preflight-real.yml
# - NEVER triggers on push or pull_request (workflow_dispatch ONLY)
# - NEVER references secrets or caches Mojang files (actions/cache)
# - ALWAYS cleans up $RUNNER_TEMP/.local Mojang data via if: always()
# - Uploads ONLY redacted log and summary text artifacts (*.log, *-summary*.md/txt)

set -euo pipefail

WORKFLOW_FILE="${1:-.github/workflows/preflight-real.yml}"

if [ ! -f "$WORKFLOW_FILE" ]; then
    echo "ERROR: Preflight workflow missing: $WORKFLOW_FILE" >&2
    exit 1
fi

python3 - "$WORKFLOW_FILE" <<'PY'
import re
import sys

path = sys.argv[1]
text = open(path, "r", encoding="utf-8").read()
lines = text.splitlines()

errors = []

# 1. Trigger restriction: workflow_dispatch ONLY, never push / pull_request / schedule
on_block_lines = []
in_on = False
for line in lines:
    if re.match(r"^on:\s*$", line):
        in_on = True
        on_block_lines.append(line)
        continue
    if re.match(r"^on:\s+\S", line):
        on_block_lines.append(line)
        break
    if in_on:
        if line and not line[0].isspace() and not line.lstrip().startswith("#"):
            break
        on_block_lines.append(line)

on_block = "\n".join(on_block_lines)
if "workflow_dispatch" not in on_block:
    errors.append("preflight-real.yml must declare workflow_dispatch trigger")
for forbidden_evt in ("push", "pull_request", "pull_request_target", "schedule", "workflow_call"):
    if re.search(rf"(^|\W){forbidden_evt}(\W|$)", on_block):
        errors.append(f"Forbidden trigger '{forbidden_evt}' found in 'on:' block of {path}")

# 2. No secrets
if re.search(r"\bsecrets\s*\.", text):
    errors.append(f"Forbidden 'secrets.' reference found in {path}")

# 3. Permissions must stay minimal (contents: read only; no write permissions)
if not re.search(r"contents:\s*read\b", text):
    errors.append(f"Expected 'contents: read' permissions in {path}")
if re.search(r":\s*write\b", text):
    errors.append(f"Forbidden write permission found in {path}")

# 4. No actions/cache (must never cache Mojang files)
if "actions/cache" in text:
    errors.append(f"Forbidden 'actions/cache' found in {path} (must never cache Mojang files)")

# 5. Cleanup step with always() deleting RUNNER_TEMP/Mojang trees
if not re.search(r"if:\s*(?:\$\{\{\s*)?always\(\)", text) or "rm -rf" not in text:
    errors.append(f"Missing 'if: always()' rm -rf cleanup step for RUNNER_TEMP in {path}")

# 6. Artifact upload allowlist: strictly redacted log and summary text only
upload_matches = list(re.finditer(r"uses:\s*actions/upload-artifact@", text))
if not upload_matches:
    errors.append(f"Missing actions/upload-artifact step in {path}")

for m in upload_matches:
    tail = text[m.end():]
    # Read lines until next top-level step ('      - ' at <=8 spaces indent) or EOF
    step_lines = []
    for ln in tail.splitlines():
        if re.match(r"^\s{2,6}-\s+(name|uses|run|id):", ln):
            break
        step_lines.append(ln)
    step_block = "\n".join(step_lines)
    raw_paths = []
    block_lines = step_block.splitlines()
    for idx, ln in enumerate(block_lines):
        m_pipe = re.match(r"^(\s*)path:\s*\|\s*$", ln)
        if m_pipe:
            base_indent = len(m_pipe.group(1))
            for sub in block_lines[idx + 1:]:
                if not sub.strip() or sub.lstrip().startswith("#"):
                    continue
                sub_indent = len(sub) - len(sub.lstrip())
                if sub_indent <= base_indent:
                    break
                raw_paths.append(sub.strip())
            break
        m_single = re.match(r"^\s*path:\s*([^\|\n#]+)$", ln)
        if m_single:
            raw_paths.append(m_single.group(1).strip())
            break
    if not raw_paths:
        errors.append("upload-artifact step missing explicit non-empty 'path:'")
        continue

    for rp in raw_paths:
        if any(bad in rp.lower() for bad in (".local", "engine-data", "minecraft", ".jar", ".zip", ".nbt", ".ogg", ".png", "assets/", "libraries/")):
            errors.append(f"Forbidden Mojang/engine artifact path in upload-artifact: '{rp}'")
        if not re.search(r"\.(?:log|txt|md)$", rp):
            errors.append(f"upload-artifact path must be an explicit .log/.txt/.md summary/log path, got: '{rp}'")

if errors:
    for err in errors:
        print(f"ERROR: {err}", file=sys.stderr)
    sys.exit(1)

print(f"✓ Preflight workflow hygiene check passed ({path}).")
PY
