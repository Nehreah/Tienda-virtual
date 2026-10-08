package com.automata.commerce.ports

import com.automata.commerce.model.Socio
import com.automata.commerce.service.ImpactoSocio
import com.automata.commerce.service.TipoRetiro

interface SocioRepository {
    suspend fun listar(incluirArchivados: Boolean = false): List<Socio>
    suspend fun buscarPorId(id: Long): Socio?
    suspend fun crear(nombre: String): Socio
    suspend fun renombrar(id: Long, nuevoNombre: String): Socio
    suspend fun impacto(id: Long): ImpactoSocio
    suspend fun retirar(id: Long): TipoRetiro
    suspend fun reactivar(id: Long): Socio
}
