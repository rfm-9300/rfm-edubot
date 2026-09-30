/* Agents module (/app → Agents): the agents list and record, the template gallery with guided setup,
   the builder, the approvals and tasks inbox, run activity and company settings. app.js mounts it and
   passes its shared helpers to init(). Forms are drawn from the catalog's JSON schemas. */
(function () {
  let d = null;
  const A = I18N.section('app.agents');
  const ui = { tab: 'agents', listFilter: '', category: '', inbox: 'approvals', activity: '', showTests: false };
  const data = {
    overview: null, agents: [], catalog: null, approvals: [], tasks: [], runs: [], settings: null,
    people: null, waTemplates: null, bookable: null,
  };

  const TABS = ['agents', 'templates', 'inbox', 'activity', 'settings'];
  const RUN_GROUPS = { open: ['QUEUED', 'RUNNING', 'WAITING', 'AWAITING_APPROVAL'], attention: ['FAILED', 'NEEDS_REVIEW'], done: ['SUCCEEDED', 'CANCELLED', 'SKIPPED'] };
  const STATUS_EVENTS = new Set(['quote.created', 'quote.status_changed', 'booking.created', 'booking.status_changed']);
  const CHANNEL_EVENTS = new Set(['message.received', 'contact.created', 'conversation.handoff']);
  const LONG_TEXT = new Set(['text', 'message', 'detail', 'notes', 'instructions', 'body']);
  const MAX_TRIGGERS = 5;
  const MAX_STEPS = 25;
  const AGENT_TONES = { ACTIVE: 'ok', PAUSED: 'warn' };
  const RUN_TONES = { QUEUED: 'info', RUNNING: 'info', WAITING: 'info', AWAITING_APPROVAL: 'warn', SUCCEEDED: 'ok', FAILED: 'bad', NEEDS_REVIEW: 'bad' };
  const STEP_TONES = { DONE: 'ok', FAILED: 'bad', REJECTED: 'bad', WAITING: 'info', RUNNING: 'info', AWAITING_APPROVAL: 'warn', DRAFTED: 'accent' };
  const STEP_STATE = { DONE: 'done', FAILED: 'failed', REJECTED: 'failed', WAITING: 'waiting', RUNNING: 'waiting', AWAITING_APPROVAL: 'waiting', DRAFTED: 'done', SKIPPED: 'skipped' };

  const ICONS = {
    bot: '<rect x="5" y="8" width="14" height="11" rx="3"/><path d="M12 4.5V8M9.5 12.5h.01M14.5 12.5h.01M9.5 16h5"/>',
    invoice: '<path d="M6 3h12v18l-3-2-3 2-3-2-3 2z"/><path d="M9.5 8h5M9.5 12h5"/>',
    receipt: '<path d="M7 3h10v18l-2.5-1.5L12 21l-2.5-1.5L7 21z"/><path d="M10 8h4M10 12h4M10 16h2"/>',
    alert: '<path d="M12 4 3 20h18z"/><path d="M12 10v4M12 17h.01"/>',
    heart: '<path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"/>',
    wallet: '<rect x="3" y="6" width="18" height="13" rx="2"/><path d="M3 10h18M15.5 14.5h2.5"/>',
    chart: '<path d="M4 20h16"/><path d="M7 16v-5M12 16V6M17 16v-8"/>',
    quote: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/><path d="M9 13h6M9 17h4"/>',
    clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
    check: '<circle cx="12" cy="12" r="8.5"/><path d="m8.5 12.2 2.4 2.4 4.6-4.8"/>',
    user: '<circle cx="10" cy="8.5" r="3.3"/><path d="M4 19.5c.8-3.2 3.1-5 6-5s5.2 1.8 6 5"/><path d="M18 8v5M15.5 10.5h5"/>',
    calendar: '<rect x="3.5" y="5" width="17" height="15.5" rx="2"/><path d="M8 3v4M16 3v4M3.5 10h17"/>',
    bell: '<path d="M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/>',
    pending: '<path d="M7 3.5h10M7 20.5h10"/><path d="M8 3.5c0 4 8 5 8 8.5s-8 4.5-8 8.5M16 3.5c0 4-8 5-8 8.5s8 4.5 8 8.5"/>',
    star: '<path d="m12 3.8 2.5 5.1 5.6.8-4 3.9.9 5.6-5-2.6-5 2.6.9-5.6-4-3.9 5.6-.8z"/>',
    retry: '<path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3"/><path d="M4.5 4.5v4h4"/>',
    list: '<path d="M9 7h11M9 12h11M9 17h11"/><path d="M4.5 7h.01M4.5 12h.01M4.5 17h.01"/>',
    chat: '<path d="M20 14.5a2 2 0 0 1-2 2H9l-5 4v-14a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2z"/>',
    comment: '<path d="M20 14.5a2 2 0 0 1-2 2H9l-5 4v-14a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2z"/><path d="M8 9h8M8 12.5h5"/>',
    wave: '<path d="M21 3 10 14"/><path d="M21 3 14.5 21 10 14 3 9.5z"/>',
    tasks: '<rect x="5" y="4" width="14" height="17" rx="2"/><path d="M9 4V3h6v1"/><path d="m9 12.5 2 2 4-4"/>',
    mail: '<rect x="3" y="5.5" width="18" height="13" rx="2"/><path d="m4 7 8 6 8-6"/>',
    sparkle: '<path d="M11 4l1.6 4.4L17 10l-4.4 1.6L11 16l-1.6-4.4L5 10l4.4-1.6z"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
  };

  const esc = s => d.escapeHTML(s == null ? '' : String(s));
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const dotKey = key => String(key || '').replace(/\./g, '_');
  const clone = value => JSON.parse(JSON.stringify(value));
  const locale = () => d.uiLocale();

  /** A catalog string under app.agents, or [fallback] (else the key's last part) when it's missing. */
  function tr(path, params, fallback) {
    const full = `app.agents.${path}`;
    const value = I18N.t(full, params);
    if (value !== full && typeof value === 'string') return value;
    return fallback !== undefined ? fallback : String(path).split('.').pop();
  }

  const triggerLabel = type => tr(`triggers.${dotKey(type)}`, null, type);
  const actionLabel = key => tr(`actions.${dotKey(key)}`, null, key);
  const eventLabel = type => tr(`events.${dotKey(type)}`, null, type);
  const entityLabel = type => tr(`entities.${type || 'none'}`, null, type || '');
  const fieldLabel = name => tr(`fields.${name}`, null, name);
  const enumLabel = (field, value) => tr(`enum.${field}.${dotKey(value)}`, null, String(value));
  const opLabel = op => tr(`ops.${op}`, null, op);
  const pill = (tone, text) => `<span class="pill${tone ? ` pill--${tone}` : ''}">${esc(text)}</span>`;
  const agentPill = status => pill(AGENT_TONES[status] || '', tr(`status.${status}`, null, status));
  const runPill = status => pill(RUN_TONES[status] || '', tr(`runStatus.${status}`, null, status));
  const stepPill = status => pill(STEP_TONES[status] || '', tr(`stepStatus.${status}`, null, status));

  function agentIcon(key, size = '') {
    return `<span class="agent-icon${size ? ` agent-icon--${size}` : ''}" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${ICONS[key] || ICONS.bot}</svg></span>`;
  }

  const unit = (n, name) => new Intl.NumberFormat(locale(), { style: 'unit', unit: name, unitDisplay: 'long' }).format(n);
  function duration(input) {
    const parts = [];
    if (input.days) parts.push(unit(input.days, 'day'));
    if (input.hours) parts.push(unit(input.hours, 'hour'));
    if (input.minutes) parts.push(unit(input.minutes, 'minute'));
    return parts.join(' ') || unit(0, 'minute');
  }
  const weekdayName = (n, style = 'long') => new Intl.DateTimeFormat(locale(), { weekday: style, timeZone: 'UTC' }).format(new Date(Date.UTC(2024, 0, n)));
  const joinList = items => new Intl.ListFormat(locale(), { style: 'long', type: 'conjunction' }).format(items);

  /** Code like `exit:invoice.paid` or `fallback_task:window_closed` in words; [steps] name jump targets. */
  function reasonText(code, steps) {
    if (!code) return '';
    // A failed run's error is `<stepId>: <code>`; the failed step is marked in the run itself.
    const stepError = String(code).match(/^[A-Za-z0-9_-]+: (.+)$/);
    if (stepError) return reasonText(stepError[1], steps);
    const [head, ...rest] = String(code).split(':');
    const tail = rest.join(':');
    if (head === 'exit') return tr('reasons.exit', { event: eventLabel(tail) });
    if (head === 'wait_until' || head === 'would_wait_until') return tr(`reasons.${head}`, { when: d.fmtDate(tail) });
    if (head === 'jump') return tr('reasons.jump', { step: stepRef(tail, steps) });
    if (head === 'fallback_task' || head === 'pdf_failed' || head === 'needs_integration' || head === 'needs_module') {
      return tr(`reasons.${head}`, { reason: tail ? tr(`reasons.${tail}`, null, tail) : '', name: tail ? integrationOrModule(head, tail) : '' });
    }
    return tr(`reasons.${head}`, null, code);
  }
  function integrationOrModule(kind, name) {
    return kind === 'needs_module' ? (d.labels[name] || name) : tr(`integrations.${name}`, null, name);
  }

  // ── catalog lookups ───────────────────────────────────────────────
  const catalog = () => data.catalog || { triggers: [], events: [], actions: [], entities: [], operators: [], variables: {}, integrations: [], templates: [] };
  const eventSpec = type => catalog().events.find(e => e.type === type) || null;
  const actionSpec = key => catalog().actions.find(a => a.key === key) || null;
  const triggerSpec = key => catalog().triggers.find(t => t.key === key) || null;
  const entityStatuses = type => catalog().entities.find(e => e.type === type)?.statuses || [];

  function triggerSubject(trigger) {
    const c = trigger?.config || {};
    switch (trigger?.type) {
      case 'event': return eventSpec(c.event)?.subjectType || null;
      case 'schedule': return c.forEach || null;
      case 'date_offset':
      case 'inactivity': return c.entity || null;
      case 'manual': return c.subjectType && c.subjectType !== 'none' ? c.subjectType : null;
      case 'email.received': return 'email';
      default: return null;
    }
  }
  const subjectOf = definition => (definition?.triggers || []).map(triggerSubject).find(Boolean) || null;
  const variablesFor = subjectType => catalog().variables[subjectType || 'none'] || catalog().variables.none || [];

  async function ensureCatalog(force = false) {
    if (!data.catalog || force) data.catalog = await d.api('/app/api/agents/catalog');
    return data.catalog;
  }
  async function ensurePeople() {
    if (!data.people) data.people = await d.api('/app/api/agents/people').catch(() => []);
    return data.people;
  }
  async function ensureWaTemplates() {
    if (data.waTemplates) return data.waTemplates;
    if (!d.hasModule('conversations') || !(catalog().integrations || []).includes('WHATSAPP')) return (data.waTemplates = []);
    data.waTemplates = await d.api('/app/api/whatsapp/templates').then(list => list.filter(t => t.sendable)).catch(() => []);
    return data.waTemplates;
  }
  async function ensureBookable() {
    if (!data.bookable) data.bookable = d.hasModule('bookings') ? await d.api('/app/api/bookings/services').catch(() => []) : [];
    return data.bookable;
  }
  const personName = id => (data.people || []).find(p => p.id === id)?.email || '';

  // ── summaries ─────────────────────────────────────────────────────
  function scheduleSummary(c) {
    const time = c.time || '09:00';
    const days = (c.weekdays || []).slice().sort((a, b) => a - b);
    let text;
    if (c.frequency === 'hourly') text = tr('recipe.hourly', { n: c.everyHours || 1 });
    else if (c.frequency === 'monthly') text = Number(c.dayOfMonth) === -1 ? tr('recipe.monthlyLast', { time }) : tr('recipe.monthly', { day: c.dayOfMonth || 1, time });
    else if (days.length && days.length < 7) {
      const weekdays = days.length === 5 && days.every((n, i) => n === i + 1);
      text = weekdays ? tr('recipe.weekdays', { time }) : tr('recipe.weekly', { days: joinList(days.map(n => weekdayName(n))), time });
    } else text = tr('recipe.daily', { time });
    return c.forEach ? `${text} · ${tr('recipe.forEach', { entity: entityLabel(c.forEach).toLowerCase() })}` : text;
  }
  function triggerSummary(trigger) {
    const c = trigger?.config || {};
    switch (trigger?.type) {
      case 'event': {
        const event = eventLabel(c.event);
        return c.toStatus ? tr('recipe.eventTo', { event, status: d.statusLabel(eventSpec(c.event)?.subjectType, c.toStatus) }) : event;
      }
      case 'schedule': return scheduleSummary(c);
      case 'date_offset': {
        const field = tr(`dateFields.${c.entity}`, null, entityLabel(c.entity));
        const hours = Number(c.offsetDays || 0) * 24 + Number(c.offsetHours || 0);
        if (!hours) return tr('recipe.dateOn', { field });
        const span = c.entity === 'booking' ? unit(Math.abs(hours), 'hour') : unit(Math.abs(Number(c.offsetDays || 0)), 'day');
        return tr(hours < 0 ? 'recipe.dateBefore' : 'recipe.dateAfter', { n: span, field });
      }
      case 'inactivity': return tr(`recipe.idle_${c.entity}`, { n: duration({ days: c.days, hours: c.hours, minutes: c.minutes }) }, triggerLabel('inactivity'));
      case 'manual': return tr('recipe.manual');
      case 'email.received': return tr('recipe.email');
      default: return triggerLabel(trigger?.type);
    }
  }
  function stepShort(step) {
    const input = step.input || {};
    if (step.action === 'flow.wait') return tr('recipe.wait', { time: duration(input) });
    if (step.action === 'data.summary') return enumLabel('kind', input.kind || 'agenda_today');
    return tr(`actionsShort.${dotKey(step.action)}`, null, actionLabel(step.action));
  }
  /** "When … → step → step" as chips. */
  function recipeHtml(definition, max = 4) {
    const triggers = definition?.triggers || [];
    const steps = definition?.steps || [];
    if (!triggers.length && !steps.length) return `<span class="muted">${esc(A.noSteps)}</span>`;
    const summary = triggers.length ? triggerSummary(triggers[0]) : '';
    const when = triggers.length ? `<span class="recipe__part recipe__part--when" title="${esc(summary)}">${esc(summary)}${triggers.length > 1 ? ` <span class="muted">+${triggers.length - 1}</span>` : ''}</span>` : '';
    const shown = steps.slice(0, max).map(s => `<span class="recipe__arrow" aria-hidden="true"></span><span class="recipe__part" title="${esc(stepShort(s))}">${esc(stepShort(s))}</span>`).join('');
    const more = steps.length > max ? `<span class="recipe__arrow" aria-hidden="true"></span><span class="recipe__part muted">+${steps.length - max}</span>` : '';
    return `<span class="recipe">${when}${shown}${more}</span>`;
  }
  /** Template text with its `{{variables}}` shown as chips; [steps] name earlier steps' outputs. */
  function templateText(text, steps) {
    return esc(text || '').replace(/\{\{\s*([A-Za-z0-9_.\-]+)\s*(\|[^}]*)?\}\}/g, (all, path) => `<span class="var-chip var-chip--static">${esc(varLabel(path, steps))}</span>`);
  }
  /** "Step 2" for a step id of [steps], else the id itself. */
  function stepRef(id, steps) {
    const index = (steps || []).findIndex(s => s.id === id);
    return index >= 0 ? tr('flow.stepN', { n: index + 1 }) : id;
  }
  function varLabel(path, steps) {
    const parts = String(path).split('.');
    if (parts[0] === 'steps') {
      const field = parts.slice(3).join('.') || parts[2] || '';
      return tr('builder.stepOutput', { step: stepRef(parts[1], steps), field: tr(`outputs.${field}`, null, field) });
    }
    if (parts[0] === 'params') return parts.slice(1).join('.');
    if (parts.length === 1) return entityLabel(parts[0]);
    const group = parts[0] === 'comment' ? 'instagram_comment' : parts[0];
    const field = parts.slice(1).join('_');
    return `${entityLabel(group)} · ${tr(`vars.${field}`, null, parts.slice(1).join('.'))}`;
  }
  function conditionText(c, steps) {
    const noValue = ['exists', 'not_exists', 'is_empty', 'not_empty'].includes(c.op);
    const value = Array.isArray(c.value) ? c.value.join(', ') : (typeof c.value === 'boolean' ? (c.value ? A.yes : A.no) : c.value);
    const statusEntity = /\.status$/.test(c.field) ? c.field.split('.')[0] : null;
    const shown = statusEntity && typeof c.value === 'string' ? d.statusLabel(statusEntity, c.value) : value;
    return `${varLabel(c.field, steps)} ${opLabel(c.op)}${noValue ? '' : ` ${shown ?? ''}`}`;
  }
  function groupText(group, steps) {
    const items = (group?.conditions || []).map(c => conditionText(c, steps));
    if (!items.length) return '';
    return items.join(group.match === 'ANY' ? ` ${A.or} ` : ` ${A.and} `);
  }
  function stepDetail(step, steps) {
    const i = step.input || {};
    if (step.action === 'whatsapp.send' || step.action === 'instagram.reply') return i.text || '';
    if (step.action === 'team.notify') return i.message || '';
    if (step.action === 'team.task.create') return i.title || '';
    if (step.action === 'flow.branch') return groupText(i.conditions, steps);
    if (step.action === 'flow.wait') {
      const parts = [];
      if (i.at) parts.push(tr('flow.waitAt', { time: i.at }));
      if (i.businessDay) parts.push(tr('flow.waitBusinessDay'));
      return parts.join(' · ');
    }
    return '';
  }
  const autonomyOf = (step, definition) => step.autonomy || definition?.policy?.autonomy || 'APPROVE';
  const sideEffectOf = step => actionSpec(step.action)?.sideEffect || 'NONE';

  // ── loading and the module page ───────────────────────────────────
  async function loadInbox() {
    const [approvals, tasks] = await Promise.all([
      d.api('/app/api/agents/approvals'),
      d.api(`/app/api/agents/tasks${ui.inbox === 'mine' ? '?mine=1' : ''}`),
      ensurePeople(),
    ]);
    data.approvals = approvals;
    data.tasks = tasks;
  }
  async function loadRuns() {
    const statuses = RUN_GROUPS[ui.activity] || [];
    const query = new URLSearchParams({ limit: '100' });
    if (statuses.length) query.set('status', statuses.join(','));
    if (ui.showTests) query.set('tests', '1');
    data.runs = await d.api(`/app/api/agents/runs?${query}`);
  }

  async function load() {
    const jobs = [d.api('/app/api/agents/overview').then(o => { data.overview = o; }), ensureCatalog(ui.tab === 'templates')];
    if (ui.tab === 'agents') jobs.push(d.api('/app/api/agents').then(list => { data.agents = list; }));
    if (ui.tab === 'inbox') jobs.push(loadInbox());
    if (ui.tab === 'activity') jobs.push(loadRuns());
    if (ui.tab === 'settings') jobs.push(d.api('/app/api/agents/settings').then(s => { data.settings = s; }));
    await Promise.all(jobs);
  }

  const canManage = () => !!data.overview?.canManage;

  async function refresh() {
    try { await load(); } catch { d.toast(d.STR.loadFailed); }
    if (d.state.active === 'agents') d.render();
    else d.onChange?.();
  }

  async function switchTab(tab) {
    ui.tab = TABS.includes(tab) ? tab : 'agents';
    await refresh();
  }

  function render(root) {
    const o = data.overview || {};
    const waiting = (o.pendingApprovals || 0) + (o.openTasks || 0);
    const tabs = TABS.map(id => {
      const count = id === 'inbox' && waiting ? `<span class="chip__count">${waiting}</span>` : '';
      return `<button type="button" class="chip ${ui.tab === id ? 'is-on' : ''}" role="tab" aria-selected="${ui.tab === id}" data-agents-tab="${id}">${esc(tr(`tabs.${id}`))}${count}</button>`;
    }).join('');
    const stats = d.statCards([
      { label: tr('stats.active'), value: o.activeAgents ?? 0 },
      { label: tr('stats.runsToday'), value: o.runsToday ?? 0 },
      { label: tr('stats.waiting'), value: waiting },
      { label: tr('stats.failed'), value: o.failedThisWeek ?? 0 },
    ]);
    root.innerHTML = `${d.hero(d.labels.agents, A.desc, stats)}${pausedNotice(o)}<div class="settings-tabs" role="tablist">${tabs}</div><div class="agents-pane"></div>`;
    const pane = $('.agents-pane', root);
    ({ agents: renderList, templates: renderGallery, inbox: renderInbox, activity: renderActivity, settings: renderSettings })[ui.tab](pane);
    $$('[data-agents-tab]', root).forEach(b => b.addEventListener('click', () => switchTab(b.dataset.agentsTab)));
    $('[data-agents-resume]', root)?.addEventListener('click', e => setCompanyPaused(false, e.currentTarget));
  }

  function pausedNotice(o) {
    if (!o.paused) return '';
    const byPlatform = o.pausedBy === 'platform';
    const action = !byPlatform && o.canManage ? `<div class="notice__actions"><button class="btn btn--sm" type="button" data-agents-resume>${esc(A.resume)}</button></div>` : '';
    return `<div class="notice notice--warn" role="status"><div class="notice__text"><strong>${esc(byPlatform ? A.pausedByPlatform : A.pausedTitle)}</strong><span>${esc(byPlatform ? A.pausedByPlatformDesc : A.pausedDesc)}</span></div>${action}</div>`;
  }

  async function setCompanyPaused(paused, button) {
    if (button) button.disabled = true;
    try {
      const current = data.settings || await d.api('/app/api/agents/settings');
      data.settings = await d.api('/app/api/agents/settings', { method: 'PUT', body: JSON.stringify({ settings: { ...current.company, paused } }) });
      d.toast(paused ? A.pausedToast : A.resumedToast);
      await refresh();
    } catch {
      if (button) button.disabled = false;
      d.toast(A.actionFailed);
    }
  }

  // ── agents list ───────────────────────────────────────────────────
  const LIST_FILTERS = ['', 'ACTIVE', 'DRAFT', 'PAUSED'];
  function renderList(pane) {
    const q = (d.state.search || '').toLowerCase();
    const all = data.agents;
    const rows = all
      .filter(a => !ui.listFilter || a.status === ui.listFilter)
      .filter(a => !q || `${a.name} ${a.description || ''}`.toLowerCase().includes(q));
    if (!all.length) {
      pane.innerHTML = `<div class="panel"><div class="empty empty--art">${EMPTY_ART}<p class="empty__title">${esc(A.emptyTitle)}</p><p class="empty__desc">${esc(A.emptyDesc)}</p>
        <div class="empty__actions"><button class="btn btn--primary" type="button" data-agents-go="templates">${esc(A.browseTemplates)}</button>${canManage() ? `<button class="btn btn--ghost" type="button" data-agents-blank>${esc(A.startBlank)}</button>` : ''}</div></div></div>`;
      $('[data-agents-go]', pane).addEventListener('click', () => switchTab('templates'));
      $('[data-agents-blank]', pane)?.addEventListener('click', createBlank);
      return;
    }
    const count = status => all.filter(a => !status || a.status === status).length;
    const tools = LIST_FILTERS.map(f => `<button class="chip ${ui.listFilter === f ? 'is-on' : ''}" type="button" data-agents-filter="${f}">${esc(tr(`filters.${f || 'all'}`))}<span class="chip__count">${count(f)}</span></button>`).join('');
    const body = rows.map(a => {
      const flags = [
        a.pendingApprovals ? pill('warn', tr('pendingCount', { n: a.pendingApprovals })) : '',
        a.problems?.length && a.status !== 'ARCHIVED' ? pill('warn', A.needsSetup) : '',
      ].join(' ');
      const toggle = !canManage() || a.status === 'ARCHIVED' ? '' : a.status === 'ACTIVE'
        ? `<button class="btn btn--sm btn--ghost" type="button" data-agent-pause="${esc(a.id)}">${esc(A.pause)}</button>`
        : `<button class="btn btn--sm" type="button" data-agent-activate="${esc(a.id)}">${esc(A.activate)}</button>`;
      return `<tr class="conversation-row${a.status === 'DRAFT' ? ' is-draft' : ''}" data-agent="${esc(a.id)}">
        <td class="name"><span class="agent-cell">${agentIcon(a.icon)}<span class="agent-cell__text"><span class="agent-cell__name">${esc(a.name)}</span><span class="agent-cell__sub">${recipeHtml(a.definition, 3)}</span></span></span></td>
        <td>${agentPill(a.status)} ${flags}</td>
        <td class="mono muted">${a.stats?.lastRunAt ? esc(d.relTime(a.stats.lastRunAt)) : esc(A.never)}</td>
        <td class="num">${a.stats?.runs || 0}</td>
        <td class="right"><div class="actions">${toggle}</div></td>
      </tr>`;
    }).join('');
    pane.innerHTML = d.crmPanel({
      title: A.listTitle,
      tag: rows.length,
      tools,
      head: `<tr><th>${esc(A.thAgent)}</th><th>${esc(A.thStatus)}</th><th>${esc(A.thLastRun)}</th><th class="right">${esc(A.thRuns)}</th><th class="right"></th></tr>`,
      rows: body,
      empty: A.emptyFiltered,
      emptyDesc: '',
    });
    $$('[data-agents-filter]', pane).forEach(b => b.addEventListener('click', () => { ui.listFilter = b.dataset.agentsFilter; d.render(); }));
    $$('[data-agent]', pane).forEach(r => r.addEventListener('click', e => {
      if (e.target.closest('button')) return;
      openAgent(r.dataset.agent);
    }));
    $$('[data-agent-activate]', pane).forEach(b => b.addEventListener('click', () => setStatus(b.dataset.agentActivate, 'activate', b)));
    $$('[data-agent-pause]', pane).forEach(b => b.addEventListener('click', () => setStatus(b.dataset.agentPause, 'pause', b)));
  }

  const EMPTY_ART = `<svg class="empty__art" viewBox="0 0 128 84" aria-hidden="true" focusable="false">
    <path class="art-accent" d="M96 12.5c.5 3.1 1.9 4.5 5 5-3.1.5-4.5 1.9-5 5-.5-3.1-1.9-4.5-5-5 3.1-.5 4.5-1.9 5-5z"/>
    <path class="art-accent" d="M30 18c.4 2.3 1.4 3.3 3.7 3.7-2.3.4-3.3 1.4-3.7 3.7-.4-2.3-1.4-3.3-3.7-3.7 2.3-.4 3.3-1.4 3.7-3.7z"/>
    <g class="art-line">
      <path d="M20 74h88"/>
      <rect x="46" y="30" width="36" height="30" rx="8"/>
      <path d="M64 22v8M56 44h.01M72 44h.01M57 52h14"/>
      <path d="M46 44h-6M88 44h-6"/>
      <circle cx="64" cy="20" r="2.5"/>
    </g>
  </svg>`;

  async function setStatus(id, action, button) {
    if (button) button.disabled = true;
    try {
      await d.api(`/app/api/agents/${encodeURIComponent(id)}/${action}`, { method: 'POST' });
      d.toast(action === 'activate' ? A.activated : A.pausedAgent);
      await refresh();
      return true;
    } catch (err) {
      if (button) button.disabled = false;
      d.toast(activationError(err));
      return false;
    }
  }
  function activationError(err) {
    if (err?.code === 'agent_limit') return A.limitReached;
    if (err?.status === 422) return A.cannotActivate;
    return A.actionFailed;
  }

  async function createBlank() {
    try {
      const agent = await d.api('/app/api/agents', { method: 'POST', body: JSON.stringify({ name: A.untitled, icon: 'bot' }) });
      await ensureCatalog();
      ui.tab = 'agents';
      await refresh();
      openBuilder(agent);
    } catch {
      d.toast(A.actionFailed);
    }
  }

  // ── agent record ──────────────────────────────────────────────────
  async function openAgent(id, focusTab = 'overview') {
    const body = document.createElement('div');
    body.className = 'record';
    body.innerHTML = `<p class="hint">${esc(d.CRM.loading)}</p>`;
    const known = data.agents.find(a => a.id === id);
    const gen = d.openDrawer(known?.name || d.CRM.loading, body, true, { eyebrow: d.labels.agents });
    let agent;
    let runs;
    try {
      [agent, runs] = await Promise.all([
        d.api(`/app/api/agents/${encodeURIComponent(id)}`),
        d.api(`/app/api/agents/runs?agentId=${encodeURIComponent(id)}&limit=30`),
        ensureCatalog(),
      ]);
    } catch {
      d.closeDrawer({ dismissed: true });
      return d.toast(d.STR.loadFailed);
    }
    if (gen !== d.drawerGen()) return;
    const here = { key: `agent:${agent.id}`, label: agent.name, open: () => openAgent(agent.id, tab) };
    let tab = focusTab;
    const draw = () => {
      $('#drawer-title').textContent = agent.name;
      body.innerHTML = agentRecordHtml(agent, runs, tab);
      wireAgentRecord(body, agent, runs, here, next => { tab = next; draw(); });
    };
    draw();
  }

  function agentRecordHtml(agent, runs, tab) {
    const def = agent.definition || {};
    const manage = canManage();
    const archived = agent.status === 'ARCHIVED';
    const template = agent.templateKey ? tr(`templates.${agent.templateKey}.name`, null, '') : '';
    const since = [
      tr('record.created', { date: d.fmtDay(agent.createdAt) }),
      tr('record.version', { n: agent.version }),
      template ? tr('record.fromTemplate', { name: template }) : '',
    ].filter(Boolean).join(' · ');
    const actions = manage ? [
      !archived ? `<button class="btn btn--sm btn--ghost" type="button" data-agent-act="edit">${esc(A.edit)}</button>` : '',
      `<button class="btn btn--sm btn--ghost" type="button" data-agent-act="duplicate">${esc(A.duplicate)}</button>`,
      companiesToCopy().length ? `<button class="btn btn--sm btn--ghost" type="button" data-agent-act="copy">${esc(A.copyTo)}</button>` : '',
      !archived ? `<button class="btn btn--sm btn--ghost" type="button" data-agent-act="remove">${esc(agent.stats?.runs ? A.archive : A.delete)}</button>` : '',
    ].join('') : '';
    const problems = (agent.problems || []).length && !archived
      ? `<div class="notice notice--warn"><div class="notice__text"><strong>${esc(A.problemsTitle)}</strong><ul class="notice__list">${agent.problems.map(p => `<li>${esc(problemText(p, def))}</li>`).join('')}</ul></div>${manage ? `<div class="notice__actions"><button class="btn btn--sm" type="button" data-agent-act="edit">${esc(A.fix)}</button></div>` : ''}</div>`
      : '';
    const pausedReason = agent.pausedReason ? `<div class="notice notice--warn"><div class="notice__text"><span>${esc(tr(`pausedReasons.${agent.pausedReason}`, null, reasonText(agent.pausedReason)))}</span></div></div>` : '';
    const suggest = agent.suggestAuto && manage
      ? `<div class="notice notice--info"><div class="notice__text"><span>${esc(tr('record.suggestAuto', { n: agent.stats?.approvalsInARow || 0 }))}</span></div><div class="notice__actions"><button class="btn btn--sm" type="button" data-agent-act="auto">${esc(A.switchAuto)}</button></div></div>`
      : '';
    const kpis = d.recordKpisHtml([
      { label: tr('record.kpiRuns'), value: String(agent.stats?.runs || 0), sub: agent.stats?.lastRunAt ? tr('record.lastRun', { when: d.relTime(agent.stats.lastRunAt) }) : A.never },
      { label: tr('record.kpiSucceeded'), value: String(agent.stats?.succeeded || 0), sub: '' },
      { label: tr('record.kpiFailed'), value: String(agent.stats?.failed || 0), sub: agent.stats?.consecutiveFailures ? tr('record.inARow', { n: agent.stats.consecutiveFailures }) : '', tone: agent.stats?.failed ? 'bad' : '' },
      agent.nextFireAt
        ? { label: tr('record.kpiNext'), value: d.fmtWhen(agent.nextFireAt), sub: '' }
        : { label: tr('record.kpiWaiting'), value: String(agent.pendingApprovals || 0), sub: '', tone: agent.pendingApprovals ? 'warn' : '' },
    ]);
    const chips = ['overview', 'runs'].map(id => `<button class="chip ${tab === id ? 'is-on' : ''}" type="button" role="tab" data-agent-tab="${id}">${esc(tr(`record.${id}`))}${id === 'runs' && runs.length ? `<span class="chip__count">${runs.length}</span>` : ''}</button>`).join('');
    const pane = tab === 'runs' ? runsTableHtml(runs, false) : flowHtml(def);
    const toggle = !manage || archived ? '' : agent.status === 'ACTIVE'
      ? `<button class="btn btn--sm btn--ghost" type="button" data-agent-act="pause">${esc(A.pause)}</button>`
      : `<button class="btn btn--sm btn--primary" type="button" data-agent-act="activate">${esc(A.activate)}</button>`;
    const manualNone = (def.triggers || []).some(t => t.type === 'manual' && (!t.config?.subjectType || t.config.subjectType === 'none'));
    const foot = `<div class="drawer__foot record__foot">
      <button class="btn btn--sm btn--ghost" type="button" data-agent-act="test">${esc(A.test)}</button>
      ${manualNone && agent.status === 'ACTIVE' ? `<button class="btn btn--sm btn--ghost" type="button" data-agent-act="run">${esc(A.runNow)}</button>` : ''}
      ${toggle}
    </div>`;
    return `<section class="record-card">
        <div class="record-card__head">
          ${agentIcon(agent.icon, 'lg')}
          <div class="record-card__who">
            <div class="record-card__lines"><span class="record-card__line">${agentPill(agent.status)} <span class="muted">${esc(tr(`kinds.${agent.kind}`, null, agent.kind))}</span></span>${agent.description ? `<span class="record-card__line">${esc(agent.description)}</span>` : ''}</div>
            <p class="record-card__since">${esc(since)}</p>
          </div>
          <div class="actions">${actions}</div>
        </div>
      </section>
      ${problems}${pausedReason}${suggest}${kpis}
      <div class="chip-tabs" role="tablist">${chips}</div>
      <div class="record__pane" role="tabpanel">${pane}</div>
      ${foot}`;
  }

  function wireAgentRecord(body, agent, runs, here, setTab) {
    $$('[data-agent-tab]', body).forEach(b => b.addEventListener('click', () => setTab(b.dataset.agentTab)));
    $$('[data-run]', body).forEach(r => r.addEventListener('click', () => d.openFrom(here, () => openRun(r.dataset.run))));
    $$('[data-agent-act]', body).forEach(b => b.addEventListener('click', async () => {
      const act = b.dataset.agentAct;
      if (act === 'edit') return d.openFrom(here, () => openBuilder(agent));
      if (act === 'test') return d.openFrom(here, () => runTest(agent));
      if (act === 'activate' || act === 'pause') {
        if (await setStatus(agent.id, act, b)) openAgent(agent.id);
        return;
      }
      if (act === 'run') return runNow(agent, b);
      if (act === 'duplicate') return duplicate(agent, b);
      if (act === 'copy') return d.openFrom(here, () => openCopy(agent));
      if (act === 'remove') return removeAgent(agent);
      if (act === 'auto') return switchToAuto(agent, b);
    }));
  }

  function flowHtml(def) {
    const triggers = def.triggers || [];
    const steps = def.steps || [];
    const policy = def.policy || {};
    const parts = [];
    parts.push(`<li class="flow__step flow__step--trigger"><span class="flow__index" aria-hidden="true">${ICON_BOLT}</span><div class="flow__main"><div class="flow__eyebrow">${esc(tr('flow.when'))}</div>${triggers.map(t => `<div class="flow__title">${esc(triggerSummary(t))}</div>`).join('') || `<div class="flow__detail">${esc(A.noTrigger)}</div>`}${def.conditions?.conditions?.length ? `<div class="flow__detail">${esc(tr('flow.onlyIf', { text: groupText(def.conditions, steps) }))}</div>` : ''}</div></li>`);
    steps.forEach((s, i) => {
      parts.push('<li class="flow__connector" aria-hidden="true"></li>');
      parts.push(flowStepHtml(s, i, def));
    });
    const exits = (def.exitRules || []).map(r => exitText(r));
    const rules = [
      tr(`autonomy.${policy.autonomy || 'APPROVE'}`),
      quietText(policy),
      policy.businessDaysOnly === true ? tr('flow.businessDays') : '',
      policy.maxRunsPerDay && policy.maxRunsPerDay !== 200 ? tr('flow.maxRuns', { n: policy.maxRunsPerDay }) : '',
      policy.cooldownHours ? tr('flow.cooldown', { hours: policy.cooldownHours }) : '',
      policy.approvers === 'ADMINS' ? tr('flow.adminsApprove') : '',
    ].filter(Boolean);
    return `<div class="record__stack">
      <ol class="flow">${parts.join('')}</ol>
      ${exits.length ? `<div class="panel"><header class="panel__head"><h2 class="panel__title">${esc(tr('flow.stopWhen'))}</h2></header><div class="panel__body"><ul class="plain-list">${exits.map(t => `<li>${esc(t)}</li>`).join('')}</ul></div></div>` : ''}
      <div class="panel"><header class="panel__head"><h2 class="panel__title">${esc(tr('flow.rules'))}</h2></header><div class="panel__body"><div class="recipe">${rules.map(r => `<span class="recipe__part">${esc(r)}</span>`).join('')}</div></div></div>
    </div>`;
  }
  const ICON_BOLT = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 3 5 14h6l-1 7 8-11h-6z"/></svg>';

  function flowStepHtml(step, index, def, result = null) {
    const steps = def?.steps || [];
    const effect = sideEffectOf(step);
    const autonomy = effect === 'NONE' ? '' : pill(autonomyOf(step, def) === 'AUTO' ? 'ok' : autonomyOf(step, def) === 'DRAFT' ? '' : 'warn', tr(`autonomy.${autonomyOf(step, def)}`));
    const detail = stepDetail(step, steps);
    const guard = step.guard?.conditions?.length ? `<div class="flow__detail">${esc(tr('flow.onlyIf', { text: groupText(step.guard, steps) }))}</div>` : '';
    const state = result ? STEP_STATE[result.status] || '' : '';
    const side = result ? stepPill(result.status) : autonomy;
    const note = result ? stepNoteHtml(result, steps) : '';
    return `<li class="flow__step"${state ? ` data-state="${state}"` : ''}>
      <span class="flow__index">${index + 1}</span>
      <div class="flow__main">
        <div class="flow__title">${esc(step.label || stepShort(step))}</div>
        ${detail ? `<div class="flow__detail">${templateText(detail, steps)}</div>` : ''}${guard}${note}
      </div>
      <div class="flow__side">${side}</div>
    </li>`;
  }
  function stepNoteHtml(result, steps) {
    const lines = [];
    if (result.note) lines.push(`<div class="flow__detail">${esc(reasonText(result.note, steps))}</div>`);
    if (result.error) lines.push(`<div class="flow__detail flow__detail--bad">${esc(reasonText(result.error, steps))}</div>`);
    const preview = result.status === 'DRAFTED' ? result.output : null;
    (preview?.warnings || []).forEach(w => lines.push(`<p class="hint hint--warn">${esc(tr(`warnings.${w}`, null, w))}</p>`));
    if (preview?.subject) lines.push(`<div class="flow__detail">${esc(preview.subject)}</div>`);
    if (preview?.body) lines.push(`<div class="wa-preview"><div class="bubble bubble--out bubble--ai"><div class="bubble__text">${esc(preview.body)}</div></div></div>`);
    if ((preview?.attachments || []).length) lines.push(`<div class="flow__detail">${esc(tr('inbox.attachments', { files: preview.attachments.join(', ') }))}</div>`);
    if (preview?.fields && Object.keys(preview.fields).length) lines.push(previewFactsHtml(preview.fields, result.action, steps));
    if (result.status === 'DONE' && result.action === 'data.summary' && result.output?.text) lines.push(`<div class="flow__detail">${esc(result.output.text)}</div>`);
    return lines.join('');
  }
  /** An action preview's details (`deposit_percent`, `from`/`to` statuses, `wait_until`, drafted inputs…) as facts. */
  function previewFactsHtml(fields, action, steps) {
    const entity = action === 'crm.quote.set_status' ? 'quote' : String(action || '').startsWith('booking.') ? 'booking' : null;
    const value = (key, v) => {
      if ((key === 'from' || key === 'to') && entity) return d.statusLabel(entity, v);
      if (key === 'wait_until' || key === 'startAt') return Number.isNaN(Date.parse(v)) ? v : d.fmtDate(v);
      if (key === 'next') return v === 'next' ? A.nextStep : v === 'end' ? A.endRun : stepRef(v, steps);
      if (key === 'amountEur' && v !== '' && Number.isFinite(Number(v))) return d.fmtEUR(v);
      if (key === 'payeeType') return enumLabel('payeeType', v);
      if (key === 'serviceId') return (data.bookable || []).find(s => s.id === v)?.name || v;
      return v;
    };
    return `<dl class="dash-facts">${Object.entries(fields).map(([k, v]) => `<div><dt>${esc(tr(`previewFields.${k}`, null, fieldLabel(k)))}</dt><dd>${esc(value(k, v))}</dd></div>`).join('')}</dl>`;
  }
  function exitText(rule) {
    const to = (rule.conditions?.conditions || []).find(c => c.field === 'event.to');
    const entity = eventSpec(rule.event)?.subjectType;
    return to ? tr('recipe.eventTo', { event: eventLabel(rule.event), status: d.statusLabel(entity, to.value) }) : eventLabel(rule.event);
  }
  function quietText(policy) {
    const q = policy.quietHours;
    if (!q) return tr('flow.quietCompany');
    if (q.start === q.end) return tr('flow.noQuiet');
    return tr('flow.quiet', { start: q.start, end: q.end });
  }

  function problemText(problem, def) {
    const where = problemPlace(problem.path, def);
    const detail = problemDetail(problem);
    const text = tr(`problems.${problem.code}`, { detail }, problem.code);
    return where ? `${where}: ${text}` : text;
  }
  function problemDetail(problem) {
    if (problem.code === 'needs_module') return d.labels[problem.detail] || problem.detail || '';
    if (problem.code === 'needs_integration') return tr(`integrations.${problem.detail}`, null, problem.detail || '');
    if (problem.code === 'unknown_variable' || problem.code === 'unknown_field') return problem.detail ? `{{${problem.detail}}}` : '';
    if (problem.code === 'unknown_event') return eventLabel(problem.detail);
    if (problem.code === 'wrong_subject') return entityLabel(problem.detail);
    return problem.detail || '';
  }
  function problemPlace(path, def) {
    const m = String(path || '').match(/^(triggers|steps|exitRules)\[(\d+)\](?:\.input)?\.?([A-Za-z]+)?/);
    if (m) {
      const n = Number(m[2]) + 1;
      const field = m[3] && !['guard', 'conditions'].includes(m[3]) ? ` · ${fieldLabel(m[3])}` : '';
      if (m[1] === 'steps') return `${tr('flow.stepN', { n })}${field}`;
      if (m[1] === 'triggers') return `${tr('flow.triggerN', { n })}${field}`;
      return tr('flow.exitN', { n });
    }
    if (String(path).startsWith('conditions')) return tr('builder.onlyIf');
    if (String(path).startsWith('policy')) return tr('builder.policy');
    return '';
  }

  const companiesToCopy = () => (d.state.me?.companies || []).filter(c => c.id && c.id !== d.state.me?.tenant?.id);

  async function duplicate(agent, button) {
    button.disabled = true;
    try {
      const copy = await d.api(`/app/api/agents/${encodeURIComponent(agent.id)}/duplicate`, { method: 'POST', body: JSON.stringify({ name: tr('record.copyName', { name: agent.name }) }) });
      d.toast(A.duplicated);
      await refresh();
      openAgent(copy.id);
    } catch {
      button.disabled = false;
      d.toast(A.actionFailed);
    }
  }

  function openCopy(agent) {
    const form = document.createElement('form');
    form.className = 'form';
    const companies = companiesToCopy();
    form.innerHTML = `<p class="hint">${esc(A.copyDesc)}</p>
      <div class="form__row"><label class="lbl" for="ag-copy-company">${esc(A.copyCompany)}</label>
        <select class="sel" id="ag-copy-company">${companies.map(c => `<option value="${esc(c.id)}">${esc(c.name)}</option>`).join('')}</select></div>
      <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${esc(d.STR.cancel)}</button><button class="btn btn--accent" type="submit">${esc(A.copyTo)}</button></div>`;
    d.openDrawer(A.copyTitle, form, false, { eyebrow: agent.name });
    d.bindDrawerClose(form);
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const btn = $('button[type="submit"]', form);
      btn.disabled = true;
      const target = $('#ag-copy-company', form);
      try {
        await d.api(`/app/api/agents/${encodeURIComponent(agent.id)}/copy`, { method: 'POST', body: JSON.stringify({ companyId: target.value }) });
        d.toast(tr('record.copied', { company: target.selectedOptions[0]?.textContent || '' }));
        d.closeDrawer();
      } catch {
        btn.disabled = false;
        d.toast(A.actionFailed);
      }
    });
  }

  async function removeAgent(agent) {
    const archive = (agent.stats?.runs || 0) > 0;
    const ok = await d.confirmDialog({ title: archive ? A.archiveTitle : A.deleteTitle, body: archive ? A.archiveBody : A.deleteBody, okLabel: archive ? A.archive : A.delete });
    if (!ok) return;
    try {
      await d.api(`/app/api/agents/${encodeURIComponent(agent.id)}`, { method: 'DELETE' });
      d.closeDrawer({ dismissed: true });
      d.toast(archive ? A.archivedToast : A.deletedToast);
      await refresh();
    } catch {
      d.toast(A.actionFailed);
    }
  }

  async function switchToAuto(agent, button) {
    button.disabled = true;
    const def = clone(agent.definition);
    def.policy = { ...(def.policy || {}), autonomy: 'AUTO' };
    def.steps = (def.steps || []).map(s => (s.autonomy === 'APPROVE' ? { ...s, autonomy: 'AUTO' } : s));
    try {
      await d.api(`/app/api/agents/${encodeURIComponent(agent.id)}`, { method: 'PUT', body: JSON.stringify({ definition: def }) });
      d.toast(A.switchedAuto);
      await refresh();
      openAgent(agent.id);
    } catch {
      button.disabled = false;
      d.toast(A.actionFailed);
    }
  }

  async function runNow(agent, button) {
    button.disabled = true;
    try {
      await d.api(`/app/api/agents/${encodeURIComponent(agent.id)}/run`, { method: 'POST', body: JSON.stringify({}) });
      d.toast(A.started);
      openAgent(agent.id, 'runs');
    } catch (err) {
      button.disabled = false;
      d.toast(err?.code ? reasonText(err.code) : A.actionFailed);
    }
  }

  /** A dry run of [definition] (the saved one when omitted) on the newest record, or on [subject]. */
  async function runTest(agent, definition = null, subject = null) {
    const body = document.createElement('div');
    body.className = 'record';
    body.innerHTML = `<p class="hint">${esc(A.testing)}</p>`;
    const gen = d.openDrawer(A.testTitle, body, true, { eyebrow: agent.name });
    try {
      const run = await d.api(`/app/api/agents/${encodeURIComponent(agent.id)}/test`, {
        method: 'POST',
        body: JSON.stringify({ definition: definition || undefined, subjectType: subject?.type, subjectId: subject?.id }),
      });
      if (gen !== d.drawerGen()) return;
      renderRun(body, run, { key: `agent:${agent.id}`, label: agent.name, open: () => openAgent(agent.id) });
    } catch (err) {
      if (gen !== d.drawerGen()) return;
      body.innerHTML = `<div class="empty"><p class="empty__title">${esc(err?.code === 'record_missing' ? A.testNoRecord : A.actionFailed)}</p></div>`;
    }
  }

  // ── runs ──────────────────────────────────────────────────────────
  function runProgress(run) {
    if (run.status === 'WAITING' && run.resumeAt) return tr('run.resumes', { when: d.fmtWhen(run.resumeAt) });
    if (run.status === 'AWAITING_APPROVAL') return A.waitingApproval;
    if (run.error) return reasonText(run.error);
    if (run.outcome && run.status !== 'SUCCEEDED') return reasonText(run.outcome);
    if (run.stepCount && !['SUCCEEDED', 'SKIPPED', 'CANCELLED'].includes(run.status)) {
      return tr('run.stepOf', { n: Math.min(run.currentStep + 1, run.stepCount), total: run.stepCount, action: run.nextStep ? actionLabel(run.nextStep) : '' });
    }
    return run.finishedAt ? tr('run.finishedWhen', { when: d.relTime(run.finishedAt) }) : '';
  }
  function runsTableHtml(runs, withAgent, bare = false) {
    const rows = runs.map(r => `<tr class="conversation-row" data-run="${esc(r.id)}">
      <td class="mono muted">${esc(d.fmtWhen(r.createdAt))}</td>
      ${withAgent ? `<td class="name">${esc(r.agentName)}</td>` : ''}
      <td>${esc(r.subjectLabel || tr('run.noRecord'))}</td>
      <td>${runPill(r.status)}${r.dryRun ? ` ${pill('accent', A.test)}` : ''}</td>
      <td class="muted">${esc(runProgress(r))}</td>
    </tr>`).join('');
    const head = `<tr><th>${esc(tr('activity.thWhen'))}</th>${withAgent ? `<th>${esc(A.thAgent)}</th>` : ''}<th>${esc(tr('activity.thRecord'))}</th><th>${esc(A.thStatus)}</th><th>${esc(tr('activity.thProgress'))}</th></tr>`;
    if (!rows) return `<div class="panel"><div class="empty"><p class="empty__title">${esc(tr('record.noRuns'))}</p><p class="empty__desc">${esc(tr('record.noRunsDesc'))}</p></div></div>`;
    const table = `<div class="tbl-wrap"><table class="tbl"><thead>${head}</thead><tbody>${rows}</tbody></table></div>`;
    return bare ? table : `<div class="panel">${table}</div>`;
  }

  async function openRun(id) {
    const body = document.createElement('div');
    body.className = 'record';
    body.innerHTML = `<p class="hint">${esc(d.CRM.loading)}</p>`;
    const gen = d.openDrawer(tr('run.title'), body, true, { eyebrow: d.labels.agents });
    try {
      const [run] = await Promise.all([d.api(`/app/api/agents/runs/${encodeURIComponent(id)}`), ensureCatalog()]);
      if (gen !== d.drawerGen()) return;
      renderRun(body, run, { key: `run:${run.id}`, label: tr('run.title'), open: () => openRun(run.id) });
    } catch {
      d.closeDrawer({ dismissed: true });
      d.toast(d.STR.loadFailed);
    }
  }

  function renderRun(body, run, here) {
    $('#drawer-title').textContent = run.agentName;
    const results = new Map((run.steps || []).map(s => [s.stepId, s]));
    const plan = run.plan || (run.steps || []).map(s => ({ id: s.stepId, action: s.action }));
    const finished = !RUN_GROUPS.open.includes(run.status);
    const planDef = { steps: plan };
    const steps = plan.map((p, i) => {
      const result = results.get(p.id);
      const step = { id: p.id, action: p.action, label: p.label, input: result?.input || p.input || {} };
      const html = flowStepHtml(step, i, planDef, result || { status: 'PENDING', note: finished ? 'not_reached' : '' });
      return (i ? '<li class="flow__connector" aria-hidden="true"></li>' : '') + html;
    }).join('');
    const open = RUN_GROUPS.open.includes(run.status);
    const retryable = ['FAILED', 'NEEDS_REVIEW'].includes(run.status) && canManage() && !run.dryRun;
    const trigger = run.trigger?.eventType ? eventLabel(run.trigger.eventType) : triggerLabel(run.trigger?.type);
    const outcome = run.error || (run.outcome && !['completed'].includes(run.outcome) ? run.outcome : '');
    body.innerHTML = `<div class="detail__head">
        <div class="detail__client">${esc(run.subjectLabel || tr('run.noRecord'))}</div>
        <div class="detail__amount">${runPill(run.status)}${run.dryRun ? ` ${pill('accent', A.test)}` : ''}</div>
      </div>
      ${d.detailMeta([
        { label: tr('run.trigger'), value: `${trigger} · ${d.fmtDate(run.trigger?.firedAt)}` },
        run.finishedAt ? { label: tr('run.finished'), value: d.fmtDate(run.finishedAt) } : null,
        run.resumeAt && open ? { label: tr('run.continues'), value: d.fmtDate(run.resumeAt) } : null,
        run.tokens ? { label: tr('run.ai'), value: tr('run.tokens', { n: run.tokens }) } : null,
      ])}
      ${run.dryRun ? `<p class="hint">${esc(A.testDesc)}</p>` : ''}
      ${outcome ? `<div class="notice ${run.error ? 'notice--warn' : 'notice--info'}"><div class="notice__text"><span>${esc(reasonText(outcome))}</span></div></div>` : ''}
      <ol class="flow">${steps || `<li class="flow__step"><span class="flow__index">–</span><div class="flow__main"><div class="flow__detail">${esc(A.noSteps)}</div></div></li>`}</ol>
      <div class="detail__foot">
        ${run.subject && d.canOpenSubject(run.subject) ? `<button class="btn btn--sm btn--ghost" type="button" data-run-act="record">${esc(tr('run.openRecord'))}</button>` : ''}
        ${open && !run.dryRun ? `<button class="btn btn--sm btn--ghost" type="button" data-run-act="cancel">${esc(tr('run.cancel'))}</button>` : ''}
        ${retryable ? `<button class="btn btn--sm btn--primary" type="button" data-run-act="retry">${esc(tr('run.retry'))}</button>` : ''}
      </div>`;
    $$('[data-run-act]', body).forEach(b => b.addEventListener('click', async () => {
      const act = b.dataset.runAct;
      if (act === 'record') return d.openSubject(run.subject, here);
      if (act === 'cancel') {
        const ok = await d.confirmDialog({ title: tr('run.cancelTitle'), body: tr('run.cancelBody'), okLabel: tr('run.cancel') });
        if (!ok) return;
      }
      b.disabled = true;
      try {
        const next = await d.api(`/app/api/agents/runs/${encodeURIComponent(run.id)}/${act}`, { method: 'POST' });
        d.toast(act === 'cancel' ? tr('run.cancelled') : tr('run.retried'));
        renderRun(body, next, here);
        refresh();
      } catch {
        b.disabled = false;
        d.toast(A.actionFailed);
      }
    }));
  }

  // ── template gallery and guided setup ─────────────────────────────
  const CATEGORIES = ['finance', 'sales', 'bookings', 'inbox', 'email', 'housekeeping'];
  function renderGallery(pane) {
    const templates = catalog().templates || [];
    const categories = CATEGORIES.filter(c => templates.some(t => t.category === c));
    const q = (d.state.search || '').toLowerCase();
    const shown = templates
      .filter(t => !ui.category || t.category === ui.category)
      .filter(t => !q || `${templateName(t)} ${templateDesc(t)}`.toLowerCase().includes(q));
    const chips = ['', ...categories].map(c => `<button class="chip ${ui.category === c ? 'is-on' : ''}" type="button" data-agents-category="${c}">${esc(c ? tr(`categories.${c}`) : tr('filters.all'))}</button>`).join('');
    const blank = canManage() && !ui.category && !q
      ? `<button class="gallery__card gallery__card--blank" type="button" data-agents-blank><span class="gallery__head">${agentIcon('plus')}<span class="gallery__title">${esc(A.startBlank)}</span></span><span class="gallery__desc">${esc(A.startBlankDesc)}</span></button>`
      : '';
    const cards = shown.map(t => {
      const reason = t.available ? '' : pill('', reasonText(t.reason));
      return `<button class="gallery__card${t.available ? '' : ' is-unavailable'}" type="button" data-template="${esc(t.key)}">
        <span class="gallery__head">${agentIcon(t.icon)}<span class="gallery__title">${esc(templateName(t))}</span></span>
        <span class="gallery__desc">${esc(templateDesc(t))}</span>
        <span class="gallery__recipe">${recipeHtml(t.definition, 3)}</span>
        <span class="gallery__meta">${pill('', tr(`categories.${t.category}`))}${t.messagesCustomers ? pill('info', A.messagesCustomers) : ''}${reason}</span>
      </button>`;
    }).join('');
    pane.innerHTML = `<div class="panel panel--card"><header class="panel__head"><div><h2 class="panel__title">${esc(A.galleryTitle)}</h2><p class="panel__meta">${esc(A.galleryDesc)}</p></div><div class="panel__tools">${chips}</div></header>
      <div class="panel__body">${cards || blank ? `<div class="gallery">${blank}${cards}</div>` : `<div class="empty"><p class="empty__title">${esc(A.galleryEmpty)}</p></div>`}</div></div>`;
    $$('[data-agents-category]', pane).forEach(b => b.addEventListener('click', () => { ui.category = b.dataset.agentsCategory; d.render(); }));
    $('[data-agents-blank]', pane)?.addEventListener('click', createBlank);
    $$('[data-template]', pane).forEach(b => b.addEventListener('click', () => openTemplateSetup(templates.find(t => t.key === b.dataset.template))));
  }
  const templateName = t => tr(`templates.${t.key}.name`, null, t.name || t.key);
  const templateDesc = t => tr(`templates.${t.key}.desc`, null, '');
  const paramLabel = (template, name) => tr(`templates.${template.key}.params.${name}`, null, tr(`params.${name}`, null, fieldLabel(name)));

  function openTemplateSetup(template) {
    if (!template) return;
    const answers = {};
    const form = document.createElement('form');
    form.className = 'form';
    const manage = canManage();
    const ctx = { kind: 'params', key: template.key, label: name => paramLabel(template, name), draft: { answers }, root: 'answers', subjectType: subjectOf(template.definition) };
    form.innerHTML = `<p class="view__desc">${esc(templateDesc(template))}</p>
      <div class="panel"><div class="panel__body"><div class="flow__eyebrow">${esc(A.whatItDoes)}</div>${recipeHtml(template.definition, 6)}</div></div>
      ${template.available ? '' : `<div class="notice notice--warn"><div class="notice__text"><strong>${esc(A.notAvailable)}</strong><span>${esc((template.requires || [template.reason]).map(reasonText).join(' · '))}</span></div></div>`}
      ${template.messagesCustomers ? `<p class="hint">${esc(A.asksFirstHint)}</p>` : ''}
      <div class="form__grid">
        <div class="form__row form__row--full"><label class="lbl" for="ag-tpl-name">${esc(A.nameLabel)}</label><input class="inp" id="ag-tpl-name" maxlength="80" value="${esc(templateName(template))}" /></div>
        ${schemaFields(template.params, answers, 'answers', ctx)}
      </div>
      <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${esc(d.STR.cancel)}</button>
        <button class="btn btn--accent" type="submit" ${manage ? '' : 'disabled'}>${esc(A.createDraft)}</button></div>`;
    d.openDrawer(templateName(template), form, false, { eyebrow: tr(`categories.${template.category}`) });
    d.bindDrawerClose(form);
    bindInputs(form, ctx.draft, () => {});
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const btn = $('button[type="submit"]', form);
      btn.disabled = true;
      btn.textContent = A.creating;
      try {
        const agent = await d.api('/app/api/agents', { method: 'POST', body: JSON.stringify({ templateKey: template.key, params: answers, name: $('#ag-tpl-name', form).value.trim() || undefined }) });
        d.toast(A.draftCreated);
        ui.tab = 'agents';
        await refresh();
        openAgent(agent.id);
      } catch (err) {
        btn.disabled = false;
        btn.textContent = A.createDraft;
        d.toast(err?.code === 'invalid_params' ? A.invalidAnswers : A.actionFailed);
      }
    });
  }

  // ── schema-driven fields ──────────────────────────────────────────
  // Every control carries data-bind="path.in.draft" and data-type; bindInputs() writes values back.
  const VISIBLE = {
    'whatsapp.send': { phone: v => v.to === 'phone', templateLanguage: () => false, templateParams: () => false },
    'team.notify': { userId: v => v.audience === 'user' },
    schedule: {
      weekdays: v => ['daily', 'weekly', 'hourly', undefined].includes(v.frequency),
      dayOfMonth: v => v.frequency === 'monthly',
      everyHours: v => v.frequency === 'hourly',
      where: v => !!v.forEach,
    },
    date_offset: { offsetHours: v => v.entity === 'booking', offsetDays: v => v.entity !== 'booking', at: v => v.entity !== 'booking' },
    event: { toStatus: v => STATUS_EVENTS.has(v.event), channel: v => CHANNEL_EVENTS.has(v.event), keywords: v => v.event === 'message.received' },
  };
  const RERENDER = new Set(['to', 'audience', 'frequency', 'forEach', 'entity', 'event', 'template', 'subjectType', 'sender']);

  function schemaFields(schema, value, base, ctx) {
    const props = schema?.properties || {};
    const required = new Set(schema?.required || []);
    const visible = VISIBLE[ctx.key] || {};
    const current = value || {};
    return Object.entries(props)
      .filter(([name]) => !visible[name] || visible[name](current))
      .map(([name, prop]) => fieldHtml(name, prop, current[name], `${base}.${name}`, ctx, required.has(name)))
      .join('');
  }

  function fieldHtml(name, prop, value, path, ctx, required) {
    const id = `ag-f-${path.replace(/[^A-Za-z0-9]/g, '-')}`;
    const label = (ctx.label || fieldLabel)(name);
    const widget = prop['x-widget'];
    const rerender = RERENDER.has(name) ? ' data-rerender' : '';
    const req = required ? ' <span class="req">*</span>' : '';
    const current = value ?? prop.default;
    const row = (control, full = false, hint = '') => `<div class="form__row${full ? ' form__row--full' : ''}"><label class="lbl" for="${id}">${esc(label)}${req}</label>${control}${hint ? `<p class="hint">${esc(hint)}</p>` : ''}</div>`;
    const select = (options, type = 'str', allowEmpty = !required && prop.default == null) => `<select class="sel" id="${id}" data-bind="${path}" data-type="${type}"${rerender}>${allowEmpty ? `<option value="">${esc(tr(`emptyOptions.${name}`, null, A.none))}</option>` : ''}${options.map(([v, text]) => `<option value="${esc(v)}" ${String(current ?? '') === String(v) ? 'selected' : ''}>${esc(text)}</option>`).join('')}</select>`;

    if (prop.type === 'object') return name === 'where' || name === 'conditions' ? `<div class="form__row form__row--full"><span class="lbl">${esc(label)}</span>${conditionsHtml(value, path, ctx)}</div>` : '';
    if (prop.type === 'boolean') return `<div class="form__row form__row--full"><label class="form__check"><input type="checkbox" data-bind="${path}" data-type="bool" ${current === true ? 'checked' : ''} /> ${esc(label)}</label></div>`;
    if (widget === 'weekdays') return row(chipsHtml(path, [1, 2, 3, 4, 5, 6, 7].map(n => [n, weekdayName(n, 'short')]), value || [], 'int'), true);
    if (widget === 'statuses') return row(chipsHtml(path, entityStatuses(ctx.entity).map(s => [s, d.statusLabel(ctx.entity, s)]), value || [], 'str'), true);
    if (widget === 'tags') return row(`<input class="inp" id="${id}" data-bind="${path}" data-type="tags" value="${esc((value || []).join(', '))}" />`, true, A.tagsHint);
    if (widget === 'event') {
      const events = catalog().events.filter(e => e.available || e.type === value).map(e => [e.type, eventLabel(e.type)]);
      return row(select(events, 'str', true), true);
    }
    if (widget === 'status') return row(select(entityStatuses(ctx.entity).map(s => [s, d.statusLabel(ctx.entity, s)]), 'str', true));
    if (widget === 'entity') {
      const options = (prop.enum || catalog().entities.map(e => e.type)).map(t => [t, entityLabel(t)]);
      return row(select(options, 'str', !required));
    }
    if (widget === 'user') return row(select((data.people || []).map(p => [p.id, p.email]), 'str', true));
    if (widget === 'step') {
      const later = (ctx.laterSteps || []).map(s => [s.id, `${tr('flow.stepN', { n: s.n })} · ${stepShort(s)}`]);
      return row(select([['next', A.nextStep], ...later, ['end', A.endRun]], 'str', false));
    }
    if (widget === 'wa-template') return waTemplateHtml(path, ctx, id, label);
    if (widget === 'bookable-service') return row(select((data.bookable || []).map(s => [s.id, s.name]), 'str', true));
    if (widget === 'weekday') return row(select([1, 2, 3, 4, 5, 6, 7].map(n => [n, weekdayName(n)]), 'int', false));
    if (widget === 'time') return row(`<input class="inp inp--mono" id="${id}" type="time" data-bind="${path}" data-type="str" value="${esc(current || '')}" />`);
    if (prop.enum) return row(select(prop.enum.map(v => [v, enumLabel(name, v)])));
    if (prop.type === 'integer' || prop.type === 'number') {
      const unitHint = { days: 'day', hours: 'hour', minutes: 'minute', percent: 'percent' }[widget];
      const attrs = `${prop.minimum != null ? ` min="${prop.minimum}"` : ''}${prop.maximum != null ? ` max="${prop.maximum}"` : ''}${prop.type === 'number' ? ' step="any"' : ''}`;
      const control = `<input class="inp inp--mono" id="${id}" type="number" inputmode="decimal"${attrs} data-bind="${path}" data-type="${prop.type === 'integer' ? 'int' : 'num'}" value="${current ?? ''}" />`;
      return row(unitHint ? `<div class="inp-unit">${control}<span class="inp-unit__label">${esc(unitLabel(unitHint))}</span></div>` : control, false, prop.description && /^0 =/.test(prop.description) ? A.zeroSkips : '');
    }
    if (widget === 'template') {
      const long = LONG_TEXT.has(name);
      const control = long
        ? `<textarea class="txt" id="${id}" rows="${name === 'text' || name === 'message' ? 5 : 3}" data-bind="${path}" data-type="raw" maxlength="${prop.maxLength || 4000}">${esc(value || '')}</textarea>`
        : `<input class="inp" id="${id}" data-bind="${path}" data-type="raw" maxlength="${prop.maxLength || 400}" value="${esc(value || '')}" />`;
      return row(control + varToolsHtml(path, ctx), long);
    }
    if (widget === 'url') return row(`<input class="inp" id="${id}" type="url" inputmode="url" data-bind="${path}" data-type="str" placeholder="https://" value="${esc(current || '')}" />`, true);
    return row(`<input class="inp" id="${id}" data-bind="${path}" data-type="str" maxlength="${prop.maxLength || 400}" value="${esc(current || '')}" />`);
  }
  const unitLabel = name => {
    const parts = new Intl.NumberFormat(locale(), { style: 'unit', unit: name, unitDisplay: name === 'percent' ? 'short' : 'long' }).formatToParts(2);
    return parts.filter(p => p.type === 'unit' || p.type === 'percentSign').map(p => p.value).join('').trim();
  };

  function chipsHtml(path, options, selected, type) {
    const on = new Set((selected || []).map(String));
    return `<div class="chip-picks" role="group">${options.map(([v, text]) => `<button class="chip ${on.has(String(v)) ? 'is-on' : ''}" type="button" aria-pressed="${on.has(String(v))}" data-toggle="${path}" data-toggle-type="${type}" data-value="${esc(v)}">${esc(text)}</button>`).join('')}</div>`;
  }

  /** Suggested variables as chips, and every variable in a select, for a template text field. */
  function varToolsHtml(path, ctx) {
    const vars = [...variablesFor(ctx.subjectType), ...(ctx.stepOutputs || [])];
    if (!vars.length) return '';
    const steps = ctx.draft?.definition?.steps;
    const subject = ctx.subjectType ? (ctx.subjectType === 'instagram_comment' ? 'comment' : ctx.subjectType) : null;
    const preferred = ['firstName', 'name', 'number', 'total', 'dueDate', 'validUntil', 'serviceName', 'start', 'contactName', 'displayName', 'text'];
    const suggested = vars
      .filter(v => subject && (v.path.startsWith(`${subject}.`) || (subject !== 'client' && v.path.startsWith('client.'))))
      .filter(v => preferred.includes(v.path.split('.').pop()))
      .slice(0, 6)
      .concat(vars.filter(v => v.path === 'company.name'));
    const groups = {};
    vars.forEach(v => { const g = v.path.split('.')[0]; (groups[g] = groups[g] || []).push(v); });
    const select = `<select class="sel var-tools__select" data-insert-select="${path}" aria-label="${esc(A.insertVariable)}"><option value="">${esc(A.insertVariable)}</option>${Object.entries(groups).map(([g, list]) => `<optgroup label="${esc(g === 'steps' ? A.earlierSteps : entityLabel(g === 'comment' ? 'instagram_comment' : g))}">${list.map(v => `<option value="${esc(v.path)}">${esc(varLabel(v.path, steps))}</option>`).join('')}</optgroup>`).join('')}</select>`;
    return `<div class="var-tools">${suggested.map(v => `<button class="var-chip" type="button" data-insert="${path}" data-var="${esc(v.path)}">${esc(varLabel(v.path, steps))}</button>`).join('')}${select}</div>`;
  }

  function waTemplateHtml(path, ctx, id, label) {
    const base = path.replace(/\.template$/, '');
    const input = getAt(ctx.draft, base) || {};
    const templates = data.waTemplates || [];
    const chosen = templates.find(t => t.name === input.template && t.language === input.templateLanguage);
    const options = templates.map(t => `<option value="${esc(`${t.name}|${t.language}`)}" ${chosen === t ? 'selected' : ''}>${esc(`${t.name} · ${t.language}`)}</option>`).join('');
    const missing = input.template && !chosen ? `<option value="${esc(`${input.template}|${input.templateLanguage || ''}`)}" selected>${esc(input.template)}</option>` : '';
    const params = chosen ? chosen.params.map((p, i) => `<div class="form__row form__row--full"><label class="lbl" for="${id}-p${i}">${esc(tr('builder.templateParam', { name: p }))}</label>
      <input class="inp" id="${id}-p${i}" data-bind="${base}.templateParams.${i}" data-type="raw" value="${esc((input.templateParams || [])[i] || '')}" />${varToolsHtml(`${base}.templateParams.${i}`, ctx)}</div>`).join('') : '';
    return `<div class="form__row form__row--full"><label class="lbl" for="${id}">${esc(label)} <span class="opt">${esc(A.optional)}</span></label>
      <select class="sel" id="${id}" data-wa-template="${base}"><option value="">${esc(templates.length ? A.noTemplate : A.noTemplates)}</option>${missing}${options}</select>
      <p class="hint">${esc(A.templateHint)}</p>${chosen ? `<div class="wa-preview"><div class="bubble bubble--out bubble--ai"><div class="bubble__text">${esc(chosen.body)}</div></div></div>` : ''}</div>${params}`;
  }

  // ── conditions ────────────────────────────────────────────────────
  const NO_VALUE = new Set(['exists', 'not_exists', 'is_empty', 'not_empty']);
  function conditionFields(ctx) {
    return [...variablesFor(ctx.subjectType), ...(ctx.stepOutputs || [])];
  }
  function conditionsHtml(group, path, ctx) {
    const g = group || { match: 'ALL', conditions: [] };
    const rows = (g.conditions || []).map((c, i) => conditionRowHtml(c, `${path}.conditions.${i}`, ctx)).join('');
    const match = (g.conditions || []).length > 1
      ? `<select class="sel sel--inline" data-bind="${path}.match" data-type="str" aria-label="${esc(A.match)}">${['ALL', 'ANY'].map(m => `<option value="${m}" ${g.match === m ? 'selected' : ''}>${esc(tr(`enum.match.${m}`))}</option>`).join('')}</select>`
      : '';
    return `<div class="conds">${match}${rows}<button class="btn btn--sm btn--ghost" type="button" data-act="cond-add" data-path="${path}"><span class="btn__plus">+</span> ${esc(A.addCondition)}</button></div>`;
  }
  function conditionRowHtml(c, path, ctx) {
    const fields = conditionFields(ctx);
    const known = fields.find(f => f.path === c.field);
    const steps = ctx.draft?.definition?.steps;
    const options = [...(known || !c.field ? [] : [{ path: c.field, type: 'TEXT' }]), ...fields]
      .map(f => `<option value="${esc(f.path)}" ${f.path === c.field ? 'selected' : ''}>${esc(varLabel(f.path, steps))}</option>`).join('');
    const ops = catalog().operators.map(op => `<option value="${op}" ${op === c.op ? 'selected' : ''}>${esc(opLabel(op))}</option>`).join('');
    return `<div class="cond-row">
      <select class="sel" data-bind="${path}.field" data-type="str" data-rerender aria-label="${esc(A.field)}">${options}</select>
      <select class="sel" data-bind="${path}.op" data-type="str" data-rerender aria-label="${esc(A.operator)}">${ops}</select>
      ${NO_VALUE.has(c.op) ? '<span></span>' : conditionValueHtml(c, path, known)}
      <button class="iconbtn" type="button" data-act="cond-remove" data-path="${path}" aria-label="${esc(A.remove)}">×</button>
    </div>`;
  }
  function conditionValueHtml(c, path, field) {
    const value = Array.isArray(c.value) ? c.value.join(', ') : (c.value ?? '');
    const entity = /\.status$/.test(c.field) ? (c.field.startsWith('event.') ? null : c.field.split('.')[0]) : null;
    const statuses = entity ? entityStatuses(entity === 'comment' ? 'instagram_comment' : entity) : [];
    if (statuses.length && !['in', 'not_in'].includes(c.op)) {
      return `<select class="sel" data-bind="${path}.value" data-type="str" aria-label="${esc(A.value)}">${statuses.map(s => `<option value="${s}" ${s === value ? 'selected' : ''}>${esc(d.statusLabel(entity, s))}</option>`).join('')}</select>`;
    }
    if (field?.type === 'BOOLEAN') {
      return `<select class="sel" data-bind="${path}.value" data-type="bool-select" aria-label="${esc(A.value)}"><option value="true" ${c.value === true || c.value === 'true' ? 'selected' : ''}>${esc(A.yes)}</option><option value="false" ${c.value === false || c.value === 'false' ? 'selected' : ''}>${esc(A.no)}</option></select>`;
    }
    const numeric = ['days_since', 'days_until'].includes(c.op) || ['NUMBER', 'MONEY'].includes(field?.type);
    const type = ['in', 'not_in'].includes(c.op) ? 'list' : numeric ? 'num' : 'val';
    return `<input class="inp" ${numeric && type !== 'list' ? 'type="number" step="any"' : ''} data-bind="${path}.value" data-type="${type}" value="${esc(value)}" aria-label="${esc(A.value)}" placeholder="${esc(type === 'list' ? A.listPlaceholder : '')}" />`;
  }

  // ── binding ───────────────────────────────────────────────────────
  const isIndex = k => /^\d+$/.test(k);
  function getAt(obj, path) {
    return String(path).split('.').reduce((o, k) => (o == null ? undefined : o[isIndex(k) ? Number(k) : k]), obj);
  }
  function setAt(obj, path, value) {
    const parts = String(path).split('.');
    let cur = obj;
    for (let i = 0; i < parts.length - 1; i += 1) {
      const key = isIndex(parts[i]) ? Number(parts[i]) : parts[i];
      if (cur[key] == null || typeof cur[key] !== 'object') cur[key] = isIndex(parts[i + 1]) ? [] : {};
      cur = cur[key];
    }
    const last = isIndex(parts[parts.length - 1]) ? Number(parts[parts.length - 1]) : parts[parts.length - 1];
    if (value === undefined) {
      if (Array.isArray(cur)) cur[last] = ''; else delete cur[last];
    } else cur[last] = value;
  }
  function readValue(el) {
    const raw = el.type === 'checkbox' ? el.checked : el.value;
    switch (el.dataset.type) {
      case 'bool': return !!raw;
      case 'bool-select': return raw === 'true';
      case 'int': return raw === '' ? undefined : parseInt(raw, 10);
      case 'num': return raw === '' ? undefined : Number(String(raw).replace(',', '.'));
      case 'tags': { const list = String(raw).split(',').map(s => s.trim()).filter(Boolean); return list.length ? list : undefined; }
      case 'list': { const list = String(raw).split(',').map(s => s.trim()).filter(Boolean); return list.length ? list : undefined; }
      case 'val': {
        const text = String(raw).trim();
        if (text === '') return '';
        if (/^-?\d+([.,]\d+)?$/.test(text)) return Number(text.replace(',', '.'));
        return text;
      }
      case 'raw': return raw === '' ? undefined : raw;
      default: return String(raw).trim() === '' ? undefined : String(raw).trim();
    }
  }
  /** Wires value binding into [target] and structural actions; [rerender] redraws after a change of shape. */
  function bindInputs(host, target, rerender) {
    const onChange = e => {
      const el = e.target.closest('[data-bind]');
      if (el) {
        setAt(target, el.dataset.bind, readValue(el));
        if (e.type === 'change' && el.hasAttribute('data-rerender')) rerender();
        return;
      }
      const wa = e.target.closest('[data-wa-template]');
      if (wa && e.type === 'change') {
        const base = wa.dataset.waTemplate;
        const [name, language] = wa.value ? wa.value.split('|') : [undefined, undefined];
        setAt(target, `${base}.template`, name || undefined);
        setAt(target, `${base}.templateLanguage`, language || undefined);
        setAt(target, `${base}.templateParams`, undefined);
        rerender();
        return;
      }
      const insert = e.target.closest('[data-insert-select]');
      if (insert && e.type === 'change' && insert.value) {
        insertVariable(host, target, insert.dataset.insertSelect, insert.value);
        insert.value = '';
      }
    };
    host.addEventListener('input', onChange);
    host.addEventListener('change', onChange);
    host.addEventListener('click', e => {
      const chip = e.target.closest('[data-insert]');
      if (chip) return insertVariable(host, target, chip.dataset.insert, chip.dataset.var);
      const toggle = e.target.closest('[data-toggle]');
      if (toggle) {
        const list = (getAt(target, toggle.dataset.toggle) || []).map(String);
        const value = toggle.dataset.value;
        const next = list.includes(value) ? list.filter(v => v !== value) : [...list, value];
        const typed = toggle.dataset.toggleType === 'int' ? next.map(Number).sort((a, b) => a - b) : next;
        setAt(target, toggle.dataset.toggle, typed.length ? typed : undefined);
        toggle.classList.toggle('is-on', next.includes(value));
        toggle.setAttribute('aria-pressed', String(next.includes(value)));
        return;
      }
      const act = e.target.closest('[data-act]');
      if (!act) return;
      const path = act.dataset.path;
      if (act.dataset.act === 'cond-add') {
        const group = getAt(target, path) || { match: 'ALL', conditions: [] };
        group.conditions = [...(group.conditions || []), { field: act.dataset.field || firstField(host), op: 'eq', value: '' }];
        setAt(target, path, group);
        rerender();
      } else if (act.dataset.act === 'cond-remove') {
        const groupPath = path.replace(/\.conditions\.\d+$/, '');
        const index = Number(path.split('.').pop());
        const group = getAt(target, groupPath);
        group.conditions.splice(index, 1);
        if (!group.conditions.length && !/\.input\./.test(groupPath) && !/config\.where$/.test(groupPath)) setAt(target, groupPath, undefined);
        rerender();
      }
    });
  }
  const firstField = host => host.dataset.firstField || 'company.name';
  /** A sensible first field for a new condition: the record's own name or number. */
  function defaultField(subjectType) {
    const prefix = subjectType === 'instagram_comment' ? 'comment' : subjectType;
    const vars = variablesFor(subjectType);
    return (vars.find(v => prefix && v.path.startsWith(`${prefix}.`) && v.type !== 'ID') || vars.find(v => v.path === 'company.name') || { path: 'company.name' }).path;
  }
  function insertVariable(host, target, path, variable) {
    const el = host.querySelector(`[data-bind="${CSS.escape(path)}"]`);
    if (!el) return;
    const token = `{{${variable}}}`;
    const start = el.selectionStart ?? el.value.length;
    const end = el.selectionEnd ?? el.value.length;
    el.value = el.value.slice(0, start) + token + el.value.slice(end);
    el.focus();
    el.setSelectionRange(start + token.length, start + token.length);
    setAt(target, path, el.value);
  }

  // ── builder ───────────────────────────────────────────────────────
  async function openBuilder(agent, kept = null) {
    await Promise.all([ensureCatalog(), ensurePeople(), ensureWaTemplates(), ensureBookable()]);
    const draft = kept?.draft || {
      name: agent.name,
      description: agent.description || '',
      definition: normalizeDefinition(clone(agent.definition || {})),
    };
    let problems = kept?.problems || agent.problems || [];
    const here = { key: `builder:${agent.id}`, label: A.editTitle, open: () => openBuilder(agent, { draft, problems }) };
    const body = document.createElement('div');
    body.className = 'builder';
    const draw = () => {
      const scroller = $('#drawer-body');
      const top = scroller ? scroller.scrollTop : 0;
      body.dataset.firstField = defaultField(subjectOf(draft.definition));
      body.innerHTML = builderHtml(draft, problems);
      d.bindDrawerClose(body);
      if (scroller) scroller.scrollTop = top;
    };
    // Registered before bindInputs so it runs before a redraw replaces the control.
    body.addEventListener('change', e => shapeChange(e, draft.definition, draw));
    bindInputs(body, draft, draw);
    body.addEventListener('change', e => {
      const pick = e.target.closest('[data-build-pick]');
      if (!pick || !pick.value) return;
      builderPick(draft, pick.dataset.buildPick, pick.value, pick.dataset.index);
      draw();
    });
    body.addEventListener('click', async e => {
      const btn = e.target.closest('[data-build]');
      if (btn) {
        builderAction(draft, btn.dataset.build, btn.dataset.index);
        return draw();
      }
      if (e.target.closest('[data-builder-test]')) return d.openFrom(here, () => runTest(agent, cleanDefinition(draft.definition)));
      const save = e.target.closest('[data-builder-save]');
      if (!save) return;
      save.disabled = true;
      save.textContent = A.saving;
      try {
        const saved = await d.api(`/app/api/agents/${encodeURIComponent(agent.id)}`, {
          method: 'PUT',
          body: JSON.stringify({ name: draft.name.trim() || agent.name, description: draft.description.trim(), definition: cleanDefinition(draft.definition) }),
        });
        problems = saved.problems || [];
        d.toast(problems.length ? A.savedWithProblems : A.saved);
        refresh();
        if (!problems.length) return d.closeDrawer();
        draw();
      } catch (err) {
        d.toast(err?.status === 422 ? A.activeInvalid : A.saveFailed);
        save.disabled = false;
        save.textContent = A.save;
      }
    });
    draw();
    d.openDrawer(A.editTitle, body, true, { eyebrow: agent.name });
  }

  /** Selects whose value is a shape (quiet hours, an exit rule's status), and fields whose change voids others. */
  function shapeChange(e, def, draw) {
    const el = e.target;
    if (el.matches('[data-policy-quiet]')) {
      if (el.value === 'company') delete def.policy.quietHours;
      if (el.value === 'off') def.policy.quietHours = { start: '00:00', end: '00:00' };
      if (el.value === 'custom') def.policy.quietHours = { start: '21:00', end: '08:00' };
      return draw();
    }
    if (el.matches('[data-policy-business]')) {
      if (el.value === '') delete def.policy.businessDaysOnly; else def.policy.businessDaysOnly = el.value === 'true';
      return;
    }
    if (el.matches('[data-exit-status]')) {
      const rule = def.exitRules[Number(el.dataset.exitStatus)];
      if (el.value) rule.conditions = { match: 'ALL', conditions: [{ field: 'event.to', op: 'eq', value: el.value }] };
      else delete rule.conditions;
      return;
    }
    const bind = el.dataset?.bind || '';
    let m = bind.match(/^definition\.exitRules\.(\d+)\.event$/);
    if (m) delete def.exitRules[Number(m[1])].conditions;
    m = bind.match(/^definition\.triggers\.(\d+)\.config\.(event|entity|forEach)$/);
    if (m) {
      const config = def.triggers[Number(m[1])].config;
      const voided = { event: ['toStatus', 'channel', 'keywords'], entity: ['statuses', 'offsetHours', 'offsetDays'], forEach: ['where'] }[m[2]];
      voided.forEach(key => delete config[key]);
    }
  }

  function normalizeDefinition(def) {
    def.triggers = def.triggers || [];
    def.steps = def.steps || [];
    def.exitRules = def.exitRules || [];
    def.policy = def.policy || {};
    def.voice = def.voice || {};
    return def;
  }
  /** Drops what the builder leaves behind: empty strings, empty lists, groups without conditions. */
  function cleanDefinition(def) {
    const prune = value => {
      // Positions matter (a WhatsApp template's variables), so emptied items stay as ''.
      if (Array.isArray(value)) return value.map(v => { const next = prune(v); return next === undefined ? '' : next; });
      if (value && typeof value === 'object') {
        const out = {};
        Object.entries(value).forEach(([k, v]) => {
          const next = prune(v);
          if (next === undefined || next === '' || (Array.isArray(next) && !next.length && k !== 'conditions')) return;
          out[k] = next;
        });
        return out;
      }
      return value;
    };
    const out = prune(clone(def));
    if (out.conditions && !(out.conditions.conditions || []).length) delete out.conditions;
    (out.steps || []).forEach(s => { if (s.guard && !(s.guard.conditions || []).length) delete s.guard; s.input = s.input || {}; });
    (out.triggers || []).forEach(t => { t.config = t.config || {}; });
    return out;
  }

  const nextId = (items, prefix) => `${prefix}${Math.max(0, ...items.map(i => Number(String(i.id).replace(/\D/g, '')) || 0)) + 1}`;
  function builderAction(draft, act, index) {
    const def = draft.definition;
    const i = Number(index);
    if (act === 'trigger-remove') def.triggers.splice(i, 1);
    if (act === 'step-remove') def.steps.splice(i, 1);
    if (act === 'step-up' && i > 0) [def.steps[i - 1], def.steps[i]] = [def.steps[i], def.steps[i - 1]];
    if (act === 'step-down' && i < def.steps.length - 1) [def.steps[i + 1], def.steps[i]] = [def.steps[i], def.steps[i + 1]];
    if (act === 'guard-add') def.steps[i].guard = { match: 'ALL', conditions: [{ field: defaultField(subjectOf(def)), op: 'eq', value: '' }] };
    if (act === 'exit-remove') def.exitRules.splice(i, 1);
  }
  function builderPick(draft, kind, value, index) {
    const def = draft.definition;
    if (kind === 'trigger-add' && def.triggers.length < MAX_TRIGGERS) def.triggers.push({ id: nextId(def.triggers, 't'), type: value, config: {} });
    if (kind === 'trigger-type') def.triggers[Number(index)] = { ...def.triggers[Number(index)], type: value, config: {} };
    if (kind === 'step-add' && def.steps.length < MAX_STEPS) def.steps.push({ id: nextId(def.steps, 's'), action: value, input: value === 'flow.wait' ? { days: 1, hours: 0, minutes: 0 } : {} });
    if (kind === 'step-action') def.steps[Number(index)] = { ...def.steps[Number(index)], action: value, input: {} };
    if (kind === 'exit-add') def.exitRules.push({ event: value });
  }

  function builderHtml(draft, problems) {
    const def = draft.definition;
    const subjectType = subjectOf(def);
    const problemsHtml = problems.length
      ? `<div class="notice notice--warn"><div class="notice__text"><strong>${esc(A.problemsTitle)}</strong><ul class="notice__list">${problems.map(p => `<li>${esc(problemText(p, def))}</li>`).join('')}</ul></div></div>`
      : '';
    return `${problemsHtml}
      <div class="form__grid">
        <div class="form__row"><label class="lbl" for="ag-name">${esc(A.nameLabel)} <span class="req">*</span></label><input class="inp" id="ag-name" maxlength="80" data-bind="name" data-type="raw" value="${esc(draft.name)}" /></div>
        <div class="form__row"><label class="lbl" for="ag-desc">${esc(A.descriptionLabel)} <span class="opt">${esc(A.optional)}</span></label><input class="inp" id="ag-desc" maxlength="500" data-bind="description" data-type="raw" value="${esc(draft.description)}" /></div>
      </div>
      ${builderSection(tr('builder.when'), triggersHtml(def), 'when')}
      ${builderSection(tr('builder.onlyIf'), `<p class="hint">${esc(tr('builder.onlyIfHint'))}</p>${conditionsHtml(def.conditions, 'definition.conditions', { subjectType })}`, 'only-if')}
      ${builderSection(tr('builder.steps'), stepsHtml(def, subjectType), 'steps')}
      ${builderSection(tr('builder.exitRules'), exitRulesHtml(def), 'exit')}
      ${builderSection(tr('builder.policy'), policyHtml(def), 'policy')}
      ${builderSection(tr('builder.voice'), voiceHtml(def), 'voice')}
      <div class="drawer__foot">
        <button class="btn btn--ghost" type="button" data-close>${esc(d.STR.cancel)}</button>
        <button class="btn btn--ghost" type="button" data-builder-test>${esc(A.testDraft)}</button>
        <button class="btn btn--accent" type="button" data-builder-save>${esc(A.save)}</button>
      </div>`;
  }
  function builderSection(title, content, id) {
    return `<section class="panel builder__section" data-section="${id}"><header class="panel__head"><h2 class="panel__title">${esc(title)}</h2></header><div class="panel__body">${content}</div></section>`;
  }

  function triggersHtml(def) {
    const types = catalog().triggers;
    const cards = def.triggers.map((t, i) => {
      const spec = triggerSpec(t.type);
      const typeOptions = types.map(tt => `<option value="${esc(tt.key)}" ${tt.key === t.type ? 'selected' : ''} ${tt.available || tt.key === t.type ? '' : 'disabled'}>${esc(triggerLabel(tt.key))}</option>`).join('');
      const ctx = { kind: 'trigger', key: t.type, entity: triggerEntity(t), draft: { definition: def }, subjectType: triggerSubject(t) };
      return `<div class="flow__card">
        <div class="flow__card-head"><span class="flow__index" aria-hidden="true">${ICON_BOLT}</span>
          <select class="sel" data-build-pick="trigger-type" data-index="${i}" aria-label="${esc(A.triggerType)}">${typeOptions}</select>
          <span class="flow__card-summary muted">${esc(triggerSummary(t))}</span>
          ${def.triggers.length > 1 ? `<button class="iconbtn" type="button" data-build="trigger-remove" data-index="${i}" aria-label="${esc(A.remove)}">×</button>` : ''}
        </div>
        <div class="form__grid">${spec ? schemaFields(spec.configSchema, t.config, `definition.triggers.${i}.config`, ctx) : ''}</div>
      </div>`;
    }).join('');
    const add = def.triggers.length < MAX_TRIGGERS
      ? `<select class="sel sel--inline" data-build-pick="trigger-add" aria-label="${esc(def.triggers.length ? A.addTrigger : A.chooseTrigger)}"><option value="">${esc(def.triggers.length ? A.addTrigger : A.chooseTrigger)}</option>${types.filter(t => t.available).map(t => `<option value="${esc(t.key)}">${esc(triggerLabel(t.key))}</option>`).join('')}</select>`
      : '';
    return `<div class="flow flow--edit">${cards}</div>${add}`;
  }
  function triggerEntity(t) {
    if (t.type === 'event') return eventSpec(t.config?.event)?.subjectType || null;
    return t.config?.entity || t.config?.forEach || null;
  }

  function stepsHtml(def, subjectType) {
    const actions = catalog().actions;
    const byCategory = {};
    actions.forEach(a => { (byCategory[a.category] = byCategory[a.category] || []).push(a); });
    const actionOptions = (selected, allowAll) => Object.entries(byCategory).map(([cat, list]) => `<optgroup label="${esc(tr(`actionCategories.${cat}`, null, cat))}">${list.map(a => `<option value="${esc(a.key)}" ${a.key === selected ? 'selected' : ''} ${a.available || a.key === selected || allowAll ? '' : 'disabled'}>${esc(actionLabel(a.key))}${a.available ? '' : ` — ${esc(reasonText(a.reason))}`}</option>`).join('')}</optgroup>`).join('');
    const cards = def.steps.map((s, i) => {
      const spec = actionSpec(s.action);
      const earlier = def.steps.slice(0, i);
      const stepOutputs = earlier.flatMap(e => Object.keys(actionSpec(e.action)?.outputSchema?.properties || {}).map(key => ({ path: `steps.${e.id}.output.${key}`, type: 'TEXT' })));
      const ctx = {
        kind: 'step', key: s.action, subjectType, stepOutputs, draft: { definition: def },
        laterSteps: def.steps.slice(i + 1).map((l, j) => ({ ...l, n: i + j + 2 })),
      };
      const effect = spec?.sideEffect || 'NONE';
      const autonomy = effect === 'NONE' ? '' : `<div class="form__row"><label class="lbl" for="ag-auto-${i}">${esc(A.autonomyLabel)}</label>
        <select class="sel" id="ag-auto-${i}" data-bind="definition.steps.${i}.autonomy" data-type="str"><option value="">${esc(tr('builder.autonomyInherit', { value: tr(`autonomy.${def.policy.autonomy || 'APPROVE'}`) }))}</option>${['APPROVE', 'AUTO', 'DRAFT'].map(v => `<option value="${v}" ${s.autonomy === v ? 'selected' : ''}>${esc(tr(`autonomy.${v}`))}</option>`).join('')}</select></div>`;
      const onError = `<div class="form__row"><label class="lbl" for="ag-err-${i}">${esc(A.onErrorLabel)}</label>
        <select class="sel" id="ag-err-${i}" data-bind="definition.steps.${i}.onError" data-type="str">${['RETRY_THEN_FAIL', 'CONTINUE', 'STOP'].map(v => `<option value="${v}" ${(s.onError || 'RETRY_THEN_FAIL') === v ? 'selected' : ''}>${esc(tr(`enum.onError.${v}`))}</option>`).join('')}</select></div>`;
      const guard = s.guard?.conditions?.length
        ? `<div class="form__row form__row--full"><span class="lbl">${esc(A.guardLabel)}</span>${conditionsHtml(s.guard, `definition.steps.${i}.guard`, ctx)}</div>`
        : `<div class="form__row form__row--full"><button class="btn btn--sm btn--ghost" type="button" data-build="guard-add" data-index="${i}"><span class="btn__plus">+</span> ${esc(A.addGuard)}</button></div>`;
      return `${i ? '<div class="flow__connector" aria-hidden="true"></div>' : ''}<div class="flow__card" id="ag-step-${i}">
        <div class="flow__card-head"><span class="flow__index">${i + 1}</span>
          <select class="sel" data-build-pick="step-action" data-index="${i}" aria-label="${esc(A.stepAction)}">${actionOptions(s.action)}</select>
          <span class="flow__card-tools">
            <button class="iconbtn" type="button" data-build="step-up" data-index="${i}" ${i ? '' : 'disabled'} aria-label="${esc(A.moveUp)}">↑</button>
            <button class="iconbtn" type="button" data-build="step-down" data-index="${i}" ${i < def.steps.length - 1 ? '' : 'disabled'} aria-label="${esc(A.moveDown)}">↓</button>
            <button class="iconbtn" type="button" data-build="step-remove" data-index="${i}" aria-label="${esc(A.remove)}">×</button>
          </span>
        </div>
        <div class="form__grid">
          ${spec ? schemaFields(spec.inputSchema, s.input, `definition.steps.${i}.input`, ctx) : ''}
          ${autonomy}${onError}${guard}
        </div>
      </div>`;
    }).join('');
    const add = def.steps.length < MAX_STEPS
      ? `<select class="sel sel--inline" data-build-pick="step-add" aria-label="${esc(A.addStep)}"><option value="">${esc(A.addStep)}</option>${actionOptions('', false)}</select>`
      : '';
    return `<div class="flow flow--edit">${cards || `<p class="hint">${esc(A.noSteps)}</p>`}</div>${add}`;
  }

  function exitRulesHtml(def) {
    const events = catalog().events.filter(e => e.available);
    const rows = def.exitRules.map((r, i) => {
      const status = (r.conditions?.conditions || []).find(c => c.field === 'event.to')?.value || '';
      const entity = eventSpec(r.event)?.subjectType;
      const statuses = STATUS_EVENTS.has(r.event) ? entityStatuses(entity) : [];
      return `<div class="cond-row cond-row--exit">
        <select class="sel" data-bind="definition.exitRules.${i}.event" data-type="str" data-rerender aria-label="${esc(A.exitEvent)}">${events.map(e => `<option value="${esc(e.type)}" ${e.type === r.event ? 'selected' : ''}>${esc(eventLabel(e.type))}</option>`).join('')}</select>
        ${statuses.length ? `<select class="sel" data-exit-status="${i}" aria-label="${esc(A.exitStatus)}"><option value="">${esc(A.anyStatus)}</option>${statuses.map(s => `<option value="${s}" ${s === status ? 'selected' : ''}>${esc(d.statusLabel(entity, s))}</option>`).join('')}</select>` : '<span></span>'}
        <span></span>
        <button class="iconbtn" type="button" data-build="exit-remove" data-index="${i}" aria-label="${esc(A.remove)}">×</button>
      </div>`;
    }).join('');
    return `<p class="hint">${esc(tr('builder.exitHint'))}</p><div class="conds">${rows}</div>
      <select class="sel sel--inline" data-build-pick="exit-add" aria-label="${esc(A.addExit)}"><option value="">${esc(A.addExit)}</option>${events.map(e => `<option value="${esc(e.type)}">${esc(eventLabel(e.type))}</option>`).join('')}</select>`;
  }

  function policyHtml(def) {
    const p = def.policy;
    const quiet = !p.quietHours ? 'company' : p.quietHours.start === p.quietHours.end ? 'off' : 'custom';
    const business = p.businessDaysOnly == null ? '' : String(p.businessDaysOnly);
    return `<div class="form__grid">
      <div class="form__row"><label class="lbl" for="ag-p-auto">${esc(A.policyAutonomy)}</label><select class="sel" id="ag-p-auto" data-bind="definition.policy.autonomy" data-type="str" data-rerender>${['APPROVE', 'AUTO', 'DRAFT'].map(v => `<option value="${v}" ${(p.autonomy || 'APPROVE') === v ? 'selected' : ''}>${esc(tr(`autonomy.${v}`))}</option>`).join('')}</select></div>
      <div class="form__row"><label class="lbl" for="ag-p-appr">${esc(A.approversLabel)}</label><select class="sel" id="ag-p-appr" data-bind="definition.policy.approvers" data-type="str">${['ANY_MEMBER', 'ADMINS'].map(v => `<option value="${v}" ${(p.approvers || 'ANY_MEMBER') === v ? 'selected' : ''}>${esc(tr(`enum.approvers.${v}`))}</option>`).join('')}</select></div>
      <div class="form__row"><label class="lbl" for="ag-p-quiet">${esc(A.quietLabel)}</label><select class="sel" id="ag-p-quiet" data-policy-quiet>${['company', 'custom', 'off'].map(v => `<option value="${v}" ${quiet === v ? 'selected' : ''}>${esc(tr(`builder.quiet_${v}`))}</option>`).join('')}</select></div>
      ${quiet === 'custom' ? `<div class="form__row"><span class="lbl">${esc(A.quietBetween)}</span><div class="inp-range"><input class="inp inp--mono" type="time" data-bind="definition.policy.quietHours.start" data-type="str" value="${esc(p.quietHours.start)}" aria-label="${esc(A.from)}" /><span class="muted">–</span><input class="inp inp--mono" type="time" data-bind="definition.policy.quietHours.end" data-type="str" value="${esc(p.quietHours.end)}" aria-label="${esc(A.to)}" /></div></div>` : ''}
      <div class="form__row"><label class="lbl" for="ag-p-bd">${esc(A.businessDaysLabel)}</label><select class="sel" id="ag-p-bd" data-policy-business><option value="" ${business === '' ? 'selected' : ''}>${esc(tr('builder.business_default'))}</option><option value="true" ${business === 'true' ? 'selected' : ''}>${esc(tr('builder.business_true'))}</option><option value="false" ${business === 'false' ? 'selected' : ''}>${esc(tr('builder.business_false'))}</option></select></div>
      <div class="form__row"><label class="lbl" for="ag-p-max">${esc(A.maxRunsLabel)}</label><input class="inp inp--mono" id="ag-p-max" type="number" min="1" max="10000" data-bind="definition.policy.maxRunsPerDay" data-type="int" value="${p.maxRunsPerDay ?? 200}" /></div>
      <div class="form__row"><label class="lbl" for="ag-p-cool">${esc(A.cooldownLabel)}</label><input class="inp inp--mono" id="ag-p-cool" type="number" min="0" max="8760" data-bind="definition.policy.cooldownHours" data-type="int" value="${p.cooldownHours ?? 0}" /></div>
      <div class="form__row form__row--full"><label class="form__check"><input type="checkbox" data-bind="definition.policy.notifyOnFailure" data-type="bool" ${p.notifyOnFailure === false ? '' : 'checked'} /> ${esc(A.notifyOnFailure)}</label></div>
    </div>`;
  }
  function voiceHtml(def) {
    const v = def.voice;
    const langs = [['', A.languageAuto], ...I18N.SUPPORTED.map(l => [l, I18N.LANG_NAMES[l] || l])];
    return `<p class="hint">${esc(A.voiceHint)}</p><div class="form__grid">
      <div class="form__row"><label class="lbl" for="ag-v-lang">${esc(A.languageLabel)}</label><select class="sel" id="ag-v-lang" data-bind="definition.voice.language" data-type="str">${langs.map(([code, name]) => `<option value="${esc(code)}" ${(v.language || '') === code ? 'selected' : ''}>${esc(name)}</option>`).join('')}</select></div>
      <div class="form__row"><label class="lbl" for="ag-v-tone">${esc(A.toneLabel)}</label><select class="sel" id="ag-v-tone" data-bind="definition.voice.tone" data-type="str">${['friendly', 'formal', 'brief'].map(t => `<option value="${t}" ${(v.tone || 'friendly') === t ? 'selected' : ''}>${esc(tr(`enum.tone.${t}`))}</option>`).join('')}</select></div>
      <div class="form__row form__row--full"><label class="lbl" for="ag-v-sig">${esc(A.signatureLabel)} <span class="opt">${esc(A.optional)}</span></label><input class="inp" id="ag-v-sig" maxlength="200" data-bind="definition.voice.signature" data-type="raw" value="${esc(v.signature || '')}" /></div>
      <div class="form__row form__row--full"><label class="lbl" for="ag-v-ins">${esc(A.instructionsLabel)} <span class="opt">${esc(A.optional)}</span></label><textarea class="txt" id="ag-v-ins" rows="3" maxlength="2000" data-bind="definition.voice.instructions" data-type="raw">${esc(v.instructions || '')}</textarea></div>
      <div class="form__row form__row--full"><label class="form__check"><input type="checkbox" data-bind="definition.voice.usePersona" data-type="bool" ${v.usePersona ? 'checked' : ''} /> ${esc(A.usePersona)}</label></div>
    </div>`;
  }

  // ── inbox: approvals and tasks ────────────────────────────────────
  function renderInbox(pane) {
    const o = data.overview || {};
    const chips = [['approvals', o.pendingApprovals || data.approvals.length], ['tasks', o.openTasks || 0], ['mine', null]].map(([id, n]) => `<button class="chip ${ui.inbox === id ? 'is-on' : ''}" type="button" data-inbox="${id}">${esc(tr(`inbox.${id}`))}${n ? `<span class="chip__count">${n}</span>` : ''}</button>`).join('');
    const approvals = ui.inbox === 'approvals';
    const q = (d.state.search || '').toLowerCase();
    const items = approvals
      ? data.approvals.filter(a => !q || `${a.agentName} ${a.subjectLabel || ''} ${a.preview?.body || ''}`.toLowerCase().includes(q)).map(approvalRow)
      : data.tasks.filter(t => !q || `${t.title} ${t.subjectLabel || ''}`.toLowerCase().includes(q)).map(taskRow);
    const empty = approvals
      ? `<div class="empty"><p class="empty__title">${esc(tr('inbox.emptyApprovals'))}</p><p class="empty__desc">${esc(tr('inbox.emptyApprovalsDesc'))}</p></div>`
      : `<div class="empty"><p class="empty__title">${esc(tr('inbox.emptyTasks'))}</p><p class="empty__desc">${esc(tr('inbox.emptyTasksDesc'))}</p></div>`;
    pane.innerHTML = `<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(tr(`inbox.${ui.inbox}`))} <span class="tag">${items.length}</span></h2><div class="panel__tools">${chips}${approvals ? '' : `<button class="btn btn--sm" type="button" data-task-new><span class="btn__plus">+</span> ${esc(tr('inbox.newTask'))}</button>`}</div></header>
      ${items.length ? `<ul class="worklist">${items.join('')}</ul>` : empty}</section>`;
    $$('[data-inbox]', pane).forEach(b => b.addEventListener('click', async () => {
      ui.inbox = b.dataset.inbox;
      await refresh();
    }));
    $('[data-task-new]', pane)?.addEventListener('click', () => openTask(null));
    $$('[data-approval]', pane).forEach(b => b.addEventListener('click', () => openApproval(data.approvals.find(a => a.id === b.dataset.approval))));
    $$('[data-task]', pane).forEach(b => b.addEventListener('click', () => openTask(data.tasks.find(t => t.id === b.dataset.task))));
  }
  function approvalRow(a) {
    const kind = a.preview?.kind || 'generic';
    const excerpt = [a.preview?.body, a.preview?.subject].find(t => t && t !== a.subjectLabel)?.replace(/\s+/g, ' ').slice(0, 140) || '';
    const expiresSoon = new Date(a.expiresAt).getTime() - Date.now() < 86400000;
    return `<li><button class="worklist__item" type="button" data-approval="${esc(a.id)}" data-tone="${kind === 'message' || kind === 'email' ? 'warn' : 'accent'}">
      <span class="worklist__dot" aria-hidden="true"></span>
      <span class="worklist__main"><span class="worklist__title">${esc(`${a.agentName} · ${actionLabel(a.action)}`)}</span><span class="worklist__detail">${esc([a.subjectLabel, excerpt].filter(Boolean).join(' — '))}</span></span>
      <span class="worklist__side"><span class="worklist__meta">${pill(expiresSoon ? 'warn' : '', tr('inbox.expires', { when: d.relTime(a.expiresAt) }))}</span><span class="worklist__when">${esc(d.relTime(a.createdAt))}</span></span>
    </button></li>`;
  }
  function taskRow(t) {
    const overdue = t.status === 'OPEN' && t.dueAt && new Date(t.dueAt).getTime() < Date.now();
    return `<li><button class="worklist__item" type="button" data-task="${esc(t.id)}" data-tone="${t.status !== 'OPEN' ? 'muted' : overdue ? 'bad' : 'info'}">
      <span class="worklist__dot" aria-hidden="true"></span>
      <span class="worklist__main"><span class="worklist__title">${esc(t.title)}</span><span class="worklist__detail">${esc([t.subjectLabel, t.agentName ? tr('inbox.fromAgent', { agent: t.agentName }) : ''].filter(Boolean).join(' · '))}</span></span>
      <span class="worklist__side">${t.assigneeName ? `<span class="worklist__meta">${esc(t.assigneeName)}</span>` : ''}${t.dueAt ? `<span class="worklist__when">${esc(d.relTime(t.dueAt))}</span>` : ''}</span>
    </button></li>`;
  }

  async function openApproval(approval) {
    if (!approval) return;
    if (approval.action === 'booking.create') await ensureBookable();
    const p = approval.preview || {};
    const editable = new Set(p.editable || []);
    const decided = approval.status !== 'PENDING';
    const can = approval.canDecide && !decided;
    const here = { key: `approval:${approval.id}`, label: approval.agentName, open: () => openApproval(approval) };
    const form = document.createElement('form');
    form.className = 'form';
    const editField = (key, rows = 5) => {
      const value = approval.input?.[key] ?? '';
      if (!editable.has(key) || !can) return `<div class="wa-preview"><div class="bubble bubble--out bubble--ai"><div class="bubble__text">${esc(value)}</div></div></div>`;
      return `<textarea class="txt" rows="${rows}" data-edit="${key}" aria-label="${esc(fieldLabel(key))}">${esc(value)}</textarea>`;
    };
    const bodyKey = ['text', 'message', 'body'].find(k => approval.input && k in approval.input);
    let preview = '';
    if (p.kind === 'message' || p.kind === 'notify' || p.kind === 'email') {
      preview = `${p.subject ? `<div class="form__row"><span class="lbl">${esc(fieldLabel('subject'))}</span>${editable.has('subject') && can ? `<input class="inp" data-edit="subject" value="${esc(approval.input?.subject ?? p.subject)}" />` : `<div>${esc(p.subject)}</div>`}</div>` : ''}
        <div class="form__row"><span class="lbl">${esc(tr(`previewKinds.${p.kind}`))}</span>${bodyKey ? editField(bodyKey) : `<div class="wa-preview"><div class="bubble bubble--out bubble--ai"><div class="bubble__text">${esc(p.body || '')}</div></div></div>`}</div>`;
    } else if (p.kind === 'task') {
      preview = `<div class="form__row"><span class="lbl">${esc(fieldLabel('title'))}</span>${editable.has('title') && can ? `<input class="inp" data-edit="title" value="${esc(approval.input?.title ?? p.subject ?? '')}" />` : `<div>${esc(p.subject || '')}</div>`}</div>
        ${p.body || editable.has('detail') ? `<div class="form__row"><span class="lbl">${esc(fieldLabel('detail'))}</span>${editField('detail', 3)}</div>` : ''}`;
    } else if (p.body) {
      preview = `<div class="wa-preview"><div class="bubble bubble--out bubble--ai"><div class="bubble__text">${esc(p.body)}</div></div></div>`;
    }
    const fields = Object.entries(p.fields || {});
    form.innerHTML = `<div class="detail__head"><div class="detail__client">${esc(actionLabel(approval.action))}</div><div class="detail__amount">${pill(decided ? (approval.status === 'APPROVED' ? 'ok' : '') : 'warn', tr(`approvalStatus.${approval.status}`, null, approval.status))}</div></div>
      ${d.detailMeta([
        approval.subjectLabel ? { label: tr('run.record'), value: approval.subjectLabel } : null,
        p.channel ? { label: tr('inbox.channel'), value: tr(`channels.${p.channel}`, null, p.channel) } : null,
        (p.recipients || []).length ? { label: tr('inbox.recipients'), value: p.recipients.map(r => tr(`enum.audience.${r}`, null, personName(r) || r)).join(', ') } : null,
        { label: tr('inbox.requested'), value: d.fmtDate(approval.createdAt) },
        decided ? { label: tr('inbox.decided'), value: [approval.decidedByName, d.fmtDate(approval.decidedAt)].filter(Boolean).join(' · ') } : { label: tr('inbox.expiresLabel'), value: d.fmtDate(approval.expiresAt) },
      ])}
      ${(p.warnings || []).map(w => `<p class="hint hint--warn">${esc(tr(`warnings.${w}`, null, w))}</p>`).join('')}
      ${preview}
      ${(p.attachments || []).length ? `<p class="hint">${esc(tr('inbox.attachments', { files: p.attachments.join(', ') }))}</p>` : ''}
      ${fields.length ? previewFactsHtml(p.fields, approval.action) : ''}
      ${approval.reason ? `<p class="hint">${esc(approval.reason)}</p>` : ''}
      ${!approval.canDecide && !decided ? `<p class="hint hint--warn">${esc(tr('inbox.adminsOnly'))}</p>` : ''}
      ${can ? `<div class="form__row"><label class="lbl" for="ag-reject-reason">${esc(tr('inbox.rejectReason'))} <span class="opt">${esc(A.optional)}</span></label><input class="inp" id="ag-reject-reason" maxlength="300" /></div>` : ''}
      <div class="detail__foot">
        ${approval.subject && d.canOpenSubject(approval.subject) ? `<button class="btn btn--sm btn--ghost" type="button" data-appr="record">${esc(tr('run.openRecord'))}</button>` : ''}
        <button class="btn btn--sm btn--ghost" type="button" data-appr="run">${esc(tr('inbox.openRun'))}</button>
        ${can ? `<button class="btn btn--sm btn--ghost" type="button" data-appr="reject">${esc(tr('inbox.reject'))}</button><button class="btn btn--sm btn--primary" type="submit">${esc(tr('inbox.approve'))}</button>` : ''}
      </div>`;
    d.openDrawer(approval.agentName, form, false, { eyebrow: tr('inbox.approvalEyebrow') });
    const edits = () => {
      const out = {};
      $$('[data-edit]', form).forEach(el => { if (el.value !== (approval.input?.[el.dataset.edit] ?? '')) out[el.dataset.edit] = el.value; });
      return out;
    };
    $$('[data-edit]', form).forEach(el => el.addEventListener('input', () => {
      const btn = $('button[type="submit"]', form);
      if (btn) btn.textContent = Object.keys(edits()).length ? tr('inbox.approveEdited') : tr('inbox.approve');
    }));
    $$('[data-appr]', form).forEach(b => b.addEventListener('click', async () => {
      if (b.dataset.appr === 'record') return d.openSubject(approval.subject, here);
      if (b.dataset.appr === 'run') return d.openFrom(here, () => openRun(approval.runId));
      const ok = await d.confirmDialog({ title: tr('inbox.rejectTitle'), body: tr('inbox.rejectBody'), okLabel: tr('inbox.reject') });
      if (!ok) return;
      decide(approval, 'reject', { reason: $('#ag-reject-reason', form)?.value.trim() || undefined }, b);
    }));
    form.addEventListener('submit', e => {
      e.preventDefault();
      const changes = edits();
      decide(approval, 'approve', Object.keys(changes).length ? { input: changes } : {}, $('button[type="submit"]', form));
    });
  }
  async function decide(approval, verb, payload, button) {
    if (button) button.disabled = true;
    try {
      await d.api(`/app/api/agents/approvals/${encodeURIComponent(approval.id)}/${verb}`, { method: 'POST', body: JSON.stringify(payload) });
      d.toast(verb === 'approve' ? tr('inbox.approved') : tr('inbox.rejected'));
      d.closeDrawer();
      await refresh();
    } catch (err) {
      if (button) button.disabled = false;
      d.toast(err?.code === 'already_decided' ? tr('inbox.alreadyDecided') : A.actionFailed);
    }
  }

  async function openTask(task, subject = null) {
    await ensurePeople();
    const isNew = !task;
    const t = task || { title: '', detail: '', status: 'OPEN', subject };
    const here = task ? { key: `task:${task.id}`, label: task.title, open: () => openTask(task) } : null;
    const form = document.createElement('form');
    form.className = 'form';
    const due = t.dueAt ? d.localDay(t.dueAt) : '';
    const open = t.status === 'OPEN';
    form.innerHTML = `<div class="form__grid">
        <div class="form__row form__row--full"><label class="lbl" for="ag-task-title">${esc(tr('inbox.taskTitle'))} <span class="req">*</span></label><input class="inp" id="ag-task-title" maxlength="200" required value="${esc(t.title)}" /></div>
        <div class="form__row form__row--full"><label class="lbl" for="ag-task-detail">${esc(tr('inbox.taskDetail'))} <span class="opt">${esc(A.optional)}</span></label><textarea class="txt" id="ag-task-detail" rows="4" maxlength="4000">${esc(t.detail || '')}</textarea></div>
        <div class="form__row"><label class="lbl" for="ag-task-who">${esc(tr('inbox.taskAssignee'))}</label><select class="sel" id="ag-task-who"><option value="">${esc(tr('inbox.unassigned'))}</option>${(data.people || []).map(p => `<option value="${esc(p.id)}" ${p.id === t.assigneeUserId ? 'selected' : ''}>${esc(p.email)}</option>`).join('')}</select></div>
        <div class="form__row"><label class="lbl" for="ag-task-due">${esc(tr('inbox.taskDue'))}</label><input class="inp inp--mono" id="ag-task-due" type="date" value="${esc(due)}" /></div>
      </div>
      ${t.subjectLabel ? `<p class="hint">${esc(tr('inbox.taskFor', { record: t.subjectLabel }))}</p>` : ''}
      ${t.agentName ? `<p class="hint">${esc(tr('inbox.fromAgent', { agent: t.agentName }))}</p>` : ''}
      <div class="detail__foot">
        ${t.subject && d.canOpenSubject(t.subject) && here ? `<button class="btn btn--sm btn--ghost" type="button" data-task-act="record">${esc(tr('run.openRecord'))}</button>` : ''}
        ${!isNew && open ? `<button class="btn btn--sm btn--ghost" type="button" data-task-act="DISMISSED">${esc(tr('inbox.dismiss'))}</button><button class="btn btn--sm" type="button" data-task-act="DONE">${esc(tr('inbox.markDone'))}</button>` : ''}
        ${!isNew && !open ? `<button class="btn btn--sm" type="button" data-task-act="OPEN">${esc(tr('inbox.reopen'))}</button>` : ''}
        <button class="btn btn--sm btn--primary" type="submit">${esc(isNew ? tr('inbox.createTask') : A.save)}</button>
      </div>`;
    d.openDrawer(isNew ? tr('inbox.newTask') : t.title, form, false, { eyebrow: tr('inbox.tasks') });
    const values = () => {
      const day = $('#ag-task-due', form).value;
      return {
        title: $('#ag-task-title', form).value.trim(),
        detail: $('#ag-task-detail', form).value.trim(),
        assigneeUserId: $('#ag-task-who', form).value || undefined,
        clearAssignee: !$('#ag-task-who', form).value,
        dueAt: day ? new Date(`${day}T09:00:00`).toISOString() : undefined,
        clearDue: !day,
      };
    };
    const save = async (extra, button) => {
      const body = { ...values(), ...extra };
      if (!body.title) return d.toast(tr('inbox.titleRequired'));
      if (button) button.disabled = true;
      try {
        if (isNew) {
          const { clearAssignee, clearDue, ...create } = body;
          await d.api('/app/api/agents/tasks', { method: 'POST', body: JSON.stringify({ ...create, subjectType: subject?.type, subjectId: subject?.id }) });
        } else {
          await d.api(`/app/api/agents/tasks/${encodeURIComponent(task.id)}`, { method: 'PATCH', body: JSON.stringify(body) });
        }
        d.toast(extra.status === 'DONE' ? tr('inbox.taskDone') : tr('inbox.taskSaved'));
        d.closeDrawer();
        await refresh();
      } catch {
        if (button) button.disabled = false;
        d.toast(A.actionFailed);
      }
    };
    $$('[data-task-act]', form).forEach(b => b.addEventListener('click', () => {
      if (b.dataset.taskAct === 'record') return d.openSubject(t.subject, here);
      save({ status: b.dataset.taskAct }, b);
    }));
    form.addEventListener('submit', e => { e.preventDefault(); save({}, $('button[type="submit"]', form)); });
  }

  // ── refs: Home rows and notifications point at `approval:ID`, `task:ID`, `run:ID`, `agent:ID` or `inbox` ──
  /** Sets the tab the page shows behind what [ref] opens, before the page loads. */
  function focusRef(ref) {
    const kind = String(ref || '').split(':')[0];
    if (kind === 'approval' || kind === 'inbox') { ui.tab = 'inbox'; ui.inbox = 'approvals'; }
    else if (kind === 'task') { ui.tab = 'inbox'; ui.inbox = 'tasks'; }
    else if (kind === 'run') { ui.tab = 'activity'; ui.activity = ''; }
    else if (kind === 'agent') ui.tab = 'agents';
  }
  /** Opens the approval, task, run or agent [ref] names in the drawer, with [back] as the way back. */
  async function openRef(ref, back = null) {
    const [kind, id] = String(ref || '').split(':');
    if (!id) return;
    const path = { approval: 'approvals', task: 'tasks' }[kind];
    if (!path) {
      if (kind === 'run') d.openFrom(back, () => openRun(id));
      else if (kind === 'agent') d.openFrom(back, () => openAgent(id));
      return;
    }
    let item;
    try { [item] = await Promise.all([d.api(`/app/api/agents/${path}/${encodeURIComponent(id)}`), ensurePeople()]); }
    catch { return d.toast(d.STR.loadFailed); }
    d.openFrom(back, () => (kind === 'approval' ? openApproval(item) : openTask(item)));
  }

  // ── automations on a record: the client record's tab, and a block under quotes, invoices and bookings ──
  /** What a run did and where it stands: "Sent a WhatsApp message · Continues tomorrow at 09:00". */
  function runSummary(run) {
    const did = [...new Set(run.done || [])].map(key => tr(`did.${dotKey(key)}`, null, actionLabel(key)));
    if (run.status !== 'SUCCEEDED') return [...did, runProgress(run)].filter(Boolean).join(' · ');
    const outcome = run.outcome && run.outcome !== 'completed' ? reasonText(run.outcome) : '';
    return [...did, outcome].filter(Boolean).join(' · ') || reasonText('completed');
  }
  function autoRunRow(run, subject) {
    const elsewhere = run.subject && (run.subject.type !== subject.type || run.subject.id !== subject.id);
    const tone = RUN_TONES[run.status] || 'muted';
    const when = run.status === 'WAITING' && run.resumeAt ? '' : d.relTime(run.finishedAt || run.createdAt);
    return `<li><button class="worklist__item" type="button" data-auto-run="${esc(run.id)}" data-tone="${tone}">
      <span class="worklist__dot" aria-hidden="true"></span>
      <span class="worklist__main"><span class="worklist__title">${esc(run.agentName)}</span><span class="worklist__detail">${esc([elsewhere ? run.subjectLabel : '', runSummary(run)].filter(Boolean).join(' · '))}</span></span>
      <span class="worklist__side">${run.status === 'SUCCEEDED' ? '' : `<span class="worklist__meta">${runPill(run.status)}</span>`}${when ? `<span class="worklist__when">${esc(when)}</span>` : ''}</span>
    </button></li>`;
  }
  const subjectPath = subject => `/app/api/agents/subjects/${encodeURIComponent(subject.type)}/${encodeURIComponent(subject.id)}`;

  /**
   * Draws [view] (GET /app/api/agents/subjects/{type}/{id}) into [el]: runs in progress, open tasks,
   * recent runs and "run an agent", plus the pause switch on a client. Rows open over the record,
   * with [back] as the way back. After a run or a pause, [onChange] gets the fresh view; without
   * it the block redraws itself. [hideEmpty] leaves [el] empty when there is nothing to show.
   */
  function renderAutomations(el, subject, view, opts = {}) {
    const { back = null, hideEmpty = false, onChange = null } = opts;
    if (!view) {
      el.innerHTML = hideEmpty ? '' : `<div class="panel"><div class="empty"><p class="empty__title">${esc(d.STR.loadFailed)}</p></div></div>`;
      return;
    }
    const upcoming = view.upcoming || [];
    const tasks = view.tasks || [];
    const recent = view.recent || [];
    const manual = view.manualAgents || [];
    const paused = view.automationPaused === true;
    const rows = upcoming.length + tasks.length + recent.length;
    if (hideEmpty && !rows && !manual.length) { el.innerHTML = ''; return; }
    const group = (label, items) => (items.length ? `<li class="worklist__group">${esc(label)}</li>${items.join('')}` : '');
    const list = rows
      ? `<ul class="worklist">${group(tr('automations.upcoming'), upcoming.map(r => autoRunRow(r, subject)))}${group(tr('automations.tasks'), tasks.map(taskRow))}${group(tr('automations.recent'), recent.map(r => autoRunRow(r, subject)))}</ul>`
      : (hideEmpty ? '' : `<div class="empty"><p class="empty__title">${esc(tr('automations.empty'))}</p><p class="empty__desc">${esc(tr('automations.emptyDesc'))}</p></div>`);
    const runner = manual.length && !paused
      ? `<div class="panel__filters"><select class="sel" data-auto-agent aria-label="${esc(tr('automations.runLabel'))}">${manual.map(a => `<option value="${esc(a.id)}">${esc(a.name)}</option>`).join('')}</select><button class="btn btn--sm" type="button" data-auto-start>${esc(A.runNow)}</button></div>`
      : '';
    const pause = view.automationPaused != null && !paused
      ? `<div class="panel__tools"><button class="btn btn--sm btn--ghost" type="button" data-auto-pause="1">${esc(tr('automations.pause'))}</button></div>`
      : '';
    const notice = paused
      ? `<div class="notice notice--warn"><div class="notice__text"><strong>${esc(tr('automations.pausedTitle'))}</strong><span>${esc(tr('automations.pausedDesc'))}</span></div><div class="notice__actions"><button class="btn btn--sm" type="button" data-auto-pause="0">${esc(tr('automations.resume'))}</button></div></div>`
      : '';
    const open = upcoming.length + tasks.length;
    el.innerHTML = `${notice}<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(tr('automations.title'))}${open ? ` <span class="tag">${open}</span>` : ''}</h2>${pause}</header>${runner}${list}</section>`;

    const refetch = async () => {
      const next = await d.api(subjectPath(subject)).catch(() => null);
      if (!next || !el.isConnected) return;
      if (onChange) onChange(next);
      else renderAutomations(el, subject, next, opts);
    };
    const runs = [...upcoming, ...recent];
    $$('[data-auto-run]', el).forEach(b => b.addEventListener('click', () => {
      const run = runs.find(r => r.id === b.dataset.autoRun);
      if (run) d.openFrom(back, () => openRun(run.id));
    }));
    $$('[data-task]', el).forEach(b => b.addEventListener('click', async () => {
      const task = tasks.find(t => t.id === b.dataset.task);
      if (!task) return;
      await ensurePeople();
      d.openFrom(back, () => openTask(task));
    }));
    $('[data-auto-start]', el)?.addEventListener('click', async e => {
      const button = e.currentTarget;
      const agentId = $('[data-auto-agent]', el).value;
      button.disabled = true;
      try {
        await d.api(`/app/api/agents/${encodeURIComponent(agentId)}/run`, { method: 'POST', body: JSON.stringify({ subjectType: subject.type, subjectId: subject.id }) });
        d.toast(A.started);
        await refetch();
      } catch (err) {
        button.disabled = false;
        d.toast(err?.code ? reasonText(err.code) : A.actionFailed);
      }
    });
    $$('[data-auto-pause]', el).forEach(b => b.addEventListener('click', async () => {
      const pausing = b.dataset.autoPause === '1';
      b.disabled = true;
      try {
        await d.api(`${subjectPath(subject)}/automation`, { method: 'PUT', body: JSON.stringify({ paused: pausing }) });
        d.toast(tr(pausing ? 'automations.pausedToast' : 'automations.resumedToast'));
        await refetch();
      } catch {
        b.disabled = false;
        d.toast(A.actionFailed);
      }
    }));
  }

  /** Loads and draws the Automations block for [subject] into [el] once it arrives, if [el] is still shown. */
  async function mountAutomations(el, subject, opts = {}) {
    const view = await d.api(subjectPath(subject)).catch(() => null);
    if (el.isConnected) renderAutomations(el, subject, view, opts);
  }

  // ── activity ──────────────────────────────────────────────────────
  function renderActivity(pane) {
    const chips = ['', 'open', 'attention', 'done'].map(f => `<button class="chip ${ui.activity === f ? 'is-on' : ''}" type="button" data-activity="${f}">${esc(tr(`activity.${f || 'all'}`))}</button>`).join('');
    const tests = `<label class="form__check"><input type="checkbox" data-activity-tests ${ui.showTests ? 'checked' : ''} /> ${esc(tr('activity.tests'))}</label>`;
    const q = (d.state.search || '').toLowerCase();
    const runs = data.runs.filter(r => !q || `${r.agentName} ${r.subjectLabel || ''}`.toLowerCase().includes(q));
    pane.innerHTML = `<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(tr('activity.title'))} <span class="tag">${runs.length}</span></h2><div class="panel__tools">${chips}${tests}</div></header>
      ${runs.length ? runsTableHtml(runs, true, true) : `<div class="empty"><p class="empty__title">${esc(tr('activity.empty'))}</p><p class="empty__desc">${esc(tr('activity.emptyDesc'))}</p></div>`}</section>`;
    $$('[data-activity]', pane).forEach(b => b.addEventListener('click', async () => { ui.activity = b.dataset.activity; await refresh(); }));
    $('[data-activity-tests]', pane)?.addEventListener('change', async e => { ui.showTests = e.target.checked; await refresh(); });
    $$('[data-run]', pane).forEach(r => r.addEventListener('click', () => openRun(r.dataset.run)));
  }

  // ── company settings ──────────────────────────────────────────────
  function renderSettings(pane) {
    const s = data.settings;
    if (!s) { pane.innerHTML = `<p class="hint">${esc(d.CRM.loading)}</p>`; return; }
    const c = s.company || {};
    const u = s.usage || {};
    const p = s.platform || {};
    const manage = s.canManage;
    const quiet = c.quietHours || null;
    const dis = manage ? '' : 'disabled';
    const num = new Intl.NumberFormat(locale());
    pane.innerHTML = `<div class="agents-settings">
      <form class="panel" id="agents-settings-form">
        <header class="panel__head"><div><h2 class="panel__title">${esc(tr('settings.title'))}</h2><p class="panel__meta">${esc(tr('settings.desc'))}</p></div></header>
        <div class="panel__body form">
          ${manage ? '' : `<p class="hint">${esc(tr('settings.readOnly'))}</p>`}
          <label class="form__check"><input type="checkbox" id="ags-paused" ${c.paused ? 'checked' : ''} ${dis} /> ${esc(tr('settings.paused'))}</label>
          <p class="hint">${esc(tr('settings.pausedHint'))}</p>
          <div class="form__grid">
            <div class="form__row"><label class="lbl" for="ags-autonomy">${esc(tr('settings.defaultAutonomy'))}</label><select class="sel" id="ags-autonomy" ${dis}>${['APPROVE', 'AUTO', 'DRAFT'].map(v => `<option value="${v}" ${(c.defaultAutonomy || 'APPROVE') === v ? 'selected' : ''}>${esc(tr(`autonomy.${v}`))}</option>`).join('')}</select></div>
            <div class="form__row"><label class="lbl" for="ags-expiry">${esc(tr('settings.expiry'))}</label><input class="inp inp--mono" id="ags-expiry" type="number" min="1" max="30" value="${c.approvalExpiryDays ?? 3}" ${dis} /></div>
            <div class="form__row form__row--full"><label class="form__check"><input type="checkbox" id="ags-quiet" ${quiet ? 'checked' : ''} ${dis} /> ${esc(tr('settings.quiet'))}</label>
              <div class="inp-range"><input class="inp inp--mono" id="ags-quiet-start" type="time" value="${esc(quiet?.start || '21:00')}" ${dis} aria-label="${esc(A.from)}" /><span class="muted">–</span><input class="inp inp--mono" id="ags-quiet-end" type="time" value="${esc(quiet?.end || '08:00')}" ${dis} aria-label="${esc(A.to)}" /></div></div>
            <div class="form__row form__row--full"><label class="form__check"><input type="checkbox" id="ags-business" ${c.businessDaysOnly ? 'checked' : ''} ${dis} /> ${esc(tr('settings.businessDays'))}</label></div>
            <div class="form__row"><label class="lbl" for="ags-daily">${esc(tr('settings.dailyCap'))}</label><input class="inp inp--mono" id="ags-daily" type="number" min="0" max="20" value="${c.perRecipientDailyCap ?? 2}" ${dis} /></div>
            <div class="form__row"><label class="lbl" for="ags-weekly">${esc(tr('settings.weeklyCap'))}</label><input class="inp inp--mono" id="ags-weekly" type="number" min="0" max="50" value="${c.perRecipientWeeklyCap ?? 5}" ${dis} /></div>
          </div>
          ${manage ? `<div class="record__pane-foot"><button class="btn btn--primary" type="submit">${esc(A.save)}</button></div>` : ''}
        </div>
      </form>
      <section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(tr('settings.usage'))}</h2></header>
        <div class="panel__body"><dl class="dash-facts">
          <div><dt>${esc(tr('settings.activeAgents'))}</dt><dd>${num.format(u.activeAgents || 0)} / ${num.format(u.maxActiveAgents || p.maxActiveAgents || 0)}</dd></div>
          <div><dt>${esc(tr('settings.runsToday'))}</dt><dd>${num.format(u.runsToday || 0)} / ${num.format(u.runsPerDay || p.runsPerDay || 0)}</dd></div>
          <div><dt>${esc(tr('settings.emails'))}</dt><dd>${num.format(p.emailSendsPerDay || 0)}</dd></div>
          <div><dt>${esc(tr('settings.tokens'))}</dt><dd>${num.format(u.tokensThisMonth || 0)}${u.tokenBudget ? ` / ${num.format(u.tokenBudget)}` : ''}</dd></div>
        </dl><p class="hint">${esc(tr('settings.limitsHint'))}</p></div></section>
    </div>`;
    const form = $('#agents-settings-form', pane);
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const btn = $('button[type="submit"]', form);
      btn.disabled = true;
      const int = (id, fallback) => { const v = parseInt($(id, form).value, 10); return Number.isFinite(v) ? v : fallback; };
      const settings = {
        paused: $('#ags-paused', form).checked,
        defaultAutonomy: $('#ags-autonomy', form).value,
        quietHours: $('#ags-quiet', form).checked ? { start: $('#ags-quiet-start', form).value || '21:00', end: $('#ags-quiet-end', form).value || '08:00' } : null,
        businessDaysOnly: $('#ags-business', form).checked,
        perRecipientDailyCap: int('#ags-daily', 2),
        perRecipientWeeklyCap: int('#ags-weekly', 5),
        approvalExpiryDays: int('#ags-expiry', 3),
      };
      try {
        data.settings = await d.api('/app/api/agents/settings', { method: 'PUT', body: JSON.stringify({ settings }) });
        d.toast(tr('settings.saved'));
        await refresh();
      } catch {
        btn.disabled = false;
        d.toast(A.actionFailed);
      }
    });
  }

  /** Nav count: approvals and tasks waiting for someone. */
  function badge() {
    const o = data.overview;
    if (!o) return null;
    return { count: (o.pendingApprovals || 0) + (o.openTasks || 0), alert: (o.pendingApprovals || 0) > 0 };
  }

  function newAgent() {
    switchTab('templates');
  }

  function init(deps) {
    d = deps;
  }

  window.AgentsUI = {
    init, load, render, badge, canManage, newAgent, openAgent, openRun, openApproval, openTask, switchTab, ensureCatalog, recipeHtml, runPill, agentIcon,
    focusRef, openRef, renderAutomations, mountAutomations, reasonText,
  };
})();
