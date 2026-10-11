package com.automata.adapters.meta

import com.automata.commerce.model.PublicProductDto
import com.automata.commerce.ports.ChannelListingStatus
import com.automata.commerce.ports.CatalogPublishingPort
import com.automata.commerce.ports.PublishedListing
import com.automata.shared.Money
import com.automata.shared.PortResult
import com.automata.shared.ProductKey
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Adaptador de Catálogo para Meta (Facebook/Instagram/WhatsApp Commerce Manager).
 * Operará en modo CATALOG_ONLY cuando el checkout nativo no esté disponible en el país.
 *
 * v14: ya no es un esqueleto. Usa la Batch API del catálogo (POST /{catalog_id}/items_batch) para crear,
 * actualizar y fijar stock, y GET /{catalog_id}/products para leer el catálogo (reconciliación).
 * El ID de cada ítem en Meta es la Key del producto (retailer_id), por eso externalId == Key.
 * La Batch API es asíncrona: devuelve "handles"; un rechazo posterior de Meta se ve en Commerce Manager
 * y la reconciliación lo detecta como diferencia.
 */
import com.automata.commerce.ports.RemoteCatalogPort
import com.automata.commerce.ports.RemoteProductItem

class MetaShopAdapter(
    private val catalogId: String,
    private val accessToken: String,
    private val countryCode: String = "CO",
    private val apiVersion: String = "v26.0",
    private val httpClient: HttpClient = HttpClient(),
    private val currency: String = "COP"
) : CatalogPublishingPort, RemoteCatalogPort {

    override val channelName: String = "meta_catalogo"

    private val graphBase: String get() = "https://graph.facebook.com/$apiVersion"

    val currentMode: ShopMode
        get() = CapabilityDetector.detectMode(countryCode, accessToken.isNotBlank())

    private fun sinCredenciales(): PortResult.Failure? =
        if (catalogId.isBlank() || accessToken.isBlank())
            PortResult.Permanent("CONFIG_ERROR", "META_CATALOG_ID o META_ACCESS_TOKEN no configurados")
        else null

    /**
     * Lee el catálogo real de Meta (solo lectura), de a 100 ítems, hasta 20 páginas.
     * Si Meta falla lanza la excepción (la reconciliación no debe tomar un error como "catálogo vacío").
     */
    override suspend fun fetchRemoteProducts(): List<RemoteProductItem> {
        if (catalogId.isBlank() || accessToken.isBlank()) return emptyList()
        val items = mutableListOf<RemoteProductItem>()
        var url: String? = "$graphBase/$catalogId/products"
        var primera = true
        var pages = 0
        while (url != null && pages < 20) {
            pages++
            val response = if (primera) {
                httpClient.get(url) {
                    parameter("fields", "retailer_id,name,price,inventory,availability")
                    parameter("limit", 100)
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            } else {
                httpClient.get(url) { header(HttpHeaders.Authorization, "Bearer $accessToken") }
            }
            primera = false
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) error("Meta no respondió al leer el catálogo (${response.status.value}): $text")
            val json = Json.parseToJsonElement(text).jsonObject
            for (el in json["data"]?.jsonArray.orEmpty()) {
                val o = el.jsonObject
                val retailerId = o["retailer_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (retailerId.isEmpty()) continue
                val availability = o["availability"]?.jsonPrimitive?.contentOrNull ?: "in stock"
                val inventory = o["inventory"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                items.add(
                    RemoteProductItem(
                        key = ProductKey(retailerId),
                        remoteId = retailerId,
                        title = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        price = parsePrice(o["price"]?.jsonPrimitive?.contentOrNull),
                        stockQuantity = inventory ?: if (availability.equals("out of stock", true)) 0 else 1,
                        channel = channelName,
                        rawStatus = availability
                    )
                )
            }
            url = json["paging"]?.jsonObject?.get("next")?.jsonPrimitive?.contentOrNull
        }
        return items
    }

    override suspend fun publishProduct(product: PublicProductDto, quantity: Int): PortResult<String> =
        when (val r = publishListing(product, quantity)) {
            is PortResult.Ok -> PortResult.Ok(r.value.externalId)
            is PortResult.Failure -> r
        }

    override suspend fun publishListing(product: PublicProductDto, quantity: Int): PortResult<PublishedListing> {
        sinCredenciales()?.let { return it }
        MetaCatalogPayloads.missingRequiredForCreate(product)?.let { return PortResult.Permanent("META_CAMPO_FALTANTE", it) }
        val data = MetaCatalogPayloads.itemData(product, quantity, currency)
        return when (val r = sendBatch("CREATE", data)) {
            is PortResult.Ok -> PortResult.Ok(PublishedListing(externalId = product.key.value, externalInventoryId = null))
            is PortResult.Failure -> r
        }
    }

    /** [updateProduct] sin ID externo no tiene sentido; en Meta el ID es la Key, así que se puede resolver solo. */
    override suspend fun updateProduct(product: PublicProductDto): PortResult<Unit> =
        updateListing(product.key.value, product)

    override suspend fun updateListing(externalId: String, product: PublicProductDto): PortResult<Unit> {
        sinCredenciales()?.let { return it }
        // Se conserva el stock actual del canal: aquí solo cambian los datos del producto (título, precio, etc.).
        val data = buildJsonObject {
            MetaCatalogPayloads.itemData(product, quantity = 0, currency = currency).forEach { (k, v) ->
                if (k != "inventory" && k != "availability") put(k, v)
            }
            put("id", externalId.ifBlank { product.key.value })
        }
        return when (val r = sendBatch("UPDATE", data)) {
            is PortResult.Ok -> PortResult.Ok(Unit)
            is PortResult.Failure -> r
        }
    }

    override suspend fun setStock(
        productKey: ProductKey,
        externalId: String,
        quantity: Int,
        expectedVersion: Long
    ): PortResult<Unit> {
        sinCredenciales()?.let { return it }
        val data = MetaCatalogPayloads.stockData(externalId.ifBlank { productKey.value }, quantity)
        return when (val r = sendBatch("UPDATE", data)) {
            is PortResult.Ok -> PortResult.Ok(Unit)
            is PortResult.Failure -> r
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
                lastSyncMessage = "Modo: ${currentMode.name} - Meta Commerce Manager"
            )
        )
    }

    /** Envía UN pedido a items_batch y traduce la respuesta a [PortResult]. */
    private suspend fun sendBatch(method: String, data: JsonObject): PortResult<String> {
        val requests = MetaCatalogPayloads.batch(method, data).toString()
        return try {
            val response = httpClient.submitForm(
                url = "$graphBase/$catalogId/items_batch",
                formParameters = parameters {
                    append("item_type", "PRODUCT_ITEM")
                    append("allow_upsert", "true")
                    append("requests", requests)
                }
            ) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            val text = response.bodyAsText()
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            val err = json?.get("error")?.jsonObject
            when {
                err != null -> mapError(err)
                response.status.isSuccess() -> {
                    val handle = json?.get("handles")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
                    if (handle != null) PortResult.Ok(handle)
                    else PortResult.Transient("Meta respondió sin 'handles': $text")
                }
                response.status == HttpStatusCode.TooManyRequests -> PortResult.RateLimited(60)
                response.status == HttpStatusCode.Unauthorized -> PortResult.AuthExpired
                response.status.value >= 500 -> PortResult.Transient("Error HTTP ${response.status.value} de Meta: $text")
                else -> PortResult.Permanent("META_HTTP_${response.status.value}", text.take(300))
            }
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            PortResult.Transient("Error de red conectando con Meta: ${e.message}")
        }
    }

    private fun mapError(err: JsonObject): PortResult.Failure {
        val code = err["code"]?.jsonPrimitive?.intOrNull ?: -1
        val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: "Error desconocido de Meta"
        return when (code) {
            190, 102 -> PortResult.AuthExpired
            4, 17, 32, 613, 80004 -> PortResult.RateLimited(60)
            1, 2 -> PortResult.Transient("Meta (código $code): $msg")
            else -> PortResult.Permanent("META_ERROR_$code", msg)
        }
    }

    /** "100000 COP", "COP 100000", "100,000.00 COP" -> 100000. Devuelve null si no se puede leer. */
    private fun parsePrice(raw: String?): Money? {
        val numero = Regex("[0-9][0-9.,]*").find(raw.orEmpty())?.value ?: return null
        val entero = numero.replace(",", "").substringBefore('.')
        return entero.toLongOrNull()?.let { Money.of(it) }
    }
}
