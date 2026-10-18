/* Platform settings (/backoffice → Platform settings): the operational and secret settings that override
   `.env` without a rebuild, by category. Secrets stay masked until revealed; Clear goes back to `.env`. */

let platformSettings = { settings: [], drafts: {}, revealed: {}, clear: new Set(), updatedAt: null };

function applyPlatformSettingsPayload(payload) {
  platformSettings.settings = payload?.settings || [];
  platformSettings.updatedAt = payload?.updatedAt || null;
  platformSettings.drafts = {};
  platformSettings.revealed = {};
  platformSettings.clear = new Set();
}

async function loadPlatformSettings() {
  const payload = await api('/admin/api/platform-settings');
  applyPlatformSettingsPayload(payload);
}

function categoryLabel(category) {
  return T.platformSettings.categories?.[category] || category;
}

function formatUpdatedAt(iso) {
  if (!iso) return T.platformSettings.neverUpdated;
  return T.platformSettings.updatedAt({ at: fmtDate(iso) });
}

async function renderPlatformSettings() {
  if (!platformSettings.settings.length) {
    try {
      await loadPlatformSettings();
    } catch (e) {
      $('#view').innerHTML = `<div class="empty"><p class="empty__title">${escapeHTML(T.loadError)}</p><p class="empty__desc">${escapeHTML(e.message)}</p></div>`;
      return;
    }
  }
  const groups = platformSettings.settings.reduce((acc, s) => {
    (acc[s.category] ||= []).push(s);
    return acc;
  }, {});
  const order = ['whatsapp', 'instagram', 'google', 'openrouter', 'ratelimit', 'pdf', 'admin'];
  const categories = [...new Set([...order, ...Object.keys(groups)])].filter(c => groups[c]?.length);
  $('#view').innerHTML = `
    <div class="view__hero">
      <div>
        <h1 class="view__title">${escapeHTML(T.platformSettings.title)}</h1>
        <p class="view__desc">${escapeHTML(T.platformSettings.desc)}</p>
      </div>
      <div class="actions">
        <button class="btn btn--ghost" id="ps-reload" type="button">${escapeHTML(T.platformSettings.reload)}</button>
      </div>
    </div>
    <div class="settings-stack">
      <p class="hint">${escapeHTML(formatUpdatedAt(platformSettings.updatedAt))}</p>
      ${categories.length === 0 ? `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(T.platformSettings.empty)}</p></div></div>` : `${categories.map(cat => `
        <div class="panel">
          <div class="panel__head"><h2 class="panel__title">${escapeHTML(categoryLabel(cat))}</h2></div>
          <div>
            ${groups[cat].map(settingRowHtml).join('')}
          </div>
        </div>`).join('')}
      ${settingsSaveBarHtml()}`}
    </div>`;
  bindPlatformSettings();
}

// Empty drafts aren't saved (a secret's field starts empty), so they don't count as changes.
const settingsDirty = () => platformSettings.clear.size > 0
  || Object.values(platformSettings.drafts).some(v => v != null && String(v).trim() !== '');

const settingsSaveStatusHtml = dirty => (dirty
  ? `<span class="savebar__dot" aria-hidden="true"></span>${escapeHTML(T.platformSettings.unsaved)}`
  : escapeHTML(T.platformSettings.allSaved));

// Save follows the screen while there are changes, so it stays in reach at the end of a long page.
function settingsSaveBarHtml() {
  const dirty = settingsDirty();
  const off = dirty ? '' : ' disabled';
  return `<div class="savebar${dirty ? ' is-dirty' : ''}" id="ps-savebar">
    <p class="savebar__status" id="ps-status" aria-live="polite">${settingsSaveStatusHtml(dirty)}</p>
    <div class="actions"><button class="btn btn--ghost" type="button" id="ps-discard"${off}>${escapeHTML(T.platformSettings.discard)}</button><button class="btn btn--primary" type="button" id="ps-save"${off}>${escapeHTML(T.platformSettings.save)}</button></div>
  </div>`;
}

function paintSettingsSaveBar() {
  const bar = $('#ps-savebar');
  const dirty = settingsDirty();
  if (!bar || bar.classList.contains('is-dirty') === dirty) return;
  bar.classList.toggle('is-dirty', dirty);
  $('#ps-status', bar).innerHTML = settingsSaveStatusHtml(dirty);
  $$('button', bar).forEach(b => { b.disabled = !dirty; });
}

function settingRowHtml(s) {
  const draft = platformSettings.drafts[s.key];
  const revealed = platformSettings.revealed[s.key];
  const willClear = platformSettings.clear.has(s.key);
  const value = draft ?? revealed ?? (s.secret ? '' : s.value);
  const placeholder = s.secret ? (s.hasValue ? s.value : '') : '';
  const sourcePill = willClear
    ? `<span class="pill pill--warn">${escapeHTML(T.platformSettings.sourceEnv)}</span>`
    : s.source === 'override'
      ? `<span class="pill pill--accent">${escapeHTML(T.platformSettings.sourceOverride)}</span>`
      : `<span class="pill pill--info">${escapeHTML(T.platformSettings.sourceEnv)}</span>`;
  const hint = s.key === 'ADMIN_PASSWORD_HASH'
    ? `<div class="hint">${escapeHTML(T.platformSettings.passwordHint)}</div>`
    : '';
  return `<div class="settings-row" data-setting-key="${escapeHTML(s.key)}">
    <div>
      <div class="settings-row__key">${escapeHTML(s.key)}</div>
      <div class="settings-row__meta">${sourcePill}${s.secret ? `<span class="pill pill--warn">${escapeHTML(T.platformSettings.secretBadge)}</span>` : ''}</div>
    </div>
    <div class="settings-row__controls">
      <input class="inp inp--mono" data-setting-input type="${s.secret && !revealed ? 'password' : 'text'}"
        value="${escapeHTML(value)}" placeholder="${escapeHTML(placeholder)}" autocomplete="off" spellcheck="false" />
      ${hint}
    </div>
    <div class="settings-row__actions">
      ${s.secret ? `<button class="btn btn--sm btn--ghost" type="button" data-reveal="${escapeHTML(s.key)}">${escapeHTML(revealed ? T.platformSettings.hide : T.platformSettings.reveal)}</button>` : ''}
      ${s.source === 'override' || willClear ? `<button class="btn btn--sm btn--ghost" type="button" data-clear="${escapeHTML(s.key)}">${escapeHTML(T.platformSettings.clear)}</button>` : ''}
    </div>
  </div>`;
}

function bindPlatformSettings() {
  $$('[data-setting-input]').forEach(input => {
    input.addEventListener('input', () => {
      const key = input.closest('[data-setting-key]')?.dataset.settingKey;
      if (!key) return;
      platformSettings.drafts[key] = input.value;
      platformSettings.clear.delete(key);
      paintSettingsSaveBar();
    });
  });
  $('#ps-discard')?.addEventListener('click', async () => {
    const PS = T.platformSettings;
    if (!await confirmDialog({ title: PS.discardTitle, body: PS.discardBody, okLabel: PS.discard })) return;
    platformSettings.drafts = {};
    platformSettings.clear = new Set();
    renderPlatformSettings();
  });
  $$('[data-reveal]').forEach(btn => btn.addEventListener('click', async () => {
    const key = btn.dataset.reveal;
    if (platformSettings.revealed[key] != null) {
      delete platformSettings.revealed[key];
      renderPlatformSettings();
      return;
    }
    try {
      const res = await api(`/admin/api/platform-settings/reveal?key=${encodeURIComponent(key)}`);
      platformSettings.revealed[key] = res.value || '';
      delete platformSettings.drafts[key];
      renderPlatformSettings();
    } catch (e) { toast(T.error({ msg: e.message })); }
  }));
  $$('[data-clear]').forEach(btn => btn.addEventListener('click', () => {
    const key = btn.dataset.clear;
    platformSettings.clear.add(key);
    delete platformSettings.drafts[key];
    delete platformSettings.revealed[key];
    renderPlatformSettings();
  }));
  $('#ps-reload')?.addEventListener('click', async () => {
    try {
      const payload = await api('/admin/api/platform-settings/reload', { method: 'POST' });
      applyPlatformSettingsPayload(payload);
      toast(T.platformSettings.reloaded);
      renderPlatformSettings();
    } catch (e) { toast(T.error({ msg: e.message })); }
  });
  $('#ps-save')?.addEventListener('click', async () => {
    const updates = {};
    Object.entries(platformSettings.drafts).forEach(([key, value]) => {
      if (value == null) return;
      if (String(value).trim() === '') return;
      updates[key] = value;
    });
    try {
      const payload = await api('/admin/api/platform-settings', {
        method: 'PUT',
        body: JSON.stringify({ updates, clear: [...platformSettings.clear] }),
      });
      applyPlatformSettingsPayload(payload);
      toast(T.platformSettings.saved);
      renderPlatformSettings();
    } catch (e) { toast(T.error({ msg: e.message })); }
  });
}
