package com.automata.adapters.meta

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CapabilityDetectorTest {

    @Test
    fun `colombia is detected as CATALOG_ONLY mode due to Meta Shops availability`() {
        val mode = CapabilityDetector.detectMode(countryCode = "CO", hasValidCredentials = true)
        assertEquals(ShopMode.CATALOG_ONLY, mode, "En Colombia Meta Shops opera en CATALOG_ONLY")
    }

    @Test
    fun `disabled credentials return DISABLED mode`() {
        val mode = CapabilityDetector.detectMode(countryCode = "US", hasValidCredentials = false)
        assertEquals(ShopMode.DISABLED, mode)
    }

    @Test
    fun `other countries with credentials return FULL_SHOP mode`() {
        val mode = CapabilityDetector.detectMode(countryCode = "US", hasValidCredentials = true)
        assertEquals(ShopMode.FULL_SHOP, mode)
    }
}
