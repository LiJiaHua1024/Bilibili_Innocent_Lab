"""Offline regression coverage for immutable Telegram delivery receipt recovery."""
import copy
import hashlib
import io
import json
import subprocess
import unittest
import warnings
import zipfile
from unittest.mock import patch

import telegram_delivery_state as state
from telegram_receipt import validate_receipt


class TelegramReceiptRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.delivery_id = 39143074972
        self.source_id = 39141475680
        self.delivery_url = f"https://github.com/{state.REPOSITORY}/actions/runs/{self.delivery_id}"
        self.source_url = f"https://github.com/{state.REPOSITORY}/actions/runs/{self.source_id}"
        self.commit = "80a21910a1ebc53edb1709a1a46a3365332e9230"
        self.digest = "sha256:" + "c" * 64
        self.run = {"id": self.delivery_id, "head_sha": "d" * 40, "head_branch": "main",
                    "path": ".github/workflows/canary-telegram.yml", "event": "workflow_dispatch",
                    "repository": {"full_name": state.REPOSITORY},
                    "head_repository": {"full_name": state.REPOSITORY}}
        self.job = {"id": 117721336044, "name": state.SEND_JOB, "run_id": self.delivery_id, "run_attempt": 1}
        self.step = {"name": state.SEND_STEP, "conclusion": "success",
                     "started_at": "2026-10-18T16:01:00Z", "completed_at": "2026-10-18T16:02:20Z"}
        self.source = {"id": self.source_id, "run_number": 80, "status": "completed", "conclusion": "success",
                       "head_sha": self.commit, "head_branch": "main", "event": "push",
                       "path": ".github/workflows/canary-build.yml", "html_url": self.source_url,
                       "repository": {"full_name": state.REPOSITORY},
                       "head_repository": {"full_name": state.REPOSITORY}}
        self.source_artifact = {"id": 12142000000, "name": f"Bilibili_Innocent_Lab-v1.2.1-canary.80-{self.commit[:8]}",
                                "expired": False, "size_in_bytes": 4000, "digest": self.digest,
                                "created_at": "2026-10-18T15:30:00Z", "updated_at": "2026-10-18T15:35:00Z",
                                "workflow_run": {"id": self.source_id, "head_sha": self.commit, "head_branch": "main"}}
        self.receipt = {"receipt_schema": 2, "delivery_run_url": self.delivery_url, "delivery_run_attempt": 1,
                        "source_artifact_digest": self.digest, "source_commit": self.commit,
                        "source_run_url": self.source_url, "release_tag": "v1.2.1-canary.80",
                        "apk_sha256": "b" * 64, "delivered_at": "2026-10-18T16:02:19.857Z",
                        "delivery_complete": True, "channel": "@Bilibili_Innocent_LabRelease",
                        "document_message_id": 64, "announcement_message_ids": [65]}
        self.responses = {}
        github = patch("telegram_delivery_state.github", side_effect=self.api)
        self.github = github.start()
        self.addCleanup(github.stop)
        command = patch("telegram_delivery_state.subprocess.run", side_effect=AssertionError("Unexpected external command"))
        self.command = command.start()
        self.addCleanup(command.stop)
        check_output = patch("subprocess.check_output", side_effect=AssertionError("Unexpected external command"))
        check_output.start()
        self.addCleanup(check_output.stop)
        sleep = patch("telegram_delivery_state.time.sleep", side_effect=AssertionError("Unexpected wait"))
        sleep.start()
        self.addCleanup(sleep.stop)

    def api(self, path, *args, **kwargs):
        # An unknown read or any attempted write fails locally rather than reaching gh.
        self.assertFalse(args, f"Unexpected API payload for {path}")
        self.assertFalse(kwargs, f"Unexpected API options for {path}")
        self.assertIn(path, self.responses, f"Unmocked API request: {path}")
        return copy.deepcopy(self.responses[path])

    def archive(self, entries):
        stream = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
                for name, value in entries:
                    bundle.writestr(name, value)
        return stream.getvalue()

    def install_archive(self, data, attempt=1):
        artifact = {"id": 12142820076, "name": f"canary-telegram-notes-{self.delivery_id}-{attempt}",
                    "expired": False, "size_in_bytes": len(data),
                    "digest": "sha256:" + hashlib.sha256(data).hexdigest(),
                    "workflow_run": {"id": self.delivery_id, "head_sha": self.run["head_sha"], "head_branch": "main"}}
        self.command.side_effect = None
        self.command.return_value = subprocess.CompletedProcess([], 0, stdout=data, stderr=b"")
        self.responses[f"actions/runs/{self.delivery_id}/artifacts?per_page=100"] = {"artifacts": [artifact]}
        self.responses[f"actions/runs/{self.source_id}"] = self.source
        self.responses[f"actions/runs/{self.source_id}/artifacts?per_page=100"] = {"artifacts": [self.source_artifact]}
        return artifact

    def install_receipt(self, receipt=None, attempt=1):
        return self.install_archive(self.archive([("telegram-delivery.json", json.dumps(self.receipt if receipt is None else receipt)),
                                                  ("TELEGRAM_CHANGELOG.txt", "Canary changes"),
                                                  ("TELEGRAM_NOTES_REPORT.md", "Verified notes")]), attempt)

    def recover(self, *, run=None, job=None, step=None):
        return state.recover_receipt(self.run if run is None else run,
                                     self.job if job is None else job,
                                     self.step if step is None else step)

    def legacy_receipt(self):
        return {key: value for key, value in self.receipt.items()
                if key not in {"receipt_schema", "delivery_run_url", "delivery_run_attempt", "source_artifact_digest", "delivered_at"}}

    def test_schema2_recovers_exact_sender_timestamp_without_completed_jobs_metadata(self):
        artifact = self.install_receipt()
        pending = {"name": state.SEND_STEP, "conclusion": None, "started_at": self.step["started_at"]}
        result = self.recover(step=pending)
        self.assertEqual(self.receipt, result)
        self.command.assert_called_once_with(["gh", "api", f"repos/{state.REPOSITORY}/actions/artifacts/{artifact['id']}/zip"],
                                              capture_output=True, timeout=60)
        self.assertEqual(3, self.github.call_count)
        self.assertFalse(any("jobs" in call.args[0] for call in self.github.call_args_list))

    def test_recovering_same_receipt_twice_is_read_only_and_identical(self):
        self.install_receipt()
        first, second = self.recover(), self.recover()
        self.assertEqual(self.receipt, first)
        self.assertEqual(first, second)
        self.assertEqual(6, self.github.call_count)
        self.assertTrue(all("deployments" not in call.args[0] for call in self.github.call_args_list))

    def test_legacy_receipt_uses_exact_successful_step_time(self):
        legacy = self.legacy_receipt()
        self.install_receipt(legacy)
        result = self.recover()
        self.assertEqual(dict(legacy, delivered_at=self.step["completed_at"], delivery_run_url=self.delivery_url,
                              delivery_run_attempt=1), result)
        self.assertNotIn("source_artifact_digest", result)

    def test_legacy_receipt_cannot_invent_time_from_pending_or_unsuccessful_step(self):
        self.install_receipt(self.legacy_receipt())
        for changes in [{"completed_at": None}, {"started_at": None}, {"conclusion": None},
                        {"conclusion": "failure"}, {"completed_at": "2026-10-18T15:59:00Z"}]:
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, "exact successful send time"):
                self.recover(step=dict(self.step, **changes))

    def test_legacy_receipt_rejects_source_artifact_replaced_after_send_started(self):
        self.install_receipt(self.legacy_receipt())
        for field in ["created_at", "updated_at"]:
            original = self.source_artifact[field]
            for value in ["2026-10-18T16:02:30Z", ""]:
                self.source_artifact[field] = value
                with self.subTest(field=field, value=value), self.assertRaisesRegex(ValueError, "original Canary artifact"):
                    self.recover()
            self.source_artifact[field] = original

    def test_schema2_receipt_cannot_belong_to_another_delivery_run_or_attempt(self):
        for changes in [{"delivery_run_url": self.delivery_url + "1"}, {"delivery_run_attempt": 2}]:
            self.install_receipt(dict(self.receipt, **changes))
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, "delivery attempt or source artifact"):
                self.recover()

    def test_attempt2_can_only_recover_its_own_named_artifact_and_receipt(self):
        receipt = dict(self.receipt, delivery_run_attempt=2)
        self.install_receipt(receipt, attempt=2)
        self.assertEqual({}, self.recover())
        self.command.assert_not_called()
        self.assertEqual(receipt, self.recover(run=dict(self.run, run_attempt=2), job=dict(self.job, run_attempt=2)))

    def test_job_or_workflow_repository_identity_mismatch_is_rejected_before_download(self):
        self.install_receipt()
        cases = [(self.run, dict(self.job, run_id=self.delivery_id + 1)),
                 (self.run, dict(self.job, run_attempt="1")),
                 (self.run, dict(self.job, run_attempt=0)),
                 (self.run, dict(self.job, run_attempt=2)),
                 (self.run, dict(self.job, name="Unrelated job")),
                 (dict(self.run, path=".github/workflows/other.yml"), self.job),
                 (dict(self.run, head_branch="topic"), self.job),
                 (dict(self.run, event="pull_request"), self.job),
                 (dict(self.run, repository={"full_name": "fork/repo"}), self.job),
                 (dict(self.run, head_repository={"full_name": "fork/repo"}), self.job),
                 (dict(self.run, head_sha="not-a-commit"), self.job)]
        for run, job in cases:
            with self.subTest(run=run, job=job), self.assertRaisesRegex(ValueError, "workflow identity"):
                self.recover(run=run, job=job)
        self.github.assert_not_called()
        self.command.assert_not_called()

    def test_source_commit_release_tag_and_digest_must_match_verified_build(self):
        for changes in [{"source_commit": "a" * 40}, {"release_tag": "v1.2.1-canary.81"},
                        {"source_artifact_digest": "sha256:" + "e" * 64}]:
            self.install_receipt(dict(self.receipt, **changes))
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, "verified Canary build"):
                self.recover()

    def test_source_run_response_cannot_substitute_a_different_run(self):
        receipt = dict(self.receipt, source_run_url=self.source_url + "1")
        self.install_receipt(receipt)
        other_id = int(receipt["source_run_url"].rsplit("/", 1)[1])
        self.responses[f"actions/runs/{other_id}"] = self.source
        self.responses[f"actions/runs/{other_id}/artifacts?per_page=100"] = {"artifacts": [self.source_artifact]}
        with self.assertRaisesRegex(ValueError, "successful main Canary build"):
            self.recover()

    def test_source_build_must_be_successful_main_canary_from_approved_repository(self):
        self.install_receipt()
        original = copy.deepcopy(self.source)
        for field, value in [("conclusion", "failure"), ("status", "in_progress"), ("head_branch", "topic"),
                             ("event", "pull_request"), ("path", ".github/workflows/other.yml"),
                             ("head_repository", {"full_name": "fork/repo"}), ("repository", {"full_name": "fork/repo"})]:
            self.source.clear()
            self.source.update(original, **{field: value})
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "successful main Canary build"):
                self.recover()

    def test_source_artifact_owner_digest_and_availability_are_verified(self):
        self.install_receipt()
        original = copy.deepcopy(self.source_artifact)
        for field, value in [("expired", True), ("id", 0), ("size_in_bytes", 0), ("digest", "not-a-digest"),
                             ("workflow_run", {"id": self.source_id + 1, "head_sha": self.commit, "head_branch": "main"})]:
            self.source_artifact.clear()
            self.source_artifact.update(original, **{field: value})
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "invalid provenance"):
                self.recover()

    def test_missing_source_artifact_or_ambiguous_source_artifacts_is_rejected(self):
        self.install_receipt()
        for artifacts in [[], [self.source_artifact, self.source_artifact]]:
            self.responses[f"actions/runs/{self.source_id}/artifacts?per_page=100"] = {"artifacts": artifacts}
            with self.subTest(artifacts=len(artifacts)), self.assertRaisesRegex(ValueError, "exactly one"):
                self.recover()

    def test_no_matching_receipt_artifact_returns_empty_without_downloading(self):
        artifact = self.install_receipt()
        artifact["name"] = f"canary-telegram-notes-{self.delivery_id}-2"
        self.assertEqual({}, self.recover())
        self.command.assert_not_called()

    def test_receipt_artifact_owner_digest_size_and_expiry_are_verified_before_download(self):
        artifact = self.install_receipt()
        original = copy.deepcopy(artifact)
        for field, value in [("id", 0), ("id", True), ("expired", True), ("size_in_bytes", 0),
                             ("size_in_bytes", 1024 * 1024 + 1), ("size_in_bytes", True), ("digest", "not-a-digest"),
                             ("workflow_run", {"id": self.delivery_id + 1, "head_sha": self.run["head_sha"], "head_branch": "main"}),
                             ("workflow_run", {"id": self.delivery_id, "head_sha": "a" * 40, "head_branch": "main"}),
                             ("workflow_run", {"id": self.delivery_id, "head_sha": self.run["head_sha"], "head_branch": "topic"})]:
            artifact.clear()
            artifact.update(original, **{field: value})
            with self.subTest(field=field, value=value), self.assertRaisesRegex(ValueError, "artifact provenance"):
                self.recover()
        self.command.assert_not_called()

    def test_receipt_artifact_list_must_be_unique_and_within_budget(self):
        artifact = self.install_receipt()
        path = f"actions/runs/{self.delivery_id}/artifacts?per_page=100"
        for response in [{"total_count": 101, "artifacts": [artifact]}, {"artifacts": [artifact, artifact]}]:
            self.responses[path] = response
            with self.subTest(response=response), self.assertRaises(ValueError):
                self.recover()
        self.command.assert_not_called()

    def test_incomplete_receipt_or_missing_announcements_is_never_recovered(self):
        for changes in [{"delivery_complete": False}, {"announcement_message_ids": []},
                        {"announcement_message_ids": None}, {"announcement_message_ids": [True]},
                        {"announcement_message_ids": [65, 65]}, {"announcement_message_ids": [64]},
                        {"document_message_id": 0}, {"document_message_id": True}]:
            self.install_receipt(dict(self.receipt, **changes))
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, "Incomplete Telegram"):
                self.recover()
        missing = dict(self.receipt)
        missing.pop("announcement_message_ids")
        self.install_receipt(missing)
        with self.assertRaisesRegex(ValueError, "Incomplete Telegram"):
            self.recover()

    def test_new_receipt_must_carry_its_own_timezone_aware_completion_time(self):
        for time in [None, "", "2026-10-18T16:02:19.857", "invalid"]:
            self.install_receipt(dict(self.receipt, delivered_at=time))
            with self.subTest(time=time), self.assertRaises(ValueError):
                self.recover()
        receipt = dict(self.receipt)
        receipt.pop("delivered_at")
        self.install_receipt(receipt)
        with self.assertRaises(ValueError):
            self.recover()

    def test_receipt_timestamp_must_be_within_known_send_step(self):
        for time in ["2026-10-18T16:00:59Z", "2026-10-18T16:02:21Z"]:
            self.install_receipt(dict(self.receipt, delivered_at=time))
            with self.subTest(time=time), self.assertRaisesRegex(ValueError, "outside its send step"):
                self.recover()

    def test_notes_without_receipt_do_not_claim_a_delivery(self):
        self.install_archive(self.archive([("TELEGRAM_CHANGELOG.txt", "notes only")]))
        self.assertEqual({}, self.recover())
        self.assertEqual(1, self.github.call_count)

    def test_receipt_archive_checks_download_exit_size_and_digest(self):
        data = self.archive([("telegram-delivery.json", json.dumps(self.receipt))])
        artifact = self.install_archive(data)
        for result in [subprocess.CompletedProcess([], 1, stdout=data, stderr=b"failed"),
                       subprocess.CompletedProcess([], 0, stdout=data[:-1], stderr=b""),
                       subprocess.CompletedProcess([], 0, stdout=b"x" + data[1:], stderr=b"")]:
            self.command.return_value = result
            with self.subTest(returncode=result.returncode, size=len(result.stdout)), self.assertRaisesRegex(ValueError, "size or digest mismatch"):
                state.receipt_archive(artifact)

    def test_receipt_archive_rejects_duplicate_unexpected_empty_or_oversized_entries(self):
        receipt_json = json.dumps(self.receipt)
        cases = [[("telegram-delivery.json", receipt_json), ("telegram-delivery.json", receipt_json)],
                 [("../telegram-delivery.json", receipt_json)], [("telegram-delivery.json", "")],
                 [("telegram-delivery.json", "x" * (256 * 1024 + 1))],
                 [("telegram-delivery.json", receipt_json), ("TELEGRAM_CHANGELOG.txt", "notes"),
                  ("TELEGRAM_NOTES_REPORT.md", "report"), ("extra.txt", "unexpected")]]
        for entries in cases:
            artifact = self.install_archive(self.archive(entries))
            with self.subTest(names=[name for name, _ in entries]), self.assertRaisesRegex(ValueError, "Unexpected Telegram receipt archive entries"):
                state.receipt_archive(artifact)

    def test_receipt_archive_rejects_non_object_receipt_and_malformed_json(self):
        for text in ["[]", "null", "false", "\"receipt\"", "not json"]:
            artifact = self.install_archive(self.archive([("telegram-delivery.json", text)]))
            with self.subTest(text=text), self.assertRaises(ValueError):
                state.receipt_archive(artifact)

    def test_validate_receipt_checks_schema_attempt_url_digest_and_build_fields(self):
        validate_receipt(self.receipt, self.delivery_url, 1, self.digest)
        for changes in [{"receipt_schema": 1}, {"delivery_run_attempt": 2}, {"delivery_run_url": self.delivery_url + "1"},
                        {"receipt_schema": 2.0}, {"delivery_run_attempt": True},
                        {"source_artifact_digest": "sha256:" + "e" * 64}, {"apk_sha256": "bad"},
                        {"apk_sha256": None}, {"release_tag": None}, {"source_run_url": None},
                        {"release_tag": "v1.2.1"}, {"source_run_url": "https://github.com/fork/repo/actions/runs/7"}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_receipt(dict(self.receipt, **changes), self.delivery_url, 1, self.digest)

    def test_validate_receipt_checks_expected_identity_before_accepting_receipt(self):
        for url, attempt, digest in [(self.delivery_url, 0, self.digest), (self.delivery_url, True, self.digest),
                                     (self.delivery_url, 101, self.digest), (self.delivery_url, 1, "bad"),
                                     ("https://github.com/fork/repo/actions/runs/7", 1, self.digest)]:
            with self.subTest(url=url, attempt=attempt, digest=digest), self.assertRaises(ValueError):
                validate_receipt(self.receipt, url, attempt, digest)


if __name__ == "__main__":
    unittest.main()
