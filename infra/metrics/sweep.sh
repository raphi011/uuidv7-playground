#!/usr/bin/env bash
# Runs on EC2. Drives the sweep and records a UTC window per rate so the latency numbers can be
# joined against the 1s CPU series afterwards. Start the server first:
#
#   bin/kotlin-playground serve &
#   ./sweep.sh
#
# Writes runs.csv (rate,start_ms,end_ms) and sweep.log next to itself.
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
SECONDS_PER_RATE=${SECONDS_PER_RATE:-20}
# Phase 3.4: the same rates as the laptop table, so the two are directly comparable.
RATES=${RATES:-"40 500 1000 2000 3000 4000"}
# Phase 3.3: a cold 4 GB cache against a 6 GB dataset would make the first rate look worst.
WARMUP_RATE=${WARMUP_RATE:-500}
# pg_stat flushes at most once a second and LoadTest settles 2s each side; without a gap one
# run's scans bleed into the next (a local run reported 4.93 scans/request purely as this artifact).
GAP=${GAP:-30}

cd "$(dirname "$0")"
: > runs.csv
echo "rate,start_ms,end_ms" >> runs.csv

run() {
  local rate=$1 seconds=$2 label=$3
  local start end
  start=$(date -u +%s%3N)
  echo "=== $label ${rate} req/s for ${seconds}s ===" | tee -a sweep.log
  bin/kotlin-playground loadtest "$rate" "$seconds" "$BASE_URL" 2>&1 | tee -a sweep.log
  end=$(date -u +%s%3N)
  [ "$label" = sweep ] && echo "$rate,$start,$end" >> runs.csv
  return 0
}

run "$WARMUP_RATE" 60 warmup   # discarded
sleep "$GAP"

for rate in $RATES; do
  run "$rate" "$SECONDS_PER_RATE" sweep
  sleep "$GAP"
done

echo "windows recorded in runs.csv:" && cat runs.csv
