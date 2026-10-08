package com.automata.app.routes

import com.automata.app.AppStore
import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.ports.InventarioRepository
import com.automata.infra.db.ExposedImportacionesRepository
import com.automata.infra.db.Origen
import com.automata.infra.excel.ExcelGenerator
import com.automata.infra.excel.ExcelPreviewService
import com.automata.infra.excel.ExcelV2Parser
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ExcelRoutes")

fun Route.excelRoutes(
    dashboardGuard: DashboardGuard,
    dbRepository: InventarioRepository?,
    importacionesRepo: ExposedImportacionesRepository?,
    excelPreviewService: ExcelPreviewService,
    excelParser: ExcelV2Parser
) {
    // 3. Vista Previa de Excel (/api/excel/preview — Fase C1 / N1 / N2)
    post("/api/excel/preview") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val multipart = call.receiveMultipart()
        var fileBytes: ByteArray? = null
        var tooLarge = false

        multipart.forEachPart { part ->
            if (part is PartData.FileItem && !tooLarge) {
                val bytes = part.streamProvider().readBytes()
                if (bytes.size > 5_242_880) {
                    tooLarge = true
                } else {
                    fileBytes = bytes
                }
            }
            part.dispose()
        }
        if (tooLarge) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "El archivo supera el límite de 5 MB"))
            return@post
        }
        if (fileBytes == null) {
            call.respond(HttpStatusCode.BadRequest, "No se recibió archivo .xlsx")
            return@post
        }

        val report = excelPreviewService.generatePreview(
            fileBytes!!,
            synchronized(AppStore.products) { AppStore.products.toMap() }
        )

        val statusCode = if (report.canConfirm) HttpStatusCode.OK else HttpStatusCode.BadRequest
        call.respond(statusCode, report)
    }

    // 4. Confirmación e Importación Transaccional en dos pasos (/api/excel/confirmar — Fase C1)
    post("/api/excel/confirmar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val multipart = call.receiveMultipart()
        var fileBytes: ByteArray? = null
        var tooLarge = false

        multipart.forEachPart { part ->
            if (part is PartData.FileItem && !tooLarge) {
                val bytes = part.streamProvider().readBytes()
                if (bytes.size > 5_242_880) {
                    tooLarge = true
                } else {
                    fileBytes = bytes
                }
            }
            part.dispose()
        }
        if (tooLarge) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "El archivo supera el límite de 5 MB"))
            return@post
        }
        if (fileBytes == null) {
            call.respond(HttpStatusCode.BadRequest, "No se recibió archivo .xlsx")
            return@post
        }

        val report = excelPreviewService.generatePreview(
            fileBytes!!,
            synchronized(AppStore.products) { AppStore.products.toMap() }
        )

        if (!report.canConfirm) {
            val reason = when {
                report.alreadyImported -> "El archivo Excel ya fue importado previamente (mismo hash SHA-256)."
                report.bloqueados.isNotEmpty() -> "Existen ${report.bloqueados.size} producto(s) bloqueado(s) porque están marcados como Vendidos en la BD."
                report.fileError != null -> report.fileError
                report.formatErrors.isNotEmpty() -> "Existen errores de formato en el Excel."
                else -> "El archivo no se puede confirmar."
            }
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("summary" to "Importación rechazada.", "reason" to reason, "report" to report)
            )
            return@post
        }

        val parseResult = excelParser.parseExcel(fileBytes!!.inputStream())
        val dbErrors = mutableListOf<String>()

        try {
            if (dbRepository != null) {
                org.jetbrains.exposed.sql.transactions.transaction {
                    parseResult.products.forEach { p ->
                        val item = InventarioItem(
                            key = p.key,
                            item = p.itemName,
                            precioPublicado = p.publishedPrice,
                            precioMinimo = p.minimumPrice,
                            precioCompra = p.cost,
                            costoTotal = p.cost,
                            descripcion = p.description,
                            tipo = p.category,
                            marca = p.brand,
                            condicion = p.condition,
                            cantidad = p.masterQuantity,
                            ubicacion = p.location,
                            fotos = p.images,
                            estado = EstadoItem.desdeEtiqueta(p.status) ?: EstadoItem.PUBLICADO
                        )
                        dbRepository.upsert(item, usuario = "sistema", origen = Origen.IMPORT)
                    }
                }
            }

            // N1 Fix: Refrescar la memoria SOLO TRAS COMMIT exitoso en la BD (o si no hay BD)
            parseResult.products.forEach { p ->
                synchronized(AppStore.products) { AppStore.products[p.key.value] = p }
                synchronized(AppStore.inventory) {
                    val currentInv = AppStore.inventory[p.key.value]
                    AppStore.inventory[p.key.value] = currentInv?.withUpdatedMaster(p.masterQuantity)
                        ?: OperationalInventory(p.key, stockMaestro = p.masterQuantity)
                }
            }

            importacionesRepo?.recordImport(
                id = "IMP-${java.util.UUID.randomUUID().toString().take(8).uppercase()}",
                usuario = "dashboard",
                hash = report.hashArchivo,
                estado = "COMPLETED",
                conteosJson = """{"creados":${report.creados.size},"actualizados":${report.actualizados.size},"sinCambios":${report.sinCambios.size}}""",
                erroresJson = "[]"
            )

        } catch (e: Exception) {
            val msg = "Error en transacción de BD al importar Excel: ${e.message}"
            logger.error(msg, e)
            dbErrors.add(msg)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "summary" to "Importación cancelada debido a un error de base de datos. Ningún dato fue modificado.",
                    "dbErrors" to dbErrors
                )
            )
            return@post
        }

        call.respond(
            HttpStatusCode.OK,
            mapOf(
                "summary" to "Importación confirmada y procesada exitosamente.",
                "importedCount" to parseResult.products.size,
                "creadosCount" to report.creados.size,
                "actualizadosCount" to report.actualizados.size,
                "sinCambiosCount" to report.sinCambios.size
            )
        )
    }

    // 5. Subida / Importación Directa de Excel a Supabase & AppStore (Fase C1 — N1 Fix)
    post("/api/excel/upload") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val multipart = call.receiveMultipart()
        var fileBytes: ByteArray? = null
        var tooLarge = false

        multipart.forEachPart { part ->
            if (part is PartData.FileItem && !tooLarge) {
                val bytes = part.streamProvider().readBytes()
                if (bytes.size > 5_242_880) {
                    tooLarge = true
                } else {
                    fileBytes = bytes
                }
            }
            part.dispose()
        }
        if (tooLarge) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "El archivo supera el límite de 5 MB"))
            return@post
        }

        if (fileBytes == null) {
            call.respond(HttpStatusCode.BadRequest, "No se recibió archivo .xlsx")
            return@post
        }

        val parseResult = excelParser.parseExcel(fileBytes!!.inputStream())

        if (parseResult.fileError != null || parseResult.errors.isNotEmpty()) {
            val allErrors = mutableListOf<String>()
            parseResult.fileError?.let { allErrors.add(it) }
            allErrors.addAll(parseResult.errors)
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "summary" to "Importación cancelada por errores en el Excel (${allErrors.size} error(es)). No se modificó ningún dato.",
                    "errors" to allErrors,
                    "warnings" to parseResult.warnings
                )
            )
            return@post
        }

        val dbErrors = mutableListOf<String>()

        try {
            if (dbRepository != null) {
                org.jetbrains.exposed.sql.transactions.transaction {
                    parseResult.products.forEach { p ->
                        val item = InventarioItem(
                            key = p.key,
                            item = p.itemName,
                            precioPublicado = p.publishedPrice,
                            precioMinimo = p.minimumPrice,
                            precioCompra = p.cost,
                            costoTotal = p.cost,
                            descripcion = p.description,
                            tipo = p.category,
                            marca = p.brand,
                            condicion = p.condition,
                            cantidad = p.masterQuantity,
                            ubicacion = p.location,
                            fotos = p.images,
                            estado = EstadoItem.desdeEtiqueta(p.status) ?: EstadoItem.PUBLICADO
                        )
                        dbRepository.upsert(item, usuario = "sistema", origen = Origen.IMPORT)
                    }
                }
            }

            parseResult.products.forEach { p ->
                synchronized(AppStore.products) { AppStore.products[p.key.value] = p }
                synchronized(AppStore.inventory) {
                    val currentInv = AppStore.inventory[p.key.value]
                    AppStore.inventory[p.key.value] = currentInv?.withUpdatedMaster(p.masterQuantity)
                        ?: OperationalInventory(p.key, stockMaestro = p.masterQuantity)
                }
            }

        } catch (e: Exception) {
            val msg = "Error en transacción de BD al importar Excel: ${e.message}"
            logger.error(msg, e)
            dbErrors.add(msg)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "summary" to "Importación cancelada debido a un error de base de datos. Ningún dato fue modificado.",
                    "dbErrors" to dbErrors
                )
            )
            return@post
        }

        val rowWarnings = parseResult.warnings.filter { it.startsWith("Hoja 'Inventario'") }
        if (rowWarnings.isEmpty() && dbErrors.isEmpty()) {
            call.respondRedirect("/")
            return@post
        }

        val imported = parseResult.products.size
        call.respond(
            HttpStatusCode.OK,
            mapOf(
                "summary" to listOf("Productos importados exitosamente: $imported."),
                "warnings" to parseResult.warnings
            )
        )
    }

    // 4. Exportación de Datos de Supabase a Excel (.xlsx)
    get("/api/excel/export") {
        if (!call.requireDashboardAuth(dashboardGuard)) return@get
        fun memoryItems(): List<InventarioItem> = synchronized(AppStore.products) { AppStore.products.values.toList() }.map { p ->
            InventarioItem(
                key = p.key,
                item = p.itemName,
                precioPublicado = p.publishedPrice,
                precioMinimo = p.minimumPrice,
                precioCompra = p.cost,
                costoTotal = p.cost,
                descripcion = p.description,
                tipo = p.category,
                marca = p.brand,
                condicion = p.condition,
                cantidad = p.masterQuantity,
                ubicacion = p.location,
                fotos = p.images,
                estado = EstadoItem.desdeEtiqueta(p.status) ?: EstadoItem.PUBLICADO
            )
        }
        val items: List<InventarioItem> = if (dbRepository != null) {
            try {
                dbRepository.findAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Export Excel: falló la consulta a la BD (${e.message}); se exportan los datos en memoria.", e)
                memoryItems()
            }
        } else {
            memoryItems()
        }

        val excelBytes = ExcelGenerator.exportToBytes(items)
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "inventario.xlsx").toString()
        )
        call.respondBytes(excelBytes, ContentType.Application.OctetStream)
    }
}
