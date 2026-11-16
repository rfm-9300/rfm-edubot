/* Time clock, jobs (/app): what a shift's time was for. The job picker (client, Building project, activity, note),
   switching jobs while clocked in, splitting a shift's time between jobs afterwards, the team's By job report and
   the company's activities. timesheets.js mounts it with its helpers (init(kit)); the server applies every rule. */
(function () {
  let k = null;
  const options = { mine: null, team: null };
  const report = { grain: 'week', key: '', group: 'client', employee: '', data: null };
  let activities = null;

  const esc = s => k.esc(s);
  const $ = (sel, root) => k.$(sel, root);
  const $$ = (sel, root) => k.$$(sel, root);
  const GROUPS = ['client', 'project', 'activity', 'employee'];
  const GROUP_KEYS = { client: 'byClient', project: 'byProject', activity: 'byActivity', employee: 'byEmployee' };

  function init(kit) {
    k = kit;
  }

  // ── What a job can name ────────────────────────────────────────────────────────────────────────

  /** The clients, open projects and activities a job can name, for the employee's pages or the team's; kept for the session. */
  async function loadOptions(forTeam) {
    const key = forTeam ? 'team' : 'mine';
    if (!options[key]) {
      options[key] = await k.d.api(forTeam ? '/app/api/timesheets/job-options' : '/app/api/portal/time/jobs')
        .then(o => ({ clients: o.clients || [], projects: o.projects || [], activities: o.activities || [] }));
    }
    return options[key];
  }

  /** Activities changed: the pickers read them again next time. */
  function forgetOptions() {
    options.mine = null;
    options.team = null;
  }

  /** A job in a few words: "Casa Silva · Limpeza", else its note, else "No job". */
  function label(job) {
    if (!job) return k.T.noJob;
    const parts = [job.clientName, job.projectName, job.activityName].filter(Boolean);
    return parts.length ? parts.join(' · ') : (job.note || k.T.noJob);
  }

  /** A shift's jobs as a table cell: the longest one, and how many others. */
  function summary(segments) {
    const list = segments || [];
    if (!list.length) return '—';
    const main = [...list].sort((a, b) => b.minutes - a.minutes)[0];
    const others = new Set(list.map(s => label(s.job))).size - 1;
    return others > 0 ? `${label(main.job)} ${k.tr('jobsMore', { n: others })}` : label(main.job);
  }

  /** The parts a complete job needs under the strict work log, or none. */
  function needsOf(policy) {
    return policy?.workLog === 'REQUIRED' ? (policy.jobNeeds || {}) : {};
  }

  /** The first part [needs] asks for that [job] lacks, as the server names it (`job`, `client`, `project`, `activity`, `note`). */
  function missing(job, needs, strict) {
    if (!strict) return null;
    const j = job || {};
    if (needs.client && !j.clientId) return 'client';
    if (needs.project && !j.projectId) return 'project';
    if (needs.activity && !j.activityId) return 'activity';
    if (needs.note && !(j.note || '').trim()) return 'note';
    const named = j.clientId || j.projectId || j.activityId;
    return !named && !needs.note ? 'job' : null;
  }

  /** What a missing part means to a person. */
  function missingText(part) {
    return part === 'job' ? k.tr('errors.job_required') : k.tr('errors.job_incomplete', { part: k.known(`jobParts.${part}`, part) });
  }

  /**
   * Fields to pick a job, each shown only when the company has something to pick: client, project (narrowed to the
   * client's), activity, note. [prefix] keeps ids apart when a form holds several; `{ note: false }` leaves the note
   * out where the form has its own (a planned shift's).
   */
  function fields(prefix, job, opts, policy, show = {}) {
    const j = job || {};
    const needs = needsOf(policy);
    const strict = policy?.workLog === 'REQUIRED';
    const mark = part => (strict && needs[part] ? ' <span class="req">●</span>' : ` <span class="opt">${esc(k.d.STR.optional)}</span>`);
    const id = part => `${prefix}-${part}`;
    const option = (value, text, selected) => `<option value="${esc(value)}"${selected ? ' selected' : ''}>${esc(text)}</option>`;
    const rows = [];
    if (opts.clients.length) {
      rows.push(`<div class="form__row"><label class="lbl" for="${id('client')}">${esc(k.T.jobClient)}${mark('client')}</label>
        <select class="sel" id="${id('client')}" data-job="client">${option('', k.T.jobNoClient, !j.clientId)}${opts.clients.map(c => option(c.id, c.name, c.id === j.clientId)).join('')}</select></div>`);
    }
    if (opts.projects.length) {
      rows.push(`<div class="form__row"><label class="lbl" for="${id('project')}">${esc(k.T.jobProject)}${mark('project')}</label>
        <select class="sel" id="${id('project')}" data-job="project"></select></div>`);
    }
    if (opts.activities.length) {
      rows.push(`<div class="form__row"><label class="lbl" for="${id('activity')}">${esc(k.T.jobActivity)}${mark('activity')}</label>
        <select class="sel" id="${id('activity')}" data-job="activity">${option('', k.T.jobNoActivity, !j.activityId)}${opts.activities.map(a => option(a.id, a.code ? `${a.code} · ${a.name}` : a.name, a.id === j.activityId)).join('')}</select></div>`);
    }
    if (show.note !== false) {
      rows.push(`<div class="form__row"><label class="lbl" for="${id('note')}">${esc(k.T.jobNote)}${mark('note')}</label>
        <input class="inp" id="${id('note')}" data-job="note" maxlength="500" autocomplete="off" placeholder="${esc(k.T.jobNotePh)}" value="${esc(j.note || '')}" /></div>`);
    }
    const anyHint = strict && !needs.client && !needs.project && !needs.activity && !needs.note ? `<p class="hint">${esc(k.T.jobNeedsAny)}</p>` : '';
    const html = `<div class="form__grid">${rows.join('')}</div>${anyHint}`;

    /** The project list follows the client: theirs and the ones without a client. Picking a project picks its client. */
    const wire = root => {
      const client = $(`#${id('client')}`, root);
      const project = $(`#${id('project')}`, root);
      if (!project) return;
      const fill = keep => {
        const who = client?.value || '';
        const list = opts.projects.filter(p => !who || !p.clientId || p.clientId === who);
        project.innerHTML = option('', k.T.jobNoProject, !keep) + list.map(p => option(p.id, `${p.number} · ${p.name}`, p.id === keep)).join('');
      };
      fill(j.projectId || '');
      client?.addEventListener('change', () => fill(project.value));
      project.addEventListener('change', () => {
        const owner = opts.projects.find(p => p.id === project.value)?.clientId;
        if (owner && client && client.value !== owner && [...client.options].some(o => o.value === owner)) {
          client.value = owner;
          fill(project.value);
        }
      });
    };

    /** What the fields say, as the API takes a job; null when nothing is picked. */
    const read = root => {
      const value = part => ($(`#${id(part)}`, root)?.value || '').trim();
      const picked = { clientId: value('client'), projectId: value('project'), activityId: value('activity'), note: value('note') };
      const job = Object.fromEntries(Object.entries(picked).filter(([, v]) => v));
      return Object.keys(job).length ? job : null;
    };
    return { html, wire, read, check: root => missing(read(root), needs, strict) };
  }

  // ── Switching jobs while clocked in ───────────────────────────────────────────────────────────

  /** A drawer that switches the open shift to another job; [send] punches `SWITCH` with it, like any punch. */
  async function openSwitch(status, send) {
    let opts;
    try {
      opts = await loadOptions(false);
    } catch (err) {
      if (err.message !== 'unauthorized') k.d.toast(k.T.loadFailed);
      return;
    }
    const picker = fields('sw', null, opts, status.policy);
    const form = document.createElement('form');
    form.className = 'form';
    const current = status.open?.segments?.[status.open.segments.length - 1]?.job;
    form.innerHTML = `
      <p class="hint">${esc(k.tr('workingOn', { job: label(current) }))}</p>
      ${picker.html}
      <p class="hint">${esc(k.T.switchHint)}</p>
      <div class="actions">
        <button class="btn btn--primary" type="submit">${esc(k.T.switchNow)}</button>
        <button class="btn btn--ghost" type="button" data-form-cancel>${esc(k.d.STR.cancel)}</button>
      </div>`;
    picker.wire(form);
    $('[data-form-cancel]', form).addEventListener('click', () => k.d.closeDrawer());
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const part = picker.check(form);
      if (part) { k.d.toast(missingText(part)); return; }
      const btn = $('button[type=submit]', form);
      btn.disabled = true;
      if (await send(picker.read(form))) k.d.closeDrawer();
      else btn.disabled = false;
    });
    k.d.openDrawer(k.T.switchTitle, form, false, { eyebrow: k.T.clockTitle });
  }

  // ── A shift's work log ────────────────────────────────────────────────────────────────────────

  /** The work log panel of a shift's detail: its parts, their job and minutes, and who set each. */
  function segmentsHtml(shift, canEdit) {
    const list = shift.segments || [];
    if (!list.length) return '';
    const rows = list.map(s => `<tr>
      <td class="mono">${esc(`${s.startTime}⁠–⁠${s.endTime || '…'}`)}</td>
      <td class="name" data-label="${esc(k.T.thJobs)}">${esc(label(s.job))}${s.job?.note && (s.job.clientName || s.job.projectName || s.job.activityName) ? `<div class="sub">${esc(s.job.note)}</div>` : ''}${s.siteName ? `<div class="sub">${esc(k.tr('atSite', { site: s.siteName }))}</div>` : ''}</td>
      <td class="num" data-label="${esc(k.T.thWorked)}">${esc(k.hm(s.minutes))}</td>
      <td data-label="${esc(k.T.thSource)}">${s.complete ? '' : k.pill(k.T.incomplete, 'warn')} ${k.pill(k.known(`source.${s.source}`, s.source), s.source === 'CLOCK' ? '' : 'info')}</td>
    </tr>`).join('');
    const unallocated = shift.unallocatedMinutes ? `<p class="hint hint--warn">${esc(k.tr('unallocatedNote', { time: k.hm(shift.unallocatedMinutes) }))}</p>` : '';
    return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(k.T.workLogTitle)} <span class="tag">${list.length}</span></h2>
      ${canEdit ? `<div class="panel__tools"><button class="btn btn--sm btn--ghost" type="button" data-segments-edit>${esc(k.T.workLogEdit)}</button></div>` : ''}</header>
      <div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr><th>${esc(k.T.thTime)}</th><th>${esc(k.T.thJobs)}</th><th class="right">${esc(k.T.thWorked)}</th><th>${esc(k.T.thSource)}</th></tr></thead><tbody>${rows}</tbody></table></div>
      ${unallocated}</section>`;
  }

  /**
   * The editor for how a shift's time splits between jobs: one card per part, from its start time to the next part's.
   * The employee saves their own until it is approved; the team gives a reason. [done] gets the saved shift.
   */
  async function openSegments(shift, forTeam, policy, done) {
    let opts;
    try {
      opts = await loadOptions(forTeam);
    } catch (err) {
      if (err.message !== 'unauthorized') k.d.toast(k.T.loadFailed);
      return;
    }
    const removeIcon = '<svg width="12" height="12" viewBox="0 0 16 16" aria-hidden="true"><path d="M3 3 L13 13 M13 3 L3 13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>';
    const start = (shift.segments?.[0]?.startTime) || shift.startTime;
    const initial = shift.segments?.length ? shift.segments : [{ startTime: shift.startTime, job: null }];
    const form = document.createElement('form');
    form.className = 'form';
    form.innerHTML = `
      <p class="hint">${esc(`${k.dayText(shift.day)} · ${k.timeRange(shift)} · ${k.hm(shift.workedMinutes)}`)}</p>
      <div class="segments" data-segments></div>
      <div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-segment-add>${esc(k.T.segmentAdd)}</button></div>
      <p class="hint">${esc(k.T.segmentsHint)}</p>
      ${forTeam ? `<div class="form__row form__row--full"><label class="lbl" for="sg-reason">${esc(k.T.formReason)} <span class="req">●</span></label>
        <textarea class="txt" id="sg-reason" maxlength="500" required placeholder="${esc(k.T.reasonPh)}"></textarea></div>
        <p class="hint">${esc(k.T.segmentsTeamHint)}</p>` : ''}
      <div class="actions">
        <button class="btn btn--primary" type="submit">${esc(k.d.STR.clientSaveChanges)}</button>
        <button class="btn btn--ghost" type="button" data-form-cancel>${esc(k.d.STR.cancel)}</button>
      </div>`;
    const list = $('[data-segments]', form);
    let seq = 0;
    const pickers = new Map();
    const add = segment => {
      const n = seq++;
      const first = !list.children.length;
      const picker = fields(`sg${n}`, segment?.job, opts, policy);
      const card = document.createElement('section');
      card.className = 'panel segment';
      card.dataset.segment = String(n);
      card.innerHTML = `<header class="panel__head">
          ${first
            ? `<h3 class="panel__title">${esc(k.tr('segmentStart', { time: start }))}</h3>`
            : `<div class="segment__start"><label class="lbl segment__from" for="sg${n}-start">${esc(k.T.segmentFrom)}</label>
              <input class="inp inp--mono segment__time" id="sg${n}-start" type="time" required value="${esc(segment?.startTime || '')}" /></div>
              <div class="panel__tools"><button type="button" class="iconbtn" data-segment-remove title="${esc(k.T.segmentRemove)}" aria-label="${esc(k.T.segmentRemove)}">${removeIcon}</button></div>`}
        </header>
        <div class="panel__body">${picker.html}</div>`;
      picker.wire(card);
      pickers.set(n, picker);
      $('[data-segment-remove]', card)?.addEventListener('click', () => { pickers.delete(n); card.remove(); });
      list.appendChild(card);
      return card;
    };
    initial.forEach(add);
    $('[data-segment-add]', form).addEventListener('click', () => $('input[type=time]', add(null))?.focus());
    $('[data-form-cancel]', form).addEventListener('click', () => k.d.closeDrawer());
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const cards = $$('[data-segment]', form);
      const segments = cards.map((card, i) => {
        const picker = pickers.get(Number(card.dataset.segment));
        const job = picker.read(card);
        return { start: i === 0 ? start : $('input[type=time]', card).value, ...(job ? { job } : {}) };
      });
      if (segments.slice(1).some(s => !s.start)) { k.d.toast(k.tr('errors.invalid_time')); return; }
      const reason = forTeam ? $('#sg-reason', form).value.trim() : '';
      if (forTeam && !reason) { k.d.toast(k.tr('errors.reason_required')); return; }
      const btn = $('button[type=submit]', form);
      btn.disabled = true;
      const path = forTeam ? `/app/api/timesheets/shifts/${encodeURIComponent(shift.id)}/segments` : `/app/api/portal/time/shifts/${encodeURIComponent(shift.id)}/segments`;
      try {
        const saved = await k.d.api(path, { method: 'PUT', body: JSON.stringify({ segments, ...(forTeam ? { reason } : {}) }) });
        k.d.toast(k.T.segmentsSaved);
        k.d.closeDrawer();
        done?.(saved);
      } catch (err) {
        btn.disabled = false;
        if (err.message !== 'unauthorized') k.d.toast(k.errorText(err));
      }
    });
    k.d.openDrawer(k.T.segmentsTitle, form, false, { eyebrow: k.T.detailEyebrow });
  }

  // ── The team's report: hours by job ────────────────────────────────────────────────────────────

  function range() {
    if (!report.key) report.key = k.d.currentPeriodKey(report.grain);
    if (report.grain === 'month') {
      const [y, m] = report.key.split('-').map(Number);
      const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
      return { from: `${report.key}-01`, to: `${report.key}-${String(last).padStart(2, '0')}` };
    }
    return { from: report.key, to: k.d.addDayKey(report.key, 6) };
  }

  async function loadReport() {
    const { from, to } = range();
    const who = report.employee ? `&employeeId=${encodeURIComponent(report.employee)}` : '';
    report.data = await k.d.api(`/app/api/timesheets/jobs?from=${from}&to=${to}${who}`);
  }

  /** The hero's period navigator while the report shows. */
  function reportNav() {
    return k.d.periodNav(report.key || k.d.currentPeriodKey(report.grain), report.grain, 'data-tj-period', k.d.CRM.services);
  }

  /** The hero's figures while the report shows. */
  function reportStats() {
    const r = report.data;
    if (!r) return [];
    const share = r.minutes ? Math.round((r.billableMinutes / r.minutes) * 100) : 0;
    return [
      { label: k.T.statLogged, value: k.hm(r.minutes) },
      { label: k.T.statBillable, value: k.hm(r.billableMinutes) },
      { label: k.T.statUnallocated, value: r.unallocatedMinutes ? k.hm(r.unallocatedMinutes) : '—' },
      { label: k.T.statBillablePct, value: r.minutes ? new Intl.NumberFormat(k.d.uiLocale(), { style: 'percent' }).format(share / 100) : '—' },
    ];
  }

  function reportHtml() {
    const r = report.data;
    if (!r) return '';
    const hasProjects = (r.byProject || []).some(g => g.id);
    const groups = GROUPS.filter(g => g !== 'project' || hasProjects);
    if (!groups.includes(report.group)) report.group = 'client';
    const rows = r[GROUP_KEYS[report.group]] || [];
    const top = Math.max(1, ...rows.map(g => g.minutes));
    const grain = ['week', 'month'].map(g => `<button class="chip${report.grain === g ? ' is-on' : ''}" type="button" data-tj-grain="${g}">${esc(k.tr(`grain.${g}`))}</button>`).join('');
    const chips = groups.map(g => `<button class="chip${report.group === g ? ' is-on' : ''}" type="button" data-tj-group="${g}">${esc(k.tr(`jobsGroup.${g}`))}</button>`).join('');
    const employees = `<select class="sel" data-tj-employee aria-label="${esc(k.T.thEmployee)}"><option value="">${esc(k.T.allEmployees)}</option>${(k.team.employees || []).map(e => `<option value="${esc(e.id)}"${report.employee === e.id ? ' selected' : ''}>${esc(e.name)}</option>`).join('')}</select>`;
    const cell = text => ` data-label="${esc(text)}"`;
    const groupRows = rows.map(g => {
      const name = g.id ? g.name : k.known(`noneGroup.${report.group}`, '—');
      const opens = report.group === 'employee' && g.id;
      return `<tr class="conversation-row"${opens ? ` data-tj-person="${esc(g.id)}" tabindex="0"` : ''}>
        <td class="name">${esc(name)}<div class="cellbar" aria-hidden="true"><span class="cellbar__fill" style="width:${Math.round((g.minutes / top) * 100)}%"></span></div></td>
        <td class="num"${cell(k.T.thHours)}>${esc(k.hm(g.minutes))}</td>
        <td class="num"${cell(k.T.thBillable)}>${g.billableMinutes ? esc(k.hm(g.billableMinutes)) : '—'}</td>
        <td class="num"${cell(k.T.thPeople)}>${g.employees}</td>
        <td class="num"${cell(k.T.thEntries)}>${g.entries}</td>
      </tr>`;
    }).join('');
    const byGroup = k.tablePanel({
      title: k.tr('byJobTitle', { group: k.tr(`jobsGroup.${report.group}`).toLowerCase() }),
      tools: `<button class="btn btn--sm btn--ghost" type="button" data-tj-export>${esc(k.T.exportWorkLog)}</button>`,
      filters: `${grain}<span class="panel__sep" aria-hidden="true"></span>${chips}${employees}`,
      head: [[k.tr(`jobsGroup.${report.group}`)], [k.T.thHours, 'right'], [k.T.thBillable, 'right'], [k.T.thPeople, 'right'], [k.T.thEntries, 'right']],
      rows: groupRows,
      empty: k.T.jobsEmpty,
      emptyDesc: k.T.jobsEmptyDesc,
    });
    const entryRows = (r.entries || []).map(e => `<tr class="conversation-row${e.status === 'OPEN' ? ' is-current' : e.status === 'APPROVED' ? ' is-paid' : ''}" data-shift="${esc(e.shiftId)}" tabindex="0">
      <td class="name">${esc(e.employeeName)}<div class="sub mono">${esc(`${k.dayText(e.day)} · ${e.startTime}⁠–⁠${e.endTime || '…'}`)}</div></td>
      <td${cell(k.T.thJobs)}>${esc(label(e.job))}${e.job?.note && (e.job.clientName || e.job.projectName || e.job.activityName) ? `<div class="sub">${esc(e.job.note)}</div>` : ''}</td>
      <td class="num"${cell(k.T.thHours)}>${esc(k.hm(e.minutes))}</td>
      <td${cell(k.T.thBillable)}>${e.complete ? '' : k.pill(k.T.incomplete, 'warn')} ${e.job?.billable ? k.pill(k.T.billable, 'ok') : ''}</td>
    </tr>`).join('');
    const entries = (r.entries || []).length ? k.tablePanel({
      title: k.T.entriesTitle,
      tag: r.entries.length,
      head: [[k.T.thEmployee], [k.T.thJobs], [k.T.thHours, 'right'], ['']],
      rows: entryRows,
      empty: '',
    }) + (r.truncated ? `<p class="hint">${esc(k.tr('entriesTruncated', { n: r.entries.length }))}</p>` : '') : '';
    return byGroup + entries;
  }

  function wireReport(root, rerender) {
    const reload = async () => {
      try { await loadReport(); } catch (err) { if (err.message !== 'unauthorized') k.d.toast(k.T.loadFailed); }
      rerender();
    };
    $$('[data-tj-period]', root).forEach(b => b.addEventListener('click', () => {
      const step = b.dataset.tjPeriod;
      report.key = step === 'current' ? k.d.currentPeriodKey(report.grain) : k.d.shiftPeriodKey(report.key, report.grain, step === 'next' ? 1 : -1);
      reload();
    }));
    $$('[data-tj-grain]', root).forEach(b => b.addEventListener('click', () => {
      if (report.grain === b.dataset.tjGrain) return;
      report.grain = b.dataset.tjGrain;
      report.key = k.d.currentPeriodKey(report.grain);
      reload();
    }));
    $$('[data-tj-group]', root).forEach(b => b.addEventListener('click', () => { report.group = b.dataset.tjGroup; rerender(); }));
    $('[data-tj-employee]', root)?.addEventListener('change', e => { report.employee = e.target.value; reload(); });
    $$('[data-tj-person]', root).forEach(row => {
      const open = () => k.d.openPayeeDrawer('employee', row.dataset.tjPerson);
      row.addEventListener('click', open);
      row.addEventListener('keydown', e => { if (e.key === 'Enter') open(); });
    });
    // The work log's rows ([data-shift]) open their shift through the page's own wiring.
    $('[data-tj-export]', root)?.addEventListener('click', async e => {
      const btn = e.currentTarget;
      const { from, to } = range();
      const who = report.employee ? `&employeeId=${encodeURIComponent(report.employee)}` : '';
      btn.disabled = true;
      try {
        await k.d.download(`/app/api/timesheets/jobs.csv?from=${from}&to=${to}${who}`, `work-log-${from}-${to}.csv`);
      } catch (err) {
        if (err.message !== 'unauthorized') k.d.toast(k.T.exportFailed);
      } finally {
        btn.disabled = false;
      }
    });
  }

  // ── The company's activities ───────────────────────────────────────────────────────────────────

  async function loadActivities() {
    activities = await k.d.api('/app/api/timesheets/activities');
  }

  function activitiesHtml() {
    const list = activities || [];
    const admin = k.d.isAdmin();
    const cell = text => ` data-label="${esc(text)}"`;
    const rows = list.map(a => `<tr class="conversation-row${a.active ? '' : ' is-draft'}" data-tj-activity="${esc(a.id)}"${admin ? ' tabindex="0"' : ''}>
      <td class="name">${esc(a.name)}${a.code ? `<div class="sub mono">${esc(a.code)}</div>` : ''}</td>
      <td${cell(k.T.thBillable)}>${a.billable ? k.pill(k.T.billable, 'ok') : k.pill(k.T.notBillable, '')}</td>
      <td${cell(k.T.thStatus)}>${k.pill(a.active ? k.T.activityActive : k.T.activityOff, a.active ? 'ok' : '')}</td>
    </tr>`).join('');
    return k.tablePanel({
      title: k.T.activitiesTitle,
      tag: list.length,
      tools: admin ? `<button class="btn btn--sm btn--primary" type="button" data-tj-activity-new><span class="btn__plus" aria-hidden="true">+</span> ${esc(k.T.activityNew)}</button>` : '',
      head: [[k.T.activityName], [k.T.thBillable], [k.T.thStatus]],
      rows,
      empty: k.T.activitiesEmpty,
      emptyDesc: k.T.activitiesEmptyDesc,
    }) + `<p class="hint">${esc(k.T.activitiesDesc)}</p>`;
  }

  function wireActivities(root, rerender) {
    const refresh = async () => {
      forgetOptions();
      try { await loadActivities(); } catch { /* keep what is on screen */ }
      rerender();
    };
    if (k.d.isAdmin()) {
      $$('[data-tj-activity]', root).forEach(row => {
        const open = () => openActivity((activities || []).find(a => a.id === row.dataset.tjActivity), refresh);
        row.addEventListener('click', open);
        row.addEventListener('keydown', e => { if (e.key === 'Enter') open(); });
      });
    }
    $('[data-tj-activity-new]', root)?.addEventListener('click', () => openActivity(null, refresh));
  }

  function openActivity(activity, refresh) {
    const editing = activity?.id ? activity : null;
    const form = document.createElement('form');
    form.className = 'form';
    form.innerHTML = `
      <div class="form__grid">
        <div class="form__row"><label class="lbl" for="ta-name">${esc(k.T.activityName)} <span class="req">●</span></label>
          <input class="inp" id="ta-name" maxlength="80" required placeholder="${esc(k.T.activityNamePh)}" value="${esc(editing?.name || '')}" /></div>
        <div class="form__row"><label class="lbl" for="ta-code">${esc(k.T.activityCode)} <span class="opt">${esc(k.d.STR.optional)}</span></label>
          <input class="inp inp--mono" id="ta-code" maxlength="20" autocomplete="off" placeholder="${esc(k.T.activityCodePh)}" value="${esc(editing?.code || '')}" /></div>
      </div>
      <div class="form__row form__row--full"><label class="form__check"><input type="checkbox" id="ta-billable"${!editing || editing.billable ? ' checked' : ''} /> ${esc(k.T.activityBillable)}</label></div>
      ${editing ? `<div class="form__row form__row--full"><label class="form__check"><input type="checkbox" id="ta-active"${editing.active ? ' checked' : ''} /> ${esc(k.T.activityActive)}</label></div>` : ''}
      <div class="actions">
        <button class="btn btn--primary" type="submit">${esc(editing ? k.d.STR.clientSaveChanges : k.T.activitySave)}</button>
        <button class="btn btn--ghost" type="button" data-form-cancel>${esc(k.d.STR.cancel)}</button>
        ${editing ? `<button class="btn btn--ghost" type="button" data-ta-delete>${esc(k.T.activityDelete)}</button>` : ''}
      </div>`;
    $('[data-form-cancel]', form).addEventListener('click', () => k.d.closeDrawer());
    $('[data-ta-delete]', form)?.addEventListener('click', async () => {
      if (!await k.d.confirmDialog({ title: k.T.activityDeleteTitle, body: k.T.activityDeleteBody, okLabel: k.T.activityDelete })) return;
      try {
        await k.d.api(`/app/api/timesheets/activities/${encodeURIComponent(editing.id)}`, { method: 'DELETE' });
        k.d.toast(k.T.activityDeleted);
        k.d.closeDrawer();
        refresh();
      } catch (err) { if (err.message !== 'unauthorized') k.d.toast(k.errorText(err)); }
    });
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const payload = {
        name: $('#ta-name', form).value.trim(),
        code: $('#ta-code', form).value.trim(),
        billable: $('#ta-billable', form).checked,
        ...(editing ? { active: $('#ta-active', form).checked } : {}),
      };
      const btn = $('button[type=submit]', form);
      btn.disabled = true;
      try {
        await k.d.api(editing ? `/app/api/timesheets/activities/${encodeURIComponent(editing.id)}` : '/app/api/timesheets/activities', {
          method: editing ? 'PATCH' : 'POST',
          body: JSON.stringify(payload),
        });
        k.d.toast(k.T.activitySaved);
        k.d.closeDrawer();
        refresh();
      } catch (err) {
        btn.disabled = false;
        if (err.message !== 'unauthorized') k.d.toast(k.errorText(err));
      }
    });
    k.d.openDrawer(editing ? k.T.activityEdit : k.T.activityNew, form);
  }

  window.TimeJobsUI = {
    init, loadOptions, forgetOptions, label, summary, fields, missing, missingText, needsOf, openSwitch, segmentsHtml, openSegments,
    loadReport, reportNav, reportStats, reportHtml, wireReport, loadActivities, activitiesHtml, wireActivities,
  };
})();
