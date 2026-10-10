package com.automata.app.routes

import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.MetricasRenderer
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.ports.InventarioRepository
import com.automata.commerce.ports.SocioRepository
import com.automata.commerce.ports.VentaRepository
import com.automata.commerce.service.DatosMetricas
import com.automata.commerce.service.MetricasService
import com.automata.shared.Money
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.metricasRoutes(
    dashboardGuard: DashboardGuard,
    metricasService: MetricasService,
    inventarioRepo: InventarioRepository,
    ventaRepo: VentaRepository,
    socioRepo: SocioRepository
) {
    route("/api/metricas") {

        get {
            if (!call.requireDashboardAuth(dashboardGuard)) return@get
            val pestana = call.request.queryParameters["p"] ?: "generales"

            val items = inventarioRepo.findAll()
            val productos = items.map { item ->
                CommercialProduct(
                    key = item.key,
                    itemName = item.item,
                    category = item.tipo ?: "General",
                    publishedPrice = item.precioPublicado ?: Money.ZERO,
                    minimumPrice = item.precioMinimo ?: Money.ZERO,
                    cost = item.costoTotal,
                    status = if (item.estaVendido) "Vendido" else "Activo",
                    description = item.descripcion ?: "",
                    brand = item.marca ?: "",
                    condition = item.condicion ?: "Nuevo"
                )
            }
            val inventarios = items.map { item ->
                OperationalInventory(productKey = item.key, stockMaestro = if (item.estaVendido) 0 else item.cantidad)
            }
            val ventas = ventaRepo.findAll()
            val repartosVenta = ventaRepo.repartos()
            val inversionesStock = items.associate { it.key to it.reparte.porSocio }
            val socios = socioRepo.listar(incluirArchivados = true)

            val datos = DatosMetricas(
                productos = productos,
                inventarios = inventarios,
                ventas = ventas,
                repartosVenta = repartosVenta,
                inversionesStock = inversionesStock,
                socios = socios
            )

            val generales = metricasService.calcularGenerales(datos)
            val sociosResumen = metricasService.calcularPorSocio(datos)
            val masVendidos = metricasService.masVendidosPorTipo(datos)
            val masRapidos = metricasService.masRapidos(datos)

            val html = MetricasRenderer.renderVistaMetricas(
                generales = generales,
                sociosResumen = sociosResumen,
                masVendidos = masVendidos,
                masRapidos = masRapidos,
                pestanaActiva = pestana
            )
            call.respondText(html, ContentType.Text.Html)
        }
    }
}
