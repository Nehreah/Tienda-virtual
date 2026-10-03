package com.automata.commerce.model

import java.time.Instant

/**
 * Una línea del historial de auditoría (tienda.historial). La llena la base con triggers.
 * cambiosJson guarda solo lo que cambió: {"campo": {"antes": ..., "despues": ...}}
 */
data class HistorialEntry(
    val id: Long,
    val tabla: String,
    val key: String,
    val operacion: String,     // INSERT | UPDATE | DELETE
    val cambiosJson: String,
    val origen: String,        // dashboard | shopify | import | supabase
    val usuario: String?,
    val creadoEn: Instant
)
