/* The dashboard's foundation (/app): the session token and the page's `state`, api() and signOut(), the
   catalog sections, formatting (money, dates, periods, relative times), what invoices received and owe,
   and status labels and pills. DOM shortcuts, escapeHTML(), toast() and confirmDialog() come from
   /admin/common.js, which the backoffice loads too.
   The page's own scripts, from this one to app.js in index.html's order, are classic scripts sharing one
   global scope, so each can call what another declares. None runs anything at its top level that needs
   another script: they only declare, and app.js calls init() once all of them have loaded. */
let token = localStorage.getItem('dashboardToken') || '';
let state = {
  me: null, overview: null, contacts: [], conversations: [], clients: [], quotes: [], invoices: [], catalog: [],
  persona: null, personaChat: [], webWidget: null, widgetDraft: null, documentTemplate: null, companies: null,
  clientServices: [], filterServiceStatus: '', filterServiceClient: '', filterServiceEmployee: '', filterServicePeriod: '', filterServicePeriodKey: '',
  filterInvoicePeriod: '', filterInvoicePeriodKey: '',
  filterFinanceiroPeriod: '', filterFinanceiroPeriodKey: '', filterFinanceiroType: '', filterFinanceiroClient: '',
  suppliers: [], employees: [], payments: [], filterPaymentStatus: '', filterPaymentSupplier: '', filterSupplierType: '',
  // pendingSubmissions: services employees registered that wait for the team (Employees page, nav count).
  // portal: an employee's own session — their submissions and what they pick from.
  pendingSubmissions: [], portal: { submissions: [], clients: [], catalog: [] }, filterSubmissionStatus: '', mySites: [],
  bookings: [], bookingUpcoming: [], bookingServices: [], bookingAvailability: [], bookingView: 'week', bookingWeekStart: '', bookingStatusFilter: '',
  instagram: { connected: false, commentsEnabled: false, needsReconnect: false, username: null, unrepliedCount: 0, comments: [], media: [] },
  instagramFilter: 'needs',
  search: '', active: 'overview', selectedAsset: '',
  // Directory ('clients', 'suppliers' or 'employees') whose archived records are listed instead of the active ones.
  archivedView: '', archivedRows: [],
  // What the company asks for about clients: required standard fields and its own fields (GET /app/api/crm/clients/fields).
  clientFields: null,
  // Column choices of the directory lists and the column order of every list as read from this browser, by
  // storage key (columnChoicesKey, columnOrderKey).
  columnChoices: {}, columnOrders: {},
  filterQuoteStatus: '', filterInvoiceStatus: '',
  selectedConversation: null, threadMessages: [],
  // threadFor: conversation whose messages are loaded; cursor: server time for the next /updates poll.
  inbox: { filter: 'all', threadFor: null, cursor: null, drafts: {}, sending: false, reading: null, templates: null },
  settingsSection: 'channels', overviewLayout: null,
  // Persona page: the open tab, unsaved edits ({ behavior, compiledInstructions }, either may be absent) and the history list.
  personaTab: 'behavior', personaDraft: {}, personaVersions: null, personaPreview: null, personaFlows: null,
  whatsAppSignup: { enabled: false },
  // integrations: the company's connected accounts (Settings); email: whether it can send, and from where.
  integrations: null, email: null,
  // Building: the projects, and each page's list with its filters.
  projects: [], projectsView: 'list', projectsFilter: 'active',
  diary: [], diaryProject: '', issues: [], issuesFilter: 'open', issuesProject: '',
  changeOrders: [], changesFilter: 'all', changesProject: '', measurements: [], measurementsFilter: 'all', measurementsProject: '',
  fetched: {
    conversations: false, invoices: false, bookings: false, instagram: false, payments: false, submissions: false,
    projects: false, issues: false, changeOrders: false, measurements: false,
  },
};

// Module nav labels + user-facing copy come from the shared i18n catalogs (admin/catalog.*.js).
// `labels`/`STR` are live proxies over the active locale, so every render() reads the current language.
const labels = I18N.section('common.nav');
const STR = I18N.section('app');
const CRM = I18N.section('admin');
const GOOGLE = I18N.section('app.integrations.google');
// An employee's own sign-in (EmployeePortal on the server) has only their own pages: the services they
// register, with the time clock their hours, and with the site diary or issues the sites they run. `/me`
// lists the ones the company has.
const PORTAL_TAB = 'my-services';
const HOURS_TAB = 'my-hours';
const SITES_TAB = 'my-sites';
const PORTAL = I18N.section('app.portal');
const TIME = I18N.section('app.time');
const SUB = I18N.section('admin.submissions');
const FIELDS = I18N.section('admin.fields');
const COLUMNS = I18N.section('app.columns');
const isPortal = () => !!state.me?.employee;
// Registered services become Serviços rows, so the team reviews them with both modules on.
const submissionsOn = () => hasModule('employees') && hasModule('services');
// Employees sign in for their services, the time clock, or the sites they run.
const employeePortalOn = () => submissionsOn() || hasModule('timesheets') || (hasModule('employees') && (hasModule('site-diary') || hasModule('site-issues')));
const googleText = (key, params) => I18N.t(`app.integrations.google.${key}`, params);
// Server error keys the catalog doesn't know read as the fallback.
const googleTextOr = (key, fallback) => {
  const text = googleText(key);
  return typeof text === 'string' && text !== `app.integrations.google.${key}` ? text : fallback;
};
// The i18n proxy answers a missing key with its own path, so check for that before trusting it.
const catalogTextOr = (section, prefix, key, fallback) => (key && section[key] !== `${prefix}.${key}` ? section[key] : fallback);
const tApp = (key, params) => I18N.t(`app.${key}`, params);
// AI chat bubbles render as plain pre-wrapped text (see .chat__msg in style.css), but the model
// writes markdown (**bold**). Escape first, then turn only **bold** into <strong> — everything
// else (numbered/bulleted lines, line breaks) already reads fine as plain text under pre-wrap.
const renderChatText = (s = '') => escapeHTML(s).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
// Filtering a list as someone types has to ignore case and accents: "orcamento" finds "Orçamento".
const foldText = (s = '') => String(s).toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
const capFirst = s => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);
const uiLocale = () => (window.I18N && I18N.locale()) || 'pt-PT';
const fmtEUR = n => new Intl.NumberFormat(uiLocale(), { style: 'currency', currency: 'EUR' }).format(Number(n || 0));
const fmtEURWhole = n => new Intl.NumberFormat(uiLocale(), { style: 'currency', currency: 'EUR', minimumFractionDigits: 0, maximumFractionDigits: 0 }).format(Number(n || 0));
const fmtEURCompact = n => new Intl.NumberFormat(uiLocale(), { style: 'currency', currency: 'EUR', notation: 'compact', minimumFractionDigits: 0, maximumFractionDigits: 1 }).format(Number(n || 0));
const fmtMinutes = n => new Intl.NumberFormat(uiLocale(), { style: 'unit', unit: 'minute', unitDisplay: 'short' }).format(n);
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
function relTime(iso) {
  if (!iso) return '';
  const seconds = (new Date(iso).getTime() - Date.now()) / 1000;
  const abs = Math.abs(seconds);
  const rtf = new Intl.RelativeTimeFormat(uiLocale(), { numeric: 'auto', style: 'short' });
  if (abs < 45) return rtf.format(0, 'second');
  if (abs < 3600) return rtf.format(Math.round(seconds / 60), 'minute');
  if (abs < 86400) return rtf.format(Math.round(seconds / 3600), 'hour');
  if (abs < 7 * 86400) return rtf.format(Math.round(seconds / 86400), 'day');
  return new Date(iso).toLocaleDateString(uiLocale(), { day: 'numeric', month: 'short', timeZone: tenantTz() });
}
function relDay(value) {
  const [y1, m1, d1] = localDay(value).split('-').map(Number);
  const [y2, m2, d2] = localDay(new Date().toISOString()).split('-').map(Number);
  if (!y1 || !y2) return '';
  const diff = Math.round((Date.UTC(y1, m1 - 1, d1) - Date.UTC(y2, m2 - 1, d2)) / 86400000);
  return new Intl.RelativeTimeFormat(uiLocale(), { numeric: 'auto' }).format(diff, 'day');
}
function fmtWhen(iso) {
  if (!iso) return '';
  if (localDay(iso) === localDay(new Date().toISOString())) return fmtTime(iso);
  const soon = Math.abs(new Date(iso).getTime() - Date.now()) < 6 * 86400000;
  const opts = soon ? { weekday: 'short', hour: '2-digit', minute: '2-digit' } : { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' };
  return new Date(iso).toLocaleString(uiLocale(), { ...opts, timeZone: tenantTz() });
}
function localDay(iso) {
  if (!iso) return '';
  const raw = String(iso);
  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) return raw;
  const dt = new Date(raw);
  if (Number.isNaN(dt.getTime())) return raw.slice(0, 10);
  return new Intl.DateTimeFormat('en-CA', { timeZone: tenantTz(), year: 'numeric', month: '2-digit', day: '2-digit' }).format(dt);
}
function periodKey(iso, grain) {
  const day = localDay(iso);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return '';
  if (grain === 'month') return day.slice(0, 7);
  const [y, m, d] = day.split('-').map(Number);
  const utc = new Date(Date.UTC(y, m - 1, d));
  const dow = utc.getUTCDay() || 7;
  utc.setUTCDate(utc.getUTCDate() - dow + 1);
  const pad = n => String(n).padStart(2, '0');
  return `${utc.getUTCFullYear()}-${pad(utc.getUTCMonth() + 1)}-${pad(utc.getUTCDate())}`;
}
function periodLabel(key, grain) {
  if (!key) return '—';
  if (grain === 'month') {
    const [y, m] = key.split('-').map(Number);
    return new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString(uiLocale(), { month: 'long', year: 'numeric', timeZone: 'UTC' });
  }
  const [y, m, d] = key.split('-').map(Number);
  const start = new Date(Date.UTC(y, m - 1, d));
  const end = new Date(start);
  end.setUTCDate(end.getUTCDate() + 6);
  const startLabel = start.toLocaleDateString(uiLocale(), { day: 'numeric', month: 'short', timeZone: 'UTC' });
  const endLabel = end.toLocaleDateString(uiLocale(), { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
  return `${startLabel} – ${endLabel}`;
}
function groupByPeriod(items, grain, dateOf) {
  const groups = new Map();
  for (const item of items) {
    const key = periodKey(dateOf(item), grain);
    if (!key) continue;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(item);
  }
  return [...groups.entries()].sort((a, b) => b[0].localeCompare(a[0]));
}
// `extra` adds views past the periods (e.g. Services' By client); each needs its own label.
function periodChips(active, attr, labels, extra = []) {
  return ['', 'week', 'month', ...extra].map(period => `<button class="btn btn--sm ${active === period ? 'btn--primary' : ''}" type="button" ${attr}="${period}">${escapeHTML(labels[period])}</button>`).join('');
}
function sumEur(items) {
  return items.reduce((sum, item) => sum + Number(item.totalEur || 0), 0);
}
function currentPeriodKey(grain) {
  return periodKey(new Date().toISOString(), grain);
}
function shiftPeriodKey(key, grain, steps) {
  if (!key || !steps) return key || '';
  if (grain === 'month') {
    const [y, m] = key.split('-').map(Number);
    const d = new Date(Date.UTC(y, m - 1 + steps, 1));
    return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
  }
  const [y, m, d] = key.split('-').map(Number);
  const utc = new Date(Date.UTC(y, m - 1, d + steps * 7));
  const pad = n => String(n).padStart(2, '0');
  return `${utc.getUTCFullYear()}-${pad(utc.getUTCMonth() + 1)}-${pad(utc.getUTCDate())}`;
}
function periodDelta(current, previous) {
  if (previous == null || previous === 0) return { text: '', trend: '' };
  const pct = Math.round(((current - previous) / Math.abs(previous)) * 100);
  const sign = pct > 0 ? '+' : '';
  return { text: `${sign}${pct}%`, trend: pct > 0 ? 'up' : pct < 0 ? 'down' : '' };
}
function groupItems(groups, key) {
  return groups.find(entry => entry[0] === key)?.[1] || [];
}
function serviceRollup(items) {
  const billable = items.filter(s => s.status !== 'CANCELLED');
  const openRows = billable.filter(s => s.status === 'OPEN');
  const invoicedRows = billable.filter(s => s.status === 'INVOICED');
  return { jobs: billable.length, open: sumEur(openRows), invoiced: sumEur(invoicedRows), total: sumEur(billable) };
}
function invoiceRollup(items) {
  const live = items.filter(inv => inv.status !== 'CANCELLED');
  const owed = list => list.reduce((sum, inv) => sum + invoiceOutstandingEur(inv), 0);
  return {
    count: live.length,
    paid: live.reduce((sum, inv) => sum + invoicePaidEur(inv), 0),
    pending: owed(live.filter(inv => inv.status === 'PENDING')),
    overdue: owed(live.filter(inv => inv.status === 'OVERDUE')),
    total: sumEur(live),
  };
}
// An invoice paid in installments owes each part by its own date. The server keeps `dueDate` on the next
// unpaid part and sends what was received (`paidEur`) and what is still owed (`outstandingEur`).
const invoiceParts = inv => inv.installments || [];
const invoicePaidEur = inv => Number(inv.paidEur ?? (inv.status === 'PAID' ? inv.totalEur : 0)) || 0;
const invoiceOutstandingEur = inv => Number(inv.outstandingEur ?? (inv.status === 'PENDING' || inv.status === 'OVERDUE' ? inv.totalEur : 0)) || 0;
const sumParts = parts => parts.reduce((sum, p) => sum + Number(p.amountEur || 0), 0);
// Nothing flips an invoice or payment to OVERDUE, so, like Home, a pending one past its due date counts.
function isPastDue(doc, today = todayKey()) {
  return doc.status === 'OVERDUE' || (doc.status === 'PENDING' && !!doc.dueDate && doc.dueDate.slice(0, 10) < today);
}
const effectiveStatus = doc => (isPastDue(doc) ? 'OVERDUE' : doc.status);
// Overdue by [today]: the unpaid parts past their date, or all still owed once the invoice is past due.
function invoiceOverdueEur(inv, today = todayKey()) {
  if (!isPastDue(inv, today)) return 0;
  const open = invoiceParts(inv).filter(p => !p.paidAt);
  if (!open.length || inv.status === 'OVERDUE') return invoiceOutstandingEur(inv);
  return sumParts(open.filter(p => p.dueDate.slice(0, 10) < today));
}
// What the next due date asks for: the parts due that day, or all still owed.
function invoiceNextDueEur(inv) {
  const open = invoiceParts(inv).filter(p => !p.paidAt);
  if (!open.length) return invoiceOutstandingEur(inv);
  return sumParts(open.filter(p => p.dueDate.slice(0, 10) === String(inv.dueDate || '').slice(0, 10)));
}
// Money received and when: each received part, numbered, or the total once paid in one go.
function invoiceReceipts(inv) {
  const parts = invoiceParts(inv);
  if (!parts.length) return inv.status === 'PAID' && inv.paidAt ? [{ at: inv.paidAt, totalEur: Number(inv.totalEur || 0) }] : [];
  return parts
    .map((p, i) => ({ at: p.paidAt || (inv.status === 'PAID' ? inv.paidAt : null), totalEur: Number(p.amountEur || 0), part: i + 1, of: parts.length }))
    .filter(r => r.at);
}
// The total, and under it how an invoice paid in installments stands.
function invoiceTotalCell(inv) {
  const parts = invoiceParts(inv);
  const open = inv.status === 'PENDING' || inv.status === 'OVERDUE';
  const note = !parts.length || !open ? ''
    : invoicePaidEur(inv) > 0 ? STR.invoiceLeftToPay({ amount: fmtEUR(invoiceOutstandingEur(inv)) })
    : STR.invoiceInParts({ n: parts.length });
  return note ? `<div class="col"><span>${fmtEUR(inv.totalEur)}</span><span class="sub">${escapeHTML(note)}</span></div>` : fmtEUR(inv.totalEur);
}
const quoteStatusLabel = code => (CRM.quoteStatus && CRM.quoteStatus[code]) || code;
const invoiceStatusLabel = code => (CRM.invoiceStatus && CRM.invoiceStatus[code]) || code;
const paymentStatusLabel = code => (CRM.paymentStatus && CRM.paymentStatus[code]) || invoiceStatusLabel(code);
const contactStatusLabel = code => STR[`contactStatus${code}`] || code;
const conversationStateLabel = code => STR[`conversationState${code}`] || code;
const channelName = platform => STR[`channel_${platform}`] || platform;
const QUOTE_STATUSES = ['PENDENTE', 'SENT', 'ACEITO'];
const INVOICE_STATUSES = ['PENDING', 'PAID', 'OVERDUE', 'CANCELLED'];
const hasModule = id => (state.me?.modules || []).includes(id);
// Bookings work in tenant-local calendar days ('YYYY-MM-DD'), never the browser's zone, so the grid
// and the times the server stores agree even when the operator sits in another timezone.
const todayKey = () => localDay(new Date().toISOString());
const addDayKey = (key, n) => {
  const [y, m, d] = key.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + n)).toISOString().slice(0, 10);
};
const dayKeyDate = key => { const [y, m, d] = key.split('-').map(Number); return new Date(Date.UTC(y, m - 1, d)); };
// A UTC window padded by a day each side: it always covers the tenant-local day(s), whatever the offset.
const dayKeyRange = (key, days) => ({
  from: dayKeyDate(addDayKey(key, -1)).toISOString(),
  to: dayKeyDate(addDayKey(key, days + 1)).toISOString(),
});
const fmtDayKey = (key, opts) => dayKeyDate(key).toLocaleDateString(uiLocale(), { ...opts, timeZone: 'UTC' });
const relativeDayLabel = key => {
  const diff = Math.round((dayKeyDate(key) - dayKeyDate(todayKey())) / 86400000);
  if (Math.abs(diff) > 1) return '';
  return capFirst(new Intl.RelativeTimeFormat(uiLocale(), { numeric: 'auto' }).format(diff, 'day'));
};
const toLocalInputValue = iso => {
  if (!iso) return '';
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: tenantTz(), year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).formatToParts(new Date(iso)).filter(p => p.type !== 'literal').map(p => [p.type, p.value]));
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
};
const bookingStatusLabel = s => STR[`bookingStatus${s}`] || s;
const BOOKING_STATUSES = ['PENDING', 'CONFIRMED', 'COMPLETED', 'NO_SHOW', 'CANCELLED'];
const bookingTone = s => ({ PENDING: 'warn', CONFIRMED: 'ok', COMPLETED: 'info', NO_SHOW: 'bad' }[s] || '');
const bookingPill = s => `<span class="pill ${bookingTone(s) ? `pill--${bookingTone(s)}` : ''}">${escapeHTML(bookingStatusLabel(s))}</span>`;
const bookingSourceLabel = s => STR[`bookingsSource${s}`] || s;
// The i18n proxy answers a missing key with its own path, so check for that before trusting it.
const bookingErrorText = err => {
  const key = `bookingsErr_${err?.code || ''}`;
  if (err?.code && STR[key] !== `app.${key}`) return STR[key];
  return err?.status === 409 ? STR.bookingsConflict : STR.bookingsSaveFailed;
};

function signOut() {
  localStorage.removeItem('dashboardToken');
  token = '';
  renderLogin();
}

function api(path, options = {}) {
  return apiFetch(path, options, token, signOut);
}

/** Downloads [path] with the session's token as [filename] (an export the browser can't fetch on its own). */
async function downloadFile(path, filename) {
  const blob = await apiBlob(path);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}

/** Like api(), for binary responses (customer media). */
async function apiBlob(path) {
  const res = await fetch(path, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
  if (res.status === 401) { signOut(); throw new Error('unauthorized'); }
  if (!res.ok) throw await apiError(res);
  return res.blob();
}

function quoteTone(status) { return { PENDENTE: 'warn', SENT: 'info', ACEITO: 'ok' }[status] || ''; }
function quotePill(status) {
  const tone = quoteTone(status);
  return `<span class="pill ${tone ? `pill--${tone}` : ''}">${escapeHTML(quoteStatusLabel(status))}</span>`;
}
function invoiceTone(status) { return { PENDING: 'warn', PAID: 'ok', OVERDUE: 'bad', CANCELLED: '' }[status] || ''; }
function invoicePill(status) {
  const tone = invoiceTone(status);
  return `<span class="pill ${tone ? `pill--${tone}` : ''}">${escapeHTML(invoiceStatusLabel(status))}</span>`;
}
function paymentPill(status) {
  const map = { PENDING: 'pill--warn', PAID: 'pill--ok', OVERDUE: 'pill--bad', CANCELLED: '' };
  return `<span class="pill ${map[status] || ''}">${escapeHTML(paymentStatusLabel(status))}</span>`;
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
