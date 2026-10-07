const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

let token = localStorage.getItem('adminToken') || '';
let state = { tenants: [], stats: {}, search: '', filter: 'all', whatsAppSignup: { enabled: false } };
let currentView = 'tenants';
let platformSettings = { settings: [], drafts: {}, revealed: {}, clear: new Set(), updatedAt: null };
let fbSdkPromise;

// Backoffice copy comes from the shared i18n catalogs (admin/catalog.*.js). Locale follows the
// browser / the operator's saved choice (super-admin is not tenant-scoped). `T` is a live proxy.
const T = I18N.section('backoffice');

// Module ids only — display labels come from the shared catalog (common.nav.<id>) at render time.
// Only `overview` is always on (see DashboardModules.alwaysOn) — every other module, messaging
// included, is opt-in so a tenant can be CRM-only.
const MODULES = [
  { id: 'overview', always: true },
  { id: 'conversations' },
  { id: 'contacts' },
  { id: 'settings' },
  { id: 'persona' },
  { id: 'clients' },
  { id: 'services' },
  { id: 'quotes' },
  { id: 'invoices' },
  { id: 'suppliers' },
  { id: 'employees' },
  { id: 'payments' },
  { id: 'catalog' },
  { id: 'ai-assistant' },
  { id: 'bookings' },
  { id: 'instagram' },
  // Opt-in: a new tenant starts without it, and tenants with no saved selection don't get it.
  { id: 'agents', optIn: true },
  { id: 'timesheets', optIn: true },
];

async function api(path, options = {}) {
  const headers = { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) };
  const res = await fetch(path, { ...options, headers: { ...headers, ...(options.headers || {}) } });
  if (res.status === 401) {
    localStorage.removeItem('adminToken');
    token = '';
    renderLogin();
    throw new Error('unauthorized');
  }
  if (!res.ok) {
    const err = new Error(`HTTP ${res.status}`);
    err.status = res.status;
    const body = await res.json().catch(() => null);
    err.code = body && typeof body.error === 'string' ? body.error : '';
    err.detail = body && typeof body.detail === 'string' ? body.detail : '';
    throw err;
  }
  if (res.status === 204) return null;
  return res.json();
}

const escapeHTML = (s = '') => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
const slugify = s => (s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const fmtDate = iso => iso ? new Date(iso).toLocaleString(I18N.locale(), { dateStyle: 'short', timeStyle: 'short' }) : '—';
const fmtDay = d => d.toLocaleDateString(I18N.locale(), { dateStyle: 'short' });
const fmtTime = d => d.toLocaleTimeString(I18N.locale(), { timeStyle: 'short' });
// Time under the date: tenant rows already run two lines (name over slug), and the column stays narrow.
const dateCell = iso => (iso ? `${escapeHTML(fmtDay(new Date(iso)))}<div class="sub">${escapeHTML(fmtTime(new Date(iso)))}</div>` : '—');
// A cell's column heading, shown above its value when a .tbl--stack table turns its rows into cards on phones.
const dataLabel = text => `data-label="${escapeHTML(text)}"`;
// Channel platforms and dashboard roles reuse the /app catalog labels; an unknown value shows as sent.
const labelOr = (key, raw) => { const label = I18N.t(key); return label === key ? raw : label; };
const platformLabel = platform => labelOr(`app.channel_${platform}`, platform);
const roleLabel = role => labelOr(`app.accountRole${role}`, role);
const warnNoticeHtml = text => `<div class="notice notice--warn" role="status"><div class="notice__text"><span>${escapeHTML(text)}</span></div></div>`;

let toastTimer;
function toast(msg) {
  const el = $('#toast');
  el.innerHTML = `<span class="toast__dot"></span><span>${escapeHTML(msg)}</span>`;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 2800);
}

function confirmDialog({ title, body, okLabel = T.confirm, danger = true }) {
  return new Promise(resolve => {
    const root = $('#confirm');
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

let drawerOpener = null;

function closeDrawer() {
  const root = $('#drawer');
  if (root.hidden) return;
  root.hidden = true;
  if (drawerOpener?.isConnected) drawerOpener.focus();
  drawerOpener = null;
}

// `back` ({ label, open }) is the tenant record this drawer was opened from: the eyebrow links back to it,
// and a save reopens it with fresh data instead of closing. `onSave` may return false to stay open, or a
// function that opens the next drawer. Without `onSave` there is no footer.
function openDrawer({ title, body, onSave, saveLabel = T.save, autofocus = true, back = null }) {
  const root = $('#drawer');
  if (root.hidden) drawerOpener = document.activeElement;
  $('#drawer-title').textContent = title;
  const eyebrow = $('#drawer-eyebrow');
  if (back) {
    eyebrow.innerHTML = `<button class="drawer__back" type="button" aria-label="${escapeHTML(T.record.backTo({ name: back.label }))}">${escapeHTML(back.label)}</button>`;
    $('.drawer__back', eyebrow).addEventListener('click', () => back.open());
  } else {
    eyebrow.textContent = T.drawerEyebrow;
  }
  const host = $('#drawer-body');
  host.innerHTML = '';
  host.appendChild(body);
  if (onSave) {
    const foot = document.createElement('div');
    foot.className = 'drawer__foot';
    foot.innerHTML = `<button class="btn btn--ghost" type="button" data-close>${escapeHTML(T.cancel)}</button><button class="btn btn--accent" type="button" id="drawer-save">${escapeHTML(saveLabel)}</button>`;
    host.appendChild(foot);
    $('#drawer-save').addEventListener('click', async () => {
      const next = await onSave();
      if (next === false) return;
      if (typeof next === 'function') next();
      else if (back) back.open();
      else closeDrawer();
    });
  }
  $$('[data-close]', root).forEach(b => { b.onclick = closeDrawer; });
  root.hidden = false;
  // On a touch screen a focused field opens the keyboard over the drawer, so focus goes to × instead.
  const intoField = autofocus && !matchMedia('(pointer: coarse)').matches;
  setTimeout(() => (intoField ? host.querySelector('input,select,textarea') : $('.drawer__head [data-close]', root))?.focus(), 50);
}

const FIREBASE_SDK = 'https://www.gstatic.com/firebasejs/12.19.0';
let googleSignIn = null;

// Browsers only allow the sign-in popup straight from the click, so the SDK is loaded and set up
// as soon as the login screen shows. The Firebase session is only used to prove who you are: it
// stays in memory and the backoffice keeps its own admin token.
function prepareGoogleSignIn(config) {
  googleSignIn = googleSignIn || Promise.all([import(`${FIREBASE_SDK}/firebase-app.js`), import(`${FIREBASE_SDK}/firebase-auth.js`)])
    .then(([appSdk, authSdk]) => {
      const app = appSdk.getApps().length ? appSdk.getApp() : appSdk.initializeApp(config);
      const auth = authSdk.initializeAuth(app, { persistence: authSdk.inMemoryPersistence, popupRedirectResolver: authSdk.browserPopupRedirectResolver });
      const provider = new authSdk.GoogleAuthProvider();
      provider.setCustomParameters({ prompt: 'select_account' });
      return { auth, provider, signInWithPopup: authSdk.signInWithPopup, signOut: authSdk.signOut };
    });
  googleSignIn.catch(() => { googleSignIn = null; });
  return googleSignIn;
}

// Signed out, the top bar keeps only the theme switch: the menu, search, New and Log out all need a session.
// Signed in, setView shows search and New on the views that use them.
function showSessionControls(on) {
  $('#btn-nav').hidden = !on;
  $('#btn-logout').hidden = !on;
  if (!on) {
    $('.topbar__search').hidden = true;
    $('#btn-new').hidden = true;
  }
}

async function startSession(newToken) {
  token = newToken;
  localStorage.setItem('adminToken', token);
  await loadAll();
  showSessionControls(true);
  setView(location.hash.replace(/^#/, '') || 'tenants');
}

async function renderLogin() {
  closeDrawer();
  showSessionControls(false);
  const config = await fetch('/admin/auth/config').then(r => (r.ok ? r.json() : null)).catch(() => null);
  const google = config?.google || null;
  const passwordEnabled = config ? config.passwordEnabled : true;
  const ready = google ? prepareGoogleSignIn(google) : null;
  let loadedSdk = null;
  ready?.then(sdk => { loadedSdk = sdk; }, () => {});
  $('#view').innerHTML = `
    <div class="auth"><div class="auth__card">
      <div class="auth__mark">BO</div>
      <p class="auth__eyebrow">${escapeHTML(T.login.eyebrow)}</p>
      <h1 class="auth__title">${escapeHTML(T.login.title)}</h1>
      <p class="auth__desc">${escapeHTML(T.login.desc)}</p>
      ${google ? `<button class="btn btn--primary" type="button" id="login-google">${escapeHTML(T.login.google)}</button>` : ''}
      ${google && passwordEnabled ? `<p class="auth__desc">${escapeHTML(T.login.or)}</p>` : ''}
      ${passwordEnabled ? `<form class="form" id="login-form">
        <div class="form__row"><label class="lbl" for="password">${escapeHTML(T.login.password)}</label><input class="inp" id="password" type="password" autocomplete="current-password" required /></div>
        <button class="btn ${google ? 'btn--ghost' : 'btn--primary'}" type="submit">${escapeHTML(T.login.submit)}</button>
      </form>` : ''}
      ${!google && !passwordEnabled ? `<p class="auth__desc">${escapeHTML(T.login.unavailable)}</p>` : ''}
    </div></div>`;
  $('#login-google')?.addEventListener('click', async e => {
    const button = e.currentTarget;
    button.disabled = true;
    try {
      const sdk = loadedSdk || await ready;
      const result = await sdk.signInWithPopup(sdk.auth, sdk.provider);
      const idToken = await result.user.getIdToken();
      sdk.signOut(sdk.auth).catch(() => {});
      const res = await fetch('/admin/auth/google', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ idToken }) });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        toast(body.error === 'not_allowed' ? T.login.notAllowed : T.login.googleFailed);
        return;
      }
      await startSession(body.token);
    } catch (err) {
      const code = String(err?.code || '');
      toast(code === 'auth/popup-closed-by-user' || code === 'auth/cancelled-popup-request' ? T.login.cancelled : T.login.googleFailed);
    } finally {
      button.disabled = false;
    }
  });
  $('#login-form')?.addEventListener('submit', async e => {
    e.preventDefault();
    try {
      const res = await api('/admin/auth/login', { method: 'POST', body: JSON.stringify({ password: $('#password').value }) });
      await startSession(res.token);
    } catch (err) {
      toast(T.login.invalid);
    }
  });
}

async function loadAll() {
  const [tenants, whatsAppSignup] = await Promise.all([
    api('/admin/api/tenants'),
    api('/admin/api/whatsapp/embedded-signup/config').catch(() => ({ enabled: false })),
  ]);
  state.tenants = tenants;
  state.whatsAppSignup = whatsAppSignup;
  state.stats = await statsFor(state.tenants.filter(t => t.status !== 'DELETED' || state.filter === 'DELETED'));
  renderSidebarStats();
}

// The sidebar's tenant count and KPIs show on every view, not only Tenants.
function renderSidebarStats() {
  const live = state.tenants.filter(t => t.status !== 'DELETED');
  $('#tenant-count').textContent = live.length;
  $('#kpi-active').textContent = state.tenants.filter(t => t.status === 'ACTIVE').length;
  $('#kpi-messages').textContent = live.reduce((sum, t) => sum + Number(state.stats[t.slug]?.messages || 0), 0);
  $('#meta-clock').textContent = new Date().toLocaleString(I18N.locale(), { hour: '2-digit', minute: '2-digit' });
}

async function statsFor(tenants) {
  const stats = await Promise.all(tenants.map(t => api(`/admin/api/tenants/${encodeURIComponent(t.slug)}/stats`).catch(() => null)));
  return Object.fromEntries(tenants.map((t, i) => [t.slug, stats[i] || {}]));
}

// Deleted tenants' stats are only fetched once their list is opened. Returns whether anything loaded.
async function loadDeletedStats() {
  const missing = state.tenants.filter(t => t.status === 'DELETED' && !state.stats[t.slug]);
  if (!missing.length) return false;
  Object.assign(state.stats, await statsFor(missing));
  return true;
}

function applyPlatformSettingsPayload(payload) {
  platformSettings.settings = payload?.settings || [];
  platformSettings.updatedAt = payload?.updatedAt || null;
  platformSettings.drafts = {};
  platformSettings.revealed = {};
  platformSettings.clear = new Set();
}

async function loadPlatformSettings() {
  const payload = await api('/admin/api/platform-settings');
  applyPlatformSettingsPayload(payload);
}

const VIEWS = ['tenants', 'admins', 'backups', 'settings'];

function setView(view) {
  if (!token) return;
  currentView = VIEWS.includes(view) ? view : 'tenants';
  stopBackupPolling();
  if (location.hash !== `#${currentView}`) location.hash = currentView;
  $$('.nav__item').forEach(a => a.classList.toggle('is-active', a.dataset.view === currentView));
  const leaf = $('.crumb__leaf');
  if (leaf) leaf.textContent = { settings: T.platformSettings.nav, admins: T.admins.nav, backups: T.backups.nav }[currentView] || T.heroTitle;
  const search = $('.topbar__search');
  if (search) search.hidden = currentView !== 'tenants';
  const btnNew = $('#btn-new');
  if (btnNew) btnNew.hidden = currentView !== 'tenants';
  if (currentView === 'settings') renderPlatformSettings();
  else if (currentView === 'admins') renderAdmins();
  else if (currentView === 'backups') renderBackups();
  else renderTenants();
}

const loadErrorHtml = e => `<div class="empty"><p class="empty__title">${escapeHTML(T.loadError)}</p><p class="empty__desc">${escapeHTML(e.message)}</p></div>`;

// ── Admins: who may sign in with Google (the server's ADMIN_EMAILS plus the ones added here) ──

async function renderAdmins() {
  const A = T.admins;
  let data;
  try {
    data = await api('/admin/api/admins');
  } catch (e) {
    if (currentView === 'admins') $('#view').innerHTML = loadErrorHtml(e);
    return;
  }
  if (currentView !== 'admins') return;
  $('#view').innerHTML = `
    <div class="view__hero"><div><h1 class="view__title">${escapeHTML(A.title)}</h1><p class="view__desc">${escapeHTML(A.desc)}</p></div></div>
    <div class="settings-stack">
      ${data.googleReady ? '' : warnNoticeHtml(A.googleNotReady)}
      <div class="panel">
        <div class="panel__head"><h2 class="panel__title">${escapeHTML(A.listTitle)} <span class="tag">${data.admins.length}</span></h2></div>
        <div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr>
          <th>${escapeHTML(A.thEmail)}</th><th>${escapeHTML(A.thSource)}</th><th>${escapeHTML(A.thAdded)}</th><th class="right">${escapeHTML(T.thActions)}</th>
        </tr></thead><tbody>${data.admins.map(a => adminRowHtml(a, data.me)).join('')}</tbody></table></div>
      </div>
      <div class="panel">
        <div class="panel__head"><h2 class="panel__title">${escapeHTML(A.addTitle)}</h2></div>
        <div class="panel__body">
          <form class="form" id="admin-form" novalidate>
            <div class="form__row"><label class="lbl" for="admin-email">${escapeHTML(A.emailLabel)}</label><input class="inp" id="admin-email" type="email" autocomplete="off" spellcheck="false" placeholder="${escapeHTML(A.emailPlaceholder)}" /><div class="hint">${escapeHTML(A.addHint)}</div></div>
            <div class="actions"><button class="btn btn--primary" type="submit">${escapeHTML(A.add)}</button></div>
          </form>
        </div>
      </div>
    </div>`;
  bindAdmins();
}

function adminRowHtml(a, me) {
  const A = T.admins;
  const self = !!me && a.email === me.toLowerCase();
  const fromEnv = a.source === 'env';
  const action = fromEnv ? `<span class="muted">${escapeHTML(A.envLocked)}</span>`
    : self ? '' : `<button class="btn btn--sm btn--ghost" type="button" data-remove-admin="${escapeHTML(a.email)}">${escapeHTML(A.remove)}</button>`;
  return `<tr>
    <td class="name">${escapeHTML(a.email)}${self ? `<span class="muted"> · ${escapeHTML(A.you)}</span>` : ''}</td>
    <td ${dataLabel(A.thSource)}><span class="pill ${fromEnv ? 'pill--info' : 'pill--accent'}">${escapeHTML(fromEnv ? A.sourceEnv : A.sourceBackoffice)}</span></td>
    <td class="muted" ${dataLabel(A.thAdded)}>${a.addedAt ? escapeHTML(A.addedBy({ date: fmtDate(a.addedAt), by: a.addedBy })) : '—'}</td>
    <td class="right">${action}</td>
  </tr>`;
}

function adminErrorText(err) {
  const A = T.admins;
  const messages = { invalid_email: A.invalidEmail, already_allowed: A.alreadyAllowed, from_env: A.fromEnv, cannot_remove_self: A.cannotRemoveSelf, not_found: A.notFound };
  return messages[err.code] || T.error({ msg: err.message });
}

function bindAdmins() {
  const A = T.admins;
  const form = $('#admin-form');
  form?.addEventListener('submit', async e => {
    e.preventDefault();
    const input = $('#admin-email', form);
    const email = input.value.trim().toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { toast(A.invalidEmail); input.focus(); return; }
    if (!await confirmDialog({ title: A.addConfirmTitle, body: A.addConfirmBody({ email }), okLabel: A.add, danger: false })) return;
    const button = $('button[type=submit]', form);
    button.disabled = true;
    try {
      await api('/admin/api/admins', { method: 'POST', body: JSON.stringify({ email }) });
      toast(A.added({ email }));
      await renderAdmins();
      $('#admin-email')?.focus();
    } catch (err) {
      toast(adminErrorText(err));
      button.disabled = false;
    }
  });
  $$('[data-remove-admin]').forEach(b => b.addEventListener('click', async () => {
    const email = b.dataset.removeAdmin;
    if (!await confirmDialog({ title: A.removeConfirmTitle, body: A.removeConfirmBody({ email }), okLabel: A.remove })) return;
    try {
      await api(`/admin/api/admins/${encodeURIComponent(email)}`, { method: 'DELETE' });
      toast(A.removed({ email }));
    } catch (err) {
      toast(adminErrorText(err));
    }
    renderAdmins();
  }));
}

// ── Backups: the server's archives, and a manual backup the host's runner picks up within a minute ──

const BACKUP_POLL_MS = 4000;
const BACKUP_STATE_PILLS = { requested: 'pill--warn', running: 'pill--info', succeeded: 'pill--ok', failed: 'pill--bad', stalled: 'pill--bad' };
let backupPoll = null;
// requestedAt of a run seen waiting or running on this page, so its outcome is announced once.
let watchedBackup = null;

function stopBackupPolling() {
  clearTimeout(backupPoll);
  backupPoll = null;
}

async function renderBackups() {
  stopBackupPolling();
  const B = T.backups;
  let data;
  try {
    data = await api('/admin/api/backups');
  } catch (e) {
    if (currentView === 'backups') $('#view').innerHTML = loadErrorHtml(e);
    return;
  }
  if (currentView !== 'backups') return;
  const run = data.current;
  const busy = !!run && (run.state === 'requested' || run.state === 'running');
  announceBackupOutcome(run);
  const hero = `<div class="view__hero"><div><h1 class="view__title">${escapeHTML(B.title)}</h1><p class="view__desc">${escapeHTML(B.desc)}</p></div>
    ${data.available ? `<div class="actions"><button class="btn btn--primary" type="button" id="backup-now" ${busy ? 'disabled' : ''}>${escapeHTML(busy ? B.inProgress : B.backupNow)}</button></div>` : ''}</div>`;
  if (!data.available) {
    $('#view').innerHTML = `${hero}<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(B.unavailableTitle)}</p><p class="empty__desc">${escapeHTML(B.unavailableDesc)}</p></div></div>`;
    return;
  }
  $('#view').innerHTML = `${hero}
    <div class="settings-stack">
      ${runnerNoticeHtml(data.runner)}
      ${run ? backupRunHtml(run) : ''}
      <div class="panel">
        <div class="panel__head"><h2 class="panel__title">${escapeHTML(B.listTitle)} <span class="tag">${data.archives.length}</span></h2></div>
        <div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr>
          <th>${escapeHTML(B.thDate)}</th><th>${escapeHTML(B.thFile)}</th><th class="right">${escapeHTML(B.thSize)}</th><th>${escapeHTML(B.thOffsite)}</th>
        </tr></thead><tbody>
        ${data.archives.length ? data.archives.map(backupArchiveRowHtml).join('')
          : `<tr><td colspan="4"><div class="empty"><p class="empty__title">${escapeHTML(B.emptyTitle)}</p><p class="empty__desc">${escapeHTML(B.emptyDesc)}</p></div></td></tr>`}
        </tbody></table></div>
      </div>
    </div>`;
  $('#backup-now')?.addEventListener('click', requestBackup);
  if (busy) backupPoll = setTimeout(() => { if (currentView === 'backups') renderBackups(); }, BACKUP_POLL_MS);
}

function announceBackupOutcome(run) {
  if (run && (run.state === 'requested' || run.state === 'running')) {
    watchedBackup = run.requestedAt;
    return;
  }
  if (watchedBackup && run?.requestedAt === watchedBackup && (run.state === 'succeeded' || run.state === 'failed')) {
    toast(run.state === 'succeeded' ? T.backups.doneToast : T.backups.failedToast);
  }
  watchedBackup = null;
}

function runnerNoticeHtml(runner) {
  if (runner?.online) return '';
  return warnNoticeHtml(runner?.lastSeenAt ? T.backups.runnerOffline({ at: fmtDate(runner.lastSeenAt) }) : T.backups.runnerMissing);
}

function backupRunHtml(run) {
  const B = T.backups;
  const by = run.requestedBy || '';
  const lines = {
    requested: () => B.requestedLine({ at: fmtDate(run.requestedAt), by }),
    running: () => B.runningLine({ at: fmtDate(run.startedAt), by }),
    succeeded: () => B.succeededLine({ at: fmtDate(run.finishedAt), archive: run.archive || '—' }),
    failed: () => B.failedLine({ at: fmtDate(run.finishedAt) }),
    stalled: () => B.stalledLine({ at: fmtDate(run.startedAt) }),
  };
  return `<div class="panel">
    <div class="panel__head"><h2 class="panel__title">${escapeHTML(B.lastTitle)}</h2><span class="pill ${BACKUP_STATE_PILLS[run.state] || 'pill--info'}">${escapeHTML(B.state[run.state] || run.state)}</span></div>
    <div class="panel__body">
      <p class="hint">${escapeHTML(lines[run.state]?.() || '')}</p>
      ${run.state === 'failed' && run.log ? `<pre class="log-tail">${escapeHTML(run.log)}</pre>` : ''}
    </div>
  </div>`;
}

function backupArchiveRowHtml(a) {
  const B = T.backups;
  return `<tr>
    <td>${escapeHTML(fmtDate(a.createdAt))}</td>
    <td class="id" ${dataLabel(B.thFile)}>${escapeHTML(a.name)}</td>
    <td class="num" ${dataLabel(B.thSize)}>${escapeHTML(formatBytes(a.sizeBytes))}</td>
    <td ${dataLabel(B.thOffsite)}>${a.uploaded ? `<span class="pill pill--ok">${escapeHTML(B.copied)}</span>` : `<span class="muted">${escapeHTML(B.notCopied)}</span>`}</td>
  </tr>`;
}

function formatBytes(bytes) {
  const units = ['byte', 'kilobyte', 'megabyte', 'gigabyte'];
  let value = Number(bytes) || 0;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
  return new Intl.NumberFormat(I18N.locale(), { style: 'unit', unit: units[unit], unitDisplay: 'short', maximumFractionDigits: unit === 0 ? 0 : 1 }).format(value);
}

async function requestBackup() {
  const B = T.backups;
  const button = $('#backup-now');
  if (button) button.disabled = true;
  try {
    await api('/admin/api/backups', { method: 'POST' });
    toast(B.requestedToast);
  } catch (e) {
    toast(e.code === 'backup_in_progress' ? B.busy : e.code === 'backups_unavailable' ? B.unavailableTitle : T.error({ msg: e.message }));
  }
  renderBackups();
}

function categoryLabel(category) {
  return T.platformSettings.categories?.[category] || category;
}

function formatUpdatedAt(iso) {
  if (!iso) return T.platformSettings.neverUpdated;
  return T.platformSettings.updatedAt({ at: fmtDate(iso) });
}

async function renderPlatformSettings() {
  if (!platformSettings.settings.length) {
    try {
      await loadPlatformSettings();
    } catch (e) {
      $('#view').innerHTML = `<div class="empty"><p class="empty__title">${escapeHTML(T.loadError)}</p><p class="empty__desc">${escapeHTML(e.message)}</p></div>`;
      return;
    }
  }
  const groups = platformSettings.settings.reduce((acc, s) => {
    (acc[s.category] ||= []).push(s);
    return acc;
  }, {});
  const order = ['whatsapp', 'instagram', 'google', 'openrouter', 'ratelimit', 'pdf', 'admin'];
  const categories = [...new Set([...order, ...Object.keys(groups)])].filter(c => groups[c]?.length);
  $('#view').innerHTML = `
    <div class="view__hero">
      <div>
        <h1 class="view__title">${escapeHTML(T.platformSettings.title)}</h1>
        <p class="view__desc">${escapeHTML(T.platformSettings.desc)}</p>
      </div>
      <div class="actions">
        <button class="btn btn--ghost" id="ps-reload" type="button">${escapeHTML(T.platformSettings.reload)}</button>
        <button class="btn btn--primary" id="ps-save" type="button">${escapeHTML(T.platformSettings.save)}</button>
      </div>
    </div>
    <div class="settings-stack">
      <p class="hint">${escapeHTML(formatUpdatedAt(platformSettings.updatedAt))}</p>
      ${categories.length === 0 ? `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(T.platformSettings.empty)}</p></div></div>` : categories.map(cat => `
        <div class="panel">
          <div class="panel__head"><h2 class="panel__title">${escapeHTML(categoryLabel(cat))}</h2></div>
          <div>
            ${groups[cat].map(settingRowHtml).join('')}
          </div>
        </div>`).join('')}
    </div>`;
  bindPlatformSettings();
}

function settingRowHtml(s) {
  const draft = platformSettings.drafts[s.key];
  const revealed = platformSettings.revealed[s.key];
  const willClear = platformSettings.clear.has(s.key);
  const value = draft ?? revealed ?? (s.secret ? '' : s.value);
  const placeholder = s.secret ? (s.hasValue ? s.value : '') : '';
  const sourcePill = willClear
    ? `<span class="pill pill--warn">${escapeHTML(T.platformSettings.sourceEnv)}</span>`
    : s.source === 'override'
      ? `<span class="pill pill--accent">${escapeHTML(T.platformSettings.sourceOverride)}</span>`
      : `<span class="pill pill--info">${escapeHTML(T.platformSettings.sourceEnv)}</span>`;
  const hint = s.key === 'ADMIN_PASSWORD_HASH'
    ? `<div class="hint">${escapeHTML(T.platformSettings.passwordHint)}</div>`
    : '';
  return `<div class="settings-row" data-setting-key="${escapeHTML(s.key)}">
    <div>
      <div class="settings-row__key">${escapeHTML(s.key)}</div>
      <div class="settings-row__meta">${sourcePill}${s.secret ? `<span class="pill pill--warn">${escapeHTML(T.platformSettings.secretBadge)}</span>` : ''}</div>
    </div>
    <div class="settings-row__controls">
      <input class="inp inp--mono" data-setting-input type="${s.secret && !revealed ? 'password' : 'text'}"
        value="${escapeHTML(value)}" placeholder="${escapeHTML(placeholder)}" autocomplete="off" spellcheck="false" />
      ${hint}
    </div>
    <div class="settings-row__actions">
      ${s.secret ? `<button class="btn btn--sm btn--ghost" type="button" data-reveal="${escapeHTML(s.key)}">${escapeHTML(revealed ? T.platformSettings.hide : T.platformSettings.reveal)}</button>` : ''}
      ${s.source === 'override' || willClear ? `<button class="btn btn--sm btn--ghost" type="button" data-clear="${escapeHTML(s.key)}">${escapeHTML(T.platformSettings.clear)}</button>` : ''}
    </div>
  </div>`;
}

function bindPlatformSettings() {
  $$('[data-setting-input]').forEach(input => {
    input.addEventListener('input', () => {
      const key = input.closest('[data-setting-key]')?.dataset.settingKey;
      if (!key) return;
      platformSettings.drafts[key] = input.value;
      platformSettings.clear.delete(key);
    });
  });
  $$('[data-reveal]').forEach(btn => btn.addEventListener('click', async () => {
    const key = btn.dataset.reveal;
    if (platformSettings.revealed[key] != null) {
      delete platformSettings.revealed[key];
      renderPlatformSettings();
      return;
    }
    try {
      const res = await api(`/admin/api/platform-settings/reveal?key=${encodeURIComponent(key)}`);
      platformSettings.revealed[key] = res.value || '';
      delete platformSettings.drafts[key];
      renderPlatformSettings();
    } catch (e) { toast(T.error({ msg: e.message })); }
  }));
  $$('[data-clear]').forEach(btn => btn.addEventListener('click', () => {
    const key = btn.dataset.clear;
    platformSettings.clear.add(key);
    delete platformSettings.drafts[key];
    delete platformSettings.revealed[key];
    renderPlatformSettings();
  }));
  $('#ps-reload')?.addEventListener('click', async () => {
    try {
      const payload = await api('/admin/api/platform-settings/reload', { method: 'POST' });
      applyPlatformSettingsPayload(payload);
      toast(T.platformSettings.reloaded);
      renderPlatformSettings();
    } catch (e) { toast(T.error({ msg: e.message })); }
  });
  $('#ps-save')?.addEventListener('click', async () => {
    const updates = {};
    Object.entries(platformSettings.drafts).forEach(([key, value]) => {
      if (value == null) return;
      if (String(value).trim() === '') return;
      updates[key] = value;
    });
    try {
      const payload = await api('/admin/api/platform-settings', {
        method: 'PUT',
        body: JSON.stringify({ updates, clear: [...platformSettings.clear] }),
      });
      applyPlatformSettingsPayload(payload);
      toast(T.platformSettings.saved);
      renderPlatformSettings();
    } catch (e) { toast(T.error({ msg: e.message })); }
  });
}

// A tenant's extra companies are tenants too; they list right under the tenant's first company.
const companiesOf = primaryId => state.tenants.filter(t => t.parentTenantId === primaryId && t.status !== 'DELETED');

// Deleted companies a deleted first company brings back when restored: they share its deletedAt.
const companiesDeletedWith = primary => state.tenants.filter(c =>
  c.parentTenantId === primary.id && c.status === 'DELETED' && (c.deletedAt || null) === (primary.deletedAt || null));

function tenantCompanyText(t) {
  if (t.parentTenantId) {
    const primary = state.tenants.find(p => p.id === t.parentTenantId);
    return T.companyOf({ name: primary?.name || t.parentTenantId });
  }
  if (t.status === 'DELETED') {
    const n = companiesDeletedWith(t).length;
    return n ? T.deletedWith({ n }) : '';
  }
  const used = 1 + companiesOf(t.id).length;
  return used > 1 || t.maxCompanies > 1 ? T.companiesTag({ used, limit: t.maxCompanies }) : '';
}

function tenantCompanyLine(t) {
  const text = tenantCompanyText(t);
  return text ? `<div class="muted">${escapeHTML(text)}</div>` : '';
}

// Tenants from before channel bindings only carry their WhatsApp number as phoneNumberId.
const tenantChannels = t => (t.channels?.length ? t.channels
  : t.phoneNumberId ? [{ platform: 'WHATSAPP', externalId: t.phoneNumberId, hasAccessToken: true }] : []);

const STATUS_FILTERS = ['all', 'ACTIVE', 'SUSPENDED', 'DELETED'];
const STATUS_PILLS = { ACTIVE: 'pill--ok', SUSPENDED: 'pill--warn', DELETED: 'pill--bad' };
// "All" leaves deleted tenants out: they only show under their own chip, ready to restore.
const inStatusFilter = (t, filter) => (filter === 'all' ? t.status !== 'DELETED' : t.status === filter);
const tenantSearchText = t => `${t.name} ${t.slug} ${t.phoneNumberId} ${(t.channels || []).map(c => `${c.platform} ${c.externalId}`).join(' ')}`.toLowerCase();

function renderTenants() {
  const q = state.search.trim().toLowerCase();
  const matching = state.tenants.filter(t => !q || tenantSearchText(t).includes(q));
  const counts = Object.fromEntries(STATUS_FILTERS.map(f => [f, matching.filter(t => inStatusFilter(t, f)).length]));
  const order = new Map(state.tenants.map((t, i) => [t.id, i]));
  const group = t => order.get(t.parentTenantId) ?? order.get(t.id);
  const rows = matching
    .filter(t => inStatusFilter(t, state.filter))
    .sort((a, b) => (group(a) - group(b)) || (Boolean(a.parentTenantId) - Boolean(b.parentTenantId)) || (order.get(a.id) - order.get(b.id)));
  renderSidebarStats();
  $('#view').innerHTML = `
    <div class="view__hero"><div><h1 class="view__title">${escapeHTML(T.heroTitle)}</h1><p class="view__desc">${escapeHTML(T.heroDesc)}</p></div></div>
    <div class="panel"><div class="panel__head"><h2 class="panel__title">${escapeHTML(T.botsTitle)} <span class="tag">${rows.length}</span></h2>
      <div class="panel__tools" role="group" aria-label="${escapeHTML(T.statusFilterAria)}">
        ${STATUS_FILTERS.map(f => `<button class="chip ${state.filter === f ? 'is-on' : ''}" type="button" data-status-filter="${f}" aria-pressed="${state.filter === f}">${escapeHTML(T.filters[f])}<span class="chip__count">${counts[f]}</span></button>`).join('')}
      </div></div>
      <div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr>
        <th>${escapeHTML(T.thName)}</th><th>${escapeHTML(T.thChannels)}</th><th>${escapeHTML(T.thStatus)}</th><th class="right">${escapeHTML(T.thMsgs)}</th><th>${escapeHTML(state.filter === 'DELETED' ? T.thDeletedAt : T.thLastActivity)}</th><th class="right">${escapeHTML(T.thActions)}</th>
      </tr></thead><tbody>
      ${rows.length === 0 ? `<tr><td colspan="6">${tenantsEmptyHtml(q, counts)}</td></tr>` : rows.map(tenantRowHtml).join('')}
      </tbody></table></div></div>`;
  bindTenantActions();
}

function tenantRowHtml(t) {
  const s = state.stats[t.slug] || {};
  const deleted = t.status === 'DELETED';
  return `<tr data-tenant="${escapeHTML(t.slug)}">
    <td class="name"><button class="tbl__open" type="button">${escapeHTML(t.name)}</button><div class="sub mono">${escapeHTML(t.slug)}</div>${tenantCompanyLine(t)}</td>
    <td ${dataLabel(T.thChannels)}>${channelBadges(t.channels)}</td>
    <td ${dataLabel(T.thStatus)}><span class="pill ${STATUS_PILLS[t.status] || 'pill--bad'}">${escapeHTML(T.status[t.status] || t.status)}</span></td>
    <td class="num" ${dataLabel(T.thMsgs)}>${s.messages ?? '—'}</td>
    <td class="mono muted" ${dataLabel(deleted ? T.thDeletedAt : T.thLastActivity)}>${dateCell(deleted ? (t.deletedAt || t.updatedAt) : s.lastMessageAt)}</td>
    <td class="right"><div class="actions">${deleted ? deletedTenantActions(t) : `<button class="btn btn--sm" type="button" data-open-dashboard="${escapeHTML(t.slug)}">${escapeHTML(T.openDashboard)}</button>`}</div></td>
  </tr>`;
}

// A deleted company can't come back while its first company is deleted: it either returns with it or waits for it.
function deletedTenantActions(t) {
  const primary = t.parentTenantId ? state.tenants.find(p => p.id === t.parentTenantId) : null;
  if (primary?.status === 'DELETED') {
    const withIt = (t.deletedAt || null) === (primary.deletedAt || null);
    return `<span class="muted">${escapeHTML(withIt ? T.comesBackWith({ name: primary.name }) : T.restoreParentFirst({ name: primary.name }))}</span>`;
  }
  return `<button class="btn btn--sm btn--accent" data-restore="${escapeHTML(t.slug)}">${escapeHTML(T.restore)}</button>`;
}

function tenantsEmptyHtml(q, counts) {
  const titles = { all: T.emptyTenants, ACTIVE: T.emptyActive, SUSPENDED: T.emptySuspended, DELETED: T.emptyDeleted };
  const title = q ? T.emptySearch({ q: state.search.trim() }) : titles[state.filter];
  const desc = !q && state.filter === 'DELETED' ? `<p class="empty__desc">${escapeHTML(T.emptyDeletedDesc)}</p>` : '';
  const elsewhere = q ? ['all', 'DELETED'].find(f => f !== state.filter && counts[f] > 0) : null;
  const jump = elsewhere
    ? `<button class="btn btn--sm" type="button" data-status-filter="${elsewhere}">${escapeHTML(T.showMatches({ n: counts[elsewhere], label: T.filters[elsewhere] }))}</button>`
    : '';
  return `<div class="empty"><p class="empty__title">${escapeHTML(title)}</p>${desc}${jump}</div>`;
}

async function setTenantFilter(filter) {
  state.filter = STATUS_FILTERS.includes(filter) ? filter : 'all';
  // Re-rendering replaces the chips; keep keyboard focus on the chip row, but never pull it from elsewhere.
  const rerender = () => {
    const active = document.activeElement;
    const onChips = !active || active === document.body || active.matches('[data-status-filter]');
    renderTenants();
    if (onChips) $(`.panel__tools [data-status-filter="${state.filter}"]`)?.focus();
  };
  rerender();
  if (state.filter === 'DELETED' && await loadDeletedStats() && state.filter === 'DELETED' && currentView === 'tenants') rerender();
}

function channelBadges(channels = []) {
  if (!channels.length) return '<span class="muted">—</span>';
  return `<div class="tbl__pills">${channels.map(c => {
    const cls = c.platform === 'INSTAGRAM' ? 'pill--accent' : 'pill--info';
    const token = c.hasAccessToken ? T.token : T.noToken;
    const label = c.displayName ? `${platformLabel(c.platform)} · ${c.platform === 'INSTAGRAM' ? '@' : ''}${c.displayName}` : platformLabel(c.platform);
    return `<span class="pill ${cls}" title="${escapeHTML(c.externalId)} · ${token}">${escapeHTML(label)}</span>`;
  }).join('')}</div>`;
}

function bindTenantActions() {
  const view = $('#view');
  $$('[data-open-dashboard]', view).forEach(b => b.addEventListener('click', () => openDashboard(b.dataset.openDashboard)));
  $$('[data-restore]', view).forEach(b => b.addEventListener('click', () => restoreTenant(b.dataset.restore)));
  $$('[data-status-filter]', view).forEach(b => b.addEventListener('click', () => setTenantFilter(b.dataset.statusFilter)));
  $$('tr[data-tenant]', view).forEach(tr => tr.addEventListener('click', e => {
    if (e.target.closest('button, a') && !e.target.closest('.tbl__open')) return;
    $('.tbl__open', tr).focus();
    tenantDrawer(tr.dataset.tenant);
  }));
}

async function openDashboard(slug) {
  try {
    const res = await api(`/admin/api/tenants/${encodeURIComponent(slug)}/impersonate`, { method: 'POST' });
    localStorage.setItem('dashboardToken', res.token);
    location.href = '/app/';
  } catch (e) { toast(T.error({ msg: e.message })); }
}

async function reloadPipeline(slug) {
  try {
    await api(`/admin/api/tenants/${encodeURIComponent(slug)}/reload`, { method: 'POST' });
    toast(T.pipelineReloaded);
  } catch (e) { toast(T.error({ msg: e.message })); }
}

// A tenant opens as a record, like a client in /app. Edit, Users and Agents open from it with a way back.
async function tenantDrawer(slug) {
  const t = state.tenants.find(x => x.slug === slug);
  if (!t) { closeDrawer(); return; }
  if (!state.stats[slug]) Object.assign(state.stats, await statsFor([t]));
  const R = T.record;
  const s = state.stats[slug] || {};
  const deleted = t.status === 'DELETED';
  const num = new Intl.NumberFormat(I18N.locale());
  const count = v => (v == null ? '—' : num.format(v));
  const last = s.lastMessageAt ? new Date(s.lastMessageAt) : null;
  const channels = tenantChannels(t);
  const initials = (t.name || t.slug).split(/\s+/).filter(Boolean).slice(0, 2).map(w => w[0]).join('').toUpperCase();
  const since = [
    tenantCompanyText(t),
    I18N.LANG_NAMES[t.locale] || t.locale,
    t.openrouterModel ? R.model({ model: t.openrouterModel }) : R.defaultModel,
    R.modulesCount({ on: selectedModulesFor(t).length, total: MODULES.length }),
  ].filter(Boolean).join(' · ');
  const kpi = (label, value, sub = '') => `<div class="record-kpi"><span class="record-kpi__label">${escapeHTML(label)}</span><span class="record-kpi__value">${escapeHTML(value)}</span>${sub ? `<span class="record-kpi__sub">${escapeHTML(sub)}</span>` : ''}</div>`;
  const channelText = c => [c.displayName ? `${c.platform === 'INSTAGRAM' ? '@' : ''}${c.displayName}` : '', c.externalId].filter(Boolean).join(' · ');
  const wrap = document.createElement('div');
  wrap.className = 'record';
  wrap.innerHTML = `
    <section class="record-card">
      <div class="record-card__head">
        <span class="record-card__avatar" aria-hidden="true">${escapeHTML(initials)}</span>
        <div class="record-card__who">
          <div class="record-card__lines"><span class="record-card__line mono">${escapeHTML(t.slug)}</span><span class="pill ${STATUS_PILLS[t.status] || 'pill--bad'}">${escapeHTML(T.status[t.status] || t.status)}</span></div>
          <p class="record-card__since">${escapeHTML(since)}</p>
        </div>
        <div class="actions">${deleted ? deletedTenantActions(t) : `
          <button class="btn btn--sm btn--ghost" type="button" data-act="edit">${escapeHTML(T.edit)}</button>
          ${t.status === 'ACTIVE'
            ? `<button class="btn btn--sm btn--ghost" type="button" data-act="suspend">${escapeHTML(T.suspend)}</button>`
            : `<button class="btn btn--sm btn--accent" type="button" data-act="activate">${escapeHTML(T.activate)}</button>`}
          <button class="btn btn--sm btn--ghost" type="button" data-act="delete">${escapeHTML(T.delete)}</button>`}
        </div>
      </div>
      ${deleted ? `<p class="hint hint--warn">${escapeHTML(R.deletedOn({ date: fmtDate(t.deletedAt || t.updatedAt) }))}</p>` : `
      <div class="record-card__contact">
        <button class="btn btn--sm" type="button" data-act="dashboard">${escapeHTML(T.openDashboard)}</button>
        ${t.parentTenantId ? '' : `<button class="btn btn--sm" type="button" data-act="users">${escapeHTML(T.users)}</button>`}
        ${(t.enabledModules || []).includes('agents') ? `<button class="btn btn--sm" type="button" data-act="agents">${escapeHTML(T.agents.action)}</button>` : ''}
        <button class="btn btn--sm btn--ghost" type="button" data-act="reload">${escapeHTML(R.reloadPipeline)}</button>
      </div>`}
    </section>
    <div class="record-kpis" data-count="4">
      ${kpi(T.kpiMessages, count(s.messages))}
      ${kpi(T.thLastActivity, last ? fmtDay(last) : '—', last ? fmtTime(last) : '')}
      ${kpi(T.ratePerHour, count(t.rateLimitPerHour))}
      ${kpi(T.ratePerDay, count(t.rateLimitPerDay))}
    </div>
    <section class="panel">
      <header class="panel__head"><h3 class="panel__title">${escapeHTML(T.channelsLabel)} <span class="tag">${channels.length}</span></h3></header>
      <div class="panel__body">${channels.length
        ? `<dl class="dash-facts">${channels.map(c => `<div><dt>${escapeHTML(platformLabel(c.platform))}</dt><dd>${escapeHTML(channelText(c))}${c.hasAccessToken ? '' : ` <span class="pill pill--warn">${escapeHTML(R.noToken)}</span>`}</dd></div>`).join('')}</dl>`
        : `<p class="dash-empty">${escapeHTML(T.noChannels)}</p>`}</div>
    </section>`;
  const back = { label: t.name, open: () => tenantDrawer(slug) };
  const on = (act, fn) => $(`[data-act="${act}"]`, wrap)?.addEventListener('click', fn);
  on('edit', () => tenantForm(t, back));
  on('users', () => usersDrawer(slug, back));
  on('agents', () => agentsDrawer(slug, back));
  on('dashboard', () => openDashboard(slug));
  on('reload', () => reloadPipeline(slug));
  on('suspend', async () => { if (await lifecycle(slug, 'suspend', T.suspendTitle, T.suspend)) tenantDrawer(slug); });
  on('activate', async () => { if (await lifecycle(slug, 'activate', T.activateTitle, T.activate, false)) tenantDrawer(slug); });
  on('delete', async () => { if (await deleteTenant(slug)) closeDrawer(); });
  $('[data-restore]', wrap)?.addEventListener('click', async () => { if (await restoreTenant(slug)) tenantDrawer(slug); });
  openDrawer({ title: t.name, body: wrap, autofocus: false });
}

async function usersDrawer(slug, back = null) {
  const users = await api(`/admin/api/tenants/${encodeURIComponent(slug)}/dashboard-users`);
  const tenant = state.tenants.find(t => t.slug === slug);
  const severalCompanies = tenant && (tenant.maxCompanies > 1 || companiesOf(tenant.id).length > 0);
  const wrap = document.createElement('div');
  wrap.className = 'form';
  wrap.innerHTML = `
    ${severalCompanies ? `<div class="hint">${escapeHTML(T.usersAllCompanies)}</div>` : ''}
    <div class="panel"><div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr><th>${escapeHTML(T.thEmail)}</th><th>${escapeHTML(T.thRole)}</th><th>${escapeHTML(T.thStatus)}</th><th class="right">${escapeHTML(T.thActions)}</th></tr></thead><tbody>
      ${users.length === 0 ? `<tr><td colspan="4"><div class="empty"><p class="empty__title">${escapeHTML(T.noUsers)}</p></div></td></tr>` : users.map(u => `<tr>
        <td class="name">${escapeHTML(u.email)}</td><td ${dataLabel(T.thRole)}>${escapeHTML(roleLabel(u.role))}</td><td ${dataLabel(T.thStatus)}><span class="pill ${u.status === 'ACTIVE' ? 'pill--ok' : ''}">${escapeHTML(T.userStatus[u.status] || u.status)}</span></td>
        <td class="right">${u.status === 'ACTIVE' ? `<button class="btn btn--sm btn--ghost" data-disable-user="${u.id}">${escapeHTML(T.disable)}</button>` : `<button class="btn btn--sm btn--accent" data-activate-user="${u.id}">${escapeHTML(T.activate)}</button>`}</td>
      </tr>`).join('')}
    </tbody></table></div></div>
    <div class="form__grid">
      <div class="form__row"><label class="lbl" for="u-email">${escapeHTML(T.emailLabel)}</label><input class="inp" id="u-email" type="email" /></div>
      <div class="form__row"><label class="lbl" for="u-password">${escapeHTML(T.tempPassword)}</label><input class="inp" id="u-password" type="password" /></div>
      <div class="form__row"><label class="lbl" for="u-role">${escapeHTML(T.roleLabel)}</label><select class="sel" id="u-role">${['TENANT_ADMIN', 'TENANT_MEMBER'].map(r => `<option value="${r}">${escapeHTML(roleLabel(r))}</option>`).join('')}</select></div>
    </div>`;
  openDrawer({
    title: T.usersTitle({ slug }),
    body: wrap,
    saveLabel: T.createUser,
    back,
    async onSave() {
      const email = $('#u-email', wrap).value.trim();
      const password = $('#u-password', wrap).value;
      const role = $('#u-role', wrap).value;
      if (!email || !password) { toast(T.emailPasswordRequired); return false; }
      await api(`/admin/api/tenants/${encodeURIComponent(slug)}/dashboard-users`, { method: 'POST', body: JSON.stringify({ email, password, role }) });
      toast(T.userCreated);
    },
  });
  $$('[data-disable-user]', wrap).forEach(b => b.addEventListener('click', async () => { await api(`/admin/api/tenants/${encodeURIComponent(slug)}/dashboard-users/${b.dataset.disableUser}/disable`, { method: 'POST' }); toast(T.userDisabled); usersDrawer(slug, back); }));
  $$('[data-activate-user]', wrap).forEach(b => b.addEventListener('click', async () => { await api(`/admin/api/tenants/${encodeURIComponent(slug)}/dashboard-users/${b.dataset.activateUser}/activate`, { method: 'POST' }); toast(T.userActivated); usersDrawer(slug, back); }));
}

const AGENT_PILLS = { ACTIVE: 'pill--ok', PAUSED: 'pill--warn' };
const RUN_GROUPS = { succeeded: ['SUCCEEDED'], failed: ['FAILED', 'NEEDS_REVIEW'], open: ['QUEUED', 'RUNNING', 'WAITING', 'AWAITING_APPROVAL'] };

function agentAdminRowHtml(a, num) {
  const A = T.agents;
  const reason = a.status === 'PAUSED' && a.pausedReason ? A.pausedReasons[a.pausedReason] || '' : '';
  return `<tr>
    <td class="name">${escapeHTML(a.name)}${reason ? `<div class="muted">${escapeHTML(reason)}</div>` : ''}</td>
    <td ${dataLabel(A.thStatus)}><span class="pill ${AGENT_PILLS[a.status] || ''}">${escapeHTML(I18N.t(`app.agents.status.${a.status}`))}</span></td>
    <td class="num" ${dataLabel(A.thRuns)}>${num.format(a.stats?.runs || 0)}</td>
    <td class="num" ${dataLabel(A.thFailed)}>${num.format(a.stats?.failed || 0)}</td>
    <td class="mono muted" ${dataLabel(A.thLastRun)}>${fmtDate(a.stats?.lastRunAt)}</td>
  </tr>`;
}

async function agentsDrawer(slug, back = null) {
  const A = T.agents;
  const name = state.tenants.find(t => t.slug === slug)?.name || slug;
  const base = `/admin/api/tenants/${encodeURIComponent(slug)}/agents`;
  let data;
  try { data = await api(base); } catch (e) { toast(T.error({ msg: e.message })); return; }
  const s = data.settings;
  const num = new Intl.NumberFormat(I18N.locale());
  const runs = data.runs7d || {};
  const count = statuses => statuses.reduce((n, status) => n + (runs[status] || 0), 0);
  const [title, desc] = s.agentsPaused ? [A.pausedHere, A.pausedHereDesc] : s.companyPaused ? [A.pausedByCompany, A.pausedByCompanyDesc] : [A.running, A.runningDesc];
  const wrap = document.createElement('div');
  wrap.className = 'form';
  wrap.innerHTML = `
    <div class="notice ${s.agentsPaused || s.companyPaused ? 'notice--warn' : ''}" role="status">
      <div class="notice__text"><strong>${escapeHTML(title)}</strong><span>${escapeHTML(desc)}</span></div>
      <div class="notice__actions"><button class="btn btn--sm ${s.agentsPaused ? 'btn--accent' : 'btn--danger'}" type="button" data-agents-pause>${escapeHTML(s.agentsPaused ? A.resume : A.pause)}</button></div>
    </div>
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(A.weekTitle)}</h2></header>
      <div class="panel__body"><dl class="dash-facts">
        <div><dt>${escapeHTML(A.runs)}</dt><dd>${num.format(Object.values(runs).reduce((n, v) => n + v, 0))}</dd></div>
        <div><dt>${escapeHTML(A.succeeded)}</dt><dd>${num.format(count(RUN_GROUPS.succeeded))}</dd></div>
        <div><dt>${escapeHTML(A.failed)}</dt><dd>${num.format(count(RUN_GROUPS.failed))}</dd></div>
        <div><dt>${escapeHTML(A.open)}</dt><dd>${num.format(count(RUN_GROUPS.open))}</dd></div>
        <div><dt>${escapeHTML(A.pendingApprovals)}</dt><dd>${num.format(data.pendingApprovals || 0)}</dd></div>
      </dl></div>
    </section>
    <div class="panel"><div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr>
      <th>${escapeHTML(A.thAgent)}</th><th>${escapeHTML(A.thStatus)}</th><th class="right">${escapeHTML(A.thRuns)}</th><th class="right">${escapeHTML(A.thFailed)}</th><th>${escapeHTML(A.thLastRun)}</th>
    </tr></thead><tbody>
      ${data.agents.length === 0 ? `<tr><td colspan="5"><div class="empty"><p class="empty__title">${escapeHTML(A.noAgents)}</p></div></td></tr>` : data.agents.map(a => agentAdminRowHtml(a, num)).join('')}
    </tbody></table></div></div>
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(A.limitsTitle)}</h2></header>
      <div class="panel__body">
        <div class="form__grid">
          <div class="form__row"><label class="lbl" for="ag-max">${escapeHTML(A.maxActive)}</label><input class="inp inp--mono" id="ag-max" type="number" min="0" max="500" value="${s.maxActiveAgents}" /></div>
          <div class="form__row"><label class="lbl" for="ag-runs">${escapeHTML(A.runsPerDay)}</label><input class="inp inp--mono" id="ag-runs" type="number" min="0" max="100000" value="${s.runsPerDay}" /></div>
          <div class="form__row"><label class="lbl" for="ag-emails">${escapeHTML(A.emailsPerDay)}</label><input class="inp inp--mono" id="ag-emails" type="number" min="0" max="2000" value="${s.emailSendsPerDay}" /></div>
        </div>
        <p class="hint">${escapeHTML(A.limitsHint)}</p>
      </div>
    </section>`;
  openDrawer({
    title: A.title({ name }),
    body: wrap,
    saveLabel: A.saveLimits,
    autofocus: false,
    back,
    async onSave() {
      // A cleared field keeps its limit: an empty input must not read as 0, which allows none.
      const limit = id => {
        const raw = $(id, wrap).value.trim();
        const value = Number(raw);
        return raw === '' || !Number.isFinite(value) || value < 0 ? null : Math.floor(value);
      };
      const body = { maxActiveAgents: limit('#ag-max'), runsPerDay: limit('#ag-runs'), emailSendsPerDay: limit('#ag-emails') };
      try {
        await api(`${base}/limits`, { method: 'PUT', body: JSON.stringify(body) });
        toast(A.limitsSaved);
      } catch (e) { toast(T.error({ msg: e.message })); return false; }
    },
  });
  $('[data-agents-pause]', wrap).addEventListener('click', async () => {
    const pausing = !s.agentsPaused;
    if (pausing && !await confirmDialog({ title: A.pauseTitle, body: A.pauseBody({ name }), okLabel: A.pause })) return;
    try {
      await api(`${base}/${pausing ? 'pause' : 'resume'}`, { method: 'POST' });
      toast(pausing ? A.pausedToast({ name }) : A.resumedToast({ name }));
      agentsDrawer(slug, back);
    } catch (e) { toast(T.error({ msg: e.message })); }
  });
}

function tenantForm(editing, back = null) {
  const wrap = document.createElement('form');
  wrap.className = 'form';
  wrap.innerHTML = `
    <div class="form__grid">
      <div class="form__row form__row--full"><label class="lbl">${escapeHTML(T.tenantNameLabel)}</label><input class="inp" id="t-name" value="${escapeHTML(editing?.name || '')}" /></div>
      <div class="form__row"><label class="lbl">${escapeHTML(T.tenantSlugLabel)}</label><input class="inp inp--mono" id="t-slug" value="${escapeHTML(editing?.slug || '')}" ${editing ? 'readonly' : ''} /></div>
      <div class="form__row"><label class="lbl">${escapeHTML(T.languageLabel)}</label><select class="sel" id="t-locale">${I18N.SUPPORTED.map(l => `<option value="${l}" ${(editing?.locale || I18N.DEFAULT) === l ? 'selected' : ''}>${escapeHTML(I18N.LANG_NAMES[l] || l)}</option>`).join('')}</select></div>
      <div class="form__row"><label class="lbl">${escapeHTML(T.modelOverride)}</label><input class="inp inp--mono" id="t-model" value="${escapeHTML(editing?.openrouterModel || '')}" placeholder="${escapeHTML(T.modelPlaceholder)}" /></div>
      <div class="form__row"><label class="lbl">${escapeHTML(T.ratePerHour)}</label><input class="inp inp--mono" id="t-hour" type="number" value="${editing?.rateLimitPerHour || 30}" /></div>
      <div class="form__row"><label class="lbl">${escapeHTML(T.ratePerDay)}</label><input class="inp inp--mono" id="t-day" type="number" value="${editing?.rateLimitPerDay || 200}" /></div>
      ${editing?.parentTenantId ? '' : `<div class="form__row form__row--full"><label class="lbl" for="t-companies">${escapeHTML(T.maxCompaniesLabel)}</label><input class="inp inp--mono" id="t-companies" type="number" min="1" max="20" value="${editing?.maxCompanies || 1}" /><div class="hint">${escapeHTML(T.maxCompaniesHint)}</div></div>`}
    </div>
    <div class="form__row">
      <div class="form__row-head">
        <span class="lbl" id="modules-label">${escapeHTML(T.modulesLabel)}</span>
        <div class="actions">
          <button class="btn btn--sm btn--ghost" type="button" id="modules-all">${escapeHTML(T.selectAllModules)}</button>
          <button class="btn btn--sm btn--ghost" type="button" id="modules-none">${escapeHTML(T.selectNoModules)}</button>
        </div>
      </div>
      <div class="panel"><div class="panel__body"><div class="form__checks" id="modules-box" role="group" aria-labelledby="modules-label"></div></div></div>
      <div class="hint">${escapeHTML(T.modulesHint)}</div>
    </div>
    <div class="form__row">
      <div class="form__row-head">
        <span class="lbl">${escapeHTML(T.channelsLabel)}</span>
        <div class="actions">
          ${editing && state.whatsAppSignup.enabled ? `<button class="btn btn--sm btn--ghost" type="button" id="wa-connect">${escapeHTML(T.connectWhatsApp)}</button>` : ''}
          ${editing ? `<button class="btn btn--sm btn--ghost" type="button" id="ig-connect">${escapeHTML(T.connectInstagram)}</button>` : ''}
          <button class="btn btn--sm btn--ghost" type="button" id="add-channel">${escapeHTML(T.addChannel)}</button>
        </div>
      </div>
      <div class="lines lines--channels" id="channels-box">
        <div class="lines__head"><span>${escapeHTML(T.colPlatform)}</span><span>${escapeHTML(T.colExternalId)}</span><span>${escapeHTML(T.colAccessToken)}</span><span></span></div>
        <div id="channels-body"></div>
      </div>
      <div class="hint">${escapeHTML(T.channelsHint)}</div>
    </div>`;
  // A new tenant starts with no channel row: bindings are optional and can be connected later.
  const existingChannels = editing ? tenantChannels(editing) : [];
  existingChannels.forEach(c => addChannelRow(wrap, c));
  renderChannelsEmpty(wrap);
  renderModulesBox(wrap, editing);
  $('#add-channel', wrap).addEventListener('click', () => addChannelRow(wrap));
  $('#modules-all', wrap).addEventListener('click', () => setAllModules(wrap, true));
  $('#modules-none', wrap).addEventListener('click', () => setAllModules(wrap, false));
  $('#wa-connect', wrap)?.addEventListener('click', () => connectWhatsApp(editing.slug));
  $('#ig-connect', wrap)?.addEventListener('click', () => connectInstagram(editing.slug));
  if (!editing) {
    let touched = false;
    $('#t-slug', wrap).addEventListener('input', () => { touched = true; });
    $('#t-name', wrap).addEventListener('input', () => { if (!touched) $('#t-slug', wrap).value = slugify($('#t-name', wrap).value); });
  }
  openDrawer({
    title: editing ? T.editTenant({ slug: editing.slug }) : T.newTenant,
    body: wrap,
    saveLabel: editing ? T.saveChanges : T.createTenant,
    back,
    async onSave() {
      const payload = {
        name: $('#t-name', wrap).value.trim(),
        locale: $('#t-locale', wrap).value,
        openrouterModel: $('#t-model', wrap).value.trim() || null,
        rateLimitPerHour: Number($('#t-hour', wrap).value || 30),
        rateLimitPerDay: Number($('#t-day', wrap).value || 200),
        channels: collectChannels(wrap, Boolean(editing)),
        enabledModules: collectModules(wrap),
      };
      const maxCompanies = $('#t-companies', wrap);
      if (maxCompanies) payload.maxCompanies = Number(maxCompanies.value || 1);
      if (!payload.name) { toast(T.nameRequired); return false; }
      if (payload.channels.some(c => c.platform === 'INSTAGRAM' && !editing && !c.accessToken)) { toast(T.igNeedsToken); return false; }
      try {
        let created = null;
        if (editing) {
          await api(`/admin/api/tenants/${encodeURIComponent(editing.slug)}`, { method: 'PUT', body: JSON.stringify(payload) });
          toast(T.tenantUpdated);
        } else {
          const slug = $('#t-slug', wrap).value.trim();
          created = (await api('/admin/api/tenants', { method: 'POST', body: JSON.stringify({ ...payload, slug }) }))?.slug || slug;
          toast(T.botCreated);
        }
        await loadAll();
        renderTenants();
        if (created) return () => tenantDrawer(created);
      } catch (e) {
        const slug = $('#t-slug', wrap).value.trim();
        if (e.code === 'slug_taken') toast(e.detail === 'DELETED' ? T.slugTakenDeleted({ slug }) : T.slugTaken({ slug }));
        else toast(T.error({ msg: e.message }));
        return false;
      }
    },
  });
}

function selectedModulesFor(editing) {
  const selected = new Set(editing?.effectiveModules || MODULES.filter(m => !m.optIn).map(m => m.id));
  return MODULES.filter(m => m.always || selected.has(m.id)).map(m => m.id);
}

function renderModulesBox(root, editing) {
  const selected = new Set(selectedModulesFor(editing));
  $('#modules-box', root).innerHTML = MODULES.map(m => `<label class="form__check">
      <input type="checkbox" data-module="${m.id}" ${selected.has(m.id) ? 'checked' : ''} ${m.always ? 'disabled' : ''} /> ${escapeHTML(I18N.t('common.nav.' + m.id))}
    </label>`).join('');
}

function collectModules(root) {
  return $$('[data-module]', root).filter(input => input.checked || input.disabled).map(input => input.dataset.module);
}

function setAllModules(root, checked) {
  $$('[data-module]', root).filter(input => !input.disabled).forEach(input => { input.checked = checked; });
}

// Channels are optional — show a placeholder instead of an empty table when there is no binding.
function renderChannelsEmpty(root) {
  const body = $('#channels-body', root);
  const empty = $('#channels-empty', root);
  if (body.children.length) { empty?.remove(); return; }
  if (empty) return;
  const el = document.createElement('div');
  el.id = 'channels-empty';
  el.className = 'lines__empty';
  el.textContent = T.noChannels;
  body.parentElement.appendChild(el);
}

function addChannelRow(root, channel = { platform: 'WHATSAPP', externalId: '', hasAccessToken: false }) {
  const row = document.createElement('div');
  row.className = 'line channel-row';
  row.innerHTML = `
    <select class="sel" data-channel-platform aria-label="${escapeHTML(T.colPlatform)}">
      ${['WHATSAPP', 'INSTAGRAM'].map(p => `<option value="${p}" ${channel.platform === p ? 'selected' : ''}>${escapeHTML(platformLabel(p))}</option>`).join('')}
    </select>
    <input class="mono" data-channel-external value="${escapeHTML(channel.externalId || '')}" placeholder="${escapeHTML(T.chExternalPlaceholder)}" aria-label="${escapeHTML(T.colExternalId)}" />
    <input class="mono" data-channel-token type="password" placeholder="${channel.hasAccessToken ? escapeHTML(T.chTokenUnchanged) : escapeHTML(T.chTokenPlaceholder)}" aria-label="${escapeHTML(T.colAccessToken)}" />
    <button class="l-rm" type="button" aria-label="${escapeHTML(T.removeChannel)}">×</button>
  `;
  row.querySelector('.l-rm').addEventListener('click', () => { row.remove(); renderChannelsEmpty(root); });
  $('#channels-body', root).appendChild(row);
  renderChannelsEmpty(root);
}

function collectChannels(root, editing) {
  return $$('.channel-row', root).map(row => ({
    platform: row.querySelector('[data-channel-platform]').value,
    externalId: row.querySelector('[data-channel-external]').value.trim(),
    accessToken: row.querySelector('[data-channel-token]').value.trim(),
  })).filter(c => c.externalId);
}

async function connectInstagram(slug) {
  let res;
  try {
    res = await api(`/admin/api/tenants/${encodeURIComponent(slug)}/instagram/connect`);
  } catch (e) {
    toast(e.message === 'unauthorized' ? T.igSessionExpired : T.igNotConfigured);
    return;
  }
  const popup = window.open(res.authorizeUrl, 'ig-oauth', 'width=600,height=750');
  if (!popup) { toast(T.igAllowPopups); return; }
  const onMessage = async ev => {
    if (ev.origin !== window.location.origin || ev.data?.type !== 'ig-oauth') return;
    window.removeEventListener('message', onMessage);
    if (ev.data.status === 'connected') {
      toast(T.igConnected);
      await loadAll();
      renderTenants();
    } else {
      toast(T.igFailed({ reason: ev.data.reason }));
    }
  };
  window.addEventListener('message', onMessage);
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

async function connectWhatsApp(slug) {
  const cfg = state.whatsAppSignup;
  if (!cfg?.enabled) { toast(T.waNotConfigured); return; }
  try {
    const FB = await loadFacebookSdk(cfg.appId, cfg.graphVersion || 'v21.0');
    toast(T.waOpenPopup);
    const sessionPromise = waitForWhatsAppSignupMessage();
    const codePromise = facebookLoginForBusiness(FB, cfg.configId);
    const [session, code] = await Promise.all([sessionPromise, codePromise]);
    await api(`/admin/api/tenants/${encodeURIComponent(slug)}/whatsapp/connect`, {
      method: 'POST',
      body: JSON.stringify({ code, wabaId: session.wabaId, phoneNumberId: session.phoneNumberId }),
    });
    toast(T.waConnected);
    await loadAll();
    renderTenants();
  } catch (e) {
    const messages = {
      signup_cancelled: T.waCancelled,
      missing_code: T.waMissingCode,
      signup_message_timeout: T.waTimeout,
      facebook_sdk_load_failed: T.waSdkFailed,
    };
    toast(messages[e.message] || T.waFailed({ msg: e.message }));
  }
}

// When the OAuth popup lands back on /backoffice/?ig=..., relay the outcome to the opener and close.
// Returns true if this load was an OAuth popup (so the normal app boot is skipped).
function handleOAuthPopup() {
  const params = new URLSearchParams(window.location.search);
  const ig = params.get('ig');
  if (!ig || !window.opener) return false;
  window.opener.postMessage({ type: 'ig-oauth', status: ig, reason: params.get('reason'), tenant: params.get('tenant') }, window.location.origin);
  window.close();
  return true;
}

// Suspending, activating or deleting a tenant's first company does the same to its other companies.
function otherCompaniesNote(slug) {
  const tenant = state.tenants.find(t => t.slug === slug);
  const n = tenant && !tenant.parentTenantId ? companiesOf(tenant.id).length : 0;
  return n > 0 ? ` ${T.cascadeNote({ n })}` : '';
}

// Resolves false when the confirm is cancelled; after that the list is refreshed either way.
async function lifecycle(slug, action, title, okLabel, danger = true) {
  if (!await confirmDialog({ title, body: T.tenantLine({ slug }) + otherCompaniesNote(slug), okLabel, danger })) return false;
  try {
    await api(`/admin/api/tenants/${encodeURIComponent(slug)}/${action}`, { method: 'POST' });
  } catch (e) {
    toast(e.code === 'tenant_deleted' ? T.tenantDeleted : T.error({ msg: e.message }));
  }
  await loadAll();
  renderTenants();
  return true;
}

// Resolves true only when the tenant was deleted.
async function deleteTenant(slug) {
  const name = state.tenants.find(t => t.slug === slug)?.name || slug;
  if (!await confirmDialog({ title: T.deleteTitle, body: T.deleteBody({ name, slug }) + otherCompaniesNote(slug), okLabel: T.delete })) return false;
  let ok = false;
  try {
    await api(`/admin/api/tenants/${encodeURIComponent(slug)}`, { method: 'DELETE' });
    toast(T.deleted({ name }));
    ok = true;
  } catch (e) {
    toast(T.error({ msg: e.message }));
  }
  await loadAll();
  renderTenants();
  return ok;
}

async function restoreTenant(slug) {
  const tenant = state.tenants.find(t => t.slug === slug);
  if (!tenant) return false;
  const n = tenant.parentTenantId ? 0 : companiesDeletedWith(tenant).length;
  const body = T.restoreBody({ name: tenant.name, slug }) + (n ? ` ${T.restoreCompaniesNote({ n })}` : '');
  if (!await confirmDialog({ title: T.restoreTitle, body, okLabel: T.restore, danger: false })) return false;
  let ok = false;
  try {
    await api(`/admin/api/tenants/${encodeURIComponent(slug)}/restore`, { method: 'POST' });
    toast(T.restored({ name: tenant.name }));
    ok = true;
  } catch (e) {
    const primary = state.tenants.find(p => p.id === tenant.parentTenantId);
    const messages = {
      parent_deleted: () => T.restoreParentFirst({ name: primary?.name || '' }),
      company_limit: () => T.restoreCompanyLimit({ name: primary?.name || '', limit: primary?.maxCompanies ?? '' }),
      not_deleted: () => T.notDeleted({ name: tenant.name }),
    };
    toast(messages[e.code]?.() || T.error({ msg: e.message }));
  }
  await loadAll();
  renderTenants();
  return ok;
}

async function init() {
  if (handleOAuthPopup()) return;
  I18N.applyDom(document);
  $('#btn-new').addEventListener('click', () => tenantForm());
  $('#btn-logout').addEventListener('click', () => { localStorage.removeItem('adminToken'); token = ''; renderLogin(); });
  $('#search').addEventListener('input', e => { state.search = e.target.value; if (currentView === 'tenants') renderTenants(); });
  $$('.nav__item[data-view]').forEach(a => a.addEventListener('click', e => {
    e.preventDefault();
    setView(a.dataset.view);
  }));
  window.addEventListener('hashchange', () => {
    if (!token) return;
    const view = location.hash.replace(/^#/, '') || 'tenants';
    if (view !== currentView) setView(view);
  });
  document.addEventListener('keydown', e => {
    if (e.key === '/' && !['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName) && currentView === 'tenants') {
      e.preventDefault();
      $('#search').focus();
    }
    // A confirm opened from a drawer closes alone, as a cancel, so the drawer behind it stays.
    if (e.key === 'Escape') {
      const confirm = $('#confirm');
      if (!confirm.hidden) $('[data-confirm-cancel]', confirm).click();
      else closeDrawer();
    }
  });
  if (!token) return renderLogin();
  try {
    await loadAll();
    const view = location.hash.replace(/^#/, '') || 'tenants';
    setView(view);
  } catch (e) {
    if (token) $('#view').innerHTML = `<div class="empty"><p class="empty__title">${escapeHTML(T.loadError)}</p><p class="empty__desc">${escapeHTML(e.message)}</p></div>`;
  }
}

init();
