package com.automata.app.dashboard

/**
 * Renderiza la pestaña de Mensajería / Chat en tiempo real.
 * Usa CSS del sistema (no Tailwind). El SSE usa addEventListener por tipo de evento.
 */
object ChatRenderer {

    fun renderChatPage(): String = """
        <div class="chat-shell">
            <!-- Columna izquierda: bandeja -->
            <div class="chat-bandeja" id="chat-bandeja-panel">
                <div class="chat-bandeja-header">
                    <h2 class="chat-bandeja-title">Mensajería</h2>
                    <div class="chat-filtros" id="chat-filtros">
                        <button class="chat-filtro active" data-filtro="todas" onclick="chatFiltrar(this,'todas')">Todas</button>
                        <button class="chat-filtro alerta" data-filtro="atencion" onclick="chatFiltrar(this,'atencion')">Requiere atención</button>
                        <button class="chat-filtro" data-filtro="mia" onclick="chatFiltrar(this,'mia')">En mi control</button>
                    </div>
                    <div class="chat-bandeja-estado" id="chat-sse-estado" aria-live="polite"></div>
                </div>
                <div class="chat-bandeja-lista" id="bandeja-lista"
                     hx-get="/api/chat/bandeja"
                     hx-trigger="load, chatActualizar from:body"
                     hx-target="#bandeja-lista"
                     hx-swap="innerHTML"
                     hx-indicator="#chat-loading">
                    <div id="chat-loading" class="chat-loading htmx-indicator">Cargando conversaciones…</div>
                </div>
            </div>

            <!-- Columna derecha: panel de conversación -->
            <div class="chat-panel" id="chat-panel">
                <div class="chat-panel-vacio" id="chat-panel-vacio">
                    <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
                    <p>Selecciona una conversación</p>
                </div>
                <div id="chat-panel-contenido" style="display:none; height:100%; flex-direction:column; flex:1;"></div>
            </div>
        </div>

        <style>
            .chat-shell { display:flex; height:calc(100vh - 56px); overflow:hidden; background:var(--surface); }

            /* Bandeja */
            .chat-bandeja { width:320px; min-width:260px; border-right:1px solid var(--border); display:flex; flex-direction:column; background:var(--surface); }
            .chat-bandeja-header { padding:14px 16px 10px; border-bottom:1px solid var(--border); }
            .chat-bandeja-title { margin:0 0 10px; font-size:1rem; font-weight:700; }
            .chat-filtros { display:flex; gap:6px; flex-wrap:wrap; }
            .chat-filtro { border:none; border-radius:999px; padding:4px 12px; font-size:.78rem; font-weight:600; cursor:pointer; background:var(--surface-soft, var(--surface)); color:var(--muted); transition:background .15s; }
            .chat-filtro.active { background:var(--primary-soft); color:var(--primary); }
            .chat-filtro.alerta.active { background:#fff3cd; color:#856404; }
            .chat-bandeja-estado { font-size:.74rem; color:var(--muted); margin-top:6px; min-height:16px; }
            .chat-bandeja-lista { flex:1; overflow-y:auto; }
            .chat-loading { padding:16px; color:var(--muted); font-size:.85rem; }

            /* Items bandeja */
            .chat-conv-item { padding:12px 14px; cursor:pointer; border-bottom:1px solid var(--border); transition:background .12s; display:block; width:100%; text-align:left; background:none; border-left:3px solid transparent; }
            .chat-conv-item:hover { background:var(--surface-soft, rgba(0,0,0,.04)); }
            .chat-conv-item.activo { background:var(--primary-soft); border-left-color:var(--primary); }
            .chat-conv-item.requiere { border-left-color:#fbbf24; }
            .chat-conv-nombre { font-weight:600; font-size:.88rem; margin-bottom:2px; display:flex; justify-content:space-between; align-items:center; }
            .chat-conv-preview { font-size:.78rem; color:var(--muted); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; max-width:240px; }
            .chat-conv-meta { display:flex; gap:6px; align-items:center; margin-top:4px; }
            .chat-conv-canal { font-size:.7rem; background:var(--surface-soft, #f3f4f6); padding:1px 6px; border-radius:4px; color:var(--muted); }
            .chat-conv-badge { font-size:.7rem; background:#fbbf24; color:#1a1a1a; padding:1px 6px; border-radius:999px; font-weight:700; }
            .chat-conv-atencion { font-size:.7rem; color:#92400e; background:#fef3c7; padding:1px 6px; border-radius:4px; }
            .chat-conv-humano { font-size:.7rem; color:#065f46; background:#d1fae5; padding:1px 6px; border-radius:4px; }
            .chat-conv-hora { font-size:.7rem; color:var(--muted); margin-left:auto; }

            /* Panel derecho */
            .chat-panel { flex:1; display:flex; flex-direction:column; background:var(--surface); overflow:hidden; }
            .chat-panel-vacio { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; color:var(--muted); }
            .chat-panel-vacio svg { opacity:.4; }

            /* Cabecera del panel */
            .chat-panel-header { padding:12px 16px; border-bottom:1px solid var(--border); display:flex; align-items:center; gap:10px; }
            .chat-panel-nombre { font-weight:700; font-size:.95rem; flex:1; }
            .chat-panel-estado { font-size:.78rem; padding:3px 10px; border-radius:999px; }
            .chat-estado-bot { background:var(--primary-soft); color:var(--primary); }
            .chat-estado-humano { background:#d1fae5; color:#065f46; }
            .chat-estado-atencion { background:#fef3c7; color:#92400e; }

            /* Botones de control */
            .chat-acciones { display:flex; gap:8px; align-items:center; }
            .chat-btn { padding:5px 14px; border:none; border-radius:8px; cursor:pointer; font-size:.82rem; font-weight:600; transition:opacity .15s; }
            .chat-btn:disabled { opacity:.45; cursor:not-allowed; }
            .chat-btn-tomar { background:var(--primary); color:#fff; }
            .chat-btn-devolver { background:#e5e7eb; color:#374151; }
            .chat-btn-danger { background:#fee2e2; color:#991b1b; }

            /* Mensajes */
            .chat-mensajes { flex:1; overflow-y:auto; padding:14px 16px; display:flex; flex-direction:column; gap:8px; }
            .chat-burbuja { max-width:72%; padding:8px 12px; border-radius:12px; font-size:.86rem; line-height:1.45; word-break:break-word; }
            .chat-burbuja.cliente { background:var(--surface-soft, #f3f4f6); color:var(--text); align-self:flex-start; border-bottom-left-radius:3px; }
            .chat-burbuja.bot { background:var(--primary-soft); color:var(--text); align-self:flex-end; border-bottom-right-radius:3px; }
            .chat-burbuja.humano { background:var(--primary); color:#fff; align-self:flex-end; border-bottom-right-radius:3px; }
            .chat-burbuja-meta { font-size:.68rem; color:var(--muted); margin-top:2px; }
            .chat-burbuja.fallo { opacity:.65; }
            .chat-burbuja-error { font-size:.7rem; color:#b91c1c; }
            .chat-reintentar { font-size:.72rem; text-decoration:underline; cursor:pointer; background:none; border:none; color:#b91c1c; padding:0; }

            /* Caja de texto */
            .chat-input-area { padding:12px 16px; border-top:1px solid var(--border); background:var(--surface); }
            .chat-ventana-aviso { font-size:.78rem; color:#b45309; background:#fef3c7; padding:6px 10px; border-radius:6px; margin-bottom:8px; }
            .chat-input-row { display:flex; gap:8px; align-items:flex-end; }
            .chat-textarea { flex:1; border:1px solid var(--border); border-radius:10px; padding:8px 12px; font-size:.88rem; resize:none; min-height:40px; max-height:120px; background:var(--surface); color:var(--text); font-family:inherit; }
            .chat-textarea:disabled { background:var(--surface-soft, #f3f4f6); color:var(--muted); }
            .chat-enviar { padding:8px 18px; background:var(--primary); color:#fff; border:none; border-radius:10px; cursor:pointer; font-weight:600; font-size:.86rem; }
            .chat-enviar:disabled { opacity:.4; cursor:not-allowed; }
            .chat-modo-aviso { font-size:.8rem; color:var(--muted); padding:8px 4px; text-align:center; }

            /* Estado de envío */
            .chat-estado-envio { font-size:.72rem; margin-left:6px; }
            .chat-estado-enviando { color:var(--muted); }
            .chat-estado-enviado { color:#059669; }
            .chat-estado-fallo { color:#b91c1c; }
        </style>

        <script src="/static/chat.js" defer></script>
    """.trimIndent()

    /**
     * Renderiza la lista de conversaciones para la bandeja (llamada por htmx desde /api/chat/bandeja).
     * Los datos se escapan siempre; el JS usa onclick con valores escapados.
     */
    fun renderBandejaItems(items: List<Map<String, Any?>>): String {
        if (items.isEmpty()) {
            return """<div class="chat-loading">No hay conversaciones activas.</div>"""
        }
        return items.joinToString("") { conv ->
            val convId   = esc(conv["conversation_id"]?.toString() ?: "")
            val nombre   = esc(conv["cliente_nombre"]?.toString()
                ?: conv["customer_id"]?.toString() ?: "Cliente")
            val canal    = esc(conv["channel"]?.toString() ?: "")
            val atencion = conv["atencion"]?.toString() ?: "BOT"
            val requiere = conv["requiere_atencion"] as? Boolean ?: false
            val preview  = esc(conv["ultimo_mensaje"]?.toString()?.take(60) ?: "")
            val emisor   = conv["ultimo_emisor"]?.toString() ?: ""
            val ts       = conv["ultimo_mensaje_en"] as? Long
            val horaStr  = if (ts != null) "data-ts=\"$ts\"" else ""
            val noLeidos = (conv["no_leidos"] as? Int ?: 0)
            val ultimoEntrante = conv["ultimo_entrante_en"] as? Long ?: 0L

            // Clase CSS de item
            var cls = "chat-conv-item"
            if (requiere) cls += " requiere"

            // Badge de atención
            val badgeAtencion = when {
                requiere     -> """<span class="chat-conv-atencion">⚡ Atención</span>"""
                atencion == "HUMANO" -> """<span class="chat-conv-humano">🧑 Humano</span>"""
                else         -> ""
            }
            val badgeNoLeidos = if (noLeidos > 0) """<span class="chat-conv-badge">$noLeidos</span>""" else ""

            // Onclick seguro: pasamos convId y ultimoEntrante como literales numéricos o strings escapados
            val onclickNombre = nombre.replace("'", "\\'")
            """<button class="$cls" data-conv-id="$convId" $horaStr
                onclick="chatAbrirConversacion('$convId','$onclickNombre','$atencion',$ultimoEntrante)">
                <div class="chat-conv-nombre">
                    <span>${nombre}</span>
                    $badgeNoLeidos
                </div>
                <div class="chat-conv-preview">${if (emisor == "CLIENTE") "" else "Tú: "}$preview</div>
                <div class="chat-conv-meta">
                    <span class="chat-conv-canal">$canal</span>
                    $badgeAtencion
                    <span class="chat-conv-hora" $horaStr></span>
                </div>
            </button>""".trimIndent()
        }
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
