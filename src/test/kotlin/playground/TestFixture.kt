package playground

import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

object TestFixture {

    const val TOTAL_PARTITIONS = 39
    private const val FALLBACK_ROWS = 120_000L
    private const val FALLBACK_MONTHS = 12

    val ds by lazy {
        Db.dataSource(4).also { pool ->
            Db.applySchema(pool)
            ensureSeeded(pool)
        }
    }

    val repository by lazy { PaymentRepository(ds) }

    /** A payment from the oldest populated partition, so pruning has the rest to discard. */
    val oldest: Payment by lazy {
        val partition = queryString(
            """SELECT c.relname FROM pg_class c JOIN pg_inherits i ON c.oid = i.inhrelid
               WHERE i.inhparent = 'payments'::regclass AND c.reltuples > 0
               ORDER BY c.relname LIMIT 1"""
        ) ?: error("no populated partition")
        val id = Uuid.parse(checkNotNull(queryString("SELECT id::text FROM $partition LIMIT 1")))
        checkNotNull(repository.findById(id)) { "sampled id not found through the repository" }
    }

    /** Deliberately looks the row up without the decoded timestamp, so it can be used to check it. */
    fun partitionOf(id: Uuid): String = ds.connection.use { conn ->
        conn.prepareStatement("SELECT tableoid::regclass::text FROM payments WHERE id = ?").use { st ->
            st.setObject(1, id.toJavaUuid())
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "none" }
        }
    }

    fun sampleIds(limit: Int): List<Uuid> = ds.connection.use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id FROM payments TABLESAMPLE SYSTEM (2) LIMIT $limit").use { rs ->
                generateSequence { if (rs.next()) rs.getObject(1, java.util.UUID::class.java) else null }
                    .map { Uuid.parse(it.toString()) }
                    .toList()
            }
        }
    }

    private fun queryString(sql: String): String? = ds.connection.use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    // Takes the pool as a parameter: reading `ds` here would re-enter its own lazy initializer.
    private fun ensureSeeded(pool: DataSource) {
        if (PaymentRepository(pool).count() > 0) return
        Seed.load(pool, totalRows = FALLBACK_ROWS, months = FALLBACK_MONTHS, parallelism = 4)
    }
}
