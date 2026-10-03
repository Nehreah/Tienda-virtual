package com.automata.app.dashboard

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.ports.ChannelListingStatus

/**
 * Generador de HTML para el Dashboard Operacional v5.3.
 * Incluye:
 * 1. Monitoreo de APIs e Integraciones (Funcional, Degradado, Deshabilitado).
 * 2. Estado de Chats y Negociaciones por Canal/App (Trato Realizado, Cancelado, Pendiente de Envío, En Negociación).
 * 3. Pantalla / Sección de Métricas estructurada con tarjetas KPI y contenedores para futuras gráficas.
 */
object DashboardRenderer {

    fun renderDashboardPage(
        products: List<CommercialProduct>,
        inventoryMap: Map<String, OperationalInventory>,
        channelStatuses: List<ChannelListingStatus>,
        apiIntegrations: List<ApiIntegrationStatus>,
        chatDeals: List<ChatDealLog>
    ): String {
        val totalProducts = products.size
        val readyForShopify = channelStatuses.count { it.channel == "shopify" && it.isPublished }
        val readyForMeta = channelStatuses.count { it.channel == "meta_catalogo" && it.isPublished }

        // Conteo de tratos por estado
        val tratosCerrados = chatDeals.count { it.dealState == DealState.TRATO_CERRADO }
        val pendientesEnvio = chatDeals.count { it.dealState == DealState.PENDIENTE_ENVIO }
        val enNegociacion = chatDeals.count { it.dealState == DealState.EN_NEGOCIACION }
        val cancelados = chatDeals.count { it.dealState == DealState.CANCELADO }

        // Filas para la tabla de Productos
        val productRowsHtml = products.joinToString("\n") { p ->
            val inv = inventoryMap[p.key.value]
            val stockVivo = inv?.stockVivo ?: 0
            val reservas = inv?.reservasActivas ?: 0
            val shopifyStatus = channelStatuses.find { it.productKey == p.key && it.channel == "shopify" }
            val metaStatus = channelStatuses.find { it.productKey == p.key && it.channel == "meta_catalogo" }

            """
            <tr class="hover:bg-gray-50 border-b transition text-sm">
                <td class="px-4 py-3 font-mono font-semibold text-indigo-700">${escapeHtml(p.key.value)}</td>
                <td class="px-4 py-3 font-medium text-gray-900">${escapeHtml(p.itemName)}</td>
                <td class="px-4 py-3 text-gray-600">${escapeHtml(p.brand)}</td>
                <td class="px-4 py-3 text-right font-semibold text-gray-900">${p.publishedPrice.formatFormattedCop()}</td>
                <td class="px-4 py-3 text-center">
                    <span class="px-2.5 py-1 text-xs font-semibold rounded-full bg-blue-100 text-blue-800">
                        ${p.masterQuantity} maestro
                    </span>
                </td>
                <td class="px-4 py-3 text-center">
                    <span class="px-2.5 py-1 text-xs font-semibold rounded-full ${if (stockVivo > 0) "bg-emerald-100 text-emerald-800" else "bg-rose-100 text-rose-800"}">
                        $stockVivo vivo (${reservas} res)
                    </span>
                </td>
                <td class="px-4 py-3 text-center text-xs">
                    ${if (shopifyStatus?.isPublished == true) "<span class='px-2 py-0.5 rounded bg-green-100 text-green-800 font-semibold'>ACTIVO</span>" else "<span class='px-2 py-0.5 rounded bg-yellow-100 text-yellow-800 font-semibold'>PENDIENTE</span>"}
                </td>
                <td class="px-4 py-3 text-center text-xs">
                    ${if (metaStatus?.isPublished == true) "<span class='px-2 py-0.5 rounded bg-purple-100 text-purple-800 font-semibold'>CATALOG_ONLY</span>" else "<span class='px-2 py-0.5 rounded bg-gray-100 text-gray-700 font-semibold'>SIN SYNC</span>"}
                </td>
            </tr>
            """.trimIndent()
        }

        // Cards de Estado de APIs
        val apiCardsHtml = apiIntegrations.joinToString("\n") { api ->
            val statusColor = when (api.status) {
                ApiStatus.FUNCIONAL -> "bg-emerald-100 text-emerald-800 border-emerald-300"
                ApiStatus.DEGRADADO -> "bg-amber-100 text-amber-800 border-amber-300"
                ApiStatus.DESHABILITADO -> "bg-rose-100 text-rose-800 border-rose-300"
            }
            val dotColor = when (api.status) {
                ApiStatus.FUNCIONAL -> "bg-emerald-500 animate-pulse"
                ApiStatus.DEGRADADO -> "bg-amber-500"
                ApiStatus.DESHABILITADO -> "bg-rose-500"
            }

            """
            <div class="bg-white rounded-xl shadow-sm border p-4 hover:shadow-md transition space-y-2">
                <div class="flex justify-between items-start">
                    <div>
                        <span class="text-[10px] uppercase font-bold tracking-wider text-gray-400 block">${api.category}</span>
                        <h4 class="font-bold text-gray-900 text-sm mt-0.5">${escapeHtml(api.name)}</h4>
                    </div>
                    <span class="px-2 py-0.5 text-[11px] font-bold rounded-full border flex items-center space-x-1 $statusColor">
                        <span class="w-1.5 h-1.5 rounded-full $dotColor mr-1"></span>
                        ${api.status.name}
                    </span>
                </div>
                <p class="text-xs text-gray-600 leading-snug">${escapeHtml(api.detail)}</p>
                <div class="text-[10px] text-gray-400 border-t pt-1.5 flex justify-between">
                    <span>Último check: ${api.lastCheck}</span>
                    <span class="text-indigo-600 font-semibold">HTTPS / JSON</span>
                </div>
            </div>
            """.trimIndent()
        }

        // Filas para la tabla de Chats y Tratos
        val chatDealRowsHtml = chatDeals.joinToString("\n") { chat ->
            val (badgeClass, badgeText) = when (chat.dealState) {
                DealState.TRATO_CERRADO -> "bg-emerald-100 text-emerald-800 border-emerald-300" to "✓ ${escapeHtml(chat.dealStateLabel)}"
                DealState.PENDIENTE_ENVIO -> "bg-blue-100 text-blue-800 border-blue-300" to "📦 ${escapeHtml(chat.dealStateLabel)}"
                DealState.EN_NEGOCIACION -> "bg-amber-100 text-amber-800 border-amber-300" to "⏳ ${escapeHtml(chat.dealStateLabel)}"
                DealState.CANCELADO -> "bg-rose-100 text-rose-800 border-rose-300" to "✕ ${escapeHtml(chat.dealStateLabel)}"
            }

            val channelBadge = when (chat.channel.lowercase()) {
                "whatsapp" -> "<span class='px-2 py-0.5 rounded text-xs font-semibold bg-emerald-50 text-emerald-700 border border-emerald-200'>WhatsApp</span>"
                "instagram" -> "<span class='px-2 py-0.5 rounded text-xs font-semibold bg-pink-50 text-pink-700 border border-pink-200'>Instagram DM</span>"
                "messenger" -> "<span class='px-2 py-0.5 rounded text-xs font-semibold bg-blue-50 text-blue-700 border border-blue-200'>Messenger</span>"
                "shopify_webhooks" -> "<span class='px-2 py-0.5 rounded text-xs font-semibold bg-green-50 text-green-700 border border-green-200'>Shopify Order</span>"
                else -> "<span class='px-2 py-0.5 rounded text-xs font-semibold bg-purple-50 text-purple-700 border border-purple-200'>Simulador</span>"
            }

            """
            <tr class="hover:bg-gray-50 border-b transition text-sm chat-row" data-channel="${escapeHtml(chat.channel)}">
                <td class="px-4 py-3 font-mono text-xs font-semibold text-gray-500">${escapeHtml(chat.id)}</td>
                <td class="px-4 py-3">$channelBadge</td>
                <td class="px-4 py-3 font-medium text-gray-900">${escapeHtml(chat.customer)}</td>
                <td class="px-4 py-3 text-gray-700">${escapeHtml(chat.productName)}</td>
                <td class="px-4 py-3 text-right font-bold text-gray-900">${escapeHtml(chat.offeredPrice)}</td>
                <td class="px-4 py-3 text-center">
                    <span class="px-2.5 py-1 text-xs font-bold rounded-full border inline-block $badgeClass">
                        $badgeText
                    </span>
                </td>
                <td class="px-4 py-3 text-right text-xs text-gray-400">${escapeHtml(chat.timestamp)}</td>
            </tr>
            """.trimIndent()
        }

        return """
        <!DOCTYPE html>
        <html lang="es">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Tienda Virtual Multicanal, Semiautogestionada</title>
            <script src="https://cdn.tailwindcss.com"></script>
            <script src="https://unpkg.com/htmx.org@1.9.10"></script>
            <style>
                body { background-color: #F5F6FC; font-family: system-ui, -apple-system, sans-serif; }
                .navy-header { background-color: #0B1020; }
                .violet-accent { color: #6C5CE7; }
                .bg-violet-accent { background-color: #6C5CE7; }
                .nav-tab-active { border-bottom: 3px solid #6C5CE7; color: #FFFFFF; font-weight: bold; }
                .nav-tab-inactive { color: #9CA3AF; }
                .nav-tab-inactive:hover { color: #E5E7EB; }
            </style>
        </head>
        <body class="text-gray-800">

            <!-- Header Principal Navy con Navegación por Pestañas -->
            <header class="navy-header text-white px-6 pt-4 pb-0 shadow-md">
                <div class="max-w-7xl mx-auto flex justify-between items-center mb-4">
                    <div class="flex items-center space-x-3">
                        <div class="w-9 h-9 rounded-lg bg-violet-accent flex items-center justify-center font-bold text-white text-lg shadow">A</div>
                        <div>
                            <h1 class="text-xl font-bold tracking-tight">Tienda Virtual Multicanal, Semiautogestionada</h1>
                            <p class="text-xs text-indigo-300">v5.3 • Matriz Comercial Excel & Engine de Negociación L0-L4</p>
                        </div>
                    </div>
                    <div class="flex items-center space-x-3">
                        <span class="px-3 py-1 bg-emerald-900/60 text-emerald-300 border border-emerald-500/30 text-xs rounded-full flex items-center shadow-sm">
                            <span class="w-2 h-2 rounded-full bg-emerald-400 mr-2 animate-pulse"></span> Sistema Operativo & APIs Conectadas
                        </span>
                    </div>
                </div>

                <!-- Barra de Navegación por Pestañas (Operación vs Métricas) -->
                <div class="max-w-7xl mx-auto flex space-x-8 text-sm border-t border-gray-800 pt-2">
                    <button id="btn-tab-ops" onclick="switchTab('ops')" class="py-2.5 px-1 nav-tab-active flex items-center space-x-2 outline-none">
                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 17V7m0 10a2 2 0 01-2 2H5a2 2 0 01-2-2V7a2 2 0 012-2h2a2 2 0 012 2m0 10a2 2 0 002 2h2a2 2 0 002-2M9 7a2 2 0 012-2h2a2 2 0 012 2m0 10V7m0 10a2 2 0 002 2h2a2 2 0 002-2V7a2 2 0 00-2-2h-2a2 2 0 00-2 2"></path></svg>
                        <span>🖥️ Operación, APIs & Chats</span>
                    </button>
                    <button id="btn-tab-metrics" onclick="switchTab('metrics')" class="py-2.5 px-1 nav-tab-inactive flex items-center space-x-2 outline-none">
                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 8v8m-4-5v5m-4-2v2m-2 4h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"></path></svg>
                        <span>📊 Pantalla de Métricas & Analítica</span>
                    </button>
                </div>
            </header>

            <main class="max-w-7xl mx-auto px-6 py-8 space-y-8">

                <!-- ==================== TAB 1: OPERACIÓN, APIS & CHATS ==================== -->
                <div id="tab-ops" class="space-y-8">

                    <!-- Fila 1: Indicadores KPI Rápidos -->
                    <div class="grid grid-cols-1 md:grid-cols-4 gap-6">
                        <div class="bg-white rounded-xl shadow-sm border p-5">
                            <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider">Catálogo Comercial</p>
                            <h3 class="text-3xl font-extrabold text-gray-900 mt-1">$totalProducts <span class="text-sm font-normal text-gray-500">ítems</span></h3>
                            <p class="text-xs text-indigo-600 mt-2">Fuente de verdad: Excel v2</p>
                        </div>

                        <div class="bg-white rounded-xl shadow-sm border p-5">
                            <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider">Shopify GraphQL API</p>
                            <h3 class="text-3xl font-extrabold text-emerald-600 mt-1">$readyForShopify / $totalProducts</h3>
                            <p class="text-xs text-emerald-600 mt-2">Publicados & Webhooks HMAC Activos</p>
                        </div>

                        <div class="bg-white rounded-xl shadow-sm border p-5">
                            <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider">Meta Shop (Colombia)</p>
                            <h3 class="text-3xl font-extrabold text-purple-600 mt-1">$readyForMeta / $totalProducts</h3>
                            <p class="text-xs text-purple-600 mt-2">Modo: CATALOG_ONLY</p>
                        </div>

                        <div class="bg-white rounded-xl shadow-sm border p-5">
                            <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider">Negociación L0-L4</p>
                            <h3 class="text-3xl font-extrabold text-indigo-600 mt-1">Protección PM</h3>
                            <p class="text-xs text-indigo-600 mt-2">Invarianza ${'$'}Precio &ge; L4 &ge; PM Activa</p>
                        </div>
                    </div>

                    <!-- SECCIÓN: ESTADO DE APIS E INTEGRACIONES INSTALADAS -->
                    <div class="space-y-4">
                        <div class="flex justify-between items-center">
                            <div>
                                <h3 class="text-lg font-bold text-gray-900 flex items-center">
                                    <svg class="w-5 h-5 mr-2 text-indigo-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"></path></svg>
                                    Estado de APIs & Aplicaciones Conectadas
                                </h3>
                                <p class="text-xs text-gray-500">Monitoreo en tiempo real de disponibilidad, tokens y adaptadores instalados</p>
                            </div>
                            <span class="text-xs bg-indigo-50 text-indigo-700 px-3 py-1 rounded-full border border-indigo-200 font-semibold">
                                ${apiIntegrations.size} Servicios Instalados
                            </span>
                        </div>
                        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
                            $apiCardsHtml
                        </div>
                    </div>

                    <!-- SECCIÓN: CHATS Y ESTADO DE TRATOS POR APP / CANAL -->
                    <div class="bg-white rounded-xl shadow-sm border overflow-hidden space-y-4 p-6">
                        <div class="flex flex-col md:flex-row md:items-center justify-between border-b pb-4 gap-4">
                            <div>
                                <h3 class="text-lg font-bold text-gray-900 flex items-center">
                                    <svg class="w-5 h-5 mr-2 text-emerald-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"></path></svg>
                                    Estado de Chats & Conversaciones por Canal / App
                                </h3>
                                <p class="text-xs text-gray-500">Tratos realizados, pendientes de envío, negociaciones activas y cancelaciones</p>
                            </div>
                            <!-- KPI Summary Badges -->
                            <div class="flex flex-wrap gap-2 text-xs">
                                <span class="px-3 py-1.5 rounded-lg bg-emerald-50 text-emerald-800 border border-emerald-200 font-bold">
                                    ✓ Tratos Realizados: $tratosCerrados
                                </span>
                                <span class="px-3 py-1.5 rounded-lg bg-blue-50 text-blue-800 border border-blue-200 font-bold">
                                    📦 Pendientes Envío: $pendientesEnvio
                                </span>
                                <span class="px-3 py-1.5 rounded-lg bg-amber-50 text-amber-800 border border-amber-200 font-bold">
                                    ⏳ En Negociación: $enNegociacion
                                </span>
                                <span class="px-3 py-1.5 rounded-lg bg-rose-50 text-rose-800 border border-rose-200 font-bold">
                                    ✕ Cancelados: $cancelados
                                </span>
                            </div>
                        </div>

                        <!-- Filtros Rápidos por Canal -->
                        <div class="flex items-center space-x-2 text-xs overflow-x-auto pb-1">
                            <span class="text-gray-500 font-semibold mr-1">Filtrar Canal:</span>
                            <button onclick="filterChats('all')" class="px-3 py-1 bg-indigo-600 text-white rounded-full font-semibold outline-none filter-btn" id="filter-all">Todos</button>
                            <button onclick="filterChats('whatsapp')" class="px-3 py-1 bg-gray-100 text-gray-700 hover:bg-gray-200 rounded-full font-semibold outline-none filter-btn" id="filter-whatsapp">WhatsApp</button>
                            <button onclick="filterChats('instagram')" class="px-3 py-1 bg-gray-100 text-gray-700 hover:bg-gray-200 rounded-full font-semibold outline-none filter-btn" id="filter-instagram">Instagram DM</button>
                            <button onclick="filterChats('messenger')" class="px-3 py-1 bg-gray-100 text-gray-700 hover:bg-gray-200 rounded-full font-semibold outline-none filter-btn" id="filter-messenger">Messenger</button>
                            <button onclick="filterChats('shopify_webhooks')" class="px-3 py-1 bg-gray-100 text-gray-700 hover:bg-gray-200 rounded-full font-semibold outline-none filter-btn" id="filter-shopify_webhooks">Shopify Orders</button>
                        </div>

                        <!-- Tabla de Chats -->
                        <div class="overflow-x-auto border rounded-lg">
                            <table class="w-full text-left border-collapse">
                                <thead>
                                    <tr class="bg-gray-100 text-xs font-semibold text-gray-600 uppercase border-b">
                                        <th class="px-4 py-2.5">ID Chat</th>
                                        <th class="px-4 py-2.5">Canal / App</th>
                                        <th class="px-4 py-2.5">Cliente</th>
                                        <th class="px-4 py-2.5">Producto</th>
                                        <th class="px-4 py-2.5 text-right">Oferta / Monto</th>
                                        <th class="px-4 py-2.5 text-center">Estado del Trato</th>
                                        <th class="px-4 py-2.5 text-right">Tiempo</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    $chatDealRowsHtml
                                </tbody>
                            </table>
                        </div>
                    </div>

                    <!-- Fila 3: Importador de Excel & Simulador de Negociación L0-L4 -->
                    <div class="grid grid-cols-1 lg:grid-cols-2 gap-8">

                        <!-- Importador de Excel v2 -->
                        <div class="bg-white rounded-xl shadow-sm border p-6 space-y-4">
                            <div class="flex justify-between items-center border-b pb-3">
                                <h2 class="text-lg font-bold text-gray-900 flex items-center">
                                    <svg class="w-5 h-5 mr-2 text-indigo-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"></path></svg>
                                    Importación Matriz Comercial (Excel v2)
                                </h2>
                                <span class="text-xs text-gray-500">3 Hojas: Inventario, Publicacion, Canales</span>
                            </div>
                            <form action="/api/excel/upload" method="post" enctype="multipart/form-data" class="space-y-4">
                                <div class="border-2 border-dashed border-gray-300 rounded-lg p-6 text-center hover:border-indigo-400 transition bg-gray-50">
                                    <input type="file" name="file" accept=".xlsx" class="block w-full text-sm text-gray-500 file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-semibold file:bg-indigo-50 file:text-indigo-700 hover:file:bg-indigo-100"/>
                                    <p class="text-xs text-gray-400 mt-2">Cargue archivo .xlsx comercial (ej. NEGOCIO.xlsx)</p>
                                </div>
                                <button type="submit" class="w-full bg-violet-accent text-white py-2.5 px-4 rounded-lg font-semibold hover:bg-indigo-700 transition shadow">
                                    Cargar y Sincronizar Operación
                                </button>
                            </form>
                        </div>

                        <!-- Simulador de Chat & Negociación L0-L4 -->
                        <div class="bg-white rounded-xl shadow-sm border p-6 space-y-4">
                            <div class="flex justify-between items-center border-b pb-3">
                                <h2 class="text-lg font-bold text-gray-900 flex items-center">
                                    <svg class="w-5 h-5 mr-2 text-emerald-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"></path></svg>
                                    Simulador de Negociación L0-L4
                                </h2>
                                <span class="text-xs bg-emerald-100 text-emerald-800 px-2 py-0.5 rounded font-semibold">Live Engine</span>
                            </div>
                            <div id="sim-results" class="bg-gray-50 p-4 rounded-lg text-sm min-h-[100px] border font-mono">
                                <p class="text-gray-400 italic">Escriba una oferta de cliente para probar la escalera de negociación y la protección PM...</p>
                            </div>
                            <form hx-post="/api/sim/chat" hx-target="#sim-results" class="flex space-x-2">
                                <input type="text" name="message" placeholder="Ej: Te ofrezco 95000 por los audífonos PROD-001" class="flex-1 border rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none"/>
                                <button type="submit" class="bg-emerald-600 text-white px-4 py-2 rounded-lg font-semibold hover:bg-emerald-700 transition text-sm">
                                    Probar Oferta
                                </button>
                            </form>
                        </div>

                    </div>

                    <!-- Fila 4: Tabla de Catálogo & Sincronización Operacional -->
                    <div class="bg-white rounded-xl shadow-sm border overflow-hidden">
                        <div class="px-6 py-4 border-b flex justify-between items-center bg-gray-50">
                            <h3 class="font-bold text-gray-900 text-lg">Catálogo & Estado Operacional Multicanal</h3>
                            <span class="text-xs text-gray-500">Cero fugas de información financiera interna</span>
                        </div>
                        <div class="overflow-x-auto">
                            <table class="w-full text-left border-collapse">
                                <thead>
                                    <tr class="bg-gray-100 text-xs font-semibold text-gray-600 uppercase border-b">
                                        <th class="px-4 py-3">Key</th>
                                        <th class="px-4 py-3">Producto</th>
                                        <th class="px-4 py-3">Marca</th>
                                        <th class="px-4 py-3 text-right">Precio Pub (PP)</th>
                                        <th class="px-4 py-3 text-center">Stock Maestro</th>
                                        <th class="px-4 py-3 text-center">Stock Vivo BD</th>
                                        <th class="px-4 py-3 text-center">Shopify</th>
                                        <th class="px-4 py-3 text-center">Meta Shop</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    $productRowsHtml
                                </tbody>
                            </table>
                        </div>
                    </div>

                </div>

                <!-- ==================== TAB 2: PANTALLA DE MÉTRICAS & ANALÍTICA ==================== -->
                <div id="tab-metrics" class="space-y-8 hidden">

                    <!-- Header de Métricas con Banner Informativo -->
                    <div class="bg-gradient-to-r from-indigo-900 via-indigo-800 to-purple-900 text-white rounded-2xl p-6 shadow-md space-y-3">
                        <div class="flex justify-between items-start">
                            <div>
                                <span class="px-3 py-1 bg-purple-500/30 text-purple-200 border border-purple-400/30 text-xs font-bold rounded-full uppercase tracking-wider">
                                    Panel de Analítica Operacional
                                </span>
                                <h2 class="text-2xl font-extrabold mt-2">📊 Pantalla de Métricas & Estadísticas</h2>
                                <p class="text-sm text-indigo-200 mt-1">Estructura preparada para la integración de reglas de negocio, tablas de rendimiento y gráficas interactivas.</p>
                            </div>
                            <span class="bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 text-xs px-3 py-1.5 rounded-lg font-semibold flex items-center">
                                <span class="w-2 h-2 rounded-full bg-emerald-400 mr-2 animate-pulse"></span> Estructura de Métricas Lista
                            </span>
                        </div>

                        <div class="bg-white/10 backdrop-blur border border-white/10 rounded-xl p-4 text-xs text-indigo-100 flex items-start space-x-3">
                            <svg class="w-5 h-5 text-purple-300 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>
                            <p>
                                <strong>Espacio Reservado para Lógica de Métricas:</strong> En este panel especificaremos las lógicas de agregación de datos para las tablas y gráficas (ej. Ventas acumuladas en COP, conversión por canal, margen financiero promedio y efectividad de la escalera L0-L4).
                            </p>
                        </div>
                    </div>

                    <!-- Tarjetas de Métricas KPI Prometidas -->
                    <div class="grid grid-cols-1 md:grid-cols-4 gap-6">
                        <div class="bg-white rounded-xl shadow-sm border p-5 space-y-2">
                            <span class="text-xs font-bold text-gray-400 uppercase tracking-wider">Ventas Totales Brutas</span>
                            <h3 class="text-3xl font-black text-emerald-600">$470.000 COP</h3>
                            <p class="text-xs text-gray-500 flex items-center">
                                <span class="text-emerald-600 font-bold mr-1">↑ 3 tratos</span> cerrados en canales
                            </p>
                        </div>

                        <div class="bg-white rounded-xl shadow-sm border p-5 space-y-2">
                            <span class="text-xs font-bold text-gray-400 uppercase tracking-wider">Tasa de Conversión Tratos</span>
                            <h3 class="text-3xl font-black text-indigo-600">75.0%</h3>
                            <p class="text-xs text-gray-500">3 de 4 ofertas dentro de PM</p>
                        </div>

                        <div class="bg-white rounded-xl shadow-sm border p-5 space-y-2">
                            <span class="text-xs font-bold text-gray-400 uppercase tracking-wider">Margen Protegido (PM)</span>
                            <h3 class="text-3xl font-black text-purple-600">38.5%</h3>
                            <p class="text-xs text-emerald-600 font-semibold">0% Fugas financieras</p>
                        </div>

                        <div class="bg-white rounded-xl shadow-sm border p-5 space-y-2">
                            <span class="text-xs font-bold text-gray-400 uppercase tracking-wider">Tiempo Promedio Cierre</span>
                            <h3 class="text-3xl font-black text-blue-600">4.2 min</h3>
                            <p class="text-xs text-gray-500">Respuesta automatizada LLM</p>
                        </div>
                    </div>

                    <!-- Contenedores de Gráficas y Tablas de Métricas (Placeholder Estructurado) -->
                    <div class="grid grid-cols-1 lg:grid-cols-2 gap-8">

                        <!-- Placeholder Gráfica 1: Distribución por Canal -->
                        <div class="bg-white rounded-xl shadow-sm border p-6 space-y-4">
                            <div class="flex justify-between items-center border-b pb-3">
                                <div>
                                    <h3 class="font-bold text-gray-900 text-base">Ventas & Tratos por Canal (WhatsApp, IG, Messenger, Shopify)</h3>
                                    <p class="text-xs text-gray-400">Distribución de conversión por aplicación instalada</p>
                                </div>
                                <span class="text-[11px] bg-indigo-50 text-indigo-700 px-2.5 py-1 rounded font-mono font-semibold">Gráfico Chart.js / Canvas</span>
                            </div>
                            <div class="bg-gray-50 rounded-lg p-8 border-2 border-dashed border-gray-200 flex flex-col items-center justify-center text-center space-y-3 min-h-[220px]">
                                <div class="w-12 h-12 rounded-full bg-indigo-100 flex items-center justify-center text-indigo-600">
                                    <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 3.055A9.001 9.001 0 1020.945 13H11V3.055z"></path></svg>
                                </div>
                                <p class="text-xs text-gray-500 font-medium max-w-sm">
                                    Contenedor listo para renderizar gráfica circular/barras de distribución por canal una vez definidas las reglas de analítica.
                                </p>
                            </div>
                        </div>

                        <!-- Placeholder Gráfica 2: Conversión por Nivel L0-L4 -->
                        <div class="bg-white rounded-xl shadow-sm border p-6 space-y-4">
                            <div class="flex justify-between items-center border-b pb-3">
                                <div>
                                    <h3 class="font-bold text-gray-900 text-base">Efectividad de Escalera L0 a L4</h3>
                                    <p class="text-xs text-gray-400">Ofertas aceptadas vs contraofertas en cada nivel</p>
                                </div>
                                <span class="text-[11px] bg-purple-50 text-purple-700 px-2.5 py-1 rounded font-mono font-semibold">Funnel Chart</span>
                            </div>
                            <div class="bg-gray-50 rounded-lg p-8 border-2 border-dashed border-gray-200 flex flex-col items-center justify-center text-center space-y-3 min-h-[220px]">
                                <div class="w-12 h-12 rounded-full bg-purple-100 flex items-center justify-center text-purple-600">
                                    <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"></path></svg>
                                </div>
                                <p class="text-xs text-gray-500 font-medium max-w-sm">
                                    Contenedor de embudo de conversión L0 a L4 listo para las reglas de métricas que indicará el usuario.
                                </p>
                            </div>
                        </div>

                    </div>

                    <!-- Tabla de Métricas Operacionales por Producto -->
                    <div class="bg-white rounded-xl shadow-sm border overflow-hidden">
                        <div class="px-6 py-4 border-b flex justify-between items-center bg-gray-50">
                            <div>
                                <h3 class="font-bold text-gray-900 text-lg">Métricas de Rendimiento Financiero por Producto</h3>
                                <p class="text-xs text-gray-500">Consultas, conversiones y margen bruto promedio</p>
                            </div>
                            <span class="text-xs font-semibold text-indigo-600 bg-indigo-50 px-3 py-1 rounded-full border border-indigo-200">
                                Tabla de Métricas Preparada
                            </span>
                        </div>
                        <div class="overflow-x-auto">
                            <table class="w-full text-left border-collapse">
                                <thead>
                                    <tr class="bg-gray-100 text-xs font-semibold text-gray-600 uppercase border-b">
                                        <th class="px-4 py-3">Key</th>
                                        <th class="px-4 py-3">Producto</th>
                                        <th class="px-4 py-3 text-center">Consultas Totales</th>
                                        <th class="px-4 py-3 text-center">Tratos Realizados</th>
                                        <th class="px-4 py-3 text-center">Cancelados</th>
                                        <th class="px-4 py-3 text-right">Margen Protegido Avg</th>
                                        <th class="px-4 py-3 text-center">Conversión %</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <tr class="border-b text-sm">
                                        <td class="px-4 py-3 font-mono font-semibold text-indigo-700">PROD-001</td>
                                        <td class="px-4 py-3 font-medium">Audífonos Bluetooth Pro ANC</td>
                                        <td class="px-4 py-3 text-center">8</td>
                                        <td class="px-4 py-3 text-center font-bold text-emerald-600">2</td>
                                        <td class="px-4 py-3 text-center font-bold text-rose-600">1</td>
                                        <td class="px-4 py-3 text-right font-semibold text-gray-900">41.6%</td>
                                        <td class="px-4 py-3 text-center font-bold text-indigo-600">66.6%</td>
                                    </tr>
                                    <tr class="border-b text-sm">
                                        <td class="px-4 py-3 font-mono font-semibold text-indigo-700">PROD-002</td>
                                        <td class="px-4 py-3 font-medium">Reloj Inteligente Sport GPS</td>
                                        <td class="px-4 py-3 text-center">5</td>
                                        <td class="px-4 py-3 text-center font-bold text-emerald-600">2</td>
                                        <td class="px-4 py-3 text-center font-bold text-gray-400">0</td>
                                        <td class="px-4 py-3 text-right font-semibold text-gray-900">35.4%</td>
                                        <td class="px-4 py-3 text-center font-bold text-indigo-600">100.0%</td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>
                    </div>

                </div>

            </main>

            <!-- Script de Cambio de Pestañas e Interactividad de Filtros -->
            <script>
                function switchTab(tabName) {
                    const tabOps = document.getElementById('tab-ops');
                    const tabMetrics = document.getElementById('tab-metrics');
                    const btnOps = document.getElementById('btn-tab-ops');
                    const btnMetrics = document.getElementById('btn-tab-metrics');

                    if (tabName === 'ops') {
                        tabOps.classList.remove('hidden');
                        tabMetrics.classList.add('hidden');
                        btnOps.className = 'py-2.5 px-1 nav-tab-active flex items-center space-x-2 outline-none';
                        btnMetrics.className = 'py-2.5 px-1 nav-tab-inactive flex items-center space-x-2 outline-none';
                    } else {
                        tabOps.classList.add('hidden');
                        tabMetrics.classList.remove('hidden');
                        btnOps.className = 'py-2.5 px-1 nav-tab-inactive flex items-center space-x-2 outline-none';
                        btnMetrics.className = 'py-2.5 px-1 nav-tab-active flex items-center space-x-2 outline-none';
                    }
                }

                function filterChats(channel) {
                    const rows = document.querySelectorAll('.chat-row');
                    const btns = document.querySelectorAll('.filter-btn');

                    btns.forEach(btn => {
                        btn.className = 'px-3 py-1 bg-gray-100 text-gray-700 hover:bg-gray-200 rounded-full font-semibold outline-none filter-btn';
                    });

                    const activeBtn = document.getElementById('filter-' + channel);
                    if (activeBtn) {
                        activeBtn.className = 'px-3 py-1 bg-indigo-600 text-white rounded-full font-semibold outline-none filter-btn';
                    }

                    rows.forEach(row => {
                        if (channel === 'all' || row.getAttribute('data-channel') === channel) {
                            row.style.display = '';
                        } else {
                            row.style.display = 'none';
                        }
                    });
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    private fun escapeHtml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
