package com.automata.commerce.ports

import com.automata.commerce.model.Inversion
import com.automata.shared.ProductKey

/** Puerto de la TABLA INVERSIÓN (porcentaje de cada socio por Key). */
interface InversionRepository {
    fun find(key: ProductKey): Inversion?

    /** Crea o actualiza. Lanza IllegalArgumentException si la Key no existe en Inventario. */
    fun guardar(inv: Inversion, usuario: String?, origen: String): Inversion
}
