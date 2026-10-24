/* Suppliers and Employees (/app), the people the company pays ("payees"): both lists and their columns,
   the record they share (what is owed to them and when), and the supplier and employee forms. */

const supplierServiceNames = s => (s.services || []).map(x => x.description).join(', ');

function supplierColumns() {
  const t = CRM.suppliers;
  const name = s => {
    const services = supplierServiceNames(s);
    return services ? `<div class="col"><span class="name">${escapeHTML(s.name)}</span><span class="sub">${escapeHTML(services)}</span></div>` : escapeHTML(s.name);
  };
  return [
    { id: 'name', label: t.thName, fixed: true, cell: s => `<td class="name long">${name(s)}</td>` },
    { id: 'type', label: t.thType, default: true, cell: s => `<td class="muted">${escapeHTML(s.type || '')}</td>` },
    { id: 'address', label: t.thAddress, default: true, cell: s => `<td class="muted long">${escapeHTML(s.address || '')}</td>` },
    { id: 'phone', label: t.thPhone, default: true, cell: s => `<td class="mono muted nowrap">${escapeHTML(s.phone)}</td>` },
    { id: 'createdAt', label: t.thCreated, default: true, cell: s => `<td class="mono nowrap">${fmtDay(s.createdAt)}</td>` },
    { id: 'number', label: t.thNo, default: true, head: 'right', cell: s => `<td class="id right">${escapeHTML(s.number)}</td>` },
  ];
}

function employeeColumns() {
  const t = CRM.employees;
  const toApprove = new Map();
  for (const s of state.pendingSubmissions) toApprove.set(s.employeeId, (toApprove.get(s.employeeId) || 0) + 1);
  const pendingPill = id => (toApprove.get(id) ? ` <span class="pill pill--warn">${escapeHTML(SUB.toApproveN({ n: toApprove.get(id) }))}</span>` : '');
  return [
    { id: 'name', label: t.thName, fixed: true, cell: e => `<td class="name long">${escapeHTML(e.name)}${pendingPill(e.id)}</td>` },
    { id: 'role', label: t.thRole, default: true, cell: e => `<td class="muted">${escapeHTML(e.role || '')}</td>` },
    { id: 'phone', label: t.thPhone, default: true, cell: e => `<td class="mono muted nowrap">${escapeHTML(e.phone)}</td>` },
    { id: 'taxId', label: t.formTaxId, cell: e => `<td class="mono muted nowrap">${escapeHTML(e.taxId || '')}</td>` },
    { id: 'birthDate', label: t.formBirthDate, cell: e => `<td class="mono muted nowrap">${escapeHTML(e.birthDate ? fmtDay(e.birthDate) : '')}</td>` },
    { id: 'address', label: t.formAddress, cell: e => `<td class="muted long">${escapeHTML(e.address || '')}</td>` },
    { id: 'createdAt', label: t.thCreated, default: true, cell: e => `<td class="mono nowrap">${fmtDay(e.createdAt)}</td>` },
    { id: 'number', label: t.thNo, default: true, head: 'right', cell: e => `<td class="id right">${escapeHTML(e.number)}</td>` },
  ];
}

const supplierTypeKey = type => String(type || '').trim().toLocaleLowerCase();

// Types differing only in case or spacing are one type, shown as first spelled.
function supplierTypes(suppliers) {
  const byKey = new Map();
  for (const s of suppliers) {
    const key = supplierTypeKey(s.type);
    if (key && !byKey.has(key)) byKey.set(key, s.type.trim());
  }
  return [...byKey.values()].sort((a, b) => a.localeCompare(b, uiLocale(), { sensitivity: 'base' }));
}

function renderSuppliers(root) {
  const t = CRM.suppliers;
  const archived = state.archivedView === 'suppliers';
  const source = archived ? state.archivedRows : (state.suppliers || []);
  const q = state.search.toLowerCase();
  const types = supplierTypes(source);
  const typeKey = supplierTypeKey(state.filterSupplierType);
  if (typeKey && !types.some(v => supplierTypeKey(v) === typeKey)) state.filterSupplierType = '';
  const columns = shownColumns('suppliers', supplierColumns());
  const ids = new Set(columns.map(c => c.id));
  const rows = source
    .filter(s => !state.filterSupplierType || supplierTypeKey(s.type) === typeKey)
    .filter(s => !q || `${s.number || ''} ${s.name || ''} ${s.type || ''} ${s.phone || ''} ${s.address || ''} ${supplierServiceNames(s)}`.toLowerCase().includes(q))
    .map(s => `<tr class="conversation-row" data-supplier="${escapeHTML(s.id)}">${columnCells(columns, s, ids)}</tr>`)
    .join('');
  const typeFilter = types.length
    ? `<select class="sel" data-filter-supplier-type aria-label="${escapeHTML(t.filterTypeAria)}"><option value="">${escapeHTML(t.filterTypeAll)}</option>${types.map(v => `<option value="${escapeHTML(v)}" ${supplierTypeKey(v) === supplierTypeKey(state.filterSupplierType) ? 'selected' : ''}>${escapeHTML(v)}</option>`).join('')}</select>`
    : '';
  const filtered = Boolean(state.filterSupplierType || q);
  const new30 = (state.suppliers || []).filter(s => s.createdAt && (Date.now() - new Date(s.createdAt)) / 86400000 <= 30).length;
  root.innerHTML = hero(labels.suppliers, CRM.tabs.fornecedores.desc, statCards([
    { label: t.total, value: (state.suppliers || []).length },
    { label: t.new30, value: new30 },
  ])) + crmPanel({
    title: t.directory,
    tag: source.length,
    tools: typeFilter + directoryViewChips('suppliers') + columnsButton('suppliers') + columnOrderButton('suppliers'),
    head: columnsHead(columns, 'suppliers'),
    rows,
    empty: archived ? STR.directoryArchivedEmpty : (filtered ? t.emptyFiltered : t.emptyTitle),
    emptyDesc: archived ? STR.directoryArchivedEmptyDesc : (filtered ? t.emptyFilteredDesc : t.emptyDesc),
  });
  $$('[data-supplier]', root).forEach(r => r.addEventListener('click', () => openPayeeDrawer('supplier', source.find(s => s.id === r.dataset.supplier) || r.dataset.supplier)));
  $('[data-filter-supplier-type]', root)?.addEventListener('change', e => { state.filterSupplierType = e.target.value; render(); });
  $('[data-columns]', root)?.addEventListener('click', () => openColumnPicker('suppliers', supplierColumns(), labels.suppliers));
  wireColumnOrder(root, 'suppliers', supplierColumns());
  wireDirectoryView(root);
}

function renderEmployees(root) {
  const t = CRM.employees;
  const archived = state.archivedView === 'employees';
  const source = archived ? state.archivedRows : (state.employees || []);
  const q = state.search.toLowerCase();
  const columns = shownColumns('employees', employeeColumns());
  const ids = new Set(columns.map(c => c.id));
  const rows = source
    .filter(e => !q || `${e.number || ''} ${e.name || ''} ${e.phone || ''} ${e.role || ''} ${e.taxId || ''}`.toLowerCase().includes(q))
    .map(e => `<tr class="conversation-row" data-employee="${escapeHTML(e.id)}">${columnCells(columns, e, ids)}</tr>`)
    .join('');
  const new30 = (state.employees || []).filter(e => e.createdAt && (Date.now() - new Date(e.createdAt)) / 86400000 <= 30).length;
  root.innerHTML = hero(labels.employees, CRM.tabs.colaboradores.desc, statCards([
    { label: t.total, value: (state.employees || []).length },
    { label: t.new30, value: new30 },
    ...(state.fetched.submissions ? [{ label: SUB.kpiToApprove, value: state.pendingSubmissions.length }] : []),
  ])) + crmPanel({
    title: t.directory,
    tag: source.length,
    tools: directoryViewChips('employees', { active: t.viewActive, archived: t.viewInactive }) + columnsButton('employees') + columnOrderButton('employees'),
    head: columnsHead(columns, 'employees'),
    rows,
    empty: archived ? t.inactiveEmpty : t.emptyTitle,
    emptyDesc: archived ? t.inactiveEmptyDesc : t.emptyDesc,
  });
  $$('[data-employee]', root).forEach(r => r.addEventListener('click', () => openPayeeDrawer('employee', source.find(e => e.id === r.dataset.employee) || r.dataset.employee)));
  $('[data-columns]', root)?.addEventListener('click', () => openColumnPicker('employees', employeeColumns(), labels.employees));
  wireColumnOrder(root, 'employees', employeeColumns());
  wireDirectoryView(root);
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
  const work = kind === 'employee' && submissionsOn();
  const signIn = kind === 'employee' && employeePortalOn();
  const assigned = kind === 'employee' && hasModule('services');
  const [payee, payments, submissions, access, time, services] = await Promise.all([
    api(`/app/api/crm/${cfg.path}/${q}`).catch(() => known),
    hasModule('payments') ? api(`/app/api/crm/payments?${cfg.filter}=${q}`).catch(() => []) : Promise.resolve([]),
    work ? api(`/app/api/crm/service-submissions?employeeId=${q}`).catch(() => []) : Promise.resolve([]),
    signIn ? api(`/app/api/crm/employees/${q}/access`).catch(() => null) : Promise.resolve(null),
    kind === 'employee' && window.TimesheetsUI ? window.TimesheetsUI.loadRecord(id) : Promise.resolve(null),
    assigned ? api(`/app/api/crm/services?employeeId=${q}`).catch(() => []) : Promise.resolve(null),
  ]);
  if (gen !== drawerGen) return;
  if (!payee) { closeDrawer({ dismissed: true }); return toast(STR.loadFailed); }
  payeeRecord = { kind, payee, payments: payments || [], submissions: submissions || [], access, time, services, el: body };
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
    p.type ? `<span class="record-card__line">${escapeHTML(p.type)}</span>` : '',
    `<span class="record-card__line mono">${escapeHTML(p.phone)}</span>`,
    p.address ? `<a class="record-card__line" href="${escapeHTML(mapsHref(p.address))}" target="_blank" rel="noopener">${escapeHTML(p.address)}</a>` : '',
    p.role ? `<span class="record-card__line">${escapeHTML(p.role)}</span>` : '',
    p.birthDate ? `<span class="record-card__line">${escapeHTML(STR.employeeBorn({ date: fmtDayKey(p.birthDate.slice(0, 10), { dateStyle: 'medium' }), age: ageOn(p.birthDate) }))}</span>` : '',
    p.taxId ? `<span class="record-card__line mono">${escapeHTML(STR.clientTaxIdShort({ id: p.taxId }))}</span>` : '',
  ].filter(Boolean).join('');
  const subs = r.submissions || [];
  const pendingSubs = subs.filter(s => s.status === 'PENDING');
  const work = kind === 'employee' && submissionsOn();
  const assigned = r.services ? employeeServiceSplit(r.services, today) : null;
  const since = [
    cfg.since(fmtDay(p.createdAt)),
    live.length ? STR.payeePayments({ n: live.length }) : '',
    subs.length ? SUB.registeredN({ n: subs.length }) : '',
    assigned && assigned.live.length ? t.servicesN({ n: assigned.live.length }) : '',
    lastPaid ? STR.payeeLastPaid({ when: relTime(lastPaid) }) : '',
  ].filter(Boolean).join(' · ');
  const workCells = !work ? [] : [{
    label: SUB.kpiToApprove,
    value: String(pendingSubs.length),
    sub: pendingSubs.length ? fmtEUR(sumBy(pendingSubs, 'totalEur')) : SUB.kpiNothingToApprove,
    tone: pendingSubs.length ? 'warn' : '',
  }];
  if (assigned) {
    const next = assigned.toDo[0];
    workCells.push({
      label: t.kpiToDo,
      value: String(assigned.toDo.length),
      sub: next ? t.kpiNextOn({ date: fmtDayKey(next.performedAt.slice(0, 10), { day: 'numeric', month: 'short' }) }) : t.kpiNothingToDo,
      tone: assigned.toDo.length ? 'info' : '',
    });
  }
  const cells = [...workCells, ...(!hasModule('payments') ? [] : [
    {
      label: STR.payeeKpiToPay,
      value: fmtEUR(sumBy(unpaid, 'totalEur')),
      sub: overdue.length ? STR.clientKpiOverdue({ amount: fmtEUR(sumBy(overdue, 'totalEur')) })
        : unpaid.length ? STR.payeeKpiUnpaid({ n: unpaid.length }) : STR.payeeKpiNothingDue,
      tone: overdue.length ? 'bad' : unpaid.length ? 'warn' : '',
    },
    ...(assigned ? [] : [{
      label: STR.payeeKpiNextDue,
      value: next ? fmtDayKey(next.dueDate.slice(0, 10), { day: 'numeric', month: 'short' }) : '—',
      sub: next ? `${fmtEUR(next.totalEur)} · ${next.number}` : STR.payeeKpiNoneDue,
    }]),
    { label: STR.payeeKpiPaid, value: fmtEUR(sumBy(paid, 'totalEur')), sub: STR.payeePayments({ n: paid.length }) },
  ])];
  const soon = addDayKey(today, 7);
  const attention = [
    ...overdue.map(x => ({
      tone: 'bad', title: STR.payeeAttnOverdue({ number: x.number }), detail: dueWhenText(x.dueDate, true), amount: x.totalEur, open: `payment:${x.id}`,
    })),
    ...pendingSubs.map(s => ({
      tone: 'warn', title: SUB.attnPending({ name: s.name }), detail: [s.clientName, fmtDay(s.performedAt)].filter(Boolean).join(' · '),
      amount: s.totalEur, open: `submission:${s.id}`,
    })),
    ...unpaid.filter(x => !isPastDue(x, today) && x.dueDate.slice(0, 10) <= soon).map(x => ({
      tone: 'warn', title: STR.clientAttnDueSoon({ number: x.number }), detail: dueWhenText(x.dueDate, false), amount: x.totalEur, open: `payment:${x.id}`,
    })),
  ];
  const inUse = r.payments.length > 0 || subs.length > 0 || (r.services || []).length > 0;
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
    ${kind === 'employee'
    ? recordCardHtml({ name: p.name, lines, since, contact: contactButtons(p.phone), actions: employeeStateButtons(p, inUse), warning: p.archivedAt ? t.inactiveSince({ date: fmtDay(p.archivedAt) }) : '' })
    : recordCardHtml({ name: p.name, lines, since, contact: contactButtons(p.phone), actions: recordRemovalButton(p), archivedAt: p.archivedAt })}
    ${recordKpisHtml(cells)}
    ${recordAttentionHtml(attention)}
    ${kind === 'employee' && employeePortalOn() ? employeeAccessHtml(p, r.access) : ''}
    ${kind === 'employee' && r.time ? window.TimesheetsUI.recordHtml(p, r.time) : ''}
    ${assigned ? employeeServicesHtml(r.services, today, !p.archivedAt) : ''}
    ${work ? employeeWorkHtml(subs, r.access) : ''}
    ${kind === 'supplier' ? supplierServicesPanel(p) : ''}
    ${payments}
    ${hasModule('payments') && !p.archivedAt ? recordFootHtml([['payment', t.addPayment]]) : ''}`;
  const here = () => payeeTrailEntry(kind, p);
  $('[data-record-edit]', body)?.addEventListener('click', () => openFrom(here(), () => cfg.edit(p)));
  const setArchivedAt = archivedAt => { r.payee = { ...p, archivedAt }; renderPayeeRecord(); refreshDirectory(cfg.path); };
  $('[data-record-remove]', body)?.addEventListener('click', () => removeDirectoryRecord(cfg.path, p, {
    inUse,
    ...(kind === 'employee'
      ? { archiveBody: t.deactivateBody, archiveTitle: t.deactivateTitle({ name: p.name }), archiveOk: t.deactivate, archivedToast: t.deactivated }
      : { archiveBody: STR.payeeArchiveBody }),
    onDeleted: () => { closeDrawer({ dismissed: true }); refreshDirectory(cfg.path); },
    onArchived: setArchivedAt,
  }));
  $('[data-record-restore]', body)?.addEventListener('click', () => restoreDirectoryRecord(cfg.path, p, () => setArchivedAt(null)));
  $('[data-employee-deactivate]', body)?.addEventListener('click', () => setEmployeeActive(p, false, setArchivedAt));
  $('[data-employee-reactivate]', body)?.addEventListener('click', () => setEmployeeActive(p, true, setArchivedAt));
  $$('[data-record-open]', body).forEach(el => el.addEventListener('click', () => {
    const [type, id] = el.dataset.recordOpen.split(':');
    openFrom(here(), () => (type === 'submission' ? openSubmissionDetail(id) : type === 'service' ? openServiceDetail(id) : openPaymentDetail(id)));
  }));
  $$('[data-record-act]', body).forEach(b => b.addEventListener('click', () => openFrom(here(), () => {
    const act = b.dataset.recordAct;
    if (act === 'services') return cfg.edit(p);
    if (act === 'employee-service') return openServiceForm(null, undefined, p.id);
    return cfg.pay(p);
  })));
  $('[data-employee-access]', body)?.addEventListener('click', () => openFrom(here(), () => openEmployeeAccessForm(p, r.access)));
  if (kind === 'employee' && r.time) {
    window.TimesheetsUI.wireRecord(body, p, here(), async () => {
      r.time = await window.TimesheetsUI.loadRecord(p.id);
      renderPayeeRecord();
    });
  }
}

// An employee's services: to do is open work dated today or later (soonest first), done is the rest
// that isn't cancelled (latest first).
function employeeServiceSplit(services, today) {
  const day = s => (s.performedAt || s.createdAt || '').slice(0, 10);
  const live = services.filter(s => s.status !== 'CANCELLED');
  const toDo = live.filter(s => s.status === 'OPEN' && s.performedAt && day(s) >= today).sort((a, b) => day(a).localeCompare(day(b)));
  const done = live.filter(s => !toDo.includes(s)).sort((a, b) => day(b).localeCompare(day(a)));
  return { live, toDo, done };
}

function employeeServicesHtml(services, today, canAdd) {
  const t = CRM.employees;
  if (!services.length) return recordEmpty(t.servicesNone, canAdd ? 'employee-service' : null, t.addService);
  const { toDo, done } = employeeServiceSplit(services, today);
  const cancelled = services.filter(s => s.status === 'CANCELLED');
  const row = (s, pill) => `<tr class="conversation-row${s.status === 'INVOICED' ? ' is-paid' : s.status === 'CANCELLED' ? ' is-draft' : ''}" data-record-open="service:${escapeHTML(s.id)}">
      <td class="mono muted">${escapeHTML(fmtDay(s.performedAt || s.createdAt))}</td><td class="name">${escapeHTML(s.clientName || '')}</td><td>${escapeHTML(s.name)}</td>
      <td>${pill}</td><td class="num right">${fmtEUR(s.totalEur)}</td></tr>`;
  const rows = [
    ...toDo.map(s => row(s, `<span class="pill pill--info">${escapeHTML(t.scheduled)}</span>`)),
    ...[...done, ...cancelled].map(s => row(s, servicePill(s.status))),
  ].join('');
  const s = CRM.services;
  return recordTable([[s.thWhen], [s.thClient], [s.thService], [s.thStatus], [s.thTotal, 'right']], rows, { title: t.servicesTitle, count: services.length })
    + (canAdd ? recordFootHtml([['employee-service', t.addService]]) : '');
}

// Employees are deactivated rather than archived: off the lists and pickers, sign-in refused, history kept.
// One with history can only be deactivated, so Delete shows only when nothing refers to them.
function employeeStateButtons(p, inUse) {
  const t = CRM.employees;
  return p.archivedAt
    ? `<button class="btn btn--sm btn--ghost" type="button" data-employee-reactivate>${escapeHTML(t.reactivate)}</button>`
    : `<button class="btn btn--sm btn--ghost" type="button" data-employee-deactivate>${escapeHTML(t.deactivate)}</button>${inUse ? '' : recordRemovalButton(p)}`;
}

async function setEmployeeActive(p, active, onChanged) {
  const t = CRM.employees;
  if (!active && !await confirmDialog({ title: t.deactivateTitle({ name: p.name }), body: t.deactivateBody, okLabel: t.deactivate, danger: false })) return;
  try {
    const res = await api(`/app/api/crm/employees/${encodeURIComponent(p.id)}/${active ? 'restore' : 'archive'}`, { method: 'POST' });
    toast(active ? t.reactivated : t.deactivated);
    onChanged(active ? null : res.archivedAt);
  } catch (err) { if (err?.message !== 'unauthorized') toast(STR.recordRemoveFailed); }
}

// A supplier's usual services, which a new payment to them offers as lines.
function supplierServicesPanel(p) {
  const t = CRM.suppliers;
  const list = p.services || [];
  if (!list.length) return p.archivedAt ? '' : recordEmpty(t.servicesEmpty, 'services', t.servicesAddFromRecord);
  const rows = list.map(s => `<tr>
    <td class="name">${escapeHTML(s.description)}</td>
    <td class="mono muted">${escapeHTML(s.unit || '')}</td>
    <td class="num right">${s.unitPriceEur == null ? `<span class="muted">${escapeHTML(t.servicePriceVaries)}</span>` : fmtEUR(s.unitPriceEur)}</td>
  </tr>`).join('');
  return recordTable([[t.serviceCol], [STR.lineColUnit], [t.servicePriceCol, 'right']], rows, { title: t.servicesLabel, count: list.length });
}

// The services a supplier usually does: description, unit and price (empty when it changes from job to
// job). A new payment to the supplier offers them as lines.
function supplierServicesField(services) {
  const t = CRM.suppliers;
  const removeIcon = '<svg width="12" height="12" viewBox="0 0 16 16"><path d="M3 3 L13 13 M13 3 L3 13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>';
  const html = `
    <div class="lines-field">
      <div class="lbl" id="sf-services-label">${escapeHTML(t.servicesLabel)} <span class="opt">${escapeHTML(STR.optional)}</span></div>
      <div class="lines lines--services" role="group" aria-labelledby="sf-services-label">
        <div class="lines__head"><span>${escapeHTML(t.serviceCol)}</span><span>${escapeHTML(STR.lineColUnit)}</span><span>${escapeHTML(t.servicePriceCol)}</span><span></span></div>
        <div id="sf-services"></div>
        <p class="lines__empty" id="sf-services-empty">${escapeHTML(t.servicesEmpty)}</p>
        <div class="lines__foot"><button class="btn btn--sm btn--ghost" type="button" id="sf-add-service">${escapeHTML(t.serviceAdd)}</button></div>
      </div>
      <p class="hint">${escapeHTML(t.servicesHint)}</p>
    </div>`;
  const collect = form => $$('#sf-services .line', form).map(row => {
    const price = $('[data-k="unitPriceEur"]', row).value;
    return {
      description: $('[data-k="description"]', row).value.trim(),
      unit: $('[data-k="unit"]', row).value.trim(),
      unitPriceEur: price === '' ? null : Number(price),
    };
  }).filter(s => s.description);
  const wire = form => {
    const body = $('#sf-services', form);
    const empty = () => { $('#sf-services-empty', form).hidden = !!body.children.length; };
    const addRow = (s = {}) => {
      const row = document.createElement('div');
      row.className = 'line';
      row.innerHTML = `
        <input type="text" data-k="description" maxlength="200" autocomplete="off" aria-label="${escapeHTML(t.serviceCol)}" placeholder="${escapeHTML(t.servicePh)}" value="${escapeHTML(s.description || '')}" />
        <input type="text" data-k="unit" class="num" maxlength="20" aria-label="${escapeHTML(STR.lineColUnit)}" placeholder="${escapeHTML(STR.lineUnitPh)}" value="${escapeHTML(s.unit || '')}" />
        <input type="number" data-k="unitPriceEur" class="num" min="0" step="0.01" aria-label="${escapeHTML(t.servicePriceCol)}" placeholder="${escapeHTML(t.servicePricePh)}" value="${s.unitPriceEur ?? ''}" />
        <button type="button" class="l-rm" title="${escapeHTML(STR.lineRemove)}" aria-label="${escapeHTML(STR.lineRemove)}">${removeIcon}</button>`;
      $('.l-rm', row).addEventListener('click', () => { row.remove(); empty(); });
      body.appendChild(row);
      empty();
      return row;
    };
    (services || []).forEach(addRow);
    empty();
    $('#sf-add-service', form).addEventListener('click', () => $('[data-k="description"]', addRow()).focus());
  };
  return { html, wire, collect };
}

// `opts.prefill` and `opts.onSaved(supplier)` work as on the client form.
async function openSupplierForm(supplier, opts = {}) {
  const t = CRM.suppliers;
  const editing = supplier && supplier.id ? supplier : null;
  const values = editing || opts.prefill || {};
  const services = supplierServicesField(editing?.services);
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__row"><label class="lbl" for="sf-name">${escapeHTML(t.formName)} <span class="req">●</span></label>
      <input class="inp" id="sf-name" required placeholder="${escapeHTML(t.phName)}" value="${escapeHTML(values.name || '')}" /></div>
    <div class="form__row"><label class="lbl" for="sf-type">${escapeHTML(t.formType)}</label>
      <input class="inp" id="sf-type" list="sf-type-options" maxlength="60" autocomplete="off" placeholder="${escapeHTML(t.phType)}" value="${escapeHTML(values.type || '')}" />
      <datalist id="sf-type-options">${supplierTypes(state.suppliers || []).map(v => `<option value="${escapeHTML(v)}"></option>`).join('')}</datalist></div>
    <div class="form__row"><label class="lbl" for="sf-phone">${escapeHTML(t.formPhone)} <span class="req">●</span></label>
      <input class="inp inp--mono" id="sf-phone" required placeholder="${escapeHTML(t.phPhone)}" value="${escapeHTML(values.phone || '')}" /></div>
    <div class="form__row"><label class="lbl" for="sf-address">${escapeHTML(t.formAddress)}</label>
      <input class="inp" id="sf-address" placeholder="${escapeHTML(t.phAddress)}" value="${escapeHTML(values.address || '')}" /></div>
    ${services.html}
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(editing ? STR.clientSaveChanges : t.save)}</button>
      ${editing ? `<button class="btn btn--ghost" type="button" data-form-cancel>${escapeHTML(STR.cancel)}</button>` : ''}
    </div>`;
  services.wire(form);
  $('[data-form-cancel]', form)?.addEventListener('click', () => closeDrawer());
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const name = $('#sf-name', form).value.trim();
    const phone = $('#sf-phone', form).value.trim();
    const address = $('#sf-address', form).value.trim() || undefined;
    const type = $('#sf-type', form).value.trim();
    if (!name || !phone) return toast(t.validate);
    const payload = { name, phone, address, type, services: services.collect(form) };
    if (payload.services.some(s => !(s.unitPriceEur == null || s.unitPriceEur >= 0))) return toast(t.err_service_price_invalid);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      if (editing) {
        await api(`/app/api/crm/suppliers/${encodeURIComponent(editing.id)}`, { method: 'PATCH', body: JSON.stringify(payload) });
        closeDrawer();
        await loadModule('suppliers');
        render();
        toast(t.updated);
      } else {
        const created = await api('/app/api/crm/suppliers', { method: 'POST', body: JSON.stringify(payload) });
        closeDrawer();
        if (opts.onSaved) { toast(t.created); return opts.onSaved(created); }
        await loadModule('suppliers');
        toast(t.created);
        if (hasModule('payments') && state.active === 'payments') return openPaymentForm(created.id);
        render();
        openPayeeDrawer('supplier', created);
      }
    } catch (err) {
      btn.disabled = false;
      // `t` is the catalog's own object, so an unknown key reads as undefined.
      toast(err?.code === 'phone_taken' ? STR.supplierPhoneTaken : (err?.code && t[`err_${err.code}`]) || t.saveFailed);
    }
  });
  openDrawer(editing ? t.editTitle : t.formTitle, form);
}

async function openEmployeeForm(employee) {
  const t = CRM.employees;
  const editing = employee && employee.id ? employee : null;
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      <div class="form__row form__row--full"><label class="lbl" for="ef-name">${escapeHTML(t.formName)} <span class="req">●</span></label>
        <input class="inp" id="ef-name" required placeholder="${escapeHTML(t.phName)}" value="${escapeHTML(editing?.name || '')}" /></div>
      <div class="form__row"><label class="lbl" for="ef-phone">${escapeHTML(t.formPhone)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="ef-phone" required placeholder="${escapeHTML(t.phPhone)}" value="${escapeHTML(editing?.phone || '')}" /></div>
      <div class="form__row"><label class="lbl" for="ef-role">${escapeHTML(t.formRole)}</label>
        <input class="inp" id="ef-role" placeholder="${escapeHTML(t.phRole)}" value="${escapeHTML(editing?.role || '')}" /></div>
      <div class="form__row"><label class="lbl" for="ef-birth">${escapeHTML(t.formBirthDate)}</label>
        <input class="inp inp--mono" id="ef-birth" type="date" min="1900-01-01" max="${todayKey()}" value="${escapeHTML((editing?.birthDate || '').slice(0, 10))}" /></div>
      <div class="form__row"><label class="lbl" for="ef-tax">${escapeHTML(t.formTaxId)}</label>
        <input class="inp inp--mono" id="ef-tax" maxlength="32" autocomplete="off" placeholder="${escapeHTML(STR.clientPhTaxId)}" value="${escapeHTML(editing?.taxId || '')}" /></div>
      <div class="form__row form__row--full"><label class="lbl" for="ef-address">${escapeHTML(t.formAddress)}</label>
        <input class="inp" id="ef-address" maxlength="300" autocomplete="off" placeholder="${escapeHTML(t.phAddress)}" value="${escapeHTML(editing?.address || '')}" /></div>
    </div>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(editing ? STR.clientSaveChanges : t.save)}</button>
      ${editing ? `<button class="btn btn--ghost" type="button" data-form-cancel>${escapeHTML(STR.cancel)}</button>` : ''}
    </div>`;
  $('[data-form-cancel]', form)?.addEventListener('click', () => closeDrawer());
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const val = sel => $(sel, form).value.trim();
    const payload = {
      name: val('#ef-name'), phone: val('#ef-phone'), role: val('#ef-role') || undefined,
      birthDate: val('#ef-birth'), taxId: val('#ef-tax'), address: val('#ef-address'),
    };
    if (!payload.name || !payload.phone) return toast(t.validate);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const saved = editing
        ? await api(`/app/api/crm/employees/${encodeURIComponent(editing.id)}`, { method: 'PATCH', body: JSON.stringify(payload) })
        : await api('/app/api/crm/employees', { method: 'POST', body: JSON.stringify(payload) });
      closeDrawer();
      await loadModule('employees');
      render();
      toast(editing ? t.updated : t.created);
      if (!editing) openPayeeDrawer('employee', saved);
    } catch (err) {
      btn.disabled = false;
      toast(err?.code === 'phone_taken' ? STR.employeePhoneTaken : err?.code === 'invalid_birth_date' ? t.invalidBirthDate : t.saveFailed);
    }
  });
  openDrawer(editing ? t.editTitle : t.formTitle, form);
}
