package playground

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path

private val SAMPLE_PATH: Path = Path.of("build", "sample-ids.txt")

fun main(args: Array<String>) {
    val ds = Db.dataSource()
    Db.applySchema(ds)
    val repository = PaymentRepository(ds)

    when (val command = args.firstOrNull() ?: "serve") {
        "load" -> {
            val rows = args.getOrNull(1)?.toLong() ?: 1_000_000
            val months = args.getOrNull(2)?.toInt() ?: 36
            println("loading $rows rows over $months months (existing rows are dropped)")
            val result = Seed.load(ds, totalRows = rows, months = months, samplePath = SAMPLE_PATH)
            println()
            println("rows        : ${repository.count()}")
            println("copy        : ${result.loadDuration}")
            println("index build : ${result.indexDuration}")
            println("sample      : ${result.sampled} ids -> ${result.samplePath}")
            ds.close()
        }

        "loadtest" -> {
            val tps = args.getOrNull(1)?.toInt() ?: 40
            val seconds = args.getOrNull(2)?.toInt() ?: 30
            val baseUrl = args.getOrNull(3) ?: "http://localhost:8080"
            val ids = Seed.readSample(SAMPLE_PATH)
            println("$tps req/s for ${seconds}s against $baseUrl, drawing from ${ids.size} known ids")
            LoadTest.print(LoadTest.run(baseUrl, ids, tps, seconds, ds), tps, seconds)
            ds.close()
        }

        "compare" -> {
            val rows = args.getOrNull(1)?.toLong() ?: 10_000_000
            val months = args.getOrNull(2)?.toInt() ?: 3
            println("v7 vs v4: $rows rows per arm over $months months, primary key in place")
            val arms = KeyComparison.KeyKind.entries.map { kind ->
                println("  loading ${kind.table} ...")
                KeyComparison.load(ds, kind, rows = rows, months = months)
            }
            KeyComparisonRun.printWrite(arms)

            val v7 = arms.first { it.kind == KeyComparison.KeyKind.V7 }
            val v4 = arms.first { it.kind == KeyComparison.KeyKind.V4 }
            // Measured twice; the first pass is discarded because the JVM is still warming up
            // across the four arms and would make whichever ran first look slowest.
            fun readPath() = listOf(
                KeyComparisonRun.readPath(ds, "A  v7, created_at decoded from the key", v7.kind.table, v7.sample, withCreatedAt = true),
                KeyComparisonRun.readPath(ds, "B  v4, id only (nothing to decode)", v4.kind.table, v4.sample, withCreatedAt = false),
                KeyComparisonRun.readPath(ds, "C  v4, created_at supplied by the caller", v4.kind.table, v4.sample, withCreatedAt = true),
                KeyComparisonRun.readPath(ds, "D  v7, id only (pruning thrown away)", v7.kind.table, v7.sample, withCreatedAt = false),
            )
            readPath()
            KeyComparisonRun.printRead(readPath())
            ds.close()
        }

        "serve" -> {
            println("payments in table: ${repository.count()}")
            embeddedServer(Netty, port = 8080) { paymentsApi(repository) }.start(wait = true)
        }

        else -> error("unknown command '$command'; expected load | loadtest | compare | serve")
    }
}
