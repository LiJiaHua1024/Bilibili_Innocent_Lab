#!/usr/bin/env python3
"""Select an existing successful Canary artifact without rebuilding or publishing it."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

from publish_telegram import validate_identity
from validate_canary_build import next_patch_version

REPOSITORY = "jichuo1/Bilibili_Innocent_Lab"
RUN_URL = re.compile(r"https://github\.com/" + re.escape(REPOSITORY) + r"/actions/runs/([1-9]\d*)/?")
ARTIFACT_NAME = re.compile(r"Bilibili_Innocent_Lab-(v\d+\.\d+\.\d+-canary\.([1-9]\d*))-([0-9a-f]{8})")
SHA = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"sha256:([0-9a-f]{64})")
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_TEXT_BYTES = 256 * 1024


def parse_run_url(value: str) -> tuple[int, str]:
    match = RUN_URL.fullmatch(value.strip())
    if match is None or int(match[1]) > 2**63 - 1:
        raise ValueError("Provide a Canary Actions run URL from " + REPOSITORY)
    run_id = int(match[1])
    return run_id, f"https://github.com/{REPOSITORY}/actions/runs/{run_id}"


def select_artifact(run: dict, artifacts: dict, run_id: int) -> dict:
    if (run.get("id") != run_id or run.get("status") != "completed" or run.get("conclusion") != "success"
        or run.get("head_branch") != "main" or run.get("event") not in {"push", "workflow_dispatch"}
        or run.get("path") != ".github/workflows/canary-build.yml"
        or run.get("repository", {}).get("full_name") != REPOSITORY
        or run.get("head_repository", {}).get("full_name") != REPOSITORY
        or not SHA.fullmatch(run.get("head_sha", ""))):
        raise ValueError("Selected run must be a successful main Canary build from the approved repository")
    number = run.get("run_number")
    if type(number) is not int or number <= 0:
        raise ValueError("Selected run has an invalid Canary sequence")
    if parse_run_url(run.get("html_url", ""))[0] != run_id:
        raise ValueError("Selected run URL does not match its identity")
    entries = artifacts.get("artifacts", [])
    if not isinstance(entries, list) or len(entries) > 100 or artifacts.get("total_count", len(entries)) > 100:
        raise ValueError("Canary artifact list exceeds the inspection budget")
    matches = []
    for entry in entries:
        match = ARTIFACT_NAME.fullmatch(entry.get("name", ""))
        if match is None or int(match[2]) != number or match[3] != run["head_sha"][:8]:
            continue
        owner = entry.get("workflow_run", {})
        if (entry.get("expired") is not False or type(entry.get("id")) is not int or entry["id"] <= 0
            or type(entry.get("size_in_bytes")) is not int or not 0 < entry["size_in_bytes"] <= MAX_ARCHIVE_BYTES
            or not DIGEST.fullmatch(entry.get("digest", "")) or owner.get("id") != run_id
            or owner.get("head_sha") != run["head_sha"] or owner.get("head_branch") != "main"):
            raise ValueError("Matching Canary artifact is expired or has invalid provenance")
        matches.append(entry)
    if len(matches) != 1:
        raise ValueError("Selected run must contain exactly one available signed Canary package")
    return matches[0]


def inspect_archive(archive: Path, artifact: dict, run: dict, destination: Path) -> dict[str, str]:
    if archive.stat().st_size != artifact["size_in_bytes"]:
        raise ValueError("Downloaded artifact size does not match GitHub metadata")
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if artifact["digest"] != "sha256:" + digest:
        raise ValueError("Downloaded artifact digest does not match GitHub metadata")
    apk_name = artifact["name"] + ".apk"
    expected = {apk_name, "BUILD_INFO.txt", "SHA256SUMS.txt", "CANARY_CHANGELOG.md"}
    with zipfile.ZipFile(archive) as bundle:
        entries = [entry for entry in bundle.infolist() if not entry.is_dir()]
        if len(entries) != len(expected) or {entry.filename for entry in entries} != expected:
            raise ValueError("Unexpected Canary archive entries")
        payloads = {}
        for entry in entries:
            budget = 50_000_000 if entry.filename == apk_name else MAX_TEXT_BYTES
            if not 0 < entry.file_size <= budget:
                raise ValueError("Canary archive entry exceeds the inspection budget")
            payloads[entry.filename] = bundle.read(entry)
    checksums = {}
    for line in payloads["SHA256SUMS.txt"].decode("utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if match is None or match[2] in checksums:
            raise ValueError("Invalid Canary checksum manifest")
        checksums[match[2]] = match[1]
    if set(checksums) != {apk_name, "BUILD_INFO.txt"}:
        raise ValueError("Canary checksum manifest does not identify the APK and provenance")
    if any(hashlib.sha256(payloads[name]).hexdigest() != value for name, value in checksums.items()):
        raise ValueError("Canary package checksum mismatch")
    info = {}
    for line in payloads["BUILD_INFO.txt"].decode("utf-8").splitlines():
        key, sep, value = line.partition("=")
        if not sep or key in info:
            raise ValueError("Invalid Canary provenance")
        info[key] = value
    tag = ARTIFACT_NAME.fullmatch(artifact["name"])[1]
    base = info.get("project_base_version", "")
    if (info.get("source_ref") != "refs/heads/main" or info.get("source_commit") != run["head_sha"]
        or info.get("release_tag") != tag or not re.fullmatch(r"\d+\.\d+\.\d+", base)
        or tag.split("-canary.")[0] != "v" + next_patch_version(base)
        or not re.fullmatch(r"[1-9]\d*", info.get("apk_version_code", ""))):
        raise ValueError("Canary package does not match the selected source or version")
    destination.mkdir(parents=True, exist_ok=True)
    if any(destination.iterdir()):
        raise ValueError("Delivery destination must be empty")
    # 逐个写入已验证的白名单文件，不使用 ZipFile.extractall。
    for name, data in payloads.items():
        (destination / name).write_bytes(data)
    source_url = f"https://github.com/{REPOSITORY}/actions/runs/{run['id']}"
    validate_identity(destination / apk_name, destination / "BUILD_INFO.txt", tag, run["head_sha"], source_url)
    return {"package_directory": str(destination.resolve()), "apk_filename": apk_name, "release_tag": tag,
            "version_name": info["apk_version_name"], "version_code": info["apk_version_code"],
            "source_commit": run["head_sha"], "source_run_url": source_url}


def api_json(path: str) -> dict:
    payload = subprocess.check_output(["gh", "api", path], timeout=60)
    if len(payload) > 512 * 1024:
        raise ValueError("GitHub metadata exceeds the inspection budget")
    return json.loads(payload)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-url", required=True)
    parser.add_argument("--destination", required=True, type=Path)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    run_id, _ = parse_run_url(args.run_url)
    run = api_json(f"repos/{REPOSITORY}/actions/runs/{run_id}")
    artifacts = api_json(f"repos/{REPOSITORY}/actions/runs/{run_id}/artifacts?per_page=100")
    artifact = select_artifact(run, artifacts, run_id)
    args.destination.parent.mkdir(parents=True, exist_ok=True)
    archive = args.destination.with_suffix(".zip")
    with archive.open("wb") as output:
        subprocess.run(["gh", "api", f"repos/{REPOSITORY}/actions/artifacts/{artifact['id']}/zip"],
                       stdout=output, check=True, timeout=180)
    outputs = inspect_archive(archive, artifact, run, args.destination)
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            for key, value in outputs.items():
                output.write(f"{key}={value}\n")
    print(f"Selected verified {outputs['release_tag']} from {outputs['source_run_url']}")


if __name__ == "__main__":
    main()
