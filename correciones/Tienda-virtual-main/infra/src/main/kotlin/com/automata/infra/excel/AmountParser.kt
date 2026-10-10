package com.automata.infra.excel

/** Resultado de leer un monto de una celda del Excel. Nunca se convierte un valor malo en 0. */
sealed interface AmountResult {
    /** Monto válido en pesos (puede ser 0: quien llama decide si 0 es aceptable). */
    data class Valid(val cop: Long) : AmountResult

    /** [reason] es un texto listo para mostrar al usuario (sin hoja ni fila: eso lo agrega el parser del Excel). */
    data class Invalid(val reason: String) : AmountResult
}

/**
 * Normaliza montos en pesos colombianos escritos en una celda (Fase 2.1).
 *
 * Acepta: `120000`, `120.000`, `120,000`, `120 000`, `$120000`, `$ 120.000`, `COP 120.000`, `120.000,00`, `120000.0`
 * (la parte decimal solo se admite si es cero: los precios son enteros).
 * Rechaza con un motivo claro: vacío, "Consultar" u otro texto, negativos, decimales distintos de cero,
 * separadores mezclados (`1.200,000`) y valores absurdamente grandes.
 */
object AmountParser {

    /** Tope de cordura (1 billón de pesos). Evita desbordes al multiplicar precios en otras partes del sistema. */
    const val MAX_COP: Long = 1_000_000_000_000L

    private val SPACES = Regex("[\\s\\u00A0\\u202F\\u2009]+")
    private val COP_WORD = Regex("\\bcop\\b", RegexOption.IGNORE_CASE)

    // Grupos de miles con el mismo separador (120.000 / 1,200,000) o solo dígitos; después, opcionalmente, ",00" o ".0".
    private val FORMAT = Regex("^(\\d{1,3}(?:[.,]\\d{3})+|\\d+)(?:[.,]0{1,2})?$")
    private val WITH_DECIMALS = Regex("^\\d+[.,]\\d{1,2}$")

    fun parse(raw: String?): AmountResult {
        var text = (raw ?: "").trim()
        // ExcelV2Parser antepone ' a los textos que empiezan con = + - @ (defensa contra fórmulas): aquí se descarta esa marca.
        if (text.startsWith("'")) text = text.substring(1)
        val shown = display(text)

        text = text.replace("$", "").replace(COP_WORD, "").replace(SPACES, "")

        if (text.isEmpty()) {
            return AmountResult.Invalid("está vacío; escribe el monto en pesos, por ejemplo 120000 o \$120.000")
        }
        if (text.startsWith("-")) {
            return AmountResult.Invalid("no puede ser negativo ('$shown')")
        }

        val match = FORMAT.matchEntire(text)
        if (match == null) {
            if (WITH_DECIMALS.matches(text)) {
                return AmountResult.Invalid("'$shown' tiene decimales; usa un valor entero en pesos")
            }
            return AmountResult.Invalid("'$shown' no es un monto válido; usa solo números, por ejemplo 120000, 120.000 o \$120.000")
        }

        val integerPart = match.groupValues[1]
        val separators = integerPart.filter { it == '.' || it == ',' }.toSet()
        if (separators.size > 1) {
            return AmountResult.Invalid("'$shown' mezcla puntos y comas como separador de miles")
        }

        val value = integerPart.replace(".", "").replace(",", "").toLongOrNull()
        if (value == null || value > MAX_COP) {
            return AmountResult.Invalid("'$shown' es demasiado grande")
        }
        return AmountResult.Valid(value)
    }

    /** Texto corto y de una sola línea para citar el valor original dentro de un mensaje de error. */
    private fun display(text: String): String =
        text.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(30)
}
