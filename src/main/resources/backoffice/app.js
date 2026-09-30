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

function openDrawer({ title, body, onSave, saveLabel = T.save }) {
  const root = $('#drawer');
  $('#drawer-title').textContent = title;
  const host = $('#drawer-body');
  host.innerHTML = '';
  host.appendChild(body);
  const foot = document.createElement('div');
  foot.className = 'drawer__foot';
  foot.innerHTML = `<button class="btn btn--ghost" data-close>${escapeHTML(T.cancel)}</button><button class="btn btn--accent" id="drawer-save">${escapeHTML(saveLabel)}</button>`;
  host.appendChild(foot);
  root.hidden = false;
  const close = () => { root.hidden = true; $$('[data-close]', root).forEach(b => b.removeEventListener('click', close)); };
  $$('[data-close]', root).forEach(b => b.addEventListener('click', close));
  $('#drawer-save').addEventListener('click', async () => {
    const ok = await onSave?.();
    if (ok !== false) close();
  });
  setTimeout(() => host.querySelector('input,select,textarea')?.focus(), 50);
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

async function startSession(newToken) {
  token = newToken;
  localStorage.setItem('adminToken', token);
  await loadAll();
  renderTenants();
}

async function renderLogin() {
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
      ${data.googleReady ? '' : `<p class="hint hint--warn">${escapeHTML(A.googleNotReady)}</p>`}
      <div class="panel">
        <div class="panel__head"><h2 class="panel__title">${escapeHTML(A.listTitle)} <span class="tag">${data.admins.length}</span></h2></div>
        <div class="tbl-wrap"><table class="tbl"><thead><tr>
          <th>${escapeHTML(A.thEmail)}</th><th>${escapeHTML(A.thSource)}</th><th>${escapeHTML(A.thAdded)}</th><th class="right">${escapeHTML(T.thActions)}</th>
        </tr></thead><tbody>${data.admins.map(a => adminRowHtml(a, data.me)).join('')}</tbody></table></div>
      </div>
      <div class="panel">
        <div class="panel__head"><h2 class="panel__title">${escapeHTML(A.addTitle)}</h2></div>
        <div class="panel__body">
          <form class="form" id="admin-form" novalidate>
            <div class="form__row"><label class="lbl" for="admin-email">${escapeHTML(A.emailLabel)}</label><input class="inp" id="admin-email" type="email" autocomplete="off" spellcheck="false" placeholder="${escapeHTML(A.emailPlaceholder)}" /></div>
            <p class="hint">${escapeHTML(A.addHint)}</p>
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
    <td><span class="pill ${fromEnv ? 'pill--info' : 'pill--accent'}">${escapeHTML(fromEnv ? A.sourceEnv : A.sourceBackoffice)}</span></td>
    <td class="muted">${a.addedAt ? escapeHTML(A.addedBy({ date: fmtDate(a.addedAt), by: a.addedBy })) : '—'}</td>
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
    ${data.available ? `<div class="row"><button class="btn btn--primary" type="button" id="backup-now" ${busy ? 'disabled' : ''}>${escapeHTML(busy ? B.inProgress : B.backupNow)}</button></div>` : ''}</div>`;
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
        <div class="tbl-wrap"><table class="tbl"><thead><tr>
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
  const text = runner?.lastSeenAt ? T.backups.runnerOffline({ at: fmtDate(runner.lastSeenAt) }) : T.backups.runnerMissing;
  return `<p class="hint hint--warn">${escapeHTML(text)}</p>`;
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
  return `<tr>
    <td>${escapeHTML(fmtDate(a.createdAt))}</td>
    <td class="id">${escapeHTML(a.name)}</td>
    <td class="num">${escapeHTML(formatBytes(a.sizeBytes))}</td>
    <td>${a.uploaded ? `<span class="pill pill--ok">${escapeHTML(T.backups.copied)}</span>` : `<span class="muted">${escapeHTML(T.backups.notCopied)}</span>`}</td>
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
  const order = ['whatsapp', 'instagram', 'openrouter', 'ratelimit', 'pdf', 'admin'];
  const categories = [...new Set([...order, ...Object.keys(groups)])].filter(c => groups[c]?.length);
  $('#view').innerHTML = `
    <div class="view__hero">
      <div>
        <h1 class="view__title">${escapeHTML(T.platformSettings.title)}</h1>
        <p class="view__desc">${escapeHTML(T.platformSettings.desc)}</p>
      </div>
      <div class="row" style="gap:8px; flex-wrap:wrap">
        <button class="btn btn--ghost" id="ps-reload" type="button">${escapeHTML(T.platformSettings.reload)}</button>
        <button class="btn btn--primary" id="ps-save" type="button">${escapeHTML(T.platformSettings.save)}</button>
      </div>
    </div>
    <p class="hint" style="margin:0 0 12px">${escapeHTML(formatUpdatedAt(platformSettings.updatedAt))}</p>
    <div class="settings-stack">
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

function tenantCompanyLine(t) {
  if (t.parentTenantId) {
    const primary = state.tenants.find(p => p.id === t.parentTenantId);
    return `<div class="muted">${escapeHTML(T.companyOf({ name: primary?.name || t.parentTenantId }))}</div>`;
  }
  if (t.status === 'DELETED') {
    const n = companiesDeletedWith(t).length;
    return n ? `<div class="muted">${escapeHTML(T.deletedWith({ n }))}</div>` : '';
  }
  const used = 1 + companiesOf(t.id).length;
  return used > 1 || t.maxCompanies > 1 ? `<div class="muted">${escapeHTML(T.companiesTag({ used, limit: t.maxCompanies }))}</div>` : '';
}

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
      <div class="tbl-wrap"><table class="tbl"><thead><tr>
        <th>${escapeHTML(T.thName)}</th><th>${escapeHTML(T.thSlug)}</th><th>${escapeHTML(T.thChannels)}</th><th>${escapeHTML(T.thStatus)}</th><th class="right">${escapeHTML(T.thMsgs)}</th><th>${escapeHTML(state.filter === 'DELETED' ? T.thDeletedAt : T.thLastActivity)}</th><th class="right">${escapeHTML(T.thActions)}</th>
      </tr></thead><tbody>
      ${rows.length === 0 ? `<tr><td colspan="7">${tenantsEmptyHtml(q, counts)}</td></tr>` : rows.map(tenantRowHtml).join('')}
      </tbody></table></div></div>`;
  bindTenantActions();
}

function tenantRowHtml(t) {
  const s = state.stats[t.slug] || {};
  const deleted = t.status === 'DELETED';
  return `<tr>
    <td class="name">${escapeHTML(t.name)}${tenantCompanyLine(t)}</td>
    <td class="id">${escapeHTML(t.slug)}</td>
    <td>${channelBadges(t.channels)}</td>
    <td><span class="pill ${STATUS_PILLS[t.status] || 'pill--bad'}">${escapeHTML(T.status[t.status] || t.status)}</span></td>
    <td class="num">${s.messages ?? '—'}</td>
    <td class="mono muted">${fmtDate(deleted ? (t.deletedAt || t.updatedAt) : s.lastMessageAt)}</td>
    <td class="right"><div class="actions">${deleted ? deletedTenantActions(t) : liveTenantActions(t)}</div></td>
  </tr>`;
}

function liveTenantActions(t) {
  const slug = escapeHTML(t.slug);
  return `
    <button class="btn btn--sm" data-open-dashboard="${slug}">${escapeHTML(T.openDashboard)}</button>
    ${t.parentTenantId ? '' : `<button class="btn btn--sm btn--ghost" data-users="${slug}">${escapeHTML(T.users)}</button>`}
    <button class="btn btn--sm btn--ghost" data-edit="${slug}">${escapeHTML(T.edit)}</button>
    ${t.status === 'ACTIVE' ? `<button class="btn btn--sm btn--ghost" data-suspend="${slug}">${escapeHTML(T.suspend)}</button>` : `<button class="btn btn--sm btn--accent" data-activate="${slug}">${escapeHTML(T.activate)}</button>`}
    <button class="btn btn--sm btn--ghost" data-reload="${slug}">${escapeHTML(T.reload)}</button>
    <button class="iconbtn iconbtn--danger" data-delete="${slug}" aria-label="${escapeHTML(T.delete)}" title="${escapeHTML(T.delete)}">×</button>`;
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
  return `<div class="row" style="gap:6px; flex-wrap:wrap">${channels.map(c => {
    const cls = c.platform === 'INSTAGRAM' ? 'pill--accent' : 'pill--info';
    const token = c.hasAccessToken ? T.token : T.noToken;
    const label = c.displayName ? `${c.platform} · ${c.platform === 'INSTAGRAM' ? '@' : ''}${c.displayName}` : c.platform;
    return `<span class="pill ${cls}" title="${escapeHTML(c.externalId)} · ${token}">${escapeHTML(label)}</span>`;
  }).join('')}</div>`;
}

function bindTenantActions() {
  $$('[data-open-dashboard]').forEach(b => b.addEventListener('click', () => openDashboard(b.dataset.openDashboard)));
  $$('[data-users]').forEach(b => b.addEventListener('click', () => usersDrawer(b.dataset.users)));
  $$('[data-edit]').forEach(b => b.addEventListener('click', () => tenantForm(state.tenants.find(t => t.slug === b.dataset.edit))));
  $$('[data-suspend]').forEach(b => b.addEventListener('click', () => lifecycle(b.dataset.suspend, 'suspend', T.suspendTitle, T.suspend)));
  $$('[data-activate]').forEach(b => b.addEventListener('click', () => lifecycle(b.dataset.activate, 'activate', T.activateTitle, T.activate, false)));
  $$('[data-reload]').forEach(b => b.addEventListener('click', async () => { await api(`/admin/api/tenants/${encodeURIComponent(b.dataset.reload)}/reload`, { method: 'POST' }); toast(T.pipelineReloaded); }));
  $$('[data-delete]').forEach(b => b.addEventListener('click', () => deleteTenant(b.dataset.delete)));
  $$('[data-restore]').forEach(b => b.addEventListener('click', () => restoreTenant(b.dataset.restore)));
  $$('[data-status-filter]').forEach(b => b.addEventListener('click', () => setTenantFilter(b.dataset.statusFilter)));
}

async function openDashboard(slug) {
  try {
    const res = await api(`/admin/api/tenants/${encodeURIComponent(slug)}/impersonate`, { method: 'POST' });
    localStorage.setItem('dashboardToken', res.token);
    location.href = '/app/';
  } catch (e) { toast(T.error({ msg: e.message })); }
}

async function usersDrawer(slug) {
  const users = await api(`/admin/api/tenants/${encodeURIComponent(slug)}/dashboard-users`);
  const tenant = state.tenants.find(t => t.slug === slug);
  const severalCompanies = tenant && (tenant.maxCompanies > 1 || companiesOf(tenant.id).length > 0);
  const wrap = document.createElement('div');
  wrap.className = 'form';
  wrap.innerHTML = `
    ${severalCompanies ? `<p class="hint">${escapeHTML(T.usersAllCompanies)}</p>` : ''}
    <div class="panel"><div class="tbl-wrap"><table class="tbl"><thead><tr><th>${escapeHTML(T.thEmail)}</th><th>${escapeHTML(T.thRole)}</th><th>${escapeHTML(T.thStatus)}</th><th class="right">${escapeHTML(T.thActions)}</th></tr></thead><tbody>
      ${users.length === 0 ? `<tr><td colspan="4"><div class="empty"><p class="empty__title">${escapeHTML(T.noUsers)}</p></div></td></tr>` : users.map(u => `<tr>
        <td class="name">${escapeHTML(u.email)}</td><td>${escapeHTML(u.role)}</td><td>${escapeHTML(u.status)}</td>
        <td class="right">${u.status === 'ACTIVE' ? `<button class="btn btn--sm btn--ghost" data-disable-user="${u.id}">${escapeHTML(T.disable)}</button>` : `<button class="btn btn--sm btn--accent" data-activate-user="${u.id}">${escapeHTML(T.activate)}</button>`}</td>
      </tr>`).join('')}
    </tbody></table></div></div>
    <div class="form__grid">
      <div class="form__row"><label class="lbl">${escapeHTML(T.emailLabel)}</label><input class="inp" id="u-email" type="email" /></div>
      <div class="form__row"><label class="lbl">${escapeHTML(T.tempPassword)}</label><input class="inp" id="u-password" type="password" /></div>
      <div class="form__row"><label class="lbl">${escapeHTML(T.roleLabel)}</label><select class="sel" id="u-role"><option>TENANT_ADMIN</option><option>TENANT_MEMBER</option></select></div>
    </div>`;
  openDrawer({
    title: T.usersTitle({ slug }),
    body: wrap,
    saveLabel: T.createUser,
    async onSave() {
      const email = $('#u-email', wrap).value.trim();
      const password = $('#u-password', wrap).value;
      const role = $('#u-role', wrap).value;
      if (!email || !password) { toast(T.emailPasswordRequired); return false; }
      await api(`/admin/api/tenants/${encodeURIComponent(slug)}/dashboard-users`, { method: 'POST', body: JSON.stringify({ email, password, role }) });
      toast(T.userCreated);
    },
  });
  $$('[data-disable-user]', wrap).forEach(b => b.addEventListener('click', async () => { await api(`/admin/api/tenants/${encodeURIComponent(slug)}/dashboard-users/${b.dataset.disableUser}/disable`, { method: 'POST' }); toast(T.userDisabled); usersDrawer(slug); }));
  $$('[data-activate-user]', wrap).forEach(b => b.addEventListener('click', async () => { await api(`/admin/api/tenants/${encodeURIComponent(slug)}/dashboard-users/${b.dataset.activateUser}/activate`, { method: 'POST' }); toast(T.userActivated); usersDrawer(slug); }));
}

function tenantForm(editing) {
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
      <div class="row" style="justify-content:space-between">
        <label class="lbl">${escapeHTML(T.modulesLabel)}</label>
        <div class="row" style="gap:6px">
          <button class="btn btn--sm btn--ghost" type="button" id="modules-all">${escapeHTML(T.selectAllModules)}</button>
          <button class="btn btn--sm btn--ghost" type="button" id="modules-none">${escapeHTML(T.selectNoModules)}</button>
        </div>
      </div>
      <div class="panel" style="padding:12px" id="modules-box"></div>
      <div class="hint">${escapeHTML(T.modulesHint)}</div>
    </div>
    <div class="form__row">
      <div class="row" style="justify-content:space-between">
        <label class="lbl">${escapeHTML(T.channelsLabel)}</label>
        <div class="row" style="gap:6px">
          ${editing && state.whatsAppSignup.enabled ? `<button class="btn btn--sm btn--ghost" type="button" id="wa-connect">${escapeHTML(T.connectWhatsApp)}</button>` : ''}
          ${editing ? `<button class="btn btn--sm btn--ghost" type="button" id="ig-connect">${escapeHTML(T.connectInstagram)}</button>` : ''}
          <button class="btn btn--sm btn--ghost" type="button" id="add-channel">${escapeHTML(T.addChannel)}</button>
        </div>
      </div>
      <div class="lines" id="channels-box">
        <div class="lines__head" style="grid-template-columns:130px 1fr 1fr 32px"><span>${escapeHTML(T.colPlatform)}</span><span>${escapeHTML(T.colExternalId)}</span><span>${escapeHTML(T.colAccessToken)}</span><span></span></div>
        <div id="channels-body"></div>
      </div>
      <div class="hint">${escapeHTML(T.channelsHint)}</div>
    </div>`;
  // A new tenant starts with no channel row: bindings are optional and can be connected later.
  const existingChannels = editing?.channels?.length ? editing.channels : (editing?.phoneNumberId ? [{ platform: 'WHATSAPP', externalId: editing.phoneNumberId, hasAccessToken: true }] : []);
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
        if (editing) {
          await api(`/admin/api/tenants/${encodeURIComponent(editing.slug)}`, { method: 'PUT', body: JSON.stringify(payload) });
          toast(T.tenantUpdated);
        } else {
          await api('/admin/api/tenants', { method: 'POST', body: JSON.stringify({ ...payload, slug: $('#t-slug', wrap).value.trim() }) });
          toast(T.botCreated);
        }
        await loadAll();
        renderTenants();
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
  $('#modules-box', root).innerHTML = `<div class="row" style="gap:10px; flex-wrap:wrap">
    ${MODULES.map(m => `<label class="pill ${m.always ? 'pill--ok' : 'pill--info'}" style="cursor:${m.always ? 'not-allowed' : 'pointer'}">
      <input type="checkbox" data-module="${m.id}" ${selected.has(m.id) ? 'checked' : ''} ${m.always ? 'disabled' : ''} /> ${escapeHTML(I18N.t('common.nav.' + m.id))}
    </label>`).join('')}
  </div>`;
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
  el.className = 'muted';
  el.style.padding = '10px 12px';
  el.textContent = T.noChannels;
  body.parentElement.appendChild(el);
}

function addChannelRow(root, channel = { platform: 'WHATSAPP', externalId: '', hasAccessToken: false }) {
  const row = document.createElement('div');
  row.className = 'line channel-row';
  row.style.gridTemplateColumns = '130px 1fr 1fr 32px';
  row.innerHTML = `
    <select class="sel" data-channel-platform>
      <option value="WHATSAPP" ${channel.platform === 'WHATSAPP' ? 'selected' : ''}>WHATSAPP</option>
      <option value="INSTAGRAM" ${channel.platform === 'INSTAGRAM' ? 'selected' : ''}>INSTAGRAM</option>
    </select>
    <input class="inp inp--mono" data-channel-external value="${escapeHTML(channel.externalId || '')}" placeholder="${escapeHTML(T.chExternalPlaceholder)}" />
    <input class="inp inp--mono" data-channel-token type="password" placeholder="${channel.hasAccessToken ? escapeHTML(T.chTokenUnchanged) : escapeHTML(T.chTokenPlaceholder)}" />
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

async function lifecycle(slug, action, title, okLabel, danger = true) {
  if (!await confirmDialog({ title, body: T.tenantLine({ slug }) + otherCompaniesNote(slug), okLabel, danger })) return;
  try {
    await api(`/admin/api/tenants/${encodeURIComponent(slug)}/${action}`, { method: 'POST' });
  } catch (e) {
    toast(e.code === 'tenant_deleted' ? T.tenantDeleted : T.error({ msg: e.message }));
  }
  await loadAll();
  renderTenants();
}

async function deleteTenant(slug) {
  const name = state.tenants.find(t => t.slug === slug)?.name || slug;
  if (!await confirmDialog({ title: T.deleteTitle, body: T.deleteBody({ name, slug }) + otherCompaniesNote(slug), okLabel: T.delete })) return;
  try {
    await api(`/admin/api/tenants/${encodeURIComponent(slug)}`, { method: 'DELETE' });
    toast(T.deleted({ name }));
  } catch (e) {
    toast(T.error({ msg: e.message }));
  }
  await loadAll();
  renderTenants();
}

async function restoreTenant(slug) {
  const tenant = state.tenants.find(t => t.slug === slug);
  if (!tenant) return;
  const n = tenant.parentTenantId ? 0 : companiesDeletedWith(tenant).length;
  const body = T.restoreBody({ name: tenant.name, slug }) + (n ? ` ${T.restoreCompaniesNote({ n })}` : '');
  if (!await confirmDialog({ title: T.restoreTitle, body, okLabel: T.restore, danger: false })) return;
  try {
    await api(`/admin/api/tenants/${encodeURIComponent(slug)}/restore`, { method: 'POST' });
    toast(T.restored({ name: tenant.name }));
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
    if (e.key === 'Escape') { $('#drawer').hidden = true; $('#confirm').hidden = true; }
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
