package com.automata.app.dashboard

/**
 * Límite de intentos fallidos de autenticación por cliente (IP).
 *
 * - [maxFailures] fallos dentro de [windowMillis] bloquean al cliente. Mientras dura el bloqueo se responde 429
 *   aunque la clave sea correcta.
 * - La espera crece al repetirse: [baseLockMillis], el doble, el cuádruple... hasta [maxLockMillis].
 * - Solo se llama a [recordFailure] cuando la petición TRAJO credenciales incorrectas. Una petición sin cabecera
 *   Authorization (el primer desafío del navegador) no cuenta.
 * - Memoria acotada: como máximo [maxTrackedClients] clientes; al llenarse se descartan los más antiguos.
 * - [clock] se puede inyectar para probar sin esperar.
 */
class AuthThrottle(
    private val maxFailures: Int = 5,
    private val windowMillis: Long = 5 * 60_000L,
    private val baseLockMillis: Long = 60_000L,
    private val maxLockMillis: Long = 60 * 60_000L,
    private val maxTrackedClients: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Entry {
        val failures = java.util.ArrayDeque<Long>()
        var lockedUntil = 0L
        var strikes = 0
        var lastSeen = 0L
    }

    private val entries = HashMap<String, Entry>()

    /** Segundos que el cliente debe esperar; 0 si puede intentar. */
    @Synchronized
    fun retryAfterSeconds(client: String): Long {
        val entry = entries[client] ?: return 0L
        val remaining = entry.lockedUntil - clock()
        return if (remaining > 0) (remaining + 999) / 1000 else 0L
    }

    @Synchronized
    fun recordFailure(client: String) {
        val now = clock()
        if (client !in entries && entries.size >= maxTrackedClients) makeRoom(now)
        val entry = entries.getOrPut(client) { Entry() }

        // Si estuvo inactivo más que la espera máxima (contada desde el último fallo y desde que terminó el bloqueo),
        // empieza de cero: se olvidan las reincidencias.
        if (isIdle(entry, now)) entry.strikes = 0
        entry.lastSeen = now

        // Ya bloqueado: los intentos durante el bloqueo no lo alargan ni acumulan.
        if (entry.lockedUntil > now) return

        while (entry.failures.isNotEmpty() && now - entry.failures.peekFirst() > windowMillis) entry.failures.pollFirst()
        entry.failures.addLast(now)

        if (entry.failures.size >= maxFailures) {
            entry.strikes++
            val lock = (baseLockMillis shl (entry.strikes - 1).coerceAtMost(20)).coerceAtMost(maxLockMillis)
            entry.lockedUntil = now + lock
            entry.failures.clear()
        }
    }

    private fun isIdle(entry: Entry, now: Long): Boolean =
        now - entry.lastSeen > maxLockMillis && now - entry.lockedUntil > maxLockMillis

    private fun makeRoom(now: Long) {
        entries.entries.removeIf { (_, e) -> isIdle(e, now) }
        if (entries.size >= maxTrackedClients) {
            entries.minByOrNull { it.value.lastSeen }?.key?.let { entries.remove(it) }
        }
    }
}
