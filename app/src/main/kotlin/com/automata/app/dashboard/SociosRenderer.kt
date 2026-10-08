package com.automata.app.dashboard

import com.automata.commerce.model.Socio
import com.automata.commerce.service.ImpactoSocio

object SociosRenderer {

    fun renderVistaSocios(
        sociosActivos: List<Socio>,
        sociosArchivados: List<Socio>,
        mostrarArchivados: Boolean = false,
        error: String? = null
    ): String = buildString {
        append("""
            <div class="space-y-6">
                <div class="flex justify-between items-center bg-white p-6 rounded-lg shadow-sm border border-gray-200">
                    <div>
                        <h2 class="text-xl font-bold text-gray-800">Gestión de Socios</h2>
                        <p class="text-sm text-gray-500 mt-1">
                            ${sociosActivos.size} socio(s) activo(s)${if (sociosArchivados.isNotEmpty()) " · ${sociosArchivados.size} archivado(s)" else ""}
                        </p>
                    </div>
                    <div class="flex space-x-3">
                        <button onclick="document.getElementById('modal-nuevo-socio').classList.remove('hidden')"
                                class="bg-indigo-600 hover:bg-indigo-700 text-white px-4 py-2 rounded-md font-medium text-sm transition">
                            + Agregar Socio
                        </button>
                    </div>
                </div>
        """.trimIndent())

        if (!error.isNullOrBlank()) {
            append("""
                <div class="bg-red-50 border-l-4 border-red-500 p-4 text-red-700 text-sm rounded">
                    ${DashboardRenderer.escapeHtml(error)}
                </div>
            """.trimIndent())
        }

        if (sociosActivos.isEmpty() && sociosArchivados.isEmpty()) {
            append("""
                <div class="bg-indigo-50 border border-indigo-200 p-8 rounded-lg text-center">
                    <h3 class="text-lg font-semibold text-indigo-900 mb-2">Bienvenido a la gestión de socios</h3>
                    <p class="text-indigo-700 text-sm max-w-md mx-auto mb-4">
                        Agrega a tu primer socio para comenzar a distribuir la inversión de los artículos en el inventario.
                    </p>
                    <button onclick="document.getElementById('modal-nuevo-socio').classList.remove('hidden')"
                            class="bg-indigo-600 hover:bg-indigo-700 text-white px-6 py-2 rounded-md font-medium text-sm transition">
                        + Agregar mi primer socio
                    </button>
                </div>
            """.trimIndent())
        } else {
            append("""
                <div class="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden">
                    <table class="min-w-full divide-y divide-gray-200 text-sm">
                        <thead class="bg-gray-50 text-gray-600 uppercase text-xs">
                            <tr>
                                <th class="px-6 py-3 text-left">ID</th>
                                <th class="px-6 py-3 text-left">Nombre</th>
                                <th class="px-6 py-3 text-left">Estado</th>
                                <th class="px-6 py-3 text-right">Acciones</th>
                            </tr>
                        </thead>
                        <tbody class="divide-y divide-gray-200">
            """.trimIndent())

            sociosActivos.forEach { socio ->
                append("""
                    <tr class="hover:bg-gray-50">
                        <td class="px-6 py-4 font-mono text-gray-500">#${socio.id}</td>
                        <td class="px-6 py-4 font-semibold text-gray-800">${DashboardRenderer.escapeHtml(socio.nombre)}</td>
                        <td class="px-6 py-4">
                            <span class="px-2 py-1 text-xs font-semibold rounded-full bg-green-100 text-green-800">Activo</span>
                        </td>
                        <td class="px-6 py-4 text-right space-x-2">
                            <button hx-get="/api/socios/${socio.id}/impacto"
                                    hx-target="#modal-container"
                                    class="text-red-600 hover:text-red-800 font-medium text-xs">
                                Eliminar / Archivar
                            </button>
                        </td>
                    </tr>
                """.trimIndent())
            }

            if (mostrarArchivados) {
                sociosArchivados.forEach { socio ->
                    append("""
                        <tr class="bg-gray-50 text-gray-400">
                            <td class="px-6 py-4 font-mono">#${socio.id}</td>
                            <td class="px-6 py-4 italic">${DashboardRenderer.escapeHtml(socio.nombre)}</td>
                            <td class="px-6 py-4">
                                <span class="px-2 py-1 text-xs font-semibold rounded-full bg-gray-200 text-gray-700">Archivado</span>
                            </td>
                            <td class="px-6 py-4 text-right">
                                <button hx-post="/api/socios/${socio.id}/reactivar"
                                        hx-target="#main-content"
                                        class="text-indigo-600 hover:text-indigo-800 font-medium text-xs">
                                    Reactivar
                                </button>
                            </td>
                        </tr>
                    """.trimIndent())
                }
            }

            append("""
                        </tbody>
                    </table>
                </div>
            """.trimIndent())
        }

        // Modal Form
        append("""
            <div id="modal-nuevo-socio" class="hidden fixed inset-0 bg-gray-600 bg-opacity-50 flex items-center justify-center p-4">
                <div class="bg-white rounded-lg p-6 max-w-md w-full shadow-xl">
                    <h3 class="text-lg font-bold text-gray-800 mb-4">Agregar Nuevo Socio</h3>
                    <form hx-post="/api/socios" hx-target="#main-content">
                        <div class="mb-4">
                            <label class="block text-sm font-medium text-gray-700 mb-1">Nombre del Socio</label>
                            <input type="text" name="nombre" required maxlength="60"
                                   class="w-full border border-gray-300 rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"/>
                        </div>
                        <div class="flex justify-end space-x-3">
                            <button type="button" onclick="document.getElementById('modal-nuevo-socio').classList.add('hidden')"
                                    class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded">Cancelar</button>
                            <button type="submit" class="px-4 py-2 text-sm bg-indigo-600 text-white rounded font-medium hover:bg-indigo-700">Guardar</button>
                        </div>
                    </form>
                </div>
            </div>
            <div id="modal-container"></div>
        </div>
        """.trimIndent())
    }

    fun renderModalImpacto(impacto: ImpactoSocio, nombre: String): String = buildString {
        append("""
            <div id="modal-impacto" class="fixed inset-0 bg-gray-600 bg-opacity-50 flex items-center justify-center p-4">
                <div class="bg-white rounded-lg p-6 max-w-md w-full shadow-xl">
                    <h3 class="text-lg font-bold text-gray-800 mb-2">Confirmar Retiro de Socio</h3>
                    <p class="text-sm text-gray-600 mb-4">
                        Socio: <strong class="text-gray-800">${DashboardRenderer.escapeHtml(nombre)}</strong>
                    </p>
                    <div class="bg-amber-50 border-l-4 border-amber-500 p-4 text-amber-800 text-xs rounded mb-4 space-y-1">
                        <p>• Participa en <strong>${impacto.articulosEnStock}</strong> artículo(s) sin vender.</p>
                        <p>• Participó en <strong>${impacto.ventasParticipadas}</strong> venta(s) histórica(s).</p>
                        <p class="mt-2 font-semibold">
                            ${if (impacto.esArchivable) "El socio se archivará para conservar el historial de ventas." else "El socio no tiene ventas ni stock, por lo que será ELIMINADO definitivamente."}
                        </p>
                    </div>
                    <div class="flex justify-end space-x-3">
                        <button onclick="document.getElementById('modal-impacto').remove()"
                                class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded">Cancelar</button>
                        <button hx-delete="/api/socios/${impacto.id}" hx-target="#main-content"
                                class="px-4 py-2 text-sm bg-red-600 text-white rounded font-medium hover:bg-red-700">Confirmar Retiro</button>
                    </div>
                </div>
            </div>
        """.trimIndent())
    }
}
