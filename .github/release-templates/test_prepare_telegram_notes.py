import subprocess
import tempfile
import unittest
from pathlib import Path

from prepare_telegram_notes import generate


class TelegramNotesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.invalid")
        self.before = self.commit("feat: previous published change")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, text=True, encoding="utf-8").strip()

    def commit(self, subject):
        self.git("commit", "--allow-empty", "-qm", subject)
        return self.git("rev-parse", "HEAD")

    def test_all_intervening_commits_survive_the_old_twenty_twelve_and_four_limits(self):
        for index in range(25):
            head = self.commit(f"feat: change-{index:02d}")
        notes = generate(self.repo, "v1.2.1-canary.3", head, self.before)
        self.assertEqual(25, sum(line.startswith("- ") for line in notes.splitlines()))
        self.assertNotIn("previous published change (", notes)
        for index in range(25):
            self.assertIn(f"change-{index:02d}", notes)

    def test_equal_subjects_maintenance_and_merge_commits_are_not_lost(self):
        first = self.commit("fix: repeated subject")
        second = self.commit("fix: repeated subject")
        self.commit("chore: build maintenance")
        branch = self.git("branch", "--show-current")
        self.git("checkout", "-qb", "feature")
        self.commit("feat: feature branch")
        self.git("checkout", "-q", branch)
        self.commit("fix: main branch")
        self.git("merge", "--no-ff", "-qm", "Merge feature branch", "feature")
        notes = generate(self.repo, "v1.2.1-canary.3", self.git("rev-parse", "HEAD"), self.before)
        self.assertEqual(2, notes.count("repeated subject"))
        self.assertIn(first[:7], notes)
        self.assertIn(second[:7], notes)
        self.assertIn("build maintenance", notes)
        self.assertIn("Merge feature branch", notes)
        self.assertEqual(6, sum(line.startswith("- ") for line in notes.splitlines()))

    def test_first_delivery_repeat_and_selected_older_build_have_explicit_ranges(self):
        current = self.commit("fix: current")
        first = generate(self.repo, "v1.2.1-canary.3", current)
        self.assertIn("首次发布", first)
        self.assertEqual(2, sum(line.startswith("- ") for line in first.splitlines()))
        for selected in [current, self.before]:
            notes = generate(self.repo, "v1.2.1-canary.3", selected, current)
            self.assertIn("没有新增提交", notes)
            self.assertIn(selected, notes)

    def test_missing_history_fails_instead_of_falling_back_to_one_commit(self):
        with self.assertRaises(subprocess.CalledProcessError):
            generate(self.repo, "v1.2.1-canary.3", self.before, "f" * 40)
        with self.assertRaises(ValueError):
            generate(self.repo, "v1.2.1-canary.3", self.before, "--all")
