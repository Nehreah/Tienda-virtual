package com.automata.adapters.meta

enum class ShopMode {
    FULL_SHOP,    // Checkout nativo en Facebook/Instagram habilitado
    CATALOG_ONLY, // Solo catálogo en Commerce Manager para anuncios y WhatsApp (Modo por defecto en Colombia desde 2023)
    DISABLED      // Sin credenciales o inhabilitado
}

object CapabilityDetector {
    fun detectMode(countryCode: String, hasValidCredentials: Boolean): ShopMode {
        if (!hasValidCredentials) return ShopMode.DISABLED

        // Hallazgo v5.3: Facebook/Instagram Shops no están disponibles con checkout nativo en Colombia desde 10/08/2023.
        return if (countryCode.equals("CO", ignoreCase = true) || countryCode.equals("CL", ignoreCase = true) || countryCode.equals("AR", ignoreCase = true)) {
            ShopMode.CATALOG_ONLY
        } else {
            ShopMode.FULL_SHOP
        }
    }
}
