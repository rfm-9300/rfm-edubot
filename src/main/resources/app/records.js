/* Building blocks of the client, supplier and employee records: contact details and their links (call,
   WhatsApp, maps), the record card, KPIs, worklist rows, tables and empty states, the drawer's foot, and
   deleting, archiving and restoring a record. */

const EMAIL_SHAPE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const phoneDigits = p => String(p || '').replace(/\D/g, '');
// Same rule as the server's findByPhone: numbers of 9+ digits compare on their last 9 digits.
const phoneKey = p => { const d = phoneDigits(p); return d.length >= 9 ? d.slice(-9) : d; };
const sumBy = (list, key) => list.reduce((t, x) => t + Number(x[key] || 0), 0);
const telHref = phone => `tel:${String(phone || '').replace(/[^\d+]/g, '')}`;
const mapsHref = address => `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(address)}`;
// Whole years on the tenant's today, from a yyyy-MM-dd birth date.
function ageOn(birthKey, today = todayKey()) {
  const [by, bm, bd] = birthKey.slice(0, 10).split('-').map(Number);
  const [ty, tm, td] = today.split('-').map(Number);
  return ty - by - (tm < bm || (tm === bm && td < bd) ? 1 : 0);
}
function clientInitials(name) {
  const words = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (!words.length) return '?';
  return (words[0][0] + (words.length > 1 ? words[words.length - 1][0] : '')).toUpperCase();
}
// wa.me needs the country code; a number typed without one is Portuguese, the product's market.
function waHref(phone) {
  const raw = String(phone || '').trim();
  const digits = phoneDigits(raw);
  const intl = raw.startsWith('00') ? digits.slice(2) : (!raw.startsWith('+') && digits.length === 9 ? `351${digits}` : digits);
  return `https://wa.me/${intl}`;
}

function recordRowHtml(item) {
  const side = [
    item.pill ? `<span class="worklist__meta">${item.pill}</span>` : '',
    item.amount != null ? `<span class="worklist__meta worklist__meta--amount">${fmtEUR(item.amount)}</span>` : '',
    item.when ? `<span class="worklist__when">${escapeHTML(item.when)}</span>` : '',
  ].join('');
  return `<li><button class="worklist__item" type="button" data-record-open="${escapeHTML(item.open)}" data-tone="${item.tone || 'neutral'}">
    <span class="worklist__dot" aria-hidden="true"></span>
    <span class="worklist__main"><span class="worklist__title">${escapeHTML(item.title)}</span>${item.detail ? `<span class="worklist__detail">${escapeHTML(item.detail)}</span>` : ''}</span>
    <span class="worklist__side">${side}</span>
  </button></li>`;
}

// Call and WhatsApp buttons for a phone number; extra buttons (open chat…) go after them.
function contactButtons(phone, extra = '') {
  return [
    phoneDigits(phone).length >= 6 ? `<a class="btn btn--sm" href="${escapeHTML(telHref(phone))}">${escapeHTML(STR.clientCall)}</a>` : '',
    phoneDigits(phone).length >= 9 ? `<a class="btn btn--sm" href="${escapeHTML(waHref(phone))}" target="_blank" rel="noopener">${escapeHTML(STR.clientWhatsApp)}</a>` : '',
    extra,
  ].join('');
}

// `lines` and `actions` are trusted HTML built by the caller (escaped values); everything else is escaped here.
// `warning` is a one-line status under the head, like automations being paused for a client.
function recordCardHtml({ name, lines, since, contact = '', notes = '', actions = '', archivedAt = null, warning = '', editable = true }) {
  return `<section class="record-card">
    <div class="record-card__head">
      <span class="record-card__avatar" aria-hidden="true">${escapeHTML(clientInitials(name))}</span>
      <div class="record-card__who">
        <div class="record-card__lines">${lines}</div>
        <p class="record-card__since">${escapeHTML(since)}</p>
      </div>
      <div class="actions">${editable ? `<button class="btn btn--sm btn--ghost" type="button" data-record-edit>${escapeHTML(STR.clientEditAction)}</button>` : ''}${actions}</div>
    </div>
    ${archivedAt ? `<p class="hint hint--warn">${escapeHTML(STR.recordArchivedOn({ date: fmtDay(archivedAt) }))}</p>` : ''}
    ${warning ? `<p class="hint hint--warn">${escapeHTML(warning)}</p>` : ''}
    ${contact ? `<div class="record-card__contact">${contact}</div>` : ''}
    ${notes ? `<div class="record-card__notes"><span class="record-card__notes-label">${escapeHTML(STR.clientFormNotes)}</span>${escapeHTML(notes)}</div>` : ''}
  </section>`;
}

function recordKpisHtml(cells) {
  const shown = cells.slice(0, 4);
  if (!shown.length) return '';
  return `<div class="record-kpis" data-count="${shown.length}">${shown.map(k => `<div class="record-kpi"${k.tone ? ` data-tone="${k.tone}"` : ''} title="${escapeHTML(`${k.label}: ${k.value} · ${k.sub}`)}">
    <span class="record-kpi__label">${escapeHTML(k.label)}</span>
    <span class="record-kpi__value">${escapeHTML(k.value)}</span>
    <span class="record-kpi__sub">${escapeHTML(k.sub)}</span>
  </div>`).join('')}</div>`;
}

function recordAttentionHtml(items) {
  if (!items.length) return '';
  return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(STR.clientAttention)} <span class="tag">${items.length}</span></h2></header><ul class="worklist">${items.map(recordRowHtml).join('')}</ul></section>`;
}

function recordTable(head, rows, heading = null) {
  const title = heading ? `<header class="panel__head"><h2 class="panel__title">${escapeHTML(heading.title)}${heading.count ? ` <span class="tag">${heading.count}</span>` : ''}</h2></header>` : '';
  return `<div class="panel">${title}<div class="tbl-wrap"><table class="tbl"><thead><tr>${head.map(([label, cls]) => `<th${cls ? ` class="${cls}"` : ''}>${escapeHTML(label)}</th>`).join('')}</tr></thead><tbody>${rows}</tbody></table></div></div>`;
}

function recordEmpty(title, act, actLabel) {
  return `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(title)}</p>${act ? `<button class="btn btn--sm" type="button" data-record-act="${act}"><span class="btn__plus">+</span> ${escapeHTML(actLabel)}</button>` : ''}</div></div>`;
}

function recordFootHtml(acts) {
  return acts.length
    ? `<div class="drawer__foot record__foot">${acts.map(([kind, label]) => `<button class="btn btn--sm" type="button" data-record-act="${kind}"><span class="btn__plus">+</span> ${escapeHTML(label)}</button>`).join('')}</div>`
    : '';
}

// A client, supplier or employee that documents refer to can't be deleted (the server answers 409
// `in_use`), only archived, so its quotes, invoices and payments keep their name and PDFs.
function recordRemovalButton(record) {
  return record.archivedAt
    ? `<button class="btn btn--sm btn--ghost" type="button" data-record-restore>${escapeHTML(STR.recordRestore)}</button>`
    : `<button class="btn btn--sm btn--ghost" type="button" data-record-remove>${escapeHTML(STR.recordDelete)}</button>`;
}

async function removeDirectoryRecord(kind, record, { inUse, archiveBody, onDeleted, onArchived }) {
  const url = `/app/api/crm/${kind}/${encodeURIComponent(record.id)}`;
  if (!inUse) {
    if (!await confirmDialog({ title: STR.recordDeleteTitle({ name: record.name }), body: STR.recordDeleteBody, okLabel: STR.recordDelete })) return;
    try {
      await api(url, { method: 'DELETE' });
      toast(STR.recordDeleted);
      return onDeleted();
    } catch (err) {
      // Documents the record didn't show (a module that is off) still count: offer to archive instead.
      if (err?.code !== 'in_use') { if (err?.message !== 'unauthorized') toast(STR.recordRemoveFailed); return; }
    }
  }
  if (!await confirmDialog({ title: STR.recordArchiveTitle({ name: record.name }), body: archiveBody, okLabel: STR.recordArchive, danger: false })) return;
  try {
    const res = await api(`${url}/archive`, { method: 'POST' });
    toast(STR.recordArchived);
    onArchived(res.archivedAt);
  } catch (err) { if (err?.message !== 'unauthorized') toast(STR.recordRemoveFailed); }
}

async function restoreDirectoryRecord(kind, record, onRestored) {
  try {
    await api(`/app/api/crm/${kind}/${encodeURIComponent(record.id)}/restore`, { method: 'POST' });
    toast(STR.recordRestored);
    onRestored();
  } catch (err) { if (err?.message !== 'unauthorized') toast(STR.recordRemoveFailed); }
}

// Pickers read the active lists, so they are reloaded after a record is deleted, archived or restored.
async function refreshDirectory(kind) {
  const [active, archived] = await Promise.all([
    api(`/app/api/crm/${kind}`).catch(() => null),
    state.archivedView === kind ? api(`/app/api/crm/${kind}?archived=1`).catch(() => null) : null,
  ]);
  if (active) state[kind] = active;
  if (archived) state.archivedRows = archived;
  render();
}

// Relative due date of an unpaid document: "was due 12 days ago" or "due in 3 days".
function dueWhenText(dueDate, overdue) {
  return overdue ? STR.clientAttnOverdueDetail({ when: relDay(dueDate) }) : STR.clientDueWhen({ when: relDay(dueDate) });
}
