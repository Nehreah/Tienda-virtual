package com.automata.commerce.service

import com.automata.commerce.model.Socio
import com.automata.commerce.ports.SocioRepository

enum class TipoRetiro { ELIMINADO, ARCHIVADO }

data class ImpactoSocio(
    val id: Long,
    val articulosEnStock: Int,
    val ventasParticipadas: Int,
    val esArchivable: Boolean
)

/**
 * Servicio de Dominio para el ciclo de vida de Socios dinámicos.
 */
class SocioService(
    private val socioRepo: SocioRepository,
    private val maxSociosActivos: Int = MAX_SOCIOS_ACTIVOS
) {
    fun validarNombre(nombre: String) {
        val trimmed = nombre.trim()
        require(trimmed.isNotEmpty()) { "El nombre del socio no puede estar vacío." }
        require(trimmed.length <= 60) { "El nombre del socio no puede superar 60 caracteres." }
    }

    suspend fun crear(nombre: String): Socio {
        validarNombre(nombre)
        val activos = socioRepo.listar(incluirArchivados = false)
        require(activos.size < maxSociosActivos) {
            "Se alcanzó el límite máximo de $maxSociosActivos socios activos."
        }
        val duplicado = activos.any { it.nombre.trim().equalsIgnoreCase(nombre.trim()) }
        require(!duplicado) { "Ya existe un socio activo con el nombre '${nombre.trim()}'." }
        return socioRepo.crear(nombre.trim())
    }

    suspend fun renombrar(id: Long, nuevoNombre: String): Socio {
        validarNombre(nuevoNombre)
        val activos = socioRepo.listar(incluirArchivados = false)
        val duplicado = activos.any { it.id != id && it.nombre.trim().equalsIgnoreCase(nuevoNombre.trim()) }
        require(!duplicado) { "Ya existe otro socio activo con el nombre '${nuevoNombre.trim()}'." }
        return socioRepo.renombrar(id, nuevoNombre.trim())
    }

    suspend fun evaluarImpacto(id: Long): ImpactoSocio {
        return socioRepo.impacto(id)
    }

    suspend fun retirar(id: Long): TipoRetiro {
        return socioRepo.retirar(id)
    }

    suspend fun reactivar(id: Long): Socio {
        val activos = socioRepo.listar(incluirArchivados = false)
        require(activos.size < maxSociosActivos) {
            "No se puede reactivar: se alcanzó el límite máximo de $maxSociosActivos socios activos."
        }
        return socioRepo.reactivar(id)
    }

    private fun String.equalsIgnoreCase(other: String): Boolean =
        this.equals(other, ignoreCase = true)

    companion object {
        const val MAX_SOCIOS_ACTIVOS = 12
    }
}
