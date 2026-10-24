/* Serviços (/app → Services): work done for clients, by week or month, waiting to be invoiced. The list,
   the service form and detail, cancelling and deleting one, and invoicing the ones selected. */

function serviceColumns() {
  const t = CRM.services;
  return [
    { id: 'pick', label: '', pin: 'start', cell: s => `<td class="check">${s.status === 'OPEN' ? `<input type="checkbox" data-pick-service="${escapeHTML(s.id)}" data-client="${escapeHTML(s.clientId)}" />` : ''}</td>` },
    { id: 'when', label: t.thWhen, cell: s => `<td class="mono muted">${escapeHTML(fmtDay(s.performedAt || s.createdAt))}</td>` },
    { id: 'client', label: t.thClient, cell: s => `<td class="name">${escapeHTML(s.clientName || '')}</td>` },
    { id: 'service', label: t.thService, cell: s => {
      const from = s.bookingId ? t.fromBooking : s.quoteId ? t.fromQuote : '';
      return `<td>${escapeHTML(s.name)}${from ? ` <span class="muted">· ${escapeHTML(from)}</span>` : ''}</td>`;
    } },
    ...(hasModule('employees') ? [{ id: 'employee', label: t.thEmployee, cell: s => `<td class="muted">${escapeHTML(s.employeeName || '')}</td>` }] : []),
    { id: 'qty', label: t.thQty, cell: s => `<td class="mono muted">${(s.items || []).length > 1 ? escapeHTML(t.lineCount({ n: s.items.length })) : `${s.quantity}${s.unit ? ` ${escapeHTML(s.unit)}` : ''}`}</td>` },
    { id: 'total', label: t.thTotal, head: 'right', cell: s => `<td class="num">${fmtEUR(s.totalEur)}</td>` },
    { id: 'status', label: t.thStatus, cell: s => `<td>${servicePill(s.status)}</td>` },
  ];
}

// The client's catalog items need both directories; without them the view isn't offered.
const serviceClientItemsOn = () => hasModule('clients') && hasModule('catalog');

function serviceViewChips(period) {
  const t = CRM.services;
  return periodChips(period, 'data-svc-period', { '': t.viewList, week: t.byWeek, month: t.byMonth, client: t.byClient }, serviceClientItemsOn() ? ['client'] : []);
}

function wireServiceViews(root) {
  $$('[data-svc-period]', root).forEach(btn => btn.addEventListener('click', () => { state.filterServicePeriod = btn.dataset.svcPeriod; state.filterServicePeriodKey = ''; render(); }));
}

function renderServices(root) {
  const t = CRM.services;
  if (state.filterServicePeriod === 'client') {
    if (serviceClientItemsOn()) return renderServiceClientItems(root);
    state.filterServicePeriod = '';
  }
  const q = state.search.toLowerCase();
  const all = state.clientServices || [];
  const clientNames = new Map();
  for (const c of state.clients || []) clientNames.set(c.id, c.name);
  for (const s of all) if (!clientNames.has(s.clientId)) clientNames.set(s.clientId, s.clientName || s.clientId);
  if (state.filterServiceClient && !clientNames.has(state.filterServiceClient)) state.filterServiceClient = '';
  const clientId = state.filterServiceClient || '';
  const employeeNames = new Map();
  if (hasModule('employees')) for (const s of all) if (s.employeeId) employeeNames.set(s.employeeId, s.employeeName || s.employeeId);
  if (state.filterServiceEmployee && !employeeNames.has(state.filterServiceEmployee)) state.filterServiceEmployee = '';
  const employeeId = state.filterServiceEmployee || '';
  const scoped = all.filter(s => (!clientId || s.clientId === clientId) && (!employeeId || s.employeeId === employeeId));
  const columns = orderedColumns('services', serviceColumns());
  const rows = scoped
    .filter(s => !state.filterServiceStatus || s.status === state.filterServiceStatus)
    .filter(s => !q || `${s.name || ''} ${s.clientName || ''} ${serviceStatusLabel(s.status)}`.toLowerCase().includes(q))
    .map(s => `<tr class="conversation-row ${s.status === 'INVOICED' ? 'is-paid' : ''}" data-service="${escapeHTML(s.id)}">${columnCells(columns, s)}</tr>`)
    .join('');
  const open = scoped.filter(s => s.status === 'OPEN');
  const openCents = open.reduce((sum, s) => sum + Number(s.totalEur || 0), 0);
  const invoiced = scoped.filter(s => s.status === 'INVOICED');
  const clientOptions = [...clientNames.entries()].sort((a, b) => String(a[1]).localeCompare(String(b[1]), uiLocale()));
  const clientFilter = clientOptions.length
    ? `<select class="sel" data-filter-svc-client aria-label="${escapeHTML(t.thClient)}"><option value="">${escapeHTML(t.filterClientAll)}</option>${clientOptions.map(([id, name]) => `<option value="${escapeHTML(id)}" ${id === clientId ? 'selected' : ''}>${escapeHTML(name)}</option>`).join('')}</select>`
    : '';
  const employeeOptions = [...employeeNames.entries()].sort((a, b) => String(a[1]).localeCompare(String(b[1]), uiLocale()));
  const employeeFilter = employeeOptions.length
    ? `<select class="sel" data-filter-svc-employee aria-label="${escapeHTML(t.thEmployee)}"><option value="">${escapeHTML(t.filterEmployeeAll)}</option>${employeeOptions.map(([id, name]) => `<option value="${escapeHTML(id)}" ${id === employeeId ? 'selected' : ''}>${escapeHTML(name)}</option>`).join('')}</select>`
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
  const views = serviceViewChips(period);
  const tools = clientFilter + employeeFilter
    + `<button class="chip ${!state.filterServiceStatus ? 'is-on' : ''}" data-filter-svc="">${escapeHTML(t.filterAll)}</button>`
    + ['OPEN', 'INVOICED', 'CANCELLED'].map(s => `<button class="chip ${state.filterServiceStatus === s ? 'is-on' : ''}" data-filter-svc="${s}">${escapeHTML(serviceStatusLabel(s))}</button>`).join('')
    + (period ? '' : columnOrderButton('services'))
    + (!period && hasModule('invoices') ? `<button class="btn btn--sm btn--primary" type="button" data-invoice-services>${escapeHTML(t.invoiceSelected)}</button>` : '');
  const filtered = Boolean(clientId || employeeId || state.filterServiceStatus || q);
  const summaryHead = `<tr><th>${escapeHTML(t.thPeriod)}</th><th class="right">${escapeHTML(t.thCount)}</th><th class="right">${escapeHTML(t.open)}</th><th class="right">${escapeHTML(t.invoiced)}</th><th class="right">${escapeHTML(t.thTotal)}</th></tr>`;
  const listHead = columnsHead(columns, 'services');
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
  wireServiceViews(root);
  $$('[data-svc-period-nav]', root).forEach(btn => btn.addEventListener('click', () => {
    const dir = btn.dataset.svcPeriodNav;
    state.filterServicePeriodKey = dir === 'current' ? '' : shiftPeriodKey(nowKey, period, dir === 'prev' ? -1 : 1);
    render();
  }));
  $$('[data-filter-svc]', root).forEach(btn => btn.addEventListener('click', () => { state.filterServiceStatus = btn.dataset.filterSvc; render(); }));
  $('[data-filter-svc-client]', root)?.addEventListener('change', e => { state.filterServiceClient = e.target.value; render(); });
  $('[data-filter-svc-employee]', root)?.addEventListener('change', e => { state.filterServiceEmployee = e.target.value; render(); });
  $$('[data-pick-service]', root).forEach(box => box.addEventListener('click', e => e.stopPropagation()));
  $$('[data-service]', root).forEach(r => r.addEventListener('click', e => {
    if (e.target.closest('[data-pick-service]')) return;
    openServiceDetail(state.clientServices.find(s => s.id === r.dataset.service) || r.dataset.service);
  }));
  $('[data-invoice-services]', root)?.addEventListener('click', invoiceSelectedServices);
  wireColumnOrder(root, 'services', serviceColumns());
}

// By client: the catalog items one client usually buys. The service, quote and invoice forms list them
// first once that client is picked, and the client record has them in its own tab. Items since removed
// from the catalog are left out and dropped on the next save.
function clientCatalogItems(client) {
  const byId = new Map((state.catalog || []).map(c => [c.id, c]));
  return (client?.catalogItemIds || []).map(id => byId.get(id)).filter(Boolean);
}

// For a form's line picker: the usual items of whichever client is picked in `#f-client` (or `fixedId`).
// `getForm` returns the form, which the caller builds after the picker.
function preferredClientItems(getForm, clients, fixedId = '') {
  return () => {
    const id = fixedId || $('#f-client', getForm())?.value;
    return (clients || state.clients || []).find(c => c.id === id)?.catalogItemIds || [];
  };
}

function clientItemsHead() {
  const ti = CRM.items;
  return [[ti.thCode], [ti.thTitle], [ti.thType], [ti.thUnit], [ti.thPrice, 'right'], ['']];
}

function clientItemsRowsHtml(items, q = '') {
  const t = CRM.services;
  return items
    .filter(i => !q || foldText(`${i.code || ''} ${i.title || ''} ${i.category || ''}`).includes(q))
    .map(i => `<tr>
      <td class="id">${escapeHTML(i.code || '')}</td>
      <td class="name">${escapeHTML(i.title || '')}</td>
      <td>${catalogTypePill(i.type)}</td>
      <td class="mono muted">${escapeHTML(i.unit || '')}</td>
      <td class="num">${fmtEUR(i.defaultUnitPriceEur)}</td>
      <td class="right"><button class="iconbtn iconbtn--danger" type="button" title="${escapeHTML(t.clientItemsRemove)}" aria-label="${escapeHTML(t.clientItemsRemoveAria({ name: i.title || '' }))}" data-remove-client-item="${escapeHTML(i.id)}"><svg width="13" height="13" viewBox="0 0 16 16"><path d="M4 4 L12 12 M12 4 L4 12" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg></button></td>
    </tr>`).join('');
}

// The catalog items the client doesn't have yet, services first; empty when there are none.
function clientItemsAddHtml(items) {
  const t = CRM.services;
  const taken = new Set(items.map(i => i.id));
  const free = serviceCatalog().filter(c => (state.catalog || []).some(i => i.id === c.id) && !taken.has(c.id));
  if (!free.length) return '';
  const group = (label, list) => (list.length ? `<optgroup label="${escapeHTML(label)}">${list.map(c => `<option value="${escapeHTML(c.id)}">${escapeHTML(catalogItemLabel(c))}</option>`).join('')}</optgroup>` : '');
  return `<select class="sel" data-svc-items-add aria-label="${escapeHTML(t.clientItemsAddPh)}"><option value="">${escapeHTML(t.clientItemsAddPh)}</option>${group(STR.catalogService, free.filter(c => c.type === 'service'))}${group(STR.catalogMaterial, free.filter(c => c.type !== 'service'))}</select>`;
}

// `onSaved(client)` runs with the saved client, after the add or remove went through.
function wireClientItems(root, client, items, onSaved) {
  $('[data-svc-items-add]', root)?.addEventListener('change', e => {
    if (e.target.value) saveClientCatalogItems(client, [...items.map(i => i.id), e.target.value], onSaved);
  });
  $$('[data-remove-client-item]', root).forEach(btn => btn.addEventListener('click', () => {
    saveClientCatalogItems(client, items.map(i => i.id).filter(id => id !== btn.dataset.removeClientItem), onSaved);
  }));
}

async function saveClientCatalogItems(client, itemIds, onSaved) {
  const t = CRM.services;
  try {
    const saved = await api(`/app/api/crm/clients/${encodeURIComponent(client.id)}/catalog-items`, { method: 'PUT', body: JSON.stringify({ itemIds }) });
    state.clients = (state.clients || []).map(c => (c.id === saved.id ? saved : c));
    toast(t.clientItemsSaved);
    onSaved(saved);
  } catch { toast(t.clientItemsFailed); }
}

function renderServiceClientItems(root) {
  const t = CRM.services;
  const clients = [...(state.clients || [])].sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), uiLocale(), { sensitivity: 'base' }));
  if (state.serviceItemsClient && !clients.some(c => c.id === state.serviceItemsClient)) state.serviceItemsClient = '';
  const clientId = state.serviceItemsClient || state.filterServiceClient || '';
  const client = clients.find(c => c.id === clientId) || null;
  const items = clientCatalogItems(client);
  const clientPick = `<select class="sel" data-svc-items-client aria-label="${escapeHTML(t.thClient)}"><option value="">${escapeHTML(STR.lineChooseClient)}</option>${clients.map(c => `<option value="${escapeHTML(c.id)}" ${c.id === clientId ? 'selected' : ''}>${escapeHTML(c.name)}</option>`).join('')}</select>`;
  const withItems = clients.filter(c => clientCatalogItems(c).length).length;
  const head = `<tr>${clientItemsHead().map(([label, cls]) => `<th${cls ? ` class="${cls}"` : ''}>${escapeHTML(label)}</th>`).join('')}</tr>`;
  root.innerHTML = hero(labels.services, CRM.tabs.servicos.desc, statCards([
    { label: t.clientItemsStat, value: items.length },
    { label: t.clientItemsClientsStat, value: withItems },
  ])) + crmPanel({
    title: client ? client.name : t.clientItemsTitle,
    tag: client ? items.length : null,
    views: serviceViewChips('client'),
    tools: clientPick + (client ? clientItemsAddHtml(items) : ''),
    head,
    rows: client ? clientItemsRowsHtml(items, foldText(state.search.trim())) : '',
    empty: !client ? t.clientItemsNoClient : (items.length ? t.emptyFiltered : t.clientItemsEmpty),
    emptyDesc: !client ? t.clientItemsNoClientDesc : (items.length ? t.emptyFilteredDesc : t.clientItemsEmptyDesc),
  });
  wireServiceViews(root);
  $('[data-svc-items-client]', root)?.addEventListener('change', e => { state.serviceItemsClient = e.target.value; render(); });
  if (client) wireClientItems(root, client, items, saved => { state.serviceItemsClient = saved.id; render(); });
}

// Bookable services are catalog services, so both lists merge into one (the booking list only
// arrives when the Catalog module is off but Bookings is on).
function serviceCatalog() {
  const byId = new Map();
  for (const c of state.catalog || []) byId.set(c.id, c.type === 'servico' ? { ...c, type: 'service' } : c);
  for (const s of state.bookingServices || []) {
    if (!byId.has(s.id)) byId.set(s.id, { id: s.id, type: 'service', title: s.name, unit: s.unit || '', defaultUnitPriceEur: s.priceEur ?? 0 });
  }
  return [...byId.values()].sort((a, b) => String(a.title).localeCompare(String(b.title), uiLocale()));
}

// An unnamed service is called after its lines ("Limpeza + Vidros"); catalog lines read "title - details".
function serviceNameFrom(items) {
  const name = items.map(it => it.description.split(' - ')[0].trim()).filter(Boolean).join(' + ');
  return name.length > 160 ? `${name.slice(0, 159)}…` : name;
}

// Who does the work: an active employee, or the one already on the row even once they are inactive.
function serviceEmployeeField(selectedId, current) {
  const t = CRM.services;
  const list = [...(state.employees || [])];
  if (selectedId && !list.some(e => e.id === selectedId)) list.unshift({ id: selectedId, name: current || selectedId });
  list.sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), uiLocale(), { sensitivity: 'base' }));
  return `<div class="form__row"><label class="lbl" for="svc-employee">${escapeHTML(t.employee)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
    <select class="sel" id="svc-employee"><option value="">${escapeHTML(t.employeeNone)}</option>
    ${list.map(e => `<option value="${escapeHTML(e.id)}"${e.id === selectedId ? ' selected' : ''}>${escapeHTML(e.name)}</option>`).join('')}</select>
    <p class="hint">${escapeHTML(t.employeeHint)}</p></div>`;
}

// One service can sum several lines, picked from the catalog or typed; invoicing it bills each line.
// `presetEmployeeId` preselects who does it, as when it is added from an employee's record.
async function openServiceForm(service, presetClientId, presetEmployeeId) {
  const t = CRM.services;
  const editing = service && service.id ? service : null;
  if (editing?.status === 'INVOICED') return openServiceDetail(editing);
  const missingPreset = presetClientId && !state.clients.some(c => c.id === presetClientId);
  if ((!state.clients.length || missingPreset) && hasModule('clients')) state.clients = await api('/app/api/crm/clients').catch(() => state.clients);
  if (hasModule('catalog') && !state.catalog.length) state.catalog = await api('/app/api/crm/standard-items').catch(() => []);
  if (hasModule('bookings') && !state.bookingServices.length) state.bookingServices = await api('/app/api/bookings/services').catch(() => []);
  const team = hasModule('employees');
  if (team && !state.employees.length) state.employees = await api('/app/api/crm/employees').catch(() => []);
  // The picked client's usual items lead the catalog picker.
  const li = lineItemsField(serviceCatalog(), { preferred: preferredClientItems(() => form, state.clients, editing?.clientId) });
  const clientCell = editing
    ? `<div class="form__row"><span class="lbl">${escapeHTML(STR.lineClient)}</span><p class="hint">${escapeHTML(editing.clientName || '')}</p></div>`
    : clientSelect(state.clients, presetClientId || '');
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${clientCell}
      <div class="form__row"><label class="lbl" for="svc-when">${escapeHTML(t.when)}</label>
        <input class="inp" id="svc-when" type="date" value="${escapeHTML((editing ? editing.performedAt || '' : todayKey()).slice(0, 10))}" /></div>
      ${team ? serviceEmployeeField(editing ? editing.employeeId || '' : presetEmployeeId || '', editing?.employeeName) : ''}
      <div class="form__row form__row--full"><label class="lbl" for="svc-name">${escapeHTML(t.name)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp" id="svc-name" maxlength="160" autocomplete="off" placeholder="${escapeHTML(t.namePh)}" value="${escapeHTML(editing?.name || '')}" />
        <p class="hint">${escapeHTML(t.nameHint)}</p></div>
    </div>
    ${li.html}
    <div class="form__row form__row--full"><label class="lbl" for="svc-notes">${escapeHTML(t.notes)}</label>
      <textarea class="txt" id="svc-notes">${escapeHTML(editing?.notes || '')}</textarea></div>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(editing ? STR.clientSaveChanges : t.save)}</button>
      ${editing ? `<button class="btn btn--ghost" type="button" data-form-cancel>${escapeHTML(STR.cancel)}</button>` : ''}
    </div>`;
  $('[data-form-cancel]', form)?.addEventListener('click', () => closeDrawer());
  const nameInput = $('#svc-name', form);
  li.wire(form, {
    initial: editing?.items || [],
    onChange: () => { nameInput.placeholder = serviceNameFrom(li.collect(form)) || t.namePh; },
  });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const chosenClient = editing?.clientId || $('#f-client', form)?.value;
    if (!chosenClient) return toast(t.validate);
    const items = li.collect(form);
    if (!items.length) return toast(t.addLine);
    if (items.some(it => !(it.quantity > 0))) return toast(t.lineQty);
    const payload = {
      clientId: chosenClient,
      name: nameInput.value.trim() || serviceNameFrom(items),
      notes: $('#svc-notes', form).value.trim() || null,
      performedAt: $('#svc-when', form).value || null,
      items,
      catalogItemId: $('.line[data-catalog]', form)?.dataset.catalog || null,
    };
    if (team) payload.employeeId = $('#svc-employee', form).value;
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      if (editing) await api(`/app/api/crm/services/${encodeURIComponent(editing.id)}`, { method: 'PATCH', body: JSON.stringify(payload) });
      else await api('/app/api/crm/services', { method: 'POST', body: JSON.stringify(payload) });
      closeDrawer();
      await loadModule('services');
      render();
      toast(editing ? t.updated : t.created);
    } catch (err) { btn.disabled = false; toast(err?.code === 'employee_not_found' ? t.employeeNotFound : t.saveFailed); }
  });
  openDrawer(editing ? t.editTitle : t.formTitle, form, true);
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
  const [invoice, booking, doneBy, quote] = await Promise.all([
    service.invoiceId && hasModule('invoices') ? api(`/app/api/crm/invoices/${encodeURIComponent(service.invoiceId)}`).catch(() => null) : null,
    service.bookingId && hasModule('bookings') ? api(`/app/api/bookings/${encodeURIComponent(service.bookingId)}`).catch(() => null) : null,
    service.employeeId && hasModule('employees') ? api(`/app/api/crm/employees/${encodeURIComponent(service.employeeId)}`).catch(() => null) : null,
    service.quoteId && hasModule('quotes') ? api(`/app/api/crm/quotes/${encodeURIComponent(service.quoteId)}`).catch(() => null) : null,
  ]);
  const quoteLink = !!quote && !linksBackTo(`quote:${quote.id}`);
  const here = serviceTrailEntry(service);
  const isOpen = service.status === 'OPEN';
  const tone = { OPEN: 'warn', INVOICED: 'ok' }[service.status] || '';
  const clientLink = hasModule('clients') && !!service.clientId && !linksBackTo(`client:${service.clientId}`);
  const lines = service.items || [];
  const showLines = lines.length > 1 || (lines.length === 1 && lines[0].description !== service.name);
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `
    ${detailHead(service.clientName, servicePill(service.status), service.totalEur, tone, clientLink)}
    ${detailMeta([
      { label: t.when, value: service.performedAt ? fmtDay(service.performedAt) : '—' },
      lines.length > 1 ? null : { label: t.qty, value: `${service.quantity}${service.unit ? ` ${service.unit}` : ''} × ${fmtEUR(service.unitPriceEur)}` },
      booking ? { label: STR.clientKindBooking, value: bookingWhen(booking) } : null,
      invoice ? { label: STR.detailInvoice, value: invoice.number } : null,
      doneBy ? { label: t.employee, value: doneBy.name } : null,
      { label: STR.detailCreated, value: fmtDay(service.createdAt) },
    ])}
    ${quote && !quoteLink ? `<p class="hint">${escapeHTML(STR.invoiceFromQuote({ number: quote.number }))}</p>` : ''}
    ${showLines ? itemsTable(lines) : ''}
    ${service.notes ? `<p class="hint">${escapeHTML(service.notes)}</p>` : ''}
    ${service.status === 'INVOICED' ? `<p class="hint">${escapeHTML(t.invoicedLocked)}</p>` : ''}
    <div class="detail__foot">
      ${isOpen && hasModule('invoices') ? `<button class="btn btn--sm btn--accent" type="button" data-svc-invoice>${escapeHTML(t.invoiceNow)}</button>` : ''}
      ${isOpen ? `<button class="btn btn--sm" type="button" data-svc-edit>${escapeHTML(STR.clientEditAction)}</button>` : ''}
      ${service.status === 'CANCELLED' && !service.bookingId ? `<button class="btn btn--sm" type="button" data-svc-reopen>${escapeHTML(t.reopen)}</button>` : ''}
      ${invoice && !linksBackTo(`invoice:${invoice.id}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-svc-open-invoice>${escapeHTML(STR.detailOpenDoc({ number: invoice.number }))}</button>` : ''}
      ${booking && !linksBackTo(`booking:${booking.id}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-svc-open-booking>${escapeHTML(t.openBooking)}</button>` : ''}
      ${quoteLink ? `<button class="btn btn--sm btn--ghost" type="button" data-svc-open-quote>${escapeHTML(STR.detailOpenDoc({ number: quote.number }))}</button>` : ''}
      ${doneBy && !linksBackTo(`employee:${doneBy.id}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-svc-open-employee>${escapeHTML(SUB.openEmployee({ name: doneBy.name }))}</button>` : ''}
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
  $('[data-svc-open-quote]', body)?.addEventListener('click', () => openFrom(here, () => openQuoteDetail(quote.id)));
  $('[data-svc-open-employee]', body)?.addEventListener('click', () => openFrom(here, () => openPayeeDrawer('employee', doneBy)));
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
    } catch (err) { btn.disabled = false; toast(err?.code === 'quote_billed' ? t.reopenQuoteBilled : t.reopenFailed); }
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
