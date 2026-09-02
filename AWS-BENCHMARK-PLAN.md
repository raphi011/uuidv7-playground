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
  `{DBInstanceClassMemory/32768}` 8 kB pages, which on a 16 GiB instance is ~4 GiB. No custom
  parameter group needed. Verify with `SHOW shared_buffers` rather than assuming.
- **D2** — Load generator and data loader run **on EC2 in the same AZ**, never from the laptop.
  20–50 ms internet RTT would swamp a sub-millisecond p50, and pushing ~3.5 GB of COPY text over a
  home uplink is pointless when the generator can produce it locally.
- **D3** — Postgres **16.x**, not 18. Production targets 16.13 (F6), and 16 is what the Kotlin-side
  timestamp decode exists to accommodate. Benchmarking on 18 would measure the wrong engine.

## Open decision to make at approval time

- **D4 — instance class.** The costing in the previous session assumed `db.t4g.xlarge` (~$0.265/hr).
  **t4g is burstable**: 4 vCPU with a 40%-per-vCPU baseline and a CPU credit balance. A sustained
  sweep to 4000 req/s will drain credits and the instance will throttle mid-run, which is exactly
  the artifact this whole exercise exists to remove.
  **Recommendation: `db.m7g.xlarge`** — same 4 vCPU / 16 GiB, non-burstable, ~$0.39/hr. Over a
  1.5-hour session the delta is roughly $0.18. If t4g is kept instead, set credit mode to
  `unlimited` and record `CPUCreditBalance` alongside every latency number.

## Architecture

```
  laptop ──ssh──►  EC2 (c7g.xlarge, same AZ)          RDS (db.m7g.xlarge, private)
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

There is **no AWS CLI and no `~/.aws` on this machine**. Terraform 1.15.5 is installed;
`~/.ssh/id_ed25519.pub` exists and will be the EC2 key pair.

1. `brew install awscli`
2. The user configures credentials themselves — `aws configure` or SSO. I will not handle keys.
   In this session that is `! aws configure`.
3. `aws sts get-caller-identity` to confirm.
4. Set a billing alarm, or at minimum agree the session ends with `terraform destroy`.
5. `gradle installDist` → `build/install/kotlin-playground/` (bin script + jars). The `application`
   plugin already provides this; **no shadow-jar plugin is needed**. Tar it for upload. Bytecode is
   architecture-independent, so an arm64 EC2 only needs a JRE 21.

### Phase 1 — Terraform (~30 min to write, ~10 min to apply)

New directory `infra/` (nothing else in the repo is touched). Terraform, not shell + AWS CLI,
specifically because `terraform destroy` is a single reliable teardown — the main cost risk here is
forgetting a resource, not the hourly rate.

`infra/main.tf` provisioning:
- `aws_key_pair` from `~/.ssh/id_ed25519.pub`
- `aws_security_group.ec2` — ingress 22 from `var.my_ip`
- `aws_security_group.rds` — ingress 5432 from the EC2 SG only
- `aws_db_subnet_group` over the default VPC's subnets
- `aws_db_instance` — engine `postgres` 16.x, `db.m7g.xlarge`, `allocated_storage = 50`,
  `storage_type = "gp3"`, `publicly_accessible = false`,
  **`skip_final_snapshot = true`** and `deletion_protection = false` — otherwise destroy leaves a
  snapshot billing quietly forever
- `aws_instance` — `c7g.xlarge`, Amazon Linux 2023 arm64, same AZ as the DB
- outputs: EC2 public IP, RDS endpoint

State is local. The repo is not under version control, so nothing gets committed; the `.tfstate`
must survive until destroy. Note `infra/` in `.gitignore` is unnecessary today but
`*.tfstate*` should be added if the repo is ever initialised.

### Phase 2 — bootstrap and load (~15 min)

1. `terraform apply`, wait for the RDS endpoint (RDS creation is ~8–10 min; the wall-clock floor of
   the whole session).
2. `scp` the `installDist` tarball and `verify.sql` / `verify-api.sh` to EC2.
3. On EC2: `sudo dnf install -y java-21-amazon-corretto-headless postgresql16`
4. Export `PGURL`, `PGUSER`, `PGPASSWORD` on the box.
5. `bin/kotlin-playground load 50000000 36` — `Db.applySchema` creates all 39 partitions, then
   `Seed.load` drops the PK, COPYs in 6 parallel connections, rebuilds the PK and ANALYZEs
   (`src/main/kotlin/playground/Seed.kt:70`).
   Expect this to be **slower than the laptop's 59.3 s**: gp3 at 50 GB gives 3000 IOPS / 125 MB/s
   baseline, and 4 GB of heap plus a 1.9 GB index build has to land on it. Budget 10–20 min and
   record it; it is itself a data point.
   `Seed` already sets `maintenance_work_mem = '512MB'` for the index build, so RDS's lower default
   does not apply.
6. Sanity: `psql -f verify.sql` (side-by-side EXPLAIN, expect 1 partition vs 39) and
   `SHOW shared_buffers` to confirm D1.

### Phase 3 — the sweep (~20 min)

1. Start the server on EC2: `bin/kotlin-playground serve`.
2. `./verify-api.sh` — one request must move exactly one partition's scan counter.
3. **Warm-up run first** (e.g. 500 req/s for 60 s), discarded. A cold 4 GB cache against a 6 GB
   dataset would otherwise make the first rate in the sweep look worst.
4. Sweep the same rates as the laptop table so the two are directly comparable:
   `40, 500, 1000, 2000, 3000, 4000` req/s, 20 s each.
   **Leave a gap between runs.** `pg_stat_user_tables` flushes at most once per second per backend;
   `LoadTest` settles 2 s on each side, but back-to-back runs still bleed one run's scans into the
   next — one local run reported 4.93 scans/request purely as this artifact.
5. Push past 4000 req/s until either errors appear or `sched lag` rises, to find the actual knee.
   The laptop never found one.

### Phase 4 — capture and record (~15 min)

Per rate, from `LoadTest.print`: achieved TPS, errors, service p50/p90/p95/p99/max, sched lag,
scans/request. Plus, per run, from psql:
- `pg_statio_user_tables` heap and index cache hit ratio (the number that was 43% / 75% locally)
- CPU and read IOPS from CloudWatch, or Performance Insights (free 7-day retention)

Write a new **"Benchmark: 50M rows on RDS"** section into `FINDINGS.md` beside the existing laptop
table, keeping both. The laptop numbers are not deleted — the contrast is the finding.

### Phase 5 — teardown (mandatory, same session)

`terraform destroy`, then confirm by hand that no RDS instance, snapshot, EC2 instance or EBS volume
survives. This is the step that actually costs money if skipped.

## Optional Phase 6 — re-run the UUIDv4 comparison here

The v4-vs-v7 comparison is being run locally first (see `KEY-COMPARISON.md`). If its write-path
result turns out to be cache-sensitive rather than structural, it is worth repeating on this
instance while it exists: a correctly-sized buffer cache is exactly what a cache-locality
comparison needs, and RDS supplies one for free (D1).

Incremental cost: one more load plus a second sweep, roughly +40 min and +$0.30. It raises the
storage requirement from 50 GB to 100 GB, so decide before Phase 1.

## Risks

- **R1 — burstable throttling.** See D4. The single biggest threat to the numbers' credibility.
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
7. `terraform destroy` completes and the console shows no surviving RDS instance, snapshot, EC2
   instance, or EBS volume.
