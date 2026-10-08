package com.automata.commerce.ports

import com.automata.commerce.model.Socio
import com.automata.commerce.service.ImpactoSocio
import com.automata.commerce.service.TipoRetiro
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

open class InMemorySocioRepository : SocioRepository {
    private val nextId = AtomicLong(1L)
    private val store = ConcurrentHashMap<Long, Socio>()

    override suspend fun listar(incluirArchivados: Boolean): List<Socio> =
        store.values.filter { incluirArchivados || it.activo }.sortedBy { it.id }

    override suspend fun buscarPorId(id: Long): Socio? = store[id]

    override suspend fun crear(nombre: String): Socio {
        val id = nextId.getAndIncrement()
        val socio = Socio(id = id, nombre = nombre.trim(), activo = true)
        store[id] = socio
        return socio
    }

    override suspend fun renombrar(id: Long, nuevoNombre: String): Socio {
        val actual = store[id] ?: throw NoSuchElementException("Socio $id no encontrado")
        val actualizado = actual.copy(nombre = nuevoNombre.trim())
        store[id] = actualizado
        return actualizado
    }

    override suspend fun impacto(id: Long): ImpactoSocio {
        return ImpactoSocio(id = id, articulosEnStock = 0, ventasParticipadas = 0, esArchivable = false)
    }

    override suspend fun retirar(id: Long): TipoRetiro {
        val actual = store[id] ?: return TipoRetiro.ELIMINADO
        val impact = impacto(id)
        return if (impact.esArchivable) {
            store[id] = actual.copy(activo = false, archivadoEn = Instant.now())
            TipoRetiro.ARCHIVADO
        } else {
            store.remove(id)
            TipoRetiro.ELIMINADO
        }
    }

    override suspend fun reactivar(id: Long): Socio {
        val actual = store[id] ?: throw NoSuchElementException("Socio $id no encontrado")
        val reactivado = actual.copy(activo = true, archivadoEn = null)
        store[id] = reactivado
        return reactivado
    }
}
