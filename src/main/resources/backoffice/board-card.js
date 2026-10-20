/* The board's drawers (/backoffice → Board): a card with its checklist and comments, a new card from a tenant's
   record, a new board, a board's settings and columns, and its archived cards. Also the tenant record's list of
   the cards linked to that tenant. */

// The people a card can be assigned to and the boards, when a drawer opens outside the Board view.
async function boardEnsureLoaded() {
  if (!boardState.loaded) await boardLoad();
}

async function boardReloadIfShown() {
  if (currentView !== 'board') return;
  try {
    await boardLoadCards();
  } catch {
    return;
  }
  if (currentView === 'board') boardPaintCards();
}

const boardOptions = (items, selected) => items.map(([value, label]) => `<option value="${escapeHTML(value)}"${value === selected ? ' selected' : ''}>${escapeHTML(label)}</option>`).join('');

function boardPeopleOptions(selected) {
  const B = T.board;
  const people = [...boardState.people];
  if (selected && !people.includes(selected)) people.push(selected);
  return boardOptions([['', B.nobody], ...people.map(p => [p, p])], selected || '');
}

function boardTenantOptions(selected) {
  const B = T.board;
  const tenants = state.tenants.filter(t => t.status !== 'DELETED' || t.id === selected)
    .slice().sort((a, b) => a.name.localeCompare(b.name, I18N.locale()));
  return boardOptions([['', B.noTenant], ...tenants.map(t => [t.id, t.name])], selected || '');
}

const boardPriorityOptions = selected => boardOptions(BOARD_PRIORITIES.map(p => [p, T.board.priority[p]]), selected || 'MEDIUM');

// Labels typed into one field: Enter or a comma adds one, Backspace in the empty field takes the last back.
function boardLabelsHtml(labels, id) {
  const B = T.board;
  return `<div class="tokens" data-labels>${labels.map(boardLabelTokenHtml).join('')}<input class="tokens__input" id="${id}" maxlength="30" placeholder="${escapeHTML(B.labelsPlaceholder)}" autocomplete="off" /></div>`;
}

const boardLabelTokenHtml = label => `<span class="token" data-label="${escapeHTML(label)}"><button class="token__text" type="button">${escapeHTML(label)}</button><button class="token__rm" type="button" aria-label="${escapeHTML(T.board.removeLabel({ label }))}">×</button></span>`;

function boardBindLabels(root) {
  const box = $('[data-labels]', root);
  const input = $('.tokens__input', box);
  const add = text => {
    const label = text.trim().replace(/,+$/, '').trim();
    if (!label) return;
    if (boardReadLabels(root, false).some(l => l.toLowerCase() === label.toLowerCase())) return;
    input.insertAdjacentHTML('beforebegin', boardLabelTokenHtml(label));
  };
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      add(input.value);
      input.value = '';
    } else if (e.key === 'Backspace' && !input.value) {
      const last = [...box.querySelectorAll('.token')].pop();
      if (last) { input.value = last.dataset.label; last.remove(); e.preventDefault(); }
    }
  });
  input.addEventListener('blur', () => { add(input.value); input.value = ''; });
  box.addEventListener('click', e => {
    const token = e.target.closest('.token');
    if (!token) return;
    if (e.target.closest('.token__rm')) token.remove();
    else if (e.target.closest('.token__text')) { input.value = token.dataset.label; token.remove(); }
    input.focus();
  });
}

function boardReadLabels(root, withTyped = true) {
  const labels = $$('[data-labels] .token', root).map(t => t.dataset.label);
  const typed = withTyped ? $('[data-labels] .tokens__input', root).value.trim() : '';
  return typed && !labels.some(l => l.toLowerCase() === typed.toLowerCase()) ? [...labels, typed] : labels;
}

const boardWho = email => (email ? email : T.board.passwordSession);

// A card in the drawer: its fields save with the footer's button; the checklist and the comments save as they change.
async function openCardDrawer(cardId, back = null) {
  const B = T.board;
  let detail;
  try {
    [detail] = await Promise.all([api(`/admin/api/board/cards/${encodeURIComponent(cardId)}`), boardEnsureLoaded()]);
  } catch (e) {
    toast(boardErrorText(e));
    return;
  }
  const { board } = detail;
  let card = detail.card;
  let comments = detail.comments;
  const key = cardKey(board, card);
  const body = document.createElement('div');
  body.className = 'board-detail';
  body.innerHTML = `
    ${card.archived ? `<p class="hint hint--warn">${escapeHTML(B.archivedNotice)}</p>` : ''}
    <form class="form" novalidate data-card-form>
      <div class="form__row"><label class="lbl" for="card-title">${escapeHTML(B.titleLabel)} <span class="req">*</span></label><input class="inp" id="card-title" maxlength="200" value="${escapeHTML(card.title)}" /></div>
      <div class="form__grid">
        <div class="form__row"><label class="lbl" for="card-column">${escapeHTML(B.columnLabel)}</label><select class="sel" id="card-column">${boardOptions(board.columns.map(c => [c.id, c.name]), card.columnId)}</select></div>
        <div class="form__row"><label class="lbl" for="card-assignee">${escapeHTML(B.assigneeLabel)}</label><select class="sel" id="card-assignee">${boardPeopleOptions(card.assignee)}</select></div>
        <div class="form__row"><label class="lbl" for="card-priority">${escapeHTML(B.priorityLabel)}</label><select class="sel" id="card-priority">${boardPriorityOptions(card.priority)}</select></div>
        <div class="form__row"><label class="lbl" for="card-due">${escapeHTML(B.dueLabel)} <span class="opt">${escapeHTML(B.optional)}</span></label><input class="inp" id="card-due" type="date" value="${escapeHTML(card.dueDate || '')}" /></div>
        <div class="form__row form__row--full"><label class="lbl" for="card-tenant">${escapeHTML(B.tenantLabel)} <span class="opt">${escapeHTML(B.optional)}</span></label><select class="sel" id="card-tenant">${boardTenantOptions(card.tenantId)}</select></div>
        <div class="form__row form__row--full"><label class="lbl" for="card-labels">${escapeHTML(B.labelsLabel)} <span class="opt">${escapeHTML(B.optional)}</span></label>${boardLabelsHtml(card.labels, 'card-labels')}</div>
        <div class="form__row form__row--full"><label class="lbl" for="card-description">${escapeHTML(B.descriptionLabel)} <span class="opt">${escapeHTML(B.optional)}</span></label><textarea class="txt" id="card-description" rows="5" maxlength="20000" placeholder="${escapeHTML(B.descriptionPlaceholder)}">${escapeHTML(card.description)}</textarea></div>
      </div>
    </form>
    <section class="board-detail__section" data-checklist></section>
    <section class="board-detail__section" data-comments></section>
    <p class="hint drawer__meta" data-card-meta></p>
    <div class="actions drawer__actions">
      <button class="btn btn--sm btn--ghost" type="button" data-card-archive>${escapeHTML(card.archived ? B.restore : B.archive)}</button>
      <button class="btn btn--sm btn--danger" type="button" data-card-delete>${escapeHTML(B.deleteCard)}</button>
    </div>`;
  boardBindLabels(body);

  const paintMeta = () => {
    const created = B.createdLine({ date: fmtDate(card.createdAt), by: boardWho(card.createdBy) });
    const changed = card.updatedAt !== card.createdAt ? ` · ${B.changedLine({ date: fmtDate(card.updatedAt), by: boardWho(card.updatedBy) })}` : '';
    $('[data-card-meta]', body).textContent = `${key} · ${created}${changed}`;
  };
  // The checklist saves each change at once and redraws only itself, so unsaved edits above it stay.
  const saveChecklist = async items => {
    try {
      card = await api(`/admin/api/board/cards/${encodeURIComponent(card.id)}`, { method: 'PATCH', body: JSON.stringify({ checklist: items }) });
      paintChecklist();
      paintMeta();
      boardReloadIfShown();
      return true;
    } catch (e) {
      toast(boardErrorText(e, board));
      return false;
    }
  };
  const paintChecklist = () => {
    const host = $('[data-checklist]', body);
    const items = card.checklist;
    const ticked = items.filter(i => i.done).length;
    const pct = items.length ? Math.round((ticked * 100) / items.length) : 0;
    host.innerHTML = `
      <header class="board-detail__head"><h3 class="board-detail__title">${escapeHTML(B.checklist)}${items.length ? ` <span class="tag">${ticked}/${items.length}</span>` : ''}</h3></header>
      ${items.length ? `<div class="meter board-detail__meter" aria-hidden="true"><div class="meter__fill" style="width:${pct}%"></div></div>` : ''}
      ${items.length ? `<ul class="checklist">${items.map(i => `<li class="checklist__item"><label class="form__check"><input type="checkbox" data-check="${escapeHTML(i.id)}"${i.done ? ' checked' : ''} /><span class="checklist__text${i.done ? ' is-done' : ''}">${escapeHTML(i.text)}</span></label><button class="checklist__rm" type="button" data-check-rm="${escapeHTML(i.id)}" aria-label="${escapeHTML(B.removeItem({ item: i.text }))}">×</button></li>`).join('')}</ul>` : ''}
      <form class="checklist__add" novalidate><input class="inp" maxlength="300" placeholder="${escapeHTML(B.addItemPlaceholder)}" aria-label="${escapeHTML(B.addItemPlaceholder)}" autocomplete="off" /><button class="btn btn--sm" type="submit">${escapeHTML(B.addItem)}</button></form>`;
    $$('[data-check]', host).forEach(box => box.addEventListener('change', () => {
      saveChecklist(card.checklist.map(i => (i.id === box.dataset.check ? { ...i, done: box.checked } : i)));
    }));
    $$('[data-check-rm]', host).forEach(b => b.addEventListener('click', () => {
      saveChecklist(card.checklist.filter(i => i.id !== b.dataset.checkRm));
    }));
    const form = $('.checklist__add', host);
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const input = $('input', form);
      const text = input.value.trim();
      if (!text) { input.focus(); return; }
      if (await saveChecklist([...card.checklist, { text, done: false }])) $('[data-checklist] .checklist__add input', body)?.focus();
    });
  };
  const paintComments = () => {
    const host = $('[data-comments]', body);
    const me = boardState.me;
    host.innerHTML = `
      <header class="board-detail__head"><h3 class="board-detail__title">${escapeHTML(B.commentsTitle)}${comments.length ? ` <span class="tag">${comments.length}</span>` : ''}</h3></header>
      ${comments.length ? `<ul class="comments">${comments.map(c => {
        const mine = (c.author || '').toLowerCase() === (me || '');
        return `<li class="comment">
          <div class="comment__head"><span class="board-card__avatar" aria-hidden="true">${escapeHTML(personInitials(c.author || '?'))}</span><span class="comment__author">${escapeHTML(boardWho(c.author))}${mine ? `<span class="muted"> · ${escapeHTML(B.you)}</span>` : ''}</span><span class="comment__when">${escapeHTML(fmtDate(c.createdAt))}</span>
            ${mine ? `<button class="btn btn--sm btn--ghost comment__rm" type="button" data-comment-rm="${escapeHTML(c.id)}">${escapeHTML(B.deleteComment)}</button>` : ''}</div>
          <p class="comment__body">${escapeHTML(c.body)}</p>
        </li>`;
      }).join('')}</ul>` : `<p class="dash-empty">${escapeHTML(B.noComments)}</p>`}
      <form class="comment-form" novalidate><textarea class="txt" rows="3" maxlength="5000" placeholder="${escapeHTML(B.commentPlaceholder)}" aria-label="${escapeHTML(B.commentPlaceholder)}"></textarea><div class="actions"><button class="btn btn--sm btn--primary" type="submit">${escapeHTML(B.addComment)}</button></div></form>`;
    $$('[data-comment-rm]', host).forEach(b => b.addEventListener('click', async () => {
      if (!await confirmDialog({ title: B.deleteCommentTitle, body: B.deleteCommentBody, okLabel: B.deleteComment })) return;
      try {
        await api(`/admin/api/board/comments/${encodeURIComponent(b.dataset.commentRm)}`, { method: 'DELETE' });
        comments = comments.filter(c => c.id !== b.dataset.commentRm);
        paintComments();
        boardReloadIfShown();
      } catch (e) {
        toast(boardErrorText(e));
      }
    }));
    const form = $('.comment-form', host);
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const area = $('textarea', form);
      const text = area.value.trim();
      if (!text) { area.focus(); return; }
      const button = $('button[type=submit]', form);
      button.disabled = true;
      try {
        const comment = await api(`/admin/api/board/cards/${encodeURIComponent(card.id)}/comments`, { method: 'POST', body: JSON.stringify({ body: text }) });
        comments = [...comments, comment];
        paintComments();
        boardReloadIfShown();
      } catch (err) {
        toast(boardErrorText(err));
        button.disabled = false;
      }
    });
  };
  paintChecklist();
  paintComments();
  paintMeta();

  const leave = () => (back ? back.open() : closeDrawer());
  $('[data-card-archive]', body).addEventListener('click', async () => {
    try {
      await api(`/admin/api/board/cards/${encodeURIComponent(card.id)}`, { method: 'PATCH', body: JSON.stringify({ archived: !card.archived }) });
      toast(card.archived ? B.restored({ key }) : B.archivedToast({ key }));
      boardReloadIfShown();
      leave();
    } catch (e) {
      toast(boardErrorText(e));
    }
  });
  $('[data-card-delete]', body).addEventListener('click', async () => {
    if (!await confirmDialog({ title: B.deleteCardTitle, body: B.deleteCardBody({ key, title: card.title }), okLabel: B.deleteCard })) return;
    try {
      await api(`/admin/api/board/cards/${encodeURIComponent(card.id)}`, { method: 'DELETE' });
      toast(B.cardDeleted({ key }));
      boardReloadIfShown();
      leave();
    } catch (e) {
      toast(boardErrorText(e));
    }
  });

  openDrawer({
    title: card.title,
    eyebrow: `${board.name} · ${key}`,
    body,
    back,
    saveLabel: B.saveCard,
    autofocus: false,
    onSave: async () => {
      const title = $('#card-title', body).value.trim();
      if (!title) {
        $('#card-title', body).setAttribute('aria-invalid', 'true');
        $('#card-title', body).focus();
        toast(B.errors.required);
        return false;
      }
      const values = {
        title,
        description: $('#card-description', body).value.trim(),
        assignee: $('#card-assignee', body).value || null,
        priority: $('#card-priority', body).value,
        dueDate: $('#card-due', body).value || null,
        tenantId: $('#card-tenant', body).value || null,
        labels: boardReadLabels(body),
      };
      // Only what changed is sent, so a field someone else changed meanwhile isn't put back.
      const changes = Object.fromEntries(Object.entries(values).filter(([k, v]) => JSON.stringify(v) !== JSON.stringify(card[k] ?? (k === 'labels' ? [] : null))));
      const columnId = $('#card-column', body).value;
      try {
        if (Object.keys(changes).length) card = await api(`/admin/api/board/cards/${encodeURIComponent(card.id)}`, { method: 'PATCH', body: JSON.stringify(changes) });
        // A card sent to another column from here goes to the bottom of it.
        if (columnId !== card.columnId) card = await api(`/admin/api/board/cards/${encodeURIComponent(card.id)}/move`, { method: 'POST', body: JSON.stringify({ columnId, index: 1000000 }) });
      } catch (e) {
        toast(boardErrorText(e, board));
        return false;
      }
      toast(B.cardSaved({ key }));
      boardReloadIfShown();
      return true;
    },
  });
}

// A card for a tenant, from its record: the board and column to put it in, and the main fields.
async function newCardDrawer({ tenantId = null, back = null } = {}) {
  const B = T.board;
  try {
    await boardEnsureLoaded();
  } catch (e) {
    toast(boardErrorText(e));
    return;
  }
  if (!boardState.boards.length) { toast(B.noBoardsYet); return; }
  const first = boardCurrent() || boardState.boards[0];
  const body = document.createElement('div');
  body.innerHTML = `
    <form class="form" novalidate>
      <div class="form__grid">
        <div class="form__row"><label class="lbl" for="new-card-board">${escapeHTML(B.boardLabel)}</label><select class="sel" id="new-card-board">${boardOptions(boardState.boards.map(b => [b.id, b.name]), first.id)}</select></div>
        <div class="form__row"><label class="lbl" for="new-card-column">${escapeHTML(B.columnLabel)}</label><select class="sel" id="new-card-column"></select></div>
        <div class="form__row form__row--full"><label class="lbl" for="new-card-title">${escapeHTML(B.titleLabel)} <span class="req">*</span></label><input class="inp" id="new-card-title" maxlength="200" /></div>
        <div class="form__row"><label class="lbl" for="new-card-assignee">${escapeHTML(B.assigneeLabel)}</label><select class="sel" id="new-card-assignee">${boardPeopleOptions(boardState.me)}</select></div>
        <div class="form__row"><label class="lbl" for="new-card-priority">${escapeHTML(B.priorityLabel)}</label><select class="sel" id="new-card-priority">${boardPriorityOptions('MEDIUM')}</select></div>
        <div class="form__row"><label class="lbl" for="new-card-due">${escapeHTML(B.dueLabel)} <span class="opt">${escapeHTML(B.optional)}</span></label><input class="inp" id="new-card-due" type="date" /></div>
        <div class="form__row"><label class="lbl" for="new-card-tenant">${escapeHTML(B.tenantLabel)}</label><select class="sel" id="new-card-tenant">${boardTenantOptions(tenantId)}</select></div>
        <div class="form__row form__row--full"><label class="lbl" for="new-card-description">${escapeHTML(B.descriptionLabel)} <span class="opt">${escapeHTML(B.optional)}</span></label><textarea class="txt" id="new-card-description" rows="4" maxlength="20000"></textarea></div>
      </div>
    </form>`;
  const columns = () => {
    const board = boardState.boards.find(b => b.id === $('#new-card-board', body).value);
    $('#new-card-column', body).innerHTML = boardOptions((board?.columns || []).map(c => [c.id, c.name]), board?.columns[0]?.id);
  };
  columns();
  $('#new-card-board', body).addEventListener('change', columns);
  openDrawer({
    title: B.newCard,
    eyebrow: B.title,
    body,
    back,
    saveLabel: B.createCard,
    onSave: async () => {
      const title = $('#new-card-title', body).value.trim();
      if (!title) {
        $('#new-card-title', body).setAttribute('aria-invalid', 'true');
        $('#new-card-title', body).focus();
        toast(B.errors.required);
        return false;
      }
      const boardId = $('#new-card-board', body).value;
      try {
        const card = await api(`/admin/api/board/boards/${encodeURIComponent(boardId)}/cards`, {
          method: 'POST',
          body: JSON.stringify({
            columnId: $('#new-card-column', body).value,
            title,
            assignee: $('#new-card-assignee', body).value || null,
            priority: $('#new-card-priority', body).value,
            dueDate: $('#new-card-due', body).value || null,
            tenantId: $('#new-card-tenant', body).value || null,
            description: $('#new-card-description', body).value.trim(),
          }),
        });
        toast(B.cardCreated({ key: cardKey(boardState.boards.find(b => b.id === boardId), card) }));
      } catch (e) {
        toast(boardErrorText(e));
        return false;
      }
      boardReloadIfShown();
      return true;
    },
  });
}

// A key from a board's name: the first letter of each word (Customer Success → CS), or a lone word's first three.
function boardKeyFrom(name) {
  const words = name.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toUpperCase().split(/[^A-Z0-9]+/).filter(Boolean);
  const raw = words.length > 1 ? words.map(w => w[0]).join('').slice(0, 4) : (words[0] || '').slice(0, 3);
  const key = raw.replace(/^[0-9]+/, '');
  return key.length >= 2 ? key : '';
}

function boardCreateDrawer() {
  const B = T.board;
  const body = document.createElement('div');
  body.innerHTML = `
    <form class="form" novalidate>
      <div class="form__row"><label class="lbl" for="board-name">${escapeHTML(B.nameLabel)} <span class="req">*</span></label><input class="inp" id="board-name" maxlength="80" placeholder="${escapeHTML(B.namePlaceholder)}" /></div>
      <div class="form__row"><label class="lbl" for="board-key">${escapeHTML(B.keyLabel)} <span class="req">*</span></label><input class="inp inp--mono" id="board-key" maxlength="6" autocomplete="off" spellcheck="false" placeholder="OPS" /><p class="hint">${escapeHTML(B.keyHint)}</p></div>
      <div class="form__row"><label class="lbl" for="board-description">${escapeHTML(B.descriptionLabel)} <span class="opt">${escapeHTML(B.optional)}</span></label><textarea class="txt" id="board-description" rows="3" maxlength="2000"></textarea></div>
    </form>`;
  const name = $('#board-name', body);
  const key = $('#board-key', body);
  // The key follows the name until someone types one.
  let typed = false;
  name.addEventListener('input', () => { if (!typed) key.value = boardKeyFrom(name.value); });
  key.addEventListener('input', () => { typed = true; key.value = key.value.toUpperCase().replace(/[^A-Z0-9]/g, ''); });
  openDrawer({
    title: B.newBoard,
    eyebrow: B.title,
    body,
    saveLabel: B.createBoard,
    onSave: async () => {
      const values = { name: name.value.trim(), key: key.value.trim() };
      if (!values.name) { name.setAttribute('aria-invalid', 'true'); name.focus(); toast(B.errors.required); return false; }
      if (!/^[A-Z][A-Z0-9]{1,5}$/.test(values.key)) { key.setAttribute('aria-invalid', 'true'); key.focus(); toast(B.errors.invalid_key); return false; }
      try {
        const board = await api('/admin/api/board/boards', {
          method: 'POST',
          body: JSON.stringify({
            ...values,
            description: $('#board-description', body).value.trim() || null,
            columns: [{ name: B.defaultColumns.todo }, { name: B.defaultColumns.doing }, { name: B.defaultColumns.done, done: true }],
          }),
        });
        boardState.boards.push(board);
        boardState.boardId = board.id;
        boardState.cards = [];
        localStorage.setItem(BOARD_STORE_KEY, board.id);
        toast(B.boardCreated({ name: board.name }));
      } catch (e) {
        if (e.code === 'key_taken') { key.setAttribute('aria-invalid', 'true'); key.focus(); }
        toast(boardErrorText(e));
        return false;
      }
      if (currentView === 'board') boardPaint();
      else setView('board');
      return true;
    },
  });
}

function boardColumnRowHtml(column) {
  const B = T.board;
  return `<div class="line" data-column-row="${escapeHTML(column.id || '')}">
      <input type="text" maxlength="40" value="${escapeHTML(column.name)}" aria-label="${escapeHTML(B.columnName)}" data-column-name />
      <label class="line__check" title="${escapeHTML(B.columnDoneHint)}"><input type="checkbox" data-column-done${column.done ? ' checked' : ''} aria-label="${escapeHTML(B.columnDoneHint)}" /></label>
      <span class="line__moves"><button class="l-move" type="button" data-column-up aria-label="${escapeHTML(B.moveUp)}">↑</button><button class="l-move" type="button" data-column-down aria-label="${escapeHTML(B.moveDown)}">↓</button></span>
      <button class="l-rm" type="button" data-column-rm aria-label="${escapeHTML(B.removeColumn)}">×</button>
    </div>`;
}

// A board's name, description and columns, saved together; and deleting it.
function boardSettingsDrawer(board) {
  const B = T.board;
  const body = document.createElement('div');
  body.innerHTML = `
    <form class="form" novalidate>
      <div class="form__row"><label class="lbl" for="board-edit-name">${escapeHTML(B.nameLabel)} <span class="req">*</span></label><input class="inp" id="board-edit-name" maxlength="80" value="${escapeHTML(board.name)}" /><p class="hint">${escapeHTML(B.keyFixed({ key: board.key }))}</p></div>
      <div class="form__row"><label class="lbl" for="board-edit-description">${escapeHTML(B.descriptionLabel)} <span class="opt">${escapeHTML(B.optional)}</span></label><textarea class="txt" id="board-edit-description" rows="3" maxlength="2000">${escapeHTML(board.description || '')}</textarea></div>
      <div class="form__row lines-field">
        <span class="lbl" id="board-columns-label">${escapeHTML(B.columnsLabel)}</span>
        <div class="lines lines--columns" role="group" aria-labelledby="board-columns-label">
          <div class="lines__head"><span>${escapeHTML(B.columnName)}</span><span>${escapeHTML(B.columnDone)}</span><span></span><span></span></div>
          <div data-column-rows>${board.columns.map(boardColumnRowHtml).join('')}</div>
          <div class="lines__foot"><button class="btn btn--sm" type="button" data-column-add><span class="btn__plus" aria-hidden="true">+</span> ${escapeHTML(B.addColumn)}</button></div>
        </div>
        <p class="hint">${escapeHTML(B.columnsHint)}</p>
      </div>
    </form>
    <div class="actions drawer__actions"><button class="btn btn--sm btn--danger" type="button" data-board-delete>${escapeHTML(B.deleteBoard)}</button></div>`;
  const rows = $('[data-column-rows]', body);
  $('[data-column-add]', body).addEventListener('click', () => {
    rows.insertAdjacentHTML('beforeend', boardColumnRowHtml({ id: '', name: '', done: false }));
    $$('[data-column-name]', rows).pop().focus();
  });
  rows.addEventListener('click', e => {
    const row = e.target.closest('[data-column-row]');
    if (!row) return;
    if (e.target.closest('[data-column-up]') && row.previousElementSibling) row.previousElementSibling.before(row);
    else if (e.target.closest('[data-column-down]') && row.nextElementSibling) row.nextElementSibling.after(row);
    else if (e.target.closest('[data-column-rm]')) row.remove();
  });
  $('[data-board-delete]', body).addEventListener('click', async () => {
    if (!await confirmDialog({ title: B.deleteBoardTitle, body: B.deleteBoardBody({ name: board.name, n: board.cardCount ?? 0 }), okLabel: B.deleteBoard })) return;
    try {
      await api(`/admin/api/board/boards/${encodeURIComponent(board.id)}`, { method: 'DELETE' });
    } catch (e) {
      toast(boardErrorText(e));
      return;
    }
    toast(B.boardDeleted({ name: board.name }));
    boardState.boards = boardState.boards.filter(b => b.id !== board.id);
    boardState.boardId = boardState.boards[0]?.id || '';
    closeDrawer();
    if (currentView === 'board') renderBoard();
  });
  openDrawer({
    title: board.name,
    eyebrow: B.settings,
    body,
    saveLabel: B.saveBoard,
    onSave: async () => {
      const name = $('#board-edit-name', body).value.trim();
      if (!name) { $('#board-edit-name', body).setAttribute('aria-invalid', 'true'); toast(B.errors.required); return false; }
      const description = $('#board-edit-description', body).value.trim();
      const columns = $$('[data-column-row]', rows).map(row => ({
        id: row.dataset.columnRow || null,
        name: $('[data-column-name]', row).value.trim(),
        done: $('[data-column-done]', row).checked,
      }));
      if (!columns.length) { toast(B.errors.columns_required); return false; }
      const blank = $$('[data-column-row]', rows).find(row => !$('[data-column-name]', row).value.trim());
      if (blank) { $('[data-column-name]', blank).focus(); toast(B.errors.required); return false; }
      const sameColumns = JSON.stringify(columns) === JSON.stringify(board.columns.map(c => ({ id: c.id, name: c.name, done: c.done })));
      try {
        let saved = board;
        if (name !== board.name || description !== (board.description || '')) {
          saved = await api(`/admin/api/board/boards/${encodeURIComponent(board.id)}`, { method: 'PATCH', body: JSON.stringify({ name, description: description || null }) });
        }
        if (!sameColumns) saved = await api(`/admin/api/board/boards/${encodeURIComponent(board.id)}/columns`, { method: 'PUT', body: JSON.stringify({ columns }) });
        boardState.boards = boardState.boards.map(b => (b.id === saved.id ? saved : b));
      } catch (e) {
        toast(boardErrorText(e, board));
        return false;
      }
      toast(B.boardSaved({ name }));
      if (currentView === 'board') renderBoard();
      return true;
    },
  });
}

async function boardArchivedDrawer(board) {
  const B = T.board;
  let cards;
  try {
    cards = await api(`/admin/api/board/boards/${encodeURIComponent(board.id)}/cards?archived=true`);
  } catch (e) {
    toast(boardErrorText(e));
    return;
  }
  const body = document.createElement('div');
  const columnName = card => boardColumnOf(board, card)?.name || '';
  body.innerHTML = cards.length
    ? `<div class="tbl-wrap"><table class="tbl tbl--stack tbl--flow"><thead><tr><th>${escapeHTML(B.thCard)}</th><th>${escapeHTML(B.columnLabel)}</th><th class="right">${escapeHTML(T.thActions)}</th></tr></thead><tbody>
        ${cards.map(c => `<tr>
          <td class="name"><button class="tbl__open" type="button" data-archived-open="${escapeHTML(c.id)}">${escapeHTML(c.title)}</button><div class="sub mono">${escapeHTML(cardKey(board, c))}</div></td>
          <td ${dataLabel(B.columnLabel)}>${escapeHTML(columnName(c))}</td>
          <td class="right"><div class="actions"><button class="btn btn--sm" type="button" data-archived-restore="${escapeHTML(c.id)}">${escapeHTML(B.restore)}</button></div></td>
        </tr>`).join('')}
      </tbody></table></div>`
    : `<div class="empty"><p class="empty__title">${escapeHTML(B.archivedEmpty)}</p><p class="empty__desc">${escapeHTML(B.archivedEmptyDesc)}</p></div>`;
  const reopen = () => boardArchivedDrawer(board);
  $$('[data-archived-open]', body).forEach(b => b.addEventListener('click', () => openCardDrawer(b.dataset.archivedOpen, { label: B.archivedTitle, open: reopen })));
  $$('[data-archived-restore]', body).forEach(b => b.addEventListener('click', async () => {
    const card = cards.find(c => c.id === b.dataset.archivedRestore);
    try {
      await api(`/admin/api/board/cards/${encodeURIComponent(card.id)}`, { method: 'PATCH', body: JSON.stringify({ archived: false }) });
    } catch (e) {
      toast(boardErrorText(e));
      return;
    }
    toast(B.restored({ key: cardKey(board, card) }));
    boardReloadIfShown();
    reopen();
  }));
  openDrawer({ title: B.archivedTitle, eyebrow: board.name, body, autofocus: false });
}

// The tenant record's Board panel: the cards linked to the tenant that aren't archived, on any board.
async function mountTenantCards(tenant, wrap, back) {
  const host = $('[data-tenant-cards]', wrap);
  if (!host) return;
  let cards;
  try {
    cards = await api(`/admin/api/board/cards?tenantId=${encodeURIComponent(tenant.id)}`);
  } catch {
    return;
  }
  if (!host.isConnected) return;
  const B = T.board;
  const tone = x => {
    if (x.done) return 'ok';
    return boardDue(x.card, { done: false })?.late ? 'bad' : 'info';
  };
  const side = x => {
    const due = boardDue(x.card, { done: x.done });
    return [due ? escapeHTML(due.text) : '', x.card.assignee ? escapeHTML(personInitials(x.card.assignee)) : ''].filter(Boolean).join(' · ');
  };
  host.hidden = false;
  host.innerHTML = `
    <header class="panel__head"><h3 class="panel__title">${escapeHTML(B.tenantCardsTitle)} <span class="tag">${cards.length}</span></h3>
      <div class="panel__tools"><button class="btn btn--sm btn--ghost" type="button" data-tenant-card-new><span class="btn__plus" aria-hidden="true">+</span> ${escapeHTML(B.newCard)}</button></div></header>
    ${cards.length
      ? `<ul class="worklist">${cards.map(x => `<li><button class="worklist__item" type="button" data-tenant-card="${escapeHTML(x.card.id)}" data-tone="${tone(x)}">
          <span class="worklist__dot" aria-hidden="true"></span>
          <span class="worklist__main"><span class="worklist__title">${escapeHTML(`${x.boardKey}-${x.card.number} · ${x.card.title}`)}</span><span class="worklist__detail">${escapeHTML(`${x.boardName} · ${x.columnName}`)}</span></span>
          <span class="worklist__side"><span class="worklist__meta">${side(x)}</span></span>
        </button></li>`).join('')}</ul>`
      : `<div class="panel__body"><p class="dash-empty">${escapeHTML(B.tenantCardsEmpty)}</p></div>`}`;
  $$('[data-tenant-card]', host).forEach(b => b.addEventListener('click', () => openCardDrawer(b.dataset.tenantCard, back)));
  $('[data-tenant-card-new]', host).addEventListener('click', () => newCardDrawer({ tenantId: tenant.id, back }));
}
