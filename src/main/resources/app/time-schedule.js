/* Time clock, schedules (/app): the team's week planner (employees × days) with how each planned shift went,
   planning several days at once, copying a week and excusing an absence; and the planned shifts an employee sees
   on My hours. timesheets.js mounts it with its helpers (init(kit)); the server applies every rule. */
(function () {
  let k = null;
  const plan = { data: null, sites: null };

  const esc = s => k.esc(s);
  const $ = (sel, root) => k.$(sel, root);
  const $$ = (sel, root) => k.$$(sel, root);
  const STATE_TONES = { UPCOMING: '', DUE: 'warn', MISSING: 'bad', ABSENT: 'bad', WORKING: 'info', ATTENDED: 'ok', EXCUSED: '' };
  const EVENT_CLASS = { DUE: 'week-event--pending', MISSING: 'week-event--no-show', ABSENT: 'week-event--no-show', WORKING: 'week-event--confirmed', EXCUSED: 'week-event--cancelled' };

  function init(kit) {
    k = kit;
  }

  const times = p => `${p.startTime}⁠–⁠${p.endTime}${p.endsNextDay ? ` ${k.T.nextDay}` : ''}`;
  const late = a => a.late || a.leftEarly;

  /** How a planned shift went, in a few words: "Worked · 12 min late", "Absent", "Due". */
  function attendanceText(p) {
    const a = p.attendance;
    const parts = [k.known(`attendance.${a.state}`, a.state)];
    if (a.late) parts.push(k.tr('lateBy', { time: k.hm(a.lateMinutes) }));
    if (a.leftEarly) parts.push(k.tr('leftEarlyBy', { time: k.hm(a.earlyLeaveMinutes) }));
    return parts.join(' · ');
  }

  function attendancePill(p) {
    const a = p.attendance;
    const tone = (a.state === 'ATTENDED' || a.state === 'WORKING') && late(a) ? 'warn' : STATE_TONES[a.state] ?? '';
    return k.pill(attendanceText(p), tone);
  }

  // ── The team's planner ─────────────────────────────────────────────────────────────────────────

  async function load(weekStart) {
    const to = k.d.addDayKey(weekStart, 6);
    plan.data = await k.d.api(`/app/api/timesheets/schedule?from=${weekStart}&to=${to}`);
  }

  /** The hero's figures while the planner shows: planned hours, how many were worked, late arrivals, absences. */
  function stats() {
    const list = (plan.data?.planned || []).filter(p => !p.excuse);
    const count = state => list.filter(p => p.attendance.state === state).length;
    const planned = list.reduce((sum, p) => sum + p.minutes, 0);
    return [
      { label: k.T.statPlanned, value: planned ? k.hm(planned) : '—' },
      { label: k.T.statAttended, value: `${count('ATTENDED') + count('WORKING')}/${list.filter(p => p.attendance.state !== 'UPCOMING').length}` },
      { label: k.T.statLate, value: list.filter(p => p.attendance.late).length },
      { label: k.T.statAbsent, value: count('ABSENT') + count('MISSING') },
    ];
  }

  function plannerHtml(weekStart) {
    const data = plan.data;
    if (!data) return '';
    const days = Array.from({ length: 7 }, (_, i) => k.d.addDayKey(weekStart, i));
    const today = k.d.todayKey();
    const known = new Map((k.team.employees || []).filter(e => !e.archivedAt).map(e => [e.id, e.name]));
    data.planned.forEach(p => { if (!known.has(p.employeeId)) known.set(p.employeeId, p.employeeName || ''); });
    const people = [...known.entries()].sort((a, b) => a[1].localeCompare(b[1], k.d.uiLocale()));
    const byCell = new Map();
    data.planned.forEach(p => {
      const key = `${p.employeeId}|${p.day}`;
      if (!byCell.has(key)) byCell.set(key, []);
      byCell.get(key).push(p);
    });
    const dayLabel = day => k.d.fmtDayKey(day, { weekday: 'short', day: 'numeric' });
    const head = `<tr><th>${esc(k.T.thEmployee)}</th>${days.map(day => `<th class="planner__day${day === today ? ' is-today' : ''}">${esc(dayLabel(day))}</th>`).join('')}<th class="right">${esc(k.T.planHours)}</th></tr>`;
    const rows = people.map(([id, name]) => {
      const cells = days.map(day => {
        const plans = byCell.get(`${id}|${day}`) || [];
        const events = plans.map(p => {
          const where = [p.siteName, p.job ? window.TimeJobsUI.label(p.job) : ''].filter(Boolean).join(' · ');
          const sub = p.attendance.state === 'UPCOMING' ? where || p.note || '' : attendanceText(p);
          const cls = p.attendance.state === 'ATTENDED' ? (late(p.attendance) ? 'week-event--pending' : 'week-event--completed') : EVENT_CLASS[p.attendance.state] || '';
          const aria = [name, dayLabel(day), times(p), attendanceText(p)].join(', ');
          return `<button class="week-event ${cls}" type="button" data-plan="${esc(p.id)}" aria-label="${esc(aria)}" title="${esc(sub ? `${times(p)} · ${sub}` : times(p))}"><b>${esc(times(p))}</b>${sub ? `<span>${esc(sub)}</span>` : ''}</button>`;
        }).join('');
        const add = `<button class="week-board__add" type="button" data-plan-add="${esc(id)}|${esc(day)}" aria-label="${esc(k.tr('planOn', { name, day: dayLabel(day) }))}">+</button>`;
        return `<td class="planner__cell${day === today ? ' is-today' : ''}" data-label="${esc(dayLabel(day))}">${events}${add}</td>`;
      }).join('');
      const minutes = data.planned.filter(p => p.employeeId === id && !p.excuse).reduce((sum, p) => sum + p.minutes, 0);
      return `<tr><td class="name">${esc(name)}</td>${cells}<td class="num" data-label="${esc(k.T.planHours)}">${minutes ? esc(k.hm(minutes)) : '—'}</td></tr>`;
    }).join('');
    const off = data.policy.schedule === 'OFF' ? `<div class="notice notice--info" role="status"><div class="notice__text"><span>${esc(k.T.scheduleOff)}</span></div></div>` : '';
    const tools = `<button class="btn btn--sm btn--ghost" type="button" data-plan-copy>${esc(k.T.copyWeek)}</button>
      <button class="btn btn--sm btn--primary" type="button" data-plan-new><span class="btn__plus" aria-hidden="true">+</span> ${esc(k.T.planShift)}</button>`;
    return `${off}<div class="panel">
      <div class="panel__head"><h2 class="panel__title">${esc(k.T.scheduleTitle)} <span class="tag">${data.planned.length}</span></h2><div class="panel__tools">${tools}</div></div>
      ${people.length
        ? `<div class="tbl-wrap"><table class="tbl tbl--stack planner"><thead>${head}</thead><tbody>${rows}</tbody></table></div>`
        : `<div class="empty"><p class="empty__title">${esc(k.T.plannerEmpty)}</p><p class="empty__desc">${esc(k.T.plannerEmptyDesc)}</p></div>`}
    </div>`;
  }

  function wirePlanner(root, weekStart, reload) {
    $$('[data-plan]', root).forEach(b => b.addEventListener('click', () => {
      const p = plan.data.planned.find(x => x.id === b.dataset.plan);
      if (p) openPlan(p, reload);
    }));
    $$('[data-plan-add]', root).forEach(b => b.addEventListener('click', () => {
      const [employeeId, day] = b.dataset.planAdd.split('|');
      openForm(null, { employeeId, day }, reload);
    }));
    $('[data-plan-new]', root)?.addEventListener('click', () => openForm(null, { day: k.d.todayKey() >= weekStart && k.d.todayKey() <= k.d.addDayKey(weekStart, 6) ? k.d.todayKey() : weekStart }, reload));
    $('[data-plan-copy]', root)?.addEventListener('click', async e => {
      const btn = e.currentTarget;
      if (!await k.d.confirmDialog({ title: k.T.copyTitle, body: k.T.copyBody, okLabel: k.T.copyOk })) return;
      btn.disabled = true;
      try {
        const res = await k.d.api('/app/api/timesheets/schedule/copy', { method: 'POST', body: JSON.stringify({ from: k.d.shiftPeriodKey(weekStart, 'week', -1), to: weekStart }) });
        k.d.toast(res.skipped ? k.tr('copiedSkipped', { created: res.created, skipped: res.skipped }) : k.tr('copied', { created: res.created }));
        reload();
      } catch (err) {
        btn.disabled = false;
        if (err.message !== 'unauthorized') k.d.toast(k.errorText(err));
      }
    });
  }

  /** One planned shift, from the bell's `planned:ID`. */
  async function openById(id, reload) {
    try {
      openPlan(await k.d.api(`/app/api/timesheets/schedule/${encodeURIComponent(id)}`), reload);
    } catch (err) {
      if (err.message !== 'unauthorized') k.d.toast(k.T.loadFailed);
    }
  }

  /** A planned shift's detail: when, where, for what, how it went, and the changes the team can make. */
  function openPlan(p, reload) {
    const after = async () => { await reload?.(); };
    const here = { key: `plan:${p.id}`, label: [p.employeeName, k.dayText(p.day)].filter(Boolean).join(' · '), open: () => openById(p.id, reload) };
    const a = p.attendance;
    const link = !k.d.linksBackTo(`employee:${p.employeeId}`);
    const body = document.createElement('div');
    body.className = 'form';
    body.innerHTML = `
      <div class="detail__head">
        ${link
          ? `<button class="detail__client detail__client--link" type="button" data-plan-employee aria-label="${esc(k.tr('openEmployee', { name: p.employeeName || '' }))}">${esc(p.employeeName || '')}</button>`
          : `<div class="detail__client">${esc(p.employeeName || '')}</div>`}
        <div class="detail__amount">${attendancePill(p)}<span class="detail__figure">${esc(times(p))}</span></div>
      </div>
      ${k.d.detailMeta([
        { label: k.T.metaDay, value: k.dayText(p.day) },
        { label: k.T.planHours, value: k.hm(p.minutes) },
        p.siteName ? { label: k.T.metaSite, value: p.siteName } : null,
        p.job ? { label: k.T.thJobs, value: window.TimeJobsUI.label(p.job) } : null,
        a.arrivedTime ? { label: k.T.metaArrived, value: a.arrivedTime } : null,
        a.leftTime ? { label: k.T.metaLeft, value: a.leftTime } : null,
        a.workedMinutes ? { label: k.T.thWorked, value: k.hm(a.workedMinutes) } : null,
      ])}
      ${p.note ? `<div class="form__row form__row--full"><span class="lbl">${esc(k.T.note)}</span><p class="hint">${esc(p.note)}</p></div>` : ''}
      ${p.excuse ? `<div class="notice notice--info"><div class="notice__text"><strong>${esc(k.T.excusedTitle)}</strong><span>${esc(k.tr('excuseBy', { who: p.excuse.by, reason: p.excuse.reason }))}</span></div></div>` : ''}
      <div class="detail__foot">
        ${a.shiftIds.length ? `<button class="btn btn--sm btn--ghost" type="button" data-plan-shift>${esc(k.T.openShiftLink)}</button>` : ''}
        ${a.shiftIds.length ? '' : `<button class="btn btn--sm btn--ghost" type="button" data-plan-delete>${esc(k.T.planDelete)}</button>`}
        ${p.excuse
          ? `<button class="btn btn--sm" type="button" data-plan-unexcuse>${esc(k.T.unexcuse)}</button>`
          : ['MISSING', 'ABSENT', 'UPCOMING', 'DUE'].includes(a.state) ? `<button class="btn btn--sm" type="button" data-plan-excuse>${esc(k.T.excuse)}</button>` : ''}
        <button class="btn btn--sm btn--accent" type="button" data-plan-edit>${esc(k.T.planEditShort)}</button>
      </div>`;
    $('[data-plan-employee]', body)?.addEventListener('click', () => k.d.openFrom(here, () => k.d.openPayeeDrawer('employee', p.employeeId)));
    $('[data-plan-shift]', body)?.addEventListener('click', () => k.d.openFrom(here, () => k.openShift(a.shiftIds[a.shiftIds.length - 1])));
    $('[data-plan-edit]', body).addEventListener('click', () => k.d.openFrom(here, () => openForm(p, null, after)));
    $('[data-plan-excuse]', body)?.addEventListener('click', () => k.d.openFrom(here, () => openExcuse(p, after)));
    $('[data-plan-unexcuse]', body)?.addEventListener('click', async e => {
      e.currentTarget.disabled = true;
      try {
        const saved = await k.d.api(`/app/api/timesheets/schedule/${encodeURIComponent(p.id)}/excuse`, { method: 'DELETE' });
        await after();
        openPlan(saved, reload);
      } catch (err) { if (err.message !== 'unauthorized') k.d.toast(k.errorText(err)); }
    });
    $('[data-plan-delete]', body)?.addEventListener('click', async () => {
      if (!await k.d.confirmDialog({ title: k.T.planDeleteTitle, body: k.T.planDeleteBody, okLabel: k.T.planDelete })) return;
      try {
        await k.d.api(`/app/api/timesheets/schedule/${encodeURIComponent(p.id)}`, { method: 'DELETE' });
        k.d.toast(k.T.planDeleted);
        k.d.closeDrawer({ dismissed: true });
        after();
      } catch (err) { if (err.message !== 'unauthorized') k.d.toast(k.errorText(err)); }
    });
    k.d.openDrawer(k.dayText(p.day), body, false, { eyebrow: `${k.T.planEyebrow}${p.employeeNumber ? ` · ${p.employeeNumber}` : ''}` });
  }

  function openExcuse(p, after) {
    const form = document.createElement('form');
    form.className = 'form';
    form.innerHTML = `
      <p class="hint">${esc(`${p.employeeName || ''} · ${k.dayText(p.day)} · ${times(p)}`)}</p>
      <div class="form__row form__row--full"><label class="lbl" for="pe-reason">${esc(k.T.excuseReason)} <span class="req">●</span></label>
        <textarea class="txt" id="pe-reason" maxlength="500" required placeholder="${esc(k.T.excuseReasonPh)}"></textarea></div>
      <div class="actions">
        <button class="btn btn--primary" type="submit">${esc(k.T.excuse)}</button>
        <button class="btn btn--ghost" type="button" data-form-cancel>${esc(k.d.STR.cancel)}</button>
      </div>`;
    $('[data-form-cancel]', form).addEventListener('click', () => k.d.closeDrawer());
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const reason = $('#pe-reason', form).value.trim();
      if (!reason) { k.d.toast(k.tr('errors.reason_required')); return; }
      const btn = $('button[type=submit]', form);
      btn.disabled = true;
      try {
        await k.d.api(`/app/api/timesheets/schedule/${encodeURIComponent(p.id)}/excuse`, { method: 'POST', body: JSON.stringify({ reason }) });
        k.d.toast(k.T.excused);
        k.d.closeDrawer();
        after();
      } catch (err) {
        btn.disabled = false;
        if (err.message !== 'unauthorized') k.d.toast(k.errorText(err));
      }
    });
    k.d.openDrawer(k.T.excuseTitle, form, false, { eyebrow: k.T.planEyebrow });
  }

  async function sites() {
    if (!plan.sites) plan.sites = await k.d.api('/app/api/timesheets/sites').catch(() => []);
    return plan.sites.filter(s => s.active);
  }

  /** The days of [day]'s week as chips, [day] picked: a new plan repeats on the ones picked. */
  function repeatChips(day, picked) {
    const monday = k.d.periodKey(day, 'week');
    return Array.from({ length: 7 }, (_, i) => k.d.addDayKey(monday, i)).map(key => {
      const on = picked.has(key);
      return `<button class="chip${on ? ' is-on' : ''}" type="button" data-plan-day="${esc(key)}" aria-pressed="${on}">${esc(k.d.fmtDayKey(key, { weekday: 'short', day: 'numeric' }))}</button>`;
    }).join('');
  }

  /** New planned shifts ([preset] gives the employee and day) or a change to [p]. */
  async function openForm(p, preset, reload) {
    let opts;
    let siteList;
    try {
      [opts, siteList] = await Promise.all([window.TimeJobsUI.loadOptions(true), sites()]);
    } catch (err) {
      if (err.message !== 'unauthorized') k.d.toast(k.T.loadFailed);
      return;
    }
    const editing = p?.id ? p : null;
    const employees = (k.team.employees || []).filter(e => !e.archivedAt);
    const day = editing?.day || preset?.day || k.d.todayKey();
    const picked = new Set([day]);
    const hasJobs = opts.clients.length || opts.projects.length || opts.activities.length;
    // Its own id prefix: `pf-note` is the planned shift's note, not the job's.
    const picker = window.TimeJobsUI.fields('pfj', editing?.job, opts, { workLog: 'OPTIONAL' }, { note: false });
    const form = document.createElement('form');
    form.className = 'form';
    form.innerHTML = `
      ${editing ? `<p class="hint">${esc(editing.employeeName || '')}</p>` : `<div class="form__row form__row--full"><label class="lbl" for="pf-employee">${esc(k.T.planEmployee)} <span class="req">●</span></label>
        <select class="sel" id="pf-employee" required><option value="">${esc(k.T.chooseEmployee)}</option>${employees.map(e => `<option value="${esc(e.id)}"${preset?.employeeId === e.id ? ' selected' : ''}>${esc(e.name)}</option>`).join('')}</select></div>`}
      <div class="form__grid form__grid--3">
        <div class="form__row"><label class="lbl" for="pf-day">${esc(k.T.planDay)} <span class="req">●</span></label>
          <input class="inp inp--mono" id="pf-day" type="date" required value="${esc(day)}" /></div>
        <div class="form__row"><label class="lbl" for="pf-start">${esc(k.T.planStart)} <span class="req">●</span></label>
          <input class="inp inp--mono" id="pf-start" type="time" required value="${esc(editing?.startTime || '')}" /></div>
        <div class="form__row"><label class="lbl" for="pf-end">${esc(k.T.planEnd)} <span class="req">●</span></label>
          <input class="inp inp--mono" id="pf-end" type="time" required value="${esc(editing?.endTime || '')}" /></div>
      </div>
      ${editing ? '' : `<div class="form__row form__row--full"><span class="lbl" id="pf-repeat-label">${esc(k.T.planRepeat)}</span>
        <div class="chip-picks" role="group" aria-labelledby="pf-repeat-label" data-plan-days>${repeatChips(day, picked)}</div></div>`}
      <p class="hint">${esc(k.T.timesHint)}</p>
      ${siteList.length ? `<div class="form__row form__row--full"><label class="lbl" for="pf-site">${esc(k.T.planSite)} <span class="opt">${esc(k.d.STR.optional)}</span></label>
        <select class="sel" id="pf-site"><option value="">${esc(k.T.planNoSite)}</option>${siteList.map(s => `<option value="${esc(s.id)}"${editing?.siteId === s.id ? ' selected' : ''}>${esc(s.name)}</option>`).join('')}</select></div>` : ''}
      ${hasJobs ? `<div class="lbl">${esc(k.T.planJob)}</div>${picker.html}` : ''}
      <div class="form__row form__row--full"><label class="lbl" for="pf-note">${esc(k.T.planNote)} <span class="opt">${esc(k.d.STR.optional)}</span></label>
        <textarea class="txt" id="pf-note" maxlength="500" placeholder="${esc(k.T.planNotePh)}">${esc(editing?.note || '')}</textarea></div>
      <div class="actions">
        <button class="btn btn--primary" type="submit">${esc(editing ? k.d.STR.clientSaveChanges : k.T.planSave)}</button>
        <button class="btn btn--ghost" type="button" data-form-cancel>${esc(k.d.STR.cancel)}</button>
      </div>`;
    if (hasJobs) picker.wire(form);
    const wireDays = () => $$('[data-plan-day]', form).forEach(chip => chip.addEventListener('click', () => {
      const key = chip.dataset.planDay;
      if (picked.has(key) && picked.size > 1) picked.delete(key); else picked.add(key);
      chip.classList.toggle('is-on', picked.has(key));
      chip.setAttribute('aria-pressed', String(picked.has(key)));
    }));
    wireDays();
    $('#pf-day', form).addEventListener('change', e => {
      if (editing || !e.target.value) return;
      picked.clear();
      picked.add(e.target.value);
      $('[data-plan-days]', form).innerHTML = repeatChips(e.target.value, picked);
      wireDays();
    });
    $('[data-form-cancel]', form).addEventListener('click', () => k.d.closeDrawer());
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const value = id => $(`#${id}`, form)?.value || '';
      const job = hasJobs ? picker.read(form) : null;
      const common = { start: value('pf-start'), end: value('pf-end'), siteId: value('pf-site'), note: value('pf-note').trim() };
      if (!editing && !value('pf-employee')) { k.d.toast(k.tr('errors.employee_not_found')); return; }
      const btn = $('button[type=submit]', form);
      btn.disabled = true;
      try {
        if (editing) {
          await k.d.api(`/app/api/timesheets/schedule/${encodeURIComponent(editing.id)}`, {
            method: 'PATCH',
            body: JSON.stringify({ ...common, day: value('pf-day'), job: job || {} }),
          });
        } else {
          await k.d.api('/app/api/timesheets/schedule', {
            method: 'POST',
            body: JSON.stringify({ ...common, employeeId: value('pf-employee'), days: [...picked].sort(), ...(job ? { job } : {}) }),
          });
        }
        k.d.toast(k.T.planSaved);
        k.d.closeDrawer();
        reload?.();
      } catch (err) {
        btn.disabled = false;
        if (err.message !== 'unauthorized') k.d.toast(k.errorText(err));
      }
    });
    k.d.openDrawer(editing ? k.T.planEdit : k.T.planShift, form, false, { eyebrow: k.T.planEyebrow });
  }

  /** Sites changed on the Work sites tab: the form reads them again. */
  function forgetSites() {
    plan.sites = null;
  }

  // ── An employee's own planned shifts (My hours) ────────────────────────────────────────────────

  /**
   * Lines for the clock card: the planned shift that matters now, how late they are, when clocking in opens. The
   * backend sends the open shift's plan, one running now or one later today, so a plan on another day began on an
   * earlier one (a night shift, or a clock-out forgotten overnight) and is named by its day.
   */
  function clockLines(status, now) {
    const p = status.planned;
    const lines = [];
    if (p) {
      const when = p.day === k.d.todayKey() ? k.tr('plannedToday', { time: times(p) }) : k.tr('plannedOn', { day: k.dayText(p.day), time: times(p) });
      lines.push(`<p class="clock__meta">${esc([when, p.siteName, p.job ? window.TimeJobsUI.label(p.job) : ''].filter(Boolean).join(' · '))}</p>`);
      const start = Date.parse(p.startAt);
      if (status.state === 'OFF' && now > start && p.attendance.shiftIds.length === 0) {
        lines.push(`<p class="clock__meta clock__meta--warn">${esc(k.tr('youAreLate', { time: k.hm(Math.floor((now - start) / 60000)) }))}</p>`);
      }
      if (p.note) lines.push(`<p class="clock__meta">${esc(p.note)}</p>`);
    }
    if (status.state === 'OFF' && status.clockIn?.state === 'TOO_EARLY') {
      lines.push(`<p class="clock__meta">${esc(k.tr('opensAt', { time: status.clockIn.opensTime || '' }))}</p>`);
    }
    if (status.state === 'OFF' && status.clockIn?.state === 'NOT_SCHEDULED') {
      lines.push(`<div class="notice notice--info" role="status"><div class="notice__text"><span>${esc(k.T.notScheduledNow)}</span></div></div>`);
    }
    return lines.join('');
  }

  /** My schedule: the planned shifts still to come, the one now first. */
  function myScheduleHtml(status) {
    const list = [status.planned, ...(status.upcoming || [])].filter(Boolean)
      .filter((p, i, all) => all.findIndex(x => x.id === p.id) === i && p.attendance.state !== 'ATTENDED');
    if (!list.length) return '';
    const label = text => ` data-label="${esc(text)}"`;
    const rows = list.map(p => `<tr>
      <td class="mono">${esc(k.dayText(p.day))}</td>
      <td class="mono"${label(k.T.thTime)}>${esc(times(p))}</td>
      <td${label(k.T.thSite)}>${esc(p.siteName || '—')}</td>
      <td${label(k.T.thJobs)}>${esc(p.job ? window.TimeJobsUI.label(p.job) : '—')}${p.note ? `<div class="sub">${esc(p.note)}</div>` : ''}</td>
      <td${label(k.T.thStatus)}>${attendancePill(p)}</td>
    </tr>`).join('');
    return k.tablePanel({
      title: k.T.myScheduleTitle,
      tag: list.length,
      head: [[k.T.thDay], [k.T.thTime], [k.T.thSite], [k.T.thJobs], [k.T.thStatus]],
      rows,
      empty: '',
    });
  }

  /** The board's "Not in yet": planned shifts that started with nobody clocked in. */
  function expectedHtml(expected) {
    if (!expected?.length) return '';
    const rows = expected.map(p => {
      const missing = p.attendance.state === 'MISSING';
      const side = missing ? k.pill(k.known('attendance.MISSING', ''), 'bad') : k.pill(k.known('attendance.DUE', ''), 'warn');
      return `<li><button class="worklist__item" type="button" data-plan-open="${esc(p.id)}" data-tone="${missing ? 'bad' : 'warn'}">
        <span class="worklist__dot" aria-hidden="true"></span>
        <span class="worklist__main"><span class="worklist__title">${esc(p.employeeName || '')}</span><span class="worklist__detail">${esc([k.tr('expectedDetail', { time: times(p) }), p.siteName].filter(Boolean).join(' · '))}</span></span>
        <span class="worklist__side">${side}</span>
      </button></li>`;
    }).join('');
    return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(k.T.expectedTitle)} <span class="tag">${expected.length}</span></h2></header>
      <ul class="worklist">${rows}</ul></section>`;
  }

  function wireExpected(root, expected, reload) {
    $$('[data-plan-open]', root).forEach(b => b.addEventListener('click', () => {
      const p = (expected || []).find(x => x.id === b.dataset.planOpen);
      if (p) openPlan(p, reload);
    }));
  }

  window.TimeScheduleUI = {
    init, load, stats, plannerHtml, wirePlanner, openPlan, openById, openForm, forgetSites, clockLines, myScheduleHtml, expectedHtml, wireExpected,
    attendancePill, attendanceText,
  };
})();
