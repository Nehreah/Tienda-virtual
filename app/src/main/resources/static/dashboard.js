(function () {
        'use strict';

        /* ---------- Subpáginas (ruteo por hash) ---------- */
        var VIEWS = ['inicio', 'apis', 'chats', 'catalogo', 'socios', 'reparaciones', 'metricas', 'automatizacion'];
        var TITLES = {
            inicio: 'Inicio',
            apis: 'APIs y conectores',
            chats: 'Mensajería',
            catalogo: 'Inventario',
            socios: 'Socios',
            reparaciones: 'Reparaciones',
            metricas: 'Métricas',
            automatizacion: 'Automatización'
        };

        function currentView() {
            var h = (location.hash || '').replace(/^#\/?/, '');
            return VIEWS.indexOf(h) >= 0 ? h : 'inicio';
        }

        function showView() {
            var v = currentView();
            document.querySelectorAll('.view').forEach(function (s) {
                s.hidden = s.getAttribute('data-view') !== v;
            });
            document.querySelectorAll('[data-nav]').forEach(function (a) {
                if (a.getAttribute('data-nav') === v) { a.setAttribute('aria-current', 'page'); }
                else { a.removeAttribute('aria-current'); }
            });
            document.getElementById('page-title').textContent = TITLES[v];
            document.title = TITLES[v] + ' · Commerce Control';
            document.body.classList.remove('nav-open');
            window.scrollTo(0, 0);
        }
        /* Paneles que se cargan al abrir cada subpágina. Cada uno es un fragmento HTML del servidor. */
        var MODULE_PANELS = {
            socios: [{ url: '/api/socios', target: 'socios-panel' }],
            reparaciones: [{ url: '/ui/reparaciones', target: 'reparaciones-panel' }],
            metricas: [{ url: '/api/metricas?p=generales', target: 'metricas-financieras-panel' }],
            catalogo: [
                { url: '/ui/catalogo', target: 'catalogo-panel' },
                { url: '/api/inventario/tabla', target: 'inventario-inversion-panel' }
            ]
        };

        function loadPanel(config) {
            var target = document.getElementById(config.target);
            if (!target || target.getAttribute('data-loaded') === 'true') { return; }
            target.textContent = 'Cargando…';
            fetch(config.url, { credentials: 'same-origin', headers: { 'X-Requested-With': 'fetch' } })
                .then(function (response) {
                    /* Una respuesta de error en HTML (503 sin base de datos, 500 con el motivo) trae el mensaje para la persona: se muestra.
                       Cualquier otro error (401/403/429 en texto plano, red) sigue siendo el aviso genérico. */
                    var esHtml = (response.headers.get('Content-Type') || '').toLowerCase().indexOf('text/html') === 0;
                    if (!response.ok && !esHtml) { throw new Error('No se pudo cargar el módulo (' + response.status + ').'); }
                    return response.text().then(function (html) { return { html: html, ok: response.ok }; });
                })
                .then(function (res) {
                    target.innerHTML = res.html;
                    /* Con error no se marca como cargado: al volver a entrar a la subpágina se reintenta. */
                    if (res.ok) { target.setAttribute('data-loaded', 'true'); }
                    /* htmx oficial no ve los atributos hx-* de HTML insertado con innerHTML: hay que procesarlo. */
                    if (window.htmx && typeof window.htmx.process === 'function') { window.htmx.process(target); }
                })
                .catch(function (error) { target.textContent = error.message || 'Error cargando el módulo.'; });
        }

        function loadModuleView(view) {
            var panels = MODULE_PANELS[view];
            if (!panels) { return; }
            panels.forEach(loadPanel);
        }
        window.addEventListener('hashchange', function () { showView(); loadModuleView(currentView()); });
        showView();
        loadModuleView(currentView());

        /* ---------- Autodetección Callback URL de Meta ---------- */
        var cbInput = document.getElementById('meta-callback-url-input');
        if (cbInput) { cbInput.value = window.location.origin + '/webhooks/meta'; }
        var cbBtn = document.getElementById('btn-copiar-meta-url');
        if (cbBtn && cbInput) {
            cbBtn.addEventListener('click', function () {
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(cbInput.value).then(function () {
                        showToast('Callback URL copiada al portapapeles');
                    }).catch(function () {
                        cbInput.select();
                        document.execCommand('copy');
                        showToast('Callback URL copiada al portapapeles');
                    });
                } else {
                    cbInput.select();
                    document.execCommand('copy');
                    showToast('Callback URL copiada al portapapeles');
                }
            });
        }

        /* ---------- Menú móvil ---------- */
        document.getElementById('menu-btn').addEventListener('click', function () {
            document.body.classList.toggle('nav-open');
        });
        document.getElementById('scrim').addEventListener('click', function () {
            document.body.classList.remove('nav-open');
        });

        /* ---------- Modo claro / oscuro ---------- */
        document.getElementById('theme-toggle').addEventListener('click', function () {
            var root = document.documentElement;
            var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
            root.setAttribute('data-theme', next);
            try { localStorage.setItem('theme', next); } catch (e) {}
        });

        /* ---------- Filtro de chats por canal ---------- */
        document.addEventListener('click', function (e) {
            var chip = e.target.closest ? e.target.closest('.chip[data-filter]') : null;
            if (!chip) { return; }
            var ch = chip.getAttribute('data-filter');
            document.querySelectorAll('.chip[data-filter]').forEach(function (b) {
                var on = b.getAttribute('data-filter') === ch;
                b.classList.toggle('active', on);
                b.setAttribute('aria-pressed', on ? 'true' : 'false');
            });
            document.querySelectorAll('.chat-row').forEach(function (r) {
                r.hidden = !(ch === 'all' || r.getAttribute('data-channel') === ch);
            });
        });

        /* ---------- Confirmar pago (el nombre se lee del atributo data-*, nunca se evalúa como JS) ---------- */
        document.addEventListener('submit', function (e) {
            var f = e.target;
            if (f && f.classList && f.classList.contains('js-confirm-pago')) {
                var who = f.getAttribute('data-customer') || 'este cliente';
                if (!window.confirm('¿Confirmar pago para ' + who + '?')) { e.preventDefault(); }
            }
        });

        /* ---------- htmx oficial: CSRF, errores y modales ---------- */
        var meta = document.querySelector('meta[name="csrf-token"]');
        var csrf = meta ? meta.getAttribute('content') : '';
        document.body.addEventListener('htmx:configRequest', function (e) {
            if (csrf) { e.detail.headers['X-CSRF-Token'] = csrf; }
        });

        function showToast(text) {
            var msg = document.getElementById('dashboard-toast');
            if (!msg) {
                msg = document.createElement('div');
                msg.id = 'dashboard-toast';
                msg.className = 'ui-toast';
                msg.setAttribute('role', 'status');
                document.body.appendChild(msg);
            }
            msg.textContent = text;
            msg.hidden = false;
            clearTimeout(showToast.timer);
            showToast.timer = setTimeout(function () { msg.hidden = true; }, 8000);
        }

        /* htmx NO intercambia respuestas 4xx por defecto. Nuestras rutas devuelven el panel con el mensaje de error
           (400/409/422 en text/html): se dejan pasar para que el mensaje se vea dentro del panel. */
        document.body.addEventListener('htmx:beforeSwap', function (e) {
            var xhr = e.detail.xhr;
            if (!xhr) { return; }
            var st = xhr.status;
            if (st >= 400 && st < 500 && st !== 401 && st !== 403 && st !== 429) {
                var ct = (xhr.getResponseHeader('Content-Type') || '').toLowerCase();
                if (ct.indexOf('text/html') === 0) { e.detail.shouldSwap = true; e.detail.isError = false; }
            }
        });
        document.body.addEventListener('htmx:responseError', function (e) {
            var xhr = e.detail.xhr;
            var st = xhr ? xhr.status : 0;
            var text = st === 401 ? 'Tu sesión expiró. Recarga la página.'
                : st === 403 ? 'Solicitud rechazada por seguridad (origen no permitido).'
                : st === 429 ? 'Demasiados intentos. Espera un momento.'
                : 'No se pudo completar la solicitud (HTTP ' + st + ').';
            showToast(text);
        });
        document.body.addEventListener('htmx:sendError', function () {
            showToast('No hay conexión con el servidor.');
        });
        document.body.addEventListener('htmx:timeout', function () {
            showToast('La solicitud tardó demasiado. Inténtalo otra vez.');
        });

        /* Modales: se cierran con [data-modal-close], con Escape o haciendo clic fuera del cuadro. */
        function closeModals() {
            document.querySelectorAll('.ui-modal-backdrop').forEach(function (m) {
                /* Un modal fijo de la página (data-static) se oculta para poder reabrirlo; los que llegan por htmx se eliminan. */
                if (m.hasAttribute('data-static')) { m.hidden = true; } else { m.remove(); }
            });
        }
        document.addEventListener('click', function (e) {
            var t = e.target;
            if (!t || !t.closest) { return; }
            var opener = t.closest('[data-modal-open]');
            if (opener) {
                var modal = document.querySelector(opener.getAttribute('data-modal-open'));
                if (modal) { modal.hidden = false; }
                return;
            }
            if (t.closest('[data-modal-close]')) { e.preventDefault(); closeModals(); return; }
            if (t.classList && t.classList.contains('ui-modal-backdrop')) { closeModals(); return; }
            var clear = t.closest('[data-clear]');
            if (clear) {
                var slot = document.querySelector(clear.getAttribute('data-clear'));
                if (slot) { slot.innerHTML = ''; }
            }
        });
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') { closeModals(); }
        });

        /* Venta manual: según el canal se muestra el detalle (Otro) o el número de pedido (Shopify). */
        document.addEventListener('change', function (e) {
            var sel = e.target;
            if (!sel || !sel.hasAttribute || !sel.hasAttribute('data-canal-toggle')) { return; }
            var detalle = document.getElementById('campo-canal-detalle');
            var pedido = document.getElementById('campo-order-id');
            if (detalle) { detalle.hidden = sel.value !== 'otro'; }
            if (pedido) { pedido.hidden = sel.value !== 'shopify'; }
        });
    })();
