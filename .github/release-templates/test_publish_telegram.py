from pathlib import Path
import tempfile
import hashlib
import unittest
from unittest.mock import patch
from publish_telegram import validate_identity,caption,multipart,deliver,split_text,announcement_messages,main,DeliveryError,MAX_FILE_BYTES

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

    def test_caption_is_no_longer_limited_to_four_entries(self):
        text=caption("v1.2.1-canary.3","a"*40,"b"*64,
            "https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/123",
            "\n".join(f"- change {index}" for index in range(10)))
        self.assertIn("change 9",text)

    def test_complete_long_announcement_preserves_every_unicode_character(self):
        notes="\n".join(f"- 更新 {index}："+"🙂"*100 for index in range(60))
        messages=announcement_messages(notes)
        self.assertGreater(len(messages),1)
        self.assertEqual(notes,"".join(message.split("\n\n",1)[1] for message in messages))
        self.assertTrue(all(len(message.encode("utf-16-le"))//2<=4096 for message in messages))

    def test_normal_commit_entries_are_split_at_line_boundaries(self):
        entries=[f"- change {index}: "+"说明🙂"*40 for index in range(80)]
        messages=announcement_messages("\n".join(entries))
        bodies=[message.split("\n\n",1)[1] for message in messages]
        self.assertTrue(all(body.endswith("\n") for body in bodies[:-1]))
        self.assertTrue(all(sum(entry in body for body in bodies)==1 for entry in entries))

    def test_line_boundary_near_unicode_budget_still_obeys_the_limit(self):
        text="\n"+"🙂"*100
        parts=split_text(text,17)
        self.assertEqual(text,"".join(parts))
        self.assertTrue(all(len(part.encode("utf-16-le"))//2<=17 for part in parts))

    def announcement_api(self, fail=False):
        calls=[]
        def call(method,fields,file=None):
            calls.append((method,fields,file))
            if method=="sendMessage":
                if fail:raise DeliveryError("announcement interrupted")
                return {"message_id":len(calls)+7}
            return {"getMe":{"id":1},"getChat":{"id":-100123,"type":"channel","username":"Bilibili_Innocent_LabRelease"},
                "getChatMember":{"status":"administrator","can_post_messages":True},"sendDocument":{"message_id":7}}[method]
        api=type("Fake",(),{})();api.call=call
        return api,calls

    def test_all_announcement_parts_reply_to_the_uploaded_apk(self):
        apk,_,_=self.fixture();api,calls=self.announcement_api()
        with patch("publish_telegram.time.sleep"):
            result=deliver(api,"@Bilibili_Innocent_LabRelease",apk,"caption","完整记录🙂"*3000)
        sent=[fields for method,fields,_ in calls if method=="sendMessage"]
        self.assertGreater(len(sent),1)
        self.assertEqual(len(sent),len(result["announcement_message_ids"]))
        self.assertTrue(all(fields["reply_parameters"]["message_id"]==7 for fields in sent))

    def test_oversized_announcement_fails_before_posting_anything(self):
        apk,_,_=self.fixture();api,calls=self.announcement_api()
        with self.assertRaises(DeliveryError):
            deliver(api,"@Bilibili_Innocent_LabRelease",apk,"caption","x"*(256*1024+1))
        self.assertEqual([],calls)

    def invoke_main(self, apk,info,notes,receipt,*,dry_run=False,api=None):
        arguments=["publish_telegram.py","--tag","v1.2.1-canary.3","--commit","a"*40,
            "--source-url","https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/123",
            "--apk",str(apk),"--build-info",str(info),"--notes",str(notes),"--receipt",str(receipt)]
        if dry_run:arguments.append("--dry-run")
        with patch("sys.argv",arguments),patch.dict("os.environ",{"TELEGRAM_BOT_TOKEN":"1:test"}),\
             patch("publish_telegram.Telegram",return_value=api),patch("publish_telegram.time.sleep"):
            return main()

    def test_success_writes_receipt_only_after_complete_notes(self):
        import json
        apk,info,_=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
        notes.write_text("- change\n",encoding="utf-8");api,_=self.announcement_api()
        self.assertEqual(0,self.invoke_main(apk,info,notes,receipt,api=api))
        result=json.loads(receipt.read_text(encoding="utf-8"))
        self.assertTrue(result["delivery_complete"])
        self.assertEqual("a"*40,result["source_commit"])
        self.assertEqual(1,len(result["announcement_message_ids"]))

    def test_dry_run_and_partial_announcement_failure_do_not_write_receipts(self):
        for dry_run in [True,False]:
            apk,info,_=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
            notes.write_text("- change\n",encoding="utf-8");api,calls=self.announcement_api(fail=True)
            self.assertEqual(0 if dry_run else 1,self.invoke_main(apk,info,notes,receipt,dry_run=dry_run,api=api))
            self.assertFalse(receipt.exists())
            if dry_run:self.assertEqual([],calls)
