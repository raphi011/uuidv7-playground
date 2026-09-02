package playground

import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Payment(
    val id: Uuid,
    val createdAt: Instant,
    val amountCents: Long,
    val currency: String,
    val reference: String,
    val status: String,
)

@Serializable
data class CreatePaymentRequest(
    val amountCents: Long,
    val currency: String,
    val reference: String,
)
