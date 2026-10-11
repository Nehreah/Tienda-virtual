package com.automata.app.routes

import com.automata.adapters.channels.MetaWebhookReceiver
import com.automata.adapters.shopify.ShopifyWebhookHandler
import com.automata.app.AppStoreCatalog
import com.automata.app.pipeline.InboundMessagePipeline
import com.automata.commerce.service.AntiOversellSaga
import com.automata.commerce.service.ConfirmPaymentUseCase
import com.automata.commerce.service.NegotiationEngine
import com.automata.messaging.ports.ChannelRouter
import com.automata.messaging.ports.CustomerIntentType
import com.automata.messaging.ports.InMemoryConversationStore
import com.automata.messaging.ports.InMemoryProcessedEventStore
import com.automata.messaging.ports.LlmPort
import com.automata.messaging.ports.ParsedCustomerIntent
import com.automata.shared.ProductKey
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pagos (guardia y validaciones) y webhooks (firma y tamaño): lo que protege el dinero y la entrada pública. */
class PagosYWebhooksTest {

    private class LlmSinIa : LlmPort {
        override suspend fun parseIntent(userMessage: String, availableProducts: List<ProductKey>) =
            ParsedCustomerIntent(CustomerIntentType.OTHER, rawText = userMessage)
    }

    private fun ApplicationTestBuilder.montar() {
        val guard = guardiaDePrueba()
        val conversaciones = InMemoryConversationStore()
        val pipeline = InboundMessagePipeline(
            llm = LlmSinIa(),
            engine = NegotiationEngine(),
            catalog = AppStoreCatalog,
            conversations = conversaciones,
            router = ChannelRouter(emptyList()),
            paymentInstructions = "Instrucciones de prueba"
        )
        val saga = AntiOversellSaga(emptyList())
        rutas {
            pagosRoutes(guard, ConfirmPaymentUseCase(null), pipeline, saga, conversaciones)
            webhookRoutes(
                ShopifyWebhookHandler(webhookSecret = "secreto-de-prueba"),
                MetaWebhookReceiver(verifyToken = "token-de-prueba"),
                InMemoryProcessedEventStore(),
                null,
                saga,
                pipeline,
                CoroutineScope(Job()),
                null
            )
        }
    }

    // ---------------- pagos ----------------

    @Test
    fun `confirmar un pago sin credenciales es 401`() = testApplication {
        montar()
        val r = client.submitForm("/api/confirmar-pago", parameters { append("dealId", "x") })
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `el usuario de lectura no puede confirmar pagos`() = testApplication {
        montar()
        val r = client.submitForm("/api/confirmar-pago", parameters { append("dealId", "x") }) { comoViewer(); desdeElSitio() }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `confirmar un pago sin dealId es 400`() = testApplication {
        montar()
        val r = client.submitForm("/api/confirmar-pago", parameters { }) { comoAdmin(); desdeElSitio() }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `confirmar el pago de un trato que no existe es 404`() = testApplication {
        montar()
        val r = client.submitForm("/api/confirmar-pago", parameters { append("dealId", "no-existe") }) { comoAdmin(); desdeElSitio() }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    // ---------------- webhooks de Shopify ----------------

    @Test
    fun `un webhook de Shopify con firma invalida es 401`() = testApplication {
        montar()
        val r = client.post("/webhooks/shopify") {
            header("X-Shopify-Topic", "orders/create")
            header("X-Shopify-Hmac-SHA256", "firma-falsa")
            setBody("""{"id": 1, "line_items": []}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `un webhook de Shopify sin firma es 401`() = testApplication {
        montar()
        val r = client.post("/webhooks/shopify") {
            header("X-Shopify-Topic", "orders/create")
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `un webhook de Shopify de mas de 1 MB es 413 antes de verificar nada`() = testApplication {
        montar()
        val r = client.post("/webhooks/shopify") {
            header("X-Shopify-Topic", "orders/create")
            header("X-Shopify-Hmac-SHA256", "da-igual")
            setBody(ByteArray(MAX_WEBHOOK_BYTES + 1))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
    }

    // ---------------- webhooks de Meta ----------------

    @Test
    fun `la verificacion de Meta con token correcto devuelve el challenge`() = testApplication {
        montar()
        val r = client.get("/webhooks/meta?hub.mode=subscribe&hub.verify_token=token-de-prueba&hub.challenge=12345")
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `la verificacion de Meta con token incorrecto es 403`() = testApplication {
        montar()
        val r = client.get("/webhooks/meta?hub.mode=subscribe&hub.verify_token=otro&hub.challenge=12345")
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `un POST de Meta sin firma valida nunca se procesa`() = testApplication {
        montar()
        val r = client.post("/webhooks/meta") {
            header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
            setBody("""{"object":"whatsapp_business_account","entry":[]}""")
        }
        // 503 si no hay META_APP_SECRET en el entorno de pruebas; 401 si lo hay y la firma no coincide.
        assertTrue(r.status == HttpStatusCode.ServiceUnavailable || r.status == HttpStatusCode.Unauthorized, "status=${r.status}")
    }

    @Test
    fun `un POST de Meta de mas de 1 MB es 413`() = testApplication {
        montar()
        val r = client.post("/webhooks/meta") { setBody(ByteArray(MAX_WEBHOOK_BYTES + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
    }
}
