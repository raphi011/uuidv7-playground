package playground

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlin.uuid.Uuid

fun Application.paymentsApi(repository: PaymentRepository) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
    }
    routing {
        post("/payments") {
            val payment = repository.create(call.receive<CreatePaymentRequest>())
            call.respond(HttpStatusCode.Created, payment)
        }
        get("/payments/{id}") {
            val id = Uuid.parseOrNull(call.parameters["id"].orEmpty())
                ?: throw IllegalArgumentException("not a UUID")
            when (val payment = repository.findById(id)) {
                null -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such payment"))
                else -> call.respond(payment)
            }
        }
    }
}
