/* Catalog (/app → Catalog): the company's standard services and materials, which the line-items editor
   offers. The list deletes them; a form adds or edits one. With Clients on, a second view manages the
   items each client usually buys (the same list as the client record's Items tab). */

const CATEGORIES_PATH = '/app/api/crm/catalog-categories';
const categoryName = text => String(text || '').trim().replace(/\s+/g, ' ');

async function loadCatalogCategories() {
  state.catalogCategories = await api(CATEGORIES_PATH).catch(() => state.catalogCategories || []);
  return state.catalogCategories;
}

// The category's name as the company already spells it, creating it when it's new: typed "limpeza" under an
// existing "Limpeza" is that category, so an item never ends up with a second spelling.
async function ensureCatalogCategory(typed) {
  const name = categoryName(typed);
  try {
    return (await api(CATEGORIES_PATH, { method: 'POST', body: JSON.stringify({ name }) })).name;
  } catch (err) {
    if (err?.code !== 'category_exists') throw err;
    const known = (await loadCatalogCategories()).find(c => foldText(c.name) === foldText(name));
    return known?.name || name;
  }
}

// The category field: the company's categories, and "New category…" for one that isn't there yet (it shows a
// text field and is created with the item). A category the item has but the list doesn't is kept as an option.
function catalogCategoryField(current) {
  const t = CRM.items;
  const names = (state.catalogCategories || []).map(c => c.name);
  if (current && !names.some(n => foldText(n) === foldText(current))) names.push(current);
  const options = names.map(n => `<option value="${escapeHTML(n)}"${foldText(n) === foldText(current) ? ' selected' : ''}>${escapeHTML(n)}</option>`).join('');
  const onlyNew = !names.length;
  return `<div class="form__row form__row--full"><label class="lbl" for="cat-cat">${escapeHTML(t.categoryLabel)} <span class="req">●</span></label>
    <select class="sel" id="cat-cat" required><option value="">${escapeHTML(t.categoryPick)}</option>${options}<option value="__new__" data-new${onlyNew ? ' selected' : ''}>${escapeHTML(t.categoryNew)}</option></select>
    <input class="inp" id="cat-cat-new" maxlength="60" autocomplete="off" placeholder="${escapeHTML(t.categoryNewPh)}" aria-label="${escapeHTML(t.categoryNewPh)}" ${onlyNew ? 'required' : 'hidden'} /></div>`;
}

// New items can be tied to a client right away (the client's items, see the Clients' items view).
function catalogClientField() {
  const t = CRM.items;
  const clients = [...(state.clients || [])].sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), uiLocale(), { sensitivity: 'base' }));
  return `<div class="form__row form__row--full"><label class="lbl" for="cat-client">${escapeHTML(t.linkClientLabel)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
    <select class="sel" id="cat-client"><option value="">${escapeHTML(t.linkClientNone)}</option>${clients.map(c => `<option value="${escapeHTML(c.id)}">${escapeHTML(c.name)}</option>`).join('')}</select>
    <p class="hint">${escapeHTML(t.linkClientHint)}</p></div>`;
}

async function openCatalogForm(itemId) {
  const editing = itemId ? state.catalog.find(c => c.id === itemId) : null;
  const t = CRM.items;
  const linkClient = !editing && catalogClientsOn();
  if (!state.catalogCategories) await loadCatalogCategories();
  if (linkClient && !state.clients.length) state.clients = await api('/app/api/crm/clients').catch(() => state.clients);
  const form = document.createElement('form');
  form.className = 'form';
  const typeValue = editing?.type === 'material' ? 'material' : 'service';
  const codePrefix = type => type === 'material' ? 'MAT' : 'SRV';
  const codePlaceholder = type => t.phCode({ prefix: codePrefix(type) });
  form.innerHTML = `
    <div class="form__grid">
      <div class="form__row form__row--full"><label class="lbl" for="cat-title">${escapeHTML(t.titleLabel)} <span class="req">●</span></label>
        <input class="inp" id="cat-title" required placeholder="${escapeHTML(t.phTitle)}" value="${escapeHTML(editing?.title || '')}" /></div>
      <div class="form__row"><label class="lbl" for="cat-code">${escapeHTML(t.codeLabel)}${editing ? ' <span class="req">●</span>' : ''}</label>
        <input class="inp inp--mono" id="cat-code" maxlength="40" autocomplete="off" spellcheck="false" ${editing ? 'required' : ''} placeholder="${escapeHTML(codePlaceholder(typeValue))}" value="${escapeHTML(editing?.code || '')}" aria-describedby="cat-code-hint" />
        <p class="hint" id="cat-code-hint">${escapeHTML(t.codeHint({ prefix: codePrefix(typeValue) }))}</p></div>
      <div class="form__row"><label class="lbl" for="cat-type">${escapeHTML(t.typeLabel)} <span class="req">●</span></label>
        <select class="sel" id="cat-type" required>
          <option value="service" ${typeValue === 'service' ? 'selected' : ''}>${escapeHTML(t.service)}</option>
          <option value="material" ${typeValue === 'material' ? 'selected' : ''}>${escapeHTML(t.material)}</option>
        </select></div>
      ${catalogCategoryField(editing?.category || '')}
      <div class="form__row form__row--full"><label class="lbl" for="cat-desc">${escapeHTML(t.descLabel)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <textarea class="txt" id="cat-desc" placeholder="${escapeHTML(t.phDesc)}">${escapeHTML(editing ? catalogDetails(editing) : '')}</textarea></div>
      <div class="form__row"><label class="lbl" for="cat-unit">${escapeHTML(t.unitLabel)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="cat-unit" required placeholder="${escapeHTML(t.phUnit)}" value="${escapeHTML(editing?.unit || '')}" /></div>
      <div class="form__row"><label class="lbl" for="cat-price">${escapeHTML(t.priceLabel)} <span class="req">●</span></label>
        <input class="inp inp--mono inp--right" id="cat-price" type="number" min="0" step="0.01" required placeholder="0.00" value="${editing?.defaultUnitPriceEur ?? ''}" /></div>
      ${hasModule('bookings') ? `<div class="form__row form__row--full" data-booking-fields ${typeValue === 'service' ? '' : 'hidden'}>
        <label class="form__check"><input type="checkbox" id="cat-bookable" ${editing?.bookable ? 'checked' : ''} /> ${escapeHTML(t.bookableLabel)}</label>
        <p class="hint">${escapeHTML(t.bookableHint)}</p></div>
      <div class="form__row" data-booking-fields ${typeValue === 'service' ? '' : 'hidden'}><label class="lbl" for="cat-duration">${escapeHTML(t.durationLabel)}</label>
        <input class="inp inp--mono" id="cat-duration" type="number" min="5" step="5" value="${editing?.durationMinutes ?? 30}" /></div>` : ''}
      ${linkClient ? catalogClientField() : ''}
    </div>
    ${editing ? `<p class="hint">${escapeHTML(t.editingHint)}</p>` : ''}
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(editing ? t.saveChanges : t.createItem)}</button>
      <button class="btn btn--ghost" type="button" data-cat-cancel>${escapeHTML(STR.cancel)}</button>
    </div>`;
  $('[data-cat-cancel]', form).addEventListener('click', () => closeDrawer());
  const titleEl = $('#cat-title', form), codeEl = $('#cat-code', form), descEl = $('#cat-desc', form), typeEl = $('#cat-type', form);
  const catEl = $('#cat-cat', form), newCatEl = $('#cat-cat-new', form);
  const isNewCategory = () => catEl.selectedOptions[0]?.hasAttribute('data-new');
  catEl.addEventListener('change', () => {
    newCatEl.hidden = !isNewCategory();
    newCatEl.required = isNewCategory();
    if (isNewCategory()) newCatEl.focus();
  });
  typeEl.addEventListener('change', () => {
    $$('[data-booking-fields]', form).forEach(row => { row.hidden = typeEl.value !== 'service'; });
    codeEl.placeholder = codePlaceholder(typeEl.value);
    $('#cat-code-hint', form).textContent = t.codeHint({ prefix: codePrefix(typeEl.value) });
  });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const title = titleEl.value.trim(), code = codeEl.value.trim().toUpperCase(), description = descEl.value.trim(), unit = $('#cat-unit', form).value.trim();
    const newCategory = isNewCategory();
    let category = newCategory ? categoryName(newCatEl.value) : catEl.value;
    const type = typeEl.value, defaultUnitPriceEur = Number($('#cat-price', form).value || 0);
    if (!title || !category || !unit || (editing && !code)) return toast(t.fillRequired);
    if (code && !/^[A-Z]{3}-[0-9]+$/.test(code)) return toast(t.codeInvalid);
    const booking = $('#cat-bookable', form)
      ? { bookable: type === 'service' && $('#cat-bookable', form).checked, durationMinutes: Math.max(5, Number($('#cat-duration', form).value || 30)) }
      : {};
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      if (newCategory) category = await ensureCatalogCategory(category);
      const path = editing ? `/app/api/crm/standard-items/${encodeURIComponent(editing.id)}` : '/app/api/crm/standard-items';
      const saved = await api(path, { method: 'POST', body: JSON.stringify({ code: code || undefined, type, title, category, description, unit, defaultUnitPriceEur, ...booking }) });
      const ref = saved?.code || title;
      // The item is saved; tying it to the chosen client is a second call that may fail on its own.
      const client = linkClient ? (state.clients || []).find(c => c.id === $('#cat-client', form)?.value) : null;
      let linked = !client;
      if (client) {
        try {
          await api(`/app/api/crm/clients/${encodeURIComponent(client.id)}/catalog-items`, { method: 'PUT', body: JSON.stringify({ itemIds: [...(client.catalogItemIds || []), saved.id] }) });
          linked = true;
        } catch { /* reported below */ }
      }
      // Reload first: closing goes back to the item's record when it was opened from there, and that reads the catalog.
      await Promise.all([loadModule('catalog'), loadCatalogCategories()]);
      render();
      closeDrawer();
      toast(editing ? t.updated({ code: ref }) : !client ? t.created({ code: ref }) : linked ? t.createdLinked({ code: ref, client: client.name }) : t.linkFailed({ code: ref, client: client.name }));
    } catch (err) { btn.disabled = false; toast(err?.code === 'code_taken' ? t.codeTaken : err?.code === 'code_invalid' ? t.codeInvalid : err?.code === 'name_too_long' ? t.catTooLong : STR.catalogCreateFailed); }
  });
  openDrawer(editing ? t.editTitleFull({ code: editing.code || editing.title }) : t.newTitle, form, false, { eyebrow: t.eyebrow });
}

const catalogTrailEntry = item => ({ key: `catalog:${item.id}`, label: item.title, open: () => openCatalogDetail(item.id) });

// The item's record, as the other lists open theirs: its kind as the eyebrow, one list of fields, and
// the actions in the drawer's foot with Edit last.
function openCatalogDetail(id) {
  const item = state.catalog.find(c => c.id === id);
  if (!item) return toast(STR.loadFailed);
  const t = CRM.items;
  const service = item.type === 'service' || item.type === 'servico';
  const bookable = service && item.bookable && item.durationMinutes && hasModule('bookings');
  const details = catalogDetails(item);
  const clients = catalogClientsOn() ? (state.clients || []).filter(c => (c.catalogItemIds || []).includes(item.id)) : [];
  const clientNames = clients.slice(0, 4).map(c => c.name).join(', ') + (clients.length > 4 ? ` ${t.andMore({ n: clients.length - 4 })}` : '');
  const here = catalogTrailEntry(item);
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `
    ${recordFieldsHtml([
      [t.thCode, `<span class="mono">${escapeHTML(item.code || '—')}</span>`],
      [t.thType, `<span class="tbl__pills">${catalogTypePill(item.type)}${bookable ? `<span class="pill pill--ok">${escapeHTML(t.bookablePill({ min: item.durationMinutes }))}</span>` : ''}</span>`],
      [t.thCategory, escapeHTML(item.category || '—')],
      [t.thPrice, recordFigureHtml(fmtEUR(item.defaultUnitPriceEur), item.unit || '')],
      details ? [t.descLabel, escapeHTML(details)] : null,
      catalogClientsOn() ? [t.thClients, clients.length ? recordFigureHtml(String(clients.length), clientNames) : escapeHTML('0')] : null,
    ])}
    ${docFootHtml(`
      <button class="btn btn--sm btn--ghost" type="button" data-cat-delete>${escapeHTML(t.deleteTitle)}</button>
      <button class="btn btn--sm btn--primary" type="button" data-cat-edit>${escapeHTML(STR.clientEditAction)}</button>`)}`;
  $('[data-cat-edit]', body).addEventListener('click', () => openFrom(here, () => openCatalogForm(item.id)));
  $('[data-cat-delete]', body).addEventListener('click', () => deleteCatalogItem(item.id));
  openDrawer(item.title, body, false, { eyebrow: service ? t.service : t.material, tags: { kind: 'catalog', id: item.id } });
  // The drawer focuses its first button, which here is Delete; Edit is the safer start (after the drawer's own frame).
  requestAnimationFrame(() => $('[data-cat-edit]', body)?.focus());
}

async function deleteCatalogItem(id) {
  const item = state.catalog.find(c => c.id === id);
  if (!item) return;
  const t = CRM.items;
  const ok = await confirmDialog({ title: t.confirmTitle, body: t.confirmBody({ desc: item.title }), okLabel: t.confirmOk });
  if (!ok) return;
  try {
    await api(`/app/api/crm/standard-items/${encodeURIComponent(id)}`, { method: 'DELETE' });
    if (!$('#drawer').hidden) closeDrawer({ dismissed: true });
    await loadModule('catalog');
    render();
    toast(t.deleted({ code: item.code || item.title }));
  } catch { toast(STR.catalogCreateFailed); }
}

function catalogTypePill(type) {
  const service = type === 'service' || type === 'servico';
  return service
    ? `<span class="pill pill--accent">${escapeHTML(CRM.items.pillService)}</span>`
    : `<span class="pill pill--info">${escapeHTML(CRM.items.pillMaterial)}</span>`;
}

// An item without its own description stores its title there, so only a different text is extra detail.
function catalogDetails(item) {
  return item.description && item.description !== item.title ? item.description : '';
}

// The classic PDF prints the part after " - " under the title.
function catalogLineText(item) {
  return [item.title, catalogDetails(item)].filter(Boolean).join(' - ');
}

function catalogItemLabel(item) {
  return [item.code, item.title].filter(Boolean).join(' · ');
}

function catalogColumns() {
  const t = CRM.items;
  const title = i => {
    const details = catalogDetails(i);
    return `<td><div class="col"><button class="tbl__open name" type="button">${escapeHTML(i.title)}</button>${details ? `<span class="sub">${escapeHTML(details)}</span>` : ''}</div></td>`;
  };
  return [
    { id: 'code', label: t.thCode, cell: i => `<td class="id">${escapeHTML(i.code || '')}</td>` },
    { id: 'title', label: t.thTitle, cell: title },
    { id: 'type', label: t.thType, cell: i => `<td>${catalogTypePill(i.type)}${i.bookable && i.durationMinutes && hasModule('bookings') ? ` <span class="pill pill--ok">${escapeHTML(t.bookablePill({ min: i.durationMinutes }))}</span>` : ''}</td>` },
    { id: 'category', label: t.thCategory, cell: i => `<td class="muted">${escapeHTML(i.category)}</td>` },
    ...tagsColumnIfUsed('catalog'),
    { id: 'unit', label: t.thUnit, cell: i => `<td class="mono muted">${escapeHTML(i.unit)}</td>` },
    { id: 'price', label: t.thPrice, head: 'right', cell: i => `<td class="num">${fmtEUR(i.defaultUnitPriceEur)}</td>` },
    ...(catalogClientsOn() ? [{ id: 'clients', label: t.thClients, head: 'right', cell: i => `<td class="num muted">${catalogItemClientCount(i.id) || ''}</td>` }] : []),
    {
      id: 'actions', label: t.thActions, head: 'right', pin: 'end', cell: i => `<td class="right"><div class="actions">
        <button class="iconbtn" type="button" title="${escapeHTML(t.editTitle)}" data-edit-item="${escapeHTML(i.id)}"><svg width="13" height="13" viewBox="0 0 16 16"><path d="M11 2 L14 5 L5 14 L2 14 L2 11 Z" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/></svg></button>
        <button class="iconbtn iconbtn--danger" type="button" title="${escapeHTML(t.deleteTitle)}" data-delete-item="${escapeHTML(i.id)}"><svg width="13" height="13" viewBox="0 0 16 16"><path d="M3 5 L13 5 M6 5 L6 3 L10 3 L10 5 M5 5 L6 13 L10 13 L11 5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
      </div></td>`,
    },
  ];
}

// The clients' items view needs the Clients module; the catalog page loads the clients for it.
const catalogClientsOn = () => hasModule('clients');

function catalogItemClientCount(itemId) {
  return (state.clients || []).filter(c => (c.catalogItemIds || []).includes(itemId)).length;
}

function catalogViewChips(view) {
  const t = CRM.items;
  return [['', t.viewItems], ['clients', t.viewClients]]
    .map(([v, label]) => `<button class="btn btn--sm ${view === v ? 'btn--primary' : ''}" type="button" data-cat-view="${v}">${escapeHTML(label)}</button>`).join('');
}

function wireCatalogViews(root) {
  $$('[data-cat-view]', root).forEach(btn => btn.addEventListener('click', () => { state.catalogView = btn.dataset.catView; render(); }));
}

function renderCatalog(root) {
  const t = CRM.items;
  if (state.catalogView === 'clients') {
    if (catalogClientsOn()) return renderCatalogClients(root);
    state.catalogView = '';
  }
  const q = state.search.toLowerCase();
  const columns = orderedColumns('catalog', catalogColumns());
  const listed = state.catalog.filter(i => matchesTagFilter('catalog', i.id));
  const rows = listed
    .filter(i => !q || `${i.code || ''} ${i.title || ''} ${i.description || ''} ${i.category || ''} ${tagsText('catalog', i.id)}`.toLowerCase().includes(q))
    .map(i => `<tr class="conversation-row" data-cat-item="${escapeHTML(i.id)}">${columnCells(columns, i)}</tr>`)
    .join('');
  const nSrv = state.catalog.filter(i => i.type === 'service' || i.type === 'servico').length;
  const nMat = state.catalog.length - nSrv;
  root.innerHTML = hero(labels.catalog, CRM.tabs.items.desc, statCards([
    { label: t.services, value: nSrv },
    { label: t.materials, value: nMat },
  ])) + crmPanel({
    title: t.catalogTitle,
    tag: t.tag({ n: listed.length }),
    views: catalogClientsOn() ? catalogViewChips('') : '',
    tools: `<span class="panel__tools-end">${tagFilterHtml('catalog')}${columnOrderButton('catalog')}<button type="button" class="btn btn--sm btn--ghost" data-cat-categories>${escapeHTML(t.manageCategories)}</button></span>`,
    head: columnsHead(columns, 'catalog'),
    rows,
    empty: t.emptyTitle,
    emptyDesc: t.emptyDesc,
  });
  $$('[data-cat-item]', root).forEach(row => row.addEventListener('click', e => {
    if (e.target.closest('[data-edit-item], [data-delete-item]')) return;
    openCatalogDetail(row.dataset.catItem);
  }));
  $$('[data-edit-item]', root).forEach(btn => btn.addEventListener('click', () => openCatalogForm(btn.dataset.editItem)));
  $$('[data-delete-item]', root).forEach(btn => btn.addEventListener('click', () => deleteCatalogItem(btn.dataset.deleteItem)));
  wireColumnOrder(root, 'catalog', catalogColumns());
  wireTagFilter(root, 'catalog');
  wireCatalogViews(root);
  $('[data-cat-categories]', root)?.addEventListener('click', openCategoryManager);
}

// The categories items are sorted into: add one, rename one (its items follow), delete one no item uses.
// Each change reloads the list, and a rename the catalog behind the drawer too.
async function openCategoryManager() {
  const t = CRM.items;
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `<p class="hint">${escapeHTML(t.catDesc)}</p>`;
  const list = document.createElement('div');
  list.className = 'form';
  body.appendChild(list);
  let renaming = null;
  const errorText = err => ({ category_exists: t.catExists, name_required: t.catNameRequired, name_too_long: t.catTooLong, category_in_use: t.catInUseError })[err?.code] || t.catFailed;
  const reload = async ({ items = false } = {}) => {
    await loadCatalogCategories();
    if (items) await loadModule('catalog').catch(() => {});
    if (state.active === 'catalog') render();
    draw();
  };
  const write = async (request, done, { items = false } = {}) => {
    try {
      const saved = await request();
      renaming = null;
      await reload({ items });
      toast(done(saved));
    } catch (err) { toast(errorText(err)); }
  };
  const pencil = '<svg width="13" height="13" viewBox="0 0 16 16" aria-hidden="true"><path d="M11 2 L14 5 L5 14 L2 14 L2 11 Z" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/></svg>';
  const bin = '<svg width="13" height="13" viewBox="0 0 16 16" aria-hidden="true"><path d="M3 5 L13 5 M6 5 L6 3 L10 3 L10 5 M5 5 L6 13 L10 13 L11 5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  const draw = () => {
    const categories = state.catalogCategories || [];
    const rows = categories.map((c, i) => {
      const name = renaming === c.name
        ? `<input class="inp" data-cat-rename-input="${i}" maxlength="60" autocomplete="off" value="${escapeHTML(c.name)}" aria-label="${escapeHTML(t.catRenameAria({ name: c.name }))}" />`
        : `<span class="name">${escapeHTML(c.name)}</span>`;
      const actions = renaming === c.name
        ? `<button class="btn btn--sm btn--primary" type="button" data-cat-rename-save="${i}">${escapeHTML(t.saveChanges)}</button><button class="btn btn--sm btn--ghost" type="button" data-cat-rename-cancel>${escapeHTML(STR.cancel)}</button>`
        : `<button class="iconbtn" type="button" title="${escapeHTML(t.catRename)}" aria-label="${escapeHTML(t.catRenameAria({ name: c.name }))}" data-cat-rename="${i}">${pencil}</button>
           <button class="iconbtn iconbtn--danger" type="button" title="${escapeHTML(c.itemCount ? t.catInUse({ n: c.itemCount }) : t.deleteTitle)}" aria-label="${escapeHTML(t.catDeleteAria({ name: c.name }))}" data-cat-delete="${i}"${c.itemCount ? ' disabled' : ''}>${bin}</button>`;
      return `<tr><td>${name}</td><td class="num">${c.itemCount}</td><td class="right"><div class="actions">${actions}</div></td></tr>`;
    }).join('');
    list.innerHTML = `
      <form class="form__row form__row--full" data-cat-add-form>
        <label class="lbl" for="cat-add">${escapeHTML(t.catAddLabel)}</label>
        <div class="inp-row"><input class="inp" id="cat-add" maxlength="60" autocomplete="off" placeholder="${escapeHTML(t.catAddPh)}" />
        <button class="btn btn--primary" type="submit">${escapeHTML(t.catAdd)}</button></div>
      </form>
      <div class="tbl-wrap"><table class="tbl"><thead><tr><th>${escapeHTML(t.catThName)}</th><th class="right">${escapeHTML(t.catThItems)}</th><th></th></tr></thead>
        <tbody>${rows || `<tr><td colspan="3"><div class="empty"><p class="empty__title">${escapeHTML(t.catEmpty)}</p><p class="empty__desc">${escapeHTML(t.catEmptyDesc)}</p></div></td></tr>`}</tbody></table></div>`;
    $('[data-cat-add-form]', list).addEventListener('submit', e => {
      e.preventDefault();
      const input = $('#cat-add', list);
      if (!categoryName(input.value)) return toast(t.catNameRequired);
      write(() => api(CATEGORIES_PATH, { method: 'POST', body: JSON.stringify({ name: categoryName(input.value) }) }), c => t.catCreated({ name: c.name }));
    });
    $$('[data-cat-rename]', list).forEach(btn => btn.addEventListener('click', () => {
      renaming = categories[Number(btn.dataset.catRename)].name;
      draw();
      const input = $('[data-cat-rename-input]', list);
      input.focus();
      input.select();
    }));
    $('[data-cat-rename-cancel]', list)?.addEventListener('click', () => { renaming = null; draw(); });
    const saveRename = () => {
      const input = $('[data-cat-rename-input]', list);
      const next = categoryName(input.value);
      if (!next) return toast(t.catNameRequired);
      if (next === renaming) { renaming = null; return draw(); }
      write(() => api(`${CATEGORIES_PATH}?name=${encodeURIComponent(renaming)}`, { method: 'PUT', body: JSON.stringify({ name: next }) }), c => t.catRenamed({ name: c.name }), { items: true });
    };
    $('[data-cat-rename-save]', list)?.addEventListener('click', saveRename);
    $('[data-cat-rename-input]', list)?.addEventListener('keydown', e => {
      if (e.key === 'Enter') { e.preventDefault(); saveRename(); }
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); renaming = null; draw(); }
    });
    $$('[data-cat-delete]', list).forEach(btn => btn.addEventListener('click', async () => {
      const category = categories[Number(btn.dataset.catDelete)];
      const ok = await confirmDialog({ title: t.catConfirmTitle, body: t.catConfirmBody({ name: category.name }), okLabel: t.catConfirmOk });
      if (!ok) return;
      write(() => api(`${CATEGORIES_PATH}?name=${encodeURIComponent(category.name)}`, { method: 'DELETE' }), () => t.catDeleted({ name: category.name }));
    }));
  };
  if (!state.catalogCategories) await loadCatalogCategories();
  draw();
  openDrawer(t.catTitle, body, false, { eyebrow: t.eyebrow });
}

// Without a client picked, every active client with how many items they have (most first); a row picks
// that client, whose items then list with remove buttons and an add select.
function renderCatalogClients(root) {
  const t = CRM.items;
  const clients = [...(state.clients || [])].sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), uiLocale(), { sensitivity: 'base' }));
  if (state.catalogClient && !clients.some(c => c.id === state.catalogClient)) state.catalogClient = '';
  const client = clients.find(c => c.id === state.catalogClient) || null;
  const q = foldText(state.search.trim());
  const withItems = clients.filter(c => clientCatalogItems(c).length);
  const links = withItems.reduce((sum, c) => sum + clientCatalogItems(c).length, 0);
  const clientPick = `<select class="sel" data-cat-client aria-label="${escapeHTML(t.thClient)}"><option value="">${escapeHTML(t.allClients)}</option>${clients.map(c => `<option value="${escapeHTML(c.id)}" ${client?.id === c.id ? 'selected' : ''}>${escapeHTML(c.name)}</option>`).join('')}</select>`;
  const stats = statCards([
    { label: t.clientsWithItems, value: withItems.length },
    { label: t.clientsWithout, value: clients.length - withItems.length },
    { label: t.itemsLinked, value: links },
  ]);
  let panel;
  if (client) {
    const items = clientCatalogItems(client);
    panel = crmPanel({
      title: client.name,
      tag: items.length,
      views: catalogViewChips('clients'),
      tools: clientPick + clientItemsAddHtml(items),
      head: `<tr>${clientItemsHead().map(([label, cls]) => `<th${cls ? ` class="${cls}"` : ''}>${escapeHTML(label)}</th>`).join('')}</tr>`,
      rows: clientItemsRowsHtml(items, q),
      empty: items.length ? CRM.services.emptyFiltered : CRM.services.clientItemsEmpty,
      emptyDesc: items.length ? CRM.services.emptyFilteredDesc : CRM.services.clientItemsEmptyDesc,
    });
  } else {
    const rows = clients
      .map(c => ({ c, items: clientCatalogItems(c) }))
      .filter(({ c, items }) => !q || foldText(`${c.name || ''} ${items.map(i => `${i.code || ''} ${i.title || ''}`).join(' ')}`).includes(q))
      .sort((a, b) => b.items.length - a.items.length)
      .map(({ c, items }) => {
        const shown = items.slice(0, 4).map(i => i.title).join(', ');
        const more = items.length > 4 ? ` ${t.andMore({ n: items.length - 4 })}` : '';
        return `<tr class="conversation-row" data-cat-pick="${escapeHTML(c.id)}" tabindex="0">
          <td class="name">${escapeHTML(c.name || '')}</td>
          <td class="muted">${items.length ? escapeHTML(shown + more) : `<span class="muted">${escapeHTML(t.noItemsYet)}</span>`}</td>
          <td class="num">${items.length}</td>
          <td class="right"><button class="btn btn--sm btn--ghost" type="button" data-cat-pick="${escapeHTML(c.id)}" aria-label="${escapeHTML(t.manageAria({ name: c.name || '' }))}">${escapeHTML(t.manage)}</button></td>
        </tr>`;
      }).join('');
    panel = crmPanel({
      title: t.clientsTitle,
      tag: clients.length,
      views: catalogViewChips('clients'),
      tools: clientPick,
      head: `<tr><th>${escapeHTML(t.thClient)}</th><th>${escapeHTML(t.thItems)}</th><th class="right">${escapeHTML(t.thCount)}</th><th></th></tr>`,
      rows,
      empty: clients.length ? CRM.services.emptyFiltered : t.noClients,
      emptyDesc: clients.length ? CRM.services.emptyFilteredDesc : t.noClientsDesc,
    });
  }
  root.innerHTML = hero(labels.catalog, t.clientsDesc, stats) + panel;
  wireCatalogViews(root);
  const pick = id => { state.catalogClient = id; render(); };
  $('[data-cat-client]', root)?.addEventListener('change', e => pick(e.target.value));
  $$('tr[data-cat-pick]', root).forEach(row => {
    row.addEventListener('click', () => pick(row.dataset.catPick));
    row.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(row.dataset.catPick); } });
  });
  if (client) wireClientItems(root, client, clientCatalogItems(client), saved => { state.catalogClient = saved.id; render(); });
}
