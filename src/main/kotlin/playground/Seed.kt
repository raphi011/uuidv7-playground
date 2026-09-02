package playground

import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.measureTime
import kotlin.uuid.Uuid
import org.postgresql.PGConnection
import org.postgresql.copy.PGCopyOutputStream

/**
 * Bulk loader for benchmark data. Streams rows straight into each monthly partition with COPY,
 * one connection per month in parallel, and drops the primary key for the duration of the load so
 * the btrees are built once at the end instead of maintained row by row.
 *
 * The generated ids are not reproducible across runs — Uuid.generateV7NonMonotonicAt draws its
 * random bits from SecureRandom. Row counts, the month distribution and every non-id column are
 * reproducible from [seed]. Ids that a later run needs are persisted by [samplePath].
 */
object Seed {

    private val CURRENCIES = arrayOf("EUR", "USD", "CHF", "GBP")
    private val STATUSES = arrayOf("PENDING", "SETTLED", "SETTLED", "SETTLED", "REFUNDED", "FAILED")

    private const val COPY_COLUMNS = "id, created_at, amount_cents, currency, reference, status"

    data class Result(
        val rows: Long,
        val months: Int,
        val sampled: Int,
        val samplePath: Path?,
        val loadDuration: Duration,
        val indexDuration: Duration,
    )

    fun load(
        ds: DataSource,
        totalRows: Long,
        months: Int,
        samplePath: Path? = null,
        sampleSize: Int = 50_000,
        parallelism: Int = 6,
        seed: Int = 42,
        // Must land exactly on a month boundary at midnight UTC, or generated rows straddle two
        // partitions and COPY is rejected by the partition constraint.
        endExclusive: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1).plusMonths(1),
    ): Result {
        require(months > 0) { "months must be positive" }
        require(totalRows >= months) { "need at least one row per month" }

        val monthStarts = (months downTo 1).map { endExclusive.minusMonths(it.toLong()) }
        val perMonth = totalRows / months
        val remainder = totalRows % months

        // One in every `sampleEvery` rows is remembered, so the load generator has real ids to ask for.
        val sampleEvery = maxOf(1, (totalRows / maxOf(1, sampleSize)).toInt())
        val sample = java.util.concurrent.ConcurrentLinkedQueue<Uuid>()

        exec(ds, "TRUNCATE payments")
        exec(ds, "ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_pkey")

        val loadDuration = measureTime {
            val pool = Executors.newFixedThreadPool(parallelism)
            try {
                monthStarts.mapIndexed { index, monthStart ->
                    val rows = perMonth + if (index < remainder) 1 else 0
                    pool.submit {
                        copyMonth(ds, monthStart, rows, Random(seed + index), sampleEvery, sample::add)
                        println("  ${partitionOf(monthStart)}  $rows rows")
                    }
                }.forEach { it.get() }
            } finally {
                pool.shutdown()
                pool.awaitTermination(1, TimeUnit.HOURS)
            }
        }

        val indexDuration = measureTime {
            exec(ds, "SET maintenance_work_mem = '512MB'; ALTER TABLE payments ADD PRIMARY KEY (id, created_at)")
            exec(ds, "ANALYZE payments")
        }

        samplePath?.let { path ->
            path.parent?.let(Files::createDirectories)
            Files.newBufferedWriter(path).use { out ->
                sample.forEach { out.write(it.toString()); out.newLine() }
            }
        }

        return Result(totalRows, months, sample.size, samplePath, loadDuration, indexDuration)
    }

    /** Reads back the ids persisted by a previous [load]. */
    fun readSample(path: Path): List<Uuid> =
        Files.readAllLines(path).mapNotNull { Uuid.parseOrNull(it.trim()) }

    private fun copyMonth(
        ds: DataSource,
        monthStart: OffsetDateTime,
        rows: Long,
        random: Random,
        sampleEvery: Int,
        onSampled: (Uuid) -> Unit,
    ) {
        val from = monthStart.toInstant().toEpochMilli()
        val until = monthStart.plusMonths(1).toInstant().toEpochMilli()
        val statement = "COPY ${partitionOf(monthStart)} ($COPY_COLUMNS) FROM STDIN"

        ds.connection.use { conn ->
            conn.createStatement().use { it.execute("SET synchronous_commit = off") }
            PGCopyOutputStream(conn.unwrap(PGConnection::class.java), statement).bufferedWriter().use { out ->
                val row = StringBuilder(128)
                for (i in 0 until rows) {
                    val at = Instant.fromEpochMilliseconds(random.nextLong(from, until))
                    val id = Uuid.generateV7NonMonotonicAt(at)
                    if (i % sampleEvery == 0L) onSampled(id)

                    row.setLength(0)
                    row.append(id).append('\t')
                        .append(at.toOffsetDateTime()).append('\t')
                        .append(random.nextLong(50, 500_000)).append('\t')
                        .append(CURRENCIES[random.nextInt(CURRENCIES.size)]).append('\t')
                        .append("INV-").append(random.nextInt(1, 999_999)).append('\t')
                        .append(STATUSES[random.nextInt(STATUSES.size)]).append('\n')
                    out.append(row)
                }
            }
        }
    }

    private fun partitionOf(monthStart: OffsetDateTime) =
        "payments_%d_%02d".format(monthStart.year, monthStart.monthValue)

    private fun exec(ds: DataSource, sql: String) =
        ds.connection.use { conn -> conn.createStatement().use { it.execute(sql) } }
}
