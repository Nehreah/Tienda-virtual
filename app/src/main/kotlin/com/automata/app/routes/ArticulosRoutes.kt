package com.automata.app.routes

import com.automata.app.AppStore
import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.ports.ReservationRepository
import com.automata.shared.Money
import com.automata.commerce.service.ArticuloLifecycleService
import com.automata.shared.ProductKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ArticulosRoutes")

/** Espejo en memoria (AppStore) de un artículo de la base; lo usan el catálogo del dashboard y los canales. */
internal fun InventarioItem.toCommercialProduct(): CommercialProduct = CommercialProduct(
    key = key,
    itemName = item,
    publishedPrice = precioPublicado ?: Money.of(0),
    minimumPrice = precioMinimo ?: Money.of(0),
    cost = precioCompra,
    status = estado.etiqueta,
    description = descripcion ?: "",
    brand = marca ?: "",
    condition = condicion ?: "",
    category = tipo ?: "",
    location = ubicacion,
    masterQuantity = cantidad
)

internal fun sincronizarProductoEnMemoria(item: InventarioItem) {
    synchronized(AppStore.products) { AppStore.products[item.key.value] = item.toCommercialProduct() }
}

internal fun marcarEstadoEnMemoria(key: ProductKey, etiqueta: String) {
    synchronized(AppStore.products) {
        AppStore.products[key.value]?.let { AppStore.products[key.value] = it.copy(status = etiqueta) }
    }
}

internal fun quitarDeMemoria(key: ProductKey) {
    synchronized(AppStore.products) { AppStore.products.remove(key.value) }
    synchronized(AppStore.inventory) { AppStore.inventory.remove(key.value) }
}

/** Key de la ruta (/{key}) para BUSCAR un artículo existente: permisiva, solo exige que no esté vacía. */
internal fun ApplicationCall.keyDeRuta(): ProductKey? =
    parameters["key"]?.trim()?.takeIf { it.isNotEmpty() }?.let { ProductKey(it) }

fun Route.articulosRoutes(
    dashboardGuard: DashboardGuard,
    articuloService: ArticuloLifecycleService?,
    reservationRepo: ReservationRepository?
) {
    // 1. Crear Producto Manualmente
    post("/api/articulos") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        if (articuloService == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Base de datos no configurada"))
            return@post
        }
        val params = call.receiveParameters()
        val keyRaw = params["key"]?.trim() ?: ""
        val itemName = params["item"]?.trim() ?: ""
        if (itemName.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Key e Item son obligatorios"))
            return@post
        }
        ProductKey.validar(keyRaw)?.let {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to it))
            return@post
        }
        val key = ProductKey(keyRaw)
        val precioCompra = params["precioCompra"]?.toLongOrNull() ?: 0L
        val precioPublicado = params["precioPublicado"]?.toLongOrNull()
        val cantidad = params["cantidad"]?.toIntOrNull() ?: 1
        val tipo = params["tipo"]?.takeIf { it.isNotBlank() }
        val descripcion = params["descripcion"]?.takeIf { it.isNotBlank() }
        val marca = params["marca"]?.takeIf { it.isNotBlank() }
        val condicion = params["condicion"]?.takeIf { it.isNotBlank() }
        val ubicacion = params["ubicacion"]?.takeIf { it.isNotBlank() }
        val estadoRaw = params["estado"]?.takeIf { it.isNotBlank() } ?: "Publicado"

        val item = com.automata.commerce.model.InventarioItem(
            key = key,
            item = itemName,
            precioCompra = com.automata.shared.Money.of(precioCompra),
            precioPublicado = precioPublicado?.let { com.automata.shared.Money.of(it) },
            cantidad = cantidad,
            tipo = tipo,
            descripcion = descripcion,
            marca = marca,
            condicion = condicion,
            ubicacion = ubicacion,
            estado = com.automata.commerce.model.EstadoItem.desdeEtiqueta(estadoRaw) ?: com.automata.commerce.model.EstadoItem.PUBLICADO
        )

        try {
            val created = articuloService.crear(item, usuario = "dashboard")
            sincronizarProductoEnMemoria(created)
            call.respond(HttpStatusCode.Created, created)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Ya existe el producto")))
        } catch (e: Exception) {
            call.respondError(logger, "crear el producto", e)
        }
    }

    // 2. Editar Producto Manualmente
    put("/api/articulos/{key}") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@put
        if (articuloService == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Base de datos no configurada"))
            return@put
        }
        val keyRaw = call.parameters["key"]?.trim() ?: ""
        if (keyRaw.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Key de producto requerida"))
            return@put
        }
        val key = ProductKey(keyRaw)
        val params = call.receiveParameters()

        val existing = articuloService.buscar(key)
        if (existing == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "No existe el producto ${key.value}"))
            return@put
        }

        // PUT mantiene compatibilidad con clientes existentes, pero combina campos omitidos
        // con los valores actuales para evitar sobrescribir precio/cantidad/estado por defecto.
        fun parseIntField(name: String, current: Int): Int? {
            val raw = params[name] ?: return current
            return raw.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        }
        val precioCompraRaw = params["precioCompra"]
        val precioCompra = if (precioCompraRaw == null) existing.precioCompra.amountInCop else precioCompraRaw.trim().toLongOrNull()
        val precioPublicadoRaw = params["precioPublicado"]
        val precioPublicado = if (precioPublicadoRaw == null) existing.precioPublicado?.amountInCop
            else precioPublicadoRaw.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
        val cantidad = parseIntField("cantidad", existing.cantidad)

        if (precioCompra == null || precioCompra < 0 || (precioPublicadoRaw != null && precioPublicadoRaw.isNotBlank() && precioPublicado == null) ||
            (precioPublicado != null && precioPublicado < 0) || cantidad == null || cantidad < 0 ||
            (precioCompraRaw != null && precioCompraRaw.trim().toLongOrNull() == null)) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Precio y cantidad deben ser números válidos no negativos"))
            return@put
        }
        val itemName = params["item"]?.trim()?.ifBlank { existing.item } ?: existing.item
        val estadoRaw = params["estado"]
        val estado = if (estadoRaw.isNullOrBlank()) existing.estado else
            com.automata.commerce.model.EstadoItem.desdeEtiqueta(estadoRaw)
        if (estado == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Estado de artículo inválido"))
            return@put
        }

        fun optionalText(name: String, current: String?): String? = when {
            !params.contains(name) -> current
            params[name].isNullOrBlank() -> null
            else -> params[name]?.trim()
        }

        val itemToUpdate = existing.copy(
            item = itemName,
            precioCompra = com.automata.shared.Money.of(precioCompra),
            precioPublicado = precioPublicado?.let { com.automata.shared.Money.of(it) },
            cantidad = cantidad,
            tipo = optionalText("tipo", existing.tipo),
            descripcion = optionalText("descripcion", existing.descripcion),
            marca = optionalText("marca", existing.marca),
            condicion = optionalText("condicion", existing.condicion),
            ubicacion = optionalText("ubicacion", existing.ubicacion),
            estado = estado
        )

        try {
            val updated = articuloService.editar(itemToUpdate, usuario = "dashboard")
            sincronizarProductoEnMemoria(updated)
            call.respond(HttpStatusCode.OK, updated)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Producto no encontrado")))
        } catch (e: Exception) {
            call.respondError(logger, "editar el producto", e)
        }
    }
    post("/api/articulos/{key}/archivar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        if (articuloService == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Base de datos no configurada"))
            return@post
        }
        val key = call.keyDeRuta() ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Key de producto requerida"))
            return@post
        }
        val params = call.receiveParameters()
        val motivo = params["motivo"]?.trim() ?: ""
        if (motivo.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El campo 'motivo' es obligatorio para archivar un artículo"))
            return@post
        }
        try {
            articuloService.archivar(
                key = key,
                motivo = motivo,
                usuario = "dashboard",
                reservationRepo = reservationRepo
            )
            marcarEstadoEnMemoria(key, "Inactivo")
            call.respond(HttpStatusCode.OK, mapOf("status" to "archivado", "key" to key.value, "motivo" to motivo))
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "No se pudo archivar")))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Artículo no encontrado")))
        }
    }

    post("/api/articulos/{key}/restaurar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        if (articuloService == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Base de datos no configurada"))
            return@post
        }
        val key = call.keyDeRuta() ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Key de producto requerida"))
            return@post
        }
        try {
            articuloService.restaurar(key, usuario = "dashboard")
            marcarEstadoEnMemoria(key, "Publicado")
            call.respond(HttpStatusCode.OK, mapOf("status" to "restaurado", "key" to key.value))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Artículo no encontrado")))
        }
    }

    delete("/api/articulos/{key}") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@delete
        if (articuloService == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Base de datos no configurada"))
            return@delete
        }
        val key = call.keyDeRuta() ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Key de producto requerida"))
            return@delete
        }
        try {
            val deleted = articuloService.eliminar(key, usuario = "dashboard")
            if (deleted) {
                quitarDeMemoria(key)
                call.respond(HttpStatusCode.OK, mapOf("status" to "eliminado", "key" to key.value))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No existe el artículo ${key.value}"))
            }
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "No se puede eliminar")))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Artículo no encontrado")))
        }
    }
}
