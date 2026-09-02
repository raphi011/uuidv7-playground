package playground

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

@OptIn(ExperimentalUuidApi::class)
class UuidV7Test {

    @Test
    fun `generateV7 has version 7 and the RFC 4122 variant`() {
        val java = Uuid.generateV7().toJavaUuid()
        assertEquals(7, java.version())
        assertEquals(2, java.variant())
    }

    @Test
    fun `timestamp is recoverable`() {
        val now = Instant.parse("2026-09-02T10:15:30.123Z")
        assertEquals(now, Uuid.generateV7NonMonotonicAt(now).v7Timestamp())
    }

    @Test
    fun `v7Timestamp rejects other versions`() {
        assertFailsWith<IllegalArgumentException> { Uuid.generateV4().v7Timestamp() }
    }

    @Test
    fun `generateV7 is strictly monotonic within the process`() {
        val ids = List(10_000) { Uuid.generateV7() }
        assertEquals(ids, ids.sorted())
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `round-trips through java util UUID and its string form`() {
        val id = Uuid.generateV7()
        assertEquals(id, UUID.fromString(id.toString()).toKotlinUuid())
        assertEquals(id, id.toJavaUuid().toKotlinUuid())
        assertEquals(36, id.toString().length)
    }

    @Test
    fun `java util UUID time accessors reject v7`() {
        val java = Uuid.generateV7().toJavaUuid()
        assertFailsWith<UnsupportedOperationException> { java.timestamp() }
        assertFailsWith<UnsupportedOperationException> { java.node() }
        assertFailsWith<UnsupportedOperationException> { java.clockSequence() }
    }

    @Test
    fun `java util UUID compareTo agrees with Uuid ordering`() {
        // java.util.UUID compares both halves as *signed* longs. That still matches the
        // unsigned order here: every v7 UUID has the variant bit set, so the sign bit of
        // leastSignificantBits is always 1, and unix_ts_ms keeps the sign bit of
        // mostSignificantBits at 0 until the year 10889.
        val ids = List(1_000) { Uuid.generateV7() }
        assertEquals(ids.sorted().map { it.toJavaUuid() }, ids.map { it.toJavaUuid() }.sorted())
    }
}
