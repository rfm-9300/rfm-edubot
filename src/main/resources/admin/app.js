/* CRM · Painel de Gestão — app.js · vanilla JS, backend API */

/* ----------  API  ---------- */

let token = localStorage.getItem('adminToken') || '';
const tenantSlug = new URLSearchParams(location.search).get('tenant');

if (!tenantSlug) {
  location.replace('/backoffice/');
  throw new Error('Missing tenant slug');
}

const API_BASE = `/admin/api/tenants/${encodeURIComponent(tenantSlug)}`;

// CRM copy comes from the shared i18n catalogs (catalog.*.js). Locale follows the tenant (resolved
// in init() from the tenant API), with the usual localStorage/browser fallbacks. `T` is a live proxy.
const T = I18N.section('admin');
const APP = I18N.section('app');

async function api(path, options = {}) {
  const headers = { ...(options.body ? { 'Content-Type': 'application/json' } : {}), Authorization: `Bearer ${token}` };
  const res = await fetch(API_BASE + path, {
    ...options,
    headers: { ...headers, ...(options.headers || {}) },
  });
  if (res.status === 401) {
    localStorage.removeItem('adminToken');
    token = '';
    renderLogin();
    throw new Error('unauthorized');
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  if (res.status === 204) return null;
  return res.json();
}

/* ----------  STATUS MAPS  ---------- */

// API status codes (never translated — the labels come from the catalog: T.quoteStatus / T.invoiceStatus).
const QUOTE_STATUSES   = ['PENDENTE', 'ACEITO'];
const INVOICE_STATUSES = ['PENDING', 'PAID', 'OVERDUE', 'CANCELLED'];
const quoteStatusLabel   = code => (T.quoteStatus && T.quoteStatus[code]) || code;
const invoiceStatusLabel = code => (T.invoiceStatus && T.invoiceStatus[code]) || code;

/* ----------  STATE (client-side cache)  ---------- */

let state = {
  clients: [], catalog: [], quotes: [], invoices: [],
  bookings: [], bookingServices: [], bookingAvailability: [],
  bookingWeekStart: null, tenantTimezone: 'Europe/Lisbon',
};

/* ----------  NORMALIZE  ---------- */

const normalizeClient = c => ({
  id: c.id, numero: c.number || c.id, nome: c.name, telefone: c.phone, morada: c.address || '',
  criadoEm: (c.createdAt || '').slice(0, 10),
});

const normalizeQuote = q => ({
  id: q.id, numero: q.number,
  clienteId: q.clientId, clienteNome: q.clientName || '—',
  statusApi: q.status,
  status: quoteStatusLabel(q.status),
  validoAte: q.validUntil || '',
  criadoEm: (q.createdAt || '').slice(0, 10),
  totalEur: q.totalEur || 0,
  hasPdf: q.hasPdf || false,
});

const normalizeInvoice = i => ({
  id: i.id, numero: i.number,
  clienteId: i.clientId, clienteNome: i.clientName || '—',
  statusApi: i.status,
  status: invoiceStatusLabel(i.status),
  vencimento: i.dueDate || '',
  criadoEm: (i.createdAt || '').slice(0, 10),
  totalEur: i.totalEur || 0,
  hasPdf: i.hasPdf || false,
});

const normalizeCatalogItem = c => ({
  id: c.id, tipo: c.type, categoria: c.category,
  descricao: c.description, unidade: c.unit,
  preco: c.defaultUnitPriceEur || 0,
});

/* ----------  DATA LOADING  ---------- */

async function loadAll() {
  const [clients, catalog, quotes, invoices] = await Promise.all([
    api('/clients'),
    api('/standard-items'),
    api('/quotes'),
    api('/invoices'),
  ]);
  state.clients  = clients.map(normalizeClient);
  state.catalog  = catalog.map(normalizeCatalogItem);
  state.quotes   = quotes.map(normalizeQuote);
  state.invoices = invoices.map(normalizeInvoice);
  await reloadBookings().catch(() => {
    state.bookings = [];
    state.bookingServices = [];
    state.bookingAvailability = [];
  });
}

async function reloadClients()  { state.clients  = (await api('/clients')).map(normalizeClient); }
async function reloadCatalog()  { state.catalog  = (await api('/standard-items')).map(normalizeCatalogItem); }
async function reloadQuotes()   { state.quotes   = (await api('/quotes')).map(normalizeQuote); }
async function reloadInvoices() { state.invoices = (await api('/invoices')).map(normalizeInvoice); }
async function reloadBookings() {
  if (!state.bookingWeekStart) state.bookingWeekStart = startOfWeekAdmin();
  const from = state.bookingWeekStart.toISOString();
  const to = addDaysAdmin(state.bookingWeekStart, 7).toISOString();
  const [bookings, services, availability] = await Promise.all([
    api(`/bookings?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
    api('/bookings/services'),
    api('/bookings/availability'),
  ]);
  state.bookings = bookings;
  state.bookingServices = services;
  state.bookingAvailability = availability;
}

/* ----------  HELPERS  ---------- */

const $  = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

const fmtEUR = n =>
  new Intl.NumberFormat('pt-PT', { style: 'currency', currency: 'EUR' }).format(Number(n || 0));

const fmtDate = iso => {
  if (!iso) return '—';
  const [y, m, d] = iso.split('-');
  if (!y || !m || !d) return iso;
  return `${d}/${m}/${y}`;
};

const slugify = s => (s || '')
  .normalize('NFD').replace(/[̀-ͯ]/g, '')
  .toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

function escapeHTML(s = '') {
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/* ----------  STATUS PILLS  ---------- */

// Color is driven by the API status code; the visible text is the already-localized label.
function pill(statusApi, label) {
  const map = {
    PENDENTE: 'pill--warn', ACEITO: 'pill--ok',
    PENDING: 'pill--warn', PAID: 'pill--ok', OVERDUE: 'pill--bad', CANCELLED: '',
  };
  return `<span class="pill ${map[statusApi] || ''}">${escapeHTML(label)}</span>`;
}

function pdfLink(id, type, hasPdf, label) {
  if (!hasPdf) {
    return `<span class="pdf pdf--ghost">
      <svg width="11" height="13" viewBox="0 0 11 13"><path d="M1 1 H7 L10 4 V12 H1 Z" fill="none" stroke="currentColor" stroke-width="1"/></svg>
      ${escapeHTML(T.pdfPending)}</span>`;
  }
  return `<button class="pdf" type="button" data-pdf-url="${API_BASE}/${type}/${encodeURIComponent(id)}/pdf">
    <svg width="11" height="13" viewBox="0 0 11 13"><path d="M1 1 H7 L10 4 V12 H1 Z" fill="none" stroke="currentColor" stroke-width="1.2"/><text x="5.5" y="10" font-family="monospace" font-size="3.6" text-anchor="middle" fill="currentColor">PDF</text></svg>
    ${escapeHTML(label)}</button>`;
}

async function openPdf(url) {
  const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const blob = await res.blob();
  const objectUrl = URL.createObjectURL(blob);
  window.open(objectUrl, '_blank');
  setTimeout(() => URL.revokeObjectURL(objectUrl), 60000);
}

/* ----------  TOAST + CONFIRM  ---------- */

let toastTimer;
function toast(msg) {
  const el = $('#toast');
  el.innerHTML = `<span class="toast__dot"></span><span>${escapeHTML(msg)}</span>`;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 2800);
}

function confirmDialog({ title, body, okLabel = T.delete, danger = true }) {
  return new Promise(resolve => {
    const root = $('#confirm');
    $('#confirm-title').textContent = title;
    $('#confirm-body').textContent  = body;
    const ok = $('#confirm-ok');
    ok.textContent = okLabel;
    ok.classList.toggle('btn--danger',  danger);
    ok.classList.toggle('btn--primary', !danger);
    root.hidden = false;
    const cleanup = v => {
      root.hidden = true;
      ok.removeEventListener('click', onOk);
      $$('[data-confirm-cancel]', root).forEach(b => b.removeEventListener('click', onCancel));
      resolve(v);
    };
    const onOk     = () => cleanup(true);
    const onCancel = () => cleanup(false);
    ok.addEventListener('click', onOk, { once: true });
    $$('[data-confirm-cancel]', root).forEach(b => b.addEventListener('click', onCancel, { once: true }));
  });
}

function renderLogin() {
  $('#nav').hidden = true;
  $('#brand-name').textContent = 'CRM';
  $('#brand-sub').textContent = `${tenantSlug} · login`;
  $('#crumb-leaf').textContent = T.login.crumb;
  $('#btn-new').hidden = true;
  $('#btn-export').hidden = true;
  $('#search').disabled = true;
  $('#view').innerHTML = `<div class="auth"><div class="auth__card"><div class="auth__mark">CRM</div><p class="auth__eyebrow">${escapeHTML(T.login.eyebrow({ tenant: tenantSlug }))}</p><h1 class="auth__title">${escapeHTML(T.login.title)}</h1><p class="auth__desc">${escapeHTML(T.login.desc)}</p><form class="form" id="login-form"><div class="form__row"><label class="lbl" for="password">${escapeHTML(T.login.password)}</label><input class="inp" id="password" type="password" autocomplete="current-password" required /></div><button class="btn btn--primary" type="submit">${escapeHTML(T.login.submit)}</button></form></div></div>`;
  $('#login-form').addEventListener('submit', async e => {
    e.preventDefault();
    try {
      const res = await fetch('/admin/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: $('#password').value }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const body = await res.json();
      token = body.token;
      localStorage.setItem('adminToken', token);
      $('#nav').hidden = false;
      $('#btn-new').hidden = false;
      $('#btn-export').hidden = false;
      $('#search').disabled = false;
      await init();
    } catch (err) {
      toast(T.login.invalid);
    }
  });
}

/* ----------  DRAWER  ---------- */

function openDrawer({ eyebrow, title, body, wide = false, onSave, saveLabel = T.save }) {
  const root = $('#drawer');
  $('#drawer-eyebrow').textContent = eyebrow;
  $('#drawer-title').textContent   = title;
  root.querySelector('.drawer__panel').classList.toggle('drawer__panel--wide', wide);

  const host = $('#drawer-body');
  host.innerHTML = '';
  if (typeof body === 'string') host.innerHTML = body;
  else host.appendChild(body);

  const foot = document.createElement('div');
  foot.className = 'drawer__foot';
  foot.innerHTML = `
    <button class="btn btn--ghost" data-close>${escapeHTML(T.cancel)}</button>
    <button class="btn btn--accent" id="drawer-save">${escapeHTML(saveLabel)}</button>
  `;
  host.appendChild(foot);
  root.hidden = false;

  const close = () => {
    root.hidden = true;
    $$('[data-close]', root).forEach(b => b.removeEventListener('click', close));
  };
  $$('[data-close]', root).forEach(b => b.addEventListener('click', close));

  const saveBtn = $('#drawer-save');
  saveBtn.addEventListener('click', async () => {
    saveBtn.disabled    = true;
    saveBtn.textContent = T.saving;
    const ok = await onSave?.();
    if (ok !== false) {
      close();
    } else {
      saveBtn.disabled    = false;
      saveBtn.textContent = saveLabel;
    }
  });

  setTimeout(() => { host.querySelector('input,select,textarea')?.focus(); }, 50);
  return { close };
}

/* ============================================================
   ROUTING / SHELL
   ============================================================ */

// Behaviour only — title/desc/newLabel come from the catalog (T.tabs[tab]) so they follow the locale.
const TABS = {
  clientes:   { render: renderClientes,   onNew: () => formClient()  },
  orcamentos: { render: renderOrcamentos, onNew: () => formQuote()   },
  faturas:    { render: renderFaturas,    onNew: () => formInvoice() },
  items:      { render: renderItems,      onNew: () => formItem()    },
  bookings:   { render: renderBookingsAdmin, onNew: () => formBookingAdmin() },
};

let activeTab           = 'clientes';
let searchTerm          = '';
let filterQuoteStatus   = '';
let filterInvoiceStatus = '';

function setActive(tab) {
  if (!TABS[tab]) tab = 'clientes';
  activeTab = tab;
  $$('.nav__item').forEach(n => n.classList.toggle('is-active', n.dataset.tab === tab));
  $('#crumb-leaf').textContent    = T.tabs[tab].title;
  $('#btn-new-label').textContent = T.tabs[tab].newLabel;
  $('#search').value = '';
  searchTerm          = '';
  filterQuoteStatus   = '';
  filterInvoiceStatus = '';
  render();
  location.hash = tab;
}

function render() {
  $('[data-count="clientes"]').textContent   = state.clients.length;
  $('[data-count="orcamentos"]').textContent = state.quotes.length;
  $('[data-count="faturas"]').textContent    = state.invoices.length;
  $('[data-count="items"]').textContent      = state.catalog.length;
  $('[data-count="bookings"]').textContent   = state.bookings.length;

  const pending = state.invoices
    .filter(i => i.statusApi === 'PENDING' || i.statusApi === 'OVERDUE')
    .reduce((t, i) => t + i.totalEur, 0);
  const paid = state.invoices
    .filter(i => i.statusApi === 'PAID')
    .reduce((t, i) => t + i.totalEur, 0);
  $('#kpi-pending').textContent = fmtEUR(pending);
  $('#kpi-paid').textContent    = fmtEUR(paid);

  $('#meta-clock').textContent = new Date().toLocaleString('pt-PT', { hour: '2-digit', minute: '2-digit' });

  const view = $('#view');
  view.innerHTML = '';
  TABS[activeTab].render(view);
  view.querySelectorAll('[data-pdf-url]').forEach(btn => {
    btn.addEventListener('click', async () => {
      try { await openPdf(btn.dataset.pdfUrl); }
      catch (e) { toast(T.errorPdf({ msg: e.message })); }
    });
  });
}

/* ============================================================
   PAGE: CLIENTES
   ============================================================ */

function renderClientes(root) {
  const t = T.tabs.clientes;
  const q = searchTerm.toLowerCase();
  const rows = state.clients
    .filter(c => !q || (c.numero + ' ' + c.nome + ' ' + c.telefone + ' ' + c.morada).toLowerCase().includes(q))
    .sort((a, b) => (b.criadoEm || '').localeCompare(a.criadoEm || ''));

  const novos30 = state.clients.filter(c => {
    const d = new Date(c.criadoEm); return (Date.now() - d) / 86400000 <= 30;
  }).length;

  root.innerHTML = `
    <div class="view__hero">
      <div>
        <h1 class="view__title">${escapeHTML(t.title)}</h1>
        <p class="view__desc">${escapeHTML(t.desc)}</p>
      </div>
      <div class="view__stats">
        <div class="stat"><div class="stat__label">${escapeHTML(T.clients.total)}</div><div class="stat__value">${state.clients.length}</div></div>
        <div class="stat"><div class="stat__label">${escapeHTML(T.clients.new30)}</div><div class="stat__value stat__value--accent">${novos30}</div></div>
      </div>
    </div>
    <div class="panel">
      <div class="panel__head">
        <h2 class="panel__title">${escapeHTML(T.clients.directory)} <span class="tag">${rows.length} ${escapeHTML(rows.length === 1 ? T.clients.one : T.clients.many)}</span></h2>
      </div>
      <div class="tbl-wrap">
        <table class="tbl">
          <thead><tr>
            <th style="width:28%">${escapeHTML(T.clients.thName)}</th>
            <th style="width:30%">${escapeHTML(T.clients.thAddress)}</th>
            <th style="width:22%">${escapeHTML(T.clients.thPhone)}</th>
            <th style="width:10%">${escapeHTML(T.clients.thCreated)}</th>
            <th style="width:10%" class="right">${escapeHTML(T.clients.thNo)}</th>
          </tr></thead>
          <tbody>
            ${rows.length === 0 ? `
              <tr><td colspan="5"><div class="empty">
                <p class="empty__title">${escapeHTML(T.clients.emptyTitle)}</p>
                <p class="empty__desc">${escapeHTML(T.clients.emptyDesc)}</p>
              </div></td></tr>
            ` : rows.map(c => `
              <tr>
                <td class="name">${escapeHTML(c.nome)}</td>
                <td class="muted">${escapeHTML(c.morada)}</td>
                <td class="mono muted">${escapeHTML(c.telefone)}</td>
                <td class="mono">${fmtDate(c.criadoEm)}</td>
                <td class="id right">${escapeHTML(c.numero)}</td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
    </div>
  `;
}

function formClient() {
  const wrap = document.createElement('form');
  wrap.className = 'form';
  wrap.innerHTML = `
    <div class="form__grid">
      <div class="form__row form__row--full">
        <label class="lbl" for="f-nome">${escapeHTML(T.clients.formName)} <span class="req">●</span></label>
        <input class="inp" id="f-nome" required placeholder="${escapeHTML(T.clients.phName)}" />
      </div>
      <div class="form__row form__row--full">
        <label class="lbl" for="f-morada">${escapeHTML(T.clients.formAddress)}</label>
        <input class="inp" id="f-morada" placeholder="${escapeHTML(T.clients.phAddress)}" />
      </div>
      <div class="form__row form__row--full">
        <label class="lbl" for="f-tel">${escapeHTML(T.clients.formPhone)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="f-tel" required placeholder="${escapeHTML(T.clients.phPhone)}" />
      </div>
    </div>
  `;
  openDrawer({
    eyebrow: T.clients.eyebrow, title: T.clients.formTitle, body: wrap, saveLabel: T.clients.saveLabel,
    async onSave() {
      const name    = $('#f-nome',   wrap).value.trim();
      const phone   = $('#f-tel',    wrap).value.trim();
      const address = $('#f-morada', wrap).value.trim() || undefined;
      if (!name || !phone) { toast(T.clients.validate); return false; }
      try {
        await api('/clients', { method: 'POST', body: JSON.stringify({ name, phone, address }) });
        await reloadClients();
        render();
        toast(T.clients.created({ name }));
      } catch (e) { toast(T.error({ msg: e.message })); return false; }
    },
  });
}

/* ============================================================
   PAGE: ORÇAMENTOS
   ============================================================ */

function renderOrcamentos(root) {
  const t = T.tabs.orcamentos;
  const q = searchTerm.toLowerCase();
  const rows = state.quotes
    .filter(o => !filterQuoteStatus || o.statusApi === filterQuoteStatus)
    .filter(o => !q || (o.numero + ' ' + o.clienteNome + ' ' + o.status).toLowerCase().includes(q))
    .sort((a, b) => (b.criadoEm || '').localeCompare(a.criadoEm || ''));

  const totalAceite  = state.quotes.filter(o => o.statusApi === 'ACEITO').reduce((t, o) => t + o.totalEur, 0);
  const totalEnviado = state.quotes.filter(o => o.statusApi === 'PENDENTE').reduce((t, o) => t + o.totalEur, 0);

  root.innerHTML = `
    <div class="view__hero">
      <div>
        <h1 class="view__title">${escapeHTML(t.title)}</h1>
        <p class="view__desc">${escapeHTML(t.desc)}</p>
      </div>
      <div class="view__stats">
        <div class="stat"><div class="stat__label">${escapeHTML(T.quotes.total)}</div><div class="stat__value">${state.quotes.length}</div></div>
        <div class="stat"><div class="stat__label">${escapeHTML(T.quotes.sent)}</div><div class="stat__value">${fmtEUR(totalEnviado)}</div></div>
        <div class="stat"><div class="stat__label">${escapeHTML(T.quotes.accepted)}</div><div class="stat__value stat__value--accent">${fmtEUR(totalAceite)}</div></div>
      </div>
    </div>
    <div class="panel">
      <div class="panel__head">
        <h2 class="panel__title">${escapeHTML(T.quotes.proposals)} <span class="tag">${rows.length}</span></h2>
        <div class="panel__tools">
          <button class="chip ${!filterQuoteStatus ? 'is-on' : ''}" data-filter-quote="">${escapeHTML(T.quotes.filterAll)}</button>
          ${QUOTE_STATUSES.map(s => `<button class="chip ${filterQuoteStatus === s ? 'is-on' : ''}" data-filter-quote="${s}">${escapeHTML(quoteStatusLabel(s))}</button>`).join('')}
        </div>
      </div>
      <div class="tbl-wrap">
        <table class="tbl">
          <thead><tr>
            <th style="width:14%">${escapeHTML(T.quotes.thNumber)}</th>
            <th style="width:28%">${escapeHTML(T.quotes.thClient)}</th>
            <th style="width:14%">${escapeHTML(T.quotes.thStatus)}</th>
            <th style="width:14%">${escapeHTML(T.quotes.thValidUntil)}</th>
            <th style="width:14%" class="right">${escapeHTML(T.quotes.thTotal)}</th>
            <th style="width:16%" class="right">${escapeHTML(T.quotes.thPdf)}</th>
          </tr></thead>
          <tbody>
            ${rows.length === 0 ? `
              <tr><td colspan="6"><div class="empty">
                <p class="empty__title">${escapeHTML(T.quotes.emptyTitle)}</p>
                <p class="empty__desc">${escapeHTML(T.quotes.emptyDesc)}</p>
              </div></td></tr>
            ` : rows.map(o => {
              const rowClass = o.statusApi === 'ACEITO' ? 'is-paid' : '';
              return `<tr class="${rowClass}">
                <td class="id">${escapeHTML(o.numero)}</td>
                <td class="name">${escapeHTML(o.clienteNome)}</td>
                <td>${pill(o.statusApi, o.status)}</td>
                <td class="mono muted">${fmtDate(o.validoAte)}</td>
                <td class="num">${fmtEUR(o.totalEur)}</td>
                <td class="right">${pdfLink(o.id, 'quotes', o.hasPdf, o.numero)}</td>
              </tr>`;
            }).join('')}
          </tbody>
        </table>
      </div>
    </div>
  `;

  root.querySelectorAll('[data-filter-quote]').forEach(btn => {
    btn.addEventListener('click', () => { filterQuoteStatus = btn.dataset.filterQuote; render(); });
  });
}

function formQuote() {
  const node = lineItemsForm({
    extraTop: `
      <div class="form__row">
        <label class="lbl" for="f-valido">${escapeHTML(T.quotes.validUntil)} <span class="opt">${escapeHTML(T.quotes.optional)}</span></label>
        <input class="inp inp--mono" id="f-valido" type="date" />
      </div>
    `,
    extraBottom: `
      <div class="form__row form__row--full">
        <label class="lbl" for="f-notas">${escapeHTML(T.quotes.notes)} <span class="opt">${escapeHTML(T.quotes.optional)}</span></label>
        <textarea class="txt" id="f-notas" placeholder="${escapeHTML(T.quotes.notesPlaceholder)}"></textarea>
      </div>
    `,
  });

  openDrawer({
    eyebrow: T.quotes.eyebrow, title: T.quotes.formTitle, body: node, wide: true, saveLabel: T.quotes.saveLabel,
    async onSave() {
      const clientId = $('#f-cliente').value;
      if (!clientId) { toast(T.quotes.chooseClient); return false; }
      const items = collectItems();
      if (items.length === 0) { toast(T.quotes.addLine); return false; }
      try {
        await api('/quotes', {
          method: 'POST',
          body: JSON.stringify({
            clientId,
            items,
            notes:      ($('#f-notas').value || '').trim() || null,
            validUntil: $('#f-valido').value || null,
          }),
        });
        await reloadQuotes();
        render();
        toast(T.quotes.created);
      } catch (e) { toast(T.error({ msg: e.message })); return false; }
    },
  });
}

/* ============================================================
   PAGE: FATURAS
   ============================================================ */

function renderFaturas(root) {
  const t = T.tabs.faturas;
  const q = searchTerm.toLowerCase();
  const rows = state.invoices
    .filter(f => !filterInvoiceStatus || f.statusApi === filterInvoiceStatus)
    .filter(f => !q || (f.numero + ' ' + f.clienteNome + ' ' + f.status).toLowerCase().includes(q))
    .sort((a, b) => (b.criadoEm || '').localeCompare(a.criadoEm || ''));

  const pago = state.invoices.filter(i => i.statusApi === 'PAID').reduce((t, i) => t + i.totalEur, 0);
  const pend = state.invoices.filter(i => i.statusApi === 'PENDING').reduce((t, i) => t + i.totalEur, 0);
  const venc = state.invoices.filter(i => i.statusApi === 'OVERDUE').reduce((t, i) => t + i.totalEur, 0);

  root.innerHTML = `
    <div class="view__hero">
      <div>
        <h1 class="view__title">${escapeHTML(t.title)}</h1>
        <p class="view__desc">${escapeHTML(t.desc)}</p>
      </div>
      <div class="view__stats">
        <div class="stat"><div class="stat__label">${escapeHTML(T.invoices.paid)}</div><div class="stat__value">${fmtEUR(pago)}</div></div>
        <div class="stat"><div class="stat__label">${escapeHTML(T.invoices.pending)}</div><div class="stat__value">${fmtEUR(pend)}</div></div>
        <div class="stat"><div class="stat__label">${escapeHTML(T.invoices.overdue)}</div><div class="stat__value stat__value--accent">${fmtEUR(venc)}</div></div>
      </div>
    </div>
    <div class="panel">
      <div class="panel__head">
        <h2 class="panel__title">${escapeHTML(T.invoices.documents)} <span class="tag">${rows.length}</span></h2>
        <div class="panel__tools">
          <button class="chip ${!filterInvoiceStatus ? 'is-on' : ''}" data-filter-inv="">${escapeHTML(T.invoices.filterAll)}</button>
          ${INVOICE_STATUSES.map(s => `<button class="chip ${filterInvoiceStatus === s ? 'is-on' : ''}" data-filter-inv="${s}">${escapeHTML(invoiceStatusLabel(s))}</button>`).join('')}
        </div>
      </div>
      <div class="tbl-wrap">
        <table class="tbl">
          <thead><tr>
            <th style="width:13%">${escapeHTML(T.invoices.thNumber)}</th>
            <th style="width:24%">${escapeHTML(T.invoices.thClient)}</th>
            <th style="width:13%">${escapeHTML(T.invoices.thStatus)}</th>
            <th style="width:13%">${escapeHTML(T.invoices.thDueDate)}</th>
            <th style="width:13%" class="right">${escapeHTML(T.invoices.thTotal)}</th>
            <th style="width:24%" class="right">${escapeHTML(T.invoices.thPdfActions)}</th>
          </tr></thead>
          <tbody>
            ${rows.length === 0 ? `
              <tr><td colspan="6"><div class="empty">
                <p class="empty__title">${escapeHTML(T.invoices.emptyTitle)}</p>
                <p class="empty__desc">${escapeHTML(T.invoices.emptyDesc)}</p>
              </div></td></tr>
            ` : rows.map(f => {
              const rowClass = f.statusApi === 'PAID' ? 'is-paid' : f.statusApi === 'OVERDUE' ? 'is-overdue' : f.statusApi === 'CANCELLED' ? 'is-draft' : '';
              return `<tr class="${rowClass}">
                <td class="id">${escapeHTML(f.numero)}</td>
                <td class="name">${escapeHTML(f.clienteNome)}</td>
                <td>${pill(f.statusApi, f.status)}</td>
                <td class="mono muted">${fmtDate(f.vencimento)}</td>
                <td class="num">${fmtEUR(f.totalEur)}</td>
                <td class="right">
                  <div class="actions">
                    ${f.statusApi === 'PENDING' || f.statusApi === 'OVERDUE' ? `<button class="btn btn--sm btn--accent" data-mark-paid="${escapeHTML(f.id)}">${escapeHTML(T.invoices.markPaid)}</button>` : ''}
                    ${pdfLink(f.id, 'invoices', f.hasPdf, f.numero)}
                  </div>
                </td>
              </tr>`;
            }).join('')}
          </tbody>
        </table>
      </div>
    </div>
  `;

  root.querySelectorAll('[data-filter-inv]').forEach(btn => {
    btn.addEventListener('click', () => { filterInvoiceStatus = btn.dataset.filterInv; render(); });
  });
  root.querySelectorAll('[data-mark-paid]').forEach(btn => {
    btn.addEventListener('click', () => markPaid(btn.dataset.markPaid));
  });
}

async function markPaid(id) {
  try {
    await api(`/invoices/${id}/paid`, { method: 'PATCH' });
    await reloadInvoices();
    render();
    const inv = state.invoices.find(i => i.id === id);
    toast(T.invoices.markedPaid({ number: inv?.numero || '' }));
  } catch (e) { toast(T.error({ msg: e.message })); }
}

function formInvoice() {
  const node = lineItemsForm({
    extraTop: `
      <div class="form__row">
        <label class="lbl" for="f-venc">${escapeHTML(T.invoices.dueDate)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="f-venc" type="date" required />
      </div>
      <div class="form__row form__row--full">
        <label class="lbl" for="f-orc">${escapeHTML(T.invoices.quoteId)} <span class="opt">${escapeHTML(T.quotes.optional)}</span></label>
        <input class="inp inp--mono" id="f-orc" placeholder="${escapeHTML(T.invoices.quoteIdPlaceholder)}" />
      </div>
    `,
    extraBottom: '',
  });

  openDrawer({
    eyebrow: T.invoices.eyebrow, title: T.invoices.formTitle, body: node, wide: true, saveLabel: T.invoices.saveLabel,
    async onSave() {
      const clientId = $('#f-cliente').value;
      const dueDate  = $('#f-venc').value;
      const quoteId  = ($('#f-orc').value || '').trim() || null;
      if (!clientId) { toast(T.invoices.chooseClient); return false; }
      if (!dueDate)  { toast(T.invoices.enterDueDate); return false; }
      const items = collectItems();
      if (items.length === 0) { toast(T.invoices.addLine); return false; }
      try {
        await api('/invoices', {
          method: 'POST',
          body: JSON.stringify({ clientId, dueDate, quoteId, items }),
        });
        await reloadInvoices();
        render();
        toast(T.invoices.issued);
      } catch (e) { toast(T.error({ msg: e.message })); return false; }
    },
  });
}

/* ----------  LINE ITEMS FORM (shared)  ---------- */

function lineItemsForm({ extraTop = '', extraBottom = '' } = {}) {
  const wrap = document.createElement('form');
  wrap.className = 'form';

  const clientOpts = state.clients
    .map(c => `<option value="${escapeHTML(c.id)}">${escapeHTML(c.nome)}</option>`)
    .join('');

  const servicos  = state.catalog.filter(c => c.tipo === 'servico');
  const materiais = state.catalog.filter(c => c.tipo === 'material');
  const catalogOpts = `
    <optgroup label="${escapeHTML(T.lines.services)}">
      ${servicos.map(c => `<option value="${escapeHTML(c.id)}">${escapeHTML(c.descricao)} · ${fmtEUR(c.preco)}/${c.unidade}</option>`).join('')}
    </optgroup>
    <optgroup label="${escapeHTML(T.lines.materials)}">
      ${materiais.map(c => `<option value="${escapeHTML(c.id)}">${escapeHTML(c.descricao)} · ${fmtEUR(c.preco)}/${c.unidade}</option>`).join('')}
    </optgroup>
  `;

  wrap.innerHTML = `
    <div class="form__grid">
      <div class="form__row">
        <label class="lbl" for="f-cliente">${escapeHTML(T.lines.client)} <span class="req">●</span></label>
        <select class="sel" id="f-cliente" required>
          <option value="">${escapeHTML(T.lines.chooseClient)}</option>
          ${clientOpts}
        </select>
      </div>
      ${extraTop}
    </div>
    <div>
      <div class="lbl" style="margin-bottom:8px">${escapeHTML(T.lines.label)} <span class="req">●</span></div>
      <div class="lines">
        <div class="lines__head">
          <span>${escapeHTML(T.lines.colDescription)}</span><span>${escapeHTML(T.lines.colQty)}</span><span>${escapeHTML(T.lines.colUnit)}</span><span>${escapeHTML(T.lines.colPrice)}</span><span></span>
        </div>
        <div id="lines-body"></div>
        <div class="lines__foot">
          <div class="lines__left">
            <button class="btn btn--sm btn--ghost" type="button" id="add-empty">${escapeHTML(T.lines.addEmpty)}</button>
            <div class="lines__pick">
              <select class="sel" id="catalog-pick">
                <option value="">${escapeHTML(T.lines.catalogPick)}</option>
                ${catalogOpts}
              </select>
              <button class="btn btn--sm" type="button" id="add-from-catalog">${escapeHTML(T.lines.add)}</button>
            </div>
          </div>
          <div class="lines__total">
            <span class="muted">${escapeHTML(T.lines.total)}</span>
            <span class="v" id="lines-total">${fmtEUR(0)}</span>
          </div>
        </div>
      </div>
    </div>
    ${extraBottom ? `<div class="form__grid">${extraBottom}</div>` : ''}
  `;

  const body = wrap.querySelector('#lines-body');

  const recalc = () => {
    const total = collectItemsFrom(body).reduce((t, it) => t + it.quantity * it.unitPriceEur, 0);
    wrap.querySelector('#lines-total').textContent = fmtEUR(total);
  };

  const addRow = (preset = {}) => {
    const row = document.createElement('div');
    row.className = 'line';
    row.innerHTML = `
      <input type="text"   data-k="description"  placeholder="${escapeHTML(T.lines.descPlaceholder)}" value="${escapeHTML(preset.description || '')}" />
      <input type="number" data-k="quantity"   class="num" min="0" step="0.01" placeholder="0"    value="${preset.quantity ?? ''}" />
      <input type="text"   data-k="unit"       class="num" placeholder="${escapeHTML(T.lines.unitPlaceholder)}"          value="${escapeHTML(preset.unit || '')}" />
      <input type="number" data-k="unitPriceEur" class="num" min="0" step="0.01" placeholder="0,00" value="${preset.unitPriceEur ?? ''}" />
      <button type="button" class="l-rm" aria-label="${escapeHTML(T.lines.removeLine)}" title="${escapeHTML(T.lines.removeLine)}">
        <svg width="12" height="12" viewBox="0 0 16 16"><path d="M3 3 L13 13 M13 3 L3 13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
      </button>
    `;
    row.querySelectorAll('input').forEach(inp => inp.addEventListener('input', recalc));
    row.querySelector('.l-rm').addEventListener('click', () => { row.remove(); recalc(); });
    body.appendChild(row);
    recalc();
  };

  addRow();

  wrap.querySelector('#add-empty').addEventListener('click', () => addRow());
  wrap.querySelector('#add-from-catalog').addEventListener('click', () => {
    const id   = wrap.querySelector('#catalog-pick').value;
    if (!id) { toast(T.lines.chooseCatalogItem); return; }
    const item = state.catalog.find(c => c.id === id);
    if (item) addRow({ description: item.descricao, quantity: 1, unit: item.unidade, unitPriceEur: item.preco });
  });

  return wrap;
}

function collectItemsFrom(body) {
  return [...body.querySelectorAll('.line')].map(row => {
    const get = k => row.querySelector(`[data-k="${k}"]`).value;
    return {
      description:  get('description').trim(),
      quantity:     Number(get('quantity') || 0),
      unitPriceEur: Number(get('unitPriceEur') || 0),
    };
  }).filter(it => it.description && (it.quantity > 0 || it.unitPriceEur > 0));
}

function collectItems() {
  return collectItemsFrom($('#lines-body'));
}

/* ============================================================
   PAGE: ITEMS PADRÃO
   ============================================================ */

function renderItems(root) {
  const t = T.tabs.items;
  const q = searchTerm.toLowerCase();
  const rows = state.catalog
    .filter(c => !q || (c.id + ' ' + c.descricao + ' ' + c.categoria).toLowerCase().includes(q))
    .sort((a, b) => a.tipo === b.tipo ? a.categoria.localeCompare(b.categoria) : a.tipo.localeCompare(b.tipo));

  const nSrv = state.catalog.filter(c => c.tipo === 'servico').length;
  const nMat = state.catalog.filter(c => c.tipo === 'material').length;

  root.innerHTML = `
    <div class="view__hero">
      <div>
        <h1 class="view__title">${escapeHTML(t.title)}</h1>
        <p class="view__desc">${escapeHTML(t.desc)}</p>
      </div>
      <div class="view__stats">
        <div class="stat"><div class="stat__label">${escapeHTML(T.items.services)}</div><div class="stat__value">${nSrv}</div></div>
        <div class="stat"><div class="stat__label">${escapeHTML(T.items.materials)}</div><div class="stat__value stat__value--accent">${nMat}</div></div>
      </div>
    </div>
    <div class="panel">
      <div class="panel__head">
        <h2 class="panel__title">${escapeHTML(T.items.catalogTitle)} <span class="tag">${escapeHTML(T.items.tag({ n: rows.length }))}</span></h2>
      </div>
      <div class="tbl-wrap">
        <table class="tbl">
          <thead><tr>
            <th style="width:11%">${escapeHTML(T.items.thType)}</th>
            <th style="width:17%">${escapeHTML(T.items.thCategory)}</th>
            <th>${escapeHTML(T.items.thDescription)}</th>
            <th style="width:9%">${escapeHTML(T.items.thUnit)}</th>
            <th style="width:13%" class="right">${escapeHTML(T.items.thPrice)}</th>
            <th style="width:14%" class="right">${escapeHTML(T.items.thActions)}</th>
          </tr></thead>
          <tbody>
            ${rows.length === 0 ? `
              <tr><td colspan="6"><div class="empty">
                <p class="empty__title">${escapeHTML(T.items.emptyTitle)}</p>
                <p class="empty__desc">${escapeHTML(T.items.emptyDesc)}</p>
              </div></td></tr>
            ` : rows.map(c => `
              <tr>
                <td>${c.tipo === 'servico' ? `<span class="pill pill--accent">${escapeHTML(T.items.pillService)}</span>` : `<span class="pill pill--info">${escapeHTML(T.items.pillMaterial)}</span>`}</td>
                <td class="muted">${escapeHTML(c.categoria)}</td>
                <td>
                  <div class="col">
                    <span class="name">${escapeHTML(c.descricao)}</span>
                    <span class="id">${escapeHTML(c.id)}</span>
                  </div>
                </td>
                <td class="mono muted">${escapeHTML(c.unidade)}</td>
                <td class="num">${fmtEUR(c.preco)}</td>
                <td class="right">
                  <div class="actions">
                    <button class="iconbtn" title="${escapeHTML(T.items.editTitle)}" data-edit-item="${escapeHTML(c.id)}">
                      <svg width="13" height="13" viewBox="0 0 16 16"><path d="M11 2 L14 5 L5 14 L2 14 L2 11 Z" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/></svg>
                    </button>
                    <button class="iconbtn iconbtn--danger" title="${escapeHTML(T.items.deleteTitle)}" data-delete-item="${escapeHTML(c.id)}">
                      <svg width="13" height="13" viewBox="0 0 16 16"><path d="M3 5 L13 5 M6 5 L6 3 L10 3 L10 5 M5 5 L6 13 L10 13 L11 5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
                    </button>
                  </div>
                </td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
    </div>
  `;

  root.querySelectorAll('[data-edit-item]').forEach(btn => {
    btn.addEventListener('click', () => formItem(btn.dataset.editItem));
  });
  root.querySelectorAll('[data-delete-item]').forEach(btn => {
    btn.addEventListener('click', () => deleteItem(btn.dataset.deleteItem));
  });
}

function formItem(id) {
  const editing = id ? state.catalog.find(c => c.id === id) : null;
  const wrap = document.createElement('form');
  wrap.className = 'form';
  wrap.innerHTML = `
    <div class="form__grid">
      <div class="form__row">
        <label class="lbl" for="i-id">${escapeHTML(T.items.idLabel)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="i-id" value="${escapeHTML(editing?.id || '')}" placeholder="${escapeHTML(T.items.phId)}" ${editing ? 'readonly' : ''} />
      </div>
      <div class="form__row">
        <label class="lbl" for="i-tipo">${escapeHTML(T.items.typeLabel)} <span class="req">●</span></label>
        <select class="sel" id="i-tipo" required>
          <option value="service"  ${editing?.tipo === 'servico'  ? 'selected' : ''}>${escapeHTML(T.items.service)}</option>
          <option value="material" ${editing?.tipo === 'material' ? 'selected' : ''}>${escapeHTML(T.items.material)}</option>
        </select>
      </div>
      <div class="form__row form__row--full">
        <label class="lbl" for="i-cat">${escapeHTML(T.items.categoryLabel)} <span class="req">●</span></label>
        <input class="inp" id="i-cat" value="${escapeHTML(editing?.categoria || '')}" placeholder="${escapeHTML(T.items.phCategory)}" required />
      </div>
      <div class="form__row form__row--full">
        <label class="lbl" for="i-desc">${escapeHTML(T.items.descLabel)} <span class="req">●</span></label>
        <textarea class="txt" id="i-desc" required placeholder="${escapeHTML(T.items.phDesc)}">${escapeHTML(editing?.descricao || '')}</textarea>
      </div>
      <div class="form__row">
        <label class="lbl" for="i-uni">${escapeHTML(T.items.unitLabel)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="i-uni" value="${escapeHTML(editing?.unidade || '')}" placeholder="${escapeHTML(T.items.phUnit)}" required />
      </div>
      <div class="form__row">
        <label class="lbl" for="i-preco">${escapeHTML(T.items.priceLabel)} <span class="req">●</span></label>
        <input class="inp inp--mono inp--right" id="i-preco" type="number" min="0" step="0.01" value="${editing?.preco ?? ''}" required placeholder="0,00" />
      </div>
    </div>
    ${editing ? `<div class="hint">${escapeHTML(T.items.editingHint({ id: editing.id }))}</div>` : ''}
  `;

  if (!editing) {
    const descEl = wrap.querySelector('#i-desc');
    const tipoEl = wrap.querySelector('#i-tipo');
    const idEl   = wrap.querySelector('#i-id');
    let touched = false;
    idEl.addEventListener('input', () => { touched = true; });
    const refresh = () => {
      if (touched) return;
      idEl.value = (tipoEl.value === 'service' ? 'srv-' : 'mat-') + slugify(descEl.value).slice(0, 40);
    };
    descEl.addEventListener('input', refresh);
    tipoEl.addEventListener('change', refresh);
  }

  openDrawer({
    eyebrow: T.items.eyebrow,
    title: editing ? T.items.editTitleFull({ id: editing.id }) : T.items.newTitle,
    body: wrap,
    saveLabel: editing ? T.items.saveChanges : T.items.createItem,
    async onSave() {
      const itemId = $('#i-id', wrap).value.trim();
      const type   = $('#i-tipo', wrap).value;
      const cat    = $('#i-cat', wrap).value.trim();
      const desc   = $('#i-desc', wrap).value.trim();
      const unit   = $('#i-uni', wrap).value.trim();
      const preco  = Number($('#i-preco', wrap).value || 0);
      if (!itemId || !cat || !desc || !unit) { toast(T.items.fillRequired); return false; }
      try {
        const payload = { id: itemId, type, category: cat, description: desc, unit, defaultUnitPriceEur: preco };
        const path = editing ? `/standard-items/${itemId}` : '/standard-items';
        await api(path, { method: 'POST', body: JSON.stringify(payload) });
        await reloadCatalog();
        render();
        toast(editing ? T.items.updated({ id: itemId }) : T.items.created({ id: itemId }));
      } catch (e) { toast(T.error({ msg: e.message })); return false; }
    },
  });
}

async function deleteItem(id) {
  const item = state.catalog.find(c => c.id === id);
  if (!item) return;
  const ok = await confirmDialog({
    title: T.items.confirmTitle,
    body: T.items.confirmBody({ desc: item.descricao }),
    okLabel: T.items.confirmOk,
  });
  if (!ok) return;
  try {
    await api(`/standard-items/${id}`, { method: 'DELETE' });
    await reloadCatalog();
    render();
    toast(T.items.deleted({ id }));
  } catch (e) { toast(T.error({ msg: e.message })); }
}

/* ============================================================
   PAGE: BOOKINGS
   ============================================================ */

const startOfWeekAdmin = (d = new Date()) => {
  const x = new Date(d);
  const day = (x.getDay() + 6) % 7;
  x.setHours(0, 0, 0, 0);
  x.setDate(x.getDate() - day);
  return x;
};
const addDaysAdmin = (d, n) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const fmtBookingWhen = iso => iso ? new Date(iso).toLocaleString('pt-PT', { dateStyle: 'short', timeStyle: 'short', timeZone: state.tenantTimezone }) : '—';
const bookingStatusLabelAdmin = s => APP[`bookingStatus${s}`] || s;
const serviceNameAdmin = id => state.bookingServices.find(s => s.id === id)?.name || id;

function renderBookingsAdmin(root) {
  const t = T.tabs.bookings;
  const weekStart = state.bookingWeekStart || startOfWeekAdmin();
  const weekLabel = `${weekStart.toLocaleDateString('pt-PT', { day: '2-digit', month: 'short' })} – ${addDaysAdmin(weekStart, 6).toLocaleDateString('pt-PT', { day: '2-digit', month: 'short', year: 'numeric' })}`;
  const q = searchTerm.toLowerCase();
  const rows = state.bookings
    .filter(b => !q || `${b.contactName} ${b.contactPhone} ${serviceNameAdmin(b.serviceId)}`.toLowerCase().includes(q))
    .map(b => `<tr data-edit-booking="${b.id}"><td class="mono">${escapeHTML(fmtBookingWhen(b.startAt))}</td><td class="name">${escapeHTML(b.contactName)}<div class="muted mono">${escapeHTML(b.contactPhone)}</div></td><td>${escapeHTML(serviceNameAdmin(b.serviceId))}</td><td>${escapeHTML(bookingStatusLabelAdmin(b.status))}</td></tr>`)
    .join('');
  root.innerHTML = `
    <div class="view__hero"><div><h1 class="view__title">${escapeHTML(t.title)}</h1><p class="view__desc">${escapeHTML(t.desc)}</p></div></div>
    <div class="booking-toolbar">
      <div class="booking-toolbar__nav">
        <button type="button" class="btn btn--sm" data-week-shift="-7">${escapeHTML(APP.bookingsPrev)}</button>
        <button type="button" class="btn btn--sm" data-week-shift="0">${escapeHTML(APP.bookingsToday)}</button>
        <button type="button" class="btn btn--sm" data-week-shift="7">${escapeHTML(APP.bookingsNext)}</button>
        <span class="booking-toolbar__label mono">${escapeHTML(weekLabel)}</span>
      </div>
      <div class="booking-toolbar__actions">
        <button type="button" class="btn btn--sm" data-manage-services>${escapeHTML(APP.bookingsManageServices)}</button>
        <button type="button" class="btn btn--sm" data-manage-availability>${escapeHTML(APP.bookingsManageAvailability)}</button>
      </div>
    </div>
    <div class="panel"><div class="tbl-wrap"><table class="tbl"><thead><tr><th>${escapeHTML(APP.bookingsThWhen)}</th><th>${escapeHTML(APP.bookingsThContact)}</th><th>${escapeHTML(APP.bookingsThService)}</th><th>${escapeHTML(APP.bookingsThStatus)}</th></tr></thead>
    <tbody>${rows || `<tr><td colspan="4"><div class="empty"><p class="empty__title">${escapeHTML(APP.bookingsEmptyList)}</p></div></td></tr>`}</tbody></table></div></div>`;
  $$('[data-week-shift]', root).forEach(b => b.addEventListener('click', async () => {
    const shift = Number(b.dataset.weekShift);
    state.bookingWeekStart = shift === 0 ? startOfWeekAdmin() : addDaysAdmin(state.bookingWeekStart || startOfWeekAdmin(), shift);
    await reloadBookings();
    render();
  }));
  $('[data-manage-services]', root)?.addEventListener('click', formBookingServicesAdmin);
  $('[data-manage-availability]', root)?.addEventListener('click', formBookingAvailabilityAdmin);
  $$('[data-edit-booking]', root).forEach(r => r.addEventListener('click', () => formBookingAdmin(state.bookings.find(b => b.id === r.dataset.editBooking))));
}

function formBookingAdmin(booking = null) {
  const body = document.createElement('form');
  body.className = 'form';
  const services = state.bookingServices.filter(s => s.active || s.id === booking?.serviceId);
  const toLocal = iso => {
    if (!iso) return '';
    const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
      timeZone: state.tenantTimezone, year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', hour12: false,
    }).formatToParts(new Date(iso)).filter(p => p.type !== 'literal').map(p => [p.type, p.value]));
    return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
  };
  body.innerHTML = `
    <div class="form__row"><label class="lbl">${escapeHTML(APP.bookingsContactName)}</label><input class="inp" name="contactName" required value="${escapeHTML(booking?.contactName || '')}" /></div>
    <div class="form__row"><label class="lbl">${escapeHTML(APP.bookingsContactPhone)}</label><input class="inp" name="contactPhone" required value="${escapeHTML(booking?.contactPhone || '')}" /></div>
    <div class="form__row"><label class="lbl">${escapeHTML(APP.bookingsService)}</label><select class="sel" name="serviceId" required>
      <option value="">${escapeHTML(APP.bookingsChooseService)}</option>
      ${services.map(s => `<option value="${s.id}" ${booking?.serviceId === s.id ? 'selected' : ''}>${escapeHTML(s.name)}</option>`).join('')}
    </select></div>
    <div class="form__row"><label class="lbl">${escapeHTML(APP.bookingsStart)}</label><input class="inp" type="datetime-local" name="startAt" required value="${escapeHTML(toLocal(booking?.startAt))}" /></div>
    <div class="form__row"><label class="lbl">${escapeHTML(APP.bookingsStatus)}</label><select class="sel" name="status">
      ${['PENDING', 'CONFIRMED', 'CANCELLED', 'COMPLETED'].map(s => `<option value="${s}" ${(booking?.status || 'CONFIRMED') === s ? 'selected' : ''}>${escapeHTML(bookingStatusLabelAdmin(s))}</option>`).join('')}
    </select></div>
    <div class="form__row"><label class="lbl">${escapeHTML(APP.bookingsNotes)}</label><textarea class="inp" name="notes" rows="3">${escapeHTML(booking?.notes || '')}</textarea></div>`;
  openDrawer({
    eyebrow: T.tabs.bookings.title,
    title: booking ? APP.bookingsEdit : APP.bookingsNew,
    body,
    saveLabel: APP.bookingsSave,
    onSave: async () => {
      const fd = new FormData(body);
      const payload = {
        contactName: String(fd.get('contactName') || '').trim(),
        contactPhone: String(fd.get('contactPhone') || '').trim(),
        serviceId: String(fd.get('serviceId') || ''),
        startAt: String(fd.get('startAt') || ''),
        status: String(fd.get('status') || 'CONFIRMED'),
        notes: String(fd.get('notes') || ''),
      };
      if (!payload.contactName || !payload.contactPhone || !payload.serviceId || !payload.startAt) {
        toast(APP.bookingsValidate);
        return false;
      }
      try {
        if (booking) await api(`/bookings/${booking.id}`, { method: 'POST', body: JSON.stringify(payload) });
        else await api('/bookings', { method: 'POST', body: JSON.stringify(payload) });
        toast(booking ? APP.bookingsUpdated : APP.bookingsCreated);
        await reloadBookings();
        render();
        return true;
      } catch (e) {
        toast(String(e.message || '').includes('409') ? APP.bookingsConflict : T.error({ msg: e.message }));
        return false;
      }
    },
  });
}

function formBookingServicesAdmin() {
  const body = document.createElement('form');
  body.className = 'form';
  body.innerHTML = `
    <div class="form__row"><label class="lbl">${escapeHTML(APP.bookingsServiceName)}</label><input class="inp" name="name" required /></div>
    <div class="form__row"><label class="lbl">${escapeHTML(APP.bookingsDuration)}</label><input class="inp" type="number" min="5" name="durationMinutes" value="30" required /></div>
    <p class="hint">${state.bookingServices.map(s => `${escapeHTML(s.name)} · ${s.durationMinutes}m`).join(' · ') || '—'}</p>`;
  openDrawer({
    eyebrow: T.tabs.bookings.title,
    title: APP.bookingsManageServices,
    body,
    saveLabel: APP.bookingsSave,
    onSave: async () => {
      const fd = new FormData(body);
      await api('/bookings/services', { method: 'POST', body: JSON.stringify({ name: fd.get('name'), durationMinutes: Number(fd.get('durationMinutes') || 30) }) });
      toast(APP.bookingsServiceSaved);
      await reloadBookings();
      render();
      return true;
    },
  });
}

function formBookingAvailabilityAdmin() {
  const body = document.createElement('form');
  body.className = 'form';
  const rules = state.bookingAvailability.length ? state.bookingAvailability : [{ dayOfWeek: 1, startLocal: '09:00', endLocal: '17:00' }];
  body.innerHTML = rules.map(r => `<div class="form__row booking-avail-row">
    <select class="sel" name="dayOfWeek">${[1,2,3,4,5,6,7].map(d => `<option value="${d}" ${r.dayOfWeek === d ? 'selected' : ''}>${escapeHTML(APP[`weekday${d}`])}</option>`).join('')}</select>
    <input class="inp" type="time" name="startLocal" value="${escapeHTML(r.startLocal)}" />
    <input class="inp" type="time" name="endLocal" value="${escapeHTML(r.endLocal)}" />
  </div>`).join('');
  openDrawer({
    eyebrow: T.tabs.bookings.title,
    title: APP.bookingsManageAvailability,
    body,
    saveLabel: APP.bookingsSave,
    onSave: async () => {
      const next = $$('.booking-avail-row', body).map(row => ({
        dayOfWeek: Number($('[name=dayOfWeek]', row).value),
        startLocal: $('[name=startLocal]', row).value,
        endLocal: $('[name=endLocal]', row).value,
      }));
      await api('/bookings/availability', { method: 'PUT', body: JSON.stringify({ rules: next }) });
      toast(APP.bookingsAvailabilitySaved);
      await reloadBookings();
      render();
      return true;
    },
  });
}

/* ============================================================
   BOOTSTRAP
   ============================================================ */

async function init() {
  try {
    const tenant = await api('');
    // Adopt the tenant's language before any data normalization bakes in status labels.
    I18N.applyTenantDefault(tenant.locale);
    I18N.applyDom(document);
    state.tenantTimezone = tenant.timezone || 'Europe/Lisbon';
    document.title = `${tenant.name} · ${T.titleSuffix}`;
    $('#brand-name').textContent = tenant.name;
    $('#brand-sub').textContent = `${tenant.slug} · CRM`;
  } catch (e) {
    if (!token) return;
    $('#view').innerHTML = `<div class="empty"><p class="empty__title">${escapeHTML(T.invalidTenantTitle)}</p><p class="empty__desc">${escapeHTML(T.invalidTenantDesc)}</p></div>`;
    return;
  }

  $$('.nav__item').forEach(a => {
    a.addEventListener('click', e => { e.preventDefault(); setActive(a.dataset.tab); });
  });

  $('#btn-new').addEventListener('click', () => TABS[activeTab].onNew());

  $('#search').addEventListener('input', e => { searchTerm = e.target.value; render(); });

  document.addEventListener('keydown', e => {
    if (e.key === '/' && document.activeElement.tagName !== 'INPUT' && document.activeElement.tagName !== 'TEXTAREA') {
      e.preventDefault(); $('#search').focus();
    }
    if (e.key === 'Escape') {
      const dr = $('#drawer'); if (!dr.hidden) dr.hidden = true;
      const cf = $('#confirm'); if (!cf.hidden) cf.hidden = true;
    }
  });

  $('#btn-export').addEventListener('click', () => toast(T.exportSoon));

  setInterval(() => {
    const el = $('#meta-clock');
    if (el) el.textContent = new Date().toLocaleString('pt-PT', { hour: '2-digit', minute: '2-digit' });
  }, 30000);

  $('#view').innerHTML = `<div class="empty"><p class="empty__title">${escapeHTML(T.loading)}</p></div>`;

  try {
    await loadAll();
  } catch (e) {
    $('#view').innerHTML = `<div class="empty"><p class="empty__title">${escapeHTML(T.loadError)}</p><p class="empty__desc">${escapeHTML(e.message)}</p></div>`;
    return;
  }

  const initial = (location.hash || '').replace('#', '') || 'clientes';
  setActive(initial);
}

if (token) init();
else renderLogin();
