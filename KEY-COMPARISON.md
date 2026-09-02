# UUIDv7 vs UUIDv4 on a partitioned Postgres table

The question that prompted this: *"uuidv7 is not very nice on postgres indexes / cache because of
its randomness."*

**Measured, that is backwards.** UUIDv4 is the fully random one. A UUIDv7's first 48 bits are a
millisecond Unix timestamp, so v7 inserts append at the right-hand edge of the btree while v4
inserts scatter across all of it. v7 exists specifically to fix v4's index-locality problem, and on
this hardware it does so by a wide margin.

The instinct is right in two narrower senses, both confirmed below:

1. v7 still carries 74 random bits, so ids minted within the same millisecond are mutually
   unordered. That affects ordering *within* a millisecond, not page locality.
2. On the **read** path v7 buys nothing as a key type. A point lookup by a random id is a random
   index descent either way — arms A and C below are identical. What v7 buys on reads is not
   locality but *partition pruning*, and that is a property of the design, not of the key.

## Method

`gradle run --args="compare <rows> <months>"` — `KeyComparison.kt` / `KeyComparisonRun.kt`.

Two tables, `bench_v7` and `bench_v4`, identical in every respect except how `id` is minted:

| | `bench_v7` | `bench_v4` |
| --- | --- | --- |
| `id` | `Uuid.generateV7NonMonotonicAt(created_at)` | `Uuid.random()` |
| `created_at` | the key's own timestamp | the wall clock |
| Partitioning | RANGE by month on `created_at` | identical |
| Primary key | `(id, created_at)` | identical |

The `created_at` sequence is derived from the row number and every other column comes from the same
seeded RNG, so **the key is the only variable**. 10,000,000 rows per arm, inserted in ascending
`created_at` order in batches of 1000, with the **primary key in place** — unlike `Seed`, which drops
the index and rebuilds it at the end and so skips exactly the incremental maintenance being measured
here. `synchronous_commit = off` for both arms, so this measures index work rather than fsync.

Postgres 18.6, `max_wal_size` left at the stock 1 GB, WAL counters from `pg_stat_wal` after a
`CHECKPOINT` and `pg_stat_reset_shared('wal')`. Leaf density and fragmentation from `pgstatindex`,
averaged over the partitions' btrees weighted by leaf pages.

## Write path — one hot partition, index larger than the cache

`compare 10000000 1`, `shared_buffers = 128MB` against a ~400–500 MB index. This is the regime the
original question was about: does the index still fit in cache while it is being written?

| | v7 | v4 | |
| --- | ---: | ---: | --- |
| duration | 1m 00.9s | 2m 18.6s | **2.3× slower** |
| rows/s | 164,093 | 72,163 | |
| heap | 805 MB | 805 MB | identical, as designed |
| index | 387 MB | 512 MB | **+32%** |
| index leaf pages | 49,261 | 65,202 | |
| avg leaf density | 90.0% | 68.1% | |
| leaf fragmentation | 0.0% | 50.0% | |
| WAL records | 20,212,694 | 20,250,533 | same row count, as expected |
| WAL full-page images | 108,796 | 421,391 | **3.9×** |
| WAL bytes | 2,711 MB | 4,524 MB | **+67%** |
| index blocks read | **1** | **4,105,465** | |
| index cache hit | 100.0% | 87.1% | |

One index block read versus four million. v7 only ever touches the rightmost leaf, which stays
resident no matter how small the cache is; v4 touches a uniformly random leaf per insert, so once
the index outgrows `shared_buffers` almost every insert costs a read.

The 90% vs 68% leaf density is the mechanism behind the size difference. Postgres applies a
right-most-page split optimisation: a split at the right edge of the btree leaves the left page
nearly full, while a split in the middle divides 50/50. v7 gets the former on every split and v4 the
latter, which is also why v4's leaf fragmentation sits at exactly 50%.

## Write path — index fits in the cache

`compare 10000000 1`, `shared_buffers = 4GB`. Same layout, but now both indexes are resident.

| | v7 | v4 | |
| --- | ---: | ---: | --- |
| duration | 54.5s | 1m 18.5s | **1.44× slower** |
| rows/s | 183,500 | 127,317 | |
| index | 387 MB | 508 MB | +31% |
| avg leaf density | 90.0% | 68.6% | |
| WAL full-page images | 74,034 | 304,992 | 4.1× |
| WAL bytes | 2,444 MB | 3,818 MB | +56% |
| index cache hit | 100.0% | 100.0% | |

**The v4 penalty is structural, not a cache artifact.** Give v4 a cache large enough to hold its
entire index and it is still 44% slower to insert, still 31% larger, and still writes 56% more WAL.
Caching removes the I/O amplification; it does not remove the page splits or the full-page images
that follow them.

## A note on partition count

`compare 10000000 36` shows a much smaller write-path gap (169k vs 144k rows/s at 128 MB). That is
an artifact of the layout, not a better result: spreading 10M rows over 36 monthly partitions means
only one partition is being written at a time and its btree is only ~14 MB, which fits in any cache.
Real traffic accumulates in one current partition for a month, so the single-partition numbers above
are the representative ones for the write path.

## Read path

`compare 10000000 36`, `shared_buffers = 4GB`, 36 partitions, 5000 point lookups per arm after a
warm-up pass.

| | partitions opened | buffers | µs/lookup |
| --- | ---: | ---: | ---: |
| **A** — v7, `created_at` decoded from the key | **1** | **4** | 50 |
| **B** — v4, id only (nothing to decode) | 36 | 218 | 134 |
| **C** — v4, `created_at` supplied by the caller | 1 | 4 | 48 |
| **D** — v7, id only (pruning thrown away) | 36 | 218 | 99 |

A and C are the same speed. That is the point: **the key type is irrelevant to read performance.**
What decides the read path is whether `created_at` is available at lookup time, and the only thing
v7 contributes is that it can always be *derived* from the id, so callers holding nothing but an id
still prune. With v4 the caller must be handed `created_at` separately (C) or the query degrades to
B — 36 partitions and 218 buffers.

D is v7's own pruning deliberately discarded, and matches B's partition count exactly while running
faster, because the v7 index it scans is 25% smaller.

## Conclusion

- **Write path: v7 wins decisively**, and by more the smaller the cache. 2.3× insert throughput,
  32% smaller index, 3.9× fewer full-page images, 67% less WAL when the index exceeds the cache;
  1.44×, 31%, 4.1× and 56% when it does not.
- **Read path: the key type is a tie** (A vs C). The design is not: v4 has no embedded timestamp, so
  a lookup holding only an id cannot prune and pays 36 partitions / 218 buffers instead of 1 / 4.
- **The premise is inverted.** There is no measured axis on which v4's randomness is kinder to
  Postgres indexes or the buffer cache.

The one real cost of v7 is not performance at all — it is C4 in `FINDINGS.md`: a v7 id leaks its
creation time to whoever holds it.

## Caveats

- 10M rows per arm on a 16 GiB laptop. Absolute throughput is machine-specific; the ratios are the
  result.
- `pg_stat_wal` is cluster-wide, so background autovacuum activity is included in both arms. It is
  small relative to 20M WAL records and applies equally to each.
- Every read-path number here is fully cached (100% index hit). The `FINDINGS.md` 50M-row run is the
  cold-cache read benchmark.
