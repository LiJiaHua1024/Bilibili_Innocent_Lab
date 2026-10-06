#!/usr/bin/env python3
"""Read back an uploaded Canary artifact and compare every entry to verified local files."""
from __future__ import annotations
import argparse
import hashlib
import zipfile
from pathlib import Path

def verify(archive: Path,directory: Path) -> None:
    expected={path.name:path for path in directory.iterdir() if path.is_file()}
    if len([name for name in expected if name.endswith('.apk')])!=1 or not {'BUILD_INFO.txt','SHA256SUMS.txt','CANARY_CHANGELOG.md'}.issubset(expected):
        raise ValueError('Canary package is incomplete')
    with zipfile.ZipFile(archive) as bundle:
        entries=[entry for entry in bundle.infolist() if not entry.is_dir()]
        names=[entry.filename for entry in entries]
        if len(names)!=len(set(names)) or set(names)!=set(expected):raise ValueError('Uploaded artifact file set differs from verified package')
        for entry in entries:
            local=expected[entry.filename]
            if entry.file_size!=local.stat().st_size:raise ValueError('Uploaded artifact file size differs')
            digest=hashlib.sha256()
            with bundle.open(entry) as data:
                while chunk:=data.read(64*1024):digest.update(chunk)
            if digest.digest()!=hashlib.sha256(local.read_bytes()).digest():raise ValueError('Uploaded artifact checksum differs')

def main() -> None:
    parser=argparse.ArgumentParser();parser.add_argument('--archive',type=Path,required=True);parser.add_argument('--directory',type=Path,required=True)
    args=parser.parse_args();verify(args.archive,args.directory);print('Canary artifact read-back verified')

if __name__=='__main__':main()
