/* Bookings (/app → Bookings): the week grid and the agenda, requests waiting for confirmation, a booking's
   detail and form, invoicing a booking, and the bookable services and opening hours. */

const BOOKING_AGENDA_DAYS = 60;

function weekDayKeys() {
  const start = state.bookingWeekStart || periodKey(todayKey(), 'week');
  return Array.from({ length: 7 }, (_, i) => addDayKey(start, i));
}
function bookingLocal(b) { return toLocalInputValue(b.startAt); }
function weekBookings() {
  const keys = new Set(weekDayKeys());
  return state.bookings.filter(b => keys.has(bookingLocal(b).slice(0, 10)));
}
function pendingUpcomingBookings() {
  const now = Date.now();
  return state.bookingUpcoming.filter(b => b.status === 'PENDING' && new Date(b.endAt).getTime() >= now);
}
function findLoadedBooking(id) {
  return state.bookings.find(b => b.id === id) || state.bookingUpcoming.find(b => b.id === id) || null;
}
function bookingServiceName(b) {
  return b.serviceName || state.bookingServices.find(s => s.id === b.serviceId)?.name || '';
}
// Bookings made before prices were snapshotted have none; the server bills those at the catalog price too.
function bookingPrice(b) {
  if (b.priceEur != null) return b.priceEur;
  return state.bookingServices.find(s => s.id === b.serviceId)?.priceEur ?? null;
}
function bookingMinutes(b) { return Math.max(0, Math.round((new Date(b.endAt) - new Date(b.startAt)) / 60000)); }
function bookingTimeRange(b) { return `${fmtTime(b.startAt)}–${fmtTime(b.endAt)}`; }
function bookingWhen(b) {
  return `${capFirst(fmtDayKey(bookingLocal(b).slice(0, 10), { weekday: 'short', day: 'numeric', month: 'short' }))} · ${bookingTimeRange(b)}`;
}
function serviceOptionLabel(s) {
  return [s.name, fmtMinutes(s.durationMinutes || 30), s.priceEur ? fmtEUR(s.priceEur) : ''].filter(Boolean).join(' · ');
}
const hhmmMinutes = hhmm => Number(String(hhmm).slice(0, 2)) * 60 + Number(String(hhmm).slice(3, 5));
const isoWeekday = dayKey => ((dayKeyDate(dayKey).getUTCDay() + 6) % 7) + 1;
function withinOpeningHours(dayKey, time, minutes) {
  const start = hhmmMinutes(time);
  return state.bookingAvailability.some(r => r.dayOfWeek === isoWeekday(dayKey) && hhmmMinutes(r.startLocal) <= start && start + minutes <= hhmmMinutes(r.endLocal));
}
function renderBookings(root) {
  const days = weekDayKeys();
  const today = todayKey();
  const agenda = state.bookingView === 'agenda';
  const atThisWeek = days[0] === periodKey(today, 'week');
  const weekLabel = `${fmtDayKey(days[0], { day: 'numeric', month: 'short' })} – ${fmtDayKey(days[6], { day: 'numeric', month: 'short', year: 'numeric' })}`;
  const list = agenda ? bookingAgendaList() : [];
  // The design's head: Week / List, the list's status tabs, then the week's arrows and the setup buttons at the end.
  const view = (key, label) => `<button class="chip ${(key === 'agenda') === agenda ? 'is-on' : ''}" type="button" data-booking-view="${key}">${escapeHTML(label)}</button>`;
  const upcoming = agenda ? bookingAgendaList('') : [];
  const statusTabs = !agenda ? '' : '<span class="panel__sep" aria-hidden="true"></span>' + [''].concat(BOOKING_STATUSES)
    .map(st => `<button class="chip ${state.bookingStatusFilter === st ? 'is-on' : ''}" type="button" data-booking-filter="${st}">${escapeHTML(st ? bookingStatusLabel(st) : STR.bookingsStatusAll)}${chipCount(st ? upcoming.filter(x => x.status === st).length : upcoming.length)}</button>`)
    .join('');
  const nav = agenda ? '' : `<div class="period-nav">
      <button type="button" class="btn btn--sm" data-week-shift="-7" aria-label="${escapeHTML(STR.bookingsPrev)}">‹</button>
      <button type="button" class="btn btn--sm" data-week-shift="7" aria-label="${escapeHTML(STR.bookingsNext)}">›</button>
      ${atThisWeek ? '' : `<button type="button" class="btn btn--sm" data-week-shift="0">${escapeHTML(STR.bookingsToday)}</button>`}
    </div>`;
  const tools = view('week', STR.bookingsWeek) + view('agenda', STR.bookingsList) + statusTabs
    + `<span class="panel__tools-end">${nav}${agenda ? columnOrderButton('bookings') : ''}<button type="button" class="btn btn--sm btn--ghost" data-booking-services>${escapeHTML(STR.bookingsManageServices)}</button><button type="button" class="btn btn--sm btn--ghost" data-booking-availability>${escapeHTML(STR.bookingsManageAvailability)}</button></span>`;
  pageCount(agenda ? list.length : weekLabel);
  const panel = agenda
    ? bookingAgendaHtml(list, tools)
    : `<div class="panel"><div class="panel__head panel__head--tabs"><div class="panel__tools">${tools}</div></div>${bookingWeekHtml(days, today)}</div>`;
  root.innerHTML = hero(labels.bookings, STR.bookingsDesc) + bookingSetupHtml() + panel;
  wireColumnOrder(root, 'bookings', agendaColumns());
  if (agenda) wireSelection(root, 'bookings', list.map(x => x.id));

  $$('[data-booking-view]', root).forEach(b => b.addEventListener('click', () => { state.bookingView = b.dataset.bookingView; render(); }));
  $$('[data-week-shift]', root).forEach(b => b.addEventListener('click', async () => {
    const shift = Number(b.dataset.weekShift);
    state.bookingWeekStart = shift === 0 ? periodKey(todayKey(), 'week') : addDayKey(days[0], shift);
    try { await loadModule('bookings'); } catch { toast(STR.loadFailed); }
    render();
  }));
  $$('[data-booking-services]', root).forEach(b => b.addEventListener('click', openBookingServicesForm));
  $$('[data-booking-availability]', root).forEach(b => b.addEventListener('click', openBookingAvailabilityForm));
  $$('[data-booking-filter]', root).forEach(b => b.addEventListener('click', () => { state.bookingStatusFilter = b.dataset.bookingFilter; render(); }));
  $$('[data-booking-status]', root).forEach(b => b.addEventListener('click', e => {
    e.stopPropagation();
    changeBookingStatus(b.dataset.bookingTarget, b.dataset.bookingStatus);
  }));
  $$('[data-booking-open]', root).forEach(el => el.addEventListener('click', e => {
    e.stopPropagation();
    openBookingById(el.dataset.bookingOpen);
  }));
  $$('[data-booking-slot]', root).forEach(el => el.addEventListener('click', () => openBookingForm(null, { slot: el.dataset.bookingSlot })));
}

// Nothing can be booked until at least one catalog service is bookable and opening hours exist.
function bookingSetupHtml() {
  const steps = [];
  if (!state.bookingServices.some(s => s.active)) steps.push({ attr: 'data-booking-services', title: STR.bookingsSetupServices, detail: STR.bookingsSetupServicesDesc });
  if (!state.bookingAvailability.length) steps.push({ attr: 'data-booking-availability', title: STR.bookingsSetupHours, detail: STR.bookingsSetupHoursDesc });
  if (!steps.length) return '';
  return `<div class="overview-block"><h2 class="panel__title">${escapeHTML(STR.bookingsSetupTitle)}</h2><div class="setup-list">${steps.map((s, i) => `<button type="button" class="queue__item" ${s.attr}><span class="queue__icon queue__icon--info mono" aria-hidden="true">${i + 1}</span><div><strong>${escapeHTML(s.title)}</strong><span>${escapeHTML(s.detail)}</span></div></button>`).join('')}</div></div>`;
}

// The week as seven day columns of booking cards, as the design draws it; a card's left edge says its status.
// Each day ends in a + that books it at its first opening hour.
function bookingWeekHtml(days, today) {
  const byDay = new Map(days.map(d => [d, []]));
  for (const b of state.bookings) byDay.get(bookingLocal(b).slice(0, 10))?.push(b);
  return `<div class="week-board">${days.map((d, i) => {
    const windows = state.bookingAvailability.filter(r => r.dayOfWeek === i + 1).sort((x, y) => hhmmMinutes(x.startLocal) - hhmmMinutes(y.startLocal));
    const closed = state.bookingAvailability.length > 0 && !windows.length;
    const first = windows[0]?.startLocal?.slice(0, 5) || '09:00';
    const cards = byDay.get(d).sort((x, y) => x.startAt.localeCompare(y.startAt)).map(b => {
      const service = bookingServiceName(b);
      const title = [bookingTimeRange(b), b.contactName, service, bookingStatusLabel(b.status)].filter(Boolean).join(' · ');
      return `<button type="button" class="week-event week-event--${b.status.toLowerCase().replace('_', '-')}" data-booking-open="${escapeHTML(b.id)}" title="${escapeHTML(title)}"><b>${escapeHTML(b.contactName)}</b><span>${escapeHTML([fmtTime(b.startAt), service].filter(Boolean).join(' · '))}</span></button>`;
    }).join('');
    const dayName = fmtDayKey(d, { weekday: 'long', day: 'numeric', month: 'long' });
    return `<section class="week-board__day${d === today ? ' is-today' : ''}${closed ? ' is-closed' : ''}" aria-label="${escapeHTML(capFirst(dayName))}">
      <h3 class="week-board__head"><b>${escapeHTML(fmtDayKey(d, { day: 'numeric' }))}</b>${escapeHTML(STR[`weekday${i + 1}`])}</h3>
      ${cards}
      <button type="button" class="week-board__add" data-booking-slot="${d}T${first}" aria-label="${escapeHTML(STR.bookingsAddOn({ day: dayName }))}" title="${escapeHTML(STR.bookingsAddOn({ day: dayName }))}">+</button>
    </section>`;
  }).join('')}</div>`;
}

function agendaColumns() {
  return [
    selectColumn('bookings'),
    { id: 'when', label: STR.bookingsThWhen, cell: b => `<td class="nowrap"><button class="tbl__open" type="button">${escapeHTML(capFirst(fmtDayKey(bookingLocal(b).slice(0, 10), { weekday: 'short', day: 'numeric', month: 'short' })))} · ${escapeHTML(bookingTimeRange(b))}</button></td>` },
    { id: 'contact', label: STR.bookingsThContact, cell: b => `<td class="name">${nameWithInitials(b.contactName)}</td>` },
    { id: 'phone', label: STR.bookingsContactPhone, cell: b => `<td class="mono muted nowrap">${escapeHTML(b.contactPhone || '')}</td>` },
    { id: 'service', label: STR.bookingsThService, cell: b => `<td class="muted">${escapeHTML(bookingServiceName(b))}</td>` },
    { id: 'duration', label: STR.bookingsDuration, cell: b => `<td class="muted nowrap">${bookingMinutes(b) ? escapeHTML(fmtMinutes(bookingMinutes(b))) : '—'}</td>` },
    { id: 'status', label: STR.bookingsThStatus, cell: b => `<td>${bookingPill(b.status)}</td>` },
    { id: 'price', label: STR.bookingsPrice, head: 'right', cell: b => `<td class="num">${bookingPrice(b) != null ? fmtEUR(bookingPrice(b)) : '—'}</td>` },
    {
      id: 'action', label: '', pin: 'end', cell: b => `<td class="right">${b.status === 'PENDING'
        ? `<button class="btn btn--sm btn--primary" type="button" data-booking-status="CONFIRMED" data-booking-target="${escapeHTML(b.id)}">${escapeHTML(STR.bookingsConfirm)}</button>`
        : `<span class="muted">${escapeHTML(bookingSourceLabel(b.source))}</span>`}</td>`,
    },
  ];
}

// The bookings from today on, in the next BOOKING_AGENDA_DAYS days, by status (`filter`) and the search.
function bookingAgendaList(filter = state.bookingStatusFilter) {
  const q = state.search.toLowerCase();
  const today = todayKey();
  return state.bookingUpcoming
    .filter(b => bookingLocal(b).slice(0, 10) >= today)
    .filter(b => !filter || b.status === filter)
    .filter(b => !q || `${b.contactName} ${b.contactPhone} ${bookingServiceName(b)} ${b.notes || ''}`.toLowerCase().includes(q))
    .sort((a, b) => a.startAt.localeCompare(b.startAt));
}

function bookingAgendaHtml(list, tools) {
  const columns = orderedColumns('bookings', agendaColumns());
  const rows = list.map(b => `<tr class="conversation-row${b.status === 'CANCELLED' ? ' is-draft' : ''}" data-booking-open="${escapeHTML(b.id)}">${columnCells(columns, b)}</tr>`).join('');
  const narrowed = Boolean(state.bookingStatusFilter || state.search);
  return crmPanel({
    title: '',
    tools,
    bar: selectionBar('bookings'),
    head: columnsHead(columns, 'bookings'),
    rows,
    empty: narrowed ? STR.bookingsEmptyList : STR.bookingsEmptyAgenda,
    emptyDesc: narrowed ? '' : STR.bookingsEmptyAgendaDesc,
  });
}

async function afterBookingChange() {
  if (state.fetched.bookings || state.active === 'bookings') await loadModule('bookings').catch(() => {});
  if (state.active === 'overview') await loadModule('overview').catch(() => {});
  render();
}

async function openBookingById(id) {
  if (!id) return;
  const booking = findLoadedBooking(id) || await api(`/app/api/bookings/${encodeURIComponent(id)}`).catch(() => null);
  if (!booking) return toast(STR.loadFailed);
  openBookingDetail(booking);
}

async function changeBookingStatus(id, status) {
  const booking = findLoadedBooking(id) || await api(`/app/api/bookings/${encodeURIComponent(id)}`).catch(() => null);
  if (!booking) return null;
  if (status === 'CANCELLED') {
    const ok = await confirmDialog({
      title: STR.bookingsCancelConfirmTitle,
      body: STR.bookingsCancelConfirmBody({ name: booking.contactName, when: bookingWhen(booking) }),
      okLabel: STR.bookingsCancelOk,
    });
    if (!ok) return null;
  }
  try {
    const updated = await api(`/app/api/bookings/${encodeURIComponent(id)}`, { method: 'POST', body: JSON.stringify({ status }) });
    toast(STR.bookingsStatusChanged({ status: bookingStatusLabel(updated.status) }));
    await afterBookingChange();
    return updated;
  } catch (err) {
    toast(bookingErrorText(err));
    return null;
  }
}

async function openBookingDetail(b) {
  let billing = '';
  if (b.clientServiceId && b.clientId && hasModule('services')) {
    const rows = await api(`/app/api/crm/services?clientId=${encodeURIComponent(b.clientId)}`).catch(() => []);
    billing = rows.find(r => r.id === b.clientServiceId)?.status || '';
  }
  const minutes = bookingMinutes(b);
  const statusButton = (status, label, tone = '') =>
    `<button class="btn btn--sm ${tone}" type="button" data-set-status="${status}">${escapeHTML(label)}</button>`;
  const canInvoice = b.status === 'COMPLETED' && billing === 'OPEN' && hasModule('invoices');
  const live = b.status === 'PENDING' || b.status === 'CONFIRMED';
  // The foot as the design orders it: links and the quieter steps first, the one that moves the booking on last.
  const main = b.status === 'PENDING' ? statusButton('CONFIRMED', STR.bookingsConfirm, 'btn--primary')
    : b.status === 'CONFIRMED' ? statusButton('COMPLETED', STR.bookingsComplete, 'btn--primary')
    : canInvoice ? `<button class="btn btn--sm btn--primary" type="button" data-booking-invoice>${escapeHTML(STR.bookingsInvoice)}</button>` : '';
  const actions = [
    b.clientServiceId && hasModule('services') && !linksBackTo(`service:${b.clientServiceId}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-booking-service>${escapeHTML(STR.bookingsOpenService)}</button>` : '',
    `<button class="btn btn--sm btn--ghost" type="button" data-booking-edit>${escapeHTML(STR.bookingsEditAction)}</button>`,
    live ? statusButton('CANCELLED', STR.bookingsCancel, 'btn--ghost') : statusButton('CONFIRMED', STR.bookingsReopen, 'btn--ghost'),
    live ? statusButton('NO_SHOW', STR.bookingsNoShow) : '',
    b.status === 'PENDING' ? statusButton('COMPLETED', STR.bookingsComplete) : '',
    main,
  ];
  const billingText = billing === 'INVOICED' ? `<span class="pill pill--ok">${escapeHTML(STR.bookingsInvoiced)}</span>`
    : billing === 'OPEN' ? escapeHTML(STR.bookingsBilled) : '';
  const clientLink = !!b.clientId && hasModule('clients') && !linksBackTo(`client:${b.clientId}`);
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `
    ${recordFieldsHtml([
      [STR.bookingsStatus, bookingPill(b.status)],
      [STR.bookingsThContact, docClientHtml(b.contactName, clientLink)],
      b.contactPhone ? [STR.bookingsContactPhone, `<span class="mono">${escapeHTML(b.contactPhone)}</span>`] : null,
      [STR.bookingsThWhen, escapeHTML(bookingWhen(b))],
      [STR.bookingsService, escapeHTML(bookingServiceName(b))],
      minutes ? [STR.bookingsDuration, escapeHTML(fmtMinutes(minutes))] : null,
      bookingPrice(b) != null ? [STR.bookingsPrice, recordFigureHtml(fmtEUR(bookingPrice(b))), bookingTone(b.status)] : null,
      [STR.bookingsSource, escapeHTML(bookingSourceLabel(b.source))],
      billingText ? [STR.bookingsBilling, billingText] : null,
      b.notes ? [STR.bookingsNotes, escapeHTML(b.notes)] : null,
    ])}
    ${docFootHtml(actions.join(''))}`;
  $$('[data-set-status]', body).forEach(btn => btn.addEventListener('click', async () => {
    btn.disabled = true;
    const updated = await changeBookingStatus(b.id, btn.dataset.setStatus);
    if (updated) openBookingDetail(updated);
    else btn.disabled = false;
  }));
  $('[data-booking-invoice]', body)?.addEventListener('click', () => invoiceBooking(b));
  $('[data-booking-edit]', body)?.addEventListener('click', () => openBookingForm(b));
  const here = {
    key: `booking:${b.id}`,
    label: bookingServiceName(b) || STR.bookingsEdit,
    open: async () => openBookingDetail(await api(`/app/api/bookings/${encodeURIComponent(b.id)}`).catch(() => b)),
  };
  $('[data-detail-link]', body)?.addEventListener('click', () => openFrom(here, () => openClientDrawer(b.clientId)));
  $('[data-booking-service]', body)?.addEventListener('click', () => openFrom(here, () => openServiceDetail(b.clientServiceId)));
  openDrawer(bookingServiceName(b) || STR.bookingsEdit, body, false, { eyebrow: STR.clientKindBooking });
  mountRecordAutomations(body, { type: 'booking', id: b.id }, here);
}

function invoiceBooking(b) {
  const t = CRM.services;
  const form = document.createElement('form');
  form.className = 'form';
  const due = new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10);
  form.innerHTML = `
    <p class="hint">${escapeHTML([b.contactName, bookingServiceName(b), fmtEUR(bookingPrice(b) || 0)].filter(Boolean).join(' · '))}</p>
    <div class="form__row"><label class="lbl" for="bk-due">${escapeHTML(t.invoiceDue)} <span class="req">●</span></label>
      <input class="inp" id="bk-due" type="date" required value="${due}" /></div>
    <button class="btn btn--primary" type="submit">${escapeHTML(STR.bookingsInvoice)}</button>`;
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const dueDate = $('#bk-due', form).value;
    if (!dueDate) return toast(STR.invoiceEnterDueDate);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const invoice = await api('/app/api/crm/services/invoice', {
        method: 'POST',
        body: JSON.stringify({ clientId: b.clientId, serviceIds: [b.clientServiceId], dueDate }),
      });
      closeDrawer();
      toast(t.issuedFrom({ number: invoice.number }));
      await afterBookingChange();
    } catch { btn.disabled = false; toast(t.invoiceFailed); }
  });
  openDrawer(STR.bookingsInvoice, form);
}

// `opts.prefill` ({ date, time, serviceId, contactName, contactPhone, notes }) starts a new booking from a request
// someone sent; `opts.onSaved(booking)` runs after it is created instead of refreshing the calendar.
function openBookingForm(booking = null, opts = {}) {
  const editing = booking && booking.id ? booking : null;
  const pre = editing ? {} : (opts.prefill || {});
  const services = state.bookingServices.filter(s => s.active || s.id === editing?.serviceId);
  if (!services.length) return openBookingServicesForm();
  const startLocal = editing ? bookingLocal(editing) : (opts.slot || (pre.date ? `${pre.date}T${pre.time || ''}` : ''));
  const dateVal = startLocal ? startLocal.slice(0, 10) : todayKey();
  const timeVal = startLocal ? startLocal.slice(11, 16) : '';
  const preset = services.find(s => s.id === (editing?.serviceId || pre.serviceId)) || (services.length === 1 ? services[0] : null);
  let clientId = editing?.clientId || opts.client?.id || '';
  let clientName = opts.client?.name || '';
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      <div class="form__row form__row--full"><label class="lbl" for="bk-service">${escapeHTML(STR.bookingsService)} <span class="req">●</span></label>
        <select class="sel" id="bk-service" required><option value="">${escapeHTML(STR.bookingsChooseService)}</option>
          ${services.map(s => `<option value="${escapeHTML(s.id)}" data-duration="${s.durationMinutes || 30}" data-price="${s.priceEur ?? ''}" ${preset?.id === s.id ? 'selected' : ''}>${escapeHTML(serviceOptionLabel(s))}</option>`).join('')}
        </select></div>
      <div class="form__row"><label class="lbl" for="bk-date">${escapeHTML(STR.bookingsDate)} <span class="req">●</span></label>
        <input class="inp" type="date" id="bk-date" required value="${escapeHTML(dateVal)}" /></div>
      <div class="form__row"><label class="lbl" for="bk-time">${escapeHTML(STR.bookingsTime)} <span class="req">●</span></label>
        <input class="inp inp--mono" type="time" id="bk-time" step="300" required value="${escapeHTML(timeVal)}" /></div>
      <div class="form__row form__row--full"><span class="lbl">${escapeHTML(STR.bookingsFreeTimes)}</span>
        <div class="slot-picks" id="bk-slots"></div>
        <p class="hint" id="bk-hours-hint" hidden>${escapeHTML(STR.bookingsOutsideHours)}</p></div>
      <div class="form__row"><label class="lbl" for="bk-duration">${escapeHTML(STR.bookingsDurationShort)}</label>
        <input class="inp inp--mono" type="number" min="5" step="5" id="bk-duration" value="${editing ? bookingMinutes(editing) : (preset?.durationMinutes || 30)}" /></div>
      <div class="form__row"><label class="lbl" for="bk-price">${escapeHTML(STR.bookingsPrice)}</label>
        <input class="inp inp--mono inp--right" type="number" min="0" step="0.01" id="bk-price" value="${editing ? (bookingPrice(editing) ?? '') : (preset?.priceEur ?? '')}" /></div>
      <div class="form__row form__row--full suggest-host"><label class="lbl" for="bk-name">${escapeHTML(STR.bookingsContactName)} <span class="req">●</span></label>
        <input class="inp" id="bk-name" autocomplete="off" required value="${escapeHTML(editing?.contactName || opts.client?.name || pre.contactName || '')}" />
        <div class="suggest" id="bk-suggest-name" hidden></div></div>
      <div class="form__row form__row--full suggest-host"><label class="lbl" for="bk-phone">${escapeHTML(STR.bookingsContactPhone)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="bk-phone" autocomplete="off" required value="${escapeHTML(editing?.contactPhone || opts.client?.phone || pre.contactPhone || '')}" />
        <div class="suggest" id="bk-suggest-phone" hidden></div>
        <p class="hint" id="bk-client-hint"></p></div>
      ${editing ? `<div class="form__row form__row--full"><label class="lbl" for="bk-status">${escapeHTML(STR.bookingsStatus)}</label>
        <select class="sel" id="bk-status">${BOOKING_STATUSES.map(s => `<option value="${s}" ${editing.status === s ? 'selected' : ''}>${escapeHTML(bookingStatusLabel(s))}</option>`).join('')}</select></div>` : ''}
      <div class="form__row form__row--full"><label class="lbl" for="bk-notes">${escapeHTML(STR.bookingsNotes)} <span class="opt">${escapeHTML(STR.bookingsOptional)}</span></label>
        <textarea class="txt" id="bk-notes">${escapeHTML(editing?.notes || pre.notes || '')}</textarea></div>
    </div>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(STR.bookingsSave)}</button>
      ${editing ? `<button class="btn btn--ghost" type="button" data-booking-back>${escapeHTML(STR.bookingsBack)}</button>` : ''}
    </div>`;
  const el = id => $(`#${id}`, form);

  const renderClientHint = () => {
    const hint = el('bk-client-hint');
    if (!hasModule('clients')) { hint.textContent = ''; return; }
    if (clientId) {
      hint.innerHTML = `${escapeHTML(STR.bookingsLinkedClient({ name: clientName || el('bk-name').value }))} <button class="btn btn--sm btn--ghost" type="button" data-unlink-client>${escapeHTML(STR.bookingsUnlink)}</button>`;
      $('[data-unlink-client]', hint).addEventListener('click', () => { clientId = ''; clientName = ''; renderClientHint(); });
    } else {
      hint.textContent = STR.bookingsClientHint;
    }
  };

  let suggestTimer;
  const hideSuggest = () => $$('.suggest', form).forEach(box => { box.hidden = true; });
  const lookupClients = (query, box) => {
    clearTimeout(suggestTimer);
    if (!hasModule('clients') || query.trim().length < 2) { box.hidden = true; return; }
    suggestTimer = setTimeout(async () => {
      const found = await api(`/app/api/crm/clients?q=${encodeURIComponent(query.trim())}`).catch(() => []);
      if (!found.length) { box.hidden = true; return; }
      box.innerHTML = found.slice(0, 6).map(c => `<button type="button" class="suggest__item" data-pick-client="${escapeHTML(c.id)}"><strong>${escapeHTML(c.name)}</strong><span class="mono">${escapeHTML(c.phone)}</span></button>`).join('');
      box.hidden = false;
      $$('[data-pick-client]', box).forEach(btn => {
        btn.addEventListener('mousedown', e => e.preventDefault());
        btn.addEventListener('click', () => {
          const c = found.find(x => x.id === btn.dataset.pickClient);
          if (!c) return;
          clientId = c.id;
          clientName = c.name;
          el('bk-name').value = c.name;
          el('bk-phone').value = c.phone;
          hideSuggest();
          renderClientHint();
        });
      });
    }, 200);
  };
  el('bk-name').addEventListener('input', e => lookupClients(e.target.value, el('bk-suggest-name')));
  el('bk-phone').addEventListener('input', e => lookupClients(e.target.value, el('bk-suggest-phone')));
  form.addEventListener('focusout', e => {
    const next = e.relatedTarget;
    if (!next?.closest?.('.suggest') && next !== el('bk-name') && next !== el('bk-phone')) hideSuggest();
  });

  const checkHours = () => {
    const time = el('bk-time').value;
    const outside = Boolean(time) && state.bookingAvailability.length > 0
      && !withinOpeningHours(el('bk-date').value, time, Number(el('bk-duration').value || 0));
    el('bk-hours-hint').hidden = !outside;
  };
  const refreshSlots = async () => {
    const box = el('bk-slots');
    const serviceId = el('bk-service').value;
    const date = el('bk-date').value;
    if (!serviceId || !date) { box.innerHTML = `<span class="muted">${escapeHTML(STR.bookingsPickServiceFirst)}</span>`; return; }
    const range = dayKeyRange(date, 1);
    const slots = await api(`/app/api/bookings/slots?serviceId=${encodeURIComponent(serviceId)}&from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}`).catch(() => []);
    const times = slots.map(s => toLocalInputValue(s.startAt)).filter(v => v.slice(0, 10) === date).map(v => v.slice(11, 16));
    box.innerHTML = times.length
      ? times.map(t => `<button type="button" class="chip ${t === el('bk-time').value ? 'is-on' : ''}" data-slot-time="${t}">${escapeHTML(t)}</button>`).join('')
      : `<span class="muted">${escapeHTML(STR.bookingsNoFreeTimes)}</span>`;
    $$('[data-slot-time]', box).forEach(chip => chip.addEventListener('click', () => {
      el('bk-time').value = chip.dataset.slotTime;
      $$('[data-slot-time]', box).forEach(c => c.classList.toggle('is-on', c === chip));
      checkHours();
    }));
  };
  el('bk-service').addEventListener('change', () => {
    const opt = el('bk-service').selectedOptions[0];
    if (opt?.value) {
      el('bk-duration').value = opt.dataset.duration || 30;
      if (opt.dataset.price !== '') el('bk-price').value = opt.dataset.price;
    }
    refreshSlots();
    checkHours();
  });
  el('bk-date').addEventListener('change', () => { refreshSlots(); checkHours(); });
  el('bk-time').addEventListener('input', () => {
    $$('[data-slot-time]', form).forEach(c => c.classList.toggle('is-on', c.dataset.slotTime === el('bk-time').value));
    checkHours();
  });
  el('bk-duration').addEventListener('input', checkHours);
  $('[data-booking-back]', form)?.addEventListener('click', () => openBookingDetail(editing));

  form.addEventListener('submit', async e => {
    e.preventDefault();
    const serviceId = el('bk-service').value;
    const date = el('bk-date').value;
    const time = el('bk-time').value;
    const contactName = el('bk-name').value.trim();
    const contactPhone = el('bk-phone').value.trim();
    if (!serviceId || !date || !time || !contactName || !contactPhone) return toast(STR.bookingsValidate);
    const price = el('bk-price').value;
    const payload = {
      serviceId,
      startAt: `${date}T${time}`,
      durationMinutes: Number(el('bk-duration').value || 0) || undefined,
      priceEur: price === '' ? undefined : Number(price),
      contactName,
      contactPhone,
      notes: el('bk-notes').value.trim(),
      status: editing ? el('bk-status').value : 'CONFIRMED',
      ...(clientId ? { clientId } : {}),
      ...(editing?.clientId && !clientId ? { clearClientId: true } : {}),
    };
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const saved = editing
        ? await api(`/app/api/bookings/${encodeURIComponent(editing.id)}`, { method: 'POST', body: JSON.stringify(payload) })
        : await api('/app/api/bookings', { method: 'POST', body: JSON.stringify(payload) });
      closeDrawer();
      toast(editing ? STR.bookingsUpdated : STR.bookingsCreated);
      if (!editing && opts.onSaved) return opts.onSaved(saved);
      await afterBookingChange();
    } catch (err) {
      btn.disabled = false;
      toast(bookingErrorText(err));
    }
  });

  renderClientHint();
  refreshSlots();
  checkHours();
  openDrawer(editing ? STR.bookingsEdit : STR.bookingsNew, form);
}

function openBookingServicesForm() {
  const body = document.createElement('div');
  body.className = 'form';
  const rows = state.bookingServices.map(s => `<tr data-svc-row="${escapeHTML(s.id)}">
      <td class="check"><input type="checkbox" data-svc-active ${s.active ? 'checked' : ''} aria-label="${escapeHTML(STR.bookingsBookable)}" /></td>
      <td class="name">${escapeHTML(s.name)}<div class="muted">${escapeHTML(s.category || '')}</div></td>
      <td><input class="inp inp--mono booking-services__duration" type="number" min="5" step="5" data-svc-duration value="${s.durationMinutes || ''}" placeholder="30" aria-label="${escapeHTML(STR.bookingsDurationShort)}" /></td>
      <td class="num">${fmtEUR(s.priceEur)}</td>
    </tr>`).join('');
  body.innerHTML = `
    <p class="hint">${escapeHTML(STR.bookingsServicesHint)}</p>
    ${panelTable(`<tr><th>${escapeHTML(STR.bookingsBookable)}</th><th>${escapeHTML(STR.bookingsServiceName)}</th><th>${escapeHTML(STR.bookingsDurationShort)}</th><th class="right">${escapeHTML(STR.bookingsPrice)}</th></tr>`, rows, STR.bookingsNoServices, STR.bookingsNoServicesDesc)}
    ${hasModule('catalog') ? `<div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-go-catalog>${escapeHTML(STR.bookingsGoCatalog)}</button></div>` : ''}
    <form class="form" id="bk-service-create">
      <h3 class="panel__title">${escapeHTML(STR.bookingsAddService)}</h3>
      <div class="form__grid form__grid--3">
        <div class="form__row"><label class="lbl" for="bs-name">${escapeHTML(STR.bookingsServiceName)} <span class="req">●</span></label><input class="inp" id="bs-name" required /></div>
        <div class="form__row"><label class="lbl" for="bs-duration">${escapeHTML(STR.bookingsDurationShort)}</label><input class="inp inp--mono" id="bs-duration" type="number" min="5" step="5" value="30" required /></div>
        <div class="form__row"><label class="lbl" for="bs-price">${escapeHTML(STR.bookingsPrice)}</label><input class="inp inp--mono inp--right" id="bs-price" type="number" min="0" step="0.01" placeholder="0.00" /></div>
      </div>
      <div class="actions"><button class="btn btn--primary" type="submit">${escapeHTML(STR.bookingsAddService)}</button></div>
    </form>`;
  const saveRow = async row => {
    const active = $('[data-svc-active]', row).checked;
    const duration = Number($('[data-svc-duration]', row).value || 0);
    try {
      await api(`/app/api/bookings/services/${encodeURIComponent(row.dataset.svcRow)}`, {
        method: 'POST',
        body: JSON.stringify({ active, ...(duration >= 5 ? { durationMinutes: duration } : {}) }),
      });
      state.bookingServices = await api('/app/api/bookings/services').catch(() => state.bookingServices);
      toast(STR.bookingsServiceSaved);
      if (state.active === 'bookings') render();
    } catch (err) { toast(bookingErrorText(err)); }
  };
  $$('[data-svc-row]', body).forEach(row => {
    $('[data-svc-active]', row).addEventListener('change', () => saveRow(row));
    $('[data-svc-duration]', row).addEventListener('change', () => saveRow(row));
  });
  $('[data-go-catalog]', body)?.addEventListener('click', () => { closeDrawer(); setActive('catalog'); });
  $('#bk-service-create', body).addEventListener('submit', async e => {
    e.preventDefault();
    const name = $('#bs-name', body).value.trim();
    if (!name) return;
    const price = $('#bs-price', body).value;
    try {
      await api('/app/api/bookings/services', {
        method: 'POST',
        body: JSON.stringify({ name, durationMinutes: Math.max(5, Number($('#bs-duration', body).value || 30)), ...(price === '' ? {} : { priceEur: Number(price) }) }),
      });
      toast(STR.bookingsServiceSaved);
      state.bookingServices = await api('/app/api/bookings/services').catch(() => state.bookingServices);
      if (state.active === 'bookings') render();
      openBookingServicesForm();
    } catch (err) { toast(bookingErrorText(err)); }
  });
  openDrawer(STR.bookingsManageServices, body, true);
}

function openBookingAvailabilityForm() {
  const form = document.createElement('form');
  form.className = 'form';
  const rules = state.bookingAvailability.length
    ? state.bookingAvailability
    : [1, 2, 3, 4, 5].map(dayOfWeek => ({ dayOfWeek, startLocal: '09:00', endLocal: '18:00' }));
  const rowHtml = r => `<div class="booking-avail-row">
      <select class="sel" name="dayOfWeek" aria-label="${escapeHTML(STR.bookingsDay)}">${[1, 2, 3, 4, 5, 6, 7].map(d => `<option value="${d}" ${r.dayOfWeek === d ? 'selected' : ''}>${escapeHTML(STR[`weekday${d}`])}</option>`).join('')}</select>
      <input class="inp inp--mono" type="time" name="startLocal" value="${escapeHTML(r.startLocal)}" aria-label="${escapeHTML(STR.bookingsFrom)}" />
      <input class="inp inp--mono" type="time" name="endLocal" value="${escapeHTML(r.endLocal)}" aria-label="${escapeHTML(STR.bookingsTo)}" />
      <button class="iconbtn iconbtn--danger" type="button" data-remove-window aria-label="${escapeHTML(STR.bookingsRemoveWindow)}" title="${escapeHTML(STR.bookingsRemoveWindow)}">×</button>
    </div>`;
  form.innerHTML = `
    <p class="hint">${escapeHTML(STR.bookingsHoursHint)}</p>
    <div class="booking-avail-row booking-avail-row--head"><span class="lbl">${escapeHTML(STR.bookingsDay)}</span><span class="lbl">${escapeHTML(STR.bookingsFrom)}</span><span class="lbl">${escapeHTML(STR.bookingsTo)}</span><span></span></div>
    <div class="booking-avail" id="bk-windows">${rules.map(rowHtml).join('')}</div>
    <div class="actions"><button type="button" class="btn btn--sm" id="add-avail">${escapeHTML(STR.bookingsAddWindow)}</button></div>
    <div class="actions"><button class="btn btn--primary" type="submit">${escapeHTML(STR.bookingsSaveHours)}</button></div>`;
  const windows = $('#bk-windows', form);
  const wireRemove = scope => $$('[data-remove-window]', scope).forEach(b => b.addEventListener('click', () => b.closest('.booking-avail-row').remove()));
  wireRemove(windows);
  $('#add-avail', form).addEventListener('click', () => {
    const last = $$('.booking-avail-row', windows).pop();
    const nextDay = last ? Math.min(7, Number($('[name=dayOfWeek]', last).value) + 1) : 1;
    windows.insertAdjacentHTML('beforeend', rowHtml({ dayOfWeek: nextDay, startLocal: '09:00', endLocal: '18:00' }));
    wireRemove(windows.lastElementChild);
  });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const next = $$('.booking-avail-row', windows).map(row => ({
      dayOfWeek: Number($('[name=dayOfWeek]', row).value),
      startLocal: $('[name=startLocal]', row).value,
      endLocal: $('[name=endLocal]', row).value,
    }));
    if (next.some(r => !r.startLocal || !r.endLocal || r.endLocal <= r.startLocal)) return toast(STR.bookingsHoursInvalid);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api('/app/api/bookings/availability', { method: 'PUT', body: JSON.stringify({ rules: next }) });
      closeDrawer();
      toast(STR.bookingsAvailabilitySaved);
      await afterBookingChange();
    } catch (err) {
      btn.disabled = false;
      toast(err?.code === 'invalid_availability' ? STR.bookingsHoursInvalid : STR.bookingsSaveFailed);
    }
  });
  openDrawer(STR.bookingsManageAvailability, form, true);
}
