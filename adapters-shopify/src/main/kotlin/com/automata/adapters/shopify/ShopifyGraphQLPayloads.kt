package com.automata.adapters.shopify

import com.automata.commerce.model.PublicProductDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Constructor de payloads GraphQL para Shopify Admin API.
 */
object ShopifyGraphQLPayloads {

    fun buildProductCreateMutation(product: PublicProductDto): String {
        val titleEscaped = escapeJson(product.title)
        val descriptionEscaped = escapeJson(product.description)
        val priceStr = product.publishedPrice.amountInCop.toString()
        val brandEscaped = escapeJson(product.brand)

        return """
            mutation {
              productCreate(input: {
                title: "$titleEscaped",
                descriptionHtml: "<p>$descriptionEscaped</p>",
                vendor: "$brandEscaped",
                status: ACTIVE,
                variants: [{
                  price: "$priceStr",
                  sku: "${product.key.value}"
                }]
              }) {
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
    }

    fun buildProductUpdateMutation(externalId: String, product: PublicProductDto): String {
        val titleEscaped = escapeJson(product.title)
        val descriptionEscaped = escapeJson(product.description)
        val brandEscaped = escapeJson(product.brand)

        return """
            mutation {
              productUpdate(input: {
                id: "$externalId",
                title: "$titleEscaped",
                descriptionHtml: "<p>$descriptionEscaped</p>",
                vendor: "$brandEscaped"
              }) {
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
    }

    fun buildInventorySetQuantitiesMutation(inventoryItemId: String, locationId: String, quantity: Int): String {
        return """
            mutation {
              inventorySetQuantities(input: {
                reason: "correction",
                name: "available",
                ignoreCompareQuantity: true,
                quantities: [{
                  inventoryItemId: "$inventoryItemId",
                  locationId: "$locationId",
                  quantity: $quantity
                }]
              }) {
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
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}
