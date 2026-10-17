/* What the quote, invoice, payment and Serviços forms share: the client, supplier and employee pickers,
   the line-items editor, opening a document's PDF, and sending a quote or invoice by email. */

// Shared client picker + line-items editor for quotes and invoices.
function clientSelect(clients, selectedId = '') {
  const list = [...clients].sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), uiLocale(), { sensitivity: 'base' }));
  return `<div class="form__row"><label class="lbl" for="f-client">${escapeHTML(STR.lineClient)} <span class="req">●</span></label>
    <select class="sel" id="f-client" required><option value="">${escapeHTML(STR.lineChooseClient)}</option>
    ${list.map(c => `<option value="${escapeHTML(c.id)}"${c.id === selectedId ? ' selected' : ''}>${escapeHTML(c.name)}</option>`).join('')}</select></div>`;
}
// A form opened from a client record keeps that client even if the directory list doesn't have it yet.
const withClient = (clients, client) => (client && !clients.some(c => c.id === client.id) ? [client, ...clients] : clients);

function supplierSelect(suppliers, selectedId = '') {
  const t = CRM.payments;
  return `<div class="form__row" id="payee-supplier"><label class="lbl" for="f-supplier">${escapeHTML(t.thSupplier)} <span class="req">●</span></label>
    <select class="sel" id="f-supplier"><option value="">${escapeHTML(t.chooseSupplierEmpty)}</option>
    ${suppliers.map(s => `<option value="${escapeHTML(s.id)}" ${s.id === selectedId ? 'selected' : ''}>${escapeHTML(s.name)}</option>`).join('')}</select></div>`;
}

function employeeSelect(employees, selectedId = '') {
  const t = CRM.payments;
  return `<div class="form__row" id="payee-employee"><label class="lbl" for="f-employee">${escapeHTML(t.thEmployee)} <span class="req">●</span></label>
    <select class="sel" id="f-employee"><option value="">${escapeHTML(t.chooseEmployeeEmpty)}</option>
    ${employees.map(e => `<option value="${escapeHTML(e.id)}" ${e.id === selectedId ? 'selected' : ''}>${escapeHTML(e.name)}</option>`).join('')}</select></div>`;
}

// Optional: the client an expense was for. It then counts as spent in that client's finances.
function paymentClientSelect(clients, selectedId = '') {
  const t = CRM.payments;
  const list = [...clients].sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), uiLocale(), { sensitivity: 'base' }));
  return `<div class="form__row"><label class="lbl" for="p-client">${escapeHTML(t.client)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
    <select class="sel" id="p-client"><option value="">${escapeHTML(t.clientNone)}</option>
    ${list.map(c => `<option value="${escapeHTML(c.id)}"${c.id === selectedId ? ' selected' : ''}>${escapeHTML(c.name)}</option>`).join('')}</select></div>`;
}

function payeeName(payment) {
  return payment.employeeName || payment.supplierName || '';
}

function payeeId(payment) {
  return payment.employeeId || payment.supplierId || '';
}

function generalPaymentFields() {
  const t = CRM.payments;
  return `<div id="payment-general" class="form__grid">
    <div class="form__row"><label class="lbl" for="p-amount">${escapeHTML(t.amount)} <span class="req">●</span></label>
      <input class="inp inp--mono inp--right" id="p-amount" type="number" min="0" step="0.01" placeholder="0.00" /></div>
    <div class="form__row"><label class="lbl" for="p-desc">${escapeHTML(t.generalEntry)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
      <input class="inp" id="p-desc" value="${escapeHTML(t.generalEntryValue)}" /></div>
  </div>`;
}

// Each row's description field is also the catalog picker: click it (or press ↓) for the full
// list, keep typing to filter it, pick an entry to fill description, unit and price. Nothing
// forces a catalog item — a line typed by hand stays as typed. The foot only adds empty rows.
let lineComboSeq = 0;

function lineItemsField(catalog) {
  // Legacy items were stored as `servico`; group them with the services rather than dropping them.
  const options = (catalog || []).map(c => ({
    item: c,
    service: c.type === 'service' || c.type === 'servico',
    label: catalogItemLabel(c),
    meta: `${fmtEUR(c.defaultUnitPriceEur)}${c.unit ? `/${c.unit}` : ''}`,
    haystack: foldText(`${c.code || ''} ${c.title || ''} ${c.category || ''} ${c.description || ''}`),
  }));
  const html = `
    <div class="lines-field">
      <div class="lbl">${escapeHTML(STR.lineItemsLabel)} <span class="req">●</span></div>
      <div class="lines${options.length ? ' lines--combo' : ''}">
        <div class="lines__head"><span>${escapeHTML(STR.lineColDesc)}</span><span>${escapeHTML(STR.lineColQty)}</span><span>${escapeHTML(STR.lineColUnit)}</span><span>${escapeHTML(STR.lineColPrice)}</span><span></span></div>
        <div id="lines-body"></div>
        <div class="lines__foot">
          <button class="btn btn--sm btn--ghost" type="button" id="add-line">${escapeHTML(STR.lineAddItem)}</button>
          <div class="lines__total"><span class="muted">${escapeHTML(STR.lineTotal)}</span><span class="v" id="lines-total">${fmtEUR(0)}</span></div>
        </div>
      </div>
    </div>`;
  const collect = form => $$('.line', form).map(row => {
    const get = k => $(`[data-k="${k}"]`, row).value;
    return { description: get('description').trim(), quantity: Number(get('quantity') || 0), unit: get('unit').trim(), unitPriceEur: Number(get('unitPriceEur') || 0) };
  }).filter(it => it.description && (it.quantity > 0 || it.unitPriceEur > 0));
  // Services come first, then materials, each under its own heading. The rendered order is the
  // order the arrow keys walk, so the caller keeps this list to resolve `data-opt`.
  const optionsHTML = (list, optsId) => {
    const ordered = [...list.filter(o => o.service), ...list.filter(o => !o.service)];
    let heading = null;
    const markup = ordered.map((o, i) => {
      const group = o.service ? STR.catalogService : STR.catalogMaterial;
      const head = group === heading ? '' : `<p class="suggest__group">${escapeHTML(group)}</p>`;
      heading = group;
      return `${head}<button type="button" class="suggest__item" role="option" aria-selected="false" tabindex="-1" id="${optsId}-${i}" data-opt="${i}"><strong>${escapeHTML(o.label)}</strong><span class="mono">${escapeHTML(o.meta)}</span></button>`;
    }).join('');
    return { ordered, markup };
  };
  // `initial` rows replace the empty first row; `onChange` runs after every edit, with the total.
  const wire = (form, { initial = [], onChange } = {}) => {
    const body = $('#lines-body', form);
    const recalc = () => {
      const total = collect(form).reduce((t, it) => t + it.quantity * it.unitPriceEur, 0);
      $('#lines-total', form).textContent = fmtEUR(total);
      onChange?.(total);
    };
    const closeCombos = except => $$('.line__opts', form).forEach(box => {
      if (box === except || box.hidden) return;
      box.hidden = true;
      const desc = $('[data-k="description"]', box.parentElement);
      desc?.setAttribute('aria-expanded', 'false');
      desc?.removeAttribute('aria-activedescendant');
    });
    const wireCombo = (row, desc) => {
      const box = $('.line__opts', row);
      if (!box) return;
      let shown = [];
      let active = -1;
      const close = () => {
        box.hidden = true;
        active = -1;
        desc.setAttribute('aria-expanded', 'false');
        desc.removeAttribute('aria-activedescendant');
      };
      const mark = () => $$('.suggest__item', box).forEach((el, i) => {
        el.setAttribute('aria-selected', i === active ? 'true' : 'false');
        if (i !== active) return;
        el.scrollIntoView({ block: 'nearest' });
        desc.setAttribute('aria-activedescendant', el.id);
      });
      const open = query => {
        const q = foldText(query.trim());
        const hits = q ? options.filter(o => o.haystack.includes(q)) : options;
        const { ordered, markup } = optionsHTML(hits, box.id);
        shown = ordered;
        active = -1;
        box.innerHTML = markup || `<p class="suggest__empty">${escapeHTML(STR.lineCatalogNoMatch)}</p>`;
        box.scrollTop = 0;
        box.hidden = false;
        desc.setAttribute('aria-expanded', 'true');
        desc.removeAttribute('aria-activedescendant');
        closeCombos(box);
      };
      const pick = o => {
        if (!o) return;
        row.dataset.catalog = o.item.id;
        desc.value = catalogLineText(o.item);
        const qty = $('[data-k="quantity"]', row);
        if (!(Number(qty.value) > 0)) qty.value = 1;
        $('[data-k="unit"]', row).value = o.item.unit || '';
        $('[data-k="unitPriceEur"]', row).value = o.item.defaultUnitPriceEur ?? '';
        close();
        recalc();
        desc.focus();
      };
      // Keep the caret in the field on mousedown so clicking an option never blurs it away first.
      box.addEventListener('mousedown', e => { if (e.target.closest('.suggest__item')) e.preventDefault(); });
      box.addEventListener('click', e => {
        const btn = e.target.closest('[data-opt]');
        if (btn) pick(shown[Number(btn.dataset.opt)]);
      });
      desc.addEventListener('input', () => open(desc.value));
      desc.addEventListener('click', () => { if (box.hidden) open(desc.value); });
      desc.addEventListener('blur', close);
      desc.addEventListener('keydown', e => {
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
          e.preventDefault();
          if (box.hidden) return open(desc.value);
          if (!shown.length) return;
          const step = e.key === 'ArrowDown' ? 1 : -1;
          active = active < 0 ? (step > 0 ? 0 : shown.length - 1) : (active + step + shown.length) % shown.length;
          mark();
        } else if (e.key === 'Enter' && !box.hidden) {
          e.preventDefault();
          if (active < 0) close(); else pick(shown[active]);
        } else if (e.key === 'Escape' && !box.hidden) {
          e.preventDefault();
          e.stopPropagation();
          close();
        }
      });
      const caret = $('.line__caret', row);
      caret.addEventListener('mousedown', e => e.preventDefault());
      caret.addEventListener('click', () => {
        if (!box.hidden) return close();
        desc.focus();
        open('');
      });
    };
    const addRow = (preset = {}) => {
      const row = document.createElement('div');
      row.className = 'line';
      if (preset.catalogItemId) row.dataset.catalog = preset.catalogItemId;
      const optsId = `line-opts-${++lineComboSeq}`;
      const combo = options.length
        ? ` role="combobox" aria-expanded="false" aria-autocomplete="list" aria-controls="${optsId}"`
        : '';
      row.innerHTML = `
        <div class="line__desc">
          <input type="text" data-k="description" autocomplete="off"${combo} placeholder="${escapeHTML(STR.lineDescPh)}" value="${escapeHTML(preset.description || '')}" />
          ${options.length ? `<button type="button" class="line__caret" tabindex="-1" title="${escapeHTML(STR.lineCatalogOpen)}" aria-label="${escapeHTML(STR.lineCatalogOpen)}"><svg width="10" height="7" viewBox="0 0 10 7" aria-hidden="true"><path d="M1 1.5 L5 5.5 L9 1.5" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
          <div class="suggest suggest--scroll line__opts" id="${optsId}" role="listbox" hidden></div>` : ''}
        </div>
        <input type="number" data-k="quantity" class="num" min="0" step="0.01" placeholder="0" value="${preset.quantity ?? ''}" />
        <input type="text" data-k="unit" class="num" placeholder="${escapeHTML(STR.lineUnitPh)}" value="${escapeHTML(preset.unit || '')}" />
        <input type="number" data-k="unitPriceEur" class="num" min="0" step="0.01" placeholder="0.00" value="${preset.unitPriceEur ?? ''}" />
        <button type="button" class="l-rm" title="${escapeHTML(STR.lineRemove)}"><svg width="12" height="12" viewBox="0 0 16 16"><path d="M3 3 L13 13 M13 3 L3 13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg></button>`;
      row.querySelectorAll('input').forEach(i => i.addEventListener('input', recalc));
      row.querySelector('.l-rm').addEventListener('click', () => { row.remove(); recalc(); });
      body.appendChild(row);
      wireCombo(row, $('[data-k="description"]', row));
      recalc();
      return row;
    };
    if (initial.length) initial.forEach(addRow);
    else addRow();
    $('#add-line', form).addEventListener('click', () => $('[data-k="description"]', addRow()).focus());
    return { addRow, recalc };
  };
  return { html, wire, collect };
}

async function openPdf(url, downloadName) {
  const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (res.status === 401) { localStorage.removeItem('dashboardToken'); token = ''; renderLogin(); throw new Error('unauthorized'); }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const blob = await res.blob();
  const objectUrl = URL.createObjectURL(blob);
  if (downloadName) {
    const a = document.createElement('a');
    a.href = objectUrl;
    a.download = downloadName.endsWith('.pdf') ? downloadName : `${downloadName}.pdf`;
    a.rel = 'noopener';
    document.body.appendChild(a);
    a.click();
    a.remove();
  } else {
    window.open(objectUrl, '_blank');
  }
  setTimeout(() => URL.revokeObjectURL(objectUrl), 60000);
}

function pdfButton(id, type, hasPdf, label, downloadName) {
  const downloadAttr = downloadName ? ` data-pdf-download="${escapeHTML(downloadName)}"` : '';
  return `<button class="pdf" type="button" data-pdf-url="/app/api/crm/${type}/${encodeURIComponent(id)}/pdf"${downloadAttr} title="${escapeHTML(STR.viewPdf)}">
    <svg width="11" height="13" viewBox="0 0 11 13"><path d="M1 1 H7 L10 4 V12 H1 Z" fill="none" stroke="currentColor" stroke-width="1.2"/><text x="5.5" y="10" font-family="monospace" font-size="3.6" text-anchor="middle" fill="currentColor">PDF</text></svg>
    ${escapeHTML(label || STR.viewPdf)}</button>`;
}

function wirePdfButtons(root) {
  $$('[data-pdf-url]', root).forEach(btn => {
    btn.addEventListener('click', async e => {
      e.stopPropagation();
      try { await openPdf(btn.dataset.pdfUrl, btn.dataset.pdfDownload); }
      catch (err) { toast(STR.errorPdf({ msg: err.message })); }
    });
  });
}

const newRequestId = () => (window.crypto?.randomUUID ? crypto.randomUUID() : `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`);

// A quote or invoice to its client, from the company's Gmail, with the PDF attached.
async function openSendEmail(type, id, number) {
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `<p class="hint">${escapeHTML(CRM.loading)}</p>`;
  const gen = openDrawer(googleText('sendTitle', { number }), form, false, { eyebrow: GOOGLE.sendByEmail });
  let draft;
  try { draft = await api(`/app/api/email/draft?type=${encodeURIComponent(type)}&id=${encodeURIComponent(id)}`); }
  catch (err) {
    if (gen !== drawerGen) return;
    closeDrawer();
    if (err.message !== 'unauthorized') toast(googleTextOr(`sendErrors.${err.code || ''}`, GOOGLE.sendFailed));
    return;
  }
  if (gen !== drawerGen) return;
  // The server sends a drawer's email once, however often Send is pressed.
  const requestId = newRequestId();
  const admin = state.me?.principalType === 'tenant' && state.me?.user?.role === 'TENANT_ADMIN';
  const required = ' <span class="req">●</span>';
  // `from` without `canSend`: the company's sending account is there but Google stopped accepting it.
  const blocked = draft.canSend ? null : draft.from
    ? { title: googleText('sendReconnectTitle', { account: draft.from }), desc: admin ? GOOGLE.sendReconnectDesc : GOOGLE.sendReconnectMember }
    : { title: GOOGLE.noAccountTitle, desc: admin ? GOOGLE.noAccountDesc : GOOGLE.noAccountMember };
  form.innerHTML = `
    ${!blocked ? '' : `<div class="notice notice--warn">
      <div class="notice__text"><strong>${escapeHTML(blocked.title)}</strong><span>${escapeHTML(blocked.desc)}</span></div>
      ${hasModule('settings') ? `<div class="notice__actions"><button class="btn btn--sm" type="button" data-se-settings>${escapeHTML(GOOGLE.openSettings)}</button></div>` : ''}
    </div>`}
    <div class="form__row"><label class="lbl" for="se-to">${escapeHTML(GOOGLE.to)}${required}</label>
      <input class="inp" id="se-to" type="email" required maxlength="254" autocomplete="off" value="${escapeHTML(draft.to || '')}" />
      ${draft.to ? '' : `<p class="hint hint--warn">${escapeHTML(GOOGLE.noClientEmail)}</p>`}</div>
    <div class="form__row"><label class="lbl" for="se-cc">${escapeHTML(GOOGLE.cc)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
      <input class="inp" id="se-cc" maxlength="1000" autocomplete="off" />
      <p class="hint">${escapeHTML(GOOGLE.ccHint)}</p></div>
    <div class="form__row"><label class="lbl" for="se-subject">${escapeHTML(GOOGLE.subject)}${required}</label>
      <input class="inp" id="se-subject" required maxlength="300" value="${escapeHTML(draft.subject)}" /></div>
    <div class="form__row"><label class="lbl" for="se-text">${escapeHTML(GOOGLE.message)}${required}</label>
      <textarea class="txt" id="se-text" rows="9" required maxlength="20000">${escapeHTML(draft.text)}</textarea></div>
    <label class="form__check"><input type="checkbox" id="se-pdf" checked /> ${escapeHTML(googleText('attach', { file: draft.attachment }))}</label>
    ${draft.from && draft.canSend ? `<p class="hint">${escapeHTML([googleText('from', { account: draft.from }), draft.marksSent ? GOOGLE.marksSent : ''].filter(Boolean).join(' '))}</p>` : ''}
    <div class="actions"><button class="btn btn--primary" type="submit" ${draft.canSend ? '' : 'disabled'}>${escapeHTML(GOOGLE.send)}</button></div>`;
  $('[data-se-settings]', form)?.addEventListener('click', () => {
    closeDrawer({ dismissed: true });
    state.settingsSection = 'channels';
    setActive('settings');
  });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const payload = {
      type, id, requestId,
      to: $('#se-to', form).value.trim(),
      cc: $('#se-cc', form).value.trim() || null,
      subject: $('#se-subject', form).value.trim(),
      text: $('#se-text', form).value.trim(),
      attachPdf: $('#se-pdf', form).checked,
    };
    const missing = !payload.to ? 'no_email' : !payload.subject ? 'empty_subject' : !payload.text ? 'empty_message' : '';
    if (missing) return toast(googleText(`sendErrors.${missing}`));
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const sent = await api('/app/api/email/send', { method: 'POST', body: JSON.stringify(payload) });
      toast(sent.alreadySent ? GOOGLE.alreadySent : googleText('sent', { to: sent.to }));
      if (sent.markedSent && state.active === 'quotes') { await loadModule('quotes').catch(() => {}); render(); }
      closeDrawer();
    } catch (err) {
      btn.disabled = false;
      if (err.message !== 'unauthorized') toast(googleTextOr(`sendErrors.${err.code || ''}`, GOOGLE.sendFailed));
    }
  });
}

function emailRowHtml(m) {
  const outbound = m.direction === 'OUTBOUND';
  return recordRowHtml({
    tone: outbound ? 'info' : 'accent',
    title: m.subject || '—',
    detail: [
      outbound ? googleText('sentTo', { to: (m.to || []).join(', ') }) : googleText('receivedFrom', { from: m.fromName || m.from }),
      (m.attachments || []).join(', '),
      m.snippet,
    ].filter(Boolean).join(' · '),
    when: relTime(m.date),
    open: `email:${m.id}`,
  });
}

function openEmailMessage(m) {
  const outbound = m.direction === 'OUTBOUND';
  const here = { key: `email:${m.id}`, label: m.subject || GOOGLE.msgEyebrow, open: () => openEmailMessage(m) };
  const named = ['USER', 'AGENT'].includes(m.sentByType);
  const sentBy = !m.sentByType ? '' : named ? (m.sentBy ? googleText(`by.${m.sentByType}`, { name: m.sentBy }) : '') : googleTextOr(`by.${m.sentByType}`, '');
  const doc = m.recordType === 'quote' && hasModule('quotes') ? { key: `quote:${m.recordId}`, label: GOOGLE.openQuote, open: () => openQuoteDetail(m.recordId) }
    : m.recordType === 'invoice' && hasModule('invoices') ? { key: `invoice:${m.recordId}`, label: GOOGLE.openInvoice, open: () => openInvoiceDetail(m.recordId) }
      : null;
  const fact = (label, value) => (value ? `<div><dt>${escapeHTML(label)}</dt><dd>${escapeHTML(value)}</dd></div>` : '');
  const attachments = m.attachments || [];
  const form = document.createElement('div');
  form.className = 'form';
  form.innerHTML = `
    <div class="detail__head"><div class="detail__client">${escapeHTML(m.fromName || m.from || '—')}</div>
      <div class="detail__amount"><span class="pill ${outbound ? 'pill--info' : 'pill--accent'}">${escapeHTML(googleTextOr(`direction.${m.direction}`, m.direction))}</span></div></div>
    <dl class="dash-facts">
      ${m.fromName ? fact(GOOGLE.msgFrom, m.from) : ''}
      ${fact(GOOGLE.msgTo, (m.to || []).join(', '))}
      ${fact(GOOGLE.msgCc, (m.cc || []).join(', '))}
      ${fact(GOOGLE.msgDate, fmtDate(m.date))}
      ${fact(GOOGLE.msgSentBy, sentBy)}
    </dl>
    ${m.bodyPurged ? `<p class="hint">${escapeHTML(GOOGLE.purged)}</p>` : `<div class="email-body">${escapeHTML(m.body ?? m.snippet ?? '')}</div>`}
    ${attachments.length ? `<p class="hint">${escapeHTML(googleText('attachments', { files: attachments.join(', ') }))}</p>` : ''}
    ${(doc && !linksBackTo(doc.key)) || hasModule('email') ? `<div class="detail__foot">
      ${doc && !linksBackTo(doc.key) ? `<button class="btn btn--sm btn--ghost" type="button" data-email-doc>${escapeHTML(doc.label)}</button>` : ''}
      ${hasModule('email') ? `<button class="btn btn--sm btn--ghost" type="button" data-email-page>${escapeHTML(I18N.t('app.email.openInEmail'))}</button>` : ''}
    </div>` : ''}`;
  $('[data-email-doc]', form)?.addEventListener('click', () => openFrom(here, doc.open));
  $('[data-email-page]', form)?.addEventListener('click', () => openEmailPage(m.id));
  openDrawer(m.subject || GOOGLE.msgEyebrow, form, false, { eyebrow: GOOGLE.msgEyebrow });
}
