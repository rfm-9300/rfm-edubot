/* Issues (/app → Issues): what is wrong or still to do on site, over every project: the list by status, an
   issue's form (who fixes it, by when, with photos) and its detail with its history. */

const ISSUE_FILTERS = {
  open: i => i.status === 'OPEN' || i.status === 'IN_PROGRESS',
  resolved: i => i.status === 'RESOLVED',
  closed: i => i.status === 'CLOSED',
  all: () => true,
};

function issueRowHtml(i) {
  return `<tr class="conversation-row${i.overdue ? ' is-overdue' : i.status === 'CLOSED' ? ' is-paid' : ''}" data-issue="${escapeHTML(i.id)}" tabindex="0">
    <td class="name long"><div class="tbl__title"><span>${escapeHTML(i.title)}</span><span class="tbl__aside">${issueStatusPill(i.status, i.overdue)}</span></div><div class="sub">${escapeHTML([i.number, i.location].filter(Boolean).join(' · '))}</div></td>
    <td class="long"${cellLabel(BLD.thProject)}>${escapeHTML(i.projectName)}</td>
    <td${cellLabel(BLD.thKind)}>${escapeHTML(issueKindLabel(i.kind))}</td>
    <td${cellLabel(BLD.thPriority)}>${issuePriorityPill(i.priority)}</td>
    <td${cellLabel(BLD.thAssignee)}>${escapeHTML(i.assigneeName || '—')}</td>
    <td class="mono nowrap"${cellLabel(BLD.thDue)}>${escapeHTML(i.dueDate ? fmtDay(i.dueDate) : '—')}${i.dueDate && (i.status === 'OPEN' || i.status === 'IN_PROGRESS') ? `<div class="sub">${escapeHTML(relDay(i.dueDate))}</div>` : ''}</td>
    <td class="tbl__stack-hide"${cellLabel(BLD.thStatus)}>${issueStatusPill(i.status, i.overdue)}</td>
  </tr>`;
}

function renderSiteIssues(root) {
  const filter = ISSUE_FILTERS[state.issuesFilter] ? state.issuesFilter : 'open';
  const all = state.issues.filter(i => !state.issuesProject || i.projectId === state.issuesProject);
  const q = foldText(state.search);
  const shown = all.filter(ISSUE_FILTERS[filter])
    .filter(i => !q || foldText(`${i.number} ${i.title} ${i.location || ''} ${i.projectName} ${i.assigneeName || ''}`).includes(q))
    .sort((a, b) => Number(b.overdue) - Number(a.overdue) || ISSUE_PRIORITIES.indexOf(b.priority) - ISSUE_PRIORITIES.indexOf(a.priority) || String(a.dueDate || '9').localeCompare(String(b.dueDate || '9')));
  const open = all.filter(ISSUE_FILTERS.open);
  const stats = [
    { label: BLD.issuesStatOpen, value: open.length },
    { label: BLD.issuesStatOverdue, value: open.filter(i => i.overdue).length },
    { label: BLD.issuesStatHigh, value: open.filter(i => i.priority === 'HIGH').length },
    { label: BLD.issuesStatToCheck, value: all.filter(ISSUE_FILTERS.resolved).length },
  ];
  const chips = Object.keys(ISSUE_FILTERS).map(f => {
    const n = all.filter(ISSUE_FILTERS[f]).length;
    return `<button class="chip${filter === f ? ' is-on' : ''}" type="button" data-issues-filter="${f}">${escapeHTML(bt(`issuesFilter.${f}`))}${n && f !== 'all' ? `<span class="chip__count">${n}</span>` : ''}</button>`;
  }).join('') + projectFilterHtml(state.issuesProject, 'data-issues-project');
  root.innerHTML = hero(labels['site-issues'], BLD.issuesDesc, statCards(stats)) + bldPanel({
    title: BLD.issuesTitle,
    tag: shown.length,
    tools: chips,
    head: `<tr><th>${escapeHTML(BLD.thIssue)}</th><th>${escapeHTML(BLD.thProject)}</th><th>${escapeHTML(BLD.thKind)}</th><th>${escapeHTML(BLD.thPriority)}</th><th>${escapeHTML(BLD.thAssignee)}</th><th>${escapeHTML(BLD.thDue)}</th><th>${escapeHTML(BLD.thStatus)}</th></tr>`,
    rows: shown.map(issueRowHtml).join(''),
    empty: state.issues.length ? BLD.issuesEmptyFilter : BLD.issuesEmpty,
    emptyDesc: state.issues.length ? '' : BLD.issuesEmptyDesc,
  });
  $$('[data-issues-filter]', root).forEach(b => b.addEventListener('click', () => { state.issuesFilter = b.dataset.issuesFilter; render(); }));
  $('[data-issues-project]', root)?.addEventListener('change', e => { state.issuesProject = e.target.value; render(); });
  $$('[data-issue]', root).forEach(row => {
    row.addEventListener('click', () => openIssueDetail(row.dataset.issue));
    row.addEventListener('keydown', e => { if (e.key === 'Enter') openIssueDetail(row.dataset.issue); });
  });
}

// Who fixes it: an employee of the company or a supplier (a subcontractor).
function assigneeOptions(employees, suppliers, issue) {
  const selected = issue?.employeeId ? `e:${issue.employeeId}` : issue?.supplierId ? `s:${issue.supplierId}` : '';
  const option = (prefix, x) => `<option value="${prefix}:${escapeHTML(x.id)}"${`${prefix}:${x.id}` === selected ? ' selected' : ''}>${escapeHTML(x.name)}</option>`;
  return `<option value="">${escapeHTML(BLD.issueNobody)}</option>
    ${employees.length ? `<optgroup label="${escapeHTML(labels.employees)}">${employees.map(e => option('e', e)).join('')}</optgroup>` : ''}
    ${suppliers.length ? `<optgroup label="${escapeHTML(labels.suppliers)}">${suppliers.map(s => option('s', s)).join('')}</optgroup>` : ''}`;
}

// `opts.projectId` starts a new issue on that project.
async function openIssueForm(issue = null, opts = {}) {
  await ensureBuildingProjects().catch(() => {});
  const [employees, suppliers] = await Promise.all([
    hasModule('employees') ? api('/app/api/crm/employees').catch(() => []) : Promise.resolve([]),
    hasModule('suppliers') ? api('/app/api/crm/suppliers').catch(() => []) : Promise.resolve([]),
  ]);
  const i = issue || {};
  const projectId = issue ? issue.projectId : opts.projectId || state.issuesProject || '';
  const queued = [];
  const select = (id, values, label, current) => `<div class="form__row"><label class="lbl" for="${id}">${escapeHTML(label)}</label><select class="sel" id="${id}">${values.map(([v, text]) => `<option value="${v}"${v === current ? ' selected' : ''}>${escapeHTML(text)}</option>`).join('')}</select></div>`;
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${issue ? '' : projectSelectHtml('is-project', projectId)}
      <div class="form__row${issue ? ' form__row--full' : ''}"><label class="lbl" for="is-title">${escapeHTML(BLD.issueTitle)} <span class="req">●</span></label>
        <input class="inp" id="is-title" maxlength="160" required value="${escapeHTML(i.title || '')}" placeholder="${escapeHTML(BLD.issueTitlePh)}" /></div>
      ${select('is-kind', ISSUE_KINDS.map(k => [k, issueKindLabel(k)]), BLD.thKind, i.kind || 'PUNCH')}
      ${select('is-priority', ISSUE_PRIORITIES.map(k => [k, issuePriorityLabel(k)]), BLD.thPriority, i.priority || 'MEDIUM')}
      <div class="form__row"><label class="lbl" for="is-location">${escapeHTML(BLD.issueLocation)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp" id="is-location" maxlength="160" value="${escapeHTML(i.location || '')}" placeholder="${escapeHTML(BLD.issueLocationPh)}" /></div>
      <div class="form__row"><label class="lbl" for="is-phase">${escapeHTML(BLD.thPhase)} <span class="opt">${escapeHTML(STR.optional)}</span></label><select class="sel" id="is-phase"><option value="">—</option></select></div>
      ${employees.length || suppliers.length ? `<div class="form__row"><label class="lbl" for="is-assignee">${escapeHTML(BLD.thAssignee)} <span class="opt">${escapeHTML(STR.optional)}</span></label><select class="sel" id="is-assignee">${assigneeOptions(employees, suppliers, issue)}</select></div>` : ''}
      <div class="form__row"><label class="lbl" for="is-due">${escapeHTML(BLD.thDue)} <span class="opt">${escapeHTML(STR.optional)}</span></label><input class="inp inp--mono" id="is-due" type="date" value="${escapeHTML(i.dueDate || '')}" /></div>
      <div class="form__row form__row--full"><label class="lbl" for="is-desc">${escapeHTML(BLD.formDescription)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <textarea class="txt" id="is-desc" maxlength="4000">${escapeHTML(i.description || '')}</textarea></div>
    </div>
    <div class="form__row"><div class="form__row-head"><span class="lbl">${escapeHTML(bt('tab.photos'))}</span><div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-issue-photos>${escapeHTML(BLD.photosAdd)}</button></div></div>
      <p class="hint" data-issue-queued>${escapeHTML(BLD.issuePhotosHint)}</p></div>
    <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${escapeHTML(BLD.cancel)}</button><button class="btn btn--accent" type="submit">${escapeHTML(issue ? BLD.save : BLD.issueSave)}</button></div>`;
  const loadPhases = async id => {
    const box = $('#is-phase', form);
    box.innerHTML = '<option value="">—</option>';
    if (!id) return;
    try {
      const project = await api(`/app/api/building/projects/${encodeURIComponent(id)}`);
      box.innerHTML += project.phases.map(ph => `<option value="${escapeHTML(ph.id)}"${ph.id === i.phaseId ? ' selected' : ''}>${escapeHTML(ph.name)}</option>`).join('');
    } catch { /* no phases to offer */ }
  };
  loadPhases(projectId);
  $('#is-project', form)?.addEventListener('change', e => loadPhases(e.target.value));
  $('[data-issue-photos]', form).addEventListener('click', async () => {
    queued.push(...await pickPhotoFiles());
    $('[data-issue-queued]', form).textContent = queued.length ? bt('photos.queued', { n: queued.length }) : BLD.issuePhotosHint;
  });
  bindDrawerClose(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const target = issue ? issue.projectId : $('#is-project', form).value;
    if (!target) return toast(BLD.chooseProject);
    const title = $('#is-title', form).value.trim();
    if (!title) return toast(bt('errors.title_required'));
    const assignee = $('#is-assignee', form)?.value || '';
    const payload = {
      title,
      kind: $('#is-kind', form).value,
      priority: $('#is-priority', form).value,
      location: $('#is-location', form).value,
      phaseId: $('#is-phase', form).value,
      dueDate: $('#is-due', form).value,
      description: $('#is-desc', form).value,
    };
    if ($('#is-assignee', form)) Object.assign(payload, { employeeId: assignee.startsWith('e:') ? assignee.slice(2) : '', supplierId: assignee.startsWith('s:') ? assignee.slice(2) : '' });
    if (!issue) payload.projectId = target;
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const saved = await api(issue ? `/app/api/building/issues/${encodeURIComponent(issue.id)}` : '/app/api/building/issues', { method: issue ? 'PATCH' : 'POST', body: JSON.stringify(payload) });
      if (queued.length) await uploadProjectPhotos(target, queued, { issueId: saved.id, shared: 'false' });
      toast(issue ? BLD.issueUpdated : BLD.issueSaved);
      closeDrawer();
      refreshBuildingPage();
    } catch (err) {
      btn.disabled = false;
      toast(buildingErrorText(err, BLD.saveFailed));
    }
  });
  openDrawer(issue ? issue.number : BLD.issueNew, form, true, { eyebrow: issue ? issue.projectName : labels['site-issues'] });
}

// The next steps an issue can take from its status.
const ISSUE_NEXT = {
  OPEN: [['IN_PROGRESS', 'issueStart', 'btn--accent'], ['RESOLVED', 'issueResolve', '']],
  IN_PROGRESS: [['RESOLVED', 'issueResolve', 'btn--accent']],
  RESOLVED: [['CLOSED', 'issueClose', 'btn--accent'], ['OPEN', 'issueReopen', '']],
  CLOSED: [['OPEN', 'issueReopen', '']],
};

async function openIssueDetail(id) {
  let issue;
  try { issue = await api(`/app/api/building/issues/${encodeURIComponent(id)}`); } catch { return toast(STR.loadFailed); }
  const here = { key: `issue:${issue.id}`, label: issue.number, open: () => openIssueDetail(issue.id) };
  const body = document.createElement('div');
  body.className = 'record';
  const reload = async () => {
    try { issue = await api(`/app/api/building/issues/${encodeURIComponent(issue.id)}`); } catch { return; }
    if (body.isConnected) draw();
    refreshBuildingPage();
  };
  const draw = () => {
    const projectLink = !linksBackTo(`project:${issue.projectId}`);
    body.innerHTML = `
      <div class="detail__head">
        ${projectLink ? `<button class="detail__client detail__client--link" type="button" data-issue-project>${escapeHTML(issue.projectName)}</button>` : `<div class="detail__client">${escapeHTML(issue.projectName)}</div>`}
        <div class="detail__amount">${issuePriorityPill(issue.priority)}${issueStatusPill(issue.status, issue.overdue)}</div>
      </div>
      ${detailMeta([
        { label: BLD.thKind, value: issueKindLabel(issue.kind) },
        issue.location ? { label: BLD.issueLocation, value: issue.location } : null,
        issue.phaseName ? { label: BLD.thPhase, value: issue.phaseName } : null,
        { label: BLD.thAssignee, value: issue.assigneeName || BLD.issueNobody },
        issue.dueDate ? { label: BLD.thDue, value: `${fmtDay(issue.dueDate)}${issue.status === 'OPEN' || issue.status === 'IN_PROGRESS' ? ` · ${relDay(issue.dueDate)}` : ''}` } : null,
        { label: STR.detailCreated, value: `${fmtDay(issue.createdAt)} · ${issue.createdBy}` },
      ])}
      ${textPanel(BLD.formDescription, issue.description)}
      <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(bt('tab.photos'))} <span class="tag">${issue.photos.length}</span></h2>
        <div class="panel__tools"><button class="btn btn--sm btn--ghost" type="button" data-issue-add-photos>${escapeHTML(BLD.photosAdd)}</button></div></header>
        <div class="panel__body">${issue.photos.length ? photoGridHtml(issue.photos) : `<p class="dash-empty">${escapeHTML(BLD.issueNoPhotos)}</p>`}</div></section>
      <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(BLD.issueHistory)}</h2></header>
        <ul class="worklist worklist--wrap">${[...issue.history].reverse().map(h => `<li><button class="worklist__item" type="button" disabled data-tone="${{ CLOSED: 'ok', RESOLVED: 'accent', IN_PROGRESS: 'info' }[h.to] || 'warn'}"><span class="worklist__dot"></span>
          <span class="worklist__main"><span class="worklist__title">${escapeHTML(h.from ? bt('issueMoved', { from: issueStatusLabel(h.from), to: issueStatusLabel(h.to) }) : BLD.issueCreated)}</span><span class="worklist__detail">${escapeHTML([h.by, h.note].filter(Boolean).join(' · '))}</span></span>
          <span class="worklist__side"><span class="worklist__when">${escapeHTML(fmtDate(h.at))}</span></span></button></li>`).join('')}</ul></section>
      <div class="detail__foot">
        ${(ISSUE_NEXT[issue.status] || []).map(([to, key, cls]) => `<button class="btn btn--sm ${cls}" type="button" data-issue-status="${to}">${escapeHTML(BLD[key])}</button>`).join('')}
        <button class="btn btn--sm" type="button" data-issue-edit>${escapeHTML(BLD.edit)}</button>
        <button class="btn btn--sm btn--ghost" type="button" data-issue-delete>${escapeHTML(BLD.delete)}</button>
      </div>`;
    $('[data-issue-project]', body)?.addEventListener('click', async () => {
      await ensureBuildingProjects().catch(() => {});
      openFrom(here, () => openProjectDrawer(issue.projectId, 'issues'));
    });
    $('[data-issue-edit]', body).addEventListener('click', () => openFrom(here, () => openIssueForm(issue)));
    $('[data-issue-add-photos]', body).addEventListener('click', () => addPhotosTo(issue.projectId, { issueId: issue.id, shared: 'false' }, reload));
    $$('[data-issue-status]', body).forEach(b => b.addEventListener('click', async () => {
      b.disabled = true;
      try {
        issue = await api(`/app/api/building/issues/${encodeURIComponent(issue.id)}`, { method: 'PATCH', body: JSON.stringify({ status: b.dataset.issueStatus }) });
        toast(bt('issueNow', { status: issueStatusLabel(issue.status) }));
        draw();
        refreshBuildingPage();
      } catch (err) { b.disabled = false; toast(buildingErrorText(err, BLD.saveFailed)); }
    }));
    $('[data-issue-delete]', body).addEventListener('click', async () => {
      if (!await confirmDialog({ title: bt('issueDeleteTitle', { number: issue.number }), body: BLD.issueDeleteBody, okLabel: BLD.delete })) return;
      try {
        await api(`/app/api/building/issues/${encodeURIComponent(issue.id)}`, { method: 'DELETE' });
        toast(BLD.issueDeleted);
        closeDrawer();
        refreshBuildingPage();
      } catch (err) { toast(buildingErrorText(err, BLD.saveFailed)); }
    });
    wirePhotoGrid(body, issue.photos, here, reload);
  };
  draw();
  openDrawer(issue.title, body, true, { eyebrow: `${issue.number} · ${issueKindLabel(issue.kind)}` });
}
