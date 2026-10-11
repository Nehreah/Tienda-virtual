package com.automata.app.dashboard

/**
 * Ventas manuales y sincronización de catálogos.
 *
 * Solo usa CSS local (ui-*, btn, module-heading de [DashboardRenderer]) y no lleva JavaScript ni handlers en línea
 * (la CSP los bloquea): dashboard.js abre el modal con [data-modal-open], lo cierra con [data-modal-close] y
 * muestra/oculta los campos según el canal con [data-canal-toggle].
 */
object VentasRenderer {

    fun renderVentasPage(
        salesHtml: String,
        syncPanelHtml: String
    ): String = """
        <div class="ui-stack">
            <div class="module-heading">
                <div>
                    <h2>Ventas y Gestión de Logística</h2>
                    <p>Registra ventas manuales, actualiza envíos y gestiona la sincronización con canales.</p>
                </div>
                <button type="button" class="btn btn-primary btn-sm" data-modal-open="#modal-nueva-venta">+ Registrar Venta Manual</button>
            </div>

            <!-- Panel de Sincronización de Catálogos -->
            <div class="ui-card">
                <h3>Estado de Sincronización de Catálogos (Outbox)</h3>
                $syncPanelHtml
            </div>

            <!-- Listado de Ventas -->
            <div class="ui-card">
                <h3>Historial de Ventas</h3>
                $salesHtml
            </div>
        </div>

        <!-- Modal Registrar Venta Manual -->
        <div id="modal-nueva-venta" class="ui-modal-backdrop" data-static hidden>
            <div class="ui-modal" role="dialog" aria-modal="true" aria-labelledby="venta-manual-titulo">
                <div class="ui-toolbar">
                    <h3 id="venta-manual-titulo">Registrar Venta Manual</h3>
                    <button type="button" class="btn btn-sm icon-btn" data-modal-close aria-label="Cerrar">&times;</button>
                </div>
                <form id="form-venta-manual" hx-post="/api/ventas" hx-target="#mensaje-venta-respuesta" autocomplete="off">
                    <input type="hidden" name="idempotencyKey" value="MAN-${java.util.UUID.randomUUID()}">
                    <div class="ui-grid">
                        <div class="ui-field wide">
                            <label for="venta-key">Clave del Producto (SKU)</label>
                            <input id="venta-key" type="text" name="key" required placeholder="PROD-001">
                        </div>
                        <div class="ui-field">
                            <label for="venta-cantidad">Cantidad</label>
                            <input id="venta-cantidad" type="number" name="cantidad" min="1" value="1" required>
                        </div>
                        <div class="ui-field">
                            <label for="venta-precio">Precio Unitario (COP)</label>
                            <input id="venta-precio" type="number" name="precioUnitario" min="0" step="1000" required placeholder="100000">
                        </div>
                        <div class="ui-field">
                            <label for="venta-canal">Canal de Venta</label>
                            <select id="venta-canal" name="canal" required data-canal-toggle>
                                <option value="presencial">Presencial</option>
                                <option value="whatsapp">WhatsApp</option>
                                <option value="instagram">Instagram</option>
                                <option value="facebook_marketplace">Facebook Marketplace</option>
                                <option value="shopify">Shopify</option>
                                <option value="otro">Otro</option>
                            </select>
                        </div>
                        <div class="ui-field">
                            <label for="venta-estado">Estado Logístico</label>
                            <select id="venta-estado" name="estadoLogistico" required>
                                <option value="SIN_ENVIO">Sin Envío (Entregado)</option>
                                <option value="PENDIENTE_ENVIO">Pendiente de Envío</option>
                                <option value="EN_PREPARACION">En Preparación</option>
                                <option value="ENVIADO">Enviado</option>
                                <option value="ENTREGADO">Entregado</option>
                            </select>
                        </div>
                        <div id="campo-canal-detalle" class="ui-field wide" hidden>
                            <label for="venta-canal-detalle">Detalle del Canal (si eligió Otro)</label>
                            <input id="venta-canal-detalle" type="text" name="canalDetalle" placeholder="Ej: Venta telefónica directa">
                        </div>
                        <div id="campo-order-id" class="ui-field wide" hidden>
                            <label for="venta-order-id">Número de Pedido Shopify (Requerido)</label>
                            <input id="venta-order-id" type="text" name="orderId" placeholder="Ej: #1002">
                        </div>
                        <div class="ui-field wide">
                            <label for="venta-nota">Nota / Comentarios</label>
                            <textarea id="venta-nota" name="nota" rows="2" placeholder="Detalles de pago o entrega..."></textarea>
                        </div>
                    </div>
                    <div id="mensaje-venta-respuesta" role="status"></div>
                    <div class="ui-actions end">
                        <button type="button" class="btn btn-sm" data-modal-close>Cancelar</button>
                        <button type="submit" class="btn btn-sm btn-primary">Registrar Venta</button>
                    </div>
                </form>
            </div>
        </div>
    """.trimIndent()
}
