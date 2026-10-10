package com.automata.app.routes

import com.automata.app.AppStore
import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.ports.ReservationRepository
import com.automata.commerce.service.ArticuloLifecycleService
import com.automata.shared.ProductKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

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
        if (keyRaw.isBlank() || itemName.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Key e Item son obligatorios"))
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
            synchronized(AppStore.products) {
                AppStore.products[key.value] = com.automata.commerce.model.CommercialProduct(
                    key = key,
                    itemName = created.item,
                    publishedPrice = created.precioPublicado ?: com.automata.shared.Money.of(0),
                    minimumPrice = created.precioMinimo ?: com.automata.shared.Money.of(0),
                    cost = created.precioCompra,
                    status = created.estado.etiqueta,
                    description = created.descripcion ?: "",
                    brand = created.marca ?: "",
                    condition = created.condicion ?: "",
                    category = created.tipo ?: "",
                    location = created.ubicacion,
                    masterQuantity = created.cantidad
                )
            }
            call.respond(HttpStatusCode.Created, created)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Ya existe el producto")))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al crear producto")))
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
            (params["precioCompra"] != null && params["precioCompra"]!!.trim().toLongOrNull() == null)) {
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
            else -> params[name]!!.trim()
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
            synchronized(AppStore.products) {
                AppStore.products[key.value] = com.automata.commerce.model.CommercialProduct(
                    key = key,
                    itemName = updated.item,
                    publishedPrice = updated.precioPublicado ?: com.automata.shared.Money.of(0),
                    minimumPrice = updated.precioMinimo ?: com.automata.shared.Money.of(0),
                    cost = updated.precioCompra,
                    status = updated.estado.etiqueta,
                    description = updated.descripcion ?: "",
                    brand = updated.marca ?: "",
                    condition = updated.condicion ?: "",
                    category = updated.tipo ?: "",
                    location = updated.ubicacion,
                    masterQuantity = updated.cantidad
                )
            }
            call.respond(HttpStatusCode.OK, updated)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Producto no encontrado")))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al editar producto")))
        }
    }
    post("/api/articulos/{key}/archivar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        if (articuloService == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Base de datos no configurada"))
            return@post
        }
        val key = ProductKey(call.parameters["key"] ?: "")
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
            synchronized(AppStore.products) {
                AppStore.products[key.value]?.let {
                    AppStore.products[key.value] = it.copy(status = "Inactivo")
                }
            }
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
        val key = ProductKey(call.parameters["key"] ?: "")
        try {
            articuloService.restaurar(key, usuario = "dashboard")
            synchronized(AppStore.products) {
                AppStore.products[key.value]?.let {
                    AppStore.products[key.value] = it.copy(status = "Publicado")
                }
            }
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
        val key = ProductKey(call.parameters["key"] ?: "")
        try {
            val deleted = articuloService.eliminar(key, usuario = "dashboard")
            if (deleted) {
                synchronized(AppStore.products) { AppStore.products.remove(key.value) }
                synchronized(AppStore.inventory) { AppStore.inventory.remove(key.value) }
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
