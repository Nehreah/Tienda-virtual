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
        function loadModuleView(view) {
            var config = {
                socios: { url: '/api/socios', target: 'socios-panel' },
                reparaciones: { url: '/ui/reparaciones', target: 'reparaciones-panel' },
                catalogo: { url: '/api/inventario/tabla', target: 'inventario-inversion-panel' }
            }[view];
            if (!config) { return; }
            var target = document.getElementById(config.target);
            if (!target || target.getAttribute('data-loaded') === 'true') { return; }
            target.textContent = 'Cargando…';
            fetch(config.url, { credentials: 'same-origin', headers: { 'X-Requested-With': 'fetch' } })
                .then(function (response) {
                    if (!response.ok) { throw new Error('No se pudo cargar el módulo (' + response.status + ').'); }
                    return response.text();
                })
                .then(function (html) { target.innerHTML = html; target.setAttribute('data-loaded', 'true'); })
                .catch(function (error) { target.textContent = error.message || 'Error cargando el módulo.'; });
        }
        window.addEventListener('hashchange', function () { showView(); loadModuleView(currentView()); });
        showView();
        loadModuleView(currentView());

        /* ---------- Autodetección Callback URL de Meta ---------- */
        var cbInput = document.getElementById('meta-callback-url-input');
        if (cbInput) { cbInput.value = window.location.origin + '/webhooks/meta'; }

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

        /* ---------- CSRF para peticiones htmx ---------- */
        var meta = document.querySelector('meta[name="csrf-token"]');
        var csrf = meta ? meta.getAttribute('content') : '';
        document.body.addEventListener('htmx:configRequest', function (e) {
            if (csrf) { e.detail.headers['X-CSRF-Token'] = csrf; }
        });
    })();
