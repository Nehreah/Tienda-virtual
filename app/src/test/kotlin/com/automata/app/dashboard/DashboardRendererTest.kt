package com.automata.app.dashboard

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DashboardRendererTest {

    @Test
    fun `data coming from customer messages is escaped in the deals table`() {
        val payload = "<img src=x onerror=alert(1)>"
        val deal = ChatDealLog(
            id = "whatsapp:$payload",
            customer = payload,
            channel = "whatsapp\" onmouseover=\"alert(2)",
            channelDisplayName = "WhatsApp",
            productName = payload,
            offeredPrice = com.automata.shared.Money.of(150_000),
            dealState = DealState.EN_NEGOCIACION,
            dealStateLabel = payload,
            timestamp = payload
        )

        val html = DashboardRenderer.renderDashboardPage(
            products = emptyList(),
            inventoryMap = emptyMap(),
            channelStatuses = emptyList(),
            apiIntegrations = emptyList(),
            chatDeals = listOf(deal)
        )

        assertFalse(html.contains(payload), "el texto del cliente salió sin escapar")
        assertFalse(html.contains("onmouseover=\"alert(2)"), "el atributo data-channel se pudo romper")
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
    }
    @Test
    fun `dashboard uses local scripts without inline javascript or third party CDNs`() {
        val html = DashboardRenderer.renderDashboardPage(
            products = emptyList(),
            inventoryMap = emptyMap(),
            channelStatuses = emptyList(),
            apiIntegrations = emptyList(),
            chatDeals = emptyList()
        )

        assertTrue(html.contains("/static/theme-init.js"))
        assertTrue(html.contains("/static/htmx.min.js"))
        assertFalse(html.contains("htmx-lite"))
        assertTrue(html.contains("name=\"htmx-config\""))
        assertTrue(html.contains("\"allowEval\":false"))
        assertTrue(html.contains("/static/dashboard.js"))
        assertFalse(Regex("<script\\s*>" , RegexOption.IGNORE_CASE).containsMatchIn(html),
            "no deben quedar scripts inline en el HTML del dashboard")
        assertFalse(html.contains("unpkg.com"))
        assertFalse(html.contains("cdn.tailwindcss.com"))
    }

}
