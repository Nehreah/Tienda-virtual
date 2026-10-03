package com.automata.app

import com.automata.adapters.channels.MetaMessagingAdapter
import com.automata.adapters.channels.MetaWebhookReceiver
import com.automata.adapters.channels.WhatsAppMessagingAdapter
import com.automata.adapters.meta.MetaShopAdapter
import com.automata.adapters.shopify.ShopifyCatalogAdapter
import com.automata.adapters.shopify.ShopifyWebhookHandler
import com.automata.app.dashboard.*
import com.automata.app.pipeline.InboundMessagePipeline
import com.automata.app.pipeline.PipelineCatalog
import com.automata.app.pipeline.PipelineOutcome
import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.ports.ChannelListingStatus
import com.automata.commerce.ports.InventarioRepository
import com.automata.commerce.service.AntiOversellSaga
import com.automata.commerce.service.NegotiationEngine
import com.automata.infra.config.AppConfig
import com.automata.infra.db.DatabaseFactory
import com.automata.infra.db.ExposedInventarioRepository
import com.automata.infra.db.ExposedProcessedEventStore
import com.automata.infra.db.Origen
import com.automata.infra.excel.ExcelGenerator
import com.automata.infra.excel.ExcelV2Parser
import com.automata.infra.llm.GeminiLlmAdapter
import com.automata.messaging.model.ConversationState
import com.automata.messaging.ports.ChannelRouter
import com.automata.messaging.ports.InMemoryConversationStore
import com.automata.messaging.ports.CustomerIntentType
import com.automata.messaging.ports.InMemoryProcessedEventStore
import com.automata.messaging.ports.ProcessedEventStore
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object AppStore {
    val products = mutableMapOf<String, CommercialProduct>()
    val inventory = mutableMapOf<String, OperationalInventory>()
    val channelMappings = mutableMapOf<String, MutableMap<String, String>>()
    val apiIntegrations = mutableListOf<ApiIntegrationStatus>()
    val chatDeals = mutableListOf<ChatDealLog>()

    /** true solo si DatabaseFactory.connect() terminó sin error. */
    @Volatile var databaseConnected: Boolean = false

    /**
     * Estado REAL de las integraciones, calculado desde la configuración (nunca inventado).
     * No existe aún un health-check contra cada API, por eso solo la base de datos puede marcarse FUNCIONAL (se verifica al conectar):
     * - DESHABILITADO: faltan credenciales.
     * - DEGRADADO: credenciales presentes pero sin verificación en vivo (o adaptador incompleto).
     */
    fun refreshIntegrationStatuses() {
        fun configured(vararg keys: String) = keys.all { AppConfig.get(it).isNotBlank() }
        fun status(ok: Boolean) = if (ok) ApiStatus.DEGRADADO else ApiStatus.DESHABILITADO
        val noCheck = "Sin verificar"
        val metaSig = if (AppConfig.metaAppSecret.isNotBlank()) "firma de webhook activa"
                      else "SIN META_APP_SECRET: webhook sin verificar firma"

        apiIntegrations.clear()
        apiIntegrations.addAll(
            listOf(
                ApiIntegrationStatus(
                    "Shopify GraphQL API", "E-Commerce",
                    status(configured("SHOPIFY_SHOP_DOMAIN", "SHOPIFY_ACCESS_TOKEN", "SHOPIFY_WEBHOOK_SECRET")),
                    if (configured("SHOPIFY_SHOP_DOMAIN", "SHOPIFY_ACCESS_TOKEN", "SHOPIFY_WEBHOOK_SECRET"))
                        "Credenciales presentes • sin verificación en vivo" else "Faltan credenciales de Shopify",
                    noCheck
                ),
                ApiIntegrationStatus(
                    "Meta Catalog API", "Catálogo",
                    ApiStatus.DEGRADADO.takeIf { configured("META_CATALOG_ID", "META_ACCESS_TOKEN") } ?: ApiStatus.DESHABILITADO,
                    "Adaptador pendiente: aún NO publica ni actualiza productos en Meta",
                    noCheck
                ),
                ApiIntegrationStatus(
                    "WhatsApp Cloud API", "Mensajería",
                    status(configured("WHATSAPP_PHONE_NUMBER_ID", "WHATSAPP_ACCESS_TOKEN")),
                    if (configured("WHATSAPP_PHONE_NUMBER_ID", "WHATSAPP_ACCESS_TOKEN"))
                        "Envío configurado (${AppConfig.metaGraphApiVersion}) • $metaSig • recepción conectada al pipeline de mensajes" else "Faltan credenciales de WhatsApp",
                    noCheck
                ),
                ApiIntegrationStatus(
                    "Instagram DM API", "Mensajería",
                    status(configured("META_PAGE_ID", "META_ACCESS_TOKEN")),
                    if (configured("META_PAGE_ID", "META_ACCESS_TOKEN"))
                        "Envío configurado (${AppConfig.metaGraphApiVersion}) • $metaSig • recepción conectada al pipeline de mensajes" else "Faltan META_PAGE_ID / META_ACCESS_TOKEN",
                    noCheck
                ),
                ApiIntegrationStatus(
                    "Facebook Messenger API", "Mensajería",
                    status(configured("META_PAGE_ID", "META_ACCESS_TOKEN")),
                    if (configured("META_PAGE_ID", "META_ACCESS_TOKEN"))
                        "Envío configurado (${AppConfig.metaGraphApiVersion}) • $metaSig • recepción conectada al pipeline de mensajes" else "Faltan META_PAGE_ID / META_ACCESS_TOKEN",
                    noCheck
                ),
                ApiIntegrationStatus(
                    "Gemini LLM Engine", "IA & NLP",
                    status(configured("GEMINI_API_KEY")),
                    if (configured("GEMINI_API_KEY")) "API key presente • el intérprete actual usa reglas locales (aún no llama a Gemini)" else "Falta GEMINI_API_KEY • el flujo usa el intérprete por reglas locales",
                    noCheck
                ),
                ApiIntegrationStatus(
                    "Base de Datos Operacional", "Almacenamiento",
                    when {
                        databaseConnected -> ApiStatus.FUNCIONAL
                        AppConfig.databaseConfigured -> ApiStatus.DEGRADADO
                        else -> ApiStatus.DESHABILITADO
                    },
                    when {
                        databaseConnected -> "Supabase PostgreSQL conectada al iniciar"
                        AppConfig.databaseConfigured -> "DATABASE_URL presente pero la conexión falló"
                        else -> "Sin DATABASE_URL: modo demo en memoria"
                    },
                    if (databaseConnected) "Al iniciar" else noCheck
                )
            )
        )
    }

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

        chatDeals.clear()
        chatDeals.addAll(
            listOf(
                ChatDealLog("CHAT-501", "+57 300 892 4110", "whatsapp", "WhatsApp", "Audífonos Bluetooth Pro ANC", "$110.000 COP", DealState.TRATO_CERRADO, "Trato Realizado (L3)", "Hace 5 min"),
                ChatDealLog("CHAT-502", "@carlos_tech", "instagram", "Instagram DM", "Reloj Inteligente Sport GPS", "$230.000 COP", DealState.PENDIENTE_ENVIO, "Pendiente de Envío", "Hace 12 min"),
                ChatDealLog("CHAT-503", "Orden Shopify #1084", "shopify_webhooks", "Shopify Order", "Audífonos Bluetooth Pro ANC", "$120.000 COP", DealState.PENDIENTE_ENVIO, "Pendiente de Envío", "Hace 25 min"),
                ChatDealLog("CHAT-504", "Laura Gómez", "messenger", "Messenger", "Audífonos Bluetooth Pro ANC", "$60.000 COP", DealState.CANCELADO, "Cancelado / Rechazado (< PM)", "Hace 40 min"),
                ChatDealLog("CHAT-505", "+57 315 776 2201", "whatsapp", "WhatsApp", "Reloj Inteligente Sport GPS", "$220.000 COP", DealState.EN_NEGOCIACION, "En Negociación (L2)", "Hace 2 min")
            )
        )
    }
}

/** Expone AppStore al pipeline de mensajes con acceso sincronizado (el pipeline corre en otras corrutinas). */
object AppStoreCatalog : PipelineCatalog {
    override fun productKeys(): List<ProductKey> =
        synchronized(AppStore.products) { AppStore.products.keys.map { ProductKey(it) } }

    override fun product(key: ProductKey): CommercialProduct? =
        synchronized(AppStore.products) { AppStore.products[key.value] }

    override fun inventory(key: ProductKey): OperationalInventory? =
        synchronized(AppStore.inventory) { AppStore.inventory[key.value] }

    override fun saveInventory(inventory: OperationalInventory) {
        synchronized(AppStore.inventory) { AppStore.inventory[inventory.productKey.value] = inventory }
    }
}

private fun channelDisplayName(channel: String): String = when (channel.lowercase()) {
    "whatsapp" -> "WhatsApp"
    "instagram" -> "Instagram DM"
    "messenger" -> "Messenger"
    else -> channel
}

/** Refleja la conversación en el tablero de tratos del dashboard (una fila por conversación). */
private fun recordDeal(outcome: PipelineOutcome) {
    val conv = outcome.conversation
    val state = when (conv.state) {
        ConversationState.PAID_CONFIRMED -> DealState.PENDIENTE_ENVIO
        ConversationState.CLOSED -> DealState.CANCELADO
        else -> DealState.EN_NEGOCIACION
    }
    val deal = ChatDealLog(
        id = conv.conversationId,
        customer = conv.customerPhoneOrId,
        channel = conv.channel,
        channelDisplayName = channelDisplayName(conv.channel),
        productName = outcome.productName ?: "—",
        offeredPrice = outcome.amountCop?.let { "${Money.of(it).formatFormattedCop()} COP" } ?: "—",
        dealState = state,
        dealStateLabel = outcome.dealLabel,
        timestamp = java.time.LocalTime.now().withSecond(0).withNano(0).toString()
    )
    synchronized(AppStore.chatDeals) {
        AppStore.chatDeals.removeAll { it.id == deal.id }
        AppStore.chatDeals.add(0, deal)
    }
}

/** Exige HTTP Basic si DASHBOARD_PASSWORD está definido. Devuelve false (ya respondió 401) cuando no hay acceso. */
private suspend fun ApplicationCall.requireDashboardAuth(auth: DashboardAuth): Boolean {
    if (auth.isAuthorized(request.headers[HttpHeaders.Authorization])) return true
    response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"Automata Comercial\", charset=\"UTF-8\"")
    respondText("Autenticación requerida", status = HttpStatusCode.Unauthorized)
    return false
}

fun main() {
    AppStore.seedSampleData()
    if (AppConfig.databaseConfigured) {
        println("Conectando a Supabase PostgreSQL...")
        try {
            DatabaseFactory.connect()
            AppStore.databaseConnected = true
            println("Conexión a Supabase establecida correctamente.")
        } catch (e: Exception) {
            System.err.println("No se pudo conectar a Supabase: ${e.message}")
        }
    }
    AppStore.refreshIntegrationStatuses()
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
    install(CORS) {
        val allowedHost = AppConfig.corsAllowedHost.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (allowedHost.isBlank()) anyHost() else allowHost(allowedHost, schemes = listOf("https", "http"))
    }

    val negotiationEngine = NegotiationEngine()

    // Supabase Repository (si está configurada la BD)
    val dbRepository: InventarioRepository? = if (AppConfig.databaseConfigured) {
        try { ExposedInventarioRepository() } catch (e: Exception) { null }
    } else null

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
        accessToken = AppConfig.whatsappAccessToken,
        apiVersion = AppConfig.metaGraphApiVersion
    )
    val instagramAdapter = MetaMessagingAdapter(
        channelName = "instagram",
        pageOrIgAccountId = AppConfig.metaPageId,
        accessToken = AppConfig.metaAccessToken,
        apiVersion = AppConfig.metaGraphApiVersion
    )
    val messengerAdapter = MetaMessagingAdapter(
        channelName = "messenger",
        pageOrIgAccountId = AppConfig.metaPageId,
        accessToken = AppConfig.metaAccessToken,
        apiVersion = AppConfig.metaGraphApiVersion
    )

    // Enrutador por canal (lo consume el pipeline de mensajes entrantes)
    val channelRouter = ChannelRouter(listOf(whatsAppAdapter, instagramAdapter, messengerAdapter))

    // Handlers de Webhooks
    val shopifyWebhookHandler = ShopifyWebhookHandler(webhookSecret = AppConfig.shopifyWebhookSecret)
    val metaWebhookReceiver = MetaWebhookReceiver(verifyToken = AppConfig.metaWebhookVerifyToken)
    // Idempotencia persistente si la base conectó; si no, memoria del proceso.
    val processedEvents: ProcessedEventStore =
        if (AppStore.databaseConnected) ExposedProcessedEventStore() else InMemoryProcessedEventStore()
    if (AppConfig.metaAppSecret.isBlank()) {
        if (AppConfig.requireMetaSignature) {
            System.err.println("META_APP_SECRET vacío y REQUIRE_META_SIGNATURE=true: /webhooks/meta rechazará TODOS los eventos hasta que lo definas.")
        } else {
            System.err.println("ADVERTENCIA: META_APP_SECRET vacío; /webhooks/meta aceptará eventos SIN verificar firma.")
        }
    }

    // Protección opcional (HTTP Basic) del tablero y de /api/*. Los webhooks y la vitrina pública no pasan por aquí.
    val dashboardAuth = DashboardAuth(AppConfig.dashboardUser, AppConfig.dashboardPassword)
    if (!dashboardAuth.enabled) {
        System.err.println("ADVERTENCIA: DASHBOARD_PASSWORD vacío; el tablero y /api/* están abiertos sin contraseña. Defínelo antes de exponer la app a Internet.")
    }

    val llmAdapter = GeminiLlmAdapter(apiKey = AppConfig.geminiApiKey)

    // Pipeline de mensajes entrantes (cliente -> conversación -> intención -> negociación -> reserva -> respuesta)
    val inboundPipeline = InboundMessagePipeline(
        llm = llmAdapter,
        engine = negotiationEngine,
        catalog = AppStoreCatalog,
        conversations = InMemoryConversationStore(),
        router = channelRouter,
        paymentInstructions = AppConfig.paymentInstructions
    )
    // Meta exige responder 200 rápido: los mensajes se procesan en segundo plano.
    val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    pipelineScope.launch {
        while (isActive) {
            delay(60_000)
            try { inboundPipeline.sweep() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                System.err.println("Error liberando reservas vencidas: ${e.message}")
            }
        }
    }
    environment.monitor.subscribe(ApplicationStopping) { pipelineScope.cancel() }
    val excelParser = ExcelV2Parser()

    routing {
        // 1. Dashboard Web Interactivo
        get("/") {
            if (!call.requireDashboardAuth(dashboardAuth)) return@get
            // Copias tomadas bajo candado: el pipeline modifica productos/inventario desde otras corrutinas.
            val productList = synchronized(AppStore.products) { AppStore.products.values.toList() }
            val inventorySnapshot = synchronized(AppStore.inventory) { AppStore.inventory.toMap() }
            val statuses = mutableListOf<ChannelListingStatus>()

            productList.forEach { p ->
                val shopifyExt = AppStore.channelMappings[p.key.value]?.get("shopify")
                val metaExt = AppStore.channelMappings[p.key.value]?.get("meta_catalogo")
                statuses.add(ChannelListingStatus(p.key, "shopify", shopifyExt, shopifyExt != null, 12, "OK"))
                statuses.add(ChannelListingStatus(p.key, "meta_catalogo", metaExt, metaExt != null, 12, "CATALOG_ONLY"))
            }

            val html = DashboardRenderer.renderDashboardPage(
                products = productList,
                inventoryMap = inventorySnapshot,
                channelStatuses = statuses,
                apiIntegrations = AppStore.apiIntegrations,
                chatDeals = synchronized(AppStore.chatDeals) { AppStore.chatDeals.toList() }
            )
            call.respondText(html, ContentType.Text.Html)
        }

        // 2. Simulador de Negociación L0-L4
        post("/api/sim/chat") {
            if (!call.requireDashboardAuth(dashboardAuth)) return@post
            val parameters = call.receiveParameters()
            val message = parameters["message"] ?: ""

            if (message.isBlank()) {
                call.respondText("<p class='text-rose-600'>Por favor escribe un mensaje.</p>", ContentType.Text.Html)
                return@post
            }

            val parsedIntent = llmAdapter.parseIntent(message, AppStoreCatalog.productKeys())
            val product = synchronized(AppStore.products) { AppStore.products.values.firstOrNull() }
            if (product == null) {
                call.respondText("<p class='text-rose-600'>No hay productos cargados. Sube primero el Excel.</p>", ContentType.Text.Html)
                return@post
            }

            val offeredPrice = parsedIntent.offeredPrice
            var dealState = DealState.EN_NEGOCIACION
            var dealLabel = "En Consulta / Negociación"

            val responseHtml = if (parsedIntent.intentType == CustomerIntentType.MAKE_OFFER && offeredPrice != null) {
                val decision = negotiationEngine.evaluateOffer(product, offeredPrice)
                val badgeColor = if (decision.accepted) "bg-emerald-100 text-emerald-800" else "bg-amber-100 text-amber-800"
                if (decision.accepted) {
                    dealState = DealState.TRATO_CERRADO
                    dealLabel = "Trato Realizado (${decision.level.name})"
                } else {
                    dealState = DealState.CANCELADO
                    dealLabel = "Cancelado / Rechazado (< PM)"
                }

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

            synchronized(AppStore.chatDeals) { AppStore.chatDeals.add(
                0,
                ChatDealLog(
                    id = "SIM-${(System.currentTimeMillis() % 10000)}",
                    customer = "Cliente Simulador",
                    channel = "simulator",
                    channelDisplayName = "Simulador",
                    productName = product.itemName,
                    offeredPrice = offeredPrice?.formatFormattedCop() ?: product.publishedPrice.formatFormattedCop(),
                    dealState = dealState,
                    dealStateLabel = dealLabel,
                    timestamp = "Ahora mismo"
                )
            ) }

            call.respondText(responseHtml, ContentType.Text.Html)
        }

        // 3. Subida / Importación de Excel a Supabase & AppStore
        post("/api/excel/upload") {
            if (!call.requireDashboardAuth(dashboardAuth)) return@post
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
                synchronized(AppStore.products) { AppStore.products[p.key.value] = p }
                synchronized(AppStore.inventory) {
                    val currentInv = AppStore.inventory[p.key.value]
                    AppStore.inventory[p.key.value] = currentInv?.withUpdatedMaster(p.masterQuantity)
                        ?: OperationalInventory(p.key, stockMaestro = p.masterQuantity)
                }

                // Guardar en Supabase si el repositorio está disponible
                if (dbRepository != null) {
                    try {
                        val item = InventarioItem(
                            key = p.key,
                            item = p.itemName,
                            precioPublicado = p.publishedPrice,
                            precioMinimo = p.minimumPrice,
                            precioCompra = p.cost,
                            costoTotal = p.cost,
                            descripcion = p.description,
                            fotos = p.images,
                            estado = EstadoItem.desdeEtiqueta(p.status) ?: EstadoItem.PUBLICADO
                        )
                        dbRepository.upsert(item, usuario = "sistema", origen = Origen.IMPORT)
                    } catch (e: Exception) {
                        System.err.println("Error al guardar ${p.key} en Supabase: ${e.message}")
                    }
                }
            }

            call.respondRedirect("/")
        }

        // 4. Exportación de Datos de Supabase a Excel (.xlsx)
        get("/api/excel/export") {
            if (!call.requireDashboardAuth(dashboardAuth)) return@get
            // Datos en memoria (se usan si no hay BD o si la consulta a la BD falla, en vez de exportar una lista vacía).
            fun memoryItems(): List<InventarioItem> = synchronized(AppStore.products) { AppStore.products.values.toList() }.map { p ->
                InventarioItem(
                    key = p.key,
                    item = p.itemName,
                    precioPublicado = p.publishedPrice,
                    precioMinimo = p.minimumPrice,
                    precioCompra = p.cost,
                    costoTotal = p.cost,
                    descripcion = p.description,
                    fotos = p.images,
                    estado = EstadoItem.desdeEtiqueta(p.status) ?: EstadoItem.PUBLICADO
                )
            }
            val items: List<InventarioItem> = if (dbRepository != null) {
                try {
                    dbRepository.findAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println("Export Excel: falló la consulta a la BD (${e.message}); se exportan los datos en memoria.")
                    memoryItems()
                }
            } else {
                memoryItems()
            }

            val excelBytes = ExcelGenerator.exportToBytes(items)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "inventario.xlsx").toString()
            )
            call.respondBytes(excelBytes, ContentType.Application.OctetStream)
        }

        // 5. Webhook Shopify HMAC
        post("/webhooks/shopify") {
            val topic = call.request.headers["X-Shopify-Topic"]
            val hmac = call.request.headers["X-Shopify-Hmac-SHA256"]
            val bodyBytes = call.receive<ByteArray>()

            val result = shopifyWebhookHandler.handleWebhook(topic, hmac, bodyBytes)

            if (!result.success) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to result.message))
                return@post
            }

            // Idempotencia: Shopify reenvía webhooks; el mismo evento no puede descontar stock dos veces.
            val shopifyEventId = call.request.headers["X-Shopify-Event-Id"]
                ?: call.request.headers["X-Shopify-Webhook-Id"]
            if (shopifyEventId != null && !processedEvents.markIfNew("shopify", shopifyEventId)) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "duplicate_ignored"))
                return@post
            }

            result.emittedEvent?.let { soldEvent ->
                val keyStr = soldEvent.productKey.value
                val updatedInv = synchronized(AppStore.inventory) {
                    AppStore.inventory[keyStr]?.recordSale(soldEvent.quantity)?.also { AppStore.inventory[keyStr] = it }
                }
                if (updatedInv != null) {
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

        // 6. Webhook Meta
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
            val bodyBytes = call.receive<ByteArray>()

            // Firma HMAC de Meta: si hay App Secret configurado, se exige y se rechaza lo que no cuadre.
            val appSecret = AppConfig.metaAppSecret
            if (appSecret.isBlank() && AppConfig.requireMetaSignature) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Webhook deshabilitado: falta META_APP_SECRET"))
                return@post
            }
            if (appSecret.isNotBlank()) {
                val signature = call.request.headers["X-Hub-Signature-256"]
                if (!metaWebhookReceiver.verifySignature(bodyBytes, signature, appSecret)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Firma X-Hub-Signature-256 inválida"))
                    return@post
                }
            }

            val inboundEvents = metaWebhookReceiver.parseInboundPayload(bodyBytes.toString(Charsets.UTF_8))

            for (event in inboundEvents) {
                // Idempotencia: Meta puede reentregar el mismo mensaje.
                if (!processedEvents.markIfNew(event.channel, event.messageId)) continue
                pipelineScope.launch {
                    try {
                        recordDeal(inboundPipeline.handle(event.channel, event.senderId, event.messageId, event.text))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        System.err.println("Error procesando mensaje de [${event.channel}] ${event.senderId}: ${e.message}")
                    }
                }
            }

            call.respond(HttpStatusCode.OK, mapOf("status" to "EVENT_RECEIVED"))
        }

        // 7. Vitrina Pública
        get("/public/p/{key}") {
            val key = call.parameters["key"] ?: ""
            val product = synchronized(AppStore.products) { AppStore.products[key] }
            if (product == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Producto no encontrado"))
                return@get
            }

            val inv = synchronized(AppStore.inventory) { AppStore.inventory[key] }
            val isAvailable = inv?.isAvailable ?: false
            val publicDto = product.toPublicDto(isAvailable = isAvailable)

            call.respond(publicDto)
        }
    }
}
