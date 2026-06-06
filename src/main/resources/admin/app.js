/* CRM · Painel de Gestão — app.js · vanilla JS, backend API */

/* ----------  API  ---------- */

const token = localStorage.getItem('adminToken');
const tenantSlug = new URLSearchParams(location.search).get('tenant');

if (!tenantSlug || !token) {
  location.replace('/backoffice/');
}

const API_BASE = `/admin/api/tenants/${encodeURIComponent(tenantSlug)}`;

async function api(path, options = {}) {
  const headers = { ...(options.body ? { 'Content-Type': 'application/json' } : {}), Authorization: `Bearer ${token}` };
  const res = await fetch(API_BASE + path, {
    ...options,
    headers: { ...headers, ...(options.headers || {}) },
  });
  if (res.status === 401) {
    localStorage.removeItem('adminToken');
    location.replace('/backoffice/');
    return;
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  if (res.status === 204) return null;
  return res.json();
}

/* ----------  STATUS MAPS  ---------- */

const QUOTE_STATUS_PT   = { PENDENTE: 'Pendente', ACEITO: 'Aceito' };
const INVOICE_STATUS_PT = { PENDING: 'PENDENTE', PAID: 'PAGA', OVERDUE: 'VENCIDA', CANCELLED: 'CANCELADA' };

const QUOTE_STATUS_LABELS   = ['Pendente', 'Aceito'];
const INVOICE_STATUS_LABELS = ['PENDENTE', 'PAGA', 'VENCIDA', 'CANCELADA'];
const QUOTE_STATUS_API_MAP   = { Pendente: 'PENDENTE', Aceito: 'ACEITO' };
const INVOICE_STATUS_API_MAP = { PENDENTE: 'PENDING', PAGA: 'PAID', VENCIDA: 'OVERDUE', CANCELADA: 'CANCELLED' };

/* ----------  STATE (client-side cache)  ---------- */

let state = { clients: [], catalog: [], quotes: [], invoices: [] };

/* ----------  NORMALIZE  ---------- */

const normalizeClient = c => ({
  id: c.id, numero: c.number || c.id, nome: c.name, telefone: c.phone, morada: c.address || '',
  criadoEm: (c.createdAt || '').slice(0, 10),
});

const normalizeQuote = q => ({
  id: q.id, numero: q.number,
  clienteId: q.clientId, clienteNome: q.clientName || '—',
  statusApi: q.status,
  status: QUOTE_STATUS_PT[q.status] || q.status,
  validoAte: q.validUntil || '',
  criadoEm: (q.createdAt || '').slice(0, 10),
  totalEur: q.totalEur || 0,
  hasPdf: q.hasPdf || false,
});

const normalizeInvoice = i => ({
  id: i.id, numero: i.number,
  clienteId: i.clientId, clienteNome: i.clientName || '—',
  statusApi: i.status,
  status: INVOICE_STATUS_PT[i.status] || i.status,
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
}

async function reloadClients()  { state.clients  = (await api('/clients')).map(normalizeClient); }
async function reloadCatalog()  { state.catalog  = (await api('/standard-items')).map(normalizeCatalogItem); }
async function reloadQuotes()   { state.quotes   = (await api('/quotes')).map(normalizeQuote); }
async function reloadInvoices() { state.invoices = (await api('/invoices')).map(normalizeInvoice); }

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

function pill(status) {
  const map = {
    RASCUNHO: '', ENVIADO: 'pill--info', ACEITE: 'pill--ok',
    RECUSADO: 'pill--bad', EXPIRADO: 'pill--warn',
    PENDENTE: 'pill--warn', PAGA: 'pill--ok', VENCIDA: 'pill--bad', CANCELADA: '',
  };
  return `<span class="pill ${map[status] || ''}">${escapeHTML(status)}</span>`;
}

function pdfLink(id, type, hasPdf, label) {
  if (!hasPdf) {
    return `<span class="pdf pdf--ghost">
      <svg width="11" height="13" viewBox="0 0 11 13"><path d="M1 1 H7 L10 4 V12 H1 Z" fill="none" stroke="currentColor" stroke-width="1"/></svg>
      pendente</span>`;
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

function confirmDialog({ title, body, okLabel = 'Eliminar', danger = true }) {
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

/* ----------  DRAWER  ---------- */

function openDrawer({ eyebrow, title, body, wide = false, onSave, saveLabel = 'Guardar' }) {
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
    <button class="btn btn--ghost" data-close>Cancelar</button>
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
    saveBtn.textContent = 'A guardar…';
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

const TABS = {
  clientes:   { title: 'Clientes',     desc: 'Diretório de clientes da empresa.',                                   newLabel: 'Novo cliente',   render: renderClientes,   onNew: () => formClient()  },
  orcamentos: { title: 'Orçamentos',   desc: 'Propostas comerciais emitidas.',                                      newLabel: 'Novo orçamento', render: renderOrcamentos, onNew: () => formQuote()   },
  faturas:    { title: 'Faturas',      desc: 'Faturas emitidas e respetivo estado.',                                newLabel: 'Nova fatura',    render: renderFaturas,    onNew: () => formInvoice() },
  items:      { title: 'Items Padrão', desc: 'Catálogo de serviços e materiais para orçamentos e faturas.',         newLabel: 'Novo item',      render: renderItems,      onNew: () => formItem()    },
};

let activeTab           = 'clientes';
let searchTerm          = '';
let filterQuoteStatus   = '';
let filterInvoiceStatus = '';

function setActive(tab) {
  if (!TABS[tab]) tab = 'clientes';
  activeTab = tab;
  $$('.nav__item').forEach(n => n.classList.toggle('is-active', n.dataset.tab === tab));
  $('#crumb-leaf').textContent    = TABS[tab].title;
  $('#btn-new-label').textContent = TABS[tab].newLabel;
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
      catch (e) { toast(`Erro PDF: ${e.message}`); }
    });
  });
}

/* ============================================================
   PAGE: CLIENTES
   ============================================================ */

function renderClientes(root) {
  const t = TABS.clientes;
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
        <div class="stat"><div class="stat__label">Total</div><div class="stat__value">${state.clients.length}</div></div>
        <div class="stat"><div class="stat__label">Novos · 30d</div><div class="stat__value stat__value--accent">${novos30}</div></div>
      </div>
    </div>
    <div class="panel">
      <div class="panel__head">
        <h2 class="panel__title">Diretório <span class="tag">${rows.length} ${rows.length === 1 ? 'cliente' : 'clientes'}</span></h2>
      </div>
      <div class="tbl-wrap">
        <table class="tbl">
          <thead><tr>
            <th style="width:28%">Nome</th>
            <th style="width:30%">Morada</th>
            <th style="width:22%">Telefone</th>
            <th style="width:10%">Criado em</th>
            <th style="width:10%" class="right">Nº Cliente</th>
          </tr></thead>
          <tbody>
            ${rows.length === 0 ? `
              <tr><td colspan="5"><div class="empty">
                <p class="empty__title">Nenhum cliente encontrado</p>
                <p class="empty__desc">Ajuste a pesquisa ou crie um novo cliente.</p>
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
        <label class="lbl" for="f-nome">Nome <span class="req">●</span></label>
        <input class="inp" id="f-nome" required placeholder="Ex: Manuel Costa, Lda." />
      </div>
      <div class="form__row form__row--full">
        <label class="lbl" for="f-morada">Morada</label>
        <input class="inp" id="f-morada" placeholder="Ex: Rua das Flores 12, 1200-001 Lisboa" />
      </div>
      <div class="form__row form__row--full">
        <label class="lbl" for="f-tel">Telefone <span class="req">●</span></label>
        <input class="inp inp--mono" id="f-tel" required placeholder="+351 912 345 678" />
      </div>
    </div>
  `;
  openDrawer({
    eyebrow: 'Clientes', title: 'Novo cliente', body: wrap, saveLabel: 'Criar cliente',
    async onSave() {
      const name    = $('#f-nome',   wrap).value.trim();
      const phone   = $('#f-tel',    wrap).value.trim();
      const address = $('#f-morada', wrap).value.trim() || undefined;
      if (!name || !phone) { toast('Preencha nome e telefone'); return false; }
      try {
        await api('/clients', { method: 'POST', body: JSON.stringify({ name, phone, address }) });
        await reloadClients();
        render();
        toast(`Cliente criado · ${name}`);
      } catch (e) { toast(`Erro: ${e.message}`); return false; }
    },
  });
}

/* ============================================================
   PAGE: ORÇAMENTOS
   ============================================================ */

function renderOrcamentos(root) {
  const t = TABS.orcamentos;
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
        <div class="stat"><div class="stat__label">Total</div><div class="stat__value">${state.quotes.length}</div></div>
        <div class="stat"><div class="stat__label">Enviados</div><div class="stat__value">${fmtEUR(totalEnviado)}</div></div>
        <div class="stat"><div class="stat__label">Aceites</div><div class="stat__value stat__value--accent">${fmtEUR(totalAceite)}</div></div>
      </div>
    </div>
    <div class="panel">
      <div class="panel__head">
        <h2 class="panel__title">Propostas <span class="tag">${rows.length}</span></h2>
        <div class="panel__tools">
          <button class="chip ${!filterQuoteStatus ? 'is-on' : ''}" data-filter-quote="">Todos</button>
          ${QUOTE_STATUS_LABELS.map(s => `<button class="chip ${filterQuoteStatus === QUOTE_STATUS_API_MAP[s] ? 'is-on' : ''}" data-filter-quote="${QUOTE_STATUS_API_MAP[s]}">${s}</button>`).join('')}
        </div>
      </div>
      <div class="tbl-wrap">
        <table class="tbl">
          <thead><tr>
            <th style="width:14%">Número</th>
            <th style="width:28%">Cliente</th>
            <th style="width:14%">Status</th>
            <th style="width:14%">Válido até</th>
            <th style="width:14%" class="right">Total</th>
            <th style="width:16%" class="right">PDF</th>
          </tr></thead>
          <tbody>
            ${rows.length === 0 ? `
              <tr><td colspan="6"><div class="empty">
                <p class="empty__title">Sem orçamentos</p>
                <p class="empty__desc">Crie o primeiro orçamento com o botão acima.</p>
              </div></td></tr>
            ` : rows.map(o => {
              const rowClass = o.statusApi === 'ACEITO' ? 'is-paid' : '';
              return `<tr class="${rowClass}">
                <td class="id">${escapeHTML(o.numero)}</td>
                <td class="name">${escapeHTML(o.clienteNome)}</td>
                <td>${pill(o.status)}</td>
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
        <label class="lbl" for="f-valido">Válido até <span class="opt">opcional</span></label>
        <input class="inp inp--mono" id="f-valido" type="date" />
      </div>
    `,
    extraBottom: `
      <div class="form__row form__row--full">
        <label class="lbl" for="f-notas">Notas <span class="opt">opcional</span></label>
        <textarea class="txt" id="f-notas" placeholder="Notas internas, condições, prazos…"></textarea>
      </div>
    `,
  });

  openDrawer({
    eyebrow: 'Orçamentos', title: 'Novo orçamento', body: node, wide: true, saveLabel: 'Criar orçamento',
    async onSave() {
      const clientId = $('#f-cliente').value;
      if (!clientId) { toast('Escolha um cliente'); return false; }
      const items = collectItems();
      if (items.length === 0) { toast('Adicione pelo menos uma linha'); return false; }
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
        toast('Orçamento criado');
      } catch (e) { toast(`Erro: ${e.message}`); return false; }
    },
  });
}

/* ============================================================
   PAGE: FATURAS
   ============================================================ */

function renderFaturas(root) {
  const t = TABS.faturas;
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
        <div class="stat"><div class="stat__label">Pago</div><div class="stat__value">${fmtEUR(pago)}</div></div>
        <div class="stat"><div class="stat__label">Pendente</div><div class="stat__value">${fmtEUR(pend)}</div></div>
        <div class="stat"><div class="stat__label">Vencido</div><div class="stat__value stat__value--accent">${fmtEUR(venc)}</div></div>
      </div>
    </div>
    <div class="panel">
      <div class="panel__head">
        <h2 class="panel__title">Documentos <span class="tag">${rows.length}</span></h2>
        <div class="panel__tools">
          <button class="chip ${!filterInvoiceStatus ? 'is-on' : ''}" data-filter-inv="">Todos</button>
          ${INVOICE_STATUS_LABELS.map(s => `<button class="chip ${filterInvoiceStatus === INVOICE_STATUS_API_MAP[s] ? 'is-on' : ''}" data-filter-inv="${INVOICE_STATUS_API_MAP[s]}">${s}</button>`).join('')}
        </div>
      </div>
      <div class="tbl-wrap">
        <table class="tbl">
          <thead><tr>
            <th style="width:13%">Número</th>
            <th style="width:24%">Cliente</th>
            <th style="width:13%">Status</th>
            <th style="width:13%">Vencimento</th>
            <th style="width:13%" class="right">Total</th>
            <th style="width:24%" class="right">PDF · Acções</th>
          </tr></thead>
          <tbody>
            ${rows.length === 0 ? `
              <tr><td colspan="6"><div class="empty">
                <p class="empty__title">Sem faturas</p>
                <p class="empty__desc">Emita a primeira fatura com o botão acima.</p>
              </div></td></tr>
            ` : rows.map(f => {
              const rowClass = f.statusApi === 'PAID' ? 'is-paid' : f.statusApi === 'OVERDUE' ? 'is-overdue' : f.statusApi === 'CANCELLED' ? 'is-draft' : '';
              return `<tr class="${rowClass}">
                <td class="id">${escapeHTML(f.numero)}</td>
                <td class="name">${escapeHTML(f.clienteNome)}</td>
                <td>${pill(f.status)}</td>
                <td class="mono muted">${fmtDate(f.vencimento)}</td>
                <td class="num">${fmtEUR(f.totalEur)}</td>
                <td class="right">
                  <div class="actions">
                    ${f.statusApi === 'PENDING' || f.statusApi === 'OVERDUE' ? `<button class="btn btn--sm btn--accent" data-mark-paid="${escapeHTML(f.id)}">Marcar pago</button>` : ''}
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
    toast(`Fatura ${inv?.numero || ''} marcada como paga`);
  } catch (e) { toast(`Erro: ${e.message}`); }
}

function formInvoice() {
  const node = lineItemsForm({
    extraTop: `
      <div class="form__row">
        <label class="lbl" for="f-venc">Vencimento <span class="req">●</span></label>
        <input class="inp inp--mono" id="f-venc" type="date" required />
      </div>
      <div class="form__row form__row--full">
        <label class="lbl" for="f-orc">ID Orçamento <span class="opt">opcional</span></label>
        <input class="inp inp--mono" id="f-orc" placeholder="ID do orçamento associado" />
      </div>
    `,
    extraBottom: '',
  });

  openDrawer({
    eyebrow: 'Faturas', title: 'Nova fatura', body: node, wide: true, saveLabel: 'Emitir fatura',
    async onSave() {
      const clientId = $('#f-cliente').value;
      const dueDate  = $('#f-venc').value;
      const quoteId  = ($('#f-orc').value || '').trim() || null;
      if (!clientId) { toast('Escolha um cliente'); return false; }
      if (!dueDate)  { toast('Indique a data de vencimento'); return false; }
      const items = collectItems();
      if (items.length === 0) { toast('Adicione pelo menos uma linha'); return false; }
      try {
        await api('/invoices', {
          method: 'POST',
          body: JSON.stringify({ clientId, dueDate, quoteId, items }),
        });
        await reloadInvoices();
        render();
        toast('Fatura emitida');
      } catch (e) { toast(`Erro: ${e.message}`); return false; }
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
    <optgroup label="Serviços">
      ${servicos.map(c => `<option value="${escapeHTML(c.id)}">${escapeHTML(c.descricao)} · ${fmtEUR(c.preco)}/${c.unidade}</option>`).join('')}
    </optgroup>
    <optgroup label="Materiais">
      ${materiais.map(c => `<option value="${escapeHTML(c.id)}">${escapeHTML(c.descricao)} · ${fmtEUR(c.preco)}/${c.unidade}</option>`).join('')}
    </optgroup>
  `;

  wrap.innerHTML = `
    <div class="form__grid">
      <div class="form__row">
        <label class="lbl" for="f-cliente">Cliente <span class="req">●</span></label>
        <select class="sel" id="f-cliente" required>
          <option value="">— Escolher cliente —</option>
          ${clientOpts}
        </select>
      </div>
      ${extraTop}
    </div>
    <div>
      <div class="lbl" style="margin-bottom:8px">Linhas <span class="req">●</span></div>
      <div class="lines">
        <div class="lines__head">
          <span>Descrição</span><span>Qtd</span><span>Unid.</span><span>Preço €</span><span></span>
        </div>
        <div id="lines-body"></div>
        <div class="lines__foot">
          <div class="lines__left">
            <button class="btn btn--sm btn--ghost" type="button" id="add-empty">+ Linha vazia</button>
            <div class="lines__pick">
              <select class="sel" id="catalog-pick">
                <option value="">Catálogo · Serviços / Materiais</option>
                ${catalogOpts}
              </select>
              <button class="btn btn--sm" type="button" id="add-from-catalog">Adicionar</button>
            </div>
          </div>
          <div class="lines__total">
            <span class="muted">Total</span>
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
      <input type="text"   data-k="description"  placeholder="Descrição da linha…" value="${escapeHTML(preset.description || '')}" />
      <input type="number" data-k="quantity"   class="num" min="0" step="0.01" placeholder="0"    value="${preset.quantity ?? ''}" />
      <input type="text"   data-k="unit"       class="num" placeholder="un"          value="${escapeHTML(preset.unit || '')}" />
      <input type="number" data-k="unitPriceEur" class="num" min="0" step="0.01" placeholder="0,00" value="${preset.unitPriceEur ?? ''}" />
      <button type="button" class="l-rm" aria-label="Remover linha" title="Remover">
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
    if (!id) { toast('Escolha um item do catálogo'); return; }
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
  const t = TABS.items;
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
        <div class="stat"><div class="stat__label">Serviços</div><div class="stat__value">${nSrv}</div></div>
        <div class="stat"><div class="stat__label">Materiais</div><div class="stat__value stat__value--accent">${nMat}</div></div>
      </div>
    </div>
    <div class="panel">
      <div class="panel__head">
        <h2 class="panel__title">Catálogo <span class="tag">${rows.length} itens</span></h2>
      </div>
      <div class="tbl-wrap">
        <table class="tbl">
          <thead><tr>
            <th style="width:11%">Tipo</th>
            <th style="width:17%">Categoria</th>
            <th>Descrição</th>
            <th style="width:9%">Unid.</th>
            <th style="width:13%" class="right">Preço</th>
            <th style="width:14%" class="right">Acções</th>
          </tr></thead>
          <tbody>
            ${rows.length === 0 ? `
              <tr><td colspan="6"><div class="empty">
                <p class="empty__title">Catálogo vazio</p>
                <p class="empty__desc">Adicione serviços ou materiais para reutilizar em orçamentos.</p>
              </div></td></tr>
            ` : rows.map(c => `
              <tr>
                <td>${c.tipo === 'servico' ? '<span class="pill pill--accent">SERVIÇO</span>' : '<span class="pill pill--info">MATERIAL</span>'}</td>
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
                    <button class="iconbtn" title="Editar" data-edit-item="${escapeHTML(c.id)}">
                      <svg width="13" height="13" viewBox="0 0 16 16"><path d="M11 2 L14 5 L5 14 L2 14 L2 11 Z" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/></svg>
                    </button>
                    <button class="iconbtn iconbtn--danger" title="Eliminar" data-delete-item="${escapeHTML(c.id)}">
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
        <label class="lbl" for="i-id">ID · slug <span class="req">●</span></label>
        <input class="inp inp--mono" id="i-id" value="${escapeHTML(editing?.id || '')}" placeholder="ex: srv-pintura-interior" ${editing ? 'readonly' : ''} />
      </div>
      <div class="form__row">
        <label class="lbl" for="i-tipo">Tipo <span class="req">●</span></label>
        <select class="sel" id="i-tipo" required>
          <option value="service"  ${editing?.tipo === 'servico'  ? 'selected' : ''}>Serviço</option>
          <option value="material" ${editing?.tipo === 'material' ? 'selected' : ''}>Material</option>
        </select>
      </div>
      <div class="form__row form__row--full">
        <label class="lbl" for="i-cat">Categoria <span class="req">●</span></label>
        <input class="inp" id="i-cat" value="${escapeHTML(editing?.categoria || '')}" placeholder="Ex: Pintura, Estruturais, Mão-de-obra…" required />
      </div>
      <div class="form__row form__row--full">
        <label class="lbl" for="i-desc">Descrição <span class="req">●</span></label>
        <textarea class="txt" id="i-desc" required placeholder="Descrição visível em orçamentos">${escapeHTML(editing?.descricao || '')}</textarea>
      </div>
      <div class="form__row">
        <label class="lbl" for="i-uni">Unidade <span class="req">●</span></label>
        <input class="inp inp--mono" id="i-uni" value="${escapeHTML(editing?.unidade || '')}" placeholder="m², h, un, rl…" required />
      </div>
      <div class="form__row">
        <label class="lbl" for="i-preco">Preço (EUR) <span class="req">●</span></label>
        <input class="inp inp--mono inp--right" id="i-preco" type="number" min="0" step="0.01" value="${editing?.preco ?? ''}" required placeholder="0,00" />
      </div>
    </div>
    ${editing ? `<div class="hint">A editar <span style="color:var(--ink)">${escapeHTML(editing.id)}</span> — alterações aplicam-se a novos orçamentos.</div>` : ''}
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
    eyebrow: 'Items Padrão',
    title: editing ? `Editar · ${editing.id}` : 'Novo item de catálogo',
    body: wrap,
    saveLabel: editing ? 'Guardar alterações' : 'Criar item',
    async onSave() {
      const itemId = $('#i-id', wrap).value.trim();
      const type   = $('#i-tipo', wrap).value;
      const cat    = $('#i-cat', wrap).value.trim();
      const desc   = $('#i-desc', wrap).value.trim();
      const unit   = $('#i-uni', wrap).value.trim();
      const preco  = Number($('#i-preco', wrap).value || 0);
      if (!itemId || !cat || !desc || !unit) { toast('Preencha todos os campos obrigatórios'); return false; }
      try {
        const payload = { id: itemId, type, category: cat, description: desc, unit, defaultUnitPriceEur: preco };
        const path = editing ? `/standard-items/${itemId}` : '/standard-items';
        await api(path, { method: 'POST', body: JSON.stringify(payload) });
        await reloadCatalog();
        render();
        toast(editing ? `Item actualizado · ${itemId}` : `Item criado · ${itemId}`);
      } catch (e) { toast(`Erro: ${e.message}`); return false; }
    },
  });
}

async function deleteItem(id) {
  const item = state.catalog.find(c => c.id === id);
  if (!item) return;
  const ok = await confirmDialog({
    title: 'Eliminar item do catálogo?',
    body: `"${item.descricao}" será removido. Orçamentos já emitidos não serão afectados.`,
    okLabel: 'Eliminar item',
  });
  if (!ok) return;
  try {
    await api(`/standard-items/${id}`, { method: 'DELETE' });
    await reloadCatalog();
    render();
    toast(`Item eliminado · ${id}`);
  } catch (e) { toast(`Erro: ${e.message}`); }
}

/* ============================================================
   BOOTSTRAP
   ============================================================ */

async function init() {
  try {
    const tenant = await api('');
    document.title = `${tenant.name} · Painel de Gestão`;
    $('#brand-name').textContent = tenant.name;
    $('#brand-sub').textContent = `${tenant.slug} · CRM`;
  } catch (e) {
    $('#view').innerHTML = `<div class="empty"><p class="empty__title">Tenant inválido</p><p class="empty__desc">Volte ao backoffice e abra um CRM válido.</p></div>`;
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

  $('#btn-export').addEventListener('click', () => toast('Exportar · em breve'));

  setInterval(() => {
    const el = $('#meta-clock');
    if (el) el.textContent = new Date().toLocaleString('pt-PT', { hour: '2-digit', minute: '2-digit' });
  }, 30000);

  $('#view').innerHTML = '<div class="empty"><p class="empty__title">A carregar…</p></div>';

  try {
    await loadAll();
  } catch (e) {
    $('#view').innerHTML = `<div class="empty"><p class="empty__title">Erro ao carregar</p><p class="empty__desc">${escapeHTML(e.message)}</p></div>`;
    return;
  }

  const initial = (location.hash || '').replace('#', '') || 'clientes';
  setActive(initial);
}

init();
