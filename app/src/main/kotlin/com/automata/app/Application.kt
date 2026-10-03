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
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.EstadoItem
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
import kotlin.system.exitProcess

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
                      else "SIN META_APP_SECRET: el webhook responde 503 (firma obligatoria)"
        // Si la variable existe pero trae un valor de ejemplo (placeholder), el tablero lo dice explícitamente.
        fun missing(base: String, vararg keys: String) =
            if (keys.any { AppConfig.hasPlaceholder(it) }) "$base — hay valores de ejemplo (placeholders) sin reemplazar" else base

        apiIntegrations.clear()
        apiIntegrations.addAll(
            listOf(
                ApiIntegrationStatus(
                    "Shopify GraphQL API", "E-Commerce",
                    status(configured("SHOPIFY_SHOP_DOMAIN", "SHOPIFY_ACCESS_TOKEN", "SHOPIFY_WEBHOOK_SECRET")),
                    if (configured("SHOPIFY_SHOP_DOMAIN", "SHOPIFY_ACCESS_TOKEN", "SHOPIFY_WEBHOOK_SECRET"))
                        "Credenciales presentes • sin verificación en vivo"
                    else missing("Faltan credenciales de Shopify", "SHOPIFY_SHOP_DOMAIN", "SHOPIFY_ACCESS_TOKEN", "SHOPIFY_WEBHOOK_SECRET"),
                    noCheck
                ),
                ApiIntegrationStatus(
                    "Meta Catalog API", "Catálogo",
                    ApiStatus.DEGRADADO.takeIf { configured("META_CATALOG_ID", "META_ACCESS_TOKEN") } ?: ApiStatus.DESHABILITADO,
                    missing("Adaptador pendiente: aún NO publica ni actualiza productos en Meta", "META_CATALOG_ID", "META_ACCESS_TOKEN"),
                    noCheck
                ),
                ApiIntegrationStatus(
                    "WhatsApp Cloud API", "Mensajería",
                    status(configured("WHATSAPP_PHONE_NUMBER_ID", "WHATSAPP_ACCESS_TOKEN")),
                    if (configured("WHATSAPP_PHONE_NUMBER_ID", "WHATSAPP_ACCESS_TOKEN"))
                        "Envío configurado (${AppConfig.metaGraphApiVersion}) • $metaSig • recepción conectada al pipeline de mensajes"
                    else missing("Faltan credenciales de WhatsApp", "WHATSAPP_PHONE_NUMBER_ID", "WHATSAPP_ACCESS_TOKEN"),
                    noCheck
                ),
                ApiIntegrationStatus(
                    "Instagram DM API", "Mensajería",
                    status(configured("META_PAGE_ID", "META_ACCESS_TOKEN")),
                    if (configured("META_PAGE_ID", "META_ACCESS_TOKEN"))
                        "Envío configurado (${AppConfig.metaGraphApiVersion}) • $metaSig • recepción conectada al pipeline de mensajes"
                    else missing("Faltan META_PAGE_ID / META_ACCESS_TOKEN", "META_PAGE_ID", "META_ACCESS_TOKEN"),
                    noCheck
                ),
                ApiIntegrationStatus(
                    "Facebook Messenger API", "Mensajería",
                    status(configured("META_PAGE_ID", "META_ACCESS_TOKEN")),
                    if (configured("META_PAGE_ID", "META_ACCESS_TOKEN"))
                        "Envío configurado (${AppConfig.metaGraphApiVersion}) • $metaSig • recepción conectada al pipeline de mensajes"
                    else missing("Faltan META_PAGE_ID / META_ACCESS_TOKEN", "META_PAGE_ID", "META_ACCESS_TOKEN"),
                    noCheck
                ),
                ApiIntegrationStatus(
                    "Gemini LLM Engine", "IA & NLP",
                    status(configured("GEMINI_API_KEY")),
                    if (configured("GEMINI_API_KEY")) "API key presente • el intérprete actual usa reglas locales (aún no llama a Gemini)"
                    else missing("Falta GEMINI_API_KEY • el flujo usa el intérprete por reglas locales", "GEMINI_API_KEY"),
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
                        else -> missing("Sin DATABASE_URL: almacenamiento solo en memoria (datos no persisten)", "DATABASE_URL")
                    },
                    if (databaseConnected) "Al iniciar" else noCheck
                )
            )
        )
    }

}


/** Expone AppStore al pipeline de mensajes con acceso sincronizado (el pipeline corre en otras corrutinas). */
object AppStoreCatalog : PipelineCatalog {
    /** Solo productos vendibles: los Inactivos no entran al catálogo del bot (Fase 2.2). */
    override fun productKeys(): List<ProductKey> =
        synchronized(AppStore.products) { AppStore.products.values.filter { it.isActive }.map { it.key } }

    /** Todas las claves, activas o no (el intérprete debe reconocer el código de un producto retirado). */
    override fun knownKeys(): List<ProductKey> =
        synchronized(AppStore.products) { AppStore.products.keys.map { ProductKey(it) } }

    /** Devuelve el producto aunque esté Inactivo: el pipeline decide qué responder. */
    override fun product(key: ProductKey): CommercialProduct? =
        synchronized(AppStore.products) { AppStore.products[key.value] }

    /** Producto visible en la vitrina pública: null si no existe o está Inactivo (se responde 404 igual que si no existiera). */
    fun publicProduct(key: String): CommercialProduct? =
        synchronized(AppStore.products) { AppStore.products[key] }?.takeIf { it.isActive }

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
        // Fase 4.3: tope de 100 entradas para evitar crecimiento sin límite en memoria.
        if (AppStore.chatDeals.size > 100) {
            AppStore.chatDeals.subList(100, AppStore.chatDeals.size).clear()
        }
    }
}

/** Lo que necesitan las rutas protegidas del tablero: credenciales, límite de intentos y cómo leer la IP del cliente. */
private class DashboardGuard(
    val auth: DashboardAuth,
    val throttle: AuthThrottle,
    val trustProxy: Boolean
)

/** IP del cliente. Solo con TRUST_PROXY=true se lee X-Forwarded-For (la última entrada la agrega NUESTRO proxy). */
private fun ApplicationCall.clientAddress(trustProxy: Boolean): String {
    if (trustProxy) {
        val forwarded = request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()
        if (!forwarded.isNullOrEmpty()) return forwarded
    }
    return request.local.remoteHost
}

/** CSRF (fase 1.4): el Origin (o Referer) del POST debe coincidir con el host propio. */
private fun ApplicationCall.isSameOriginRequest(trustProxy: Boolean): Boolean {
    val forwardedHost = if (trustProxy) {
        request.headers["X-Forwarded-Host"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    } else null
    val host = forwardedHost ?: request.headers[HttpHeaders.Host]
    return OriginGuard.isSameOrigin(
        origin = request.headers[HttpHeaders.Origin],
        referer = request.headers[HttpHeaders.Referrer],
        host = host
    )
}

/**
 * Protege una ruta del tablero. Devuelve false (ya respondió) cuando no hay acceso:
 * 429 si el cliente está bloqueado por intentos fallidos, 401 si faltan o fallan las credenciales y,
 * para rutas que modifican datos ([mutating] = true), 403 si el Origin no es el propio.
 */
private suspend fun ApplicationCall.requireDashboardAuth(guard: DashboardGuard, mutating: Boolean = false): Boolean {
    val client = clientAddress(guard.trustProxy)

    val wait = guard.throttle.retryAfterSeconds(client)
    if (wait > 0) {
        response.header(HttpHeaders.RetryAfter, wait.toString())
        respondText("Demasiados intentos fallidos. Intenta de nuevo en $wait segundos.", status = HttpStatusCode.TooManyRequests)
        return false
    }

    val authorization = request.headers[HttpHeaders.Authorization]
    if (!guard.auth.isAuthorized(authorization)) {
        // Sin cabecera = primer desafío del navegador: no cuenta como intento fallido.
        if (authorization != null) guard.throttle.recordFailure(client)
        response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"Tienda Virtual Multicanal\", charset=\"UTF-8\"")
        respondText("Autenticación requerida", status = HttpStatusCode.Unauthorized)
        return false
    }

    if (mutating && !isSameOriginRequest(guard.trustProxy)) {
        respondText("Origen no permitido", status = HttpStatusCode.Forbidden)
        return false
    }
    return true
}

fun main() {
    // Fase 1.1: falla cerrado. Si la configuración de seguridad no es válida, la app NO arranca (antes de abrir BD o puerto).
    val securityPlan = try {
        DashboardSecurity.plan(AppConfig.appEnv, AppConfig.dashboardUser, AppConfig.dashboardPassword)
    } catch (e: IllegalStateException) {
        System.err.println("ERROR DE CONFIGURACIÓN: la aplicación no arrancará.")
        System.err.println("  ${e.message}")
        exitProcess(1)
    }
    securityPlan.warnings.forEach { System.err.println("ADVERTENCIA: $it") }

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

    // Fase 2.3: poblar AppStore desde la BD para que el catálogo persista entre reinicios.
    // Si la BD está conectada, carga productos e inventario antes de abrir el puerto.
    if (AppStore.databaseConnected) {
        try {
            val repo = ExposedInventarioRepository()
            val items = repo.findAll()
            items.forEach { item ->
                val product = CommercialProduct(
                    key = item.key,
                    itemName = item.item,
                    publishedPrice = item.precioPublicado ?: Money.of(0L),
                    minimumPrice = item.precioMinimo ?: Money.of(0L),
                    cost = item.precioCompra,
                    status = item.estado.etiqueta,
                    description = item.descripcion ?: "",
                    brand = "",
                    images = item.fotos
                )
                synchronized(AppStore.products) { AppStore.products[item.key.value] = product }
                synchronized(AppStore.inventory) {
                    // Preserva reservas/ventas activas si ya existía; solo actualiza el maestro.
                    val existing = AppStore.inventory[item.key.value]
                    AppStore.inventory[item.key.value] = existing?.withUpdatedMaster(1)
                        ?: OperationalInventory(item.key, stockMaestro = 1)
                }
            }
            println("  AppStore cargado desde BD: ${items.size} producto(s).")
        } catch (e: Exception) {
            System.err.println("Error cargando catálogo desde BD al arrancar: ${e.message}. Continuando con AppStore vacío.")
        }
    }
    AppStore.refreshIntegrationStatuses()
    val port = AppConfig.port
    println("===============================================================")
    println("  Tienda Virtual Multicanal, Semiautogestionada (Código Privado)")
    println("  Servidor iniciado en http://${securityPlan.host}:$port  (APP_ENV=${AppConfig.appEnv.name.lowercase()})")
    println("  Canales Activos: Shopify GraphQL, Meta Catalog, WhatsApp, IG, Messenger")
    println("===============================================================")
    embeddedServer(Netty, port = port, host = securityPlan.host, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }
    // Fase 1.3: sin CORS_ALLOWED_HOST no se instala CORS (solo mismo origen). Ya no existe anyHost().
    val corsHost = AppConfig.corsAllowedHost.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    if (corsHost.isNotBlank()) {
        install(CORS) {
            allowHost(corsHost, schemes = listOf("https", "http"))
        }
    }

    // Fase 4.1: cabeceras de seguridad HTTP en todas las respuestas.
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header(
            "Content-Security-Policy",
            "default-src 'self'; style-src 'self' 'unsafe-inline' https://cdn.tailwindcss.com; " +
            "script-src 'self' 'unsafe-inline' https://cdn.tailwindcss.com; img-src 'self' data:; frame-ancestors 'none'"
        )
    }


    val negotiationEngine = NegotiationEngine()

    // Supabase Repository (si está configurada la BD)
    val dbRepository: InventarioRepository? = if (AppConfig.databaseConfigured) {
        try { ExposedInventarioRepository() } catch (e: Exception) { null }
    } else null

    // Adaptadores E-commerce
    val shopifyAdapter = ShopifyCatalogAdapter(
        shopDomain   = AppConfig.shopifyShopDomain,
        accessToken  = AppConfig.shopifyAccessToken,
        locationId   = AppConfig.shopifyLocationId  // Fase 4.4: desde SHOPIFY_LOCATION_ID env var
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
    // La firma de Meta es obligatoria: sin App Secret el webhook responde 503 (no hay modo "sin firma").
    if (AppConfig.metaAppSecret.isBlank()) {
        System.err.println("ADVERTENCIA: META_APP_SECRET vacío; POST /webhooks/meta responderá 503 hasta que lo definas.")
    }
    if (AppConfig.metaWebhookVerifyToken.isBlank()) {
        System.err.println("ADVERTENCIA: META_WEBHOOK_VERIFY_TOKEN vacío; Meta no podrá verificar GET /webhooks/meta.")
    }

    // Protección (HTTP Basic) del tablero y de /api/*, con límite de intentos y defensa CSRF.
    // Los webhooks y la vitrina pública no pasan por aquí. DashboardSecurity.plan lanza error si la política no se cumple.
    val dashboardGuard = DashboardGuard(
        auth = DashboardSecurity.plan(AppConfig.appEnv, AppConfig.dashboardUser, AppConfig.dashboardPassword).auth,
        throttle = AuthThrottle(),
        trustProxy = AppConfig.trustProxyHeaders
    )

    val llmAdapter = GeminiLlmAdapter(apiKey = AppConfig.geminiApiKey).also { adapter ->
        // Fase 6.1: si no hay API key el adaptador funciona en modo regex (sin LLM real).
        if (AppConfig.geminiApiKey.isBlank()) {
            println("  AVISO: GEMINI_API_KEY no configurada. Interpretación de intenciones en modo regex (sin IA).")
        } else {
            println("  Gemini LLM activo.")
        }
    }

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
            if (!call.requireDashboardAuth(dashboardGuard)) return@get
            // Copias tomadas bajo candado: el pipeline modifica productos/inventario desde otras corrutinas.
            val productList = synchronized(AppStore.products) { AppStore.products.values.toList() }
            val inventorySnapshot = synchronized(AppStore.inventory) { AppStore.inventory.toMap() }
            val statuses = mutableListOf<ChannelListingStatus>()

            // Fase 2.2: los productos Inactivos no se publican en ningún canal, por eso no tienen estado de sincronización.
            productList.filter { it.isActive }.forEach { p ->
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
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
            val parameters = call.receiveParameters()
            val message = parameters["message"] ?: ""

            if (message.isBlank()) {
                call.respondText("<p class='text-rose-600'>Por favor escribe un mensaje.</p>", ContentType.Text.Html)
                return@post
            }

            val parsedIntent = llmAdapter.parseIntent(message, AppStoreCatalog.knownKeys())
            // Fase 2.2: si el mensaje nombra un producto Inactivo, el simulador responde "no disponible" igual que el chat real.
            val mentioned = parsedIntent.extractedProductKey?.let { AppStoreCatalog.product(it) }
            if (mentioned != null && !mentioned.isActive) {
                call.respondText("<p class='text-rose-600'>Este producto no está disponible (estado Inactivo).</p>", ContentType.Text.Html)
                return@post
            }
            val product = synchronized(AppStore.products) { AppStore.products.values.firstOrNull { it.isActive } }
            if (product == null) {
                call.respondText("<p class='text-rose-600'>No hay productos activos cargados. Sube primero el Excel (los productos Inactivos no se negocian).</p>", ContentType.Text.Html)
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
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
            val multipart = call.receiveMultipart()
            var fileBytes: ByteArray? = null

            var tooLarge = false
            multipart.forEachPart { part ->
                if (part is PartData.FileItem && !tooLarge) {
                    val bytes = part.streamProvider().readBytes()
                    // Fase 4.2: tope de 5 MB para archivos Excel
                    if (bytes.size > 5_242_880) {
                        tooLarge = true
                    } else {
                        fileBytes = bytes
                    }
                }
                part.dispose()
            }
            if (tooLarge) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "El archivo supera el límite de 5 MB"))
                return@post
            }

            if (fileBytes == null) {
                call.respond(HttpStatusCode.BadRequest, "No se recibió archivo .xlsx")
                return@post
            }

            val parseResult = excelParser.parseExcel(fileBytes!!.inputStream())

            // Fase 2.1: archivo dañado o sin lo mínimo -> 400 con mensaje (nunca 500). No se importa nada.
            val fileError = parseResult.fileError
            if (fileError != null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to fileError))
                return@post
            }
            // Las filas válidas SÍ se importan (más abajo); las inválidas se listan en la respuesta.

            val dbErrors = mutableListOf<String>()

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
                        // Fase 2.4: el error de BD llega al cliente (no solo a System.err)
                        val msg = "Error al guardar ${p.key} en BD: ${e.message}"
                        System.err.println(msg)
                        dbErrors.add(msg)
                    }
                }
            }

            // Fase 2.1: si todo estaba bien se vuelve al tablero; si hubo filas rechazadas o errores de BD se muestra el detalle.
            val rowWarnings = parseResult.warnings.filter { it.startsWith("Hoja 'Inventario'") }
            if (parseResult.errors.isEmpty() && rowWarnings.isEmpty() && dbErrors.isEmpty()) {
                call.respondRedirect("/")
                return@post
            }
            val imported = parseResult.products.size
            call.respond(
                if (imported == 0) HttpStatusCode.UnprocessableEntity else HttpStatusCode.OK,
                mapOf(
                    "summary" to listOf("Productos importados: $imported. Filas rechazadas: ${parseResult.rejectedRows}."),
                    "errors" to parseResult.errors,
                    "warnings" to parseResult.warnings,
                    "dbErrors" to dbErrors     // Fase 2.4: errores de persistencia visibles
                )
            )
        }

        // 4. Exportación de Datos de Supabase a Excel (.xlsx)
        get("/api/excel/export") {
            if (!call.requireDashboardAuth(dashboardGuard)) return@get
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

        // Fase 5.3: Confirmar pago manualmente desde el tablero.
        post("/api/confirmar-pago") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
            val params = call.receiveParameters()
            val dealId = params["dealId"]?.trim()

            if (dealId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "dealId requerido"))
                return@post
            }

            val updated = synchronized(AppStore.chatDeals) {
                val idx = AppStore.chatDeals.indexOfFirst { it.id == dealId }
                if (idx >= 0) {
                    val deal = AppStore.chatDeals[idx]
                    val confirmed = deal.copy(
                        dealState = DealState.PENDIENTE_ENVIO,
                        dealStateLabel = "Pago confirmado — pendiente envío"
                    )
                    AppStore.chatDeals[idx] = confirmed
                    true
                } else false
            }

            if (updated) {
                call.respondRedirect("/")
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Trato $dealId no encontrado"))
            }
        }

        // 5. Webhook Shopify HMAC
        post("/webhooks/shopify") {
            val topic = call.request.headers["X-Shopify-Topic"]
            val hmac = call.request.headers["X-Shopify-Hmac-SHA256"]
            // Fase 4.2: tope de 1 MB — rechaza payloads anómalos antes de procesarlos.
            val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
            if (contentLength > 1_048_576L) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Payload demasiado grande (máx 1 MB)"))
                return@post
            }
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

            // Fase 2.5: procesar TODOS los eventos (un evento por line_item).
            for (soldEvent in result.emittedEvents) {
                val keyStr = soldEvent.productKey.value
                if (soldEvent.quantity < 0) {
                    // Cancelación: devolver stock (sustraer de unidadesVendidas, sin bajar de 0).
                    val returnQty = -soldEvent.quantity
                    synchronized(AppStore.inventory) {
                        val inv = AppStore.inventory[keyStr]
                        if (inv != null) {
                            AppStore.inventory[keyStr] = inv.copy(
                                unidadesVendidas = maxOf(0, inv.unidadesVendidas - returnQty),
                                version = inv.version + 1
                            )
                        }
                    }
                } else {
                    val updatedInv = synchronized(AppStore.inventory) {
                        AppStore.inventory[keyStr]?.recordSale(soldEvent.quantity)
                            ?.also { AppStore.inventory[keyStr] = it }
                    }
                    if (updatedInv != null) {
                        // Fase 4.6: el handler de Ktor ya es una coroutine; no se necesita runBlocking.
                        antiOversellSaga.executeRetractionSaga(
                            productKey     = soldEvent.productKey,
                            originChannel  = "shopify",
                            externalIdMap  = AppStore.channelMappings[keyStr] ?: emptyMap(),
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
            // Fase 4.2: tope de 1 MB
            val contentLengthMeta = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
            if (contentLengthMeta > 1_048_576L) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Payload demasiado grande (máx 1 MB)"))
                return@post
            }
            val bodyBytes = call.receive<ByteArray>()

            // Firma HMAC de Meta SIEMPRE obligatoria: sin App Secret -> 503; sin firma válida -> 401.
            when (metaWebhookReceiver.authenticate(bodyBytes, call.request.headers["X-Hub-Signature-256"], AppConfig.metaAppSecret)) {
                MetaWebhookReceiver.SignatureResult.SECRET_NOT_CONFIGURED -> {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Webhook deshabilitado: falta META_APP_SECRET"))
                    return@post
                }
                MetaWebhookReceiver.SignatureResult.INVALID -> {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Firma X-Hub-Signature-256 inválida"))
                    return@post
                }
                MetaWebhookReceiver.SignatureResult.VALID -> Unit
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
            // Fase 2.2: un producto Inactivo no aparece en la vitrina (404, igual que si no existiera).
            val product = AppStoreCatalog.publicProduct(key)
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
