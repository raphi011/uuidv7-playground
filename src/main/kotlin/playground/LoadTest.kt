package playground

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration as JavaDuration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * Fixed-rate GET generator. Requests are fired on a wall-clock schedule rather than one after the
 * other, so a server that slows down produces rising latency instead of falling throughput — the
 * open-model measurement, without coordinated omission.
 */
object LoadTest {

    data class Report(
        val requested: Int,
        val completed: Int,
        val errors: Int,
        val achievedTps: Double,
        val latenciesMicros: LongArray,
        val serviceMicros: LongArray,
        val scheduleDelayMicros: LongArray,
        val statusCounts: Map<Int, Int>,
        val partitionScans: Map<String, Long>,
    ) {
        fun percentileMs(p: Double): Double = percentileMs(latenciesMicros, p)

        fun percentileMs(values: LongArray, p: Double): Double {
            if (values.isEmpty()) return 0.0
            val index = Math.ceil(p / 100.0 * values.size).toInt().coerceIn(1, values.size)
            return values[index - 1] / 1000.0
        }
    }

    fun run(
        baseUrl: String,
        ids: List<Uuid>,
        tps: Int,
        durationSeconds: Int,
        ds: DataSource? = null,
        random: Random = Random(7),
    ): Report {
        require(ids.isNotEmpty()) { "no sampled ids — run the loader with a sample path first" }
        val total = tps * durationSeconds
        val intervalNanos = 1_000_000_000L / tps

        val client = HttpClient.newBuilder()
            .connectTimeout(JavaDuration.ofSeconds(5))
            .build()

        val latencies = LongArray(total) { -1 }
        val service = LongArray(total) { -1 }
        val delay = LongArray(total) { -1 }
        val statuses = AtomicIntegerArray(total)
        // Pick the ids up front so the generator thread does no work between ticks.
        val targets = List(total) { ids[random.nextInt(ids.size)] }

        val before = ds?.let { settleAndSnapshot(it) }

        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val start = System.nanoTime()
        executor.use {
            for (i in 0 until total) {
                val dueAt = start + i * intervalNanos
                var wait = dueAt - System.nanoTime()
                while (wait > 0) {
                    LockSupport.parkNanos(wait)
                    wait = dueAt - System.nanoTime()
                }
                executor.submit {
                    // Split the two so a slow scheduler cannot masquerade as a slow server.
                    val sentAt = System.nanoTime()
                    delay[i] = (sentAt - dueAt) / 1_000
                    val request = HttpRequest.newBuilder(URI.create("$baseUrl/payments/${targets[i]}"))
                        .timeout(JavaDuration.ofSeconds(10))
                        .GET()
                        .build()
                    val status = try {
                        client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                    } catch (e: Exception) {
                        -1
                    }
                    statuses.set(i, status)
                    val doneAt = System.nanoTime()
                    service[i] = (doneAt - sentAt) / 1_000
                    // Measured from when the request was due, not when it was sent.
                    latencies[i] = (doneAt - dueAt) / 1_000
                }
            }
        }
        executor.awaitTermination(1, TimeUnit.MINUTES)
        val elapsedNanos = System.nanoTime() - start

        val after = ds?.let { settleAndSnapshot(it) }
        val scans = if (before != null && after != null) {
            after.mapNotNull { (name, value) ->
                val delta = value - (before[name] ?: 0)
                if (delta > 0) name to delta else null
            }.toMap()
        } else {
            emptyMap()
        }

        val completed = latencies.count { it >= 0 }
        val statusCounts = (0 until total).map { statuses.get(it) }.groupingBy { it }.eachCount()
        return Report(
            requested = total,
            completed = completed,
            errors = statusCounts.filterKeys { it !in 200..299 }.values.sum(),
            achievedTps = completed / (elapsedNanos / 1_000_000_000.0),
            latenciesMicros = latencies.filter { it >= 0 }.sorted().toLongArray(),
            serviceMicros = service.filter { it >= 0 }.sorted().toLongArray(),
            scheduleDelayMicros = delay.filter { it >= 0 }.sorted().toLongArray(),
            statusCounts = statusCounts,
            partitionScans = scans,
        )
    }

    fun print(report: Report, tps: Int, seconds: Int) {
        val scanTotal = report.partitionScans.values.sum()
        println()
        println("requested   : $tps req/s for ${seconds}s = ${report.requested} requests")
        println("achieved    : ${"%.1f".format(report.achievedTps)} req/s, ${report.completed} completed, ${report.errors} errors")
        println("status      : ${report.statusCounts.toSortedMap()}")
        fun line(label: String, values: LongArray) = println(
            "$label: p50=${"%.2f".format(report.percentileMs(values, 50.0))} " +
                "p90=${"%.2f".format(report.percentileMs(values, 90.0))} " +
                "p95=${"%.2f".format(report.percentileMs(values, 95.0))} " +
                "p99=${"%.2f".format(report.percentileMs(values, 99.0))} " +
                "max=${"%.2f".format(report.percentileMs(values, 100.0))}"
        )
        line("service ms  ", report.serviceMicros)
        line("sched lag ms", report.scheduleDelayMicros)
        line("total ms    ", report.latenciesMicros)
        if (report.partitionScans.isNotEmpty()) {
            println(
                "partitions  : $scanTotal scans across ${report.partitionScans.size} partitions " +
                    "= ${"%.2f".format(scanTotal.toDouble() / report.completed)} per request"
            )
            println("              (1.00 means every request read exactly one partition)")
        }
    }

    private fun settleAndSnapshot(ds: DataSource): Map<String, Long> = ds.connection.use { conn ->
        conn.createStatement().use { it.execute("SELECT pg_sleep(2)") }   // stats flush at most once a second
        conn.createStatement().use { st ->
            st.executeQuery(
                """SELECT relname, coalesce(idx_scan, 0) + coalesce(seq_scan, 0)
                   FROM pg_stat_user_tables WHERE relname LIKE 'payments\_%'"""
            ).use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) }
            }
        }
    }
}
