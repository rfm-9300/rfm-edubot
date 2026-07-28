const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
let token = localStorage.getItem('dashboardToken') || '';
let state = { me: null, overview: null, contacts: [], conversations: [], clients: [], quotes: [], invoices: [], catalog: [], persona: null, personaChat: [], webWidget: null, search: '', active: 'overview', selectedAsset: '' };
let personaChatBusy = false;

// Module nav labels + user-facing copy come from the shared i18n catalogs (admin/catalog.*.js).
// `labels`/`STR` are live proxies over the active locale, so every render() reads the current language.
const labels = I18N.section('common.nav');
const STR = I18N.section('app');
const escapeHTML = (s = '') => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
const slugify = (s = '') => s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
const fmtEUR = n => new Intl.NumberFormat('pt-PT', { style: 'currency', currency: 'EUR' }).format(Number(n || 0));
const fmtDate = iso => iso ? new Date(iso).toLocaleString('pt-PT', { dateStyle: 'short', timeStyle: 'short' }) : '—';

async function api(path, options = {}) {
  const headers = { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) };
  const res = await fetch(path, { ...options, headers: { ...headers, ...(options.headers || {}) } });
  if (res.status === 401) { localStorage.removeItem('dashboardToken'); token = ''; renderLogin(); throw new Error('unauthorized'); }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  if (res.status === 204) return null;
  return res.json();
}

let toastTimer;
function toast(msg) { const el = $('#toast'); el.innerHTML = `<span class="toast__dot"></span><span>${escapeHTML(msg)}</span>`; el.hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => { el.hidden = true; }, 2800); }

function renderLogin() {
  $('#nav').innerHTML = '';
  $('#view').innerHTML = `<div class="auth"><div class="auth__card"><div class="auth__mark">AI</div><p class="auth__eyebrow">${escapeHTML(STR.loginEyebrow)}</p><h1 class="auth__title">${escapeHTML(STR.loginTitle)}</h1><p class="auth__desc">${escapeHTML(STR.loginDesc)}</p><form class="form" id="login-form"><div class="form__row"><label class="lbl" for="email">${escapeHTML(STR.loginEmail)}</label><input class="inp" id="email" type="email" autocomplete="email" required /></div><div class="form__row"><label class="lbl" for="password">${escapeHTML(STR.loginPassword)}</label><input class="inp" id="password" type="password" autocomplete="current-password" required /></div><button class="btn btn--primary" type="submit">${escapeHTML(STR.loginSubmit)}</button></form></div></div>`;
  $('#login-form').addEventListener('submit', async e => {
    e.preventDefault();
    try { const res = await api('/app/auth/login', { method: 'POST', body: JSON.stringify({ email: $('#email').value, password: $('#password').value }) }); token = res.token; localStorage.setItem('dashboardToken', token); await bootAuthed(); }
    catch { toast(STR.loginInvalid); }
  });
}

async function bootAuthed() {
  state.me = await api('/app/api/me');
  // Adopt the tenant's language (unless the user picked an explicit override this session), then
  // refresh the static chrome that was rendered before /me resolved.
  I18N.applyTenantDefault(state.me.tenant.locale);
  I18N.applyDom(document);
  $('#brand-name').textContent = state.me.tenant.name;
  $('#brand-sub').textContent = `${state.me.tenant.agentType} · ${state.me.tenant.slug}`;
  $('#principal-type').textContent = state.me.principalType;
  if (!state.me.modules.includes(state.active)) state.active = state.me.modules[0] || 'settings';
  renderNav();
  await loadModule(state.active);
  render();
}

function renderNav() {
  $('#nav').innerHTML = state.me.modules.map(m => `<a class="nav__item" data-tab="${m}" href="#${m}"><span class="nav__dot"></span><span class="nav__label">${labels[m] || m}</span><span class="nav__count"> </span></a>`).join('');
  $$('.nav__item').forEach(a => a.addEventListener('click', async e => { e.preventDefault(); await setActive(a.dataset.tab); }));
}

async function setActive(tab) { state.active = tab; location.hash = tab; $('#search').value = ''; state.search = ''; await loadModule(tab); render(); }

async function loadModule(tab) {
  if (tab === 'overview') state.overview = await api('/app/api/overview');
  if (tab === 'contacts') state.contacts = await api('/app/api/contacts');
  if (tab === 'conversations') state.conversations = await api('/app/api/conversations');
  if (tab === 'clients') state.clients = await api('/app/api/crm/clients');
  if (tab === 'quotes') state.quotes = await api('/app/api/crm/quotes');
  if (tab === 'invoices') state.invoices = await api('/app/api/crm/invoices');
  if (tab === 'catalog') state.catalog = await api('/app/api/crm/standard-items');
  if (tab === 'persona') state.persona = await api('/app/api/persona');
  if (tab === 'settings') state.webWidget = await api('/app/api/web-widget').catch(() => ({ publicKey: null, allowedOrigins: [] }));
}

function render() {
  $$('.nav__item').forEach(a => a.classList.toggle('is-active', a.dataset.tab === state.active));
  $('#crumb-leaf').textContent = labels[state.active] || state.active;
  $('#meta-clock').textContent = new Date().toLocaleString('pt-PT', { hour: '2-digit', minute: '2-digit' });
  $('#kpi-messages').textContent = state.overview?.messages ?? '—';
  $('#kpi-users').textContent = state.overview?.users ?? '—';
  $('#btn-new').hidden = !['clients', 'quotes', 'invoices', 'catalog'].includes(state.active);
  $('#btn-new').textContent = `${STR.newPrefix} ${labels[state.active] || ''}`;
  const root = $('#view');
  if (state.active === 'overview') return renderOverview(root);
  if (state.active === 'contacts') return renderContacts(root);
  if (state.active === 'conversations') return renderConversations(root);
  if (state.active === 'clients') return renderClients(root);
  if (state.active === 'quotes') return renderQuotes(root);
  if (state.active === 'invoices') return renderInvoices(root);
  if (state.active === 'catalog') return renderCatalog(root);
  if (state.active === 'persona') return renderPersona(root);
  renderSettings(root);
}

function hero(title, desc) { return `<div class="view__hero"><div><h1 class="view__title">${escapeHTML(title)}</h1><p class="view__desc">${escapeHTML(desc)}</p></div></div>`; }
function panelTable(head, rows, empty = STR.noData) { return `<div class="panel"><div class="tbl-wrap"><table class="tbl"><thead>${head}</thead><tbody>${rows || `<tr><td colspan="8"><div class="empty"><p class="empty__title">${empty}</p></div></td></tr>`}</tbody></table></div></div>`; }

function renderOverview(root) { const o = state.overview || {}; root.innerHTML = `${hero(labels.overview, STR.overviewDesc)}<div class="view__stats" style="margin-bottom:22px"><div class="stat"><div class="stat__label">${STR.statMessages24h}</div><div class="stat__value">${o.messagesToday || 0}</div></div><div class="stat"><div class="stat__label">${STR.statMessages}</div><div class="stat__value">${o.messages || 0}</div></div><div class="stat"><div class="stat__label">${STR.statContacts}</div><div class="stat__value">${o.users || 0}</div></div><div class="stat"><div class="stat__label">${STR.statConversations}</div><div class="stat__value">${o.conversations || 0}</div></div><div class="stat"><div class="stat__label">${STR.statQuotes}</div><div class="stat__value">${o.quotes || 0}</div></div><div class="stat"><div class="stat__label">${STR.statInvoices}</div><div class="stat__value">${o.invoices || 0}</div></div></div>`; }
function renderContacts(root) { const q = state.search.toLowerCase(); const rows = state.contacts.filter(c => !q || `${c.displayName || ''} ${c.waId}`.toLowerCase().includes(q)).map(c => `<tr><td class="name">${escapeHTML(c.displayName || '—')}</td><td>${escapeHTML(c.channel)}</td><td class="mono">${escapeHTML(c.waId)}</td><td>${c.status}</td><td class="mono muted">${fmtDate(c.lastSeenAt)}</td><td class="right"><button class="btn btn--sm" data-contact-status="${c.id}" data-status="${c.status === 'BLOCKED' ? 'ACTIVE' : 'BLOCKED'}">${c.status === 'BLOCKED' ? STR.unblock : STR.block}</button></td></tr>`).join(''); root.innerHTML = hero(labels.contacts, STR.contactsDesc) + panelTable(`<tr><th>${STR.thName}</th><th>${STR.colChannel}</th><th>${STR.colAccount}</th><th>${STR.thStatus}</th><th>${STR.thLastSeen}</th><th class="right">${STR.thActions}</th></tr>`, rows); $$('[data-contact-status]').forEach(b => b.addEventListener('click', async () => { await api(`/app/api/contacts/${b.dataset.contactStatus}/status`, { method: 'PATCH', body: JSON.stringify({ status: b.dataset.status }) }); await loadModule('contacts'); render(); })); }
function assetLabel(asset) { return `${asset.platform} · ${asset.displayName || STR.unnamedAsset} · ${asset.externalId}`; }
function renderConversations(root) {
  const assets = (state.me?.tenant.channels || []).filter(a => a.platform !== 'WEB');
  if (!assets.some(a => a.externalId === state.selectedAsset)) state.selectedAsset = assets[0]?.externalId || '';
  const selected = assets.find(a => a.externalId === state.selectedAsset);
  const q = state.search.toLowerCase();
  const conversations = state.conversations.filter(c => (!selected || c.channel === selected.platform) && (!q || `${c.displayName || ''} ${c.waId}`.toLowerCase().includes(q)));
  const rows = conversations.map(c => `<tr class="conversation-row" data-conversation="${c.id}"><td>${escapeHTML(c.channel)}</td><td>${escapeHTML(c.displayName || c.waId)}</td><td>${c.state}</td><td class="num">${c.messageCount}</td><td class="mono muted">${fmtDate(c.lastMessageAt)}</td><td class="right"><button class="btn btn--sm" type="button">${escapeHTML(STR.openChat)}</button></td></tr>`).join('');
  const picker = `<div class="asset-picker panel"><div><div class="lbl">${escapeHTML(STR.assetLabel)}</div><p class="hint">${escapeHTML(STR.assetHint)}</p></div><select class="sel asset-picker__select" id="conversation-asset">${assets.map(a => `<option value="${escapeHTML(a.externalId)}" ${a.externalId === state.selectedAsset ? 'selected' : ''}>${escapeHTML(assetLabel(a))}</option>`).join('')}</select></div>`;
  root.innerHTML = hero(labels.conversations, STR.conversationsDesc) + picker + panelTable(`<tr><th>${STR.colChannel}</th><th>${STR.recipient}</th><th>${STR.thState}</th><th>${STR.thMsgs}</th><th>${STR.thLast}</th><th class="right">${STR.thActions}</th></tr>`, rows, assets.length ? STR.noAssetConversations : STR.noMessagingAssets);
  $('#conversation-asset')?.addEventListener('change', e => { state.selectedAsset = e.target.value; renderConversations(root); });
  $$('[data-conversation]').forEach(r => r.addEventListener('click', () => openThread(r.dataset.conversation)));
}
async function openThread(id) {
  const conversation = state.conversations.find(c => c.id === id);
  const asset = (state.me?.tenant.channels || []).find(a => a.platform === conversation?.channel && a.externalId === state.selectedAsset);
  const msgs = await api(`/app/api/conversations/${id}/messages`);
  const body = document.createElement('div');
  body.className = 'thread';
  const recipient = conversation?.displayName || conversation?.waId || '';
  body.innerHTML = `<div class="thread__asset"><span class="lbl">${escapeHTML(STR.sendingFrom)}</span><strong>${escapeHTML(asset ? assetLabel(asset) : conversation?.channel || '')}</strong><span class="mono muted">${escapeHTML(STR.sendingTo)} ${escapeHTML(recipient)}</span></div><div class="chat__log thread__log" id="thread-log"></div>${asset ? `<form class="chat__form" id="thread-form"><input class="inp chat__input" id="thread-input" maxlength="1000" required placeholder="${escapeHTML(STR.messagePlaceholder)}" autocomplete="off" /><button class="btn btn--primary" type="submit">${escapeHTML(STR.send)}</button></form>` : `<p class="hint">${escapeHTML(STR.sendUnavailable)}</p>`}`;
  const renderMessages = () => {
    const log = $('#thread-log', body);
    log.innerHTML = msgs.map(m => `<div class="chat__msg ${m.role === 'USER' ? 'chat__msg--bot' : 'chat__msg--user'}"><div>${escapeHTML(m.text)}</div><span class="thread__meta">${escapeHTML(m.role === 'USER' ? STR.customer : STR.operator)} · ${escapeHTML(m.status)} · ${fmtDate(m.createdAt)}</span></div>`).join('') || `<div class="chat__empty">${escapeHTML(STR.noMessages)}</div>`;
    log.scrollTop = log.scrollHeight;
  };
  renderMessages();
  $('#thread-form', body)?.addEventListener('submit', async e => {
    e.preventDefault();
    const input = $('#thread-input', body), button = $('button[type=submit]', e.currentTarget), text = input.value.trim();
    if (!text) return;
    button.disabled = true;
    try {
      const sent = await api(`/app/api/conversations/${id}/messages`, { method: 'POST', body: JSON.stringify({ text, assetExternalId: asset.externalId }) });
      msgs.push(sent); input.value = ''; renderMessages(); toast(STR.messageDelivered);
      state.conversations = await api('/app/api/conversations');
    } catch { toast(STR.messageFailed); }
    finally { button.disabled = false; input.focus(); }
  });
  openDrawer(`${STR.threadTitle} · ${recipient}`, body);
}
function renderClients(root) { const rows = state.clients.map(c => `<tr><td class="name">${escapeHTML(c.name)}</td><td class="mono">${escapeHTML(c.phone)}</td><td>${escapeHTML(c.address || '')}</td><td class="id right">${escapeHTML(c.number)}</td></tr>`).join(''); root.innerHTML = hero(labels.clients, STR.clientsDesc) + panelTable(`<tr><th>${STR.thName}</th><th>${STR.thPhone}</th><th>${STR.thAddress}</th><th class="right">${STR.thNo}</th></tr>`, rows); }
function openClientForm() {
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__row"><label class="lbl" for="cf-name">${escapeHTML(STR.clientFormName)} <span class="req">●</span></label>
      <input class="inp" id="cf-name" required placeholder="${escapeHTML(STR.clientPhName)}" /></div>
    <div class="form__row"><label class="lbl" for="cf-phone">${escapeHTML(STR.clientFormPhone)} <span class="req">●</span></label>
      <input class="inp inp--mono" id="cf-phone" required placeholder="${escapeHTML(STR.clientPhPhone)}" /></div>
    <div class="form__row"><label class="lbl" for="cf-address">${escapeHTML(STR.clientFormAddress)}</label>
      <input class="inp" id="cf-address" placeholder="${escapeHTML(STR.clientPhAddress)}" /></div>
    <button class="btn btn--primary" type="submit">${escapeHTML(STR.clientSave)}</button>`;
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const name = $('#cf-name', form).value.trim();
    const phone = $('#cf-phone', form).value.trim();
    const address = $('#cf-address', form).value.trim() || undefined;
    if (!name || !phone) return toast(STR.clientValidate);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api('/app/api/crm/clients', { method: 'POST', body: JSON.stringify({ name, phone, address }) });
      $('#drawer').hidden = true;
      await loadModule('clients');
      render();
      toast(STR.clientCreated({ name }));
    } catch { btn.disabled = false; toast(STR.clientCreateFailed); }
  });
  openDrawer(STR.clientFormTitle, form);
}

function openCatalogForm() {
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      <div class="form__row"><label class="lbl" for="cat-id">${escapeHTML(STR.catalogId)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="cat-id" required placeholder="${escapeHTML(STR.catalogPhId)}" /></div>
      <div class="form__row"><label class="lbl" for="cat-type">${escapeHTML(STR.catalogType)} <span class="req">●</span></label>
        <select class="sel" id="cat-type" required><option value="service">${escapeHTML(STR.catalogService)}</option><option value="material">${escapeHTML(STR.catalogMaterial)}</option></select></div>
      <div class="form__row form__row--full"><label class="lbl" for="cat-cat">${escapeHTML(STR.catalogCategory)} <span class="req">●</span></label>
        <input class="inp" id="cat-cat" required placeholder="${escapeHTML(STR.catalogPhCategory)}" /></div>
      <div class="form__row form__row--full"><label class="lbl" for="cat-desc">${escapeHTML(STR.catalogDesc)} <span class="req">●</span></label>
        <textarea class="txt" id="cat-desc" required placeholder="${escapeHTML(STR.catalogPhDesc)}"></textarea></div>
      <div class="form__row"><label class="lbl" for="cat-unit">${escapeHTML(STR.catalogUnit)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="cat-unit" required placeholder="${escapeHTML(STR.catalogPhUnit)}" /></div>
      <div class="form__row"><label class="lbl" for="cat-price">${escapeHTML(STR.catalogPrice)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="cat-price" type="number" min="0" step="0.01" required placeholder="0.00" /></div>
    </div>
    <button class="btn btn--primary" type="submit">${escapeHTML(STR.catalogSave)}</button>`;
  const idEl = $('#cat-id', form), descEl = $('#cat-desc', form), typeEl = $('#cat-type', form);
  let touched = false;
  idEl.addEventListener('input', () => { touched = true; });
  const refresh = () => { if (!touched) idEl.value = (typeEl.value === 'service' ? 'srv-' : 'mat-') + slugify(descEl.value).slice(0, 40); };
  descEl.addEventListener('input', refresh);
  typeEl.addEventListener('change', refresh);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const id = idEl.value.trim(), category = $('#cat-cat', form).value.trim(), description = descEl.value.trim(), unit = $('#cat-unit', form).value.trim();
    const type = typeEl.value, defaultUnitPriceEur = Number($('#cat-price', form).value || 0);
    if (!id || !category || !description || !unit) return toast(STR.catalogValidate);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api('/app/api/crm/standard-items', { method: 'POST', body: JSON.stringify({ id, type, category, description, unit, defaultUnitPriceEur }) });
      $('#drawer').hidden = true;
      await loadModule('catalog');
      render();
      toast(STR.catalogCreated({ id }));
    } catch { btn.disabled = false; toast(STR.catalogCreateFailed); }
  });
  openDrawer(STR.catalogFormTitle, form);
}

// Shared client picker + line-items editor for quotes and invoices.
function clientSelect(clients) {
  return `<div class="form__row"><label class="lbl" for="f-client">${escapeHTML(STR.lineClient)} <span class="req">●</span></label>
    <select class="sel" id="f-client" required><option value="">${escapeHTML(STR.lineChooseClient)}</option>
    ${clients.map(c => `<option value="${escapeHTML(c.id)}">${escapeHTML(c.name)}</option>`).join('')}</select></div>`;
}

function lineItemsField(catalog) {
  const opt = c => `<option value="${escapeHTML(c.id)}">${escapeHTML(c.description)} · ${fmtEUR(c.defaultUnitPriceEur)}/${escapeHTML(c.unit)}</option>`;
  const services = catalog.filter(c => c.type === 'service');
  const materials = catalog.filter(c => c.type === 'material');
  const html = `
    <div>
      <div class="lbl" style="margin-bottom:8px">${escapeHTML(STR.lineItemsLabel)} <span class="req">●</span></div>
      <div class="lines">
        <div class="lines__head"><span>${escapeHTML(STR.lineColDesc)}</span><span>${escapeHTML(STR.lineColQty)}</span><span>${escapeHTML(STR.lineColUnit)}</span><span>${escapeHTML(STR.lineColPrice)}</span><span></span></div>
        <div id="lines-body"></div>
        <div class="lines__foot">
          <div class="lines__left">
            <button class="btn btn--sm btn--ghost" type="button" id="add-empty">${escapeHTML(STR.lineAddEmpty)}</button>
            <div class="lines__pick">
              <select class="sel" id="catalog-pick"><option value="">${escapeHTML(STR.lineCatalogPick)}</option>
                <optgroup label="${escapeHTML(STR.catalogService)}">${services.map(opt).join('')}</optgroup>
                <optgroup label="${escapeHTML(STR.catalogMaterial)}">${materials.map(opt).join('')}</optgroup>
              </select>
              <button class="btn btn--sm" type="button" id="add-from-catalog">${escapeHTML(STR.lineAdd)}</button>
            </div>
          </div>
          <div class="lines__total"><span class="muted">${escapeHTML(STR.lineTotal)}</span><span class="v" id="lines-total">${fmtEUR(0)}</span></div>
        </div>
      </div>
    </div>`;
  const collect = form => [...$$('.line', form)].map(row => {
    const get = k => $(`[data-k="${k}"]`, row).value;
    return { description: get('description').trim(), quantity: Number(get('quantity') || 0), unit: get('unit').trim(), unitPriceEur: Number(get('unitPriceEur') || 0) };
  }).filter(it => it.description && (it.quantity > 0 || it.unitPriceEur > 0));
  const wire = form => {
    const body = $('#lines-body', form);
    const recalc = () => { $('#lines-total', form).textContent = fmtEUR(collect(form).reduce((t, it) => t + it.quantity * it.unitPriceEur, 0)); };
    const addRow = (preset = {}) => {
      const row = document.createElement('div');
      row.className = 'line';
      row.innerHTML = `
        <input type="text" data-k="description" placeholder="${escapeHTML(STR.lineDescPh)}" value="${escapeHTML(preset.description || '')}" />
        <input type="number" data-k="quantity" class="num" min="0" step="0.01" placeholder="0" value="${preset.quantity ?? ''}" />
        <input type="text" data-k="unit" class="num" placeholder="${escapeHTML(STR.lineUnitPh)}" value="${escapeHTML(preset.unit || '')}" />
        <input type="number" data-k="unitPriceEur" class="num" min="0" step="0.01" placeholder="0.00" value="${preset.unitPriceEur ?? ''}" />
        <button type="button" class="l-rm" title="${escapeHTML(STR.lineRemove)}"><svg width="12" height="12" viewBox="0 0 16 16"><path d="M3 3 L13 13 M13 3 L3 13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg></button>`;
      row.querySelectorAll('input').forEach(i => i.addEventListener('input', recalc));
      row.querySelector('.l-rm').addEventListener('click', () => { row.remove(); recalc(); });
      body.appendChild(row);
      recalc();
    };
    addRow();
    $('#add-empty', form).addEventListener('click', () => addRow());
    $('#add-from-catalog', form).addEventListener('click', () => {
      const id = $('#catalog-pick', form).value;
      if (!id) return toast(STR.lineChooseCatalog);
      const it = catalog.find(c => c.id === id);
      if (it) addRow({ description: it.description, quantity: 1, unit: it.unit, unitPriceEur: it.defaultUnitPriceEur });
    });
  };
  return { html, wire, collect };
}

async function openQuoteForm() {
  let clients, catalog;
  try { [clients, catalog] = await Promise.all([api('/app/api/crm/clients'), api('/app/api/crm/standard-items')]); }
  catch { return toast(STR.quoteCreateFailed); }
  const li = lineItemsField(catalog);
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${clientSelect(clients)}
      <div class="form__row"><label class="lbl" for="q-valid">${escapeHTML(STR.quoteValidUntil)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp inp--mono" id="q-valid" type="date" /></div>
    </div>
    ${li.html}
    <div class="form__row form__row--full"><label class="lbl" for="q-notes">${escapeHTML(STR.quoteNotes)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
      <textarea class="txt" id="q-notes" placeholder="${escapeHTML(STR.quoteNotesPh)}"></textarea></div>
    <button class="btn btn--primary" type="submit">${escapeHTML(STR.quoteSave)}</button>`;
  li.wire(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const clientId = $('#f-client', form).value;
    if (!clientId) return toast(STR.quoteChooseClient);
    const items = li.collect(form);
    if (!items.length) return toast(STR.quoteAddLine);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api('/app/api/crm/quotes', { method: 'POST', body: JSON.stringify({ clientId, items, notes: $('#q-notes', form).value.trim() || null, validUntil: $('#q-valid', form).value || null }) });
      $('#drawer').hidden = true;
      await loadModule('quotes');
      render();
      toast(STR.quoteCreated);
    } catch { btn.disabled = false; toast(STR.quoteCreateFailed); }
  });
  openDrawer(STR.quoteFormTitle, form, true);
}

async function openInvoiceForm() {
  let clients, catalog;
  try { [clients, catalog] = await Promise.all([api('/app/api/crm/clients'), api('/app/api/crm/standard-items')]); }
  catch { return toast(STR.invoiceCreateFailed); }
  const li = lineItemsField(catalog);
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <div class="form__grid">
      ${clientSelect(clients)}
      <div class="form__row"><label class="lbl" for="i-due">${escapeHTML(STR.invoiceDueDate)} <span class="req">●</span></label>
        <input class="inp inp--mono" id="i-due" type="date" required /></div>
      <div class="form__row form__row--full"><label class="lbl" for="i-quote">${escapeHTML(STR.invoiceQuoteId)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
        <input class="inp inp--mono" id="i-quote" placeholder="${escapeHTML(STR.invoiceQuoteIdPh)}" /></div>
    </div>
    ${li.html}
    <button class="btn btn--primary" type="submit">${escapeHTML(STR.invoiceSave)}</button>`;
  li.wire(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const clientId = $('#f-client', form).value;
    const dueDate = $('#i-due', form).value;
    if (!clientId) return toast(STR.invoiceChooseClient);
    if (!dueDate) return toast(STR.invoiceEnterDueDate);
    const items = li.collect(form);
    if (!items.length) return toast(STR.invoiceAddLine);
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api('/app/api/crm/invoices', { method: 'POST', body: JSON.stringify({ clientId, quoteId: $('#i-quote', form).value.trim() || null, items, dueDate }) });
      $('#drawer').hidden = true;
      await loadModule('invoices');
      render();
      toast(STR.invoiceCreated);
    } catch { btn.disabled = false; toast(STR.invoiceCreateFailed); }
  });
  openDrawer(STR.invoiceFormTitle, form, true);
}

function renderQuotes(root) { const rows = state.quotes.map(q => `<tr><td class="id">${escapeHTML(q.number)}</td><td>${escapeHTML(q.clientName || '')}</td><td>${q.status}</td><td class="num">${fmtEUR(q.totalEur)}</td></tr>`).join(''); root.innerHTML = hero(labels.quotes, STR.quotesDesc) + panelTable(`<tr><th>${STR.thNumber}</th><th>${STR.thClient}</th><th>${STR.thStatus}</th><th class="right">${STR.thTotal}</th></tr>`, rows); }
function renderInvoices(root) { const rows = state.invoices.map(i => `<tr><td class="id">${escapeHTML(i.number)}</td><td>${escapeHTML(i.clientName || '')}</td><td>${i.status}</td><td class="mono">${i.dueDate}</td><td class="num">${fmtEUR(i.totalEur)}</td></tr>`).join(''); root.innerHTML = hero(labels.invoices, STR.invoicesDesc) + panelTable(`<tr><th>${STR.thNumber}</th><th>${STR.thClient}</th><th>${STR.thStatus}</th><th>${STR.thDueDate}</th><th class="right">${STR.thTotal}</th></tr>`, rows); }
function renderCatalog(root) { const rows = state.catalog.map(i => `<tr><td>${i.type}</td><td>${escapeHTML(i.category)}</td><td class="name">${escapeHTML(i.description)}</td><td>${escapeHTML(i.unit)}</td><td class="num">${fmtEUR(i.defaultUnitPriceEur)}</td></tr>`).join(''); root.innerHTML = hero(labels.catalog, STR.catalogDesc) + panelTable(`<tr><th>${STR.thType}</th><th>${STR.thCategory}</th><th>${STR.thDescription}</th><th>${STR.thUnit}</th><th class="right">${STR.thPrice}</th></tr>`, rows); }
let personaPollTimer;
async function refreshPersona() {
  state.persona = await api('/app/api/persona');
  if (state.active === 'persona') render();
  clearTimeout(personaPollTimer);
  if (state.persona.status === 'COMPILING') personaPollTimer = setTimeout(refreshPersona, 3000);
}

function renderPersona(root) {
  const p = state.persona || {};
  const sources = p.sources || [];
  const compiling = p.status === 'COMPILING';
  const sourceRows = sources.map(s => `<tr><td>${s.kind === 'FILE' ? '📄' : '📝'} ${escapeHTML(s.label)}</td><td>${s.compiled ? `<span class="muted">${STR.sourceSynced}</span>` : `<span>${STR.sourcePending}</span>`}</td><td class="mono muted">${fmtDate(s.createdAt)}</td><td class="right"><button class="btn btn--sm" data-del-source="${s.id}">${STR.remove}</button></td></tr>`).join('');
  root.innerHTML = `${hero(labels.persona, STR.personaDesc)}
    <div class="view__stats" style="margin-bottom:18px">
      <div class="stat"><div class="stat__label">${STR.thStatus}</div><div class="stat__value">${compiling ? STR.personaCompiling : escapeHTML(p.status || 'EMPTY')}</div></div>
      <div class="stat"><div class="stat__label">${STR.statVersion}</div><div class="stat__value">${p.version || 0}</div></div>
      <div class="stat"><div class="stat__label">${STR.statTokens}</div><div class="stat__value">${p.tokenEstimate || 0}</div></div>
      <div class="stat"><div class="stat__label">${STR.statUpdated}</div><div class="stat__value" style="font-size:16px">${escapeHTML(fmtDate(p.updatedAt))}</div></div>
    </div>

    <div class="panel" style="padding:18px;margin-bottom:18px">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">
        <h2 class="view__title" style="font-size:18px">${escapeHTML(STR.testBotTitle)}</h2>
        <button class="btn btn--sm" id="persona-chat-clear">${escapeHTML(STR.clear)}</button>
      </div>
      <p class="view__desc" style="margin-bottom:14px">${escapeHTML(STR.testBotDesc)}</p>
      <div class="chat__log" id="persona-chat-log"></div>
      <form class="chat__form" id="persona-chat-form">
        <input class="inp chat__input" id="persona-chat-input" placeholder="${escapeHTML(STR.chatPlaceholder)}" autocomplete="off" />
        <button class="btn btn--primary" type="submit" id="persona-chat-send">${escapeHTML(STR.send)}</button>
      </form>
    </div>

    <div class="panel" style="padding:18px;margin-bottom:18px">
      <h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(STR.addInfoTitle)}</h2>
      <p class="view__desc" style="margin-bottom:14px">${escapeHTML(STR.addInfoDesc)}</p>
      <form class="form" id="persona-note-form">
        <div class="form__row form__row--full">
          <label class="lbl" for="persona-note">${escapeHTML(STR.noteLabel)}</label>
          <textarea class="txt" id="persona-note" rows="4" placeholder="${escapeHTML(STR.notePlaceholder)}"></textarea>
        </div>
        <button class="btn btn--primary" type="submit">${escapeHTML(STR.addNote)}</button>
      </form>
      <div class="form__row form__row--full" style="margin-top:14px">
        <label class="lbl" for="persona-file">${escapeHTML(STR.fileLabel)}</label>
        <input class="inp" id="persona-file" type="file" accept=".pdf,.txt,.md,.markdown" />
        <div class="hint">${escapeHTML(STR.fileHint)}</div>
      </div>
    </div>

    <div class="panel" style="padding:18px;margin-bottom:18px">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div><h2 class="view__title" style="font-size:18px">${escapeHTML(STR.sourcesTitle)}</h2><p class="view__desc">${escapeHTML(STR.sourcesDesc)}</p></div>
        <button class="btn btn--sm" id="persona-rebuild" ${compiling ? 'disabled' : ''}>${escapeHTML(STR.rebuildAll)}</button>
      </div>
      <div class="tbl-wrap"><table class="tbl"><thead><tr><th>${STR.thSource}</th><th>${STR.thState}</th><th>${STR.thCreated}</th><th class="right">${STR.thActions}</th></tr></thead><tbody>${sourceRows || `<tr><td colspan="4"><div class="empty"><p class="empty__title">${escapeHTML(STR.noSourcesTitle)}</p><p class="empty__desc">${escapeHTML(STR.noSourcesDesc)}</p></div></td></tr>`}</tbody></table></div>
    </div>

    <div class="panel" style="padding:18px">
      <h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(STR.compiledTitle)}</h2>
      <p class="view__desc" style="margin-bottom:14px">${escapeHTML(STR.compiledDesc)}</p>
      <form class="form" id="persona-form">
        <div class="form__row form__row--full">
          <textarea class="txt" id="persona-text" rows="16" placeholder="${escapeHTML(STR.compiledPlaceholder)}">${escapeHTML(p.compiledInstructions || '')}</textarea>
        </div>
        <button class="btn btn--primary" type="submit">${escapeHTML(STR.saveManual)}</button>
      </form>
    </div>`;

  $('#persona-form').addEventListener('submit', async e => {
    e.preventDefault();
    const compiledInstructions = $('#persona-text').value.trim();
    state.persona = await api('/app/api/persona', { method: 'PUT', body: JSON.stringify({ compiledInstructions }) });
    toast(STR.personaSaved);
    render();
  });
  $('#persona-note-form').addEventListener('submit', async e => {
    e.preventDefault();
    const content = $('#persona-note').value.trim();
    if (!content) return;
    state.persona = await api('/app/api/persona/sources', { method: 'POST', body: JSON.stringify({ content }) });
    toast(STR.noteAdded);
    render();
    refreshPersona();
  });
  $('#persona-file').addEventListener('change', async e => {
    const file = e.target.files[0];
    if (!file) return;
    try {
      state.persona = await uploadPersonaFile(file);
      toast(STR.fileUploaded);
      render();
      refreshPersona();
    } catch (err) { toast(err.message || STR.uploadFailed); }
  });
  $('#persona-rebuild').addEventListener('click', async () => {
    state.persona = await api('/app/api/persona/rebuild', { method: 'POST', body: '{}' });
    toast(STR.rebuildStarted);
    render();
    refreshPersona();
  });
  $$('[data-del-source]').forEach(b => b.addEventListener('click', async () => {
    await api(`/app/api/persona/sources/${b.dataset.delSource}`, { method: 'DELETE' });
    await refreshPersona();
  }));

  renderPersonaChatLog(personaChatBusy);
  $('#persona-chat-clear').addEventListener('click', () => { state.personaChat = []; renderPersonaChatLog(); });
  $('#persona-chat-form').addEventListener('submit', async e => {
    e.preventDefault();
    if (personaChatBusy) return;
    const input = $('#persona-chat-input');
    const text = input.value.trim();
    if (!text) return;
    state.personaChat.push({ role: 'user', content: text });
    input.value = '';
    personaChatBusy = true;
    $('#persona-chat-send').disabled = true;
    renderPersonaChatLog(true);
    try {
      const res = await api('/app/api/persona/test', { method: 'POST', body: JSON.stringify({ messages: state.personaChat }) });
      state.personaChat.push({ role: 'assistant', content: res.reply });
    } catch (err) {
      state.personaChat.push({ role: 'assistant', content: STR.chatError });
    } finally {
      personaChatBusy = false;
      renderPersonaChatLog();
      const send = $('#persona-chat-send'); if (send) send.disabled = false;
      const inp = $('#persona-chat-input'); if (inp) inp.focus();
    }
  });
}

function renderPersonaChatLog(busy = false) {
  const log = $('#persona-chat-log');
  if (!log) return;
  const msgs = state.personaChat || [];
  if (!msgs.length && !busy) {
    log.innerHTML = `<div class="chat__empty">${escapeHTML(STR.chatEmpty)}</div>`;
    return;
  }
  log.innerHTML = msgs.map(m => `<div class="chat__msg chat__msg--${m.role === 'user' ? 'user' : 'bot'}">${escapeHTML(m.content)}</div>`).join('')
    + (busy ? `<div class="chat__msg chat__msg--bot chat__typing">${escapeHTML(STR.typing)}</div>` : '');
  log.scrollTop = log.scrollHeight;
}

async function uploadPersonaFile(file) {
  const form = new FormData();
  form.append('file', file);
  const res = await fetch('/app/api/persona/sources/file', { method: 'POST', headers: token ? { Authorization: `Bearer ${token}` } : {}, body: form });
  if (res.status === 401) { localStorage.removeItem('dashboardToken'); token = ''; renderLogin(); throw new Error('unauthorized'); }
  if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.error || `HTTP ${res.status}`); }
  return res.json();
}
function widgetSnippet(key) { return `<script src="${location.origin}/widget/widget.js" data-key="${key}" defer><\/script>`; }

function renderSettings(root) {
  const channels = state.me?.tenant?.channels || [];
  const wa = channels.find(c => c.platform === 'WHATSAPP');
  const ig = channels.find(c => c.platform === 'INSTAGRAM');
  const web = state.webWidget || { publicKey: null, allowedOrigins: [] };
  root.innerHTML = `${hero(labels.settings, STR.settingsDesc)}
    <div class="panel" style="padding:18px;margin-bottom:18px">
      <h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(STR.channelsTitle)}</h2>
      <p class="view__desc" style="margin-bottom:14px">${escapeHTML(STR.channelsDesc)}</p>
      <div class="tbl-wrap"><table class="tbl">
        <thead><tr><th>${escapeHTML(STR.colChannel)}</th><th>${escapeHTML(STR.colAccount)}</th><th>${escapeHTML(STR.colStatus)}</th><th class="right">${escapeHTML(STR.colActions)}</th></tr></thead>
        <tbody>
          <tr><td class="name">WhatsApp</td><td class="mono">${escapeHTML(wa?.displayName || wa?.externalId || '—')}</td><td>${wa ? escapeHTML(STR.connected) : `<span class="muted">${escapeHTML(STR.notConnected)}</span>`}</td><td class="right"></td></tr>
          <tr><td class="name">Instagram</td><td class="mono">${ig ? escapeHTML(ig.displayName ? '@' + ig.displayName : ig.externalId) : '—'}</td><td>${ig ? escapeHTML(STR.connected) : `<span class="muted">${escapeHTML(STR.notConnected)}</span>`}</td>
            <td class="right"><button class="btn btn--sm" id="ig-connect">${escapeHTML(ig ? STR.igReconnect : STR.igConnect)}</button></td></tr>
          <tr><td class="name">${escapeHTML(STR.webRowName)}</td><td class="mono">${web.publicKey ? escapeHTML(web.publicKey) : '—'}</td><td>${web.publicKey ? escapeHTML(STR.connected) : `<span class="muted">${escapeHTML(STR.notConnected)}</span>`}</td>
            <td class="right">${web.publicKey ? `<span class="muted">${escapeHTML(STR.webRegenerate)}</span>` : `<button class="btn btn--sm" id="web-generate">${escapeHTML(STR.webGenerate)}</button>`}</td></tr>
        </tbody>
      </table></div>
      <div class="hint" style="margin-top:10px">${escapeHTML(STR.igHint)}</div>
    </div>

    <div class="panel" style="padding:18px;margin-bottom:18px">
      <h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(STR.webTitle)}</h2>
      <p class="view__desc" style="margin-bottom:14px">${escapeHTML(STR.webDesc)}</p>
      ${web.publicKey ? `
        <div class="form__row form__row--full">
          <textarea class="txt mono" id="web-snippet" rows="2" readonly style="resize:none">${escapeHTML(widgetSnippet(web.publicKey))}</textarea>
        </div>
        <button class="btn btn--primary" id="web-copy" type="button" style="margin-top:10px">${escapeHTML(STR.webCopy)}</button>
        <form class="form" id="web-origins-form" style="margin-top:18px">
          <div class="form__row form__row--full">
            <label class="lbl" for="web-origins">${escapeHTML(STR.webOriginsLabel)}</label>
            <textarea class="txt mono" id="web-origins" rows="3" placeholder="https://www.yoursite.com">${escapeHTML((web.allowedOrigins || []).join('\n'))}</textarea>
            <div class="hint">${escapeHTML(STR.webOriginsHint)}</div>
          </div>
          <button class="btn btn--ghost" type="submit">${escapeHTML(STR.webOriginsSave)}</button>
        </form>
      ` : `<button class="btn btn--primary" id="web-generate-2" type="button">${escapeHTML(STR.webGenerate)}</button>`}
    </div>

    <div class="panel" style="padding:18px;margin-bottom:18px">
      <h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(I18N.t('common.lang.title'))}</h2>
      <p class="view__desc" style="margin-bottom:14px">${escapeHTML(I18N.t('common.lang.desc'))}</p>
      <div class="form__row" style="max-width:280px">
        <label class="lbl" for="ui-locale">${escapeHTML(I18N.t('common.lang.label'))}</label>
        <select class="sel" id="ui-locale">${I18N.SUPPORTED.map(l => `<option value="${l}" ${l === I18N.locale() ? 'selected' : ''}>${escapeHTML(I18N.LANG_NAMES[l] || l)}</option>`).join('')}</select>
      </div>
    </div>

    <div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.moreSettingsTitle)}</p><p class="empty__desc">${escapeHTML(STR.moreSettingsDesc)}</p></div></div>`;
  $('#ig-connect').addEventListener('click', connectInstagram);
  const localeSel = $('#ui-locale');
  if (localeSel) localeSel.addEventListener('change', async () => {
    const locale = localeSel.value;
    I18N.choose(locale);
    I18N.applyDom(document);
    try { await api('/app/api/settings/locale', { method: 'POST', body: JSON.stringify({ locale }) }); toast(I18N.t('common.lang.saved')); }
    catch { toast(I18N.t('common.lang.saveFailed')); }
    renderNav();
    render();
  });
  $$('#web-generate, #web-generate-2').forEach(b => b.addEventListener('click', generateWebWidget));
  const copyBtn = $('#web-copy');
  if (copyBtn) copyBtn.addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(widgetSnippet(web.publicKey)); toast(STR.webCopied); }
    catch { const t = $('#web-snippet'); t.select(); document.execCommand('copy'); toast(STR.webCopied); }
  });
  const originsForm = $('#web-origins-form');
  if (originsForm) originsForm.addEventListener('submit', async e => {
    e.preventDefault();
    const allowedOrigins = $('#web-origins').value.split('\n').map(s => s.trim()).filter(Boolean);
    try { state.webWidget = await api('/app/api/web-widget', { method: 'POST', body: JSON.stringify({ allowedOrigins }) }); toast(STR.webOriginsSaved); render(); }
    catch { toast(STR.webGenerateFailed); }
  });
}

async function generateWebWidget() {
  try {
    state.webWidget = await api('/app/api/web-widget', { method: 'POST', body: JSON.stringify({ allowedOrigins: [] }) });
    state.me = await api('/app/api/me');
    toast(STR.webGenerated);
    render();
  } catch { toast(STR.webGenerateFailed); }
}

async function connectInstagram() {
  let res;
  try { res = await api('/app/api/instagram/connect'); }
  catch (e) { toast(e.message === 'unauthorized' ? STR.sessionExpired : STR.igUnavailable); return; }
  const popup = window.open(res.authorizeUrl, 'ig-oauth', 'width=600,height=750');
  if (!popup) { toast(STR.igAllowPopups); return; }
  const onMsg = async ev => {
    if (ev.origin !== window.location.origin || ev.data?.type !== 'ig-oauth') return;
    window.removeEventListener('message', onMsg);
    if (ev.data.status === 'connected') {
      toast(STR.igConnected);
      state.me = await api('/app/api/me');
      if (state.active === 'settings') render();
    } else {
      toast(STR.igFailed(ev.data.reason));
    }
  };
  window.addEventListener('message', onMsg);
}

// When the OAuth popup lands back on /app/?ig=..., relay the outcome to the opener and close.
// Returns true if this load was an OAuth popup (so the normal app boot is skipped).
function handleOAuthPopup() {
  const params = new URLSearchParams(window.location.search);
  const ig = params.get('ig');
  if (!ig || !window.opener) return false;
  window.opener.postMessage({ type: 'ig-oauth', status: ig, reason: params.get('reason'), tenant: params.get('tenant') }, window.location.origin);
  window.close();
  return true;
}

function openDrawer(title, body, wide = false) { const root = $('#drawer'); $('.drawer__panel', root).classList.toggle('drawer__panel--wide', wide); $('#drawer-title').textContent = title; $('#drawer-body').innerHTML = ''; $('#drawer-body').appendChild(body); root.hidden = false; const close = () => { root.hidden = true; }; $$('[data-close]', root).forEach(b => b.onclick = close); }

async function init() {
  if (handleOAuthPopup()) return;
  I18N.applyDom(document);
  $('#btn-logout').addEventListener('click', () => { localStorage.removeItem('dashboardToken'); token = ''; renderLogin(); });
  $('#search').addEventListener('input', e => { state.search = e.target.value; render(); });
  $('#btn-new').addEventListener('click', () => {
    if (state.active === 'clients') return openClientForm();
    if (state.active === 'catalog') return openCatalogForm();
    if (state.active === 'quotes') return openQuoteForm();
    if (state.active === 'invoices') return openInvoiceForm();
    toast(STR.quickCreateSoon);
  });
  document.addEventListener('keydown', e => { if (e.key === '/' && !['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) { e.preventDefault(); $('#search').focus(); } });
  if (!token) return renderLogin();
  state.active = (location.hash || '').replace('#', '') || 'overview';
  try { await bootAuthed(); } catch { renderLogin(); }
}
init();
