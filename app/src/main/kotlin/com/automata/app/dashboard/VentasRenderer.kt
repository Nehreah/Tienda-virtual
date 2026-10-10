package com.automata.app.dashboard

object VentasRenderer {

    fun renderVentasPage(
        salesHtml: String,
        syncPanelHtml: String
    ): String = """
        <div class="stack">
            <header class="view-header">
                <div class="view-title">
                    <h2>Ventas y Gestión de Logística</h2>
                    <p class="muted">Registra ventas manuales, actualiza envíos y gestiona la sincronización con canales.</p>
                </div>
                <button type="button" class="btn btn-primary" onclick="document.getElementById('modal-nueva-venta').showModal()">
                    <span class="nav-icon">+</span> Registrar Venta Manual
                </button>
            </header>

            <!-- Panel de Sincronización de Catálogos -->
            <section class="card card-pad stack">
                <h3>Estado de Sincronización de Catálogos (Outbox)</h3>
                $syncPanelHtml
            </section>

            <!-- Listado de Ventas -->
            <section class="card card-pad stack">
                <h3>Historial de Ventas</h3>
                $salesHtml
            </section>
        </div>

        <!-- Modal Registrar Venta Manual -->
        <dialog id="modal-nueva-venta" class="modal">
            <div class="modal-content card card-pad stack">
                <header class="modal-header">
                    <h3>Registrar Venta Manual</h3>
                    <button type="button" class="btn icon-btn" onclick="document.getElementById('modal-nueva-venta').close()">&times;</button>
                </header>
                <form id="form-venta-manual" hx-post="/api/ventas" hx-target="#mensaje-venta-respuesta" class="stack mt-2">
                    <input type="hidden" name="idempotencyKey" value="MAN-${'$'}{java.util.UUID.randomUUID()}">
                    
                    <div class="form-row">
                        <label>Clave del Producto (SKU)</label>
                        <input type="text" name="key" required class="input" placeholder="PROD-001">
                    </div>
                    
                    <div class="form-group-row">
                        <div class="form-row flex-1">
                            <label>Cantidad</label>
                            <input type="number" name="cantidad" min="1" value="1" required class="input">
                        </div>
                        <div class="form-row flex-1">
                            <label>Precio Unitario (COP)</label>
                            <input type="number" name="precioUnitario" min="0" step="1000" required class="input" placeholder="100000">
                        </div>
                    </div>
                    
                    <div class="form-row">
                        <label>Canal de Venta</label>
                        <select name="canal" class="input" onchange="window.toggleCanalDetalle(this.value)">
                            <option value="directo" selected>Venta Directa (Física/WhatsApp manual)</option>
                            <option value="shopify">Shopify</option>
                            <option value="otro">Otro Canal Externo</option>
                        </select>
                    </div>
                    
                    <div id="campo-order-id" class="form-row" hidden>
                        <label>ID de Orden en Shopify</label>
                        <input type="text" name="orderId" class="input" placeholder="1001">
                    </div>
                    
                    <div id="campo-canal-detalle" class="form-row" hidden>
                        <label>Especificar Canal (Si es 'Otro')</label>
                        <input type="text" name="canalDetalle" class="input" placeholder="Ej. MercadoLibre">
                    </div>
                    
                    <div class="form-row">
                        <label>Estado Logístico Inicial</label>
                        <select name="estadoLogistico" class="input">
                            <option value="PENDIENTE_ENVIO" selected>Pendiente de Envío</option>
                            <option value="ENVIADO">Enviado</option>
                            <option value="ENTREGADO">Entregado</option>
                            <option value="CANCELADO">Cancelado / Devuelto</option>
                        </select>
                    </div>
                    
                    <div id="mensaje-venta-respuesta"></div>
                    
                    <footer class="modal-footer mt-4">
                        <button type="button" class="btn" onclick="document.getElementById('modal-nueva-venta').close()">Cancelar</button>
                        <button type="submit" class="btn btn-primary">Registrar Venta</button>
                    </footer>
                </form>
            </div>
        </dialog>
    """.trimIndent()
}
