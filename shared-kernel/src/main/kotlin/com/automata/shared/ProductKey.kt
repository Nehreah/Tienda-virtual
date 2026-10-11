package com.automata.shared

import kotlinx.serialization.Serializable

/**
 * Identificador único e inmutable del producto en la Matriz Comercial (Excel).
 * Ejemplo: "PROD-001" o "A-0412".
 *
 * POLÍTICA DE CLAVES (v11)
 * Una clave NUEVA (alta manual, importación de Excel) debe cumplir [validar]:
 *  - entre 1 y [MAX_LARGO] caracteres, sin espacios al principio ni al final;
 *  - solo letras, dígitos, guion (-), guion bajo (_) y punto (.), sin espacios en medio;
 *  - empezar con letra o dígito (así nunca es "." ni "..").
 * Con eso la clave es siempre un único segmento de URL seguro: no puede contener "/", "\", "?", "#", "%",
 * comillas ni caracteres de control, que son los que rompían el enrutamiento de las rutas /api/.../{key}.
 *
 * El constructor sigue siendo permisivo (solo exige que no esté vacía) A PROPÓSITO: se usa para LEER claves que ya
 * existen en la base o que llegan de fuera (SKU de Shopify, reservas, conversaciones) y no debe romper esas lecturas.
 * Quien CREE una clave debe pasar por [parse] o [validar]; la base lo refuerza con la restricción
 * ck_inventario_key_formato (V005), que aplica solo a filas nuevas o modificadas.
 */
@Serializable
data class ProductKey(val value: String) {
    init {
        require(value.isNotBlank()) { "ProductKey no puede estar vacío" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LARGO = 64

        /** Letras y dígitos Unicode al inicio; luego letras, dígitos, '.', '_' o '-'. */
        private val FORMATO = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}._-]*$")

        /** Devuelve null si [raw] es una clave válida para ALTAS; si no, el mensaje que se le muestra a la persona. */
        fun validar(raw: String?): String? {
            val texto = raw?.trim().orEmpty()
            return when {
                texto.isEmpty() -> "La clave del artículo es obligatoria."
                texto.length > MAX_LARGO -> "La clave no puede superar $MAX_LARGO caracteres."
                !FORMATO.matches(texto) ->
                    "La clave solo admite letras, números, guion (-), guion bajo (_) y punto (.), sin espacios, " +
                        "y debe empezar con letra o número (ej. PROD-001)."
                else -> null
            }
        }

        /** Crea una clave aplicando la política de altas. Lanza IllegalArgumentException con el mensaje de [validar]. */
        fun parse(raw: String?): ProductKey {
            validar(raw)?.let { throw IllegalArgumentException(it) }
            return ProductKey(raw!!.trim())
        }

        /** Como [parse], pero devuelve null en vez de lanzar. */
        fun parseOrNull(raw: String?): ProductKey? = if (validar(raw) == null) ProductKey(raw!!.trim()) else null
    }
}
