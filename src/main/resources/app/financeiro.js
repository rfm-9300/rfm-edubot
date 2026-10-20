/* Financeiro (/app → Financeiro): money in from invoices and out through payments, as a list or by week or
   month. Not a module of its own: the nav shows it whenever Invoices or Payments is on. */

function ledgerColumns() {
  const t = CRM.financeiro;
  return [
    { id: 'when', label: t.thWhen, cell: m => `<td class="mono muted">${escapeHTML(fmtDay(m.date))} <span class="pill ${m.kind === 'in' ? 'pill--ok' : 'pill--bad'}">${escapeHTML(m.kind === 'in' ? t.received : t.spent)}</span></td>` },
    { id: 'who', label: t.thWho, cell: m => `<td class="name">${escapeHTML(m.who)}${m.whoFor ? ` <span class="muted">· ${escapeHTML(m.whoFor)}</span>` : ''}</td>` },
    { id: 'ref', label: t.thRef, cell: m => `<td class="mono muted">${escapeHTML(m.ref)}</td>` },
    { id: 'total', label: t.thTotal, head: 'right', cell: m => `<td class="num">${m.kind === 'out' ? '−' : ''}${fmtEUR(m.amount)}</td>` },
  ];
}

function renderFinanceiro(root) {
  const t = CRM.financeiro;
  const period = state.filterFinanceiroPeriod || '';
  const typeFilter = state.filterFinanceiroType || '';
  // Money in: each invoice paid in one go, and each installment received on an invoice paid in parts.
  const everyReceipt = hasModule('invoices')
    ? state.invoices.filter(i => i.status !== 'CANCELLED').flatMap(i => invoiceReceipts(i).map(r => ({ ...r, inv: i, clientId: i.clientId, clientName: i.clientName })))
    : [];
  const everyPaidPayment = hasModule('payments') ? state.payments.filter(p => p.status === 'PAID' && p.paidAt) : [];
  // A payment counts for a client only when it is linked to that client.
  const clientNames = new Map();
  for (const m of [...everyReceipt, ...everyPaidPayment]) {
    if (m.clientId && !clientNames.has(m.clientId)) clientNames.set(m.clientId, m.clientName || m.clientId);
  }
  if (state.filterFinanceiroClient && !clientNames.has(state.filterFinanceiroClient)) state.filterFinanceiroClient = '';
  const clientId = state.filterFinanceiroClient || '';
  const receipts = clientId ? everyReceipt.filter(r => r.clientId === clientId) : everyReceipt;
  const paidPayments = clientId ? everyPaidPayment.filter(p => p.clientId === clientId) : everyPaidPayment;
  const receivedAll = sumEur(receipts);
  const spentAll = sumEur(paidPayments);

  const ledgerAll = [
    ...receipts.map(r => ({
      kind: 'in', date: r.at, amount: r.totalEur, who: r.inv.clientName || '—', open: `invoice:${r.inv.id}`,
      ref: r.of ? STR.installmentRef({ number: r.inv.number, n: r.part, of: r.of }) : r.inv.number,
    })),
    ...paidPayments.map(p => ({
      kind: 'out', date: p.paidAt, amount: p.totalEur, who: payeeName(p) || '—', whoFor: clientId ? '' : p.clientName || '', ref: p.number, open: `payment:${p.id}`,
    })),
  ].sort((a, b) => String(b.date || '').localeCompare(String(a.date || '')));
  const ledger = ledgerAll.filter(m => !typeFilter || m.kind === typeFilter);

  const inGroups = period ? groupByPeriod(receipts, period, r => r.at) : [];
  const outGroups = period ? groupByPeriod(paidPayments, period, p => p.paidAt) : [];
  const periodKeys = [...new Set([...inGroups.map(([k]) => k), ...outGroups.map(([k]) => k)])].sort((a, b) => b.localeCompare(a));
  const rollupFor = key => {
    const received = sumEur(groupItems(inGroups, key));
    const spent = sumEur(groupItems(outGroups, key));
    return { received, spent, net: received - spent };
  };
  const nowKey = period ? (state.filterFinanceiroPeriodKey || currentPeriodKey(period)) : '';
  const nowRoll = period ? rollupFor(nowKey) : null;
  const prevKey = period ? shiftPeriodKey(nowKey, period, -1) : '';
  const delta = period && periodKeys.includes(prevKey) ? periodDelta(nowRoll.net, rollupFor(prevKey).net) : { text: '', trend: '' };

  const views = periodChips(period, 'data-fin-period', { '': t.viewList, week: t.byWeek, month: t.byMonth });
  const clientOptions = [...clientNames.entries()].sort((a, b) => String(a[1]).localeCompare(String(b[1]), uiLocale()));
  const clientFilter = hasModule('clients') && clientOptions.length
    ? `<select class="sel" data-filter-fin-client aria-label="${escapeHTML(t.filterClientAria)}"><option value="">${escapeHTML(t.filterClientAll)}</option>${clientOptions.map(([id, name]) => `<option value="${escapeHTML(id)}" ${id === clientId ? 'selected' : ''}>${escapeHTML(name)}</option>`).join('')}</select>`
    : '';
  const tools = clientFilter + (period ? '' : `<button class="chip ${!typeFilter ? 'is-on' : ''}" data-filter-fin="">${escapeHTML(t.filterAll)}</button>`
    + `<button class="chip ${typeFilter === 'in' ? 'is-on' : ''}" data-filter-fin="in">${escapeHTML(t.received)}</button>`
    + `<button class="chip ${typeFilter === 'out' ? 'is-on' : ''}" data-filter-fin="out">${escapeHTML(t.spent)}</button>`
    + columnOrderButton('financeiro'));

  const financeNav = periodNav(nowKey, period, 'data-fin-period-nav', t);
  const financeStats = period
    ? [
      { label: t.received, value: fmtEUR(nowRoll.received) },
      { label: t.spent, value: fmtEUR(nowRoll.spent) },
      { label: t.net, value: fmtEUR(nowRoll.net), hint: delta.text ? t.vsPrev({ delta: delta.text }) : '', trend: delta.trend },
    ]
    : [
      { label: t.received, value: fmtEUR(receivedAll) },
      { label: t.spent, value: fmtEUR(spentAll) },
      { label: t.net, value: fmtEUR(receivedAll - spentAll) },
    ];

  const summaryHead = `<tr><th>${escapeHTML(t.thPeriod)}</th><th class="right">${escapeHTML(t.received)}</th><th class="right">${escapeHTML(t.spent)}</th><th class="right">${escapeHTML(t.net)}</th></tr>`;
  const columns = orderedColumns('financeiro', ledgerColumns());
  const listHead = columnsHead(columns, 'financeiro');
  const summaryRows = period && periodKeys.length
    ? `<tr class="is-total"><td class="name">${escapeHTML(t.thTotal)}</td><td class="num">${fmtEUR(receivedAll)}</td><td class="num">${fmtEUR(spentAll)}</td><td class="num">${fmtEUR(receivedAll - spentAll)}</td></tr>`
      + periodKeys.map(key => {
        const roll = rollupFor(key);
        return `<tr class="${key === nowKey ? 'is-current' : ''}"><td class="name">${escapeHTML(periodLabel(key, period))}</td><td class="num">${fmtEUR(roll.received)}</td><td class="num">${fmtEUR(roll.spent)}</td><td class="num">${fmtEUR(roll.net)}</td></tr>`;
      }).join('')
    : '';
  const ledgerRows = ledger.map(m => `<tr class="conversation-row" data-fin-open="${escapeHTML(m.open)}">${columnCells(columns, m)}</tr>`).join('');

  root.innerHTML = hero(labels.financeiro, CRM.tabs.financeiro.desc, statCards(financeStats), financeNav) + crmPanel({
    title: period === 'week' ? t.summaryWeek : period === 'month' ? t.summaryMonth : t.ledger,
    tag: period ? periodKeys.length : ledger.length,
    views,
    tools,
    head: period ? summaryHead : listHead,
    rows: period ? summaryRows : ledgerRows,
    empty: clientId ? t.emptyClient : period ? t.summaryEmpty : t.emptyTitle,
    emptyDesc: clientId ? t.emptyClientDesc : period ? t.summaryEmptyDesc : t.emptyDesc,
  });
  wireColumnOrder(root, 'financeiro', ledgerColumns());
  $('[data-filter-fin-client]', root)?.addEventListener('change', e => { state.filterFinanceiroClient = e.target.value; render(); });
  $$('[data-fin-period]', root).forEach(btn => btn.addEventListener('click', () => { state.filterFinanceiroPeriod = btn.dataset.finPeriod; state.filterFinanceiroPeriodKey = ''; render(); }));
  $$('[data-fin-period-nav]', root).forEach(btn => btn.addEventListener('click', () => {
    const dir = btn.dataset.finPeriodNav;
    state.filterFinanceiroPeriodKey = dir === 'current' ? '' : shiftPeriodKey(nowKey, period, dir === 'prev' ? -1 : 1);
    render();
  }));
  $$('[data-filter-fin]', root).forEach(btn => btn.addEventListener('click', () => { state.filterFinanceiroType = btn.dataset.filterFin; render(); }));
  $$('[data-fin-open]', root).forEach(tr => tr.addEventListener('click', () => {
    const [kind, id] = tr.dataset.finOpen.split(':');
    if (kind === 'invoice') openInvoiceDetail(id);
    else openPaymentDetail(id);
  }));
}
