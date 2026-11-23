#!/usr/bin/env python3
"""Derive CPU credit consumption per sweep run from the 1s Enhanced Monitoring CPU series.

The CloudWatch credit metrics are published at a five-minute frequency, so a 20s run at any
rate is invisible on them. The 1s series is not, and credits are a pure function of CPU:

    1 CPU credit = 1 vCPU at 100% for 1 minute

    spend  credits/min = cpu_fraction * vcpus
    earn   credits/min = baseline_fraction * vcpus     (db.t4g.xlarge: 40% * 4 = 1.6 = 96/hr)
    net    credits/min = (cpu_fraction - baseline_fraction) * vcpus

A negative net means the run is accruing rather than consuming. The rate at which net crosses
zero is the answer to "at which point do we start consuming credits".

    ./credit-burn.py runs.csv os-cpu.csv
"""
import csv
import sys

# db.t4g.xlarge: 4 vCPU, 40% baseline per vCPU, 96 credits/hr, 2304 max. Verified against the
# EC2 burstable credit table; RDS uses the same credit model for the same instance size.
BASELINE_FRACTION = 0.40


def main(runs_path: str, cpu_path: str) -> None:
    with open(cpu_path) as f:
        samples = [(int(r["ts_ms"]), int(r["vcpus"]), float(r["cpu_total"]))
                   for r in csv.DictReader(f)]
    samples.sort()
    if not samples:
        sys.exit(f"{cpu_path} has no samples — check the window and that monitoring_interval is 1")

    with open(runs_path) as f:
        runs = [(int(r["rate"]), int(r["start_ms"]), int(r["end_ms"])) for r in csv.DictReader(f)]

    print(f"{'rate':>6} {'samples':>8} {'cpu avg%':>9} {'cpu max%':>9} "
          f"{'spend/min':>10} {'net/min':>9} {'credits':>9}")
    for rate, start, end in runs:
        window = [(v, c) for ts, v, c in samples if start <= ts <= end]
        if not window:
            print(f"{rate:>6} {'-':>8}   no 1s samples in window")
            continue
        vcpus = window[0][0]
        cpus = [c for _, c in window]
        avg = sum(cpus) / len(cpus)
        spend = avg / 100.0 * vcpus
        net = (avg / 100.0 - BASELINE_FRACTION) * vcpus
        # Each sample covers one second.
        credits = net * len(window) / 60.0
        print(f"{rate:>6} {len(window):>8} {avg:>9.1f} {max(cpus):>9.1f} "
              f"{spend:>10.2f} {net:>+9.2f} {credits:>+9.3f}")

    print()
    print(f"baseline: {BASELINE_FRACTION:.0%} per vCPU x {samples[0][1]} vCPU "
          f"= {BASELINE_FRACTION * samples[0][1]:.1f} credits/min earned "
          f"({BASELINE_FRACTION * samples[0][1] * 60:.0f}/hr)")
    print("net/min > 0 means the run consumed credits faster than the instance earns them.")


if __name__ == "__main__":
    main(*sys.argv[1:3])
