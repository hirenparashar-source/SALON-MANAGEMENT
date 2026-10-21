/* ============================================================================
   SILLAGE core — API client, house formatting, accessibility controller,
   toasts, modals and the hash router shared by every page.
   Vanilla JS, no build step, no dependencies. CSP: script-src 'self'.
   ========================================================================== */
(function (global) {
  'use strict';

  var PREF_KEY = 'sillage.prefs.v1';
  var CACHE_KEY = 'sillage.bootstrap.v1';

  /* ---------------------------------------------------------------- prefs */
  var DEFAULT_PREFS = {
    theme: 'light', fontScale: 100, contrast: 'normal', motion: 'full',
    font: 'house', underline: 'off', spacing: 'normal', targets: 'normal',
    announcements: 'on', reduceData: 'off'
  };

  var prefs = (function () {
    var stored = {};
    try { stored = JSON.parse(global.localStorage.getItem(PREF_KEY) || '{}') || {}; } catch (e) { stored = {}; }
    var merged = {};
    Object.keys(DEFAULT_PREFS).forEach(function (k) {
      merged[k] = stored[k] === undefined || stored[k] === null ? DEFAULT_PREFS[k] : stored[k];
    });
    return merged;
  }());

  function clamp(n, lo, hi) { return Math.max(lo, Math.min(hi, n)); }

  function applyPrefs(serverPrefs) {
    if (serverPrefs && typeof serverPrefs === 'object') {
      Object.keys(serverPrefs).forEach(function (k) {
        if (DEFAULT_PREFS[k] !== undefined && serverPrefs[k] !== null && serverPrefs[k] !== undefined) prefs[k] = serverPrefs[k];
      });
    }
    var root = document.documentElement;
    root.setAttribute('data-theme', prefs.theme === 'dark' ? 'dark' : 'light');
    root.setAttribute('data-contrast', prefs.contrast === 'high' ? 'high' : 'normal');
    root.setAttribute('data-motion', prefs.motion === 'reduce' ? 'reduce' : 'full');
    root.setAttribute('data-font', prefs.font === 'dyslexia' ? 'dyslexia' : 'house');
    root.setAttribute('data-underline', prefs.underline === 'on' ? 'on' : 'off');
    root.setAttribute('data-spacing', prefs.spacing === 'relaxed' ? 'relaxed' : 'normal');
    root.setAttribute('data-targets', prefs.targets === 'large' ? 'large' : 'normal');
    root.style.setProperty('--fs', (clamp(Number(prefs.fontScale) || 100, 85, 150) / 100).toFixed(3));
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', prefs.theme === 'dark' ? '#0E0D0B' : '#F4F1EA');
  }

  function savePrefs(patch) {
    Object.keys(patch || {}).forEach(function (k) { if (DEFAULT_PREFS[k] !== undefined) prefs[k] = patch[k]; });
    try { global.localStorage.setItem(PREF_KEY, JSON.stringify(prefs)); } catch (e) { /* private mode */ }
    applyPrefs();
    announce('Preferences updated');
    return prefs;
  }

  function pushPrefs(patch) {
    savePrefs(patch);
    // best effort: keep the server copy in step when signed in
    api.put('/api/profile/preferences', prefs).catch(function () { /* guests just keep it local */ });
  }

  /* ------------------------------------------------------------ announcer */
  var liveRegion = null;
  function announce(message, polite) {
    if (prefs.announcements === 'off') return;
    if (!liveRegion) {
      liveRegion = document.createElement('div');
      liveRegion.setAttribute('role', 'status');
      liveRegion.setAttribute('aria-live', polite === false ? 'assertive' : 'polite');
      liveRegion.className = 'sr-only';
      document.body.appendChild(liveRegion);
    }
    liveRegion.textContent = '';
    global.setTimeout(function () { liveRegion.textContent = String(message || ''); }, 60);
  }

  /* ------------------------------------------------------------------ api */
  var csrfToken = null;
  function readCookie(name) {
    var parts = (document.cookie || '').split(';');
    for (var i = 0; i < parts.length; i++) {
      var kv = parts[i].trim().split('=');
      if (kv[0] === name) return decodeURIComponent(kv.slice(1).join('='));
    }
    return null;
  }

  function ApiError(status, code, message, details) {
    var err = new Error(message || ('Request failed (' + status + ')'));
    err.status = status; err.code = code; err.details = details || null;
    return err;
  }

  function api(method, path, body, opts) {
    opts = opts || {};
    var init = { method: method, headers: { 'Accept': 'application/json' }, credentials: 'same-origin' };
    if (body !== undefined && body !== null) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
      var token = csrfToken || readCookie('sillage_csrf');
      if (token && method !== 'GET') init.headers['X-CSRF-Token'] = token;
    }
    if (opts.signal) init.signal = opts.signal;
    return fetch(path, init).then(function (res) {
      var type = res.headers.get('content-type') || '';
      var payload = type.indexOf('application/json') >= 0 ? res.json() : res.text();
      return payload.then(function (data) {
        if (!res.ok) {
          var envelope = data && data.error ? data.error : {};
          throw ApiError(res.status, envelope.code || 'HTTP_' + res.status, envelope.message || res.statusText, envelope.details);
        }
        if (data && data.csrfToken) csrfToken = data.csrfToken;
        return data;
      });
    });
  }

  var client = {
    get: function (p, o) { return api('GET', p, null, o); },
    post: function (p, b, o) { return api('POST', p, b === undefined ? {} : b, o); },
    put: function (p, b, o) { return api('PUT', p, b === undefined ? {} : b, o); },
    patch: function (p, b, o) { return api('PATCH', p, b === undefined ? {} : b, o); },
    del: function (p, o) { return api('DELETE', p, null, o); },
    text: function (p) {
      return fetch(p, { credentials: 'same-origin' }).then(function (r) { return r.ok ? r.text() : Promise.reject(ApiError(r.status, 'HTTP_' + r.status, r.statusText)); });
    },
    download: function (url, filename) {
      var a = document.createElement('a');
      a.href = url; a.download = filename || ''; a.rel = 'noopener';
      document.body.appendChild(a); a.click(); a.remove();
    }
  };

  /* -------------------------------------------------------------- bootstrap */
  var bootstrapPromise = null;
  function bootstrap(force) {
    if (!force && bootstrapPromise) return bootstrapPromise;
    try {
      var cached = JSON.parse(global.localStorage.getItem(CACHE_KEY) || 'null');
      if (cached && !force && Date.now() - cached.at < 120000) {
        bootstrapPromise = Promise.resolve(cached.data);
        client.get('/api/bootstrap').then(function (fresh) {
          try { global.localStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), data: fresh })); } catch (e) {}
        }).catch(function () {});
        return bootstrapPromise;
      }
    } catch (e) { /* fall through to the network */ }
    bootstrapPromise = client.get('/api/bootstrap').then(function (data) {
      try { global.localStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), data: data })); } catch (e) {}
      if (data && data.session && data.session.preferences) applyPrefs(data.session.preferences);
      return data;
    });
    return bootstrapPromise;
  }

  /* --------------------------------------------------------------- format */
  function money(value, withSymbol) {
    var n = Number(value || 0);
    var s = n.toLocaleString('en-IN', { minimumFractionDigits: n % 1 === 0 ? 0 : 2, maximumFractionDigits: 2 });
    return withSymbol === false ? s : '\u20B9' + s;
  }
  function num(value, digits) {
    return Number(value || 0).toLocaleString('en-IN', { maximumFractionDigits: digits === undefined ? 0 : digits });
  }
  function pct(value, digits) { return Number(value || 0).toFixed(digits === undefined ? 0 : digits) + '%'; }
  function dateOnly(value) {
    if (!value) return '\u2014';
    var d = new Date(String(value).length <= 10 ? value + 'T00:00:00' : value);
    if (isNaN(d)) return String(value);
    return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
  }
  function dayLabel(value) {
    if (!value) return '\u2014';
    var d = new Date(String(value).length <= 10 ? value + 'T00:00:00' : value);
    return d.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' });
  }
  function timeOf(value) {
    if (!value) return '\u2014';
    var d = new Date(value);
    if (isNaN(d)) return String(value);
    return d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  }
  function stamp(value) {
    if (!value) return '\u2014';
    var d = new Date(value);
    if (isNaN(d)) return String(value);
    return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short' }) + ' \u00b7 ' + timeOf(value);
  }
  function duration(minutes) {
    var m = Number(minutes || 0);
    if (m < 60) return m + 'm';
    var h = Math.floor(m / 60);
    var rest = m % 60;
    return rest ? h + 'h ' + rest + 'm' : h + 'h';
  }
  function relative(value) {
    if (!value) return '\u2014';
    var then = new Date(value).getTime();
    if (isNaN(then)) return String(value);
    var diff = Date.now() - then;
    var abs = Math.abs(diff);
    var mins = Math.round(abs / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return (diff > 0 ? mins + ' min ago' : 'in ' + mins + ' min');
    var hours = Math.round(mins / 60);
    if (hours < 24) return diff > 0 ? hours + ' h ago' : 'in ' + hours + ' h';
    var days = Math.round(hours / 24);
    if (days < 30) return diff > 0 ? days + ' d ago' : 'in ' + days + ' d';
    var months = Math.round(days / 30);
    return diff > 0 ? months + ' mo ago' : 'in ' + months + ' mo';
  }
  function todayISO(offsetDays) {
    var d = new Date();
    d.setDate(d.getDate() + (offsetDays || 0));
    return d.toISOString().slice(0, 10);
  }
  function esc(value) {
    return String(value === undefined || value === null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function initials(name) {
    var parts = String(name || '').trim().split(/\s+/);
    return ((parts[0] || '')[0] || '') + ((parts[1] || '')[0] || '');
  }

  /* ---------------------------------------------------------------- toast */
  var toastHost = null;
  function toast(message, kind, title) {
    if (!toastHost) {
      toastHost = document.createElement('div');
      toastHost.className = 'toasts';
      toastHost.setAttribute('role', 'region');
      toastHost.setAttribute('aria-label', 'Notifications');
      document.body.appendChild(toastHost);
    }
    var node = document.createElement('div');
    node.className = 'toast' + (kind ? ' ' + kind : '');
    node.innerHTML = (title ? '<span class="t">' + esc(title) + '</span>' : '') + '<span>' + esc(message) + '</span>';
    toastHost.appendChild(node);
    announce((title ? title + '. ' : '') + message, kind === 'bad' ? false : true);
    global.setTimeout(function () {
      node.style.transition = 'opacity .3s, transform .3s';
      node.style.opacity = '0'; node.style.transform = 'translateY(8px)';
      global.setTimeout(function () { node.remove(); }, 320);
    }, kind === 'bad' ? 7000 : 4200);
    return node;
  }

  /* ---------------------------------------------------------------- modal */
  var openModal = null;
  function close() {
    if (!openModal) return;
    var host = openModal;
    openModal = null;
    host.remove();
    document.body.style.overflow = '';
    if (host.__restore && host.__restore.focus) host.__restore.focus();
  }
  function modal(options) {
    close();
    options = options || {};
    var backdrop = document.createElement('div');
    backdrop.className = 'modal-backdrop';
    backdrop.setAttribute('role', 'presentation');
    var box = document.createElement('div');
    box.className = 'modal' + (options.size ? ' ' + options.size : '');
    box.setAttribute('role', 'dialog');
    box.setAttribute('aria-modal', 'true');
    box.setAttribute('aria-label', options.title || 'Dialog');
    var head = document.createElement('header');
    head.innerHTML = '<div><p class="kicker plain" style="margin:0 0 .35rem">' + esc(options.kicker || 'Sillage') + '</p>' +
      '<h3 style="font-family:var(--display);font-size:1.5rem">' + (options.title || '') + '</h3></div>' +
      '<button class="link" type="button" aria-label="Close dialog">Close</button>';
    var body = document.createElement('div');
    body.className = 'body';
    if (typeof options.body === 'string') body.innerHTML = options.body;
    else if (options.body) body.appendChild(options.body);
    box.appendChild(head); box.appendChild(body);
    if (options.actions && options.actions.length) {
      var foot = document.createElement('footer');
      options.actions.forEach(function (action) {
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'btn ' + (action.kind || 'ghost');
        b.textContent = action.label;
        b.addEventListener('click', function () {
          if (action.onClick) { if (action.onClick(body, close) !== false) close(); } else close();
        });
        foot.appendChild(b);
      });
      box.appendChild(foot);
    }
    backdrop.appendChild(box);
    backdrop.__restore = document.activeElement;
    document.body.appendChild(backdrop);
    document.body.style.overflow = 'hidden';
    openModal = backdrop;
    head.querySelector('button').addEventListener('click', close);
    backdrop.addEventListener('mousedown', function (e) { if (e.target === backdrop) close(); });
    document.addEventListener('keydown', onKey);
    var first = box.querySelector('input, select, textarea, button.btn') || head.querySelector('button');
    if (first) first.focus();
    if (options.onOpen) options.onOpen(body, close);
    announce('Dialog opened: ' + (options.title || ''));
    return { root: box, body: body, close: close };
  }
  function onKey(e) {
    if (!openModal) return;
    if (e.key === 'Escape') { e.preventDefault(); close(); }
    if (e.key === 'Tab') {
      var focusables = openModal.querySelectorAll('a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
      if (!focusables.length) return;
      var first = focusables[0], last = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }
  }
  function confirmDialog(options) {
    return new Promise(function (resolve) {
      modal({
        title: options.title || 'Are you sure?',
        kicker: options.kicker || 'Confirm',
        body: '<p>' + esc(options.message || '') + '</p>',
        actions: [
          { label: options.cancelLabel || 'Cancel', kind: 'ghost', onClick: function () { resolve(false); } },
          { label: options.confirmLabel || 'Confirm', kind: options.danger ? 'danger' : 'solid', onClick: function () { resolve(true); } }
        ],
        onOpen: function (body, closeFn) {
          var host = openModal;
          if (host) host.addEventListener('mousedown', function (e) { if (e.target === host) resolve(false); });
        }
      });
    });
  }

  /* ---------------------------------------------------------------- router */
  function router(routes, fallback) {
    function current() {
      var hash = (global.location.hash || '').replace(/^#\/?/, '');
      var parts = hash.split('?');
      var path = '/' + (parts[0] || '').replace(/^\//, '');
      var query = {};
      (parts[1] || '').split('&').forEach(function (kv) {
        if (!kv) return;
        var i = kv.indexOf('=');
        query[decodeURIComponent(i < 0 ? kv : kv.slice(0, i))] = decodeURIComponent(i < 0 ? '' : kv.slice(i + 1));
      });
      return { path: path, query: query, hash: hash };
    }
    function run() {
      var loc = current();
      var segments = loc.path.split('/').filter(Boolean);
      for (var i = 0; i < routes.length; i++) {
        var route = routes[i];
        var want = route.path.split('/').filter(Boolean);
        if (want.length !== segments.length) continue;
        var params = {}; var ok = true;
        for (var j = 0; j < want.length; j++) {
          if (want[j].charAt(0) === ':') params[want[j].slice(1)] = decodeURIComponent(segments[j]);
          else if (want[j] !== segments[j]) { ok = false; break; }
        }
        if (ok) { route.render(loc.query, params); return; }
      }
      if (fallback) fallback(loc);
    }
    global.addEventListener('hashchange', run);
    return { go: run, current: current, stop: function () { global.removeEventListener('hashchange', run); } };
  }

  function go(hash) {
    if (global.location.hash === hash) routerRefresh();
    else global.location.hash = hash;
  }
  var refreshers = [];
  function routerRefresh() { refreshers.forEach(function (fn) { try { fn(); } catch (e) {} }); }
  function onRefresh(fn) { refreshers.push(fn); }

  /* ------------------------------------------------------------- utilities */
  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'class') node.className = attrs[k];
      else if (k === 'html') node.innerHTML = attrs[k];
      else if (k === 'text') node.textContent = attrs[k];
      else if (k.slice(0, 2) === 'on') node.addEventListener(k.slice(2).toLowerCase(), attrs[k]);
      else if (attrs[k] !== null && attrs[k] !== undefined) node.setAttribute(k, attrs[k]);
    });
    (children || []).forEach(function (c) { if (c) node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return node;
  }

  function debounce(fn, wait) {
    var timer = null;
    return function () {
      var args = arguments, self = this;
      global.clearTimeout(timer);
      timer = global.setTimeout(function () { fn.apply(self, args); }, wait || 250);
    };
  }

  function groupBy(list, keyFn) {
    var out = {};
    (list || []).forEach(function (item) {
      var key = keyFn(item);
      if (!out[key]) out[key] = [];
      out[key].push(item);
    });
    return out;
  }

  function statusTone(status) {
    var s = String(status || '').toUpperCase();
    if (s === 'PAID' || s === 'COMPLETED' || s === 'CONFIRMED' || s === 'ACTIVE' || s === 'SENT' || s === 'CAPTURED' || s === 'SUCCEEDED' || s === 'IN_CHAIR' || s === 'ARRIVED') return 'ok';
    if (s === 'PARTIAL' || s === 'PARTIALLY_REFUNDED' || s === 'REQUESTED' || s === 'QUEUED' || s === 'PENDING' || s === 'WAITLISTED' || s === 'LOW' || s === 'REQUIRES_ACTION') return 'warn';
    if (s === 'CANCELLED' || s === 'NO_SHOW' || s === 'FAILED' || s === 'DECLINED' || s === 'VOID' || s === 'REFUNDED' || s === 'OUT' || s === 'ARCHIVED' || s === 'LOCKED') return 'bad';
    return 'mut';
  }
  function badge(text, tone) {
    return '<span class="badge ' + (tone || statusTone(text)) + '">' + esc(text) + '</span>';
  }

  /* --------------------------------------------------------- accessibility */
  function openAccessibility() {
    var body = el('div');
    body.innerHTML =
      '<p class="lede" style="font-size:.98rem">Everything here is stored on this device, and on the server too once you sign in. Nothing is sent anywhere else.</p>' +
      '<div class="grid g2" style="gap:1.4rem">' +
        '<div><p class="tiny">Appearance</p><div class="chip-row" data-group="theme">' +
          '<button class="chip" data-value="light" type="button">Bone</button>' +
          '<button class="chip" data-value="dark" type="button">Ink</button></div></div>' +
        '<div><p class="tiny">Contrast</p><div class="chip-row" data-group="contrast">' +
          '<button class="chip" data-value="normal" type="button">House</button>' +
          '<button class="chip" data-value="high" type="button">High</button></div></div>' +
        '<div><p class="tiny">Text size</p><div class="row" style="align-items:center;grid-template-columns:1fr auto auto">' +
          '<input type="range" min="85" max="150" step="5" data-group="fontScale" aria-label="Text size">' +
          '<span class="num" data-out="fontScale"></span><button class="btn sm ghost" type="button" data-reset="fontScale">Reset</button></div></div>' +
        '<div><p class="tiny">Motion</p><div class="chip-row" data-group="motion">' +
          '<button class="chip" data-value="full" type="button">Full</button>' +
          '<button class="chip" data-value="reduce" type="button">Reduced</button></div></div>' +
        '<div><p class="tiny">Reading face</p><div class="chip-row" data-group="font">' +
          '<button class="chip" data-value="house" type="button">Jost</button>' +
          '<button class="chip" data-value="dyslexia" type="button">Dyslexia-friendly</button></div></div>' +
        '<div><p class="tiny">Links</p><div class="chip-row" data-group="underline">' +
          '<button class="chip" data-value="off" type="button">Colour only</button>' +
          '<button class="chip" data-value="on" type="button">Underlined</button></div></div>' +
        '<div><p class="tiny">Text spacing</p><div class="chip-row" data-group="spacing">' +
          '<button class="chip" data-value="normal" type="button">House</button>' +
          '<button class="chip" data-value="relaxed" type="button">Relaxed (WCAG 1.4.12)</button></div></div>' +
        '<div><p class="tiny">Touch targets</p><div class="chip-row" data-group="targets">' +
          '<button class="chip" data-value="normal" type="button">Normal</button>' +
          '<button class="chip" data-value="large" type="button">Large</button></div></div>' +
        '<div><p class="tiny">Spoken feedback</p><div class="chip-row" data-group="announcements">' +
          '<button class="chip" data-value="on" type="button">On</button>' +
          '<button class="chip" data-value="off" type="button">Off</button></div></div>' +
      '</div>' +
      '<hr class="rule">' +
      '<div class="between"><p class="hint" style="margin:0">Keyboard: <kbd>?</kbd> opens this panel, <kbd>Esc</kbd> closes it, <kbd>g</kbd> then <kbd>d</kbd>/<kbd>c</kbd>/<kbd>p</kbd> jumps around the console.</p>' +
      '<button class="link" type="button" data-restore>Restore defaults</button></div>';

    var handle = modal({ title: 'Reading &amp; access', kicker: 'Accessibility', body: body, size: 'wide', actions: [{ label: 'Done', kind: 'solid' }] });

    function sync() {
      body.querySelectorAll('[data-group]').forEach(function (group) {
        var key = group.getAttribute('data-group');
        if (group.tagName === 'INPUT') { group.value = prefs[key]; return; }
        group.querySelectorAll('.chip').forEach(function (chip) {
          chip.setAttribute('aria-pressed', String(chip.getAttribute('data-value') === String(prefs[key])));
        });
      });
      var out = body.querySelector('[data-out="fontScale"]');
      if (out) out.textContent = prefs.fontScale + '%';
    }
    body.addEventListener('click', function (e) {
      var chip = e.target.closest ? e.target.closest('.chip') : null;
      if (chip && chip.parentElement && chip.parentElement.hasAttribute('data-group')) {
        var patch = {}; patch[chip.parentElement.getAttribute('data-group')] = chip.getAttribute('data-value');
        pushPrefs(patch); sync();
        return;
      }
      if (e.target.hasAttribute && e.target.hasAttribute('data-reset')) {
        pushPrefs({ fontScale: 100 }); sync(); return;
      }
      if (e.target.hasAttribute && e.target.hasAttribute('data-restore')) {
        pushPrefs(DEFAULT_PREFS); sync(); toast('Defaults restored'); return;
      }
    });
    body.addEventListener('input', function (e) {
      if (e.target.getAttribute && e.target.getAttribute('data-group') === 'fontScale') {
        pushPrefs({ fontScale: Number(e.target.value) }); sync();
      }
    });
    sync();
    return handle;
  }

  function bindKeyboardShortcuts(map) {
    document.addEventListener('keydown', function (e) {
      var tag = (e.target.tagName || '').toLowerCase();
      if (tag === 'input' || tag === 'textarea' || tag === 'select' || e.target.isContentEditable) return;
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      var key = e.key.toLowerCase();
      if (key === '?' || (key === '/' && e.shiftKey)) { e.preventDefault(); openAccessibility(); return; }
      if (map && map[key]) { e.preventDefault(); map[key](e); }
    });
  }

  /* ------------------------------------------------------------ reveal/scroll */
  function observeReveals(root) {
    var nodes = (root || document).querySelectorAll('.reveal:not(.in)');
    if (!nodes.length) return;
    if (!('IntersectionObserver' in global)) { nodes.forEach(function (n) { n.classList.add('in'); }); return; }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) { entry.target.classList.add('in'); io.unobserve(entry.target); }
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.05 });
    nodes.forEach(function (n) { io.observe(n); });
  }

  function sparkline(values, opts) {
    opts = opts || {};
    var w = opts.width || 100, h = opts.height || 28;
    var data = (values || []).map(function (v) { return Number(v) || 0; });
    if (!data.length) return '';
    var max = Math.max.apply(null, data.concat([1]));
    var min = Math.min.apply(null, data.concat([0]));
    var span = (max - min) || 1;
    var step = data.length > 1 ? w / (data.length - 1) : w;
    var points = data.map(function (v, i) {
      return (i * step).toFixed(2) + ',' + (h - ((v - min) / span) * (h - 3) - 1.5).toFixed(2);
    }).join(' ');
    return '<svg viewBox="0 0 ' + w + ' ' + h + '" width="' + w + '" height="' + h + '" role="img" aria-label="trend">' +
      '<polyline fill="none" stroke="currentColor" stroke-width="1.4" points="' + points + '"/></svg>';
  }

  function bars(rows, opts) {
    opts = opts || {};
    var max = Math.max.apply(null, rows.map(function (r) { return r.value; }).concat([1]));
    return rows.map(function (r) {
      var width = Math.max(1.5, (r.value / max) * 100);
      return '<div class="between" style="gap:.8rem;margin-bottom:.42rem">' +
        '<span style="flex:0 0 ' + (opts.labelWidth || '11rem') + ';font-size:.86rem">' + esc(r.label) + '</span>' +
        '<span class="bar" style="flex:1"><i style="width:' + width.toFixed(1) + '%"></i></span>' +
        '<span class="num" style="flex:0 0 6.5rem;text-align:right">' + (r.display || money(r.value)) + '</span></div>';
    }).join('');
  }

  /* ------------------------------------------------------------------ export */
  global.Sillage = {
    version: '1.0.0',
    prefs: prefs, applyPrefs: applyPrefs, savePrefs: savePrefs, pushPrefs: pushPrefs,
    api: client, bootstrap: bootstrap,
    fmt: { money: money, num: num, pct: pct, date: dateOnly, day: dayLabel, time: timeOf, stamp: stamp,
           duration: duration, relative: relative, today: todayISO, esc: esc, initials: initials },
    toast: toast, announce: announce, modal: modal, closeModal: close, confirm: confirmDialog,
    router: router, go: go, onRefresh: onRefresh,
    el: el, debounce: debounce, groupBy: groupBy, badge: badge, statusTone: statusTone,
    a11y: openAccessibility, keys: bindKeyboardShortcuts, reveal: observeReveals,
    sparkline: sparkline, bars: bars
  };

  // apply stored preferences before the first paint of anything else
  applyPrefs();
  if (global.matchMedia && global.matchMedia('(prefers-reduced-motion: reduce)').matches && prefs.motion === 'full') {
    // honour the OS setting on first run only; the visitor can override it here
    try {
      if (!global.localStorage.getItem(PREF_KEY)) { prefs.motion = 'reduce'; applyPrefs(); }
    } catch (e) {}
  }
  if (global.matchMedia && global.matchMedia('(prefers-color-scheme: dark)').matches) {
    try {
      if (!global.localStorage.getItem(PREF_KEY)) { prefs.theme = 'dark'; applyPrefs(); }
    } catch (e) {}
  }
  document.addEventListener('DOMContentLoaded', function () {
    document.addEventListener('keydown', function (e) {
      if (e.key === '?' && !(e.target.tagName || '').match(/input|textarea|select/i)) openAccessibility();
    });
    bootstrap().catch(function () { /* the pages cope without it */ });
  });
}(window));
