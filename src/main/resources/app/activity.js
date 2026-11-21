/* Activity (/app → Activity, the company's admins): who did what, read from the event log, by person, area and days;
   and the History a record's drawer shows to anyone who can open it. A row says what was done and to which record
   in two parts, so no language has to build a sentence out of pieces. */

const ACT = I18N.section('app.activity');
const actT = (key, params) => I18N.t(`app.activity.${key}`, params);
const actOr = (key, fallback, params) => {
  const value = actT(key, params);
  return value === `app.activity.${key}` ? fallback : value;
};

// Event types whose last word isn't the verb the row shows.
const ACTIVITY_VERB_OF = { 'email.sent': 'emailed', 'instagram.comment.received': 'received', 'email.received': 'received', 'message.received': 'received' };
// What the person filter offers besides the team, one entry per kind of actor.
const ACTIVITY_KINDS = ['CLIENT', 'SUPPLIER', 'AGENT', 'BOT', 'SYSTEM', 'OPERATOR'];
// The areas the log can be narrowed to, in the order of the nav; each shows when the company has it.
const ACTIVITY_AREAS = ['clients', 'services', 'quotes', 'invoices', 'suppliers', 'employees', 'payments', 'catalog', 'bookings', 'conversations', 'email', 'instagram',
  'projects', 'site-diary', 'site-issues', 'change-orders', 'measurements'];

const activity = { items: [], next: null, people: [], filters: { actor: '', area: '', from: '', to: '' }, loading: false, failed: false };

/** What was done, as the row's first column says it: "Approved", "Moved to “Accepted”", "Set Walls to 40%". */
function activityVerb(item) {
  const f = item.facts || {};
  const tail = item.type.split('.').pop();
  if (item.type === 'change_order.decided') return actT(`verbs.${f.to === 'REJECTED' ? 'rejected' : 'approved'}`);
  if (item.type === 'project.share_changed') return actOr(`verbs.share_${f.action}`, actT('verbs.other'));
  if (item.type === 'supplier.link_changed') return actOr(`verbs.link_${f.action}`, actT('verbs.other'));
  if (item.type === 'project.phases_changed' && f.phase) return actT('verbs.phase_progress', { phase: f.phase, pct: f.toPct ?? '' });
  if (tail === 'status_changed') return actT('verbs.status_changed', { status: recordStatusLabel(item.subjectType, f.to) || f.to || '' });
  return actOr(`verbs.${ACTIVITY_VERB_OF[item.type] || tail}`, actT('verbs.other'));
}

/** The record it was done to: "Invoice FT 2026/12", "Measurement no. 3", "Diary · 20 Nov". */
function activityRecord(item) {
  const noun = actOr(`nouns.${item.subjectType}`, actT('nouns.other'));
  const label = item.label || '';
  if (item.subjectType === 'measurement' && label) return I18N.t('app.building.measurementN', { n: label });
  if (item.subjectType === 'site_log' && label) return `${noun} · ${fmtDay(label)}`;
  if (item.subjectType === 'site_photo' && /^\d{4}-\d{2}-\d{2}$/.test(label)) return `${noun} · ${fmtDay(label)}`;
  if (item.subjectType === 'report' && label.includes(' – ')) return `${noun} · ${label.split(' – ').map(d => fmtDay(d)).join(' – ')}`;
  return label ? `${noun} ${label}` : noun;
}

/** Who did it: the main line (an email, the name typed on a link, the agent) and what kind of actor that is. */
function activityActor(actor) {
  const a = actor || {};
  switch (a.type) {
    case 'USER': return { name: a.name || '—', kind: a.via === 'assistant' ? ACT.viaAssistant : '' };
    case 'CLIENT':
    case 'SUPPLIER':
    case 'AGENT': return { name: a.name || actT(`actor.${a.type}`), kind: actT(`actor.${a.type}`) };
    case 'BOT': return { name: actT('actor.BOT'), kind: a.id ? channelName(a.id.toUpperCase()) : '' };
    case 'OPERATOR': return { name: actT('actor.OPERATOR'), kind: '' };
    default: return { name: actT('actor.SYSTEM'), kind: '' };
  }
}

/** Opens the record a row is about; nothing for one that was deleted. The project's drawer for its photos and updates. */
function openActivityRecord(item, back) {
  if (item.type.endsWith('.deleted')) return;
  const subject = { type: item.subjectType, id: item.subjectId };
  if (canOpenAgentSubject(subject)) return openAgentSubject(subject, back);
  if ((subject.type === 'supplier' || subject.type === 'employee') && hasModule(`${subject.type}s`)) {
    return openFrom(back, () => openPayeeDrawer(subject.type, subject.id));
  }
  if (item.projectId && hasModule('projects') && !linksBackTo(`project:${item.projectId}`)) return openFrom(back, () => openProjectDrawer(item.projectId));
}
const activityOpens = item => !item.type.endsWith('.deleted') &&
  (canOpenAgentSubject({ type: item.subjectType, id: item.subjectId }) ||
    ((item.subjectType === 'supplier' || item.subjectType === 'employee') && hasModule(`${item.subjectType}s`)) ||
    (!!item.projectId && hasModule('projects')));

function activityQuery(before = '') {
  const f = activity.filters;
  const q = new URLSearchParams();
  if (f.actor) q.set('actor', f.actor);
  if (f.area) q.set('area', f.area);
  if (f.from) q.set('from', f.from);
  if (f.to) q.set('to', f.to);
  if (before) q.set('before', before);
  return `/app/api/activity?${q}`;
}

async function loadActivity() {
  activity.loading = true;
  activity.failed = false;
  try {
    const [page, people] = await Promise.all([
      api(activityQuery()),
      activity.people.length ? Promise.resolve(activity.people) : api('/app/api/activity/people').catch(() => []),
    ]);
    activity.items = page.items;
    activity.next = page.next;
    activity.people = people;
  } catch {
    activity.items = [];
    activity.next = null;
    activity.failed = true;
  } finally {
    activity.loading = false;
  }
}

async function loadMoreActivity() {
  if (!activity.next || activity.loading) return;
  activity.loading = true;
  try {
    const page = await api(activityQuery(activity.next));
    activity.items = activity.items.concat(page.items);
    activity.next = page.next;
  } catch {
    toast(ACT.loadFailed);
  } finally {
    activity.loading = false;
  }
  render();
}

function activityRowHtml(item, i) {
  const who = activityActor(item.actor);
  const opens = activityOpens(item);
  return `<tr data-activity-row="${i}"${opens ? ' tabindex="0"' : ''}>
    <td class="nowrap"${cellLabel(ACT.thWhen)}><span title="${escapeHTML(fmtWhen(item.at))}">${escapeHTML(relTime(item.at))}</span><div class="sub">${escapeHTML(fmtWhen(item.at))}</div></td>
    <td class="long"${cellLabel(ACT.thWho)}><div class="tbl__title"><span>${escapeHTML(who.name)}</span></div>${who.kind ? `<div class="sub">${escapeHTML(who.kind)}</div>` : ''}</td>
    <td class="long"${cellLabel(ACT.thWhat)}>${escapeHTML(activityVerb(item))}</td>
    <td class="name long"${cellLabel(ACT.thRecord)}><div class="tbl__title"><span>${escapeHTML(activityRecord(item))}</span></div>${item.project && item.subjectType !== 'project' ? `<div class="sub">${escapeHTML(item.project)}</div>` : ''}</td>
  </tr>`;
}

function activityFiltersHtml() {
  const f = activity.filters;
  const people = activity.people.map(p => `<option value="user:${escapeHTML(p.id)}"${f.actor === `user:${p.id}` ? ' selected' : ''}>${escapeHTML(p.email)}</option>`).join('');
  const kinds = ACTIVITY_KINDS.map(k => `<option value="${k}"${f.actor === k ? ' selected' : ''}>${escapeHTML(actT(`kinds.${k}`))}</option>`).join('');
  const areas = ACTIVITY_AREAS.filter(m => hasModule(m)).map(m => `<option value="${m}"${f.area === m ? ' selected' : ''}>${escapeHTML(labels[m] || m)}</option>`).join('');
  const any = f.actor || f.area || f.from || f.to;
  return `<div class="panel__filters">
    <select class="sel" data-activity-filter="actor" aria-label="${escapeHTML(ACT.filterPerson)}"><option value="">${escapeHTML(ACT.filterEveryone)}</option>${people}${kinds}</select>
    <select class="sel" data-activity-filter="area" aria-label="${escapeHTML(ACT.filterArea)}"><option value="">${escapeHTML(ACT.filterAllAreas)}</option>${areas}</select>
    <input class="inp" type="date" value="${escapeHTML(f.from)}" data-activity-filter="from" aria-label="${escapeHTML(ACT.filterFrom)}" title="${escapeHTML(ACT.filterFrom)}" />
    <input class="inp" type="date" value="${escapeHTML(f.to)}" data-activity-filter="to" aria-label="${escapeHTML(ACT.filterTo)}" title="${escapeHTML(ACT.filterTo)}" />
    ${any ? `<button class="btn btn--sm btn--ghost" type="button" data-activity-clear>${escapeHTML(ACT.clear)}</button>` : ''}</div>`;
}

function renderActivity(root) {
  const filtered = Object.values(activity.filters).some(Boolean);
  const body = activity.failed
    ? bldEmptyHtml(ACT.loadFailed)
    : activity.items.length
      ? `<div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr><th>${escapeHTML(ACT.thWhen)}</th><th>${escapeHTML(ACT.thWho)}</th><th>${escapeHTML(ACT.thWhat)}</th><th>${escapeHTML(ACT.thRecord)}</th></tr></thead>
          <tbody>${activity.items.map(activityRowHtml).join('')}</tbody></table></div>
          ${activity.next ? `<div class="panel__foot"><button class="btn btn--sm" type="button" data-activity-more>${escapeHTML(ACT.more)}</button></div>` : ''}`
      : bldEmptyHtml(filtered ? ACT.emptyFilter : ACT.empty, filtered ? '' : ACT.emptyDesc);
  root.innerHTML = hero(labels.activity, ACT.desc) + `<section class="panel">
    <header class="panel__head"><h2 class="panel__title">${escapeHTML(labels.activity)}${activity.items.length ? ` <span class="tag">${activity.items.length}${activity.next ? '+' : ''}</span>` : ''}</h2></header>
    ${activityFiltersHtml()}${body}</section>`;
  $$('[data-activity-filter]', root).forEach(el => el.addEventListener('change', async () => {
    activity.filters[el.dataset.activityFilter] = el.value;
    await loadActivity();
    render();
  }));
  $('[data-activity-clear]', root)?.addEventListener('click', async () => {
    activity.filters = { actor: '', area: '', from: '', to: '' };
    await loadActivity();
    render();
  });
  $('[data-activity-more]', root)?.addEventListener('click', loadMoreActivity);
  $$('[data-activity-row]', root).forEach(row => {
    const item = activity.items[Number(row.dataset.activityRow)];
    if (!item || !activityOpens(item)) return;
    row.addEventListener('click', () => openActivityRecord(item, null));
    row.addEventListener('keydown', e => { if (e.key === 'Enter') openActivityRecord(item, null); });
  });
}

// ── A record's history, in its drawer ───────────────────────────────────────────────────────────

/**
 * A History panel for the record [type]/[id], filled by [hydrateRecordHistory]: what was done to it and to the records
 * it holds (a project's changes and measurements, a client's invoices), by whom and when. A row about another record
 * opens it; [back] is the trail entry that comes back here.
 */
function recordHistoryHtml(type, id) {
  return `<section class="panel" data-history="${escapeHTML(type)}:${escapeHTML(id)}">
    <header class="panel__head"><h2 class="panel__title">${escapeHTML(ACT.historyTitle)}</h2></header>
    <div class="panel__body panel__body--flush"><p class="hint">${escapeHTML(ACT.loading)}</p></div></section>`;
}

/** Under a quote, invoice, payment or booking, above the drawer's pinned foot: its History, filled once drawn. */
function mountRecordHistory(parent, type, id, back) {
  const el = document.createElement('div');
  el.innerHTML = recordHistoryHtml(type, id);
  parent.insertBefore(el, [...parent.children].find(c => c.classList.contains('drawer__foot')) || null);
  hydrateRecordHistory(el, back);
}

/** Fills every History panel in [root]. */
async function hydrateRecordHistory(root, back = null) {
  await Promise.all($$('[data-history]', root).map(async panel => {
    const [type, id] = panel.dataset.history.split(':');
    const body = $('.panel__body', panel);
    let items;
    try {
      items = await api(`/app/api/activity/records/${encodeURIComponent(type)}/${encodeURIComponent(id)}`);
    } catch {
      body.innerHTML = `<p class="hint">${escapeHTML(ACT.loadFailed)}</p>`;
      return;
    }
    if (!items.length) {
      body.innerHTML = `<p class="hint">${escapeHTML(ACT.historyEmpty)}</p>`;
      return;
    }
    body.innerHTML = `<ul class="worklist worklist--wrap">${items.map((item, i) => {
      const who = activityActor(item.actor);
      const self = item.subjectType === type && item.subjectId === id;
      const opens = !self && activityOpens(item);
      const what = self ? activityVerb(item) : `${activityVerb(item)} · ${activityRecord(item)}`;
      return `<li><button class="worklist__item" type="button" data-history-row="${i}" data-tone="${self ? 'neutral' : 'info'}"${opens ? '' : ' disabled'}>
        <span class="worklist__dot" aria-hidden="true"></span>
        <span class="worklist__main"><span class="worklist__title">${escapeHTML(what)}</span><span class="worklist__detail">${escapeHTML([who.name, who.kind].filter(Boolean).join(' · '))}</span></span>
        <span class="worklist__side"><time class="worklist__when" datetime="${escapeHTML(item.at)}" title="${escapeHTML(fmtWhen(item.at))}">${escapeHTML(relTime(item.at))}</time></span>
      </button></li>`;
    }).join('')}</ul>${isCompanyAdmin() ? `<div class="panel__foot"><button class="btn btn--sm btn--ghost" type="button" data-history-all>${escapeHTML(ACT.historyAll)}</button></div>` : ''}`;
    $$('[data-history-row]', body).forEach(row => row.addEventListener('click', () => {
      const item = items[Number(row.dataset.historyRow)];
      if (item) openActivityRecord(item, back);
    }));
    $('[data-history-all]', body)?.addEventListener('click', () => {
      closeDrawer({ dismissed: true });
      setActive('activity');
    });
  }));
}
