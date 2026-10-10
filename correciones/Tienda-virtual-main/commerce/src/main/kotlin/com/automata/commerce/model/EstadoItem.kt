package com.automata.commerce.model

import java.text.Normalizer

/**
 * Estados reales de un producto (confirmados por el usuario):
 * Comprado, Publicado, Negociación, Vendido y Otro.
 * "etiqueta" es el texto exacto guardado en tienda.cat_estado / tienda.inventario.estado.
 */
enum class EstadoItem(val etiqueta: String, val esFinal: Boolean = false) {
    COMPRADO("Comprado"),
    PUBLICADO("Publicado"),
    NEGOCIACION("Negociación"),
    VENDIDO("Vendido", esFinal = true),
    INACTIVO("Inactivo"),
    OTRO("Otro");

    companion object {
        private fun normalizar(texto: String): String =
            Normalizer.normalize(texto.trim(), Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                .lowercase()

        /** Acepta mayúsculas/minúsculas y con o sin tilde ("negociacion" == "Negociación"). */
        fun desdeEtiqueta(texto: String): EstadoItem? {
            val buscado = normalizar(texto)
            return entries.firstOrNull { normalizar(it.etiqueta) == buscado }
        }
    }
}
