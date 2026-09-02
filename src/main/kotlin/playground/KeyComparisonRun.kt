package playground

import javax.sql.DataSource
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.measureTime
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import playground.KeyComparison.ArmResult
import playground.KeyComparison.KeyKind

/**
 * Read side of the v7-vs-v4 comparison, and the printing for both sides.
 *
 * The write side ([KeyComparison.load]) answers "does a random key cost more to insert". This side
 * answers the question that actually separates the two *designs*: with only an id in hand, how much
 * of the table does a point lookup have to open?
 */
object KeyComparisonRun {

    data class ReadResult(
        val label: String,
        val partitions: Int,
        val subplansRemoved: Int,
        val blocksTouched: Long,
        val lookups: Int,
        val duration: Duration,
    ) {
        val microsPerLookup get() = duration.inWholeMicroseconds.toDouble() / maxOf(1, lookups)
    }

    /** EXPLAIN once for the plan shape, then time [lookups] real point lookups. */
    fun readPath(
        ds: DataSource,
        label: String,
        table: String,
        sample: List<Pair<Uuid, Instant>>,
        withCreatedAt: Boolean,
        lookups: Int = 5_000,
        warmup: Int = 500,
        random: Random = Random(7),
    ): ReadResult {
        val sql = buildString {
            append("SELECT id, created_at, amount_cents, currency, reference, status FROM ")
            append(table).append(" WHERE id = ?")
            if (withCreatedAt) append(" AND created_at = ?")
        }
        val probe = sample[random.nextInt(sample.size)]
        val plan = Explain.analyzeAsPreparedStatement(ds, sql) { st ->
            st.setObject(1, probe.first.toJavaUuid())
            if (withCreatedAt) st.setObject(2, probe.second.toOffsetDateTime())
        }

        val targets = List(lookups) { sample[random.nextInt(sample.size)] }
        var found = 0
        var duration = Duration.ZERO
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { st ->
                fun run(rows: List<Pair<Uuid, Instant>>) {
                    for ((id, at) in rows) {
                        st.setObject(1, id.toJavaUuid())
                        if (withCreatedAt) st.setObject(2, at.toOffsetDateTime())
                        st.executeQuery().use { rs -> if (rs.next()) found++ }
                    }
                }
                // Warm the connection and let pgjdbc switch to a server-side prepared statement,
                // so the first arm measured is not the only one paying for that.
                run(targets.take(warmup))
                found = 0
                duration = measureTime { run(targets) }
            }
        }
        check(found == lookups) { "$label: only $found of $lookups sampled ids came back" }

        // Plan.partitions is scoped to the `payments_` prefix; these tables are named differently.
        val opened = plan.relations.filter { it.startsWith("${table}_") }.distinct().size
        return ReadResult(label, opened, plan.subplansRemoved, plan.blocksTouched, lookups, duration)
    }

    fun printWrite(arms: List<ArmResult>) {
        println()
        println("=== write path: ${arms.first().rows} rows, primary key in place, ascending created_at ===")
        fun row(label: String, value: (ArmResult) -> String) =
            println("  %-22s %s".format(label, arms.joinToString("") { "%18s".format(value(it)) }))

        println("  %-22s %s".format("", arms.joinToString("") { "%18s".format(it.kind.name) }))
        row("duration") { "${it.duration}" }
        row("rows/s") { "%,.0f".format(it.rowsPerSecond) }
        row("heap") { mb(it.heapBytes) }
        row("index") { mb(it.indexBytes) }
        row("index leaf pages") { "%,d".format(it.leafPages) }
        row("avg leaf density %") { "%.1f".format(it.avgLeafDensity) }
        row("leaf fragmentation %") { "%.1f".format(it.leafFragmentation) }
        row("WAL records") { "%,d".format(it.walRecords) }
        row("WAL full-page images") { "%,d".format(it.walFpi) }
        row("WAL bytes") { mb(it.walBytes) }
        row("index blks read") { "%,d".format(it.idxBlksRead) }
        row("index cache hit %") { "%.1f".format(it.idxHitRatio * 100) }
    }

    fun printRead(reads: List<ReadResult>) {
        println()
        println("=== read path: point lookup by id ===")
        println("  %-46s %11s %9s %14s".format("", "partitions", "buffers", "us/lookup"))
        reads.forEach {
            val partitions = if (it.subplansRemoved > 0) "${it.partitions} (-${it.subplansRemoved})" else "${it.partitions}"
            println("  %-46s %11s %9d %14.0f".format(it.label, partitions, it.blocksTouched, it.microsPerLookup))
        }
    }

    private fun mb(bytes: Long) = "%,.0f MB".format(bytes / 1024.0 / 1024.0)
}
