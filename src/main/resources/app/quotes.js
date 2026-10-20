/* Quotes (/app → Quotes): the list, the new-quote form, and a quote's detail, where it is marked sent or
   accepted, emailed, and turned into an invoice. */

// `opts.items` and `opts.notes` start the quote with what was asked for (an email's request); `opts.onSaved(quote)`
// runs after it is created instead of showing the quotes list.
async function openQuoteForm(opts = {}) {
  let clients, catalog;
  try {
    [clients, catalog] = await Promise.all([
      api('/app/api/crm/clients'),
      api('/app/api/crm/standard-items').catch(() => []),
    ]);
  } catch { return toast(STR.quoteCreateFailed); }
  const li = lineItemsField(catalog);
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${clientSelect(withClient(clients, opts.client), opts.client?.id)}
      <div class="form__row"><label class="lbl" for="q-valid">${escapeHTML(STR.quoteValidUntil)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp inp--mono" id="q-valid" type="date" /></div>
    </div>
    ${li.html}
    <div class="form__row form__row--full"><label class="lbl" for="q-notes">${escapeHTML(STR.quoteNotes)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
      <textarea class="txt" id="q-notes" placeholder="${escapeHTML(STR.quoteNotesPh)}">${escapeHTML(opts.notes || '')}</textarea></div>
    <button class="btn btn--primary" type="submit">${escapeHTML(STR.quoteSave)}</button>`;
  li.wire(form, { initial: opts.items || [] });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const clientId = $('#f-client', form).value;
    if (!clientId) return toast(STR.quoteChooseClient);
    const items = li.collect(form);
    if (!items.length) return toast(STR.quoteAddLine);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const saved = await api('/app/api/crm/quotes', { method: 'POST', body: JSON.stringify({ clientId, items, notes: $('#q-notes', form).value.trim() || null, validUntil: $('#q-valid', form).value || null }) });
      closeDrawer();
      if (opts.onSaved) { toast(STR.quoteCreated); return opts.onSaved(saved); }
      await loadModule('quotes');
      render();
      toast(STR.quoteCreated);
    } catch { btn.disabled = false; toast(STR.quoteCreateFailed); }
  });
  openDrawer(STR.quoteFormTitle, form, true);
}

function quoteColumns() {
  const t = CRM.quotes;
  return [
    { id: 'number', label: t.thNumber, cell: quote => `<td class="id">${escapeHTML(quote.number)}</td>` },
    { id: 'client', label: t.thClient, cell: quote => `<td class="name">${escapeHTML(quote.clientName || '')}</td>` },
    { id: 'status', label: t.thStatus, cell: quote => `<td>${quotePill(quote.status)}</td>` },
    { id: 'validUntil', label: t.thValidUntil, cell: quote => `<td class="mono muted">${fmtDay(quote.validUntil)}</td>` },
    { id: 'total', label: t.thTotal, head: 'right', cell: quote => `<td class="num">${fmtEUR(quote.totalEur)}</td>` },
    { id: 'pdf', label: t.thPdf, head: 'right', pin: 'end', cell: quote => `<td class="right"><div class="actions">${pdfButton(quote.id, 'quotes', quote.hasPdf, quote.number)}</div></td>` },
  ];
}

function renderQuotes(root) {
  const t = CRM.quotes;
  const q = state.search.toLowerCase();
  const columns = orderedColumns('quotes', quoteColumns());
  const rows = state.quotes
    .filter(quote => !state.filterQuoteStatus || quote.status === state.filterQuoteStatus)
    .filter(quote => !q || `${quote.number} ${quote.clientName || ''} ${quoteStatusLabel(quote.status)}`.toLowerCase().includes(q))
    .map(quote => `<tr class="conversation-row ${quote.status === 'ACEITO' ? 'is-paid' : ''}" data-quote="${escapeHTML(quote.id)}">${columnCells(columns, quote)}</tr>`)
    .join('');
  const accepted = state.quotes.filter(o => o.status === 'ACEITO').reduce((sum, o) => sum + Number(o.totalEur || 0), 0);
  const sent = state.quotes.filter(o => o.status === 'PENDENTE' || o.status === 'SENT').reduce((sum, o) => sum + Number(o.totalEur || 0), 0);
  const tools = `<button class="chip ${!state.filterQuoteStatus ? 'is-on' : ''}" data-filter-quote="">${escapeHTML(t.filterAll)}</button>`
    + QUOTE_STATUSES.map(s => `<button class="chip ${state.filterQuoteStatus === s ? 'is-on' : ''}" data-filter-quote="${s}">${escapeHTML(quoteStatusLabel(s))}</button>`).join('')
    + columnOrderButton('quotes');
  root.innerHTML = hero(labels.quotes, CRM.tabs.orcamentos.desc, statCards([
    { label: t.total, value: state.quotes.length },
    { label: t.sent, value: fmtEUR(sent) },
    { label: t.accepted, value: fmtEUR(accepted) },
  ])) + crmPanel({
    title: t.proposals,
    tag: rows ? state.quotes.filter(o => !state.filterQuoteStatus || o.status === state.filterQuoteStatus).length : 0,
    tools,
    head: columnsHead(columns, 'quotes'),
    rows,
    empty: t.emptyTitle,
    emptyDesc: t.emptyDesc,
  });
  wirePdfButtons(root);
  wireColumnOrder(root, 'quotes', quoteColumns());
  $$('[data-filter-quote]', root).forEach(btn => btn.addEventListener('click', () => { state.filterQuoteStatus = btn.dataset.filterQuote; render(); }));
  $$('[data-quote]', root).forEach(r => r.addEventListener('click', e => {
    if (e.target.closest('[data-pdf-url]')) return;
    openQuoteDetail(r.dataset.quote);
  }));
}

async function openQuoteDetail(id) {
  let quote = state.quotes.find(q => q.id === id);
  const email = emailStatus();
  try { quote = await api(`/app/api/crm/quotes/${encodeURIComponent(id)}`); }
  catch { /* keep list row */ }
  if (!quote) return;
  const canEmail = !!(await email)?.configured && quote.status !== 'CANCELLED';
  // Converting marks the quote accepted and bills it once: by its invoice, the one that points back at it, or by
  // the service made from it, which is invoiced later. A cancelled one frees the quote to be converted again.
  const accepted = quote.status === 'ACEITO';
  const [ownInvoice, service] = await Promise.all([
    hasModule('invoices') && accepted
      ? api(`/app/api/crm/invoices?clientId=${encodeURIComponent(quote.clientId)}`).then(list => list.find(i => i.quoteId === quote.id && i.status !== 'CANCELLED') || null).catch(() => null)
      : null,
    hasModule('services') && accepted
      ? api(`/app/api/crm/services?quoteId=${encodeURIComponent(quote.id)}`).then(list => list.find(s => s.status !== 'CANCELLED') || null).catch(() => null)
      : null,
  ]);
  const invoice = ownInvoice || (service?.invoiceId && hasModule('invoices')
    ? await api(`/app/api/crm/invoices/${encodeURIComponent(service.invoiceId)}`).catch(() => null)
    : null);
  const here = { key: `quote:${quote.id}`, label: quote.number, open: () => openQuoteDetail(quote.id) };
  const isOpen = quote.status === 'PENDENTE' || quote.status === 'SENT';
  const expired = !!quote.validUntil && quote.validUntil.slice(0, 10) < todayKey();
  const validity = quote.validUntil && isOpen
    ? `${fmtDay(quote.validUntil)} · ${expired ? STR.clientAttnQuoteExpired({ when: relDay(quote.validUntil) }) : STR.quoteExpiresWhen({ when: relDay(quote.validUntil) })}`
    : fmtDay(quote.validUntil);
  const form = document.createElement('div');
  form.className = 'form';
  const canSend = quote.status === 'PENDENTE';
  const canAccept = isOpen;
  const billable = quote.status !== 'CANCELLED' && !invoice && !service;
  const canConvert = hasModule('invoices') && billable;
  const canService = hasModule('services') && billable;
  const clientLink = hasModule('clients') && !!quote.clientId && !linksBackTo(`client:${quote.clientId}`);
  form.innerHTML = `
    ${detailHead(quote.clientName, quotePill(quote.status), quote.totalEur, quoteTone(quote.status), clientLink)}
    ${detailMeta([
      { label: STR.detailCreated, value: fmtDay(quote.createdAt) },
      quote.validUntil ? { label: CRM.quotes.thValidUntil, value: validity } : null,
      service ? { label: STR.clientKindService, value: service.name } : null,
      invoice ? { label: STR.detailInvoice, value: invoice.number } : null,
    ])}
    ${itemsTable(quote.items)}
    ${quote.notes ? `<p class="hint">${escapeHTML(quote.notes)}</p>` : ''}
    ${canConvert ? `<div class="form__row"><label class="lbl" for="q-due">${escapeHTML(STR.quoteConvertDue)}</label>
      <input class="inp" id="q-due" type="date" value="${new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10)}" /></div>` : ''}
    <div class="detail__foot">
      ${canEmail ? `<button class="btn btn--sm" type="button" data-send-email>${escapeHTML(GOOGLE.sendByEmail)}</button>` : ''}
      ${canSend ? `<button class="btn btn--sm" type="button" data-q-status="SENT">${escapeHTML(STR.quoteMarkSent)}</button>` : ''}
      ${canAccept ? `<button class="btn btn--sm" type="button" data-q-status="ACEITO">${escapeHTML(STR.quoteAccept)}</button>` : ''}
      ${canConvert ? `<button class="btn btn--sm btn--primary" type="button" id="q-convert">${escapeHTML(STR.quoteConvert)}</button>` : ''}
      ${canService ? `<button class="btn btn--sm" type="button" data-q-service>${escapeHTML(STR.quoteToService)}</button>` : ''}
      ${pdfButton(quote.id, 'quotes', quote.hasPdf, quote.number)}
      ${service && !linksBackTo(`service:${service.id}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-open-service>${escapeHTML(STR.bookingsOpenService)}</button>` : ''}
      ${invoice && !linksBackTo(`invoice:${invoice.id}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-open-invoice>${escapeHTML(STR.detailOpenDoc({ number: invoice.number }))}</button>` : ''}
    </div>`;
  $('[data-detail-link]', form)?.addEventListener('click', () => openFrom(here, () => openClientDrawer(quote.clientId)));
  $('[data-q-service]', form)?.addEventListener('click', () => openFrom(here, () => openQuoteServiceForm(quote)));
  $('[data-open-service]', form)?.addEventListener('click', () => openFrom(here, () => openServiceDetail(service.id)));
  $('[data-open-invoice]', form)?.addEventListener('click', () => openFrom(here, () => openInvoiceDetail(invoice.id)));
  $('[data-send-email]', form)?.addEventListener('click', () => openFrom(here, () => openSendEmail('quote', quote.id, quote.number)));
  form.querySelectorAll('[data-q-status]').forEach(b => b.addEventListener('click', async () => {
    b.disabled = true;
    try {
      await api(`/app/api/crm/quotes/${encodeURIComponent(id)}`, { method: 'PATCH', body: JSON.stringify({ status: b.dataset.qStatus }) });
      closeDrawer();
      await loadModule('quotes');
      render();
      toast(STR.quoteStatusUpdated);
    } catch { b.disabled = false; toast(STR.loadFailed); }
  }));
  $('#q-convert', form)?.addEventListener('click', async e => {
    const dueDate = $('#q-due', form).value;
    if (!dueDate) return toast(STR.invoiceEnterDueDate);
    const btn = e.currentTarget;
    btn.disabled = true;
    try {
      await api(`/app/api/crm/quotes/${encodeURIComponent(id)}/invoice`, { method: 'POST', body: JSON.stringify({ dueDate }) });
      closeDrawer();
      await loadModule('quotes');
      if (hasModule('invoices')) state.invoices = await api('/app/api/crm/invoices').catch(() => state.invoices);
      render();
      toast(STR.quoteConverted);
    } catch (err) {
      btn.disabled = false;
      toast(quoteBilledText(err) || STR.quoteConvertFailed);
    }
  });
  wirePdfButtons(form);
  openDrawer(quote.number, form);
  mountRecordAutomations(form, { type: 'quote', id: quote.id }, here);
}

// Another tab may have billed the quote since this detail opened.
const quoteBilledText = err => ({ already_invoiced: STR.quoteAlreadyInvoiced, already_serviced: STR.quoteAlreadyServiced }[err?.code] || '');

// The quote's lines become one service for its client, invoiced later with their other open work.
function openQuoteServiceForm(quote) {
  const t = CRM.services;
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <p class="hint">${escapeHTML(STR.quoteToServiceIntro({ client: quote.clientName || '' }))}</p>
    <div class="form__grid">
      <div class="form__row"><label class="lbl" for="qs-when">${escapeHTML(t.when)}</label>
        <input class="inp" id="qs-when" type="date" value="${todayKey()}" /></div>
      <div class="form__row form__row--full"><label class="lbl" for="qs-name">${escapeHTML(t.name)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp" id="qs-name" maxlength="160" autocomplete="off" placeholder="${escapeHTML(serviceNameFrom(quote.items || []) || t.namePh)}" /></div>
    </div>
    ${itemsTable(quote.items)}
    <div class="actions"><button class="btn btn--primary" type="submit">${escapeHTML(STR.quoteToServiceSave)}</button></div>`;
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api(`/app/api/crm/quotes/${encodeURIComponent(quote.id)}/service`, {
        method: 'POST',
        body: JSON.stringify({ performedAt: $('#qs-when', form).value || null, name: $('#qs-name', form).value.trim() || null }),
      });
      if (['quotes', 'services'].includes(state.active)) await loadModule(state.active).catch(() => {});
      closeDrawer();
      render();
      toast(STR.quoteToServiceDone);
    } catch (err) {
      btn.disabled = false;
      toast(quoteBilledText(err) || STR.quoteToServiceFailed);
    }
  });
  openDrawer(STR.quoteToService, form);
}
