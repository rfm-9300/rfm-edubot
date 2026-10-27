/* What the Clients, Suppliers and Employees lists share: the Active / Archived switch and the column
   picker. Each page's own columns are defined beside it (clientColumns in clients.js…), and their order is
   in columns.js. */

// The directory pages list active records; their Archived tab swaps in the archived ones, ready to restore.
async function showDirectoryView(kind) {
  if (kind) {
    const rows = await api(`/app/api/crm/${kind}?archived=1`).catch(() => null);
    if (!rows) return toast(STR.loadFailed);
    state.archivedRows = rows;
  }
  state.archivedView = kind;
  render();
}

function wireDirectoryView(root) {
  $$('[data-directory-view]', root).forEach(b => b.addEventListener('click', () => showDirectoryView(b.dataset.directoryView)));
}

// Each person picks the columns of a directory list. The choice stays in this browser, per company and
// user, as on/off overrides of each column's default, so a column added later still follows its default.
const columnChoicesKey = table => tableStoreKey('tableColumns', table);

function columnChoices(table) {
  const key = columnChoicesKey(table);
  if (!state.columnChoices[key]) {
    let saved = null;
    try { saved = JSON.parse(localStorage.getItem(key) || 'null'); } catch { /* unreadable: defaults */ }
    state.columnChoices[key] = saved && typeof saved === 'object' && !Array.isArray(saved) ? saved : {};
  }
  return state.columnChoices[key];
}

function saveColumnChoices(table, choices) {
  const key = columnChoicesKey(table);
  state.columnChoices[key] = choices;
  try {
    if (Object.keys(choices).length) localStorage.setItem(key, JSON.stringify(choices));
    else localStorage.removeItem(key);
  } catch { /* ignore */ }
}

const isColumnShown = (choices, column) => !!column.fixed || (choices[column.id] ?? !!column.default);
const shownColumns = (table, columns) => orderedColumns(table, columns).filter(c => isColumnShown(columnChoices(table), c));
const columnsButton = table => `<button class="btn btn--sm btn--ghost" type="button" data-columns="${table}">${escapeHTML(COLUMNS.button)}</button>`;

function openColumnPicker(table, columns, eyebrow) {
  const body = document.createElement('div');
  body.className = 'form';
  const box = c => `<label class="form__check"><input type="checkbox" data-column="${escapeHTML(c.id)}"${isColumnShown(columnChoices(table), c) ? ' checked' : ''}${c.fixed ? ' disabled' : ''} /> ${escapeHTML(c.label)}</label>`;
  const group = (id, title, list) => (list.length ? `<section class="panel">
      <header class="panel__head"><h2 class="panel__title" id="${id}">${escapeHTML(title)}</h2></header>
      <div class="panel__body form"><div class="form__checks" role="group" aria-labelledby="${id}">${list.map(box).join('')}</div></div>
    </section>` : '');
  body.innerHTML = `
    <p class="hint">${escapeHTML(COLUMNS.intro)}</p>
    ${group('columns-standard', COLUMNS.standard, columns.filter(c => !c.custom && !c.pin))}
    ${group('columns-custom', FIELDS.customTitle, columns.filter(c => c.custom))}
    <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-columns-reset>${escapeHTML(COLUMNS.reset)}</button></div>`;
  openDrawer(COLUMNS.title, body, false, { eyebrow });
  // The name is always shown, so its box can't take the focus.
  requestAnimationFrame(() => $('[data-column]:not(:disabled)', body)?.focus());
  const column = input => columns.find(c => c.id === input.dataset.column);
  $$('[data-column]', body).forEach(input => input.addEventListener('change', () => {
    const choices = Object.fromEntries(Object.entries(columnChoices(table)).filter(([id]) => columns.some(c => c.id === id)));
    if (input.checked === !!column(input).default) delete choices[input.dataset.column];
    else choices[input.dataset.column] = input.checked;
    saveColumnChoices(table, choices);
    // Without the column someone moved, the rest may be in the list's own order again.
    if (inOwnOrder(columns, shownColumns(table, columns).filter(c => !c.pin).map(c => c.id))) saveColumnOrder(table, []);
    render();
  }));
  $('[data-columns-reset]', body).addEventListener('click', () => {
    saveColumnChoices(table, {});
    saveColumnOrder(table, []);
    $$('[data-column]', body).forEach(input => { input.checked = isColumnShown({}, column(input)); });
    render();
  });
}
