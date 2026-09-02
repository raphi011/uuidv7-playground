# UUIDv7 keys on a partitioned Postgres table

Playground for one question: can a REST `GET /payments/{id}` hit a single monthly partition when
the caller supplies **only** the id?

Yes. The partition is derivable from the key, because a UUIDv7 carries its own creation timestamp.

## What we built

| Path | Purpose |
| --- | --- |
| `src/main/resources/schema.sql` | `payments`, RANGE-partitioned by month on `created_at`, 39 partitions |
| `src/main/kotlin/playground/UuidV7.kt` | `internal fun Uuid.v7Timestamp()` — unpacks the 48-bit timestamp |
| `src/main/kotlin/playground/PaymentRepository.kt` | Owns the decode; nothing above it knows about it |
| `src/main/kotlin/playground/Api.kt` | Ktor: `POST /payments`, `GET /payments/{id}` |
| `src/main/kotlin/playground/Seed.kt` | Parallel COPY bulk loader, any volume; persists an id sample |
| `src/main/kotlin/playground/LoadTest.kt` | Fixed-rate GET generator, open model |
| `src/main/kotlin/playground/KeyComparison.kt` | v7-vs-v4 write path: page splits, WAL, index size |
| `src/main/kotlin/playground/KeyComparisonRun.kt` | v7-vs-v4 read path, and the reporting for both |
| `src/test/kotlin/playground/PartitionPruningTest.kt` | The proof |
| `load-data.sh`, `load-test.sh` | Dataset generation and load runs |
| `verify.sql`, `verify-api.sh` | Manual verification, EXPLAIN and live-server flavours |

```
./load-data.sh 50000000 36   # 50M payments over 36 months, replaces existing data
gradle run --args=serve      # :8080
./load-test.sh 500 20        # 500 req/s for 20s against the running server
gradle run --args="compare 10000000 1"   # v7 vs v4, see KEY-COMPARISON.md
gradle test                  # 14 tests
```

Stack: Kotlin 2.4.0, Ktor 3.5.2, HikariCP (pool of 8), plain JDBC. Local Postgres 18.6 — but the
code targets **PG 16**, see F6.

## How it works

1. `Uuid.generateV7()` mints a key whose first 48 bits are `unix_ts_ms`.
2. The repository decodes that timestamp and writes it to `created_at`. The partition is therefore a
   pure function of the key.
3. `findById` decodes the same timestamp again and adds it to the WHERE clause:

```sql
SELECT ... FROM payments WHERE id = ? AND created_at = ?
```

Postgres prunes to one partition. Without the second predicate it must consider all 39.

```kotlin
internal fun Uuid.v7Timestamp(): Instant = toLongs { msb, _ ->
    val version = (msb ushr 12) and 0xF
    require(version == 7L) { "not a v7 UUID: version $version" }
    Instant.fromEpochMilliseconds(msb ushr 16)   // ushr, not shr
}
```

## Findings

**F1 — Kotlin 2.3+ generates v7 natively.** `Uuid.generateV7()` is **strictly monotonic within the
process** — 10k consecutive ids come out pre-sorted with no duplicates. `Uuid.LEXICAL_ORDER` is
deprecated in 2.4; natural order is lexical now, so plain `.sorted()` is correct.

**F2 — Nothing anywhere reads the timestamp back.** Not `kotlin.uuid.Uuid` (no `version()`,
`variant()`, or timestamp accessor at all), not `java.util.UUID`. `toLongs { msb, _ -> msb ushr 16 }`
is the floor. Verified by dumping the full public surface of `kotlin.uuid` from the 2.4.0 jar.

**F3 — JDK 26 added `UUID.ofEpochMillis(long)`**, generation only; `timestamp()` is still scoped to
version 1 and throws `UnsupportedOperationException` on v7, as do `node()` and `clockSequence()`.
We're on JDK 21 regardless.

**F4 — `java.util.UUID` parses v7 without complaint.** `fromString`/`toString`/`equals` round-trip
exactly, `version()` returns 7, `variant()` returns 2. `compareTo` also agrees with lexicographic
string order: it compares both halves as *signed* longs, but the variant bit pins the sign bit of
`leastSignificantBits` to 1 for every RFC UUID, and `unix_ts_ms` keeps the other sign bit at 0 until
the year 10889. (We initially assumed the opposite and wrote a test asserting disagreement; it
failed, and the assumption was wrong, not the code.)

**F5 — A unique constraint on a partitioned table must include the partition key.**

```
ERROR:  unique constraint on partitioned table must include all partitioning columns
DETAIL:  PRIMARY KEY constraint on table "t1" lacks column "created_at".
```

Hence `PRIMARY KEY (id, created_at)`. Same error for a bare `CREATE UNIQUE INDEX ON t (id)`. A
non-unique `CREATE INDEX ON payments (id)` is allowed, if you'd rather not imply a guarantee.

**F6 — `uuid_extract_timestamp()` exists, but not for us.** PG 17 added it (plus
`uuid_extract_version()`); PG 18 added `uuidv7()`/`uuidv4()` generators. All immutable, so usable in
a CHECK. **Our target is PG 16, which has only `gen_random_uuid()`.** A portable `IMMUTABLE` SQL
function reproducing the shift was written and validated against all rows — it prunes identically,
even under `plan_cache_mode = force_generic_plan` — but was rejected in favour of keeping the decode
in Kotlin, hidden inside the repository, rather than duplicating the bit-twiddling in SQL.

**F7 — A generated column cannot be a partition key.** `created_at timestamptz GENERATED ALWAYS AS
(uuid_extract_timestamp(id)) STORED` would have made the invariant structural.
`ERROR: cannot use generated column in partition key`. So `created_at` stays an ordinary column the
application writes — which is the root of R1.

**F8 — Partition bounds need an explicit UTC offset.** `FROM ('2026-01-01')` is parsed in the
*creating session's* TimeZone, silently shifting every boundary. Written as
`FROM ('2026-01-01 00:00:00+00')`.

**F9 — v4 is the cache-hostile key, not v7.** Head-to-head at 10M rows per arm, identical in every
column but the key, primary key in place, inserted in `created_at` order. With the index larger than
`shared_buffers`, v4 took **2.3× longer** to insert, built a **32% larger** index (68% leaf density
vs 90%, 50% leaf fragmentation vs 0%), wrote **3.9× the full-page images** and **67% more WAL**, and
did **4,105,465 index block reads against v7's 1**. Sizing the cache to hold the whole index removes
the I/O but not the penalty: v4 is still 44% slower, 31% larger and 56% more WAL. On the read path
the key type is a tie — what v7 actually buys is that `created_at` is always derivable, so a caller
holding only an id can still prune. Full write-up and method in `KEY-COMPARISON.md`.

## The proof

`PartitionPruningTest`, 7 tests, all passing:

| Query | Partitions scanned | Buffers |
| --- | --- | --- |
| `WHERE id = ? AND created_at = ?` | **1** | 4 |
| `WHERE id = ?` | 39 | 218 |

(At 120k rows over 15 partitions the same comparison was 3 vs 50 buffers. The gap widens with
partition count, which is the point — see R3.)

- With literals, the pruned plan is a bare `Index Scan using payments_2023_10_pkey` — **no `Append`
  node at all**, so the other 38 partitions never entered the plan.
- With bind parameters executed 10× on one connection (pgjdbc switches to a server-side prepared
  statement after 5), still one partition. This matters: it's the plan a long-lived API connection
  actually gets.
- Under forced generic plan, `Subplans Removed: 38` — pruning falls back to execution time and still
  reads 4 buffers.
- 500 sampled rows: `tableoid` always equals the partition the key's timestamp names.
- Live server, via `pg_stat_user_tables` counters rather than EXPLAIN: a `GET` moves the scan counter
  of exactly one partition, and it's the one the key points at.

## Benchmark: 50M rows

Generated with `./load-data.sh 50000000 36` — 36 monthly partitions, ~1.39M rows each,
2023-10 through 2026-09.

| | |
| --- | --- |
| Rows | 50,000,000 |
| Heap / indexes / total | 4032 MB / 1934 MB / **5969 MB** (119 bytes/row) |
| COPY (6 parallel connections, PK dropped) | 59.3 s (~840k rows/s) |
| PK rebuild + ANALYZE | 2 m 11 s |
| Sampled ids persisted | 50,004 → `build/sample-ids.txt` |

Disk headroom: the volume had 99 GiB free, so ~700M rows is the hard ceiling. 50M used 6 GB.

### Under load

Fixed-rate GETs against the Ktor server, random ids drawn from the persisted sample:

| Rate | Achieved | Errors | service p50 | p95 | p99 | scans/request |
| --- | --- | --- | --- | --- | --- | --- |
| 40 | 40.0 | 0 | 1.12 ms | 2.25 ms | 13.9 ms | 0.99 |
| 500 | 500.0 | 0 | 0.80 ms | 3.23 ms | 22.1 ms | 0.96 |
| 1000 | 999.8 | 0 | 0.80 ms | 29.4 ms | 162.9 ms | 0.96 |
| 2000 | 1999.9 | 0 | 0.56 ms | 24.1 ms | 86.8 ms | 0.98 |
| 3000 | 2999.5 | 0 | 0.49 ms | 60.7 ms | 313.2 ms | 0.98 |
| 4000 | 3999.6 | 0 | 0.41 ms | 35.0 ms | 105.9 ms | 0.96 |

**The result that matters: ~1 partition scan per request at every rate**, at 50M rows across 39
partitions. Unpruned it would be 39.

Requested throughput was met exactly up to 4000 req/s with zero errors, and p50 stayed
sub-millisecond. What does *not* hold is the tail: p95/p99 degrade sharply above ~500 req/s and are
**non-monotonic** across rates — 1000 req/s produced a worse p99 than 4000 req/s. So this is not a
saturation knee. The cause is I/O, not the query plan: `shared_buffers` is the stock 128 MB against
a 6 GB dataset, and the partitions were running at 43% heap / 75% index cache hit, so most lookups
fetch cold pages. Random ids across three years defeat caching by construction. Treat the tail
numbers as a property of this laptop, not of the design.

Two measurement traps found while building this, both now handled:

- The generator's own scheduling lag was initially counted as server latency. At 40 req/s
  `LockSupport.parkNanos` overshoots by ~3–5 ms on macOS, which showed up as a 5.36 ms p50 — worse
  than the *4000* req/s run. `service` and `sched lag` are now reported separately; service time at
  40 req/s is 1.12 ms.
- Partition-scan deltas are contaminated by the previous run if the stats collector has not
  flushed. One run reported 4.93 scans/request, an artifact of the preceding 4000 req/s run. The
  generator settles 2 s on both sides of the measurement window; back-to-back runs still need a gap.

## Pros

- **P1** — Point lookup by primary key alone, no secondary id→partition lookup table, no global index.
- **P2** — Time-ordered inserts: appends to the right-hand edge of the btree instead of scattering
  like v4, so fewer page splits and less WAL. **Measured** — see F9 and `KEY-COMPARISON.md`.
- **P3** — Retention is `DROP TABLE payments_2025_10`, instant, no `DELETE` churn.
- **P4** — No extensions, no PG 17+ features. Runs on PG 16 as-is.
- **P5** — Ordering is free: sorting by id is sorting by time.

## Cons

- **C1** — `created_at` duplicates data already inside the key. Denormalised by necessity (F7).
- **C2** — Every lookup path must carry `created_at`. A foreign key referencing `payments` must
  reference the full `(id, created_at)`, so **referencing tables have to store both columns** too.
  This propagates.
- **C3** — No database-enforced global uniqueness of `id` (F5). It's per-partition only.
- **C4** — v7 ids leak creation time to anyone holding one. Fine for internal ids, a consideration
  for public ones.

## Risks

- **R1 — The invariant is application-enforced.** `created_at = v7Timestamp(id)` holds only because
  the repository makes it hold. A bug writing a different `created_at` files the row in the wrong
  partition, and `findById` then looks in the *right* partition and returns `null` — the row exists
  but is invisible to the API. Verified reachable: inserting the same id twice with two different
  `created_at` values succeeds and produces two rows in two partitions.
  *Mitigation:* on PG 17+, `CHECK (created_at = uuid_extract_timestamp(id))`. On PG 16 this costs a
  hand-rolled SQL function — deliberately not taken.
- **R2 — Partition rotation is unsolved here.** 39 partitions are pre-created through 2026-12. An
  `INSERT` past the last bound fails outright. Production needs a rotation job (`pg_partman`, or a
  scheduled `CREATE TABLE ... PARTITION OF`), plus a `DEFAULT` partition or monitoring to catch the
  gap.
- **R3 — Pruning is easy to lose silently.** `WHERE id = ?` still returns the right answer, just
  scans every partition. Cost grows linearly with partition count: 15 today, 60 after five years.
  An ORM, an ad-hoc join, or a report query can reintroduce it without any error surfacing.
  *Mitigation:* the `verify-api.sh` counter check catches it on real traffic.
- **R4 — `created_at` comes from the application clock**, not the database. Clock skew or a wrong
  container clock backdates rows into a neighbouring partition. They stay findable, since the lookup
  decodes the same value, but partition boundaries stop meaning "created that month".
- **R5 — Monotonicity is per-process** (F1). Several app instances interleave within a millisecond.
  Harmless for partitioning, relevant if you rely on id order as a global sequence.
- **R6 — Millisecond granularity is the join key.** `created_at` must round-trip exactly.
  `timestamptz` stores microseconds, so ms values are exact today; introducing any rounding,
  truncation, or a `timestamp(0)` column would break every lookup at once.

## Verify it yourself

```
gradle test --tests '*PartitionPruningTest*'          # plans + counts
psql -h localhost -d uuidv7_playground -f verify.sql  # side-by-side EXPLAIN
gradle run --args=serve && ./verify-api.sh            # live server, one request
gradle run --args=serve && ./load-test.sh 500 20      # live server, under sustained load
```

## Reproducing the dataset

`./load-data.sh <rows> <months>` regenerates from scratch at any volume — it truncates first, so a
run is repeatable rather than additive. Row counts, the month distribution and every non-id column
come from a fixed RNG seed.

**Ids are not reproducible across runs.** `Uuid.generateV7NonMonotonicAt` draws its random bits from
`SecureRandom`, so a rerun produces a different key set. That is why the loader persists a sample
(`build/sample-ids.txt`, ~50k ids) instead of expecting the load generator to recompute them: the
query side is repeatable even though the ids are not. Reproducible ids would mean hand-rolling a
seeded v7 generator, which was judged not worth owning.
