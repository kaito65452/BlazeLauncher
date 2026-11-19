#!/usr/bin/env bash
set -euo pipefail

echo "=== Running Guard Checks ==="

IS_ISOLATED_MODE=0
if [ "${GUARD_CHECK7_ONLY_DIR:-}" != "" ] || [ "${GUARD_CHECK9_ONLY_DIR:-}" != "" ] || [ "${GUARD_CHECK10_ONLY_DIR:-}" != "" ] || [ "${GUARD_CHECK11_ONLY_DIR:-}" != "" ] || [ "${GUARD_CHECK12_ONLY_PATCH:-}" != "" ] || [ "${GUARD_CHECK14_ONLY_DIR:-}" != "" ] || [ "${GUARD_CHECK16_ACTIVITY:-}" != "" ] || [ "${GUARD_CHECK17_GAME_RS:-}" != "" ] || [ "${GUARD_CHECK18_DIR:-}" != "" ] || [ "${GUARD_CHECK_ZERO_ONLY_DIR:-}" != "" ] || [ "${1:-}" = "--check-apk" ] || [ "${1:-}" = "--check-release-apk" ] || [ "${1:-}" = "--check-release-upload" ] || [ "${1:-}" = "--check-embedded-so" ]; then
    IS_ISOLATED_MODE=1
fi

if [ "$IS_ISOLATED_MODE" -eq 0 ]; then
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
fi

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

# 10. Engine login neutralization guard (structurally parses Rust comments, raw strings, #[cfg(...)], concat!, and live call sites)
check_engine_login_neutralized() {
    local target_dir="$1"
    python3 - "$target_dir" <<'PY'
import os
import re
import sys

root = sys.argv[1]
if not os.path.isdir(root):
    print(f"ERROR: Check 10 target directory missing: {root}", file=sys.stderr)
    sys.exit(1)

def skip_char_or_str(src: str, i: int):
    """If src[i:] starts a Rust string/raw-string/byte-string/char literal, return (end_idx, kind)."""
    n = len(src)
    ch = src[i]
    # Normal or byte string: "..." or b"..."
    if ch == '"':
        j = i + 1
        while j < n:
            c = src[j]
            if c == '\\' and j + 1 < n:
                j += 2
                continue
            if c == '"':
                return j + 1, "str"
            j += 1
        return n, "str"
    if ch == 'b' and i + 1 < n and src[i + 1] == '"':
        end, _ = skip_char_or_str(src, i + 1)
        return end, "str"
    # Raw string: r#*"..."#* or br#*"..."#*
    if ch == 'r' or (ch == 'b' and i + 1 < n and src[i + 1] == 'r'):
        prev = src[i - 1] if i > 0 else ""
        if not (prev.isalnum() or prev == '_'):
            k = i + (2 if ch == 'b' else 1)
            hashes = 0
            while k < n and src[k] == '#':
                hashes += 1
                k += 1
            if k < n and src[k] == '"':
                j = k + 1
                closing = '"' + ('#' * hashes)
                pos = src.find(closing, j)
                return (n if pos == -1 else pos + len(closing)), "raw_str"
    # Char/byte literal: 'x', '\n', '\x7f', '\u{...}', b'x' (distinguish from 'lifetime)
    if ch == "'" or (ch == 'b' and i + 1 < n and src[i + 1] == "'"):
        q = i + (1 if ch == 'b' else 0)
        if q + 2 < n and src[q + 1] == '\\':
            j = q + 2
            if j < n:
                j += 1
                while j < n and j - q <= 12 and src[j] not in ("\n", ";"):
                    if src[j] == "'":
                        return j + 1, "char"
                    j += 1
        elif q + 2 < n and src[q + 1] not in ("\n", "'", "\\") and src[q + 2] == "'":
            return q + 3, "char"
    return None

def strip_rust_comments_preserving_strings(src: str) -> str:
    out = []
    i = 0
    n = len(src)
    block_depth = 0
    while i < n:
        ch = src[i]
        nxt = src[i:i + 2]
        if block_depth > 0:
            if nxt == "/*":
                block_depth += 1
                out.append("  ")
                i += 2
            elif nxt == "*/":
                block_depth -= 1
                out.append("  ")
                i += 2
            elif ch == "\n":
                out.append("\n")
                i += 1
            else:
                out.append(" ")
                i += 1
            continue
        lit = skip_char_or_str(src, i)
        if lit is not None:
            end, _ = lit
            out.append(src[i:end])
            i = end
            continue
        if nxt == "//":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if nxt == "/*":
            block_depth = 1
            out.append("  ")
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)

def find_matching_delim(src: str, open_idx: int, open_c: str, close_c: str) -> int:
    depth = 0
    i = open_idx
    n = len(src)
    while i < n:
        lit = skip_char_or_str(src, i)
        if lit is not None:
            i = lit[0]
            continue
        c = src[i]
        if c == open_c:
            depth += 1
        elif c == close_c:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n

DISABLED_CFG_RE = re.compile(
    r'^\#\[\s*cfg\s*\(\s*(?:test|not\s*\(\s*target_os\s*=\s*"android"\s*\))\s*\)\s*\]$'
)
CFG_MOD_DECL_RE = re.compile(r'\bmod\s+([A-Za-z_][A-Za-z0-9_]*)\s*;')
ATTR_PATH_RE = re.compile(r'\#\[\s*path\s*=\s*"([^"]+)"\s*\]')

def prune_non_android_cfg_items(src: str, fpath: str):
    """Blank out items guarded by #[cfg(test)] or #[cfg(not(target_os = "android"))] while tracking disabled external mods."""
    disabled_mods = set()
    chars = list(src)
    i = 0
    n = len(src)
    while i < n:
        lit = skip_char_or_str(src, i)
        if lit is not None:
            i = lit[0]
            continue
        if src[i:i + 2] == "#[":
            attr_end = find_matching_delim(src, i, "[", "]")
            attr_norm = " ".join(src[i:attr_end].split())
            if DISABLED_CFG_RE.match(attr_norm):
                j = attr_end
                # Consume any stacked attributes (#[derive(...)], #[path = ...], #[allow(...)])
                while j < n:
                    while j < n and src[j].isspace():
                        j += 1
                    if src[j:j + 2] == "#[":
                        j = find_matching_delim(src, j, "[", "]")
                        continue
                    break
                # Advance to end of the annotated item (; at depth 0, or matching {...})
                paren = 0
                bracket = 0
                item_end = n
                while j < n:
                    lit2 = skip_char_or_str(src, j)
                    if lit2 is not None:
                        j = lit2[0]
                        continue
                    c = src[j]
                    if c == '(':
                        paren += 1
                    elif c == ')' and paren > 0:
                        paren -= 1
                    elif c == '[':
                        bracket += 1
                    elif c == ']' and bracket > 0:
                        bracket -= 1
                    elif paren == 0 and bracket == 0:
                        if c == '{':
                            j = find_matching_delim(src, j, "{", "}")
                            k = j
                            while k < n and src[k].isspace():
                                k += 1
                            if src[k:k + 4] == "else" and (k + 4 == n or not (src[k + 4].isalnum() or src[k + 4] == '_')):
                                j = k + 4
                                continue
                            if k < n and src[k] == ';':
                                j = k + 1
                            item_end = j
                            break
                        if c == ';':
                            item_end = j + 1
                            break
                    j += 1
                removed = src[i:item_end]
                if "mod " in removed and "{" not in removed:
                    pm = ATTR_PATH_RE.search(removed)
                    mm = CFG_MOD_DECL_RE.search(removed)
                    base = os.path.dirname(fpath)
                    if pm:
                        disabled_mods.add(os.path.normpath(os.path.join(base, pm.group(1))))
                    elif mm:
                        mod_name = mm.group(1)
                        stem = os.path.splitext(os.path.basename(fpath))[0]
                        if stem in ("lib", "main", "mod"):
                            disabled_mods.add(os.path.normpath(os.path.join(base, f"{mod_name}.rs")))
                            disabled_mods.add(os.path.normpath(os.path.join(base, mod_name, "mod.rs")))
                        else:
                            disabled_mods.add(os.path.normpath(os.path.join(base, stem, f"{mod_name}.rs")))
                for pos in range(i, item_end):
                    if chars[pos] != "\n":
                        chars[pos] = " "
                i = item_end
                continue
        i += 1
    return "".join(chars), disabled_mods

CONCAT_RE = re.compile(r'\bconcat\s*!\s*\(')
STR_TOKEN_RE = re.compile(r'"((?:\\.|[^"\\])*)"')

def fold_concat_strings(src: str) -> list[str]:
    folded = []
    for m in CONCAT_RE.finditer(src):
        end = find_matching_delim(src, m.end() - 1, "(", ")")
        inner = src[m.end():end - 1]
        parts = STR_TOKEN_RE.findall(inner)
        if parts:
            folded.append("".join(parts))
    return folded

def blank_string_contents(src: str) -> str:
    out = []
    i = 0
    n = len(src)
    while i < n:
        lit = skip_char_or_str(src, i)
        if lit is not None:
            end, kind = lit
            newlines = src[i:end].count("\n")
            if kind in ("str", "raw_str"):
                out.append('""' + ("\n" * newlines))
            else:
                out.append("' '" + ("\n" * newlines))
            i = end
            continue
        out.append(src[i])
        i += 1
    return "".join(out)

FORBIDDEN_CLIENT_IDS = ("00000000441cc96b", "00000000402b5328")
FORBIDDEN_ENDPOINTS_RE = re.compile(
    r"(login\.microsoftonline\.com"
    r"|login\.live\.com"
    r"|oauth20_(?:token|connect|remoteconnect)\.srf"
    r"|user\.auth\.xboxlive\.com"
    r"|xsts\.auth\.xboxlive\.com"
    r"|/authentication/login_with_xbox"
    r"|/entitlements/mcstore"
    r"|authserver\.ely\.by(?!/session/join))",
    re.IGNORECASE,
)
FORBIDDEN_CALLS_RE = re.compile(
    r"\b(?:account\s*::\s*(?:device_code|poll|finish_sign_in|finish_sign_in_with_progress|refresh_ms|write_session_file|legacy_client|session\s*::\s*(?:selected_session|selected_session_with|refresh|refresh_with_progress|renew_for_game)|ely\s*::\s*(?:authenticate|refresh))"
    r"|SignIn\s*::\s*start"
    r"|Endpoints\s*::\s*live)\b"
)

DESKTOP_ONLY_REL = {
    "launcher/src/backend.rs",
    "launcher/src/main.rs",
    "launcher/src/cli.rs",
    "launcher/src/mcp.rs",
    "launcher/src/owner_page.rs",
    "launcher/src/switch_page.rs",
}

all_rs_files = []
for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = sorted(d for d in dirnames if d not in (".git", "target", ".local", "patches"))
    for fname in sorted(filenames):
        if fname.endswith(".rs"):
            all_rs_files.append(os.path.normpath(os.path.join(dirpath, fname)))

# Two-pass scan so external #[cfg(test)] mod <name>; targets are collected before reporting.
parsed = []
disabled_external = set()
for fpath in all_rs_files:
    try:
        with open(fpath, "r", encoding="utf-8", errors="replace") as f:
            raw = f.read()
    except OSError:
        continue
    no_comments = strip_rust_comments_preserving_strings(raw)
    android_src, mods = prune_non_android_cfg_items(no_comments, fpath)
    disabled_external.update(mods)
    parsed.append((fpath, raw, no_comments, android_src))

violations = []
seen_rel = set()
is_real_workspace = os.path.isfile(os.path.join(root, "Cargo.toml")) and os.path.isdir(os.path.join(root, "crates/horizon-client"))

for fpath, raw, _no_comments, android_src in parsed:
    if os.path.normpath(fpath) in disabled_external:
        continue
    rel = os.path.relpath(fpath, root).replace(os.sep, "/")
    desktop_rel = rel[len("starling/"):] if (not is_real_workspace and rel.startswith("starling/")) else rel
    if desktop_rel in DESKTOP_ONLY_REL or "/tests/" in f"/{rel}":
        continue
    seen_rel.add(rel)

    folded = "\n".join(fold_concat_strings(android_src))
    check_text = android_src + ("\n" + folded if folded else "")
    code_only = blank_string_contents(android_src)

    for cid in FORBIDDEN_CLIENT_IDS:
        m = re.search(re.escape(cid), check_text)
        if m:
            line_no = check_text[:m.start()].count("\n") + 1
            violations.append(f"{rel}:{line_no}: embedded Starling/Mojang client ID {cid!r} active in Android build")

    for m in FORBIDDEN_ENDPOINTS_RE.finditer(check_text):
        line_no = check_text[:m.start()].count("\n") + 1
        violations.append(f"{rel}:{line_no}: active OAuth/Xbox/Ely login endpoint {m.group(0)!r} in Android build")

    for m in FORBIDDEN_CALLS_RE.finditer(code_only):
        line_no = code_only[:m.start()].count("\n") + 1
        violations.append(f"{rel}:{line_no}: active engine login/refresh call {m.group(0)!r} in Android build")

    if "/android/" in f"/{rel}" or rel.endswith(("android_main.rs", "blaze_diagnostics.rs", "jni_bridge.rs")):
        m = re.search(r"\bAccounts\s*::\s*load\b", code_only)
        if m:
            line_no = code_only[:m.start()].count("\n") + 1
            violations.append(f"{rel}:{line_no}: active Accounts::load call in Android engine module")

    if rel.endswith("blaze_diagnostics.rs"):
        for disk_pat in (r"\.save\s*\(", r"\bfs\s*::\s*write\b", r"File\s*::\s*(?:create|options)"):
            m = re.search(disk_pat, code_only)
            if m:
                line_no = code_only[:m.start()].count("\n") + 1
                violations.append(f"{rel}:{line_no}: setSession/diagnostics must not write session data to disk ({m.group(0)!r})")

    for m in re.finditer(r"BlazeSessionHandoff[^;\n]*\{:032x\}|\buuid=\{:032x\}", check_text):
        line_no = check_text[:m.start()].count("\n") + 1
        violations.append(f"{rel}:{line_no}: BlazeSessionHandoff log must only log first 8 UUID chars (uuid_prefix), not full {{:032x}}")

    if rel.endswith("android/catalog.rs"):
        if not re.search(r'pub\s+const\s+MICROSOFT_CLIENT_ID\s*:\s*&str\s*=\s*""\s*;', android_src):
            violations.append(f"{rel}: MICROSOFT_CLIENT_ID is not cleared to empty string")
        m = re.search(r'"client_id"', android_src)
        if m:
            line_no = android_src[:m.start()].count("\n") + 1
            violations.append(f"{rel}:{line_no}: editable client_id field must be removed")

    if rel.endswith("android/platform.rs"):
        m = re.search(r"pub\s+fn\s+open_login_url\b[^{]*\{(.*?)\n\}", android_src, re.DOTALL)
        if not m or "startActivity" in m.group(1) or "Url::parse" in m.group(1):
            violations.append(f"{rel}: open_login_url must immediately return Err without launching browser")

    if rel.endswith("android/login.rs"):
        if re.search(r"\buse\s+crate::account\b", code_only):
            violations.append(f"{rel}: android/login.rs still imports crate::account")

if is_real_workspace:
    for required in (
        "crates/horizon-client/src/android/catalog.rs",
        "crates/horizon-client/src/android/login.rs",
        "crates/horizon-client/src/android/launcher.rs",
        "launcher/src/account.rs",
    ):
        if required not in seen_rel:
            violations.append(f"{required}: expected engine file missing during Check 10 scan")

if violations:
    print("ERROR: Engine login neutralization guard failed:", file=sys.stderr)
    for v in violations:
        print(f"  {v}", file=sys.stderr)
    sys.exit(1)
PY
}

if [ "${GUARD_CHECK10_ONLY_DIR:-}" != "" ]; then
    echo "Checking custom directory for engine login neutralization: $GUARD_CHECK10_ONLY_DIR"
    check_engine_login_neutralized "$GUARD_CHECK10_ONLY_DIR"
    echo "✓ Custom Check 10 directory passed."
    exit 0
fi

check17_run() {
    local game_rs="$1"
    if [ ! -f "$game_rs" ]; then
        echo "ERROR: Check 17: game.rs not found at $game_rs" >&2
        return 1
    fi
    if grep -q -F "in_world_stationary_overlay" "$game_rs"; then
        echo "ERROR: Check 17: in_world_stationary_overlay found in $game_rs (freezes world behind chat/inventory/multiplayer UI)" >&2
        return 1
    fi
    if ! grep -q -F "should_cache_menu_blur" "$game_rs"; then
        echo "ERROR: Check 17: $game_rs must use should_cache_menu_blur predicate for blur cache" >&2
        return 1
    fi
    echo "✓ Check 17: in_world_stationary_overlay absent in patched game.rs and blur cache gated by should_cache_menu_blur."
}

if [ "$IS_ISOLATED_MODE" -eq 0 ] && [ -d "engine" ]; then
    echo "Checking engine/ for include_bytes!/include_str! font embedding and login neutralization..."
    TEMP_PATCHED_WORKTREE=""
    cleanup_wt() {
        if [ -n "$TEMP_PATCHED_WORKTREE" ] && [ -d "$TEMP_PATCHED_WORKTREE" ]; then
            git -C engine/starling worktree remove --force "$TEMP_PATCHED_WORKTREE" >/dev/null 2>&1 || rm -rf "$TEMP_PATCHED_WORKTREE"
        fi
    }
    trap cleanup_wt EXIT

    run_engine_tree_checks() {
        local starling_root="$1"
        check_engine_font_embeds "$starling_root"
        check_engine_login_neutralized "$starling_root"
        if [ -f "$starling_root/crates/horizon-client/src/game.rs" ]; then
            check17_run "$starling_root/crates/horizon-client/src/game.rs"
        fi
        for item in engine/*; do
            if [ "$item" != "engine/starling" ] && [ -d "$item" ]; then
                check_engine_font_embeds "$item"
                check_engine_login_neutralized "$item"
            fi
        done
    }

    # Scan patched starling tree (clean submodule -> temp worktree; dirty/CI in-tree -> engine/starling) plus any sibling dirs
    if [ -d "engine/starling/.git" ] || [ -f "engine/starling/.git" ]; then
        if [ -z "$(git -C engine/starling status --porcelain)" ] && ls engine/patches/*.patch >/dev/null 2>&1; then
            # Submodule working tree is clean upstream pin; verify patches apply cleanly and scan the patched view
            TEMP_PATCHED_WORKTREE=$(mktemp -d)
            git -C engine/starling worktree add --no-checkout --detach "$TEMP_PATCHED_WORKTREE" HEAD >/dev/null 2>&1
            # Query tracked paths from main submodule index (--no-checkout worktree has an empty index)
            {
                git -C engine/starling ls-files 'Cargo.toml' '**/Cargo.toml' '*.rs'
                grep -h '^--- a/' engine/patches/*.patch | sed 's|^--- a/||' | xargs -r git -C engine/starling ls-files --
            } | sort -u | xargs -r git -C "$TEMP_PATCHED_WORKTREE" checkout HEAD --
            for p in engine/patches/*.patch; do
                p_abs="$(pwd)/$p"
                git -C "$TEMP_PATCHED_WORKTREE" apply --check "$p_abs"
                git -C "$TEMP_PATCHED_WORKTREE" apply "$p_abs"
            done
            run_engine_tree_checks "$TEMP_PATCHED_WORKTREE"
        else
            # Submodule already patched in-tree (CI build state) or no patches: scan engine/starling explicitly
            run_engine_tree_checks "engine/starling"
        fi
    elif [ -d "engine/starling" ]; then
        run_engine_tree_checks "engine/starling"
    else
        check_engine_font_embeds "engine"
        check_engine_login_neutralized "engine"
    fi
    echo "✓ No forbidden include_bytes!/include_str! font embedding found under engine/."
    echo "✓ Engine login/OAuth paths & embedded client IDs verified neutralized."
fi

# 9. Developer package safety guard (must never reference offline-account creation, ownership bypass, or token fields)
check_developer_pkg_safety() {
    local dev_dir="$1"
    if [ ! -d "$dev_dir" ]; then
        echo "ERROR: Developer package directory missing: $dev_dir" >&2
        return 1
    fi
    local forbidden_re='(localLogin|AccountType\.LOCAL|OfflineYggdrasilServer|OfflinePlayer:|saveAccount|addOtherServer|circumventLimit|verifyGameOwnership|com\.movtery\.zalithlauncher\.game\.account|accessToken|refreshToken|access_token|refresh_token|id_token|microsoftRefreshToken|xboxToken|client_secret|MinecraftAccount|AccountsManager|offlineAccount|bypassOwnership|skipOwnership)'
    local hits
    hits=$(grep -rnE "$forbidden_re" "$dev_dir" || true)
    if [ -n "$hits" ]; then
        echo "ERROR: Forbidden account/ownership/token reference found in developer package ($dev_dir):" >&2
        echo "$hits" >&2
        return 1
    fi
    echo "✓ Developer package safety check passed ($dev_dir)."
}

if [ "${GUARD_CHECK9_ONLY_DIR:-}" != "" ]; then
    echo "Checking custom directory for developer package safety: $GUARD_CHECK9_ONLY_DIR"
    check_developer_pkg_safety "$GUARD_CHECK9_ONLY_DIR"
    exit 0
fi

DEV_PKG_DIR="ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/developer"
if [ "$IS_ISOLATED_MODE" -eq 0 ] && [ -d "$DEV_PKG_DIR" ]; then
    echo "Checking developer package safety: $DEV_PKG_DIR..."
    check_developer_pkg_safety "$DEV_PKG_DIR"
fi

# 7b. Check 11: Stage B pure-Rust preparer & bundled asset hygiene (zero Python, zero Mojang assets/derived tables)
check_stage_b_hygiene() {
    local target_root="${1:-.}"
    python3 - "$target_root" <<'PY'
import os
import re
import subprocess
import sys

root = sys.argv[1]
violations = []

# K4 (6): Never allow engine-data.zip / engine-data-generated.zip tracked in git or committed anywhere in repo tree
git_dir = os.path.join(root, ".git")
if os.path.isdir(git_dir) or os.path.isfile(git_dir):
    proc = subprocess.run(["git", "-C", root, "ls-files", "-z"], capture_output=True)
    if proc.returncode == 0:
        for raw_rel in proc.stdout.split(b"\x00"):
            if not raw_rel:
                continue
            rel = raw_rel.decode("utf-8", errors="replace").replace(os.sep, "/")
            bname = os.path.basename(rel).lower()
            if bname in ("engine-data.zip", "engine-data-generated.zip"):
                violations.append(f"{rel}: forbidden git-tracked engine-data zip")
else:
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in (".git", "target", "build", ".gradle"))
        for fname in sorted(filenames):
            if fname.lower() in ("engine-data.zip", "engine-data-generated.zip"):
                rel = os.path.relpath(os.path.join(dirpath, fname), root).replace(os.sep, "/")
                violations.append(f"{rel}: forbidden generated engine-data zip in repo tree")

for sub in ("blaze-prepare", os.path.join("ZalithLauncher", "src", "main", "assets")):
    scan_dir = os.path.join(root, sub)
    if not os.path.isdir(scan_dir):
        continue
    for dirpath, dirnames, filenames in os.walk(scan_dir):
        dirnames[:] = sorted(d for d in dirnames if d not in (".git", "target"))
        for fname in sorted(filenames):
            fpath = os.path.join(dirpath, fname)
            rel = os.path.relpath(fpath, root).replace(os.sep, "/")
            lower = rel.lower()
            if lower.endswith((".py", ".pyc", ".nbt")):
                violations.append(f"{rel}: forbidden Python or NBT file in Stage B tree")
            if fname in ("engine-data.zip", "engine-data-generated.zip", "horizon-resources.json", "android-downloads-v2.json", "audio-complete.json"):
                violations.append(f"{rel}: forbidden generated engine-data file committed in repo")
            if sub == "blaze-prepare" and lower.endswith(".jar"):
                violations.append(f"{rel}: forbidden JAR in blaze-prepare crate")
            if "/horizon-parity/" in f"/{rel}" or "/blaze-prepare/" in f"/{rel}":
                if sub.endswith("assets") and not lower.endswith(".java"):
                    violations.append(f"{rel}: only .java sources may exist under assets/blaze-prepare or assets/horizon-parity")
            if re.search(r"/assets/(?:minecraft|worldgen|\.local)(?:/|$)", f"/{lower}"):
                violations.append(f"{rel}: forbidden Mojang assets/worldgen/.local directory under assets/")

if violations:
    print("ERROR: Stage B engine-data preparer hygiene guard failed:", file=sys.stderr)
    for v in violations:
        print(f"  {v}", file=sys.stderr)
    sys.exit(1)
PY
}

if [ "${GUARD_CHECK11_ONLY_DIR:-}" != "" ]; then
    echo "Checking custom directory for Stage B preparer hygiene: $GUARD_CHECK11_ONLY_DIR"
    check_stage_b_hygiene "$GUARD_CHECK11_ONLY_DIR"
    echo "✓ Custom Check 11 directory passed."
    exit 0
fi

if [ "$IS_ISOLATED_MODE" -eq 0 ]; then
    echo "Checking Stage B preparer & bundled asset hygiene..."
    check_stage_b_hygiene "."
    echo "✓ Stage B preparer & bundled asset hygiene passed."
fi

# 7c. Local pin refresh patch guard: engine/patches/0007-pin-refresh-26.3.patch may ONLY append 40-hex
# hashes to accepted_manifest_sha1 / accepted_asset_index_sha1 in research-lock.json and must never
# modify client_sha1, server_sha1, manifest_sha1, or add unrelated fields.
check_pin_refresh_patch() {
    local patch_file="$1"
    local base_lock="${2:-engine/starling/research-lock.json}"
    python3 - "$patch_file" "$base_lock" <<'PY'
import json
import os
import re
import subprocess
import sys
import tempfile

patch_path, base_lock = sys.argv[1:3]
if not os.path.isfile(patch_path) or os.path.getsize(patch_path) == 0:
    print(f"ERROR: Missing or empty pin refresh patch: {patch_path}", file=sys.stderr)
    sys.exit(1)

raw = open(patch_path, "r", encoding="utf-8", errors="replace").read()
lines = raw.splitlines()

# 1. File header scope: ONLY research-lock.json may appear
diff_files = []
for line in lines:
    if line.startswith("diff --git "):
        diff_files.append(line)
    elif line.startswith("--- "):
        diff_files.append(line)
    elif line.startswith("+++ "):
        diff_files.append(line)

if not diff_files:
    print(f"ERROR: Patch {patch_path} contains no unified diff file headers!", file=sys.stderr)
    sys.exit(1)

allowed_headers = {
    "diff --git a/research-lock.json b/research-lock.json",
    "--- a/research-lock.json",
    "+++ b/research-lock.json",
}
bad_headers = [h for h in diff_files if h not in allowed_headers]
if bad_headers:
    print(
        f"ERROR: Patch {patch_path} touches forbidden paths outside research-lock.json: {bad_headers}",
        file=sys.stderr,
    )
    sys.exit(1)

# 2. Line-by-line hunk audit: only 40-hex accepted-hash entries (or adding trailing comma on existing 40-hex entry)
hex_item_re = re.compile(r'^\s*"([0-9a-f]{40})"(,?)\s*$')
removed_hashes = []
added_hashes = []
for idx, line in enumerate(lines, start=1):
    if line.startswith(("diff --git ", "--- ", "+++ ", "@@ ", "index ", "\\")):
        continue
    if line.startswith("-"):
        body = line[1:]
        m = hex_item_re.match(body)
        if not m:
            print(
                f"ERROR: Patch {patch_path}:{idx} removes or modifies non-hash line in research-lock.json: {body!r}",
                file=sys.stderr,
            )
            sys.exit(1)
        removed_hashes.append((m.group(1), body))
    elif line.startswith("+"):
        body = line[1:]
        m = hex_item_re.match(body)
        if not m:
            print(
                f"ERROR: Patch {patch_path}:{idx} adds forbidden non-hash/unrelated field line to research-lock.json: {body!r}",
                file=sys.stderr,
            )
            sys.exit(1)
        added_hashes.append((m.group(1), body))

added_set = {h for h, _ in added_hashes}
for rem_hash, rem_body in removed_hashes:
    if rem_hash not in added_set:
        print(
            f"ERROR: Patch {patch_path} removes existing hash {rem_hash} ({rem_body!r}) instead of only appending!",
            file=sys.stderr,
        )
        sys.exit(1)

if not added_hashes:
    print(f"ERROR: Patch {patch_path} adds no hashes to accepted lists!", file=sys.stderr)
    sys.exit(1)

# 3. Structural JSON verification against clean submodule HEAD (or base_lock on disk)
if os.path.isfile(base_lock):
    orig_text = None
    lock_dir = os.path.dirname(os.path.abspath(base_lock))
    lock_name = os.path.basename(base_lock)
    head_res = subprocess.run(
        ["git", "-C", lock_dir, "show", f"HEAD:{lock_name}"],
        capture_output=True,
        text=True,
    )
    if head_res.returncode == 0 and head_res.stdout:
        orig_text = head_res.stdout
    else:
        orig_text = open(base_lock, "r", encoding="utf-8").read()
    with tempfile.TemporaryDirectory() as td:
        target = os.path.join(td, "research-lock.json")
        with open(target, "w", encoding="utf-8") as fh:
            fh.write(orig_text)
        res = subprocess.run(
            ["git", "apply", os.path.abspath(patch_path)],
            cwd=td,
            capture_output=True,
            text=True,
        )
        if res.returncode != 0:
            print(
                f"ERROR: Patch {patch_path} does not cleanly apply to {base_lock}: {res.stderr.strip()}",
                file=sys.stderr,
            )
            sys.exit(1)
        orig_json = json.loads(orig_text)
        new_json = json.loads(open(target, "r", encoding="utf-8").read())
        if set(orig_json.keys()) != set(new_json.keys()):
            print(
                f"ERROR: Patch {patch_path} changed top-level keys in research-lock.json!",
                file=sys.stderr,
            )
            sys.exit(1)
        for k in orig_json:
            if k != "minecraft":
                if orig_json[k] != new_json[k]:
                    print(
                        f"ERROR: Patch {patch_path} modified non-minecraft section {k!r}!",
                        file=sys.stderr,
                    )
                    sys.exit(1)
        om = orig_json.get("minecraft", {})
        nm = new_json.get("minecraft", {})
        if set(om.keys()) != set(nm.keys()):
            print(
                f"ERROR: Patch {patch_path} added/removed fields under minecraft: {set(om.keys()) ^ set(nm.keys())}",
                file=sys.stderr,
            )
            sys.exit(1)
        allowed_list_keys = {"accepted_manifest_sha1", "accepted_asset_index_sha1"}
        for k in om:
            if k in allowed_list_keys:
                if not isinstance(nm[k], list) or nm[k][: len(om[k])] != om[k]:
                    print(
                        f"ERROR: Patch {patch_path} mutated existing entries in {k} instead of appending!",
                        file=sys.stderr,
                    )
                    sys.exit(1)
                for h in nm[k]:
                    if not isinstance(h, str) or not re.fullmatch(r"[0-9a-f]{40}", h):
                        print(
                            f"ERROR: Patch {patch_path} introduced invalid SHA-1 in {k}: {h!r}",
                            file=sys.stderr,
                        )
                        sys.exit(1)
            elif om[k] != nm[k]:
                print(
                    f"ERROR: Patch {patch_path} modified strict field minecraft.{k} ({om[k]!r} -> {nm[k]!r})!",
                    file=sys.stderr,
                )
                sys.exit(1)
PY
}

if [ "${GUARD_CHECK12_ONLY_PATCH:-}" != "" ]; then
    echo "Checking custom patch for Check 12 pin refresh hygiene: $GUARD_CHECK12_ONLY_PATCH"
    check_pin_refresh_patch "$GUARD_CHECK12_ONLY_PATCH" "engine/starling/research-lock.json"
    echo "✓ Custom Check 12 patch passed."
    exit 0
fi

if [ "$IS_ISOLATED_MODE" -eq 0 ] && [ -f "engine/patches/0007-pin-refresh-26.3.patch" ]; then
    echo "Checking local pin refresh patch 0007-pin-refresh-26.3.patch..."
    check_pin_refresh_patch "engine/patches/0007-pin-refresh-26.3.patch" "engine/starling/research-lock.json"
    echo "✓ Pin refresh patch 0007 touches only accepted hash lists in research-lock.json."
fi

# 7d. Zero-byte & NUL-corruption integrity check (Stage 0.2)
if [ "${GUARD_CHECK_ZERO_ONLY_DIR:-}" != "" ]; then
    echo "Checking custom directory for zero-filled / NUL-corrupted files: $GUARD_CHECK_ZERO_ONLY_DIR"
    bash scripts/check_no_zero_files.sh "$GUARD_CHECK_ZERO_ONLY_DIR"
    echo "✓ Custom zero/NUL integrity check passed."
    exit 0
fi

if [ "$IS_ISOLATED_MODE" -eq 0 ]; then
    echo "Checking repository for zero-filled or NUL-corrupted files..."
    bash scripts/check_no_zero_files.sh
fi

# --- Check 16 (Stage L-B2): raw engine touch path must not be registered in desktop mode ---
# Native activity owns the window input queue, so Java views in the activity window never get touches.
# Desktop overlay must live in its own panel window, and the engine must drop WindowEvent::Touch.
check16_run() {
    local activity="$1" patch="$2"
    local body
    body="$(awk '/private fun attachDesktopInputOverlay/{f=1} /private fun persistInputBridgeDiagnostics/{f=0} f' "$activity")"
    if [ -z "$body" ]; then
        echo "ERROR: Check 16: attachDesktopInputOverlay not found in $activity" >&2
        return 1
    fi
    if printf '%s\n' "$body" | grep -q -F "addContentView("; then
        echo "ERROR: Check 16: desktop overlay uses addContentView in the NativeActivity window (never receives touches/draws)" >&2
        return 1
    fi
    if ! printf '%s\n' "$body" | grep -q -F "TYPE_APPLICATION_PANEL"; then
        echo "ERROR: Check 16: desktop overlay must be attached as TYPE_APPLICATION_PANEL window" >&2
        return 1
    fi
    if ! grep -q -F -A1 "WindowEvent::Touch(_) =>" "$patch" || ! grep -q -F "record_raw_touch_ignored();" "$patch"; then
        echo "ERROR: Check 16: engine patch must ignore WindowEvent::Touch in desktop mode" >&2
        return 1
    fi
    echo "✓ Check 16: raw touch path not registered in desktop mode (panel overlay + engine touch drop)."
}
if [ "${GUARD_CHECK16_ACTIVITY:-}" != "" ]; then
    check16_run "$GUARD_CHECK16_ACTIVITY" "${GUARD_CHECK16_PATCH:?GUARD_CHECK16_PATCH required}"
    exit $?
fi
if [ "$IS_ISOLATED_MODE" -eq 0 ]; then
    check16_run "ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher/rustruntime/RustActivity.kt" \
        "engine/patches/0009-blaze-input-injector.patch"
fi

# --- Check 17 (Stage L-B3): world must keep rendering when UI/chat/inventory/multiplayer options are open ---
if [ "${GUARD_CHECK17_GAME_RS:-}" != "" ]; then
    check17_run "$GUARD_CHECK17_GAME_RS"
    exit $?
fi

# --- Check 18 (Stage L-B5): all IME show callers in Rust Edition must go through RustImeController ---
check18_run() {
    local target_dir="$1"
    python3 - "$target_dir" <<'PY'
import os
import re
import sys

root = sys.argv[1]
if not os.path.isdir(root):
    print(f"ERROR: Check 18: directory not found: {root}", file=sys.stderr)
    sys.exit(1)

errors = []
for dirpath, _, filenames in os.walk(root):
    for fname in sorted(filenames):
        if not fname.endswith((".kt", ".java")):
            continue
        fpath = os.path.join(dirpath, fname)
        text = open(fpath, "r", encoding="utf-8", errors="replace").read()
        lines = text.splitlines()
        if "SHOW_FORCED" in text or "toggleSoftInput" in text:
            errors.append(f"{fpath}: forbidden SHOW_FORCED / toggleSoftInput IME call")
        for idx, line in enumerate(lines, 1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            if ("showSoftInput(" in line or ".show(WindowInsetsCompat.Type.ime())" in line) and fname != "RustImeInputView.kt":
                errors.append(f"{fpath}:{idx}: direct IME show API call outside RustImeInputView.kt")
            if "enableIme(" in line and not re.search(r'\bfun\s+enableIme\b', line):
                # Must only be called inside RustImeController.Host.showIme()
                ctx = "\n".join(lines[max(0, idx - 6):idx])
                if "override fun showIme()" not in ctx and fname != "RustImeController.kt":
                    errors.append(f"{fpath}:{idx}: enableIme() called outside RustImeController.Host.showIme()")

if errors:
    print("ERROR: Check 18 failed (IME show caller outside RustImeController):", file=sys.stderr)
    for err in errors:
        print(f"  - {err}", file=sys.stderr)
    sys.exit(1)
print("✓ Check 18: all Rust Edition IME show paths strictly gated through RustImeController.")
PY
}
if [ "${GUARD_CHECK18_DIR:-}" != "" ]; then
    check18_run "$GUARD_CHECK18_DIR"
    exit $?
fi
if [ "$IS_ISOLATED_MODE" -eq 0 ]; then
    check18_run "ZalithLauncher/src/main/java/io/github/kaito65452/blazelauncher"
fi

# 8. Optional APK inspection (--check-apk <apk_path> | --check-release-apk <apk_path>)
# --- Check 13: Real-data preflight workflow hygiene (P4) ---
if [ -z "${1:-}" ] && [ -z "${GUARD_CHECK10_ONLY_DIR:-}" ] && [ -z "${GUARD_CHECK11_ONLY_APK:-}" ] && [ -z "${GUARD_CHECK12_ONLY_APK:-}" ] && [ -z "${GUARD_CHECK14_ONLY_DIR:-}" ]; then
    echo "=== Checking preflight-real.yml hygiene ==="
    bash scripts/check_preflight_workflow.sh
fi

# --- Check 14: K8 Nightly/Release/CI engine-data bundle isolation ---
check_release_bundle_isolation() {
    local wf_dir="$1"
    python3 - "$wf_dir" <<'PY'
import os
import re
import sys

wf_dir = sys.argv[1]
if not os.path.isdir(wf_dir):
    print(f"ERROR: Workflows directory not found: {wf_dir}", file=sys.stderr)
    sys.exit(1)

errors = []
forbidden_release_re = re.compile(
    r"BUNDLE_ENGINE_DATA|bundle_engine_data|ci_bundle_engine_data|engine-data\.zip|"
    r"piston-meta\.mojang\.com|launchermeta\.mojang\.com|"
    r"resources\.download\.minecraft\.net|libraries\.minecraft\.net",
    re.IGNORECASE,
)

for name in ("nightly.yml", "release.yml"):
    path = os.path.join(wf_dir, name)
    if not os.path.isfile(path):
        errors.append(f"{path}: missing required workflow file")
        continue
    lines = open(path, "r", encoding="utf-8").read().splitlines()
    for lineno, line in enumerate(lines, 1):
        m = forbidden_release_re.search(line)
        if m:
            errors.append(
                f"{path}:{lineno}: nightly/release workflow must never reference {m.group(0)!r} or Mojang downloads"
            )

ci_path = os.path.join(wf_dir, "ci.yml")
if not os.path.isfile(ci_path):
    errors.append(f"{ci_path}: missing required workflow file")
else:
    ci_lines = open(ci_path, "r", encoding="utf-8").read().splitlines()
    signed_apk_upload_seen = False
    for idx, line in enumerate(ci_lines):
        if "actions/upload-artifact" in line:
            window = "\n".join(ci_lines[idx : min(len(ci_lines), idx + 15)])
            if "signed-apks" in window:
                signed_apk_upload_seen = True
                if not re.search(r"retention-days:\s*3\b", window):
                    errors.append(
                        f"{ci_path}:{idx + 1}: signed-apks upload step must specify retention-days: 3"
                    )
    if not signed_apk_upload_seen:
        errors.append(f"{ci_path}: missing signed-apks actions/upload-artifact step")

for fname in sorted(os.listdir(wf_dir)):
    if not fname.endswith((".yml", ".yaml")):
        continue
    fpath = os.path.join(wf_dir, fname)
    content = open(fpath, "r", encoding="utf-8").read()
    lines = content.splitlines()
    release_upload_lines = [
        (i + 1, l)
        for i, l in enumerate(lines)
        if re.search(
            r"\bgh\s+release\s+(upload|create)\b|softprops/action-gh-release|actions/upload-release-asset",
            l,
        )
    ]
    if release_upload_lines:
        if "--check-release-upload" not in content:
            lineno = release_upload_lines[0][0]
            errors.append(
                f"{fpath}:{lineno}: GitHub Release asset upload workflow must run scripts/guard.sh --check-release-upload before uploading"
            )

if errors:
    print("ERROR: K8 release/nightly bundle isolation guard failed:", file=sys.stderr)
    for err in errors:
        print(f"  - {err}", file=sys.stderr)
    sys.exit(1)
PY
}

if [ "${GUARD_CHECK14_ONLY_DIR:-}" != "" ]; then
    echo "Checking custom workflow directory for K8 release bundle isolation: $GUARD_CHECK14_ONLY_DIR"
    check_release_bundle_isolation "$GUARD_CHECK14_ONLY_DIR"
    echo "✓ Custom Check 14 directory passed."
    exit 0
fi

if [ "$IS_ISOLATED_MODE" -eq 0 ]; then
    echo "Checking K8 nightly/release/CI engine-data bundle isolation..."
    check_release_bundle_isolation ".github/workflows"
    echo "✓ K8 nightly/release/CI engine-data bundle isolation passed."
fi

if [ "${1:-}" = "--check-embedded-so" ]; then
    SO_PATH="${2:-}"
    if [ -z "$SO_PATH" ] || [ ! -f "$SO_PATH" ]; then
        echo "ERROR: --check-embedded-so requires a valid .so file path (got: '$SO_PATH')"
        exit 1
    fi
    echo "Checking embedded engine .so (A0 KEEP-list + A3 stripped UI): $SO_PATH"
    python3 - "$SO_PATH" <<'PY'
import sys

so_path = sys.argv[1]
data = open(so_path, "rb").read()

required_keep_markers = [
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeInit",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSetSession",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSetDirectLaunch",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSignalEngineExit",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeConfigureEmbedded",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeInjectKey",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeInjectMouseButton",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeInjectCursorPos",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeInjectCursorDelta",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeInjectScroll",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeInjectChar",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeInjectAndroidBack",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeResetInputState",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeSetDesktopInputMode",
    b"Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeInputDiagnostics",
    b"BlazeWgpu:",
    b"BlazePerf pipelines_created_ms=",
    b"BlazePerf surface_to_first_frame_ms=",
    b"Not affiliated with Mojang or Microsoft",
    b"APerformanceHint_createSession",
    b"ANativeWindow_setFrameRate",
    b"android_main",
    b"BlazeDirectLaunch: entered 26.3 game directly (blaze-embedded)",
]

forbidden_embedded_markers = [
    b"Horizon Control Hub",
    b"Configure touch controls, virtual mouse & feedback",
    b"Horizon Android",
    b"Start Horizon from the launcher",
    b"https://telemetry.playhorizon.dev",
    b"https://horizon-rust.github.io/horizon-mods",
    b"https://github.com/JuanLuci/starling/releases/latest",
    b"https://github.com/Vasysik/horizon-android",
    b"https://api.curseforge.com/v1",
    b"Complementary Reimagined",
]

missing = [m.decode("ascii") for m in required_keep_markers if m not in data]
if missing:
    print("ERROR: blaze-embedded .so is missing mandatory A0 KEEP-list symbols/markers:", file=sys.stderr)
    for item in missing:
        print(f"  - {item}", file=sys.stderr)
    sys.exit(1)

found_bad = [m.decode("ascii") for m in forbidden_embedded_markers if m in data]
if found_bad:
    print("ERROR: blaze-embedded .so still contains forbidden Horizon UI/network markers:", file=sys.stderr)
    for item in found_bad:
        print(f"  - {item}", file=sys.stderr)
    sys.exit(1)

print(f"✓ Embedded .so verified: {len(required_keep_markers)} KEEP markers present, 0 forbidden UI/network markers.")
PY
    exit 0
fi

if [ "${1:-}" = "--check-apk" ] || [ "${1:-}" = "--check-release-apk" ] || [ "${1:-}" = "--check-release-upload" ]; then
    CHECK_RELEASE_APK=0
    CHECK_RELEASE_UPLOAD=0
    ALLOW_BUNDLED_ENGINE_DATA=0
    if [ "${1:-}" = "--check-release-apk" ]; then
        CHECK_RELEASE_APK=1
    elif [ "${1:-}" = "--check-release-upload" ]; then
        CHECK_RELEASE_APK=1
        CHECK_RELEASE_UPLOAD=1
    fi
    if [ "$CHECK_RELEASE_UPLOAD" -eq 0 ] && [ "${BUNDLE_ENGINE_DATA:-}" = "true" ]; then
        ALLOW_BUNDLED_ENGINE_DATA=1
    fi
    APK_PATH="${2:-}"
    if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
        echo "ERROR: ${1:-} requires a valid APK file path (got: '$APK_PATH')"
        exit 1
    fi
    echo "Checking APK license hygiene: $APK_PATH (release_check=$CHECK_RELEASE_APK release_upload=$CHECK_RELEASE_UPLOAD allow_bundle=$ALLOW_BUNDLED_ENGINE_DATA)"
    python3 - "$APK_PATH" "$CHECK_RELEASE_APK" "$ALLOW_BUNDLED_ENGINE_DATA" "$CHECK_RELEASE_UPLOAD" <<'PY'
import re
import hashlib
import io
import json
import os
import re
import struct
import sys
import zipfile

apk_path = sys.argv[1]
check_release = sys.argv[2] == "1"
allow_bundle_flag = sys.argv[3] == "1"
check_release_upload = sys.argv[4] == "1"
disallowed_re = re.compile(
    r"(^|/)(wallpapers|wallpaper_templates)/|(^|/)assets/skin/|(^|/)assets/alex\.png$|voicechat"
    r"|^assets/(?:minecraft|worldgen|\.local)/|^assets/(?:horizon-resources\.json|android-downloads-v2\.json|audio-complete\.json)$"
    r"|\.(?:py|pyc|nbt)$|^assets/(?:horizon-parity|blaze-prepare)/.*\.class$",
    re.IGNORECASE,
)

# K4 narrow allowlist matching engine/starling tools/android_build.py:generated_engine_file
ALLOWED_GENERATED_ENGINE_FILES = {
    "attributes.json",
    "block-behaviours.json",
    "cached-collision.nbt",
    "command-registries.json",
    "commands.json",
    "creative-inventory.json",
    "damage-catalog.json",
    "entity-models.json",
    "entity-types.json",
    "facts.json",
    "gameplay-facts.json",
    "horizon-resources.json",
    "items.json",
    "landing-definitions.json",
    "mob-data.json",
    "model-states.json",
    "movement-properties.nbt",
    "occlusion.nbt",
    "player-float-ids.json",
    "registries.json",
    "render-definitions.json",
    "biome-parameters/nether.json",
    "biome-parameters/overworld.json",
    "worldgen/biome_parameters/nether.json",
    "worldgen/biome_parameters/overworld.json",
    "worldgen/block_flags.json",
    "worldgen/blocks/block-properties.nbt",
    "worldgen/blocks/cached-collision.nbt",
    "worldgen/blocks/cached-support.nbt",
    "worldgen/blocks/survival-rules.json",
    "worldgen/blocks/survival-extra-definitions.json",
    "worldgen/blocks/placement-definitions.json",
    "worldgen/blocks/structure-blocks.json",
}

def verify_bundled_engine_data_zip(raw_bytes: bytes, doc_path: str):
    if os.environ.get("BUNDLE_ENGINE_DATA") != "true":
        print("ERROR: APK contains assets/engine-data.zip but BUNDLE_ENGINE_DATA != 'true'!", file=sys.stderr)
        sys.exit(1)
    if not os.path.isfile(doc_path):
        print(f"ERROR: K4 requires {doc_path} to exist when assets/engine-data.zip is bundled!", file=sys.stderr)
        sys.exit(1)

    sha256_hex = hashlib.sha256(raw_bytes).hexdigest()
    print(f"ENGINE_DATA_ZIP_SHA256={sha256_hex}")
    print(f"ENGINE_DATA_ZIP_BYTES={len(raw_bytes)}")

    expected_sha = os.environ.get("GUARD_EXPECT_ENGINE_DATA_SHA256", "").strip().lower()
    if expected_sha and sha256_hex != expected_sha:
        print(
            f"ERROR: assets/engine-data.zip sha256 {sha256_hex} != GUARD_EXPECT_ENGINE_DATA_SHA256 {expected_sha}!",
            file=sys.stderr,
        )
        sys.exit(1)

    seen_files = set()
    try:
        with zipfile.ZipFile(io.BytesIO(raw_bytes), "r") as nested:
            for zi in nested.infolist():
                if zi.is_dir():
                    continue
                if ((zi.external_attr >> 16) & 0o170000) == 0o120000:
                    print(f"ERROR: Symlink forbidden in assets/engine-data.zip: {zi.filename}", file=sys.stderr)
                    sys.exit(1)
                name = zi.filename
                if name.startswith("/") or "\\" in name or ".." in name.split("/"):
                    print(f"ERROR: Unsafe path in assets/engine-data.zip: {name}", file=sys.stderr)
                    sys.exit(1)
                if name not in ALLOWED_GENERATED_ENGINE_FILES:
                    print(
                        f"ERROR: Disallowed entry inside assets/engine-data.zip: {name} (not in generated allowlist)",
                        file=sys.stderr,
                    )
                    sys.exit(1)
                if name in seen_files:
                    print(f"ERROR: Duplicate entry inside assets/engine-data.zip: {name}", file=sys.stderr)
                    sys.exit(1)
                seen_files.add(name)

            for req in ("horizon-resources.json", "facts.json", "items.json", "registries.json", "commands.json"):
                if req not in seen_files:
                    print(f"ERROR: assets/engine-data.zip missing required file {req}", file=sys.stderr)
                    sys.exit(1)
            manifest = json.loads(nested.read("horizon-resources.json").decode("utf-8"))
            if manifest.get("schema") != 7 or manifest.get("minecraft") != "26.3":
                print(
                    f"ERROR: assets/engine-data.zip horizon-resources.json invalid metadata: schema={manifest.get('schema')} minecraft={manifest.get('minecraft')}",
                    file=sys.stderr,
                )
                sys.exit(1)
    except zipfile.BadZipFile as exc:
        print(f"ERROR: assets/engine-data.zip is not a valid zip: {exc}", file=sys.stderr)
        sys.exit(1)

    print(f"ENGINE_DATA_ZIP_ENTRIES={len(seen_files)}")
    print(f"✓ K4 assets/engine-data.zip verified ({len(seen_files)} allowlisted entries, sha256={sha256_hex}).")
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

def inspect_axml_debuggable(manifest_bytes: bytes):
    if len(manifest_bytes) < 8:
        raise ValueError("AndroidManifest.xml too short")
    magic, hdr_size, _total_size = struct.unpack_from("<HHI", manifest_bytes, 0)
    if magic != 0x0003:
        raise ValueError(f"AndroidManifest.xml is not binary AXML (magic=0x{magic:04x})")
    pos = hdr_size
    strings = []
    res_ids = []
    seen_application = False
    debuggable_attr = None
    while pos + 8 <= len(manifest_bytes):
        ctype, chdr, csize = struct.unpack_from("<HHI", manifest_bytes, pos)
        if csize < 8 or pos + csize > len(manifest_bytes):
            break
        if ctype == 0x0001:  # RES_STRING_POOL_TYPE
            scount, _stcount, flags, sstart, _ststart = struct.unpack_from("<IIIII", manifest_bytes, pos + 8)
            utf8 = bool(flags & (1 << 8))
            offsets = struct.unpack_from(f"<{scount}I", manifest_bytes, pos + 28)
            base = pos + sstart
            for off in offsets:
                p = base + off
                if utf8:
                    if manifest_bytes[p] & 0x80:
                        p += 2
                    else:
                        p += 1
                    blen = manifest_bytes[p]
                    if blen & 0x80:
                        blen = ((blen & 0x7f) << 8) | manifest_bytes[p + 1]
                        p += 2
                    else:
                        p += 1
                    strings.append(manifest_bytes[p : p + blen].decode("utf-8", errors="replace"))
                else:
                    ulen = struct.unpack_from("<H", manifest_bytes, p)[0]
                    p += 2
                    if ulen & 0x8000:
                        ulen = ((ulen & 0x7fff) << 16) | struct.unpack_from("<H", manifest_bytes, p)[0]
                        p += 2
                    strings.append(manifest_bytes[p : p + 2 * ulen].decode("utf-16-le", errors="replace"))
        elif ctype == 0x0180:  # RES_XML_RESOURCE_MAP_TYPE
            cnt = (csize - chdr) // 4
            res_ids = list(struct.unpack_from(f"<{cnt}I", manifest_bytes, pos + chdr))
        elif ctype == 0x0102:  # RES_XML_START_ELEMENT_TYPE
            _line, _cmt, _ns, name_idx, attr_start, attr_size, attr_count = struct.unpack_from("<IIiiHHH", manifest_bytes, pos + 8)
            tag_name = strings[name_idx] if 0 <= name_idx < len(strings) else ""
            if tag_name == "application":
                seen_application = True
                attr_base = pos + chdr + attr_start
                for i in range(attr_count):
                    ap = attr_base + i * attr_size
                    if ap + 20 > pos + csize:
                        break
                    _a_ns, a_name, _a_raw, _a_sz, _a_r0, a_type, a_data = struct.unpack_from("<iiiHBBI", manifest_bytes, ap)
                    aname = strings[a_name] if 0 <= a_name < len(strings) else ""
                    rid = res_ids[a_name] if 0 <= a_name < len(res_ids) else 0
                    if aname == "debuggable" or rid == 0x0101000f:
                        debuggable_attr = (a_type, a_data)
        pos += csize
    return seen_application, debuggable_attr

with zipfile.ZipFile(apk_path, "r") as zf:
    names = zf.namelist()
    if "assets/THIRD_PARTY_NOTICES.md" not in names:
        print("ERROR: APK is missing assets/THIRD_PARTY_NOTICES.md!", file=sys.stderr)
        sys.exit(1)

    bad = [n for n in names if disallowed_re.search(n)]
    relocated_engine_data = [
        n for n in names
        if n != "assets/engine-data.zip"
        and n.lower().startswith("assets/")
        and ("engine-data" in n.lower() or "engine_data" in n.lower())
        and n.lower().endswith(".zip")
    ]
    bad.extend(relocated_engine_data)
    if bad:
        print("ERROR: APK contains disallowed asset entries:", file=sys.stderr)
        for item in bad:
            print(f"  - {item}", file=sys.stderr)
        sys.exit(1)

    if "assets/engine-data.zip" in names:
        if check_release_upload:
            print(
                "ERROR: GitHub Release upload forbids APK built with assets/engine-data.zip (regardless of BUNDLE_ENGINE_DATA)!",
                file=sys.stderr,
            )
            sys.exit(1)
        if not allow_bundle_flag:
            print(
                "ERROR: assets/engine-data.zip present in APK when BUNDLE_ENGINE_DATA != 'true'!",
                file=sys.stderr,
            )
            sys.exit(1)
        doc_path = os.environ.get("GUARD_ENGINE_DATA_DOC_PATH", "docs/ENGINE_DATA_BUNDLE.md")
        verify_bundled_engine_data_zip(zf.read("assets/engine-data.zip"), doc_path)
    elif allow_bundle_flag and os.environ.get("BUNDLE_ENGINE_DATA") == "true" and check_release:
        print(
            "ERROR: BUNDLE_ENGINE_DATA=true on release APK check, but assets/engine-data.zip is missing!",
            file=sys.stderr,
        )
        sys.exit(1)

    font_files = [n for n in names if re.match(r"^assets/fonts/.*\.(ttf|otf)$", n, re.IGNORECASE)]
    if font_files:
        ofl_notices = [n for n in names if re.match(r"^assets/fonts/OFL-.*\.txt$", n)]
        if not ofl_notices:
            print("ERROR: APK bundles assets/fonts/*.ttf|.otf without assets/fonts/OFL-*.txt!", file=sys.stderr)
            sys.exit(1)

    so_entries = [n for n in names if re.match(r"^lib/[^/]+/.*\.so$", n)]
    print(f"Scanning {len(so_entries)} shared libraries under lib/*/*.so...")
    forbidden_so_markers = [
        b"00000000441cc96b",
        b"00000000402b5328",
        b"login.microsoftonline.com",
        b"login.live.com",
        b"oauth20_token.srf",
        b"oauth20_connect.srf",
        b"oauth20_remoteconnect.srf",
        b"user.auth.xboxlive.com",
        b"xsts.auth.xboxlive.com",
        b"/authentication/login_with_xbox",
        b"/entitlements/mcstore",
        b"authserver.ely.by",
    ]
    forbidden_embedded_ui_markers = [
        b"Horizon Control Hub",
        b"Horizon Android",
        b"Start Horizon from the launcher",
        b"https://telemetry.playhorizon.dev",
        b"https://horizon-rust.github.io/horizon-mods",
        b"https://github.com/JuanLuci/starling/releases/latest",
        b"https://github.com/Vasysik/horizon-android",
        b"https://api.curseforge.com/v1",
        b"Complementary Reimagined",
    ]
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
        masked_so = data.replace(b"authserver.ely.by/session/join", b"")
        for marker in forbidden_so_markers:
            if marker in masked_so:
                print(
                    f"ERROR: {n} contains forbidden Starling OAuth/login marker {marker.decode('ascii')!r}!",
                    file=sys.stderr,
                )
                sys.exit(1)
        for marker in forbidden_embedded_ui_markers:
            if marker in data:
                print(
                    f"ERROR: {n} contains forbidden Horizon UI/network marker {marker.decode('ascii')!r}!",
                    file=sys.stderr,
                )
                sys.exit(1)

    if check_release:
        if "AndroidManifest.xml" not in names:
            print("ERROR: Release APK check failed: missing AndroidManifest.xml!", file=sys.stderr)
            sys.exit(1)
        seen_app, dbg_attr = inspect_axml_debuggable(zf.read("AndroidManifest.xml"))
        if not seen_app:
            print("ERROR: Release APK check failed: AndroidManifest.xml missing <application> element!", file=sys.stderr)
            sys.exit(1)
        if dbg_attr is not None:
            a_type, a_data = dbg_attr
            if a_type != 0x12 or a_data != 0:
                print(
                    f"ERROR: APK has android:debuggable=true (or non-false attr type=0x{a_type:02x} data=0x{a_data:08x})!",
                    file=sys.stderr,
                )
                sys.exit(1)
        print("✓ Release APK manifest verified non-debuggable (android:debuggable=false).")

print(f"✓ APK license hygiene check passed ({len(so_entries)} .so files scanned).")
PY
fi

echo "=== All Guard Checks Passed ==="
