/* Measurements (/app → Measurements): progress billing (autos de medição). Each measurement records how much
   of every contract line and approved addition is done to date; this period bills the difference with the
   previous one, less the retention, and an approved measurement becomes an invoice in one click. */

const MEASUREMENT_FILTERS = {
  draft: m => m.status === 'DRAFT',
  approved: m => m.status === 'APPROVED',
  invoiced: m => m.status === 'INVOICED',
  all: () => true,
};

function measurementRowHtml(m) {
  return `<tr class="conversation-row${m.status === 'INVOICED' ? ' is-paid' : m.status === 'DRAFT' ? ' is-draft' : ''}" data-measurement="${escapeHTML(m.id)}" tabindex="0">
    <td class="name long"><div class="tbl__title"><span>${escapeHTML(m.projectName)}</span><span class="tbl__aside">${measurementStatusPill(m.status)}</span></div><div class="sub">${escapeHTML([m.projectNumber, bt('measurementN', { n: m.number })].join(' · '))}</div></td>
    <td class="mono nowrap"${cellLabel(BLD.thPeriodEnd)}>${escapeHTML(fmtDay(m.periodEnd))}</td>
    <td class="num nowrap"${cellLabel(BLD.thThisPeriod)}>${fmtEUR(m.grossEur)}</td>
    <td class="num nowrap"${cellLabel(BLD.thRetention)}>${fmtEUR(m.retentionEur)}</td>
    <td class="num nowrap"${cellLabel(BLD.thNet)}>${fmtEUR(m.netEur)}</td>
    <td${cellLabel(BLD.thToDate)}>${progressCellHtml(m.pctToDate, null, m.status === 'INVOICED' ? 'ok' : 'accent')}</td>
    <td class="tbl__stack-hide"${cellLabel(BLD.thStatus)}>${measurementStatusPill(m.status)}</td>
  </tr>`;
}

function renderMeasurements(root) {
  const filter = MEASUREMENT_FILTERS[state.measurementsFilter] ? state.measurementsFilter : 'all';
  const all = state.measurements.filter(m => !state.measurementsProject || m.projectId === state.measurementsProject);
  const q = foldText(state.search);
  const shown = all.filter(MEASUREMENT_FILTERS[filter]).filter(m => !q || foldText(`${m.projectNumber} ${m.projectName} ${m.clientName || ''} ${m.number}`).includes(q))
    .sort((a, b) => String(b.periodEnd).localeCompare(String(a.periodEnd)) || b.number - a.number);
  const month = currentPeriodKey('month');
  const stats = [
    { label: BLD.statToInvoice, value: fmtEUR(all.filter(MEASUREMENT_FILTERS.approved).reduce((n, m) => n + Number(m.netEur || 0), 0)) },
    { label: BLD.measurementsStatDrafts, value: all.filter(MEASUREMENT_FILTERS.draft).length },
    { label: BLD.measurementsStatMonth, value: fmtEUR(all.filter(m => m.status === 'INVOICED' && String(m.invoicedAt || '').slice(0, 7) === month).reduce((n, m) => n + Number(m.netEur || 0), 0)) },
    { label: BLD.retainedToDate, value: fmtEUR(all.filter(m => m.status !== 'DRAFT').reduce((n, m) => n + Number(m.retentionEur || 0), 0)) },
  ];
  const chips = Object.keys(MEASUREMENT_FILTERS).map(f => {
    const n = all.filter(MEASUREMENT_FILTERS[f]).length;
    return `<button class="chip${filter === f ? ' is-on' : ''}" type="button" data-measurements-filter="${f}">${escapeHTML(bt(`measurementsFilter.${f}`))}${n && f !== 'all' ? `<span class="chip__count">${n}</span>` : ''}</button>`;
  }).join('') + projectFilterHtml(state.measurementsProject, 'data-measurements-project');
  root.innerHTML = hero(labels.measurements, BLD.measurementsDesc, statCards(stats)) + bldPanel({
    title: BLD.measurementsTitle,
    tag: shown.length,
    tools: chips,
    head: `<tr><th>${escapeHTML(BLD.thMeasurement)}</th><th>${escapeHTML(BLD.thPeriodEnd)}</th><th class="right">${escapeHTML(BLD.thThisPeriod)}</th><th class="right">${escapeHTML(BLD.thRetention)}</th><th class="right">${escapeHTML(BLD.thNet)}</th><th>${escapeHTML(BLD.thToDate)}</th><th>${escapeHTML(BLD.thStatus)}</th></tr>`,
    rows: shown.map(measurementRowHtml).join(''),
    empty: state.measurements.length ? BLD.measurementsEmptyFilter : BLD.measurementsEmpty,
    emptyDesc: state.measurements.length ? '' : BLD.measurementsEmptyDesc,
  });
  $$('[data-measurements-filter]', root).forEach(b => b.addEventListener('click', () => { state.measurementsFilter = b.dataset.measurementsFilter; render(); }));
  $('[data-measurements-project]', root)?.addEventListener('change', e => { state.measurementsProject = e.target.value; render(); });
  $$('[data-measurement]', root).forEach(row => {
    row.addEventListener('click', () => openMeasurementDetail(row.dataset.measurement));
    row.addEventListener('keydown', e => { if (e.key === 'Enter') openMeasurementDetail(row.dataset.measurement); });
  });
}

// A new measurement of [projectId] starts from what the previous one measured; while one is still a draft, that one opens.
async function startMeasurement(projectId, back = null, periodEnd = '') {
  if (!projectId) return openMeasurementCreateForm();
  try {
    const m = await api('/app/api/building/measurements', { method: 'POST', body: JSON.stringify({ projectId, periodEnd: periodEnd || null }) });
    toast(bt('measurementStarted', { n: m.number }));
    refreshBuildingPage();
    openFrom(back, () => openMeasurementDetail(m.id));
  } catch (err) {
    if (err?.code === 'measurement_open' && err.body?.id) {
      toast(bt('errors.measurement_open'));
      return openFrom(back, () => openMeasurementDetail(err.body.id));
    }
    toast(buildingErrorText(err, BLD.saveFailed));
  }
}

async function openMeasurementCreateForm() {
  await ensureBuildingProjects().catch(() => {});
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <p class="hint">${escapeHTML(BLD.measurementIntro)}</p>
    <div class="form__grid">
      ${projectSelectHtml('ms-project', state.measurementsProject || '')}
      <div class="form__row"><label class="lbl" for="ms-end">${escapeHTML(BLD.thPeriodEnd)}</label><input class="inp inp--mono" id="ms-end" type="date" value="${todayKey()}" /></div>
    </div>
    <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${escapeHTML(BLD.cancel)}</button><button class="btn btn--accent" type="submit">${escapeHTML(BLD.measurementCreate)}</button></div>`;
  bindDrawerClose(form);
  form.addEventListener('submit', e => {
    e.preventDefault();
    const projectId = $('#ms-project', form).value;
    if (!projectId) return toast(BLD.chooseProject);
    startMeasurement(projectId, null, $('#ms-end', form).value);
  });
  openDrawer(BLD.measurementNew, form, false, { eyebrow: labels.measurements });
}

// What a line is worth at [qty], in cents, as the server counts it: the whole total once fully done.
function measuredCents(line, qty) {
  const total = Math.round(line.totalEur * 100);
  if (qty >= line.quantity - 1e-9) return total;
  const value = Math.round(qty * Math.round(line.unitPriceEur * 100));
  return Math.max(Math.min(0, total), Math.min(Math.max(0, total), value));
}

function measurementLinesHtml(m, editable) {
  const rows = m.lines.map(l => {
    const prevPct = l.quantity ? (l.previousQty / l.quantity) * 100 : 0;
    const toDate = editable
      ? `<td class="tbl__cell-input"${cellLabel(BLD.thToDate)}><input class="inp inp--mono inp--right tbl__input" type="number" min="${l.previousQty}" max="${l.quantity}" step="any" data-ml-qty="${escapeHTML(l.key)}" aria-label="${escapeHTML(bt('measureQtyAria', { line: l.description }))}" value="${l.toDateQty}" /><input class="inp inp--mono inp--right tbl__input tbl__input--pct" type="number" min="0" max="100" step="any" data-ml-pct="${escapeHTML(l.key)}" aria-label="${escapeHTML(bt('measurePctAria', { line: l.description }))}" value="${Math.round(l.pctToDate * 10) / 10}" /></td>`
      : `<td class="num nowrap"${cellLabel(BLD.thToDate)}>${escapeHTML(fmtQty(l.toDateQty))}<div class="sub">${escapeHTML(`${fmtPct(l.pctToDate)}%`)}</div></td>`;
    return `<tr data-ml-row="${escapeHTML(l.key)}">
      <td class="name long">${escapeHTML(l.description)}<div class="sub">${escapeHTML(`${fmtQty(l.quantity)}${l.unit ? ` ${l.unit}` : ''} × ${fmtEUR(l.unitPriceEur)} = ${fmtEUR(l.totalEur)}`)}</div></td>
      <td class="num nowrap"${cellLabel(BLD.thPrevious)}>${escapeHTML(fmtQty(l.previousQty))}<div class="sub">${escapeHTML(`${fmtPct(prevPct)}%`)}</div></td>
      ${toDate}
      <td class="num nowrap"${cellLabel(BLD.thThisPeriod)} data-ml-period="${escapeHTML(l.key)}">${fmtEUR(l.periodEur)}</td>
    </tr>`;
  }).join('');
  return `<div class="panel"><div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr><th>${escapeHTML(BLD.thLine)}</th><th class="right">${escapeHTML(BLD.thPrevious)}</th><th class="right">${escapeHTML(BLD.thToDate)}</th><th class="right">${escapeHTML(BLD.thThisPeriod)}</th></tr></thead><tbody>${rows}</tbody></table></div></div>`;
}

function measurementTotalsHtml(t, retentionPct) {
  return dashFacts([
    [BLD.thContract, fmtEUR(t.contract)],
    [BLD.measuredBefore, fmtEUR(t.previous)],
    [BLD.measuredToDate, fmtEUR(t.toDate)],
    [BLD.thThisPeriod, fmtEUR(t.gross)],
    retentionPct ? [bt('retentionPct', { pct: fmtPct(retentionPct) }), `− ${fmtEUR(t.retention)}`] : null,
    [BLD.measurementNet, fmtEUR(t.net)],
  ]);
}

async function openMeasurementDetail(id) {
  let m;
  try { m = await api(`/app/api/building/measurements/${encodeURIComponent(id)}`); } catch { return toast(STR.loadFailed); }
  const here = { key: `measurement:${m.id}`, label: bt('measurementN', { n: m.number }), open: () => openMeasurementDetail(m.id) };
  const editable = m.status === 'DRAFT';
  const body = document.createElement('div');
  body.className = 'form';
  const totals = () => ({ contract: m.contractEur, previous: m.previousEur, toDate: m.toDateEur, gross: m.grossEur, retention: m.retentionEur, net: m.netEur });
  const projectLink = !linksBackTo(`project:${m.projectId}`);
  const canInvoice = m.status === 'APPROVED' && hasModule('invoices');
  const due = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10);
  body.innerHTML = `
    <div class="detail__head">
      ${projectLink ? `<button class="detail__client detail__client--link" type="button" data-ms-project>${escapeHTML(m.projectName)}</button>` : `<div class="detail__client">${escapeHTML(m.projectName)}</div>`}
      <div class="detail__amount">${measurementStatusPill(m.status)}<span class="detail__figure" data-ms-net>${fmtEUR(m.netEur)}</span></div>
    </div>
    ${detailMeta([
      { label: BLD.thPeriodEnd, value: fmtDay(m.periodEnd) },
      { label: BLD.thRetention, value: `${fmtPct(m.retentionPct)}%` },
      m.clientName ? { label: STR.lineClient, value: m.clientName } : null,
      m.approvedAt ? { label: BLD.measurementApprovedBy, value: `${fmtDay(m.approvedAt)} · ${m.approvedBy || ''}` } : null,
      m.invoiceNumber ? { label: STR.detailInvoice, value: `${m.invoiceNumber} · ${invoiceStatusLabel(m.invoiceStatus)}` } : null,
    ])}
    ${editable ? `<div class="form__row"><label class="lbl" for="ms-period">${escapeHTML(BLD.thPeriodEnd)}</label><input class="inp inp--mono" id="ms-period" type="date" value="${escapeHTML(m.periodEnd)}" /></div>
      <p class="hint">${escapeHTML(BLD.measurementEditHint)}</p>` : ''}
    ${measurementLinesHtml(m, editable)}
    <div data-ms-totals>${measurementTotalsHtml(totals(), m.retentionPct)}</div>
    <p class="hint hint--bad" data-ms-problem role="alert" hidden></p>
    ${editable ? `<div class="form__row"><label class="lbl" for="ms-notes">${escapeHTML(BLD.measurementNotes)} <span class="opt">${escapeHTML(STR.optional)}</span></label><textarea class="txt" id="ms-notes" maxlength="4000">${escapeHTML(m.notes || '')}</textarea></div>` : (m.notes ? `<p class="panel__text">${escapeHTML(m.notes)}</p>` : '')}
    ${canInvoice ? `<div class="form__row"><label class="lbl" for="ms-due">${escapeHTML(STR.quoteConvertDue)}</label><input class="inp inp--mono" id="ms-due" type="date" value="${due}" /></div>` : ''}
    ${m.status === 'APPROVED' && !m.clientId ? `<p class="hint hint--warn">${escapeHTML(BLD.measurementNoClient)}</p>` : ''}
    <div class="detail__foot">
      ${editable ? `<button class="btn btn--sm btn--accent" type="button" data-ms-save>${escapeHTML(BLD.save)}</button><button class="btn btn--sm btn--primary" type="button" data-ms-approve>${escapeHTML(BLD.measurementApprove)}</button>` : ''}
      ${canInvoice && m.clientId ? `<button class="btn btn--sm btn--accent" type="button" data-ms-invoice>${escapeHTML(bt('measurementInvoice', { amount: fmtEUR(m.netEur) }))}</button>` : ''}
      ${m.status === 'INVOICED' && m.invoiceId && hasModule('invoices') && !linksBackTo(`invoice:${m.invoiceId}`) ? `<button class="btn btn--sm" type="button" data-ms-open-invoice>${escapeHTML(STR.detailOpenDoc({ number: m.invoiceNumber || '' }))}</button>` : ''}
      ${m.status === 'APPROVED' && m.latest ? `<button class="btn btn--sm btn--ghost" type="button" data-ms-reopen>${escapeHTML(BLD.measurementReopen)}</button>` : ''}
      ${m.status !== 'INVOICED' && m.latest ? `<button class="btn btn--sm btn--ghost" type="button" data-ms-delete>${escapeHTML(BLD.delete)}</button>` : ''}
    </div>`;
  const problem = $('[data-ms-problem]', body);
  // The quantities typed, the problems with them, and the totals they make, drawn as they change.
  const typed = () => m.lines.map(l => ({ line: l, qty: Number($(`[data-ml-qty="${CSS.escape(l.key)}"]`, body)?.value ?? l.toDateQty) }));
  const recompute = () => {
    let bad = null;
    let gross = 0;
    let toDate = 0;
    typed().forEach(({ line, qty }) => {
      const input = $(`[data-ml-qty="${CSS.escape(line.key)}"]`, body);
      const wrong = !Number.isFinite(qty) || qty < line.previousQty - 1e-9 || qty > line.quantity + 1e-9;
      input?.setAttribute('aria-invalid', String(wrong));
      if (wrong && !bad) bad = qty > line.quantity ? bt('errors.above_contract_line', { line: line.description }) : bt('errors.below_previous_line', { line: line.description });
      const now = wrong ? measuredCents(line, line.previousQty) : measuredCents(line, qty);
      const before = measuredCents(line, line.previousQty);
      toDate += now;
      gross += now - before;
      const cell = $(`[data-ml-period="${CSS.escape(line.key)}"]`, body);
      if (cell) cell.textContent = fmtEUR((now - before) / 100);
    });
    const retention = gross > 0 && m.retentionPct > 0 ? Math.round(gross * m.retentionPct / 100) : 0;
    $('[data-ms-totals]', body).innerHTML = measurementTotalsHtml({ contract: m.contractEur, previous: m.previousEur, toDate: toDate / 100, gross: gross / 100, retention: retention / 100, net: (gross - retention) / 100 }, m.retentionPct);
    $('[data-ms-net]', body).textContent = fmtEUR((gross - retention) / 100);
    problem.hidden = !bad;
    problem.textContent = bad || '';
    return !bad;
  };
  if (editable) {
    m.lines.forEach(l => {
      const qty = $(`[data-ml-qty="${CSS.escape(l.key)}"]`, body);
      const pct = $(`[data-ml-pct="${CSS.escape(l.key)}"]`, body);
      qty.addEventListener('input', () => { pct.value = l.quantity ? Math.round((Number(qty.value) / l.quantity) * 1000) / 10 : 0; recompute(); });
      pct.addEventListener('input', () => { qty.value = Math.round((Number(pct.value) / 100) * l.quantity * 1000) / 1000; recompute(); });
    });
  }
  const save = async () => {
    if (!recompute()) return null;
    const lines = typed().map(({ line, qty }) => ({ key: line.key, toDateQty: qty }));
    try {
      return await api(`/app/api/building/measurements/${encodeURIComponent(m.id)}`, { method: 'PATCH', body: JSON.stringify({ periodEnd: $('#ms-period', body).value, notes: $('#ms-notes', body).value, lines }) });
    } catch (err) {
      const line = m.lines.find(l => l.key === err?.body?.key);
      toast(line && ['above_contract', 'below_previous'].includes(err.code) ? bt(`errors.${err.code}_line`, { line: line.description }) : buildingErrorText(err, BLD.saveFailed));
      return null;
    }
  };
  const after = (text, next) => { toast(text); refreshBuildingPage(); openMeasurementDetail(next.id); };
  $('[data-ms-project]', body)?.addEventListener('click', async () => {
    await ensureBuildingProjects().catch(() => {});
    openFrom(here, () => openProjectDrawer(m.projectId, 'measurements'));
  });
  $('[data-ms-save]', body)?.addEventListener('click', async e => {
    const btn = e.currentTarget;
    btn.disabled = true;
    const saved = await save();
    if (saved) after(BLD.measurementSaved, saved);
    else btn.disabled = false;
  });
  $('[data-ms-approve]', body)?.addEventListener('click', async e => {
    const btn = e.currentTarget;
    btn.disabled = true;
    const saved = await save();
    if (!saved) { btn.disabled = false; return; }
    try {
      after(BLD.measurementApproved, await api(`/app/api/building/measurements/${encodeURIComponent(m.id)}/approve`, { method: 'POST' }));
    } catch (err) { btn.disabled = false; toast(buildingErrorText(err, BLD.saveFailed)); }
  });
  $('[data-ms-reopen]', body)?.addEventListener('click', async () => {
    try { after(BLD.measurementReopened, await api(`/app/api/building/measurements/${encodeURIComponent(m.id)}/reopen`, { method: 'POST' })); } catch (err) { toast(buildingErrorText(err, BLD.saveFailed)); }
  });
  $('[data-ms-invoice]', body)?.addEventListener('click', async e => {
    const dueDate = $('#ms-due', body).value;
    if (!dueDate) return toast(STR.invoiceEnterDueDate);
    const btn = e.currentTarget;
    btn.disabled = true;
    try {
      const done = await api(`/app/api/building/measurements/${encodeURIComponent(m.id)}/invoice`, { method: 'POST', body: JSON.stringify({ dueDate }) });
      if (hasModule('invoices')) state.fetched.invoices = false;
      after(bt('measurementInvoiced', { number: done.invoiceNumber }), done.measurement);
    } catch (err) { btn.disabled = false; toast(buildingErrorText(err, BLD.saveFailed)); }
  });
  $('[data-ms-open-invoice]', body)?.addEventListener('click', () => openFrom(here, () => openInvoiceDetail(m.invoiceId)));
  $('[data-ms-delete]', body)?.addEventListener('click', async () => {
    if (!await confirmDialog({ title: bt('measurementDeleteTitle', { n: m.number }), body: BLD.measurementDeleteBody, okLabel: BLD.delete })) return;
    try {
      await api(`/app/api/building/measurements/${encodeURIComponent(m.id)}`, { method: 'DELETE' });
      toast(BLD.measurementDeleted);
      closeDrawer();
      refreshBuildingPage();
    } catch (err) { toast(buildingErrorText(err, BLD.saveFailed)); }
  });
  openDrawer(bt('measurementTitle', { n: m.number, project: m.projectNumber }), body, true, { eyebrow: labels.measurements });
}
