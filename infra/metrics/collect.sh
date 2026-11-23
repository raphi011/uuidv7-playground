#!/usr/bin/env bash
# Runs on the laptop. Pulls the two metric sources for a time window into CSVs.
#
#   ./collect.sh <start_ms> <end_ms>
#   ./collect.sh $(head -2 runs.csv | tail -1 | cut -d, -f2) ...
#
# Reads identifiers from `terraform output` in ../ so nothing has to be passed by hand.
set -euo pipefail
cd "$(dirname "$0")"

START_MS=${1:?start epoch millis}
END_MS=${2:?end epoch millis}
REGION=$(terraform -chdir=.. output -raw region 2>/dev/null || echo "${AWS_REGION:-eu-central-1}")
RESOURCE_ID=$(terraform -chdir=.. output -raw db_resource_id)
DB_ID=$(terraform -chdir=.. output -raw db_instance_identifier)

iso() { python3 -c "import sys,datetime;print(datetime.datetime.fromtimestamp(int(sys.argv[1])/1000,datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))" "$1"; }

# --- 1s OS CPU, from the Enhanced Monitoring log stream -----------------------------------
# This is the series that can resolve a 20s run. The CloudWatch credit metrics cannot.
echo "ts_ms,vcpus,cpu_total,cpu_user,cpu_system,cpu_wait,cpu_steal" > os-cpu.csv
aws logs filter-log-events \
  --region "$REGION" \
  --log-group-name RDSOSMetrics \
  --log-stream-names "$RESOURCE_ID" \
  --start-time "$START_MS" --end-time "$END_MS" \
  --output json \
| jq -r '.events[].message | fromjson
         | [ .timestamp, .numVCPUs, .cpuUtilization.total, .cpuUtilization.user,
             .cpuUtilization.system, .cpuUtilization.wait, .cpuUtilization.steal ]
         | @csv' \
| python3 -c '
import sys, datetime
for line in sys.stdin:
    parts = line.rstrip("\n").split(",")
    ts = parts[0].strip(chr(34))
    ms = int(datetime.datetime.strptime(ts, "%Y-%m-%dT%H:%M:%SZ")
             .replace(tzinfo=datetime.timezone.utc).timestamp() * 1000)
    print(",".join([str(ms)] + parts[1:]))
' >> os-cpu.csv

# --- 5-minute credit metrics, as the coarse ground truth ----------------------------------
# Published at a five-minute frequency only, so these confirm the derivation over the whole
# session; they cannot attribute anything to an individual 20s run.
echo "metric,ts,value" > credits.csv
for metric in CPUUtilization CPUCreditUsage CPUCreditBalance CPUSurplusCreditBalance CPUSurplusCreditsCharged; do
  aws cloudwatch get-metric-statistics \
    --region "$REGION" \
    --namespace AWS/RDS --metric-name "$metric" \
    --dimensions Name=DBInstanceIdentifier,Value="$DB_ID" \
    --start-time "$(iso "$START_MS")" --end-time "$(iso "$END_MS")" \
    --period 300 --statistics Average \
    --output json \
  | jq -r --arg m "$metric" '.Datapoints | sort_by(.Timestamp) | .[] | [$m, .Timestamp, .Average] | @csv' \
  | tr -d '"' >> credits.csv
done

echo "os-cpu.csv : $(( $(wc -l < os-cpu.csv) - 1 )) samples"
echo "credits.csv: $(( $(wc -l < credits.csv) - 1 )) datapoints"
