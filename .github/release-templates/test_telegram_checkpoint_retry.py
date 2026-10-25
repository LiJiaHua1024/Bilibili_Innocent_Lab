import copy
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

from telegram_delivery_state import (SEND_JOB, SEND_STEP, REPOSITORY, completed_send, record_delivery,
                                     previous_attempt_delivery, previous_delivery)
from select_canary_delivery import select_delivery, unchanged_build


class TelegramCheckpointRetryTest(unittest.TestCase):
    def setUp(self):
        self.url = f"https://github.com/{REPOSITORY}/actions/runs/9"
        self.digest = "sha256:" + "c" * 64
        self.receipt = {"receipt_schema": 2, "delivery_run_url": self.url, "delivery_run_attempt": 1,
                        "delivery_complete": True, "source_commit": "a" * 40, "channel": "@test_channel",
                        "release_tag": "v1.2.1-canary.80", "source_run_url": self.url.replace("/9", "/8"),
                        "source_artifact_digest": self.digest, "apk_sha256": "b" * 64,
                        "document_message_id": 64, "announcement_message_ids": [65],
                        "delivered_at": "2026-10-18T16:02:19Z"}
        self.run = {"id": 9, "path": ".github/workflows/canary-telegram.yml", "head_branch": "main",
                    "event": "workflow_dispatch", "run_attempt": 2}
        self.job = {"id": 7, "name": SEND_JOB, "run_id": 9, "run_attempt": 1,
                    "steps": [{"name": SEND_STEP, "conclusion": "success",
                               "started_at": "2026-10-18T16:02:11Z", "completed_at": self.receipt["delivered_at"]}]}

    def test_new_receipt_records_real_completion_without_reading_jobs_api(self):
        writes = []
        def api(path, payload=None):
            self.assertNotIn("jobs", path)
            if payload is None:
                self.assertTrue(path.startswith("deployments?"))
                return []
            writes.append((path, payload))
            return {"id": 71, "sha": "a" * 40}
        with patch("telegram_delivery_state.github", side_effect=api):
            self.assertEqual(71, record_delivery(self.receipt, self.url, artifact_digest=self.digest))
        self.assertEqual(self.receipt["delivered_at"], writes[0][1]["payload"]["delivered_at"])

    def test_status_failure_and_repeated_execution_reuse_the_same_deployment(self):
        rows = []
        creates = []
        statuses = []
        def api(path, payload=None):
            if payload is None:
                self.assertTrue(path.startswith("deployments?"))
                return copy.deepcopy(rows)
            if path == "deployments":
                creates.append(payload)
                row = {"id": 71, "sha": "a" * 40, "environment": "canary-telegram", "task": "canary-telegram",
                       "payload": payload["payload"]}
                rows.append(row)
                return row
            statuses.append(payload)
            if len(statuses) == 1:
                raise ValueError("fixture status write failed")
            return {}
        with patch("telegram_delivery_state.github", side_effect=api):
            with self.assertRaisesRegex(ValueError, "status write"):
                record_delivery(self.receipt, self.url, artifact_digest=self.digest)
            for _ in range(2):
                self.assertEqual(71, record_delivery(self.receipt, self.url, artifact_digest=self.digest))
        self.assertEqual(1, len(creates))

    def test_conflicting_existing_checkpoint_is_not_overwritten_or_duplicated(self):
        payload = dict(self.receipt, schema=1)
        for field, value in [("source_commit", "f" * 40), ("source_artifact_digest", "sha256:" + "f" * 64),
                             ("announcement_message_ids", [66]), ("document_message_id", 99),
                             ("source_run_url", self.url), ("delivered_at", "2026-10-18T17:00:00Z")]:
            row = {"id": 71, "sha": "a" * 40, "environment": "canary-telegram", "task": "canary-telegram",
                   "payload": dict(payload, **{field: value})}
            with self.subTest(field=field), patch("telegram_delivery_state.github", return_value=[row]) as api:
                with self.assertRaisesRegex(ValueError, "Conflicting"):
                    record_delivery(self.receipt, self.url, artifact_digest=self.digest)
                self.assertEqual(1, api.call_count)

    def test_mismatching_or_incomplete_new_receipt_never_writes(self):
        for field, value in [("receipt_schema", 1), ("receipt_schema", 2.0), ("delivery_run_attempt", True),
                             ("delivery_run_attempt", 2), ("delivery_run_url", self.url.replace("/9", "/10")),
                             ("source_artifact_digest", "sha256:" + "d" * 64), ("apk_sha256", ""),
                             ("delivered_at", ""), ("delivered_at", "2026-10-18T16:02:19"),
                             ("delivery_complete", False), ("announcement_message_ids", []),
                             ("announcement_message_ids", [65, 65]), ("announcement_message_ids", [64])]:
            with self.subTest(field=field, value=value), patch("telegram_delivery_state.github") as api:
                with self.assertRaises(ValueError):
                    record_delivery(dict(self.receipt, **{field: value}), self.url, artifact_digest=self.digest)
                api.assert_not_called()

    def test_legacy_metadata_delay_then_exact_success_recovers_without_using_current_time(self):
        legacy = {k: v for k, v in self.receipt.items() if k not in
                  {"receipt_schema", "delivered_at", "source_artifact_digest", "delivery_run_attempt", "apk_sha256"}}
        waiting = copy.deepcopy(self.job)
        waiting["steps"][0].update(conclusion=None, completed_at=None)
        replies = [{"jobs": []}, {"jobs": [waiting]}, {"jobs": [self.job]}, [], {"id": 71, "sha": "a" * 40}, {}]
        with patch("telegram_delivery_state.github", side_effect=replies) as api, patch("telegram_delivery_state.time.sleep") as sleep:
            self.assertEqual(71, record_delivery(legacy, self.url))
        self.assertEqual([2, 4], [call.args[0] for call in sleep.call_args_list])
        self.assertEqual(self.receipt["delivered_at"], api.call_args_list[4].args[1]["payload"]["delivered_at"])

    def test_legacy_missing_or_wrong_attempt_times_out_without_writes(self):
        wrong = dict(self.job, run_attempt=2)
        for jobs in [[], [wrong], [dict(self.job, run_id=10)]]:
            with self.subTest(jobs=jobs), patch("telegram_delivery_state.github", return_value={"jobs": jobs}) as api, \
                    patch("telegram_delivery_state.time.sleep"), self.assertRaisesRegex(ValueError, "actual Telegram send"):
                completed_send(9, 1)
            self.assertEqual(5, api.call_count)
            self.assertTrue(all(len(call.args) == 1 for call in api.call_args_list))

    def test_duplicate_job_or_step_and_invalid_time_are_rejected_immediately(self):
        duplicate_step = copy.deepcopy(self.job)
        duplicate_step["steps"] *= 2
        backwards = copy.deepcopy(self.job)
        backwards["steps"][0]["started_at"] = "2026-10-18T17:00:00Z"
        for jobs in [[self.job, dict(self.job, id=8)], [duplicate_step], [backwards]]:
            with self.subTest(jobs=jobs), patch("telegram_delivery_state.github", return_value={"jobs": jobs}) as api:
                with self.assertRaises(ValueError):
                    completed_send(9, 1)
                self.assertEqual(1, api.call_count)

    def test_manual_retry_skips_complete_prior_delivery(self):
        with patch("select_canary_delivery.api_json", return_value=self.run), \
                patch("telegram_delivery_state.github", return_value={"jobs": [self.job]}), \
                patch("telegram_delivery_state.recover_receipt", return_value=self.receipt):
            result = select_delivery("workflow_dispatch", self.receipt["source_run_url"], 9,
                                     datetime.now(timezone.utc), self.receipt["channel"])
        self.assertEqual("false", result["should_publish"])

    def test_manual_retry_missing_previous_jobs_is_not_authorized(self):
        with patch("select_canary_delivery.api_json", return_value=self.run), \
                patch("telegram_delivery_state.github", return_value={"jobs": []}), \
                self.assertRaisesRegex(ValueError, "previous attempt jobs unavailable"):
            select_delivery("workflow_dispatch", self.receipt["source_run_url"], 9,
                            datetime.now(timezone.utc), self.receipt["channel"])

    def test_manual_retry_unknown_or_partial_send_is_blocked(self):
        log = "\n".join(f"{SEND_JOB}\t{SEND_STEP}\t2026-10-18T16:02:19Z {line}" for line in [
            "SOURCE_COMMIT: " + "a" * 40, "TELEGRAM_CHAT_ID: @test_channel", "TELEGRAM_DELIVERY_PROTOCOL: 3",
            "TELEGRAM_POST_ATTEMPT: sendDocument", "TELEGRAM_POST_ATTEMPT: sendMessage"])
        with patch("telegram_delivery_state.github", side_effect=[{"jobs": [self.job]}, log]), \
                patch("telegram_delivery_state.recover_receipt", return_value={}), self.assertRaisesRegex(ValueError, "Unconfirmed Telegram"):
            previous_attempt_delivery(self.receipt["channel"], self.run)

    def test_modern_success_log_without_a_bound_complete_receipt_is_not_confirmation(self):
        log = f"{SEND_JOB}\t{SEND_STEP}\t2026-10-18T16:02:19Z TELEGRAM_DELIVERY_PROTOCOL: 3\n"
        log += f"{SEND_JOB}\t{SEND_STEP}\t2026-10-18T16:02:19Z Telegram delivered Canary APK, message ID 64"
        with patch("telegram_delivery_state.github", side_effect=[{"jobs": [self.job]}, log]), \
                patch("telegram_delivery_state.recover_receipt", return_value={}), self.assertRaisesRegex(ValueError, "bound receipt unavailable"):
            previous_attempt_delivery(self.receipt["channel"], self.run)

    def test_skipped_prior_job_does_not_read_an_archive_or_logs(self):
        with patch("telegram_delivery_state.github", return_value={"jobs": [dict(self.job, conclusion="skipped", steps=[])]}) as api, \
                patch("telegram_delivery_state.recover_receipt") as archive:
            self.assertEqual({}, previous_attempt_delivery(self.receipt["channel"], self.run))
        self.assertEqual(1, api.call_count)
        archive.assert_not_called()

    def test_recovered_receipt_prevents_unchanged_build_on_a_later_day(self):
        run = dict(self.run, run_attempt=1, updated_at="2026-10-19T16:00:00Z")
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [run]}, {"jobs": [self.job]}]), \
                patch("telegram_delivery_state.recover_receipt", return_value=self.receipt):
            recovered = previous_delivery(self.receipt["channel"], strict=True)
        self.assertTrue(unchanged_build(recovered, {"id": 8, "head_sha": "a" * 40}, {"digest": self.digest}))

    def test_old_attempt_below_a_newer_checkpoint_does_not_read_expired_artifact(self):
        payload = dict(self.receipt, schema=1, channel="@test_channel", delivered_at="2026-10-19T16:02:19Z",
                       delivery_run_url=self.url.replace("/9", "/10"))
        row = {"id": 71, "sha": "a" * 40, "environment": "canary-telegram", "task": "canary-telegram", "payload": payload}
        run = dict(self.run, run_attempt=1, updated_at="2026-10-20T16:00:00Z")
        with patch("telegram_delivery_state.github", side_effect=[[row], {"workflow_runs": [run]}, {"jobs": [self.job]}]), \
                patch("telegram_delivery_state.recover_receipt") as archive:
            self.assertEqual(payload, previous_delivery(self.receipt["channel"], strict=True))
        archive.assert_not_called()
