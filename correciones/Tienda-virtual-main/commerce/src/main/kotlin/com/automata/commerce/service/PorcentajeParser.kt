package com.automata.commerce.service

import java.math.BigDecimal

sealed class PorcentajeResult {
    data class Valid(val value: BigDecimal) : PorcentajeResult()
    object Remove : PorcentajeResult()
    data class Error(val message: String) : PorcentajeResult()
}

object PorcentajeParser {
    fun parse(raw: String): PorcentajeResult {
        val trimmed = raw.trim().removeSuffix("%").trim()
        if (trimmed.isEmpty()) {
            return PorcentajeResult.Remove
        }

        val normalized = trimmed.replace(',', '.')
        // Validar formato numérico con máximo 2 decimales
        if (!normalized.matches(Regex("""^\d+(\.\d{1,2})?$"""))) {
            if (normalized.contains('.') && normalized.substringAfter('.').length > 2) {
                return PorcentajeResult.Error("El porcentaje no puede tener más de 2 decimales.")
            }
            return PorcentajeResult.Error("Porcentaje inválido: '$raw'. Debe ser un número entre 0 y 100.")
        }

        val parsed = normalized.toBigDecimalOrNull()
            ?: return PorcentajeResult.Error("Porcentaje inválido: '$raw'.")

        if (parsed < BigDecimal.ZERO || parsed > BigDecimal("100")) {
            return PorcentajeResult.Error("El porcentaje debe estar entre 0 y 100.")
        }

        return PorcentajeResult.Valid(parsed.setScale(2))
    }
}
