/* Board (/backoffice → Board): the platform team's work, one Trello-style board per project. Cards move between
   columns by dragging (a mouse at once; a finger holds the card first, so a swipe still scrolls) or with Alt and the
   arrow keys, and open in the drawer (board-card.js). Every backoffice admin sees and changes every board. */

let boardState = {
  boards: [], boardId: '', cards: [], people: [], me: null, loaded: false,
  filters: { q: '', who: '', tenant: '' },
  refresh: 0, watching: false, dragging: false, suppressClick: false,
};

const BOARD_STORE_KEY = 'backoffice:board';
const BOARD_HOLD_MS = 350;
const BOARD_REFRESH_MS = 60000;
// MEDIUM, the default, shows no pill.
const BOARD_PRIORITY_PILLS = { LOW: 'pill--info', HIGH: 'pill--warn', URGENT: 'pill--bad' };
const BOARD_PRIORITIES = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];
// Line icons for a card's facts; their strokes take the text color.
const BOARD_ICONS = {
  due: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><rect x="3.5" y="5" width="17" height="15" rx="2"/><path d="M3.5 10h17M8 3v4M16 3v4"/></svg>',
  checklist: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><rect x="3.5" y="3.5" width="17" height="17" rx="3"/><path d="m8 12 3 3 5-6"/></svg>',
  comments: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M20 12a8 8 0 0 1-11.6 7.1L4 20l1-4A8 8 0 1 1 20 12Z"/></svg>',
  tenant: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M4.5 21V5a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v16"/><path d="M15.5 9h2a2 2 0 0 1 2 2v10M3 21h18M8.5 7.5h3M8.5 11.5h3M8.5 15.5h3"/></svg>',
};

const boardCurrent = () => boardState.boards.find(b => b.id === boardState.boardId) || null;
const cardKey = (board, card) => `${board?.key || '?'}-${card.number}`;
const boardTenantName = id => state.tenants.find(t => t.id === id)?.name || '';
const boardColumnOf = (board, card) => board?.columns.find(c => c.id === card.columnId) || null;

// Two letters for an email: the first of each part of its name (ana.ribeiro → AR), or its first two.
function personInitials(email) {
  const name = (email || '').split('@')[0];
  const parts = name.split(/[._-]+/).filter(Boolean);
  return ((parts.length > 1 ? parts[0][0] + parts[1][0] : name.slice(0, 2)) || '?').toUpperCase();
}

// A card's due date as the board shows it; late only while the card isn't in a done column.
function boardDue(card, column) {
  if (!card.dueDate) return null;
  const [y, m, d] = card.dueDate.split('-').map(Number);
  const due = new Date(y, m - 1, d);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const options = due.getFullYear() === today.getFullYear() ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' };
  return { text: due.toLocaleDateString(I18N.locale(), options), late: !column?.done && due < today };
}

function boardErrorText(err, board = null) {
  const E = T.board.errors;
  if (err.code === 'column_not_empty') {
    const name = board?.columns.find(c => c.id === err.detail)?.name || err.detail;
    return E.column_not_empty({ name });
  }
  return E[err.code] || T.error({ msg: err.message });
}

async function renderBoard() {
  try {
    if (!boardState.loaded) await boardLoad();
    if (currentView !== 'board') return;
    await boardLoadCards();
  } catch (e) {
    if (currentView === 'board') $('#view').innerHTML = loadErrorHtml(e);
    return;
  }
  if (currentView !== 'board') return;
  boardPaint();
  boardStartRefresh();
}

async function boardLoad() {
  const [boards, admins] = await Promise.all([
    api('/admin/api/board/boards'),
    api('/admin/api/admins').catch(() => null),
  ]);
  boardState.boards = boards;
  boardState.people = admins ? admins.admins.map(a => a.email) : [];
  boardState.me = admins?.me ? admins.me.toLowerCase() : null;
  if (!boards.some(b => b.id === boardState.boardId)) {
    const saved = localStorage.getItem(BOARD_STORE_KEY);
    boardState.boardId = (boards.find(b => b.id === saved) || boards[0])?.id || '';
  }
  boardState.loaded = true;
}

async function boardLoadCards() {
  const id = boardState.boardId;
  boardState.cards = id ? await api(`/admin/api/board/boards/${encodeURIComponent(id)}/cards`) : [];
}

async function boardSelect(id) {
  boardState.boardId = id;
  localStorage.setItem(BOARD_STORE_KEY, id);
  try {
    await boardLoadCards();
  } catch (e) {
    toast(boardErrorText(e));
  }
  if (currentView === 'board') boardPaint();
}

// Other admins' changes show within a minute, and as soon as the tab is back, unless someone is busy on the board.
function boardStartRefresh() {
  clearInterval(boardState.refresh);
  boardState.refresh = setInterval(boardRefresh, BOARD_REFRESH_MS);
  if (!boardState.watching) {
    boardState.watching = true;
    document.addEventListener('visibilitychange', () => { if (!document.hidden && currentView === 'board') boardRefresh(); });
  }
}

function stopBoardRefresh() {
  clearInterval(boardState.refresh);
  boardState.refresh = 0;
}

async function boardRefresh() {
  if (currentView !== 'board' || boardState.dragging || document.hidden || !$('#drawer').hidden) return;
  const active = document.activeElement;
  if ($('#view').contains(active) && active.matches('input, textarea, select')) return;
  try {
    boardState.loaded = false;
    await boardLoad();
    await boardLoadCards();
  } catch {
    return;
  }
  if (currentView === 'board' && !boardState.dragging && $('#drawer').hidden) boardPaint();
}

function boardMatches(card, board) {
  const { q, who, tenant } = boardState.filters;
  if (who === 'none' && card.assignee) return false;
  if (who === 'me' && card.assignee !== boardState.me) return false;
  if (who && who !== 'none' && who !== 'me' && card.assignee !== who) return false;
  if (tenant && card.tenantId !== tenant) return false;
  const query = q.trim().toLowerCase();
  if (query) {
    const text = [cardKey(board, card), card.title, card.description, ...card.labels].join(' ').toLowerCase();
    if (!text.includes(query)) return false;
  }
  return true;
}

const boardFiltering = () => !!(boardState.filters.q.trim() || boardState.filters.who || boardState.filters.tenant);

function boardPaint() {
  const B = T.board;
  const board = boardCurrent();
  if (board) board.cardCount = boardState.cards.length;
  const focused = document.activeElement?.closest?.('[data-card]')?.dataset.card;
  $('#view').innerHTML = `
    <div class="view__hero"><div><h1 class="view__title">${escapeHTML(B.title)}</h1><p class="view__desc">${escapeHTML(B.desc)}</p></div>
      <div class="actions"><button class="btn btn--primary" type="button" data-board-new><span class="btn__plus" aria-hidden="true">+</span><span class="btn__label">${escapeHTML(B.newBoard)}</span></button></div>
    </div>
    ${board ? boardPanelHtml(board) : `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(B.emptyTitle)}</p><p class="empty__desc">${escapeHTML(B.emptyDesc)}</p></div></div>`}`;
  $('[data-board-new]').addEventListener('click', () => boardCreateDrawer());
  if (!board) return;
  boardBindPanel(board);
  boardBindCols(board);
  if (focused) $(`[data-card="${CSS.escape(focused)}"]`)?.focus();
}

function boardPanelHtml(board) {
  const B = T.board;
  const tabs = boardState.boards.map(b => `<button class="chip ${b.id === board.id ? 'is-on' : ''}" type="button" role="tab" aria-selected="${b.id === board.id}" data-board-pick="${escapeHTML(b.id)}">${escapeHTML(b.name)}<span class="chip__count">${b.cardCount ?? 0}</span></button>`).join('');
  const who = boardState.filters.who;
  const people = boardState.people.map(p => `<option value="${escapeHTML(p)}"${who === p ? ' selected' : ''}>${escapeHTML(p)}</option>`).join('');
  const tenant = boardState.filters.tenant;
  const tenants = state.tenants.filter(t => t.status !== 'DELETED' || t.id === tenant)
    .slice().sort((a, b) => a.name.localeCompare(b.name, I18N.locale()))
    .map(t => `<option value="${escapeHTML(t.id)}"${tenant === t.id ? ' selected' : ''}>${escapeHTML(t.name)}</option>`).join('');
  return `
    <div class="panel board">
      <div class="panel__head">
        <div class="chip-tabs" role="tablist" aria-label="${escapeHTML(B.boardsAria)}">${tabs}</div>
        <div class="panel__tools">
          <button class="btn btn--sm btn--ghost" type="button" data-board-archived>${escapeHTML(B.archivedOpen)}</button>
          <button class="btn btn--sm btn--ghost" type="button" data-board-settings>${escapeHTML(B.settings)}</button>
        </div>
      </div>
      <div class="panel__filters">
        <input class="inp panel__search" type="search" data-board-q value="${escapeHTML(boardState.filters.q)}" placeholder="${escapeHTML(B.searchPlaceholder)}" aria-label="${escapeHTML(B.searchPlaceholder)}" autocomplete="off" spellcheck="false" />
        <select class="sel" data-board-who aria-label="${escapeHTML(B.whoAria)}">
          <option value="">${escapeHTML(B.whoAll)}</option>
          ${boardState.me ? `<option value="me"${who === 'me' ? ' selected' : ''}>${escapeHTML(B.whoMe)}</option>` : ''}
          <option value="none"${who === 'none' ? ' selected' : ''}>${escapeHTML(B.whoNone)}</option>
          ${people}
        </select>
        <select class="sel" data-board-tenant aria-label="${escapeHTML(B.tenantAria)}"><option value="">${escapeHTML(B.tenantAll)}</option>${tenants}</select>
      </div>
      <div class="board__body" data-board-body>${boardColsHtml(board)}</div>
    </div>`;
}

function boardColsHtml(board) {
  const B = T.board;
  const visible = boardState.cards.filter(c => boardMatches(c, board));
  const filtered = boardFiltering()
    ? `<p class="hint board__filtered">${escapeHTML(B.filtered({ shown: visible.length, total: boardState.cards.length }))} <button class="linkbtn" type="button" data-board-clear>${escapeHTML(B.clearFilters)}</button></p>`
    : '';
  return `${filtered}<div class="board__cols" data-board-cols>${board.columns.map(col => boardColumnHtml(board, col, visible.filter(c => c.columnId === col.id))).join('')}</div>`;
}

function boardColumnHtml(board, column, cards) {
  const B = T.board;
  const total = boardState.cards.filter(c => c.columnId === column.id).length;
  return `
    <section class="board-col" data-col="${escapeHTML(column.id)}" aria-label="${escapeHTML(B.columnAria({ name: column.name, n: total }))}">
      <header class="board-col__head"><h2 class="board-col__title">${escapeHTML(column.name)}</h2>${column.done ? `<span class="board-col__done" title="${escapeHTML(B.columnDoneHint)}" role="img" aria-label="${escapeHTML(B.doneColumn)}">${BOARD_ICONS.checklist}</span>` : ''}<span class="tag">${total}</span></header>
      <div class="board-col__cards" data-col-cards="${escapeHTML(column.id)}">${cards.map(c => boardCardHtml(board, column, c)).join('')}</div>
      <div class="board-col__foot" data-col-foot="${escapeHTML(column.id)}">
        <button class="btn btn--sm btn--ghost board-col__add" type="button" data-card-add="${escapeHTML(column.id)}"><span class="btn__plus" aria-hidden="true">+</span> ${escapeHTML(B.addCard)}</button>
      </div>
    </section>`;
}

function boardCardHtml(board, column, card) {
  const B = T.board;
  const due = boardDue(card, column);
  const ticked = card.checklist.filter(i => i.done).length;
  const tenant = card.tenantId ? boardTenantName(card.tenantId) : '';
  const facts = [
    due ? `<span class="board-card__fact${due.late ? ' is-late' : ''}" title="${escapeHTML(due.late ? B.overdue : B.dueDate)}">${BOARD_ICONS.due}${escapeHTML(due.text)}</span>` : '',
    card.checklist.length ? `<span class="board-card__fact${ticked === card.checklist.length ? ' is-done' : ''}" title="${escapeHTML(B.checklist)}">${BOARD_ICONS.checklist}${ticked}/${card.checklist.length}</span>` : '',
    card.commentCount ? `<span class="board-card__fact" title="${escapeHTML(B.comments)}">${BOARD_ICONS.comments}${card.commentCount}</span>` : '',
    tenant ? `<span class="board-card__fact board-card__tenant" title="${escapeHTML(B.tenant)}">${BOARD_ICONS.tenant}<span>${escapeHTML(tenant)}</span></span>` : '',
  ].join('');
  const avatar = card.assignee ? `<span class="board-card__avatar" title="${escapeHTML(card.assignee)}">${escapeHTML(personInitials(card.assignee))}</span>` : '';
  const tone = BOARD_PRIORITY_PILLS[card.priority];
  return `<article class="board-card" data-card="${escapeHTML(card.id)}" tabindex="0" aria-label="${escapeHTML(`${cardKey(board, card)} · ${card.title}`)}">
      <div class="board-card__top"><span class="board-card__key">${escapeHTML(cardKey(board, card))}</span>${tone ? `<span class="pill ${tone}">${escapeHTML(B.priority[card.priority])}</span>` : ''}</div>
      <p class="board-card__title">${escapeHTML(card.title)}</p>
      ${card.description ? `<p class="board-card__desc">${escapeHTML(card.description)}</p>` : ''}
      ${card.labels.length ? `<div class="board-card__labels">${card.labels.map(l => `<span class="board-card__label">${escapeHTML(l)}</span>`).join('')}</div>` : ''}
      ${facts || avatar ? `<div class="board-card__meta">${facts}${avatar}</div>` : ''}
    </article>`;
}

// Redraws the columns only, so the search field keeps its focus and caret while someone types.
function boardPaintCards() {
  const board = boardCurrent();
  const body = $('[data-board-body]');
  if (!board || !body) return boardPaint();
  board.cardCount = boardState.cards.length;
  const focused = document.activeElement?.closest?.('[data-card]')?.dataset.card;
  body.innerHTML = boardColsHtml(board);
  const tab = $(`[data-board-pick="${CSS.escape(board.id)}"] .chip__count`);
  if (tab) tab.textContent = board.cardCount;
  boardBindCols(board);
  if (focused) $(`[data-card="${CSS.escape(focused)}"]`)?.focus();
}

function boardBindPanel(board) {
  const view = $('#view');
  $$('[data-board-pick]', view).forEach(b => b.addEventListener('click', () => { if (b.dataset.boardPick !== board.id) boardSelect(b.dataset.boardPick); }));
  $('[data-board-settings]', view).addEventListener('click', () => boardSettingsDrawer(board));
  $('[data-board-archived]', view).addEventListener('click', () => boardArchivedDrawer(board));
  const q = $('[data-board-q]', view);
  q.addEventListener('input', () => { boardState.filters.q = q.value; boardPaintCards(); });
  $('[data-board-who]', view).addEventListener('change', e => { boardState.filters.who = e.target.value; boardPaintCards(); });
  $('[data-board-tenant]', view).addEventListener('change', e => { boardState.filters.tenant = e.target.value; boardPaintCards(); });
}

function boardBindCols(board) {
  const body = $('[data-board-body]');
  const cols = $('[data-board-cols]', body);
  $('[data-board-clear]', body)?.addEventListener('click', () => {
    boardState.filters = { q: '', who: '', tenant: '' };
    boardPaint();
  });
  $$('[data-card-add]', cols).forEach(b => b.addEventListener('click', () => boardQuickAdd(board, b.dataset.cardAdd)));
  cols.addEventListener('click', e => {
    const card = e.target.closest('[data-card]');
    if (!card || boardState.suppressClick) return;
    openCardDrawer(card.dataset.card);
  });
  cols.addEventListener('keydown', e => {
    const card = e.target.closest('[data-card]');
    if (!card || e.target !== card) return;
    if ((e.key === 'Enter' || e.key === ' ') && !e.altKey) {
      e.preventDefault();
      openCardDrawer(card.dataset.card);
      return;
    }
    const step = { ArrowUp: [0, -1], ArrowDown: [0, 1], ArrowLeft: [-1, 0], ArrowRight: [1, 0] }[e.key];
    if (!step || !e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
    e.preventDefault();
    boardStep(board, card.dataset.card, step[0], step[1]);
  });
  let drag = null;
  // Once a finger drags a card the page must not scroll; until then a swipe over the cards does.
  cols.addEventListener('touchmove', e => { if (boardState.dragging) e.preventDefault(); }, { passive: false });
  cols.addEventListener('contextmenu', e => { if (drag?.hold) e.preventDefault(); });
  cols.addEventListener('pointerdown', e => {
    const card = e.target.closest('[data-card]');
    if (!card || drag || !e.isPrimary || e.button !== 0) return;
    if (e.pointerType === 'mouse') e.preventDefault();
    drag = boardDrag(e, card, cols, (el, list) => boardDropped(board, el, list), () => { drag = null; });
  });
}

// Alt and an arrow: up and down within the column, left and right to the neighbouring column at the same height.
function boardStep(board, cardId, dx, dy) {
  const card = boardState.cards.find(c => c.id === cardId);
  if (!card) return;
  const columns = board.columns.map(c => c.id);
  const from = columns.indexOf(card.columnId);
  const inColumn = id => boardState.cards.filter(c => c.columnId === id);
  const index = inColumn(card.columnId).indexOf(card);
  if (dx) {
    const to = columns[from + dx];
    if (!to) return;
    boardMove(cardId, to, Math.min(index, inColumn(to).length)).then(() => $(`[data-card="${CSS.escape(cardId)}"]`)?.focus());
  } else {
    const next = index + dy;
    if (next < 0 || next >= inColumn(card.columnId).length) return;
    boardMove(cardId, card.columnId, next).then(() => $(`[data-card="${CSS.escape(cardId)}"]`)?.focus());
  }
}

// Where a dropped card landed, read from its neighbours in the list, so a filtered list still drops in the right place.
function boardDropped(board, el, list) {
  const columnId = list.dataset.colCards;
  const cardId = el.dataset.card;
  const others = boardState.cards.filter(c => c.columnId === columnId && c.id !== cardId);
  const next = el.nextElementSibling?.dataset.card;
  const prev = el.previousElementSibling?.dataset.card;
  let index = others.length;
  if (next && others.some(c => c.id === next)) index = others.findIndex(c => c.id === next);
  else if (prev && others.some(c => c.id === prev)) index = others.findIndex(c => c.id === prev) + 1;
  boardMove(cardId, columnId, index);
}

// Moves the card on the page at once, then on the server; a refusal reloads the board as it is.
async function boardMove(cardId, columnId, index) {
  const card = boardState.cards.find(c => c.id === cardId);
  if (!card) return;
  const current = boardState.cards.filter(c => c.columnId === card.columnId);
  if (card.columnId === columnId && current.indexOf(card) === index) {
    boardPaintCards();
    return;
  }
  const target = boardState.cards.filter(c => c.columnId === columnId && c.id !== cardId);
  const rest = boardState.cards.filter(c => c.id !== cardId);
  const moved = { ...card, columnId };
  const anchor = target[index];
  const last = target[target.length - 1];
  if (anchor) rest.splice(rest.indexOf(anchor), 0, moved);
  else if (last) rest.splice(rest.indexOf(last) + 1, 0, moved);
  else rest.push(moved);
  boardState.cards = rest;
  boardPaintCards();
  try {
    const saved = await api(`/admin/api/board/cards/${encodeURIComponent(cardId)}/move`, { method: 'POST', body: JSON.stringify({ columnId, index }) });
    boardState.cards = boardState.cards.map(c => (c.id === saved.id ? saved : c));
  } catch (e) {
    toast(boardErrorText(e));
    await boardLoadCards().catch(() => {});
    if (currentView === 'board') boardPaintCards();
  }
}

// The scroller that moves the page up and down: the document, unless something above the board scrolls itself.
function boardScroller(el) {
  for (let node = el.parentElement; node && node !== document.body; node = node.parentElement) {
    const overflow = getComputedStyle(node).overflowY;
    if ((overflow === 'auto' || overflow === 'scroll') && node.scrollHeight > node.clientHeight) return node;
  }
  return document.scrollingElement;
}

// One drag, from the press on card `cardEl`: the card follows the pointer, a dashed slot shows where it will land,
// and the board scrolls near its edges. Escape or a cancelled touch puts it back.
function boardDrag(down, cardEl, cols, onDrop, onEnd) {
  const s = { hold: down.pointerType !== 'mouse', active: false, x: down.clientX, y: down.clientY, timer: 0, frame: 0, slot: null, dx: 0, dy: 0 };
  const page = boardScroller(cols);
  const follow = () => {
    cardEl.style.left = `${s.x - s.dx}px`;
    cardEl.style.top = `${s.y - s.dy}px`;
  };
  const place = () => {
    const under = document.elementFromPoint(s.x, s.y);
    const list = under?.closest('[data-col-cards]') || under?.closest('.board-col')?.querySelector('[data-col-cards]');
    if (!list || !cols.contains(list)) return;
    const others = [...list.children].filter(el => el !== cardEl && el !== s.slot);
    const before = others.find(el => { const r = el.getBoundingClientRect(); return s.y < r.top + r.height / 2; });
    if (before) { if (s.slot.nextElementSibling !== before) list.insertBefore(s.slot, before); }
    else if (list.lastElementChild !== s.slot) list.appendChild(s.slot);
    $$('.board-col', cols).forEach(c => c.classList.toggle('is-drop', c.contains(s.slot)));
  };
  // Faster the nearer the pointer is to the edge, so a drop just inside it doesn't run the board away.
  const scroll = () => {
    const near = edge => Math.ceil(Math.min(48, Math.max(0, 40 - edge)) / 3);
    const box = cols.getBoundingClientRect();
    const byX = near(box.right - s.x) - near(s.x - box.left);
    if (byX && cols.scrollWidth > cols.clientWidth) { cols.scrollLeft += byX; place(); }
    const byY = near(innerHeight - s.y) - near(s.y);
    if (byY) { page.scrollTop += byY; place(); }
    s.frame = requestAnimationFrame(scroll);
  };
  const begin = () => {
    s.active = true;
    boardState.dragging = true;
    const r = cardEl.getBoundingClientRect();
    s.dx = down.clientX - r.left;
    s.dy = down.clientY - r.top;
    s.slot = document.createElement('div');
    s.slot.className = 'board-card board-card--slot';
    s.slot.style.height = `${r.height}px`;
    cardEl.after(s.slot);
    cardEl.style.width = `${r.width}px`;
    cardEl.classList.add('is-dragging');
    follow();
    cols.closest('.board')?.classList.add('is-dragging');
    try { cardEl.setPointerCapture(down.pointerId); } catch { /* the pointer is already gone */ }
    place();
    s.frame = requestAnimationFrame(scroll);
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
    cols.closest('.board')?.classList.remove('is-dragging');
    $$('.board-col.is-drop', cols).forEach(c => c.classList.remove('is-drop'));
    cardEl.classList.remove('is-dragging');
    cardEl.style.left = cardEl.style.top = cardEl.style.width = '';
    const list = s.slot.parentElement;
    if (drop && list) s.slot.replaceWith(cardEl);
    else s.slot.remove();
    // The click that ends a drag isn't a click on the card.
    boardState.suppressClick = true;
    setTimeout(() => { boardState.suppressClick = false; boardState.dragging = false; }, 0);
    if (drop && list) onDrop(cardEl, list);
  };
  const onMove = e => {
    if (e.pointerId !== down.pointerId) return;
    if (!e.buttons) return end(false);
    s.x = e.clientX;
    s.y = e.clientY;
    if (!s.active) {
      const far = Math.hypot(e.clientX - down.clientX, e.clientY - down.clientY);
      // Moving before the hold is up is a swipe: leave it to the browser.
      if (s.hold) {
        if (far > 10) end(false);
        return;
      }
      if (far < 5) return;
      begin();
    }
    follow();
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
  if (s.hold) s.timer = setTimeout(begin, BOARD_HOLD_MS);
  return s;
}

// "+ Add card" turns into a field at the foot of the column; Enter adds the card and leaves the field for the next.
function boardQuickAdd(board, columnId) {
  const B = T.board;
  const foot = $(`[data-col-foot="${CSS.escape(columnId)}"]`);
  if (!foot) return;
  foot.innerHTML = `
    <form class="board-col__new" novalidate>
      <input class="inp" type="text" maxlength="500" placeholder="${escapeHTML(B.newCardPlaceholder)}" aria-label="${escapeHTML(B.newCardPlaceholder)}" autocomplete="off" />
      <div class="actions"><button class="btn btn--sm btn--primary" type="submit">${escapeHTML(B.add)}</button><button class="btn btn--sm btn--ghost" type="button" data-new-cancel>${escapeHTML(T.cancel)}</button></div>
    </form>`;
  const form = $('form', foot);
  const input = $('input', form);
  const close = () => boardPaintCards();
  input.focus();
  input.addEventListener('keydown', e => { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); } });
  $('[data-new-cancel]', form).addEventListener('click', close);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const title = input.value.trim();
    if (!title) { input.focus(); return; }
    const button = $('button[type=submit]', form);
    button.disabled = true;
    // A card added while the board is filtered by tenant or person takes that filter, so it stays in sight.
    const { who, tenant } = boardState.filters;
    const assignee = who === 'me' ? boardState.me : (who && who !== 'none' ? who : null);
    try {
      const card = await api(`/admin/api/board/boards/${encodeURIComponent(board.id)}/cards`, {
        method: 'POST',
        body: JSON.stringify({ columnId, title, assignee, tenantId: tenant || null }),
      });
      boardState.cards.push(card);
      boardPaintCards();
      boardQuickAdd(board, columnId);
    } catch (err) {
      toast(boardErrorText(err, board));
      button.disabled = false;
    }
  });
}
