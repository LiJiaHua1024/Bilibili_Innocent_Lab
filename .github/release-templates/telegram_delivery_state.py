#!/usr/bin/env python3
"""Keep the last confirmed Telegram source separate from Canary build history."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path

REPOSITORY = "jichuo1/Bilibili_Innocent_Lab"
ENVIRONMENT = "canary-telegram"
TASK = "canary-telegram"
SHA = re.compile(r"[0-9a-f]{40}")
MAX_PAGES = 20


def github(path: str, payload: dict | None = None, *, raw: bool = False):
    command = ["gh", "api", f"repos/{REPOSITORY}/{path}"]
    if raw:
        match = re.fullmatch(r"actions/jobs/([1-9]\d*)/logs", path)
        if match is None or payload is not None:
            raise ValueError("Unexpected Telegram job log request")
        # gh api 会拒绝输出日志里的 ANSI 控制字符；run view 专门处理 Actions 日志。
        command = ["gh", "run", "view", "--repo", REPOSITORY, "--job", match[1], "--log"]
    if payload is not None:
        command += ["--method", "POST", "--input", "-"]
    result = subprocess.run(command, input=json.dumps(payload).encode() if payload is not None else None,
                            capture_output=True, timeout=60)
    if result.returncode:
        raise ValueError("Cannot read or record Telegram delivery history: " + path.split("?")[0])
    if len(result.stdout) > (8 * 1024 * 1024 if raw else 1024 * 1024):
        raise ValueError("Telegram delivery history exceeds the inspection budget")
    return result.stdout.decode("utf-8") if raw else json.loads(result.stdout)


def channel_key(channel: str) -> str:
    if not re.fullmatch(r"@[A-Za-z][A-Za-z0-9_]{4,31}|-100\d+", channel):
        raise ValueError("Invalid Telegram channel ID")
    return channel.lower()


def confirmed_receipt(payload: dict) -> bool:
    messages = payload.get("announcement_message_ids")
    return (isinstance(payload.get("source_commit"), str) and SHA.fullmatch(payload["source_commit"]) is not None
            and payload.get("delivery_complete") is True
            and type(payload.get("document_message_id")) is int and payload["document_message_id"] > 0
            and isinstance(messages, list) and bool(messages)
            and all(type(identity) is int and identity > 0 for identity in messages))


def previous_commit(channel: str) -> str:
    channel = channel_key(channel)
    for page in range(1, MAX_PAGES + 1):
        deployments = github(f"deployments?environment={ENVIRONMENT}&task={TASK}&per_page=100&page={page}")
        for deployment in sorted(deployments, key=lambda row: row["id"], reverse=True):
            payload = deployment.get("payload", {})
            if isinstance(payload, str):
                payload = json.loads(payload)
            if (deployment.get("environment") != ENVIRONMENT or deployment.get("task") != TASK
                or not isinstance(payload, dict) or payload.get("schema") != 1
                or payload.get("channel") != channel):
                continue
            if payload.get("delivery_complete") is not True:
                continue
            commit = payload.get("source_commit", "")
            if not confirmed_receipt(payload) or deployment.get("sha") != commit:
                raise ValueError("Invalid Telegram delivery checkpoint")
            # 回执只在所有 TG 消息成功后创建；status 写入失败也不能抹掉真实投递。
            # 同时检查较新的运行，恢复 TG 成功但 deployment 创建失败的情况。
            delivery_url = payload.get("delivery_run_url", "")
            match = re.fullmatch(r"https://github\.com/" + re.escape(REPOSITORY) + r"/actions/runs/([1-9]\d*)", delivery_url)
            if match is None:
                raise ValueError("Telegram checkpoint is missing its delivery run")
            return legacy_previous_commit(channel, int(match[1]), commit)
        if len(deployments) < 100:
            break
    else:
        raise ValueError("Telegram checkpoint history exceeds the inspection budget")
    return legacy_previous_commit(channel)


def legacy_previous_commit(channel: str, checkpoint_run: int = 0, fallback: str = "") -> str:
    # 旧版没有持久化回执。必须看实际发送结果，而不是 workflow 成功或其 head_sha；
    # dry-run 也成功，且手动同步可能选择比工作流源码更早的构建。
    for page in range(1, MAX_PAGES + 1):
        result = github(f"actions/workflows/canary-telegram.yml/runs?status=completed&per_page=100&page={page}")
        runs = result["workflow_runs"]
        for run in sorted(runs, key=lambda row: (row.get("updated_at", ""), row["id"]), reverse=True):
            if run["id"] == checkpoint_run:
                return fallback
            if (run.get("path") != ".github/workflows/canary-telegram.yml" or run.get("head_branch") != "main"
                or run.get("event") != "workflow_dispatch"):
                continue
            jobs = github(f"actions/runs/{run['id']}/jobs?per_page=100")
            if jobs.get("total_count", len(jobs["jobs"])) > 100:
                raise ValueError("Telegram delivery job history exceeds the inspection budget")
            for job in jobs["jobs"]:
                if job.get("name") != "Verify selected Canary and send its APK":
                    continue
                log = github(f"actions/jobs/{job['id']}/logs", raw=True)
                if not re.search(r"Telegram delivered Canary APK, message ID [1-9]\d*", log):
                    continue
                commits = set(re.findall(r"\bSOURCE_COMMIT: ([0-9a-f]{40})\s*$", log, re.MULTILINE))
                channels = set(re.findall(r"\bTELEGRAM_CHAT_ID: (\S+)\s*$", log, re.MULTILINE))
                if len(commits) != 1 or len(channels) != 1:
                    raise ValueError("Cannot identify the source of a confirmed legacy Telegram delivery")
                if channel_key(channels.pop()) == channel:
                    return commits.pop()
        if len(runs) < 100:
            return fallback
    raise ValueError("Legacy Telegram history exceeds the inspection budget")


def record_delivery(receipt: dict, delivery_url: str) -> int:
    commit = receipt.get("source_commit", "")
    if (not confirmed_receipt(receipt)
        or not re.fullmatch(r"https://github\.com/" + re.escape(REPOSITORY) + r"/actions/runs/[1-9]\d*", delivery_url)):
        raise ValueError("Invalid confirmed Telegram delivery receipt")
    payload = dict(receipt, schema=1, channel=channel_key(receipt["channel"]), delivery_run_url=delivery_url)
    deployment = github("deployments", {
        "ref": commit, "task": TASK, "environment": ENVIRONMENT,
        "auto_merge": False, "required_contexts": [], "production_environment": False,
        "description": "Confirmed Canary Telegram delivery", "payload": payload,
    })
    if type(deployment.get("id")) is not int or deployment.get("sha") != commit:
        raise ValueError("GitHub did not record the selected Telegram source")
    github(f"deployments/{deployment['id']}/statuses", {
        "state": "success", "auto_inactive": False, "log_url": delivery_url,
        "description": f"Telegram message {receipt['document_message_id']}; complete announcement delivered",
    })
    return deployment["id"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--delivery-url", required=True)
    args = parser.parse_args()
    identity = record_delivery(json.loads(args.receipt.read_text(encoding="utf-8")), args.delivery_url)
    print(f"Recorded confirmed Telegram delivery checkpoint {identity}")


if __name__ == "__main__":
    main()
