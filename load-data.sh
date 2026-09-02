#!/usr/bin/env bash
# Generate a benchmark dataset. Drops whatever is in `payments` first.
#
#   ./load-data.sh                 # 1M rows over 36 months
#   ./load-data.sh 50000000 36     # 50M rows over 36 months
#   ./load-data.sh 100000 12       # small, for a quick loop
#
# Ids of roughly every (rows/50000)th payment are written to build/sample-ids.txt for ./load-test.sh.
set -euo pipefail
exec gradle run -q --args="load ${1:-1000000} ${2:-36}"
