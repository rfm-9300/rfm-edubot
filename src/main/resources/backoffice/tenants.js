/* Tenants (/backoffice → Tenants): the list with its status filters, a tenant's record (profile, KPIs,
   channels) with its dashboard users and agents, opening its dashboard, and suspending, activating,
   deleting and restoring a tenant with its other companies. The new and edit form is tenant-form.js. */

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
const tenantStatusPill = (t, cls = '') => `<span class="pill ${STATUS_PILLS[t.status] || 'pill--bad'}${cls ? ` ${cls}` : ''}">${escapeHTML(T.status[t.status] || t.status)}</span>`;
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
      <div class="panel__tools panel__tools--chips" role="group" aria-label="${escapeHTML(T.statusFilterAria)}">
        ${STATUS_FILTERS.map(f => `<button class="chip ${state.filter === f ? 'is-on' : ''}" type="button" data-status-filter="${f}" aria-pressed="${state.filter === f}">${escapeHTML(T.filters[f])}<span class="chip__count">${counts[f]}</span></button>`).join('')}
      </div></div>
      <div class="tbl-wrap"><table class="tbl tbl--stack tbl--flow"><thead><tr>
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
    <td class="name"><div class="tbl__title"><button class="tbl__open" type="button">${escapeHTML(t.name)}</button>${tenantStatusPill(t, 'tbl__aside')}</div><div class="sub mono">${escapeHTML(t.slug)}</div>${tenantCompanyLine(t)}</td>
    <td class="tbl__wide" ${dataLabel(T.thChannels)}>${channelBadges(t.channels)}</td>
    <td class="tbl__stack-hide" ${dataLabel(T.thStatus)}>${tenantStatusPill(t)}</td>
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
          <div class="record-card__lines"><span class="record-card__line mono">${escapeHTML(t.slug)}</span>${tenantStatusPill(t)}</div>
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
        ? `<dl class="dash-facts dash-facts--stack">${channels.map(c => `<div><dt>${escapeHTML(platformLabel(c.platform))}</dt><dd>${escapeHTML(channelText(c))}${c.hasAccessToken ? '' : ` <span class="pill pill--warn">${escapeHTML(R.noToken)}</span>`}</dd></div>`).join('')}</dl>`
        : `<p class="dash-empty">${escapeHTML(T.noChannels)}</p>`}</div>
    </section>
    <section class="panel" data-tenant-cards hidden></section>`;
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
  // The board's cards for this tenant arrive after the record opens, so it doesn't wait on them.
  mountTenantCards(t, wrap, back);
}

async function usersDrawer(slug, back = null) {
  const users = await api(`/admin/api/tenants/${encodeURIComponent(slug)}/dashboard-users`);
  const tenant = state.tenants.find(t => t.slug === slug);
  const severalCompanies = tenant && (tenant.maxCompanies > 1 || companiesOf(tenant.id).length > 0);
  const statusPill = (u, cls = '') => `<span class="pill ${u.status === 'ACTIVE' ? 'pill--ok' : ''}${cls ? ` ${cls}` : ''}">${escapeHTML(T.userStatus[u.status] || u.status)}</span>`;
  const wrap = document.createElement('div');
  wrap.className = 'form';
  wrap.innerHTML = `
    ${severalCompanies ? `<div class="hint">${escapeHTML(T.usersAllCompanies)}</div>` : ''}
    <div class="panel"><div class="tbl-wrap"><table class="tbl tbl--stack tbl--flow"><thead><tr><th>${escapeHTML(T.thEmail)}</th><th>${escapeHTML(T.thRole)}</th><th>${escapeHTML(T.thStatus)}</th><th class="right">${escapeHTML(T.thActions)}</th></tr></thead><tbody>
      ${users.length === 0 ? `<tr><td colspan="4"><div class="empty"><p class="empty__title">${escapeHTML(T.noUsers)}</p></div></td></tr>` : users.map(u => `<tr>
        <td class="name"><div class="tbl__title"><span>${escapeHTML(u.email)}</span>${statusPill(u, 'tbl__aside')}</div></td><td ${dataLabel(T.thRole)}>${escapeHTML(roleLabel(u.role))}</td><td class="tbl__stack-hide" ${dataLabel(T.thStatus)}>${statusPill(u)}</td>
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
  const statusPill = (cls = '') => `<span class="pill ${AGENT_PILLS[a.status] || ''}${cls ? ` ${cls}` : ''}">${escapeHTML(I18N.t(`app.agents.status.${a.status}`))}</span>`;
  return `<tr>
    <td class="name"><div class="tbl__title"><span>${escapeHTML(a.name)}</span>${statusPill('tbl__aside')}</div>${reason ? `<div class="muted">${escapeHTML(reason)}</div>` : ''}</td>
    <td class="tbl__stack-hide" ${dataLabel(A.thStatus)}>${statusPill()}</td>
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
    <div class="panel"><div class="tbl-wrap"><table class="tbl tbl--stack tbl--flow"><thead><tr>
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
