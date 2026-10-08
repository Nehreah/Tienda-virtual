package com.automata.app.dashboard

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.ports.ChannelListingStatus
import java.util.Locale

/**
 * Generador de HTML para el Dashboard Operacional v5.3.
 * Incluye:
 * 1. Monitoreo de APIs e Integraciones (Funcional, Degradado, Deshabilitado).
 * 2. Estado de Chats y Negociaciones por Canal/App (Trato Realizado, Cancelado, Pendiente de Envío, En Negociación).
 * 3. Pantalla / Sección de Métricas calculadas con los datos reales de [chatDeals].
 *
 * La interfaz es de una sola página con subpáginas internas (ruteo por hash, sin cambios en el backend):
 *   #/inicio · #/apis · #/chats · #/catalogo · #/metricas · #/automatizacion
 *
 * Soporta modo claro y oscuro (botón en la barra superior, preferencia guardada en el navegador).
 */
object DashboardRenderer {

    // htmx fijado a una versión exacta y verificado con SRI (Subresource Integrity).
    private const val HTMX_SRC = "https://unpkg.com/htmx.org@1.9.10"
    private const val HTMX_SRI = "sha384-D1Kt99CQMDuVetoL1lrYwg5t+9QdHe7NLX/SoJYkXDFfX37iInKRy5xLSi8nO7UC"

    /** Canales conocidos. Cualquier otro valor se agrupa como "simulador". */
    private val CHANNEL_LABELS = linkedMapOf(
        "whatsapp" to "WhatsApp",
        "instagram" to "Instagram DM",
        "messenger" to "Messenger",
        "shopify_webhooks" to "Shopify Order",
        "simulador" to "Simulador"
    )

    private const val ICON_HOME = """<path d="M3 11.5 12 4l9 7.5"/><path d="M5 10.5V20h14v-9.5"/>"""
    private const val ICON_API = """<path d="M13 2 4 14h7l-1 8 9-12h-7l1-8z"/>"""
    private const val ICON_CHAT = """<path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>"""
    private const val ICON_BOX = """<path d="M21 8 12 3 3 8v8l9 5 9-5V8z"/><path d="m3 8 9 5 9-5M12 13v8"/>"""
    private const val ICON_CHART = """<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>"""
    private const val ICON_SPARK = """<path d="m12 3 1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3z"/><path d="m19 16 .7 1.8 1.8.7-1.8.7L19 21l-.7-1.8-1.8-.7 1.8-.7L19 16z"/>"""
    private const val ICON_SUN = """<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>"""
    private const val ICON_MOON = """<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>"""
    private const val ICON_MENU = """<path d="M4 6h16M4 12h16M4 18h16"/>"""
    private const val ICON_INFO = """<circle cx="12" cy="12" r="9"/><path d="M12 8h.01M11 12h1v4h1"/>"""

    fun renderDashboardPage(
        products: List<CommercialProduct>,
        inventoryMap: Map<String, OperationalInventory>,
        channelStatuses: List<ChannelListingStatus>,
        apiIntegrations: List<ApiIntegrationStatus>,
        chatDeals: List<ChatDealLog>,
        csrfToken: String = ""
    ): String {
        val totalProducts = products.size
        val readyForShopify = channelStatuses.count { it.channel == "shopify" && it.isPublished }
        val readyForMeta = channelStatuses.count { it.channel == "meta_catalogo" && it.isPublished }

        // Conteo de tratos por estado
        val totalDeals = chatDeals.size
        val tratosCerrados = chatDeals.count { it.dealState == DealState.TRATO_CERRADO }
        val pendientesEnvio = chatDeals.count { it.dealState == DealState.PENDIENTE_ENVIO }
        val enNegociacion = chatDeals.count { it.dealState == DealState.EN_NEGOCIACION }
        val cancelados = chatDeals.count { it.dealState == DealState.CANCELADO }

        // Salud de integraciones
        val functionalApis = apiIntegrations.count { it.status == ApiStatus.FUNCIONAL }
        val degradedApis = apiIntegrations.count { it.status == ApiStatus.DEGRADADO }
        val disabledApis = apiIntegrations.count { it.status == ApiStatus.DESHABILITADO }

        // Estado global: se deriva de las integraciones reales (antes estaba fijo en "verde").
        val (globalClass, globalText) = when {
            apiIntegrations.isEmpty() -> "neutral" to "Sin integraciones registradas"
            disabledApis > 0 -> "bad" to "$disabledApis integración(es) deshabilitada(s)"
            degradedApis > 0 -> "warn" to "$degradedApis integración(es) degradada(s)"
            else -> "ok" to "Todas las integraciones operativas"
        }
        val heroHeadline = when (globalClass) {
            "ok" -> "¡Hola! Tu operación está en marcha. 🚀"
            "neutral" -> "Conecta tus canales para empezar."
            else -> "Hay integraciones que requieren atención."
        }

        // Gamification / onboarding progress: derived only from live dashboard state.
        val setupStepsCompleted = listOf(
            totalProducts > 0,
            readyForShopify > 0,
            readyForMeta > 0,
            functionalApis > 0
        ).count { it }
        val setupProgress = setupStepsCompleted * 25
        val operatorLevel = when {
            setupProgress >= 100 -> 5
            setupProgress >= 75 -> 4
            setupProgress >= 50 -> 3
            setupProgress >= 25 -> 2
            else -> 1
        }

        // Token CSRF: se incluye en formularios (campo oculto) y en las peticiones htmx (cabecera).
        val csrfInput = if (csrfToken.isNotBlank()) {
            """<input type="hidden" name="_csrf" value="${escapeHtml(csrfToken)}">"""
        } else ""

        // Filas para la tabla de Productos
        val productRowsHtml = products.joinToString("\n") { p ->
            val inv = inventoryMap[p.key.value]
            val stockVivo = inv?.stockVivo ?: 0
            val reservas = inv?.reservasActivas ?: 0
            val shopifyStatus = channelStatuses.find { it.productKey == p.key && it.channel == "shopify" }
            val metaStatus = channelStatuses.find { it.productKey == p.key && it.channel == "meta_catalogo" }

            val inactiveTag = if (!p.isActive) """<span class="badge neutral sm">Inactivo</span>""" else ""
            val categoryLine = if (p.category.isNotBlank() && p.category != "General") {
                """<span class="sub">${escapeHtml(p.category)}</span>"""
            } else ""
            val stockClass = if (stockVivo > 0) "ok" else "bad"
            val negativeTag = if (stockVivo < 0) """<span class="badge bad sm">⚠ Stock negativo</span>""" else ""
            val shopifyBadge = when {
                !p.isActive -> """<span class="badge neutral sm">No se publica</span>"""
                shopifyStatus?.isPublished == true -> """<span class="badge ok sm">Activo</span>"""
                else -> """<span class="badge warn sm">Pendiente</span>"""
            }
            val metaBadge = when {
                !p.isActive -> """<span class="badge neutral sm">No se publica</span>"""
                metaStatus?.isPublished == true -> """<span class="badge purple sm">CATALOG_ONLY</span>"""
                else -> """<span class="badge neutral sm">Sin sync</span>"""
            }

            """
            <tr>
                <td class="mono key">${escapeHtml(p.key.value)}</td>
                <td><span class="cell-main">${escapeHtml(p.itemName)}</span> $inactiveTag</td>
                <td><span class="cell-main">${escapeHtml(p.brand)}</span>$categoryLine</td>
                <td>${escapeHtml(p.condition)}</td>
                <td class="mono">${escapeHtml(p.location ?: "-")}</td>
                <td class="num strong">${p.publishedPrice.formatFormattedCop()}</td>
                <td class="center"><span class="badge info">${p.masterQuantity} maestro</span></td>
                <td class="center"><span class="badge $stockClass">$stockVivo vivo ($reservas res)</span> $negativeTag</td>
                <td class="center">$shopifyBadge</td>
                <td class="center">$metaBadge</td>
            </tr>
            """
        }.ifBlank { emptyRow(10, "Aún no hay productos cargados. Importa tu matriz comercial desde Automatización.") }

        // Cards de Estado de APIs
        val apiCardsHtml = apiIntegrations.joinToString("\n") { api ->
            val statusClass = when (api.status) {
                ApiStatus.FUNCIONAL -> "ok"
                ApiStatus.DEGRADADO -> "warn"
                ApiStatus.DESHABILITADO -> "bad"
            }
            val dotClass = if (api.status == ApiStatus.FUNCIONAL) "dot pulse" else "dot"

            """
            <article class="card api-card">
                <header>
                    <div>
                        <span class="muted small">${escapeHtml(api.category.toString())}</span>
                        <h3>${escapeHtml(api.name)}</h3>
                    </div>
                    <span class="badge $statusClass sm"><span class="$dotClass"></span>${api.status.name}</span>
                </header>
                <p>${escapeHtml(api.detail)}</p>
                <footer>
                    <span>Último check: ${escapeHtml(api.lastCheck.toString())}</span>
                    <span class="proto">HTTPS / JSON</span>
                </footer>
            </article>
            """
        }.ifBlank { """<div class="card card-pad empty">No hay integraciones registradas todavía.</div>""" }

        // Filas para la tabla de Chats y Tratos
        val chatDealRowsHtml = chatDeals.joinToString("\n") { chat ->
            val (badgeClass, badgeIcon) = when (chat.dealState) {
                DealState.TRATO_CERRADO -> "ok" to "✓"
                DealState.PENDIENTE_ENVIO -> "info" to "📦"
                DealState.EN_NEGOCIACION -> "warn" to "⏳"
                DealState.CANCELADO -> "bad" to "✕"
            }

            val key = channelKey(chat.channel)
            val channelBadge = """<span class="badge sm ch-$key">${CHANNEL_LABELS.getValue(key)}</span>"""

            // Fase 5.3: botón "Confirmar pago" para tratos en espera de confirmación manual.
            // El nombre del cliente va en un atributo data-* (escapado como HTML) y se lee con
            // dataset desde JS: nunca se concatena dentro de código JavaScript (evita XSS).
            val confirmForm = if (chat.dealState == DealState.TRATO_CERRADO) {
                """
                <form method="POST" action="/api/confirmar-pago" class="inline-form js-confirm-pago" data-customer="${escapeHtml(chat.customer)}">
                    <input type="hidden" name="dealId" value="${escapeHtml(chat.id)}">
                    $csrfInput
                    <button type="submit" class="btn btn-primary btn-sm">✔ Confirmar pago</button>
                </form>
                """
            } else ""

            """
            <tr class="chat-row" data-channel="$key">
                <td class="mono muted">${escapeHtml(chat.id)}</td>
                <td>$channelBadge</td>
                <td class="cell-main">${escapeHtml(chat.customer)}</td>
                <td>${escapeHtml(chat.productName)}</td>
                <td class="num strong">${escapeHtml(chat.offeredPriceFormatted)}</td>
                <td class="center">
                    <div class="deal-state">
                        <span class="badge $badgeClass">$badgeIcon ${escapeHtml(chat.dealStateLabel)}</span>
                        $confirmForm
                    </div>
                </td>
                <td class="num muted small">${escapeHtml(chat.timestamp)}</td>
            </tr>
            """
        }.ifBlank { emptyRow(7, "Todavía no hay conversaciones registradas.") }

        val channelChipsHtml = CHANNEL_LABELS.entries.joinToString("\n") { (k, label) ->
            """<button type="button" class="chip" data-filter="$k" aria-pressed="false">$label</button>"""
        }

        // ---------- Métricas calculadas con datos reales ----------
        val closeRate = pct(tratosCerrados, totalDeals)

        val channelStats = CHANNEL_LABELS.keys.map { k ->
            val deals = chatDeals.filter { channelKey(it.channel) == k }
            Triple(k, deals.size, deals.count { it.dealState == DealState.TRATO_CERRADO })
        }.filter { it.second > 0 }
        val maxChannel = channelStats.maxOfOrNull { it.second } ?: 1
        val channelBarsHtml = if (channelStats.isEmpty()) {
            """<div class="empty">Aún no hay conversaciones para graficar.</div>"""
        } else {
            channelStats.joinToString("\n") { (k, n, closed) ->
                """
                <div class="bar-row">
                    <div class="bar-label"><span>${CHANNEL_LABELS.getValue(k)}</span><span class="muted">$closed cerrados de <strong>$n</strong></span></div>
                    <div class="bar-track"><div class="bar-fill" style="width:${n * 100 / maxChannel}%"></div></div>
                </div>
                """
            }
        }

        val stateStats = listOf(
            Triple("Cerrados", tratosCerrados, "ok"),
            Triple("Pendientes de envío", pendientesEnvio, "info"),
            Triple("En negociación", enNegociacion, "warn"),
            Triple("Cancelados", cancelados, "bad")
        )
        val stateBarsHtml = if (totalDeals == 0) {
            """<div class="empty">Aún no hay conversaciones para graficar.</div>"""
        } else {
            stateStats.joinToString("\n") { (label, n, cls) ->
                """
                <div class="bar-row">
                    <div class="bar-label"><span>$label</span><span class="muted"><strong>$n</strong> · ${pct(n, totalDeals)}%</span></div>
                    <div class="bar-track"><div class="bar-fill fill-$cls" style="width:${n * 100 / totalDeals}%"></div></div>
                </div>
                """
            }
        }

        val productMetricsHtml = chatDeals
            .groupBy { it.productName }
            .entries
            .sortedByDescending { it.value.size }
            .joinToString("\n") { (name, deals) ->
                val total = deals.size
                val closed = deals.count { it.dealState == DealState.TRATO_CERRADO }
                val canceled = deals.count { it.dealState == DealState.CANCELADO }
                """
                <tr>
                    <td class="cell-main">${escapeHtml(name)}</td>
                    <td class="center">$total</td>
                    <td class="center"><span class="badge ok sm">$closed</span></td>
                    <td class="center"><span class="badge ${if (canceled > 0) "bad" else "neutral"} sm">$canceled</span></td>
                    <td class="center strong">${pct(closed, total)}%</td>
                </tr>
                """
            }.ifBlank { emptyRow(5, "Todavía no hay conversaciones para calcular métricas por producto.") }

        return """<!DOCTYPE html>
<html lang="es" data-theme="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="color-scheme" content="dark light">
    <meta name="csrf-token" content="${escapeHtml(csrfToken)}">
    <title>Tienda Virtual Multicanal · Commerce Control</title>
    <script>
        /* Aplica el tema antes de pintar para evitar el parpadeo claro/oscuro. */
        (function () {
            var t = null;
            try { t = localStorage.getItem('theme'); } catch (e) {}
            if (t !== 'light' && t !== 'dark') {
                t = (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) ? 'light' : 'dark';
            }
            document.documentElement.setAttribute('data-theme', t);
        })();
    </script>
    <script src="$HTMX_SRC" integrity="$HTMX_SRI" crossorigin="anonymous" defer></script>
    <style>
        /* ================= Tokens ================= */
        :root {
            color-scheme: dark;
            --font: Inter, "Segoe UI", system-ui, -apple-system, BlinkMacSystemFont, Roboto, "Helvetica Neue", Arial, sans-serif;
            --mono: "JetBrains Mono", "SFMono-Regular", ui-monospace, Consolas, monospace;
            --r: 16px;

            --bg: #070b1a;
            --side-bg: #0a1126;
            --topbar-bg: rgba(7, 11, 26, .78);
            --surface: #0f1730;
            --surface-2: #141e3d;
            --hover: rgba(117, 103, 255, .07);
            --border: rgba(133, 156, 255, .16);
            --text: #eef2ff;
            --text-2: #b0bbd6;
            --muted: #8190b0;
            --primary: #7567ff;
            --primary-2: #9b5cff;
            --primary-soft: rgba(117, 103, 255, .18);
            --primary-fg: #c4beff;
            --shadow: 0 10px 28px rgba(0, 0, 0, .28);

            --ok-bg: rgba(53, 224, 174, .12);   --ok-fg: #5eeac0;   --ok-bd: rgba(53, 224, 174, .30);
            --warn-bg: rgba(255, 200, 87, .12); --warn-fg: #ffd47a; --warn-bd: rgba(255, 200, 87, .30);
            --bad-bg: rgba(255, 102, 133, .12); --bad-fg: #ff8fa6;  --bad-bd: rgba(255, 102, 133, .32);
            --info-bg: rgba(56, 217, 255, .11); --info-fg: #6fe0ff; --info-bd: rgba(56, 217, 255, .28);
            --purple-bg: rgba(155, 92, 255, .15); --purple-fg: #c9aaff; --purple-bd: rgba(155, 92, 255, .32);
            --pink-bg: rgba(255, 85, 173, .12); --pink-fg: #ff8cc6; --pink-bd: rgba(255, 85, 173, .30);
            --neutral-bg: rgba(145, 160, 191, .12); --neutral-fg: #b0bbd6; --neutral-bd: rgba(145, 160, 191, .26);
        }

        :root[data-theme="light"] {
            color-scheme: light;
            --bg: #f3f5fb;
            --side-bg: #ffffff;
            --topbar-bg: rgba(243, 245, 251, .85);
            --surface: #ffffff;
            --surface-2: #f6f7fc;
            --hover: rgba(91, 75, 232, .06);
            --border: #e1e5f1;
            --text: #131a33;
            --text-2: #43506e;
            --muted: #66718f;
            --primary: #5b4be8;
            --primary-2: #8a4fe0;
            --primary-soft: #ece9ff;
            --primary-fg: #4a3bd0;
            --shadow: 0 6px 20px rgba(30, 40, 90, .07);

            --ok-bg: #e3f8ef;   --ok-fg: #0b7a57;   --ok-bd: #b4e9d1;
            --warn-bg: #fff4dc; --warn-fg: #8a5a00; --warn-bd: #f1d9a0;
            --bad-bg: #ffe8ed;  --bad-fg: #b4223f;  --bad-bd: #f5bfca;
            --info-bg: #e2f5fc; --info-fg: #0a6585; --info-bd: #b3e1f1;
            --purple-bg: #f0e8ff; --purple-fg: #6a35c4; --purple-bd: #d8c6f6;
            --pink-bg: #ffe8f3; --pink-fg: #b1226c; --pink-bd: #f6c0dc;
            --neutral-bg: #eef0f6; --neutral-fg: #43506e; --neutral-bd: #dce0ec;
        }

        /* ================= Base ================= */
        * { box-sizing: border-box; }
        html { scroll-behavior: smooth; }
        body {
            margin: 0;
            min-height: 100vh;
            background: var(--bg);
            color: var(--text);
            font: 14px/1.5 var(--font);
            -webkit-font-smoothing: antialiased;
        }
        [hidden] { display: none !important; }
        h1, h2, h3, h4, p { margin: 0; }
        a { color: inherit; text-decoration: none; }
        button { font: inherit; cursor: pointer; }
        :focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
        .muted { color: var(--muted); }
        .small { font-size: 12px; }
        .mono { font-family: var(--mono); font-size: 12px; }
        .strong { color: var(--text); font-weight: 600; }
        .ic { width: 18px; height: 18px; flex: 0 0 18px; }

        /* ================= Layout (grid, sin posiciones superpuestas) ================= */
        .app { display: grid; grid-template-columns: 248px minmax(0, 1fr); min-height: 100vh; }

        .sidebar {
            position: sticky; top: 0;
            height: 100vh;
            display: flex; flex-direction: column;
            padding: 20px 14px;
            background: var(--side-bg);
            border-right: 1px solid var(--border);
        }
        .brand { display: flex; align-items: center; gap: 12px; padding: 4px 8px 22px; }
        .brand-logo {
            width: 40px; height: 40px; flex: 0 0 40px;
            display: grid; place-items: center;
            border-radius: 12px; color: #fff; font-weight: 800; font-size: 18px;
            background: linear-gradient(135deg, #28d9ff 0%, #7157ff 55%, #a249ff 100%);
        }
        .brand-name { font-weight: 800; font-size: 15px; line-height: 1.2; }
        .brand-sub { color: var(--muted); font-size: 12px; }

        .nav { display: flex; flex-direction: column; gap: 4px; }
        .nav a {
            position: relative;
            display: flex; align-items: center; gap: 12px;
            height: 42px; padding: 0 12px;
            border-radius: 10px;
            color: var(--muted); font-weight: 500;
            transition: background .15s, color .15s;
        }
        .nav a:hover { background: var(--hover); color: var(--text); }
        .nav a[aria-current="page"] { background: var(--primary-soft); color: var(--text); font-weight: 600; }
        .nav a[aria-current="page"]::before {
            content: ""; position: absolute; left: -14px; top: 9px; bottom: 9px; width: 3px;
            border-radius: 0 3px 3px 0; background: var(--primary);
        }
        .nav a[aria-current="page"] .ic { color: var(--primary); }

        .side-foot {
            margin-top: auto; padding: 14px 8px 0;
            border-top: 1px solid var(--border);
            color: var(--muted); font-size: 12px; line-height: 1.6;
        }

        .main { min-width: 0; display: flex; flex-direction: column; }
        .topbar {
            position: sticky; top: 0; z-index: 30;
            display: flex; align-items: center; gap: 12px;
            height: 64px; padding: 0 28px;
            background: var(--topbar-bg);
            -webkit-backdrop-filter: blur(14px); backdrop-filter: blur(14px);
            border-bottom: 1px solid var(--border);
        }
        .topbar h1 { font-size: 18px; font-weight: 800; letter-spacing: -.01em; }
        .spacer { flex: 1; }
        .btn.menu-btn { display: none; }
        .scrim { display: none; }

        .content { width: 100%; max-width: 1400px; margin: 0 auto; padding: 28px; }
        .view { display: flex; flex-direction: column; gap: 22px; }

        /* ================= Componentes ================= */
        .card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--r); box-shadow: var(--shadow); }
        .card-pad { padding: 22px; }
        .card-head {
            display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap;
            padding: 18px 22px; border-bottom: 1px solid var(--border);
        }
        .card-head h3 { font-size: 16px; font-weight: 700; }
        .card-head p { color: var(--muted); font-size: 13px; margin-top: 2px; }

        .page-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; flex-wrap: wrap; }
        .page-head h2 { font-size: 22px; font-weight: 800; letter-spacing: -.015em; }
        .page-head p { color: var(--muted); margin-top: 2px; }

        .cols-2 { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(340px, 100%), 1fr)); gap: 20px; }
        .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(230px, 100%), 1fr)); gap: 16px; }
        .kpi { padding: 20px; }
        .kpi .label { color: var(--muted); font-size: 13px; font-weight: 600; }
        .kpi .value { font-size: 28px; font-weight: 800; letter-spacing: -.02em; line-height: 1.15; margin-top: 8px; }
        .kpi .value small { font-size: 14px; font-weight: 500; color: var(--muted); }
        .kpi .hint { color: var(--muted); font-size: 12.5px; margin-top: 8px; }
        .v-ok { color: var(--ok-fg); } .v-purple { color: var(--purple-fg); } .v-primary { color: var(--primary-fg); } .v-info { color: var(--info-fg); }

        /* Badges y estados */
        .ok      { --c-bg: var(--ok-bg);      --c-fg: var(--ok-fg);      --c-bd: var(--ok-bd); }
        .warn    { --c-bg: var(--warn-bg);    --c-fg: var(--warn-fg);    --c-bd: var(--warn-bd); }
        .bad     { --c-bg: var(--bad-bg);     --c-fg: var(--bad-fg);     --c-bd: var(--bad-bd); }
        .info    { --c-bg: var(--info-bg);    --c-fg: var(--info-fg);    --c-bd: var(--info-bd); }
        .purple  { --c-bg: var(--purple-bg);  --c-fg: var(--purple-fg);  --c-bd: var(--purple-bd); }
        .pink    { --c-bg: var(--pink-bg);    --c-fg: var(--pink-fg);    --c-bd: var(--pink-bd); }
        .neutral { --c-bg: var(--neutral-bg); --c-fg: var(--neutral-fg); --c-bd: var(--neutral-bd); }
        .ch-whatsapp, .ch-shopify_webhooks { --c-bg: var(--ok-bg); --c-fg: var(--ok-fg); --c-bd: var(--ok-bd); }
        .ch-instagram { --c-bg: var(--pink-bg); --c-fg: var(--pink-fg); --c-bd: var(--pink-bd); }
        .ch-messenger { --c-bg: var(--info-bg); --c-fg: var(--info-fg); --c-bd: var(--info-bd); }
        .ch-simulador { --c-bg: var(--purple-bg); --c-fg: var(--purple-fg); --c-bd: var(--purple-bd); }

        .badge {
            display: inline-flex; align-items: center; gap: 6px;
            padding: 4px 11px; border-radius: 999px;
            border: 1px solid var(--c-bd, var(--neutral-bd));
            background: var(--c-bg, var(--neutral-bg));
            color: var(--c-fg, var(--neutral-fg));
            font-size: 12.5px; font-weight: 600; line-height: 1.3; white-space: nowrap;
        }
        .badge.sm { padding: 2px 9px; font-size: 12px; }
        .dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
        .dot.pulse { animation: pulse 2s ease-in-out infinite; }
        @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: .35; } }

        /* Botones, chips e inputs */
        .btn {
            display: inline-flex; align-items: center; justify-content: center; gap: 8px;
            height: 40px; padding: 0 16px;
            border-radius: 10px; border: 1px solid var(--border);
            background: var(--surface-2); color: var(--text); font-weight: 600;
            transition: filter .15s, border-color .15s, background .15s;
        }
        .btn:hover { border-color: var(--primary); }
        .btn-primary { border-color: transparent; color: #fff; background: linear-gradient(135deg, var(--primary), var(--primary-2)); }
        .btn-primary:hover { filter: brightness(1.1); border-color: transparent; }
        .btn-ok { border-color: transparent; color: #fff; background: linear-gradient(135deg, #14a07a, #2fcf9f); }
        .btn-ok:hover { filter: brightness(1.08); border-color: transparent; }
        .btn-sm { height: 30px; padding: 0 12px; font-size: 12.5px; border-radius: 8px; }
        .btn-block { width: 100%; }
        .icon-btn { width: 40px; padding: 0; }

        .chips { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
        .chip {
            padding: 6px 14px; border-radius: 999px;
            border: 1px solid var(--border); background: var(--surface-2); color: var(--text-2);
            font-size: 13px; font-weight: 600;
            transition: border-color .15s, background .15s;
        }
        .chip:hover { border-color: var(--primary); }
        .chip.active { background: var(--primary); border-color: var(--primary); color: #fff; }

        .input {
            flex: 1; min-width: 0; height: 40px; padding: 0 12px;
            border-radius: 10px; border: 1px solid var(--border);
            background: var(--bg); color: var(--text); font: inherit;
        }
        .input::placeholder { color: var(--muted); }
        .input:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft); }
        .form-row { display: flex; gap: 10px; }
        .stack { display: flex; flex-direction: column; gap: 16px; }

        .dropzone { padding: 22px; text-align: center; border: 2px dashed var(--border); border-radius: 12px; background: var(--surface-2); }
        .dropzone input[type="file"] { width: 100%; color: var(--muted); font: inherit; }
        .dropzone input[type="file"]::file-selector-button {
            margin-right: 12px; padding: 8px 16px; border: 0; border-radius: 999px;
            background: var(--primary-soft); color: var(--primary-fg); font-weight: 600; cursor: pointer;
        }
        .dropzone p { margin-top: 10px; color: var(--muted); font-size: 13px; }
        .console {
            min-height: 120px; padding: 14px; border-radius: 12px;
            background: var(--bg); border: 1px solid var(--border);
            font-family: var(--mono); font-size: 13px; color: var(--text-2);
        }

        /* Tablas */
        .table-wrap { overflow-x: auto; }
        table { width: 100%; border-collapse: collapse; font-size: 13.5px; }
        th {
            padding: 12px 16px; text-align: left; white-space: nowrap;
            font-size: 12.5px; font-weight: 600; color: var(--muted);
            background: var(--surface-2); border-bottom: 1px solid var(--border);
        }
        td { padding: 13px 16px; color: var(--text-2); vertical-align: middle; border-bottom: 1px solid var(--border); }
        tbody tr:last-child td { border-bottom: 0; }
        tbody tr:hover { background: var(--hover); }
        .num { text-align: right; } .center { text-align: center; }
        td.key { color: var(--primary-fg); font-weight: 600; }
        .cell-main { color: var(--text); font-weight: 600; }
        .sub { display: block; color: var(--muted); font-size: 12px; }
        .deal-state { display: flex; align-items: center; justify-content: center; gap: 8px; flex-wrap: wrap; }
        .inline-form { display: inline; margin: 0; }
        .empty { padding: 28px; text-align: center; color: var(--muted); }
        td.empty { padding: 36px 16px; }

        /* Hero de inicio */
        .hero {
            display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(300px, 1fr); gap: 28px;
            padding: 28px; align-items: center;
        }
        .hero h2 { font-size: clamp(24px, 3vw, 32px); line-height: 1.15; letter-spacing: -.02em; font-weight: 800; }
        .hero p { color: var(--text-2); max-width: 56ch; margin-top: 10px; }
        .achievements { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 18px; }
        .level-card { padding: 18px; border-radius: 12px; background: var(--surface-2); border: 1px solid var(--border); }
        .level-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 12px; }
        .level-row .t1 { color: var(--muted); font-size: 13px; font-weight: 600; }
        .level-row .t2 { font-size: 18px; font-weight: 800; margin-top: 2px; }
        .progress-track, .bar-track { height: 10px; border-radius: 99px; overflow: hidden; background: var(--bg); border: 1px solid var(--border); }
        .progress-fill { height: 100%; border-radius: inherit; background: linear-gradient(90deg, #36d9ff, #7567ff, #b05aff); }
        .quest-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-top: 14px; }
        .quest { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 9px; background: var(--bg); color: var(--muted); font-size: 13px; }
        .quest-dot { width: 8px; height: 8px; flex: 0 0 8px; border-radius: 50%; background: var(--neutral-bd); }
        .quest.done { color: var(--ok-fg); background: var(--ok-bg); }
        .quest.done .quest-dot { background: var(--ok-fg); }

        .summary-card { display: flex; flex-direction: column; align-items: flex-start; }
        .summary-card .btn { margin-top: auto; }
        .summary-list { display: flex; flex-wrap: wrap; gap: 8px; margin: 14px 0 18px; }
        .card-pad > h3 { font-size: 16px; font-weight: 700; }
        .card-pad > p { color: var(--muted); font-size: 13px; margin-top: 2px; }

        /* APIs */
        .api-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(270px, 100%), 1fr)); gap: 16px; }
        .api-card { padding: 18px; display: flex; flex-direction: column; gap: 12px; }
        .api-card header { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }
        .api-card h3 { font-size: 15px; font-weight: 700; margin-top: 2px; }
        .api-card p { flex: 1; color: var(--text-2); font-size: 13px; }
        .api-card footer { display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap; padding-top: 10px; border-top: 1px solid var(--border); color: var(--muted); font-size: 12px; }
        .api-card .proto { color: var(--primary-fg); font-weight: 600; }

        /* Barras de métricas */
        .bar-row + .bar-row { margin-top: 18px; }
        .bar-label { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 6px; font-size: 13.5px; color: var(--text); }
        .bar-fill { height: 100%; border-radius: inherit; background: var(--primary); }
        .fill-ok { background: var(--ok-fg); } .fill-info { background: var(--info-fg); }
        .fill-warn { background: var(--warn-fg); } .fill-bad { background: var(--bad-fg); }
        .notice { display: flex; gap: 12px; align-items: flex-start; padding: 16px 18px; color: var(--text-2); }
        .notice .ic { color: var(--info-fg); margin-top: 2px; }

        /* ================= Responsive ================= */
        @media (max-width: 1000px) {
            .hero { grid-template-columns: 1fr; }
        }
        @media (max-width: 900px) {
            .app { grid-template-columns: minmax(0, 1fr); }
            .sidebar {
                position: fixed; top: 0; bottom: 0; left: 0; z-index: 50;
                width: 264px; height: auto;
                transform: translateX(-104%);
                transition: transform .22s ease;
            }
            body.nav-open .sidebar { transform: none; box-shadow: 18px 0 50px rgba(0, 0, 0, .35); }
            body.nav-open .scrim { display: block; position: fixed; inset: 0; z-index: 40; background: rgba(0, 0, 0, .5); }
            .btn.menu-btn { display: inline-flex; }
            .topbar { padding: 0 16px; }
            .content { padding: 20px 14px 56px; }
        }
        @media (max-width: 640px) {
            .status-text { display: none; }
            .hero { padding: 20px; }
        }
        @media (max-width: 480px) {
            .quest-list { grid-template-columns: 1fr; }
            .form-row { flex-direction: column; }
        }

        /* Botón de tema: muestra el sol en oscuro y la luna en claro */
        :root[data-theme="dark"] .ic-moon { display: none; }
        :root[data-theme="light"] .ic-sun { display: none; }

        @media (prefers-reduced-motion: reduce) {
            *, *::before, *::after { animation: none !important; transition: none !important; scroll-behavior: auto !important; }
        }

        /* =========================================================
           PREMIUM VISUAL REFRESH · inspirado en el diseño de referencia
           ========================================================= */
        :root {
            --glow-purple: rgba(117, 103, 255, .28);
            --glow-blue: rgba(56, 217, 255, .20);
            --glow-pink: rgba(255, 85, 173, .18);
        }

        body {
            background:
                radial-gradient(circle at 88% 3%, rgba(117,103,255,.085), transparent 25%),
                radial-gradient(circle at 12% 95%, rgba(56,217,255,.045), transparent 24%),
                var(--bg);
        }

        .sidebar {
            background:
                radial-gradient(circle at 20% 4%, rgba(117,103,255,.09), transparent 25%),
                var(--side-bg);
        }

        .brand-logo {
            box-shadow: 0 8px 28px rgba(117,103,255,.25);
        }

        .nav a {
            border: 1px solid transparent;
            transition: background .18s ease, color .18s ease, border-color .18s ease, transform .18s ease;
        }

        .nav a:hover {
            border-color: rgba(117,103,255,.10);
            transform: translateX(1px);
        }

        .nav a[aria-current="page"] {
            border-color: rgba(117,103,255,.15);
            box-shadow: 0 6px 22px rgba(117,103,255,.07);
        }

        .nav a[aria-current="page"]::before {
            box-shadow: 0 0 12px rgba(117,103,255,.35);
        }

        .nav-icon {
            width: 28px;
            height: 28px;
            flex: 0 0 28px;
            display: grid;
            place-items: center;
            border-radius: 9px;
            color: var(--muted);
            background: rgba(117,103,255,.055);
            border: 1px solid rgba(117,103,255,.08);
            transition: color .18s ease, background .18s ease, transform .18s ease, box-shadow .18s ease;
        }

        .nav-icon .ic {
            width: 16px;
            height: 16px;
        }

        .nav a:hover .nav-icon {
            color: var(--primary-fg);
            background: rgba(117,103,255,.12);
            transform: scale(1.04);
        }

        .nav a[aria-current="page"] .nav-icon {
            color: #fff;
            background: linear-gradient(135deg, var(--primary), var(--primary-2));
            border-color: transparent;
            box-shadow: 0 5px 16px rgba(117,103,255,.24);
        }

        .topbar {
            background:
                linear-gradient(90deg, rgba(7,11,26,.92), rgba(10,13,35,.72));
            border-bottom-color: rgba(133,156,255,.12);
        }

        :root[data-theme="light"] .topbar {
            background: rgba(243,245,251,.86);
        }

        .card {
            position: relative;
            overflow: hidden;
            background:
                linear-gradient(145deg, rgba(255,255,255,.035), rgba(255,255,255,.012)),
                var(--surface);
            border-color: rgba(145,155,255,.17);
            box-shadow: 0 10px 35px rgba(0,0,0,.20), inset 0 1px 0 rgba(255,255,255,.025);
            transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease;
        }

        .card:hover {
            border-color: rgba(145,155,255,.28);
            box-shadow: 0 15px 45px rgba(0,0,0,.25), 0 0 30px rgba(117,103,255,.045);
        }

        :root[data-theme="light"] .card {
            background: linear-gradient(145deg, rgba(255,255,255,1), rgba(249,250,255,.96));
            border-color: var(--border);
            box-shadow: var(--shadow);
        }

        .kpis {
            grid-template-columns: repeat(4, minmax(0, 1fr));
            gap: 12px;
        }

        .kpi {
            min-height: 132px;
            padding: 18px 20px;
            isolation: isolate;
        }

        .kpi::after {
            content: "";
            position: absolute;
            width: 112px;
            height: 112px;
            right: -42px;
            bottom: -52px;
            border-radius: 50%;
            background: radial-gradient(circle, rgba(117,103,255,.20) 0%, rgba(117,103,255,.08) 45%, transparent 72%);
            border: 1px solid rgba(117,103,255,.10);
            z-index: -1;
        }

        .kpi::before {
            content: "";
            position: absolute;
            width: 76px;
            height: 76px;
            right: -27px;
            bottom: -36px;
            border-radius: 50%;
            border: 1px solid rgba(117,103,255,.09);
            z-index: -1;
        }

        .kpi:nth-child(2)::after {
            background: radial-gradient(circle, rgba(53,224,174,.18), rgba(53,224,174,.04) 64%, transparent 70%);
            border-color: rgba(53,224,174,.09);
        }

        .kpi:nth-child(3)::after {
            background: radial-gradient(circle, rgba(155,92,255,.22), rgba(155,92,255,.04) 64%, transparent 70%);
            border-color: rgba(155,92,255,.10);
        }

        .kpi:nth-child(4)::after {
            background: radial-gradient(circle, rgba(56,217,255,.17), rgba(56,217,255,.03) 64%, transparent 70%);
            border-color: rgba(56,217,255,.09);
        }

        .kpi .label {
            text-transform: uppercase;
            letter-spacing: .075em;
            font-size: 10px;
            font-weight: 700;
        }

        .kpi .value {
            font-size: 25px;
            letter-spacing: -.035em;
        }

        .kpi .hint {
            font-size: 11px;
        }

        .hero {
            position: relative;
            overflow: hidden;
            background:
                radial-gradient(circle at 90% 18%, rgba(117,103,255,.15), transparent 29%),
                radial-gradient(circle at 75% 100%, rgba(155,92,255,.09), transparent 30%),
                var(--surface);
            border-color: rgba(117,103,255,.20);
        }

        .hero::after {
            content: "";
            position: absolute;
            width: 230px;
            height: 230px;
            right: -100px;
            top: -110px;
            border-radius: 50%;
            border: 1px solid rgba(117,103,255,.12);
            box-shadow: 0 0 0 25px rgba(117,103,255,.025), 0 0 0 50px rgba(117,103,255,.018);
            pointer-events: none;
        }

        .hero h2 {
            letter-spacing: -.035em;
        }

        .api-card {
            min-height: 155px;
            background:
                linear-gradient(145deg, rgba(117,103,255,.035), rgba(255,255,255,.012)),
                var(--surface);
        }

        .api-card::after {
            content: "";
            position: absolute;
            width: 100px;
            height: 100px;
            right: -38px;
            top: -43px;
            border-radius: 50%;
            background: radial-gradient(circle, rgba(117,103,255,.16), transparent 68%);
            border: 1px solid rgba(117,103,255,.10);
            pointer-events: none;
        }

        .api-card header, .api-card p, .api-card footer {
            position: relative;
            z-index: 1;
        }

        .api-card h3 {
            font-size: 14px;
            letter-spacing: -.01em;
        }

        .api-card .muted.small {
            text-transform: uppercase;
            letter-spacing: .07em;
            font-size: 9px;
            font-weight: 700;
        }

        .badge {
            box-shadow: inset 0 1px 0 rgba(255,255,255,.035);
        }

        .badge.ok { box-shadow: 0 0 14px rgba(53,224,174,.045); }
        .badge.purple { box-shadow: 0 0 14px rgba(155,92,255,.06); }

        .summary-card {
            background:
                radial-gradient(circle at 92% 8%, rgba(117,103,255,.08), transparent 26%),
                var(--surface);
        }

        .level-card {
            box-shadow: 0 12px 32px rgba(0,0,0,.16), inset 0 1px 0 rgba(255,255,255,.025);
            border-color: rgba(117,103,255,.13);
            background: rgba(255,255,255,.025);
            backdrop-filter: blur(8px);
        }

        .progress-track {
            background: rgba(255,255,255,.06);
            box-shadow: inset 0 1px 3px rgba(0,0,0,.18);
        }

        .progress-fill {
            background: linear-gradient(90deg, var(--primary), #a45cff, #38d9ff);
            box-shadow: 0 0 14px rgba(117,103,255,.25);
        }

        table tbody tr {
            transition: background .15s ease;
        }

        table tbody tr:hover {
            background: linear-gradient(90deg, rgba(117,103,255,.05), transparent);
        }

        th {
            text-transform: uppercase;
            letter-spacing: .045em;
            font-size: 10px;
        }

        .btn-primary {
            box-shadow: 0 6px 18px rgba(117,103,255,.16);
        }

        .btn-primary:hover {
            box-shadow: 0 9px 24px rgba(117,103,255,.24);
        }

        @media (max-width: 1100px) {
            .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
        }

        @media (max-width: 640px) {
            .kpis { grid-template-columns: 1fr; }
            .kpi { min-height: 120px; }
        }

    </style>
</head>
<body>
<div class="app">

    <!-- ============== Barra lateral ============== -->
    <aside class="sidebar" id="sidebar">
        <div class="brand">
            <div class="brand-logo">A</div>
            <div>
                <div class="brand-name">Automata</div>
                <div class="brand-sub">Commerce Control · v5.3</div>
            </div>
        </div>
        <nav class="nav" aria-label="Navegación principal">
            <a href="#/inicio" data-nav="inicio"><span class="nav-icon">${icon(ICON_HOME)}</span><span>Inicio</span></a>
            <a href="#/apis" data-nav="apis"><span class="nav-icon">${icon(ICON_API)}</span><span>APIs y conectores</span></a>
            <a href="#/chats" data-nav="chats"><span class="nav-icon">${icon(ICON_CHAT)}</span><span>Mensajería</span></a>
            <a href="#/catalogo" data-nav="catalogo"><span class="nav-icon">${icon(ICON_BOX)}</span><span>Catálogo</span></a>
            <a href="#/metricas" data-nav="metricas"><span class="nav-icon">${icon(ICON_CHART)}</span><span>Métricas</span></a>
            <a href="#/automatizacion" data-nav="automatizacion"><span class="nav-icon">${icon(ICON_SPARK)}</span><span>Automatización</span></a>
        </nav>
        <div class="side-foot">Más canales, más clientes, más ventas.</div>
    </aside>
    <div class="scrim" id="scrim"></div>

    <div class="main">
        <!-- ============== Barra superior ============== -->
        <header class="topbar">
            <button type="button" class="btn icon-btn menu-btn" id="menu-btn" aria-label="Abrir menú">${icon(ICON_MENU)}</button>
            <h1 id="page-title">Inicio</h1>
            <div class="spacer"></div>
            <span class="badge $globalClass" title="Estado de las integraciones">
                <span class="dot ${if (globalClass == "ok") "pulse" else ""}"></span>
                <span class="status-text">$globalText</span>
            </span>
            <button type="button" class="btn icon-btn" id="theme-toggle" aria-label="Cambiar entre modo claro y oscuro" title="Cambiar entre modo claro y oscuro">
                ${icon(ICON_SUN, "ic ic-sun")}${icon(ICON_MOON, "ic ic-moon")}
            </button>
        </header>

        <main class="content">

            <!-- ==================== SUBPÁGINA: INICIO ==================== -->
            <section class="view" data-view="inicio">
                <div class="card hero">
                    <div>
                        <h2>$heroHeadline</h2>
                        <p>Conecta tus canales, completa tus misiones y convierte el panel en el centro de mando de tu tienda multicanal.</p>
                        <div class="achievements">
                            <span class="badge neutral">⚡ $functionalApis APIs saludables</span>
                            <span class="badge neutral">💬 $totalDeals conversaciones</span>
                            <span class="badge neutral">🏆 Nivel $operatorLevel</span>
                        </div>
                    </div>
                    <div class="level-card">
                        <div class="level-row">
                            <div>
                                <div class="t1">Progreso de configuración</div>
                                <div class="t2">$setupProgress% completado</div>
                            </div>
                            <span class="badge purple sm">Nivel $operatorLevel / 5</span>
                        </div>
                        <div class="progress-track"><div class="progress-fill" style="width:$setupProgress%"></div></div>
                        <div class="quest-list">
                            <div class="quest ${if (totalProducts > 0) "done" else ""}"><span class="quest-dot"></span> Catálogo cargado</div>
                            <div class="quest ${if (readyForShopify > 0) "done" else ""}"><span class="quest-dot"></span> Productos en Shopify</div>
                            <div class="quest ${if (readyForMeta > 0) "done" else ""}"><span class="quest-dot"></span> Productos en Meta</div>
                            <div class="quest ${if (functionalApis > 0) "done" else ""}"><span class="quest-dot"></span> APIs funcionales</div>
                        </div>
                    </div>
                </div>

                <div class="kpis">
                    <div class="card kpi">
                        <p class="label">Catálogo comercial</p>
                        <p class="value">$totalProducts <small>ítems</small></p>
                        <p class="hint">Fuente de verdad: Excel v2</p>
                    </div>
                    <div class="card kpi">
                        <p class="label">Shopify</p>
                        <p class="value v-ok">$readyForShopify / $totalProducts</p>
                        <p class="hint">Productos publicados en el canal</p>
                    </div>
                    <div class="card kpi">
                        <p class="label">Meta Shop (Colombia)</p>
                        <p class="value v-purple">$readyForMeta / $totalProducts</p>
                        <p class="hint">Modo: CATALOG_ONLY</p>
                    </div>
                    <div class="card kpi">
                        <p class="label">Negociación L0–L4</p>
                        <p class="value v-primary">Protección PM</p>
                        <p class="hint">Invarianza: Precio ≥ L4 ≥ PM activa</p>
                    </div>
                </div>

                <div class="cols-2">
                    <div class="card card-pad summary-card">
                        <h3>Estado de integraciones</h3>
                        <p>${apiIntegrations.size} servicios instalados</p>
                        <div class="summary-list">
                            <span class="badge ok">Funcionales: $functionalApis</span>
                            <span class="badge warn">Degradadas: $degradedApis</span>
                            <span class="badge bad">Deshabilitadas: $disabledApis</span>
                        </div>
                        <a class="btn btn-primary" href="#/apis">Ver integraciones</a>
                    </div>
                    <div class="card card-pad summary-card">
                        <h3>Resumen de tratos</h3>
                        <p>$totalDeals conversaciones registradas</p>
                        <div class="summary-list">
                            <span class="badge ok">✓ Realizados: $tratosCerrados</span>
                            <span class="badge info">📦 Pendientes de envío: $pendientesEnvio</span>
                            <span class="badge warn">⏳ En negociación: $enNegociacion</span>
                            <span class="badge bad">✕ Cancelados: $cancelados</span>
                        </div>
                        <a class="btn btn-primary" href="#/chats">Ver mensajería</a>
                    </div>
                </div>
            </section>

            <!-- ==================== SUBPÁGINA: APIS ==================== -->
            <section class="view" data-view="apis" hidden>
                <div class="page-head">
                    <div>
                        <h2>Estado de APIs y aplicaciones conectadas</h2>
                        <p>Disponibilidad, tokens y adaptadores instalados.</p>
                    </div>
                    <div class="chips">
                        <span class="badge neutral">${apiIntegrations.size} servicios instalados</span>
                        <span class="badge ok">Funcionales: $functionalApis</span>
                        <span class="badge warn">Degradadas: $degradedApis</span>
                        <span class="badge bad">Deshabilitadas: $disabledApis</span>
                    </div>
                </div>
                <div class="api-grid">
                    $apiCardsHtml
                </div>
                <div class="card card-pad stack" style="margin-top:20px;">
                    <div class="form-row" style="justify-content:space-between;align-items:flex-start">
                        <div>
                            <h3>Configuración de Webhook de Meta (WhatsApp / Instagram / Messenger)</h3>
                            <p>Copia estos datos en el panel de Meta for Developers → Webhooks / WhatsApp.</p>
                        </div>
                        <span class="badge info sm">URL Autodetectada</span>
                    </div>
                    <div class="cols-2">
                        <div class="stack" style="gap:8px;">
                            <label style="font-size:12.5px;font-weight:600;color:var(--muted)">Callback URL (URL de retorno):</label>
                            <div class="form-row">
                                <input type="text" id="meta-callback-url-input" class="input" readonly style="font-family:var(--mono);font-size:12.5px;">
                                <button type="button" class="btn btn-sm btn-primary" onclick="navigator.clipboard.writeText(document.getElementById('meta-callback-url-input').value);alert('Callback URL copiada al portapapeles');">Copiar URL</button>
                            </div>
                            <p style="font-size:12px;color:var(--muted)">Calculada dinámicamente según el host actual (local, ngrok o servidor de producción).</p>
                        </div>
                        <div class="stack" style="gap:8px;">
                            <label style="font-size:12.5px;font-weight:600;color:var(--muted)">Ruta del Endpoint:</label>
                            <input type="text" value="/webhooks/meta" class="input" readonly style="font-family:var(--mono);font-size:12.5px;">
                            <p style="font-size:12px;color:var(--muted)">Verify Token: configurado en META_WEBHOOK_VERIFY_TOKEN de tu local.properties.</p>
                        </div>
                    </div>
                </div>

                <div class="card card-pad stack" style="margin-top:20px;">
                    <div class="form-row" style="justify-content:space-between;align-items:flex-start">
                        <div>
                            <h3>Diagnóstico Operacional y Auditoría de Sincronización</h3>
                            <p>Monitorea la coherencia entre el catálogo local y las plataformas conectadas.</p>
                        </div>
                        <span class="badge purple sm">Auditoría en Vivo</span>
                    </div>
                    <div class="cols-2">
                        <div class="stack" style="gap:8px;">
                            <label style="font-size:12.5px;font-weight:600;color:var(--muted)">Deriva de Stock en Canales:</label>
                            <button type="button" class="btn btn-sm btn-primary" hx-get="/api/deriva/reporte" hx-target="#diag-results">Auditar Deriva de Stock</button>
                        </div>
                        <div class="stack" style="gap:8px;">
                            <label style="font-size:12.5px;font-weight:600;color:var(--muted)">Eventos de Webhooks No Resueltos:</label>
                            <button type="button" class="btn btn-sm btn-ok" hx-get="/api/eventos-sin-resolver" hx-target="#diag-results">Ver Eventos Pendientes</button>
                        </div>
                    </div>
                    <div id="diag-results" class="console" style="margin-top:10px;" aria-live="polite">
                        <span class="muted">Haz clic en un botón superior para auditar discrepancias de inventario o webhooks retenidos...</span>
                    </div>
                </div>
            </section>

            <!-- ==================== SUBPÁGINA: MENSAJERÍA ==================== -->
            <section class="view" data-view="chats" hidden>
                <div class="page-head">
                    <div>
                        <h2>Chats y conversaciones por canal</h2>
                        <p>Tratos realizados, pendientes de envío, negociaciones activas y cancelaciones.</p>
                    </div>
                    <div class="chips">
                        <span class="badge ok">✓ Realizados: $tratosCerrados</span>
                        <span class="badge info">📦 Pendientes de envío: $pendientesEnvio</span>
                        <span class="badge warn">⏳ En negociación: $enNegociacion</span>
                        <span class="badge bad">✕ Cancelados: $cancelados</span>
                    </div>
                </div>

                <div class="card">
                    <div class="card-head">
                        <div class="chips" role="group" aria-label="Filtrar por canal">
                            <span class="muted">Canal:</span>
                            <button type="button" class="chip active" data-filter="all" aria-pressed="true">Todos</button>
                            $channelChipsHtml
                        </div>
                    </div>
                    <div class="table-wrap">
                        <table>
                            <thead>
                                <tr>
                                    <th>ID chat</th>
                                    <th>Canal / app</th>
                                    <th>Cliente</th>
                                    <th>Producto</th>
                                    <th class="num">Oferta / monto</th>
                                    <th class="center">Estado del trato</th>
                                    <th class="num">Tiempo</th>
                                </tr>
                            </thead>
                            <tbody>
                                $chatDealRowsHtml
                            </tbody>
                        </table>
                    </div>
                </div>
            </section>

            <!-- ==================== SUBPÁGINA: CATÁLOGO ==================== -->
            <section class="view" data-view="catalogo" hidden>
                <div class="page-head">
                    <div>
                        <h2>Catálogo y estado operacional multicanal</h2>
                        <p>Stock maestro frente a stock vivo, y publicación en cada canal.</p>
                    </div>
                    <div class="chips">
                        <span class="badge neutral">$totalProducts ítems</span>
                        <a href="/api/excel/export" class="btn btn-sm btn-primary" title="Descargar inventario completo en formato Excel .xlsx">⬇ Exportar Excel</a>
                        <button type="button" class="btn btn-sm btn-ok" hx-post="/api/reconciliacion/ejecutar" hx-target="#reconcile-results" title="Comparar catálogo local contra Shopify y Meta Catalog">🔄 Reconciliar Canales</button>
                    </div>
                </div>
                <div id="reconcile-results" aria-live="polite"></div>
                <div class="card">
                    <div class="table-wrap">
                        <table style="min-width:1080px">
                            <thead>
                                <tr>
                                    <th>Key</th>
                                    <th>Producto</th>
                                    <th>Marca / tipo</th>
                                    <th>Condición</th>
                                    <th>Ubicación</th>
                                    <th class="num">Precio pub. (PP)</th>
                                    <th class="center">Stock maestro</th>
                                    <th class="center">Stock vivo BD</th>
                                    <th class="center">Shopify</th>
                                    <th class="center">Meta Shop</th>
                                </tr>
                            </thead>
                            <tbody>
                                $productRowsHtml
                            </tbody>
                        </table>
                    </div>
                </div>
            </section>

            <!-- ==================== SUBPÁGINA: MÉTRICAS ==================== -->
            <section class="view" data-view="metricas" hidden>
                <div class="page-head">
                    <div>
                        <h2>Métricas y estadísticas</h2>
                        <p>Calculadas con las conversaciones registradas en este momento.</p>
                    </div>
                </div>

                <div class="kpis">
                    <div class="card kpi">
                        <p class="label">Conversaciones</p>
                        <p class="value">$totalDeals</p>
                        <p class="hint">En todos los canales</p>
                    </div>
                    <div class="card kpi">
                        <p class="label">Tratos realizados</p>
                        <p class="value v-ok">$tratosCerrados</p>
                        <p class="hint">$pendientesEnvio pendientes de envío</p>
                    </div>
                    <div class="card kpi">
                        <p class="label">Tasa de cierre</p>
                        <p class="value v-primary">$closeRate%</p>
                        <p class="hint">$tratosCerrados de $totalDeals conversaciones</p>
                    </div>
                    <div class="card kpi">
                        <p class="label">En negociación</p>
                        <p class="value v-info">$enNegociacion</p>
                        <p class="hint">$cancelados cancelados</p>
                    </div>
                </div>

                <div class="card notice">
                    ${icon(ICON_INFO)}
                    <p><strong>Métricas pendientes de definir:</strong> ventas brutas en COP, margen protegido y tiempo promedio de cierre. Todavía no hay una fuente de datos para calcularlas, por eso no se muestran valores de ejemplo.</p>
                </div>

                <div class="cols-2">
                    <div class="card card-pad">
                        <h3>Tratos por canal</h3>
                        <p>Conversaciones y cierres en cada aplicación.</p>
                        <div style="margin-top:18px">$channelBarsHtml</div>
                    </div>
                    <div class="card card-pad">
                        <h3>Distribución por estado</h3>
                        <p>Cómo se reparten las conversaciones.</p>
                        <div style="margin-top:18px">$stateBarsHtml</div>
                    </div>
                </div>

                <div class="card">
                    <div class="card-head">
                        <div>
                            <h3>Rendimiento por producto</h3>
                            <p>Consultas, cierres y cancelaciones.</p>
                        </div>
                    </div>
                    <div class="table-wrap">
                        <table>
                            <thead>
                                <tr>
                                    <th>Producto</th>
                                    <th class="center">Consultas totales</th>
                                    <th class="center">Tratos realizados</th>
                                    <th class="center">Cancelados</th>
                                    <th class="center">Conversión</th>
                                </tr>
                            </thead>
                            <tbody>
                                $productMetricsHtml
                            </tbody>
                        </table>
                    </div>
                </div>
            </section>

            <!-- ==================== SUBPÁGINA: AUTOMATIZACIÓN ==================== -->
            <section class="view" data-view="automatizacion" hidden>
                <div class="page-head">
                    <div>
                        <h2>Automatización</h2>
                        <p>Importa tu matriz comercial y prueba la escalera de negociación.</p>
                    </div>
                </div>

                <div class="cols-2">
                    <div class="card card-pad stack">
                        <div>
                            <h3>Importación de matriz comercial (Excel v2)</h3>
                            <p>3 hojas: Inventario, Publicacion, Canales.</p>
                        </div>
                        <form action="/api/excel/upload" method="post" enctype="multipart/form-data" class="stack">
                            $csrfInput
                            <div class="dropzone">
                                <input type="file" name="file" accept=".xlsx" aria-label="Archivo Excel comercial">
                                <p>Carga un archivo .xlsx comercial (ej. NEGOCIO.xlsx).</p>
                            </div>
                            <button type="submit" class="btn btn-primary btn-block">Cargar y sincronizar operación</button>
                        </form>
                    </div>

                    <div id="simulator-section" class="card card-pad stack">
                        <div class="form-row" style="justify-content:space-between;align-items:flex-start">
                            <div>
                                <h3>Simulador de negociación L0–L4</h3>
                                <p>Prueba ofertas contra la protección PM.</p>
                            </div>
                            <span class="badge ok sm">Motor en vivo</span>
                        </div>
                        <div id="sim-results" class="console" aria-live="polite">
                            <span class="muted">Escribe una oferta de cliente para probar la escalera de negociación y la protección PM…</span>
                        </div>
                        <form hx-post="/api/sim/chat" hx-target="#sim-results" class="form-row">
                            <input type="text" name="message" class="input" placeholder="Ej: Te ofrezco 95000 por los audífonos PROD-001" aria-label="Mensaje de prueba">
                            <button type="submit" class="btn btn-ok">Probar oferta</button>
                        </form>
                    </div>
                </div>
            </section>

        </main>
    </div>
</div>

<script>
    (function () {
        'use strict';

        /* ---------- Subpáginas (ruteo por hash) ---------- */
        var VIEWS = ['inicio', 'apis', 'chats', 'catalogo', 'metricas', 'automatizacion'];
        var TITLES = {
            inicio: 'Inicio',
            apis: 'APIs y conectores',
            chats: 'Mensajería',
            catalogo: 'Catálogo',
            metricas: 'Métricas',
            automatizacion: 'Automatización'
        };

        function currentView() {
            var h = (location.hash || '').replace(/^#\/?/, '');
            return VIEWS.indexOf(h) >= 0 ? h : 'inicio';
        }

        function showView() {
            var v = currentView();
            document.querySelectorAll('.view').forEach(function (s) {
                s.hidden = s.getAttribute('data-view') !== v;
            });
            document.querySelectorAll('[data-nav]').forEach(function (a) {
                if (a.getAttribute('data-nav') === v) { a.setAttribute('aria-current', 'page'); }
                else { a.removeAttribute('aria-current'); }
            });
            document.getElementById('page-title').textContent = TITLES[v];
            document.title = TITLES[v] + ' · Commerce Control';
            document.body.classList.remove('nav-open');
            window.scrollTo(0, 0);
        }
        window.addEventListener('hashchange', showView);
        showView();

        /* ---------- Autodetección Callback URL de Meta ---------- */
        var cbInput = document.getElementById('meta-callback-url-input');
        if (cbInput) { cbInput.value = window.location.origin + '/webhooks/meta'; }

        /* ---------- Menú móvil ---------- */
        document.getElementById('menu-btn').addEventListener('click', function () {
            document.body.classList.toggle('nav-open');
        });
        document.getElementById('scrim').addEventListener('click', function () {
            document.body.classList.remove('nav-open');
        });

        /* ---------- Modo claro / oscuro ---------- */
        document.getElementById('theme-toggle').addEventListener('click', function () {
            var root = document.documentElement;
            var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
            root.setAttribute('data-theme', next);
            try { localStorage.setItem('theme', next); } catch (e) {}
        });

        /* ---------- Filtro de chats por canal ---------- */
        document.addEventListener('click', function (e) {
            var chip = e.target.closest ? e.target.closest('.chip[data-filter]') : null;
            if (!chip) { return; }
            var ch = chip.getAttribute('data-filter');
            document.querySelectorAll('.chip[data-filter]').forEach(function (b) {
                var on = b.getAttribute('data-filter') === ch;
                b.classList.toggle('active', on);
                b.setAttribute('aria-pressed', on ? 'true' : 'false');
            });
            document.querySelectorAll('.chat-row').forEach(function (r) {
                r.hidden = !(ch === 'all' || r.getAttribute('data-channel') === ch);
            });
        });

        /* ---------- Confirmar pago (el nombre se lee del atributo data-*, nunca se evalúa como JS) ---------- */
        document.addEventListener('submit', function (e) {
            var f = e.target;
            if (f && f.classList && f.classList.contains('js-confirm-pago')) {
                var who = f.getAttribute('data-customer') || 'este cliente';
                if (!window.confirm('¿Confirmar pago para ' + who + '?')) { e.preventDefault(); }
            }
        });

        /* ---------- CSRF para peticiones htmx ---------- */
        var meta = document.querySelector('meta[name="csrf-token"]');
        var csrf = meta ? meta.getAttribute('content') : '';
        document.body.addEventListener('htmx:configRequest', function (e) {
            if (csrf) { e.detail.headers['X-CSRF-Token'] = csrf; }
        });
    })();
</script>
</body>
</html>
"""
    }

    /** Normaliza el canal: minúsculas y, si no es conocido, se agrupa como "simulador". */
    private fun channelKey(raw: String): String {
        val k = raw.lowercase()
        return if (CHANNEL_LABELS.containsKey(k)) k else "simulador"
    }

    /** Porcentaje con un decimal y punto decimal (independiente del idioma del servidor). */
    private fun pct(part: Int, total: Int): String =
        if (total == 0) "0.0" else String.format(Locale.ROOT, "%.1f", part * 100.0 / total)

    private fun icon(paths: String, cls: String = "ic"): String =
        "<svg class=\"$cls\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" " +
            "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">$paths</svg>"

    private fun emptyRow(colspan: Int, message: String): String =
        "<tr><td colspan=\"$colspan\" class=\"empty\">${escapeHtml(message)}</td></tr>"

    fun escapeHtml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
