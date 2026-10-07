#!/usr/bin/env python3
"""Generate every commit entry since the preceding confirmed Telegram delivery."""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

from generate_canary_notes import SHA, TAG
from release_note_common import translate_subject
from telegram_delivery_state import previous_commit

MAX_NOTES_BYTES = 256 * 1024


def generate(repo: Path, tag: str, commit: str, before: str = "") -> str:
    if not TAG.fullmatch(tag) or not SHA.fullmatch(commit) or (before and not SHA.fullmatch(before)):
        raise ValueError("Invalid Telegram changelog identity")
    for revision in [commit] + ([before] if before else []):
        subprocess.run(["git", "cat-file", "-e", revision + "^{commit}"], cwd=repo, check=True, capture_output=True)
    revision = f"{before}..{commit}" if before else commit
    subjects = subprocess.check_output(
        ["git", "log", "--reverse", "--topo-order", "--format=%h%x1f%s", revision],
        cwd=repo, text=True, encoding="utf-8", timeout=60,
    ).splitlines()
    # 不限提交数、不按标题去重、不过滤维护或 merge：每项保留提交短 SHA。
    entries = []
    for row in subjects:
        short_sha, subject = row.split("\x1f", 1)
        entries.append(f"- {translate_subject(subject) or subject} ({short_sha})")
    start = f"上一次成功发布到 Telegram 的源码：{before}。" if before else "首次发布到 Telegram，包含所选源码的完整提交历史。"
    notes = f"{tag}\n\n{start}\n本次源码：{commit}。\n本次共 {len(entries)} 个提交。\n\n"
    notes += "\n".join(entries) if entries else "本次没有新增提交，仅重新发布所选 Canary 构建。"
    if len(notes.encode("utf-8")) > MAX_NOTES_BYTES:
        raise ValueError("Complete Telegram announcement exceeds the delivery budget; no entries were truncated")
    return notes + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--channel", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    before = previous_commit(args.channel)
    notes = generate(args.repo_root, args.tag, args.commit, before)
    args.output.write_text(notes, encoding="utf-8", newline="\n")
    print(f"Prepared complete Telegram announcement from {before or 'the first commit'} to {args.commit}")


if __name__ == "__main__":
    main()
