/* Time clock (/app): an employee's own page (My hours) and the team's Timesheets: who is working, the week's
   shifts and totals, corrections with their reason, work sites and rules, plus the hours and phone on an
   employee's record. app.js mounts it and passes its shared helpers to init(); the server applies every rule. */
(function () {
  let d = null;
  const T = I18N.section('app.time');
  const tr = (key, params) => I18N.t(`app.time.${key}`, params);
  /** A catalog string, or [fallback] when the key is missing (a code this page doesn't know yet). */
  const known = (key, fallback, params) => {
    const value = I18N.t(`app.time.${key}`, params);
    return typeof value === 'string' && value !== `app.time.${key}` ? value : fallback;
  };
  const esc = s => d.escapeHTML(s == null ? '' : String(s));
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

  const MINE = 'my-hours';
  const TEAM = 'timesheets';
  const STATE_TONES = { OFF: '', WORKING: 'ok', ON_BREAK: 'warn' };
  const FLAG_TONES = {
    MOCK_LOCATION: 'bad', OUTSIDE_SITE: 'warn', NO_LOCATION: 'warn', LOW_ACCURACY: 'warn', UNVERIFIED: 'warn',
    LONG_SHIFT: 'warn', MISSED_CLOCK_OUT: 'warn', EDITED: 'info', MANUAL: 'info',
  };
  const POLICIES = { location: ['OFF', 'OPTIONAL', 'REQUIRED'], geofence: ['FLAG', 'BLOCK'], biometric: ['OFF', 'OPTIONAL', 'REQUIRED'] };
  const TABS = { shifts: 'tabShifts', sites: 'tabSites', rules: 'tabRules' };
  const FILTERS = {
    review: s => isPendingClosed(s),
    flagged: s => s.flags.length > 0,
    approved: s => s.review === 'APPROVED',
    all: () => true,
  };
  const FILTER_LABELS = { review: 'filterReview', flagged: 'filterFlagged', approved: 'filterApproved', all: 'filterAll' };

  // offset: server clock minus this browser's, so a running shift ticks on the server's time.
  const mine = { status: null, shifts: [], offset: 0, busy: false };
  const team = { tab: 'shifts', weekStart: '', filter: 'review', employee: '', data: null, board: null, sites: null, settings: null, employees: null, clients: null, offset: 0 };
  let ticker = null;

  const locale = () => d.uiLocale();
  const isPendingClosed = s => s.status === 'CLOSED' && s.review === 'PENDING';

  function hm(minutes) {
    const total = Math.max(0, Math.floor(minutes || 0));
    const h = Math.floor(total / 60);
    return h ? tr('hm', { h, m: total % 60 }) : tr('minutesOnly', { m: total });
  }
  const hoursValue = minutes => new Intl.NumberFormat(locale(), { maximumFractionDigits: 1 }).format((minutes || 0) / 60);
  function distance(m) {
    const meters = Math.max(0, Number(m) || 0);
    return meters >= 1000
      ? new Intl.NumberFormat(locale(), { style: 'unit', unit: 'kilometer', maximumFractionDigits: 1 }).format(meters / 1000)
      : new Intl.NumberFormat(locale(), { style: 'unit', unit: 'meter', maximumFractionDigits: 0 }).format(meters);
  }
  const dayText = day => d.fmtDayKey(day, { weekday: 'short', day: 'numeric', month: 'short' });
  const clockTime = ms => new Date(ms).toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit', timeZone: d.tenantTz() });
  const mapHref = (lat, lng) => `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${lat},${lng}`)}`;
  const breakRange = b => `${b.startTime}–${b.endTime || '…'}`;
  function timeRange(s) {
    if (!s.endTime) return `${s.startTime}–…`;
    return `${s.startTime}–${s.endTime}${s.endsNextDay ? ` (${T.nextDay})` : ''}`;
  }
  function addTime(hhmm, minutes) {
    const [h, m] = String(hhmm || '00:00').split(':').map(Number);
    const total = (((h * 60 + m + minutes) % 1440) + 1440) % 1440;
    return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
  }

  /** Minutes worked so far: an open shift runs until [now], less its breaks (an open break runs too). */
  function workedNow(shift, now) {
    if (!shift) return 0;
    if (shift.status !== 'OPEN') return shift.workedMinutes || 0;
    const start = Date.parse(shift.startAt);
    const breaks = (shift.breaks || []).reduce((sum, b) => {
      const from = Math.max(Date.parse(b.startAt), start);
      const to = b.endAt ? Date.parse(b.endAt) : now;
      return sum + Math.max(0, to - from);
    }, 0);
    return Math.max(0, Math.floor((now - start - breaks) / 60000));
  }
  const mineNow = () => Date.now() + mine.offset;
  const teamNow = () => Date.now() + team.offset;

  const pill = (text, tone) => `<span class="pill${tone ? ` pill--${tone}` : ''}">${esc(text)}</span>`;
  const flagPills = flags => (flags || []).map(f => pill(known(`flags.${f}`, f), FLAG_TONES[f] || '')).join('');
  function reviewPill(s) {
    if (s.status === 'OPEN') return pill(tr('review.OPEN'), 'info');
    return s.review === 'APPROVED' ? pill(tr('review.APPROVED'), 'ok') : pill(tr('review.PENDING'), 'warn');
  }

  /** Where a punch happened, as its site verdict, accuracy and whether the location was simulated. */
  function punchWhere(p) {
    const parts = [];
    if (p.siteName) parts.push(p.inside ? tr('atSite', { site: p.siteName }) : tr('fromSite', { distance: distance(p.siteDistanceM), site: p.siteName }));
    else if (p.latitude != null || p.accuracyM != null) parts.push(T.located);
    else parts.push([T.noLocation, p.locationError ? known(`locationError.${p.locationError}`, '') : ''].filter(Boolean).join(' · '));
    if (p.accuracyM != null) parts.push(tr('accuracy', { m: Math.round(p.accuracyM) }));
    if (p.mocked) parts.push(known('flags.MOCK_LOCATION', ''));
    return parts.filter(Boolean).join(' · ');
  }

  function errorText(err, locationError) {
    const code = err?.code || '';
    if (code === 'location_required' && locationError) return known(`geoErrors.${locationError}`, tr('errors.location_required'));
    if (code === 'outside_sites') return tr('errors.outside_sites', { distance: distance(err.body?.distanceM), site: err.body?.siteName || '' });
    return code ? known(`errors.${code}`, T.saveFailed) : T.saveFailed;
  }

  /** One fix from the browser, only when asked for; rejects with the reason the server stores. */
  function currentPosition() {
    return new Promise((resolve, reject) => {
      if (!navigator.geolocation) { reject('UNAVAILABLE'); return; }
      navigator.geolocation.getCurrentPosition(
        pos => resolve({ latitude: pos.coords.latitude, longitude: pos.coords.longitude, accuracyM: pos.coords.accuracy }),
        err => reject(err.code === 1 ? 'PERMISSION_DENIED' : err.code === 3 ? 'TIMEOUT' : 'UNAVAILABLE'),
        { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 },
      );
    });
  }

  /** A list table that reads as cards on a phone (`.tbl--stack`); [head] is `[label, class]` pairs. */
  function tablePanel({ title, tag = null, tools = '', filters = '', head, rows, empty, emptyDesc = '' }) {
    const body = rows || `<tr><td colspan="${head.length}"><div class="empty"><p class="empty__title">${esc(empty)}</p>${emptyDesc ? `<p class="empty__desc">${esc(emptyDesc)}</p>` : ''}</div></td></tr>`;
    return `<div class="panel">
      <div class="panel__head"><h2 class="panel__title">${esc(title)}${tag != null ? ` <span class="tag">${esc(String(tag))}</span>` : ''}</h2>${tools ? `<div class="panel__tools">${tools}</div>` : ''}</div>
      ${filters ? `<div class="panel__filters">${filters}</div>` : ''}
      <div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr>${head.map(([label, cls]) => `<th${cls ? ` class="${cls}"` : ''}>${esc(label)}</th>`).join('')}</tr></thead><tbody>${body}</tbody></table></div>
    </div>`;
  }

  /** A shift as a table row; the team's rows lead with the employee, an employee's with the day. */
  function shiftRow(s, withEmployee, now) {
    const marker = s.status === 'OPEN' ? ' is-current' : s.review === 'APPROVED' ? ' is-paid' : '';
    const label = text => ` data-label="${esc(text)}"`;
    return `<tr class="conversation-row${marker}" data-shift="${esc(s.id)}" tabindex="0">
      ${withEmployee ? `<td class="name">${esc(s.employeeName || '')}</td>` : ''}
      <td class="mono"${withEmployee ? label(T.thDay) : ''}>${esc(dayText(s.day))}</td>
      <td class="mono"${label(T.thTime)}>${esc(timeRange(s))}</td>
      <td class="num"${label(T.thBreaks)}>${esc(s.breakMinutes ? hm(s.breakMinutes) : '—')}</td>
      <td class="num"${label(T.thWorked)}>${esc(hm(workedNow(s, now)))}</td>
      <td${label(T.thSite)}>${esc(s.siteName || '—')}</td>
      <td${label(T.thFlags)}>${s.flags.length ? `<div class="tbl__pills">${flagPills(s.flags)}</div>` : '<span class="muted">—</span>'}</td>
      <td${label(T.thStatus)}>${reviewPill(s)}</td>
    </tr>`;
  }

  /** Rows open their shift on click, and table rows on Enter too (a button already turns Enter into a click). */
  function wireRows(root, open) {
    $$('[data-shift]', root).forEach(row => {
      row.addEventListener('click', () => open(row.dataset.shift));
      if (row.tagName !== 'BUTTON') row.addEventListener('keydown', e => { if (e.key === 'Enter') open(row.dataset.shift); });
    });
  }

  // ── An employee's own page ─────────────────────────────────────────────────────────────────────

  async function loadMine() {
    const [status, shifts] = await Promise.all([d.api('/app/api/portal/time'), d.api('/app/api/portal/time/shifts')]);
    setStatus(status);
    mine.shifts = shifts || [];
  }

  function setStatus(status) {
    mine.status = status;
    mine.offset = Date.parse(status.serverTime) - Date.now();
  }

  function mergeShift(shift) {
    mine.shifts = [shift, ...mine.shifts.filter(s => s.id !== shift.id)].sort((a, b) => Date.parse(b.startAt) - Date.parse(a.startAt));
  }

  /** Today and this week, with the running shift counted up to now. */
  function mineTotals(now) {
    const s = mine.status;
    const open = s.open;
    const running = open ? workedNow(open, now) - open.workedMinutes : 0;
    const today = d.todayKey();
    return {
      today: s.todayMinutes + (open && open.day === today ? running : 0),
      week: s.weekMinutes + (open && open.day >= d.periodKey(today, 'week') ? running : 0),
    };
  }

  function renderMine(root) {
    const s = mine.status;
    if (!s) { root.innerHTML = d.hero(d.labels[MINE], T.myDesc); return; }
    const now = mineNow();
    const totals = mineTotals(now);
    root.innerHTML = d.hero(d.labels[MINE], T.myDesc, d.statCards([
      { label: T.statToday, value: hm(totals.today) },
      { label: T.statWeek, value: tr('weekOfLimit', { worked: hoursValue(totals.week), limit: s.policy.weeklyHours }) },
    ])) + overdueHtml(s) + clockHtml(s, now) + historyHtml(now);
    $$('[data-punch]', root).forEach(b => b.addEventListener('click', () => punch(b.dataset.punch)));
    $('[data-overdue]', root)?.addEventListener('submit', closeForgotten);
    wireRows(root, id => openShift(id));
    startTicker();
  }

  function clockHtml(s, now) {
    const p = s.policy;
    const appOnly = p.biometric === 'REQUIRED';
    const open = s.open;
    const lastBreak = open?.breaks?.[open.breaks.length - 1];
    const line = s.state === 'WORKING' ? tr('workingSince', { time: open.startTime })
      : s.state === 'ON_BREAK' ? tr('onBreakSince', { time: lastBreak?.startTime || open.startTime })
        : T.offHint;
    const last = open?.punches?.[open.punches.length - 1];
    const buttons = {
      OFF: [['IN', T.clockIn, 'btn--primary']],
      WORKING: [['BREAK_START', T.breakStart, 'btn--ghost'], ['OUT', T.clockOut, 'btn--primary']],
      ON_BREAK: [['BREAK_END', T.breakEnd, 'btn--primary'], ['OUT', T.clockOut, 'btn--ghost']],
    }[s.state] || [];
    return `<section class="panel clock" data-state="${esc(s.state)}">
      <div class="clock__head"><h2 class="panel__title">${esc(T.clockTitle)}</h2>${pill(known(`state.${s.state}`, s.state), STATE_TONES[s.state])}</div>
      <p class="clock__time" data-clock-big>${esc(s.state === 'OFF' ? clockTime(now) : hm(workedNow(open, now)))}</p>
      <p class="clock__line" data-clock-line>${esc(line)}</p>
      ${last ? `<p class="clock__meta">${esc(tr('lastPunch', { what: `${known(`punchType.${last.type}`, last.type)} ${last.time} · ${punchWhere(last)}` }))}</p>` : ''}
      ${appOnly ? `<div class="notice notice--info" role="status"><div class="notice__text"><span>${esc(T.useApp)}</span></div></div>` : ''}
      <div class="clock__actions">${buttons.map(([type, label, cls]) => `<button class="btn ${cls}" type="button" data-punch="${type}"${appOnly ? ' disabled' : ''}>${esc(label)}</button>`).join('')}</div>
      <p class="hint">${esc(p.location === 'OFF' ? T.locationOffNote : T.locationNote)}</p>
    </section>`;
  }

  /** Still clocked in past the company's limit: say when you stopped, before anything else. */
  function overdueHtml(s) {
    if (!s.overdue || !s.open) return '';
    const guess = addTime(s.open.startTime, s.policy.dailyHours * 60);
    return `<div class="notice notice--warn" role="status">
      <div class="notice__text"><strong>${esc(tr('overdueTitle', { when: d.fmtWhen(s.open.startAt) }))}</strong><span>${esc(T.overdueBody)}</span></div>
      <form class="notice__actions clock__fix" data-overdue>
        <label class="lbl" for="tc-end">${esc(T.overdueEnd)}</label>
        <input class="inp inp--mono" id="tc-end" type="time" required value="${esc(guess)}" />
        <button class="btn btn--sm btn--primary" type="submit">${esc(T.overdueSave)}</button>
      </form>
    </div>`;
  }

  function historyHtml(now) {
    const weeks = new Map();
    mine.shifts.forEach(s => {
      const week = d.periodKey(s.day, 'week');
      if (!weeks.has(week)) weeks.set(week, []);
      weeks.get(week).push(s);
    });
    const rows = [...weeks.entries()].sort((a, b) => b[0].localeCompare(a[0])).map(([week, list]) => {
      const total = list.reduce((sum, s) => sum + workedNow(s, now), 0);
      const label = `${tr('weekRow', { date: d.fmtDayKey(week, { day: 'numeric', month: 'short' }) })} · ${hm(total)}`;
      return `<tr class="is-day"><td colspan="7">${esc(label)}</td></tr>${list.map(s => shiftRow(s, false, now)).join('')}`;
    }).join('');
    return tablePanel({
      title: T.historyTitle,
      tag: mine.shifts.length,
      head: [[T.thDay], [T.thTime], [T.thBreaks, 'right'], [T.thWorked, 'right'], [T.thSite], [T.thFlags], [T.thStatus]],
      rows,
      empty: T.historyEmpty,
      emptyDesc: T.historyEmptyDesc,
    });
  }

  async function punch(type) {
    if (mine.busy || !mine.status) return;
    const policy = mine.status.policy;
    mine.busy = true;
    $$('[data-punch]').forEach(b => { b.disabled = true; });
    let location = null;
    let locationError = null;
    if (policy.location !== 'OFF') {
      const line = $('[data-clock-line]');
      if (line) line.textContent = T.locating;
      try { location = await currentPosition(); } catch (code) { locationError = typeof code === 'string' ? code : 'UNAVAILABLE'; }
    }
    try {
      const res = await d.api('/app/api/portal/time/punches', { method: 'POST', body: JSON.stringify({ type, channel: 'WEB', location, locationError }) });
      setStatus(res.status);
      mergeShift(res.shift);
      d.toast(known(`punched.${type}`, ''));
    } catch (err) {
      if (err.message !== 'unauthorized') d.toast(errorText(err, locationError));
    } finally {
      mine.busy = false;
      if (d.state.active === MINE) d.render();
    }
  }

  async function closeForgotten(e) {
    e.preventDefault();
    const form = e.currentTarget;
    const open = mine.status?.open;
    const end = $('#tc-end', form).value;
    if (!open || !end) return;
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const res = await d.api(`/app/api/portal/time/shifts/${encodeURIComponent(open.id)}/close`, { method: 'POST', body: JSON.stringify({ end }) });
      setStatus(res.status);
      mergeShift(res.shift);
      d.toast(T.overdueSaved);
      d.render();
    } catch (err) {
      btn.disabled = false;
      if (err.message !== 'unauthorized') d.toast(errorText(err));
    }
  }

  /** The running time ticks while My hours is on screen; it stops itself once the page is gone. */
  function startTicker() {
    if (ticker) return;
    ticker = setInterval(() => {
      const big = $('[data-clock-big]');
      if (d.state.active !== MINE || !mine.status || !big) {
        clearInterval(ticker);
        ticker = null;
        return;
      }
      const now = mineNow();
      big.textContent = mine.status.state === 'OFF' ? clockTime(now) : hm(workedNow(mine.status.open, now));
    }, 15000);
  }

  /** Sidebar figures on an employee's own session: today and this week. */
  function kpis() {
    if (!mine.status) return null;
    const totals = mineTotals(mineNow());
    return [{ label: T.statToday, value: hm(totals.today) }, { label: T.statWeek, value: hm(totals.week) }];
  }

  // ── One shift: the times, what was punched, the history ────────────────────────────────────────

  async function openShift(id) {
    const forTeam = !d.isPortal();
    let s = null;
    try {
      s = forTeam ? await d.api(`/app/api/timesheets/shifts/${encodeURIComponent(id)}`) : mine.shifts.find(x => x.id === id);
    } catch (err) {
      if (err.message !== 'unauthorized') d.toast(T.loadFailed);
      return;
    }
    if (!s) { d.toast(T.loadFailed); return; }
    const now = forTeam ? teamNow() : mineNow();
    const here = { key: `shift:${s.id}`, label: [s.employeeName, dayText(s.day)].filter(Boolean).join(' · '), open: () => openShift(s.id) };
    const name = forTeam ? s.employeeName || '' : dayText(s.day);
    const link = forTeam && !d.linksBackTo(`employee:${s.employeeId}`);
    const reviewed = s.reviewedAt ? { label: T.metaReviewed, value: [d.fmtDate(s.reviewedAt), forTeam ? s.reviewedBy : ''].filter(Boolean).join(' · ') } : null;
    const actions = forTeam ? [
      isPendingClosed(s) ? `<button class="btn btn--sm btn--accent" type="button" data-shift-approve>${esc(T.approve)}</button>` : '',
      s.status === 'CLOSED' ? `<button class="btn btn--sm" type="button" data-shift-correct>${esc(T.correct)}</button>` : '',
      s.status === 'OPEN' ? `<button class="btn btn--sm" type="button" data-shift-close>${esc(T.closeShift)}</button>` : '',
      link ? `<button class="btn btn--sm btn--ghost" type="button" data-shift-employee-open>${esc(tr('openEmployee', { name }))}</button>` : '',
    ] : [];
    const body = document.createElement('div');
    body.className = 'form';
    body.innerHTML = `
      <div class="detail__head">
        ${link
          ? `<button class="detail__client detail__client--link" type="button" data-shift-employee aria-label="${esc(tr('openEmployee', { name }))}">${esc(name)}</button>`
          : `<div class="detail__client">${esc(name)}</div>`}
        <div class="detail__amount">${reviewPill(s)}<span class="detail__figure">${esc(hm(workedNow(s, now)))}</span></div>
      </div>
      ${d.detailMeta([
        { label: T.metaDay, value: dayText(s.day) },
        { label: T.metaStart, value: s.startTime },
        { label: T.metaEnd, value: s.endTime ? `${s.endTime}${s.endsNextDay ? ` (${T.nextDay})` : ''}` : '…' },
        { label: T.metaBreaks, value: s.breaks.length ? `${hm(s.breakMinutes)} · ${s.breaks.map(breakRange).join(', ')}` : '—' },
        s.siteName ? { label: T.metaSite, value: s.siteName } : null,
        reviewed,
      ])}
      ${s.flags.length ? `<div class="actions">${flagPills(s.flags)}</div>` : ''}
      ${punchesHtml(s)}
      ${editsHtml(s)}
      ${noteHtml(s, forTeam)}
      ${actions.some(Boolean) ? `<div class="detail__foot">${actions.join('')}</div>` : ''}`;
    $$('[data-map]', body).forEach(a => a.addEventListener('click', e => e.stopPropagation()));
    const openEmployee = () => d.openFrom(here, () => d.openPayeeDrawer('employee', s.employeeId));
    $('[data-shift-employee]', body)?.addEventListener('click', openEmployee);
    $('[data-shift-employee-open]', body)?.addEventListener('click', openEmployee);
    $('[data-shift-approve]', body)?.addEventListener('click', async e => {
      if (await approve([s.id], e.currentTarget)) openShift(s.id);
    });
    $('[data-shift-correct]', body)?.addEventListener('click', () => d.openFrom(here, () => openTimesForm(s, 'correct')));
    $('[data-shift-close]', body)?.addEventListener('click', () => d.openFrom(here, () => openTimesForm(s, 'close')));
    $('[data-shift-note]', body)?.addEventListener('submit', async e => {
      e.preventDefault();
      const btn = $('button[type=submit]', e.currentTarget);
      btn.disabled = true;
      try {
        const saved = await d.api(`/app/api/portal/time/shifts/${encodeURIComponent(s.id)}`, { method: 'PATCH', body: JSON.stringify({ note: $('#ts-note', body).value }) });
        mergeShift(saved);
        d.toast(T.noteSaved);
        btn.disabled = false;
      } catch (err) {
        btn.disabled = false;
        if (err.message !== 'unauthorized') d.toast(errorText(err));
      }
    });
    d.openDrawer(forTeam ? name : `${T.detailEyebrow} · ${name}`, body, false, { eyebrow: T.detailEyebrow });
  }

  function punchesHtml(s) {
    if (!s.punches?.length) return '';
    const rows = s.punches.map(p => {
      const how = [known(`channel.${p.channel}`, p.channel), p.recordedAt ? tr('recordedAt', { when: d.fmtDate(p.recordedAt) }) : '', p.by || ''].filter(Boolean).join(' · ');
      const verified = p.verified ? pill(tr('verifiedOn', { device: p.deviceName || '' }), 'ok')
        : p.channel === 'APP' || p.channel === 'WEB' ? pill(T.notVerified, '') : '';
      const map = p.latitude != null && p.longitude != null
        ? ` <a href="${esc(mapHref(p.latitude, p.longitude))}" target="_blank" rel="noopener" data-map>${esc(T.openMap)}</a>` : '';
      return `<tr>
        <td class="name">${esc(known(`punchType.${p.type}`, p.type))}<div class="sub">${esc(how)}</div></td>
        <td class="mono" data-label="${esc(T.thTime)}">${esc(p.time)}</td>
        <td data-label="${esc(T.thSite)}">${esc(p.channel === 'TEAM' || p.channel === 'CORRECTION' ? '—' : punchWhere(p))}${map}${p.flags.length ? `<div class="tbl__pills">${flagPills(p.flags)}</div>` : ''}</td>
        <td data-label="${esc(T.thStatus)}">${verified}</td>
      </tr>`;
    }).join('');
    return `<div class="panel"><header class="panel__head"><h2 class="panel__title">${esc(T.punchesTitle)}</h2></header>
      <div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr><th>${esc(T.punchesTitle)}</th><th>${esc(T.thTime)}</th><th>${esc(T.thSite)}</th><th></th></tr></thead><tbody>${rows}</tbody></table></div></div>`;
  }

  function editsHtml(s) {
    if (!s.edits?.length) return '';
    const times = t => [`${t.startTime}–${t.endTime || '…'}`, t.breaks.length ? t.breaks.map(breakRange).join(', ') : ''].filter(Boolean).join(' · ');
    const rows = [...s.edits].reverse().map(e => {
      const change = e.before ? `${T.before}: ${times(e.before)} → ${T.after}: ${times(e.after)}` : `${T.added}: ${times(e.after)}`;
      return `<li><button class="worklist__item" type="button" data-tone="info" disabled>
        <span class="worklist__dot" aria-hidden="true"></span>
        <span class="worklist__main"><span class="worklist__title">${esc(e.reason)}</span><span class="worklist__detail">${esc(change)}</span></span>
        <span class="worklist__side"><span class="worklist__when">${esc(tr('editedBy', { who: e.by, when: d.relTime(e.at) }))}</span></span>
      </button></li>`;
    }).join('');
    return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(T.historyChanges)} <span class="tag">${s.edits.length}</span></h2></header>
      <ul class="worklist worklist--wrap">${rows}</ul></section>`;
  }

  /** The employee writes a note on their own shift until it is approved; the team reads it. */
  function noteHtml(s, forTeam) {
    if (forTeam || s.review === 'APPROVED') {
      return s.note ? `<div class="form__row form__row--full"><span class="lbl">${esc(T.note)}</span><p class="hint">${esc(s.note)}</p></div>` : '';
    }
    return `<form class="form__row form__row--full" data-shift-note>
      <label class="lbl" for="ts-note">${esc(T.note)} <span class="opt">${esc(d.STR.optional)}</span></label>
      <textarea class="txt" id="ts-note" maxlength="500" placeholder="${esc(T.notePh)}">${esc(s.note || '')}</textarea>
      <div class="actions"><button class="btn btn--sm" type="submit">${esc(T.noteSave)}</button></div>
    </form>`;
  }

  // ── The team ───────────────────────────────────────────────────────────────────────────────────

  async function loadTeam() {
    if (!team.weekStart) team.weekStart = d.currentPeriodKey('week');
    const from = team.weekStart;
    const to = d.addDayKey(from, 6);
    const who = team.employee ? `&employeeId=${encodeURIComponent(team.employee)}` : '';
    const [data, board, employees] = await Promise.all([
      d.api(`/app/api/timesheets?from=${from}&to=${to}${who}`),
      d.api('/app/api/timesheets/board'),
      team.employees ? Promise.resolve(team.employees) : d.api('/app/api/crm/employees').catch(() => []),
    ]);
    team.data = data;
    team.board = board;
    team.offset = Date.parse(board.serverTime) - Date.now();
    team.employees = employees;
    if (team.tab === 'sites') await loadSites();
    if (team.tab === 'rules') team.settings = await d.api('/app/api/timesheets/settings');
  }

  async function loadSites() {
    const [sites, clients] = await Promise.all([
      d.api('/app/api/timesheets/sites'),
      team.clients || !d.hasModule('clients') ? Promise.resolve(team.clients) : d.api('/app/api/crm/clients').catch(() => null),
    ]);
    team.sites = sites;
    team.clients = clients;
  }

  function renderTeam(root) {
    const data = team.data;
    if (!data) { root.innerHTML = d.hero(d.labels[TEAM], T.desc); return; }
    const now = teamNow();
    const working = team.board?.working || [];
    const worked = data.totals.reduce((sum, t) => sum + t.workedMinutes, 0);
    const over = data.totals.reduce((sum, t) => sum + Math.max(t.overDailyMinutes, t.overWeeklyMinutes), 0);
    const nav = team.tab === 'shifts' ? d.periodNav(team.weekStart, 'week', 'data-ts-period', d.CRM.services) : '';
    const stats = d.statCards([
      { label: T.statWorking, value: working.length },
      { label: T.statToReview, value: data.shifts.filter(isPendingClosed).length },
      { label: T.statHours, value: hm(worked) },
      { label: T.statOver, value: over ? hm(over) : '—' },
    ]);
    const tabs = `<div class="settings-tabs" role="tablist">${Object.entries(TABS).map(([id, key]) => `<button class="chip${team.tab === id ? ' is-on' : ''}" type="button" role="tab" aria-selected="${team.tab === id}" data-ts-tab="${id}">${esc(T[key])}</button>`).join('')}</div>`;
    const body = {
      shifts: () => boardHtml(working, now) + shiftsHtml(data.shifts, now) + totalsHtml(data.totals),
      sites: () => sitesHtml(),
      rules: () => rulesHtml(),
    }[team.tab]();
    root.innerHTML = d.hero(d.labels[TEAM], T.desc, stats, nav) + tabs + body;
    wireTeam(root);
  }

  function boardHtml(working, now) {
    const rows = working.map(s => {
      const tone = s.overdue ? 'warn' : s.onBreak ? 'info' : 'ok';
      const detail = [tr('boardSince', { time: s.startTime }), s.siteName, s.onBreak ? known('state.ON_BREAK', '') : ''].filter(Boolean).join(' · ');
      const side = s.overdue ? pill(T.overdueTag, 'warn') : `<span class="worklist__when">${esc(hm(workedNow(s, now)))}</span>`;
      return `<li><button class="worklist__item" type="button" data-shift="${esc(s.id)}" data-tone="${tone}">
        <span class="worklist__dot" aria-hidden="true"></span>
        <span class="worklist__main"><span class="worklist__title">${esc(s.employeeName || '')}</span><span class="worklist__detail">${esc(detail)}</span></span>
        <span class="worklist__side">${side}</span>
      </button></li>`;
    }).join('');
    return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(T.boardTitle)} <span class="tag">${working.length}</span></h2></header>
      ${rows ? `<ul class="worklist">${rows}</ul>` : `<div class="empty"><p class="empty__title">${esc(T.boardEmpty)}</p></div>`}</section>`;
  }

  function shiftsHtml(shifts, now) {
    const counts = Object.fromEntries(Object.entries(FILTERS).map(([id, test]) => [id, shifts.filter(test).length]));
    const visible = shifts.filter(FILTERS[team.filter] || FILTERS.all).sort((a, b) => Date.parse(b.startAt) - Date.parse(a.startAt));
    const clean = shifts.filter(s => isPendingClosed(s) && !s.flags.length);
    const chips = Object.keys(FILTERS).map(id => `<button class="chip${team.filter === id ? ' is-on' : ''}" type="button" data-ts-filter="${id}">${esc(T[FILTER_LABELS[id]])}<span class="chip__count">${counts[id]}</span></button>`).join('');
    const employees = `<select class="sel" data-ts-employee aria-label="${esc(T.thEmployee)}"><option value="">${esc(T.allEmployees)}</option>${(team.employees || []).map(e => `<option value="${esc(e.id)}"${team.employee === e.id ? ' selected' : ''}>${esc(e.name)}</option>`).join('')}</select>`;
    const tools = [
      clean.length ? `<button class="btn btn--sm btn--accent" type="button" data-ts-approve-clean>${esc(tr('approveClean', { n: clean.length }))}</button>` : '',
      `<button class="btn btn--sm btn--ghost" type="button" data-ts-export>${esc(T.exportCsv)}</button>`,
    ].join('');
    return tablePanel({
      title: T.shiftsTitle,
      tag: visible.length,
      tools,
      filters: employees + chips,
      head: [[T.thEmployee], [T.thDay], [T.thTime], [T.thBreaks, 'right'], [T.thWorked, 'right'], [T.thSite], [T.thFlags], [T.thStatus]],
      rows: visible.map(s => shiftRow(s, true, now)).join(''),
      empty: T.shiftsEmpty,
      emptyDesc: T.shiftsEmptyDesc,
    });
  }

  function totalsHtml(totals) {
    if (!totals.length) return '';
    const label = text => ` data-label="${esc(text)}"`;
    const rows = totals.map(t => `<tr class="conversation-row" data-ts-person="${esc(t.employeeId)}" tabindex="0">
      <td class="name">${esc(t.employeeName)}<div class="sub mono">${esc(t.employeeNumber)}</div></td>
      <td class="num"${label(T.thDays)}>${t.days}</td>
      <td class="num"${label(T.thWorked)}>${esc(hm(t.workedMinutes))}</td>
      <td class="num"${label(T.thOverDaily)}>${t.overDailyMinutes ? pill(hm(t.overDailyMinutes), 'warn') : '—'}</td>
      <td class="num"${label(T.thOverWeekly)}>${t.overWeeklyMinutes ? pill(hm(t.overWeeklyMinutes), 'warn') : '—'}</td>
      <td class="num"${label(T.thToReview)}>${t.toReview}</td>
    </tr>`).join('');
    return tablePanel({
      title: T.totalsTitle,
      head: [[T.thEmployee], [T.thDays, 'right'], [T.thWorked, 'right'], [T.thOverDaily, 'right'], [T.thOverWeekly, 'right'], [T.thToReview, 'right']],
      rows,
      empty: '',
    });
  }

  function sitesHtml() {
    const sites = team.sites || [];
    const admin = d.isAdmin();
    const label = text => ` data-label="${esc(text)}"`;
    const rows = sites.map(s => `<tr class="conversation-row${s.active ? '' : ' is-draft'}" data-ts-site="${esc(s.id)}"${admin ? ' tabindex="0"' : ''}>
      <td class="name">${esc(s.name)}${s.address ? `<div class="sub">${esc(s.address)}</div>` : ''}</td>
      <td class="num"${label(T.thRadius)}>${esc(distance(s.radiusM))}</td>
      <td${label(T.thClient)}>${esc(s.clientName || '—')}</td>
      <td${label(T.thStatus)}>${pill(s.active ? T.siteActive : T.siteOff, s.active ? 'ok' : '')}</td>
      <td class="right"><div class="actions"><a class="btn btn--sm btn--ghost" href="${esc(mapHref(s.latitude, s.longitude))}" target="_blank" rel="noopener" data-map>${esc(T.openMap)}</a></div></td>
    </tr>`).join('');
    return tablePanel({
      title: T.sitesTitle,
      tag: sites.length,
      tools: admin ? `<button class="btn btn--sm btn--primary" type="button" data-ts-site-new><span class="btn__plus" aria-hidden="true">+</span> ${esc(T.siteNew)}</button>` : '',
      head: [[T.thName], [T.thRadius, 'right'], [T.thClient], [T.thStatus], ['', 'right']],
      rows,
      empty: T.sitesEmpty,
      emptyDesc: T.sitesEmptyDesc,
    }) + `<p class="hint">${esc(T.sitesDesc)}</p>`;
  }

  function rulesHtml() {
    const s = team.settings;
    if (!s) return '';
    const off = s.canEdit ? '' : ' disabled';
    const select = (field, key, label) => `<div class="form__row form__row--full"><label class="lbl" for="tr-${field}">${esc(label)}</label>
      <select class="sel" id="tr-${field}"${off}>${POLICIES[field].map(v => `<option value="${v}"${s[field] === v ? ' selected' : ''}>${esc(tr(`${key}.${v}`))}</option>`).join('')}</select></div>`;
    const number = (field, label, min, max) => `<div class="form__row"><label class="lbl" for="tr-${field}">${esc(label)}</label>
      <input class="inp inp--mono" id="tr-${field}" type="number" min="${min}" max="${max}" step="1" required value="${esc(s[field])}"${off} /></div>`;
    return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(T.rulesTitle)}</h2></header>
      <form class="panel__body form" data-ts-rules>
        <p class="view__desc">${esc(T.rulesDesc)}</p>
        ${select('location', 'locationPolicy', T.ruleLocation)}
        ${select('geofence', 'geofencePolicy', T.ruleGeofence)}
        ${select('biometric', 'biometricPolicy', T.ruleBiometric)}
        <p class="hint">${esc(T.rulesBiometricNote)}</p>
        <div class="form__grid form__grid--3">
          ${number('maxShiftHours', T.ruleMaxShift, 4, 24)}
          ${number('dailyHours', T.ruleDaily, 1, 24)}
          ${number('weeklyHours', T.ruleWeekly, 1, 80)}
        </div>
        <p class="hint">${esc(T.ruleMaxShiftHint)}</p>
        <div class="notice notice--info"><div class="notice__text"><span>${esc(T.rulesInform)}</span></div></div>
        ${s.canEdit ? `<div class="actions"><button class="btn btn--primary" type="submit">${esc(T.rulesSave)}</button></div>` : `<p class="hint">${esc(T.rulesAdminOnly)}</p>`}
      </form></section>`;
  }

  function wireTeam(root) {
    const reload = async () => {
      try { await loadTeam(); } catch (err) { if (err.message !== 'unauthorized') d.toast(T.loadFailed); }
      if (d.state.active === TEAM) d.render();
    };
    $$('[data-ts-tab]', root).forEach(b => b.addEventListener('click', () => { team.tab = b.dataset.tsTab; reload(); }));
    $$('[data-ts-period]', root).forEach(b => b.addEventListener('click', () => {
      const step = b.dataset.tsPeriod;
      team.weekStart = step === 'current' ? d.currentPeriodKey('week') : d.shiftPeriodKey(team.weekStart, 'week', step === 'next' ? 1 : -1);
      reload();
    }));
    $$('[data-ts-filter]', root).forEach(b => b.addEventListener('click', () => { team.filter = b.dataset.tsFilter; d.render(); }));
    $('[data-ts-employee]', root)?.addEventListener('change', e => { team.employee = e.target.value; reload(); });
    $('[data-ts-approve-clean]', root)?.addEventListener('click', e => {
      const ids = team.data.shifts.filter(s => isPendingClosed(s) && !s.flags.length).map(s => s.id);
      approve(ids, e.currentTarget);
    });
    $('[data-ts-export]', root)?.addEventListener('click', e => exportCsv(e.currentTarget));
    wireRows(root, id => openShift(id));
    $$('[data-ts-person]', root).forEach(row => {
      const open = () => d.openPayeeDrawer('employee', row.dataset.tsPerson);
      row.addEventListener('click', open);
      row.addEventListener('keydown', e => { if (e.key === 'Enter') open(); });
    });
    $$('[data-map]', root).forEach(a => a.addEventListener('click', e => e.stopPropagation()));
    if (d.isAdmin()) {
      $$('[data-ts-site]', root).forEach(row => {
        const open = () => openSiteForm((team.sites || []).find(s => s.id === row.dataset.tsSite));
        row.addEventListener('click', open);
        row.addEventListener('keydown', e => { if (e.key === 'Enter') open(); });
      });
    }
    $('[data-ts-site-new]', root)?.addEventListener('click', () => openSiteForm(null));
    $('[data-ts-rules]', root)?.addEventListener('submit', saveRules);
  }

  /** Approves [ids]; true when it went through. */
  async function approve(ids, btn) {
    if (!ids.length) return false;
    if (btn) btn.disabled = true;
    try {
      const res = await d.api('/app/api/timesheets/shifts/approve', { method: 'POST', body: JSON.stringify({ ids }) });
      d.toast(ids.length === 1 ? T.approvedToast : tr('approvedN', { n: res.approved }));
      await afterChange();
      return true;
    } catch (err) {
      if (btn) btn.disabled = false;
      if (err.message !== 'unauthorized') d.toast(errorText(err));
      return false;
    }
  }

  async function afterChange() {
    if (d.state.active !== TEAM) return;
    try { await loadTeam(); } catch { /* keep what is on screen */ }
    d.render();
  }

  async function exportCsv(btn) {
    btn.disabled = true;
    const from = team.weekStart;
    const to = d.addDayKey(from, 6);
    const who = team.employee ? `&employeeId=${encodeURIComponent(team.employee)}` : '';
    try {
      await d.download(`/app/api/timesheets/export.csv?from=${from}&to=${to}${who}`, `timesheets-${from}-${to}.csv`);
    } catch (err) {
      if (err.message !== 'unauthorized') d.toast(T.exportFailed);
    } finally {
      btn.disabled = false;
    }
  }

  async function saveRules(e) {
    e.preventDefault();
    const form = e.currentTarget;
    const btn = $('button[type=submit]', form);
    const value = id => $(`#tr-${id}`, form).value;
    btn.disabled = true;
    try {
      team.settings = await d.api('/app/api/timesheets/settings', {
        method: 'PUT',
        body: JSON.stringify({
          location: value('location'),
          geofence: value('geofence'),
          biometric: value('biometric'),
          maxShiftHours: Number(value('maxShiftHours')),
          dailyHours: Number(value('dailyHours')),
          weeklyHours: Number(value('weeklyHours')),
        }),
      });
      d.toast(T.rulesSaved);
      d.render();
    } catch (err) {
      btn.disabled = false;
      if (err.message !== 'unauthorized') d.toast(errorText(err));
    }
  }

  // ── Forms: correct, close and add a shift; a work site ─────────────────────────────────────────

  /** The breaks editor: from and to, on the company's clock. */
  function breaksField(breaks) {
    const removeIcon = '<svg width="12" height="12" viewBox="0 0 16 16" aria-hidden="true"><path d="M3 3 L13 13 M13 3 L3 13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>';
    const html = `<div class="lines-field">
      <div class="lbl" id="ts-breaks-label">${esc(T.formBreaks)} <span class="opt">${esc(d.STR.optional)}</span></div>
      <div class="lines lines--breaks" role="group" aria-labelledby="ts-breaks-label">
        <div class="lines__head"><span>${esc(T.breakFrom)}</span><span>${esc(T.breakTo)}</span><span></span></div>
        <div id="ts-breaks"></div>
        <p class="lines__empty" id="ts-breaks-empty">—</p>
        <div class="lines__foot"><button class="btn btn--sm btn--ghost" type="button" id="ts-add-break">${esc(T.addBreak)}</button></div>
      </div>
    </div>`;
    const wire = form => {
      const list = $('#ts-breaks', form);
      const empty = () => { $('#ts-breaks-empty', form).hidden = !!list.children.length; };
      const add = (b = {}) => {
        const row = document.createElement('div');
        row.className = 'line';
        row.innerHTML = `<input type="time" data-k="start" required aria-label="${esc(T.breakFrom)}" value="${esc(b.startTime || '')}" />
          <input type="time" data-k="end" required aria-label="${esc(T.breakTo)}" value="${esc(b.endTime || '')}" />
          <button type="button" class="l-rm" title="${esc(T.removeBreak)}" aria-label="${esc(T.removeBreak)}">${removeIcon}</button>`;
        $('.l-rm', row).addEventListener('click', () => { row.remove(); empty(); });
        list.appendChild(row);
        empty();
        return row;
      };
      (breaks || []).forEach(add);
      empty();
      $('#ts-add-break', form).addEventListener('click', () => $('input', add()).focus());
    };
    const collect = form => $$('#ts-breaks .line', form).map(row => ({ start: $('[data-k="start"]', row).value, end: $('[data-k="end"]', row).value }));
    return { html, wire, collect };
  }

  /** [mode]: `correct` a closed shift, `close` an open one, or `add` one nobody clocked. */
  function openTimesForm(shift, mode) {
    const adding = mode === 'add';
    const closing = mode === 'close';
    const breaks = breaksField(mode === 'correct' ? shift.breaks : []);
    const day = shift?.day || d.todayKey();
    const end = closing ? addTime(shift.startTime, (team.data?.policy?.dailyHours || 8) * 60) : shift?.endTime || '';
    const employees = (team.employees || []).filter(e => !e.archivedAt);
    const form = document.createElement('form');
    form.className = 'form';
    form.innerHTML = `
      ${adding ? `<div class="form__row form__row--full"><label class="lbl" for="ts-employee">${esc(T.formEmployee)} <span class="req">●</span></label>
        <select class="sel" id="ts-employee" required><option value="">${esc(T.chooseEmployee)}</option>${employees.map(e => `<option value="${esc(e.id)}"${team.employee === e.id ? ' selected' : ''}>${esc(e.name)}</option>`).join('')}</select></div>` : ''}
      <div class="form__grid form__grid--3">
        ${closing ? '' : `<div class="form__row"><label class="lbl" for="ts-day">${esc(T.formDay)} <span class="req">●</span></label>
          <input class="inp inp--mono" id="ts-day" type="date" required max="${esc(d.todayKey())}" value="${esc(day)}" /></div>
        <div class="form__row"><label class="lbl" for="ts-start">${esc(T.formStart)} <span class="req">●</span></label>
          <input class="inp inp--mono" id="ts-start" type="time" required value="${esc(shift?.startTime || '')}" /></div>`}
        <div class="form__row"><label class="lbl" for="ts-end">${esc(T.formEnd)} <span class="req">●</span></label>
          <input class="inp inp--mono" id="ts-end" type="time" required value="${esc(end)}" /></div>
      </div>
      ${closing ? '' : breaks.html}
      <p class="hint">${esc(T.timesHint)}</p>
      <div class="form__row form__row--full"><label class="lbl" for="ts-reason">${esc(T.formReason)} <span class="req">●</span></label>
        <textarea class="txt" id="ts-reason" maxlength="500" required placeholder="${esc(T.reasonPh)}"></textarea></div>
      ${mode === 'correct' ? `<p class="hint">${esc(T.correctHint)}</p>` : ''}
      <div class="actions">
        <button class="btn btn--primary" type="submit">${esc(d.STR.clientSaveChanges)}</button>
        <button class="btn btn--ghost" type="button" data-form-cancel>${esc(d.STR.cancel)}</button>
      </div>`;
    if (!closing) breaks.wire(form);
    $('[data-form-cancel]', form).addEventListener('click', () => d.closeDrawer());
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const reason = $('#ts-reason', form).value.trim();
      if (!reason) { d.toast(tr('errors.reason_required')); return; }
      const value = id => $(`#${id}`, form)?.value || '';
      const btn = $('button[type=submit]', form);
      btn.disabled = true;
      try {
        if (closing) {
          await d.api(`/app/api/timesheets/shifts/${encodeURIComponent(shift.id)}/close`, { method: 'POST', body: JSON.stringify({ end: value('ts-end'), reason }) });
        } else {
          const payload = { day: value('ts-day'), start: value('ts-start'), end: value('ts-end'), breaks: breaks.collect(form), reason };
          if (adding) {
            if (!value('ts-employee')) { btn.disabled = false; d.toast(tr('errors.employee_not_found')); return; }
            await d.api('/app/api/timesheets/shifts', { method: 'POST', body: JSON.stringify({ ...payload, employeeId: value('ts-employee') }) });
          } else {
            await d.api(`/app/api/timesheets/shifts/${encodeURIComponent(shift.id)}`, { method: 'PATCH', body: JSON.stringify(payload) });
          }
        }
        d.toast(T.saved);
        d.closeDrawer();
        afterChange();
      } catch (err) {
        btn.disabled = false;
        if (err.message !== 'unauthorized') d.toast(errorText(err));
      }
    });
    d.openDrawer(adding ? T.addTitle : closing ? T.closeTitle : T.correctTitle, form, false, { eyebrow: T.detailEyebrow });
  }

  /** Coordinates as people type them: a dot or a decimal comma, nothing else. */
  function coordinate(value) {
    const text = String(value || '').trim();
    if (!/^-?\d{1,3}([.,]\d+)?$/.test(text)) return NaN;
    return Number(text.replace(',', '.'));
  }

  function openSiteForm(site) {
    const editing = site?.id ? site : null;
    const clients = team.clients || null;
    const coords = v => (v == null || Number.isNaN(v) ? '' : String(v));
    const form = document.createElement('form');
    form.className = 'form';
    form.innerHTML = `
      <div class="form__row form__row--full"><label class="lbl" for="sf-name">${esc(T.siteName)} <span class="req">●</span></label>
        <input class="inp" id="sf-name" maxlength="120" required placeholder="${esc(T.siteNamePh)}" value="${esc(editing?.name || '')}" /></div>
      <div class="form__row form__row--full"><label class="lbl" for="sf-address">${esc(T.siteAddress)} <span class="opt">${esc(d.STR.optional)}</span></label>
        <input class="inp" id="sf-address" maxlength="300" autocomplete="off" value="${esc(editing?.address || '')}" /></div>
      <div class="form__grid">
        <div class="form__row"><label class="lbl" for="sf-lat">${esc(T.siteLat)} <span class="req">●</span></label>
          <input class="inp inp--mono" id="sf-lat" inputmode="decimal" autocomplete="off" required value="${esc(coords(editing?.latitude))}" /></div>
        <div class="form__row"><label class="lbl" for="sf-lng">${esc(T.siteLng)} <span class="req">●</span></label>
          <input class="inp inp--mono" id="sf-lng" inputmode="decimal" autocomplete="off" required value="${esc(coords(editing?.longitude))}" /></div>
      </div>
      <div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-sf-here>${esc(T.useMyLocation)}</button></div>
      <p class="hint">${esc(T.coordsHint)}</p>
      <div class="form__grid">
        <div class="form__row"><label class="lbl" for="sf-radius">${esc(T.siteRadius)}</label>
          <input class="inp inp--mono" id="sf-radius" type="number" min="25" max="2000" step="5" required value="${esc(editing?.radiusM || 150)}" />
          <p class="hint">${esc(T.siteRadiusHint)}</p></div>
        ${clients ? `<div class="form__row"><label class="lbl" for="sf-client">${esc(T.siteClient)}</label>
          <select class="sel" id="sf-client"><option value="">${esc(T.siteNoClient)}</option>${clients.map(c => `<option value="${esc(c.id)}"${editing?.clientId === c.id ? ' selected' : ''}>${esc(c.name)}</option>`).join('')}</select></div>` : ''}
      </div>
      ${editing ? `<div class="form__row form__row--full"><label class="form__check"><input type="checkbox" id="sf-active"${editing.active ? ' checked' : ''} /> ${esc(T.siteActive)}</label></div>` : ''}
      <div class="actions">
        <button class="btn btn--primary" type="submit">${esc(editing ? d.STR.clientSaveChanges : T.siteSave)}</button>
        <button class="btn btn--ghost" type="button" data-form-cancel>${esc(d.STR.cancel)}</button>
        ${editing ? `<button class="btn btn--ghost" type="button" data-sf-delete>${esc(T.siteDelete)}</button>` : ''}
      </div>`;
    const lat = $('#sf-lat', form);
    const lng = $('#sf-lng', form);
    // "38.72230, -9.13930" pasted from Google Maps fills both fields.
    lat.addEventListener('input', () => {
      const pair = lat.value.match(/^\s*(-?\d{1,3}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)\s*$/);
      if (pair) { lat.value = pair[1]; lng.value = pair[2]; }
    });
    $('[data-sf-here]', form).addEventListener('click', async e => {
      const btn = e.currentTarget;
      btn.disabled = true;
      try {
        const here = await currentPosition();
        lat.value = here.latitude.toFixed(5);
        lng.value = here.longitude.toFixed(5);
        d.toast(T.locationFilled);
      } catch (code) {
        d.toast(known(`geoErrors.${code}`, T.saveFailed));
      } finally {
        btn.disabled = false;
      }
    });
    $('[data-form-cancel]', form).addEventListener('click', () => d.closeDrawer());
    $('[data-sf-delete]', form)?.addEventListener('click', async () => {
      if (!await d.confirmDialog({ title: T.siteDeleteTitle, body: T.siteDeleteBody, okLabel: T.siteDelete })) return;
      try {
        await d.api(`/app/api/timesheets/sites/${encodeURIComponent(editing.id)}`, { method: 'DELETE' });
        d.toast(T.siteDeleted);
        d.closeDrawer();
        afterChange();
      } catch (err) { if (err.message !== 'unauthorized') d.toast(errorText(err)); }
    });
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const latitude = coordinate(lat.value);
      const longitude = coordinate(lng.value);
      if (Number.isNaN(latitude) || Number.isNaN(longitude)) { d.toast(tr('errors.invalid_location')); return; }
      const payload = {
        name: $('#sf-name', form).value.trim(),
        address: $('#sf-address', form).value.trim(),
        latitude,
        longitude,
        radiusM: Number($('#sf-radius', form).value),
        ...(clients ? { clientId: $('#sf-client', form).value } : {}),
        ...(editing ? { active: $('#sf-active', form).checked } : {}),
      };
      const btn = $('button[type=submit]', form);
      btn.disabled = true;
      try {
        await d.api(editing ? `/app/api/timesheets/sites/${encodeURIComponent(editing.id)}` : '/app/api/timesheets/sites', {
          method: editing ? 'PATCH' : 'POST',
          body: JSON.stringify(payload),
        });
        d.toast(T.siteSaved);
        d.closeDrawer();
        afterChange();
      } catch (err) {
        btn.disabled = false;
        if (err.message !== 'unauthorized') d.toast(errorText(err));
      }
    });
    d.openDrawer(editing ? T.siteEdit : T.siteNew, form);
  }

  // ── An employee's record (the team's side) ─────────────────────────────────────────────────────

  async function loadRecord(employeeId) {
    if (!d.hasModule(TEAM)) return null;
    const id = encodeURIComponent(employeeId);
    const [week, devices] = await Promise.all([
      d.api(`/app/api/timesheets?employeeId=${id}`).catch(() => null),
      d.api(`/app/api/timesheets/devices?employeeId=${id}`).catch(() => []),
    ]);
    return { week, devices: devices || [], offset: Date.now() };
  }

  function recordHtml(employee, info) {
    if (!info) return '';
    const now = Date.now();
    const shifts = [...(info.week?.shifts || [])].sort((a, b) => Date.parse(b.startAt) - Date.parse(a.startAt));
    const total = shifts.reduce((sum, s) => sum + workedNow(s, now), 0);
    const rows = shifts.map(s => `<tr class="conversation-row${s.status === 'OPEN' ? ' is-current' : s.review === 'APPROVED' ? ' is-paid' : ''}" data-shift="${esc(s.id)}" tabindex="0">
      <td class="mono">${esc(dayText(s.day))}</td><td class="mono">${esc(timeRange(s))}</td>
      <td class="num">${esc(hm(workedNow(s, now)))}</td><td>${reviewPill(s)}</td></tr>`).join('');
    const hours = shifts.length
      ? d.recordTable([[T.thDay], [T.thTime], [T.thWorked, 'right'], [T.thStatus]], rows, { title: `${T.recordHours} · ${T.recordWeek} · ${hm(total)}`, count: shifts.length })
      : d.recordEmpty(T.recordNoShifts, null, '');
    const device = info.devices.find(x => x.active);
    const admin = d.isAdmin();
    const detail = device
      ? tr(device.lastUsedAt ? 'deviceDetail' : 'deviceNeverUsed', { since: d.relTime(device.createdAt), used: device.lastUsedAt ? d.relTime(device.lastUsedAt) : '' })
      : tr('deviceNoneDetail', { name: employee.name });
    const phone = `<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(T.devicePanel)}</h2>
      ${device && admin ? `<div class="panel__tools"><button class="btn btn--sm btn--ghost" type="button" data-device-revoke="${esc(device.id)}">${esc(T.deviceRevoke)}</button></div>` : ''}</header>
      <ul class="worklist"><li><button class="worklist__item" type="button" data-tone="${device ? 'ok' : 'neutral'}" disabled>
        <span class="worklist__dot" aria-hidden="true"></span>
        <span class="worklist__main"><span class="worklist__title">${esc(device ? device.name : T.deviceNone)}</span><span class="worklist__detail">${esc(detail)}</span></span>
      </button></li></ul></section>
      ${device && !admin ? `<p class="hint">${esc(T.deviceAdminOnly)}</p>` : ''}`;
    return hours + phone;
  }

  /** [back] is the record's trail entry; [refresh] reloads the record after a change. */
  function wireRecord(body, employee, back, refresh) {
    $$('[data-shift]', body).forEach(row => {
      const open = () => d.openFrom(back, () => openShift(row.dataset.shift));
      row.addEventListener('click', open);
      row.addEventListener('keydown', e => { if (e.key === 'Enter') open(); });
    });
    $('[data-device-revoke]', body)?.addEventListener('click', async e => {
      const id = e.currentTarget.dataset.deviceRevoke;
      if (!await d.confirmDialog({ title: T.deviceRevokeTitle, body: tr('deviceRevokeBody', { name: employee.name }), okLabel: T.deviceRevoke })) return;
      try {
        await d.api(`/app/api/timesheets/devices/${encodeURIComponent(id)}`, { method: 'DELETE' });
        d.toast(T.deviceRevoked);
        refresh();
      } catch (err) { if (err.message !== 'unauthorized') d.toast(errorText(err)); }
    });
  }

  /** Nav count: closed shifts waiting for the team this week, once the page has been opened. */
  function badge() {
    if (!team.data) return null;
    const count = team.data.shifts.filter(isPendingClosed).length;
    return count ? { count, alert: false } : null;
  }

  function newShift() {
    openTimesForm(null, 'add');
  }

  function init(deps) {
    d = deps;
  }

  window.TimesheetsUI = {
    init, loadMine, renderMine, loadTeam, renderTeam, openShift, newShift, badge, kpis, loadRecord, recordHtml, wireRecord,
  };
})();
