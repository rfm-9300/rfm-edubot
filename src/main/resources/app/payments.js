/* Payments (/app → Payments): what the company pays its suppliers and employees. The list, the payment
   form, and a payment's detail: marking it paid, the client an expense was for, cancelling and deleting. */

// `opts.items`, `opts.dueDate` and `opts.notes` start it from a bill someone received; `opts.onSaved(payment)`
// runs after it is created instead of showing the payments list.
async function openPaymentForm(presetSupplierId, presetEmployeeId, presetClient = null, opts = {}) {
  const t = CRM.payments;
  const employeesOn = hasModule('employees');
  const clientsOn = hasModule('clients');
  let suppliers, employees, catalog, clients;
  try {
    suppliers = await api('/app/api/crm/suppliers');
    employees = employeesOn ? await api('/app/api/crm/employees').catch(() => state.employees || []) : [];
    catalog = hasModule('catalog') ? await api('/app/api/crm/standard-items').catch(() => state.catalog || []) : (state.catalog || []);
    clients = clientsOn ? withClient(await api('/app/api/crm/clients').catch(() => state.clients || []), presetClient) : [];
  } catch { return toast(t.saveFailed); }
  state.suppliers = suppliers;
  if (employeesOn) state.employees = employees;
  if (!suppliers.length && !employees.length) {
    toast(employeesOn ? t.needPayee : t.needSupplier);
    return openSupplierForm();
  }
  let payee = presetEmployeeId ? 'employee' : 'supplier';
  if (!presetSupplierId && !presetEmployeeId && !suppliers.length && employees.length) payee = 'employee';
  const selectedSupplier = presetSupplierId || (!presetEmployeeId ? state.filterPaymentSupplier || '' : '');
  const li = lineItemsField(catalog);
  const due = opts.dueDate || new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10);
  const kindSwitch = employeesOn
    ? `<div class="form__row form__row--full"><span class="lbl">${escapeHTML(t.payee)}</span><div class="actions">
        <button class="chip ${payee === 'supplier' ? 'is-on' : ''}" type="button" data-payee="supplier">${escapeHTML(t.paySupplier)}</button>
        <button class="chip ${payee === 'employee' ? 'is-on' : ''}" type="button" data-payee="employee">${escapeHTML(t.payEmployee)}</button>
      </div></div>`
    : '';
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${kindSwitch}
      ${supplierSelect(suppliers, selectedSupplier)}
      ${employeesOn ? employeeSelect(employees, presetEmployeeId || '') : ''}
      <div class="form__row"><label class="lbl" for="p-due">${escapeHTML(t.thDueDate)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="p-due" type="date" required value="${due}" /></div>
      ${clientsOn ? paymentClientSelect(clients, presetClient?.id || '') : ''}
      <div class="form__row form__row--full" id="payee-services" hidden></div>
    </div>
    <div id="payment-lines">${li.html}</div>
    ${employeesOn ? generalPaymentFields() : ''}
    <div class="form__row form__row--full"><label class="lbl" for="p-notes">${escapeHTML(t.notes)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
      <textarea class="txt" id="p-notes" placeholder="${escapeHTML(t.notesPh)}">${escapeHTML(opts.notes || '')}</textarea></div>
    <button class="btn btn--primary" type="submit">${escapeHTML(t.save)}</button>`;
  // The picked supplier's usual services, as ticks. A ticked service adds a line tagged with it, so
  // unticking removes that line and removing the line unticks it.
  let lines = null;
  const supplierServices = () => (payee === 'supplier' ? suppliers.find(s => s.id === ($('#f-supplier', form)?.value || ''))?.services || [] : []);
  const paymentLines = () => $$('#payment-lines .line', form);
  const syncServices = () => $$('[data-supplier-service]', form).forEach(box => {
    box.checked = paymentLines().some(row => row.dataset.fromService === box.dataset.supplierService);
  });
  const blankLine = row => ['description', 'quantity', 'unitPriceEur'].every(k => !$(`[data-k="${k}"]`, row).value.trim());
  const toggleService = (i, on) => {
    const s = supplierServices()[i];
    if (!lines || !s) return;
    if (on) {
      const preset = { description: s.description, quantity: 1, unit: s.unit || '', unitPriceEur: s.unitPriceEur ?? '' };
      let row = paymentLines().find(blankLine);
      if (row) Object.entries(preset).forEach(([k, v]) => { $(`[data-k="${k}"]`, row).value = v; });
      else row = lines.addRow(preset);
      row.dataset.fromService = String(i);
    } else {
      paymentLines().filter(row => row.dataset.fromService === String(i)).forEach(row => row.remove());
      if (!paymentLines().length) lines.addRow();
    }
    lines.recalc();
  };
  const renderServices = () => {
    const row = $('#payee-services', form);
    const list = supplierServices();
    row.hidden = !list.length;
    row.innerHTML = !list.length ? '' : `
      <span class="lbl" id="ps-label">${escapeHTML(t.supplierServices)}</span>
      <div class="form__checks form__checks--wide" role="group" aria-labelledby="ps-label">
        ${list.map((s, i) => `<label class="form__check"><input type="checkbox" data-supplier-service="${i}" />
          <span>${escapeHTML(s.description)}${s.unitPriceEur != null ? ` <span class="muted mono">${escapeHTML(fmtEUR(s.unitPriceEur))}${s.unit ? `/${escapeHTML(s.unit)}` : ''}</span>` : ''}</span></label>`).join('')}
      </div>
      <p class="hint">${escapeHTML(t.supplierServicesHint)}</p>`;
    $$('[data-supplier-service]', row).forEach(box => box.addEventListener('change', () => toggleService(Number(box.dataset.supplierService), box.checked)));
    syncServices();
  };
  const showPayee = kind => {
    payee = kind;
    const supplierRow = $('#payee-supplier', form);
    const employeeRow = $('#payee-employee', form);
    const lineBox = $('#payment-lines', form);
    const general = $('#payment-general', form);
    if (supplierRow) supplierRow.hidden = kind !== 'supplier';
    if (employeeRow) employeeRow.hidden = kind !== 'employee';
    if (lineBox) lineBox.hidden = kind === 'employee';
    if (general) general.hidden = kind !== 'employee';
    $$('[data-payee]', form).forEach(btn => btn.classList.toggle('is-on', btn.dataset.payee === kind));
    renderServices();
  };
  showPayee(payee);
  $$('[data-payee]', form).forEach(btn => btn.addEventListener('click', () => {
    if (btn.dataset.payee === 'supplier' && !suppliers.length) return toast(t.needSupplier);
    if (btn.dataset.payee === 'employee' && !employees.length) {
      toast(t.needEmployee);
      return openEmployeeForm();
    }
    showPayee(btn.dataset.payee);
  }));
  lines = li.wire(form, { onChange: syncServices, initial: opts.items || [] });
  // Lines the previous supplier's services added go with it.
  $('#f-supplier', form)?.addEventListener('change', () => {
    paymentLines().filter(row => row.dataset.fromService).forEach(row => row.remove());
    if (!paymentLines().length) lines.addRow();
    lines.recalc();
    renderServices();
  });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const dueDate = $('#p-due', form).value;
    const supplierId = $('#f-supplier', form)?.value || '';
    const employeeId = $('#f-employee', form)?.value || '';
    if (payee === 'employee') {
      if (!employeeId) return toast(t.chooseEmployee);
    } else if (!supplierId) return toast(t.chooseSupplier);
    if (!dueDate) return toast(t.enterDueDate);
    let items;
    if (payee === 'employee') {
      const amount = Number($('#p-amount', form).value || 0);
      if (!(amount > 0)) return toast(t.enterAmount);
      const description = $('#p-desc', form).value.trim() || t.generalEntryValue;
      items = [{ description, quantity: 1, unit: '', unitPriceEur: amount }];
    } else {
      items = li.collect(form);
      if (!items.length) return toast(t.addLine);
    }
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    const clientId = $('#p-client', form)?.value || null;
    const body = payee === 'employee'
      ? { employeeId, items, dueDate, notes: $('#p-notes', form).value.trim() || null, clientId }
      : { supplierId, items, dueDate, notes: $('#p-notes', form).value.trim() || null, clientId };
    try {
      const saved = await api('/app/api/crm/payments', { method: 'POST', body: JSON.stringify(body) });
      closeDrawer();
      if (opts.onSaved) { toast(t.created); return opts.onSaved(saved); }
      await loadModule('payments');
      render();
      toast(t.created);
    } catch { btn.disabled = false; toast(t.saveFailed); }
  });
  openDrawer(t.formTitle, form, true);
}

function renderPayments(root) {
  const t = CRM.payments;
  const q = state.search.toLowerCase();
  const all = state.payments || [];
  const names = new Map();
  for (const s of state.suppliers || []) names.set(s.id, s.name);
  for (const e of state.employees || []) names.set(e.id, e.name);
  for (const p of all) {
    const id = payeeId(p);
    if (id && !names.has(id)) names.set(id, payeeName(p) || id);
  }
  if (state.filterPaymentSupplier && !names.has(state.filterPaymentSupplier)) state.filterPaymentSupplier = '';
  const supplierId = state.filterPaymentSupplier || '';
  const scoped = supplierId ? all.filter(p => payeeId(p) === supplierId) : all;
  const rows = scoped
    .filter(p => !state.filterPaymentStatus || p.status === state.filterPaymentStatus)
    .filter(p => !q || `${p.number} ${payeeName(p)} ${p.clientName || ''} ${paymentStatusLabel(p.status)}`.toLowerCase().includes(q))
    .map(p => {
      const canMarkPaid = p.status === 'PENDING' || p.status === 'OVERDUE';
      const rowClass = p.status === 'PAID' ? 'is-paid' : p.status === 'OVERDUE' ? 'is-overdue' : p.status === 'CANCELLED' ? 'is-draft' : '';
      return `<tr class="conversation-row ${rowClass}" data-payment="${escapeHTML(p.id)}">
        <td class="id">${escapeHTML(p.number)}</td>
        <td class="name">${escapeHTML(payeeName(p))}${p.clientName ? ` <span class="muted">· ${escapeHTML(p.clientName)}</span>` : ''}</td>
        <td>${paymentPill(p.status)}</td>
        <td class="mono muted">${fmtDay(p.dueDate)}</td>
        <td class="num">${fmtEUR(p.totalEur)}</td>
        <td class="right"><div class="actions">
          ${canMarkPaid ? `<button class="btn btn--sm btn--accent" type="button" data-mark-payment="${escapeHTML(p.id)}">${escapeHTML(t.markPaid)}</button>` : ''}
        </div></td>
      </tr>`;
    }).join('');
  const paid = scoped.filter(p => p.status === 'PAID').reduce((sum, p) => sum + Number(p.totalEur || 0), 0);
  const pending = scoped.filter(p => p.status === 'PENDING').reduce((sum, p) => sum + Number(p.totalEur || 0), 0);
  const overdue = scoped.filter(p => p.status === 'OVERDUE').reduce((sum, p) => sum + Number(p.totalEur || 0), 0);
  const supplierOptions = [...names.entries()].sort((a, b) => String(a[1]).localeCompare(String(b[1]), uiLocale()));
  const supplierFilter = supplierOptions.length
    ? `<select class="sel" data-filter-pay-supplier aria-label="${escapeHTML(t.thPayee)}"><option value="">${escapeHTML(t.filterPayeeAll)}</option>${supplierOptions.map(([id, name]) => `<option value="${escapeHTML(id)}" ${id === supplierId ? 'selected' : ''}>${escapeHTML(name)}</option>`).join('')}</select>`
    : '';
  const tools = supplierFilter
    + `<button class="chip ${!state.filterPaymentStatus ? 'is-on' : ''}" data-filter-pay="">${escapeHTML(t.filterAll)}</button>`
    + INVOICE_STATUSES.map(s => `<button class="chip ${state.filterPaymentStatus === s ? 'is-on' : ''}" data-filter-pay="${s}">${escapeHTML(paymentStatusLabel(s))}</button>`).join('');
  const filtered = Boolean(supplierId || state.filterPaymentStatus || q);
  root.innerHTML = hero(labels.payments, CRM.tabs.pagamentos.desc, statCards([
    { label: t.paid, value: fmtEUR(paid) },
    { label: t.pending, value: fmtEUR(pending) },
    { label: t.overdue, value: fmtEUR(overdue) },
  ])) + crmPanel({
    title: t.ledger,
    tag: scoped.filter(p => !state.filterPaymentStatus || p.status === state.filterPaymentStatus).length,
    tools,
    head: `<tr><th>${escapeHTML(t.thNumber)}</th><th>${escapeHTML(t.thPayee)}</th><th>${escapeHTML(t.thStatus)}</th><th>${escapeHTML(t.thDueDate)}</th><th class="right">${escapeHTML(t.thTotal)}</th><th></th></tr>`,
    rows,
    empty: filtered ? t.emptyFiltered : t.emptyTitle,
    emptyDesc: filtered ? t.emptyFilteredDesc : t.emptyDesc,
  });
  $$('[data-filter-pay]', root).forEach(btn => btn.addEventListener('click', () => { state.filterPaymentStatus = btn.dataset.filterPay; render(); }));
  $('[data-filter-pay-supplier]', root)?.addEventListener('change', e => { state.filterPaymentSupplier = e.target.value; render(); });
  $$('[data-mark-payment]', root).forEach(btn => btn.addEventListener('click', e => {
    e.stopPropagation();
    markPaymentPaid(btn.dataset.markPayment);
  }));
  $$('[data-payment]', root).forEach(r => r.addEventListener('click', e => {
    if (e.target.closest('[data-mark-payment]')) return;
    openPaymentDetail(r.dataset.payment);
  }));
}

async function markPaymentPaid(id, number = state.payments.find(p => p.id === id)?.number || '') {
  const t = CRM.payments;
  const ok = await confirmDialog({
    title: t.markPaidConfirmTitle,
    body: t.markPaidConfirmBody({ number }),
    okLabel: t.markPaid,
    danger: false,
  });
  if (!ok) return;
  try {
    await api(`/app/api/crm/payments/${encodeURIComponent(id)}/paid`, { method: 'PATCH' });
    closeDrawer();
    await loadModule('payments');
    render();
    toast(t.markedPaid({ number }));
  } catch { toast(t.markPaidFailed); }
}

// `CRM.payments` is the catalog's own object, so an unknown key reads as undefined.
const paymentErrorText = err => (err?.code && CRM.payments[`err_${err.code}`]) || CRM.payments.actionFailed;

async function reloadAfterPaymentChange() {
  await loadModule('payments').catch(() => {});
  if (state.active === 'overview') await loadModule('overview').catch(() => {});
  render();
}

async function cancelPayment(pay) {
  const t = CRM.payments;
  if (!await confirmDialog({ title: t.cancelTitle({ number: pay.number }), body: t.cancelBody, okLabel: t.cancel })) return;
  try {
    await api(`/app/api/crm/payments/${encodeURIComponent(pay.id)}/cancel`, { method: 'POST' });
    closeDrawer();
    toast(t.cancelled({ number: pay.number }));
    await reloadAfterPaymentChange();
  } catch (err) { if (err?.message !== 'unauthorized') toast(paymentErrorText(err)); }
}

async function deletePayment(pay) {
  const t = CRM.payments;
  const body = pay.status === 'PAID' ? t.deleteBodyPaid({ amount: fmtEUR(pay.totalEur) }) : t.deleteBody;
  if (!await confirmDialog({ title: t.deleteTitle({ number: pay.number }), body, okLabel: t.delete })) return;
  try {
    await api(`/app/api/crm/payments/${encodeURIComponent(pay.id)}`, { method: 'DELETE' });
    closeDrawer();
    toast(t.deleted({ number: pay.number }));
    await reloadAfterPaymentChange();
  } catch (err) { if (err?.message !== 'unauthorized') toast(paymentErrorText(err)); }
}

async function openPaymentDetail(id) {
  const t = CRM.payments;
  let pay = state.payments.find(p => p.id === id);
  try { pay = await api(`/app/api/crm/payments/${encodeURIComponent(id)}`); }
  catch { /* keep list row */ }
  if (!pay) return;
  const status = effectiveStatus(pay);
  const canMarkPaid = pay.status === 'PENDING' || pay.status === 'OVERDUE';
  const tone = { PENDING: 'warn', PAID: 'ok', OVERDUE: 'bad' }[status] || '';
  const kind = pay.supplierId ? 'supplier' : pay.employeeId ? 'employee' : '';
  const payeeKey = kind ? `${kind}:${payeeId(pay)}` : '';
  const payeeLink = !!kind && hasModule(PAYEE_KINDS[kind].module) && !linksBackTo(payeeKey);
  const clientsOn = hasModule('clients');
  const clientLink = clientsOn && !!pay.clientId && !linksBackTo(`client:${pay.clientId}`);
  const here = { key: `payment:${pay.id}`, label: pay.number, open: () => openPaymentDetail(pay.id) };
  const form = document.createElement('div');
  form.className = 'form';
  form.innerHTML = `
    ${detailHead(payeeName(pay), paymentPill(status), pay.totalEur, tone, payeeLink)}
    ${detailMeta([
      { label: STR.detailCreated, value: fmtDay(pay.createdAt) },
      { label: t.thDueDate, value: canMarkPaid ? `${fmtDay(pay.dueDate)} · ${dueWhenText(pay.dueDate, status === 'OVERDUE')}` : fmtDay(pay.dueDate) },
      pay.status === 'PAID' && pay.paidAt ? { label: STR.paidOnLabel, value: fmtDay(pay.paidAt) } : null,
      clientsOn ? { label: t.client, value: pay.clientName || t.noClient } : null,
    ])}
    ${itemsTable(pay.items)}
    ${pay.notes ? `<p class="hint">${escapeHTML(pay.notes)}</p>` : ''}
    <div class="detail__foot">
      ${canMarkPaid ? `<button class="btn btn--sm btn--accent" type="button" id="pay-paid">${escapeHTML(t.markPaid)}</button>` : ''}
      ${clientLink ? `<button class="btn btn--sm btn--ghost" type="button" data-open-client>${escapeHTML(STR.detailOpenRecordAria({ name: pay.clientName || '' }))}</button>` : ''}
      ${clientsOn ? `<button class="btn btn--sm btn--ghost" type="button" data-pay-client>${escapeHTML(pay.clientId ? t.changeClient : t.linkClient)}</button>` : ''}
      ${canMarkPaid ? `<button class="btn btn--sm btn--ghost" type="button" data-pay-cancel>${escapeHTML(t.cancel)}</button>` : ''}
      <button class="btn btn--sm btn--ghost" type="button" data-pay-delete>${escapeHTML(t.delete)}</button>
    </div>`;
  $('[data-detail-link]', form)?.addEventListener('click', () => openFrom(here, () => openPayeeDrawer(kind, payeeId(pay))));
  $('[data-open-client]', form)?.addEventListener('click', () => openFrom(here, () => openClientDrawer(pay.clientId)));
  $('[data-pay-client]', form)?.addEventListener('click', () => openFrom(here, () => openPaymentClientForm(pay)));
  $('#pay-paid', form)?.addEventListener('click', () => markPaymentPaid(pay.id, pay.number));
  $('[data-pay-cancel]', form)?.addEventListener('click', () => cancelPayment(pay));
  $('[data-pay-delete]', form)?.addEventListener('click', () => deletePayment(pay));
  openDrawer(pay.number, form);
}

// Links a payment to the client it was for, or unlinks it.
async function openPaymentClientForm(pay) {
  const t = CRM.payments;
  let clients;
  try { clients = await api('/app/api/crm/clients'); } catch { return toast(t.clientSaveFailed); }
  const current = pay.clientId ? { id: pay.clientId, name: pay.clientName || pay.clientId } : null;
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <p class="hint">${escapeHTML(t.clientHint)}</p>
    ${paymentClientSelect(withClient(clients, current), pay.clientId || '')}
    <button class="btn btn--primary" type="submit">${escapeHTML(t.clientSave)}</button>`;
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api(`/app/api/crm/payments/${encodeURIComponent(pay.id)}/client`, {
        method: 'PATCH',
        body: JSON.stringify({ clientId: $('#p-client', form).value || null }),
      });
      closeDrawer();
      if (state.fetched.payments) state.payments = await api('/app/api/crm/payments').catch(() => state.payments);
      if (state.active === 'payments' || state.active === 'financeiro') render();
      toast(t.clientSaved);
    } catch { btn.disabled = false; toast(t.clientSaveFailed); }
  });
  openDrawer(t.clientFormTitle, form, true);
}
