package com.automata.infra.db

import com.automata.commerce.model.Socio
import com.automata.commerce.ports.SocioRepository
import com.automata.commerce.service.ImpactoSocio
import com.automata.commerce.service.TipoRetiro
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class ExposedSocioRepository : SocioRepository {

    override suspend fun listar(incluirArchivados: Boolean): List<Socio> = DatabaseFactory.tx("listar_socios", "system") {
        val query = if (incluirArchivados) {
            SociosTable.selectAll()
        } else {
            SociosTable.select { SociosTable.activo eq true }
        }
        query.orderBy(SociosTable.id to SortOrder.ASC).map { toSocio(it) }
    }

    override suspend fun buscarPorId(id: Long): Socio? = DatabaseFactory.tx("buscar_socio", "system") {
        SociosTable.select { SociosTable.id eq id }.singleOrNull()?.let { toSocio(it) }
    }

    override suspend fun crear(nombre: String): Socio = DatabaseFactory.tx("crear_socio", "system") {
        val stmt = SociosTable.insert {
            it[SociosTable.nombre] = nombre.trim()
            it[activo] = true
        }
        val id = stmt[SociosTable.id]
        SociosTable.select { SociosTable.id eq id }.single().let { toSocio(it) }
    }

    override suspend fun renombrar(id: Long, nuevoNombre: String): Socio = DatabaseFactory.tx("renombrar_socio", "system") {
        SociosTable.update({ SociosTable.id eq id }) {
            it[nombre] = nuevoNombre.trim()
        }
        SociosTable.select { SociosTable.id eq id }.single().let { toSocio(it) }
    }

    override suspend fun impacto(id: Long): ImpactoSocio = DatabaseFactory.tx("impacto_socio", "system") {
        val stockCount = InventarioInversionTable.innerJoin(InventarioTable)
            .select { (InventarioInversionTable.socioId eq id) and (InventarioTable.estado neq "Vendido") }
            .count().toInt()

        val ventasCount = VentaRepartoTable.select { VentaRepartoTable.socioId eq id }.count().toInt()
        val archivable = ventasCount > 0 || stockCount > 0

        ImpactoSocio(
            id = id,
            articulosEnStock = stockCount,
            ventasParticipadas = ventasCount,
            esArchivable = archivable
        )
    }

    override suspend fun retirar(id: Long): TipoRetiro = DatabaseFactory.tx("retirar_socio", "system") {
        var resStr = "ELIMINADO"
        exec("SELECT tienda.retirar_socio($id) AS res") { rs ->
            if (rs.next()) {
                resStr = rs.getString("res") ?: "ELIMINADO"
            }
        }
        if (resStr == "ARCHIVADO") TipoRetiro.ARCHIVADO else TipoRetiro.ELIMINADO
    }

    override suspend fun reactivar(id: Long): Socio = DatabaseFactory.tx("reactivar_socio", "system") {
        SociosTable.update({ SociosTable.id eq id }) {
            it[activo] = true
            it[archivadoEn] = null
        }
        SociosTable.select { SociosTable.id eq id }.single().let { toSocio(it) }
    }

    private fun toSocio(row: ResultRow): Socio = Socio(
        id = row[SociosTable.id],
        nombre = row[SociosTable.nombre],
        activo = row[SociosTable.activo],
        archivadoEn = row[SociosTable.archivadoEn]
    )
}
