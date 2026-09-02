package playground

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.toJavaUuid

/**
 * Proves that a lookup by UUIDv7 primary key reads exactly one monthly partition, because
 * created_at is decoded from the key itself and handed to the planner.
 */
class PartitionPruningTest {

    private val repository = TestFixture.repository
    private val payment = TestFixture.oldest
    private val expectedPartition = Db.partitionName(payment.createdAt)

    @Test
    fun `created_at matches the timestamp encoded in the key`() {
        assertEquals(payment.id.v7Timestamp(), payment.createdAt)
    }

    @Test
    fun `every payment lives in the partition its key encodes`() {
        val ids = TestFixture.sampleIds(500)
        assertTrue(ids.size > 100, "sample was too small: ${ids.size}")
        for (id in ids) {
            assertEquals(Db.partitionName(id.v7Timestamp()), TestFixture.partitionOf(id))
        }
    }

    @Test
    fun `lookup with the decoded timestamp touches exactly one partition`() {
        val plan = Explain.analyze(TestFixture.ds, findByIdSql(payment.id, payment.createdAt))
        println("--- pruned ---\n${plan.text}\n")
        assertEquals(listOf(expectedPartition), plan.partitions)
    }

    @Test
    fun `lookup by id alone touches every partition`() {
        val plan = Explain.analyze(TestFixture.ds, findByIdSql(payment.id, createdAt = null))
        println("--- naive ---\n${plan.text}\n")
        assertEquals(TestFixture.TOTAL_PARTITIONS, plan.partitions.size)
    }

    @Test
    fun `the query the API actually runs prunes with bind parameters`() {
        val plan = Explain.analyzeAsPreparedStatement(TestFixture.ds, PaymentRepository.FIND_BY_ID) { st ->
            st.setObject(1, payment.id.toJavaUuid())
            st.setObject(2, payment.createdAt.toOffsetDateTime())
        }
        println("--- pruned, bind parameters, generic plan candidate ---\n${plan.text}\n")
        assertEquals(listOf(expectedPartition), plan.partitions)
    }

    @Test
    fun `pruning reads far fewer blocks than scanning every partition`() {
        val pruned = Explain.analyze(TestFixture.ds, findByIdSql(payment.id, payment.createdAt))
        val naive = Explain.analyze(TestFixture.ds, findByIdSql(payment.id, createdAt = null))
        println(
            "blocks: pruned=${pruned.blocksTouched} naive=${naive.blocksTouched} " +
                "| partitions: pruned=${pruned.partitions.size} naive=${naive.partitions.size} " +
                "| ms: pruned=${pruned.executionTimeMs} naive=${naive.executionTimeMs}"
        )
        assertTrue(
            pruned.blocksTouched * 5 < naive.blocksTouched,
            "pruned=${pruned.blocksTouched} naive=${naive.blocksTouched}",
        )
    }

    @Test
    fun `pruned and unpruned lookups return the same payment`() {
        assertEquals(repository.findByIdScanningAllPartitions(payment.id), repository.findById(payment.id))
    }
}
