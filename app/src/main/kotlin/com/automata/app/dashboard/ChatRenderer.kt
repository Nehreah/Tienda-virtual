package com.automata.app.dashboard

/**
 * Bandeja de mensajería (control humano de conversaciones).
 *
 * Solo usa CSS local (card, chips, cols-2 de [DashboardRenderer]) y no lleva JavaScript en línea: la CSP
 * (script-src 'self') lo bloquea. La actualización en tiempo real (SSE) la maneja /static/chat.js, que todavía
 * no se carga en ninguna página; hay que incluirlo con <script src="/static/chat.js" defer> en la página que
 * aloje esta bandeja.
 */
object ChatRenderer {

    fun renderChatPage(
        conversationsHtml: String,
        activeChatHtml: String
    ): String = """
        <div class="cols-2">
            <!-- Columna izquierda: bandeja de conversaciones -->
            <section class="card card-pad" aria-label="Bandeja de conversaciones">
                <h3>Bandeja de Mensajería</h3>
                <div class="chips" role="group" aria-label="Filtrar conversaciones">
                    <button type="button" class="chip chat-filtro active">Todas</button>
                    <button type="button" class="chip chat-filtro">Requiere Atención</button>
                    <button type="button" class="chip chat-filtro">En mi Control</button>
                </div>
                <div id="bandeja-lista">
                    $conversationsHtml
                </div>
            </section>

            <!-- Columna derecha: panel de conversación activa -->
            <section class="card card-pad" aria-label="Conversación activa">
                $activeChatHtml
            </section>
        </div>
    """.trimIndent()
}
