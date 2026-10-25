/* Catalog (/app → Catalog): the company's standard services and materials, which the line-items editor
   offers. The list deletes them; a form adds or edits one. With Clients on, a second view manages the
   items each client usually buys (the same list as the client record's Items tab). */

function openCatalogForm(itemId) {
  const editing = itemId ? state.catalog.find(c => c.id === itemId) : null;
  const t = CRM.items;
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
      <div class="form__row form__row--full"><label class="lbl" for="cat-cat">${escapeHTML(t.categoryLabel)} <span class="req">●</span></label>
        <input class="inp" id="cat-cat" required placeholder="${escapeHTML(t.phCategory)}" value="${escapeHTML(editing?.category || '')}" /></div>
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
    </div>
    ${editing ? `<p class="hint">${escapeHTML(t.editingHint)}</p>` : ''}
    <button class="btn btn--primary" type="submit">${escapeHTML(editing ? t.saveChanges : t.createItem)}</button>`;
  const titleEl = $('#cat-title', form), codeEl = $('#cat-code', form), descEl = $('#cat-desc', form), typeEl = $('#cat-type', form);
  typeEl.addEventListener('change', () => {
    $$('[data-booking-fields]', form).forEach(row => { row.hidden = typeEl.value !== 'service'; });
    codeEl.placeholder = codePlaceholder(typeEl.value);
    $('#cat-code-hint', form).textContent = t.codeHint({ prefix: codePrefix(typeEl.value) });
  });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const title = titleEl.value.trim(), code = codeEl.value.trim().toUpperCase(), category = $('#cat-cat', form).value.trim(), description = descEl.value.trim(), unit = $('#cat-unit', form).value.trim();
    const type = typeEl.value, defaultUnitPriceEur = Number($('#cat-price', form).value || 0);
    if (!title || !category || !unit || (editing && !code)) return toast(t.fillRequired);
    if (code && !/^[A-Z]{3}-[0-9]+$/.test(code)) return toast(t.codeInvalid);
    const booking = $('#cat-bookable', form)
      ? { bookable: type === 'service' && $('#cat-bookable', form).checked, durationMinutes: Math.max(5, Number($('#cat-duration', form).value || 30)) }
      : {};
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const path = editing ? `/app/api/crm/standard-items/${encodeURIComponent(editing.id)}` : '/app/api/crm/standard-items';
      const saved = await api(path, { method: 'POST', body: JSON.stringify({ code: code || undefined, type, title, category, description, unit, defaultUnitPriceEur, ...booking }) });
      closeDrawer();
      await loadModule('catalog');
      render();
      const ref = saved?.code || title;
      toast(editing ? t.updated({ code: ref }) : t.created({ code: ref }));
    } catch (err) { btn.disabled = false; toast(err?.code === 'code_taken' ? t.codeTaken : err?.code === 'code_invalid' ? t.codeInvalid : STR.catalogCreateFailed); }
  });
  openDrawer(editing ? t.editTitleFull({ code: editing.code || editing.title }) : t.newTitle, form);
}

async function deleteCatalogItem(id) {
  const item = state.catalog.find(c => c.id === id);
  if (!item) return;
  const t = CRM.items;
  const ok = await confirmDialog({ title: t.confirmTitle, body: t.confirmBody({ desc: item.title }), okLabel: t.confirmOk });
  if (!ok) return;
  try {
    await api(`/app/api/crm/standard-items/${encodeURIComponent(id)}`, { method: 'DELETE' });
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
    return `<td><div class="col"><span class="name">${escapeHTML(i.title)}</span>${details ? `<span class="sub">${escapeHTML(details)}</span>` : ''}</div></td>`;
  };
  return [
    { id: 'code', label: t.thCode, cell: i => `<td class="id">${escapeHTML(i.code || '')}</td>` },
    { id: 'title', label: t.thTitle, cell: title },
    { id: 'type', label: t.thType, cell: i => `<td>${catalogTypePill(i.type)}${i.bookable && i.durationMinutes && hasModule('bookings') ? ` <span class="pill pill--ok">${escapeHTML(t.bookablePill({ min: i.durationMinutes }))}</span>` : ''}</td>` },
    { id: 'category', label: t.thCategory, cell: i => `<td class="muted">${escapeHTML(i.category)}</td>` },
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
  const rows = state.catalog
    .filter(i => !q || `${i.code || ''} ${i.title || ''} ${i.description || ''} ${i.category || ''}`.toLowerCase().includes(q))
    .map(i => `<tr>${columnCells(columns, i)}</tr>`)
    .join('');
  const nSrv = state.catalog.filter(i => i.type === 'service' || i.type === 'servico').length;
  const nMat = state.catalog.length - nSrv;
  root.innerHTML = hero(labels.catalog, CRM.tabs.items.desc, statCards([
    { label: t.services, value: nSrv },
    { label: t.materials, value: nMat },
  ])) + crmPanel({
    title: t.catalogTitle,
    tag: t.tag({ n: state.catalog.length }),
    views: catalogClientsOn() ? catalogViewChips('') : '',
    tools: columnOrderButton('catalog'),
    head: columnsHead(columns, 'catalog'),
    rows,
    empty: t.emptyTitle,
    emptyDesc: t.emptyDesc,
  });
  $$('[data-edit-item]', root).forEach(btn => btn.addEventListener('click', () => openCatalogForm(btn.dataset.editItem)));
  $$('[data-delete-item]', root).forEach(btn => btn.addEventListener('click', () => deleteCatalogItem(btn.dataset.deleteItem)));
  wireColumnOrder(root, 'catalog', catalogColumns());
  wireCatalogViews(root);
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
