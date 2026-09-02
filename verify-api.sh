#!/usr/bin/env bash
# Proves the running API touches one partition, using Postgres' own per-table scan counters
# rather than EXPLAIN. Start the server first:  gradle run --args=serve
#
#   ./verify-api.sh              # creates a payment, then fetches it
#   ./verify-api.sh <uuid>       # fetches an existing payment
set -euo pipefail

DB=(-h localhost -d uuidv7_playground -tAq -F' ')
API=http://localhost:8080

settle() {   # stats are flushed by each backend at most once a second
  psql "${DB[@]}" -c "SELECT pg_sleep(2)" > /dev/null
}

snapshot() {
  psql "${DB[@]}" -c "SELECT relname, coalesce(idx_scan,0) + coalesce(seq_scan,0)
                      FROM pg_stat_user_tables WHERE relname LIKE 'payments\_%' ORDER BY relname"
}

if [ $# -ge 1 ]; then
  ID=$1
else
  ID=$(curl -sS -X POST "$API/payments" -H 'content-type: application/json' \
        -d '{"amountCents":4999,"currency":"EUR","reference":"VERIFY"}' |
       sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
  echo "created $ID"
fi

settle
before=$(snapshot)
curl -sS -o /dev/null "$API/payments/$ID"
settle

join <(echo "$before") <(snapshot) |
  awk '$3 > $2 { printf "  %-20s +%d scans\n", $1, $3 - $2; n++ }
       END { printf "\npartitions touched by GET /payments/%s: %d\n", id, n+0 }' id="$ID"
