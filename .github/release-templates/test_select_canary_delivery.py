import copy
from datetime import datetime
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

from prepare_canary_delivery import REPOSITORY
from select_canary_delivery import manual_triggered_today, select_delivery, timestamp, unchanged_build


class DailyCanaryDeliveryTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime.fromisoformat("2026-10-16T14:00:00+00:00")
        self.current = {"id": 90, "path": ".github/workflows/canary-telegram.yml", "head_branch": "main",
                        "event": "schedule", "created_at": "2026-10-16T14:00:00Z"}
        self.run = {"id": 7, "run_number": 3, "head_sha": "a" * 40, "head_branch": "main", "event": "push",
                    "status": "completed", "conclusion": "success", "path": ".github/workflows/canary-build.yml",
                    "repository": {"full_name": REPOSITORY}, "head_repository": {"full_name": REPOSITORY},
                    "html_url": f"https://github.com/{REPOSITORY}/actions/runs/7"}
        self.artifact = {"id": 9, "name": "Bilibili_Innocent_Lab-v1.2.1-canary.3-aaaaaaaa", "expired": False,
                         "size_in_bytes": 100, "digest": "sha256:" + "b" * 64,
                         "workflow_run": {"id": 7, "head_sha": "a" * 40, "head_branch": "main"}}
        self.previous = {}
        self.manual = []
        self.builds = [self.run]
        self.paths = []

    def api(self, path):
        self.paths.append(path)
        root = f"repos/{REPOSITORY}/actions/"
        if path == root + "runs/90":
            return self.current
        if path.startswith(root + "workflows/canary-telegram.yml/runs?"):
            query = parse_qs(urlsplit(path).query)
            self.assertEqual(["workflow_dispatch"], query["event"])
            self.assertEqual([">=2026-10-15T16:00:00+00:00"], query["created"])
            return {"workflow_runs": self.manual}
        if path.startswith(root + "workflows/canary-build.yml/runs?"):
            self.assertIn("branch=main&status=success&per_page=1", path)
            return {"workflow_runs": self.builds}
        if path == root + "runs/7":
            return self.run
        if path == root + "runs/7/artifacts?per_page=100":
            return {"artifacts": [self.artifact]}
        raise AssertionError("Unexpected request: " + path)

    def decision(self, run_url="", expected_digest=""):
        with patch("select_canary_delivery.api_json", side_effect=self.api), \
             patch("select_canary_delivery.previous_delivery", return_value=self.previous):
            return select_delivery("schedule", run_url, 90, self.now, "@Bilibili_Innocent_LabRelease", expected_digest)

    def previous_build(self, **changes):
        return dict({"source_commit": self.run["head_sha"], "source_run_url": self.run["html_url"],
                     "source_artifact_digest": self.artifact["digest"], "delivered_at": "2026-10-15T14:00:00Z"},
                    **changes)

    def test_manual_deliveries_bypass_daily_and_unchanged_checks(self):
        with patch("select_canary_delivery.api_json", return_value=dict(self.current, event="workflow_dispatch")) as api, patch("select_canary_delivery.previous_delivery") as history:
            for _ in range(3):
                result = select_delivery("workflow_dispatch", self.run["html_url"], 90, self.now, "@test_channel")
                self.assertEqual("true", result["should_publish"])
        self.assertEqual(3, api.call_count)
        history.assert_not_called()

    def test_manual_link_validation_is_preserved(self):
        with self.assertRaises(ValueError):
            select_delivery("workflow_dispatch", "https://example.org/runs/7", 90, self.now, "@test_channel")

    def test_first_scheduled_delivery_selects_latest_successful_main_canary(self):
        result = self.decision()
        self.assertEqual("true", result["should_publish"])
        self.assertEqual(self.run["html_url"], result["run_url"])
        self.assertEqual(self.artifact["digest"], result["artifact_digest"])

    def test_any_manual_trigger_today_blocks_schedule_regardless_of_result(self):
        for status in ["queued", "in_progress", "failure", "cancelled", "success", "dry_run"]:
            self.manual = [{"id": 80, "event": "workflow_dispatch", "head_branch": "main", "path": self.current["path"],
                            "created_at": "2026-10-15T16:00:00Z", "status": status}]
            with self.subTest(status=status):
                self.assertEqual("false", self.decision()["should_publish"])
        self.assertFalse(any("canary-build.yml" in path for path in self.paths))

    def test_yesterday_manual_and_other_branch_do_not_block_schedule(self):
        self.manual = [dict(self.current, event="workflow_dispatch", created_at="2026-10-15T15:59:59Z"),
                       dict(self.current, event="workflow_dispatch", head_branch="next")]
        self.assertEqual("true", self.decision()["should_publish"])

    def test_today_success_blocks_schedule_even_if_new_build_is_available(self):
        self.previous = self.previous_build(source_commit="c" * 40, delivered_at="2026-10-15T16:00:00Z")
        self.assertEqual("false", self.decision()["should_publish"])
        self.assertFalse(any("canary-build.yml" in path for path in self.paths))

    def test_same_build_on_later_days_is_not_automatically_resent(self):
        self.previous = self.previous_build()
        self.assertEqual("false", self.decision()["should_publish"])
        self.previous["delivered_at"] = "2026-09-01T00:00:00Z"
        self.assertEqual("false", self.decision()["should_publish"])

    def test_rebuilt_source_with_new_run_is_a_changed_build(self):
        self.previous = self.previous_build(source_run_url=self.run["html_url"].replace("runs/7", "runs/6"))
        self.assertEqual("true", self.decision()["should_publish"])

    def test_same_run_with_changed_artifact_is_publishable(self):
        self.previous = self.previous_build(source_artifact_digest="sha256:" + "c" * 64)
        self.assertEqual("true", self.decision()["should_publish"])

    def test_old_receipt_without_digest_does_not_repeat_the_same_run(self):
        self.previous = self.previous_build()
        self.previous.pop("source_artifact_digest")
        self.assertEqual("false", self.decision()["should_publish"])

    def test_legacy_commit_only_receipt_conservatively_skips_identical_source(self):
        self.previous = {"source_commit": self.run["head_sha"], "delivered_at": "2026-10-15T14:00:00Z"}
        self.assertEqual("false", self.decision()["should_publish"])

    def test_no_successful_build_skips_without_download(self):
        self.builds = []
        self.assertEqual("false", self.decision()["should_publish"])
        self.assertFalse(any("artifacts?" in path for path in self.paths))

    def test_unsafe_or_expired_candidate_fails_before_sending(self):
        for key, value in [("expired", True), ("digest", "missing")]:
            original = copy.deepcopy(self.artifact)
            self.artifact[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.decision()
            self.artifact = original
        self.run["conclusion"] = "failure"
        with self.assertRaises(ValueError):
            self.decision()

    def test_unreadable_history_does_not_authorize_publication(self):
        with patch("select_canary_delivery.api_json", side_effect=self.api), \
             patch("select_canary_delivery.previous_delivery", side_effect=ValueError("history unavailable")):
            with self.assertRaisesRegex(ValueError, "history unavailable"):
                select_delivery("schedule", "", 90, self.now, "@test_channel")

    def test_delayed_run_or_next_day_rerun_does_not_publish(self):
        self.current["created_at"] = "2026-10-15T15:59:59Z"
        self.assertEqual("false", self.decision()["should_publish"])

    def test_non_main_or_non_scheduled_current_run_is_rejected(self):
        for key, value in [("head_branch", "next"), ("event", "push")]:
            original = dict(self.current)
            self.current[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.decision()
            self.current = original

    def test_recheck_notices_manual_trigger_during_apk_verification(self):
        self.assertEqual("true", self.decision()["should_publish"])
        self.manual = [dict(self.current, id=91, event="workflow_dispatch")]
        self.assertEqual("false", self.decision(self.run["html_url"], self.artifact["digest"])["should_publish"])

    def test_artifact_replacement_after_selection_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "artifact changed"):
            self.decision(self.run["html_url"], "sha256:" + "c" * 64)

    def test_unknown_post_result_is_not_automatically_retried_on_the_next_day(self):
        failed = {"id": 89, "path": self.current["path"], "head_branch": "main", "event": "schedule",
                  "conclusion": "failure", "run_attempt": 1, "updated_at": "2026-10-15T14:01:00Z"}
        prefix = "Verify selected Canary and send its APK\tSend the selected Canary APK to Telegram\t"
        lines = ["2026-10-15T14:00:00Z SOURCE_COMMIT: " + self.run["head_sha"],
                 "2026-10-15T14:00:00Z TELEGRAM_CHAT_ID: @Bilibili_Innocent_LabRelease",
                 "2026-10-15T14:00:00Z SOURCE_RUN_URL: " + self.run["html_url"],
                 "2026-10-15T14:00:00Z SOURCE_ARTIFACT_DIGEST: " + self.artifact["digest"],
                 "2026-10-15T14:00:30Z TELEGRAM_POST_ATTEMPT: sendDocument",
                 "2026-10-15T14:01:00Z Telegram delivery failed: Telegram network/protocol failure; check delivery before retrying"]
        with patch("select_canary_delivery.api_json", side_effect=self.api), \
             patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [failed]},
                   {"jobs": [{"id": 5, "name": "Verify selected Canary and send its APK"}]},
                   "\n".join(prefix + line for line in lines)]), \
             patch("telegram_delivery_state.recover_receipt", return_value={}):
            with self.assertRaisesRegex(ValueError, "Unconfirmed Telegram"):
                select_delivery("schedule", "", 90, self.now, "@Bilibili_Innocent_LabRelease")

    def test_checkpoint_created_after_midnight_uses_actual_send_step_date(self):
        source = "c" * 40
        receipt = {"schema": 1, "channel": "@bilibili_innocent_labrelease", "source_commit": source,
                   "delivery_complete": True, "document_message_id": 7, "announcement_message_ids": [8],
                   "delivery_run_url": f"https://github.com/{REPOSITORY}/actions/runs/88",
                   "delivered_at": "2026-10-15T15:59:59Z", "delivery_run_attempt": 1}
        deployment = {"id": 1, "sha": source, "environment": "canary-telegram", "task": "canary-telegram",
                      "payload": receipt, "created_at": "2026-10-15T16:00:01Z"}
        with patch("select_canary_delivery.api_json", side_effect=self.api), \
             patch("telegram_delivery_state.github", side_effect=[[deployment], {"workflow_runs": [{"id": 88}]}]):
            result = select_delivery("schedule", "", 90, self.now, "@Bilibili_Innocent_LabRelease")
        self.assertEqual("true", result["should_publish"])

    def test_manual_history_is_paginated(self):
        previous_day = dict(self.current, event="workflow_dispatch", created_at="2026-10-15T15:59:59Z")
        today = dict(self.current, event="workflow_dispatch")
        with patch("select_canary_delivery.api_json", side_effect=[{"workflow_runs": [previous_day] * 100},
                                                                 {"workflow_runs": [today]}]) as api:
            self.assertTrue(manual_triggered_today(timestamp("2026-10-16T14:00:00Z")))
        self.assertIn("page=2", api.call_args.args[0])

    def test_timestamps_must_be_explicit_and_source_identity_must_agree(self):
        with self.assertRaises(ValueError):
            timestamp("2026-10-16T14:00:00")
        with self.assertRaises(ValueError):
            unchanged_build(self.previous_build(source_commit="c" * 40), self.run, self.artifact)

    def test_workflow_wires_schedule_shared_lock_and_final_send_guard(self):
        workflow = (Path(__file__).resolve().parents[1] / "workflows/canary-telegram.yml").read_text(encoding="utf-8")
        self.assertIn("cron: '0 14 * * *'", workflow)
        self.assertIn("group: canary-telegram\n  cancel-in-progress: false", workflow)
        self.assertIn("if: needs.select.outputs.should_publish == 'true'", workflow)
        self.assertIn("if: steps.eligibility.outputs.should_publish == 'true'", workflow)
        self.assertIn("steps.delivery.outcome == 'success' && !inputs.dry_run", workflow)
        self.assertIn('--expected-artifact-digest "$EXPECTED_ARTIFACT_DIGEST"', workflow)
        self.assertLess(workflow.index("Recheck daily publication policy"), workflow.index("publish_telegram.py"))
