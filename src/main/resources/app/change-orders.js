/* Changes (/app → Changes): work added to or taken out of a project's contract (trabalhos a mais e a menos),
   from drafted, to sent to the client, to their answer. Approved, they change the contract's value and the
   planned end, and an addition's lines are measured and billed like the contract's. */

const CHANGE_FILTERS = {
  waiting: c => c.status === 'SENT',
  draft: c => c.status === 'DRAFT',
  approved: c => c.status === 'APPROVED',
  rejected: c => c.status === 'REJECTED',
  all: () => true,
};

function changeRowHtml(c) {
  return `<tr class="conversation-row${c.status === 'APPROVED' ? ' is-paid' : c.status === 'REJECTED' || c.status === 'DRAFT' ? ' is-draft' : ''}" data-change="${escapeHTML(c.id)}" tabindex="0">
    <td class="name long"><div class="tbl__title"><span>${escapeHTML(c.title)}</span><span class="tbl__aside">${changeStatusPill(c.status)}</span></div><div class="sub mono">${escapeHTML(c.number)}</div></td>
    <td class="long"${cellLabel(BLD.thProject)}>${escapeHTML(c.projectName)}</td>
    <td${cellLabel(BLD.thKind)}>${escapeHTML(changeKindLabel(c.kind))}</td>
    <td class="num nowrap"${cellLabel(BLD.thValue)}>${fmtEUR(c.signedEur)}</td>
    <td class="num"${cellLabel(BLD.thDays)}>${c.daysImpact ? bt('changeDaysN', { n: c.daysImpact }) : '—'}</td>
    <td class="tbl__stack-hide"${cellLabel(BLD.thStatus)}>${changeStatusPill(c.status)}</td>
  </tr>`;
}

function renderChangeOrders(root) {
  const filter = CHANGE_FILTERS[state.changesFilter] ? state.changesFilter : 'all';
  const all = state.changeOrders.filter(c => !state.changesProject || c.projectId === state.changesProject);
  const q = foldText(state.search);
  const shown = all.filter(CHANGE_FILTERS[filter]).filter(c => !q || foldText(`${c.number} ${c.title} ${c.projectName} ${c.description || ''}`).includes(q));
  const waiting = all.filter(CHANGE_FILTERS.waiting);
  const approved = all.filter(CHANGE_FILTERS.approved);
  const stats = [
    { label: BLD.changesStatWaiting, value: waiting.length },
    { label: BLD.changesStatWaitingValue, value: fmtEUR(waiting.reduce((n, c) => n + Number(c.signedEur || 0), 0)) },
    { label: BLD.changesStatApproved, value: fmtEUR(approved.reduce((n, c) => n + Number(c.signedEur || 0), 0)) },
    { label: BLD.changesStatDays, value: approved.reduce((n, c) => n + (c.daysImpact || 0), 0) },
  ];
  const chips = Object.keys(CHANGE_FILTERS).map(f => {
    const n = all.filter(CHANGE_FILTERS[f]).length;
    return `<button class="chip${filter === f ? ' is-on' : ''}" type="button" data-changes-filter="${f}">${escapeHTML(bt(`changesFilter.${f}`))}${n && f !== 'all' ? `<span class="chip__count">${n}</span>` : ''}</button>`;
  }).join('') + projectFilterHtml(state.changesProject, 'data-changes-project');
  root.innerHTML = hero(labels['change-orders'], BLD.changesDesc, statCards(stats)) + bldPanel({
    title: BLD.changesTitle,
    tag: shown.length,
    tools: chips,
    head: `<tr><th>${escapeHTML(BLD.thChange)}</th><th>${escapeHTML(BLD.thProject)}</th><th>${escapeHTML(BLD.thKind)}</th><th class="right">${escapeHTML(BLD.thValue)}</th><th class="right">${escapeHTML(BLD.thDays)}</th><th>${escapeHTML(BLD.thStatus)}</th></tr>`,
    rows: shown.map(changeRowHtml).join(''),
    empty: state.changeOrders.length ? BLD.changesEmptyFilter : BLD.changesEmpty,
    emptyDesc: state.changeOrders.length ? '' : BLD.changesEmptyDesc,
  });
  $$('[data-changes-filter]', root).forEach(b => b.addEventListener('click', () => { state.changesFilter = b.dataset.changesFilter; render(); }));
  $('[data-changes-project]', root)?.addEventListener('change', e => { state.changesProject = e.target.value; render(); });
  $$('[data-change]', root).forEach(row => {
    row.addEventListener('click', () => openChangeDetail(row.dataset.change));
    row.addEventListener('keydown', e => { if (e.key === 'Enter') openChangeDetail(row.dataset.change); });
  });
}

// `opts.projectId` starts a new change on that project.
async function openChangeForm(change = null, opts = {}) {
  await ensureBuildingProjects().catch(() => {});
  const catalog = hasModule('catalog') ? await api('/app/api/crm/standard-items').catch(() => []) : [];
  const c = change || {};
  let kind = c.kind || 'ADDITION';
  const li = lineItemsField(catalog);
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${change ? '' : projectSelectHtml('co-project', opts.projectId || state.changesProject || '')}
      <div class="form__row${change ? ' form__row--full' : ''}"><label class="lbl" for="co-title">${escapeHTML(BLD.changeTitle)} <span class="req">●</span></label>
        <input class="inp" id="co-title" maxlength="160" required value="${escapeHTML(c.title || '')}" placeholder="${escapeHTML(BLD.changeTitlePh)}" /></div>
      <div class="form__row"><span class="lbl" id="co-kind-label">${escapeHTML(BLD.thKind)}</span>
        <div class="chip-picks" role="group" aria-labelledby="co-kind-label">${['ADDITION', 'OMISSION'].map(k => `<button class="chip${kind === k ? ' is-on' : ''}" type="button" aria-pressed="${kind === k}" data-change-kind="${k}">${escapeHTML(changeKindLabel(k))}</button>`).join('')}</div></div>
      <div class="form__row"><label class="lbl" for="co-days">${escapeHTML(BLD.changeDays)}</label>
        <input class="inp inp--mono inp--right" id="co-days" type="number" min="-730" max="730" step="1" value="${c.daysImpact ?? 0}" /><p class="hint">${escapeHTML(BLD.changeDaysHint)}</p></div>
      <div class="form__row form__row--full"><label class="lbl" for="co-desc">${escapeHTML(BLD.formDescription)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <textarea class="txt" id="co-desc" maxlength="4000" placeholder="${escapeHTML(BLD.changeDescPh)}">${escapeHTML(c.description || '')}</textarea></div>
    </div>
    ${li.html}
    <p class="hint" data-change-kind-hint>${escapeHTML(kind === 'OMISSION' ? BLD.changeOmissionHint : BLD.changeAdditionHint)}</p>
    ${c.status === 'SENT' ? `<p class="hint">${escapeHTML(BLD.changeEditSentHint)}</p>` : ''}
    <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${escapeHTML(BLD.cancel)}</button><button class="btn btn--accent" type="submit">${escapeHTML(change ? BLD.save : BLD.changeSave)}</button></div>`;
  li.wire(form, { initial: (c.items || []).map(it => ({ description: it.description, quantity: it.quantity, unit: it.unit, unitPriceEur: it.unitPriceEur })) });
  $$('[data-change-kind]', form).forEach(b => b.addEventListener('click', () => {
    kind = b.dataset.changeKind;
    $$('[data-change-kind]', form).forEach(x => { x.classList.toggle('is-on', x.dataset.changeKind === kind); x.setAttribute('aria-pressed', String(x.dataset.changeKind === kind)); });
    $('[data-change-kind-hint]', form).textContent = kind === 'OMISSION' ? BLD.changeOmissionHint : BLD.changeAdditionHint;
  }));
  bindDrawerClose(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const projectId = change ? change.projectId : $('#co-project', form).value;
    if (!projectId) return toast(BLD.chooseProject);
    const title = $('#co-title', form).value.trim();
    if (!title) return toast(bt('errors.title_required'));
    const items = li.collect(form);
    if (!items.length) return toast(bt('errors.items_required'));
    const payload = { title, kind, daysImpact: Math.round(Number($('#co-days', form).value || 0)), description: $('#co-desc', form).value, items };
    if (!change) payload.projectId = projectId;
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api(change ? `/app/api/building/changes/${encodeURIComponent(change.id)}` : '/app/api/building/changes', { method: change ? 'PATCH' : 'POST', body: JSON.stringify(payload) });
      toast(!change ? BLD.changeSaved : change.status === 'SENT' ? BLD.changeUpdatedDraft : BLD.changeUpdated);
      closeDrawer();
      refreshBuildingPage();
    } catch (err) {
      btn.disabled = false;
      toast(buildingErrorText(err, BLD.saveFailed));
    }
  });
  openDrawer(change ? change.number : BLD.changeNew, form, true, { eyebrow: change ? change.projectName : labels['change-orders'] });
}

// The client's answer, in their name or the team member who heard it.
function openChangeDecision(change, status, back) {
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <p class="hint">${escapeHTML(status === 'APPROVED' ? bt('changeApproveIntro', { amount: fmtEUR(change.signedEur), days: change.daysImpact }) : BLD.changeRejectIntro)}</p>
    <div class="form__row"><label class="lbl" for="cd-by">${escapeHTML(BLD.changeDecidedBy)}</label><input class="inp" id="cd-by" maxlength="160" value="${escapeHTML(change.clientName || '')}" /></div>
    <div class="form__row"><label class="lbl" for="cd-note">${escapeHTML(BLD.changeNote)} <span class="opt">${escapeHTML(STR.optional)}</span></label><textarea class="txt" id="cd-note" maxlength="1000"></textarea></div>
    <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${escapeHTML(BLD.cancel)}</button><button class="btn ${status === 'APPROVED' ? 'btn--accent' : 'btn--danger'}" type="submit">${escapeHTML(status === 'APPROVED' ? BLD.changeRecordApproval : BLD.changeRecordRejection)}</button></div>`;
  bindDrawerClose(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api(`/app/api/building/changes/${encodeURIComponent(change.id)}/status`, { method: 'POST', body: JSON.stringify({ status, decidedBy: $('#cd-by', form).value, note: $('#cd-note', form).value }) });
      toast(status === 'APPROVED' ? BLD.changeApproved : BLD.changeRejected);
      closeDrawer();
      refreshBuildingPage();
    } catch (err) {
      btn.disabled = false;
      toast(buildingErrorText(err, BLD.saveFailed));
    }
  });
  openFrom(back, () => openDrawer(status === 'APPROVED' ? BLD.changeRecordApproval : BLD.changeRecordRejection, form, false, { eyebrow: `${change.number} · ${change.title}` }));
}

const changeApprovalUrl = token => `${location.origin}/app/share/change/${encodeURIComponent(token)}`;

// While a change is sent, the link its client approves or rejects it from, to copy or send by WhatsApp.
function changeLinkPanel(c, url) {
  const text = bt('changeMessageLink', { number: c.number, title: c.title, project: c.projectName, amount: fmtEUR(c.signedEur), days: c.daysImpact, url });
  const wa = c.clientPhone ? `${waHref(c.clientPhone)}?text=${encodeURIComponent(text)}` : `https://wa.me/?text=${encodeURIComponent(text)}`;
  return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(BLD.changeLinkTitle)}</h2></header><div class="panel__body">
    <p class="hint">${escapeHTML(BLD.changeLinkIntro)}</p>
    <div class="form__row"><label class="lbl" for="co-link">${escapeHTML(BLD.changeLinkLabel)}</label><input class="inp inp--mono" id="co-link" readonly value="${escapeHTML(url)}" /></div>
    <div class="actions">
      <button class="btn btn--sm btn--primary" type="button" data-change-copy>${escapeHTML(bt('share.copy'))}</button>
      <a class="btn btn--sm" href="${escapeHTML(wa)}" target="_blank" rel="noopener">${escapeHTML(c.clientName ? bt('share.whatsappTo', { name: c.clientName }) : bt('share.whatsapp'))}</a>
      <a class="btn btn--sm btn--ghost" href="${escapeHTML(url)}" target="_blank" rel="noopener">${escapeHTML(bt('share.preview'))}</a>
    </div></div></section>`;
}

async function openChangeDetail(id) {
  let change;
  try { change = await api(`/app/api/building/changes/${encodeURIComponent(id)}`); } catch { return toast(STR.loadFailed); }
  const here = { key: `change:${change.id}`, label: change.number, open: () => openChangeDetail(change.id) };
  const c = change;
  const open = c.status === 'DRAFT' || c.status === 'SENT';
  const projectLink = !linksBackTo(`project:${c.projectId}`);
  const link = c.status === 'SENT' && c.approvalToken ? changeApprovalUrl(c.approvalToken) : '';
  // A change sent before the links existed has none: the message asks the client to confirm by reply.
  const message = bt('changeMessage', { number: c.number, title: c.title, project: c.projectName, amount: fmtEUR(c.signedEur), days: c.daysImpact });
  const wa = c.clientPhone && !link ? `${waHref(c.clientPhone)}?text=${encodeURIComponent(message)}` : '';
  const decided = c.decidedAt ? [fmtDay(c.decidedAt), c.decidedBy, c.decidedVia === 'CLIENT_LINK' ? BLD.changeViaLink : ''].filter(Boolean).join(' · ') : '';
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `
    <div class="detail__head">
      ${projectLink ? `<button class="detail__client detail__client--link" type="button" data-change-project>${escapeHTML(c.projectName)}</button>` : `<div class="detail__client">${escapeHTML(c.projectName)}</div>`}
      <div class="detail__amount">${changeStatusPill(c.status)}<span class="detail__figure">${fmtEUR(c.signedEur)}</span></div>
    </div>
    ${detailMeta([
      { label: BLD.thKind, value: changeKindLabel(c.kind) },
      { label: BLD.changeDays, value: c.daysImpact ? bt('changeDaysN', { n: c.daysImpact }) : '—' },
      { label: STR.detailCreated, value: fmtDay(c.createdAt) },
      c.sentAt ? { label: BLD.changeSentOn, value: fmtDay(c.sentAt) } : null,
      decided ? { label: c.status === 'REJECTED' ? BLD.changeRejectedOn : BLD.changeApprovedOn, value: decided } : null,
    ])}
    ${link ? changeLinkPanel(c, link) : ''}
    ${c.description ? `<p class="panel__text">${escapeHTML(c.description)}</p>` : ''}
    ${itemsTable(c.items)}
    ${c.decisionNote ? `<p class="hint">${escapeHTML(c.decisionNote)}</p>` : ''}
    ${c.status === 'APPROVED' ? `<p class="hint">${escapeHTML(c.kind === 'ADDITION' ? BLD.changeApprovedHint : BLD.changeOmittedHint)}</p>` : ''}
    ${c.status === 'APPROVED' && c.measured ? `<p class="hint">${escapeHTML(BLD.changeMeasuredHint)}</p>` : ''}
    <div class="detail__foot">
      ${c.status === 'DRAFT' ? `<button class="btn btn--sm btn--accent" type="button" data-change-send>${escapeHTML(BLD.changeMarkSent)}</button>` : ''}
      ${c.status === 'SENT' && wa ? `<a class="btn btn--sm" href="${escapeHTML(wa)}" target="_blank" rel="noopener">${escapeHTML(BLD.changeWhatsApp)}</a>` : ''}
      ${open ? `<button class="btn btn--sm${c.status === 'SENT' ? ' btn--accent' : ''}" type="button" data-change-decide="APPROVED">${escapeHTML(BLD.changeRecordApproval)}</button>
        <button class="btn btn--sm" type="button" data-change-decide="REJECTED">${escapeHTML(BLD.changeRecordRejection)}</button>
        <button class="btn btn--sm btn--ghost" type="button" data-change-edit>${escapeHTML(BLD.edit)}</button>` : ''}
      ${c.status === 'SENT' || c.status === 'REJECTED' || (c.status === 'APPROVED' && !c.measured) ? `<button class="btn btn--sm btn--ghost" type="button" data-change-draft>${escapeHTML(BLD.changeBackToDraft)}</button>` : ''}
      ${c.status !== 'APPROVED' ? `<button class="btn btn--sm btn--ghost" type="button" data-change-delete>${escapeHTML(BLD.delete)}</button>` : ''}
    </div>`;
  const setStatus = async (status, done) => {
    try {
      await api(`/app/api/building/changes/${encodeURIComponent(c.id)}/status`, { method: 'POST', body: JSON.stringify({ status }) });
      toast(done);
      refreshBuildingPage();
      openChangeDetail(c.id);
    } catch (err) { toast(buildingErrorText(err, BLD.saveFailed)); }
  };
  $('[data-change-project]', body)?.addEventListener('click', async () => {
    await ensureBuildingProjects().catch(() => {});
    openFrom(here, () => openProjectDrawer(c.projectId, 'changes'));
  });
  $('[data-change-copy]', body)?.addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(link); toast(bt('share.copied')); } catch { $('#co-link', body).select(); }
  });
  $('[data-change-send]', body)?.addEventListener('click', () => setStatus('SENT', BLD.changeSent));
  $('[data-change-draft]', body)?.addEventListener('click', () => setStatus('DRAFT', BLD.changeReopened));
  $('[data-change-edit]', body)?.addEventListener('click', () => openFrom(here, () => openChangeForm(c)));
  $$('[data-change-decide]', body).forEach(b => b.addEventListener('click', () => openChangeDecision(c, b.dataset.changeDecide, here)));
  $('[data-change-delete]', body)?.addEventListener('click', async () => {
    if (!await confirmDialog({ title: bt('changeDeleteTitle', { number: c.number }), body: BLD.changeDeleteBody, okLabel: BLD.delete })) return;
    try {
      await api(`/app/api/building/changes/${encodeURIComponent(c.id)}`, { method: 'DELETE' });
      toast(BLD.changeDeleted);
      closeDrawer();
      refreshBuildingPage();
    } catch (err) { toast(buildingErrorText(err, BLD.saveFailed)); }
  });
  openDrawer(c.title, body, true, { eyebrow: `${c.number} · ${changeKindLabel(c.kind)}` });
}
