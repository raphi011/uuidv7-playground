package playground

import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.sql.DataSource
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.measureTime
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * Head-to-head between two *designs*, not two key types.
 *
 *   A (v7) — id is a UUIDv7 minted at created_at. The partition is a pure function of the key, so a
 *            lookup that knows only the id can still prune to one partition.
 *   B (v4) — id is random and created_at is the wall clock. Nothing links them, so a lookup by id
 *            alone has to open every partition.
 *
 * Both arms are loaded with the primary key **in place** and rows in ascending created_at order,
 * the way an application actually writes. [Seed] deliberately drops the index and rebuilds it at
 * the end, which skips exactly the incremental index maintenance this measures.
 *
 * Every column except `id` is identical between the arms: the created_at sequence is derived from
 * the row number and the rest comes from the same seeded RNG. The key is the only variable.
 */
object KeyComparison {

    private val CURRENCIES = arrayOf("EUR", "USD", "CHF", "GBP")
    private val STATUSES = arrayOf("PENDING", "SETTLED", "SETTLED", "SETTLED", "REFUNDED", "FAILED")

    enum class KeyKind(val table: String) {
        V7("bench_v7"),
        V4("bench_v4"),
    }

    data class ArmResult(
        val kind: KeyKind,
        val rows: Long,
        val duration: Duration,
        val walRecords: Long,
        val walFpi: Long,
        val walBytes: Long,
        val heapBytes: Long,
        val indexBytes: Long,
        val leafPages: Long,
        val avgLeafDensity: Double,
        val leafFragmentation: Double,
        val idxBlksRead: Long,
        val idxBlksHit: Long,
        val sample: List<Pair<Uuid, Instant>>,
    ) {
        val rowsPerSecond get() = rows / duration.inWholeMilliseconds.toDouble() * 1000
        val idxHitRatio get() = idxBlksHit.toDouble() / maxOf(1, idxBlksHit + idxBlksRead)
    }

    /**
     * Rebuilds [kind]'s table from scratch and inserts [rows] rows spread evenly over the [months]
     * months ending at the start of the current month, in ascending created_at order.
     */
    fun load(
        ds: DataSource,
        kind: KeyKind,
        rows: Long,
        months: Int,
        batchSize: Int = 1_000,
        sampleSize: Int = 20_000,
        seed: Int = 42,
    ): ArmResult {
        require(rows > 0 && months > 0)
        val endExclusive = OffsetDateTime.now(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1).plusMonths(1)
        val start = endExclusive.minusMonths(months.toLong())

        createSchema(ds, kind, start, months)

        val fromMillis = start.toInstant().toEpochMilli()
        val untilMillis = endExclusive.toInstant().toEpochMilli()
        // Ascending, evenly spaced, deterministic — identical for both arms.
        val stepMillis = (untilMillis - fromMillis).toDouble() / rows

        val sampleEvery = maxOf(1, (rows / maxOf(1, sampleSize)).toInt())
        val sample = ArrayList<Pair<Uuid, Instant>>(sampleSize + 1)
        val random = Random(seed)

        exec(ds, "CHECKPOINT")
        exec(ds, "SELECT pg_stat_reset_shared('wal')")

        val duration = measureTime {
            ds.connection.use { conn ->
                conn.autoCommit = false
                conn.createStatement().use { it.execute("SET synchronous_commit = off") }
                conn.prepareStatement(insertInto(kind.table)).use { st ->
                    for (i in 0 until rows) {
                        val at = Instant.fromEpochMilliseconds(fromMillis + (i * stepMillis).toLong())
                        val id = when (kind) {
                            KeyKind.V7 -> Uuid.generateV7NonMonotonicAt(at)
                            KeyKind.V4 -> Uuid.random()
                        }
                        if (i % sampleEvery == 0L) sample += id to at

                        st.setObject(1, id.toJavaUuid())
                        st.setObject(2, at.toOffsetDateTime())
                        st.setLong(3, random.nextLong(50, 500_000))
                        st.setString(4, CURRENCIES[random.nextInt(CURRENCIES.size)])
                        st.setString(5, "INV-" + random.nextInt(1, 999_999))
                        st.setString(6, STATUSES[random.nextInt(STATUSES.size)])
                        st.addBatch()

                        if ((i + 1) % batchSize == 0L) {
                            st.executeBatch()
                            conn.commit()
                        }
                    }
                    st.executeBatch()
                    conn.commit()
                }
            }
        }

        exec(ds, "ANALYZE ${kind.table}")
        // pg_stat_wal and pg_statio are flushed at most once a second per backend.
        exec(ds, "SELECT pg_sleep(2)")

        val wal = queryRow(ds, "SELECT wal_records, wal_fpi, wal_bytes FROM pg_stat_wal") {
            Triple(it.getLong(1), it.getLong(2), it.getLong(3))
        }
        val sizes = queryRow(ds, SIZES, kind.table) { it.getLong(1) to it.getLong(2) }
        val index = queryRow(ds, INDEX_STATS, kind.table) {
            Triple(it.getLong(1), it.getDouble(2), it.getDouble(3))
        }
        val statio = queryRow(ds, STATIO, "${kind.table}\\_%") { it.getLong(1) to it.getLong(2) }

        return ArmResult(
            kind = kind,
            rows = rows,
            duration = duration,
            walRecords = wal.first,
            walFpi = wal.second,
            walBytes = wal.third,
            heapBytes = sizes.first,
            indexBytes = sizes.second,
            leafPages = index.first,
            avgLeafDensity = index.second,
            leafFragmentation = index.third,
            idxBlksRead = statio.first,
            idxBlksHit = statio.second,
            sample = sample,
        )
    }

    private fun createSchema(ds: DataSource, kind: KeyKind, start: OffsetDateTime, months: Int) {
        val ddl = buildString {
            append("DROP TABLE IF EXISTS ${kind.table} CASCADE;\n")
            append(
                """
                CREATE TABLE ${kind.table} (
                    id           uuid        NOT NULL,
                    created_at   timestamptz NOT NULL,
                    amount_cents bigint      NOT NULL,
                    currency     text        NOT NULL,
                    reference    text        NOT NULL,
                    status       text        NOT NULL,
                    PRIMARY KEY (id, created_at)
                ) PARTITION BY RANGE (created_at);
                """.trimIndent()
            )
            append('\n')
            repeat(months) { i ->
                val from = start.plusMonths(i.toLong())
                val to = from.plusMonths(1)
                append(
                    "CREATE TABLE %s_%d_%02d PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s');\n"
                        .format(kind.table, from.year, from.monthValue, kind.table, from, to)
                )
            }
        }
        ds.connection.use { conn -> conn.createStatement().use { it.execute(ddl) } }
    }

    private fun insertInto(table: String) =
        "INSERT INTO $table (id, created_at, amount_cents, currency, reference, status) " +
            "VALUES (?, ?, ?, ?, ?, ?)"

    private fun exec(ds: DataSource, sql: String) =
        ds.connection.use { conn: Connection -> conn.createStatement().use { it.execute(sql) } }

    private fun <T> queryRow(ds: DataSource, sql: String, vararg args: String, read: (java.sql.ResultSet) -> T): T =
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setString(i + 1, a) }
                st.executeQuery().use { rs -> rs.next(); read(rs) }
            }
        }

    private const val SIZES = """
        SELECT coalesce(sum(pg_relation_size(c.oid)), 0), coalesce(sum(pg_indexes_size(c.oid)), 0)
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.relname = ?
    """

    /** Leaf density and fragmentation, averaged over the partitions' btrees weighted by leaf pages. */
    private const val INDEX_STATS = """
        SELECT coalesce(sum(s.leaf_pages), 0)::bigint,
               coalesce(sum(s.avg_leaf_density * s.leaf_pages) / nullif(sum(s.leaf_pages), 0), 0),
               coalesce(sum(s.leaf_fragmentation * s.leaf_pages) / nullif(sum(s.leaf_pages), 0), 0)
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_class p ON p.oid = i.inhparent
        JOIN pg_index x ON x.indrelid = c.oid
        CROSS JOIN LATERAL pgstatindex(x.indexrelid) s
        WHERE p.relname = ?
    """

    private const val STATIO = """
        SELECT coalesce(sum(idx_blks_read), 0), coalesce(sum(idx_blks_hit), 0)
        FROM pg_statio_user_tables WHERE relname LIKE ?
    """
}
