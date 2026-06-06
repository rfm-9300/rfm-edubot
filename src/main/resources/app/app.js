const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
let token = localStorage.getItem('dashboardToken') || '';
let state = { me: null, overview: null, contacts: [], conversations: [], clients: [], quotes: [], invoices: [], catalog: [], search: '', active: 'overview' };

const labels = { overview: 'Overview', conversations: 'Conversas', contacts: 'Contactos', clients: 'Clientes', quotes: 'Orçamentos', invoices: 'Faturas', catalog: 'Catálogo', settings: 'Settings' };
const escapeHTML = (s = '') => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
const fmtEUR = n => new Intl.NumberFormat('pt-PT', { style: 'currency', currency: 'EUR' }).format(Number(n || 0));
const fmtDate = iso => iso ? new Date(iso).toLocaleString('pt-PT', { dateStyle: 'short', timeStyle: 'short' }) : '—';

async function api(path, options = {}) {
  const headers = { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) };
  const res = await fetch(path, { ...options, headers: { ...headers, ...(options.headers || {}) } });
  if (res.status === 401) { localStorage.removeItem('dashboardToken'); token = ''; renderLogin(); throw new Error('unauthorized'); }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  if (res.status === 204) return null;
  return res.json();
}

let toastTimer;
function toast(msg) { const el = $('#toast'); el.innerHTML = `<span class="toast__dot"></span><span>${escapeHTML(msg)}</span>`; el.hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => { el.hidden = true; }, 2800); }

function renderLogin() {
  $('#nav').innerHTML = '';
  $('#view').innerHTML = `<div class="view__hero"><div><h1 class="view__title">Login</h1><p class="view__desc">Aceda ao dashboard do seu chatbot.</p></div></div><div class="panel" style="max-width:460px"><div class="drawer__body"><form class="form" id="login-form"><div class="form__row"><label class="lbl">Email</label><input class="inp" id="email" type="email" /></div><div class="form__row"><label class="lbl">Password</label><input class="inp" id="password" type="password" /></div><button class="btn btn--primary" type="submit">Entrar</button></form></div></div>`;
  $('#login-form').addEventListener('submit', async e => {
    e.preventDefault();
    try { const res = await api('/app/auth/login', { method: 'POST', body: JSON.stringify({ email: $('#email').value, password: $('#password').value }) }); token = res.token; localStorage.setItem('dashboardToken', token); await bootAuthed(); }
    catch { toast('Login inválido'); }
  });
}

async function bootAuthed() {
  state.me = await api('/app/api/me');
  $('#brand-name').textContent = state.me.tenant.name;
  $('#brand-sub').textContent = `${state.me.tenant.agentType} · ${state.me.tenant.slug}`;
  $('#principal-type').textContent = state.me.principalType;
  renderNav();
  await loadModule(state.active);
  render();
}

function renderNav() {
  $('#nav').innerHTML = state.me.modules.map(m => `<a class="nav__item" data-tab="${m}" href="#${m}"><span class="nav__dot"></span><span class="nav__label">${labels[m] || m}</span><span class="nav__count"> </span></a>`).join('');
  $$('.nav__item').forEach(a => a.addEventListener('click', async e => { e.preventDefault(); await setActive(a.dataset.tab); }));
}

async function setActive(tab) { state.active = tab; location.hash = tab; $('#search').value = ''; state.search = ''; await loadModule(tab); render(); }

async function loadModule(tab) {
  if (tab === 'overview') state.overview = await api('/app/api/overview');
  if (tab === 'contacts') state.contacts = await api('/app/api/contacts');
  if (tab === 'conversations') state.conversations = await api('/app/api/conversations');
  if (tab === 'clients') state.clients = await api('/app/api/crm/clients');
  if (tab === 'quotes') state.quotes = await api('/app/api/crm/quotes');
  if (tab === 'invoices') state.invoices = await api('/app/api/crm/invoices');
  if (tab === 'catalog') state.catalog = await api('/app/api/crm/standard-items');
}

function render() {
  $$('.nav__item').forEach(a => a.classList.toggle('is-active', a.dataset.tab === state.active));
  $('#crumb-leaf').textContent = labels[state.active] || state.active;
  $('#meta-clock').textContent = new Date().toLocaleString('pt-PT', { hour: '2-digit', minute: '2-digit' });
  $('#kpi-messages').textContent = state.overview?.messages ?? '—';
  $('#kpi-users').textContent = state.overview?.users ?? '—';
  $('#btn-new').hidden = !['clients', 'quotes', 'invoices', 'catalog'].includes(state.active);
  $('#btn-new').textContent = `Novo ${labels[state.active] || ''}`;
  const root = $('#view');
  if (state.active === 'overview') return renderOverview(root);
  if (state.active === 'contacts') return renderContacts(root);
  if (state.active === 'conversations') return renderConversations(root);
  if (state.active === 'clients') return renderClients(root);
  if (state.active === 'quotes') return renderQuotes(root);
  if (state.active === 'invoices') return renderInvoices(root);
  if (state.active === 'catalog') return renderCatalog(root);
  renderSettings(root);
}

function hero(title, desc) { return `<div class="view__hero"><div><h1 class="view__title">${escapeHTML(title)}</h1><p class="view__desc">${escapeHTML(desc)}</p></div></div>`; }
function panelTable(head, rows, empty = 'Sem dados') { return `<div class="panel"><div class="tbl-wrap"><table class="tbl"><thead>${head}</thead><tbody>${rows || `<tr><td colspan="8"><div class="empty"><p class="empty__title">${empty}</p></div></td></tr>`}</tbody></table></div></div>`; }

function renderOverview(root) { const o = state.overview || {}; root.innerHTML = `${hero('Overview', 'Estado do chatbot e actividade recente.')}<div class="view__stats" style="margin-bottom:22px"><div class="stat"><div class="stat__label">Mensagens 24h</div><div class="stat__value">${o.messagesToday || 0}</div></div><div class="stat"><div class="stat__label">Mensagens</div><div class="stat__value">${o.messages || 0}</div></div><div class="stat"><div class="stat__label">Contactos</div><div class="stat__value">${o.users || 0}</div></div><div class="stat"><div class="stat__label">Conversas</div><div class="stat__value">${o.conversations || 0}</div></div><div class="stat"><div class="stat__label">Orçamentos</div><div class="stat__value">${o.quotes || 0}</div></div><div class="stat"><div class="stat__label">Faturas</div><div class="stat__value">${o.invoices || 0}</div></div></div>`; }
function renderContacts(root) { const q = state.search.toLowerCase(); const rows = state.contacts.filter(c => !q || `${c.displayName || ''} ${c.waId}`.toLowerCase().includes(q)).map(c => `<tr><td class="name">${escapeHTML(c.displayName || '—')}</td><td class="mono">${escapeHTML(c.waId)}</td><td>${c.status}</td><td class="mono muted">${fmtDate(c.lastSeenAt)}</td><td class="right"><button class="btn btn--sm" data-contact-status="${c.id}" data-status="${c.status === 'BLOCKED' ? 'ACTIVE' : 'BLOCKED'}">${c.status === 'BLOCKED' ? 'Unblock' : 'Block'}</button></td></tr>`).join(''); root.innerHTML = hero('Contactos', 'End-users que falaram com o bot.') + panelTable('<tr><th>Nome</th><th>WhatsApp</th><th>Status</th><th>Último visto</th><th class="right">Acções</th></tr>', rows); $$('[data-contact-status]').forEach(b => b.addEventListener('click', async () => { await api(`/app/api/contacts/${b.dataset.contactStatus}/status`, { method: 'PATCH', body: JSON.stringify({ status: b.dataset.status }) }); await loadModule('contacts'); render(); })); }
function renderConversations(root) { const rows = state.conversations.map(c => `<tr data-conversation="${c.id}"><td class="mono">${escapeHTML(c.waId)}</td><td>${c.state}</td><td class="num">${c.messageCount}</td><td class="mono muted">${fmtDate(c.lastMessageAt)}</td></tr>`).join(''); root.innerHTML = hero('Conversas', 'Histórico read-only das conversas.') + panelTable('<tr><th>WhatsApp</th><th>Estado</th><th>Msgs</th><th>Última</th></tr>', rows); $$('[data-conversation]').forEach(r => r.addEventListener('click', () => openThread(r.dataset.conversation))); }
async function openThread(id) { const msgs = await api(`/app/api/conversations/${id}/messages`); const body = document.createElement('div'); body.className = 'form'; body.innerHTML = msgs.map(m => `<div class="panel" style="padding:12px"><div class="mono muted">${m.role} · ${fmtDate(m.createdAt)}</div><div>${escapeHTML(m.text)}</div></div>`).join('') || '<div class="empty">Sem mensagens</div>'; openDrawer('Conversa', body); }
function renderClients(root) { const rows = state.clients.map(c => `<tr><td class="name">${escapeHTML(c.name)}</td><td class="mono">${escapeHTML(c.phone)}</td><td>${escapeHTML(c.address || '')}</td><td class="id right">${escapeHTML(c.number)}</td></tr>`).join(''); root.innerHTML = hero('Clientes', 'CRM · Clientes.') + panelTable('<tr><th>Nome</th><th>Telefone</th><th>Morada</th><th class="right">Nº</th></tr>', rows); }
function renderQuotes(root) { const rows = state.quotes.map(q => `<tr><td class="id">${escapeHTML(q.number)}</td><td>${escapeHTML(q.clientName || '')}</td><td>${q.status}</td><td class="num">${fmtEUR(q.totalEur)}</td></tr>`).join(''); root.innerHTML = hero('Orçamentos', 'CRM · Propostas comerciais.') + panelTable('<tr><th>Número</th><th>Cliente</th><th>Status</th><th class="right">Total</th></tr>', rows); }
function renderInvoices(root) { const rows = state.invoices.map(i => `<tr><td class="id">${escapeHTML(i.number)}</td><td>${escapeHTML(i.clientName || '')}</td><td>${i.status}</td><td class="mono">${i.dueDate}</td><td class="num">${fmtEUR(i.totalEur)}</td></tr>`).join(''); root.innerHTML = hero('Faturas', 'CRM · Faturas emitidas.') + panelTable('<tr><th>Número</th><th>Cliente</th><th>Status</th><th>Vencimento</th><th class="right">Total</th></tr>', rows); }
function renderCatalog(root) { const rows = state.catalog.map(i => `<tr><td>${i.type}</td><td>${escapeHTML(i.category)}</td><td class="name">${escapeHTML(i.description)}</td><td>${escapeHTML(i.unit)}</td><td class="num">${fmtEUR(i.defaultUnitPriceEur)}</td></tr>`).join(''); root.innerHTML = hero('Catálogo', 'CRM · Serviços e materiais padrão.') + panelTable('<tr><th>Tipo</th><th>Categoria</th><th>Descrição</th><th>Unid.</th><th class="right">Preço</th></tr>', rows); }
function renderSettings(root) { root.innerHTML = hero('Settings', 'Business profile, team e bot tweaks entram aqui nas próximas fases.') + '<div class="panel"><div class="empty"><p class="empty__title">Settings v1</p><p class="empty__desc">Team é criado no backoffice por enquanto. Business profile/PDF branding ainda pendente.</p></div></div>'; }

function openDrawer(title, body) { const root = $('#drawer'); $('#drawer-title').textContent = title; $('#drawer-body').innerHTML = ''; $('#drawer-body').appendChild(body); root.hidden = false; const close = () => { root.hidden = true; }; $$('[data-close]', root).forEach(b => b.onclick = close); }

async function init() {
  $('#btn-logout').addEventListener('click', () => { localStorage.removeItem('dashboardToken'); token = ''; renderLogin(); });
  $('#search').addEventListener('input', e => { state.search = e.target.value; render(); });
  $('#btn-new').addEventListener('click', () => toast('Criação rápida neste módulo será adicionada no próximo passo.'));
  document.addEventListener('keydown', e => { if (e.key === '/' && !['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) { e.preventDefault(); $('#search').focus(); } });
  if (!token) return renderLogin();
  state.active = (location.hash || '').replace('#', '') || 'overview';
  try { await bootAuthed(); } catch { renderLogin(); }
}
init();
