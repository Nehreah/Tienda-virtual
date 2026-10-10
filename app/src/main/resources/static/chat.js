        (function() {
            // Estado actual del chat
            var chatState = {
                convId: null,
                atencion: 'BOT',
                filtro: 'todas',
                ultimoEntrante: null,
                sseSource: null,
                sseReconexiones: 0
            };

            // --- FILTROS ---
            window.chatFiltrar = function(btn, filtro) {
                document.querySelectorAll('.chat-filtro').forEach(function(b) { b.classList.remove('active'); });
                btn.classList.add('active');
                chatState.filtro = filtro;
                chatCargarBandeja();
            };

            // --- SSE ---
            function conectarSSE() {
                if (chatState.sseSource) { chatState.sseSource.close(); }
                var url = '/api/chat/stream';
                var lastId = localStorage.getItem('chat_last_sse_id');
                if (lastId) { url += '?lastId=' + encodeURIComponent(lastId); }

                var source = new EventSource(url);
                chatState.sseSource = source;
                var estadoEl = document.getElementById('chat-sse-estado');

                source.addEventListener('mensaje', function(e) {
                    localStorage.setItem('chat_last_sse_id', e.lastEventId);
                    if (estadoEl) estadoEl.textContent = '';
                    var data = JSON.parse(e.data || '{}');
                    // Recargar bandeja
                    chatCargarBandeja();
                    // Si el panel abierto es el afectado, recargar mensajes
                    if (chatState.convId && data.convId === chatState.convId) {
                        chatCargarMensajes(chatState.convId);
                    }
                    chatState.sseReconexiones = 0;
                });

                source.addEventListener('atencion', function(e) {
                    localStorage.setItem('chat_last_sse_id', e.lastEventId);
                    // Notificación visual en el título
                    document.title = '🔔 ' + document.title.replace(/^🔔 /, '');
                    chatCargarBandeja();
                    // Sonido opcional (solo si el usuario ha interactuado)
                    try {
                        var ctx = new (window.AudioContext || window.webkitAudioContext)();
                        var osc = ctx.createOscillator();
                        var g = ctx.createGain();
                        osc.connect(g); g.connect(ctx.destination);
                        osc.frequency.value = 880; osc.type = 'sine';
                        g.gain.setValueAtTime(0.3, ctx.currentTime);
                        g.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.4);
                        osc.start(ctx.currentTime); osc.stop(ctx.currentTime + 0.4);
                    } catch(err) {}
                });

                source.addEventListener('reset', function() {
                    localStorage.removeItem('chat_last_sse_id');
                    chatCargarBandeja();
                });

                source.addEventListener('ping', function() {
                    if (estadoEl) estadoEl.textContent = '';
                });

                source.onerror = function() {
                    chatState.sseReconexiones++;
                    var espera = Math.min(30, Math.pow(2, chatState.sseReconexiones));
                    if (estadoEl) estadoEl.textContent = 'Reconectando en ' + espera + 's…';
                    source.close();
                    setTimeout(conectarSSE, espera * 1000);
                };

                source.onopen = function() {
                    if (estadoEl) estadoEl.textContent = '';
                };
            }

            // --- ABRIR CONVERSACIÓN ---
            window.chatAbrirConversacion = function(convId, nombre, atencion, ultimoEntrante) {
                chatState.convId = convId;
                chatState.atencion = atencion;
                chatState.ultimoEntrante = ultimoEntrante ? parseInt(ultimoEntrante) : null;

                // Marcar activo en bandeja
                document.querySelectorAll('.chat-conv-item').forEach(function(el) {
                    el.classList.toggle('activo', el.dataset.convId === convId);
                });

                var panelVacio = document.getElementById('chat-panel-vacio');
                var panelContenido = document.getElementById('chat-panel-contenido');
                if (panelVacio) panelVacio.style.display = 'none';
                if (panelContenido) {
                    panelContenido.style.display = 'flex';
                    chatRenderPanel(convId, nombre, atencion, ultimoEntrante);
                }
            };

            // --- RENDER PANEL ---
            function chatRenderPanel(convId, nombre, atencion, ultimoEntrante) {
                var el = document.getElementById('chat-panel-contenido');
                if (!el) return;

                var estadoLabel = atencion === 'HUMANO' ? '<span class="chat-panel-estado chat-estado-humano">🧑 Modo humano</span>'
                    : '<span class="chat-panel-estado chat-estado-bot">🤖 Bot activo</span>';

                var btnTomar = atencion === 'BOT'
                    ? '<button class="chat-btn chat-btn-tomar" onclick="chatTomar()">Tomar control</button>'
                    : '<button class="chat-btn chat-btn-devolver" onclick="chatDevolver()">Devolver al bot</button>';

                el.innerHTML =
                    '<div class="chat-panel-header">' +
                        '<div class="chat-panel-nombre">' + escHtml(nombre) + '</div>' +
                        estadoLabel +
                        '<div class="chat-acciones">' + btnTomar + '</div>' +
                    '</div>' +
                    '<div class="chat-mensajes" id="chat-mensajes-lista"><div class="chat-loading">Cargando…</div></div>' +
                    '<div class="chat-input-area" id="chat-input-area">' +
                        chatRenderInputArea(atencion, ultimoEntrante) +
                    '</div>';

                chatCargarMensajes(convId);
            }

            function chatRenderInputArea(atencion, ultimoEntrante) {
                if (atencion !== 'HUMANO') {
                    return '<p class="chat-modo-aviso">Toma el control de la conversación para enviar mensajes.</p>';
                }
                // Verificar ventana de 24h
                var avisoCierre = '';
                if (ultimoEntrante) {
                    var diffHoras = (Date.now() - ultimoEntrante) / 3600000;
                    if (diffHoras >= 20) {
                        var restante = Math.max(0, 24 - diffHoras).toFixed(1);
                        avisoCierre = '<div class="chat-ventana-aviso">⚠️ Quedan ~' + restante + 'h para responder dentro de la ventana de 24h de WhatsApp.</div>';
                    }
                    if (diffHoras >= 24) {
                        return '<p class="chat-modo-aviso">⛔ La ventana de 24 horas expiró. No es posible enviar mensajes estándar.</p>';
                    }
                }
                return avisoCierre +
                    '<div class="chat-input-row">' +
                        '<textarea class="chat-textarea" id="chat-textarea" placeholder="Escribe un mensaje…" rows="2" ' +
                            'onkeydown="if(event.key===\'Enter\'&&!event.shiftKey){event.preventDefault();chatEnviar();}"></textarea>' +
                        '<button class="chat-enviar" onclick="chatEnviar()" id="chat-btn-enviar">Enviar</button>' +
                    '</div>';
            }

            // --- CARGAR MENSAJES ---
            window.chatCargarMensajes = function(convId) {
                var lista = document.getElementById('chat-mensajes-lista');
                if (!lista) return;

                fetch('/api/chat/' + encodeURIComponent(convId) + '/mensajes')
                    .then(function(r) { return r.json(); })
                    .then(function(data) {
                        if (!data.data || !data.data.length) {
                            lista.innerHTML = '<div class="chat-loading">Sin mensajes aún.</div>';
                            return;
                        }
                        lista.innerHTML = data.data.map(function(m) {
                            var cls = m.emisor === 'CLIENTE' ? 'cliente' : (m.emisor === 'HUMANO' ? 'humano' : 'bot');
                            if (m.estado_envio === 'FALLO') cls += ' fallo';
                            var hora = m.creado_en ? new Date(m.creado_en).toLocaleTimeString('es-CO', {hour:'2-digit', minute:'2-digit'}) : '';
                            var errHtml = m.error_envio
                                ? '<div class="chat-burbuja-error">' + escHtml(m.error_envio) +
                                  ' <button class="chat-reintentar" onclick="chatReintentar(' + m.id + ')">Reintentar</button></div>'
                                : '';
                            return '<div class="chat-burbuja ' + cls + '">' +
                                '<span>' + escHtml(m.contenido || '') + '</span>' +
                                '<div class="chat-burbuja-meta">' +
                                    hora + (m.emisor !== 'CLIENTE' ?
                                        ' <span class="chat-estado-envio chat-estado-' + (m.estado_envio||'').toLowerCase() + '">' +
                                        (m.estado_envio === 'ENVIADO' ? '✓' : m.estado_envio === 'ENVIANDO' ? '…' : '') + '</span>' : '') +
                                '</div>' + errHtml +
                            '</div>';
                        }).join('');
                        // Scroll al final
                        lista.scrollTop = lista.scrollHeight;
                    })
                    .catch(function() {
                        if (lista) lista.innerHTML = '<div class="chat-loading">Error al cargar mensajes.</div>';
                    });
            };

            // --- ENVIAR MENSAJE ---
            window.chatEnviar = function() {
                if (!chatState.convId) return;
                var ta = document.getElementById('chat-textarea');
                var btn = document.getElementById('chat-btn-enviar');
                if (!ta) return;
                var texto = ta.value.trim();
                if (!texto) return;

                ta.disabled = true;
                if (btn) btn.disabled = true;

                fetch('/api/chat/' + encodeURIComponent(chatState.convId) + '/enviar', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: 'texto=' + encodeURIComponent(texto)
                })
                .then(function(r) { return r.json().then(function(d) { return {ok:r.ok, data:d}; }); })
                .then(function(res) {
                    if (res.ok) {
                        ta.value = '';
                        chatCargarMensajes(chatState.convId);
                    } else {
                        alert('Error al enviar: ' + (res.data.error || 'Error desconocido'));
                    }
                })
                .catch(function() { alert('Error de red al enviar el mensaje.'); })
                .finally(function() {
                    ta.disabled = false;
                    if (btn) btn.disabled = false;
                    ta.focus();
                });
            };

            // --- TOMAR / DEVOLVER ---
            window.chatTomar = function() {
                if (!chatState.convId) return;
                fetch('/api/chat/' + encodeURIComponent(chatState.convId) + '/tomar', { method: 'POST' })
                    .then(function(r) { return r.json(); })
                    .then(function() {
                        chatState.atencion = 'HUMANO';
                        chatCargarBandeja();
                        var nombre = document.querySelector('.chat-panel-nombre');
                        chatRenderPanel(chatState.convId, nombre ? nombre.textContent : '', 'HUMANO', chatState.ultimoEntrante);
                    });
            };

            window.chatDevolver = function() {
                if (!chatState.convId) return;
                if (!confirm('¿Devolver la conversación al bot?')) return;
                fetch('/api/chat/' + encodeURIComponent(chatState.convId) + '/devolver', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: 'nuevoEstado=IDLE'
                })
                .then(function(r) { return r.json(); })
                .then(function() {
                    chatState.atencion = 'BOT';
                    chatCargarBandeja();
                    var nombre = document.querySelector('.chat-panel-nombre');
                    chatRenderPanel(chatState.convId, nombre ? nombre.textContent : '', 'BOT', chatState.ultimoEntrante);
                });
            };

            // --- REINTENTAR MENSAJE ---
            window.chatReintentar = function(msgId) {
                fetch('/api/chat/mensajes/' + msgId + '/reintentar', { method: 'POST' })
                    .then(function() { if (chatState.convId) chatCargarMensajes(chatState.convId); });
            };

            // --- CARGAR BANDEJA ---
            window.chatCargarBandeja = function() {
                var lista = document.getElementById('bandeja-lista');
                if (!lista) return;
                fetch('/api/chat/bandeja?filtro=' + encodeURIComponent(chatState.filtro))
                    .then(function(r) { return r.json(); })
                    .then(function(data) {
                        if (!data.data || !data.data.length) {
                            lista.innerHTML = '<div class="chat-loading">No hay conversaciones activas.</div>';
                            return;
                        }
                        lista.innerHTML = data.data.map(function(conv) {
                            var atencion = conv.atencion || 'BOT';
                            var requiere = !!conv.requiere_atencion;
                            var noLeidos = conv.no_leidos || 0;
                            var cls = 'chat-conv-item' + (requiere ? ' requiere' : '');
                            var nombre = escHtml(conv.cliente_nombre || conv.customer_id || 'Cliente');
                            var preview = escHtml((conv.ultimo_mensaje || '').substring(0, 60));
                            var canal = escHtml(conv.channel || '');
                            var ultimoEntrante = conv.ultimo_entrante_en || 0;
                            var badgeAtencion = requiere
                                ? '<span class="chat-conv-atencion">⚡ Atención</span>'
                                : (atencion === 'HUMANO' ? '<span class="chat-conv-humano">🧑 Humano</span>' : '');
                            var badgeNoLeidos = noLeidos > 0
                                ? '<span class="chat-conv-badge">' + noLeidos + '</span>' : '';
                            var convIdSafe = escHtml(conv.conversation_id || '');
                            var nombreJs = (conv.cliente_nombre || conv.customer_id || 'Cliente').replace(/'/g, "\\'");
                            return '<button class="' + cls + '" data-conv-id="' + convIdSafe + '" ' +
                                'onclick="chatAbrirConversacion(\'' + convIdSafe + '\',\'' + nombreJs + '\',\'' + atencion + '\',' + ultimoEntrante + ')">' +
                                '<div class="chat-conv-nombre"><span>' + nombre + '</span>' + badgeNoLeidos + '</div>' +
                                '<div class="chat-conv-preview">' + (conv.ultimo_emisor !== 'CLIENTE' ? 'Tú: ' : '') + preview + '</div>' +
                                '<div class="chat-conv-meta"><span class="chat-conv-canal">' + canal + '</span>' + badgeAtencion + '</div>' +
                                '</button>';
                        }).join('');
                        // Reseleccionar item activo si corresponde
                        if (chatState.convId) {
                            var activo = lista.querySelector('[data-conv-id="' + chatState.convId + '"]');
                            if (activo) activo.classList.add('activo');
                        }
                    })
                    .catch(function() {
                        if (lista) lista.innerHTML = '<div class="chat-loading">Error al cargar conversaciones.</div>';
                    });
            };

            // --- ESCAPING ---
            function escHtml(s) {
                return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');
            }

            // Iniciar SSE
            if (window.EventSource) { conectarSSE(); }

            // Carga inicial de la bandeja
            chatCargarBandeja();

            // Limpiar título al volver a la ventana
            window.addEventListener('focus', function() {
                document.title = document.title.replace(/^🔔 /, '');
            });
        })();
