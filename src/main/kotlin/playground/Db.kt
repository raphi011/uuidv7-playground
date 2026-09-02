package playground

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.time.Instant

object Db {

    fun dataSource(poolSize: Int = 8): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = System.getenv("PGURL") ?: "jdbc:postgresql://localhost:5432/uuidv7_playground"
            username = System.getenv("PGUSER") ?: System.getProperty("user.name")
            System.getenv("PGPASSWORD")?.let { password = it }
            maximumPoolSize = poolSize
        }
        return HikariDataSource(config)
    }

    fun applySchema(ds: DataSource) {
        val sql = checkNotNull(Db::class.java.getResourceAsStream("/schema.sql")) { "schema.sql missing" }
            .bufferedReader().readText()
        ds.connection.use { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    /** Partition holding [instant], e.g. `payments_2026_01`. */
    fun partitionName(instant: Instant): String =
        instant.toOffsetDateTime().let { "payments_%d_%02d".format(it.year, it.monthValue) }
}

/** Postgres `timestamptz` is always read and written in UTC so partition bounds line up. */
fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(
    java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()),
    ZoneOffset.UTC,
)

fun OffsetDateTime.toKotlinInstant(): Instant =
    Instant.fromEpochSeconds(toEpochSecond(), nano.toLong())
