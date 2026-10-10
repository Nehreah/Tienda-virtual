(function () {
    'use strict';

    /* ---------- Subpáginas (ruteo por hash) ---------- */
    var VIEWS = ['inicio', 'apis', 'chats', 'catalogo', 'metricas', 'automatizacion'];
    var TITLES = {
        inicio: 'Inicio',
        apis: 'APIs y conectores',
        chats: 'Mensajería',
        catalogo: 'Catálogo',
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
        
        var titleEl = document.getElementById('page-title');
        if (titleEl) { titleEl.textContent = TITLES[v] || v; }
        
        document.title = (TITLES[v] || v) + ' · Commerce Control';
        document.body.classList.remove('nav-open');
        window.scrollTo(0, 0);
    }
    window.addEventListener('hashchange', showView);
    // Ejecutar al cargar si hay un elemento page-title (estamos en el dashboard)
    if (document.getElementById('page-title')) {
        showView();
    }

    /* ---------- Autodetección Callback URL de Meta ---------- */
    var cbInput = document.getElementById('meta-callback-url-input');
    if (cbInput) { cbInput.value = window.location.origin + '/webhooks/meta'; }

    /* ---------- Menú móvil ---------- */
    var menuBtn = document.getElementById('menu-btn');
    if (menuBtn) {
        menuBtn.addEventListener('click', function () {
            document.body.classList.toggle('nav-open');
        });
    }
    var scrim = document.getElementById('scrim');
    if (scrim) {
        scrim.addEventListener('click', function () {
            document.body.classList.remove('nav-open');
        });
    }

    /* ---------- Modo claro / oscuro ---------- */
    var themeToggle = document.getElementById('theme-toggle');
    if (themeToggle) {
        themeToggle.addEventListener('click', function () {
            var root = document.documentElement;
            var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
            root.setAttribute('data-theme', next);
            try { localStorage.setItem('theme', next); } catch (e) {}
        });
    }

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

    /* ---------- Confirmar pago ---------- */
    document.addEventListener('submit', function (e) {
        var f = e.target;
        if (f && f.classList && f.classList.contains('js-confirm-pago')) {
            var who = f.getAttribute('data-customer') || 'este cliente';
            if (!window.confirm('¿Confirmar pago para ' + who + '?')) { e.preventDefault(); }
        }
    });

    /* ---------- Funciones Globales para UI (ej. Ventas) ---------- */
    window.toggleCanalDetalle = function(canal) {
        var detalle = document.getElementById('campo-canal-detalle');
        if (detalle) detalle.hidden = (canal !== 'otro');
        var order = document.getElementById('campo-order-id');
        if (order) order.hidden = (canal !== 'shopify');
    };

})();
