/* Catalog (/app → Catalog): the company's standard services and materials, which the line-items editor
   offers. The list deletes them; a form adds or edits one. */

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

function renderCatalog(root) {
  const t = CRM.items;
  const q = state.search.toLowerCase();
  const rows = state.catalog
    .filter(i => !q || `${i.code || ''} ${i.title || ''} ${i.description || ''} ${i.category || ''}`.toLowerCase().includes(q))
    .map(i => {
      const details = catalogDetails(i);
      return `<tr>
      <td class="id">${escapeHTML(i.code || '')}</td>
      <td><div class="col"><span class="name">${escapeHTML(i.title)}</span>${details ? `<span class="sub">${escapeHTML(details)}</span>` : ''}</div></td>
      <td>${catalogTypePill(i.type)}${i.bookable && i.durationMinutes && hasModule('bookings') ? ` <span class="pill pill--ok">${escapeHTML(t.bookablePill({ min: i.durationMinutes }))}</span>` : ''}</td>
      <td class="muted">${escapeHTML(i.category)}</td>
      <td class="mono muted">${escapeHTML(i.unit)}</td>
      <td class="num">${fmtEUR(i.defaultUnitPriceEur)}</td>
      <td class="right"><div class="actions">
        <button class="iconbtn" type="button" title="${escapeHTML(t.editTitle)}" data-edit-item="${escapeHTML(i.id)}"><svg width="13" height="13" viewBox="0 0 16 16"><path d="M11 2 L14 5 L5 14 L2 14 L2 11 Z" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/></svg></button>
        <button class="iconbtn iconbtn--danger" type="button" title="${escapeHTML(t.deleteTitle)}" data-delete-item="${escapeHTML(i.id)}"><svg width="13" height="13" viewBox="0 0 16 16"><path d="M3 5 L13 5 M6 5 L6 3 L10 3 L10 5 M5 5 L6 13 L10 13 L11 5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
      </div></td>
    </tr>`;
    }).join('');
  const nSrv = state.catalog.filter(i => i.type === 'service' || i.type === 'servico').length;
  const nMat = state.catalog.length - nSrv;
  root.innerHTML = hero(labels.catalog, CRM.tabs.items.desc, statCards([
    { label: t.services, value: nSrv },
    { label: t.materials, value: nMat },
  ])) + crmPanel({
    title: t.catalogTitle,
    tag: t.tag({ n: state.catalog.length }),
    head: `<tr><th>${escapeHTML(t.thCode)}</th><th>${escapeHTML(t.thTitle)}</th><th>${escapeHTML(t.thType)}</th><th>${escapeHTML(t.thCategory)}</th><th>${escapeHTML(t.thUnit)}</th><th class="right">${escapeHTML(t.thPrice)}</th><th class="right">${escapeHTML(t.thActions)}</th></tr>`,
    rows,
    empty: t.emptyTitle,
    emptyDesc: t.emptyDesc,
  });
  $$('[data-edit-item]', root).forEach(btn => btn.addEventListener('click', () => openCatalogForm(btn.dataset.editItem)));
  $$('[data-delete-item]', root).forEach(btn => btn.addEventListener('click', () => deleteCatalogItem(btn.dataset.deleteItem)));
}
