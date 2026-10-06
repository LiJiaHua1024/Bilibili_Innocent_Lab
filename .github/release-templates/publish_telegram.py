#!/usr/bin/env python3
"""Upload an already verified signed Canary APK to its Telegram channel."""
from __future__ import annotations
import argparse
import hashlib
import http.client
import json
import os
import re
import sys
import uuid
from pathlib import Path
from urllib.parse import urlsplit

MAX_FILE_BYTES = 50_000_000
MAX_RESPONSE_BYTES = 256 * 1024
DEFAULT_CHAT = "@Bilibili_Innocent_LabRelease"
REPOSITORY_PATH = "/jichuo1/Bilibili_Innocent_Lab/actions/runs/"

class DeliveryError(Exception):pass

def validate_identity(apk: Path, info: Path, tag: str, commit: str, source_url: str) -> str:
    if not apk.is_file() or not 0<apk.stat().st_size<=MAX_FILE_BYTES:
        raise DeliveryError("APK exceeds the Telegram upload budget")
    if not re.fullmatch(r"v\d+\.\d+\.\d+-canary\.\d+",tag) or not re.fullmatch(r"[0-9a-f]{40}",commit):
        raise DeliveryError("Invalid Canary identity")
    url=urlsplit(source_url)
    run=url.path.removeprefix(REPOSITORY_PATH)
    if (url.scheme!="https" or url.netloc!="github.com" or not url.path.startswith(REPOSITORY_PATH)
        or not run.isdigit() or int(run)<=0 or url.query or url.fragment):
        raise DeliveryError("Unexpected build source URL")
    values={}
    for line in info.read_text(encoding="utf-8").splitlines():
        key,sep,value=line.partition("=")
        if not sep or key in values:raise DeliveryError("Invalid build provenance")
        values[key]=value
    digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    required={"release_channel":"canary","release_tag":tag,"source_commit":commit,
              "apk_build_type":"release","apk_debuggable":"false","apk_filename":apk.name,"apk_sha256":digest,
              "apk_package_name":"com.Bilibili_Innocent_Lab.xposedmodule","apk_version_name":tag.removeprefix("v")}
    if any(values.get(k)!=v for k,v in required.items()):raise DeliveryError("APK does not match verified build provenance")
    if not re.fullmatch(r"[0-9a-fA-F]{64}",values.get("apk_signer_certificate_sha256","")):
        raise DeliveryError("Verified signing identity is missing")
    return digest

def caption(tag: str, commit: str, digest: str, source_url: str, notes: str) -> str:
    entries=[line[2:].strip() for line in notes.splitlines() if line.startswith("- ")][:4]
    summary="\n".join("• "+entry for entry in entries)
    header=f"Bilibili Innocent Lab · Canary\n{tag}\n\n"
    footer=f"\n\n源码：{commit[:8]}\nSHA-256：{digest}\n构建：{source_url}"
    # Telegram 字符预算按 UTF-16 计数，截断点不能切开代理对。
    while len((header+summary+footer).encode("utf-16-le"))//2>1000:summary=summary[:-1]
    return header+summary+footer

def multipart(fields: dict[str,str], apk: Path) -> tuple[bytes,str]:
    boundary="CanaryUpload"+uuid.uuid4().hex
    parts=[]
    for key,value in fields.items():
        parts.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"{key}\"\r\n\r\n{value}\r\n").encode())
    if not re.fullmatch(r"[A-Za-z0-9_.-]+\.apk",apk.name):raise DeliveryError("Invalid APK upload filename")
    parts.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"document\"; filename=\"{apk.name}\"\r\n"
                  "Content-Type: application/vnd.android.package-archive\r\n\r\n").encode())
    parts.append(apk.read_bytes());parts.append(f"\r\n--{boundary}--\r\n".encode())
    return b"".join(parts),"multipart/form-data; boundary="+boundary

class Telegram:
    def __init__(self,token: str):
        if not re.fullmatch(r"\d+:[A-Za-z0-9_-]+",token):raise DeliveryError("Invalid Telegram secret format")
        self.token=token

    def call(self,method: str,fields: dict,apk: Path|None=None) -> dict:
        if method not in ["getMe","getChat","getChatMember","sendDocument"]:raise DeliveryError("Unsupported Telegram operation")
        if apk is None:
            body=json.dumps(fields).encode();content_type="application/json"
        else:body,content_type=multipart(fields,apk)
        connection=http.client.HTTPSConnection("api.telegram.org",timeout=180)
        try:
            connection.request("POST",f"/bot{self.token}/{method}",body,{"Content-Type":content_type})
            response=connection.getresponse();data=response.read(MAX_RESPONSE_BYTES+1)
            if len(data)>MAX_RESPONSE_BYTES:raise DeliveryError("Telegram response exceeds budget")
            value=json.loads(data)
            if response.status!=200 or value.get("ok") is not True:
                detail=str(value.get("description","request rejected")).replace(self.token,"[redacted]")[:200]
                raise DeliveryError(f"Telegram rejected {method}: {detail}")
            return value["result"]
        except DeliveryError:raise
        except Exception:
            # 发送请求超时可能已经投递：不自动重发，避免频道出现重复 APK。异常不含带令牌 URL。
            raise DeliveryError("Telegram network/protocol failure; check delivery before retrying") from None
        finally:connection.close()

def deliver(api: Telegram,chat: str,apk: Path,text: str) -> dict:
    if not re.fullmatch(r"@[A-Za-z][A-Za-z0-9_]{4,31}|-100\d+",chat):raise DeliveryError("Invalid Telegram channel ID")
    me=api.call("getMe",{});target=api.call("getChat",{"chat_id":chat})
    if target.get("type")!="channel":raise DeliveryError("Telegram destination must be a channel")
    if chat.startswith("@") and target.get("username","").lower()!=chat[1:].lower():raise DeliveryError("Telegram channel does not match configured destination")
    member=api.call("getChatMember",{"chat_id":target["id"],"user_id":me["id"]})
    if member.get("status")!="creator" and not (member.get("status")=="administrator" and member.get("can_post_messages") is True):
        raise DeliveryError("Bot needs channel administrator permission to post messages")
    return api.call("sendDocument",{"chat_id":str(target["id"]),"caption":text},apk)

def main() -> int:
    parser=argparse.ArgumentParser()
    for name in ["tag","commit","source-url"]:parser.add_argument("--"+name,required=True)
    for name in ["apk","build-info","notes"]:parser.add_argument("--"+name,required=True,type=Path)
    parser.add_argument("--summary",type=Path)
    parser.add_argument("--dry-run",action="store_true")
    args=parser.parse_args()
    try:
        digest=validate_identity(args.apk,args.build_info,args.tag,args.commit,args.source_url)
        text=caption(args.tag,args.commit,digest,args.source_url,args.notes.read_text(encoding="utf-8"))
        token=os.environ.get("TELEGRAM_BOT_TOKEN","");chat=os.environ.get("TELEGRAM_CHAT_ID",DEFAULT_CHAT)
        if args.dry_run:status="Validated Telegram payload; dry-run only"
        elif not token:status="Telegram delivery not configured: add TELEGRAM_BOT_TOKEN and grant channel posting permission"
        else:
            result=deliver(Telegram(token),chat,args.apk,text)
            status=f"Telegram delivered Canary APK, message ID {result['message_id']}"
        print(status)
        if args.summary:
            with args.summary.open("a",encoding="utf-8") as output:output.write("\n### Telegram delivery\n\n"+status+"\n")
        return 0
    except (DeliveryError,OSError) as error:
        message=str(error).replace(os.environ.get("TELEGRAM_BOT_TOKEN") or "\0","[redacted]")
        print("Telegram delivery failed: "+message,file=sys.stderr)
        if args.summary:
            with args.summary.open("a",encoding="utf-8") as output:output.write("\n### Telegram delivery failed\n\n"+message+"\n")
        return 1

if __name__=="__main__":raise SystemExit(main())
