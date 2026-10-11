package com.automata.commerce.service

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.DiscrepancyDetail
import com.automata.commerce.model.ReconciliationItem
import com.automata.commerce.model.ReconciliationReport
import com.automata.commerce.model.ReconciliationStatus
import com.automata.commerce.ports.InventarioRepository
import com.automata.commerce.ports.RemoteCatalogPort
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.slf4j.LoggerFactory

/**
 * Caso de Uso de Reconciliación de Catálogo (Fase F2 + C3).
 * Compara los productos de la matriz local contra lo publicado en canales externos (Shopify, Meta)
 * en MODO SOLO LECTURA sin alterar ningún dato.
 */
class ReconcileCatalogUseCase(
    private val remotePorts: List<RemoteCatalogPort>
) {
    private val logger = LoggerFactory.getLogger(ReconcileCatalogUseCase::class.java)

    suspend fun execute(
        localProducts: List<CommercialProduct>
    ): ReconciliationReport {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC).toString()
        val items = mutableListOf<ReconciliationItem>()
        val totalRemoteByChannel = mutableMapOf<String, Int>()
        val failedChannels = mutableMapOf<String, String>()

        val activeLocalProducts = localProducts.filter { it.isActive }.associateBy { it.key.value }

        for (port in remotePorts) {
            val channel = port.channelName
            // Si el canal no responde NO se trata como "canal vacío": eso haría aparecer todos los productos locales como
            // "solo local" (falsos faltantes) y podría llevar a republicar todo el catálogo. Se omite el canal y se avisa.
            val remoteItems = try {
                port.fetchRemoteProducts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Reconciliación: no se pudo consultar el canal '$channel': ${e.message}", e)
                failedChannels[channel] = e.message ?: e::class.simpleName ?: "error desconocido"
                continue
            }
            totalRemoteByChannel[channel] = remoteItems.size

            val remoteByKey = remoteItems.associateBy { it.key.value }

            // 1. Comparar productos locales activos contra el canal
            for ((keyStr, localProd) in activeLocalProducts) {
                val remote = remoteByKey[keyStr]
                if (remote == null) {
                    items.add(
                        ReconciliationItem(
                            key = keyStr,
                            title = localProd.itemName,
                            channel = channel,
                            status = ReconciliationStatus.LOCAL_ONLY
                        )
                    )
                } else {
                    val discrepancies = mutableListOf<DiscrepancyDetail>()

                    // Comparar precio si el canal reporta precio
                    if (remote.price != null && remote.price.amountInCop != localProd.publishedPrice.amountInCop) {
                        discrepancies.add(
                            DiscrepancyDetail(
                                field = "precio",
                                localValue = localProd.publishedPrice.amountInCop.toString(),
                                remoteValue = remote.price.amountInCop.toString()
                            )
                        )
                    }

                    // Comparar stock
                    if (remote.stockQuantity != localProd.masterQuantity) {
                        discrepancies.add(
                            DiscrepancyDetail(
                                field = "stock",
                                localValue = localProd.masterQuantity.toString(),
                                remoteValue = remote.stockQuantity.toString()
                            )
                        )
                    }

                    val status = if (discrepancies.isEmpty()) ReconciliationStatus.MATCH_OK else ReconciliationStatus.MISMATCH
                    items.add(
                        ReconciliationItem(
                            key = keyStr,
                            title = localProd.itemName,
                            channel = channel,
                            status = status,
                            discrepancies = discrepancies,
                            remoteId = remote.remoteId
                        )
                    )
                }
            }

            // 2. Identificar productos que están en el canal pero no en la matriz local activa
            for ((keyStr, remoteProd) in remoteByKey) {
                if (!activeLocalProducts.containsKey(keyStr)) {
                    items.add(
                        ReconciliationItem(
                            key = keyStr,
                            title = remoteProd.title,
                            channel = channel,
                            status = ReconciliationStatus.CHANNEL_ONLY,
                            remoteId = remoteProd.remoteId
                        )
                    )
                }
            }
        }

        return ReconciliationReport(
            timestamp = now,
            totalLocal = activeLocalProducts.size,
            totalRemoteByChannel = totalRemoteByChannel,
            matchOkCount = items.count { it.status == ReconciliationStatus.MATCH_OK },
            mismatchCount = items.count { it.status == ReconciliationStatus.MISMATCH },
            localOnlyCount = items.count { it.status == ReconciliationStatus.LOCAL_ONLY },
            channelOnlyCount = items.count { it.status == ReconciliationStatus.CHANNEL_ONLY },
            items = items,
            failedChannels = failedChannels
        )
    }
}
