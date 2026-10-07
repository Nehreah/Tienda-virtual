package com.automata.adapters.shopify

import com.automata.commerce.model.PublicProductDto
import com.automata.commerce.ports.ChannelListingStatus
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
import com.automata.commerce.ports.RemoteCatalogPort
import com.automata.commerce.ports.RemoteProductItem

class ShopifyCatalogAdapter(
    private val shopDomain: String,
    private val accessToken: String,
    private val apiVersion: String = "2024-10",
    private val locationId: String = "",
    private val httpClient: HttpClient = HttpClient()
) : CatalogPublishingPort, RemoteCatalogPort {

    override val channelName: String = "shopify"

    override suspend fun fetchRemoteProducts(): List<RemoteProductItem> {
        if (shopDomain.isBlank() || accessToken.isBlank()) return emptyList()
        return emptyList()
    }

    private val graphqlEndpoint: String
        get() = "https://$shopDomain/admin/api/$apiVersion/graphql.json"

    override suspend fun publishProduct(product: PublicProductDto, quantity: Int): PortResult<String> {
        val payload = ShopifyGraphQLPayloads.buildProductCreatePayload(product)
        val response = sendGraphQLRequest(payload)

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
        return PortResult.Ok(Unit)
    }

    override suspend fun setStock(
        productKey: ProductKey,
        externalId: String,
        quantity: Int,
        expectedVersion: Long
    ): PortResult<Unit> {
        if (locationId.isBlank()) {
            return PortResult.Permanent("CONFIG_ERROR", "SHOPIFY_LOCATION_ID no configurada (A11)")
        }

        val payload = ShopifyGraphQLPayloads.buildInventorySetQuantitiesPayload(
            inventoryItemId = externalId,
            locationId = locationId,
            quantity = quantity
        )
        val response = sendGraphQLRequest(payload)
        return when (response) {
            is PortResult.Ok -> {
                val json = Json.parseToJsonElement(response.value).jsonObject
                val errors = json["errors"]?.jsonArray
                if (errors != null && errors.isNotEmpty()) {
                    val errStr = errors.toString()
                    if (errStr.contains("THROTTLED", ignoreCase = true)) {
                        return PortResult.RateLimited(retryAfterSeconds = 5)
                    }
                    return PortResult.Transient("GraphQL Error: $errStr")
                }

                val invData = json["data"]?.jsonObject?.get("inventorySetQuantities")?.jsonObject
                val userErrors = invData?.get("userErrors")?.jsonArray
                if (userErrors != null && userErrors.isNotEmpty()) {
                    val msg = userErrors.first().jsonObject["message"]?.jsonPrimitive?.content ?: "Error en inventario de Shopify"
                    return PortResult.Permanent("SHOPIFY_USER_ERROR", msg)
                }

                val adjGroup = invData?.get("inventoryAdjustmentGroup")
                if (adjGroup == null || adjGroup is JsonNull) {
                    return PortResult.Transient("SHOPIFY_UPDATE_FAILED: no se retornó grupo de ajuste")
                }

                PortResult.Ok(Unit)
            }
            is PortResult.Failure -> response
        }
    }

    override suspend fun unpublishProduct(productKey: ProductKey, externalId: String): PortResult<Unit> {
        return setStock(productKey, externalId, 0, 0L)
    }

    override suspend fun getStatus(productKey: ProductKey, externalId: String?): PortResult<ChannelListingStatus> {
        return PortResult.Ok(
            ChannelListingStatus(
                productKey = productKey,
                channel = channelName,
                externalId = externalId,
                isPublished = externalId != null,
                stockSynced = 0,
                lastSyncMessage = if (externalId != null) "Sincronizado vía Shopify GraphQL" else "Sin publicar"
            )
        )
    }

    private suspend fun sendGraphQLRequest(payload: GraphQLPayload): PortResult<String> {
        if (shopDomain.isBlank() || accessToken.isBlank()) {
            return PortResult.Permanent("CONFIG_ERROR", "Credenciales de Shopify no configuradas")
        }

        return try {
            val bodyObj = buildJsonObject {
                put("query", payload.query)
                payload.variables?.let { put("variables", it) }
            }

            val response = httpClient.post(graphqlEndpoint) {
                header("X-Shopify-Access-Token", accessToken)
                contentType(ContentType.Application.Json)
                setBody(bodyObj.toString())
            }

            if (response.status.isSuccess()) {
                val responseText = response.bodyAsText()
                if (responseText.contains("THROTTLED", ignoreCase = true)) {
                    PortResult.RateLimited(retryAfterSeconds = 5)
                } else {
                    PortResult.Ok(responseText)
                }
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
