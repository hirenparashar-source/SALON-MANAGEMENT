/* ============================================================================
   SILLAGE — the studio console.
   One hash-routed single-page app over the same API the public pages use.
   Role aware: a guest sees the welcome desk, a stylist the floor, an owner
   everything including the audit trail and the data room.
   ========================================================================== */
(function () {
  'use strict';

  var S = window.Sillage, api = S.api, fmt = S.fmt, esc = fmt.esc;

  var RANK = { GUEST: 0, CLIENT: 1, STYLIST: 2, RECEPTION: 3, MANAGER: 4, OWNER: 5 };
  var me = null;
  var boot = null;
  var router = null;
  var posCart = { customerId: null, customerName: '', items: [], products: [], promoCode: '', gratuity: 0, loyaltyPoints: 0, staffId: '', notes: '' };

  /* ------------------------------------------------------------- helpers */
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  function rank() { return me && me.role ? (RANK[me.role] || 0) : 0; }
  function can(min) { return rank() >= min; }
  function isStaff() { return rank() >= RANK.STYLIST; }

  function loading(text) {
    return '<div class="empty"><p class="big">' + esc(text || 'Reading the book') + '</p>' +
      '<p><span class="spinner"></span> <span class="muted">one moment</span></p></div>';
  }
  function emptyState(title, body) {
    return '<div class="empty"><p class="big">' + esc(title) + '</p><p>' + (body || '') + '</p></div>';
  }
  function errorState(err) {
    var detail = (err && err.details && err.details.length) ? err.details.join(' · ') : (err && err.message) || 'Unknown error';
    return '<div class="empty" style="border-color:var(--bad)"><p class="big">That did not work</p>' +
      '<p style="color:var(--bad)">' + esc(detail) + '</p></div>';
  }
  function panel(title, body, actions, opts) {
    opts = opts || {};
    return '<section class="panel' + (opts.cls ? ' ' + opts.cls : '') + '">' +
      (title ? '<header><h3>' + title + '</h3><div class="btn-row">' + (actions || '') + '</div></header>' : '') +
      '<div class="body' + (opts.flush ? ' flush' : '') + '">' + body + '</div></section>';
  }
  function kpis(list) {
    return '<div class="kpi-row">' + list.filter(Boolean).map(function (k) {
      var delta = '';
      if (k.delta !== undefined && k.delta !== null) {
        var cls = k.delta > 0 ? 'up' : (k.delta < 0 ? 'down' : 'flat');
        var arrow = k.delta > 0 ? '\u25B2' : (k.delta < 0 ? '\u25BC' : '\u25AC');
        delta = '<p class="d ' + cls + '">' + arrow + ' ' + fmt.pct(Math.abs(k.delta), 1) + ' <span class="muted">vs previous</span></p>';
      }
      return '<div class="stat"><p class="v">' + esc(k.value) + '</p><p class="k">' + esc(k.label) + '</p>' +
        delta + (k.note ? '<p class="d flat muted">' + esc(k.note) + '</p>' : '') + '</div>';
    }).join('') + '</div>';
  }
  function table(headers, rows, opts) {
    opts = opts || {};
    if (!rows.length) return emptyState(opts.emptyTitle || 'Nothing here yet', opts.emptyBody || '');
    return '<div class="scroll-x"><table class="table"><thead><tr>' +
      headers.map(function (h) { return '<th class="' + (h.cls || '') + '">' + h.label + '</th>'; }).join('') +
      '</tr></thead><tbody>' + rows.map(function (r) {
        return '<tr' + (r.attrs ? ' ' + r.attrs : '') + '>' + r.cells.map(function (c, i) {
          return '<td class="' + (headers[i] && headers[i].cls || '') + '">' + c + '</td>';
        }).join('') + '</tr>';
      }).join('') + '</tbody></table></div>';
  }
  function head(title, lede, actions) {
    return '<div class="view-head"><div><p class="kicker plain">' + esc(kickerFor(title)) + '</p>' +
      '<h1>' + title + '</h1>' + (lede ? '<p class="lede">' + lede + '</p>' : '') + '</div>' +
      '<div class="btn-row">' + (actions || '') + '</div></div>';
  }
  function kickerFor(title) {
    var map = { Dashboard: 'The floor today', Diary: 'The booking book', Clients: 'The client book',
      Menu: 'The index of services', 'Point of sale': 'The till', Invoices: 'Bills & receipts',
      Inventory: 'The shelf', Suggestions: 'The rule engine', Analytics: 'The numbers',
      Search: 'The index', Settings: 'The house rules', Audit: 'Who did what',
      Profile: 'Your record', Privacy: 'Your data', Accounts: 'Who may sign in',
      'Data room': 'Export, backup, restore' };
    var plain = String(title).replace(/<[^>]+>/g, '');
    return map[plain] || 'Studio console';
  }
  function gate(min, what) {
    return '<div class="gate"><div class="panel"><p class="kicker plain">Signed out</p>' +
      '<h2 style="font-size:1.9rem;max-width:18ch">' + esc(what || 'This room needs a sign-in') + '</h2>' +
      '<p style="color:var(--fg-soft);margin-top:.7rem">The public pages \u2014 the index, the lookbook and the booking book \u2014 need no account at all. ' +
      'The console does, because it holds other people\u2019s records.</p>' +
      '<div class="btn-row" style="margin-top:1.2rem">' +
      '<a class="btn solid" href="#/login">Sign in or create an account</a>' +
      '<button class="btn ghost" type="button" data-guest>Continue as a guest</button>' +
      '<a class="btn ghost" href="/book">Book a chair instead</a></div>' +
      '<p class="hint" style="margin-top:1rem">Demo sign-ins: <span class="mono">meher / Sillage#2026</span> (owner), ' +
      '<span class="mono">frontdesk / FrontDesk#2026</span>, <span class="mono">ishaan / Colour#2026</span>, ' +
      '<span class="mono">ritika / Ritika#2026</span> (client).</p></div></div>';
  }

  function setCrumb(title, note) {
    $('#crumb').innerHTML = 'Studio <b>' + title + '</b>' + (note ? ' <span class="muted">\u00b7 ' + esc(note) + '</span>' : '');
    document.title = String(title).replace(/<[^>]+>/g, '') + ' \u00b7 SILLAGE studio';
  }
  function setView(html) { $('#view').innerHTML = html; S.reveal($('#view')); }

  /* --------------------------------------------------------------- shell */
  var NAV = [
    { group: 'Floor', items: [
      { id: 'dashboard', label: 'Dashboard', n: '01', min: 0 },
      { id: 'diary', label: 'Diary', n: '02', min: 2 },
      { id: 'pos', label: 'Point of sale', n: '03', min: 2 }
    ]},
    { group: 'People', items: [
      { id: 'clients', label: 'Clients', n: '04', min: 2 },
      { id: 'team', label: 'The chairs', n: '05', min: 2 },
      { id: 'accounts', label: 'Accounts', n: '06', min: 5 }
    ]},
    { group: 'Selling', items: [
      { id: 'invoices', label: 'Bills & receipts', n: '07', min: 2 },
      { id: 'dayclose', label: 'Day close', n: '08', min: 3 },
      { id: 'menu', label: 'The index', n: '09', min: 3 },
      { id: 'inventory', label: 'The shelf', n: '10', min: 3 }
    ]},
    { group: 'Intelligence', items: [
      { id: 'suggestions', label: 'Suggestions', n: '11', min: 2 },
      { id: 'analytics', label: 'Analytics', n: '12', min: 3 },
      { id: 'search', label: 'Search index', n: '13', min: 1 }
    ]},
    { group: 'House', items: [
      { id: 'notifications', label: 'Outbox', n: '14', min: 3 },
      { id: 'audit', label: 'Audit trail', n: '15', min: 4 },
      { id: 'data', label: 'Data room', n: '16', min: 3 },
      { id: 'settings', label: 'Settings', n: '17', min: 4 }
    ]},
    { group: 'You', items: [
      { id: 'profile', label: 'Your record', n: '18', min: 1 },
      { id: 'privacy', label: 'Privacy', n: '19', min: 0 },
      { id: 'login', label: 'Sign in', n: '20', min: 0, hideIfSignedIn: true }
    ]}
  ];

  function renderRail() {
    var nav = $('#railNav');
    nav.innerHTML = '';
    NAV.forEach(function (group) {
      var items = group.items.filter(function (item) {
        if (item.hideIfSignedIn && me && me.authenticated && me.role !== 'GUEST') return false;
        return true;
      });
      if (!items.length) return;
      var label = document.createElement('p');
      label.className = 'rail-group';
      label.textContent = group.group;
      nav.appendChild(label);
      items.forEach(function (item) {
        var a = document.createElement('a');
        a.className = 'rail-link';
        a.href = '#/' + item.id;
        a.setAttribute('data-id', item.id);
        var locked = item.min > rank();
        a.innerHTML = '<span class="n">' + item.n + '</span><span>' + esc(item.label) + '</span>' +
          (locked ? '<span class="pill" title="Needs a higher role">\u25CF</span>' : '');
        nav.appendChild(a);
      });
    });
    var foot = $('#railFoot');
    if (me && me.authenticated && me.role !== 'GUEST') {
      foot.innerHTML = '<div class="who"><span class="avatar">' + esc(fmt.initials(me.displayName || me.role)) + '</span>' +
        '<span style="min-width:0"><b class="truncate" style="display:block;font-size:.9rem">' + esc(me.displayName || '') + '</b>' +
        '<span class="tiny">' + esc(me.roleLabel || me.role) + '</span></span></div>' +
        '<div class="btn-row" style="margin-top:.7rem"><button class="link" type="button" id="btnOut">Sign out</button>' +
        '<a class="link" href="#/profile">Your record</a></div>';
      $('#btnOut').addEventListener('click', signOut);
    } else {
      foot.innerHTML = '<p class="tiny" style="margin:0 0 .5rem">' +
        (me && me.role === 'GUEST' ? 'Browsing as a guest' : 'Not signed in') + '</p>' +
        '<a class="btn sm gold block" href="#/login">Sign in</a>' +
        '<p class="hint" style="margin:.6rem 0 0">Booking, the index and the lookbook need no account.</p>';
    }
    var salon = (boot && boot.salon) || {};
    $('#railSalon').textContent = salon.salonName ? salon.salonName + ' \u00b7 ' + (salon.city || '') : 'Maison de coiffure & beaut\u00e9';
    $('#footEngine').textContent = 'Suggestions: ' + ((boot && boot.engine) || 'deterministic rule engine') +
      ' \u00b7 Search: BM25 inverted index';
    $('#footVersion').textContent = 'v' + ((boot && boot.version) || S.version);
  }

  function markCurrent(id) {
    $$('#railNav .rail-link').forEach(function (a) {
      if (a.getAttribute('data-id') === id) a.setAttribute('aria-current', 'page');
      else a.removeAttribute('aria-current');
    });
  }

  function signOut() {
    api.post('/api/auth/logout').then(function () {
      me = null;
      S.toast('Signed out', 'ok');
      bootSession().then(function () { window.location.hash = '#/dashboard'; });
    }).catch(function (err) { S.toast(err.message, 'bad'); });
  }

  /* --------------------------------------------------------------- boot */
  function bootSession() {
    return api.get('/api/auth/me').then(function (session) {
      me = session;
      if (session && session.preferences) S.applyPrefs(session.preferences);
      renderRail();
      paintOpenBadge();
      return session;
    }).catch(function () {
      me = { authenticated: false, role: 'GUEST', roleLabel: 'Guest', capabilities: [] };
      renderRail();
      return me;
    });
  }

  function paintOpenBadge() {
    var open = (boot && boot.open) || {};
    var badge = $('#openBadge');
    badge.className = 'badge ' + (open.open ? 'ok' : 'mut');
    badge.textContent = open.open ? 'Open' : 'Closed';
    badge.title = open.detail || '';
  }

  function start() {
    $('#btnA11y').addEventListener('click', function () { S.a11y(); });
    $('#btnTheme').addEventListener('click', function () {
      S.pushPrefs({ theme: S.prefs.theme === 'dark' ? 'light' : 'dark' });
    });
    $('#railToggle').addEventListener('click', function () {
      var rail = $('#rail');
      rail.classList.toggle('open');
      $('#railScrim').classList.toggle('hide', !rail.classList.contains('open'));
      $('#railToggle').setAttribute('aria-expanded', String(rail.classList.contains('open')));
    });
    $('#railScrim').addEventListener('click', function () {
      $('#rail').classList.remove('open');
      $('#railScrim').classList.add('hide');
    });
    $('#globalSearch').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') {
        e.preventDefault();
        window.location.hash = '#/search?q=' + encodeURIComponent(e.target.value.trim());
      }
    });
    S.keys({
      d: function () { window.location.hash = '#/dashboard'; },
      c: function () { window.location.hash = '#/diary'; },
      p: function () { window.location.hash = '#/pos'; },
      i: function () { window.location.hash = '#/invoices'; },
      s: function () { window.location.hash = '#/suggestions'; },
      '/': function () { $('#globalSearch').focus(); }
    });

    Promise.all([S.bootstrap(), bootSession()]).then(function (results) {
      boot = results[0];
      paintOpenBadge();
      if (!window.location.hash) window.location.hash = '#/dashboard';
      router = S.router(routes(), fallback);
      router.go();
    }).catch(function (err) {
      setView('<div class="gate"><div class="panel"><p class="kicker plain">No answer</p>' +
        '<h2>The studio cannot reach the salon</h2><p style="margin-top:.7rem;color:var(--fg-soft)">' +
        esc(err && err.message || 'The server did not respond.') + '</p>' +
        '<div class="btn-row" style="margin-top:1.2rem"><button class="btn solid" type="button" onclick="location.reload()">Try again</button>' +
        '<a class="btn ghost" href="/">Front page</a></div></div></div>');
    });
  }

  function fallback(loc) {
    setView(emptyState('No such room', '<p>The console has no page filed under <span class="mono">' +
      esc(loc.path) + '</span>.</p><p><a class="link" href="#/dashboard">Back to the dashboard</a></p>'));
  }

  /* ------------------------------------------------------------- routes */
  function routes() {
    return [
      { path: '/login', render: viewLogin },
      { path: '/dashboard', render: viewDashboard },
      { path: '/diary', render: viewDiary },
      { path: '/pos', render: viewPos },
      { path: '/clients', render: viewClients },
      { path: '/clients/:id', render: viewClient },
      { path: '/team', render: viewTeam },
      { path: '/accounts', render: viewAccounts },
      { path: '/invoices', render: viewInvoices },
      { path: '/invoices/:id', render: viewInvoice },
      { path: '/dayclose', render: viewDayClose },
      { path: '/menu', render: viewMenu },
      { path: '/inventory', render: viewInventory },
      { path: '/suggestions', render: viewSuggestions },
      { path: '/analytics', render: viewAnalytics },
      { path: '/search', render: viewSearch },
      { path: '/notifications', render: viewOutbox },
      { path: '/audit', render: viewAudit },
      { path: '/data', render: viewData },
      { path: '/settings', render: viewSettings },
      { path: '/profile', render: viewProfile },
      { path: '/privacy', render: viewPrivacy },
      { path: '/sessions', render: viewProfile }
    ];
  }

  function routeId() { return (window.location.hash || '#/dashboard').replace(/^#\//, '').split('?')[0]; }

  function enter(id) {
    markCurrent(id);
    $('#view').scrollTop = 0;
    window.scrollTo({ top: 0, behavior: S.prefs.motion === 'reduce' ? 'auto' : 'smooth' });
  }

  /* =========================================================== login */
  function viewLogin(query) {
    enter('login');
    setCrumb('Sign in', 'optional');
    if (me && me.authenticated && me.role !== 'GUEST') {
      setView(head('You are signed in', 'As <b>' + esc(me.displayName || '') + '</b> (' + esc(me.roleLabel || me.role) + ').',
        '<a class="btn ghost" href="#/dashboard">Go to the dashboard</a>') +
        panel('Session', '<dl class="kv"><dt>Role</dt><dd>' + esc(me.roleLabel || '') + '</dd>' +
          '<dt>Since</dt><dd>' + esc(fmt.stamp(me.session && me.session.createdAt)) + '</dd>' +
          '<dt>Expires</dt><dd>' + esc(fmt.stamp(me.session && me.session.expiresAt)) + '</dd></dl>',
          '<button class="btn sm ghost" type="button" id="out2">Sign out</button>'));
      var o = $('#out2'); if (o) o.addEventListener('click', signOut);
      return;
    }
    setView(head('Sign in <em>or</em> carry on', 'An account is optional. Everything a guest needs \u2014 the index, the lookbook, a booking and a receipt \u2014 works without one.') +
      '<div class="gate" style="margin:0 auto"><div class="panel">' +
      '<div class="signin-tabs" role="tablist">' +
        '<button role="tab" aria-selected="true" data-tab="in" type="button">Sign in</button>' +
        '<button role="tab" aria-selected="false" data-tab="up" type="button">Create an account</button>' +
        '<button role="tab" aria-selected="false" data-tab="reset" type="button">Lost password</button>' +
      '</div>' +
      '<div id="tabIn">' +
        '<form id="loginForm" novalidate>' +
          '<div class="field"><label class="lbl" for="liUser">Username or email</label>' +
          '<input id="liUser" autocomplete="username" required></div>' +
          '<div class="field"><label class="lbl" for="liPass">Password</label>' +
          '<input id="liPass" type="password" autocomplete="current-password" required></div>' +
          '<p class="err" id="liErr" role="alert"></p>' +
          '<button class="btn solid block" type="submit">Sign in</button>' +
        '</form>' +
        '<p class="hint" style="margin-top:1rem">Demo: <span class="mono">meher / Sillage#2026</span> \u00b7 ' +
        '<span class="mono">frontdesk / FrontDesk#2026</span> \u00b7 <span class="mono">ishaan / Colour#2026</span> \u00b7 ' +
        '<span class="mono">ritika / Ritika#2026</span></p>' +
      '</div>' +
      '<div id="tabUp" class="hide">' +
        '<form id="registerForm" novalidate>' +
          '<div class="row"><div class="field"><label class="lbl" for="rUser">Username</label>' +
          '<input id="rUser" autocomplete="username" minlength="3" required></div>' +
          '<div class="field"><label class="lbl" for="rName">Your name</label>' +
          '<input id="rName" autocomplete="name" required></div></div>' +
          '<div class="row"><div class="field"><label class="lbl" for="rEmail">Email</label>' +
          '<input id="rEmail" type="email" autocomplete="email" required></div>' +
          '<div class="field"><label class="lbl" for="rPhone">Mobile</label>' +
          '<input id="rPhone" type="tel" autocomplete="tel" placeholder="+91 \u2026"></div></div>' +
          '<div class="row"><div class="field"><label class="lbl" for="rPass">Password</label>' +
          '<input id="rPass" type="password" autocomplete="new-password" required></div>' +
          '<div class="field"><label class="lbl" for="rPass2">Again</label>' +
          '<input id="rPass2" type="password" autocomplete="new-password" required></div></div>' +
          '<label class="check"><input type="checkbox" id="rCustomer" checked><span>Keep a client record for me, so my history and points follow me.</span></label>' +
          '<p class="err" id="rErr" role="alert"></p>' +
          '<button class="btn solid block" type="submit">Create the account</button>' +
          '<p class="hint" id="policyNote" style="margin-top:.8rem"></p>' +
        '</form>' +
      '</div>' +
      '<div id="tabReset" class="hide">' +
        '<form id="resetForm" novalidate>' +
          '<div class="field"><label class="lbl" for="rsId">Username or email</label><input id="rsId" required></div>' +
          '<button class="btn solid block" type="submit">Send a reset code</button>' +
          '<p class="hint" id="rsMsg" style="margin-top:.8rem" role="status"></p>' +
        '</form>' +
        '<hr class="rule">' +
        '<form id="resetConfirm" novalidate>' +
          '<div class="row"><div class="field"><label class="lbl" for="rcToken">Code</label><input id="rcToken"></div>' +
          '<div class="field"><label class="lbl" for="rcPass">New password</label><input id="rcPass" type="password"></div></div>' +
          '<button class="btn ghost block" type="submit">Set the new password</button>' +
        '</form>' +
      '</div>' +
      '<hr class="rule">' +
      '<div class="between" style="gap:1rem;flex-wrap:wrap">' +
        '<p class="hint" style="margin:0">No account? You do not need one.</p>' +
        '<div class="btn-row"><button class="btn ghost" type="button" id="guestBtn">Continue as a guest</button>' +
        '<a class="btn ghost" href="/book">Book a chair</a></div></div>' +
      '</div></div>');

    $$('.signin-tabs button').forEach(function (btn) {
      btn.addEventListener('click', function () {
        $$('.signin-tabs button').forEach(function (b) { b.setAttribute('aria-selected', 'false'); });
        btn.setAttribute('aria-selected', 'true');
        $('#tabIn').classList.toggle('hide', btn.getAttribute('data-tab') !== 'in');
        $('#tabUp').classList.toggle('hide', btn.getAttribute('data-tab') !== 'up');
        $('#tabReset').classList.toggle('hide', btn.getAttribute('data-tab') !== 'reset');
      });
    });
    $('#guestBtn').addEventListener('click', function () {
      api.post('/api/auth/guest', {}).then(function () {
        S.toast('You are browsing as a guest', 'ok');
        return bootSession();
      }).then(function () { window.location.hash = '#/dashboard'; })
        .catch(function (err) { S.toast(err.message, 'bad'); });
    });
    $('#loginForm').addEventListener('submit', function (e) {
      e.preventDefault();
      var err = $('#liErr'); err.textContent = '';
      api.post('/api/auth/login', { username: $('#liUser').value.trim(), password: $('#liPass').value })
        .then(function (result) {
          if (result.mustChangePassword) {
            S.modal({
              title: 'Choose a new password', kicker: 'First sign-in',
              body: '<p>' + esc(result.message || 'This account was created with a temporary password.') + '</p>' +
                '<div class="field"><label class="lbl" for="mcPass">New password</label><input id="mcPass" type="password"></div>' +
                '<p class="hint" id="mcHint"></p>',
              actions: [{ label: 'Set password', kind: 'solid', onClick: function (root) {
                return api.post('/api/auth/password', { current: $('#liPass').value, next: $('#mcPass', root).value })
                  .then(function () { S.toast('Password changed', 'ok'); afterAuth(result); })
                  .catch(function (e2) { $('#mcHint', root).textContent = e2.message; return false; });
              }}]
            });
            return;
          }
          afterAuth(result);
        })
        .catch(function (e2) {
          err.textContent = (e2.details && e2.details.length ? e2.details.join(' ') : e2.message) || 'Sign-in failed';
        });
    });
    $('#registerForm').addEventListener('submit', function (e) {
      e.preventDefault();
      var err = $('#rErr'); err.textContent = '';
      if ($('#rPass').value !== $('#rPass2').value) { err.textContent = 'The two passwords do not match.'; return; }
      api.post('/api/auth/register', {
        username: $('#rUser').value.trim(), displayName: $('#rName').value.trim(),
        email: $('#rEmail').value.trim(), phone: $('#rPhone').value.trim(),
        password: $('#rPass').value, createCustomerRecord: $('#rCustomer').checked
      }).then(afterAuth).catch(function (e2) {
        err.textContent = (e2.details && e2.details.length ? e2.details.join(' \u00b7 ') : e2.message) || 'Could not register';
      });
    });
    $('#resetForm').addEventListener('submit', function (e) {
      e.preventDefault();
      api.post('/api/auth/password/reset', { identifier: $('#rsId').value.trim() }).then(function (r) {
        $('#rsMsg').innerHTML = esc(r.message || '') + (r.devToken ? ' <b class="mono">dev code: ' + esc(r.devToken) + '</b>' : '');
      }).catch(function (e2) { $('#rsMsg').textContent = e2.message; });
    });
    $('#resetConfirm').addEventListener('submit', function (e) {
      e.preventDefault();
      api.post('/api/auth/password/confirm', { token: $('#rcToken').value.trim(), password: $('#rcPass').value })
        .then(function () { S.toast('Password reset \u2014 sign in with the new one', 'ok'); })
        .catch(function (e2) { S.toast(e2.message, 'bad'); });
    });
    api.get('/api/auth/policy').then(function (policy) {
      var note = $('#policyNote');
      if (note) note.innerHTML = 'Password rules: ' + esc((policy.rules || []).join(' \u00b7 ')) +
        ' \u00b7 ' + esc(policy.note || '');
    }).catch(function () {});
  }

  function afterAuth(result) {
    S.toast(result.greeting || ('Welcome, ' + (result.account && result.account.displayName || '')), 'ok', 'Signed in');
    return bootSession().then(function () {
      window.location.hash = (result.account && RANK[result.account.role] >= RANK.STYLIST) ? '#/dashboard' : '#/profile';
    });
  }

  /* ======================================================= dashboard */
  function viewDashboard() {
    enter('dashboard');
    if (!isStaff()) return viewGuestDashboard();
    setCrumb('Dashboard', fmt.day(new Date().toISOString().slice(0, 10)));
    setView(head('The floor, <em>today</em>', 'What is booked, what is owed, what needs ordering and what the engine suggests.',
      '<a class="btn ghost" href="#/diary">Open the diary</a><a class="btn solid" href="#/pos">Take a payment</a>') +
      '<div id="dashBody">' + loading('Reading today') + '</div>');

    var today = fmt.today(0);
    Promise.all([
      api.get('/api/diary?date=' + today),
      api.get('/api/analytics/overview?range=7d'),
      api.get('/api/suggestions/today?limit=6'),
      api.get('/api/products/low'),
      api.get('/api/customers/birthdays?days=14'),
      api.get('/api/invoices?status=UNPAID&limit=6'),
      api.get('/api/inventory/stats'),
      api.get('/api/day-close?date=' + today)
    ]).then(function (r) {
      var diary = r[0], week = r[1], today6 = r[2] || {}, low = r[3] || {}, bdays = r[4] || {},
        unpaid = r[5] || {}, stock = r[6] || {}, day = r[7] || {};
      var totals = diary.totals || {};
      var html = '';
      html += kpis([
        { label: 'Booked today', value: fmt.num(totals.appointments || 0), note: (totals.confirmed || 0) + ' confirmed, ' + (totals.requested || 0) + ' requested' },
        { label: 'Chair time', value: fmt.duration(totals.chairMinutes || 0), note: fmt.pct(Math.round((totals.chairMinutes || 0) / Math.max(1, totals.capacityMinutes || 1) * 100)) + ' of capacity' },
        { label: 'Billed today', value: fmt.money(day.gross || 0), note: (day.invoices || 0) + ' bills' },
        { label: 'Last 7 days', value: fmt.money(week.revenue || 0), delta: week.deltas ? week.deltas.revenue : null },
        { label: 'Outstanding', value: fmt.money(week.outstanding || 0), note: (unpaid.invoices || []).length + ' open bills shown' },
        { label: 'Stock value', value: fmt.money(stock.stockValue || 0), note: (stock.lowStock || 0) + ' below reorder point' }
      ]);
      html += '<div class="desk-cols" style="margin-top:1.6rem">';

      // today's chairs
      var chairs = (diary.columns || []).map(function (col) {
        var load = Math.round((col.bookedMinutes || 0) / Math.max(1, col.capacityMinutes || 1) * 100);
        return '<div class="card tight">' +
          '<div class="between"><b>' + esc(col.name) + '</b><span class="tiny">' + load + '%</span></div>' +
          '<div class="bar" style="margin:.5rem 0"><i style="width:' + load + '%"></i></div>' +
          (col.closed ? '<p class="hint">Not on the floor today</p>' :
            (col.appointments || []).slice(0, 4).map(function (a) {
              return '<div class="appt" data-status="' + esc(a.status) + '" data-id="' + esc(a.id) + '">' +
                '<span class="t">' + esc(fmt.time(a.start)) + ' \u00b7 ' + fmt.duration(a.duration) + '</span>' +
                '<span class="who">' + esc(a.customer || 'Walk-in') + '</span>' +
                '<span class="what">' + esc((a.services || []).join(', ')) + '</span></div>';
            }).join('') || '<p class="hint">Nothing booked yet</p>') +
          '</div>';
      }).join('');
      html += panel('The chairs today <span class="muted">\u00b7 ' + esc(diary.label || '') + '</span>',
        '<div class="grid g2" style="gap:.8rem">' + (chairs || '<p class="muted">Nobody on the floor.</p>') + '</div>',
        '<a class="btn sm ghost" href="#/diary">Full diary</a>', { flush: false });

      // suggestions
      var suggestions = (today6.suggestions || []).map(function (s) {
        return '<div class="suggestion" style="margin-bottom:.7rem"><div class="head">' +
          '<h4>' + esc(s.title) + '</h4><span class="badge ' + esc(s.confidence || 'mut') + '">' + esc(s.impact || s.confidence || '') + '</span></div>' +
          '<p class="why">' + esc(s.rationale || s.detail) + '</p>' +
          '<div class="rules">' + (s.rules || []).map(function (r2) { return '<code>' + esc(r2) + '</code>'; }).join('') + '</div></div>';
      }).join('');
      html += panel('The engine suggests <span class="muted">\u00b7 deterministic</span>',
        suggestions || '<p class="muted">Nothing to say yet \u2014 it needs a week of trading.</p>',
        '<a class="btn sm ghost" href="#/suggestions">All suggestions</a>');

      // low stock + birthdays + unpaid
      html += panel('Below the reorder point',
        table([{ label: 'Product' }, { label: 'Stock', cls: 'r' }, { label: 'Reorder at', cls: 'r' }, { label: '' }],
          (low.products || []).slice(0, 6).map(function (p) {
            return { cells: ['<b>' + esc(p.name) + '</b><br><span class="tiny">' + esc(p.brand || '') + '</span>',
              '<span class="num">' + p.stock + '</span>', '<span class="num">' + p.reorderLevel + '</span>',
              '<button class="link" type="button" data-stock="' + esc(p.id) + '">Receive</button>'] };
          }), { emptyTitle: 'The shelf is healthy', emptyBody: 'Nothing below its reorder point.' }),
        '<a class="btn sm ghost" href="#/inventory">The shelf</a>', { flush: true });

      html += panel('Birthdays in the fortnight',
        '<ul class="list-plain">' + (bdays.birthdays || []).slice(0, 6).map(function (b) {
          return '<li><span class="grow"><b>' + esc(b.name || b.firstName) + '</b> ' +
            '<span class="tiny">' + esc(b.when || b.date || '') + '</span></span>' +
            '<button class="link" type="button" data-client="' + esc(b.id) + '">Open</button></li>';
        }).join('') + '</ul>' || '<p class="muted">Nobody in the next fourteen days.</p>',
        '', { flush: false });

      html += panel('Open bills',
        '<ul class="list-plain">' + (unpaid.invoices || []).map(function (i) {
          return '<li><span class="grow"><b>' + esc(i.customerName || 'Guest') + '</b> ' +
            '<span class="tiny">' + esc(i.number) + ' \u00b7 ' + esc(fmt.date(i.createdAt)) + '</span></span>' +
            '<span class="num">' + esc(i.balanceLabel || fmt.money(i.balance)) + '</span>' +
            '<button class="link" type="button" data-invoice="' + esc(i.id) + '">Open</button></li>';
        }).join('') + '</ul>' || '<p class="muted">Everything is settled.</p>',
        '<a class="btn sm ghost" href="#/invoices">All bills</a>');

      html += '</div>';
      setView(head('The floor, <em>today</em>', 'What is booked, what is owed, what needs ordering and what the engine suggests.',
        '<a class="btn ghost" href="#/diary">Open the diary</a><a class="btn solid" href="#/pos">Take a payment</a>') + html);
      wireDash();
    }).catch(function (err) { $('#dashBody').innerHTML = errorState(err); });
  }

  function wireDash() {
    $$('#view [data-client]').forEach(function (b) {
      b.addEventListener('click', function () { window.location.hash = '#/clients/' + b.getAttribute('data-client'); });
    });
    $$('#view [data-invoice]').forEach(function (b) {
      b.addEventListener('click', function () { window.location.hash = '#/invoices/' + b.getAttribute('data-invoice'); });
    });
    $$('#view [data-stock]').forEach(function (b) {
      b.addEventListener('click', function () { window.location.hash = '#/inventory?receive=' + b.getAttribute('data-stock'); });
    });
    $$('#view .appt[data-id]').forEach(function (a) {
      a.addEventListener('click', function () { openAppointment(a.getAttribute('data-id')); });
    });
  }

  function viewGuestDashboard() {
    setCrumb('Welcome');
    var salon = (boot && boot.salon) || {};
    var menu = ((boot && boot.menu) || {}).services || [];
    var open = (boot && boot.open) || {};
    setView(head('Welcome to <em>' + esc(salon.salonName || 'SILLAGE') + '</em>',
      'You are browsing without an account, which is exactly how most guests use the site. Book a chair, read the index, check a gift card \u2014 no sign-up, nothing stored beyond the booking itself.') +
      '<div class="desk-cols">' +
        panel('Right now',
          '<p style="font-size:1.05rem">' + (open.open ? '<b style="color:var(--ok)">Open</b> ' : '<b>Closed</b> ') +
          esc(open.detail || '') + '</p><dl class="kv" style="margin-top:.8rem">' +
          '<dt>Services</dt><dd>' + menu.length + ' on the index</dd>' +
          '<dt>Chairs</dt><dd>' + ((boot && boot.staff) || []).length + ' stylists</dd>' +
          '<dt>Since</dt><dd>' + esc(salon.established || 1987) + '</dd></dl>',
          '<a class="btn sm solid" href="/book">Book a chair</a>') +
        panel('What you can do without signing in',
          '<ul class="list-plain">' +
          ['Read the whole index of services, with prices and honest durations',
           'See the lookbook and who did what',
           'Book a chair and pay a deposit by card or UPI',
           'Verify a paper receipt against the book',
           'Check a gift card balance',
           'Leave a note for the desk'].map(function (t) {
             return '<li><span class="grow">' + esc(t) + '</span><span class="badge ok">\u2713</span></li>';
           }).join('') + '</ul>', '<a class="btn sm ghost" href="/">Front page</a>') +
        panel('And with an account',
          '<ul class="list-plain">' +
          ['Your bookings, bills and receipts in one place',
           'Loyalty points and your tier, tracked automatically',
           'A copy of your record, or its erasure, in two clicks',
           'Your reading preferences follow you between devices'].map(function (t) {
             return '<li><span class="grow">' + esc(t) + '</span></li>';
           }).join('') + '</ul>', '<a class="btn sm gold" href="#/login">Sign in or register</a>') +
      '</div>');
  }

  /* =========================================================== diary */
  function viewDiary(query) {
    enter('diary');
    if (!can(2)) return setView(gate(2, 'The diary is for the floor'));
    var date = (query && query.date) || fmt.today(0);
    setCrumb('Diary', fmt.day(date));
    setView(head('The booking book', 'One column per chair. Click any appointment to move it through the day.',
      '<button class="btn ghost" type="button" id="dPrev">Previous day</button>' +
      '<input type="date" id="dDate" value="' + esc(date) + '" style="width:auto" aria-label="Choose a day">' +
      '<button class="btn ghost" type="button" id="dNext">Next day</button>' +
      '<button class="btn solid" type="button" id="dNew">New booking</button>') +
      '<div id="diaryBody">' + loading('Reading the diary') + '</div>');
    $('#dDate').addEventListener('change', function (e) { window.location.hash = '#/diary?date=' + e.target.value; });
    $('#dPrev').addEventListener('click', function () { shift(date, -1); });
    $('#dNext').addEventListener('click', function () { shift(date, 1); });
    $('#dNew').addEventListener('click', newBookingDialog);

    api.get('/api/diary?date=' + date).then(function (d) {
      var cols = d.columns || [];
      var totals = d.totals || {};
      var startHour = Number(String(d.dayStart || '10:00').slice(0, 2));
      var endHour = Number(String(d.dayEnd || '19:00').slice(0, 2));
      var grid = '<div class="diary" style="--chairs:' + cols.length + '">';
      grid += '<div class="dh">Hour</div>';
      cols.forEach(function (c) {
        var load = Math.round((c.bookedMinutes || 0) / Math.max(1, c.capacityMinutes || 1) * 100);
        grid += '<div class="dh"><b>' + esc(c.name) + '</b>' + esc(c.title || '') +
          '<span class="tiny">' + (c.closed ? 'off' : fmt.duration(c.bookedMinutes || 0) + ' of ' + fmt.duration(c.capacityMinutes || 0)) + '</span>' +
          '<span class="load"><i style="width:' + load + '%"></i></span></div>';
      });
      for (var h = startHour; h <= endHour; h++) {
        grid += '<div class="hour">' + (h < 10 ? '0' + h : h) + ':00</div>';
        cols.forEach(function (c) {
          var items = (c.appointments || []).filter(function (a) { return Number(String(a.start).slice(11, 13)) === h; });
          grid += '<div>' + (items.length ? items.map(function (a) {
            return '<div class="appt" data-status="' + esc(a.status) + '" data-id="' + esc(a.id) + '" tabindex="0" role="button">' +
              '<span class="t">' + esc(fmt.time(a.start)) + ' \u00b7 ' + fmt.duration(a.duration) + '</span>' +
              '<span class="who">' + esc(a.customer || 'Walk-in') + '</span>' +
              '<span class="what">' + esc((a.services || []).join(', ')) + '</span></div>';
          }).join('') : '<span class="tiny" style="opacity:.45">\u2014</span>') + '</div>';
        });
      }
      grid += '</div>';
      var html = kpis([
        { label: 'Appointments', value: fmt.num(totals.appointments || 0), note: (totals.completed || 0) + ' completed' },
        { label: 'Requested', value: fmt.num(totals.requested || 0), note: 'awaiting the desk' },
        { label: 'No-shows', value: fmt.num(totals.noShows || 0) },
        { label: 'Cancellations', value: fmt.num(totals.cancelled || 0) },
        { label: 'Chair minutes', value: fmt.duration(totals.chairMinutes || 0), note: fmt.pct(Math.round((totals.chairMinutes || 0) / Math.max(1, totals.capacityMinutes || 1) * 100)) + ' occupancy' },
        { label: 'Billed', value: fmt.money(totals.revenue || 0) }
      ]) + (d.holiday ? '<p class="hint" style="margin-top:1rem">House holiday: <b>' + esc(d.holiday) + '</b></p>' : '') +
        '<div style="margin-top:1.4rem">' + grid + '</div>' +
        '<p class="hint" style="margin-top:.8rem">Slots are ' + (d.slotMinutes || 30) + ' minutes apart. Click an appointment to move it through the day.</p>';
      $('#diaryBody').innerHTML = html;
      $$('#diaryBody .appt').forEach(function (a) {
        a.addEventListener('click', function () { openAppointment(a.getAttribute('data-id')); });
        a.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openAppointment(a.getAttribute('data-id')); } });
      });
    }).catch(function (err) { $('#diaryBody').innerHTML = errorState(err); });
  }

  function shift(date, days) {
    var d = new Date(date + 'T00:00:00');
    d.setDate(d.getDate() + days);
    window.location.hash = '#/diary?date=' + d.toISOString().slice(0, 10);
  }

  function openAppointment(id) {
    api.get('/api/appointments/' + encodeURIComponent(id)).then(function (detail) {
      var a = detail.appointment || detail;
      var summary = detail.summary || {};
      var next = detail.nextStates || [];
      var body = document.createElement('div');
      body.innerHTML =
        '<p class="tiny" style="margin-top:0">' + esc(a.code || '') + ' \u00b7 ' + S.badge(a.status) + ' \u00b7 ' + esc(a.source || '') + '</p>' +
        '<dl class="kv" style="margin:.8rem 0">' +
          '<dt>Client</dt><dd>' + esc(summary.customer || a.guestName || 'Walk-in') + (summary.phone ? ' <span class="mono">\u00b7 ' + esc(summary.phone) + '</span>' : '') + '</dd>' +
          '<dt>Chair</dt><dd>' + esc(summary.staff || '') + '</dd>' +
          '<dt>When</dt><dd>' + esc(fmt.stamp(a.start)) + ' \u2013 ' + esc(fmt.time(a.end)) + ' (' + fmt.duration(a.duration || a.durationMinutes) + ')</dd>' +
          '<dt>Services</dt><dd>' + esc((summary.services || a.services || []).join(', ')) + '</dd>' +
          '<dt>Price</dt><dd>' + fmt.money(summary.price || a.price) + '</dd>' +
          (a.notes ? '<dt>Notes</dt><dd>' + esc(a.notes) + '</dd>' : '') +
          (a.internalNotes ? '<dt>Internal</dt><dd>' + esc(a.internalNotes) + '</dd>' : '') +
          (summary.invoiceNumber ? '<dt>Bill</dt><dd><a class="link" href="#/invoices/' + esc(summary.invoiceId || '') + '">' + esc(summary.invoiceNumber) + '</a></dd>' : '') +
          (a.rating ? '<dt>Rated</dt><dd>\u2605 ' + a.rating + ' ' + esc(a.ratingComment || '') + '</dd>' : '') +
        '</dl>' +
        (summary.customerId ? '<p><a class="link" href="#/clients/' + esc(summary.customerId) + '">Open the client record</a></p>' : '') +
        '<p class="tiny" style="margin-top:1rem">Move it along</p>' +
        '<div class="chip-row" style="margin-top:.4rem">' +
        (next.length ? next.map(function (s) {
          return '<button class="chip" type="button" data-status="' + esc(s) + '">' + esc(s.replace('_', ' ').toLowerCase()) + '</button>';
        }).join('') : '<span class="hint">No further moves from here.</span>') + '</div>' +
        '<div class="field" style="margin-top:1rem"><label class="lbl" for="apNote">Note for the book</label>' +
        '<input id="apNote" placeholder="Optional"></div>' +
        '<div class="btn-row" style="margin-top:.6rem">' +
        '<button class="btn sm ghost" type="button" data-act="bill">Bill this visit</button>' +
        '<button class="btn sm ghost" type="button" data-act="move">Reschedule</button>' +
        '<button class="btn sm danger" type="button" data-act="cancel">Cancel</button></div>' +
        '<p class="hint" id="apMsg" role="status" style="margin-top:.7rem"></p>';
      var handle = S.modal({ title: esc(summary.customer || a.guestName || 'Appointment'), kicker: 'Booking ' + esc(a.code || ''), body: body, size: 'wide' });
      body.addEventListener('click', function (e) {
        var chip = e.target.closest ? e.target.closest('[data-status]') : null;
        if (chip) {
          api.patch('/api/appointments/' + encodeURIComponent(id) + '/status',
            { status: chip.getAttribute('data-status'), note: $('#apNote', body).value.trim() })
            .then(function () { S.toast('Moved to ' + chip.textContent, 'ok'); handle.close(); refresh(); })
            .catch(function (err) { $('#apMsg', body).textContent = err.message; });
          return;
        }
        var act = e.target.getAttribute && e.target.getAttribute('data-act');
        if (act === 'bill') {
          api.post('/api/invoices/from-appointment/' + encodeURIComponent(id), {}).then(function (inv) {
            handle.close();
            window.location.hash = '#/invoices/' + inv.id;
          }).catch(function (err) { $('#apMsg', body).textContent = err.message; });
        } else if (act === 'cancel') {
          S.confirm({ title: 'Cancel this booking?', message: 'The chair is released and the client is told.', danger: true, confirmLabel: 'Cancel it' })
            .then(function (yes) {
              if (!yes) return;
              api.post('/api/appointments/' + encodeURIComponent(id) + '/cancel', { reason: 'Cancelled at the desk' })
                .then(function () { S.toast('Cancelled', 'ok'); handle.close(); refresh(); })
                .catch(function (err) { $('#apMsg', body).textContent = err.message; });
            });
        } else if (act === 'move') {
          var input = document.createElement('input');
          input.type = 'datetime-local';
          input.value = String(a.start || '').slice(0, 16);
          input.className = 'input';
          var wrap = document.createElement('div');
          wrap.appendChild(input);
          S.modal({
            title: 'Reschedule', kicker: esc(a.code || ''), body: wrap,
            actions: [{ label: 'Move it', kind: 'solid', onClick: function () {
              return api.post('/api/appointments/' + encodeURIComponent(id) + '/reschedule', { start: input.value })
                .then(function () { S.toast('Rescheduled', 'ok'); handle.close(); refresh(); })
                .catch(function (err) { S.toast(err.message, 'bad'); return false; });
            }}]
          });
        }
      });
    }).catch(function (err) { S.toast(err.message, 'bad'); });
  }

  function newBookingDialog() {
    var services = ((boot.menu || {}).services || []);
    var staff = boot.staff || [];
    var body = document.createElement('div');
    body.innerHTML =
      '<div class="row">' +
        '<div class="field"><label class="lbl" for="nbService">Service</label><select id="nbService">' +
          services.map(function (s) { return '<option value="' + esc(s.id) + '">' + esc(s.name) + ' \u00b7 ' + fmt.money(s.price) + '</option>'; }).join('') +
        '</select></div>' +
        '<div class="field"><label class="lbl" for="nbStaff">Chair</label><select id="nbStaff">' +
          '<option value="">First available</option>' +
          staff.map(function (m) { return '<option value="' + esc(m.id) + '">' + esc(m.name) + '</option>'; }).join('') +
        '</select></div>' +
      '</div>' +
      '<div class="row">' +
        '<div class="field"><label class="lbl" for="nbStart">When</label><input id="nbStart" type="datetime-local"></div>' +
        '<div class="field"><label class="lbl" for="nbPhone">Mobile</label><input id="nbPhone" type="tel" placeholder="+91 \u2026"></div>' +
      '</div>' +
      '<div class="field"><label class="lbl" for="nbName">Name</label><input id="nbName" placeholder="Walk-in or a known client"></div>' +
      '<div class="field"><label class="lbl" for="nbNotes">Notes</label><input id="nbNotes"></div>' +
      '<p class="hint">Desk bookings go straight into the book as confirmed.</p>' +
      '<p class="err" id="nbErr" role="alert"></p>';
    S.modal({
      title: 'Write in a booking', kicker: 'The diary', body: body, size: 'wide',
      actions: [{
        label: 'Book the chair', kind: 'solid', onClick: function (root) {
          var start = $('#nbStart', root).value;
          if (!start) { $('#nbErr', root).textContent = 'A start time is required'; return false; }
          return api.post('/api/appointments', {
            items: [{ serviceId: $('#nbService', root).value }],
            staffId: $('#nbStaff', root).value || null,
            start: start,
            guestName: $('#nbName', root).value.trim(),
            guestPhone: $('#nbPhone', root).value.trim(),
            notes: $('#nbNotes', root).value.trim(),
            source: 'desk'
          }).then(function () { S.toast('Written into the book', 'ok'); refresh(); })
            .catch(function (err) {
              $('#nbErr', root).textContent = (err.details || []).join(' \u00b7 ') || err.message;
              return false;
            });
        }
      }, { label: 'Cancel', kind: 'ghost' }]
    });
  }

  /* ============================================================= POS */
  function viewPos(query) {
    enter('pos');
    if (!can(2)) return setView(gate(2, 'The till is for staff'));
    setCrumb('Point of sale');
    var services = ((boot.menu || {}).services || []);
    setView(head('The <em>till</em>', 'Build the bill as the chair finishes. Tax, rounding, loyalty and the receipt are all handled here.',
      '<button class="btn ghost" type="button" id="posClear">Clear the cart</button>' +
      '<button class="btn solid" type="button" id="posQuote">Price it</button>') +
      '<div class="pos-grid">' +
        '<div>' +
          panel('Find a client <span class="muted">\u00b7 optional</span>',
            '<div class="row" style="grid-template-columns:1fr auto">' +
            '<input id="posFind" type="search" placeholder="Name, mobile or code \u2014 the index answers as you type" autocomplete="off">' +
            '<button class="btn ghost" type="button" id="posWalk">Walk-in</button></div>' +
            '<div id="posFindOut" style="margin-top:.8rem"></div>', '') +
          '<div style="margin-top:1.2rem">' +
          panel('Services', '<input id="posFilter" type="search" placeholder="Filter the index\u2026" style="margin-bottom:.8rem">' +
            '<div class="pick-grid" id="posServices"></div>', '', { flush: false }) +
          '</div>' +
          '<div style="margin-top:1.2rem">' +
          panel('From the shelf', '<div class="pick-grid" id="posProducts"></div>', '') +
          '</div>' +
        '</div>' +
        '<div>' +
          panel('The bill', '<div id="posCart"></div>' +
            '<div class="field" style="margin-top:1rem"><label class="lbl" for="posPromo">Promotion code</label>' +
            '<input id="posPromo" placeholder="ATELIER500" value="' + esc(posCart.promoCode) + '"></div>' +
            '<div class="row">' +
            '<div class="field"><label class="lbl" for="posGratuity">Gratuity</label><input id="posGratuity" type="number" min="0" step="50" value="' + (posCart.gratuity || 0) + '"></div>' +
            '<div class="field"><label class="lbl" for="posPoints">Loyalty points to redeem</label><input id="posPoints" type="number" min="0" step="10" value="' + (posCart.loyaltyPoints || 0) + '"></div>' +
            '</div>' +
            '<div id="posQuoteOut"></div>' +
            '<div class="btn-row" style="margin-top:1rem">' +
            '<button class="btn ghost" type="button" id="posSave">Save as a bill</button>' +
            '<button class="btn solid" type="button" id="posTake" disabled>Take payment</button></div>' +
            '<p class="hint" id="posMsg" role="status" style="margin-top:.7rem"></p>', '') +
        '</div>' +
      '</div>');

    renderPosServices('');
    renderPosProducts();
    renderPosCart();
    $('#posFilter').addEventListener('input', S.debounce(function (e) { renderPosServices(e.target.value.trim().toLowerCase()); }, 140));
    $('#posFind').addEventListener('input', S.debounce(function (e) { findPosClient(e.target.value.trim()); }, 220));
    $('#posWalk').addEventListener('click', function () {
      posCart.customerId = null; posCart.customerName = 'Walk-in guest';
      $('#posFindOut').innerHTML = '<p class="hint">Walk-in: no record will be kept beyond the bill itself.</p>';
      renderPosCart();
    });
    $('#posClear').addEventListener('click', function () {
      posCart = { customerId: null, customerName: '', items: [], products: [], promoCode: '', gratuity: 0, loyaltyPoints: 0, staffId: '', notes: '' };
      $('#posQuoteOut').innerHTML = ''; renderPosCart(); S.toast('Cart cleared');
    });
    $('#posQuote').addEventListener('click', quoteCart);
    $('#posSave').addEventListener('click', saveBill);
    $('#posTake').addEventListener('click', takePayment);
    ['posPromo', 'posGratuity', 'posPoints'].forEach(function (id) {
      $('#' + id).addEventListener('change', function (e) {
        posCart[id === 'posPromo' ? 'promoCode' : (id === 'posGratuity' ? 'gratuity' : 'loyaltyPoints')] =
          id === 'posPromo' ? e.target.value.trim() : Number(e.target.value || 0);
        quoteCart();
      });
    });
    if (query && query.client) {
      api.get('/api/customers/' + encodeURIComponent(query.client)).then(function (profile) {
        posCart.customerId = profile.customer.id;
        posCart.customerName = profile.display.firstName + ' ' + (profile.display.lastName || '');
        $('#posFindOut').innerHTML = clientLine(profile);
        renderPosCart();
      }).catch(function () {});
    }
  }

  function clientLine(profile) {
    var c = profile.customer || profile.display || {};
    var loy = profile.loyalty || {};
    return '<div class="card tight"><div class="between"><b>' + esc(c.name || ((c.firstName || '') + ' ' + (c.lastName || ''))) + '</b>' +
      S.badge(c.tier || '', 'gold') + '</div>' +
      '<p class="mono" style="margin:.3rem 0 0;font-size:.72rem">' + esc(c.phone || '') + ' \u00b7 ' + esc(c.code || '') + '</p>' +
      '<p class="hint" style="margin:.4rem 0 0">' + fmt.num(loy.points || 0) + ' points worth ' + fmt.money(loy.value || 0) +
      ' \u00b7 ' + fmt.num((profile.stats || {}).visits || 0) + ' visits \u00b7 LTV ' + fmt.money((profile.stats || {}).lifetimeValue || 0) + '</p>' +
      '<div class="btn-row" style="margin-top:.6rem"><button class="link" type="button" data-open="' + esc(c.id) + '">Open the record</button></div></div>';
  }

  function findPosClient(q) {
    var out = $('#posFindOut');
    if (!out) return;
    if (!q || q.length < 2) { out.innerHTML = ''; return; }
    api.get('/api/search?q=' + encodeURIComponent(q) + '&kind=customer&limit=6').then(function (result) {
      var hits = result.hits || [];
      if (!hits.length) { out.innerHTML = '<p class="hint">No client under \u201C' + esc(q) + '\u201D. <button class="link" type="button" id="newClient">Create one</button></p>';
        var nc = $('#newClient'); if (nc) nc.addEventListener('click', newClientDialog); return; }
      out.innerHTML = '<ul class="list-plain">' + hits.map(function (h) {
        return '<li><span class="grow"><b>' + esc(h.title) + '</b><br><span class="tiny">' + esc(h.subtitle || '') + '</span></span>' +
          '<span class="mono">' + esc((h.attrs || {}).phone || '') + '</span>' +
          '<button class="link" type="button" data-pick="' + esc(h.id) + '">Use</button></li>';
      }).join('') + '</ul>';
      $$('[data-pick]', out).forEach(function (b) {
        b.addEventListener('click', function () {
          api.get('/api/customers/' + b.getAttribute('data-pick')).then(function (profile) {
            posCart.customerId = (profile.customer || profile.display).id;
            posCart.customerName = profile.display.firstName + ' ' + (profile.display.lastName || '');
            out.innerHTML = clientLine(profile);
            $$('#view [data-open]').forEach(function (o) {
              o.addEventListener('click', function () { window.location.hash = '#/clients/' + o.getAttribute('data-open'); });
            });
            renderPosCart();
          });
        });
      });
    }).catch(function () { out.innerHTML = ''; });
  }

  function newClientDialog() {
    var body = document.createElement('div');
    body.innerHTML = '<div class="row">' +
      '<div class="field"><label class="lbl" for="ncFirst">First name</label><input id="ncFirst" required></div>' +
      '<div class="field"><label class="lbl" for="ncLast">Last name</label><input id="ncLast"></div></div>' +
      '<div class="row"><div class="field"><label class="lbl" for="ncPhone">Mobile</label><input id="ncPhone" type="tel" required></div>' +
      '<div class="field"><label class="lbl" for="ncEmail">Email</label><input id="ncEmail" type="email"></div></div>' +
      '<div class="row"><div class="field"><label class="lbl" for="ncBirthday">Birthday</label><input id="ncBirthday" type="date"></div>' +
      '<div class="field"><label class="lbl" for="ncGender">Gender</label><select id="ncGender"><option value="">Unsaid</option>' +
      '<option>female</option><option>male</option><option>other</option></select></div></div>' +
      '<div class="field"><label class="lbl" for="ncNotes">Notes</label><textarea id="ncNotes"></textarea></div>' +
      '<p class="err" id="ncErr" role="alert"></p>';
    S.modal({
      title: 'A new client', kicker: 'The book', body: body,
      actions: [{ label: 'Create the record', kind: 'solid', onClick: function (root) {
        return api.post('/api/customers', {
          firstName: $('#ncFirst', root).value.trim(), lastName: $('#ncLast', root).value.trim(),
          phone: $('#ncPhone', root).value.trim(), email: $('#ncEmail', root).value.trim(),
          birthday: $('#ncBirthday', root).value || null, gender: $('#ncGender', root).value || null,
          notes: $('#ncNotes', root).value.trim()
        }).then(function (customer) {
          posCart.customerId = customer.id;
          posCart.customerName = (customer.firstName || '') + ' ' + (customer.lastName || '');
          S.toast('Record created', 'ok');
          var out = $('#posFindOut');
          if (out) out.innerHTML = '<p class="hint">Using <b>' + esc(posCart.customerName) + '</b></p>';
          renderPosCart();
        }).catch(function (err) { $('#ncErr', root).textContent = (err.details || []).join(' \u00b7 ') || err.message; return false; });
      }}, { label: 'Cancel', kind: 'ghost' }]
    });
  }

  function renderPosServices(filter) {
    var host = $('#posServices');
    if (!host) return;
    var services = ((boot.menu || {}).services || []).filter(function (s) {
      return !filter || (s.name + ' ' + (s.tagline || '')).toLowerCase().indexOf(filter) >= 0;
    });
    host.innerHTML = services.map(function (s) {
      return '<button class="pick" type="button" data-service="' + esc(s.id) + '">' +
        '<span class="meta">' + esc(s.categoryName || '') + '</span>' +
        '<span class="name">' + esc(s.name) + '</span>' +
        '<span class="meta"><b>' + fmt.money(s.price) + '</b><span>' + fmt.duration(s.durationMinutes) + '</span></span></button>';
    }).join('');
    $$('[data-service]', host).forEach(function (b) {
      b.addEventListener('click', function () {
        var id = b.getAttribute('data-service');
        var service = ((boot.menu || {}).services || []).filter(function (s) { return s.id === id; })[0];
        var existing = posCart.items.filter(function (i) { return i.serviceId === id; })[0];
        if (existing) existing.qty += 1;
        else posCart.items.push({ serviceId: id, qty: 1, staffId: posCart.staffId || null, name: service.name, price: Number(service.price) });
        renderPosCart();
      });
    });
  }

  function renderPosProducts() {
    var host = $('#posProducts');
    if (!host) return;
    api.get('/api/products').then(function (result) {
      var products = result.products || [];
      host.innerHTML = products.map(function (p) {
        return '<button class="pick" type="button" data-product="' + esc(p.id) + '"' + (p.stock <= 0 ? ' disabled' : '') + '>' +
          '<span class="meta">' + esc(p.brand || '') + '</span><span class="name">' + esc(p.name) + '</span>' +
          '<span class="meta"><b>' + fmt.money(p.price) + '</b><span>' + p.stock + ' in stock</span></span></button>';
      }).join('') || '<p class="hint">The shelf is empty.</p>';
      $$('[data-product]', host).forEach(function (b) {
        b.addEventListener('click', function () {
          var id = b.getAttribute('data-product');
          var product = products.filter(function (p) { return p.id === id; })[0];
          var existing = posCart.products.filter(function (i) { return i.productId === id; })[0];
          if (existing) existing.qty += 1;
          else posCart.products.push({ productId: id, qty: 1, name: product.name, price: Number(product.price) });
          renderPosCart();
        });
      });
    }).catch(function () { host.innerHTML = '<p class="hint">Could not read the shelf.</p>'; });
  }

  function renderPosCart() {
    var host = $('#posCart');
    if (!host) return;
    var lines = posCart.items.map(function (item, i) {
      return '<div class="cart-line"><span><b>' + esc(item.name) + '</b><br><span class="tiny">service' +
        (item.staffId ? ' \u00b7 ' + esc(staffName(item.staffId)) : '') + '</span></span>' +
        '<span class="qty"><button type="button" data-dec="item' + i + '">\u2212</button>' +
        '<span class="num">' + item.qty + '</span><button type="button" data-inc="item' + i + '">+</button></span>' +
        '<span class="num">' + fmt.money(item.price * item.qty) + '</span></div>';
    }).join('') + posCart.products.map(function (item, i) {
      return '<div class="cart-line"><span><b>' + esc(item.name) + '</b><br><span class="tiny">retail</span></span>' +
        '<span class="qty"><button type="button" data-dec="prod' + i + '">\u2212</button>' +
        '<span class="num">' + item.qty + '</span><button type="button" data-inc="prod' + i + '">+</button></span>' +
        '<span class="num">' + fmt.money(item.price * item.qty) + '</span></div>';
    }).join('');
    var total = posCart.items.concat(posCart.products).reduce(function (sum, l) { return sum + l.price * l.qty; }, 0);
    host.innerHTML = (lines || '<p class="hint">Nothing on the bill yet \u2014 pick a service from the index.</p>') +
      '<div class="between" style="margin-top:.9rem;padding-top:.8rem;border-top:2px solid var(--fg)">' +
      '<span class="tiny">Gross before tax</span><span class="num" style="font-size:1.2rem">' + fmt.money(total) + '</span></div>' +
      '<p class="hint" style="margin-top:.5rem">Client: <b>' + esc(posCart.customerName || 'not chosen') + '</b>' +
      (posCart.customerId ? ' <button class="link" type="button" id="posDrop">clear</button>' : '') + '</p>';
    var drop = $('#posDrop');
    if (drop) drop.addEventListener('click', function () {
      posCart.customerId = null; posCart.customerName = ''; posCart.loyaltyPoints = 0;
      var pts = $('#posPoints'); if (pts) pts.value = 0;
      renderPosCart();
    });
    $$('[data-inc], [data-dec]', host).forEach(function (b) {
      b.addEventListener('click', function () {
        var key = b.getAttribute('data-inc') || b.getAttribute('data-dec');
        var delta = b.hasAttribute('data-inc') ? 1 : -1;
        var kind = key.slice(0, 4) === 'item' ? posCart.items : posCart.products;
        var index = Number(key.replace(/^\D+/, ''));
        kind[index].qty = Math.max(0, kind[index].qty + delta);
        if (kind[index].qty === 0) kind.splice(index, 1);
        renderPosCart();
      });
    });
  }

  function staffName(id) {
    var found = (boot.staff || []).filter(function (m) { return m.id === id; })[0];
    return found ? found.name : id;
  }

  function cartPayload() {
    return {
      customerId: posCart.customerId || null,
      customerName: posCart.customerName || null,
      items: posCart.items.map(function (i) { return { serviceId: i.serviceId, variantId: i.variantId || null, staffId: i.staffId || posCart.staffId || null, qty: i.qty }; }),
      products: posCart.products.map(function (p) { return { productId: p.productId, qty: p.qty }; }),
      promoCode: posCart.promoCode || null,
      gratuity: posCart.gratuity || 0,
      loyaltyPoints: posCart.loyaltyPoints || 0,
      notes: posCart.notes || null
    };
  }

  function quoteCart() {
    var out = $('#posQuoteOut');
    out.innerHTML = '<p class="hint"><span class="spinner"></span> pricing\u2026</p>';
    api.post('/api/invoices/quote', cartPayload()).then(function (q) {
      out.innerHTML = '<dl class="kv" style="margin-top:1rem">' +
        '<dt>Subtotal</dt><dd class="num">' + fmt.money(q.subtotal) + '</dd>' +
        (q.discount > 0 ? '<dt>Discount</dt><dd class="num">\u2212' + fmt.money(q.discount) + (q.promoCode ? ' <span class="tiny">' + esc(q.promoCode) + '</span>' : '') + '</dd>' : '') +
        (q.loyaltyRedemption > 0 ? '<dt>Loyalty</dt><dd class="num">\u2212' + fmt.money(q.loyaltyRedemption) + ' (' + fmt.num(q.pointsRedeemed) + ' pts)</dd>' : '') +
        '<dt>' + esc(q.taxLabel || 'CGST + SGST') + '</dt><dd class="num">' + fmt.money(q.taxTotal) + '</dd>' +
        (q.gratuity > 0 ? '<dt>Gratuity</dt><dd class="num">' + fmt.money(q.gratuity) + '</dd>' : '') +
        (q.roundOff ? '<dt>Round off</dt><dd class="num">' + fmt.money(q.roundOff) + '</dd>' : '') +
        '<dt>Total</dt><dd class="num" style="font-family:var(--display);font-size:1.4rem">' + fmt.money(q.total) + '</dd>' +
        (q.pointsEarned ? '<dt>Points earned</dt><dd>' + fmt.num(q.pointsEarned) + '</dd>' : '') +
        '</dl>' +
        ((q.notes || []).length ? '<p class="hint" style="margin-top:.6rem">' + q.notes.map(esc).join(' \u00b7 ') + '</p>' : '') +
        ((q.explain || []).length ? '<details style="margin-top:.6rem"><summary class="link">How this was priced</summary>' +
          '<pre class="mono" style="font-size:.72rem;white-space:pre-wrap">' + esc((q.explain || []).join('\n')) + '</pre></details>' : '');
      $('#posTake').disabled = !(q.total > 0);
      $('#posTake').dataset.total = q.total;
    }).catch(function (err) {
      out.innerHTML = '<p class="err">' + esc((err.details || []).join(' \u00b7 ') || err.message) + '</p>';
      $('#posTake').disabled = true;
    });
  }

  function saveBill() {
    api.post('/api/invoices', cartPayload()).then(function (inv) {
      S.toast('Bill ' + inv.number + ' saved as ' + inv.statusLabel, 'ok');
      window.location.hash = '#/invoices/' + inv.id;
    }).catch(function (err) { S.toast((err.details || []).join(' \u00b7 ') || err.message, 'bad'); });
  }

  function takePayment() {
    var total = Number($('#posTake').dataset.total || 0);
    if (total <= 0) { S.toast('Price the bill first', 'bad'); return; }
    api.post('/api/invoices', cartPayload()).then(function (inv) {
      openPaymentDialog(inv, total, function () {
        posCart = { customerId: posCart.customerId, customerName: posCart.customerName, items: [], products: [], promoCode: '', gratuity: 0, loyaltyPoints: 0, staffId: '', notes: '' };
        renderPosCart(); renderPosServices('');
        $('#posQuoteOut').innerHTML = '';
        $('#posTake').disabled = true;
      });
    }).catch(function (err) { S.toast((err.details || []).join(' \u00b7 ') || err.message, 'bad'); });
  }

  function openPaymentDialog(invoice, suggested, onDone) {
    var methods = (boot.payments || []).filter(function (m) { return m.enabled !== false; });
    var body = document.createElement('div');
    body.innerHTML = '<p class="tiny" style="margin-top:0">' + esc(invoice.number) + ' \u00b7 balance ' +
        fmt.money(invoice.balance) + '</p>' +
      '<div class="field"><label class="lbl" for="payAmount">Amount</label>' +
      '<input id="payAmount" type="number" step="0.01" min="1" value="' + Number(suggested || invoice.balance || invoice.total).toFixed(2) + '"></div>' +
      '<p class="tiny">Tender</p><div class="tender-grid" id="payMethods" style="margin:.4rem 0 1rem"></div>' +
      '<div id="payFields"></div>' +
      '<p class="err" id="payErr" role="alert"></p>' +
      '<p class="hint" id="payHint"></p>';
    var chosen = methods.length ? methods[0].key : 'CASH';
    var handle = S.modal({
      title: 'Take payment', kicker: 'The till', body: body, size: 'wide',
      actions: [{
        label: 'Charge it', kind: 'solid', onClick: function (root) {
          var payload = collectTender(root, chosen);
          payload.invoiceId = invoice.id;
          payload.amount = Number($('#payAmount', root).value || 0);
          return api.post('/api/payments', payload).then(function (result) {
            if (result.status === 'AUTHENTICATION_REQUIRED') {
              S.toast('The bank wants a code \u2014 enter it below', 'bad', '3-D Secure');
              $('#payHint', root).innerHTML = 'Challenge <span class="mono">' + esc(result.challengeId || '') +
                '</span> \u00b7 the simulated code is <b class="mono">' + esc(result.testOtp || '') + '</b>. ' +
                'Press <b>Charge it</b> again with the code filled in.';
              $('#payFields', root).innerHTML = '<div class="field"><label class="lbl" for="payOtp">One-time code</label>' +
                '<input id="payOtp" inputmode="numeric" value="' + esc(result.testOtp || '') + '"></div>' +
                '<input type="hidden" id="payIntent" value="' + esc(result.intentId || '') + '">';
              return false;
            }
            S.toast('Settled \u00b7 ' + fmt.money(result.invoice ? result.invoice.paid : payload.amount), 'ok', 'Paid');
            if (onDone) onDone();
            window.location.hash = '#/invoices/' + invoice.id;
            return true;
          }).catch(function (err) {
            $('#payErr', root).textContent = (err.details || []).join(' \u00b7 ') || err.message;
            return false;
          });
        }
      }, { label: 'Not now', kind: 'ghost' }]
    });
    function paintMethods() {
      var host = $('#payMethods', body);
      host.innerHTML = methods.map(function (m) {
        return '<button class="chip' + (m.key === chosen ? ' on' : '') + '" type="button" data-m="' + esc(m.key) + '" aria-pressed="' + (m.key === chosen) + '">' +
          esc(m.label || m.key) + '</button>';
      }).join('');
      $$('[data-m]', host).forEach(function (b) {
        b.addEventListener('click', function () { chosen = b.getAttribute('data-m'); paintMethods(); paintFields(); });
      });
    }
    function paintFields() {
      var host = $('#payFields', body);
      if (chosen === 'CARD') {
        host.innerHTML = '<div class="row"><div class="field" style="grid-column:1/-1"><label class="lbl" for="tCardN">Card number</label>' +
          '<input id="tCardN" inputmode="numeric" placeholder="4111 1111 1111 1111" autocomplete="off"></div>' +
          '<div class="field"><label class="lbl" for="tCardE">Expiry</label><input id="tCardE" placeholder="12/29"></div>' +
          '<div class="field"><label class="lbl" for="tCardC">CVV</label><input id="tCardC" type="password" placeholder="\u2022\u2022\u2022"></div>' +
          '<div class="field" style="grid-column:1/-1"><label class="lbl" for="tCardH">Name on card</label><input id="tCardH"></div></div>' +
          '<p class="hint">Test cards: <span class="mono">4111 1111 1111 1111</span> approves \u00b7 ' +
          '<span class="mono">3782 8224 6310 005</span> asks for a code \u00b7 <span class="mono">4000 0000 0000 0002</span> declines.</p>';
      } else if (chosen === 'UPI') {
        host.innerHTML = '<div class="field"><label class="lbl" for="tUpiV">UPI id</label><input id="tUpiV" placeholder="name@okhdfcbank"></div>';
      } else if (chosen === 'NETBANKING') {
        host.innerHTML = '<div class="field"><label class="lbl" for="tBankN">Bank</label><select id="tBankN">' +
          ['HDFC Bank', 'ICICI Bank', 'Punjab National Bank', 'Axis Bank', 'State Bank of India'].map(function (b) {
            return '<option>' + esc(b) + '</option>'; }).join('') + '</select></div>';
      } else if (chosen === 'WALLET') {
        host.innerHTML = '<div class="field"><label class="lbl" for="tWalletN">Wallet</label><select id="tWalletN">' +
          ['Paytm', 'PhonePe', 'Amazon Pay'].map(function (w) { return '<option>' + esc(w) + '</option>'; }).join('') + '</select></div>';
      } else if (chosen === 'GIFT_CARD') {
        host.innerHTML = '<div class="field"><label class="lbl" for="tGiftC">Gift card code</label><input id="tGiftC" placeholder="SIL-000-000"></div>';
      } else {
        host.innerHTML = '<p class="hint">Cash taken at the desk. Nothing else is needed.</p>';
      }
    }
    function collectTender(root, method) {
      var payload = { method: method };
      if (method === 'CARD') {
        payload.cardNumber = ($('#tCardN', root) || {}).value || '';
        payload.cardExpiry = ($('#tCardE', root) || {}).value || '';
        payload.cardCvv = ($('#tCardC', root) || {}).value || '';
        payload.cardHolder = ($('#tCardH', root) || {}).value || '';
      } else if (method === 'UPI') payload.upiVpa = ($('#tUpiV', root) || {}).value || '';
      else if (method === 'NETBANKING') payload.bank = ($('#tBankN', root) || {}).value || '';
      else if (method === 'WALLET') payload.wallet = ($('#tWalletN', root) || {}).value || '';
      else if (method === 'GIFT_CARD') payload.giftCardCode = ($('#tGiftC', root) || {}).value || '';
      var otp = $('#payOtp', root); if (otp && otp.value) payload.otp = otp.value.trim();
      var intent = $('#payIntent', root); if (intent && intent.value) payload.intentId = intent.value;
      return payload;
    }
    paintMethods(); paintFields();
    return handle;
  }

  /* ========================================================= clients */
  function viewClients(query) {
    enter('clients');
    if (!can(2)) return setView(gate(2, 'The client book is for staff'));
    setCrumb('Clients');
    setView(head('The client book', 'Search by name, mobile, email, code or a note. The index answers in microseconds.',
      '<button class="btn ghost" type="button" id="dupBtn">Find duplicates</button>' +
      '<button class="btn ghost" type="button" id="bdaysBtn">Birthdays</button>' +
      '<button class="btn ghost" type="button" id="importBtn">Import CSV</button>' +
      '<button class="btn solid" type="button" id="newBtn">New client</button>') +
      '<div class="row" style="grid-template-columns:1fr auto;margin-bottom:1.2rem">' +
      '<input id="clientSearch" type="search" placeholder="Search 30 clients \u2014 try \u201Cbalayage\u201D, \u201C98145\u201D or \u201CModel Town\u201D" value="' + esc((query && query.q) || '') + '">' +
      '<div class="seg" role="group" aria-label="Sort">' +
      '<button type="button" data-sort="name" aria-pressed="true">Name</button>' +
      '<button type="button" data-sort="value" aria-pressed="false">Value</button>' +
      '<button type="button" data-sort="recent" aria-pressed="false">Recent</button></div></div>' +
      '<div id="clientBody">' + loading() + '</div>');
    var sort = 'name';
    $('#clientSearch').addEventListener('input', S.debounce(function (e) {
      window.location.hash = '#/clients?q=' + encodeURIComponent(e.target.value.trim());
    }, 260));
    $$('.seg button').forEach(function (b) {
      b.addEventListener('click', function () {
        $$('.seg button').forEach(function (x) { x.setAttribute('aria-pressed', 'false'); });
        b.setAttribute('aria-pressed', 'true'); sort = b.getAttribute('data-sort'); load();
      });
    });
    $('#newBtn').addEventListener('click', newClientDialog);
    $('#dupBtn').addEventListener('click', function () {
      api.get('/api/customers/duplicates').then(function (r) {
        S.modal({ title: 'Possible duplicates', kicker: 'Housekeeping', body:
          '<p class="hint">Matched on mobile number or email.</p>' +
          ((r.duplicates || []).length ? '<ul class="list-plain">' + r.duplicates.map(function (d) {
            return '<li><span class="grow">' + esc(JSON.stringify(d)) + '</span></li>'; }).join('') + '</ul>'
            : '<p>No duplicates found.</p>') });
      });
    });
    $('#bdaysBtn').addEventListener('click', function () {
      api.get('/api/customers/birthdays?days=30').then(function (r) {
        S.modal({ title: 'Birthdays', kicker: 'Next thirty days', body:
          (r.birthdays || []).length ? '<ul class="list-plain">' + r.birthdays.map(function (b) {
            return '<li><span class="grow"><b>' + esc(b.name) + '</b><br><span class="tiny">' + esc(b.when || '') + '</span></span>' +
              '<button class="link" type="button" onclick="location.hash=\'#/clients/' + esc(b.id) + '\'">Open</button></li>';
          }).join('') + '</ul>' : '<p>Nobody in the next thirty days.</p>' });
      });
    });
    $('#importBtn').addEventListener('click', function () {
      var body = document.createElement('div');
      body.innerHTML = '<p class="hint">Paste a CSV with a header row. Recognised columns include ' +
        '<span class="mono">first_name, last_name, phone, email, birthday, city, tags, notes</span>.</p>' +
        '<textarea id="csvIn" style="min-height:12rem;font-family:var(--mono);font-size:.78rem" placeholder="first_name,last_name,phone,email"></textarea>' +
        '<p class="err" id="csvErr"></p>';
      S.modal({ title: 'Import clients', kicker: 'CSV', body: body, actions: [
        { label: 'Import', kind: 'solid', onClick: function (root) {
          return api.post('/api/customers/import', { csv: $('#csvIn', root).value })
            .then(function (r) { S.toast(r.message || ('Imported ' + r.imported), 'ok'); load(); })
            .catch(function (err) { $('#csvErr', root).textContent = err.message; return false; });
        }}, { label: 'Cancel', kind: 'ghost' }] });
    });

    function load() {
      var q = ($('#clientSearch') || {}).value || '';
      var url = '/api/customers?limit=80' + (q.trim() ? '&q=' + encodeURIComponent(q.trim()) : '');
      api.get(url).then(function (result) {
        var rows = (result.customers || []).slice();
        if (sort === 'value') rows.sort(function (a, b) { return Number(b.lifetimeValue || 0) - Number(a.lifetimeValue || 0); });
        if (sort === 'recent') rows.sort(function (a, b) { return String(b.lastVisit || '').localeCompare(String(a.lastVisit || '')); });
        var stats = (result.meta || {}).stats || {};
        $('#clientBody').innerHTML = kpis([
          { label: 'Clients', value: fmt.num(stats.total || rows.length) },
          { label: 'Active 90d', value: fmt.num(stats.active90 || 0) },
          { label: 'New 30d', value: fmt.num(stats.new30 || 0) },
          { label: 'Lifetime value', value: fmt.money(stats.lifetimeValue || 0) },
          { label: 'Average ticket', value: fmt.money(stats.averageTicket || 0) }
        ]) + '<div style="margin-top:1.4rem">' + table(
          [{ label: 'Client' }, { label: 'Contact' }, { label: 'Tier' }, { label: 'Points', cls: 'r' },
           { label: 'Visits', cls: 'r' }, { label: 'Lifetime', cls: 'r' }, { label: 'Last seen' }, { label: '' }],
          rows.map(function (c) {
            return { cells: [
              '<b>' + esc(c.name) + '</b><br><span class="tiny">' + esc(c.code) + (c.archived ? ' \u00b7 archived' : '') + '</span>',
              '<span class="mono" style="font-size:.76rem">' + esc(c.phone || '') + '</span><br><span class="tiny">' + esc(c.email || '') + '</span>',
              S.badge(c.tier || '', 'gold'),
              '<span class="num">' + fmt.num(c.points) + '</span>',
              '<span class="num">' + fmt.num(c.visits) + '</span>',
              '<span class="num">' + fmt.money(c.lifetimeValue) + '</span>',
              c.daysSince >= 0 ? esc(fmt.relative(c.lastVisit)) : '<span class="muted">never</span>',
              '<button class="link" type="button" data-open="' + esc(c.id) + '">Open</button>'] };
          }), { emptyTitle: 'No client matches that', emptyBody: 'Try a name, a mobile number or a tag.' }) + '</div>' +
          '<p class="hint" style="margin-top:.8rem">Served by the BM25 index \u00b7 ' + esc((result.meta || {}).count || rows.length) + ' shown.</p>';
        $$('#clientBody [data-open]').forEach(function (b) {
          b.addEventListener('click', function () { window.location.hash = '#/clients/' + b.getAttribute('data-open'); });
        });
      }).catch(function (err) { $('#clientBody').innerHTML = errorState(err); });
    }
    load();
  }

  function viewClient(params) {
    enter('clients');
    if (!can(1)) return setView(gate(1, 'Sign in to open a record'));
    var id = params.id;
    setCrumb('Client record');
    setView('<div id="clientOne">' + loading('Opening the record') + '</div>');
    api.get('/api/customers/' + encodeURIComponent(id)).then(function (p) {
      var c = p.customer || {};
      var d = p.display || {};
      var st = p.stats || {};
      setCrumb('Client record', d.firstName + ' ' + (d.lastName || ''));
      var html = head(esc((d.firstName || '') + ' ' + (d.lastName || '')) + ' <em>\u00b7 ' + esc(c.tier || '') + '</em>',
        esc([d.phone, d.email].filter(Boolean).join(' \u00b7 ')) +
        ' &mdash; <span class="mono">' + esc(c.code || '') + '</span>',
        '<button class="btn ghost" type="button" id="cEdit">Edit</button>' +
        '<button class="btn ghost" type="button" id="cBook">Book for them</button>' +
        '<button class="btn ghost" type="button" id="cBill">New bill</button>' +
        '<button class="btn ghost" type="button" id="cExport">Export their data</button>' +
        '<button class="btn danger" type="button" id="cErase">Erase</button>') +
        kpis([
          { label: 'Visits', value: fmt.num(st.visits || 0), note: (st.completed || 0) + ' completed' },
          { label: 'Lifetime value', value: fmt.money(st.lifetimeValue || 0) },
          { label: 'Average ticket', value: fmt.money(st.averageTicket || 0) },
          { label: 'Points', value: fmt.num((p.loyalty || {}).points || 0), note: 'worth ' + fmt.money((p.loyalty || {}).value || 0) },
          { label: 'Last visit', value: st.daysSinceLastVisit >= 0 ? st.daysSinceLastVisit + ' d ago' : '\u2014', note: st.medianGapDays ? 'median gap ' + st.medianGapDays + ' d' : '' },
          { label: 'No-shows', value: fmt.num(st.noShows || 0), note: fmt.pct(st.noShowRate || 0) }
        ]) +
        '<div class="desk-cols" style="margin-top:1.6rem">' +
          panel('Suggestions for this client', '<div id="cSugg">' + loading('Asking the engine') + '</div>', '') +
          panel('Loyalty', '<dl class="kv">' +
            '<dt>Tier</dt><dd>' + esc((p.loyalty || {}).tier || c.tier || '') + '</dd>' +
            '<dt>Points</dt><dd>' + fmt.num((p.loyalty || {}).points || 0) + ' \u00b7 ' + fmt.money((p.loyalty || {}).value || 0) + '</dd>' +
            '<dt>Next tier</dt><dd>' + esc((p.loyalty || {}).nextTier || 'top tier') + '</dd>' +
            '<dt>Multiplier</dt><dd>\u00d7' + esc(String((p.loyalty || {}).multiplier || 1)) + '</dd></dl>', '') +
          panel('The record', '<dl class="kv">' +
            '<dt>Birthday</dt><dd>' + esc(d.birthday ? fmt.date(d.birthday) : '\u2014') + (p.birthdaySoon ? ' <span class="badge gold">soon</span>' : '') + '</dd>' +
            '<dt>Address</dt><dd>' + esc([d.address, d.city, d.pincode].filter(Boolean).join(', ') || '\u2014') + '</dd>' +
            '<dt>Preferred chair</dt><dd>' + esc((p.preferredStaff || {}).name || 'no preference') + '</dd>' +
            '<dt>Preferred time</dt><dd>' + esc(d.preferredTime || 'any') + '</dd>' +
            '<dt>Tags</dt><dd>' + (d.tags || []).map(function (t) { return '<span class="badge mut">' + esc(t) + '</span>'; }).join(' ') + '</dd>' +
            '<dt>Allergies</dt><dd>' + esc(p.allergies || 'none recorded') + '</dd>' +
            '<dt>Consent</dt><dd>' + esc((p.consent || {}).marketing ? 'marketing yes' : 'marketing no') + ' \u00b7 ' +
              esc((p.consent || {}).data ? 'record kept' : 'record not kept') + '</dd>' +
            '<dt>Notes</dt><dd>' + esc(p.notes || '\u2014') + '</dd>' +
            '<dt>Favourites</dt><dd>' + esc((p.favourites || []).map(function (f) { return f.name || f; }).join(', ') || '\u2014') + '</dd>' +
            '</dl>', '<button class="btn sm ghost" type="button" id="cConsent">Change consent</button>') +
          panel('Upcoming', (p.upcoming || []).length ? '<ul class="list-plain">' + p.upcoming.map(function (a) {
            return '<li><span class="grow"><b>' + esc(fmt.stamp(a.start)) + '</b><br><span class="tiny">' +
              esc((a.services || []).join(', ')) + ' \u00b7 ' + esc(a.staff || '') + '</span></span>' + S.badge(a.statusLabel || a.status) + '</li>';
          }).join('') + '</ul>' : '<p class="muted">Nothing booked.</p>', '') +
          panel('History', table([{ label: 'When' }, { label: 'Services' }, { label: 'Chair' }, { label: 'Status' }, { label: 'Price', cls: 'r' }],
            (p.history || []).slice(0, 24).map(function (a) {
              return { cells: ['<span class="mono" style="font-size:.76rem">' + esc(a.when || fmt.stamp(a.start)) + '</span>',
                esc((a.services || []).join(', ')), esc(a.staff || ''), S.badge(a.statusLabel || a.status),
                '<span class="num">' + fmt.money(a.price) + '</span>'] };
            }), { emptyTitle: 'No history yet' }), '', { flush: true }) +
          panel('Bills', table([{ label: 'Number' }, { label: 'Date' }, { label: 'Total', cls: 'r' }, { label: 'Status' }, { label: '' }],
            (p.invoices || []).slice(0, 12).map(function (i) {
              return { cells: ['<span class="mono" style="font-size:.76rem">' + esc(i.number) + '</span>',
                esc(fmt.date(i.createdAt)), '<span class="num">' + fmt.money(i.total) + '</span>',
                S.badge(i.statusLabel || i.status),
                '<button class="link" type="button" data-inv="' + esc(i.id) + '">Open</button>'] };
            }), { emptyTitle: 'No bills yet' }), '', { flush: true }) +
        '</div>';
      $('#clientOne').innerHTML = html;
      $$('[data-inv]').forEach(function (b) {
        b.addEventListener('click', function () { window.location.hash = '#/invoices/' + b.getAttribute('data-inv'); });
      });
      $('#cBill').addEventListener('click', function () { window.location.hash = '#/pos?client=' + encodeURIComponent(id); });
      $('#cBook').addEventListener('click', function () { window.open('/book', '_blank', 'noopener'); });
      $('#cEdit').addEventListener('click', function () { editClient(c, d); });
      $('#cConsent').addEventListener('click', function () {
        S.modal({ title: 'Consent', kicker: 'Your record', body:
          '<label class="check"><input type="checkbox" id="csM"' + ((p.consent || {}).marketing ? ' checked' : '') + '><span>Marketing messages</span></label>' +
          '<label class="check"><input type="checkbox" id="csD"' + ((p.consent || {}).data ? ' checked' : '') + '><span>Keep this record</span></label>',
          actions: [{ label: 'Save', kind: 'solid', onClick: function (root) {
            return api.patch('/api/customers/' + encodeURIComponent(id), {
              marketingConsent: $('#csM', root).checked, dataConsent: $('#csD', root).checked
            }).then(function () { S.toast('Consent updated', 'ok'); refresh(); }).catch(function (e2) { S.toast(e2.message, 'bad'); return false; });
          }}] });
      });
      $('#cExport').addEventListener('click', function () {
        api.get('/api/customers/' + encodeURIComponent(id) + '/export').then(function (subject) {
          S.modal({ title: 'A copy of the record', kicker: 'Right of access', body:
            '<p class="hint">This is everything the salon holds. Download it, or ask for erasure.</p>' +
            '<pre class="mono" style="max-height:22rem;overflow:auto;font-size:.72rem;background:var(--sunken);border:1px solid var(--rule);padding:.8rem">' +
            esc(JSON.stringify(subject, null, 2)) + '</pre>',
            actions: [{ label: 'Download JSON', kind: 'solid', onClick: function () {
              var blob = new Blob([JSON.stringify(subject, null, 2)], { type: 'application/json' });
              var url = URL.createObjectURL(blob);
              var a = document.createElement('a'); a.href = url; a.download = (c.code || 'client') + '-record.json';
              document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
            }}, { label: 'Close', kind: 'ghost' }] });
        }).catch(function (err) { S.toast(err.message, 'bad'); });
      });
      $('#cErase').addEventListener('click', function () {
        S.confirm({
          title: 'Erase this record?', danger: true, confirmLabel: 'Erase it',
          message: 'The name, mobile number, email, birthday, address, notes and tags are removed and the record is archived. Bills are kept because tax law requires them, with the client shown as "erased on request". This is written permanently to the audit trail.'
        }).then(function (yes) {
          if (!yes) return;
          api.post('/api/customers/' + encodeURIComponent(id) + '/erase', {}).then(function (r) {
            S.toast('Erased. A receipt is in the audit trail.', 'ok');
            window.location.hash = '#/clients';
          }).catch(function (err) { S.toast(err.message, 'bad'); });
        });
      });
      api.get('/api/customers/' + encodeURIComponent(id) + '/suggestions?limit=6').then(function (r) {
        var host = $('#cSugg');
        if (!host) return;
        host.innerHTML = (r.suggestions || []).map(function (s) {
          return '<div class="suggestion" style="margin-bottom:.7rem"><div class="head"><h4>' + esc(s.title) + '</h4>' +
            '<span class="badge ' + esc(s.confidence || 'mut') + '">' + esc(s.impact || s.confidence || '') + '</span></div>' +
            '<p class="why">' + esc(s.rationale || s.detail) + '</p>' +
            '<div class="rules">' + (s.rules || []).map(function (rr) { return '<code>' + esc(rr) + '</code>'; }).join('') + '</div>' +
            '<div class="meter"><i style="width:' + Math.round((s.score || 0) * 100) + '%"></i></div></div>';
        }).join('') || '<p class="muted">The engine has nothing for this client yet.</p>';
      }).catch(function () { var host = $('#cSugg'); if (host) host.innerHTML = '<p class="muted">Could not ask the engine.</p>'; });
    }).catch(function (err) { $('#clientOne').innerHTML = errorState(err); });
  }

  function editClient(c, d) {
    var body = document.createElement('div');
    body.innerHTML = '<div class="row">' +
      '<div class="field"><label class="lbl" for="eFirst">First name</label><input id="eFirst" value="' + esc(d.firstName || '') + '"></div>' +
      '<div class="field"><label class="lbl" for="eLast">Last name</label><input id="eLast" value="' + esc(d.lastName || '') + '"></div></div>' +
      '<div class="row">' +
      '<div class="field"><label class="lbl" for="ePhone">Mobile</label><input id="ePhone" value="' + esc(d.phone || '') + '"></div>' +
      '<div class="field"><label class="lbl" for="eEmail">Email</label><input id="eEmail" value="' + esc(d.email || '') + '"></div></div>' +
      '<div class="row">' +
      '<div class="field"><label class="lbl" for="eBday">Birthday</label><input id="eBday" type="date" value="' + esc(d.birthday || '') + '"></div>' +
      '<div class="field"><label class="lbl" for="eCity">Area</label><input id="eCity" value="' + esc(d.address || '') + '"></div></div>' +
      '<div class="field"><label class="lbl" for="eTags">Tags (comma separated)</label><input id="eTags" value="' + esc((d.tags || []).join(', ')) + '"></div>' +
      '<div class="field"><label class="lbl" for="eNotes">Notes</label><textarea id="eNotes">' + esc(d.notes || '') + '</textarea></div>' +
      '<div class="field"><label class="lbl" for="eAllergies">Allergies</label><input id="eAllergies" value="' + esc(d.allergies || '') + '"></div>' +
      '<div class="field"><label class="lbl" for="ePref">Preferred chair</label><select id="ePref"><option value="">No preference</option>' +
        (boot.staff || []).map(function (m) { return '<option value="' + esc(m.id) + '"' + (c.preferredStaffId === m.id ? ' selected' : '') + '>' + esc(m.name) + '</option>'; }).join('') +
      '</select></div>' +
      '<p class="err" id="eErr" role="alert"></p>';
    S.modal({
      title: 'Edit the record', kicker: esc(c.code || ''), body: body,
      actions: [{ label: 'Save', kind: 'solid', onClick: function (root) {
        return api.patch('/api/customers/' + encodeURIComponent(c.id), {
          firstName: $('#eFirst', root).value.trim(), lastName: $('#eLast', root).value.trim(),
          phone: $('#ePhone', root).value.trim(), email: $('#eEmail', root).value.trim(),
          birthday: $('#eBday', root).value || null, address: $('#eCity', root).value.trim(),
          tags: $('#eTags', root).value.split(',').map(function (t) { return t.trim(); }).filter(Boolean),
          notes: $('#eNotes', root).value.trim(), allergies: $('#eAllergies', root).value.trim(),
          preferredStaffId: $('#ePref', root).value || null
        }).then(function () { S.toast('Record updated', 'ok'); refresh(); })
          .catch(function (err) { $('#eErr', root).textContent = (err.details || []).join(' \u00b7 ') || err.message; return false; });
      }}, { label: 'Cancel', kind: 'ghost' }]
    });
  }

  /* ============================================================ team */
  function viewTeam() {
    enter('team');
    if (!can(2)) return setView(gate(2, 'The rota is for staff'));
    setCrumb('The chairs');
    setView(head('The <em>chairs</em>', 'Who is on the floor, what they are certified for, and how their week looks.',
      (can(4) ? '<button class="btn solid" type="button" id="addStaff">Add a stylist</button>' : '')) +
      '<div id="teamBody">' + loading() + '</div>');
    var add = $('#addStaff');
    if (add) add.addEventListener('click', staffDialog);
    api.get('/api/staff').then(function (result) {
      var rows = result.staff || [];
      $('#teamBody').innerHTML = '<div class="chairs">' + rows.map(function (m) {
        return '<article class="chair"><div class="between" style="align-items:flex-start">' +
          '<span class="avatar" aria-hidden="true">' + esc(fmt.initials(m.name)) + '</span>' +
          S.badge(m.active ? 'On the floor' : 'Away', m.active ? 'ok' : 'mut') + '</div>' +
          '<div><h4>' + esc(m.name) + '</h4><p class="role">' + esc(m.title || '') + '</p></div>' +
          (m.quote ? '<blockquote>\u201C' + esc(m.quote) + '</blockquote>' : '') +
          '<p class="specs">' + esc((m.specialties || []).join(' \u00b7 ')) + '</p>' +
          '<p class="specs mono">' + esc(m.phone || '') + ' \u00b7 ' + esc(m.email || '') + '</p>' +
          '<div class="foot"><span class="mono" style="font-size:.64rem;color:var(--fg-mut)">\u2605 ' +
            Number(m.rating || 0).toFixed(1) + ' \u00b7 ' + (m.years || 0) + ' yrs \u00b7 ' + (m.commissionPct || 0) + '%</span>' +
          '<span class="btn-row"><button class="link" type="button" data-week="' + esc(m.id) + '">Week</button>' +
          (can(4) ? '<button class="link" type="button" data-edit="' + esc(m.id) + '">Edit</button>' : '') + '</span></div></article>';
      }).join('') + '</div>';
      $$('[data-week]').forEach(function (b) {
        b.addEventListener('click', function () {
          api.get('/api/staff/' + encodeURIComponent(b.getAttribute('data-week'))).then(function (profile) {
            var s = profile.staff || {};
            S.modal({
              title: esc(s.name || ''), kicker: esc(s.title || 'Stylist'),
              body: '<div class="grid g2"><div>' + kpis([
                { label: 'Rating', value: Number(s.rating || 0).toFixed(1) },
                { label: 'Years', value: fmt.num(s.yearsExperience || 0) },
                { label: 'Commission', value: fmt.pct(s.commissionPct || 0) }]) + '</div><div>' +
                '<p class="tiny">This week</p>' + (profile.week || []).map(function (d) {
                  return '<div class="between" style="border-bottom:1px solid var(--rule);padding:.4rem 0">' +
                    '<span>' + esc(d.day || d.date || '') + '</span><span class="num">' +
                    (d.closed ? 'off' : (d.booked || 0) + ' / ' + (d.capacity || 0) + ' min') + '</span></div>';
                }).join('') + '</div></div>' +
                '<p class="tiny" style="margin-top:1rem">Certified for</p><p>' +
                esc((profile.topServices || []).map(function (t) { return t.name || t; }).join(', ') || 'everything') + '</p>'
            });
          });
        });
      });
      $$('[data-edit]').forEach(function (b) {
        b.addEventListener('click', function () {
          var member = rows.filter(function (m) { return m.id === b.getAttribute('data-edit'); })[0];
          staffDialog(member);
        });
      });
    }).catch(function (err) { $('#teamBody').innerHTML = errorState(err); });
  }

  function staffDialog(member) {
    var services = ((boot.menu || {}).services || []);
    var body = document.createElement('div');
    body.innerHTML = '<div class="row">' +
      '<div class="field"><label class="lbl" for="sName">Name</label><input id="sName" value="' + esc(member ? member.name : '') + '"></div>' +
      '<div class="field"><label class="lbl" for="sTitle">Title</label><input id="sTitle" value="' + esc(member ? member.title : '') + '"></div></div>' +
      '<div class="row">' +
      '<div class="field"><label class="lbl" for="sPhone">Mobile</label><input id="sPhone" value="' + esc(member ? member.phone : '') + '"></div>' +
      '<div class="field"><label class="lbl" for="sEmail">Email</label><input id="sEmail" value="' + esc(member ? member.email : '') + '"></div></div>' +
      '<div class="row">' +
      '<div class="field"><label class="lbl" for="sComm">Commission %</label><input id="sComm" type="number" step="0.5" value="' + (member ? member.commissionPct : 10) + '"></div>' +
      '<div class="field"><label class="lbl" for="sYears">Years experience</label><input id="sYears" type="number" value="' + (member ? member.years : 1) + '"></div>' +
      '<div class="field"><label class="lbl" for="sBuffer">Buffer minutes</label><input id="sBuffer" type="number" value="' + (member ? member.bufferMinutes || 15 : 15) + '"></div></div>' +
      '<div class="field"><label class="lbl" for="sSpec">Specialties (comma separated)</label>' +
      '<input id="sSpec" value="' + esc(member ? (member.specialties || []).join(', ') : '') + '"></div>' +
      '<div class="field"><label class="lbl" for="sQuote">Their line</label><input id="sQuote" value="' + esc(member ? member.quote : '') + '"></div>' +
      '<div class="field"><span class="lbl">Certified for</span><div style="max-height:12rem;overflow:auto;border:1px solid var(--rule);padding:.6rem">' +
      services.map(function (s) {
        var on = member && member.serviceIds && member.serviceIds.indexOf(s.id) >= 0;
        return '<label class="check"><input type="checkbox" class="svcCheck" value="' + esc(s.id) + '"' + (on ? ' checked' : '') +
          '><span>' + esc(s.name) + '</span></label>';
      }).join('') + '</div></div>' +
      '<label class="check"><input type="checkbox" id="sWalk"' + (member && member.acceptsWalkIns ? ' checked' : ' checked') + '><span>Takes walk-ins</span></label>' +
      '<p class="err" id="sErr" role="alert"></p>';
    S.modal({
      title: member ? 'Edit ' + esc(member.name) : 'Add a stylist', kicker: 'The chairs', body: body, size: 'wide',
      actions: [{ label: 'Save', kind: 'solid', onClick: function (root) {
        var payload = {
          name: $('#sName', root).value.trim(), title: $('#sTitle', root).value.trim(),
          phone: $('#sPhone', root).value.trim(), email: $('#sEmail', root).value.trim(),
          commissionPct: Number($('#sComm', root).value || 0), yearsExperience: Number($('#sYears', root).value || 0),
          bufferMinutes: Number($('#sBuffer', root).value || 0),
          specialties: $('#sSpec', root).value.split(',').map(function (x) { return x.trim(); }).filter(Boolean),
          quote: $('#sQuote', root).value.trim(),
          acceptsWalkIns: $('#sWalk', root).checked,
          serviceIds: $$('.svcCheck', root).filter(function (c) { return c.checked; }).map(function (c) { return c.value; })
        };
        var call = member ? api.patch('/api/staff/' + encodeURIComponent(member.id), payload) : api.post('/api/staff', payload);
        return call.then(function () { S.toast('Saved', 'ok'); refresh(); })
          .catch(function (err) { $('#sErr', root).textContent = (err.details || []).join(' \u00b7 ') || err.message; return false; });
      }}, { label: 'Cancel', kind: 'ghost' }]
    });
  }

  /* ========================================================= invoices */
  function viewInvoices(query) {
    enter('invoices');
    if (!can(2)) return setView(gate(2, 'Bills are for staff'));
    setCrumb('Bills & receipts');
    setView(head('Bills &amp; <em>receipts</em>', 'Every fiscal document, with its seal, tenders and tax split.',
      '<select id="invStatus" aria-label="Filter by status" style="width:auto">' +
      ['PAID', 'UNPAID', 'PARTIAL', 'REFUNDED', 'VOID', 'DRAFT'].map(function (s) {
        return '<option value="' + s + '"' + ((query && query.status) === s ? ' selected' : '') + '>' + s + '</option>'; }).join('') +
      '</select>' +
      '<button class="btn ghost" type="button" id="invCsv">Export CSV</button>' +
      '<button class="btn solid" type="button" id="invNew">New bill</button>') +
      '<div id="invBody">' + loading() + '</div>');
    $('#invStatus').addEventListener('change', function (e) {
      window.location.hash = '#/invoices?status=' + e.target.value;
    });
    $('#invCsv').addEventListener('click', function () { api.download('/api/export/invoices.csv', 'sillage-invoices.csv'); });
    $('#invNew').addEventListener('click', function () { window.location.hash = '#/pos'; });
    var status = (query && query.status) || '';
    api.get('/api/invoices?limit=60' + (status ? '&status=' + status : '')).then(function (result) {
      var stats = (result.meta || {}).stats || {};
      $('#invBody').innerHTML = kpis([
        { label: 'Bills shown', value: fmt.num((result.invoices || []).length) },
        { label: 'Gross', value: fmt.money(stats.gross || 0) },
        { label: 'Outstanding', value: fmt.money(stats.outstanding || 0) },
        { label: 'Refunded', value: fmt.money(stats.refunded || 0) },
        { label: 'Average ticket', value: fmt.money(stats.averageTicket || 0) }
      ]) + '<div style="margin-top:1.4rem">' + table(
        [{ label: 'Number' }, { label: 'Client' }, { label: 'Date' }, { label: 'Total', cls: 'r' },
         { label: 'Paid', cls: 'r' }, { label: 'Balance', cls: 'r' }, { label: 'Status' }, { label: 'Method' }, { label: '' }],
        (result.invoices || []).map(function (i) {
          return { cells: [
            '<span class="mono" style="font-size:.76rem">' + esc(i.number) + '</span>',
            esc(i.customerName || 'Guest'),
            '<span class="mono" style="font-size:.76rem">' + esc(fmt.date(i.createdAt)) + '</span>',
            '<span class="num">' + fmt.money(i.total) + '</span>',
            '<span class="num">' + fmt.money(i.paid) + '</span>',
            '<span class="num">' + (i.balance > 0 ? fmt.money(i.balance) : '\u2014') + '</span>',
            S.badge(i.statusLabel || i.status),
            esc(i.primaryMethod || (i.payments && i.payments[0] ? i.payments[0].method : '')),
            '<button class="link" type="button" data-inv="' + esc(i.id) + '">Open</button>'] };
        }), { emptyTitle: 'No bills match', emptyBody: 'Change the status filter, or take a payment at the till.' }) + '</div>';
      $$('[data-inv]').forEach(function (b) {
        b.addEventListener('click', function () { window.location.hash = '#/invoices/' + b.getAttribute('data-inv'); });
      });
    }).catch(function (err) { $('#invBody').innerHTML = errorState(err); });
  }

  function viewInvoice(params) {
    enter('invoices');
    setCrumb('Receipt');
    setView('<div id="invOne">' + loading() + '</div>');
    api.get('/api/invoices/' + encodeURIComponent(params.id)).then(function (i) {
      setCrumb('Receipt', i.number);
      var lines = (i.lines || []).map(function (l) {
        return { cells: [
          '<b>' + esc(l.name) + '</b>' + (l.detail ? '<br><span class="tiny">' + esc(l.detail) + '</span>' : ''),
          '<span class="tiny">' + esc(String(l.kind || '').toLowerCase()) + '</span>' + (l.staff ? '<br><span class="tiny">' + esc(l.staff) + '</span>' : ''),
          '<span class="num">' + l.qty + '</span>',
          '<span class="num">' + fmt.money(l.unitPrice) + '</span>',
          '<span class="num">' + (l.taxPct ? fmt.pct(l.taxPct) : '\u2014') + '</span>',
          '<span class="num">' + (l.amount < 0 ? '\u2212' + fmt.money(Math.abs(l.amount)) : fmt.money(l.amount)) + '</span>'] };
      });
      var html = head('<span class="mono" style="font-size:.5em;letter-spacing:.1em">' + esc(i.number) + '</span><br>' +
        esc(i.customerName || 'Guest'),
        esc(i.createdAtLabel || fmt.stamp(i.createdAt)) + ' \u00b7 billed by ' + esc(i.createdBy || '') + ' \u00b7 ' + S.badge(i.statusLabel || i.status),
        '<a class="btn ghost" target="_blank" rel="noopener" href="' + esc(i.receipt.html) + '">Print</a>' +
        '<a class="btn ghost" target="_blank" rel="noopener" href="' + esc(i.receipt.text) + '">Thermal text</a>' +
        '<button class="btn ghost" type="button" id="iCsv">CSV</button>' +
        (i.balance > 0 ? '<button class="btn solid" type="button" id="iPay">Take ' + fmt.money(i.balance) + '</button>' : '') +
        (can(4) && i.status !== 'VOID' && i.status !== 'REFUNDED' ? '<button class="btn danger" type="button" id="iVoid">Void</button>' : '')) +
        '<div class="grid g2" style="align-items:start">' +
        '<div>' + panel('Lines', table([{ label: 'Item' }, { label: 'Kind' }, { label: 'Qty', cls: 'r' },
          { label: 'Rate', cls: 'r' }, { label: 'Tax', cls: 'r' }, { label: 'Amount', cls: 'r' }], lines), '', { flush: true }) +
        '<div style="margin-top:1.2rem">' + panel('Tenders', (i.payments || []).length ? '<ul class="list-plain">' +
          i.payments.map(function (p) {
            return '<li><span class="grow"><b>' + esc(p.tenderLabel || p.method) + '</b><br>' +
              '<span class="tiny">' + esc(fmt.stamp(p.createdAt)) + ' \u00b7 ' + esc(p.statusLabel || p.status) +
              (p.gatewayRef ? ' \u00b7 ' + esc(p.gatewayRef) : '') + (p.cardLast4 ? ' \u00b7 \u2022\u2022\u2022\u2022 ' + esc(p.cardLast4) : '') +
              (p.upiVpa ? ' \u00b7 ' + esc(p.upiVpa) : '') + '</span></span>' +
              '<span class="num">' + fmt.money(p.amount) + '</span>' +
              (can(4) && p.status === 'CAPTURED' ? '<button class="link" type="button" data-refund="' + esc(p.id) + '">Refund</button>' : '') + '</li>';
          }).join('') + '</ul>' : '<p class="muted">Nothing taken yet.</p>', '') + '</div></div>' +
        '<div>' + panel('The money', '<dl class="kv">' +
          '<dt>Subtotal</dt><dd class="num">' + fmt.money(i.subtotal) + '</dd>' +
          (i.discount > 0 ? '<dt>Discount</dt><dd class="num">\u2212' + fmt.money(i.discount) + (i.promoCode ? ' <span class="tiny">' + esc(i.promoCode) + '</span>' : '') + '</dd>' : '') +
          (i.loyaltyRedemption > 0 ? '<dt>Loyalty</dt><dd class="num">\u2212' + fmt.money(i.loyaltyRedemption) + ' (' + fmt.num(i.pointsRedeemed) + ' pts)</dd>' : '') +
          '<dt>Taxable value</dt><dd class="num">' + fmt.money(i.taxable) + '</dd>' +
          '<dt>' + esc(i.taxLabel || 'Tax') + '</dt><dd class="num">' + fmt.money(i.taxTotal) + '</dd>' +
          (i.cgst ? '<dt>CGST</dt><dd class="num">' + fmt.money(i.cgst) + '</dd>' : '') +
          (i.sgst ? '<dt>SGST</dt><dd class="num">' + fmt.money(i.sgst) + '</dd>' : '') +
          (i.gratuity > 0 ? '<dt>Gratuity</dt><dd class="num">' + fmt.money(i.gratuity) + '</dd>' : '') +
          (i.roundOff ? '<dt>Round off</dt><dd class="num">' + fmt.money(i.roundOff) + '</dd>' : '') +
          '<dt>Total</dt><dd class="num" style="font-family:var(--display);font-size:1.4rem">' + fmt.money(i.total) + '</dd>' +
          '<dt>Paid</dt><dd class="num">' + fmt.money(i.paid) + '</dd>' +
          '<dt>Balance</dt><dd class="num">' + (i.balance > 0 ? fmt.money(i.balance) : 'settled') + '</dd>' +
          (i.pointsEarned ? '<dt>Points earned</dt><dd>' + fmt.num(i.pointsEarned) + '</dd>' : '') +
          '</dl>', '') +
        panel('Seal', '<p class="seal">' + esc(i.receipt.code) + '</p>' +
          '<p class="hint" style="margin-top:.7rem">Printed on the paper receipt. Verify it at ' +
          '<a class="mono" href="' + esc(i.verifyUrl) + '" target="_blank" rel="noopener">' + esc(i.verifyUrl) + '</a></p>' +
          (i.notes ? '<p class="hint" style="margin-top:.8rem">Notes: ' + esc(i.notes) + '</p>' : ''), '') +
        (i.customerId ? panel('Client', '<p><b>' + esc(i.customerName) + '</b></p><p class="mono" style="font-size:.76rem">' +
          esc(i.customerPhone || '') + '<br>' + esc(i.customerEmail || '') + '</p>' +
          '<a class="link" href="#/clients/' + esc(i.customerId) + '">Open the record</a>', '') : '') +
        '</div></div>';
      $('#invOne').innerHTML = html;
      var pay = $('#iPay');
      if (pay) pay.addEventListener('click', function () { openPaymentDialog(i, i.balance); });
      var csv = $('#iCsv');
      if (csv) csv.addEventListener('click', function () { api.download('/api/receipts/' + encodeURIComponent(i.id) + '/csv', i.number.replace(/\//g, '-') + '.csv'); });
      var voidBtn = $('#iVoid');
      if (voidBtn) voidBtn.addEventListener('click', function () {
        S.confirm({ title: 'Void ' + i.number + '?', danger: true, confirmLabel: 'Void the bill',
          message: 'Voiding keeps the number in the sequence and records who did it and why. Only an owner may void a bill.' })
          .then(function (yes) {
            if (!yes) return;
            api.post('/api/invoices/' + encodeURIComponent(i.id) + '/void', { reason: 'Voided at the desk' })
              .then(function () { S.toast('Voided', 'ok'); refresh(); })
              .catch(function (err) { S.toast(err.message, 'bad'); });
          });
      });
      $$('[data-refund]').forEach(function (b) {
        b.addEventListener('click', function () {
          var payment = (i.payments || []).filter(function (p) { return p.id === b.getAttribute('data-refund'); })[0] || {};
          var wrap = document.createElement('div');
          wrap.innerHTML = '<p class="hint">Refunding from ' + esc(payment.tenderLabel || payment.method || '') +
            ' (' + fmt.money(payment.amount) + ').</p>' +
            '<div class="field"><label class="lbl" for="rfAmount">Amount</label>' +
            '<input id="rfAmount" type="number" step="0.01" value="' + Number(payment.amount || i.balance || 0).toFixed(2) + '"></div>' +
            '<div class="field"><label class="lbl" for="rfReason">Reason</label><input id="rfReason" placeholder="Why"></div>' +
            '<p class="err" id="rfErr"></p>';
          S.modal({ title: 'Refund', kicker: i.number, body: wrap, actions: [
            { label: 'Refund it', kind: 'danger', onClick: function (root) {
              return api.post('/api/payments/' + encodeURIComponent(i.id) + '/refund', {
                paymentId: payment.id, amount: Number($('#rfAmount', root).value || 0), reason: $('#rfReason', root).value.trim()
              }).then(function () { S.toast('Refunded', 'ok'); refresh(); })
                .catch(function (err) { $('#rfErr', root).textContent = err.message; return false; });
            }}, { label: 'Cancel', kind: 'ghost' }] });
        });
      });
    }).catch(function (err) { $('#invOne').innerHTML = errorState(err); });
  }

  /* ========================================================= day close */
  function viewDayClose(query) {
    enter('dayclose');
    if (!can(3)) return setView(gate(3, 'The day close is for managers'));
    var date = (query && query.date) || fmt.today(0);
    setCrumb('Day close', fmt.day(date));
    setView(head('Day <em>close</em>', 'The Z-report: what came in, by which tender, and what the floor did.',
      '<input type="date" id="dcDate" value="' + esc(date) + '" style="width:auto" aria-label="Choose a day">' +
      '<button class="btn ghost" type="button" id="dcCsv">Export the day</button>') +
      '<div id="dcBody">' + loading() + '</div>');
    $('#dcDate').addEventListener('change', function (e) { window.location.hash = '#/dayclose?date=' + e.target.value; });
    $('#dcCsv').addEventListener('click', function () { api.download('/api/export/invoices.csv', 'sillage-invoices.csv'); });
    api.get('/api/day-close?date=' + date).then(function (d) {
      var methods = d.byMethod || {};
      $('#dcBody').innerHTML = kpis([
        { label: 'Bills', value: fmt.num(d.invoices || 0) },
        { label: 'Gross', value: fmt.money(d.gross || 0) },
        { label: 'Tax collected', value: fmt.money(d.taxCollected || 0) },
        { label: 'Discounts given', value: fmt.money(d.discountGiven || 0) },
        { label: 'Tips', value: fmt.money(d.tips || 0) },
        { label: 'Refunds', value: fmt.money(d.refunds || 0) },
        { label: 'Net', value: fmt.money(d.net || 0) },
        { label: 'Still owed', value: fmt.money(d.outstanding || 0) }
      ]) + '<div class="desk-cols" style="margin-top:1.6rem">' +
        panel('By tender', S.bars(Object.keys(methods).map(function (k) {
          return { label: k, value: Number(methods[k]) };
        }), { labelWidth: '8rem' }) || '<p class="muted">Nothing taken.</p>', '') +
        panel('By status', '<ul class="list-plain">' + Object.keys(d.byStatus || {}).map(function (k) {
          return '<li><span class="grow">' + esc(k) + '</span><span class="num">' + fmt.num(d.byStatus[k]) + '</span></li>';
        }).join('') + '</ul>', '') +
        panel('Top services', S.bars((d.topServices || []).slice(0, 8).map(function (s) {
          return { label: s.name, value: Number(s.revenue), display: fmt.money(s.revenue) + ' \u00b7 ' + s.count };
        }), { labelWidth: '10rem' }) || '<p class="muted">No services today.</p>', '') +
        panel('The floor', '<dl class="kv">' +
          '<dt>Appointments</dt><dd>' + fmt.num((d.appointments || {}).total || 0) + '</dd>' +
          '<dt>Completed</dt><dd>' + fmt.num((d.appointments || {}).completed || 0) + '</dd>' +
          '<dt>Cancelled</dt><dd>' + fmt.num((d.appointments || {}).cancelled || 0) + '</dd>' +
          '<dt>No-shows</dt><dd>' + fmt.num((d.appointments || {}).noShows || 0) + '</dd></dl>', '') +
        '</div>' +
        '<div class="card" style="margin-top:1.4rem"><p class="tiny" style="margin-top:0">Signed off</p>' +
        '<p style="font-size:.94rem;color:var(--fg-soft)">Generated ' + esc(fmt.stamp(d.generatedAt)) +
        ' by ' + esc(me.displayName || 'the console') + '. This report is a snapshot: the bills behind it are the record.</p>' +
        '<div class="btn-row"><button class="btn sm ghost" type="button" id="dcPrint">Print the Z-report</button></div></div>';
      $('#dcPrint').addEventListener('click', function () { window.print(); });
    }).catch(function (err) { $('#dcBody').innerHTML = errorState(err); });
  }

  /* ============================================================ menu */
  function viewMenu() {
    enter('menu');
    if (!can(3)) return setView(gate(3, 'The index is edited by reception and above'));
    setCrumb('The index');
    setView(head('The index of <em>services</em>', 'What the salon sells, at what price, for how long, and in whose hands.',
      '<button class="btn ghost" type="button" id="catBtn">Categories</button>' +
      '<button class="btn solid" type="button" id="svcBtn">Add a service</button>') +
      '<div id="menuBody">' + loading() + '</div>');
    $('#svcBtn').addEventListener('click', function () { serviceDialog(null); });
    $('#catBtn').addEventListener('click', function () {
      api.get('/api/categories').then(function (r) {
        S.modal({ title: 'Categories', kicker: 'The index', body: table([{ label: 'Name' }, { label: 'Blurb' }, { label: 'Order', cls: 'r' }, { label: 'Active' }],
          (r.categories || []).map(function (c) {
            return { cells: ['<b>' + esc(c.name) + '</b>', esc(c.blurb || ''), c.order, c.active ? 'yes' : 'no'] };
          })) });
      });
    });
    api.get('/api/services').then(function (result) {
      var rows = result.services || [];
      $('#menuBody').innerHTML = table(
        [{ label: 'Service' }, { label: 'Room' }, { label: 'Time', cls: 'r' }, { label: 'Price', cls: 'r' },
         { label: 'Booked', cls: 'r' }, { label: 'Rating', cls: 'r' }, { label: 'Live' }, { label: '' }],
        rows.map(function (s) {
          return { cells: [
            '<b>' + esc(s.name) + '</b><br><span class="tiny">' + esc(s.code || '') + ' \u00b7 ' + esc(s.tagline || '') + '</span>',
            esc(s.categoryName || ''), '<span class="num">' + fmt.duration(s.durationMinutes) + '</span>',
            '<span class="num">' + fmt.money(s.price) + '</span>',
            '<span class="num">' + fmt.num(s.timesBooked || 0) + '</span>',
            '<span class="num">' + (s.rating ? Number(s.rating).toFixed(1) : '\u2014') + '</span>',
            (s.active ? '<span class="badge ok">live</span>' : '<span class="badge mut">hidden</span>') +
              (s.onlineBookable ? '' : ' <span class="badge warn">desk only</span>'),
            '<span class="btn-row"><button class="link" type="button" data-edit="' + esc(s.id) + '">Edit</button>' +
            '<button class="link" type="button" data-feature="' + esc(s.id) + '">' + (s.featured ? 'Unfeature' : 'Feature') + '</button></span>'] };
        }), { emptyTitle: 'The index is empty' });
      $$('[data-edit]').forEach(function (b) {
        b.addEventListener('click', function () {
          serviceDialog(rows.filter(function (s) { return s.id === b.getAttribute('data-edit'); })[0]);
        });
      });
      $$('[data-feature]').forEach(function (b) {
        b.addEventListener('click', function () {
          var service = rows.filter(function (s) { return s.id === b.getAttribute('data-feature'); })[0];
          api.patch('/api/services/' + encodeURIComponent(service.id) + '/featured', { featured: !service.featured })
            .then(function () { S.toast('Updated', 'ok'); refresh(); }).catch(function (err) { S.toast(err.message, 'bad'); });
        });
      });
    }).catch(function (err) { $('#menuBody').innerHTML = errorState(err); });
  }

  function serviceDialog(service) {
    var cats = ((boot.menu || {}).categories || []);
    var staff = boot.staff || [];
    var body = document.createElement('div');
    body.innerHTML = '<div class="row">' +
      '<div class="field"><label class="lbl" for="svName">Name</label><input id="svName" value="' + esc(service ? service.name : '') + '"></div>' +
      '<div class="field"><label class="lbl" for="svCat">Room</label><select id="svCat">' +
        cats.map(function (c) { return '<option value="' + esc(c.id) + '"' + (service && service.categoryId === c.id ? ' selected' : '') + '>' + esc(c.name) + '</option>'; }).join('') +
      '</select></div></div>' +
      '<div class="field"><label class="lbl" for="svTag">Tagline</label><input id="svTag" value="' + esc(service ? service.tagline : '') + '"></div>' +
      '<div class="field"><label class="lbl" for="svDesc">Description</label><textarea id="svDesc">' + esc(service ? service.description : '') + '</textarea></div>' +
      '<div class="field"><label class="lbl" for="svEd">Editorial line</label><textarea id="svEd">' + esc(service ? service.editorial : '') + '</textarea></div>' +
      '<div class="row">' +
      '<div class="field"><label class="lbl" for="svPrice">Price</label><input id="svPrice" type="number" step="50" value="' + (service ? service.price : 1000) + '"></div>' +
      '<div class="field"><label class="lbl" for="svMins">Minutes</label><input id="svMins" type="number" step="5" value="' + (service ? service.durationMinutes : 45) + '"></div>' +
      '<div class="field"><label class="lbl" for="svTax">Tax %</label><input id="svTax" type="number" step="0.5" value="' + (service && service.taxPct ? service.taxPct : 18) + '"></div></div>' +
      '<div class="field"><label class="lbl" for="svTags">Tags (comma separated)</label><input id="svTags" value="' + esc(service ? (service.tags || []).join(', ') : '') + '"></div>' +
      '<div class="field"><span class="lbl">In these hands</span><div style="border:1px solid var(--rule);padding:.6rem;max-height:11rem;overflow:auto">' +
      staff.map(function (m) {
        var on = service && service.staffIds && service.staffIds.indexOf(m.id) >= 0;
        return '<label class="check"><input type="checkbox" class="svStaff" value="' + esc(m.id) + '"' + (on ? ' checked' : '') + '><span>' + esc(m.name) + '</span></label>';
      }).join('') + '</div></div>' +
      '<div class="field"><label class="lbl" for="svPrep">Before</label><input id="svPrep" value="' + esc(service ? service.prepNotes : '') + '"></div>' +
      '<div class="field"><label class="lbl" for="svAfter">After</label><input id="svAfter" value="' + esc(service ? service.aftercareNotes : '') + '"></div>' +
      '<label class="check"><input type="checkbox" id="svActive"' + (!service || service.active ? ' checked' : '') + '><span>On the index</span></label>' +
      '<label class="check"><input type="checkbox" id="svOnline"' + (!service || service.onlineBookable ? ' checked' : '') + '><span>Bookable online</span></label>' +
      '<p class="err" id="svErr" role="alert"></p>';
    S.modal({
      title: service ? 'Edit ' + esc(service.name) : 'Add a service', kicker: 'The index', body: body, size: 'wide',
      actions: [{ label: 'Save', kind: 'solid', onClick: function (root) {
        var payload = {
          name: $('#svName', root).value.trim(), categoryId: $('#svCat', root).value,
          tagline: $('#svTag', root).value.trim(), description: $('#svDesc', root).value.trim(),
          editorial: $('#svEd', root).value.trim(), price: Number($('#svPrice', root).value || 0),
          durationMinutes: Number($('#svMins', root).value || 0), taxPct: Number($('#svTax', root).value || 0),
          tags: $('#svTags', root).value.split(',').map(function (t) { return t.trim(); }).filter(Boolean),
          staffIds: $$('.svStaff', root).filter(function (c) { return c.checked; }).map(function (c) { return c.value; }),
          prepNotes: $('#svPrep', root).value.trim(), aftercareNotes: $('#svAfter', root).value.trim(),
          active: $('#svActive', root).checked, onlineBookable: $('#svOnline', root).checked
        };
        var call = service ? api.patch('/api/services/' + encodeURIComponent(service.id), payload) : api.post('/api/services', payload);
        return call.then(function () { S.toast('Saved', 'ok'); S.bootstrap(true).then(function (b) { boot = b; }); refresh(); })
          .catch(function (err) { $('#svErr', root).textContent = (err.details || []).join(' \u00b7 ') || err.message; return false; });
      }}, { label: 'Cancel', kind: 'ghost' }]
    });
  }

  /* ======================================================= inventory */
  function viewInventory(query) {
    enter('inventory');
    if (!can(3)) return setView(gate(3, 'The shelf is for reception and above'));
    setCrumb('The shelf');
    setView(head('The <em>shelf</em>', 'Stock never moves except through the ledger, so the count and the history always agree.',
      '<button class="btn ghost" type="button" id="lowBtn">Below reorder point</button>' +
      '<button class="btn ghost" type="button" id="stockCsv">Export CSV</button>' +
      '<button class="btn solid" type="button" id="prodBtn">Add a product</button>') +
      '<div id="inv2Body">' + loading() + '</div>');
    $('#stockCsv').addEventListener('click', function () { api.download('/api/export/products.csv', 'sillage-products.csv'); });
    $('#lowBtn').addEventListener('click', function () { window.location.hash = '#/inventory?low=1'; });
    $('#prodBtn').addEventListener('click', function () { productDialog(null); });
    var url = query && query.low ? '/api/products/low' : '/api/products';
    Promise.all([api.get(url), api.get('/api/inventory/stats')]).then(function (r) {
      var rows = (r[0].products || []);
      var stats = r[1] || {};
      $('#inv2Body').innerHTML = kpis([
        { label: 'Products', value: fmt.num(stats.products || rows.length) },
        { label: 'Stock at cost', value: fmt.money(stats.stockValue || 0) },
        { label: 'Retail value', value: fmt.money(stats.retailValue || 0) },
        { label: 'Potential margin', value: fmt.money(stats.potentialMargin || 0) },
        { label: 'Below reorder', value: fmt.num(stats.lowStock || 0) },
        { label: 'Out of stock', value: fmt.num(stats.outOfStock || 0) },
        { label: 'Movements 30d', value: fmt.num(stats.movements30d || 0) }
      ]) + '<div style="margin-top:1.4rem">' + table(
        [{ label: 'Product' }, { label: 'SKU' }, { label: 'Stock', cls: 'r' }, { label: 'Reorder at', cls: 'r' },
         { label: 'Cost', cls: 'r' }, { label: 'Retail', cls: 'r' }, { label: 'Margin', cls: 'r' }, { label: 'State' }, { label: '' }],
        rows.map(function (p) {
          return { cells: [
            '<b>' + esc(p.name) + '</b><br><span class="tiny">' + esc(p.brand || '') + ' \u00b7 ' + esc(p.category || '') + '</span>',
            '<span class="mono" style="font-size:.74rem">' + esc(p.sku) + '</span>',
            '<span class="num">' + p.stock + '</span>',
            '<span class="num">' + p.reorderLevel + '</span>',
            '<span class="num">' + fmt.money(p.costPrice) + '</span>',
            '<span class="num">' + fmt.money(p.price) + '</span>',
            '<span class="num">' + fmt.pct(p.marginPct || 0) + '</span>',
            S.badge(p.status === 'ok' ? 'in stock' : p.status, p.status === 'ok' ? 'ok' : p.status === 'low' ? 'warn' : 'bad'),
            '<span class="btn-row"><button class="link" type="button" data-adjust="' + esc(p.id) + '">Adjust</button>' +
            '<button class="link" type="button" data-receive="' + esc(p.id) + '">Receive</button>' +
            '<button class="link" type="button" data-ledger="' + esc(p.id) + '">Ledger</button></span>'] };
        }), { emptyTitle: 'The shelf is empty', emptyBody: 'Add a product to start counting.' }) + '</div>';
      $$('[data-adjust]').forEach(function (b) {
        b.addEventListener('click', function () { stockDialog(rows.filter(function (p) { return p.id === b.getAttribute('data-adjust'); })[0], 'adjust'); });
      });
      $$('[data-receive]').forEach(function (b) {
        b.addEventListener('click', function () { stockDialog(rows.filter(function (p) { return p.id === b.getAttribute('data-receive'); })[0], 'receive'); });
      });
      $$('[data-ledger]').forEach(function (b) {
        b.addEventListener('click', function () {
          var product = rows.filter(function (p) { return p.id === b.getAttribute('data-ledger'); })[0];
          api.get('/api/products/' + encodeURIComponent(product.id) + '/movements?limit=40').then(function (r2) {
            S.modal({ title: 'Ledger \u00b7 ' + esc(product.name), kicker: 'Every change is recorded', body:
              table([{ label: 'When' }, { label: 'Reason' }, { label: 'Delta', cls: 'r' }, { label: 'Balance', cls: 'r' }, { label: 'By' }],
                (r2.movements || []).map(function (m) {
                  return { cells: ['<span class="mono" style="font-size:.74rem">' + esc(fmt.stamp(m.at)) + '</span>',
                    esc(m.reason), '<span class="num">' + (m.delta > 0 ? '+' : '') + m.delta + '</span>',
                    '<span class="num">' + m.balanceAfter + '</span>', esc(m.byName || '')] };
                }), { emptyTitle: 'No movements yet' }), size: 'wide' });
          });
        });
      });
      if (query && query.receive) {
        var target = rows.filter(function (p) { return p.id === query.receive; })[0];
        if (target) stockDialog(target, 'receive');
      }
    }).catch(function (err) { $('#inv2Body').innerHTML = errorState(err); });
  }

  function stockDialog(product, mode) {
    var body = document.createElement('div');
    if (mode === 'receive') {
      body.innerHTML = '<p class="hint">Receiving <b>' + esc(product.name) + '</b> (now ' + product.stock + ').</p>' +
        '<div class="row"><div class="field"><label class="lbl" for="rcQty">Quantity</label><input id="rcQty" type="number" value="' + (product.reorderQty || 10) + '"></div>' +
        '<div class="field"><label class="lbl" for="rcCost">Cost each (optional)</label><input id="rcCost" type="number" step="0.01" placeholder="' + product.costPrice + '"></div></div>' +
        '<div class="field"><label class="lbl" for="rcSup">Supplier</label><input id="rcSup" value="' + esc(product.supplier || '') + '"></div>' +
        '<p class="err" id="rcErr"></p>';
    } else {
      body.innerHTML = '<p class="hint">Adjusting <b>' + esc(product.name) + '</b> (now ' + product.stock + '). ' +
        'Negative numbers take stock out.</p>' +
        '<div class="field"><label class="lbl" for="adDelta">Change</label><input id="adDelta" type="number" value="0"></div>' +
        '<div class="field"><label class="lbl" for="adReason">Reason</label><select id="adReason">' +
        ['CORRECTION', 'DAMAGE', 'RETURN', 'PURCHASE', 'SERVICE_USAGE'].map(function (r) { return '<option>' + r + '</option>'; }).join('') + '</select></div>' +
        '<div class="field"><label class="lbl" for="adNote">Note</label><input id="adNote" placeholder="What happened"></div>' +
        '<p class="err" id="adErr"></p>';
    }
    S.modal({
      title: mode === 'receive' ? 'Receive stock' : 'Adjust stock', kicker: esc(product.sku), body: body,
      actions: [{ label: mode === 'receive' ? 'Receive it' : 'Write the change', kind: 'solid', onClick: function (root) {
        var call = mode === 'receive'
          ? api.post('/api/products/' + encodeURIComponent(product.id) + '/receive', {
              qty: Number($('#rcQty', root).value || 0), costPrice: Number($('#rcCost', root).value || 0) || null,
              supplier: $('#rcSup', root).value.trim() })
          : api.post('/api/products/' + encodeURIComponent(product.id) + '/stock', {
              delta: Number($('#adDelta', root).value || 0), reason: $('#adReason', root).value,
              note: $('#adNote', root).value.trim() });
        return call.then(function () { S.toast('Ledger written', 'ok'); refresh(); })
          .catch(function (err) {
            var node = $(mode === 'receive' ? '#rcErr' : '#adErr', root);
            if (node) node.textContent = err.message;
            return false;
          });
      }}, { label: 'Cancel', kind: 'ghost' }]
    });
  }

  function productDialog(product) {
    var body = document.createElement('div');
    body.innerHTML = '<div class="row">' +
      '<div class="field"><label class="lbl" for="pName">Name</label><input id="pName" value="' + esc(product ? product.name : '') + '"></div>' +
      '<div class="field"><label class="lbl" for="pBrand">Brand</label><input id="pBrand" value="' + esc(product ? product.brand : '') + '"></div></div>' +
      '<div class="row">' +
      '<div class="field"><label class="lbl" for="pCat">Category</label><input id="pCat" value="' + esc(product ? product.category : 'Retail') + '"></div>' +
      '<div class="field"><label class="lbl" for="pUnit">Unit</label><input id="pUnit" value="' + esc(product ? product.unit : 'bottle') + '"></div></div>' +
      '<div class="row">' +
      '<div class="field"><label class="lbl" for="pPrice">Retail</label><input id="pPrice" type="number" step="10" value="' + (product ? product.price : 0) + '"></div>' +
      '<div class="field"><label class="lbl" for="pCost">Cost</label><input id="pCost" type="number" step="10" value="' + (product ? product.costPrice : 0) + '"></div>' +
      '<div class="field"><label class="lbl" for="pTax">Tax %</label><input id="pTax" type="number" step="0.5" value="18"></div></div>' +
      '<div class="row">' +
      '<div class="field"><label class="lbl" for="pStock">Opening stock</label><input id="pStock" type="number" value="' + (product ? product.stock : 0) + '"></div>' +
      '<div class="field"><label class="lbl" for="pReorder">Reorder at</label><input id="pReorder" type="number" value="6"></div>' +
      '<div class="field"><label class="lbl" for="pUsage">Used per service</label><input id="pUsage" type="number" step="0.1" value="0"></div></div>' +
      '<div class="field"><label class="lbl" for="pSup">Supplier</label><input id="pSup" value="' + esc(product ? product.supplier : '') + '"></div>' +
      '<div class="field"><label class="lbl" for="pDesc">Description</label><textarea id="pDesc">' + esc(product ? product.description : '') + '</textarea></div>' +
      '<p class="err" id="pErr" role="alert"></p>';
    S.modal({
      title: product ? 'Edit product' : 'Add a product', kicker: 'The shelf', body: body, size: 'wide',
      actions: [{ label: 'Save', kind: 'solid', onClick: function (root) {
        var payload = {
          name: $('#pName', root).value.trim(), brand: $('#pBrand', root).value.trim(),
          category: $('#pCat', root).value.trim(), unit: $('#pUnit', root).value.trim(),
          price: Number($('#pPrice', root).value || 0), costPrice: Number($('#pCost', root).value || 0),
          taxPct: Number($('#pTax', root).value || 0), stock: Number($('#pStock', root).value || 0),
          reorderLevel: Number($('#pReorder', root).value || 0), usagePerService: Number($('#pUsage', root).value || 0),
          supplier: $('#pSup', root).value.trim(), description: $('#pDesc', root).value.trim()
        };
        var call = product ? api.patch('/api/products/' + encodeURIComponent(product.id), payload) : api.post('/api/products', payload);
        return call.then(function () { S.toast('Saved', 'ok'); refresh(); })
          .catch(function (err) { $('#pErr', root).textContent = (err.details || []).join(' \u00b7 ') || err.message; return false; });
      }}, { label: 'Cancel', kind: 'ghost' }]
    });
  }

  /* ====================================================== suggestions */
  function viewSuggestions(query) {
    enter('suggestions');
    if (!can(2)) return setView(gate(2, 'Suggestions are for staff'));
    setCrumb('Suggestions', 'deterministic');
    var tab = (query && query.tab) || 'desk';
    setView(head('Suggestions, <em>not</em> predictions',
      'No model, no training, no black box. Every card below is produced by a published rule \u2014 co-occurrence counts, ' +
      'a first-order Markov chain, rebooking intervals, thresholds \u2014 and every card says which rule made it and with what numbers.',
      '<div class="seg" role="group" aria-label="Suggestion views">' +
      ['desk', 'clients', 'bundles', 'operations', 'today', 'engine', 'matrix'].map(function (t) {
        return '<button type="button" data-tab="' + t + '" aria-pressed="' + (t === tab) + '">' + t + '</button>'; }).join('') +
      '</div>') + '<div id="suggBody">' + loading('Asking the rule engine') + '</div>');
    $$('[data-tab]').forEach(function (b) {
      b.addEventListener('click', function () { window.location.hash = '#/suggestions?tab=' + b.getAttribute('data-tab'); });
    });

    if (tab === 'engine') return renderEngine();
    if (tab === 'matrix') return renderMatrix();

    var url = tab === 'desk' ? '/api/suggestions?limit=14'
      : tab === 'clients' ? '/api/suggestions?limit=14'
      : tab === 'bundles' ? '/api/suggestions/bundles?limit=12'
      : tab === 'operations' ? '/api/suggestions/operations?limit=14'
      : '/api/suggestions/today?limit=14';
    api.get(url).then(function (result) {
      var items = tab === 'clients' ? ((result.meta || {}).clients || []) : (result.suggestions || []);
      var meta = result.meta || {};
      var html = '<p class="hint" style="margin-bottom:1.2rem">' + esc(meta.engine || 'Sillage Rule Engine v1') +
        ' \u00b7 ' + fmt.num(items.length) + ' suggestion' + (items.length === 1 ? '' : 's') +
        (meta.tookMillis ? ' \u00b7 computed in ' + meta.tookMillis + ' ms' : '') + '</p>' +
        '<div class="grid g2">' + items.map(function (s, i) {
          return '<article class="suggestion" data-i="' + i + '">' +
            '<div class="head"><h4>' + esc(s.title) + '</h4><span class="badge ' + esc(s.confidence || 'mut') + '">' +
            esc(s.impact || s.confidence || '') + '</span></div>' +
            '<p class="why">' + esc(s.rationale || s.detail) + '</p>' +
            '<div class="between" style="margin-top:.6rem;gap:.6rem;flex-wrap:wrap">' +
              '<span class="tiny">' + esc(s.kind || '') + (s.audience ? ' \u00b7 ' + esc(s.audience) : '') +
              (s.horizon ? ' \u00b7 ' + esc(s.horizon) : '') + '</span>' +
              '<span class="num">' + Number(s.score || 0).toFixed(2) + '</span></div>' +
            '<div class="rules">' + (s.rules || []).map(function (r) { return '<code>' + esc(r) + '</code>'; }).join('') + '</div>' +
            '<div class="meter"><i style="width:' + Math.round((s.score || 0) * 100) + '%"></i></div>' +
            '<div class="btn-row" style="margin-top:.7rem">' +
              '<button class="link" type="button" data-why="' + i + '">Why this?</button>' +
              (s.entityId && s.entityKind === 'customer' ? '<button class="link" type="button" data-goto="#/clients/' + esc(s.entityId) + '">Open the client</button>' : '') +
              (s.entityId && s.entityKind === 'product' ? '<button class="link" type="button" data-goto="#/inventory">Open the shelf</button>' : '') +
              (s.entityId && s.entityKind === 'service' ? '<button class="link" type="button" data-goto="#/menu">Open the index</button>' : '') +
              '<button class="link" type="button" data-done="' + i + '">Mark done</button>' +
              '<button class="link" type="button" data-dismiss="' + i + '">Dismiss</button>' +
            '</div></article>';
        }).join('') + '</div>';
      $('#suggBody').innerHTML = items.length ? html : emptyState('Nothing to suggest',
        '<p>The engine needs a week of trading before it can say anything useful.</p>');
      $$('[data-why]').forEach(function (b) {
        b.addEventListener('click', function () {
          var s = items[Number(b.getAttribute('data-why'))];
          S.modal({ title: esc(s.title), kicker: 'How this was worked out', body:
            '<p>' + esc(s.rationale || '') + '</p><dl class="kv" style="margin-top:1rem">' +
            '<dt>Kind</dt><dd>' + esc(s.kind) + '</dd><dt>Score</dt><dd>' + Number(s.score || 0).toFixed(3) + '</dd>' +
            '<dt>Confidence</dt><dd>' + esc(s.confidence || '') + '</dd><dt>Impact</dt><dd>' + esc(s.impact || '') + '</dd>' +
            '<dt>Horizon</dt><dd>' + esc(s.horizon || '') + '</dd><dt>Rules</dt><dd class="mono">' + esc((s.rules || []).join(', ')) + '</dd></dl>' +
            (s.payload ? '<pre class="mono" style="margin-top:1rem;font-size:.72rem;background:var(--sunken);border:1px solid var(--rule);padding:.8rem;overflow:auto">' +
              esc(JSON.stringify(s.payload, null, 2)) + '</pre>' : ''), size: 'wide' });
        });
      });
      $$('[data-goto]').forEach(function (b) {
        b.addEventListener('click', function () { window.location.hash = b.getAttribute('data-goto'); });
      });
      $$('[data-done]').forEach(function (b) {
        b.addEventListener('click', function () { setState(items[Number(b.getAttribute('data-done'))], 'DONE'); });
      });
      $$('[data-dismiss]').forEach(function (b) {
        b.addEventListener('click', function () { setState(items[Number(b.getAttribute('data-dismiss'))], 'DISMISSED'); });
      });
    }).catch(function (err) { $('#suggBody').innerHTML = errorState(err); });

    function setState(s, status) {
      api.post('/api/suggestions/' + encodeURIComponent(s.id) + '/state', { status: status, kind: s.kind, entityId: s.entityId })
        .then(function () { S.toast(status === 'DONE' ? 'Marked done' : 'Dismissed', 'ok'); refresh(); })
        .catch(function (err) { S.toast(err.message, 'bad'); });
    }
  }

  function renderEngine() {
    api.get('/api/suggestions/engine').then(function (engine) {
      var rules = engine.rules || [];
      $('#suggBody').innerHTML =
        '<div class="card" style="margin-bottom:1.4rem"><p class="tiny" style="margin-top:0">The engine</p>' +
        '<p style="font-family:var(--display);font-size:1.3rem;margin:.3rem 0 .6rem">' + esc(engine.engine || '') + '</p>' +
        '<p style="color:var(--fg-soft);font-size:.94rem">' + esc(engine.summary || engine.note ||
          'Market-basket statistics, a first-order Markov chain over service sequences, median rebooking intervals from the RFM record, and a table of published thresholds. Nothing here is learned by a model; every number can be recomputed from the diary.') + '</p></div>' +
        '<div class="rulebook">' + rules.map(function (r) {
          return '<details><summary>' + esc(r.id || r.code || '') + ' \u00b7 ' + esc(r.name || r.title || '') + '</summary>' +
            '<div class="body"><p>' + esc(r.description || r.what || '') + '</p>' +
            (r.inputs ? '<p class="tiny">Inputs</p><p>' + esc(Array.isArray(r.inputs) ? r.inputs.join(', ') : JSON.stringify(r.inputs)) + '</p>' : '') +
            (r.formula ? '<p class="tiny">Formula</p><pre>' + esc(r.formula) + '</pre>' : '') +
            (r.threshold || r.thresholds ? '<p class="tiny">Thresholds</p><pre>' + esc(typeof (r.threshold || r.thresholds) === 'string' ? (r.threshold || r.thresholds) : JSON.stringify(r.threshold || r.thresholds, null, 2)) + '</pre>' : '') +
            '</div></details>';
        }).join('') + '</div>' +
        (engine.stats ? '<div class="card" style="margin-top:1.4rem"><p class="tiny" style="margin-top:0">Corpus</p>' +
          '<dl class="kv">' + Object.keys(engine.stats).map(function (k) {
            return '<dt>' + esc(k) + '</dt><dd class="num">' + esc(String(engine.stats[k])) + '</dd>'; }).join('') + '</dl></div>' : '');
    }).catch(function (err) { $('#suggBody').innerHTML = errorState(err); });
  }

  function renderMatrix() {
    api.get('/api/suggestions/matrix?limit=60').then(function (result) {
      $('#suggBody').innerHTML = '<p class="hint" style="margin-bottom:1rem">Co-occurrence pairs from the last year of the diary: ' +
        'how often two services share a visit (support), how often the second follows the first (confidence), and how much more often than chance (lift).</p>' +
        table([{ label: 'From' }, { label: 'To' }, { label: 'Pairs', cls: 'r' }, { label: 'Support', cls: 'r' },
               { label: 'Confidence', cls: 'r' }, { label: 'Lift', cls: 'r' }],
          (result.pairs || []).map(function (p) {
            return { cells: [esc(p.fromName), esc(p.toName), '<span class="num">' + p.pairs + '</span>',
              '<span class="num">' + fmt.pct(p.support * 100, 1) + '</span>',
              '<span class="num">' + fmt.pct(p.confidence * 100, 0) + '</span>',
              '<span class="num">' + Number(p.lift).toFixed(2) + '\u00d7</span>'] };
          }), { emptyTitle: 'Not enough history yet' });
    }).catch(function (err) { $('#suggBody').innerHTML = errorState(err); });
  }

  /* ======================================================== analytics */
  function viewAnalytics(query) {
    enter('analytics');
    if (!can(3)) return setView(gate(3, 'The numbers are for managers'));
    var range = (query && query.range) || '30d';
    setCrumb('Analytics', range);
    setView(head('The <em>numbers</em>', 'Revenue, chairs, retention and a forecast that says exactly how it was made.',
      '<div class="seg" role="group" aria-label="Range">' +
      ['7d', '30d', '90d', 'ytd'].map(function (r) {
        return '<button type="button" data-range="' + r + '" aria-pressed="' + (r === range) + '">' + r + '</button>'; }).join('') +
      '</div>') +
      '<div id="anBody">' + loading('Crunching the book') + '</div>');
    $$('[data-range]').forEach(function (b) {
      b.addEventListener('click', function () { window.location.hash = '#/analytics?range=' + b.getAttribute('data-range'); });
    });
    Promise.all([
      api.get('/api/analytics/overview?range=' + range),
      api.get('/api/analytics/retention'),
      api.get('/api/analytics/forecast?days=7'),
      api.get('/api/analytics/occupancy?range=' + range),
      api.get('/api/analytics/tax?range=' + range)
    ]).then(function (r) {
      var o = r[0], ret = r[1], fc = r[2], occ = r[3], tax = r[4];
      var html = kpis([
        { label: 'Revenue net of refunds', value: fmt.money(o.revenue), delta: (o.deltas || {}).revenue },
        { label: 'Gross billed', value: fmt.money(o.gross), delta: (o.deltas || {}).gross },
        { label: 'Tax collected', value: fmt.money(o.taxCollected) },
        { label: 'Bills', value: fmt.num(o.invoices), delta: (o.deltas || {}).invoices },
        { label: 'Average ticket', value: fmt.money(o.averageTicket), delta: (o.deltas || {}).averageTicket },
        { label: 'Occupancy', value: fmt.pct(o.occupancy || 0), note: fmt.duration(o.chairMinutes) + ' of ' + fmt.duration(o.capacityMinutes) },
        { label: 'Revenue per chair hour', value: fmt.money(o.revenuePerChairHour) },
        { label: 'Retail share', value: fmt.pct(o.retailShare || 0), note: fmt.money(o.retailRevenue) + ' of product' },
        { label: 'Expenses', value: fmt.money(o.expenses), note: 'profit est. ' + fmt.money(o.estimatedProfit) },
        { label: 'No-shows', value: fmt.pct(o.noShowRate || 0), note: fmt.num(o.noShows) + ' of ' + fmt.num(o.appointments) },
        { label: 'New clients', value: fmt.num(o.newCustomers) },
        { label: 'Outstanding', value: fmt.money(o.outstanding) }
      ]);
      html += '<div class="grid g2" style="margin-top:1.6rem;align-items:start">';
      html += panel('Revenue by day', lineChart((o.series || []), 'revenue') +
        '<p class="legend" style="margin-top:.7rem"><span><i></i>this period</span></p>' +
        '<p class="hint">' + esc((o.range || {}).from || '') + ' to ' + esc((o.range || {}).to || '') + '</p>', '');
      html += panel('Top services', S.bars((o.topServices || []).slice(0, 8).map(function (s) {
        return { label: s.name, value: Number(s.revenue), display: fmt.money(s.revenue) + ' \u00b7 ' + fmt.num(s.count) };
      })), '');
      html += panel('Chairs', table([{ label: 'Stylist' }, { label: 'Bills', cls: 'r' }, { label: 'Revenue', cls: 'r' },
        { label: 'Commission', cls: 'r' }, { label: 'Occupancy', cls: 'r' }, { label: 'Rating', cls: 'r' }],
        (o.staffPerformance || []).map(function (s) {
          return { cells: ['<b>' + esc(s.name) + '</b>', '<span class="num">' + fmt.num(s.invoices || s.bills || 0) + '</span>',
            '<span class="num">' + fmt.money(s.revenue) + '</span>', '<span class="num">' + fmt.money(s.commission || 0) + '</span>',
            '<span class="num">' + fmt.pct(s.occupancy || 0) + '</span>', '<span class="num">' + Number(s.rating || 0).toFixed(1) + '</span>'] };
        })), '', { flush: true });
      html += panel('Forecast, next seven days', kpis([
        { label: 'Expected', value: fmt.money(fc.expected || fc.total || 0), note: 'weekday-weighted 8-week average' },
        { label: 'Trend factor', value: '\u00d7' + Number(fc.trend || 1).toFixed(3) },
        { label: 'Best day', value: esc((fc.best || {}).label || '\u2014'), note: fmt.money((fc.best || {}).value || 0) }
      ]) + '<div style="margin-top:1rem">' + lineChart((fc.days || []).map(function (d) {
        return { label: d.label || d.date, revenue: d.expected || d.value };
      }), 'revenue') + '</div>' +
      '<p class="hint" style="margin-top:.7rem">' + esc(fc.method || fc.note ||
        'A weekday-weighted moving average over eight weeks, clamped to \u00b115% by the trend factor. Not a fitted model.') + '</p>', '');
      html += panel('Retention &amp; segments', kpis([
        { label: 'Repeat rate', value: fmt.pct((ret.repeatRate || 0)), note: fmt.num(ret.repeatClients || 0) + ' of ' + fmt.num(ret.clients || 0) },
        { label: 'Average gap', value: (ret.averageGapDays || 0) + ' d' },
        { label: 'At risk', value: fmt.num((ret.segments || {}).churnRisk || (ret.segments || {}).atRisk || 0) }
      ]) + '<div style="margin-top:1rem">' + S.bars(Object.keys(ret.segments || {}).map(function (k) {
        return { label: k, value: Number(ret.segments[k]), display: fmt.num(ret.segments[k]) + ' clients' };
      }), { labelWidth: '9rem' }) + '</div>' +
      '<p class="hint" style="margin-top:.7rem">' + esc(ret.note || 'Five deterministic RFM segments: recency against the median gap, frequency against the median visits, and lifetime value against the median spend.') + '</p>', '');
      html += panel('Occupancy by hour', heat(occ), '');
      html += panel('Channels', S.bars(Object.keys(o.channelMix || {}).map(function (k) {
        return { label: k, value: Number((o.channelMix || {})[k]), display: fmt.num((o.channelMix || {})[k]) + ' bills' };
      }), { labelWidth: '7rem' }), '');
      html += panel('Tax report <span class="muted">\u00b7 ' + esc((tax.range || {}).label || range) + '</span>',
        '<dl class="kv">' +
        '<dt>Taxable value</dt><dd class="num">' + fmt.money(tax.taxable || 0) + '</dd>' +
        '<dt>CGST</dt><dd class="num">' + fmt.money(tax.cgst || 0) + '</dd>' +
        '<dt>SGST</dt><dd class="num">' + fmt.money(tax.sgst || 0) + '</dd>' +
        '<dt>Total tax</dt><dd class="num">' + fmt.money(tax.total || tax.taxTotal || 0) + '</dd>' +
        '<dt>GSTIN</dt><dd class="mono">' + esc(tax.gstin || (boot.salon || {}).gstin || '') + '</dd></dl>' +
        '<p class="hint" style="margin-top:.7rem">A working summary for the accountant, not a statutory return.</p>',
        '<button class="btn sm ghost" type="button" id="taxCsv">CSV</button>');
      html += '</div>';
      $('#anBody').innerHTML = html;
      var taxBtn = $('#taxCsv');
      if (taxBtn) taxBtn.addEventListener('click', function () { api.download('/api/analytics/tax.csv?range=' + range, 'sillage-tax.csv'); });
    }).catch(function (err) { $('#anBody').innerHTML = errorState(err); });
  }

  function lineChart(rows, key) {
    if (!rows.length) return '<p class="muted">No data in this window.</p>';
    var w = 640, h = 180, padL = 46, padB = 22, padT = 10, padR = 8;
    var values = rows.map(function (r) { return Number(r[key] || 0); });
    var max = Math.max.apply(null, values.concat([1]));
    var min = 0;
    var stepX = rows.length > 1 ? (w - padL - padR) / (rows.length - 1) : 0;
    function x(i) { return padL + i * stepX; }
    function y(v) { return padT + (h - padT - padB) * (1 - (v - min) / (max - min || 1)); }
    var line = values.map(function (v, i) { return (i ? 'L' : 'M') + x(i).toFixed(1) + ' ' + y(v).toFixed(1); }).join(' ');
    var area = line + ' L' + x(values.length - 1).toFixed(1) + ' ' + (h - padB) + ' L' + padL + ' ' + (h - padB) + ' Z';
    var gridLines = [0, 0.25, 0.5, 0.75, 1].map(function (t) {
      var v = max * t;
      return '<line class="grid-line" x1="' + padL + '" y1="' + y(v).toFixed(1) + '" x2="' + (w - padR) + '" y2="' + y(v).toFixed(1) + '"/>' +
        '<text class="axis" x="' + (padL - 6) + '" y="' + (y(v) + 3).toFixed(1) + '" text-anchor="end">' +
        (v >= 100000 ? (v / 100000).toFixed(1) + 'L' : v >= 1000 ? Math.round(v / 1000) + 'k' : Math.round(v)) + '</text>';
    }).join('');
    var every = Math.max(1, Math.floor(rows.length / 6));
    var labels = rows.map(function (r, i) {
      if (i % every !== 0 && i !== rows.length - 1) return '';
      return '<text class="axis" x="' + x(i).toFixed(1) + '" y="' + (h - 6) + '" text-anchor="middle">' + esc(r.label || '') + '</text>';
    }).join('');
    var dots = values.map(function (v, i) {
      return '<circle cx="' + x(i).toFixed(1) + '" cy="' + y(v).toFixed(1) + '" r="2.4" fill="var(--accent)"><title>' +
        esc(rows[i].label || '') + ': ' + fmt.money(v) + '</title></circle>';
    }).join('');
    return '<div class="chart"><svg viewBox="0 0 ' + w + ' ' + h + '" role="img" aria-label="Revenue over time">' +
      gridLines + '<path class="series-area" d="' + area + '"/><path class="series-line" d="' + line + '"/>' + dots + labels + '</svg></div>';
  }

  function heat(occ) {
    var cells = occ.cells || occ.grid || [];
    var days = occ.days || ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    var hours = occ.hours || [];
    if (!cells.length) return '<p class="muted">No occupancy data in this window.</p>';
    var max = Math.max.apply(null, cells.map(function (c) { return Number(c.value || c.count || 0); }).concat([1]));
    var html = '<div class="heat" style="grid-template-columns:3.2rem repeat(' + days.length + ', minmax(2rem,1fr))">';
    html += '<div class="h"></div>' + days.map(function (d) { return '<div class="h">' + esc(String(d).slice(0, 3)) + '</div>'; }).join('');
    var byHour = {};
    cells.forEach(function (c) {
      var key = c.hour + '|' + (c.dayIndex !== undefined ? c.dayIndex : days.indexOf(c.day));
      byHour[key] = Number(c.value || c.count || 0);
    });
    (hours.length ? hours : Array.from({ length: 12 }, function (_, i) { return i + 9; })).forEach(function (hour) {
      html += '<div class="h">' + (hour < 10 ? '0' + hour : hour) + '</div>';
      days.forEach(function (d, di) {
        var v = byHour[hour + '|' + di] || 0;
        var alpha = max ? (v / max) : 0;
        html += '<div class="cell" title="' + esc(String(d)) + ' ' + hour + ':00 \u00b7 ' + v +
          '" style="background:color-mix(in srgb, var(--accent) ' + Math.round(alpha * 100) + '%, var(--sunken))">' +
          (v ? '<span>' + v + '</span>' : '') + '</div>';
      });
    });
    html += '</div><p class="hint" style="margin-top:.7rem">' + esc(occ.note || 'Bookings per hour, by weekday.') + '</p>';
    return html;
  }

  /* ========================================================== search */
  function viewSearch(query) {
    enter('search');
    if (!can(1)) return setView(gate(1, 'Sign in to search the whole book'));
    var q = (query && query.q) || '';
    setCrumb('Search index', q || 'idle');
    setView(head('One <em>index</em>, every room',
      'A BM25 inverted index over clients, bills, services, the shelf, the diary and the lookbook. ' +
      'Fuzzy matching, prefix matching, facets, filters and microsecond timings \u2014 all in-process, no external engine.',
      '<a class="btn ghost" target="_blank" rel="noopener" href="/api/search/stats">Index stats</a>' +
      '<button class="btn ghost" type="button" id="rebuildBtn">Rebuild the index</button>') +
      '<div class="row" style="grid-template-columns:2fr 1fr auto;margin-bottom:1rem">' +
      '<input id="sQ" type="search" value="' + esc(q) + '" placeholder="Try: balayage, 98145, Meher, C-00007, oxblood, refund\u2026">' +
      '<select id="sKind" aria-label="Restrict to a kind"><option value="">Everything</option>' +
      ['customer', 'invoice', 'service', 'staff', 'product', 'appointment', 'look', 'giftcard'].map(function (k) {
        return '<option value="' + k + '">' + k + '</option>'; }).join('') + '</select>' +
      '<button class="btn solid" type="button" id="sGo">Search</button></div>' +
      '<div id="sBody">' + (q ? loading('Searching') : '<p class="hint">Type at least two characters.</p>') + '</div>');
    $('#sGo').addEventListener('click', run);
    $('#sQ').addEventListener('keydown', function (e) { if (e.key === 'Enter') run(); });
    $('#sKind').addEventListener('change', run);
    $('#rebuildBtn').addEventListener('click', function () {
      api.post('/api/search/rebuild', {}).then(function (r) {
        S.toast('Rebuilt ' + fmt.num(r.documents) + ' documents in ' + r.tookMillis + ' ms', 'ok');
      }).catch(function (err) { S.toast(err.message, 'bad'); });
    });
    function run() {
      var text = $('#sQ').value.trim();
      window.location.hash = '#/search?q=' + encodeURIComponent(text) + ($('#sKind').value ? '&kind=' + $('#sKind').value : '');
      if (!text) return;
      $('#sBody').innerHTML = loading('Searching');
      api.get('/api/search?q=' + encodeURIComponent(text) + '&limit=25' + ($('#sKind').value ? '&kind=' + $('#sKind').value : ''))
        .then(function (r) {
          var telemetry = r.telemetry || {};
          var html = '<div class="between" style="margin-bottom:1rem;gap:1rem;flex-wrap:wrap">' +
            '<p class="hint" style="margin:0"><b>' + fmt.num(r.total) + '</b> result' + (r.total === 1 ? '' : 's') +
            ' in <b>' + (r.tookMicros / 1000).toFixed(2) + ' ms</b> \u00b7 ' + esc(telemetry.engine || '') +
            ' \u00b7 strategy ' + esc(telemetry.strategy || '') + ' \u00b7 ' + fmt.num(telemetry.postings || 0) + ' postings' +
            ' \u00b7 ' + fmt.num(telemetry.vocabulary || 0) + ' terms</p>' +
            (r.didYouMean && r.didYouMean.length ? '<p class="hint" style="margin:0">Did you mean ' +
              r.didYouMean.map(function (d) { return '<button class="link" type="button" data-mean="' + esc(d) + '">' + esc(d) + '</button>'; }).join(', ') + '?</p>' : '') +
            '</div>';
          if ((r.expansions || []).length) html += '<p class="hint">Matched on: ' + r.expansions.map(esc).join(', ') + '</p>';
          html += '<div class="grid g2" style="align-items:start"><div>' +
            ((r.hits || []).length ? '<ul class="list-plain">' + r.hits.map(function (h) {
              return '<li><span class="grow"><b>' + esc(h.title) + '</b>' +
                (h.subtitle ? ' <span class="muted">\u00b7 ' + esc(h.subtitle) + '</span>' : '') + '<br>' +
                '<span class="tiny">' + esc(h.kind) + (h.label ? ' \u00b7 ' + esc(h.label) : '') +
                (h.attrs && h.attrs.phone ? ' \u00b7 ' + esc(h.attrs.phone) : '') +
                (h.attrs && h.attrs.price ? ' \u00b7 ' + fmt.money(h.attrs.price) : '') + '</span>' +
                (h.snippet ? '<br><span style="font-size:.86rem;color:var(--fg-soft)">' + esc(h.snippet) + '</span>' : '') + '</span>' +
                '<span class="num">' + Number(h.score).toFixed(2) + '</span>' +
                '<button class="link" type="button" data-hit="' + esc(h.kind) + ':' + esc(h.id) + '">Open</button></li>';
            }).join('') + '</ul>' : emptyState('Nothing filed under that', '<p>Try a shorter word, a mobile number, or clear the kind filter.</p>'));
          html += '</div><div>';
          var facets = r.facets || {};
          Object.keys(facets).forEach(function (key) {
            var entries = Object.keys(facets[key]).slice(0, 8);
            if (!entries.length) return;
            html += panel(esc(key), '<div class="chip-row">' + entries.map(function (value) {
              return '<button class="chip" type="button" data-facet="' + esc(key) + ':' + esc(value) + '">' +
                esc(value) + ' <span class="mono">' + facets[key][value] + '</span></button>';
            }).join('') + '</div>', '');
          });
          html += panel('How it scores', '<p style="font-size:.9rem;color:var(--fg-soft)">BM25 with k1=' +
            esc(String(telemetry.params ? telemetry.params.k1 : 1.35)) + ', b=' + esc(String(telemetry.params ? telemetry.params.b : 0.72)) +
            '. Field weights: name and title \u00d75, code and mobile \u00d74, email and tags \u00d73, category and chair \u00d72, notes \u00d71. ' +
            'Exact matches score 1.0, prefixes 0.62, fuzzy (one deletion) 0.70 and substrings 0.45.</p>', '');
          html += '</div></div>';
          $('#sBody').innerHTML = html;
          $$('[data-mean]').forEach(function (b) {
            b.addEventListener('click', function () { $('#sQ').value = b.getAttribute('data-mean'); run(); });
          });
          $$('[data-facet]').forEach(function (b) {
            b.addEventListener('click', function () {
              var parts = b.getAttribute('data-facet').split(':');
              $('#sQ').value = $('#sQ').value.trim() + ' ' + parts[1];
              run();
            });
          });
          $$('[data-hit]').forEach(function (b) {
            b.addEventListener('click', function () {
              var parts = b.getAttribute('data-hit').split(':');
              if (parts[0] === 'customer') window.location.hash = '#/clients/' + parts[1];
              else if (parts[0] === 'invoice') window.location.hash = '#/invoices/' + parts[1];
              else if (parts[0] === 'appointment') openAppointment(parts[1]);
              else if (parts[0] === 'product') window.location.hash = '#/inventory';
              else if (parts[0] === 'service') window.location.hash = '#/menu';
              else S.toast('Open it from the relevant room', 'bad');
            });
          });
        }).catch(function (err) { $('#sBody').innerHTML = errorState(err); });
    }
    if (q) run();
  }

  /* ======================================================= notifications */
  function viewOutbox() {
    enter('notifications');
    if (!can(3)) return setView(gate(3, 'The outbox is for reception and above'));
    setCrumb('Outbox');
    setView(head('The <em>outbox</em>', 'Every message the salon would send. Nothing leaves this machine: transmission is a dry run, and each message says so.',
      '<button class="btn ghost" type="button" id="remindBtn">Send today\u2019s reminders</button>' +
      '<button class="btn ghost" type="button" id="tplBtn">Templates</button>') +
      '<div id="outBody">' + loading() + '</div>');
    $('#remindBtn').addEventListener('click', function () {
      api.post('/api/booking/reminders', {}).then(function (r) {
        S.toast((r.queued || r.sent || 0) + ' reminder(s) queued', 'ok');
        refresh();
      }).catch(function (err) { S.toast(err.message, 'bad'); });
    });
    $('#tplBtn').addEventListener('click', function () {
      api.get('/api/notifications/templates').then(function (r) {
        S.modal({ title: 'Message templates', kicker: 'The outbox', body:
          '<p class="hint">Variables in braces are filled from the booking or the bill.</p>' +
          Object.keys(r.templates || {}).map(function (k) {
            return '<details style="border-bottom:1px solid var(--rule);padding:.5rem 0"><summary class="mono" style="font-size:.76rem">' +
              esc(k) + '</summary><p style="margin:.5rem 0 0;font-size:.9rem">' + esc(r.templates[k]) + '</p></details>';
          }).join(''), size: 'wide' });
      });
    });
    Promise.all([api.get('/api/notifications?limit=60'), api.get('/api/notifications/stats')]).then(function (r) {
      var rows = r[0].notifications || [];
      var stats = r[1] || {};
      $('#outBody').innerHTML = kpis(Object.keys(stats).slice(0, 6).map(function (k) {
        return { label: k, value: typeof stats[k] === 'number' ? fmt.num(stats[k]) : esc(String(stats[k])) };
      })) + '<div style="margin-top:1.4rem">' + table(
        [{ label: 'When' }, { label: 'Channel' }, { label: 'To' }, { label: 'Subject' }, { label: 'Template' },
         { label: 'Status' }, { label: '' }],
        rows.map(function (n) {
          return { cells: ['<span class="mono" style="font-size:.74rem">' + esc(fmt.stamp(n.createdAt)) + '</span>',
            esc(n.channel), '<span class="mono" style="font-size:.74rem">' + esc(n.to) + '</span>',
            esc(n.subject || ''), '<span class="tiny">' + esc(n.template || '') + '</span>',
            S.badge(n.status) + (n.dryRun ? ' <span class="badge mut">dry run</span>' : ''),
            '<button class="link" type="button" data-msg="' + esc(n.id) + '">Read</button>'] };
        }), { emptyTitle: 'The outbox is empty' }) + '</div>';
      $$('[data-msg]').forEach(function (b) {
        b.addEventListener('click', function () {
          var n = rows.filter(function (x) { return x.id === b.getAttribute('data-msg'); })[0];
          S.modal({ title: esc(n.subject || 'Message'), kicker: esc(n.channel + ' \u00b7 ' + n.template), body:
            '<p class="mono" style="font-size:.76rem">To ' + esc(n.to) + ' \u00b7 ' + esc(fmt.stamp(n.createdAt)) + '</p>' +
            '<pre style="white-space:pre-wrap;font-family:var(--ui);font-size:.95rem;line-height:1.7;margin-top:.8rem">' + esc(n.body || '') + '</pre>' +
            (n.error ? '<p class="err">' + esc(n.error) + '</p>' : ''),
            actions: [{ label: 'Queue it again', kind: 'ghost', onClick: function () {
              return api.post('/api/notifications/' + encodeURIComponent(n.id) + '/resend', {})
                .then(function () { S.toast('Queued again', 'ok'); refresh(); })
                .catch(function (err) { S.toast(err.message, 'bad'); return false; });
            }}, { label: 'Close', kind: 'solid' }] });
        });
      });
    }).catch(function (err) { $('#outBody').innerHTML = errorState(err); });
  }

  /* ============================================================ audit */
  function viewAudit(query) {
    enter('audit');
    if (!can(4)) return setView(gate(4, 'The audit trail is for managers'));
    setCrumb('Audit trail');
    setView(head('Who did <em>what</em>', 'Every write, every sign-in, every refund, every export \u2014 with the actor, the address and the moment.',
      '<input id="aQ" type="search" placeholder="Search the trail" value="' + esc((query && query.q) || '') + '" style="width:auto">' +
      '<select id="aSev" aria-label="Severity" style="width:auto"><option value="">Any severity</option>' +
      ['INFO', 'WARN', 'CRITICAL'].map(function (s) { return '<option>' + s + '</option>'; }).join('') + '</select>' +
      '<button class="btn ghost" type="button" id="aCsv">Export CSV</button>') +
      '<div id="aBody">' + loading() + '</div>');
    function load() {
      var q = $('#aQ').value.trim();
      var sev = $('#aSev').value;
      api.get('/api/audit?limit=120' + (q ? '&q=' + encodeURIComponent(q) : '') + (sev ? '&severity=' + sev : '')).then(function (r) {
        var meta = r.meta || {};
        $('#aBody').innerHTML = '<p class="hint" style="margin-bottom:1rem">' + fmt.num(meta.count || 0) +
          ' events held (cap ' + fmt.num(meta.cap || 6000) + ') \u00b7 ' + esc(JSON.stringify((meta.summary || {}).bySeverity || {})) + '</p>' +
          table([{ label: 'When' }, { label: 'Severity' }, { label: 'Action' }, { label: 'Actor' },
                 { label: 'Summary' }, { label: 'Entity' }, { label: 'Address' }],
            (r.events || []).map(function (e) {
              return { cells: ['<span class="mono" style="font-size:.72rem">' + esc(fmt.stamp(e.at)) + '</span>',
                S.badge(e.severity, e.severity === 'CRITICAL' ? 'bad' : e.severity === 'WARN' ? 'warn' : 'mut'),
                '<span class="mono" style="font-size:.72rem">' + esc(e.action) + '</span>',
                esc(e.actorName || '') + '<br><span class="tiny">' + esc(e.role || '') + '</span>',
                esc(e.summary || ''),
                '<span class="tiny">' + esc(e.entityKind || '') + (e.entityId ? ' \u00b7 ' + esc(e.entityId) : '') + '</span>',
                '<span class="mono" style="font-size:.7rem">' + esc(e.ip || '') + '</span>'] };
            }), { emptyTitle: 'Nothing in the trail matches' });
      }).catch(function (err) { $('#aBody').innerHTML = errorState(err); });
    }
    $('#aQ').addEventListener('input', S.debounce(load, 300));
    $('#aSev').addEventListener('change', load);
    $('#aCsv').addEventListener('click', function () { api.download('/api/audit.csv', 'sillage-audit.csv'); });
    load();
  }

  /* ============================================================= data */
  function viewData() {
    enter('data');
    if (!can(3)) return setView(gate(3, 'The data room is for managers'));
    setCrumb('Data room');
    setView(head('The <em>data room</em>', 'Exports, snapshots, restores, an integrity check and the honest numbers about disk use.',
      '<button class="btn ghost" type="button" id="bkBtn">Take a snapshot</button>' +
      '<button class="btn ghost" type="button" id="intBtn">Run the integrity check</button>' +
      '<button class="btn danger" type="button" id="purgeBtn">Purge short-lived data</button>') +
      '<div id="dataBody">' + loading() + '</div>');
    function load() {
      Promise.all([api.get('/api/exports'), api.get('/api/storage'), api.get('/api/backups')]).then(function (r) {
        var exports = r[0].exports || [], storage = r[1] || {}, backups = r[2].backups || [];
        $('#dataBody').innerHTML =
          panel('Exports', table([{ label: 'Collection' }, { label: 'Rows', cls: 'r' }, { label: 'Columns', cls: 'r' },
            { label: 'What it holds' }, { label: '' }],
            exports.map(function (e) {
              return { cells: ['<b>' + esc(e.label) + '</b>', '<span class="num">' + fmt.num(e.rows) + '</span>',
                '<span class="num">' + e.columns + '</span>', '<span class="hint">' + esc(e.note) + '</span>',
                '<a class="link" href="' + esc(e.url) + '" download>CSV</a>'] };
            })), '', { flush: true }) +
          '<div class="grid g2" style="margin-top:1.4rem;align-items:start">' +
          panel('Storage', '<dl class="kv">' +
            '<dt>On disk</dt><dd>' + esc(storage.diskHuman || '') + '</dd>' +
            '<dt>Directory</dt><dd class="mono" style="font-size:.72rem;word-break:break-all">' + esc(storage.directory || '') + '</dd>' +
            '<dt>Snapshots</dt><dd>' + fmt.num(storage.snapshots || 0) + '</dd>' +
            '<dt>Indexed</dt><dd>' + fmt.num((storage.searchIndex || {}).documents || 0) + ' documents</dd>' +
            '<dt>Open since</dt><dd>' + esc(fmt.stamp(storage.openedAt)) + '</dd></dl>' +
            '<p class="tiny" style="margin-top:1rem">Collections</p>' +
            '<div style="max-height:16rem;overflow:auto">' + table([{ label: 'File' }, { label: 'Records', cls: 'r' }],
              Object.keys(storage.collections || {}).map(function (k) {
                return { cells: ['<span class="mono" style="font-size:.74rem">' + esc(k) + '</span>', '<span class="num">' + fmt.num(storage.collections[k]) + '</span>'] };
              })) + '</div>', '') +
          panel('Snapshots', backups.length ? '<ul class="list-plain">' + backups.map(function (b) {
            return '<li><span class="grow"><b>' + esc(b.name || b.file) + '</b><br><span class="tiny">' +
              esc(fmt.stamp(b.at || b.createdAt)) + ' \u00b7 ' + esc(b.human || (b.bytes ? Math.round(b.bytes / 1024) + ' KB' : '')) + '</span></span>' +
              (can(5) ? '<button class="link" type="button" data-restore="' + esc(b.name || b.file) + '">Restore</button>' : '') + '</li>';
          }).join('') + '</ul>' : '<p class="muted">No snapshots yet. Take one before you change anything large.</p>',
            '<button class="btn sm ghost" type="button" id="bkBtn2">Snapshot now</button>') +
          '</div>' +
          '<div class="card" style="margin-top:1.4rem"><p class="tiny" style="margin-top:0">Danger zone</p>' +
          '<p style="font-size:.94rem;color:var(--fg-soft)">A reset clears every collection and reseeds the demo salon. ' +
          'It cannot be undone, and it is written to the audit trail with your name on it.</p>' +
          '<button class="btn danger" type="button" id="resetBtn">Wipe and reseed</button></div>';
        $('#bkBtn2').addEventListener('click', snapshot);
        $$('[data-restore]').forEach(function (b) {
          b.addEventListener('click', function () {
            S.confirm({ title: 'Restore this snapshot?', danger: true, confirmLabel: 'Restore it',
              message: 'Every collection is replaced by the snapshot. The search index is rebuilt afterwards.' })
              .then(function (yes) {
                if (!yes) return;
                api.post('/api/restore/' + encodeURIComponent(b.getAttribute('data-restore')), {}).then(function (r) {
                  S.toast(r.message || 'Restored', 'ok');
                  S.bootstrap(true).then(function (nb) { boot = nb; });
                  load();
                }).catch(function (err) { S.toast(err.message, 'bad'); });
              });
          });
        });
        $('#resetBtn').addEventListener('click', function () {
          S.confirm({ title: 'Wipe the salon?', danger: true, confirmLabel: 'Wipe and reseed',
            message: 'Every client, bill, booking, product and audit event is deleted, then the demo salon is written again.' })
            .then(function (yes) {
              if (!yes) return;
              api.post('/api/reset', {}).then(function () {
                S.toast('Reset \u2014 reload the page', 'ok');
                global.setTimeout(function () { window.location.reload(); }, 900);
              }).catch(function (err) { S.toast(err.message, 'bad'); });
            });
        });
      }).catch(function (err) { $('#dataBody').innerHTML = errorState(err); });
    }
    function snapshot() {
      api.post('/api/backup', { label: 'console' }).then(function (r) {
        S.toast('Snapshot ' + (r.snapshot || '') + ' written', 'ok');
        load();
      }).catch(function (err) { S.toast(err.message, 'bad'); });
    }
    $('#bkBtn').addEventListener('click', snapshot);
    $('#intBtn').addEventListener('click', function () {
      api.get('/api/integrity').then(function (r) {
        S.modal({ title: r.clean ? 'The book agrees with itself' : (r.issues || []).length + ' things to look at',
          kicker: 'Integrity check', size: 'wide', body: r.clean
            ? '<p>No orphaned references and every bill balances against its own lines.</p>'
            : '<ul class="list-plain">' + (r.issues || []).map(function (i) {
                return '<li><span class="grow"><b>' + esc(i.kind) + ' \u00b7 ' + esc(i.label) + '</b><br>' +
                  '<span class="hint">' + esc(i.message) + '</span></span></li>'; }).join('') + '</ul>' });
      });
    });
    $('#purgeBtn').addEventListener('click', function () {
      api.post('/api/purge', {}).then(function (r) {
        S.toast('Purged ' + JSON.stringify(r), 'ok'); load();
      }).catch(function (err) { S.toast(err.message, 'bad'); });
    });
    load();
  }

  /* ========================================================= settings */
  function viewSettings() {
    enter('settings');
    if (!can(4)) return setView(gate(4, 'Settings are for managers and the owner'));
    setCrumb('Settings');
    setView(head('The house <em>rules</em>', 'Hours, holidays, tax, loyalty, tenders, message templates, the theme and the accessibility defaults.', '') +
      '<div id="setBody">' + loading() + '</div>');
    api.get('/api/settings').then(function (s) {
      var owner = can(5);
      function field(key, label, type, hint) {
        var value = s[key];
        if (type === 'checkbox') {
          return '<label class="check"><input type="checkbox" data-k="' + key + '"' + (value ? ' checked' : '') +
            (owner ? '' : ' disabled') + '><span>' + esc(label) + '</span></label>';
        }
        return '<div class="field"><label class="lbl" for="set_' + key + '">' + esc(label) + '</label>' +
          '<input id="set_' + key + '" data-k="' + key + '" type="' + (type || 'text') + '" value="' + esc(value === null || value === undefined ? '' : value) + '"' +
          (owner ? '' : ' disabled') + '>' + (hint ? '<span class="hint">' + esc(hint) + '</span>' : '') + '</div>';
      }
      var html = '<div class="grid g2" style="align-items:start">' +
        panel('Identity', field('salonName', 'Salon name') + field('legalName', 'Legal name') +
          field('tagline', 'Tagline') + field('established', 'Established', 'number') +
          '<div class="field"><label class="lbl" for="set_about">The house, in a paragraph</label>' +
          '<textarea id="set_about" data-k="about"' + (owner ? '' : ' disabled') + '>' + esc(s.about || '') + '</textarea></div>', '') +
        panel('Address &amp; contact', field('addressLine', 'Address') + field('city', 'City') +
          field('state', 'State') + field('pincode', 'Pincode') + field('phone', 'Telephone') +
          field('whatsapp', 'WhatsApp') + field('email', 'Email') + field('website', 'Website') +
          field('gstin', 'GSTIN'), '') +
        panel('Money &amp; tax', field('currency', 'Currency') + field('currencySymbol', 'Symbol') +
          field('defaultTaxPct', 'Default tax %', 'number') + field('taxLabel', 'Tax label') +
          '<div class="field"><label class="lbl" for="set_taxSplit">Split</label><select id="set_taxSplit" data-k="taxSplit"' + (owner ? '' : ' disabled') + '>' +
          ['CGST_SGST', 'IGST', 'SINGLE'].map(function (v) { return '<option' + (s.taxSplit === v ? ' selected' : '') + '>' + v + '</option>'; }).join('') + '</select></div>' +
          field('pricesInclusiveOfTax', 'Prices include tax', 'checkbox') +
          field('invoicePrefix', 'Invoice prefix') + field('roundTo', 'Round to', 'number') +
          field('autoRound', 'Round the total', 'checkbox') +
          '<p class="hint">Next invoice number: <b class="mono">' + esc(s.invoicePreview || '') + '</b> \u00b7 fiscal year ' + esc(s.fiscalYear || '') + '</p>', '') +
        panel('The diary', field('slotMinutes', 'Slot minutes', 'number') + field('bufferMinutes', 'Buffer minutes', 'number') +
          field('minAdvanceMinutes', 'Minimum advance (minutes)', 'number') + field('maxAdvanceDays', 'How far ahead (days)', 'number') +
          field('cancellationHours', 'Free cancellation window (hours)', 'number') + field('graceMinutes', 'Grace minutes', 'number') +
          field('allowWalkIns', 'Walk-ins allowed', 'checkbox') + field('allowGuestCheckout', 'Guest checkout allowed', 'checkbox') +
          field('requireDeposit', 'Require a deposit', 'checkbox') + field('depositPct', 'Deposit %', 'number'), '') +
        panel('Loyalty', field('loyaltyEnabled', 'Loyalty on', 'checkbox') +
          field('loyaltyPointsPerHundred', 'Points per \u20B9100', 'number') +
          field('loyaltyPointValue', 'Value of a point', 'number', 'In rupees') +
          field('loyaltyExpiryMonths', 'Points expire after (months)', 'number') +
          '<p class="tiny">Tiers</p>' + table([{ label: 'Tier' }, { label: 'From', cls: 'r' }, { label: '\u00d7', cls: 'r' }, { label: 'Perk' }],
            (s.loyaltyTiers || []).map(function (t) {
              return { cells: ['<b>' + esc(t.name) + '</b>', '<span class="num">' + fmt.money(t.minSpend) + '</span>',
                '<span class="num">' + t.pointsMultiplier + '</span>', esc(t.perk || '')] };
            })) + '<p class="hint">Tiers are edited in the settings file or through the API; the thresholds above apply automatically.</p>', '') +
        panel('Payments', field('gatewayName', 'Gateway name') +
          '<div class="field"><label class="lbl" for="set_gwMode">Mode</label><select id="set_gwMode" data-k="gatewayMode"' + (owner ? '' : ' disabled') + '>' +
          ['TEST', 'LIVE'].map(function (v) { return '<option' + (s.gatewayMode === v ? ' selected' : '') + '>' + v + '</option>'; }).join('') + '</select>' +
          '<span class="hint">LIVE mode is refused while the gateway is simulated.</span></div>' +
          field('cardEnabled', 'Cards', 'checkbox') + field('upiEnabled', 'UPI', 'checkbox') +
          field('netbankingEnabled', 'Netbanking', 'checkbox') + field('walletEnabled', 'Wallets', 'checkbox') +
          field('cashEnabled', 'Cash', 'checkbox') + field('giftCardEnabled', 'Gift cards', 'checkbox') +
          field('intentTtlMinutes', 'Intent lifetime (minutes)', 'number') + field('otpLength', 'OTP length', 'number') +
          field('simulateGatewayFailures', 'Simulate occasional declines', 'checkbox'), '') +
        panel('Messages', field('notificationsEnabled', 'Outbox on', 'checkbox') +
          field('notificationSenderId', 'Sender id') +
          '<p class="tiny">Templates</p>' +
          Object.keys(s.templates || {}).map(function (k) {
            return '<div class="field"><label class="lbl" for="tpl_' + k + '">' + esc(k) + '</label>' +
              '<textarea id="tpl_' + k + '" data-tpl="' + esc(k) + '"' + (owner ? '' : ' disabled') + '>' + esc(s.templates[k]) + '</textarea></div>';
          }).join(''), '') +
        panel('The look of the place', '<p class="hint">These are the defaults offered to every visitor; each person can override them on their own device.</p>' +
          '<pre class="mono" style="font-size:.72rem;background:var(--sunken);border:1px solid var(--rule);padding:.7rem;overflow:auto">' +
          esc(JSON.stringify({ theme: s.theme, accessibility: s.accessibility }, null, 2)) + '</pre>', '') +
        panel('Feature flags', Object.keys(s.features || {}).map(function (k) {
          return '<label class="check"><input type="checkbox" data-feature="' + esc(k) + '"' + (s.features[k] ? ' checked' : '') +
            (owner ? '' : ' disabled') + '><span class="mono" style="font-size:.8rem">' + esc(k) + '</span></label>';
        }).join(''), '') +
        '</div>' +
        (owner ? '<div class="btn-row" style="margin-top:1.4rem"><button class="btn solid" type="button" id="saveSet">Save the house rules</button>' +
          '<span class="hint">Every change is written to the audit trail with your name.</span></div>'
          : '<p class="hint" style="margin-top:1.4rem">Read-only: only the owner may change the house rules.</p>');
      $('#setBody').innerHTML = html;
      var save = $('#saveSet');
      if (save) save.addEventListener('click', function () {
        var patch = {};
        $$('#setBody [data-k]').forEach(function (input) {
          var key = input.getAttribute('data-k');
          var value = input.type === 'checkbox' ? input.checked
            : (input.type === 'number' ? Number(input.value || 0) : input.value);
          patch[key] = value;
        });
        var templates = {};
        $$('#setBody [data-tpl]').forEach(function (input) { templates[input.getAttribute('data-tpl')] = input.value; });
        if (Object.keys(templates).length) patch.templates = templates;
        var features = {};
        $$('#setBody [data-feature]').forEach(function (input) { features[input.getAttribute('data-feature')] = input.checked; });
        patch.features = features;
        api.patch('/api/settings', patch).then(function () {
          S.toast('House rules saved', 'ok');
          S.bootstrap(true).then(function (b) { boot = b; paintOpenBadge(); });
          refresh();
        }).catch(function (err) { S.toast((err.details || []).join(' \u00b7 ') || err.message, 'bad'); });
      });
    }).catch(function (err) { $('#setBody').innerHTML = errorState(err); });
  }

  /* ========================================================= accounts */
  function viewAccounts() {
    enter('accounts');
    if (!can(5)) return setView(gate(5, 'Accounts are for the owner'));
    setCrumb('Accounts');
    setView(head('Who may <em>sign in</em>', 'Roles decide what a person can see. Nothing here stores a password \u2014 only a PBKDF2 hash and a failure count.',
      '<button class="btn solid" type="button" id="accBtn">Add a staff account</button>') +
      '<div id="accBody">' + loading() + '</div>');
    $('#accBtn').addEventListener('click', function () {
      var body = document.createElement('div');
      body.innerHTML = '<div class="row"><div class="field"><label class="lbl" for="aUser">Username</label><input id="aUser"></div>' +
        '<div class="field"><label class="lbl" for="aName">Display name</label><input id="aName"></div></div>' +
        '<div class="row"><div class="field"><label class="lbl" for="aEmail">Email</label><input id="aEmail" type="email"></div>' +
        '<div class="field"><label class="lbl" for="aPhone">Mobile</label><input id="aPhone" type="tel"></div></div>' +
        '<div class="row"><div class="field"><label class="lbl" for="aRole">Role</label><select id="aRole">' +
        ['RECEPTION', 'STYLIST', 'MANAGER'].map(function (r) { return '<option>' + r + '</option>'; }).join('') + '</select></div>' +
        '<div class="field"><label class="lbl" for="aStaff">Chair</label><select id="aStaff"><option value="">None</option>' +
        (boot.staff || []).map(function (m) { return '<option value="' + esc(m.id) + '">' + esc(m.name) + '</option>'; }).join('') + '</select></div></div>' +
        '<p class="hint">A temporary password is generated and shown once.</p><p class="err" id="aErr"></p>';
      S.modal({ title: 'Add a staff account', kicker: 'Access', body: body, actions: [
        { label: 'Create it', kind: 'solid', onClick: function (root) {
          return api.post('/api/accounts/staff', {
            username: $('#aUser', root).value.trim(), displayName: $('#aName', root).value.trim(),
            email: $('#aEmail', root).value.trim(), phone: $('#aPhone', root).value.trim(),
            role: $('#aRole', root).value, staffId: $('#aStaff', root).value || null
          }).then(function (r) {
            S.modal({ title: 'Give them this once', kicker: 'Temporary password', body:
              '<p class="seal" style="font-size:1.2rem">' + esc(r.temporaryPassword || r.password || '') + '</p>' +
              '<p class="hint" style="margin-top:.8rem">' + esc(r.message || 'They will be asked to change it at the first sign-in.') + '</p>' });
            refresh();
          }).catch(function (err) { $('#aErr', root).textContent = (err.details || []).join(' \u00b7 ') || err.message; return false; });
        }}, { label: 'Cancel', kind: 'ghost' }] });
    });
    api.get('/api/accounts').then(function (r) {
      var rows = r.accounts || [];
      $('#accBody').innerHTML = table(
        [{ label: 'Person' }, { label: 'Username' }, { label: 'Role' }, { label: 'Chair' }, { label: 'Last seen' },
         { label: 'State' }, { label: '' }],
        rows.map(function (a) {
          return { cells: ['<b>' + esc(a.displayName || a.username) + '</b><br><span class="tiny">' + esc(a.email || '') + '</span>',
            '<span class="mono" style="font-size:.76rem">' + esc(a.username) + '</span>',
            S.badge(a.roleLabel || a.role, a.role === 'OWNER' ? 'gold' : 'mut'),
            esc(a.staffName || '\u2014'),
            a.lastLoginAt ? esc(fmt.relative(a.lastLoginAt)) : '<span class="muted">never</span>',
            a.active ? (a.locked ? '<span class="badge bad">locked</span>' : '<span class="badge ok">active</span>') : '<span class="badge mut">disabled</span>',
            '<span class="btn-row">' +
            '<button class="link" type="button" data-role="' + esc(a.id) + ':' + esc(a.role) + '">Role</button>' +
            '<button class="link" type="button" data-active="' + esc(a.id) + ':' + (a.active ? '1' : '0') + '">' + (a.active ? 'Disable' : 'Enable') + '</button>' +
            '</span>'] };
        }), { emptyTitle: 'No accounts' });
      $$('[data-role]').forEach(function (b) {
        b.addEventListener('click', function () {
          var parts = b.getAttribute('data-role').split(':');
          S.modal({ title: 'Change the role', kicker: 'Access', body:
            '<p class="hint">OWNER sees everything including settings and the audit trail. MANAGER adds refunds and exports. ' +
            'RECEPTION runs the desk. STYLIST sees their own chair and the clients in it. CLIENT sees only their own record.</p>' +
            '<select id="roleSel" class="input">' + ['OWNER', 'MANAGER', 'RECEPTION', 'STYLIST', 'CLIENT'].map(function (r) {
              return '<option' + (r === parts[1] ? ' selected' : '') + '>' + r + '</option>'; }).join('') + '</select>',
            actions: [{ label: 'Save', kind: 'solid', onClick: function (root) {
              return api.patch('/api/accounts/' + encodeURIComponent(parts[0]) + '/role', { role: $('#roleSel', root).value })
                .then(function () { S.toast('Role changed', 'ok'); refresh(); })
                .catch(function (err) { S.toast(err.message, 'bad'); return false; });
            }}] });
        });
      });
      $$('[data-active]').forEach(function (b) {
        b.addEventListener('click', function () {
          var parts = b.getAttribute('data-active').split(':');
          api.patch('/api/accounts/' + encodeURIComponent(parts[0]) + '/active', { active: parts[1] !== '1' })
            .then(function () { S.toast('Updated', 'ok'); refresh(); })
            .catch(function (err) { S.toast(err.message, 'bad'); });
        });
      });
    }).catch(function (err) { $('#accBody').innerHTML = errorState(err); });
  }

  /* ========================================================== profile */
  function viewProfile() {
    enter('profile');
    if (!me || !me.authenticated) {
      setCrumb('Your record');
      return setView(head('Your <em>record</em>', 'Sign in to keep your bookings, bills, points and reading preferences in one place \u2014 or carry on as a guest.') +
        gate(1, 'Sign in to see your record'));
    }
    setCrumb('Your record', me.displayName || '');
    setView('<div id="profBody">' + loading() + '</div>');
    api.get('/api/profile').then(function (p) {
      var account = p.account || {};
      var record = p.record || null;
      var loyalty = p.loyalty || {};
      var html = head(esc(account.displayName || me.displayName || 'Your record'),
        esc(account.username || '') + ' \u00b7 ' + esc(me.roleLabel || '') + ' \u00b7 signed in since ' + esc(fmt.relative(account.createdAt)),
        '<button class="btn ghost" type="button" id="pwBtn">Change password</button>' +
        '<button class="btn ghost" type="button" id="a11yBtn">Reading &amp; access</button>' +
        '<button class="btn danger" type="button" id="outBtn">Sign out everywhere</button>') +
        '<div class="desk-cols">' +
        panel('Preferences', '<p class="hint">Stored on this device, and on the server while you are signed in.</p>' +
          '<dl class="kv" style="margin-top:.8rem">' +
          Object.keys(S.prefs).map(function (k) {
            return '<dt>' + esc(k) + '</dt><dd class="mono">' + esc(String(S.prefs[k])) + '</dd>'; }).join('') + '</dl>' +
          '<button class="btn sm ghost" type="button" id="a11yBtn2" style="margin-top:1rem">Open the panel</button>', '') +
        (record ? panel('Your client record', '<dl class="kv">' +
          '<dt>Name</dt><dd>' + esc((record.display || {}).firstName || '') + ' ' + esc((record.display || {}).lastName || '') + '</dd>' +
          '<dt>Mobile</dt><dd class="mono">' + esc((record.display || {}).phone || '') + '</dd>' +
          '<dt>Email</dt><dd>' + esc((record.display || {}).email || '') + '</dd>' +
          '<dt>Tier</dt><dd>' + esc(loyalty.tier || '') + '</dd>' +
          '<dt>Points</dt><dd>' + fmt.num(loyalty.points || 0) + ' \u00b7 ' + fmt.money(loyalty.value || 0) + '</dd>' +
          '<dt>Visits</dt><dd>' + fmt.num((record.stats || {}).visits || 0) + '</dd>' +
          '<dt>Lifetime</dt><dd>' + fmt.money((record.stats || {}).lifetimeValue || 0) + '</dd></dl>' +
          '<div class="btn-row" style="margin-top:1rem">' +
          '<button class="btn sm ghost" type="button" id="expBtn">Download my data</button>' +
          '<button class="btn sm danger" type="button" id="eraseBtn">Erase my record</button></div>', '')
          : panel('No client record', '<p class="hint">You are signed in but not linked to a client file. ' +
            'Ask the desk to link you with a six-character code, or book once with the same mobile number.</p>' +
            '<div class="row" style="margin-top:.8rem"><div class="field"><label class="lbl" for="lkPhone">Mobile on the record</label>' +
            '<input id="lkPhone" type="tel"></div><div class="field"><label class="lbl" for="lkCode">Desk code</label><input id="lkCode"></div></div>' +
            '<button class="btn sm ghost" type="button" id="lkBtn">Link the record</button>' +
            '<p class="hint" id="lkMsg" role="status" style="margin-top:.6rem"></p>', '')) +
        panel('Bookings', (p.appointments || []).length ? '<ul class="list-plain">' + p.appointments.map(function (a) {
          return '<li><span class="grow"><b>' + esc(fmt.stamp(a.start)) + '</b><br><span class="tiny">' +
            esc((a.services || []).join(', ')) + ' \u00b7 ' + esc(a.staff || '') + '</span></span>' +
            S.badge(a.statusLabel || a.status) + '</li>'; }).join('') + '</ul>' : '<p class="muted">Nothing booked yet.</p>',
          '<a class="btn sm ghost" href="/book" target="_blank" rel="noopener">Book a chair</a>') +
        panel('Bills', (p.invoices || []).length ? '<ul class="list-plain">' + p.invoices.slice(0, 10).map(function (i) {
          return '<li><span class="grow"><span class="mono" style="font-size:.76rem">' + esc(i.number) + '</span><br>' +
            '<span class="tiny">' + esc(fmt.date(i.createdAt)) + '</span></span>' +
            '<span class="num">' + fmt.money(i.total) + '</span>' +
            '<a class="link" target="_blank" rel="noopener" href="' + esc(i.receipt ? i.receipt.html : '#') + '">Receipt</a></li>';
        }).join('') + '</ul>' : '<p class="muted">No bills yet.</p>', '') +
        panel('Sessions', '<div id="sessHost">' + loading() + '</div>',
          '<button class="btn sm ghost" type="button" id="revokeAll">Sign out everywhere</button>') +
        '</div>';
      $('#profBody').innerHTML = html;
      $('#a11yBtn').addEventListener('click', function () { S.a11y(); });
      $('#a11yBtn2').addEventListener('click', function () { S.a11y(); });
      $('#outBtn').addEventListener('click', signOut);
      $('#pwBtn').addEventListener('click', function () {
        var body = document.createElement('div');
        body.innerHTML = '<div class="field"><label class="lbl" for="pwCur">Current password</label><input id="pwCur" type="password"></div>' +
          '<div class="field"><label class="lbl" for="pwNew">New password</label><input id="pwNew" type="password"></div>' +
          '<div class="field"><label class="lbl" for="pwAgain">Again</label><input id="pwAgain" type="password"></div>' +
          '<p class="err" id="pwErr" role="alert"></p>';
        S.modal({ title: 'Change password', kicker: 'Security', body: body, actions: [
          { label: 'Change it', kind: 'solid', onClick: function (root) {
            if ($('#pwNew', root).value !== $('#pwAgain', root).value) { $('#pwErr', root).textContent = 'The two do not match'; return false; }
            return api.post('/api/auth/password', { current: $('#pwCur', root).value, next: $('#pwNew', root).value })
              .then(function () { S.toast('Password changed \u2014 other sessions were signed out', 'ok'); })
              .catch(function (err) { $('#pwErr', root).textContent = (err.details || []).join(' \u00b7 ') || err.message; return false; });
          }}, { label: 'Cancel', kind: 'ghost' }] });
      });
      var exp = $('#expBtn');
      if (exp) exp.addEventListener('click', function () {
        api.get('/api/customers/' + encodeURIComponent(record.customer.id) + '/export').then(function (subject) {
          var blob = new Blob([JSON.stringify(subject, null, 2)], { type: 'application/json' });
          var url = URL.createObjectURL(blob);
          var a = document.createElement('a'); a.href = url; a.download = 'my-sillage-record.json';
          document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
          S.toast('Downloaded', 'ok');
        }).catch(function (err) { S.toast(err.message, 'bad'); });
      });
      var erase = $('#eraseBtn');
      if (erase) erase.addEventListener('click', function () {
        S.confirm({ title: 'Erase your record?', danger: true, confirmLabel: 'Erase it',
          message: 'Your name, mobile, email, birthday, address, notes and tags are removed. Bills are kept because tax law requires them, with you shown as "erased on request". You will be signed out.' })
          .then(function (yes) {
            if (!yes) return;
            api.post('/api/customers/' + encodeURIComponent(record.customer.id) + '/erase', {}).then(function () {
              S.toast('Erased', 'ok'); signOut();
            }).catch(function (err) { S.toast(err.message, 'bad'); });
          });
      });
      var lk = $('#lkBtn');
      if (lk) lk.addEventListener('click', function () {
        api.post('/api/auth/link-customer', { phone: $('#lkPhone').value.trim(), code: $('#lkCode').value.trim() })
          .then(function (r) {
            $('#lkMsg').innerHTML = r.codeRequired
              ? esc(r.message || 'Enter the code the desk reads out.') + (r.devCode ? ' <b class="mono">dev code: ' + esc(r.devCode) + '</b>' : '')
              : esc(r.message || 'Linked');
            if (r.linked) global.setTimeout(function () { window.location.reload(); }, 900);
          }).catch(function (err) { $('#lkMsg').textContent = err.message; });
      });
      $('#revokeAll').addEventListener('click', function () {
        api.post('/api/auth/sessions/revoke-all', {}).then(function (r) {
          S.toast(r.message || 'Signed out everywhere', 'ok');
          global.setTimeout(function () { window.location.reload(); }, 800);
        });
      });
      api.get('/api/auth/sessions').then(function (r) {
        var host = $('#sessHost');
        if (!host) return;
        host.innerHTML = (r.sessions || []).length ? '<ul class="list-plain">' + r.sessions.map(function (s) {
          return '<li><span class="grow"><b>' + esc(s.device || s.kind || 'session') + '</b>' +
            (s.current ? ' <span class="badge ok">this device</span>' : '') + '<br>' +
            '<span class="tiny">' + esc(s.ip || '') + ' \u00b7 seen ' + esc(fmt.relative(s.lastSeenAt)) +
            ' \u00b7 expires ' + esc(fmt.relative(s.expiresAt)) + '</span></span>' +
            (s.current ? '' : '<button class="link" type="button" data-kill="' + esc(s.id) + '">Sign out</button>') + '</li>';
        }).join('') + '</ul>' : '<p class="muted">No sessions.</p>';
        $$('[data-kill]', host).forEach(function (b) {
          b.addEventListener('click', function () {
            api.del('/api/auth/sessions/' + encodeURIComponent(b.getAttribute('data-kill')))
              .then(function () { S.toast('That session was signed out', 'ok'); refresh(); });
          });
        });
      }).catch(function () {});
    }).catch(function (err) { $('#profBody').innerHTML = errorState(err); });
  }

  /* ========================================================== privacy */
  function viewPrivacy() {
    enter('privacy');
    setCrumb('Privacy');
    var salon = (boot && boot.salon) || {};
    setView(head('How your data is <em>kept</em>',
      'Plainly, because a salon is not a data broker. This page describes what this program stores, why, and what you can ask for.') +
      '<div class="prose">' +
      '<h3>What is stored</h3>' +
      '<table><thead><tr><th>Thing</th><th>Why</th><th>How long</th></tr></thead><tbody>' +
      [['Your name and mobile number', 'To find your record at the desk and to send a reminder', 'While the record is open'],
       ['Your email', 'Only if you give it, for receipts and the lookbook', 'Until you remove it'],
       ['Your birthday', 'One message a year, if you said yes', 'Until you remove it'],
       ['Services you had, and when', 'So the next stylist can read the history', 'Indefinitely while the record is open'],
       ['Bills and payments', 'Tax law requires it', 'Eight years'],
       ['Card numbers', 'Never. A token, the brand and the last four digits only', '\u2014'],
       ['CVV numbers', 'Never, under any circumstances', '\u2014'],
       ['Your password', 'As a PBKDF2-HMAC-SHA256 hash with a per-account salt and 210,000 rounds', 'Until the account is closed'],
       ['Sign-in addresses', 'So you can see and revoke your own sessions', 'Fourteen days'],
       ['Who changed what', 'The audit trail, so a mistake can be traced', '6,000 events, oldest first']].map(function (row) {
        return '<tr><td><b>' + esc(row[0]) + '</b></td><td>' + esc(row[1]) + '</td><td>' + esc(row[2]) + '</td></tr>';
      }).join('') + '</tbody></table>' +
      '<h3>What you can ask for</h3>' +
      '<ul>' +
      '<li><b>A copy.</b> One click in <a href="#/profile">your record</a>, or at the desk. You get every field we hold, as JSON.</li>' +
      '<li><b>A correction.</b> Tell the desk, or edit it yourself while signed in.</li>' +
      '<li><b>An erasure.</b> Your personal record is anonymised and archived. Bills survive, because the law requires them, with the client shown as \u201Cerased on request\u201D. The erasure itself is written permanently to the audit trail, with a receipt.</li>' +
      '<li><b>A port.</b> The same export, as CSV, for any collection \u2014 available from the data room.</li>' +
      '</ul>' +
      '<h3>Where it lives</h3>' +
      '<p>On the machine that runs this program, in a folder of JSON files written atomically ' +
      '(write to a temporary file, then rename, so a crash cannot leave a half-written record). ' +
      'Snapshots go to a sibling folder. Nothing is sent to a third party: the message outbox is a dry run, ' +
      'and the payment gateway is simulated in-process.</p>' +
      '<h3>Security</h3>' +
      '<p>Sessions are opaque 32-byte tokens, stored as their SHA-256 digest; the token itself is only ever in your cookie. ' +
      'A CSRF token is issued alongside it and required on every state-changing request. Rate limits apply to sign-in, ' +
      'registration, payment, search, booking and export. Passwords are locked after five failures for fifteen minutes. ' +
      'Receipts carry an HMAC seal so a printed bill can be verified against the book.</p>' +
      '<h3>The suggestions are not a model</h3>' +
      '<p>The engine that suggests a next service, a bundle or a reorder is a set of published rules over counts: ' +
      'market-basket support, confidence and lift; a first-order Markov chain over service sequences; median rebooking ' +
      'intervals; and thresholds. Nothing is trained, nothing is inferred about you as a person, and every card shows ' +
      'the rule that produced it and the numbers it used. <a href="/api/meta/engine" target="_blank" rel="noopener">Read the rulebook</a>.</p>' +
      '<h3>Contact</h3>' +
      '<p>' + esc(salon.legalName || salon.salonName || 'SILLAGE') + ', ' + esc([salon.addressLine, salon.city, salon.pincode].filter(Boolean).join(', ')) +
      ' \u00b7 ' + esc(salon.phone || '') + ' \u00b7 ' + esc(salon.email || '') + '</p>' +
      '</div>');
  }

  /* ------------------------------------------------------------ refresh */
  function refresh() {
    if (router) router.go();
    else window.location.reload();
  }
  S.onRefresh(refresh);

  var global = window;
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
}());
