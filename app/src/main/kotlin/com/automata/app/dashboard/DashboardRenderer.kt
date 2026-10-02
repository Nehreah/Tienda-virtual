package com.automata.app.dashboard

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.ports.ChannelListingStatus

/**
 * Generador de HTML para el Dashboard Operacional Temprano (Plan Maestro v5.3 Sección 08).
 * Diseñado con sistema visual Navy/Violeta/Menta y componentes interactivos para HTMX.
 */
object DashboardRenderer {

    fun renderDashboardPage(
        products: List<CommercialProduct>,
        inventoryMap: Map<String, OperationalInventory>,
        channelStatuses: List<ChannelListingStatus>,
        alerts: List<String> = emptyList()
    ): String {
        val totalProducts = products.size
        val readyForShopify = channelStatuses.count { it.channel == "shopify" && it.isPublished }
        val readyForMeta = channelStatuses.count { it.channel == "meta_catalogo" && it.isPublished }

        val rowsHtml = products.joinToString("\n") { p ->
            val inv = inventoryMap[p.key.value]
            val stockVivo = inv?.stockVivo ?: 0
            val reservas = inv?.reservasActivas ?: 0
            val shopifyStatus = channelStatuses.find { it.productKey == p.key && it.channel == "shopify" }
            val metaStatus = channelStatuses.find { it.productKey == p.key && it.channel == "meta_catalogo" }

            """
            <tr class="hover:bg-gray-50 border-b transition">
                <td class="px-4 py-3 font-mono text-sm font-semibold text-indigo-700">${p.key.value}</td>
                <td class="px-4 py-3 font-medium text-gray-900">${escapeHtml(p.itemName)}</td>
                <td class="px-4 py-3 text-sm text-gray-600">${escapeHtml(p.brand)}</td>
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
                    ${if (shopifyStatus?.isPublished == true) "<span class='px-2 py-0.5 rounded bg-green-100 text-green-800'>ACTIVO</span>" else "<span class='px-2 py-0.5 rounded bg-yellow-100 text-yellow-800'>PENDIENTE</span>"}
                </td>
                <td class="px-4 py-3 text-center text-xs">
                    ${if (metaStatus?.isPublished == true) "<span class='px-2 py-0.5 rounded bg-purple-100 text-purple-800'>CATALOG_ONLY</span>" else "<span class='px-2 py-0.5 rounded bg-gray-100 text-gray-700'>SIN SYNC</span>"}
                </td>
            </tr>
            """.trimIndent()
        }

        return """
        <!DOCTYPE html>
        <html lang="es">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Autómata Comercial Multicanal v5.3</title>
            <script src="https://cdn.tailwindcss.com"></script>
            <script src="https://unpkg.com/htmx.org@1.9.10"></script>
            <style>
                body { background-color: #F5F6FC; font-family: system-ui, -apple-system, sans-serif; }
                .navy-header { background-color: #0B1020; }
                .violet-accent { color: #6C5CE7; }
                .bg-violet-accent { background-color: #6C5CE7; }
            </style>
        </head>
        <body class="text-gray-800">
            <!-- Header Navy -->
            <header class="navy-header text-white px-6 py-4 shadow-md flex justify-between items-center">
                <div class="flex items-center space-x-3">
                    <div class="w-8 h-8 rounded bg-violet-accent flex items-center justify-center font-bold text-white">A</div>
                    <div>
                        <h1 class="text-xl font-bold tracking-tight">Autómata Comercial Multicanal</h1>
                        <p class="text-xs text-indigo-300">v5.3 - Matriz Comercial Excel & PostgreSQL Operacional</p>
                    </div>
                </div>
                <div class="flex items-center space-x-3">
                    <span class="px-3 py-1 bg-emerald-900/60 text-emerald-300 border border-emerald-500/30 text-xs rounded-full flex items-center">
                        <span class="w-2 h-2 rounded-full bg-emerald-400 mr-2 animate-pulse"></span> Sistema Operativo
                    </span>
                </div>
            </header>

            <main class="max-w-7xl mx-auto px-6 py-8 space-y-8">

                <!-- Fila 1: Indicadores y Alertas -->
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

                <!-- Fila 2: Herramientas de Operación (Importador Excel & Simulador de Chat) -->
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

                <!-- Fila 3: Tabla de Catálogo & Sincronización Operacional -->
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
                                $rowsHtml
                            </tbody>
                        </table>
                    </div>
                </div>

            </main>
        </body>
        </html>
        """.trimIndent()
    }

    private fun escapeHtml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
