#!/usr/bin/env bash
# Drive the API at a fixed request rate using ids persisted by ./load-data.sh.
# Start the server first:  gradle run --args=serve
#
#   ./load-test.sh                 # 40 req/s for 30s
#   ./load-test.sh 200 60          # 200 req/s for 60s
set -euo pipefail
exec gradle run -q --args="loadtest ${1:-40} ${2:-30} ${3:-http://localhost:8080}"
