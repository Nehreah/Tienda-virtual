package com.automata.app.dashboard

object ChatRenderer {

    fun renderChatPage(
        conversationsHtml: String,
        activeChatHtml: String
    ): String = """
        <div class="h-[calc(100vh-4rem)] flex overflow-hidden bg-gray-50">
            <!-- Columna Izquierda: Bandeja de Conversaciones -->
            <div class="w-1/3 border-r border-gray-200 bg-white flex flex-col">
                <div class="p-4 border-b border-gray-200">
                    <h1 class="text-lg font-bold text-gray-900">Bandeja de Mensajería</h1>
                    <div class="mt-2 flex gap-2">
                        <button class="px-3 py-1 text-xs font-semibold rounded-full bg-indigo-100 text-indigo-700">Todas</button>
                        <button class="px-3 py-1 text-xs font-semibold rounded-full bg-yellow-100 text-yellow-800">Requiere Atención</button>
                        <button class="px-3 py-1 text-xs font-semibold rounded-full bg-green-100 text-green-800">En mi Control</button>
                    </div>
                </div>
                <div class="flex-1 overflow-y-auto divide-y divide-gray-100" id="bandeja-lista">
                    $conversationsHtml
                </div>
            </div>

            <!-- Columna Derecha: Panel de Conversación Activa -->
            <div class="w-2/3 flex flex-col bg-white">
                $activeChatHtml
            </div>
        </div>

        <script>
            // SSE Client for real-time chat updates
            if (!!window.EventSource) {
                const source = new EventSource('/api/chat/stream');
                source.onmessage = function(e) {
                    if (e.data === 'ping') return;
                    console.log('SSE chat update:', e.data);
                    // Disparar recarga de HTMX o actualizar la lista
                    if (typeof htmx !== 'undefined') {
                        htmx.trigger('#bandeja-lista', 'reload');
                    }
                };
            }
        </script>
    """.trimIndent()
}
