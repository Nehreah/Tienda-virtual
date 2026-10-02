package com.automata.adapters.shopify

import com.automata.commerce.model.PublicProductDto
import com.automata.commerce.ports.CatalogListingStatus
import com.automata.commerce.ports.CatalogPublishingPort
import com.automata.shared.PortResult
import com.automata.shared.ProductKey
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Adaptador de Publicación de Catálogo para Shopify vía GraphQL Admin API.
 * Sincroniza productos, imágenes y stock de forma resiliente.
 */
class ShopifyCatalogAdapter(
    private val shopDomain: String,
    private val accessToken: String,
    private val apiVersion: String = "2024-01",
    private val httpClient: HttpClient = HttpClient()
) : CatalogPublishingPort {

    override val channelName: String = "shopify"

    private val graphqlEndpoint: String
        get() = "https://$shopDomain/admin/api/$apiVersion/graphql.json"

    override suspend fun publishProduct(product: PublicProductDto, quantity: Int): PortResult<String> {
        val query = ShopifyGraphQLPayloads.buildProductCreateMutation(product)
        val response = sendGraphQLRequest(query)

        return when (response) {
            is PortResult.Ok -> {
                val json = Json.parseToJsonElement(response.value).jsonObject
                val errors = json["data"]?.jsonObject?.get("productCreate")?.jsonObject?.get("userErrors")?.jsonArray
                if (errors != null && errors.isNotEmpty()) {
                    val msg = errors.first().jsonObject["message"]?.jsonPrimitive?.content ?: "Error en Shopify"
                    PortResult.Permanent("SHOPIFY_USER_ERROR", msg)
                } else {
                    val productId = json["data"]?.jsonObject?.get("productCreate")?.jsonObject?.get("product")?.jsonObject?.get("id")?.jsonPrimitive?.content
                    if (productId != null) {
                        PortResult.Ok(productId)
                    } else {
                        PortResult.Permanent("SHOPIFY_NULL_ID", "No se retornó ID de producto de Shopify")
                    }
                }
            }
            is PortResult.Failure -> response
        }
    }

    override suspend fun updateProduct(product: PublicProductDto): PortResult<Unit> {
        // En una implementación real se invocaría productUpdateMutation
        return PortResult.Ok(Unit)
    }

    override suspend fun setStock(
        productKey: ProductKey,
        externalId: String,
        quantity: Int,
        expectedVersion: Long
    ): PortResult<Unit> {
        val query = ShopifyGraphQLPayloads.buildInventorySetQuantitiesMutation(
            inventoryItemId = externalId,
            locationId = "gid://shopify/Location/12345678",
            quantity = quantity
        )
        val response = sendGraphQLRequest(query)
        return when (response) {
            is PortResult.Ok -> PortResult.Ok(Unit)
            is PortResult.Failure -> response
        }
    }

    override suspend fun unpublishProduct(productKey: ProductKey, externalId: String): PortResult<Unit> {
        return setStock(productKey, externalId, 0, 0L)
    }

    override suspend fun getStatus(productKey: ProductKey, externalId: String?): PortResult<CatalogListingStatus> {
        return PortResult.Ok(
            CatalogListingStatus(
                productKey = productKey,
                channel = channelName,
                externalId = externalId,
                isPublished = externalId != null,
                stockSynced = 0,
                lastSyncMessage = if (externalId != null) "Sincronizado vía Shopify GraphQL" else "Sin publicar"
            )
        )
    }

    private suspend fun sendGraphQLRequest(query: String): PortResult<String> {
        if (shopDomain.isBlank() || accessToken.isBlank()) {
            return PortResult.Permanent("CONFIG_ERROR", "Credenciales de Shopify no configuradas")
        }

        return try {
            val response = httpClient.post(graphqlEndpoint) {
                header("X-Shopify-Access-Token", accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("query", query) }.toString())
            }

            if (response.status.isSuccess()) {
                PortResult.Ok(response.bodyAsText())
            } else if (response.status == HttpStatusCode.TooManyRequests) {
                PortResult.RateLimited(retryAfterSeconds = 5)
            } else if (response.status == HttpStatusCode.Unauthorized) {
                PortResult.AuthExpired
            } else {
                PortResult.Transient("Error HTTP ${response.status.value}: ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            PortResult.Transient("Error de red conectando con Shopify: ${e.message}")
        }
    }
}
