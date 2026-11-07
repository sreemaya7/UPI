'use strict';
/* JavaBank Pay front end. Plain JavaScript, no build step.
   Security notes: every piece of user-supplied text is inserted with textContent (never innerHTML),
   so names and notes cannot inject markup; there are no inline scripts or handlers (strict CSP);
   the session token lives in sessionStorage and is sent as a Bearer header. */
(() => {
  // ------------------------------------------------------------------ tiny helpers
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];

  function h(tag, attrs, ...kids) {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === 'class') e.className = v;
      else if (k.startsWith('on') && typeof v === 'function') e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v === true ? '' : String(v));
    }
    for (const kid of kids.flat()) {
      if (kid == null || kid === false) continue;
      e.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
    }
    return e;
  }

  const inr = n => '₹' + Number(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const compact = n => '₹' + new Intl.NumberFormat('en-IN', { notation: 'compact', maximumFractionDigits: 1 }).format(Number(n));
  const cap = s => s ? s[0].toUpperCase() + s.slice(1) : s;
  const when = ms => new Date(ms).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
  const initials = name => (name || '?').split(/\s+/).map(w => w[0]).join('').slice(0, 2).toUpperCase();
  const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : [...crypto.getRandomValues(new Uint8Array(16))].map(b => b.toString(16).padStart(2, '0')).join(''));

  const store = {
    get() { try { return sessionStorage.getItem('jb_token'); } catch { return null; } },
    set(v) { try { v ? sessionStorage.setItem('jb_token', v) : sessionStorage.removeItem('jb_token'); } catch { /* private mode */ } },
  };

  const state = { token: null, user: null, contacts: [], filter: 'all', tab: 'home' };

  let toastTimer;
  function toast(msg, kind = 'ok') {
    const t = $('#toast');
    t.textContent = msg;
    t.className = 'toast show ' + kind;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 4200);
  }

  async function api(method, path, body) {
    const headers = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (state.token) headers.Authorization = 'Bearer ' + state.token;
    let res;
    try { res = await fetch(path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined }); }
    catch { throw new Error('Cannot reach the server. Check your connection.'); }
    const data = await res.json().catch(() => ({}));
    if (res.status === 401 && state.token) {
      localSignOut();
      throw new Error(data.message || 'Session expired. Please sign in again.');
    }
    if (!res.ok || data.success === false) throw new Error(data.message || 'Something went wrong');
    return data;
  }

  /** Run an async handler, show errors as a toast, and keep the submit button from being double-clicked. */
  function guarded(fn, form) {
    return async (...a) => {
      const btn = form && $('button[type=submit]', form);
      if (btn) btn.disabled = true;
      try { return await fn(...a); }
      catch (e) { toast(e.message || 'Something went wrong', 'bad'); }
      finally { if (btn) btn.disabled = false; }
    };
  }

  // ------------------------------------------------------------------ views, tabs, modals
  function showAuth() {
    $('#view-app').hidden = true;
    $('#view-auth').hidden = false;
  }

  function showApp() {
    $('#view-auth').hidden = true;
    $('#view-app').hidden = false;
  }

  function localSignOut(message) {
    state.token = null; state.user = null; state.contacts = [];
    store.set(null);
    stopScan();
    $$('.stage').forEach(s => s.replaceChildren());
    closeAllModals();
    $('#login-form').hidden = false;
    $('#register-form').hidden = true;
    showAuth();
    $('#genie').hidden = true;
    if (message) toast(message, 'ok');
  }

  function showTab(name) {
    state.tab = name;
    $$('#view-app .page').forEach(p => { p.hidden = p.id !== 'tab-' + name; });
    $$('.tab').forEach(t => { if (t.dataset.tab === name) t.setAttribute('aria-current', 'page'); else t.removeAttribute('aria-current'); });
    if (name !== 'qr') stopScan();
    const loader = { home: loadHome, pay: loadContacts, contacts: loadContacts, split: loadSplit, insights: loadInsights, history: loadHistory }[name];
    if (loader) guarded(loader)();
    window.scrollTo({ top: 0 });
  }

  let pinResolve = null;
  function openModal(id) {
    const m = $('#' + id);
    m.hidden = false;
    const first = $('input', m);
    if (first) first.focus(); // immediately: a delayed focus could steal keystrokes from a fast typist
  }
  function closeModal(id) { $('#' + id).hidden = true; }
  function closeAllModals() {
    $$('.overlay').forEach(o => { o.hidden = true; });
    if (pinResolve) { pinResolve(null); pinResolve = null; }
    $('#pin-input').value = '';
  }

  function askPin(title, detail) {
    return new Promise(resolve => {
      if (pinResolve) pinResolve(null);
      pinResolve = resolve;
      $('#pin-title').textContent = title;
      $('#pin-detail').textContent = detail;
      $('#pin-input').value = '';
      $('#pin-error').textContent = '';
      openModal('modal-pin');
    });
  }
  function finishPin(value) {
    const r = pinResolve;
    pinResolve = null;
    $('#pin-input').value = '';
    closeModal('modal-pin');
    if (r) r(value);
  }

  // ------------------------------------------------------------------ session
  function setSession(res) {
    state.token = res.token;
    state.user = res.user;
    store.set(res.token);
    $$('#login-form input, #register-form input').forEach(i => { i.value = ''; });
    showApp();
    renderUser();
    showTab('home');
  }

  async function refreshMe() {
    const r = await api('GET', '/api/me');
    state.user = r.user;
    renderUser();
  }

  function renderUser() {
    const u = state.user;
    if (!u) return;
    $('#nav-avatar').textContent = initials(u.name);
    $('#nav-name').textContent = u.name.split(' ')[0];
    $('#home-name').textContent = 'Hello, ' + u.name.split(' ')[0];
    $('#home-date').textContent = new Date().toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
    $('#home-bal').textContent = inr(u.balance);
    $('#home-upi').textContent = u.upiId;
  }

  // ------------------------------------------------------------------ transactions
  function badge(text, kind, title) { return h('span', { class: 'badge ' + kind, title }, text); }

  function txnItem(t) {
    const credit = t.direction === 'CREDIT';
    const neutral = t.type === 'ADD_MONEY' || t.type === 'WITHDRAW';
    const name = h('div', { class: 'name' }, t.counterparty || '—');
    if (t.flagged) name.append(badge('Flagged', 'b-warn', t.flagReason));
    if (t.status === 'REVERSED') name.append(badge('Reversed', 'b-bad'));
    if (t.type === 'REVERSAL') name.append(badge('Reversal', 'b-info'));
    if (t.source === 'qr') name.append(badge('QR', 'b-info'));
    if (t.source === 'ai') name.append(badge('AI', 'b-info'));
    if (t.source === 'split') name.append(badge('Split', 'b-info'));
    const subBits = [t.note, t.type === 'TRANSFER' ? cap(t.category) : '', when(t.time)].filter(Boolean).join(' · ');
    const li = h('li', { class: 'item' },
      h('div', { class: 'dot ' + (neutral ? 'neutral' : credit ? 'in' : 'out') }, neutral ? '＋' : credit ? '↓' : '↑'),
      h('div', { class: 'grow' }, name, h('div', { class: 'sub' }, subBits),
        t.flagged ? h('div', { class: 'sub', style: 'color:var(--warn)' }, '⚠ ' + t.flagReason) : null),
      h('div', { style: 'text-align:right' },
        h('div', { class: credit ? 'amt-in' : 'amt-out', style: t.status === 'REVERSED' ? 'text-decoration:line-through;opacity:.6' : '' }, (credit ? '+' : '−') + inr(t.amount)),
        t.balanceAfter != null ? h('div', { class: 'sub' }, 'Bal ' + inr(t.balanceAfter)) : null,
        t.undoMsLeft > 0 ? h('button', { class: 'btn btn-danger btn-sm', style: 'margin-top:6px', type: 'button', 'data-action': 'undo', 'data-id': t.id, 'data-deadline': Date.now() + t.undoMsLeft }, 'Undo') : null));
    return li;
  }

  function fillList(ul, items, emptyText) {
    ul.replaceChildren(...(items.length ? items : [h('li', { class: 'empty' }, emptyText)]));
  }

  async function loadHome() {
    const [tx, an] = await Promise.all([api('GET', '/api/transactions?limit=5'), api('GET', '/api/analytics'), refreshMe()]);
    fillList($('#home-recent'), tx.transactions.map(txnItem), 'No transactions yet. Try paying someone!');
    $('#stat-spent').textContent = inr(an.spent);
    $('#stat-received').textContent = inr(an.received);
    $('#stat-flagged').textContent = an.flaggedCount;
    const box = $('#home-budget');
    box.replaceChildren();
    if (an.budget.level !== 'none') {
      const meter = h('div', { class: 'meter' }, h('div'));
      const text = h('p', { class: 'small muted' });
      fillBudget(meter, text, an.budget);
      box.append(h('div', { class: 'card', style: 'margin-bottom:22px' }, h('h3', {}, 'Monthly budget'), meter, text));
    }
  }

  async function loadHistory() {
    const r = await api('GET', '/api/transactions?limit=100&filter=' + encodeURIComponent(state.filter));
    fillList($('#history-list'), r.transactions.map(txnItem), 'Nothing here yet.');
  }

  // ------------------------------------------------------------------ countdown ticker (undo + QR expiry)
  function tick() {
    const now = Date.now();
    $$('[data-deadline]').forEach(el => {
      const left = Math.ceil((Number(el.dataset.deadline) - now) / 1000);
      if (left <= 0) el.remove(); else el.textContent = `Undo (${left}s)`;
    });
    $$('[data-expires]').forEach(el => {
      const left = Math.floor((Number(el.dataset.expires) - now) / 1000);
      const wrap = el.closest('[data-qrwrap]');
      if (left <= 0) {
        el.textContent = 'Expired. Generate a new code.';
        el.style.color = 'var(--danger)';
        if (wrap) $('.qr-img', wrap)?.classList.add('expired');
      } else el.textContent = `Expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
    });
  }
  setInterval(tick, 500);

  // ------------------------------------------------------------------ stage cards (preview / success)
  function setStage(stage, node) {
    stage.replaceChildren(...(node ? [node] : []));
    tick();
    if (node && stage.scrollIntoView) stage.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  function previewCard(spec) {
    const confirm = h('button', { class: 'btn btn-gold', type: 'button', disabled: !!spec.disabled }, spec.confirmLabel || 'Confirm with UPI PIN');
    confirm.addEventListener('click', async () => {
      confirm.disabled = true;
      try { await spec.onConfirm(); }
      catch (e) { toast(e.message || 'Something went wrong', 'bad'); }
      finally { if (confirm.isConnected) confirm.disabled = !!spec.disabled; }
    });
    return h('div', { class: 'preview' },
      h('h3', {}, spec.title),
      h('dl', { class: 'kv' }, spec.rows.flatMap(([k, v]) => [h('dt', {}, k), h('dd', {}, v)])),
      ...(spec.alerts || []).map(a => h('div', { class: 'alert ' + a.kind }, a.text)),
      h('div', { class: 'actions' }, confirm, h('button', { class: 'btn btn-ghost', type: 'button', onclick: () => spec.onCancel && spec.onCancel() }, 'Cancel')));
  }

  function successCard(res, headline, withUndo = true) {
    return h('div', { class: 'success-card' },
      h('div', { class: 'tick', 'aria-hidden': 'true' }, '✓'),
      h('h3', {}, headline),
      h('p', { class: 'muted small', style: 'margin:4px 0 12px' }, `Ref ${res.ref} · New balance ${inr(res.balance)}`),
      ...(res.flags || []).map(f => h('div', { class: 'alert warn' }, '⚠ ' + f)),
      res.budgetWarning ? h('div', { class: 'alert warn' }, res.budgetWarning) : null,
      withUndo && res.undoMsLeft > 0 ? h('button', { class: 'btn btn-danger btn-sm', type: 'button', 'data-action': 'undo', 'data-id': res.txnId, 'data-deadline': Date.now() + res.undoMsLeft }, 'Undo (30s)') : null);
  }

  const amountText = n => inr(n);

  // ------------------------------------------------------------------ PAY
  async function previewPay(target, amount, note, source, stage) {
    stage = stage || $('#pay-stage');
    const body = { ...target, amount: String(amount), note: note || '' };
    const p = await api('POST', '/api/pay/preview', body);
    const key = uuid();
    const alerts = (p.flags || []).map(f => ({ kind: 'warn', text: '⚠ ' + f }));
    if (!p.sufficient) alerts.push({ kind: 'bad', text: `Not enough balance (you have ${inr(p.balance)}).` });
    setStage(stage, previewCard({
      title: 'Review payment',
      rows: [['To', h('span', {}, p.payee.name, h('br'), h('span', { class: 'mono muted small' }, p.payee.upiId))],
        ['Amount', h('span', { class: 'big-amt' }, amountText(p.amount))],
        ['Note', p.note || '—'], ['Category', h('span', { class: 'badge b-info', style: 'margin:0' }, cap(p.category))]],
      alerts, disabled: !p.sufficient,
      onCancel: () => setStage(stage, null),
      onConfirm: async () => {
        const pin = await askPin('Approve payment', `${amountText(p.amount)} to ${p.payee.name}`);
        if (!pin) return;
        const res = await api('POST', '/api/pay', { ...body, source, pin, idempotencyKey: key });
        setStage(stage, successCard(res, `${amountText(res.amount)} sent to ${res.payee}`));
        refreshMe();
      },
    }));
  }

  async function runAi(text) {
    const r = await api('POST', '/api/ai/parse', { text });
    const onSplit = r.intent === 'SPLIT';
    if (r.intent === 'UNKNOWN') {
      const stage = state.tab === 'split' ? $('#split-stage') : $('#pay-stage');
      setStage(stage, h('div', { class: 'alert info' }, r.message));
      return;
    }
    if (onSplit) { if (state.tab !== 'split') showTab('split'); return renderAiSplit(r); }
    if (state.tab !== 'pay') showTab('pay');
    const stage = $('#pay-stage');
    const go = m => {
      if (!m.registered) return setStage(stage, h('div', { class: 'alert bad' }, `${m.name} has not joined JavaPay yet, so they cannot receive money.`));
      return guarded(() => previewPay({ contactId: m.contactId }, r.amount, r.note, 'ai', stage))();
    };
    if (!r.matches.length) return setStage(stage, h('div', { class: 'alert bad' }, r.message));
    if (r.needsChoice) {
      setStage(stage, h('div', { class: 'preview' },
        h('h3', {}, `Which "${r.recipient}" do you mean?`),
        h('p', { class: 'muted small' }, `Paying ${amountText(r.amount)}${r.note ? ' for ' + r.note : ''}`),
        h('div', { class: 'choice-btns' }, r.matches.map(m => h('button', { class: 'btn btn-ghost', type: 'button', onclick: () => go(m) }, `${m.name} ···${m.phone.slice(-4)}`)))));
      return;
    }
    go(r.matches[0]);
  }

  function renderAiSplit(r) {
    const stage = $('#split-stage');
    const picks = [];
    let blocked = null;
    const rows = r.participants.map(p => {
      if (!p.matches.length) { blocked = `No saved contact matches "${p.query}". Add them in Contacts first.`; return [p.query, h('span', { style: 'color:var(--danger)' }, 'Not found')]; }
      const unreg = p.matches.filter(m => m.registered);
      if (!unreg.length) { blocked = `${p.matches[0].name} has not joined JavaPay yet.`; return [p.query, h('span', { style: 'color:var(--danger)' }, 'Not on JavaPay')]; }
      if (p.needsChoice || unreg.length > 1) {
        const sel = h('select', { 'aria-label': 'Which ' + p.query, style: 'padding:6px;border-radius:8px;border:1.5px solid var(--line)' },
          unreg.map(m => h('option', { value: m.contactId }, `${m.name} ···${m.phone.slice(-4)}`)));
        picks.push(() => Number(sel.value));
        return [p.query, sel];
      }
      picks.push(() => unreg[0].contactId);
      return [p.query, unreg[0].name];
    });
    const alerts = [];
    if (blocked) alerts.push({ kind: 'bad', text: blocked });
    setStage(stage, previewCard({
      title: 'Review split',
      rows: [['Total', h('span', { class: 'big-amt' }, inr(r.total))], ['What for', r.note || '—'], ...rows,
        ['Each pays', `${inr(r.perHead)} (${r.people} people)`], ['Your share', inr(r.yourShare)]],
      alerts, disabled: !!blocked, confirmLabel: 'Send requests',
      onCancel: () => setStage(stage, null),
      onConfirm: async () => {
        const res = await api('POST', '/api/split/create', { total: String(r.total), note: r.note || '', participants: picks.map(f => ({ contactId: f() })) });
        setStage(stage, h('div', { class: 'success-card' }, h('div', { class: 'tick' }, '✓'), h('h3', {}, res.message),
          h('p', { class: 'muted small' }, `${inr(res.perHead)} each · your share ${inr(res.yourShare)}`)));
        loadSplit().catch(() => {});
      },
    }));
  }

  // ------------------------------------------------------------------ contacts
  function fillPaySelect() {
    const sel = $('#pay-who');
    const prev = sel.value;
    sel.replaceChildren(h('option', { value: '' }, 'Choose a contact'),
      ...state.contacts.filter(c => c.registered).map(c => h('option', { value: c.id }, `${c.name} ···${c.phone.slice(-4)}`)),
      h('option', { value: 'upi' }, 'Someone else (enter UPI ID)'));
    if ([...sel.options].some(o => o.value === prev)) sel.value = prev;
    $('#pay-upi-wrap').hidden = sel.value !== 'upi';
  }

  function fillSplitPeople() {
    const box = $('#split-people');
    const checked = new Set($$('input:checked', box).map(i => i.value));
    const reg = state.contacts.filter(c => c.registered);
    box.replaceChildren(...(reg.length ? reg.map(c => h('label', {},
      h('input', { type: 'checkbox', value: c.id, checked: checked.has(String(c.id)) }), c.name, h('span', { class: 'muted small' }, ' ···' + c.phone.slice(-4))))
      : [h('p', { class: 'muted small' }, 'Add friends who have JavaPay accounts in Contacts first.')]));
    updateSplitCalc();
  }

  function updateSplitCalc() {
    const total = Math.round(parseFloat($('#split-total').value) * 100);
    const n = $$('#split-people input:checked').length + 1;
    const out = $('#split-calc');
    if (!total || n < 2) { out.textContent = n < 2 ? 'Pick at least one person.' : ''; return; }
    const per = Math.floor(total / n);
    out.textContent = `Each person pays ${inr(per / 100)}; your share is ${inr((total - per * (n - 1)) / 100)} (${n} people).`;
  }

  async function loadContacts() {
    const r = await api('GET', '/api/contacts');
    state.contacts = r.contacts;
    fillList($('#contact-list'), r.contacts.map(c => h('li', { class: 'item' },
      h('div', { class: 'dot neutral' }, initials(c.name)),
      h('div', { class: 'grow' }, h('div', { class: 'name' }, c.name, c.registered ? badge('On JavaPay', 'b-ok') : badge('Not joined', 'b-warn')),
        h('div', { class: 'sub mono' }, c.registered ? c.upiId : c.phone)),
      c.registered ? h('button', { class: 'btn btn-navy btn-sm', type: 'button', 'data-action': 'pay-contact', 'data-id': c.id }, 'Pay') : null,
      h('button', { class: 'btn btn-ghost btn-sm', type: 'button', 'data-action': 'del-contact', 'data-id': c.id, 'data-name': c.name, 'aria-label': 'Remove ' + c.name }, '✕'))),
      'No contacts yet. Add someone on the left.');
    fillPaySelect();
    fillSplitPeople();
  }

  // ------------------------------------------------------------------ QR
// ------------------------------------------------------------------ QR
let scan = { scanner: null, running: false };

function stopScan() {
  const scanner = scan.scanner;
  scan = { scanner: null, running: false };

  const stage = $('#scan-stage');
  if (stage) stage.replaceChildren();

  const start = $('#scan-start');
  const stop = $('#scan-stop');

  if (start) start.hidden = false;
  if (stop) stop.hidden = true;

  if (scanner) {
    scanner.stop()
      .catch(() => {})
      .finally(() => scanner.clear().catch(() => {}));
  }
}

async function startScan() {
  if (!window.Html5Qrcode) {
    toast('QR scanner library could not be loaded. Refresh the page.', 'bad');
    return;
  }

  const stage = $('#scan-stage');
  const reader = h('div', { id: 'qr-reader' });

  stage.replaceChildren(reader);

  const scanner = new Html5Qrcode('qr-reader');
  scan = { scanner, running: false };

  try {
    await scanner.start(
      { facingMode: 'environment' },
      {
        fps: 10,
        qrbox: { width: 250, height: 250 }
      },
      rawValue => {
        if (!scan.running) return;

        if (!/^upi:\/\//i.test(rawValue)) {
          toast('This QR code is not a valid UPI payment code.', 'bad');
          return;
        }

        stopScan();
        guarded(() => handleScanned(rawValue))();
      },
      () => {}
    );

    scan.running = true;
    $('#scan-start').hidden = true;
    $('#scan-stop').hidden = false;

  } catch (error) {
    stopScan();
    toast(
      'Could not open camera. Check browser camera permission.',
      'bad'
    );
    console.error('Camera error:', error);
  }
}

async function scanImage(file) {
  if (!file) return;

  if (!window.Html5Qrcode) {
    throw new Error('QR scanner library could not be loaded.');
  }

  const stage = $('#scan-stage');

  stage.replaceChildren(h('div', { id: 'qr-reader' }));

  const scanner = new Html5Qrcode('qr-reader');

  try {
    const rawValue = await scanner.scanFile(file, true);

    if (!/^upi:\/\//i.test(rawValue)) {
      throw new Error('This image does not contain a valid UPI payment QR code.');
    }

    await handleScanned(rawValue);
  } catch (error) {
    throw new Error(error.message || 'Could not read the QR image.');
  } finally {
    scanner.clear().catch(() => {});
    stage.replaceChildren();
  }
}
  // ------------------------------------------------------------------ split
  async function loadSplit() {
    const [r] = await Promise.all([api('GET', '/api/split'), state.contacts.length ? null : loadContacts()]);
    fillSplitPeople();
    fillList($('#split-owed'), r.owed.map(o => h('li', { class: 'item' },
      h('div', { class: 'dot out' }, '↑'),
      h('div', { class: 'grow' }, h('div', { class: 'name' }, `${o.from} asks ${inr(o.amount)}`), h('div', { class: 'sub' }, `${o.note || 'Split bill'} · ${when(o.time)}`)),
      o.status === 'PENDING'
        ? h('button', { class: 'btn btn-gold btn-sm', type: 'button', 'data-action': 'split-pay', 'data-id': o.shareId, 'data-amount': o.amount, 'data-from': o.from }, 'Pay')
        : badge('Paid', 'b-ok'))), 'No requests for you.');
    fillList($('#split-created'), r.created.map(s => h('li', { class: 'item', style: 'align-items:flex-start' },
      h('div', { class: 'dot neutral' }, '➗'),
      h('div', { class: 'grow' }, h('div', { class: 'name' }, `${inr(s.total)} · ${s.note || 'Split bill'}`), h('div', { class: 'sub' }, when(s.time)),
        h('div', { style: 'margin-top:6px;display:flex;flex-wrap:wrap;gap:6px' }, s.shares.map(x => h('span', { class: 'badge ' + (x.status === 'PAID' ? 'b-ok' : 'b-warn'), style: 'margin:0' }, `${x.name} ${inr(x.amount)} ${x.status === 'PAID' ? '✓' : '…'}`)))))),
      'You have not split any bills yet.');
  }

  // ------------------------------------------------------------------ insights
  function fillBudget(meter, text, b) {
    meter.hidden = b.level === 'none';
    meter.className = 'meter' + (b.level === 'warn' ? ' warn' : b.level === 'over' ? ' over' : '');
    $('div', meter).style.width = Math.min(100, b.percent) + '%';
    if (b.level === 'none') { text.textContent = 'Set a monthly limit to get warnings at 80% and 100%.'; return; }
    const left = b.limit - b.used;
    text.textContent = `${inr(b.used)} of ${inr(b.limit)} spent (${b.percent}%). ` + (left >= 0 ? `${inr(left)} left this month.` : `Over budget by ${inr(-left)}.`);
  }

  async function loadInsights() {
    const a = await api('GET', '/api/analytics');
    $('#in-month').textContent = `${a.month}: spent ${inr(a.spent)}, received ${inr(a.received)}${a.flaggedCount ? `, ${a.flaggedCount} flagged payment(s)` : ''}.`;
    fillBudget($('#budget-meter'), $('#budget-text'), a.budget);
    const max = Math.max(...a.byCategory.map(c => Number(c.amount)), 1);
    $('#in-cats').replaceChildren(...(a.byCategory.length ? a.byCategory.map(c => h('div', { class: 'bar-row' },
      h('span', {}, cap(c.category)), h('div', { class: 'bar-track' }, h('div', { class: 'bar-fill', style: `width:${Math.max(3, Number(c.amount) / max * 100)}%` })), h('span', { class: 'v' }, inr(c.amount))))
      : [h('p', { class: 'empty' }, 'No spending this month yet.')]));
    fillList($('#in-payees'), a.topPayees.map(p => h('li', { class: 'item' }, h('div', { class: 'dot neutral' }, initials(p.name)),
      h('div', { class: 'grow' }, h('div', { class: 'name' }, p.name), h('div', { class: 'sub' }, `${p.count} payment${p.count > 1 ? 's' : ''}`)), h('div', { class: 'amt-out' }, inr(p.amount)))), 'No payments this month.');
    const mmax = Math.max(...a.monthly.map(m => Number(m.spent)), 1);
    $('#in-months').replaceChildren(...a.monthly.map((m, i) => h('div', { class: 'col' }, h('b', {}, Number(m.spent) ? compact(m.spent) : ''),
      h('div', { class: 'stick' + (i === a.monthly.length - 1 ? ' now' : ''), style: `height:${Number(m.spent) / mmax * 100}%` }), m.month)));
    if (!$('#st-from').value) {
      const d = new Date(), pad = n => String(n).padStart(2, '0');
      $('#st-from').value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-01`;
      $('#st-to').value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    }
  }

  async function downloadStatement(from, to) {
    const res = await fetch(`/api/statement?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, { headers: { Authorization: 'Bearer ' + state.token } });
    if (!res.ok) { const d = await res.json().catch(() => ({})); throw new Error(d.message || 'Could not create the statement'); }
    const url = URL.createObjectURL(await res.blob());
    const a = h('a', { href: url, download: `javabank-statement-${from}-to-${to}.pdf` });
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
  }

  // ------------------------------------------------------------------ Genie assistant
  function genieSay(text, who) {
    const box = $('#genie-msgs');
    box.append(h('div', { class: 'msg ' + who }, text));
    box.scrollTop = box.scrollHeight;
  }

  function genieLocal(q) {
    q = q.toLowerCase();
    if (/balance|how much/.test(q)) return `You have ${inr(state.user.balance)} in your wallet.`;
    if (/undo|wrong person|mistake/.test(q)) return 'Open History and tap Undo within 30 seconds of paying. It works only if the receiver has not spent the money.';
    if (/qr/.test(q)) return 'Go to the QR tab. Codes with an amount expire and can be used once, so a screenshot cannot be reused later.';
    if (/pin/.test(q)) return 'Your UPI PIN is asked for every payment. Five wrong tries lock it for 15 minutes. Change it in Settings.';
    if (/limit/.test(q)) return 'Limits: ₹1,00,000 per payment and per day, ₹10,000 per simulated top-up.';
    return 'I can pay or split for you, e.g. "Pay 200 to Arun for lunch" or "Split 600 among me, Arun, Meena". You can also ask about your balance, undo, QR or limits.';
  }

  // ------------------------------------------------------------------ event wiring
  const actions = {
    'show-register': () => { $('#login-form').hidden = true; $('#register-form').hidden = false; $('#reg-name').focus(); },
    'show-login': () => { $('#register-form').hidden = true; $('#login-form').hidden = false; $('#login-id').focus(); },
    tab: a => showTab(a.dataset.tab),
    logout: async () => { try { await api('POST', '/api/logout', {}); } catch { /* already out */ } localSignOut('Signed out.'); },
    'open-settings': () => openModal('modal-settings'),
    'open-add': () => openModal('modal-add'),
    'open-withdraw': () => openModal('modal-withdraw'),
    'close-modal': a => closeModal(a.closest('.overlay').id),
    'pin-cancel': () => finishPin(null),
    'copy-upi': async () => { try { await navigator.clipboard.writeText(state.user.upiId); toast('UPI ID copied'); } catch { toast('Copy is not available here', 'bad'); } },
    'copy-text': async a => { try { await navigator.clipboard.writeText(a.dataset.text); toast('Copied'); } catch { toast('Copy is not available here', 'bad'); } },
    'ai-example': a => { const i = $('#ai-input'); i.value = a.dataset.text; i.focus(); },
    'scan-start': () => guarded(startScan)(),
    'scan-stop': () => stopScan(),
    'pay-contact': a => { showTab('pay'); $('#pay-who').value = a.dataset.id; $('#pay-upi-wrap').hidden = true; setTimeout(() => $('#pay-amount').focus(), 60); },
    'del-contact': guarded(async a => {
      if (!confirm(`Remove ${a.dataset.name} from your contacts?`)) return;
      await api('DELETE', '/api/contacts?id=' + encodeURIComponent(a.dataset.id));
      toast('Contact removed');
      await loadContacts();
    }),
    filter: a => {
      state.filter = a.dataset.filter;
      $$('[data-action=filter]').forEach(b => b.setAttribute('aria-pressed', String(b === a)));
      guarded(loadHistory)();
    },
    undo: guarded(async a => {
      a.disabled = true;
      let r;
      try { r = await api('POST', '/api/undo', { txnId: Number(a.dataset.id) }); }
      catch (e) { a.disabled = false; throw e; }
      toast('Payment reversed. Your money is back.');
      const card = a.closest('.success-card');
      if (card) card.replaceChildren(h('div', { class: 'alert ok', style: 'margin:0' }, `Payment reversed. Your balance is ${inr(r.balance)}.`));
      await refreshMe();
      if (state.tab === 'history') await loadHistory();
      if (state.tab === 'home') await loadHome();
    }),
    'split-pay': guarded(async a => {
      const pin = await askPin('Pay your share', `${inr(a.dataset.amount)} to ${a.dataset.from}`);
      if (!pin) return;
      const r = await api('POST', '/api/split/pay', { shareId: Number(a.dataset.id), pin });
      toast(`Paid ${inr(r.amount)} to ${r.payee}`);
      await refreshMe();
      await loadSplit();
    }),
    'genie-toggle': () => {
      const g = $('#genie');
      g.hidden = !g.hidden;
      if (!g.hidden) {
        if (!$('#genie-msgs').children.length) genieSay('Namaste! Tell me what to pay, like "Pay 200 to Arun for lunch". I will show it to you to confirm first.', 'bot');
        $('#genie-input').focus();
      }
    },
  };

  const forms = {
    login: async f => {
      setSession(await api('POST', '/api/login', { identifier: $('#login-id').value.trim(), password: $('#login-pw').value }));
    },
    register: async f => {
      setSession(await api('POST', '/api/register', { name: $('#reg-name').value.trim(), phone: $('#reg-phone').value.trim(), password: $('#reg-pw').value, pin: $('#reg-pin').value }));
      toast('Account created. ₹1,000 of simulated money is ready to use.');
    },
    ai: async f => {
      const input = $('input', f);
      const text = input.value.trim();
      if (!text) return;
      await runAi(text);
    },
    'pay-manual': async f => {
      const who = $('#pay-who').value;
      if (!who) throw new Error('Choose who to pay.');
      const target = who === 'upi' ? { upiId: $('#pay-upi').value.trim() } : { contactId: Number(who) };
      if (who === 'upi' && !target.upiId) throw new Error('Enter a UPI ID.');
      await previewPay(target, $('#pay-amount').value, $('#pay-note').value.trim(), 'manual');
    },
    'qr-create': async f => {
      const ttl = Number($('#qr-ttl').value);
      const body = { ttlMinutes: ttl, note: $('#qr-note').value.trim() };
      if ($('#qr-amount').value && ttl > 0) body.amount = $('#qr-amount').value;
      const r = await api('POST', '/api/qr/create', body);
      const wrap = h('div', { 'data-qrwrap': '', style: 'text-align:center' },
        h('img', { class: 'qr-img', src: r.qr, alt: `QR code to pay ${state.user.name}` }),
        h('p', { style: 'font-weight:600' }, r.amount ? `Pay ${inr(r.amount)}` : 'Pays any amount'),
        r.expiresAt ? h('p', { class: 'small', 'data-expires': r.expiresAt }, '') : h('p', { class: 'small muted' }, 'Permanent code. The payer chooses the amount.'),
        h('div', { class: 'actions', style: 'justify-content:center' },
          h('a', { class: 'btn btn-ghost btn-sm', href: r.qr, download: 'javabank-pay-qr.png' }, 'Download'),
          h('button', { class: 'btn btn-ghost btn-sm', type: 'button', 'data-action': 'copy-text', 'data-text': r.payload }, 'Copy UPI link')));
      setStage($('#qr-out'), wrap);
    },
    'scan-paste': async f => {
      const v = $('#scan-text').value.trim();
      if (!v) return;
      await handleScanned(v);
      $('#scan-text').value = '';
    },
    'contact-add': async f => {
      const r = await api('POST', '/api/contacts', { name: $('#c-name').value.trim(), phone: $('#c-phone').value.trim() });
      toast(r.message);
      f.reset();
      await loadContacts();
    },
    'split-create': async f => {
      const ids = $$('#split-people input:checked').map(i => ({ contactId: Number(i.value) }));
      if (!ids.length) throw new Error('Pick at least one person to split with.');
      const r = await api('POST', '/api/split/create', { total: $('#split-total').value, note: $('#split-note').value.trim(), participants: ids });
      toast(`${r.message}. Each pays ${inr(r.perHead)}.`);
      f.reset();
      fillSplitPeople();
      await loadSplit();
    },
    budget: async f => {
      const v = $('#budget-amount').value.trim();
      if (v === '') throw new Error('Enter an amount, or 0 to turn the budget off.');
      const r = await api('POST', '/api/budget', { amount: v });
      toast(r.message);
      $('#budget-amount').value = '';
      await loadInsights();
    },
    statement: async f => { await downloadStatement($('#st-from').value, $('#st-to').value); toast('Statement downloaded'); },
    pin: async f => {
      const v = $('#pin-input').value;
      if (!/^\d{4,6}$/.test(v)) { $('#pin-error').textContent = 'Enter your 4 to 6 digit PIN.'; return; }
      finishPin(v);
    },
    'add-money': async f => {
      const r = await api('POST', '/api/wallet/add', { amount: $('#add-amount').value });
      toast(`Added. New balance ${inr(r.balance)}`);
      f.reset(); closeModal('modal-add');
      await refreshMe();
      if (state.tab === 'home') await loadHome();
    },
    withdraw: async f => {
      const r = await api('POST', '/api/wallet/withdraw', { amount: $('#wd-amount').value, pin: $('#wd-pin').value });
      toast(`Withdrawn. New balance ${inr(r.balance)}`);
      f.reset(); closeModal('modal-withdraw');
      await refreshMe();
      if (state.tab === 'home') await loadHome();
    },
    'change-pw': async f => {
      const r = await api('POST', '/api/change-password', { oldPassword: $('#cp-old').value, newPassword: $('#cp-new').value });
      state.token = r.token; store.set(r.token);
      toast(r.message);
      f.reset(); closeModal('modal-settings');
    },
    'change-pin': async f => {
      const r = await api('POST', '/api/change-pin', { oldPin: $('#pin-old').value, newPin: $('#pin-new').value });
      toast(r.message);
      f.reset(); closeModal('modal-settings');
    },
    genie: async f => {
      const input = $('#genie-input');
      const text = input.value.trim();
      if (!text) return;
      input.value = '';
      genieSay(text, 'me');
      if (/\b(pay|send|transfer|give|split)\b/i.test(text) && /\d/.test(text)) {
        genieSay('On it. I am preparing that for you to confirm.', 'bot');
        try { await runAi(text); genieSay('Ready. Check the confirmation card on the screen. Nothing is paid until you enter your PIN.', 'bot'); }
        catch (e) { genieSay(e.message, 'bot'); }
      } else genieSay(genieLocal(text), 'bot');
    },
  };

  document.addEventListener('click', e => {
    const a = e.target.closest('[data-action]');
    if (a && actions[a.dataset.action]) { e.preventDefault(); actions[a.dataset.action](a, e); return; }
    if (e.target.classList && e.target.classList.contains('overlay')) {
      if (e.target.id === 'modal-pin') finishPin(null); else closeModal(e.target.id);
    }
  });

  document.addEventListener('submit', e => {
    const f = e.target.closest('form[data-form]');
    if (!f) return;
    e.preventDefault();
    const fn = forms[f.dataset.form];
    if (fn) guarded(() => fn(f), f)();
  });

  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
      if (!$('#modal-pin').hidden) finishPin(null);
      else $$('.overlay').forEach(o => { o.hidden = true; });
    }
  });

  document.addEventListener('change', e => {
    if (e.target.id === 'pay-who') $('#pay-upi-wrap').hidden = e.target.value !== 'upi';
    if (e.target.id === 'scan-file') { guarded(() => scanImage(e.target.files[0]))().finally(() => { e.target.value = ''; }); }
    if (e.target.closest && e.target.closest('#split-people')) updateSplitCalc();
  });
  document.addEventListener('input', e => { if (e.target.id === 'split-total') updateSplitCalc(); });

  // ------------------------------------------------------------------ boot
  async function boot() {
  $('#scan-support').textContent = window.Html5Qrcode
  ? ''
  : 'QR scanner library is not loaded. Refresh the page or upload a QR image.';
     const saved = store.get();
    if (!saved) { showAuth(); return; }
    state.token = saved;
    try {
      state.user = (await api('GET', '/api/me')).user;
      showApp();
      renderUser();
      showTab('home');
    } catch { localSignOut(); }
  }
  boot();
})();
