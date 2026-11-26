/* Briefings (/app → Briefings): agents that write to each employee about their day on a schedule (the Agents
   gallery's Team templates), shown by day and person: what each one gets, what went and how WhatsApp delivered it.
   Send now, send again and approve act on the briefing's runs through /app/api/briefings; setting one up, editing
   it and opening a run happen in the Agents module's own drawers (window.AgentsUI), over this page. */

const BRF = I18N.section('app.briefings');
const BRIEFING_TONES = {
  SCHEDULED: 'info', READY: 'accent', SENDING: 'info', WAITING: 'info', AWAITING_APPROVAL: 'warn',
  SENT: 'info', DELIVERED: 'ok', READ: 'ok', FAILED: 'bad', FALLBACK: 'warn', NO_PHONE: 'warn',
};
// What still goes out today, what went, and what can be sent once more.
const BRIEFING_PENDING = new Set(['SCHEDULED', 'READY', 'SENDING', 'WAITING']);
const BRIEFING_SENT = new Set(['SENT', 'DELIVERED', 'READ']);
const BRIEFING_AGAIN = new Set(['SENT', 'DELIVERED', 'READ', 'FAILED', 'FALLBACK', 'SKIPPED', 'CANCELLED', 'NOTHING_TODAY']);
// Once more today: after a run (a day that had nothing may have something now), never for someone not run yet.
const briefingCanSendAgain = p => BRIEFING_AGAIN.has(p.state) && (p.state !== 'NOTHING_TODAY' || !!p.runId);
// Characters of a message the list shows; its drawer shows all of it.
const BRIEFING_EXCERPT = 140;

const briefingText = (key, params) => I18N.t(`app.briefings.${key}`, params);
const briefingStateLabel = s => briefingText(`states.${s}`);
const briefingPill = s => `<span class="pill${BRIEFING_TONES[s] ? ` pill--${BRIEFING_TONES[s]}` : ''}">${escapeHTML(briefingStateLabel(s))}</span>`;

async function loadBriefings() {
  const data = await api('/app/api/briefings');
  state.briefings = data;
  const list = data.briefings || [];
  if (!list.some(b => b.id === state.briefingId)) state.briefingId = list[0]?.id || '';
  if (!state.briefingDayKey || state.briefingDayKey > data.today) state.briefingDayKey = data.today;
  state.briefingDay = state.briefingId
    ? await api(`/app/api/briefings/${encodeURIComponent(state.briefingId)}?day=${encodeURIComponent(state.briefingDayKey)}`)
    : null;
}

// After an action, or a change made in Agents' drawers: fresh data, and the page redrawn under the drawer still open.
async function reloadBriefings() {
  if (state.active !== 'briefings') return;
  try { await loadBriefings(); } catch { toast(STR.loadFailed); }
  if (state.active === 'briefings') render();
}

function renderBriefings(root) {
  const data = state.briefings || { briefings: [], canManage: false };
  const list = data.briefings || [];
  const day = state.briefingDay;
  if (!list.length || !day) {
    root.innerHTML = hero(labels.briefings, BRF.desc) + `<div class="panel"><div class="empty">
      <p class="empty__title">${escapeHTML(BRF.emptyTitle)}</p><p class="empty__desc">${escapeHTML(BRF.emptyDesc)}</p>
      ${data.canManage ? `<div class="empty__actions"><button class="btn btn--sm btn--primary" type="button" data-briefing-new>${escapeHTML(BRF.new)}</button></div>` : ''}
    </div></div>`;
    $('[data-briefing-new]', root)?.addEventListener('click', openBriefingChooser);
    return;
  }
  // The server leaves out a field at its default (no problems, no warnings, zero sends): read them with a fallback.
  const b = { ...day.briefing, problems: day.briefing.problems || [], groups: day.briefing.groups || [] };
  const people = day.people || [];
  const count = test => people.filter(p => test(p.state)).length;
  const stats = [
    { label: BRF.statSent, value: count(s => BRIEFING_SENT.has(s)) },
    { label: BRF.statRead, value: count(s => s === 'READ') },
    { label: BRF.statPending, value: count(s => BRIEFING_PENDING.has(s)) },
    { label: BRF.statApproval, value: count(s => s === 'AWAITING_APPROVAL') },
    { label: BRF.statFailed, value: count(s => s === 'FAILED') },
  ];
  const isToday = day.day === day.today;
  const manage = data.canManage;
  const active = b.status === 'ACTIVE';
  const waiting = count(s => s === 'AWAITING_APPROVAL');
  const toSend = count(s => s === 'READY' || s === 'SCHEDULED');
  const actions = [
    manage && active && isToday && toSend ? `<button class="btn btn--sm btn--primary" type="button" data-briefing-act="send">${escapeHTML(BRF.sendNow)}</button>` : '',
    manage && !active ? `<button class="btn btn--sm btn--accent" type="button" data-briefing-act="on" ${b.problems.length ? 'disabled' : ''}>${escapeHTML(BRF.turnOn)}</button>` : '',
    manage && active ? `<button class="btn btn--sm btn--ghost" type="button" data-briefing-act="pause">${escapeHTML(BRF.pause)}</button>` : '',
    `<button class="btn btn--sm btn--ghost" type="button" data-briefing-act="edit">${escapeHTML(manage ? BRF.edit : BRF.open)}</button>`,
  ].join('');
  const chooser = list.length > 1
    ? `<div class="panel__filters">${list.map(x => `<button class="chip ${x.id === b.id ? 'is-on' : ''}" type="button" data-briefing="${escapeHTML(x.id)}">${escapeHTML(x.name)}</button>`).join('')}</div>`
    : '';
  const meta = [briefingScheduleText(b), b.template ? briefingText('templateIs', { name: `${b.template} · ${b.templateLanguage || ''}` }) : '', briefingGroupsText(b)].filter(Boolean).join(' · ');
  const rows = people.map(p => briefingRowHtml(p, { manage, active, isToday })).join('');
  const empty = `<tr><td colspan="5"><div class="empty"><p class="empty__title">${escapeHTML(BRF.noPeople)}</p><p class="empty__desc">${escapeHTML(BRF.noPeopleDesc)}</p></div></td></tr>`;
  root.innerHTML = hero(labels.briefings, BRF.desc, statCards(stats), briefingDayNav(day))
    + briefingNoticesHtml(b, { manage, waiting, isToday })
    + `<section class="panel">
      <header class="panel__head">
        <div><h2 class="panel__title">${escapeHTML(b.name)} ${briefingAgentPill(b.status)}</h2>${meta ? `<div class="hint">${escapeHTML(meta)}</div>` : ''}</div>
        <div class="panel__tools">${actions}</div>
      </header>
      ${chooser}
      <div class="tbl-wrap"><table class="tbl tbl--stack">
        <thead><tr><th>${escapeHTML(BRF.thPerson)}</th><th>${escapeHTML(BRF.thStatus)}</th><th>${escapeHTML(BRF.thMessage)}</th><th>${escapeHTML(BRF.thWhen)}</th><th class="right"></th></tr></thead>
        <tbody>${rows || empty}</tbody>
      </table></div>
    </section>`;
  pageCount(people.length);
  wireBriefings(root, b, day);
}

function briefingRowHtml(p, { manage, active, isToday }) {
  const detail = briefingDetailText(p);
  const message = p.message ? (p.message.length > BRIEFING_EXCERPT ? `${p.message.slice(0, BRIEFING_EXCERPT).trimEnd()}…` : p.message) : '';
  const when = p.state === 'SCHEDULED' && p.at ? briefingText('at', { time: fmtTime(p.at) }) : (p.at ? fmtWhen(p.at) : '');
  const buttons = [
    p.state === 'AWAITING_APPROVAL' && p.approvalId ? `<button class="btn btn--sm btn--accent" type="button" data-briefing-row="approve">${escapeHTML(BRF.approve)}</button>` : '',
    manage && active && isToday && (p.state === 'READY' || p.state === 'SCHEDULED') ? `<button class="btn btn--sm" type="button" data-briefing-row="send">${escapeHTML(BRF.sendNow)}</button>` : '',
    manage && active && isToday && briefingCanSendAgain(p) ? `<button class="btn btn--sm btn--ghost" type="button" data-briefing-row="again">${escapeHTML(BRF.sendAgain)}</button>` : '',
  ].join('');
  return `<tr data-briefing-person="${escapeHTML(p.employeeId)}">
    <td class="name long"><button class="tbl__open" type="button">${escapeHTML(p.name)}</button><div class="sub mono">${escapeHTML(p.phone || '—')}</div></td>
    <td data-label="${escapeHTML(BRF.thStatus)}">${briefingPill(p.state)}${detail ? `<div class="sub">${escapeHTML(detail)}</div>` : ''}</td>
    <td class="long${p.message ? '' : ' muted'}" data-label="${escapeHTML(BRF.thMessage)}">${escapeHTML(message || '—')}</td>
    <td class="nowrap" data-label="${escapeHTML(BRF.thWhen)}">${escapeHTML(when || '—')}</td>
    <td class="right">${buttons ? `<div class="actions">${buttons}</div>` : ''}</td>
  </tr>`;
}

function briefingNoticesHtml(b, { manage, waiting, isToday }) {
  const edit = manage ? `<div class="notice__actions"><button class="btn btn--sm" type="button" data-briefing-act="edit">${escapeHTML(BRF.edit)}</button></div>` : '';
  const notices = [];
  if (b.problems.length) {
    const items = b.problems.map(p => `<li>${escapeHTML(window.AgentsUI?.problemText(p) || p.code)}</li>`).join('');
    notices.push(`<div class="notice notice--warn" role="status"><div class="notice__text"><strong>${escapeHTML(BRF.problemsTitle)}</strong><ul class="notice__list">${items}</ul></div>${edit}</div>`);
  } else if (!b.template) {
    notices.push(`<div class="notice notice--warn" role="status"><div class="notice__text"><strong>${escapeHTML(BRF.noTemplateTitle)}</strong><span>${escapeHTML(BRF.noTemplateDesc)}</span></div>${edit}</div>`);
  }
  if (b.status !== 'ACTIVE') {
    const on = manage && !b.problems.length ? `<div class="notice__actions"><button class="btn btn--sm btn--accent" type="button" data-briefing-act="on">${escapeHTML(BRF.turnOn)}</button></div>` : '';
    notices.push(`<div class="notice notice--info" role="status"><div class="notice__text"><strong>${escapeHTML(BRF.offTitle)}</strong><span>${escapeHTML(BRF.offDesc)}</span></div>${on}</div>`);
  }
  if (waiting && isToday) {
    notices.push(`<div class="notice notice--warn" role="status"><div class="notice__text"><strong>${escapeHTML(briefingText('approvalTitle', { n: waiting }))}</strong><span>${escapeHTML(BRF.approvalDesc)}</span></div>
      <div class="notice__actions"><button class="btn btn--sm btn--accent" type="button" data-briefing-act="approve">${escapeHTML(BRF.approveAll)}</button></div></div>`);
  }
  return notices.join('');
}

function briefingDayNav(day) {
  const atToday = day.day === day.today;
  return `<div class="period-nav">
    <button class="btn btn--sm" type="button" aria-label="${escapeHTML(BRF.dayPrev)}" data-briefing-day="prev">‹</button>
    <span class="period-nav__label mono">${escapeHTML(fmtDayKey(day.day, { weekday: 'short', day: 'numeric', month: 'short' }))}</span>
    <button class="btn btn--sm" type="button" aria-label="${escapeHTML(BRF.dayNext)}" ${atToday ? 'disabled' : ''} data-briefing-day="next">›</button>
    ${atToday ? '' : `<button class="btn btn--sm" type="button" data-briefing-day="today">${escapeHTML(BRF.dayToday)}</button>`}
  </div>`;
}

const briefingAgentPill = status => `<span class="pill${status === 'ACTIVE' ? ' pill--ok' : status === 'PAUSED' ? ' pill--warn' : ''}">${escapeHTML(I18N.t(`app.agents.status.${status}`))}</span>`;

function briefingScheduleText(b) {
  const s = b.schedule;
  if (!s) return '';
  const time = s.time || '09:00';
  if (s.frequency !== 'daily') return BRF.otherSchedule;
  const days = s.weekdays || [];
  if (!days.length || days.length === 7) return briefingText('everyDay', { time });
  if (days.join(',') === '1,2,3,4,5') return briefingText('weekdaysAt', { time });
  // 1 January 2024 was a Monday, so day n of the ISO week is 2024-01-0n.
  const names = days.map(n => new Intl.DateTimeFormat(uiLocale(), { weekday: 'short', timeZone: 'UTC' }).format(new Date(Date.UTC(2024, 0, n))));
  return briefingText('daysAt', { days: new Intl.ListFormat(uiLocale(), { style: 'short', type: 'conjunction' }).format(names), time });
}

function briefingGroupsText(b) {
  if (!b.groups?.length) return '';
  const names = b.groups.map(g => briefingText(`groups.${g}`));
  return briefingText('reads', { groups: new Intl.ListFormat(uiLocale(), { style: 'long', type: 'conjunction' }).format(names) });
}

// Why a row is as it is: a reason the agents engine names (translated through Agents' words), or WhatsApp's own text.
function briefingDetailText(p) {
  if (p.state === 'OPTED_OUT' || p.state === 'NO_PHONE' || p.state === 'NOTHING_TODAY' || p.state === 'NOT_INCLUDED') return briefingText(`hints.${p.state}`);
  if (!p.detail) return '';
  const code = /^[a-z0-9_]+(:[a-z0-9_.]*)?$/i.test(p.detail) || /^[A-Za-z0-9_-]+: [a-z0-9_:.]+$/.test(p.detail);
  return code && window.AgentsUI ? window.AgentsUI.reasonText(p.detail) : p.detail;
}

function wireBriefings(root, b, day) {
  $$('[data-briefing]', root).forEach(btn => btn.addEventListener('click', () => switchBriefing(btn.dataset.briefing)));
  $$('[data-briefing-day]', root).forEach(btn => btn.addEventListener('click', () => {
    const go = btn.dataset.briefingDay;
    const key = go === 'today' ? day.today : addDayKey(day.day, go === 'prev' ? -1 : 1);
    switchBriefingDay(key > day.today ? day.today : key);
  }));
  $$('[data-briefing-act]', root).forEach(btn => btn.addEventListener('click', () => briefingAction(btn.dataset.briefingAct, b, day, btn)));
  $$('[data-briefing-person]', root).forEach(tr => tr.addEventListener('click', e => {
    const person = (day.people || []).find(p => p.employeeId === tr.dataset.briefingPerson);
    if (!person) return;
    const act = e.target.closest('[data-briefing-row]');
    if (act) return briefingPersonAction(act.dataset.briefingRow, b, person, act);
    if (e.target.closest('button, a')?.matches(':not(.tbl__open)')) return;
    openBriefingPerson(b, day, person);
  }));
}

async function switchBriefing(id) {
  state.briefingId = id;
  await reloadBriefings();
}

async function switchBriefingDay(key) {
  state.briefingDayKey = key;
  await reloadBriefings();
}

async function briefingAction(act, b, day, btn) {
  if (act === 'edit') return window.AgentsUI?.openAgent(b.id);
  if (act === 'send') {
    const n = (day.people || []).filter(p => p.state === 'READY' || p.state === 'SCHEDULED').length;
    const ok = await confirmDialog({ title: BRF.sendTitle, body: briefingText('sendBody', { n }), okLabel: BRF.sendNow, danger: false });
    if (!ok) return;
    return briefingCall(btn, `/app/api/briefings/${encodeURIComponent(b.id)}/send`, {}, res => briefingSentText(res, b));
  }
  if (act === 'approve') {
    return briefingCall(btn, `/app/api/briefings/${encodeURIComponent(b.id)}/approve`, {}, res => (res.approved ? briefingText('approvedToast', { n: res.approved }) : BRF.approvedNone));
  }
  if (act === 'on') return briefingCall(btn, `/app/api/agents/${encodeURIComponent(b.id)}/activate`, null, () => BRF.turnedOn);
  if (act === 'pause') return briefingCall(btn, `/app/api/agents/${encodeURIComponent(b.id)}/pause`, null, () => BRF.paused);
}

async function briefingPersonAction(act, b, person, btn) {
  const base = `/app/api/briefings/${encodeURIComponent(b.id)}`;
  const one = { employeeIds: [person.employeeId] };
  if (act === 'approve') return briefingCall(btn, `${base}/approve`, one, res => (res.approved ? briefingText('approvedToast', { n: res.approved }) : BRF.approvedNone));
  if (act === 'again') {
    const ok = await confirmDialog({ title: BRF.againTitle, body: briefingText('againBody', { name: person.name }), okLabel: BRF.sendAgain, danger: false });
    if (!ok) return;
    return briefingCall(btn, `${base}/send`, { ...one, again: true }, res => briefingSentText(res, b));
  }
  if (act === 'send') return briefingCall(btn, `${base}/send`, one, res => briefingSentText(res, b));
}

// One action on the briefing: the button waits, a toast says what came of it, and the page and an open drawer refresh.
async function briefingCall(btn, path, body, message) {
  if (btn) btn.disabled = true;
  try {
    const res = await api(path, { method: 'POST', ...(body ? { body: JSON.stringify(body) } : {}) });
    toast(message(res));
    if (!$('#drawer').hidden && $('#drawer-body [data-briefing-drawer]')) closeDrawer({ dismissed: true });
  } catch (err) {
    if (btn) btn.disabled = false;
    toast(briefingErrorText(err));
  }
  await reloadBriefings();
}

// What came of a send: messages on their way, or, for a briefing that asks first, approvals waiting.
function briefingSentText(res, b) {
  if (res.started) return briefingText(b.asksFirst ? 'approvalTitle' : 'sentToast', { n: res.started });
  const reason = res.skipped?.[0]?.reason;
  return reason ? briefingSkipText(reason) : BRF.nobodyLeft;
}

const briefingSkipText = reason => I18N.t(`app.briefings.skipped.${reason}`, null) !== `app.briefings.skipped.${reason}`
  ? briefingText(`skipped.${reason}`)
  : (window.AgentsUI?.reasonText(reason) || reason);

function briefingErrorText(err) {
  const code = err?.code || '';
  if (code === 'inactive') return BRF.errInactive;
  if (code === 'not_allowed') return BRF.errNotAllowed;
  if (code === 'agent_limit') return BRF.errLimit;
  if (code === 'invalid_definition' || err?.status === 422) return BRF.errProblems;
  return BRF.errFailed;
}

// One person's day: the message in a WhatsApp bubble, what happened to it, and what can be done next.
function openBriefingPerson(b, day, person) {
  const body = document.createElement('div');
  body.className = 'record';
  body.dataset.briefingDrawer = '';
  const detail = briefingDetailText(person);
  const isToday = day.day === day.today;
  const manage = state.briefings?.canManage;
  const active = b.status === 'ACTIVE';
  const fields = recordFieldsHtml([
    [BRF.thStatus, `${briefingPill(person.state)}${detail ? ` <span class="muted">${escapeHTML(detail)}</span>` : ''}`],
    [BRF.thPhone, `<span class="mono">${escapeHTML(person.phone || '—')}</span>`],
    person.role ? [BRF.thRole, escapeHTML(person.role)] : null,
    person.at ? [BRF.thWhen, escapeHTML(fmtDate(person.at))] : null,
    person.sends > 1 ? [BRF.thSends, escapeHTML(String(person.sends))] : null,
  ]);
  const preview = person.state === 'SCHEDULED' || person.state === 'READY';
  const message = person.message
    ? `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(BRF.messageTitle)}</h2></header>
        <div class="panel__body"><div class="wa-preview"><div class="bubble bubble--out bubble--automation"><div class="bubble__text">${escapeHTML(person.message)}</div></div></div>
        ${preview ? `<p class="hint">${escapeHTML(BRF.previewHint)}</p>` : ''}</div></section>`
    : '';
  const warnings = person.warnings?.length
    ? `<div class="notice notice--warn"><div class="notice__text"><ul class="notice__list">${person.warnings.map(w => `<li>${escapeHTML(I18N.t(`app.agents.warnings.${w}`))}</li>`).join('')}</ul></div></div>`
    : '';
  const buttons = [
    person.runId && window.AgentsUI ? `<button class="btn btn--ghost" type="button" data-briefing-run>${escapeHTML(BRF.openRun)}</button>` : '',
    manage && active && isToday && briefingCanSendAgain(person) ? `<button class="btn" type="button" data-briefing-row="again">${escapeHTML(BRF.sendAgain)}</button>` : '',
    manage && active && isToday && (person.state === 'READY' || person.state === 'SCHEDULED') ? `<button class="btn btn--primary" type="button" data-briefing-row="send">${escapeHTML(BRF.sendNow)}</button>` : '',
    person.state === 'AWAITING_APPROVAL' && person.approvalId ? `<button class="btn btn--accent" type="button" data-briefing-row="approve">${escapeHTML(BRF.approve)}</button>` : '',
  ].join('');
  body.innerHTML = `${fields}${warnings}${message}${docFootHtml(buttons)}`;
  $('[data-briefing-run]', body)?.addEventListener('click', () => {
    const back = { key: `briefing:${b.id}:${person.employeeId}`, label: person.name, open: () => openBriefingPerson(b, day, person) };
    openFrom(back, () => window.AgentsUI.openRun(person.runId));
  });
  $$('[data-briefing-row]', body).forEach(btn => btn.addEventListener('click', () => briefingPersonAction(btn.dataset.briefingRow, b, person, btn)));
  openDrawer(person.name, body, false, { page: true, eyebrow: [b.name, fmtDayKey(day.day, { day: 'numeric', month: 'short' })].join(' · ') });
}

// "New briefing": the Agents gallery's Team templates; one opens Agents' setup drawer, which makes a draft to finish there.
async function openBriefingChooser() {
  const body = document.createElement('div');
  body.className = 'record';
  body.innerHTML = `<p class="hint">${escapeHTML(BRF.loading)}</p>`;
  const gen = openDrawer(BRF.chooseTitle, body, false, { eyebrow: labels.briefings });
  let templates;
  try {
    templates = await window.AgentsUI.templatesIn('team');
  } catch {
    closeDrawer({ dismissed: true });
    return toast(STR.loadFailed);
  }
  if (gen !== undefined && gen !== drawerGen) return;
  const cards = templates.map(t => `<button class="gallery__card${t.available ? '' : ' is-unavailable'}" type="button" data-briefing-template="${escapeHTML(t.key)}">
      <span class="gallery__head">${window.AgentsUI.agentIcon(t.icon)}<span class="gallery__title">${escapeHTML(t.name)}</span></span>
      <span class="gallery__desc">${escapeHTML(t.desc)}</span>
      ${t.available ? '' : `<span class="gallery__meta"><span class="pill">${escapeHTML(t.reason)}</span></span>`}
    </button>`).join('');
  body.innerHTML = `<p class="view__desc">${escapeHTML(BRF.chooseDesc)}</p><div class="gallery">${cards}</div>`;
  $$('[data-briefing-template]', body).forEach(btn => btn.addEventListener('click', () => window.AgentsUI.openTemplate(btn.dataset.briefingTemplate)));
}
