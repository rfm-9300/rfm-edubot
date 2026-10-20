/* The columns of the list tables (Clients, Services, Quotes, Invoices…) and the order each person drags them
   into. Each list defines its columns beside it, as { id, label, head, pin, cell(row, ids) } (clientColumns in
   clients.js…); a pinned column ('start' or 'end': a row's tick box, its actions) keeps its place. Which columns
   the directories show is in directories.js. */

// Kept in this browser, per company and user.
const tableStoreKey = (kind, table) => `${kind}:${state.me?.tenant?.id || ''}:${state.me?.user?.id || 'operator'}:${table}`;
const columnOrderKey = table => tableStoreKey('tableColumnOrder', table);

// The column ids in the order someone moved them to, or none while the list keeps its own order.
function columnOrder(table) {
  const key = columnOrderKey(table);
  if (!state.columnOrders[key]) {
    let saved = null;
    try { saved = JSON.parse(localStorage.getItem(key) || 'null'); } catch { /* unreadable: the list's own order */ }
    state.columnOrders[key] = Array.isArray(saved) ? saved.filter(id => typeof id === 'string') : [];
  }
  return state.columnOrders[key];
}

function saveColumnOrder(table, ids) {
  const key = columnOrderKey(table);
  state.columnOrders[key] = ids;
  try {
    if (ids.length) localStorage.setItem(key, JSON.stringify(ids));
    else localStorage.removeItem(key);
  } catch { /* ignore */ }
}

// The saved order names the columns that were shown when someone last moved one. Any other (hidden then, or a
// company field added since) comes right after the column it follows in the list's own order.
function orderedColumns(table, columns) {
  const saved = columnOrder(table);
  if (!saved.length) return columns;
  const movable = columns.filter(c => !c.pin);
  const ordered = [...new Set(saved)].map(id => movable.find(c => c.id === id)).filter(Boolean);
  movable.forEach((c, i) => {
    if (ordered.includes(c)) return;
    const prev = movable.slice(0, i).reverse().find(p => ordered.includes(p));
    ordered.splice(prev ? ordered.indexOf(prev) + 1 : 0, 0, c);
  });
  return [...columns.filter(c => c.pin === 'start'), ...ordered, ...columns.filter(c => c.pin === 'end')];
}

// Whether `ids`, the shown columns that move, are in the list's own order.
const inOwnOrder = (columns, ids) => {
  const own = columns.filter(c => ids.includes(c.id)).map(c => c.id);
  return ids.every((id, i) => id === own[i]);
};

// Puts column `id` just before `beforeId` (null: after the last one) among `ids` and saves that order; back in the
// list's own order, nothing stays saved.
function moveColumn(table, columns, ids, id, beforeId) {
  const next = ids.filter(x => x !== id);
  const at = beforeId ? next.indexOf(beforeId) : -1;
  next.splice(at < 0 ? next.length : at, 0, id);
  saveColumnOrder(table, inOwnOrder(columns, next) ? [] : next);
}

// With a `table` and at least two columns that move, each of their headings carries its id and takes the focus.
function columnsHead(columns, table) {
  const movable = !!table && columns.filter(c => !c.pin).length > 1;
  const th = c => `<th${c.head ? ` class="${c.head}"` : ''}${movable && !c.pin ? ` data-col="${escapeHTML(c.id)}" tabindex="0" title="${escapeHTML(COLUMNS.moveHint)}"` : ''}>${escapeHTML(c.label)}</th>`;
  return `<tr${movable ? ` data-col-table="${table}"` : ''}>${columns.map(th).join('')}</tr>`;
}
// A cell may read which other columns are shown (`ids`), e.g. the address leaves the city to its own column.
const columnCells = (columns, row, ids) => columns.map(c => c.cell(row, ids)).join('');
// Only there once someone has moved the list's columns.
const columnOrderButton = table => (columnOrder(table).length
  ? `<button class="btn btn--sm btn--ghost" type="button" data-columns-order-reset="${table}">${escapeHTML(COLUMNS.resetOrder)}</button>`
  : '');

// A finger or a pen holds a heading this long before it drags, so a swipe across the headings still scrolls.
const COLUMN_HOLD_MS = 350;

// Dragging a heading sideways moves its column (at once with a mouse); ← and → move the heading that has the
// focus. Each move is saved and the page drawn again.
function wireColumnOrder(root, table, columns) {
  $(`[data-columns-order-reset="${table}"]`, root)?.addEventListener('click', () => { saveColumnOrder(table, []); render(); });
  const row = $(`tr[data-col-table="${table}"]`, root);
  if (!row) return;
  const move = (id, beforeId) => {
    moveColumn(table, columns, $$('th[data-col]', row).map(h => h.dataset.col), id, beforeId);
    render();
  };
  let drag = null;
  row.addEventListener('keydown', e => {
    const th = e.target.closest('th[data-col]');
    const step = { ArrowLeft: -1, ArrowRight: 1 }[e.key];
    if (!th || !step || e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
    e.preventDefault();
    const heads = $$('th[data-col]', row);
    const i = heads.indexOf(th);
    if (!heads[i + step]) return;
    const id = th.dataset.col;
    move(id, step < 0 ? heads[i - 1].dataset.col : heads[i + 2]?.dataset.col || null);
    $$(`tr[data-col-table="${table}"] th[data-col]`).find(h => h.dataset.col === id)?.focus();
  });
  // Once a finger drags a heading the page must not scroll; until then a swipe on the headings does.
  row.addEventListener('touchmove', e => { if (drag?.active) e.preventDefault(); }, { passive: false });
  row.addEventListener('contextmenu', e => { if (drag?.hold) e.preventDefault(); });
  row.addEventListener('pointerdown', e => {
    const th = e.target.closest('th[data-col]');
    if (!th || drag || !e.isPrimary || e.button !== 0) return;
    // A mouse would start selecting the headings' text.
    if (e.pointerType === 'mouse') e.preventDefault();
    drag = dragColumn(e, th, row, move, () => { drag = null; });
  });
}

// One drag, from the press on heading `th`: the column takes the selection tint, a line marks where it will land,
// and a table wider than its panel scrolls near the edges. Escape or a cancelled touch leaves the order as it was.
function dragColumn(down, th, row, onDrop, onEnd) {
  const tbl = row.closest('table');
  const wrap = tbl.closest('.tbl-wrap');
  const heads = $$('th[data-col]', row);
  const from = heads.indexOf(th);
  const others = heads.filter(h => h !== th);
  // Full-width rows (an empty list, a day's heading) have fewer cells and stay as they are.
  const column = cell => [...tbl.rows].filter(r => r.cells.length === row.cells.length).map(r => r.cells[cell.cellIndex]);
  const mark = (cls, cells) => {
    $$(`.${cls}`, tbl).forEach(c => c.classList.remove(cls));
    cells.forEach(c => c.classList.add(cls));
  };
  const s = { hold: down.pointerType !== 'mouse', active: false, x: down.clientX, at: from, timer: 0, frame: 0 };
  const place = () => {
    const at = others.filter(h => { const r = h.getBoundingClientRect(); return r.left + r.width / 2 < s.x; }).length;
    if (at === s.at) return;
    s.at = at;
    mark('is-drop-before', at !== from && others[at] ? column(others[at]) : []);
    mark('is-drop-after', at !== from && !others[at] ? column(others[at - 1]) : []);
  };
  // Faster the nearer the pointer is to the edge, so a drop just inside it doesn't run the table away.
  const scroll = () => {
    const r = wrap.getBoundingClientRect();
    const near = edge => Math.ceil(Math.min(48, Math.max(0, 32 - edge)) / 3);
    const by = near(r.right - s.x) - near(s.x - r.left);
    if (by && wrap.scrollWidth > wrap.clientWidth) {
      wrap.scrollLeft += by;
      place();
    }
    s.frame = requestAnimationFrame(scroll);
  };
  const begin = () => {
    s.active = true;
    try { th.setPointerCapture(down.pointerId); } catch { /* the pointer is already gone */ }
    tbl.classList.add('is-col-dragging');
    column(th).forEach(c => c.classList.add('is-col-dragged'));
    place();
    if (wrap) s.frame = requestAnimationFrame(scroll);
  };
  const end = drop => {
    clearTimeout(s.timer);
    cancelAnimationFrame(s.frame);
    removeEventListener('pointermove', onMove);
    removeEventListener('pointerup', onUp);
    removeEventListener('pointercancel', onCancel);
    removeEventListener('keydown', onKey, true);
    onEnd();
    if (!s.active) return;
    s.active = false;
    tbl.classList.remove('is-col-dragging');
    $$('.is-col-dragged, .is-drop-before, .is-drop-after', tbl).forEach(c => c.classList.remove('is-col-dragged', 'is-drop-before', 'is-drop-after'));
    if (drop && s.at !== from) onDrop(th.dataset.col, others[s.at]?.dataset.col || null);
  };
  const onMove = e => {
    if (e.pointerId !== down.pointerId) return;
    if (!e.buttons) return end(false);
    s.x = e.clientX;
    if (!s.active) {
      const far = Math.hypot(e.clientX - down.clientX, e.clientY - down.clientY);
      // Moving before the hold is up is a swipe: leave it to the browser.
      if (s.hold) {
        if (far > 10) end(false);
        return;
      }
      if (far < 4) return;
      begin();
    }
    place();
  };
  const onUp = e => { if (e.pointerId === down.pointerId) end(true); };
  const onCancel = e => { if (e.pointerId === down.pointerId) end(false); };
  const onKey = e => {
    if (e.key !== 'Escape' || !s.active) return;
    e.preventDefault();
    e.stopPropagation();
    end(false);
  };
  addEventListener('pointermove', onMove);
  addEventListener('pointerup', onUp);
  addEventListener('pointercancel', onCancel);
  addEventListener('keydown', onKey, true);
  if (s.hold) s.timer = setTimeout(begin, COLUMN_HOLD_MS);
  return s;
}
