# Plan: run the 50M-row partition-pruning benchmark on AWS RDS

## Context

`FINDINGS.md` proves the design works: `GET /payments/{id}` decodes the timestamp out of the UUIDv7
key and Postgres prunes to 1 of 39 partitions — 4 buffers instead of 218, ~1.00 partition scans per
request at every rate up to 4000 req/s.

What the local run **cannot** answer is latency. On this laptop `shared_buffers` is the stock 128 MB
against a 6 GB dataset, so partitions ran at 43% heap / 75% index cache hit and p95/p99 came out
*non-monotonic* across rates (1000 req/s produced a worse p99 than 4000 req/s). Those tail numbers
are a property of an undersized buffer cache, not of the design. `FINDINGS.md` already says so.

This plan provisions a properly-sized managed Postgres with a same-AZ load generator, re-runs the
identical sweep, and records numbers that describe the design rather than the machine. It is a
throwaway environment: provisioned, measured, destroyed in one session.

**Nothing in this plan changes application code.** `Seed`, `LoadTest`, `PaymentRepository` and
`schema.sql` run unmodified against RDS; only connection details differ, and `Db.dataSource()`
already reads `PGURL` / `PGUSER` / `PGPASSWORD` from the environment
(`src/main/kotlin/playground/Db.kt:12`).

## Decisions already taken

- **D1** — `shared_buffers` ~4 GB. On RDS this is **free**: the default parameter group computes
  `{DBInstanceClassMemory/32768}` 8 kB pages, which on a 16 GiB instance is ~4 GiB. `db.t4g.xlarge`
  is 16 GiB, so D1 survives the D4 change unchanged. No custom
  parameter group needed. Verify with `SHOW shared_buffers` rather than assuming.
- **D2** — Load generator and data loader run **on EC2 in the same AZ**, never from the laptop.
  20–50 ms internet RTT would swamp a sub-millisecond p50, and pushing ~3.5 GB of COPY text over a
  home uplink is pointless when the generator can produce it locally.
- **D3** — Postgres **16.x**, not 18. Production targets 16.13 (F6), and 16 is what the Kotlin-side
  timestamp decode exists to accommodate. Benchmarking on 18 would measure the wrong engine.

## Decisions taken this session

- **D4 — instance class: `db.t4g.xlarge`** (~$0.265/hr), burstable, chosen deliberately. The
  original recommendation was the non-burstable `db.m7g.xlarge`; it was overridden because credit
  behaviour is now part of what the run is measuring, not just a threat to it.
- **D5 — credit mode is not selectable.** The intent was `standard`, so the instance would throttle
  at baseline and the knee would be visible. **RDS does not offer standard mode.** The RDS user
  guide states plainly: *"Amazon RDS T4g instances are configured for Unlimited mode."* The same
  applies to T3. Corroborated by the Terraform AWS provider schema, where `credit_specification`
  exists on `aws_instance`, `aws_launch_template` and `aws_spot_instance_request` but **not** on
  `aws_db_instance`. There is no argument to set, so `infra/main.tf` sets none.

  Consequences, both of which change what the run can show:

  - **The instance will never throttle.** It bursts past baseline for as long as asked and bills
    surplus credits (~$0.075/vCPU-hour). R1 as originally written is void; the tail numbers are
    *not* at risk from credit exhaustion. This is the same property `db.m7g.xlarge` was recommended
    for, obtained by a different mechanism and at lower cost.
  - **There are no credits to drain.** T4g instances earn no launch credits, so a fresh instance
    starts near zero and accrues 96/hr. Provisioning plus load is roughly half an hour, ~48 credits,
    and the 50M-row index build consumes that immediately. Watching a balance fall from a full 2304
    is not possible in a throwaway session without idling the instance for hours first, which is not
    worth the wall-clock. The balance is expected to sit at or near zero for the whole sweep, with
    `CPUSurplusCreditBalance` rising instead.

- **D6 — credit consumption is derived from the 1s CPU series, not read from CloudWatch.** The
  credit metrics (`CPUCreditUsage`, `CPUCreditBalance`, `CPUSurplusCreditBalance`) are published at
  a **five-minute frequency only**. A 20 s sweep run shares a datapoint with 4:40 of idle, so those
  metrics cannot attribute anything to an individual rate. Enhanced Monitoring at `monitoring_interval = 1`
  writes 1 s OS metrics to the `RDSOSMetrics` CloudWatch Logs group, and credits are a pure function
  of CPU:

  ```
  1 CPU credit = 1 vCPU at 100% for 1 minute

  spend credits/min = cpu_fraction x vCPUs
  earn  credits/min = 0.40 x 4 = 1.6          (db.t4g.xlarge: 96/hr, max balance 2304)
  net   credits/min = (cpu_fraction - 0.40) x 4
  ```

  So the question "at which point do we consume credits" resolves to: **which sweep rate first
  pushes average CPU above the 40% baseline.** Below that the run accrues; above it, it consumes.
  The 5-minute metrics are still collected, as coarse ground truth over the session as a whole.

- **D7 — the sweep stays at 20 s per rate.** Comparability with the laptop table in `FINDINGS.md`
  is worth more than making the coarse metrics move. The one phase long enough to register on
  5-minute datapoints is the 10–20 min data load, which therefore becomes a data point in its own
  right rather than setup.

## Architecture

```
  laptop ──ssh──►  EC2 (c7g.xlarge, same AZ)          RDS (db.t4g.xlarge, private)
                   ├─ Ktor server  :8080      ──5432──►  postgres 16.x
                   └─ LoadTest generator                  gp3 50 GB
```

- Default VPC, one AZ (e.g. `eu-central-1a` — pick the region closest to the user; it does not
  affect the numbers, only the RTT to the laptop for ssh).
- RDS **not** publicly accessible. Its security group accepts 5432 only from the EC2 instance's
  security group. EC2 accepts 22 from the laptop's IP only.
- EC2 `c7g.xlarge` (4 vCPU arm64, ~$0.145/hr) runs both the Ktor server and the generator, mirroring
  the local topology. `LoadTest` already reports `service` and `sched lag` separately
  (`src/main/kotlin/playground/LoadTest.kt:96`), so generator starvation is visible rather than
  silently attributed to the server. If sched lag is non-trivial at 4000 req/s, re-run on
  `c7g.2xlarge`.

## Steps

### Phase 0 — preflight, on the laptop (~20 min, human-gated)

Terraform 1.15.5 and `~/.ssh/id_ed25519.pub` were already present; the AWS CLI has since been
installed. **No `~/.aws` exists yet — this remains the hard gate (R6).**

1. ~~`brew install awscli`~~ — done.
2. The user configures credentials themselves — `aws configure` or SSO. I will not handle keys.
   In this session that is `! aws configure`.
3. `aws sts get-caller-identity` to confirm.
4. Set a billing alarm, or at minimum agree the session ends with `terraform destroy`.
5. `gradle installDist` → `build/install/kotlin-playground/` (bin script + jars). The `application`
   plugin already provides this; **no shadow-jar plugin is needed**. Tar it for upload. Bytecode is
   architecture-independent, so an arm64 EC2 only needs a JRE 21.

### Phase 1 — Terraform (written; ~10 min to apply)

`infra/` is written and `terraform validate` passes. Nothing else in the repo is touched.
Terraform, not shell + AWS CLI, specifically because `terraform destroy` is a single reliable
teardown — the main cost risk here is forgetting a resource, not the hourly rate.

`infra/main.tf` provisions:
- `aws_key_pair` from `~/.ssh/id_ed25519.pub`
- `aws_security_group.ec2` — ingress 22 from this laptop's IP, resolved at apply time by a
  `http` data source against `checkip.amazonaws.com` rather than a hand-set variable
- `aws_security_group.rds` — ingress 5432 from the EC2 SG only
- `aws_db_subnet_group` over the default VPC's subnets; the AZ is pinned so D2 holds
- `aws_db_instance` — engine `postgres` 16, `db.t4g.xlarge`, `allocated_storage = 50`,
  `storage_type = "gp3"`, `publicly_accessible = false`,
  **`skip_final_snapshot = true`** and `deletion_protection = false` — otherwise destroy leaves a
  snapshot billing quietly forever
- **`monitoring_interval = 1`** plus an `aws_iam_role` trusted by `monitoring.rds.amazonaws.com`
  with the `AmazonRDSEnhancedMonitoringRole` managed policy. This is what produces the 1 s CPU
  series D6 depends on; without it there is no way to attribute credit burn to a 20 s run.
- `performance_insights_enabled = true`, retention 7 days (free tier)
- `aws_instance` — `c7g.xlarge`, Amazon Linux 2023 arm64 via SSM parameter, same AZ, user_data
  installing `java-21-amazon-corretto-headless` and `postgresql16`
- outputs: EC2 public IP, RDS endpoint, `db_resource_id` (names the `RDSOSMetrics` log stream),
  and a sensitive `env` output holding ready-to-source `PGURL` / `PGUSER` / `PGPASSWORD`

No `credit_specification` is set anywhere on the DB instance — per D5 the argument does not exist.

Note on R4: gp3 below 400 GB is fixed at 3000 IOPS / 125 MBps and `iops` / `storage_throughput`
**cannot** be set at 50 GB. The only lever if the load turns out throughput-bound is raising
`db_allocated_storage` to 400.

State is local. `*.tfstate*` and the metric CSVs are now in `.gitignore`; the `.tfstate` must
survive until destroy.

### Phase 2 — bootstrap and load (~15 min)

1. `terraform apply`, wait for the RDS endpoint (RDS creation is ~8–10 min; the wall-clock floor of
   the whole session).
2. `scp` the `installDist` tarball, `verify.sql`, `verify-api.sh` and `infra/metrics/sweep.sh` to
   EC2. `sweep.sh` must sit at the install root, because `Main.kt` resolves the sampled ids at the
   relative path `build/sample-ids.txt` (`src/main/kotlin/playground/Main.kt:6`) — so the loader and
   the sweep must run from the same working directory, and that directory needs a `build/`.
3. On EC2: `sudo dnf install -y java-21-amazon-corretto-headless postgresql16`
4. `source` the `env` terraform output on the box (`terraform output -raw env`), which sets
   `PGURL` / `PGUSER` / `PGPASSWORD` for the JVM and `PGHOST` / `PGDATABASE` for `psql`.
5. `bin/kotlin-playground load 50000000 36` — `Db.applySchema` creates all 39 partitions, then
   `Seed.load` drops the PK, COPYs in 6 parallel connections, rebuilds the PK and ANALYZEs
   (`src/main/kotlin/playground/Seed.kt:70`).
   Expect this to be **slower than the laptop's 59.3 s**: gp3 at 50 GB gives 3000 IOPS / 125 MB/s
   baseline, and 4 GB of heap plus a 1.9 GB index build has to land on it. Budget 10–20 min and
   record it; it is itself a data point.
   `Seed` already sets `maintenance_work_mem = '512MB'` for the index build, so RDS's lower default
   does not apply.
   **Record the UTC start and end of this phase.** Per D7 it is the only sustained load in the
   session, so it is the one event the 5-minute credit metrics can actually resolve — the credit
   balance should be visibly pinned at zero with `CPUSurplusCreditBalance` climbing through it.
6. Sanity: `psql -f verify.sql` (side-by-side EXPLAIN, expect 1 partition vs 39) and
   `SHOW shared_buffers` to confirm D1.

### Phase 3 — the sweep (~25 min)

1. Start the server on EC2: `bin/kotlin-playground serve`.
2. `./verify-api.sh` — one request must move exactly one partition's scan counter.
3. `./sweep.sh` drives the whole thing. It:
   - runs a **discarded warm-up** first (500 req/s for 60 s), because a cold 4 GB cache against a
     6 GB dataset would otherwise make the first rate in the sweep look worst;
   - sweeps `40, 500, 1000, 2000, 3000, 4000` req/s at 20 s each — the same rates as the laptop
     table, per D7, so the two are directly comparable;
   - leaves a 30 s gap between runs. `pg_stat_user_tables` flushes at most once per second per
     backend; `LoadTest` settles 2 s on each side, but back-to-back runs still bleed one run's scans
     into the next — one local run reported 4.93 scans/request purely as this artifact;
   - writes `runs.csv` with a UTC millisecond window per rate. **This file is what makes D6 work**:
     without the windows there is no way to slice the 1 s CPU series per rate.
4. Push past 4000 req/s until either errors appear or `sched lag` rises, to find the actual knee.
   The laptop never found one. Per D5 the knee will not come from credit throttling — this instance
   cannot throttle — so anything found here is a real limit of the design or the box.

### Phase 4 — capture and record (~20 min)

**From `LoadTest.print`, per rate:** achieved TPS, errors, service p50/p90/p95/p99/max, sched lag,
scans/request. `sweep.sh` tees all of it to `sweep.log`.

**From psql, per run:** `pg_statio_user_tables` heap and index cache hit ratio (the number that was
43% / 75% locally).

**From AWS,** on the laptop, over the window spanned by `runs.csv`:

```
infra/metrics/collect.sh <start_ms> <end_ms>
infra/metrics/credit-burn.py runs.csv os-cpu.csv
```

`collect.sh` reads the resource ids straight out of `terraform output` and writes two files:

- `os-cpu.csv` — the 1 s series from the `RDSOSMetrics` log group: `cpu_total`, plus `user`,
  `system`, `wait` and `steal` breakdowns. `wait` separates storage stalls from real CPU, and
  `steal` is worth watching on a burstable instance specifically.
- `credits.csv` — `CPUUtilization`, `CPUCreditUsage`, `CPUCreditBalance`,
  `CPUSurplusCreditBalance`, `CPUSurplusCreditsCharged` at their native 5-minute period. Coarse
  ground truth only, per D6.

`credit-burn.py` joins `runs.csv` against `os-cpu.csv` and prints, per rate: sample count, average
and peak CPU, credits/min spent, **net credits/min** against the 1.6/min baseline, and total credits
consumed by that run. Output shape:

```
  rate  samples  cpu avg%  cpu max%  spend/min   net/min   credits
   500       20      20.0      20.0       0.80     -0.80    -0.267
  4000       20      90.0      90.0       3.60     +2.00    +0.667
```

A negative `net/min` means the run accrued credits rather than consuming them. **The rate at which
`net/min` first turns positive is the answer to the credit question** — the point where the
benchmark stops living inside the baseline and starts drawing down burst capacity.

Write a new **"Benchmark: 50M rows on RDS"** section into `FINDINGS.md` beside the existing laptop
table, keeping both, plus a **"Credit consumption"** subsection carrying the `credit-burn.py` table
and the crossover rate. The laptop numbers are not deleted — the contrast is the finding.

### Phase 5 — teardown (mandatory, same session)

`terraform destroy`, then confirm by hand that no RDS instance, snapshot, EC2 instance or EBS volume
survives. This is the step that actually costs money if skipped.

Two things `destroy` will **not** remove, because Terraform never created them:
- the `RDSOSMetrics` CloudWatch Logs group, created by the Enhanced Monitoring service itself.
  Retention is indefinite by default, and the group is **shared by every Enhanced Monitoring RDS
  instance in the account**. Collect the CSVs *before* destroying. Only delete the group outright
  (`aws logs delete-log-group --log-group-name RDSOSMetrics`) after confirming no other RDS instance
  in this account uses it; otherwise just set a retention policy. The stored volume here is small —
  a few thousand JSON events — so leaving it is not a real cost.
- Performance Insights data, which ages out on its own after the 7-day free-tier retention.

## Optional Phase 6 — re-run the UUIDv4 comparison here

The v4-vs-v7 comparison is being run locally first (see `KEY-COMPARISON.md`). If its write-path
result turns out to be cache-sensitive rather than structural, it is worth repeating on this
instance while it exists: a correctly-sized buffer cache is exactly what a cache-locality
comparison needs, and RDS supplies one for free (D1).

Incremental cost: one more load plus a second sweep, roughly +40 min and +$0.30. It raises the
storage requirement from 50 GB to 100 GB, so decide before Phase 1.

## Risks

- **R1 — burstable throttling. Void.** See D5: RDS T4g runs in Unlimited mode with no standard
  mode to select, so the instance cannot throttle on credit exhaustion. It bursts and bills surplus
  instead. What replaces this risk is a *billing* one — a sustained run past baseline accrues
  surplus credits at ~$0.075/vCPU-hour. Over a 1.5-hour session with ~10 minutes of genuine
  above-baseline load that is cents, not dollars, but it is not zero and it does not appear in the
  instance-hour price.
- **R2 — the p50 floor moves.** Same-AZ EC2→RDS adds ~0.3–0.6 ms network RTT per query that the
  laptop's loopback did not have. Expect p50 to look *worse* than the local 0.4–0.8 ms even though
  the database is strictly better. State this in `FINDINGS.md` before the table, or the RDS run
  reads as a regression.
- **R3 — forgotten teardown.** Mitigated by Terraform and by destroying in the same session.
  `skip_final_snapshot = true` is what stops a snapshot outliving the run.
- **R4 — gp3 throughput caps the load.** 125 MB/s baseline against ~6 GB of writes plus index build.
  If the load exceeds ~20 min, raise `iops`/`throughput` on the gp3 volume rather than waiting.
- **R5 — generator and server share 4 vCPUs.** Watch `sched lag`; escalate the EC2 to `c7g.2xlarge`
  if it is non-trivial. Never fold sched lag into the reported service time.
- **R7 — the sweep may never leave the baseline.** A 20 s point lookup sweep against a warm 4 GB
  cache is cheap. If even 4000 req/s keeps average CPU under 40%, `net/min` stays negative at every
  rate and the honest answer to the credit question is "this workload never consumes credits at
  all". That is a legitimate result, not a failed measurement — but it means the crossover has to be
  found by pushing past 4000 (Phase 3.5) rather than read off the planned sweep.
- **R8 — 1 s monitoring is not guaranteed to deliver one sample per second.** If `os-cpu.csv` comes
  back materially short of one row per second of window, the per-run averages are drawn from a thin
  sample and the derived credit figures inherit that error. `credit-burn.py` prints the sample count
  per run precisely so this is visible rather than silent; at 20 s per rate, expect ~20.
- **R6 — no AWS account is configured here.** Phase 0 is a hard human gate; everything downstream
  blocks on it.

## Verification

The run is correct if all of the following hold:

1. `SHOW shared_buffers` reports ~4 GB (D1) and `SELECT version()` reports 16.x (D3).
2. `psql -f verify.sql` prints `partitions scanned: with timestamp = 1, by id alone = 39`.
3. `./verify-api.sh` moves exactly one partition's scan counter.
4. Every sweep row reports **~1.00 scans/request** — the invariant that has held at every rate
   locally and must survive the move to RDS.
5. Errors are 0 at every rate below the knee.
6. Cache hit ratio is materially above the local 43% / 75%; if it is not, D1 did not take effect and
   the tail numbers are still measuring the wrong thing.
7. `os-cpu.csv` carries roughly one sample per second of window (R8), and `credit-burn.py` reports a
   non-zero sample count for every rate in `runs.csv`.
8. The derived credit figures reconcile with the coarse ones: summing `net/min x duration` across
   the whole session should be consistent in sign and rough magnitude with the `CPUCreditBalance` /
   `CPUSurplusCreditBalance` movement in `credits.csv`. They will not match exactly — 5-minute
   averaging guarantees that — but a contradiction in *sign* means the derivation is wrong.
9. `terraform destroy` completes and the console shows no surviving RDS instance, snapshot, EC2
   instance, or EBS volume.
