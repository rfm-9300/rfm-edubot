/* Serviços (/app → Services): work done for clients, by week or month, waiting to be invoiced. The list,
   the service form and detail, cancelling and deleting one, and invoicing the ones selected. */

function serviceColumns() {
  const t = CRM.services;
  return [
    { id: 'pick', label: '', pin: 'start', cell: s => `<td class="check">${s.status === 'OPEN' ? `<input type="checkbox" data-pick-service="${escapeHTML(s.id)}" data-client="${escapeHTML(s.clientId)}" />` : ''}</td>` },
    { id: 'when', label: t.thWhen, cell: s => `<td class="mono muted">${escapeHTML(fmtDay(s.performedAt || s.createdAt))}</td>` },
    { id: 'client', label: t.thClient, cell: s => `<td class="name">${escapeHTML(s.clientName || '')}</td>` },
    { id: 'service', label: t.thService, cell: s => `<td>${escapeHTML(s.name)}${s.bookingId ? ` <span class="muted">· ${escapeHTML(t.fromBooking)}</span>` : ''}</td>` },
    { id: 'qty', label: t.thQty, cell: s => `<td class="mono muted">${(s.items || []).length > 1 ? escapeHTML(t.lineCount({ n: s.items.length })) : `${s.quantity}${s.unit ? ` ${escapeHTML(s.unit)}` : ''}`}</td>` },
    { id: 'total', label: t.thTotal, head: 'right', cell: s => `<td class="num">${fmtEUR(s.totalEur)}</td>` },
    { id: 'status', label: t.thStatus, cell: s => `<td>${servicePill(s.status)}</td>` },
  ];
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
    + (period ? '' : columnOrderButton('services'))
    + (!period && hasModule('invoices') ? `<button class="btn btn--sm btn--primary" type="button" data-invoice-services>${escapeHTML(t.invoiceSelected)}</button>` : '');
  const filtered = Boolean(clientId || state.filterServiceStatus || q);
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
  wireColumnOrder(root, 'services', serviceColumns());
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

// One service can sum several lines, picked from the catalog or typed; invoicing it bills each line.
async function openServiceForm(service, presetClientId) {
  const t = CRM.services;
  const editing = service && service.id ? service : null;
  if (editing?.status === 'INVOICED') return openServiceDetail(editing);
  const missingPreset = presetClientId && !state.clients.some(c => c.id === presetClientId);
  if ((!state.clients.length || missingPreset) && hasModule('clients')) state.clients = await api('/app/api/crm/clients').catch(() => state.clients);
  if (hasModule('catalog') && !state.catalog.length) state.catalog = await api('/app/api/crm/standard-items').catch(() => []);
  if (hasModule('bookings') && !state.bookingServices.length) state.bookingServices = await api('/app/api/bookings/services').catch(() => []);
  const li = lineItemsField(serviceCatalog());
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
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      if (editing) await api(`/app/api/crm/services/${encodeURIComponent(editing.id)}`, { method: 'PATCH', body: JSON.stringify(payload) });
      else await api('/app/api/crm/services', { method: 'POST', body: JSON.stringify(payload) });
      closeDrawer();
      await loadModule('services');
      render();
      toast(editing ? t.updated : t.created);
    } catch { btn.disabled = false; toast(t.saveFailed); }
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
  const [invoice, booking, doneBy] = await Promise.all([
    service.invoiceId && hasModule('invoices') ? api(`/app/api/crm/invoices/${encodeURIComponent(service.invoiceId)}`).catch(() => null) : null,
    service.bookingId && hasModule('bookings') ? api(`/app/api/bookings/${encodeURIComponent(service.bookingId)}`).catch(() => null) : null,
    service.employeeId && hasModule('employees') ? api(`/app/api/crm/employees/${encodeURIComponent(service.employeeId)}`).catch(() => null) : null,
  ]);
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
      doneBy ? { label: SUB.doneBy, value: doneBy.name } : null,
      { label: STR.detailCreated, value: fmtDay(service.createdAt) },
    ])}
    ${showLines ? itemsTable(lines) : ''}
    ${service.notes ? `<p class="hint">${escapeHTML(service.notes)}</p>` : ''}
    ${service.status === 'INVOICED' ? `<p class="hint">${escapeHTML(t.invoicedLocked)}</p>` : ''}
    <div class="detail__foot">
      ${isOpen && hasModule('invoices') ? `<button class="btn btn--sm btn--accent" type="button" data-svc-invoice>${escapeHTML(t.invoiceNow)}</button>` : ''}
      ${isOpen ? `<button class="btn btn--sm" type="button" data-svc-edit>${escapeHTML(STR.clientEditAction)}</button>` : ''}
      ${service.status === 'CANCELLED' && !service.bookingId ? `<button class="btn btn--sm" type="button" data-svc-reopen>${escapeHTML(t.reopen)}</button>` : ''}
      ${invoice && !linksBackTo(`invoice:${invoice.id}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-svc-open-invoice>${escapeHTML(STR.detailOpenDoc({ number: invoice.number }))}</button>` : ''}
      ${booking && !linksBackTo(`booking:${booking.id}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-svc-open-booking>${escapeHTML(t.openBooking)}</button>` : ''}
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
