-- Verify by hand that a lookup by UUIDv7 key reads one partition, not all of them.
--   psql -h localhost -d uuidv7_playground -f verify.sql

-- Pick a real payment from the oldest partition, so pruning has 14 others to discard.
\set QUIET on
SELECT id, created_at FROM payments ORDER BY created_at LIMIT 1 \gset
\set QUIET off

\echo '=========== 1. lookup WITH the timestamp decoded from the key ==========='
\echo '=========== expect: one Index Scan, no Append node             ==========='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM payments WHERE id = :'id' AND created_at = :'created_at';

\echo ''
\echo '=========== 2. the same lookup by id alone                     ==========='
\echo '=========== expect: Append over all 15 partitions              ==========='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM payments WHERE id = :'id';

\echo ''
\echo '=========== 3. summary ==========='
DO $$
DECLARE
    v_id uuid;
    v_ts timestamptz;
    line text;
    pruned int := 0;
    naive  int := 0;
BEGIN
    SELECT id, created_at INTO v_id, v_ts FROM payments ORDER BY created_at LIMIT 1;

    FOR line IN EXECUTE format(
        'EXPLAIN (ANALYZE, COSTS OFF) SELECT * FROM payments WHERE id = %L AND created_at = %L', v_id, v_ts)
    LOOP
        IF line ~ 'on payments_\d{4}_\d{2}' THEN pruned := pruned + 1; END IF;
    END LOOP;

    FOR line IN EXECUTE format(
        'EXPLAIN (ANALYZE, COSTS OFF) SELECT * FROM payments WHERE id = %L', v_id)
    LOOP
        IF line ~ 'on payments_\d{4}_\d{2}' THEN naive := naive + 1; END IF;
    END LOOP;

    RAISE NOTICE 'partitions scanned: with timestamp = %, by id alone = %', pruned, naive;
END $$;
