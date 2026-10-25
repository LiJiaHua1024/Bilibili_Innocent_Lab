#!/usr/bin/env python3
"""Sequential device qualification; preserve measurements and distinguish capability from test execution."""
import argparse
import contextlib
import fcntl
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

PROFILES = [(15, 15), (30, 30), (15, 45), (25, 50), (30, 60), (30, 120),
            (60, 120), (24, 144), (15, 165), (37, 185)]
RUNNER = "com.Bilibili_Innocent_Lab.xposedmodule.frameverify.test/androidx.test.runner.AndroidJUnitRunner"
TEST = "com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameCaptureBridgeDeviceTest#highRate120Metrics"


@contextlib.contextmanager
def device_test_lock():
    """One instrumentation owner per host user, shared by matrices and diagnostic callers."""
    path = Path(tempfile.gettempdir()) / f"innocent-frame-validation-{os.getuid()}.lock"
    with path.open("a+") as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError("another frame validation owns the device; run tests sequentially") from error
        try:
            yield
        finally:
            fcntl.flock(stream, fcntl.LOCK_UN)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sizes", default="640x360,854x480,960x540,1280x720")
    parser.add_argument("--seconds", type=int, default=6)
    parser.add_argument("--screen", action="store_true")
    parser.add_argument("--texture", action="store_true", help="use the host-like TextureView display path")
    parser.add_argument("--reuse-fixture", action="store_true", help="reuse a format-validated private encoded fixture")
    parser.add_argument("--stage-uploads", choices=("true", "false"), default="true")
    parser.add_argument("--source", type=int)
    parser.add_argument("--target", type=int)
    args = parser.parse_args()
    if args.texture and not args.screen:
        parser.error("--texture requires --screen")
    if not 6 <= args.seconds <= 120:
        parser.error("seconds must be 6..120")
    sizes = [tuple(map(int, item.split("x"))) for item in args.sizes.split(",")]
    if any(size not in {(640, 360), (854, 480), (960, 540), (1280, 720)} for size in sizes):
        parser.error("full pipeline sizes must stay within the verified capture size limit")
    profiles = [(s, t) for s, t in PROFILES if (args.source is None or s == args.source)
                and (args.target is None or t == args.target)]
    if not profiles:
        parser.error("source/target did not select a validated profile")
    args.output.mkdir(parents=True, exist_ok=True)
    result_path = args.output / "pipeline.jsonl"
    if result_path.exists():
        parser.error("use a new output directory to avoid mixing runs")
    failures = 0
    total = len(sizes) * len(profiles)
    index = 0
    for w, h in sizes:
        for source, target in profiles:
            index += 1
            label = f"{w}x{h}-{source}to{target}"
            command = ["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST,
                       "-e", "sourceFps", str(source), "-e", "targetFps", str(target),
                       "-e", "width", str(w), "-e", "height", str(h), "-e", "seconds", str(args.seconds)]
            command += ["-e", "stageUploads", args.stage_uploads]
            if args.reuse_fixture:
                command += ["-e", "reuseFixture", "true"]
            if args.screen:
                command += ["-e", "screen", "true"]
            if args.texture:
                command += ["-e", "texture", "true"]
            command.append(RUNNER)
            started = time.monotonic()
            try:
                completed = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                           stderr=subprocess.STDOUT, timeout=args.seconds + 150)
                output = completed.stdout
                records = [json.loads(line.split("highRate=", 1)[1]) for line in output.splitlines()
                           if line.startswith("INSTRUMENTATION_STATUS: highRate=")]
                if completed.returncode != 0 or "OK (1 test)" not in output or len(records) != 1:
                    raise RuntimeError("instrumentation did not complete one measured scenario")
                record = records[0]
                record["executionStatus"] = "complete"
            except (subprocess.TimeoutExpired, RuntimeError, ValueError) as error:
                failures += 1
                output = str(getattr(error, "output", "") or str(error))
                record = {"width": w, "height": h, "sourceFps": source, "targetFps": target,
                          "executionStatus": "error", "error": str(error)}
            record["hostWallSeconds"] = time.monotonic() - started
            (args.output / f"{label}.log").write_text(output)
            with result_path.open("a") as stream:
                stream.write(json.dumps(record, ensure_ascii=False) + "\n")
            print(json.dumps({"progress": f"{index}/{total}", "case": label,
                              "execution": record["executionStatus"], "fps": record.get("measuredFps"),
                              "qualifies": record.get("qualifiesTarget"), "fallback": record.get("nativeFallback")}), flush=True)
    return int(failures > 0)


if __name__ == "__main__":
    with device_test_lock():
        raise SystemExit(main())
