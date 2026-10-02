package com.automata.app

import com.automata.adapters.channels.MetaMessagingAdapter
import com.automata.adapters.channels.MetaWebhookReceiver
import com.automata.adapters.channels.WhatsAppMessagingAdapter
import com.automata.adapters.meta.MetaShopAdapter
import com.automata.adapters.shopify.ShopifyCatalogAdapter
import com.automata.adapters.shopify.ShopifyWebhookHandler
import com.automata.app.dashboard.DashboardRenderer
import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.ports.ChannelListingStatus
import com.automata.commerce.service.AntiOversellSaga
import com.automata.commerce.service.NegotiationEngine
import com.automata.infra.config.AppConfig
import com.automata.infra.excel.ExcelV2Parser
import com.automata.infra.llm.GeminiLlmAdapter
import com.automata.messaging.ports.CustomerIntentType
import com.automata.shared.Money
import com.automata.shared.ProductKey
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking

object AppStore {
    val products = mutableMapOf<String, CommercialProduct>()
    val inventory = mutableMapOf<String, OperationalInventory>()
    val channelMappings = mutableMapOf<String, MutableMap<String, String>>()

    fun seedSampleData() {
        val p1 = CommercialProduct(
            key = ProductKey("PROD-001"),
            itemName = "Audífonos Bluetooth Pro ANC",
            publishedPrice = Money.of(120000),
            minimumPrice = Money.of(95000),
            cost = Money.of(70000),
            status = "Activo",
            description = "Audífonos inalámbricos con cancelación activa de ruido y 30 horas de batería.",
            brand = "AudioTech",
            condition = "Nuevo",
            gtinEan = "7701234567890",
            mpn = "AT-BTPRO",
            masterQuantity = 20
        )
        val p2 = CommercialProduct(
            key = ProductKey("PROD-002"),
            itemName = "Reloj Inteligente Sport GPS",
            publishedPrice = Money.of(250000),
            minimumPrice = Money.of(210000),
            cost = Money.of(150000),
            status = "Activo",
            description = "Smartwatch con monitor cardíaco, GPS integrado y resistencia al agua 5 ATM.",
            brand = "TechWear",
            condition = "Nuevo",
            gtinEan = "7709876543210",
            mpn = "TW-WATCH",
            masterQuantity = 15
        )

        products[p1.key.value] = p1
        products[p2.key.value] = p2

        inventory[p1.key.value] = OperationalInventory(p1.key, stockMaestro = 20, reservasActivas = 2, unidadesVendidas = 3)
        inventory[p2.key.value] = OperationalInventory(p2.key, stockMaestro = 15, reservasActivas = 1, unidadesVendidas = 2)

        channelMappings[p1.key.value] = mutableMapOf("shopify" to "gid://shopify/Product/987654321", "meta_catalogo" to "meta_PROD-001")
        channelMappings[p2.key.value] = mutableMapOf("shopify" to "gid://shopify/Product/987654322", "meta_catalogo" to "meta_PROD-002")
    }
}

fun main() {
    AppStore.seedSampleData()
    val port = AppConfig.port
    println("===============================================================")
    println("  Autómata Comercial Multicanal v5.3 (Código Privado)")
    println("  Servidor iniciado en http://localhost:$port")
    println("  Canales Activos: Shopify GraphQL, Meta Catalog, WhatsApp, IG, Messenger")
    println("===============================================================")
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }
    install(CORS) { anyHost() }

    val negotiationEngine = NegotiationEngine()

    // Adaptadores E-commerce
    val shopifyAdapter = ShopifyCatalogAdapter(
        shopDomain = AppConfig.shopifyShopDomain,
        accessToken = AppConfig.shopifyAccessToken
    )
    val metaCatalogAdapter = MetaShopAdapter(
        catalogId = AppConfig.metaCatalogId,
        accessToken = AppConfig.metaAccessToken,
        countryCode = AppConfig.metaCountryCode
    )
    val antiOversellSaga = AntiOversellSaga(listOf(shopifyAdapter, metaCatalogAdapter))

    // Adaptadores Canales Mensajería (WhatsApp, Instagram DM, Facebook Messenger)
    val whatsAppAdapter = WhatsAppMessagingAdapter(
        phoneNumberId = AppConfig.whatsappPhoneNumberId,
        accessToken = AppConfig.whatsappAccessToken
    )
    val instagramAdapter = MetaMessagingAdapter(
        channelName = "instagram",
        pageOrIgAccountId = AppConfig.metaPageId,
        accessToken = AppConfig.metaAccessToken
    )
    val messengerAdapter = MetaMessagingAdapter(
        channelName = "messenger",
        pageOrIgAccountId = AppConfig.metaPageId,
        accessToken = AppConfig.metaAccessToken
    )

    // Handlers de Webhooks
    val shopifyWebhookHandler = ShopifyWebhookHandler(webhookSecret = AppConfig.shopifyWebhookSecret)
    val metaWebhookReceiver = MetaWebhookReceiver(verifyToken = AppConfig.metaWebhookVerifyToken)

    val llmAdapter = GeminiLlmAdapter(apiKey = AppConfig.geminiApiKey)
    val excelParser = ExcelV2Parser()

    routing {
        // 1. Dashboard Web Interactivo
        get("/") {
            val productList = AppStore.products.values.toList()
            val statuses = mutableListOf<ChannelListingStatus>()

            productList.forEach { p ->
                val shopifyExt = AppStore.channelMappings[p.key.value]?.get("shopify")
                val metaExt = AppStore.channelMappings[p.key.value]?.get("meta_catalogo")
                statuses.add(ChannelListingStatus(p.key, "shopify", shopifyExt, shopifyExt != null, 12, "OK"))
                statuses.add(ChannelListingStatus(p.key, "meta_catalogo", metaExt, metaExt != null, 12, "CATALOG_ONLY"))
            }

            val html = DashboardRenderer.renderDashboardPage(productList, AppStore.inventory, statuses)
            call.respondText(html, ContentType.Text.Html)
        }

        // 2. Simulador de Negociación L0-L4
        post("/api/sim/chat") {
            val parameters = call.receiveParameters()
            val message = parameters["message"] ?: ""

            if (message.isBlank()) {
                call.respondText("<p class='text-rose-600'>Por favor escribe un mensaje.</p>", ContentType.Text.Html)
                return@post
            }

            val parsedIntent = llmAdapter.parseIntent(message, AppStore.products.keys.map { ProductKey(it) })
            val product = AppStore.products.values.firstOrNull() ?: AppStore.products.values.first()

            val responseHtml = if (parsedIntent.intentType == CustomerIntentType.MAKE_OFFER && parsedIntent.offeredPrice != null) {
                val decision = negotiationEngine.evaluateOffer(product, parsedIntent.offeredPrice)
                val badgeColor = if (decision.accepted) "bg-emerald-100 text-emerald-800" else "bg-amber-100 text-amber-800"

                """
                <div class="space-y-2">
                    <div class="flex items-center space-x-2">
                        <span class="px-2 py-0.5 rounded text-xs font-bold $badgeColor">Nivel ${decision.level.name}</span>
                        <span class="text-xs text-gray-500">Producto: ${product.itemName} (PP: ${product.publishedPrice.formatFormattedCop()})</span>
                    </div>
                    <p class="font-semibold text-gray-800">&ldquo;${decision.message}&rdquo;</p>
                    ${decision.counterOffer?.let { "<p class='text-xs text-indigo-600'>Contraoferta generada: ${it.formatFormattedCop()}</p>" } ?: ""}
                </div>
                """.trimIndent()
            } else {
                """
                <div class="space-y-1">
                    <p class="text-xs font-bold text-indigo-600">Intención Detectada: ${parsedIntent.intentType.name}</p>
                    <p class="text-gray-700">Consulta recibida sobre ${product.itemName}. El precio publicado es ${product.publishedPrice.formatFormattedCop()}.</p>
                </div>
                """.trimIndent()
            }

            call.respondText(responseHtml, ContentType.Text.Html)
        }

        // 3. Subida de Excel v2 Matriz Comercial
        post("/api/excel/upload") {
            val multipart = call.receiveMultipart()
            var fileBytes: ByteArray? = null

            multipart.forEachPart { part ->
                if (part is PartData.FileItem) {
                    fileBytes = part.streamProvider().readBytes()
                }
                part.dispose()
            }

            if (fileBytes == null) {
                call.respond(HttpStatusCode.BadRequest, "No se recibió archivo .xlsx")
                return@post
            }

            val parseResult = excelParser.parseExcel(fileBytes!!.inputStream())

            if (parseResult.errors.isNotEmpty()) {
                call.respond(HttpStatusCode.UnprocessableEntity, mapOf("errors" to parseResult.errors))
                return@post
            }

            parseResult.products.forEach { p ->
                AppStore.products[p.key.value] = p
                val currentInv = AppStore.inventory[p.key.value]
                if (currentInv != null) {
                    AppStore.inventory[p.key.value] = currentInv.withUpdatedMaster(p.masterQuantity)
                } else {
                    AppStore.inventory[p.key.value] = OperationalInventory(p.key, stockMaestro = p.masterQuantity)
                }
            }

            call.respondRedirect("/")
        }

        // 4. Webhook Shopify HMAC (Order Create / Inventory Update)
        post("/webhooks/shopify") {
            val topic = call.request.headers["X-Shopify-Topic"]
            val hmac = call.request.headers["X-Shopify-Hmac-SHA256"]
            val bodyBytes = call.receiveChannel().toByteArray()

            val result = shopifyWebhookHandler.handleWebhook(topic, hmac, bodyBytes)

            if (!result.success) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to result.message))
                return@post
            }

            result.emittedEvent?.let { soldEvent ->
                val keyStr = soldEvent.productKey.value
                val inv = AppStore.inventory[keyStr]
                if (inv != null) {
                    val updatedInv = inv.recordSale(soldEvent.quantity)
                    AppStore.inventory[keyStr] = updatedInv

                    runBlocking {
                        antiOversellSaga.executeRetractionSaga(
                            productKey = soldEvent.productKey,
                            originChannel = "shopify",
                            externalIdMap = AppStore.channelMappings[keyStr] ?: emptyMap(),
                            remainingStock = updatedInv.stockVivo
                        )
                    }
                }
            }

            call.respond(HttpStatusCode.OK, mapOf("status" to "processed", "message" to result.message))
        }

        // 5. Webhook Meta (WhatsApp, Instagram DM, Facebook Messenger)
        get("/webhooks/meta") {
            val mode = call.request.queryParameters["hub.mode"]
            val token = call.request.queryParameters["hub.verify_token"]
            val challenge = call.request.queryParameters["hub.challenge"]

            val challengeResult = metaWebhookReceiver.verifyWebhookChallenge(mode, token, challenge)
            if (challengeResult != null) {
                call.respondText(challengeResult, ContentType.Text.Plain)
            } else {
                call.respond(HttpStatusCode.Forbidden, "Token de verificación de Meta inválido")
            }
        }

        post("/webhooks/meta") {
            val bodyText = call.receiveText()
            val inboundEvents = metaWebhookReceiver.parseInboundPayload(bodyText)

            for (event in inboundEvents) {
                println("Mensaje entrante de [${event.channel}] de ${event.senderId}: ${event.text}")
                // Procesar la máquina de estados e intentar evaluar ofertas o responder
            }

            call.respond(HttpStatusCode.OK, mapOf("status" to "EVENT_RECEIVED"))
        }

        // 6. Vitrina Pública (SIN FUGAS FINANCIERAS)
        get("/public/p/{key}") {
            val key = call.parameters["key"] ?: ""
            val product = AppStore.products[key]
            if (product == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Producto no encontrado"))
                return@get
            }

            val inv = AppStore.inventory[key]
            val isAvailable = inv?.isAvailable ?: false
            val publicDto = product.toPublicDto(isAvailable = isAvailable)

            call.respond(publicDto)
        }
    }
}

private suspend fun io.ktor.utils.io.ByteReadChannel.toByteArray(): ByteArray {
    val list = mutableListOf<Byte>()
    while (!isClosedForRead) {
        list.add(readByte())
    }
    return list.toByteArray()
}
