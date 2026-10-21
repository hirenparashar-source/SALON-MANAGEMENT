/* ============================================================================
   SILLAGE — the public booking book.
   Five steps, no account required, with an optional deposit through the
   simulated gateway. Everything the desk sees is written by the same API the
   console uses, so the diary has one source of truth.
   ========================================================================== */
(function () {
  'use strict';
  var S = window.Sillage;
  var api = S.api, fmt = S.fmt;

  var boot = null;
  var state = {
    step: 1, category: null, filter: '',
    serviceId: null, variantId: null, staffId: null, date: null, start: null,
    slots: [], days: [], price: 0, minutes: 0,
    payNow: false, method: null, tender: {}, otp: null, intentId: null,
    booking: null, deposit: null
  };

  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  /* ------------------------------------------------------------------ boot */
  function init() {
    $('#btnAccess').addEventListener('click', function () { S.a11y(); });
    $('#btnAccessFoot').addEventListener('click', function () { S.a11y(); });
    $('#svcSearch').addEventListener('input', S.debounce(function (e) {
      state.filter = e.target.value.trim().toLowerCase(); renderServices();
    }, 140));
    $$('#stepRail .step').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var target = Number(btn.getAttribute('data-step'));
        if (btn.disabled) return;
        if (target < state.step) showStep(target);
      });
    });
    $('#btnBack').addEventListener('click', function () {
      if (state.step > 1) showStep(state.step - 1);
    });
    $('#btnConfirm').addEventListener('click', confirmBooking);
    $('#dNotes').addEventListener('input', function (e) {
      $('#notesCount').textContent = e.target.value.length + ' / 400';
    });
    $$('#payChoice .chip').forEach(function (chip) {
      chip.addEventListener('click', function () {
        $$('#payChoice .chip').forEach(function (c) { c.classList.remove('on'); c.setAttribute('aria-pressed', 'false'); });
        chip.classList.add('on'); chip.setAttribute('aria-pressed', 'true');
        state.payNow = chip.getAttribute('data-pay') === 'now';
        $('#tenderHost').classList.toggle('hide', !state.payNow);
        $('#payNote').textContent = state.payNow
          ? 'We will take a deposit now and settle the rest at the desk. Nothing is stored on this page: the card number is tokenised by the gateway and never written to the booking book.'
          : 'Most guests settle at the desk by cash, UPI or card. The chair is yours either way.';
        renderReview();
      });
    });
    $('#manageForm').addEventListener('submit', lookup);
    $('#giftForm').addEventListener('submit', checkGift);

    // query-string entry points: /book?service=svc-x&staff=stf-y&date=2026-11-02
    var q = new URLSearchParams(window.location.search);
    if (q.get('service')) state.serviceId = q.get('service');
    if (q.get('staff')) state.staffId = q.get('staff');
    if (q.get('date')) state.date = q.get('date');

    S.bootstrap().then(function (data) {
      boot = data;
      renderCats();
      renderServices();
      if (state.serviceId) {
        pickService(state.serviceId, true);
      } else {
        renderSummary();
      }
    }).catch(function (err) {
      $('#svcList').innerHTML = '<div class="empty"><p class="big">The index is not loading</p>' +
        '<p>' + fmt.esc(err.message || 'The salon server did not answer.') + ' Call the desk on +91 98765 43210 and we will write you in by hand.</p></div>';
    });
  }

  /* --------------------------------------------------------------- step 1 */
  function renderCats() {
    var cats = (boot.menu || {}).categories || [];
    var host = $('#svcCats');
    host.innerHTML = '';
    var all = mkChip('Everything', state.category === null, function () { state.category = null; renderCats(); renderServices(); });
    host.appendChild(all);
    cats.forEach(function (cat) {
      host.appendChild(mkChip(cat.name, state.category === cat.id, function () {
        state.category = cat.id; renderCats(); renderServices();
      }));
    });
  }

  function mkChip(label, on, handler) {
    var b = document.createElement('button');
    b.type = 'button'; b.className = 'chip' + (on ? ' on' : '');
    b.textContent = label; b.setAttribute('aria-pressed', String(!!on));
    b.addEventListener('click', handler);
    return b;
  }

  function renderServices() {
    var services = ((boot.menu || {}).services || []).filter(function (s) { return s.onlineBookable !== false; });
    if (state.category) services = services.filter(function (s) { return s.categoryId === state.category; });
    if (state.filter) {
      services = services.filter(function (s) {
        return (s.name + ' ' + (s.tagline || '') + ' ' + (s.tags || []).join(' ') + ' ' + (s.categoryName || ''))
          .toLowerCase().indexOf(state.filter) >= 0;
      });
    }
    var host = $('#svcList');
    host.innerHTML = '';
    if (!services.length) {
      host.innerHTML = '<div class="empty"><p class="big">Nothing matches</p><p>Try another word, or clear the filter.</p></div>';
      return;
    }
    var grid = document.createElement('div');
    grid.className = 'pick-grid';
    services.forEach(function (service) {
      var card = document.createElement('button');
      card.type = 'button';
      card.className = 'pick';
      card.setAttribute('aria-pressed', String(service.id === state.serviceId));
      card.innerHTML =
        '<span class="meta">' + fmt.esc(service.categoryName || '') + '</span>' +
        '<span class="name">' + fmt.esc(service.name) + '</span>' +
        '<span class="tag">' + fmt.esc(service.tagline || '') + '</span>' +
        '<span class="meta"><b>' + fmt.money(service.price) + '</b><span>' + fmt.duration(service.durationMinutes) + '</span>' +
        (service.rating > 0 ? '<span>\u2605 ' + Number(service.rating).toFixed(1) + '</span>' : '') +
        ((service.variants || []).length ? '<span>options</span>' : '') + '</span>';
      card.addEventListener('click', function () { pickService(service.id); });
      grid.appendChild(card);
    });
    host.appendChild(grid);
  }

  function pickService(id, silent) {
    state.serviceId = id; state.variantId = null;
    $$('#svcList .pick').forEach(function (node, i) {
      node.setAttribute('aria-pressed', 'false');
    });
    var service = serviceById(id);
    renderServices();
    var variants = (service && service.variants) || [];
    if (variants.length && !silent) {
      chooseVariant(service, variants);
      return;
    }
    applyService(service);
  }

  function chooseVariant(service, variants) {
    var body = document.createElement('div');
    body.innerHTML = '<p style="color:var(--fg-soft)">' + fmt.esc(service.tagline || '') + '</p>' +
      '<div class="pick-grid" style="margin-top:1rem">' +
      '<button class="pick" type="button" data-v=""><span class="name">As listed</span><span class="meta">' +
        fmt.money(service.price) + '<span>' + fmt.duration(service.durationMinutes) + '</span></span></button>' +
      variants.map(function (v) {
        return '<button class="pick" type="button" data-v="' + fmt.esc(v.id) + '"><span class="name">' + fmt.esc(v.label) + '</span>' +
          '<span class="meta">+' + fmt.money(v.extraPrice) + '<span>+' + v.extraMinutes + ' min</span></span>' +
          (v.note ? '<span class="tag">' + fmt.esc(v.note) + '</span>' : '') + '</button>';
      }).join('') + '</div>';
    var handle = S.modal({
      title: fmt.esc(service.name), kicker: 'Choose an option', body: body,
      actions: [{ label: 'Keep browsing', kind: 'ghost' }]
    });
    body.addEventListener('click', function (e) {
      var btn = e.target.closest ? e.target.closest('[data-v]') : null;
      if (!btn) return;
      state.variantId = btn.getAttribute('data-v') || null;
      handle.close();
      applyService(service);
    });
  }

  function serviceById(id) {
    return ((boot.menu || {}).services || []).filter(function (s) { return s.id === id; })[0] || null;
  }

  function applyService(service) {
    if (!service) return;
    var extra = 0, extraMinutes = 0;
    if (state.variantId) {
      var variant = (service.variants || []).filter(function (v) { return v.id === state.variantId; })[0];
      if (variant) { extra = Number(variant.extraPrice || 0); extraMinutes = Number(variant.extraMinutes || 0); }
    }
    state.price = Number(service.price || 0) + extra;
    state.minutes = Number(service.durationMinutes || 0) + extraMinutes;
    state.staffId = state.staffId || null;
    renderStaff(service);
    renderSummary();
    showStep(2);
    loadAvailability();
    S.announce('Chosen: ' + service.name + ', ' + fmt.money(state.price));
  }

  /* --------------------------------------------------------------- step 2 */
  function renderStaff(service) {
    var host = $('#staffList');
    var eligible = (boot.staff || []).filter(function (m) {
      return !service.serviceIds || !service.serviceIds.length || service.serviceIds.indexOf(m.id) >= 0;
    });
    host.innerHTML = '';
    var any = document.createElement('button');
    any.type = 'button'; any.className = 'pick' + (state.staffId ? '' : '');
    any.setAttribute('aria-pressed', String(!state.staffId));
    any.innerHTML = '<span class="meta">First available</span><span class="name">Any chair</span>' +
      '<span class="tag">The earliest stylist who can do this service.</span>';
    any.addEventListener('click', function () {
      state.staffId = null; renderStaff(service); loadAvailability();
    });
    host.appendChild(any);
    eligible.forEach(function (member) {
      var card = document.createElement('button');
      card.type = 'button'; card.className = 'pick';
      card.setAttribute('aria-pressed', String(state.staffId === member.id));
      card.innerHTML = '<span class="meta">' + fmt.esc(member.title || '') + '</span>' +
        '<span class="name">' + fmt.esc(member.name) + '</span>' +
        '<span class="tag">' + fmt.esc((member.specialties || []).slice(0, 2).join(' · ')) + '</span>' +
        '<span class="meta"><span>\u2605 ' + Number(member.rating || 0).toFixed(1) + '</span>' +
        (member.years ? '<span>' + member.years + ' years</span>' : '') + '</span>';
      card.addEventListener('click', function () {
        state.staffId = member.id; renderStaff(service); loadAvailability();
      });
      host.appendChild(card);
    });
    if (!eligible.length) {
      host.innerHTML = '<p class="hint">Every chair can do this service.</p>';
    }
  }

  function loadAvailability() {
    var service = serviceById(state.serviceId);
    if (!service) return;
    var rail = $('#dayRail');
    rail.innerHTML = '<p class="hint">Reading the diary\u2026</p>';
    var from = state.date || fmt.today(0);
    var url = '/api/availability?serviceId=' + encodeURIComponent(service.id) +
      (state.variantId ? '&variantId=' + encodeURIComponent(state.variantId) : '') +
      (state.staffId ? '&staffId=' + encodeURIComponent(state.staffId) : '') +
      '&date=' + encodeURIComponent(from) + '&days=10';
    api.get(url).then(function (result) {
      state.days = result.days || [];
      renderDays(result);
      var chosen = state.days.filter(function (d) { return d.date === state.date; })[0];
      if (chosen) selectDay(chosen.date);
      else if (state.days.length) {
        var first = state.days.filter(function (d) { return d.available > 0; })[0] || state.days[0];
        selectDay(first.date);
      }
    }).catch(function (err) {
      rail.innerHTML = '<p class="err">' + fmt.esc(err.message || 'The diary is not answering.') + '</p>';
    });
  }

  function renderDays(result) {
    var rail = $('#dayRail');
    rail.innerHTML = '';
    (result.days || []).forEach(function (day) {
      var chip = document.createElement('button');
      chip.type = 'button';
      var closed = !day.open || day.available === 0;
      chip.className = 'day-chip' + (closed ? ' closed' : '');
      chip.setAttribute('aria-pressed', String(day.date === state.date));
      chip.disabled = closed && !day.open;
      chip.innerHTML = '<span class="dow">' + fmt.esc((day.dayLabel || '').slice(0, 3)) + '</span>' +
        fmt.esc((day.date || '').slice(8)) + '/' + fmt.esc((day.date || '').slice(5, 7)) +
        '<span class="dow" style="margin-top:.2rem">' + (closed ? (day.reason || 'closed') : day.available + ' free') + '</span>';
      chip.addEventListener('click', function () {
        if (chip.disabled) return;
        selectDay(day.date);
      });
      rail.appendChild(chip);
    });
    $('#dayNote').textContent = result.note || '';
  }

  function selectDay(date) {
    state.date = date; state.start = null;
    $$('#dayRail .day-chip').forEach(function (chip) { chip.setAttribute('aria-pressed', 'false'); });
    var day = state.days.filter(function (d) { return d.date === date; })[0];
    state.slots = (day && day.slots) || [];
    renderSlots(day);
    renderSummary();
    showStep(3);
  }

  /* --------------------------------------------------------------- step 3 */
  function renderSlots(day) {
    var host = $('#slotHost');
    host.innerHTML = '';
    if (!day) { host.innerHTML = '<p class="muted">Choose a day first.</p>'; return; }
    $('#slotHint').innerHTML = '<b>' + fmt.esc(day.dayLabel || day.date) + '</b> · ' +
      (day.available || 0) + ' chair' + ((day.available || 0) === 1 ? '' : 's') + ' free' +
      (day.closed ? ' · <span style="color:var(--bad)">' + fmt.esc(day.reason || 'closed') + '</span>' : '');
    if (!day.open || !state.slots.length) {
      host.innerHTML = '<div class="empty"><p class="big">Nothing free that day</p>' +
        '<p>' + fmt.esc(day.reason || 'The salon is closed, or every chair is taken.') + ' Try the next day.</p></div>';
      return;
    }
    var morning = state.slots.filter(function (s) { return Number(s.start.slice(11, 13)) < 12; });
    var afternoon = state.slots.filter(function (s) { return Number(s.start.slice(11, 13)) >= 12 && Number(s.start.slice(11, 13)) < 17; });
    var evening = state.slots.filter(function (s) { return Number(s.start.slice(11, 13)) >= 17; });
    [['Morning', morning], ['Afternoon', afternoon], ['Evening', evening]].forEach(function (group) {
      if (!group[1].length) return;
      var label = document.createElement('p');
      label.className = 'tiny'; label.style.margin = '1rem 0 .5rem';
      label.textContent = group[0];
      host.appendChild(label);
      var grid = document.createElement('div');
      grid.className = 'slots';
      group[1].forEach(function (slot) {
        var b = document.createElement('button');
        b.type = 'button'; b.className = 'slot';
        b.disabled = !slot.available;
        b.setAttribute('aria-pressed', String(state.start === slot.start));
        b.innerHTML = fmt.time(slot.start) + (slot.staffName ? '<small>' + fmt.esc(slot.staffName.split(' ')[0]) + '</small>' : '');
        if (!slot.available && slot.reason) b.title = slot.reason;
        b.addEventListener('click', function () {
          state.start = slot.start;
          state.staffId = slot.staffId || state.staffId;
          $$('#slotHost .slot').forEach(function (n) { n.setAttribute('aria-pressed', 'false'); });
          b.setAttribute('aria-pressed', 'true');
          renderSummary();
          showStep(4);
          S.announce('Time chosen: ' + fmt.time(slot.start) + ' with ' + (slot.staffName || 'the first available chair'));
        });
        grid.appendChild(b);
      });
      host.appendChild(grid);
    });
  }

  /* --------------------------------------------------------------- step 4/5 */
  function validateDetails() {
    var ok = true;
    var name = $('#dName').value.trim();
    var phone = $('#dPhone').value.trim();
    var email = $('#dEmail').value.trim();
    $$('[data-for]').forEach(function (n) { n.textContent = ''; });
    if (name.length < 2) { $('[data-for="dName"]').textContent = 'We need a name for the book.'; ok = false; }
    var digits = phone.replace(/\D/g, '');
    if (digits.length < 10) { $('[data-for="dPhone"]').textContent = 'A ten-digit mobile, please.'; ok = false; }
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email)) { $('[data-for="dEmail"]').textContent = 'That email looks incomplete.'; ok = false; }
    return ok ? { name: name, phone: phone, email: email, notes: $('#dNotes').value.trim(),
      preferredTime: $('#dWhen').value, consent: $('#dConsent').checked, marketing: $('#dMarketing').checked } : null;
  }

  function renderReview() {
    var service = serviceById(state.serviceId);
    if (!service) return;
    var staff = (boot.staff || []).filter(function (m) { return m.id === state.staffId; })[0];
    var deposit = depositAmount();
    var rows = [
      ['Service', service.name + (state.variantId ? ' · ' + variantLabel(service) : '')],
      ['Chair', staff ? staff.name + (staff.title ? ' — ' + staff.title : '') : 'First available'],
      ['When', state.date ? fmt.day(state.date) + ' at ' + (state.start ? fmt.time(state.start) : '\u2014') : '\u2014'],
      ['Time in the chair', fmt.duration(state.minutes)],
      ['Price', fmt.money(state.price) + ' (inclusive of tax as applicable)']
    ];
    if (state.payNow) rows.push(['Deposit now', fmt.money(deposit) + ' · balance at the desk']);
    $('#reviewHost').innerHTML =
      '<div class="card flush"><table class="table"><tbody>' +
      rows.map(function (r) { return '<tr><td class="tiny" style="width:11rem;border-bottom:1px solid var(--rule)">' +
        fmt.esc(r[0]) + '</td><td style="border-bottom:1px solid var(--rule)">' + fmt.esc(r[1]) + '</td></tr>'; }).join('') +
      '</tbody></table></div>';
    if (state.payNow) renderTenders(deposit);
  }

  function variantLabel(service) {
    var v = (service.variants || []).filter(function (x) { return x.id === state.variantId; })[0];
    return v ? v.label : '';
  }

  function depositAmount() {
    var policy = (boot.salon || {}).requireDeposit ? Number((boot.salon || {}).depositPct || 0) : 0;
    if (state.price >= 10000 && policy > 0) return Math.round(state.price * policy / 100);
    return Math.min(state.price, Math.max(300, Math.round(state.price * 0.25)));
  }

  function renderTenders(amount) {
    var methods = (boot.payments || []).filter(function (m) { return m.enabled !== false && m.key !== 'CASH'; });
    var host = $('#tenderGrid');
    host.innerHTML = '';
    methods.forEach(function (m) {
      var b = document.createElement('button');
      b.type = 'button'; b.className = 'chip' + (state.method === m.key ? ' on' : '');
      b.setAttribute('aria-pressed', String(state.method === m.key));
      b.textContent = m.label || m.key;
      b.addEventListener('click', function () {
        state.method = m.key;
        renderTenders(amount);
        showTenderFields(m.key);
      });
      host.appendChild(b);
    });
    if (!state.method && methods.length) { state.method = methods[0].key; showTenderFields(state.method); }
    $('#payNote').innerHTML = 'Deposit <b>' + fmt.money(amount) + '</b> of ' + fmt.money(state.price) +
      '. The balance is settled at the desk, and the deposit is printed on your receipt.';
  }

  function showTenderFields(method) {
    $('#cardFields').classList.toggle('hide', method !== 'CARD');
    $('#upiFields').classList.toggle('hide', method !== 'UPI');
    $('#bankFields').classList.toggle('hide', method !== 'NETBANKING');
    $('#walletFields').classList.toggle('hide', method !== 'WALLET');
    $('#giftFields').classList.toggle('hide', method !== 'GIFT_CARD');
  }

  /* ------------------------------------------------------------- stepping */
  function showStep(n) {
    state.step = n;
    for (var i = 1; i <= 5; i++) {
      var panel = $('#panel' + i);
      if (panel) panel.classList.toggle('hide', i !== n);
    }
    $('#panelDone').classList.add('hide');
    $$('#stepRail .step').forEach(function (btn) {
      var step = Number(btn.getAttribute('data-step'));
      btn.setAttribute('aria-current', step === n ? 'step' : 'false');
      btn.classList.toggle('done', step < n);
      btn.disabled = step > n && !canReach(step);
    });
    if (n === 5) renderReview();
    window.scrollTo({ top: 0, behavior: S.prefs.motion === 'reduce' ? 'auto' : 'smooth' });
  }

  function canReach(step) {
    if (step === 2) return !!state.serviceId;
    if (step === 3) return !!state.serviceId && !!state.date;
    if (step === 4) return !!state.start;
    if (step === 5) return !!state.start;
    return true;
  }

  function renderSummary() {
    var service = serviceById(state.serviceId);
    var staff = (boot.staff || []).filter(function (m) { return m.id === state.staffId; })[0];
    var host = $('#summaryBody');
    if (!service) { host.innerHTML = '<p class="hint">Nothing chosen yet.</p>'; return; }
    var lines = [
      ['Service', service.name],
      service.variants && state.variantId ? ['Option', variantLabel(service)] : null,
      ['Chair', staff ? staff.name : 'First available'],
      ['Day', state.date ? fmt.day(state.date) : 'Not chosen'],
      ['Time', state.start ? fmt.time(state.start) + ' \u2013 ' + fmt.time(endOf(state.start, state.minutes)) : 'Not chosen'],
      ['Duration', fmt.duration(state.minutes)]
    ].filter(Boolean);
    host.innerHTML = lines.map(function (l) {
      return '<div class="line"><span>' + fmt.esc(l[0]) + '</span><span>' + fmt.esc(String(l[1])) + '</span></div>';
    }).join('') +
      '<div class="grand"><span>Total</span><span>' + fmt.money(state.price) + '</span></div>' +
      (state.payNow ? '<div class="line" style="margin-top:.4rem"><span>Deposit now</span><span>' + fmt.money(depositAmount()) + '</span></div>' : '') +
      '<p class="hint" style="margin:.8rem 0 0">Tax is calculated on the final bill at the desk and shown on your receipt.</p>';
  }

  function endOf(start, minutes) {
    var d = new Date(start);
    d.setMinutes(d.getMinutes() + (minutes || 0));
    return d.toTimeString().slice(0, 5);
  }

  /* -------------------------------------------------------------- confirm */
  function confirmBooking() {
    var details = validateDetails();
    if (!details) { showStep(4); S.toast('Check the highlighted fields', 'bad', 'Not yet'); return; }
    if (!state.start) { showStep(3); return; }
    var btn = $('#btnConfirm');
    btn.disabled = true; btn.textContent = 'Holding the chair\u2026';
    $('#confirmError').textContent = '';

    var body = {
      items: [{ serviceId: state.serviceId, variantId: state.variantId }],
      staffId: state.staffId,
      start: state.start,
      guestName: details.name,
      guestPhone: details.phone,
      guestEmail: details.email,
      notes: details.notes,
      preferredTime: details.preferredTime,
      marketingConsent: details.marketing,
      dataConsent: details.consent,
      source: 'web'
    };
    api.post('/api/appointments', body).then(function (result) {
      state.booking = result.appointment || result;
      if (!state.payNow) return finish();
      return payDeposit(details).then(finish).catch(function (err) {
        $('#confirmError').textContent = '';
        if (err.code === 'AUTHENTICATION_REQUIRED') return askOtp(err, details);
        finishWithDepositError(err);
      });
    }).catch(function (err) {
      btn.disabled = false; btn.textContent = 'Confirm the booking';
      var message = (err.details && err.details.length) ? err.details.join(' \u00b7 ') : err.message;
      $('#confirmError').textContent = message || 'That slot could not be held.';
      S.toast(message || 'That slot could not be held', 'bad', 'Booking not taken');
      if (err.code === 'CONFLICT' || err.code === 'SLOT_TAKEN') { showStep(3); loadAvailability(); }
    });
  }

  function tenderBody(details) {
    var body = {
      code: state.booking.code,
      phone: details.phone,
      method: state.method || 'CARD',
      amount: depositAmount()
    };
    if (body.method === 'CARD') {
      body.cardNumber = $('#tCard').value.replace(/\s+/g, '');
      body.cardExpiry = $('#tExp').value.trim();
      body.cardCvv = $('#tCvv').value.trim();
      body.cardHolder = $('#tHolder').value.trim() || details.name;
    } else if (body.method === 'UPI') body.upiVpa = $('#tUpi').value.trim();
    else if (body.method === 'NETBANKING') body.bank = $('#tBank').value;
    else if (body.method === 'WALLET') body.wallet = $('#tWallet').value;
    else if (body.method === 'GIFT_CARD') body.giftCardCode = $('#tGift').value.trim();
    if (state.intentId) body.intentId = state.intentId;
    if (state.otp) body.otp = state.otp;
    return body;
  }

  function payDeposit(details) {
    return api.post('/api/appointments/' + encodeURIComponent(state.booking.id) + '/deposit', tenderBody(details));
  }

  function askOtp(err, details) {
    state.intentId = (err.details || {}).intentId || null;
    var challenge = (err.details || {}).challengeId || '';
    var body = document.createElement('div');
    body.innerHTML = '<p>Your bank wants a one-time code' + (challenge ? ' for challenge <span class="mono">' +
      fmt.esc(challenge) + '</span>' : '') + '. In this simulated gateway the code is shown to you: ' +
      '<span class="seal" id="otpHint">' + fmt.esc((err.details || {}).testOtp || '') + '</span></p>' +
      '<div class="field" style="margin-top:1rem"><label class="lbl" for="otpInput">One-time code</label>' +
      '<input id="otpInput" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="\u2022\u2022\u2022\u2022\u2022\u2022"></div>';
    S.modal({
      title: 'Confirm it is you', kicker: '3-D Secure', body: body,
      actions: [
        { label: 'Pay at the desk instead', kind: 'ghost', onClick: function () { state.payNow = false; finish(); } },
        { label: 'Submit the code', kind: 'solid', onClick: function (root) {
            state.otp = $('#otpInput', root).value.trim();
            if (!state.otp) { S.toast('Enter the code first', 'bad'); return false; }
            var btn = $('#btnConfirm'); btn.disabled = true; btn.textContent = 'Authorising\u2026';
            payDeposit(details).then(finish).catch(function (again) {
              btn.disabled = false; btn.textContent = 'Confirm the booking';
              finishWithDepositError(again);
            });
          } }
      ]
    });
  }

  function finishWithDepositError(err) {
    state.deposit = { error: (err.details && err.details.length ? err.details.join(' \u00b7 ') : err.message) };
    S.toast('The booking is held, but the deposit did not go through: ' + state.deposit.error, 'bad', 'Deposit declined');
    finish();
  }

  function finish() {
    var btn = $('#btnConfirm');
    btn.disabled = false; btn.textContent = 'Confirm the booking';
    var booking = state.booking;
    var service = serviceById(state.serviceId);
    var staff = (boot.staff || []).filter(function (m) { return m.id === (booking.staffId || state.staffId); })[0];
    var invoice = state.deposit && state.deposit.invoice ? state.deposit.invoice : null;
    var payment = state.deposit && state.deposit.payment ? state.deposit.payment : null;

    $('#doneHost').innerHTML =
      '<p class="kicker">Written into the book</p>' +
      '<h2 style="max-width:20ch">Your chair is held<span style="color:var(--accent-2)">.</span></h2>' +
      '<p class="lede" style="margin-top:.8rem">' +
        fmt.esc(fmt.day(booking.start)) + ' at <b>' + fmt.time(booking.start) + '</b> with ' +
        fmt.esc(staff ? staff.name : 'the first available chair') + ' for ' + fmt.esc(service ? service.name : 'your service') +
        '. We will send a reminder the day before.</p>' +
      (state.deposit && state.deposit.error
        ? '<p class="err" style="margin-top:.6rem">Deposit not taken: ' + fmt.esc(state.deposit.error) +
          '. The booking still stands \u2014 settle at the desk.</p>' : '') +
      '<div class="grid g2" style="margin-top:1.6rem;align-items:start">' +
        '<div class="card">' +
          '<p class="tiny" style="margin-top:0">Your reference</p>' +
          '<p style="font-family:var(--display);font-size:2.2rem;line-height:1;margin:.3rem 0 .8rem">' + fmt.esc(booking.code) + '</p>' +
          '<dl class="kv">' +
            '<dt>Service</dt><dd>' + fmt.esc(service ? service.name : '') + '</dd>' +
            '<dt>Chair</dt><dd>' + fmt.esc(staff ? staff.name : 'First available') + '</dd>' +
            '<dt>Starts</dt><dd>' + fmt.esc(fmt.stamp(booking.start)) + '</dd>' +
            '<dt>Ends</dt><dd>' + fmt.esc(fmt.time(booking.end)) + '</dd>' +
            '<dt>Price</dt><dd>' + fmt.money(booking.price) + '</dd>' +
            '<dt>Status</dt><dd>' + S.badge(booking.status || 'REQUESTED') + '</dd>' +
          '</dl>' +
          '<div class="btn-row" style="margin-top:1.1rem">' +
            '<button class="btn sm solid" type="button" id="btnIcs">Add to calendar</button>' +
            '<button class="btn sm ghost" type="button" id="btnPrint">Print</button>' +
            '<a class="btn sm ghost" href="/">Back to the front page</a>' +
          '</div>' +
        '</div>' +
        '<div class="card">' +
          '<p class="tiny" style="margin-top:0">What happens next</p>' +
          '<ol style="padding-left:1.1rem;line-height:1.85;font-size:.94rem;color:var(--fg-soft)">' +
            '<li>A reminder goes out the day before, on the channel you chose.</li>' +
            '<li>Arrive five minutes early; the slot is held fifteen minutes past its start.</li>' +
            '<li>Cancelling is free up to six hours before your chair time.</li>' +
            (invoice ? '<li>Your deposit of ' + fmt.money(invoice.paid) + ' is on receipt <a href="' +
              '/api/receipts/' + fmt.esc(invoice.id) + '/print" target="_blank" rel="noopener">' + fmt.esc(invoice.number) + '</a>.</li>'
              : '<li>Settle at the desk by cash, UPI or card.</li>') +
            '<li>Ask for a loyalty record and your points will follow you visit to visit.</li>' +
          '</ol>' +
          (invoice ? '<p class="tiny" style="margin-top:1rem">Receipt seal</p><p class="seal">' + fmt.esc(invoice.receiptCode || '') + '</p>' +
            '<p class="hint">Verify it any time at <span class="mono">/api/receipts/verify</span>.</p>' : '') +
          (payment ? '<p class="hint" style="margin-top:.8rem">Paid with ' + fmt.esc(payment.tenderLabel || payment.method) +
            ' \u00b7 reference <span class="mono">' + fmt.esc(payment.gatewayRef || '') + '</span></p>' : '') +
        '</div>' +
      '</div>';

    $('#panel1').classList.add('hide'); $('#panel2').classList.add('hide'); $('#panel3').classList.add('hide');
    $('#panel4').classList.add('hide'); $('#panel5').classList.add('hide');
    $('#panelDone').classList.remove('hide');
    $$('#stepRail .step').forEach(function (b) { b.classList.add('done'); b.setAttribute('aria-current', 'false'); });
    $('#btnIcs').addEventListener('click', function () { downloadIcs(booking, service, staff); });
    $('#btnPrint').addEventListener('click', function () { window.print(); });
    window.scrollTo({ top: 0, behavior: 'smooth' });
    S.toast('Booking ' + booking.code + ' is in the book', 'ok', 'Confirmed');
  }

  function downloadIcs(booking, service, staff) {
    function z(value) { return String(value).replace(/[-:]/g, '').split('.')[0] + 'Z'; }
    var start = new Date(booking.start);
    var end = new Date(booking.end || booking.start);
    var ics = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//SILLAGE//Booking//EN', 'BEGIN:VEVENT',
      'UID:' + booking.id + '@sillage',
      'DTSTAMP:' + z(new Date().toISOString()),
      'DTSTART:' + z(start.toISOString()),
      'DTEND:' + z(end.toISOString()),
      'SUMMARY:' + (service ? service.name : 'SILLAGE appointment'),
      'LOCATION:SILLAGE, Fountain Lane, Civil Lines, Ludhiana 141001',
      'DESCRIPTION:Reference ' + booking.code + (staff ? ' with ' + staff.name : '') + '. Cancel free up to six hours before.',
      'END:VEVENT', 'END:VCALENDAR'].join('\r\n');
    var url = URL.createObjectURL(new Blob([ics], { type: 'text/calendar' }));
    var a = document.createElement('a');
    a.href = url; a.download = 'sillage-' + booking.code + '.ics';
    document.body.appendChild(a); a.click(); a.remove();
    global.setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    S.toast('Calendar file downloaded', 'ok');
  }

  /* ------------------------------------------------------- lookup & gifts */
  function lookup(e) {
    e.preventDefault();
    var code = $('#mCode').value.trim();
    var out = $('#manageOut');
    if (!code) return;
    out.innerHTML = '<div class="field" style="margin:.4rem 0 0"><label class="lbl" for="mPhone">Mobile on the booking</label>' +
      '<input id="mPhone" type="tel" inputmode="tel" placeholder="+91 \u2026"><span class="hint">We only show a booking to the number it was made with.</span></div>' +
      '<button class="btn sm ghost" type="button" id="mGo" style="margin-top:.6rem">Find it</button>';
    $('#mGo').addEventListener('click', function () {
      var phone = $('#mPhone').value.trim();
      api.get('/api/appointments/lookup?code=' + encodeURIComponent(code) + '&phone=' + encodeURIComponent(phone))
        .then(function (result) {
          out.innerHTML = '<div class="card tight" style="margin-top:.6rem">' +
            '<p class="tiny" style="margin-top:0">' + fmt.esc(result.appointment.code) + ' \u00b7 ' + S.badge(result.appointment.status) + '</p>' +
            '<p style="font-size:.94rem;margin:.3rem 0"><b>' + fmt.esc(result.appointment.serviceNames || result.appointment.service || '') + '</b><br>' +
            fmt.esc(fmt.stamp(result.appointment.start)) + ' with ' + fmt.esc(result.appointment.staffName || 'the team') + '</p>' +
            '<div class="btn-row" style="margin-top:.6rem">' +
              '<button class="btn sm ghost" type="button" data-act="cancel">Cancel this booking</button>' +
              '<a class="btn sm ghost" href="/book">Book another</a></div>' +
            '<p class="hint" id="mMsg" style="margin-top:.6rem"></p></div>';
          var cancel = out.querySelector('[data-act="cancel"]');
          cancel.addEventListener('click', function () {
            S.confirm({ title: 'Cancel ' + result.appointment.code + '?', message: 'Free of charge up to six hours before your chair time. After that the salon may retain a deposit.', danger: true, confirmLabel: 'Cancel the booking' })
              .then(function (yes) {
                if (!yes) return;
                api.post('/api/appointments/' + result.appointment.id + '/release', { code: code, phone: phone, reason: 'Cancelled online by the guest' })
                  .then(function () { $('#mMsg').textContent = 'Cancelled. We hope to see you soon.'; S.toast('Booking cancelled', 'ok'); })
                  .catch(function (err) { $('#mMsg').textContent = err.message; });
              });
          });
        })
        .catch(function (err) { out.innerHTML = '<p class="err">' + fmt.esc(err.message || 'Not found.') + '</p>'; });
    });
  }

  function checkGift(e) {
    e.preventDefault();
    var code = $('#gCode').value.trim();
    var out = $('#giftOut');
    if (!code) return;
    api.get('/api/gift-cards/' + encodeURIComponent(code)).then(function (card) {
      out.innerHTML = '<p style="font-size:.92rem;margin:.2rem 0"><b>' + fmt.money(card.balance) + '</b> left of ' +
        fmt.money(card.amount) + ' ' + S.badge(card.status) + '<br><span class="hint">' +
        (card.recipient ? 'For ' + fmt.esc(card.recipient) + ' \u00b7 ' : '') +
        (card.expiresOn ? 'valid to ' + fmt.date(card.expiresOn) : '') + '</span></p>';
    }).catch(function (err) {
      out.innerHTML = '<p class="err">' + fmt.esc(err.message || 'That code is not in the book.') + '</p>';
    });
  }

  var global = window;
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
}());
