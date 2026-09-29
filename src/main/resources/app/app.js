const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
let token = localStorage.getItem('dashboardToken') || '';
let state = {
  me: null, overview: null, contacts: [], conversations: [], clients: [], quotes: [], invoices: [], catalog: [],
  persona: null, personaChat: [], assistantThreads: [], assistantThread: null, webWidget: null, widgetDraft: null, documentTemplate: null, companies: null,
  clientServices: [], filterServiceStatus: '', filterServiceClient: '', filterServicePeriod: '', filterServicePeriodKey: '',
  filterInvoicePeriod: '', filterInvoicePeriodKey: '',
  filterFinanceiroPeriod: '', filterFinanceiroPeriodKey: '', filterFinanceiroType: '',
  suppliers: [], employees: [], payments: [], filterPaymentStatus: '', filterPaymentSupplier: '',
  bookings: [], bookingUpcoming: [], bookingServices: [], bookingAvailability: [], bookingView: 'week', bookingWeekStart: '', bookingStatusFilter: '',
  instagram: { connected: false, commentsEnabled: false, needsReconnect: false, username: null, unrepliedCount: 0, comments: [], media: [] },
  instagramFilter: 'needs',
  search: '', active: 'overview', selectedAsset: '',
  // Directory ('clients', 'suppliers' or 'employees') whose archived records are listed instead of the active ones.
  archivedView: '', archivedRows: [],
  filterQuoteStatus: '', filterInvoiceStatus: '',
  selectedConversation: null, threadMessages: [],
  settingsSection: 'channels', personaAdvanced: false, overviewLayout: null, overviewExtended: false,
  whatsAppSignup: { enabled: false },
  fetched: { conversations: false, invoices: false, bookings: false, instagram: false, payments: false },
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
// AI chat bubbles render as plain pre-wrapped text (see .chat__msg in style.css), but the model
// writes markdown (**bold**). Escape first, then turn only **bold** into <strong> — everything
// else (numbered/bulleted lines, line breaks) already reads fine as plain text under pre-wrap.
const renderChatText = (s = '') => escapeHTML(s).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
const slugify = (s = '') => s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
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
function periodChips(active, attr, labels) {
  return ['', 'week', 'month'].map(period => `<button class="btn btn--sm ${active === period ? 'btn--primary' : ''}" type="button" ${attr}="${period}">${escapeHTML(labels[period])}</button>`).join('');
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
  return {
    count: live.length,
    paid: sumEur(live.filter(inv => inv.status === 'PAID')),
    pending: sumEur(live.filter(inv => inv.status === 'PENDING')),
    overdue: sumEur(live.filter(inv => inv.status === 'OVERDUE')),
    total: sumEur(live),
  };
}
const quoteStatusLabel = code => (CRM.quoteStatus && CRM.quoteStatus[code]) || code;
const invoiceStatusLabel = code => (CRM.invoiceStatus && CRM.invoiceStatus[code]) || code;
const paymentStatusLabel = code => (CRM.paymentStatus && CRM.paymentStatus[code]) || invoiceStatusLabel(code);
const contactStatusLabel = code => STR[`contactStatus${code}`] || code;
const conversationStateLabel = code => STR[`conversationState${code}`] || code;
const QUOTE_STATUSES = ['PENDENTE', 'SENT', 'ACEITO'];
const INVOICE_STATUSES = ['PENDING', 'PAID', 'OVERDUE', 'CANCELLED'];
const hasModule = id => (state.me?.modules || []).includes(id);
const isMinimalLayout = () => document.documentElement.dataset.layout === 'minimal';
const overviewPath = () => (isMinimalLayout() ? '/app/api/overview?extended=1' : '/app/api/overview');
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

async function api(path, options = {}) {
  const headers = { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) };
  const res = await fetch(path, { ...options, headers: { ...headers, ...(options.headers || {}) } });
  if (res.status === 401) { localStorage.removeItem('dashboardToken'); token = ''; renderLogin(); throw new Error('unauthorized'); }
  if (!res.ok) {
    const err = new Error(`HTTP ${res.status}`);
    err.status = res.status;
    err.code = await res.json().then(b => (b && typeof b.error === 'string' ? b.error : ''), () => '');
    throw err;
  }
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

const FIREBASE_SDK = 'https://www.gstatic.com/firebasejs/12.19.0';
let googleSignIn = null;
let googleSdk = null;
let authConfigLoad = null;

function loadAuthConfig() {
  authConfigLoad = authConfigLoad || fetch('/app/auth/config')
    .then(r => (r.ok ? r.json() : {}))
    .catch(() => { authConfigLoad = null; return {}; });
  return authConfigLoad;
}

// Browsers only allow the sign-in popup straight from a click, so the SDK loads as soon as a screen
// offering Google shows. The Firebase session only proves who you are: it stays in memory and the
// dashboard keeps its own token.
function prepareGoogleSignIn(config) {
  googleSignIn = googleSignIn || Promise.all([import(`${FIREBASE_SDK}/firebase-app.js`), import(`${FIREBASE_SDK}/firebase-auth.js`)])
    .then(([appSdk, authSdk]) => {
      const app = appSdk.getApps().length ? appSdk.getApp() : appSdk.initializeApp(config);
      const auth = authSdk.initializeAuth(app, { persistence: authSdk.inMemoryPersistence, popupRedirectResolver: authSdk.browserPopupRedirectResolver });
      const provider = new authSdk.GoogleAuthProvider();
      provider.setCustomParameters({ prompt: 'select_account' });
      googleSdk = { auth, provider, signInWithPopup: authSdk.signInWithPopup, signOut: authSdk.signOut };
      return googleSdk;
    });
  googleSignIn.catch(() => { googleSignIn = null; });
  return googleSignIn;
}

// Opens Google's account picker and returns an ID token for the chosen account.
async function googleIdToken() {
  const sdk = googleSdk || await googleSignIn;
  if (!sdk) throw Object.assign(new Error('google_unavailable'), { code: 'google_unavailable' });
  const result = await sdk.signInWithPopup(sdk.auth, sdk.provider);
  const idToken = await result.user.getIdToken();
  sdk.signOut(sdk.auth).catch(() => {});
  return idToken;
}

function googlePopupErrorText(err) {
  const code = String(err?.code || '');
  if (code === 'auth/popup-closed-by-user' || code === 'auth/cancelled-popup-request') return STR.loginGoogleCancelled;
  if (code === 'auth/popup-blocked') return STR.loginGooglePopupBlocked;
  return STR.loginGoogleFailed;
}

async function startDashboardSession(newToken) {
  token = newToken;
  localStorage.setItem('dashboardToken', token);
  await bootAuthed();
}

async function renderLogin() {
  $('#nav').innerHTML = '';
  $('#btn-account').hidden = true;
  renderCompanySwitch();
  if (!$('#drawer')?.hidden) closeDrawer({ dismissed: true });
  const google = (await loadAuthConfig()).google || null;
  if (google) prepareGoogleSignIn(google).catch(() => {});
  $('#view').innerHTML = `<div class="auth"><div class="auth__card"><div class="auth__mark">AI</div><p class="auth__eyebrow">${escapeHTML(STR.loginEyebrow)}</p><h1 class="auth__title">${escapeHTML(STR.loginTitle)}</h1><p class="auth__desc">${escapeHTML(STR.loginDesc)}</p>
    ${google ? `<button class="btn btn--primary" type="button" id="login-google">${escapeHTML(STR.loginGoogle)}</button>
      <p class="hint hint--warn" id="login-google-error" role="alert" hidden></p>
      <p class="auth__desc">${escapeHTML(STR.loginOr)}</p>` : ''}
    <form class="form" id="login-form"><div class="form__row"><label class="lbl" for="email">${escapeHTML(STR.loginEmail)}</label><input class="inp" id="email" type="email" autocomplete="email" required /></div><div class="form__row"><label class="lbl" for="password">${escapeHTML(STR.loginPassword)}</label><input class="inp" id="password" type="password" autocomplete="current-password" required /></div><button class="btn ${google ? 'btn--ghost' : 'btn--primary'}" type="submit">${escapeHTML(STR.loginSubmit)}</button></form></div></div>`;
  $('#login-google')?.addEventListener('click', async e => {
    const button = e.currentTarget;
    const note = $('#login-google-error');
    note.hidden = true;
    button.disabled = true;
    try {
      const idToken = await googleIdToken();
      const res = await fetch('/app/auth/google', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ idToken }) });
      const body = await res.json().catch(() => ({}));
      if (res.ok) return await startDashboardSession(body.token);
      const refusal = { no_account: STR.loginGoogleNoAccount, other_google_account: STR.loginGoogleOtherAccount, account_inactive: STR.loginInactive }[body.error];
      if (refusal) { note.textContent = refusal; note.hidden = false; } else toast(STR.loginGoogleFailed);
    } catch (err) {
      toast(googlePopupErrorText(err));
    } finally {
      button.disabled = false;
    }
  });
  $('#login-form').addEventListener('submit', async e => {
    e.preventDefault();
    try { const res = await api('/app/auth/login', { method: 'POST', body: JSON.stringify({ email: $('#email').value, password: $('#password').value }) }); await startDashboardSession(res.token); }
    catch { toast(STR.loginInvalid); }
  });
}

// ── Account: how the signed-in user gets in ─────────────────────────────────────
// Two sign-in methods, Google and password. The server confirms every change with the current
// password or a fresh Google sign-in, so an open session alone can't take the account over.
const accountTrail = () => ({ key: 'account', label: STR.accountTitle, open: () => openAccount() });

function accountInitials(email = '') {
  const parts = String(email).split('@')[0].split(/[._+-]+/).filter(Boolean);
  return ((parts[0]?.[0] || '') + (parts[1]?.[0] || '')).toUpperCase() || '?';
}

function accountErrorText(err) {
  const code = String(err?.code || '');
  if (code.startsWith('auth/') || code === 'google_unavailable') return googlePopupErrorText(err);
  const key = `accountErr_${code}`;
  if (code && STR[key] !== `app.${key}`) return STR[key];
  return ['invalid_token', 'stale_sign_in', 'email_not_verified', 'not_google_sign_in'].includes(code) ? STR.loginGoogleFailed : STR.accountFailed;
}

async function openAccount() {
  let account;
  try { account = await api('/app/api/account'); }
  catch (err) { if (err.message !== 'unauthorized') toast(STR.accountLoadFailed); return; }
  state.account = account;
  if (account.googleAvailable) loadAuthConfig().then(cfg => cfg.google && prepareGoogleSignIn(cfg.google)).catch(() => {});
  const linked = !!account.googleEmail;
  const pill = (on, onLabel, offLabel) => `<span class="pill ${on ? 'pill--ok' : ''}">${escapeHTML(on ? onLabel : offLabel)}</span>`;
  const rows = [
    {
      key: 'google', title: STR.accountGoogle, tone: linked ? 'ok' : 'neutral', pill: pill(linked, STR.accountLinked, STR.accountNotLinked),
      detail: linked ? account.googleEmail : account.googleAvailable ? STR.accountGoogleOffDetail : STR.accountGoogleUnavailable,
      disabled: !linked && !account.googleAvailable,
    },
    {
      key: 'password', title: STR.accountPassword, tone: account.passwordEnabled ? 'ok' : 'neutral', pill: pill(account.passwordEnabled, STR.accountOn, STR.accountOff),
      detail: account.passwordEnabled ? STR.accountPasswordOnDetail : STR.accountPasswordOffDetail,
    },
  ];
  const hint = !linked ? (account.googleAvailable ? STR.accountHintLink : '')
    : account.passwordEnabled ? STR.accountHintBoth : STR.accountHintGoogleOnly;
  const body = document.createElement('div');
  body.className = 'record';
  body.innerHTML = `<section class="record-card"><div class="record-card__head">
      <span class="record-card__avatar" aria-hidden="true">${escapeHTML(accountInitials(account.email))}</span>
      <div class="record-card__who">
        <div class="record-card__lines"><span class="record-card__line">${escapeHTML(account.email)}</span></div>
        <p class="record-card__since">${escapeHTML([STR[`accountRole${account.role}`], state.me?.tenant?.name].filter(Boolean).join(' · '))}</p>
      </div>
    </div></section>
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(STR.accountMethods)}</h2></header>
      <ul class="worklist">${rows.map(r => `<li><button class="worklist__item" type="button" data-account-open="${r.key}" data-tone="${r.tone}"${r.disabled ? ' disabled' : ''}>
        <span class="worklist__dot" aria-hidden="true"></span>
        <span class="worklist__main"><span class="worklist__title">${escapeHTML(r.title)}</span><span class="worklist__detail">${escapeHTML(r.detail)}</span></span>
        <span class="worklist__side"><span class="worklist__meta">${r.pill}</span></span>
      </button></li>`).join('')}</ul>
    </section>
    ${hint ? `<p class="hint">${escapeHTML(hint)}</p>` : ''}`;
  openDrawer(STR.accountTitle, body, false, { eyebrow: state.me?.tenant?.name });
  $$('[data-account-open]', body).forEach(b => b.addEventListener('click', () => {
    openFrom(accountTrail(), () => (b.dataset.accountOpen === 'google' ? openAccountGoogle() : openAccountPassword()));
  }));
}

function accountPasswordField(id, label, autocomplete, hint = '') {
  return `<div class="form__row form__row--full"><label class="lbl" for="${id}">${escapeHTML(label)}</label>
    <input class="inp" id="${id}" type="password" autocomplete="${autocomplete}" maxlength="128" required />${hint ? `<p class="hint">${escapeHTML(hint)}</p>` : ''}</div>`;
}

function accountActions(submitLabel, tone = 'btn--primary') {
  return `<div class="actions"><button class="btn ${tone}" type="submit">${escapeHTML(submitLabel)}</button><button class="btn btn--ghost" type="button" data-account-cancel>${escapeHTML(STR.cancel)}</button></div>`;
}

// Submits an account change. `run` must open the Google popup (if any) before its first await, while
// the click still counts. On success the account drawer comes back with the new state.
function wireAccountForm(form, run, done) {
  $('[data-account-cancel]', form)?.addEventListener('click', () => closeDrawer());
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      state.account = await run();
      toast(done);
      closeDrawer();
    } catch (err) {
      btn.disabled = false;
      if (err?.message !== 'unauthorized') toast(accountErrorText(err));
    }
  });
}

function openAccountGoogle() {
  const a = state.account;
  const form = document.createElement('form');
  form.className = 'form';
  const linkedMeta = `<div class="detail__meta"><div class="detail__meta-item"><span class="detail__meta-label">${escapeHTML(STR.accountGoogleEmail)}</span><span class="detail__meta-value">${escapeHTML(a.googleEmail || '')}</span></div></div>`;
  if (!a.googleEmail) {
    form.innerHTML = `<p class="hint">${escapeHTML(STR.accountGoogleLinkIntro)}</p>
      ${accountPasswordField('acc-current', STR.accountCurrentPassword, 'current-password')}
      ${accountActions(STR.loginGoogle)}`;
    wireAccountForm(form, async () => {
      const currentPassword = $('#acc-current', form).value;
      const idToken = await googleIdToken();
      return api('/app/api/account/google/link', { method: 'POST', body: JSON.stringify({ idToken, currentPassword }) });
    }, STR.accountGoogleLinked);
  } else if (a.passwordEnabled) {
    form.innerHTML = `${linkedMeta}<p class="hint">${escapeHTML(STR.accountGoogleUnlinkIntro)}</p>
      ${accountPasswordField('acc-current', STR.accountCurrentPassword, 'current-password')}
      ${accountActions(STR.accountGoogleUnlinkAction, 'btn--danger')}`;
    wireAccountForm(form, () => api('/app/api/account/google/unlink', {
      method: 'POST', body: JSON.stringify({ currentPassword: $('#acc-current', form).value }),
    }), STR.accountGoogleUnlinked);
  } else {
    form.innerHTML = `${linkedMeta}<p class="hint">${escapeHTML(STR.accountGoogleOnlyKeep)}</p>
      <div class="actions"><button class="btn" type="button" data-account-set-password>${escapeHTML(STR.accountSetPasswordAction)}</button></div>`;
    $('[data-account-set-password]', form).addEventListener('click', () => openAccountPassword());
  }
  openDrawer(STR.accountGoogleTitle, form);
}

function openAccountPassword() {
  const a = state.account;
  const form = document.createElement('form');
  form.className = 'form';
  const newFields = `${accountPasswordField('acc-new', STR.accountNewPassword, 'new-password', STR.accountNewPasswordHint)}
    ${accountPasswordField('acc-repeat', STR.accountRepeatPassword, 'new-password')}`;
  const readNewPassword = () => {
    const value = $('#acc-new', form).value;
    if (value !== $('#acc-repeat', form).value) throw Object.assign(new Error('mismatch'), { code: 'mismatch' });
    return value;
  };
  const body = document.createElement('div');
  body.className = 'settings-stack';
  body.appendChild(form);
  if (a.passwordEnabled) {
    form.innerHTML = `${accountPasswordField('acc-current', STR.accountCurrentPassword, 'current-password')}${newFields}
      ${accountActions(STR.accountSavePassword)}`;
    wireAccountForm(form, async () => {
      const newPassword = readNewPassword();
      return api('/app/api/account/password', { method: 'POST', body: JSON.stringify({ currentPassword: $('#acc-current', form).value, newPassword }) });
    }, STR.accountPasswordChanged);
    const only = document.createElement('section');
    only.className = 'panel';
    only.innerHTML = `<header class="panel__head"><h2 class="panel__title">${escapeHTML(STR.accountGoogleOnlyTitle)}</h2></header>
      <div class="panel__body settings-stack">
        <p class="hint">${escapeHTML(a.googleEmail ? STR.accountGoogleOnlyIntro({ email: a.googleEmail }) : STR.accountGoogleOnlyNeedsLink)}</p>
        <div class="actions">${a.googleEmail
          ? `<button class="btn" type="button" data-account-google-only>${escapeHTML(STR.accountGoogleOnlyAction)}</button>`
          : (a.googleAvailable ? `<button class="btn" type="button" data-account-link>${escapeHTML(STR.accountLinkGoogleAction)}</button>` : '')}</div>
      </div>`;
    body.appendChild(only);
    $('[data-account-link]', only)?.addEventListener('click', () => openAccountGoogle());
    $('[data-account-google-only]', only)?.addEventListener('click', async e => {
      const btn = e.currentTarget;
      btn.disabled = true;
      try {
        const idToken = await googleIdToken();
        state.account = await api('/app/api/account/password/disable', { method: 'POST', body: JSON.stringify({ idToken }) });
        toast(STR.accountPasswordOff);
        closeDrawer();
      } catch (err) {
        btn.disabled = false;
        if (err?.message !== 'unauthorized') toast(accountErrorText(err));
      }
    });
  } else {
    form.innerHTML = `<p class="hint">${escapeHTML(STR.accountSetIntro({ email: a.googleEmail || '' }))}</p>${newFields}
      ${accountActions(STR.accountSetAction)}`;
    wireAccountForm(form, async () => {
      const newPassword = readNewPassword();
      const idToken = await googleIdToken();
      return api('/app/api/account/password', { method: 'POST', body: JSON.stringify({ newPassword, idToken }) });
    }, STR.accountPasswordSet);
  }
  openDrawer(STR.accountPassword, body);
}

// ── Companies: a tenant can hold several, each with its own data ─────────────────
// Switching swaps the session token for one scoped to the other company and reloads, so nothing
// the previous company loaded survives in `state`.
const companiesEnabled = () => (state.me?.companyLimit || 1) > 1 || (state.me?.companies || []).length > 1;

function renderCompanySwitch() {
  const brand = $('#brand');
  const switchable = Boolean(token) && (state.me?.companies || []).length > 1;
  brand.disabled = !switchable;
  brand.title = switchable ? STR.companySwitchAria : '';
}

function companyErrorText(err, fallback) {
  const key = `companyErr_${err?.code || ''}`;
  return err?.code && STR[key] !== `app.${key}` ? STR[key] : fallback;
}

function companyRow(c, current, openable) {
  const suspended = c.status === 'SUSPENDED';
  const tone = c.id === current ? 'ok' : suspended ? 'warn' : 'neutral';
  const side = c.id === current ? `<span class="pill pill--ok">${escapeHTML(STR.companyCurrent)}</span>`
    : suspended ? `<span class="pill pill--warn">${escapeHTML(STR.companyStatusSUSPENDED)}</span>`
    : escapeHTML(STR.companyOpen);
  const detail = [c.slug, c.primary ? STR.companyFirst : ''].filter(Boolean).join(' · ');
  return `<li><button class="worklist__item" type="button" data-company="${escapeHTML(c.id)}" data-tone="${tone}"${c.id === current ? ' aria-current="true"' : ''}${c.id === current || !openable ? ' disabled' : ''}>
    <span class="worklist__dot" aria-hidden="true"></span>
    <span class="worklist__main"><span class="worklist__title">${escapeHTML(c.name)}</span><span class="worklist__detail">${escapeHTML(detail)}</span></span>
    <span class="worklist__side"><span class="worklist__meta">${side}</span></span>
  </button></li>`;
}

async function switchCompany(button) {
  button.disabled = true;
  try {
    const res = await api(`/app/api/companies/${encodeURIComponent(button.dataset.company)}/switch`, { method: 'POST' });
    localStorage.setItem('dashboardToken', res.token);
    location.reload();
  } catch (err) {
    button.disabled = false;
    if (err.message !== 'unauthorized') toast(companyErrorText(err, STR.companySwitchFailed));
  }
}

function openCompanySwitcher() {
  const companies = state.me?.companies || [];
  if (companies.length < 2) return;
  const body = document.createElement('div');
  body.className = 'record';
  body.innerHTML = `<section class="panel"><ul class="worklist">${companies.map(c => companyRow(c, state.me.tenant.id, true)).join('')}</ul></section>
    ${hasModule('settings') ? `<div class="actions"><button class="btn btn--ghost" type="button" data-company-manage>${escapeHTML(STR.companiesManage)}</button></div>` : ''}`;
  openDrawer(STR.companiesSwitchTitle, body, false, { eyebrow: state.me.tenant.name });
  $$('[data-company]', body).forEach(b => b.addEventListener('click', () => switchCompany(b)));
  $('[data-company-manage]', body)?.addEventListener('click', () => {
    closeDrawer({ dismissed: true });
    state.settingsSection = 'companies';
    setActive('settings');
  });
}

function companiesPanel() {
  const data = state.companies;
  if (!data) return `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.companiesLoadFailed)}</p></div></div>`;
  const openable = new Set((state.me?.companies || []).map(c => c.id));
  const used = data.companies.length;
  const add = !data.canManage ? `<p class="hint">${escapeHTML(STR.companiesAdminOnly)}</p>`
    : used >= data.limit ? `<p class="hint">${escapeHTML(STR.companiesLimitReached({ limit: data.limit }))}</p>`
    : `<form class="form" id="company-form">
        <div class="form__row"><label class="lbl" for="company-name">${escapeHTML(STR.companyNameLabel)}</label><input class="inp" id="company-name" maxlength="80" autocomplete="organization" required /></div>
        <p class="hint">${escapeHTML(STR.companyAddHint)}</p>
        <div class="actions"><button class="btn btn--primary" type="submit">${escapeHTML(STR.companyCreate)}</button></div>
      </form>`;
  return `<div class="record">
    <section class="panel">
      <header class="panel__head"><h2 class="panel__title">${escapeHTML(STR.companiesTitle)} <span class="tag">${escapeHTML(STR.companiesUsage({ used, limit: data.limit }))}</span></h2></header>
      <div class="panel__body"><p class="hint">${escapeHTML(STR.companiesDesc)}</p></div>
      <ul class="worklist">${data.companies.map(c => companyRow(c, data.current, openable.has(c.id))).join('')}</ul>
    </section>
    <section class="panel">
      <header class="panel__head"><h2 class="panel__title">${escapeHTML(STR.companyAddTitle)}</h2></header>
      <div class="panel__body">${add}</div>
    </section>
  </div>`;
}

function wireCompaniesPanel(root) {
  $$('[data-company]', root).forEach(b => b.addEventListener('click', () => switchCompany(b)));
  const form = $('#company-form', root);
  form?.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const company = await api('/app/api/companies', { method: 'POST', body: JSON.stringify({ name: $('#company-name', form).value }) });
      [state.me, state.companies] = await Promise.all([api('/app/api/me'), api('/app/api/companies')]);
      toast(STR.companyCreated({ name: company.name }));
      render();
    } catch (err) {
      btn.disabled = false;
      if (err.message !== 'unauthorized') toast(companyErrorText(err, STR.companyCreateFailed));
    }
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
  // An operator opening the dashboard has no user account here.
  const accountBtn = $('#btn-account');
  accountBtn.hidden = !(state.me.user && state.me.principalType === 'tenant');
  accountBtn.textContent = accountInitials(state.me.user?.email);
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
    ? pendingUpcomingBookings().length
    : (o.calendar?.pending || 0);
  const pendingIg = state.fetched.instagram
    ? ((state.instagram?.unrepliedCount) || (state.instagram?.comments || []).filter(c => c.needsReply).length)
    : (o.social?.unreplied || 0);
  const overduePay = state.fetched.payments
    ? state.payments.filter(p => p.status === 'OVERDUE').length
    : (o.payments?.overdueCount || 0);
  const counts = {
    contacts: state.contacts.length || o.inbox?.contacts || 0,
    conversations: waiting || state.conversations.length || o.inbox?.conversations || 0,
    clients: state.clients.length || o.customers?.total || 0,
    quotes: state.quotes.length || o.pipeline?.quoteCount || 0,
    invoices: overdue || state.invoices.length || o.cash?.invoiceCount || 0,
    catalog: state.catalog.length || o.catalog?.items || 0,
    services: (state.clientServices.filter(s => s.status === 'OPEN').length) || o.services?.openCount || 0,
    suppliers: state.suppliers.length || o.suppliers?.total || 0,
    employees: state.employees.length || o.employees?.total || 0,
    payments: overduePay || state.payments.length || o.payments?.paymentCount || 0,
    bookings: pendingBookings || (state.fetched.bookings ? weekBookings().filter(b => b.status !== 'CANCELLED').length : o.calendar?.thisWeek) || 0,
    instagram: pendingIg || (state.instagram?.media || []).length,
  };
  const alerts = { conversations: waiting > 0, invoices: overdue > 0, payments: overduePay > 0, bookings: pendingBookings > 0, instagram: pendingIg > 0 };
  const groups = [
    { id: 'home', items: ['overview'] },
    { id: 'groupInbox', items: ['conversations', 'contacts', 'instagram'] },
    { id: 'groupBusiness', items: ['clients', 'services', 'quotes', 'invoices', 'financeiro', 'suppliers', 'employees', 'payments', 'catalog', 'bookings'] },
    { id: 'groupBot', items: ['persona', 'ai-assistant'] },
    { id: 'groupSetup', items: ['settings'] },
  ];
  const enabled = new Set(state.me.modules);
  // Financeiro isn't its own toggleable module — it's a combined week/month lens over
  // invoices + payments, so it shows whenever either of those does.
  if (enabled.has('invoices') || enabled.has('payments')) enabled.add('financeiro');
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
  state.filterServiceClient = '';
  state.filterServicePeriod = '';
  state.filterServicePeriodKey = '';
  state.filterInvoicePeriod = '';
  state.filterInvoicePeriodKey = '';
  state.filterFinanceiroPeriod = '';
  state.filterFinanceiroPeriodKey = '';
  state.filterFinanceiroType = '';
  state.filterPaymentStatus = '';
  state.filterPaymentSupplier = '';
  state.archivedView = '';
  try {
    await loadModule(tab);
    render();
  } catch {
    toast(STR.loadFailed);
  }
}

async function loadModule(tab) {
  if (tab === 'overview') {
    const extended = isMinimalLayout();
    state.overview = await api(overviewPath());
    state.overviewExtended = extended;
  }
  if (tab === 'contacts') state.contacts = await api('/app/api/contacts');
  if (tab === 'conversations') {
    state.conversations = await api('/app/api/conversations');
    state.fetched.conversations = true;
  }
  if (tab === 'clients') state.clients = await api('/app/api/crm/clients');
  if (tab === 'suppliers') state.suppliers = await api('/app/api/crm/suppliers');
  if (tab === 'employees') state.employees = await api('/app/api/crm/employees');
  if (tab === 'payments') {
    const [payments, suppliers, employees] = await Promise.all([
      api('/app/api/crm/payments'),
      api('/app/api/crm/suppliers').catch(() => state.suppliers),
      hasModule('employees') ? api('/app/api/crm/employees').catch(() => state.employees) : Promise.resolve(state.employees),
    ]);
    state.payments = payments;
    state.suppliers = suppliers;
    if (hasModule('employees')) state.employees = employees;
    state.fetched.payments = true;
    if (hasModule('catalog')) state.catalog = await api('/app/api/crm/standard-items').catch(() => state.catalog);
    if (!state.filterPaymentStatus && state.payments.some(p => p.status === 'OVERDUE')) state.filterPaymentStatus = 'OVERDUE';
  }
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
  if (tab === 'financeiro') {
    const [invoices, payments] = await Promise.all([
      hasModule('invoices') ? api('/app/api/crm/invoices') : Promise.resolve(state.invoices),
      hasModule('payments') ? api('/app/api/crm/payments') : Promise.resolve(state.payments),
    ]);
    state.invoices = invoices;
    state.payments = payments;
    if (hasModule('invoices')) state.fetched.invoices = true;
    if (hasModule('payments')) state.fetched.payments = true;
  }
  if (tab === 'catalog') state.catalog = await api('/app/api/crm/standard-items');
  if (tab === 'persona') state.persona = await api('/app/api/persona');
  if (tab === 'ai-assistant') {
    if (hasModule('bookings') && !state.bookingServices.length) state.bookingServices = await api('/app/api/bookings/services').catch(() => []);
    state.assistantThreads = await api('/app/api/assistant/threads');
    if (state.assistantThread && !state.assistantThreads.some(t => t.id === state.assistantThread.thread.id)) state.assistantThread = null;
    if (!state.assistantThread && state.assistantThreads.length) state.assistantThread = await api(`/app/api/assistant/threads/${state.assistantThreads[0].id}`);
  }
  if (tab === 'settings') {
    state.webWidget = await api('/app/api/web-widget').catch(() => ({ publicKey: null, allowedOrigins: [] }));
    state.whatsAppSignup = await api('/app/api/whatsapp/embedded-signup/config').catch(() => ({ enabled: false }));
    state.documentTemplate = await api('/app/api/settings/document-template').catch(() => null);
    state.overviewLayout = await api('/app/api/settings/overview').catch(() => ({ hidden: [], available: [] }));
    state.companies = companiesEnabled() ? await api('/app/api/companies').catch(() => null) : null;
  }
  if (tab === 'bookings') {
    if (!state.bookingWeekStart) state.bookingWeekStart = periodKey(todayKey(), 'week');
    const week = dayKeyRange(state.bookingWeekStart, 7);
    const upcoming = dayKeyRange(todayKey(), BOOKING_AGENDA_DAYS);
    const [bookings, agenda, services, availability] = await Promise.all([
      api(`/app/api/bookings?from=${encodeURIComponent(week.from)}&to=${encodeURIComponent(week.to)}`),
      api(`/app/api/bookings?from=${encodeURIComponent(upcoming.from)}&to=${encodeURIComponent(upcoming.to)}`),
      api('/app/api/bookings/services'),
      api('/app/api/bookings/availability'),
    ]);
    state.bookings = bookings;
    state.bookingUpcoming = agenda;
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
  renderCompanySwitch();
  $('#crumb-leaf').textContent = labels[state.active] || state.active;
  $('#meta-clock').textContent = new Date().toLocaleString(uiLocale(), { hour: '2-digit', minute: '2-digit' });
  updateSidebarKpis();
  $('#btn-new').hidden = !['clients', 'services', 'quotes', 'invoices', 'suppliers', 'employees', 'payments', 'catalog', 'bookings'].includes(state.active);
  const newButtonLabels = {
    clients: STR.clientFormTitle, services: CRM.services.formTitle, quotes: STR.quoteFormTitle,
    invoices: STR.invoiceFormTitle, suppliers: CRM.suppliers.formTitle, employees: CRM.employees.formTitle, payments: CRM.payments.formTitle, catalog: STR.catalogFormTitle, bookings: STR.bookingsNew,
  };
  $('#btn-new').textContent = newButtonLabels[state.active] || `${STR.newPrefix} ${labels[state.active] || ''}`;
  const root = $('#view');
  if (state.active === 'overview') return renderOverview(root);
  if (state.active === 'contacts') return renderContacts(root);
  if (state.active === 'conversations') return renderConversations(root);
  if (state.active === 'clients') return renderClients(root);
  if (state.active === 'suppliers') return renderSuppliers(root);
  if (state.active === 'employees') return renderEmployees(root);
  if (state.active === 'services') return renderServices(root);
  if (state.active === 'quotes') return renderQuotes(root);
  if (state.active === 'invoices') return renderInvoices(root);
  if (state.active === 'financeiro') return renderFinanceiro(root);
  if (state.active === 'payments') return renderPayments(root);
  if (state.active === 'catalog') return renderCatalog(root);
  if (state.active === 'persona') return renderPersona(root);
  if (state.active === 'ai-assistant') return renderAssistant(root);
  if (state.active === 'bookings') return renderBookings(root);
  if (state.active === 'instagram') return renderInstagram(root);
  renderSettings(root);
}

function hero(title, desc, stats = '', nav = '') {
  const right = nav ? `<div class="view__hero-right">${nav}${stats}</div>` : stats;
  return `<div class="view__hero"><div><h1 class="view__title">${escapeHTML(title)}</h1><p class="view__desc">${escapeHTML(desc)}</p></div>${right}</div>`;
}
function periodNav(selectedKey, grain, attr, t) {
  if (!grain) return '';
  const atCurrent = selectedKey === currentPeriodKey(grain);
  return `<div class="period-nav">
    <button class="btn btn--sm" type="button" aria-label="${escapeHTML(t.periodPrev)}" ${attr}="prev">‹</button>
    <span class="period-nav__label mono">${escapeHTML(periodLabel(selectedKey, grain))}</span>
    <button class="btn btn--sm" type="button" aria-label="${escapeHTML(t.periodNext)}" ${atCurrent ? 'disabled' : ''} ${attr}="next">›</button>
    ${atCurrent ? '' : `<button class="btn btn--sm" type="button" ${attr}="current">${escapeHTML(t.periodCurrent)}</button>`}
  </div>`;
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
function crmPanel({ title, tag, views = '', tools = '', head, rows, empty, emptyDesc }) {
  const cols = (String(head).match(/<th/g) || []).length || 8;
  const emptyCell = `<tr><td colspan="${cols}"><div class="empty"><p class="empty__title">${escapeHTML(empty)}</p>${emptyDesc ? `<p class="empty__desc">${escapeHTML(emptyDesc)}</p>` : ''}</div></td></tr>`;
  // `views` switches the panel's whole layout (e.g. List / By week / By month). Keep it out of
  // `.panel__tools` so it reads as a mode switch, not a same-weight filter: it stays in the head,
  // and any `tools` (status/client filters) drop to their own `.panel__filters` row underneath.
  const headTools = views ? `<div class="panel__views">${views}</div>` : (tools ? `<div class="panel__tools">${tools}</div>` : '');
  const filtersRow = views && tools ? `<div class="panel__filters">${tools}</div>` : '';
  return `<div class="panel">
    <div class="panel__head">
      <h2 class="panel__title">${escapeHTML(title)}${tag != null ? ` <span class="tag">${escapeHTML(String(tag))}</span>` : ''}</h2>
      ${headTools}
    </div>
    ${filtersRow}
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
  if (item.aggregate && item.kind === 'overdue_invoice' && typeof STR.overdueInvoiceN === 'function') return STR.overdueInvoiceN({ n: item.count });
  if (item.aggregate && item.kind === 'overdue_payment' && typeof STR.overduePaymentN === 'function') return STR.overduePaymentN({ n: item.count });
  if (item.kind === 'assistant_action' && typeof STR.assistantAction === 'function') return STR.assistantAction({ n: Number(item.detail || 0) });
  const map = {
    waiting_chat: STR.waitingChat,
    overdue_invoice: STR.overdueInvoice,
    due_soon_invoice: STR.dueSoonInvoice,
    overdue_payment: STR.overduePayment,
    due_soon_payment: STR.dueSoonPayment,
    pending_booking: STR.pendingBooking,
    instagram_comment: STR.instagramWaitingComment,
    quote_expiring: STR.quoteExpiring,
  };
  return map[item.kind] || item.kind;
}
function attentionPill(kind) {
  const map = {
    waiting_chat: 'pill--warn', overdue_invoice: 'pill--bad', due_soon_invoice: 'pill--warn',
    overdue_payment: 'pill--bad', due_soon_payment: 'pill--warn',
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
    overdue_payment: '💸', due_soon_payment: '⏰',
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
// Overdue invoices/payments are per-document and uncapped at the source — with a dozen
// overdue invoices this list would be a dozen near-identical rows. Collapse each kind into
// one row (count + total) instead; every other kind is already capped small (3-5) upstream
// and stays specific because each one is its own distinct thing to act on.
function aggregateAttention(attention, o) {
  const rows = [];
  if ((o.cash?.overdueCount || 0) > 0) {
    rows.push({ kind: 'overdue_invoice', tab: 'invoices', aggregate: true, count: o.cash.overdueCount, amountCents: o.cash.overdueCents });
  }
  if ((o.payments?.overdueCount || 0) > 0) {
    rows.push({ kind: 'overdue_payment', tab: 'payments', aggregate: true, count: o.payments.overdueCount, amountCents: o.payments.overdueCents });
  }
  return [...rows, ...attention.filter(n => n.kind !== 'overdue_invoice' && n.kind !== 'overdue_payment')];
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
    financeiro: STR.snapFinance, pipeline: STR.snapPipeline, customers: STR.snapCustomers, inbox: STR.snapInbox,
    calendar: STR.homeCard_calendar, social: STR.snapSocial, catalog: STR.snapCatalog, services: STR.snapServices,
    suppliers: STR.snapSuppliers, employees: STR.snapEmployees, assistant: STR.snapAssistant,
  };
  const descs = {
    highlights: STR.homeCard_highlightsDesc, pulse: STR.homeCard_pulseDesc, attention: STR.homeCard_attentionDesc, setup: STR.homeCard_setupDesc,
    financeiro: STR.homeCard_financeDesc, pipeline: STR.homeCard_pipelineDesc, customers: STR.homeCard_customersDesc, inbox: STR.homeCard_inboxDesc,
    calendar: STR.homeCard_calendarDesc, social: STR.homeCard_socialDesc, catalog: STR.homeCard_catalogDesc, services: STR.homeCard_servicesDesc,
    suppliers: STR.homeCard_suppliersDesc, employees: STR.homeCard_employeesDesc, assistant: STR.homeCard_assistantDesc,
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
  if (isMinimalLayout()) return renderOverviewMinimal(root);
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
  const attention = hidden.has('attention') ? [] : aggregateAttention(o.attention || [], o);
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
  if ((o.cash || o.payments) && !hidden.has('financeiro')) {
    const gained = o.cash?.collectedThisMonthCents || 0;
    const spent = o.payments?.paidThisMonthCents || 0;
    const financeRows = [];
    if (o.cash) {
      financeRows.push({ label: STR.financeGained, value: centsEUR(o.cash.collectedThisMonthCents) });
      financeRows.push({ label: STR.financeReceivable, value: centsEUR(o.cash.outstandingCents) });
    }
    if (o.payments) {
      financeRows.push({ label: STR.financeSpent, value: centsEUR(o.payments.paidThisMonthCents) });
      financeRows.push({ label: STR.financePayable, value: centsEUR(o.payments.outstandingCents) });
    }
    const financeMeter = (gained || spent) ? { pct: (gained / (gained + spent)) * 100 } : null;
    snapshots.push(snapshotPanel({
      tab: 'financeiro', kind: 'financeiro', icon: '💶', title: STR.snapFinance,
      figure: centsEUR(gained - spent), rows: financeRows, meter: financeMeter,
    }));
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
  if (o.suppliers && !hidden.has('suppliers')) {
    snapshots.push(snapshotPanel({ tab: 'suppliers', kind: 'suppliers', icon: '🏭', title: STR.snapSuppliers, figure: o.suppliers.total, rows: [
      { label: STR.snapSuppliers, value: o.suppliers.total },
      { label: STR.suppliersNewMonth, value: o.suppliers.newThisMonth },
      { label: STR.suppliersNewLastMonth, value: o.suppliers.newLastMonth },
    ] }));
  }
  if (o.employees && !hidden.has('employees')) {
    snapshots.push(snapshotPanel({ tab: 'employees', kind: 'employees', icon: '👷', title: STR.snapEmployees, figure: o.employees.total, rows: [
      { label: STR.snapEmployees, value: o.employees.total },
      { label: STR.employeesNewMonth, value: o.employees.newThisMonth },
      { label: STR.employeesNewLastMonth, value: o.employees.newLastMonth },
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
  const snapshotsOff = ['financeiro', 'pipeline', 'customers', 'services', 'suppliers', 'employees', 'inbox', 'calendar', 'social', 'catalog', 'assistant'].some(id => hidden.has(id));
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

// Minimal layout Home: a denser CRM cockpit fed by /app/api/overview?extended=1. It honours the
// same hidden-card ids as the classic Home, so Settings → Home works for both layouts.
const tApp = (key, params) => I18N.t(`app.${key}`, params);
const capFirst = s => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);
const wholeCentsEUR = c => fmtEURWhole(Number(c || 0) / 100);
const RECENT_TONES = { invoice_paid: 'ok', invoice_issued: 'info', payment_paid: 'neutral', quote_accepted: 'ok', quote_created: 'accent', client_created: 'accent', booking_created: 'info' };
function pctChange(current, previous) {
  if (!previous) return null;
  return Math.round(((current - previous) / Math.abs(previous)) * 100);
}
function niceCeil(v) {
  if (!(v > 0)) return 0;
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  return ([1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10].find(s => v / p <= s) || 10) * p;
}
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
function dashGreeting() {
  const hour = Number(new Intl.DateTimeFormat('en-GB', { hour: 'numeric', hourCycle: 'h23', timeZone: tenantTz() }).format(new Date()));
  if (hour >= 5 && hour < 12) return STR.greetingMorning;
  if (hour >= 12 && hour < 19) return STR.greetingAfternoon;
  return STR.greetingEvening;
}
function monthLabel(key, long = false) {
  const [y, m] = String(key).split('-').map(Number);
  const opts = long ? { month: 'long', year: 'numeric', timeZone: 'UTC' } : { month: 'short', timeZone: 'UTC' };
  return new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString(uiLocale(), opts);
}
function dayLabel(key) {
  const [y, m, d] = String(key).split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString(uiLocale(), { day: 'numeric', month: 'short', timeZone: 'UTC' });
}
function sparkline(values, cls = '') {
  if (!values || values.length < 2) return '';
  const w = 120, h = 32;
  const max = Math.max(...values, 0);
  const x = i => (i / (values.length - 1)) * w;
  const y = v => (max > 0 ? h - 2 - (v / max) * (h - 5) : h - 2);
  const line = values.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)} ${y(v).toFixed(1)}`).join(' ');
  return `<svg class="spark${cls ? ` ${cls}` : ''}" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" aria-hidden="true"><path class="spark__area" d="${line} L${w} ${h} L0 ${h} Z"/><path class="spark__line" d="${line}"/></svg>`;
}
// Unlike the classic aggregateAttention, a single overdue document keeps its own row so it can
// deep-link to that invoice/payment; only two or more collapse into one count row.
function dashAttention(o) {
  const list = o.attention || [];
  const rows = [];
  [['overdue_invoice', 'invoices', o.cash], ['overdue_payment', 'payments', o.payments]].forEach(([kind, tab, money]) => {
    const count = money?.overdueCount || 0;
    const own = list.filter(n => n.kind === kind);
    if (count > 1 || (count === 1 && !own.length)) rows.push({ kind, tab, aggregate: true, count, amountCents: money.overdueCents });
    else rows.push(...own);
  });
  return [...rows, ...list.filter(n => n.kind !== 'overdue_invoice' && n.kind !== 'overdue_payment')];
}
function attentionTone(kind) {
  return { 'pill--bad': 'bad', 'pill--warn': 'warn', 'pill--info': 'info', 'pill--accent': 'accent' }[attentionPill(kind)] || 'neutral';
}
function dashFacts(rows, inline = false) {
  const items = rows.filter(Boolean);
  if (!items.length) return '';
  return `<dl class="dash-facts${inline ? ' dash-facts--row' : ''}">${items.map(([label, value]) => `<div><dt>${escapeHTML(label)}</dt><dd>${escapeHTML(String(value))}</dd></div>`).join('')}</dl>`;
}
function worklistRow({ tone = 'neutral', title, detail = '', meta = '', when = '', amount = false, go, open = '', conversation = '' }) {
  const attrs = `data-go="${escapeHTML(go)}"${open ? ` data-open="${escapeHTML(open)}"` : ''}${conversation ? ` data-conversation="${escapeHTML(conversation)}"` : ''}`;
  const side = meta || when
    ? `<span class="worklist__side">${meta ? `<span class="worklist__meta${amount ? ' worklist__meta--amount' : ''}">${escapeHTML(meta)}</span>` : ''}${when ? `<span class="worklist__when">${escapeHTML(when)}</span>` : ''}</span>`
    : '';
  return `<li><button type="button" class="worklist__item" ${attrs}>
    <span class="worklist__dot" data-tone="${tone}" aria-hidden="true"></span>
    <span class="worklist__main"><span class="worklist__title">${escapeHTML(title)}</span>${detail ? `<span class="worklist__detail">${escapeHTML(detail)}</span>` : ''}</span>
    ${side}
  </button></li>`;
}
function dashCard({ order, title, tag = null, meta = '', go = '', body, flush = false }) {
  const open = go ? `<button type="button" class="btn btn--sm btn--ghost" data-go="${escapeHTML(go)}" aria-label="${escapeHTML(tApp('openModuleAria', { name: title }))}">${escapeHTML(STR.openModule)} <span aria-hidden="true">→</span></button>` : '';
  const tools = (meta ? `<span class="panel__meta">${escapeHTML(meta)}</span>` : '') + open;
  return `<section class="panel panel--card" data-order="${order}">
    <header class="panel__head"><h2 class="panel__title">${escapeHTML(title)}${tag != null ? ` <span class="tag">${escapeHTML(String(tag))}</span>` : ''}</h2>${tools ? `<div class="panel__tools">${tools}</div>` : ''}</header>
    <div class="panel__body${flush ? ' panel__body--flush' : ''}">${body}</div>
  </section>`;
}

function dashKpis(o, hidden) {
  const cells = [];
  const money = !hidden.has('financeiro');
  const plusThisMonth = n => (n ? tApp('kpiNewThisMonth', { n }) : '');
  if (o.cash && money) {
    const pct = pctChange(o.cash.collectedThisMonthCents || 0, o.cash.collectedLastMonthCents || 0);
    cells.push({
      go: 'financeiro', label: STR.hl_collected_month, value: wholeCentsEUR(o.cash.collectedThisMonthCents),
      meta: deltaHint(pct, STR.vsLastMonth), trend: pct > 0 ? 'up' : pct < 0 ? 'down' : '',
      spark: (o.cashFlow || []).map(m => m.inCents || 0),
    });
    const overdue = (o.cash.overdueCents || 0) > 0;
    cells.push({
      go: 'invoices', label: STR.hl_outstanding, value: wholeCentsEUR(o.cash.outstandingCents),
      meta: overdue ? tApp('kpiOverdue', { amount: wholeCentsEUR(o.cash.overdueCents) }) : (o.cash.dueSoonCount ? tApp('kpiDueSoon', { n: o.cash.dueSoonCount }) : ''),
      metaTone: overdue ? 'bad' : '',
    });
  }
  if (o.payments && money) {
    if (o.cash) {
      const received = o.cash.collectedThisMonthCents || 0;
      const spent = o.payments.paidThisMonthCents || 0;
      cells.push({ go: 'financeiro', label: STR.kpiNet, value: wholeCentsEUR(received - spent), meta: tApp('kpiInOut', { in: wholeCentsEUR(received), out: wholeCentsEUR(spent) }) });
    } else {
      const overdue = (o.payments.overdueCents || 0) > 0;
      cells.push({ go: 'payments', label: STR.financePayable, value: wholeCentsEUR(o.payments.outstandingCents), meta: overdue ? tApp('kpiOverdue', { amount: wholeCentsEUR(o.payments.overdueCents) }) : '', metaTone: overdue ? 'bad' : '' });
    }
  }
  if (o.pipeline && !hidden.has('pipeline')) {
    cells.push({ go: 'quotes', label: STR.hl_pipeline_open, value: wholeCentsEUR(o.pipeline.openCents), meta: tApp('kpiQuotes', { n: (o.pipeline.pendingCount || 0) + (o.pipeline.sentCount || 0), pct: o.pipeline.winRatePct || 0 }) });
  }
  if (o.inbox && !hidden.has('inbox')) {
    if (hasModule('conversations')) {
      cells.push({
        go: 'conversations', label: STR.hl_waiting, value: o.inbox.waiting || 0, valueTone: o.inbox.waiting > 0 ? 'warn' : '',
        meta: tApp('kpiMessagesToday', { n: o.inbox.messagesToday || 0 }), spark: (o.activity || []).map(d => d.count || 0),
      });
    } else if (hasModule('contacts')) {
      cells.push({ go: 'contacts', label: STR.hl_contacts, value: o.inbox.contacts || 0, meta: o.inbox.newContactsThisWeek ? tApp('kpiNewThisWeek', { n: o.inbox.newContactsThisWeek }) : '' });
    }
  }
  if (o.calendar && !hidden.has('calendar')) cells.push({ go: 'bookings', label: STR.hl_bookings_today, value: o.calendar.today || 0, meta: tApp('kpiThisWeek', { n: o.calendar.thisWeek || 0 }) });
  if (o.customers && !hidden.has('customers')) cells.push({ go: 'clients', label: STR.hl_clients, value: o.customers.total || 0, meta: plusThisMonth(o.customers.newThisMonth) });
  return cells.slice(0, 5);
}

function dashCashFlowCard(o) {
  const flow = o.cashFlow || [];
  const showIn = !!o.cash;
  const showOut = !!o.payments;
  let body = '';
  if (flow.length) {
    const peak = Math.max(0, ...flow.map(m => Math.max(showIn ? m.inCents || 0 : 0, showOut ? m.outCents || 0 : 0))) / 100;
    const max = niceCeil(peak);
    const height = eur => (max > 0 ? Math.min(100, (eur / max) * 100) : 0).toFixed(1);
    const legend = `<ul class="legend">${showIn ? `<li class="legend__item"><span class="legend__swatch" data-tone="accent"></span>${escapeHTML(STR.dashMoneyIn)}</li>` : ''}${showOut ? `<li class="legend__item"><span class="legend__swatch" data-tone="muted"></span>${escapeHTML(STR.dashMoneyOut)}</li>` : ''}</ul>`;
    const describe = m => [monthLabel(m.month, true), showIn ? `${STR.dashMoneyIn} ${fmtEUR((m.inCents || 0) / 100)}` : '', showOut ? `${STR.dashMoneyOut} ${fmtEUR((m.outCents || 0) / 100)}` : ''].filter(Boolean).join(' · ');
    const cols = flow.map(m => `<div class="bars__pair" title="${escapeHTML(describe(m))}">${showIn ? `<span class="bars__bar" data-tone="accent" style="height:${height((m.inCents || 0) / 100)}%"></span>` : ''}${showOut ? `<span class="bars__bar" data-tone="muted" style="height:${height((m.outCents || 0) / 100)}%"></span>` : ''}</div>`).join('');
    const labels = flow.map((m, i) => `<span class="bars__label${i === flow.length - 1 ? ' is-current' : ''}">${escapeHTML(monthLabel(m.month))}</span>`).join('');
    const quiet = flow.every(m => !(m.inCents || m.outCents)) ? `<p class="dash-empty">${escapeHTML(STR.dashCashEmpty)}</p>` : '';
    body = `${legend}${quiet}<div class="bars" role="img" aria-label="${escapeHTML(flow.map(describe).join('; '))}"><div class="bars__scale"><span>${escapeHTML(max ? fmtEURCompact(max) : '')}</span></div><div class="bars__plot">${cols}</div><div class="bars__labels">${labels}</div></div>`;
  }
  const months = flow.length || 1;
  const avg = key => flow.reduce((sum, m) => sum + (m[key] || 0), 0) / months;
  body += dashFacts([
    flow.length && showIn ? [STR.dashAvgIn, wholeCentsEUR(avg('inCents'))] : null,
    flow.length && showOut ? [STR.dashAvgOut, wholeCentsEUR(avg('outCents'))] : null,
    o.cash ? [STR.dashIssuedMonth, wholeCentsEUR(o.cash.issuedThisMonthCents)] : null,
    !flow.length && o.cash ? [STR.financeGained, wholeCentsEUR(o.cash.collectedThisMonthCents)] : null,
    !flow.length && o.payments ? [STR.financeSpent, wholeCentsEUR(o.payments.paidThisMonthCents)] : null,
  ], true);
  return dashCard({ order: 3, title: STR.dashCashFlow, meta: STR.dashLast6Months, go: 'financeiro', body });
}

function dashMoneyCard(o) {
  let body = '';
  if (o.cash) {
    const c = o.cash;
    const aging = [
      ['neutral', STR.agingCurrent, c.agingCurrentCents || 0],
      ['warn', STR.agingWeek, c.agingWeekCents || 0],
      ['late', STR.agingMonth, c.agingMonthCents || 0],
      ['bad', STR.agingOld, c.agingOldCents || 0],
    ];
    const total = aging.reduce((sum, a) => sum + a[2], 0);
    const segs = total ? aging.filter(a => a[2] > 0).map(([tone, label, cents]) => `<span class="segbar__seg" data-tone="${tone}" style="width:${((cents / total) * 100).toFixed(2)}%" title="${escapeHTML(`${label} · ${centsEUR(cents)}`)}"></span>`).join('') : '';
    const overdue = (c.overdueCents || 0) > 0
      ? `<div class="dash-figure dash-figure--end"><span class="dash-figure__value dash-figure__value--bad">${escapeHTML(wholeCentsEUR(c.overdueCents))}</span><span class="dash-figure__label">${escapeHTML(STR.hl_overdue)}</span></div>`
      : '';
    body += `<div class="dash-split"><div class="dash-figure dash-figure--lg"><span class="dash-figure__value">${escapeHTML(wholeCentsEUR(c.outstandingCents))}</span><span class="dash-figure__label">${escapeHTML(STR.dashOutstanding)}</span></div>${overdue}</div>`;
    body += `<div class="segbar" role="img" aria-label="${escapeHTML(aging.map(([, label, cents]) => `${label} ${centsEUR(cents)}`).join('; '))}">${segs}</div>`;
    body += `<ul class="legend legend--rows">${aging.map(([tone, label, cents]) => `<li class="legend__item"><span class="legend__swatch" data-tone="${tone}"></span><span>${escapeHTML(label)}</span><span class="legend__value">${escapeHTML(wholeCentsEUR(cents))}</span></li>`).join('')}</ul>`;
    const top = (c.topOverdue || []).slice(0, 3);
    if (top.length) {
      body += `<p class="dash-sub">${escapeHTML(STR.dashTopOverdue)}</p><ul class="worklist">${top.map(t => worklistRow({
        tone: 'bad', title: t.name || t.number || '', detail: t.name && t.number ? t.number : '', meta: centsEUR(t.amountCents), amount: true, go: 'invoices', open: t.id,
      })).join('')}</ul>`;
    }
    body += dashFacts([c.dueSoonCount ? [STR.dashDueSoon, `${wholeCentsEUR(c.dueSoonCents)} · ${c.dueSoonCount}`] : null]);
  }
  if (o.payments) {
    const p = o.payments;
    body += `${o.cash ? `<p class="dash-sub">${escapeHTML(STR.dashPayables)}</p>` : ''}${dashFacts([
      [STR.financePayable, wholeCentsEUR(p.outstandingCents)],
      p.overdueCount ? [STR.hl_overdue, `${wholeCentsEUR(p.overdueCents)} · ${p.overdueCount}`] : null,
      p.dueSoonCount ? [STR.dashDueSoon, `${wholeCentsEUR(p.dueSoonCents)} · ${p.dueSoonCount}`] : null,
    ])}`;
  }
  return dashCard({ order: 4, title: o.cash ? STR.dashReceivables : STR.dashPayables, go: o.cash ? 'invoices' : 'payments', body });
}

function dashTodayCard(o) {
  const cal = o.calendar;
  const agenda = o.agenda || [];
  const now = Date.now();
  const rows = agenda.map(b => {
    const start = new Date(b.startAt).getTime();
    const end = new Date(b.endAt).getTime();
    const when = end <= now ? ' is-past' : start <= now ? ' is-now' : '';
    const minutes = Math.max(0, Math.round((end - start) / 60000));
    const what = [b.service, minutes ? fmtMinutes(minutes) : ''].filter(Boolean).join(' · ');
    const tone = { PENDING: 'warn', COMPLETED: 'neutral', NO_SHOW: 'bad' }[b.status] || 'accent';
    const pill = b.status === 'PENDING'
      ? `<span class="pill pill--warn">${escapeHTML(bookingStatusLabel(b.status))}</span>`
      : when === ' is-now' ? `<span class="pill pill--accent">${escapeHTML(STR.dashNow)}</span>` : '';
    return `<li class="agenda__item${when}" data-tone="${tone}"><span class="agenda__time">${escapeHTML(fmtTime(b.startAt))}</span><span class="agenda__rail" aria-hidden="true"></span><span class="agenda__main"><span class="agenda__who">${escapeHTML(b.contactName)}</span>${what ? `<span class="agenda__what">${escapeHTML(what)}</span>` : ''}</span>${pill}</li>`;
  }).join('');
  const next = cal.next && !agenda.some(b => b.id === cal.next.id) ? cal.next : null;
  const body = (rows ? `<ol class="agenda">${rows}</ol>` : `<p class="dash-empty">${escapeHTML(STR.dashAgendaEmpty)}</p>`) + dashFacts([
    next ? [STR.calendarNext, `${next.contactName} · ${fmtWhen(next.startAt)}`] : null,
    [STR.snapCalendar, cal.thisWeek || 0],
    [STR.calendarPending, cal.pending || 0],
  ]);
  return dashCard({ order: 2, title: STR.todayTitle, tag: cal.today || 0, go: 'bookings', body });
}

function dashPipelineCard(o) {
  const p = o.pipeline;
  const open = (p.pendingCount || 0) + (p.sentCount || 0);
  const stages = [[STR.pipelineDrafts, p.pendingCount || 0, 'neutral'], [STR.pipelineSent, p.sentCount || 0, 'info'], [STR.pipelineAccepted, p.acceptedCount || 0, 'ok']];
  const top = Math.max(1, ...stages.map(s => s[1]));
  const body = `<div class="dash-split">
      <div class="dash-figure"><span class="dash-figure__value">${escapeHTML(wholeCentsEUR(p.openCents))}</span><span class="dash-figure__label">${escapeHTML(tApp('dashOpenQuotesN', { n: open }))}</span></div>
      <div class="dash-figure dash-figure--end"><span class="dash-figure__value">${escapeHTML(`${p.winRatePct || 0}%`)}</span><span class="dash-figure__label">${escapeHTML(STR.hl_win_rate)}</span></div>
    </div>
    <ul class="hbars">${stages.map(([label, n, tone]) => `<li class="hbars__row"><span class="hbars__label">${escapeHTML(label)}</span><span class="hbars__track"><span class="hbars__fill" data-tone="${tone}" style="width:${((n / top) * 100).toFixed(1)}%"></span></span><span class="hbars__value">${n}</span></li>`).join('')}</ul>
    ${dashFacts([[STR.pipelineAcceptedMonth, `${wholeCentsEUR(p.acceptedThisMonthCents)} · ${p.acceptedThisMonthCount || 0}`], [STR.pipelineExpiring, p.expiringSoonCount || 0]])}`;
  return dashCard({ order: 5, title: STR.snapPipeline, go: 'quotes', body });
}

function dashInboxCard(o) {
  const i = o.inbox;
  const convo = hasModule('conversations');
  const activity = o.activity || [];
  const figures = convo
    ? [[i.waiting || 0, STR.hl_waiting, i.waiting > 0 ? 'warn' : ''], [i.messagesToday || 0, STR.hl_messages_today, ''], [i.messagesThisWeek || 0, STR.inboxWeek, '']]
    : [[i.contacts || 0, STR.hl_contacts, ''], [i.newContactsThisWeek || 0, STR.inboxNewContacts, '']];
  const pct = convo ? pctChange(i.messagesThisWeek || 0, i.messagesLastWeek || 0) : null;
  const body = `<div class="dash-figures">${figures.map(([v, label, tone]) => `<div class="dash-figure"><span class="dash-figure__value${tone ? ` dash-figure__value--${tone}` : ''}">${escapeHTML(String(v))}</span><span class="dash-figure__label">${escapeHTML(label)}</span></div>`).join('')}</div>
    ${activity.length > 1 ? `${sparkline(activity.map(d => d.count || 0), 'spark--tall')}<div class="dash-axis"><span>${escapeHTML(dayLabel(activity[0].day))}</span><span>${escapeHTML(STR.todayTitle)}</span></div>` : ''}
    ${dashFacts([
      pct != null ? [STR.dashWeekChange, `${pct > 0 ? '+' : ''}${pct}%`] : null,
      convo ? [STR.inboxNewContacts, i.newContactsThisWeek || 0] : null,
      convo ? [STR.hl_contacts, i.contacts || 0] : null,
      convo && i.autoReplyPaused ? [STR.inboxPaused, i.autoReplyPaused] : null,
    ])}`;
  return dashCard({ order: 6, title: STR.snapInbox, meta: activity.length > 1 ? STR.dashLast14Days : '', go: convo ? 'conversations' : 'contacts', body });
}

function dashTopClientsCard(o) {
  const top = o.topClients || [];
  const maxBilled = Math.max(1, ...top.map(t => t.billedCents || 0));
  const width = cents => ((cents / maxBilled) * 100).toFixed(1);
  const body = top.length
    ? `<ol class="rank">${top.map((t, idx) => {
      const paidPct = t.billedCents ? Math.round(((t.paidCents || 0) / t.billedCents) * 100) : 0;
      return `<li><button type="button" class="rank__item" data-go="clients" data-open="${escapeHTML(t.id)}"><span class="rank__pos">${idx + 1}</span><span class="rank__name">${escapeHTML(t.name || '—')}</span><span class="rank__value">${escapeHTML(wholeCentsEUR(t.billedCents))}</span><span class="rank__bar" aria-hidden="true"><span class="rank__fill rank__fill--billed" style="width:${width(t.billedCents || 0)}%"></span><span class="rank__fill" style="width:${width(t.paidCents || 0)}%"></span></span><span class="rank__meta">${escapeHTML(`${tApp('dashInvoicesN', { n: t.invoiceCount || 0 })} · ${tApp('dashPaidPct', { pct: paidPct })}`)}</span></button></li>`;
    }).join('')}</ol>`
    : `<p class="dash-empty">${escapeHTML(STR.dashTopClientsEmpty)}</p>`;
  return dashCard({ order: 7, title: STR.dashTopClients, meta: STR.dashLast12Months, go: 'clients', body, flush: top.length > 0 });
}

function dashRecentCard(o) {
  const items = (o.recent || []).filter(r => RECENT_TONES[r.kind]).slice(0, 6);
  const body = items.length
    ? `<ul class="worklist">${items.map(r => worklistRow({
      tone: RECENT_TONES[r.kind],
      title: tApp(`recent_${r.kind}`, { number: r.number || '' }),
      detail: [r.name, r.amountCents != null ? centsEUR(r.amountCents) : ''].filter(Boolean).join(' · '),
      meta: relTime(r.at),
      go: r.tab,
      open: r.id,
    })).join('')}</ul>`
    : `<p class="dash-empty">${escapeHTML(STR.dashRecentEmpty)}</p>`;
  return dashCard({ order: 8, title: STR.dashRecent, body, flush: items.length > 0 });
}

function renderOverviewMinimal(root) {
  const o = state.overview || {};
  const hidden = new Set(o.hiddenCards || []);
  const money = !hidden.has('financeiro');
  const health = o.health || 'ok';

  const status = hidden.has('pulse') ? '' : `<p class="dash__status dash__status--${escapeHTML(health)}"><span class="dash__dot" aria-hidden="true"></span><strong>${escapeHTML(healthLabel(health))}</strong><span class="dash__sep" aria-hidden="true">·</span><span>${escapeHTML(pulseLine(o))}</span></p>`;
  const creators = [['invoices', STR.invoiceFormTitle], ['quotes', STR.quoteFormTitle], ['clients', STR.clientFormTitle], ['bookings', STR.bookingsNew]]
    .filter(([m]) => hasModule(m)).slice(0, 3);
  const actions = (hasModule('settings') ? `<button type="button" class="btn btn--sm btn--ghost" data-go="settings" data-settings="home">${escapeHTML(STR.customizeHome)}</button>` : '')
    + creators.map(([m, label], i) => `<button type="button" class="btn btn--sm${i === 0 ? ' btn--primary' : ''}" data-create="${m}"><span class="btn__plus" aria-hidden="true">+</span>${escapeHTML(label)}</button>`).join('');
  const today = new Date().toLocaleDateString(uiLocale(), { weekday: 'long', day: 'numeric', month: 'long', timeZone: tenantTz() });
  const head = `<header class="dash__head"><div><p class="dash__date">${escapeHTML(capFirst(today))}</p><h1 class="dash__title">${escapeHTML(dashGreeting())}</h1>${status}</div>${actions ? `<div class="dash__actions">${actions}</div>` : ''}</header>`;

  const kpis = hidden.has('highlights') ? [] : dashKpis(o, hidden);
  const kpiHtml = kpis.length ? `<section class="dash-kpis" aria-label="${escapeHTML(STR.dashKpisAria)}">${kpis.map(k => {
    const metaCls = k.trend === 'up' ? ' delta delta--up' : k.trend === 'down' ? ' delta delta--down' : k.metaTone === 'bad' ? ' dash-kpi__meta--bad' : '';
    const spark = k.spark && k.spark.some(v => v > 0) ? sparkline(k.spark) : '';
    return `<button type="button" class="dash-kpi" data-go="${escapeHTML(k.go)}"><span class="dash-kpi__label">${escapeHTML(k.label)}</span><span class="dash-kpi__value${k.valueTone === 'warn' ? ' dash-kpi__value--warn' : ''}">${escapeHTML(String(k.value))}</span><span class="dash-kpi__meta${metaCls}">${escapeHTML(k.meta || '')}</span>${spark}</button>`;
  }).join('')}</section>` : '';

  const setupCopy = { wa: STR.waConnect, ig: STR.igConnect, widget: STR.setupWidget, persona: STR.teachBot };
  const setup = hidden.has('setup') ? [] : (o.setup || []);
  const setupHtml = setup.length
    ? `<section class="panel panel--card"><header class="panel__head"><h2 class="panel__title">${escapeHTML(STR.setupTitle)}</h2></header><div class="panel__body"><div class="actions">${setup.map(s => `<button type="button" class="btn btn--sm" data-go="${escapeHTML(s.tab)}" data-settings="${escapeHTML(s.section || '')}">${escapeHTML(setupCopy[s.kind] || s.kind)} <span aria-hidden="true">→</span></button>`).join('')}</div></div></section>`
    : '';

  const cards = [];
  if (!hidden.has('attention')) {
    const items = dashAttention(o);
    const rows = items.map(n => {
      let meta = '';
      let when = '';
      if (n.amountCents != null) {
        meta = centsEUR(n.amountCents);
        when = n.aggregate || !n.at ? '' : relDay(n.at);
      } else if (n.kind === 'waiting_chat' || n.kind === 'instagram_comment') meta = relTime(n.at);
      else if (n.kind === 'pending_booking') meta = fmtWhen(n.at);
      else if (n.at) meta = relDay(n.at);
      const detail = n.aggregate && n.kind === 'overdue_invoice'
        ? (o.cash?.topOverdue || []).map(t => t.name).filter(Boolean).join(', ')
        : (n.kind === 'assistant_action' ? '' : n.detail || '');
      const opens = ['overdue_invoice', 'due_soon_invoice', 'overdue_payment', 'due_soon_payment', 'quote_expiring', 'pending_booking'].includes(n.kind);
      return worklistRow({
        tone: attentionTone(n.kind), title: attentionTitle(n), detail, meta, when, amount: n.amountCents != null, go: n.tab,
        open: !n.aggregate && opens ? n.id || '' : '', conversation: n.kind === 'waiting_chat' ? n.id || '' : '',
      });
    }).join('');
    const body = rows
      ? `<ul class="worklist">${rows}</ul>`
      : `<div class="dash-empty dash-empty--center"><strong>${escapeHTML(STR.needsYouEmpty)}</strong><span>${escapeHTML(STR.needsYouEmptyDesc)}</span></div>`;
    cards.push({ col: 'main', order: 1, html: dashCard({ order: 1, title: STR.needsYou, tag: items.length || null, body, flush: !!rows }) });
  }
  if (o.calendar && !hidden.has('calendar')) cards.push({ col: 'side', order: 2, html: dashTodayCard(o) });
  if ((o.cash || o.payments) && money) {
    cards.push({ col: 'main', order: 3, html: dashCashFlowCard(o) });
    cards.push({ col: 'side', order: 4, html: dashMoneyCard(o) });
  }
  if (o.pipeline && !hidden.has('pipeline')) cards.push({ col: 'main', order: 5, html: dashPipelineCard(o) });
  if (o.inbox && !hidden.has('inbox')) cards.push({ col: 'side', order: 6, html: dashInboxCard(o) });
  if (o.customers && o.cash && !hidden.has('customers')) cards.push({ col: 'main', order: 7, html: dashTopClientsCard(o) });
  const feedSources = [['invoices', 'financeiro'], ['payments', 'financeiro'], ['quotes', 'pipeline'], ['clients', 'customers'], ['bookings', 'calendar']];
  if (feedSources.some(([m, card]) => hasModule(m) && !hidden.has(card))) cards.push({ col: 'side', order: 8, html: dashRecentCard(o) });
  const count = col => cards.filter(c => c.col === col).length;
  const column = col => cards.filter(c => c.col === col).sort((a, b) => a.order - b.order).map(c => c.html).join('');
  const single = !count('main') || !count('side');
  const gridHtml = cards.length
    ? `<div class="dash-grid${single ? ' dash-grid--single' : ''}">${single
      ? `<div class="dash-col">${cards.sort((a, b) => a.order - b.order).map(c => c.html).join('')}</div>`
      : `<div class="dash-col">${column('main')}</div><div class="dash-col">${column('side')}</div>`}</div>`
    : '';

  const inKpis = new Set(kpis.map(k => k.go));
  const plus = n => (n ? tApp('kpiNewThisMonth', { n }) : '');
  const tiles = [
    o.customers && !hidden.has('customers') && !inKpis.has('clients') ? ['clients', STR.snapCustomers, o.customers.total, plus(o.customers.newThisMonth)] : null,
    o.services && !hidden.has('services') ? ['services', STR.servicesOpen, o.services.openCount, wholeCentsEUR(o.services.openCents)] : null,
    o.suppliers && !hidden.has('suppliers') ? ['suppliers', STR.snapSuppliers, o.suppliers.total, plus(o.suppliers.newThisMonth)] : null,
    o.employees && !hidden.has('employees') ? ['employees', STR.snapEmployees, o.employees.total, plus(o.employees.newThisMonth)] : null,
    o.catalog && !hidden.has('catalog') ? ['catalog', STR.snapCatalog, o.catalog.items, STR.catalogItems] : null,
    o.social && !hidden.has('social') ? ['instagram', STR.snapSocial, o.social.unreplied, o.social.connected ? STR.hl_instagram_unreplied : STR.notConnected] : null,
    o.assistant && !hidden.has('assistant') ? ['ai-assistant', STR.snapAssistant, o.assistant.pendingActions, STR.assistantPending] : null,
  ].filter(Boolean);
  const tilesHtml = tiles.length
    ? `<section class="dash-tiles">${tiles.map(([go, label, value, meta]) => `<button type="button" class="dash-tile" data-go="${escapeHTML(go)}"><span class="dash-tile__label">${escapeHTML(label)}</span><span class="dash-tile__value">${escapeHTML(String(value ?? 0))}</span>${meta ? `<span class="dash-tile__meta">${escapeHTML(meta)}</span>` : ''}</button>`).join('')}</section>`
    : '';

  const emptyHtml = !kpiHtml && !setupHtml && !gridHtml && !tilesHtml
    ? `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.overviewEmptyTitle)}</p><p class="empty__desc">${escapeHTML(STR.overviewEmptyDesc)}</p></div></div>`
    : '';
  root.innerHTML = `<div class="dash">${head}${kpiHtml}${setupHtml}${gridHtml}${tilesHtml}${emptyHtml}</div>`;
  balanceDashColumns(root);
  document.fonts?.ready.then(() => balanceDashColumns(root));
  $$('[data-go]', root).forEach(b => b.addEventListener('click', () => dashGo(b)));
  $$('[data-create]', root).forEach(b => b.addEventListener('click', () => dashCreate(b.dataset.create)));
}

// Card heights depend on data, so even out the two columns after layout. Needs you, Today and
// Cash flow keep their column; the narrow single-column layout orders cards by data-order instead.
function balanceDashColumns(root) {
  const grid = $('.dash-grid', root);
  if (!grid || grid.classList.contains('dash-grid--single') || getComputedStyle(grid).display !== 'grid') return;
  const [main, side] = $$('.dash-col', grid);
  const gap = parseFloat(getComputedStyle(main).rowGap) || 0;
  for (let pass = 0; pass < 3; pass++) {
    const diff = main.offsetHeight - side.offsetHeight;
    const [tall, short] = diff > 0 ? [main, side] : [side, main];
    let best = null;
    let bestDiff = Math.abs(diff);
    [...tall.children].filter(card => Number(card.dataset.order) >= 4).forEach(card => {
      const next = Math.abs(Math.abs(diff) - 2 * (card.offsetHeight + gap));
      if (next < bestDiff) { best = card; bestDiff = next; }
    });
    if (!best || Math.abs(diff) - bestDiff < 48) return;
    short.insertBefore(best, [...short.children].find(card => Number(card.dataset.order) > Number(best.dataset.order)) || null);
  }
}

async function dashGo(el) {
  const { go, open, conversation, settings } = el.dataset;
  if (conversation) state.selectedConversation = conversation;
  if (settings) state.settingsSection = settings;
  await setActive(go);
  if (!open || state.active !== go) return;
  if (go === 'invoices') return openInvoiceDetail(open);
  if (go === 'quotes') return openQuoteDetail(open);
  if (go === 'payments') return openPaymentDetail(open);
  if (go === 'clients') return openClientDrawer(open);
  if (go === 'bookings') return openBookingById(open);
}

async function dashCreate(module) {
  await setActive(module);
  if (state.active !== module) return;
  if (module === 'invoices') return openInvoiceForm();
  if (module === 'quotes') return openQuoteForm();
  if (module === 'clients') return openClientForm();
  if (module === 'bookings') return openBookingForm();
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
// The directory pages list active records; the Archived chip swaps in the archived ones, ready to restore.
function directoryViewChips(kind) {
  const archived = state.archivedView === kind;
  return `<button class="chip ${archived ? '' : 'is-on'}" type="button" data-directory-view="">${escapeHTML(STR.directoryActive)}</button>`
    + `<button class="chip ${archived ? 'is-on' : ''}" type="button" data-directory-view="${kind}">${escapeHTML(STR.directoryArchived)}</button>`;
}

async function showDirectoryView(kind) {
  if (kind) {
    const rows = await api(`/app/api/crm/${kind}?archived=1`).catch(() => null);
    if (!rows) return toast(STR.loadFailed);
    state.archivedRows = rows;
  }
  state.archivedView = kind;
  render();
}

function wireDirectoryView(root) {
  $$('[data-directory-view]', root).forEach(b => b.addEventListener('click', () => showDirectoryView(b.dataset.directoryView)));
}

function renderClients(root) {
  const t = CRM.clients;
  const archived = state.archivedView === 'clients';
  const source = archived ? state.archivedRows : state.clients;
  const q = state.search.trim().toLowerCase();
  const qDigits = phoneDigits(q);
  const matches = c => !q
    || `${c.number || ''} ${c.name || ''} ${c.phone || ''} ${c.address || ''} ${c.email || ''} ${c.taxId || ''}`.toLowerCase().includes(q)
    || (qDigits.length >= 3 && qDigits === q.replace(/[\s+()-]/g, '') && phoneDigits(c.phone).includes(qDigits));
  const rows = source
    .filter(matches)
    .sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), uiLocale(), { sensitivity: 'base' }))
    .map(c => `<tr class="conversation-row" data-client="${escapeHTML(c.id)}"><td class="name">${escapeHTML(c.name)}</td><td class="muted">${escapeHTML(c.address || '')}</td><td class="mono muted">${escapeHTML(c.phone)}</td><td class="mono">${fmtDay(c.createdAt)}</td><td class="id right">${escapeHTML(c.number)}</td></tr>`)
    .join('');
  const new30 = state.clients.filter(c => c.createdAt && (Date.now() - new Date(c.createdAt)) / 86400000 <= 30).length;
  root.innerHTML = hero(labels.clients, CRM.tabs.clientes.desc, statCards([
    { label: t.total, value: state.clients.length },
    { label: t.new30, value: new30 },
  ])) + crmPanel({
    title: t.directory,
    tag: source.length,
    tools: directoryViewChips('clients'),
    head: `<tr><th>${escapeHTML(t.thName)}</th><th>${escapeHTML(t.thAddress)}</th><th>${escapeHTML(t.thPhone)}</th><th>${escapeHTML(t.thCreated)}</th><th class="right">${escapeHTML(t.thNo)}</th></tr>`,
    rows,
    empty: archived ? STR.directoryArchivedEmpty : t.emptyTitle,
    emptyDesc: archived ? STR.directoryArchivedEmptyDesc : t.emptyDesc,
  });
  $$('[data-client]', root).forEach(r => r.addEventListener('click', () => openClientDrawer(source.find(c => c.id === r.dataset.client) || r.dataset.client)));
  wireDirectoryView(root);
}

function renderSuppliers(root) {
  const t = CRM.suppliers;
  const archived = state.archivedView === 'suppliers';
  const source = archived ? state.archivedRows : (state.suppliers || []);
  const q = state.search.toLowerCase();
  const rows = source
    .filter(s => !q || `${s.number || ''} ${s.name || ''} ${s.phone || ''} ${s.address || ''}`.toLowerCase().includes(q))
    .map(s => `<tr class="conversation-row" data-supplier="${escapeHTML(s.id)}"><td class="name">${escapeHTML(s.name)}</td><td class="muted">${escapeHTML(s.address || '')}</td><td class="mono muted">${escapeHTML(s.phone)}</td><td class="mono">${fmtDay(s.createdAt)}</td><td class="id right">${escapeHTML(s.number)}</td></tr>`)
    .join('');
  const new30 = (state.suppliers || []).filter(s => s.createdAt && (Date.now() - new Date(s.createdAt)) / 86400000 <= 30).length;
  root.innerHTML = hero(labels.suppliers, CRM.tabs.fornecedores.desc, statCards([
    { label: t.total, value: (state.suppliers || []).length },
    { label: t.new30, value: new30 },
  ])) + crmPanel({
    title: t.directory,
    tag: source.length,
    tools: directoryViewChips('suppliers'),
    head: `<tr><th>${escapeHTML(t.thName)}</th><th>${escapeHTML(t.thAddress)}</th><th>${escapeHTML(t.thPhone)}</th><th>${escapeHTML(t.thCreated)}</th><th class="right">${escapeHTML(t.thNo)}</th></tr>`,
    rows,
    empty: archived ? STR.directoryArchivedEmpty : t.emptyTitle,
    emptyDesc: archived ? STR.directoryArchivedEmptyDesc : t.emptyDesc,
  });
  $$('[data-supplier]', root).forEach(r => r.addEventListener('click', () => openPayeeDrawer('supplier', source.find(s => s.id === r.dataset.supplier) || r.dataset.supplier)));
  wireDirectoryView(root);
}

function renderEmployees(root) {
  const t = CRM.employees;
  const archived = state.archivedView === 'employees';
  const source = archived ? state.archivedRows : (state.employees || []);
  const q = state.search.toLowerCase();
  const rows = source
    .filter(e => !q || `${e.number || ''} ${e.name || ''} ${e.phone || ''} ${e.role || ''}`.toLowerCase().includes(q))
    .map(e => `<tr class="conversation-row" data-employee="${escapeHTML(e.id)}"><td class="name">${escapeHTML(e.name)}</td><td class="muted">${escapeHTML(e.role || '')}</td><td class="mono muted">${escapeHTML(e.phone)}</td><td class="mono">${fmtDay(e.createdAt)}</td><td class="id right">${escapeHTML(e.number)}</td></tr>`)
    .join('');
  const new30 = (state.employees || []).filter(e => e.createdAt && (Date.now() - new Date(e.createdAt)) / 86400000 <= 30).length;
  root.innerHTML = hero(labels.employees, CRM.tabs.colaboradores.desc, statCards([
    { label: t.total, value: (state.employees || []).length },
    { label: t.new30, value: new30 },
  ])) + crmPanel({
    title: t.directory,
    tag: source.length,
    tools: directoryViewChips('employees'),
    head: `<tr><th>${escapeHTML(t.thName)}</th><th>${escapeHTML(t.thRole)}</th><th>${escapeHTML(t.thPhone)}</th><th>${escapeHTML(t.thCreated)}</th><th class="right">${escapeHTML(t.thNo)}</th></tr>`,
    rows,
    empty: archived ? STR.directoryArchivedEmpty : t.emptyTitle,
    emptyDesc: archived ? STR.directoryArchivedEmptyDesc : t.emptyDesc,
  });
  $$('[data-employee]', root).forEach(r => r.addEventListener('click', () => openPayeeDrawer('employee', source.find(e => e.id === r.dataset.employee) || r.dataset.employee)));
  wireDirectoryView(root);
}

function renderServices(root) {
  const t = CRM.services;
  const q = state.search.toLowerCase();
  const all = state.clientServices || [];
  const clientNames = new Map();
  for (const c of state.clients || []) clientNames.set(c.id, c.name);
  for (const s of all) if (!clientNames.has(s.clientId)) clientNames.set(s.clientId, s.clientName || s.clientId);
  if (state.filterServiceClient && !clientNames.has(state.filterServiceClient)) state.filterServiceClient = '';
  const clientId = state.filterServiceClient || '';
  const scoped = clientId ? all.filter(s => s.clientId === clientId) : all;
  const rows = scoped
    .filter(s => !state.filterServiceStatus || s.status === state.filterServiceStatus)
    .filter(s => !q || `${s.name || ''} ${s.clientName || ''} ${serviceStatusLabel(s.status)}`.toLowerCase().includes(q))
    .map(s => {
      const canPick = s.status === 'OPEN';
      return `<tr class="conversation-row ${s.status === 'INVOICED' ? 'is-paid' : ''}" data-service="${escapeHTML(s.id)}">
        <td class="check">${canPick ? `<input type="checkbox" data-pick-service="${escapeHTML(s.id)}" data-client="${escapeHTML(s.clientId)}" />` : ''}</td>
        <td class="mono muted">${escapeHTML(fmtDay(s.performedAt || s.createdAt))}</td>
        <td class="name">${escapeHTML(s.clientName || '')}</td>
        <td>${escapeHTML(s.name)}${s.bookingId ? ` <span class="muted">· ${escapeHTML(t.fromBooking)}</span>` : ''}</td>
        <td class="mono muted">${s.quantity}${s.unit ? ` ${escapeHTML(s.unit)}` : ''}</td>
        <td class="num">${fmtEUR(s.totalEur)}</td>
        <td>${servicePill(s.status)}</td>
      </tr>`;
    }).join('');
  const open = scoped.filter(s => s.status === 'OPEN');
  const openCents = open.reduce((sum, s) => sum + Number(s.totalEur || 0), 0);
  const invoiced = scoped.filter(s => s.status === 'INVOICED');
  const clientOptions = [...clientNames.entries()].sort((a, b) => String(a[1]).localeCompare(String(b[1]), uiLocale()));
  const clientFilter = clientOptions.length
    ? `<select class="sel" data-filter-svc-client aria-label="${escapeHTML(t.thClient)}"><option value="">${escapeHTML(t.filterClientAll)}</option>${clientOptions.map(([id, name]) => `<option value="${escapeHTML(id)}" ${id === clientId ? 'selected' : ''}>${escapeHTML(name)}</option>`).join('')}</select>`
    : '';
  const period = state.filterServicePeriod || '';
  const listed = scoped
    .filter(s => !state.filterServiceStatus || s.status === state.filterServiceStatus)
    .filter(s => !q || `${s.name || ''} ${s.clientName || ''} ${serviceStatusLabel(s.status)}`.toLowerCase().includes(q));
  const serviceGroups = period ? groupByPeriod(listed, period, s => s.performedAt || s.createdAt) : [];
  const nowKey = period ? (state.filterServicePeriodKey || currentPeriodKey(period)) : '';
  const nowRoll = serviceRollup(groupItems(serviceGroups, nowKey));
  const prevKey = period ? shiftPeriodKey(nowKey, period, -1) : '';
  const prevExists = serviceGroups.some(([key]) => key === prevKey);
  const delta = prevExists ? periodDelta(nowRoll.total, serviceRollup(groupItems(serviceGroups, prevKey)).total) : { text: '', trend: '' };
  const allRoll = serviceRollup(listed);
  const summaryRows = period && serviceGroups.length
    ? `<tr class="is-total"><td class="name">${escapeHTML(t.thTotal)}</td><td class="num">${allRoll.jobs}</td><td class="num">${fmtEUR(allRoll.open)}</td><td class="num">${fmtEUR(allRoll.invoiced)}</td><td class="num">${fmtEUR(allRoll.total)}</td></tr>`
      + serviceGroups.map(([key, items]) => {
        const roll = serviceRollup(items);
        return `<tr class="${key === nowKey ? 'is-current' : ''}"><td class="name">${escapeHTML(periodLabel(key, period))}</td><td class="num">${roll.jobs}</td><td class="num">${fmtEUR(roll.open)}</td><td class="num">${fmtEUR(roll.invoiced)}</td><td class="num">${fmtEUR(roll.total)}</td></tr>`;
      }).join('')
    : '';
  const views = periodChips(period, 'data-svc-period', { '': t.viewList, week: t.byWeek, month: t.byMonth });
  const tools = clientFilter
    + `<button class="chip ${!state.filterServiceStatus ? 'is-on' : ''}" data-filter-svc="">${escapeHTML(t.filterAll)}</button>`
    + ['OPEN', 'INVOICED', 'CANCELLED'].map(s => `<button class="chip ${state.filterServiceStatus === s ? 'is-on' : ''}" data-filter-svc="${s}">${escapeHTML(serviceStatusLabel(s))}</button>`).join('')
    + (!period && hasModule('invoices') ? `<button class="btn btn--sm btn--primary" type="button" data-invoice-services>${escapeHTML(t.invoiceSelected)}</button>` : '');
  const filtered = Boolean(clientId || state.filterServiceStatus || q);
  const summaryHead = `<tr><th>${escapeHTML(t.thPeriod)}</th><th class="right">${escapeHTML(t.thCount)}</th><th class="right">${escapeHTML(t.open)}</th><th class="right">${escapeHTML(t.invoiced)}</th><th class="right">${escapeHTML(t.thTotal)}</th></tr>`;
  const listHead = `<tr><th></th><th>${escapeHTML(t.thWhen)}</th><th>${escapeHTML(t.thClient)}</th><th>${escapeHTML(t.thService)}</th><th>${escapeHTML(t.thQty)}</th><th class="right">${escapeHTML(t.thTotal)}</th><th>${escapeHTML(t.thStatus)}</th></tr>`;
  const serviceNav = periodNav(nowKey, period, 'data-svc-period-nav', t);
  const serviceStats = period
    ? [
      { label: t.thCount, value: nowRoll.jobs },
      { label: t.open, value: fmtEUR(nowRoll.open) },
      { label: t.invoiced, value: fmtEUR(nowRoll.invoiced) },
      { label: t.thTotal, value: fmtEUR(nowRoll.total), hint: delta.text ? t.vsPrev({ delta: delta.text }) : '', trend: delta.trend },
    ]
    : [
      { label: t.open, value: `${open.length} · ${fmtEUR(openCents)}` },
      { label: t.invoiced, value: invoiced.length },
    ];
  root.innerHTML = hero(labels.services, CRM.tabs.servicos.desc, statCards(serviceStats), serviceNav) + crmPanel({
    title: period === 'week' ? t.summaryWeek : period === 'month' ? t.summaryMonth : t.work,
    tag: period ? serviceGroups.length : listed.length,
    views,
    tools,
    head: period ? summaryHead : listHead,
    rows: period ? summaryRows : rows,
    empty: period ? t.summaryEmpty : (filtered ? t.emptyFiltered : t.emptyTitle),
    emptyDesc: period ? t.summaryEmptyDesc : (filtered ? t.emptyFilteredDesc : t.emptyDesc),
  });
  $$('[data-svc-period]', root).forEach(btn => btn.addEventListener('click', () => { state.filterServicePeriod = btn.dataset.svcPeriod; state.filterServicePeriodKey = ''; render(); }));
  $$('[data-svc-period-nav]', root).forEach(btn => btn.addEventListener('click', () => {
    const dir = btn.dataset.svcPeriodNav;
    state.filterServicePeriodKey = dir === 'current' ? '' : shiftPeriodKey(nowKey, period, dir === 'prev' ? -1 : 1);
    render();
  }));
  $$('[data-filter-svc]', root).forEach(btn => btn.addEventListener('click', () => { state.filterServiceStatus = btn.dataset.filterSvc; render(); }));
  $('[data-filter-svc-client]', root)?.addEventListener('change', e => { state.filterServiceClient = e.target.value; render(); });
  $$('[data-pick-service]', root).forEach(box => box.addEventListener('click', e => e.stopPropagation()));
  $$('[data-service]', root).forEach(r => r.addEventListener('click', e => {
    if (e.target.closest('[data-pick-service]')) return;
    openServiceDetail(state.clientServices.find(s => s.id === r.dataset.service) || r.dataset.service);
  }));
  $('[data-invoice-services]', root)?.addEventListener('click', invoiceSelectedServices);
}

// Bookable services are catalog services, so both lists merge into one (the booking list only
// arrives when the Catalog module is off but Bookings is on).
function serviceSourceOptions() {
  const byId = new Map();
  for (const c of (state.catalog || []).filter(c => c.type === 'service' || c.type === 'servico')) {
    byId.set(c.id, { id: c.id, name: c.description, price: c.defaultUnitPriceEur, unit: c.unit || '' });
  }
  for (const s of state.bookingServices || []) {
    if (!byId.has(s.id)) byId.set(s.id, { id: s.id, name: s.name, price: s.priceEur, unit: s.unit || '' });
  }
  return [...byId.values()]
    .sort((a, b) => String(a.name).localeCompare(String(b.name), uiLocale()))
    .map(s => `<option value="${escapeHTML(s.id)}" data-name="${escapeHTML(s.name)}" data-price="${s.price ?? ''}" data-unit="${escapeHTML(s.unit)}">${escapeHTML(s.name)}</option>`)
    .join('');
}

async function openServiceForm(service, presetClientId) {
  const t = CRM.services;
  const editing = service && service.id ? service : null;
  const missingPreset = presetClientId && !state.clients.some(c => c.id === presetClientId);
  if ((!state.clients.length || missingPreset) && hasModule('clients')) state.clients = await api('/app/api/crm/clients').catch(() => state.clients);
  if (hasModule('catalog') && !state.catalog.length) state.catalog = await api('/app/api/crm/standard-items').catch(() => []);
  if (hasModule('bookings') && !state.bookingServices.length) state.bookingServices = await api('/app/api/bookings/services').catch(() => []);
  const sources = serviceSourceOptions();
  const clientId = editing?.clientId || presetClientId || '';
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    ${editing ? '' : clientSelect(state.clients)}
    ${editing ? `<p class="hint">${escapeHTML(editing.clientName || '')}</p>` : ''}
    ${sources && !editing ? `<div class="form__row"><label class="lbl" for="svc-source">${escapeHTML(t.fromCatalog)}</label>
      <select class="sel" id="svc-source"><option value="">${escapeHTML(t.fromCatalogNone)}</option>${sources}</select></div>` : ''}
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
        <input class="inp" id="svc-when" type="date" value="${escapeHTML((editing ? editing.performedAt || '' : todayKey()).slice(0, 10))}" ${editing?.status === 'INVOICED' ? 'readonly' : ''} /></div>
    </div>
    <div class="form__row form__row--full"><label class="lbl" for="svc-notes">${escapeHTML(t.notes)}</label>
      <textarea class="txt" id="svc-notes" ${editing?.status === 'INVOICED' ? 'readonly' : ''}>${escapeHTML(editing?.notes || '')}</textarea></div>
    ${editing?.status === 'INVOICED' ? `<p class="hint">${escapeHTML(t.invoicedLocked)}</p>` : `<p class="hint" id="svc-total"></p>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(editing ? STR.clientSaveChanges : t.save)}</button>
      ${editing ? `<button class="btn btn--ghost" type="button" data-form-cancel>${escapeHTML(STR.cancel)}</button>` : ''}
    </div>`}`;
  if (clientId && $('#f-client', form)) $('#f-client', form).value = clientId;
  $('[data-form-cancel]', form)?.addEventListener('click', () => closeDrawer());
  const totalHint = $('#svc-total', form);
  const refreshTotal = () => {
    if (totalHint) totalHint.textContent = `${t.thTotal}: ${fmtEUR(Number($('#svc-qty', form).value || 0) * Number($('#svc-price', form).value || 0))}`;
  };
  ['#svc-qty', '#svc-price'].forEach(sel => $(sel, form)?.addEventListener('input', refreshTotal));
  refreshTotal();
  const source = $('#svc-source', form);
  source?.addEventListener('change', () => {
    const opt = source.selectedOptions[0];
    if (!opt || !opt.value) return;
    $('#svc-name', form).value = opt.dataset.name || '';
    if (opt.dataset.price) $('#svc-price', form).value = opt.dataset.price;
    if (opt.dataset.unit) $('#svc-unit', form).value = opt.dataset.unit;
    refreshTotal();
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
      catalogItemId: sourceVal || null,
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

async function cancelClientService(service) {
  const t = CRM.services;
  const ok = await confirmDialog({
    title: t.cancelConfirmTitle,
    body: t.cancelConfirmBody({ name: service.name }),
    okLabel: t.cancelOk,
  });
  if (!ok) return;
  try {
    const updated = await api(`/app/api/crm/services/${encodeURIComponent(service.id)}`, {
      method: 'PATCH',
      body: JSON.stringify(servicePatch(service, 'CANCELLED')),
    });
    if (updated.status !== 'CANCELLED') throw new Error('not cancelled');
    closeDrawer();
    await loadModule('services');
    render();
    toast(t.cancelled);
  } catch { toast(t.cancelFailed); }
}

async function deleteClientService(service) {
  const t = CRM.services;
  const ok = await confirmDialog({
    title: t.deleteConfirmTitle,
    body: t.deleteConfirmBody({ name: service.name }),
    okLabel: t.deleteOk,
  });
  if (!ok) return;
  try {
    await api(`/app/api/crm/services/${encodeURIComponent(service.id)}`, { method: 'DELETE' });
    closeDrawer();
    await loadModule('services');
    render();
    toast(t.deleted);
  } catch { toast(t.deleteFailed); }
}

const serviceTrailEntry = s => ({ key: `service:${s.id}`, label: s.name, open: () => openServiceDetail(s.id) });
// PATCH replaces notes with whatever is sent, so a status change resends the whole row.
const servicePatch = (s, status) => ({
  name: s.name, notes: s.notes, quantity: s.quantity, unit: s.unit || '', unitPriceEur: s.unitPriceEur,
  performedAt: (s.performedAt || '').slice(0, 10) || null, status,
});

// A service opens as a detail (who, what, where it came from, where it was billed), like an invoice;
// Edit is one of its actions.
async function openServiceDetail(ref) {
  const t = CRM.services;
  const id = typeof ref === 'string' ? ref : ref?.id;
  if (!id) return;
  const known = (typeof ref === 'object' && ref) || (state.clientServices || []).find(s => s.id === id) || null;
  const service = await api(`/app/api/crm/services/${encodeURIComponent(id)}`).catch(() => known);
  if (!service) return toast(STR.loadFailed);
  const [invoice, booking] = await Promise.all([
    service.invoiceId && hasModule('invoices') ? api(`/app/api/crm/invoices/${encodeURIComponent(service.invoiceId)}`).catch(() => null) : null,
    service.bookingId && hasModule('bookings') ? api(`/app/api/bookings/${encodeURIComponent(service.bookingId)}`).catch(() => null) : null,
  ]);
  const here = serviceTrailEntry(service);
  const isOpen = service.status === 'OPEN';
  const tone = { OPEN: 'warn', INVOICED: 'ok' }[service.status] || '';
  const clientLink = hasModule('clients') && !!service.clientId && !linksBackTo(`client:${service.clientId}`);
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `
    ${detailHead(service.clientName, servicePill(service.status), service.totalEur, tone, clientLink)}
    ${detailMeta([
      { label: t.when, value: service.performedAt ? fmtDay(service.performedAt) : '—' },
      { label: t.qty, value: `${service.quantity}${service.unit ? ` ${service.unit}` : ''} × ${fmtEUR(service.unitPriceEur)}` },
      booking ? { label: STR.clientKindBooking, value: bookingWhen(booking) } : null,
      invoice ? { label: STR.detailInvoice, value: invoice.number } : null,
      { label: STR.detailCreated, value: fmtDay(service.createdAt) },
    ])}
    ${service.notes ? `<p class="hint">${escapeHTML(service.notes)}</p>` : ''}
    ${service.status === 'INVOICED' ? `<p class="hint">${escapeHTML(t.invoicedLocked)}</p>` : ''}
    <div class="detail__foot">
      ${isOpen && hasModule('invoices') ? `<button class="btn btn--sm btn--accent" type="button" data-svc-invoice>${escapeHTML(t.invoiceNow)}</button>` : ''}
      ${isOpen ? `<button class="btn btn--sm" type="button" data-svc-edit>${escapeHTML(STR.clientEditAction)}</button>` : ''}
      ${service.status === 'CANCELLED' && !service.bookingId ? `<button class="btn btn--sm" type="button" data-svc-reopen>${escapeHTML(t.reopen)}</button>` : ''}
      ${invoice && !linksBackTo(`invoice:${invoice.id}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-svc-open-invoice>${escapeHTML(STR.detailOpenDoc({ number: invoice.number }))}</button>` : ''}
      ${booking && !linksBackTo(`booking:${booking.id}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-svc-open-booking>${escapeHTML(t.openBooking)}</button>` : ''}
      ${isOpen ? `<button class="btn btn--sm btn--ghost" type="button" data-svc-cancel>${escapeHTML(t.cancel)}</button>` : ''}
      ${service.status !== 'INVOICED' ? `<button class="btn btn--sm btn--ghost" type="button" data-svc-delete>${escapeHTML(t.delete)}</button>` : ''}
    </div>`;
  $('[data-detail-link]', body)?.addEventListener('click', () => openFrom(here, () => openClientDrawer(service.clientId)));
  $('[data-svc-edit]', body)?.addEventListener('click', () => openFrom(here, () => openServiceForm(service)));
  $('[data-svc-invoice]', body)?.addEventListener('click', () => openFrom(here, async () => {
    const open = await api(`/app/api/crm/services?clientId=${encodeURIComponent(service.clientId)}&status=OPEN`).catch(() => [service]);
    openInvoiceOpenWork({ id: service.clientId, name: service.clientName }, open, [service.id]);
  }));
  $('[data-svc-open-invoice]', body)?.addEventListener('click', () => openFrom(here, () => openInvoiceDetail(invoice.id)));
  $('[data-svc-open-booking]', body)?.addEventListener('click', () => openFrom(here, () => openBookingDetail(booking)));
  $('[data-svc-cancel]', body)?.addEventListener('click', () => cancelClientService(service));
  $('[data-svc-delete]', body)?.addEventListener('click', () => deleteClientService(service));
  $('[data-svc-reopen]', body)?.addEventListener('click', async e => {
    const btn = e.currentTarget;
    btn.disabled = true;
    try {
      await api(`/app/api/crm/services/${encodeURIComponent(service.id)}`, { method: 'PATCH', body: JSON.stringify(servicePatch(service, 'OPEN')) });
      toast(t.reopened);
      if (state.active === 'services') { await loadModule('services').catch(() => {}); render(); }
      openServiceDetail(service.id);
    } catch { btn.disabled = false; toast(t.reopenFailed); }
  });
  openDrawer(service.name, body);
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
// ── Client record ──────────────────────────────────────────────────────────────
// A client opens as a record: who they are and how to reach them, what they owe, what's next, then
// the full history. Edit is one click away. Drawers opened from here come back to it (drawerTrail).
let clientRecord = null;
const EMAIL_SHAPE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const phoneDigits = p => String(p || '').replace(/\D/g, '');
// Same rule as the server's findByPhone: numbers of 9+ digits compare on their last 9 digits.
const phoneKey = p => { const d = phoneDigits(p); return d.length >= 9 ? d.slice(-9) : d; };
const sumBy = (list, key) => list.reduce((t, x) => t + Number(x[key] || 0), 0);
const telHref = phone => `tel:${String(phone || '').replace(/[^\d+]/g, '')}`;
const mapsHref = address => `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(address)}`;
function clientInitials(name) {
  const words = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (!words.length) return '?';
  return (words[0][0] + (words.length > 1 ? words[words.length - 1][0] : '')).toUpperCase();
}
// wa.me needs the country code; a number typed without one is Portuguese, the product's market.
function waHref(phone) {
  const raw = String(phone || '').trim();
  const digits = phoneDigits(raw);
  const intl = raw.startsWith('00') ? digits.slice(2) : (!raw.startsWith('+') && digits.length === 9 ? `351${digits}` : digits);
  return `https://wa.me/${intl}`;
}
// Nothing flips an invoice or payment to OVERDUE, so, like Home, a pending one past its due date counts.
function isPastDue(doc, today = todayKey()) {
  return doc.status === 'OVERDUE' || (doc.status === 'PENDING' && !!doc.dueDate && doc.dueDate.slice(0, 10) < today);
}
const effectiveStatus = doc => (isPastDue(doc) ? 'OVERDUE' : doc.status);

function matchConversation(client, list) {
  const key = phoneKey(client.phone);
  if (key.length < 6) return null;
  return list
    .filter(c => c.channel === 'WHATSAPP' && phoneKey(c.waId) === key)
    .sort((a, b) => String(b.lastMessageAt || '').localeCompare(String(a.lastMessageAt || '')))[0] || null;
}

// The booking form and legacy booking prices read the bookable services and opening hours,
// which only the Bookings page loads.
async function ensureBookingData() {
  if (!hasModule('bookings') || state.bookingServices.length) return;
  const [services, availability] = await Promise.all([
    api('/app/api/bookings/services').catch(() => []),
    api('/app/api/bookings/availability').catch(() => []),
  ]);
  state.bookingServices = services;
  state.bookingAvailability = availability;
}

async function loadClientRecord(id) {
  const q = encodeURIComponent(id);
  const related = (module, path) => (hasModule(module) ? api(path).catch(() => []) : Promise.resolve([]));
  const conversations = !hasModule('conversations') ? Promise.resolve([])
    : state.fetched.conversations ? Promise.resolve(state.conversations)
    : api('/app/api/conversations').catch(() => []);
  const [client, quotes, invoices, services, bookings, chats] = await Promise.all([
    api(`/app/api/crm/clients/${q}`).catch(() => null),
    related('quotes', `/app/api/crm/quotes?clientId=${q}`),
    related('invoices', `/app/api/crm/invoices?clientId=${q}`),
    related('services', `/app/api/crm/services?clientId=${q}`),
    related('bookings', `/app/api/bookings?clientId=${q}`),
    conversations,
    ensureBookingData(),
  ]);
  return {
    client,
    quotes: quotes || [],
    invoices: invoices || [],
    services: services || [],
    bookings: bookings || [],
    conversation: client ? matchConversation(client, chats || []) : null,
  };
}

function clientMoney(r) {
  const today = todayKey();
  const live = r.invoices.filter(i => i.status !== 'CANCELLED');
  const unpaid = live.filter(i => i.status === 'PENDING' || i.status === 'OVERDUE');
  const overdue = unpaid.filter(i => isPastDue(i, today)).sort((a, b) => String(a.dueDate).localeCompare(String(b.dueDate)));
  const openWork = r.services.filter(s => s.status === 'OPEN');
  const openQuotes = r.quotes.filter(q => q.status === 'PENDENTE' || q.status === 'SENT');
  return {
    billed: sumBy(live, 'totalEur'),
    paid: sumBy(live.filter(i => i.status === 'PAID'), 'totalEur'),
    unpaid,
    outstanding: sumBy(unpaid, 'totalEur'),
    overdue,
    overdueTotal: sumBy(overdue, 'totalEur'),
    openWork,
    openWorkTotal: sumBy(openWork, 'totalEur'),
    openQuotes,
    openQuotesTotal: sumBy(openQuotes, 'totalEur'),
  };
}

function clientBookingsSplit(r) {
  const now = Date.now();
  const sorted = [...r.bookings].sort((a, b) => a.startAt.localeCompare(b.startAt));
  const isLive = b => b.status === 'PENDING' || b.status === 'CONFIRMED';
  const upcoming = sorted.filter(b => isLive(b) && new Date(b.endAt || b.startAt).getTime() >= now);
  return {
    upcoming,
    past: sorted.filter(b => !upcoming.includes(b)).reverse(),
    // Staff never marked these done or no-show, so they were never billed either.
    unclosed: sorted.filter(b => isLive(b) && new Date(b.endAt || b.startAt).getTime() < now),
    visits: r.bookings.filter(b => b.status === 'COMPLETED').length,
    noShows: r.bookings.filter(b => b.status === 'NO_SHOW').length,
  };
}

function clientLastActivity(r) {
  const now = Date.now();
  return [
    ...r.bookings.filter(b => b.status !== 'CANCELLED').map(b => b.startAt),
    ...r.services.map(s => s.performedAt || s.createdAt),
    ...r.quotes.map(q => q.createdAt),
    ...r.invoices.flatMap(i => [i.createdAt, i.paidAt]),
    r.conversation?.lastMessageAt,
  ].filter(s => s && new Date(s).getTime() <= now).sort().pop() || null;
}

// ── Record building blocks (client, supplier and employee records) ─────────────
function recordRowHtml(item) {
  const side = [
    item.pill ? `<span class="worklist__meta">${item.pill}</span>` : '',
    item.amount != null ? `<span class="worklist__meta worklist__meta--amount">${fmtEUR(item.amount)}</span>` : '',
    item.when ? `<span class="worklist__when">${escapeHTML(item.when)}</span>` : '',
  ].join('');
  return `<li><button class="worklist__item" type="button" data-record-open="${escapeHTML(item.open)}" data-tone="${item.tone || 'neutral'}">
    <span class="worklist__dot" aria-hidden="true"></span>
    <span class="worklist__main"><span class="worklist__title">${escapeHTML(item.title)}</span>${item.detail ? `<span class="worklist__detail">${escapeHTML(item.detail)}</span>` : ''}</span>
    <span class="worklist__side">${side}</span>
  </button></li>`;
}

// Call and WhatsApp buttons for a phone number; extra buttons (open chat…) go after them.
function contactButtons(phone, extra = '') {
  return [
    phoneDigits(phone).length >= 6 ? `<a class="btn btn--sm" href="${escapeHTML(telHref(phone))}">${escapeHTML(STR.clientCall)}</a>` : '',
    phoneDigits(phone).length >= 9 ? `<a class="btn btn--sm" href="${escapeHTML(waHref(phone))}" target="_blank" rel="noopener">${escapeHTML(STR.clientWhatsApp)}</a>` : '',
    extra,
  ].join('');
}

// `lines` and `actions` are trusted HTML built by the caller (escaped values); everything else is escaped here.
function recordCardHtml({ name, lines, since, contact = '', notes = '', actions = '', archivedAt = null }) {
  return `<section class="record-card">
    <div class="record-card__head">
      <span class="record-card__avatar" aria-hidden="true">${escapeHTML(clientInitials(name))}</span>
      <div class="record-card__who">
        <div class="record-card__lines">${lines}</div>
        <p class="record-card__since">${escapeHTML(since)}</p>
      </div>
      <div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-record-edit>${escapeHTML(STR.clientEditAction)}</button>${actions}</div>
    </div>
    ${archivedAt ? `<p class="hint hint--warn">${escapeHTML(STR.recordArchivedOn({ date: fmtDay(archivedAt) }))}</p>` : ''}
    ${contact ? `<div class="record-card__contact">${contact}</div>` : ''}
    ${notes ? `<div class="record-card__notes"><span class="record-card__notes-label">${escapeHTML(STR.clientFormNotes)}</span>${escapeHTML(notes)}</div>` : ''}
  </section>`;
}

function recordKpisHtml(cells) {
  const shown = cells.slice(0, 4);
  if (!shown.length) return '';
  return `<div class="record-kpis" data-count="${shown.length}">${shown.map(k => `<div class="record-kpi"${k.tone ? ` data-tone="${k.tone}"` : ''} title="${escapeHTML(`${k.label}: ${k.value} · ${k.sub}`)}">
    <span class="record-kpi__label">${escapeHTML(k.label)}</span>
    <span class="record-kpi__value">${escapeHTML(k.value)}</span>
    <span class="record-kpi__sub">${escapeHTML(k.sub)}</span>
  </div>`).join('')}</div>`;
}

function recordAttentionHtml(items) {
  if (!items.length) return '';
  return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(STR.clientAttention)} <span class="tag">${items.length}</span></h2></header><ul class="worklist">${items.map(recordRowHtml).join('')}</ul></section>`;
}

function recordTable(head, rows, heading = null) {
  const title = heading ? `<header class="panel__head"><h2 class="panel__title">${escapeHTML(heading.title)}${heading.count ? ` <span class="tag">${heading.count}</span>` : ''}</h2></header>` : '';
  return `<div class="panel">${title}<div class="tbl-wrap"><table class="tbl"><thead><tr>${head.map(([label, cls]) => `<th${cls ? ` class="${cls}"` : ''}>${escapeHTML(label)}</th>`).join('')}</tr></thead><tbody>${rows}</tbody></table></div></div>`;
}

function recordEmpty(title, act, actLabel) {
  return `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(title)}</p>${act ? `<button class="btn btn--sm" type="button" data-record-act="${act}"><span class="btn__plus">+</span> ${escapeHTML(actLabel)}</button>` : ''}</div></div>`;
}

function recordFootHtml(acts) {
  return acts.length
    ? `<div class="drawer__foot record__foot">${acts.map(([kind, label]) => `<button class="btn btn--sm" type="button" data-record-act="${kind}"><span class="btn__plus">+</span> ${escapeHTML(label)}</button>`).join('')}</div>`
    : '';
}

// A client, supplier or employee that documents refer to can't be deleted (the server answers 409
// `in_use`), only archived, so its quotes, invoices and payments keep their name and PDFs.
function recordRemovalButton(record) {
  return record.archivedAt
    ? `<button class="btn btn--sm btn--ghost" type="button" data-record-restore>${escapeHTML(STR.recordRestore)}</button>`
    : `<button class="btn btn--sm btn--ghost" type="button" data-record-remove>${escapeHTML(STR.recordDelete)}</button>`;
}

async function removeDirectoryRecord(kind, record, { inUse, archiveBody, onDeleted, onArchived }) {
  const url = `/app/api/crm/${kind}/${encodeURIComponent(record.id)}`;
  if (!inUse) {
    if (!await confirmDialog({ title: STR.recordDeleteTitle({ name: record.name }), body: STR.recordDeleteBody, okLabel: STR.recordDelete })) return;
    try {
      await api(url, { method: 'DELETE' });
      toast(STR.recordDeleted);
      return onDeleted();
    } catch (err) {
      // Documents the record didn't show (a module that is off) still count: offer to archive instead.
      if (err?.code !== 'in_use') { if (err?.message !== 'unauthorized') toast(STR.recordRemoveFailed); return; }
    }
  }
  if (!await confirmDialog({ title: STR.recordArchiveTitle({ name: record.name }), body: archiveBody, okLabel: STR.recordArchive, danger: false })) return;
  try {
    const res = await api(`${url}/archive`, { method: 'POST' });
    toast(STR.recordArchived);
    onArchived(res.archivedAt);
  } catch (err) { if (err?.message !== 'unauthorized') toast(STR.recordRemoveFailed); }
}

async function restoreDirectoryRecord(kind, record, onRestored) {
  try {
    await api(`/app/api/crm/${kind}/${encodeURIComponent(record.id)}/restore`, { method: 'POST' });
    toast(STR.recordRestored);
    onRestored();
  } catch (err) { if (err?.message !== 'unauthorized') toast(STR.recordRemoveFailed); }
}

// Pickers read the active lists, so they are reloaded after a record is deleted, archived or restored.
async function refreshDirectory(kind) {
  const [active, archived] = await Promise.all([
    api(`/app/api/crm/${kind}`).catch(() => null),
    state.archivedView === kind ? api(`/app/api/crm/${kind}?archived=1`).catch(() => null) : null,
  ]);
  if (active) state[kind] = active;
  if (archived) state.archivedRows = archived;
  render();
}

// Relative due date of an unpaid document: "was due 12 days ago" or "due in 3 days".
function dueWhenText(dueDate, overdue) {
  return overdue ? STR.clientAttnOverdueDetail({ when: relDay(dueDate) }) : STR.clientDueWhen({ when: relDay(dueDate) });
}

function clientCardHtml(r, bk) {
  const c = r.client;
  const last = clientLastActivity(r);
  const since = [
    STR.clientSince({ date: fmtDay(c.createdAt) }),
    bk.visits ? STR.clientVisits({ n: bk.visits }) : '',
    bk.noShows ? STR.bookingsClientNoShows({ n: bk.noShows }) : '',
    last ? STR.clientLastActivity({ when: relTime(last) }) : '',
  ].filter(Boolean).join(' · ');
  const lines = [
    `<span class="record-card__line mono">${escapeHTML(c.phone)}</span>`,
    c.email ? `<a class="record-card__line" href="mailto:${escapeHTML(c.email)}">${escapeHTML(c.email)}</a>` : '',
    c.address ? `<a class="record-card__line" href="${escapeHTML(mapsHref(c.address))}" target="_blank" rel="noopener">${escapeHTML(c.address)}</a>` : '',
    c.taxId ? `<span class="record-card__line mono">${escapeHTML(STR.clientTaxIdShort({ id: c.taxId }))}</span>` : '',
  ].filter(Boolean).join('');
  const chat = r.conversation
    ? `<button class="btn btn--sm" type="button" data-record-open="chat">${escapeHTML(STR.clientOpenChat)}${r.conversation.waiting ? ` <span class="pill pill--warn">${escapeHTML(STR.waiting)}</span>` : ''}</button>`
    : '';
  return recordCardHtml({
    name: c.name, lines, since, contact: contactButtons(c.phone, chat), notes: c.notes,
    actions: recordRemovalButton(c), archivedAt: c.archivedAt,
  });
}

function clientKpiCells(m, bk) {
  const cells = [];
  if (hasModule('invoices')) {
    cells.push({
      label: STR.clientKpiOutstanding,
      value: fmtEUR(m.outstanding),
      sub: m.overdueTotal ? STR.clientKpiOverdue({ amount: fmtEUR(m.overdueTotal) })
        : m.unpaid.length ? STR.clientKpiUnpaid({ n: m.unpaid.length }) : STR.clientKpiNothingDue,
      tone: m.overdueTotal ? 'bad' : m.outstanding ? 'warn' : '',
    });
  }
  if (hasModule('bookings')) {
    const next = bk.upcoming[0];
    const key = next ? bookingLocal(next).slice(0, 10) : '';
    const relative = next ? relativeDayLabel(key) : '';
    cells.push({
      label: STR.clientKpiNextBooking,
      value: next ? relative || fmtDayKey(key, { day: 'numeric', month: 'short' }) : '—',
      sub: next
        ? [relative ? '' : capFirst(fmtDayKey(key, { weekday: 'short' })), fmtTime(next.startAt), bookingServiceName(next)].filter(Boolean).join(' · ')
        : STR.clientKpiNoBooking,
    });
  }
  if (hasModule('services')) {
    cells.push({
      label: STR.clientKpiUnbilled,
      value: fmtEUR(m.openWorkTotal),
      sub: STR.clientKpiServices({ n: m.openWork.length }),
      tone: m.openWork.length ? 'warn' : '',
    });
  }
  if (hasModule('invoices')) cells.push({ label: STR.clientKpiBilled, value: fmtEUR(m.billed), sub: STR.clientKpiPaid({ amount: fmtEUR(m.paid) }) });
  if (hasModule('quotes')) {
    cells.push({
      label: STR.clientKpiQuotes,
      value: fmtEUR(m.openQuotesTotal),
      sub: STR.clientKpiOpenQuotes({ n: m.openQuotes.length }),
      tone: m.openQuotes.length ? 'info' : '',
    });
  }
  return cells;
}

function clientAttention(r, m, bk) {
  const today = todayKey();
  const items = [];
  m.overdue.forEach(i => items.push({
    tone: 'bad', title: STR.clientAttnOverdue({ number: i.number }), detail: STR.clientAttnOverdueDetail({ when: relDay(i.dueDate) }),
    amount: i.totalEur, open: `invoice:${i.id}`,
  }));
  bk.unclosed.forEach(b => items.push({
    tone: 'late', title: STR.clientAttnUnclosed, detail: [bookingWhen(b), bookingServiceName(b)].filter(Boolean).join(' · '), open: `booking:${b.id}`,
  }));
  bk.upcoming.filter(b => b.status === 'PENDING').forEach(b => items.push({
    tone: 'warn', title: STR.clientAttnPendingBooking, detail: [bookingWhen(b), bookingServiceName(b)].filter(Boolean).join(' · '), open: `booking:${b.id}`,
  }));
  if (m.openWork.length) {
    items.push({
      tone: 'warn', title: STR.clientAttnUnbilled({ n: m.openWork.length }), detail: m.openWork.map(s => s.name).join(', '),
      amount: m.openWorkTotal, open: hasModule('invoices') && !r.client.archivedAt ? 'invoice-open-work' : 'tab:services',
    });
  }
  m.openQuotes.forEach(q => {
    const expired = !!q.validUntil && q.validUntil.slice(0, 10) < today;
    items.push({
      tone: expired ? 'late' : 'info',
      title: q.status === 'SENT' ? STR.clientAttnQuote({ number: q.number }) : STR.clientAttnQuoteDraft({ number: q.number }),
      detail: q.validUntil
        ? (expired ? STR.clientAttnQuoteExpired({ when: relDay(q.validUntil) }) : STR.clientValidUntil({ date: fmtDay(q.validUntil) }))
        : STR.clientCreatedOn({ date: fmtDay(q.createdAt) }),
      amount: q.totalEur, open: `quote:${q.id}`,
    });
  });
  const soon = addDayKey(today, 7);
  m.unpaid.filter(i => !isPastDue(i, today) && i.dueDate && i.dueDate.slice(0, 10) <= soon).forEach(i => items.push({
    tone: 'warn', title: STR.clientAttnDueSoon({ number: i.number }), detail: STR.clientDueWhen({ when: relDay(i.dueDate) }),
    amount: i.totalEur, open: `invoice:${i.id}`,
  }));
  return items;
}

function clientActivity(r, bk) {
  const upcoming = bk.upcoming.map(b => ({
    tone: bookingTone(b.status) || 'neutral', title: bookingServiceName(b) || STR.clientKindBooking,
    detail: `${STR.clientKindBooking} · ${bookingWhen(b)}`, pill: bookingPill(b.status), amount: bookingPrice(b), open: `booking:${b.id}`,
  }));
  const past = [];
  bk.past.forEach(b => past.push({
    at: b.startAt, tone: bookingTone(b.status) || 'muted', title: bookingServiceName(b) || STR.clientKindBooking,
    detail: `${STR.clientKindBooking} · ${bookingWhen(b)}`, pill: bookingPill(b.status), amount: bookingPrice(b), open: `booking:${b.id}`,
  }));
  r.services.forEach(s => past.push({
    at: s.performedAt || s.createdAt, tone: { OPEN: 'warn', INVOICED: 'ok' }[s.status] || 'muted', title: s.name,
    detail: [STR.clientKindService, fmtDay(s.performedAt || s.createdAt), s.bookingId ? CRM.services.fromBooking : ''].filter(Boolean).join(' · '),
    pill: servicePill(s.status), amount: s.totalEur, open: `service:${s.id}`,
  }));
  r.quotes.forEach(q => past.push({
    at: q.createdAt, tone: quoteTone(q.status) || 'accent', title: STR.clientEvtQuote({ number: q.number }),
    detail: STR.clientCreatedOn({ date: fmtDay(q.createdAt) }), pill: quotePill(q.status), amount: q.totalEur, open: `quote:${q.id}`,
  }));
  r.invoices.forEach(i => {
    const status = effectiveStatus(i);
    past.push({
      at: i.createdAt, tone: invoiceTone(status) || 'muted', title: STR.clientEvtInvoice({ number: i.number }),
      detail: STR.clientIssuedOn({ date: fmtDay(i.createdAt) }), pill: invoicePill(status), amount: i.totalEur, open: `invoice:${i.id}`,
    });
    if (i.status === 'PAID' && i.paidAt) {
      past.push({
        at: i.paidAt, tone: 'ok', title: STR.clientEvtPaid({ number: i.number }), detail: fmtDay(i.paidAt), amount: i.totalEur, open: `invoice:${i.id}`,
      });
    }
  });
  if (r.conversation) {
    past.push({
      at: r.conversation.lastMessageAt, tone: r.conversation.waiting ? 'warn' : 'info', title: STR.clientEvtChat,
      detail: r.conversation.lastPreview || '', when: relTime(r.conversation.lastMessageAt), open: 'chat',
    });
  }
  past.sort((a, b) => String(b.at || '').localeCompare(String(a.at || '')));
  return { upcoming, past: past.slice(0, 40) };
}

function clientPaneHtml(r, m, bk) {
  // An archived client gets no new documents until it is restored.
  const act = kind => (r.client.archivedAt ? null : kind);
  if (r.tab === 'bookings') {
    if (!r.bookings.length) return recordEmpty(STR.clientNoBookings, act('booking'), STR.bookingsNewForClient);
    const row = b => `<tr class="conversation-row${b.status === 'CANCELLED' ? ' is-draft' : ''}" data-record-open="booking:${escapeHTML(b.id)}">
      <td>${escapeHTML(bookingWhen(b))}</td><td class="name">${escapeHTML(bookingServiceName(b))}</td><td>${bookingPill(b.status)}</td>
      <td class="num right">${bookingPrice(b) != null ? fmtEUR(bookingPrice(b)) : ''}</td></tr>`;
    const group = (label, list) => (list.length ? `<tr class="is-day"><td colspan="4">${escapeHTML(label)}</td></tr>${list.map(row).join('')}` : '');
    return recordTable(
      [[STR.bookingsThWhen], [STR.bookingsThService], [STR.bookingsThStatus], [CRM.quotes.thTotal, 'right']],
      group(STR.clientUpcoming, bk.upcoming) + group(STR.clientPast, bk.past),
    );
  }
  if (r.tab === 'services') {
    if (!r.services.length) return recordEmpty(STR.clientNoServices, act('service'), CRM.services.addForClient);
    const t = CRM.services;
    const rows = [...r.services]
      .sort((a, b) => String(b.performedAt || b.createdAt).localeCompare(String(a.performedAt || a.createdAt)))
      .map(s => `<tr class="conversation-row${s.status === 'CANCELLED' ? ' is-draft' : s.status === 'INVOICED' ? ' is-paid' : ''}" data-record-open="service:${escapeHTML(s.id)}">
        <td class="mono">${escapeHTML(fmtDay(s.performedAt || s.createdAt))}</td>
        <td class="name">${escapeHTML(s.name)}${s.bookingId ? ` <span class="muted">· ${escapeHTML(t.fromBooking)}</span>` : ''}</td>
        <td>${servicePill(s.status)}</td><td class="num right">${fmtEUR(s.totalEur)}</td></tr>`).join('');
    const invoiceOpen = m.openWork.length && hasModule('invoices') && act('invoice')
      ? `<div class="record__pane-foot"><button class="btn btn--sm btn--accent" type="button" data-record-act="invoice-open-work">${escapeHTML(STR.clientInvoiceOpenWork({ amount: fmtEUR(m.openWorkTotal) }))}</button></div>` : '';
    return recordTable([[t.thWhen], [t.thService], [t.thStatus], [t.thTotal, 'right']], rows) + invoiceOpen;
  }
  if (r.tab === 'quotes') {
    if (!r.quotes.length) return recordEmpty(STR.clientNoQuotes, act('quote'), CRM.tabs.orcamentos.newLabel);
    const t = CRM.quotes;
    const rows = r.quotes.map(q => `<tr class="conversation-row${q.status === 'ACEITO' ? ' is-paid' : ''}" data-record-open="quote:${escapeHTML(q.id)}">
      <td class="id">${escapeHTML(q.number)}</td><td class="mono">${escapeHTML(fmtDay(q.createdAt))}</td>
      <td class="mono muted">${escapeHTML(q.validUntil ? fmtDay(q.validUntil) : '—')}</td><td>${quotePill(q.status)}</td>
      <td class="num right">${fmtEUR(q.totalEur)}</td></tr>`).join('');
    return recordTable([[t.thNumber], [STR.clientThDate], [t.thValidUntil], [t.thStatus], [t.thTotal, 'right']], rows);
  }
  if (r.tab === 'invoices') {
    if (!r.invoices.length) return recordEmpty(STR.clientNoInvoices, act('invoice'), CRM.tabs.faturas.newLabel);
    const t = CRM.invoices;
    const rows = r.invoices.map(i => {
      const status = effectiveStatus(i);
      const marker = { PAID: ' is-paid', OVERDUE: ' is-overdue', CANCELLED: ' is-draft' }[status] || '';
      return `<tr class="conversation-row${marker}" data-record-open="invoice:${escapeHTML(i.id)}">
        <td class="id">${escapeHTML(i.number)}</td><td class="mono">${escapeHTML(fmtDay(i.createdAt))}</td>
        <td class="mono muted">${escapeHTML(fmtDay(i.dueDate))}</td><td>${invoicePill(status)}</td><td class="num right">${fmtEUR(i.totalEur)}</td></tr>`;
    }).join('');
    return recordTable([[t.thNumber], [STR.clientThDate], [t.thDueDate], [t.thStatus], [t.thTotal, 'right']], rows);
  }
  const ev = clientActivity(r, bk);
  if (!ev.upcoming.length && !ev.past.length) {
    return `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.clientActivityEmpty)}</p><p class="empty__desc">${escapeHTML(STR.clientActivityEmptyDesc)}</p></div></div>`;
  }
  const group = (label, list) => (list.length ? `<li class="worklist__group">${escapeHTML(label)}</li>${list.map(recordRowHtml).join('')}` : '');
  return `<div class="panel"><ul class="worklist">${group(STR.clientUpcoming, ev.upcoming)}${group(STR.clientHistory, ev.past)}</ul></div>`;
}

function clientTabs(r) {
  const tabs = [['activity', STR.clientTabActivity, 0]];
  if (hasModule('bookings')) tabs.push(['bookings', labels.bookings, r.bookings.length]);
  if (hasModule('services')) tabs.push(['services', labels.services, r.services.length]);
  if (hasModule('quotes')) tabs.push(['quotes', labels.quotes, r.quotes.length]);
  if (hasModule('invoices')) tabs.push(['invoices', labels.invoices, r.invoices.length]);
  if (!tabs.some(([id]) => id === r.tab)) r.tab = 'activity';
  return `<div class="chip-tabs" role="tablist">${tabs.map(([id, label, n]) => `<button class="chip${id === r.tab ? ' is-on' : ''}" type="button" role="tab" aria-selected="${id === r.tab}" data-record-tab="${id}">${escapeHTML(label)}${n ? `<span class="chip__count">${n}</span>` : ''}</button>`).join('')}</div>`;
}

function renderClientRecord(focusTab = false) {
  const r = clientRecord;
  const body = r?.el;
  if (!body || !body.isConnected) return;
  const m = clientMoney(r);
  const bk = clientBookingsSplit(r);
  const acts = [
    hasModule('bookings') ? ['booking', STR.bookingsNewForClient] : null,
    hasModule('services') ? ['service', CRM.services.addForClient] : null,
    hasModule('quotes') ? ['quote', CRM.tabs.orcamentos.newLabel] : null,
    hasModule('invoices') ? ['invoice', CRM.tabs.faturas.newLabel] : null,
  ].filter(Boolean);
  body.innerHTML = `
    ${clientCardHtml(r, bk)}
    ${recordKpisHtml(clientKpiCells(m, bk))}
    ${recordAttentionHtml(clientAttention(r, m, bk))}
    ${clientTabs(r)}
    <div class="record__pane" role="tabpanel">${clientPaneHtml(r, m, bk)}</div>
    ${r.client.archivedAt ? '' : recordFootHtml(acts)}`;
  $('[data-record-edit]', body)?.addEventListener('click', () => openFromClient(() => openClientForm(r.client)));
  const setArchivedAt = archivedAt => { r.client = { ...r.client, archivedAt }; renderClientRecord(); refreshDirectory('clients'); };
  $('[data-record-remove]', body)?.addEventListener('click', () => removeDirectoryRecord('clients', r.client, {
    inUse: r.quotes.length + r.invoices.length + r.services.length + r.bookings.length > 0,
    archiveBody: STR.clientArchiveBody,
    onDeleted: () => { closeDrawer({ dismissed: true }); refreshDirectory('clients'); },
    onArchived: setArchivedAt,
  }));
  $('[data-record-restore]', body)?.addEventListener('click', () => restoreDirectoryRecord('clients', r.client, () => setArchivedAt(null)));
  $$('[data-record-tab]', body).forEach(b => b.addEventListener('click', () => { r.tab = b.dataset.recordTab; renderClientRecord(true); }));
  $$('[data-record-open]', body).forEach(el => el.addEventListener('click', () => openClientItem(el.dataset.recordOpen)));
  $$('[data-record-act]', body).forEach(b => b.addEventListener('click', () => clientAct(b.dataset.recordAct)));
  if (focusTab) $('[data-record-tab].is-on', body)?.focus();
}

const clientTrailEntry = (id, name, tab) => ({ key: `client:${id}`, label: name, open: () => openClientDrawer(id, tab) });
function openFromClient(open) {
  const c = clientRecord?.client;
  openFrom(c ? clientTrailEntry(c.id, c.name, clientRecord.tab) : null, open);
}

function openClientItem(ref) {
  const r = clientRecord;
  if (!r) return;
  const [kind, id] = ref.split(':');
  if (kind === 'chat') return openClientConversation(r.conversation);
  if (kind === 'tab') { r.tab = id; return renderClientRecord(true); }
  if (kind === 'invoice-open-work') return openFromClient(() => openInvoiceOpenWork(r.client, r.services.filter(s => s.status === 'OPEN')));
  if (kind === 'invoice') return openFromClient(() => openInvoiceDetail(id));
  if (kind === 'quote') return openFromClient(() => openQuoteDetail(id));
  if (kind === 'booking') {
    const booking = r.bookings.find(b => b.id === id);
    return booking && openFromClient(() => openBookingDetail(booking));
  }
  if (kind === 'service') {
    const service = r.services.find(s => s.id === id);
    return service && openFromClient(() => openServiceDetail(service));
  }
}

function clientAct(kind) {
  const c = clientRecord?.client;
  if (!c) return;
  if (kind === 'booking') return openFromClient(() => openBookingForm(null, { client: c }));
  if (kind === 'service') return openFromClient(() => openServiceForm(null, c.id));
  if (kind === 'quote') return openFromClient(() => openQuoteForm({ client: c }));
  if (kind === 'invoice') return openFromClient(() => openInvoiceForm({ client: c }));
  if (kind === 'invoice-open-work') return openClientItem('invoice-open-work');
}

async function openClientConversation(conversation) {
  if (!conversation) return;
  closeDrawer({ dismissed: true });
  const asset = (state.me?.tenant.channels || []).find(a => a.platform === conversation.channel);
  if (asset) rememberAsset(asset.externalId);
  state.selectedConversation = conversation.id;
  await setActive('conversations');
}

async function openClientDrawer(ref, tab) {
  const id = typeof ref === 'string' ? ref : ref?.id;
  if (!id) return;
  const known = (typeof ref === 'object' && ref) || state.clients.find(c => c.id === id) || null;
  const body = document.createElement('div');
  body.className = 'record';
  body.innerHTML = `<p class="hint">${escapeHTML(CRM.loading)}</p>`;
  const eyebrow = c => [STR.clientEyebrow, c?.number].filter(Boolean).join(' · ');
  const gen = openDrawer(known?.name || CRM.loading, body, false, { eyebrow: eyebrow(known) });
  const record = await loadClientRecord(id);
  if (gen !== drawerGen) return;
  if (!record.client) { closeDrawer({ dismissed: true }); return toast(STR.loadFailed); }
  clientRecord = { ...record, el: body, tab: tab || (clientRecord?.client?.id === id ? clientRecord.tab : 'activity') };
  $('#drawer-title').textContent = record.client.name;
  renderDrawerEyebrow(eyebrow(record.client));
  renderClientRecord();
}

// Every open row starts ticked, unless `preselect` names the ones to tick (the others stay listed).
function openInvoiceOpenWork(client, services, preselect = null) {
  const t = CRM.services;
  const form = document.createElement('form');
  form.className = 'form';
  const due = new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10);
  const rows = services.map(s => `<tr>
    <td class="check"><input type="checkbox" data-pick="${escapeHTML(s.id)}" data-eur="${Number(s.totalEur || 0)}" ${!preselect || preselect.includes(s.id) ? 'checked' : ''} aria-label="${escapeHTML(s.name)}" /></td>
    <td class="name">${escapeHTML(s.name)}</td><td class="mono muted">${escapeHTML(fmtDay(s.performedAt || s.createdAt))}</td>
    <td class="num right">${fmtEUR(s.totalEur)}</td></tr>`).join('');
  form.innerHTML = `
    <p class="hint">${escapeHTML(client.name)}</p>
    <div class="panel"><div class="tbl-wrap"><table class="tbl"><tbody>${rows}</tbody></table></div></div>
    <div class="form__row"><label class="lbl" for="ow-due">${escapeHTML(t.invoiceDue)} <span class="req">●</span></label>
      <input class="inp" id="ow-due" type="date" required value="${due}" /></div>
    <div class="actions"><button class="btn btn--primary" type="submit" id="ow-submit"></button></div>`;
  const submit = $('#ow-submit', form);
  const refresh = () => {
    const picked = $$('[data-pick]:checked', form);
    submit.textContent = STR.clientIssueInvoice({ amount: fmtEUR(picked.reduce((sum, i) => sum + Number(i.dataset.eur), 0)) });
    submit.disabled = !picked.length;
  };
  $$('[data-pick]', form).forEach(i => i.addEventListener('change', refresh));
  refresh();
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const serviceIds = $$('[data-pick]:checked', form).map(i => i.dataset.pick);
    const dueDate = $('#ow-due', form).value;
    if (!serviceIds.length) return toast(t.invoiceNeedRows);
    if (!dueDate) return toast(STR.invoiceEnterDueDate);
    submit.disabled = true;
    try {
      const invoice = await api('/app/api/crm/services/invoice', { method: 'POST', body: JSON.stringify({ clientId: client.id, serviceIds, dueDate }) });
      closeDrawer();
      toast(t.issuedFrom({ number: invoice.number }));
      if (state.fetched.invoices) state.invoices = await api('/app/api/crm/invoices').catch(() => state.invoices);
      if (state.active === 'services') await loadModule('services').catch(() => {});
      render();
    } catch { submit.disabled = false; toast(t.invoiceFailed); }
  });
  openDrawer(STR.clientInvoiceOpenWorkTitle, form);
}

function wireDuplicatePhone(form, editingId) {
  const input = $('#cf-phone', form);
  const hint = $('#cf-dup', form);
  let timer;
  let seq = 0;
  const check = async () => {
    const mine = ++seq;
    if (phoneKey(input.value).length < 9) { hint.hidden = true; return; }
    const found = await api(`/app/api/crm/clients/by-phone?phone=${encodeURIComponent(input.value)}`).catch(() => null);
    if (mine !== seq) return;
    if (!found || found.id === editingId) { hint.hidden = true; return; }
    const text = found.archivedAt ? STR.clientDuplicateArchived : STR.clientDuplicate;
    hint.innerHTML = `${escapeHTML(text({ name: found.name, number: found.number }))} <button class="btn btn--sm btn--ghost" type="button" data-open-dup>${escapeHTML(STR.clientOpenExisting)}</button>`;
    hint.hidden = false;
    $('[data-open-dup]', hint).addEventListener('click', () => openClientDrawer(found));
  };
  input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(check, 350); });
  if (input.value) check();
}

async function openClientForm(client) {
  const editing = client && client.id ? client : null;
  const field = ({ id, label, value, attrs = '', cls = 'inp', required = false }) => `<div class="form__row"><label class="lbl" for="${id}">${escapeHTML(label)}${required ? ' <span class="req">●</span>' : ''}</label>
    <input class="${cls}" id="${id}" value="${escapeHTML(value || '')}" ${attrs} /></div>`;
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${field({ id: 'cf-name', label: STR.clientFormName, value: editing?.name, required: true, attrs: `required maxlength="120" autocomplete="off" placeholder="${escapeHTML(STR.clientPhName)}"` })}
      ${field({ id: 'cf-tax', label: STR.clientFormTaxId, value: editing?.taxId, cls: 'inp inp--mono', attrs: `maxlength="32" autocomplete="off" placeholder="${escapeHTML(STR.clientPhTaxId)}"` })}
      <div class="form__row"><label class="lbl" for="cf-phone">${escapeHTML(STR.clientFormPhone)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="cf-phone" type="tel" required maxlength="40" autocomplete="off" placeholder="${escapeHTML(STR.clientPhPhone)}" value="${escapeHTML(editing?.phone || '')}" />
        <p class="hint hint--warn" id="cf-dup" hidden></p></div>
      ${field({ id: 'cf-email', label: STR.clientFormEmail, value: editing?.email, attrs: `type="email" maxlength="254" autocomplete="off" placeholder="${escapeHTML(STR.clientPhEmail)}"` })}
      <div class="form__row form__row--full"><label class="lbl" for="cf-address">${escapeHTML(STR.clientFormAddress)}</label>
        <input class="inp" id="cf-address" maxlength="300" autocomplete="off" placeholder="${escapeHTML(STR.clientPhAddress)}" value="${escapeHTML(editing?.address || '')}" /></div>
      <div class="form__row form__row--full"><label class="lbl" for="cf-notes">${escapeHTML(STR.clientFormNotes)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <textarea class="txt" id="cf-notes" maxlength="4000" placeholder="${escapeHTML(STR.clientPhNotes)}">${escapeHTML(editing?.notes || '')}</textarea>
        <p class="hint">${escapeHTML(STR.clientNotesHint)}</p></div>
    </div>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(editing ? STR.clientSaveChanges : STR.clientSave)}</button>
      ${editing ? `<button class="btn btn--ghost" type="button" data-cf-cancel>${escapeHTML(STR.cancel)}</button>` : ''}
    </div>`;
  wireDuplicatePhone(form, editing?.id);
  $('[data-cf-cancel]', form)?.addEventListener('click', () => closeDrawer());
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const val = sel => $(sel, form).value.trim();
    const payload = {
      name: val('#cf-name'), phone: val('#cf-phone'), taxId: val('#cf-tax'), email: val('#cf-email'),
      address: val('#cf-address'), notes: val('#cf-notes'),
    };
    if (!payload.name || !payload.phone) return toast(STR.clientValidate);
    if (payload.email && !EMAIL_SHAPE.test(payload.email)) return toast(STR.clientInvalidEmail);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const saved = editing
        ? await api(`/app/api/crm/clients/${encodeURIComponent(editing.id)}`, { method: 'PATCH', body: JSON.stringify(payload) })
        : await api('/app/api/crm/clients', { method: 'POST', body: JSON.stringify(payload) });
      toast(editing ? STR.clientUpdated : STR.clientCreated({ name: saved.name }));
      if (state.active === 'clients') { await loadModule('clients').catch(() => {}); render(); }
      if (editing) closeDrawer();
      else openClientDrawer(saved);
    } catch (err) {
      btn.disabled = false;
      toast(err?.code === 'invalid_email' ? STR.clientInvalidEmail
        : err?.code === 'phone_taken' ? STR.clientPhoneTaken
          : editing ? STR.clientUpdateFailed : STR.clientCreateFailed);
    }
  });
  openDrawer(editing ? STR.clientEdit : STR.clientFormTitle, form, false, { eyebrow: STR.clientEyebrow });
}

// ── Supplier and employee records ──────────────────────────────────────────────
// Same record as a client, from the other side of the money: what the tenant owes them and when.
let payeeRecord = null;
const PAYEE_KINDS = {
  supplier: {
    module: 'suppliers', path: 'suppliers', filter: 'supplierId', t: () => CRM.suppliers,
    eyebrow: () => STR.recordSupplier, since: date => STR.supplierSince({ date }), list: () => state.suppliers || [],
    edit: p => openSupplierForm(p), pay: p => openPaymentForm(p.id),
  },
  employee: {
    module: 'employees', path: 'employees', filter: 'employeeId', t: () => CRM.employees,
    eyebrow: () => STR.recordEmployee, since: date => STR.employeeSince({ date }), list: () => state.employees || [],
    edit: p => openEmployeeForm(p), pay: p => openPaymentForm(null, p.id),
  },
};
const payeeTrailEntry = (kind, p) => ({ key: `${kind}:${p.id}`, label: p.name, open: () => openPayeeDrawer(kind, p.id) });

async function openPayeeDrawer(kind, ref) {
  const cfg = PAYEE_KINDS[kind];
  const id = typeof ref === 'string' ? ref : ref?.id;
  if (!cfg || !id) return;
  const known = (typeof ref === 'object' && ref) || cfg.list().find(p => p.id === id) || null;
  const body = document.createElement('div');
  body.className = 'record';
  body.innerHTML = `<p class="hint">${escapeHTML(CRM.loading)}</p>`;
  const eyebrow = p => [cfg.eyebrow(), p?.number].filter(Boolean).join(' · ');
  const gen = openDrawer(known?.name || CRM.loading, body, false, { eyebrow: eyebrow(known) });
  const q = encodeURIComponent(id);
  const [payee, payments] = await Promise.all([
    api(`/app/api/crm/${cfg.path}/${q}`).catch(() => known),
    hasModule('payments') ? api(`/app/api/crm/payments?${cfg.filter}=${q}`).catch(() => []) : Promise.resolve([]),
  ]);
  if (gen !== drawerGen) return;
  if (!payee) { closeDrawer({ dismissed: true }); return toast(STR.loadFailed); }
  payeeRecord = { kind, payee, payments: payments || [], el: body };
  $('#drawer-title').textContent = payee.name;
  renderDrawerEyebrow(eyebrow(payee));
  renderPayeeRecord();
}

function renderPayeeRecord() {
  const r = payeeRecord;
  const body = r?.el;
  if (!body || !body.isConnected) return;
  const { kind, payee: p } = r;
  const cfg = PAYEE_KINDS[kind];
  const t = cfg.t();
  const pt = CRM.payments;
  const today = todayKey();
  const live = r.payments.filter(x => x.status !== 'CANCELLED');
  const unpaid = live.filter(x => x.status === 'PENDING' || x.status === 'OVERDUE')
    .sort((a, b) => String(a.dueDate).localeCompare(String(b.dueDate)));
  const overdue = unpaid.filter(x => isPastDue(x, today));
  const paid = live.filter(x => x.status === 'PAID');
  const next = unpaid.find(x => !isPastDue(x, today));
  const lastPaid = paid.map(x => x.paidAt).filter(Boolean).sort().pop();
  const lines = [
    `<span class="record-card__line mono">${escapeHTML(p.phone)}</span>`,
    p.address ? `<a class="record-card__line" href="${escapeHTML(mapsHref(p.address))}" target="_blank" rel="noopener">${escapeHTML(p.address)}</a>` : '',
    p.role ? `<span class="record-card__line">${escapeHTML(p.role)}</span>` : '',
  ].filter(Boolean).join('');
  const since = [
    cfg.since(fmtDay(p.createdAt)),
    live.length ? STR.payeePayments({ n: live.length }) : '',
    lastPaid ? STR.payeeLastPaid({ when: relTime(lastPaid) }) : '',
  ].filter(Boolean).join(' · ');
  const cells = !hasModule('payments') ? [] : [
    {
      label: STR.payeeKpiToPay,
      value: fmtEUR(sumBy(unpaid, 'totalEur')),
      sub: overdue.length ? STR.clientKpiOverdue({ amount: fmtEUR(sumBy(overdue, 'totalEur')) })
        : unpaid.length ? STR.payeeKpiUnpaid({ n: unpaid.length }) : STR.payeeKpiNothingDue,
      tone: overdue.length ? 'bad' : unpaid.length ? 'warn' : '',
    },
    {
      label: STR.payeeKpiNextDue,
      value: next ? fmtDayKey(next.dueDate.slice(0, 10), { day: 'numeric', month: 'short' }) : '—',
      sub: next ? `${fmtEUR(next.totalEur)} · ${next.number}` : STR.payeeKpiNoneDue,
    },
    { label: STR.payeeKpiPaid, value: fmtEUR(sumBy(paid, 'totalEur')), sub: STR.payeePayments({ n: paid.length }) },
  ];
  const soon = addDayKey(today, 7);
  const attention = [
    ...overdue.map(x => ({
      tone: 'bad', title: STR.payeeAttnOverdue({ number: x.number }), detail: dueWhenText(x.dueDate, true), amount: x.totalEur, open: `payment:${x.id}`,
    })),
    ...unpaid.filter(x => !isPastDue(x, today) && x.dueDate.slice(0, 10) <= soon).map(x => ({
      tone: 'warn', title: STR.clientAttnDueSoon({ number: x.number }), detail: dueWhenText(x.dueDate, false), amount: x.totalEur, open: `payment:${x.id}`,
    })),
  ];
  const rows = [...r.payments].sort((a, b) => String(b.dueDate).localeCompare(String(a.dueDate))).map(x => {
    const status = effectiveStatus(x);
    const marker = { PAID: ' is-paid', OVERDUE: ' is-overdue', CANCELLED: ' is-draft' }[status] || '';
    return `<tr class="conversation-row${marker}" data-record-open="payment:${escapeHTML(x.id)}">
      <td class="id">${escapeHTML(x.number)}</td><td class="mono muted">${escapeHTML(fmtDay(x.dueDate))}</td>
      <td>${paymentPill(status)}</td><td class="num right">${fmtEUR(x.totalEur)}</td></tr>`;
  }).join('');
  const payments = !hasModule('payments') ? ''
    : r.payments.length
      ? recordTable([[pt.thNumber], [pt.thDueDate], [pt.thStatus], [pt.thTotal, 'right']], rows, { title: labels.payments, count: r.payments.length })
      : recordEmpty(STR.payeeNoPayments, p.archivedAt ? null : 'payment', t.addPayment);
  body.innerHTML = `
    ${recordCardHtml({ name: p.name, lines, since, contact: contactButtons(p.phone), actions: recordRemovalButton(p), archivedAt: p.archivedAt })}
    ${recordKpisHtml(cells)}
    ${recordAttentionHtml(attention)}
    ${payments}
    ${hasModule('payments') && !p.archivedAt ? recordFootHtml([['payment', t.addPayment]]) : ''}`;
  const here = () => payeeTrailEntry(kind, p);
  $('[data-record-edit]', body)?.addEventListener('click', () => openFrom(here(), () => cfg.edit(p)));
  const setArchivedAt = archivedAt => { r.payee = { ...p, archivedAt }; renderPayeeRecord(); refreshDirectory(cfg.path); };
  $('[data-record-remove]', body)?.addEventListener('click', () => removeDirectoryRecord(cfg.path, p, {
    inUse: r.payments.length > 0,
    archiveBody: STR.payeeArchiveBody,
    onDeleted: () => { closeDrawer({ dismissed: true }); refreshDirectory(cfg.path); },
    onArchived: setArchivedAt,
  }));
  $('[data-record-restore]', body)?.addEventListener('click', () => restoreDirectoryRecord(cfg.path, p, () => setArchivedAt(null)));
  $$('[data-record-open]', body).forEach(el => el.addEventListener('click', () => {
    const id = el.dataset.recordOpen.split(':')[1];
    openFrom(here(), () => openPaymentDetail(id));
  }));
  $$('[data-record-act]', body).forEach(b => b.addEventListener('click', () => openFrom(here(), () => cfg.pay(p))));
}

async function openSupplierForm(supplier) {
  const t = CRM.suppliers;
  const editing = supplier && supplier.id ? supplier : null;
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__row"><label class="lbl" for="sf-name">${escapeHTML(t.formName)} <span class="req">●</span></label>
      <input class="inp" id="sf-name" required placeholder="${escapeHTML(t.phName)}" value="${escapeHTML(editing?.name || '')}" /></div>
    <div class="form__row"><label class="lbl" for="sf-phone">${escapeHTML(t.formPhone)} <span class="req">●</span></label>
      <input class="inp inp--mono" id="sf-phone" required placeholder="${escapeHTML(t.phPhone)}" value="${escapeHTML(editing?.phone || '')}" /></div>
    <div class="form__row"><label class="lbl" for="sf-address">${escapeHTML(t.formAddress)}</label>
      <input class="inp" id="sf-address" placeholder="${escapeHTML(t.phAddress)}" value="${escapeHTML(editing?.address || '')}" /></div>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(editing ? STR.clientSaveChanges : t.save)}</button>
      ${editing ? `<button class="btn btn--ghost" type="button" data-form-cancel>${escapeHTML(STR.cancel)}</button>` : ''}
    </div>`;
  $('[data-form-cancel]', form)?.addEventListener('click', () => closeDrawer());
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const name = $('#sf-name', form).value.trim();
    const phone = $('#sf-phone', form).value.trim();
    const address = $('#sf-address', form).value.trim() || undefined;
    if (!name || !phone) return toast(t.validate);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      if (editing) {
        await api(`/app/api/crm/suppliers/${encodeURIComponent(editing.id)}`, { method: 'PATCH', body: JSON.stringify({ name, phone, address }) });
        closeDrawer();
        await loadModule('suppliers');
        render();
        toast(t.updated);
      } else {
        const created = await api('/app/api/crm/suppliers', { method: 'POST', body: JSON.stringify({ name, phone, address }) });
        closeDrawer();
        await loadModule('suppliers');
        toast(t.created);
        if (hasModule('payments') && state.active === 'payments') return openPaymentForm(created.id);
        render();
        openPayeeDrawer('supplier', created);
      }
    } catch (err) { btn.disabled = false; toast(err?.code === 'phone_taken' ? STR.supplierPhoneTaken : t.saveFailed); }
  });
  openDrawer(editing ? t.editTitle : t.formTitle, form);
}

async function openEmployeeForm(employee) {
  const t = CRM.employees;
  const editing = employee && employee.id ? employee : null;
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__row"><label class="lbl" for="ef-name">${escapeHTML(t.formName)} <span class="req">●</span></label>
      <input class="inp" id="ef-name" required placeholder="${escapeHTML(t.phName)}" value="${escapeHTML(editing?.name || '')}" /></div>
    <div class="form__row"><label class="lbl" for="ef-phone">${escapeHTML(t.formPhone)} <span class="req">●</span></label>
      <input class="inp inp--mono" id="ef-phone" required placeholder="${escapeHTML(t.phPhone)}" value="${escapeHTML(editing?.phone || '')}" /></div>
    <div class="form__row"><label class="lbl" for="ef-role">${escapeHTML(t.formRole)}</label>
      <input class="inp" id="ef-role" placeholder="${escapeHTML(t.phRole)}" value="${escapeHTML(editing?.role || '')}" /></div>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(editing ? STR.clientSaveChanges : t.save)}</button>
      ${editing ? `<button class="btn btn--ghost" type="button" data-form-cancel>${escapeHTML(STR.cancel)}</button>` : ''}
    </div>`;
  $('[data-form-cancel]', form)?.addEventListener('click', () => closeDrawer());
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const name = $('#ef-name', form).value.trim();
    const phone = $('#ef-phone', form).value.trim();
    const role = $('#ef-role', form).value.trim() || undefined;
    if (!name || !phone) return toast(t.validate);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const saved = editing
        ? await api(`/app/api/crm/employees/${encodeURIComponent(editing.id)}`, { method: 'PATCH', body: JSON.stringify({ name, phone, role }) })
        : await api('/app/api/crm/employees', { method: 'POST', body: JSON.stringify({ name, phone, role }) });
      closeDrawer();
      await loadModule('employees');
      render();
      toast(editing ? t.updated : t.created);
      if (!editing) openPayeeDrawer('employee', saved);
    } catch (err) { btn.disabled = false; toast(err?.code === 'phone_taken' ? STR.employeePhoneTaken : t.saveFailed); }
  });
  openDrawer(editing ? t.editTitle : t.formTitle, form);
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
      ${hasModule('bookings') ? `<div class="form__row form__row--full" data-booking-fields ${typeValue === 'service' ? '' : 'hidden'}>
        <label class="form__check"><input type="checkbox" id="cat-bookable" ${editing?.bookable ? 'checked' : ''} /> ${escapeHTML(t.bookableLabel)}</label>
        <p class="hint">${escapeHTML(t.bookableHint)}</p></div>
      <div class="form__row" data-booking-fields ${typeValue === 'service' ? '' : 'hidden'}><label class="lbl" for="cat-duration">${escapeHTML(t.durationLabel)}</label>
        <input class="inp inp--mono" id="cat-duration" type="number" min="5" step="5" value="${editing?.durationMinutes ?? 30}" /></div>` : ''}
    </div>
    ${editing ? `<p class="hint">${escapeHTML(t.editingHint({ id: editing.id }))}</p>` : ''}
    <button class="btn btn--primary" type="submit">${escapeHTML(editing ? t.saveChanges : t.createItem)}</button>`;
  const idEl = $('#cat-id', form), descEl = $('#cat-desc', form), typeEl = $('#cat-type', form);
  typeEl.addEventListener('change', () => $$('[data-booking-fields]', form).forEach(row => { row.hidden = typeEl.value !== 'service'; }));
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
    const booking = $('#cat-bookable', form)
      ? { bookable: type === 'service' && $('#cat-bookable', form).checked, durationMinutes: Math.max(5, Number($('#cat-duration', form).value || 30)) }
      : {};
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const path = editing ? `/app/api/crm/standard-items/${encodeURIComponent(id)}` : '/app/api/crm/standard-items';
      await api(path, { method: 'POST', body: JSON.stringify({ id, type, category, description, unit, defaultUnitPriceEur, ...booking }) });
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
function clientSelect(clients, selectedId = '') {
  const list = [...clients].sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), uiLocale(), { sensitivity: 'base' }));
  return `<div class="form__row"><label class="lbl" for="f-client">${escapeHTML(STR.lineClient)} <span class="req">●</span></label>
    <select class="sel" id="f-client" required><option value="">${escapeHTML(STR.lineChooseClient)}</option>
    ${list.map(c => `<option value="${escapeHTML(c.id)}"${c.id === selectedId ? ' selected' : ''}>${escapeHTML(c.name)}</option>`).join('')}</select></div>`;
}
// A form opened from a client record keeps that client even if the directory list doesn't have it yet.
const withClient = (clients, client) => (client && !clients.some(c => c.id === client.id) ? [client, ...clients] : clients);

function supplierSelect(suppliers, selectedId = '') {
  const t = CRM.payments;
  return `<div class="form__row" id="payee-supplier"><label class="lbl" for="f-supplier">${escapeHTML(t.thSupplier)} <span class="req">●</span></label>
    <select class="sel" id="f-supplier"><option value="">${escapeHTML(t.chooseSupplierEmpty)}</option>
    ${suppliers.map(s => `<option value="${escapeHTML(s.id)}" ${s.id === selectedId ? 'selected' : ''}>${escapeHTML(s.name)}</option>`).join('')}</select></div>`;
}

function employeeSelect(employees, selectedId = '') {
  const t = CRM.payments;
  return `<div class="form__row" id="payee-employee"><label class="lbl" for="f-employee">${escapeHTML(t.thEmployee)} <span class="req">●</span></label>
    <select class="sel" id="f-employee"><option value="">${escapeHTML(t.chooseEmployeeEmpty)}</option>
    ${employees.map(e => `<option value="${escapeHTML(e.id)}" ${e.id === selectedId ? 'selected' : ''}>${escapeHTML(e.name)}</option>`).join('')}</select></div>`;
}

function payeeName(payment) {
  return payment.employeeName || payment.supplierName || '';
}

function payeeId(payment) {
  return payment.employeeId || payment.supplierId || '';
}

function generalPaymentFields() {
  const t = CRM.payments;
  return `<div id="payment-general" class="form__grid">
    <div class="form__row"><label class="lbl" for="p-amount">${escapeHTML(t.amount)} <span class="req">●</span></label>
      <input class="inp inp--mono inp--right" id="p-amount" type="number" min="0" step="0.01" placeholder="0.00" /></div>
    <div class="form__row"><label class="lbl" for="p-desc">${escapeHTML(t.generalEntry)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
      <input class="inp" id="p-desc" value="${escapeHTML(t.generalEntryValue)}" /></div>
  </div>`;
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

async function openQuoteForm(opts = {}) {
  let clients, catalog;
  try {
    [clients, catalog] = await Promise.all([
      api('/app/api/crm/clients'),
      api('/app/api/crm/standard-items').catch(() => []),
    ]);
  } catch { return toast(STR.quoteCreateFailed); }
  const li = lineItemsField(catalog);
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${clientSelect(withClient(clients, opts.client), opts.client?.id)}
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

async function openInvoiceForm(opts = {}) {
  let clients, catalog;
  try {
    [clients, catalog] = await Promise.all([
      api('/app/api/crm/clients'),
      api('/app/api/crm/standard-items').catch(() => []),
    ]);
  } catch { return toast(STR.invoiceCreateFailed); }
  const li = lineItemsField(catalog);
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${clientSelect(withClient(clients, opts.client), opts.client?.id)}
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

async function openPaymentForm(presetSupplierId, presetEmployeeId) {
  const t = CRM.payments;
  const employeesOn = hasModule('employees');
  let suppliers, employees, catalog;
  try {
    suppliers = await api('/app/api/crm/suppliers');
    employees = employeesOn ? await api('/app/api/crm/employees').catch(() => state.employees || []) : [];
    catalog = hasModule('catalog') ? await api('/app/api/crm/standard-items').catch(() => state.catalog || []) : (state.catalog || []);
  } catch { return toast(t.saveFailed); }
  state.suppliers = suppliers;
  if (employeesOn) state.employees = employees;
  if (!suppliers.length && !employees.length) {
    toast(employeesOn ? t.needPayee : t.needSupplier);
    return openSupplierForm();
  }
  let payee = presetEmployeeId ? 'employee' : 'supplier';
  if (!presetSupplierId && !presetEmployeeId && !suppliers.length && employees.length) payee = 'employee';
  const selectedSupplier = presetSupplierId || (!presetEmployeeId ? state.filterPaymentSupplier || '' : '');
  const li = lineItemsField(catalog);
  const due = new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10);
  const kindSwitch = employeesOn
    ? `<div class="form__row form__row--full"><span class="lbl">${escapeHTML(t.payee)}</span><div class="actions">
        <button class="chip ${payee === 'supplier' ? 'is-on' : ''}" type="button" data-payee="supplier">${escapeHTML(t.paySupplier)}</button>
        <button class="chip ${payee === 'employee' ? 'is-on' : ''}" type="button" data-payee="employee">${escapeHTML(t.payEmployee)}</button>
      </div></div>`
    : '';
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${kindSwitch}
      ${supplierSelect(suppliers, selectedSupplier)}
      ${employeesOn ? employeeSelect(employees, presetEmployeeId || '') : ''}
      <div class="form__row"><label class="lbl" for="p-due">${escapeHTML(t.thDueDate)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="p-due" type="date" required value="${due}" /></div>
    </div>
    <div id="payment-lines">${li.html}</div>
    ${employeesOn ? generalPaymentFields() : ''}
    <div class="form__row form__row--full"><label class="lbl" for="p-notes">${escapeHTML(t.notes)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
      <textarea class="txt" id="p-notes" placeholder="${escapeHTML(t.notesPh)}"></textarea></div>
    <button class="btn btn--primary" type="submit">${escapeHTML(t.save)}</button>`;
  const showPayee = kind => {
    payee = kind;
    const supplierRow = $('#payee-supplier', form);
    const employeeRow = $('#payee-employee', form);
    const lines = $('#payment-lines', form);
    const general = $('#payment-general', form);
    if (supplierRow) supplierRow.hidden = kind !== 'supplier';
    if (employeeRow) employeeRow.hidden = kind !== 'employee';
    if (lines) lines.hidden = kind === 'employee';
    if (general) general.hidden = kind !== 'employee';
    $$('[data-payee]', form).forEach(btn => btn.classList.toggle('is-on', btn.dataset.payee === kind));
  };
  showPayee(payee);
  $$('[data-payee]', form).forEach(btn => btn.addEventListener('click', () => {
    if (btn.dataset.payee === 'supplier' && !suppliers.length) return toast(t.needSupplier);
    if (btn.dataset.payee === 'employee' && !employees.length) {
      toast(t.needEmployee);
      return openEmployeeForm();
    }
    showPayee(btn.dataset.payee);
  }));
  li.wire(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const dueDate = $('#p-due', form).value;
    const supplierId = $('#f-supplier', form)?.value || '';
    const employeeId = $('#f-employee', form)?.value || '';
    if (payee === 'employee') {
      if (!employeeId) return toast(t.chooseEmployee);
    } else if (!supplierId) return toast(t.chooseSupplier);
    if (!dueDate) return toast(t.enterDueDate);
    let items;
    if (payee === 'employee') {
      const amount = Number($('#p-amount', form).value || 0);
      if (!(amount > 0)) return toast(t.enterAmount);
      const description = $('#p-desc', form).value.trim() || t.generalEntryValue;
      items = [{ description, quantity: 1, unit: '', unitPriceEur: amount }];
    } else {
      items = li.collect(form);
      if (!items.length) return toast(t.addLine);
    }
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    const body = payee === 'employee'
      ? { employeeId, items, dueDate, notes: $('#p-notes', form).value.trim() || null }
      : { supplierId, items, dueDate, notes: $('#p-notes', form).value.trim() || null };
    try {
      await api('/app/api/crm/payments', { method: 'POST', body: JSON.stringify(body) });
      closeDrawer();
      await loadModule('payments');
      render();
      toast(t.created);
    } catch { btn.disabled = false; toast(t.saveFailed); }
  });
  openDrawer(t.formTitle, form, true);
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

// With `link`, the name opens that client's or payee's record (wire [data-detail-link]).
function detailHead(clientName, pillHtml, totalEur, tone, link = false) {
  const name = escapeHTML(clientName || '');
  return `<div class="detail__head">
    ${link
      ? `<button class="detail__client detail__client--link" type="button" data-detail-link aria-label="${escapeHTML(STR.detailOpenRecordAria({ name: clientName || '' }))}">${name}</button>`
      : `<div class="detail__client">${name}</div>`}
    <div class="detail__amount">${pillHtml}<span class="detail__figure${tone ? ` detail__figure--${tone}` : ''}">${fmtEUR(totalEur)}</span></div>
  </div>`;
}
function detailMeta(items) {
  const rows = items.filter(Boolean);
  if (!rows.length) return '';
  return `<div class="detail__meta">${rows.map(m => `<div class="detail__meta-item"><span class="detail__meta-label">${escapeHTML(m.label)}</span><span class="detail__meta-value">${escapeHTML(m.value)}</span></div>`).join('')}</div>`;
}
function itemsTable(items) {
  if (!items || !items.length) return '';
  const rows = items.map(it => `<tr><td class="name">${escapeHTML(it.description)}</td><td class="num muted">${it.quantity}${it.unit ? ` ${escapeHTML(it.unit)}` : ''}</td><td class="num muted">${fmtEUR(it.unitPriceEur)}</td><td class="num">${fmtEUR(it.quantity * it.unitPriceEur)}</td></tr>`).join('');
  return `<div class="panel"><div class="tbl-wrap"><table class="tbl"><thead><tr><th>${escapeHTML(STR.lineColDesc)}</th><th class="right">${escapeHTML(STR.lineColQty)}</th><th class="right">${escapeHTML(STR.lineColPrice)}</th><th class="right">${escapeHTML(STR.lineTotal)}</th></tr></thead><tbody>${rows}</tbody></table></div></div>`;
}

async function openQuoteDetail(id) {
  let quote = state.quotes.find(q => q.id === id);
  try { quote = await api(`/app/api/crm/quotes/${encodeURIComponent(id)}`); }
  catch { /* keep list row */ }
  if (!quote) return;
  // Converting marks the quote accepted; its invoice is the one that points back at it.
  const invoice = hasModule('invoices') && quote.status === 'ACEITO'
    ? (await api(`/app/api/crm/invoices?clientId=${encodeURIComponent(quote.clientId)}`).catch(() => [])).find(i => i.quoteId === quote.id) || null
    : null;
  const here = { key: `quote:${quote.id}`, label: quote.number, open: () => openQuoteDetail(quote.id) };
  const isOpen = quote.status === 'PENDENTE' || quote.status === 'SENT';
  const expired = !!quote.validUntil && quote.validUntil.slice(0, 10) < todayKey();
  const validity = quote.validUntil && isOpen
    ? `${fmtDay(quote.validUntil)} · ${expired ? STR.clientAttnQuoteExpired({ when: relDay(quote.validUntil) }) : STR.quoteExpiresWhen({ when: relDay(quote.validUntil) })}`
    : fmtDay(quote.validUntil);
  const form = document.createElement('div');
  form.className = 'form';
  const canSend = quote.status === 'PENDENTE';
  const canAccept = isOpen;
  const canConvert = hasModule('invoices') && quote.status !== 'CANCELLED' && !invoice;
  const clientLink = hasModule('clients') && !!quote.clientId && !linksBackTo(`client:${quote.clientId}`);
  form.innerHTML = `
    ${detailHead(quote.clientName, quotePill(quote.status), quote.totalEur, quoteTone(quote.status), clientLink)}
    ${detailMeta([
      { label: STR.detailCreated, value: fmtDay(quote.createdAt) },
      quote.validUntil ? { label: CRM.quotes.thValidUntil, value: validity } : null,
      invoice ? { label: STR.detailInvoice, value: invoice.number } : null,
    ])}
    ${itemsTable(quote.items)}
    ${quote.notes ? `<p class="hint">${escapeHTML(quote.notes)}</p>` : ''}
    ${canConvert ? `<div class="form__row"><label class="lbl" for="q-due">${escapeHTML(STR.quoteConvertDue)}</label>
      <input class="inp" id="q-due" type="date" value="${new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10)}" /></div>` : ''}
    <div class="detail__foot">
      ${canSend ? `<button class="btn btn--sm" type="button" data-q-status="SENT">${escapeHTML(STR.quoteMarkSent)}</button>` : ''}
      ${canAccept ? `<button class="btn btn--sm" type="button" data-q-status="ACEITO">${escapeHTML(STR.quoteAccept)}</button>` : ''}
      ${canConvert ? `<button class="btn btn--sm btn--primary" type="button" id="q-convert">${escapeHTML(STR.quoteConvert)}</button>` : ''}
      ${pdfButton(quote.id, 'quotes', quote.hasPdf, quote.number)}
      ${invoice && !linksBackTo(`invoice:${invoice.id}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-open-invoice>${escapeHTML(STR.detailOpenDoc({ number: invoice.number }))}</button>` : ''}
    </div>`;
  $('[data-detail-link]', form)?.addEventListener('click', () => openFrom(here, () => openClientDrawer(quote.clientId)));
  $('[data-open-invoice]', form)?.addEventListener('click', () => openFrom(here, () => openInvoiceDetail(invoice.id)));
  form.querySelectorAll('[data-q-status]').forEach(b => b.addEventListener('click', async () => {
    b.disabled = true;
    try {
      await api(`/app/api/crm/quotes/${encodeURIComponent(id)}`, { method: 'PATCH', body: JSON.stringify({ status: b.dataset.qStatus }) });
      closeDrawer();
      await loadModule('quotes');
      render();
      toast(STR.quoteStatusUpdated);
    } catch { b.disabled = false; toast(STR.loadFailed); }
  }));
  $('#q-convert', form)?.addEventListener('click', async e => {
    const dueDate = $('#q-due', form).value;
    if (!dueDate) return toast(STR.invoiceEnterDueDate);
    const btn = e.currentTarget;
    btn.disabled = true;
    try {
      await api(`/app/api/crm/quotes/${encodeURIComponent(id)}/invoice`, { method: 'POST', body: JSON.stringify({ dueDate }) });
      closeDrawer();
      await loadModule('quotes');
      if (hasModule('invoices')) state.invoices = await api('/app/api/crm/invoices').catch(() => state.invoices);
      render();
      toast(STR.quoteConverted);
    } catch (err) {
      btn.disabled = false;
      toast(err?.code === 'already_invoiced' ? STR.quoteAlreadyInvoiced : STR.quoteConvertFailed);
    }
  });
  wirePdfButtons(form);
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
  const period = state.filterInvoicePeriod || '';
  const listed = state.invoices
    .filter(inv => !state.filterInvoiceStatus || inv.status === state.filterInvoiceStatus)
    .filter(inv => !q || `${inv.number} ${inv.clientName || ''} ${invoiceStatusLabel(inv.status)}`.toLowerCase().includes(q));
  const invoiceGroups = period ? groupByPeriod(listed, period, inv => inv.createdAt || inv.dueDate) : [];
  const nowKey = period ? (state.filterInvoicePeriodKey || currentPeriodKey(period)) : '';
  const nowRoll = invoiceRollup(groupItems(invoiceGroups, nowKey));
  const prevKey = period ? shiftPeriodKey(nowKey, period, -1) : '';
  const prevExists = invoiceGroups.some(([key]) => key === prevKey);
  const delta = prevExists ? periodDelta(nowRoll.total, invoiceRollup(groupItems(invoiceGroups, prevKey)).total) : { text: '', trend: '' };
  const allRoll = invoiceRollup(listed);
  const summaryRows = period && invoiceGroups.length
    ? `<tr class="is-total"><td class="name">${escapeHTML(t.thTotal)}</td><td class="num">${allRoll.count}</td><td class="num">${fmtEUR(allRoll.paid)}</td><td class="num">${fmtEUR(allRoll.pending)}</td><td class="num">${fmtEUR(allRoll.overdue)}</td><td class="num">${fmtEUR(allRoll.total)}</td></tr>`
      + invoiceGroups.map(([key, items]) => {
        const roll = invoiceRollup(items);
        return `<tr class="${key === nowKey ? 'is-current' : ''}"><td class="name">${escapeHTML(periodLabel(key, period))}</td><td class="num">${roll.count}</td><td class="num">${fmtEUR(roll.paid)}</td><td class="num">${fmtEUR(roll.pending)}</td><td class="num">${fmtEUR(roll.overdue)}</td><td class="num">${fmtEUR(roll.total)}</td></tr>`;
      }).join('')
    : '';
  const views = periodChips(period, 'data-inv-period', { '': t.viewList, week: t.byWeek, month: t.byMonth });
  const tools = `<button class="chip ${!state.filterInvoiceStatus ? 'is-on' : ''}" data-filter-inv="">${escapeHTML(t.filterAll)}</button>`
    + INVOICE_STATUSES.map(s => `<button class="chip ${state.filterInvoiceStatus === s ? 'is-on' : ''}" data-filter-inv="${s}">${escapeHTML(invoiceStatusLabel(s))}</button>`).join('');
  const summaryHead = `<tr><th>${escapeHTML(t.thPeriod)}</th><th class="right">${escapeHTML(t.thCount)}</th><th class="right">${escapeHTML(t.paid)}</th><th class="right">${escapeHTML(t.pending)}</th><th class="right">${escapeHTML(t.overdue)}</th><th class="right">${escapeHTML(t.thTotal)}</th></tr>`;
  const listHead = `<tr><th>${escapeHTML(t.thNumber)}</th><th>${escapeHTML(t.thClient)}</th><th>${escapeHTML(t.thStatus)}</th><th>${escapeHTML(t.thDueDate)}</th><th class="right">${escapeHTML(t.thTotal)}</th><th class="right">${escapeHTML(t.thPdfActions)}</th></tr>`;
  const invoiceNav = periodNav(nowKey, period, 'data-inv-period-nav', t);
  const invoiceStats = period
    ? [
      { label: t.paid, value: fmtEUR(nowRoll.paid) },
      { label: t.pending, value: fmtEUR(nowRoll.pending) },
      { label: t.overdue, value: fmtEUR(nowRoll.overdue) },
      { label: t.thTotal, value: fmtEUR(nowRoll.total), hint: delta.text ? t.vsPrev({ delta: delta.text }) : '', trend: delta.trend },
    ]
    : [
      { label: t.paid, value: fmtEUR(paid) },
      { label: t.pending, value: fmtEUR(pending) },
      { label: t.overdue, value: fmtEUR(overdue) },
    ];
  root.innerHTML = hero(labels.invoices, CRM.tabs.faturas.desc, statCards(invoiceStats), invoiceNav) + crmPanel({
    title: period === 'week' ? t.summaryWeek : period === 'month' ? t.summaryMonth : t.documents,
    tag: period ? invoiceGroups.length : listed.length,
    views,
    tools,
    head: period ? summaryHead : listHead,
    rows: period ? summaryRows : rows,
    empty: period ? t.summaryEmpty : t.emptyTitle,
    emptyDesc: period ? t.summaryEmptyDesc : t.emptyDesc,
  });
  wirePdfButtons(root);
  $$('[data-inv-period]', root).forEach(btn => btn.addEventListener('click', () => { state.filterInvoicePeriod = btn.dataset.invPeriod; state.filterInvoicePeriodKey = ''; render(); }));
  $$('[data-inv-period-nav]', root).forEach(btn => btn.addEventListener('click', () => {
    const dir = btn.dataset.invPeriodNav;
    state.filterInvoicePeriodKey = dir === 'current' ? '' : shiftPeriodKey(nowKey, period, dir === 'prev' ? -1 : 1);
    render();
  }));
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

function renderFinanceiro(root) {
  const t = CRM.financeiro;
  const period = state.filterFinanceiroPeriod || '';
  const typeFilter = state.filterFinanceiroType || '';
  const paidInvoices = hasModule('invoices') ? state.invoices.filter(i => i.status === 'PAID' && i.paidAt) : [];
  const paidPayments = hasModule('payments') ? state.payments.filter(p => p.status === 'PAID' && p.paidAt) : [];
  const receivedAll = sumEur(paidInvoices);
  const spentAll = sumEur(paidPayments);

  const ledgerAll = [
    ...paidInvoices.map(i => ({ kind: 'in', date: i.paidAt, amount: i.totalEur, who: i.clientName || '—', ref: i.number })),
    ...paidPayments.map(p => ({ kind: 'out', date: p.paidAt, amount: p.totalEur, who: p.supplierName || p.employeeName || '—', ref: p.number })),
  ].sort((a, b) => String(b.date || '').localeCompare(String(a.date || '')));
  const ledger = ledgerAll.filter(m => !typeFilter || m.kind === typeFilter);

  const inGroups = period ? groupByPeriod(paidInvoices, period, i => i.paidAt) : [];
  const outGroups = period ? groupByPeriod(paidPayments, period, p => p.paidAt) : [];
  const periodKeys = [...new Set([...inGroups.map(([k]) => k), ...outGroups.map(([k]) => k)])].sort((a, b) => b.localeCompare(a));
  const rollupFor = key => {
    const received = sumEur(groupItems(inGroups, key));
    const spent = sumEur(groupItems(outGroups, key));
    return { received, spent, net: received - spent };
  };
  const nowKey = period ? (state.filterFinanceiroPeriodKey || currentPeriodKey(period)) : '';
  const nowRoll = period ? rollupFor(nowKey) : null;
  const prevKey = period ? shiftPeriodKey(nowKey, period, -1) : '';
  const delta = period && periodKeys.includes(prevKey) ? periodDelta(nowRoll.net, rollupFor(prevKey).net) : { text: '', trend: '' };

  const views = periodChips(period, 'data-fin-period', { '': t.viewList, week: t.byWeek, month: t.byMonth });
  const tools = period ? '' : `<button class="chip ${!typeFilter ? 'is-on' : ''}" data-filter-fin="">${escapeHTML(t.filterAll)}</button>`
    + `<button class="chip ${typeFilter === 'in' ? 'is-on' : ''}" data-filter-fin="in">${escapeHTML(t.received)}</button>`
    + `<button class="chip ${typeFilter === 'out' ? 'is-on' : ''}" data-filter-fin="out">${escapeHTML(t.spent)}</button>`;

  const financeNav = periodNav(nowKey, period, 'data-fin-period-nav', t);
  const financeStats = period
    ? [
      { label: t.received, value: fmtEUR(nowRoll.received) },
      { label: t.spent, value: fmtEUR(nowRoll.spent) },
      { label: t.net, value: fmtEUR(nowRoll.net), hint: delta.text ? t.vsPrev({ delta: delta.text }) : '', trend: delta.trend },
    ]
    : [
      { label: t.received, value: fmtEUR(receivedAll) },
      { label: t.spent, value: fmtEUR(spentAll) },
      { label: t.net, value: fmtEUR(receivedAll - spentAll) },
    ];

  const summaryHead = `<tr><th>${escapeHTML(t.thPeriod)}</th><th class="right">${escapeHTML(t.received)}</th><th class="right">${escapeHTML(t.spent)}</th><th class="right">${escapeHTML(t.net)}</th></tr>`;
  const listHead = `<tr><th>${escapeHTML(t.thWhen)}</th><th>${escapeHTML(t.thWho)}</th><th>${escapeHTML(t.thRef)}</th><th class="right">${escapeHTML(t.thTotal)}</th></tr>`;
  const summaryRows = period && periodKeys.length
    ? `<tr class="is-total"><td class="name">${escapeHTML(t.thTotal)}</td><td class="num">${fmtEUR(receivedAll)}</td><td class="num">${fmtEUR(spentAll)}</td><td class="num">${fmtEUR(receivedAll - spentAll)}</td></tr>`
      + periodKeys.map(key => {
        const roll = rollupFor(key);
        return `<tr class="${key === nowKey ? 'is-current' : ''}"><td class="name">${escapeHTML(periodLabel(key, period))}</td><td class="num">${fmtEUR(roll.received)}</td><td class="num">${fmtEUR(roll.spent)}</td><td class="num">${fmtEUR(roll.net)}</td></tr>`;
      }).join('')
    : '';
  const ledgerRows = ledger.map(m => `<tr class="conversation-row" data-fin-go="${m.kind === 'in' ? 'invoices' : 'payments'}">
    <td class="mono muted">${escapeHTML(fmtDay(m.date))} <span class="pill ${m.kind === 'in' ? 'pill--ok' : 'pill--bad'}">${escapeHTML(m.kind === 'in' ? t.received : t.spent)}</span></td>
    <td class="name">${escapeHTML(m.who)}</td>
    <td class="mono muted">${escapeHTML(m.ref)}</td>
    <td class="num">${m.kind === 'out' ? '−' : ''}${fmtEUR(m.amount)}</td>
  </tr>`).join('');

  root.innerHTML = hero(labels.financeiro, CRM.tabs.financeiro.desc, statCards(financeStats), financeNav) + crmPanel({
    title: period === 'week' ? t.summaryWeek : period === 'month' ? t.summaryMonth : t.ledger,
    tag: period ? periodKeys.length : ledger.length,
    views,
    tools,
    head: period ? summaryHead : listHead,
    rows: period ? summaryRows : ledgerRows,
    empty: period ? t.summaryEmpty : t.emptyTitle,
    emptyDesc: period ? t.summaryEmptyDesc : t.emptyDesc,
  });
  $$('[data-fin-period]', root).forEach(btn => btn.addEventListener('click', () => { state.filterFinanceiroPeriod = btn.dataset.finPeriod; state.filterFinanceiroPeriodKey = ''; render(); }));
  $$('[data-fin-period-nav]', root).forEach(btn => btn.addEventListener('click', () => {
    const dir = btn.dataset.finPeriodNav;
    state.filterFinanceiroPeriodKey = dir === 'current' ? '' : shiftPeriodKey(nowKey, period, dir === 'prev' ? -1 : 1);
    render();
  }));
  $$('[data-filter-fin]', root).forEach(btn => btn.addEventListener('click', () => { state.filterFinanceiroType = btn.dataset.filterFin; render(); }));
  $$('[data-fin-go]', root).forEach(tr => tr.addEventListener('click', () => setActive(tr.dataset.finGo)));
}

function renderPayments(root) {
  const t = CRM.payments;
  const q = state.search.toLowerCase();
  const all = state.payments || [];
  const names = new Map();
  for (const s of state.suppliers || []) names.set(s.id, s.name);
  for (const e of state.employees || []) names.set(e.id, e.name);
  for (const p of all) {
    const id = payeeId(p);
    if (id && !names.has(id)) names.set(id, payeeName(p) || id);
  }
  if (state.filterPaymentSupplier && !names.has(state.filterPaymentSupplier)) state.filterPaymentSupplier = '';
  const supplierId = state.filterPaymentSupplier || '';
  const scoped = supplierId ? all.filter(p => payeeId(p) === supplierId) : all;
  const rows = scoped
    .filter(p => !state.filterPaymentStatus || p.status === state.filterPaymentStatus)
    .filter(p => !q || `${p.number} ${payeeName(p)} ${paymentStatusLabel(p.status)}`.toLowerCase().includes(q))
    .map(p => {
      const canMarkPaid = p.status === 'PENDING' || p.status === 'OVERDUE';
      const rowClass = p.status === 'PAID' ? 'is-paid' : p.status === 'OVERDUE' ? 'is-overdue' : p.status === 'CANCELLED' ? 'is-draft' : '';
      return `<tr class="conversation-row ${rowClass}" data-payment="${escapeHTML(p.id)}">
        <td class="id">${escapeHTML(p.number)}</td>
        <td class="name">${escapeHTML(payeeName(p))}</td>
        <td>${paymentPill(p.status)}</td>
        <td class="mono muted">${fmtDay(p.dueDate)}</td>
        <td class="num">${fmtEUR(p.totalEur)}</td>
        <td class="right"><div class="actions">
          ${canMarkPaid ? `<button class="btn btn--sm btn--accent" type="button" data-mark-payment="${escapeHTML(p.id)}">${escapeHTML(t.markPaid)}</button>` : ''}
        </div></td>
      </tr>`;
    }).join('');
  const paid = scoped.filter(p => p.status === 'PAID').reduce((sum, p) => sum + Number(p.totalEur || 0), 0);
  const pending = scoped.filter(p => p.status === 'PENDING').reduce((sum, p) => sum + Number(p.totalEur || 0), 0);
  const overdue = scoped.filter(p => p.status === 'OVERDUE').reduce((sum, p) => sum + Number(p.totalEur || 0), 0);
  const supplierOptions = [...names.entries()].sort((a, b) => String(a[1]).localeCompare(String(b[1]), uiLocale()));
  const supplierFilter = supplierOptions.length
    ? `<select class="sel" data-filter-pay-supplier aria-label="${escapeHTML(t.thPayee)}"><option value="">${escapeHTML(t.filterPayeeAll)}</option>${supplierOptions.map(([id, name]) => `<option value="${escapeHTML(id)}" ${id === supplierId ? 'selected' : ''}>${escapeHTML(name)}</option>`).join('')}</select>`
    : '';
  const tools = supplierFilter
    + `<button class="chip ${!state.filterPaymentStatus ? 'is-on' : ''}" data-filter-pay="">${escapeHTML(t.filterAll)}</button>`
    + INVOICE_STATUSES.map(s => `<button class="chip ${state.filterPaymentStatus === s ? 'is-on' : ''}" data-filter-pay="${s}">${escapeHTML(paymentStatusLabel(s))}</button>`).join('');
  const filtered = Boolean(supplierId || state.filterPaymentStatus || q);
  root.innerHTML = hero(labels.payments, CRM.tabs.pagamentos.desc, statCards([
    { label: t.paid, value: fmtEUR(paid) },
    { label: t.pending, value: fmtEUR(pending) },
    { label: t.overdue, value: fmtEUR(overdue) },
  ])) + crmPanel({
    title: t.ledger,
    tag: scoped.filter(p => !state.filterPaymentStatus || p.status === state.filterPaymentStatus).length,
    tools,
    head: `<tr><th>${escapeHTML(t.thNumber)}</th><th>${escapeHTML(t.thPayee)}</th><th>${escapeHTML(t.thStatus)}</th><th>${escapeHTML(t.thDueDate)}</th><th class="right">${escapeHTML(t.thTotal)}</th><th></th></tr>`,
    rows,
    empty: filtered ? t.emptyFiltered : t.emptyTitle,
    emptyDesc: filtered ? t.emptyFilteredDesc : t.emptyDesc,
  });
  $$('[data-filter-pay]', root).forEach(btn => btn.addEventListener('click', () => { state.filterPaymentStatus = btn.dataset.filterPay; render(); }));
  $('[data-filter-pay-supplier]', root)?.addEventListener('change', e => { state.filterPaymentSupplier = e.target.value; render(); });
  $$('[data-mark-payment]', root).forEach(btn => btn.addEventListener('click', e => {
    e.stopPropagation();
    markPaymentPaid(btn.dataset.markPayment);
  }));
  $$('[data-payment]', root).forEach(r => r.addEventListener('click', e => {
    if (e.target.closest('[data-mark-payment]')) return;
    openPaymentDetail(r.dataset.payment);
  }));
}

async function markInvoicePaid(id, number = state.invoices.find(i => i.id === id)?.number || '') {
  const ok = await confirmDialog({
    title: STR.markPaidConfirmTitle,
    body: STR.markPaidConfirmBody({ number }),
    okLabel: STR.markPaid,
    danger: false,
  });
  if (!ok) return;
  try {
    await api(`/app/api/crm/invoices/${encodeURIComponent(id)}/paid`, { method: 'PATCH' });
    closeDrawer();
    await loadModule('invoices');
    render();
    toast(STR.markedPaid({ number }));
  } catch { toast(STR.markPaidFailed); }
}

async function openInvoiceDetail(id) {
  let inv = state.invoices.find(i => i.id === id);
  try { inv = await api(`/app/api/crm/invoices/${encodeURIComponent(id)}`); }
  catch { /* keep list row */ }
  if (!inv) return;
  const status = effectiveStatus(inv);
  const unpaid = inv.status === 'PENDING' || inv.status === 'OVERDUE';
  const here = { key: `invoice:${inv.id}`, label: inv.number, open: () => openInvoiceDetail(inv.id) };
  const clientLink = hasModule('clients') && !!inv.clientId && !linksBackTo(`client:${inv.clientId}`);
  const quoteLink = hasModule('quotes') && !!inv.quoteId && !linksBackTo(`quote:${inv.quoteId}`);
  const form = document.createElement('div');
  form.className = 'form';
  form.innerHTML = `
    ${detailHead(inv.clientName, invoicePill(status), inv.totalEur, invoiceTone(status), clientLink)}
    ${detailMeta([
      { label: STR.detailIssued, value: fmtDay(inv.createdAt) },
      { label: STR.thDueDate, value: unpaid ? `${fmtDay(inv.dueDate)} · ${dueWhenText(inv.dueDate, status === 'OVERDUE')}` : fmtDay(inv.dueDate) },
      inv.status === 'PAID' && inv.paidAt ? { label: STR.paidOnLabel, value: fmtDay(inv.paidAt) } : null,
    ])}
    ${inv.quoteNumber && !quoteLink ? `<p class="hint">${escapeHTML(STR.invoiceFromQuote({ number: inv.quoteNumber }))}</p>` : ''}
    ${itemsTable(inv.items)}
    <div class="detail__foot">
      ${unpaid ? `<button class="btn btn--sm btn--accent" type="button" id="inv-paid">${escapeHTML(STR.markPaid)}</button>` : ''}
      ${pdfButton(inv.id, 'invoices', inv.hasPdf, inv.number)}
      ${quoteLink ? `<button class="btn btn--sm btn--ghost" type="button" data-open-quote>${escapeHTML(STR.detailOpenDoc({ number: inv.quoteNumber || '' }))}</button>` : ''}
    </div>`;
  $('[data-detail-link]', form)?.addEventListener('click', () => openFrom(here, () => openClientDrawer(inv.clientId)));
  $('[data-open-quote]', form)?.addEventListener('click', () => openFrom(here, () => openQuoteDetail(inv.quoteId)));
  $('#inv-paid', form)?.addEventListener('click', () => markInvoicePaid(inv.id, inv.number));
  wirePdfButtons(form);
  openDrawer(inv.number, form);
}

async function markPaymentPaid(id, number = state.payments.find(p => p.id === id)?.number || '') {
  const t = CRM.payments;
  const ok = await confirmDialog({
    title: t.markPaidConfirmTitle,
    body: t.markPaidConfirmBody({ number }),
    okLabel: t.markPaid,
    danger: false,
  });
  if (!ok) return;
  try {
    await api(`/app/api/crm/payments/${encodeURIComponent(id)}/paid`, { method: 'PATCH' });
    closeDrawer();
    await loadModule('payments');
    render();
    toast(t.markedPaid({ number }));
  } catch { toast(t.markPaidFailed); }
}

async function openPaymentDetail(id) {
  const t = CRM.payments;
  let pay = state.payments.find(p => p.id === id);
  try { pay = await api(`/app/api/crm/payments/${encodeURIComponent(id)}`); }
  catch { /* keep list row */ }
  if (!pay) return;
  const status = effectiveStatus(pay);
  const canMarkPaid = pay.status === 'PENDING' || pay.status === 'OVERDUE';
  const tone = { PENDING: 'warn', PAID: 'ok', OVERDUE: 'bad' }[status] || '';
  const kind = pay.supplierId ? 'supplier' : pay.employeeId ? 'employee' : '';
  const payeeKey = kind ? `${kind}:${payeeId(pay)}` : '';
  const payeeLink = !!kind && hasModule(PAYEE_KINDS[kind].module) && !linksBackTo(payeeKey);
  const here = { key: `payment:${pay.id}`, label: pay.number, open: () => openPaymentDetail(pay.id) };
  const form = document.createElement('div');
  form.className = 'form';
  form.innerHTML = `
    ${detailHead(payeeName(pay), paymentPill(status), pay.totalEur, tone, payeeLink)}
    ${detailMeta([
      { label: STR.detailCreated, value: fmtDay(pay.createdAt) },
      { label: t.thDueDate, value: canMarkPaid ? `${fmtDay(pay.dueDate)} · ${dueWhenText(pay.dueDate, status === 'OVERDUE')}` : fmtDay(pay.dueDate) },
      pay.status === 'PAID' && pay.paidAt ? { label: STR.paidOnLabel, value: fmtDay(pay.paidAt) } : null,
    ])}
    ${itemsTable(pay.items)}
    ${pay.notes ? `<p class="hint">${escapeHTML(pay.notes)}</p>` : ''}
    <div class="detail__foot">
      ${canMarkPaid ? `<button class="btn btn--sm btn--accent" type="button" id="pay-paid">${escapeHTML(t.markPaid)}</button>` : ''}
    </div>`;
  $('[data-detail-link]', form)?.addEventListener('click', () => openFrom(here, () => openPayeeDrawer(kind, payeeId(pay))));
  $('#pay-paid', form)?.addEventListener('click', () => markPaymentPaid(pay.id, pay.number));
  openDrawer(pay.number, form);
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
      <td>${catalogTypePill(i.type)}${i.bookable && i.durationMinutes && hasModule('bookings') ? ` <span class="pill pill--ok">${escapeHTML(t.bookablePill({ min: i.durationMinutes }))}</span>` : ''}</td>
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
  if (action.toolName === 'create_booking') return STR.assistantCreateBooking;
  if (action.toolName === 'reschedule_booking') return STR.assistantRescheduleBooking;
  if (action.toolName === 'cancel_booking') return STR.assistantCancelBooking;
  if (action.toolName === 'confirm_booking') return STR.assistantConfirmBooking;
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
  if (args.service_id) details.push(`${STR.bookingsService}: ${state.bookingServices.find(s => s.id === args.service_id)?.name || args.service_id}`);
  if (args.start_at) details.push(`${STR.bookingsStart}: ${String(args.start_at).replace('T', ' ')}`);
  if (args.contact_name) details.push(`${STR.bookingsContactName}: ${args.contact_name}`);
  if (args.contact_phone) details.push(`${STR.bookingsContactPhone}: ${args.contact_phone}`);
  if (args.booking_id) details.push(STR.assistantBookingRef({ id: args.booking_id }));
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
    const bubble = m.content ? `<div class="chat__msg chat__msg--${m.role === 'user' ? 'user' : 'bot'}">${renderChatText(m.content)}</div>` : '';
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
  log.innerHTML = msgs.map(m => `<div class="chat__msg chat__msg--${m.role === 'user' ? 'user' : 'bot'}">${renderChatText(m.content)}</div>`).join('')
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

function appearancePanel() {
  const prefs = window.UIPrefs;
  const layout = prefs ? prefs.layout() : 'classic';
  const theme = prefs ? prefs.theme() : 'light';
  const choice = (group, id, title, detail, on) => `<button type="button" class="queue__item choice ${on ? 'is-on' : ''}" data-appearance-${group}="${id}" aria-pressed="${on}"><div><strong>${escapeHTML(title)}</strong><span>${escapeHTML(detail)}</span></div><span class="queue__meta">${on ? `<span class="pill pill--ok">${escapeHTML(STR.appearanceActive)}</span>` : ''}</span></button>`;
  const section = (title, hint, choices) => `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(title)}</h2></header><div class="panel__body"><div class="form">${hint ? `<div class="hint">${escapeHTML(hint)}</div>` : ''}<div class="choice-list">${choices}</div></div></div></section>`;
  return `<div class="settings-stack">${section(STR.appearanceLayoutTitle, STR.appearanceLayoutHint,
    choice('layout', 'classic', STR.layoutClassic, STR.layoutClassicDesc, layout === 'classic')
    + choice('layout', 'minimal', STR.layoutMinimal, STR.layoutMinimalDesc, layout === 'minimal'))}${section(STR.appearanceThemeTitle, '',
    choice('theme', 'light', STR.themeLight, STR.themeLightDesc, theme === 'light')
    + choice('theme', 'dark', STR.themeDark, STR.themeDarkDesc, theme === 'dark'))}</div>`;
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
  const section = state.settingsSection === 'companies' && !companiesEnabled() ? 'channels' : (state.settingsSection || 'channels');
  const tabs = [
    ['home', STR.settingsHome],
    ['appearance', STR.settingsAppearance],
    ['channels', STR.settingsChannels],
    ['widget', STR.settingsWidget],
    ['language', STR.settingsLanguage],
    ['documents', STR.settingsDocuments],
    ...(companiesEnabled() ? [['companies', STR.settingsCompanies]] : []),
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
    : section === 'appearance' ? appearancePanel()
    : section === 'widget' ? widgetPanel
    : section === 'language' ? languagePanel
    : section === 'documents' ? renderDocumentTemplatePanel()
    : section === 'companies' ? companiesPanel()
    : channelsPanel;
  root.innerHTML = `${hero(labels.settings, STR.settingsDesc)}${chips}${body}`;
  $$('[data-settings]', root).forEach(b => b.addEventListener('click', () => { state.settingsSection = b.dataset.settings; render(); }));
  $$('[data-home-card]', root).forEach(b => b.addEventListener('click', () => toggleHomeCard(b.dataset.homeCard)));
  $$('[data-appearance-layout]', root).forEach(b => b.addEventListener('click', () => { window.UIPrefs?.setLayout(b.dataset.appearanceLayout); toast(STR.appearanceSaved); }));
  $$('[data-appearance-theme]', root).forEach(b => b.addEventListener('click', () => { window.UIPrefs?.setTheme(b.dataset.appearanceTheme); toast(STR.appearanceSaved); }));
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
  if (section === 'companies') wireCompaniesPanel(root);
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
    openDrawer,
    closeDrawer,
    confirmDialog,
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
let drawerGen = 0;
// Drawers opened from another drawer (a client record, an invoice…) stack their opener here as
// { key, label, open }. The head shows the way back to the last one, and a drawer that closes itself
// after an action (save, mark paid, convert…) reopens it with fresh data. Closing by hand (×, scrim,
// Escape) clears the stack and goes back to the page.
let drawerTrail = [];
const trailTop = () => drawerTrail[drawerTrail.length - 1] || null;
function openFrom(back, open) {
  if (back) drawerTrail.push(back);
  open();
}
// A link to the record the trail already goes back to would just loop.
const linksBackTo = key => trailTop()?.key === key;

function drawerFocusables(panel) {
  return [...panel.querySelectorAll('a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])')]
    .filter(el => !el.hidden && el.offsetParent !== null);
}

function closeDrawer(opts = {}) {
  const root = $('#drawer');
  if (root) {
    root.hidden = true;
    if (drawerKeyHandler) {
      root.removeEventListener('keydown', drawerKeyHandler);
      drawerKeyHandler = null;
    }
  }
  if (opts.dismissed) drawerTrail = [];
  const back = drawerTrail.pop();
  if (back) {
    // The caller may open another drawer right after closing this one; only come back if it didn't.
    const gen = drawerGen;
    setTimeout(() => { if (drawerGen === gen && $('#drawer')?.hidden) back.open(); }, 0);
    return;
  }
  const prev = drawerPrevFocus;
  drawerPrevFocus = null;
  if (prev && typeof prev.focus === 'function') prev.focus();
}

function renderDrawerEyebrow(text) {
  const el = $('#drawer .drawer__eyebrow');
  if (!el) return;
  const back = trailTop();
  if (!back) { el.textContent = text || STR.dashboardWord; return; }
  el.innerHTML = `<button class="drawer__back" type="button" aria-label="${escapeHTML(STR.clientBackAria({ name: back.label }))}">${escapeHTML(back.label)}</button>`;
  $('.drawer__back', el).addEventListener('click', () => { drawerTrail.pop(); back.open(); });
}

function openDrawer(title, body, wide = false, opts = {}) {
  const root = $('#drawer');
  const panel = $('.drawer__panel', root);
  if (drawerKeyHandler) root.removeEventListener('keydown', drawerKeyHandler);
  if (root.hidden) drawerPrevFocus = document.activeElement;
  drawerGen += 1;
  panel.classList.toggle('drawer__panel--wide', wide);
  renderDrawerEyebrow(opts.eyebrow);
  $('#drawer-title').textContent = title;
  $('#drawer-body').innerHTML = '';
  $('#drawer-body').appendChild(body);
  root.hidden = false;
  $$('[data-close]', root).forEach(b => { b.onclick = () => closeDrawer({ dismissed: true }); });
  drawerKeyHandler = e => {
    if (e.key === 'Escape') { e.preventDefault(); closeDrawer({ dismissed: true }); return; }
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
  return drawerGen;
}

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

const BOOKING_AGENDA_DAYS = 60;

function weekDayKeys() {
  const start = state.bookingWeekStart || periodKey(todayKey(), 'week');
  return Array.from({ length: 7 }, (_, i) => addDayKey(start, i));
}
function bookingLocal(b) { return toLocalInputValue(b.startAt); }
function weekBookings() {
  const keys = new Set(weekDayKeys());
  return state.bookings.filter(b => keys.has(bookingLocal(b).slice(0, 10)));
}
function pendingUpcomingBookings() {
  const now = Date.now();
  return state.bookingUpcoming.filter(b => b.status === 'PENDING' && new Date(b.endAt).getTime() >= now);
}
function findLoadedBooking(id) {
  return state.bookings.find(b => b.id === id) || state.bookingUpcoming.find(b => b.id === id) || null;
}
function bookingServiceName(b) {
  return b.serviceName || state.bookingServices.find(s => s.id === b.serviceId)?.name || '';
}
// Bookings made before prices were snapshotted have none; the server bills those at the catalog price too.
function bookingPrice(b) {
  if (b.priceEur != null) return b.priceEur;
  return state.bookingServices.find(s => s.id === b.serviceId)?.priceEur ?? null;
}
function bookingMinutes(b) { return Math.max(0, Math.round((new Date(b.endAt) - new Date(b.startAt)) / 60000)); }
function bookingTimeRange(b) { return `${fmtTime(b.startAt)}–${fmtTime(b.endAt)}`; }
function bookingWhen(b) {
  return `${capFirst(fmtDayKey(bookingLocal(b).slice(0, 10), { weekday: 'short', day: 'numeric', month: 'short' }))} · ${bookingTimeRange(b)}`;
}
function serviceOptionLabel(s) {
  return [s.name, fmtMinutes(s.durationMinutes || 30), s.priceEur ? fmtEUR(s.priceEur) : ''].filter(Boolean).join(' · ');
}
const hhmmMinutes = hhmm => Number(String(hhmm).slice(0, 2)) * 60 + Number(String(hhmm).slice(3, 5));
const isoWeekday = dayKey => ((dayKeyDate(dayKey).getUTCDay() + 6) % 7) + 1;
function withinOpeningHours(dayKey, time, minutes) {
  const start = hhmmMinutes(time);
  return state.bookingAvailability.some(r => r.dayOfWeek === isoWeekday(dayKey) && hhmmMinutes(r.startLocal) <= start && start + minutes <= hhmmMinutes(r.endLocal));
}
function renderBookings(root) {
  const days = weekDayKeys();
  const today = todayKey();
  const live = weekBookings().filter(b => b.status !== 'CANCELLED');
  const todays = state.bookingUpcoming.filter(b => b.status !== 'CANCELLED' && bookingLocal(b).slice(0, 10) === today);
  const pending = pendingUpcomingBookings();
  const weekValue = live.filter(b => b.status !== 'NO_SHOW').reduce((sum, b) => sum + Number(bookingPrice(b) || 0), 0);
  const stats = statCards([
    { label: STR.bookingsStatToday, value: todays.length },
    { label: STR.bookingsStatWeek, value: live.length },
    { label: STR.bookingsStatPending, value: pending.length },
    { label: STR.bookingsStatValue, value: fmtEUR(weekValue) },
  ]);
  const agenda = state.bookingView === 'agenda';
  const atThisWeek = days[0] === periodKey(today, 'week');
  const weekLabel = `${fmtDayKey(days[0], { day: '2-digit', month: 'short' })} – ${fmtDayKey(days[6], { day: '2-digit', month: 'short', year: 'numeric' })}`;
  const toolbar = `<div class="booking-toolbar">
    <div class="booking-toolbar__views">
      <button type="button" class="btn btn--sm ${agenda ? '' : 'btn--primary'}" data-booking-view="week">${escapeHTML(STR.bookingsWeek)}</button>
      <button type="button" class="btn btn--sm ${agenda ? 'btn--primary' : ''}" data-booking-view="agenda">${escapeHTML(STR.bookingsAgenda)}</button>
    </div>
    ${agenda ? '' : `<div class="booking-toolbar__nav period-nav">
      <button type="button" class="btn btn--sm" data-week-shift="-7" aria-label="${escapeHTML(STR.bookingsPrev)}">‹</button>
      <span class="period-nav__label mono">${escapeHTML(weekLabel)}</span>
      <button type="button" class="btn btn--sm" data-week-shift="7" aria-label="${escapeHTML(STR.bookingsNext)}">›</button>
      ${atThisWeek ? '' : `<button type="button" class="btn btn--sm" data-week-shift="0">${escapeHTML(STR.bookingsToday)}</button>`}
    </div>`}
    <div class="booking-toolbar__actions">
      <button type="button" class="btn btn--sm" data-booking-services>${escapeHTML(STR.bookingsManageServices)}</button>
      <button type="button" class="btn btn--sm" data-booking-availability>${escapeHTML(STR.bookingsManageAvailability)}</button>
    </div>
  </div>`;
  const body = agenda ? bookingAgendaHtml() : bookingPendingPanel(pending) + bookingWeekHtml(days, today);
  root.innerHTML = hero(labels.bookings, STR.bookingsDesc, stats) + bookingSetupHtml() + toolbar + body;

  $$('[data-booking-view]', root).forEach(b => b.addEventListener('click', () => { state.bookingView = b.dataset.bookingView; render(); }));
  $$('[data-week-shift]', root).forEach(b => b.addEventListener('click', async () => {
    const shift = Number(b.dataset.weekShift);
    state.bookingWeekStart = shift === 0 ? periodKey(todayKey(), 'week') : addDayKey(days[0], shift);
    try { await loadModule('bookings'); } catch { toast(STR.loadFailed); }
    render();
  }));
  $$('[data-booking-services]', root).forEach(b => b.addEventListener('click', openBookingServicesForm));
  $$('[data-booking-availability]', root).forEach(b => b.addEventListener('click', openBookingAvailabilityForm));
  $$('[data-booking-filter]', root).forEach(b => b.addEventListener('click', () => { state.bookingStatusFilter = b.dataset.bookingFilter; render(); }));
  $('[data-booking-see-pending]', root)?.addEventListener('click', () => { state.bookingView = 'agenda'; state.bookingStatusFilter = 'PENDING'; render(); });
  $$('[data-booking-status]', root).forEach(b => b.addEventListener('click', e => {
    e.stopPropagation();
    changeBookingStatus(b.dataset.bookingTarget, b.dataset.bookingStatus);
  }));
  $$('[data-booking-open]', root).forEach(el => el.addEventListener('click', e => {
    e.stopPropagation();
    openBookingById(el.dataset.bookingOpen);
  }));
  $$('[data-booking-slot]', root).forEach(el => el.addEventListener('click', e => {
    if (e.target.closest('[data-booking-open]')) return;
    openBookingForm(null, { slot: el.dataset.bookingSlot });
  }));
}

// Nothing can be booked until at least one catalog service is bookable and opening hours exist.
function bookingSetupHtml() {
  const steps = [];
  if (!state.bookingServices.some(s => s.active)) steps.push({ attr: 'data-booking-services', title: STR.bookingsSetupServices, detail: STR.bookingsSetupServicesDesc });
  if (!state.bookingAvailability.length) steps.push({ attr: 'data-booking-availability', title: STR.bookingsSetupHours, detail: STR.bookingsSetupHoursDesc });
  if (!steps.length) return '';
  return `<div class="overview-block"><h2 class="panel__title">${escapeHTML(STR.bookingsSetupTitle)}</h2><div class="setup-list">${steps.map((s, i) => `<button type="button" class="queue__item" ${s.attr}><span class="queue__icon queue__icon--info mono" aria-hidden="true">${i + 1}</span><div><strong>${escapeHTML(s.title)}</strong><span>${escapeHTML(s.detail)}</span></div></button>`).join('')}</div></div>`;
}

function bookingPendingPanel(pending) {
  if (!pending.length) return '';
  const rows = pending.slice(0, 5).map(b => `<tr class="conversation-row" data-booking-open="${escapeHTML(b.id)}">
      <td class="mono">${escapeHTML(bookingWhen(b))}</td>
      <td class="name">${escapeHTML(b.contactName)}<div class="muted mono">${escapeHTML(b.contactPhone)}</div></td>
      <td>${escapeHTML(bookingServiceName(b))}</td>
      <td class="muted">${escapeHTML(bookingSourceLabel(b.source))}</td>
      <td class="right"><div class="actions">
        <button class="btn btn--sm btn--accent" type="button" data-booking-status="CONFIRMED" data-booking-target="${escapeHTML(b.id)}">${escapeHTML(STR.bookingsConfirm)}</button>
        <button class="btn btn--sm btn--ghost" type="button" data-booking-status="CANCELLED" data-booking-target="${escapeHTML(b.id)}">${escapeHTML(STR.bookingsDecline)}</button>
      </div></td>
    </tr>`).join('');
  return crmPanel({
    title: STR.bookingsToConfirm,
    tag: pending.length,
    tools: pending.length > 5 ? `<button class="btn btn--sm btn--ghost" type="button" data-booking-see-pending>${escapeHTML(STR.bookingsToConfirmAll)}</button>` : '',
    head: `<tr><th>${escapeHTML(STR.bookingsThWhen)}</th><th>${escapeHTML(STR.bookingsThContact)}</th><th>${escapeHTML(STR.bookingsThService)}</th><th>${escapeHTML(STR.bookingsSource)}</th><th></th></tr>`,
    rows,
    empty: '',
  });
}

function bookingHourRange(days) {
  let start = 24;
  let end = 0;
  for (const r of state.bookingAvailability) {
    start = Math.min(start, Math.floor(hhmmMinutes(r.startLocal) / 60));
    end = Math.max(end, Math.ceil(hhmmMinutes(r.endLocal) / 60));
  }
  if (start >= end) { start = 8; end = 19; }
  const keys = new Set(days);
  for (const b of state.bookings) {
    const from = bookingLocal(b);
    if (!keys.has(from.slice(0, 10))) continue;
    const to = toLocalInputValue(b.endAt);
    start = Math.min(start, Number(from.slice(11, 13)));
    end = Math.max(end, to.slice(0, 10) === from.slice(0, 10) ? Math.ceil(hhmmMinutes(to.slice(11, 16)) / 60) : 24);
  }
  return { start: Math.max(0, start), end: Math.min(24, Math.max(end, start + 1)) };
}

function bookingWeekHtml(days, today) {
  const { start, end } = bookingHourRange(days);
  const byCell = new Map();
  for (const b of state.bookings) {
    const local = bookingLocal(b);
    const key = `${local.slice(0, 10)}|${Number(local.slice(11, 13))}`;
    if (!byCell.has(key)) byCell.set(key, []);
    byCell.get(key).push(b);
  }
  const hasHours = state.bookingAvailability.length > 0;
  const head = `<div class="cal-grid__corner"></div>${days.map((d, i) => `<div class="cal-grid__dayhead${d === today ? ' is-today' : ''}"><div>${escapeHTML(STR[`weekday${i + 1}`])}</div><div class="mono muted">${escapeHTML(fmtDayKey(d, { day: '2-digit', month: '2-digit' }))}</div></div>`).join('')}`;
  let rows = '';
  for (let hour = start; hour < end; hour += 1) {
    const hh = String(hour).padStart(2, '0');
    const cells = days.map((d, i) => {
      const chips = (byCell.get(`${d}|${hour}`) || [])
        .sort((a, b) => a.startAt.localeCompare(b.startAt))
        .map(b => {
          const service = bookingServiceName(b);
          const title = [bookingTimeRange(b), b.contactName, service, bookingStatusLabel(b.status)].filter(Boolean).join(' · ');
          return `<button type="button" class="cal-event cal-event--${b.status.toLowerCase().replace('_', '-')}" data-booking-open="${escapeHTML(b.id)}" title="${escapeHTML(title)}"><span class="cal-event__time mono">${escapeHTML(bookingTimeRange(b))}</span><span class="cal-event__who">${escapeHTML(b.contactName)}</span>${service ? `<span class="cal-event__what">${escapeHTML(service)}</span>` : ''}</button>`;
        }).join('');
      const open = !hasHours || state.bookingAvailability.some(r => r.dayOfWeek === i + 1 && hhmmMinutes(r.startLocal) < (hour + 1) * 60 && hhmmMinutes(r.endLocal) > hour * 60);
      return `<div class="cal-grid__cell${open ? '' : ' is-closed'}${d === today ? ' is-today' : ''}" data-booking-slot="${d}T${hh}:00">${chips}</div>`;
    }).join('');
    rows += `<div class="cal-grid__hour mono muted">${hh}:00</div>${cells}`;
  }
  return `<div class="panel cal-wrap"><div class="cal-grid">${head}${rows}</div></div>`;
}

function bookingAgendaHtml() {
  const q = state.search.toLowerCase();
  const filter = state.bookingStatusFilter;
  const today = todayKey();
  const list = state.bookingUpcoming
    .filter(b => bookingLocal(b).slice(0, 10) >= today)
    .filter(b => !filter || b.status === filter)
    .filter(b => !q || `${b.contactName} ${b.contactPhone} ${bookingServiceName(b)} ${b.notes || ''}`.toLowerCase().includes(q))
    .sort((a, b) => a.startAt.localeCompare(b.startAt));
  let lastDay = '';
  const rows = list.map(b => {
    const day = bookingLocal(b).slice(0, 10);
    const dayRow = day === lastDay ? '' : `<tr class="is-day"><td colspan="6">${escapeHTML(capFirst([relativeDayLabel(day), fmtDayKey(day, { weekday: 'long', day: 'numeric', month: 'long' })].filter(Boolean).join(' · ')))}</td></tr>`;
    lastDay = day;
    const action = b.status === 'PENDING'
      ? `<button class="btn btn--sm btn--accent" type="button" data-booking-status="CONFIRMED" data-booking-target="${escapeHTML(b.id)}">${escapeHTML(STR.bookingsConfirm)}</button>`
      : `<span class="muted">${escapeHTML(bookingSourceLabel(b.source))}</span>`;
    return `${dayRow}<tr class="conversation-row${b.status === 'CANCELLED' ? ' is-draft' : ''}" data-booking-open="${escapeHTML(b.id)}">
      <td class="mono">${escapeHTML(bookingTimeRange(b))}</td>
      <td class="name">${escapeHTML(b.contactName)}<div class="muted mono">${escapeHTML(b.contactPhone)}</div></td>
      <td>${escapeHTML(bookingServiceName(b))}</td>
      <td class="num">${bookingPrice(b) != null ? fmtEUR(bookingPrice(b)) : '—'}</td>
      <td>${bookingPill(b.status)}</td>
      <td class="right">${action}</td>
    </tr>`;
  }).join('');
  const chips = [''].concat(BOOKING_STATUSES)
    .map(s => `<button class="chip ${filter === s ? 'is-on' : ''}" type="button" data-booking-filter="${s}">${escapeHTML(s ? bookingStatusLabel(s) : STR.bookingsStatusAll)}</button>`)
    .join('');
  const narrowed = Boolean(filter || q);
  return crmPanel({
    title: STR.bookingsAgendaTitle({ days: BOOKING_AGENDA_DAYS }),
    tag: list.length,
    tools: chips,
    head: `<tr><th>${escapeHTML(STR.bookingsThWhen)}</th><th>${escapeHTML(STR.bookingsThContact)}</th><th>${escapeHTML(STR.bookingsThService)}</th><th class="right">${escapeHTML(STR.bookingsPrice)}</th><th>${escapeHTML(STR.bookingsThStatus)}</th><th></th></tr>`,
    rows,
    empty: narrowed ? STR.bookingsEmptyList : STR.bookingsEmptyAgenda,
    emptyDesc: narrowed ? '' : STR.bookingsEmptyAgendaDesc,
  });
}

async function afterBookingChange() {
  if (state.fetched.bookings || state.active === 'bookings') await loadModule('bookings').catch(() => {});
  if (state.active === 'overview') await loadModule('overview').catch(() => {});
  render();
}

async function openBookingById(id) {
  if (!id) return;
  const booking = findLoadedBooking(id) || await api(`/app/api/bookings/${encodeURIComponent(id)}`).catch(() => null);
  if (!booking) return toast(STR.loadFailed);
  openBookingDetail(booking);
}

async function changeBookingStatus(id, status) {
  const booking = findLoadedBooking(id) || await api(`/app/api/bookings/${encodeURIComponent(id)}`).catch(() => null);
  if (!booking) return null;
  if (status === 'CANCELLED') {
    const ok = await confirmDialog({
      title: STR.bookingsCancelConfirmTitle,
      body: STR.bookingsCancelConfirmBody({ name: booking.contactName, when: bookingWhen(booking) }),
      okLabel: STR.bookingsCancelOk,
    });
    if (!ok) return null;
  }
  try {
    const updated = await api(`/app/api/bookings/${encodeURIComponent(id)}`, { method: 'POST', body: JSON.stringify({ status }) });
    toast(STR.bookingsStatusChanged({ status: bookingStatusLabel(updated.status) }));
    await afterBookingChange();
    return updated;
  } catch (err) {
    toast(bookingErrorText(err));
    return null;
  }
}

// The record opens over whatever page the user is on, so they keep their place.
function openClientById(id) {
  if (id && hasModule('clients')) openClientDrawer(id);
}

async function openBookingDetail(b) {
  let billing = '';
  if (b.clientServiceId && b.clientId && hasModule('services')) {
    const rows = await api(`/app/api/crm/services?clientId=${encodeURIComponent(b.clientId)}`).catch(() => []);
    billing = rows.find(r => r.id === b.clientServiceId)?.status || '';
  }
  const minutes = bookingMinutes(b);
  const statusButton = (status, label, tone = '') =>
    `<button class="btn btn--sm ${tone}" type="button" data-set-status="${status}">${escapeHTML(label)}</button>`;
  const actions = [];
  if (b.status === 'PENDING') actions.push(statusButton('CONFIRMED', STR.bookingsConfirm, 'btn--accent'));
  if (b.status === 'PENDING' || b.status === 'CONFIRMED') {
    actions.push(statusButton('COMPLETED', STR.bookingsComplete, b.status === 'CONFIRMED' ? 'btn--accent' : ''));
    actions.push(statusButton('NO_SHOW', STR.bookingsNoShow));
    actions.push(statusButton('CANCELLED', STR.bookingsCancel, 'btn--ghost'));
  } else {
    actions.push(statusButton('CONFIRMED', STR.bookingsReopen, 'btn--ghost'));
  }
  const canInvoice = b.status === 'COMPLETED' && billing === 'OPEN' && hasModule('invoices');
  const billingHint = billing === 'INVOICED'
    ? `<p class="hint"><span class="pill pill--ok">${escapeHTML(STR.bookingsInvoiced)}</span></p>`
    : billing === 'OPEN' ? `<p class="hint">${escapeHTML(STR.bookingsBilled)}</p>` : '';
  const clientLink = !!b.clientId && hasModule('clients') && !linksBackTo(`client:${b.clientId}`);
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `
    ${detailHead(b.contactName, bookingPill(b.status), bookingPrice(b) || 0, bookingTone(b.status), clientLink)}
    ${detailMeta([
      { label: STR.bookingsThWhen, value: bookingWhen(b) },
      { label: STR.bookingsService, value: [bookingServiceName(b), minutes ? fmtMinutes(minutes) : ''].filter(Boolean).join(' · ') },
      { label: STR.bookingsContactPhone, value: b.contactPhone },
      { label: STR.bookingsSource, value: bookingSourceLabel(b.source) },
    ])}
    ${b.notes ? `<p class="hint">${escapeHTML(b.notes)}</p>` : ''}
    ${billingHint}
    <div class="detail__foot">
      ${actions.join('')}
      ${canInvoice ? `<button class="btn btn--sm btn--accent" type="button" data-booking-invoice>${escapeHTML(STR.bookingsInvoice)}</button>` : ''}
      <button class="btn btn--sm btn--ghost" type="button" data-booking-edit>${escapeHTML(STR.bookingsEditAction)}</button>
      ${b.clientServiceId && hasModule('services') && !linksBackTo(`service:${b.clientServiceId}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-booking-service>${escapeHTML(STR.bookingsOpenService)}</button>` : ''}
    </div>`;
  $$('[data-set-status]', body).forEach(btn => btn.addEventListener('click', async () => {
    btn.disabled = true;
    const updated = await changeBookingStatus(b.id, btn.dataset.setStatus);
    if (updated) openBookingDetail(updated);
    else btn.disabled = false;
  }));
  $('[data-booking-invoice]', body)?.addEventListener('click', () => invoiceBooking(b));
  $('[data-booking-edit]', body)?.addEventListener('click', () => openBookingForm(b));
  const here = {
    key: `booking:${b.id}`,
    label: bookingServiceName(b) || STR.bookingsEdit,
    open: async () => openBookingDetail(await api(`/app/api/bookings/${encodeURIComponent(b.id)}`).catch(() => b)),
  };
  $('[data-detail-link]', body)?.addEventListener('click', () => openFrom(here, () => openClientDrawer(b.clientId)));
  $('[data-booking-service]', body)?.addEventListener('click', () => openFrom(here, () => openServiceDetail(b.clientServiceId)));
  openDrawer(bookingServiceName(b) || STR.bookingsEdit, body);
}

function invoiceBooking(b) {
  const t = CRM.services;
  const form = document.createElement('form');
  form.className = 'form';
  const due = new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10);
  form.innerHTML = `
    <p class="hint">${escapeHTML([b.contactName, bookingServiceName(b), fmtEUR(bookingPrice(b) || 0)].filter(Boolean).join(' · '))}</p>
    <div class="form__row"><label class="lbl" for="bk-due">${escapeHTML(t.invoiceDue)} <span class="req">●</span></label>
      <input class="inp" id="bk-due" type="date" required value="${due}" /></div>
    <button class="btn btn--primary" type="submit">${escapeHTML(STR.bookingsInvoice)}</button>`;
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const dueDate = $('#bk-due', form).value;
    if (!dueDate) return toast(STR.invoiceEnterDueDate);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const invoice = await api('/app/api/crm/services/invoice', {
        method: 'POST',
        body: JSON.stringify({ clientId: b.clientId, serviceIds: [b.clientServiceId], dueDate }),
      });
      closeDrawer();
      toast(t.issuedFrom({ number: invoice.number }));
      await afterBookingChange();
    } catch { btn.disabled = false; toast(t.invoiceFailed); }
  });
  openDrawer(STR.bookingsInvoice, form);
}

function openBookingForm(booking = null, opts = {}) {
  const editing = booking && booking.id ? booking : null;
  const services = state.bookingServices.filter(s => s.active || s.id === editing?.serviceId);
  if (!services.length) return openBookingServicesForm();
  const startLocal = editing ? bookingLocal(editing) : (opts.slot || '');
  const dateVal = startLocal ? startLocal.slice(0, 10) : todayKey();
  const timeVal = startLocal ? startLocal.slice(11, 16) : '';
  const preset = services.find(s => s.id === editing?.serviceId) || (services.length === 1 ? services[0] : null);
  let clientId = editing?.clientId || opts.client?.id || '';
  let clientName = opts.client?.name || '';
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      <div class="form__row form__row--full"><label class="lbl" for="bk-service">${escapeHTML(STR.bookingsService)} <span class="req">●</span></label>
        <select class="sel" id="bk-service" required><option value="">${escapeHTML(STR.bookingsChooseService)}</option>
          ${services.map(s => `<option value="${escapeHTML(s.id)}" data-duration="${s.durationMinutes || 30}" data-price="${s.priceEur ?? ''}" ${preset?.id === s.id ? 'selected' : ''}>${escapeHTML(serviceOptionLabel(s))}</option>`).join('')}
        </select></div>
      <div class="form__row"><label class="lbl" for="bk-date">${escapeHTML(STR.bookingsDate)} <span class="req">●</span></label>
        <input class="inp" type="date" id="bk-date" required value="${escapeHTML(dateVal)}" /></div>
      <div class="form__row"><label class="lbl" for="bk-time">${escapeHTML(STR.bookingsTime)} <span class="req">●</span></label>
        <input class="inp inp--mono" type="time" id="bk-time" step="300" required value="${escapeHTML(timeVal)}" /></div>
      <div class="form__row form__row--full"><span class="lbl">${escapeHTML(STR.bookingsFreeTimes)}</span>
        <div class="slot-picks" id="bk-slots"></div>
        <p class="hint" id="bk-hours-hint" hidden>${escapeHTML(STR.bookingsOutsideHours)}</p></div>
      <div class="form__row"><label class="lbl" for="bk-duration">${escapeHTML(STR.bookingsDurationShort)}</label>
        <input class="inp inp--mono" type="number" min="5" step="5" id="bk-duration" value="${editing ? bookingMinutes(editing) : (preset?.durationMinutes || 30)}" /></div>
      <div class="form__row"><label class="lbl" for="bk-price">${escapeHTML(STR.bookingsPrice)}</label>
        <input class="inp inp--mono inp--right" type="number" min="0" step="0.01" id="bk-price" value="${editing ? (bookingPrice(editing) ?? '') : (preset?.priceEur ?? '')}" /></div>
      <div class="form__row form__row--full suggest-host"><label class="lbl" for="bk-name">${escapeHTML(STR.bookingsContactName)} <span class="req">●</span></label>
        <input class="inp" id="bk-name" autocomplete="off" required value="${escapeHTML(editing?.contactName || opts.client?.name || '')}" />
        <div class="suggest" id="bk-suggest-name" hidden></div></div>
      <div class="form__row form__row--full suggest-host"><label class="lbl" for="bk-phone">${escapeHTML(STR.bookingsContactPhone)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="bk-phone" autocomplete="off" required value="${escapeHTML(editing?.contactPhone || opts.client?.phone || '')}" />
        <div class="suggest" id="bk-suggest-phone" hidden></div>
        <p class="hint" id="bk-client-hint"></p></div>
      ${editing ? `<div class="form__row form__row--full"><label class="lbl" for="bk-status">${escapeHTML(STR.bookingsStatus)}</label>
        <select class="sel" id="bk-status">${BOOKING_STATUSES.map(s => `<option value="${s}" ${editing.status === s ? 'selected' : ''}>${escapeHTML(bookingStatusLabel(s))}</option>`).join('')}</select></div>` : ''}
      <div class="form__row form__row--full"><label class="lbl" for="bk-notes">${escapeHTML(STR.bookingsNotes)} <span class="opt">${escapeHTML(STR.bookingsOptional)}</span></label>
        <textarea class="txt" id="bk-notes">${escapeHTML(editing?.notes || '')}</textarea></div>
    </div>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(STR.bookingsSave)}</button>
      ${editing ? `<button class="btn btn--ghost" type="button" data-booking-back>${escapeHTML(STR.bookingsBack)}</button>` : ''}
    </div>`;
  const el = id => $(`#${id}`, form);

  const renderClientHint = () => {
    const hint = el('bk-client-hint');
    if (!hasModule('clients')) { hint.textContent = ''; return; }
    if (clientId) {
      hint.innerHTML = `${escapeHTML(STR.bookingsLinkedClient({ name: clientName || el('bk-name').value }))} <button class="btn btn--sm btn--ghost" type="button" data-unlink-client>${escapeHTML(STR.bookingsUnlink)}</button>`;
      $('[data-unlink-client]', hint).addEventListener('click', () => { clientId = ''; clientName = ''; renderClientHint(); });
    } else {
      hint.textContent = STR.bookingsClientHint;
    }
  };

  let suggestTimer;
  const hideSuggest = () => $$('.suggest', form).forEach(box => { box.hidden = true; });
  const lookupClients = (query, box) => {
    clearTimeout(suggestTimer);
    if (!hasModule('clients') || query.trim().length < 2) { box.hidden = true; return; }
    suggestTimer = setTimeout(async () => {
      const found = await api(`/app/api/crm/clients?q=${encodeURIComponent(query.trim())}`).catch(() => []);
      if (!found.length) { box.hidden = true; return; }
      box.innerHTML = found.slice(0, 6).map(c => `<button type="button" class="suggest__item" data-pick-client="${escapeHTML(c.id)}"><strong>${escapeHTML(c.name)}</strong><span class="mono">${escapeHTML(c.phone)}</span></button>`).join('');
      box.hidden = false;
      $$('[data-pick-client]', box).forEach(btn => {
        btn.addEventListener('mousedown', e => e.preventDefault());
        btn.addEventListener('click', () => {
          const c = found.find(x => x.id === btn.dataset.pickClient);
          if (!c) return;
          clientId = c.id;
          clientName = c.name;
          el('bk-name').value = c.name;
          el('bk-phone').value = c.phone;
          hideSuggest();
          renderClientHint();
        });
      });
    }, 200);
  };
  el('bk-name').addEventListener('input', e => lookupClients(e.target.value, el('bk-suggest-name')));
  el('bk-phone').addEventListener('input', e => lookupClients(e.target.value, el('bk-suggest-phone')));
  form.addEventListener('focusout', e => {
    const next = e.relatedTarget;
    if (!next?.closest?.('.suggest') && next !== el('bk-name') && next !== el('bk-phone')) hideSuggest();
  });

  const checkHours = () => {
    const time = el('bk-time').value;
    const outside = Boolean(time) && state.bookingAvailability.length > 0
      && !withinOpeningHours(el('bk-date').value, time, Number(el('bk-duration').value || 0));
    el('bk-hours-hint').hidden = !outside;
  };
  const refreshSlots = async () => {
    const box = el('bk-slots');
    const serviceId = el('bk-service').value;
    const date = el('bk-date').value;
    if (!serviceId || !date) { box.innerHTML = `<span class="muted">${escapeHTML(STR.bookingsPickServiceFirst)}</span>`; return; }
    const range = dayKeyRange(date, 1);
    const slots = await api(`/app/api/bookings/slots?serviceId=${encodeURIComponent(serviceId)}&from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}`).catch(() => []);
    const times = slots.map(s => toLocalInputValue(s.startAt)).filter(v => v.slice(0, 10) === date).map(v => v.slice(11, 16));
    box.innerHTML = times.length
      ? times.map(t => `<button type="button" class="chip ${t === el('bk-time').value ? 'is-on' : ''}" data-slot-time="${t}">${escapeHTML(t)}</button>`).join('')
      : `<span class="muted">${escapeHTML(STR.bookingsNoFreeTimes)}</span>`;
    $$('[data-slot-time]', box).forEach(chip => chip.addEventListener('click', () => {
      el('bk-time').value = chip.dataset.slotTime;
      $$('[data-slot-time]', box).forEach(c => c.classList.toggle('is-on', c === chip));
      checkHours();
    }));
  };
  el('bk-service').addEventListener('change', () => {
    const opt = el('bk-service').selectedOptions[0];
    if (opt?.value) {
      el('bk-duration').value = opt.dataset.duration || 30;
      if (opt.dataset.price !== '') el('bk-price').value = opt.dataset.price;
    }
    refreshSlots();
    checkHours();
  });
  el('bk-date').addEventListener('change', () => { refreshSlots(); checkHours(); });
  el('bk-time').addEventListener('input', () => {
    $$('[data-slot-time]', form).forEach(c => c.classList.toggle('is-on', c.dataset.slotTime === el('bk-time').value));
    checkHours();
  });
  el('bk-duration').addEventListener('input', checkHours);
  $('[data-booking-back]', form)?.addEventListener('click', () => openBookingDetail(editing));

  form.addEventListener('submit', async e => {
    e.preventDefault();
    const serviceId = el('bk-service').value;
    const date = el('bk-date').value;
    const time = el('bk-time').value;
    const contactName = el('bk-name').value.trim();
    const contactPhone = el('bk-phone').value.trim();
    if (!serviceId || !date || !time || !contactName || !contactPhone) return toast(STR.bookingsValidate);
    const price = el('bk-price').value;
    const payload = {
      serviceId,
      startAt: `${date}T${time}`,
      durationMinutes: Number(el('bk-duration').value || 0) || undefined,
      priceEur: price === '' ? undefined : Number(price),
      contactName,
      contactPhone,
      notes: el('bk-notes').value.trim(),
      status: editing ? el('bk-status').value : 'CONFIRMED',
      ...(clientId ? { clientId } : {}),
      ...(editing?.clientId && !clientId ? { clearClientId: true } : {}),
    };
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      if (editing) await api(`/app/api/bookings/${encodeURIComponent(editing.id)}`, { method: 'POST', body: JSON.stringify(payload) });
      else await api('/app/api/bookings', { method: 'POST', body: JSON.stringify(payload) });
      closeDrawer();
      toast(editing ? STR.bookingsUpdated : STR.bookingsCreated);
      await afterBookingChange();
    } catch (err) {
      btn.disabled = false;
      toast(bookingErrorText(err));
    }
  });

  renderClientHint();
  refreshSlots();
  checkHours();
  openDrawer(editing ? STR.bookingsEdit : STR.bookingsNew, form);
}

function openBookingServicesForm() {
  const body = document.createElement('div');
  body.className = 'form';
  const rows = state.bookingServices.map(s => `<tr data-svc-row="${escapeHTML(s.id)}">
      <td class="check"><input type="checkbox" data-svc-active ${s.active ? 'checked' : ''} aria-label="${escapeHTML(STR.bookingsBookable)}" /></td>
      <td class="name">${escapeHTML(s.name)}<div class="muted">${escapeHTML(s.category || '')}</div></td>
      <td><input class="inp inp--mono booking-services__duration" type="number" min="5" step="5" data-svc-duration value="${s.durationMinutes || ''}" placeholder="30" aria-label="${escapeHTML(STR.bookingsDurationShort)}" /></td>
      <td class="num">${fmtEUR(s.priceEur)}</td>
    </tr>`).join('');
  body.innerHTML = `
    <p class="hint">${escapeHTML(STR.bookingsServicesHint)}</p>
    ${panelTable(`<tr><th>${escapeHTML(STR.bookingsBookable)}</th><th>${escapeHTML(STR.bookingsServiceName)}</th><th>${escapeHTML(STR.bookingsDurationShort)}</th><th class="right">${escapeHTML(STR.bookingsPrice)}</th></tr>`, rows, STR.bookingsNoServices, STR.bookingsNoServicesDesc)}
    ${hasModule('catalog') ? `<div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-go-catalog>${escapeHTML(STR.bookingsGoCatalog)}</button></div>` : ''}
    <form class="form" id="bk-service-create">
      <h3 class="panel__title">${escapeHTML(STR.bookingsAddService)}</h3>
      <div class="form__grid form__grid--3">
        <div class="form__row"><label class="lbl" for="bs-name">${escapeHTML(STR.bookingsServiceName)} <span class="req">●</span></label><input class="inp" id="bs-name" required /></div>
        <div class="form__row"><label class="lbl" for="bs-duration">${escapeHTML(STR.bookingsDurationShort)}</label><input class="inp inp--mono" id="bs-duration" type="number" min="5" step="5" value="30" required /></div>
        <div class="form__row"><label class="lbl" for="bs-price">${escapeHTML(STR.bookingsPrice)}</label><input class="inp inp--mono inp--right" id="bs-price" type="number" min="0" step="0.01" placeholder="0.00" /></div>
      </div>
      <div class="actions"><button class="btn btn--primary" type="submit">${escapeHTML(STR.bookingsAddService)}</button></div>
    </form>`;
  const saveRow = async row => {
    const active = $('[data-svc-active]', row).checked;
    const duration = Number($('[data-svc-duration]', row).value || 0);
    try {
      await api(`/app/api/bookings/services/${encodeURIComponent(row.dataset.svcRow)}`, {
        method: 'POST',
        body: JSON.stringify({ active, ...(duration >= 5 ? { durationMinutes: duration } : {}) }),
      });
      state.bookingServices = await api('/app/api/bookings/services').catch(() => state.bookingServices);
      toast(STR.bookingsServiceSaved);
      if (state.active === 'bookings') render();
    } catch (err) { toast(bookingErrorText(err)); }
  };
  $$('[data-svc-row]', body).forEach(row => {
    $('[data-svc-active]', row).addEventListener('change', () => saveRow(row));
    $('[data-svc-duration]', row).addEventListener('change', () => saveRow(row));
  });
  $('[data-go-catalog]', body)?.addEventListener('click', () => { closeDrawer(); setActive('catalog'); });
  $('#bk-service-create', body).addEventListener('submit', async e => {
    e.preventDefault();
    const name = $('#bs-name', body).value.trim();
    if (!name) return;
    const price = $('#bs-price', body).value;
    try {
      await api('/app/api/bookings/services', {
        method: 'POST',
        body: JSON.stringify({ name, durationMinutes: Math.max(5, Number($('#bs-duration', body).value || 30)), ...(price === '' ? {} : { priceEur: Number(price) }) }),
      });
      toast(STR.bookingsServiceSaved);
      state.bookingServices = await api('/app/api/bookings/services').catch(() => state.bookingServices);
      if (state.active === 'bookings') render();
      openBookingServicesForm();
    } catch (err) { toast(bookingErrorText(err)); }
  });
  openDrawer(STR.bookingsManageServices, body, true);
}

function openBookingAvailabilityForm() {
  const form = document.createElement('form');
  form.className = 'form';
  const rules = state.bookingAvailability.length
    ? state.bookingAvailability
    : [1, 2, 3, 4, 5].map(dayOfWeek => ({ dayOfWeek, startLocal: '09:00', endLocal: '18:00' }));
  const rowHtml = r => `<div class="booking-avail-row">
      <select class="sel" name="dayOfWeek" aria-label="${escapeHTML(STR.bookingsDay)}">${[1, 2, 3, 4, 5, 6, 7].map(d => `<option value="${d}" ${r.dayOfWeek === d ? 'selected' : ''}>${escapeHTML(STR[`weekday${d}`])}</option>`).join('')}</select>
      <input class="inp inp--mono" type="time" name="startLocal" value="${escapeHTML(r.startLocal)}" aria-label="${escapeHTML(STR.bookingsFrom)}" />
      <input class="inp inp--mono" type="time" name="endLocal" value="${escapeHTML(r.endLocal)}" aria-label="${escapeHTML(STR.bookingsTo)}" />
      <button class="iconbtn iconbtn--danger" type="button" data-remove-window aria-label="${escapeHTML(STR.bookingsRemoveWindow)}" title="${escapeHTML(STR.bookingsRemoveWindow)}">×</button>
    </div>`;
  form.innerHTML = `
    <p class="hint">${escapeHTML(STR.bookingsHoursHint)}</p>
    <div class="booking-avail-row booking-avail-row--head"><span class="lbl">${escapeHTML(STR.bookingsDay)}</span><span class="lbl">${escapeHTML(STR.bookingsFrom)}</span><span class="lbl">${escapeHTML(STR.bookingsTo)}</span><span></span></div>
    <div class="booking-avail" id="bk-windows">${rules.map(rowHtml).join('')}</div>
    <div class="actions"><button type="button" class="btn btn--sm" id="add-avail">${escapeHTML(STR.bookingsAddWindow)}</button></div>
    <div class="actions"><button class="btn btn--primary" type="submit">${escapeHTML(STR.bookingsSaveHours)}</button></div>`;
  const windows = $('#bk-windows', form);
  const wireRemove = scope => $$('[data-remove-window]', scope).forEach(b => b.addEventListener('click', () => b.closest('.booking-avail-row').remove()));
  wireRemove(windows);
  $('#add-avail', form).addEventListener('click', () => {
    const last = $$('.booking-avail-row', windows).pop();
    const nextDay = last ? Math.min(7, Number($('[name=dayOfWeek]', last).value) + 1) : 1;
    windows.insertAdjacentHTML('beforeend', rowHtml({ dayOfWeek: nextDay, startLocal: '09:00', endLocal: '18:00' }));
    wireRemove(windows.lastElementChild);
  });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const next = $$('.booking-avail-row', windows).map(row => ({
      dayOfWeek: Number($('[name=dayOfWeek]', row).value),
      startLocal: $('[name=startLocal]', row).value,
      endLocal: $('[name=endLocal]', row).value,
    }));
    if (next.some(r => !r.startLocal || !r.endLocal || r.endLocal <= r.startLocal)) return toast(STR.bookingsHoursInvalid);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api('/app/api/bookings/availability', { method: 'PUT', body: JSON.stringify({ rules: next }) });
      closeDrawer();
      toast(STR.bookingsAvailabilitySaved);
      await afterBookingChange();
    } catch (err) {
      btn.disabled = false;
      toast(err?.code === 'invalid_availability' ? STR.bookingsHoursInvalid : STR.bookingsSaveFailed);
    }
  });
  openDrawer(STR.bookingsManageAvailability, form, true);
}

async function init() {
  if (handleOAuthPopup()) return;
  I18N.applyDom(document);
  $('#btn-logout').addEventListener('click', () => { localStorage.removeItem('dashboardToken'); token = ''; renderLogin(); });
  $('#btn-account').addEventListener('click', () => { drawerTrail = []; openAccount(); });
  $('#brand').addEventListener('click', () => { drawerTrail = []; openCompanySwitcher(); });
  $('#search').addEventListener('input', e => { state.search = e.target.value; render(); });
  $('#btn-new').addEventListener('click', () => {
    if (state.active === 'clients') return openClientForm();
    if (state.active === 'services') return openServiceForm(null, state.filterServiceClient || undefined);
    if (state.active === 'catalog') return openCatalogForm();
    if (state.active === 'quotes') return openQuoteForm();
    if (state.active === 'invoices') return openInvoiceForm();
    if (state.active === 'suppliers') return openSupplierForm();
    if (state.active === 'employees') return openEmployeeForm();
    if (state.active === 'payments') return openPaymentForm();
    if (state.active === 'bookings') return openBookingForm();
    toast(STR.quickCreateSoon);
  });
  document.addEventListener('keydown', e => { if (e.key === '/' && !['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) { e.preventDefault(); $('#search').focus(); } });
  // setActive() sets location.hash itself on a nav click, so this only ever has real work to do
  // for hash changes it didn't cause: browser Back/Forward, or a same-document navigation to a
  // #tab URL. Without this listener the URL bar updates but the visible tab silently doesn't.
  window.addEventListener('hashchange', () => {
    if (!token) return;
    const tab = (location.hash || '').replace('#', '') || 'overview';
    if (tab === state.active) return;
    setActive(tab);
  });
  document.addEventListener('ui:layout', async () => {
    if (!token || !state.me) return;
    if (state.active === 'overview' && isMinimalLayout() && !state.overviewExtended) {
      try { await loadModule('overview'); } catch { toast(STR.loadFailed); }
    }
    render();
  });
  document.addEventListener('ui:theme', () => {
    if (token && state.me && state.active === 'settings' && state.settingsSection === 'appearance') render();
  });
  if (!token) return renderLogin();
  state.active = (location.hash || '').replace('#', '') || 'overview';
  try { await bootAuthed(); } catch { renderLogin(); }
}
init();
