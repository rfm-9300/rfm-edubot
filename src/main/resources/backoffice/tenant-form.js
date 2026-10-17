/* The new and edit tenant form: name, language, model, rate limits, how many companies, the modules and the
   channel bindings, with Instagram's and WhatsApp's own connect flows. Also the OAuth popup's side: back on
   /backoffice, it hands Instagram's outcome to the page that opened it. */

// Module ids only — display labels come from the shared catalog (common.nav.<id>) at render time.
// Only `overview` is always on (see DashboardModules.alwaysOn) — every other module, messaging
// included, is opt-in so a tenant can be CRM-only.
const MODULES = [
  { id: 'overview', always: true },
  { id: 'conversations' },
  { id: 'contacts' },
  { id: 'settings' },
  { id: 'persona' },
  { id: 'clients' },
  { id: 'services' },
  { id: 'quotes' },
  { id: 'invoices' },
  { id: 'suppliers' },
  { id: 'employees' },
  { id: 'payments' },
  { id: 'catalog' },
  { id: 'ai-assistant' },
  { id: 'bookings' },
  { id: 'instagram' },
  // Opt-in: a new tenant starts without it, and tenants with no saved selection don't get it.
  { id: 'agents', optIn: true },
  { id: 'timesheets', optIn: true },
  { id: 'email', optIn: true },
];

function tenantForm(editing, back = null) {
  const wrap = document.createElement('form');
  wrap.className = 'form';
  wrap.innerHTML = `
    <div class="form__grid">
      <div class="form__row form__row--full"><label class="lbl">${escapeHTML(T.tenantNameLabel)}</label><input class="inp" id="t-name" value="${escapeHTML(editing?.name || '')}" /></div>
      <div class="form__row"><label class="lbl">${escapeHTML(T.tenantSlugLabel)}</label><input class="inp inp--mono" id="t-slug" value="${escapeHTML(editing?.slug || '')}" ${editing ? 'readonly' : ''} /></div>
      <div class="form__row"><label class="lbl">${escapeHTML(T.languageLabel)}</label><select class="sel" id="t-locale">${I18N.SUPPORTED.map(l => `<option value="${l}" ${(editing?.locale || I18N.DEFAULT) === l ? 'selected' : ''}>${escapeHTML(I18N.LANG_NAMES[l] || l)}</option>`).join('')}</select></div>
      <div class="form__row"><label class="lbl">${escapeHTML(T.modelOverride)}</label><input class="inp inp--mono" id="t-model" value="${escapeHTML(editing?.openrouterModel || '')}" placeholder="${escapeHTML(T.modelPlaceholder)}" /></div>
      <div class="form__row"><label class="lbl">${escapeHTML(T.ratePerHour)}</label><input class="inp inp--mono" id="t-hour" type="number" value="${editing?.rateLimitPerHour || 30}" /></div>
      <div class="form__row"><label class="lbl">${escapeHTML(T.ratePerDay)}</label><input class="inp inp--mono" id="t-day" type="number" value="${editing?.rateLimitPerDay || 200}" /></div>
      ${editing?.parentTenantId ? '' : `<div class="form__row form__row--full"><label class="lbl" for="t-companies">${escapeHTML(T.maxCompaniesLabel)}</label><input class="inp inp--mono" id="t-companies" type="number" min="1" max="20" value="${editing?.maxCompanies || 1}" /><div class="hint">${escapeHTML(T.maxCompaniesHint)}</div></div>`}
    </div>
    <div class="form__row">
      <div class="form__row-head">
        <span class="lbl" id="modules-label">${escapeHTML(T.modulesLabel)}</span>
        <div class="actions">
          <button class="btn btn--sm btn--ghost" type="button" id="modules-all">${escapeHTML(T.selectAllModules)}</button>
          <button class="btn btn--sm btn--ghost" type="button" id="modules-none">${escapeHTML(T.selectNoModules)}</button>
        </div>
      </div>
      <div class="panel"><div class="panel__body"><div class="form__checks" id="modules-box" role="group" aria-labelledby="modules-label"></div></div></div>
      <div class="hint">${escapeHTML(T.modulesHint)}</div>
    </div>
    <div class="form__row">
      <div class="form__row-head">
        <span class="lbl">${escapeHTML(T.channelsLabel)}</span>
        <div class="actions">
          ${editing && state.whatsAppSignup.enabled ? `<button class="btn btn--sm btn--ghost" type="button" id="wa-connect">${escapeHTML(T.connectWhatsApp)}</button>` : ''}
          ${editing ? `<button class="btn btn--sm btn--ghost" type="button" id="ig-connect">${escapeHTML(T.connectInstagram)}</button>` : ''}
          <button class="btn btn--sm btn--ghost" type="button" id="add-channel">${escapeHTML(T.addChannel)}</button>
        </div>
      </div>
      <div class="lines lines--channels" id="channels-box">
        <div class="lines__head"><span>${escapeHTML(T.colPlatform)}</span><span>${escapeHTML(T.colExternalId)}</span><span>${escapeHTML(T.colAccessToken)}</span><span></span></div>
        <div id="channels-body"></div>
      </div>
      <div class="hint">${escapeHTML(T.channelsHint)}</div>
    </div>`;
  // A new tenant starts with no channel row: bindings are optional and can be connected later.
  const existingChannels = editing ? tenantChannels(editing) : [];
  existingChannels.forEach(c => addChannelRow(wrap, c));
  renderChannelsEmpty(wrap);
  renderModulesBox(wrap, editing);
  $('#add-channel', wrap).addEventListener('click', () => addChannelRow(wrap));
  $('#modules-all', wrap).addEventListener('click', () => setAllModules(wrap, true));
  $('#modules-none', wrap).addEventListener('click', () => setAllModules(wrap, false));
  $('#wa-connect', wrap)?.addEventListener('click', () => connectWhatsApp(editing.slug));
  $('#ig-connect', wrap)?.addEventListener('click', () => connectInstagram(editing.slug));
  if (!editing) {
    let touched = false;
    $('#t-slug', wrap).addEventListener('input', () => { touched = true; });
    $('#t-name', wrap).addEventListener('input', () => { if (!touched) $('#t-slug', wrap).value = slugify($('#t-name', wrap).value); });
  }
  openDrawer({
    title: editing ? T.editTenant({ slug: editing.slug }) : T.newTenant,
    body: wrap,
    saveLabel: editing ? T.saveChanges : T.createTenant,
    back,
    async onSave() {
      const payload = {
        name: $('#t-name', wrap).value.trim(),
        locale: $('#t-locale', wrap).value,
        openrouterModel: $('#t-model', wrap).value.trim() || null,
        rateLimitPerHour: Number($('#t-hour', wrap).value || 30),
        rateLimitPerDay: Number($('#t-day', wrap).value || 200),
        channels: collectChannels(wrap, Boolean(editing)),
        enabledModules: collectModules(wrap),
      };
      const maxCompanies = $('#t-companies', wrap);
      if (maxCompanies) payload.maxCompanies = Number(maxCompanies.value || 1);
      if (!payload.name) { toast(T.nameRequired); return false; }
      if (payload.channels.some(c => c.platform === 'INSTAGRAM' && !editing && !c.accessToken)) { toast(T.igNeedsToken); return false; }
      try {
        let created = null;
        if (editing) {
          await api(`/admin/api/tenants/${encodeURIComponent(editing.slug)}`, { method: 'PUT', body: JSON.stringify(payload) });
          toast(T.tenantUpdated);
        } else {
          const slug = $('#t-slug', wrap).value.trim();
          created = (await api('/admin/api/tenants', { method: 'POST', body: JSON.stringify({ ...payload, slug }) }))?.slug || slug;
          toast(T.botCreated);
        }
        await loadAll();
        renderTenants();
        if (created) return () => tenantDrawer(created);
      } catch (e) {
        const slug = $('#t-slug', wrap).value.trim();
        if (e.code === 'slug_taken') toast(e.detail === 'DELETED' ? T.slugTakenDeleted({ slug }) : T.slugTaken({ slug }));
        else toast(T.error({ msg: e.message }));
        return false;
      }
    },
  });
}

function selectedModulesFor(editing) {
  const selected = new Set(editing?.effectiveModules || MODULES.filter(m => !m.optIn).map(m => m.id));
  return MODULES.filter(m => m.always || selected.has(m.id)).map(m => m.id);
}

function renderModulesBox(root, editing) {
  const selected = new Set(selectedModulesFor(editing));
  $('#modules-box', root).innerHTML = MODULES.map(m => `<label class="form__check">
      <input type="checkbox" data-module="${m.id}" ${selected.has(m.id) ? 'checked' : ''} ${m.always ? 'disabled' : ''} /> ${escapeHTML(I18N.t('common.nav.' + m.id))}
    </label>`).join('');
}

function collectModules(root) {
  return $$('[data-module]', root).filter(input => input.checked || input.disabled).map(input => input.dataset.module);
}

function setAllModules(root, checked) {
  $$('[data-module]', root).filter(input => !input.disabled).forEach(input => { input.checked = checked; });
}

// Channels are optional — show a placeholder instead of an empty table when there is no binding.
function renderChannelsEmpty(root) {
  const body = $('#channels-body', root);
  const empty = $('#channels-empty', root);
  if (body.children.length) { empty?.remove(); return; }
  if (empty) return;
  const el = document.createElement('div');
  el.id = 'channels-empty';
  el.className = 'lines__empty';
  el.textContent = T.noChannels;
  body.parentElement.appendChild(el);
}

function addChannelRow(root, channel = { platform: 'WHATSAPP', externalId: '', hasAccessToken: false }) {
  const row = document.createElement('div');
  row.className = 'line channel-row';
  row.innerHTML = `
    <select class="sel" data-channel-platform aria-label="${escapeHTML(T.colPlatform)}">
      ${['WHATSAPP', 'INSTAGRAM'].map(p => `<option value="${p}" ${channel.platform === p ? 'selected' : ''}>${escapeHTML(platformLabel(p))}</option>`).join('')}
    </select>
    <input class="mono" data-channel-external value="${escapeHTML(channel.externalId || '')}" placeholder="${escapeHTML(T.chExternalPlaceholder)}" aria-label="${escapeHTML(T.colExternalId)}" />
    <input class="mono" data-channel-token type="password" placeholder="${channel.hasAccessToken ? escapeHTML(T.chTokenUnchanged) : escapeHTML(T.chTokenPlaceholder)}" aria-label="${escapeHTML(T.colAccessToken)}" />
    <button class="l-rm" type="button" aria-label="${escapeHTML(T.removeChannel)}">×</button>
  `;
  row.querySelector('.l-rm').addEventListener('click', () => { row.remove(); renderChannelsEmpty(root); });
  $('#channels-body', root).appendChild(row);
  renderChannelsEmpty(root);
}

function collectChannels(root, editing) {
  return $$('.channel-row', root).map(row => ({
    platform: row.querySelector('[data-channel-platform]').value,
    externalId: row.querySelector('[data-channel-external]').value.trim(),
    accessToken: row.querySelector('[data-channel-token]').value.trim(),
  })).filter(c => c.externalId);
}

async function connectInstagram(slug) {
  let res;
  try {
    res = await api(`/admin/api/tenants/${encodeURIComponent(slug)}/instagram/connect`);
  } catch (e) {
    toast(e.message === 'unauthorized' ? T.igSessionExpired : T.igNotConfigured);
    return;
  }
  const popup = window.open(res.authorizeUrl, 'ig-oauth', 'width=600,height=750');
  if (!popup) { toast(T.igAllowPopups); return; }
  const onMessage = async ev => {
    if (ev.origin !== window.location.origin || ev.data?.type !== 'ig-oauth') return;
    window.removeEventListener('message', onMessage);
    if (ev.data.status === 'connected') {
      toast(T.igConnected);
      await loadAll();
      renderTenants();
    } else {
      toast(T.igFailed({ reason: ev.data.reason }));
    }
  };
  window.addEventListener('message', onMessage);
}
let fbSdkPromise;

function loadFacebookSdk(appId, graphVersion) {
  if (window.FB) {
    window.FB.init({ appId, version: graphVersion, cookie: true, xfbml: false });
    return Promise.resolve(window.FB);
  }
  if (fbSdkPromise) return fbSdkPromise;
  fbSdkPromise = new Promise((resolve, reject) => {
    window.fbAsyncInit = () => {
      window.FB.init({ appId, version: graphVersion, cookie: true, xfbml: false });
      resolve(window.FB);
    };
    const script = document.createElement('script');
    script.id = 'facebook-jssdk';
    script.src = 'https://connect.facebook.net/en_US/sdk.js';
    script.async = true;
    script.defer = true;
    script.onerror = () => reject(new Error('facebook_sdk_load_failed'));
    document.body.appendChild(script);
  });
  return fbSdkPromise;
}

function waitForWhatsAppSignupMessage() {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      window.removeEventListener('message', onMessage);
      reject(new Error('signup_message_timeout'));
    }, 5 * 60 * 1000);
    const onMessage = ev => {
      if (!['https://www.facebook.com', 'https://web.facebook.com'].includes(ev.origin)) return;
      const data = typeof ev.data === 'string' ? (() => { try { return JSON.parse(ev.data); } catch (_) { return null; } })() : ev.data;
      if (data?.type !== 'WA_EMBEDDED_SIGNUP') return;
      if (data.event && data.event !== 'FINISH') {
        clearTimeout(timer);
        window.removeEventListener('message', onMessage);
        reject(new Error(data.event === 'CANCEL' ? 'signup_cancelled' : 'signup_not_finished'));
        return;
      }
      const wabaId = data.data?.waba_id || data.data?.wabaId;
      const phoneNumberId = data.data?.phone_number_id || data.data?.phoneNumberId;
      if (!wabaId || !phoneNumberId) return;
      clearTimeout(timer);
      window.removeEventListener('message', onMessage);
      resolve({ wabaId, phoneNumberId });
    };
    window.addEventListener('message', onMessage);
  });
}

function facebookLoginForBusiness(FB, configId) {
  return new Promise((resolve, reject) => {
    FB.login(response => {
      const code = response?.authResponse?.code;
      if (!code) {
        reject(new Error(response?.status === 'not_authorized' ? 'not_authorized' : 'missing_code'));
        return;
      }
      resolve(code);
    }, {
      config_id: configId,
      response_type: 'code',
      override_default_response_type: true,
      extras: { setup: {} },
    });
  });
}

async function connectWhatsApp(slug) {
  const cfg = state.whatsAppSignup;
  if (!cfg?.enabled) { toast(T.waNotConfigured); return; }
  try {
    const FB = await loadFacebookSdk(cfg.appId, cfg.graphVersion || 'v21.0');
    toast(T.waOpenPopup);
    const sessionPromise = waitForWhatsAppSignupMessage();
    const codePromise = facebookLoginForBusiness(FB, cfg.configId);
    const [session, code] = await Promise.all([sessionPromise, codePromise]);
    await api(`/admin/api/tenants/${encodeURIComponent(slug)}/whatsapp/connect`, {
      method: 'POST',
      body: JSON.stringify({ code, wabaId: session.wabaId, phoneNumberId: session.phoneNumberId }),
    });
    toast(T.waConnected);
    await loadAll();
    renderTenants();
  } catch (e) {
    const messages = {
      signup_cancelled: T.waCancelled,
      missing_code: T.waMissingCode,
      signup_message_timeout: T.waTimeout,
      facebook_sdk_load_failed: T.waSdkFailed,
    };
    toast(messages[e.message] || T.waFailed({ msg: e.message }));
  }
}

// When the OAuth popup lands back on /backoffice/?ig=..., relay the outcome to the opener and close.
// Returns true if this load was an OAuth popup (so the normal app boot is skipped).
function handleOAuthPopup() {
  const params = new URLSearchParams(window.location.search);
  const ig = params.get('ig');
  if (!ig || !window.opener) return false;
  window.opener.postMessage({ type: 'ig-oauth', status: ig, reason: params.get('reason'), tenant: params.get('tenant') }, window.location.origin);
  window.close();
  return true;
}
