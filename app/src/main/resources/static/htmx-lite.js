/*
 * Cliente local mínimo para los atributos hx-* usados por este dashboard.
 * No pretende ser una implementación completa de htmx.
 * Soporta hx-get, hx-post, hx-target, hx-swap, formularios y htmx.trigger().
 */
(function () {
  'use strict';
  function targetFor(el) {
    var selector = el.getAttribute('hx-target');
    if (!selector || selector === 'this') return el;
    try { return document.querySelector(selector); } catch (_) { return null; }
  }
  function emit(name, detail) {
    document.body.dispatchEvent(new CustomEvent(name, { bubbles: true, detail: detail || {} }));
  }
  function request(el, method, url, body) {
    var target = targetFor(el);
    if (!target) { emit('htmx:responseError', { error: 'No existe el destino de la respuesta.' }); return; }
    var headers = { 'X-Requested-With': 'XMLHttpRequest' };
    var csrf = document.querySelector('meta[name="csrf-token"]');
    if (csrf && csrf.content) headers['X-CSRF-Token'] = csrf.content;
    emit('htmx:configRequest', { headers: headers, parameters: body || {} });
    var options = { method: method, headers: headers, credentials: 'same-origin' };
    if (body instanceof FormData) {
      var params = new URLSearchParams();
      body.forEach(function (value, key) { if (typeof value === 'string') params.append(key, value); });
      options.body = params.toString();
      options.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
    } else if (body) {
      options.body = new URLSearchParams(body).toString();
      options.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
    }
    emit('htmx:beforeRequest', { elt: el });
    fetch(url, options).then(function (response) {
      return response.text().then(function (text) {
        if (!response.ok) throw new Error('HTTP ' + response.status + ': ' + text);
        var swap = el.getAttribute('hx-swap') || 'innerHTML';
        if (swap === 'outerHTML') target.outerHTML = text;
        else if (swap === 'beforeend') target.insertAdjacentHTML('beforeend', text);
        else if (swap === 'afterbegin') target.insertAdjacentHTML('afterbegin', text);
        else target.innerHTML = text;
        emit('htmx:afterRequest', { elt: el, successful: true });
        emit('htmx:afterSwap', { elt: target });
      });
    }).catch(function (error) {
      emit('htmx:responseError', { elt: el, error: error.message });
      var msg = document.getElementById('dashboard-toast');
      if (!msg) { msg = document.createElement('div'); msg.id = 'dashboard-toast'; msg.setAttribute('role', 'status'); msg.style.cssText = 'position:fixed;right:1rem;bottom:1rem;z-index:9999;max-width:32rem;padding:.8rem 1rem;border-radius:.75rem;background:#7f1d1d;color:#fff;box-shadow:0 8px 30px #0004'; document.body.appendChild(msg); }
      msg.textContent = 'No se pudo completar la solicitud. ' + error.message;
    });
  }
  document.addEventListener('click', function (event) {
    var el = event.target.closest('[hx-get], [hx-post], [hx-put], [hx-delete]');
    if (!el || el.matches('form')) return;
    if ((el.getAttribute('hx-trigger') || '').split(/\s+/).indexOf('change') >= 0) return;
    event.preventDefault();
    var method = el.hasAttribute('hx-post') ? 'POST' : (el.hasAttribute('hx-put') ? 'PUT' : (el.hasAttribute('hx-delete') ? 'DELETE' : 'GET'));
    request(el, method, el.getAttribute('hx-' + method.toLowerCase()));
  });
  document.addEventListener('change', function (event) {
    var el = event.target.closest ? event.target.closest('[hx-put][hx-trigger~=\"change\"], [hx-post][hx-trigger~=\"change\"], [hx-delete][hx-trigger~=\"change\"]') : null;
    if (!el || el.matches('form')) return;
    var method = el.hasAttribute('hx-post') ? 'POST' : (el.hasAttribute('hx-delete') ? 'DELETE' : 'PUT');
    var body = {};
    if (el.name) body[el.name] = el.value;
    request(el, method, el.getAttribute('hx-' + method.toLowerCase()), body);
  });
  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!form.matches('form[hx-get], form[hx-post], form[hx-put], form[hx-delete]')) return;
    event.preventDefault();
    var method = form.hasAttribute('hx-post') ? 'POST' : (form.hasAttribute('hx-put') ? 'PUT' : (form.hasAttribute('hx-delete') ? 'DELETE' : 'GET'));
    var url = form.getAttribute('hx-' + method.toLowerCase());
    request(form, method, url, new FormData(form));
  });
  window.htmx = {
    trigger: function (selector, name) {
      var el = typeof selector === 'string' ? document.querySelector(selector) : selector;
      if (!el) return;
      if (name === 'reload' && (el.hasAttribute('hx-get') || el.hasAttribute('hx-post'))) {
        var method = el.hasAttribute('hx-post') ? 'POST' : (el.hasAttribute('hx-put') ? 'PUT' : (el.hasAttribute('hx-delete') ? 'DELETE' : 'GET'));
    request(el, method, el.getAttribute('hx-' + method.toLowerCase()));
      } else el.dispatchEvent(new Event(name, { bubbles: true }));
    }
  };
})();
