#!/usr/bin/env python3
"""
Blaze Launcher — CI-only transient engine-data.zip builder & K2 reconciliation oracle (Tasks K1/K2)
Copyright (C) 2026 Blaze Launcher contributors
SPDX-License-Identifier: GPL-3.0-or-later

Gated strictly on BUNDLE_ENGINE_DATA=true in GitHub Actions. Downloads pinned 26.3 inputs
transiently under ALLOWED_DOWNLOAD_HOSTS, runs upstream desktop horizon-prepare, packages
assets/engine-data.zip using upstream android_prepare/android_build allowlist rules, and
reconciles against blaze-prepare outputs on the same transient game-home. Never retains or
prints raw Mojang file contents.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from urllib.parse import urlparse
import urllib.request
import zipfile

CATALOG_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


def load_allowed_hosts(repo_root: Path) -> set[str]:
    lock_rs = (repo_root / "blaze-prepare" / "src" / "lock.rs").read_text(encoding="utf-8")
    m = re.search(r"pub const ALLOWED_DOWNLOAD_HOSTS:\s*&\[&str\]\s*=\s*&\[(.*?)\];", lock_rs, re.S)
    if not m:
        raise RuntimeError("Could not parse ALLOWED_DOWNLOAD_HOSTS from blaze-prepare/src/lock.rs")
    hosts = set(re.findall(r'"([^"]+)"', m.group(1)))
    if not hosts:
        raise RuntimeError("Parsed empty ALLOWED_DOWNLOAD_HOSTS set")
    return hosts


class SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    def __init__(self, allowed_hosts: set[str]):
        super().__init__()
        self.allowed_hosts = allowed_hosts

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_url(newurl, self.allowed_hosts, "redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def validate_url(url: str, allowed_hosts: set[str], field: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https":
        raise ValueError(f"Refusing non-HTTPS URL for {field}: scheme={parsed.scheme}")
    if parsed.port not in (None, 443):
        raise ValueError(f"Refusing non-443 port for {field}: {parsed.port}")
    if parsed.username or parsed.password:
        raise ValueError(f"Credentials forbidden in URL for {field}")
    host = (parsed.hostname or "").lower()
    if host not in allowed_hosts:
        raise ValueError(f"Untrusted host '{host}' for {field}; allowed={sorted(allowed_hosts)}")


def sha1_file(path: Path) -> str:
    h = hashlib.sha1()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_join(base: Path, rel: str) -> Path:
    dest = (base / rel).resolve()
    if not dest.is_relative_to(base.resolve()):
        raise ValueError(f"Path traversal blocked: {rel}")
    return dest


def fetch_verified_bytes(opener, url: str, expected_sha1: str | None, allowed_hosts: set[str], field: str) -> bytes:
    validate_url(url, allowed_hosts, field)
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "BlazeLauncher-CI/1.0"})
            with opener.open(req, timeout=120) as resp:
                data = resp.read()
            if expected_sha1 is not None:
                actual = hashlib.sha1(data).hexdigest()
                if actual.lower() != expected_sha1.lower():
                    raise ValueError(f"SHA-1 mismatch on {field}: actual={actual} expected={expected_sha1}")
            return data
        except Exception:
            if attempt == 3:
                raise
            time.sleep(1 + attempt)
    raise AssertionError("unreachable")


def download_to_file(opener, url: str, dest: Path, expected_sha1: str, allowed_hosts: set[str], field: str) -> None:
    if dest.is_file() and sha1_file(dest).lower() == expected_sha1.lower():
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    data = fetch_verified_bytes(opener, url, expected_sha1, allowed_hosts, field)
    tmp = dest.with_name(f"{dest.name}.partial")
    tmp.write_bytes(data)
    tmp.replace(dest)


def accepted_hashes(pins: dict, primary_key: str, list_key: str) -> set[str]:
    vals = {pins[primary_key].lower()}
    for extra in pins.get(list_key, []):
        vals.add(extra.lower())
    return vals


def download_pinned_mc(starling_root: Path, mc_dir: Path, allowed_hosts: set[str]) -> tuple[dict, Path]:
    sys.path.insert(0, str(starling_root / "tools"))
    import lab  # noqa: PLC0415

    opener = urllib.request.build_opener(SafeRedirectHandler(allowed_hosts))
    pins = json.loads((starling_root / "research-lock.json").read_text(encoding="utf-8"))["minecraft"]
    version = pins["version"]

    catalog = json.loads(fetch_verified_bytes(opener, CATALOG_URL, None, allowed_hosts, "catalog").decode("utf-8"))
    entry = next((v for v in catalog.get("versions", []) if v.get("id") == version), None)
    if entry is None:
        raise RuntimeError(f"Mojang catalog missing {version}")

    manifest_sha1 = entry["sha1"].lower()
    if manifest_sha1 not in accepted_hashes(pins, "manifest_sha1", "accepted_manifest_sha1"):
        raise RuntimeError(f"26.3 manifest sha1 {manifest_sha1} rejected by research-lock.json pins")

    versions_dir = mc_dir / "versions" / version
    manifest_path = versions_dir / f"{version}.json"
    download_to_file(opener, entry["url"], manifest_path, manifest_sha1, allowed_hosts, "version_manifest")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    downloads = []
    client_dl = manifest["downloads"]["client"]
    if client_dl["sha1"].lower() != pins["client_sha1"].lower():
        raise RuntimeError("client jar sha1 mismatch against research-lock.json")
    downloads.append((client_dl["url"], versions_dir / f"{version}.jar", client_dl["sha1"], "downloads.client"))

    server_dl = manifest["downloads"]["server"]
    if server_dl["sha1"].lower() != pins["server_sha1"].lower():
        raise RuntimeError("server jar sha1 mismatch against research-lock.json")
    downloads.append((server_dl["url"], versions_dir / f"{version}-server.jar", server_dl["sha1"], "downloads.server"))

    for artifact in lab.client_library_artifacts(manifest):
        downloads.append((artifact["url"], safe_join(mc_dir / "libraries", artifact["path"]), artifact["sha1"], "library"))

    idx = manifest["assetIndex"]
    idx_sha1 = idx["sha1"].lower()
    if idx_sha1 not in accepted_hashes(pins, "asset_index_sha1", "accepted_asset_index_sha1"):
        raise RuntimeError(f"26.3 assetIndex sha1 {idx_sha1} rejected by research-lock.json pins")
    idx_path = safe_join(mc_dir / "assets" / "indexes", f"{idx['id']}.json")
    download_to_file(opener, idx["url"], idx_path, idx_sha1, allowed_hosts, "assetIndex")

    objects = json.loads(idx_path.read_text(encoding="utf-8"))["objects"]
    for obj in objects.values():
        checksum = obj["hash"]
        rel = f"{checksum[:2]}/{checksum}"
        url = f"https://resources.download.minecraft.net/{rel}"
        downloads.append((url, safe_join(mc_dir / "assets" / "objects", rel), checksum, "asset_object"))

    print(f"Downloading/verifying {len(downloads)} Mojang {version} inputs strictly via ALLOWED_DOWNLOAD_HOSTS...", flush=True)
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [
            pool.submit(download_to_file, opener, url, dest, sha1, allowed_hosts, field)
            for (url, dest, sha1, field) in downloads
        ]
        for f in futures:
            f.result()

    return manifest, versions_dir / f"{version}-server.jar"


def stage_parity_sources(repo_root: Path, dst_parity_root: Path) -> None:
    src_pkg = repo_root / "engine/starling/mods/horizon/src/test/java/dev/horizonlab/parity"
    dst_pkg = dst_parity_root / "dev/horizonlab/parity"
    dst_pkg.mkdir(parents=True, exist_ok=True)
    for path in sorted(src_pkg.glob("*.java")):
        shutil.copy2(path, dst_pkg / path.name)
    shim = repo_root / "ZalithLauncher/src/main/assets/blaze-prepare/PrepareBatchShim.java"
    shutil.copy2(shim, dst_pkg / "PrepareBatchShim.java")


def json_structurally_equal(a: bytes, b: bytes) -> bool:
    try:
        return json.loads(a.decode("utf-8")) == json.loads(b.decode("utf-8"))
    except Exception:
        return False


def reconcile_k2(bundle_zip: Path, blaze_out_dir: Path) -> None:
    with zipfile.ZipFile(bundle_zip, "r") as zf:
        entries = sorted(n for n in zf.namelist() if not n.endswith("/"))
        identical = 0
        struct_eq = 0
        diffs = []
        for name in entries:
            upstream_bytes = zf.read(name)
            blaze_path = blaze_out_dir / name
            if not blaze_path.is_file():
                diffs.append((name, "missing_in_blaze_prepare"))
                continue
            blaze_bytes = blaze_path.read_bytes()
            if sha256_bytes(upstream_bytes) == sha256_bytes(blaze_bytes):
                identical += 1
            elif name.endswith(".json") and json_structurally_equal(upstream_bytes, blaze_bytes):
                struct_eq += 1
            else:
                diffs.append((name, f"bytes_differ(upstream={len(upstream_bytes)},blaze={len(blaze_bytes)})"))
        print(
            f"K2_ORACLE total={len(entries)} byte_identical={identical} "
            f"json_structurally_equal={struct_eq} differing={len(diffs)}",
            flush=True,
        )
        for name, reason in diffs:
            print(f"K2_DIFF entry={name} reason={reason}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--horizon-prepare", type=Path, required=True)
    parser.add_argument("--blaze-preflight", type=Path, default=None)
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "")))
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--out-asset", type=Path, default=Path("ZalithLauncher/src/main/assets/engine-data.zip"))
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[1]
    starling_root = (repo_root / "engine/starling").resolve()
    horizon_prepare_bin = args.horizon_prepare.resolve()
    java_home = args.java_home.resolve()

    if not horizon_prepare_bin.is_file():
        raise FileNotFoundError(f"Missing prebuilt horizon-prepare binary: {horizon_prepare_bin}")
    if not (java_home / "bin" / "java").is_file():
        raise FileNotFoundError(f"Missing JDK 25 java binary under --java-home {java_home}")

    allowed_hosts = load_allowed_hosts(repo_root)
    work_dir = args.work_dir.resolve()
    if work_dir.exists():
        shutil.rmtree(work_dir)
    mc_dir = work_dir / "minecraft"
    mc_dir.mkdir(parents=True, exist_ok=True)

    # Clear any stale starling .local before running upstream preparer
    starling_local = starling_root / ".local"
    if starling_local.exists():
        shutil.rmtree(starling_local)

    _manifest, server_jar = download_pinned_mc(starling_root, mc_dir, allowed_hosts)

    iso_env_dir = work_dir / "horizon-env"
    iso_env_dir.mkdir(parents=True, exist_ok=True)
    prep_env = dict(os.environ)
    prep_env["JAVA_HOME"] = str(java_home)
    for k in ("HORIZON_HOME", "HORIZON_LAUNCHER_HOME", "XDG_DATA_HOME", "XDG_CONFIG_HOME"):
        prep_env[k] = str(iso_env_dir / k.lower())
    prep_env["HORIZON_LAUNCHER_NO_SELF_UPDATE"] = "1"
    prep_env["HORIZON_NO_AUDIO"] = "1"

    print("Running upstream horizon-prepare --prepare (desktop JDK 25)...", flush=True)
    t0 = time.monotonic()
    subprocess.run(
        [
            str(horizon_prepare_bin),
            "--root",
            str(starling_root),
            "--prepare",
            "--minecraft-dir",
            str(mc_dir),
            "--server-jar",
            str(server_jar),
        ],
        cwd=starling_root,
        env=prep_env,
        check=True,
    )
    print(f"Upstream horizon-prepare finished in {time.monotonic() - t0:.1f}s", flush=True)

    # Verify upstream readiness check passes cleanly on generated tree
    subprocess.run(
        [str(horizon_prepare_bin), "--root", str(starling_root), "--check"],
        cwd=starling_root,
        env=prep_env,
        check=True,
    )

    sys.path.insert(0, str(starling_root / "tools"))
    import android_prepare  # noqa: PLC0415
    import android_build  # noqa: PLC0415

    android_private = starling_root / ".local/android"
    android_prepare.package_resources("26.3", android_private)
    bootstrap = json.loads((android_private / "serve/bootstrap.json").read_text(encoding="utf-8"))
    print(f"UPSTREAM_BOOTSTRAP version={bootstrap['version']} serve_zip_bytes={bootstrap['size']}", flush=True)

    generated_zip = android_build.engine_data(public=True)

    # Enforce K4 allowlist before installing as APK asset
    raw_bytes = generated_zip.read_bytes()
    with zipfile.ZipFile(generated_zip, "r") as zf:
        member_infos = [(zi.filename, zi.file_size) for zi in zf.infolist() if not zi.is_dir()]
        for fname, _sz in member_infos:
            if not android_build.generated_engine_file(fname):
                raise RuntimeError(f"Generated zip contains non-allowlisted file: {fname}")

    out_asset = args.out_asset.resolve()
    out_asset.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(generated_zip, out_asset)

    zip_sha256 = sha256_bytes(raw_bytes)
    print(f"ENGINE_DATA_ZIP_SHA256={zip_sha256}", flush=True)
    print(f"ENGINE_DATA_ZIP_BYTES={len(raw_bytes)}", flush=True)
    print(f"ENGINE_DATA_ZIP_ENTRIES={len(member_infos)}", flush=True)
    for fname, sz in sorted(member_infos):
        print(f"ENGINE_DATA_ENTRY path={fname} bytes={sz}", flush=True)

    if args.blaze_preflight is not None:
        preflight_bin = args.blaze_preflight.resolve()
        if not preflight_bin.is_file():
            raise FileNotFoundError(f"Missing prebuilt blaze-prepare preflight binary: {preflight_bin}")
        staged_parity = work_dir / "staged-parity"
        stage_parity_sources(repo_root, staged_parity)
        blaze_files = work_dir / "blaze-files"
        summary_json = work_dir / "blaze-summary.json"
        print("Running K2 reconciliation oracle via prebuilt blaze-prepare preflight (reusing transient mc_dir)...", flush=True)
        subprocess.run(
            [
                str(preflight_bin),
                "--files-dir",
                str(blaze_files),
                "--game-home",
                str(mc_dir),
                "--lock-path",
                str(starling_root / "research-lock.json"),
                "--controls-manifest",
                str(starling_root / "packaging/android/bedrock-controls.json"),
                "--parity-sources",
                str(staged_parity),
                "--java-home",
                str(java_home),
                "--heap-mb",
                "2048",
                "--summary-json",
                str(summary_json),
            ],
            cwd=repo_root,
            check=True,
        )
        reconcile_k2(out_asset, blaze_files / ".local/horizon-client/26.3")


if __name__ == "__main__":
    main()
