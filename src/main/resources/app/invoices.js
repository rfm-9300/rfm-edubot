/* Invoices (/app → Invoices): the list, the new-invoice form, and an invoice's detail: marking it paid,
   splitting it into installments and receiving them, its tax office code (ATCUD), cancelling and deleting. */

async function openInvoiceForm(opts = {}) {
  let clients, catalog;
  try {
    [clients, catalog] = await Promise.all([
      api('/app/api/crm/clients'),
      api('/app/api/crm/standard-items').catch(() => []),
    ]);
  } catch { return toast(STR.invoiceCreateFailed); }
  const li = lineItemsField(catalog);
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${clientSelect(withClient(clients, opts.client), opts.client?.id)}
      <div class="form__row"><label class="lbl" for="i-due">${escapeHTML(STR.invoiceDueDate)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="i-due" type="date" required /></div>
      <div class="form__row"><label class="lbl" for="i-quote">${escapeHTML(STR.invoiceQuoteId)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp inp--mono" id="i-quote" placeholder="${escapeHTML(STR.invoiceQuoteIdPh)}" /></div>
      <div class="form__row"><label class="lbl" for="i-taxcode">${escapeHTML(STR.invoiceTaxCode)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp inp--mono" id="i-taxcode" maxlength="80" autocomplete="off" placeholder="${escapeHTML(STR.invoiceTaxCodePh)}" /></div>
    </div>
    ${li.html}
    <button class="btn btn--primary" type="submit">${escapeHTML(STR.invoiceSave)}</button>`;
  li.wire(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const clientId = $('#f-client', form).value;
    const dueDate = $('#i-due', form).value;
    if (!clientId) return toast(STR.invoiceChooseClient);
    if (!dueDate) return toast(STR.invoiceEnterDueDate);
    const items = li.collect(form);
    if (!items.length) return toast(STR.invoiceAddLine);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const taxOfficeCode = $('#i-taxcode', form).value.trim() || null;
      await api('/app/api/crm/invoices', { method: 'POST', body: JSON.stringify({ clientId, quoteId: $('#i-quote', form).value.trim() || null, items, dueDate, taxOfficeCode }) });
      closeDrawer();
      await loadModule('invoices');
      render();
      toast(STR.invoiceCreated);
    } catch (err) { btn.disabled = false; toast(invoiceErrorText(err, STR.invoiceCreateFailed)); }
  });
  openDrawer(STR.invoiceFormTitle, form, true);
}

function invoiceColumns() {
  const t = CRM.invoices;
  const actions = inv => {
    const canMarkPaid = inv.status === 'PENDING' || inv.status === 'OVERDUE';
    // An invoice paid in installments receives its next part from the row instead.
    const nextPart = canMarkPaid ? invoiceParts(inv).findIndex(p => !p.paidAt) : -1;
    const pay = nextPart >= 0
      ? `<button class="btn btn--sm btn--accent" type="button" data-receive-invoice="${escapeHTML(inv.id)}" data-part="${nextPart}">${escapeHTML(STR.installmentReceiveShort)}</button>`
      : canMarkPaid ? `<button class="btn btn--sm btn--accent" type="button" data-mark-paid="${escapeHTML(inv.id)}">${escapeHTML(STR.markPaid)}</button>` : '';
    return `<td class="right"><div class="actions">${pay}${pdfButton(inv.id, 'invoices', inv.hasPdf, inv.number)}</div></td>`;
  };
  return [
    { id: 'number', label: t.thNumber, cell: inv => `<td class="id">${escapeHTML(inv.number)}</td>` },
    { id: 'client', label: t.thClient, cell: inv => `<td class="name">${escapeHTML(inv.clientName || '')}</td>` },
    { id: 'status', label: t.thStatus, cell: inv => `<td>${invoicePill(inv.status)}</td>` },
    { id: 'dueDate', label: t.thDueDate, cell: inv => `<td class="mono muted">${fmtDay(inv.dueDate)}</td>` },
    { id: 'total', label: t.thTotal, head: 'right', cell: inv => `<td class="num">${invoiceTotalCell(inv)}</td>` },
    { id: 'actions', label: t.thPdfActions, head: 'right', pin: 'end', cell: actions },
  ];
}

function renderInvoices(root) {
  const t = CRM.invoices;
  const q = state.search.toLowerCase();
  const matches = inv => !q || `${inv.number} ${inv.clientName || ''} ${invoiceStatusLabel(inv.status)} ${inv.taxOfficeCode || ''}`.toLowerCase().includes(q);
  const columns = orderedColumns('invoices', invoiceColumns());
  const rowClass = inv => (inv.status === 'PAID' ? 'is-paid' : inv.status === 'OVERDUE' ? 'is-overdue' : inv.status === 'CANCELLED' ? 'is-draft' : '');
  const rows = state.invoices
    .filter(inv => !state.filterInvoiceStatus || inv.status === state.filterInvoiceStatus)
    .filter(matches)
    .map(inv => `<tr class="conversation-row ${rowClass(inv)}" data-invoice="${escapeHTML(inv.id)}">${columnCells(columns, inv)}</tr>`)
    .join('');
  const { paid, pending, overdue } = invoiceRollup(state.invoices);
  const period = state.filterInvoicePeriod || '';
  const listed = state.invoices
    .filter(inv => !state.filterInvoiceStatus || inv.status === state.filterInvoiceStatus)
    .filter(matches);
  const invoiceGroups = period ? groupByPeriod(listed, period, inv => inv.createdAt || inv.dueDate) : [];
  const nowKey = period ? (state.filterInvoicePeriodKey || currentPeriodKey(period)) : '';
  const nowRoll = invoiceRollup(groupItems(invoiceGroups, nowKey));
  const prevKey = period ? shiftPeriodKey(nowKey, period, -1) : '';
  const prevExists = invoiceGroups.some(([key]) => key === prevKey);
  const delta = prevExists ? periodDelta(nowRoll.total, invoiceRollup(groupItems(invoiceGroups, prevKey)).total) : { text: '', trend: '' };
  const allRoll = invoiceRollup(listed);
  const summaryRows = period && invoiceGroups.length
    ? `<tr class="is-total"><td class="name">${escapeHTML(t.thTotal)}</td><td class="num">${allRoll.count}</td><td class="num">${fmtEUR(allRoll.paid)}</td><td class="num">${fmtEUR(allRoll.pending)}</td><td class="num">${fmtEUR(allRoll.overdue)}</td><td class="num">${fmtEUR(allRoll.total)}</td></tr>`
      + invoiceGroups.map(([key, items]) => {
        const roll = invoiceRollup(items);
        return `<tr class="${key === nowKey ? 'is-current' : ''}"><td class="name">${escapeHTML(periodLabel(key, period))}</td><td class="num">${roll.count}</td><td class="num">${fmtEUR(roll.paid)}</td><td class="num">${fmtEUR(roll.pending)}</td><td class="num">${fmtEUR(roll.overdue)}</td><td class="num">${fmtEUR(roll.total)}</td></tr>`;
      }).join('')
    : '';
  const views = periodChips(period, 'data-inv-period', { '': t.viewList, week: t.byWeek, month: t.byMonth });
  const tools = `<button class="chip ${!state.filterInvoiceStatus ? 'is-on' : ''}" data-filter-inv="">${escapeHTML(t.filterAll)}</button>`
    + INVOICE_STATUSES.map(s => `<button class="chip ${state.filterInvoiceStatus === s ? 'is-on' : ''}" data-filter-inv="${s}">${escapeHTML(invoiceStatusLabel(s))}</button>`).join('')
    + (period ? '' : columnOrderButton('invoices'));
  const summaryHead = `<tr><th>${escapeHTML(t.thPeriod)}</th><th class="right">${escapeHTML(t.thCount)}</th><th class="right">${escapeHTML(t.paid)}</th><th class="right">${escapeHTML(t.pending)}</th><th class="right">${escapeHTML(t.overdue)}</th><th class="right">${escapeHTML(t.thTotal)}</th></tr>`;
  const listHead = columnsHead(columns, 'invoices');
  const invoiceNav = periodNav(nowKey, period, 'data-inv-period-nav', t);
  const invoiceStats = period
    ? [
      { label: t.paid, value: fmtEUR(nowRoll.paid) },
      { label: t.pending, value: fmtEUR(nowRoll.pending) },
      { label: t.overdue, value: fmtEUR(nowRoll.overdue) },
      { label: t.thTotal, value: fmtEUR(nowRoll.total), hint: delta.text ? t.vsPrev({ delta: delta.text }) : '', trend: delta.trend },
    ]
    : [
      { label: t.paid, value: fmtEUR(paid) },
      { label: t.pending, value: fmtEUR(pending) },
      { label: t.overdue, value: fmtEUR(overdue) },
    ];
  root.innerHTML = hero(labels.invoices, CRM.tabs.faturas.desc, statCards(invoiceStats), invoiceNav) + crmPanel({
    title: period === 'week' ? t.summaryWeek : period === 'month' ? t.summaryMonth : t.documents,
    tag: period ? invoiceGroups.length : listed.length,
    views,
    tools,
    head: period ? summaryHead : listHead,
    rows: period ? summaryRows : rows,
    empty: period ? t.summaryEmpty : t.emptyTitle,
    emptyDesc: period ? t.summaryEmptyDesc : t.emptyDesc,
  });
  wirePdfButtons(root);
  wireColumnOrder(root, 'invoices', invoiceColumns());
  $$('[data-inv-period]', root).forEach(btn => btn.addEventListener('click', () => { state.filterInvoicePeriod = btn.dataset.invPeriod; state.filterInvoicePeriodKey = ''; render(); }));
  $$('[data-inv-period-nav]', root).forEach(btn => btn.addEventListener('click', () => {
    const dir = btn.dataset.invPeriodNav;
    state.filterInvoicePeriodKey = dir === 'current' ? '' : shiftPeriodKey(nowKey, period, dir === 'prev' ? -1 : 1);
    render();
  }));
  $$('[data-filter-inv]', root).forEach(btn => btn.addEventListener('click', () => { state.filterInvoiceStatus = btn.dataset.filterInv; render(); }));
  $$('[data-mark-paid]', root).forEach(btn => btn.addEventListener('click', e => {
    e.stopPropagation();
    markInvoicePaid(btn.dataset.markPaid);
  }));
  $$('[data-receive-invoice]', root).forEach(btn => btn.addEventListener('click', e => {
    e.stopPropagation();
    const inv = state.invoices.find(i => i.id === btn.dataset.receiveInvoice);
    if (inv) receiveInvoiceInstallment(inv, Number(btn.dataset.part));
  }));
  $$('[data-invoice]', root).forEach(r => r.addEventListener('click', e => {
    if (e.target.closest('[data-pdf-url], [data-mark-paid], [data-receive-invoice]')) return;
    openInvoiceDetail(r.dataset.invoice);
  }));
}

// [rest] is what an invoice paid in installments still owes: marking it paid receives all of that today.
async function markInvoicePaid(id, number = state.invoices.find(i => i.id === id)?.number || '', rest = null) {
  const ok = await confirmDialog({
    title: STR.markPaidConfirmTitle,
    body: rest == null ? STR.markPaidConfirmBody({ number }) : STR.markPaidConfirmBodyRest({ number, amount: fmtEUR(rest) }),
    okLabel: STR.markPaid,
    danger: false,
  });
  if (!ok) return;
  try {
    await api(`/app/api/crm/invoices/${encodeURIComponent(id)}/paid`, { method: 'PATCH' });
    closeDrawer();
    await loadModule('invoices');
    render();
    toast(STR.markedPaid({ number }));
  } catch { toast(STR.markPaidFailed); }
}

// The i18n proxy answers a missing key with its own path, so `catalogTextOr` checks for that.
const invoiceErrorText = (err, fallback = STR.invoiceActionFailed) => catalogTextOr(STR, 'app', err?.code ? `invoiceErr_${err.code}` : '', fallback);

// After an invoice changes: its list, and the page behind the drawer when that page counts invoice money
// (cancelling or deleting one also reopens the Serviços rows it billed).
async function reloadAfterInvoiceChange() {
  await loadModule('invoices').catch(() => {});
  if (['services', 'financeiro', 'overview'].includes(state.active)) await loadModule(state.active).catch(() => {});
  render();
}

async function receiveInvoiceInstallment(inv, index, reopen = false) {
  const parts = invoiceParts(inv);
  const part = parts[index];
  if (!part) return;
  const last = parts.filter(p => !p.paidAt).length === 1;
  const ok = await confirmDialog({
    title: STR.installmentReceiveTitle,
    body: STR.installmentReceiveBody({ n: index + 1, of: parts.length, amount: fmtEUR(part.amountEur), number: inv.number, last }),
    okLabel: STR.installmentReceiveShort,
    danger: false,
  });
  if (!ok) return;
  try {
    const updated = await api(`/app/api/crm/invoices/${encodeURIComponent(inv.id)}/installments/${index}/paid`, { method: 'PATCH' });
    toast(updated.status === 'PAID' ? STR.markedPaid({ number: inv.number }) : STR.installmentReceived({ n: index + 1, of: parts.length, number: inv.number }));
    await reloadAfterInvoiceChange();
    if (reopen) openInvoiceDetail(inv.id);
  } catch (err) { if (err?.message !== 'unauthorized') toast(invoiceErrorText(err)); }
}

async function cancelInvoice(inv) {
  const ok = await confirmDialog({ title: STR.invoiceCancelTitle({ number: inv.number }), body: STR.invoiceCancelBody, okLabel: STR.invoiceCancel });
  if (!ok) return;
  try {
    await api(`/app/api/crm/invoices/${encodeURIComponent(inv.id)}/cancel`, { method: 'POST' });
    closeDrawer();
    toast(STR.invoiceCancelled({ number: inv.number }));
    await reloadAfterInvoiceChange();
  } catch (err) { if (err?.message !== 'unauthorized') toast(invoiceErrorText(err)); }
}

async function deleteInvoice(inv) {
  const body = invoicePaidEur(inv) > 0 ? STR.invoiceDeleteBodyPaid({ amount: fmtEUR(invoicePaidEur(inv)) }) : STR.invoiceDeleteBody;
  const ok = await confirmDialog({ title: STR.invoiceDeleteTitle({ number: inv.number }), body, okLabel: STR.invoiceDelete });
  if (!ok) return;
  try {
    await api(`/app/api/crm/invoices/${encodeURIComponent(inv.id)}`, { method: 'DELETE' });
    closeDrawer();
    toast(STR.invoiceDeleted({ number: inv.number }));
    await reloadAfterInvoiceChange();
  } catch (err) { if (err?.message !== 'unauthorized') toast(invoiceErrorText(err)); }
}

// The parts of an invoice paid in installments, each received on its own. Two columns, so a phone
// shows the amount and its action without scrolling: the action wraps under the amount.
function installmentsPanel(inv) {
  const parts = invoiceParts(inv);
  const today = todayKey();
  const open = inv.status === 'PENDING' || inv.status === 'OVERDUE';
  const rows = parts.map((p, i) => {
    const paid = !!p.paidAt || inv.status === 'PAID';
    const late = !paid && open && p.dueDate.slice(0, 10) < today;
    const when = paid ? (p.paidAt ? STR.installmentPaidOn({ date: fmtDay(p.paidAt) }) : STR.installmentPaid)
      : open ? dueWhenText(p.dueDate, late) : invoiceStatusLabel(inv.status);
    const side = paid ? `<span class="pill pill--ok">${escapeHTML(STR.installmentPaid)}</span>`
      : open ? `<button class="btn btn--sm btn--accent" type="button" data-receive-part="${i}">${escapeHTML(STR.installmentReceiveShort)}</button>` : '';
    return `<tr class="${paid ? 'is-paid' : late ? 'is-overdue' : ''}">
      <td><div class="col"><span class="mono">${escapeHTML(fmtDay(p.dueDate))}</span><span class="sub">${escapeHTML(`${i + 1}/${parts.length} · ${when}`)}</span></div></td>
      <td class="right"><div class="actions"><strong class="mono">${fmtEUR(p.amountEur)}</strong>${side}</div></td>
    </tr>`;
  }).join('');
  return `<section class="panel">
    <header class="panel__head"><h2 class="panel__title">${escapeHTML(STR.installmentsTitle)} <span class="tag">${parts.length}</span></h2>
      <span class="panel__meta">${escapeHTML(STR.installmentsReceivedOf({ paid: fmtEUR(invoicePaidEur(inv)), total: fmtEUR(inv.totalEur) }))}</span></header>
    <div class="tbl-wrap"><table class="tbl"><thead><tr><th>${escapeHTML(STR.installmentsColDue)}</th><th class="right">${escapeHTML(STR.installmentsColAmount)}</th></tr></thead><tbody>${rows}</tbody></table></div>
  </section>`;
}

async function openInvoiceDetail(id) {
  let inv = state.invoices.find(i => i.id === id);
  const email = emailStatus();
  try { inv = await api(`/app/api/crm/invoices/${encodeURIComponent(id)}`); }
  catch { /* keep list row */ }
  if (!inv) return;
  const canEmail = !!(await email)?.configured && inv.status !== 'CANCELLED';
  const status = effectiveStatus(inv);
  const unpaid = inv.status === 'PENDING' || inv.status === 'OVERDUE';
  const parts = invoiceParts(inv);
  const received = invoicePaidEur(inv);
  const here = { key: `invoice:${inv.id}`, label: inv.number, open: () => openInvoiceDetail(inv.id) };
  const clientLink = hasModule('clients') && !!inv.clientId && !linksBackTo(`client:${inv.clientId}`);
  const quoteLink = hasModule('quotes') && !!inv.quoteId && !linksBackTo(`quote:${inv.quoteId}`);
  const projectLink = hasModule('projects') && !!inv.projectId && !linksBackTo(`project:${inv.projectId}`);
  const form = document.createElement('div');
  form.className = 'form';
  form.innerHTML = `
    ${detailHead(inv.clientName, invoicePill(status), inv.totalEur, invoiceTone(status), clientLink)}
    ${detailMeta([
      { label: STR.detailIssued, value: fmtDay(inv.createdAt) },
      {
        label: unpaid && parts.length ? STR.invoiceNextDue : STR.thDueDate,
        value: unpaid ? `${fmtDay(inv.dueDate)} · ${dueWhenText(inv.dueDate, status === 'OVERDUE')}` : fmtDay(inv.dueDate),
      },
      inv.status === 'PAID' && inv.paidAt ? { label: STR.paidOnLabel, value: fmtDay(inv.paidAt) } : null,
      unpaid && received > 0 ? { label: STR.invoiceReceived, value: fmtEUR(received) } : null,
      unpaid && received > 0 ? { label: STR.invoiceStillOwed, value: fmtEUR(invoiceOutstandingEur(inv)) } : null,
      inv.taxOfficeCode ? { label: STR.invoiceTaxCode, value: inv.taxOfficeCode } : null,
      inv.projectLabel ? { label: BLD.project, value: [inv.projectLabel, inv.measurementNumber ? bt('measurementN', { n: inv.measurementNumber }) : '', inv.retentionRelease ? BLD.retentionReleaseLabel : ''].filter(Boolean).join(' · ') } : null,
    ])}
    ${inv.quoteNumber && !quoteLink ? `<p class="hint">${escapeHTML(STR.invoiceFromQuote({ number: inv.quoteNumber }))}</p>` : ''}
    ${itemsTable(inv.items)}
    ${parts.length ? installmentsPanel(inv) : ''}
    <div class="detail__foot">
      ${unpaid ? `<button class="btn btn--sm btn--accent" type="button" id="inv-paid">${escapeHTML(STR.markPaid)}</button>` : ''}
      ${unpaid ? `<button class="btn btn--sm" type="button" data-inv-parts>${escapeHTML(parts.length ? STR.installmentsChange : STR.installmentsSplit)}</button>` : ''}
      ${canEmail ? `<button class="btn btn--sm" type="button" data-send-email>${escapeHTML(GOOGLE.sendByEmail)}</button>` : ''}
      ${pdfButton(inv.id, 'invoices', inv.hasPdf, inv.number)}
      ${quoteLink ? `<button class="btn btn--sm btn--ghost" type="button" data-open-quote>${escapeHTML(STR.detailOpenDoc({ number: inv.quoteNumber || '' }))}</button>` : ''}
      ${projectLink ? `<button class="btn btn--sm btn--ghost" type="button" data-open-project>${escapeHTML(BLD.openProject)}</button>` : ''}
      <button class="btn btn--sm btn--ghost" type="button" data-inv-taxcode>${escapeHTML(inv.taxOfficeCode ? STR.invoiceTaxCodeChange : STR.invoiceTaxCodeAdd)}</button>
      ${unpaid && received === 0 ? `<button class="btn btn--sm btn--ghost" type="button" data-inv-cancel>${escapeHTML(STR.invoiceCancel)}</button>` : ''}
      <button class="btn btn--sm btn--ghost" type="button" data-inv-delete>${escapeHTML(STR.invoiceDelete)}</button>
    </div>`;
  $('[data-detail-link]', form)?.addEventListener('click', () => openFrom(here, () => openClientDrawer(inv.clientId)));
  $('[data-open-quote]', form)?.addEventListener('click', () => openFrom(here, () => openQuoteDetail(inv.quoteId)));
  $('[data-open-project]', form)?.addEventListener('click', () => openFrom(here, () => openProjectDrawer(inv.projectId, 'measurements')));
  $('#inv-paid', form)?.addEventListener('click', () => markInvoicePaid(inv.id, inv.number, parts.length ? invoiceOutstandingEur(inv) : null));
  $('[data-inv-parts]', form)?.addEventListener('click', () => openFrom(here, () => openInvoiceInstallmentsForm(inv)));
  $$('[data-receive-part]', form).forEach(b => b.addEventListener('click', () => receiveInvoiceInstallment(inv, Number(b.dataset.receivePart), true)));
  $('[data-inv-taxcode]', form)?.addEventListener('click', () => openFrom(here, () => openInvoiceTaxCodeForm(inv)));
  $('[data-inv-cancel]', form)?.addEventListener('click', () => cancelInvoice(inv));
  $('[data-inv-delete]', form)?.addEventListener('click', () => deleteInvoice(inv));
  $('[data-send-email]', form)?.addEventListener('click', () => openFrom(here, () => openSendEmail('invoice', inv.id, inv.number)));
  wirePdfButtons(form);
  openDrawer(inv.number, form);
  mountRecordAutomations(form, { type: 'invoice', id: inv.id }, here);
}

// "Same day next month" for installment dates; the 31st becomes the month's last day.
function addMonthsKey(key, n) {
  const [y, m, d] = key.split('-').map(Number);
  const last = new Date(Date.UTC(y, m + n, 0)).getUTCDate();
  return new Date(Date.UTC(y, m - 1 + n, Math.min(d, last))).toISOString().slice(0, 10);
}

// [cents] in [n] equal parts a month apart from [firstKey]; leftover cents go to the first parts.
function splitInstallments(cents, n, firstKey) {
  const base = Math.floor(cents / n);
  return Array.from({ length: n }, (_, i) => ({
    amountEur: (base + (i < cents - base * n ? 1 : 0)) / 100,
    dueDate: addMonthsKey(firstKey, i),
  }));
}

// Splits what an invoice still owes into installments, each with its own amount and date. Parts already
// received stay as they are; leaving a single part pays the invoice in one go again, by that date.
function openInvoiceInstallmentsForm(inv) {
  const parts = invoiceParts(inv);
  const received = parts.filter(p => p.paidAt);
  const owedCents = Math.round(Number(inv.totalEur || 0) * 100) - Math.round(sumParts(received) * 100);
  const open = parts.filter(p => !p.paidAt).map(p => ({ amountEur: p.amountEur, dueDate: p.dueDate.slice(0, 10) }));
  const initial = open.length ? open : splitInstallments(owedCents, 2, todayKey());
  const removeIcon = '<svg width="12" height="12" viewBox="0 0 16 16"><path d="M3 3 L13 13 M13 3 L3 13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>';
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <p class="hint">${escapeHTML([
      STR.installmentsIntro({ amount: fmtEUR(owedCents / 100) }),
      received.length ? STR.installmentsKeepReceived({ n: received.length, amount: fmtEUR(sumParts(received)) }) : '',
    ].filter(Boolean).join(' '))}</p>
    <div class="lines-field">
      <div class="form__row-head">
        <span class="lbl" id="ip-label">${escapeHTML(STR.installmentsTitle)}</span>
        <div class="actions"><span class="hint">${escapeHTML(STR.installmentsSplitEvenly)}</span>
          ${[2, 3, 4, 6, 12].map(n => `<button class="btn btn--sm btn--ghost" type="button" data-ip-split="${n}" aria-label="${escapeHTML(STR.installmentsSplitAria({ n }))}">${escapeHTML(STR.installmentsSplitShort({ n }))}</button>`).join('')}
        </div>
      </div>
      <div class="lines lines--installments" role="group" aria-labelledby="ip-label">
        <div class="lines__head"><span>${escapeHTML(STR.installmentsColPart)}</span><span>${escapeHTML(STR.installmentsColAmount)}</span><span>${escapeHTML(STR.installmentsColDue)}</span><span></span></div>
        <div id="ip-body"></div>
        <div class="lines__foot">
          <button class="btn btn--sm btn--ghost" type="button" id="ip-add">${escapeHTML(STR.installmentsAdd)}</button>
          <div class="lines__total"><span class="muted" id="ip-sum-label"></span><span class="v" id="ip-sum"></span></div>
        </div>
      </div>
      <p class="hint" id="ip-check" role="status"></p>
    </div>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(STR.installmentsSave)}</button>
      <button class="btn btn--ghost" type="button" data-form-cancel>${escapeHTML(STR.cancel)}</button>
    </div>`;
  const body = $('#ip-body', form);
  const rows = () => $$('.line', body).map(row => ({
    cents: Math.round(Number($('[data-k="amountEur"]', row).value || 0) * 100),
    dueDate: $('[data-k="dueDate"]', row).value,
  }));
  // The sum against what is still owed, and the first thing to fix before saving.
  const check = () => {
    const list = rows();
    $$('.line__index', body).forEach((el, i) => { el.textContent = String(received.length + i + 1); });
    const diff = owedCents - list.reduce((t, r) => t + r.cents, 0);
    $('#ip-sum-label', form).textContent = diff > 0 ? STR.installmentsMissing : diff < 0 ? STR.installmentsOver : STR.lineTotal;
    $('#ip-sum', form).textContent = fmtEUR(Math.abs(diff ? diff : owedCents) / 100);
    const problem = !list.length ? STR.installmentsNeedOne
      : list.some(r => r.cents <= 0) ? STR.installmentsNeedAmounts
        : list.some(r => !r.dueDate) ? STR.installmentsNeedDates
          : diff !== 0 ? STR.installmentsMustAddUp({ amount: fmtEUR(owedCents / 100) })
            : list.length === 1 && !received.length ? STR.installmentsSingle : '';
    const hint = $('#ip-check', form);
    hint.textContent = problem;
    hint.classList.toggle('hint--warn', !!problem && problem !== STR.installmentsSingle);
    return problem === STR.installmentsSingle ? '' : problem;
  };
  const addRow = (preset = {}) => {
    const row = document.createElement('div');
    row.className = 'line';
    row.innerHTML = `
      <span class="line__index mono"></span>
      <input type="number" data-k="amountEur" class="num" min="0" step="0.01" placeholder="0.00" aria-label="${escapeHTML(STR.installmentsColAmount)}" value="${preset.amountEur ?? ''}" />
      <input type="date" data-k="dueDate" class="mono" aria-label="${escapeHTML(STR.installmentsColDue)}" value="${escapeHTML(preset.dueDate || '')}" />
      <button type="button" class="l-rm" title="${escapeHTML(STR.installmentsRemove)}" aria-label="${escapeHTML(STR.installmentsRemove)}">${removeIcon}</button>`;
    row.querySelectorAll('input').forEach(i => i.addEventListener('input', check));
    $('.l-rm', row).addEventListener('click', () => { row.remove(); check(); });
    body.appendChild(row);
    return row;
  };
  const fill = list => { body.innerHTML = ''; list.forEach(addRow); check(); };
  fill(initial);
  $$('[data-ip-split]', form).forEach(b => b.addEventListener('click', () => {
    const first = rows()[0]?.dueDate || todayKey();
    fill(splitInstallments(owedCents, Number(b.dataset.ipSplit), first));
  }));
  $('#ip-add', form).addEventListener('click', () => {
    const list = rows();
    const left = owedCents - list.reduce((t, r) => t + r.cents, 0);
    const last = list[list.length - 1]?.dueDate;
    const row = addRow({ amountEur: left > 0 ? left / 100 : '', dueDate: last ? addMonthsKey(last, 1) : todayKey() });
    check();
    $('[data-k="amountEur"]', row).focus();
  });
  $('[data-form-cancel]', form).addEventListener('click', () => closeDrawer());
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const problem = check();
    if (problem) return toast(problem);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const installments = rows().map(r => ({ amountEur: r.cents / 100, dueDate: r.dueDate }));
      await api(`/app/api/crm/invoices/${encodeURIComponent(inv.id)}/installments`, { method: 'PUT', body: JSON.stringify({ installments }) });
      closeDrawer();
      toast(STR.installmentsSaved);
      await reloadAfterInvoiceChange();
    } catch (err) { btn.disabled = false; if (err?.message !== 'unauthorized') toast(invoiceErrorText(err)); }
  });
  openDrawer(parts.length ? STR.installmentsChangeTitle({ number: inv.number }) : STR.installmentsSplitTitle({ number: inv.number }), form);
}

// The code the tax office gave the invoice (the ATCUD in Portugal). It usually arrives after the invoice
// was made, so it is added or changed here; the PDF prints it.
function openInvoiceTaxCodeForm(inv) {
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__row"><label class="lbl" for="it-code">${escapeHTML(STR.invoiceTaxCode)}</label>
      <input class="inp inp--mono" id="it-code" maxlength="80" autocomplete="off" placeholder="${escapeHTML(STR.invoiceTaxCodePh)}" value="${escapeHTML(inv.taxOfficeCode || '')}" />
      <p class="hint">${escapeHTML(STR.invoiceTaxCodeHint)}</p></div>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(STR.invoiceTaxCodeSave)}</button>
      <button class="btn btn--ghost" type="button" data-form-cancel>${escapeHTML(STR.cancel)}</button>
    </div>`;
  $('[data-form-cancel]', form).addEventListener('click', () => closeDrawer());
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    const taxOfficeCode = $('#it-code', form).value.trim();
    try {
      await api(`/app/api/crm/invoices/${encodeURIComponent(inv.id)}/tax-office-code`, { method: 'PATCH', body: JSON.stringify({ taxOfficeCode }) });
      closeDrawer();
      toast(taxOfficeCode ? STR.invoiceTaxCodeSaved : STR.invoiceTaxCodeCleared);
      await reloadAfterInvoiceChange();
    } catch (err) { btn.disabled = false; if (err?.message !== 'unauthorized') toast(invoiceErrorText(err)); }
  });
  openDrawer(STR.invoiceTaxCodeTitle({ number: inv.number }), form);
}
