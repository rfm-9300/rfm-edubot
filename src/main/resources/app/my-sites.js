/* My sites (/app → My sites), on a site manager's own sign-in: the projects they run, whether today's diary is
   written, and each site's phases, diary, issues and photos, all through /app/api/portal. No money anywhere. */

const mySite = (key, params) => bt(`mySites.${key}`, params);

function renderMySites(root) {
  const sites = state.mySites || [];
  const written = sites.filter(s => s.todayEntryId).length;
  const stats = [
    { label: mySite('statSites'), value: sites.length },
    { label: mySite('statToday'), value: bt('diaryOfN', { n: written, total: sites.length }) },
    { label: mySite('statIssues'), value: sites.reduce((n, s) => n + s.openIssues, 0) },
  ];
  const rows = sites.map(s => recordRowHtml({
    tone: s.todayEntryId ? 'ok' : 'warn',
    title: projectLabel(s),
    detail: [s.address, mySite('progress', { pct: s.progressPct }), s.openIssues ? mySite('openIssues', { n: s.openIssues }) : ''].filter(Boolean).join(' · '),
    pill: bldPill(s.todayEntryId ? BLD.diaryReported : BLD.diaryWrite, s.todayEntryId ? 'ok' : 'warn'),
    open: s.id,
  })).join('');
  root.innerHTML = hero(labels['my-sites'], mySite('desc'), statCards(stats)) + (sites.length
    ? `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(mySite('title'))} <span class="tag">${sites.length}</span></h2></header><ul class="worklist">${rows}</ul></section>`
    : `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(mySite('empty'))}</p><p class="empty__desc">${escapeHTML(mySite('emptyDesc'))}</p></div></div>`);
  $$('[data-record-open]', root).forEach(el => el.addEventListener('click', () => openMySite(el.dataset.recordOpen)));
}

async function reloadMySites() {
  if (state.active !== SITES_TAB) return;
  try { await loadModule(SITES_TAB); } catch { /* keep what is shown */ }
  render();
}

async function openMySite(id) {
  let d;
  try { d = await api(`/app/api/portal/sites/${encodeURIComponent(id)}`); } catch { return toast(STR.loadFailed); }
  const s = d.site;
  const here = { key: `my-site:${s.id}`, label: s.number, open: () => openMySite(s.id) };
  const today = d.diary.find(e => e.day === d.today);
  const earlier = d.diary.filter(e => e.day !== d.today);
  const saved = () => reloadMySites();
  const diaryForm = entry => openFrom(here, () => openDiaryForm(entry, { site: s, phases: d.phases, onSaved: saved }));
  const body = document.createElement('div');
  body.className = 'record';
  const todayPanel = !d.canDiary ? '' : `<section class="panel" data-site-panel="diary"><header class="panel__head"><h2 class="panel__title">${escapeHTML(BLD.diaryToday)}</h2>
      ${today ? `<div class="panel__tools"><button class="btn btn--sm btn--ghost" type="button" data-site-diary="${escapeHTML(today.id)}">${escapeHTML(BLD.edit)}</button></div>` : ''}</header>
    ${today
      ? `<div class="panel__body">${dashFacts([[BLD.thWeather, weatherLabel(today.weather) || '—'], [BLD.thWorkers, today.workers ?? '—']], true)}<p class="panel__text">${escapeHTML(today.workDone)}</p></div>`
      : `<div class="empty"><p class="empty__title">${escapeHTML(BLD.diaryNotYet)}</p><div class="empty__actions"><button class="btn btn--sm btn--primary" type="button" data-site-diary="">${escapeHTML(BLD.diaryNew)}</button></div></div>`}
    ${earlier.length ? `<ul class="worklist">${earlier.map(e => recordRowHtml({ tone: 'neutral', title: capFirst(fmtDay(e.day)), detail: e.workDone, when: relDay(e.day), open: e.id })).join('')}</ul>` : ''}</section>`;
  const phases = d.phases.length ? `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(BLD.scheduleTitle)} <span class="tag">${d.phases.length}</span></h2></header>
    <div class="panel__body">${d.phases.map(ph => `<div class="form__row"><span class="lbl">${escapeHTML(ph.name)}</span>${progressCellHtml(ph.progressPct, null, PHASE_TONES[ph.state])}</div>`).join('')}</div></section>` : '';
  const issues = !d.canIssues ? '' : `<section class="panel" data-site-panel="issues"><header class="panel__head"><h2 class="panel__title">${escapeHTML(labels['site-issues'])} <span class="tag">${d.issues.length}</span></h2>
      <div class="panel__tools"><button class="btn btn--sm" type="button" data-site-issue-new><span class="btn__plus">+</span> ${escapeHTML(BLD.issueNew)}</button></div></header>
    ${d.issues.length ? `<ul class="worklist">${d.issues.map(i => recordRowHtml({
      tone: i.overdue ? 'bad' : i.status === 'RESOLVED' ? 'ok' : 'warn',
      title: i.title,
      detail: [i.number, i.location, i.dueDate ? fmtDay(i.dueDate) : ''].filter(Boolean).join(' · '),
      pill: issueStatusPill(i.status, i.overdue),
      open: i.id,
    })).join('')}</ul>` : `<p class="dash-empty">${escapeHTML(BLD.issuesEmpty)}</p>`}</section>`;
  const photos = `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(bt('tab.photos'))} <span class="tag">${d.photos.length}</span></h2>
      <div class="panel__tools"><button class="btn btn--sm btn--ghost" type="button" data-site-photos>${escapeHTML(BLD.photosAdd)}</button></div></header>
    <div class="panel__body">${d.photos.length ? photoGridHtml(d.photos) : `<p class="dash-empty">${escapeHTML(BLD.photosEmpty)}</p>`}</div></section>`;
  body.innerHTML = `
    ${recordCardHtml({ name: s.name, lines: s.address ? `<a class="record-card__line" href="${escapeHTML(mapsHref(s.address))}" target="_blank" rel="noopener">${escapeHTML(s.address)}</a>` : `<span class="record-card__line mono">${escapeHTML(s.number)}</span>`, since: mySite('progress', { pct: s.progressPct }) })}
    <div class="record__stack">${todayPanel}${phases}${issues}${photos}</div>`;
  hydratePhotos(body);
  $('[data-site-diary]', body)?.addEventListener('click', () => diaryForm(today || null));
  $$('[data-site-panel="diary"] [data-record-open]', body).forEach(el => el.addEventListener('click', () => {
    const entry = earlier.find(e => e.id === el.dataset.recordOpen);
    if (entry) diaryForm(entry);
  }));
  $('[data-site-issue-new]', body)?.addEventListener('click', () => openFrom(here, () => openMySiteIssueForm(s, null, saved)));
  $$('[data-site-panel="issues"] [data-record-open]', body).forEach(el => el.addEventListener('click', () => {
    const issue = d.issues.find(i => i.id === el.dataset.recordOpen);
    if (issue) openFrom(here, () => openMySiteIssueForm(s, issue, saved));
  }));
  $('[data-site-photos]', body).addEventListener('click', () => addPhotosTo(s.id, today ? { logId: today.id } : {}, () => { saved(); openMySite(s.id); }));
  openDrawer(s.name, body, true, { eyebrow: [labels['my-sites'], s.number].join(' · ') });
}

// Logs an issue on the site, or moves one: started, fixed, or edited.
function openMySiteIssueForm(site, issue, onSaved) {
  const i = issue || {};
  const form = document.createElement('form');
  form.className = 'form';
  const next = { OPEN: ['IN_PROGRESS', BLD.issueStart], IN_PROGRESS: ['RESOLVED', BLD.issueResolve] }[i.status];
  form.innerHTML = `
    ${issue ? `<div class="detail__head"><div class="detail__client">${escapeHTML(i.number)}</div><div class="detail__amount">${issueStatusPill(i.status, i.overdue)}</div></div>` : ''}
    <div class="form__grid">
      <div class="form__row form__row--full"><label class="lbl" for="mi-title">${escapeHTML(BLD.issueTitle)} <span class="req">●</span></label>
        <input class="inp" id="mi-title" maxlength="160" required value="${escapeHTML(i.title || '')}" placeholder="${escapeHTML(BLD.issueTitlePh)}" /></div>
      <div class="form__row"><label class="lbl" for="mi-location">${escapeHTML(BLD.issueLocation)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp" id="mi-location" maxlength="160" value="${escapeHTML(i.location || '')}" placeholder="${escapeHTML(BLD.issueLocationPh)}" /></div>
      <div class="form__row"><label class="lbl" for="mi-priority">${escapeHTML(BLD.thPriority)}</label>
        <select class="sel" id="mi-priority">${ISSUE_PRIORITIES.map(p => `<option value="${p}"${(i.priority || 'MEDIUM') === p ? ' selected' : ''}>${escapeHTML(issuePriorityLabel(p))}</option>`).join('')}</select></div>
      <div class="form__row"><label class="lbl" for="mi-due">${escapeHTML(BLD.thDue)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp inp--mono" id="mi-due" type="date" value="${escapeHTML(i.dueDate || '')}" /></div>
      <div class="form__row form__row--full"><label class="lbl" for="mi-desc">${escapeHTML(BLD.formDescription)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <textarea class="txt" id="mi-desc" maxlength="4000">${escapeHTML(i.description || '')}</textarea></div>
    </div>
    <div class="drawer__foot">
      ${next ? `<button class="btn btn--sm btn--primary" type="button" data-issue-next="${next[0]}">${escapeHTML(next[1])}</button>` : ''}
      <button class="btn btn--ghost" type="button" data-close>${escapeHTML(BLD.cancel)}</button><button class="btn btn--accent" type="submit">${escapeHTML(issue ? BLD.save : BLD.issueSave)}</button>
    </div>`;
  const base = `/app/api/portal/sites/${encodeURIComponent(site.id)}/issues`;
  const send = async (payload, done, btn) => {
    btn.disabled = true;
    try {
      await api(issue ? `${base}/${encodeURIComponent(issue.id)}` : base, { method: issue ? 'PATCH' : 'POST', body: JSON.stringify(payload) });
      toast(done);
      closeDrawer();
      onSaved?.();
    } catch (err) {
      btn.disabled = false;
      toast(buildingErrorText(err, BLD.saveFailed));
    }
  };
  $('[data-issue-next]', form)?.addEventListener('click', e => {
    const status = e.currentTarget.dataset.issueNext;
    send({ status }, bt('issueNow', { status: issueStatusLabel(status) }), e.currentTarget);
  });
  bindDrawerClose(form);
  form.addEventListener('submit', e => {
    e.preventDefault();
    const title = $('#mi-title', form).value.trim();
    if (!title) return toast(bt('errors.title_required'));
    send({
      title,
      location: $('#mi-location', form).value.trim(),
      priority: $('#mi-priority', form).value,
      dueDate: $('#mi-due', form).value,
      description: $('#mi-desc', form).value.trim(),
    }, issue ? BLD.issueUpdated : BLD.issueSaved, $('button[type=submit]', form));
  });
  openDrawer(issue ? i.title : BLD.issueNew, form, true, { eyebrow: projectLabel(site) });
}
