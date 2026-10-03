package com.automata.commerce.model

/**
 * Periodo de venta según los días que tardó en venderse (contados desde la COMPRA).
 *
 *   corto : menos de 15 días          (0 a 14)
 *   medio : de 15 a 29 días
 *   largo : 30 días o más
 *
 * Es el espejo de tienda.fn_periodo() en la base de datos (umbrales en tienda.config:
 * periodo_corto_dias = 15, periodo_medio_dias = 30). Si cambias los umbrales en la base,
 * cámbialos también aquí o pásalos como parámetro.
 */
enum class Periodo(val etiqueta: String) {
    CORTO("corto"),
    MEDIO("medio"),
    LARGO("largo");

    companion object {
        const val LIMITE_CORTO_DIAS = 15
        const val LIMITE_MEDIO_DIAS = 30

        fun desdeDias(
            dias: Int,
            limiteCorto: Int = LIMITE_CORTO_DIAS,
            limiteMedio: Int = LIMITE_MEDIO_DIAS
        ): Periodo = when {
            dias < limiteCorto -> CORTO
            dias < limiteMedio -> MEDIO
            else -> LARGO
        }

        fun desdeEtiqueta(texto: String): Periodo? {
            val buscado = texto.trim().lowercase()
            return entries.firstOrNull { it.etiqueta == buscado }
        }
    }
}
