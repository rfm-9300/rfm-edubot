const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

let token = localStorage.getItem('adminToken') || '';
let state = { tenants: [], stats: {}, agentTypes: ['CRM_V1'], search: '' };

const MODULES = [
  { id: 'overview', label: 'Overview', always: true },
  { id: 'conversations', label: 'Conversas', always: true },
  { id: 'contacts', label: 'Contactos', always: true },
  { id: 'settings', label: 'Settings', always: true },
  { id: 'persona', label: 'Persona' },
  { id: 'clients', label: 'Clientes', crm: true },
  { id: 'quotes', label: 'Orçamentos', crm: true },
  { id: 'invoices', label: 'Faturas', crm: true },
  { id: 'catalog', label: 'Catálogo', crm: true },
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

function confirmDialog({ title, body, okLabel = 'Confirmar', danger = true }) {
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

function openDrawer({ title, body, onSave, saveLabel = 'Guardar' }) {
  const root = $('#drawer');
  $('#drawer-title').textContent = title;
  const host = $('#drawer-body');
  host.innerHTML = '';
  host.appendChild(body);
  const foot = document.createElement('div');
  foot.className = 'drawer__foot';
  foot.innerHTML = `<button class="btn btn--ghost" data-close>Cancelar</button><button class="btn btn--accent" id="drawer-save">${escapeHTML(saveLabel)}</button>`;
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
      <p class="auth__eyebrow">thebots.lab / platform</p>
      <h1 class="auth__title">Backoffice</h1>
      <p class="auth__desc">Acesso de operador para gerir bots, tenants e equipas.</p>
      <form class="form" id="login-form">
        <div class="form__row"><label class="lbl" for="password">Password</label><input class="inp" id="password" type="password" autocomplete="current-password" required /></div>
        <button class="btn btn--primary" type="submit">Entrar</button>
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
      toast('Login inválido');
    }
  });
}

async function loadAll() {
  state.agentTypes = await api('/admin/api/agent-types');
  state.tenants = await api('/admin/api/tenants');
  const stats = await Promise.all(state.tenants.map(t => api(`/admin/api/tenants/${encodeURIComponent(t.slug)}/stats`).catch(() => null)));
  state.stats = Object.fromEntries(state.tenants.map((t, i) => [t.slug, stats[i] || {}]));
}

function renderTenants() {
  const q = state.search.toLowerCase();
  const rows = state.tenants.filter(t => !q || `${t.name} ${t.slug} ${t.phoneNumberId} ${(t.channels || []).map(c => `${c.platform} ${c.externalId}`).join(' ')}`.toLowerCase().includes(q));
  $('#tenant-count').textContent = state.tenants.length;
  $('#kpi-active').textContent = state.tenants.filter(t => t.status === 'ACTIVE').length;
  $('#kpi-messages').textContent = Object.values(state.stats).reduce((sum, s) => sum + Number(s.messages || 0), 0);
  $('#meta-clock').textContent = new Date().toLocaleString('pt-PT', { hour: '2-digit', minute: '2-digit' });
  $('#view').innerHTML = `
    <div class="view__hero"><div><h1 class="view__title">Tenants</h1><p class="view__desc">Bots/clientes configurados nesta plataforma.</p></div></div>
    <div class="panel"><div class="panel__head"><h2 class="panel__title">Bots <span class="tag">${rows.length}</span></h2></div>
      <div class="tbl-wrap"><table class="tbl"><thead><tr>
        <th>Nome</th><th>Slug</th><th>Channels</th><th>Agent</th><th>Status</th><th class="right">Msgs</th><th>Última actividade</th><th class="right">Acções</th>
      </tr></thead><tbody>
      ${rows.length === 0 ? '<tr><td colspan="8"><div class="empty"><p class="empty__title">Nenhum tenant encontrado</p></div></td></tr>' : rows.map(t => {
        const s = state.stats[t.slug] || {};
        const pillClass = t.status === 'ACTIVE' ? 'pill--ok' : t.status === 'SUSPENDED' ? 'pill--warn' : 'pill--bad';
        return `<tr>
          <td class="name">${escapeHTML(t.name)}</td>
          <td class="id">${escapeHTML(t.slug)}</td>
          <td>${channelBadges(t.channels)}</td>
          <td>${escapeHTML(t.agentType)}</td>
          <td><span class="pill ${pillClass}">${escapeHTML(t.status)}</span></td>
          <td class="num">${s.messages || 0}</td>
          <td class="mono muted">${fmtDate(s.lastMessageAt)}</td>
          <td class="right"><div class="actions">
            <button class="btn btn--sm" data-open-dashboard="${escapeHTML(t.slug)}">Open Dashboard</button>
            <button class="btn btn--sm btn--ghost" data-users="${escapeHTML(t.slug)}">Users</button>
            <button class="btn btn--sm btn--ghost" data-edit="${escapeHTML(t.slug)}">Edit</button>
            ${t.status === 'ACTIVE' ? `<button class="btn btn--sm btn--ghost" data-suspend="${escapeHTML(t.slug)}">Suspend</button>` : `<button class="btn btn--sm btn--accent" data-activate="${escapeHTML(t.slug)}">Activate</button>`}
            <button class="btn btn--sm btn--ghost" data-reload="${escapeHTML(t.slug)}">Reload</button>
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
    const token = c.hasAccessToken ? 'token' : 'no token';
    return `<span class="pill ${cls}" title="${escapeHTML(c.externalId)} · ${token}">${escapeHTML(c.platform)}</span>`;
  }).join('')}</div>`;
}

function bindTenantActions() {
  $$('[data-open-dashboard]').forEach(b => b.addEventListener('click', () => openDashboard(b.dataset.openDashboard)));
  $$('[data-users]').forEach(b => b.addEventListener('click', () => usersDrawer(b.dataset.users)));
  $$('[data-edit]').forEach(b => b.addEventListener('click', () => tenantForm(state.tenants.find(t => t.slug === b.dataset.edit))));
  $$('[data-suspend]').forEach(b => b.addEventListener('click', () => lifecycle(b.dataset.suspend, 'suspend', 'Suspender tenant?', 'Suspender')));
  $$('[data-activate]').forEach(b => b.addEventListener('click', () => lifecycle(b.dataset.activate, 'activate', 'Activar tenant?', 'Activar', false)));
  $$('[data-reload]').forEach(b => b.addEventListener('click', async () => { await api(`/admin/api/tenants/${encodeURIComponent(b.dataset.reload)}/reload`, { method: 'POST' }); toast('Pipeline recarregada'); }));
  $$('[data-delete]').forEach(b => b.addEventListener('click', () => deleteTenant(b.dataset.delete)));
}

async function openDashboard(slug) {
  try {
    const res = await api(`/admin/api/tenants/${encodeURIComponent(slug)}/impersonate`, { method: 'POST' });
    localStorage.setItem('dashboardToken', res.token);
    location.href = '/app/';
  } catch (e) { toast(`Erro: ${e.message}`); }
}

async function usersDrawer(slug) {
  const users = await api(`/admin/api/tenants/${encodeURIComponent(slug)}/dashboard-users`);
  const wrap = document.createElement('div');
  wrap.className = 'form';
  wrap.innerHTML = `
    <div class="panel"><div class="tbl-wrap"><table class="tbl"><thead><tr><th>Email</th><th>Role</th><th>Status</th><th class="right">Acções</th></tr></thead><tbody>
      ${users.length === 0 ? '<tr><td colspan="4"><div class="empty"><p class="empty__title">Sem utilizadores</p></div></td></tr>' : users.map(u => `<tr>
        <td class="name">${escapeHTML(u.email)}</td><td>${escapeHTML(u.role)}</td><td>${escapeHTML(u.status)}</td>
        <td class="right">${u.status === 'ACTIVE' ? `<button class="btn btn--sm btn--ghost" data-disable-user="${u.id}">Disable</button>` : `<button class="btn btn--sm btn--accent" data-activate-user="${u.id}">Activate</button>`}</td>
      </tr>`).join('')}
    </tbody></table></div></div>
    <div class="form__grid">
      <div class="form__row"><label class="lbl">Email</label><input class="inp" id="u-email" type="email" /></div>
      <div class="form__row"><label class="lbl">Password temporária</label><input class="inp" id="u-password" type="password" /></div>
      <div class="form__row"><label class="lbl">Role</label><select class="sel" id="u-role"><option>TENANT_ADMIN</option><option>TENANT_MEMBER</option></select></div>
    </div>`;
  openDrawer({
    title: `Dashboard users · ${slug}`,
    body: wrap,
    saveLabel: 'Criar utilizador',
    async onSave() {
      const email = $('#u-email', wrap).value.trim();
      const password = $('#u-password', wrap).value;
      const role = $('#u-role', wrap).value;
      if (!email || !password) { toast('Email e password obrigatórios'); return false; }
      await api(`/admin/api/tenants/${encodeURIComponent(slug)}/dashboard-users`, { method: 'POST', body: JSON.stringify({ email, password, role }) });
      toast('Utilizador criado');
    },
  });
  $$('[data-disable-user]', wrap).forEach(b => b.addEventListener('click', async () => { await api(`/admin/api/tenants/${encodeURIComponent(slug)}/dashboard-users/${b.dataset.disableUser}/disable`, { method: 'POST' }); toast('Utilizador desactivado'); usersDrawer(slug); }));
  $$('[data-activate-user]', wrap).forEach(b => b.addEventListener('click', async () => { await api(`/admin/api/tenants/${encodeURIComponent(slug)}/dashboard-users/${b.dataset.activateUser}/activate`, { method: 'POST' }); toast('Utilizador activado'); usersDrawer(slug); }));
}

function tenantForm(editing) {
  const wrap = document.createElement('form');
  wrap.className = 'form';
  wrap.innerHTML = `
    <div class="form__grid">
      <div class="form__row form__row--full"><label class="lbl">Nome</label><input class="inp" id="t-name" value="${escapeHTML(editing?.name || '')}" /></div>
      <div class="form__row"><label class="lbl">Slug</label><input class="inp inp--mono" id="t-slug" value="${escapeHTML(editing?.slug || '')}" ${editing ? 'readonly' : ''} /></div>
      <div class="form__row"><label class="lbl">Agent type</label><select class="sel" id="t-agent">${state.agentTypes.map(a => `<option value="${a}" ${editing?.agentType === a ? 'selected' : ''}>${a}</option>`).join('')}</select></div>
      <div class="form__row"><label class="lbl">Model override</label><input class="inp inp--mono" id="t-model" value="${escapeHTML(editing?.openrouterModel || '')}" placeholder="blank = default" /></div>
      <div class="form__row"><label class="lbl">Rate / hour</label><input class="inp inp--mono" id="t-hour" type="number" value="${editing?.rateLimitPerHour || 30}" /></div>
      <div class="form__row"><label class="lbl">Rate / day</label><input class="inp inp--mono" id="t-day" type="number" value="${editing?.rateLimitPerDay || 200}" /></div>
    </div>
    <div class="form__row">
      <label class="lbl">Modules</label>
      <div class="panel" style="padding:12px" id="modules-box"></div>
      <div class="hint">Always-on modules stay checked. CRM modules require agent type CRM_V1.</div>
    </div>
    <div class="form__row">
      <div class="row" style="justify-content:space-between">
        <label class="lbl">Channels <span class="req">●</span></label>
        <div class="row" style="gap:6px">
          ${editing ? '<button class="btn btn--sm btn--ghost" type="button" id="ig-connect">Connect Instagram</button>' : ''}
          <button class="btn btn--sm btn--ghost" type="button" id="add-channel">+ Add channel</button>
        </div>
      </div>
      <div class="lines" id="channels-box">
        <div class="lines__head" style="grid-template-columns:130px 1fr 1fr 32px"><span>Platform</span><span>External ID</span><span>Access token</span><span></span></div>
        <div id="channels-body"></div>
      </div>
      <div class="hint">Instagram token is required when adding a channel. In edit, leave token blank to keep the existing one.</div>
    </div>`;
  const existingChannels = editing?.channels?.length ? editing.channels : (editing?.phoneNumberId ? [{ platform: 'WHATSAPP', externalId: editing.phoneNumberId, hasAccessToken: true }] : [{ platform: 'WHATSAPP', externalId: '', hasAccessToken: false }]);
  existingChannels.forEach(c => addChannelRow(wrap, c));
  renderModulesBox(wrap, editing);
  $('#add-channel', wrap).addEventListener('click', () => addChannelRow(wrap));
  $('#ig-connect', wrap)?.addEventListener('click', () => connectInstagram(editing.slug));
  $('#t-agent', wrap).addEventListener('change', () => renderModulesBox(wrap, editing));
  if (!editing) {
    let touched = false;
    $('#t-slug', wrap).addEventListener('input', () => { touched = true; });
    $('#t-name', wrap).addEventListener('input', () => { if (!touched) $('#t-slug', wrap).value = slugify($('#t-name', wrap).value); });
  }
  openDrawer({
    title: editing ? `Editar · ${editing.slug}` : 'Novo bot/cliente',
    body: wrap,
    saveLabel: editing ? 'Guardar alterações' : 'Criar tenant',
    async onSave() {
      const payload = {
        name: $('#t-name', wrap).value.trim(),
        agentType: $('#t-agent', wrap).value,
        openrouterModel: $('#t-model', wrap).value.trim() || null,
        rateLimitPerHour: Number($('#t-hour', wrap).value || 30),
        rateLimitPerDay: Number($('#t-day', wrap).value || 200),
        channels: collectChannels(wrap, Boolean(editing)),
        enabledModules: collectModules(wrap),
      };
      if (!payload.name) { toast('Nome obrigatório'); return false; }
      if (payload.channels.length === 0) { toast('Adicione pelo menos um channel'); return false; }
      if (payload.channels.some(c => c.platform === 'INSTAGRAM' && !editing && !c.accessToken)) { toast('Instagram precisa de Page access token'); return false; }
      try {
        if (editing) {
          await api(`/admin/api/tenants/${encodeURIComponent(editing.slug)}`, { method: 'PUT', body: JSON.stringify(payload) });
          toast('Tenant actualizado');
        } else {
          await api('/admin/api/tenants', { method: 'POST', body: JSON.stringify({ ...payload, slug: $('#t-slug', wrap).value.trim() }) });
          toast('Bot criado. Liga os canais à app Meta e aponta o webhook.');
        }
        await loadAll();
        renderTenants();
      } catch (e) { toast(`Erro: ${e.message}`); return false; }
    },
  });
}

function modulesForAgent(agentType) {
  return MODULES.filter(m => !m.crm || agentType === 'CRM_V1');
}

function selectedModulesFor(editing, agentType) {
  const selected = new Set(editing?.effectiveModules || ['overview', 'conversations', 'contacts', 'settings', 'clients', 'quotes', 'invoices', 'catalog']);
  return modulesForAgent(agentType).filter(m => m.always || selected.has(m.id)).map(m => m.id);
}

function renderModulesBox(root, editing) {
  const agentType = $('#t-agent', root).value;
  const selected = new Set(selectedModulesFor(editing, agentType));
  $('#modules-box', root).innerHTML = `<div class="row" style="gap:10px; flex-wrap:wrap">
    ${modulesForAgent(agentType).map(m => `<label class="pill ${m.always ? 'pill--ok' : 'pill--info'}" style="cursor:${m.always ? 'not-allowed' : 'pointer'}">
      <input type="checkbox" data-module="${m.id}" ${selected.has(m.id) ? 'checked' : ''} ${m.always ? 'disabled' : ''} /> ${escapeHTML(m.label)}
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
    <input class="inp inp--mono" data-channel-external value="${escapeHTML(channel.externalId || '')}" placeholder="phone_number_id or IG account id" />
    <input class="inp inp--mono" data-channel-token type="password" placeholder="${channel.hasAccessToken ? 'unchanged' : 'access token'}" />
    <button class="l-rm" type="button" aria-label="Remove channel">×</button>
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
    toast(e.message === 'unauthorized' ? 'Sessão expirada' : 'Instagram OAuth não configurado (IG_APP_ID)');
    return;
  }
  const popup = window.open(res.authorizeUrl, 'ig-oauth', 'width=600,height=750');
  if (!popup) { toast('Permite popups para ligar o Instagram'); return; }
  const onMessage = async ev => {
    if (ev.origin !== window.location.origin || ev.data?.type !== 'ig-oauth') return;
    window.removeEventListener('message', onMessage);
    if (ev.data.status === 'connected') {
      toast('Instagram ligado ✓');
      await loadAll();
      renderTenants();
    } else {
      toast(`Instagram falhou: ${ev.data.reason || 'erro'}`);
    }
  };
  window.addEventListener('message', onMessage);
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
  if (!await confirmDialog({ title, body: `Tenant ${slug}`, okLabel, danger })) return;
  await api(`/admin/api/tenants/${encodeURIComponent(slug)}/${action}`, { method: 'POST' });
  await loadAll();
  renderTenants();
}

async function deleteTenant(slug) {
  if (!await confirmDialog({ title: 'Eliminar tenant?', body: `${slug} ficará marcado como DELETED. Dados existentes não serão apagados.`, okLabel: 'Eliminar' })) return;
  await api(`/admin/api/tenants/${encodeURIComponent(slug)}`, { method: 'DELETE' });
  await loadAll();
  renderTenants();
}

async function init() {
  if (handleOAuthPopup()) return;
  $('#btn-new').addEventListener('click', () => tenantForm());
  $('#btn-logout').addEventListener('click', () => { localStorage.removeItem('adminToken'); token = ''; renderLogin(); });
  $('#search').addEventListener('input', e => { state.search = e.target.value; renderTenants(); });
  document.addEventListener('keydown', e => {
    if (e.key === '/' && !['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) { e.preventDefault(); $('#search').focus(); }
    if (e.key === 'Escape') { $('#drawer').hidden = true; $('#confirm').hidden = true; }
  });
  if (!token) return renderLogin();
  try { await loadAll(); renderTenants(); }
  catch (e) { if (token) $('#view').innerHTML = `<div class="empty"><p class="empty__title">Erro ao carregar</p><p class="empty__desc">${escapeHTML(e.message)}</p></div>`; }
}

init();
