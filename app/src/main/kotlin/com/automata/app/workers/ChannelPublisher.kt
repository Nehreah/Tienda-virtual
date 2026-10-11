package com.automata.app.workers

import com.automata.commerce.ports.CatalogPublishingPort
import com.automata.commerce.ports.InventarioRepository
import com.automata.infra.config.AppConfig
import com.automata.infra.db.PublicacionesTable
import com.automata.shared.PortResult
import com.automata.shared.ProductKey
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

sealed interface PublicarResultado {
    /** CHANNEL_WRITE_ENABLED=false: no se escribió nada; [datos] es lo que se enviaría. */
    data class Ensayo(
        val canal: String,
        val datos: Map<String, String>,
        val stock: Int,
        val avisos: List<String>
    ) : PublicarResultado

    data class Publicado(
        val canal: String,
        val externalId: String,
        val externalInventoryId: String?,
        val stock: Int,
        val stockSincronizado: Boolean,
        val aviso: String?
    ) : PublicarResultado

    /** [status] es el código HTTP sugerido (400, 404, 409 o 422). */
    data class Rechazado(val status: Int, val mensaje: String) : PublicarResultado
}

/**
 * v14: publica un artículo del inventario en un canal (Shopify o Meta) por primera vez.
 * Guarda los IDs externos en tienda.publicaciones: desde ese momento los triggers de la base encolan
 * los cambios de stock y de datos, y el OutboxWorker los envía.
 *
 * Con CHANNEL_WRITE_ENABLED=false hace un ENSAYO: valida y muestra qué enviaría, sin escribir en el canal ni en la base.
 */
class ChannelPublisher(
    private val repo: InventarioRepository,
    private val adapters: List<CatalogPublishingPort>
) {

    suspend fun publicar(
        key: ProductKey,
        canalSolicitado: String,
        escrituraHabilitada: Boolean = AppConfig.channelWriteEnabled
    ): PublicarResultado {
        val canal = normalizarCanal(canalSolicitado)
        val adapter = adapters.firstOrNull { it.channelName.equals(canal, ignoreCase = true) }
            ?: return PublicarResultado.Rechazado(
                400, "Canal inválido: '$canalSolicitado'. Usa ${adapters.joinToString(" o ") { it.channelName }}."
            )
        val item = repo.findByKey(key)
            ?: return PublicarResultado.Rechazado(404, "No existe el artículo ${key.value}.")
        if (!item.estaALaVenta()) {
            return PublicarResultado.Rechazado(
                409, "Solo se publican artículos en estado Publicado o Negociación y no archivados (${key.value} está ${item.estado.etiqueta})."
            )
        }
        val precio = item.precioPublicado
        if (precio == null || precio.amountInCop <= 0) {
            return PublicarResultado.Rechazado(400, "El artículo ${key.value} necesita un precio publicado mayor a 0.")
        }
        if (item.cantidad <= 0) {
            return PublicarResultado.Rechazado(409, "El artículo ${key.value} no tiene unidades en stock.")
        }

        val dto = item.toChannelDto()
        val stock = item.cantidad

        if (!escrituraHabilitada) {
            val avisos = mutableListOf<String>()
            if (canal == "meta_catalogo") {
                if (dto.images.firstOrNull().isNullOrBlank()) avisos.add("Meta exige al menos una foto; el artículo no tiene.")
                if (dto.attributes["link"].isNullOrBlank()) avisos.add("Meta exige un enlace público: completa 'Enlace de la publicación' o define PUBLIC_BASE_URL.")
            }
            val datos = linkedMapOf(
                "key" to dto.key.value,
                "titulo" to dto.title,
                "descripcion" to dto.description,
                "precio" to precio.amountInCop.toString(),
                "stock" to stock.toString(),
                "marca" to dto.brand,
                "condicion" to dto.condition,
                "foto" to dto.images.firstOrNull().orEmpty(),
                "enlace" to dto.attributes["link"].orEmpty()
            )
            return PublicarResultado.Ensayo(canal, datos, stock, avisos)
        }

        val yaPublicado = transaction {
            PublicacionesTable.select {
                (PublicacionesTable.sku eq key.value) and (PublicacionesTable.canal eq adapter.channelName) and
                    (PublicacionesTable.publicado eq true)
            }.count() > 0
        }
        if (yaPublicado) {
            return PublicarResultado.Rechazado(409, "${key.value} ya está publicado en $canal. Sus cambios se sincronizan solos.")
        }

        val listing = when (val r = adapter.publishListing(dto, stock)) {
            is PortResult.Ok -> r.value
            is PortResult.Failure -> return PublicarResultado.Rechazado(422, "$canal rechazó la publicación: ${describir(r)}")
        }
        val stockId = listing.externalInventoryId ?: listing.externalId

        transaction {
            val actualizadas = PublicacionesTable.update({
                (PublicacionesTable.sku eq key.value) and (PublicacionesTable.canal eq adapter.channelName)
            }) { row ->
                row[PublicacionesTable.externalId] = listing.externalId
                row[PublicacionesTable.externalInventoryId] = listing.externalInventoryId
                row[PublicacionesTable.publicado] = true
                row[PublicacionesTable.stockPublicado] = 0
                row[PublicacionesTable.ultimaSincronizacion] = Instant.now()
            }
            if (actualizadas == 0) {
                PublicacionesTable.insert { row ->
                    row[PublicacionesTable.sku] = key.value
                    row[PublicacionesTable.canal] = adapter.channelName
                    row[PublicacionesTable.externalId] = listing.externalId
                    row[PublicacionesTable.externalInventoryId] = listing.externalInventoryId
                    row[PublicacionesTable.publicado] = true
                    row[PublicacionesTable.stockPublicado] = 0
                    row[PublicacionesTable.ultimaSincronizacion] = Instant.now()
                }
            }
        }

        val stockResult = adapter.setStock(key, stockId, stock, 0L)
        val stockOk = stockResult is PortResult.Ok
        if (stockOk) {
            transaction {
                PublicacionesTable.update({
                    (PublicacionesTable.sku eq key.value) and (PublicacionesTable.canal eq adapter.channelName)
                }) { row ->
                    row[PublicacionesTable.stockPublicado] = stock
                    row[PublicacionesTable.ultimaSincronizacion] = Instant.now()
                }
            }
        }
        com.automata.app.AppStore.channelMappings.compute(key.value) { _, actual ->
            (actual ?: emptyMap()) + (adapter.channelName to stockId)
        }
        val aviso = if (stockOk) null else
            "Producto creado en $canal, pero el stock no se pudo fijar todavía (${(stockResult as PortResult.Failure).let { describir(it) }}). Se reintentará con el próximo cambio de stock."
        return PublicarResultado.Publicado(canal, listing.externalId, listing.externalInventoryId, stock, stockOk, aviso)
    }

    companion object {
        /** Acepta "shopify", "meta" y "meta_catalogo". */
        fun normalizarCanal(raw: String): String = when (raw.trim().lowercase()) {
            "meta", "meta_catalogo", "meta-catalogo", "facebook", "instagram" -> "meta_catalogo"
            else -> raw.trim().lowercase()
        }

        fun describir(f: PortResult.Failure): String = when (f) {
            is PortResult.Permanent -> "${f.code}: ${f.detail}"
            is PortResult.Transient -> f.cause
            is PortResult.RateLimited -> "límite de uso del canal, reintenta en ${f.retryAfterSeconds}s"
            PortResult.AuthExpired -> "credenciales vencidas o inválidas"
        }
    }
}
