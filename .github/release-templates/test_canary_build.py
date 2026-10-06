from pathlib import Path
import tempfile
import unittest
from validate_canary_build import resolve_build_identity,next_patch_version

class CanaryIdentityTest(unittest.TestCase):
    def properties(self):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup)
        path=Path(temporary.name)/"gradle.properties"
        path.write_text('project.app.versionName="1.2.0"\nproject.app.versionCode=21\nproject.app.packageName=com.example.module\n')
        return path

    def test_next_patch_canary_keeps_source_version_code(self):
        result=resolve_build_identity(self.properties(),"v1.2.1-canary.123")
        self.assertEqual("1.2.1-canary.123",result.build_version_name)
        self.assertEqual(21,result.version_code)
        self.assertEqual("1.2.0",result.base_version)

    def test_invalid_and_cross_channel_tags_are_rejected(self):
        for tag in ["v1.2.1-alpha.1","v1.2.0-canary.1","v1.3.0-canary.1","v1.2.1-canary","v1.2.1-canary.-1","v1.2.1-canary.01"]:
            with self.subTest(tag=tag),self.assertRaises(ValueError):resolve_build_identity(self.properties(),tag)

    def test_patch_rollover(self):self.assertEqual("1.2.100",next_patch_version("1.2.99"))

    def test_actions_only_pipeline_keeps_signed_identity_gates(self):
        content=(Path(__file__).resolve().parents[1]/"workflows/canary-build.yml").read_text(encoding="utf-8")
        self.assertIn("branches:\n      - main",content)
        self.assertIn('--run-number "$CANARY_RUN_NUMBER"',content)
        self.assertIn("assembleRelease",content)
        self.assertIn("verify_release_apk.py",content)
        self.assertIn("apk_debuggable=false",content)
        self.assertIn("ANDROID_SIGNING_CERT_SHA256",content)
        self.assertIn("uses: actions/upload-artifact@v6",content)
        self.assertNotIn("publish_telegram.py",content)
        self.assertNotIn("TELEGRAM_BOT_TOKEN",content)
        self.assertIn("name: alpha-release",content)
        self.assertNotIn("gh release",content)
        self.assertNotIn("git tag",content)
        self.assertNotIn("contents: write",content)
        self.assertNotIn("inputs.publish",content)
        self.assertNotIn("app-debug.apk",content)
