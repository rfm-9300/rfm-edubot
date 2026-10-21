/* Site diary (/app → Site diary): which running projects reported today, the diary of the last 30 days (or of
   one project), an entry's form (weather, people on site, work done, phase progress, photos…) and its detail. */

function diaryRowHtml(e) {
  const excerpt = e.workDone.length > 120 ? `${e.workDone.slice(0, 119)}…` : e.workDone;
  return `<tr class="conversation-row" data-diary="${escapeHTML(e.id)}" tabindex="0">
    <td class="name long">${escapeHTML(e.projectName)}<div class="sub mono">${escapeHTML(e.projectNumber)}</div></td>
    <td${cellLabel(BLD.thWeather)}>${escapeHTML([weatherLabel(e.weather), e.temperatureC != null ? `${e.temperatureC}\u00a0°C` : ''].filter(Boolean).join(' · ') || '—')}</td>
    <td class="num"${cellLabel(BLD.thWorkers)}>${e.workers ?? '—'}</td>
    <td class="long"${cellLabel(BLD.thWorkDone)}>${escapeHTML(excerpt)}</td>
    <td class="num"${cellLabel(BLD.thPhotos)}>${e.photos.length || '—'}</td>
    <td${cellLabel(BLD.diaryShared)}>${e.shared ? bldPill(BLD.diarySharedYes, 'info') : '<span class="muted">—</span>'}</td>
  </tr>`;
}

function renderSiteDiary(root) {
  const today = todayKey();
  const weekStart = periodKey(today, 'week');
  const entries = state.diary;
  const q = foldText(state.search);
  const shown = entries.filter(e => !q || foldText(`${e.projectNumber} ${e.projectName} ${e.workDone} ${e.incidents || ''} ${e.materials || ''}`).includes(q));
  const active = state.projects.filter(p => p.status === 'ACTIVE');
  const todays = entries.filter(e => e.day === today);
  const week = entries.filter(e => e.day >= weekStart);
  const stats = [
    { label: BLD.diaryStatToday, value: bt('diaryOfN', { n: active.filter(p => todays.some(e => e.projectId === p.id)).length, total: active.length }) },
    { label: BLD.diaryStatWeek, value: week.length },
    { label: BLD.diaryStatWorkers, value: todays.reduce((n, e) => n + (e.workers || 0), 0) },
    { label: BLD.diaryStatLost, value: fmtQty(week.reduce((n, e) => n + (e.lostHours || 0), 0)) },
  ];
  const board = !state.diaryProject && active.length ? `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(BLD.diaryToday)} <span class="tag">${active.length}</span></h2>
    <span class="panel__meta">${escapeHTML(capFirst(dayKeyDate(today).toLocaleDateString(uiLocale(), { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' })))}</span></header>
    <ul class="worklist">${active.map(p => {
      const entry = todays.find(e => e.projectId === p.id);
      return recordRowHtml(entry
        ? { tone: 'ok', title: projectLabel(p), detail: [weatherLabel(entry.weather), entry.workers != null ? bt('diaryOnSite', { n: entry.workers }) : '', entry.workDone].filter(Boolean).join(' · '), pill: bldPill(BLD.diaryReported, 'ok'), open: `entry:${entry.id}` }
        : { tone: 'warn', title: projectLabel(p), detail: BLD.diaryNotYet, pill: bldPill(BLD.diaryWrite, 'warn'), open: `new:${p.id}` });
    }).join('')}</ul></section>` : '';
  let rows = '';
  let day = '';
  shown.forEach(e => {
    if (e.day !== day) {
      day = e.day;
      const label = capFirst(dayKeyDate(day).toLocaleDateString(uiLocale(), { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' }));
      rows += `<tr class="is-day"><td colspan="6">${escapeHTML(relativeDayLabel(day) || label)}</td></tr>`;
    }
    rows += diaryRowHtml(e);
  });
  const tools = projectFilterHtml(state.diaryProject, 'data-diary-project')
    + (state.diaryProject ? `<button class="btn btn--sm btn--ghost" type="button" data-diary-pdf>${escapeHTML(BLD.diaryPdf)}</button>` : '');
  root.innerHTML = hero(labels['site-diary'], BLD.diaryDesc, statCards(stats)) + board + bldPanel({
    title: state.diaryProject ? BLD.diaryAllEntries : BLD.diaryRecent,
    tag: shown.length,
    tools,
    head: `<tr><th>${escapeHTML(BLD.thProject)}</th><th>${escapeHTML(BLD.thWeather)}</th><th class="right">${escapeHTML(BLD.thWorkers)}</th><th>${escapeHTML(BLD.thWorkDone)}</th><th class="right">${escapeHTML(BLD.thPhotos)}</th><th>${escapeHTML(BLD.diaryShared)}</th></tr>`,
    rows,
    empty: state.projects.length ? BLD.diaryEmpty : BLD.diaryNoProjects,
    emptyDesc: state.projects.length ? BLD.diaryEmptyDesc : BLD.diaryNoProjectsDesc,
  });
  $$('[data-record-open]', root).forEach(el => el.addEventListener('click', () => {
    const [kind, id] = el.dataset.recordOpen.split(':');
    if (kind === 'entry') openDiaryEntry(id);
    else openDiaryForm(null, { projectId: id });
  }));
  $('[data-diary-project]', root)?.addEventListener('change', async e => {
    state.diaryProject = e.target.value;
    try { await loadModule('site-diary'); } catch { toast(STR.loadFailed); }
    render();
  });
  $('[data-diary-pdf]', root)?.addEventListener('click', () => {
    const project = state.projects.find(p => p.id === state.diaryProject);
    if (project) openDiaryPdfForm(project);
  });
  $$('[data-diary]', root).forEach(row => {
    row.addEventListener('click', () => openDiaryEntry(row.dataset.diary));
    row.addEventListener('keydown', e => { if (e.key === 'Enter') openDiaryEntry(row.dataset.diary); });
  });
}

const DIARY_PDF_MAX_DAYS = 92;

// A project's diary for a period, as a PDF: up to three months, with some of each day's photos.
function openDiaryPdfForm(project) {
  const to = todayKey();
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `<p class="hint">${escapeHTML(BLD.diaryPdfIntro)}</p>
    <div class="form__grid">
      <div class="form__row"><label class="lbl" for="dp-from">${escapeHTML(BLD.diaryPdfFrom)}</label><input class="inp inp--mono" id="dp-from" type="date" required value="${addDayKey(to, -29)}" max="${to}" /></div>
      <div class="form__row"><label class="lbl" for="dp-to">${escapeHTML(BLD.diaryPdfTo)}</label><input class="inp inp--mono" id="dp-to" type="date" required value="${to}" max="${to}" /></div>
    </div>
    <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${escapeHTML(BLD.cancel)}</button><button class="btn btn--accent" type="submit">${escapeHTML(BLD.diaryPdfDownload)}</button></div>`;
  bindDrawerClose(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const from = $('#dp-from', form).value;
    const until = $('#dp-to', form).value;
    if (!from || !until || until < from) return toast(bt('errors.invalid_range'));
    if (dayNumber(until) - dayNumber(from) >= DIARY_PDF_MAX_DAYS) return toast(bt('errors.range_too_long'));
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await openPdf(`/app/api/building/diary/pdf?projectId=${encodeURIComponent(project.id)}&from=${from}&to=${until}`, `${project.number} ${labels['site-diary']} ${from} ${until}`);
      closeDrawer();
    } catch (err) {
      btn.disabled = false;
      toast(STR.errorPdf({ msg: err.message }));
    }
  });
  openDrawer(BLD.diaryPdfTitle, form, false, { eyebrow: projectLabel(project) });
}

// The phases a diary entry can move, each with the progress it has now.
function diaryPhasesHtml(project) {
  const open = (project?.phases || []);
  if (!open.length) return '';
  return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(BLD.diaryProgress)}</h2></header>
    <div class="panel__body form">
      <p class="hint">${escapeHTML(BLD.diaryProgressHint)}</p>
      <div class="form__grid">${open.map(ph => `<div class="form__row"><label class="lbl" for="dp-${escapeHTML(ph.id)}">${escapeHTML(ph.name)}</label>
        <div class="inp-unit"><input class="inp inp--mono inp--right" id="dp-${escapeHTML(ph.id)}" type="number" min="0" max="100" step="5" data-diary-phase="${escapeHTML(ph.id)}" data-was="${ph.progressPct}" value="${ph.progressPct}" /><span class="inp-unit__label">%</span></div></div>`).join('')}</div>
    </div></section>`;
}

// `opts.projectId` and `opts.day` start a new entry on that project and day (today by default).
async function openDiaryForm(entry = null, opts = {}) {
  await ensureBuildingProjects().catch(() => {});
  const e = entry || {};
  const projectId = entry ? entry.projectId : opts.projectId || state.diaryProject || '';
  const form = document.createElement('form');
  form.className = 'form';
  let weather = e.weather || '';
  const queued = [];
  const text = (id, label, value, rows = '') => `<div class="form__row form__row--full"><label class="lbl" for="${id}">${escapeHTML(label)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
    <textarea class="txt" id="${id}" maxlength="4000"${rows}>${escapeHTML(value || '')}</textarea></div>`;
  form.innerHTML = `
    <div class="form__grid">
      ${entry ? '' : projectSelectHtml('dy-project', projectId)}
      <div class="form__row"><label class="lbl" for="dy-day">${escapeHTML(BLD.thDay)}</label><input class="inp inp--mono" id="dy-day" type="date" max="${todayKey()}" value="${escapeHTML(e.day || opts.day || todayKey())}" /></div>
    </div>
    <div class="form__row"><span class="lbl" id="dy-weather-label">${escapeHTML(BLD.thWeather)}</span>
      <div class="chip-picks" role="group" aria-labelledby="dy-weather-label">${WEATHERS.map(w => `<button class="chip${weather === w ? ' is-on' : ''}" type="button" aria-pressed="${weather === w}" data-weather="${w}">${escapeHTML(weatherLabel(w))}</button>`).join('')}</div></div>
    <div class="form__grid form__grid--3">
      <div class="form__row"><label class="lbl" for="dy-temp">${escapeHTML(BLD.diaryTemperature)}</label><div class="inp-unit"><input class="inp inp--mono inp--right" id="dy-temp" type="number" min="-50" max="60" step="1" value="${e.temperatureC ?? ''}" /><span class="inp-unit__label">°C</span></div></div>
      <div class="form__row"><label class="lbl" for="dy-workers">${escapeHTML(BLD.thWorkers)}</label><input class="inp inp--mono inp--right" id="dy-workers" type="number" min="0" max="10000" step="1" value="${e.workers ?? ''}" /></div>
      <div class="form__row"><label class="lbl" for="dy-lost">${escapeHTML(BLD.diaryLostHours)}</label><input class="inp inp--mono inp--right" id="dy-lost" type="number" min="0" step="0.5" value="${e.lostHours ?? ''}" /></div>
    </div>
    <div class="form__row form__row--full"><label class="lbl" for="dy-work">${escapeHTML(BLD.thWorkDone)} <span class="req">●</span></label>
      <textarea class="txt" id="dy-work" maxlength="4000" required placeholder="${escapeHTML(BLD.diaryWorkPh)}">${escapeHTML(e.workDone || '')}</textarea></div>
    <div data-diary-phases></div>
    <div class="form__grid">
      ${text('dy-materials', BLD.diaryMaterials, e.materials)}
      ${text('dy-equipment', BLD.diaryEquipment, e.equipment)}
      ${text('dy-visitors', BLD.diaryVisitors, e.visitors)}
      ${text('dy-incidents', BLD.diaryIncidents, e.incidents)}
    </div>
    <label class="form__check"><input type="checkbox" id="dy-shared" ${e.shared ? 'checked' : ''} /> ${escapeHTML(BLD.diaryShareCheck)}</label>
    <p class="hint">${escapeHTML(BLD.diaryShareHint)}</p>
    <div class="form__row"><div class="form__row-head"><span class="lbl">${escapeHTML(bt('tab.photos'))}</span><div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-diary-photos>${escapeHTML(BLD.photosAdd)}</button></div></div>
      <p class="hint" data-diary-queued>${escapeHTML(BLD.diaryPhotosHint)}</p></div>
    <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${escapeHTML(BLD.cancel)}</button><button class="btn btn--accent" type="submit">${escapeHTML(entry ? BLD.save : BLD.diarySave)}</button></div>`;
  const phasesBox = $('[data-diary-phases]', form);
  let project = null;
  const loadPhases = async id => {
    phasesBox.innerHTML = '';
    project = null;
    if (!id) return;
    try { project = await api(`/app/api/building/projects/${encodeURIComponent(id)}`); } catch { return; }
    phasesBox.innerHTML = diaryPhasesHtml(project);
  };
  loadPhases(projectId);
  $('#dy-project', form)?.addEventListener('change', ev => loadPhases(ev.target.value));
  $$('[data-weather]', form).forEach(b => b.addEventListener('click', () => {
    weather = weather === b.dataset.weather ? '' : b.dataset.weather;
    $$('[data-weather]', form).forEach(x => { x.classList.toggle('is-on', x.dataset.weather === weather); x.setAttribute('aria-pressed', String(x.dataset.weather === weather)); });
  }));
  $('[data-diary-photos]', form).addEventListener('click', async () => {
    queued.push(...await pickPhotoFiles());
    $('[data-diary-queued]', form).textContent = queued.length ? bt('photos.queued', { n: queued.length }) : BLD.diaryPhotosHint;
  });
  bindDrawerClose(form);
  form.addEventListener('submit', async ev => {
    ev.preventDefault();
    const target = entry ? entry.projectId : $('#dy-project', form).value;
    if (!target) return toast(BLD.chooseProject);
    const workDone = $('#dy-work', form).value.trim();
    if (!workDone) return toast(bt('errors.work_required'));
    const num = id => { const v = $(id, form).value; return v === '' ? null : Number(v); };
    const payload = {
      day: $('#dy-day', form).value,
      weather,
      temperatureC: num('#dy-temp'),
      workers: num('#dy-workers'),
      lostHours: num('#dy-lost'),
      workDone,
      materials: $('#dy-materials', form).value,
      equipment: $('#dy-equipment', form).value,
      visitors: $('#dy-visitors', form).value,
      incidents: $('#dy-incidents', form).value,
      shared: $('#dy-shared', form).checked,
      phaseProgress: $$('[data-diary-phase]', form)
        .filter(i => i.value !== '' && Number(i.value) !== Number(i.dataset.was))
        .map(i => ({ phaseId: i.dataset.diaryPhase, progressPct: clampPct(i.value) })),
    };
    if (entry) payload.clear = ['temperatureC', 'workers', 'lostHours'].filter(k => payload[k] == null);
    else payload.projectId = target;
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const saved = await api(entry ? `/app/api/building/diary/${encodeURIComponent(entry.id)}` : '/app/api/building/diary', {
        method: entry ? 'PATCH' : 'POST',
        body: JSON.stringify(payload),
      });
      if (queued.length) await uploadProjectPhotos(target, queued, { logId: saved.id, shared: payload.shared ? 'true' : 'false' });
      toast(entry ? BLD.diaryUpdated : BLD.diarySaved);
      closeDrawer();
      refreshBuildingPage();
    } catch (err) {
      btn.disabled = false;
      if (err?.code === 'day_taken' && err.detail) {
        toast(bt('errors.day_taken'));
        return openDiaryEntry(err.detail);
      }
      toast(buildingErrorText(err, BLD.saveFailed));
    }
  });
  openDrawer(entry ? bt('diaryEditTitle', { day: fmtDay(entry.day) }) : BLD.diaryNew, form, true, { eyebrow: entry ? projectLabel({ number: entry.projectNumber, name: entry.projectName }) : labels['site-diary'] });
}

async function openDiaryEntry(id) {
  let entry;
  try { entry = await api(`/app/api/building/diary/${encodeURIComponent(id)}`); } catch { return toast(STR.loadFailed); }
  const here = { key: `diary:${entry.id}`, label: fmtDay(entry.day), open: () => openDiaryEntry(entry.id) };
  const projectLink = !linksBackTo(`project:${entry.projectId}`);
  const body = document.createElement('div');
  body.className = 'record';
  const draw = () => {
    body.innerHTML = `
      <div class="detail__head">
        ${projectLink ? `<button class="detail__client detail__client--link" type="button" data-diary-project>${escapeHTML(entry.projectName)}</button>` : `<div class="detail__client">${escapeHTML(entry.projectName)}</div>`}
        <div class="detail__amount">${entry.shared ? bldPill(BLD.diarySharedYes, 'info') : bldPill(BLD.diaryInternal, '')}</div>
      </div>
      ${dashFacts([
        [BLD.thWeather, [weatherLabel(entry.weather), entry.temperatureC != null ? `${entry.temperatureC}\u00a0°C` : ''].filter(Boolean).join(' · ') || '—'],
        [BLD.thWorkers, entry.workers ?? '—'],
        entry.lostHours ? [BLD.diaryLostHours, fmtQty(entry.lostHours)] : null,
        [BLD.diaryWrittenBy, entry.updatedBy !== entry.createdBy ? `${entry.createdBy} · ${entry.updatedBy}` : entry.createdBy],
      ])}
      ${textPanel(BLD.thWorkDone, entry.workDone)}
      ${entry.progress.length ? `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(BLD.diaryProgress)}</h2></header><ul class="worklist">${entry.progress.map(c => `<li><button class="worklist__item" type="button" disabled data-tone="${c.toPct >= 100 ? 'ok' : 'accent'}"><span class="worklist__dot"></span><span class="worklist__main"><span class="worklist__title">${escapeHTML(c.phaseName)}</span></span><span class="worklist__side"><span class="worklist__meta">${escapeHTML(`${c.fromPct}% → ${c.toPct}%`)}</span></span></button></li>`).join('')}</ul></section>` : ''}
      ${textPanel(BLD.diaryMaterials, entry.materials)}
      ${textPanel(BLD.diaryEquipment, entry.equipment)}
      ${textPanel(BLD.diaryVisitors, entry.visitors)}
      ${textPanel(BLD.diaryIncidents, entry.incidents)}
      <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(bt('tab.photos'))} <span class="tag">${entry.photos.length}</span></h2>
        <div class="panel__tools"><button class="btn btn--sm btn--ghost" type="button" data-diary-add-photos>${escapeHTML(BLD.photosAdd)}</button></div></header>
        <div class="panel__body">${entry.photos.length ? photoGridHtml(entry.photos) : `<p class="dash-empty">${escapeHTML(BLD.diaryNoPhotos)}</p>`}</div></section>
      <div class="detail__foot">
        <button class="btn btn--sm btn--accent" type="button" data-diary-edit>${escapeHTML(BLD.edit)}</button>
        <button class="btn btn--sm btn--ghost" type="button" data-diary-delete>${escapeHTML(BLD.delete)}</button>
      </div>`;
    $('[data-diary-project]', body)?.addEventListener('click', () => openFrom(here, () => openProjectDrawer(entry.projectId, 'diary')));
    $('[data-diary-edit]', body).addEventListener('click', () => openFrom(here, () => openDiaryForm(entry)));
    $('[data-diary-add-photos]', body).addEventListener('click', () => addPhotosTo(entry.projectId, { logId: entry.id, shared: entry.shared ? 'true' : 'false' }, reload));
    $('[data-diary-delete]', body).addEventListener('click', async () => {
      if (!await confirmDialog({ title: BLD.diaryDeleteTitle, body: BLD.diaryDeleteBody, okLabel: BLD.delete })) return;
      try {
        await api(`/app/api/building/diary/${encodeURIComponent(entry.id)}`, { method: 'DELETE' });
        toast(BLD.diaryDeleted);
        closeDrawer();
        refreshBuildingPage();
      } catch (err) { toast(buildingErrorText(err, BLD.saveFailed)); }
    });
    wirePhotoGrid(body, entry.photos, here, reload);
  };
  const reload = async () => {
    try { entry = await api(`/app/api/building/diary/${encodeURIComponent(entry.id)}`); } catch { return; }
    if (body.isConnected) draw();
    refreshBuildingPage();
  };
  draw();
  openDrawer(capFirst(dayKeyDate(entry.day).toLocaleDateString(uiLocale(), { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' })), body, true, { eyebrow: projectLabel({ number: entry.projectNumber, name: entry.projectName }) });
}
