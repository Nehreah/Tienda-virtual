package com.automata.app.dashboard

object VentasRenderer {

    fun renderVentasPage(
        salesHtml: String,
        syncPanelHtml: String
    ): String = """
        <div class="p-6 max-w-7xl mx-auto space-y-8">
            <div class="flex justify-between items-center">
                <div>
                    <h1 class="text-2xl font-bold text-gray-900">Ventas y Gestión de Logística</h1>
                    <p class="text-sm text-gray-500">Registra ventas manuales, actualiza envíos y gestiona la sincronización con canales.</p>
                </div>
                <button onclick="document.getElementById('modal-nueva-venta').classList.remove('hidden')" 
                        class="bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium px-4 py-2 rounded-lg shadow-sm transition flex items-center gap-2">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"/></svg>
                    Registrar Venta Manual
                </button>
            </div>

            <!-- Panel de Sincronización de Catálogos -->
            <div class="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
                <h2 class="text-lg font-semibold text-gray-900 mb-4 flex items-center gap-2">
                    <svg class="w-5 h-5 text-indigo-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/></svg>
                    Estado de Sincronización de Catálogos (Outbox)
                </h2>
                $syncPanelHtml
            </div>

            <!-- Listado de Ventas -->
            <div class="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
                <div class="flex justify-between items-center mb-4">
                    <h2 class="text-lg font-semibold text-gray-900">Historial de Ventas</h2>
                </div>
                $salesHtml
            </div>
        </div>

        <!-- Modal Registrar Venta Manual -->
        <div id="modal-nueva-venta" class="hidden fixed inset-0 bg-gray-600 bg-opacity-50 overflow-y-auto h-full w-full z-50">
            <div class="relative top-20 mx-auto p-5 border w-full max-w-lg shadow-lg rounded-xl bg-white">
                <div class="flex justify-between items-center pb-3 border-b border-gray-200">
                    <h3 class="text-lg font-bold text-gray-900">Registrar Venta Manual</h3>
                    <button onclick="document.getElementById('modal-nueva-venta').classList.add('hidden')" class="text-gray-400 hover:text-gray-600">&times;</button>
                </div>
                <form id="form-venta-manual" hx-post="/api/ventas" hx-target="#mensaje-venta-respuesta" class="mt-4 space-y-4">
                    <input type="hidden" name="idempotencyKey" value="MAN-${java.util.UUID.randomUUID()}">
                    <div>
                        <label class="block text-xs font-semibold text-gray-700 uppercase">Clave del Producto (SKU)</label>
                        <input type="text" name="key" required class="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 text-sm p-2 border" placeholder="PROD-001">
                    </div>
                    <div class="grid grid-cols-2 gap-4">
                        <div>
                            <label class="block text-xs font-semibold text-gray-700 uppercase">Cantidad</label>
                            <input type="number" name="cantidad" min="1" value="1" required class="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 text-sm p-2 border">
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-gray-700 uppercase">Precio Unitario (COP)</label>
                            <input type="number" name="precioUnitario" min="0" step="1000" required class="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 text-sm p-2 border" placeholder="100000">
                        </div>
                    </div>
                    <div class="grid grid-cols-2 gap-4">
                        <div>
                            <label class="block text-xs font-semibold text-gray-700 uppercase">Canal de Venta</label>
                            <select name="canal" required class="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 text-sm p-2 border" onchange="toggleCanalDetalle(this.value)">
                                <option value="presencial">Presencial</option>
                                <option value="whatsapp">WhatsApp</option>
                                <option value="instagram">Instagram</option>
                                <option value="facebook_marketplace">Facebook Marketplace</option>
                                <option value="shopify">Shopify</option>
                                <option value="otro">Otro</option>
                            </select>
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-gray-700 uppercase">Estado Logístico</label>
                            <select name="estadoLogistico" required class="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 text-sm p-2 border">
                                <option value="SIN_ENVIO">Sin Envío (Entregado)</option>
                                <option value="PENDIENTE_ENVIO">Pendiente de Envío</option>
                                <option value="EN_PREPARACION">En Preparación</option>
                                <option value="ENVIADO">Enviado</option>
                                <option value="ENTREGADO">Entregado</option>
                            </select>
                        </div>
                    </div>
                    <div id="campo-canal-detalle" class="hidden">
                        <label class="block text-xs font-semibold text-gray-700 uppercase">Detalle del Canal (si eligió Otro)</label>
                        <input type="text" name="canalDetalle" class="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 text-sm p-2 border" placeholder="Ej: Venta telefónica directa">
                    </div>
                    <div id="campo-order-id" class="hidden">
                        <label class="block text-xs font-semibold text-gray-700 uppercase">Número de Pedido Shopify (Requerido)</label>
                        <input type="text" name="orderId" class="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 text-sm p-2 border" placeholder="Ej: #1002">
                    </div>
                    <div>
                        <label class="block text-xs font-semibold text-gray-700 uppercase">Nota / Comentarios</label>
                        <textarea name="nota" rows="2" class="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 text-sm p-2 border" placeholder="Detalles de pago o entrega..."></textarea>
                    </div>
                    <div id="mensaje-venta-respuesta" class="text-sm"></div>
                    <div class="pt-3 border-t border-gray-200 flex justify-end gap-2">
                        <button type="button" onclick="document.getElementById('modal-nueva-venta').classList.add('hidden')" class="px-4 py-2 text-sm text-gray-600 hover:text-gray-800">Cancelar</button>
                        <button type="submit" class="px-4 py-2 text-sm font-medium text-white bg-indigo-600 hover:bg-indigo-700 rounded-lg shadow-sm">Registrar Venta</button>
                    </div>
                </form>
            </div>
        </div>

        <script>
            function toggleCanalDetalle(canal) {
                document.getElementById('campo-canal-detalle').classList.toggle('hidden', canal !== 'otro');
                document.getElementById('campo-order-id').classList.toggle('hidden', canal !== 'shopify');
            }
        </script>
    """.trimIndent()
}
