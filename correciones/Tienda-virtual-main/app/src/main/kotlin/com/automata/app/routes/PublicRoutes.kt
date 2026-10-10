package com.automata.app.routes

import com.automata.app.AppStore
import com.automata.app.AppStoreCatalog
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.publicRoutes() {
    // 7. Vitrina Pública
    get("/public/p/{key}") {
        val key = call.parameters["key"] ?: ""
        val product = AppStoreCatalog.publicProduct(key)
        if (product == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Producto no encontrado"))
            return@get
        }

        val inv = synchronized(AppStore.inventory) { AppStore.inventory[key] }
        val isAvailable = inv?.isAvailable ?: false
        val publicDto = product.toPublicDto(isAvailable = isAvailable)

        call.respond(publicDto)
    }
}
