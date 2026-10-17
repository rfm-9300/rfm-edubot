/* Markup builders shared by the pages and drawers (design-system/components.md): the page hero, period
   navigation, stat cards, list panels, fact lists and the head, facts and lines of a document's detail. */

function hero(title, desc, stats = '', nav = '') {
  const right = nav ? `<div class="view__hero-right">${nav}${stats}</div>` : stats;
  return `<div class="view__hero"><div><h1 class="view__title">${escapeHTML(title)}</h1><p class="view__desc">${escapeHTML(desc)}</p></div>${right}</div>`;
}
function periodNav(selectedKey, grain, attr, t) {
  if (!grain) return '';
  const atCurrent = selectedKey === currentPeriodKey(grain);
  return `<div class="period-nav">
    <button class="btn btn--sm" type="button" aria-label="${escapeHTML(t.periodPrev)}" ${attr}="prev">‹</button>
    <span class="period-nav__label mono">${escapeHTML(periodLabel(selectedKey, grain))}</span>
    <button class="btn btn--sm" type="button" aria-label="${escapeHTML(t.periodNext)}" ${atCurrent ? 'disabled' : ''} ${attr}="next">›</button>
    ${atCurrent ? '' : `<button class="btn btn--sm" type="button" ${attr}="current">${escapeHTML(t.periodCurrent)}</button>`}
  </div>`;
}
function statCards(items, size = '') {
  if (!items.length) return '';
  const sizeCls = size === 'lg' ? ' stat--lg' : '';
  return `<div class="view__stats">${items.map((it, i) => {
    const trendCls = it.trend === 'up' ? ' delta--up' : it.trend === 'down' ? ' delta--down' : '';
    const icon = it.icon ? `<span class="stat__icon" data-icon="${escapeHTML(it.icon)}" aria-hidden="true"></span>` : '';
    return `<div class="stat${sizeCls}${icon ? ' stat--icon' : ''}">${icon}<div class="stat__label">${escapeHTML(it.label)}</div><div class="stat__value${i === items.length - 1 ? ' stat__value--accent' : ''}">${escapeHTML(String(it.value))}</div>${it.hint ? `<div class="stat__hint${trendCls}">${escapeHTML(it.hint)}</div>` : ''}</div>`;
  }).join('')}</div>`;
}
function panelTable(head, rows, empty = STR.noData, emptyDesc = '') {
  const cols = (String(head).match(/<th/g) || []).length || 8;
  const emptyCell = `<tr><td colspan="${cols}"><div class="empty"><p class="empty__title">${escapeHTML(empty)}</p>${emptyDesc ? `<p class="empty__desc">${escapeHTML(emptyDesc)}</p>` : ''}</div></td></tr>`;
  return `<div class="panel"><div class="tbl-wrap"><table class="tbl"><thead>${head}</thead><tbody>${rows || emptyCell}</tbody></table></div></div>`;
}
function crmPanel({ title, tag, views = '', tools = '', head, rows, empty, emptyDesc, emptyArt = '' }) {
  const cols = (String(head).match(/<th/g) || []).length || 8;
  const emptyCell = `<tr><td colspan="${cols}"><div class="empty${emptyArt ? ' empty--art' : ''}">${emptyArt}<p class="empty__title">${escapeHTML(empty)}</p>${emptyDesc ? `<p class="empty__desc">${escapeHTML(emptyDesc)}</p>` : ''}</div></td></tr>`;
  // `views` switches the panel's whole layout (e.g. List / By week / By month). Keep it out of
  // `.panel__tools` so it reads as a mode switch, not a same-weight filter: it stays in the head,
  // and any `tools` (status/client filters) drop to their own `.panel__filters` row underneath.
  const headTools = views ? `<div class="panel__views">${views}</div>` : (tools ? `<div class="panel__tools">${tools}</div>` : '');
  const filtersRow = views && tools ? `<div class="panel__filters">${tools}</div>` : '';
  return `<div class="panel">
    <div class="panel__head">
      <h2 class="panel__title">${escapeHTML(title)}${tag != null ? ` <span class="tag">${escapeHTML(String(tag))}</span>` : ''}</h2>
      ${headTools}
    </div>
    ${filtersRow}
    <div class="tbl-wrap"><table class="tbl"><thead>${head}</thead><tbody>${rows || emptyCell}</tbody></table></div>
  </div>`;
}
function dashFacts(rows, inline = false) {
  const items = rows.filter(Boolean);
  if (!items.length) return '';
  return `<dl class="dash-facts${inline ? ' dash-facts--row' : ''}">${items.map(([label, value]) => `<div><dt>${escapeHTML(label)}</dt><dd>${escapeHTML(String(value))}</dd></div>`).join('')}</dl>`;
}

// With `link`, the name opens that client's or payee's record (wire [data-detail-link]).
function detailHead(clientName, pillHtml, totalEur, tone, link = false) {
  const name = escapeHTML(clientName || '');
  return `<div class="detail__head">
    ${link
      ? `<button class="detail__client detail__client--link" type="button" data-detail-link aria-label="${escapeHTML(STR.detailOpenRecordAria({ name: clientName || '' }))}">${name}</button>`
      : `<div class="detail__client">${name}</div>`}
    <div class="detail__amount">${pillHtml}<span class="detail__figure${tone ? ` detail__figure--${tone}` : ''}">${fmtEUR(totalEur)}</span></div>
  </div>`;
}
function detailMeta(items) {
  const rows = items.filter(Boolean);
  if (!rows.length) return '';
  return `<div class="detail__meta">${rows.map(m => `<div class="detail__meta-item"><span class="detail__meta-label">${escapeHTML(m.label)}</span><span class="detail__meta-value">${escapeHTML(m.value)}</span></div>`).join('')}</div>`;
}
function itemsTable(items) {
  if (!items || !items.length) return '';
  const rows = items.map(it => `<tr><td class="name">${escapeHTML(it.description)}</td><td class="num muted">${it.quantity}${it.unit ? ` ${escapeHTML(it.unit)}` : ''}</td><td class="num muted">${fmtEUR(it.unitPriceEur)}</td><td class="num">${fmtEUR(it.quantity * it.unitPriceEur)}</td></tr>`).join('');
  return `<div class="panel"><div class="tbl-wrap"><table class="tbl"><thead><tr><th>${escapeHTML(STR.lineColDesc)}</th><th class="right">${escapeHTML(STR.lineColQty)}</th><th class="right">${escapeHTML(STR.lineColPrice)}</th><th class="right">${escapeHTML(STR.lineTotal)}</th></tr></thead><tbody>${rows}</tbody></table></div></div>`;
}
