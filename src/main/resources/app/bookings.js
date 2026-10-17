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
  const live = weekBookings().filter(b => b.status !== 'CANCELLED');
  const todays = state.bookingUpcoming.filter(b => b.status !== 'CANCELLED' && bookingLocal(b).slice(0, 10) === today);
  const pending = pendingUpcomingBookings();
  const weekValue = live.filter(b => b.status !== 'NO_SHOW').reduce((sum, b) => sum + Number(bookingPrice(b) || 0), 0);
  const stats = statCards([
    { label: STR.bookingsStatToday, value: todays.length },
    { label: STR.bookingsStatWeek, value: live.length },
    { label: STR.bookingsStatPending, value: pending.length },
    { label: STR.bookingsStatValue, value: fmtEUR(weekValue) },
  ]);
  const agenda = state.bookingView === 'agenda';
  const atThisWeek = days[0] === periodKey(today, 'week');
  const weekLabel = `${fmtDayKey(days[0], { day: '2-digit', month: 'short' })} – ${fmtDayKey(days[6], { day: '2-digit', month: 'short', year: 'numeric' })}`;
  const toolbar = `<div class="booking-toolbar">
    <div class="booking-toolbar__views">
      <button type="button" class="btn btn--sm ${agenda ? '' : 'btn--primary'}" data-booking-view="week">${escapeHTML(STR.bookingsWeek)}</button>
      <button type="button" class="btn btn--sm ${agenda ? 'btn--primary' : ''}" data-booking-view="agenda">${escapeHTML(STR.bookingsAgenda)}</button>
    </div>
    ${agenda ? '' : `<div class="booking-toolbar__nav period-nav">
      <button type="button" class="btn btn--sm" data-week-shift="-7" aria-label="${escapeHTML(STR.bookingsPrev)}">‹</button>
      <span class="period-nav__label mono">${escapeHTML(weekLabel)}</span>
      <button type="button" class="btn btn--sm" data-week-shift="7" aria-label="${escapeHTML(STR.bookingsNext)}">›</button>
      ${atThisWeek ? '' : `<button type="button" class="btn btn--sm" data-week-shift="0">${escapeHTML(STR.bookingsToday)}</button>`}
    </div>`}
    <div class="booking-toolbar__actions">
      <button type="button" class="btn btn--sm" data-booking-services>${escapeHTML(STR.bookingsManageServices)}</button>
      <button type="button" class="btn btn--sm" data-booking-availability>${escapeHTML(STR.bookingsManageAvailability)}</button>
    </div>
  </div>`;
  const body = agenda ? bookingAgendaHtml() : bookingPendingPanel(pending) + bookingWeekHtml(days, today);
  root.innerHTML = hero(labels.bookings, STR.bookingsDesc, stats) + bookingSetupHtml() + toolbar + body;

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
  $('[data-booking-see-pending]', root)?.addEventListener('click', () => { state.bookingView = 'agenda'; state.bookingStatusFilter = 'PENDING'; render(); });
  $$('[data-booking-status]', root).forEach(b => b.addEventListener('click', e => {
    e.stopPropagation();
    changeBookingStatus(b.dataset.bookingTarget, b.dataset.bookingStatus);
  }));
  $$('[data-booking-open]', root).forEach(el => el.addEventListener('click', e => {
    e.stopPropagation();
    openBookingById(el.dataset.bookingOpen);
  }));
  $$('[data-booking-slot]', root).forEach(el => el.addEventListener('click', e => {
    if (e.target.closest('[data-booking-open]')) return;
    openBookingForm(null, { slot: el.dataset.bookingSlot });
  }));
}

// Nothing can be booked until at least one catalog service is bookable and opening hours exist.
function bookingSetupHtml() {
  const steps = [];
  if (!state.bookingServices.some(s => s.active)) steps.push({ attr: 'data-booking-services', title: STR.bookingsSetupServices, detail: STR.bookingsSetupServicesDesc });
  if (!state.bookingAvailability.length) steps.push({ attr: 'data-booking-availability', title: STR.bookingsSetupHours, detail: STR.bookingsSetupHoursDesc });
  if (!steps.length) return '';
  return `<div class="overview-block"><h2 class="panel__title">${escapeHTML(STR.bookingsSetupTitle)}</h2><div class="setup-list">${steps.map((s, i) => `<button type="button" class="queue__item" ${s.attr}><span class="queue__icon queue__icon--info mono" aria-hidden="true">${i + 1}</span><div><strong>${escapeHTML(s.title)}</strong><span>${escapeHTML(s.detail)}</span></div></button>`).join('')}</div></div>`;
}

function bookingPendingPanel(pending) {
  if (!pending.length) return '';
  const rows = pending.slice(0, 5).map(b => `<tr class="conversation-row" data-booking-open="${escapeHTML(b.id)}">
      <td class="mono">${escapeHTML(bookingWhen(b))}</td>
      <td class="name">${escapeHTML(b.contactName)}<div class="muted mono">${escapeHTML(b.contactPhone)}</div></td>
      <td>${escapeHTML(bookingServiceName(b))}</td>
      <td class="muted">${escapeHTML(bookingSourceLabel(b.source))}</td>
      <td class="right"><div class="actions">
        <button class="btn btn--sm btn--accent" type="button" data-booking-status="CONFIRMED" data-booking-target="${escapeHTML(b.id)}">${escapeHTML(STR.bookingsConfirm)}</button>
        <button class="btn btn--sm btn--ghost" type="button" data-booking-status="CANCELLED" data-booking-target="${escapeHTML(b.id)}">${escapeHTML(STR.bookingsDecline)}</button>
      </div></td>
    </tr>`).join('');
  return crmPanel({
    title: STR.bookingsToConfirm,
    tag: pending.length,
    tools: pending.length > 5 ? `<button class="btn btn--sm btn--ghost" type="button" data-booking-see-pending>${escapeHTML(STR.bookingsToConfirmAll)}</button>` : '',
    head: `<tr><th>${escapeHTML(STR.bookingsThWhen)}</th><th>${escapeHTML(STR.bookingsThContact)}</th><th>${escapeHTML(STR.bookingsThService)}</th><th>${escapeHTML(STR.bookingsSource)}</th><th></th></tr>`,
    rows,
    empty: '',
  });
}

function bookingHourRange(days) {
  let start = 24;
  let end = 0;
  for (const r of state.bookingAvailability) {
    start = Math.min(start, Math.floor(hhmmMinutes(r.startLocal) / 60));
    end = Math.max(end, Math.ceil(hhmmMinutes(r.endLocal) / 60));
  }
  if (start >= end) { start = 8; end = 19; }
  const keys = new Set(days);
  for (const b of state.bookings) {
    const from = bookingLocal(b);
    if (!keys.has(from.slice(0, 10))) continue;
    const to = toLocalInputValue(b.endAt);
    start = Math.min(start, Number(from.slice(11, 13)));
    end = Math.max(end, to.slice(0, 10) === from.slice(0, 10) ? Math.ceil(hhmmMinutes(to.slice(11, 16)) / 60) : 24);
  }
  return { start: Math.max(0, start), end: Math.min(24, Math.max(end, start + 1)) };
}

function bookingWeekHtml(days, today) {
  const { start, end } = bookingHourRange(days);
  const byCell = new Map();
  for (const b of state.bookings) {
    const local = bookingLocal(b);
    const key = `${local.slice(0, 10)}|${Number(local.slice(11, 13))}`;
    if (!byCell.has(key)) byCell.set(key, []);
    byCell.get(key).push(b);
  }
  const hasHours = state.bookingAvailability.length > 0;
  const head = `<div class="cal-grid__corner"></div>${days.map((d, i) => `<div class="cal-grid__dayhead${d === today ? ' is-today' : ''}"><div>${escapeHTML(STR[`weekday${i + 1}`])}</div><div class="mono muted">${escapeHTML(fmtDayKey(d, { day: '2-digit', month: '2-digit' }))}</div></div>`).join('')}`;
  let rows = '';
  for (let hour = start; hour < end; hour += 1) {
    const hh = String(hour).padStart(2, '0');
    const cells = days.map((d, i) => {
      const chips = (byCell.get(`${d}|${hour}`) || [])
        .sort((a, b) => a.startAt.localeCompare(b.startAt))
        .map(b => {
          const service = bookingServiceName(b);
          const title = [bookingTimeRange(b), b.contactName, service, bookingStatusLabel(b.status)].filter(Boolean).join(' · ');
          return `<button type="button" class="cal-event cal-event--${b.status.toLowerCase().replace('_', '-')}" data-booking-open="${escapeHTML(b.id)}" title="${escapeHTML(title)}"><span class="cal-event__time mono">${escapeHTML(bookingTimeRange(b))}</span><span class="cal-event__who">${escapeHTML(b.contactName)}</span>${service ? `<span class="cal-event__what">${escapeHTML(service)}</span>` : ''}</button>`;
        }).join('');
      const open = !hasHours || state.bookingAvailability.some(r => r.dayOfWeek === i + 1 && hhmmMinutes(r.startLocal) < (hour + 1) * 60 && hhmmMinutes(r.endLocal) > hour * 60);
      return `<div class="cal-grid__cell${open ? '' : ' is-closed'}${d === today ? ' is-today' : ''}" data-booking-slot="${d}T${hh}:00">${chips}</div>`;
    }).join('');
    rows += `<div class="cal-grid__hour mono muted">${hh}:00</div>${cells}`;
  }
  return `<div class="panel cal-wrap"><div class="cal-grid">${head}${rows}</div></div>`;
}

function bookingAgendaHtml() {
  const q = state.search.toLowerCase();
  const filter = state.bookingStatusFilter;
  const today = todayKey();
  const list = state.bookingUpcoming
    .filter(b => bookingLocal(b).slice(0, 10) >= today)
    .filter(b => !filter || b.status === filter)
    .filter(b => !q || `${b.contactName} ${b.contactPhone} ${bookingServiceName(b)} ${b.notes || ''}`.toLowerCase().includes(q))
    .sort((a, b) => a.startAt.localeCompare(b.startAt));
  let lastDay = '';
  const rows = list.map(b => {
    const day = bookingLocal(b).slice(0, 10);
    const dayRow = day === lastDay ? '' : `<tr class="is-day"><td colspan="6">${escapeHTML(capFirst([relativeDayLabel(day), fmtDayKey(day, { weekday: 'long', day: 'numeric', month: 'long' })].filter(Boolean).join(' · ')))}</td></tr>`;
    lastDay = day;
    const action = b.status === 'PENDING'
      ? `<button class="btn btn--sm btn--accent" type="button" data-booking-status="CONFIRMED" data-booking-target="${escapeHTML(b.id)}">${escapeHTML(STR.bookingsConfirm)}</button>`
      : `<span class="muted">${escapeHTML(bookingSourceLabel(b.source))}</span>`;
    return `${dayRow}<tr class="conversation-row${b.status === 'CANCELLED' ? ' is-draft' : ''}" data-booking-open="${escapeHTML(b.id)}">
      <td class="mono">${escapeHTML(bookingTimeRange(b))}</td>
      <td class="name">${escapeHTML(b.contactName)}<div class="muted mono">${escapeHTML(b.contactPhone)}</div></td>
      <td>${escapeHTML(bookingServiceName(b))}</td>
      <td class="num">${bookingPrice(b) != null ? fmtEUR(bookingPrice(b)) : '—'}</td>
      <td>${bookingPill(b.status)}</td>
      <td class="right">${action}</td>
    </tr>`;
  }).join('');
  const chips = [''].concat(BOOKING_STATUSES)
    .map(s => `<button class="chip ${filter === s ? 'is-on' : ''}" type="button" data-booking-filter="${s}">${escapeHTML(s ? bookingStatusLabel(s) : STR.bookingsStatusAll)}</button>`)
    .join('');
  const narrowed = Boolean(filter || q);
  return crmPanel({
    title: STR.bookingsAgendaTitle({ days: BOOKING_AGENDA_DAYS }),
    tag: list.length,
    tools: chips,
    head: `<tr><th>${escapeHTML(STR.bookingsThWhen)}</th><th>${escapeHTML(STR.bookingsThContact)}</th><th>${escapeHTML(STR.bookingsThService)}</th><th class="right">${escapeHTML(STR.bookingsPrice)}</th><th>${escapeHTML(STR.bookingsThStatus)}</th><th></th></tr>`,
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
  const actions = [];
  if (b.status === 'PENDING') actions.push(statusButton('CONFIRMED', STR.bookingsConfirm, 'btn--accent'));
  if (b.status === 'PENDING' || b.status === 'CONFIRMED') {
    actions.push(statusButton('COMPLETED', STR.bookingsComplete, b.status === 'CONFIRMED' ? 'btn--accent' : ''));
    actions.push(statusButton('NO_SHOW', STR.bookingsNoShow));
    actions.push(statusButton('CANCELLED', STR.bookingsCancel, 'btn--ghost'));
  } else {
    actions.push(statusButton('CONFIRMED', STR.bookingsReopen, 'btn--ghost'));
  }
  const canInvoice = b.status === 'COMPLETED' && billing === 'OPEN' && hasModule('invoices');
  const billingHint = billing === 'INVOICED'
    ? `<p class="hint"><span class="pill pill--ok">${escapeHTML(STR.bookingsInvoiced)}</span></p>`
    : billing === 'OPEN' ? `<p class="hint">${escapeHTML(STR.bookingsBilled)}</p>` : '';
  const clientLink = !!b.clientId && hasModule('clients') && !linksBackTo(`client:${b.clientId}`);
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `
    ${detailHead(b.contactName, bookingPill(b.status), bookingPrice(b) || 0, bookingTone(b.status), clientLink)}
    ${detailMeta([
      { label: STR.bookingsThWhen, value: bookingWhen(b) },
      { label: STR.bookingsService, value: [bookingServiceName(b), minutes ? fmtMinutes(minutes) : ''].filter(Boolean).join(' · ') },
      { label: STR.bookingsContactPhone, value: b.contactPhone },
      { label: STR.bookingsSource, value: bookingSourceLabel(b.source) },
    ])}
    ${b.notes ? `<p class="hint">${escapeHTML(b.notes)}</p>` : ''}
    ${billingHint}
    <div class="detail__foot">
      ${actions.join('')}
      ${canInvoice ? `<button class="btn btn--sm btn--accent" type="button" data-booking-invoice>${escapeHTML(STR.bookingsInvoice)}</button>` : ''}
      <button class="btn btn--sm btn--ghost" type="button" data-booking-edit>${escapeHTML(STR.bookingsEditAction)}</button>
      ${b.clientServiceId && hasModule('services') && !linksBackTo(`service:${b.clientServiceId}`) ? `<button class="btn btn--sm btn--ghost" type="button" data-booking-service>${escapeHTML(STR.bookingsOpenService)}</button>` : ''}
    </div>`;
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
  openDrawer(bookingServiceName(b) || STR.bookingsEdit, body);
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
