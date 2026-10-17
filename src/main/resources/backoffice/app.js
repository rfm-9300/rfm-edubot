/* The backoffice's shell, loaded last: loadAll() fetches the tenants and their stats for the sidebar,
   setView() switches between Tenants, Admins, Backups and Platform settings, and init() wires the top bar
   and starts it all. */

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
