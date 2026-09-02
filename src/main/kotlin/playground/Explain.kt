package playground

import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What EXPLAIN (ANALYZE, BUFFERS) reveals about how much of the table a query touched. */
data class Plan(
    /** Partitions the executor actually opened. */
    val relations: List<String>,
    /** Partitions eliminated at execution time, when the plan was cached and generic. */
    val subplansRemoved: Int,
    val blocksTouched: Long,
    val executionTimeMs: Double,
    val text: String,
) {
    val partitions: List<String> get() = relations.filter { it.startsWith("payments_") }
}

object Explain {

    private val json = Json { ignoreUnknownKeys = true }

    fun analyze(ds: DataSource, sql: String, bind: (PreparedStatement) -> Unit = {}): Plan =
        ds.connection.use { analyze(it, sql, bind) }

    /**
     * Runs the statement [times] times on one connection before explaining it. The pgjdbc driver
     * switches to a server-side prepared statement after five executions, which is when Postgres
     * may fall back to a generic plan — the plan a long-lived API connection really uses.
     */
    fun analyzeAsPreparedStatement(
        ds: DataSource,
        sql: String,
        times: Int = 10,
        bind: (PreparedStatement) -> Unit,
    ): Plan = ds.connection.use { conn ->
        var plan: Plan? = null
        repeat(times) { plan = analyze(conn, sql, bind) }
        plan!!
    }

    private fun analyze(conn: Connection, sql: String, bind: (PreparedStatement) -> Unit): Plan {
        val jsonPlan = conn.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql").use { st ->
            bind(st)
            st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
        val text = conn.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) $sql").use { st ->
            bind(st)
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.joinToString("\n") }
        }

        val root = (json.parseToJsonElement(jsonPlan) as JsonArray).first() as JsonObject
        val nodes = mutableListOf<JsonObject>()
        fun walk(node: JsonObject) {
            nodes += node
            (node["Plans"] as? JsonArray)?.forEach { walk(it as JsonObject) }
        }
        walk(root["Plan"] as JsonObject)

        return Plan(
            relations = nodes.mapNotNull { it["Relation Name"]?.jsonPrimitive?.content },
            subplansRemoved = nodes.sumOf { it["Subplans Removed"]?.jsonPrimitive?.longOrNull ?: 0L }.toInt(),
            blocksTouched = nodes.sumOf {
                (it["Shared Hit Blocks"]?.jsonPrimitive?.longOrNull ?: 0L) +
                    (it["Shared Read Blocks"]?.jsonPrimitive?.longOrNull ?: 0L)
            },
            executionTimeMs = root["Execution Time"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            text = text,
        )
    }
}
