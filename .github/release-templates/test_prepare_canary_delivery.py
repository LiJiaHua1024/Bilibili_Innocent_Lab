import copy
import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

from prepare_canary_delivery import REPOSITORY, parse_run_url, select_artifact, inspect_archive


class CanarySelectionTest(unittest.TestCase):
    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        sha = "a" * 40
        artifact_name = "Bilibili_Innocent_Lab-v1.2.1-canary.3-aaaaaaaa"
        apk_name = artifact_name + ".apk"
        apk = b"unchanged selected APK"
        info = "\n".join([
            "release_channel=canary", "release_tag=v1.2.1-canary.3", "source_ref=refs/heads/main",
            "source_commit=" + sha, "project_base_version=1.2.0", "apk_version_code=21",
            "apk_package_name=com.Bilibili_Innocent_Lab.xposedmodule", "apk_version_name=1.2.1-canary.3",
            "apk_build_type=release", "apk_debuggable=false", "apk_filename=" + apk_name,
            "apk_sha256=" + hashlib.sha256(apk).hexdigest(), "apk_signer_certificate_sha256=" + "b" * 64,
        ]).encode()
        files = {apk_name: apk, "BUILD_INFO.txt": info, "CANARY_CHANGELOG.md": b"# Canary\n\n- selected changes\n"}
        files["SHA256SUMS.txt"] = "\n".join(
            hashlib.sha256(files[name]).hexdigest() + "  " + name for name in [apk_name, "BUILD_INFO.txt"]
        ).encode()
        archive = root / "package.zip"
        self.write_archive(archive, files)
        run = {"id": 7, "run_number": 3, "head_sha": sha, "head_branch": "main", "event": "push",
               "status": "completed", "conclusion": "success", "path": ".github/workflows/canary-build.yml",
               "repository": {"full_name": REPOSITORY}, "head_repository": {"full_name": REPOSITORY},
               "html_url": f"https://github.com/{REPOSITORY}/actions/runs/7"}
        artifact = {"id": 9, "name": artifact_name, "expired": False,
                    "workflow_run": {"id": 7, "head_sha": sha, "head_branch": "main"}}
        self.refresh_digest(archive, artifact)
        return root, archive, run, artifact, files

    def write_archive(self, path, files):
        with zipfile.ZipFile(path, "w") as bundle:
            for name, data in files.items():
                bundle.writestr(name, data)

    def refresh_digest(self, archive, artifact):
        artifact["size_in_bytes"] = archive.stat().st_size
        artifact["digest"] = "sha256:" + hashlib.sha256(archive.read_bytes()).hexdigest()

    def test_run_link_is_scoped_and_normalized(self):
        url = f"https://github.com/{REPOSITORY}/actions/runs/7"
        self.assertEqual((7, url), parse_run_url(" " + url + "/ "))
        for value in [url.replace("https:", "http:"), url.replace("github.com", "evil.invalid"),
                      url.replace("github.com", "user@github.com"), url.replace(REPOSITORY, "other/repo"),
                      url + "?next=other", url + "#fragment", url.replace("runs/7", "runs/0"),
                      url.replace("runs/7", "runs/9223372036854775808"), ""]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_run_url(value)

    def test_completed_canary_selects_one_signed_package_and_keeps_its_identity(self):
        root, archive, run, artifact, files = self.fixture()
        selected = select_artifact(run, {"artifacts": [artifact]}, 7)
        outputs = inspect_archive(archive, selected, run, root / "delivery")
        self.assertEqual("v1.2.1-canary.3", outputs["release_tag"])
        self.assertEqual(run["html_url"], outputs["source_run_url"])
        self.assertEqual("21", outputs["version_code"])
        self.assertEqual(files[outputs["apk_filename"]], (root / "delivery" / outputs["apk_filename"]).read_bytes())

    def test_failed_fork_and_non_canary_runs_are_rejected(self):
        _, _, run, artifact, _ = self.fixture()
        changes = [("id", 8), ("status", "in_progress"), ("conclusion", "failure"), ("head_branch", "next"),
                   ("event", "pull_request"), ("path", ".github/workflows/alpha-release.yml"), ("head_sha", "bad"),
                   ("head_repository", {"full_name": "fork/repo"}), ("repository", {"full_name": "fork/repo"})]
        for key, value in changes:
            changed = copy.deepcopy(run)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                select_artifact(changed, {"artifacts": [artifact]}, 7)

    def test_expired_mismatched_or_ambiguous_artifacts_are_rejected(self):
        _, _, run, artifact, _ = self.fixture()
        changes = [("expired", True), ("id", 0), ("size_in_bytes", 99_000_000), ("digest", "missing"),
                   ("name", artifact["name"].replace("canary.3", "canary.4")),
                   ("workflow_run", {"id": 8, "head_sha": run["head_sha"], "head_branch": "main"})]
        for key, value in changes:
            changed = copy.deepcopy(artifact)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                select_artifact(run, {"artifacts": [changed]}, 7)
        with self.assertRaises(ValueError):
            select_artifact(run, {"artifacts": [artifact, artifact]}, 7)

    def test_archive_digest_rejects_mutation_before_writing_files(self):
        root, archive, run, artifact, _ = self.fixture()
        archive.write_bytes(archive.read_bytes()[:-1] + b"x")
        with self.assertRaises(ValueError):
            inspect_archive(archive, artifact, run, root / "delivery")
        self.assertFalse((root / "delivery").exists())

    def test_archive_paths_checksums_and_provenance_are_independently_checked(self):
        for mutation in ["path", "checksum", "version", "source"]:
            root, archive, run, artifact, files = self.fixture()
            if mutation == "path":
                files["../outside.apk"] = b"not permitted"
            elif mutation == "checksum":
                files[artifact["name"] + ".apk"] = b"changed APK"
            else:
                files["BUILD_INFO.txt"] = files["BUILD_INFO.txt"].replace(
                    b"apk_version_code=21" if mutation == "version" else b"source_commit=" + b"a" * 40,
                    b"apk_version_code=invalid" if mutation == "version" else b"source_commit=" + b"c" * 40)
                files["SHA256SUMS.txt"] = "\n".join(
                    hashlib.sha256(files[name]).hexdigest() + "  " + name
                    for name in [artifact["name"] + ".apk", "BUILD_INFO.txt"]
                ).encode()
            self.write_archive(archive, files)
            self.refresh_digest(archive, artifact)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                inspect_archive(archive, artifact, run, root / "delivery")
            self.assertFalse((root / "delivery").exists())
