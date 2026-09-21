const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
let token = localStorage.getItem('dashboardToken') || '';
let state = {
  me: null, overview: null, contacts: [], conversations: [], clients: [], quotes: [], invoices: [], catalog: [],
  persona: null, personaChat: [], assistantThreads: [], assistantThread: null, webWidget: null, widgetDraft: null, documentTemplate: null,
  clientServices: [], filterServiceStatus: '',
  bookings: [], bookingServices: [], bookingAvailability: [], bookingView: 'week', bookingWeekStart: null,
  instagram: { connected: false, commentsEnabled: false, needsReconnect: false, username: null, unrepliedCount: 0, comments: [], media: [] },
  instagramFilter: 'needs',
  search: '', active: 'overview', selectedAsset: '',
  filterQuoteStatus: '', filterInvoiceStatus: '',
  selectedConversation: null, threadMessages: [],
  settingsSection: 'channels', personaAdvanced: false, overviewLayout: null,
  whatsAppSignup: { enabled: false },
  fetched: { conversations: false, invoices: false, bookings: false, instagram: false },
};
let personaChatBusy = false;
let assistantBusy = false;
let fbSdkPromise = null;

// Module nav labels + user-facing copy come from the shared i18n catalogs (admin/catalog.*.js).
// `labels`/`STR` are live proxies over the active locale, so every render() reads the current language.
const labels = I18N.section('common.nav');
const STR = I18N.section('app');
const CRM = I18N.section('admin');
const escapeHTML = (s = '') => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
const slugify = (s = '') => s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
const uiLocale = () => (window.I18N && I18N.locale()) || 'pt-PT';
const fmtEUR = n => new Intl.NumberFormat(uiLocale(), { style: 'currency', currency: 'EUR' }).format(Number(n || 0));
const tenantTz = () => state.me?.tenant?.timezone || 'Europe/Lisbon';
const fmtDate = iso => iso ? new Date(iso).toLocaleString(uiLocale(), { dateStyle: 'short', timeStyle: 'short', timeZone: tenantTz() }) : '—';
const fmtDay = iso => {
  if (!iso) return '—';
  const day = String(iso).slice(0, 10);
  const [y, m, d] = day.split('-');
  if (!y || !m || !d) return iso;
  return new Date(`${day}T00:00:00`).toLocaleDateString(uiLocale(), { dateStyle: 'short', timeZone: tenantTz() });
};
const fmtTime = iso => iso ? new Date(iso).toLocaleTimeString(uiLocale(), { hour: '2-digit', minute: '2-digit', timeZone: tenantTz() }) : '';
const quoteStatusLabel = code => (CRM.quoteStatus && CRM.quoteStatus[code]) || code;
const invoiceStatusLabel = code => (CRM.invoiceStatus && CRM.invoiceStatus[code]) || code;
const contactStatusLabel = code => STR[`contactStatus${code}`] || code;
const conversationStateLabel = code => STR[`conversationState${code}`] || code;
const QUOTE_STATUSES = ['PENDENTE', 'SENT', 'ACEITO'];
const INVOICE_STATUSES = ['PENDING', 'PAID', 'OVERDUE', 'CANCELLED'];
const hasModule = id => (state.me?.modules || []).includes(id);
const startOfWeek = (d = new Date()) => {
  const x = new Date(d);
  const day = (x.getDay() + 6) % 7;
  x.setHours(0, 0, 0, 0);
  x.setDate(x.getDate() - day);
  return x;
};
const addDays = (d, n) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const toIso = d => d.toISOString();
const toLocalInputValue = iso => {
  if (!iso) return '';
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: tenantTz(), year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).formatToParts(new Date(iso)).filter(p => p.type !== 'literal').map(p => [p.type, p.value]));
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
};
const bookingStatusLabel = s => STR[`bookingStatus${s}`] || s;

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

function confirmDialog({ title, body, okLabel = STR.confirm, danger = true }) {
  return new Promise(resolve => {
    const root = $('#confirm');
    if (!root) return resolve(false);
    $('#confirm-title').textContent = title;
    $('#confirm-body').textContent = body;
    const ok = $('#confirm-ok');
    ok.textContent = okLabel;
    ok.classList.toggle('btn--danger', danger);
    ok.classList.toggle('btn--primary', !danger);
    root.hidden = false;
    const cleanup = v => {
      root.hidden = true;
      ok.removeEventListener('click', onOk);
      $$('[data-confirm-cancel]', root).forEach(b => b.removeEventListener('click', onCancel));
      resolve(v);
    };
    const onOk = () => cleanup(true);
    const onCancel = () => cleanup(false);
    ok.addEventListener('click', onOk, { once: true });
    $$('[data-confirm-cancel]', root).forEach(b => b.addEventListener('click', onCancel, { once: true }));
  });
}

function quotePill(status) {
  const map = { PENDENTE: 'pill--warn', SENT: 'pill--info', ACEITO: 'pill--ok' };
  return `<span class="pill ${map[status] || ''}">${escapeHTML(quoteStatusLabel(status))}</span>`;
}
function invoicePill(status) {
  const map = { PENDING: 'pill--warn', PAID: 'pill--ok', OVERDUE: 'pill--bad', CANCELLED: '' };
  return `<span class="pill ${map[status] || ''}">${escapeHTML(invoiceStatusLabel(status))}</span>`;
}
function serviceStatusLabel(status) {
  const t = CRM.services;
  return ({ OPEN: t.open, INVOICED: t.invoiced, CANCELLED: t.cancelled }[status] || status);
}
function servicePill(status) {
  const map = { OPEN: 'pill--warn', INVOICED: 'pill--ok', CANCELLED: '' };
  return `<span class="pill ${map[status] || ''}">${escapeHTML(serviceStatusLabel(status))}</span>`;
}
function contactPill(status) {
  const map = { ACTIVE: 'pill--ok', BLOCKED: 'pill--bad', RATE_LIMITED: 'pill--warn' };
  return `<span class="pill ${map[status] || ''}">${escapeHTML(contactStatusLabel(status))}</span>`;
}

function renderLogin() {
  $('#nav').innerHTML = '';
  $('#view').innerHTML = `<div class="auth"><div class="auth__card"><div class="auth__mark">AI</div><p class="auth__eyebrow">${escapeHTML(STR.loginEyebrow)}</p><h1 class="auth__title">${escapeHTML(STR.loginTitle)}</h1><p class="auth__desc">${escapeHTML(STR.loginDesc)}</p><form class="form" id="login-form"><div class="form__row"><label class="lbl" for="email">${escapeHTML(STR.loginEmail)}</label><input class="inp" id="email" type="email" autocomplete="email" required /></div><div class="form__row"><label class="lbl" for="password">${escapeHTML(STR.loginPassword)}</label><input class="inp" id="password" type="password" autocomplete="current-password" required /></div><button class="btn btn--primary" type="submit">${escapeHTML(STR.loginSubmit)}</button></form></div></div>`;
  $('#login-form').addEventListener('submit', async e => {
    e.preventDefault();
    try { const res = await api('/app/auth/login', { method: 'POST', body: JSON.stringify({ email: $('#email').value, password: $('#password').value }) }); token = res.token; localStorage.setItem('dashboardToken', token); await bootAuthed(); }
    catch { toast(STR.loginInvalid); }
  });
}

async function bootAuthed() {
  state.me = await api('/app/api/me');
  // Adopt the tenant's language (unless the user picked an explicit override this session), then
  // refresh the static chrome that was rendered before /me resolved.
  I18N.applyTenantDefault(state.me.tenant.locale);
  I18N.applyDom(document);
  document.documentElement.lang = I18N.locale();
  window.refreshThemeLabels?.();
  document.title = `${state.me.tenant.name} · ${STR.dashboardWord}`;
  $('#brand-name').textContent = state.me.tenant.name;
  $('#brand-sub').textContent = state.me.tenant.slug;
  $('#principal-type').textContent = state.me.principalType;
  if (!state.me.modules.includes(state.active)) state.active = state.me.modules[0] || 'settings';
  renderNav();
  if (state.active !== 'overview') {
    state.overview = await api('/app/api/overview').catch(() => state.overview);
  }
  await loadModule(state.active);
  render();
}

function renderNav() {
  const o = state.overview || {};
  const waiting = state.fetched.conversations
    ? state.conversations.filter(c => c.waiting).length
    : (o.inbox?.waiting || 0);
  const overdue = state.fetched.invoices
    ? state.invoices.filter(i => i.status === 'OVERDUE').length
    : (o.cash?.overdueCount || 0);
  const pendingBookings = state.fetched.bookings
    ? state.bookings.filter(b => b.status === 'PENDING').length
    : (o.calendar?.pending || 0);
  const pendingIg = state.fetched.instagram
    ? ((state.instagram?.unrepliedCount) || (state.instagram?.comments || []).filter(c => c.needsReply).length)
    : (o.social?.unreplied || 0);
  const counts = {
    contacts: state.contacts.length || o.inbox?.contacts || 0,
    conversations: waiting || state.conversations.length || o.inbox?.conversations || 0,
    clients: state.clients.length || o.customers?.total || 0,
    quotes: state.quotes.length || o.pipeline?.quoteCount || 0,
    invoices: overdue || state.invoices.length || o.cash?.invoiceCount || 0,
    catalog: state.catalog.length || o.catalog?.items || 0,
    services: (state.clientServices.filter(s => s.status === 'OPEN').length) || o.services?.openCount || 0,
    bookings: pendingBookings || state.bookings.length || o.calendar?.thisWeek || 0,
    instagram: pendingIg || (state.instagram?.media || []).length,
  };
  const alerts = { conversations: waiting > 0, invoices: overdue > 0, bookings: pendingBookings > 0, instagram: pendingIg > 0 };
  const groups = [
    { id: 'home', items: ['overview'] },
    { id: 'groupInbox', items: ['conversations', 'contacts', 'instagram'] },
    { id: 'groupBusiness', items: ['clients', 'services', 'quotes', 'invoices', 'catalog', 'bookings'] },
    { id: 'groupBot', items: ['persona', 'ai-assistant'] },
    { id: 'groupSetup', items: ['settings'] },
  ];
  const enabled = new Set(state.me.modules);
  $('#nav').innerHTML = groups.map(g => {
    const items = g.items.filter(m => enabled.has(m));
    if (!items.length) return '';
    const label = g.id === 'home' ? '' : `<div class="nav__group-label">${escapeHTML(labels[g.id] || g.id)}</div>`;
    const links = items.map(m => {
      const countHtml = Object.prototype.hasOwnProperty.call(counts, m) ? `<span class="nav__count${alerts[m] ? ' is-alert' : ''}">${counts[m]}</span>` : '';
      return `<a class="nav__item${m === state.active ? ' is-active' : ''}" data-tab="${m}" href="#${m}"><span class="nav__dot"></span><span class="nav__label">${labels[m] || m}</span>${countHtml}</a>`;
    }).join('');
    return `<div class="nav__group">${label}${links}</div>`;
  }).join('');
  $$('.nav__item').forEach(a => a.addEventListener('click', async e => { e.preventDefault(); await setActive(a.dataset.tab); }));
}

async function setActive(tab) {
  state.active = tab;
  location.hash = tab;
  $('#search').value = '';
  state.search = '';
  state.filterQuoteStatus = '';
  state.filterInvoiceStatus = '';
  state.filterServiceStatus = '';
  try {
    await loadModule(tab);
    render();
  } catch {
    toast(STR.loadFailed);
  }
}

async function loadModule(tab) {
  if (tab === 'overview') {
    state.overview = await api('/app/api/overview');
  }
  if (tab === 'contacts') state.contacts = await api('/app/api/contacts');
  if (tab === 'conversations') {
    state.conversations = await api('/app/api/conversations');
    state.fetched.conversations = true;
  }
  if (tab === 'clients') state.clients = await api('/app/api/crm/clients');
  if (tab === 'services') {
    const [services, clients] = await Promise.all([
      api('/app/api/crm/services'),
      api('/app/api/crm/clients').catch(() => state.clients),
    ]);
    state.clientServices = services;
    state.clients = clients;
    if (hasModule('catalog')) state.catalog = await api('/app/api/crm/standard-items').catch(() => state.catalog);
    if (hasModule('bookings')) state.bookingServices = await api('/app/api/bookings/services').catch(() => state.bookingServices);
  }
  if (tab === 'quotes') state.quotes = await api('/app/api/crm/quotes');
  if (tab === 'invoices') {
    state.invoices = await api('/app/api/crm/invoices');
    state.fetched.invoices = true;
    if (!state.filterInvoiceStatus && state.invoices.some(i => i.status === 'OVERDUE')) state.filterInvoiceStatus = 'OVERDUE';
  }
  if (tab === 'catalog') state.catalog = await api('/app/api/crm/standard-items');
  if (tab === 'persona') state.persona = await api('/app/api/persona');
  if (tab === 'ai-assistant') {
    state.assistantThreads = await api('/app/api/assistant/threads');
    if (state.assistantThread && !state.assistantThreads.some(t => t.id === state.assistantThread.thread.id)) state.assistantThread = null;
    if (!state.assistantThread && state.assistantThreads.length) state.assistantThread = await api(`/app/api/assistant/threads/${state.assistantThreads[0].id}`);
  }
  if (tab === 'settings') {
    state.webWidget = await api('/app/api/web-widget').catch(() => ({ publicKey: null, allowedOrigins: [] }));
    state.whatsAppSignup = await api('/app/api/whatsapp/embedded-signup/config').catch(() => ({ enabled: false }));
    state.documentTemplate = await api('/app/api/settings/document-template').catch(() => null);
    state.overviewLayout = await api('/app/api/settings/overview').catch(() => ({ hidden: [], available: [] }));
  }
  if (tab === 'bookings') {
    if (!state.bookingWeekStart) state.bookingWeekStart = startOfWeek();
    const from = toIso(state.bookingWeekStart);
    const to = toIso(addDays(state.bookingWeekStart, 7));
    const [bookings, services, availability] = await Promise.all([
      api(`/app/api/bookings?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
      api('/app/api/bookings/services'),
      api('/app/api/bookings/availability'),
    ]);
    state.bookings = bookings;
    state.bookingServices = services;
    state.bookingAvailability = availability;
    state.fetched.bookings = true;
  }
  if (tab === 'instagram') {
    try { state.instagram = await api('/app/api/instagram?refresh=1'); }
    catch { toast(STR.instagramSyncFailed); state.instagram = await api('/app/api/instagram').catch(() => state.instagram); }
    state.fetched.instagram = true;
  }
}

function render() {
  renderNav();
  $('#crumb-leaf').textContent = labels[state.active] || state.active;
  $('#meta-clock').textContent = new Date().toLocaleString(uiLocale(), { hour: '2-digit', minute: '2-digit' });
  updateSidebarKpis();
  $('#btn-new').hidden = !['clients', 'services', 'quotes', 'invoices', 'catalog', 'bookings'].includes(state.active);
  const newButtonLabels = {
    clients: STR.clientFormTitle, services: CRM.services.formTitle, quotes: STR.quoteFormTitle,
    invoices: STR.invoiceFormTitle, catalog: STR.catalogFormTitle, bookings: STR.bookingsNew,
  };
  $('#btn-new').textContent = newButtonLabels[state.active] || `${STR.newPrefix} ${labels[state.active] || ''}`;
  const root = $('#view');
  if (state.active === 'overview') return renderOverview(root);
  if (state.active === 'contacts') return renderContacts(root);
  if (state.active === 'conversations') return renderConversations(root);
  if (state.active === 'clients') return renderClients(root);
  if (state.active === 'services') return renderServices(root);
  if (state.active === 'quotes') return renderQuotes(root);
  if (state.active === 'invoices') return renderInvoices(root);
  if (state.active === 'catalog') return renderCatalog(root);
  if (state.active === 'persona') return renderPersona(root);
  if (state.active === 'ai-assistant') return renderAssistant(root);
  if (state.active === 'bookings') return renderBookings(root);
  if (state.active === 'instagram') return renderInstagram(root);
  renderSettings(root);
}

function hero(title, desc, stats = '') {
  return `<div class="view__hero"><div><h1 class="view__title">${escapeHTML(title)}</h1><p class="view__desc">${escapeHTML(desc)}</p></div>${stats}</div>`;
}
function statCards(items, size = '') {
  if (!items.length) return '';
  const sizeCls = size === 'lg' ? ' stat--lg' : '';
  return `<div class="view__stats">${items.map((it, i) => {
    const trendCls = it.trend === 'up' ? ' delta--up' : it.trend === 'down' ? ' delta--down' : '';
    return `<div class="stat${sizeCls}"><div class="stat__label">${escapeHTML(it.label)}</div><div class="stat__value${i === items.length - 1 ? ' stat__value--accent' : ''}">${escapeHTML(String(it.value))}</div>${it.hint ? `<div class="stat__hint${trendCls}">${escapeHTML(it.hint)}</div>` : ''}</div>`;
  }).join('')}</div>`;
}
function panelTable(head, rows, empty = STR.noData, emptyDesc = '') {
  const cols = (String(head).match(/<th/g) || []).length || 8;
  const emptyCell = `<tr><td colspan="${cols}"><div class="empty"><p class="empty__title">${escapeHTML(empty)}</p>${emptyDesc ? `<p class="empty__desc">${escapeHTML(emptyDesc)}</p>` : ''}</div></td></tr>`;
  return `<div class="panel"><div class="tbl-wrap"><table class="tbl"><thead>${head}</thead><tbody>${rows || emptyCell}</tbody></table></div></div>`;
}
function crmPanel({ title, tag, tools = '', head, rows, empty, emptyDesc }) {
  const cols = (String(head).match(/<th/g) || []).length || 8;
  const emptyCell = `<tr><td colspan="${cols}"><div class="empty"><p class="empty__title">${escapeHTML(empty)}</p>${emptyDesc ? `<p class="empty__desc">${escapeHTML(emptyDesc)}</p>` : ''}</div></td></tr>`;
  return `<div class="panel">
    <div class="panel__head">
      <h2 class="panel__title">${escapeHTML(title)}${tag != null ? ` <span class="tag">${escapeHTML(String(tag))}</span>` : ''}</h2>
      ${tools ? `<div class="panel__tools">${tools}</div>` : ''}
    </div>
    <div class="tbl-wrap"><table class="tbl"><thead>${head}</thead><tbody>${rows || emptyCell}</tbody></table></div>
  </div>`;
}
function updateSidebarKpis() {
  const label1 = $('#kpi-1-label') || $$('.kpi__label')[0];
  const label2 = $('#kpi-2-label') || $$('.kpi__label')[1];
  const o = state.overview || {};
  if (o.cash) {
    if (label1) label1.textContent = CRM.kpiReceivable;
    if (label2) label2.textContent = CRM.kpiPaid;
    $('#kpi-messages').textContent = fmtEUR((o.cash.outstandingCents || 0) / 100);
    $('#kpi-users').textContent = fmtEUR((o.cash.collectedThisMonthCents || 0) / 100);
    return;
  }
  if (o.inbox) {
    if (label1) label1.textContent = STR.hl_waiting;
    if (label2) label2.textContent = STR.hl_messages_today;
    $('#kpi-messages').textContent = o.inbox.waiting ?? '—';
    $('#kpi-users').textContent = o.inbox.messagesToday ?? '—';
    return;
  }
  if (o.customers) {
    if (label1) label1.textContent = STR.hl_clients;
    if (label2) label2.textContent = STR.customersNewMonth;
    $('#kpi-messages').textContent = o.customers.total ?? '—';
    $('#kpi-users').textContent = o.customers.newThisMonth ?? '—';
    return;
  }
  if (label1) label1.textContent = STR.statMessages;
  if (label2) label2.textContent = STR.statContacts;
  $('#kpi-messages').textContent = o.messages ?? '—';
  $('#kpi-users').textContent = o.users ?? '—';
}

function centsEUR(n) { return fmtEUR(Number(n || 0) / 100); }
function deltaHint(deltaPct, vs) {
  if (deltaPct == null || typeof STR.deltaUp !== 'function') return '';
  if (deltaPct > 0) return STR.deltaUp({ pct: deltaPct, vs });
  if (deltaPct < 0) return STR.deltaDown({ pct: Math.abs(deltaPct), vs });
  return STR.deltaFlat({ vs });
}
function highlightLabel(key) { return STR[`hl_${key}`] || key; }
function highlightValue(h) {
  if (h.cents != null) return centsEUR(h.cents);
  if (h.pct != null) return `${h.pct}%`;
  return String(h.count ?? 0);
}
function attentionTitle(item) {
  if (item.kind === 'assistant_action' && typeof STR.assistantAction === 'function') return STR.assistantAction({ n: Number(item.detail || 0) });
  const map = {
    waiting_chat: STR.waitingChat,
    overdue_invoice: STR.overdueInvoice,
    due_soon_invoice: STR.dueSoonInvoice,
    pending_booking: STR.pendingBooking,
    instagram_comment: STR.instagramWaitingComment,
    quote_expiring: STR.quoteExpiring,
  };
  return map[item.kind] || item.kind;
}
function attentionPill(kind) {
  const map = {
    waiting_chat: 'pill--warn', overdue_invoice: 'pill--bad', due_soon_invoice: 'pill--warn',
    pending_booking: 'pill--info', instagram_comment: 'pill--accent', quote_expiring: 'pill--warn',
    assistant_action: 'pill--info',
  };
  return map[kind] || '';
}
function healthPill(health) {
  if (health === 'urgent') return 'pill--bad';
  if (health === 'watch') return 'pill--warn';
  return 'pill--ok';
}
function healthLabel(health) {
  if (health === 'urgent') return STR.healthUrgent;
  if (health === 'watch') return STR.healthWatch;
  return STR.healthOk;
}
function healthIcon(health) {
  if (health === 'urgent') return '🚨';
  if (health === 'watch') return '👀';
  return '✅';
}
function attentionIcon(kind) {
  const map = {
    waiting_chat: '💬', overdue_invoice: '💶', due_soon_invoice: '⏰',
    pending_booking: '📅', instagram_comment: '📸', quote_expiring: '📝',
    assistant_action: '✨',
  };
  return map[kind] || '•';
}
function attentionIconTone(kind) {
  const pill = attentionPill(kind);
  if (pill === 'pill--bad') return 'queue__icon--bad';
  if (pill === 'pill--warn') return 'queue__icon--warn';
  if (pill === 'pill--info') return 'queue__icon--info';
  if (pill === 'pill--accent') return 'queue__icon--accent';
  return '';
}
function setupIcon(kind) {
  const map = { wa: '📱', ig: '📸', widget: '🧩', persona: '🎭' };
  return map[kind] || '⚙️';
}
function pulseLine(o) {
  const parts = [];
  if ((o.health || 'ok') === 'ok') parts.push(STR.pulseOk);
  else parts.push(typeof STR.pulseNeeds === 'function' ? STR.pulseNeeds({ n: o.attentionCount || 1 }) : STR.needsYou);
  if (o.cash?.outstandingCents > 0 && typeof STR.pulseOutstanding === 'function') parts.push(STR.pulseOutstanding({ amount: centsEUR(o.cash.outstandingCents) }));
  if (o.calendar?.today > 0 && typeof STR.pulseBookingsToday === 'function') parts.push(STR.pulseBookingsToday({ n: o.calendar.today }));
  return parts.join(' · ');
}
function homeCardCopy(id) {
  const titles = {
    highlights: STR.homeCard_highlights, pulse: STR.homeCard_pulse, attention: STR.needsYou, setup: STR.setupTitle,
    cash: STR.snapCash, pipeline: STR.snapPipeline, customers: STR.snapCustomers, inbox: STR.snapInbox,
    calendar: STR.homeCard_calendar, social: STR.snapSocial, catalog: STR.snapCatalog, services: STR.snapServices, assistant: STR.snapAssistant,
  };
  const descs = {
    highlights: STR.homeCard_highlightsDesc, pulse: STR.homeCard_pulseDesc, attention: STR.homeCard_attentionDesc, setup: STR.homeCard_setupDesc,
    cash: STR.homeCard_cashDesc, pipeline: STR.homeCard_pipelineDesc, customers: STR.homeCard_customersDesc, inbox: STR.homeCard_inboxDesc,
    calendar: STR.homeCard_calendarDesc, social: STR.homeCard_socialDesc, catalog: STR.homeCard_catalogDesc, services: STR.homeCard_servicesDesc, assistant: STR.homeCard_assistantDesc,
  };
  return { title: titles[id] || id, detail: descs[id] || '' };
}

function snapshotPanel({ tab, kind, icon, title, figure, rows, meter }) {
  const metrics = `<div class="snapshot__metrics">${rows.map(r => `<div class="snapshot__metric"><span class="snapshot__metric-label">${escapeHTML(r.label)}</span><span class="snapshot__metric-value">${escapeHTML(String(r.value))}</span></div>`).join('')}</div>`;
  const pct = meter ? Math.max(0, Math.min(100, Math.round(meter.pct))) : null;
  const meterHtml = pct != null ? `<div class="meter"><div class="meter__fill" style="width:${pct}%"></div></div>` : '';
  return `<div class="panel snapshot" data-kind="${escapeHTML(kind)}">
    <div class="snapshot__head">
      <span class="snapshot__icon" aria-hidden="true">${icon}</span>
      <div class="snapshot__head-text">
        <h2 class="panel__title">${escapeHTML(title)}</h2>
        <div class="snapshot__figure">${escapeHTML(String(figure))}</div>
      </div>
      <button type="button" class="btn btn--sm snapshot__open" data-go="${escapeHTML(tab)}">${escapeHTML(STR.openModule)}</button>
    </div>
    ${meterHtml}
    ${metrics}
  </div>`;
}

function renderOverview(root) {
  const o = state.overview || {};
  const hidden = new Set(o.hiddenCards || []);
  const highlights = hidden.has('highlights') ? [] : (o.highlights || []).map(h => ({
    label: highlightLabel(h.key),
    value: highlightValue(h),
    hint: deltaHint(h.deltaPct, h.key === 'messages_today' ? STR.vsLastWeek : STR.vsLastMonth),
    trend: h.deltaPct > 0 ? 'up' : h.deltaPct < 0 ? 'down' : '',
  }));
  const stats = highlights.length ? statCards(highlights, 'lg') : '';
  const health = o.health || 'ok';
  const pulseHtml = hidden.has('pulse')
    ? ''
    : `<div class="pulse pulse--${escapeHTML(health)}"><span class="pulse__icon" aria-hidden="true">${healthIcon(health)}</span><span class="pill ${healthPill(health)}">${escapeHTML(healthLabel(health))}</span><span class="pulse__text">${escapeHTML(pulseLine(o))}</span></div>`;
  const attention = hidden.has('attention') ? [] : (o.attention || []);
  const needsHtml = hidden.has('attention')
    ? ''
    : (attention.length
    ? `<div class="overview-block"><h2 class="panel__title">${escapeHTML(STR.needsYou)} <span class="tag">${attention.length}</span></h2><div class="queue">${attention.map(n => {
      const meta = n.amountCents != null ? `<span class="queue__meta"><span class="pill ${attentionPill(n.kind)}">${escapeHTML(centsEUR(n.amountCents))}</span></span>`
        : (n.at ? `<span class="queue__meta">${escapeHTML(n.kind === 'waiting_chat' || n.kind === 'pending_booking' ? fmtDate(n.at) : fmtDay(n.at))}</span>` : '');
      const detail = n.kind === 'assistant_action' ? '' : (n.detail || '');
      return `<button type="button" class="queue__item" data-go="${escapeHTML(n.tab)}" data-conversation="${n.kind === 'waiting_chat' ? escapeHTML(n.id || '') : ''}"><span class="queue__icon ${attentionIconTone(n.kind)}" aria-hidden="true">${attentionIcon(n.kind)}</span><div><strong>${escapeHTML(attentionTitle(n))}</strong><span>${escapeHTML(detail)}</span></div>${meta}</button>`;
    }).join('')}</div></div>`
    : `<div class="overview-block"><h2 class="panel__title">${escapeHTML(STR.needsYou)}</h2><div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.needsYouEmpty)}</p><p class="empty__desc">${escapeHTML(STR.needsYouEmptyDesc)}</p></div></div></div>`);
  const snapshots = [];
  if (o.cash && !hidden.has('cash')) {
    const cashRows = [
      { label: STR.hl_collected_month, value: centsEUR(o.cash.collectedThisMonthCents) },
      { label: STR.hl_outstanding, value: centsEUR(o.cash.outstandingCents) },
      { label: STR.hl_overdue, value: `${centsEUR(o.cash.overdueCents)} · ${o.cash.overdueCount}` },
      { label: STR.cashDueSoon, value: centsEUR(o.cash.dueSoonCents) },
      { label: STR.cashIssuedMonth, value: centsEUR(o.cash.issuedThisMonthCents) },
    ];
    if (o.cash.agingWeekCents || o.cash.agingMonthCents || o.cash.agingOldCents) {
      cashRows.push({ label: STR.cashAgingCurrent, value: centsEUR(o.cash.agingCurrentCents) });
      if (o.cash.agingWeekCents) cashRows.push({ label: STR.cashAgingWeek, value: centsEUR(o.cash.agingWeekCents) });
      if (o.cash.agingMonthCents) cashRows.push({ label: STR.cashAgingMonth, value: centsEUR(o.cash.agingMonthCents) });
      if (o.cash.agingOldCents) cashRows.push({ label: STR.cashAgingOld, value: centsEUR(o.cash.agingOldCents) });
    }
    const collected = o.cash.collectedThisMonthCents || 0, outstanding = o.cash.outstandingCents || 0;
    const cashMeter = (collected || outstanding) ? { pct: (collected / (collected + outstanding)) * 100 } : null;
    snapshots.push(snapshotPanel({ tab: 'invoices', kind: 'cash', icon: '💶', title: STR.snapCash, figure: centsEUR(o.cash.outstandingCents), rows: cashRows, meter: cashMeter }));
  }
  if (o.pipeline && !hidden.has('pipeline')) {
    snapshots.push(snapshotPanel({ tab: 'quotes', kind: 'pipeline', icon: '📝', title: STR.snapPipeline, figure: `${o.pipeline.winRatePct}%`, meter: { pct: o.pipeline.winRatePct }, rows: [
      { label: STR.hl_pipeline_open, value: centsEUR(o.pipeline.openCents) },
      { label: STR.pipelineDrafts, value: o.pipeline.pendingCount },
      { label: STR.pipelineSent, value: o.pipeline.sentCount },
      { label: STR.pipelineAccepted, value: o.pipeline.acceptedCount },
      { label: STR.pipelineAcceptedMonth, value: centsEUR(o.pipeline.acceptedThisMonthCents) },
      { label: STR.pipelineExpiring, value: o.pipeline.expiringSoonCount },
      { label: STR.hl_win_rate, value: `${o.pipeline.winRatePct}%` },
    ] }));
  }
  if (o.customers && !hidden.has('customers')) {
    snapshots.push(snapshotPanel({ tab: 'clients', kind: 'customers', icon: '👥', title: STR.snapCustomers, figure: o.customers.total, rows: [
      { label: STR.hl_clients, value: o.customers.total },
      { label: STR.customersNewMonth, value: o.customers.newThisMonth },
      { label: STR.customersNewLastMonth, value: o.customers.newLastMonth },
    ] }));
  }
  if (o.services && !hidden.has('services')) {
    snapshots.push(snapshotPanel({ tab: 'services', kind: 'services', icon: '🧾', title: STR.snapServices, figure: o.services.openCount, rows: [
      { label: STR.servicesOpen, value: `${o.services.openCount} · ${centsEUR(o.services.openCents)}` },
      { label: STR.servicesInvoicedMonth, value: `${o.services.invoicedThisMonthCount} · ${centsEUR(o.services.invoicedThisMonthCents)}` },
    ] }));
  }
  if (o.inbox && hasModule('conversations') && !hidden.has('inbox')) {
    snapshots.push(snapshotPanel({ tab: 'conversations', kind: 'inbox', icon: '💬', title: STR.snapInbox, figure: o.inbox.waiting, rows: [
      { label: STR.hl_waiting, value: o.inbox.waiting },
      { label: STR.hl_messages_today, value: o.inbox.messagesToday },
      { label: STR.inboxWeek, value: o.inbox.messagesThisWeek },
      { label: STR.statConversations, value: o.inbox.conversations },
      { label: STR.inboxNewContacts, value: o.inbox.newContactsThisWeek },
      { label: STR.inboxPaused, value: o.inbox.autoReplyPaused },
    ] }));
  } else if (o.inbox && hasModule('contacts') && !hidden.has('inbox')) {
    snapshots.push(snapshotPanel({ tab: 'contacts', kind: 'contacts', icon: '📇', title: labels.contacts, figure: o.inbox.contacts, rows: [
      { label: STR.hl_contacts, value: o.inbox.contacts },
      { label: STR.inboxNewContacts, value: o.inbox.newContactsThisWeek },
    ] }));
  }
  if (o.calendar && !hidden.has('calendar')) {
    snapshots.push(snapshotPanel({ tab: 'bookings', kind: 'calendar', icon: '📅', title: STR.snapCalendar, figure: o.calendar.today, rows: [
      { label: STR.hl_bookings_today, value: o.calendar.today },
      { label: STR.snapCalendar, value: o.calendar.thisWeek },
      { label: STR.calendarPending, value: o.calendar.pending },
      { label: STR.calendarNext, value: o.calendar.next ? `${o.calendar.next.contactName} · ${fmtDate(o.calendar.next.startAt)}` : STR.calendarNone },
    ] }));
  }
  if (o.social && !hidden.has('social')) {
    snapshots.push(snapshotPanel({ tab: 'instagram', kind: 'social', icon: '📸', title: STR.snapSocial, figure: o.social.unreplied, rows: [
      { label: STR.hl_instagram_unreplied, value: o.social.unreplied },
      { label: STR.colStatus, value: o.social.connected ? STR.connected : STR.notConnected },
    ] }));
  }
  if (o.catalog && !hidden.has('catalog')) {
    snapshots.push(snapshotPanel({ tab: 'catalog', kind: 'catalog', icon: '🗂️', title: STR.snapCatalog, figure: o.catalog.items, rows: [
      { label: STR.catalogItems, value: o.catalog.items },
    ] }));
  }
  if (o.assistant && !hidden.has('assistant')) {
    snapshots.push(snapshotPanel({ tab: 'ai-assistant', kind: 'assistant', icon: '✨', title: STR.snapAssistant, figure: o.assistant.pendingActions, rows: [
      { label: STR.assistantPending, value: o.assistant.pendingActions },
    ] }));
  }
  const setupMap = {
    wa: { title: STR.waConnect, detail: STR.setupTitle },
    ig: { title: STR.igConnect, detail: STR.setupTitle },
    widget: { title: STR.setupWidget, detail: STR.settingsWidget },
    persona: { title: STR.teachBot, detail: STR.personaDesc },
  };
  const setup = hidden.has('setup') ? [] : (o.setup || []);
  const setupHtml = setup.length
    ? `<div class="overview-block"><h2 class="panel__title">${escapeHTML(STR.setupTitle)}</h2><div class="setup-list">${setup.map(s => {
      const copy = setupMap[s.kind] || { title: s.kind, detail: STR.setupTitle };
      return `<button type="button" class="queue__item" data-go="${escapeHTML(s.tab)}" data-settings="${escapeHTML(s.section || '')}"><span class="queue__icon queue__icon--info" aria-hidden="true">${setupIcon(s.kind)}</span><div><strong>${escapeHTML(copy.title)}</strong><span>${escapeHTML(copy.detail)}</span></div></button>`;
    }).join('')}</div></div>`
    : '';
  const snapshotsOff = ['cash', 'pipeline', 'customers', 'services', 'inbox', 'calendar', 'social', 'catalog', 'assistant'].some(id => hidden.has(id));
  const modulesHtml = snapshots.length
    ? `<div class="home-grid">${snapshots.join('')}</div>`
    : (snapshotsOff ? '' : `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.overviewEmptyTitle)}</p><p class="empty__desc">${escapeHTML(STR.overviewEmptyDesc)}</p></div></div>`);
  const customize = hasModule('settings')
    ? `<button type="button" class="btn btn--sm home-customize" data-go="settings" data-settings="home">${escapeHTML(STR.customizeHome)}</button>`
    : '';
  const heroHtml = `<div class="view__hero"><div><div class="home-title-row"><h1 class="view__title">${escapeHTML(labels.overview)}</h1>${customize}</div><p class="view__desc">${escapeHTML(STR.overviewDesc)}</p></div>${stats}</div>`;
  root.innerHTML = heroHtml + pulseHtml + needsHtml + modulesHtml + setupHtml;
  $$('[data-go]', root).forEach(b => b.addEventListener('click', async () => {
    if (b.dataset.conversation) state.selectedConversation = b.dataset.conversation;
    if (b.dataset.settings) state.settingsSection = b.dataset.settings;
    await setActive(b.dataset.go);
  }));
}
function renderContacts(root) {
  const q = state.search.toLowerCase();
  const rows = state.contacts.filter(c => !q || `${c.displayName || ''} ${c.waId}`.toLowerCase().includes(q)).map(c => `<tr><td class="name">${escapeHTML(c.displayName || '—')}</td><td>${escapeHTML(c.channel)}</td><td class="mono">${escapeHTML(c.waId)}</td><td>${contactPill(c.status)}</td><td class="mono muted">${fmtDate(c.lastSeenAt)}</td><td class="right"><button class="btn btn--sm" data-contact-status="${c.id}" data-status="${c.status === 'BLOCKED' ? 'ACTIVE' : 'BLOCKED'}">${c.status === 'BLOCKED' ? STR.unblock : STR.block}</button></td></tr>`).join('');
  root.innerHTML = hero(labels.contacts, STR.contactsDesc) + panelTable(`<tr><th>${STR.thName}</th><th>${STR.colChannel}</th><th>${STR.colAccount}</th><th>${STR.thStatus}</th><th>${STR.thLastSeen}</th><th class="right">${STR.thActions}</th></tr>`, rows, STR.noData);
  $$('[data-contact-status]').forEach(b => b.addEventListener('click', async () => { await api(`/app/api/contacts/${b.dataset.contactStatus}/status`, { method: 'PATCH', body: JSON.stringify({ status: b.dataset.status }) }); await loadModule('contacts'); render(); }));
}
function assetLabel(asset) {
  if (asset.platform === 'WEB') return `${asset.platform} · ${STR.webWidgetAsset}`;
  return `${asset.platform} · ${asset.displayName || STR.unnamedAsset} · ${asset.externalId}`;
}
function rememberedAsset() {
  try { return localStorage.getItem('dashboardAsset') || ''; } catch { return ''; }
}
function rememberAsset(id) {
  state.selectedAsset = id;
  try { localStorage.setItem('dashboardAsset', id); } catch { /* ignore */ }
}
function renderConversations(root) {
  const assets = state.me?.tenant.channels || [];
  if (!state.selectedAsset) state.selectedAsset = rememberedAsset();
  if (!assets.some(a => a.externalId === state.selectedAsset)) state.selectedAsset = assets[0]?.externalId || '';
  const selected = assets.find(a => a.externalId === state.selectedAsset);
  const q = state.search.toLowerCase();
  const conversations = state.conversations.filter(c => (!selected || c.channel === selected.platform) && (!q || `${c.displayName || ''} ${c.waId} ${c.lastPreview || ''}`.toLowerCase().includes(q)));
  if (state.selectedConversation && !conversations.some(c => c.id === state.selectedConversation)) state.selectedConversation = conversations[0]?.id || null;
  const current = conversations.find(c => c.id === state.selectedConversation);
  const threadRows = conversations.map(c => `<button class="assistant__thread ${current?.id === c.id ? 'is-active' : ''}" data-conversation="${c.id}" type="button"><strong>${escapeHTML(c.displayName || c.waId)}</strong><span class="inbox__preview">${c.waiting ? `${escapeHTML(STR.waiting)} · ` : ''}${escapeHTML(c.lastPreview || conversationStateLabel(c.state))}</span><span class="inbox__meta">${escapeHTML(c.channel)} · ${escapeHTML(fmtDate(c.lastMessageAt))}</span></button>`).join('');
  const picker = assets.length ? `<select class="sel" id="conversation-asset">${assets.map(a => `<option value="${escapeHTML(a.externalId)}" ${a.externalId === state.selectedAsset ? 'selected' : ''}>${escapeHTML(assetLabel(a))}</option>`).join('')}</select>` : `<p class="hint">${escapeHTML(STR.noMessagingAssets)}</p>`;
  const emptyThread = `<div class="chat__empty"><p class="empty__title">${escapeHTML(STR.noThread)}</p><p class="empty__desc">${escapeHTML(STR.noThreadDesc)}</p></div>`;
  root.innerHTML = `${hero(labels.conversations, STR.conversationsDesc)}<div class="assistant"><aside class="assistant__sidebar">${picker}<div class="assistant__threads">${threadRows || `<p class="chat__empty">${escapeHTML(assets.length ? STR.noAssetConversations : STR.noMessagingAssets)}</p>`}</div></aside><div class="panel assistant__chat" id="inbox-thread">${emptyThread}</div></div>`;
  $('#conversation-asset')?.addEventListener('change', e => { rememberAsset(e.target.value); renderConversations(root); });
  $$('[data-conversation]', root).forEach(b => b.addEventListener('click', () => openInboxThread(b.dataset.conversation, root)));
  if (current) openInboxThread(current.id, root);
}
async function openInboxThread(id, root) {
  state.selectedConversation = id;
  const conversation = state.conversations.find(c => c.id === id);
  const asset = (state.me?.tenant.channels || []).find(a => a.platform === conversation?.channel && a.externalId === state.selectedAsset);
  $$('[data-conversation]', root).forEach(b => b.classList.toggle('is-active', b.dataset.conversation === id));
  const pane = $('#inbox-thread', root);
  if (!pane) return;
  const recipient = conversation?.displayName || conversation?.waId || '';
  const autoReplyOn = conversation?.autoReplyEnabled !== false;
  const isWeb = conversation?.channel === 'WEB';
  const autoReplyRow = conversation && !isWeb ? `<div class="thread__auto-reply"><span class="pill ${autoReplyOn ? 'pill--ok' : 'pill--warn'}">${escapeHTML(autoReplyOn ? STR.autoReplyOn : STR.autoReplyPaused)}</span><button type="button" class="btn btn--sm btn--ghost" id="thread-auto-reply">${escapeHTML(autoReplyOn ? STR.autoReplyPauseAction : STR.autoReplyResumeAction)}</button></div>` : '';
  const composer = isWeb ? `<p class="hint">${escapeHTML(STR.webConversationReadOnly)}</p>` : asset ? `<form class="chat__form" id="thread-form"><input class="inp chat__input" id="thread-input" maxlength="1000" required placeholder="${escapeHTML(STR.messagePlaceholder)}" autocomplete="off" /><button class="btn btn--primary" type="submit">${escapeHTML(STR.send)}</button></form>` : `<p class="hint">${escapeHTML(STR.sendUnavailable)}</p>`;
  pane.innerHTML = `<div class="thread__asset"><span class="lbl">${escapeHTML(STR.sendingFrom)}</span><strong>${escapeHTML(asset ? assetLabel(asset) : conversation?.channel || '')}</strong><span class="mono muted">${escapeHTML(STR.sendingTo)} ${escapeHTML(recipient)}</span>${autoReplyRow}</div><div class="chat__log assistant__log" id="thread-log"></div>${composer}`;
  $('#thread-auto-reply', pane)?.addEventListener('click', async () => {
    const button = $('#thread-auto-reply', pane);
    const nextEnabled = !(conversation.autoReplyEnabled !== false);
    button.disabled = true;
    try {
      const updated = await api(`/app/api/conversations/${id}/auto-reply`, { method: 'PATCH', body: JSON.stringify({ enabled: nextEnabled }) });
      const idx = state.conversations.findIndex(c => c.id === id);
      if (idx >= 0) state.conversations[idx] = updated;
      toast(nextEnabled ? STR.autoReplyOn : STR.autoReplyPaused);
      openInboxThread(id, root);
    } catch { toast(STR.autoReplyToggleFailed); button.disabled = false; }
  });
  try { state.threadMessages = await api(`/app/api/conversations/${id}/messages`); }
  catch { state.threadMessages = []; toast(STR.loadFailed); }
  const renderMessages = () => {
    const log = $('#thread-log', pane);
    if (!log) return;
    log.innerHTML = state.threadMessages.map(m => `<div class="chat__msg ${m.role === 'USER' ? 'chat__msg--bot' : 'chat__msg--user'}"><div>${escapeHTML(m.text)}</div><span class="thread__meta">${escapeHTML(m.role === 'USER' ? STR.customer : STR.operator)} · ${fmtDate(m.createdAt)}</span></div>`).join('') || `<div class="chat__empty">${escapeHTML(STR.noMessages)}</div>`;
    log.scrollTop = log.scrollHeight;
  };
  renderMessages();
  $('#thread-form', pane)?.addEventListener('submit', async e => {
    e.preventDefault();
    const input = $('#thread-input', pane), button = $('button[type=submit]', e.currentTarget), text = input.value.trim();
    if (!text || !asset) return;
    button.disabled = true;
    try {
      const sent = await api(`/app/api/conversations/${id}/messages`, { method: 'POST', body: JSON.stringify({ text, assetExternalId: asset.externalId }) });
      state.threadMessages.push(sent); input.value = ''; renderMessages(); toast(STR.messageDelivered);
      state.conversations = await api('/app/api/conversations');
    } catch { toast(STR.messageFailed); }
    finally { button.disabled = false; input.focus(); }
  });
}
function renderClients(root) {
  const t = CRM.clients;
  const q = state.search.toLowerCase();
  const rows = state.clients
    .filter(c => !q || `${c.number || ''} ${c.name || ''} ${c.phone || ''} ${c.address || ''}`.toLowerCase().includes(q))
    .map(c => `<tr class="conversation-row" data-client="${escapeHTML(c.id)}"><td class="name">${escapeHTML(c.name)}</td><td class="muted">${escapeHTML(c.address || '')}</td><td class="mono muted">${escapeHTML(c.phone)}</td><td class="mono">${fmtDay(c.createdAt)}</td><td class="id right">${escapeHTML(c.number)}</td></tr>`)
    .join('');
  const new30 = state.clients.filter(c => c.createdAt && (Date.now() - new Date(c.createdAt)) / 86400000 <= 30).length;
  root.innerHTML = hero(labels.clients, CRM.tabs.clientes.desc, statCards([
    { label: t.total, value: state.clients.length },
    { label: t.new30, value: new30 },
  ])) + crmPanel({
    title: t.directory,
    tag: state.clients.length,
    head: `<tr><th>${escapeHTML(t.thName)}</th><th>${escapeHTML(t.thAddress)}</th><th>${escapeHTML(t.thPhone)}</th><th>${escapeHTML(t.thCreated)}</th><th class="right">${escapeHTML(t.thNo)}</th></tr>`,
    rows,
    empty: t.emptyTitle,
    emptyDesc: t.emptyDesc,
  });
  $$('[data-client]', root).forEach(r => r.addEventListener('click', () => openClientForm(state.clients.find(c => c.id === r.dataset.client))));
}

function renderServices(root) {
  const t = CRM.services;
  const q = state.search.toLowerCase();
  const rows = (state.clientServices || [])
    .filter(s => !state.filterServiceStatus || s.status === state.filterServiceStatus)
    .filter(s => !q || `${s.name || ''} ${s.clientName || ''} ${serviceStatusLabel(s.status)}`.toLowerCase().includes(q))
    .map(s => {
      const canPick = s.status === 'OPEN';
      return `<tr class="conversation-row ${s.status === 'INVOICED' ? 'is-paid' : ''}" data-service="${escapeHTML(s.id)}">
        <td class="check">${canPick ? `<input type="checkbox" data-pick-service="${escapeHTML(s.id)}" data-client="${escapeHTML(s.clientId)}" />` : ''}</td>
        <td class="mono muted">${escapeHTML(fmtDay(s.performedAt || s.createdAt))}</td>
        <td class="name">${escapeHTML(s.clientName || '')}</td>
        <td>${escapeHTML(s.name)}</td>
        <td class="mono muted">${s.quantity}${s.unit ? ` ${escapeHTML(s.unit)}` : ''}</td>
        <td class="num">${fmtEUR(s.totalEur)}</td>
        <td>${servicePill(s.status)}</td>
      </tr>`;
    }).join('');
  const open = (state.clientServices || []).filter(s => s.status === 'OPEN');
  const openCents = open.reduce((sum, s) => sum + Number(s.totalEur || 0), 0);
  const invoiced = (state.clientServices || []).filter(s => s.status === 'INVOICED');
  const tools = `<button class="chip ${!state.filterServiceStatus ? 'is-on' : ''}" data-filter-svc="">${escapeHTML(t.filterAll)}</button>`
    + ['OPEN', 'INVOICED', 'CANCELLED'].map(s => `<button class="chip ${state.filterServiceStatus === s ? 'is-on' : ''}" data-filter-svc="${s}">${escapeHTML(serviceStatusLabel(s))}</button>`).join('')
    + (hasModule('invoices') ? `<button class="btn btn--sm btn--primary" type="button" data-invoice-services>${escapeHTML(t.invoiceSelected)}</button>` : '');
  root.innerHTML = hero(labels.services, CRM.tabs.servicos.desc, statCards([
    { label: t.open, value: `${open.length} · ${fmtEUR(openCents)}` },
    { label: t.invoiced, value: invoiced.length },
  ])) + crmPanel({
    title: t.work,
    tag: (state.clientServices || []).filter(s => !state.filterServiceStatus || s.status === state.filterServiceStatus).length,
    tools,
    head: `<tr><th></th><th>${escapeHTML(t.thWhen)}</th><th>${escapeHTML(t.thClient)}</th><th>${escapeHTML(t.thService)}</th><th>${escapeHTML(t.thQty)}</th><th class="right">${escapeHTML(t.thTotal)}</th><th>${escapeHTML(t.thStatus)}</th></tr>`,
    rows,
    empty: t.emptyTitle,
    emptyDesc: t.emptyDesc,
  });
  $$('[data-filter-svc]', root).forEach(btn => btn.addEventListener('click', () => { state.filterServiceStatus = btn.dataset.filterSvc; render(); }));
  $$('[data-pick-service]', root).forEach(box => box.addEventListener('click', e => e.stopPropagation()));
  $$('[data-service]', root).forEach(r => r.addEventListener('click', e => {
    if (e.target.closest('[data-pick-service]')) return;
    openServiceForm(state.clientServices.find(s => s.id === r.dataset.service));
  }));
  $('[data-invoice-services]', root)?.addEventListener('click', invoiceSelectedServices);
}

function serviceSourceOptions() {
  const catalog = (state.catalog || []).filter(c => c.type === 'service' || c.type === 'servico');
  const bookings = state.bookingServices || [];
  const catalogOpts = catalog.map(c => `<option value="catalog:${escapeHTML(c.id)}" data-name="${escapeHTML(c.description)}" data-price="${c.defaultUnitPriceEur}" data-unit="${escapeHTML(c.unit || '')}">${escapeHTML(c.description)}</option>`).join('');
  const bookingOpts = bookings.filter(s => s.active).map(s => `<option value="booking:${escapeHTML(s.id)}" data-name="${escapeHTML(s.name)}" data-price="" data-unit="">${escapeHTML(s.name)}</option>`).join('');
  return { catalogOpts, bookingOpts };
}

async function openServiceForm(service, presetClientId) {
  const t = CRM.services;
  const editing = service && service.id ? service : null;
  if (!state.clients.length && hasModule('clients')) state.clients = await api('/app/api/crm/clients').catch(() => []);
  if (hasModule('catalog') && !state.catalog.length) state.catalog = await api('/app/api/crm/standard-items').catch(() => []);
  if (hasModule('bookings') && !state.bookingServices.length) state.bookingServices = await api('/app/api/bookings/services').catch(() => []);
  const sources = serviceSourceOptions();
  const clientId = editing?.clientId || presetClientId || '';
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    ${editing ? '' : clientSelect(state.clients)}
    ${editing ? `<p class="hint">${escapeHTML(editing.clientName || '')}</p>` : ''}
    ${(sources.catalogOpts || sources.bookingOpts) && !editing ? `<div class="form__row"><label class="lbl" for="svc-source">${escapeHTML(t.fromCatalog)}</label>
      <select class="sel" id="svc-source"><option value="">${escapeHTML(t.fromCatalogNone)}</option>
        ${sources.bookingOpts ? `<optgroup label="${escapeHTML(labels.bookings)}">${sources.bookingOpts}</optgroup>` : ''}
        ${sources.catalogOpts ? `<optgroup label="${escapeHTML(labels.catalog)}">${sources.catalogOpts}</optgroup>` : ''}
      </select></div>` : ''}
    <div class="form__row"><label class="lbl" for="svc-name">${escapeHTML(t.name)} <span class="req">●</span></label>
      <input class="inp" id="svc-name" required placeholder="${escapeHTML(t.namePh)}" value="${escapeHTML(editing?.name || '')}" ${editing?.status === 'INVOICED' ? 'readonly' : ''} /></div>
    <div class="form__grid">
      <div class="form__row"><label class="lbl" for="svc-qty">${escapeHTML(t.qty)}</label>
        <input class="inp inp--mono" id="svc-qty" type="number" min="0" step="0.01" value="${editing?.quantity ?? 1}" ${editing?.status === 'INVOICED' ? 'readonly' : ''} /></div>
      <div class="form__row"><label class="lbl" for="svc-unit">${escapeHTML(t.unit)}</label>
        <input class="inp" id="svc-unit" value="${escapeHTML(editing?.unit || '')}" ${editing?.status === 'INVOICED' ? 'readonly' : ''} /></div>
      <div class="form__row"><label class="lbl" for="svc-price">${escapeHTML(t.price)} <span class="req">●</span></label>
        <input class="inp inp--mono inp--right" id="svc-price" type="number" min="0" step="0.01" required value="${editing?.unitPriceEur ?? ''}" ${editing?.status === 'INVOICED' ? 'readonly' : ''} /></div>
      <div class="form__row"><label class="lbl" for="svc-when">${escapeHTML(t.when)}</label>
        <input class="inp" id="svc-when" type="date" value="${escapeHTML((editing?.performedAt || '').slice(0, 10))}" ${editing?.status === 'INVOICED' ? 'readonly' : ''} /></div>
    </div>
    <div class="form__row form__row--full"><label class="lbl" for="svc-notes">${escapeHTML(t.notes)}</label>
      <textarea class="txt" id="svc-notes" ${editing?.status === 'INVOICED' ? 'readonly' : ''}>${escapeHTML(editing?.notes || '')}</textarea></div>
    ${editing?.status === 'INVOICED' ? '' : `<button class="btn btn--primary" type="submit">${escapeHTML(t.save)}</button>`}`;
  if (clientId && $('#f-client', form)) $('#f-client', form).value = clientId;
  const source = $('#svc-source', form);
  source?.addEventListener('change', () => {
    const opt = source.selectedOptions[0];
    if (!opt || !opt.value) return;
    $('#svc-name', form).value = opt.dataset.name || '';
    if (opt.dataset.price) $('#svc-price', form).value = opt.dataset.price;
    if (opt.dataset.unit) $('#svc-unit', form).value = opt.dataset.unit;
  });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    if (editing?.status === 'INVOICED') return;
    const chosenClient = editing?.clientId || $('#f-client', form)?.value;
    const name = $('#svc-name', form).value.trim();
    if (!chosenClient || !name) return toast(t.validate);
    const sourceVal = $('#svc-source', form)?.value || '';
    const payload = {
      clientId: chosenClient,
      name,
      notes: $('#svc-notes', form).value.trim() || null,
      quantity: Number($('#svc-qty', form).value || 1),
      unit: $('#svc-unit', form).value.trim(),
      unitPriceEur: Number($('#svc-price', form).value || 0),
      performedAt: $('#svc-when', form).value || null,
      bookingServiceId: sourceVal.startsWith('booking:') ? sourceVal.slice(8) : null,
      catalogItemId: sourceVal.startsWith('catalog:') ? sourceVal.slice(8) : null,
    };
    const btn = $('button[type=submit]', form);
    if (btn) btn.disabled = true;
    try {
      if (editing) await api(`/app/api/crm/services/${encodeURIComponent(editing.id)}`, { method: 'PATCH', body: JSON.stringify(payload) });
      else await api('/app/api/crm/services', { method: 'POST', body: JSON.stringify(payload) });
      closeDrawer();
      await loadModule('services');
      render();
      toast(editing ? t.updated : t.created);
    } catch { if (btn) btn.disabled = false; toast(t.saveFailed); }
  });
  openDrawer(editing ? t.editTitle : t.formTitle, form);
}

async function invoiceSelectedServices() {
  const t = CRM.services;
  const boxes = $$('[data-pick-service]:checked');
  if (!boxes.length) return toast(t.invoiceNeedRows);
  const clientIds = [...new Set(boxes.map(b => b.dataset.client))];
  if (clientIds.length !== 1) return toast(t.invoiceNeedSameClient);
  const form = document.createElement('form');
  form.className = 'form';
  const due = new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10);
  form.innerHTML = `
    <p class="hint">${escapeHTML(boxes.length + ' · ' + (state.clients.find(c => c.id === clientIds[0])?.name || ''))}</p>
    <div class="form__row"><label class="lbl" for="svc-due">${escapeHTML(t.invoiceDue)} <span class="req">●</span></label>
      <input class="inp" id="svc-due" type="date" required value="${due}" /></div>
    <button class="btn btn--primary" type="submit">${escapeHTML(t.invoiceSelected)}</button>`;
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const dueDate = $('#svc-due', form).value;
    if (!dueDate) return toast(STR.invoiceEnterDueDate);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const invoice = await api('/app/api/crm/services/invoice', {
        method: 'POST',
        body: JSON.stringify({ clientId: clientIds[0], serviceIds: boxes.map(b => b.dataset.pickService), dueDate }),
      });
      closeDrawer();
      await loadModule('services');
      if (hasModule('invoices')) state.invoices = await api('/app/api/crm/invoices').catch(() => state.invoices);
      render();
      toast(t.issuedFrom({ number: invoice.number }));
    } catch { btn.disabled = false; toast(t.invoiceFailed); }
  });
  openDrawer(t.invoiceSelected, form);
}
async function openClientForm(client) {
  const editing = client && client.id ? client : null;
  let relatedQuotes = [];
  let relatedInvoices = [];
  let relatedServices = [];
  if (editing) {
    if (hasModule('quotes')) relatedQuotes = await api(`/app/api/crm/quotes?clientId=${encodeURIComponent(editing.id)}`).catch(() => state.quotes.filter(q => q.clientId === editing.id));
    if (hasModule('invoices')) relatedInvoices = await api(`/app/api/crm/invoices?clientId=${encodeURIComponent(editing.id)}`).catch(() => state.invoices.filter(i => i.clientId === editing.id));
    if (hasModule('services')) relatedServices = await api(`/app/api/crm/services?clientId=${encodeURIComponent(editing.id)}`).catch(() => (state.clientServices || []).filter(s => s.clientId === editing.id));
  }
  const related = [
    relatedQuotes.length ? `<p class="hint">${escapeHTML(labels.quotes)} · ${relatedQuotes.map(q => escapeHTML(q.number)).join(', ')}</p>` : '',
    relatedInvoices.length ? `<p class="hint">${escapeHTML(labels.invoices)} · ${relatedInvoices.map(i => escapeHTML(i.number)).join(', ')}</p>` : '',
    relatedServices.length ? `<p class="hint">${escapeHTML(CRM.services.related)} · ${relatedServices.map(s => escapeHTML(s.name)).join(', ')}</p>` : '',
    editing && hasModule('services') ? `<button class="btn btn--sm" type="button" id="cf-add-service">${escapeHTML(CRM.services.addForClient)}</button>` : '',
  ].join('');
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__row"><label class="lbl" for="cf-name">${escapeHTML(STR.clientFormName)} <span class="req">●</span></label>
      <input class="inp" id="cf-name" required placeholder="${escapeHTML(STR.clientPhName)}" value="${escapeHTML(editing?.name || '')}" /></div>
    <div class="form__row"><label class="lbl" for="cf-phone">${escapeHTML(STR.clientFormPhone)} <span class="req">●</span></label>
      <input class="inp inp--mono" id="cf-phone" required placeholder="${escapeHTML(STR.clientPhPhone)}" value="${escapeHTML(editing?.phone || '')}" /></div>
    <div class="form__row"><label class="lbl" for="cf-address">${escapeHTML(STR.clientFormAddress)}</label>
      <input class="inp" id="cf-address" placeholder="${escapeHTML(STR.clientPhAddress)}" value="${escapeHTML(editing?.address || '')}" /></div>
    ${related}
    <button class="btn btn--primary" type="submit">${escapeHTML(editing ? STR.clientEdit : STR.clientSave)}</button>`;
  $('#cf-add-service', form)?.addEventListener('click', () => openServiceForm(null, editing.id));
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const name = $('#cf-name', form).value.trim();
    const phone = $('#cf-phone', form).value.trim();
    const address = $('#cf-address', form).value.trim() || undefined;
    if (!name || !phone) return toast(STR.clientValidate);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      if (editing) await api(`/app/api/crm/clients/${encodeURIComponent(editing.id)}`, { method: 'PATCH', body: JSON.stringify({ name, phone, address }) });
      else await api('/app/api/crm/clients', { method: 'POST', body: JSON.stringify({ name, phone, address }) });
      closeDrawer();
      await loadModule('clients');
      render();
      toast(editing ? STR.clientUpdated : STR.clientCreated({ name }));
    } catch { btn.disabled = false; toast(editing ? STR.clientUpdateFailed : STR.clientCreateFailed); }
  });
  openDrawer(editing ? STR.clientEdit : STR.clientFormTitle, form);
}

function openCatalogForm(itemId) {
  const editing = itemId ? state.catalog.find(c => c.id === itemId) : null;
  const t = CRM.items;
  const form = document.createElement('form');
  form.className = 'form';
  const typeValue = editing?.type === 'material' ? 'material' : 'service';
  form.innerHTML = `
    <div class="form__grid">
      <div class="form__row"><label class="lbl" for="cat-id">${escapeHTML(t.idLabel)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="cat-id" required placeholder="${escapeHTML(t.phId)}" value="${escapeHTML(editing?.id || '')}" ${editing ? 'readonly' : ''} /></div>
      <div class="form__row"><label class="lbl" for="cat-type">${escapeHTML(t.typeLabel)} <span class="req">●</span></label>
        <select class="sel" id="cat-type" required>
          <option value="service" ${typeValue === 'service' ? 'selected' : ''}>${escapeHTML(t.service)}</option>
          <option value="material" ${typeValue === 'material' ? 'selected' : ''}>${escapeHTML(t.material)}</option>
        </select></div>
      <div class="form__row form__row--full"><label class="lbl" for="cat-cat">${escapeHTML(t.categoryLabel)} <span class="req">●</span></label>
        <input class="inp" id="cat-cat" required placeholder="${escapeHTML(t.phCategory)}" value="${escapeHTML(editing?.category || '')}" /></div>
      <div class="form__row form__row--full"><label class="lbl" for="cat-desc">${escapeHTML(t.descLabel)} <span class="req">●</span></label>
        <textarea class="txt" id="cat-desc" required placeholder="${escapeHTML(t.phDesc)}">${escapeHTML(editing?.description || '')}</textarea></div>
      <div class="form__row"><label class="lbl" for="cat-unit">${escapeHTML(t.unitLabel)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="cat-unit" required placeholder="${escapeHTML(t.phUnit)}" value="${escapeHTML(editing?.unit || '')}" /></div>
      <div class="form__row"><label class="lbl" for="cat-price">${escapeHTML(t.priceLabel)} <span class="req">●</span></label>
        <input class="inp inp--mono inp--right" id="cat-price" type="number" min="0" step="0.01" required placeholder="0.00" value="${editing?.defaultUnitPriceEur ?? ''}" /></div>
    </div>
    ${editing ? `<p class="hint">${escapeHTML(t.editingHint({ id: editing.id }))}</p>` : ''}
    <button class="btn btn--primary" type="submit">${escapeHTML(editing ? t.saveChanges : t.createItem)}</button>`;
  const idEl = $('#cat-id', form), descEl = $('#cat-desc', form), typeEl = $('#cat-type', form);
  if (!editing) {
    let touched = false;
    idEl.addEventListener('input', () => { touched = true; });
    const refresh = () => { if (!touched) idEl.value = (typeEl.value === 'service' ? 'srv-' : 'mat-') + slugify(descEl.value).slice(0, 40); };
    descEl.addEventListener('input', refresh);
    typeEl.addEventListener('change', refresh);
  }
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const id = idEl.value.trim(), category = $('#cat-cat', form).value.trim(), description = descEl.value.trim(), unit = $('#cat-unit', form).value.trim();
    const type = typeEl.value, defaultUnitPriceEur = Number($('#cat-price', form).value || 0);
    if (!id || !category || !description || !unit) return toast(t.fillRequired);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const path = editing ? `/app/api/crm/standard-items/${encodeURIComponent(id)}` : '/app/api/crm/standard-items';
      await api(path, { method: 'POST', body: JSON.stringify({ id, type, category, description, unit, defaultUnitPriceEur }) });
      closeDrawer();
      await loadModule('catalog');
      render();
      toast(editing ? t.updated({ id }) : t.created({ id }));
    } catch { btn.disabled = false; toast(STR.catalogCreateFailed); }
  });
  openDrawer(editing ? t.editTitleFull({ id: editing.id }) : t.newTitle, form);
}

async function deleteCatalogItem(id) {
  const item = state.catalog.find(c => c.id === id);
  if (!item) return;
  const t = CRM.items;
  const ok = await confirmDialog({ title: t.confirmTitle, body: t.confirmBody({ desc: item.description }), okLabel: t.confirmOk });
  if (!ok) return;
  try {
    await api(`/app/api/crm/standard-items/${encodeURIComponent(id)}`, { method: 'DELETE' });
    await loadModule('catalog');
    render();
    toast(t.deleted({ id }));
  } catch { toast(STR.catalogCreateFailed); }
}

// Shared client picker + line-items editor for quotes and invoices.
function clientSelect(clients) {
  return `<div class="form__row"><label class="lbl" for="f-client">${escapeHTML(STR.lineClient)} <span class="req">●</span></label>
    <select class="sel" id="f-client" required><option value="">${escapeHTML(STR.lineChooseClient)}</option>
    ${clients.map(c => `<option value="${escapeHTML(c.id)}">${escapeHTML(c.name)}</option>`).join('')}</select></div>`;
}

function lineItemsField(catalog) {
  const opt = c => `<option value="${escapeHTML(c.id)}">${escapeHTML(c.description)} · ${fmtEUR(c.defaultUnitPriceEur)}/${escapeHTML(c.unit)}</option>`;
  const services = catalog.filter(c => c.type === 'service');
  const materials = catalog.filter(c => c.type === 'material');
  const html = `
    <div>
      <div class="lbl" style="margin-bottom:8px">${escapeHTML(STR.lineItemsLabel)} <span class="req">●</span></div>
      <div class="lines">
        <div class="lines__head"><span>${escapeHTML(STR.lineColDesc)}</span><span>${escapeHTML(STR.lineColQty)}</span><span>${escapeHTML(STR.lineColUnit)}</span><span>${escapeHTML(STR.lineColPrice)}</span><span></span></div>
        <div id="lines-body"></div>
        <div class="lines__foot">
          <div class="lines__left">
            <button class="btn btn--sm btn--ghost" type="button" id="add-empty">${escapeHTML(STR.lineAddEmpty)}</button>
            <div class="lines__pick">
              <select class="sel" id="catalog-pick"><option value="">${escapeHTML(STR.lineCatalogPick)}</option>
                <optgroup label="${escapeHTML(STR.catalogService)}">${services.map(opt).join('')}</optgroup>
                <optgroup label="${escapeHTML(STR.catalogMaterial)}">${materials.map(opt).join('')}</optgroup>
              </select>
              <button class="btn btn--sm" type="button" id="add-from-catalog">${escapeHTML(STR.lineAdd)}</button>
            </div>
          </div>
          <div class="lines__total"><span class="muted">${escapeHTML(STR.lineTotal)}</span><span class="v" id="lines-total">${fmtEUR(0)}</span></div>
        </div>
      </div>
    </div>`;
  const collect = form => [...$$('.line', form)].map(row => {
    const get = k => $(`[data-k="${k}"]`, row).value;
    return { description: get('description').trim(), quantity: Number(get('quantity') || 0), unit: get('unit').trim(), unitPriceEur: Number(get('unitPriceEur') || 0) };
  }).filter(it => it.description && (it.quantity > 0 || it.unitPriceEur > 0));
  const wire = form => {
    const body = $('#lines-body', form);
    const recalc = () => { $('#lines-total', form).textContent = fmtEUR(collect(form).reduce((t, it) => t + it.quantity * it.unitPriceEur, 0)); };
    const addRow = (preset = {}) => {
      const row = document.createElement('div');
      row.className = 'line';
      row.innerHTML = `
        <input type="text" data-k="description" placeholder="${escapeHTML(STR.lineDescPh)}" value="${escapeHTML(preset.description || '')}" />
        <input type="number" data-k="quantity" class="num" min="0" step="0.01" placeholder="0" value="${preset.quantity ?? ''}" />
        <input type="text" data-k="unit" class="num" placeholder="${escapeHTML(STR.lineUnitPh)}" value="${escapeHTML(preset.unit || '')}" />
        <input type="number" data-k="unitPriceEur" class="num" min="0" step="0.01" placeholder="0.00" value="${preset.unitPriceEur ?? ''}" />
        <button type="button" class="l-rm" title="${escapeHTML(STR.lineRemove)}"><svg width="12" height="12" viewBox="0 0 16 16"><path d="M3 3 L13 13 M13 3 L3 13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg></button>`;
      row.querySelectorAll('input').forEach(i => i.addEventListener('input', recalc));
      row.querySelector('.l-rm').addEventListener('click', () => { row.remove(); recalc(); });
      body.appendChild(row);
      recalc();
    };
    addRow();
    $('#add-empty', form).addEventListener('click', () => addRow());
    $('#add-from-catalog', form).addEventListener('click', () => {
      const id = $('#catalog-pick', form).value;
      if (!id) return toast(STR.lineChooseCatalog);
      const it = catalog.find(c => c.id === id);
      if (it) addRow({ description: it.description, quantity: 1, unit: it.unit, unitPriceEur: it.defaultUnitPriceEur });
    });
  };
  return { html, wire, collect };
}

async function openPdf(url, downloadName) {
  const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (res.status === 401) { localStorage.removeItem('dashboardToken'); token = ''; renderLogin(); throw new Error('unauthorized'); }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const blob = await res.blob();
  const objectUrl = URL.createObjectURL(blob);
  if (downloadName) {
    const a = document.createElement('a');
    a.href = objectUrl;
    a.download = downloadName.endsWith('.pdf') ? downloadName : `${downloadName}.pdf`;
    a.rel = 'noopener';
    document.body.appendChild(a);
    a.click();
    a.remove();
  } else {
    window.open(objectUrl, '_blank');
  }
  setTimeout(() => URL.revokeObjectURL(objectUrl), 60000);
}

function pdfButton(id, type, hasPdf, label, downloadName) {
  const downloadAttr = downloadName ? ` data-pdf-download="${escapeHTML(downloadName)}"` : '';
  return `<button class="pdf" type="button" data-pdf-url="/app/api/crm/${type}/${encodeURIComponent(id)}/pdf"${downloadAttr} title="${escapeHTML(STR.viewPdf)}">
    <svg width="11" height="13" viewBox="0 0 11 13"><path d="M1 1 H7 L10 4 V12 H1 Z" fill="none" stroke="currentColor" stroke-width="1.2"/><text x="5.5" y="10" font-family="monospace" font-size="3.6" text-anchor="middle" fill="currentColor">PDF</text></svg>
    ${escapeHTML(label || STR.viewPdf)}</button>`;
}

function wirePdfButtons(root) {
  $$('[data-pdf-url]', root).forEach(btn => {
    btn.addEventListener('click', async e => {
      e.stopPropagation();
      try { await openPdf(btn.dataset.pdfUrl, btn.dataset.pdfDownload); }
      catch (err) { toast(STR.errorPdf({ msg: err.message })); }
    });
  });
}

async function openQuoteForm() {
  let clients, catalog;
  try {
    clients = await api('/app/api/crm/clients');
    catalog = await api('/app/api/crm/standard-items').catch(() => []);
  } catch { return toast(STR.quoteCreateFailed); }
  const li = lineItemsField(catalog);
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${clientSelect(clients)}
      <div class="form__row"><label class="lbl" for="q-valid">${escapeHTML(STR.quoteValidUntil)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp inp--mono" id="q-valid" type="date" /></div>
    </div>
    ${li.html}
    <div class="form__row form__row--full"><label class="lbl" for="q-notes">${escapeHTML(STR.quoteNotes)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
      <textarea class="txt" id="q-notes" placeholder="${escapeHTML(STR.quoteNotesPh)}"></textarea></div>
    <button class="btn btn--primary" type="submit">${escapeHTML(STR.quoteSave)}</button>`;
  li.wire(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const clientId = $('#f-client', form).value;
    if (!clientId) return toast(STR.quoteChooseClient);
    const items = li.collect(form);
    if (!items.length) return toast(STR.quoteAddLine);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api('/app/api/crm/quotes', { method: 'POST', body: JSON.stringify({ clientId, items, notes: $('#q-notes', form).value.trim() || null, validUntil: $('#q-valid', form).value || null }) });
      closeDrawer();
      await loadModule('quotes');
      render();
      toast(STR.quoteCreated);
    } catch { btn.disabled = false; toast(STR.quoteCreateFailed); }
  });
  openDrawer(STR.quoteFormTitle, form, true);
}

async function openInvoiceForm() {
  let clients, catalog;
  try {
    clients = await api('/app/api/crm/clients');
    catalog = await api('/app/api/crm/standard-items').catch(() => []);
  } catch { return toast(STR.invoiceCreateFailed); }
  const li = lineItemsField(catalog);
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${clientSelect(clients)}
      <div class="form__row"><label class="lbl" for="i-due">${escapeHTML(STR.invoiceDueDate)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="i-due" type="date" required /></div>
      <div class="form__row form__row--full"><label class="lbl" for="i-quote">${escapeHTML(STR.invoiceQuoteId)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp inp--mono" id="i-quote" placeholder="${escapeHTML(STR.invoiceQuoteIdPh)}" /></div>
    </div>
    ${li.html}
    <button class="btn btn--primary" type="submit">${escapeHTML(STR.invoiceSave)}</button>`;
  li.wire(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const clientId = $('#f-client', form).value;
    const dueDate = $('#i-due', form).value;
    if (!clientId) return toast(STR.invoiceChooseClient);
    if (!dueDate) return toast(STR.invoiceEnterDueDate);
    const items = li.collect(form);
    if (!items.length) return toast(STR.invoiceAddLine);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api('/app/api/crm/invoices', { method: 'POST', body: JSON.stringify({ clientId, quoteId: $('#i-quote', form).value.trim() || null, items, dueDate }) });
      closeDrawer();
      await loadModule('invoices');
      render();
      toast(STR.invoiceCreated);
    } catch { btn.disabled = false; toast(STR.invoiceCreateFailed); }
  });
  openDrawer(STR.invoiceFormTitle, form, true);
}

function renderQuotes(root) {
  const t = CRM.quotes;
  const q = state.search.toLowerCase();
  const rows = state.quotes
    .filter(quote => !state.filterQuoteStatus || quote.status === state.filterQuoteStatus)
    .filter(quote => !q || `${quote.number} ${quote.clientName || ''} ${quoteStatusLabel(quote.status)}`.toLowerCase().includes(q))
    .map(quote => `<tr class="conversation-row ${quote.status === 'ACEITO' ? 'is-paid' : ''}" data-quote="${escapeHTML(quote.id)}">
      <td class="id">${escapeHTML(quote.number)}</td>
      <td class="name">${escapeHTML(quote.clientName || '')}</td>
      <td>${quotePill(quote.status)}</td>
      <td class="mono muted">${fmtDay(quote.validUntil)}</td>
      <td class="num">${fmtEUR(quote.totalEur)}</td>
      <td class="right"><div class="actions">${pdfButton(quote.id, 'quotes', quote.hasPdf, quote.number)}</div></td>
    </tr>`).join('');
  const accepted = state.quotes.filter(o => o.status === 'ACEITO').reduce((sum, o) => sum + Number(o.totalEur || 0), 0);
  const sent = state.quotes.filter(o => o.status === 'PENDENTE' || o.status === 'SENT').reduce((sum, o) => sum + Number(o.totalEur || 0), 0);
  const tools = `<button class="chip ${!state.filterQuoteStatus ? 'is-on' : ''}" data-filter-quote="">${escapeHTML(t.filterAll)}</button>`
    + QUOTE_STATUSES.map(s => `<button class="chip ${state.filterQuoteStatus === s ? 'is-on' : ''}" data-filter-quote="${s}">${escapeHTML(quoteStatusLabel(s))}</button>`).join('');
  root.innerHTML = hero(labels.quotes, CRM.tabs.orcamentos.desc, statCards([
    { label: t.total, value: state.quotes.length },
    { label: t.sent, value: fmtEUR(sent) },
    { label: t.accepted, value: fmtEUR(accepted) },
  ])) + crmPanel({
    title: t.proposals,
    tag: rows ? state.quotes.filter(o => !state.filterQuoteStatus || o.status === state.filterQuoteStatus).length : 0,
    tools,
    head: `<tr><th>${escapeHTML(t.thNumber)}</th><th>${escapeHTML(t.thClient)}</th><th>${escapeHTML(t.thStatus)}</th><th>${escapeHTML(t.thValidUntil)}</th><th class="right">${escapeHTML(t.thTotal)}</th><th class="right">${escapeHTML(t.thPdf)}</th></tr>`,
    rows,
    empty: t.emptyTitle,
    emptyDesc: t.emptyDesc,
  });
  wirePdfButtons(root);
  $$('[data-filter-quote]', root).forEach(btn => btn.addEventListener('click', () => { state.filterQuoteStatus = btn.dataset.filterQuote; render(); }));
  $$('[data-quote]', root).forEach(r => r.addEventListener('click', e => {
    if (e.target.closest('[data-pdf-url]')) return;
    openQuoteDetail(r.dataset.quote);
  }));
}

async function openQuoteDetail(id) {
  let quote = state.quotes.find(q => q.id === id);
  try { quote = await api(`/app/api/crm/quotes/${encodeURIComponent(id)}`); }
  catch { /* keep list row */ }
  if (!quote) return;
  const form = document.createElement('div');
  form.className = 'form';
  const items = (quote.items || []).map(it => `<li>${escapeHTML(it.description)} · ${it.quantity} × ${fmtEUR(it.unitPriceEur)}</li>`).join('');
  const canSend = quote.status === 'PENDENTE';
  const canAccept = quote.status === 'PENDENTE' || quote.status === 'SENT';
  const canConvert = hasModule('invoices') && quote.status !== 'CANCELLED';
  form.innerHTML = `
    <p class="hint">${escapeHTML(quote.clientName || '')} · ${quotePill(quote.status)} · ${fmtEUR(quote.totalEur)}</p>
    ${items ? `<ul class="assistant__action-details">${items}</ul>` : ''}
    ${quote.notes ? `<p class="hint">${escapeHTML(quote.notes)}</p>` : ''}
    ${canConvert ? `<div class="form__row"><label class="lbl" for="q-due">${escapeHTML(STR.quoteConvertDue)}</label>
      <input class="inp" id="q-due" type="date" value="${new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10)}" /></div>` : ''}
    <div class="actions">
      ${canSend ? `<button class="btn btn--sm" type="button" data-q-status="SENT">${escapeHTML(STR.quoteMarkSent)}</button>` : ''}
      ${canAccept ? `<button class="btn btn--sm" type="button" data-q-status="ACEITO">${escapeHTML(STR.quoteAccept)}</button>` : ''}
      ${canConvert ? `<button class="btn btn--sm btn--primary" type="button" id="q-convert">${escapeHTML(STR.quoteConvert)}</button>` : ''}
    </div>`;
  form.querySelectorAll('[data-q-status]').forEach(b => b.addEventListener('click', async () => {
    try {
      await api(`/app/api/crm/quotes/${encodeURIComponent(id)}`, { method: 'PATCH', body: JSON.stringify({ status: b.dataset.qStatus }) });
      closeDrawer();
      await loadModule('quotes');
      render();
      toast(STR.quoteStatusUpdated);
    } catch { toast(STR.loadFailed); }
  }));
  $('#q-convert', form)?.addEventListener('click', async () => {
    const dueDate = $('#q-due', form).value;
    if (!dueDate) return toast(STR.invoiceEnterDueDate);
    try {
      await api(`/app/api/crm/quotes/${encodeURIComponent(id)}/invoice`, { method: 'POST', body: JSON.stringify({ dueDate }) });
      closeDrawer();
      await loadModule('quotes');
      if (hasModule('invoices')) state.invoices = await api('/app/api/crm/invoices').catch(() => state.invoices);
      render();
      toast(STR.quoteConverted);
    } catch { toast(STR.quoteConvertFailed); }
  });
  openDrawer(quote.number, form);
}

function renderInvoices(root) {
  const t = CRM.invoices;
  const q = state.search.toLowerCase();
  const rows = state.invoices
    .filter(inv => !state.filterInvoiceStatus || inv.status === state.filterInvoiceStatus)
    .filter(inv => !q || `${inv.number} ${inv.clientName || ''} ${invoiceStatusLabel(inv.status)}`.toLowerCase().includes(q))
    .map(inv => {
      const canMarkPaid = inv.status === 'PENDING' || inv.status === 'OVERDUE';
      const rowClass = inv.status === 'PAID' ? 'is-paid' : inv.status === 'OVERDUE' ? 'is-overdue' : inv.status === 'CANCELLED' ? 'is-draft' : '';
      return `<tr class="conversation-row ${rowClass}" data-invoice="${escapeHTML(inv.id)}">
        <td class="id">${escapeHTML(inv.number)}</td>
        <td class="name">${escapeHTML(inv.clientName || '')}</td>
        <td>${invoicePill(inv.status)}</td>
        <td class="mono muted">${fmtDay(inv.dueDate)}</td>
        <td class="num">${fmtEUR(inv.totalEur)}</td>
        <td class="right"><div class="actions">
          ${canMarkPaid ? `<button class="btn btn--sm btn--accent" type="button" data-mark-paid="${escapeHTML(inv.id)}">${escapeHTML(STR.markPaid)}</button>` : ''}
          ${pdfButton(inv.id, 'invoices', inv.hasPdf, inv.number)}
        </div></td>
      </tr>`;
    }).join('');
  const paid = state.invoices.filter(i => i.status === 'PAID').reduce((sum, i) => sum + Number(i.totalEur || 0), 0);
  const pending = state.invoices.filter(i => i.status === 'PENDING').reduce((sum, i) => sum + Number(i.totalEur || 0), 0);
  const overdue = state.invoices.filter(i => i.status === 'OVERDUE').reduce((sum, i) => sum + Number(i.totalEur || 0), 0);
  const tools = `<button class="chip ${!state.filterInvoiceStatus ? 'is-on' : ''}" data-filter-inv="">${escapeHTML(t.filterAll)}</button>`
    + INVOICE_STATUSES.map(s => `<button class="chip ${state.filterInvoiceStatus === s ? 'is-on' : ''}" data-filter-inv="${s}">${escapeHTML(invoiceStatusLabel(s))}</button>`).join('');
  root.innerHTML = hero(labels.invoices, CRM.tabs.faturas.desc, statCards([
    { label: t.paid, value: fmtEUR(paid) },
    { label: t.pending, value: fmtEUR(pending) },
    { label: t.overdue, value: fmtEUR(overdue) },
  ])) + crmPanel({
    title: t.documents,
    tag: state.invoices.filter(i => !state.filterInvoiceStatus || i.status === state.filterInvoiceStatus).length,
    tools,
    head: `<tr><th>${escapeHTML(t.thNumber)}</th><th>${escapeHTML(t.thClient)}</th><th>${escapeHTML(t.thStatus)}</th><th>${escapeHTML(t.thDueDate)}</th><th class="right">${escapeHTML(t.thTotal)}</th><th class="right">${escapeHTML(t.thPdfActions)}</th></tr>`,
    rows,
    empty: t.emptyTitle,
    emptyDesc: t.emptyDesc,
  });
  wirePdfButtons(root);
  $$('[data-filter-inv]', root).forEach(btn => btn.addEventListener('click', () => { state.filterInvoiceStatus = btn.dataset.filterInv; render(); }));
  $$('[data-mark-paid]', root).forEach(btn => btn.addEventListener('click', e => {
    e.stopPropagation();
    markInvoicePaid(btn.dataset.markPaid);
  }));
  $$('[data-invoice]', root).forEach(r => r.addEventListener('click', e => {
    if (e.target.closest('[data-pdf-url], [data-mark-paid]')) return;
    openInvoiceDetail(r.dataset.invoice);
  }));
}

async function markInvoicePaid(id) {
  const inv = state.invoices.find(i => i.id === id);
  const ok = await confirmDialog({
    title: STR.markPaidConfirmTitle,
    body: STR.markPaidConfirmBody({ number: inv?.number || '' }),
    okLabel: STR.markPaid,
    danger: false,
  });
  if (!ok) return;
  try {
    await api(`/app/api/crm/invoices/${encodeURIComponent(id)}/paid`, { method: 'PATCH' });
    closeDrawer();
    await loadModule('invoices');
    render();
    toast(STR.markedPaid({ number: inv?.number || '' }));
  } catch { toast(STR.markPaidFailed); }
}

async function openInvoiceDetail(id) {
  let inv = state.invoices.find(i => i.id === id);
  try { inv = await api(`/app/api/crm/invoices/${encodeURIComponent(id)}`); }
  catch { /* keep list row */ }
  if (!inv) return;
  const canMarkPaid = inv.status === 'PENDING' || inv.status === 'OVERDUE';
  const items = (inv.items || []).map(it => `<li>${escapeHTML(it.description)} · ${it.quantity} × ${fmtEUR(it.unitPriceEur)}</li>`).join('');
  const form = document.createElement('div');
  form.className = 'form';
  form.innerHTML = `
    <p class="hint">${escapeHTML(inv.clientName || '')} · ${invoicePill(inv.status)} · ${fmtEUR(inv.totalEur)}</p>
    <p class="hint">${escapeHTML(STR.thDueDate)} · ${escapeHTML(fmtDay(inv.dueDate))}</p>
    ${inv.status === 'PAID' && inv.paidAt ? `<p class="hint">${escapeHTML(STR.paidOnLabel)} · ${escapeHTML(fmtDay(inv.paidAt))}</p>` : ''}
    ${inv.quoteNumber ? `<p class="hint">${escapeHTML(STR.invoiceFromQuote({ number: inv.quoteNumber }))}</p>` : ''}
    ${items ? `<ul class="assistant__action-details">${items}</ul>` : ''}
    <div class="actions">
      ${canMarkPaid ? `<button class="btn btn--sm btn--accent" type="button" id="inv-paid">${escapeHTML(STR.markPaid)}</button>` : ''}
      ${pdfButton(inv.id, 'invoices', inv.hasPdf, inv.number)}
    </div>`;
  $('#inv-paid', form)?.addEventListener('click', () => markInvoicePaid(inv.id));
  wirePdfButtons(form);
  openDrawer(inv.number, form);
}

function catalogTypePill(type) {
  const service = type === 'service' || type === 'servico';
  return service
    ? `<span class="pill pill--accent">${escapeHTML(CRM.items.pillService)}</span>`
    : `<span class="pill pill--info">${escapeHTML(CRM.items.pillMaterial)}</span>`;
}

function renderCatalog(root) {
  const t = CRM.items;
  const q = state.search.toLowerCase();
  const rows = state.catalog
    .filter(i => !q || `${i.id || ''} ${i.description || ''} ${i.category || ''}`.toLowerCase().includes(q))
    .map(i => `<tr>
      <td>${catalogTypePill(i.type)}</td>
      <td class="muted">${escapeHTML(i.category)}</td>
      <td><div class="col"><span class="name">${escapeHTML(i.description)}</span><span class="id">${escapeHTML(i.id)}</span></div></td>
      <td class="mono muted">${escapeHTML(i.unit)}</td>
      <td class="num">${fmtEUR(i.defaultUnitPriceEur)}</td>
      <td class="right"><div class="actions">
        <button class="iconbtn" type="button" title="${escapeHTML(t.editTitle)}" data-edit-item="${escapeHTML(i.id)}"><svg width="13" height="13" viewBox="0 0 16 16"><path d="M11 2 L14 5 L5 14 L2 14 L2 11 Z" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/></svg></button>
        <button class="iconbtn iconbtn--danger" type="button" title="${escapeHTML(t.deleteTitle)}" data-delete-item="${escapeHTML(i.id)}"><svg width="13" height="13" viewBox="0 0 16 16"><path d="M3 5 L13 5 M6 5 L6 3 L10 3 L10 5 M5 5 L6 13 L10 13 L11 5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
      </div></td>
    </tr>`).join('');
  const nSrv = state.catalog.filter(i => i.type === 'service' || i.type === 'servico').length;
  const nMat = state.catalog.length - nSrv;
  root.innerHTML = hero(labels.catalog, CRM.tabs.items.desc, statCards([
    { label: t.services, value: nSrv },
    { label: t.materials, value: nMat },
  ])) + crmPanel({
    title: t.catalogTitle,
    tag: t.tag({ n: state.catalog.length }),
    head: `<tr><th>${escapeHTML(t.thType)}</th><th>${escapeHTML(t.thCategory)}</th><th>${escapeHTML(t.thDescription)}</th><th>${escapeHTML(t.thUnit)}</th><th class="right">${escapeHTML(t.thPrice)}</th><th class="right">${escapeHTML(t.thActions)}</th></tr>`,
    rows,
    empty: t.emptyTitle,
    emptyDesc: t.emptyDesc,
  });
  $$('[data-edit-item]', root).forEach(btn => btn.addEventListener('click', () => openCatalogForm(btn.dataset.editItem)));
  $$('[data-delete-item]', root).forEach(btn => btn.addEventListener('click', () => deleteCatalogItem(btn.dataset.deleteItem)));
}

function assistantActionLabel(action) {
  const args = action.arguments || {};
  if (action.toolName === 'create_client') return STR.assistantCreateClient({ name: args.name || '' });
  if (action.toolName === 'create_quote') return STR.assistantCreateQuote;
  if (action.toolName === 'update_quote') return STR.assistantUpdateQuote({ id: args.quote_id || '' });
  if (action.toolName === 'create_invoice') return STR.assistantCreateInvoice;
  if (action.toolName === 'mark_invoice_paid') return STR.assistantMarkPaid({ id: args.invoice_id || '' });
  return STR.assistantChangeData;
}

function assistantActionDetails(action) {
  const args = action.arguments || {};
  const details = [];
  if (args.name) details.push(`${STR.thName}: ${args.name}`);
  if (args.phone) details.push(`${STR.thPhone}: ${args.phone}`);
  if (args.address) details.push(`${STR.thAddress}: ${args.address}`);
  if (args.client_id) details.push(STR.assistantClientRef({ id: args.client_id }));
  if (args.quote_id) details.push(STR.assistantQuoteRef({ id: args.quote_id }));
  if (args.invoice_id) details.push(STR.assistantInvoiceRef({ id: args.invoice_id }));
  if (args.valid_until) details.push(STR.assistantValidUntil({ date: args.valid_until }));
  if (args.due_date) details.push(STR.assistantDueDate({ date: args.due_date }));
  if (args.status) details.push(STR.assistantNewStatus({ status: args.status }));
  if (args.notes) details.push(`${STR.quoteNotes}: ${args.notes}`);
  (args.items || []).forEach(item => details.push(`${item.description} · ${item.quantity || 1} × ${fmtEUR(item.price_eur)}`));
  return details.map(detail => `<li>${escapeHTML(detail)}</li>`).join('');
}

function assistantDocumentDownload(action) {
  if (action.status !== 'CONFIRMED') return '';
  const result = action.result || {};
  const id = result.id;
  if (!id) return '';
  const invoice = action.toolName === 'create_invoice' || result.type === 'invoice';
  const quote = action.toolName === 'create_quote' || result.type === 'quote';
  if (!invoice && !quote) return '';
  const type = invoice ? 'invoices' : 'quotes';
  const number = result.number || '';
  const filename = `${invoice ? 'Fatura' : 'Orcamento'} ${number || id}.pdf`;
  return `<div class="assistant__action-buttons">${pdfButton(id, type, true, STR.assistantDownloadPdf({ number }), filename)}</div>`;
}

async function openAssistantThread(id) {
  state.assistantThread = await api(`/app/api/assistant/threads/${id}`);
  render();
}

async function createAssistantThread() {
  const thread = await api('/app/api/assistant/threads', { method: 'POST', body: JSON.stringify({ title: STR.assistantNewThread }) });
  state.assistantThreads.unshift(thread);
  state.assistantThread = { thread, messages: [] };
  render();
}

function renderAssistant(root) {
  const current = state.assistantThread;
  const threadRows = state.assistantThreads.map(t => `<button class="assistant__thread ${current?.thread.id === t.id ? 'is-active' : ''}" data-assistant-thread="${t.id}" type="button"><strong>${escapeHTML(t.title)}</strong><span>${escapeHTML(fmtDate(t.updatedAt))}</span></button>`).join('');
  const messages = (current?.messages || []).map(m => {
    const bubble = m.content ? `<div class="chat__msg chat__msg--${m.role === 'user' ? 'user' : 'bot'}">${escapeHTML(m.content)}</div>` : '';
    if (!m.action) return bubble;
    const pending = m.action.status === 'PENDING';
    const details = assistantActionDetails(m.action);
    return `${bubble}<div class="assistant__action"><div><span class="assistant__action-label">${escapeHTML(STR.assistantProposedAction)}</span><strong>${escapeHTML(assistantActionLabel(m.action))}</strong></div>${details ? `<ul class="assistant__action-details">${details}</ul>` : ''}<span class="pill">${escapeHTML(STR['assistantStatus' + m.action.status] || m.action.status)}</span>${pending ? `<div class="assistant__action-buttons"><button class="btn btn--sm btn--ghost" data-assistant-cancel="${m.action.id}" type="button">${escapeHTML(STR.assistantCancel)}</button><button class="btn btn--sm btn--primary" data-assistant-confirm="${m.action.id}" type="button">${escapeHTML(STR.assistantConfirm)}</button></div>` : ''}${assistantDocumentDownload(m.action)}</div>`;
  }).join('');
  root.innerHTML = `${hero(labels['ai-assistant'], STR.assistantDesc)}<div class="assistant"><aside class="assistant__sidebar"><button class="btn btn--primary" id="assistant-new" type="button">${escapeHTML(STR.assistantNewThread)}</button><div class="assistant__threads">${threadRows || `<p class="chat__empty">${escapeHTML(STR.assistantNoThreads)}</p>`}</div></aside><div class="panel assistant__chat"><div class="chat__log assistant__log" id="assistant-log">${messages || `<div class="chat__empty">${escapeHTML(STR.assistantEmpty)}</div>`}${assistantBusy ? `<div class="chat__msg chat__msg--bot chat__typing">${escapeHTML(STR.typing)}</div>` : ''}</div><form class="chat__form" id="assistant-form"><textarea class="inp chat__input assistant__input" id="assistant-input" rows="1" maxlength="4000" placeholder="${escapeHTML(STR.assistantPlaceholder)}" ${current && !assistantBusy ? '' : 'disabled'}></textarea><button class="btn btn--primary" type="submit" ${current && !assistantBusy ? '' : 'disabled'}>${escapeHTML(STR.send)}</button></form></div></div>`;
  $('#assistant-new').addEventListener('click', createAssistantThread);
  $$('[data-assistant-thread]').forEach(b => b.addEventListener('click', () => openAssistantThread(b.dataset.assistantThread)));
  const log = $('#assistant-log'); log.scrollTop = log.scrollHeight;
  const composer = $('#assistant-input');
  const resizeComposer = () => {
    composer.style.height = 'auto';
    composer.style.height = `${Math.min(composer.scrollHeight, 140)}px`;
  };
  composer.addEventListener('input', resizeComposer);
  resizeComposer();
  composer.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
      e.preventDefault();
      $('#assistant-form').requestSubmit();
    }
  });
  $('#assistant-form').addEventListener('submit', async e => {
    e.preventDefault();
    if (!current || assistantBusy) return;
    const input = $('#assistant-input'), content = input.value.trim();
    if (!content) return;
    input.value = '';
    assistantBusy = true;
    current.messages.push({ role: 'user', content });
    renderAssistant(root);
    try {
      state.assistantThread = await api(`/app/api/assistant/threads/${current.thread.id}/messages`, { method: 'POST', body: JSON.stringify({ content }) });
      state.assistantThreads = await api('/app/api/assistant/threads');
    } catch { toast(STR.assistantError); }
    finally { assistantBusy = false; render(); }
  });
  $$('[data-assistant-confirm]').forEach(b => b.addEventListener('click', () => updateAssistantAction(current.thread.id, b.dataset.assistantConfirm, 'confirm')));
  $$('[data-assistant-cancel]').forEach(b => b.addEventListener('click', () => updateAssistantAction(current.thread.id, b.dataset.assistantCancel, 'cancel')));
  wirePdfButtons(root);
}

async function updateAssistantAction(threadId, actionId, decision) {
  if (assistantBusy) return;
  assistantBusy = true;
  render();
  try {
    state.assistantThread = await api(`/app/api/assistant/threads/${threadId}/actions/${encodeURIComponent(actionId)}/${decision}`, { method: 'POST', body: '{}' });
    state.assistantThreads = await api('/app/api/assistant/threads');
  } catch { toast(STR.assistantActionError); }
  finally { assistantBusy = false; render(); }
}
let personaPollTimer;
async function refreshPersona() {
  state.persona = await api('/app/api/persona');
  if (state.active === 'persona') render();
  clearTimeout(personaPollTimer);
  if (state.persona.status === 'COMPILING') personaPollTimer = setTimeout(refreshPersona, 3000);
}

function renderPersona(root) {
  const p = state.persona || {};
  const sources = p.sources || [];
  const compiling = p.status === 'COMPILING';
  const sourceRows = sources.map(s => `<tr><td>${s.kind === 'FILE' ? '📄' : '📝'} ${escapeHTML(s.label)}</td><td>${s.compiled ? `<span class="muted">${STR.sourceSynced}</span>` : `<span>${STR.sourcePending}</span>`}</td><td class="mono muted">${fmtDate(s.createdAt)}</td><td class="right"><button class="btn btn--sm" data-del-source="${s.id}">${STR.remove}</button></td></tr>`).join('');
  root.innerHTML = `${hero(labels.persona, STR.personaDesc)}
    <div class="view__stats" style="margin-bottom:18px">
      <div class="stat"><div class="stat__label">${STR.thStatus}</div><div class="stat__value">${compiling ? STR.personaCompiling : escapeHTML(p.status || 'EMPTY')}</div></div>
      <div class="stat"><div class="stat__label">${STR.statVersion}</div><div class="stat__value">${p.version || 0}</div></div>
      <div class="stat"><div class="stat__label">${STR.statTokens}</div><div class="stat__value">${p.tokenEstimate || 0}</div></div>
      <div class="stat"><div class="stat__label">${STR.statUpdated}</div><div class="stat__value" style="font-size:16px">${escapeHTML(fmtDate(p.updatedAt))}</div></div>
    </div>

    <div class="panel" style="padding:18px;margin-bottom:18px">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">
        <h2 class="view__title" style="font-size:18px">${escapeHTML(STR.testBotTitle)}</h2>
        <button class="btn btn--sm" id="persona-chat-clear">${escapeHTML(STR.clear)}</button>
      </div>
      <p class="view__desc" style="margin-bottom:14px">${escapeHTML(STR.testBotDesc)}</p>
      <div class="chat__log" id="persona-chat-log"></div>
      <form class="chat__form" id="persona-chat-form">
        <input class="inp chat__input" id="persona-chat-input" placeholder="${escapeHTML(STR.chatPlaceholder)}" autocomplete="off" />
        <button class="btn btn--primary" type="submit" id="persona-chat-send">${escapeHTML(STR.send)}</button>
      </form>
    </div>

    <div class="panel" style="padding:18px;margin-bottom:18px">
      <h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(STR.addInfoTitle)}</h2>
      <p class="view__desc" style="margin-bottom:14px">${escapeHTML(STR.addInfoDesc)}</p>
      <form class="form" id="persona-note-form">
        <div class="form__row form__row--full">
          <label class="lbl" for="persona-note">${escapeHTML(STR.noteLabel)}</label>
          <textarea class="txt" id="persona-note" rows="4" placeholder="${escapeHTML(STR.notePlaceholder)}"></textarea>
        </div>
        <button class="btn btn--primary" type="submit">${escapeHTML(STR.addNote)}</button>
      </form>
      <div class="form__row form__row--full" style="margin-top:14px">
        <label class="lbl" for="persona-file">${escapeHTML(STR.fileLabel)}</label>
        <input class="inp" id="persona-file" type="file" accept=".pdf,.txt,.md,.markdown" />
        <div class="hint">${escapeHTML(STR.fileHint)}</div>
      </div>
    </div>

    <div class="panel" style="padding:18px;margin-bottom:18px">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div><h2 class="view__title" style="font-size:18px">${escapeHTML(STR.sourcesTitle)}</h2><p class="view__desc">${escapeHTML(STR.sourcesDesc)}</p></div>
        <button class="btn btn--sm" id="persona-rebuild" ${compiling ? 'disabled' : ''}>${escapeHTML(STR.rebuildAll)}</button>
      </div>
      <div class="tbl-wrap"><table class="tbl"><thead><tr><th>${STR.thSource}</th><th>${STR.thState}</th><th>${STR.thCreated}</th><th class="right">${STR.thActions}</th></tr></thead><tbody>${sourceRows || `<tr><td colspan="4"><div class="empty"><p class="empty__title">${escapeHTML(STR.noSourcesTitle)}</p><p class="empty__desc">${escapeHTML(STR.noSourcesDesc)}</p></div></td></tr>`}</tbody></table></div>
    </div>

    <div class="panel" style="padding:18px">
      <div class="settings-tabs">
        <button type="button" class="chip ${state.personaAdvanced ? 'is-on' : ''}" id="persona-advanced-toggle">${escapeHTML(STR.personaAdvanced)}</button>
      </div>
      <p class="view__desc">${escapeHTML(STR.personaAdvancedHint)}</p>
      ${state.personaAdvanced ? `
      <h2 class="view__title" style="font-size:18px;margin:14px 0 6px">${escapeHTML(STR.compiledTitle)}</h2>
      <p class="view__desc" style="margin-bottom:14px">${escapeHTML(STR.compiledDesc)}</p>
      <form class="form" id="persona-form">
        <div class="form__row form__row--full">
          <textarea class="txt" id="persona-text" rows="16" placeholder="${escapeHTML(STR.compiledPlaceholder)}">${escapeHTML(p.compiledInstructions || '')}</textarea>
        </div>
        <button class="btn btn--primary" type="submit">${escapeHTML(STR.saveManual)}</button>
      </form>` : ''}
    </div>`;

  $('#persona-advanced-toggle')?.addEventListener('click', () => {
    state.personaAdvanced = !state.personaAdvanced;
    render();
  });
  $('#persona-form')?.addEventListener('submit', async e => {
    e.preventDefault();
    const compiledInstructions = $('#persona-text').value.trim();
    state.persona = await api('/app/api/persona', { method: 'PUT', body: JSON.stringify({ compiledInstructions }) });
    toast(STR.personaSaved);
    render();
  });
  $('#persona-note-form').addEventListener('submit', async e => {
    e.preventDefault();
    const content = $('#persona-note').value.trim();
    if (!content) return;
    state.persona = await api('/app/api/persona/sources', { method: 'POST', body: JSON.stringify({ content }) });
    toast(STR.noteAdded);
    render();
    refreshPersona();
  });
  $('#persona-file').addEventListener('change', async e => {
    const file = e.target.files[0];
    if (!file) return;
    try {
      state.persona = await uploadPersonaFile(file);
      toast(STR.fileUploaded);
      render();
      refreshPersona();
    } catch (err) { toast(err.message || STR.uploadFailed); }
  });
  $('#persona-rebuild').addEventListener('click', async () => {
    state.persona = await api('/app/api/persona/rebuild', { method: 'POST', body: '{}' });
    toast(STR.rebuildStarted);
    render();
    refreshPersona();
  });
  $$('[data-del-source]').forEach(b => b.addEventListener('click', async () => {
    await api(`/app/api/persona/sources/${b.dataset.delSource}`, { method: 'DELETE' });
    await refreshPersona();
  }));

  renderPersonaChatLog(personaChatBusy);
  $('#persona-chat-clear').addEventListener('click', () => { state.personaChat = []; renderPersonaChatLog(); });
  $('#persona-chat-form').addEventListener('submit', async e => {
    e.preventDefault();
    if (personaChatBusy) return;
    const input = $('#persona-chat-input');
    const text = input.value.trim();
    if (!text) return;
    state.personaChat.push({ role: 'user', content: text });
    input.value = '';
    personaChatBusy = true;
    $('#persona-chat-send').disabled = true;
    renderPersonaChatLog(true);
    try {
      const res = await api('/app/api/persona/test', { method: 'POST', body: JSON.stringify({ messages: state.personaChat }) });
      state.personaChat.push({ role: 'assistant', content: res.reply });
    } catch (err) {
      state.personaChat.push({ role: 'assistant', content: STR.chatError });
    } finally {
      personaChatBusy = false;
      renderPersonaChatLog();
      const send = $('#persona-chat-send'); if (send) send.disabled = false;
      const inp = $('#persona-chat-input'); if (inp) inp.focus();
    }
  });
}

function renderPersonaChatLog(busy = false) {
  const log = $('#persona-chat-log');
  if (!log) return;
  const msgs = state.personaChat || [];
  if (!msgs.length && !busy) {
    log.innerHTML = `<div class="chat__empty">${escapeHTML(STR.chatEmpty)}</div>`;
    return;
  }
  log.innerHTML = msgs.map(m => `<div class="chat__msg chat__msg--${m.role === 'user' ? 'user' : 'bot'}">${escapeHTML(m.content)}</div>`).join('')
    + (busy ? `<div class="chat__msg chat__msg--bot chat__typing">${escapeHTML(STR.typing)}</div>` : '');
  log.scrollTop = log.scrollHeight;
}

async function uploadPersonaFile(file) {
  const form = new FormData();
  form.append('file', file);
  const res = await fetch('/app/api/persona/sources/file', { method: 'POST', headers: token ? { Authorization: `Bearer ${token}` } : {}, body: form });
  if (res.status === 401) { localStorage.removeItem('dashboardToken'); token = ''; renderLogin(); throw new Error('unauthorized'); }
  if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.error || `HTTP ${res.status}`); }
  return res.json();
}
function widgetDraft() {
  if (!state.widgetDraft) {
    state.widgetDraft = {
      title: state.me?.tenant?.name || STR.widgetDefaultTitle,
      subtitle: STR.widgetDefaultSubtitle,
      welcome: STR.widgetDefaultWelcome,
      placeholder: STR.widgetDefaultPlaceholder,
      launcher: STR.widgetDefaultLauncher,
      accent: '#7c5cfc',
      position: 'right',
      theme: 'light',
    };
  }
  return state.widgetDraft;
}

function widgetSnippet(key) {
  const config = widgetDraft();
  const attrs = [
    ['data-key', key], ['data-title', config.title], ['data-subtitle', config.subtitle],
    ['data-welcome', config.welcome], ['data-placeholder', config.placeholder],
    ['data-launcher', config.launcher], ['data-accent', config.accent],
    ['data-position', config.position], ['data-theme', config.theme],
  ];
  return `<script src="${location.origin}/widget/widget.js" ${attrs.map(([name, value]) => `${name}="${escapeHTML(value)}"`).join(' ')} defer><\/script>`;
}

function homeLayoutPanel() {
  const layout = state.overviewLayout || { hidden: [], available: [] };
  const hidden = new Set(layout.hidden || []);
  const groups = [
    ['sections', STR.homeLayoutSections],
    ['snapshots', STR.homeLayoutSnapshots],
  ];
  const blocks = groups.map(([group, title]) => {
    const items = (layout.available || []).filter(opt => opt.group === group);
    if (!items.length) return '';
    const rows = items.map(opt => {
      const shown = !hidden.has(opt.id);
      const copy = homeCardCopy(opt.id);
      return `<button type="button" class="queue__item choice ${shown ? 'is-on' : ''}" data-home-card="${escapeHTML(opt.id)}" aria-pressed="${shown}"><div><strong>${escapeHTML(copy.title)}</strong><span>${escapeHTML(copy.detail)}</span></div><span class="queue__meta"><span class="pill ${shown ? 'pill--ok' : ''}">${escapeHTML(shown ? STR.homeLayoutShown : STR.homeLayoutHidden)}</span></span></button>`;
    }).join('');
    return `<div class="panel" style="padding:18px;margin-bottom:18px"><h2 class="view__title" style="font-size:18px;margin-bottom:14px">${escapeHTML(title)}</h2><div class="choice-list">${rows}</div></div>`;
  }).join('');
  return `<div class="panel" style="padding:18px;margin-bottom:18px"><h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(STR.homeLayoutTitle)}</h2><p class="view__desc">${escapeHTML(STR.homeLayoutDesc)}</p></div>${blocks}`;
}

async function toggleHomeCard(id) {
  const layout = state.overviewLayout || { hidden: [], available: [] };
  const hidden = new Set(layout.hidden || []);
  if (hidden.has(id)) hidden.delete(id); else hidden.add(id);
  try {
    state.overviewLayout = await api('/app/api/settings/overview', { method: 'PUT', body: JSON.stringify({ hidden: [...hidden] }) });
    state.overview = await api('/app/api/overview').catch(() => state.overview);
    toast(STR.homeLayoutSaved);
    render();
  } catch {
    toast(STR.homeLayoutSaveFailed);
  }
}

function renderSettings(root) {
  const channels = state.me?.tenant?.channels || [];
  const wa = channels.find(c => c.platform === 'WHATSAPP');
  const ig = channels.find(c => c.platform === 'INSTAGRAM');
  const web = state.webWidget || { publicKey: null, allowedOrigins: [] };
  const draft = widgetDraft();
  const section = state.settingsSection || 'channels';
  const tabs = [
    ['home', STR.settingsHome],
    ['channels', STR.settingsChannels],
    ['widget', STR.settingsWidget],
    ['language', STR.settingsLanguage],
    ['documents', STR.settingsDocuments],
  ];
  const chips = `<div class="settings-tabs">${tabs.map(([id, label]) => `<button type="button" class="chip ${section === id ? 'is-on' : ''}" data-settings="${id}">${escapeHTML(label)}</button>`).join('')}</div>`;
  const channelsPanel = `<div class="panel" style="padding:18px;margin-bottom:18px">
      <h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(STR.channelsTitle)}</h2>
      <p class="view__desc" style="margin-bottom:14px">${escapeHTML(STR.channelsDesc)}</p>
      <div class="tbl-wrap"><table class="tbl">
        <thead><tr><th>${escapeHTML(STR.colChannel)}</th><th>${escapeHTML(STR.colAccount)}</th><th>${escapeHTML(STR.colStatus)}</th><th class="right">${escapeHTML(STR.colActions)}</th></tr></thead>
        <tbody>
          <tr><td class="name">WhatsApp</td><td class="mono">${escapeHTML(wa?.displayName || wa?.externalId || '—')}</td><td>${wa ? escapeHTML(STR.connected) : `<span class="muted">${escapeHTML(STR.notConnected)}</span>`}</td>
            <td class="right">${state.whatsAppSignup?.enabled ? `<button class="btn btn--sm" id="wa-connect">${escapeHTML(wa ? STR.waReconnect : STR.waConnect)}</button>` : ''}</td></tr>
          <tr><td class="name">Instagram</td><td class="mono">${ig ? escapeHTML(ig.displayName ? '@' + ig.displayName : ig.externalId) : '—'}</td><td>${ig ? escapeHTML(STR.connected) : `<span class="muted">${escapeHTML(STR.notConnected)}</span>`}</td>
            <td class="right"><button class="btn btn--sm" id="ig-connect">${escapeHTML(ig ? STR.igReconnect : STR.igConnect)}</button>${ig ? ` <button class="btn btn--sm btn--ghost" id="ig-disconnect">${escapeHTML(STR.igDisconnect)}</button>` : ''}</td></tr>
          <tr><td class="name">${escapeHTML(STR.webRowName)}</td><td class="mono">${web.publicKey ? escapeHTML(web.publicKey) : '—'}</td><td>${web.publicKey ? escapeHTML(STR.connected) : `<span class="muted">${escapeHTML(STR.notConnected)}</span>`}</td>
            <td class="right">${web.publicKey ? `<span class="muted">${escapeHTML(STR.webRegenerate)}</span>` : `<button class="btn btn--sm" id="web-generate">${escapeHTML(STR.webGenerate)}</button>`}</td></tr>
        </tbody>
      </table></div>
      <div class="hint" style="margin-top:10px">${escapeHTML(ig && !ig.commentsEnabled ? STR.instagramReconnectBanner : STR.channelsHint)}</div>
    </div>`;
  const widgetPanel = `<div class="panel widget-customizer">
      <div class="widget-customizer__head">
        <div>
      <h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(STR.webTitle)}</h2>
          <p class="view__desc">${escapeHTML(STR.webDesc)}</p>
        </div>
        ${web.publicKey ? `<span class="pill pill--ok">${escapeHTML(STR.webRegenerate)}</span>` : ''}
      </div>
      ${web.publicKey ? `
        <div class="widget-customizer__studio">
          <form class="form widget-customizer__controls" id="widget-customizer-form">
            <div class="widget-customizer__section">
              <h3>${escapeHTML(STR.widgetContentTitle)}</h3>
              <div class="form__row">
                <label class="lbl" for="widget-title">${escapeHTML(STR.widgetTitleLabel)}</label>
                <input class="inp" id="widget-title" maxlength="60" value="${escapeHTML(draft.title)}" />
              </div>
              <div class="form__row">
                <label class="lbl" for="widget-subtitle">${escapeHTML(STR.widgetSubtitleLabel)}</label>
                <input class="inp" id="widget-subtitle" maxlength="80" value="${escapeHTML(draft.subtitle)}" />
              </div>
              <div class="form__row">
                <label class="lbl" for="widget-welcome">${escapeHTML(STR.widgetWelcomeLabel)}</label>
                <textarea class="txt" id="widget-welcome" rows="3" maxlength="240">${escapeHTML(draft.welcome)}</textarea>
              </div>
              <div class="form__row">
                <label class="lbl" for="widget-placeholder">${escapeHTML(STR.widgetPlaceholderLabel)}</label>
                <input class="inp" id="widget-placeholder" maxlength="80" value="${escapeHTML(draft.placeholder)}" />
              </div>
              <div class="form__row">
                <label class="lbl" for="widget-launcher">${escapeHTML(STR.widgetLauncherLabel)}</label>
                <input class="inp" id="widget-launcher" maxlength="40" value="${escapeHTML(draft.launcher)}" />
              </div>
            </div>
            <div class="widget-customizer__section">
              <h3>${escapeHTML(STR.widgetAppearanceTitle)}</h3>
              <div class="form__grid form__grid--3">
                <div class="form__row">
                  <label class="lbl" for="widget-accent">${escapeHTML(STR.widgetAccentLabel)}</label>
                  <input class="widget-customizer__color" id="widget-accent" type="color" value="${escapeHTML(draft.accent)}" />
                </div>
                <div class="form__row">
                  <label class="lbl" for="widget-position">${escapeHTML(STR.widgetPositionLabel)}</label>
                  <select class="sel" id="widget-position"><option value="right" ${draft.position === 'right' ? 'selected' : ''}>${escapeHTML(STR.widgetPositionRight)}</option><option value="left" ${draft.position === 'left' ? 'selected' : ''}>${escapeHTML(STR.widgetPositionLeft)}</option></select>
                </div>
                <div class="form__row">
                  <label class="lbl" for="widget-theme">${escapeHTML(STR.widgetThemeLabel)}</label>
                  <select class="sel" id="widget-theme"><option value="light" ${draft.theme === 'light' ? 'selected' : ''}>${escapeHTML(STR.widgetThemeLight)}</option><option value="dark" ${draft.theme === 'dark' ? 'selected' : ''}>${escapeHTML(STR.widgetThemeDark)}</option><option value="auto" ${draft.theme === 'auto' ? 'selected' : ''}>${escapeHTML(STR.widgetThemeAuto)}</option></select>
                </div>
              </div>
            </div>
          </form>
          <div class="widget-preview">
            <div class="widget-preview__label"><span>${escapeHTML(STR.widgetPreview)}</span><span>${escapeHTML(STR.widgetPreviewLive)}</span></div>
            <div class="widget-preview__stage" id="widget-preview-stage">
              <div class="widget-demo widget-demo--${escapeHTML(draft.theme)} widget-demo--${escapeHTML(draft.position)}" id="widget-demo">
                <div class="widget-demo__panel">
                  <div class="widget-demo__header" id="widget-preview-header">
                    <span class="widget-demo__avatar" id="widget-preview-avatar">${escapeHTML(draft.title.charAt(0).toUpperCase() || 'C')}</span>
                    <span><strong id="widget-preview-title">${escapeHTML(draft.title)}</strong><small><i></i><span id="widget-preview-subtitle">${escapeHTML(draft.subtitle)}</span></small></span>
                    <b aria-hidden="true">×</b>
                  </div>
                  <div class="widget-demo__body"><span class="widget-demo__message" id="widget-preview-welcome">${escapeHTML(draft.welcome)}</span></div>
                  <div class="widget-demo__footer"><span id="widget-preview-placeholder">${escapeHTML(draft.placeholder)}</span><i>➤</i></div>
                </div>
                <div class="widget-demo__launcher" id="widget-preview-launcher"><span aria-hidden="true">◇</span><strong>${escapeHTML(draft.launcher)}</strong></div>
              </div>
            </div>
          </div>
        </div>
        <div class="widget-customizer__install">
          <div>
            <h3>${escapeHTML(STR.widgetInstallTitle)}</h3>
            <p class="hint">${escapeHTML(STR.widgetInstallHint)}</p>
          </div>
          <div class="form__row form__row--full">
            <textarea class="txt mono" id="web-snippet" rows="5" readonly>${escapeHTML(widgetSnippet(web.publicKey))}</textarea>
          </div>
          <button class="btn btn--primary" id="web-copy" type="button">${escapeHTML(STR.webCopy)}</button>
        </div>
        <form class="form widget-customizer__security" id="web-origins-form">
          <div class="form__row form__row--full">
            <label class="lbl" for="web-origins">${escapeHTML(STR.webOriginsLabel)}</label>
            <textarea class="txt mono" id="web-origins" rows="3" placeholder="https://www.yoursite.com">${escapeHTML((web.allowedOrigins || []).join('\n'))}</textarea>
            <div class="hint">${escapeHTML(STR.webOriginsHint)}</div>
          </div>
          <button class="btn btn--ghost" type="submit">${escapeHTML(STR.webOriginsSave)}</button>
        </form>
      ` : `<div class="empty widget-customizer__empty"><p class="empty__title">${escapeHTML(STR.widgetEmptyTitle)}</p><p class="empty__desc">${escapeHTML(STR.widgetEmptyDesc)}</p><button class="btn btn--primary" id="web-generate-2" type="button">${escapeHTML(STR.webGenerate)}</button></div>`}
    </div>`;
  const languagePanel = `<div class="panel" style="padding:18px;margin-bottom:18px">
      <h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(I18N.t('common.lang.title'))}</h2>
      <p class="view__desc" style="margin-bottom:14px">${escapeHTML(I18N.t('common.lang.desc'))}</p>
      <div class="form__row" style="max-width:280px">
        <label class="lbl" for="ui-locale">${escapeHTML(I18N.t('common.lang.label'))}</label>
        <select class="sel" id="ui-locale">${I18N.SUPPORTED.map(l => `<option value="${l}" ${l === I18N.locale() ? 'selected' : ''}>${escapeHTML(I18N.LANG_NAMES[l] || l)}</option>`).join('')}</select>
      </div>
    </div>
    <div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.moreSettingsTitle)}</p><p class="empty__desc">${escapeHTML(STR.moreSettingsDesc)}</p></div></div>`;
  const body = section === 'home' ? homeLayoutPanel()
    : section === 'widget' ? widgetPanel
    : section === 'language' ? languagePanel
    : section === 'documents' ? renderDocumentTemplatePanel()
    : channelsPanel;
  root.innerHTML = `${hero(labels.settings, STR.settingsDesc)}${chips}${body}`;
  $$('[data-settings]', root).forEach(b => b.addEventListener('click', () => { state.settingsSection = b.dataset.settings; render(); }));
  $$('[data-home-card]', root).forEach(b => b.addEventListener('click', () => toggleHomeCard(b.dataset.homeCard)));
  $('#wa-connect')?.addEventListener('click', connectWhatsApp);
  $('#ig-connect')?.addEventListener('click', connectInstagram);
  $('#ig-disconnect')?.addEventListener('click', () => disconnectInstagram(ig));
  const localeSel = $('#ui-locale');
  if (localeSel) localeSel.addEventListener('change', async () => {
    const locale = localeSel.value;
    I18N.choose(locale);
    I18N.applyDom(document);
    document.documentElement.lang = I18N.locale();
    window.refreshThemeLabels?.();
    try { await api('/app/api/settings/locale', { method: 'POST', body: JSON.stringify({ locale }) }); toast(I18N.t('common.lang.saved')); }
    catch { toast(I18N.t('common.lang.saveFailed')); }
    renderNav();
    render();
  });
  $$('#web-generate, #web-generate-2').forEach(b => b.addEventListener('click', generateWebWidget));
  const customizer = $('#widget-customizer-form');
  if (customizer) {
    const controls = {
      title: $('#widget-title'), subtitle: $('#widget-subtitle'), welcome: $('#widget-welcome'),
      placeholder: $('#widget-placeholder'), launcher: $('#widget-launcher'), accent: $('#widget-accent'),
      position: $('#widget-position'), theme: $('#widget-theme'),
    };
    const refreshPreview = () => {
      Object.entries(controls).forEach(([name, control]) => { state.widgetDraft[name] = control.value; });
      const config = state.widgetDraft;
      const demo = $('#widget-demo');
      demo.className = `widget-demo widget-demo--${config.theme} widget-demo--${config.position}`;
      demo.style.setProperty('--widget-demo-accent', config.accent);
      const hex = config.accent.replace('#', '');
      const rgb = /^[0-9a-f]{6}$/i.test(hex) ? [0, 2, 4].map(i => parseInt(hex.slice(i, i + 2), 16)) : [37, 99, 235];
      demo.style.setProperty('--widget-demo-accent-ink', (rgb[0] * 299 + rgb[1] * 587 + rgb[2] * 114) / 1000 > 155 ? '#111827' : '#ffffff');
      $('#widget-preview-title').textContent = config.title;
      $('#widget-preview-avatar').textContent = config.title.charAt(0).toUpperCase() || 'C';
      $('#widget-preview-subtitle').textContent = config.subtitle;
      $('#widget-preview-welcome').textContent = config.welcome;
      $('#widget-preview-placeholder').textContent = config.placeholder;
      $('#widget-preview-launcher strong').textContent = config.launcher;
      $('#web-snippet').value = widgetSnippet(web.publicKey);
    };
    Object.values(controls).forEach(control => {
      control.addEventListener('input', refreshPreview);
      control.addEventListener('change', refreshPreview);
    });
    refreshPreview();
  }
  const copyBtn = $('#web-copy');
  if (copyBtn) copyBtn.addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(widgetSnippet(web.publicKey)); toast(STR.webCopied); }
    catch { const t = $('#web-snippet'); t.select(); document.execCommand('copy'); toast(STR.webCopied); }
  });
  const originsForm = $('#web-origins-form');
  if (originsForm) originsForm.addEventListener('submit', async e => {
    e.preventDefault();
    const allowedOrigins = $('#web-origins').value.split('\n').map(s => s.trim()).filter(Boolean);
    try { state.webWidget = await api('/app/api/web-widget', { method: 'POST', body: JSON.stringify({ allowedOrigins }) }); toast(STR.webOriginsSaved); render(); }
    catch { toast(STR.webGenerateFailed); }
  });
  if (section === 'documents') wireDocumentTemplateForm();
}

function renderDocumentTemplatePanel() {
  return `<div id="doc-template-panel"></div>`;
}

function wireDocumentTemplateForm() {
  const host = $('#doc-template-panel');
  if (!host || !window.DocTemplate) return;
  DocTemplate.mount(host, {
    getTemplate: () => state.documentTemplate,
    setTemplate: next => { state.documentTemplate = next; },
    tenantName: () => state.me?.tenant?.name || '',
    api,
    getToken: () => token,
    toast,
    STR,
    escapeHTML,
  });
}

async function generateWebWidget() {
  try {
    state.webWidget = await api('/app/api/web-widget', { method: 'POST', body: JSON.stringify({ allowedOrigins: [] }) });
    state.me = await api('/app/api/me');
    toast(STR.webGenerated);
    render();
  } catch { toast(STR.webGenerateFailed); }
}

async function connectInstagram() {
  let res;
  try { res = await api('/app/api/instagram/connect'); }
  catch (e) { toast(e.message === 'unauthorized' ? STR.sessionExpired : STR.igUnavailable); return; }
  const popup = window.open(res.authorizeUrl, 'ig-oauth', 'width=600,height=750');
  if (!popup) { toast(STR.igAllowPopups); return; }
  const onMsg = async ev => {
    if (ev.origin !== window.location.origin || ev.data?.type !== 'ig-oauth') return;
    window.removeEventListener('message', onMsg);
    if (ev.data.status === 'connected') {
      toast(STR.igConnected);
      state.me = await api('/app/api/me');
      if (state.active === 'settings') render();
    } else {
      toast(STR.igFailed(ev.data.reason));
    }
  };
  window.addEventListener('message', onMsg);
}

async function disconnectInstagram(ig) {
  if (!ig) return;
  const ok = await confirmDialog({
    title: STR.igDisconnectConfirmTitle,
    body: STR.igDisconnectConfirmBody({ account: ig.displayName ? '@' + ig.displayName : ig.externalId }),
    okLabel: STR.igDisconnectAction,
  });
  if (!ok) return;
  try {
    await api(`/app/api/channels/INSTAGRAM/${encodeURIComponent(ig.externalId)}`, { method: 'DELETE' });
    state.me = await api('/app/api/me');
    toast(STR.igDisconnected);
    render();
  } catch { toast(STR.igDisconnectFailed); }
}

function loadFacebookSdk(appId, graphVersion) {
  if (window.FB) {
    window.FB.init({ appId, version: graphVersion, cookie: true, xfbml: false });
    return Promise.resolve(window.FB);
  }
  if (fbSdkPromise) return fbSdkPromise;
  fbSdkPromise = new Promise((resolve, reject) => {
    window.fbAsyncInit = () => {
      window.FB.init({ appId, version: graphVersion, cookie: true, xfbml: false });
      resolve(window.FB);
    };
    const script = document.createElement('script');
    script.id = 'facebook-jssdk';
    script.src = 'https://connect.facebook.net/en_US/sdk.js';
    script.async = true;
    script.defer = true;
    script.onerror = () => reject(new Error('facebook_sdk_load_failed'));
    document.body.appendChild(script);
  });
  return fbSdkPromise;
}

function waitForWhatsAppSignupMessage() {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      window.removeEventListener('message', onMessage);
      reject(new Error('signup_message_timeout'));
    }, 5 * 60 * 1000);
    const onMessage = ev => {
      if (!['https://www.facebook.com', 'https://web.facebook.com'].includes(ev.origin)) return;
      const data = typeof ev.data === 'string' ? (() => { try { return JSON.parse(ev.data); } catch (_) { return null; } })() : ev.data;
      if (data?.type !== 'WA_EMBEDDED_SIGNUP') return;
      if (data.event && data.event !== 'FINISH') {
        clearTimeout(timer);
        window.removeEventListener('message', onMessage);
        reject(new Error(data.event === 'CANCEL' ? 'signup_cancelled' : 'signup_not_finished'));
        return;
      }
      const wabaId = data.data?.waba_id || data.data?.wabaId;
      const phoneNumberId = data.data?.phone_number_id || data.data?.phoneNumberId;
      if (!wabaId || !phoneNumberId) return;
      clearTimeout(timer);
      window.removeEventListener('message', onMessage);
      resolve({ wabaId, phoneNumberId });
    };
    window.addEventListener('message', onMessage);
  });
}

function facebookLoginForBusiness(FB, configId) {
  return new Promise((resolve, reject) => {
    FB.login(response => {
      const code = response?.authResponse?.code;
      if (!code) {
        reject(new Error(response?.status === 'not_authorized' ? 'not_authorized' : 'missing_code'));
        return;
      }
      resolve(code);
    }, {
      config_id: configId,
      response_type: 'code',
      override_default_response_type: true,
      extras: { setup: {} },
    });
  });
}

async function connectWhatsApp() {
  const cfg = state.whatsAppSignup;
  if (!cfg?.enabled) { toast(STR.waNotConfigured); return; }
  try {
    const FB = await loadFacebookSdk(cfg.appId, cfg.graphVersion || 'v21.0');
    toast(STR.waOpenPopup);
    const sessionPromise = waitForWhatsAppSignupMessage();
    const codePromise = facebookLoginForBusiness(FB, cfg.configId);
    const [session, code] = await Promise.all([sessionPromise, codePromise]);
    await api('/app/api/whatsapp/connect', {
      method: 'POST',
      body: JSON.stringify({ code, wabaId: session.wabaId, phoneNumberId: session.phoneNumberId }),
    });
    toast(STR.waConnected);
    state.me = await api('/app/api/me');
    if (state.active === 'settings') render();
  } catch (e) {
    const messages = {
      signup_cancelled: STR.waCancelled,
      missing_code: STR.waMissingCode,
      signup_message_timeout: STR.waTimeout,
      facebook_sdk_load_failed: STR.waSdkFailed,
      unauthorized: STR.sessionExpired,
    };
    toast(messages[e.message] || STR.waFailed({ msg: e.message }));
  }
}

// When the OAuth popup lands back on /app/?ig=..., relay the outcome to the opener and close.
// Returns true if this load was an OAuth popup (so the normal app boot is skipped).
function handleOAuthPopup() {
  const params = new URLSearchParams(window.location.search);
  const ig = params.get('ig');
  if (!ig || !window.opener) return false;
  window.opener.postMessage({ type: 'ig-oauth', status: ig, reason: params.get('reason'), tenant: params.get('tenant') }, window.location.origin);
  window.close();
  return true;
}

let drawerPrevFocus = null;
let drawerKeyHandler = null;

function drawerFocusables(panel) {
  return [...panel.querySelectorAll('a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])')]
    .filter(el => !el.hidden && el.offsetParent !== null);
}

function closeDrawer() {
  const root = $('#drawer');
  if (root) {
    root.hidden = true;
    if (drawerKeyHandler) {
      root.removeEventListener('keydown', drawerKeyHandler);
      drawerKeyHandler = null;
    }
  }
  const prev = drawerPrevFocus;
  drawerPrevFocus = null;
  if (prev && typeof prev.focus === 'function') prev.focus();
}

function openDrawer(title, body, wide = false) {
  const root = $('#drawer');
  const panel = $('.drawer__panel', root);
  if (drawerKeyHandler) root.removeEventListener('keydown', drawerKeyHandler);
  drawerPrevFocus = document.activeElement;
  panel.classList.toggle('drawer__panel--wide', wide);
  $('#drawer-title').textContent = title;
  $('#drawer-body').innerHTML = '';
  $('#drawer-body').appendChild(body);
  root.hidden = false;
  $$('[data-close]', root).forEach(b => { b.onclick = closeDrawer; });
  drawerKeyHandler = e => {
    if (e.key === 'Escape') { e.preventDefault(); closeDrawer(); return; }
    if (e.key !== 'Tab') return;
    const list = drawerFocusables(panel);
    if (!list.length) return;
    const first = list[0];
    const last = list[list.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  };
  root.addEventListener('keydown', drawerKeyHandler);
  requestAnimationFrame(() => {
    const preferred = body.querySelector('input, select, textarea, button');
    (preferred || drawerFocusables(panel)[0])?.focus();
  });
}

function serviceName(id) { return state.bookingServices.find(s => s.id === id)?.name || id; }

function igThumb(url) {
  if (url) return `<img class="ig-thumb" src="${escapeHTML(url)}" alt="" />`;
  return `<div class="ig-thumb ig-thumb--empty" aria-hidden="true">◇</div>`;
}

function captionPreview(text) {
  const value = (text || '').replace(/\s+/g, ' ').trim();
  if (!value) return STR.instagramNoCaption;
  return value.length > 80 ? `${value.slice(0, 77)}…` : value;
}

function renderInstagram(root) {
  const data = state.instagram || { connected: false, comments: [], media: [], unrepliedCount: 0 };
  if (!data.connected) {
    root.innerHTML = hero(labels.instagram, STR.instagramDesc) +
      `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.instagramNotConnected)}</p><p class="empty__desc">${escapeHTML(STR.instagramNotConnectedDesc)}</p><button type="button" class="btn btn--primary" data-ig-settings>${escapeHTML(STR.instagramGoSettings)}</button></div></div>`;
    $('[data-ig-settings]', root)?.addEventListener('click', async () => {
      state.settingsSection = 'channels';
      await setActive('settings');
    });
    return;
  }
  const filter = state.instagramFilter || 'needs';
  const unreplied = (data.comments || []).filter(c => c.needsReply);
  const banner = data.needsReconnect
    ? `<div class="panel" style="padding:16px;margin-bottom:16px"><p class="view__desc" style="margin:0 0 10px">${escapeHTML(STR.instagramReconnectBanner)}</p><button type="button" class="btn btn--sm btn--primary" data-ig-settings>${escapeHTML(STR.igReconnect)}</button></div>`
    : '';
  const tools = `<div class="panel__tools">
      <button type="button" class="chip ${filter === 'needs' ? 'is-on' : ''}" data-ig-filter="needs">${escapeHTML(STR.instagramNeedsReply)}</button>
      <button type="button" class="chip ${filter === 'posts' ? 'is-on' : ''}" data-ig-filter="posts">${escapeHTML(STR.instagramPosts)}</button>
    </div>`;
  const stats = statCards([
    { label: STR.instagramNeedsReply, value: data.unrepliedCount || unreplied.length },
    { label: STR.instagramPosts, value: (data.media || []).length },
  ]);
  let body;
  if (filter === 'posts') {
    const rows = (data.media || []).map(m => `<tr data-ig-media="${escapeHTML(m.id)}">
      <td>${igThumb(m.thumbnailUrl)}</td>
      <td class="name">${escapeHTML(captionPreview(m.caption))}</td>
      <td class="mono">${m.commentsCount ?? '—'}</td>
      <td>${m.unrepliedCount ? `<span class="pill pill--warn">${escapeHTML(String(m.unrepliedCount))}</span>` : '—'}</td>
      <td class="mono muted">${escapeHTML(fmtDate(m.publishedAt))}</td>
    </tr>`).join('');
    body = crmPanel({
      title: STR.instagramPosts,
      tag: (data.media || []).length,
      tools,
      head: `<tr><th></th><th>${STR.instagramThPost}</th><th>${STR.instagramThComment}</th><th>${STR.instagramUnreplied}</th><th>${STR.instagramThWhen}</th></tr>`,
      rows,
      empty: STR.instagramEmptyPosts,
      emptyDesc: STR.instagramEmptyPostsDesc,
    });
  } else {
    const rows = unreplied.map(c => `<tr data-ig-comment="${escapeHTML(c.id)}" data-ig-media="${escapeHTML(c.mediaId)}">
      <td>${igThumb(c.thumbnailUrl)}</td>
      <td class="name">${escapeHTML(c.fromUsername ? '@' + c.fromUsername : '—')}</td>
      <td>${escapeHTML(c.text || '')}</td>
      <td class="muted">${escapeHTML(captionPreview(c.caption))}</td>
      <td class="mono muted">${escapeHTML(fmtDate(c.createdAt))}</td>
    </tr>`).join('');
    body = crmPanel({
      title: STR.instagramNeedsReply,
      tag: unreplied.length,
      tools,
      head: `<tr><th></th><th>${STR.instagramThFrom}</th><th>${STR.instagramThComment}</th><th>${STR.instagramThPost}</th><th>${STR.instagramThWhen}</th></tr>`,
      rows,
      empty: STR.instagramEmptyComments,
      emptyDesc: STR.instagramEmptyCommentsDesc,
    });
  }
  root.innerHTML = hero(labels.instagram, STR.instagramDesc, stats) + banner + body;
  $$('[data-ig-filter]', root).forEach(b => b.addEventListener('click', () => {
    state.instagramFilter = b.dataset.igFilter;
    renderInstagram(root);
  }));
  $('[data-ig-settings]', root)?.addEventListener('click', async () => {
    state.settingsSection = 'channels';
    await setActive('settings');
  });
  $$('[data-ig-media]', root).forEach(el => el.addEventListener('click', () => openInstagramPost(el.dataset.igMedia, el.dataset.igComment)));
}

async function openInstagramPost(mediaId, commentId) {
  let detail;
  try { detail = await api(`/app/api/instagram/media/${encodeURIComponent(mediaId)}`); }
  catch { toast(STR.instagramSyncFailed); return; }
  const media = detail.media || {};
  const comments = detail.comments || [];
  const permalink = media.permalink
    ? `<a class="btn btn--sm btn--ghost" href="${escapeHTML(media.permalink)}" target="_blank" rel="noopener">${escapeHTML(STR.instagramOpenPost)}</a>`
    : '';
  const items = comments.map(c => `<div class="ig-comment${c.id === commentId ? ' is-target' : ''}${c.fromAccount ? ' ig-comment--own' : ''}">
      <strong>${escapeHTML(c.fromUsername ? '@' + c.fromUsername : (c.fromAccount ? (state.instagram?.username ? '@' + state.instagram.username : '—') : '—'))}</strong>
      ${c.needsReply ? `<span class="pill pill--warn">${escapeHTML(STR.instagramUnreplied)}</span>` : ''}
      <p>${escapeHTML(c.text || '')}</p>
      <span class="muted">${escapeHTML(fmtDate(c.createdAt))}</span>
      ${c.needsReply ? `<button type="button" class="btn btn--sm" data-ig-reply="${escapeHTML(c.id)}">${escapeHTML(STR.instagramReply)}</button>` : ''}
    </div>`).join('');
  const body = document.createElement('div');
  body.innerHTML = `<div class="ig-post">
      ${igThumb(media.thumbnailUrl)}
      <div><strong>${escapeHTML(captionPreview(media.caption))}</strong>${permalink}</div>
    </div>
    <div class="ig-comments">${items || `<p class="muted">${escapeHTML(STR.instagramEmptyComments)}</p>`}</div>
    <form class="form" id="ig-reply-form" hidden>
      <input type="hidden" name="commentId" />
      <div class="form__row"><label class="lbl">${escapeHTML(STR.instagramReply)}</label><textarea class="inp" name="message" rows="3" required placeholder="${escapeHTML(STR.instagramReplyPlaceholder)}"></textarea></div>
      <button class="btn btn--primary" type="submit">${escapeHTML(STR.instagramReply)}</button>
    </form>`;
  const form = $('#ig-reply-form', body);
  const showReply = id => {
    form.hidden = false;
    form.commentId.value = id;
    form.message.focus();
  };
  $$('[data-ig-reply]', body).forEach(b => b.addEventListener('click', () => showReply(b.dataset.igReply)));
  if (commentId && comments.some(c => c.id === commentId && c.needsReply)) showReply(commentId);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const id = form.commentId.value;
    const message = form.message.value.trim();
    if (!id || !message) return;
    try {
      await api(`/app/api/instagram/comments/${encodeURIComponent(id)}/replies`, { method: 'POST', body: JSON.stringify({ message }) });
      toast(STR.instagramReplied);
      closeDrawer();
      await loadModule('instagram');
      render();
    } catch { toast(STR.instagramReplyFailed); }
  });
  openDrawer(labels.instagram, body, true);
}

function renderBookings(root) {
  const weekStart = state.bookingWeekStart || startOfWeek();
  const weekLabel = `${weekStart.toLocaleDateString(uiLocale(), { day: '2-digit', month: 'short' })} – ${addDays(weekStart, 6).toLocaleDateString(uiLocale(), { day: '2-digit', month: 'short', year: 'numeric' })}`;
  const toolbar = `<div class="booking-toolbar">
    <div class="booking-toolbar__views">
      <button type="button" class="btn btn--sm ${state.bookingView === 'week' ? 'btn--primary' : ''}" data-booking-view="week">${escapeHTML(STR.bookingsWeek)}</button>
      <button type="button" class="btn btn--sm ${state.bookingView === 'list' ? 'btn--primary' : ''}" data-booking-view="list">${escapeHTML(STR.bookingsList)}</button>
    </div>
    <div class="booking-toolbar__nav">
      <button type="button" class="btn btn--sm" data-week-shift="-7">${escapeHTML(STR.bookingsPrev)}</button>
      <button type="button" class="btn btn--sm" data-week-shift="0">${escapeHTML(STR.bookingsToday)}</button>
      <button type="button" class="btn btn--sm" data-week-shift="7">${escapeHTML(STR.bookingsNext)}</button>
      <span class="booking-toolbar__label mono">${escapeHTML(weekLabel)}</span>
    </div>
    <div class="booking-toolbar__actions">
      <button type="button" class="btn btn--sm" data-booking-services>${escapeHTML(STR.bookingsManageServices)}</button>
      <button type="button" class="btn btn--sm" data-booking-availability>${escapeHTML(STR.bookingsManageAvailability)}</button>
    </div>
  </div>`;
  const body = state.bookingView === 'list' ? bookingListHtml() : bookingWeekHtml(weekStart);
  root.innerHTML = hero(labels.bookings, STR.bookingsDesc) + toolbar + body;
  $$('[data-booking-view]').forEach(b => b.addEventListener('click', () => { state.bookingView = b.dataset.bookingView; render(); }));
  $$('[data-week-shift]').forEach(b => b.addEventListener('click', async () => {
    const shift = Number(b.dataset.weekShift);
    state.bookingWeekStart = shift === 0 ? startOfWeek() : addDays(state.bookingWeekStart || startOfWeek(), shift);
    await loadModule('bookings');
    render();
  }));
  $('[data-booking-services]')?.addEventListener('click', openBookingServicesForm);
  $('[data-booking-availability]')?.addEventListener('click', openBookingAvailabilityForm);
  $$('[data-booking-id]').forEach(el => el.addEventListener('click', () => openBookingForm(state.bookings.find(x => x.id === el.dataset.bookingId))));
  $$('[data-booking-slot]').forEach(el => el.addEventListener('click', () => openBookingForm(null, el.dataset.bookingSlot)));
}

function bookingWeekHtml(weekStart) {
  const hours = Array.from({ length: 12 }, (_, i) => i + 8);
  const days = Array.from({ length: 7 }, (_, i) => addDays(weekStart, i));
  const head = `<div class="cal-grid__corner"></div>${days.map((d, i) => `<div class="cal-grid__dayhead"><div>${escapeHTML(STR[`weekday${i + 1}`])}</div><div class="mono muted">${d.toLocaleDateString(uiLocale(), { day: '2-digit', month: '2-digit' })}</div></div>`).join('')}`;
  const rows = hours.map(hour => {
    const cells = days.map((day, di) => {
      const slotStart = new Date(day); slotStart.setHours(hour, 0, 0, 0);
      const slotEnd = new Date(day); slotEnd.setHours(hour + 1, 0, 0, 0);
      const events = state.bookings.filter(b => {
        const t = new Date(b.startAt).getTime();
        return t >= slotStart.getTime() && t < slotEnd.getTime();
      });
      const chips = events.map(b => `<button type="button" class="cal-event cal-event--${b.status.toLowerCase()}" data-booking-id="${b.id}"><span>${escapeHTML(fmtTime(b.startAt))} · ${escapeHTML(b.contactName)}</span></button>`).join('');
      return `<div class="cal-grid__cell" data-booking-slot="${toLocalInputValue(slotStart.toISOString())}">${chips || ''}</div>`;
    }).join('');
    return `<div class="cal-grid__hour mono muted">${String(hour).padStart(2, '0')}:00</div>${cells}`;
  }).join('');
  return `<div class="panel cal-wrap"><div class="cal-grid">${head}${rows}</div></div>`;
}

function bookingListHtml() {
  const q = state.search.toLowerCase();
  const rows = state.bookings
    .filter(b => !q || `${b.contactName} ${b.contactPhone} ${serviceName(b.serviceId)}`.toLowerCase().includes(q))
    .map(b => `<tr data-booking-id="${b.id}" class="conversation-row"><td class="mono">${escapeHTML(fmtDate(b.startAt))}</td><td class="name">${escapeHTML(b.contactName)}<div class="muted mono">${escapeHTML(b.contactPhone)}</div></td><td>${escapeHTML(serviceName(b.serviceId))}</td><td>${escapeHTML(bookingStatusLabel(b.status))}</td></tr>`)
    .join('');
  return panelTable(`<tr><th>${STR.bookingsThWhen}</th><th>${STR.bookingsThContact}</th><th>${STR.bookingsThService}</th><th>${STR.bookingsThStatus}</th></tr>`, rows, STR.bookingsEmptyList);
}

function openBookingForm(booking = null, slotLocal = '') {
  const body = document.createElement('form');
  body.className = 'form';
  const services = state.bookingServices.filter(s => s.active || s.id === booking?.serviceId);
  body.innerHTML = `
    <div class="form__row"><label class="lbl">${escapeHTML(STR.bookingsContactName)}</label><input class="inp" name="contactName" required value="${escapeHTML(booking?.contactName || '')}" /></div>
    <div class="form__row"><label class="lbl">${escapeHTML(STR.bookingsContactPhone)}</label><input class="inp" name="contactPhone" required value="${escapeHTML(booking?.contactPhone || '')}" /></div>
    <div class="form__row"><label class="lbl">${escapeHTML(STR.bookingsService)}</label><select class="sel" name="serviceId" required>
      <option value="">${escapeHTML(STR.bookingsChooseService)}</option>
      ${services.map(s => `<option value="${s.id}" ${booking?.serviceId === s.id ? 'selected' : ''}>${escapeHTML(s.name)} (${s.durationMinutes}m)</option>`).join('')}
    </select></div>
    <div class="form__row"><label class="lbl">${escapeHTML(STR.bookingsStart)}</label><input class="inp" type="datetime-local" name="startAt" required value="${escapeHTML(booking ? toLocalInputValue(booking.startAt) : slotLocal)}" /></div>
    <div class="form__row"><label class="lbl">${escapeHTML(STR.bookingsStatus)}</label><select class="sel" name="status">
      ${['PENDING', 'CONFIRMED', 'CANCELLED', 'COMPLETED'].map(s => `<option value="${s}" ${(booking?.status || 'CONFIRMED') === s ? 'selected' : ''}>${escapeHTML(bookingStatusLabel(s))}</option>`).join('')}
    </select></div>
    <div class="form__row"><label class="lbl">${escapeHTML(STR.bookingsNotes)}</label><textarea class="inp" name="notes" rows="3">${escapeHTML(booking?.notes || '')}</textarea></div>
    <button class="btn btn--primary" type="submit">${escapeHTML(STR.bookingsSave)}</button>`;
  body.addEventListener('submit', async e => {
    e.preventDefault();
    const fd = new FormData(body);
    const payload = {
      contactName: String(fd.get('contactName') || '').trim(),
      contactPhone: String(fd.get('contactPhone') || '').trim(),
      serviceId: String(fd.get('serviceId') || ''),
      startAt: String(fd.get('startAt') || ''),
      status: String(fd.get('status') || 'CONFIRMED'),
      notes: String(fd.get('notes') || ''),
    };
    if (!payload.contactName || !payload.contactPhone || !payload.serviceId || !payload.startAt) return toast(STR.bookingsValidate);
    try {
      if (booking) await api(`/app/api/bookings/${booking.id}`, { method: 'POST', body: JSON.stringify(payload) });
      else await api('/app/api/bookings', { method: 'POST', body: JSON.stringify(payload) });
      closeDrawer();
      toast(booking ? STR.bookingsUpdated : STR.bookingsCreated);
      await loadModule('bookings');
      render();
    } catch (err) {
      toast(String(err.message || '').includes('409') ? STR.bookingsConflict : STR.bookingsValidate);
    }
  });
  openDrawer(booking ? STR.bookingsEdit : STR.bookingsNew, body);
}

function openBookingServicesForm() {
  const body = document.createElement('div');
  body.className = 'form';
  const list = state.bookingServices.map(s => `<tr><td class="name">${escapeHTML(s.name)}</td><td class="mono">${s.durationMinutes}m</td><td>${s.active ? '✓' : '—'}</td><td class="right"><button type="button" class="btn btn--sm" data-edit-service="${s.id}">${escapeHTML(s.active ? STR.bookingsDeactivate : STR.bookingsActivate)}</button></td></tr>`).join('');
  body.innerHTML = `${panelTable(`<tr><th>${STR.bookingsServiceName}</th><th>${STR.bookingsDuration}</th><th>${STR.bookingsActive}</th><th></th></tr>`, list)}
    <form id="service-create" class="form" style="margin-top:16px">
      <div class="form__row"><label class="lbl">${escapeHTML(STR.bookingsServiceName)}</label><input class="inp" name="name" required /></div>
      <div class="form__row"><label class="lbl">${escapeHTML(STR.bookingsDuration)}</label><input class="inp" type="number" min="5" name="durationMinutes" value="30" required /></div>
      <button class="btn btn--primary" type="submit">${escapeHTML(STR.bookingsAddService)}</button>
    </form>`;
  body.querySelector('#service-create').addEventListener('submit', async e => {
    e.preventDefault();
    const fd = new FormData(e.target);
    await api('/app/api/bookings/services', { method: 'POST', body: JSON.stringify({ name: fd.get('name'), durationMinutes: Number(fd.get('durationMinutes') || 30) }) });
    toast(STR.bookingsServiceSaved);
    await loadModule('bookings');
    openBookingServicesForm();
  });
  $$('[data-edit-service]', body).forEach(b => b.addEventListener('click', async () => {
    const service = state.bookingServices.find(s => s.id === b.dataset.editService);
    if (!service) return;
    const active = !service.active;
    await api(`/app/api/bookings/services/${service.id}`, { method: 'POST', body: JSON.stringify({ active }) });
    toast(STR.bookingsServiceSaved);
    await loadModule('bookings');
    openBookingServicesForm();
  }));
  openDrawer(STR.bookingsManageServices, body, true);
}

function openBookingAvailabilityForm() {
  const body = document.createElement('form');
  body.className = 'form';
  const rows = (state.bookingAvailability.length ? state.bookingAvailability : [{ dayOfWeek: 1, startLocal: '09:00', endLocal: '17:00' }])
    .map((r, idx) => `<div class="form__row booking-avail-row" data-idx="${idx}">
      <select class="sel" name="dayOfWeek">${[1,2,3,4,5,6,7].map(d => `<option value="${d}" ${r.dayOfWeek === d ? 'selected' : ''}>${escapeHTML(STR[`weekday${d}`])}</option>`).join('')}</select>
      <input class="inp" type="time" name="startLocal" value="${escapeHTML(r.startLocal)}" />
      <input class="inp" type="time" name="endLocal" value="${escapeHTML(r.endLocal)}" />
    </div>`).join('');
  body.innerHTML = `<p class="hint">${escapeHTML(STR.bookingsManageAvailability)}</p>${rows}
    <button type="button" class="btn btn--sm" id="add-avail">${escapeHTML(STR.bookingsAddWindow)}</button>
    <button class="btn btn--primary" type="submit" style="margin-top:12px">${escapeHTML(STR.bookingsSaveHours)}</button>`;
  $('#add-avail', body).addEventListener('click', () => {
    const row = document.createElement('div');
    row.className = 'form__row booking-avail-row';
    row.innerHTML = `<select class="sel" name="dayOfWeek">${[1,2,3,4,5,6,7].map(d => `<option value="${d}">${escapeHTML(STR[`weekday${d}`])}</option>`).join('')}</select>
      <input class="inp" type="time" name="startLocal" value="09:00" />
      <input class="inp" type="time" name="endLocal" value="17:00" />`;
    body.insertBefore(row, $('#add-avail', body));
  });
  body.addEventListener('submit', async e => {
    e.preventDefault();
    const rules = $$('.booking-avail-row', body).map(row => ({
      dayOfWeek: Number($('[name=dayOfWeek]', row).value),
      startLocal: $('[name=startLocal]', row).value,
      endLocal: $('[name=endLocal]', row).value,
    }));
    await api('/app/api/bookings/availability', { method: 'PUT', body: JSON.stringify({ rules }) });
    toast(STR.bookingsAvailabilitySaved);
    closeDrawer();
    await loadModule('bookings');
    render();
  });
  openDrawer(STR.bookingsManageAvailability, body, true);
}

async function init() {
  if (handleOAuthPopup()) return;
  I18N.applyDom(document);
  $('#btn-logout').addEventListener('click', () => { localStorage.removeItem('dashboardToken'); token = ''; renderLogin(); });
  $('#search').addEventListener('input', e => { state.search = e.target.value; render(); });
  $('#btn-new').addEventListener('click', () => {
    if (state.active === 'clients') return openClientForm();
    if (state.active === 'services') return openServiceForm();
    if (state.active === 'catalog') return openCatalogForm();
    if (state.active === 'quotes') return openQuoteForm();
    if (state.active === 'invoices') return openInvoiceForm();
    if (state.active === 'bookings') return openBookingForm();
    toast(STR.quickCreateSoon);
  });
  document.addEventListener('keydown', e => { if (e.key === '/' && !['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) { e.preventDefault(); $('#search').focus(); } });
  if (!token) return renderLogin();
  state.active = (location.hash || '').replace('#', '') || 'overview';
  try { await bootAuthed(); } catch { renderLogin(); }
}
init();
