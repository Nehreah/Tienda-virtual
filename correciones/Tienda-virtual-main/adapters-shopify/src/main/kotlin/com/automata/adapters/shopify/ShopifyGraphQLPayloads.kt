package com.automata.adapters.shopify

import com.automata.commerce.model.PublicProductDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class GraphQLPayload(
    val query: String,
    val variables: JsonObject? = null
)

/**
 * Constructor de payloads GraphQL para Shopify Admin API usando variables (M7).
 */
object ShopifyGraphQLPayloads {

    fun buildProductCreatePayload(product: PublicProductDto): GraphQLPayload {
        val query = """
            mutation ProductCreate(${'$'}input: ProductInput!) {
              productCreate(input: ${'$'}input) {
                product {
                  id
                  title
                  variants(first: 1) {
                    edges {
                      node {
                        id
                        inventoryItem {
                          id
                        }
                      }
                    }
                  }
                }
                userErrors {
                  field
                  message
                }
              }
            }
        """.trimIndent()

        val variables = buildJsonObject {
            putJsonObject("input") {
                put("title", product.title)
                put("descriptionHtml", "<p>${product.description}</p>")
                put("vendor", product.brand)
                put("status", "ACTIVE")
                putJsonArray("variants") {
                    add(buildJsonObject {
                        put("price", product.publishedPrice.amountInCop.toString())
                        put("sku", product.key.value)
                    })
                }
            }
        }

        return GraphQLPayload(query, variables)
    }

    fun buildProductUpdatePayload(externalId: String, product: PublicProductDto): GraphQLPayload {
        val query = """
            mutation ProductUpdate(${'$'}input: ProductInput!) {
              productUpdate(input: ${'$'}input) {
                product {
                  id
                  title
                }
                userErrors {
                  field
                  message
                }
              }
            }
        """.trimIndent()

        val variables = buildJsonObject {
            putJsonObject("input") {
                put("id", externalId)
                put("title", product.title)
                put("descriptionHtml", "<p>${product.description}</p>")
                put("vendor", product.brand)
            }
        }

        return GraphQLPayload(query, variables)
    }

    fun buildInventorySetQuantitiesPayload(inventoryItemId: String, locationId: String, quantity: Int): GraphQLPayload {
        val query = """
            mutation InventorySetQuantities(${'$'}input: InventorySetQuantitiesInput!) {
              inventorySetQuantities(input: ${'$'}input) {
                inventoryAdjustmentGroup {
                  id
                }
                userErrors {
                  field
                  message
                }
              }
            }
        """.trimIndent()

        val variables = buildJsonObject {
            putJsonObject("input") {
                put("reason", "correction")
                put("name", "available")
                put("ignoreCompareQuantity", true)
                putJsonArray("quantities") {
                    add(buildJsonObject {
                        put("inventoryItemId", inventoryItemId)
                        put("locationId", locationId)
                        put("quantity", quantity)
                    })
                }
            }
        }

        return GraphQLPayload(query, variables)
    }

    // Métodos legacy para compatibilidad si alguna prueba los requiere directamente como String
    fun buildProductCreateMutation(product: PublicProductDto): String = buildProductCreatePayload(product).query
    fun buildProductUpdateMutation(externalId: String, product: PublicProductDto): String = buildProductUpdatePayload(externalId, product).query
    fun buildInventorySetQuantitiesMutation(inventoryItemId: String, locationId: String, quantity: Int): String = buildInventorySetQuantitiesPayload(inventoryItemId, locationId, quantity).query
}
