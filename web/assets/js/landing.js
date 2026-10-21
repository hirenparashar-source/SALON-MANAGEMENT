/* ============================================================================
   SILLAGE — landing page behaviour.
   Renders the index of services, the chairs, the lookbook, membership,
   testimonials, hours and the live search over the whole house catalogue.
   ========================================================================== */
(function () {
  'use strict';
  var S = window.Sillage;
  var api = S.api, fmt = S.fmt;
  var data = null;
  var activeCategory = null;

  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  /* ---------------------------------------------------------------- boot */
  function boot() {
    $('#btnAccess').addEventListener('click', function () { S.a11y(); });
    $('#btnAccessFoot').addEventListener('click', function () { S.a11y(); });
    $('#linkErasure').addEventListener('click', function (e) {
      e.preventDefault();
      S.modal({
        title: 'Ask for an erasure', kicker: 'Your record',
        body: '<p style="max-width:52ch">Two ways, both honoured the same day.</p>' +
          '<ol style="line-height:1.9;padding-left:1.2rem"><li>Sign in to the studio console and open ' +
          '<b>Profile &rarr; Your record &rarr; Erase</b>. The screen shows exactly what will be deleted first.</li>' +
          '<li>Or ask at the desk. We will read you the record, note the request in the audit log, and ' +
          'anonymise the client file while keeping the invoices the tax rules require.</li></ol>' +
          '<p class="hint">An erasure is recorded permanently in the audit trail, with a receipt you can keep.</p>',
        actions: [{ label: 'Go to the console', kind: 'solid', onClick: function () { window.location.href = '/studio#/profile'; } },
                  { label: 'Close', kind: 'ghost' }]
      });
    });
    $('#finder').addEventListener('submit', function (e) {
      e.preventDefault();
      runSearch($('#finderInput').value, false);
    });
    $('#finderInput').addEventListener('input', S.debounce(function (e) {
      runSearch(e.target.value, true);
    }, 180));
    $('#finderInput').addEventListener('keydown', function (e) {
      var box = $('#finderResults');
      if (box.classList.contains('hide')) return;
      var items = $$('a, button.suggest', box);
      var index = items.findIndex(function (n) { return n.classList.contains('sel'); });
      if (e.key === 'ArrowDown') { e.preventDefault(); move(items, index + 1); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); move(items, index - 1); }
      else if (e.key === 'Escape') { hideResults(); }
    });
    document.addEventListener('click', function (e) {
      if (!e.target.closest || !e.target.closest('#finder')) hideResults();
    });
    $('#verifyForm').addEventListener('submit', verifyReceipt);
    $('#quoteForm').addEventListener('submit', sendQuote);

    S.keys({ b: function () { window.location.href = '/book'; }, s: function () { window.location.href = '/studio'; } });

    S.bootstrap().then(function (bootstrap) {
      data = bootstrap;
      renderAll(bootstrap);
    }).catch(function (err) {
      $('#openNow').textContent = 'The index is loading slowly \u2014 call the desk on +91 98765 43210';
      S.toast(err.message || 'Could not reach the salon', 'bad', 'Offline');
    });
    S.reveal();
    window.addEventListener('scroll', S.debounce(function () { S.reveal(); }, 400));
  }

  function move(items, index) {
    items.forEach(function (n) { n.classList.remove('sel'); });
    var next = items[(index + items.length) % items.length];
    if (next) { next.classList.add('sel'); next.scrollIntoView({ block: 'nearest' }); }
  }
  function hideResults() { $('#finderResults').classList.add('hide'); }

  /* ------------------------------------------------------------ rendering */
  function renderAll(b) {
    var salon = b.salon || {};
    renderOpen(b.open, salon);
    renderFacts(b);
    renderMenu(b.menu || {});
    renderChairs(b.staff || []);
    renderLooks(b.looks || []);
    renderTiers(b.loyalty || {});
    renderQuotes(b.testimonials || []);
    renderHours(b.salon || {});
    renderPolicies(b.salon || {});
    $('#colophonContact').textContent = [salon.phone, salon.email].filter(Boolean).join('  ·  ');
    $('#colophonEngine').textContent = 'Search: ' + ((b.search && b.search.engine) || 'sillage inverted index / BM25') +
      ' · Suggestions: ' + (b.engine || 'deterministic rule engine');
    var studio = $('#btnStudio');
    if (b.session && b.session.authenticated && b.session.role !== 'GUEST') {
      studio.textContent = 'Console · ' + (b.session.displayName || b.session.roleLabel || '');
    } else if (b.session && b.session.role === 'GUEST' && b.session.authenticated) {
      studio.textContent = 'Studio';
    }
  }

  function renderOpen(open, salon) {
    var node = $('#openNow');
    if (!open) { node.textContent = ''; return; }
    node.innerHTML = '<span class="badge ' + (open.open ? 'ok' : 'mut') + '">' +
      fmt.esc(open.open ? 'Open now' : 'Closed') + '</span> <span class="mono" style="margin-left:.4rem">' +
      fmt.esc(open.detail || open.until || '') + '</span>';
    var facts = salon.hours ? null : null;
    void facts;
  }

  function renderFacts(b) {
    var menu = b.menu || {};
    var services = menu.services || [];
    $('#factServices').textContent = services.length + ' on the index';
    $('#factChairs').textContent = (b.staff || []).length + ' stylists';
    var next = (b.open && b.open.nextSlot) || null;
    $('#factNext').textContent = next ? fmt.time(next) : (b.open && b.open.open ? 'walk in today' : 'tomorrow morning');
  }

  function renderMenu(menu) {
    var cats = menu.categories || [];
    var services = menu.services || [];
    var filters = $('#menuFilters');
    filters.innerHTML = '';
    var all = document.createElement('button');
    all.type = 'button'; all.className = 'chip on'; all.textContent = 'Everything';
    all.setAttribute('aria-pressed', 'true');
    all.addEventListener('click', function () { setCategory(null); });
    filters.appendChild(all);
    cats.forEach(function (cat) {
      var chip = document.createElement('button');
      chip.type = 'button'; chip.className = 'chip'; chip.textContent = cat.name;
      chip.setAttribute('data-id', cat.id);
      chip.setAttribute('aria-pressed', 'false');
      chip.addEventListener('click', function () { setCategory(cat.id); });
      filters.appendChild(chip);
    });

    function setCategory(id) {
      activeCategory = id;
      $$('.chip', filters).forEach(function (chip) {
        var on = id ? chip.getAttribute('data-id') === id : !chip.hasAttribute('data-id');
        chip.classList.toggle('on', !!on);
        chip.setAttribute('aria-pressed', String(!!on));
      });
      paint();
      S.announce(id ? 'Filtered to ' + (cats.filter(function (c) { return c.id === id; })[0] || {}).name : 'Showing the whole index');
    }

    function paint() {
      var host = $('#menuCols');
      host.innerHTML = '';
      var shown = cats.filter(function (c) { return !activeCategory || c.id === activeCategory; });
      shown.forEach(function (cat) {
        var items = services.filter(function (s) { return s.categoryId === cat.id; });
        if (!items.length) return;
        var block = document.createElement('section');
        block.className = 'menu-cat';
        block.innerHTML = '<h3>' + fmt.esc(cat.name) + ' <span class="count">' + items.length +
          (items.length === 1 ? ' service' : ' services') + '</span></h3>' +
          (cat.blurb ? '<p class="blurb">' + fmt.esc(cat.blurb) + '</p>' : '');
        items.forEach(function (item) {
          var dish = document.createElement('article');
          dish.className = 'dish';
          var variants = item.variants || [];
          dish.innerHTML =
            '<div class="dish-head">' +
              '<span class="dish-name"><button type="button" data-id="' + fmt.esc(item.id) + '">' + fmt.esc(item.name) + '</button></span>' +
              '<span class="dots" aria-hidden="true"></span>' +
              '<span class="dish-price">' + fmt.money(item.price) + (variants.length ? '<span class="dish-tag"> +</span>' : '') + '</span>' +
            '</div>' +
            '<div class="dish-meta">' +
              '<span>' + fmt.duration(item.durationMinutes) + '</span>' +
              (item.rating > 0 ? '<span class="star">\u2605 ' + Number(item.rating).toFixed(1) + '</span>' : '') +
              (item.timesBooked ? '<span>' + fmt.num(item.timesBooked) + ' times booked</span>' : '') +
              (item.featured ? '<span>house favourite</span>' : '') +
              (!item.onlineBookable ? '<span>desk only</span>' : '') +
            '</div>' +
            (item.tagline ? '<p class="dish-note">' + fmt.esc(item.tagline) + '</p>' : '');
          dish.querySelector('button').addEventListener('click', function () { openService(item, cats); });
          block.appendChild(dish);
        });
        host.appendChild(block);
      });
      if (!host.children.length) host.innerHTML = '<p class="muted">Nothing in this room yet.</p>';
    }
    paint();
  }

  function openService(item, cats) {
    var cat = (cats || []).filter(function (c) { return c.id === item.categoryId; })[0] || {};
    var variants = item.variants || [];
    var body = document.createElement('div');
    body.innerHTML =
      '<p class="tiny" style="margin-top:0">' + fmt.esc(cat.name || 'Service') + ' · ' + fmt.esc(item.code || '') + '</p>' +
      (item.editorial ? '<p style="font-family:var(--display);font-size:1.16rem;line-height:1.5;font-style:italic">' + fmt.esc(item.editorial) + '</p>' : '') +
      '<p style="color:var(--fg-soft)">' + fmt.esc(item.description || item.tagline || '') + '</p>' +
      '<dl class="kv" style="margin:1rem 0">' +
        '<dt>Time</dt><dd>' + fmt.duration(item.durationMinutes) + '</dd>' +
        '<dt>Price</dt><dd>' + fmt.money(item.price) + ' <span class="muted">incl. tax as applicable</span></dd>' +
        (item.rating > 0 ? '<dt>Rated</dt><dd>\u2605 ' + Number(item.rating).toFixed(1) + ' from ' + fmt.num(item.ratingCount || 0) + ' visits</dd>' : '') +
        (item.prepNotes ? '<dt>Before</dt><dd>' + fmt.esc(item.prepNotes) + '</dd>' : '') +
        (item.aftercareNotes ? '<dt>After</dt><dd>' + fmt.esc(item.aftercareNotes) + '</dd>' : '') +
        ((item.tags || []).length ? '<dt>Filed under</dt><dd class="mono">' + fmt.esc((item.tags || []).join(' · ')) + '</dd>' : '') +
      '</dl>' +
      (variants.length ? '<p class="tiny">Options</p><ul style="margin:.4rem 0 1rem;padding-left:1.1rem;line-height:1.8">' +
        variants.map(function (v) {
          return '<li>' + fmt.esc(v.label) + ' <span class="mono">+' + fmt.money(v.extraPrice) +
            (v.extraMinutes ? ' · +' + v.extraMinutes + ' min' : '') + '</span>' +
            (v.note ? ' <span class="muted">— ' + fmt.esc(v.note) + '</span>' : '') + '</li>';
        }).join('') + '</ul>' : '') +
      ((item.stylists || []).length ? '<p class="tiny">In these hands</p><p class="mono" style="margin:.3rem 0 0">' +
        fmt.esc((item.stylists || []).join(', ')) + '</p>' : '');
    S.modal({
      title: fmt.esc(item.name), kicker: 'Service',
      body: body,
      actions: item.onlineBookable === false
        ? [{ label: 'Ask at the desk', kind: 'solid' }]
        : [{ label: 'Book this', kind: 'solid', onClick: function () { window.location.href = '/book?service=' + encodeURIComponent(item.id); } },
           { label: 'Close', kind: 'ghost' }]
    });
  }

  function renderChairs(staff) {
    var host = $('#chairGrid');
    host.innerHTML = '';
    staff.forEach(function (member) {
      var card = document.createElement('article');
      card.className = 'chair';
      card.innerHTML =
        '<div class="between" style="align-items:flex-start">' +
          '<span class="avatar" aria-hidden="true">' + fmt.esc(fmt.initials(member.name)) + '</span>' +
          '<span class="badge ' + (member.rating >= 4.8 ? 'gold' : 'mut') + '">\u2605 ' + Number(member.rating || 0).toFixed(1) + '</span>' +
        '</div>' +
        '<div><h4>' + fmt.esc(member.name) + '</h4><p class="role">' + fmt.esc(member.title || 'Stylist') + '</p></div>' +
        (member.quote ? '<blockquote>\u201C' + fmt.esc(member.quote) + '\u201D</blockquote>' : '') +
        '<p class="specs">' + fmt.esc((member.specialties || []).slice(0, 3).join(' · ')) + '</p>' +
        '<div class="foot"><span class="mono" style="font-size:.66rem;color:var(--fg-mut)">' +
          (member.years ? fmt.num(member.years) + ' years' : '') + '</span>' +
          '<button class="link" type="button">Book with ' + fmt.esc((member.name || '').split(' ')[0]) + '</button></div>';
      card.querySelector('button').addEventListener('click', function () {
        window.location.href = '/book?staff=' + encodeURIComponent(member.id);
      });
      host.appendChild(card);
    });
    if (!staff.length) host.innerHTML = '<p class="muted">The chairs are being introduced.</p>';
  }

  function renderLooks(looks) {
    var host = $('#lookGrid');
    host.innerHTML = '';
    looks.forEach(function (look) {
      var palette = look.palette || [];
      var card = document.createElement('article');
      card.className = 'look';
      card.innerHTML =
        '<div class="swatches" aria-hidden="true">' +
          palette.map(function (c) { return '<i style="background:' + fmt.esc(c) + '"></i>'; }).join('') +
        '</div>' +
        '<div class="body">' +
          '<p class="tiny" style="margin:0">' + fmt.esc(look.season || '') + (look.stylist ? ' · ' + fmt.esc(look.stylist) : '') + '</p>' +
          '<h4>' + fmt.esc(look.title) + '</h4>' +
          '<p class="story">' + fmt.esc(look.story || look.notes || '') + '</p>' +
          '<div class="foot"><span class="mono" style="font-size:.64rem;color:var(--fg-mut)">' + fmt.duration(look.minutes || 0) +
            ' · ' + (look.serviceNames || []).length + ' services</span>' +
            '<button class="link" type="button">What it took</button></div>' +
        '</div>';
      card.querySelector('button').addEventListener('click', function () {
        S.modal({
          title: fmt.esc(look.title), kicker: fmt.esc(look.season || 'Lookbook'),
          body: '<p style="font-family:var(--display);font-style:italic;font-size:1.1rem">' + fmt.esc(look.story || '') + '</p>' +
            '<p>' + fmt.esc(look.notes || '') + '</p>' +
            '<p class="tiny">Services used</p><ul style="margin:.3rem 0 1rem;padding-left:1.1rem;line-height:1.8">' +
            (look.serviceNames || []).map(function (n) { return '<li>' + fmt.esc(n) + '</li>'; }).join('') + '</ul>' +
            '<p class="tiny">Palette</p><div style="display:flex;gap:.4rem;margin-top:.4rem">' +
            palette.map(function (c) { return '<span class="mono" style="border:1px solid var(--rule);padding:.35rem .5rem;background:' +
              fmt.esc(c) + '">' + fmt.esc(c) + '</span>'; }).join('') + '</div>',
          actions: [{ label: 'Book something like this', kind: 'solid', onClick: function () { window.location.href = '/book'; } },
                    { label: 'Close', kind: 'ghost' }]
        });
      });
      host.appendChild(card);
    });
    if (!looks.length) host.innerHTML = '<p class="muted">The lookbook is being printed.</p>';
  }

  function renderTiers(loyalty) {
    var host = $('#tierGrid');
    var tiers = loyalty.tiers || [];
    host.innerHTML = tiers.map(function (tier, i) {
      return '<div class="tier' + (i === tiers.length - 1 ? ' hero-tier' : '') + '">' +
        '<p class="name">' + fmt.esc(tier.name) + '</p>' +
        '<p class="min">From ' + fmt.money(tier.minSpend) + ' a year</p>' +
        '<p class="perk">' + fmt.esc(tier.perk || '') + '</p>' +
        '<p class="mult">' + (tier.pointsMultiplier > 1 ? 'Points \u00d7' + tier.pointsMultiplier : 'Standard point earning') + '</p>' +
        '</div>';
    }).join('');
    var note = document.createElement('p');
    note.className = 'hint';
    note.style.marginTop = '1.2rem';
    note.innerHTML = fmt.esc(loyalty.note || '') + ' ' + (loyalty.pointsPerHundred
      ? 'Earn <b>' + loyalty.pointsPerHundred + ' points per \u20B9100</b>' : '') +
      (loyalty.pointValue ? ' \u00b7 each point is worth <b>' + fmt.money(loyalty.pointValue) + '</b>' : '') +
      (loyalty.capPct ? ' \u00b7 redeem up to <b>' + loyalty.capPct + '%</b> of a bill' : '');
    host.parentNode.appendChild(note);
  }

  function renderQuotes(quotes) {
    var host = $('#quoteGrid');
    host.innerHTML = quotes.map(function (q) {
      var stars = '\u2605'.repeat(Math.max(1, Math.min(5, q.rating || 5)));
      return '<figure class="quote" style="margin:0">' +
        '<p class="stars" aria-label="' + (q.rating || 5) + ' out of five">' + stars + '</p>' +
        '<blockquote>\u201C' + fmt.esc(q.quote) + '\u201D</blockquote>' +
        '<figcaption class="by">' + fmt.esc(q.authorName) +
          (q.handle ? ' · ' + fmt.esc(q.handle) : '') +
          (q.staffName ? ' · chair: ' + fmt.esc(q.staffName) : '') +
          (q.date ? ' · ' + fmt.date(q.date) : '') + '</figcaption></figure>';
    }).join('');
    if (!quotes.length) host.innerHTML = '<p class="muted">No notes in the book yet.</p>';
  }

  function renderHours(salon) {
    var body = $('#hoursTable tbody');
    var hours = salon.hours || [];
    var today = new Date().toLocaleDateString('en-GB', { weekday: 'long' });
    body.innerHTML = hours.map(function (h) {
      var closed = h.closed || !h.start;
      var isToday = String(h.day || '').toLowerCase() === today.toLowerCase();
      return '<tr class="' + (closed ? 'closed ' : '') + (isToday ? 'today' : '') + '">' +
        '<td>' + fmt.esc(h.day) + (isToday ? ' <span class="tiny">today</span>' : '') + '</td>' +
        '<td>' + (closed ? 'Closed' : fmt.esc(h.start) + ' \u2013 ' + fmt.esc(h.end)) + '</td></tr>';
    }).join('');
    var holidays = (salon.holidays || []).slice(0, 3);
    $('#holidayNote').textContent = holidays.length
      ? 'Closed also for: ' + holidays.map(function (h) { return h.name + ' (' + fmt.date(h.date) + ')'; }).join(', ')
      : '';
    $('#addressBlock').innerHTML = [salon.legalName || salon.salonName, salon.addressLine,
      (salon.city || '') + ' ' + (salon.pincode || ''), salon.state].filter(Boolean)
      .map(function (line, i) { return i === 0 ? '<b>' + fmt.esc(line) + '</b>' : fmt.esc(line); }).join('<br>');
  }

  function renderPolicies(salon) {
    var host = $('#policyList');
    var policies = salon.policies || {};
    var labels = { cancellation: 'Cancelling', lateArrival: 'Running late', consultation: 'Consultations',
      privacy: 'Your record', giftCards: 'Gift cards', children: 'Children', receipt: 'Receipts' };
    host.innerHTML = Object.keys(policies).map(function (key) {
      return '<dt>' + fmt.esc(labels[key] || key) + '</dt><dd style="color:var(--fg-soft)">' + fmt.esc(policies[key]) + '</dd>';
    }).join('');
  }

  /* --------------------------------------------------------------- search */
  var lastQuery = '';
  function runSearch(query, suggestOnly) {
    query = (query || '').trim();
    var box = $('#finderResults');
    if (query.length < 2) { hideResults(); return; }
    if (query === lastQuery && !suggestOnly) return;
    lastQuery = query;
    var url = suggestOnly ? '/api/search/suggest?q=' + encodeURIComponent(query) + '&limit=7'
      : '/api/search?q=' + encodeURIComponent(query) + '&limit=8';
    api.get(url).then(function (result) {
      var items = suggestOnly ? (result.suggestions || []) : (result.hits || []);
      box.innerHTML = '';
      if (!items.length) {
        box.innerHTML = '<p class="finder-meta">Nothing filed under \u201C' + fmt.esc(query) + '\u201D</p>';
        box.classList.remove('hide');
        return;
      }
      items.forEach(function (item) {
        if (suggestOnly) {
          var b = document.createElement('button');
          b.type = 'button'; b.className = 'suggest';
          b.innerHTML = '<span class="kind">' + fmt.esc(item.kind || 'index') + '</span><span>' + fmt.esc(item.title || item.text || item) + '</span>';
          b.addEventListener('click', function () {
            $('#finderInput').value = item.title || item.text || item;
            runSearch($('#finderInput').value, false);
          });
          box.appendChild(b);
          return;
        }
        var a = document.createElement('a');
        a.href = item.href || '#';
        a.setAttribute('role', 'option');
        a.innerHTML = '<span class="kind">' + fmt.esc(item.kind) + '</span>' +
          '<span><b>' + fmt.esc(item.title) + '</b>' +
          (item.subtitle ? ' <span class="muted">\u00b7 ' + fmt.esc(item.subtitle) + '</span>' : '') + '</span>' +
          (item.attrs && item.attrs.price ? '<span class="price">' + fmt.money(item.attrs.price) + '</span>' : '');
        if (item.kind === 'service') {
          a.addEventListener('click', function (e) {
            e.preventDefault();
            var service = ((data.menu || {}).services || []).filter(function (s) { return s.id === item.id; })[0];
            if (service) openService(service, (data.menu || {}).categories);
            else window.location.href = '/book?service=' + encodeURIComponent(item.id);
          });
        }
        box.appendChild(a);
      });
      var meta = document.createElement('p');
      meta.className = 'finder-meta';
      if (!suggestOnly) {
        meta.textContent = result.total + ' result' + (result.total === 1 ? '' : 's') + ' in ' +
          (result.tookMicros / 1000).toFixed(1) + ' ms · ' + ((result.telemetry || {}).engine || 'bm25') +
          ' · strategy ' + ((result.telemetry || {}).strategy || 'exact');
      }
      box.appendChild(meta);
      box.classList.remove('hide');
      if (!suggestOnly) S.announce(result.total + ' results for ' + query);
    }).catch(function () { hideResults(); });
  }

  /* -------------------------------------------------------------- verify */
  function verifyReceipt(e) {
    e.preventDefault();
    var number = $('#vNumber').value.trim();
    var code = $('#vCode').value.trim();
    var out = $('#verifyOut');
    if (!number || !code) { out.textContent = 'Both the number and the seal are needed.'; return; }
    out.textContent = 'Checking the book\u2026';
    api.get('/api/receipts/verify?number=' + encodeURIComponent(number) + '&code=' + encodeURIComponent(code))
      .then(function (r) {
        out.innerHTML = r.verified
          ? '<span style="color:var(--ok)">\u2713 Genuine.</span> ' + fmt.esc(r.number) + ' for ' +
            fmt.money(r.total) + ', ' + fmt.esc(r.status) + ', client ' + fmt.esc(r.customer || '') + '.'
          : '<span style="color:var(--bad)">\u2717 No match.</span> Check the number and the twelve-character seal on the paper receipt.';
        S.announce(r.verified ? 'Receipt verified' : 'Receipt not verified', false);
      })
      .catch(function (err) { out.textContent = err.message || 'Could not verify right now.'; });
  }

  /* --------------------------------------------------------------- quote */
  function sendQuote(e) {
    e.preventDefault();
    var body = {
      authorName: $('#qName').value.trim(),
      handle: $('#qHandle').value.trim(),
      rating: Number($('#qRating').value),
      quote: $('#qText').value.trim(),
      source: 'web'
    };
    if (!body.authorName || !body.quote) return;
    api.post('/api/testimonials', body).then(function () {
      S.toast('Thank you \u2014 the desk reads every note before it goes on the page.', 'ok', 'Sent');
      $('#quoteForm').reset();
    }).catch(function (err) {
      S.toast((err.details && err.details.length ? err.details.join(' ') : err.message) || 'Could not send', 'bad', 'Not sent');
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
}());
