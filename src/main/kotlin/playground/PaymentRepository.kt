package playground

import java.sql.ResultSet
import java.time.OffsetDateTime
import javax.sql.DataSource
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * Owns the one trick this schema depends on: `payments` is partitioned by month on `created_at`,
 * and `created_at` is the timestamp carried inside the UUIDv7 key. Callers pass an id and get a
 * single-partition lookup; the decoding stays in here.
 */
class PaymentRepository(private val ds: DataSource) {

    fun create(request: CreatePaymentRequest): Payment {
        val id = Uuid.generateV7()
        // created_at is the UUID's own timestamp, so the partition is a pure function of the key.
        val payment = Payment(
            id = id,
            createdAt = id.v7Timestamp(),
            amountCents = request.amountCents,
            currency = request.currency,
            reference = request.reference,
            status = "PENDING",
        )
        ds.connection.use { conn ->
            conn.prepareStatement(INSERT).use { st ->
                st.setObject(1, payment.id.toJavaUuid())
                st.setObject(2, payment.createdAt.toOffsetDateTime())
                st.setLong(3, payment.amountCents)
                st.setString(4, payment.currency)
                st.setString(5, payment.reference)
                st.setString(6, payment.status)
                st.executeUpdate()
            }
        }
        return payment
    }

    /**
     * Decodes created_at from the key and passes it to Postgres, which prunes to the single
     * matching partition. Without it the planner has to touch every partition.
     */
    fun findById(id: Uuid): Payment? = ds.connection.use { conn ->
        conn.prepareStatement(FIND_BY_ID).use { st ->
            st.setObject(1, id.toJavaUuid())
            st.setObject(2, id.v7Timestamp().toOffsetDateTime())
            st.executeQuery().use { rs -> if (rs.next()) rs.toPayment() else null }
        }
    }

    fun findByIdScanningAllPartitions(id: Uuid): Payment? = ds.connection.use { conn ->
        conn.prepareStatement(FIND_BY_ID_NAIVE).use { st ->
            st.setObject(1, id.toJavaUuid())
            st.executeQuery().use { rs -> if (rs.next()) rs.toPayment() else null }
        }
    }

    fun count(): Long = ds.connection.use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM payments").use { it.next(); it.getLong(1) }
        }
    }

    private fun ResultSet.toPayment() = Payment(
        id = getObject("id", java.util.UUID::class.java).let { Uuid.parse(it.toString()) },
        createdAt = getObject("created_at", OffsetDateTime::class.java).toKotlinInstant(),
        amountCents = getLong("amount_cents"),
        currency = getString("currency"),
        reference = getString("reference"),
        status = getString("status"),
    )

    companion object {
        const val INSERT =
            "INSERT INTO payments (id, created_at, amount_cents, currency, reference, status) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        private const val COLUMNS = "id, created_at, amount_cents, currency, reference, status"
        const val FIND_BY_ID = "SELECT $COLUMNS FROM payments WHERE id = ? AND created_at = ?"
        const val FIND_BY_ID_NAIVE = "SELECT $COLUMNS FROM payments WHERE id = ?"
    }
}

/** Literal-bound variants of the two queries, for EXPLAIN output that shows plan-time pruning. */
fun findByIdSql(id: Uuid, createdAt: Instant?): String {
    val uuid = "'${id}'::uuid"
    return if (createdAt == null) {
        "SELECT * FROM payments WHERE id = $uuid"
    } else {
        "SELECT * FROM payments WHERE id = $uuid AND created_at = '${createdAt.toOffsetDateTime()}'::timestamptz"
    }
}
