package com.automata.commerce.service

import com.automata.commerce.ports.InMemorySocioRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SocioServiceTest {

    private lateinit var repo: InMemorySocioRepository
    private lateinit var service: SocioService

    @BeforeEach
    fun setup() {
        repo = InMemorySocioRepository()
        service = SocioService(repo)
    }

    @Test
    fun `crear socio con nombre valido`() = runBlocking {
        val s = service.crear("Carlos Giraldo")
        assertEquals(1L, s.id)
        assertEquals("Carlos Giraldo", s.nombre)
        assertTrue(s.activo)
    }

    @Test
    fun `rechaza nombre vacio o duplicado`() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.crear("   ") }
        }
        service.crear("Carlos Giraldo")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.crear("carlos giraldo") }
        }
    }

    @Test
    fun `respetar limite maximo de socios activos`() = runBlocking {
        val s = SocioService(repo, maxSociosActivos = 2)
        s.crear("Socio 1")
        s.crear("Socio 2")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { s.crear("Socio 3") }
        }
    }

    @Test
    fun `retirar socio sin ventas ni stock lo elimina`() = runBlocking {
        val socio = service.crear("Pedro")
        val res = service.retirar(socio.id)
        assertEquals(TipoRetiro.ELIMINADO, res)
        assertNull(repo.buscarPorId(socio.id))
    }
}
