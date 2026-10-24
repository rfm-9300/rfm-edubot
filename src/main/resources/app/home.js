/* Home (/app → overview): the cockpit from GET /app/api/overview?extended=1. A KPI strip with sparklines,
   what needs you, today's agenda, cash flow, receivables, the quote pipeline, inbox activity, top
   clients, recent events and module tiles. Cards hidden in Settings → Home are left out. */

function centsEUR(n) { return fmtEUR(Number(n || 0) / 100); }
function deltaHint(deltaPct, vs) {
  if (deltaPct == null || typeof STR.deltaUp !== 'function') return '';
  if (deltaPct > 0) return STR.deltaUp({ pct: deltaPct, vs });
  if (deltaPct < 0) return STR.deltaDown({ pct: Math.abs(deltaPct), vs });
  return STR.deltaFlat({ vs });
}
function attentionTitle(item) {
  if (item.aggregate && item.kind === 'overdue_invoice' && typeof STR.overdueInvoiceN === 'function') return STR.overdueInvoiceN({ n: item.count });
  if (item.aggregate && item.kind === 'overdue_payment' && typeof STR.overduePaymentN === 'function') return STR.overduePaymentN({ n: item.count });
  if (item.kind === 'assistant_action' && typeof STR.assistantAction === 'function') return STR.assistantAction({ n: Number(item.detail || 0) });
  if (item.kind === 'agent_task_due') return agentTaskLate(item) ? STR.agentTaskOverdue : STR.agentTaskDue;
  const map = {
    waiting_chat: STR.waitingChat,
    overdue_invoice: STR.overdueInvoice,
    due_soon_invoice: STR.dueSoonInvoice,
    overdue_payment: STR.overduePayment,
    due_soon_payment: STR.dueSoonPayment,
    pending_booking: STR.pendingBooking,
    instagram_comment: STR.instagramWaitingComment,
    quote_expiring: STR.quoteExpiring,
    agent_approval: STR.agentApproval,
    agent_failed: STR.agentFailed,
    integration_reconnect: STR.integrationReconnect,
    service_submission: STR.serviceSubmission,
    project_delayed: STR.projectDelayed,
    site_issue_overdue: STR.siteIssueOverdue,
    change_order_pending: STR.changeOrderPending,
    measurement_to_invoice: STR.measurementToInvoice,
  };
  return map[item.kind] || item.kind;
}
// Home rows that open a particular Settings section.
const ATTENTION_SETTINGS = { integration_reconnect: 'channels' };
// Agent tasks come in when due by the end of today; one from an earlier day is late.
const agentTaskLate = item => !!item.at && localDay(item.at) < localDay(new Date().toISOString());
// Home rows about agents open the approval, task or run itself (see AgentsUI.openRef).
const AGENT_ATTENTION_REFS = { agent_approval: 'approval', agent_task_due: 'task', agent_failed: 'run' };
function attentionPill(kind) {
  const map = {
    waiting_chat: 'pill--warn', overdue_invoice: 'pill--bad', due_soon_invoice: 'pill--warn',
    overdue_payment: 'pill--bad', due_soon_payment: 'pill--warn',
    pending_booking: 'pill--info', instagram_comment: 'pill--accent', quote_expiring: 'pill--warn',
    assistant_action: 'pill--info', agent_approval: 'pill--warn', agent_task_due: 'pill--info', agent_failed: 'pill--bad',
    integration_reconnect: 'pill--bad', service_submission: 'pill--warn',
    project_delayed: 'pill--bad', site_issue_overdue: 'pill--bad', change_order_pending: 'pill--warn', measurement_to_invoice: 'pill--info',
  };
  return map[kind] || '';
}
function healthLabel(health) {
  if (health === 'urgent') return STR.healthUrgent;
  if (health === 'watch') return STR.healthWatch;
  return STR.healthOk;
}
function pulseLine(o) {
  const parts = [];
  if ((o.health || 'ok') === 'ok') parts.push(STR.pulseOk);
  else parts.push(typeof STR.pulseNeeds === 'function' ? STR.pulseNeeds({ n: o.attentionCount || 1 }) : STR.needsYou);
  if (o.cash?.outstandingCents > 0 && typeof STR.pulseOutstanding === 'function') parts.push(STR.pulseOutstanding({ amount: centsEUR(o.cash.outstandingCents) }));
  if (o.calendar?.today > 0 && typeof STR.pulseBookingsToday === 'function') parts.push(STR.pulseBookingsToday({ n: o.calendar.today }));
  return parts.join(' · ');
}
function homeCardCopy(id) {
  const titles = {
    highlights: STR.homeCard_highlights, pulse: STR.homeCard_pulse, attention: STR.needsYou, setup: STR.setupTitle,
    financeiro: STR.snapFinance, pipeline: STR.snapPipeline, customers: STR.snapCustomers, inbox: STR.snapInbox,
    calendar: STR.homeCard_calendar, social: STR.snapSocial, catalog: STR.snapCatalog, services: STR.snapServices,
    suppliers: STR.snapSuppliers, employees: STR.snapEmployees, assistant: STR.snapAssistant, agents: STR.snapAgents,
    projects: STR.snapProjects, recent: STR.dashRecent,
  };
  const descs = {
    highlights: STR.homeCard_highlightsDesc, pulse: STR.homeCard_pulseDesc, attention: STR.homeCard_attentionDesc, setup: STR.homeCard_setupDesc,
    financeiro: STR.homeCard_financeDesc, pipeline: STR.homeCard_pipelineDesc, customers: STR.homeCard_customersDesc, inbox: STR.homeCard_inboxDesc,
    calendar: STR.homeCard_calendarDesc, social: STR.homeCard_socialDesc, catalog: STR.homeCard_catalogDesc, services: STR.homeCard_servicesDesc,
    suppliers: STR.homeCard_suppliersDesc, employees: STR.homeCard_employeesDesc, assistant: STR.homeCard_assistantDesc, agents: STR.homeCard_agentsDesc,
    projects: STR.homeCard_projectsDesc, recent: STR.homeCard_recentDesc,
  };
  return { title: titles[id] || id, detail: descs[id] || '' };
}

const wholeCentsEUR = c => fmtEURWhole(Number(c || 0) / 100);
const RECENT_TONES = { invoice_paid: 'ok', invoice_issued: 'info', payment_paid: 'neutral', quote_accepted: 'ok', quote_created: 'accent', client_created: 'accent', booking_created: 'info' };
function pctChange(current, previous) {
  if (!previous) return null;
  return Math.round(((current - previous) / Math.abs(previous)) * 100);
}
function niceCeil(v) {
  if (!(v > 0)) return 0;
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  return ([1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10].find(s => v / p <= s) || 10) * p;
}
function dashGreeting() {
  const hour = Number(new Intl.DateTimeFormat('en-GB', { hour: 'numeric', hourCycle: 'h23', timeZone: tenantTz() }).format(new Date()));
  if (hour >= 5 && hour < 12) return STR.greetingMorning;
  if (hour >= 12 && hour < 19) return STR.greetingAfternoon;
  return STR.greetingEvening;
}
function monthLabel(key, long = false) {
  const [y, m] = String(key).split('-').map(Number);
  const opts = long ? { month: 'long', year: 'numeric', timeZone: 'UTC' } : { month: 'short', timeZone: 'UTC' };
  return new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString(uiLocale(), opts);
}
function dayLabel(key) {
  const [y, m, d] = String(key).split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString(uiLocale(), { day: 'numeric', month: 'short', timeZone: 'UTC' });
}
function sparkline(values, cls = '') {
  if (!values || values.length < 2) return '';
  const w = 120, h = 32;
  const max = Math.max(...values, 0);
  const x = i => (i / (values.length - 1)) * w;
  const y = v => (max > 0 ? h - 2 - (v / max) * (h - 5) : h - 2);
  const line = values.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)} ${y(v).toFixed(1)}`).join(' ');
  return `<svg class="spark${cls ? ` ${cls}` : ''}" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" aria-hidden="true"><path class="spark__area" d="${line} L${w} ${h} L0 ${h} Z"/><path class="spark__line" d="${line}"/></svg>`;
}
// A single overdue document keeps its own row so it can deep-link to that invoice/payment; only two
// or more collapse into one count row.
function dashAttention(o) {
  const list = o.attention || [];
  const rows = [];
  [['overdue_invoice', 'invoices', o.cash], ['overdue_payment', 'payments', o.payments]].forEach(([kind, tab, money]) => {
    const count = money?.overdueCount || 0;
    const own = list.filter(n => n.kind === kind);
    if (count > 1 || (count === 1 && !own.length)) rows.push({ kind, tab, aggregate: true, count, amountCents: money.overdueCents });
    else rows.push(...own);
  });
  return [...rows, ...list.filter(n => n.kind !== 'overdue_invoice' && n.kind !== 'overdue_payment')];
}
function attentionTone(kind) {
  return { 'pill--bad': 'bad', 'pill--warn': 'warn', 'pill--info': 'info', 'pill--accent': 'accent' }[attentionPill(kind)] || 'neutral';
}
function worklistRow({ tone = 'neutral', title, detail = '', meta = '', when = '', amount = false, go, open = '', conversation = '', settings = '' }) {
  const attrs = `data-go="${escapeHTML(go)}"${open ? ` data-open="${escapeHTML(open)}"` : ''}${conversation ? ` data-conversation="${escapeHTML(conversation)}"` : ''}${settings ? ` data-settings="${escapeHTML(settings)}"` : ''}`;
  const side = meta || when
    ? `<span class="worklist__side">${meta ? `<span class="worklist__meta${amount ? ' worklist__meta--amount' : ''}">${escapeHTML(meta)}</span>` : ''}${when ? `<span class="worklist__when">${escapeHTML(when)}</span>` : ''}</span>`
    : '';
  return `<li><button type="button" class="worklist__item" ${attrs}>
    <span class="worklist__dot" data-tone="${tone}" aria-hidden="true"></span>
    <span class="worklist__main"><span class="worklist__title">${escapeHTML(title)}</span>${detail ? `<span class="worklist__detail">${escapeHTML(detail)}</span>` : ''}</span>
    ${side}
  </button></li>`;
}
function dashCard({ order, title, tag = null, meta = '', go = '', body, flush = false }) {
  const open = go ? `<button type="button" class="btn btn--sm btn--ghost" data-go="${escapeHTML(go)}" aria-label="${escapeHTML(tApp('openModuleAria', { name: title }))}">${escapeHTML(STR.openModule)} <span aria-hidden="true">→</span></button>` : '';
  const tools = (meta ? `<span class="panel__meta">${escapeHTML(meta)}</span>` : '') + open;
  return `<section class="panel panel--card" data-order="${order}">
    <header class="panel__head"><h2 class="panel__title">${escapeHTML(title)}${tag != null ? ` <span class="tag">${escapeHTML(String(tag))}</span>` : ''}</h2>${tools ? `<div class="panel__tools">${tools}</div>` : ''}</header>
    <div class="panel__body${flush ? ' panel__body--flush' : ''}">${body}</div>
  </section>`;
}

function dashKpis(o, hidden) {
  const cells = [];
  const money = !hidden.has('financeiro');
  const plusThisMonth = n => (n ? tApp('kpiNewThisMonth', { n }) : '');
  if (o.cash && money) {
    const pct = pctChange(o.cash.collectedThisMonthCents || 0, o.cash.collectedLastMonthCents || 0);
    cells.push({
      go: 'financeiro', label: STR.hl_collected_month, value: wholeCentsEUR(o.cash.collectedThisMonthCents),
      meta: deltaHint(pct, STR.vsLastMonth), trend: pct > 0 ? 'up' : pct < 0 ? 'down' : '',
      spark: (o.cashFlow || []).map(m => m.inCents || 0),
    });
    const overdue = (o.cash.overdueCents || 0) > 0;
    cells.push({
      go: 'invoices', label: STR.hl_outstanding, value: wholeCentsEUR(o.cash.outstandingCents),
      meta: overdue ? tApp('kpiOverdue', { amount: wholeCentsEUR(o.cash.overdueCents) }) : (o.cash.dueSoonCount ? tApp('kpiDueSoon', { n: o.cash.dueSoonCount }) : ''),
      metaTone: overdue ? 'bad' : '',
    });
  }
  if (o.payments && money) {
    if (o.cash) {
      const received = o.cash.collectedThisMonthCents || 0;
      const spent = o.payments.paidThisMonthCents || 0;
      cells.push({ go: 'financeiro', label: STR.kpiNet, value: wholeCentsEUR(received - spent), meta: tApp('kpiInOut', { in: wholeCentsEUR(received), out: wholeCentsEUR(spent) }) });
    } else {
      const overdue = (o.payments.overdueCents || 0) > 0;
      cells.push({ go: 'payments', label: STR.financePayable, value: wholeCentsEUR(o.payments.outstandingCents), meta: overdue ? tApp('kpiOverdue', { amount: wholeCentsEUR(o.payments.overdueCents) }) : '', metaTone: overdue ? 'bad' : '' });
    }
  }
  if (o.pipeline && !hidden.has('pipeline')) {
    cells.push({ go: 'quotes', label: STR.hl_pipeline_open, value: wholeCentsEUR(o.pipeline.openCents), meta: tApp('kpiQuotes', { n: (o.pipeline.pendingCount || 0) + (o.pipeline.sentCount || 0), pct: o.pipeline.winRatePct || 0 }) });
  }
  if (o.inbox && !hidden.has('inbox')) {
    if (hasModule('conversations')) {
      cells.push({
        go: 'conversations', label: STR.hl_waiting, value: o.inbox.waiting || 0, valueTone: o.inbox.waiting > 0 ? 'warn' : '',
        meta: tApp('kpiMessagesToday', { n: o.inbox.messagesToday || 0 }), spark: (o.activity || []).map(d => d.count || 0),
      });
    } else if (hasModule('contacts')) {
      cells.push({ go: 'contacts', label: STR.hl_contacts, value: o.inbox.contacts || 0, meta: o.inbox.newContactsThisWeek ? tApp('kpiNewThisWeek', { n: o.inbox.newContactsThisWeek }) : '' });
    }
  }
  if (o.calendar && !hidden.has('calendar')) cells.push({ go: 'bookings', label: STR.hl_bookings_today, value: o.calendar.today || 0, meta: tApp('kpiThisWeek', { n: o.calendar.thisWeek || 0 }) });
  if (o.customers && !hidden.has('customers')) cells.push({ go: 'clients', label: STR.hl_clients, value: o.customers.total || 0, meta: plusThisMonth(o.customers.newThisMonth) });
  return cells.slice(0, 5);
}

function dashCashFlowCard(o) {
  const flow = o.cashFlow || [];
  const showIn = !!o.cash;
  const showOut = !!o.payments;
  let body = '';
  if (flow.length) {
    const peak = Math.max(0, ...flow.map(m => Math.max(showIn ? m.inCents || 0 : 0, showOut ? m.outCents || 0 : 0))) / 100;
    const max = niceCeil(peak);
    const height = eur => (max > 0 ? Math.min(100, (eur / max) * 100) : 0).toFixed(1);
    const legend = `<ul class="legend">${showIn ? `<li class="legend__item"><span class="legend__swatch" data-tone="accent"></span>${escapeHTML(STR.dashMoneyIn)}</li>` : ''}${showOut ? `<li class="legend__item"><span class="legend__swatch" data-tone="muted"></span>${escapeHTML(STR.dashMoneyOut)}</li>` : ''}</ul>`;
    const describe = m => [monthLabel(m.month, true), showIn ? `${STR.dashMoneyIn} ${fmtEUR((m.inCents || 0) / 100)}` : '', showOut ? `${STR.dashMoneyOut} ${fmtEUR((m.outCents || 0) / 100)}` : ''].filter(Boolean).join(' · ');
    const cols = flow.map(m => `<div class="bars__pair" title="${escapeHTML(describe(m))}">${showIn ? `<span class="bars__bar" data-tone="accent" style="height:${height((m.inCents || 0) / 100)}%"></span>` : ''}${showOut ? `<span class="bars__bar" data-tone="muted" style="height:${height((m.outCents || 0) / 100)}%"></span>` : ''}</div>`).join('');
    const labels = flow.map((m, i) => `<span class="bars__label${i === flow.length - 1 ? ' is-current' : ''}">${escapeHTML(monthLabel(m.month))}</span>`).join('');
    const quiet = flow.every(m => !(m.inCents || m.outCents)) ? `<p class="dash-empty">${escapeHTML(STR.dashCashEmpty)}</p>` : '';
    body = `${legend}${quiet}<div class="bars" role="img" aria-label="${escapeHTML(flow.map(describe).join('; '))}"><div class="bars__scale"><span>${escapeHTML(max ? fmtEURCompact(max) : '')}</span></div><div class="bars__plot">${cols}</div><div class="bars__labels">${labels}</div></div>`;
  }
  const months = flow.length || 1;
  const avg = key => flow.reduce((sum, m) => sum + (m[key] || 0), 0) / months;
  body += dashFacts([
    flow.length && showIn ? [STR.dashAvgIn, wholeCentsEUR(avg('inCents'))] : null,
    flow.length && showOut ? [STR.dashAvgOut, wholeCentsEUR(avg('outCents'))] : null,
    o.cash ? [STR.dashIssuedMonth, wholeCentsEUR(o.cash.issuedThisMonthCents)] : null,
    !flow.length && o.cash ? [STR.financeGained, wholeCentsEUR(o.cash.collectedThisMonthCents)] : null,
    !flow.length && o.payments ? [STR.financeSpent, wholeCentsEUR(o.payments.paidThisMonthCents)] : null,
  ], true);
  return dashCard({ order: 3, title: STR.dashCashFlow, meta: STR.dashLast6Months, go: 'financeiro', body });
}

function dashMoneyCard(o) {
  let body = '';
  if (o.cash) {
    const c = o.cash;
    const aging = [
      ['neutral', STR.agingCurrent, c.agingCurrentCents || 0],
      ['warn', STR.agingWeek, c.agingWeekCents || 0],
      ['late', STR.agingMonth, c.agingMonthCents || 0],
      ['bad', STR.agingOld, c.agingOldCents || 0],
    ];
    const total = aging.reduce((sum, a) => sum + a[2], 0);
    const segs = total ? aging.filter(a => a[2] > 0).map(([tone, label, cents]) => `<span class="segbar__seg" data-tone="${tone}" style="width:${((cents / total) * 100).toFixed(2)}%" title="${escapeHTML(`${label} · ${centsEUR(cents)}`)}"></span>`).join('') : '';
    const overdue = (c.overdueCents || 0) > 0
      ? `<div class="dash-figure dash-figure--end"><span class="dash-figure__value dash-figure__value--bad">${escapeHTML(wholeCentsEUR(c.overdueCents))}</span><span class="dash-figure__label">${escapeHTML(STR.hl_overdue)}</span></div>`
      : '';
    body += `<div class="dash-split"><div class="dash-figure dash-figure--lg"><span class="dash-figure__value">${escapeHTML(wholeCentsEUR(c.outstandingCents))}</span><span class="dash-figure__label">${escapeHTML(STR.dashOutstanding)}</span></div>${overdue}</div>`;
    body += `<div class="segbar" role="img" aria-label="${escapeHTML(aging.map(([, label, cents]) => `${label} ${centsEUR(cents)}`).join('; '))}">${segs}</div>`;
    body += `<ul class="legend legend--rows">${aging.map(([tone, label, cents]) => `<li class="legend__item"><span class="legend__swatch" data-tone="${tone}"></span><span>${escapeHTML(label)}</span><span class="legend__value">${escapeHTML(wholeCentsEUR(cents))}</span></li>`).join('')}</ul>`;
    const top = (c.topOverdue || []).slice(0, 3);
    if (top.length) {
      body += `<p class="dash-sub">${escapeHTML(STR.dashTopOverdue)}</p><ul class="worklist">${top.map(t => worklistRow({
        tone: 'bad', title: t.name || t.number || '', detail: t.name && t.number ? t.number : '', meta: centsEUR(t.amountCents), amount: true, go: 'invoices', open: t.id,
      })).join('')}</ul>`;
    }
    body += dashFacts([c.dueSoonCount ? [STR.dashDueSoon, `${wholeCentsEUR(c.dueSoonCents)} · ${c.dueSoonCount}`] : null]);
  }
  if (o.payments) {
    const p = o.payments;
    body += `${o.cash ? `<p class="dash-sub">${escapeHTML(STR.dashPayables)}</p>` : ''}${dashFacts([
      [STR.financePayable, wholeCentsEUR(p.outstandingCents)],
      p.overdueCount ? [STR.hl_overdue, `${wholeCentsEUR(p.overdueCents)} · ${p.overdueCount}`] : null,
      p.dueSoonCount ? [STR.dashDueSoon, `${wholeCentsEUR(p.dueSoonCents)} · ${p.dueSoonCount}`] : null,
    ])}`;
  }
  return dashCard({ order: 4, title: o.cash ? STR.dashReceivables : STR.dashPayables, go: o.cash ? 'invoices' : 'payments', body });
}

function dashTodayCard(o) {
  const cal = o.calendar;
  const agenda = o.agenda || [];
  const now = Date.now();
  const rows = agenda.map(b => {
    const start = new Date(b.startAt).getTime();
    const end = new Date(b.endAt).getTime();
    const when = end <= now ? ' is-past' : start <= now ? ' is-now' : '';
    const minutes = Math.max(0, Math.round((end - start) / 60000));
    const what = [b.service, minutes ? fmtMinutes(minutes) : ''].filter(Boolean).join(' · ');
    const tone = { PENDING: 'warn', COMPLETED: 'neutral', NO_SHOW: 'bad' }[b.status] || 'accent';
    const pill = b.status === 'PENDING'
      ? `<span class="pill pill--warn">${escapeHTML(bookingStatusLabel(b.status))}</span>`
      : when === ' is-now' ? `<span class="pill pill--accent">${escapeHTML(STR.dashNow)}</span>` : '';
    return `<li class="agenda__item${when}" data-tone="${tone}"><span class="agenda__time">${escapeHTML(fmtTime(b.startAt))}</span><span class="agenda__rail" aria-hidden="true"></span><span class="agenda__main"><span class="agenda__who">${escapeHTML(b.contactName)}</span>${what ? `<span class="agenda__what">${escapeHTML(what)}</span>` : ''}</span>${pill}</li>`;
  }).join('');
  const next = cal.next && !agenda.some(b => b.id === cal.next.id) ? cal.next : null;
  const body = (rows ? `<ol class="agenda">${rows}</ol>` : `<p class="dash-empty">${escapeHTML(STR.dashAgendaEmpty)}</p>`) + dashFacts([
    next ? [STR.calendarNext, `${next.contactName} · ${fmtWhen(next.startAt)}`] : null,
    [STR.snapCalendar, cal.thisWeek || 0],
    [STR.calendarPending, cal.pending || 0],
  ]);
  return dashCard({ order: 2, title: STR.todayTitle, tag: cal.today || 0, go: 'bookings', body });
}

function dashPipelineCard(o) {
  const p = o.pipeline;
  const open = (p.pendingCount || 0) + (p.sentCount || 0);
  const stages = [[STR.pipelineDrafts, p.pendingCount || 0, 'neutral'], [STR.pipelineSent, p.sentCount || 0, 'info'], [STR.pipelineAccepted, p.acceptedCount || 0, 'ok']];
  const top = Math.max(1, ...stages.map(s => s[1]));
  const body = `<div class="dash-split">
      <div class="dash-figure"><span class="dash-figure__value">${escapeHTML(wholeCentsEUR(p.openCents))}</span><span class="dash-figure__label">${escapeHTML(tApp('dashOpenQuotesN', { n: open }))}</span></div>
      <div class="dash-figure dash-figure--end"><span class="dash-figure__value">${escapeHTML(`${p.winRatePct || 0}%`)}</span><span class="dash-figure__label">${escapeHTML(STR.hl_win_rate)}</span></div>
    </div>
    <ul class="hbars">${stages.map(([label, n, tone]) => `<li class="hbars__row"><span class="hbars__label">${escapeHTML(label)}</span><span class="hbars__track"><span class="hbars__fill" data-tone="${tone}" style="width:${((n / top) * 100).toFixed(1)}%"></span></span><span class="hbars__value">${n}</span></li>`).join('')}</ul>
    ${dashFacts([[STR.pipelineAcceptedMonth, `${wholeCentsEUR(p.acceptedThisMonthCents)} · ${p.acceptedThisMonthCount || 0}`], [STR.pipelineExpiring, p.expiringSoonCount || 0]])}`;
  return dashCard({ order: 5, title: STR.snapPipeline, go: 'quotes', body });
}

// [[value, label, tone]] as big numbers side by side; tone is '', 'warn' or 'bad'.
function dashFigures(figures) {
  return `<div class="dash-figures">${figures.map(([v, label, tone]) => `<div class="dash-figure"><span class="dash-figure__value${tone ? ` dash-figure__value--${tone}` : ''}">${escapeHTML(String(v))}</span><span class="dash-figure__label">${escapeHTML(label)}</span></div>`).join('')}</div>`;
}

function dashAgentsCard(o) {
  const a = o.agents;
  const paused = a.paused ? `<div class="notice notice--warn"><div class="notice__text"><span>${escapeHTML(I18N.t('app.agents.pausedTitle'))}</span></div></div>` : '';
  const body = `${paused}${dashFigures([
    [a.pendingApprovals || 0, STR.agentsToApprove, a.pendingApprovals ? 'warn' : ''],
    [a.openTasks || 0, STR.agentsOpenTasks, a.tasksDue ? 'warn' : ''],
    [a.runsToday || 0, STR.agentsRunsToday, ''],
  ])}
    ${dashFacts([
      [STR.agentsActive, a.activeAgents || 0],
      a.tasksDue ? [STR.agentsTasksDue, a.tasksDue] : null,
      [STR.agentsFailedWeek, a.failedThisWeek || 0],
    ])}`;
  return dashCard({ order: 9, title: STR.snapAgents, go: 'agents', body });
}

// The projects running, the ones furthest behind their plan, and what the sites and the client wait on.
function dashProjectsCard(o) {
  const p = o.projects;
  const behind = (p.atRisk || 0) + (p.delayed || 0);
  const figures = [
    [p.active || 0, STR.projectsActive, ''],
    [behind, STR.projectsBehind, p.delayed ? 'bad' : behind ? 'warn' : ''],
  ];
  if (hasModule('site-issues')) figures.push([p.openIssues || 0, STR.projectsOpenIssues, p.overdueIssues ? 'bad' : '']);
  const rows = (p.behind || []).map(r => worklistRow({
    tone: r.health === 'DELAYED' ? 'bad' : 'warn', title: `${r.number} · ${r.name}`, detail: scheduleText(r.scheduleDays), meta: `${r.progressPct}%`, go: 'projects', open: r.id,
  })).join('');
  const body = `${dashFigures(figures)}
    ${rows ? `<p class="dash-sub">${escapeHTML(STR.projectsFurthestBehind)}</p><ul class="worklist">${rows}</ul>` : (p.active ? `<p class="dash-empty">${escapeHTML(STR.projectsAllOnTrack)}</p>` : `<p class="dash-empty">${escapeHTML(STR.projectsNoneRunning)}</p>`)}
    ${dashFacts([
      hasModule('site-diary') && p.active ? [STR.projectsDiariesToday, tApp('projectsDiariesOf', { n: p.diariesToday || 0, total: (p.diariesToday || 0) + (p.activeWithoutDiary || 0) })] : null,
      hasModule('measurements') ? [STR.projectsToInvoice, `${wholeCentsEUR(p.toInvoiceCents)} · ${p.toInvoiceCount || 0}`] : null,
      hasModule('change-orders') && p.pendingChanges ? [STR.projectsChangesWaiting, `${p.pendingChanges} · ${wholeCentsEUR(p.pendingChangesCents)}`] : null,
      p.planning ? [STR.projectsPlanning, p.planning] : null,
    ])}`;
  return dashCard({ order: 2, title: STR.snapProjects, go: 'projects', body });
}

function dashInboxCard(o) {
  const i = o.inbox;
  const convo = hasModule('conversations');
  const activity = o.activity || [];
  const figures = convo
    ? [[i.waiting || 0, STR.hl_waiting, i.waiting > 0 ? 'warn' : ''], [i.messagesToday || 0, STR.hl_messages_today, ''], [i.messagesThisWeek || 0, STR.inboxWeek, '']]
    : [[i.contacts || 0, STR.hl_contacts, ''], [i.newContactsThisWeek || 0, STR.inboxNewContacts, '']];
  const pct = convo ? pctChange(i.messagesThisWeek || 0, i.messagesLastWeek || 0) : null;
  const body = `${dashFigures(figures)}
    ${activity.length > 1 ? `${sparkline(activity.map(d => d.count || 0), 'spark--tall')}<div class="dash-axis"><span>${escapeHTML(dayLabel(activity[0].day))}</span><span>${escapeHTML(STR.todayTitle)}</span></div>` : ''}
    ${dashFacts([
      pct != null ? [STR.dashWeekChange, `${pct > 0 ? '+' : ''}${pct}%`] : null,
      convo ? [STR.inboxNewContacts, i.newContactsThisWeek || 0] : null,
      convo ? [STR.hl_contacts, i.contacts || 0] : null,
      convo && i.autoReplyPaused ? [STR.inboxPaused, i.autoReplyPaused] : null,
    ])}`;
  return dashCard({ order: 6, title: STR.snapInbox, meta: activity.length > 1 ? STR.dashLast14Days : '', go: convo ? 'conversations' : 'contacts', body });
}

function dashTopClientsCard(o) {
  const top = o.topClients || [];
  const maxBilled = Math.max(1, ...top.map(t => t.billedCents || 0));
  const width = cents => ((cents / maxBilled) * 100).toFixed(1);
  const body = top.length
    ? `<ol class="rank">${top.map((t, idx) => {
      const paidPct = t.billedCents ? Math.round(((t.paidCents || 0) / t.billedCents) * 100) : 0;
      return `<li><button type="button" class="rank__item" data-go="clients" data-open="${escapeHTML(t.id)}"><span class="rank__pos">${idx + 1}</span><span class="rank__name">${escapeHTML(t.name || '—')}</span><span class="rank__value">${escapeHTML(wholeCentsEUR(t.billedCents))}</span><span class="rank__bar" aria-hidden="true"><span class="rank__fill rank__fill--billed" style="width:${width(t.billedCents || 0)}%"></span><span class="rank__fill" style="width:${width(t.paidCents || 0)}%"></span></span><span class="rank__meta">${escapeHTML(`${tApp('dashInvoicesN', { n: t.invoiceCount || 0 })} · ${tApp('dashPaidPct', { pct: paidPct })}`)}</span></button></li>`;
    }).join('')}</ol>`
    : `<p class="dash-empty">${escapeHTML(STR.dashTopClientsEmpty)}</p>`;
  return dashCard({ order: 7, title: STR.dashTopClients, meta: STR.dashLast12Months, go: 'clients', body, flush: top.length > 0 });
}

function dashRecentCard(o) {
  const items = (o.recent || []).filter(r => RECENT_TONES[r.kind]).slice(0, 6);
  const body = items.length
    ? `<ul class="worklist">${items.map(r => worklistRow({
      tone: RECENT_TONES[r.kind],
      title: tApp(`recent_${r.kind}`, { number: r.number || '' }),
      detail: [r.name, r.amountCents != null ? centsEUR(r.amountCents) : ''].filter(Boolean).join(' · '),
      meta: relTime(r.at),
      go: r.tab,
      open: r.id,
    })).join('')}</ul>`
    : `<p class="dash-empty">${escapeHTML(STR.dashRecentEmpty)}</p>`;
  return dashCard({ order: 8, title: STR.dashRecent, body, flush: items.length > 0 });
}

function renderOverview(root) {
  const o = state.overview || {};
  const hidden = new Set(o.hiddenCards || []);
  const money = !hidden.has('financeiro');
  const health = o.health || 'ok';

  const status = hidden.has('pulse') ? '' : `<p class="dash__status dash__status--${escapeHTML(health)}"><span class="dash__dot" aria-hidden="true"></span><strong>${escapeHTML(healthLabel(health))}</strong><span class="dash__sep" aria-hidden="true">·</span><span>${escapeHTML(pulseLine(o))}</span></p>`;
  const creators = [['invoices', STR.invoiceFormTitle], ['quotes', STR.quoteFormTitle], ['clients', STR.clientFormTitle], ['bookings', STR.bookingsNew]]
    .filter(([m]) => hasModule(m)).slice(0, 3);
  const actions = (hasModule('settings') ? `<button type="button" class="btn btn--sm btn--ghost" data-go="settings" data-settings="home">${escapeHTML(STR.customizeHome)}</button>` : '')
    + creators.map(([m, label], i) => `<button type="button" class="btn btn--sm${i === 0 ? ' btn--primary' : ''}" data-create="${m}"><span class="btn__plus" aria-hidden="true">+</span>${escapeHTML(label)}</button>`).join('');
  const today = new Date().toLocaleDateString(uiLocale(), { weekday: 'long', day: 'numeric', month: 'long', timeZone: tenantTz() });
  const head = `<header class="dash__head"><div><p class="dash__date">${escapeHTML(capFirst(today))}</p><h1 class="dash__title">${escapeHTML(dashGreeting())}</h1>${status}</div>${actions ? `<div class="dash__actions">${actions}</div>` : ''}</header>`;

  const kpis = hidden.has('highlights') ? [] : dashKpis(o, hidden);
  const kpiHtml = kpis.length ? `<section class="dash-kpis" aria-label="${escapeHTML(STR.dashKpisAria)}">${kpis.map(k => {
    const metaCls = k.trend === 'up' ? ' delta delta--up' : k.trend === 'down' ? ' delta delta--down' : k.metaTone === 'bad' ? ' dash-kpi__meta--bad' : '';
    const spark = k.spark && k.spark.some(v => v > 0) ? sparkline(k.spark) : '';
    return `<button type="button" class="dash-kpi" data-go="${escapeHTML(k.go)}"><span class="dash-kpi__label">${escapeHTML(k.label)}</span><span class="dash-kpi__value${k.valueTone === 'warn' ? ' dash-kpi__value--warn' : ''}">${escapeHTML(String(k.value))}</span><span class="dash-kpi__meta${metaCls}">${escapeHTML(k.meta || '')}</span>${spark}</button>`;
  }).join('')}</section>` : '';

  const setupCopy = { wa: STR.waConnect, ig: STR.igConnect, widget: STR.setupWidget, persona: STR.teachBot };
  const setup = hidden.has('setup') ? [] : (o.setup || []);
  const setupHtml = setup.length
    ? `<section class="panel panel--card"><header class="panel__head"><h2 class="panel__title">${escapeHTML(STR.setupTitle)}</h2></header><div class="panel__body"><div class="actions">${setup.map(s => `<button type="button" class="btn btn--sm" data-go="${escapeHTML(s.tab)}" data-settings="${escapeHTML(s.section || '')}">${escapeHTML(setupCopy[s.kind] || s.kind)} <span aria-hidden="true">→</span></button>`).join('')}</div></div></section>`
    : '';

  const cards = [];
  if (!hidden.has('attention')) {
    const items = dashAttention(o);
    const rows = items.map(n => {
      let meta = '';
      let when = '';
      if (n.amountCents != null) {
        meta = centsEUR(n.amountCents);
        when = n.aggregate || !n.at ? '' : relDay(n.at);
      } else if (['waiting_chat', 'instagram_comment', 'agent_approval', 'agent_failed'].includes(n.kind)) meta = relTime(n.at);
      else if (n.kind === 'pending_booking') meta = fmtWhen(n.at);
      else if (n.at) meta = relDay(n.at);
      const detail = n.aggregate && n.kind === 'overdue_invoice'
        ? (o.cash?.topOverdue || []).map(t => t.name).filter(Boolean).join(', ')
        : (n.kind === 'assistant_action' ? '' : n.detail || '');
      const opens = ['overdue_invoice', 'due_soon_invoice', 'overdue_payment', 'due_soon_payment', 'quote_expiring', 'pending_booking', 'service_submission',
        'project_delayed', 'site_issue_overdue', 'change_order_pending', 'measurement_to_invoice'].includes(n.kind);
      const agentRef = AGENT_ATTENTION_REFS[n.kind] && n.id ? `${AGENT_ATTENTION_REFS[n.kind]}:${n.id}` : '';
      return worklistRow({
        tone: n.kind === 'agent_task_due' && agentTaskLate(n) ? 'late' : attentionTone(n.kind), title: attentionTitle(n), detail, meta, when, amount: n.amountCents != null, go: n.tab,
        open: agentRef || (!n.aggregate && opens ? n.id || '' : ''), conversation: n.kind === 'waiting_chat' ? n.id || '' : '',
        settings: ATTENTION_SETTINGS[n.kind] || '',
      });
    }).join('');
    const body = rows
      ? `<ul class="worklist">${rows}</ul>`
      : `<div class="dash-empty dash-empty--center"><strong>${escapeHTML(STR.needsYouEmpty)}</strong><span>${escapeHTML(STR.needsYouEmptyDesc)}</span></div>`;
    cards.push({ col: 'main', order: 1, html: dashCard({ order: 1, title: STR.needsYou, tag: items.length || null, body, flush: !!rows }) });
  }
  if (o.calendar && !hidden.has('calendar')) cards.push({ col: 'side', order: 2, html: dashTodayCard(o) });
  if (o.projects && !hidden.has('projects')) cards.push({ col: 'main', order: 2, html: dashProjectsCard(o) });
  if ((o.cash || o.payments) && money) {
    cards.push({ col: 'main', order: 3, html: dashCashFlowCard(o) });
    cards.push({ col: 'side', order: 4, html: dashMoneyCard(o) });
  }
  if (o.pipeline && !hidden.has('pipeline')) cards.push({ col: 'main', order: 5, html: dashPipelineCard(o) });
  if (o.inbox && !hidden.has('inbox')) cards.push({ col: 'side', order: 6, html: dashInboxCard(o) });
  if (o.customers && o.cash && !hidden.has('customers')) cards.push({ col: 'main', order: 7, html: dashTopClientsCard(o) });
  const feedSources = [['invoices', 'financeiro'], ['payments', 'financeiro'], ['quotes', 'pipeline'], ['clients', 'customers'], ['bookings', 'calendar']];
  if (!hidden.has('recent') && feedSources.some(([m, card]) => hasModule(m) && !hidden.has(card))) cards.push({ col: 'side', order: 8, html: dashRecentCard(o) });
  if (o.agents && !hidden.has('agents')) cards.push({ col: 'side', order: 9, html: dashAgentsCard(o) });
  const count = col => cards.filter(c => c.col === col).length;
  const column = col => cards.filter(c => c.col === col).sort((a, b) => a.order - b.order).map(c => c.html).join('');
  const single = !count('main') || !count('side');
  const gridHtml = cards.length
    ? `<div class="dash-grid${single ? ' dash-grid--single' : ''}">${single
      ? `<div class="dash-col">${cards.sort((a, b) => a.order - b.order).map(c => c.html).join('')}</div>`
      : `<div class="dash-col">${column('main')}</div><div class="dash-col">${column('side')}</div>`}</div>`
    : '';

  const inKpis = new Set(kpis.map(k => k.go));
  const plus = n => (n ? tApp('kpiNewThisMonth', { n }) : '');
  const tiles = [
    o.customers && !hidden.has('customers') && !inKpis.has('clients') ? ['clients', STR.snapCustomers, o.customers.total, plus(o.customers.newThisMonth)] : null,
    o.services && !hidden.has('services') ? ['services', STR.servicesOpen, o.services.openCount, wholeCentsEUR(o.services.openCents)] : null,
    o.suppliers && !hidden.has('suppliers') ? ['suppliers', STR.snapSuppliers, o.suppliers.total, plus(o.suppliers.newThisMonth)] : null,
    o.employees && !hidden.has('employees')
      ? ['employees', STR.snapEmployees, o.employees.total, o.employees.pendingSubmissions ? SUB.toApproveN({ n: o.employees.pendingSubmissions }) : plus(o.employees.newThisMonth)]
      : null,
    o.catalog && !hidden.has('catalog') ? ['catalog', STR.snapCatalog, o.catalog.items, STR.catalogItems] : null,
    o.social && !hidden.has('social') ? ['instagram', STR.snapSocial, o.social.unreplied, o.social.connected ? STR.hl_instagram_unreplied : STR.notConnected] : null,
    o.assistant && !hidden.has('assistant') ? ['ai-assistant', STR.snapAssistant, o.assistant.pendingActions, STR.assistantPending] : null,
  ].filter(Boolean);
  const tilesHtml = tiles.length
    ? `<section class="dash-tiles">${tiles.map(([go, label, value, meta]) => `<button type="button" class="dash-tile" data-go="${escapeHTML(go)}"><span class="dash-tile__label">${escapeHTML(label)}</span><span class="dash-tile__value">${escapeHTML(String(value ?? 0))}</span>${meta ? `<span class="dash-tile__meta">${escapeHTML(meta)}</span>` : ''}</button>`).join('')}</section>`
    : '';

  const emptyHtml = !kpiHtml && !setupHtml && !gridHtml && !tilesHtml
    ? `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.overviewEmptyTitle)}</p><p class="empty__desc">${escapeHTML(STR.overviewEmptyDesc)}</p></div></div>`
    : '';
  root.innerHTML = `<div class="dash">${head}${kpiHtml}${setupHtml}${gridHtml}${tilesHtml}${emptyHtml}</div>`;
  balanceDashColumns(root);
  document.fonts?.ready.then(() => balanceDashColumns(root));
  $$('[data-go]', root).forEach(b => b.addEventListener('click', () => dashGo(b)));
  $$('[data-create]', root).forEach(b => b.addEventListener('click', () => dashCreate(b.dataset.create)));
}

// Card heights depend on data, so even out the two columns after layout. Needs you, Today and
// Cash flow keep their column; the narrow single-column layout orders cards by data-order instead.
function balanceDashColumns(root) {
  const grid = $('.dash-grid', root);
  if (!grid || grid.classList.contains('dash-grid--single') || getComputedStyle(grid).display !== 'grid') return;
  const [main, side] = $$('.dash-col', grid);
  const gap = parseFloat(getComputedStyle(main).rowGap) || 0;
  for (let pass = 0; pass < 3; pass++) {
    const diff = main.offsetHeight - side.offsetHeight;
    const [tall, short] = diff > 0 ? [main, side] : [side, main];
    let best = null;
    let bestDiff = Math.abs(diff);
    [...tall.children].filter(card => Number(card.dataset.order) >= 4).forEach(card => {
      const next = Math.abs(Math.abs(diff) - 2 * (card.offsetHeight + gap));
      if (next < bestDiff) { best = card; bestDiff = next; }
    });
    if (!best || Math.abs(diff) - bestDiff < 48) return;
    short.insertBefore(best, [...short.children].find(card => Number(card.dataset.order) > Number(best.dataset.order)) || null);
  }
}

async function dashGo(el) {
  const { go, open, conversation, settings } = el.dataset;
  if (conversation) state.selectedConversation = conversation;
  if (settings) state.settingsSection = settings;
  if (go === 'agents' && open) window.AgentsUI?.focusRef(open);
  await setActive(go);
  if (!open || state.active !== go) return;
  if (go === 'agents') return window.AgentsUI.openRef(open);
  if (go === 'invoices') return openInvoiceDetail(open);
  if (go === 'quotes') return openQuoteDetail(open);
  if (go === 'payments') return openPaymentDetail(open);
  if (go === 'clients') return openClientDrawer(open);
  if (go === 'bookings') return openBookingById(open);
  if (go === 'employees') return openSubmissionDetail(open);
  if (go === 'projects') return openProjectDrawer(open);
  if (go === 'site-issues') return openIssueDetail(open);
  if (go === 'change-orders') return openChangeDetail(open);
  if (go === 'measurements') return openMeasurementDetail(open);
}

async function dashCreate(module) {
  await setActive(module);
  if (state.active !== module) return;
  if (module === 'invoices') return openInvoiceForm();
  if (module === 'quotes') return openQuoteForm();
  if (module === 'clients') return openClientForm();
  if (module === 'bookings') return openBookingForm();
}
