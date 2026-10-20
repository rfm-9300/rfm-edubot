/* Registered services: employees sign in, register the work they did, the team approves it. An approved
   submission becomes an ordinary Serviços row "done by" the employee; a rejected one keeps its reason for
   the employee to read. Both sides open the same detail, with their own actions. Also here: an employee's
   sign-in on their record and the employee's own page (My services). */
const submissionTone = status => ({ PENDING: 'warn', APPROVED: 'ok', REJECTED: 'bad' }[status] || '');
function submissionPill(status) {
  const tone = submissionTone(status);
  return `<span class="pill ${tone ? `pill--${tone}` : ''}">${escapeHTML((SUB.status || {})[status] || status)}</span>`;
}
const SUBMISSION_STATUSES = ['PENDING', 'APPROVED', 'REJECTED'];
const submissionTrailEntry = s => ({ key: `submission:${s.id}`, label: s.name, open: () => openSubmissionDetail(s.id) });
const approvedThisMonth = list => list.filter(s => s.status === 'APPROVED' && periodKey(s.performedAt, 'month') === currentPeriodKey('month'));
const submissionErrorText = err => catalogTextOr(SUB, 'admin.submissions', err?.code ? `err_${err.code}` : '', SUB.saveFailed);
const accessErrorText = err => catalogTextOr(SUB, 'admin.submissions', err?.code ? `accessErr_${err.code}` : '', SUB.accessFailed);

// Approving or rejecting changes the counts on Home, the Employees list and the nav, and adds a Serviços row.
async function afterSubmissionDecision() {
  const pending = await api('/app/api/crm/service-submissions?status=PENDING').catch(() => null);
  if (pending) {
    state.pendingSubmissions = pending;
    state.fetched.submissions = true;
  }
  if (['overview', 'services'].includes(state.active)) await loadModule(state.active).catch(() => {});
  render();
}

// What an employee's sign-in is for: their hours, their services, or both, as the company has them.
function signInPurpose(prefix, servicesOnly, name) {
  if (!hasModule('timesheets')) return servicesOnly({ name });
  return I18N.t(`app.time.${prefix}${submissionsOn() ? 'Both' : 'Hours'}`, { name });
}

function employeeAccessHtml(p, access) {
  if (!access) return '';
  const has = !!access.email;
  const on = has && access.active && !p.archivedAt;
  const detail = !has ? signInPurpose('accessNone', SUB.accessNoneDetail, p.name)
    : p.archivedAt ? SUB.accessArchived
    : !access.active ? SUB.accessOffDetail
    : access.lastLoginAt ? SUB.accessLastSignIn({ when: relTime(access.lastLoginAt) }) : SUB.accessNeverSignedIn;
  const pill = has ? `<span class="worklist__meta"><span class="pill ${on ? 'pill--ok' : 'pill--warn'}">${escapeHTML(on ? SUB.accessOn : SUB.accessOff)}</span></span>` : '';
  return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(SUB.accessTitle)}</h2></header>
      <ul class="worklist"><li><button class="worklist__item" type="button" data-employee-access data-tone="${on ? 'ok' : has ? 'warn' : 'neutral'}"${access.canManage ? '' : ' disabled'}>
        <span class="worklist__dot" aria-hidden="true"></span>
        <span class="worklist__main"><span class="worklist__title">${escapeHTML(has ? access.email : SUB.accessNone)}</span><span class="worklist__detail">${escapeHTML(detail)}</span></span>
        <span class="worklist__side">${pill}</span>
      </button></li></ul>
    </section>
    ${access.canManage ? '' : `<p class="hint">${escapeHTML(SUB.accessAdminOnly)}</p>`}`;
}

function employeeWorkHtml(subs, access) {
  if (!subs.length) return access?.email ? recordEmpty(SUB.noneYet, null, '') : '';
  const marker = { APPROVED: ' is-paid', REJECTED: ' is-draft' };
  const rows = subs.map(s => `<tr class="conversation-row${marker[s.status] || ''}" data-record-open="submission:${escapeHTML(s.id)}">
      <td class="mono muted">${escapeHTML(fmtDay(s.performedAt))}</td><td class="name">${escapeHTML(s.clientName)}</td><td>${escapeHTML(s.name)}</td>
      <td>${submissionPill(s.status)}</td><td class="num right">${fmtEUR(s.totalEur)}</td></tr>`).join('');
  return recordTable([[SUB.thWhen], [SUB.thClient], [SUB.thService], [SUB.thStatus], [SUB.thTotal, 'right']], rows, { title: SUB.registeredTitle, count: subs.length });
}

// A random password to hand over: no look-alike characters, 12 of them.
function suggestPassword() {
  const chars = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  return [...crypto.getRandomValues(new Uint32Array(12))].map(n => chars[n % chars.length]).join('');
}

// Admins give an employee their own sign-in from the employee's record, and change or remove it here.
function openEmployeeAccessForm(employee, access) {
  const has = !!access?.email;
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <p class="hint">${escapeHTML(has ? SUB.accessEditIntro : signInPurpose('accessIntro', SUB.accessGiveIntro, employee.name))}</p>
    <div class="form__row form__row--full"><label class="lbl" for="ea-email">${escapeHTML(SUB.accessEmail)} <span class="req">●</span></label>
      <input class="inp" id="ea-email" type="email" autocomplete="off" maxlength="254" required value="${escapeHTML(access?.email || '')}" /></div>
    <div class="form__row form__row--full"><label class="lbl" for="ea-password">${escapeHTML(has ? SUB.accessNewPassword : STR.accountNewPassword)}${has ? ` <span class="opt">${escapeHTML(STR.optional)}</span>` : ' <span class="req">●</span>'}</label>
      <input class="inp inp--mono" id="ea-password" type="text" autocomplete="off" spellcheck="false" maxlength="72"${has ? '' : ' required'} />
      <p class="hint">${escapeHTML(has ? SUB.accessNewPasswordHint : SUB.accessPasswordHint)}</p>
      <div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-suggest-password>${escapeHTML(SUB.accessSuggest)}</button></div></div>
    ${has ? `<div class="form__row form__row--full"><label class="form__check"><input type="checkbox" id="ea-active"${access.active ? ' checked' : ''} /> ${escapeHTML(SUB.accessCanSignIn)}</label></div>` : ''}
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(has ? STR.clientSaveChanges : SUB.accessGive)}</button>
      <button class="btn btn--ghost" type="button" data-form-cancel>${escapeHTML(STR.cancel)}</button>
      ${has ? `<button class="btn btn--ghost" type="button" data-access-remove>${escapeHTML(SUB.accessRemove)}</button>` : ''}
    </div>`;
  const password = $('#ea-password', form);
  $('[data-suggest-password]', form).addEventListener('click', () => { password.value = suggestPassword(); password.focus(); password.select(); });
  $('[data-form-cancel]', form).addEventListener('click', () => closeDrawer());
  const path = `/app/api/crm/employees/${encodeURIComponent(employee.id)}/access`;
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const email = $('#ea-email', form).value.trim();
    const secret = password.value;
    if (!EMAIL_SHAPE.test(email)) return toast(SUB.accessErr_invalid_email);
    if ((!has || secret) && secret.length < 8) return toast(SUB.accessErr_password_too_short);
    const body = has ? { email, active: $('#ea-active', form).checked, ...(secret ? { password: secret } : {}) } : { email, password: secret };
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api(path, { method: has ? 'PATCH' : 'POST', body: JSON.stringify(body) });
      toast(has ? SUB.accessSaved : SUB.accessGiven({ email }));
      closeDrawer();
    } catch (err) {
      btn.disabled = false;
      if (err?.message !== 'unauthorized') toast(accessErrorText(err));
    }
  });
  $('[data-access-remove]', form)?.addEventListener('click', async () => {
    if (!await confirmDialog({ title: SUB.accessRemoveTitle({ name: employee.name }), body: SUB.accessRemoveBody, okLabel: SUB.accessRemove })) return;
    try {
      await api(path, { method: 'DELETE' });
      toast(SUB.accessRemoved);
      closeDrawer();
    } catch (err) { if (err?.message !== 'unauthorized') toast(SUB.accessFailed); }
  });
  openDrawer(has ? SUB.accessEditTitle : SUB.accessGiveTitle, form);
}

function myServiceColumns() {
  return [
    { id: 'when', label: SUB.thWhen, cell: s => `<td class="mono muted">${escapeHTML(fmtDay(s.performedAt))}</td>` },
    { id: 'client', label: SUB.thClient, cell: s => `<td class="name">${escapeHTML(s.clientName)}</td>` },
    { id: 'service', label: SUB.thService, cell: s => `<td>${escapeHTML(s.name)}</td>` },
    { id: 'total', label: SUB.thTotal, head: 'right', cell: s => `<td class="num">${fmtEUR(s.totalEur)}</td>` },
    { id: 'status', label: SUB.thStatus, cell: s => `<td>${submissionPill(s.status)}</td>` },
  ];
}

// An employee's own page: what they registered and where it stands.
function renderMyServices(root) {
  const all = state.portal.submissions || [];
  const q = state.search.trim().toLowerCase();
  const filter = state.filterSubmissionStatus || '';
  const count = status => all.filter(s => s.status === status).length;
  const columns = orderedColumns(PORTAL_TAB, myServiceColumns());
  const rows = all
    .filter(s => !filter || s.status === filter)
    .filter(s => !q || `${s.name} ${s.clientName}`.toLowerCase().includes(q))
    .map(s => `<tr class="conversation-row" data-submission="${escapeHTML(s.id)}">${columnCells(columns, s)}</tr>`)
    .join('');
  const approved = approvedThisMonth(all);
  const chips = `<button class="chip ${filter ? '' : 'is-on'}" type="button" data-filter-submission="">${escapeHTML(SUB.filterAll)}</button>`
    + SUBMISSION_STATUSES.map(s => `<button class="chip ${filter === s ? 'is-on' : ''}" type="button" data-filter-submission="${s}">${escapeHTML(SUB.status[s])}<span class="chip__count">${count(s)}</span></button>`).join('')
    + columnOrderButton(PORTAL_TAB);
  const filtered = Boolean(filter || q);
  root.innerHTML = hero(labels[PORTAL_TAB], PORTAL.desc, statCards([
    { label: SUB.status.PENDING, value: count('PENDING') },
    { label: SUB.status.REJECTED, value: count('REJECTED') },
    { label: PORTAL.statApprovedMonth, value: `${approved.length} · ${fmtEUR(sumEur(approved))}` },
  ])) + crmPanel({
    title: PORTAL.listTitle,
    tag: all.length,
    tools: chips,
    head: columnsHead(columns, PORTAL_TAB),
    rows,
    empty: filtered ? PORTAL.emptyFiltered : PORTAL.emptyTitle,
    emptyDesc: filtered ? PORTAL.emptyFilteredDesc : PORTAL.emptyDesc,
  });
  wireColumnOrder(root, PORTAL_TAB, myServiceColumns());
  $$('[data-filter-submission]', root).forEach(b => b.addEventListener('click', () => { state.filterSubmissionStatus = b.dataset.filterSubmission; render(); }));
  $$('[data-submission]', root).forEach(r => r.addEventListener('click', () => openSubmissionDetail(r.dataset.submission)));
}

// One form for an employee registering or changing their work and for the team changing it while approving
// (`review`). Same shape as the Serviços form: client, day, optional name, lines, notes.
async function openSubmissionForm(submission = null, { review = false } = {}) {
  const t = CRM.services;
  const editing = submission?.id ? submission : null;
  let clients;
  let catalog;
  if (review) {
    if (!state.clients.length && hasModule('clients')) state.clients = await api('/app/api/crm/clients').catch(() => state.clients);
    if (hasModule('catalog') && !state.catalog.length) state.catalog = await api('/app/api/crm/standard-items').catch(() => []);
    if (hasModule('bookings') && !state.bookingServices.length) state.bookingServices = await api('/app/api/bookings/services').catch(() => []);
    clients = withClient(state.clients, { id: submission.clientId, name: submission.clientName });
    catalog = serviceCatalog();
  } else {
    clients = state.portal.clients;
    catalog = (state.portal.catalog || []).map(c => (c.type === 'servico' ? { ...c, type: 'service' } : c));
  }
  const li = lineItemsField(catalog);
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${clientSelect(clients, editing?.clientId || '')}
      <div class="form__row"><label class="lbl" for="sub-when">${escapeHTML(t.when)} <span class="req">●</span></label>
        <input class="inp" id="sub-when" type="date" required${review ? '' : ` max="${todayKey()}"`} value="${escapeHTML((editing?.performedAt || todayKey()).slice(0, 10))}" /></div>
      <div class="form__row form__row--full"><label class="lbl" for="sub-name">${escapeHTML(t.name)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp" id="sub-name" maxlength="160" autocomplete="off" placeholder="${escapeHTML(t.namePh)}" value="${escapeHTML(editing?.name || '')}" />
        <p class="hint">${escapeHTML(t.nameHint)}</p></div>
    </div>
    ${li.html}
    <div class="form__row form__row--full"><label class="lbl" for="sub-notes">${escapeHTML(t.notes)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
      <textarea class="txt" id="sub-notes" maxlength="4000"${review ? '' : ` placeholder="${escapeHTML(PORTAL.notesPh)}"`}>${escapeHTML(editing?.notes || '')}</textarea></div>
    <p class="hint">${escapeHTML(review ? SUB.changeHint({ name: submission.employeeName }) : PORTAL.formHint)}</p>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(review ? SUB.saveAndApprove : editing ? STR.clientSaveChanges : PORTAL.submit)}</button>
      <button class="btn btn--ghost" type="button" data-form-cancel>${escapeHTML(STR.cancel)}</button>
    </div>`;
  $('[data-form-cancel]', form).addEventListener('click', () => closeDrawer());
  const nameInput = $('#sub-name', form);
  li.wire(form, {
    initial: editing?.items || [],
    onChange: () => { nameInput.placeholder = serviceNameFrom(li.collect(form)) || t.namePh; },
  });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const clientId = $('#f-client', form)?.value;
    if (!clientId) return toast(t.validate);
    const performedAt = $('#sub-when', form).value;
    if (!performedAt) return toast(SUB.err_date_required);
    const items = li.collect(form);
    if (!items.length) return toast(t.addLine);
    if (items.some(it => !(it.quantity > 0))) return toast(t.lineQty);
    const payload = {
      clientId,
      performedAt,
      name: nameInput.value.trim() || serviceNameFrom(items),
      notes: $('#sub-notes', form).value.trim() || null,
      items,
      catalogItemId: $('.line[data-catalog]', form)?.dataset.catalog || null,
    };
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      if (review) {
        await api(`/app/api/crm/service-submissions/${encodeURIComponent(submission.id)}/approve`, { method: 'POST', body: JSON.stringify({ changes: payload }) });
        toast(SUB.approvedToast);
        afterSubmissionDecision();
        closeDrawer();
        return;
      }
      if (editing) await api(`/app/api/portal/services/${encodeURIComponent(editing.id)}`, { method: 'PATCH', body: JSON.stringify(payload) });
      else await api('/app/api/portal/services', { method: 'POST', body: JSON.stringify(payload) });
      await loadModule(PORTAL_TAB);
      render();
      closeDrawer();
      toast(editing ? PORTAL.updated : PORTAL.created);
    } catch (err) {
      btn.disabled = false;
      if (err?.message !== 'unauthorized') toast(submissionErrorText(err));
    }
  });
  openDrawer(review ? SUB.changeTitle : editing ? PORTAL.editTitle : PORTAL.newTitle, form, true);
}

// The employee sees their own submission (change or withdraw it while pending); the team sees whose it is
// and approves it as sent, changes it first, or rejects it.
async function openSubmissionDetail(ref) {
  const id = typeof ref === 'string' ? ref : ref?.id;
  if (!id) return;
  const portal = isPortal();
  const s = portal
    ? (state.portal.submissions || []).find(x => x.id === id)
    : await api(`/app/api/crm/service-submissions/${encodeURIComponent(id)}`).catch(() => (typeof ref === 'object' ? ref : null));
  if (!s) return toast(STR.loadFailed);
  const pending = s.status === 'PENDING';
  const here = submissionTrailEntry(s);
  const clientLink = !portal && hasModule('clients') && !linksBackTo(`client:${s.clientId}`);
  const reviewed = s.reviewedAt
    ? { label: s.status === 'APPROVED' ? SUB.approvedOn : SUB.rejectedOn, value: [fmtDate(s.reviewedAt), portal ? '' : s.reviewedBy].filter(Boolean).join(' · ') }
    : null;
  const outcome = s.status === 'REJECTED'
    ? `<div class="notice notice--warn" role="status"><span>${escapeHTML(s.rejectionReason ? SUB.rejectedBecause({ reason: s.rejectionReason }) : SUB.rejectedNoReason)}</span></div>`
    : `<p class="hint">${escapeHTML(s.status === 'APPROVED' ? (s.adjusted ? SUB.approvedAdjusted : SUB.approvedHint) : portal ? PORTAL.pendingHint : SUB.pendingHint({ name: s.employeeName }))}</p>`;
  const actions = portal
    ? [
      pending ? `<button class="btn btn--sm" type="button" data-sub-edit>${escapeHTML(STR.clientEditAction)}</button>` : '',
      pending ? `<button class="btn btn--sm btn--ghost" type="button" data-sub-withdraw>${escapeHTML(PORTAL.withdraw)}</button>` : '',
    ]
    : [
      pending ? `<button class="btn btn--sm btn--accent" type="button" data-sub-approve>${escapeHTML(SUB.approve)}</button>` : '',
      pending ? `<button class="btn btn--sm" type="button" data-sub-change>${escapeHTML(SUB.changeAndApprove)}</button>` : '',
      s.serviceId && hasModule('services') && !linksBackTo(`service:${s.serviceId}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-sub-service>${escapeHTML(SUB.openService)}</button>` : '',
      !linksBackTo(`employee:${s.employeeId}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-sub-employee>${escapeHTML(SUB.openEmployee({ name: s.employeeName }))}</button>` : '',
      pending ? `<button class="btn btn--sm btn--ghost" type="button" data-sub-reject>${escapeHTML(SUB.reject)}</button>` : '',
    ];
  const tone = submissionTone(s.status);
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `
    ${detailHead(s.clientName, submissionPill(s.status), s.totalEur, tone === 'bad' ? '' : tone, clientLink)}
    ${detailMeta([
      portal ? null : { label: SUB.doneBy, value: s.employeeName },
      { label: CRM.services.when, value: fmtDay(s.performedAt) },
      { label: SUB.submittedOn, value: fmtDate(s.createdAt) },
      reviewed,
    ])}
    ${itemsTable(s.items)}
    ${s.notes ? `<p class="hint">${escapeHTML(s.notes)}</p>` : ''}
    ${outcome}
    ${actions.some(Boolean) ? `<div class="detail__foot">${actions.join('')}</div>` : ''}`;
  $('[data-detail-link]', body)?.addEventListener('click', () => openFrom(here, () => openClientDrawer(s.clientId)));
  $('[data-sub-edit]', body)?.addEventListener('click', () => openFrom(here, () => openSubmissionForm(s)));
  $('[data-sub-withdraw]', body)?.addEventListener('click', async () => {
    if (!await confirmDialog({ title: PORTAL.withdrawTitle, body: PORTAL.withdrawBody({ name: s.name }), okLabel: PORTAL.withdraw })) return;
    try {
      await api(`/app/api/portal/services/${encodeURIComponent(s.id)}`, { method: 'DELETE' });
      closeDrawer({ dismissed: true });
      await loadModule(PORTAL_TAB);
      render();
      toast(PORTAL.withdrawn);
    } catch (err) { if (err?.message !== 'unauthorized') toast(submissionErrorText(err)); }
  });
  $('[data-sub-approve]', body)?.addEventListener('click', async e => {
    const btn = e.currentTarget;
    btn.disabled = true;
    try {
      await api(`/app/api/crm/service-submissions/${encodeURIComponent(s.id)}/approve`, { method: 'POST', body: '{}' });
      toast(SUB.approvedToast);
      afterSubmissionDecision();
      openSubmissionDetail(s.id);
    } catch (err) {
      btn.disabled = false;
      if (err?.message === 'unauthorized') return;
      toast(submissionErrorText(err));
      if (err?.code === 'not_pending') openSubmissionDetail(s.id);
    }
  });
  $('[data-sub-change]', body)?.addEventListener('click', () => openFrom(here, () => openSubmissionForm(s, { review: true })));
  $('[data-sub-reject]', body)?.addEventListener('click', () => openFrom(here, () => openRejectForm(s)));
  $('[data-sub-service]', body)?.addEventListener('click', () => openFrom(here, () => openServiceDetail(s.serviceId)));
  $('[data-sub-employee]', body)?.addEventListener('click', () => openFrom(here, () => openPayeeDrawer('employee', s.employeeId)));
  openDrawer(s.name, body, false, { eyebrow: SUB.eyebrow });
}

function openRejectForm(s) {
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <p class="hint">${escapeHTML(SUB.rejectIntro({ name: s.employeeName }))}</p>
    <div class="form__row form__row--full"><label class="lbl" for="sub-reason">${escapeHTML(SUB.rejectReason)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
      <textarea class="txt" id="sub-reason" maxlength="500" placeholder="${escapeHTML(SUB.rejectReasonPh)}"></textarea></div>
    <div class="actions">
      <button class="btn btn--danger" type="submit">${escapeHTML(SUB.reject)}</button>
      <button class="btn btn--ghost" type="button" data-form-cancel>${escapeHTML(STR.cancel)}</button>
    </div>`;
  $('[data-form-cancel]', form).addEventListener('click', () => closeDrawer());
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api(`/app/api/crm/service-submissions/${encodeURIComponent(s.id)}/reject`, {
        method: 'POST', body: JSON.stringify({ reason: $('#sub-reason', form).value.trim() || null }),
      });
      toast(SUB.rejectedToast);
      afterSubmissionDecision();
      closeDrawer();
    } catch (err) {
      btn.disabled = false;
      if (err?.message !== 'unauthorized') toast(submissionErrorText(err));
    }
  });
  openDrawer(SUB.rejectTitle({ name: s.name }), form);
}
