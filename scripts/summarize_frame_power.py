#!/usr/bin/env python3
"""Validate Perfetto energy rails before computing watts; zero/stale telemetry is unavailable."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--processor", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    query = """SELECT t.name, t.type, c.ts, c.value FROM counter c JOIN counter_track t ON t.id=c.track_id
               WHERE t.type IN ('power_rails','android_energy_estimation_breakdown','battery_counter','entity_state')
               ORDER BY t.name,c.ts"""
    result = subprocess.run([str(args.processor), "query", str(args.trace), query],
                            text=True, capture_output=True, check=True)
    rows = list(csv.DictReader(io.StringIO(result.stdout)))
    if not rows:
        raise ValueError("trace contains no power/battery counters")
    groups = {}
    for row in rows:
        groups.setdefault((row["name"], row["type"]), []).append((int(row["ts"]), float(row["value"])))
    tracks = []
    for (name, kind), samples in groups.items():
        values = [value for _, value in samples]
        is_energy = kind in ("power_rails", "android_energy_estimation_breakdown")
        backwards = any(b < a for a, b in zip(values, values[1:]))
        span_ns = samples[-1][0] - samples[0][0]
        valid = is_energy and len(samples) > 1 and span_ns > 0 and max(values) > min(values) and not backwards
        tracks.append({"name": name, "type": kind, "samples": len(samples), "durationSeconds": span_ns / 1e9,
                       "minimum": min(values), "maximum": max(values), "counterDecreased": backwards,
                       "energyRailUsable": valid,
                       "wholeTraceAverageRailW": (values[-1] - values[0]) * 1e3 / span_ns if valid else None,
                       "reason": "valid increasing energy counter; includes all device users" if valid
                       else "all-zero/stale/reset energy counter" if is_energy else "state residency is not an energy measurement" if kind == "entity_state"
                       else "battery telemetry; USB charging prevents module power attribution"})
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "power-counters.csv").write_text(result.stdout)
    summary = {"traceSha256": hashlib.sha256(args.trace.read_bytes()).hexdigest(),
               "source": "android.power; 500 ms poll; no frequency/charging controls changed",
               "validEnergyRails": sum(t["energyRailUsable"] and t["type"] == "power_rails" for t in tracks),
               "validEnergyConsumers": sum(t["energyRailUsable"] and t["type"] == "android_energy_estimation_breakdown" for t in tracks),
               "tracks": tracks}
    (args.output / "power-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"validEnergyRails": summary["validEnergyRails"], "counters": len(rows)}))


if __name__ == "__main__":
    main()
