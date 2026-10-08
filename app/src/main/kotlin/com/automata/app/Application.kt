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
import com.automata.app.routes.*
import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.ports.InventarioRepository
import com.automata.commerce.model.ReconciliationReport
import com.automata.commerce.service.AntiOversellSaga
import com.automata.commerce.service.ArticuloLifecycleService
import com.automata.commerce.service.ClienteService
import com.automata.commerce.service.ConfirmPaymentUseCase
import com.automata.commerce.service.NegotiationEngine
import com.automata.commerce.service.ReconcileCatalogUseCase
import com.automata.commerce.service.StockDriftDetector
import com.automata.infra.config.AppConfig
import com.automata.infra.db.DatabaseFactory
import com.automata.infra.db.ExposedConversationStore
import com.automata.infra.db.ExposedImportacionesRepository
import com.automata.infra.db.ExposedInventarioRepository
import com.automata.infra.db.ExposedProcessedEventStore
import com.automata.infra.db.ExposedReservationRepository
import com.automata.infra.db.ExposedUnresolvedEventStore
import com.automata.infra.excel.ExcelPreviewService
import com.automata.infra.excel.ExcelV2Parser
import com.automata.infra.llm.GeminiLlmAdapter
import com.automata.messaging.ports.ChannelRouter
import com.automata.messaging.ports.InMemoryConversationStore
import com.automata.messaging.ports.InMemoryProcessedEventStore
import com.automata.messaging.ports.ProcessedEventStore
import com.automata.shared.ProductKey
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import org.slf4j.LoggerFactory
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

private val logger = LoggerFactory.getLogger("com.automata.app.Application")

object AppStore {
    val products = mutableMapOf<String, CommercialProduct>()
    val inventory = mutableMapOf<String, OperationalInventory>()
    val channelMappings = mutableMapOf<String, MutableMap<String, String>>()
    val apiIntegrations = mutableListOf<ApiIntegrationStatus>()
    val chatDeals = mutableListOf<ChatDealLog>()

    /** true solo si DatabaseFactory.connect() terminó sin error. */
    @Volatile var databaseConnected: Boolean = false
    @Volatile var lastReconciliationReport: ReconciliationReport? = null

    /**
     * Estado REAL de las integraciones, calculado desde la configuración (nunca inventado).
     */
    fun refreshIntegrationStatuses() {
        fun configured(vararg keys: String) = keys.all { AppConfig.get(it).isNotBlank() }
        fun status(ok: Boolean) = if (ok) ApiStatus.DEGRADADO else ApiStatus.DESHABILITADO
        val noCheck = "Sin verificar"
        val metaSig = if (AppConfig.metaAppSecret.isNotBlank()) "firma de webhook activa"
                      else "SIN META_APP_SECRET: el webhook responde 503 (firma obligatoria)"
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
    override fun productKeys(): List<ProductKey> =
        synchronized(AppStore.products) { AppStore.products.values.filter { it.isActive }.map { it.key } }

    override fun knownKeys(): List<ProductKey> =
        synchronized(AppStore.products) { AppStore.products.keys.map { ProductKey(it) } }

    override fun product(key: ProductKey): CommercialProduct? =
        synchronized(AppStore.products) { AppStore.products[key.value] }

    fun publicProduct(key: String): CommercialProduct? =
        synchronized(AppStore.products) { AppStore.products[key] }?.takeIf { it.isActive }

    override fun inventory(key: ProductKey): OperationalInventory? =
        synchronized(AppStore.inventory) { AppStore.inventory[key.value] }

    override fun saveInventory(inventory: OperationalInventory) {
        synchronized(AppStore.inventory) { AppStore.inventory[inventory.productKey.value] = inventory }
    }
}

fun main() {
    val securityPlan = try {
        DashboardSecurity.plan(AppConfig.appEnv, AppConfig.dashboardUser, AppConfig.dashboardPassword)
    } catch (e: IllegalStateException) {
        logger.error("ERROR DE CONFIGURACIÓN: la aplicación no arrancará.")
        logger.error("  ${e.message}")
        exitProcess(1)
    }
    securityPlan.warnings.forEach { logger.warn("ADVERTENCIA: $it") }

    if (AppConfig.databaseConfigured) {
        logger.info("Conectando a Supabase PostgreSQL...")
        try {
            DatabaseFactory.connect()
            AppStore.databaseConnected = true
            logger.info("Conexión a Supabase establecida correctamente.")
        } catch (e: Exception) {
            logger.error("No se pudo conectar a Supabase: ${e.message}", e)
        }
    }

    if (AppStore.databaseConnected) {
        try {
            val repo = ExposedInventarioRepository()
            val items = repo.findAll()
            items.forEach { item ->
                val product = CommercialProduct(
                    key = item.key,
                    itemName = item.item,
                    publishedPrice = item.precioPublicado ?: com.automata.shared.Money.of(0L),
                    minimumPrice = item.precioMinimo ?: com.automata.shared.Money.of(0L),
                    cost = item.precioCompra,
                    status = item.estado.etiqueta,
                    description = item.descripcion ?: "",
                    brand = item.marca ?: "",
                    condition = item.condicion ?: "Nuevo",
                    category = item.tipo ?: "General",
                    location = item.ubicacion,
                    images = item.fotos,
                    masterQuantity = item.cantidad
                )
                synchronized(AppStore.products) { AppStore.products[item.key.value] = product }
                synchronized(AppStore.inventory) {
                    val existing = AppStore.inventory[item.key.value]
                    AppStore.inventory[item.key.value] = existing?.withUpdatedMaster(item.cantidad)
                        ?: OperationalInventory(item.key, stockMaestro = item.cantidad)
                }
            }
            logger.info("  AppStore cargado desde BD: ${items.size} producto(s).")
        } catch (e: Exception) {
            logger.error("Error cargando catálogo desde BD al arrancar: ${e.message}. Continuando con AppStore vacío.", e)
        }
    }
    AppStore.refreshIntegrationStatuses()
    val port = AppConfig.port
    logger.info("===============================================================")
    logger.info("  Tienda Virtual Multicanal, Semiautogestionada (Código Privado)")
    logger.info("  Servidor iniciado en http://${securityPlan.host}:$port  (APP_ENV=${AppConfig.appEnv.name.lowercase()})")
    logger.info("  Canales Activos: Shopify GraphQL, Meta Catalog, WhatsApp, IG, Messenger")
    logger.info("===============================================================")
    embeddedServer(Netty, port = port, host = securityPlan.host, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }
    val corsHost = AppConfig.corsAllowedHost.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    if (corsHost.isNotBlank()) {
        install(CORS) {
            allowHost(corsHost, schemes = listOf("https", "http"))
        }
    }

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

    val dbRepository: InventarioRepository? = if (AppConfig.databaseConfigured) {
        try { ExposedInventarioRepository() } catch (e: Exception) { null }
    } else null

    val outboxPublisher = if (AppStore.databaseConnected) com.automata.infra.db.OutboxEventPublisher() else null
    val articuloService: ArticuloLifecycleService? = dbRepository?.let { repo ->
        ArticuloLifecycleService(repo) { tipo, sku, stock ->
            outboxPublisher?.publishEvent(tipo, sku, "ALL", stock)
        }
    }

    val shopifyAdapter = ShopifyCatalogAdapter(
        shopDomain   = AppConfig.shopifyShopDomain,
        accessToken  = AppConfig.shopifyAccessToken,
        locationId   = AppConfig.shopifyLocationId
    )
    val metaCatalogAdapter = MetaShopAdapter(
        catalogId = AppConfig.metaCatalogId,
        accessToken = AppConfig.metaAccessToken,
        countryCode = AppConfig.metaCountryCode
    )
    val antiOversellSaga = AntiOversellSaga(listOf(shopifyAdapter, metaCatalogAdapter))
    val reconcileUseCase = ReconcileCatalogUseCase(listOf(shopifyAdapter, metaCatalogAdapter))
    val unresolvedEventStore = if (AppStore.databaseConnected) ExposedUnresolvedEventStore() else null
    val stockDriftDetector = StockDriftDetector()
    val clienteService = ClienteService()
    val confirmPaymentUseCase = ConfirmPaymentUseCase(dbRepository)

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

    val channelRouter = ChannelRouter(listOf(whatsAppAdapter, instagramAdapter, messengerAdapter))

    val shopifyWebhookHandler = ShopifyWebhookHandler(webhookSecret = AppConfig.shopifyWebhookSecret)
    val metaWebhookReceiver = MetaWebhookReceiver(verifyToken = AppConfig.metaWebhookVerifyToken)
    val processedEvents: ProcessedEventStore =
        if (AppStore.databaseConnected) ExposedProcessedEventStore() else InMemoryProcessedEventStore()

    if (AppConfig.metaAppSecret.isBlank()) {
        logger.warn("ADVERTENCIA: META_APP_SECRET vacío; POST /webhooks/meta responderá 503 hasta que lo definas.")
    }
    if (AppConfig.metaWebhookVerifyToken.isBlank()) {
        logger.warn("ADVERTENCIA: META_WEBHOOK_VERIFY_TOKEN vacío; Meta no podrá verificar GET /webhooks/meta.")
    }

    val dashboardGuard = DashboardGuard(
        auth = DashboardSecurity.plan(AppConfig.appEnv, AppConfig.dashboardUser, AppConfig.dashboardPassword).auth,
        throttle = AuthThrottle(),
        trustProxy = AppConfig.trustProxyHeaders
    )

    val llmAdapter = GeminiLlmAdapter(apiKey = AppConfig.geminiApiKey).also { adapter ->
        if (AppConfig.geminiApiKey.isBlank()) {
            logger.info("  AVISO: GEMINI_API_KEY no configurada. Interpretación de intenciones en modo regex (sin IA).")
        } else {
            logger.info("  Gemini LLM activo.")
        }
    }

    val reservationRepo = if (AppStore.databaseConnected) {
        try { ExposedReservationRepository() } catch (e: Exception) {
            logger.error("No se pudo crear ExposedReservationRepository: ${e.message}", e)
            null
        }
    } else null

    val inboundPipeline = InboundMessagePipeline(
        llm = llmAdapter,
        engine = negotiationEngine,
        catalog = AppStoreCatalog,
        conversations = if (AppStore.databaseConnected) ExposedConversationStore() else InMemoryConversationStore(),
        router = channelRouter,
        paymentInstructions = AppConfig.paymentInstructions,
        reservationRepo = reservationRepo
    )

    if (reservationRepo != null) {
        try {
            runBlocking { inboundPipeline.loadActiveReservations() }
        } catch (e: Exception) {
            logger.error("Error recuperando reservas activas de la BD: ${e.message}", e)
        }
    }

    val inboundWorker = if (AppStore.databaseConnected) com.automata.app.workers.InboundWebhookWorker(inboundPipeline) else null
    val outboxWorker = if (AppStore.databaseConnected) com.automata.app.workers.OutboxWorker(listOf(shopifyAdapter, metaCatalogAdapter)) else null

    val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    pipelineScope.launch {
        while (isActive) {
            delay(60_000)
            try { inboundPipeline.sweep() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                logger.error("Error liberando reservas vencidas: ${e.message}", e)
            }
        }
    }
    if (inboundWorker != null) {
        pipelineScope.launch {
            while (isActive) {
                delay(5_000)
                try { inboundWorker.processBatch() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    logger.error("Error procesando worker de webhooks entrantes: ${e.message}", e)
                }
            }
        }
    }
    if (outboxWorker != null) {
        pipelineScope.launch {
            while (isActive) {
                delay(5_000)
                try { outboxWorker.processBatch() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    logger.error("Error procesando worker de outbox: ${e.message}", e)
                }
            }
        }
    }
    environment.monitor.subscribe(ApplicationStopping) { pipelineScope.cancel() }
    val excelParser = ExcelV2Parser()
    val importacionesRepo = if (AppStore.databaseConnected) ExposedImportacionesRepository() else null
    val excelPreviewService = ExcelPreviewService(
        parser = excelParser,
        repo = dbRepository,
        importacionesRepo = importacionesRepo
    )

    routing {
        dashboardRoutes(dashboardGuard, llmAdapter, negotiationEngine)
        excelRoutes(dashboardGuard, dbRepository, importacionesRepo, excelPreviewService, excelParser)
        articulosRoutes(dashboardGuard, articuloService, reservationRepo)
        reconciliacionRoutes(dashboardGuard, reconcileUseCase)
        eventosDerivaRoutes(dashboardGuard, unresolvedEventStore, stockDriftDetector)
        clientesRoutes(dashboardGuard, clienteService)
        pagosRoutes(dashboardGuard, confirmPaymentUseCase, inboundPipeline, antiOversellSaga)
        webhookRoutes(shopifyWebhookHandler, metaWebhookReceiver, processedEvents, dbRepository, antiOversellSaga, inboundPipeline, pipelineScope)
        publicRoutes()
    }
}
