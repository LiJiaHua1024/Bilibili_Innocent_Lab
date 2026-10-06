from pathlib import Path
import tempfile
import hashlib
import unittest
from unittest.mock import patch
from publish_telegram import validate_identity,caption,multipart,deliver,DeliveryError,MAX_FILE_BYTES

class TelegramDeliveryTest(unittest.TestCase):
    def fixture(self):
        directory=tempfile.TemporaryDirectory();self.addCleanup(directory.cleanup)
        root=Path(directory.name);apk=root/"Bilibili_Innocent_Lab-v1.2.1-canary.3-aaaaaaaa.apk"
        apk.write_bytes(b"test APK data")
        digest=hashlib.sha256(apk.read_bytes()).hexdigest()
        info=root/"BUILD_INFO.txt"
        info.write_text("\n".join(["release_channel=canary","release_tag=v1.2.1-canary.3","source_commit="+"a"*40,
            "apk_build_type=release","apk_debuggable=false","apk_filename="+apk.name,"apk_sha256="+digest,
            "apk_package_name=com.Bilibili_Innocent_Lab.xposedmodule","apk_version_name=1.2.1-canary.3",
            "apk_signer_certificate_sha256="+"b"*64]),encoding="utf-8")
        return apk,info,digest

    def test_verified_identity(self):
        apk,info,digest=self.fixture()
        self.assertEqual(digest,validate_identity(apk,info,"v1.2.1-canary.3","a"*40,"https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/123"))

    def test_payload_mismatch_and_debug_are_rejected(self):
        for mutation in ["debug","digest","url"]:
            apk,info,_=self.fixture()
            url="https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/123"
            if mutation=="debug":info.write_text(info.read_text().replace("apk_debuggable=false","apk_debuggable=true"))
            if mutation=="digest":apk.write_bytes(b"changed")
            if mutation=="url":url="https://evil.invalid/actions/runs/123"
            with self.subTest(mutation=mutation),self.assertRaises(DeliveryError):validate_identity(apk,info,"v1.2.1-canary.3","a"*40,url)

    def test_caption_budget_retains_identity_and_url(self):
        url="https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/123"
        text=caption("v1.2.1-canary.3","a"*40,"b"*64,url,"- "+"🙂"*2000)
        self.assertLessEqual(len(text.encode("utf-16-le"))//2,1000)
        self.assertIn(url,text);self.assertIn("b"*64,text)

    def test_multipart_uploads_apk_bytes_instead_of_remote_url(self):
        apk,_,_=self.fixture();body,kind=multipart({"chat_id":"-100123","caption":"test"},apk)
        self.assertIn(apk.read_bytes(),body);self.assertIn(b"name=\"document\"",body)
        self.assertTrue(kind.startswith("multipart/form-data; boundary="))

    def test_permission_failure_never_posts(self):
        apk,_,_=self.fixture()
        api=type("Fake",(),{"call":lambda self,method,fields,apk=None:{"getMe":{"id":1},"getChat":{"id":-100123,"type":"channel","username":"Bilibili_Innocent_LabRelease"},"getChatMember":{"status":"member"}}[method]})()
        with self.assertRaises(DeliveryError):deliver(api,"@Bilibili_Innocent_LabRelease",apk,"caption")

    def test_admin_posts_to_the_resolved_channel(self):
        apk,_,_=self.fixture();calls=[]
        def call(method,fields,file=None):
            calls.append((method,fields,file))
            return {"getMe":{"id":1},"getChat":{"id":-100123,"type":"channel","username":"Bilibili_Innocent_LabRelease"},
                "getChatMember":{"status":"administrator","can_post_messages":True},"sendDocument":{"message_id":7}}[method]
        api=type("Fake",(),{})();api.call=call
        self.assertEqual(7,deliver(api,"@Bilibili_Innocent_LabRelease",apk,"caption")["message_id"])
        self.assertEqual("-100123",calls[-1][1]["chat_id"])
        self.assertEqual(apk,calls[-1][2])
