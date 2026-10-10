package com.automata.app.routes

import com.automata.app.AppStore
import com.automata.app.AppStoreCatalog
import com.automata.app.dashboard.ChatDealLog
import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.DashboardRenderer
import com.automata.app.dashboard.DealState
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.ports.ChannelListingStatus
import com.automata.commerce.service.NegotiationEngine
import com.automata.infra.llm.GeminiLlmAdapter
import com.automata.messaging.ports.CustomerIntentType
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.dashboardRoutes(
    dashboardGuard: DashboardGuard,
    llmAdapter: GeminiLlmAdapter,
    negotiationEngine: NegotiationEngine
) {
    // 1. Dashboard Web Interactivo
    get("/") {
        if (!call.requireDashboardAuth(dashboardGuard)) return@get
        val productList = synchronized(AppStore.products) { AppStore.products.values.toList() }
        val inventorySnapshot = synchronized(AppStore.inventory) { AppStore.inventory.toMap() }
        val statuses = mutableListOf<ChannelListingStatus>()

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

    get("/chat") {
        if (!call.requireDashboardAuth(dashboardGuard)) return@get
        val html = com.automata.app.dashboard.ChatRenderer.renderChatPage(
            conversationsHtml = "<!-- Cargado dinámicamente -->",
            activeChatHtml = "<!-- Selecciona una conversación -->"
        )
        call.respondText(html, ContentType.Text.Html)
    }

    get("/ventas") {
        if (!call.requireDashboardAuth(dashboardGuard)) return@get
        val html = com.automata.app.dashboard.VentasRenderer.renderVentasPage(
            salesHtml = "<!-- Cargado dinámicamente -->",
            syncPanelHtml = "<!-- Cargado dinámicamente -->"
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
        val mentioned = parsedIntent.extractedProductKey?.let { AppStoreCatalog.product(it) }
        if (mentioned != null && !mentioned.isActive) {
            call.respondText("<p class='text-rose-600'>Este producto no está disponible (estado Inactivo).</p>", ContentType.Text.Html)
            return@post
        }
        val product = mentioned
            ?: synchronized(AppStore.products) { AppStore.products.values.firstOrNull { it.isActive } }
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

            val safeName = DashboardRenderer.escapeHtml(product.itemName)
            val safeMsg = DashboardRenderer.escapeHtml(decision.message)
            """
            <div class="space-y-2">
                <div class="flex items-center space-x-2">
                    <span class="px-2 py-0.5 rounded text-xs font-bold $badgeColor">Nivel ${decision.level.name}</span>
                    <span class="text-xs text-gray-500">Producto: $safeName (PP: ${product.publishedPrice.formatFormattedCop()})</span>
                </div>
                <p class="font-semibold text-gray-800">&ldquo;$safeMsg&rdquo;</p>
                ${decision.counterOffer?.let { "<p class='text-xs text-indigo-600'>Contraoferta generada: ${it.formatFormattedCop()}</p>" } ?: ""}
            </div>
            """.trimIndent()
        } else {
            val safeName = DashboardRenderer.escapeHtml(product.itemName)
            """
            <div class="space-y-1">
                <p class="text-xs font-bold text-indigo-600">Intención Detectada: ${parsedIntent.intentType.name}</p>
                <p class="text-gray-700">Consulta recibida sobre $safeName. El precio publicado es ${product.publishedPrice.formatFormattedCop()}.</p>
            </div>
            """.trimIndent()
        }

        val simId = "SIM-${java.util.UUID.randomUUID().toString().take(8).uppercase()}"
        synchronized(AppStore.chatDeals) {
            AppStore.chatDeals.add(
                0,
                ChatDealLog(
                    id = simId,
                    customer = "Cliente Simulador",
                    channel = "simulator",
                    channelDisplayName = "Simulador",
                    productName = product.itemName,
                    offeredPrice = offeredPrice ?: product.publishedPrice,
                    dealState = dealState,
                    dealStateLabel = dealLabel,
                    timestamp = "Ahora mismo"
                )
            )
            if (AppStore.chatDeals.size > 100) {
                AppStore.chatDeals.subList(100, AppStore.chatDeals.size).clear()
            }
        }

        call.respondText(responseHtml, ContentType.Text.Html)
    }
}
