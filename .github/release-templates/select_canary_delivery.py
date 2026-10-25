#!/usr/bin/env python3
"""Apply the daily Telegram policy without limiting explicit manual deliveries."""
from __future__ import annotations

import argparse
from datetime import datetime, time, timedelta, timezone
from pathlib import Path
from urllib.parse import urlencode

from prepare_canary_delivery import REPOSITORY, api_json, parse_run_url, select_artifact
from telegram_delivery_state import MAX_PAGES, channel_key, previous_delivery, previous_attempt_delivery

BEIJING = timezone(timedelta(hours=8))
WORKFLOW = ".github/workflows/canary-telegram.yml"


def timestamp(value: str) -> datetime:
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("Telegram policy requires a timestamp with an explicit timezone")
    return result.astimezone(BEIJING)


def manual_triggered_today(now: datetime) -> bool:
    start = datetime.combine(now.date(), time.min, BEIJING).astimezone(timezone.utc)
    for page in range(1, MAX_PAGES + 1):
        query = urlencode({"branch": "main", "event": "workflow_dispatch",
                           "created": ">=" + start.isoformat(), "per_page": 100, "page": page})
        runs = api_json(f"repos/{REPOSITORY}/actions/workflows/canary-telegram.yml/runs?{query}")["workflow_runs"]
        for run in runs:
            if (run.get("path") == WORKFLOW and run.get("head_branch") == "main"
                and run.get("event") == "workflow_dispatch"
                and timestamp(run["created_at"]).date() == now.date()):
                # 用户要求按手动触发计数；失败、取消和 dry-run 也不再自动补发。
                return True
        if len(runs) < 100:
            return False
    raise ValueError("Daily manual Telegram history exceeds the inspection budget")


def unchanged_build(previous: dict, run: dict, artifact: dict) -> bool:
    if not previous:
        return False
    source_url = previous.get("source_run_url", "")
    if source_url:
        if parse_run_url(source_url)[0] != run["id"]:
            return False
        if previous.get("source_commit") != run["head_sha"]:
            raise ValueError("Confirmed Telegram source disagrees with its Canary run")
        # 旧回执没有产物摘要，相同 run 先视为已发布，避免升级策略后重复发包。
        return not previous.get("source_artifact_digest") or previous["source_artifact_digest"] == artifact["digest"]
    return previous.get("source_commit") == run["head_sha"]


def select_delivery(event: str, run_url: str, delivery_run_id: int, now: datetime,
                    channel: str, expected_digest: str = "") -> dict[str, str]:
    if event == "workflow_dispatch":
        _, url = parse_run_url(run_url)
        current = api_json(f"repos/{REPOSITORY}/actions/runs/{delivery_run_id}")
        if (current.get("id") != delivery_run_id or current.get("path") != WORKFLOW
                or current.get("head_branch") != "main" or current.get("event") != event):
            raise ValueError("Manual publication must originate from the main Telegram workflow")
        attempt = current.get("run_attempt", 1)
        if type(attempt) is not int or not 1 <= attempt <= 100:
            raise ValueError("Invalid Telegram delivery attempt")
        if attempt > 1 and previous_attempt_delivery(channel, current):
            return {"should_publish": "false", "run_url": "", "artifact_digest": "",
                    "reason": "本次运行先前 attempt 已完整投递，跳过重试以避免重复发送。"}
        return {"should_publish": "true", "run_url": url, "artifact_digest": "",
                "reason": "手动发布：不受每日次数和重复构建限制。"}
    if event != "schedule":
        raise ValueError("Unsupported Telegram publication event")
    if now.tzinfo is None:
        raise ValueError("Telegram policy requires a timezone-aware current time")
    now = now.astimezone(BEIJING)
    channel = channel_key(channel)

    def skip(reason: str) -> dict[str, str]:
        return {"should_publish": "false", "run_url": "", "artifact_digest": "", "reason": reason}

    current = api_json(f"repos/{REPOSITORY}/actions/runs/{delivery_run_id}")
    if (current.get("id") != delivery_run_id or current.get("path") != WORKFLOW
        or current.get("head_branch") != "main" or current.get("event") != "schedule"):
        raise ValueError("Daily publication must originate from the main scheduled Telegram workflow")
    if timestamp(current["created_at"]).date() != now.date():
        return skip("定时任务已跨过北京时间日期边界，跳过过期任务。")
    if manual_triggered_today(now):
        return skip("北京时间今天已有手动触发，跳过定时发布。")
    previous = previous_delivery(channel, strict=True, active_run=current)
    if previous and timestamp(previous["delivered_at"]).date() == now.date():
        return skip("北京时间今天已有成功投递，跳过重复的定时发布。")

    if run_url:
        run_id, _ = parse_run_url(run_url)
    else:
        runs = api_json(f"repos/{REPOSITORY}/actions/workflows/canary-build.yml/runs"
                        "?branch=main&status=success&per_page=1")["workflow_runs"]
        if not runs:
            return skip("没有通过门禁的 main Canary 构建。")
        run_id = runs[0]["id"]
    run = api_json(f"repos/{REPOSITORY}/actions/runs/{run_id}")
    artifacts = api_json(f"repos/{REPOSITORY}/actions/runs/{run_id}/artifacts?per_page=100")
    artifact = select_artifact(run, artifacts, run_id)
    if expected_digest and artifact["digest"] != expected_digest:
        raise ValueError("Selected Canary artifact changed before Telegram delivery")
    if unchanged_build(previous, run, artifact):
        return skip("最新通过门禁的 Canary 构建与上次已发布构建完全一致。")
    return {"should_publish": "true", "run_url": parse_run_url(run["html_url"])[1],
            "artifact_digest": artifact["digest"], "reason": "今日未手动触发且 Canary 构建有更新，允许定时发布。"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", required=True, choices=["schedule", "workflow_dispatch"])
    parser.add_argument("--run-url", default="")
    parser.add_argument("--delivery-run-id", required=True, type=int)
    parser.add_argument("--channel", required=True)
    parser.add_argument("--expected-artifact-digest", default="")
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    result = select_delivery(args.event, args.run_url, args.delivery_run_id, datetime.now(timezone.utc),
                             args.channel, args.expected_artifact_digest)
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            for key, value in result.items():
                output.write(f"{key}={value}\n")
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as output:
            output.write("\n### Canary Telegram 发布判断\n\n" + result["reason"] + "\n")
            if result["run_url"]:
                output.write("\n所选构建：" + result["run_url"] + "\n")
    print(result["reason"])


if __name__ == "__main__":
    main()
