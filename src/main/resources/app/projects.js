/* Projects (/app → Projects): the company's projects as a list or a timeline, a project's record (schedule,
   diary, photos, issues, changes, measurements and contract), its form, the phases editor, progress updates
   and the link the client follows the project from. */

const PROJECT_FILTERS = {
  active: p => p.status === 'ACTIVE' || p.status === 'ON_HOLD',
  planning: p => p.status === 'PLANNING',
  done: p => p.status === 'COMPLETED' || p.status === 'CANCELLED',
  all: () => true,
};

// While it runs a project shows its health; otherwise its status.
const projectStatePill = p => (p.status === 'ACTIVE' ? projectHealthPill(p.health) : projectStatusPill(p.status));

function projectColumns() {
  return [
    {
      id: 'name', label: BLD.thProject,
      cell: p => `<td class="name long"><div class="tbl__title"><span>${escapeHTML(p.name)}</span><span class="tbl__aside">${projectStatePill(p)}</span></div><div class="sub">${escapeHTML([p.number, p.clientName].filter(Boolean).join(' · '))}</div></td>`,
    },
    { id: 'progress', label: BLD.thProgress, cell: p => `<td${cellLabel(BLD.thProgress)}>${progressCellHtml(p.progressPct, p.status === 'ACTIVE' ? p.plannedPct : null, projectTone(p))}</td>` },
    { id: 'schedule', label: BLD.thSchedule, cell: p => `<td class="nowrap"${cellLabel(BLD.thSchedule)}>${p.status === 'ACTIVE' ? scheduleHtml(p.scheduleDays) : '<span class="muted">—</span>'}</td>` },
    {
      id: 'end', label: BLD.thEnd,
      cell: p => {
        const end = p.actualEnd || p.revisedEnd;
        const when = !p.actualEnd && end && p.status !== 'CANCELLED' ? `<div class="sub">${escapeHTML(relDay(end))}</div>` : '';
        return `<td class="mono nowrap"${cellLabel(BLD.thEnd)}>${escapeHTML(end ? fmtDay(end) : '—')}${when}</td>`;
      },
    },
    { id: 'contract', label: BLD.thContract, head: 'right', cell: p => `<td class="num nowrap"${cellLabel(BLD.thContract)}>${fmtEUR(p.currentContractEur)}</td>` },
    { id: 'status', label: BLD.thStatus, cell: p => `<td class="tbl__stack-hide"${cellLabel(BLD.thStatus)}>${projectStatePill(p)}</td>` },
  ];
}

function renderProjects(root) {
  const all = state.projects;
  const filter = PROJECT_FILTERS[state.projectsFilter] ? state.projectsFilter : 'active';
  const q = foldText(state.search);
  const shown = all.filter(PROJECT_FILTERS[filter])
    .filter(p => !q || foldText(`${p.number} ${p.name} ${p.clientName || ''} ${p.address || ''} ${p.managerName || ''}`).includes(q));
  const active = all.filter(p => p.status === 'ACTIVE');
  const behind = active.filter(p => p.health === 'AT_RISK' || p.health === 'DELAYED');
  const stats = [
    { label: BLD.statActive, value: active.length },
    { label: BLD.statBehind, value: behind.length },
    hasModule('site-issues') ? { label: BLD.statOpenIssues, value: all.reduce((n, p) => n + (p.openIssues || 0), 0) } : null,
    hasModule('measurements')
      ? { label: BLD.statToInvoice, value: fmtEUR(all.reduce((n, p) => n + Number(p.toInvoiceEur || 0), 0)) }
      : { label: BLD.statContracts, value: fmtEUR(active.reduce((n, p) => n + Number(p.currentContractEur || 0), 0)) },
  ].filter(Boolean);
  const view = state.projectsView === 'timeline' ? 'timeline' : 'list';
  const views = ['list', 'timeline'].map(v => `<button class="btn btn--sm${view === v ? ' btn--primary' : ''}" type="button" data-projects-view="${v}">${escapeHTML(bt(`view.${v}`))}</button>`).join('');
  const chips = Object.keys(PROJECT_FILTERS).map(f => {
    const n = all.filter(PROJECT_FILTERS[f]).length;
    return `<button class="chip${filter === f ? ' is-on' : ''}" type="button" data-projects-filter="${f}">${escapeHTML(bt(`filter.${f}`))}${n ? `<span class="chip__count">${n}</span>` : ''}</button>`;
  }).join('') + (view === 'list' ? columnOrderButton('projects') : '');
  let panel;
  if (view === 'timeline') {
    const rows = shown.map(p => ({
      label: p.name,
      sub: [p.number, p.clientName].filter(Boolean).join(' · '),
      start: p.actualStart || p.plannedStart,
      end: p.actualEnd || p.revisedEnd,
      pct: p.progressPct,
      tone: projectTone(p),
      open: p.id,
    }));
    const gantt = ganttHtml(rows, BLD.thProject);
    panel = bldPanel({ title: BLD.portfolio, tag: shown.length, views, tools: chips, body: gantt ? `<div class="panel__body">${gantt}</div>` : bldEmptyHtml(BLD.timelineEmpty, BLD.timelineEmptyDesc) });
  } else {
    const columns = orderedColumns('projects', projectColumns());
    panel = bldPanel({
      title: BLD.portfolio,
      tag: shown.length,
      views,
      tools: chips,
      head: columnsHead(columns, 'projects'),
      rows: shown.map(p => `<tr class="conversation-row${p.health === 'DELAYED' ? ' is-overdue' : p.status === 'COMPLETED' ? ' is-paid' : ''}" data-project="${escapeHTML(p.id)}" tabindex="0">${columnCells(columns, p)}</tr>`).join(''),
      empty: all.length ? BLD.emptyFilter : BLD.empty,
      emptyDesc: all.length ? '' : (hasModule('quotes') ? BLD.emptyDescQuote : BLD.emptyDesc),
    });
  }
  root.innerHTML = hero(labels.projects, BLD.desc, statCards(stats)) + panel;
  if (view === 'list') wireColumnOrder(root, 'projects', projectColumns());
  $$('[data-projects-view]', root).forEach(b => b.addEventListener('click', () => { state.projectsView = b.dataset.projectsView; render(); }));
  $$('[data-projects-filter]', root).forEach(b => b.addEventListener('click', () => { state.projectsFilter = b.dataset.projectsFilter; render(); }));
  $$('[data-project]', root).forEach(row => {
    row.addEventListener('click', () => openProjectDrawer(row.dataset.project));
    row.addEventListener('keydown', e => { if (e.key === 'Enter') openProjectDrawer(row.dataset.project); });
  });
  $$('[data-gantt-open]', root).forEach(b => b.addEventListener('click', () => openProjectDrawer(b.dataset.ganttOpen)));
}

// ── A project's record ───────────────────────────────────────────────────────────────────────────

let projectRecord = null;

async function loadProjectRecord(id) {
  const base = '/app/api/building';
  const q = `projectId=${encodeURIComponent(id)}`;
  const [project, diary, photos, issues, changes, measurements] = await Promise.all([
    api(`${base}/projects/${encodeURIComponent(id)}`),
    hasModule('site-diary') ? api(`${base}/diary?${q}`).catch(() => []) : [],
    api(`${base}/projects/${encodeURIComponent(id)}/photos`).catch(() => []),
    hasModule('site-issues') ? api(`${base}/issues?${q}`).catch(() => []) : [],
    hasModule('change-orders') ? api(`${base}/changes?${q}`).catch(() => []) : [],
    hasModule('measurements') ? api(`${base}/measurements?${q}`).catch(() => []) : [],
  ]);
  return { project, diary, photos, issues, changes, measurements };
}

async function openProjectDrawer(id, tab) {
  const known = state.projects.find(p => p.id === id);
  const body = document.createElement('div');
  body.className = 'record';
  body.innerHTML = `<p class="hint">${escapeHTML(CRM.loading)}</p>`;
  const eyebrow = p => [BLD.eyebrow, p?.number].filter(Boolean).join(' · ');
  const gen = openDrawer(known?.name || CRM.loading, body, true, { eyebrow: eyebrow(known) });
  let record;
  try { record = await loadProjectRecord(id); } catch { record = null; }
  if (gen !== drawerGen) return;
  if (!record?.project) { closeDrawer({ dismissed: true }); return toast(STR.loadFailed); }
  const keep = projectRecord?.project?.id === id ? projectRecord.tab : null;
  projectRecord = { ...record, el: body, tab: tab || keep || 'schedule' };
  $('#drawer-title').textContent = record.project.name;
  renderDrawerEyebrow(eyebrow(record.project));
  renderProjectRecord();
}

// Opens [open] from the record, so it comes back here (same tab, fresh data) once it saves.
function openFromProject(open) {
  const r = projectRecord;
  openFrom(r ? projectTrailEntry(r.project, r.tab) : null, open);
}

function projectSinceLine(p) {
  const end = p.actualEnd || p.revisedEnd;
  if (p.status === 'COMPLETED') return bt('record.finished', { date: fmtDay(p.actualEnd || end) });
  if (p.status === 'CANCELLED') return projectStatusLabel(p.status);
  const parts = [];
  if (p.actualStart) parts.push(bt('record.started', { date: fmtDay(p.actualStart) }));
  else if (p.plannedStart) parts.push(bt('record.starts', { date: fmtDay(p.plannedStart) }));
  if (end) parts.push(bt('record.ends', { date: fmtDay(end), when: relDay(end) }));
  if (p.changeDays) parts.push(bt('record.changeDays', { n: p.changeDays }));
  return parts.join(' · ') || bt('record.noDates');
}

function projectKpiCells(r) {
  const p = r.project;
  const cells = [{
    label: BLD.kpiProgress,
    value: `${p.progressPct}%`,
    sub: p.status === 'ACTIVE' && p.plannedPct != null ? bt('planPct', { pct: p.plannedPct }) : projectStatusLabel(p.status),
    tone: { AT_RISK: 'warn', DELAYED: 'bad' }[p.health] || '',
  }];
  if (p.status === 'ACTIVE' && p.scheduleDays != null) {
    cells.push({ label: BLD.kpiSchedule, value: scheduleText(p.scheduleDays), sub: p.revisedEnd ? bt('record.endsShort', { date: fmtDay(p.revisedEnd) }) : '', tone: p.scheduleDays < 0 ? (p.health === 'DELAYED' ? 'bad' : 'warn') : '' });
  }
  cells.push({
    label: BLD.kpiContract,
    value: fmtEUR(p.currentContractEur),
    sub: p.changesEur ? bt('record.withChanges', { amount: fmtEUR(p.changesEur) }) : (p.contractEur ? bt('record.baseContract') : bt('record.noContract')),
  });
  if (hasModule('measurements')) {
    const pct = p.currentContractEur ? Math.round((p.measuredEur / p.currentContractEur) * 100) : 0;
    cells.push({ label: BLD.kpiMeasured, value: fmtEUR(p.measuredEur), sub: p.toInvoiceEur ? bt('record.toInvoice', { amount: fmtEUR(p.toInvoiceEur) }) : bt('record.ofContract', { pct }), tone: p.toInvoiceEur ? 'info' : '' });
  } else if (hasModule('site-issues')) {
    cells.push({ label: BLD.kpiIssues, value: String(p.openIssues), sub: p.overdueIssues ? bt('record.overdueN', { n: p.overdueIssues }) : bt('record.noneOverdue'), tone: p.overdueIssues ? 'bad' : '' });
  }
  return cells;
}

function projectAttention(r) {
  const p = r.project;
  const items = [];
  p.phases.filter(ph => ph.state === 'LATE').forEach(ph => items.push({
    tone: 'bad', title: bt('attn.latePhase', { name: ph.name }), detail: bt('attn.latePhaseDetail', { done: ph.progressPct, plan: ph.plannedPct ?? 100 }), open: `phase:${ph.id}`,
  }));
  r.issues.filter(i => i.overdue).forEach(i => items.push({ tone: 'bad', title: bt('attn.overdueIssue', { title: i.title }), detail: [i.number, i.assigneeName].filter(Boolean).join(' · '), when: relDay(i.dueDate), open: `issue:${i.id}` }));
  r.changes.filter(c => c.status === 'SENT').forEach(c => items.push({ tone: 'warn', title: bt('attn.changeWaiting', { title: c.title }), detail: c.number, amount: c.signedEur, open: `change:${c.id}` }));
  r.measurements.filter(m => m.status === 'APPROVED').forEach(m => items.push({ tone: 'info', title: bt('attn.measurementToInvoice', { n: m.number }), detail: fmtDay(m.periodEnd), amount: m.netEur, open: `measurement:${m.id}` }));
  if (hasModule('site-diary') && p.status === 'ACTIVE') {
    const last = p.lastDiaryDay;
    const quiet = !last || Math.round((dayKeyDate(todayKey()) - dayKeyDate(last)) / 86400000) >= DIARY_QUIET_DAYS;
    if (quiet) items.push({ tone: 'warn', title: bt('attn.noDiary'), detail: last ? bt('attn.lastDiary', { when: relDay(last) }) : bt('attn.noDiaryYet'), open: 'act:diary' });
  }
  return items;
}

function projectTabs(r) {
  const tabs = [['schedule', bt('tab.schedule'), r.project.phaseCount]];
  if (hasModule('site-diary')) tabs.push(['diary', labels['site-diary'], r.diary.length]);
  tabs.push(['photos', bt('tab.photos'), r.photos.length]);
  if (hasModule('site-issues')) tabs.push(['issues', labels['site-issues'], r.issues.filter(i => i.status === 'OPEN' || i.status === 'IN_PROGRESS').length]);
  if (hasModule('change-orders')) tabs.push(['changes', labels['change-orders'], r.changes.length]);
  if (hasModule('measurements')) tabs.push(['measurements', labels.measurements, r.measurements.length]);
  tabs.push(['contract', bt('tab.contract'), r.project.contract.length]);
  if (!tabs.some(([id]) => id === r.tab)) r.tab = 'schedule';
  return `<div class="chip-tabs" role="tablist">${tabs.map(([id, label, n]) => `<button class="chip${id === r.tab ? ' is-on' : ''}" type="button" role="tab" aria-selected="${id === r.tab}" data-record-tab="${id}">${escapeHTML(label)}${n ? `<span class="chip__count">${n}</span>` : ''}</button>`).join('')}</div>`;
}

function projectSchedulePane(r) {
  const p = r.project;
  if (!p.phases.length) {
    return `<div class="record__stack"><div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(BLD.phasesEmpty)}</p><p class="empty__desc">${escapeHTML(BLD.phasesEmptyDesc)}</p>
      <div class="empty__actions"><button class="btn btn--sm btn--primary" type="button" data-project-act="phases"><span class="btn__plus">+</span> ${escapeHTML(BLD.phasesAdd)}</button><button class="btn btn--sm" type="button" data-project-act="progress">${escapeHTML(BLD.progressUpdate)}</button></div></div></div></div>`;
  }
  const gantt = ganttHtml(p.phases.map(ph => ({
    label: ph.name,
    sub: `${phaseStateLabel(ph.state)} · ${ph.progressPct}%`,
    start: ph.plannedStart,
    end: ph.plannedEnd,
    pct: ph.progressPct,
    tone: PHASE_TONES[ph.state],
    open: ph.id,
  })), BLD.thPhase);
  const rows = p.phases.map(ph => `<tr class="conversation-row${ph.state === 'DONE' ? ' is-paid' : ph.state === 'LATE' ? ' is-overdue' : ''}" data-phase="${escapeHTML(ph.id)}" tabindex="0">
    <td class="name long">${escapeHTML(ph.name)}<div class="sub mono">${escapeHTML(ph.plannedStart && ph.plannedEnd ? `${fmtDay(ph.plannedStart)} – ${fmtDay(ph.plannedEnd)}` : BLD.noDates)}</div></td>
    <td${cellLabel(BLD.thProgress)}>${progressCellHtml(ph.progressPct, ph.state === 'DONE' ? null : ph.plannedPct, PHASE_TONES[ph.state])}</td>
    <td${cellLabel(BLD.thStatus)}>${phaseStatePill(ph.state)}</td>
  </tr>`).join('');
  return `<div class="record__stack">
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(BLD.scheduleTitle)} <span class="tag">${p.phases.length}</span></h2>
      <div class="panel__tools"><button class="btn btn--sm btn--ghost" type="button" data-project-act="phases">${escapeHTML(BLD.phasesEdit)}</button></div></header>
      ${gantt ? `<div class="panel__body">${gantt}</div>` : ''}
    </section>
    <div class="panel"><div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr><th>${escapeHTML(BLD.thPhase)}</th><th>${escapeHTML(BLD.thProgress)}</th><th>${escapeHTML(BLD.thStatus)}</th></tr></thead><tbody>${rows}</tbody></table></div></div>
    <p class="hint">${escapeHTML(BLD.scheduleHint)}</p>
  </div>`;
}

function projectPaneHtml(r) {
  const p = r.project;
  if (r.tab === 'schedule') return projectSchedulePane(r);
  if (r.tab === 'diary') {
    if (!r.diary.length) return recordEmpty(BLD.diaryEmpty, 'diary', BLD.diaryNew);
    const rows = r.diary.map(e => `<tr class="conversation-row" data-record-open="diary:${escapeHTML(e.id)}">
      <td class="mono nowrap">${escapeHTML(fmtDay(e.day))}</td><td>${escapeHTML(weatherLabel(e.weather) || '—')}</td>
      <td class="num">${e.workers ?? '—'}</td><td class="long">${escapeHTML(e.workDone.length > 90 ? `${e.workDone.slice(0, 89)}…` : e.workDone)}</td>
      <td class="num">${e.photos.length || ''}</td></tr>`).join('');
    return recordTable([[BLD.thDay], [BLD.thWeather], [BLD.thWorkers, 'right'], [BLD.thWorkDone], [BLD.thPhotos, 'right']], rows);
  }
  if (r.tab === 'photos') {
    const add = `<button class="btn btn--sm" type="button" data-project-act="photos"><span class="btn__plus">+</span> ${escapeHTML(BLD.photosAdd)}</button>`;
    if (!r.photos.length) return `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(BLD.photosEmpty)}</p><p class="empty__desc">${escapeHTML(BLD.photosEmptyDesc)}</p><div class="empty__actions">${add}</div></div></div>`;
    return `<div class="record__stack">${photoGridHtml(r.photos)}<div class="record__pane-foot">${add}</div></div>`;
  }
  if (r.tab === 'issues') {
    if (!r.issues.length) return recordEmpty(BLD.issuesEmpty, 'issue', BLD.issueNew);
    const rows = r.issues.map(i => `<tr class="conversation-row${i.overdue ? ' is-overdue' : i.status === 'CLOSED' ? ' is-paid' : ''}" data-record-open="issue:${escapeHTML(i.id)}">
      <td class="name long">${escapeHTML(i.title)}<div class="sub mono">${escapeHTML(i.number)}</div></td><td>${issuePriorityPill(i.priority)}</td>
      <td class="mono nowrap">${escapeHTML(i.dueDate ? fmtDay(i.dueDate) : '—')}</td><td>${issueStatusPill(i.status, i.overdue)}</td></tr>`).join('');
    return recordTable([[BLD.thIssue], [BLD.thPriority], [BLD.thDue], [BLD.thStatus]], rows);
  }
  if (r.tab === 'changes') {
    if (!r.changes.length) return recordEmpty(BLD.changesEmpty, 'change', BLD.changeNew);
    const rows = r.changes.map(c => `<tr class="conversation-row${c.status === 'APPROVED' ? ' is-paid' : c.status === 'REJECTED' ? ' is-draft' : ''}" data-record-open="change:${escapeHTML(c.id)}">
      <td class="name long">${escapeHTML(c.title)}<div class="sub mono">${escapeHTML(c.number)}</div></td><td>${escapeHTML(changeKindLabel(c.kind))}</td>
      <td class="num nowrap">${fmtEUR(c.signedEur)}</td><td class="num">${c.daysImpact || '—'}</td><td>${changeStatusPill(c.status)}</td></tr>`).join('');
    return recordTable([[BLD.thChange], [BLD.thKind], [BLD.thValue, 'right'], [BLD.thDays, 'right'], [BLD.thStatus]], rows);
  }
  if (r.tab === 'measurements') {
    const facts = dashFacts([
      [BLD.kpiContract, fmtEUR(p.currentContractEur)],
      [BLD.measuredToDate, fmtEUR(p.measuredEur)],
      [BLD.retainedToDate, fmtEUR(p.retainedEur)],
      [BLD.statToInvoice, fmtEUR(p.toInvoiceEur)],
    ], true);
    if (!r.measurements.length) return `<div class="record__stack">${facts}${recordEmpty(BLD.measurementsEmpty, 'measurement', BLD.measurementNew)}</div>`;
    const rows = r.measurements.map(m => `<tr class="conversation-row${m.status === 'INVOICED' ? ' is-paid' : m.status === 'DRAFT' ? ' is-draft' : ''}" data-record-open="measurement:${escapeHTML(m.id)}">
      <td class="id">${escapeHTML(bt('measurementN', { n: m.number }))}</td><td class="mono nowrap">${escapeHTML(fmtDay(m.periodEnd))}</td>
      <td class="num nowrap">${fmtEUR(m.grossEur)}</td><td class="num nowrap">${fmtEUR(m.netEur)}</td><td>${measurementStatusPill(m.status)}</td></tr>`).join('');
    return `<div class="record__stack">${facts}${recordTable([[BLD.thMeasurement], [BLD.thPeriodEnd], [BLD.thThisPeriod, 'right'], [BLD.thNet, 'right'], [BLD.thStatus]], rows)}</div>`;
  }
  // Contract
  const locked = p.contractLocked ? `<p class="hint">${escapeHTML(BLD.contractLocked)}</p>` : '';
  const from = p.quoteNumber ? `<p class="hint">${escapeHTML(bt('contractFromQuote', { number: p.quoteNumber }))}</p>` : '';
  if (!p.contract.length) return `<div class="record__stack">${from}${recordEmpty(BLD.contractEmpty, 'edit', BLD.contractAdd)}</div>`;
  const rows = p.contract.map(l => `<tr><td class="name long">${escapeHTML(l.description)}</td><td class="num nowrap">${fmtQty(l.quantity)}${l.unit ? ` ${escapeHTML(l.unit)}` : ''}</td>
    <td class="num nowrap">${fmtEUR(l.unitPriceEur)}</td><td class="num nowrap">${fmtEUR(l.totalEur)}</td>${hasModule('measurements') ? `<td class="num">${l.measuredPct}%</td>` : ''}</tr>`).join('');
  const head = [[BLD.thLine], [STR.lineColQty, 'right'], [STR.lineColPrice, 'right'], [STR.lineTotal, 'right']];
  if (hasModule('measurements')) head.push([BLD.thMeasuredPct, 'right']);
  const totals = dashFacts([
    [BLD.baseContract, fmtEUR(p.contractEur)],
    p.changesEur ? [BLD.approvedChanges, fmtEUR(p.changesEur)] : null,
    [BLD.currentContract, fmtEUR(p.currentContractEur)],
  ]);
  return `<div class="record__stack">${from}${recordTable(head, rows)}${totals}${locked}</div>`;
}

function projectContactButtons(p) {
  const next = {
    PLANNING: [['ACTIVE', BLD.actStart, 'btn--primary']],
    ACTIVE: [['ON_HOLD', BLD.actHold, ''], ['COMPLETED', BLD.actComplete, '']],
    ON_HOLD: [['ACTIVE', BLD.actResume, 'btn--primary']],
    COMPLETED: [['ACTIVE', BLD.actReopen, '']],
    CANCELLED: [['PLANNING', BLD.actReopen, '']],
  }[p.status] || [];
  const status = next.map(([to, label, cls]) => `<button class="btn btn--sm ${cls}" type="button" data-project-status="${to}">${escapeHTML(label)}</button>`).join('');
  return [
    status,
    `<button class="btn btn--sm" type="button" data-project-act="share">${escapeHTML(p.shareEnabled ? BLD.shareOpen : BLD.shareStart)}</button>`,
    p.clientId && hasModule('clients') && !linksBackTo(`client:${p.clientId}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-project-act="client">${escapeHTML(BLD.openClient)}</button>` : '',
    p.quoteId && hasModule('quotes') && !linksBackTo(`quote:${p.quoteId}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-project-act="quote">${escapeHTML(STR.detailOpenDoc({ number: p.quoteNumber || '' }))}</button>` : '',
  ].join('');
}

function renderProjectRecord(focusTab = false) {
  const r = projectRecord;
  const body = r?.el;
  if (!body || !body.isConnected) return;
  const p = r.project;
  const lines = [
    p.clientName ? `<span class="record-card__line">${escapeHTML(p.clientName)}</span>` : '',
    p.address ? `<a class="record-card__line" href="${escapeHTML(mapsHref(p.address))}" target="_blank" rel="noopener">${escapeHTML(p.address)}</a>` : '',
    p.managerName ? `<span class="record-card__line">${escapeHTML(bt('record.manager', { name: p.managerName }))}</span>` : '',
  ].join('') || `<span class="record-card__line mono">${escapeHTML(p.number)}</span>`;
  const acts = [
    hasModule('site-diary') ? ['diary', BLD.diaryNew] : null,
    ['photos', BLD.photosAdd],
    hasModule('site-issues') ? ['issue', BLD.issueNew] : null,
    hasModule('change-orders') ? ['change', BLD.changeNew] : null,
    hasModule('measurements') ? ['measurement', BLD.measurementNew] : null,
  ].filter(Boolean);
  body.innerHTML = `
    ${recordCardHtml({
      name: p.name,
      lines,
      since: projectSinceLine(p),
      contact: projectContactButtons(p),
      notes: p.description || '',
      actions: isCompanyAdmin() ? `<button class="btn btn--sm btn--ghost" type="button" data-project-delete>${escapeHTML(BLD.delete)}</button>` : '',
    })}
    ${recordKpisHtml(projectKpiCells(r))}
    ${recordAttentionHtml(projectAttention(r))}
    ${projectTabs(r)}
    <div class="record__pane" role="tabpanel">${projectPaneHtml(r)}</div>
    ${recordFootHtml(acts)}`;
  $('[data-record-edit]', body)?.addEventListener('click', () => openFromProject(() => openProjectForm(p)));
  $('[data-project-delete]', body)?.addEventListener('click', () => deleteProject(p));
  $$('[data-project-status]', body).forEach(b => b.addEventListener('click', () => setProjectStatus(p, b.dataset.projectStatus, b)));
  $$('[data-record-tab]', body).forEach(b => b.addEventListener('click', () => { r.tab = b.dataset.recordTab; renderProjectRecord(true); }));
  $$('[data-record-open]', body).forEach(el => el.addEventListener('click', () => openProjectItem(el.dataset.recordOpen)));
  $$('[data-record-act], [data-project-act]', body).forEach(b => b.addEventListener('click', () => projectAct(b.dataset.recordAct || b.dataset.projectAct)));
  $$('[data-phase], [data-gantt-open]', body).forEach(el => {
    const open = () => { const phase = p.phases.find(ph => ph.id === (el.dataset.phase || el.dataset.ganttOpen)); if (phase) openFromProject(() => openPhaseProgressForm(p, phase)); };
    el.addEventListener('click', open);
    if (el.tagName === 'TR') el.addEventListener('keydown', e => { if (e.key === 'Enter') open(); });
  });
  if (r.tab === 'photos') wirePhotoGrid(body, r.photos, projectTrailEntry(p, 'photos'), () => {});
  if (focusTab) $('[data-record-tab].is-on', body)?.focus();
}

function openProjectItem(ref) {
  const r = projectRecord;
  if (!r) return;
  const [kind, id] = ref.split(':');
  if (kind === 'act') return projectAct(id);
  if (kind === 'phase') {
    const phase = r.project.phases.find(ph => ph.id === id);
    return phase && openFromProject(() => openPhaseProgressForm(r.project, phase));
  }
  if (kind === 'diary') return openFromProject(() => openDiaryEntry(id));
  if (kind === 'issue') return openFromProject(() => openIssueDetail(id));
  if (kind === 'change') return openFromProject(() => openChangeDetail(id));
  if (kind === 'measurement') return openFromProject(() => openMeasurementDetail(id));
}

async function projectAct(kind) {
  const r = projectRecord;
  if (!r) return;
  const p = r.project;
  if (kind === 'edit') return openFromProject(() => openProjectForm(p));
  if (kind === 'phases') return openFromProject(() => openPhasesEditor(p));
  if (kind === 'progress') return openFromProject(() => openManualProgressForm(p));
  if (kind === 'share') return openFromProject(() => openProjectShare(p));
  if (kind === 'client') return openFromProject(() => openClientDrawer(p.clientId));
  if (kind === 'quote') return openFromProject(() => openQuoteDetail(p.quoteId));
  if (kind === 'diary') {
    const today = r.diary.find(e => e.day === todayKey());
    return openFromProject(() => (today ? openDiaryForm(today) : openDiaryForm(null, { projectId: p.id })));
  }
  if (kind === 'issue') return openFromProject(() => openIssueForm(null, { projectId: p.id }));
  if (kind === 'change') return openFromProject(() => openChangeForm(null, { projectId: p.id }));
  if (kind === 'measurement') return startMeasurement(p.id, projectTrailEntry(p, 'measurements'));
  if (kind === 'photos') {
    await addPhotosTo(p.id, {}, async () => {
      r.tab = 'photos';
      await reloadProjectRecord();
    });
  }
}

// The record drawn again with fresh data, when it is still the one on screen.
async function reloadProjectRecord() {
  const r = projectRecord;
  if (!r?.el?.isConnected) return;
  try {
    const fresh = await loadProjectRecord(r.project.id);
    if (projectRecord === r) {
      Object.assign(r, fresh);
      renderProjectRecord();
    }
  } catch { /* keep what is shown */ }
  refreshBuildingPage();
}

async function setProjectStatus(p, status, btn) {
  if (status === 'COMPLETED' && !await confirmDialog({ title: BLD.completeTitle, body: BLD.completeBody, okLabel: BLD.actComplete, danger: false })) return;
  btn.disabled = true;
  try {
    await api(`/app/api/building/projects/${encodeURIComponent(p.id)}`, { method: 'PATCH', body: JSON.stringify({ status }) });
    toast(bt('statusChanged', { status: projectStatusLabel(status) }));
    await reloadProjectRecord();
  } catch (err) {
    btn.disabled = false;
    toast(buildingErrorText(err, BLD.saveFailed));
  }
}

async function deleteProject(p) {
  if (!await confirmDialog({ title: bt('deleteTitle', { name: p.name }), body: BLD.deleteBody, okLabel: BLD.delete })) return;
  try {
    await api(`/app/api/building/projects/${encodeURIComponent(p.id)}`, { method: 'DELETE' });
    toast(BLD.deleted);
    closeDrawer({ dismissed: true });
    projectRecord = null;
    await ensureBuildingProjects(true).catch(() => {});
    refreshBuildingPage();
  } catch (err) {
    toast(buildingErrorText(err, BLD.saveFailed));
  }
}

// ── The project form ─────────────────────────────────────────────────────────────────────────────

function contractRows(form) {
  return $$('.line', form).map(row => {
    const get = k => $(`[data-k="${k}"]`, row).value;
    return { id: row.dataset.lineId || null, description: get('description').trim(), quantity: Number(get('quantity') || 0), unit: get('unit').trim(), unitPriceEur: Number(get('unitPriceEur') || 0) };
  }).filter(it => it.description && (it.quantity > 0 || it.unitPriceEur > 0));
}

// `opts.quote` starts a project from that quote: its client and its lines as the contract.
async function openProjectForm(project = null, opts = {}) {
  const quote = opts.quote || null;
  const [clients, employees, catalog] = await Promise.all([
    hasModule('clients') ? api('/app/api/crm/clients').catch(() => state.clients) : Promise.resolve([]),
    hasModule('employees') ? api('/app/api/crm/employees').catch(() => state.employees) : Promise.resolve([]),
    hasModule('catalog') ? api('/app/api/crm/standard-items').catch(() => []) : Promise.resolve([]),
  ]);
  const p = project || {};
  const locked = !!p.contractLocked;
  const fromQuote = !!quote;
  const li = lineItemsField(catalog);
  const initial = (project ? p.contract : []).map(l => ({ id: l.id, description: l.description, quantity: l.quantity, unit: l.unit, unitPriceEur: l.unitPriceEur }));
  const statusOptions = PROJECT_STATUSES.map(s => `<option value="${s}"${(p.status || 'PLANNING') === s ? ' selected' : ''}>${escapeHTML(projectStatusLabel(s))}</option>`).join('');
  const clientId = quote?.clientId || p.clientId || opts.clientId || '';
  const clientOptions = [...clients].sort((a, b) => String(a.name).localeCompare(String(b.name), uiLocale(), { sensitivity: 'base' }))
    .map(c => `<option value="${escapeHTML(c.id)}"${c.id === clientId ? ' selected' : ''}>${escapeHTML(c.name)}</option>`).join('');
  const contract = fromQuote
    ? `<p class="hint">${escapeHTML(bt('contractFromQuote', { number: quote.number }))}</p>${itemsTable(quote.items)}`
    : locked ? `<div class="lines-field"><div class="lbl">${escapeHTML(bt('tab.contract'))}</div>${itemsTable(p.contract.map(l => ({ ...l, unitPriceEur: l.unitPriceEur })))}<p class="hint">${escapeHTML(BLD.contractLocked)}</p></div>` : li.html;
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      <div class="form__row form__row--full"><label class="lbl" for="pj-name">${escapeHTML(BLD.formName)} <span class="req">●</span></label>
        <input class="inp" id="pj-name" maxlength="160" required value="${escapeHTML(p.name || '')}" placeholder="${escapeHTML(fromQuote ? serviceNameFrom(quote.items || []) || quote.number : BLD.formNamePh)}" /></div>
      ${hasModule('clients') ? `<div class="form__row"><label class="lbl" for="pj-client">${escapeHTML(STR.lineClient)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <select class="sel" id="pj-client"${fromQuote ? ' disabled' : ''}><option value="">${escapeHTML(BLD.formNoClient)}</option>${clientOptions}</select></div>` : ''}
      <div class="form__row"><label class="lbl" for="pj-status">${escapeHTML(BLD.thStatus)}</label><select class="sel" id="pj-status">${statusOptions}</select></div>
      ${hasModule('employees') ? `<div class="form__row"><label class="lbl" for="pj-manager">${escapeHTML(BLD.formManager)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <select class="sel" id="pj-manager"><option value="">${escapeHTML(BLD.formNoManager)}</option>${employees.map(e => `<option value="${escapeHTML(e.id)}"${e.id === p.managerId ? ' selected' : ''}>${escapeHTML(e.name)}</option>`).join('')}</select></div>` : ''}
      <div class="form__row form__row--full"><label class="lbl" for="pj-address">${escapeHTML(BLD.formAddress)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp" id="pj-address" maxlength="300" value="${escapeHTML(p.address || '')}" /></div>
      <div class="form__row"><label class="lbl" for="pj-start">${escapeHTML(BLD.formPlannedStart)}</label><input class="inp inp--mono" id="pj-start" type="date" value="${escapeHTML(p.projectStart || '')}" /></div>
      <div class="form__row"><label class="lbl" for="pj-end">${escapeHTML(BLD.formPlannedEnd)}</label><input class="inp inp--mono" id="pj-end" type="date" value="${escapeHTML(p.projectEnd || '')}" /></div>
      ${hasModule('measurements') ? `<div class="form__row"><label class="lbl" for="pj-retention">${escapeHTML(BLD.formRetention)}</label>
        <div class="inp-unit"><input class="inp inp--mono inp--right" id="pj-retention" type="number" min="0" max="20" step="0.5" value="${p.retentionPct ?? 0}" /><span class="inp-unit__label">%</span></div><p class="hint">${escapeHTML(BLD.formRetentionHint)}</p></div>` : ''}
      <div class="form__row form__row--full"><label class="lbl" for="pj-desc">${escapeHTML(BLD.formDescription)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <textarea class="txt" id="pj-desc" maxlength="4000">${escapeHTML(p.description || '')}</textarea></div>
    </div>
    ${project && p.phaseCount ? `<p class="hint">${escapeHTML(BLD.formDatesFromPhases)}</p>` : ''}
    ${contract}
    <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${escapeHTML(BLD.cancel)}</button><button class="btn btn--accent" type="submit">${escapeHTML(project ? BLD.save : BLD.create)}</button></div>`;
  if (!fromQuote && !locked) {
    li.wire(form, { initial });
    $$('.line', form).forEach((row, i) => { if (initial[i]?.id) row.dataset.lineId = initial[i].id; });
  }
  bindDrawerClose(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const payload = {
      name: $('#pj-name', form).value.trim(),
      status: $('#pj-status', form).value,
      address: $('#pj-address', form).value.trim(),
      description: $('#pj-desc', form).value.trim(),
    };
    if (!payload.name && !fromQuote) return toast(BLD.formNameRequired);
    if (!payload.name) delete payload.name;
    Object.assign(payload, { plannedStart: $('#pj-start', form).value, plannedEnd: $('#pj-end', form).value });
    if (payload.plannedStart && payload.plannedEnd && payload.plannedEnd < payload.plannedStart) return toast(bt('errors.invalid_dates'));
    if ($('#pj-client', form) && !fromQuote) payload.clientId = $('#pj-client', form).value;
    if ($('#pj-manager', form)) payload.managerId = $('#pj-manager', form).value;
    if ($('#pj-retention', form)) payload.retentionPct = Number($('#pj-retention', form).value || 0);
    if (fromQuote) payload.quoteId = quote.id;
    else if (!locked) payload.contract = contractRows(form);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const saved = await api(project ? `/app/api/building/projects/${encodeURIComponent(project.id)}` : '/app/api/building/projects', {
        method: project ? 'PATCH' : 'POST',
        body: JSON.stringify(payload),
      });
      toast(project ? BLD.saved : BLD.created);
      await ensureBuildingProjects(true).catch(() => {});
      if (project) {
        closeDrawer();
        refreshBuildingPage();
      } else {
        closeDrawer({ dismissed: true });
        refreshBuildingPage();
        openProjectDrawer(saved.id);
      }
    } catch (err) {
      btn.disabled = false;
      toast(buildingErrorText(err, BLD.saveFailed));
    }
  });
  openDrawer(project ? bt('formEditTitle', { name: p.name }) : (fromQuote ? bt('formFromQuoteTitle', { number: quote.number }) : BLD.formNewTitle), form, true, { eyebrow: BLD.eyebrow });
}

// ── Phases ───────────────────────────────────────────────────────────────────────────────────────

function phaseRowHtml(ph = {}) {
  return `<div class="line" data-phase-id="${escapeHTML(ph.id || '')}">
    <input data-k="name" maxlength="120" aria-label="${escapeHTML(BLD.thPhase)}" placeholder="${escapeHTML(BLD.phaseNamePh)}" value="${escapeHTML(ph.name || '')}" />
    <input type="date" class="mono" data-k="start" aria-label="${escapeHTML(BLD.phaseStart)}" value="${escapeHTML(ph.plannedStart || '')}" />
    <input type="date" class="mono" data-k="end" aria-label="${escapeHTML(BLD.phaseEnd)}" value="${escapeHTML(ph.plannedEnd || '')}" />
    <input type="number" class="num" data-k="weight" min="1" max="10000" step="1" aria-label="${escapeHTML(BLD.phaseWeight)}" placeholder="—" value="${ph.weight ?? ''}" />
    <input type="number" class="num" data-k="progress" min="0" max="100" step="5" aria-label="${escapeHTML(BLD.phaseDone)}" value="${ph.progressPct ?? 0}" />
    <button type="button" class="l-rm" aria-label="${escapeHTML(BLD.phaseRemove)}"><svg width="12" height="12" viewBox="0 0 16 16"><path d="M3 3 L13 13 M13 3 L3 13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg></button>
  </div>`;
}

// The typical sequence of a build, spread over the project's planned dates when it has them.
function typicalPhases(p) {
  const names = bt('phaseTemplates');
  const list = Array.isArray(names) ? names : [];
  const start = p.plannedStart;
  const end = p.plannedEnd;
  const days = start && end ? Math.round((dayKeyDate(end) - dayKeyDate(start)) / 86400000) + 1 : 0;
  return list.map((name, i) => {
    if (!days || days < list.length) return { name };
    const from = Math.floor((days * i) / list.length);
    const to = Math.floor((days * (i + 1)) / list.length) - 1;
    return { name, plannedStart: addDayKey(start, from), plannedEnd: addDayKey(start, to) };
  });
}

function openPhasesEditor(project) {
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <p class="hint">${escapeHTML(BLD.phasesIntro)}</p>
    <div class="form__row">
      <div class="form__row-head"><span class="lbl">${escapeHTML(bt('tab.schedule'))}</span><div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-phases-typical>${escapeHTML(BLD.phasesTypical)}</button></div></div>
      <div class="lines lines--phases">
        <div class="lines__head"><span>${escapeHTML(BLD.thPhase)}</span><span>${escapeHTML(BLD.phaseStart)}</span><span>${escapeHTML(BLD.phaseEnd)}</span><span>${escapeHTML(BLD.phaseWeight)}</span><span>${escapeHTML(BLD.phaseDone)}</span><span></span></div>
        <div data-phase-rows>${project.phases.map(phaseRowHtml).join('')}</div>
        <p class="lines__empty"${project.phases.length ? ' hidden' : ''}>${escapeHTML(BLD.phasesNone)}</p>
        <div class="lines__foot"><button class="btn btn--sm btn--ghost" type="button" data-phase-add>${escapeHTML(BLD.phaseAdd)}</button></div>
      </div>
      <p class="hint">${escapeHTML(BLD.phasesWeightHint)}</p>
    </div>
    <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${escapeHTML(BLD.cancel)}</button><button class="btn btn--accent" type="submit">${escapeHTML(BLD.save)}</button></div>`;
  const rows = $('[data-phase-rows]', form);
  const sync = () => { $('.lines__empty', form).hidden = !!rows.children.length; };
  const wireRow = row => $('.l-rm', row).addEventListener('click', () => { row.remove(); sync(); });
  const add = ph => {
    rows.insertAdjacentHTML('beforeend', phaseRowHtml(ph));
    wireRow(rows.lastElementChild);
    sync();
    return rows.lastElementChild;
  };
  $$('.line', rows).forEach(wireRow);
  $('[data-phase-add]', form).addEventListener('click', () => {
    const last = $$('.line', rows).pop();
    const lastEnd = last ? $('[data-k="end"]', last).value : '';
    $('[data-k="name"]', add(lastEnd ? { plannedStart: addDayKey(lastEnd, 1) } : {})).focus();
  });
  $('[data-phases-typical]', form).addEventListener('click', () => typicalPhases(project).forEach(add));
  bindDrawerClose(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const phases = $$('.line', rows).map(row => {
      const get = k => $(`[data-k="${k}"]`, row).value.trim();
      return {
        id: row.dataset.phaseId || null,
        name: get('name'),
        plannedStart: get('start') || null,
        plannedEnd: get('end') || null,
        weight: get('weight') ? Number(get('weight')) : null,
        progressPct: clampPct(get('progress')),
      };
    }).filter(ph => ph.name || ph.plannedStart || ph.plannedEnd);
    if (phases.some(ph => !ph.name)) return toast(bt('errors.phase_name_required'));
    if (phases.some(ph => ph.plannedStart && ph.plannedEnd && ph.plannedEnd < ph.plannedStart)) return toast(bt('errors.invalid_dates'));
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api(`/app/api/building/projects/${encodeURIComponent(project.id)}/phases`, { method: 'PUT', body: JSON.stringify({ phases }) });
      toast(BLD.phasesSaved);
      closeDrawer();
      refreshBuildingPage();
    } catch (err) {
      btn.disabled = false;
      toast(buildingErrorText(err, BLD.saveFailed));
    }
  });
  openDrawer(BLD.phasesEdit, form, true, { eyebrow: projectLabel(project) });
}

function progressFormHtml(value, idPrefix) {
  return `<div class="form__row"><label class="lbl" for="${idPrefix}-pct">${escapeHTML(BLD.phaseDone)}</label>
    <div class="inp-unit"><input class="inp inp--mono inp--right" id="${idPrefix}-pct" type="number" min="0" max="100" step="5" value="${clampPct(value)}" /><span class="inp-unit__label">%</span></div></div>
    <div class="chip-picks">${[0, 25, 50, 75, 100].map(v => `<button class="chip" type="button" data-pct="${v}">${v}%</button>`).join('')}</div>`;
}

function openPhaseProgressForm(project, phase) {
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    ${dashFacts([
      [BLD.phaseStart, phase.plannedStart ? fmtDay(phase.plannedStart) : '—'],
      [BLD.phaseEnd, phase.plannedEnd ? fmtDay(phase.plannedEnd) : '—'],
      phase.plannedPct != null && phase.state !== 'DONE' ? [BLD.phasePlannedByNow, `${phase.plannedPct}%`] : null,
      phase.actualStart ? [BLD.phaseStarted, fmtDay(phase.actualStart)] : null,
      phase.actualEnd ? [BLD.phaseFinished, fmtDay(phase.actualEnd)] : null,
    ])}
    ${progressFormHtml(phase.progressPct, 'pp')}
    <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${escapeHTML(BLD.cancel)}</button><button class="btn btn--accent" type="submit">${escapeHTML(BLD.save)}</button></div>`;
  $$('[data-pct]', form).forEach(b => b.addEventListener('click', () => { $('#pp-pct', form).value = b.dataset.pct; }));
  bindDrawerClose(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api(`/app/api/building/projects/${encodeURIComponent(project.id)}/phases/${encodeURIComponent(phase.id)}`, { method: 'PATCH', body: JSON.stringify({ progressPct: clampPct($('#pp-pct', form).value) }) });
      toast(BLD.progressSaved);
      closeDrawer();
      refreshBuildingPage();
    } catch (err) {
      btn.disabled = false;
      toast(buildingErrorText(err, BLD.saveFailed));
    }
  });
  openDrawer(phase.name, form, false, { eyebrow: projectLabel(project) });
}

// A project without phases keeps a progress of its own.
function openManualProgressForm(project) {
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `<p class="hint">${escapeHTML(BLD.manualProgressHint)}</p>${progressFormHtml(project.manualProgressPct, 'mp')}
    <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${escapeHTML(BLD.cancel)}</button><button class="btn btn--accent" type="submit">${escapeHTML(BLD.save)}</button></div>`;
  $$('[data-pct]', form).forEach(b => b.addEventListener('click', () => { $('#mp-pct', form).value = b.dataset.pct; }));
  bindDrawerClose(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api(`/app/api/building/projects/${encodeURIComponent(project.id)}`, { method: 'PATCH', body: JSON.stringify({ manualProgressPct: clampPct($('#mp-pct', form).value) }) });
      toast(BLD.progressSaved);
      closeDrawer();
      refreshBuildingPage();
    } catch (err) {
      btn.disabled = false;
      toast(buildingErrorText(err, BLD.saveFailed));
    }
  });
  openDrawer(BLD.progressUpdate, form, false, { eyebrow: projectLabel(project) });
}

// ── The client's link ────────────────────────────────────────────────────────────────────────────

const projectShareUrl = token => `${location.origin}/app/share/${encodeURIComponent(token)}`;

async function openProjectShare(project) {
  let p = project;
  try { p = await api(`/app/api/building/projects/${encodeURIComponent(project.id)}`); } catch { /* keep what we have */ }
  const body = document.createElement('div');
  body.className = 'form';
  const draw = () => {
    const on = p.shareEnabled && p.shareToken;
    const url = on ? projectShareUrl(p.shareToken) : '';
    const text = on ? bt('share.message', { name: p.name, url }) : '';
    const wa = p.clientPhone ? `${waHref(p.clientPhone)}?text=${encodeURIComponent(text)}` : `https://wa.me/?text=${encodeURIComponent(text)}`;
    body.innerHTML = on
      ? `<p class="hint">${escapeHTML(bt('share.onIntro'))}</p>
        <div class="form__row"><label class="lbl" for="sh-url">${escapeHTML(bt('share.link'))}</label><input class="inp inp--mono" id="sh-url" readonly value="${escapeHTML(url)}" /></div>
        <div class="actions">
          <button class="btn btn--sm btn--primary" type="button" data-share-copy>${escapeHTML(bt('share.copy'))}</button>
          <a class="btn btn--sm" href="${escapeHTML(wa)}" target="_blank" rel="noopener">${escapeHTML(p.clientName ? bt('share.whatsappTo', { name: p.clientName }) : bt('share.whatsapp'))}</a>
          <a class="btn btn--sm btn--ghost" href="${escapeHTML(url)}" target="_blank" rel="noopener">${escapeHTML(bt('share.preview'))}</a>
        </div>
        <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(bt('share.seesTitle'))}</h2></header><div class="panel__body"><ul class="plain-list">${['share.sees1', 'share.sees2', 'share.sees3', 'share.sees4'].map(k => `<li>${escapeHTML(bt(k))}</li>`).join('')}</ul></div></section>
        <div class="drawer__foot"><button class="btn btn--sm btn--ghost" type="button" data-share-rotate>${escapeHTML(bt('share.rotate'))}</button><button class="btn btn--sm btn--ghost" type="button" data-share-off>${escapeHTML(bt('share.off'))}</button></div>`
      : `<p class="hint">${escapeHTML(bt('share.offIntro'))}</p>
        <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(bt('share.seesTitle'))}</h2></header><div class="panel__body"><ul class="plain-list">${['share.sees1', 'share.sees2', 'share.sees3', 'share.sees4'].map(k => `<li>${escapeHTML(bt(k))}</li>`).join('')}</ul></div></section>
        <div class="actions"><button class="btn btn--primary" type="button" data-share-on>${escapeHTML(bt('share.turnOn'))}</button></div>`;
    $('[data-share-copy]', body)?.addEventListener('click', async () => {
      try { await navigator.clipboard.writeText(url); toast(bt('share.copied')); } catch { $('#sh-url', body).select(); }
    });
    $('[data-share-on]', body)?.addEventListener('click', () => change('POST', '', bt('share.turnedOn')));
    $('[data-share-off]', body)?.addEventListener('click', async () => {
      if (await confirmDialog({ title: bt('share.offTitle'), body: bt('share.offBody'), okLabel: bt('share.off') })) change('DELETE', '', bt('share.turnedOff'));
    });
    $('[data-share-rotate]', body)?.addEventListener('click', async () => {
      if (await confirmDialog({ title: bt('share.rotateTitle'), body: bt('share.rotateBody'), okLabel: bt('share.rotate') })) change('POST', '/rotate', bt('share.rotated'));
    });
  };
  const change = async (method, suffix, done) => {
    try {
      p = await api(`/app/api/building/projects/${encodeURIComponent(p.id)}/share${suffix}`, { method });
      toast(done);
      draw();
      refreshBuildingPage();
    } catch (err) { toast(buildingErrorText(err, BLD.saveFailed)); }
  };
  draw();
  openDrawer(bt('share.title'), body, false, { eyebrow: projectLabel(p) });
}
