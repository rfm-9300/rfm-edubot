/* The dashboard's shell (/app), loaded last. bootAuthed() loads /me and draws the nav; setActive() and
   loadModule() switch pages and fetch what each needs; render() draws the active page; init() wires the
   top bar and starts it all. It also mounts the self-contained modules (agents.js, timesheets.js,
   assistant.js, email.js, menus.js), handing each the helpers it needs through init(deps). */

async function bootAuthed() {
  state.email = null;
  state.integrations = null;
  state.me = await api('/app/api/me');
  // Adopt the tenant's language (unless the user picked an explicit override this session), then
  // refresh the static chrome that was rendered before /me resolved.
  I18N.applyTenantDefault(state.me.tenant.locale);
  I18N.applyDom(document);
  document.documentElement.lang = I18N.locale();
  window.refreshThemeLabels?.();
  paintSidebarCollapsed(document.body.classList.contains('sidebar-collapsed'));
  document.title = `${state.me.tenant.name} · ${STR.dashboardWord}`;
  $('#brand-name').textContent = state.me.tenant.name;
  $('#brand-sub').textContent = state.me.tenant.slug;
  paintBrandLogo();
  $('#principal-type').textContent = state.me.principalType;
  // An operator opening the dashboard has no user account here.
  const accountBtn = $('#btn-account');
  accountBtn.hidden = !(state.me.user && state.me.principalType === 'tenant');
  accountBtn.textContent = accountInitials(state.me.user?.email);
  // An operator who opened this company from the backoffice gets a way back, one click away.
  $('#btn-backoffice').hidden = state.me.principalType !== 'operator-imp';
  // An employee's session reaches only its own pages: no bell, no Home figures.
  if (!isPortal()) startNotifications();
  if (!state.me.modules.includes(state.active)) state.active = state.me.modules[0] || 'settings';
  // The address may still name the previous session's page, or one this session can't open.
  const hashTab = (location.hash || '').replace('#', '');
  if (hashTab && hashTab !== state.active) history.replaceState(null, '', `#${state.active}`);
  renderNav();
  if (state.active !== 'overview' && !isPortal()) {
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
  const toApprove = state.fetched.submissions
    ? state.pendingSubmissions.length
    : (o.employees?.pendingSubmissions || 0);
  const counts = {
    contacts: state.contacts.length || o.inbox?.contacts || 0,
    conversations: waiting || state.conversations.length || o.inbox?.conversations || 0,
    clients: state.clients.length || o.customers?.total || 0,
    quotes: state.quotes.length || o.pipeline?.quoteCount || 0,
    invoices: overdue || state.invoices.length || o.cash?.invoiceCount || 0,
    catalog: state.catalog.length || o.catalog?.items || 0,
    services: (state.clientServices.filter(s => s.status === 'OPEN').length) || o.services?.openCount || 0,
    suppliers: state.suppliers.length || o.suppliers?.total || 0,
    employees: toApprove || state.employees.length || o.employees?.total || 0,
    payments: overduePay || state.payments.length || o.payments?.paymentCount || 0,
    bookings: pendingBookings || (state.fetched.bookings ? weekBookings().filter(b => b.status !== 'CANCELLED').length : o.calendar?.thisWeek) || 0,
    instagram: pendingIg || (state.instagram?.media || []).length,
    [PORTAL_TAB]: state.portal.submissions.filter(s => s.status === 'PENDING').length,
    [SITES_TAB]: state.mySites.filter(s => !s.todayEntryId).length,
  };
  const agentsWaiting = window.AgentsUI?.badge() || (o.agents ? { count: (o.agents.pendingApprovals || 0) + (o.agents.openTasks || 0), alert: (o.agents.pendingApprovals || 0) > 0 } : null);
  if (agentsWaiting?.count) counts.agents = agentsWaiting.count;
  const shiftsToReview = window.TimesheetsUI?.badge();
  if (shiftsToReview?.count) counts.timesheets = shiftsToReview.count;
  const unreadEmail = window.EmailUI?.badge()?.count ?? o.email?.unread ?? 0;
  if (unreadEmail) counts.email = unreadEmail;
  const alerts = { conversations: waiting > 0, invoices: overdue > 0, payments: overduePay > 0, employees: toApprove > 0, bookings: pendingBookings > 0, instagram: pendingIg > 0, agents: !!agentsWaiting?.alert, email: unreadEmail > 0 };
  const building = buildingNavCounts(o);
  Object.assign(counts, building.counts);
  Object.assign(alerts, building.alerts);
  const groups = [
    { id: 'home', items: ['overview', HOURS_TAB, PORTAL_TAB, SITES_TAB] },
    { id: 'groupInbox', items: ['conversations', 'contacts', 'email', 'instagram'] },
    { id: 'groupBusiness', items: ['clients', 'services', 'quotes', 'invoices', 'financeiro', 'suppliers', 'employees', 'timesheets', 'payments', 'catalog', 'bookings'] },
    { id: 'groupBuilding', items: ['projects', 'site-diary', 'site-issues', 'change-orders', 'measurements'] },
    { id: 'groupBot', items: ['persona', 'ai-assistant', 'agents'] },
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
      return `<a class="nav__item${m === state.active ? ' is-active' : ''}" data-tab="${m}" href="#${m}" title="${escapeHTML(labels[m] || m)}"><span class="nav__dot"></span><span class="nav__label">${labels[m] || m}</span>${countHtml}</a>`;
    }).join('');
    return `<div class="nav__group">${label}${links}</div>`;
  }).join('');
  $$('.nav__item').forEach(a => a.addEventListener('click', async e => { e.preventDefault(); await setActive(a.dataset.tab); }));
}

async function setActive(tab) {
  if (isPortal() && !state.me.modules.includes(tab)) tab = state.me.modules[0] || PORTAL_TAB;
  if (tab !== 'conversations') stopInboxPolling();
  if (tab !== 'email') window.EmailUI?.stop();
  state.active = tab;
  location.hash = tab;
  $('#search').value = '';
  state.search = '';
  state.filterQuoteStatus = '';
  state.filterInvoiceStatus = '';
  state.filterServiceStatus = '';
  state.filterServiceClient = '';
  state.filterServiceEmployee = '';
  state.filterServicePeriod = '';
  state.filterServicePeriodKey = '';
  state.filterInvoicePeriod = '';
  state.filterInvoicePeriodKey = '';
  state.filterFinanceiroPeriod = '';
  state.filterFinanceiroPeriodKey = '';
  state.filterFinanceiroType = '';
  state.filterFinanceiroClient = '';
  state.filterPaymentStatus = '';
  state.filterPaymentSupplier = '';
  state.filterSupplierType = '';
  state.filterSubmissionStatus = '';
  state.archivedView = '';
  state.filterClientStanding = '';
  state.selectedRows = {};
  state.filterEmployeeView = '';
  try {
    await loadModule(tab);
    render();
  } catch {
    toast(STR.loadFailed);
  }
}

async function loadModule(tab) {
  if (tab === 'overview') state.overview = await api('/app/api/overview?extended=1');
  if (tab === 'contacts') state.contacts = await api('/app/api/contacts');
  if (tab === 'conversations') {
    state.conversations = await api('/app/api/conversations');
    state.fetched.conversations = true;
  }
  if (tab === 'clients') {
    // The list's Status, Billed and Owes come from the company's invoices.
    const [clients, invoices] = await Promise.all([
      api('/app/api/crm/clients'),
      hasModule('invoices') ? api('/app/api/crm/invoices').catch(() => null) : Promise.resolve(null),
      loadClientFields(true),
    ]);
    state.clients = clients;
    if (invoices) {
      state.invoices = invoices;
      state.fetched.invoices = true;
    }
    state.clientBalances = invoices ? new Map() : null;
  }
  if (tab === 'suppliers') state.suppliers = await api('/app/api/crm/suppliers');
  if (tab === 'employees') {
    const [employees, pending] = await Promise.all([
      api('/app/api/crm/employees'),
      submissionsOn() ? api('/app/api/crm/service-submissions?status=PENDING').catch(() => null) : Promise.resolve([]),
    ]);
    state.employees = employees;
    if (pending) state.pendingSubmissions = pending;
    state.fetched.submissions = submissionsOn() && !!pending;
  }
  if (tab === PORTAL_TAB) {
    const [submissions, clients, catalog] = await Promise.all([
      api('/app/api/portal/services'),
      api('/app/api/portal/clients'),
      api('/app/api/portal/catalog').catch(() => state.portal.catalog),
    ]);
    state.portal = { submissions, clients, catalog };
  }
  if (tab === HOURS_TAB) await window.TimesheetsUI.loadMine();
  if (tab === SITES_TAB) state.mySites = await api('/app/api/portal/sites');
  if (tab === 'timesheets') await window.TimesheetsUI.loadTeam();
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
    if (hasModule('employees')) state.employees = await api('/app/api/crm/employees').catch(() => state.employees);
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
  if (tab === 'catalog') {
    const [catalog, clients] = await Promise.all([
      api('/app/api/crm/standard-items'),
      hasModule('clients') ? api('/app/api/crm/clients').catch(() => state.clients) : Promise.resolve(state.clients),
    ]);
    state.catalog = catalog;
    state.clients = clients;
  }
  if (tab === 'persona') {
    state.persona = await api('/app/api/persona');
    if (state.personaTab === 'menus') await window.MenusUI.load().catch(() => { state.personaFlows = { failed: true }; });
  }
  if (tab === 'ai-assistant') await window.AssistantUI.load();
  if (tab === 'settings') {
    state.webWidget = await api('/app/api/web-widget').catch(() => ({ publicKey: null, allowedOrigins: [] }));
    state.whatsAppSignup = await api('/app/api/whatsapp/embedded-signup/config').catch(() => ({ enabled: false }));
    await refreshGoogle();
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
  if (tab === 'projects') await ensureBuildingProjects(true);
  if (tab === 'site-diary') {
    const today = todayKey();
    const range = state.diaryProject ? `projectId=${encodeURIComponent(state.diaryProject)}` : `from=${addDayKey(today, -30)}&to=${today}`;
    [state.diary] = await Promise.all([api(`/app/api/building/diary?${range}`), ensureBuildingProjects(true)]);
  }
  if (tab === 'site-issues') {
    [state.issues] = await Promise.all([api('/app/api/building/issues'), ensureBuildingProjects(true)]);
    state.fetched.issues = true;
  }
  if (tab === 'change-orders') {
    [state.changeOrders] = await Promise.all([api('/app/api/building/changes'), ensureBuildingProjects(true)]);
    state.fetched.changeOrders = true;
  }
  if (tab === 'measurements') {
    [state.measurements] = await Promise.all([api('/app/api/building/measurements'), ensureBuildingProjects(true)]);
    state.fetched.measurements = true;
  }
  if (tab === 'agents') await window.AgentsUI.load();
  if (tab === 'email') await window.EmailUI.load();
  if (tab === 'instagram') {
    try { state.instagram = await api('/app/api/instagram?refresh=1'); }
    catch { toast(STR.instagramSyncFailed); state.instagram = await api('/app/api/instagram').catch(() => state.instagram); }
    state.fetched.instagram = true;
  }
}

function render() {
  renderNav();
  renderCompanySwitch();
  renderBell();
  $('#crumb-leaf').textContent = labels[state.active] || state.active;
  $('#crumb-leaf').dataset.tab = state.active;
  pageCount(null);
  $('#meta-clock').textContent = new Date().toLocaleString(uiLocale(), { hour: '2-digit', minute: '2-digit' });
  updateSidebarKpis();
  $('#btn-new').hidden = !['clients', 'services', 'quotes', 'invoices', 'suppliers', 'employees', 'timesheets', 'payments', 'catalog', 'bookings', 'agents', PORTAL_TAB,
    'projects', 'site-diary', 'site-issues', 'change-orders', 'measurements'].includes(state.active)
    || (state.active === 'agents' && !window.AgentsUI?.canManage());
  const newButtonLabels = {
    clients: STR.clientFormTitle, services: CRM.services.formTitle, quotes: STR.quoteFormTitle,
    invoices: STR.invoiceFormTitle, suppliers: CRM.suppliers.formTitle, employees: CRM.employees.formTitle, payments: CRM.payments.formTitle, catalog: STR.catalogFormTitle, bookings: STR.bookingsNew,
    agents: I18N.t('app.agents.newAgent'), [PORTAL_TAB]: PORTAL.newTitle, timesheets: TIME.addShift,
    projects: BLD.formNewTitle, 'site-diary': BLD.diaryNew, 'site-issues': BLD.issueNew, 'change-orders': BLD.changeNew, measurements: BLD.measurementNew,
  };
  $('#btn-new .btn__label').textContent = newButtonLabels[state.active] || `${STR.newPrefix} ${labels[state.active] || ''}`;
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
  if (state.active === 'ai-assistant') return window.AssistantUI.render(root);
  if (state.active === 'bookings') return renderBookings(root);
  if (state.active === 'instagram') return renderInstagram(root);
  if (state.active === 'agents') return window.AgentsUI.render(root);
  if (state.active === 'email') return window.EmailUI.render(root);
  if (state.active === PORTAL_TAB) return renderMyServices(root);
  if (state.active === HOURS_TAB) return window.TimesheetsUI.renderMine(root);
  if (state.active === SITES_TAB) return renderMySites(root);
  if (state.active === 'timesheets') return window.TimesheetsUI.renderTeam(root);
  if (state.active === 'projects') return renderProjects(root);
  if (state.active === 'site-diary') return renderSiteDiary(root);
  if (state.active === 'site-issues') return renderSiteIssues(root);
  if (state.active === 'change-orders') return renderChangeOrders(root);
  if (state.active === 'measurements') return renderMeasurements(root);
  renderSettings(root);
}

function updateSidebarKpis() {
  const label1 = $('#kpi-1-label') || $$('.kpi__label')[0];
  const label2 = $('#kpi-2-label') || $$('.kpi__label')[1];
  const hours = isPortal() && (state.active === HOURS_TAB || !hasModule(PORTAL_TAB)) ? window.TimesheetsUI?.kpis() : null;
  if (hours) {
    if (label1) label1.textContent = hours[0].label;
    if (label2) label2.textContent = hours[1].label;
    $('#kpi-messages').textContent = hours[0].value;
    $('#kpi-users').textContent = hours[1].value;
    return;
  }
  if (isPortal()) {
    const mine = state.portal.submissions;
    if (label1) label1.textContent = SUB.status.PENDING;
    if (label2) label2.textContent = PORTAL.statApprovedMonth;
    $('#kpi-messages').textContent = mine.filter(s => s.status === 'PENDING').length;
    $('#kpi-users').textContent = approvedThisMonth(mine).length;
    return;
  }
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

// ── Agents module glue: agents.js draws the module; these open the records its runs are about. ──
function recordStatusLabel(entity, status) {
  if (!status) return '';
  if (entity === 'quote') return quoteStatusLabel(status);
  if (entity === 'invoice') return invoiceStatusLabel(status);
  if (entity === 'payment') return paymentStatusLabel(status);
  if (entity === 'booking') return bookingStatusLabel(status);
  if (entity === 'project') return projectStatusLabel(status);
  if (entity === 'site_issue' || entity === 'issue') return issueStatusLabel(status);
  if (entity === 'change_order' || entity === 'change') return changeStatusLabel(status);
  if (entity === 'measurement') return measurementStatusLabel(status);
  return status;
}
const AGENT_SUBJECT_MODULES = {
  client: 'clients', quote: 'quotes', invoice: 'invoices', payment: 'payments', booking: 'bookings', service: 'services', conversation: 'conversations', email: 'email',
  project: 'projects', site_log: 'site-diary', site_issue: 'site-issues', change_order: 'change-orders', measurement: 'measurements',
};
const AGENT_SUBJECT_OPENERS = {
  client: id => openClientDrawer(id),
  quote: id => openQuoteDetail(id),
  invoice: id => openInvoiceDetail(id),
  payment: id => openPaymentDetail(id),
  booking: id => openBookingById(id),
  service: id => openServiceDetail(id),
  project: id => openProjectDrawer(id),
  site_log: id => openDiaryEntry(id),
  site_issue: id => openIssueDetail(id),
  change_order: id => openChangeDetail(id),
  measurement: id => openMeasurementDetail(id),
};
// Trail keys are `${type}:${id}` like subjects (Building's drawers use shorter types), so a run opened from its own
// record doesn't link back to it.
const AGENT_SUBJECT_TRAIL = { site_log: 'diary', site_issue: 'issue', change_order: 'change' };
const canOpenAgentSubject = subject => !!subject && hasModule(AGENT_SUBJECT_MODULES[subject.type]) &&
  !linksBackTo(`${AGENT_SUBJECT_TRAIL[subject.type] || subject.type}:${subject.id}`);
async function openAgentSubject(subject, back) {
  if (!canOpenAgentSubject(subject)) return;
  if (subject.type === 'conversation') {
    closeDrawer({ dismissed: true });
    state.selectedConversation = subject.id;
    await setActive('conversations');
    return;
  }
  if (subject.type === 'email') return openEmailPage(subject.id);
  openFrom(back, () => AGENT_SUBJECT_OPENERS[subject.type](subject.id));
}
// The Email page with the thread of the email [id] open.
async function openEmailPage(id) {
  closeDrawer({ dismissed: true });
  window.EmailUI.focus(id);
  if (state.active === 'email') render();
  else await setActive('email');
}
function agentsDeps() {
  return {
    api, escapeHTML, toast, confirmDialog, openDrawer, closeDrawer, openFrom, bindDrawerClose,
    drawerGen: () => drawerGen,
    hero, statCards, crmPanel, recordKpisHtml, detailMeta,
    fmtDate, fmtDay, fmtWhen, fmtEUR, relTime, localDay, uiLocale,
    render, hasModule, labels, STR, CRM,
    get state() { return state; },
    statusLabel: recordStatusLabel,
    openSubject: openAgentSubject,
    canOpenSubject: canOpenAgentSubject,
    // Something changed in Agents while another view is showing: keep Home's card and rows and the nav count in step.
    onChange: () => {
      if (state.active !== 'overview') return renderNav();
      loadModule('overview').then(render).catch(() => renderNav());
    },
  };
}
// The company's admins (and operators opening its dashboard) change how it works for the whole team.
const isCompanyAdmin = () => state.me?.principalType === 'operator-imp' || state.me?.user?.role === 'TENANT_ADMIN';
function timesheetsDeps() {
  return {
    api, escapeHTML, toast, confirmDialog, openDrawer, closeDrawer, openFrom, linksBackTo, download: downloadFile,
    hero, statCards, periodNav, detailMeta, recordTable, recordEmpty,
    fmtDate, fmtDay, fmtDayKey, fmtWhen, relTime, uiLocale, tenantTz,
    todayKey, addDayKey, periodKey, currentPeriodKey, shiftPeriodKey,
    render, hasModule, isPortal, isAdmin: isCompanyAdmin, openPayeeDrawer, labels, STR, CRM,
    get state() { return state; },
  };
}
function assistantDeps() {
  return {
    api, escapeHTML, toast, confirmDialog, openDrawer, closeDrawer, hero,
    fmtDate, fmtDay, fmtEUR, fmtMinutes, relTime,
    hasModule, labels, STR, pdfButton, wirePdfButtons,
    get state() { return state; },
    statusLabel: recordStatusLabel,
    openSubject: openAgentSubject,
    canOpenSubject: canOpenAgentSubject,
    onChange: () => renderNav(),
  };
}
// The Email page opens the dashboard's own forms, started from what an email says, and opens the records they made.
function emailDeps() {
  return {
    api, escapeHTML, toast, confirmDialog, closeDrawer, hero, render, renderNav, setActive,
    fmtDate, fmtDay, fmtTime, fmtEUR, relTime, localDay, uiLocale, newRequestId,
    hasModule, labels, STR, isAdmin: isCompanyAdmin, isNarrow: isNarrowInbox, statusLabel: recordStatusLabel,
    get state() { return state; },
    // A record made from an email changes the nav's counts (clients, quotes, payments…), which come from Home's figures.
    refreshCounts: async () => { state.overview = await api('/app/api/overview').catch(() => state.overview); renderNav(); },
    googleTextOr, connectGoogle, openSettingsAccount: async id => { state.settingsSection = 'channels'; await setActive('settings'); if (id) openEmailAccount(id); },
    openClientForm, openClientDrawer, openQuoteForm, openQuoteDetail, openInvoiceDetail, openPaymentForm, openPaymentDetail, openSupplierForm,
    openBookingById, openBookingForm: async (opts) => { await ensureBookingData(); openBookingForm(null, opts); },
    openTask: (subject, opts) => window.AgentsUI?.openTask(null, subject, opts),
    openRecord: subject => openAgentSubject(subject, null),
    canOpenRecord: subject => canOpenAgentSubject(subject),
  };
}
// The Persona page's Menus tab; the test chat beside it stays here and tries the unsaved menus.
function menusDeps() {
  return {
    api, escapeHTML, toast, confirmDialog, render, renderKeepingFocus, setActive, relTime, fmtCount, personaNotice, personaAuthor,
    fail: personaFail,
    get state() { return state; },
    onDraft: paintPersonaDraft,
    tryInChat: tryPersonaMenu,
  };
}
// Under a quote, invoice or booking: what agents did on it, what's next and "run an agent". Stays out
// of the way (hidden) when there's none of that.
function mountRecordAutomations(parent, subject, back) {
  if (!hasModule('agents') || !window.AgentsUI) return;
  const el = document.createElement('div');
  el.hidden = true;
  // Above the drawer's pinned foot, when it has one.
  parent.insertBefore(el, [...parent.children].find(c => c.classList.contains('drawer__foot')) || null);
  window.AgentsUI.mountAutomations(el, subject, { back, hideEmpty: true }).then(() => { el.hidden = !el.childElementCount; });
}

// The desktop sidebar folds to a rail of icons; each browser remembers the choice. Phones keep the slide-in menu.
const SIDEBAR_COLLAPSED_KEY = 'ui:sidebarCollapsed';
function paintSidebarCollapsed(collapsed) {
  document.body.classList.toggle('sidebar-collapsed', collapsed);
  const btn = $('#btn-sidebar');
  const label = collapsed ? STR.sidebarExpand : STR.sidebarCollapse;
  btn.setAttribute('aria-expanded', String(!collapsed));
  btn.setAttribute('aria-label', label);
  btn.title = label;
}
function wireSidebarToggle() {
  let collapsed = false;
  try { collapsed = localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1'; } catch { /* storage blocked: start open */ }
  paintSidebarCollapsed(collapsed);
  $('#btn-sidebar').addEventListener('click', () => {
    collapsed = !collapsed;
    try { localStorage.setItem(SIDEBAR_COLLAPSED_KEY, collapsed ? '1' : '0'); } catch { /* keep it for this page only */ }
    paintSidebarCollapsed(collapsed);
  });
}

// `relayed`: this is a consent popup that already passed its outcome on and couldn't close.
async function init(relayed = false) {
  if (!relayed && handleOAuthPopup()) return;
  window.AgentsUI?.init(agentsDeps());
  window.TimesheetsUI?.init(timesheetsDeps());
  window.AssistantUI?.init(assistantDeps());
  window.EmailUI?.init(emailDeps());
  window.MenusUI?.init(menusDeps());
  I18N.applyDom(document);
  wireSidebarToggle();
  $('#btn-logout').addEventListener('click', () => signOut());
  $('#btn-account').addEventListener('click', () => { drawerTrail = []; openAccount(); });
  $('#btn-notifications').addEventListener('click', () => { drawerTrail = []; openNotifications(); });
  $('#brand').addEventListener('click', () => { drawerTrail = []; openCompanySwitcher(); });
  $('#search').addEventListener('input', e => { state.search = e.target.value; render(); });
  $('#btn-new').addEventListener('click', () => {
    if (state.active === PORTAL_TAB) return openSubmissionForm();
    if (state.active === 'timesheets') return window.TimesheetsUI.newShift();
    if (state.active === 'clients') return openClientForm();
    if (state.active === 'services') {
      const client = (state.filterServicePeriod === 'client' && state.serviceItemsClient) || state.filterServiceClient;
      return openServiceForm(null, client || undefined, state.filterServiceEmployee || undefined);
    }
    if (state.active === 'catalog') return openCatalogForm();
    if (state.active === 'quotes') return openQuoteForm();
    if (state.active === 'invoices') return openInvoiceForm();
    if (state.active === 'suppliers') return openSupplierForm();
    if (state.active === 'employees') return openEmployeeForm();
    if (state.active === 'payments') return openPaymentForm();
    if (state.active === 'bookings') return openBookingForm();
    if (state.active === 'agents') return window.AgentsUI.newAgent();
    if (state.active === 'projects') return openProjectForm();
    if (state.active === 'site-diary') return openDiaryForm(null, { projectId: state.diaryProject });
    if (state.active === 'site-issues') return openIssueForm(null, { projectId: state.issuesProject });
    if (state.active === 'change-orders') return openChangeForm(null, { projectId: state.changesProject });
    if (state.active === 'measurements') return startMeasurement(state.measurementsProject);
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
  document.addEventListener('ui:theme', () => {
    if (token && state.me && state.active === 'settings' && state.settingsSection === 'appearance') render();
  });
  // Inbox and notification polls pause while the tab is hidden; catch up at once when it comes back.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible' || !token) return;
    if (state.me && Date.now() - notificationsAt > NOTIFICATIONS_POLL_MS) loadNotifications().catch(() => {});
    if (state.active !== 'conversations' || !$('.inbox')) return;
    const root = $('#view');
    refreshInboxList(root).catch(() => {});
    if (state.inbox.threadFor) refreshThread(root).catch(() => {});
  });
  const google = takeGoogleRedirect();
  if (!token) return renderLogin();
  state.active = (location.hash || '').replace('#', '') || 'overview';
  if (google) state.settingsSection = 'channels';
  try { await bootAuthed(); } catch { renderLogin(); return; }
  if (google) googleOutcome(google.status, google.reason);
}
init();
