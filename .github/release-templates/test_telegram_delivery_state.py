import unittest
from unittest.mock import patch

from telegram_delivery_state import previous_commit, record_delivery, github, REPOSITORY


class TelegramDeliveryStateTest(unittest.TestCase):
    def receipt(self, commit="a" * 40):
        return {"source_commit": commit, "delivery_complete": True,
                "channel": "@Bilibili_Innocent_LabRelease", "document_message_id": 39,
                "announcement_message_ids": [40, 41],
                "delivery_run_url": f"https://github.com/{REPOSITORY}/actions/runs/9"}

    def test_only_complete_receipt_for_the_same_channel_is_a_checkpoint(self):
        deployments = [
            {"id": 3, "sha": "c" * 40, "environment": "canary-telegram", "task": "canary-telegram",
             "payload": dict(self.receipt("c" * 40), schema=1, channel="@other_channel")},
            {"id": 2, "sha": "b" * 40, "environment": "canary-telegram", "task": "canary-telegram",
             "payload": dict(self.receipt("b" * 40), schema=1, channel="@bilibili_innocent_labrelease", delivery_complete=False)},
            {"id": 1, "sha": "a" * 40, "environment": "canary-telegram", "task": "canary-telegram",
             "payload": dict(self.receipt(), schema=1, channel="@bilibili_innocent_labrelease")},
        ]
        with patch("telegram_delivery_state.github", side_effect=[deployments, {"workflow_runs": []}]) as api:
            self.assertEqual("a" * 40, previous_commit("@Bilibili_Innocent_LabRelease"))
        self.assertEqual(2, api.call_count)

    def test_newer_real_delivery_recovers_a_failed_checkpoint_write(self):
        checkpoint = {"id": 1, "sha": "a" * 40, "environment": "canary-telegram", "task": "canary-telegram",
                      "payload": dict(self.receipt(), schema=1, channel="@bilibili_innocent_labrelease")}
        run = {"id": 10, "conclusion": "failure", "path": ".github/workflows/canary-telegram.yml",
               "head_branch": "main", "event": "workflow_dispatch"}
        log = "SOURCE_COMMIT: " + "b" * 40 + "\nTELEGRAM_CHAT_ID: @Bilibili_Innocent_LabRelease\nTelegram delivered Canary APK, message ID 49\n"
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint], {"workflow_runs": [run]},
                   {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]}, log]):
            self.assertEqual("b" * 40, previous_commit("@Bilibili_Innocent_LabRelease"))

    def test_checkpoint_does_not_require_the_old_send_logs_to_remain_available(self):
        checkpoint = {"id": 1, "sha": "a" * 40, "environment": "canary-telegram", "task": "canary-telegram",
                      "payload": dict(self.receipt(), schema=1, channel="@bilibili_innocent_labrelease")}
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint], {"workflow_runs": [{"id": 9}]}]) as api:
            self.assertEqual("a" * 40, previous_commit("@Bilibili_Innocent_LabRelease"))
        self.assertEqual(2, api.call_count)

    def test_legacy_migration_uses_selected_source_and_skips_successful_dry_run(self):
        runs = [{"id": identity, "head_sha": "d" * 40, "path": ".github/workflows/canary-telegram.yml",
                 "head_branch": "main", "event": "workflow_dispatch"} for identity in [2, 1]]
        environment = "SOURCE_COMMIT: " + "a" * 40 + "\nTELEGRAM_CHAT_ID: @Bilibili_Innocent_LabRelease\n"
        job = {"total_count": 1, "jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]}
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": runs}, job,
                   environment + "Validated Telegram payload; dry-run only", job,
                   environment + "Telegram delivered Canary APK, message ID 39"]):
            self.assertEqual("a" * 40, previous_commit("@Bilibili_Innocent_LabRelease"))

    def test_delivery_from_failed_workflow_can_still_be_confirmed_by_its_send_result(self):
        run = {"id": 1, "conclusion": "failure", "head_branch": "main", "event": "workflow_dispatch",
               "path": ".github/workflows/canary-telegram.yml"}
        log = "SOURCE_COMMIT: " + "a" * 40 + "\nTELEGRAM_CHAT_ID: @Bilibili_Innocent_LabRelease\nTelegram delivered Canary APK, message ID 39\n"
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [run]},
                   {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]}, log]):
            self.assertEqual("a" * 40, previous_commit("@Bilibili_Innocent_LabRelease"))

    def test_no_deliveries_and_unreadable_history_are_distinct(self):
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": []}]):
            self.assertEqual("", previous_commit("@Bilibili_Innocent_LabRelease"))
        with patch("telegram_delivery_state.github", side_effect=ValueError("history unavailable")):
            with self.assertRaises(ValueError):
                previous_commit("@Bilibili_Innocent_LabRelease")

    def test_completed_receipt_records_selected_source_without_merging_or_moving_tags(self):
        with patch("telegram_delivery_state.github", side_effect=[{"id": 7, "sha": "a" * 40}, {"state": "success"}]) as api:
            identity = record_delivery(self.receipt(), f"https://github.com/{REPOSITORY}/actions/runs/9")
        self.assertEqual(7, identity)
        payload = api.call_args_list[0].args[1]
        self.assertEqual("a" * 40, payload["ref"])
        self.assertFalse(payload["auto_merge"])
        self.assertEqual([], payload["required_contexts"])
        self.assertEqual("@bilibili_innocent_labrelease", payload["payload"]["channel"])
        self.assertFalse(api.call_args_list[1].args[1]["auto_inactive"])

    def test_incomplete_delivery_never_writes_a_checkpoint(self):
        with patch("telegram_delivery_state.github") as api:
            with self.assertRaises(ValueError):
                record_delivery(dict(self.receipt(), delivery_complete=False), f"https://github.com/{REPOSITORY}/actions/runs/9")
        api.assert_not_called()

    def test_receipt_without_confirmed_announcement_messages_is_rejected(self):
        with patch("telegram_delivery_state.github") as api:
            with self.assertRaises(ValueError):
                record_delivery(dict(self.receipt(), announcement_message_ids=[]),
                                f"https://github.com/{REPOSITORY}/actions/runs/9")
        api.assert_not_called()

    def test_job_logs_use_the_actions_log_reader_that_handles_ansi(self):
        import subprocess
        result = subprocess.CompletedProcess([], 0, stdout=b"2026-01-01\nlog\n", stderr=b"")
        with patch("telegram_delivery_state.subprocess.run", return_value=result) as process:
            self.assertIn("log", github("actions/jobs/7/logs", raw=True))
        self.assertEqual(["gh", "run", "view", "--repo", REPOSITORY, "--job", "7", "--log"],
                         process.call_args.args[0])

    def test_workflow_generates_delivery_range_and_only_records_after_real_send(self):
        from pathlib import Path
        workflow = (Path(__file__).resolve().parents[1] / "workflows/canary-telegram.yml").read_text(encoding="utf-8")
        self.assertIn("fetch-depth: 0", workflow)
        self.assertIn("deployments: write", workflow)
        self.assertIn("prepare_telegram_notes.py", workflow)
        self.assertIn('--notes "$RUNNER_TEMP/TELEGRAM_CHANGELOG.txt"', workflow)
        self.assertIn("if: success() && !inputs.dry_run", workflow)
        self.assertLess(workflow.index("publish_telegram.py"), workflow.index("telegram_delivery_state.py"))
