/* Clients (/app → Clients): the list and its columns, the client record (profile, money, what needs
   attention, activity and a tab per module), the client form, and the fields each company asks for. */

// Empty-state illustration for the clients directory: a client, a spray bottle and sparkles.
// Strokes follow the text color; `.art-accent` shapes take the accent (see `.empty__art`).
const CLIENTS_EMPTY_ART = `<svg class="empty__art" viewBox="0 0 128 84" aria-hidden="true" focusable="false">
  <path class="art-accent" d="M71 8.5c.5 3.1 1.9 4.5 5 5-3.1.5-4.5 1.9-5 5-.5-3.1-1.9-4.5-5-5 3.1-.5 4.5-1.9 5-5z"/>
  <path class="art-accent" d="M29 43.5c.4 2.3 1.4 3.3 3.7 3.7-2.3.4-3.3 1.4-3.7 3.7-.4-2.3-1.4-3.3-3.7-3.7 2.3-.4 3.3-1.4 3.7-3.7z"/>
  <path class="art-accent" d="M105 25c.3 1.7 1.1 2.5 2.8 2.8-1.7.3-2.5 1.1-2.8 2.8-.3-1.7-1.1-2.5-2.8-2.8 1.7-.3 2.5-1.1 2.8-2.8z"/>
  <path class="art-accent" d="M84.5 62h13v10.5h-13z"/>
  <g class="art-line">
    <path d="M22 74h84"/>
    <circle cx="54" cy="36" r="9.5"/>
    <path d="M36 74c0-11 8-19 18-19s18 8 18 19"/>
    <path d="M82 74V59.5c0-2.2 1.3-4.1 3.2-5l1.3-.6V50h9v3.9l1.3.6c1.9.9 3.2 2.8 3.2 5V74"/>
    <path d="M86.5 50v-5.5c0-1.4 1.1-2.5 2.5-2.5h8.5l1.5 4v4"/>
    <path d="M86.5 44.5h-6.5"/>
    <path d="M80 43v3"/>
    <path d="M89 47.5c-2.6.3-4.1 1.8-4.4 4.4"/>
  </g>
</svg>`;

// What each client was billed and still owes, from the company's invoices (cancelled ones aside).
function clientBalances() {
  const today = todayKey();
  const byClient = new Map();
  for (const i of state.invoices) {
    if (i.status === 'CANCELLED' || !i.clientId) continue;
    const b = byClient.get(i.clientId) || { billed: 0, owes: 0, overdue: false };
    b.billed += Number(i.totalEur || 0);
    if (i.status === 'PENDING' || i.status === 'OVERDUE') {
      b.owes += invoiceOutstandingEur(i);
      if (isPastDue(i, today)) b.overdue = true;
    }
    byClient.set(i.clientId, b);
  }
  return byClient;
}
const NO_BALANCE = { billed: 0, owes: 0, overdue: false };
// overdue · owing · paid (billed, nothing owed) · none (never billed).
const clientStanding = b => (b.overdue ? 'overdue' : b.owes > 0.004 ? 'owing' : b.billed > 0 ? 'paid' : 'none');
const CLIENT_STANDING_TONES = { overdue: 'bad', owing: 'warn', paid: 'ok', none: '' };
const clientStandingPill = standing => `<span class="pill${CLIENT_STANDING_TONES[standing] ? ` pill--${CLIENT_STANDING_TONES[standing]}` : ''}">${escapeHTML(CRM.clients.standing[standing])}</span>`;
// A status tab keeps the clients it names; `owing` includes the overdue ones.
const CLIENT_STANDING_FILTERS = { owing: s => s === 'owing' || s === 'overdue', overdue: s => s === 'overdue', none: s => s === 'none' };

function clientColumns() {
  const t = CRM.clients;
  const muted = value => `<td class="muted">${escapeHTML(value || '')}</td>`;
  const mono = value => `<td class="mono muted nowrap">${escapeHTML(value || '')}</td>`;
  const balance = c => state.clientBalances?.get(c.id) || NO_BALANCE;
  const money = hasModule('invoices') ? [
    { id: 'standing', label: t.thStatus, default: true, cell: c => `<td>${clientStandingPill(clientStanding(balance(c)))}</td>` },
    { id: 'billed', label: t.thBilled, default: true, head: 'right', cell: c => `<td class="num right nowrap">${balance(c).billed ? fmtEUR(balance(c).billed) : '—'}</td>` },
    { id: 'owes', label: t.thOwes, default: true, head: 'right', cell: c => `<td class="num right nowrap${balance(c).overdue ? ' is-bad' : ''}">${balance(c).owes > 0.004 ? fmtEUR(balance(c).owes) : '—'}</td>` },
  ] : [];
  return [
    selectColumn('clients'),
    { id: 'name', label: t.thName, fixed: true, cell: c => `<td class="name long">${nameWithInitials(c.name, true)}</td>` },
    { id: 'contactPerson', label: standardClientFieldLabel('contactPerson'), cell: c => muted(c.contactPerson) },
    { id: 'address', label: t.thAddress, cell: (c, ids) => `<td class="muted long">${escapeHTML(ids.has('city') ? c.address || '' : [c.address, c.city].filter(Boolean).join(', '))}</td>` },
    { id: 'postalCode', label: standardClientFieldLabel('postalCode'), cell: c => mono(c.postalCode) },
    { id: 'city', label: standardClientFieldLabel('city'), cell: c => muted(c.city) },
    { id: 'email', label: standardClientFieldLabel('email'), default: true, cell: c => muted(c.email) },
    { id: 'phone', label: t.thPhone, default: true, cell: c => mono(c.phone) },
    { id: 'taxId', label: standardClientFieldLabel('taxId'), cell: c => mono(c.taxId) },
    ...clientCustomFields().map(f => ({
      id: f.key, label: f.label, default: f.showInList, custom: true, head: f.type === 'NUMBER' ? 'right' : '',
      cell: c => `<td class="${{ NUMBER: 'num nowrap', DATE: 'mono muted nowrap', CHECKBOX: 'muted' }[f.type] || 'muted long'}">${escapeHTML(customFieldText(f, c.customFields?.[f.key]))}</td>`,
    })),
    ...money,
    { id: 'createdAt', label: t.thSince, default: true, cell: c => `<td class="mono muted nowrap">${fmtDay(c.createdAt)}</td>` },
    { id: 'number', label: t.thNo, head: 'right', cell: c => `<td class="id right">${escapeHTML(c.number)}</td>` },
  ];
}

function renderClients(root) {
  const t = CRM.clients;
  const archived = state.archivedView === 'clients';
  const source = archived ? state.archivedRows : state.clients;
  const q = state.search.trim().toLowerCase();
  const qDigits = phoneDigits(q);
  const custom = clientCustomFields();
  const columns = shownColumns('clients', clientColumns());
  const ids = new Set(columns.map(c => c.id));
  const matches = c => !q
    || `${c.number || ''} ${c.name || ''} ${c.phone || ''} ${fullAddress(c)} ${c.contactPerson || ''} ${c.email || ''} ${c.taxId || ''} ${custom.map(f => customFieldText(f, c.customFields?.[f.key])).join(' ')}`.toLowerCase().includes(q)
    || (qDigits.length >= 3 && qDigits === q.replace(/[\s+()-]/g, '') && phoneDigits(c.phone).includes(qDigits));
  const money = hasModule('invoices') && !!state.clientBalances;
  state.clientBalances = money ? clientBalances() : null;
  const standingOf = c => clientStanding(state.clientBalances?.get(c.id) || NO_BALANCE);
  const filter = !archived && money ? CLIENT_STANDING_FILTERS[state.filterClientStanding] : null;
  const shown = source.filter(c => !filter || filter(standingOf(c)));
  const rows = shown
    .filter(matches)
    .sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), uiLocale(), { sensitivity: 'base' }))
    .map(c => `<tr class="conversation-row" data-client="${escapeHTML(c.id)}">${columnCells(columns, c, ids)}</tr>`)
    .join('');
  // Tabs: every client, then by what they owe (with the invoices module), then the archived ones.
  const tab = (key, label, n, on) => `<button class="chip ${on ? 'is-on' : ''}" type="button" data-client-tab="${key}">${escapeHTML(label)}${n == null ? '' : chipCount(n)}</button>`;
  const standingTab = (key, label) => tab(key, label, state.clients.filter(c => CLIENT_STANDING_FILTERS[key](standingOf(c))).length, !archived && state.filterClientStanding === key);
  const tabs = tab('', t.tabAll, state.clients.length, !archived && !filter)
    + (money ? standingTab('owing', t.tabOwing) + standingTab('overdue', t.tabOverdue) + standingTab('none', t.tabNotBilled) : '')
    + tab('archived', STR.directoryArchived, archived ? source.length : null, archived);
  pageCount(shown.length);
  root.innerHTML = hero(labels.clients, CRM.tabs.clientes.desc) + crmPanel({
    title: '',
    tools: tabs + `<span class="panel__tools-end">${columnsButton('clients') + columnOrderButton('clients')
      + (state.clientFields?.canEdit ? `<button class="btn btn--sm btn--ghost" type="button" data-client-fields>${escapeHTML(FIELDS.open)}</button>` : '')}</span>`,
    bar: selectionBar('clients'),
    head: columnsHead(columns, 'clients'),
    rows,
    empty: archived ? STR.directoryArchivedEmpty : t.emptyTitle,
    emptyDesc: archived ? STR.directoryArchivedEmptyDesc : t.emptyDesc,
    emptyArt: CLIENTS_EMPTY_ART,
  });
  $$('[data-client]', root).forEach(r => r.addEventListener('click', () => openClientDrawer(source.find(c => c.id === r.dataset.client) || r.dataset.client)));
  $('[data-client-fields]', root)?.addEventListener('click', () => openClientFieldsEditor());
  $('[data-columns]', root)?.addEventListener('click', () => openColumnPicker('clients', clientColumns(), labels.clients));
  wireColumnOrder(root, 'clients', clientColumns());
  wireSelection(root, 'clients', shown.map(c => c.id));
  $$('[data-client-tab]', root).forEach(b => b.addEventListener('click', () => {
    const key = b.dataset.clientTab;
    if (key === 'archived') return showDirectoryView('clients');
    state.filterClientStanding = key;
    if (archived) return showDirectoryView('');
    render();
  }));
}

// ── Client record ──────────────────────────────────────────────────────────────
// A client opens as a record: who they are and how to reach them, what they owe, what's next, then
// the full history. Edit is one click away. Drawers opened from here come back to it (drawerTrail).
let clientRecord = null;
// "Rua das Flores 12, 1200-001 Lisboa": the street, then postal code and city.
const fullAddress = r => [r.address, [r.postalCode, r.city].filter(Boolean).join(' ')].filter(Boolean).join(', ');

function matchConversation(client, list) {
  const key = phoneKey(client.phone);
  if (key.length < 6) return null;
  return list
    .filter(c => c.channel === 'WHATSAPP' && phoneKey(c.waId) === key)
    .sort((a, b) => String(b.lastMessageAt || '').localeCompare(String(a.lastMessageAt || '')))[0] || null;
}

// The booking form and legacy booking prices read the bookable services and opening hours,
// which only the Bookings page loads.
async function ensureBookingData() {
  if (!hasModule('bookings') || state.bookingServices.length) return;
  const [services, availability] = await Promise.all([
    api('/app/api/bookings/services').catch(() => []),
    api('/app/api/bookings/availability').catch(() => []),
  ]);
  state.bookingServices = services;
  state.bookingAvailability = availability;
}

async function loadClientRecord(id) {
  const q = encodeURIComponent(id);
  const related = (module, path) => (hasModule(module) ? api(path).catch(() => []) : Promise.resolve([]));
  const conversations = !hasModule('conversations') ? Promise.resolve([])
    : state.fetched.conversations ? Promise.resolve(state.conversations)
    : api('/app/api/conversations').catch(() => []);
  // Mail is kept only while an account is connected, so without one there's no Emails tab.
  const emails = emailStatus().then(s => (s?.configured && s.from ? api(`/app/api/email/messages?clientId=${q}`).catch(() => null) : null));
  const [client, quotes, invoices, services, bookings, payments, chats, automations, mail] = await Promise.all([
    api(`/app/api/crm/clients/${q}`).catch(() => null),
    related('quotes', `/app/api/crm/quotes?clientId=${q}`),
    related('invoices', `/app/api/crm/invoices?clientId=${q}`),
    related('services', `/app/api/crm/services?clientId=${q}`),
    related('bookings', `/app/api/bookings?clientId=${q}`),
    related('payments', `/app/api/crm/payments?clientId=${q}`),
    conversations,
    hasModule('agents') ? api(`/app/api/agents/subjects/client/${q}`).catch(() => null) : null,
    emails,
    ensureBookingData(),
    loadClientFields(),
    // The Items tab names the client's catalog items from the catalog.
    hasModule('catalog') && !state.catalog.length ? api('/app/api/crm/standard-items').then(c => { state.catalog = c; }).catch(() => {}) : null,
  ]);
  return {
    client,
    quotes: quotes || [],
    invoices: invoices || [],
    services: services || [],
    bookings: bookings || [],
    payments: payments || [],
    conversation: client ? matchConversation(client, chats || []) : null,
    automations,
    emails: mail,
  };
}

function clientMoney(r) {
  const today = todayKey();
  const live = r.invoices.filter(i => i.status !== 'CANCELLED');
  const unpaid = live.filter(i => i.status === 'PENDING' || i.status === 'OVERDUE');
  const overdue = unpaid.filter(i => isPastDue(i, today)).sort((a, b) => String(a.dueDate).localeCompare(String(b.dueDate)));
  const openWork = r.services.filter(s => s.status === 'OPEN');
  const openQuotes = r.quotes.filter(q => q.status === 'PENDENTE' || q.status === 'SENT');
  return {
    billed: sumBy(live, 'totalEur'),
    paid: live.reduce((t, i) => t + invoicePaidEur(i), 0),
    unpaid,
    outstanding: unpaid.reduce((t, i) => t + invoiceOutstandingEur(i), 0),
    overdue,
    overdueTotal: overdue.reduce((t, i) => t + invoiceOverdueEur(i, today), 0),
    openWork,
    openWorkTotal: sumBy(openWork, 'totalEur'),
    openQuotes,
    openQuotesTotal: sumBy(openQuotes, 'totalEur'),
  };
}

function clientBookingsSplit(r) {
  const now = Date.now();
  const sorted = [...r.bookings].sort((a, b) => a.startAt.localeCompare(b.startAt));
  const isLive = b => b.status === 'PENDING' || b.status === 'CONFIRMED';
  const upcoming = sorted.filter(b => isLive(b) && new Date(b.endAt || b.startAt).getTime() >= now);
  return {
    upcoming,
    past: sorted.filter(b => !upcoming.includes(b)).reverse(),
    // Staff never marked these done or no-show, so they were never billed either.
    unclosed: sorted.filter(b => isLive(b) && new Date(b.endAt || b.startAt).getTime() < now),
    visits: r.bookings.filter(b => b.status === 'COMPLETED').length,
    noShows: r.bookings.filter(b => b.status === 'NO_SHOW').length,
  };
}

function clientLastActivity(r) {
  const now = Date.now();
  return [
    ...r.bookings.filter(b => b.status !== 'CANCELLED').map(b => b.startAt),
    ...r.services.map(s => s.performedAt || s.createdAt),
    ...r.quotes.map(q => q.createdAt),
    ...r.invoices.flatMap(i => [i.createdAt, ...invoiceReceipts(i).map(x => x.at)]),
    r.conversation?.lastMessageAt,
  ].filter(s => s && new Date(s).getTime() <= now).sort().pop() || null;
}

// "3/2/25 · 4 visits · last activity 2 days ago", under the Client since label.
function clientSinceText(r, bk) {
  const last = clientLastActivity(r);
  return [
    fmtDay(r.client.createdAt),
    bk.visits ? STR.clientVisits({ n: bk.visits }) : '',
    bk.noShows ? STR.bookingsClientNoShows({ n: bk.noShows }) : '',
    last ? STR.clientLastActivity({ when: relTime(last) }) : '',
  ].filter(Boolean).join(' · ');
}

// Under the drawer's head: call, WhatsApp and the chat, then edit and archive or delete.
function clientBarHtml(r) {
  const c = r.client;
  const chat = r.conversation
    ? `<button class="btn btn--sm" type="button" data-record-open="chat">${escapeHTML(STR.clientOpenChat)}${r.conversation.waiting ? ` <span class="pill pill--warn">${escapeHTML(STR.waiting)}</span>` : ''}</button>`
    : '';
  return `${recordBarHtml({
    name: c.name,
    contact: contactButtons(c.phone, chat),
    end: `<button class="btn btn--sm btn--ghost" type="button" data-record-edit>${escapeHTML(STR.clientEditAction)}</button>${recordRemovalButton(c)}`,
  })}
    ${c.archivedAt ? `<p class="hint hint--warn">${escapeHTML(STR.recordArchivedOn({ date: fmtDay(c.archivedAt) }))}</p>` : ''}
    ${c.automationPaused && hasModule('agents') ? `<p class="hint hint--warn">${escapeHTML(I18N.t('app.agents.automations.pausedTitle'))}</p>` : ''}`;
}

// The Overview tab: who the client is and where they stand, as one list of fields, the notes, then what needs attention.
function clientOverviewHtml(r, m, bk) {
  const c = r.client;
  const t = CRM.clients;
  const address = fullAddress(c);
  const fields = recordFieldsHtml([
    hasModule('invoices') ? [t.thStatus, clientStandingPill(clientStanding({ billed: m.billed, owes: m.outstanding, overdue: m.overdue.length > 0 }))] : null,
    [standardClientFieldLabel('phone'), `<span class="mono">${escapeHTML(c.phone)}</span>`],
    c.email ? [standardClientFieldLabel('email'), `<a href="mailto:${escapeHTML(c.email)}">${escapeHTML(c.email)}</a>`] : null,
    c.contactPerson ? [standardClientFieldLabel('contactPerson'), escapeHTML(c.contactPerson)] : null,
    address ? [t.thAddress, `<a href="${escapeHTML(mapsHref(address))}" target="_blank" rel="noopener">${escapeHTML(address)}</a>`] : null,
    c.taxId ? [standardClientFieldLabel('taxId'), `<span class="mono">${escapeHTML(c.taxId)}</span>`] : null,
    ...clientCustomFields().map(f => [f.label, escapeHTML(customFieldText(f, c.customFields?.[f.key]))]),
    ...kpiFieldRows(clientKpiCells(m, bk)),
    [t.thSince, escapeHTML(clientSinceText(r, bk))],
  ]);
  const notes = c.notes ? `<div class="record-card__notes"><span class="record-card__notes-label">${escapeHTML(STR.clientFormNotes)}</span>${escapeHTML(c.notes)}</div>` : '';
  return `<div class="record__stack">${fields}${notes}${recordAttentionHtml(clientAttention(r, m, bk))}</div>`;
}

function clientKpiCells(m, bk) {
  const cells = [];
  if (hasModule('invoices')) {
    cells.push({
      label: STR.clientKpiOutstanding,
      value: fmtEUR(m.outstanding),
      sub: m.overdueTotal ? STR.clientKpiOverdue({ amount: fmtEUR(m.overdueTotal) })
        : m.unpaid.length ? STR.clientKpiUnpaid({ n: m.unpaid.length }) : STR.clientKpiNothingDue,
      tone: m.overdueTotal ? 'bad' : m.outstanding ? 'warn' : '',
    });
  }
  if (hasModule('bookings')) {
    const next = bk.upcoming[0];
    const key = next ? bookingLocal(next).slice(0, 10) : '';
    const relative = next ? relativeDayLabel(key) : '';
    cells.push({
      label: STR.clientKpiNextBooking,
      value: next ? relative || fmtDayKey(key, { day: 'numeric', month: 'short' }) : '—',
      sub: next
        ? [relative ? '' : capFirst(fmtDayKey(key, { weekday: 'short' })), fmtTime(next.startAt), bookingServiceName(next)].filter(Boolean).join(' · ')
        : STR.clientKpiNoBooking,
    });
  }
  if (hasModule('services')) {
    cells.push({
      label: STR.clientKpiUnbilled,
      value: fmtEUR(m.openWorkTotal),
      sub: STR.clientKpiServices({ n: m.openWork.length }),
      tone: m.openWork.length ? 'warn' : '',
    });
  }
  if (hasModule('invoices')) cells.push({ label: STR.clientKpiBilled, value: fmtEUR(m.billed), sub: STR.clientKpiPaid({ amount: fmtEUR(m.paid) }) });
  if (hasModule('quotes')) {
    cells.push({
      label: STR.clientKpiQuotes,
      value: fmtEUR(m.openQuotesTotal),
      sub: STR.clientKpiOpenQuotes({ n: m.openQuotes.length }),
      tone: m.openQuotes.length ? 'info' : '',
    });
  }
  return cells;
}

function clientAttention(r, m, bk) {
  const today = todayKey();
  const items = [];
  m.overdue.forEach(i => items.push({
    tone: 'bad', title: STR.clientAttnOverdue({ number: i.number }), detail: STR.clientAttnOverdueDetail({ when: relDay(i.dueDate) }),
    amount: invoiceOverdueEur(i, today), open: `invoice:${i.id}`,
  }));
  bk.unclosed.forEach(b => items.push({
    tone: 'late', title: STR.clientAttnUnclosed, detail: [bookingWhen(b), bookingServiceName(b)].filter(Boolean).join(' · '), open: `booking:${b.id}`,
  }));
  bk.upcoming.filter(b => b.status === 'PENDING').forEach(b => items.push({
    tone: 'warn', title: STR.clientAttnPendingBooking, detail: [bookingWhen(b), bookingServiceName(b)].filter(Boolean).join(' · '), open: `booking:${b.id}`,
  }));
  if (m.openWork.length) {
    items.push({
      tone: 'warn', title: STR.clientAttnUnbilled({ n: m.openWork.length }), detail: m.openWork.map(s => s.name).join(', '),
      amount: m.openWorkTotal, open: hasModule('invoices') && !r.client.archivedAt ? 'invoice-open-work' : 'tab:services',
    });
  }
  m.openQuotes.forEach(q => {
    const expired = !!q.validUntil && q.validUntil.slice(0, 10) < today;
    items.push({
      tone: expired ? 'late' : 'info',
      title: q.status === 'SENT' ? STR.clientAttnQuote({ number: q.number }) : STR.clientAttnQuoteDraft({ number: q.number }),
      detail: q.validUntil
        ? (expired ? STR.clientAttnQuoteExpired({ when: relDay(q.validUntil) }) : STR.clientValidUntil({ date: fmtDay(q.validUntil) }))
        : STR.clientCreatedOn({ date: fmtDay(q.createdAt) }),
      amount: q.totalEur, open: `quote:${q.id}`,
    });
  });
  const soon = addDayKey(today, 7);
  m.unpaid.filter(i => !isPastDue(i, today) && i.dueDate && i.dueDate.slice(0, 10) <= soon).forEach(i => items.push({
    tone: 'warn', title: STR.clientAttnDueSoon({ number: i.number }), detail: STR.clientDueWhen({ when: relDay(i.dueDate) }),
    amount: invoiceNextDueEur(i), open: `invoice:${i.id}`,
  }));
  return items;
}

function clientActivity(r, bk) {
  const upcoming = bk.upcoming.map(b => ({
    tone: bookingTone(b.status) || 'neutral', title: bookingServiceName(b) || STR.clientKindBooking,
    detail: `${STR.clientKindBooking} · ${bookingWhen(b)}`, pill: bookingPill(b.status), amount: bookingPrice(b), open: `booking:${b.id}`,
  }));
  const past = [];
  bk.past.forEach(b => past.push({
    at: b.startAt, tone: bookingTone(b.status) || 'muted', title: bookingServiceName(b) || STR.clientKindBooking,
    detail: `${STR.clientKindBooking} · ${bookingWhen(b)}`, pill: bookingPill(b.status), amount: bookingPrice(b), open: `booking:${b.id}`,
  }));
  r.services.forEach(s => past.push({
    at: s.performedAt || s.createdAt, tone: { OPEN: 'warn', INVOICED: 'ok' }[s.status] || 'muted', title: s.name,
    detail: [STR.clientKindService, fmtDay(s.performedAt || s.createdAt), s.bookingId ? CRM.services.fromBooking : ''].filter(Boolean).join(' · '),
    pill: servicePill(s.status), amount: s.totalEur, open: `service:${s.id}`,
  }));
  r.quotes.forEach(q => past.push({
    at: q.createdAt, tone: quoteTone(q.status) || 'accent', title: STR.clientEvtQuote({ number: q.number }),
    detail: STR.clientCreatedOn({ date: fmtDay(q.createdAt) }), pill: quotePill(q.status), amount: q.totalEur, open: `quote:${q.id}`,
  }));
  r.invoices.forEach(i => {
    const status = effectiveStatus(i);
    past.push({
      at: i.createdAt, tone: invoiceTone(status) || 'muted', title: STR.clientEvtInvoice({ number: i.number }),
      detail: STR.clientIssuedOn({ date: fmtDay(i.createdAt) }), pill: invoicePill(status), amount: i.totalEur, open: `invoice:${i.id}`,
    });
    invoiceReceipts(i).forEach(x => past.push({
      at: x.at, tone: 'ok', detail: fmtDay(x.at), amount: x.totalEur, open: `invoice:${i.id}`,
      title: x.of ? STR.clientEvtInstallmentPaid({ number: i.number, n: x.part, of: x.of }) : STR.clientEvtPaid({ number: i.number }),
    }));
  });
  if (r.conversation) {
    past.push({
      at: r.conversation.lastMessageAt, tone: r.conversation.waiting ? 'warn' : 'info', title: STR.clientEvtChat,
      detail: r.conversation.lastPreview || '', when: relTime(r.conversation.lastMessageAt), open: 'chat',
    });
  }
  past.sort((a, b) => String(b.at || '').localeCompare(String(a.at || '')));
  return { upcoming, past: past.slice(0, 40) };
}

// What the client paid against what was spent on them: payments count only once linked to the client.
function clientFinance(r, m) {
  const expenses = r.payments.filter(p => p.status !== 'CANCELLED');
  const toPay = expenses.filter(p => p.status === 'PENDING' || p.status === 'OVERDUE');
  const spent = sumBy(expenses.filter(p => p.status === 'PAID'), 'totalEur');
  return { expenses, spent, toPay: sumBy(toPay, 'totalEur'), net: m.paid - spent };
}

function clientFinanceHtml(r, m, act) {
  const t = CRM.financeiro;
  const f = clientFinance(r, m);
  const payments = hasModule('payments');
  const invoices = r.invoices.filter(i => i.status !== 'CANCELLED');
  const addExpense = payments && act('expense');
  if (!invoices.length && !f.expenses.length) {
    return `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.clientFinEmpty)}</p>
      <p class="empty__desc">${escapeHTML(payments ? STR.clientFinExpensesHint : STR.clientFinEmptyDesc)}</p>
      ${addExpense ? `<button class="btn btn--sm" type="button" data-record-act="expense"><span class="btn__plus">+</span> ${escapeHTML(STR.clientAddExpense)}</button>` : ''}</div></div>`;
  }
  const cells = [];
  if (hasModule('invoices')) {
    cells.push({ label: t.received, value: fmtEUR(m.paid), sub: m.outstanding ? STR.clientFinToReceive({ amount: fmtEUR(m.outstanding) }) : STR.clientKpiNothingDue });
  }
  if (payments) {
    cells.push({
      label: t.spent,
      value: fmtEUR(f.spent),
      sub: f.toPay ? STR.clientFinToPay({ amount: fmtEUR(f.toPay) }) : f.expenses.length ? STR.clientFinNothingToPay : STR.clientFinNoExpenses,
    });
    cells.push({
      label: t.net,
      value: fmtEUR(f.net),
      sub: m.paid > 0 ? STR.clientFinMargin({ pct: Math.round((f.net / m.paid) * 100) }) : STR.clientFinNoMargin,
      tone: f.net < 0 ? 'bad' : '',
    });
  }
  const moves = [
    ...invoices.map(i => ({ i, status: effectiveStatus(i) })).map(({ i, status }) => ({
      at: status === 'PAID' && i.paidAt ? i.paidAt : i.dueDate, out: false, open: `invoice:${i.id}`,
      title: STR.clientEvtInvoice({ number: i.number }), detail: '', pill: invoicePill(status), amount: i.totalEur, status,
    })),
    ...f.expenses.map(p => ({ p, status: effectiveStatus(p) })).map(({ p, status }) => ({
      at: status === 'PAID' && p.paidAt ? p.paidAt : p.dueDate, out: true, open: `payment:${p.id}`,
      title: STR.clientEvtExpense({ number: p.number }), detail: payeeName(p), pill: paymentPill(status), amount: p.totalEur, status,
    })),
  ].sort((a, b) => String(b.at || '').localeCompare(String(a.at || '')));
  const rows = moves.map(mv => `<tr class="conversation-row${{ PAID: ' is-paid', OVERDUE: ' is-overdue' }[mv.status] || ''}" data-record-open="${escapeHTML(mv.open)}">
    <td class="mono">${escapeHTML(fmtDay(mv.at))}</td>
    <td class="name">${escapeHTML(mv.title)}${mv.detail ? ` <span class="muted">· ${escapeHTML(mv.detail)}</span>` : ''}</td>
    <td>${mv.pill}</td>
    <td class="num right">${mv.out ? '−' : ''}${fmtEUR(mv.amount)}</td></tr>`).join('');
  const foot = [
    m.paid || f.spent ? `<button class="btn btn--sm btn--ghost" type="button" data-record-act="finance-page">${escapeHTML(STR.clientFinOpenPage)}</button>` : '',
    addExpense ? `<button class="btn btn--sm" type="button" data-record-act="expense"><span class="btn__plus">+</span> ${escapeHTML(STR.clientAddExpense)}</button>` : '',
  ].join('');
  return `<div class="record__stack">
    ${recordKpisHtml(cells)}
    ${payments && !f.expenses.length ? `<p class="hint">${escapeHTML(STR.clientFinExpensesHint)}</p>` : ''}
    ${recordTable([[STR.clientThDate], [STR.clientFinThMove], [CRM.invoices.thStatus], [t.thTotal, 'right']], rows)}
    ${foot ? `<div class="record__pane-foot">${foot}</div>` : ''}
  </div>`;
}

function clientPaneHtml(r, m, bk) {
  // An archived client gets no new documents until it is restored.
  const act = kind => (r.client.archivedAt ? null : kind);
  if (r.tab === 'overview') return clientOverviewHtml(r, m, bk);
  if (r.tab === 'automations') return '<div data-client-automations></div>';
  if (r.tab === 'finance') return clientFinanceHtml(r, m, act);
  if (r.tab === 'emails') {
    if (!r.emails?.length) return `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(GOOGLE.empty)}</p><p class="empty__desc">${escapeHTML(GOOGLE.emptyDesc)}</p></div></div>`;
    return `<div class="panel"><ul class="worklist">${r.emails.map(emailRowHtml).join('')}</ul></div>`;
  }
  if (r.tab === 'bookings') {
    if (!r.bookings.length) return recordEmpty(STR.clientNoBookings, act('booking'), STR.bookingsNewForClient);
    const row = b => `<tr class="conversation-row${b.status === 'CANCELLED' ? ' is-draft' : ''}" data-record-open="booking:${escapeHTML(b.id)}">
      <td>${escapeHTML(bookingWhen(b))}</td><td class="name">${escapeHTML(bookingServiceName(b))}</td><td>${bookingPill(b.status)}</td>
      <td class="num right">${bookingPrice(b) != null ? fmtEUR(bookingPrice(b)) : ''}</td></tr>`;
    const group = (label, list) => (list.length ? `<tr class="is-day"><td colspan="4">${escapeHTML(label)}</td></tr>${list.map(row).join('')}` : '');
    return recordTable(
      [[STR.bookingsThWhen], [STR.bookingsThService], [STR.bookingsThStatus], [CRM.quotes.thTotal, 'right']],
      group(STR.clientUpcoming, bk.upcoming) + group(STR.clientPast, bk.past),
    );
  }
  if (r.tab === 'items') {
    const t = CRM.services;
    const items = clientCatalogItems(r.client);
    const add = act('items') ? clientItemsAddHtml(items) : '';
    const foot = add ? `<div class="record__pane-foot">${add}</div>` : '';
    if (!items.length) return `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(t.clientItemsEmpty)}</p><p class="empty__desc">${escapeHTML(t.clientItemsEmptyDesc)}</p></div></div>${foot}`;
    return recordTable(clientItemsHead(), clientItemsRowsHtml(items)) + foot;
  }
  if (r.tab === 'services') {
    if (!r.services.length) return recordEmpty(STR.clientNoServices, act('service'), CRM.services.addForClient);
    const t = CRM.services;
    const rows = [...r.services]
      .sort((a, b) => String(b.performedAt || b.createdAt).localeCompare(String(a.performedAt || a.createdAt)))
      .map(s => `<tr class="conversation-row${s.status === 'CANCELLED' ? ' is-draft' : s.status === 'INVOICED' ? ' is-paid' : ''}" data-record-open="service:${escapeHTML(s.id)}">
        <td class="mono">${escapeHTML(fmtDay(s.performedAt || s.createdAt))}</td>
        <td class="name"><span class="svc-name" title="${escapeHTML(serviceRowText(s))}">${escapeHTML(serviceRowText(s))}${s.bookingId ? ` <span class="muted">· ${escapeHTML(t.fromBooking)}</span>` : ''}</span></td>
        <td>${servicePill(s.status)}</td><td class="num right">${fmtEUR(s.totalEur)}</td></tr>`).join('');
    const invoiceOpen = m.openWork.length && hasModule('invoices') && act('invoice')
      ? `<div class="record__pane-foot"><button class="btn btn--sm btn--accent" type="button" data-record-act="invoice-open-work">${escapeHTML(STR.clientInvoiceOpenWork({ amount: fmtEUR(m.openWorkTotal) }))}</button></div>` : '';
    return recordTable([[t.thWhen], [t.thService], [t.thStatus], [t.thTotal, 'right']], rows) + invoiceOpen;
  }
  if (r.tab === 'quotes') {
    if (!r.quotes.length) return recordEmpty(STR.clientNoQuotes, act('quote'), CRM.tabs.orcamentos.newLabel);
    const t = CRM.quotes;
    const rows = r.quotes.map(q => `<tr class="conversation-row${q.status === 'ACEITO' ? ' is-paid' : ''}" data-record-open="quote:${escapeHTML(q.id)}">
      <td class="id">${escapeHTML(q.number)}</td><td class="mono">${escapeHTML(fmtDay(q.createdAt))}</td>
      <td class="mono muted">${escapeHTML(q.validUntil ? fmtDay(q.validUntil) : '—')}</td><td>${quotePill(q.status)}</td>
      <td class="num right">${fmtEUR(q.totalEur)}</td></tr>`).join('');
    return recordTable([[t.thNumber], [STR.clientThDate], [t.thValidUntil], [t.thStatus], [t.thTotal, 'right']], rows);
  }
  if (r.tab === 'invoices') {
    if (!r.invoices.length) return recordEmpty(STR.clientNoInvoices, act('invoice'), CRM.tabs.faturas.newLabel);
    const t = CRM.invoices;
    const rows = r.invoices.map(i => {
      const status = effectiveStatus(i);
      const marker = { PAID: ' is-paid', OVERDUE: ' is-overdue', CANCELLED: ' is-draft' }[status] || '';
      return `<tr class="conversation-row${marker}" data-record-open="invoice:${escapeHTML(i.id)}">
        <td class="id">${escapeHTML(i.number)}</td><td class="mono">${escapeHTML(fmtDay(i.createdAt))}</td>
        <td class="mono muted">${escapeHTML(fmtDay(i.dueDate))}</td><td>${invoicePill(status)}</td><td class="num right">${invoiceTotalCell(i)}</td></tr>`;
    }).join('');
    return recordTable([[t.thNumber], [STR.clientThDate], [t.thDueDate], [t.thStatus], [t.thTotal, 'right']], rows);
  }
  const ev = clientActivity(r, bk);
  if (!ev.upcoming.length && !ev.past.length) {
    return `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.clientActivityEmpty)}</p><p class="empty__desc">${escapeHTML(STR.clientActivityEmptyDesc)}</p></div></div>`;
  }
  const group = (label, list) => (list.length ? `<li class="worklist__group">${escapeHTML(label)}</li>${list.map(recordRowHtml).join('')}` : '');
  return `<div class="panel"><ul class="worklist">${group(STR.clientUpcoming, ev.upcoming)}${group(STR.clientHistory, ev.past)}</ul></div>`;
}

function clientTabs(r) {
  const tabs = [['overview', CRM.clients.recordOverview, 0], ['activity', STR.clientTabActivity, 0]];
  if (hasModule('invoices') || hasModule('payments')) tabs.push(['finance', labels.financeiro, 0]);
  if (hasModule('bookings')) tabs.push(['bookings', labels.bookings, r.bookings.length]);
  if (hasModule('services')) tabs.push(['services', labels.services, r.services.length]);
  if (hasModule('quotes')) tabs.push(['quotes', labels.quotes, r.quotes.length]);
  if (hasModule('catalog')) tabs.push(['items', CRM.services.clientItemsTab, clientCatalogItems(r.client).length]);
  if (hasModule('invoices')) tabs.push(['invoices', labels.invoices, r.invoices.length]);
  if (r.emails) tabs.push(['emails', GOOGLE.tab, r.emails.length]);
  if (hasModule('agents')) tabs.push(['automations', I18N.t('app.agents.automations.title'), (r.automations?.upcoming?.length || 0) + (r.automations?.tasks?.length || 0)]);
  if (!tabs.some(([id]) => id === r.tab)) r.tab = 'overview';
  return recordTabsHtml(tabs, r.tab);
}

function renderClientRecord(focusTab = false) {
  const r = clientRecord;
  const body = r?.el;
  if (!body || !body.isConnected) return;
  const m = clientMoney(r);
  const bk = clientBookingsSplit(r);
  const acts = [
    hasModule('bookings') ? ['booking', STR.bookingsNewForClient] : null,
    hasModule('services') ? ['service', CRM.services.addForClient] : null,
    hasModule('quotes') ? ['quote', CRM.tabs.orcamentos.newLabel] : null,
    hasModule('invoices') ? ['invoice', CRM.tabs.faturas.newLabel] : null,
  ].filter(Boolean);
  body.innerHTML = `
    ${clientBarHtml(r)}
    ${clientTabs(r)}
    <div class="record__pane" role="tabpanel">${clientPaneHtml(r, m, bk)}</div>
    ${r.client.archivedAt ? '' : recordFootHtml(acts)}`;
  $('[data-record-edit]', body)?.addEventListener('click', () => openFromClient(() => openClientForm(r.client)));
  const setArchivedAt = archivedAt => { r.client = { ...r.client, archivedAt }; renderClientRecord(); refreshDirectory('clients'); };
  $('[data-record-remove]', body)?.addEventListener('click', () => removeDirectoryRecord('clients', r.client, {
    inUse: r.quotes.length + r.invoices.length + r.services.length + r.bookings.length + r.payments.length > 0,
    archiveBody: STR.clientArchiveBody,
    onDeleted: () => { closeDrawer({ dismissed: true }); refreshDirectory('clients'); },
    onArchived: setArchivedAt,
  }));
  $('[data-record-restore]', body)?.addEventListener('click', () => restoreDirectoryRecord('clients', r.client, () => setArchivedAt(null)));
  $$('[data-record-tab]', body).forEach(b => b.addEventListener('click', () => { r.tab = b.dataset.recordTab; renderClientRecord(true); }));
  $$('[data-record-open]', body).forEach(el => el.addEventListener('click', () => openClientItem(el.dataset.recordOpen)));
  $$('[data-record-act]', body).forEach(b => b.addEventListener('click', () => clientAct(b.dataset.recordAct)));
  if (r.tab === 'items' && !r.client.archivedAt) {
    wireClientItems(body, r.client, clientCatalogItems(r.client), saved => { r.client = saved; renderClientRecord(); });
  }
  const automations = $('[data-client-automations]', body);
  if (automations) {
    window.AgentsUI.renderAutomations(automations, { type: 'client', id: r.client.id }, r.automations, {
      back: clientTrailEntry(r.client.id, r.client.name, 'automations'),
      onChange: view => {
        r.automations = view;
        r.client = { ...r.client, automationPaused: view.automationPaused === true };
        renderClientRecord();
      },
    });
  }
  if (focusTab) $('[data-record-tab].is-on', body)?.focus();
}

const clientTrailEntry = (id, name, tab) => ({ key: `client:${id}`, label: name, open: () => openClientDrawer(id, tab) });
function openFromClient(open) {
  const c = clientRecord?.client;
  openFrom(c ? clientTrailEntry(c.id, c.name, clientRecord.tab) : null, open);
}

function openClientItem(ref) {
  const r = clientRecord;
  if (!r) return;
  const [kind, id] = ref.split(':');
  if (kind === 'chat') return openClientConversation(r.conversation);
  if (kind === 'tab') { r.tab = id; return renderClientRecord(true); }
  if (kind === 'invoice-open-work') return openFromClient(() => openInvoiceOpenWork(r.client, r.services.filter(s => s.status === 'OPEN')));
  if (kind === 'invoice') return openFromClient(() => openInvoiceDetail(id));
  if (kind === 'payment') return openFromClient(() => openPaymentDetail(id));
  if (kind === 'quote') return openFromClient(() => openQuoteDetail(id));
  if (kind === 'email') {
    const message = r.emails?.find(e => e.id === id);
    return message && openFromClient(() => openEmailMessage(message));
  }
  if (kind === 'booking') {
    const booking = r.bookings.find(b => b.id === id);
    return booking && openFromClient(() => openBookingDetail(booking));
  }
  if (kind === 'service') {
    const service = r.services.find(s => s.id === id);
    return service && openFromClient(() => openServiceDetail(service));
  }
}

function clientAct(kind) {
  const c = clientRecord?.client;
  if (!c) return;
  if (kind === 'booking') return openFromClient(() => openBookingForm(null, { client: c }));
  if (kind === 'service') return openFromClient(() => openServiceForm(null, c.id));
  if (kind === 'quote') return openFromClient(() => openQuoteForm({ client: c }));
  if (kind === 'invoice') return openFromClient(() => openInvoiceForm({ client: c }));
  if (kind === 'invoice-open-work') return openClientItem('invoice-open-work');
  if (kind === 'expense') return openFromClient(() => openPaymentForm(null, null, c));
  if (kind === 'finance-page') return openClientFinancePage(c);
}

// Finances filtered to this client; the filter is set after setActive, which clears filters.
async function openClientFinancePage(client) {
  closeDrawer({ dismissed: true });
  await setActive('financeiro');
  state.filterFinanceiroClient = client.id;
  render();
}

async function openClientConversation(conversation) {
  if (!conversation) return;
  closeDrawer({ dismissed: true });
  const asset = (state.me?.tenant.channels || []).find(a => a.platform === conversation.channel);
  if (asset) rememberAsset(asset.externalId);
  state.selectedConversation = conversation.id;
  await setActive('conversations');
}

async function openClientDrawer(ref, tab) {
  const id = typeof ref === 'string' ? ref : ref?.id;
  if (!id) return;
  const known = (typeof ref === 'object' && ref) || state.clients.find(c => c.id === id) || null;
  const body = document.createElement('div');
  body.className = 'record';
  body.innerHTML = `<p class="hint">${escapeHTML(CRM.loading)}</p>`;
  const eyebrow = c => [STR.clientEyebrow, c?.number].filter(Boolean).join(' · ');
  const gen = openDrawer(known?.name || CRM.loading, body, false, { eyebrow: eyebrow(known) });
  const record = await loadClientRecord(id);
  if (gen !== drawerGen) return;
  if (!record.client) { closeDrawer({ dismissed: true }); return toast(STR.loadFailed); }
  clientRecord = { ...record, el: body, tab: tab || (clientRecord?.client?.id === id ? clientRecord.tab : 'overview') };
  $('#drawer-title').textContent = record.client.name;
  renderDrawerEyebrow(eyebrow(record.client));
  renderClientRecord();
}

// Every open row starts ticked, unless `preselect` names the ones to tick (the others stay listed).
function openInvoiceOpenWork(client, services, preselect = null) {
  const t = CRM.services;
  const form = document.createElement('form');
  form.className = 'form';
  const due = new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10);
  const rows = services.map(s => `<tr>
    <td class="check"><input type="checkbox" data-pick="${escapeHTML(s.id)}" data-eur="${Number(s.totalEur || 0)}" ${!preselect || preselect.includes(s.id) ? 'checked' : ''} aria-label="${escapeHTML(s.name)}" /></td>
    <td class="name">${escapeHTML(s.name)}</td><td class="mono muted">${escapeHTML(fmtDay(s.performedAt || s.createdAt))}</td>
    <td class="num right">${fmtEUR(s.totalEur)}</td></tr>`).join('');
  form.innerHTML = `
    <p class="hint">${escapeHTML(client.name)}</p>
    <div class="panel"><div class="tbl-wrap"><table class="tbl"><tbody>${rows}</tbody></table></div></div>
    <div class="form__row"><label class="lbl" for="ow-due">${escapeHTML(t.invoiceDue)} <span class="req">●</span></label>
      <input class="inp" id="ow-due" type="date" required value="${due}" /></div>
    <div class="actions"><button class="btn btn--primary" type="submit" id="ow-submit"></button></div>`;
  const submit = $('#ow-submit', form);
  const refresh = () => {
    const picked = $$('[data-pick]:checked', form);
    submit.textContent = STR.clientIssueInvoice({ amount: fmtEUR(picked.reduce((sum, i) => sum + Number(i.dataset.eur), 0)) });
    submit.disabled = !picked.length;
  };
  $$('[data-pick]', form).forEach(i => i.addEventListener('change', refresh));
  refresh();
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const serviceIds = $$('[data-pick]:checked', form).map(i => i.dataset.pick);
    const dueDate = $('#ow-due', form).value;
    if (!serviceIds.length) return toast(t.invoiceNeedRows);
    if (!dueDate) return toast(STR.invoiceEnterDueDate);
    submit.disabled = true;
    try {
      const invoice = await api('/app/api/crm/services/invoice', { method: 'POST', body: JSON.stringify({ clientId: client.id, serviceIds, dueDate }) });
      closeDrawer();
      toast(t.issuedFrom({ number: invoice.number }));
      if (state.fetched.invoices) state.invoices = await api('/app/api/crm/invoices').catch(() => state.invoices);
      if (state.active === 'services') await loadModule('services').catch(() => {});
      render();
    } catch { submit.disabled = false; toast(t.invoiceFailed); }
  });
  openDrawer(STR.clientInvoiceOpenWorkTitle, form);
}

// ── Client fields ─────────────────────────────────────────────────────────────────────────────
// Each company picks which standard fields staff must fill in and adds fields of its own
// (GET/PUT /app/api/crm/clients/fields). The client form, the list's columns and the record's
// Details follow it; a client carries its values in `customFields`, by field key.
const STANDARD_CLIENT_FIELDS = ['name', 'phone', 'taxId', 'email', 'contactPerson', 'address', 'postalCode', 'city'];
// What staff were always asked for, until the company's choice has loaded.
const DEFAULT_REQUIRED_CLIENT_FIELDS = ['name', 'phone', 'taxId', 'address'];
const CUSTOM_FIELD_TYPES = ['TEXT', 'NUMBER', 'DATE', 'SELECT', 'CHECKBOX'];
const REQUIRED_ERROR_FIELDS = {
  tax_id_required: 'taxId', email_required: 'email', contact_person_required: 'contactPerson',
  address_required: 'address', postal_code_required: 'postalCode', city_required: 'city',
};

const standardClientFieldLabel = key => ({
  name: STR.clientFormName, phone: STR.clientFormPhone, taxId: STR.clientFormTaxId, email: STR.clientFormEmail,
  contactPerson: STR.clientFormContact, address: STR.clientFormAddress, postalCode: STR.clientFormPostalCode, city: STR.clientFormCity,
}[key] || key);

async function loadClientFields(fresh = false) {
  if (!state.clientFields || fresh) state.clientFields = await api('/app/api/crm/clients/fields').catch(() => state.clientFields);
  return state.clientFields;
}
const clientCustomFields = () => state.clientFields?.custom || [];
const clientFieldRequired = key => (state.clientFields
  ? !!state.clientFields.standard.find(f => f.key === key)?.required
  : DEFAULT_REQUIRED_CLIENT_FIELDS.includes(key));
const customFieldTypeLabel = type => (FIELDS.types && FIELDS.types[type]) || type;
const fieldsErrorText = code => (FIELDS.errors && typeof FIELDS.errors === 'object' && code && FIELDS.errors[code]) || FIELDS.saveFailed;

// How a stored value reads in the list and the record; empty when there is nothing to show.
function customFieldText(field, value) {
  if (value == null || value === '' || value === false) return '';
  if (field.type === 'CHECKBOX') return FIELDS.yes;
  if (field.type === 'NUMBER' && Number.isFinite(Number(value))) return new Intl.NumberFormat(uiLocale()).format(Number(value));
  if (field.type === 'DATE') return fmtDay(value);
  return String(value);
}

function customFieldInput(field, value) {
  const id = `cf-custom-${field.key}`;
  const attrs = `id="${id}" data-custom-field="${escapeHTML(field.key)}"${field.required ? ' required' : ''}`;
  if (field.type === 'CHECKBOX') {
    return `<div class="form__row form__row--full"><label class="form__check"><input type="checkbox" ${attrs}${value === true ? ' checked' : ''} /> ${escapeHTML(field.label)}</label></div>`;
  }
  const label = `<label class="lbl" for="${id}">${escapeHTML(field.label)}${field.required ? ' <span class="req">●</span>' : ''}</label>`;
  const current = value == null ? '' : String(value);
  if (field.type === 'SELECT') {
    // A choice the company has since removed stays on the clients that have it, until someone changes it.
    const options = current && !field.options.includes(current) ? [...field.options, current] : field.options;
    return `<div class="form__row">${label}<select class="sel" ${attrs}><option value=""></option>${options.map(o => `<option value="${escapeHTML(o)}"${o === current ? ' selected' : ''}>${escapeHTML(o)}</option>`).join('')}</select></div>`;
  }
  const input = {
    NUMBER: 'class="inp inp--mono" type="number" step="any" inputmode="decimal"',
    DATE: 'class="inp inp--mono" type="date"',
  }[field.type] || 'class="inp" maxlength="500" autocomplete="off"';
  return `<div class="form__row">${label}<input ${input} ${attrs} value="${escapeHTML(current)}" /></div>`;
}

function customFieldValues(form) {
  const values = {};
  for (const field of clientCustomFields()) {
    const el = $(`[data-custom-field="${field.key}"]`, form);
    if (el) values[field.key] = field.type === 'CHECKBOX' ? el.checked : el.value.trim();
  }
  return values;
}

async function clientSaveErrorText(err, fallback) {
  const code = err?.code || '';
  if (code === 'invalid_email') return STR.clientInvalidEmail;
  if (REQUIRED_ERROR_FIELDS[code]) return FIELDS.missing({ field: standardClientFieldLabel(REQUIRED_ERROR_FIELDS[code]) });
  if (!code.startsWith('custom_field_')) return fallback;
  // A field an admin added after this form opened isn't known here yet.
  if (!clientCustomFields().some(f => f.key === err.detail)) await loadClientFields(true);
  const field = clientCustomFields().find(f => f.key === err.detail)?.label || err.detail;
  if (code === 'custom_field_required') return FIELDS.missing({ field });
  if (code === 'custom_field_too_long') return FIELDS.tooLong({ field });
  return FIELDS.invalid({ field });
}

// Saves the company's client fields; what isn't passed stays as it is. True once saved.
async function saveClientFields({ required, custom }) {
  const now = state.clientFields;
  const body = {
    required: required ?? now.standard.filter(f => f.required && !f.locked).map(f => f.key),
    custom: (custom ?? now.custom).map(f => ({ key: f.key, label: f.label, type: f.type, required: f.required, showInList: f.showInList, options: f.options })),
  };
  try {
    state.clientFields = await api('/app/api/crm/clients/fields', { method: 'PUT', body: JSON.stringify(body) });
    toast(FIELDS.saved);
    if (state.active === 'clients') render();
    return true;
  } catch (err) {
    toast(fieldsErrorText(err?.code));
    return false;
  }
}

async function openClientFieldsEditor() {
  const info = await loadClientFields(true);
  if (!info) return toast(STR.loadFailed);
  const body = document.createElement('div');
  body.className = 'form';
  const standard = info.standard.map(f => `<label class="form__check"><input type="checkbox" data-required-field="${escapeHTML(f.key)}"${f.required ? ' checked' : ''}${f.locked || !info.canEdit ? ' disabled' : ''} /> ${escapeHTML(standardClientFieldLabel(f.key))}</label>`).join('');
  const rows = info.custom.map(f => {
    const detail = [
      customFieldTypeLabel(f.type),
      f.type === 'SELECT' ? f.options.join(', ') : '',
      f.required ? FIELDS.detailRequired : '',
      f.showInList ? FIELDS.detailInList : '',
    ].filter(Boolean).join(' · ');
    return `<li><button class="worklist__item" type="button" data-edit-field="${escapeHTML(f.key)}" data-tone="neutral"${info.canEdit ? '' : ' disabled'}>
      <span class="worklist__dot" aria-hidden="true"></span>
      <span class="worklist__main"><span class="worklist__title">${escapeHTML(f.label)}</span><span class="worklist__detail">${escapeHTML(detail)}</span></span>
    </button></li>`;
  }).join('');
  body.innerHTML = `
    <p class="hint">${escapeHTML(FIELDS.intro)}</p>
    <section class="panel">
      <header class="panel__head"><h2 class="panel__title" id="cfe-standard">${escapeHTML(FIELDS.standardTitle)}</h2></header>
      <div class="panel__body form">
        <p class="hint">${escapeHTML(FIELDS.standardHint)}</p>
        <div class="form__checks" role="group" aria-labelledby="cfe-standard">${standard}</div>
      </div>
    </section>
    <section class="panel">
      <header class="panel__head"><h2 class="panel__title">${escapeHTML(FIELDS.customTitle)}${info.custom.length ? ` <span class="tag">${info.custom.length}</span>` : ''}</h2></header>
      ${info.custom.length ? `<ul class="worklist">${rows}</ul>` : `<div class="empty"><p class="empty__title">${escapeHTML(FIELDS.emptyTitle)}</p><p class="empty__desc">${escapeHTML(FIELDS.emptyDesc)}</p></div>`}
    </section>
    ${info.canEdit ? `<div class="drawer__foot"><button class="btn btn--primary" type="button" data-add-field><span class="btn__plus">+</span> ${escapeHTML(FIELDS.add)}</button></div>` : ''}`;
  openDrawer(FIELDS.title, body, false, { eyebrow: FIELDS.eyebrow });
  // Name and phone lead the list but are always required, so their boxes can't take the focus.
  requestAnimationFrame(() => $('[data-required-field]:not(:disabled), [data-add-field]', body)?.focus());
  const here = { key: 'client-fields', label: FIELDS.title, open: () => openClientFieldsEditor() };
  $$('[data-required-field]', body).forEach(box => box.addEventListener('change', async () => {
    const required = $$('[data-required-field]:checked:not(:disabled)', body).map(b => b.dataset.requiredField);
    if (!(await saveClientFields({ required }))) box.checked = !box.checked;
  }));
  $$('[data-edit-field]', body).forEach(b => b.addEventListener('click', () => openFrom(here, () => openClientFieldForm(info.custom.find(f => f.key === b.dataset.editField)))));
  $('[data-add-field]', body)?.addEventListener('click', () => openFrom(here, () => openClientFieldForm(null)));
}

function openClientFieldForm(field) {
  const editing = field || null;
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__row form__row--full"><label class="lbl" for="fd-label">${escapeHTML(FIELDS.label)} <span class="req">●</span></label>
      <input class="inp" id="fd-label" required maxlength="60" autocomplete="off" placeholder="${escapeHTML(FIELDS.labelPh)}" value="${escapeHTML(editing?.label || '')}" /></div>
    <div class="form__row form__row--full"><label class="lbl" for="fd-type">${escapeHTML(FIELDS.type)}</label>
      <select class="sel" id="fd-type"${editing ? ' disabled' : ''}>${CUSTOM_FIELD_TYPES.map(t => `<option value="${t}"${t === (editing?.type || 'TEXT') ? ' selected' : ''}>${escapeHTML(customFieldTypeLabel(t))}</option>`).join('')}</select>
      ${editing ? `<p class="hint">${escapeHTML(FIELDS.typeLocked)}</p>` : ''}</div>
    <div class="form__row form__row--full" data-fd-options><label class="lbl" for="fd-options">${escapeHTML(FIELDS.options)} <span class="req">●</span></label>
      <textarea class="txt" id="fd-options" rows="4">${escapeHTML((editing?.options || []).join('\n'))}</textarea>
      <p class="hint">${escapeHTML(FIELDS.optionsHint)}</p></div>
    <div class="form__row form__row--full" data-fd-required><label class="form__check"><input type="checkbox" id="fd-required"${editing?.required ? ' checked' : ''} /> ${escapeHTML(FIELDS.required)}</label></div>
    <div class="form__row form__row--full"><label class="form__check"><input type="checkbox" id="fd-list"${editing?.showInList ? ' checked' : ''} /> ${escapeHTML(FIELDS.showInList)}</label></div>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(editing ? STR.clientSaveChanges : FIELDS.add)}</button>
      <button class="btn btn--ghost" type="button" data-form-cancel>${escapeHTML(STR.cancel)}</button>
      ${editing ? `<button class="btn btn--ghost" type="button" data-field-remove>${escapeHTML(FIELDS.remove)}</button>` : ''}
    </div>`;
  const type = $('#fd-type', form);
  const paintType = () => {
    $('[data-fd-options]', form).hidden = type.value !== 'SELECT';
    $('[data-fd-required]', form).hidden = type.value === 'CHECKBOX';
  };
  type.addEventListener('change', paintType);
  paintType();
  $('[data-form-cancel]', form).addEventListener('click', () => closeDrawer());
  $('[data-field-remove]', form)?.addEventListener('click', async () => {
    const ok = await confirmDialog({ title: FIELDS.removeTitle({ label: editing.label }), body: FIELDS.removeBody, okLabel: FIELDS.removeOk });
    if (ok && await saveClientFields({ custom: clientCustomFields().filter(f => f.key !== editing.key) })) closeDrawer();
  });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const next = {
      key: editing?.key || null,
      label: $('#fd-label', form).value.trim(),
      type: type.value,
      required: type.value !== 'CHECKBOX' && $('#fd-required', form).checked,
      showInList: $('#fd-list', form).checked,
      options: type.value === 'SELECT' ? $('#fd-options', form).value.split('\n').map(o => o.trim()).filter(Boolean) : [],
    };
    if (!next.label) return toast(fieldsErrorText('label_required'));
    if (next.type === 'SELECT' && !next.options.length) return toast(fieldsErrorText('options_required'));
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    const custom = editing ? clientCustomFields().map(f => (f.key === editing.key ? next : f)) : [...clientCustomFields(), next];
    if (await saveClientFields({ custom })) closeDrawer();
    else btn.disabled = false;
  });
  openDrawer(editing ? FIELDS.editTitle : FIELDS.newTitle, form, false, { eyebrow: FIELDS.title });
}

function wireDuplicatePhone(form, editingId) {
  const input = $('#cf-phone', form);
  const hint = $('#cf-dup', form);
  let timer;
  let seq = 0;
  const check = async () => {
    const mine = ++seq;
    if (phoneKey(input.value).length < 9) { hint.hidden = true; return; }
    const found = await api(`/app/api/crm/clients/by-phone?phone=${encodeURIComponent(input.value)}`).catch(() => null);
    if (mine !== seq) return;
    if (!found || found.id === editingId) { hint.hidden = true; return; }
    const text = found.archivedAt ? STR.clientDuplicateArchived : STR.clientDuplicate;
    hint.innerHTML = `${escapeHTML(text({ name: found.name, number: found.number }))} <button class="btn btn--sm btn--ghost" type="button" data-open-dup>${escapeHTML(STR.clientOpenExisting)}</button>`;
    hint.hidden = false;
    $('[data-open-dup]', hint).addEventListener('click', () => openClientDrawer(found));
  };
  input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(check, 350); });
  if (input.value) check();
}

// `opts.prefill` starts a new client with values from elsewhere (an email's sender); `opts.onSaved(client)`
// runs after a save instead of opening the record, for flows that continue from where they started.
async function openClientForm(client, opts = {}) {
  const editing = client && client.id ? client : null;
  const values = editing || opts.prefill || {};
  await loadClientFields();
  const field = ({ id, label, value, attrs = '', cls = 'inp', required = false, full = false }) => `<div class="form__row${full ? ' form__row--full' : ''}"><label class="lbl" for="${id}">${escapeHTML(label)}${required ? ' <span class="req">●</span>' : ''}</label>
    <input class="${cls}" id="${id}" value="${escapeHTML(value || '')}" ${required ? 'required ' : ''}${attrs} /></div>`;
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${field({ id: 'cf-name', label: STR.clientFormName, value: values.name, required: true, attrs: `maxlength="120" autocomplete="off" placeholder="${escapeHTML(STR.clientPhName)}"` })}
      ${field({ id: 'cf-tax', label: STR.clientFormTaxId, value: values.taxId, cls: 'inp inp--mono', required: clientFieldRequired('taxId'), attrs: `maxlength="32" autocomplete="off" placeholder="${escapeHTML(STR.clientPhTaxId)}"` })}
      <div class="form__row"><label class="lbl" for="cf-phone">${escapeHTML(STR.clientFormPhone)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="cf-phone" type="tel" required maxlength="40" autocomplete="off" placeholder="${escapeHTML(STR.clientPhPhone)}" value="${escapeHTML(values.phone || '')}" />
        <p class="hint hint--warn" id="cf-dup" hidden></p></div>
      ${field({ id: 'cf-email', label: STR.clientFormEmail, value: values.email, required: clientFieldRequired('email'), attrs: `type="email" maxlength="254" autocomplete="off" placeholder="${escapeHTML(STR.clientPhEmail)}"` })}
      ${field({ id: 'cf-contact', label: STR.clientFormContact, value: values.contactPerson, full: true, required: clientFieldRequired('contactPerson'), attrs: `maxlength="120" autocomplete="off" placeholder="${escapeHTML(STR.clientPhContact)}"` })}
      ${field({ id: 'cf-address', label: STR.clientFormAddress, value: values.address, full: true, required: clientFieldRequired('address'), attrs: `maxlength="300" autocomplete="off" placeholder="${escapeHTML(STR.clientPhAddress)}"` })}
      ${field({ id: 'cf-postal', label: STR.clientFormPostalCode, value: values.postalCode, cls: 'inp inp--mono', required: clientFieldRequired('postalCode'), attrs: `maxlength="20" autocomplete="off" placeholder="${escapeHTML(STR.clientPhPostalCode)}"` })}
      ${field({ id: 'cf-city', label: STR.clientFormCity, value: values.city, required: clientFieldRequired('city'), attrs: `maxlength="100" autocomplete="off" placeholder="${escapeHTML(STR.clientPhCity)}"` })}
      ${clientCustomFields().map(f => customFieldInput(f, editing?.customFields?.[f.key])).join('')}
      <div class="form__row form__row--full"><label class="lbl" for="cf-notes">${escapeHTML(STR.clientFormNotes)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <textarea class="txt" id="cf-notes" maxlength="4000" placeholder="${escapeHTML(STR.clientPhNotes)}">${escapeHTML(values.notes || '')}</textarea>
        <p class="hint">${escapeHTML(STR.clientNotesHint)}</p></div>
    </div>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(editing ? STR.clientSaveChanges : STR.clientSave)}</button>
      ${editing ? `<button class="btn btn--ghost" type="button" data-cf-cancel>${escapeHTML(STR.cancel)}</button>` : ''}
    </div>`;
  wireDuplicatePhone(form, editing?.id);
  $('[data-cf-cancel]', form)?.addEventListener('click', () => closeDrawer());
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const val = sel => $(sel, form).value.trim();
    const payload = {
      name: val('#cf-name'), phone: val('#cf-phone'), taxId: val('#cf-tax'), email: val('#cf-email'),
      contactPerson: val('#cf-contact'), address: val('#cf-address'), postalCode: val('#cf-postal'), city: val('#cf-city'),
      notes: val('#cf-notes'),
    };
    const customFields = customFieldValues(form);
    if (Object.keys(customFields).length) payload.customFields = customFields;
    const missing = STANDARD_CLIENT_FIELDS.find(key => clientFieldRequired(key) && !payload[key]);
    if (missing) return toast(FIELDS.missing({ field: standardClientFieldLabel(missing) }));
    const missingCustom = clientCustomFields().find(f => f.required && !customFields[f.key]);
    if (missingCustom) return toast(FIELDS.missing({ field: missingCustom.label }));
    if (payload.email && !EMAIL_SHAPE.test(payload.email)) return toast(STR.clientInvalidEmail);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const saved = editing
        ? await api(`/app/api/crm/clients/${encodeURIComponent(editing.id)}`, { method: 'PATCH', body: JSON.stringify(payload) })
        : await api('/app/api/crm/clients', { method: 'POST', body: JSON.stringify(payload) });
      toast(editing ? STR.clientUpdated : STR.clientCreated({ name: saved.name }));
      if (opts.onSaved) { closeDrawer(); return opts.onSaved(saved); }
      if (state.active === 'clients') { await loadModule('clients').catch(() => {}); render(); }
      if (editing) closeDrawer();
      else openClientDrawer(saved);
    } catch (err) {
      btn.disabled = false;
      toast(await clientSaveErrorText(err, editing ? STR.clientUpdateFailed : STR.clientCreateFailed));
    }
  });
  openDrawer(editing ? STR.clientEdit : STR.clientFormTitle, form, false, { eyebrow: STR.clientEyebrow });
}
