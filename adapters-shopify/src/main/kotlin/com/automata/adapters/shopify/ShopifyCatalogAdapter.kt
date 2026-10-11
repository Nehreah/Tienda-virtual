package com.automata.adapters.shopify

import com.automata.commerce.model.PublicProductDto
import com.automata.commerce.ports.ChannelListingStatus
import com.automata.commerce.ports.CatalogPublishingPort
import com.automata.commerce.ports.PublishedListing
import com.automata.shared.Money
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

    private companion object {
        const val MAX_PAGINAS_CATALOGO = 20
    }

    /**
     * v14: lee el catálogo real de Shopify (solo lectura), de a 50 productos, hasta 20 páginas.
     * Usa el SKU de la primera variante como Key. Productos sin SKU o con SKU vacío se omiten.
     * Si Shopify falla lanza la excepción: así la reconciliación no confunde "error" con "catálogo vacío".
     */
    override suspend fun fetchRemoteProducts(): List<RemoteProductItem> {
        if (shopDomain.isBlank() || accessToken.isBlank()) return emptyList()
        val items = mutableListOf<RemoteProductItem>()
        var cursor: String? = null
        var pages = 0
        while (pages < MAX_PAGINAS_CATALOGO) {
            pages++
            val response = sendGraphQLRequest(ShopifyGraphQLPayloads.buildProductsPagePayload(cursor))
            val body = when (response) {
                is PortResult.Ok -> response.value
                is PortResult.Failure -> error("Shopify no respondió al leer el catálogo: $response")
            }
            val json = Json.parseToJsonElement(body).jsonObject
            json["errors"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { error("Shopify devolvió errores GraphQL: $it") }
            val products = json["data"]?.jsonObject?.get("products")?.jsonObject ?: break
            for (edge in products["edges"]?.jsonArray.orEmpty()) {
                val node = edge.jsonObject["node"]?.jsonObject ?: continue
                val variant = node["variants"]?.jsonObject?.get("edges")?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("node")?.jsonObject
                val sku = variant?.get("sku")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (sku.isEmpty()) continue
                val price = variant?.get("price")?.jsonPrimitive?.contentOrNull
                    ?.toBigDecimalOrNull()?.toLong()?.let { Money.of(it) }
                items.add(
                    RemoteProductItem(
                        key = ProductKey(sku),
                        remoteId = node["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        title = node["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        price = price,
                        stockQuantity = node["totalInventory"]?.jsonPrimitive?.intOrNull ?: 0,
                        channel = channelName,
                        rawStatus = node["status"]?.jsonPrimitive?.contentOrNull ?: "ACTIVE"
                    )
                )
            }
            val pageInfo = products["pageInfo"]?.jsonObject
            val hasNext = pageInfo?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull ?: false
            cursor = pageInfo?.get("endCursor")?.jsonPrimitive?.contentOrNull
            if (!hasNext || cursor == null) break
        }
        return items
    }

    private val graphqlEndpoint: String
        get() = "https://$shopDomain/admin/api/$apiVersion/graphql.json"

    override suspend fun publishProduct(product: PublicProductDto, quantity: Int): PortResult<String> =
        when (val r = publishListing(product, quantity)) {
            is PortResult.Ok -> PortResult.Ok(r.value.externalId)
            is PortResult.Failure -> r
        }

    /**
     * v14: crea el producto y devuelve AMBOS IDs. El del producto (gid://shopify/Product/..) sirve para editarlo;
     * el de inventario (gid://shopify/InventoryItem/..) es el que exige [setStock]. Antes solo se guardaba el primero,
     * por eso setStock nunca habría funcionado con un producto publicado desde aquí.
     */
    override suspend fun publishListing(product: PublicProductDto, quantity: Int): PortResult<PublishedListing> {
        val payload = ShopifyGraphQLPayloads.buildProductCreatePayload(product)
        return when (val response = sendGraphQLRequest(payload)) {
            is PortResult.Ok -> {
                val json = Json.parseToJsonElement(response.value).jsonObject
                val gqlErrors = json["errors"]?.jsonArray
                if (gqlErrors != null && gqlErrors.isNotEmpty()) {
                    return PortResult.Permanent("SHOPIFY_GRAPHQL_ERROR", gqlErrors.toString())
                }
                val create = json["data"]?.jsonObject?.get("productCreate")?.jsonObject
                val errors = create?.get("userErrors")?.jsonArray
                if (errors != null && errors.isNotEmpty()) {
                    val msg = errors.first().jsonObject["message"]?.jsonPrimitive?.content ?: "Error en Shopify"
                    return PortResult.Permanent("SHOPIFY_USER_ERROR", msg)
                }
                val created = create?.get("product")?.jsonObject
                val productId = created?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: return PortResult.Permanent("SHOPIFY_NULL_ID", "No se retornó ID de producto de Shopify")
                val inventoryItemId = created["variants"]?.jsonObject?.get("edges")?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("node")?.jsonObject
                    ?.get("inventoryItem")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                PortResult.Ok(PublishedListing(productId, inventoryItemId))
            }
            is PortResult.Failure -> response
        }
    }

    /** v14: ya no devuelve OK sin hacer nada (parecía un éxito). Usa [updateListing], que sí recibe el ID externo. */
    override suspend fun updateProduct(product: PublicProductDto): PortResult<Unit> =
        PortResult.Permanent("NO_IMPLEMENTADO", "Shopify necesita el ID externo: usa updateListing(externalId, product)")

    /** v14: actualiza título, descripción y marca (productUpdate) y luego el precio de la primera variante. */
    override suspend fun updateListing(externalId: String, product: PublicProductDto): PortResult<Unit> {
        val upd = sendGraphQLRequest(ShopifyGraphQLPayloads.buildProductUpdatePayload(externalId, product))
        val updBody = when (upd) {
            is PortResult.Ok -> upd.value
            is PortResult.Failure -> return upd
        }
        checkMutation(updBody, "productUpdate")?.let { return it }

        val variantResp = sendGraphQLRequest(ShopifyGraphQLPayloads.buildFirstVariantPayload(externalId))
        val variantBody = when (variantResp) {
            is PortResult.Ok -> variantResp.value
            is PortResult.Failure -> return variantResp
        }
        val variantJson = Json.parseToJsonElement(variantBody).jsonObject
        val variantId = variantJson["data"]?.jsonObject?.get("product")?.jsonObject
            ?.get("variants")?.jsonObject?.get("edges")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("node")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: return PortResult.Permanent("SHOPIFY_NO_VARIANT", "El producto $externalId no tiene variante en Shopify")

        val price = ShopifyGraphQLPayloads.buildVariantPriceUpdatePayload(
            externalId, variantId, product.publishedPrice.amountInCop.toString()
        )
        val priceResp = sendGraphQLRequest(price)
        val priceBody = when (priceResp) {
            is PortResult.Ok -> priceResp.value
            is PortResult.Failure -> return priceResp
        }
        checkMutation(priceBody, "productVariantsBulkUpdate")?.let { return it }
        return PortResult.Ok(Unit)
    }

    /** Devuelve un error si la respuesta trae errores GraphQL o userErrors de la mutación [nombre]; null si todo bien. */
    private fun checkMutation(body: String, nombre: String): PortResult.Failure? {
        val json = Json.parseToJsonElement(body).jsonObject
        val gql = json["errors"]?.jsonArray
        if (gql != null && gql.isNotEmpty()) {
            return if (gql.toString().contains("THROTTLED", ignoreCase = true)) PortResult.RateLimited(5)
            else PortResult.Permanent("SHOPIFY_GRAPHQL_ERROR", gql.toString())
        }
        val userErrors = json["data"]?.jsonObject?.get(nombre)?.jsonObject?.get("userErrors")?.jsonArray
        if (userErrors != null && userErrors.isNotEmpty()) {
            val msg = userErrors.first().jsonObject["message"]?.jsonPrimitive?.content ?: "Error en Shopify ($nombre)"
            return PortResult.Permanent("SHOPIFY_USER_ERROR", msg)
        }
        return null
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
