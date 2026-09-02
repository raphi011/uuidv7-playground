package playground

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * RFC 9562 UUID version 7 layout (128 bits, big endian):
 *
 *  |             unix_ts_ms (48 bit)               | ver |  rand_a  |
 *  |var|                     rand_b (62 bit)                        |
 *
 * Neither kotlin.uuid.Uuid nor java.util.UUID exposes the timestamp field, so it has to be
 * unpacked by hand. Postgres grew uuid_extract_timestamp() in 17, which is too new for us.
 *
 * Internal on purpose: [PaymentRepository] is the only production caller, so nothing above it
 * has to know that a payment's partition is derivable from its key.
 */
internal fun Uuid.v7Timestamp(): Instant = toLongs { msb, _ ->
    val version = (msb ushr 12) and 0xF
    require(version == 7L) { "not a v7 UUID: version $version" }
    Instant.fromEpochMilliseconds(msb ushr 16)
}
