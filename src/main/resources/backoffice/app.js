const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

let token = localStorage.getItem('adminToken') || '';
let state = { tenants: [], stats: {}, search: '', whatsAppSignup: { enabled: false } };
let currentView = 'tenants';
let platformSettings = { settings: [], drafts: {}, revealed: {}, clear: new Set(), updatedAt: null };
let fbSdkPromise;

// Backoffice copy comes from the shared i18n catalogs (admin/catalog.*.js). Locale follows the
// browser / the operator's saved choice (super-admin is not tenant-scoped). `T` is a live proxy.
const T = I18N.section('backoffice');

// Module ids only — display labels come from the shared catalog (common.nav.<id>) at render time.
const MODULES = [
  { id: 'overview', always: true },
  { id: 'conversations', always: true },
  { id: 'contacts', always: true },
  { id: 'settings', always: true },
  { id: 'persona' },
  { id: 'clients' },
  { id: 'quotes' },
  { id: 'invoices' },
  { id: 'catalog' },
  { id: 'ai-assistant' },
  { id: 'bookings' },
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
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  if (res.status === 204) return null;
  return res.json();
}

const escapeHTML = (s = '') => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
const slugify = s => (s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const fmtDate = iso => iso ? new Date(iso).toLocaleString('pt-PT', { dateStyle: 'short', timeStyle: 'short' }) : '—';

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

function renderLogin() {
  $('#view').innerHTML = `
    <div class="auth"><div class="auth__card">
      <div class="auth__mark">BO</div>
      <p class="auth__eyebrow">${escapeHTML(T.login.eyebrow)}</p>
      <h1 class="auth__title">${escapeHTML(T.login.title)}</h1>
      <p class="auth__desc">${escapeHTML(T.login.desc)}</p>
      <form class="form" id="login-form">
        <div class="form__row"><label class="lbl" for="password">${escapeHTML(T.login.password)}</label><input class="inp" id="password" type="password" autocomplete="current-password" required /></div>
        <button class="btn btn--primary" type="submit">${escapeHTML(T.login.submit)}</button>
      </form>
    </div></div>`;
  $('#login-form').addEventListener('submit', async e => {
    e.preventDefault();
    try {
      const res = await api('/admin/auth/login', { method: 'POST', body: JSON.stringify({ password: $('#password').value }) });
      token = res.token;
      localStorage.setItem('adminToken', token);
      await loadAll();
      renderTenants();
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
  const stats = await Promise.all(state.tenants.map(t => api(`/admin/api/tenants/${encodeURIComponent(t.slug)}/stats`).catch(() => null)));
  state.stats = Object.fromEntries(state.tenants.map((t, i) => [t.slug, stats[i] || {}]));
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

function setView(view) {
  currentView = view === 'settings' ? 'settings' : 'tenants';
  if (location.hash !== `#${currentView}`) location.hash = currentView;
  $$('.nav__item').forEach(a => a.classList.toggle('is-active', a.dataset.view === currentView));
  const leaf = $('.crumb__leaf');
  if (leaf) leaf.textContent = currentView === 'settings' ? T.platformSettings.nav : T.heroTitle;
  const search = $('.topbar__search');
  if (search) search.hidden = currentView !== 'tenants';
  const btnNew = $('#btn-new');
  if (btnNew) btnNew.hidden = currentView !== 'tenants';
  if (currentView === 'settings') renderPlatformSettings();
  else renderTenants();
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

function renderTenants() {
  const q = state.search.toLowerCase();
  const rows = state.tenants.filter(t => !q || `${t.name} ${t.slug} ${t.phoneNumberId} ${(t.channels || []).map(c => `${c.platform} ${c.externalId}`).join(' ')}`.toLowerCase().includes(q));
  $('#tenant-count').textContent = state.tenants.length;
  $('#kpi-active').textContent = state.tenants.filter(t => t.status === 'ACTIVE').length;
  $('#kpi-messages').textContent = Object.values(state.stats).reduce((sum, s) => sum + Number(s.messages || 0), 0);
  $('#meta-clock').textContent = new Date().toLocaleString('pt-PT', { hour: '2-digit', minute: '2-digit' });
  $('#view').innerHTML = `
    <div class="view__hero"><div><h1 class="view__title">${escapeHTML(T.heroTitle)}</h1><p class="view__desc">${escapeHTML(T.heroDesc)}</p></div></div>
    <div class="panel"><div class="panel__head"><h2 class="panel__title">${escapeHTML(T.botsTitle)} <span class="tag">${rows.length}</span></h2></div>
      <div class="tbl-wrap"><table class="tbl"><thead><tr>
        <th>${escapeHTML(T.thName)}</th><th>${escapeHTML(T.thSlug)}</th><th>${escapeHTML(T.thChannels)}</th><th>${escapeHTML(T.thStatus)}</th><th class="right">${escapeHTML(T.thMsgs)}</th><th>${escapeHTML(T.thLastActivity)}</th><th class="right">${escapeHTML(T.thActions)}</th>
      </tr></thead><tbody>
      ${rows.length === 0 ? `<tr><td colspan="7"><div class="empty"><p class="empty__title">${escapeHTML(T.emptyTenants)}</p></div></td></tr>` : rows.map(t => {
        const s = state.stats[t.slug] || {};
        const pillClass = t.status === 'ACTIVE' ? 'pill--ok' : t.status === 'SUSPENDED' ? 'pill--warn' : 'pill--bad';
        return `<tr>
          <td class="name">${escapeHTML(t.name)}</td>
          <td class="id">${escapeHTML(t.slug)}</td>
          <td>${channelBadges(t.channels)}</td>
          <td><span class="pill ${pillClass}">${escapeHTML(t.status)}</span></td>
          <td class="num">${s.messages || 0}</td>
          <td class="mono muted">${fmtDate(s.lastMessageAt)}</td>
          <td class="right"><div class="actions">
            <button class="btn btn--sm" data-open-dashboard="${escapeHTML(t.slug)}">${escapeHTML(T.openDashboard)}</button>
            <button class="btn btn--sm btn--ghost" data-users="${escapeHTML(t.slug)}">${escapeHTML(T.users)}</button>
            <button class="btn btn--sm btn--ghost" data-edit="${escapeHTML(t.slug)}">${escapeHTML(T.edit)}</button>
            ${t.status === 'ACTIVE' ? `<button class="btn btn--sm btn--ghost" data-suspend="${escapeHTML(t.slug)}">${escapeHTML(T.suspend)}</button>` : `<button class="btn btn--sm btn--accent" data-activate="${escapeHTML(t.slug)}">${escapeHTML(T.activate)}</button>`}
            <button class="btn btn--sm btn--ghost" data-reload="${escapeHTML(t.slug)}">${escapeHTML(T.reload)}</button>
            <button class="iconbtn iconbtn--danger" data-delete="${escapeHTML(t.slug)}">×</button>
          </div></td>
        </tr>`;
      }).join('')}
      </tbody></table></div></div>`;
  bindTenantActions();
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
  const wrap = document.createElement('div');
  wrap.className = 'form';
  wrap.innerHTML = `
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
    </div>
    <div class="form__row">
      <label class="lbl">${escapeHTML(T.modulesLabel)}</label>
      <div class="panel" style="padding:12px" id="modules-box"></div>
      <div class="hint">${escapeHTML(T.modulesHint)}</div>
    </div>
    <div class="form__row">
      <div class="row" style="justify-content:space-between">
        <label class="lbl">${escapeHTML(T.channelsLabel)} <span class="req">●</span></label>
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
  const existingChannels = editing?.channels?.length ? editing.channels : (editing?.phoneNumberId ? [{ platform: 'WHATSAPP', externalId: editing.phoneNumberId, hasAccessToken: true }] : [{ platform: 'WHATSAPP', externalId: '', hasAccessToken: false }]);
  existingChannels.forEach(c => addChannelRow(wrap, c));
  renderModulesBox(wrap, editing);
  $('#add-channel', wrap).addEventListener('click', () => addChannelRow(wrap));
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
      if (!payload.name) { toast(T.nameRequired); return false; }
      if (payload.channels.length === 0) { toast(T.addOneChannel); return false; }
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
      } catch (e) { toast(T.error({ msg: e.message })); return false; }
    },
  });
}

function selectedModulesFor(editing) {
  const selected = new Set(editing?.effectiveModules || MODULES.map(m => m.id));
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
  row.querySelector('.l-rm').addEventListener('click', () => row.remove());
  $('#channels-body', root).appendChild(row);
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

async function lifecycle(slug, action, title, okLabel, danger = true) {
  if (!await confirmDialog({ title, body: T.tenantLine({ slug }), okLabel, danger })) return;
  await api(`/admin/api/tenants/${encodeURIComponent(slug)}/${action}`, { method: 'POST' });
  await loadAll();
  renderTenants();
}

async function deleteTenant(slug) {
  if (!await confirmDialog({ title: T.deleteTitle, body: T.deleteBody({ slug }), okLabel: T.delete })) return;
  await api(`/admin/api/tenants/${encodeURIComponent(slug)}`, { method: 'DELETE' });
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
